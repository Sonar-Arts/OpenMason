// First-person view (OpenMason #161): hiding joints in a palette by zero scale, the first-person projection
// (own FOV + depth slab + lighting pull) and the camera's head-bob stabilizer. See rig.h "First-person view".

#include "rig_internal.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <new>
#include <vector>

using cenda::rig::Mat4;
using cenda::rig::set_error;

struct cr_stabilizer {
    double threshold = 0.02;
    double max_offset = 0.1;
    double time_constant = 0.3;
    double snap = 1.0;
    bool started = false;
    double pos[3]{};  // filtered point, model space
    double vel[3]{};
};

namespace {

bool finite_all(const float* v, int n) {
    for (int i = 0; i < n; ++i) {
        if (!std::isfinite(v[i])) return false;
    }
    return true;
}

// The point a joint stands for in a world matrix: a part's pivot, a bone's head.
void joint_point(const cenda::rig::Joint& joint, const float* m, double out[3]) {
    double p[3] = {0.0, 0.0, 0.0};
    if (joint.kind == CR_JOINT_PART) {
        p[0] = joint.origin[0];
        p[1] = joint.origin[1];
        p[2] = joint.origin[2];
    }
    for (int r = 0; r < 3; ++r) {
        out[r] = static_cast<double>(m[r]) * p[0] + static_cast<double>(m[4 + r]) * p[1] +
                 static_cast<double>(m[8 + r]) * p[2] + static_cast<double>(m[12 + r]);
    }
}

}  // namespace

extern "C" {

int32_t cr_palette_hide(const cr_rig* rig, const cr_mask* visible, const float* world, float* skin) {
    if (rig == nullptr || visible == nullptr || world == nullptr || skin == nullptr) {
        set_error("cr_palette_hide: bad arguments");
        return CR_ERR_ARG;
    }
    const size_t n = rig->joints.size();
    if (static_cast<size_t>(cr_mask_joint_count(visible)) != n) {
        set_error("cr_palette_hide: the mask belongs to a rig with another joint count");
        return CR_ERR_ARG;
    }
    thread_local std::vector<float> w;
    thread_local std::vector<int32_t> root;
    w.resize(n);
    root.resize(n);
    cr_mask_get(visible, w.data());
    for (int32_t idx : rig->order) {
        const size_t i = static_cast<size_t>(idx);
        const int32_t parent = rig->joints[i].parent;
        root[i] = (parent < 0 || w[static_cast<size_t>(parent)] >= 1.f) ? idx : root[static_cast<size_t>(parent)];
    }
    for (size_t i = 0; i < n; ++i) {
        const double v = w[i];
        if (v >= 1.0) continue;
        const size_t r = static_cast<size_t>(root[i]);
        double p[3];
        joint_point(rig->joints[r], world + r * 16, p);
        // K' = T(p)·S(v)·T(-p)·K: rows 0..2 become v·row + (1 - v)·p·row3.
        float* k = skin + i * 16;
        for (int c = 0; c < 4; ++c) {
            const double row3 = k[c * 4 + 3];
            for (int rr = 0; rr < 3; ++rr) {
                k[c * 4 + rr] = static_cast<float>(v * k[c * 4 + rr] + (1.0 - v) * p[rr] * row3);
            }
        }
    }
    return CR_OK;
}

int32_t cr_first_person_projection(const float* view, const float* world_projection, const cr_first_person_desc* desc,
                                   float* out_projection, float* out_pull) {
    if (view == nullptr || world_projection == nullptr || !finite_all(view, 16) || !finite_all(world_projection, 16)) {
        set_error("cr_first_person_projection: bad view or projection");
        return CR_ERR_ARG;
    }
    const float* P = world_projection;
    // An OpenGL perspective: w = -z (view depth), no x/y/w terms from w.
    if (std::fabs(P[11] + 1.f) > 1e-6f || std::fabs(P[15]) > 1e-6f || P[3] != 0.f || P[7] != 0.f || P[0] == 0.f ||
        P[5] == 0.f) {
        set_error("cr_first_person_projection: the world projection is not an OpenGL perspective");
        return CR_ERR_ARG;
    }
    const double m10 = P[10], m14 = P[14];
    const double world_near = m14 / (m10 - 1.0);
    if (!(world_near > 0.0) || !std::isfinite(world_near)) {
        set_error("cr_first_person_projection: the world projection has no positive near plane");
        return CR_ERR_ARG;
    }
    cr_first_person_desc d{};
    if (desc != nullptr) d = *desc;
    const double fov = d.fov_y_deg > 0.f ? d.fov_y_deg : 0.0;
    const double fp_near = d.near > 0.f ? d.near : 0.01;
    const double fp_far = d.far > 0.f ? d.far : 2.0;
    const double slab = d.depth_scale > 0.f ? d.depth_scale : 0.5;
    const double pull = d.pull > 0.f ? d.pull : 0.2;
    if (fov >= 180.0 || !(fp_far > fp_near) || pull > 1.0 || !std::isfinite(fp_far) || !std::isfinite(slab)) {
        set_error("cr_first_person_projection: bad desc (fov < 180, far > near, 0 < pull <= 1)");
        return CR_ERR_ARG;
    }

    if (out_projection != nullptr) {
        double fp[16];
        for (int i = 0; i < 16; ++i) fp[i] = P[i];
        if (fov > 0.0) {
            // x and y scaled about the projection axis: the first-person FOV with the world's aspect and
            // off-centre (jitter) terms.
            const double c = (1.0 / std::tan(fov * cenda::rig::DEG_TO_RAD * 0.5)) / static_cast<double>(P[5]);
            for (int r = 0; r < 4; ++r) {
                fp[0 * 4 + r] *= c;
                fp[1 * 4 + r] *= c;
            }
        }
        // Depth: pulled view depth d in [pull·near, pull·far] -> d' in [n, n·(1 + slab)] with 1/d' = a/d + b,
        // so z_ndc stays affine in 1/d (perspective-correct) and ordered.
        const double dn = pull * fp_near, df = pull * fp_far;
        const double tn = world_near, tf = world_near * (1.0 + slab);
        const double alpha = (1.0 / tn - 1.0 / tf) / (1.0 / dn - 1.0 / df);
        const double beta = 1.0 / tn - alpha / dn;
        // World: z_ndc = -m10 + m14 / d'. Substituted: z_ndc = (-m10 + m14·beta) + (m14·alpha) / d.
        fp[10] = m10 - m14 * beta;
        fp[14] = m14 * alpha;
        for (int i = 0; i < 16; ++i) out_projection[i] = static_cast<float>(fp[i]);
    }
    if (out_pull != nullptr) {
        const Mat4 inv = Mat4::from_float(view).affine_inverse();
        const double e[3] = {inv.at(0, 3), inv.at(1, 3), inv.at(2, 3)};
        Mat4 m = Mat4::identity();
        for (int r = 0; r < 3; ++r) {
            m.at(r, r) = pull;
            m.at(r, 3) = (1.0 - pull) * e[r];
        }
        m.to_float(out_pull);
    }
    return CR_OK;
}

cr_stabilizer* cr_stabilizer_create(const cr_stabilizer_desc* desc) {
    cr_stabilizer* s = new (std::nothrow) cr_stabilizer();
    if (s == nullptr) {
        set_error("cr_stabilizer_create: out of memory");
        return nullptr;
    }
    if (desc != nullptr) {
        if (desc->threshold > 0.f) s->threshold = desc->threshold;
        if (desc->max_offset > 0.f) s->max_offset = desc->max_offset;
        if (desc->time_constant > 0.f) s->time_constant = desc->time_constant;
        if (desc->snap_distance > 0.f) s->snap = desc->snap_distance;
    }
    s->max_offset = std::max(s->max_offset, s->threshold);
    return s;
}

void cr_stabilizer_destroy(cr_stabilizer* s) { delete s; }

int32_t cr_stabilizer_reset(cr_stabilizer* s) {
    if (s == nullptr) {
        set_error("cr_stabilizer_reset: null stabilizer");
        return CR_ERR_ARG;
    }
    s->started = false;
    return CR_OK;
}

int32_t cr_stabilizer_update(cr_stabilizer* s, const float* point, const float* base, float dt, float* out) {
    if (s == nullptr || point == nullptr || out == nullptr || !finite_all(point, 3) ||
        (base != nullptr && !finite_all(base, 16)) || !std::isfinite(dt)) {
        set_error("cr_stabilizer_update: bad arguments");
        return CR_ERR_ARG;
    }
    const Mat4 b = base != nullptr ? Mat4::from_float(base) : Mat4::identity();
    const double world[3] = {point[0], point[1], point[2]};
    double raw[3];
    b.affine_inverse().transform_point(world, raw);

    double off[3] = {raw[0] - s->pos[0], raw[1] - s->pos[1], raw[2] - s->pos[2]};
    double dist = std::sqrt(off[0] * off[0] + off[1] * off[1] + off[2] * off[2]);
    if (!s->started || dist > s->snap) {
        for (int c = 0; c < 3; ++c) {
            s->pos[c] = raw[c];
            s->vel[c] = 0.0;
        }
        s->started = true;
    } else if (dt > 0.f) {
        // Exact critically damped step toward raw: x(t) = raw + (c1 + c2 t) e^(-w t).
        const double w = 2.0 / s->time_constant;
        const double t = dt;
        const double e = std::exp(-w * t);
        for (int c = 0; c < 3; ++c) {
            const double c1 = s->pos[c] - raw[c];
            const double c2 = s->vel[c] + w * c1;
            s->pos[c] = raw[c] + (c1 + c2 * t) * e;
            s->vel[c] = (c2 - w * (c1 + c2 * t)) * e;
        }
    }
    for (int c = 0; c < 3; ++c) off[c] = raw[c] - s->pos[c];
    dist = std::sqrt(off[0] * off[0] + off[1] * off[1] + off[2] * off[2]);
    if (dist > s->max_offset) {
        // Intended motion: drag the filter so it trails by max_offset at most.
        const double k = s->max_offset / dist;
        for (int c = 0; c < 3; ++c) {
            off[c] *= k;
            s->pos[c] = raw[c] - off[c];
        }
        dist = s->max_offset;
    }
    const double keep = dist > s->threshold ? s->threshold / dist : 1.0;
    double cam[3];
    for (int c = 0; c < 3; ++c) cam[c] = s->pos[c] + off[c] * keep;
    double res[3];
    b.transform_point(cam, res);
    for (int c = 0; c < 3; ++c) out[c] = static_cast<float>(res[c]);
    return CR_OK;
}

}  // extern "C"
