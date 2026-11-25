// Cenda rig: skin weights — storage, authoring operations (auto weights, brush, smooth, mirror,
// transfer), GPU packing and CPU deformation. Contract in cenda/rig.h.

#include "rig_internal.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <new>
#include <unordered_map>

namespace cenda::rig {
namespace {

struct Influences {
    int32_t count = 0;
    int32_t joint[CR_MAX_INFLUENCES]{};
    float weight[CR_MAX_INFLUENCES]{};
};

// A small joint -> weight accumulator used while editing one vertex.
struct WeightMap {
    std::vector<std::pair<int32_t, float>> items;

    void add(int32_t joint, float w) {
        for (auto& it : items) {
            if (it.first == joint) {
                it.second += w;
                return;
            }
        }
        items.emplace_back(joint, w);
    }

    float get(int32_t joint) const {
        for (const auto& it : items) {
            if (it.first == joint) return it.second;
        }
        return 0.f;
    }

    void set(int32_t joint, float w) {
        for (auto& it : items) {
            if (it.first == joint) {
                it.second = w;
                return;
            }
        }
        items.emplace_back(joint, w);
    }
};

// Keep the heaviest CR_MAX_INFLUENCES positive weights (ties: lower joint first), normalized.
Influences finalize(WeightMap& map) {
    auto& v = map.items;
    v.erase(std::remove_if(v.begin(), v.end(),
                           [](const std::pair<int32_t, float>& p) { return !(p.second > 0.f) || p.first < 0; }),
            v.end());
    std::sort(v.begin(), v.end(), [](const auto& a, const auto& b) {
        if (a.second != b.second) return a.second > b.second;
        return a.first < b.first;
    });
    if (v.size() > CR_MAX_INFLUENCES) v.resize(CR_MAX_INFLUENCES);
    Influences out;
    double sum = 0.0;
    for (const auto& p : v) sum += p.second;
    if (sum <= 0.0) return out;
    out.count = static_cast<int32_t>(v.size());
    for (size_t i = 0; i < v.size(); ++i) {
        out.joint[i] = v[i].first;
        out.weight[i] = static_cast<float>(v[i].second / sum);
    }
    return out;
}

WeightMap to_map(const Influences& inf) {
    WeightMap m;
    for (int32_t i = 0; i < inf.count; ++i) m.add(inf.joint[i], inf.weight[i]);
    return m;
}

// The starting weights of a vertex: authored, else its part joint at 1.
WeightMap effective(const Influences& inf, const int32_t* part_joint_of_vertex, int32_t v) {
    if (inf.count > 0) return to_map(inf);
    WeightMap m;
    if (part_joint_of_vertex != nullptr && part_joint_of_vertex[v] >= 0) m.add(part_joint_of_vertex[v], 1.f);
    return m;
}

double dist2_point_segment(const double p[3], const double a[3], const double b[3]) {
    double ab[3] = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
    double ap[3] = {p[0] - a[0], p[1] - a[1], p[2] - a[2]};
    const double len2 = ab[0] * ab[0] + ab[1] * ab[1] + ab[2] * ab[2];
    double t = len2 > 0.0 ? (ap[0] * ab[0] + ap[1] * ab[1] + ap[2] * ab[2]) / len2 : 0.0;
    t = std::clamp(t, 0.0, 1.0);
    double d[3] = {ap[0] - ab[0] * t, ap[1] - ab[1] * t, ap[2] - ab[2] * t};
    return d[0] * d[0] + d[1] * d[1] + d[2] * d[2];
}

// Uniform-grid nearest-neighbour search over a point set.
struct Grid {
    double cell = 1.0;
    double min[3]{};
    std::unordered_map<uint64_t, std::vector<int32_t>> cells;
    const float* points = nullptr;
    int32_t count = 0;

    static uint64_t key(int64_t x, int64_t y, int64_t z) {
        return (static_cast<uint64_t>(x & 0x1FFFFF) << 42) | (static_cast<uint64_t>(y & 0x1FFFFF) << 21)
            | static_cast<uint64_t>(z & 0x1FFFFF);
    }

    void build(const float* pts, int32_t n) {
        points = pts;
        count = n;
        if (n <= 0) return;
        double lo[3] = {pts[0], pts[1], pts[2]}, hi[3] = {pts[0], pts[1], pts[2]};
        for (int32_t i = 1; i < n; ++i) {
            for (int c = 0; c < 3; ++c) {
                lo[c] = std::min(lo[c], static_cast<double>(pts[i * 3 + c]));
                hi[c] = std::max(hi[c], static_cast<double>(pts[i * 3 + c]));
            }
        }
        const double extent = std::max({hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2], 1e-6});
        cell = std::max(extent / std::max(1.0, std::cbrt(static_cast<double>(n))), 1e-6);
        std::memcpy(min, lo, sizeof min);
        for (int32_t i = 0; i < n; ++i) cells[key_of(pts + i * 3)].push_back(i);
    }

    void coords(const double p[3], int64_t out[3]) const {
        for (int c = 0; c < 3; ++c) out[c] = static_cast<int64_t>(std::floor((p[c] - min[c]) / cell));
    }

    uint64_t key_of(const float* p) const {
        double d[3] = {p[0], p[1], p[2]};
        int64_t c[3];
        coords(d, c);
        return key(c[0], c[1], c[2]);
    }

    // Every point within sqrt(r2) of p (r2 must be smaller than a cell: neighbour cells only).
    void within(const double p[3], double r2, std::vector<int32_t>& out) const {
        out.clear();
        if (count <= 0) return;
        int64_t c[3];
        coords(p, c);
        for (int64_t dx = -1; dx <= 1; ++dx) {
            for (int64_t dy = -1; dy <= 1; ++dy) {
                for (int64_t dz = -1; dz <= 1; ++dz) {
                    auto it = cells.find(key(c[0] + dx, c[1] + dy, c[2] + dz));
                    if (it == cells.end()) continue;
                    for (int32_t i : it->second) {
                        const float* q = points + i * 3;
                        const double ddx = q[0] - p[0], ddy = q[1] - p[1], ddz = q[2] - p[2];
                        if (ddx * ddx + ddy * ddy + ddz * ddz <= r2) out.push_back(i);
                    }
                }
            }
        }
        std::sort(out.begin(), out.end());
    }

    // Nearest point index, -1 if empty. max_dist2 < 0 = unbounded.
    int32_t nearest(const double p[3], double max_dist2, double* out_d2) const {
        if (count <= 0) return -1;
        int64_t c[3];
        coords(p, c);
        int32_t best = -1;
        double best_d2 = max_dist2 >= 0.0 ? max_dist2 : 1e300;
        for (int64_t r = 0;; ++r) {
            // Any point in ring r is at least (r-1)*cell away.
            const double ring_min = static_cast<double>(r - 1) * cell;
            if (r > 0 && ring_min > 0.0 && ring_min * ring_min > best_d2) break;
            if (r > 4096) break;
            for (int64_t dx = -r; dx <= r; ++dx) {
                for (int64_t dy = -r; dy <= r; ++dy) {
                    for (int64_t dz = -r; dz <= r; ++dz) {
                        if (std::max({std::llabs(dx), std::llabs(dy), std::llabs(dz)}) != r) continue;
                        auto it = cells.find(key(c[0] + dx, c[1] + dy, c[2] + dz));
                        if (it == cells.end()) continue;
                        for (int32_t i : it->second) {
                            const float* q = points + i * 3;
                            const double ddx = q[0] - p[0], ddy = q[1] - p[1], ddz = q[2] - p[2];
                            const double d2 = ddx * ddx + ddy * ddy + ddz * ddz;
                            if (d2 < best_d2 || (d2 == best_d2 && best >= 0 && i < best)) {
                                best_d2 = d2;
                                best = i;
                            }
                        }
                    }
                }
            }
            if (max_dist2 >= 0.0 && static_cast<double>(r) * cell > std::sqrt(max_dist2) + cell) break;
        }
        if (out_d2 != nullptr) *out_d2 = best_d2;
        return best;
    }
};

// Vertices welded by position (split UV seams share a weld id) and the weld adjacency from triangles.
struct Welds {
    std::vector<int32_t> weld_of;                  // vertex -> weld id
    std::vector<std::vector<int32_t>> members;     // weld id -> vertices
    std::vector<std::vector<int32_t>> neighbours;  // weld id -> neighbouring weld ids
};

Welds build_welds(const float* positions, int32_t n, const int32_t* indices, int32_t index_count) {
    Welds w;
    w.weld_of.assign(static_cast<size_t>(n), -1);
    std::unordered_map<std::string, int32_t> by_pos;
    by_pos.reserve(static_cast<size_t>(n));
    for (int32_t v = 0; v < n; ++v) {
        char key[3 * sizeof(int64_t)];
        for (int c = 0; c < 3; ++c) {
            const int64_t q = static_cast<int64_t>(std::llround(static_cast<double>(positions[v * 3 + c]) * 1e5));
            std::memcpy(key + static_cast<size_t>(c) * sizeof(int64_t), &q, sizeof q);
        }
        auto [it, inserted] = by_pos.emplace(std::string(key, sizeof key), static_cast<int32_t>(w.members.size()));
        if (inserted) w.members.emplace_back();
        w.weld_of[static_cast<size_t>(v)] = it->second;
        w.members[static_cast<size_t>(it->second)].push_back(v);
    }
    w.neighbours.assign(w.members.size(), {});
    for (int32_t t = 0; t + 2 < index_count; t += 3) {
        int32_t g[3];
        bool ok = true;
        for (int k = 0; k < 3; ++k) {
            const int32_t vi = indices[t + k];
            if (vi < 0 || vi >= n) {
                ok = false;
                break;
            }
            g[k] = w.weld_of[static_cast<size_t>(vi)];
        }
        if (!ok) continue;
        for (int a = 0; a < 3; ++a) {
            for (int b = 0; b < 3; ++b) {
                if (a == b || g[a] == g[b]) continue;
                auto& nb = w.neighbours[static_cast<size_t>(g[a])];
                if (std::find(nb.begin(), nb.end(), g[b]) == nb.end()) nb.push_back(g[b]);
            }
        }
    }
    return w;
}

float brush_falloff(float x) {
    if (x >= 1.f) return 0.f;
    if (x <= 0.f) return 1.f;
    return 1.f - (3.f * x * x - 2.f * x * x * x);
}

// Set joint's weight to `target`, rescaling the others to fill the rest.
void set_joint_weight(WeightMap& m, int32_t joint, float target, int32_t fallback_joint) {
    target = std::clamp(target, 0.f, 1.f);
    double others = 0.0;
    for (const auto& it : m.items) {
        if (it.first != joint) others += it.second;
    }
    const double rest = 1.0 - target;
    if (others > 0.0) {
        const double k = rest / others;
        for (auto& it : m.items) {
            if (it.first != joint) it.second = static_cast<float>(it.second * k);
        }
    } else if (rest > 0.0 && fallback_joint >= 0 && fallback_joint != joint) {
        m.set(fallback_joint, static_cast<float>(rest));
    } else if (rest > 0.0) {
        target = 1.f;  // nothing can take the remainder
    }
    m.set(joint, target);
}

}  // namespace
}  // namespace cenda::rig

using namespace cenda::rig;

struct cr_skin {
    std::vector<Influences> v;
};

extern "C" {

cr_skin* cr_skin_create(int32_t vertex_count) {
    if (vertex_count < 0) {
        set_error("cr_skin_create: negative vertex count");
        return nullptr;
    }
    cr_skin* s = new (std::nothrow) cr_skin();
    if (s == nullptr) {
        set_error("cr_skin_create: out of memory");
        return nullptr;
    }
    s->v.resize(static_cast<size_t>(vertex_count));
    return s;
}

void cr_skin_destroy(cr_skin* skin) { delete skin; }

cr_skin* cr_skin_clone(const cr_skin* skin) {
    if (skin == nullptr) return nullptr;
    return new (std::nothrow) cr_skin(*skin);
}

int32_t cr_skin_vertex_count(const cr_skin* skin) { return skin == nullptr ? 0 : static_cast<int32_t>(skin->v.size()); }

int32_t cr_skin_weighted_count(const cr_skin* skin) {
    if (skin == nullptr) return 0;
    int32_t n = 0;
    for (const auto& i : skin->v) n += i.count > 0 ? 1 : 0;
    return n;
}

int32_t cr_skin_set(cr_skin* skin, int32_t vertex, const int32_t* joints, const float* weights, int32_t count) {
    if (skin == nullptr || vertex < 0 || vertex >= static_cast<int32_t>(skin->v.size()) || count < 0
        || (count > 0 && (joints == nullptr || weights == nullptr))) {
        set_error("cr_skin_set: bad arguments");
        return CR_ERR_ARG;
    }
    WeightMap m;
    for (int32_t i = 0; i < count; ++i) {
        if (joints[i] < 0) {
            set_error("cr_skin_set: negative joint");
            return CR_ERR_ARG;
        }
        m.add(joints[i], weights[i]);
    }
    skin->v[static_cast<size_t>(vertex)] = finalize(m);
    return CR_OK;
}

int32_t cr_skin_get(const cr_skin* skin, int32_t vertex, int32_t* out_joints, float* out_weights) {
    if (skin == nullptr || vertex < 0 || vertex >= static_cast<int32_t>(skin->v.size())) return 0;
    const Influences& i = skin->v[static_cast<size_t>(vertex)];
    for (int32_t k = 0; k < i.count; ++k) {
        if (out_joints != nullptr) out_joints[k] = i.joint[k];
        if (out_weights != nullptr) out_weights[k] = i.weight[k];
    }
    return i.count;
}

int32_t cr_skin_joint_weights(const cr_skin* skin, int32_t joint, const int32_t* part_joint_of_vertex, float* out) {
    if (skin == nullptr || out == nullptr) {
        set_error("cr_skin_joint_weights: bad arguments");
        return CR_ERR_ARG;
    }
    for (size_t v = 0; v < skin->v.size(); ++v) {
        const Influences& i = skin->v[v];
        float w = 0.f;
        if (i.count > 0) {
            for (int32_t k = 0; k < i.count; ++k) {
                if (i.joint[k] == joint) w = i.weight[k];
            }
        } else if (part_joint_of_vertex != nullptr && part_joint_of_vertex[v] == joint) {
            w = 1.f;
        }
        out[v] = w;
    }
    return CR_OK;
}

int32_t cr_skin_import(cr_skin* skin, const cr_skin_entry* entries, int32_t count) {
    if (skin == nullptr || count < 0 || (count > 0 && entries == nullptr)) {
        set_error("cr_skin_import: bad arguments");
        return CR_ERR_ARG;
    }
    for (int32_t e = 0; e < count; ++e) {
        const cr_skin_entry& en = entries[e];
        const int32_t c = std::clamp(en.count, 0, static_cast<int32_t>(CR_MAX_INFLUENCES));
        if (cr_skin_set(skin, en.vertex, en.joints, en.weights, c) != CR_OK) return CR_ERR_RANGE;
    }
    return CR_OK;
}

int32_t cr_skin_export(const cr_skin* skin, cr_skin_entry* out, int32_t capacity) {
    if (skin == nullptr) return 0;
    int32_t n = 0;
    for (size_t v = 0; v < skin->v.size(); ++v) {
        const Influences& i = skin->v[v];
        if (i.count <= 0) continue;
        if (out != nullptr && n < capacity) {
            cr_skin_entry& e = out[n];
            std::memset(&e, 0, sizeof e);
            e.vertex = static_cast<int32_t>(v);
            e.count = i.count;
            for (int32_t k = 0; k < i.count; ++k) {
                e.joints[k] = i.joint[k];
                e.weights[k] = i.weight[k];
            }
        }
        ++n;
    }
    return n;
}

int32_t cr_skin_pack(const cr_skin* skin, const int32_t* part_joint_of_vertex, uint16_t* out_joints,
                     uint8_t* out_weights) {
    if (skin == nullptr || out_joints == nullptr || out_weights == nullptr) {
        set_error("cr_skin_pack: bad arguments");
        return CR_ERR_ARG;
    }
    for (size_t v = 0; v < skin->v.size(); ++v) {
        uint16_t* j = out_joints + v * 4;
        uint8_t* w = out_weights + v * 4;
        std::memset(j, 0, 8);
        std::memset(w, 0, 4);
        const Influences& i = skin->v[v];
        if (i.count <= 0) {
            const int32_t pj = part_joint_of_vertex != nullptr ? part_joint_of_vertex[v] : 0;
            j[0] = static_cast<uint16_t>(std::clamp(pj, 0, 65535));
            w[0] = 255;
            continue;
        }
        // Largest remainder: quantized weights sum to exactly 255.
        int32_t q[CR_MAX_INFLUENCES]{};
        float rem[CR_MAX_INFLUENCES]{};
        int32_t total = 0;
        for (int32_t k = 0; k < i.count; ++k) {
            const float exact = i.weight[k] * 255.f;
            q[k] = static_cast<int32_t>(std::floor(exact));
            rem[k] = exact - static_cast<float>(q[k]);
            total += q[k];
        }
        while (total < 255) {
            int32_t best = 0;
            for (int32_t k = 1; k < i.count; ++k) {
                if (rem[k] > rem[best]) best = k;
            }
            q[best] += 1;
            rem[best] = -1.f;
            total += 1;
            if (i.count == 1) break;
        }
        for (int32_t k = 0; k < i.count; ++k) {
            j[k] = static_cast<uint16_t>(std::clamp(i.joint[k], 0, 65535));
            w[k] = static_cast<uint8_t>(std::clamp(q[k], 0, 255));
        }
    }
    return CR_OK;
}

int32_t cr_skin_auto_weights(cr_skin* skin, const cr_rig* rig, const float* positions, int32_t vertex_first,
                             int32_t vertex_count, const int32_t* bones, int32_t bone_count, float falloff,
                             float min_weight) {
    if (skin == nullptr || rig == nullptr || positions == nullptr || bones == nullptr || bone_count <= 0
        || vertex_first < 0 || vertex_count < 0
        || vertex_first + vertex_count > static_cast<int32_t>(skin->v.size())) {
        set_error("cr_skin_auto_weights: bad arguments");
        return CR_ERR_ARG;
    }
    struct Seg {
        int32_t joint;
        double a[3];
        double b[3];
    };
    std::vector<Seg> segs;
    for (int32_t k = 0; k < bone_count; ++k) {
        const int32_t j = bones[k];
        if (j < 0 || j >= static_cast<int32_t>(rig->joints.size())) {
            set_error("cr_skin_auto_weights: bone index out of range");
            return CR_ERR_RANGE;
        }
        Seg s{};
        s.joint = j;
        const Joint& joint = rig->joints[static_cast<size_t>(j)];
        if (joint.kind == CR_JOINT_BONE) {
            const Mat4& head = rig->rest_world[static_cast<size_t>(j)];
            const Mat4& tail = rig->rest_child[static_cast<size_t>(j)];
            for (int c = 0; c < 3; ++c) {
                s.a[c] = head.at(c, 3);
                s.b[c] = tail.at(c, 3);
            }
        } else {
            const double o[3] = {joint.origin[0], joint.origin[1], joint.origin[2]};
            rig->rest_world[static_cast<size_t>(j)].transform_point(o, s.a);
            std::memcpy(s.b, s.a, sizeof s.a);
        }
        segs.push_back(s);
    }
    std::vector<std::pair<double, int32_t>> d(segs.size());
    for (int32_t v = vertex_first; v < vertex_first + vertex_count; ++v) {
        const double p[3] = {positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2]};
        for (size_t k = 0; k < segs.size(); ++k) d[k] = {dist2_point_segment(p, segs[k].a, segs[k].b), segs[k].joint};
        std::sort(d.begin(), d.end(), [](const auto& x, const auto& y) {
            if (x.first != y.first) return x.first < y.first;
            return x.second < y.second;
        });
        WeightMap m;
        if (falloff <= 0.f) {
            m.add(d[0].second, 1.f);
        } else {
            const size_t take = std::min<size_t>(CR_MAX_INFLUENCES, d.size());
            for (size_t k = 0; k < take; ++k) {
                m.add(d[k].second, static_cast<float>(1.0 / std::pow(d[k].first + 1e-6, static_cast<double>(falloff))));
            }
        }
        Influences inf = finalize(m);
        if (min_weight > 0.f && inf.count > 1) {
            WeightMap pruned;
            for (int32_t k = 0; k < inf.count; ++k) {
                if (inf.weight[k] >= min_weight || k == 0) pruned.add(inf.joint[k], inf.weight[k]);
            }
            inf = finalize(pruned);
        }
        skin->v[static_cast<size_t>(v)] = inf;
    }
    return CR_OK;
}

int32_t cr_skin_paint(cr_skin* skin, const float* positions, const int32_t* part_joint_of_vertex,
                      const int32_t* indices, int32_t index_count, const uint8_t* only_vertices, const float* center,
                      float radius, int32_t joint, int32_t mode, float strength) {
    if (skin == nullptr || positions == nullptr || center == nullptr || !(radius > 0.f) || joint < 0
        || mode < CR_PAINT_ADD || mode > CR_PAINT_SMOOTH || (mode == CR_PAINT_SMOOTH && index_count > 0 && indices == nullptr)) {
        set_error("cr_skin_paint: bad arguments");
        return -1;
    }
    const int32_t n = static_cast<int32_t>(skin->v.size());
    const float r2 = radius * radius;
    std::vector<std::pair<int32_t, float>> hit;  // vertex, falloff
    for (int32_t v = 0; v < n; ++v) {
        if (only_vertices != nullptr && only_vertices[v] == 0) continue;
        const float dx = positions[v * 3] - center[0], dy = positions[v * 3 + 1] - center[1],
                    dz = positions[v * 3 + 2] - center[2];
        const float d2 = dx * dx + dy * dy + dz * dz;
        if (d2 > r2) continue;
        const float f = brush_falloff(std::sqrt(d2) / radius);
        if (f > 0.f) hit.emplace_back(v, f);
    }
    if (hit.empty()) return 0;
    int32_t changed = 0;
    if (mode == CR_PAINT_SMOOTH) {
        const Welds w = build_welds(positions, n, indices, index_count);
        // Jacobi: read the weights as they were before the dab.
        const std::vector<Influences> before = skin->v;
        auto weld_avg = [&](int32_t weld, WeightMap& acc, float scale) {
            const auto& mem = w.members[static_cast<size_t>(weld)];
            for (int32_t mv : mem) {
                WeightMap e = effective(before[static_cast<size_t>(mv)], part_joint_of_vertex, mv);
                for (auto& it : e.items) acc.add(it.first, it.second * scale / static_cast<float>(mem.size()));
            }
        };
        for (const auto& [v, f] : hit) {
            const int32_t weld = w.weld_of[static_cast<size_t>(v)];
            const auto& nb = w.neighbours[static_cast<size_t>(weld)];
            WeightMap avg;
            const float share = 1.f / static_cast<float>(nb.size() + 1);
            weld_avg(weld, avg, share);
            for (int32_t g : nb) weld_avg(g, avg, share);
            WeightMap own = effective(before[static_cast<size_t>(v)], part_joint_of_vertex, v);
            const float a = std::clamp(f * strength, 0.f, 1.f);
            WeightMap mixed;
            for (auto& it : own.items) mixed.add(it.first, it.second * (1.f - a));
            for (auto& it : avg.items) mixed.add(it.first, it.second * a);
            skin->v[static_cast<size_t>(v)] = finalize(mixed);
            ++changed;
        }
        return changed;
    }
    for (const auto& [v, f] : hit) {
        const int32_t pj = part_joint_of_vertex != nullptr ? part_joint_of_vertex[v] : -1;
        WeightMap m = effective(skin->v[static_cast<size_t>(v)], part_joint_of_vertex, v);
        const float w = m.get(joint);
        float target = w;
        switch (mode) {
        case CR_PAINT_ADD: target = w + f * std::clamp(strength, 0.f, 1.f) * (1.f - w); break;
        case CR_PAINT_SUBTRACT: target = w - f * std::clamp(strength, 0.f, 1.f) * w; break;
        case CR_PAINT_REPLACE: target = w + f * (std::clamp(strength, 0.f, 1.f) - w); break;
        default: break;
        }
        if (target == w && !m.items.empty()) continue;
        set_joint_weight(m, joint, target, pj);
        skin->v[static_cast<size_t>(v)] = finalize(m);
        ++changed;
    }
    return changed;
}

int32_t cr_skin_normalize(cr_skin* skin) {
    if (skin == nullptr) return CR_ERR_ARG;
    for (auto& i : skin->v) {
        if (i.count <= 0) continue;
        WeightMap m = to_map(i);
        i = finalize(m);
    }
    return CR_OK;
}

int32_t cr_skin_prune(cr_skin* skin, float min_weight) {
    if (skin == nullptr) return CR_ERR_ARG;
    int32_t removed = 0;
    for (auto& i : skin->v) {
        if (i.count <= 1) continue;
        WeightMap m;
        for (int32_t k = 0; k < i.count; ++k) {
            if (i.weight[k] >= min_weight || k == 0) {
                m.add(i.joint[k], i.weight[k]);
            } else {
                ++removed;
            }
        }
        i = finalize(m);
    }
    return removed;
}

int32_t cr_skin_remove_joint(cr_skin* skin, int32_t joint) {
    if (skin == nullptr) return CR_ERR_ARG;
    int32_t touched = 0;
    for (auto& i : skin->v) {
        bool has = false;
        WeightMap m;
        for (int32_t k = 0; k < i.count; ++k) {
            if (i.joint[k] == joint) {
                has = true;
            } else {
                m.add(i.joint[k], i.weight[k]);
            }
        }
        if (has) {
            i = finalize(m);
            ++touched;
        }
    }
    return touched;
}

int32_t cr_skin_remap_joints(cr_skin* skin, const int32_t* map, int32_t map_count) {
    if (skin == nullptr || map == nullptr || map_count < 0) {
        set_error("cr_skin_remap_joints: bad arguments");
        return CR_ERR_ARG;
    }
    for (auto& i : skin->v) {
        if (i.count <= 0) continue;
        WeightMap m;
        for (int32_t k = 0; k < i.count; ++k) {
            const int32_t j = i.joint[k];
            const int32_t to = j >= 0 && j < map_count ? map[j] : -1;
            if (to >= 0) m.add(to, i.weight[k]);
        }
        i = finalize(m);
    }
    return CR_OK;
}

int32_t cr_skin_mirror(cr_skin* skin, const float* positions, int32_t axis, int32_t source_sign, float tolerance,
                       const int32_t* joint_mirror, int32_t joint_count) {
    if (skin == nullptr || positions == nullptr || axis < 0 || axis > 2 || (source_sign != 1 && source_sign != -1)
        || tolerance < 0.f) {
        set_error("cr_skin_mirror: bad arguments");
        return -1;
    }
    const int32_t n = static_cast<int32_t>(skin->v.size());
    Grid grid;
    grid.build(positions, n);
    const std::vector<Influences> before = skin->v;
    const double tol2 = static_cast<double>(tolerance) * tolerance;
    std::vector<int32_t> coincident;
    std::vector<uint8_t> touched(static_cast<size_t>(n), 0);
    int32_t written = 0;  // distinct vertices written
    for (int32_t v = 0; v < n; ++v) {
        const float c = positions[v * 3 + axis];
        if (c * static_cast<float>(source_sign) <= tolerance) continue;  // other side or on the plane
        double p[3] = {positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2]};
        p[axis] = -p[axis];
        double d2 = 0.0;
        const int32_t target = grid.nearest(p, tol2, &d2);
        if (target < 0 || target == v) continue;
        const Influences& src = before[static_cast<size_t>(v)];
        WeightMap m;
        for (int32_t k = 0; k < src.count; ++k) {
            int32_t j = src.joint[k];
            if (joint_mirror != nullptr && j >= 0 && j < joint_count && joint_mirror[j] >= 0) j = joint_mirror[j];
            m.add(j, src.weight[k]);
        }
        const Influences mirrored = finalize(m);
        // Every copy of the target position (split UV seams) receives the same weights.
        const double t[3] = {positions[target * 3], positions[target * 3 + 1], positions[target * 3 + 2]};
        grid.within(t, 1e-12, coincident);
        for (int32_t tv : coincident) {
            if (tv == v) continue;
            skin->v[static_cast<size_t>(tv)] = mirrored;
            if (!touched[static_cast<size_t>(tv)]) {
                touched[static_cast<size_t>(tv)] = 1;
                ++written;
            }
        }
    }
    return written;
}

cr_skin* cr_skin_transfer(const cr_skin* skin, const float* old_positions, const float* new_positions,
                          int32_t new_vertex_count) {
    if (skin == nullptr || new_vertex_count < 0 || (new_vertex_count > 0 && new_positions == nullptr)
        || (!skin->v.empty() && old_positions == nullptr)) {
        set_error("cr_skin_transfer: bad arguments");
        return nullptr;
    }
    cr_skin* out = cr_skin_create(new_vertex_count);
    if (out == nullptr || skin->v.empty()) return out;
    Grid grid;
    grid.build(old_positions, static_cast<int32_t>(skin->v.size()));
    for (int32_t v = 0; v < new_vertex_count; ++v) {
        const double p[3] = {new_positions[v * 3], new_positions[v * 3 + 1], new_positions[v * 3 + 2]};
        const int32_t src = grid.nearest(p, -1.0, nullptr);
        if (src >= 0) out->v[static_cast<size_t>(v)] = skin->v[static_cast<size_t>(src)];
    }
    return out;
}

int32_t cr_skin_deform(const cr_skin* skin, const int32_t* part_joint_of_vertex, const float* positions,
                       int32_t vertex_count, const float* skin_matrices, int32_t joint_count, float* out_positions) {
    if (positions == nullptr || skin_matrices == nullptr || out_positions == nullptr || vertex_count < 0
        || joint_count <= 0 || (skin != nullptr && static_cast<int32_t>(skin->v.size()) < vertex_count)) {
        set_error("cr_skin_deform: bad arguments");
        return CR_ERR_ARG;
    }
    for (int32_t v = 0; v < vertex_count; ++v) {
        const float* p = positions + v * 3;
        double acc[3] = {0.0, 0.0, 0.0};
        auto apply = [&](int32_t j, double w) {
            if (j < 0 || j >= joint_count) return;
            const float* m = skin_matrices + j * 16;
            for (int r = 0; r < 3; ++r) {
                acc[r] += w * (m[r] * static_cast<double>(p[0]) + m[4 + r] * static_cast<double>(p[1])
                               + m[8 + r] * static_cast<double>(p[2]) + m[12 + r]);
            }
        };
        const Influences* inf = skin != nullptr ? &skin->v[static_cast<size_t>(v)] : nullptr;
        if (inf != nullptr && inf->count > 0) {
            for (int32_t k = 0; k < inf->count; ++k) apply(inf->joint[k], inf->weight[k]);
        } else {
            apply(part_joint_of_vertex != nullptr ? part_joint_of_vertex[v] : 0, 1.0);
        }
        for (int c = 0; c < 3; ++c) out_positions[v * 3 + c] = static_cast<float>(acc[c]);
    }
    return CR_OK;
}

}  // extern "C"
