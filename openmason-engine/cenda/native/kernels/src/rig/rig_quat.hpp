// Cenda rig: quaternion helpers in double (shared by the pose pipeline and the modifier stack). Moved out of
// pose.cpp unchanged (OpenMason #158); the pose pipeline's results depend on these exact operations.
#pragma once

#include "rig_math.hpp"

#include <algorithm>
#include <cmath>

namespace cenda::rig {

inline constexpr double RAD_TO_DEG = 180.0 / 3.14159265358979323846;

// ---- quaternions (w, x, y, z), Hamilton product, column-vector convention -------------------------

struct Quat {
    double w = 1.0, x = 0.0, y = 0.0, z = 0.0;
};

inline Quat qmul(const Quat& a, const Quat& b) {
    return Quat{a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
                a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
                a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
                a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w};
}

inline Quat qconj(const Quat& q) { return Quat{q.w, -q.x, -q.y, -q.z}; }

inline double qdot(const Quat& a, const Quat& b) { return a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z; }

inline Quat qnormalize(const Quat& q) {
    const double len = std::sqrt(qdot(q, q));
    if (len <= 0.0) return Quat{};
    return Quat{q.w / len, q.x / len, q.y / len, q.z / len};
}

inline Quat qaxis(double ax, double ay, double az, double angle) {
    const double s = std::sin(angle * 0.5);
    return Quat{std::cos(angle * 0.5), ax * s, ay * s, az * s};
}

// R = Rx·Ry·Rz (rig.h) -> q = qx·qy·qz.
inline Quat from_euler_deg(const float rot[3]) {
    const Quat qx = qaxis(1, 0, 0, rot[0] * DEG_TO_RAD);
    const Quat qy = qaxis(0, 1, 0, rot[1] * DEG_TO_RAD);
    const Quat qz = qaxis(0, 0, 1, rot[2] * DEG_TO_RAD);
    return qmul(qmul(qx, qy), qz);
}

// Row-major 3x3 of a unit quaternion.
inline void qmatrix(const Quat& q, double r[9]) {
    const double xx = q.x * q.x, yy = q.y * q.y, zz = q.z * q.z;
    const double xy = q.x * q.y, xz = q.x * q.z, yz = q.y * q.z;
    const double wx = q.w * q.x, wy = q.w * q.y, wz = q.w * q.z;
    r[0] = 1 - 2 * (yy + zz); r[1] = 2 * (xy - wz);     r[2] = 2 * (xz + wy);
    r[3] = 2 * (xy + wz);     r[4] = 1 - 2 * (xx + zz); r[5] = 2 * (yz - wx);
    r[6] = 2 * (xz - wy);     r[7] = 2 * (yz + wx);     r[8] = 1 - 2 * (xx + yy);
}

// Euler XYZ degrees of R = Rx·Ry·Rz: r02 = sin y, r12 = -sin x cos y, r22 = cos x cos y,
// r01 = -cos y sin z, r00 = cos y cos z.
inline void to_euler_deg(const Quat& qin, float out[3]) {
    double r[9];
    qmatrix(qnormalize(qin), r);
    const double sy = std::clamp(r[2], -1.0, 1.0);
    double x, y, z;
    if (std::fabs(sy) < 0.9999999) {
        y = std::asin(sy);
        x = std::atan2(-r[5], r[8]);
        z = std::atan2(-r[1], r[0]);
    } else {  // gimbal lock: put everything in x
        y = sy > 0 ? 1.5707963267948966 : -1.5707963267948966;
        x = std::atan2(r[7], r[4]);
        z = 0.0;
    }
    out[0] = static_cast<float>(x * RAD_TO_DEG);
    out[1] = static_cast<float>(y * RAD_TO_DEG);
    out[2] = static_cast<float>(z * RAD_TO_DEG);
}

inline Quat qslerp(const Quat& a, Quat b, double t) {
    double d = qdot(a, b);
    if (d < 0.0) {
        b = Quat{-b.w, -b.x, -b.y, -b.z};
        d = -d;
    }
    if (d > 0.9999995) {  // nearly equal: nlerp
        return qnormalize(Quat{a.w + (b.w - a.w) * t, a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t,
                               a.z + (b.z - a.z) * t});
    }
    const double theta = std::acos(std::clamp(d, -1.0, 1.0));
    const double s = std::sin(theta);
    const double wa = std::sin((1.0 - t) * theta) / s;
    const double wb = std::sin(t * theta) / s;
    return Quat{a.w * wa + b.w * wb, a.x * wa + b.x * wb, a.y * wa + b.y * wb, a.z * wa + b.z * wb};
}

// Angle (radians, >= 0) and unit axis of q (shortest arc). Angle 0 -> axis (1,0,0).
inline double qangle_axis(Quat q, double axis[3]) {
    if (q.w < 0.0) q = Quat{-q.w, -q.x, -q.y, -q.z};
    const double s = std::sqrt(q.x * q.x + q.y * q.y + q.z * q.z);
    if (s <= 0.0) {
        axis[0] = 1.0;
        axis[1] = axis[2] = 0.0;
        return 0.0;
    }
    axis[0] = q.x / s;
    axis[1] = q.y / s;
    axis[2] = q.z / s;
    return 2.0 * std::atan2(s, q.w);
}


}  // namespace cenda::rig
