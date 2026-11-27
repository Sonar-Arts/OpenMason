// Cenda rig pose pipeline (CR ABI 2, OpenMason #156): pose slots, weighted bone masks, n-way blends,
// override / additive layers, additive deltas (local or mesh space), inertialized transitions and root
// motion. Contract in cenda/rig.h ("Pose pipeline").
//
// The Euler paths (sample, override layer without CR_POSE_QUAT) are the shared float code of rig.cpp, so
// cr_rig_pose (built from the same ops) stays bit-identical to ABI 1. Quaternion work runs in double and
// is rounded once into the float Euler degrees a pose holds.

#include "rig_internal.hpp"
#include "rig_quat.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <new>
#include <string>

using namespace cenda::rig;

namespace {

constexpr int32_t MAX_SLOTS = 1024;
// A rotation delta below this angle (radians) is an exact zero (float Euler degrees cannot hold less
// than ~1e-7 degrees of meaning anyway).
constexpr double IDENTITY_EPS = 1e-9;

bool same3(const float a[3], const float b[3]) { return std::memcmp(a, b, sizeof(float) * 3) == 0; }

bool zero3(const float a[3]) { return a[0] == 0.f && a[1] == 0.f && a[2] == 0.f; }

// Heading (degrees) of a rotation's +Z axis about +Y.
double heading_deg(const float rot[3]) {
    double r[9];
    rot_xyz(rot[0] * DEG_TO_RAD, rot[1] * DEG_TO_RAD, rot[2] * DEG_TO_RAD, r);
    return std::atan2(r[2], r[8]) * RAD_TO_DEG;
}

// Shortest signed angle difference b - a in degrees.
double angle_delta_deg(double a, double b) {
    double d = std::fmod(b - a, 360.0);
    if (d > 180.0) d -= 360.0;
    if (d < -180.0) d += 360.0;
    return d;
}

// ---- inertialization curve (Bollo, "Inertialization: High-Performance Animation Transitions in Gears of
// War", GDC 2018): x(t) = A t^5 + B t^4 + C t^3 + a0/2 t^2 + v0 t + x0 for t < t1, 0 after. x0 >= 0 is the
// offset magnitude, v0 its rate of change; a growing offset (v0 > 0) is clamped to 0 so nothing overshoots.

struct Curve {
    double A = 0, B = 0, C = 0, half_a0 = 0, v0 = 0, x0 = 0, t1 = 0;

    void fit(double x, double v, double blend) {
        x0 = x;
        v0 = std::min(v, 0.0);
        t1 = blend;
        if (v0 < 0.0) t1 = std::min(blend, -5.0 * x0 / v0);
        if (t1 <= 0.0 || x0 <= 0.0) {
            *this = Curve{};
            return;
        }
        double a0 = (-8.0 * v0 * t1 - 20.0 * x0) / (t1 * t1);
        if (a0 < 0.0) a0 = 0.0;
        const double t2 = t1 * t1, t3 = t2 * t1, t4 = t3 * t1, t5 = t4 * t1;
        A = -(a0 * t2 + 6.0 * v0 * t1 + 12.0 * x0) / (2.0 * t5);
        B = (3.0 * a0 * t2 + 16.0 * v0 * t1 + 30.0 * x0) / (2.0 * t4);
        C = -(3.0 * a0 * t2 + 12.0 * v0 * t1 + 20.0 * x0) / (2.0 * t3);
        half_a0 = a0 * 0.5;
    }

    bool live() const { return t1 > 0.0; }

    double at(double t) const {
        if (t >= t1) return 0.0;
        return (((((A * t + B) * t + C) * t + half_a0) * t + v0) * t) + x0;
    }
};

struct JointInertia {
    // Translation and rotation: a magnitude along an axis; scale: per component with a sign.
    Curve trans, rot, scale[3];
    double trans_axis[3]{};
    double rot_axis[3]{};
    double scale_sign[3]{};
};

}  // namespace

// ---- public structs ------------------------------------------------------------------------------

struct cr_poses {
    const cr_rig* rig = nullptr;
    std::vector<PoseBuf> slots;
    PoseBuf scratch;                // blend target (dst may alias a source)
    Binding binding;                // sample scratch
    std::vector<Quat> qa, qb;       // per-joint quaternion scratch (mesh space)
    std::vector<uint8_t> changed;   // per-joint scratch
};

struct cr_mask {
    const cr_rig* rig = nullptr;
    std::vector<float> w;
    std::vector<int32_t> depth;  // scratch for branch sets
};

struct cr_inertia {
    const cr_rig* rig = nullptr;
    std::vector<Pose> prev, prev2;
    float prev_dt = 0.f;
    int32_t recorded = 0;
    bool pending = false;
    float pending_blend = 0.f;
    bool active = false;
    double elapsed = 0.0;
    double duration = 0.0;
    std::vector<JointInertia> joints;
    std::vector<uint8_t> moving;  // per joint: any live curve
};

namespace cenda::rig {

void lerp_pose_quat(Pose& a, const Pose& b, float w) {
    if (w <= 0.f) return;
    if (w >= 1.f) {
        a = b;
        return;
    }
    for (int c = 0; c < 3; ++c) {
        a.pos[c] = a.pos[c] + (b.pos[c] - a.pos[c]) * w;
        a.scale[c] = a.scale[c] + (b.scale[c] - a.scale[c]) * w;
    }
    if (same3(a.rot, b.rot)) return;
    to_euler_deg(qslerp(from_euler_deg(a.rot), from_euler_deg(b.rot), w), a.rot);
}

}  // namespace cenda::rig

namespace {

size_t joint_count(const cr_poses& p) { return p.rig->joints.size(); }

// Rotation-only forward kinematics: mesh-space rotation of every joint of `buf`.
void mesh_rotations(const cr_rig& rig, const PoseBuf& buf, std::vector<Quat>& out) {
    for (int32_t idx : rig.order) {
        const size_t i = static_cast<size_t>(idx);
        const int32_t parent = rig.joints[i].parent;
        const Quat local = from_euler_deg(buf.v[i].rot);
        out[i] = parent >= 0 ? qnormalize(qmul(out[static_cast<size_t>(parent)], local)) : local;
    }
}

void op_blend(cr_poses& p, int32_t dst, int32_t first, int32_t count, const float* weights, uint32_t flags) {
    const size_t n = joint_count(p);
    PoseBuf& out = p.scratch;
    pose_rest(*p.rig, out);
    double total = 0.0;
    int32_t positive = 0, only = -1;
    for (int32_t k = 0; k < count; ++k) {
        if (weights[k] > 0.f) {
            total += weights[k];
            ++positive;
            only = first + k;
        }
    }
    if (positive == 1) {
        out.v = p.slots[static_cast<size_t>(only)].v;
        out.driven = p.slots[static_cast<size_t>(only)].driven;
    } else if (positive > 1) {
        const bool quat = (flags & CR_POSE_QUAT) != 0;
        for (size_t i = 0; i < n; ++i) {
            uint8_t driven = 0;
            if (!quat) {
                // Running lerp: after source k the value is the weighted mean of the sources so far.
                bool started = false;
                float acc = 0.f;
                Pose r{};
                for (int32_t k = 0; k < count; ++k) {
                    const float w = weights[k];
                    if (w <= 0.f) continue;
                    const PoseBuf& s = p.slots[static_cast<size_t>(first + k)];
                    driven |= s.driven[i];
                    if (!started) {
                        r = s.v[i];
                        acc = w;
                        started = true;
                        continue;
                    }
                    acc += w;
                    const float u = w / acc;
                    for (int c = 0; c < 3; ++c) {
                        r.pos[c] = r.pos[c] + (s.v[i].pos[c] - r.pos[c]) * u;
                        r.rot[c] = lerp_angle(r.rot[c], s.v[i].rot[c], u);
                        r.scale[c] = r.scale[c] + (s.v[i].scale[c] - r.scale[c]) * u;
                    }
                }
                out.v[i] = r;
            } else {
                double pos[3]{}, scale[3]{};
                Quat sum{0, 0, 0, 0};
                Quat ref{};
                bool have_ref = false;
                bool all_same_rot = true;
                const float* first_rot = nullptr;
                for (int32_t k = 0; k < count; ++k) {
                    const float w = weights[k];
                    if (w <= 0.f) continue;
                    const PoseBuf& s = p.slots[static_cast<size_t>(first + k)];
                    driven |= s.driven[i];
                    const double u = w / total;
                    for (int c = 0; c < 3; ++c) {
                        pos[c] += s.v[i].pos[c] * u;
                        scale[c] += s.v[i].scale[c] * u;
                    }
                    if (first_rot == nullptr) {
                        first_rot = s.v[i].rot;
                    } else if (!same3(first_rot, s.v[i].rot)) {
                        all_same_rot = false;
                    }
                    Quat q = from_euler_deg(s.v[i].rot);
                    if (!have_ref) {
                        ref = q;
                        have_ref = true;
                    } else if (qdot(ref, q) < 0.0) {
                        q = Quat{-q.w, -q.x, -q.y, -q.z};
                    }
                    sum.w += q.w * u;
                    sum.x += q.x * u;
                    sum.y += q.y * u;
                    sum.z += q.z * u;
                }
                for (int c = 0; c < 3; ++c) {
                    out.v[i].pos[c] = static_cast<float>(pos[c]);
                    out.v[i].scale[c] = static_cast<float>(scale[c]);
                }
                if (all_same_rot) {
                    std::memcpy(out.v[i].rot, first_rot, sizeof(float) * 3);
                } else {
                    to_euler_deg(sum, out.v[i].rot);
                }
            }
            out.driven[i] = driven;
        }
    }
    PoseBuf& d = p.slots[static_cast<size_t>(dst)];
    d.v = out.v;
    d.driven = out.driven;
}

void op_make_additive(cr_poses& p, int32_t dst, int32_t src, int32_t ref, uint32_t flags) {
    const cr_rig& rig = *p.rig;
    const size_t n = joint_count(p);
    const PoseBuf& a = p.slots[static_cast<size_t>(src)];
    const PoseBuf& b = p.slots[static_cast<size_t>(ref)];
    PoseBuf& out = p.scratch;
    out.v.resize(n);
    out.driven.resize(n);
    const bool mesh = (flags & CR_POSE_MESH_SPACE) != 0;
    if (mesh) {
        mesh_rotations(rig, a, p.qa);
        mesh_rotations(rig, b, p.qb);
    }
    for (size_t i = 0; i < n; ++i) {
        Pose& d = out.v[i];
        const Pose& av = a.v[i];
        const Pose& bv = b.v[i];
        for (int c = 0; c < 3; ++c) {
            d.pos[c] = av.pos[c] - bv.pos[c];
            d.scale[c] = (bv.scale[c] != 0.f && av.scale[c] != bv.scale[c]) ? av.scale[c] / bv.scale[c] : 1.f;
        }
        Quat delta;
        bool identity;
        if (mesh) {
            delta = qmul(p.qa[i], qconj(p.qb[i]));
            double axis[3];
            identity = qangle_axis(delta, axis) < IDENTITY_EPS;
        } else if (same3(av.rot, bv.rot)) {
            identity = true;
        } else {
            delta = qmul(qconj(from_euler_deg(bv.rot)), from_euler_deg(av.rot));
            double axis[3];
            identity = qangle_axis(delta, axis) < IDENTITY_EPS;
        }
        if (identity) {
            d.rot[0] = d.rot[1] = d.rot[2] = 0.f;
        } else {
            to_euler_deg(delta, d.rot);
        }
        // Mesh space: every joint carries its delta (a child of a turned joint inherits the turn through
        // its own mesh-space delta); local: what either pose drives.
        out.driven[i] = mesh ? 1 : static_cast<uint8_t>(a.driven[i] | b.driven[i]);
    }
    PoseBuf& d = p.slots[static_cast<size_t>(dst)];
    d.v = out.v;
    d.driven = out.driven;
}

float joint_weight(float weight, const cr_mask* mask, size_t i) {
    float w = mask != nullptr ? weight * mask->w[i] : weight;
    return std::min(w, 1.f);
}

void op_layer_additive(cr_poses& p, int32_t dst, const PoseBuf& delta, float weight, const cr_mask* mask,
                       uint32_t flags) {
    const cr_rig& rig = *p.rig;
    const size_t n = joint_count(p);
    PoseBuf& out = p.slots[static_cast<size_t>(dst)];
    const bool mesh = (flags & CR_POSE_MESH_SPACE) != 0;
    // Translation and scale are local in both spaces.
    for (size_t i = 0; i < n; ++i) {
        if (!delta.driven[i]) continue;
        const float w = joint_weight(weight, mask, i);
        if (w <= 0.f) continue;
        const Pose& d = delta.v[i];
        Pose& o = out.v[i];
        for (int c = 0; c < 3; ++c) {
            if (d.pos[c] != 0.f) o.pos[c] = o.pos[c] + d.pos[c] * w;
            if (d.scale[c] != 1.f) o.scale[c] = o.scale[c] * (1.f + (d.scale[c] - 1.f) * w);
        }
        if (!mesh && !zero3(d.rot)) {
            const Quat step = w >= 1.f ? from_euler_deg(d.rot) : qslerp(Quat{}, from_euler_deg(d.rot), w);
            to_euler_deg(qmul(from_euler_deg(o.rot), step), o.rot);
        }
        out.driven[i] = 1;
    }
    if (!mesh) return;
    // Mesh space: Q'(j) = slerp(I, D(j), w)·Q(j); local'(j) = Q'(parent)^-1·Q'(j), only where the joint or an
    // ancestor changed (everything else keeps its exact bits).
    mesh_rotations(rig, out, p.qa);  // Q
    p.changed.assign(n, 0);
    for (int32_t idx : rig.order) {
        const size_t i = static_cast<size_t>(idx);
        const int32_t parent = rig.joints[i].parent;
        const bool parent_changed = parent >= 0 && p.changed[static_cast<size_t>(parent)];
        bool self = false;
        Quat qnew = p.qa[i];
        if (delta.driven[i] && !zero3(delta.v[i].rot)) {
            const float w = joint_weight(weight, mask, i);
            if (w > 0.f) {
                const Quat d = from_euler_deg(delta.v[i].rot);
                qnew = qnormalize(qmul(w >= 1.f ? d : qslerp(Quat{}, d, w), p.qa[i]));
                self = true;
            }
        }
        p.qb[i] = qnew;  // Q'
        if (self || parent_changed) {
            const Quat local = parent >= 0 ? qmul(qconj(p.qb[static_cast<size_t>(parent)]), qnew) : qnew;
            to_euler_deg(local, out.v[i].rot);
            p.changed[i] = 1;
            out.driven[i] = 1;
        }
    }
}

// ---- inertialization -------------------------------------------------------------------------------

void inertia_start(cr_inertia& in, const PoseBuf& target) {
    const size_t n = in.prev.size();
    const bool velocity = in.recorded >= 2 && in.prev_dt > 0.f;
    const double inv_dt = velocity ? 1.0 / in.prev_dt : 0.0;
    const double blend = in.duration;
    bool any = false;
    for (size_t i = 0; i < n; ++i) {
        JointInertia& j = in.joints[i];
        j = JointInertia{};
        const Pose& prev = in.prev[i];
        const Pose& prev2 = in.prev2[i];
        const Pose& g = target.v[i];
        // Translation.
        double d[3], len2 = 0.0;
        for (int c = 0; c < 3; ++c) {
            d[c] = static_cast<double>(prev.pos[c]) - g.pos[c];
            len2 += d[c] * d[c];
        }
        if (len2 > 0.0) {
            const double len = std::sqrt(len2);
            double v = 0.0;
            for (int c = 0; c < 3; ++c) {
                j.trans_axis[c] = d[c] / len;
                if (velocity) v += (static_cast<double>(prev.pos[c]) - prev2.pos[c]) * inv_dt * j.trans_axis[c];
            }
            j.trans.fit(len, v, blend);
        }
        // Rotation: offset q_prev·q_g^-1 as angle about an axis; velocity = the last step's rotation.
        if (!same3(prev.rot, g.rot)) {
            const Quat qp = from_euler_deg(prev.rot);
            const double angle = qangle_axis(qmul(qp, qconj(from_euler_deg(g.rot))), j.rot_axis);
            if (angle >= IDENTITY_EPS) {
                double v = 0.0;
                if (velocity && !same3(prev.rot, prev2.rot)) {
                    double w_axis[3];
                    const double w_angle = qangle_axis(qmul(qp, qconj(from_euler_deg(prev2.rot))), w_axis);
                    for (int c = 0; c < 3; ++c) v += w_axis[c] * w_angle * inv_dt * j.rot_axis[c];
                }
                j.rot.fit(angle, v, blend);
            }
        }
        // Scale, per component.
        for (int c = 0; c < 3; ++c) {
            const double ds = static_cast<double>(prev.scale[c]) - g.scale[c];
            if (ds == 0.0) continue;
            j.scale_sign[c] = ds > 0.0 ? 1.0 : -1.0;
            const double v = velocity ? (static_cast<double>(prev.scale[c]) - prev2.scale[c]) * inv_dt * j.scale_sign[c] : 0.0;
            j.scale[c].fit(std::fabs(ds), v, blend);
        }
        const bool live = j.trans.live() || j.rot.live() || j.scale[0].live() || j.scale[1].live() || j.scale[2].live();
        in.moving[i] = live ? 1 : 0;
        any |= live;
    }
    in.active = any;
    in.elapsed = 0.0;
}

void inertia_apply(cr_inertia& in, PoseBuf& pose, float dt) {
    if (in.pending) {
        in.pending = false;
        in.active = false;
        if (in.recorded >= 1 && in.duration > 0.0) inertia_start(in, pose);
    }
    if (in.active) {
        in.elapsed += dt;
        if (in.elapsed >= in.duration) {
            in.active = false;
        } else {
            const double t = in.elapsed;
            const size_t n = in.prev.size();
            for (size_t i = 0; i < n; ++i) {
                if (!in.moving[i]) continue;
                const JointInertia& j = in.joints[i];
                Pose& o = pose.v[i];
                if (j.trans.live()) {
                    const double x = j.trans.at(t);
                    for (int c = 0; c < 3; ++c) o.pos[c] = static_cast<float>(o.pos[c] + j.trans_axis[c] * x);
                }
                if (j.rot.live()) {
                    const double x = j.rot.at(t);
                    if (x != 0.0) {
                        const Quat off = qaxis(j.rot_axis[0], j.rot_axis[1], j.rot_axis[2], x);
                        to_euler_deg(qmul(off, from_euler_deg(o.rot)), o.rot);
                    }
                }
                for (int c = 0; c < 3; ++c) {
                    if (j.scale[c].live()) o.scale[c] = static_cast<float>(o.scale[c] + j.scale_sign[c] * j.scale[c].at(t));
                }
            }
        }
    }
    in.prev2.swap(in.prev);
    in.prev = pose.v;  // same size: no allocation
    in.prev_dt = dt;
    in.recorded = std::min(in.recorded + 1, 2);
}

// ---- root motion -----------------------------------------------------------------------------------

// A planar step: translation in the frame at its start (yaw removed), then a turn.
struct Step {
    double t[3]{};
    double yaw = 0.0;  // degrees

    // this then s.
    void then(const Step& s) {
        const double r = yaw / RAD_TO_DEG;
        const double c = std::cos(r), sn = std::sin(r);
        // Ry(yaw)·s.t: x' = c x + s z, z' = -s x + c z.
        t[0] += c * s.t[0] + sn * s.t[2];
        t[1] += s.t[1];
        t[2] += -sn * s.t[0] + c * s.t[2];
        yaw += s.yaw;
    }
};

// The root's step from time ta to tb (wrapped clip times) of one track.
Step root_step(const cr_clip& clip, int32_t track, uint32_t axes, float ta, float tb) {
    Pose a, b;
    sample_track(clip, track, ta, a);
    sample_track(clip, track, tb, b);
    Step s;
    double d[3];
    for (int c = 0; c < 3; ++c) d[c] = (axes & (1u << c)) ? static_cast<double>(b.pos[c]) - a.pos[c] : 0.0;
    double ya = 0.0;
    if (axes & CR_ROOT_YAW) {
        ya = heading_deg(a.rot);
        s.yaw = angle_delta_deg(ya, heading_deg(b.rot));
    }
    // Into the frame at the start: Ry(-ya)·d.
    const double r = -ya / RAD_TO_DEG;
    const double c = std::cos(r), sn = std::sin(r);
    s.t[0] = c * d[0] + sn * d[2];
    s.t[1] = d[1];
    s.t[2] = -sn * d[0] + c * d[2];
    return s;
}

bool bad_slot(const cr_poses& p, int32_t s) { return s < 0 || s >= static_cast<int32_t>(p.slots.size()); }

int32_t op_fail(int32_t code, int32_t index, const char* why) {
    set_error("cr_poses_run: op " + std::to_string(index) + ": " + why);
    return code;
}

int32_t run_ops(cr_poses& p, const cr_pose_op* ops, int32_t count) {
    const cr_rig& rig = *p.rig;
    const size_t n = rig.joints.size();
    for (int32_t k = 0; k < count; ++k) {
        const cr_pose_op& op = ops[k];
        if (bad_slot(p, op.dst)) return op_fail(CR_ERR_RANGE, k, "destination slot out of range");
        PoseBuf& dst = p.slots[static_cast<size_t>(op.dst)];
        switch (op.op) {
        case CR_OP_REST:
            pose_rest(rig, dst);
            break;
        case CR_OP_SAMPLE:
            if (op.object == nullptr) return op_fail(CR_ERR_ARG, k, "SAMPLE needs a clip");
            pose_sample(rig, *static_cast<const cr_clip*>(op.object), op.time, (op.flags & CR_POSE_CLIP_MASK) != 0,
                        dst, p.binding);
            if ((op.flags & CR_POSE_DRIVE_ALL) != 0) std::fill(dst.driven.begin(), dst.driven.end(), uint8_t{1});
            break;
        case CR_OP_COPY:
            if (bad_slot(p, op.a)) return op_fail(CR_ERR_RANGE, k, "COPY source slot out of range");
            if (op.a != op.dst) {
                dst.v = p.slots[static_cast<size_t>(op.a)].v;
                dst.driven = p.slots[static_cast<size_t>(op.a)].driven;
            }
            break;
        case CR_OP_BLEND:
            if (op.b < 1 || bad_slot(p, op.a) || bad_slot(p, op.a + op.b - 1)) {
                return op_fail(CR_ERR_RANGE, k, "BLEND slots out of range");
            }
            if (op.object == nullptr) return op_fail(CR_ERR_ARG, k, "BLEND needs weights");
            op_blend(p, op.dst, op.a, op.b, static_cast<const float*>(op.object), op.flags);
            break;
        case CR_OP_LAYER: {
            if (bad_slot(p, op.a)) return op_fail(CR_ERR_RANGE, k, "LAYER source slot out of range");
            const cr_mask* mask = static_cast<const cr_mask*>(op.object);
            if (mask != nullptr && mask->w.size() != n) return op_fail(CR_ERR_ARG, k, "mask of another rig");
            if (op.mode == CR_BLEND_OVERRIDE) {
                const PoseBuf* src = &p.slots[static_cast<size_t>(op.a)];
                if (op.a == op.dst) {
                    if ((op.flags & CR_POSE_NO_PASSTHROUGH) == 0) break;  // a pose over itself changes nothing
                    p.scratch.v = src->v;
                    p.scratch.driven = src->driven;
                    src = &p.scratch;
                }
                pose_layer_override(rig, dst, *src, op.weight, mask != nullptr ? mask->w.data() : nullptr, op.flags);
            } else if (op.mode == CR_BLEND_ADDITIVE) {
                const PoseBuf* src = &p.slots[static_cast<size_t>(op.a)];
                if (op.a == op.dst) {  // the delta must not change under us
                    p.scratch.v = src->v;
                    p.scratch.driven = src->driven;
                    src = &p.scratch;
                }
                op_layer_additive(p, op.dst, *src, op.weight, mask, op.flags);
            } else {
                return op_fail(CR_ERR_ARG, k, "unknown LAYER mode");
            }
            break;
        }
        case CR_OP_MAKE_ADDITIVE:
            if (bad_slot(p, op.a) || bad_slot(p, op.b)) return op_fail(CR_ERR_RANGE, k, "MAKE_ADDITIVE slot out of range");
            op_make_additive(p, op.dst, op.a, op.b, op.flags);
            break;
        case CR_OP_INERTIALIZE: {
            cr_inertia* in = const_cast<cr_inertia*>(static_cast<const cr_inertia*>(op.object));
            if (in == nullptr) return op_fail(CR_ERR_ARG, k, "INERTIALIZE needs an inertializer");
            if (in->prev.size() != n) return op_fail(CR_ERR_ARG, k, "inertializer of another rig");
            inertia_apply(*in, dst, op.param);
            break;
        }
        case CR_OP_REMOVE_ROOT: {
            const cr_clip* clip = static_cast<const cr_clip*>(op.object);
            if (clip == nullptr) return op_fail(CR_ERR_ARG, k, "REMOVE_ROOT needs a clip");
            if (op.b < 0 || op.b >= static_cast<int32_t>(n)) return op_fail(CR_ERR_RANGE, k, "joint out of range");
            binding_into(rig, *clip, p.binding);
            const int32_t track = p.binding.track[static_cast<size_t>(op.b)];
            if (track < 0) break;
            Pose anchor;
            sample_track(*clip, track, 0.f, anchor);
            Pose& o = dst.v[static_cast<size_t>(op.b)];
            for (int c = 0; c < 3; ++c) {
                if (op.axes & (1u << c)) o.pos[c] = anchor.pos[c];
            }
            if (op.axes & CR_ROOT_YAW) {
                const double turn = angle_delta_deg(heading_deg(anchor.rot), heading_deg(o.rot));
                if (turn != 0.0) {
                    to_euler_deg(qmul(qaxis(0, 1, 0, -turn / RAD_TO_DEG), from_euler_deg(o.rot)), o.rot);
                }
            }
            break;
        }
        case CR_OP_MODIFY: {
            cr_modstack* stack = const_cast<cr_modstack*>(static_cast<const cr_modstack*>(op.object));
            if (stack == nullptr) return op_fail(CR_ERR_ARG, k, "MODIFY needs a modifier stack");
            const int32_t rc = modstack_apply(*stack, dst, op.param);
            if (rc != CR_OK) return op_fail(rc, k, cr_last_error());
            break;
        }
        default:
            return op_fail(CR_ERR_ARG, k, "unknown op");
        }
    }
    return CR_OK;
}

}  // namespace

extern "C" {

cr_poses* cr_poses_create(const cr_rig* rig, int32_t slot_count) {
    if (rig == nullptr || slot_count < 1 || slot_count > MAX_SLOTS) {
        set_error("cr_poses_create: need a rig and 1.." + std::to_string(MAX_SLOTS) + " slots");
        return nullptr;
    }
    cr_poses* p = new (std::nothrow) cr_poses();
    if (p == nullptr) {
        set_error("cr_poses_create: out of memory");
        return nullptr;
    }
    p->rig = rig;
    const size_t n = rig->joints.size();
    p->slots.resize(static_cast<size_t>(slot_count));
    for (PoseBuf& s : p->slots) pose_rest(*rig, s);
    pose_rest(*rig, p->scratch);
    p->binding.track.reserve(n);
    p->binding.masked.reserve(n);
    p->qa.resize(n);
    p->qb.resize(n);
    p->changed.reserve(n);
    return p;
}

void cr_poses_destroy(cr_poses* poses) { delete poses; }

int32_t cr_poses_slot_count(const cr_poses* poses) {
    return poses == nullptr ? 0 : static_cast<int32_t>(poses->slots.size());
}

int32_t cr_poses_get(const cr_poses* poses, int32_t slot, float* out_local, uint8_t* out_driven) {
    if (poses == nullptr || bad_slot(*poses, slot)) {
        set_error("cr_poses_get: bad poses/slot");
        return CR_ERR_ARG;
    }
    const PoseBuf& s = poses->slots[static_cast<size_t>(slot)];
    for (size_t i = 0; i < s.v.size(); ++i) {
        if (out_local != nullptr) {
            std::memcpy(out_local + i * 9, s.v[i].pos, 12);
            std::memcpy(out_local + i * 9 + 3, s.v[i].rot, 12);
            std::memcpy(out_local + i * 9 + 6, s.v[i].scale, 12);
        }
        if (out_driven != nullptr) out_driven[i] = s.driven[i];
    }
    return CR_OK;
}

int32_t cr_poses_set(cr_poses* poses, int32_t slot, const float* local, const uint8_t* driven) {
    if (poses == nullptr || local == nullptr || bad_slot(*poses, slot)) {
        set_error("cr_poses_set: bad poses/slot/values");
        return CR_ERR_ARG;
    }
    PoseBuf& s = poses->slots[static_cast<size_t>(slot)];
    for (size_t i = 0; i < s.v.size(); ++i) {
        std::memcpy(s.v[i].pos, local + i * 9, 12);
        std::memcpy(s.v[i].rot, local + i * 9 + 3, 12);
        std::memcpy(s.v[i].scale, local + i * 9 + 6, 12);
        s.driven[i] = driven == nullptr ? 1 : (driven[i] != 0 ? 1 : 0);
    }
    return CR_OK;
}

int32_t cr_poses_run(cr_poses* poses, const cr_pose_op* ops, int32_t op_count) {
    if (poses == nullptr || op_count < 0 || (op_count > 0 && ops == nullptr)) {
        set_error("cr_poses_run: bad arguments");
        return CR_ERR_ARG;
    }
    return run_ops(*poses, ops, op_count);
}

int32_t cr_poses_compose(const cr_poses* poses, int32_t slot, const cr_joint_extra* extras, int32_t extra_count,
                         const float* base_matrix, float* out_skin, float* out_world, float* out_child) {
    if (poses == nullptr || bad_slot(*poses, slot) || extra_count < 0 || (extra_count > 0 && extras == nullptr)) {
        set_error("cr_poses_compose: bad arguments");
        return CR_ERR_ARG;
    }
    return compose_pose(poses->rig, poses->slots[static_cast<size_t>(slot)].v, extras, extra_count, base_matrix,
                        out_skin, out_world, out_child, "cr_poses_compose");
}

int32_t cr_poses_evaluate(cr_poses* poses, const cr_pose_op* ops, int32_t op_count, int32_t slot,
                          const cr_joint_extra* extras, int32_t extra_count, const float* base_matrix,
                          float* out_skin, float* out_world, float* out_child) {
    const int32_t rc = cr_poses_run(poses, ops, op_count);
    if (rc != CR_OK) return rc;
    return cr_poses_compose(poses, slot, extras, extra_count, base_matrix, out_skin, out_world, out_child);
}

// ---- masks -----------------------------------------------------------------------------------------

cr_mask* cr_mask_create(const cr_rig* rig, float fill) {
    if (rig == nullptr) {
        set_error("cr_mask_create: null rig");
        return nullptr;
    }
    cr_mask* m = new (std::nothrow) cr_mask();
    if (m == nullptr) {
        set_error("cr_mask_create: out of memory");
        return nullptr;
    }
    m->rig = rig;
    m->w.assign(rig->joints.size(), std::clamp(fill, 0.f, 1.f));
    m->depth.assign(rig->joints.size(), -1);
    return m;
}

void cr_mask_destroy(cr_mask* mask) { delete mask; }

int32_t cr_mask_joint_count(const cr_mask* mask) { return mask == nullptr ? 0 : static_cast<int32_t>(mask->w.size()); }

int32_t cr_mask_fill(cr_mask* mask, float weight) {
    if (mask == nullptr) {
        set_error("cr_mask_fill: null mask");
        return CR_ERR_ARG;
    }
    std::fill(mask->w.begin(), mask->w.end(), std::clamp(weight, 0.f, 1.f));
    return CR_OK;
}

int32_t cr_mask_set(cr_mask* mask, int32_t joint, float weight, uint32_t flags, int32_t blend_depth) {
    if (mask == nullptr || joint < 0 || joint >= static_cast<int32_t>(mask->w.size())) {
        set_error("cr_mask_set: bad mask/joint");
        return mask == nullptr ? CR_ERR_ARG : CR_ERR_RANGE;
    }
    const float w = std::clamp(weight, 0.f, 1.f);
    if ((flags & CR_MASK_BRANCH) == 0) {
        mask->w[static_cast<size_t>(joint)] = w;
        return CR_OK;
    }
    // Depth below `joint` for every joint of its branch: parents come first in rig order.
    const cr_rig& rig = *mask->rig;
    std::fill(mask->depth.begin(), mask->depth.end(), -1);
    mask->depth[static_cast<size_t>(joint)] = 0;
    for (int32_t idx : rig.order) {
        const size_t i = static_cast<size_t>(idx);
        if (idx == joint) continue;
        const int32_t parent = rig.joints[i].parent;
        if (parent >= 0 && mask->depth[static_cast<size_t>(parent)] >= 0) {
            mask->depth[i] = mask->depth[static_cast<size_t>(parent)] + 1;
        }
    }
    for (size_t i = 0; i < mask->w.size(); ++i) {
        const int32_t d = mask->depth[i];
        if (d < 0) continue;
        float ramp = 1.f;
        if (blend_depth > 0) ramp = std::min(1.f, static_cast<float>(d + 1) / static_cast<float>(blend_depth));
        mask->w[i] = w * ramp;
    }
    return CR_OK;
}

int32_t cr_mask_get(const cr_mask* mask, float* out_weights) {
    if (mask == nullptr || out_weights == nullptr) {
        set_error("cr_mask_get: bad arguments");
        return CR_ERR_ARG;
    }
    std::memcpy(out_weights, mask->w.data(), mask->w.size() * sizeof(float));
    return CR_OK;
}

int32_t cr_mask_set_all(cr_mask* mask, const float* weights) {
    if (mask == nullptr || weights == nullptr) {
        set_error("cr_mask_set_all: bad arguments");
        return CR_ERR_ARG;
    }
    for (size_t i = 0; i < mask->w.size(); ++i) mask->w[i] = std::clamp(weights[i], 0.f, 1.f);
    return CR_OK;
}

int32_t cr_mask_from_clip(cr_mask* mask, const cr_clip* clip) {
    if (mask == nullptr || clip == nullptr) {
        set_error("cr_mask_from_clip: bad arguments");
        return CR_ERR_ARG;
    }
    const Binding b = binding_for(*mask->rig, *clip);
    for (size_t i = 0; i < mask->w.size(); ++i) mask->w[i] = b.masked[i] ? 1.f : 0.f;
    return CR_OK;
}

// ---- inertialization -------------------------------------------------------------------------------

cr_inertia* cr_inertia_create(const cr_rig* rig) {
    if (rig == nullptr) {
        set_error("cr_inertia_create: null rig");
        return nullptr;
    }
    cr_inertia* in = new (std::nothrow) cr_inertia();
    if (in == nullptr) {
        set_error("cr_inertia_create: out of memory");
        return nullptr;
    }
    const size_t n = rig->joints.size();
    in->rig = rig;
    in->prev.resize(n);
    in->prev2.resize(n);
    in->joints.resize(n);
    in->moving.assign(n, 0);
    return in;
}

void cr_inertia_destroy(cr_inertia* inertia) { delete inertia; }

int32_t cr_inertia_request(cr_inertia* inertia, float blend_time) {
    if (inertia == nullptr || !std::isfinite(blend_time)) {
        set_error("cr_inertia_request: bad arguments");
        return CR_ERR_ARG;
    }
    inertia->pending = true;
    inertia->duration = blend_time > 0.f ? blend_time : 0.0;
    return CR_OK;
}

int32_t cr_inertia_reset(cr_inertia* inertia) {
    if (inertia == nullptr) {
        set_error("cr_inertia_reset: null inertializer");
        return CR_ERR_ARG;
    }
    inertia->recorded = 0;
    inertia->pending = false;
    inertia->active = false;
    inertia->elapsed = 0.0;
    return CR_OK;
}

int32_t cr_inertia_active(const cr_inertia* inertia) {
    return inertia != nullptr && (inertia->active || inertia->pending) ? 1 : 0;
}

// ---- modifiers ---------------------------------------------------------------------------------------

int32_t cr_modstack_apply(cr_modstack* stack, cr_poses* poses, int32_t slot, float dt) {
    if (stack == nullptr || poses == nullptr || bad_slot(*poses, slot) || !std::isfinite(dt) || dt < 0.f) {
        set_error("cr_modstack_apply: bad arguments");
        return CR_ERR_ARG;
    }
    return modstack_apply(*stack, poses->slots[static_cast<size_t>(slot)], dt);
}

// ---- root motion -----------------------------------------------------------------------------------

int32_t cr_clip_root_motion(const cr_clip* clip, const cr_rig* rig, int32_t joint, uint32_t axes, float prev_time,
                            float delta, cr_root_motion* out) {
    if (clip == nullptr || rig == nullptr || out == nullptr || !std::isfinite(prev_time) || !std::isfinite(delta)) {
        set_error("cr_clip_root_motion: bad arguments");
        return CR_ERR_ARG;
    }
    if (joint < 0 || joint >= static_cast<int32_t>(rig->joints.size())) {
        set_error("cr_clip_root_motion: joint out of range");
        return CR_ERR_RANGE;
    }
    *out = cr_root_motion{};
    const int32_t track = binding_for(*rig, *clip).track[static_cast<size_t>(joint)];
    const float d = clip->desc.duration;
    if (track < 0 || d <= 0.f || delta == 0.f || axes == 0) return CR_OK;
    Step total;
    if (clip->desc.loop == 0) {
        const float t0 = wrap_time(prev_time, d, 0);
        const float t1 = wrap_time(prev_time + delta, d, 0);
        total = root_step(*clip, track, axes, t0, t1);
    } else {
        // Whole cycles, then the remainder piecewise around the wrap.
        const float t0 = wrap_time(prev_time, d, 1);
        const double span = std::fabs(static_cast<double>(delta));
        const double cycles_d = std::floor(span / d);
        const int64_t cycles = static_cast<int64_t>(std::min(cycles_d, 100000.0));
        const float rest = static_cast<float>(span - cycles_d * d);
        const bool forward = delta > 0.f;
        Step cycle = root_step(*clip, track, axes, 0.f, d);
        if (!forward) cycle = root_step(*clip, track, axes, d, 0.f);
        for (int64_t c = 0; c < cycles; ++c) total.then(cycle);
        if (rest > 0.f) {
            if (forward) {
                const float end = t0 + rest;
                if (end <= d) {
                    total.then(root_step(*clip, track, axes, t0, end));
                } else {
                    total.then(root_step(*clip, track, axes, t0, d));
                    total.then(root_step(*clip, track, axes, 0.f, end - d));
                }
            } else {
                const float end = t0 - rest;
                if (end >= 0.f) {
                    total.then(root_step(*clip, track, axes, t0, end));
                } else {
                    total.then(root_step(*clip, track, axes, t0, 0.f));
                    total.then(root_step(*clip, track, axes, d, end + d));
                }
            }
        }
    }
    for (int c = 0; c < 3; ++c) out->translation[c] = static_cast<float>(total.t[c]);
    out->yaw_deg = static_cast<float>(total.yaw);
    return CR_OK;
}

}  // extern "C"
