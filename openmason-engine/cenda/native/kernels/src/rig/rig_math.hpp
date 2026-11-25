// Cenda rig: small column-major affine math in double (composition) — the inputs/outputs are float.
#pragma once

#include <cmath>
#include <cstring>

namespace cenda::rig {

// Column-major 4x4 (m[col*4 + row]), affine in practice.
struct Mat4 {
    double m[16];

    static Mat4 identity() {
        Mat4 r{};
        r.m[0] = r.m[5] = r.m[10] = r.m[15] = 1.0;
        return r;
    }

    static Mat4 from_float(const float* f) {
        Mat4 r{};
        for (int i = 0; i < 16; ++i) r.m[i] = static_cast<double>(f[i]);
        return r;
    }

    void to_float(float* out) const {
        for (int i = 0; i < 16; ++i) out[i] = static_cast<float>(m[i]);
    }

    double& at(int row, int col) { return m[col * 4 + row]; }
    double at(int row, int col) const { return m[col * 4 + row]; }

    Mat4 operator*(const Mat4& b) const {
        Mat4 r{};
        for (int c = 0; c < 4; ++c) {
            for (int rr = 0; rr < 4; ++rr) {
                double s = 0.0;
                for (int k = 0; k < 4; ++k) s += at(rr, k) * b.at(k, c);
                r.at(rr, c) = s;
            }
        }
        return r;
    }

    // this * T(x,y,z)
    Mat4 translated(double x, double y, double z) const {
        Mat4 t = identity();
        t.at(0, 3) = x;
        t.at(1, 3) = y;
        t.at(2, 3) = z;
        return *this * t;
    }

    void transform_point(const double in[3], double out[3]) const {
        for (int r = 0; r < 3; ++r) {
            out[r] = at(r, 0) * in[0] + at(r, 1) * in[1] + at(r, 2) * in[2] + at(r, 3);
        }
    }

    // General inverse of an affine matrix (3x3 linear part may be any invertible matrix). A singular
    // linear part (zero scale) inverts to the translation-only inverse so posing never produces NaN.
    Mat4 affine_inverse() const {
        const double a = at(0, 0), b = at(0, 1), c = at(0, 2);
        const double d = at(1, 0), e = at(1, 1), f = at(1, 2);
        const double g = at(2, 0), h = at(2, 1), i = at(2, 2);
        const double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        const double det = a * A + b * B + c * C;
        Mat4 r = identity();
        if (std::fabs(det) < 1e-30) {
            r.at(0, 3) = -at(0, 3);
            r.at(1, 3) = -at(1, 3);
            r.at(2, 3) = -at(2, 3);
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
        const double tx = at(0, 3), ty = at(1, 3), tz = at(2, 3);
        for (int row = 0; row < 3; ++row) {
            r.at(row, 3) = -(r.at(row, 0) * tx + r.at(row, 1) * ty + r.at(row, 2) * tz);
        }
        return r;
    }
};

constexpr double DEG_TO_RAD = 3.14159265358979323846 / 180.0;

// 3x3 row-major rotation R = Rx(x)·Ry(y)·Rz(z) (JOML rotateXYZ order), angles in radians.
inline void rot_xyz(double x, double y, double z, double r[9]) {
    const double sx = std::sin(x), cx = std::cos(x);
    const double sy = std::sin(y), cy = std::cos(y);
    const double sz = std::sin(z), cz = std::cos(z);
    // Rx·Ry
    // Rx = [1 0 0; 0 cx -sx; 0 sx cx], Ry = [cy 0 sy; 0 1 0; -sy 0 cy], Rz = [cz -sz 0; sz cz 0; 0 0 1]
    const double xy[9] = {
        cy, 0.0, sy,
        sx * sy, cx, -sx * cy,
        -cx * sy, sx, cx * cy,
    };
    const double rz[9] = {cz, -sz, 0.0, sz, cz, 0.0, 0.0, 0.0, 1.0};
    for (int row = 0; row < 3; ++row) {
        for (int col = 0; col < 3; ++col) {
            r[row * 3 + col] = xy[row * 3 + 0] * rz[0 * 3 + col] + xy[row * 3 + 1] * rz[1 * 3 + col]
                + xy[row * 3 + 2] * rz[2 * 3 + col];
        }
    }
}

inline void mul3(const double a[9], const double b[9], double out[9]) {
    double r[9];
    for (int row = 0; row < 3; ++row) {
        for (int col = 0; col < 3; ++col) {
            r[row * 3 + col] = a[row * 3 + 0] * b[0 * 3 + col] + a[row * 3 + 1] * b[1 * 3 + col]
                + a[row * 3 + 2] * b[2 * 3 + col];
        }
    }
    std::memcpy(out, r, sizeof r);
}

}  // namespace cenda::rig
