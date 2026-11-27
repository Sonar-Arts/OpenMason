// Cenda mesh pipeline (OpenMason #175 F3): VertexWelder + MeshImporter + PolygonTriangulator + RenderMeshBuilder +
// MeshTopologyBuilder of engine-modeling, native and bit-for-bit (see cenda/model_mesh.h for the contract and every
// Java behaviour reproduced). The Java classes are the wire truth: each function below names the Java method it
// mirrors, and float expressions keep Java's operation order (JOML dot = x*x' + (y*y' + z*z'), no FMA: this TU is
// compiled with -ffp-contract=off). No exception leaves this file.
#include "cenda/model_mesh.h"

#include "model_internal.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <exception>
#include <limits>
#include <new>
#include <string>
#include <type_traits>
#include <utility>
#include <vector>

static_assert(sizeof(co_mesh_info) == 56 && offsetof(co_mesh_info, revision) == 48,
              "co_mesh_info layout is documented in cenda/model_mesh.h");
static_assert(sizeof(co_face_mapping) == 32, "co_face_mapping layout is cenda/model.h's");

namespace {

// ---------------------------------------------------------------------------------------------------------------
// Java float semantics.

struct V3 {
    float x, y, z;
};

// JOML (org.joml.Math.fma without joml.useMathFma = a * b + c, unfused).
inline float jdot(V3 a, V3 b) { return a.x * b.x + (a.y * b.y + a.z * b.z); }
inline float jlensq(V3 a) { return a.x * a.x + (a.y * a.y + a.z * a.z); }
inline float jlength(V3 a) { return static_cast<float>(std::sqrt(static_cast<double>(jlensq(a)))); }
inline V3 jnormalize(V3 a) {
    const float s = 1.0f / static_cast<float>(std::sqrt(static_cast<double>(jlensq(a))));
    return {a.x * s, a.y * s, a.z * s};
}
// Vector3f.cross: rx = fma(y, v.z, -z * v.y) = y*v.z + (-(z*v.y)) == y*v.z - z*v.y exactly in IEEE.
inline V3 jcross(V3 a, V3 b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
inline V3 jdiv(V3 a, float s) {
    const float inv = 1.0f / s;
    return {a.x * inv, a.y * inv, a.z * inv};
}

// java.lang.Math.min / max (NaN first, -0.0 < +0.0).
inline float jmin(float a, float b) {
    if (a != a) return a;
    if (a == 0.0f && b == 0.0f && std::signbit(b)) return b;
    return a <= b ? a : b;
}
inline float jmax(float a, float b) {
    if (a != a) return a;
    if (a == 0.0f && b == 0.0f && std::signbit(a)) return b;
    return a >= b ? a : b;
}
inline double jmax(double a, double b) {
    if (a != a) return a;
    if (a == 0.0 && b == 0.0 && std::signbit(a)) return b;
    return a >= b ? a : b;
}
// Math.clamp(float, float, float) for min < max: min(max, max(value, min)).
inline float jclamp(float v, float lo, float hi) { return jmin(hi, jmax(v, lo)); }
inline float jsignum(float f) { return (f == 0.0f || f != f) ? f : std::copysign(1.0f, f); }
// Java's d2i: NaN -> 0, saturating.
inline int32_t jd2i(double d) {
    if (d != d) return 0;
    if (d >= 2147483647.0) return std::numeric_limits<int32_t>::max();
    if (d <= -2147483648.0) return std::numeric_limits<int32_t>::min();
    return static_cast<int32_t>(d);
}
inline uint32_t float_bits(float f) {
    uint32_t u;
    std::memcpy(&u, &f, 4);
    return u;
}
// Float.compare(a, b) == 0 (record equality): floatToIntBits, NaNs canonical.
inline bool jfloat_equals(float a, float b) {
    if (a != a || b != b) return a != a && b != b;
    return float_bits(a) == float_bits(b);
}

constexpr float kPositionEpsilon = 1e-4f;          // ModelingConstants.POSITION_EPSILON
constexpr float kDegenerateNormalEpsilon = 1e-6f;  // ModelingConstants.DEGENERATE_NORMAL_EPSILON
constexpr float kRelativeAreaEpsilon = 1e-7f;      // PolygonTriangulator.RELATIVE_AREA_EPSILON
constexpr float kJavaPiF = static_cast<float>(3.14159265358979323846);  // (float) Math.PI
constexpr int32_t kMaxFaceId = 1 << 26;

// ---------------------------------------------------------------------------------------------------------------
// Open-addressing uint64 -> int32 map (linear probing, power-of-two capacity). Keys must not be UINT64_MAX.

inline uint64_t mix64(uint64_t x) {
    x ^= x >> 33;
    x *= 0xff51afd7ed558ccdull;
    x ^= x >> 33;
    x *= 0xc4ceb9fe1a85ec53ull;
    x ^= x >> 33;
    return x;
}

struct U64Map {
    static constexpr uint64_t kEmpty = std::numeric_limits<uint64_t>::max();
    std::vector<uint64_t> keys;
    std::vector<int32_t> values;
    uint64_t mask = 0;
    size_t size = 0;

    void reset(size_t expected) {
        size_t cap = 16;
        while (cap < expected * 2) cap <<= 1;
        keys.assign(cap, kEmpty);
        values.assign(cap, -1);
        mask = cap - 1;
        size = 0;
    }
    int32_t find(uint64_t key) const { return find_h(key, mix64(key)); }
    // h = mix64(key), when the caller already has it.
    int32_t find_h(uint64_t key, uint64_t h) const {
        if (keys.empty()) return -1;
        for (uint64_t i = h & mask;; i = (i + 1) & mask) {
            const uint64_t k = keys[i];
            if (k == key) return values[i];
            if (k == kEmpty) return -1;
        }
    }
    // Slot index of the key (valid until the next insertion), inserting it (value -1) when absent.
    size_t slot(uint64_t key, bool* inserted) { return slot_h(key, mix64(key), inserted); }
    size_t slot_h(uint64_t key, uint64_t h, bool* inserted) {
        if ((size + 1) * 2 > keys.size()) grow();
        for (uint64_t i = h & mask;; i = (i + 1) & mask) {
            const uint64_t k = keys[i];
            if (k == key) {
                *inserted = false;
                return static_cast<size_t>(i);
            }
            if (k == kEmpty) {
                keys[i] = key;
                values[i] = -1;
                ++size;
                *inserted = true;
                return static_cast<size_t>(i);
            }
        }
    }
    void grow() {
        std::vector<uint64_t> old_keys = std::move(keys);
        std::vector<int32_t> old_values = std::move(values);
        const size_t cap = std::max<size_t>(16, old_keys.size() * 2);
        keys.assign(cap, kEmpty);
        values.assign(cap, -1);
        mask = cap - 1;
        for (size_t j = 0; j < old_keys.size(); ++j) {
            if (old_keys[j] == kEmpty) continue;
            for (uint64_t i = mix64(old_keys[j]) & mask;; i = (i + 1) & mask) {
                if (keys[i] == kEmpty) {
                    keys[i] = old_keys[j];
                    values[i] = old_values[j];
                    break;
                }
            }
        }
    }
};

// ---------------------------------------------------------------------------------------------------------------
// java.util.HashSet<Integer> iteration order (see model_mesh.h). perm gets the visiting order of vals[0..n) (distinct
// values in insertion order). Returns false when Java would have made a tree bin (the order is then approximate).

inline uint32_t jspread(int32_t v) {
    const auto h = static_cast<uint32_t>(v);
    return h ^ (h >> 16);
}

bool java_hash_order(const int32_t* vals, int32_t n, std::vector<int32_t>& perm, std::vector<int32_t>& counts) {
    perm.resize(static_cast<size_t>(n));
    for (int32_t i = 0; i < n; ++i) perm[static_cast<size_t>(i)] = i;
    if (n <= 1) return true;
    uint32_t cap = 16;
    bool exact = true;
    if (n > 8) {  // up to 8 entries neither treeify a bucket nor pass the 12-entry threshold
        auto recount = [&](int32_t upto) {
            counts.assign(cap, 0);
            for (int32_t j = 0; j < upto; ++j) ++counts[jspread(vals[j]) & (cap - 1)];
        };
        counts.assign(cap, 0);
        for (int32_t i = 0; i < n; ++i) {
            const uint32_t b = jspread(vals[i]) & (cap - 1);
            const int32_t before = counts[b];
            ++counts[b];
            if (before >= 8) {  // HashMap.putVal: binCount >= TREEIFY_THRESHOLD - 1 -> treeifyBin
                if (cap < 64) {  // MIN_TREEIFY_CAPACITY: resize instead
                    cap <<= 1;
                    recount(i + 1);
                } else {
                    exact = false;
                }
            }
            if (static_cast<uint32_t>(i + 1) > cap / 4 * 3) {  // ++size > threshold (0.75 * capacity)
                cap <<= 1;
                recount(i + 1);
            }
        }
    }
    const uint32_t mask = cap - 1;
    auto bucket = [&](int32_t idx) { return jspread(vals[idx]) & mask; };
    if (n <= 16) {
        for (int32_t i = 1; i < n; ++i) {
            const int32_t p = perm[static_cast<size_t>(i)];
            const uint32_t bp = bucket(p);
            int32_t j = i - 1;
            while (j >= 0 && bucket(perm[static_cast<size_t>(j)]) > bp) {
                perm[static_cast<size_t>(j + 1)] = perm[static_cast<size_t>(j)];
                --j;
            }
            perm[static_cast<size_t>(j + 1)] = p;
        }
    } else {
        std::stable_sort(perm.begin(), perm.end(), [&](int32_t a, int32_t b) { return bucket(a) < bucket(b); });
    }
    return exact;
}

// ---------------------------------------------------------------------------------------------------------------
// PolygonTriangulator.

struct TriScratch {
    std::vector<V3> loop, norm;
    std::vector<float> xs, ys;
    std::vector<int32_t> active;
};

// PolygonTriangulator.newellNormal: double accumulation relative to loop[0], float result.
V3 newell_double(const V3* loop, int32_t n) {
    double nx = 0, ny = 0, nz = 0;
    if (n == 0) return {0, 0, 0};
    const V3 o = loop[0];
    for (int32_t i = 0; i < n; ++i) {
        const V3 c = loop[i];
        const V3 x = loop[(i + 1) % n];
        const double cy = static_cast<double>(c.y) - o.y, xy = static_cast<double>(x.y) - o.y;
        const double cz = static_cast<double>(c.z) - o.z, xz = static_cast<double>(x.z) - o.z;
        const double cx = static_cast<double>(c.x) - o.x, xx = static_cast<double>(x.x) - o.x;
        nx += (cy - xy) * (cz + xz);
        ny += (cz - xz) * (cx + xx);
        nz += (cx - xx) * (cy + xy);
    }
    return {static_cast<float>(nx), static_cast<float>(ny), static_cast<float>(nz)};
}

void fan(int32_t n, int32_t* out) {
    int32_t t = 0;
    for (int32_t i = 1; i < n - 1; ++i) {
        out[t++] = 0;
        out[t++] = i % n;
        out[t++] = (i + 1) % n;
    }
}

// (a->b) x (b->c)
inline float cross2(const float* xs, const float* ys, int32_t a, int32_t b, int32_t c) {
    const float abx = xs[b] - xs[a];
    const float aby = ys[b] - ys[a];
    const float bcx = xs[c] - xs[b];
    const float bcy = ys[c] - ys[b];
    return abx * bcy - aby * bcx;
}
// (a->b) x (a->p)
inline float cross_to_point(const float* xs, const float* ys, int32_t a, int32_t b, int32_t p) {
    const float abx = xs[b] - xs[a];
    const float aby = ys[b] - ys[a];
    const float apx = xs[p] - xs[a];
    const float apy = ys[p] - ys[a];
    return abx * apy - aby * apx;
}

// PolygonTriangulator.triangulate: out = 3(n-2) loop-local indices. Returns 0 or the CO_MESH_FACE_TRI_* fallback.
int32_t triangulate(const V3* loop_in, int32_t n, int32_t* out, TriScratch& s) {
    if (n == 3) {
        out[0] = 0;
        out[1] = 1;
        out[2] = 2;
        return 0;
    }
    // normalizedLoop
    const V3* loop = loop_in;
    const V3 o = loop_in[0];
    double extent = 0;
    for (int32_t i = 0; i < n; ++i) {
        const V3 p = loop_in[i];
        extent = jmax(extent, jmax(std::fabs(static_cast<double>(p.x) - o.x),
                                   jmax(std::fabs(static_cast<double>(p.y) - o.y),
                                        std::fabs(static_cast<double>(p.z) - o.z))));
    }
    if (!(extent == 0)) {
        s.norm.resize(static_cast<size_t>(n));
        for (int32_t i = 0; i < n; ++i) {
            const V3 p = loop_in[i];
            s.norm[static_cast<size_t>(i)] = {static_cast<float>((static_cast<double>(p.x) - o.x) / extent),
                                              static_cast<float>((static_cast<double>(p.y) - o.y) / extent),
                                              static_cast<float>((static_cast<double>(p.z) - o.z) / extent)};
        }
        loop = s.norm.data();
    }
    const V3 normal = newell_double(loop, n);
    if (jlensq(normal) < kRelativeAreaEpsilon * kRelativeAreaEpsilon) {
        fan(n, out);
        return CO_MESH_FACE_TRI_DEGENERATE_NORMAL;
    }
    const V3 unit = jnormalize(normal);
    // pickTangent
    const V3 ref = std::fabs(unit.y) > 0.9f ? V3{0.0f, 0.0f, -1.0f} : V3{0.0f, 1.0f, 0.0f};
    const V3 tangent = jnormalize(jcross(ref, unit));
    const V3 bitangent = jcross(unit, tangent);

    s.xs.resize(static_cast<size_t>(n));
    s.ys.resize(static_cast<size_t>(n));
    float* xs = s.xs.data();
    float* ys = s.ys.data();
    float max_extent = 0.0f;
    for (int32_t i = 0; i < n; ++i) {
        xs[i] = jdot(loop[i], tangent);
        ys[i] = jdot(loop[i], bitangent);
        max_extent = jmax(max_extent, jmax(std::fabs(xs[i]), std::fabs(ys[i])));
    }
    const float area_eps = kRelativeAreaEpsilon * jmax(max_extent * max_extent, 1e-12f);

    float sum = 0.0f;  // signedArea
    for (int32_t i = 0; i < n; ++i) {
        const int32_t j = (i + 1) % n;
        sum += xs[i] * ys[j] - xs[j] * ys[i];
    }
    const float orient = jsignum(sum * 0.5f);
    if (orient == 0.0f) {
        fan(n, out);
        return CO_MESH_FACE_TRI_ZERO_AREA;
    }

    bool convex = true;  // isConvex
    for (int32_t i = 0; i < n; ++i) {
        const int32_t p = (i + n - 1) % n;
        const int32_t x = (i + 1) % n;
        if (cross2(xs, ys, p, i, x) * orient < -area_eps) {
            convex = false;
            break;
        }
    }
    if (convex) {
        fan(n, out);
        return 0;
    }

    // earClip
    std::vector<int32_t>& active = s.active;
    active.resize(static_cast<size_t>(n));
    for (int32_t i = 0; i < n; ++i) active[static_cast<size_t>(i)] = i;
    int32_t t = 0;
    while (active.size() > 3) {
        const auto m = static_cast<int32_t>(active.size());
        int32_t ear = -1;
        for (int32_t k = 0; k < m && ear < 0; ++k) {  // findEar
            const int32_t a = active[static_cast<size_t>((k + m - 1) % m)];
            const int32_t b = active[static_cast<size_t>(k)];
            const int32_t c = active[static_cast<size_t>((k + 1) % m)];
            if (cross2(xs, ys, a, b, c) * orient <= area_eps) continue;
            bool blocked = false;
            for (int32_t other : active) {
                if (other == a || other == b || other == c) continue;
                if (cross_to_point(xs, ys, a, b, other) * orient >= -area_eps &&
                    cross_to_point(xs, ys, b, c, other) * orient >= -area_eps &&
                    cross_to_point(xs, ys, c, a, other) * orient >= -area_eps) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) ear = k;
        }
        if (ear < 0) {
            for (int32_t i = 1; i < m - 1; ++i) {
                out[t++] = active[0];
                out[t++] = active[static_cast<size_t>(i)];
                out[t++] = active[static_cast<size_t>(i + 1)];
            }
            return CO_MESH_FACE_TRI_NO_EAR;
        }
        out[t++] = active[static_cast<size_t>((ear + m - 1) % m)];
        out[t++] = active[static_cast<size_t>(ear)];
        out[t++] = active[static_cast<size_t>((ear + 1) % m)];
        active.erase(active.begin() + ear);
    }
    out[t++] = active[0];
    out[t++] = active[1];
    out[t] = active[2];
    return 0;
}

// ---------------------------------------------------------------------------------------------------------------
// RenderMeshBuilder.projectFaceUVs (+ FaceProjectionUtil.computeTangentFrame).

struct Region {
    float u0 = 0, v0 = 0, u1 = 1, v1 = 1;
    int32_t rotation = 0;  // degrees
};

// Returns CO_MESH_FACE_UV_PROJECTED (| UV_DEGENERATE).
int32_t project_uvs(const V3* loop, int32_t n, const Region& r, float* uv, std::vector<float>& proj) {
    V3 normal = newell_double(loop, n);
    // Tangent-frame validation expects a direction, not an area-scaled normal.
    if (jlensq(normal) > 0) normal = jnormalize(normal);
    if (jlensq(normal) < kPositionEpsilon * kPositionEpsilon) {  // computeTangentFrame == null
        for (int32_t i = 0; i < n; ++i) {
            uv[i * 2] = r.u0 + 0.5f * (r.u1 - r.u0);
            uv[i * 2 + 1] = r.v0 + 0.5f * (r.v1 - r.v0);
        }
        return CO_MESH_FACE_UV_PROJECTED | CO_MESH_FACE_UV_DEGENERATE;
    }
    const V3 nn = jnormalize(normal);
    const V3 ref = std::fabs(nn.y) > 0.9f ? V3{0.0f, 0.0f, -1.0f} : V3{0.0f, 1.0f, 0.0f};
    const V3 tangent = jnormalize(jcross(ref, nn));
    const V3 bitangent = jnormalize(jcross(nn, tangent));

    proj.resize(static_cast<size_t>(n) * 2);
    float* ps = proj.data();
    float* pt = proj.data() + n;
    const V3 r0 = loop[0];
    float min_s = std::numeric_limits<float>::max(), max_s = -std::numeric_limits<float>::max();
    float min_t = std::numeric_limits<float>::max(), max_t = -std::numeric_limits<float>::max();
    for (int32_t i = 0; i < n; ++i) {
        const float dx = loop[i].x - r0.x;
        const float dy = loop[i].y - r0.y;
        const float dz = loop[i].z - r0.z;
        ps[i] = dx * tangent.x + dy * tangent.y + dz * tangent.z;
        pt[i] = dx * bitangent.x + dy * bitangent.y + dz * bitangent.z;
        min_s = jmin(min_s, ps[i]);
        max_s = jmax(max_s, ps[i]);
        min_t = jmin(min_t, pt[i]);
        max_t = jmax(max_t, pt[i]);
    }
    const float range_s = max_s - min_s;
    const float range_t = max_t - min_t;
    for (int32_t i = 0; i < n; ++i) {
        float s = (range_s > kPositionEpsilon) ? (ps[i] - min_s) / range_s : 0.5f;
        float t = (range_t > kPositionEpsilon) ? (pt[i] - min_t) / range_t : 0.5f;
        t = 1.0f - t;  // V flip
        if (r.rotation != 0) {
            float rs, rt;
            switch (r.rotation) {
                case 90: rs = t; rt = 1.0f - s; break;
                case 180: rs = 1.0f - s; rt = 1.0f - t; break;
                case 270: rs = 1.0f - t; rt = s; break;
                default: rs = s; rt = t; break;
            }
            s = rs;
            t = rt;
        }
        uv[i * 2] = r.u0 + s * (r.u1 - r.u0);
        uv[i * 2 + 1] = r.v0 + t * (r.v1 - r.v0);
    }
    return CO_MESH_FACE_UV_PROJECTED;
}

// ---------------------------------------------------------------------------------------------------------------
// MeshGeometry (topology face / vertex geometry, float accumulation).

struct FaceGeom {
    V3 normal, centroid;
    float area;
};

// computeNormal + computeCentroid + computeArea for one loop.
FaceGeom face_geometry(const float* pos, const int32_t* loop, int32_t n) {
    float nx = 0, ny = 0, nz = 0, sx = 0, sy = 0, sz = 0;
    for (int32_t i = 0; i < n; ++i) {
        const float* c = pos + static_cast<size_t>(loop[i]) * 3;
        const float* x = pos + static_cast<size_t>(loop[(i + 1) % n]) * 3;
        nx += (c[1] - x[1]) * (c[2] + x[2]);
        ny += (c[2] - x[2]) * (c[0] + x[0]);
        nz += (c[0] - x[0]) * (c[1] + x[1]);
        sx += c[0];
        sy += c[1];
        sz += c[2];
    }
    FaceGeom g{};
    V3 r{nx, ny, nz};
    const float len = jlength(r);
    if (len > kDegenerateNormalEpsilon) r = jdiv(r, len);
    g.normal = r;
    const auto cnt = static_cast<float>(n);
    g.centroid = {sx / cnt, sy / cnt, sz / cnt};
    g.area = 0.5f * static_cast<float>(std::sqrt(static_cast<double>(nx * nx + ny * ny + nz * nz)));
    return g;
}

// ---------------------------------------------------------------------------------------------------------------
// Weld cells (VertexWelder).

inline uint64_t cell_key(int32_t x, int32_t y, int32_t z) {
    return ((static_cast<uint64_t>(static_cast<uint32_t>(x)) & 0x1FFFFFull) << 42) |
           ((static_cast<uint64_t>(static_cast<uint32_t>(y)) & 0x1FFFFFull) << 21) |
           (static_cast<uint64_t>(static_cast<uint32_t>(z)) & 0x1FFFFFull);
}
inline int32_t wrap_add(int32_t a, int32_t b) {
    return static_cast<int32_t>(static_cast<uint32_t>(a) + static_cast<uint32_t>(b));
}

// Which of the neighbour cells c-1, c, c+1 (bits 0, 1, 2) along one axis can hold a point within epsilon of x. An
// exact pruning: a cell is skipped only when every point the Java code could have filed under it is provably farther
// than epsilon along this axis (1e-3 relative margin, far above float rounding), so the first match - therefore the
// weld - is the one the full 27-cell scan finds. Non-finite / large (>= 2^22 cells) coordinates scan all three.
inline int32_t axis_cells(float x, int32_t c, double cs, double margin) {
    if (x != x || !(std::fabs(static_cast<double>(c)) < 4194304.0)) return 7;
    const double u = (std::fabs(static_cast<double>(c)) + 2.0) * 1.1920928955078125e-07;  // 2^-23
    const double xd = x;
    int32_t m = 2;
    // A point of cell c-1 has fl(p / cs) < c, so p < cs * c (c, c + 1 are exact floats below 2^22).
    if (!(xd - cs * (static_cast<double>(c) + u) >= margin)) m |= 1;
    // A point of cell c+1 has fl(p / cs) >= c + 1, so p >= cs * ((c + 1) - u).
    if (!(cs * (static_cast<double>(c) + 1.0 - u) - xd >= margin)) m |= 4;
    return m;
}

// ---------------------------------------------------------------------------------------------------------------

int32_t fail(int32_t code, const std::string& message) {
    cenda::model::co_error_message() = message;
    return code;
}
void clear_error() { cenda::model::co_error_message().clear(); }

template <typename F>
auto guarded(const char* fn, F&& body) -> decltype(body()) {
    try {
        return body();
    } catch (const std::bad_alloc&) {
        fail(CO_ERR_ARG, std::string(fn) + ": out of memory");
    } catch (const std::exception& e) {
        fail(CO_ERR_ARG, std::string(fn) + ": " + e.what());
    } catch (...) {
        fail(CO_ERR_ARG, std::string(fn) + ": unknown error");
    }
    if constexpr (std::is_pointer_v<decltype(body())>) {
        return nullptr;
    } else {
        return static_cast<decltype(body())>(CO_ERR_ARG);
    }
}

constexpr int32_t kBuildStages = CO_MESH_STAGE_TRIANGULATE | CO_MESH_STAGE_RENDER | CO_MESH_STAGE_TOPOLOGY;
constexpr int32_t kTriFlags =
    CO_MESH_FACE_TRI_DEGENERATE_NORMAL | CO_MESH_FACE_TRI_ZERO_AREA | CO_MESH_FACE_TRI_NO_EAR;

struct ImportTri {
    int32_t slot;
    int32_t v[3];
    int32_t s[3];
};

struct LoopScratch {
    std::vector<uint64_t> dir;                            // directed edges of the face, in triangle order
    std::vector<uint64_t> keys_sorted;                    // distinct directed keys, ascending
    std::vector<std::pair<int32_t, uint64_t>> first_seq;  // (first sequence, key)
    std::vector<int32_t> b_src, b_dst;                    // boundaryNext, insertion order
    std::vector<int32_t> pos_of;                          // per vertex: index into b_src (when pos_stamp == gen)
    std::vector<uint32_t> pos_stamp;
    uint32_t gen = 0;
};

}  // namespace

struct co_mesh {
    int32_t stages = 0;
    uint64_t revision = 0;
    std::vector<co_face_mapping> mappings;
    std::vector<int32_t> mapping_flags;  // parallel to mappings (CO_MESH_MAPPING_*)
    int32_t approximate_sets = 0;

    // WELD
    int32_t soup_count = 0;
    std::vector<int32_t> soup_to_vertex, vertex_soup_off, vertex_soup;
    // WELD / FACES
    int32_t vertex_count = 0;
    std::vector<float> positions;
    // FACES
    std::vector<int32_t> face_ids, loop_off{0}, loop_v, face_flags, corner_face, face_slots, warnings;
    std::vector<float> loop_uvs;
    int32_t face_id_upper = 0;
    // TRIANGULATE
    std::vector<int32_t> tri_idx, tri_face;
    // RENDER
    std::vector<float> corner_pos, corner_uv;
    std::vector<int32_t> vc_off, vc;
    // TOPOLOGY
    std::vector<int32_t> edge_v, ef_off, ef, corner_edges, ve_off, ve, vf_off, vf, fadj_off, fadj, fadj_e;
    std::vector<float> fnorm, fcent, farea, edihed;
    std::vector<int32_t> ekind;
    std::vector<int32_t> edge_lo_off, edge_by_lo;  // edges grouped by low vertex (co_mesh_find_edge)
    // move
    std::vector<int32_t> moved_faces;

    // scratch (reused between calls; never part of a result)
    TriScratch tri_scratch;
    LoopScratch loop_scratch;
    std::vector<V3> loop_pos;
    std::vector<float> proj, uv_tmp;
    std::vector<int32_t> tri_tmp, perm, counts, stamp, slot_of, aux, aux2, mapping_of, loop_tmp;
    std::vector<ImportTri> import_tris, import_sorted;
    std::vector<std::pair<int32_t, int32_t>> pair_tmp;
    uint32_t stamp_gen = 0;
    U64Map map;
    std::vector<int32_t> chain_next, chain_head, chain_tail;
    std::vector<uint64_t> occupied;  // weld: one bit per hashed cell key (clear = the cell is surely empty)

    int32_t next_stamp() {
        if (++stamp_gen >= 0x7FFFFFFFu) {
            std::fill(stamp.begin(), stamp.end(), 0);
            stamp_gen = 1;
        }
        return static_cast<int32_t>(stamp_gen);
    }
    void ensure_stamp(size_t n) {
        if (stamp.size() < n) stamp.resize(n, 0);
    }
};

namespace {

int32_t corner_count(const co_mesh& m) { return m.loop_off.empty() ? 0 : m.loop_off.back(); }
int32_t face_count(const co_mesh& m) { return static_cast<int32_t>(m.face_ids.size()); }

void finish_faces(co_mesh& m) {
    const int32_t F = face_count(m);
    const int32_t C = corner_count(m);
    m.corner_face.resize(static_cast<size_t>(C));
    for (int32_t f = 0; f < F; ++f) {
        for (int32_t c = m.loop_off[static_cast<size_t>(f)]; c < m.loop_off[static_cast<size_t>(f) + 1]; ++c) {
            m.corner_face[static_cast<size_t>(c)] = f;
        }
    }
    m.face_slots.assign(static_cast<size_t>(m.face_id_upper), -1);
    for (int32_t f = 0; f < F; ++f) m.face_slots[static_cast<size_t>(m.face_ids[static_cast<size_t>(f)])] = f;
    m.stages |= CO_MESH_STAGE_FACES;
    m.moved_faces.clear();
}

// ---- WELD (VertexWelder.weld) -----------------------------------------------------------------------------------

void weld(co_mesh& m, const float* p, int32_t S, float eps) {
    m.soup_count = S;
    m.soup_to_vertex.resize(static_cast<size_t>(S));
    const float cell = eps * 2.0f;
    const float eps_sq = eps * eps;
    const double cs = cell;
    const double margin = static_cast<double>(eps) * 1.001;
    U64Map& table = m.map;  // cell key -> chain id
    table.reset(static_cast<size_t>(S) / 4 + 16);  // soups repeat positions; the table grows when needed
    m.chain_head.clear();
    m.chain_tail.clear();
    m.chain_next.assign(static_cast<size_t>(S), -1);
    // Most neighbour cells are empty: an occupancy bitset (~16 bits per vertex, high hash bits) answers those
    // without probing the table. Only a speed-up - a set bit still goes through the table.
    int32_t occ_log2 = 12;
    while (occ_log2 < 26 && (size_t{1} << occ_log2) < static_cast<size_t>(S) * 16) ++occ_log2;
    m.occupied.assign((size_t{1} << occ_log2) / 64, 0);
    uint64_t* occ = m.occupied.data();
    const int32_t occ_shift = 64 - occ_log2;
    std::vector<int32_t>& reps = m.aux;
    reps.clear();
    for (int32_t i = 0; i < S; ++i) {
        const float x = p[static_cast<size_t>(i) * 3];
        const float y = p[static_cast<size_t>(i) * 3 + 1];
        const float z = p[static_cast<size_t>(i) * 3 + 2];
        const int32_t cx = jd2i(std::floor(static_cast<double>(x / cell)));
        const int32_t cy = jd2i(std::floor(static_cast<double>(y / cell)));
        const int32_t cz = jd2i(std::floor(static_cast<double>(z / cell)));
        const int32_t mx = axis_cells(x, cx, cs, margin);
        const int32_t my = axis_cells(y, cy, cs, margin);
        const int32_t mz = axis_cells(z, cz, cs, margin);
        int32_t matched = -1;
        for (int32_t dx = -1; dx <= 1 && matched < 0; ++dx) {
            if (!((mx >> (dx + 1)) & 1)) continue;
            for (int32_t dy = -1; dy <= 1 && matched < 0; ++dy) {
                if (!((my >> (dy + 1)) & 1)) continue;
                for (int32_t dz = -1; dz <= 1 && matched < 0; ++dz) {
                    if (!((mz >> (dz + 1)) & 1)) continue;
                    const uint64_t key = cell_key(wrap_add(cx, dx), wrap_add(cy, dy), wrap_add(cz, dz));
                    const uint64_t h = mix64(key);
                    const uint64_t bit = h >> occ_shift;
                    if (!((occ[bit >> 6] >> (bit & 63)) & 1)) continue;
                    const int32_t chain = table.find_h(key, h);
                    if (chain < 0) continue;
                    for (int32_t j = m.chain_head[static_cast<size_t>(chain)]; j >= 0;
                         j = m.chain_next[static_cast<size_t>(j)]) {
                        const float ddx = x - p[static_cast<size_t>(j) * 3];
                        const float ddy = y - p[static_cast<size_t>(j) * 3 + 1];
                        const float ddz = z - p[static_cast<size_t>(j) * 3 + 2];
                        if (ddx * ddx + ddy * ddy + ddz * ddz < eps_sq) {
                            matched = m.soup_to_vertex[static_cast<size_t>(j)];
                            break;
                        }
                    }
                }
            }
        }
        if (matched >= 0) {
            m.soup_to_vertex[static_cast<size_t>(i)] = matched;
        } else {
            m.soup_to_vertex[static_cast<size_t>(i)] = static_cast<int32_t>(reps.size());
            reps.push_back(i);
        }
        // every soup vertex joins its own cell's chain (also when it matched)
        bool inserted = false;
        const uint64_t own = cell_key(cx, cy, cz);
        const uint64_t own_h = mix64(own);
        const size_t s = table.slot_h(own, own_h, &inserted);
        if (inserted) {
            const uint64_t bit = own_h >> occ_shift;
            occ[bit >> 6] |= uint64_t{1} << (bit & 63);
            table.values[s] = static_cast<int32_t>(m.chain_head.size());
            m.chain_head.push_back(i);
            m.chain_tail.push_back(i);
        } else {
            const auto chain = static_cast<size_t>(table.values[s]);
            m.chain_next[static_cast<size_t>(m.chain_tail[chain])] = i;
            m.chain_tail[chain] = i;
        }
    }
    const auto welded = static_cast<int32_t>(reps.size());
    m.vertex_count = welded;
    m.positions.resize(static_cast<size_t>(welded) * 3);
    for (int32_t w = 0; w < welded; ++w) {
        std::memcpy(&m.positions[static_cast<size_t>(w) * 3],
                    p + static_cast<size_t>(reps[static_cast<size_t>(w)]) * 3, 3 * sizeof(float));
    }
    m.vertex_soup_off.assign(static_cast<size_t>(welded) + 1, 0);
    for (int32_t i = 0; i < S; ++i) ++m.vertex_soup_off[static_cast<size_t>(m.soup_to_vertex[static_cast<size_t>(i)]) + 1];
    for (int32_t w = 0; w < welded; ++w) {
        m.vertex_soup_off[static_cast<size_t>(w) + 1] += m.vertex_soup_off[static_cast<size_t>(w)];
    }
    m.vertex_soup.resize(static_cast<size_t>(S));
    std::vector<int32_t>& fill = m.aux2;
    fill.assign(m.vertex_soup_off.begin(), m.vertex_soup_off.end() - 1);
    for (int32_t i = 0; i < S; ++i) {
        const auto v = static_cast<size_t>(m.soup_to_vertex[static_cast<size_t>(i)]);
        m.vertex_soup[static_cast<size_t>(fill[v]++)] = i;
    }
    m.stages = CO_MESH_STAGE_WELD;
    m.moved_faces.clear();
}

// ---- FACES (MeshImporter.importSoup + reconstructLoop) ----------------------------------------------------------

inline uint64_t dkey(int32_t src, int32_t dst) {
    return (static_cast<uint64_t>(static_cast<uint32_t>(src)) << 32) | static_cast<uint32_t>(dst);
}

// reconstruct_loop for a face of at most 8 triangles: the same LinkedHashMap semantics with linear scans (no sort,
// no per-vertex tables).
bool reconstruct_small(const ImportTri* tris, const std::vector<uint64_t>& dir, std::vector<int32_t>& out) {
    uint64_t distinct[24];
    int32_t nd = 0;
    for (uint64_t d : dir) {  // directedEdgeCounts: first-occurrence order
        bool seen = false;
        for (int32_t j = 0; j < nd && !seen; ++j) seen = distinct[j] == d;
        if (!seen) distinct[nd++] = d;
    }
    int32_t src[24], dst[24];
    int32_t nb = 0;
    for (int32_t i = 0; i < nd; ++i) {
        const auto a = static_cast<int32_t>(distinct[i] >> 32);
        const auto b = static_cast<int32_t>(static_cast<uint32_t>(distinct[i]));
        const uint64_t rev = dkey(b, a);
        bool interior = false;
        for (int32_t j = 0; j < nd && !interior; ++j) interior = distinct[j] == rev;
        if (interior) continue;
        int32_t at = -1;
        for (int32_t j = 0; j < nb && at < 0; ++j) {
            if (src[j] == a) at = j;
        }
        if (at >= 0) {
            dst[at] = b;  // put() on an existing key: value replaced, position kept
        } else {
            src[nb] = a;
            dst[nb] = b;
            ++nb;
        }
    }
    auto next_of = [&](int32_t v) {
        for (int32_t j = 0; j < nb; ++j) {
            if (src[j] == v) return j;
        }
        return -1;
    };
    if (nb >= 3) {
        int32_t start = tris[0].v[0];
        if (next_of(start) < 0) start = src[0];
        int32_t current = start;
        do {
            out.push_back(current);
            const int32_t j = next_of(current);
            if (j < 0) break;
            current = dst[j];
        } while (current != start && static_cast<int32_t>(out.size()) < nb);
        if (out.size() >= 3 && current == start && static_cast<int32_t>(out.size()) == nb) return true;
    }
    out.clear();
    return false;
}

// MeshImporter.reconstructLoop's boundary walk. Returns false when it failed (the caller then uses the
// insertion-order fallback and records the warning).
bool reconstruct_loop(const ImportTri* tris, int32_t k, std::vector<int32_t>& out, LoopScratch& s, int32_t V) {
    out.clear();
    if (k == 1) {
        out.assign(tris[0].v, tris[0].v + 3);
        return true;
    }
    s.dir.clear();
    for (int32_t t = 0; t < k; ++t) {
        const int32_t* v = tris[t].v;
        s.dir.push_back(dkey(v[0], v[1]));
        s.dir.push_back(dkey(v[1], v[2]));
        s.dir.push_back(dkey(v[2], v[0]));
    }
    if (k <= 8) return reconstruct_small(tris, s.dir, out);
    // directedEdgeCounts (LinkedHashMap): distinct keys in first-occurrence order
    const auto D = static_cast<int32_t>(s.dir.size());
    s.first_seq.clear();
    s.first_seq.reserve(static_cast<size_t>(D));
    for (int32_t i = 0; i < D; ++i) s.first_seq.emplace_back(i, s.dir[static_cast<size_t>(i)]);
    std::stable_sort(s.first_seq.begin(), s.first_seq.end(),
                     [](const auto& a, const auto& b) { return a.second < b.second; });
    size_t w = 0;
    for (size_t i = 0; i < s.first_seq.size(); ++i) {
        if (w > 0 && s.first_seq[w - 1].second == s.first_seq[i].second) continue;
        s.first_seq[w++] = s.first_seq[i];
    }
    s.first_seq.resize(w);
    s.keys_sorted.resize(w);
    for (size_t i = 0; i < w; ++i) s.keys_sorted[i] = s.first_seq[i].second;
    std::sort(s.first_seq.begin(), s.first_seq.end(), [](const auto& a, const auto& b) { return a.first < b.first; });

    if (s.pos_of.size() < static_cast<size_t>(V)) {
        s.pos_of.resize(static_cast<size_t>(V));
        s.pos_stamp.resize(static_cast<size_t>(V), 0);
    }
    if (++s.gen == 0) {
        std::fill(s.pos_stamp.begin(), s.pos_stamp.end(), 0);
        s.gen = 1;
    }
    // boundaryNext (LinkedHashMap src -> dst): put() on an existing src replaces the value, keeps the position
    s.b_src.clear();
    s.b_dst.clear();
    for (const auto& e : s.first_seq) {
        const auto src = static_cast<int32_t>(e.second >> 32);
        const auto dst = static_cast<int32_t>(static_cast<uint32_t>(e.second));
        if (std::binary_search(s.keys_sorted.begin(), s.keys_sorted.end(), dkey(dst, src))) continue;
        const auto sv = static_cast<size_t>(src);
        if (s.pos_stamp[sv] == s.gen) {
            s.b_dst[static_cast<size_t>(s.pos_of[sv])] = dst;
        } else {
            s.pos_stamp[sv] = s.gen;
            s.pos_of[sv] = static_cast<int32_t>(s.b_src.size());
            s.b_src.push_back(src);
            s.b_dst.push_back(dst);
        }
    }
    const auto bsize = static_cast<int32_t>(s.b_src.size());
    if (bsize >= 3) {
        int32_t start = tris[0].v[0];
        if (s.pos_stamp[static_cast<size_t>(start)] != s.gen) start = s.b_src[0];
        int32_t current = start;
        do {
            out.push_back(current);
            if (s.pos_stamp[static_cast<size_t>(current)] != s.gen) break;  // boundaryNext.get == null
            current = s.b_dst[static_cast<size_t>(s.pos_of[static_cast<size_t>(current)])];
        } while (current != start && static_cast<int32_t>(out.size()) < bsize);
        if (out.size() >= 3 && current == start && static_cast<int32_t>(out.size()) == bsize) return true;
    }
    out.clear();
    return false;
}

void import_soup(co_mesh& m, const float* p, int32_t S, const float* tc, int64_t tc_count, const int32_t* idx,
                 int64_t index_count, const int32_t* tri_face_ids, int64_t tri_face_id_count, float eps) {
    m.face_ids.clear();
    m.loop_off.assign(1, 0);
    m.loop_v.clear();
    m.loop_uvs.clear();
    m.face_flags.clear();
    m.warnings.clear();
    m.face_id_upper = 0;
    if (S < 3 || index_count < 3) {  // Java: vertices.length < 9 || indices.length < 3 -> empty mesh, no weld
        m.soup_count = 0;
        m.soup_to_vertex.clear();
        m.vertex_soup_off.assign(1, 0);
        m.vertex_soup.clear();
        m.vertex_count = 0;
        m.positions.clear();
        m.stages = CO_MESH_STAGE_WELD;
        finish_faces(m);
        return;
    }
    weld(m, p, S, eps);
    const int32_t V = m.vertex_count;
    const bool has_uvs = tc != nullptr && tc_count >= static_cast<int64_t>(S) * 2;
    const int64_t T = index_count / 3;

    // group the triangles by face id in first-occurrence order (faceTriangles LinkedHashMap). Face ids are usually
    // dense: a direct table when the largest id is small, else a hash map.
    int64_t max_id = -1;
    if (tri_face_ids != nullptr) {
        for (int64_t t = 0; t < std::min(T, tri_face_id_count); ++t) max_id = std::max<int64_t>(max_id, tri_face_ids[t]);
    }
    if (tri_face_ids == nullptr || tri_face_id_count < T) max_id = std::max<int64_t>(max_id, T - 1);
    const bool dense = max_id < 4 * T + 4096;
    std::vector<int32_t>& dense_slot = m.slot_of;
    U64Map& slots = m.map;
    if (dense) {
        dense_slot.assign(static_cast<size_t>(max_id + 1), -1);
    } else {
        slots.reset(static_cast<size_t>(std::min<int64_t>(T, 1 << 20)));
    }
    std::vector<ImportTri>& tris = m.import_tris;
    tris.clear();
    tris.reserve(static_cast<size_t>(T));
    int64_t skipped = 0;
    for (int64_t t = 0; t < T; ++t) {
        const int32_t sa = idx[t * 3], sb = idx[t * 3 + 1], sc = idx[t * 3 + 2];
        if (sa < 0 || sa >= S || sb < 0 || sb >= S || sc < 0 || sc >= S) {
            ++skipped;
            continue;
        }
        const int32_t a = m.soup_to_vertex[static_cast<size_t>(sa)];
        const int32_t b = m.soup_to_vertex[static_cast<size_t>(sb)];
        const int32_t c = m.soup_to_vertex[static_cast<size_t>(sc)];
        if (a == b || b == c || a == c) continue;  // degenerate after welding
        const int32_t face_id =
            (tri_face_ids != nullptr && t < tri_face_id_count) ? tri_face_ids[t] : static_cast<int32_t>(t);
        if (face_id < 0) continue;
        int32_t* slot_ref;
        if (dense) {
            slot_ref = &dense_slot[static_cast<size_t>(face_id)];
        } else {
            bool inserted = false;
            slot_ref = &slots.values[slots.slot(static_cast<uint64_t>(static_cast<uint32_t>(face_id)), &inserted)];
        }
        if (*slot_ref < 0) {
            *slot_ref = static_cast<int32_t>(m.face_ids.size());
            m.face_ids.push_back(face_id);
        }
        tris.push_back({*slot_ref, {a, b, c}, {sa, sb, sc}});
    }
    if (skipped > 0) {
        m.warnings.insert(m.warnings.end(), {CO_MESH_WARN_CORRUPT_INDICES, -1,
                                             static_cast<int32_t>(std::min<int64_t>(skipped, INT32_MAX))});
    }
    const auto G = static_cast<int32_t>(m.face_ids.size());
    // stable counting sort of the triangles by face slot
    std::vector<int32_t>& off = m.slot_of;
    off.assign(static_cast<size_t>(G) + 1, 0);
    for (const ImportTri& t : tris) ++off[static_cast<size_t>(t.slot) + 1];
    for (int32_t g = 0; g < G; ++g) off[static_cast<size_t>(g) + 1] += off[static_cast<size_t>(g)];
    std::vector<ImportTri>& sorted = m.import_sorted;
    sorted.resize(tris.size());
    {
        std::vector<int32_t>& fill = m.aux2;
        fill.assign(off.begin(), off.end() - 1);
        for (const ImportTri& t : tris) sorted[static_cast<size_t>(fill[static_cast<size_t>(t.slot)]++)] = t;
    }

    std::vector<int32_t>& group_ids = m.aux;
    group_ids.assign(m.face_ids.begin(), m.face_ids.end());
    m.face_ids.clear();
    m.ensure_stamp(static_cast<size_t>(V));
    std::vector<int32_t>& first_soup = m.perm;
    first_soup.resize(static_cast<size_t>(V));
    std::vector<int32_t>& loop = m.loop_tmp;
    for (int32_t g = 0; g < G; ++g) {
        const int32_t face_id = group_ids[static_cast<size_t>(g)];
        const ImportTri* ft = sorted.data() + off[static_cast<size_t>(g)];
        const int32_t k = off[static_cast<size_t>(g) + 1] - off[static_cast<size_t>(g)];
        int32_t flags = 0;
        if (!reconstruct_loop(ft, k, loop, m.loop_scratch, V)) {
            // insertion-order fallback (LinkedHashSet of the triangles' vertices)
            m.warnings.insert(m.warnings.end(), {CO_MESH_WARN_BOUNDARY_WALK_FAILED, face_id, k});
            flags |= CO_MESH_FACE_WALK_FAILED;
            const int32_t st = m.next_stamp();
            for (int32_t t = 0; t < k; ++t) {
                for (int32_t v : ft[t].v) {
                    if (m.stamp[static_cast<size_t>(v)] != st) {
                        m.stamp[static_cast<size_t>(v)] = st;
                        loop.push_back(v);
                    }
                }
            }
        }
        if (loop.size() < 3) {
            m.warnings.insert(m.warnings.end(),
                              {CO_MESH_WARN_DEGENERATE_FACE, face_id, static_cast<int32_t>(loop.size())});
            continue;
        }
        m.face_ids.push_back(face_id);
        m.face_id_upper = std::max(m.face_id_upper, face_id + 1);
        m.loop_v.insert(m.loop_v.end(), loop.begin(), loop.end());
        m.loop_off.push_back(static_cast<int32_t>(m.loop_v.size()));
        if (has_uvs) {
            // faceWeldedToSoup: putIfAbsent(a, sa), (b, sb), (c, sc) per triangle in order
            const int32_t st = m.next_stamp();
            for (int32_t t = 0; t < k; ++t) {
                for (int32_t j = 0; j < 3; ++j) {
                    const auto v = static_cast<size_t>(ft[t].v[j]);
                    if (m.stamp[v] != st) {
                        m.stamp[v] = st;
                        first_soup[v] = ft[t].s[j];
                    }
                }
            }
            for (int32_t v : loop) {
                const auto si = static_cast<size_t>(first_soup[static_cast<size_t>(v)]);
                m.loop_uvs.push_back(tc[si * 2]);
                m.loop_uvs.push_back(tc[si * 2 + 1]);
            }
            flags |= CO_MESH_FACE_AUTHORED_UVS;
        } else {
            m.loop_uvs.insert(m.loop_uvs.end(), loop.size() * 2, 0.0f);
        }
        m.face_flags.push_back(flags);
    }
    finish_faces(m);
}

// ---- TRIANGULATE --------------------------------------------------------------------------------------------------

void gather_loop(co_mesh& m, int32_t f, std::vector<V3>& out) {
    const int32_t b = m.loop_off[static_cast<size_t>(f)];
    const int32_t n = m.loop_off[static_cast<size_t>(f) + 1] - b;
    out.resize(static_cast<size_t>(n));
    for (int32_t i = 0; i < n; ++i) {
        const float* p = &m.positions[static_cast<size_t>(m.loop_v[static_cast<size_t>(b + i)]) * 3];
        out[static_cast<size_t>(i)] = {p[0], p[1], p[2]};
    }
}

// Triangulates face slot f into its triangle range; returns true when an index changed.
bool triangulate_face(co_mesh& m, int32_t f) {
    gather_loop(m, f, m.loop_pos);
    const int32_t b = m.loop_off[static_cast<size_t>(f)];
    const auto n = static_cast<int32_t>(m.loop_pos.size());
    m.tri_tmp.resize(static_cast<size_t>(n - 2) * 3);
    const int32_t flag = triangulate(m.loop_pos.data(), n, m.tri_tmp.data(), m.tri_scratch);
    int32_t& ff = m.face_flags[static_cast<size_t>(f)];
    ff = (ff & ~kTriFlags) | flag;
    const size_t first = (static_cast<size_t>(b) - 2 * static_cast<size_t>(f)) * 3;
    bool changed = false;
    for (size_t i = 0; i < m.tri_tmp.size(); ++i) {
        const int32_t c = b + m.tri_tmp[i];
        if (m.tri_idx[first + i] != c) {
            m.tri_idx[first + i] = c;
            changed = true;
        }
    }
    return changed;
}

void build_triangulate(co_mesh& m) {
    const int32_t F = face_count(m);
    const int32_t T = corner_count(m) - 2 * F;
    m.tri_idx.assign(static_cast<size_t>(T) * 3, -1);
    m.tri_face.resize(static_cast<size_t>(T));
    for (int32_t f = 0; f < F; ++f) {
        triangulate_face(m, f);
        const int32_t t0 = m.loop_off[static_cast<size_t>(f)] - 2 * f;
        const int32_t t1 = m.loop_off[static_cast<size_t>(f) + 1] - 2 * (f + 1);
        for (int32_t t = t0; t < t1; ++t) m.tri_face[static_cast<size_t>(t)] = m.face_ids[static_cast<size_t>(f)];
    }
    m.stages |= CO_MESH_STAGE_TRIANGULATE;
}

// ---- RENDER (RenderMeshBuilder.build / emitFaceUVs) ---------------------------------------------------------------

// RenderMeshBuilder.emitFaceUVs: explicitMapping = mapping != null && (materialId != DEFAULT (0) ||
// !uvRegion.equals(FULL_REGION)) - record equality, so -0.0f != 0.0f.
bool is_explicit(const co_face_mapping& mp) {
    return mp.material_id != 0 || !(jfloat_equals(mp.u0, 0.0f) && jfloat_equals(mp.v0, 0.0f) &&
                                    jfloat_equals(mp.u1, 1.0f) && jfloat_equals(mp.v1, 1.0f));
}

// m.mapping_of[face id] = index into m.mappings (the last entry for the id), -1 = unmapped.
void index_mappings(co_mesh& m) {
    m.mapping_of.assign(static_cast<size_t>(m.face_id_upper), -1);
    for (size_t i = 0; i < m.mappings.size(); ++i) {
        const int32_t id = m.mappings[i].face_id;
        if (id >= 0 && id < m.face_id_upper) m.mapping_of[static_cast<size_t>(id)] = static_cast<int32_t>(i);
    }
}

// Writes face slot f's corner UVs; returns true when a value changed. Needs index_mappings.
bool face_uvs(co_mesh& m, int32_t f) {
    const int32_t b = m.loop_off[static_cast<size_t>(f)];
    const int32_t n = m.loop_off[static_cast<size_t>(f) + 1] - b;
    const int32_t id = m.face_ids[static_cast<size_t>(f)];
    const int32_t mi = m.mapping_of[static_cast<size_t>(id)];
    const co_face_mapping* mp = mi >= 0 ? &m.mappings[static_cast<size_t>(mi)] : nullptr;
    int32_t& ff = m.face_flags[static_cast<size_t>(f)];
    ff &= ~(CO_MESH_FACE_UV_PROJECTED | CO_MESH_FACE_UV_DEGENERATE);
    const bool explicit_mapping = mp != nullptr && is_explicit(*mp);
    const bool mapping_authored = mi >= 0 && (m.mapping_flags[static_cast<size_t>(mi)] & CO_MESH_MAPPING_AUTHORED_UVS) != 0;
    const bool authored = (ff & CO_MESH_FACE_AUTHORED_UVS) != 0;
    const size_t bytes = static_cast<size_t>(n) * 2 * sizeof(float);
    m.uv_tmp.resize(static_cast<size_t>(n) * 2);
    if (authored && (!explicit_mapping || mapping_authored)) {  // authored != null && (!explicit || authoredUVs())
        std::memcpy(m.uv_tmp.data(), &m.loop_uvs[static_cast<size_t>(b) * 2], bytes);
    } else {
        Region r;  // no mapping: FULL_REGION, NONE
        if (mp != nullptr) {
            r.u0 = mp->u0;
            r.v0 = mp->v0;
            r.u1 = mp->u1;
            r.v1 = mp->v1;
            r.rotation = mp->uv_rotation_degrees;
        }
        gather_loop(m, f, m.loop_pos);
        ff |= project_uvs(m.loop_pos.data(), n, r, m.uv_tmp.data(), m.proj);
    }
    float* dst = &m.corner_uv[static_cast<size_t>(b) * 2];
    const bool changed = std::memcmp(dst, m.uv_tmp.data(), bytes) != 0;
    std::memcpy(dst, m.uv_tmp.data(), bytes);
    return changed;
}

void build_render(co_mesh& m) {
    const int32_t F = face_count(m);
    const int32_t C = corner_count(m);
    const int32_t V = m.vertex_count;
    m.corner_pos.resize(static_cast<size_t>(C) * 3);
    m.corner_uv.assign(static_cast<size_t>(C) * 2, 0.0f);
    for (int32_t c = 0; c < C; ++c) {
        std::memcpy(&m.corner_pos[static_cast<size_t>(c) * 3],
                    &m.positions[static_cast<size_t>(m.loop_v[static_cast<size_t>(c)]) * 3], 3 * sizeof(float));
    }
    index_mappings(m);
    for (int32_t f = 0; f < F; ++f) face_uvs(m, f);
    m.vc_off.assign(static_cast<size_t>(V) + 1, 0);
    for (int32_t c = 0; c < C; ++c) ++m.vc_off[static_cast<size_t>(m.loop_v[static_cast<size_t>(c)]) + 1];
    for (int32_t v = 0; v < V; ++v) m.vc_off[static_cast<size_t>(v) + 1] += m.vc_off[static_cast<size_t>(v)];
    m.vc.resize(static_cast<size_t>(C));
    std::vector<int32_t>& fill = m.aux2;
    fill.assign(m.vc_off.begin(), m.vc_off.end() - 1);
    for (int32_t c = 0; c < C; ++c) {
        m.vc[static_cast<size_t>(fill[static_cast<size_t>(m.loop_v[static_cast<size_t>(c)])]++)] = c;
    }
    m.stages |= CO_MESH_STAGE_RENDER;
}

// ---- TOPOLOGY (MeshTopologyBuilder.build) ---------------------------------------------------------------------


void face_geometry_into(co_mesh& m, int32_t f) {
    const int32_t b = m.loop_off[static_cast<size_t>(f)];
    const int32_t n = m.loop_off[static_cast<size_t>(f) + 1] - b;
    const FaceGeom g = face_geometry(m.positions.data(), &m.loop_v[static_cast<size_t>(b)], n);
    const auto id = static_cast<size_t>(m.face_ids[static_cast<size_t>(f)]);
    m.fnorm[id * 3] = g.normal.x;
    m.fnorm[id * 3 + 1] = g.normal.y;
    m.fnorm[id * 3 + 2] = g.normal.z;
    m.fcent[id * 3] = g.centroid.x;
    m.fcent[id * 3 + 1] = g.centroid.y;
    m.fcent[id * 3 + 2] = g.centroid.z;
    m.farea[id] = g.area;
}

// MeshTopologyBuilder's initial dihedral angle (= DihedralAngleCache.computeDihedralAngleForEdge) + EdgeKind.
void classify_edge(co_mesh& m, int32_t e) {
    const int32_t k0 = m.ef_off[static_cast<size_t>(e)];
    const int32_t cnt = m.ef_off[static_cast<size_t>(e) + 1] - k0;
    float angle = std::numeric_limits<float>::quiet_NaN();
    if (cnt == 2) {
        const auto a = static_cast<size_t>(m.ef[static_cast<size_t>(k0)]);
        const auto b = static_cast<size_t>(m.ef[static_cast<size_t>(k0) + 1]);
        const V3 n0{m.fnorm[a * 3], m.fnorm[a * 3 + 1], m.fnorm[a * 3 + 2]};
        const V3 n1{m.fnorm[b * 3], m.fnorm[b * 3 + 1], m.fnorm[b * 3 + 2]};
        const float dot = jclamp(jdot(n0, n1), -1.0f, 1.0f);  // MeshGeometry.computeDihedralAngle
        angle = static_cast<float>(std::acos(static_cast<double>(dot)));
    }
    const auto ei = static_cast<size_t>(e);
    m.edihed[ei] = angle;
    m.ekind[ei] = cnt <= 1 ? CO_MESH_EDGE_OPEN : (cnt == 2 ? CO_MESH_EDGE_MANIFOLD : CO_MESH_EDGE_NON_MANIFOLD);
}

struct AdjPair {
    int32_t owner, other, edge;
};

void build_topology(co_mesh& m) {
    const int32_t F = face_count(m);
    const int32_t C = corner_count(m);
    const int32_t V = m.vertex_count;
    const int32_t U = m.face_id_upper;
    m.approximate_sets = 0;

    // Steps 1-2: edges in first-use order (edgeToFaceIds LinkedHashMap), corner -> edge (MeshFace.edgeIds). No hash
    // map: the corners are bucketed by their edge's low vertex (stable counting sort), the first corner of a
    // (low, high) pair inside its bucket represents the edge, and ids are handed out walking the corners in order.
    std::vector<int32_t>& lo_off = m.edge_lo_off;
    std::vector<int32_t>& bucket = m.loop_tmp;
    std::vector<int32_t>& rep = m.aux2;
    std::vector<int32_t>& hi_of = m.slot_of;
    lo_off.assign(static_cast<size_t>(V) + 1, 0);
    hi_of.resize(static_cast<size_t>(C));
    for (int32_t f = 0; f < F; ++f) {
        const int32_t b = m.loop_off[static_cast<size_t>(f)];
        const int32_t e = m.loop_off[static_cast<size_t>(f) + 1];
        for (int32_t c = b; c < e; ++c) {
            const int32_t a = m.loop_v[static_cast<size_t>(c)];
            const int32_t n = m.loop_v[static_cast<size_t>(c + 1 < e ? c + 1 : b)];
            ++lo_off[static_cast<size_t>(std::min(a, n)) + 1];
            hi_of[static_cast<size_t>(c)] = std::max(a, n);
        }
    }
    for (int32_t v = 0; v < V; ++v) lo_off[static_cast<size_t>(v) + 1] += lo_off[static_cast<size_t>(v)];
    bucket.resize(static_cast<size_t>(C));
    {
        std::vector<int32_t>& fill = m.aux;
        fill.assign(lo_off.begin(), lo_off.end() - 1);
        for (int32_t f = 0; f < F; ++f) {
            const int32_t b = m.loop_off[static_cast<size_t>(f)];
            const int32_t e = m.loop_off[static_cast<size_t>(f) + 1];
            for (int32_t c = b; c < e; ++c) {
                const int32_t a = m.loop_v[static_cast<size_t>(c)];
                const int32_t n = m.loop_v[static_cast<size_t>(c + 1 < e ? c + 1 : b)];
                bucket[static_cast<size_t>(fill[static_cast<size_t>(std::min(a, n))]++)] = c;
            }
        }
    }
    rep.resize(static_cast<size_t>(C));
    std::vector<std::pair<int32_t, int32_t>>& big = m.pair_tmp;
    for (int32_t v = 0; v < V; ++v) {
        const int32_t k0 = lo_off[static_cast<size_t>(v)], k1 = lo_off[static_cast<size_t>(v) + 1];
        if (k1 - k0 <= 32) {
            for (int32_t k = k0; k < k1; ++k) {
                const int32_t c = bucket[static_cast<size_t>(k)];
                const int32_t hi = hi_of[static_cast<size_t>(c)];
                int32_t r = c;
                for (int32_t j = k0; j < k; ++j) {
                    const int32_t cj = bucket[static_cast<size_t>(j)];
                    if (hi_of[static_cast<size_t>(cj)] == hi) {
                        r = rep[static_cast<size_t>(cj)];
                        break;
                    }
                }
                rep[static_cast<size_t>(c)] = r;
            }
        } else {  // a high-valence vertex: sort its corners by (high vertex, corner)
            big.clear();
            for (int32_t k = k0; k < k1; ++k) {
                const int32_t c = bucket[static_cast<size_t>(k)];
                big.emplace_back(hi_of[static_cast<size_t>(c)], c);
            }
            std::sort(big.begin(), big.end());
            for (size_t k = 0; k < big.size(); ++k) {
                const bool first = k == 0 || big[k - 1].first != big[k].first;
                rep[static_cast<size_t>(big[k].second)] = first ? big[k].second : rep[static_cast<size_t>(big[k - 1].second)];
            }
        }
    }
    m.edge_v.clear();
    m.corner_edges.resize(static_cast<size_t>(C));
    std::vector<int32_t>& ecount = m.aux;
    ecount.clear();
    for (int32_t c = 0; c < C; ++c) {
        const int32_t r = rep[static_cast<size_t>(c)];
        int32_t e;
        if (r == c) {
            e = static_cast<int32_t>(m.edge_v.size() / 2);
            const int32_t hi = hi_of[static_cast<size_t>(c)];
            const int32_t a = m.loop_v[static_cast<size_t>(c)];
            const size_t f = static_cast<size_t>(m.corner_face[static_cast<size_t>(c)]);
            const int32_t nc = c + 1 < m.loop_off[f + 1] ? c + 1 : m.loop_off[f];
            m.edge_v.push_back(a == hi ? m.loop_v[static_cast<size_t>(nc)] : a);  // the low vertex
            m.edge_v.push_back(hi);
            ecount.push_back(0);
        } else {
            e = m.corner_edges[static_cast<size_t>(r)];
        }
        m.corner_edges[static_cast<size_t>(c)] = e;
        ++ecount[static_cast<size_t>(e)];
    }
    const auto E = static_cast<int32_t>(m.edge_v.size() / 2);
    // edges grouped by low vertex for co_mesh_find_edge (edge_lo_off is free again: the corner buckets are done)
    m.edge_by_lo.resize(static_cast<size_t>(E));
    {
        std::vector<int32_t>& eoff = m.edge_lo_off;
        eoff.assign(static_cast<size_t>(V) + 1, 0);
        for (int32_t e = 0; e < E; ++e) ++eoff[static_cast<size_t>(m.edge_v[static_cast<size_t>(e) * 2]) + 1];
        for (int32_t v = 0; v < V; ++v) eoff[static_cast<size_t>(v) + 1] += eoff[static_cast<size_t>(v)];
        std::vector<int32_t>& fill = m.aux2;
        fill.assign(eoff.begin(), eoff.end() - 1);
        for (int32_t e = 0; e < E; ++e) {
            m.edge_by_lo[static_cast<size_t>(fill[static_cast<size_t>(m.edge_v[static_cast<size_t>(e) * 2])]++)] = e;
        }
    }
    // edge -> faces: insertion order = face order (a loop never repeats an edge), then HashSet order
    m.ef_off.assign(static_cast<size_t>(E) + 1, 0);
    for (int32_t e = 0; e < E; ++e) {
        m.ef_off[static_cast<size_t>(e) + 1] = m.ef_off[static_cast<size_t>(e)] + ecount[static_cast<size_t>(e)];
    }
    m.ef.resize(static_cast<size_t>(C));
    {
        std::vector<int32_t>& fill = m.aux2;
        fill.assign(m.ef_off.begin(), m.ef_off.end() - 1);
        for (int32_t c = 0; c < C; ++c) {
            const auto e = static_cast<size_t>(m.corner_edges[static_cast<size_t>(c)]);
            m.ef[static_cast<size_t>(fill[e]++)] =
                m.face_ids[static_cast<size_t>(m.corner_face[static_cast<size_t>(c)])];
        }
        std::vector<int32_t>& tmp = m.loop_tmp;
        for (int32_t e = 0; e < E; ++e) {
            const int32_t k0 = m.ef_off[static_cast<size_t>(e)];
            const int32_t n = m.ef_off[static_cast<size_t>(e) + 1] - k0;
            if (n <= 1) continue;
            int32_t* vals = &m.ef[static_cast<size_t>(k0)];
            if (!java_hash_order(vals, n, m.perm, m.counts)) ++m.approximate_sets;
            tmp.assign(vals, vals + n);
            for (int32_t i = 0; i < n; ++i) vals[i] = tmp[static_cast<size_t>(m.perm[static_cast<size_t>(i)])];
        }
    }

    // Step 4: vertex -> edges (edge id order, vertexA then vertexB), vertex -> faces (face order, once per face)
    m.ve_off.assign(static_cast<size_t>(V) + 1, 0);
    for (int32_t e = 0; e < E; ++e) {
        ++m.ve_off[static_cast<size_t>(m.edge_v[static_cast<size_t>(e) * 2]) + 1];
        ++m.ve_off[static_cast<size_t>(m.edge_v[static_cast<size_t>(e) * 2 + 1]) + 1];
    }
    for (int32_t v = 0; v < V; ++v) m.ve_off[static_cast<size_t>(v) + 1] += m.ve_off[static_cast<size_t>(v)];
    m.ve.resize(static_cast<size_t>(E) * 2);
    {
        std::vector<int32_t>& fill = m.aux2;
        fill.assign(m.ve_off.begin(), m.ve_off.end() - 1);
        for (int32_t e = 0; e < E; ++e) {
            m.ve[static_cast<size_t>(fill[static_cast<size_t>(m.edge_v[static_cast<size_t>(e) * 2])]++)] = e;
            m.ve[static_cast<size_t>(fill[static_cast<size_t>(m.edge_v[static_cast<size_t>(e) * 2 + 1])]++)] = e;
        }
    }
    m.vf_off.assign(static_cast<size_t>(V) + 1, 0);
    for (int32_t c = 0; c < C; ++c) ++m.vf_off[static_cast<size_t>(m.loop_v[static_cast<size_t>(c)]) + 1];
    for (int32_t v = 0; v < V; ++v) m.vf_off[static_cast<size_t>(v) + 1] += m.vf_off[static_cast<size_t>(v)];
    m.vf.resize(static_cast<size_t>(C));
    {
        std::vector<int32_t>& fill = m.aux2;
        fill.assign(m.vf_off.begin(), m.vf_off.end() - 1);
        for (int32_t c = 0; c < C; ++c) {
            m.vf[static_cast<size_t>(fill[static_cast<size_t>(m.loop_v[static_cast<size_t>(c)])]++)] =
                m.face_ids[static_cast<size_t>(m.corner_face[static_cast<size_t>(c)])];
        }
    }

    // Step 4b: face -> adjacent faces by face id (faceAdjSets: edges in id order, pairs i < j of each edge's faces
    // in their HashSet order), pair -> shared edge (facePairToEdgeId.put: the later edge wins)
    {
        std::vector<AdjPair> pairs;
        for (int32_t e = 0; e < E; ++e) {
            const int32_t k0 = m.ef_off[static_cast<size_t>(e)];
            const int32_t n = m.ef_off[static_cast<size_t>(e) + 1] - k0;
            for (int32_t i = 0; i < n; ++i) {
                for (int32_t j = i + 1; j < n; ++j) {
                    const int32_t fa = m.ef[static_cast<size_t>(k0 + i)];
                    const int32_t fb = m.ef[static_cast<size_t>(k0 + j)];
                    pairs.push_back({fa, fb, e});
                    pairs.push_back({fb, fa, e});
                }
            }
        }
        std::vector<int32_t>& off = m.slot_of;
        off.assign(static_cast<size_t>(U) + 1, 0);
        for (const AdjPair& p : pairs) ++off[static_cast<size_t>(p.owner) + 1];
        for (int32_t u = 0; u < U; ++u) off[static_cast<size_t>(u) + 1] += off[static_cast<size_t>(u)];
        std::vector<AdjPair> grouped(pairs.size());
        {
            std::vector<int32_t>& fill = m.aux2;
            fill.assign(off.begin(), off.end() - 1);
            for (const AdjPair& p : pairs) grouped[static_cast<size_t>(fill[static_cast<size_t>(p.owner)]++)] = p;
        }
        m.fadj_off.assign(static_cast<size_t>(U) + 1, 0);
        m.fadj.clear();
        m.fadj_e.clear();
        m.ensure_stamp(static_cast<size_t>(U));
        std::vector<int32_t>& pos = m.aux;  // other face id -> index in vals (valid when stamped)
        pos.resize(static_cast<size_t>(U));
        std::vector<int32_t> vals, edges;
        for (int32_t u = 0; u < U; ++u) {
            const int32_t k0 = off[static_cast<size_t>(u)], k1 = off[static_cast<size_t>(u) + 1];
            if (k0 == k1) {
                m.fadj_off[static_cast<size_t>(u) + 1] = static_cast<int32_t>(m.fadj.size());
                continue;
            }
            const int32_t st = m.next_stamp();
            vals.clear();
            edges.clear();
            for (int32_t k = k0; k < k1; ++k) {
                const AdjPair& p = grouped[static_cast<size_t>(k)];
                const auto o = static_cast<size_t>(p.other);
                if (m.stamp[o] == st) {
                    edges[static_cast<size_t>(pos[o])] = p.edge;
                } else {
                    m.stamp[o] = st;
                    pos[o] = static_cast<int32_t>(vals.size());
                    vals.push_back(p.other);
                    edges.push_back(p.edge);
                }
            }
            const auto n = static_cast<int32_t>(vals.size());
            if (!java_hash_order(vals.data(), n, m.perm, m.counts)) ++m.approximate_sets;
            for (int32_t i = 0; i < n; ++i) {
                m.fadj.push_back(vals[static_cast<size_t>(m.perm[static_cast<size_t>(i)])]);
                m.fadj_e.push_back(edges[static_cast<size_t>(m.perm[static_cast<size_t>(i)])]);
            }
            m.fadj_off[static_cast<size_t>(u) + 1] = static_cast<int32_t>(m.fadj.size());
        }
    }

    // Step 7: face geometry by id; dihedral angles + edge kinds
    m.fnorm.assign(static_cast<size_t>(U) * 3, 0.0f);
    m.fcent.assign(static_cast<size_t>(U) * 3, 0.0f);
    m.farea.assign(static_cast<size_t>(U), 0.0f);
    for (int32_t f = 0; f < F; ++f) face_geometry_into(m, f);
    m.edihed.resize(static_cast<size_t>(E));
    m.ekind.resize(static_cast<size_t>(E));
    for (int32_t e = 0; e < E; ++e) classify_edge(m, e);
    m.stages |= CO_MESH_STAGE_TOPOLOGY;
}

int32_t run_build(co_mesh& m, int32_t stages, const char* fn) {
    if (!(m.stages & CO_MESH_STAGE_FACES)) {
        return fail(CO_ERR_ARG, std::string(fn) + ": no faces (co_mesh_import / co_mesh_set_faces first)");
    }
    if ((stages & CO_MESH_STAGE_TOPOLOGY) && m.face_id_upper > kMaxFaceId) {
        return fail(CO_ERR_RANGE, std::string(fn) + ": face id upper bound " + std::to_string(m.face_id_upper) +
                                      " exceeds 2^26 (TOPOLOGY sizes arrays by face id)");
    }
    if (stages & (CO_MESH_STAGE_TRIANGULATE | CO_MESH_STAGE_RENDER)) {
        m.stages &= ~(CO_MESH_STAGE_TRIANGULATE | CO_MESH_STAGE_RENDER);
        build_triangulate(m);
    }
    if (stages & CO_MESH_STAGE_RENDER) build_render(m);
    if (stages & CO_MESH_STAGE_TOPOLOGY) {
        m.stages &= ~CO_MESH_STAGE_TOPOLOGY;
        build_topology(m);
    }
    m.moved_faces.clear();
    return CO_OK;
}

int32_t stage_of(int32_t which) {
    switch (which) {
        case CO_MESH_A_SOUP_TO_VERTEX:
        case CO_MESH_A_VERTEX_SOUP_OFFSETS:
        case CO_MESH_A_VERTEX_SOUP: return CO_MESH_STAGE_WELD;
        case CO_MESH_A_VERTEX_POSITIONS: return CO_MESH_STAGE_WELD | CO_MESH_STAGE_FACES;
        case CO_MESH_A_FACE_IDS:
        case CO_MESH_A_LOOP_OFFSETS:
        case CO_MESH_A_LOOP_VERTICES:
        case CO_MESH_A_LOOP_UVS:
        case CO_MESH_A_FACE_FLAGS:
        case CO_MESH_A_CORNER_FACES:
        case CO_MESH_A_FACE_SLOTS:
        case CO_MESH_A_WARNINGS:
        case CO_MESH_A_MOVED_FACES: return CO_MESH_STAGE_FACES;
        case CO_MESH_A_TRI_INDICES:
        case CO_MESH_A_TRI_FACE_IDS: return CO_MESH_STAGE_TRIANGULATE;
        case CO_MESH_A_CORNER_POSITIONS:
        case CO_MESH_A_CORNER_UVS:
        case CO_MESH_A_VERTEX_CORNER_OFFSETS:
        case CO_MESH_A_VERTEX_CORNERS: return CO_MESH_STAGE_RENDER;
        default: return (which >= 0 && which < CO_MESH_A_COUNT) ? CO_MESH_STAGE_TOPOLOGY : 0;
    }
}

const char* stage_name(int32_t s) {
    switch (s) {
        case CO_MESH_STAGE_WELD: return "WELD";
        case CO_MESH_STAGE_FACES: return "FACES";
        case CO_MESH_STAGE_TRIANGULATE: return "TRIANGULATE";
        case CO_MESH_STAGE_RENDER: return "RENDER";
        case CO_MESH_STAGE_TOPOLOGY: return "TOPOLOGY";
        default: return "WELD or FACES";
    }
}

template <typename T>
int64_t borrow(const std::vector<T>& v, const void** out) {
    if (out != nullptr) *out = v.data();
    return static_cast<int64_t>(v.size());
}

thread_local TriScratch t_tri_scratch;

}  // namespace

extern "C" {

co_mesh* co_mesh_create(void) {
    return guarded("co_mesh_create", []() -> co_mesh* {
        auto* m = new co_mesh();
        clear_error();
        return m;
    });
}

co_mesh* co_mesh_clone(const co_mesh* m) {
    return guarded("co_mesh_clone", [&]() -> co_mesh* {
        if (m == nullptr) {
            fail(CO_ERR_ARG, "co_mesh_clone: null mesh");
            return nullptr;
        }
        auto* c = new co_mesh(*m);
        clear_error();
        return c;
    });
}

void co_mesh_destroy(co_mesh* m) { delete m; }

int32_t co_mesh_info_get(const co_mesh* m, co_mesh_info* out) {
    if (m == nullptr || out == nullptr) return fail(CO_ERR_ARG, "co_mesh_info_get: null argument");
    co_mesh_info i{};
    i.stages = m->stages;
    i.soup_vertex_count = (m->stages & CO_MESH_STAGE_WELD) ? m->soup_count : 0;
    i.vertex_count = m->vertex_count;
    const bool faces = (m->stages & CO_MESH_STAGE_FACES) != 0;
    i.face_count = faces ? face_count(*m) : 0;
    i.face_id_upper_bound = faces ? m->face_id_upper : 0;
    i.corner_count = faces ? corner_count(*m) : 0;
    i.triangle_count = (m->stages & CO_MESH_STAGE_TRIANGULATE) ? static_cast<int32_t>(m->tri_face.size()) : 0;
    i.edge_count = (m->stages & CO_MESH_STAGE_TOPOLOGY) ? static_cast<int32_t>(m->edge_v.size() / 2) : 0;
    i.warning_count = faces ? static_cast<int32_t>(m->warnings.size() / 3) : 0;
    i.uniform = 1;  // TopologyMetadataQuery: no faces leaves the flag set
    if (faces && !m->face_ids.empty()) {
        i.first_face_vertex_count = m->loop_off[1] - m->loop_off[0];
        for (size_t f = 1; f < m->face_ids.size(); ++f) {
            if (m->loop_off[f + 1] - m->loop_off[f] != i.first_face_vertex_count) {
                i.uniform = 0;
                break;
            }
        }
    }
    i.approximate_order_sets = (m->stages & CO_MESH_STAGE_TOPOLOGY) ? m->approximate_sets : 0;
    i.revision = m->revision;
    *out = i;
    clear_error();
    return CO_OK;
}

int64_t co_mesh_array(const co_mesh* m, int32_t which, const void** out_data) {
    if (out_data != nullptr) *out_data = nullptr;
    if (m == nullptr) {
        fail(CO_ERR_ARG, "co_mesh_array: null mesh");
        return -1;
    }
    const int32_t need = stage_of(which);
    if (need == 0) {
        fail(CO_ERR_ARG, "co_mesh_array: unknown array " + std::to_string(which));
        return -1;
    }
    if (!(m->stages & need)) {
        fail(CO_ERR_ARG, std::string("co_mesh_array: array ") + std::to_string(which) + " needs stage " +
                             stage_name(need) + ", which is not current");
        return -1;
    }
    clear_error();
    switch (which) {
        case CO_MESH_A_SOUP_TO_VERTEX: return borrow(m->soup_to_vertex, out_data);
        case CO_MESH_A_VERTEX_SOUP_OFFSETS: return borrow(m->vertex_soup_off, out_data);
        case CO_MESH_A_VERTEX_SOUP: return borrow(m->vertex_soup, out_data);
        case CO_MESH_A_VERTEX_POSITIONS: return borrow(m->positions, out_data);
        case CO_MESH_A_FACE_IDS: return borrow(m->face_ids, out_data);
        case CO_MESH_A_LOOP_OFFSETS: return borrow(m->loop_off, out_data);
        case CO_MESH_A_LOOP_VERTICES: return borrow(m->loop_v, out_data);
        case CO_MESH_A_LOOP_UVS: return borrow(m->loop_uvs, out_data);
        case CO_MESH_A_FACE_FLAGS: return borrow(m->face_flags, out_data);
        case CO_MESH_A_CORNER_FACES: return borrow(m->corner_face, out_data);
        case CO_MESH_A_FACE_SLOTS: return borrow(m->face_slots, out_data);
        case CO_MESH_A_WARNINGS: return borrow(m->warnings, out_data);
        case CO_MESH_A_TRI_INDICES: return borrow(m->tri_idx, out_data);
        case CO_MESH_A_TRI_FACE_IDS: return borrow(m->tri_face, out_data);
        case CO_MESH_A_CORNER_POSITIONS: return borrow(m->corner_pos, out_data);
        case CO_MESH_A_CORNER_UVS: return borrow(m->corner_uv, out_data);
        case CO_MESH_A_VERTEX_CORNER_OFFSETS: return borrow(m->vc_off, out_data);
        case CO_MESH_A_VERTEX_CORNERS: return borrow(m->vc, out_data);
        case CO_MESH_A_EDGE_VERTICES: return borrow(m->edge_v, out_data);
        case CO_MESH_A_EDGE_FACE_OFFSETS: return borrow(m->ef_off, out_data);
        case CO_MESH_A_EDGE_FACES: return borrow(m->ef, out_data);
        case CO_MESH_A_CORNER_EDGES: return borrow(m->corner_edges, out_data);
        case CO_MESH_A_VERTEX_EDGE_OFFSETS: return borrow(m->ve_off, out_data);
        case CO_MESH_A_VERTEX_EDGES: return borrow(m->ve, out_data);
        case CO_MESH_A_VERTEX_FACE_OFFSETS: return borrow(m->vf_off, out_data);
        case CO_MESH_A_VERTEX_FACES: return borrow(m->vf, out_data);
        case CO_MESH_A_FACE_ADJ_OFFSETS: return borrow(m->fadj_off, out_data);
        case CO_MESH_A_FACE_ADJ: return borrow(m->fadj, out_data);
        case CO_MESH_A_FACE_ADJ_EDGES: return borrow(m->fadj_e, out_data);
        case CO_MESH_A_FACE_NORMALS: return borrow(m->fnorm, out_data);
        case CO_MESH_A_FACE_CENTROIDS: return borrow(m->fcent, out_data);
        case CO_MESH_A_FACE_AREAS: return borrow(m->farea, out_data);
        case CO_MESH_A_EDGE_DIHEDRAL: return borrow(m->edihed, out_data);
        case CO_MESH_A_EDGE_KIND: return borrow(m->ekind, out_data);
        case CO_MESH_A_MOVED_FACES: return borrow(m->moved_faces, out_data);
        default: break;
    }
    fail(CO_ERR_ARG, "co_mesh_array: unknown array " + std::to_string(which));
    return -1;
}

int32_t co_mesh_set_mappings(co_mesh* m, const co_face_mapping* mappings, const int32_t* mapping_flags, int32_t count) {
    if (m == nullptr || count < 0 || (count > 0 && mappings == nullptr)) {
        return fail(CO_ERR_ARG, "co_mesh_set_mappings: null mesh or bad mapping array");
    }
    for (int32_t i = 0; i < count; ++i) {
        const int32_t r = mappings[i].uv_rotation_degrees;
        if (r != 0 && r != 90 && r != 180 && r != 270) {
            return fail(CO_ERR_ARG, "co_mesh_set_mappings: Invalid UV rotation: " + std::to_string(r) +
                                        " (must be 0, 90, 180, or 270), face " + std::to_string(mappings[i].face_id));
        }
    }
    return guarded("co_mesh_set_mappings", [&]() -> int32_t {
        m->mappings.assign(mappings, mappings + count);
        if (mapping_flags != nullptr) {
            m->mapping_flags.assign(mapping_flags, mapping_flags + count);
        } else {
            m->mapping_flags.assign(static_cast<size_t>(count), 0);
        }
        ++m->revision;
        clear_error();
        return CO_OK;
    });
}

int32_t co_mesh_weld(co_mesh* m, const float* positions, int64_t soup_vertex_count, float epsilon) {
    constexpr const char* fn = "co_mesh_weld";
    if (m == nullptr || soup_vertex_count < 0 || (soup_vertex_count > 0 && positions == nullptr)) {
        return fail(CO_ERR_ARG, std::string(fn) + ": null mesh / positions or negative count");
    }
    if (soup_vertex_count > INT32_MAX / 3) return fail(CO_ERR_ARG, std::string(fn) + ": soup too large");
    if (!(epsilon > 0)) epsilon = CO_MESH_DEFAULT_WELD_EPSILON;
    return guarded(fn, [&]() -> int32_t {
        weld(*m, positions, static_cast<int32_t>(soup_vertex_count), epsilon);
        ++m->revision;
        clear_error();
        return CO_OK;
    });
}

int32_t co_mesh_import(co_mesh* m, const float* positions, int64_t soup_vertex_count, const float* tex_coords,
                       int64_t tex_coord_count, const int32_t* indices, int64_t index_count,
                       const int32_t* tri_face_ids, int64_t tri_face_id_count, float epsilon, int32_t stages) {
    constexpr const char* fn = "co_mesh_import";
    if (m == nullptr) return fail(CO_ERR_ARG, std::string(fn) + ": null mesh");
    if (soup_vertex_count < 0 || index_count < 0 || tex_coord_count < 0 || tri_face_id_count < 0) {
        return fail(CO_ERR_ARG, std::string(fn) + ": negative count");
    }
    if ((soup_vertex_count > 0 && positions == nullptr) || (index_count > 0 && indices == nullptr) ||
        (tex_coord_count > 0 && tex_coords == nullptr) || (tri_face_id_count > 0 && tri_face_ids == nullptr)) {
        return fail(CO_ERR_ARG, std::string(fn) + ": null array with a positive count");
    }
    if (soup_vertex_count > INT32_MAX / 3 || index_count > INT32_MAX) {
        return fail(CO_ERR_ARG, std::string(fn) + ": soup too large for int32 corner ids");
    }
    if (tri_face_ids != nullptr) {
        // arrays by face id are sized by the upper bound (Java: per-id arrays in the topology, faceId + 1 wraps at
        // Integer.MAX_VALUE); refused before anything changes
        const int64_t n = std::min(tri_face_id_count, index_count / 3);
        for (int64_t t = 0; t < n; ++t) {
            if (tri_face_ids[t] >= kMaxFaceId) {
                return fail(CO_ERR_RANGE, std::string(fn) + ": face id " + std::to_string(tri_face_ids[t]) +
                                              " (triangle " + std::to_string(t) + ") >= 2^26");
            }
        }
    }
    if (index_count / 3 > kMaxFaceId && (tri_face_ids == nullptr || tri_face_id_count < index_count / 3)) {
        return fail(CO_ERR_RANGE, std::string(fn) + ": more than 2^26 triangles without face ids");
    }
    if (!(epsilon > 0)) epsilon = CO_MESH_DEFAULT_WELD_EPSILON;
    return guarded(fn, [&]() -> int32_t {
        import_soup(*m, positions, static_cast<int32_t>(soup_vertex_count), tex_coords, tex_coord_count, indices,
                    index_count, tri_face_ids, tri_face_id_count, epsilon);
        ++m->revision;
        if (stages & kBuildStages) {
            const int32_t r = run_build(*m, stages, fn);
            if (r != CO_OK) return r;  // WELD + FACES stay current; the failed stage is not
        }
        clear_error();
        return CO_OK;
    });
}

int32_t co_mesh_set_faces(co_mesh* m, const float* positions, int32_t vertex_count, const int32_t* face_ids,
                          int32_t face_count, const int32_t* loop_offsets, const int32_t* loop_vertices,
                          const int32_t* face_has_uvs, const float* corner_uvs, int32_t face_id_upper_bound,
                          int32_t stages) {
    constexpr const char* fn = "co_mesh_set_faces";
    if (m == nullptr || vertex_count < 0 || face_count < 0 || (vertex_count > 0 && positions == nullptr) ||
        (face_count > 0 && (face_ids == nullptr || loop_offsets == nullptr || loop_vertices == nullptr))) {
        return fail(CO_ERR_ARG, std::string(fn) + ": null mesh / array or negative count");
    }
    if (vertex_count > INT32_MAX / 3) return fail(CO_ERR_ARG, std::string(fn) + ": too many vertices");
    return guarded(fn, [&]() -> int32_t {
        if (face_count > 0 && loop_offsets[0] != 0) {
            return fail(CO_ERR_ARG, std::string(fn) + ": loop_offsets[0] must be 0");
        }
        int32_t upper = 0;
        bool any_uvs = false;
        // EditableMesh.addFaceWithId / validatedLoop, checked before anything changes
        std::vector<int32_t> seen(static_cast<size_t>(vertex_count), -1);
        std::vector<uint8_t> id_used;
        for (int32_t f = 0; f < face_count; ++f) {
            const int32_t id = face_ids[f];
            if (id < 0) return fail(CO_ERR_ARG, std::string(fn) + ": Face id must be >= 0: " + std::to_string(id));
            if (id >= kMaxFaceId) {
                return fail(CO_ERR_RANGE, std::string(fn) + ": face id " + std::to_string(id) + " >= 2^26");
            }
            if (id_used.size() <= static_cast<size_t>(id)) id_used.resize(static_cast<size_t>(id) + 1, 0);
            if (id_used[static_cast<size_t>(id)]) {
                return fail(CO_ERR_ARG, std::string(fn) + ": Face id already in use: " + std::to_string(id));
            }
            id_used[static_cast<size_t>(id)] = 1;
            upper = std::max(upper, id + 1);
            const int32_t b = loop_offsets[f], e = loop_offsets[f + 1];
            if (e < b || e - b < 3) {
                return fail(CO_ERR_ARG, std::string(fn) + ": Face loop must have >= 3 vertices (face " +
                                            std::to_string(id) + ")");
            }
            for (int32_t k = b; k < e; ++k) {
                const int32_t v = loop_vertices[k];
                if (v < 0 || v >= vertex_count) {
                    return fail(CO_ERR_RANGE,
                                std::string(fn) + ": Loop references invalid vertex id " + std::to_string(v));
                }
                if (seen[static_cast<size_t>(v)] == f) {
                    return fail(CO_ERR_ARG, std::string(fn) + ": Loop repeats vertex id " + std::to_string(v));
                }
                seen[static_cast<size_t>(v)] = f;
            }
            if (face_has_uvs != nullptr && face_has_uvs[f] != 0) any_uvs = true;
        }
        if (any_uvs && corner_uvs == nullptr) {
            return fail(CO_ERR_ARG, std::string(fn) + ": face_has_uvs set but corner_uvs is NULL");
        }
        const int32_t C = face_count > 0 ? loop_offsets[face_count] : 0;
        m->stages = 0;
        m->soup_count = 0;
        m->soup_to_vertex.clear();
        m->vertex_soup_off.clear();
        m->vertex_soup.clear();
        m->vertex_count = vertex_count;
        m->positions.assign(positions, positions + static_cast<size_t>(vertex_count) * 3);
        m->face_ids.assign(face_ids, face_ids + face_count);
        if (face_count > 0) {
            m->loop_off.assign(loop_offsets, loop_offsets + face_count + 1);
        } else {
            m->loop_off.assign(1, 0);
        }
        m->loop_v.assign(loop_vertices, loop_vertices + C);
        m->loop_uvs.assign(static_cast<size_t>(C) * 2, 0.0f);
        m->face_flags.assign(static_cast<size_t>(face_count), 0);
        for (int32_t f = 0; f < face_count; ++f) {
            if (face_has_uvs != nullptr && face_has_uvs[f] != 0) {
                m->face_flags[static_cast<size_t>(f)] = CO_MESH_FACE_AUTHORED_UVS;
                const int32_t b = loop_offsets[f], e = loop_offsets[f + 1];
                std::memcpy(&m->loop_uvs[static_cast<size_t>(b) * 2], corner_uvs + static_cast<size_t>(b) * 2,
                            static_cast<size_t>(e - b) * 2 * sizeof(float));
            }
        }
        m->warnings.clear();
        m->face_id_upper = std::max(upper, std::min(face_id_upper_bound, kMaxFaceId));
        finish_faces(*m);
        ++m->revision;
        if (stages & kBuildStages) {
            const int32_t r = run_build(*m, stages, fn);
            if (r != CO_OK) return r;
        }
        clear_error();
        return CO_OK;
    });
}

int32_t co_mesh_build(co_mesh* m, int32_t stages) {
    constexpr const char* fn = "co_mesh_build";
    if (m == nullptr) return fail(CO_ERR_ARG, std::string(fn) + ": null mesh");
    return guarded(fn, [&]() -> int32_t {
        const int32_t r = run_build(*m, stages, fn);
        if (r != CO_OK) return r;
        ++m->revision;
        clear_error();
        return CO_OK;
    });
}

int32_t co_mesh_move_vertices(co_mesh* m, const int32_t* vertex_ids, const float* xyz, int64_t count,
                              int32_t* out_changes) {
    constexpr const char* fn = "co_mesh_move_vertices";
    if (out_changes != nullptr) *out_changes = 0;
    if (m == nullptr || count < 0 || (count > 0 && (vertex_ids == nullptr || xyz == nullptr))) {
        return fail(CO_ERR_ARG, std::string(fn) + ": null mesh / array or negative count");
    }
    if (!(m->stages & CO_MESH_STAGE_FACES)) return fail(CO_ERR_ARG, std::string(fn) + ": no faces");
    for (int64_t i = 0; i < count; ++i) {
        if (vertex_ids[i] < 0 || vertex_ids[i] >= m->vertex_count) {
            return fail(CO_ERR_RANGE,
                        std::string(fn) + ": vertex id " + std::to_string(vertex_ids[i]) + " out of range");
        }
    }
    return guarded(fn, [&]() -> int32_t {
        co_mesh& mm = *m;
        const bool tri = (mm.stages & CO_MESH_STAGE_TRIANGULATE) != 0;
        const bool render = (mm.stages & CO_MESH_STAGE_RENDER) != 0;
        const bool topo = (mm.stages & CO_MESH_STAGE_TOPOLOGY) != 0;
        int32_t changes = 0;
        for (int64_t i = 0; i < count; ++i) {
            std::memcpy(&mm.positions[static_cast<size_t>(vertex_ids[i]) * 3], xyz + i * 3, 3 * sizeof(float));
        }
        // the touched faces: every face using a moved vertex (vertex -> corners when RENDER is current, else the
        // topology's vertex -> faces, else one corner scan)
        mm.moved_faces.clear();
        const int32_t F = face_count(mm);
        mm.ensure_stamp(static_cast<size_t>(std::max(F, mm.vertex_count)));
        const int32_t fst = mm.next_stamp();
        auto touch = [&](int32_t f) {
            if (mm.stamp[static_cast<size_t>(f)] != fst) {
                mm.stamp[static_cast<size_t>(f)] = fst;
                mm.moved_faces.push_back(f);
            }
        };
        if (render) {
            for (int64_t i = 0; i < count; ++i) {
                const auto v = static_cast<size_t>(vertex_ids[i]);
                for (int32_t k = mm.vc_off[v]; k < mm.vc_off[v + 1]; ++k) {
                    const int32_t c = mm.vc[static_cast<size_t>(k)];
                    std::memcpy(&mm.corner_pos[static_cast<size_t>(c) * 3], &mm.positions[v * 3], 3 * sizeof(float));
                    touch(mm.corner_face[static_cast<size_t>(c)]);
                }
            }
        } else if (topo) {
            for (int64_t i = 0; i < count; ++i) {
                const auto v = static_cast<size_t>(vertex_ids[i]);
                for (int32_t k = mm.vf_off[v]; k < mm.vf_off[v + 1]; ++k) {
                    touch(mm.face_slots[static_cast<size_t>(mm.vf[static_cast<size_t>(k)])]);
                }
            }
        } else if (count > 0) {
            std::vector<uint8_t> moved(static_cast<size_t>(mm.vertex_count), 0);
            for (int64_t i = 0; i < count; ++i) moved[static_cast<size_t>(vertex_ids[i])] = 1;
            const int32_t C = corner_count(mm);
            for (int32_t c = 0; c < C; ++c) {
                if (moved[static_cast<size_t>(mm.loop_v[static_cast<size_t>(c)])]) {
                    touch(mm.corner_face[static_cast<size_t>(c)]);
                }
            }
        }
        std::sort(mm.moved_faces.begin(), mm.moved_faces.end());
        if (count > 0) changes |= CO_MESH_CHANGED_POSITIONS;
        if (render) {
            index_mappings(mm);
            for (int32_t f : mm.moved_faces) {
                if (face_uvs(mm, f)) changes |= CO_MESH_CHANGED_UVS;
            }
        }
        if (tri) {
            for (int32_t f : mm.moved_faces) {
                if (triangulate_face(mm, f)) changes |= CO_MESH_CHANGED_INDICES;
            }
        }
        if (topo && !mm.moved_faces.empty()) {
            for (int32_t f : mm.moved_faces) face_geometry_into(mm, f);
            std::vector<int32_t>& edges = mm.aux2;
            edges.clear();
            for (int32_t f : mm.moved_faces) {
                for (int32_t c = mm.loop_off[static_cast<size_t>(f)]; c < mm.loop_off[static_cast<size_t>(f) + 1]; ++c) {
                    edges.push_back(mm.corner_edges[static_cast<size_t>(c)]);
                }
            }
            std::sort(edges.begin(), edges.end());
            edges.erase(std::unique(edges.begin(), edges.end()), edges.end());
            for (int32_t e : edges) classify_edge(mm, e);
            changes |= CO_MESH_CHANGED_TOPOLOGY;
        }
        ++mm.revision;
        if (out_changes != nullptr) *out_changes = changes;
        clear_error();
        return CO_OK;
    });
}

int32_t co_mesh_find_edge(const co_mesh* m, int32_t a, int32_t b) {
    if (m == nullptr || !(m->stages & CO_MESH_STAGE_TOPOLOGY)) return -1;
    const int32_t lo = std::min(a, b), hi = std::max(a, b);
    if (lo < 0 || hi >= m->vertex_count || lo == hi) return -1;
    for (int32_t k = m->edge_lo_off[static_cast<size_t>(lo)]; k < m->edge_lo_off[static_cast<size_t>(lo) + 1]; ++k) {
        const int32_t e = m->edge_by_lo[static_cast<size_t>(k)];
        if (m->edge_v[static_cast<size_t>(e) * 2 + 1] == hi) return e;
    }
    return -1;
}

int32_t co_polygon_triangulate(const float* xyz, int32_t n, int32_t* out_indices, float* out_newell) {
    constexpr const char* fn = "co_polygon_triangulate";
    if (xyz == nullptr || out_indices == nullptr || n < 3) {
        return fail(CO_ERR_ARG, std::string(fn) + ": Polygon needs >= 3 vertices and non-null arrays");
    }
    return guarded(fn, [&]() -> int32_t {
        TriScratch& s = t_tri_scratch;
        s.loop.resize(static_cast<size_t>(n));
        for (int32_t i = 0; i < n; ++i) s.loop[static_cast<size_t>(i)] = {xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]};
        const int32_t r = triangulate(s.loop.data(), n, out_indices, s);
        if (out_newell != nullptr) {
            const V3 nw = newell_double(s.loop.data(), n);
            out_newell[0] = nw.x;
            out_newell[1] = nw.y;
            out_newell[2] = nw.z;
        }
        clear_error();
        return r;
    });
}

}  // extern "C"
