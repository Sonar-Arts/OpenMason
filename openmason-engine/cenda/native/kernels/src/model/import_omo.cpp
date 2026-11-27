// Model import, WRITER half (OpenMason #173): an omformat::import::Scene (glTF 2.0 / OBJ, parsed by libomformat)
// converted to an OMO 1.9 document + OMANIM 1.2 clips. The conversion rules are documented in
// cenda/model_import.h; this file implements them. No exception leaves the co_import_* entry points.
#include "model_internal.hpp"
#include "cenda/model_import.h"

#include "import_math.hpp"
// libomformat's private pixel access (decode + the importer's PNG encoder); not on any include path on purpose.
#include "../../../../libomformat/src/import/image_resize.hpp"
#include "omformat/import.hpp"
#include "omformat/omanim.hpp"
#include "omformat/omo.hpp"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <exception>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iterator>
#include <map>
#include <memory>
#include <new>
#include <optional>
#include <set>
#include <span>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

namespace imp = cenda::omformat::import;
namespace omo = cenda::omformat::omo;
namespace oa = cenda::omformat::omanim;
using namespace cenda::model::import_math;

struct co_import_result {
    co_doc* doc = nullptr;
    struct Clip {
        std::string name;
        std::vector<std::byte> bytes;
    };
    std::vector<Clip> clips;
    std::vector<std::string> warnings;
    co_import_counts counts{};

    co_import_result() = default;
    co_import_result(const co_import_result&) = delete;
    co_import_result& operator=(const co_import_result&) = delete;
    ~co_import_result() {
        if (doc != nullptr) co_doc_destroy(doc);
    }
};

static_assert(sizeof(co_import_options) == 24);
static_assert(sizeof(co_import_counts) == 48);

namespace {

std::string& last_error() { return cenda::model::co_error_message(); }   // the one store behind co_last_error

int32_t fail_code(int32_t code, std::string message) {
    last_error() = std::move(message);
    return code;
}

std::size_t sz(int v) { return static_cast<std::size_t>(v); }

// ---- small quaternion helpers -----------------------------------------------------------------------------

Q qmul(Q a, Q b) {
    return {a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y, a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
            a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w, a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z};
}

Q qconj(Q q) { return {-q.x, -q.y, -q.z, q.w}; }

// ---- options / conversion space ---------------------------------------------------------------------------

struct Options {
    double unit = 1.0;
    bool flip = true;  // 180 degrees about +Y
    uint32_t flags = CO_IMPORT_DEFAULT_FLAGS;
    int32_t max_texture = 0;
    double quad_cos = std::cos(1.0 * kDegToRad);
};

// The source -> Open Mason map C = Ry(180) · unit (or unit alone).
struct Space {
    double unit = 1.0;
    bool flip = true;
    M4 rot;  // Ry(180) or I
    M4 c, cinv;

    explicit Space(const Options& o) : unit(o.unit), flip(o.flip) {
        if (flip) {
            rot.at(0, 0) = -1.0;
            rot.at(2, 2) = -1.0;
        }
        c = rot * scaling({unit, unit, unit});
        cinv = c.affine_inverse();
    }

    V3 point(V3 p) const { return c.point(p); }
    Q quat(Q q) const { return flip ? Q{-q.x, q.y, -q.z, q.w} : q; }
    M4 conj(const M4& m) const { return c * m * cinv; }
};

struct TRS {
    V3 t;
    Q q;
    V3 s{1.0, 1.0, 1.0};
};

V3 v3(const std::array<float, 3>& a) { return {a[0], a[1], a[2]}; }

// Rest TRS of a node: its TRS components, or its matrix decomposed (translation, column scales, rotation).
TRS rest_trs(const imp::Node& n) {
    TRS r;
    if (n.matrix && !n.translation && !n.rotation && !n.scale) {
        const M4 m = M4::from_float(*n.matrix);
        r.t = m.translation();
        r.s = {length(m.col(0)), length(m.col(1)), length(m.col(2))};
        if (m.det3() < 0.0) r.s.x = -r.s.x;
        M4 rm;
        for (int c = 0; c < 3; ++c) {
            const double sc = c == 0 ? r.s.x : (c == 1 ? r.s.y : r.s.z);
            const V3 unit_axis{c == 0 ? 1.0 : 0.0, c == 1 ? 1.0 : 0.0, c == 2 ? 1.0 : 0.0};
            rm.set_col(c, std::fabs(sc) > 0.0 ? m.col(c) * (1.0 / sc) : unit_axis);
        }
        r.q = quat_of(rm);
        return r;
    }
    if (n.translation) r.t = v3(*n.translation);
    if (n.rotation) r.q = qnormalized({(*n.rotation)[0], (*n.rotation)[1], (*n.rotation)[2], (*n.rotation)[3]});
    if (n.scale) r.s = v3(*n.scale);
    return r;
}

// Orthonormal frame (rotation + translation) of an affine matrix: Gram-Schmidt on the columns, right-handed.
struct Frame {
    M4 w;
    double sigma = 1.0;   // mean column length
    bool uniform = true;  // columns equal in length and orthogonal
    bool mirrored = false;
};

Frame frame_of(const M4& g) {
    Frame f;
    const V3 c0 = g.col(0), c1 = g.col(1), c2 = g.col(2);
    const double l0 = length(c0), l1 = length(c1), l2 = length(c2);
    f.sigma = (l0 + l1 + l2) / 3.0;
    if (!(f.sigma > 0.0)) f.sigma = 1.0;
    const double tol = 1e-4 * f.sigma;
    f.uniform = std::fabs(l0 - f.sigma) < tol && std::fabs(l1 - f.sigma) < tol && std::fabs(l2 - f.sigma) < tol &&
                std::fabs(dot(c0, c1)) < tol * f.sigma && std::fabs(dot(c0, c2)) < tol * f.sigma &&
                std::fabs(dot(c1, c2)) < tol * f.sigma;
    V3 x = normalized(c0);
    if (length(x) == 0.0) x = {1, 0, 0};
    V3 y = normalized(c1 - x * dot(x, c1));
    if (length(y) == 0.0) y = normalized(cross({0, 0, 1}, x));
    if (length(y) == 0.0) y = {0, 1, 0};
    const V3 z = cross(x, y);
    f.mirrored = dot(z, c2) < 0.0;
    f.w.set_col(0, x);
    f.w.set_col(1, y);
    f.w.set_col(2, z);
    f.w.set_col(3, g.translation());
    return f;
}

// Deterministic UUID-shaped ids (the editor keys parts / bones by id; only uniqueness matters).
std::string make_id(char kind, std::size_t index, const std::string& name) {
    std::uint64_t h = 1469598103934665603ull;
    auto mix = [&](unsigned char ch) {
        h ^= ch;
        h *= 1099511628211ull;
    };
    mix(static_cast<unsigned char>(kind));
    for (std::size_t i = 0; i < 8; ++i) mix(static_cast<unsigned char>((index >> (i * 8)) & 0xffu));
    for (char ch : name) mix(static_cast<unsigned char>(ch));
    char buf[48];
    std::snprintf(buf, sizeof buf, "%08x-%04x-4%03x-%04x-%012llx", static_cast<unsigned>(index & 0xffffffffu),
                  static_cast<unsigned>(kind == 'b' ? 0xb0e5u : 0x9a27u), static_cast<unsigned>((h >> 52) & 0xfffu),
                  static_cast<unsigned>(0x8000u | ((h >> 36) & 0x3fffu)),
                  static_cast<unsigned long long>(h & 0xffffffffffffull));
    return buf;
}

std::string lower(std::string s) {
    for (char& ch : s) ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
    return s;
}

// ---- texture downscale ------------------------------------------------------------------------------------------

// The size that fits max_side, aspect kept: the longer side = max_side, the other rounded (half up), >= 1.
std::pair<int, int> fitted_size(int w, int h, int max_side) {
    const std::int64_t m = max_side;
    if (w >= h) return {max_side, static_cast<int>(std::max<std::int64_t>(1, (std::int64_t{h} * m * 2 + w) / (2 * std::int64_t{w})))};
    return {static_cast<int>(std::max<std::int64_t>(1, (std::int64_t{w} * m * 2 + h) / (2 * std::int64_t{h}))), max_side};
}

// Per destination pixel of one axis: the source pixels it covers and their exact overlap lengths (area filter).
struct Coverage {
    std::vector<std::int32_t> first, count;
    std::vector<std::int32_t> src;
    std::vector<double> weight;
};

Coverage coverage(int src_len, int dst_len) {
    Coverage c;
    const double ratio = static_cast<double>(src_len) / dst_len;
    for (int d = 0; d < dst_len; ++d) {
        const double a = d * ratio, b = d + 1 == dst_len ? static_cast<double>(src_len) : (d + 1) * ratio;
        c.first.push_back(static_cast<std::int32_t>(c.src.size()));
        const int i0 = std::max(0, static_cast<int>(std::floor(a)));
        const int i1 = std::min(src_len, static_cast<int>(std::ceil(b)));
        for (int i = i0; i < i1; ++i) {
            const double w = std::min(b, i + 1.0) - std::max(a, static_cast<double>(i));
            if (w <= 0.0) continue;
            c.src.push_back(i);
            c.weight.push_back(w);
        }
        c.count.push_back(static_cast<std::int32_t>(c.src.size()) - c.first.back());
    }
    return c;
}

// Area (box) downscale of 8-bit pixels (channels 1..4: grey, grey + alpha, RGB, RGBA) in LINEAR light: colour
// samples are sRGB-decoded, every source pixel weighted by its exact overlap with the destination pixel and by its
// alpha (premultiplied, so invisible pixels lend no colour; all-transparent areas keep the plain average), then
// sRGB-encoded; alpha is averaged by overlap and stored straight. Separable: each output row accumulates the
// horizontally filtered source rows it covers.
std::vector<unsigned char> downscale_area(const std::vector<unsigned char>& src, int sw, int sh, int ch, int dw,
                                          int dh) {
    const int cc = ch >= 3 ? 3 : 1;
    const bool alpha = ch == 2 || ch == 4;
    std::array<double, 256> lin{};
    for (int i = 0; i < 256; ++i) lin[sz(i)] = srgb_to_linear(i / 255.0);
    const Coverage cx = coverage(sw, dw), cy = coverage(sh, dh);
    // Row accumulators per destination column: premultiplied colour (cc), plain colour (cc), alpha, weight.
    const std::size_t k = sz(2 * cc + 2);
    std::vector<double> row(sz(dw) * k), acc(sz(dw) * k);
    std::vector<unsigned char> out(sz(dw) * sz(dh) * sz(ch));
    for (int y = 0; y < dh; ++y) {
        std::fill(acc.begin(), acc.end(), 0.0);
        for (int e = cy.first[sz(y)]; e < cy.first[sz(y)] + cy.count[sz(y)]; ++e) {
            const unsigned char* line = src.data() + sz(cy.src[sz(e)]) * sz(sw) * sz(ch);
            const double wy = cy.weight[sz(e)];
            std::fill(row.begin(), row.end(), 0.0);
            for (int x = 0; x < dw; ++x) {
                double* r = row.data() + sz(x) * k;
                for (int f = cx.first[sz(x)]; f < cx.first[sz(x)] + cx.count[sz(x)]; ++f) {
                    const unsigned char* p = line + sz(cx.src[sz(f)]) * sz(ch);
                    const double w = cx.weight[sz(f)];
                    const double a = alpha ? p[ch - 1] / 255.0 : 1.0;
                    for (int c = 0; c < cc; ++c) {
                        const double v = lin[p[c]];
                        r[c] += w * a * v;
                        r[cc + c] += w * v;
                    }
                    r[2 * cc] += w * a;
                    r[2 * cc + 1] += w;
                }
            }
            for (std::size_t i = 0; i < acc.size(); ++i) acc[i] += wy * row[i];
        }
        for (int x = 0; x < dw; ++x) {
            const double* r = acc.data() + sz(x) * k;
            unsigned char* o = out.data() + (sz(y) * sz(dw) + sz(x)) * sz(ch);
            const double wsum = r[2 * cc + 1];
            const double asum = r[2 * cc];
            for (int c = 0; c < cc; ++c) {
                const double v = asum > 0.0 ? r[c] / asum : (wsum > 0.0 ? r[cc + c] / wsum : 0.0);
                o[c] = static_cast<unsigned char>(std::lround(linear_to_srgb(v) * 255.0));
            }
            if (alpha) {
                const double a = wsum > 0.0 ? asum / wsum : 0.0;
                o[ch - 1] = static_cast<unsigned char>(std::lround(std::clamp(a, 0.0, 1.0) * 255.0));
            }
        }
    }
    return out;
}

// ---- polygons -----------------------------------------------------------------------------------------------

V3 newell(const std::vector<V3>& p) {
    V3 n;
    for (std::size_t i = 0; i < p.size(); ++i) {
        const V3& a = p[i];
        const V3& b = p[(i + 1) % p.size()];
        n.x += (a.y - b.y) * (a.z + b.z);
        n.y += (a.z - b.z) * (a.x + b.x);
        n.z += (a.x - b.x) * (a.y + b.y);
    }
    return n;
}

using Tri = std::array<std::uint32_t, 3>;

// Ear clipping of one polygon (indices into the loop); a fan for what it cannot clip.
std::vector<Tri> triangulate(const std::vector<V3>& p) {
    const std::size_t n = p.size();
    std::vector<Tri> out;
    if (n < 3) return out;
    auto fan = [&out](const std::vector<std::uint32_t>& rest) {
        for (std::size_t i = 1; i + 1 < rest.size(); ++i) out.push_back({rest[0], rest[i], rest[i + 1]});
    };
    std::vector<std::uint32_t> rest(n);
    for (std::size_t i = 0; i < n; ++i) rest[i] = static_cast<std::uint32_t>(i);
    if (n == 3) {
        out.push_back({0, 1, 2});
        return out;
    }
    const V3 nrm = newell(p);
    const double ax = std::fabs(nrm.x), ay = std::fabs(nrm.y), az = std::fabs(nrm.z);
    if (!(ax + ay + az > 0.0)) {
        fan(rest);
        return out;
    }
    // Project onto the plane of the largest normal component.
    std::vector<std::array<double, 2>> q(n);
    for (std::size_t i = 0; i < n; ++i) {
        if (az >= ax && az >= ay) q[i] = {p[i].x, p[i].y};
        else if (ay >= ax) q[i] = {p[i].z, p[i].x};
        else q[i] = {p[i].y, p[i].z};
    }
    double area = 0.0;
    for (std::size_t i = 0; i < n; ++i) {
        const auto& a = q[i];
        const auto& b = q[(i + 1) % n];
        area += a[0] * b[1] - b[0] * a[1];
    }
    const double orient = area >= 0.0 ? 1.0 : -1.0;
    auto cross2 = [&q, orient](std::uint32_t a, std::uint32_t b, std::uint32_t c) {
        return ((q[b][0] - q[a][0]) * (q[c][1] - q[a][1]) - (q[b][1] - q[a][1]) * (q[c][0] - q[a][0])) * orient;
    };
    const double eps = 1e-12 * std::fabs(area);
    while (rest.size() > 3) {
        bool clipped = false;
        const std::size_t m = rest.size();
        for (std::size_t i = 0; i < m; ++i) {
            const std::uint32_t a = rest[(i + m - 1) % m], b = rest[i], c = rest[(i + 1) % m];
            if (cross2(a, b, c) <= eps) continue;  // reflex or degenerate
            bool inside = false;
            for (std::uint32_t v : rest) {
                if (v == a || v == b || v == c) continue;
                if (cross2(a, b, v) >= -eps && cross2(b, c, v) >= -eps && cross2(c, a, v) >= -eps) {
                    inside = true;
                    break;
                }
            }
            if (inside) continue;
            out.push_back({a, b, c});
            rest.erase(rest.begin() + static_cast<std::ptrdiff_t>(i));
            clipped = true;
            break;
        }
        if (!clipped) {
            fan(rest);
            return out;
        }
    }
    out.push_back({rest[0], rest[1], rest[2]});
    return out;
}

// A face: its corners (primitive vertex indices, in loop order) and its triangles (indices into the loop).
struct Face {
    std::vector<std::uint32_t> loop;
    std::vector<Tri> tris;
    bool smooth = false;
};

// glTF triangles -> faces, pairing coplanar convex neighbours into quads.
std::vector<Face> pair_triangles(const std::vector<std::uint32_t>& idx, const std::vector<V3>& pos, bool merge,
                                 double cos_tol) {
    const std::size_t nt = idx.size() / 3;
    auto vtx = [&idx](std::size_t t, std::size_t k) { return idx[t * 3 + k % 3]; };
    std::vector<Face> faces;
    faces.reserve(nt);
    std::vector<V3> normal(nt);
    std::vector<char> valid(nt, 0);
    for (std::size_t t = 0; t < nt; ++t) {
        const V3 a = pos[vtx(t, 0)], b = pos[vtx(t, 1)], c = pos[vtx(t, 2)];
        normal[t] = cross(b - a, c - a);
        valid[t] = length(normal[t]) > 0.0 ? 1 : 0;
    }
    std::vector<std::int64_t> partner(nt, -1);
    std::vector<int> shared(nt, -1);  // the edge slot (0..2) of t shared with its partner (first of the pair)
    if (merge && nt >= 2) {
        std::unordered_map<std::uint64_t, std::uint32_t> edges;
        edges.reserve(nt * 3);
        auto key = [](std::uint32_t a, std::uint32_t b) { return (static_cast<std::uint64_t>(a) << 32) | b; };
        for (std::size_t t = 0; t < nt; ++t) {
            for (std::size_t k = 0; k < 3; ++k) edges.emplace(key(vtx(t, k), vtx(t, k + 1)), static_cast<std::uint32_t>(t));
        }
        for (std::size_t t = 0; t < nt; ++t) {
            if (partner[t] >= 0 || !valid[t]) continue;
            std::array<std::size_t, 3> order{0, 1, 2};
            std::array<double, 3> len{};
            for (std::size_t k = 0; k < 3; ++k) len[k] = length(pos[vtx(t, k + 1)] - pos[vtx(t, k)]);
            std::stable_sort(order.begin(), order.end(), [&len](std::size_t a, std::size_t b) { return len[a] > len[b]; });
            for (std::size_t k : order) {
                const std::uint32_t a = vtx(t, k), b = vtx(t, k + 1), c = vtx(t, k + 2);
                const auto it = edges.find(key(b, a));
                if (it == edges.end()) continue;
                const std::size_t u = it->second;
                if (u == t || partner[u] >= 0 || !valid[u]) continue;
                std::uint32_t d = 0;
                bool found = false;
                for (std::size_t j = 0; j < 3; ++j) {
                    const std::uint32_t v = vtx(u, j);
                    if (v != a && v != b) {
                        d = v;
                        found = true;
                    }
                }
                if (!found || d == c) continue;
                const double nn = length(normal[t]) * length(normal[u]);
                if (dot(normal[t], normal[u]) < cos_tol * nn) continue;
                // Loop a, d, b, c must be strictly convex.
                const std::array<V3, 4> qd{pos[a], pos[d], pos[b], pos[c]};
                bool convex = true;
                for (std::size_t i = 0; i < 4 && convex; ++i) {
                    const V3 e1 = qd[(i + 1) % 4] - qd[i];
                    const V3 e2 = qd[(i + 2) % 4] - qd[(i + 1) % 4];
                    if (dot(cross(e1, e2), normal[t]) <= 1e-9 * length(e1) * length(e2) * length(normal[t])) {
                        convex = false;
                    }
                }
                if (!convex) continue;
                partner[t] = static_cast<std::int64_t>(u);
                partner[u] = static_cast<std::int64_t>(t);
                shared[t] = static_cast<int>(k);
                break;
            }
        }
    }
    for (std::size_t t = 0; t < nt; ++t) {
        Face f;
        if (partner[t] < 0) {
            f.loop = {vtx(t, 0), vtx(t, 1), vtx(t, 2)};
            f.tris = {Tri{0, 1, 2}};
        } else if (shared[t] >= 0) {
            const std::size_t u = static_cast<std::size_t>(partner[t]);
            const std::size_t k = static_cast<std::size_t>(shared[t]);
            const std::uint32_t a = vtx(t, k), b = vtx(t, k + 1), c = vtx(t, k + 2);
            std::uint32_t d = 0;
            std::size_t a_slot = 0;
            for (std::size_t j = 0; j < 3; ++j) {
                const std::uint32_t v = vtx(u, j);
                if (v != a && v != b) d = v;
                if (v == a) a_slot = j;
            }
            f.loop = {a, d, b, c};           // loop slots: a 0, d 1, b 2, c 3
            f.tris.push_back(Tri{0, 2, 3});  // t = (a, b, c)
            // u in its own winding, starting at a: (a, d, b).
            Tri us{};
            for (std::size_t j = 0; j < 3; ++j) {
                const std::uint32_t v = vtx(u, a_slot + j);
                us[j] = v == a ? 0u : (v == b ? 2u : 1u);
            }
            f.tris.push_back(us);
        } else {
            continue;  // the second triangle of a pair: emitted with its partner
        }
        faces.push_back(std::move(f));
    }
    return faces;
}

// ---- the converter ------------------------------------------------------------------------------------------

struct BoneB {
    int node = -1;
    int parent = -1;  // bone index
    std::string id, name;
    M4 w;  // rest world (rigid)
    double sigma = 1.0;
    double sigma_parent = 1.0;
    M4 p;      // converted static chain between the parent joint and this joint
    TRS rest;  // converted rest local TRS of the node
    M4 rel_rot;  // rotation of F(parent)^-1 · W (rest)
    V3 origin, rot, endpoint;
};

struct MorphRows {
    std::vector<std::int32_t> vertices;
    std::vector<float> deltas;
    std::vector<float> normals;  // 3 per row (zeros where the source had none)
    bool any_normals = false;
};

struct PartB {
    std::string id, name;
    std::optional<std::string> parent;
    V3 origin;
    std::vector<float> pos, uv;
    std::vector<std::int32_t> idx;    // part-local soup indices
    std::vector<std::int32_t> tface;  // part-local face per triangle
    std::int32_t faces = 0;
    std::vector<char> smooth;  // per part-local face
    std::vector<std::int32_t> face_material;
    std::vector<std::int32_t> nrm_v;
    std::vector<std::int16_t> nrm_oct;
    std::vector<std::int32_t> skin_v, skin_off{0}, skin_j;
    std::vector<float> skin_w;
    std::vector<std::pair<std::string, MorphRows>> morphs;
    std::vector<std::pair<std::string, omo::GroupPart>> groups;
    std::int32_t vertices() const { return static_cast<std::int32_t>(pos.size() / 3); }
};

struct KeyVal {
    V3 pos, rot, scale{1, 1, 1};
};

class Converter {
public:
    Converter(const imp::Scene& scene, const Options& opt, std::string object_name)
        : s_(scene), o_(opt), sp_(opt), object_name_(std::move(object_name)) {}

    void run();

    omo::Document doc;
    std::vector<co_import_result::Clip> clips;
    std::vector<std::string> warnings;
    co_import_counts counts{};

private:
    void walk_scene();
    void build_bones();
    void build_materials();
    std::vector<std::byte> downscaled_png(const std::vector<std::byte>& png, const std::string& what);
    void build_parts();
    void bake_mesh_node(int node, PartB& part, int rigid_bone);
    void build_doc();
    void build_animations();
    std::string unique_name(const std::string& want, const std::string& fallback);
    void warn(std::string w) { warnings.push_back(std::move(w)); }
    int nearest_bone(int node) const;
    const imp::Node& node(int i) const { return s_.nodes[sz(i)]; }

    const imp::Scene& s_;
    Options o_;
    Space sp_;
    std::string object_name_;

    std::vector<char> in_scene_;
    std::vector<int> order_;
    std::vector<M4> world_;  // source space
    std::vector<int> bone_of_node_;
    std::vector<BoneB> bones_;
    std::vector<int> part_of_node_;
    std::vector<PartB> parts_;
    std::vector<M4> part_frame_;  // the rest frame a part's children inherit (= its parent's child frame)
    std::set<std::string> used_names_;
    std::vector<std::int32_t> material_id_;  // scene material -> OMO id
    std::int32_t default_material_ = -1;
    std::vector<std::string> morph_order_;
    std::map<std::string, std::array<float, 3>> morph_params_;  // default, min, max
    std::vector<std::string> group_order_;
    std::set<std::string> group_seen_;
    int max_influences_ = 0;
};

std::string Converter::unique_name(const std::string& want_in, const std::string& fallback) {
    const std::string want = want_in.empty() ? fallback : want_in;
    std::string name = want;
    for (int i = 1; used_names_.count(name) != 0; ++i) {
        char suffix[16];
        std::snprintf(suffix, sizeof suffix, ".%03d", i);
        name = want + suffix;
    }
    used_names_.insert(name);
    return name;
}

void Converter::walk_scene() {
    const std::size_t n = s_.nodes.size();
    in_scene_.assign(n, 0);
    world_.assign(n, M4{});
    std::vector<std::pair<int, M4>> stack;
    for (auto it = s_.roots.rbegin(); it != s_.roots.rend(); ++it) stack.push_back({*it, M4{}});
    while (!stack.empty()) {
        const auto [ni, parent_world] = stack.back();
        stack.pop_back();
        if (ni < 0 || sz(ni) >= n || in_scene_[sz(ni)]) continue;
        in_scene_[sz(ni)] = 1;
        const imp::Node& nd = node(ni);
        world_[sz(ni)] = parent_world * M4::from_float(nd.local);
        order_.push_back(ni);
        for (auto c = nd.children.rbegin(); c != nd.children.rend(); ++c) stack.push_back({*c, world_[sz(ni)]});
    }
}

void Converter::build_bones() {
    const std::size_t n = s_.nodes.size();
    bone_of_node_.assign(n, -1);
    std::vector<char> is_joint(n, 0);
    for (const imp::Skin& skin : s_.skins) {
        for (int j : skin.joints) {
            if (j < 0 || sz(j) >= n) continue;
            if (!in_scene_[sz(j)]) {
                warn("skin '" + skin.name + "': joint '" + node(j).name + "' is not in the default scene; ignored");
                continue;
            }
            is_joint[sz(j)] = 1;
        }
    }
    for (int ni : order_) {
        if (!is_joint[sz(ni)]) continue;
        BoneB b;
        b.node = ni;
        // Nearest joint ancestor + the static chain between.
        M4 chain;
        int k = node(ni).parent;
        while (k >= 0 && !is_joint[sz(k)]) {
            chain = M4::from_float(node(k).local) * chain;
            k = node(k).parent;
        }
        b.parent = k >= 0 ? bone_of_node_[sz(k)] : -1;
        b.p = sp_.conj(chain);
        const TRS r = rest_trs(node(ni));
        b.rest = {sp_.point(r.t), sp_.quat(r.q), r.s};
        const Frame f = frame_of(sp_.conj(world_[sz(ni)]));
        b.w = f.w;
        b.sigma = f.sigma;
        if (!f.uniform) {
            warn("joint '" + node(ni).name +
                 "' has a non-uniform or sheared rest frame: bones store no scale, its frame was orthonormalized "
                 "(skinning stays exact, the bone frame and its animation are approximated)");
        }
        if (f.mirrored) warn("joint '" + node(ni).name + "' has a mirrored rest frame: approximated by a rotation");
        b.sigma_parent = b.parent >= 0 ? bones_[sz(b.parent)].sigma : 1.0;
        b.name = unique_name(node(ni).name, "Bone " + std::to_string(bones_.size() + 1));
        b.id = make_id('b', bones_.size(), b.name);
        bone_of_node_[sz(ni)] = static_cast<int>(bones_.size());
        bones_.push_back(std::move(b));
    }
    // Endpoints: the first child joint's head in the bone's frame; else +Y with the parent's length or 0.1.
    const double eps = 1e-6 * std::max(1.0, o_.unit);
    for (std::size_t i = 0; i < bones_.size(); ++i) {
        BoneB& b = bones_[i];
        bool set = false;
        for (std::size_t c = i + 1; c < bones_.size(); ++c) {
            if (bones_[c].parent != static_cast<int>(i)) continue;
            const V3 e = b.w.affine_inverse().point(bones_[c].w.translation());
            if (length(e) > eps) {
                b.endpoint = e;
                set = true;
            }
            break;
        }
        if (!set) {
            double len = 0.1 * o_.unit;
            if (b.parent >= 0) {
                const double pl = length(bones_[sz(b.parent)].endpoint);
                if (pl > eps) len = pl;
            }
            b.endpoint = {0.0, len, 0.0};
        }
    }
    for (BoneB& b : bones_) {
        M4 parent_frame;
        if (b.parent >= 0) {
            const BoneB& pb = bones_[sz(b.parent)];
            parent_frame = pb.w * translation(pb.endpoint);
        }
        const M4 rel = parent_frame.affine_inverse() * b.w;
        b.origin = rel.translation();
        b.rel_rot = rel.linear();
        b.rot = euler_of(rel);
    }
}

// The PNG downscaled to fit max_texture (warning: both sizes); empty + a warning when it cannot be decoded.
std::vector<std::byte> Converter::downscaled_png(const std::vector<std::byte>& png, const std::string& what) {
    namespace det = cenda::omformat::import::detail;
    std::vector<unsigned char> pixels;
    int w = 0, h = 0, ch = 0;
    if (!det::decode_pixels(png, pixels, w, h, ch)) {
        warn(what + "; it could not be decoded, dropped (base colour only)");
        return {};
    }
    const auto [dw, dh] = fitted_size(w, h, o_.max_texture);
    try {
        std::vector<std::byte> out = det::encode_png(downscale_area(pixels, w, h, ch, dw, dh).data(), dw, dh, ch);
        warn(what + "; downscaled from " + std::to_string(w) + "x" + std::to_string(h) + " to " +
             std::to_string(dw) + "x" + std::to_string(dh));
        return out;
    } catch (const cenda::omformat::format_error&) {
        warn(what + "; re-encoding failed, dropped (base colour only)");
        return {};
    }
}

void Converter::build_materials() {
    material_id_.assign(s_.materials.size(), 0);
    doc.face_textures.emplace();
    for (std::size_t i = 0; i < s_.materials.size(); ++i) {
        const imp::Material& m = s_.materials[i];
        omo::Material om;
        om.material_id = static_cast<std::int32_t>(i + 1);
        om.name = m.name.empty() ? "Material " + std::to_string(i + 1) : m.name;
        om.render_layer = m.alpha_mode == imp::AlphaMode::Mask    ? "CUTOUT"
                          : m.alpha_mode == imp::AlphaMode::Blend ? "TRANSLUCENT"
                                                                  : "OPAQUE";
        om.base_color = std::array<float, 4>{m.base_color[0], m.base_color[1], m.base_color[2], m.base_color[3]};
        if (m.base_color_texture && m.base_color_texture->image >= 0 &&
            sz(m.base_color_texture->image) < s_.images.size()) {
            const imp::Image& img = s_.images[sz(m.base_color_texture->image)];
            if (img.png.empty()) {
                warn("material '" + *om.name + "': its base colour texture has no PNG data; base colour only");
            } else {
                std::vector<std::byte> png = img.png;
                if (o_.max_texture > 0 && (img.width > o_.max_texture || img.height > o_.max_texture)) {
                    const std::string what = "material '" + *om.name + "': texture " + std::to_string(img.width) +
                                             "x" + std::to_string(img.height) + " exceeds the maximum " +
                                             std::to_string(o_.max_texture);
                    png = downscaled_png(img.png, what);
                }
                if (!png.empty()) {
                    const std::string file = "material_" + std::to_string(om.material_id) + ".png";
                    om.texture_file = file;
                    doc.images.push_back(omo::Image{file, std::move(png)});
                }
            }
        }
        material_id_[i] = om.material_id;
        doc.face_textures->materials.push_back(std::move(om));
    }
}

int Converter::nearest_bone(int ni) const {
    for (int k = ni; k >= 0; k = node(k).parent) {
        if (bone_of_node_[sz(k)] >= 0) return bone_of_node_[sz(k)];
    }
    return -1;
}

void Converter::build_parts() {
    const bool split = (o_.flags & CO_IMPORT_SPLIT_PARTS) != 0;
    part_of_node_.assign(s_.nodes.size(), -1);
    std::vector<int> mesh_nodes;
    for (int ni : order_) {
        if (node(ni).mesh >= 0) mesh_nodes.push_back(ni);
    }
    if (!split) {
        if (mesh_nodes.empty()) return;
        PartB part;
        part.name = unique_name(object_name_, "Model");
        part.id = make_id('p', 0, part.name);
        parts_.push_back(std::move(part));
        part_frame_.push_back(M4{});
        for (int ni : mesh_nodes) bake_mesh_node(ni, parts_[0], node(ni).skin >= 0 ? -1 : nearest_bone(ni));
        return;
    }
    for (int ni : mesh_nodes) {
        const imp::Node& nd = node(ni);
        PartB part;
        std::string want = nd.name;
        if (want.empty()) want = s_.meshes[sz(nd.mesh)].name;
        part.name = unique_name(want, "Part " + std::to_string(parts_.size() + 1));
        part.id = make_id('p', parts_.size(), part.name);
        M4 frame;  // the parent's child frame at rest
        if (nd.skin < 0) {
            for (int k = ni; k >= 0; k = node(k).parent) {
                if (bone_of_node_[sz(k)] >= 0) {
                    const BoneB& b = bones_[sz(bone_of_node_[sz(k)])];
                    part.parent = b.id;
                    frame = b.w * translation(b.endpoint);
                    break;
                }
                if (k != ni && part_of_node_[sz(k)] >= 0) {
                    const std::size_t pi = sz(part_of_node_[sz(k)]);
                    part.parent = parts_[pi].id;
                    frame = part_frame_[pi];
                    break;
                }
            }
        }
        part.origin = frame.affine_inverse().point(sp_.point(world_[sz(ni)].translation()));
        part_of_node_[sz(ni)] = static_cast<int>(parts_.size());
        parts_.push_back(std::move(part));
        part_frame_.push_back(frame);
        bake_mesh_node(ni, parts_.back(), -1);
    }
}

// Bakes every primitive of a mesh node into `part`. rigid_bone >= 0 (merge mode): every vertex gets a skin row
// (that bone, 1).
void Converter::bake_mesh_node(int ni, PartB& part, int rigid_bone) {
    const imp::Node& nd = node(ni);
    const imp::Mesh& mesh = s_.meshes[sz(nd.mesh)];
    const bool want_morphs = (o_.flags & CO_IMPORT_MORPHS) != 0;
    const bool want_groups = (o_.flags & CO_IMPORT_VERTEX_GROUPS) != 0;
    const bool obj = s_.format == imp::SourceFormat::Obj;

    // Skin of the node: per joint, source world · IBM, and its bone.
    const imp::Skin* skin = nullptr;
    std::vector<M4> joint_mats;
    std::vector<int> joint_bone;
    if (nd.skin >= 0 && sz(nd.skin) < s_.skins.size()) {
        skin = &s_.skins[sz(nd.skin)];
        joint_mats.resize(skin->joints.size());
        joint_bone.resize(skin->joints.size(), -1);
        for (std::size_t j = 0; j < skin->joints.size(); ++j) {
            const int jn = skin->joints[j];
            const M4 ibm = j < skin->inverse_bind_matrices.size() ? M4::from_float(skin->inverse_bind_matrices[j]) : M4{};
            if (jn >= 0 && sz(jn) < s_.nodes.size()) {
                joint_mats[j] = world_[sz(jn)] * ibm;
                joint_bone[j] = bone_of_node_[sz(jn)];
            }
        }
    }
    const M4 rigid = sp_.c * world_[sz(ni)];
    const M4 rigid_n = sp_.rot * world_[sz(ni)].normal_matrix();
    const bool rigid_mirror = world_[sz(ni)].det3() < 0.0;
    const std::vector<float>& wdef = !nd.weights.empty() ? nd.weights : mesh.weights;

    int unweighted = 0;
    for (const imp::Primitive& prim : mesh.primitives) {
        const std::size_t nv = prim.positions.size();
        if (nv == 0 || prim.indices.empty()) continue;
        const bool skinned = skin != nullptr && !prim.influences.empty();

        // Per-vertex rest matrices, positions, normals, skin rows (CSR over bones).
        std::vector<V3> pos(nv), nrm;
        std::vector<M4> vlin, vnrm;  // per vertex linear / normal map (skinned)
        const bool has_n = prim.normals.size() == nv;
        if (has_n) nrm.resize(nv);
        std::vector<std::uint32_t> roff(1, 0);
        std::vector<std::int32_t> rbone;
        std::vector<float> rw;
        if (skinned) {
            vlin.resize(nv);
            vnrm.resize(nv);
        }
        for (std::size_t v = 0; v < nv; ++v) {
            M4 m = rigid;
            M4 nm = rigid_n;
            if (skinned) {
                // Merge duplicate bones, drop non-positive weights, renormalize.
                std::vector<std::pair<int, double>> acc;
                double sum = 0.0;
                const std::uint32_t b0 = prim.influences.offsets[v], b1 = prim.influences.offsets[v + 1];
                for (std::uint32_t i = b0; i < b1; ++i) {
                    const std::uint32_t j = prim.influences.joints[i];
                    const double w = prim.influences.weights[i];
                    if (!(w > 0.0) || j >= joint_mats.size() || joint_bone[j] < 0) continue;
                    bool merged = false;
                    for (auto& [bj, bw] : acc) {
                        if (bj == joint_bone[j]) {
                            bw += w;
                            merged = true;
                        }
                    }
                    if (!merged) acc.push_back({joint_bone[j], w});
                    sum += w;
                }
                if (acc.empty() || !(sum > 0.0)) {
                    ++unweighted;
                } else {
                    M4 blend;
                    blend.m.fill(0.0);
                    for (std::uint32_t i = b0; i < b1; ++i) {
                        const std::uint32_t j = prim.influences.joints[i];
                        const double w = prim.influences.weights[i];
                        if (!(w > 0.0) || j >= joint_mats.size() || joint_bone[j] < 0) continue;
                        blend = blend + joint_mats[j] * (w / sum);
                    }
                    m = sp_.c * blend;
                    nm = sp_.rot * blend.normal_matrix();
                    for (const auto& [bj, bw] : acc) {
                        rbone.push_back(bj);
                        rw.push_back(static_cast<float>(bw / sum));
                    }
                }
                vlin[v] = m.linear();
                vnrm[v] = nm;
            } else if (rigid_bone >= 0) {
                rbone.push_back(rigid_bone);
                rw.push_back(1.0f);
            }
            roff.push_back(static_cast<std::uint32_t>(rbone.size()));
            pos[v] = m.point(v3(prim.positions[v]));
            if (has_n) nrm[v] = normalized(nm.vector(v3(prim.normals[v])));
        }

        // UVs: the material's texture set (+ KHR_texture_transform), else TEXCOORD_0; OBJ (v up) flips v.
        std::vector<std::array<double, 2>> uv(nv, std::array<double, 2>{0.0, 0.0});
        {
            int set = 0;
            const imp::TextureTransform* xf = nullptr;
            if (prim.material >= 0 && sz(prim.material) < s_.materials.size()) {
                const auto& tex = s_.materials[sz(prim.material)].base_color_texture;
                if (tex) {
                    set = tex->texcoord;
                    if (tex->transform) {
                        xf = &*tex->transform;
                        if (xf->texcoord >= 0) set = xf->texcoord;
                    }
                }
            }
            const std::vector<imp::Float2>* src = nullptr;
            if (set >= 0 && sz(set) < prim.texcoords.size() && prim.texcoords[sz(set)].size() == nv) {
                src = &prim.texcoords[sz(set)];
            } else if (set != 0) {
                warn("mesh '" + mesh.name + "': texture coordinate set " + std::to_string(set) + " is missing");
            }
            if (src != nullptr) {
                for (std::size_t v = 0; v < nv; ++v) {
                    double u = (*src)[v][0], w = (*src)[v][1];
                    if (xf != nullptr) {
                        const double c = std::cos(xf->rotation), sn = std::sin(xf->rotation);
                        const double su = u * xf->scale[0], sw = w * xf->scale[1];
                        u = c * su + sn * sw + xf->offset[0];
                        w = -sn * su + c * sw + xf->offset[1];
                    }
                    if (s_.uv_origin == imp::UvOrigin::BottomLeft) w = 1.0 - w;
                    uv[v] = {u, w};
                }
            }
        }

        // Faces.
        std::vector<Face> faces;
        if (obj) {
            std::size_t at = 0;
            for (std::size_t f = 0; f < prim.face_sizes.size(); ++f) {
                const std::uint32_t k = prim.face_sizes[f];
                Face face;
                face.loop.assign(prim.indices.begin() + static_cast<std::ptrdiff_t>(at),
                                 prim.indices.begin() + static_cast<std::ptrdiff_t>(at + k));
                at += k;
                if (k < 3) continue;
                std::vector<V3> lp(k);
                for (std::uint32_t i = 0; i < k; ++i) lp[i] = pos[face.loop[i]];
                face.tris = triangulate(lp);
                face.smooth = f < prim.face_smoothing.size() && prim.face_smoothing[f] != 0;
                faces.push_back(std::move(face));
            }
        } else {
            faces = pair_triangles(prim.indices, pos, (o_.flags & CO_IMPORT_MERGE_QUADS) != 0, o_.quad_cos);
            for (Face& f : faces) f.smooth = has_n;
        }
        if (!skinned && rigid_mirror) {
            for (Face& f : faces) {
                const std::uint32_t n = static_cast<std::uint32_t>(f.loop.size());
                std::reverse(f.loop.begin(), f.loop.end());
                for (Tri& t : f.tris) t = {n - 1 - t[2], n - 1 - t[1], n - 1 - t[0]};
            }
        }

        // Morph deltas (converted, per vertex) and vertex groups.
        struct TargetB {
            std::string name;
            std::vector<V3> d, dn;
            std::vector<char> zero;
            bool normals = false;
        };
        std::vector<TargetB> targets;
        if (want_morphs) {
            for (std::size_t t = 0; t < prim.targets.size(); ++t) {
                const imp::MorphTarget& mt = prim.targets[t];
                TargetB tb;
                tb.name = t < mesh.target_names.size() && !mesh.target_names[t].empty() ? mesh.target_names[t]
                                                                                       : "Key " + std::to_string(t + 1);
                const bool tp = mt.positions.size() == nv;
                tb.normals = mt.normals.size() == nv && has_n;
                tb.d.assign(nv, V3{});
                tb.dn.assign(nv, V3{});
                tb.zero.assign(nv, 1);
                for (std::size_t v = 0; v < nv; ++v) {
                    if (tp) {
                        const V3 d = v3(mt.positions[v]);
                        if (d.x != 0.0 || d.y != 0.0 || d.z != 0.0) tb.zero[v] = 0;
                        tb.d[v] = (skinned ? vlin[v] : rigid).vector(d);
                    }
                    if (tb.normals) {
                        const V3 dn = v3(mt.normals[v]);
                        if (dn.x != 0.0 || dn.y != 0.0 || dn.z != 0.0) tb.zero[v] = 0;
                        const M4& nmat = skinned ? vnrm[v] : rigid_n;
                        const double l = length(nmat.vector(v3(prim.normals[v])));
                        tb.dn[v] = nmat.vector(dn) * (l > 0.0 ? 1.0 / l : 1.0);
                    }
                }
                if (morph_params_.count(tb.name) == 0) {
                    const float def = t < wdef.size() ? wdef[t] : 0.f;
                    morph_params_[tb.name] = {def, std::min(0.f, def), std::max(1.f, def)};
                    morph_order_.push_back(tb.name);
                }
                targets.push_back(std::move(tb));
            }
        }
        struct GroupB {
            std::string name;
            const std::vector<float>* values;
        };
        std::vector<GroupB> groups;
        if (want_groups) {
            for (const imp::CustomAttribute& ca : prim.custom) {
                std::string gname = ca.name;
                if (!gname.empty() && gname[0] == '_') gname.erase(0, 1);
                gname = lower(gname);
                if (ca.components != 1 || ca.values.size() != nv) {
                    warn("mesh '" + mesh.name + "': attribute '" + ca.name + "' has " + std::to_string(ca.components) +
                         " components; only scalar attributes become vertex groups");
                    continue;
                }
                if (gname.empty()) continue;
                if (group_seen_.insert(gname).second) group_order_.push_back(gname);
                groups.push_back({gname, &ca.values});
            }
        }

        // Material of this primitive.
        std::int32_t mat;
        if (prim.material >= 0 && sz(prim.material) < material_id_.size()) {
            mat = material_id_[sz(prim.material)];
        } else {
            if (default_material_ < 0) {
                default_material_ = static_cast<std::int32_t>(s_.materials.size() + 1);
                omo::Material om;
                om.material_id = default_material_;
                om.name = "Default";
                om.render_layer = "OPAQUE";
                om.base_color = std::array<float, 4>{1.f, 1.f, 1.f, 1.f};
                doc.face_textures->materials.push_back(std::move(om));
            }
            mat = default_material_;
        }

        // Emit: every face owns its corners.
        auto morph_rows = [&part](const std::string& mname) -> MorphRows& {
            for (auto& [n, r] : part.morphs) {
                if (n == mname) return r;
            }
            part.morphs.push_back({mname, MorphRows{}});
            return part.morphs.back().second;
        };
        auto group_rows = [&part](const std::string& gname) -> omo::GroupPart& {
            for (auto& [n, g] : part.groups) {
                if (n == gname) return g;
            }
            omo::GroupPart g;
            g.part_id = part.id;
            part.groups.push_back({gname, std::move(g)});
            return part.groups.back().second;
        };
        for (const Face& f : faces) {
            const std::int32_t face_id = part.faces++;
            const std::int32_t base = part.vertices();
            part.smooth.push_back(f.smooth ? 1 : 0);
            part.face_material.push_back(mat);
            for (std::uint32_t v : f.loop) {
                const std::int32_t sv = part.vertices();
                part.pos.insert(part.pos.end(), {static_cast<float>(pos[v].x), static_cast<float>(pos[v].y),
                                                 static_cast<float>(pos[v].z)});
                part.uv.insert(part.uv.end(), {static_cast<float>(uv[v][0]), static_cast<float>(uv[v][1])});
                if (has_n && length(nrm[v]) > 0.0) {
                    const auto oct = oct_encode(nrm[v]);
                    part.nrm_v.push_back(sv);
                    part.nrm_oct.insert(part.nrm_oct.end(), {oct[0], oct[1]});
                }
                if (roff[v + 1] > roff[v]) {
                    part.skin_v.push_back(sv);
                    for (std::uint32_t i = roff[v]; i < roff[v + 1]; ++i) {
                        part.skin_j.push_back(rbone[i]);
                        part.skin_w.push_back(rw[i]);
                    }
                    part.skin_off.push_back(static_cast<std::int32_t>(part.skin_j.size()));
                    max_influences_ = std::max(max_influences_, static_cast<int>(roff[v + 1] - roff[v]));
                }
                for (const TargetB& tb : targets) {
                    if (tb.zero[v]) continue;
                    MorphRows& mr = morph_rows(tb.name);
                    mr.vertices.push_back(sv);
                    mr.deltas.insert(mr.deltas.end(), {static_cast<float>(tb.d[v].x), static_cast<float>(tb.d[v].y),
                                                       static_cast<float>(tb.d[v].z)});
                    mr.normals.insert(mr.normals.end(), {static_cast<float>(tb.dn[v].x),
                                                         static_cast<float>(tb.dn[v].y),
                                                         static_cast<float>(tb.dn[v].z)});
                    if (tb.normals) mr.any_normals = true;
                }
                for (const GroupB& g : groups) {
                    const float w = (*g.values)[v];
                    if (w == 0.f) continue;
                    omo::GroupPart& gp = group_rows(g.name);
                    gp.vertices.push_back(sv);
                    gp.weights.push_back(w);
                }
            }
            for (const Tri& t : f.tris) {
                part.idx.insert(part.idx.end(), {base + static_cast<std::int32_t>(t[0]),
                                                 base + static_cast<std::int32_t>(t[1]),
                                                 base + static_cast<std::int32_t>(t[2])});
                part.tface.push_back(face_id);
            }
        }
    }
    if (unweighted > 0) {
        warn("mesh '" + mesh.name + "': " + std::to_string(unweighted) +
             " skinned vertices have no weights; they follow their part");
    }
}

void Converter::build_doc() {
    doc.version = "1.9";
    doc.object_name = object_name_;
    doc.model_type = "SINGLE_CUBE";
    std::vector<float> vertices, tex;
    std::vector<std::int32_t> indices, tri_face;
    std::int32_t face_base = 0;
    for (PartB& p : parts_) {
        omo::Part op;
        op.id = p.id;
        op.name = p.name;
        op.origin = {static_cast<float>(p.origin.x), static_cast<float>(p.origin.y), static_cast<float>(p.origin.z)};
        op.parent_id = p.parent;
        const std::int32_t vbase = static_cast<std::int32_t>(vertices.size() / 3);
        op.vertex_start = vbase;
        op.vertex_count = p.vertices();
        op.index_start = static_cast<std::int32_t>(indices.size());
        op.index_count = static_cast<std::int32_t>(p.idx.size());
        op.face_start = face_base;
        op.face_count = p.faces;
        vertices.insert(vertices.end(), p.pos.begin(), p.pos.end());
        tex.insert(tex.end(), p.uv.begin(), p.uv.end());
        for (std::int32_t v : p.idx) indices.push_back(v + vbase);
        for (std::int32_t f : p.tface) tri_face.push_back(f + face_base);
        for (std::int32_t f = 0; f < p.faces; ++f) {
            const std::int32_t gf = face_base + f;
            if (p.smooth[sz(f)]) doc.shading.set_smooth_face(static_cast<std::uint32_t>(gf), true);
            omo::FaceMapping fm;
            fm.face_id = gf;
            fm.material_id = p.face_material[sz(f)];
            fm.auto_resize = false;
            doc.face_textures->mappings.push_back(fm);
        }
        for (std::size_t r = 0; r < p.nrm_v.size(); ++r) {
            doc.shading.normal_vertices.push_back(p.nrm_v[r] + vbase);
            doc.shading.normal_oct.push_back(p.nrm_oct[r * 2]);
            doc.shading.normal_oct.push_back(p.nrm_oct[r * 2 + 1]);
        }
        if (!p.skin_v.empty()) {
            omo::SkinPart sk;
            sk.part_id = p.id;
            sk.vertices = p.skin_v;
            sk.offsets = p.skin_off;
            sk.joints = p.skin_j;
            sk.weights = p.skin_w;
            doc.skin.parts.push_back(std::move(sk));
        }
        face_base += p.faces;
        doc.parts.push_back(std::move(op));
    }
    if (!parts_.empty()) {
        omo::Mesh mesh;
        mesh.vertices = std::move(vertices);
        mesh.tex_coords = std::move(tex);
        mesh.indices = std::move(indices);
        mesh.triangle_to_face_id = std::move(tri_face);
        doc.mesh = std::move(mesh);
    }
    for (const BoneB& b : bones_) {
        omo::Bone ob;
        ob.id = b.id;
        ob.name = b.name;
        if (b.parent >= 0) ob.parent_bone_id = bones_[sz(b.parent)].id;
        ob.origin = {static_cast<float>(b.origin.x), static_cast<float>(b.origin.y), static_cast<float>(b.origin.z)};
        ob.rot = {static_cast<float>(b.rot.x), static_cast<float>(b.rot.y), static_cast<float>(b.rot.z)};
        ob.endpoint = {static_cast<float>(b.endpoint.x), static_cast<float>(b.endpoint.y),
                       static_cast<float>(b.endpoint.z)};
        doc.bones.push_back(std::move(ob));
    }
    if (!doc.skin.parts.empty()) {
        for (const BoneB& b : bones_) doc.skin.joints.push_back(b.id);
    }
    // Morphs / groups in first-seen order, one part entry per part that has rows.
    for (const std::string& name : morph_order_) {
        omo::Morph m;
        m.name = name;
        const auto& prm = morph_params_[name];
        m.default_weight = prm[0];
        m.min = prm[1];
        m.max = prm[2];
        for (PartB& p : parts_) {
            for (auto& [n, rows] : p.morphs) {
                if (n != name || rows.vertices.empty()) continue;
                omo::MorphPart mp;
                mp.part_id = p.id;
                mp.vertices = rows.vertices;
                mp.deltas = rows.deltas;
                if (rows.any_normals) mp.normals = rows.normals;
                m.parts.push_back(std::move(mp));
            }
        }
        doc.morphs.push_back(std::move(m));
    }
    for (const std::string& name : group_order_) {
        omo::VertexGroup g;
        g.name = name;
        for (PartB& p : parts_) {
            for (auto& [n, gp] : p.groups) {
                if (n == name && !gp.vertices.empty()) g.parts.push_back(gp);
            }
        }
        doc.vertex_groups.push_back(std::move(g));
    }
    if (doc.face_textures && doc.face_textures->materials.empty() && doc.face_textures->mappings.empty()) {
        doc.face_textures.reset();
    }
}

// ---- animations -----------------------------------------------------------------------------------------------

// Samples one channel at time t into out[components] (rotation: normalized quaternion). Clamps outside the keys.
void sample_channel(const imp::AnimSampler& s, double t, bool is_rotation, double* out) {
    const std::size_t n = s.input.size();
    const std::size_t comps = sz(s.components);
    const bool cubic = s.interpolation == imp::Interpolation::CubicSpline;
    const std::size_t stride = comps * (cubic ? 3 : 1);
    if (n == 0 || s.output.size() < n * stride) return;
    auto value = [&](std::size_t k, std::size_t c) {
        return static_cast<double>(s.output[k * stride + (cubic ? comps : 0) + c]);
    };
    auto finish = [&]() {
        if (is_rotation) {
            const Q q = qnormalized({out[0], out[1], out[2], out[3]});
            out[0] = q.x;
            out[1] = q.y;
            out[2] = q.z;
            out[3] = q.w;
        }
    };
    if (n == 1 || t <= s.input[0]) {
        for (std::size_t c = 0; c < comps; ++c) out[c] = value(0, c);
        finish();
        return;
    }
    if (t >= s.input[n - 1]) {
        for (std::size_t c = 0; c < comps; ++c) out[c] = value(n - 1, c);
        finish();
        return;
    }
    std::size_t k = 0;
    while (k + 2 < n && t >= static_cast<double>(s.input[k + 1])) ++k;
    const double t0 = s.input[k], t1 = s.input[k + 1];
    const double dt = t1 - t0;
    const double u = dt > 0.0 ? (t - t0) / dt : 0.0;
    switch (s.interpolation) {
    case imp::Interpolation::Step:
        for (std::size_t c = 0; c < comps; ++c) out[c] = value(k, c);
        break;
    case imp::Interpolation::Linear:
        if (is_rotation) {
            const Q q = slerp({value(k, 0), value(k, 1), value(k, 2), value(k, 3)},
                              {value(k + 1, 0), value(k + 1, 1), value(k + 1, 2), value(k + 1, 3)}, u);
            out[0] = q.x;
            out[1] = q.y;
            out[2] = q.z;
            out[3] = q.w;
        } else {
            for (std::size_t c = 0; c < comps; ++c) out[c] = value(k, c) + (value(k + 1, c) - value(k, c)) * u;
        }
        break;
    case imp::Interpolation::CubicSpline: {
        const double u2 = u * u, u3 = u2 * u;
        const double h00 = 2 * u3 - 3 * u2 + 1, h10 = u3 - 2 * u2 + u, h01 = -2 * u3 + 3 * u2, h11 = u3 - u2;
        for (std::size_t c = 0; c < comps; ++c) {
            const double vk = value(k, c), vk1 = value(k + 1, c);
            const double bk = s.output[k * stride + 2 * comps + c];   // out-tangent of k
            const double ak1 = s.output[(k + 1) * stride + c];        // in-tangent of k + 1
            out[c] = h00 * vk + h10 * dt * bk + h01 * vk1 + h11 * dt * ak1;
        }
        break;
    }
    }
    finish();
}

void Converter::build_animations() {
    std::set<std::string> clip_names;
    for (std::size_t ai = 0; ai < s_.animations.size(); ++ai) {
        const imp::Animation& anim = s_.animations[ai];
        std::string name = anim.name.empty() ? "Animation " + std::to_string(ai + 1) : anim.name;
        {
            const std::string base = name;
            for (int i = 1; clip_names.count(name) != 0; ++i) {
                char suffix[16];
                std::snprintf(suffix, sizeof suffix, ".%03d", i);
                name = base + suffix;
            }
            clip_names.insert(name);
        }
        double duration = 0.0;
        for (const imp::AnimSampler& smp : anim.samplers) {
            if (!smp.input.empty()) duration = std::max(duration, static_cast<double>(smp.input.back()));
        }
        // Samplers per bone and path (T, R, S).
        std::map<int, std::array<int, 3>> per_bone;
        bool warned_weights = false;
        std::set<int> warned_nodes;
        for (const imp::AnimChannel& ch : anim.channels) {
            if (ch.sampler < 0 || sz(ch.sampler) >= anim.samplers.size() || ch.node < 0 ||
                sz(ch.node) >= s_.nodes.size()) {
                continue;
            }
            if (ch.path == imp::AnimPath::Weights) {
                if (!warned_weights) {
                    warn("animation '" + name + "': morph weight channels skipped (OMANIM has no morph tracks yet)");
                    warned_weights = true;
                }
                continue;
            }
            const int bone = bone_of_node_[sz(ch.node)];
            if (bone < 0) {
                if (warned_nodes.insert(ch.node).second) {
                    warn("animation '" + name + "': channels of node '" + node(ch.node).name +
                         "' skipped (only joints are animated)");
                }
                continue;
            }
            auto it = per_bone.find(bone);
            if (it == per_bone.end()) it = per_bone.emplace(bone, std::array<int, 3>{-1, -1, -1}).first;
            const std::size_t slot = ch.path == imp::AnimPath::Translation ? 0 : (ch.path == imp::AnimPath::Rotation ? 1 : 2);
            const int expected = slot == 1 ? 4 : 3;
            if (anim.samplers[sz(ch.sampler)].components != expected) continue;
            it->second[slot] = ch.sampler;
        }
        oa::Clip clip;
        clip.name = name;
        clip.fps = 30.f;
        clip.duration = static_cast<float>(duration);
        clip.loop = false;
        for (const auto& [bone_index, chans] : per_bone) {
            const BoneB& b = bones_[sz(bone_index)];
            const TRS src_rest = rest_trs(node(b.node));
            struct Exact {
                V3 pos;
                M4 rot;
                V3 scale;
            };
            // The converted local value at time t, exactly.
            auto exact = [&, &ch3 = chans](double t) {
                TRS v = src_rest;
                double buf[4];
                if (ch3[0] >= 0) {
                    buf[0] = v.t.x;
                    buf[1] = v.t.y;
                    buf[2] = v.t.z;
                    sample_channel(anim.samplers[sz(ch3[0])], t, false, buf);
                    v.t = {buf[0], buf[1], buf[2]};
                }
                if (ch3[1] >= 0) {
                    buf[0] = v.q.x;
                    buf[1] = v.q.y;
                    buf[2] = v.q.z;
                    buf[3] = v.q.w;
                    sample_channel(anim.samplers[sz(ch3[1])], t, true, buf);
                    v.q = {buf[0], buf[1], buf[2], buf[3]};
                }
                if (ch3[2] >= 0) {
                    buf[0] = v.s.x;
                    buf[1] = v.s.y;
                    buf[2] = v.s.z;
                    sample_channel(anim.samplers[sz(ch3[2])], t, false, buf);
                    v.s = {buf[0], buf[1], buf[2]};
                }
                Exact e;
                e.pos = b.p.vector(sp_.point(v.t) - b.rest.t) * b.sigma_parent;
                e.rot = b.rel_rot * rotation(qmul(qconj(b.rest.q), sp_.quat(v.q)));
                auto ratio = [](double sv, double rv) { return std::fabs(rv) > 1e-12 ? sv / rv : sv; };
                e.scale = {ratio(v.s.x, b.rest.s.x), ratio(v.s.y, b.rest.s.y), ratio(v.s.z, b.rest.s.z)};
                return e;
            };
            auto to_key = [&exact](double t, V3 near) {
                const Exact e = exact(t);
                KeyVal k;
                k.pos = e.pos;
                k.rot = euler_near(euler_of(e.rot), near);
                k.scale = e.scale;
                return k;
            };
            // The rig's interpolation between two keys (rig.cpp sample_track: lerp, per-component shortest angle).
            auto lerp_key = [](const KeyVal& a, const KeyVal& c, double u) {
                KeyVal k;
                k.pos = a.pos + (c.pos - a.pos) * u;
                k.rot = {a.rot.x + angle_delta(a.rot.x, c.rot.x) * u, a.rot.y + angle_delta(a.rot.y, c.rot.y) * u,
                         a.rot.z + angle_delta(a.rot.z, c.rot.z) * u};
                k.scale = a.scale + (c.scale - a.scale) * u;
                return k;
            };
            // ... as a matrix.
            auto lerp_matrix = [&lerp_key](const KeyVal& a, const KeyVal& c, double u) {
                const KeyVal k = lerp_key(a, c, u);
                return translation(k.pos) * euler_xyz(k.rot) * scaling(k.scale);
            };
            auto err = [](const M4& a, const M4& c) {
                double e = 0.0;
                for (int col = 0; col < 3; ++col) {
                    for (int r = 0; r < 3; ++r) e = std::max(e, std::fabs(a.at(r, col) - c.at(r, col)));
                }
                for (int r = 0; r < 3; ++r) {
                    const double scale = std::max(1.0, std::fabs(c.at(r, 3)));
                    e = std::max(e, std::fabs(a.at(r, 3) - c.at(r, 3)) / scale);
                }
                return e;
            };
            // Key times: the union of this joint's channel times; all-STEP channels keep STEP keys.
            std::vector<double> times;
            bool all_step = true;
            for (std::size_t c = 0; c < 3; ++c) {
                if (chans[c] < 0) continue;
                const imp::AnimSampler& smp = anim.samplers[sz(chans[c])];
                for (float t : smp.input) times.push_back(t);
                if (smp.interpolation != imp::Interpolation::Step) all_step = false;
            }
            std::sort(times.begin(), times.end());
            times.erase(std::unique(times.begin(), times.end(), [](double a, double c) { return std::fabs(a - c) < 1e-7; }),
                        times.end());
            if (times.empty()) continue;
            constexpr double kTol = 2e-5;
            // With the key reduction on, refinement samples 16x tighter (a candidate grid about 4x finer than the
            // keys kTol alone needs), so the reduction can place each kept key where its segment reaches kTol
            // instead of on the power-of-two grid of one subdivision level (two halves of a refined segment
            // merge back into a segment that failed: the reduction alone could hardly drop a key).
            const bool reduce = !all_step && (o_.flags & CO_IMPORT_KEEP_ALL_KEYS) == 0;
            const double refine_tol = reduce ? kTol / 16.0 : kTol;
            constexpr double kMinSpan = 1.0 / 4096.0;
            constexpr std::size_t kMaxKeys = 1u << 16;
            std::vector<std::pair<double, KeyVal>> keys;
            KeyVal prev = to_key(times[0], b.rot);
            keys.push_back({times[0], prev});
            // Refines a segment (a at ta, c at tc); appends the inserted keys in time order.
            std::function<void(double, const KeyVal&, double, const KeyVal&, int)> refine =
                [&](double ta, const KeyVal& a, double tc, const KeyVal& c, int depth) {
                    if (depth >= 24 || tc - ta <= kMinSpan || keys.size() >= kMaxKeys) return;
                    bool ok = true;
                    for (double u : {0.25, 0.5, 0.75}) {
                        const Exact e = exact(ta + (tc - ta) * u);
                        if (err(lerp_matrix(a, c, u), translation(e.pos) * e.rot * scaling(e.scale)) > refine_tol) {
                            ok = false;
                            break;
                        }
                    }
                    if (ok) return;
                    const double tm = 0.5 * (ta + tc);
                    const KeyVal m = to_key(tm, a.rot);
                    refine(ta, a, tm, m, depth + 1);
                    keys.push_back({tm, m});
                    refine(tm, m, tc, c, depth + 1);
                };
            for (std::size_t i = 1; i < times.size(); ++i) {
                const KeyVal next = to_key(times[i], prev.rot);
                if (!all_step) refine(times[i - 1], prev, times[i], next, 0);
                keys.push_back({times[i], next});
                prev = next;
            }
            // Key reduction: drop refined keys while the rig's interpolation of what is left stays within kTol of
            // the refined track AND of the glTF value, per component group (position, rotation, scale) and as the
            // composed matrix, at every refined key time and every refined segment's midpoint. First / last keys
            // stay; STEP tracks are left alone.
            if (reduce && keys.size() > 2) {
                const std::size_t n = keys.size();
                auto exact_of = [](const KeyVal& k) { return Exact{k.pos, euler_xyz(k.rot), k.scale}; };
                std::vector<Exact> at_key(n), at_mid(n - 1), refined_mid(n - 1);
                std::vector<double> t_mid(n - 1);
                for (std::size_t k = 0; k < n; ++k) at_key[k] = exact(keys[k].first);
                for (std::size_t k = 0; k + 1 < n; ++k) {
                    t_mid[k] = 0.5 * (keys[k].first + keys[k + 1].first);
                    at_mid[k] = exact(t_mid[k]);
                    refined_mid[k] = exact_of(lerp_key(keys[k].second, keys[k + 1].second, 0.5));
                }
                // The worst error of a key value against a reference, over the groups and the composed matrix.
                auto off = [&err](const KeyVal& k, const Exact& want) {
                    const M4 r = euler_xyz(k.rot);
                    double e = err(translation(k.pos) * r * scaling(k.scale),
                                   translation(want.pos) * want.rot * scaling(want.scale));
                    for (int row = 0; row < 3; ++row) {
                        for (int col = 0; col < 3; ++col) e = std::max(e, std::fabs(r.at(row, col) - want.rot.at(row, col)));
                    }
                    const double dp[3] = {k.pos.x - want.pos.x, k.pos.y - want.pos.y, k.pos.z - want.pos.z};
                    const double wp[3] = {want.pos.x, want.pos.y, want.pos.z};
                    const double ds[3] = {k.scale.x - want.scale.x, k.scale.y - want.scale.y, k.scale.z - want.scale.z};
                    for (int c = 0; c < 3; ++c) {
                        e = std::max(e, std::fabs(dp[c]) / std::max(1.0, std::fabs(wp[c])));
                        e = std::max(e, std::fabs(ds[c]));
                    }
                    return e;
                };
                auto fits = [&](std::size_t i, std::size_t j) {
                    const KeyVal& a = keys[i].second;
                    const KeyVal& c = keys[j].second;
                    const double ta = keys[i].first, span = keys[j].first - ta;
                    if (!(span > 0.0)) return false;
                    for (std::size_t k = i; k < j; ++k) {
                        if (k > i && off(lerp_key(a, c, (keys[k].first - ta) / span), at_key[k]) > kTol) return false;
                        const KeyVal m = lerp_key(a, c, (t_mid[k] - ta) / span);
                        if (off(m, at_mid[k]) > kTol || off(m, refined_mid[k]) > kTol) return false;
                    }
                    return true;
                };
                const std::vector<std::size_t> kept = reduce_keys(n, fits);
                std::vector<std::pair<double, KeyVal>> reduced;
                reduced.reserve(kept.size());
                for (std::size_t k : kept) reduced.push_back(keys[k]);
                keys = std::move(reduced);
            }
            oa::Track tr;
            tr.part_id = b.id;
            tr.part_name = b.name;
            tr.bone = true;
            for (const auto& [t, k] : keys) {
                oa::Key key;
                key.time = static_cast<float>(t);
                key.pos[0] = static_cast<float>(k.pos.x);
                key.pos[1] = static_cast<float>(k.pos.y);
                key.pos[2] = static_cast<float>(k.pos.z);
                key.rot[0] = static_cast<float>(k.rot.x);
                key.rot[1] = static_cast<float>(k.rot.y);
                key.rot[2] = static_cast<float>(k.rot.z);
                key.scale[0] = static_cast<float>(k.scale.x);
                key.scale[1] = static_cast<float>(k.scale.y);
                key.scale[2] = static_cast<float>(k.scale.z);
                key.easing = all_step ? oa::Easing::Step : oa::Easing::Linear;
                tr.keys.push_back(key);
            }
            clip.tracks.push_back(std::move(tr));
        }
        if (clip.tracks.empty()) {
            warn("animation '" + name + "': nothing to import (no joint channels)");
            continue;
        }
        clips.push_back({name, oa::write(clip)});
    }
}

void Converter::run() {
    walk_scene();
    build_bones();
    build_materials();
    build_parts();
    build_doc();
    if ((o_.flags & CO_IMPORT_ANIMATIONS) != 0) {
        build_animations();
    } else if (!s_.animations.empty()) {
        warn(std::to_string(s_.animations.size()) + " animation(s) not imported (animations off)");
    }
    counts.parts = static_cast<int32_t>(doc.parts.size());
    counts.bones = static_cast<int32_t>(doc.bones.size());
    for (const omo::Part& p : doc.parts) counts.faces += p.face_count;
    counts.triangles = doc.mesh && doc.mesh->indices ? static_cast<int32_t>(doc.mesh->indices->size() / 3) : 0;
    counts.vertices = doc.mesh && doc.mesh->vertices ? static_cast<int32_t>(doc.mesh->vertices->size() / 3) : 0;
    counts.materials = doc.face_textures ? static_cast<int32_t>(doc.face_textures->materials.size()) : 0;
    counts.max_influences = max_influences_;
    counts.morphs = static_cast<int32_t>(doc.morphs.size());
    counts.vertex_groups = static_cast<int32_t>(doc.vertex_groups.size());
    counts.clips = static_cast<int32_t>(clips.size());
}

// ---- entry ------------------------------------------------------------------------------------------------------

bool valid_options(const co_import_options* in) {
    if (in == nullptr) return true;
    return std::isfinite(in->unit_scale) && in->unit_scale > 0.f &&
           (in->axis == CO_IMPORT_AXIS_OPENMASON || in->axis == CO_IMPORT_AXIS_KEEP) && in->max_texture_size >= 0 &&
           std::isfinite(in->quad_angle_deg) && in->quad_angle_deg >= 0.f && in->quad_angle_deg < 90.f;
}

Options options_of(const co_import_options* in) {
    co_import_options d;
    co_import_options_default(&d);
    if (in != nullptr) d = *in;
    Options o;
    o.unit = d.unit_scale;
    o.flip = d.axis != CO_IMPORT_AXIS_KEEP;
    o.flags = d.flags;
    o.max_texture = d.max_texture_size;
    o.quad_cos = std::cos(static_cast<double>(d.quad_angle_deg) * kDegToRad);
    return o;
}

int32_t detect(std::span<const std::byte> bytes) {
    if (bytes.size() >= 4 && std::memcmp(bytes.data(), "glTF", 4) == 0) return CO_IMPORT_FORMAT_GLTF;
    std::size_t i = 0;
    if (bytes.size() >= 3 && bytes[0] == std::byte{0xEF} && bytes[1] == std::byte{0xBB} && bytes[2] == std::byte{0xBF}) {
        i = 3;
    }
    while (i < bytes.size() && std::isspace(static_cast<int>(static_cast<unsigned char>(bytes[i])))) ++i;
    if (i < bytes.size() && bytes[i] == std::byte{'{'}) return CO_IMPORT_FORMAT_GLTF;
    return CO_IMPORT_FORMAT_OBJ;
}

int32_t run_import(std::span<const std::byte> bytes, int32_t format, const imp::Resolver& resolve,
                   const co_import_options* options, const std::string& object_name, co_import_result** out) {
    if (out == nullptr) return fail_code(CO_ERR_ARG, "co_import: out_result is null");
    *out = nullptr;
    if (!valid_options(options)) return fail_code(CO_ERR_ARG, "co_import: invalid options");
    if (format == CO_IMPORT_FORMAT_AUTO) format = detect(bytes);
    if (format != CO_IMPORT_FORMAT_GLTF && format != CO_IMPORT_FORMAT_OBJ) {
        return fail_code(CO_ERR_ARG, "co_import: unknown format " + std::to_string(format));
    }
    imp::Scene scene;
    try {
        if (format == CO_IMPORT_FORMAT_GLTF) {
            scene = imp::read_gltf(bytes, resolve);
        } else {
            scene = imp::read_obj(std::string_view(reinterpret_cast<const char*>(bytes.data()), bytes.size()), resolve);
        }
    } catch (const cenda::omformat::format_error& e) {
        return fail_code(CO_ERR_FORMAT, e.what());
    }
    auto result = std::make_unique<co_import_result>();
    Converter conv(scene, options_of(options), object_name.empty() ? std::string("Imported") : object_name);
    conv.run();
    result->warnings = scene.warnings;
    result->warnings.insert(result->warnings.end(), conv.warnings.begin(), conv.warnings.end());
    result->clips = std::move(conv.clips);
    result->counts = conv.counts;
    result->counts.warnings = static_cast<int32_t>(result->warnings.size());
    std::vector<std::byte> archive;
    try {
        archive = omo::write(conv.doc);
    } catch (const cenda::omformat::format_error& e) {
        return fail_code(CO_ERR_FORMAT, std::string("co_import: the converted document is invalid: ") + e.what());
    }
    int32_t rc = CO_OK;
    result->doc = co_doc_read(reinterpret_cast<const uint8_t*>(archive.data()), static_cast<int64_t>(archive.size()), &rc);
    if (result->doc == nullptr) {
        return fail_code(rc != CO_OK ? rc : CO_ERR_FORMAT,
                         std::string("co_import: the converted document did not read back: ") + co_last_error());
    }
    last_error().clear();
    *out = result.release();
    return CO_OK;
}

std::filesystem::path u8path(const char* s) { return std::filesystem::path(std::u8string(reinterpret_cast<const char8_t*>(s))); }

}  // namespace

#define CI_GUARD_BEGIN try {
#define CI_GUARD_END(fallback)          \
    }                                   \
    catch (const std::bad_alloc&) {     \
        last_error() = "out of memory"; \
        return fallback;                \
    }                                   \
    catch (const std::exception& e) {   \
        last_error() = e.what();        \
        return fallback;                \
    }                                   \
    catch (...) {                       \
        last_error() = "unknown error"; \
        return fallback;                \
    }

extern "C" {

void co_import_options_default(co_import_options* out) {
    if (out == nullptr) return;
    out->unit_scale = 1.f;
    out->axis = CO_IMPORT_AXIS_OPENMASON;
    out->flags = CO_IMPORT_DEFAULT_FLAGS;
    out->max_texture_size = 0;
    out->quad_angle_deg = 1.f;
    out->reserved = 0;
}

const char* co_import_last_error(void) { return last_error().c_str(); }

int32_t co_import_file(const char* path, const co_import_options* options, co_import_result** out_result) {
    if (out_result != nullptr) *out_result = nullptr;
    CI_GUARD_BEGIN
    if (path == nullptr || out_result == nullptr) return fail_code(CO_ERR_ARG, "co_import_file: null argument");
    const std::filesystem::path file = u8path(path);
    const std::string ext = lower(file.extension().string());
    int32_t format;
    if (ext == ".gltf" || ext == ".glb") {
        format = CO_IMPORT_FORMAT_GLTF;
    } else if (ext == ".obj") {
        format = CO_IMPORT_FORMAT_OBJ;
    } else {
        return fail_code(CO_ERR_ARG, "co_import_file: unknown extension '" + ext + "' (.gltf, .glb, .obj)");
    }
    std::ifstream in(file, std::ios::binary);
    if (!in) return fail_code(CO_ERR_ARG, std::string("co_import_file: cannot read ") + path);
    std::vector<char> raw((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    if (in.bad()) return fail_code(CO_ERR_ARG, std::string("co_import_file: cannot read ") + path);
    std::vector<std::byte> bytes(raw.size());
    if (!raw.empty()) std::memcpy(bytes.data(), raw.data(), raw.size());
    const imp::Resolver resolve =
        imp::directory_resolver(file.parent_path().empty() ? std::filesystem::path(".") : file.parent_path());
    const std::u8string stem = file.stem().u8string();
    return run_import(bytes, format, resolve, options, std::string(stem.begin(), stem.end()), out_result);
    CI_GUARD_END(CO_ERR_FORMAT)
}

int32_t co_import_bytes(const uint8_t* bytes, int64_t length, int32_t format, const char* base_dir,
                        const co_import_options* options, co_import_result** out_result) {
    if (out_result != nullptr) *out_result = nullptr;
    CI_GUARD_BEGIN
    if (out_result == nullptr || length < 0 || (bytes == nullptr && length > 0)) {
        return fail_code(CO_ERR_ARG, "co_import_bytes: bad argument");
    }
    imp::Resolver resolve;
    if (base_dir != nullptr) resolve = imp::directory_resolver(u8path(base_dir));
    static const std::byte empty{};
    const std::span<const std::byte> span(length > 0 ? reinterpret_cast<const std::byte*>(bytes) : &empty,
                                          static_cast<std::size_t>(length));
    return run_import(span, format, resolve, options, "Imported", out_result);
    CI_GUARD_END(CO_ERR_FORMAT)
}

void co_import_result_destroy(co_import_result* r) { delete r; }

const co_doc* co_import_result_doc(const co_import_result* r) { return r == nullptr ? nullptr : r->doc; }

co_doc* co_import_result_take_doc(co_import_result* r) {
    if (r == nullptr) return nullptr;
    co_doc* d = r->doc;
    r->doc = nullptr;
    return d;
}

int32_t co_import_result_clip_count(const co_import_result* r) {
    return r == nullptr ? 0 : static_cast<int32_t>(r->clips.size());
}

int32_t co_import_result_clip(const co_import_result* r, int32_t index, const char** out_name,
                              const uint8_t** out_data, int64_t* out_size) {
    if (r == nullptr) return fail_code(CO_ERR_ARG, "co_import_result_clip: null result");
    if (index < 0 || static_cast<std::size_t>(index) >= r->clips.size()) {
        return fail_code(CO_ERR_RANGE, "co_import_result_clip: index out of range");
    }
    const auto& c = r->clips[static_cast<std::size_t>(index)];
    if (out_name != nullptr) *out_name = c.name.c_str();
    if (out_data != nullptr) *out_data = reinterpret_cast<const uint8_t*>(c.bytes.data());
    if (out_size != nullptr) *out_size = static_cast<int64_t>(c.bytes.size());
    return CO_OK;
}

int32_t co_import_result_warning_count(const co_import_result* r) {
    return r == nullptr ? 0 : static_cast<int32_t>(r->warnings.size());
}

const char* co_import_result_warning(const co_import_result* r, int32_t index) {
    if (r == nullptr || index < 0 || static_cast<std::size_t>(index) >= r->warnings.size()) return nullptr;
    return r->warnings[static_cast<std::size_t>(index)].c_str();
}

int32_t co_import_result_counts(const co_import_result* r, co_import_counts* out) {
    if (r == nullptr || out == nullptr) return fail_code(CO_ERR_ARG, "co_import_result_counts: null argument");
    *out = r->counts;
    return CO_OK;
}

}  // extern "C"
