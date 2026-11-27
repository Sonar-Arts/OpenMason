// Cenda rig pose modifiers (OpenMason #158) conformance: two-bone IK (reach within 1e-4, smooth out of reach, the
// pole sets the bend plane), FABRIK / CCD, multi-aim (weights add up to the requested total, joint limits never
// exceeded, eyes aim each), constraints, spring bones (deterministic, settle at rest, colliders), full-body
// effectors, and the CR_OP_MODIFY op. Plain asserts.

#include "cenda/rig.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <initializer_list>
#include <numeric>
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

bool near(double a, double b, double eps) { return std::fabs(a - b) <= eps; }

struct V {
    double x, y, z;
};
V sub(V a, V b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
double dot(V a, V b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
V cross(V a, V b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
double len(V a) { return std::sqrt(dot(a, a)); }

// A bone: head at pos in the parent's tail frame, tail = endpoint.
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

cr_rig* make(const std::vector<cr_joint_desc>& j) { return cr_rig_create(j.data(), static_cast<int32_t>(j.size()), nullptr, nullptr); }

// The ABI 3 desc layout the Java binding writes (64-bit hosts).
static_assert(sizeof(void*) != 8 || (sizeof(cr_modifier_desc) == 80 && offsetof(cr_modifier_desc, joints) == 16
                                     && offsetof(cr_modifier_desc, joint_weights) == 24 && offsetof(cr_modifier_desc, target) == 32
                                     && offsetof(cr_modifier_desc, pole) == 36 && offsetof(cr_modifier_desc, weight) == 40
                                     && offsetof(cr_modifier_desc, p) == 44 && offsetof(cr_modifier_desc, reserved) == 76));

// A modifier desc owning its joint / weight lists (rig ABI 3: the desc holds pointers + a count; copies re-point).
struct Mod : cr_modifier_desc {
    std::vector<int32_t> js;
    std::vector<float> ws;
    Mod() : cr_modifier_desc{} {}
    Mod(const Mod& o) : cr_modifier_desc(o), js(o.js), ws(o.ws) { bind(); }
    Mod& operator=(const Mod& o) {
        static_cast<cr_modifier_desc&>(*this) = o;
        js = o.js;
        ws = o.ws;
        bind();
        return *this;
    }
    void bind() {
        joint_count = static_cast<int32_t>(js.size());
        joints = js.data();
        joint_weights = ws.data();
    }
};

Mod mod(int type, std::initializer_list<int> joints, int target) {
    Mod d;
    d.type = type;
    for (int j : joints) {
        d.js.push_back(j);
        d.ws.push_back(0.f);
    }
    d.bind();
    d.target = target;
    d.pole = -1;
    d.weight = 1.f;
    return d;
}

cr_target model_target(float x, float y, float z) {
    cr_target t{};
    t.kind = CR_TARGET_MODEL;
    t.pos[0] = x;
    t.pos[1] = y;
    t.pos[2] = z;
    return t;
}

// World matrices of a slot (bone heads = translation columns).
std::vector<float> worlds(cr_poses* p, int slot, int n) {
    std::vector<float> w(static_cast<size_t>(n) * 16);
    CHECK(cr_poses_compose(p, slot, nullptr, 0, nullptr, nullptr, w.data(), nullptr) == CR_OK);
    return w;
}

V head(const std::vector<float>& w, int j) {
    const float* m = w.data() + static_cast<size_t>(j) * 16;
    return {m[12], m[13], m[14]};
}

V axis_z(const std::vector<float>& w, int j) {
    const float* m = w.data() + static_cast<size_t>(j) * 16;
    return {m[8], m[9], m[10]};
}

std::vector<float> locals(cr_poses* p, int slot, int n) {
    std::vector<float> v(static_cast<size_t>(n) * 9);
    CHECK(cr_poses_get(p, slot, v.data(), nullptr) == CR_OK);
    return v;
}

void rest(cr_poses* p, int slot) {
    cr_pose_op op{};
    op.op = CR_OP_REST;
    op.dst = slot;
    CHECK(cr_poses_run(p, &op, 1) == CR_OK);
}

// ---- two-bone ------------------------------------------------------------------------------------------

// Leg: thigh (0) from the origin down 1, shin (1) down 1, foot (2) forward 0.2.
std::vector<cr_joint_desc> leg() {
    return {bone(-1, 0, 0, 0, 0, -1, 0), bone(0, 0, 0, 0, 0, -1, 0), bone(1, 0, 0, 0, 0, 0, 0.2f)};
}

void test_two_bone() {
    cr_rig* rig = make(leg());
    cr_poses* p = cr_poses_create(rig, 1);
    Mod d = mod(CR_MOD_TWO_BONE, {0, 1, 2}, 0);
    d.pole = 1;
    cr_modstack* s = cr_modstack_create(rig, &d, 1, 2);
    CHECK(s != nullptr);
    cr_target pole = model_target(0, -1, 1);  // the knee bends forward (+Z)
    CHECK(cr_modstack_set_target(s, 1, &pole) == CR_OK);
    // Reachable targets: the foot lands within 1e-4; the knee lies in the hip-target-pole plane, on the pole side.
    const float targets[][3] = {{0.5f, -1.5f, 0.3f}, {0.f, -1.2f, 0.f}, {-0.7f, -0.9f, 0.6f}, {0.2f, -1.95f, -0.1f}};
    for (const auto& t : targets) {
        cr_target tg = model_target(t[0], t[1], t[2]);
        CHECK(cr_modstack_set_target(s, 0, &tg) == CR_OK);
        rest(p, 0);
        CHECK(cr_modstack_apply(s, p, 0, 0.f) == CR_OK);
        std::vector<float> w = worlds(p, 0, 3);
        const V foot = head(w, 2), knee = head(w, 1), hip = head(w, 0);
        CHECK(len(sub(foot, V{t[0], t[1], t[2]})) < 1e-4);
        CHECK(near(len(sub(knee, hip)), 1.0, 1e-5) && near(len(sub(foot, knee)), 1.0, 1e-5));  // no stretch
        const V n = cross(sub(V{t[0], t[1], t[2]}, hip), sub(V{0, -1, 1}, hip));
        CHECK(std::fabs(dot(sub(knee, hip), n)) / len(n) < 1e-4);
        // The knee is on the pole's side of the hip-target line.
        const V ht = sub(V{t[0], t[1], t[2]}, hip);
        const V side = cross(n, ht);
        CHECK(dot(sub(knee, hip), side) * dot(sub(V{0, -1, 1}, hip), side) > 0.0);
    }
    // Out of reach: the leg points at the target and the reach grows smoothly as the target moves away.
    double last = 0.0;
    for (int i = 0; i <= 60; ++i) {
        const float dist = 1.7f + 0.01f * static_cast<float>(i);
        cr_target tg = model_target(0.f, -dist * 0.8f, dist * 0.6f);
        cr_modstack_set_target(s, 0, &tg);
        rest(p, 0);
        cr_modstack_apply(s, p, 0, 0.f);
        const V foot = head(worlds(p, 0, 3), 2);
        const double reach = len(foot);
        CHECK(reach <= 2.0 + 1e-6);
        CHECK(near(dot(foot, V{0, -0.8, 0.6}) / reach, 1.0, 1e-4));  // on the line to the target
        if (i > 0) {
            CHECK(reach >= last - 1e-6);  // float Euler rounding past full extension
            CHECK(reach - last < 0.0101);
        }
        last = reach;
    }
    cr_modstack_destroy(s);
    // Softness 0.1: the reach eases in over the last 10% of the length (a target exactly at full length is held
    // at 1.8 + 0.2 (1 - 1/e)), still reaches targets well inside.
    d.p[0] = 0.1f;
    s = cr_modstack_create(rig, &d, 1, 2);
    cr_modstack_set_target(s, 1, &pole);
    cr_target full = model_target(0, -2, 0);
    cr_modstack_set_target(s, 0, &full);
    rest(p, 0);
    cr_modstack_apply(s, p, 0, 0.f);
    CHECK(near(len(head(worlds(p, 0, 3), 2)), 1.8 + 0.2 * (1.0 - std::exp(-1.0)), 1e-4));
    cr_target in = model_target(0.2f, -1.f, 0.3f);
    cr_modstack_set_target(s, 0, &in);
    rest(p, 0);
    cr_modstack_apply(s, p, 0, 0.f);
    CHECK(len(sub(head(worlds(p, 0, 3), 2), V{0.2, -1, 0.3})) < 1e-4);
    // Influence 0 leaves the pose alone; 0.5 lands in between.
    cr_modstack_set_weight(s, 0, 0.f);
    rest(p, 0);
    cr_modstack_apply(s, p, 0, 0.f);
    CHECK(len(sub(head(worlds(p, 0, 3), 2), V{0, -2, 0})) < 1e-6);
    cr_modstack_set_weight(s, 0, 0.5f);
    rest(p, 0);
    cr_modstack_apply(s, p, 0, 0.f);
    const double half = len(sub(head(worlds(p, 0, 3), 2), V{0, -2, 0}));
    CHECK(half > 0.1 && half < len(sub(V{0.2, -1, 0.3}, V{0, -2, 0})));
    cr_modstack_destroy(s);
    cr_poses_destroy(p);
    cr_rig_destroy(rig);
}

// ---- chains ------------------------------------------------------------------------------------------

void test_chains() {
    std::vector<cr_joint_desc> j = {bone(-1, 0, 0, 0, 0, 0.5f, 0)};
    for (int i = 1; i < 6; ++i) j.push_back(bone(i - 1, 0, 0, 0, 0, 0.5f, 0));  // 6 bones, the last head at y 2.5
    cr_rig* rig = make(j);
    cr_poses* p = cr_poses_create(rig, 1);
    for (int type : {CR_MOD_FABRIK, CR_MOD_CCD}) {
        Mod d = mod(type, {0, 5}, 0);
        d.p[0] = type == CR_MOD_FABRIK ? 20.f : 50.f;
        d.p[1] = 1e-5f;
        cr_modstack* s = cr_modstack_create(rig, &d, 1, 1);
        CHECK(s != nullptr);
        cr_target t = model_target(1.2f, 1.4f, 0.5f);
        cr_modstack_set_target(s, 0, &t);
        rest(p, 0);
        CHECK(cr_modstack_apply(s, p, 0, 0.f) == CR_OK);
        std::vector<float> w = worlds(p, 0, 6);
        CHECK(len(sub(head(w, 5), V{1.2, 1.4, 0.5})) < (type == CR_MOD_FABRIK ? 1e-4 : 1e-3));
        for (int i = 1; i < 6; ++i) CHECK(near(len(sub(head(w, i), head(w, i - 1))), 0.5, 1e-5));
        cr_modstack_destroy(s);
    }
    // A chain whose start is not an ancestor of its end is refused.
    Mod bad = mod(CR_MOD_FABRIK, {5, 0}, 0);
    CHECK(cr_modstack_create(rig, &bad, 1, 1) == nullptr);
    cr_poses_destroy(p);
    cr_rig_destroy(rig);
}

// ---- aim --------------------------------------------------------------------------------------------

void test_aim() {
    // Spine (0), chest (1), neck (2), head (3) up +Y; two eyes (4, 5) on the head. Rest: everything faces -Z.
    std::vector<cr_joint_desc> j = {bone(-1, 0, 1, 0, 0, 0.3f, 0), bone(0, 0, 0, 0, 0, 0.3f, 0), bone(1, 0, 0, 0, 0, 0.2f, 0),
                                    bone(2, 0, 0, 0, 0, 0.25f, 0), bone(3, 0.05f, -0.1f, -0.1f, 0, 0, -0.02f),
                                    bone(3, -0.05f, -0.1f, -0.1f, 0, 0, -0.02f)};
    cr_rig* rig = make(j);
    cr_poses* p = cr_poses_create(rig, 1);
    Mod d = mod(CR_MOD_AIM, {0, 1, 2, 3}, 0);
    const float weights[4] = {0.1f, 0.2f, 0.3f, 0.4f};
    d.ws.assign(weights, weights + 4);
    d.bind();
    d.p[2] = 1.f;  // the head's +Z axis... aim axis (0, 0, 1) is "backward"; use -Z:
    d.p[2] = -1.f;
    cr_modstack* s = cr_modstack_create(rig, &d, 1, 1);
    // A far target 60 degrees to the right (about -Y): the turns add up to 60, shared 1:2:3:4.
    const double yaw = 60.0 * 3.14159265358979 / 180.0;
    cr_target t = model_target(static_cast<float>(1e4 * std::sin(yaw)), 1.5f, static_cast<float>(-1e4 * std::cos(yaw)));
    cr_modstack_set_target(s, 0, &t);
    rest(p, 0);
    cr_modstack_apply(s, p, 0, 0.f);
    std::vector<float> v = locals(p, 0, 6);
    double sum = 0.0;
    for (int k = 0; k < 4; ++k) {
        CHECK(near(v[static_cast<size_t>(k) * 9 + 4], -60.0 * weights[k], 1e-3));
        sum += v[static_cast<size_t>(k) * 9 + 4];
    }
    CHECK(near(sum, -60.0, 1e-3));
    const V fwd = axis_z(worlds(p, 0, 6), 3);
    CHECK(near(-fwd.z, -std::cos(yaw), 1e-4) && near(-fwd.x, std::sin(yaw), 1e-4));  // -Z (the aim axis) at the target
    // Influence 0.5: half the turn.
    cr_modstack_set_weight(s, 0, 0.5f);
    rest(p, 0);
    cr_modstack_apply(s, p, 0, 0.f);
    v = locals(p, 0, 6);
    sum = 0.0;
    for (int k = 0; k < 4; ++k) sum += v[static_cast<size_t>(k) * 9 + 4];
    CHECK(near(sum, -30.0, 1e-2));
    cr_modstack_destroy(s);

    // Limits: +-10 degrees about Y each, a target behind: no joint ever exceeds its range.
    d.flags = CR_MOD_LIMITS;
    s = cr_modstack_create(rig, &d, 1, 1);
    const float zero[3] = {0, 0, 0}, mn[3] = {-15, -10, -5}, mx[3] = {15, 10, 5};
    for (int k = 0; k < 4; ++k) cr_modstack_set_limit(s, k, zero, mn, mx);
    for (int i = 0; i < 24; ++i) {
        const double a = i * 15.0 * 3.14159265358979 / 180.0;
        cr_target tg = model_target(static_cast<float>(10 * std::sin(a)), 1.5f + static_cast<float>(5 * std::sin(3 * a)),
                                    static_cast<float>(-10 * std::cos(a)));
        cr_modstack_set_target(s, 0, &tg);
        rest(p, 0);
        cr_modstack_apply(s, p, 0, 0.f);
        v = locals(p, 0, 6);
        for (int k = 0; k < 4; ++k) {
            for (int c = 0; c < 3; ++c) {
                const float r = v[static_cast<size_t>(k) * 9 + 3 + static_cast<size_t>(c)];
                CHECK(r >= mn[c] - 1e-4f && r <= mx[c] + 1e-4f);
            }
        }
    }
    cr_modstack_destroy(s);

    // Eyes aim separately, each fully.
    Mod eyes = mod(CR_MOD_AIM, {4, 5}, 0);
    eyes.flags = CR_MOD_AIM_EACH;
    eyes.ws[0] = eyes.ws[1] = 1.f;
    eyes.p[2] = -1.f;
    s = cr_modstack_create(rig, &eyes, 1, 1);
    cr_target near_t = model_target(0.3f, 2.f, -0.6f);
    cr_modstack_set_target(s, 0, &near_t);
    rest(p, 0);
    cr_modstack_apply(s, p, 0, 0.f);
    std::vector<float> w = worlds(p, 0, 6);
    for (int e : {4, 5}) {
        const V dir = sub(V{0.3, 2.0, -0.6}, head(w, e));
        const V z = axis_z(w, e);
        CHECK(near(-dot(z, dir) / len(dir), 1.0, 1e-5));
    }
    CHECK(std::fabs(locals(p, 0, 6)[3 * 9 + 4]) < 1e-6);  // the head did not move
    cr_modstack_destroy(s);
    cr_poses_destroy(p);
    cr_rig_destroy(rig);
}

// ---- constraints ------------------------------------------------------------------------------------

void test_constraints() {
    std::vector<cr_joint_desc> j = {bone(-1, 0, 0, 0, 0, 1, 0), bone(0, 0, 0, 0, 0, 1, 0), bone(-1, 2, 0, 0, 0, 0.5f, 0)};
    cr_rig* rig = make(j);
    cr_poses* p = cr_poses_create(rig, 1);
    const float q45y[4] = {0.f, std::sin(0.3926991f), 0.f, std::cos(0.3926991f)};  // 45 deg about Y (x, y, z, w)
    // Point: the joint's pivot lands on the target (+ offset in the target's frame).
    for (int kind : {CR_CONSTRAINT_POINT, CR_CONSTRAINT_ORIENT, CR_CONSTRAINT_PARENT, CR_CONSTRAINT_COPY, CR_CONSTRAINT_AIM}) {
        Mod d = mod(CR_MOD_CONSTRAINT, {1}, 0);
        d.kind = kind;
        if (kind == CR_CONSTRAINT_PARENT || kind == CR_CONSTRAINT_POINT) d.p[2] = 0.5f;  // offset +Z in the target frame
        if (kind == CR_CONSTRAINT_AIM) d.p[1] = 1.f;                                    // aim the bone's +Y
        cr_modstack* s = cr_modstack_create(rig, &d, 1, 1);
        cr_target t = model_target(1.f, 2.f, 3.f);
        std::memcpy(t.rot, q45y, sizeof q45y);
        cr_modstack_set_target(s, 0, &t);
        rest(p, 0);
        CHECK(cr_modstack_apply(s, p, 0, 0.f) == CR_OK);
        std::vector<float> w = worlds(p, 0, 3);
        const V pv = head(w, 1);
        const V offset_world{0.5 * std::sin(0.785398), 0.0, 0.5 * std::cos(0.785398)};
        if (kind == CR_CONSTRAINT_POINT || kind == CR_CONSTRAINT_PARENT) {
            CHECK(len(sub(pv, V{1 + offset_world.x, 2, 3 + offset_world.z})) < 1e-5);
        }
        if (kind == CR_CONSTRAINT_COPY) CHECK(len(sub(pv, V{1, 2, 3})) < 1e-5);
        if (kind == CR_CONSTRAINT_ORIENT || kind == CR_CONSTRAINT_PARENT || kind == CR_CONSTRAINT_COPY) {
            const V z = axis_z(w, 1);  // +Z turned 45 about Y
            CHECK(near(z.x, std::sin(0.785398), 1e-5) && near(z.z, std::cos(0.785398), 1e-5));
        }
        if (kind == CR_CONSTRAINT_ORIENT) CHECK(len(sub(pv, V{0, 1, 0})) < 1e-6);  // orient leaves the pivot
        if (kind == CR_CONSTRAINT_AIM) {
            const float* m = w.data() + 16;
            const V y{m[4], m[5], m[6]};
            const V dir = sub(V{1, 2, 3}, pv);
            CHECK(near(dot(y, dir) / len(dir), 1.0, 1e-5));
        }
        cr_modstack_destroy(s);
    }
    // A joint target: follow another joint (with its offset).
    Mod d = mod(CR_MOD_CONSTRAINT, {2}, 0);
    d.kind = CR_CONSTRAINT_POINT;
    cr_modstack* s = cr_modstack_create(rig, &d, 1, 1);
    cr_target jt{};
    jt.kind = CR_TARGET_JOINT;
    jt.joint = 1;
    jt.pos[1] = 0.25f;
    cr_modstack_set_target(s, 0, &jt);
    rest(p, 0);
    cr_modstack_apply(s, p, 0, 0.f);
    CHECK(len(sub(head(worlds(p, 0, 3), 2), V{0, 1.25, 0})) < 1e-5);
    cr_modstack_destroy(s);
    cr_poses_destroy(p);
    cr_rig_destroy(rig);
}

// ---- spring bones -----------------------------------------------------------------------------------

// A tail of three bones hanging down from a root at y 2.
std::vector<cr_joint_desc> tail() {
    return {bone(-1, 0, 2, 0, 0, -0.01f, 0), bone(0, 0, 0, 0, 0, -0.4f, 0), bone(1, 0, 0, 0, 0, -0.4f, 0),
            bone(2, 0, 0, 0, 0, -0.4f, 0)};
}

void run_spring(cr_modstack* s, cr_poses* p, int frames, float x0, float vx, std::vector<std::vector<float>>* out) {
    for (int f = 0; f < frames; ++f) {
        float frame[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x0 + vx * static_cast<float>(f) / 60.f, 0, 0, 1};
        cr_modstack_set_frame(s, frame);
        rest(p, 0);
        CHECK(cr_modstack_apply(s, p, 0, 1.f / 60.f) == CR_OK);
        if (out != nullptr) out->push_back(locals(p, 0, 4));
    }
}

void test_spring() {
    cr_rig* rig = make(tail());
    cr_poses* pa = cr_poses_create(rig, 1);
    cr_poses* pb = cr_poses_create(rig, 1);
    Mod d = mod(CR_MOD_SPRING, {1, 3}, -1);
    d.p[0] = 2.f;    // stiffness
    d.p[1] = 0.3f;   // drag
    d.p[6] = 1.f / 120.f;
    cr_modstack* a = cr_modstack_create(rig, &d, 1, 0);
    cr_modstack* b = cr_modstack_create(rig, &d, 1, 0);
    // Identical input, identical output: run the character sideways, then hold still.
    std::vector<std::vector<float>> ra, rb;
    run_spring(a, pa, 30, 0.f, 3.f, &ra);
    run_spring(b, pb, 30, 0.f, 3.f, &rb);
    CHECK(ra == rb);
    // The motion swung the tail (it lags behind the run).
    double swing = 0.0;
    for (const auto& v : ra) swing = std::max(swing, std::fabs(static_cast<double>(v[9 + 5])) + std::fabs(static_cast<double>(v[9 + 3])));
    CHECK(swing > 1.0);
    // Standing still, it settles at rest (the animated pose).
    std::vector<std::vector<float>> settle;
    run_spring(a, pa, 600, 1.5f, 0.f, &settle);
    const std::vector<float>& last = settle.back();
    for (int k = 1; k < 4; ++k) {
        for (int c = 3; c < 6; ++c) CHECK(std::fabs(last[static_cast<size_t>(k) * 9 + static_cast<size_t>(c)]) < 0.05f);
    }
    for (size_t i = 0; i < last.size(); ++i) CHECK(std::fabs(last[i] - settle[settle.size() - 2][i]) < 1e-4f);

    // Gravity sideways: it settles at a stationary bend instead.
    cr_modstack_destroy(b);
    d.p[2] = 9.8f;
    b = cr_modstack_create(rig, &d, 1, 0);
    std::vector<std::vector<float>> g;
    run_spring(b, pb, 900, 0.f, 0.f, &g);
    double bend = 0.0, moving = 0.0;
    for (size_t i = 0; i < g.back().size(); ++i) {
        moving = std::max(moving, static_cast<double>(std::fabs(g.back()[i] - g[g.size() - 2][i])));
    }
    bend = std::fabs(static_cast<double>(g.back()[9 + 5]));
    CHECK(bend > 1.0 && moving < 1e-3);

    // A sphere collider beside the rest tail pushes it out.
    cr_modstack_destroy(b);
    d.p[2] = 0.f;
    d.p[5] = 0.02f;
    b = cr_modstack_create(rig, &d, 1, 0);
    const float c0[3] = {0.1f, -0.4f, 0.f};  // in the root bone's frame: beside the first segment's tail
    CHECK(cr_modstack_add_collider(b, 0, c0, nullptr, 0.15f) == 0);
    run_spring(b, pb, 240, 0.f, 0.f, nullptr);
    const V tip = head(worlds(pb, 0, 4), 2);  // the first segment's tail = joint 2's head
    CHECK(len(sub(tip, V{0.1, 1.6, 0})) >= 0.17 - 1e-3);
    cr_modstack_reset(b);
    cr_modstack_destroy(b);
    cr_modstack_destroy(a);
    cr_poses_destroy(pb);
    cr_poses_destroy(pa);
    cr_rig_destroy(rig);
}

// ---- full body --------------------------------------------------------------------------------------

void test_full_body() {
    // Hips (0) at y 1; an arm: shoulder (1) on the hips' tail, upper arm (1) 0.4 along +X, forearm (2) 0.4, hand (3).
    std::vector<cr_joint_desc> j = {bone(-1, 0, 1, 0, 0, 0.5f, 0), bone(0, 0.1f, 0, 0, 0.4f, 0, 0), bone(1, 0, 0, 0, 0.4f, 0, 0),
                                    bone(2, 0, 0, 0, 0.1f, 0, 0)};
    cr_rig* rig = make(j);
    cr_poses* p = cr_poses_create(rig, 1);
    Mod d = mod(CR_MOD_FULL_BODY, {0, 1, 2, 3}, 0);
    d.ws[1] = 1.f;  // pull
    d.ws[2] = 1.f;  // reach
    cr_modstack* s = cr_modstack_create(rig, &d, 1, 1);
    cr_target t = model_target(1.5f, 1.5f, 0.3f);  // 1.4 from the shoulder: out of the arm's 0.8 reach
    cr_modstack_set_target(s, 0, &t);
    rest(p, 0);
    CHECK(cr_modstack_apply(s, p, 0, 0.f) == CR_OK);
    std::vector<float> w = worlds(p, 0, 4);
    CHECK(len(sub(head(w, 3), V{1.5, 1.5, 0.3})) < 1e-3);  // the body came along
    CHECK(len(sub(head(w, 0), V{0, 1, 0})) > 0.5);
    // No pull: the body stays, the arm points at the goal.
    d.ws[1] = 0.f;
    cr_modstack_destroy(s);
    s = cr_modstack_create(rig, &d, 1, 1);
    cr_modstack_set_target(s, 0, &t);
    rest(p, 0);
    cr_modstack_apply(s, p, 0, 0.f);
    w = worlds(p, 0, 4);
    CHECK(len(sub(head(w, 0), V{0, 1, 0})) < 1e-6);
    CHECK(near(len(sub(head(w, 3), head(w, 1))), 0.8, 1e-3));
    cr_modstack_destroy(s);
    // The CR_OP_MODIFY op runs the same stack as cr_modstack_apply.
    d.ws[1] = 1.f;
    cr_modstack* s1 = cr_modstack_create(rig, &d, 1, 1);
    cr_modstack* s2 = cr_modstack_create(rig, &d, 1, 1);
    cr_modstack_set_target(s1, 0, &t);
    cr_modstack_set_target(s2, 0, &t);
    cr_poses* q = cr_poses_create(rig, 1);
    rest(p, 0);
    cr_modstack_apply(s1, p, 0, 0.f);
    cr_pose_op ops[2]{};
    ops[0].op = CR_OP_REST;
    ops[1].op = CR_OP_MODIFY;
    ops[1].object = s2;
    CHECK(cr_poses_run(q, ops, 2) == CR_OK);
    CHECK(locals(p, 0, 4) == locals(q, 0, 4));
    cr_poses_destroy(q);
    cr_modstack_destroy(s2);
    cr_modstack_destroy(s1);
    cr_poses_destroy(p);
    cr_rig_destroy(rig);
}

// ---- no fixed joint / limb caps (rig ABI 3, OpenMason #170) ----------------------------------------------------

// A 24-joint aim chain (ABI 2 stopped at 16): the turn is shared by weight over every joint.
void test_long_aim_chain() {
    constexpr int N = 24;
    std::vector<cr_joint_desc> j = {bone(-1, 0, 1, 0, 0, 0.1f, 0)};
    for (int i = 1; i < N; ++i) j.push_back(bone(i - 1, 0, 0, 0, 0, 0.1f, 0));
    cr_rig* rig = make(j);
    cr_poses* p = cr_poses_create(rig, 1);
    std::vector<int32_t> joints(N);
    std::vector<float> weights(N);
    double total = 0.0;
    for (int k = 0; k < N; ++k) {
        joints[static_cast<size_t>(k)] = k;
        weights[static_cast<size_t>(k)] = static_cast<float>(k + 1);
        total += k + 1;
    }
    cr_modifier_desc d{};
    d.type = CR_MOD_AIM;
    d.joint_count = N;
    d.joints = joints.data();
    d.joint_weights = weights.data();
    d.target = 0;
    d.pole = -1;
    d.weight = 1.f;
    d.p[2] = -1.f;
    cr_modstack* s = cr_modstack_create(rig, &d, 1, 1);
    CHECK(s != nullptr);
    // The stack copied the lists: scribbling over the caller's arrays changes nothing.
    std::fill(joints.begin(), joints.end(), -7);
    std::fill(weights.begin(), weights.end(), 0.f);
    const double yaw = 60.0 * 3.14159265358979 / 180.0;
    cr_target t = model_target(static_cast<float>(1e4 * std::sin(yaw)), 2.f, static_cast<float>(-1e4 * std::cos(yaw)));
    cr_modstack_set_target(s, 0, &t);
    rest(p, 0);
    CHECK(cr_modstack_apply(s, p, 0, 0.f) == CR_OK);
    const std::vector<float> v = locals(p, 0, N);
    double sum = 0.0;
    for (int k = 0; k < N; ++k) {
        const double ry = v[static_cast<size_t>(k) * 9 + 4];
        CHECK(near(ry, -60.0 * (k + 1) / total, 1e-3));
        sum += ry;
    }
    CHECK(near(sum, -60.0, 1e-3));
    cr_modstack_destroy(s);
    // NULL joint_weights = every weight 0: the shared turn has nothing to share, the pose is untouched.
    std::iota(joints.begin(), joints.end(), 0);
    cr_modifier_desc zero = d;
    zero.joint_weights = nullptr;
    cr_modstack* sz = cr_modstack_create(rig, &zero, 1, 1);
    CHECK(sz != nullptr);
    cr_modstack_set_target(sz, 0, &t);
    rest(p, 0);
    CHECK(cr_modstack_apply(sz, p, 0, 0.f) == CR_OK);
    const std::vector<float> still = locals(p, 0, N);
    for (int k = 0; k < N; ++k) CHECK(still[static_cast<size_t>(k) * 9 + 4] == 0.f);
    cr_modstack_destroy(sz);
    // A joint list without joints is refused.
    cr_modifier_desc bad = d;
    bad.joints = nullptr;
    CHECK(cr_modstack_create(rig, &bad, 1, 1) == nullptr);
    cr_poses_destroy(p);
    cr_rig_destroy(rig);
}

// Full body with six limbs (ABI 2 stopped at 5): every hand reaches its goal, limb 5 included.
void test_six_limb_full_body() {
    constexpr int LIMBS = 6;
    std::vector<cr_joint_desc> j = {bone(-1, 0, 1, 0, 0, 0.5f, 0)};  // hips (0), tail at y 1.5
    std::vector<V> dirs;
    for (int k = 0; k < LIMBS; ++k) {
        const double a = 2.0 * 3.14159265358979 * k / LIMBS;
        const V dir{std::cos(a), 0.0, std::sin(a)};
        dirs.push_back(dir);
        const auto f = [](double x) { return static_cast<float>(x); };
        const int up = static_cast<int>(j.size());
        j.push_back(bone(0, f(0.1 * dir.x), 0, f(0.1 * dir.z), f(0.4 * dir.x), 0, f(0.4 * dir.z)));
        j.push_back(bone(up, 0, 0, 0, f(0.4 * dir.x), 0, f(0.4 * dir.z)));
        j.push_back(bone(up + 1, 0, 0, 0, f(0.1 * dir.x), 0, f(0.1 * dir.z)));
    }
    cr_rig* rig = make(j);
    cr_poses* p = cr_poses_create(rig, 1);
    std::vector<int32_t> joints = {0};
    std::vector<float> weights = {0.f};
    for (int k = 0; k < LIMBS; ++k) {
        for (int c = 0; c < 3; ++c) joints.push_back(1 + 3 * k + c);
        weights.insert(weights.end(), {0.f, 1.f, 0.f});  // no pull (the body stays), full reach
    }
    cr_modifier_desc d{};
    d.type = CR_MOD_FULL_BODY;
    d.joint_count = static_cast<int32_t>(joints.size());
    d.joints = joints.data();
    d.joint_weights = weights.data();
    d.target = 0;
    d.pole = -1;
    d.weight = 1.f;
    // One target slot per limb is required.
    CHECK(cr_modstack_create(rig, &d, 1, LIMBS - 1) == nullptr);
    cr_modstack* s = cr_modstack_create(rig, &d, 1, LIMBS);
    CHECK(s != nullptr);
    std::vector<V> goals;
    for (int k = 0; k < LIMBS; ++k) {
        // 0.6 from the shoulder, 30 degrees up: inside the 0.8 reach.
        const V sh{0.1 * dirs[static_cast<size_t>(k)].x, 1.5, 0.1 * dirs[static_cast<size_t>(k)].z};
        const double c30 = std::cos(3.14159265358979 / 6), s30 = 0.5;
        const V g{sh.x + 0.6 * c30 * dirs[static_cast<size_t>(k)].x, sh.y + 0.6 * s30, sh.z + 0.6 * c30 * dirs[static_cast<size_t>(k)].z};
        goals.push_back(g);
        cr_target t = model_target(static_cast<float>(g.x), static_cast<float>(g.y), static_cast<float>(g.z));
        CHECK(cr_modstack_set_target(s, k, &t) == CR_OK);
    }
    rest(p, 0);
    CHECK(cr_modstack_apply(s, p, 0, 0.f) == CR_OK);
    const std::vector<float> w = worlds(p, 0, static_cast<int>(j.size()));
    CHECK(len(sub(head(w, 0), V{0, 1, 0})) < 1e-6);
    for (int k = 0; k < LIMBS; ++k) CHECK(len(sub(head(w, 3 + 3 * k), goals[static_cast<size_t>(k)])) < 1e-3);
    cr_modstack_destroy(s);
    cr_poses_destroy(p);
    cr_rig_destroy(rig);
}

}  // namespace

int main() {
    test_two_bone();
    test_chains();
    test_aim();
    test_constraints();
    test_spring();
    test_full_body();
    test_long_aim_chain();
    test_six_limb_full_body();
    if (failures != 0) {
        std::fprintf(stderr, "rig_modifiers: %d check(s) failed\n", failures);
        return 1;
    }
    std::printf("rig_modifiers: all checks passed\n");
    return 0;
}
