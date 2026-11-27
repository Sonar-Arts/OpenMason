// Cenda model normals (OpenMason #169): Blender corner normals over an OMO soup, octahedral snorm16 encoding, the
// Java-equal position weld. See cenda/model_shading.h for the rules. No exception leaves this file.
#define CENDA_MODEL_SHADING_INTERNAL 1
#include "model_internal.hpp"
#include "cenda/model_shading.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <exception>
#include <limits>
#include <numeric>
#include <string>
#include <vector>

namespace cenda::model::shading_detail {

namespace {

std::string& last_error() { return cenda::model::co_error_message(); }   // the one store behind co_last_error

constexpr double kPi = 3.14159265358979323846;

struct Vec3 {
    double x = 0, y = 0, z = 0;
};

Vec3 sub(const Vec3& a, const Vec3& b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
Vec3 add(const Vec3& a, const Vec3& b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
Vec3 scale(const Vec3& a, double s) { return {a.x * s, a.y * s, a.z * s}; }
double dot(const Vec3& a, const Vec3& b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
Vec3 cross(const Vec3& a, const Vec3& b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
double length(const Vec3& a) { return std::sqrt(dot(a, a)); }

// The angle between two vectors in [0, pi]; 0 when either is zero.
double angle_between(const Vec3& a, const Vec3& b) {
    const double r = std::atan2(length(cross(a, b)), dot(a, b));
    return std::isfinite(r) ? r : 0.0;
}

// Java's (int) Math.floor(f): saturating, NaN -> 0.
int32_t java_floor_int(float f) {
    if (std::isnan(f)) return 0;
    const double d = std::floor(static_cast<double>(f));
    if (d >= 2147483647.0) return std::numeric_limits<int32_t>::max();
    if (d <= -2147483648.0) return std::numeric_limits<int32_t>::min();
    return static_cast<int32_t>(d);
}

struct CellEntry {
    int32_t cx, cy, cz;
    int32_t index;
    bool operator<(const CellEntry& o) const {
        if (cx != o.cx) return cx < o.cx;
        if (cy != o.cy) return cy < o.cy;
        if (cz != o.cz) return cz < o.cz;
        return index < o.index;
    }
};

// The sorted cell list (cell = 2 epsilon, VertexWelder's grid). visit(cx, cy, cz, f) calls f(index) for the entries
// of one cell in ascending index order until f returns false.
class CellGrid {
public:
    CellGrid(const float* points, const int32_t* subset, int64_t count, float cell) : points_(points), cell_(cell) {
        entries_.reserve(static_cast<std::size_t>(count));
        for (int64_t i = 0; i < count; ++i) {
            const int32_t p = subset != nullptr ? subset[i] : static_cast<int32_t>(i);
            entries_.push_back(cell_at(points_ + 3 * static_cast<std::ptrdiff_t>(p), p));
        }
        std::sort(entries_.begin(), entries_.end());
    }

    [[nodiscard]] CellEntry cell_at(const float* q, int32_t index) const {
        return {java_floor_int(q[0] / cell_), java_floor_int(q[1] / cell_), java_floor_int(q[2] / cell_), index};
    }

    // The cells (cx, cy, cz - 1 .. cz + 1): contiguous in the sorted list, so one search per row. Entries come in
    // (cz, index) order, i.e. VertexWelder's dz-inner order. f returns false to stop the row.
    template <typename F>
    void visit_row(int64_t cx, int64_t cy, int64_t cz, F&& f) const {
        constexpr int64_t lo = std::numeric_limits<int32_t>::min();
        constexpr int64_t hi = std::numeric_limits<int32_t>::max();
        if (cx < lo || cx > hi || cy < lo || cy > hi) return;
        const int64_t z0 = std::max(cz - 1, lo);
        const int64_t z1 = std::min(cz + 1, hi);
        const CellEntry key{static_cast<int32_t>(cx), static_cast<int32_t>(cy), static_cast<int32_t>(z0),
                            std::numeric_limits<int32_t>::min()};
        auto it = std::lower_bound(entries_.begin(), entries_.end(), key);
        for (; it != entries_.end() && it->cx == key.cx && it->cy == key.cy && it->cz <= z1; ++it) {
            if (!f(*it)) return;
        }
    }

private:
    const float* points_;
    float cell_;
    std::vector<CellEntry> entries_;
};

float distance_sq(const float* a, const float* b) {
    const float dx = a[0] - b[0];
    const float dy = a[1] - b[1];
    const float dz = a[2] - b[2];
    return dx * dx + dy * dy + dz * dz;
}

Vec3 point(const float* positions, int32_t v) {
    const float* p = positions + 3 * static_cast<std::ptrdiff_t>(v);
    return {p[0], p[1], p[2]};
}

void oct_decode_d(int16_t ox, int16_t oy, double out[3]) {
    double u = std::max(static_cast<double>(ox) / 32767.0, -1.0);
    double v = std::max(static_cast<double>(oy) / 32767.0, -1.0);
    const double z = 1.0 - std::fabs(u) - std::fabs(v);
    const double t = std::max(-z, 0.0);
    u += u >= 0.0 ? -t : t;
    v += v >= 0.0 ? -t : t;
    const double len = std::sqrt(u * u + v * v + z * z);
    out[0] = u / len;
    out[1] = v / len;
    out[2] = z / len;
}

int16_t clamp_snorm(double f) { return static_cast<int16_t>(std::clamp(f, -32767.0, 32767.0)); }

uint64_t edge_key(int32_t a, int32_t b) {
    const auto lo = static_cast<uint32_t>(std::min(a, b));
    const auto hi = static_cast<uint32_t>(std::max(a, b));
    return (static_cast<uint64_t>(lo) << 32) | hi;
}

uint8_t flag(bool b) { return b ? uint8_t{1} : uint8_t{0}; }

uint8_t saturating_inc(uint8_t n) { return n < 2 ? static_cast<uint8_t>(n + 1) : n; }

}  // namespace

int32_t fail(int32_t code, const std::string& message) {
    last_error() = message;
    return code;
}

void clear_error() { last_error().clear(); }

const char* last_error_text() { return last_error().c_str(); }

float effective_epsilon(float epsilon) {
    return (epsilon > 0.0f && std::isfinite(epsilon)) ? epsilon : CO_SHADING_DEFAULT_WELD_EPSILON;
}

void oct_encode(const float* n, int16_t* out) {
    const double x = n[0], y = n[1], z = n[2];
    const double l1 = std::fabs(x) + std::fabs(y) + std::fabs(z);
    out[0] = 0;
    out[1] = 0;
    if (!(l1 > 0.0) || !std::isfinite(l1)) return;
    const double len = std::sqrt(x * x + y * y + z * z);
    const double ref[3] = {x / len, y / len, z / len};
    double u = x / l1, v = y / l1;
    if (z < 0.0) {
        const double ou = (1.0 - std::fabs(v)) * (u >= 0.0 ? 1.0 : -1.0);
        const double ov = (1.0 - std::fabs(u)) * (v >= 0.0 ? 1.0 : -1.0);
        u = ou;
        v = ov;
    }
    const double fu = u * 32767.0, fv = v * 32767.0;
    const int16_t cu[2] = {clamp_snorm(std::floor(fu)), clamp_snorm(std::ceil(fu))};
    const int16_t cv[2] = {clamp_snorm(std::floor(fv)), clamp_snorm(std::ceil(fv))};
    double best = -2.0;
    for (int j = 0; j < 2; ++j) {
        for (int i = 0; i < 2; ++i) {
            double d[3];
            oct_decode_d(cu[i], cv[j], d);
            const double score = d[0] * ref[0] + d[1] * ref[1] + d[2] * ref[2];
            if (score > best) {
                best = score;
                out[0] = cu[i];
                out[1] = cv[j];
            }
        }
    }
}

void oct_decode(const int16_t* o, float* out) {
    double d[3];
    oct_decode_d(o[0], o[1], d);
    out[0] = static_cast<float>(d[0]);
    out[1] = static_cast<float>(d[1]);
    out[2] = static_cast<float>(d[2]);
}

void weld_vertices(const float* positions, int64_t vertex_count, const uint8_t* use, float epsilon,
                   std::vector<int32_t>& out_weld, std::vector<int32_t>& out_rep_vertex) {
    out_weld.assign(static_cast<std::size_t>(vertex_count), -1);
    out_rep_vertex.clear();
    std::vector<int32_t> used;
    used.reserve(static_cast<std::size_t>(vertex_count));
    for (int64_t v = 0; v < vertex_count; ++v) {
        if (use == nullptr || use[v] != 0) used.push_back(static_cast<int32_t>(v));
    }
    const float cell = epsilon * 2.0f;
    const float eps_sq = epsilon * epsilon;
    const CellGrid grid(positions, used.data(), static_cast<int64_t>(used.size()), cell);
    for (const int32_t v : used) {
        const float* p = positions + 3 * static_cast<std::ptrdiff_t>(v);
        const CellEntry c = grid.cell_at(p, v);
        int32_t matched = -1;
        // VertexWelder's order: dx, then dy, then dz; earlier vertices of a cell in visiting (= index) order.
        for (int dx = -1; dx <= 1 && matched < 0; ++dx) {
            for (int dy = -1; dy <= 1 && matched < 0; ++dy) {
                grid.visit_row(int64_t{c.cx} + dx, int64_t{c.cy} + dy, c.cz, [&](const CellEntry& e) {
                    if (e.index >= v) return true;  // not visited yet: skip to the next cell of the row
                    if (distance_sq(p, positions + 3 * static_cast<std::ptrdiff_t>(e.index)) < eps_sq) {
                        matched = out_weld[static_cast<std::size_t>(e.index)];
                        return false;
                    }
                    return true;
                });
            }
        }
        if (matched >= 0) {
            out_weld[static_cast<std::size_t>(v)] = matched;
        } else {
            out_weld[static_cast<std::size_t>(v)] = static_cast<int32_t>(out_rep_vertex.size());
            out_rep_vertex.push_back(v);
        }
    }
}

void resolve_points(const float* points, const int32_t* point_weld, int64_t point_count, float epsilon,
                    const float* queries, int64_t query_count, int32_t* out_weld) {
    const float cell = epsilon * 2.0f;
    const float eps_sq = epsilon * epsilon;
    const CellGrid grid(points, nullptr, point_count, cell);
    for (int64_t q = 0; q < query_count; ++q) {
        const float* qp = queries + 3 * q;
        const CellEntry c = grid.cell_at(qp, 0);
        int32_t best = -1;
        float best_d = 0.0f;
        for (int dx = -1; dx <= 1; ++dx) {
            for (int dy = -1; dy <= 1; ++dy) {
                grid.visit_row(int64_t{c.cx} + dx, int64_t{c.cy} + dy, c.cz, [&](const CellEntry& e) {
                    const float d = distance_sq(qp, points + 3 * static_cast<std::ptrdiff_t>(e.index));
                    if (d < eps_sq && (best < 0 || d < best_d || (d == best_d && e.index < best))) {
                        best = e.index;
                        best_d = d;
                    }
                    return true;
                });
            }
        }
        out_weld[q] = best < 0 ? -1 : (point_weld != nullptr ? point_weld[best] : best);
    }
}

int32_t validate_soup(const float* positions, int64_t vertex_count, const int32_t* indices, int64_t index_count,
                      const int32_t* tri_face_ids, const std::string& name) {
    if (vertex_count < 0 || index_count < 0) return fail(CO_ERR_ARG, name + ": negative count");
    if (vertex_count > std::numeric_limits<int32_t>::max()) return fail(CO_ERR_ARG, name + ": too many vertices");
    if (index_count % 3 != 0) return fail(CO_ERR_ARG, name + ": index count is not a multiple of 3");
    if (vertex_count > 0 && positions == nullptr) return fail(CO_ERR_ARG, name + ": null positions");
    if (index_count > 0 && indices == nullptr) return fail(CO_ERR_ARG, name + ": null indices");
    const int64_t triangles = index_count / 3;
    if (tri_face_ids == nullptr && triangles > std::numeric_limits<int32_t>::max()) {
        return fail(CO_ERR_ARG, name + ": too many triangles");
    }
    for (int64_t i = 0; i < index_count; ++i) {
        if (indices[i] < 0 || indices[i] >= vertex_count) {
            return fail(CO_ERR_RANGE, name + ": index " + std::to_string(i) + " out of range");
        }
    }
    if (tri_face_ids != nullptr) {
        for (int64_t t = 0; t < triangles; ++t) {
            if (tri_face_ids[t] < 0) {
                return fail(CO_ERR_RANGE, name + ": negative face id at triangle " + std::to_string(t));
            }
        }
    }
    return CO_OK;
}

int32_t prepare_soup(Soup& soup, const float* positions, int64_t vertex_count, const int32_t* indices,
                     int64_t index_count, const int32_t* tri_face_ids, float epsilon, const char* fn) {
    if (int32_t rc = validate_soup(positions, vertex_count, indices, index_count, tri_face_ids, fn)) return rc;
    const int64_t triangles = index_count / 3;
    soup.positions = positions;
    soup.vertex_count = vertex_count;
    soup.indices = indices;
    soup.triangle_count = triangles;
    soup.tri_face_ids = tri_face_ids;

    soup.face_of.assign(static_cast<std::size_t>(vertex_count), -1);
    for (int64_t t = 0; t < triangles; ++t) {
        for (int k = 0; k < 3; ++k) {
            auto& f = soup.face_of[static_cast<std::size_t>(indices[3 * t + k])];
            if (f < 0) f = soup.face(t);
        }
    }
    std::vector<uint8_t> use(static_cast<std::size_t>(vertex_count));
    for (std::size_t v = 0; v < use.size(); ++v) use[v] = flag(soup.face_of[v] >= 0);
    weld_vertices(positions, vertex_count, use.data(), effective_epsilon(epsilon), soup.weld, soup.rep_vertex);

    soup.faces.resize(static_cast<std::size_t>(triangles));
    for (int64_t t = 0; t < triangles; ++t) soup.faces[static_cast<std::size_t>(t)] = soup.face(t);
    std::sort(soup.faces.begin(), soup.faces.end());
    soup.faces.erase(std::unique(soup.faces.begin(), soup.faces.end()), soup.faces.end());

    // Polygon boundary edges: a face's triangle edges with the opposite uses (internal diagonals) cancelled.
    struct Use {
        int32_t face, lo, hi, dir;
    };
    std::vector<Use> uses;
    uses.reserve(static_cast<std::size_t>(triangles) * 3);
    for (int64_t t = 0; t < triangles; ++t) {
        const int32_t f = soup.face(t);
        for (int k = 0; k < 3; ++k) {
            const int32_t a = soup.weld[static_cast<std::size_t>(indices[3 * t + k])];
            const int32_t b = soup.weld[static_cast<std::size_t>(indices[3 * t + (k + 1) % 3])];
            if (a == b) continue;
            uses.push_back(a < b ? Use{f, a, b, 1} : Use{f, b, a, -1});
        }
    }
    std::sort(uses.begin(), uses.end(), [](const Use& a, const Use& b) {
        if (a.face != b.face) return a.face < b.face;
        if (a.lo != b.lo) return a.lo < b.lo;
        return a.hi < b.hi;
    });
    soup.boundary.clear();
    for (std::size_t i = 0; i < uses.size();) {
        std::size_t j = i;
        int32_t net = 0;
        while (j < uses.size() && uses[j].face == uses[i].face && uses[j].lo == uses[i].lo && uses[j].hi == uses[i].hi) {
            net += uses[j].dir;
            ++j;
        }
        if (net > 0) soup.boundary.push_back({uses[i].face, uses[i].lo, uses[i].hi});
        if (net < 0) soup.boundary.push_back({uses[i].face, uses[i].hi, uses[i].lo});
        i = j;
    }
    std::sort(soup.boundary.begin(), soup.boundary.end(), [](const BoundaryEdge& a, const BoundaryEdge& b) {
        if (a.face != b.face) return a.face < b.face;
        if (a.from != b.from) return a.from < b.from;
        return a.to < b.to;
    });
    return CO_OK;
}

void resolve_in_soup(const Soup& soup, float epsilon, const float* queries, int64_t query_count, int32_t* out_weld) {
    std::vector<float> points;
    std::vector<int32_t> welds;
    points.reserve(static_cast<std::size_t>(soup.vertex_count) * 3);
    for (int64_t v = 0; v < soup.vertex_count; ++v) {
        const int32_t w = soup.weld[static_cast<std::size_t>(v)];
        if (w < 0) continue;
        points.insert(points.end(), soup.positions + 3 * v, soup.positions + 3 * v + 3);
        welds.push_back(w);
    }
    resolve_points(points.data(), welds.data(), static_cast<int64_t>(welds.size()), effective_epsilon(epsilon),
                   queries, query_count, out_weld);
}

int32_t compute_normals(const Soup& soup, const uint32_t* smooth_words, int64_t smooth_word_count, bool all_smooth,
                        const std::vector<uint64_t>& sharp, float auto_smooth_degrees,
                        const std::vector<CustomRow>& custom, float* out_xyz, int16_t* out_oct) {
    const int64_t triangles = soup.triangle_count;
    const std::size_t face_count = soup.faces.size();
    // Face id -> dense index: a table when the ids are compact enough, else a binary search.
    std::vector<int32_t> face_table;
    if (!soup.faces.empty() && static_cast<int64_t>(soup.faces.back()) <= 8 * triangles + 1024) {
        face_table.assign(static_cast<std::size_t>(soup.faces.back()) + 1, -1);
        for (std::size_t f = 0; f < face_count; ++f) face_table[static_cast<std::size_t>(soup.faces[f])] = static_cast<int32_t>(f);
    }
    const auto dense = [&](int32_t face_id) {
        if (!face_table.empty()) return static_cast<std::size_t>(face_table[static_cast<std::size_t>(face_id)]);
        return static_cast<std::size_t>(std::lower_bound(soup.faces.begin(), soup.faces.end(), face_id) -
                                        soup.faces.begin());
    };
    const auto rep = [&](int32_t w) { return point(soup.positions, soup.rep_vertex[static_cast<std::size_t>(w)]); };
    const auto tri_weld = [&](int64_t t, int k) {
        return soup.weld[static_cast<std::size_t>(soup.indices[3 * t + k])];
    };

    // Faces: vector-area normal (= Newell), validity, smooth flag.
    std::vector<std::size_t> tri_face(static_cast<std::size_t>(triangles));
    std::vector<Vec3> face_area(face_count);
    std::vector<double> face_edge_sq(face_count, 0.0);
    for (int64_t t = 0; t < triangles; ++t) {
        const std::size_t f = dense(soup.face(t));
        tri_face[static_cast<std::size_t>(t)] = f;
        const Vec3 a = rep(tri_weld(t, 0)), b = rep(tri_weld(t, 1)), c = rep(tri_weld(t, 2));
        const Vec3 ab = sub(b, a), ac = sub(c, a), bc = sub(c, b);
        face_area[f] = add(face_area[f], cross(ab, ac));
        face_edge_sq[f] = std::max({face_edge_sq[f], dot(ab, ab), dot(ac, ac), dot(bc, bc)});
    }
    std::vector<Vec3> face_normal(face_count);
    std::vector<uint8_t> face_valid(face_count, 0);
    std::vector<uint8_t> face_smooth(face_count, 0);
    for (std::size_t f = 0; f < face_count; ++f) {
        const double len = length(face_area[f]);
        if (std::isfinite(len) && len > 0.0 && len > 1e-10 * face_edge_sq[f]) {
            face_normal[f] = scale(face_area[f], 1.0 / len);
            face_valid[f] = 1;
        }
        const auto id = static_cast<uint32_t>(soup.faces[f]);
        face_smooth[f] = flag(all_smooth || (static_cast<int64_t>(id / 32) < smooth_word_count &&
                                             ((smooth_words[id / 32] >> (id % 32)) & 1u) != 0));
    }

    // Corners = (dense face, weld), from the triangle corners.
    std::vector<uint64_t> corner_keys;
    corner_keys.reserve(static_cast<std::size_t>(triangles) * 3);
    for (int64_t t = 0; t < triangles; ++t) {
        for (int k = 0; k < 3; ++k) {
            corner_keys.push_back((static_cast<uint64_t>(tri_face[static_cast<std::size_t>(t)]) << 32) |
                                  static_cast<uint32_t>(tri_weld(t, k)));
        }
    }
    std::sort(corner_keys.begin(), corner_keys.end());
    corner_keys.erase(std::unique(corner_keys.begin(), corner_keys.end()), corner_keys.end());
    const std::size_t corner_count = corner_keys.size();
    // A face's corners are one sorted run: corner_of scans the face's few corners.
    std::vector<std::size_t> face_corners(face_count + 1, 0);
    for (std::size_t c = 0; c < corner_count; ++c) ++face_corners[static_cast<std::size_t>(corner_keys[c] >> 32) + 1];
    for (std::size_t f = 0; f < face_count; ++f) face_corners[f + 1] += face_corners[f];
    const auto corner_of = [&](std::size_t f, int32_t w) {
        const uint64_t key = (static_cast<uint64_t>(f) << 32) | static_cast<uint32_t>(w);
        const auto begin = corner_keys.begin() + static_cast<std::ptrdiff_t>(face_corners[f]);
        const auto end = corner_keys.begin() + static_cast<std::ptrdiff_t>(face_corners[f + 1]);
        if (end - begin <= 16) {
            for (auto it = begin; it != end; ++it) {
                if (*it == key) return static_cast<std::size_t>(it - corner_keys.begin());
            }
        }
        return static_cast<std::size_t>(std::lower_bound(begin, end, key) - corner_keys.begin());
    };
    const auto corner_face = [&](std::size_t c) { return static_cast<std::size_t>(corner_keys[c] >> 32); };
    const auto corner_weld = [&](std::size_t c) { return static_cast<int32_t>(corner_keys[c] & 0xFFFFFFFFu); };

    // Corner angles from the polygon boundary (the in and out edge at the corner), else the triangles' angle sum.
    std::vector<int32_t> in_nb(corner_count, -1), out_nb(corner_count, -1);
    std::vector<uint8_t> in_n(corner_count, 0), out_n(corner_count, 0);
    for (const BoundaryEdge& e : soup.boundary) {
        const std::size_t f = dense(e.face);
        const std::size_t cf = corner_of(f, e.from), ct = corner_of(f, e.to);
        out_nb[cf] = e.to;
        out_n[cf] = saturating_inc(out_n[cf]);
        in_nb[ct] = e.from;
        in_n[ct] = saturating_inc(in_n[ct]);
    }
    std::vector<double> angle(corner_count, 0.0);
    std::vector<uint8_t> by_triangles(corner_count, 0);
    for (std::size_t c = 0; c < corner_count; ++c) {
        if (in_n[c] == 1 && out_n[c] == 1) {
            const Vec3 p = rep(corner_weld(c));
            angle[c] = angle_between(sub(rep(in_nb[c]), p), sub(rep(out_nb[c]), p));
        } else {
            by_triangles[c] = 1;
        }
    }
    for (int64_t t = 0; t < triangles; ++t) {
        const std::size_t f = tri_face[static_cast<std::size_t>(t)];
        for (int k = 0; k < 3; ++k) {
            const int32_t w = tri_weld(t, k);
            const std::size_t c = corner_of(f, w);
            if (by_triangles[c] == 0) continue;
            const int32_t a = tri_weld(t, (k + 1) % 3), b = tri_weld(t, (k + 2) % 3);
            if (a == w || b == w) continue;
            const Vec3 p = rep(w);
            angle[c] += angle_between(sub(rep(a), p), sub(rep(b), p));
        }
    }
    for (std::size_t c = 0; c < corner_count; ++c) {
        if (by_triangles[c] != 0) angle[c] = std::clamp(angle[c], 0.0, kPi);
    }

    // Fans: union the corners of two faces around a vertex across every smooth edge.
    std::vector<std::size_t> parent(corner_count);
    std::iota(parent.begin(), parent.end(), std::size_t{0});
    const auto find_root = [&](std::size_t c) {
        while (parent[c] != c) {
            parent[c] = parent[parent[c]];
            c = parent[c];
        }
        return c;
    };
    const auto unite = [&](std::size_t a, std::size_t b) {
        a = find_root(a);
        b = find_root(b);
        if (a == b) return;
        if (b < a) std::swap(a, b);
        parent[b] = a;
    };
    const bool auto_on = auto_smooth_degrees > 0.0f && std::isfinite(auto_smooth_degrees);
    const double auto_cos = auto_on ? std::cos(static_cast<double>(auto_smooth_degrees) * kPi / 180.0) : -2.0;
    struct EdgeUse {
        int32_t lo, hi;
        std::size_t face;
        int32_t dir;
    };
    std::vector<EdgeUse> edge_uses;
    edge_uses.reserve(soup.boundary.size());
    for (const BoundaryEdge& e : soup.boundary) {
        const std::size_t f = dense(e.face);
        edge_uses.push_back(e.from < e.to ? EdgeUse{e.from, e.to, f, 1} : EdgeUse{e.to, e.from, f, -1});
    }
    std::sort(edge_uses.begin(), edge_uses.end(), [](const EdgeUse& a, const EdgeUse& b) {
        if (a.lo != b.lo) return a.lo < b.lo;
        if (a.hi != b.hi) return a.hi < b.hi;
        return a.face < b.face;
    });
    for (std::size_t i = 0; i < edge_uses.size();) {
        std::size_t j = i;
        while (j < edge_uses.size() && edge_uses[j].lo == edge_uses[i].lo && edge_uses[j].hi == edge_uses[i].hi) ++j;
        if (j - i == 2) {
            const EdgeUse& u0 = edge_uses[i];
            const EdgeUse& u1 = edge_uses[i + 1];
            const std::size_t f0 = u0.face, f1 = u1.face;
            bool smooth_edge = f0 != f1 && u0.dir != u1.dir && face_smooth[f0] != 0 && face_smooth[f1] != 0 &&
                               !std::binary_search(sharp.begin(), sharp.end(), edge_key(u0.lo, u0.hi));
            if (smooth_edge && auto_on && face_valid[f0] != 0 && face_valid[f1] != 0 &&
                dot(face_normal[f0], face_normal[f1]) < auto_cos) {
                smooth_edge = false;
            }
            if (smooth_edge) {
                unite(corner_of(f0, u0.lo), corner_of(f1, u0.lo));
                unite(corner_of(f0, u0.hi), corner_of(f1, u0.hi));
            }
        }
        i = j;
    }

    // Fan sums (corner-angle-weighted face normals), fan sizes, and the per-vertex fallback sums.
    std::vector<Vec3> fan_sum(corner_count);
    std::vector<int32_t> fan_size(corner_count, 0);
    std::vector<Vec3> weld_sum(soup.rep_vertex.size());
    for (std::size_t c = 0; c < corner_count; ++c) {
        const std::size_t f = corner_face(c);
        const std::size_t r = find_root(c);
        ++fan_size[r];
        if (face_valid[f] == 0) continue;
        const Vec3 weighted = scale(face_normal[f], angle[c]);
        if (face_smooth[f] != 0) fan_sum[r] = add(fan_sum[r], weighted);
        auto& ws = weld_sum[static_cast<std::size_t>(corner_weld(c))];
        ws = add(ws, weighted);
    }
    const auto store = [](const Vec3& n, float* dst) {
        dst[0] = static_cast<float>(n.x);
        dst[1] = static_cast<float>(n.y);
        dst[2] = static_cast<float>(n.z);
    };
    const auto normalized = [](const Vec3& v, Vec3& result) {
        const double len = length(v);
        if (!(len > 1e-300) || !std::isfinite(len)) return false;
        result = scale(v, 1.0 / len);
        return true;
    };
    std::vector<float> corner_normal(corner_count * 3);
    for (std::size_t c = 0; c < corner_count; ++c) {
        const std::size_t f = corner_face(c);
        float* dst = corner_normal.data() + 3 * c;
        const std::size_t r = find_root(c);
        Vec3 n;
        if (face_valid[f] != 0 && (face_smooth[f] == 0 || fan_size[r] == 1)) {
            store(face_normal[f], dst);
        } else if (face_smooth[f] != 0 && normalized(fan_sum[r], n)) {
            store(n, dst);
        } else if (face_valid[f] != 0) {
            store(face_normal[f], dst);
        } else if (normalized(weld_sum[static_cast<std::size_t>(corner_weld(c))], n)) {
            store(n, dst);
        } else {
            store(Vec3{0.0, 0.0, 1.0}, dst);
        }
    }

    // Per soup vertex, then the custom overrides.
    for (int64_t v = 0; v < soup.vertex_count; ++v) {
        float* dst = out_xyz + 3 * v;
        const int32_t w = soup.weld[static_cast<std::size_t>(v)];
        if (w < 0) {
            dst[0] = 0.0f;
            dst[1] = 0.0f;
            dst[2] = 1.0f;
        } else {
            const std::size_t c = corner_of(dense(soup.face_of[static_cast<std::size_t>(v)]), w);
            std::copy_n(corner_normal.data() + 3 * c, 3, dst);
        }
        if (out_oct != nullptr) oct_encode(dst, out_oct + 2 * v);
    }
    for (const CustomRow& row : custom) {
        if (row.vertex < 0 || row.vertex >= soup.vertex_count) continue;
        oct_decode(row.oct, out_xyz + 3 * static_cast<std::ptrdiff_t>(row.vertex));
        if (out_oct != nullptr) {
            out_oct[2 * static_cast<std::ptrdiff_t>(row.vertex)] = row.oct[0];
            out_oct[2 * static_cast<std::ptrdiff_t>(row.vertex) + 1] = row.oct[1];
        }
    }
    return CO_OK;
}

namespace {

// co_mesh_normals' body: one soup, triangles optionally split into groups that shade apart.
int32_t grouped_normals(const float* positions, int64_t vertex_count, const int32_t* indices, int64_t index_count,
                        const int32_t* tri_face_ids, const int32_t* tri_groups, const uint32_t* smooth_words,
                        int64_t smooth_word_count, bool all_smooth, const uint32_t* sharp_pairs,
                        int64_t sharp_edge_count, float auto_smooth_degrees, const int32_t* custom_vertices,
                        const int16_t* custom_oct, int64_t custom_count, float weld_epsilon, float* out_xyz,
                        int16_t* out_oct, const char* fn) {
    const std::string name(fn);
    if (out_xyz == nullptr && vertex_count > 0) return fail(CO_ERR_ARG, name + ": null out_xyz");
    if (smooth_word_count < 0 || (smooth_word_count > 0 && smooth_words == nullptr)) {
        return fail(CO_ERR_ARG, name + ": bad smooth words");
    }
    if (sharp_edge_count < 0 || (sharp_edge_count > 0 && sharp_pairs == nullptr)) {
        return fail(CO_ERR_ARG, name + ": bad sharp pairs");
    }
    if (custom_count < 0 || (custom_count > 0 && (custom_vertices == nullptr || custom_oct == nullptr))) {
        return fail(CO_ERR_ARG, name + ": bad custom normals");
    }
    const float eps = effective_epsilon(weld_epsilon);
    const auto in_range = [&](uint32_t v) { return static_cast<int64_t>(v) < vertex_count; };

    if (tri_groups == nullptr) {
        Soup soup;
        if (int32_t rc = prepare_soup(soup, positions, vertex_count, indices, index_count, tri_face_ids, eps, fn)) {
            return rc;
        }
        std::vector<uint64_t> sharp;
        for (int64_t e = 0; e < sharp_edge_count; ++e) {
            const uint32_t a = sharp_pairs[2 * e], b = sharp_pairs[2 * e + 1];
            if (!in_range(a) || !in_range(b)) continue;
            const int32_t wa = soup.weld[a], wb = soup.weld[b];
            if (wa < 0 || wb < 0 || wa == wb) continue;
            sharp.push_back(edge_key(wa, wb));
        }
        std::sort(sharp.begin(), sharp.end());
        sharp.erase(std::unique(sharp.begin(), sharp.end()), sharp.end());
        std::vector<CustomRow> custom;
        custom.reserve(static_cast<std::size_t>(custom_count));
        for (int64_t i = 0; i < custom_count; ++i) {
            custom.push_back({custom_vertices[i], {custom_oct[2 * i], custom_oct[2 * i + 1]}});
        }
        return compute_normals(soup, smooth_words, smooth_word_count, all_smooth, sharp, auto_smooth_degrees, custom,
                               out_xyz, out_oct);
    }

    // Groups: validate the whole soup once, then shade each group's compacted sub-soup.
    if (int32_t rc = validate_soup(positions, vertex_count, indices, index_count, tri_face_ids, name)) return rc;
    const int64_t triangles = index_count / 3;
    std::vector<int64_t> first_tri(static_cast<std::size_t>(vertex_count), -1);
    for (int64_t t = 0; t < triangles; ++t) {
        for (int k = 0; k < 3; ++k) {
            auto& ft = first_tri[static_cast<std::size_t>(indices[3 * t + k])];
            if (ft < 0) ft = t;
        }
    }
    for (int64_t v = 0; v < vertex_count; ++v) {
        out_xyz[3 * v] = 0.0f;
        out_xyz[3 * v + 1] = 0.0f;
        out_xyz[3 * v + 2] = 1.0f;
        if (out_oct != nullptr) {
            out_oct[2 * v] = 0;
            out_oct[2 * v + 1] = 0;
        }
    }
    // Sharp pairs as positions (they resolve in every group).
    std::vector<float> sharp_points;
    for (int64_t e = 0; e < sharp_edge_count; ++e) {
        const uint32_t a = sharp_pairs[2 * e], b = sharp_pairs[2 * e + 1];
        if (!in_range(a) || !in_range(b) || first_tri[a] < 0 || first_tri[b] < 0) continue;
        sharp_points.insert(sharp_points.end(), positions + 3 * static_cast<std::ptrdiff_t>(a),
                            positions + 3 * static_cast<std::ptrdiff_t>(a) + 3);
        sharp_points.insert(sharp_points.end(), positions + 3 * static_cast<std::ptrdiff_t>(b),
                            positions + 3 * static_cast<std::ptrdiff_t>(b) + 3);
    }
    std::vector<std::size_t> order(static_cast<std::size_t>(triangles));
    std::iota(order.begin(), order.end(), std::size_t{0});
    std::stable_sort(order.begin(), order.end(),
                     [&](std::size_t a, std::size_t b) { return tri_groups[a] < tri_groups[b]; });

    std::vector<int32_t> local_of(static_cast<std::size_t>(vertex_count), -1);
    std::vector<int32_t> globals, local_indices, local_faces, sharp_welds;
    std::vector<float> local_positions, local_xyz;
    std::vector<int16_t> local_oct;
    std::vector<uint64_t> sharp;
    std::vector<CustomRow> custom;
    for (std::size_t i = 0; i < order.size();) {
        const int32_t group = tri_groups[order[i]];
        std::size_t j = i;
        while (j < order.size() && tri_groups[order[j]] == group) ++j;
        globals.clear();
        for (std::size_t k = i; k < j; ++k) {
            for (std::size_t c = 0; c < 3; ++c) {
                const int32_t v = indices[3 * order[k] + c];
                if (local_of[static_cast<std::size_t>(v)] < 0) {
                    local_of[static_cast<std::size_t>(v)] = 0;
                    globals.push_back(v);
                }
            }
        }
        std::sort(globals.begin(), globals.end());  // ascending soup order = the weld's visiting order
        local_positions.resize(globals.size() * 3);
        for (std::size_t l = 0; l < globals.size(); ++l) {
            local_of[static_cast<std::size_t>(globals[l])] = static_cast<int32_t>(l);
            std::copy_n(positions + 3 * static_cast<std::ptrdiff_t>(globals[l]), 3, local_positions.data() + 3 * l);
        }
        local_indices.clear();
        local_faces.clear();
        for (std::size_t k = i; k < j; ++k) {
            const std::size_t t = order[k];
            for (std::size_t c = 0; c < 3; ++c) local_indices.push_back(local_of[static_cast<std::size_t>(indices[3 * t + c])]);
            local_faces.push_back(tri_face_ids != nullptr ? tri_face_ids[t] : static_cast<int32_t>(t));
        }
        Soup soup;
        if (int32_t rc = prepare_soup(soup, local_positions.data(), static_cast<int64_t>(globals.size()),
                                      local_indices.data(), static_cast<int64_t>(local_indices.size()),
                                      local_faces.data(), eps, fn)) {
            return rc;
        }
        sharp_welds.assign(sharp_points.size() / 3, -1);
        resolve_in_soup(soup, eps, sharp_points.data(), static_cast<int64_t>(sharp_welds.size()), sharp_welds.data());
        sharp.clear();
        for (std::size_t e = 0; e + 1 < sharp_welds.size(); e += 2) {
            if (sharp_welds[e] < 0 || sharp_welds[e + 1] < 0 || sharp_welds[e] == sharp_welds[e + 1]) continue;
            sharp.push_back(edge_key(sharp_welds[e], sharp_welds[e + 1]));
        }
        std::sort(sharp.begin(), sharp.end());
        sharp.erase(std::unique(sharp.begin(), sharp.end()), sharp.end());
        custom.clear();
        for (int64_t r = 0; r < custom_count; ++r) {
            const int32_t v = custom_vertices[r];
            if (v < 0 || v >= vertex_count) continue;
            const int64_t ft = first_tri[static_cast<std::size_t>(v)];
            if (ft < 0 || tri_groups[ft] != group) continue;
            custom.push_back({local_of[static_cast<std::size_t>(v)], {custom_oct[2 * r], custom_oct[2 * r + 1]}});
        }
        local_xyz.resize(globals.size() * 3);
        local_oct.resize(globals.size() * 2);
        if (int32_t rc = compute_normals(soup, smooth_words, smooth_word_count, all_smooth, sharp, auto_smooth_degrees,
                                         custom, local_xyz.data(), out_oct != nullptr ? local_oct.data() : nullptr)) {
            return rc;
        }
        for (std::size_t l = 0; l < globals.size(); ++l) {
            const int32_t v = globals[l];
            if (tri_groups[first_tri[static_cast<std::size_t>(v)]] == group) {
                std::copy_n(local_xyz.data() + 3 * l, 3, out_xyz + 3 * static_cast<std::ptrdiff_t>(v));
                if (out_oct != nullptr) {
                    std::copy_n(local_oct.data() + 2 * l, 2, out_oct + 2 * static_cast<std::ptrdiff_t>(v));
                }
            }
            local_of[static_cast<std::size_t>(v)] = -1;
        }
        i = j;
    }
    return CO_OK;
}

template <typename F>
int32_t guarded(const char* fn, F&& body) {
    try {
        return body();
    } catch (const std::exception& e) {
        return fail(CO_ERR_ARG, std::string(fn) + ": " + e.what());
    } catch (...) {
        return fail(CO_ERR_ARG, std::string(fn) + ": unknown error");
    }
}

}  // namespace

}  // namespace cenda::model::shading_detail

namespace sd = cenda::model::shading_detail;

extern "C" {

const char* co_shading_last_error(void) { return sd::last_error_text(); }

int32_t co_oct_encode(const float* xyz, int16_t* out_oct, int64_t count) {
    if (count < 0 || (count > 0 && (xyz == nullptr || out_oct == nullptr))) {
        return sd::fail(CO_ERR_ARG, "co_oct_encode: bad arguments");
    }
    for (int64_t i = 0; i < count; ++i) sd::oct_encode(xyz + 3 * i, out_oct + 2 * i);
    sd::clear_error();
    return CO_OK;
}

int32_t co_oct_decode(const int16_t* oct, float* out_xyz, int64_t count) {
    if (count < 0 || (count > 0 && (oct == nullptr || out_xyz == nullptr))) {
        return sd::fail(CO_ERR_ARG, "co_oct_decode: bad arguments");
    }
    for (int64_t i = 0; i < count; ++i) sd::oct_decode(oct + 2 * i, out_xyz + 3 * i);
    sd::clear_error();
    return CO_OK;
}

int32_t co_mesh_normals(const float* positions, int64_t vertex_count, const int32_t* indices, int64_t index_count,
                        const int32_t* tri_face_ids, const int32_t* tri_groups, const uint32_t* smooth_words,
                        int64_t smooth_word_count, const uint32_t* sharp_pairs, int64_t sharp_edge_count,
                        float auto_smooth_degrees, const int32_t* custom_vertices, const int16_t* custom_oct,
                        int64_t custom_count, float weld_epsilon, float* out_xyz, int16_t* out_oct) {
    return sd::guarded("co_mesh_normals", [&] {
        const bool all_smooth = smooth_words == nullptr;
        const int32_t rc = sd::grouped_normals(
            positions, vertex_count, indices, index_count, tri_face_ids, tri_groups, smooth_words,
            all_smooth ? 0 : smooth_word_count, all_smooth, sharp_pairs, sharp_edge_count, auto_smooth_degrees,
            custom_vertices, custom_oct, custom_count, weld_epsilon, out_xyz, out_oct, "co_mesh_normals");
        if (rc == CO_OK) sd::clear_error();
        return rc;
    });
}

int32_t co_doc_compute_normals(const co_doc* d, float* out_xyz, int16_t* out_oct) {
    return sd::guarded("co_doc_compute_normals", [&]() -> int32_t {
        if (d == nullptr) return sd::fail(CO_ERR_ARG, "co_doc_compute_normals: null handle");
        if (out_xyz == nullptr) return sd::fail(CO_ERR_ARG, "co_doc_compute_normals: null out_xyz");
        const void* vdata = nullptr;
        const int64_t floats = co_doc_mesh_array(d, CO_MESH_VERTICES, &vdata);
        if (floats <= 0 || floats % 3 != 0) return sd::fail(CO_ERR_ARG, "co_doc_compute_normals: no mesh vertices");
        const void* idata = nullptr;
        int64_t index_count = co_doc_mesh_array(d, CO_MESH_INDICES, &idata);
        if (index_count < 0) index_count = 0;
        const int64_t triangles = index_count / 3;
        const void* fdata = nullptr;
        const int64_t face_count = co_doc_mesh_array(d, CO_MESH_TRIANGLE_FACE_IDS, &fdata);
        if (face_count >= 0 && face_count != triangles) {
            return sd::fail(CO_ERR_ARG, "co_doc_compute_normals: triangleToFaceId length != triangle count");
        }
        const auto* tri_faces = face_count >= 0 ? static_cast<const int32_t*>(fdata) : nullptr;

        const int32_t part_count = co_doc_parts_get(d, nullptr, 0);
        std::vector<int32_t> groups;
        if (part_count > 0) {
            std::vector<co_part> parts(static_cast<std::size_t>(part_count));
            co_doc_parts_get(d, parts.data(), part_count);
            groups.assign(static_cast<std::size_t>(triangles), -1);
            for (int32_t p = 0; p < part_count; ++p) {
                const co_part& part = parts[static_cast<std::size_t>(p)];
                const int64_t begin = std::clamp<int64_t>(part.index_start / 3, 0, triangles);
                const int64_t end =
                    std::clamp<int64_t>((int64_t{part.index_start} + int64_t{part.index_count}) / 3, begin, triangles);
                for (int64_t t = begin; t < end; ++t) groups[static_cast<std::size_t>(t)] = p;
            }
        }
        const uint32_t* words = nullptr;
        const int64_t word_count = co_doc_shading_smooth(d, &words);
        const uint32_t* pairs = nullptr;
        const int64_t edge_count = co_doc_shading_sharp_edges(d, &pairs);
        float degrees = 0.0f;
        if (co_doc_shading_auto_smooth(d, &degrees) != 1) degrees = 0.0f;
        const int32_t* custom_vertices = nullptr;
        const int16_t* custom_oct = nullptr;
        const int64_t custom_count = co_doc_shading_custom_normals(d, &custom_vertices, &custom_oct);

        const int32_t rc = sd::grouped_normals(
            static_cast<const float*>(vdata), floats / 3, static_cast<const int32_t*>(idata), triangles * 3, tri_faces,
            groups.empty() ? nullptr : groups.data(), words, std::max<int64_t>(word_count, 0), false, pairs,
            std::max<int64_t>(edge_count, 0), degrees, custom_vertices, custom_oct, std::max<int64_t>(custom_count, 0),
            CO_SHADING_DEFAULT_WELD_EPSILON, out_xyz, out_oct, "co_doc_compute_normals");
        if (rc == CO_OK) sd::clear_error();
        return rc;
    });
}

}  // extern "C"
