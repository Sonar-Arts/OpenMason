// Small double-precision math for the model importer (import_omo.cpp): vectors, quaternions, column-major affine
// 4x4 matrices, Euler XYZ (rig.h: R = Rx·Ry·Rz, degrees), the octahedral normal encoding of OMO 1.9, key reduction
// and sRGB transfer functions.
#pragma once

#include "cenda/model_shading.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace cenda::model::import_math {

inline constexpr double kPi = 3.14159265358979323846;
inline constexpr double kRadToDeg = 180.0 / kPi;
inline constexpr double kDegToRad = kPi / 180.0;

struct V3 {
    double x = 0.0, y = 0.0, z = 0.0;
};

inline V3 operator+(V3 a, V3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
inline V3 operator-(V3 a, V3 b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
inline V3 operator*(V3 a, double s) { return {a.x * s, a.y * s, a.z * s}; }
inline double dot(V3 a, V3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
inline V3 cross(V3 a, V3 b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
inline double length(V3 a) { return std::sqrt(dot(a, a)); }
inline V3 normalized(V3 a) {
    const double l = length(a);
    return l > 0.0 ? a * (1.0 / l) : V3{};
}

struct Q {
    double x = 0.0, y = 0.0, z = 0.0, w = 1.0;
};

inline Q qnormalized(Q q) {
    const double l = std::sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w);
    if (!(l > 0.0)) return Q{};
    return {q.x / l, q.y / l, q.z / l, q.w / l};
}

inline double qdot(Q a, Q b) { return a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w; }

// Shortest-path slerp (the glTF reference interpolation).
inline Q slerp(Q a, Q b, double u) {
    double d = qdot(a, b);
    if (d < 0.0) {
        b = {-b.x, -b.y, -b.z, -b.w};
        d = -d;
    }
    if (d > 0.9995) {
        return qnormalized({a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u, a.z + (b.z - a.z) * u,
                            a.w + (b.w - a.w) * u});
    }
    const double theta = std::acos(std::min(1.0, d));
    const double s = std::sin(theta);
    const double wa = std::sin((1.0 - u) * theta) / s;
    const double wb = std::sin(u * theta) / s;
    return {a.x * wa + b.x * wb, a.y * wa + b.y * wb, a.z * wa + b.z * wb, a.w * wa + b.w * wb};
}

// Column-major 4x4: m[col * 4 + row].
struct M4 {
    std::array<double, 16> m{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    double& at(int r, int c) { return m[static_cast<std::size_t>(c * 4 + r)]; }
    double at(int r, int c) const { return m[static_cast<std::size_t>(c * 4 + r)]; }

    static M4 from_float(const std::array<float, 16>& f) {
        M4 r;
        for (std::size_t i = 0; i < 16; ++i) r.m[i] = static_cast<double>(f[i]);
        return r;
    }

    V3 col(int c) const { return {at(0, c), at(1, c), at(2, c)}; }
    void set_col(int c, V3 v) {
        at(0, c) = v.x;
        at(1, c) = v.y;
        at(2, c) = v.z;
    }
    V3 translation() const { return col(3); }

    M4 operator*(const M4& b) const {
        M4 r;
        for (int c = 0; c < 4; ++c) {
            for (int rr = 0; rr < 4; ++rr) {
                double s = 0.0;
                for (int k = 0; k < 4; ++k) s += at(rr, k) * b.at(k, c);
                r.at(rr, c) = s;
            }
        }
        return r;
    }

    M4 operator*(double s) const {
        M4 r = *this;
        for (double& v : r.m) v *= s;
        return r;
    }

    M4 operator+(const M4& b) const {
        M4 r = *this;
        for (std::size_t i = 0; i < 16; ++i) r.m[i] += b.m[i];
        return r;
    }

    V3 point(V3 p) const {
        return {at(0, 0) * p.x + at(0, 1) * p.y + at(0, 2) * p.z + at(0, 3),
                at(1, 0) * p.x + at(1, 1) * p.y + at(1, 2) * p.z + at(1, 3),
                at(2, 0) * p.x + at(2, 1) * p.y + at(2, 2) * p.z + at(2, 3)};
    }

    V3 vector(V3 v) const {
        return {at(0, 0) * v.x + at(0, 1) * v.y + at(0, 2) * v.z, at(1, 0) * v.x + at(1, 1) * v.y + at(1, 2) * v.z,
                at(2, 0) * v.x + at(2, 1) * v.y + at(2, 2) * v.z};
    }

    double det3() const {
        return at(0, 0) * (at(1, 1) * at(2, 2) - at(1, 2) * at(2, 1)) -
               at(0, 1) * (at(1, 0) * at(2, 2) - at(1, 2) * at(2, 0)) +
               at(0, 2) * (at(1, 0) * at(2, 1) - at(1, 1) * at(2, 0));
    }

    // Inverse of an affine matrix (singular linear part -> translation-only inverse).
    M4 affine_inverse() const {
        const double a = at(0, 0), b = at(0, 1), c = at(0, 2);
        const double d = at(1, 0), e = at(1, 1), f = at(1, 2);
        const double g = at(2, 0), h = at(2, 1), i = at(2, 2);
        const double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        const double det = a * A + b * B + c * C;
        M4 r;
        if (std::fabs(det) < 1e-300) {
            r.set_col(3, translation() * -1.0);
            return r;
        }
        const double inv = 1.0 / det;
        r.at(0, 0) = A * inv;
        r.at(0, 1) = -(b * i - c * h) * inv;
        r.at(0, 2) = (b * f - c * e) * inv;
        r.at(1, 0) = B * inv;
        r.at(1, 1) = (a * i - c * g) * inv;
        r.at(1, 2) = -(a * f - c * d) * inv;
        r.at(2, 0) = C * inv;
        r.at(2, 1) = -(a * h - b * g) * inv;
        r.at(2, 2) = (a * e - b * d) * inv;
        const V3 t = translation();
        r.set_col(3, r.vector(t) * -1.0);
        return r;
    }

    // The linear part only (translation dropped).
    M4 linear() const {
        M4 r = *this;
        r.set_col(3, {});
        return r;
    }

    // Inverse-transpose of the linear part (the normal matrix).
    M4 normal_matrix() const {
        const M4 inv = linear().affine_inverse();
        M4 r;
        for (int rr = 0; rr < 3; ++rr) {
            for (int c = 0; c < 3; ++c) r.at(rr, c) = inv.at(c, rr);
        }
        return r;
    }
};

inline M4 translation(V3 t) {
    M4 r;
    r.set_col(3, t);
    return r;
}

inline M4 scaling(V3 s) {
    M4 r;
    r.at(0, 0) = s.x;
    r.at(1, 1) = s.y;
    r.at(2, 2) = s.z;
    return r;
}

inline M4 rotation(Q q) {
    q = qnormalized(q);
    const double x = q.x, y = q.y, z = q.z, w = q.w;
    M4 r;
    r.at(0, 0) = 1 - 2 * (y * y + z * z);
    r.at(0, 1) = 2 * (x * y - z * w);
    r.at(0, 2) = 2 * (x * z + y * w);
    r.at(1, 0) = 2 * (x * y + z * w);
    r.at(1, 1) = 1 - 2 * (x * x + z * z);
    r.at(1, 2) = 2 * (y * z - x * w);
    r.at(2, 0) = 2 * (x * z - y * w);
    r.at(2, 1) = 2 * (y * z + x * w);
    r.at(2, 2) = 1 - 2 * (x * x + y * y);
    return r;
}

inline M4 trs(V3 t, Q q, V3 s) { return translation(t) * rotation(q) * scaling(s); }

// Quaternion of a pure rotation matrix (Shepperd).
inline Q quat_of(const M4& r) {
    const double tr = r.at(0, 0) + r.at(1, 1) + r.at(2, 2);
    Q q;
    if (tr > 0.0) {
        const double s = std::sqrt(tr + 1.0) * 2.0;
        q.w = 0.25 * s;
        q.x = (r.at(2, 1) - r.at(1, 2)) / s;
        q.y = (r.at(0, 2) - r.at(2, 0)) / s;
        q.z = (r.at(1, 0) - r.at(0, 1)) / s;
    } else if (r.at(0, 0) > r.at(1, 1) && r.at(0, 0) > r.at(2, 2)) {
        const double s = std::sqrt(1.0 + r.at(0, 0) - r.at(1, 1) - r.at(2, 2)) * 2.0;
        q.w = (r.at(2, 1) - r.at(1, 2)) / s;
        q.x = 0.25 * s;
        q.y = (r.at(0, 1) + r.at(1, 0)) / s;
        q.z = (r.at(0, 2) + r.at(2, 0)) / s;
    } else if (r.at(1, 1) > r.at(2, 2)) {
        const double s = std::sqrt(1.0 + r.at(1, 1) - r.at(0, 0) - r.at(2, 2)) * 2.0;
        q.w = (r.at(0, 2) - r.at(2, 0)) / s;
        q.x = (r.at(0, 1) + r.at(1, 0)) / s;
        q.y = 0.25 * s;
        q.z = (r.at(1, 2) + r.at(2, 1)) / s;
    } else {
        const double s = std::sqrt(1.0 + r.at(2, 2) - r.at(0, 0) - r.at(1, 1)) * 2.0;
        q.w = (r.at(1, 0) - r.at(0, 1)) / s;
        q.x = (r.at(0, 2) + r.at(2, 0)) / s;
        q.y = (r.at(1, 2) + r.at(2, 1)) / s;
        q.z = 0.25 * s;
    }
    return qnormalized(q);
}

// R = Rx(x)·Ry(y)·Rz(z), degrees (rig.h, JOML rotateXYZ).
inline M4 euler_xyz(V3 deg) {
    const double a = deg.x * kDegToRad, b = deg.y * kDegToRad, c = deg.z * kDegToRad;
    const double ca = std::cos(a), sa = std::sin(a), cb = std::cos(b), sb = std::sin(b), cc = std::cos(c),
                 sc = std::sin(c);
    M4 r;
    r.at(0, 0) = cb * cc;
    r.at(0, 1) = -cb * sc;
    r.at(0, 2) = sb;
    r.at(1, 0) = ca * sc + sa * sb * cc;
    r.at(1, 1) = ca * cc - sa * sb * sc;
    r.at(1, 2) = -sa * cb;
    r.at(2, 0) = sa * sc - ca * sb * cc;
    r.at(2, 1) = sa * cc + ca * sb * sc;
    r.at(2, 2) = ca * cb;
    return r;
}

// Euler XYZ degrees of a rotation matrix (inverse of euler_xyz; gimbal lock: z = 0).
inline V3 euler_of(const M4& r) {
    const double s = std::clamp(r.at(0, 2), -1.0, 1.0);
    const double b = std::asin(s);
    double a, c;
    if (std::fabs(s) < 0.9999999) {
        a = std::atan2(-r.at(1, 2), r.at(2, 2));
        c = std::atan2(-r.at(0, 1), r.at(0, 0));
    } else {
        c = 0.0;
        a = std::atan2(r.at(2, 1), r.at(1, 1));
    }
    return {a * kRadToDeg, b * kRadToDeg, c * kRadToDeg};
}

// Wrapped difference b - a into [-180, 180).
inline double angle_delta(double a, double b) {
    double d = std::fmod(b - a, 360.0);
    if (d < -180.0) d += 360.0;
    if (d >= 180.0) d -= 360.0;
    return d;
}

inline double wrap180(double a) { return angle_delta(0.0, a); }

// The Euler triple naming the same rotation (x + 180, 180 - y, z + 180), wrapped.
inline V3 euler_alternate(V3 e) { return {wrap180(e.x + 180.0), wrap180(180.0 - e.y), wrap180(e.z + 180.0)}; }

// Of e and its alternate, the one with the smallest per-component wrapped distance to `near`; expressed as
// near + wrapped deltas (so a per-component shortest-path lerp from `near` walks the short way).
inline V3 euler_near(V3 e, V3 near) {
    const V3 alt = euler_alternate(e);
    auto dist = [&](V3 v) {
        return std::fabs(angle_delta(near.x, v.x)) + std::fabs(angle_delta(near.y, v.y)) +
               std::fabs(angle_delta(near.z, v.z));
    };
    const V3 best = dist(alt) < dist(e) ? alt : e;
    return {wrap180(best.x), wrap180(best.y), wrap180(best.z)};
}

// Octahedral encoding of a unit normal as signed-normalized 16-bit x, y (OMO 1.9 customNormals): THE encoder of
// model_shading.h (co_oct_encode, "precise": best of the 4 floor/ceil roundings), so imported custom normals and
// computed normals share one encoding.
inline std::array<std::int16_t, 2> oct_encode(V3 n) {
    const float xyz[3] = {static_cast<float>(n.x), static_cast<float>(n.y), static_cast<float>(n.z)};
    std::array<std::int16_t, 2> out{0, 0};
    co_oct_encode(xyz, out.data(), 1);
    return out;
}

inline V3 oct_decode(std::int16_t ox, std::int16_t oy) {
    double x = std::max(-1.0, ox / 32767.0), y = std::max(-1.0, oy / 32767.0);
    const double z = 1.0 - std::fabs(x) - std::fabs(y);
    if (z < 0.0) {
        const double fx = (1.0 - std::fabs(y)) * (x >= 0.0 ? 1.0 : -1.0);
        const double fy = (1.0 - std::fabs(x)) * (y >= 0.0 ? 1.0 : -1.0);
        x = fx;
        y = fy;
    }
    return normalized({x, y, z});
}

// Key reduction over n keys (indices 0..n-1): keeps the first and the last key and, from each kept key i, jumps to the
// farthest key j such that fits(i, j) holds (the segment i..j may replace every key between them). The reach is
// found by doubling (j = i+2, i+4, i+8, ...) and then bisecting between the last span that fit and the first that did
// not, so a track costs O(n log L) fits calls of span L. fits(i, i + 1) is never asked (an adjacent pair always
// fits). Deterministic: the same fits answers give the same keys. Returns the kept indices, ascending.
template <class Fits>
std::vector<std::size_t> reduce_keys(std::size_t n, Fits&& fits) {
    std::vector<std::size_t> kept;
    if (n == 0) return kept;
    kept.push_back(0);
    std::size_t i = 0;
    while (i + 1 < n) {
        std::size_t good = i + 1;
        std::size_t bad = n;  // a span end known not to fit (n = none)
        for (std::size_t span = 2;; span *= 2) {
            const std::size_t cand = std::min(i + span, n - 1);
            if (cand <= good) break;
            if (fits(i, cand)) {
                good = cand;
                if (cand == n - 1) break;
            } else {
                bad = cand;
                break;
            }
        }
        while (bad != n && bad - good > 1) {
            const std::size_t mid = good + (bad - good) / 2;
            if (fits(i, mid)) {
                good = mid;
            } else {
                bad = mid;
            }
        }
        kept.push_back(good);
        i = good;
    }
    return kept;
}

// sRGB transfer functions (IEC 61966-2-1), values in [0, 1].
inline double srgb_to_linear(double v) {
    return v <= 0.04045 ? v / 12.92 : std::pow((v + 0.055) / 1.055, 2.4);
}

inline double linear_to_srgb(double v) {
    v = std::clamp(v, 0.0, 1.0);
    return v <= 0.0031308 ? v * 12.92 : 1.055 * std::pow(v, 1.0 / 2.4) - 0.055;
}

}  // namespace cenda::model::import_math
