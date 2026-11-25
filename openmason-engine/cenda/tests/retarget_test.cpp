// Cenda animation retargeting (OpenMason #160) conformance: profile chains / IK goals (format + auto-map), a
// character retargeted onto itself reproduces the source pose, a differently built target (legs 0.7x, arms 1.2x,
// three spine bones for one, every bone's axes turned, facing +Z instead of -Z) gets its feet on the scaled goals
// with knees bending forward and its chest turned like the source's spine, the op stack (pelvis weight, FK-only,
// stride warp, a chain switched off), a baked clip poses like the runtime path through the animation graph (key
// times, between keys, root motion), and determinism. Plain asserts.

#include "cenda/anim_graph.h"
#include "cenda/retarget.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
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
V operator+(V a, V b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
V operator-(V a, V b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
V operator*(V a, double s) { return {a.x * s, a.y * s, a.z * s}; }
double dot(V a, V b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
double len(V a) { return std::sqrt(dot(a, a)); }
V ry(V v, double deg) {
    const double a = deg * PI / 180.0, c = std::cos(a), s = std::sin(a);
    return {v.x * c + v.z * s, v.y, -v.x * s + v.z * c};
}

// ---- a synthetic humanoid ---------------------------------------------------------------------------------------

struct Bone {
    std::string name, parent;
    V head, tail;
    double yaw = 0.0;  // rest rotation about Y (turns the bone's axes; the geometry stays)
};

struct Body {
    double leg = 1.0, arm = 1.0;
    int spines = 1;
    bool faces_pz = false;
    bool twist = false;
};

std::vector<Bone> humanoid(const Body& b) {
    const double L = b.leg, A = b.arm, H = 1.0 * L;
    std::vector<Bone> out;
    out.push_back({"hips", "", {0, H, 0}, {0, H + 0.1, 0}});
    const char* spine_names[] = {"spine", "chest", "upper_chest"};
    std::string top = "hips";
    for (int k = 0; k < b.spines; ++k) {
        const double y0 = H + 0.1 + 0.5 * k / b.spines, y1 = H + 0.1 + 0.5 * (k + 1) / b.spines;
        out.push_back({spine_names[k], top, {0, y0, 0}, {0, y1, 0}});
        top = spine_names[k];
    }
    out.push_back({"head", top, {0, H + 0.65, 0}, {0, H + 0.9, 0}});
    for (int side = 0; side < 2; ++side) {
        const double x = side == 0 ? -1.0 : 1.0;  // left = -X
        const std::string s = side == 0 ? "_l" : "_r";
        const V sh{0.2 * x, H + 0.55, 0}, el = sh + V{0, -0.3 * A, 0.01}, wr = el + V{0, -0.27 * A, 0}, tip = wr + V{0, -0.1 * A, 0};
        out.push_back({"upper_arm" + s, top, sh, el});
        out.push_back({"lower_arm" + s, "upper_arm" + s, el, wr});
        out.push_back({"hand" + s, "lower_arm" + s, wr, tip});
        const V hip{0.1 * x, H, 0}, knee{0.1 * x, 0.55 * L, -0.01}, ankle{0.1 * x, 0.1 * L, 0}, toe{0.1 * x, 0.0, -0.2 * L};
        out.push_back({"upper_leg" + s, "hips", hip, knee});
        out.push_back({"lower_leg" + s, "upper_leg" + s, knee, ankle});
        out.push_back({"foot" + s, "lower_leg" + s, ankle, toe});
    }
    for (size_t i = 0; i < out.size(); ++i) {
        if (b.faces_pz) {
            out[i].head = V{-out[i].head.x, out[i].head.y, -out[i].head.z};
            out[i].tail = V{-out[i].tail.x, out[i].tail.y, -out[i].tail.z};
        }
        if (b.twist) out[i].yaw = static_cast<double>((static_cast<int>(i) * 37) % 90 - 45);
    }
    return out;
}

struct Rigged {
    cr_rig* rig = nullptr;
    cc_char* chr = nullptr;
    std::vector<Bone> bones;
    int count() const { return cr_rig_joint_count(rig); }
    int find(const char* n) const { return cr_rig_find(rig, n); }
};

Rigged make(const Body& b) {
    Rigged r;
    r.bones = humanoid(b);
    const size_t n = r.bones.size();
    std::vector<cr_joint_desc> descs(n);
    std::vector<double> acc(n, 0.0);
    std::vector<const char*> names(n);
    for (size_t i = 0; i < n; ++i) {
        const Bone& bn = r.bones[i];
        int parent = -1;
        for (size_t k = 0; k < i; ++k) {
            if (r.bones[k].name == bn.parent) parent = static_cast<int>(k);
        }
        const V ptail = parent >= 0 ? r.bones[static_cast<size_t>(parent)].tail : V{};
        const double pyaw = parent >= 0 ? acc[static_cast<size_t>(parent)] : 0.0;
        acc[i] = pyaw + bn.yaw;
        const V pos = ry(bn.head - ptail, -pyaw), end = ry(bn.tail - bn.head, -acc[i]);
        cr_joint_desc& d = descs[i];
        d = cr_joint_desc{};
        d.parent = parent;
        d.kind = CR_JOINT_BONE;
        d.flags = CR_JOINT_HAS_GEOMETRY;
        d.pos[0] = static_cast<float>(pos.x);
        d.pos[1] = static_cast<float>(pos.y);
        d.pos[2] = static_cast<float>(pos.z);
        d.rot_deg[1] = static_cast<float>(bn.yaw);
        d.scale[0] = d.scale[1] = d.scale[2] = 1.f;
        d.endpoint[0] = static_cast<float>(end.x);
        d.endpoint[1] = static_cast<float>(end.y);
        d.endpoint[2] = static_cast<float>(end.z);
        names[i] = r.bones[i].name.c_str();
    }
    r.rig = cr_rig_create(descs.data(), static_cast<int32_t>(n), names.data(), names.data());
    CHECK(r.rig != nullptr);
    r.chr = cc_char_create();
    CHECK(cc_char_set_skeleton(r.chr, r.rig) == CC_OK);
    cc_profile* humanoid_profile = cc_profile_builtin("humanoid");
    cc_automap* m = cc_char_auto_map(r.chr, r.rig, humanoid_profile);
    CHECK(m != nullptr);
    cc_automap_destroy(m);
    cc_profile_destroy(humanoid_profile);
    return r;
}

void destroy(Rigged& r) {
    cc_char_destroy(r.chr);
    cr_rig_destroy(r.rig);
}

std::vector<float> rest(const Rigged& r) {
    cr_poses* p = cr_poses_create(r.rig, 1);
    cr_pose_op op{};
    op.op = CR_OP_REST;
    cr_poses_run(p, &op, 1);
    std::vector<float> l(static_cast<size_t>(r.count()) * 9);
    cr_poses_get(p, 0, l.data(), nullptr);
    cr_poses_destroy(p);
    return l;
}

std::vector<float> worlds(const Rigged& r, const std::vector<float>& local) {
    std::vector<float> w(static_cast<size_t>(r.count()) * 16);
    CHECK(cr_rig_pose_locals(r.rig, local.data(), nullptr, 0, nullptr, nullptr, w.data(), nullptr) == CR_OK);
    return w;
}

V at(const std::vector<float>& w, int j) { return {w[static_cast<size_t>(j) * 16 + 12], w[static_cast<size_t>(j) * 16 + 13], w[static_cast<size_t>(j) * 16 + 14]}; }

double mat_diff(const std::vector<float>& a, const std::vector<float>& b, int j) {
    double d = 0.0;
    for (size_t c = 0; c < 16; ++c) d = std::max(d, static_cast<double>(std::fabs(a[static_cast<size_t>(j) * 16 + c] - b[static_cast<size_t>(j) * 16 + c])));
    return d;
}

// A walking-ish pose on a rig: hips moved and turned, thighs swung, knees bent, spine leaning, arms swinging.
std::vector<float> walk_pose(const Rigged& r, double phase) {
    std::vector<float> l = rest(r);
    auto add = [&](const char* name, double rx, double ry_, double rz) {
        const int j = r.find(name);
        if (j < 0) return;
        float* rot = l.data() + static_cast<size_t>(j) * 9 + 3;
        rot[0] += static_cast<float>(rx);
        rot[1] += static_cast<float>(ry_);
        rot[2] += static_cast<float>(rz);
    };
    const double s = std::sin(2 * PI * phase), c = std::cos(2 * PI * phase);
    const int h = r.find("hips");
    l[static_cast<size_t>(h) * 9 + 0] += static_cast<float>(0.03 * c);
    l[static_cast<size_t>(h) * 9 + 1] += static_cast<float>(-0.04 + 0.02 * std::fabs(s));
    l[static_cast<size_t>(h) * 9 + 2] += static_cast<float>(-0.1 * s);
    add("hips", 0, 6 * s, 3 * c);
    add("spine", -8, -4 * s, 0);
    add("upper_leg_l", 30 * s, 0, 2);
    add("upper_leg_r", -30 * s, 0, -2);
    add("lower_leg_l", -35 - 20 * std::max(0.0, c), 0, 0);
    add("lower_leg_r", -35 - 20 * std::max(0.0, -c), 0, 0);
    add("foot_l", 10 * s, 0, 0);
    add("upper_arm_l", -25 * s, 0, -10);
    add("upper_arm_r", 25 * s, 0, 10);
    add("lower_arm_l", 30, 0, 0);
    add("lower_arm_r", 30, 0, 0);
    return l;
}

// ---- tests -----------------------------------------------------------------------------------------------------

void test_profile_chains_and_auto_map() {
    cc_profile* p = cc_profile_builtin("humanoid");
    CHECK(p != nullptr);
    cc_bytes* text = cc_profile_write(p);
    const std::string json(reinterpret_cast<const char*>(cc_bytes_data(text)), static_cast<size_t>(cc_bytes_size(text)));
    CHECK(json.find("\"chains\"") != std::string::npos && json.find("\"ikGoals\"") != std::string::npos);
    cc_profile* again = cc_profile_read(cc_bytes_data(text), cc_bytes_size(text), nullptr);
    CHECK(again != nullptr);
    cc_bytes* text2 = cc_profile_write(again);
    CHECK(cc_bytes_size(text) == cc_bytes_size(text2)
          && std::memcmp(cc_bytes_data(text), cc_bytes_data(text2), static_cast<size_t>(cc_bytes_size(text))) == 0);
    cc_bytes_destroy(text2);
    cc_profile_destroy(again);
    // A chain naming an unknown role, or a goal nobody defines, is refused.
    std::string bad = json;
    bad.replace(bad.find("\"roles\": [\n        \"spine\""), std::strlen("\"roles\": [\n        \"spine\""),
                "\"roles\": [\n        \"spline\"");
    CHECK(cc_profile_read(reinterpret_cast<const uint8_t*>(bad.data()), static_cast<int64_t>(bad.size()), nullptr) == nullptr);
    cc_bytes_destroy(text);
    cc_profile_destroy(p);

    // Auto-map writes the chains (first .. last mapped role) and goals.
    Rigged r = make(Body{});
    std::vector<std::string> chains;
    for (int32_t i = 0, n = cc_char_chain_count(r.chr); i < n; ++i) {
        const char *name = nullptr, *start = nullptr, *end = nullptr, *goal = nullptr;
        CHECK(cc_char_chain_get(r.chr, i, &name, &start, &end, &goal) == CC_OK);
        chains.emplace_back(name);
        if (std::string(name) == "leg_l") {
            CHECK(std::string(start) == r.bones[static_cast<size_t>(r.find("upper_leg_l"))].name
                  || cr_rig_find(r.rig, start) == r.find("upper_leg_l"));
            CHECK(cr_rig_find(r.rig, end) == r.find("foot_l"));
            CHECK(std::string(goal) == "foot_l");
        }
        if (std::string(name) == "spine") CHECK(cr_rig_find(r.rig, start) == r.find("spine") && cr_rig_find(r.rig, end) == r.find("spine"));
    }
    for (const char* want : {"spine", "head", "arm_l", "arm_r", "leg_l", "leg_r"}) {
        CHECK(std::find(chains.begin(), chains.end(), want) != chains.end());
    }
    CHECK(std::find(chains.begin(), chains.end(), "neck") == chains.end());  // no neck joint: no chain
    CHECK(cc_char_goal_count(r.chr) == 4);
    destroy(r);
}

void test_self_retarget_reproduces_the_pose() {
    for (bool twist : {false, true}) {
        Body b;
        b.twist = twist;
        b.spines = 3;
        Rigged r = make(b);
        cc_retargeter* rt = cc_retarget_create(r.rig, r.chr, r.rig, r.chr);
        CHECK(rt != nullptr);
        CHECK(std::fabs(cc_retarget_scale(rt) - 1.f) < 1e-6f);
        std::vector<float> out(static_cast<size_t>(r.count()) * 9);
        std::vector<uint8_t> driven(static_cast<size_t>(r.count()));
        double worst = 0.0;
        for (int k = 0; k < 24; ++k) {
            const std::vector<float> src = walk_pose(r, k / 24.0);
            CHECK(cc_retarget_pose(rt, src.data(), out.data(), driven.data()) == CC_OK);
            const std::vector<float> ws = worlds(r, src), wd = worlds(r, out);
            for (int j = 0; j < r.count(); ++j) {
                CHECK(driven[static_cast<size_t>(j)] == 1);  // every joint is the hips or in a chain
                worst = std::max(worst, mat_diff(ws, wd, j));
            }
        }
        std::printf("[retarget] self (twisted axes %d): worst world-matrix difference %.3g\n", twist ? 1 : 0, worst);
        CHECK(worst < 2e-5);
        cc_retarget_destroy(rt);
        destroy(r);
    }
}

struct Pair {
    Rigged src, dst;
    cc_retargeter* rt = nullptr;
    Pair() {
        src = make(Body{});
        Body t;
        t.leg = 0.7;
        t.arm = 1.2;
        t.spines = 3;
        t.faces_pz = true;
        t.twist = true;
        dst = make(t);
        CHECK(cc_char_set_string(dst.chr, CC_FIELD_FACING, "+z") == CC_OK);
        rt = cc_retarget_create(src.rig, src.chr, dst.rig, dst.chr);
        CHECK(rt != nullptr);
    }
    ~Pair() {
        cc_retarget_destroy(rt);
        destroy(src);
        destroy(dst);
    }
};

// Where a target foot should land (GROUND goals): horizontally the source foot's offset from the source hip joint,
// turned into the target's facing (+Z: 180 degrees) and scaled, from the target hip joint; vertically the target
// foot's reference height + the source foot's rise over its own reference height, scaled.
V expected_goal(const Pair& p, const std::vector<float>& ws, const std::vector<float>& wd, const char* goal, double stride) {
    const std::string side = std::string(goal).substr(std::string(goal).size() - 2);
    const std::string upper = "upper_leg" + side;
    const double s = cc_retarget_scale(p.rt);
    const V off = at(ws, p.src.find(goal)) - at(ws, p.src.find(upper.c_str()));
    const V turned = ry(V{off.x * s * stride, 0.0, off.z * s * stride}, 180.0);
    const std::vector<float> wsr = worlds(p.src, rest(p.src)), wdr = worlds(p.dst, rest(p.dst));
    const double rise = at(ws, p.src.find(goal)).y - at(wsr, p.src.find(goal)).y;
    const V start = at(wd, p.dst.find(upper.c_str()));
    return V{start.x + turned.x, at(wdr, p.dst.find(goal)).y + rise * s, start.z + turned.z};
}

void test_different_proportions() {
    Pair p;
    // Hip height above the ankles: 0.9 for the source, 0.63 for the target.
    CHECK(std::fabs(cc_retarget_scale(p.rt) - 0.7f) < 1e-5f);
    int ik = 0;
    for (int32_t i = 0, n = cc_retarget_chain_count(p.rt); i < n; ++i) {
        const char* name = nullptr;
        int32_t sj = 0, dj = 0, goal = 0;
        cc_retarget_chain_get(p.rt, i, &name, &sj, &dj, &goal);
        if (std::string(name) == "spine") CHECK(sj == 1 && dj == 3);
        ik += goal;
    }
    CHECK(ik == 4);
    std::vector<float> out(static_cast<size_t>(p.dst.count()) * 9);
    double worst_goal = 0.0, worst_spine = 0.0;
    int forward_knees = 0, bent = 0;
    for (int k = 0; k < 24; ++k) {
        const std::vector<float> src = walk_pose(p.src, k / 24.0);
        CHECK(cc_retarget_pose(p.rt, src.data(), out.data(), nullptr) == CC_OK);
        const std::vector<float> ws = worlds(p.src, src), wd = worlds(p.dst, out);
        for (const char* foot : {"foot_l", "foot_r"}) {
            worst_goal = std::max(worst_goal, len(at(wd, p.dst.find(foot)) - expected_goal(p, ws, wd, foot, 1.0)));
        }
        // Knees bend toward the target's forward (+Z).
        for (const char* side : {"_l", "_r"}) {
            const V a = at(wd, p.dst.find((std::string("upper_leg") + side).c_str()));
            const V m = at(wd, p.dst.find((std::string("lower_leg") + side).c_str()));
            const V e = at(wd, p.dst.find((std::string("foot") + side).c_str()));
            const V ae = (e - a) * (1.0 / len(e - a));
            const V off = (m - a) - ae * dot(m - a, ae);
            if (len(off) > 0.01) {
                ++bent;
                forward_knees += off.z > 0.0 ? 1 : 0;
            }
        }
        // The chest ends up turned like the source spine (global FK: every target spine bone takes its delta). Compare
        // the turn each applies to a probe vector, in each one's profile frame (the target's is turned 180 degrees).
        auto turn_of = [](const std::vector<float>& w, const std::vector<float>& wr, int j, V v) {
            // R_pose * R_rest^-1 * v
            const float* a = w.data() + static_cast<size_t>(j) * 16;
            const float* b = wr.data() + static_cast<size_t>(j) * 16;
            auto col = [](const float* m, int c) { return V{m[c * 4], m[c * 4 + 1], m[c * 4 + 2]}; };
            const V rinv{dot(col(b, 0), v), dot(col(b, 1), v), dot(col(b, 2), v)};
            return col(a, 0) * rinv.x + col(a, 1) * rinv.y + col(a, 2) * rinv.z;
        };
        const std::vector<float> wsr = worlds(p.src, rest(p.src)), wdr = worlds(p.dst, rest(p.dst));
        for (V probe : {V{1, 0, 0}, V{0, 1, 0}, V{0, 0, 1}}) {
            const V s_turn = turn_of(ws, wsr, p.src.find("spine"), probe);
            const V d_turn = ry(turn_of(wd, wdr, p.dst.find("upper_chest"), ry(probe, 180.0)), 180.0);
            worst_spine = std::max(worst_spine, len(s_turn - d_turn));
        }
    }
    std::printf("[retarget] proportions: feet within %.3g of their goals; %d/%d bent knees forward; chest vs spine %.3g\n",
                worst_goal, forward_knees, bent, worst_spine);
    CHECK(worst_goal < 1e-4);
    CHECK(bent > 20 && forward_knees == bent);
    CHECK(worst_spine < 1e-4);
}

void test_op_stack() {
    Pair p;
    const std::vector<float> src = walk_pose(p.src, 0.2);
    const std::vector<float> ws = worlds(p.src, src);
    const std::vector<float> ref = rest(p.dst);
    std::vector<float> out(static_cast<size_t>(p.dst.count()) * 9);
    const int hips = p.dst.find("hips");
    // Default stack: 5 ops.
    CHECK(cc_retarget_op_count(p.rt) == 5);
    // Pelvis weight 0: the hips keep the reference pose.
    cc_retarget_op ops[5];
    for (int i = 0; i < 5; ++i) cc_retarget_op_get(p.rt, i, &ops[i]);
    ops[0].weight = 0.f;
    CHECK(cc_retarget_set_ops(p.rt, ops, 5) == CC_OK);
    CHECK(cc_retarget_pose(p.rt, src.data(), out.data(), nullptr) == CC_OK);
    for (size_t c = 0; c < 9; ++c) CHECK(out[static_cast<size_t>(hips) * 9 + c] == ref[static_cast<size_t>(hips) * 9 + c]);
    // Without IK (pelvis, FK, stride warp 1.3): FK carries the source's angles, not the warped stride, so the feet
    // miss the warped goals (this target is a uniformly scaled copy: unwarped, FK alone lands on them).
    ops[0].weight = 1.f;
    ops[2].p[0] = 1.3f;
    CHECK(cc_retarget_set_ops(p.rt, ops, 3) == CC_OK);
    CHECK(cc_retarget_pose(p.rt, src.data(), out.data(), nullptr) == CC_OK);
    std::vector<float> wd = worlds(p.dst, out);
    CHECK(len(at(wd, p.dst.find("foot_l")) - expected_goal(p, ws, wd, "foot_l", 1.3)) > 1e-3);
    // With ROOT_HEIGHT and IK the feet land on the warped goals.
    CHECK(cc_retarget_set_ops(p.rt, ops, 5) == CC_OK);
    CHECK(cc_retarget_pose(p.rt, src.data(), out.data(), nullptr) == CC_OK);
    wd = worlds(p.dst, out);
    CHECK(len(at(wd, p.dst.find("foot_l")) - expected_goal(p, ws, wd, "foot_l", 1.3)) < 1e-4);
    // A chain switched off keeps the target's reference pose.
    CHECK(cc_retarget_set_ops(p.rt, ops, 5) == CC_OK);
    cc_retarget_chain cs{};
    CHECK(cc_retarget_get_chain(p.rt, "head", &cs) == CC_OK && cs.fk_weight == 1.f && cs.auto_align == 1);
    cs.fk_mode = CC_FK_OFF;
    CHECK(cc_retarget_set_chain(p.rt, "head", &cs) == CC_OK);
    std::vector<uint8_t> driven(static_cast<size_t>(p.dst.count()));
    CHECK(cc_retarget_pose(p.rt, src.data(), out.data(), driven.data()) == CC_OK);
    const int head = p.dst.find("head");
    CHECK(driven[static_cast<size_t>(head)] == 0);
    for (size_t c = 3; c < 6; ++c) CHECK(out[static_cast<size_t>(head) * 9 + c] == ref[static_cast<size_t>(head) * 9 + c]);
    CHECK(cc_retarget_set_chain(p.rt, "nope", &cs) == CC_ERR_RANGE);
    cc_retarget_op badop{};
    badop.type = 99;
    CHECK(cc_retarget_set_ops(p.rt, &badop, 1) == CC_ERR_ARG);
}

// A source clip: the walk pose keyed at 30/s over a 1 s loop, a curve, a notify, a marker, root motion on the hips.
cm_clip* source_clip(const Rigged& r) {
    cm_clip* c = cm_clip_create();
    cm_header h{30.f, 1.f, 1, 0, 0.f, 0.f, 0};
    cm_clip_header_set(c, &h);
    cm_clip_set_string(c, CM_FIELD_NAME, "walk");
    const int n = r.count();
    std::vector<std::vector<cr_key>> keys(static_cast<size_t>(n));
    for (int k = 0; k <= 30; ++k) {
        const std::vector<float> l = walk_pose(r, k / 30.0);
        for (int j = 0; j < n; ++j) {
            cr_key key{};
            key.time = static_cast<float>(k) / 30.f;
            std::memcpy(key.pos, l.data() + j * 9, sizeof key.pos);
            std::memcpy(key.rot_deg, l.data() + j * 9 + 3, sizeof key.rot_deg);
            std::memcpy(key.scale, l.data() + j * 9 + 6, sizeof key.scale);
            key.easing = CR_EASE_LINEAR;
            keys[static_cast<size_t>(j)].push_back(key);
        }
    }
    for (int j = 0; j < n; ++j) {
        const char* name = r.bones[static_cast<size_t>(j)].name.c_str();
        cm_clip_track_set(c, name, name, nullptr, 1, keys[static_cast<size_t>(j)].data(), 31);
    }
    const cm_curve_key ck[] = {{0.f, 0.f, 0.f, 0.f, CM_INTERP_LINEAR}, {1.f, 1.f, 0.f, 0.f, CM_INTERP_LINEAR}};
    cm_clip_curve_set(c, "foot_lock_l", ck, 2);
    cm_clip_notify_add(c, "step", 0.25f, 0.f, nullptr);
    cm_clip_marker_add(c, "L", 0.5f);
    cm_clip_root_motion_set(c, "hips", CM_ROOT_X | CM_ROOT_Z);
    return c;
}

void test_bake_matches_runtime() {
    Pair p;
    cm_clip* src = source_clip(p.src);
    int32_t rc = -1;
    cm_clip* baked = cc_retarget_bake(p.rt, src, 30.f, &rc);
    CHECK(baked != nullptr && rc == CC_OK);
    // Every target bone is driven (hips + chains); 31 keys each; timing data kept; root motion on the target hips.
    CHECK(cm_clip_track_count(baked) == p.dst.count());
    const char *pid = nullptr, *pname = nullptr, *role = nullptr;
    int32_t bone = 0, kc = 0;
    cm_clip_track_get(baked, 0, &pid, &pname, &role, &bone, &kc);
    CHECK(kc == 31 && bone == 1);
    CHECK(cm_clip_curve_count(baked) == 1 && cm_clip_notify_count(baked) == 1 && cm_clip_marker_count(baked) == 1);
    const char* rm = nullptr;
    uint32_t axes = 0;
    cm_clip_root_motion_get(baked, &rm, &axes);
    CHECK(rm != nullptr && cr_rig_find(p.dst.rig, rm) == p.dst.find("hips") && axes == (CM_ROOT_X | CM_ROOT_Z));

    const std::string json = R"({"format": "omanimgraph", "version": "1.0", "slots": ["walk"], "root": "walk",
                                 "nodes": [{"id": "walk", "type": "clip", "slot": "walk"}]})";
    int32_t grc = 0;
    cg_graph* g = cg_graph_read(json.data(), static_cast<int64_t>(json.size()), &grc);
    CHECK(g != nullptr);
    cg_instance* live = cg_instance_create(g, p.dst.rig, p.dst.chr, nullptr);
    cg_instance* bake = cg_instance_create(g, p.dst.rig, p.dst.chr, nullptr);
    CHECK(cg_instance_bind_slot_retarget(live, 0, src, p.rt, nullptr) == CG_OK);
    CHECK(cg_instance_bind_slot(bake, 0, baked, nullptr) == CG_OK);
    // The retargeter's target must be the instance's rig.
    cg_instance* wrong = cg_instance_create(g, p.src.rig, p.src.chr, nullptr);
    CHECK(cg_instance_bind_slot_retarget(wrong, 0, src, p.rt, nullptr) == CG_ERR_ARG);
    cg_instance_destroy(wrong);
    const int n = p.dst.count();
    std::vector<float> wa(static_cast<size_t>(n) * 16), wb(wa.size());
    double at_keys = 0.0, between = 0.0, motion = 0.0;
    for (int step = 0; step < 90; ++step) {
        const float dt = step < 60 ? 1.f / 30.f : 1.f / 45.f;
        CHECK(cg_instance_evaluate(live, dt, nullptr, 0, nullptr, nullptr, wa.data(), nullptr) == CG_OK);
        CHECK(cg_instance_evaluate(bake, dt, nullptr, 0, nullptr, nullptr, wb.data(), nullptr) == CG_OK);
        double d = 0.0;
        for (int j = 0; j < n; ++j) d = std::max(d, mat_diff(wa, wb, j));
        if (step < 60) {
            at_keys = std::max(at_keys, d);
        } else {
            between = std::max(between, d);
        }
        cr_root_motion ma{}, mb{};
        cg_instance_root_motion(live, &ma);
        cg_instance_root_motion(bake, &mb);
        for (int c = 0; c < 3; ++c) motion = std::max(motion, static_cast<double>(std::fabs(ma.translation[c] - mb.translation[c])));
    }
    std::printf("[retarget] baked vs runtime: %.3g at key times, %.3g between keys, root motion %.3g\n", at_keys, between, motion);
    CHECK(at_keys < 1e-4);
    CHECK(between < 2e-2);
    CHECK(motion < 1e-3);
    cg_instance_destroy(live);
    cg_instance_destroy(bake);
    cg_graph_destroy(g);
    cm_clip_destroy(baked);
    cm_clip_destroy(src);
}

void test_determinism() {
    Pair a, b;
    std::mt19937 rng(160);
    std::vector<float> oa(static_cast<size_t>(a.dst.count()) * 9), ob(oa.size());
    for (int k = 0; k < 50; ++k) {
        const double phase = std::uniform_real_distribution<double>(0.0, 1.0)(rng);
        const std::vector<float> src = walk_pose(a.src, phase);
        CHECK(cc_retarget_pose(a.rt, src.data(), oa.data(), nullptr) == CC_OK);
        CHECK(cc_retarget_pose(b.rt, src.data(), ob.data(), nullptr) == CC_OK);
        CHECK(std::memcmp(oa.data(), ob.data(), oa.size() * sizeof(float)) == 0);
    }
}

}  // namespace

int main() {
    test_profile_chains_and_auto_map();
    test_self_retarget_reproduces_the_pose();
    test_different_proportions();
    test_op_stack();
    test_bake_matches_runtime();
    test_determinism();
    if (failures != 0) {
        std::fprintf(stderr, "retarget: %d check(s) failed\n", failures);
        return 1;
    }
    std::printf("retarget: all checks passed\n");
    return 0;
}
