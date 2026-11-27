// Cenda rig pose modifiers (OpenMason #158): two-bone IK with pole and soft reach, FABRIK / CCD chains, multi-aim
// with per-joint weights and limits, Maya-style constraints, spring bones with colliders, full-body effectors; and
// locomotion adaptation (#159): orientation warping, stride warping, foot placement over the host's batched ground
// query with world-space foot locks. Contract in cenda/rig.h ("Pose modifiers", "Locomotion adaptation").
//
// Everything works on the pose's forward kinematics in model space (double). A joint is turned by a WORLD rotation
// about its pivot: q_local' = q_parent^-1 · delta · q_parent · q_local (q_parent = the rotation of the frame its
// parent hands down), written back as local Euler degrees; moved by a world offset through the inverse of that
// frame. After an edit only the joint's subtree is re-posed.

#include "rig_internal.hpp"
#include "rig_quat.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <new>
#include <string>

using namespace cenda::rig;

namespace {

constexpr double EPS = 1e-9;
constexpr int MAX_SPRING_STEPS = 8;

struct Vec {
    double x = 0.0, y = 0.0, z = 0.0;
};

Vec operator+(const Vec& a, const Vec& b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
Vec operator-(const Vec& a, const Vec& b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
Vec operator*(const Vec& a, double s) { return {a.x * s, a.y * s, a.z * s}; }
double dot(const Vec& a, const Vec& b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
Vec cross(const Vec& a, const Vec& b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
double length(const Vec& a) { return std::sqrt(dot(a, a)); }

Vec normalized(const Vec& a) {
    const double l = length(a);
    return l > EPS ? a * (1.0 / l) : Vec{};
}

Vec vec_of(const float* f) { return {f[0], f[1], f[2]}; }

Vec transform_point(const Mat4& m, const Vec& p) {
    const double in[3] = {p.x, p.y, p.z};
    double out[3];
    m.transform_point(in, out);
    return {out[0], out[1], out[2]};
}

Vec transform_dir(const Mat4& m, const Vec& d) {
    return {m.at(0, 0) * d.x + m.at(0, 1) * d.y + m.at(0, 2) * d.z, m.at(1, 0) * d.x + m.at(1, 1) * d.y + m.at(1, 2) * d.z,
            m.at(2, 0) * d.x + m.at(2, 1) * d.y + m.at(2, 2) * d.z};
}

Vec rotate(const Quat& q, const Vec& v) {
    const Quat p{0.0, v.x, v.y, v.z};
    const Quat r = qmul(qmul(q, p), qconj(q));
    return {r.x, r.y, r.z};
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

// The shortest rotation taking unit a to unit b.
Quat from_to(const Vec& a, const Vec& b) {
    const double d = dot(a, b);
    if (d >= 1.0 - 1e-15) return Quat{};
    if (d <= -1.0 + 1e-12) {
        Vec axis = cross(a, Vec{1, 0, 0});
        if (length(axis) < 1e-6) axis = cross(a, Vec{0, 1, 0});
        axis = normalized(axis);
        return Quat{0.0, axis.x, axis.y, axis.z};
    }
    const Vec c = cross(a, b);
    return qnormalize(Quat{1.0 + d, c.x, c.y, c.z});
}

Quat scaled(const Quat& q, double w) { return w >= 1.0 ? q : qslerp(Quat{}, q, w); }

double wrap180(double a) {
    a = std::fmod(a, 360.0);
    if (a > 180.0) a -= 360.0;
    if (a < -180.0) a += 360.0;
    return a;
}

struct Target {
    int kind = CR_TARGET_NONE;
    int joint = -1;
    Vec pos;
    Quat rot;
};

struct Limit {
    bool has = false;
    int32_t order = CR_LIMIT_XYZ;  // the Euler order the box is read in (CR_LIMIT_*)
    float ref[3]{}, mn[3]{}, mx[3]{};
};

struct Collider {
    int joint = -1;
    Vec a, b;
    double radius = 0.0;
};

// One leg of a foot-placement modifier (#159).
struct LegRt {
    bool init = false;
    double ground = 0.0;  // eased ground offset along up
    bool locked = false;
    Vec lock_w;           // the lock point, world
    double lock_height = 0.0;  // the animated ankle's model y when it locked
    double alpha = 0.0;   // last frame's pull toward the lock
    double fade = 0.0;    // the pull while releasing
};

struct ModRt {
    cr_modifier_desc d{};
    std::vector<int> chain;  // FABRIK / CCD / SPRING: start .. end
    std::vector<Vec> cur, prev;
    bool spring_init = false;
    double acc = 0.0;
    // Locomotion (#159).
    float v[CR_VALUE_INPUT_COUNT]{};
    LegRt legs[CR_MAX_LEGS];
    bool has_toe[CR_MAX_LEGS]{};
    bool with_pelvis[CR_MAX_LEGS]{};  // the thigh moves with the pelvis
    int probe_count = 0;
    double angle = 0.0;
    bool angle_init = false;
    double pelvis = 0.0;
    bool pelvis_init = false;
    double out_pelvis = 0.0;

    void reset_locomotion() {
        for (LegRt& l : legs) l = LegRt{};
        angle = pelvis = out_pelvis = 0.0;
        angle_init = pelvis_init = false;
    }
};

// Parent-to-child path from start to end; empty when start is not an ancestor of (or equal to) end.
std::vector<int> path(const cr_rig& rig, int start, int end) {
    std::vector<int> out;
    for (int j = end; j >= 0; j = rig.joints[static_cast<size_t>(j)].parent) {
        out.push_back(j);
        if (j == start) {
            std::reverse(out.begin(), out.end());
            return out;
        }
        if (out.size() > rig.joints.size()) break;
    }
    return {};
}

}  // namespace

struct cr_modstack {
    const cr_rig* rig = nullptr;
    std::vector<ModRt> mods;
    std::vector<Target> targets;
    std::vector<Limit> limits;
    std::vector<Collider> colliders;
    Mat4 frame = Mat4::identity();
    Mat4 frame_inv = Mat4::identity();
    // Working state of one apply.
    PoseBuf* pose = nullptr;
    std::vector<Mat4> world, child;
    std::vector<uint8_t> dirty;
    std::vector<Pose> snap;
    std::vector<uint8_t> touched;
    std::vector<Vec> scratch;
    std::vector<double> lengths;
    // Ground query (#159): buffers sized at create.
    cr_ground_fn ground = nullptr;
    void* ground_user = nullptr;
    std::vector<cr_ground_probe> probes;
    std::vector<cr_ground_hit> hits;
};

namespace {

// ---- forward kinematics on the working pose ------------------------------------------------------------------

void fk_joint(cr_modstack& s, int i) {
    const Joint& j = s.rig->joints[static_cast<size_t>(i)];
    const Mat4 parent = j.parent >= 0 ? s.child[static_cast<size_t>(j.parent)] : Mat4::identity();
    s.world[static_cast<size_t>(i)] = parent * joint_local_matrix(j, s.pose->v[static_cast<size_t>(i)]);
    s.child[static_cast<size_t>(i)] = joint_child_frame(j, s.world[static_cast<size_t>(i)]);
}

void fk_all(cr_modstack& s) {
    for (int idx : s.rig->order) fk_joint(s, idx);
}

void fk_from(cr_modstack& s, int joint) {
    std::fill(s.dirty.begin(), s.dirty.end(), 0);
    for (int idx : s.rig->order) {
        const int parent = s.rig->joints[static_cast<size_t>(idx)].parent;
        if (idx == joint || (parent >= 0 && s.dirty[static_cast<size_t>(parent)])) {
            fk_joint(s, idx);
            s.dirty[static_cast<size_t>(idx)] = 1;
        }
    }
}

Vec pivot(const cr_modstack& s, int j) {
    const Joint& jt = s.rig->joints[static_cast<size_t>(j)];
    const Vec local = jt.kind == CR_JOINT_PART ? vec_of(jt.origin) : Vec{};
    return transform_point(s.world[static_cast<size_t>(j)], local);
}

Quat world_rot(const cr_modstack& s, int j) { return rotation_of(s.world[static_cast<size_t>(j)]); }

Quat parent_rot(const cr_modstack& s, int j) {
    const int p = s.rig->joints[static_cast<size_t>(j)].parent;
    return p >= 0 ? rotation_of(s.child[static_cast<size_t>(p)]) : Quat{};
}

// How far relative Euler angles (degrees, wrapped) lie outside a limit box: the summed excess per axis.
double limit_excess(const Limit& l, const double rel[3]) {
    double e = 0.0;
    for (int c = 0; c < 3; ++c) {
        if (rel[c] < l.mn[c]) e += l.mn[c] - rel[c];
        else if (rel[c] > l.mx[c]) e += rel[c] - l.mx[c];
    }
    return e;
}

// YXZ Euler degrees of a rotation (R = Ry(y) Rx(x) Rz(z)), returned as [x, y, z].
void to_euler_yxz_deg(const Quat& q, double out[3]) {
    double r[9];
    qmatrix(qnormalize(q), r);
    const double sx = std::clamp(-r[5], -1.0, 1.0);
    double x, y, z;
    if (std::fabs(sx) < 0.9999999) {
        x = std::asin(sx);
        y = std::atan2(r[2], r[8]);
        z = std::atan2(r[3], r[4]);
    } else {  // gimbal lock: put everything in y
        x = sx > 0 ? 1.5707963267948966 : -1.5707963267948966;
        y = std::atan2(-r[6], r[0]);
        z = 0.0;
    }
    out[0] = x * RAD_TO_DEG;
    out[1] = y * RAD_TO_DEG;
    out[2] = z * RAD_TO_DEG;
}

Quat from_euler_yxz_deg(const double e[3]) {
    const Quat qx = qaxis(1, 0, 0, e[0] * DEG_TO_RAD);
    const Quat qy = qaxis(0, 1, 0, e[1] * DEG_TO_RAD);
    const Quat qz = qaxis(0, 0, 1, e[2] * DEG_TO_RAD);
    return qmul(qmul(qy, qx), qz);
}

// The YXZ box (Stonebreak #460): yaw about the parent's Y, then pitch, then roll - a head's look range. The turn
// relative to the reference rotation is read as YXZ Euler; (x, y) say where the joint's Z axis points and z is the twist
// about it. The angles name the turn twice ((x, y, z) == (180 - x, y + 180, z + 180)): each branch is clamped per axis
// and the one whose Z axis stays nearer the unclamped Z axis is kept (comparing summed excess instead let the twist an
// aim leaves pick the wrong branch: a look behind and down came out facing forward). A turn inside the box is left
// exactly as it was; a look straight behind is a tie and takes the first branch.
void apply_limit_yxz(cr_modstack& s, int j, const Limit& l) {
    float* rot = s.pose->v[static_cast<size_t>(j)].rot;
    const Quat ref = from_euler_deg(l.ref);
    const Quat rel_q = qmul(qconj(ref), from_euler_deg(rot));
    double rel[3], alt[3];
    to_euler_yxz_deg(rel_q, rel);
    for (int c = 0; c < 3; ++c) rel[c] = wrap180(rel[c]);
    if (limit_excess(l, rel) <= 0.0) return;
    alt[0] = wrap180(180.0 - rel[0]);
    alt[1] = wrap180(rel[1] + 180.0);
    alt[2] = wrap180(rel[2] + 180.0);
    double a[3], b[3];
    for (int c = 0; c < 3; ++c) {
        a[c] = std::clamp(rel[c], static_cast<double>(l.mn[c]), static_cast<double>(l.mx[c]));
        b[c] = std::clamp(alt[c], static_cast<double>(l.mn[c]), static_cast<double>(l.mx[c]));
    }
    const Quat qa = from_euler_yxz_deg(a), qb = from_euler_yxz_deg(b);
    const Vec z{0.0, 0.0, 1.0};
    const Vec want = rotate(rel_q, z);
    const Quat& keep = dot(rotate(qb, z), want) > dot(rotate(qa, z), want) + 1e-12 ? qb : qa;
    to_euler_deg(qnormalize(qmul(ref, keep)), rot);
}

void apply_limit(cr_modstack& s, int j) {
    const Limit& l = s.limits[static_cast<size_t>(j)];
    if (!l.has) return;
    if (l.order == CR_LIMIT_YXZ) {
        apply_limit_yxz(s, j, l);
        return;
    }
    float* rot = s.pose->v[static_cast<size_t>(j)].rot;
    // XYZ Euler angles name one rotation twice: (x, y, z) == (x + 180, 180 - y, z + 180). A turn past 90 degrees
    // about Y decomposes on the second branch (x and z near 180), and clamping that per axis mixed a plain yaw into
    // pitch and roll (a head looking behind it twisted sideways; Stonebreak #460). Clamp the branch nearer the box;
    // a pose inside the limits keeps its own angles.
    double rel[3], alt[3];
    for (int c = 0; c < 3; ++c) rel[c] = wrap180(static_cast<double>(rot[c]) - l.ref[c]);
    alt[0] = wrap180(static_cast<double>(rot[0]) + 180.0 - l.ref[0]);
    alt[1] = wrap180(180.0 - static_cast<double>(rot[1]) - l.ref[1]);
    alt[2] = wrap180(static_cast<double>(rot[2]) + 180.0 - l.ref[2]);
    const double e = limit_excess(l, rel);
    const double* use = (e > 0.0 && limit_excess(l, alt) < e) ? alt : rel;
    for (int c = 0; c < 3; ++c) {
        const double clamped = std::clamp(use[c], static_cast<double>(l.mn[c]), static_cast<double>(l.mx[c]));
        rot[c] = static_cast<float>(l.ref[c] + clamped);
    }
}

// Turn joint j by a world rotation about its pivot.
void rotate_world(cr_modstack& s, int j, const Quat& delta, bool limit = false) {
    Pose& p = s.pose->v[static_cast<size_t>(j)];
    const Quat qp = parent_rot(s, j);
    const Quat q = qnormalize(qmul(qmul(qmul(qconj(qp), delta), qp), from_euler_deg(p.rot)));
    to_euler_deg(q, p.rot);
    if (limit) apply_limit(s, j);
    s.pose->driven[static_cast<size_t>(j)] = 1;
    s.touched[static_cast<size_t>(j)] = 1;
    fk_from(s, j);
}

// Move joint j's pivot by a model-space offset.
void move_world(cr_modstack& s, int j, const Vec& delta) {
    Pose& p = s.pose->v[static_cast<size_t>(j)];
    const int parent = s.rig->joints[static_cast<size_t>(j)].parent;
    const Vec local = parent >= 0 ? transform_dir(s.child[static_cast<size_t>(parent)].affine_inverse(), delta) : delta;
    p.pos[0] = static_cast<float>(p.pos[0] + local.x);
    p.pos[1] = static_cast<float>(p.pos[1] + local.y);
    p.pos[2] = static_cast<float>(p.pos[2] + local.z);
    s.pose->driven[static_cast<size_t>(j)] = 1;
    s.touched[static_cast<size_t>(j)] = 1;
    fk_from(s, j);
}

// A target slot in model space; false when it has no value.
bool target_frame(const cr_modstack& s, int t, Vec& pos, Quat& rot) {
    if (t < 0 || static_cast<size_t>(t) >= s.targets.size()) return false;
    const Target& tg = s.targets[static_cast<size_t>(t)];
    switch (tg.kind) {
        case CR_TARGET_MODEL:
            pos = tg.pos;
            rot = tg.rot;
            return true;
        case CR_TARGET_WORLD:
            pos = transform_point(s.frame_inv, tg.pos);
            rot = qnormalize(qmul(rotation_of(s.frame_inv), tg.rot));
            return true;
        case CR_TARGET_JOINT: {
            const Quat jr = world_rot(s, tg.joint);
            pos = pivot(s, tg.joint) + rotate(jr, tg.pos);
            rot = qnormalize(qmul(jr, tg.rot));
            return true;
        }
        default:
            return false;
    }
}

// ---- solvers --------------------------------------------------------------------------------------------------

// A limb's end point: the end joint's pivot, or (end -1) the mid joint's tail: the origin of the frame it hands
// down (a bone's endpoint).
Vec effector(const cr_modstack& s, int mid, int end) {
    return end >= 0 ? pivot(s, end) : transform_point(s.child[static_cast<size_t>(mid)], Vec{});
}

void two_bone(cr_modstack& s, int up, int mid, int end, Vec t, const Vec* pole, double soft, const Quat* end_rot) {
    const Vec a = pivot(s, up), b = pivot(s, mid), c = effector(s, mid, end);
    const double lab = length(b - a), lcb = length(c - b);
    if (lab < EPS || lcb < EPS) return;
    const Vec at = t - a;
    double d = length(at);
    if (d < EPS) return;
    const Vec u = at * (1.0 / d);
    const double full = lab + lcb;
    if (soft > 0.0) {
        const double s0 = full * (1.0 - soft);
        if (d > s0) {
            const double ds = full * soft;
            d = s0 + ds * (1.0 - std::exp(-(d - s0) / ds));
        }
    }
    d = std::clamp(d, std::fabs(lab - lcb) + 1e-9, full * (1.0 - 1e-12));
    // The bend plane: through a, the target and the pole (else the current elbow).
    const Vec ref = pole != nullptr ? *pole - a : b - a;
    Vec n = cross(u, ref);
    if (length(n) < 1e-9) n = cross(u, c - a);
    if (length(n) < 1e-9) n = cross(u, std::fabs(u.x) < 0.9 ? Vec{1, 0, 0} : Vec{0, 1, 0});
    const Vec v = normalized(cross(normalized(n), u));  // in the plane, toward the pole
    const double x = (lab * lab - lcb * lcb + d * d) / (2.0 * d);
    const double h = std::sqrt(std::max(0.0, lab * lab - x * x));
    const Vec b1 = a + u * x + v * h;
    const Vec c1 = a + u * d;
    rotate_world(s, up, from_to(normalized(b - a), normalized(b1 - a)));
    const Vec b2 = pivot(s, mid), c2 = effector(s, mid, end);
    rotate_world(s, mid, from_to(normalized(c2 - b2), normalized(c1 - b2)));
    if (end_rot != nullptr && end >= 0) rotate_world(s, end, qnormalize(qmul(*end_rot, qconj(world_rot(s, end)))));
}

void fabrik(cr_modstack& s, const std::vector<int>& chain, const Vec& t, int iterations, double tol) {
    const size_t n = chain.size();
    if (n < 2) return;
    std::vector<Vec>& p = s.scratch;
    p.resize(n);
    s.lengths.resize(n);
    double total = 0.0;
    for (size_t i = 0; i < n; ++i) p[i] = pivot(s, chain[i]);
    for (size_t i = 0; i + 1 < n; ++i) {
        s.lengths[i] = length(p[i + 1] - p[i]);
        total += s.lengths[i];
    }
    const Vec root = p[0];
    if (length(t - root) >= total) {
        for (size_t i = 0; i + 1 < n; ++i) p[i + 1] = p[i] + normalized(t - p[i]) * s.lengths[i];
    } else {
        for (int it = 0; it < iterations && length(p[n - 1] - t) > tol; ++it) {
            p[n - 1] = t;
            for (size_t i = n - 1; i-- > 0;) p[i] = p[i + 1] + normalized(p[i] - p[i + 1]) * s.lengths[i];
            p[0] = root;
            for (size_t i = 0; i + 1 < n; ++i) p[i + 1] = p[i] + normalized(p[i + 1] - p[i]) * s.lengths[i];
        }
    }
    for (size_t i = 0; i + 1 < n; ++i) {
        const Vec here = pivot(s, chain[i]);
        const Vec next = pivot(s, chain[i + 1]);
        rotate_world(s, chain[i], from_to(normalized(next - here), normalized(p[i + 1] - here)));
    }
}

void ccd(cr_modstack& s, const std::vector<int>& chain, const Vec& t, int iterations, double tol) {
    const size_t n = chain.size();
    if (n < 2) return;
    const int end = chain[n - 1];
    for (int it = 0; it < iterations; ++it) {
        for (size_t i = n - 1; i-- > 0;) {
            const Vec j = pivot(s, chain[i]);
            const Vec e = pivot(s, end);
            rotate_world(s, chain[i], from_to(normalized(e - j), normalized(t - j)));
        }
        if (length(pivot(s, end) - t) <= tol) break;
    }
}

void aim(cr_modstack& s, const cr_modifier_desc& d, const Vec& t) {
    Vec axis = vec_of(d.p);
    if (length(axis) < EPS) axis = {0, 0, -1};
    axis = normalized(axis);
    const bool limits = (d.flags & CR_MOD_LIMITS) != 0;
    const int count = d.joint_count;
    if ((d.flags & CR_MOD_AIM_EACH) != 0) {
        for (int k = 0; k < count; ++k) {
            const int j = d.joints[k];
            const Vec dir = normalized(t - pivot(s, j));
            if (length(dir) < EPS) continue;
            const Vec a = rotate(world_rot(s, j), axis);
            rotate_world(s, j, scaled(from_to(a, dir), std::clamp(static_cast<double>(d.joint_weights[k]), 0.0, 1.0)), limits);
        }
        return;
    }
    double total = 0.0;
    for (int k = 0; k < count; ++k) total += std::max(0.f, d.joint_weights[k]);
    if (total <= 0.0) return;
    const int last = d.joints[count - 1];
    // A few passes: the joints' pivots sit apart, so a near target needs the error re-measured.
    for (int pass = 0; pass < 4; ++pass) {
        const Vec dir = normalized(t - pivot(s, last));
        if (length(dir) < EPS) return;
        const Vec a = rotate(world_rot(s, last), axis);
        double k_axis[3];
        const double theta = qangle_axis(from_to(a, dir), k_axis);
        if (theta < 1e-9) return;
        for (int k = 0; k < count; ++k) {
            const double share = std::max(0.f, d.joint_weights[k]) / total;
            if (share <= 0.0) continue;
            rotate_world(s, d.joints[k], qaxis(k_axis[0], k_axis[1], k_axis[2], theta * share), limits);
        }
    }
}

void constraint(cr_modstack& s, const cr_modifier_desc& d, const Vec& tpos, const Quat& trot) {
    const int j = d.joints[0];
    if (d.kind == CR_CONSTRAINT_AIM) {
        Vec axis = vec_of(d.p);
        if (length(axis) < EPS) axis = {0, 0, -1};
        const Vec dir = normalized(tpos - pivot(s, j));
        if (length(dir) < EPS) return;
        rotate_world(s, j, from_to(rotate(world_rot(s, j), normalized(axis)), dir));
        return;
    }
    const bool copy = d.kind == CR_CONSTRAINT_COPY;
    const Vec offset = copy ? Vec{} : vec_of(d.p);
    const Quat offset_rot = copy ? Quat{} : from_euler_deg(d.p + 3);
    if (d.kind == CR_CONSTRAINT_ORIENT || d.kind == CR_CONSTRAINT_PARENT || copy) {
        const Quat want = qnormalize(qmul(trot, offset_rot));
        rotate_world(s, j, qnormalize(qmul(want, qconj(world_rot(s, j)))));
    }
    if (d.kind == CR_CONSTRAINT_POINT || d.kind == CR_CONSTRAINT_PARENT || copy) {
        move_world(s, j, tpos + rotate(trot, offset) - pivot(s, j));
    }
}

// Closest point to p on segment a-b.
Vec closest(const Vec& a, const Vec& b, const Vec& p) {
    const Vec ab = b - a;
    const double l2 = dot(ab, ab);
    if (l2 < EPS) return a;
    return a + ab * std::clamp(dot(p - a, ab) / l2, 0.0, 1.0);
}

// Tail of chain segment k in model space: the next joint's pivot, or a last bone's tail.
bool tail_of(const cr_modstack& s, const std::vector<int>& chain, size_t k, Vec& out) {
    if (k + 1 < chain.size()) {
        out = pivot(s, chain[k + 1]);
        return true;
    }
    const Joint& j = s.rig->joints[static_cast<size_t>(chain[k])];
    if (j.kind != CR_JOINT_BONE || (j.endpoint[0] == 0.f && j.endpoint[1] == 0.f && j.endpoint[2] == 0.f)) return false;
    out = transform_point(s.child[static_cast<size_t>(chain[k])], Vec{});
    return true;
}

void spring(cr_modstack& s, ModRt& m, double dt) {
    const cr_modifier_desc& d = m.d;
    const std::vector<int>& chain = m.chain;
    const double stiffness = d.p[0], drag = std::clamp(static_cast<double>(d.p[1]), 0.0, 1.0);
    const Vec gravity{d.p[2], d.p[3], d.p[4]};
    const double radius = d.p[5];
    const double h = d.p[6] > 0.f ? d.p[6] : 1.0 / 60.0;
    if (!m.spring_init) {
        for (size_t k = 0; k < chain.size(); ++k) {
            Vec tail;
            m.cur[k] = m.prev[k] = tail_of(s, chain, k, tail) ? transform_point(s.frame, tail) : Vec{};
        }
        m.spring_init = true;
        m.acc = 0.0;
    }
    m.acc += dt;
    int steps = static_cast<int>(std::floor(m.acc / h));
    if (steps > MAX_SPRING_STEPS) {
        steps = MAX_SPRING_STEPS;
        m.acc = 0.0;
    } else {
        m.acc -= steps * h;
    }
    for (size_t k = 0; k < chain.size(); ++k) {
        Vec tail_m;
        if (!tail_of(s, chain, k, tail_m)) continue;
        const Vec head_m = pivot(s, chain[k]);
        const Vec head = transform_point(s.frame, head_m);
        const Vec rest = transform_point(s.frame, tail_m) - head;  // the animated direction (parents settled)
        const double len = length(rest);
        if (len < EPS) continue;
        const Vec rest_dir = rest * (1.0 / len);
        for (int st = 0; st < steps; ++st) {
            const Vec cur = m.cur[k];
            Vec next = cur + (cur - m.prev[k]) * (1.0 - drag) + rest_dir * (stiffness * h) + gravity * (h * h);
            next = head + normalized(next - head) * len;
            // Push out of every collider and back onto the bone's length sphere; repeating the two projections
            // walks the tail to a point that satisfies both (one pass can drag it back inside).
            for (int pass = 0; pass < 8; ++pass) {
                bool hit = false;
                for (const Collider& c : s.colliders) {
                    const Mat4& cw = s.world[static_cast<size_t>(c.joint)];
                    const Vec ca = transform_point(s.frame, transform_point(cw, c.a));
                    const Vec cb = transform_point(s.frame, transform_point(cw, c.b));
                    const Vec q = closest(ca, cb, next);
                    const double reach = c.radius + radius;
                    const double dist = length(next - q);
                    if (dist < reach - 1e-9) {
                        next = q + (dist > EPS ? (next - q) * (1.0 / dist) : rest_dir) * reach;
                        next = head + normalized(next - head) * len;
                        hit = true;
                    }
                }
                if (!hit) break;
            }
            m.prev[k] = cur;
            m.cur[k] = next;
        }
        const Vec want_m = transform_point(s.frame_inv, m.cur[k]);
        rotate_world(s, chain[k], from_to(normalized(tail_m - head_m), normalized(want_m - head_m)));
    }
}

void full_body(cr_modstack& s, const cr_modifier_desc& d) {
    const int body = d.joints[0];
    const int limbs = (d.joint_count - 1) / 3;
    const int iterations = d.p[0] > 0.f ? static_cast<int>(d.p[0]) : 3;
    Vec goals[5];
    bool has[5]{};
    Quat unused;
    for (int it = 0; it < iterations; ++it) {
        Vec shift;
        double pulls = 0.0;
        for (int k = 0; k < limbs; ++k) {
            has[k] = target_frame(s, d.target + k, goals[k], unused);
            if (!has[k]) continue;
            const int up = d.joints[1 + 3 * k], mid = d.joints[2 + 3 * k], end = d.joints[3 + 3 * k];
            const Vec a = pivot(s, up);
            const double reach = length(pivot(s, mid) - a) + length(pivot(s, end) - pivot(s, mid));
            const double pull = std::clamp(static_cast<double>(d.joint_weights[1 + 3 * k]), 0.0, 1.0);
            if (pull <= 0.0) continue;
            pulls += pull;
            const Vec to = goals[k] - a;
            const double excess = length(to) - reach * 0.999;
            if (excess > 0.0) shift = shift + normalized(to) * (excess * pull);
        }
        if (pulls > 1.0) shift = shift * (1.0 / pulls);
        Vec hip;
        if (d.pole >= 0 && target_frame(s, d.pole, hip, unused)) {
            shift = shift + (hip - pivot(s, body)) * std::clamp(static_cast<double>(d.joint_weights[0]), 0.0, 1.0);
        }
        if (length(shift) < 1e-12) break;
        move_world(s, body, shift);
    }
    for (int k = 0; k < limbs; ++k) {
        if (!target_frame(s, d.target + k, goals[k], unused)) continue;
        const int up = d.joints[1 + 3 * k], mid = d.joints[2 + 3 * k], end = d.joints[3 + 3 * k];
        const double reach = std::clamp(static_cast<double>(d.joint_weights[2 + 3 * k]), 0.0, 1.0);
        const Vec c = pivot(s, end);
        two_bone(s, up, mid, end, c + (goals[k] - c) * reach, nullptr, 0.0, nullptr);
    }
}

// ---- locomotion adaptation (#159) -------------------------------------------------------------------------------

constexpr double DEG_TO_RAD = 3.14159265358979323846 / 180.0;
const Vec UP{0.0, 1.0, 0.0};

int leg_count(const cr_modifier_desc& d) { return (d.joint_count - 1) / 3; }

// Exponential easing toward want at rate (1/s); rate <= 0 = instant.
double ease(double cur, double want, double rate, double dt) {
    if (rate <= 0.0) return want;
    return cur + (want - cur) * (1.0 - std::exp(-rate * dt));
}

// The highest pelvis offset along up, at most `cap` (what the caller wants), that lets every leg moving with the
// pelvis reach its goal: |goal - (thigh + up * P)| <= its length. A leg already in reach at `cap` sets no bound.
double reach_bound(const cr_modstack& s, const ModRt& m, const Vec* goals, double cap) {
    double bound = cap;
    const cr_modifier_desc& d = m.d;
    for (int k = 0, n = leg_count(d); k < n; ++k) {
        if (!m.with_pelvis[k]) continue;
        const int up = d.joints[1 + 3 * k], mid = d.joints[2 + 3 * k], end = d.joints[3 + 3 * k];
        const Vec a = pivot(s, up);
        const Vec b = pivot(s, mid);
        const double full = length(b - a) + length(effector(s, mid, end) - b);
        const Vec g = goals[k] - a;
        if (length(g - UP * cap) <= full) continue;  // already in reach at the offset the caller wants
        const double reach = full * (1.0 - 1e-9);
        const double h2 = g.x * g.x + g.z * g.z;
        // Out of reach horizontally: the best the pelvis can do is sit level with the goal.
        const double p = h2 >= reach * reach ? g.y : g.y + std::sqrt(reach * reach - h2);
        bound = std::min(bound, p);
    }
    return bound;
}

// The pole a leg bends toward when its animated knee is straight: forward along the foot (else -Z).
bool straight_leg_pole(const cr_modstack& s, int up, int mid, int end, bool has_toe, const Vec& goal, Vec& pole) {
    const Vec a = pivot(s, up), b = pivot(s, mid), c = effector(s, mid, end);
    const Vec ac = c - a;
    const double lab = length(b - a);
    if (length(ac) < EPS || length(cross(normalized(ac), b - a)) > 1e-3 * lab) return false;
    Vec fwd{0.0, 0.0, -1.0};
    if (has_toe) {
        const Vec t = transform_point(s.child[static_cast<size_t>(end)], Vec{}) - c;
        const Vec h{t.x, 0.0, t.z};
        if (length(h) > EPS) fwd = normalized(h);
    }
    pole = b + fwd * lab + (goal - c) * 0.5;
    return true;
}

// Two-bone a leg onto goal; the foot keeps the world rotation `foot` (when it has one).
void place_leg(cr_modstack& s, const ModRt& m, int k, const Vec& goal, const Quat* foot) {
    const cr_modifier_desc& d = m.d;
    const int up = d.joints[1 + 3 * k], mid = d.joints[2 + 3 * k], end = d.joints[3 + 3 * k];
    Vec pole;
    const bool use_pole = straight_leg_pole(s, up, mid, end, m.has_toe[k], goal, pole);
    two_bone(s, up, mid, end, goal, use_pole ? &pole : nullptr, 0.0, nullptr);
    if (foot != nullptr && end >= 0) rotate_world(s, end, qnormalize(qmul(*foot, qconj(world_rot(s, end)))));
}

void orient_warp(cr_modstack& s, ModRt& m, double dt) {
    const cr_modifier_desc& d = m.d;
    const double mx = std::clamp(static_cast<double>(m.v[CR_VALUE_MAX_ANGLE]), 0.0, 180.0);
    const double want = std::clamp(static_cast<double>(m.v[CR_VALUE_ANGLE]), -mx, mx);
    if (!m.angle_init) {
        m.angle = want;
        m.angle_init = true;
    } else {
        m.angle = ease(m.angle, want, m.v[CR_VALUE_RATE], dt);
    }
    if (std::fabs(m.angle) < 1e-9) return;
    const double rad = m.angle * DEG_TO_RAD;
    rotate_world(s, d.joints[0], qaxis(0.0, 1.0, 0.0, rad));
    const int n = d.joint_count - 1;
    if (n <= 0) return;
    double sum = 0.0;
    for (int i = 1; i <= n; ++i) sum += std::max(0.f, d.joint_weights[i]);
    const double back = rad * static_cast<double>(m.v[CR_VALUE_COUNTER]);
    for (int i = 1; i <= n; ++i) {
        const double w = sum > 0.0 ? std::max(0.f, d.joint_weights[i]) / sum : 1.0 / n;
        if (w > 0.0) rotate_world(s, d.joints[i], qaxis(0.0, 1.0, 0.0, -back * w));
    }
}

void stride_warp(cr_modstack& s, ModRt& m) {
    const cr_modifier_desc& d = m.d;
    m.out_pelvis = 0.0;
    const double scale = m.v[CR_VALUE_SCALE];
    Vec dir{m.v[CR_VALUE_DIR_X], 0.0, m.v[CR_VALUE_DIR_Z]};
    if (length(dir) < 1e-9 || std::fabs(scale - 1.0) < 1e-9) return;
    dir = normalized(dir);
    const int legs = leg_count(d);
    Vec goals[CR_MAX_LEGS];
    Quat feet[CR_MAX_LEGS];
    for (int k = 0; k < legs; ++k) {
        const int up = d.joints[1 + 3 * k], mid = d.joints[2 + 3 * k], end = d.joints[3 + 3 * k];
        const Vec a = pivot(s, up), f = effector(s, mid, end);
        goals[k] = f + dir * (dot(f - a, dir) * (scale - 1.0));
        if (end >= 0) feet[k] = world_rot(s, end);
    }
    const double pw = std::clamp(static_cast<double>(m.v[CR_VALUE_PELVIS]), 0.0, 1.0);
    if (pw > 0.0) {
        const double need = reach_bound(s, m, goals, 0.0);
        if (need < 0.0) {
            m.out_pelvis = need * pw;
            move_world(s, d.joints[0], UP * m.out_pelvis);
        }
    }
    for (int k = 0; k < legs; ++k) place_leg(s, m, k, goals[k], d.joints[3 + 3 * k] >= 0 ? &feet[k] : nullptr);
}

// The leg length average of a foot-placement / stride modifier on the current pose.
double mean_leg(const cr_modstack& s, const cr_modifier_desc& d) {
    double sum = 0.0;
    const int legs = leg_count(d);
    for (int k = 0; k < legs; ++k) {
        const int up = d.joints[1 + 3 * k], mid = d.joints[2 + 3 * k], end = d.joints[3 + 3 * k];
        const Vec b = pivot(s, mid);
        sum += length(b - pivot(s, up)) + length(effector(s, mid, end) - b);
    }
    return legs > 0 ? sum / legs : 0.0;
}

bool finite3(const double* p) { return std::isfinite(p[0]) && std::isfinite(p[1]) && std::isfinite(p[2]); }

int32_t foot_placement(cr_modstack& s, ModRt& m, double dt) {
    const cr_modifier_desc& d = m.d;
    const int legs = leg_count(d);
    const double mean = mean_leg(s, d);
    auto length_value = [&](int key, double auto_share) {
        const double v = m.v[key];
        return v > 0.0 ? v : auto_share * mean;
    };
    const double probe_up = length_value(CR_VALUE_PROBE_UP, 1.0);
    const double probe_down = length_value(CR_VALUE_PROBE_DOWN, 0.75);
    const double max_drop = length_value(CR_VALUE_MAX_DROP, 0.5);
    const double max_lock = length_value(CR_VALUE_MAX_LOCK_DISTANCE, 0.5);
    const double max_rise = std::max(0.0, static_cast<double>(m.v[CR_VALUE_MAX_RISE]));
    const double max_align = std::clamp(static_cast<double>(m.v[CR_VALUE_MAX_ALIGN]), 0.0, 90.0) * DEG_TO_RAD;
    const double ground_y = m.v[CR_VALUE_GROUND_Y];
    const double unlock_time = std::max(0.0, static_cast<double>(m.v[CR_VALUE_UNLOCK_TIME]));

    // The animated feet.
    Vec ankle[CR_MAX_LEGS], toe[CR_MAX_LEGS];
    Quat foot[CR_MAX_LEGS];
    for (int k = 0; k < legs; ++k) {
        const int mid = d.joints[2 + 3 * k], end = d.joints[3 + 3 * k];
        ankle[k] = effector(s, mid, end);
        if (end >= 0) foot[k] = world_rot(s, end);
        if (m.has_toe[k]) toe[k] = transform_point(s.child[static_cast<size_t>(end)], Vec{});
    }

    // The ground: one host call for every probe.
    double ground[CR_MAX_LEGS]{};
    Vec normal[CR_MAX_LEGS];
    for (Vec& n : normal) n = UP;
    if (s.ground != nullptr && m.probe_count > 0) {
        const Vec up_w = transform_dir(s.frame, UP);
        const double units = length(up_w);  // world units per model unit
        const Vec down_w = normalized(up_w) * -1.0;
        int count = 0;
        for (int k = 0; k < legs; ++k) {
            for (int point = 0; point < (m.has_toe[k] ? 2 : 1); ++point) {
                const Vec p = point == 0 ? ankle[k] : toe[k];
                const Vec o = transform_point(s.frame, Vec{p.x, ground_y + probe_up, p.z});
                cr_ground_probe& pr = s.probes[static_cast<size_t>(count++)];
                pr.origin[0] = o.x;
                pr.origin[1] = o.y;
                pr.origin[2] = o.z;
                pr.direction[0] = down_w.x;
                pr.direction[1] = down_w.y;
                pr.direction[2] = down_w.z;
                pr.max_distance = (probe_up + probe_down) * units;
                pr.foot = k;
                pr.point = point;
            }
        }
        std::fill(s.hits.begin(), s.hits.begin() + count, cr_ground_hit{});
        if (s.ground(s.ground_user, s.probes.data(), count, s.hits.data()) < 0) {
            set_error("cr_modstack: the ground query failed");
            return CR_ERR_HOST;
        }
        bool has[CR_MAX_LEGS]{};
        int ties[CR_MAX_LEGS]{};
        Vec sum[CR_MAX_LEGS];
        for (int i = 0; i < count; ++i) {
            const cr_ground_hit& h = s.hits[static_cast<size_t>(i)];
            if (h.hit == 0 || !finite3(h.point)) continue;
            const int k = s.probes[static_cast<size_t>(i)].foot;
            const double height = transform_point(s.frame_inv, Vec{h.point[0], h.point[1], h.point[2]}).y - ground_y;
            Vec n = transform_dir(s.frame_inv, Vec{h.normal[0], h.normal[1], h.normal[2]});
            n = std::isfinite(n.x) && std::isfinite(n.y) && std::isfinite(n.z) && length(n) > EPS ? normalized(n) : UP;
            if (!has[k] || height > ground[k] + 1e-6) {
                has[k] = true;
                ground[k] = height;
                sum[k] = n;
                ties[k] = 1;
            } else if (std::fabs(height - ground[k]) <= 1e-6) {
                sum[k] = sum[k] + n;
                ++ties[k];
            }
        }
        for (int k = 0; k < legs; ++k) {
            if (has[k] && length(sum[k]) > EPS) normal[k] = normalized(sum[k]);
        }
    }

    // Easing, locks, goals.
    Vec goals[CR_MAX_LEGS];
    for (int k = 0; k < legs; ++k) {
        LegRt& l = m.legs[k];
        const double lock = std::clamp(static_cast<double>(m.v[CR_VALUE_LOCK + k]), 0.0, 1.0);
        if (!l.init) {
            l.ground = ground[k];
            l.init = true;
        } else if (!l.locked) {
            l.ground = ease(l.ground, ground[k], m.v[CR_VALUE_FOOT_RATE], dt);
        }
        const Vec live = ankle[k] + UP * l.ground;
        if (lock > 0.0 && !l.locked) {
            l.locked = true;
            l.fade = 0.0;
            l.lock_w = transform_point(s.frame, ankle[k] + UP * ground[k]);
            l.lock_height = ankle[k].y;
        }
        // Pinned where it locked; its height above that ground follows the animation (heel roll, a late contact).
        Vec lock_m = transform_point(s.frame_inv, l.lock_w) + UP * (ankle[k].y - l.lock_height);
        if (l.locked && (lock <= 0.0 || length(lock_m - live) > max_lock)) {
            l.locked = false;
            l.fade = l.alpha;
        }
        double a;
        if (l.locked) {
            a = lock;
        } else {
            if (l.fade > 0.0) l.fade = unlock_time > 0.0 ? std::max(0.0, l.fade - dt / unlock_time) : 0.0;
            a = l.fade;
        }
        l.alpha = a;
        goals[k] = a > 0.0 ? live + (lock_m - live) * a : live;
    }

    // The pelvis: down to the lowest foot's need, eased, never so high a goal is out of reach.
    double need = std::numeric_limits<double>::infinity();
    for (int k = 0; k < legs; ++k) need = std::min(need, goals[k].y - ankle[k].y);
    need = std::clamp(need, -max_drop, max_rise);
    if (!m.pelvis_init) {
        m.pelvis = need;
        m.pelvis_init = true;
    } else {
        m.pelvis = ease(m.pelvis, need, m.v[CR_VALUE_PELVIS_RATE], dt);
    }
    m.pelvis = std::max(reach_bound(s, m, goals, m.pelvis), -max_drop);
    m.out_pelvis = m.pelvis;
    if (m.pelvis != 0.0) move_world(s, d.joints[0], UP * m.pelvis);

    // The legs, then the feet turned toward the ground's normal.
    for (int k = 0; k < legs; ++k) {
        const int end = d.joints[3 + 3 * k];
        Quat aligned;
        if (end >= 0) {
            Quat q = from_to(UP, normal[k]);
            double axis[3];
            const double angle = qangle_axis(q, axis);
            if (angle > max_align) q = qaxis(axis[0], axis[1], axis[2], max_align);
            aligned = qnormalize(qmul(q, foot[k]));
        }
        place_leg(s, m, k, goals[k], end >= 0 ? &aligned : nullptr);
    }
    return CR_OK;
}

// Which keyed values a modifier type reads (inputs) / reports (outputs).
bool reads_value(const cr_modifier_desc& d, int key) {
    switch (d.type) {
        case CR_MOD_ORIENT_WARP:
            return key >= CR_VALUE_ANGLE && key <= CR_VALUE_COUNTER;
        case CR_MOD_STRIDE_WARP:
            return key >= CR_VALUE_SCALE && key <= CR_VALUE_PELVIS;
        case CR_MOD_FOOT_PLACEMENT:
            return (key >= CR_VALUE_LOCK && key < CR_VALUE_LOCK + leg_count(d))
                || (key >= CR_VALUE_PROBE_UP && key < CR_VALUE_INPUT_COUNT);
        default:
            return false;
    }
}

// Blend the joints a modifier touched back toward the pose it got, by its weight.
void blend_back(cr_modstack& s, float w) {
    const size_t n = s.snap.size();
    for (size_t i = 0; i < n; ++i) {
        if (!s.touched[i]) continue;
        if (w <= 0.f) {
            s.pose->v[i] = s.snap[i];
            continue;
        }
        if (w >= 1.f) continue;
        lerp_pose_quat(s.snap[i], s.pose->v[i], w);
        s.pose->v[i] = s.snap[i];
    }
}

bool valid_joint(const cr_rig& rig, int j) { return j >= 0 && static_cast<size_t>(j) < rig.joints.size(); }

bool check_mod(const cr_rig& rig, const cr_modifier_desc& d, int targets, std::vector<int>& chain, std::string& why) {
    auto joints_ok = [&](int need_min, int need_max) {
        if (d.joint_count < need_min || d.joint_count > need_max) {
            why = "needs " + std::to_string(need_min) + ".." + std::to_string(need_max) + " joints";
            return false;
        }
        for (int k = 0; k < d.joint_count; ++k) {
            if (!valid_joint(rig, d.joints[k])) {
                why = "joint " + std::to_string(d.joints[k]) + " out of range";
                return false;
            }
        }
        return true;
    };
    if (d.target >= targets || d.target < -1 || d.pole >= targets || d.pole < -1) {
        why = "target slot out of range";
        return false;
    }
    switch (d.type) {
        case CR_MOD_TWO_BONE:
            return joints_ok(3, 3);
        case CR_MOD_FABRIK:
        case CR_MOD_CCD:
        case CR_MOD_SPRING:
            if (!joints_ok(2, 2)) return false;
            chain = path(rig, d.joints[0], d.joints[1]);
            if (chain.empty()) {
                why = "the chain's start is not an ancestor of its end";
                return false;
            }
            return true;
        case CR_MOD_AIM:
            return joints_ok(1, CR_MOD_MAX_JOINTS);
        case CR_MOD_CONSTRAINT:
            if (d.kind < CR_CONSTRAINT_POINT || d.kind > CR_CONSTRAINT_COPY) {
                why = "unknown constraint kind";
                return false;
            }
            return joints_ok(1, 1);
        case CR_MOD_ORIENT_WARP:
            return joints_ok(1, CR_MOD_MAX_JOINTS);
        case CR_MOD_STRIDE_WARP:
        case CR_MOD_FOOT_PLACEMENT: {
            if (d.joint_count < 4 || d.joint_count > 1 + 3 * CR_MAX_LEGS || (d.joint_count - 1) % 3 != 0) {
                why = "needs the pelvis and 3 joints per leg (1..5 legs)";
                return false;
            }
            for (int k = 0; k < d.joint_count; ++k) {
                const bool foot = k > 0 && (k - 1) % 3 == 2;  // a leg's end may be -1 (the mid joint's tail)
                if (!valid_joint(rig, d.joints[k]) && !(foot && d.joints[k] == -1)) {
                    why = "joint " + std::to_string(d.joints[k]) + " out of range";
                    return false;
                }
            }
            return true;
        }
        case CR_MOD_FULL_BODY:
            if (!joints_ok(4, 16) || (d.joint_count - 1) % 3 != 0) {
                why = d.joint_count >= 4 ? "needs the body and 3 joints per limb" : why;
                return false;
            }
            if (d.target < 0 || d.target + (d.joint_count - 1) / 3 > targets) {
                why = "a full-body modifier needs one target slot per limb";
                return false;
            }
            return true;
        default:
            why = "unknown modifier type";
            return false;
    }
}

}  // namespace

namespace cenda::rig {

int32_t modstack_apply(cr_modstack& s, PoseBuf& pose, float dt) {
    const size_t n = s.rig->joints.size();
    if (pose.v.size() != n) {
        set_error("cr_modstack: pose of another rig");
        return CR_ERR_ARG;
    }
    s.pose = &pose;
    fk_all(s);
    for (ModRt& m : s.mods) {
        const cr_modifier_desc& d = m.d;
        if (d.weight <= 0.f) {
            if (d.type == CR_MOD_SPRING) m.spring_init = false;  // restart from the pose when it comes back
            m.reset_locomotion();                                // locks and easing too
            continue;
        }
        s.snap = pose.v;
        std::fill(s.touched.begin(), s.touched.end(), 0);
        Vec tpos;
        Quat trot;
        const bool has_target = target_frame(s, d.target, tpos, trot);
        switch (d.type) {
            case CR_MOD_TWO_BONE: {
                if (!has_target) break;
                Vec pole;
                Quat unused;
                const bool has_pole = target_frame(s, d.pole, pole, unused);
                two_bone(s, d.joints[0], d.joints[1], d.joints[2], tpos, has_pole ? &pole : nullptr,
                         std::clamp(static_cast<double>(d.p[0]), 0.0, 1.0),
                         (d.flags & CR_MOD_MATCH_ROTATION) != 0 ? &trot : nullptr);
                break;
            }
            case CR_MOD_FABRIK:
            case CR_MOD_CCD: {
                if (!has_target) break;
                const int it = d.p[0] > 0.f ? static_cast<int>(d.p[0]) : 10;
                const double tol = d.p[1] > 0.f ? d.p[1] : 1e-4;
                if (d.type == CR_MOD_FABRIK) {
                    fabrik(s, m.chain, tpos, it, tol);
                } else {
                    ccd(s, m.chain, tpos, it, tol);
                }
                break;
            }
            case CR_MOD_AIM:
                if (has_target) aim(s, d, tpos);
                break;
            case CR_MOD_CONSTRAINT:
                if (has_target) constraint(s, d, tpos, trot);
                break;
            case CR_MOD_SPRING:
                spring(s, m, dt);
                break;
            case CR_MOD_FULL_BODY:
                full_body(s, d);
                break;
            case CR_MOD_ORIENT_WARP:
                orient_warp(s, m, dt);
                break;
            case CR_MOD_STRIDE_WARP:
                stride_warp(s, m);
                break;
            case CR_MOD_FOOT_PLACEMENT: {
                const int32_t rc = foot_placement(s, m, dt);
                if (rc != CR_OK) {
                    s.pose = nullptr;
                    return rc;
                }
                break;
            }
            default:
                break;
        }
        if (d.weight < 1.f) {
            blend_back(s, d.weight);
            fk_all(s);
        }
    }
    s.pose = nullptr;
    return CR_OK;
}

}  // namespace cenda::rig

extern "C" {

cr_modstack* cr_modstack_create(const cr_rig* rig, const cr_modifier_desc* mods, int32_t mod_count, int32_t target_count) {
    if (rig == nullptr || mod_count < 0 || (mod_count > 0 && mods == nullptr) || target_count < 0 || target_count > 4096) {
        set_error("cr_modstack_create: bad arguments");
        return nullptr;
    }
    auto* s = new (std::nothrow) cr_modstack();
    if (s == nullptr) {
        set_error("cr_modstack_create: out of memory");
        return nullptr;
    }
    s->rig = rig;
    const size_t n = rig->joints.size();
    for (int32_t i = 0; i < mod_count; ++i) {
        ModRt m;
        m.d = mods[i];
        std::string why;
        if (!check_mod(*rig, m.d, target_count, m.chain, why)) {
            set_error("cr_modstack_create: modifier " + std::to_string(i) + ": " + why);
            delete s;
            return nullptr;
        }
        m.d.weight = std::clamp(m.d.weight, 0.f, 1.f);
        m.cur.resize(m.chain.size());
        m.prev.resize(m.chain.size());
        // Locomotion defaults (rig.h CR_VALUE_*; 0 = derived from the leg length where documented).
        m.v[CR_VALUE_MAX_ANGLE] = 90.f;
        m.v[CR_VALUE_COUNTER] = 1.f;
        m.v[CR_VALUE_SCALE] = 1.f;
        m.v[CR_VALUE_DIR_Z] = -1.f;
        m.v[CR_VALUE_PELVIS] = 1.f;
        m.v[CR_VALUE_MAX_ALIGN] = 30.f;
        m.v[CR_VALUE_PELVIS_RATE] = 10.f;
        m.v[CR_VALUE_FOOT_RATE] = 15.f;
        m.v[CR_VALUE_UNLOCK_TIME] = 0.15f;
        if (m.d.type == CR_MOD_STRIDE_WARP || m.d.type == CR_MOD_FOOT_PLACEMENT) {
            const int pelvis = m.d.joints[0];
            for (int k = 0, legs = leg_count(m.d); k < legs; ++k) {
                const int end = m.d.joints[3 + 3 * k];
                if (end >= 0) {
                    const Joint& j = rig->joints[static_cast<size_t>(end)];
                    m.has_toe[k] = j.kind == CR_JOINT_BONE
                        && length(vec_of(j.endpoint)) > 1e-6;
                }
                for (int a = m.d.joints[1 + 3 * k]; a >= 0; a = rig->joints[static_cast<size_t>(a)].parent) {
                    if (a == pelvis) {
                        m.with_pelvis[k] = true;
                        break;
                    }
                }
                m.probe_count += m.has_toe[k] ? 2 : 1;
            }
            if (m.d.type == CR_MOD_FOOT_PLACEMENT && static_cast<size_t>(m.probe_count) > s->probes.size()) {
                s->probes.resize(static_cast<size_t>(m.probe_count));
                s->hits.resize(static_cast<size_t>(m.probe_count));
            }
        }
        s->mods.push_back(std::move(m));
    }
    s->targets.resize(static_cast<size_t>(target_count));
    s->limits.resize(n);
    s->world.resize(n);
    s->child.resize(n);
    s->dirty.resize(n);
    s->snap.resize(n);
    s->touched.resize(n);
    s->scratch.reserve(n);
    s->lengths.reserve(n);
    return s;
}

void cr_modstack_destroy(cr_modstack* stack) { delete stack; }

int32_t cr_modstack_set_target(cr_modstack* stack, int32_t target, const cr_target* value) {
    if (stack == nullptr || value == nullptr || target < 0 || static_cast<size_t>(target) >= stack->targets.size()) {
        set_error("cr_modstack_set_target: bad target");
        return CR_ERR_ARG;
    }
    if (value->kind == CR_TARGET_JOINT && !valid_joint(*stack->rig, value->joint)) {
        set_error("cr_modstack_set_target: joint out of range");
        return CR_ERR_RANGE;
    }
    for (int c = 0; c < 3; ++c) {
        if (!std::isfinite(value->pos[c])) {
            set_error("cr_modstack_set_target: non-finite position");
            return CR_ERR_ARG;
        }
    }
    Target& t = stack->targets[static_cast<size_t>(target)];
    t.kind = value->kind;
    t.joint = value->joint;
    t.pos = vec_of(value->pos);
    const float* r = value->rot;
    t.rot = (r[0] == 0.f && r[1] == 0.f && r[2] == 0.f && r[3] == 0.f) ? Quat{} : qnormalize(Quat{r[3], r[0], r[1], r[2]});
    return CR_OK;
}

int32_t cr_modstack_set_weight(cr_modstack* stack, int32_t mod, float weight) {
    if (stack == nullptr || mod < 0 || static_cast<size_t>(mod) >= stack->mods.size() || !std::isfinite(weight)) {
        set_error("cr_modstack_set_weight: bad modifier");
        return CR_ERR_ARG;
    }
    stack->mods[static_cast<size_t>(mod)].d.weight = std::clamp(weight, 0.f, 1.f);
    return CR_OK;
}

int32_t cr_modstack_set_limit(cr_modstack* stack, int32_t joint, const float* ref, const float* mn, const float* mx) {
    if (stack == nullptr || !valid_joint(*stack->rig, joint)) {
        set_error("cr_modstack_set_limit: bad joint");
        return CR_ERR_ARG;
    }
    Limit& l = stack->limits[static_cast<size_t>(joint)];
    if (mn == nullptr || mx == nullptr) {
        l = Limit{};
        return CR_OK;
    }
    l.has = true;
    l.order = CR_LIMIT_XYZ;
    for (int c = 0; c < 3; ++c) {
        l.ref[c] = ref != nullptr ? ref[c] : stack->rig->joints[static_cast<size_t>(joint)].rot[c];
        l.mn[c] = mn[c];
        l.mx[c] = mx[c];
    }
    return CR_OK;
}

int32_t cr_modstack_set_limit_order(cr_modstack* stack, int32_t joint, int32_t order) {
    if (stack == nullptr || !valid_joint(*stack->rig, joint) || (order != CR_LIMIT_XYZ && order != CR_LIMIT_YXZ)) {
        set_error("cr_modstack_set_limit_order: bad joint or order");
        return CR_ERR_ARG;
    }
    stack->limits[static_cast<size_t>(joint)].order = order;
    return CR_OK;
}

int32_t cr_modstack_add_collider(cr_modstack* stack, int32_t joint, const float* a, const float* b, float radius) {
    if (stack == nullptr || !valid_joint(*stack->rig, joint) || a == nullptr || !(radius >= 0.f)) {
        set_error("cr_modstack_add_collider: bad arguments");
        return CR_ERR_ARG;
    }
    Collider c;
    c.joint = joint;
    c.a = vec_of(a);
    c.b = b != nullptr ? vec_of(b) : c.a;
    c.radius = radius;
    stack->colliders.push_back(c);
    return static_cast<int32_t>(stack->colliders.size() - 1);
}

int32_t cr_modstack_set_frame(cr_modstack* stack, const float* m) {
    if (stack == nullptr) {
        set_error("cr_modstack_set_frame: null stack");
        return CR_ERR_ARG;
    }
    stack->frame = m != nullptr ? Mat4::from_float(m) : Mat4::identity();
    stack->frame_inv = stack->frame.affine_inverse();
    return CR_OK;
}

int32_t cr_target_from_socket(const cr_rig* rig, int32_t joint, const float* pos, const float* rot_deg, cr_target* out) {
    if (rig == nullptr || out == nullptr || !valid_joint(*rig, joint)) {
        set_error("cr_target_from_socket: bad arguments");
        return CR_ERR_ARG;
    }
    // socket world = W_pose · W_rest^-1 · T(pos) R(rot); a JOINT target = pivot + R_W · offset, R_W · offset_rot.
    const Mat4& rest = rig->rest_world[static_cast<size_t>(joint)];
    const Joint& j = rig->joints[static_cast<size_t>(joint)];
    const Vec p = pos != nullptr ? vec_of(pos) : Vec{};
    const Vec local = transform_point(rig->rest_world_inv[static_cast<size_t>(joint)], p);
    const Vec off = j.kind == CR_JOINT_PART ? local - vec_of(j.origin) : local;
    const float zero[3] = {0.f, 0.f, 0.f};
    const Quat q = qnormalize(qmul(qconj(rotation_of(rest)), from_euler_deg(rot_deg != nullptr ? rot_deg : zero)));
    *out = cr_target{};
    out->kind = CR_TARGET_JOINT;
    out->joint = joint;
    out->pos[0] = static_cast<float>(off.x);
    out->pos[1] = static_cast<float>(off.y);
    out->pos[2] = static_cast<float>(off.z);
    out->rot[0] = static_cast<float>(q.x);
    out->rot[1] = static_cast<float>(q.y);
    out->rot[2] = static_cast<float>(q.z);
    out->rot[3] = static_cast<float>(q.w);
    return CR_OK;
}

int32_t cr_modstack_reset(cr_modstack* stack) {
    if (stack == nullptr) {
        set_error("cr_modstack_reset: null stack");
        return CR_ERR_ARG;
    }
    for (ModRt& m : stack->mods) {
        m.spring_init = false;
        m.acc = 0.0;
        m.reset_locomotion();
    }
    return CR_OK;
}

int32_t cr_modstack_set_value(cr_modstack* stack, int32_t mod, int32_t key, float value) {
    if (stack == nullptr || mod < 0 || static_cast<size_t>(mod) >= stack->mods.size() || !std::isfinite(value)) {
        set_error("cr_modstack_set_value: bad modifier or value");
        return CR_ERR_ARG;
    }
    ModRt& m = stack->mods[static_cast<size_t>(mod)];
    if (!reads_value(m.d, key)) {
        set_error("cr_modstack_set_value: modifier " + std::to_string(mod) + " does not read value " + std::to_string(key));
        return CR_ERR_RANGE;
    }
    m.v[key] = value;
    return CR_OK;
}

float cr_modstack_get_value(const cr_modstack* stack, int32_t mod, int32_t key) {
    if (stack == nullptr || mod < 0 || static_cast<size_t>(mod) >= stack->mods.size()) return 0.f;
    const ModRt& m = stack->mods[static_cast<size_t>(mod)];
    if (reads_value(m.d, key)) return m.v[key];
    const int legs = m.d.type == CR_MOD_FOOT_PLACEMENT ? leg_count(m.d) : 0;
    switch (m.d.type) {
        case CR_MOD_ORIENT_WARP:
            return key == CR_VALUE_OUT_ANGLE ? static_cast<float>(m.angle) : 0.f;
        case CR_MOD_STRIDE_WARP:
        case CR_MOD_FOOT_PLACEMENT:
            if (key == CR_VALUE_OUT_PELVIS) return static_cast<float>(m.out_pelvis);
            if (key >= CR_VALUE_OUT_LOCKED && key < CR_VALUE_OUT_LOCKED + legs) {
                return m.legs[key - CR_VALUE_OUT_LOCKED].locked ? 1.f : 0.f;
            }
            if (key >= CR_VALUE_OUT_GROUND && key < CR_VALUE_OUT_GROUND + legs) {
                return static_cast<float>(m.legs[key - CR_VALUE_OUT_GROUND].ground);
            }
            if (key >= CR_VALUE_OUT_ALPHA && key < CR_VALUE_OUT_ALPHA + legs) {
                return static_cast<float>(m.legs[key - CR_VALUE_OUT_ALPHA].alpha);
            }
            return 0.f;
        default:
            return 0.f;
    }
}

int32_t cr_modstack_set_ground(cr_modstack* stack, cr_ground_fn fn, void* user) {
    if (stack == nullptr) {
        set_error("cr_modstack_set_ground: null stack");
        return CR_ERR_ARG;
    }
    stack->ground = fn;
    stack->ground_user = fn != nullptr ? user : nullptr;
    return CR_OK;
}

}  // extern "C"
