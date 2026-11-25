// Cenda locomotion adaptation (OpenMason #159) conformance: foot placement (the batched ground query's layout,
// steps, holes, the pelvis drop and its clamp, surface alignment within its limit, world-space foot locks and their
// release, legs without a foot joint, host failure), stride warping (feet scaled along the travel direction, the
// pelvis drop that lets stretched legs reach), orientation warping (hips toward the movement, chest kept forward,
// clamp and easing), and through the animation graph: play rate within its band + stride scale, foot locks from
// curves == from sync markers, determinism, and the ACCEPTANCE: a flat-ground walk on two staircases keeps every
// contact foot within 0.01 of its tread. Ends with the drift bench (contact-foot slip per step: play rate alone vs
// stride warping vs stride warping + foot locks). Plain asserts.

#include "cenda/anim_graph.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <random>
#include <string>
#include <vector>

namespace {

int failures = 0;

#define CHECK(cond)                                                                       \
    do {                                                                                  \
        if (!(cond)) {                                                                    \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); \
            ++failures;                                                                   \
        }                                                                                 \
    } while (0)

constexpr double PI = 3.14159265358979323846;

struct V {
    double x = 0, y = 0, z = 0;
};
V sub(V a, V b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
double dot(V a, V b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
double len(V a) { return std::sqrt(dot(a, a)); }
double hlen(V a) { return std::sqrt(a.x * a.x + a.z * a.z); }

// ---- the test biped (blocks): hips 1.0 up, legs 0.45 + 0.45, ankles 0.1 above the ground, feet 0.2 long ------

enum { HIPS, SPINE, CHEST, UL, LL, FL, UR, LR, FR, JOINTS };
const char* kNames[JOINTS] = {"hips", "spine", "chest", "upper_leg_l", "lower_leg_l", "foot_l",
                              "upper_leg_r", "lower_leg_r", "foot_r"};
constexpr float kHipsY = 1.0f, kHalfHips = 0.15f, kThigh = 0.45f, kShin = 0.45f;
constexpr double kAnkle = 0.1;

cr_joint_desc bone(int parent, float px, float py, float pz, float ex, float ey, float ez) {
    cr_joint_desc d{};
    d.parent = parent;
    d.kind = CR_JOINT_BONE;
    d.flags = CR_JOINT_HAS_GEOMETRY;
    d.pos[0] = px;
    d.pos[1] = py;
    d.pos[2] = pz;
    d.scale[0] = d.scale[1] = d.scale[2] = 1.f;
    d.endpoint[0] = ex;
    d.endpoint[1] = ey;
    d.endpoint[2] = ez;
    return d;
}

std::vector<cr_joint_desc> biped_joints() {
    return {bone(-1, 0, kHipsY, 0, 0, 0, 0),         bone(HIPS, 0, 0, 0, 0, 0.3f, 0),
            bone(SPINE, 0, 0, 0, 0, 0.3f, 0),        bone(HIPS, -kHalfHips, 0, 0, 0, -kThigh, 0),
            bone(UL, 0, 0, 0, 0, -kShin, 0),         bone(LL, 0, 0, 0, 0, -0.1f, -0.2f),
            bone(HIPS, kHalfHips, 0, 0, 0, -kThigh, 0), bone(UR, 0, 0, 0, 0, -kShin, 0),
            bone(LR, 0, 0, 0, 0, -0.1f, -0.2f)};
}

cr_rig* biped() {
    const std::vector<cr_joint_desc> j = biped_joints();
    return cr_rig_create(j.data(), JOINTS, kNames, kNames);
}

// Column-major 4x4.
struct M {
    float m[16];
};
M translate(double x, double y, double z, double yaw_deg = 0.0, double scale = 1.0) {
    const double c = std::cos(yaw_deg * PI / 180.0) * scale, s = std::sin(yaw_deg * PI / 180.0) * scale;
    M r{};
    r.m[0] = static_cast<float>(c);
    r.m[2] = static_cast<float>(-s);
    r.m[5] = static_cast<float>(scale);
    r.m[8] = static_cast<float>(s);
    r.m[10] = static_cast<float>(c);
    r.m[12] = static_cast<float>(x);
    r.m[13] = static_cast<float>(y);
    r.m[14] = static_cast<float>(z);
    r.m[15] = 1.f;
    return r;
}

V col(const float* w, int j, int c) {
    const float* m = w + static_cast<size_t>(j) * 16 + c * 4;
    return {m[0], m[1], m[2]};
}
V at(const std::vector<float>& w, int j) { return col(w.data(), j, 3); }

// ---- ground ------------------------------------------------------------------------------------------------

struct Ground {
    std::function<double(double, double)> height = [](double, double) { return 0.0; };
    std::function<V(double, double)> normal;  // null = up
    int calls = 0;
    bool fail = false;
    std::vector<cr_ground_probe> seen;
};

int32_t ground_fn(void* user, const cr_ground_probe* p, int32_t n, cr_ground_hit* out) {
    auto* g = static_cast<Ground*>(user);
    ++g->calls;
    if (g->fail) return -1;
    g->seen.assign(p, p + n);
    for (int32_t i = 0; i < n; ++i) {
        const double h = g->height(p[i].origin[0], p[i].origin[2]);
        const double t = (p[i].origin[1] - h) / -p[i].direction[1];
        if (!(t >= 0.0 && t <= p[i].max_distance)) continue;
        out[i].hit = 1;
        out[i].surface = 7;
        out[i].point[0] = p[i].origin[0];
        out[i].point[1] = h;
        out[i].point[2] = p[i].origin[2];
        if (g->normal) {
            const V nn = g->normal(p[i].origin[0], p[i].origin[2]);
            out[i].normal[0] = static_cast<float>(nn.x);
            out[i].normal[1] = static_cast<float>(nn.y);
            out[i].normal[2] = static_cast<float>(nn.z);
        }
    }
    return 0;
}

// ---- modifier stacks -----------------------------------------------------------------------------------------

cr_modifier_desc legs_mod(int type, bool feet = true) {
    cr_modifier_desc d{};
    d.type = type;
    for (int& j : d.joints) j = -1;
    d.target = d.pole = -1;
    d.weight = 1.f;
    const int js[] = {HIPS, UL, LL, feet ? FL : -1, UR, LR, feet ? FR : -1};
    for (int j : js) d.joints[d.joint_count++] = j;
    return d;
}

void rest(cr_poses* p) {
    cr_pose_op op{};
    op.op = CR_OP_REST;
    op.dst = 0;
    CHECK(cr_poses_run(p, &op, 1) == CR_OK);
}

// The rest pose with local Euler X rotations per joint (and a hips height).
void posed(cr_poses* p, cr_rig* rig, const std::vector<std::pair<int, float>>& rx, float hips_y = kHipsY) {
    rest(p);
    std::vector<float> l(JOINTS * 9);
    CHECK(cr_poses_get(p, 0, l.data(), nullptr) == CR_OK);
    for (auto [j, a] : rx) l[static_cast<size_t>(j) * 9 + 3] = a;
    l[HIPS * 9 + 1] = hips_y;
    CHECK(cr_poses_set(p, 0, l.data(), nullptr) == CR_OK);
    (void)rig;
}

std::vector<float> worlds(cr_poses* p, const M* base = nullptr) {
    std::vector<float> w(JOINTS * 16);
    CHECK(cr_poses_compose(p, 0, nullptr, 0, base != nullptr ? base->m : nullptr, nullptr, w.data(), nullptr) == CR_OK);
    return w;
}

std::vector<float> childs(cr_poses* p, const M* base = nullptr) {
    std::vector<float> c(JOINTS * 16);
    CHECK(cr_poses_compose(p, 0, nullptr, 0, base != nullptr ? base->m : nullptr, nullptr, nullptr, c.data()) == CR_OK);
    return c;
}

void apply(cr_modstack* s, cr_poses* p, float dt, const M* frame = nullptr) {
    CHECK(cr_modstack_set_frame(s, frame != nullptr ? frame->m : nullptr) == CR_OK);
    CHECK(cr_modstack_apply(s, p, 0, dt) == CR_OK);
}

void test_probe_layout_and_flat_ground() {
    cr_rig* rig = biped();
    cr_poses* p = cr_poses_create(rig, 1);
    cr_modifier_desc d = legs_mod(CR_MOD_FOOT_PLACEMENT);
    cr_modstack* s = cr_modstack_create(rig, &d, 1, 0);
    CHECK(s != nullptr);
    Ground g;
    CHECK(cr_modstack_set_ground(s, ground_fn, &g) == CR_OK);
    // Flat ground at the clips' ground: nothing moves.
    rest(p);
    const std::vector<float> before = worlds(p);
    apply(s, p, 0.f);
    const std::vector<float> after = worlds(p);
    for (int j = 0; j < JOINTS; ++j) CHECK(len(sub(at(after, j), at(before, j))) < 1e-6);
    CHECK(cr_modstack_get_value(s, 0, CR_VALUE_OUT_PELVIS) == 0.f);
    // One call; heel then toe per leg, in leg order; straight down from probe_up (auto: the 0.9 leg) above
    // the ground to probe_down (0.75 x 0.9) below it.
    CHECK(g.calls == 1 && g.seen.size() == 4);
    const double expect_x[4] = {-kHalfHips, -kHalfHips, kHalfHips, kHalfHips};
    const double expect_z[4] = {0.0, -0.2, 0.0, -0.2};
    for (int i = 0; i < 4 && g.seen.size() == 4; ++i) {
        const cr_ground_probe& pr = g.seen[static_cast<size_t>(i)];
        CHECK(pr.foot == i / 2 && pr.point == i % 2);
        CHECK(std::fabs(pr.origin[0] - expect_x[i]) < 1e-6 && std::fabs(pr.origin[2] - expect_z[i]) < 1e-6);
        CHECK(std::fabs(pr.origin[1] - 0.9) < 1e-6);
        CHECK(pr.direction[0] == 0.0 && pr.direction[1] == -1.0 && pr.direction[2] == 0.0);
        CHECK(std::fabs(pr.max_distance - 0.9 * 1.75) < 1e-6);
    }
    // Through a scaled, turned, moved frame: probes are world space, the distance scales with the frame.
    const M f = translate(10, 5, -3, 90, 2);
    rest(p);
    apply(s, p, 0.f, &f);
    CHECK(g.seen.size() == 4);
    if (g.seen.size() == 4) {
        // Model (-0.15, 0.45, 0) -> rotated 90 degrees about +Y (x' = z, z' = -x), x2, moved.
        CHECK(std::fabs(g.seen[0].origin[0] - 10.0) < 1e-5 && std::fabs(g.seen[0].origin[2] - (-3.0 + 0.3)) < 1e-5);
        CHECK(std::fabs(g.seen[0].origin[1] - (5.0 + 1.8)) < 1e-5);
        CHECK(std::fabs(g.seen[0].max_distance - 0.9 * 1.75 * 2.0) < 1e-5);
        CHECK(std::fabs(g.seen[0].direction[1] + 1.0) < 1e-9);
    }
    // No ground function: the flat plane, no call.
    CHECK(cr_modstack_set_ground(s, nullptr, nullptr) == CR_OK);
    rest(p);
    apply(s, p, 0.f);
    CHECK(g.calls == 2);
    // A failing host aborts the apply.
    g.fail = true;
    CHECK(cr_modstack_set_ground(s, ground_fn, &g) == CR_OK);
    rest(p);
    CHECK(cr_modstack_apply(s, p, 0, 0.f) == CR_ERR_HOST);
    // Values: a type reads only its own keys.
    CHECK(cr_modstack_set_value(s, 0, CR_VALUE_LOCK + 1, 1.f) == CR_OK);
    CHECK(cr_modstack_set_value(s, 0, CR_VALUE_LOCK + 2, 1.f) == CR_ERR_RANGE);  // two legs only
    CHECK(cr_modstack_set_value(s, 0, CR_VALUE_ANGLE, 1.f) == CR_ERR_RANGE);
    CHECK(cr_modstack_set_value(s, 0, CR_VALUE_MAX_ALIGN, NAN) == CR_ERR_ARG);
    CHECK(cr_modstack_get_value(s, 0, CR_VALUE_UNLOCK_TIME) == 0.15f);
    cr_modstack_destroy(s);
    cr_poses_destroy(p);
    cr_rig_destroy(rig);
}

void test_steps_holes_and_alignment() {
    cr_rig* rig = biped();
    cr_poses* p = cr_poses_create(rig, 1);
    cr_modifier_desc d = legs_mod(CR_MOD_FOOT_PLACEMENT);
    cr_modstack* s = cr_modstack_create(rig, &d, 1, 0);
    Ground g;
    cr_modstack_set_ground(s, ground_fn, &g);
    auto run = [&](std::function<double(double, double)> h) {
        g.height = std::move(h);
        cr_modstack_reset(s);
        rest(p);
        apply(s, p, 0.f);
        return worlds(p);
    };
    // A step under the left foot: it rises onto it, the right foot and the pelvis stay.
    std::vector<float> w = run([](double x, double) { return x < 0 ? 0.2 : 0.0; });
    CHECK(std::fabs(at(w, FL).y - (kAnkle + 0.2)) < 1e-4);
    CHECK(std::fabs(at(w, FR).y - kAnkle) < 1e-6);
    CHECK(std::fabs(at(w, HIPS).y - kHipsY) < 1e-6);
    CHECK(len(sub(at(w, LL), at(w, UL))) > 0.4499 && at(w, LL).z < -0.01);  // no stretch; the knee bends forward
    // A hole under the left foot: the pelvis drops so it reaches; the right knee bends.
    w = run([](double x, double) { return x < 0 ? -0.3 : 0.0; });
    CHECK(std::fabs(cr_modstack_get_value(s, 0, CR_VALUE_OUT_PELVIS) + 0.3f) < 1e-6f);
    CHECK(std::fabs(at(w, HIPS).y - (kHipsY - 0.3)) < 1e-6);
    CHECK(std::fabs(at(w, FL).y - (kAnkle - 0.3)) < 1e-4);
    CHECK(std::fabs(at(w, FR).y - kAnkle) < 1e-4);
    // Deeper than the drop limit (auto: half the leg): the pelvis stops there.
    w = run([](double x, double) { return x < 0 ? -0.6 : 0.0; });  // (within probe_down: 0.675)
    CHECK(std::fabs(cr_modstack_get_value(s, 0, CR_VALUE_OUT_PELVIS) + 0.45f) < 1e-6f);
    // A stair edge under the left foot (heel over the lower tread, toe over the higher): it sits on the higher.
    w = run([](double x, double z) { return x < 0 && z < -0.1 ? 0.25 : 0.0; });
    CHECK(std::fabs(at(w, FL).y - (kAnkle + 0.25)) < 1e-4);
    CHECK(std::fabs(cr_modstack_get_value(s, 0, CR_VALUE_OUT_GROUND) - 0.25f) < 1e-6f);
    // A 20 degree slope (rising toward +X): both feet roll 20 degrees; 50 degrees is clamped to 30.
    for (double slope : {20.0, 50.0}) {
        const double r = slope * PI / 180.0;
        g.normal = [r](double, double) { return V{-std::sin(r), std::cos(r), 0.0}; };
        rest(p);
        const std::vector<float> w0 = worlds(p);
        w = run([](double, double) { return 0.0; });
        // The foot's X axis (across the foot) tilts by the aligned angle.
        const V x0 = col(w0.data(), FL, 0), x1 = col(w.data(), FL, 0);
        const double tilt = std::acos(std::clamp(dot(x0, x1) / (len(x0) * len(x1)), -1.0, 1.0)) * 180.0 / PI;
        CHECK(std::fabs(tilt - std::min(slope, 30.0)) < 1e-3);
        CHECK(x1.y > 0.0);
    }
    g.normal = nullptr;
    cr_modstack_destroy(s);
    cr_poses_destroy(p);
    cr_rig_destroy(rig);
}

void test_locks() {
    cr_rig* rig = biped();
    cr_poses* p = cr_poses_create(rig, 1);
    cr_modifier_desc d = legs_mod(CR_MOD_FOOT_PLACEMENT);
    cr_modstack* s = cr_modstack_create(rig, &d, 1, 0);
    // Flat plane (no ground function). The character slides backward 2 cm a frame: a locked foot stays put.
    cr_modstack_set_value(s, 0, CR_VALUE_LOCK + 0, 1.f);
    V planted;
    for (int f = 0; f < 10; ++f) {
        const M frame = translate(0, 0, 0.02 * f);
        rest(p);
        apply(s, p, 0.05f, &frame);
        const std::vector<float> w = worlds(p, &frame);
        if (f == 0) planted = at(w, FL);
        CHECK(len(sub(at(w, FL), planted)) < 1e-4);
        CHECK(std::fabs(at(w, FR).z - 0.02 * f) < 1e-5);  // the free foot rides along
        CHECK(cr_modstack_get_value(s, 0, CR_VALUE_OUT_LOCKED) == 1.f);
    }
    // Release: the pull fades out over 0.15 s (3 frames of 0.05 s).
    cr_modstack_set_value(s, 0, CR_VALUE_LOCK + 0, 0.f);
    const float fades[] = {2.f / 3.f, 1.f / 3.f, 0.f};
    for (float want : fades) {
        const M frame = translate(0, 0, 0.18);
        rest(p);
        apply(s, p, 0.05f, &frame);
        CHECK(cr_modstack_get_value(s, 0, CR_VALUE_OUT_LOCKED) == 0.f);
        CHECK(std::fabs(cr_modstack_get_value(s, 0, CR_VALUE_OUT_ALPHA) - want) < 1e-5f);
    }
    // A lock further than max_lock_distance from the live foot lets go.
    cr_modstack_set_value(s, 0, CR_VALUE_LOCK + 0, 1.f);
    cr_modstack_set_value(s, 0, CR_VALUE_UNLOCK_TIME, 0.f);
    M frame = translate(0, 0, 0);
    rest(p);
    apply(s, p, 0.05f, &frame);
    CHECK(cr_modstack_get_value(s, 0, CR_VALUE_OUT_LOCKED) == 1.f);
    frame = translate(0, 0, 0.6);  // > 0.45 away
    rest(p);
    apply(s, p, 0.05f, &frame);
    CHECK(cr_modstack_get_value(s, 0, CR_VALUE_OUT_LOCKED) == 0.f);
    // A partial lock pulls part way.
    cr_modstack_reset(s);
    cr_modstack_set_value(s, 0, CR_VALUE_LOCK + 0, 0.5f);
    frame = translate(0, 0, 0);
    rest(p);
    apply(s, p, 0.f, &frame);
    frame = translate(0, 0, 0.2);
    rest(p);
    apply(s, p, 0.f, &frame);
    CHECK(std::fabs(at(worlds(p, &frame), FL).z - 0.1) < 1e-4);
    cr_modstack_destroy(s);
    cr_poses_destroy(p);
    cr_rig_destroy(rig);
}

void test_leg_without_foot() {
    cr_rig* rig = biped();
    cr_poses* p = cr_poses_create(rig, 1);
    cr_modifier_desc d = legs_mod(CR_MOD_FOOT_PLACEMENT, false);
    cr_modstack* s = cr_modstack_create(rig, &d, 1, 0);
    CHECK(s != nullptr);
    Ground g;
    g.height = [](double x, double) { return x < 0 ? -0.2 : 0.1; };
    cr_modstack_set_ground(s, ground_fn, &g);
    rest(p);
    apply(s, p, 0.f);
    CHECK(g.seen.size() == 2);  // ankles only
    // The lower leg's tail is the ankle: left down 0.2 (the pelvis drops), right up 0.1.
    const std::vector<float> c = childs(p);
    CHECK(std::fabs(at(c, LL).y - (kAnkle - 0.2)) < 1e-4);
    CHECK(std::fabs(at(c, LR).y - (kAnkle + 0.1)) < 1e-4);
    cr_modstack_destroy(s);
    cr_poses_destroy(p);
    cr_rig_destroy(rig);
}

void test_stride_warp() {
    cr_rig* rig = biped();
    cr_poses* p = cr_poses_create(rig, 1);
    cr_modifier_desc d = legs_mod(CR_MOD_STRIDE_WARP);
    cr_modstack* s = cr_modstack_create(rig, &d, 1, 0);
    CHECK(s != nullptr);
    // Mid-stride: left leg 20 degrees forward, right 20 back, both knees a little bent.
    const std::vector<std::pair<int, float>> pose = {{UL, 20.f}, {LL, -10.f}, {UR, -20.f}, {LR, -10.f}};
    posed(p, rig, pose);
    const std::vector<float> w0 = worlds(p);
    for (double scale : {1.0, 0.6, 1.3}) {
        cr_modstack_set_value(s, 0, CR_VALUE_SCALE, static_cast<float>(scale));
        posed(p, rig, pose);
        apply(s, p, 0.f);
        const std::vector<float> w = worlds(p);
        const double drop = cr_modstack_get_value(s, 0, CR_VALUE_OUT_PELVIS);
        for (int foot : {FL, FR}) {
            const int thigh = foot == FL ? UL : UR;
            // Along -Z the foot's offset from its (unmoved-pelvis) thigh scales; across and up it keeps.
            const double before = -(at(w0, foot).z - at(w0, thigh).z);
            const double after = -(at(w, foot).z - at(w0, thigh).z);
            CHECK(std::fabs(after - before * scale) < 1e-4);
            CHECK(std::fabs(at(w, foot).x - at(w0, foot).x) < 1e-4 && std::fabs(at(w, foot).y - at(w0, foot).y) < 1e-4);
            // The feet keep their animated rotation.
            const V a = col(w0.data(), foot, 2), b = col(w.data(), foot, 2);
            CHECK(len(sub(a, b)) < 1e-4);
        }
        if (scale <= 1.0) CHECK(drop == 0.0);
        if (scale > 1.2) CHECK(drop < 0.0 && std::fabs(at(w, HIPS).y - (kHipsY + drop)) < 1e-6);
    }
    cr_modstack_destroy(s);
    cr_poses_destroy(p);
    cr_rig_destroy(rig);
}

void test_orientation_warp() {
    cr_rig* rig = biped();
    cr_poses* p = cr_poses_create(rig, 1);
    cr_modifier_desc d{};
    d.type = CR_MOD_ORIENT_WARP;
    for (int& j : d.joints) j = -1;
    d.target = d.pole = -1;
    d.weight = 1.f;
    d.joints[0] = HIPS;
    d.joints[1] = SPINE;
    d.joints[2] = CHEST;
    d.joint_weights[1] = 1.f;
    d.joint_weights[2] = 3.f;
    d.joint_count = 3;
    cr_modstack* s = cr_modstack_create(rig, &d, 1, 0);
    CHECK(s != nullptr);
    rest(p);
    const std::vector<float> w0 = worlds(p);
    auto yaw_of = [](const std::vector<float>& w, int j) {
        const V z = col(w.data(), j, 2);
        return std::atan2(z.x, z.z) * 180.0 / PI;
    };
    for (double angle : {40.0, -70.0, 120.0}) {
        cr_modstack_reset(s);
        cr_modstack_set_value(s, 0, CR_VALUE_ANGLE, static_cast<float>(angle));
        rest(p);
        apply(s, p, 0.f);
        const std::vector<float> w = worlds(p);
        const double want = std::clamp(angle, -90.0, 90.0);
        CHECK(std::fabs(yaw_of(w, HIPS) - want) < 1e-4);
        CHECK(std::fabs(yaw_of(w, UL) - want) < 1e-4);  // the legs go with the hips
        CHECK(std::fabs(yaw_of(w, SPINE) - want * 0.75) < 1e-4);  // the spine takes back 1/4 ...
        CHECK(len(sub(col(w.data(), CHEST, 2), col(w0.data(), CHEST, 2))) < 1e-5);  // ... the chest the rest
        CHECK(std::fabs(cr_modstack_get_value(s, 0, CR_VALUE_OUT_ANGLE) - want) < 1e-6);
    }
    // Easing: rate 10/s over 0.1 s goes 1 - e^-1 of the way.
    cr_modstack_reset(s);
    cr_modstack_set_value(s, 0, CR_VALUE_ANGLE, 0.f);
    cr_modstack_set_value(s, 0, CR_VALUE_RATE, 10.f);
    rest(p);
    apply(s, p, 0.1f);
    cr_modstack_set_value(s, 0, CR_VALUE_ANGLE, 40.f);
    rest(p);
    apply(s, p, 0.1f);
    CHECK(std::fabs(cr_modstack_get_value(s, 0, CR_VALUE_OUT_ANGLE) - 40.0 * (1.0 - std::exp(-1.0))) < 1e-4);
    cr_modstack_destroy(s);
    cr_poses_destroy(p);
    cr_rig_destroy(rig);
}

// ---- through the graph -------------------------------------------------------------------------------------------

// The flat-ground walk (1 s loop, 30 keys/s): thighs swing +-25 degrees (left forward first at t = 0.25 heel strike),
// the swing knee bends up to 60 degrees, the feet stay level, the hips bob so the stance ankle stays 0.1 above the
// ground.
// Curves foot_lock_l / foot_lock_r (STEP) = the stance phases; markers L / Loff and R / Roff the same edges.
constexpr double kSwing = 25.0;
// The clip's ground speed at rate 1: how far a stance foot travels back over its contact keys (8/30 .. 22/30, the
// thigh from +24.86 to -24.86 degrees) per second.
const double kContactSwing = kSwing * std::sin(2 * PI * 8.0 / 30.0);
const double kAnimSpeed = 2.0 * (kThigh + kShin) * std::sin(kContactSwing * PI / 180.0) / (14.0 / 30.0);

cm_clip* walk_clip() {
    cm_clip* c = cm_clip_create();
    cm_header h{30.f, 1.f, 1, 0, 0.f, 0.f, 0};
    cm_clip_header_set(c, &h);
    const std::vector<cr_joint_desc> rest = biped_joints();
    const int keyed[] = {HIPS, UL, LL, FL, UR, LR, FR};
    for (int j : keyed) {
        std::vector<cr_key> keys;
        for (int i = 0; i <= 30; ++i) {
            const double t = i / 30.0;
            const double th = kSwing * std::sin(2 * PI * t);  // left thigh; right = -th
            const double cs = std::cos(2 * PI * t);
            cr_key k{};
            k.time = static_cast<float>(t);
            std::memcpy(k.pos, rest[static_cast<size_t>(j)].pos, sizeof k.pos);
            k.scale[0] = k.scale[1] = k.scale[2] = 1.f;
            k.easing = CR_EASE_LINEAR;
            switch (j) {
                case HIPS: k.pos[1] = static_cast<float>(kAnkle + (kThigh + kShin) * std::cos(th * PI / 180.0)); break;
                case UL: k.rot_deg[0] = static_cast<float>(th); break;
                case UR: k.rot_deg[0] = static_cast<float>(-th); break;
                case LL: k.rot_deg[0] = static_cast<float>(-60.0 * std::max(0.0, cs)); break;
                case LR: k.rot_deg[0] = static_cast<float>(-60.0 * std::max(0.0, -cs)); break;
                // The feet stay level (thigh + knee + foot = 0).
                case FL: k.rot_deg[0] = static_cast<float>(-th + 60.0 * std::max(0.0, cs)); break;
                case FR: k.rot_deg[0] = static_cast<float>(th + 60.0 * std::max(0.0, -cs)); break;
                default: break;
            }
            keys.push_back(k);
        }
        cm_clip_track_set(c, kNames[j], kNames[j], nullptr, 1, keys.data(), static_cast<int32_t>(keys.size()));
    }
    // Contact = the keys where the (linearly keyed) knee is straight: left 8/30 .. 22/30, right 23/30 .. 7/30.
    const float l_on = 8.f / 30.f, l_off = 22.f / 30.f, r_on = 23.f / 30.f, r_off = 7.f / 30.f;
    const cm_curve_key l[] = {{0.f, 0.f, 0.f, 0.f, CM_INTERP_STEP}, {l_on, 1.f, 0.f, 0.f, CM_INTERP_STEP},
                              {l_off, 0.f, 0.f, 0.f, CM_INTERP_STEP}};
    const cm_curve_key r[] = {{0.f, 1.f, 0.f, 0.f, CM_INTERP_STEP}, {r_off, 0.f, 0.f, 0.f, CM_INTERP_STEP},
                              {r_on, 1.f, 0.f, 0.f, CM_INTERP_STEP}};
    cm_clip_curve_set(c, "foot_lock_l", l, 3);
    cm_clip_curve_set(c, "foot_lock_r", r, 3);
    cm_clip_marker_add(c, "L", l_on);
    cm_clip_marker_add(c, "Loff", l_off);
    cm_clip_marker_add(c, "R", r_on);
    cm_clip_marker_add(c, "Roff", r_off);
    return c;
}

struct GraphOpts {
    bool markers = false;        // foot locks from sync markers instead of curves
    bool stride_only_rate = false;  // stride clamped to 1: play-rate scaling alone
    double max_drop = 0.0;
};

std::string locomotion_graph(const GraphOpts& o) {
    const std::string lockL = o.markers ? R"("lockMarkers": ["L", "Loff"])" : R"("lockCurve": "foot_lock_l")";
    const std::string lockR = o.markers ? R"("lockMarkers": ["R", "Roff"])" : R"("lockCurve": "foot_lock_r")";
    const std::string stride = o.stride_only_rate ? R"(, "strideMin": 1, "strideMax": 1)" : "";
    const std::string drop = o.max_drop > 0 ? ", \"maxDrop\": " + std::to_string(o.max_drop) : "";
    return R"({"format": "omanimgraph", "version": "1.0",
      "parameters": [{"name": "speed", "type": "float"}, {"name": "dir", "type": "vec2", "default": [0, -1]},
                     {"name": "angle", "type": "float"}, {"name": "place", "type": "float", "default": 1},
                     {"name": "stride", "type": "float", "default": 1}],
      "slots": ["walk"], "root": "walk",
      "nodes": [{"id": "walk", "type": "clip", "slot": "walk"}],
      "modifiers": [
        {"type": "orientationWarp", "hips": "hips", "spine": [{"joint": "spine"}, {"joint": "chest"}],
         "angleParam": "angle", "rate": 8},
        {"type": "strideWarp", "pelvis": "hips", "weightParam": "stride",
         "legs": [{"upper": "upper_leg_l", "mid": "lower_leg_l", "end": "foot_l"},
                  {"upper": "upper_leg_r", "mid": "lower_leg_r", "end": "foot_r"}],
         "speedParam": "speed", "directionParam": "dir", "animSpeed": )"
        + std::to_string(kAnimSpeed) + stride + R"(},
        {"type": "footPlacement", "pelvis": "hips", "weightParam": "place",
         "legs": [{"upper": "upper_leg_l", "mid": "lower_leg_l", "end": "foot_l", )" + lockL + R"(},
                  {"upper": "upper_leg_r", "mid": "lower_leg_r", "end": "foot_r", )" + lockR + R"(}])" + drop + R"(}
      ]})";
}

cg_graph* read_graph(const std::string& json) {
    int32_t rc = 0;
    cg_graph* g = cg_graph_read(json.data(), static_cast<int64_t>(json.size()), &rc);
    if (g == nullptr) std::fprintf(stderr, "graph refused: %s\n", cg_last_error());
    CHECK(g != nullptr && rc == CG_OK);
    return g;
}

struct Walker {
    cr_rig* rig = nullptr;
    cg_graph* g = nullptr;
    cg_instance* inst = nullptr;
    cm_clip* clip = nullptr;
    Ground ground;
    std::vector<float> world = std::vector<float>(JOINTS * 16);
    std::vector<float> child = std::vector<float>(JOINTS * 16);
    V ankle_prev[2];

    explicit Walker(const GraphOpts& o) {
        rig = biped();
        g = read_graph(locomotion_graph(o));
        clip = walk_clip();
        int32_t unresolved = -1;
        inst = cg_instance_create(g, rig, nullptr, &unresolved);
        CHECK(inst != nullptr && unresolved == 0);
        CHECK(cg_instance_bind_slot(inst, 0, clip, nullptr) == CG_OK);
        CHECK(cg_instance_set_ground(inst, ground_fn, &ground) == CG_OK);
    }
    ~Walker() {
        cg_instance_destroy(inst);
        cg_graph_destroy(g);
        cm_clip_destroy(clip);
        cr_rig_destroy(rig);
    }
    void set(const char* p, float v) { CHECK(cg_instance_set_float(inst, cg_graph_param_find(g, p), v) == CG_OK); }
    void step(float dt, const M& base) {
        CHECK(cg_instance_evaluate(inst, dt, nullptr, 0, base.m, nullptr, world.data(), child.data()) == CG_OK);
    }
    float value(int mod, int key) const { return cg_instance_modifier_value(inst, mod, key); }
};

void test_graph_format() {
    const std::string json = locomotion_graph(GraphOpts{});
    cg_graph* g = read_graph(json);
    CHECK(cg_graph_modifier_count(g) == 3);
    // write(read(write(g))) is byte-identical and keeps every locomotion field.
    cc_bytes* a = cg_graph_write(g);
    int32_t rc = 0;
    cg_graph* g2 = cg_graph_read(reinterpret_cast<const char*>(cc_bytes_data(a)), cc_bytes_size(a), &rc);
    CHECK(g2 != nullptr);
    cc_bytes* b = cg_graph_write(g2);
    CHECK(cc_bytes_size(a) == cc_bytes_size(b) && std::memcmp(cc_bytes_data(a), cc_bytes_data(b), static_cast<size_t>(cc_bytes_size(a))) == 0);
    const std::string text(reinterpret_cast<const char*>(cc_bytes_data(a)), static_cast<size_t>(cc_bytes_size(a)));
    for (const char* field : {"orientationWarp", "strideWarp", "footPlacement", "angleParam", "speedParam",
                              "directionParam", "animSpeed", "lockCurve", "\"rate\": 8"}) {
        CHECK(text.find(field) != std::string::npos);
    }
    cc_bytes_destroy(a);
    cc_bytes_destroy(b);
    cg_graph_destroy(g2);
    cg_graph_destroy(g);
    // Markers survive too.
    GraphOpts m;
    m.markers = true;
    g = read_graph(locomotion_graph(m));
    a = cg_graph_write(g);
    CHECK(std::string(reinterpret_cast<const char*>(cc_bytes_data(a)), static_cast<size_t>(cc_bytes_size(a))).find("lockMarkers")
          != std::string::npos);
    cc_bytes_destroy(a);
    cg_graph_destroy(g);
    // Refusals.
    auto refused = [](std::string doc, const std::string& from, const std::string& to) {
        doc.replace(doc.find(from), from.size(), to);
        int32_t r = 0;
        cg_graph* bad = cg_graph_read(doc.data(), static_cast<int64_t>(doc.size()), &r);
        CHECK(bad == nullptr && r == CG_ERR_FORMAT);
        cg_graph_destroy(bad);
    };
    refused(json, "\"angleParam\": \"angle\"", "\"angleParam\": \"dir\"");  // a vec2 is not an angle
    refused(json, "\"directionParam\": \"dir\"", "\"directionParam\": \"speed\"");
    refused(json, "\"speedParam\": \"speed\"", "\"speedParam\": \"nope\"");
    refused(json, "\"speedParam\": \"speed\"", "\"speedParam\": \"speed\", \"rateMin\": 2");  // rateMin > rateMax
    refused(json, "\"weightParam\": \"place\",", "\"weightParam\": \"place\", \"maxAlign\": 120,");
    refused(json, "\"lockCurve\": \"foot_lock_l\"", "\"lockMarkers\": [\"L\"]");
}

void test_graph_rates_and_locks() {
    // The play rate stays in its band; the stride covers the rest of the speed.
    Walker w{GraphOpts{}};
    const M base = translate(0, 0, 0);
    struct Case {
        double speed, rate, stride;
    };
    const Case cases[] = {{kAnimSpeed, 1.0, 1.0},
                          {2.4, 1.2, 2.4 / (kAnimSpeed * 1.2)},
                          {1.0, 0.8, 1.0 / (kAnimSpeed * 0.8)},
                          {kAnimSpeed * 1.1, 1.1, 1.0}};
    for (const Case& c : cases) {
        w.set("speed", static_cast<float>(c.speed));
        w.step(1.f / 60.f, base);
        CHECK(std::fabs(w.value(1, CG_VALUE_PLAY_RATE) - c.rate) < 1e-5);
        CHECK(std::fabs(w.value(1, CG_VALUE_STRIDE) - c.stride) < 1e-5);
    }
    // Stride weight 0: rate 1 again.
    w.set("stride", 0.f);
    w.step(1.f / 60.f, base);
    CHECK(w.value(1, CG_VALUE_PLAY_RATE) == 1.f);
    // Foot locks from sync markers == from curves, frame for frame, bit for bit.
    GraphOpts mo;
    mo.markers = true;
    Walker a{GraphOpts{}}, b{mo};
    a.ground.height = b.ground.height = [](double x, double z) { return 0.05 * std::sin(x * 3 + z * 2); };
    int locked_frames = 0;
    for (int f = 0; f < 240; ++f) {
        const M bf = translate(0, 0, -kAnimSpeed * f / 60.0);
        a.set("speed", static_cast<float>(kAnimSpeed));
        b.set("speed", static_cast<float>(kAnimSpeed));
        a.step(1.f / 60.f, bf);
        b.step(1.f / 60.f, bf);
        CHECK(std::memcmp(a.world.data(), b.world.data(), a.world.size() * sizeof(float)) == 0);
        locked_frames += a.value(2, CR_VALUE_OUT_LOCKED) == 1.f ? 1 : 0;
        CHECK(a.value(2, CR_VALUE_OUT_LOCKED) == b.value(2, CR_VALUE_OUT_LOCKED));
    }
    CHECK(locked_frames > 100 && locked_frames < 125);  // the left foot: 14 of 30 keys
}

// Acceptance: the flat-ground walk climbs a staircase (and walks back down it); every fully locked contact foot
// stays within 0.01 of the tread it stands on (its ankle 0.1 above it, as on flat ground) and does not slide.
double climb(double rise, double run, bool place, double* slide_out, int* checked_out, double speed_scale = 1.0) {
    GraphOpts o;
    o.max_drop = 0.6;
    Walker w{o};
    w.set("place", place ? 1.f : 0.f);
    auto tread = [rise, run](double z) { return z >= 0.0 ? 0.0 : rise * (std::floor(-z / run) + 1.0); };
    w.ground.height = [tread](double, double z) { return tread(z); };
    const double speed = kAnimSpeed * speed_scale;
    double worst = 0.0, slide = 0.0;
    int checked = 0;
    V lock_at[2];
    bool was[2]{};
    const int frames = 360;
    for (int f = 0; f < frames; ++f) {
        const double t = f / 60.0;
        const double z = 1.0 - speed * t;  // from 1 block before the stairs, up toward -Z
        // The host's smoothed root (a capsule's step smoothing): the line through the treads' midpoints.
        const double y = z >= 0.0 ? 0.0 : -z * rise / run + rise / 2;
        const M base = translate(0, y, z);
        w.set("speed", static_cast<float>(speed));
        w.step(1.f / 60.f, base);
        for (int k = 0; k < 2; ++k) {
            const int foot = k == 0 ? FL : FR;
            // Contact = the clip's stance (its lock curve), fully locked when foot placement runs.
            const bool full = w.value(2, CR_VALUE_LOCK + k) == 1.f
                && (!place || (w.value(2, CR_VALUE_OUT_LOCKED + k) == 1.f && w.value(2, CR_VALUE_OUT_ALPHA + k) == 1.f));
            const V ankle = at(w.world, foot), toe = at(w.child, foot);
            if (full && z < -0.5 && f > 30) {
                // The tread it stands on: the higher of the ones under its heel (ankle) and toe.
                const double ground = std::max(tread(ankle.z), tread(toe.z));
                const double err = std::fabs(ankle.y - ground - kAnkle);
                if (place && err > 0.005 && std::getenv("LOCO_DEBUG") != nullptr) {
                    const V thigh = at(w.world, k == 0 ? UL : UR), knee = at(w.world, k == 0 ? LL : LR);
                    std::printf("  f=%d k=%d z=%.3f root=%.3f ankle=(%.3f %.3f %.3f) toe z=%.3f ground=%.3f err=%.4f pelvis=%.3f "
                                "thigh-ankle %.4f thigh-knee %.4f knee-ankle %.4f lockin %.2f\n",
                                f, k, z, y, ankle.x, ankle.y, ankle.z, toe.z, ground, err, w.value(2, CR_VALUE_OUT_PELVIS),
                                len(sub(ankle, thigh)), len(sub(knee, thigh)), len(sub(ankle, knee)), w.value(2, CR_VALUE_LOCK + k));
                }
                worst = std::max(worst, err);
                ++checked;
                if (!was[k]) lock_at[k] = ankle;
                slide = std::max(slide, hlen(sub(ankle, lock_at[k])));
            }
            was[k] = full && z < -0.5 && f > 30;
        }
    }
    if (slide_out != nullptr) *slide_out = slide;
    if (checked_out != nullptr) *checked_out = checked;
    return worst;
}

void test_staircase_acceptance() {
    struct Stair {
        double rise, run;
        const char* name;
    };
    const Stair stairs[] = {{0.5, 0.5, "Stonebreak stairs (0.5 up, 0.5 deep)"}, {0.25, 0.5, "gentle (0.25 up, 0.5 deep)"}};
    for (const Stair& s : stairs) {
        double slide = 0.0;
        int checked = 0;
        const double placed = climb(s.rise, s.run, true, &slide, &checked);
        const double plain = climb(s.rise, s.run, false, nullptr, nullptr);
        std::printf("[locomotion] %s: contact feet within %.5f of their treads over %d foot-frames (slide %.5f); "
                    "without foot placement %.3f\n", s.name, placed, checked, slide, plain);
        CHECK(checked > 150);
        CHECK(placed <= 0.01);
        CHECK(slide <= 0.01);
        CHECK(plain > 0.1);  // the test means something
    }
}

// Determinism: the same parameters, ground and dt sequence give bit-identical poses.
void test_determinism() {
    Walker a{GraphOpts{}}, b{GraphOpts{}};
    a.ground.height = b.ground.height = [](double x, double z) { return std::floor(z * 2) * 0.25 + 0.1 * std::sin(x); };
    std::mt19937 rng(159);
    std::uniform_real_distribution<float> u(0.f, 1.f);
    for (int f = 0; f < 600; ++f) {
        const float speed = 3.f * u(rng), angle = 180.f * u(rng) - 90.f, dt = 0.03f * u(rng);
        const M base = translate(u(rng), u(rng), -0.02 * f, 10.0 * u(rng));
        const float place = u(rng) < 0.05f ? 0.f : 1.f;
        for (Walker* w : {&a, &b}) {
            w->set("speed", speed);
            w->set("angle", angle);
            w->set("place", place);
            w->step(dt, base);
        }
        CHECK(std::memcmp(a.world.data(), b.world.data(), a.world.size() * sizeof(float)) == 0);
    }
}

// Bench: contact-foot drift per step when the host moves faster than the clip's band allows (1.6x the clip's
// speed, play rate capped at 1.2): play rate alone vs + stride warping vs + stride warping + foot locks.
double drift_per_step(bool stride, bool locks, double speed_scale) {
    GraphOpts o;
    o.stride_only_rate = !stride;
    Walker w{o};
    w.set("place", locks ? 1.f : 0.f);
    const double speed = kAnimSpeed * speed_scale;
    double total = 0.0;
    int steps = 0;
    V start[2];
    bool down[2]{};
    for (int f = 0; f < 600; ++f) {
        const M base = translate(0, 0, -speed * f / 60.0);
        w.set("speed", static_cast<float>(speed));
        w.step(1.f / 60.f, base);
        // Contact = the lock curve (the clip's stance phases), read back from the modifier's lock input.
        for (int k = 0; k < 2; ++k) {
            const bool contact = w.value(2, CR_VALUE_LOCK + k) > 0.5f;
            const V ankle = at(w.world, k == 0 ? FL : FR);
            if (contact && !down[k]) start[k] = ankle;
            if (!contact && down[k] && f > 60) {
                total += hlen(sub(w.ankle_prev[k], start[k]));
                ++steps;
            }
            w.ankle_prev[k] = ankle;
            down[k] = contact;
        }
    }
    return steps > 0 ? total / steps : 0.0;
}

void bench_drift() {
    const double slow = drift_per_step(false, false, 1.0);  // matched speed, nothing warped: the clip's own slip
    const double rate_only = drift_per_step(false, false, 1.6);
    const double warped = drift_per_step(true, false, 1.6);
    const double locked = drift_per_step(true, true, 1.6);
    std::printf("[locomotion-bench] contact-foot drift per step at 1.6x the clip speed (play rate capped at 1.2): "
                "play rate alone %.4f, + stride warping %.4f, + foot locks %.5f (matched speed, no warping: %.4f)\n",
                rate_only, warped, locked, slow);
    CHECK(warped < rate_only * 0.5);
    CHECK(locked < warped);
    CHECK(locked < 0.005);
}

}  // namespace

int main() {
    test_probe_layout_and_flat_ground();
    test_steps_holes_and_alignment();
    test_locks();
    test_leg_without_foot();
    test_stride_warp();
    test_orientation_warp();
    test_graph_format();
    test_graph_rates_and_locks();
    test_staircase_acceptance();
    test_determinism();
    bench_drift();
    if (failures != 0) {
        std::fprintf(stderr, "locomotion: %d check(s) failed\n", failures);
        return 1;
    }
    std::printf("locomotion: all checks passed\n");
    return 0;
}
