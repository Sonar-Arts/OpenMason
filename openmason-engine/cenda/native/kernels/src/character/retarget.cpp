// Cenda animation retargeting (OpenMason #160): contract and the op stack in cenda/retarget.h.
//
// Frames: every rotation is a unit quaternion in double; a joint's WORLD rotation is the rotation of its posed
// world matrix (model space), its parent frame the rotation of the frame its parent hands down (a bone's tail
// frame, a part's world). The PROFILE frame of a character is its model frame turned so it faces -Z (F, about +Y):
// an orientation R in model space is F·R there, a world delta D is F·D·F^-1. Retargeting works on deltas from the
// two reference poses, so neither the bone axes nor the rest orientations of the two skeletons need to agree.

#include "character_internal.hpp"

#include "cenda/retarget.h"

#include "../rig/rig_quat.hpp"

#include <array>
#include <cstring>
#include <limits>
#include <memory>
#include <new>
#include <unordered_map>

using cenda::rig::Joint;
using cenda::rig::Mat4;
using cenda::rig::Pose;
using cenda::rig::Quat;
using cenda::rig::qconj;
using cenda::rig::qdot;
using cenda::rig::qmul;
using cenda::rig::qnormalize;
using cenda::rig::qslerp;

namespace {

namespace ch = cenda::character;
namespace oc = cenda::omformat::omchar;

constexpr double EPS = 1e-9;
constexpr int MAX_OPS = 16;

struct V3 {
    double x = 0.0, y = 0.0, z = 0.0;
};
V3 operator+(V3 a, V3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
V3 operator-(V3 a, V3 b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
V3 operator*(V3 a, double s) { return {a.x * s, a.y * s, a.z * s}; }
double dot(V3 a, V3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
V3 cross(V3 a, V3 b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
double length(V3 a) { return std::sqrt(dot(a, a)); }
V3 normalized(V3 a) {
    const double l = length(a);
    return l > EPS ? a * (1.0 / l) : V3{};
}

V3 rotate(const Quat& q, V3 v) {
    const Quat r = qmul(qmul(q, Quat{0.0, v.x, v.y, v.z}), qconj(q));
    return {r.x, r.y, r.z};
}

V3 apply(const Mat4& m, V3 p) {
    const double in[3] = {p.x, p.y, p.z};
    double out[3];
    m.transform_point(in, out);
    return {out[0], out[1], out[2]};
}

// The rotation of an affine matrix (columns normalized; Shepperd's method).
Quat rotation_of(const Mat4& m) {
    double c[3][3];
    for (int col = 0; col < 3; ++col) {
        const double l = std::sqrt(m.at(0, col) * m.at(0, col) + m.at(1, col) * m.at(1, col) + m.at(2, col) * m.at(2, col));
        for (int row = 0; row < 3; ++row) c[row][col] = l > EPS ? m.at(row, col) / l : (row == col ? 1.0 : 0.0);
    }
    const double tr = c[0][0] + c[1][1] + c[2][2];
    Quat q;
    if (tr > 0.0) {
        const double s = std::sqrt(tr + 1.0) * 2.0;
        q = {0.25 * s, (c[2][1] - c[1][2]) / s, (c[0][2] - c[2][0]) / s, (c[1][0] - c[0][1]) / s};
    } else if (c[0][0] > c[1][1] && c[0][0] > c[2][2]) {
        const double s = std::sqrt(1.0 + c[0][0] - c[1][1] - c[2][2]) * 2.0;
        q = {(c[2][1] - c[1][2]) / s, 0.25 * s, (c[0][1] + c[1][0]) / s, (c[0][2] + c[2][0]) / s};
    } else if (c[1][1] > c[2][2]) {
        const double s = std::sqrt(1.0 + c[1][1] - c[0][0] - c[2][2]) * 2.0;
        q = {(c[0][2] - c[2][0]) / s, (c[0][1] + c[1][0]) / s, 0.25 * s, (c[1][2] + c[2][1]) / s};
    } else {
        const double s = std::sqrt(1.0 + c[2][2] - c[0][0] - c[1][1]) * 2.0;
        q = {(c[1][0] - c[0][1]) / s, (c[0][2] + c[2][0]) / s, (c[1][2] + c[2][1]) / s, 0.25 * s};
    }
    return qnormalize(q);
}

// The shortest rotation taking unit a to unit b (exactly identity when they are equal).
Quat from_to(V3 a, V3 b) {
    const double d = dot(a, b);
    if (d >= 1.0 - 1e-15) return Quat{};
    if (d <= -1.0 + 1e-12) {
        V3 axis = cross(a, V3{1, 0, 0});
        if (length(axis) < 1e-6) axis = cross(a, V3{0, 1, 0});
        axis = normalized(axis);
        return Quat{0.0, axis.x, axis.y, axis.z};
    }
    const V3 c = cross(a, b);
    return qnormalize(Quat{1.0 + d, c.x, c.y, c.z});
}

// Model -> profile frame (the profile faces -Z).
Quat facing_turn(const std::string& f) {
    const double h = 3.14159265358979323846 / 2.0;
    if (f == "+z") return cenda::rig::qaxis(0, 1, 0, 2.0 * h);
    if (f == "-x") return cenda::rig::qaxis(0, 1, 0, -h);
    if (f == "+x") return cenda::rig::qaxis(0, 1, 0, h);
    return Quat{};
}

Quat slerp_exact(const Quat& a, const Quat& b, double t) {
    if (t <= 0.0) return a;
    if (t >= 1.0) return b;
    return qnormalize(qslerp(a, b, t));
}

// One side of the retarget: a rig, its character, its reference pose and the working pose.
struct Side {
    const cr_rig* rig = nullptr;
    const cc_char* chr = nullptr;
    Quat F;                       // model -> profile
    std::vector<Pose> ref;        // reference locals
    std::vector<Mat4> ref_world, ref_child;
    std::vector<Pose> pose;       // working locals
    std::vector<Mat4> world, child;
    int hips = -1;
    double hip_height = 0.0;

    [[nodiscard]] int count() const { return static_cast<int>(rig->joints.size()); }

    void fk(const std::vector<Pose>& p, std::vector<Mat4>& w, std::vector<Mat4>& c) const {
        for (int j : rig->order) fk_one(j, p, w, c);
    }

    void fk_one(int j, const std::vector<Pose>& p, std::vector<Mat4>& w, std::vector<Mat4>& c) const {
        const Joint& jt = rig->joints[static_cast<size_t>(j)];
        const Mat4 parent = jt.parent >= 0 ? c[static_cast<size_t>(jt.parent)] : Mat4::identity();
        w[static_cast<size_t>(j)] = parent * cenda::rig::joint_local_matrix(jt, p[static_cast<size_t>(j)]);
        c[static_cast<size_t>(j)] = cenda::rig::joint_child_frame(jt, w[static_cast<size_t>(j)]);
    }

    // A joint's point: a bone's head, a part's pivot.
    [[nodiscard]] V3 point(const std::vector<Mat4>& w, int j) const {
        const Joint& jt = rig->joints[static_cast<size_t>(j)];
        const V3 local = jt.kind == CR_JOINT_PART ? V3{jt.origin[0], jt.origin[1], jt.origin[2]} : V3{};
        return apply(w[static_cast<size_t>(j)], local);
    }

    [[nodiscard]] Quat parent_rot(const std::vector<Mat4>& c, int j) const {
        const int p = rig->joints[static_cast<size_t>(j)].parent;
        return p >= 0 ? rotation_of(c[static_cast<size_t>(p)]) : Quat{};
    }

    [[nodiscard]] Mat4 parent_frame(const std::vector<Mat4>& c, int j) const {
        const int p = rig->joints[static_cast<size_t>(j)].parent;
        return p >= 0 ? c[static_cast<size_t>(p)] : Mat4::identity();
    }

    [[nodiscard]] bool descends(int j, int ancestor) const {
        for (int a = j; a >= 0; a = rig->joints[static_cast<size_t>(a)].parent) {
            if (a == ancestor) return true;
        }
        return false;
    }

    int role(const char* name) const {
        const char* id = cc_char_role_joint(chr, name);
        return id == nullptr ? -1 : ch::rig_index(rig, id);
    }
};

struct ChainSide {
    std::vector<int> joints;
    std::vector<double> u;    // normalized position along the chain (reference)
    std::vector<V3> dir;      // reference direction toward the next joint, profile frame (zero = none)
};

struct Pair {
    std::string name;
    ChainSide s, d;
    cc_retarget_chain set{1.f, CC_FK_INTERPOLATED, CC_FK_GLOBAL, 1.f, 1, CC_GOAL_AUTO};
    bool ground = false;         // the goal space resolved (AUTO -> by the source reference)
    bool has_bend = false;       // the way the limb bends when the source limb is straight (profile frame)
    V3 bend;
    double length_ratio = 1.0;   // target / source chain length (reference)
    std::vector<int> map_i;      // per target joint: the source joint below it
    std::vector<double> map_f;   // and the fraction toward the next one
    std::vector<Quat> align;     // auto-align turn per target joint (profile frame)
    int goal = -1;               // IK: the stack modifier, -1 = none
};

}  // namespace

struct cc_retargeter {
    Side src, dst;
    std::vector<Pair> chains;
    std::vector<cc_retarget_op> ops;
    double scale = 1.0;
    std::vector<int> chain_of;      // per target joint: pair index, -1 = none
    std::vector<int> index_in;      // per target joint: its index in that chain
    cr_modstack* ik = nullptr;
    cr_poses* poses = nullptr;
    std::vector<int> goal_pairs;    // IK pairs in stack order
    std::vector<uint8_t> driven;
    std::vector<float> scratch;
    std::vector<V3> goals, bends;
    std::string error_text;

    ~cc_retargeter() {
        if (ik != nullptr) cr_modstack_destroy(ik);
        if (poses != nullptr) cr_poses_destroy(poses);
    }
};

namespace {

bool init_side(Side& s, const cr_rig* rig, const cc_char* chr) {
    s.rig = rig;
    s.chr = chr;
    s.F = facing_turn(chr->def.facing);
    const size_t n = rig->joints.size();
    s.ref.resize(n);
    for (size_t i = 0; i < n; ++i) {
        const Joint& j = rig->joints[i];
        std::memcpy(s.ref[i].pos, j.pos, sizeof j.pos);
        std::memcpy(s.ref[i].rot, j.rot, sizeof j.rot);
        std::memcpy(s.ref[i].scale, j.scale, sizeof j.scale);
    }
    for (const oc::JointRotation& r : chr->def.reference_pose) {
        const int j = ch::rig_index(rig, r.joint);
        if (j < 0) continue;
        float* rot = s.ref[static_cast<size_t>(j)].rot;
        rot[0] = r.rot.x;
        rot[1] = r.rot.y;
        rot[2] = r.rot.z;
    }
    s.ref_world.resize(n);
    s.ref_child.resize(n);
    s.fk(s.ref, s.ref_world, s.ref_child);
    s.pose = s.ref;
    s.world = s.ref_world;
    s.child = s.ref_child;
    s.hips = s.role("hips");
    return true;
}

// A chain's joints: the path start..end, keeping the start's kind.
bool chain_side(const Side& s, const oc::Chain& c, ChainSide& out) {
    const int start = ch::rig_index(s.rig, c.start), end = ch::rig_index(s.rig, c.end);
    if (start < 0 || end < 0) return false;
    std::vector<int> path;
    for (int j = end; j >= 0; j = s.rig->joints[static_cast<size_t>(j)].parent) {
        path.push_back(j);
        if (j == start) break;
        if (path.size() > s.rig->joints.size()) return false;
    }
    if (path.back() != start) return false;
    std::reverse(path.begin(), path.end());
    const int kind = s.rig->joints[static_cast<size_t>(start)].kind;
    for (int j : path) {
        if (s.rig->joints[static_cast<size_t>(j)].kind == kind) out.joints.push_back(j);
    }
    const size_t n = out.joints.size();
    std::vector<V3> pts(n);
    for (size_t k = 0; k < n; ++k) pts[k] = s.point(s.ref_world, out.joints[k]);
    out.u.assign(n, 0.0);
    double total = 0.0;
    for (size_t k = 1; k < n; ++k) {
        total += length(pts[k] - pts[k - 1]);
        out.u[k] = total;
    }
    for (size_t k = 0; k < n; ++k) {
        out.u[k] = total > EPS ? out.u[k] / total : (n > 1 ? static_cast<double>(k) / static_cast<double>(n - 1) : 0.0);
    }
    out.dir.assign(n, V3{});
    for (size_t k = 0; k < n; ++k) {
        V3 d;
        if (k + 1 < n) {
            d = pts[k + 1] - pts[k];
        } else {
            const int j = out.joints[k];
            if (s.rig->joints[static_cast<size_t>(j)].kind == CR_JOINT_BONE) {
                const Mat4& c2 = s.ref_child[static_cast<size_t>(j)];
                d = V3{c2.at(0, 3), c2.at(1, 3), c2.at(2, 3)} - pts[k];
            }
            if (length(d) < EPS && k > 0) d = pts[k] - pts[k - 1];
        }
        out.dir[k] = normalized(rotate(s.F, d));
    }
    return true;
}

// Hips height above the lowest IK-goal joint, in the reference pose.
double hip_height(const Side& s, const std::vector<int>& goal_joints) {
    if (s.hips < 0) return 0.0;
    double ground = goal_joints.empty() ? 0.0 : std::numeric_limits<double>::infinity();
    for (int j : goal_joints) ground = std::min(ground, s.point(s.ref_world, j).y);
    return s.point(s.ref_world, s.hips).y - ground;
}

void default_ops(cc_retargeter& rt) {
    rt.ops.clear();
    for (int t : {CC_RETARGET_PELVIS, CC_RETARGET_FK, CC_RETARGET_STRIDE_WARP, CC_RETARGET_ROOT_HEIGHT, CC_RETARGET_IK}) {
        cc_retarget_op op{};
        op.type = t;
        op.weight = 1.f;
        if (t == CC_RETARGET_STRIDE_WARP) op.p[0] = 1.f;
        rt.ops.push_back(op);
    }
}

double chain_length(const Side& s, const ChainSide& c) {
    double l = 0.0;
    for (size_t k = 1; k < c.joints.size(); ++k) l += length(s.point(s.ref_world, c.joints[k]) - s.point(s.ref_world, c.joints[k - 1]));
    return l;
}

// AUTO goal space: GROUND when the source chain's end lies in the lowest quarter of the source's hip height.
void resolve_goal(const cc_retargeter& rt, Pair& p) {
    const double ls = chain_length(rt.src, p.s), ld = chain_length(rt.dst, p.d);
    p.length_ratio = ls > EPS && ld > EPS ? ld / ls : 1.0;
    if (p.set.goal_space == CC_GOAL_GROUND || p.set.goal_space == CC_GOAL_CHAIN) {
        p.ground = p.set.goal_space == CC_GOAL_GROUND;
        return;
    }
    const Side& S = rt.src;
    if (S.hips < 0 || S.hip_height <= EPS) {
        p.ground = false;
        return;
    }
    const double hips_y = S.point(S.ref_world, S.hips).y;
    const double end_y = S.point(S.ref_world, p.s.joints.back()).y;
    p.ground = end_y <= hips_y - 0.75 * S.hip_height;
}

void compute_mapping(Pair& p) {
    const size_t nd = p.d.joints.size(), ns = p.s.joints.size();
    p.map_i.assign(nd, 0);
    p.map_f.assign(nd, 0.0);
    p.align.assign(nd, Quat{});
    for (size_t k = 0; k < nd; ++k) {
        size_t i = 0;
        double f = 0.0;
        if (p.set.fk_mode == CC_FK_ONE_TO_ONE) {
            i = std::min(k, ns - 1);
        } else {
            const double u = p.d.u[k];
            for (size_t m = 0; m < ns; ++m) {
                if (p.s.u[m] <= u + 1e-12) i = m;
            }
            if (i + 1 < ns) {
                const double span = p.s.u[i + 1] - p.s.u[i];
                f = span > EPS ? std::clamp((u - p.s.u[i]) / span, 0.0, 1.0) : 0.0;
                if (f < 1e-12) f = 0.0;
            }
        }
        p.map_i[k] = static_cast<int>(i);
        p.map_f[k] = f;
        if (p.set.auto_align != 0) {
            const V3 a = p.d.dir[k], b = p.s.dir[i];
            if (length(a) > 0.5 && length(b) > 0.5) p.align[k] = from_to(a, b);
        }
    }
}

// Writes a world rotation into joint j's local rotation (blended by w from the current one).
void set_world_rot(Side& s, int j, const Quat& world, double w) {
    Pose& p = s.pose[static_cast<size_t>(j)];
    const Quat local = qnormalize(qmul(qconj(s.parent_rot(s.child, j)), world));
    if (w >= 1.0) {
        cenda::rig::to_euler_deg(local, p.rot);
    } else if (w > 0.0) {
        cenda::rig::to_euler_deg(slerp_exact(cenda::rig::from_euler_deg(p.rot), local, w), p.rot);
    }
}

// Moves joint j's point to `at` (model space) through its local position, blended by w.
void set_point(Side& s, int j, V3 at, double w) {
    const Joint& jt = s.rig->joints[static_cast<size_t>(j)];
    const V3 local = apply(s.parent_frame(s.child, j).affine_inverse(), at) - V3{jt.origin[0], jt.origin[1], jt.origin[2]};
    Pose& p = s.pose[static_cast<size_t>(j)];
    const double want[3] = {local.x, local.y, local.z};
    for (int c = 0; c < 3; ++c) p.pos[c] = static_cast<float>(p.pos[c] + (want[c] - p.pos[c]) * std::clamp(w, 0.0, 1.0));
}

struct Frame {
    std::vector<Quat> src_world_p;  // source world rotations, profile frame (chain joints + hips)
    V3 hips0;                       // the target hips after PELVIS
    double sh = 1.0, sv = 1.0;      // pelvis scales used for goals
    double stride = 1.0;
    bool goals = false;
};

void compute_goals(cc_retargeter& rt, Frame& fr) {
    Side& S = rt.src;
    Side& D = rt.dst;
    const Quat to_dst = qmul(qconj(D.F), S.F);
    rt.goals.resize(rt.goal_pairs.size());
    rt.bends.resize(rt.goal_pairs.size());
    for (size_t g = 0; g < rt.goal_pairs.size(); ++g) {
        const Pair& p = rt.chains[static_cast<size_t>(rt.goal_pairs[g])];
        const V3 s_start = S.point(S.world, p.s.joints.front()), s_end = S.point(S.world, p.s.joints.back());
        const V3 d_start = D.point(D.world, p.d.joints.front());
        if (p.ground) {
            // Horizontal: from the target chain start, at the pelvis scale; vertical: over the reference heights.
            const V3 off = rotate(S.F, s_end - s_start);
            const V3 h = rotate(qconj(D.F), V3{off.x * fr.sh * fr.stride, 0.0, off.z * fr.sh * fr.stride});
            const double rise = s_end.y - S.point(S.ref_world, p.s.joints.back()).y;
            rt.goals[g] = V3{d_start.x + h.x, D.point(D.ref_world, p.d.joints.back()).y + rise * fr.sv, d_start.z + h.z};
        } else {
            rt.goals[g] = d_start + rotate(to_dst, s_end - s_start) * p.length_ratio;
        }
        // The source limb's bend (its middle joint off the line from its first to its last joint), when clear.
        V3 bend;
        if (p.s.joints.size() >= 3) {
            const V3 m = S.point(S.world, p.s.joints[p.s.joints.size() - 2]);
            const V3 ae = normalized(s_end - s_start);
            const V3 off_line = (m - s_start) - ae * dot(m - s_start, ae);
            if (length(off_line) > 0.05 * length(m - s_start)) bend = rotate(to_dst, normalized(off_line));
        }
        // A straight source limb: the chain's bend direction, turned with the chain start's parent.
        if (length(bend) < 0.5 && p.has_bend) {
            const int parent = D.rig->joints[static_cast<size_t>(p.d.joints.front())].parent;
            Quat turn;
            if (parent >= 0) {
                turn = qmul(rotation_of(D.world[static_cast<size_t>(parent)]),
                            qconj(rotation_of(D.ref_world[static_cast<size_t>(parent)])));
            }
            bend = rotate(turn, rotate(qconj(D.F), p.bend));
        }
        rt.bends[g] = bend;
    }
    fr.goals = true;
}

int32_t run(cc_retargeter& rt, const float* src_local, float* out_local, uint8_t* out_driven) {
    Side& S = rt.src;
    Side& D = rt.dst;
    const int ns = S.count(), nd = D.count();
    for (int j = 0; j < ns; ++j) {
        Pose& p = S.pose[static_cast<size_t>(j)];
        std::memcpy(p.pos, src_local + j * 9, sizeof p.pos);
        std::memcpy(p.rot, src_local + j * 9 + 3, sizeof p.rot);
        std::memcpy(p.scale, src_local + j * 9 + 6, sizeof p.scale);
    }
    S.fk(S.pose, S.world, S.child);
    D.pose = D.ref;
    D.world = D.ref_world;
    D.child = D.ref_child;
    std::fill(rt.driven.begin(), rt.driven.end(), 0);

    Frame fr;
    fr.hips0 = D.hips >= 0 ? D.point(D.world, D.hips) : V3{};
    fr.sh = fr.sv = rt.scale;

    for (const cc_retarget_op& op : rt.ops) {
        const double w = std::clamp(static_cast<double>(op.weight), 0.0, 1.0);
        switch (op.type) {
            case CC_RETARGET_PELVIS: {
                if (op.p[0] > 0.f) fr.sh = op.p[0];
                if (op.p[1] > 0.f) fr.sv = op.p[1];
                if (S.hips < 0 || D.hips < 0) break;
                if (w > 0.0) {
                    const Quat Ds = qmul(qmul(S.F, rotation_of(S.world[static_cast<size_t>(S.hips)])),
                                         qconj(qmul(S.F, rotation_of(S.ref_world[static_cast<size_t>(S.hips)]))));
                    const Quat want = qmul(qconj(D.F), qmul(Ds, qmul(D.F, rotation_of(D.ref_world[static_cast<size_t>(D.hips)]))));
                    set_world_rot(D, D.hips, want, w);
                    V3 delta = rotate(S.F, S.point(S.world, S.hips) - S.point(S.ref_world, S.hips));
                    delta = V3{delta.x * fr.sh, delta.y * fr.sv, delta.z * fr.sh};
                    set_point(D, D.hips, D.point(D.ref_world, D.hips) + rotate(qconj(D.F), delta), w);
                    D.fk(D.pose, D.world, D.child);
                    rt.driven[static_cast<size_t>(D.hips)] = 1;
                }
                fr.hips0 = D.point(D.world, D.hips);
                fr.goals = false;
                break;
            }
            case CC_RETARGET_FK: {
                if (w <= 0.0) break;
                for (int j : D.rig->order) {
                    const int pi = rt.chain_of[static_cast<size_t>(j)];
                    if (pi >= 0) {
                        const Pair& p = rt.chains[static_cast<size_t>(pi)];
                        const double cw = w * std::clamp(static_cast<double>(p.set.fk_weight), 0.0, 1.0);
                        if (p.set.fk_mode != CC_FK_OFF && cw > 0.0) {
                            const int k = rt.index_in[static_cast<size_t>(j)];
                            const int i = p.map_i[static_cast<size_t>(k)];
                            const double f = p.map_f[static_cast<size_t>(k)];
                            const int s0 = p.s.joints[static_cast<size_t>(i)];
                            const int s1 = p.s.joints[std::min(static_cast<size_t>(i) + 1, p.s.joints.size() - 1)];
                            if (p.set.fk_space == CC_FK_LOCAL) {
                                auto delta = [&](int sj) {
                                    const Quat pr = qmul(S.F, S.parent_rot(S.ref_child, sj));
                                    const Quat dl = qmul(cenda::rig::from_euler_deg(S.pose[static_cast<size_t>(sj)].rot),
                                                         qconj(cenda::rig::from_euler_deg(S.ref[static_cast<size_t>(sj)].rot)));
                                    return qmul(qmul(pr, dl), qconj(pr));
                                };
                                const Quat dlt = slerp_exact(delta(s0), delta(s1), f);
                                const Quat pr_d = qmul(D.F, D.parent_rot(D.ref_child, j));
                                const Quat local = qmul(qmul(qmul(qconj(pr_d), qmul(dlt, p.align[static_cast<size_t>(k)])), pr_d),
                                                        cenda::rig::from_euler_deg(D.ref[static_cast<size_t>(j)].rot));
                                const Quat world = qmul(D.parent_rot(D.child, j), local);
                                set_world_rot(D, j, world, cw);
                            } else {
                                auto delta = [&](int sj) {
                                    return qmul(qmul(S.F, rotation_of(S.world[static_cast<size_t>(sj)])),
                                                qconj(qmul(S.F, rotation_of(S.ref_world[static_cast<size_t>(sj)]))));
                                };
                                const Quat dlt = slerp_exact(delta(s0), delta(s1), f);
                                const Quat ref_p = qmul(D.F, rotation_of(D.ref_world[static_cast<size_t>(j)]));
                                const Quat world = qmul(qconj(D.F), qmul(dlt, qmul(p.align[static_cast<size_t>(k)], ref_p)));
                                set_world_rot(D, j, world, cw);
                            }
                            rt.driven[static_cast<size_t>(j)] = 1;
                        }
                    }
                    D.fk_one(j, D.pose, D.world, D.child);
                }
                fr.goals = false;
                break;
            }
            case CC_RETARGET_STRIDE_WARP:
                fr.stride = op.p[0] > 0.f ? op.p[0] : 1.0;
                fr.goals = false;
                break;
            case CC_RETARGET_ROOT_HEIGHT: {
                if (w <= 0.0 || D.hips < 0 || rt.goal_pairs.empty()) break;
                if (!fr.goals) compute_goals(rt, fr);
                double drop = 0.0;
                for (size_t g = 0; g < rt.goal_pairs.size(); ++g) {
                    const Pair& p = rt.chains[static_cast<size_t>(rt.goal_pairs[g])];
                    const int up = p.d.joints.front();
                    if (!p.ground || !D.descends(up, D.hips)) continue;
                    const V3 a = D.point(D.world, up);
                    const V3 m = D.point(D.world, p.d.joints[p.d.joints.size() - 2]);
                    const V3 e = D.point(D.world, p.d.joints.back());
                    const double full = length(m - a) + length(e - m);
                    const V3 to = rt.goals[g] - a;
                    if (length(to) <= full) continue;
                    const double reach = full * (1.0 - 1e-9);
                    const double h2 = to.x * to.x + to.z * to.z;
                    drop = std::min(drop, h2 >= reach * reach ? to.y : to.y + std::sqrt(reach * reach - h2));
                }
                const double most = (op.p[0] > 0.f ? op.p[0] : 0.5) * D.hip_height;
                drop = std::max(drop, -most);
                if (drop < 0.0) {
                    set_point(D, D.hips, D.point(D.world, D.hips) + V3{0.0, drop, 0.0}, w);
                    D.fk(D.pose, D.world, D.child);
                    rt.driven[static_cast<size_t>(D.hips)] = 1;
                    fr.goals = false;  // chain-space goals follow the lowered chain starts
                }
                break;
            }
            case CC_RETARGET_IK: {
                if (w <= 0.0 || rt.ik == nullptr) break;
                if (!fr.goals) compute_goals(rt, fr);
                for (size_t g = 0; g < rt.goal_pairs.size(); ++g) {
                    const Pair& p = rt.chains[static_cast<size_t>(rt.goal_pairs[g])];
                    cr_target t{};
                    t.kind = CR_TARGET_MODEL;
                    t.pos[0] = static_cast<float>(rt.goals[g].x);
                    t.pos[1] = static_cast<float>(rt.goals[g].y);
                    t.pos[2] = static_cast<float>(rt.goals[g].z);
                    const Quat er = rotation_of(D.world[static_cast<size_t>(p.d.joints.back())]);
                    t.rot[0] = static_cast<float>(er.x);
                    t.rot[1] = static_cast<float>(er.y);
                    t.rot[2] = static_cast<float>(er.z);
                    t.rot[3] = static_cast<float>(er.w);
                    cr_modstack_set_target(rt.ik, static_cast<int32_t>(2 * g), &t);
                    cr_target pole{};
                    if (length(rt.bends[g]) > 0.5) {
                        const V3 m = D.point(D.world, p.d.joints[p.d.joints.size() - 2]);
                        const V3 at = m + rt.bends[g] * std::max(0.1, length(m - D.point(D.world, p.d.joints.front())));
                        pole.kind = CR_TARGET_MODEL;
                        pole.pos[0] = static_cast<float>(at.x);
                        pole.pos[1] = static_cast<float>(at.y);
                        pole.pos[2] = static_cast<float>(at.z);
                    }
                    cr_modstack_set_target(rt.ik, static_cast<int32_t>(2 * g + 1), &pole);
                    // Already there (FK alone reaches the goal: the same skeleton, a uniformly scaled one): no solve,
                    // so retargeting a character onto itself is exact.
                    const V3 end = D.point(D.world, p.d.joints.back());
                    const double span = std::max(1e-3, length(end - D.point(D.world, p.d.joints.front())));
                    const bool there = length(end - rt.goals[g]) <= 1e-6 * span;
                    cr_modstack_set_weight(rt.ik, static_cast<int32_t>(g),
                                           there ? 0.f : static_cast<float>(w * std::clamp(static_cast<double>(p.set.ik_weight), 0.0, 1.0)));
                }
                for (int j = 0; j < nd; ++j) {
                    const Pose& p = D.pose[static_cast<size_t>(j)];
                    std::memcpy(rt.scratch.data() + j * 9, p.pos, sizeof p.pos);
                    std::memcpy(rt.scratch.data() + j * 9 + 3, p.rot, sizeof p.rot);
                    std::memcpy(rt.scratch.data() + j * 9 + 6, p.scale, sizeof p.scale);
                }
                cr_poses_set(rt.poses, 0, rt.scratch.data(), nullptr);
                cr_modstack_set_frame(rt.ik, nullptr);
                if (cr_modstack_apply(rt.ik, rt.poses, 0, 0.f) != CR_OK) {
                    return ch::error(CC_ERR_ARG, std::string("cc_retarget_pose: ") + cr_last_error());
                }
                cr_poses_get(rt.poses, 0, rt.scratch.data(), nullptr);
                for (int j = 0; j < nd; ++j) {
                    Pose& p = D.pose[static_cast<size_t>(j)];
                    std::memcpy(p.pos, rt.scratch.data() + j * 9, sizeof p.pos);
                    std::memcpy(p.rot, rt.scratch.data() + j * 9 + 3, sizeof p.rot);
                }
                for (int pi : rt.goal_pairs) {
                    for (int j : rt.chains[static_cast<size_t>(pi)].d.joints) rt.driven[static_cast<size_t>(j)] = 1;
                }
                D.fk(D.pose, D.world, D.child);
                break;
            }
            default:
                break;
        }
    }

    for (int j = 0; j < nd; ++j) {
        const Pose& p = D.pose[static_cast<size_t>(j)];
        std::memcpy(out_local + j * 9, p.pos, sizeof p.pos);
        std::memcpy(out_local + j * 9 + 3, p.rot, sizeof p.rot);
        std::memcpy(out_local + j * 9 + 6, p.scale, sizeof p.scale);
    }
    if (out_driven != nullptr) std::memcpy(out_driven, rt.driven.data(), rt.driven.size());
    return CC_OK;
}

// A clip's twin on a rig (role tracks bound through the character); the caller destroys both.
cr_clip* rig_clip_of(const cm_clip* clip, const cc_char* chr, cm_clip** bound) {
    *bound = cm_clip_bind_roles(clip, chr, nullptr);
    if (*bound == nullptr) return nullptr;
    cm_header h{};
    cm_clip_header_get(*bound, &h);
    std::vector<cr_track_desc> tracks;
    std::vector<cr_key> keys;
    std::vector<std::string> ids, names;
    for (int32_t t = 0, tc = cm_clip_track_count(*bound); t < tc; ++t) {
        const char* pid = nullptr;
        const char* pname = nullptr;
        const char* role = nullptr;
        int32_t bone = 0, kc = 0;
        cm_clip_track_get(*bound, t, &pid, &pname, &role, &bone, &kc);
        if (role != nullptr && role[0] != '\0') continue;
        const size_t first = keys.size();
        keys.resize(first + static_cast<size_t>(kc));
        cm_clip_track_keys(*bound, t, keys.data() + first, kc);
        tracks.push_back(cr_track_desc{static_cast<int32_t>(first), kc});
        ids.emplace_back(pid != nullptr ? pid : "");
        names.emplace_back(pname != nullptr ? pname : "");
    }
    std::vector<const char*> idp, namep;
    for (size_t i = 0; i < ids.size(); ++i) {
        idp.push_back(ids[i].c_str());
        namep.push_back(names[i].c_str());
    }
    cr_clip_desc desc{h.duration, h.loop, CR_LAYER_BASE, 0, 0.f, 0.f};
    return cr_clip_create(&desc, tracks.data(), static_cast<int32_t>(tracks.size()), keys.data(),
                          static_cast<int32_t>(keys.size()), idp.data(), namep.data(), nullptr, 0);
}

double angle_gap(double a, double b) {
    double d = std::fmod(std::fmod(b - a, 360.0) + 540.0, 360.0) - 180.0;
    return std::fabs(d);
}

// The Euler triple (or its dual: x+180, 180-y, z+180) nearer the previous key.
void continuous_euler(float rot[3], const float* prev) {
    if (prev == nullptr) return;
    const float dual[3] = {rot[0] + 180.f, 180.f - rot[1], rot[2] + 180.f};
    auto wrap = [](float a) {
        while (a > 180.f) a -= 360.f;
        while (a <= -180.f) a += 360.f;
        return a;
    };
    const double d0 = angle_gap(prev[0], rot[0]) + angle_gap(prev[1], rot[1]) + angle_gap(prev[2], rot[2]);
    const double d1 = angle_gap(prev[0], dual[0]) + angle_gap(prev[1], dual[1]) + angle_gap(prev[2], dual[2]);
    if (d1 + 1e-3 < d0) {
        for (int c = 0; c < 3; ++c) rot[c] = wrap(dual[c]);
    }
}

}  // namespace

extern "C" {

cc_retargeter* cc_retarget_create(const cr_rig* source_rig, const cc_char* source, const cr_rig* target_rig,
                                  const cc_char* target) {
    if (source_rig == nullptr || source == nullptr || target_rig == nullptr || target == nullptr) {
        ch::error(CC_ERR_ARG, "cc_retarget_create: null rig or character");
        return nullptr;
    }
    CC_GUARD_BEGIN
    auto rt = std::make_unique<cc_retargeter>();
    init_side(rt->src, source_rig, source);
    init_side(rt->dst, target_rig, target);
    const auto& sg = source->def.ik_goals;
    const auto& dg = target->def.ik_goals;
    auto has_goal = [](const std::vector<oc::IkGoal>& goals, const std::string& name) {
        return !name.empty() && std::any_of(goals.begin(), goals.end(), [&](const oc::IkGoal& g) { return g.name == name; });
    };
    for (const oc::Chain& dc : target->def.chains) {
        auto it = std::find_if(source->def.chains.begin(), source->def.chains.end(),
                               [&](const oc::Chain& c) { return c.name == dc.name; });
        if (it == source->def.chains.end()) continue;
        Pair p;
        p.name = dc.name;
        if (!chain_side(rt->src, *it, p.s) || !chain_side(rt->dst, dc, p.d) || p.s.joints.empty() || p.d.joints.empty()) continue;
        compute_mapping(p);
        if (has_goal(dg, dc.goal) && has_goal(sg, it->goal) && dc.goal == it->goal && p.d.joints.size() >= 3) p.goal = 0;
        // The bend direction from the target's profile (built-in profiles; a custom one: cc_retarget_set_bend).
        try {
            for (const auto& pc : cenda::omformat::omprofile::builtin(target->def.profile).chains) {
                if (pc.name == dc.name && pc.has_bend) {
                    p.has_bend = true;
                    p.bend = V3{pc.bend[0], pc.bend[1], pc.bend[2]};
                }
            }
        } catch (const std::exception&) {
            // not a built-in profile
        }
        rt->chains.push_back(std::move(p));
    }
    if (rt->chains.empty()) {
        ch::error(CC_ERR_ARG, "cc_retarget_create: the characters share no retarget chain (auto-map writes them)");
        return nullptr;
    }
    const size_t nd = target_rig->joints.size();
    rt->chain_of.assign(nd, -1);
    rt->index_in.assign(nd, -1);
    for (size_t pi = 0; pi < rt->chains.size(); ++pi) {
        const Pair& p = rt->chains[pi];
        for (size_t k = 0; k < p.d.joints.size(); ++k) {
            const int j = p.d.joints[k];
            if (rt->chain_of[static_cast<size_t>(j)] >= 0 || j == rt->dst.hips) continue;  // first chain wins
            rt->chain_of[static_cast<size_t>(j)] = static_cast<int>(pi);
            rt->index_in[static_cast<size_t>(j)] = static_cast<int>(k);
        }
    }
    // IK goals: one two-bone modifier each (targets 2g = goal, 2g + 1 = pole).
    std::vector<cr_modifier_desc> descs;
    std::vector<std::array<int32_t, 3>> desc_joints;  // rig ABI 3: the desc points at them; create copies
    std::vector<int> src_goal_joints, dst_goal_joints;
    for (size_t pi = 0; pi < rt->chains.size(); ++pi) {
        Pair& p = rt->chains[pi];
        if (p.goal < 0) continue;
        cr_modifier_desc d{};
        d.type = CR_MOD_TWO_BONE;
        d.joint_count = 3;
        desc_joints.push_back({p.d.joints.front(), p.d.joints[p.d.joints.size() - 2], p.d.joints.back()});
        d.target = static_cast<int32_t>(2 * descs.size());
        d.pole = d.target + 1;
        d.flags = CR_MOD_MATCH_ROTATION;
        d.weight = 1.f;
        p.goal = static_cast<int>(descs.size());
        descs.push_back(d);
        rt->goal_pairs.push_back(static_cast<int>(pi));
        src_goal_joints.push_back(p.s.joints.back());
        dst_goal_joints.push_back(p.d.joints.back());
    }
    for (size_t k = 0; k < descs.size(); ++k) descs[k].joints = desc_joints[k].data();
    if (!descs.empty()) {
        rt->ik = cr_modstack_create(target_rig, descs.data(), static_cast<int32_t>(descs.size()),
                                    static_cast<int32_t>(2 * descs.size()));
        if (rt->ik == nullptr) {
            ch::error(CC_ERR_ARG, std::string("cc_retarget_create: ") + cr_last_error());
            return nullptr;
        }
    }
    rt->poses = cr_poses_create(target_rig, 1);
    rt->src.hip_height = hip_height(rt->src, src_goal_joints);
    rt->dst.hip_height = hip_height(rt->dst, dst_goal_joints);
    rt->scale = rt->src.hip_height > EPS && rt->dst.hip_height > EPS ? rt->dst.hip_height / rt->src.hip_height : 1.0;
    for (Pair& p : rt->chains) resolve_goal(*rt, p);
    rt->driven.assign(nd, 0);
    rt->scratch.assign(nd * 9, 0.f);
    default_ops(*rt);
    ch::clear_error();
    return rt.release();
    CC_GUARD_END(nullptr)
}

void cc_retarget_destroy(cc_retargeter* rt) { delete rt; }

int32_t cc_retarget_set_ops(cc_retargeter* rt, const cc_retarget_op* ops, int32_t count) {
    if (rt == nullptr || count < 0 || count > MAX_OPS || (count > 0 && ops == nullptr)) {
        return ch::error(CC_ERR_ARG, "cc_retarget_set_ops: bad arguments");
    }
    for (int32_t i = 0; i < count; ++i) {
        if (ops[i].type < CC_RETARGET_PELVIS || ops[i].type > CC_RETARGET_IK || !std::isfinite(ops[i].weight)) {
            return ch::error(CC_ERR_ARG, "cc_retarget_set_ops: op " + std::to_string(i) + " is not valid");
        }
        for (float v : ops[i].p) {
            if (!std::isfinite(v)) return ch::error(CC_ERR_ARG, "cc_retarget_set_ops: non-finite parameter");
        }
    }
    rt->ops.assign(ops, ops + count);
    return CC_OK;
}

int32_t cc_retarget_op_count(const cc_retargeter* rt) { return rt == nullptr ? 0 : static_cast<int32_t>(rt->ops.size()); }

int32_t cc_retarget_op_get(const cc_retargeter* rt, int32_t index, cc_retarget_op* out) {
    if (rt == nullptr || out == nullptr || index < 0 || static_cast<size_t>(index) >= rt->ops.size()) {
        return ch::error(CC_ERR_RANGE, "cc_retarget_op_get: index out of range");
    }
    *out = rt->ops[static_cast<size_t>(index)];
    return CC_OK;
}

int32_t cc_retarget_chain_count(const cc_retargeter* rt) { return rt == nullptr ? 0 : static_cast<int32_t>(rt->chains.size()); }

int32_t cc_retarget_chain_get(const cc_retargeter* rt, int32_t index, const char** out_name, int32_t* out_source_joints,
                              int32_t* out_target_joints, int32_t* out_goal) {
    if (rt == nullptr || index < 0 || static_cast<size_t>(index) >= rt->chains.size()) {
        return ch::error(CC_ERR_RANGE, "cc_retarget_chain_get: index out of range");
    }
    const Pair& p = rt->chains[static_cast<size_t>(index)];
    ch::set_out(out_name, p.name.c_str());
    ch::set_out(out_source_joints, static_cast<int32_t>(p.s.joints.size()));
    ch::set_out(out_target_joints, static_cast<int32_t>(p.d.joints.size()));
    ch::set_out(out_goal, p.goal >= 0 ? int32_t{1} : int32_t{0});
    return CC_OK;
}

int32_t cc_retarget_set_chain(cc_retargeter* rt, const char* chain, const cc_retarget_chain* settings) {
    if (rt == nullptr || chain == nullptr || settings == nullptr) return ch::error(CC_ERR_ARG, "cc_retarget_set_chain: null argument");
    if (settings->fk_mode < CC_FK_INTERPOLATED || settings->fk_mode > CC_FK_OFF || settings->fk_space < CC_FK_GLOBAL
        || settings->fk_space > CC_FK_LOCAL || !std::isfinite(settings->fk_weight) || !std::isfinite(settings->ik_weight)
        || settings->goal_space < CC_GOAL_AUTO || settings->goal_space > CC_GOAL_CHAIN) {
        return ch::error(CC_ERR_ARG, "cc_retarget_set_chain: bad settings");
    }
    for (Pair& p : rt->chains) {
        if (p.name != chain) continue;
        p.set = *settings;
        compute_mapping(p);
        resolve_goal(*rt, p);
        return CC_OK;
    }
    return ch::error(CC_ERR_RANGE, std::string("cc_retarget_set_chain: no paired chain ") + chain);
}

int32_t cc_retarget_set_bend(cc_retargeter* rt, const char* chain, const float* direction) {
    if (rt == nullptr || chain == nullptr) return ch::error(CC_ERR_ARG, "cc_retarget_set_bend: null argument");
    V3 d;
    if (direction != nullptr) {
        d = V3{direction[0], direction[1], direction[2]};
        if (!(length(d) > EPS) || !std::isfinite(length(d))) return ch::error(CC_ERR_ARG, "cc_retarget_set_bend: zero direction");
        d = normalized(d);
    }
    for (Pair& p : rt->chains) {
        if (p.name != chain) continue;
        p.has_bend = direction != nullptr;
        p.bend = d;
        return CC_OK;
    }
    return ch::error(CC_ERR_RANGE, std::string("cc_retarget_set_bend: no paired chain ") + chain);
}

int32_t cc_retarget_get_chain(const cc_retargeter* rt, const char* chain, cc_retarget_chain* out) {
    if (rt == nullptr || chain == nullptr || out == nullptr) return ch::error(CC_ERR_ARG, "cc_retarget_get_chain: null argument");
    for (const Pair& p : rt->chains) {
        if (p.name == chain) {
            *out = p.set;
            return CC_OK;
        }
    }
    return ch::error(CC_ERR_RANGE, std::string("cc_retarget_get_chain: no paired chain ") + chain);
}

const cr_rig* cc_retarget_source_rig(const cc_retargeter* rt) { return rt == nullptr ? nullptr : rt->src.rig; }
const cr_rig* cc_retarget_target_rig(const cc_retargeter* rt) { return rt == nullptr ? nullptr : rt->dst.rig; }
const cc_char* cc_retarget_source_char(const cc_retargeter* rt) { return rt == nullptr ? nullptr : rt->src.chr; }

float cc_retarget_scale(const cc_retargeter* rt) { return rt == nullptr ? 0.f : static_cast<float>(rt->scale); }

int32_t cc_retarget_map_motion(const cc_retargeter* rt, cr_root_motion* inout) {
    if (rt == nullptr || inout == nullptr) return ch::error(CC_ERR_ARG, "cc_retarget_map_motion: null argument");
    double sh = rt->scale, sv = rt->scale;
    for (const cc_retarget_op& op : rt->ops) {
        if (op.type != CC_RETARGET_PELVIS) continue;
        if (op.p[0] > 0.f) sh = op.p[0];
        if (op.p[1] > 0.f) sv = op.p[1];
    }
    V3 t = rotate(rt->src.F, V3{inout->translation[0], inout->translation[1], inout->translation[2]});
    t = rotate(qconj(rt->dst.F), V3{t.x * sh, t.y * sv, t.z * sh});
    inout->translation[0] = static_cast<float>(t.x);
    inout->translation[1] = static_cast<float>(t.y);
    inout->translation[2] = static_cast<float>(t.z);
    return CC_OK;
}

int32_t cc_retarget_pose(cc_retargeter* rt, const float* source_local, float* out_target_local, uint8_t* out_driven) {
    if (rt == nullptr || source_local == nullptr || out_target_local == nullptr) {
        return ch::error(CC_ERR_ARG, "cc_retarget_pose: null argument");
    }
    CC_GUARD_BEGIN
    return run(*rt, source_local, out_target_local, out_driven);
    CC_GUARD_END(CC_ERR_ARG)
}

cm_clip* cc_retarget_bake(cc_retargeter* rt, const cm_clip* source_clip, float fps, int32_t* out_result) {
    ch::set_out(out_result, int32_t{CC_ERR_ARG});
    if (rt == nullptr || source_clip == nullptr || !(fps > 0.f) || fps > 1000.f) {
        ch::error(CC_ERR_ARG, "cc_retarget_bake: bad arguments");
        return nullptr;
    }
    CC_GUARD_BEGIN
    int32_t additive = CM_ADDITIVE_NONE, mesh = 0;
    float atime = 0.f;
    const char* aref = nullptr;
    cm_clip_additive_get(source_clip, &additive, &atime, &aref, &mesh);
    if (additive != CM_ADDITIVE_NONE) {
        ch::error(CC_ERR_ARG, "cc_retarget_bake: additive clips are not retargeted");
        return nullptr;
    }
    cm_clip* bound = nullptr;
    cr_clip* rclip = rig_clip_of(source_clip, rt->src.chr, &bound);
    if (rclip == nullptr) {
        if (bound != nullptr) cm_clip_destroy(bound);
        ch::error(CC_ERR_ARG, std::string("cc_retarget_bake: binding the clip: ") + cr_last_error());
        return nullptr;
    }
    cm_header h{};
    cm_clip_header_get(source_clip, &h);
    const int ns = rt->src.count(), nd = rt->dst.count();
    const int frames = std::max(1, static_cast<int>(std::ceil(static_cast<double>(h.duration) * fps - 1e-4)));
    std::vector<float> src_local(static_cast<size_t>(ns) * 9), dst_local(static_cast<size_t>(nd) * 9);
    std::vector<uint8_t> driven(static_cast<size_t>(nd)), ever(static_cast<size_t>(nd), 0);
    std::vector<std::vector<cr_key>> keys(static_cast<size_t>(nd));
    cenda::rig::PoseBuf buf;
    cenda::rig::Binding scratch;
    int32_t rc = CC_OK;
    for (int k = 0; k <= frames && rc == CC_OK; ++k) {
        const float t = k == frames ? h.duration : static_cast<float>(k) / fps;
        // A looping clip's last key is its end, not the wrapped start.
        const float elapsed = (h.loop != 0 && k == frames && h.duration > 0.f) ? std::nextafter(h.duration, 0.f) : t;
        cenda::rig::pose_sample(*rt->src.rig, *rclip, elapsed, false, buf, scratch);
        for (int j = 0; j < ns; ++j) {
            const Pose& p = buf.v[static_cast<size_t>(j)];
            std::memcpy(src_local.data() + j * 9, p.pos, sizeof p.pos);
            std::memcpy(src_local.data() + j * 9 + 3, p.rot, sizeof p.rot);
            std::memcpy(src_local.data() + j * 9 + 6, p.scale, sizeof p.scale);
        }
        rc = run(*rt, src_local.data(), dst_local.data(), driven.data());
        for (int j = 0; j < nd && rc == CC_OK; ++j) {
            if (!driven[static_cast<size_t>(j)]) continue;
            ever[static_cast<size_t>(j)] = 1;
            cr_key key{};
            key.time = t;
            std::memcpy(key.pos, dst_local.data() + j * 9, sizeof key.pos);
            std::memcpy(key.rot_deg, dst_local.data() + j * 9 + 3, sizeof key.rot_deg);
            std::memcpy(key.scale, dst_local.data() + j * 9 + 6, sizeof key.scale);
            key.easing = CR_EASE_LINEAR;
            auto& ks = keys[static_cast<size_t>(j)];
            continuous_euler(key.rot_deg, ks.empty() ? nullptr : ks.back().rot_deg);
            ks.push_back(key);
        }
    }
    cr_clip_destroy(rclip);
    if (rc != CC_OK) {
        cm_clip_destroy(bound);
        ch::set_out(out_result, rc);
        return nullptr;
    }
    cm_clip* out = cm_clip_create();
    cm_header oh = h;
    oh.fps = fps;
    cm_clip_header_set(out, &oh);
    cm_clip_set_string(out, CM_FIELD_NAME, cm_clip_get_string(source_clip, CM_FIELD_NAME));
    cm_clip_set_string(out, CM_FIELD_LAYER_TYPE, cm_clip_get_string(source_clip, CM_FIELD_LAYER_TYPE));
    cm_clip_set_string(out, CM_FIELD_MODEL_REF, nullptr);
    for (int j = 0; j < nd; ++j) {
        if (!ever[static_cast<size_t>(j)]) continue;
        const Joint& jt = rt->dst.rig->joints[static_cast<size_t>(j)];
        const auto& ks = keys[static_cast<size_t>(j)];
        cm_clip_track_set(out, jt.id.c_str(), jt.name.c_str(), nullptr, jt.kind == CR_JOINT_BONE ? 1 : 0, ks.data(),
                          static_cast<int32_t>(ks.size()));
    }
    // Timing data carries over unchanged.
    std::vector<cm_curve_key> ck;
    for (int32_t c = 0, n = cm_clip_curve_count(source_clip); c < n; ++c) {
        const char* name = nullptr;
        int32_t kc = 0;
        cm_clip_curve_get(source_clip, c, &name, &kc);
        ck.resize(static_cast<size_t>(kc));
        cm_clip_curve_keys(source_clip, c, ck.data(), kc);
        cm_clip_curve_set(out, name, ck.data(), kc);
    }
    for (int32_t i = 0, n = cm_clip_notify_count(source_clip); i < n; ++i) {
        const char* name = nullptr;
        const char* payload = nullptr;
        float t = 0.f, dur = 0.f;
        cm_clip_notify_get(source_clip, i, &name, &t, &dur, &payload);
        cm_clip_notify_add(out, name, t, dur, payload);
    }
    for (int32_t i = 0, n = cm_clip_marker_count(source_clip); i < n; ++i) {
        const char* name = nullptr;
        float t = 0.f;
        cm_clip_marker_get(source_clip, i, &name, &t);
        cm_clip_marker_add(out, name, t);
    }
    // Root motion on the source hips moves to the target hips.
    const char* rm = nullptr;
    uint32_t axes = 0;
    cm_clip_root_motion_get(bound, &rm, &axes);
    if (rm != nullptr && rm[0] != '\0' && rt->src.hips >= 0 && rt->dst.hips >= 0
        && ch::rig_index(rt->src.rig, rm) == rt->src.hips) {
        cm_clip_root_motion_set(out, rt->dst.rig->joints[static_cast<size_t>(rt->dst.hips)].id.c_str(), axes);
    }
    cm_clip_destroy(bound);
    ch::set_out(out_result, int32_t{CC_OK});
    ch::clear_error();
    return out;
    CC_GUARD_END(nullptr)
}

}  // extern "C"
