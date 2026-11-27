// Cenda rig: skin weights — storage, authoring operations (auto weights, brush, smooth, mirror,
// transfer), GPU packing and CPU deformation. Contract in cenda/rig.h.

#include "rig_internal.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <memory>
#include <new>
#include <stdexcept>
#include <string>
#include <unordered_map>

namespace cenda::rig {
namespace {

// One influence.
struct Inf {
    int32_t joint;
    float weight;
};

using Infs = std::vector<Inf>;

// A read-only view of one vertex's influences.
struct Span {
    const Inf* p = nullptr;
    uint32_t n = 0;
};

// A small joint -> weight accumulator used while editing one vertex. Items keep their insertion order (the float
// sums and every later pass read them in that order, as before ABI 3); past a few joints a hash index finds them,
// so merging B influences is O(B), not O(B^2).
struct WeightMap {
    std::vector<std::pair<int32_t, float>> items;
    std::unordered_map<int32_t, size_t> index;  // joint -> item, built once items outgrow a linear scan

    static constexpr size_t kIndexFrom = 16;

    size_t find(int32_t joint) const {
        if (!index.empty()) {
            auto it = index.find(joint);
            return it == index.end() ? items.size() : it->second;
        }
        for (size_t i = 0; i < items.size(); ++i) {
            if (items[i].first == joint) return i;
        }
        return items.size();
    }

    void push(int32_t joint, float w) {
        items.emplace_back(joint, w);
        if (!index.empty()) {
            index.emplace(joint, items.size() - 1);
        } else if (items.size() >= kIndexFrom) {
            index.reserve(items.size() * 2);
            for (size_t i = 0; i < items.size(); ++i) index.emplace(items[i].first, i);
        }
    }

    void add(int32_t joint, float w) {
        const size_t i = find(joint);
        if (i < items.size()) {
            items[i].second += w;
        } else {
            push(joint, w);
        }
    }

    float get(int32_t joint) const {
        const size_t i = find(joint);
        return i < items.size() ? items[i].second : 0.f;
    }

    void set(int32_t joint, float w) {
        const size_t i = find(joint);
        if (i < items.size()) {
            items[i].second = w;
        } else {
            push(joint, w);
        }
    }
};

// Every positive finite weight (ties: lower joint first), heaviest first, normalized. No cap (ABI 3; ABI 2 kept 4).
// A weight whose normalized float underflows to 0 is dropped (and the rest renormalized), so a stored weight is
// always > 0.
Infs finalize(WeightMap& map) {
    auto& v = map.items;
    map.index.clear();
    v.erase(std::remove_if(v.begin(), v.end(),
                           [](const std::pair<int32_t, float>& p) {
                               return !(p.second > 0.f) || !std::isfinite(p.second) || p.first < 0;
                           }),
            v.end());
    std::sort(v.begin(), v.end(), [](const auto& a, const auto& b) {
        if (a.second != b.second) return a.second > b.second;
        return a.first < b.first;
    });
    Infs out;
    for (;;) {
        double sum = 0.0;
        for (const auto& p : v) sum += p.second;
        if (sum <= 0.0) return Infs{};
        out.resize(v.size());
        size_t zeros = 0;
        for (size_t i = 0; i < v.size(); ++i) {
            out[i] = Inf{v[i].first, static_cast<float>(v[i].second / sum)};
            zeros += out[i].weight > 0.f ? size_t{0} : size_t{1};
        }
        if (zeros == 0) return out;
        // Heaviest first: the underflowed ones are at the end.
        v.resize(v.size() - zeros);
    }
}

WeightMap to_map(Span inf) {
    WeightMap m;
    for (uint32_t i = 0; i < inf.n; ++i) m.add(inf.p[i].joint, inf.p[i].weight);
    return m;
}

// The starting weights of a vertex: authored, else its part joint at 1.
WeightMap effective(Span inf, const int32_t* part_joint_of_vertex, int32_t v) {
    if (inf.n > 0) return to_map(inf);
    WeightMap m;
    if (part_joint_of_vertex != nullptr && part_joint_of_vertex[v] >= 0) m.add(part_joint_of_vertex[v], 1.f);
    return m;
}

// Per-thread scratch of quantize (grows, never shrinks: no allocation per call once warm).
struct QuantScratch {
    std::vector<uint16_t> q;
    std::vector<double> rem;
    std::vector<uint32_t> order;
};

QuantScratch& quant_scratch() {
    thread_local QuantScratch s;
    return s;
}

// The GPU weights of one weighted vertex (rig.h "GPU packing"): unorm16 summing to exactly 65535, largest remainder
// (ties: the earlier, i.e. heavier, influence). O(n log n). Returns the per-thread q array (valid until the next call
// on this thread). Weights are finite and positive (finalize guarantees it).
const uint16_t* quantize(Span inf) {
    QuantScratch& s = quant_scratch();
    const size_t n = inf.n;
    if (s.q.size() < n) {
        s.q.resize(n);
        s.rem.resize(n);
        s.order.resize(n);
    }
    uint16_t* q = s.q.data();
    double* rem = s.rem.data();
    double sum = 0.0;
    for (size_t k = 0; k < n; ++k) sum += inf.p[k].weight;
    int64_t total = 0;
    for (size_t k = 0; k < n; ++k) {
        const double exact = sum > 0.0 ? static_cast<double>(inf.p[k].weight) / sum * 65535.0 : 0.0;
        double fl = std::floor(exact);
        if (!(fl >= 0.0)) fl = 0.0;  // (also catches NaN)
        if (fl > 65535.0) fl = 65535.0;
        q[k] = static_cast<uint16_t>(fl);
        rem[k] = exact - fl;
        total += q[k];
    }
    int64_t deficit = 65535 - total;
    if (deficit <= 0 || n == 0) return q;
    // One unit each to the `deficit` largest remainders (ties: lower index); any rest (never, for a normalized
    // sum) to the heaviest.
    uint32_t* order = s.order.data();
    for (size_t k = 0; k < n; ++k) order[k] = static_cast<uint32_t>(k);
    const size_t take = static_cast<size_t>(std::min<int64_t>(deficit, static_cast<int64_t>(n)));
    std::partial_sort(order, order + take, order + n, [rem](uint32_t a, uint32_t b) {
        if (rem[a] != rem[b]) return rem[a] > rem[b];
        return a < b;
    });
    for (size_t i = 0; i < take; ++i) q[order[i]] = static_cast<uint16_t>(q[order[i]] + 1);
    deficit -= static_cast<int64_t>(take);
    if (deficit > 0) q[0] = static_cast<uint16_t>(q[0] + deficit);
    return q;
}

// The pool cannot address more influences (thrown inside, CR_ERR_RANGE at the C boundary).
struct PoolFull : std::length_error {
    PoolFull() : std::length_error("more than 2^32 - 1 skin influences") {}
};

// Runs an extern "C" body: no exception crosses the C boundary (rig.h error model).
template <class R, class F>
R guarded(const char* fn, R full, R other, F&& body) {
    try {
        return body();
    } catch (const PoolFull& e) {
        set_error(std::string(fn) + ": " + e.what());
        return full;
    } catch (const std::bad_alloc&) {
        set_error(std::string(fn) + ": out of memory");
        return other;
    } catch (const std::exception& e) {
        set_error(std::string(fn) + ": " + e.what());
        return other;
    }
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

// Variable-length per-vertex influences (ABI 3): one pooled array; each vertex owns a block [offset, offset + cap)
// of which the first `count` are live. A vertex whose new list fits its block is rewritten in place; a longer one
// moves to a fresh block at the end. Dead space (abandoned blocks and the unused tails of shrunk ones) is
// reclaimed by compaction once the pool exceeds twice the live influences + 4096, so it stays O(live) amortized.
struct cr_skin {
    struct Range {
        uint32_t offset = 0;
        uint32_t count = 0;
        uint32_t cap = 0;
    };
    std::vector<Range> r;
    std::vector<Inf> pool;
    size_t live = 0;  // sum of count over every vertex

    static constexpr size_t kSlack = 4096;

    int32_t size() const { return static_cast<int32_t>(r.size()); }

    Span at(size_t v) const {
        const Range& x = r[v];
        return Span{x.count > 0 ? pool.data() + x.offset : nullptr, x.count};
    }

    // Live influences only, in vertex order (strong guarantee: on bad_alloc nothing changed).
    void compact() {
        std::vector<Inf> np;
        np.reserve(live);
        for (const Range& x : r) np.insert(np.end(), pool.begin() + x.offset, pool.begin() + x.offset + x.count);
        uint32_t off = 0;
        for (Range& x : r) {
            x.offset = off;
            x.cap = x.count;
            off += x.count;
        }
        pool.swap(np);
    }

    // A compact copy (no dead space).
    cr_skin snapshot() const {
        cr_skin c;
        c.r = r;
        c.live = live;
        c.pool.reserve(live);
        uint32_t off = 0;
        for (size_t v = 0; v < r.size(); ++v) {
            const Range& x = r[v];
            c.pool.insert(c.pool.end(), pool.begin() + x.offset, pool.begin() + x.offset + x.count);
            c.r[v] = Range{off, x.count, x.count};
            off += x.count;
        }
        return c;
    }

    void assign(size_t v, const Inf* src, size_t n) {
        // A source inside the pool would move with it.
        Infs copy;
        if (n > 0 && !pool.empty() && src >= pool.data() && src < pool.data() + pool.size()) {
            copy.assign(src, src + n);
            src = copy.data();
        }
        constexpr size_t kMax = std::numeric_limits<uint32_t>::max();
        if (n > kMax) throw PoolFull();
        if (n <= r[v].cap) {
            Range& x = r[v];
            if (n > 0) std::memmove(pool.data() + x.offset, src, n * sizeof(Inf));
            live = live - x.count + n;
            x.count = static_cast<uint32_t>(n);
        } else {
            if (pool.size() + n > kMax) {
                compact();
                if (pool.size() + n > kMax) throw PoolFull();
            }
            const size_t off = pool.size();
            pool.insert(pool.end(), src, src + n);  // may throw: nothing changed yet
            Range& x = r[v];
            live = live - x.count + n;
            x.offset = static_cast<uint32_t>(off);
            x.cap = static_cast<uint32_t>(n);
            x.count = static_cast<uint32_t>(n);
        }
        if (pool.size() > 2 * live + kSlack) compact();
    }

    void assign(size_t v, const Infs& infs) { assign(v, infs.data(), infs.size()); }

    // Sum over every vertex of max(count, 1) (the packed record count).
    int64_t pack_records() const {
        int64_t unweighted = 0;
        for (const Range& x : r) unweighted += x.count == 0 ? 1 : 0;
        return static_cast<int64_t>(live) + unweighted;
    }
};

namespace {

constexpr int64_t kInt32Max = std::numeric_limits<int32_t>::max();

// The joint an unweighted vertex follows: its part joint, joint 0 when there is none or it is not a valid palette
// index (pack: outside [0, CR_MAX_JOINTS]; deform: outside [0, joint_count)).
int32_t rigid_joint(const int32_t* part_joint_of_vertex, size_t v, int32_t joint_limit) {
    const int32_t pj = part_joint_of_vertex != nullptr ? part_joint_of_vertex[v] : 0;
    return pj >= 0 && pj < joint_limit ? pj : 0;
}

}  // namespace

extern "C" {

cr_skin* cr_skin_create(int32_t vertex_count) {
    if (vertex_count < 0) {
        set_error("cr_skin_create: negative vertex count");
        return nullptr;
    }
    return guarded<cr_skin*>("cr_skin_create", nullptr, nullptr, [&]() -> cr_skin* {
        auto s = std::make_unique<cr_skin>();
        s->r.resize(static_cast<size_t>(vertex_count));
        return s.release();
    });
}

void cr_skin_destroy(cr_skin* skin) { delete skin; }

cr_skin* cr_skin_clone(const cr_skin* skin) {
    if (skin == nullptr) return nullptr;
    return guarded<cr_skin*>("cr_skin_clone", nullptr, nullptr, [&]() -> cr_skin* {
        return std::make_unique<cr_skin>(skin->snapshot()).release();
    });
}

int32_t cr_skin_vertex_count(const cr_skin* skin) { return skin == nullptr ? 0 : skin->size(); }

int32_t cr_skin_weighted_count(const cr_skin* skin) {
    if (skin == nullptr) return 0;
    int32_t n = 0;
    for (const auto& x : skin->r) n += x.count > 0 ? 1 : 0;
    return n;
}

int32_t cr_skin_set(cr_skin* skin, int32_t vertex, const int32_t* joints, const float* weights, int32_t count) {
    if (skin == nullptr || vertex < 0 || vertex >= skin->size() || count < 0
        || (count > 0 && (joints == nullptr || weights == nullptr))) {
        set_error("cr_skin_set: bad arguments");
        return CR_ERR_ARG;
    }
    for (int32_t i = 0; i < count; ++i) {
        if (joints[i] < 0) {
            set_error("cr_skin_set: negative joint");
            return CR_ERR_ARG;
        }
        if (!std::isfinite(weights[i])) {
            set_error("cr_skin_set: non-finite weight");
            return CR_ERR_ARG;
        }
    }
    return guarded<int32_t>("cr_skin_set", CR_ERR_RANGE, CR_ERR_ARG, [&]() -> int32_t {
        WeightMap m;
        for (int32_t i = 0; i < count; ++i) m.add(joints[i], weights[i]);
        for (const auto& it : m.items) {
            if (!std::isfinite(it.second)) {
                set_error("cr_skin_set: merged weights overflow");
                return CR_ERR_ARG;
            }
        }
        skin->assign(static_cast<size_t>(vertex), finalize(m));
        return CR_OK;
    });
}

int32_t cr_skin_get(const cr_skin* skin, int32_t vertex, int32_t* out_joints, float* out_weights, int32_t capacity) {
    if (skin == nullptr || vertex < 0 || vertex >= skin->size()) return 0;
    const Span i = skin->at(static_cast<size_t>(vertex));
    const uint32_t n = capacity > 0 ? std::min(i.n, static_cast<uint32_t>(capacity)) : 0u;
    for (uint32_t k = 0; k < n; ++k) {
        if (out_joints != nullptr) out_joints[k] = i.p[k].joint;
        if (out_weights != nullptr) out_weights[k] = i.p[k].weight;
    }
    return static_cast<int32_t>(i.n);
}

int32_t cr_skin_influence_count(const cr_skin* skin, int32_t vertex) {
    if (skin == nullptr || vertex < 0 || vertex >= skin->size()) return 0;
    return static_cast<int32_t>(skin->r[static_cast<size_t>(vertex)].count);
}

int32_t cr_skin_max_influences(const cr_skin* skin) {
    if (skin == nullptr) return 0;
    uint32_t m = 0;
    for (const auto& x : skin->r) m = std::max(m, x.count);
    return static_cast<int32_t>(m);
}

int32_t cr_skin_influence_total(const cr_skin* skin) {
    if (skin == nullptr) return 0;
    if (static_cast<int64_t>(skin->live) > kInt32Max) {
        set_error("cr_skin_influence_total: more than INT32_MAX influences");
        return CR_ERR_RANGE;
    }
    return static_cast<int32_t>(skin->live);
}

int32_t cr_skin_joint_weights(const cr_skin* skin, int32_t joint, const int32_t* part_joint_of_vertex, float* out) {
    if (skin == nullptr || out == nullptr) {
        set_error("cr_skin_joint_weights: bad arguments");
        return CR_ERR_ARG;
    }
    for (size_t v = 0; v < skin->r.size(); ++v) {
        const Span i = skin->at(v);
        float w = 0.f;
        if (i.n > 0) {
            for (uint32_t k = 0; k < i.n; ++k) {
                if (i.p[k].joint == joint) w = i.p[k].weight;
            }
        } else if (part_joint_of_vertex != nullptr && part_joint_of_vertex[v] == joint) {
            w = 1.f;
        }
        out[v] = w;
    }
    return CR_OK;
}

int32_t cr_skin_import(cr_skin* skin, int32_t count, const int32_t* vertices, const int32_t* offsets, const int32_t* joints,
                       const float* weights) {
    if (skin == nullptr || count < 0 || (count > 0 && offsets == nullptr)) {
        set_error("cr_skin_import: bad arguments");
        return CR_ERR_ARG;
    }
    if (count == 0) return CR_OK;
    if (offsets[0] < 0) {
        set_error("cr_skin_import: negative offset");
        return CR_ERR_ARG;
    }
    for (int32_t e = 0; e < count; ++e) {
        if (offsets[e + 1] < offsets[e]) {
            set_error("cr_skin_import: offsets must not decrease");
            return CR_ERR_ARG;
        }
    }
    if (offsets[count] > offsets[0] && (joints == nullptr || weights == nullptr)) {
        set_error("cr_skin_import: null joints or weights");
        return CR_ERR_ARG;
    }
    for (int32_t k = offsets[0]; k < offsets[count]; ++k) {
        if (!std::isfinite(weights[k])) {
            set_error("cr_skin_import: non-finite weight at " + std::to_string(k));
            return CR_ERR_ARG;
        }
    }
    for (int32_t e = 0; e < count; ++e) {
        const int32_t v = vertices != nullptr ? vertices[e] : e;
        const int32_t first = offsets[e], n = offsets[e + 1] - offsets[e];
        const int32_t rc = cr_skin_set(skin, v, n > 0 ? joints + first : nullptr, n > 0 ? weights + first : nullptr, n);
        if (rc != CR_OK) return CR_ERR_RANGE;  // a bad vertex / negative joint / merged overflow / a full pool
    }
    return CR_OK;
}

int32_t cr_skin_export(const cr_skin* skin, int32_t vertex_capacity, int32_t influence_capacity, int32_t* out_vertices,
                       int32_t* out_offsets, int32_t* out_joints, float* out_weights) {
    if (skin == nullptr) return 0;
    if (static_cast<int64_t>(skin->live) > kInt32Max) {
        set_error("cr_skin_export: more than INT32_MAX influences");
        return CR_ERR_RANGE;
    }
    const int32_t n = cr_skin_weighted_count(skin);
    const int32_t total = static_cast<int32_t>(skin->live);
    if (out_vertices == nullptr || out_offsets == nullptr || out_joints == nullptr || out_weights == nullptr
        || vertex_capacity < n || influence_capacity < total) {
        return n;
    }
    int32_t e = 0, k = 0;
    out_offsets[0] = 0;
    for (size_t v = 0; v < skin->r.size(); ++v) {
        const Span i = skin->at(v);
        if (i.n == 0) continue;
        out_vertices[e] = static_cast<int32_t>(v);
        for (uint32_t c = 0; c < i.n; ++c, ++k) {
            out_joints[k] = i.p[c].joint;
            out_weights[k] = i.p[c].weight;
        }
        out_offsets[++e] = k;
    }
    return n;
}

int32_t cr_skin_pack_size(const cr_skin* skin) {
    if (skin == nullptr) return 0;
    const int64_t t = skin->pack_records();
    if (t > kInt32Max) {
        set_error("cr_skin_pack_size: more than INT32_MAX records");
        return CR_ERR_RANGE;
    }
    return static_cast<int32_t>(t);
}

int32_t cr_skin_pack(const cr_skin* skin, const int32_t* part_joint_of_vertex, int32_t influence_capacity, uint32_t* out_ranges,
                     uint16_t* out_influences) {
    if (skin == nullptr || out_ranges == nullptr || out_influences == nullptr) {
        set_error("cr_skin_pack: bad arguments");
        return CR_ERR_ARG;
    }
    const int64_t records = skin->pack_records();
    if (records > kInt32Max) {
        set_error("cr_skin_pack: more than INT32_MAX records");
        return CR_ERR_RANGE;
    }
    if (influence_capacity < records) {
        set_error("cr_skin_pack: influence_capacity < cr_skin_pack_size");
        return CR_ERR_ARG;
    }
    return guarded<int32_t>("cr_skin_pack", CR_ERR_RANGE, CR_ERR_ARG, [&]() -> int32_t {
        uint32_t off = 0;
        for (size_t v = 0; v < skin->r.size(); ++v) {
            const Span i = skin->at(v);
            uint16_t* out = out_influences + static_cast<size_t>(off) * 2;
            out_ranges[v * 2] = off;
            if (i.n == 0) {
                out[0] = static_cast<uint16_t>(rigid_joint(part_joint_of_vertex, v, CR_MAX_JOINTS + 1));
                out[1] = 65535;
                out_ranges[v * 2 + 1] = 1;
                off += 1;
                continue;
            }
            const uint16_t* q = quantize(i);
            for (uint32_t k = 0; k < i.n; ++k) {
                out[k * 2] = static_cast<uint16_t>(std::clamp(i.p[k].joint, 0, 65535));
                out[k * 2 + 1] = q[k];
            }
            out_ranges[v * 2 + 1] = i.n;
            off += i.n;
        }
        return CR_OK;
    });
}

int32_t cr_skin_auto_weights(cr_skin* skin, const cr_rig* rig, const float* positions, int32_t vertex_first,
                             int32_t vertex_count, const int32_t* bones, int32_t bone_count, float falloff,
                             float min_weight, int32_t max_influences) {
    if (skin == nullptr || rig == nullptr || positions == nullptr || bones == nullptr || bone_count <= 0
        || vertex_first < 0 || vertex_count < 0 || max_influences < 0
        || static_cast<int64_t>(vertex_first) + vertex_count > skin->size()) {
        set_error("cr_skin_auto_weights: bad arguments");
        return CR_ERR_ARG;
    }
    for (int32_t k = 0; k < bone_count; ++k) {
        if (bones[k] < 0 || bones[k] >= static_cast<int32_t>(rig->joints.size())) {
            set_error("cr_skin_auto_weights: bone index out of range");
            return CR_ERR_RANGE;
        }
    }
    return guarded<int32_t>("cr_skin_auto_weights", CR_ERR_RANGE, CR_ERR_ARG, [&]() -> int32_t {
        struct Seg {
            int32_t joint;
            double a[3];
            double b[3];
        };
        std::vector<Seg> segs;
        for (int32_t k = 0; k < bone_count; ++k) {
            const int32_t j = bones[k];
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
        const size_t take = max_influences > 0 ? std::min<size_t>(static_cast<size_t>(max_influences), d.size()) : d.size();
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
                for (size_t k = 0; k < take; ++k) {
                    m.add(d[k].second, static_cast<float>(1.0 / std::pow(d[k].first + 1e-6, static_cast<double>(falloff))));
                }
            }
            Infs inf = finalize(m);
            if (min_weight > 0.f && inf.size() > 1) {
                WeightMap pruned;
                for (size_t k = 0; k < inf.size(); ++k) {
                    if (inf[k].weight >= min_weight || k == 0) pruned.add(inf[k].joint, inf[k].weight);
                }
                inf = finalize(pruned);
            }
            skin->assign(static_cast<size_t>(v), inf);
        }
        return CR_OK;
    });
}

int32_t cr_skin_paint(cr_skin* skin, const float* positions, const int32_t* part_joint_of_vertex,
                      const int32_t* indices, int32_t index_count, const uint8_t* only_vertices, const float* center,
                      float radius, int32_t joint, int32_t mode, float strength) {
    if (skin == nullptr || positions == nullptr || center == nullptr || !(radius > 0.f) || joint < 0
        || mode < CR_PAINT_ADD || mode > CR_PAINT_SMOOTH || (mode == CR_PAINT_SMOOTH && index_count > 0 && indices == nullptr)) {
        set_error("cr_skin_paint: bad arguments");
        return -1;
    }
    return guarded<int32_t>("cr_skin_paint", -1, -1, [&]() -> int32_t {
        const int32_t n = skin->size();
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
            // Jacobi: read the weights as they were before the dab (a compact copy: no dead space).
            const cr_skin before = skin->snapshot();
            auto weld_avg = [&](int32_t weld, WeightMap& acc, float scale) {
                const auto& mem = w.members[static_cast<size_t>(weld)];
                for (int32_t mv : mem) {
                    WeightMap e = effective(before.at(static_cast<size_t>(mv)), part_joint_of_vertex, mv);
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
                WeightMap own = effective(before.at(static_cast<size_t>(v)), part_joint_of_vertex, v);
                const float a = std::clamp(f * strength, 0.f, 1.f);
                WeightMap mixed;
                for (auto& it : own.items) mixed.add(it.first, it.second * (1.f - a));
                for (auto& it : avg.items) mixed.add(it.first, it.second * a);
                skin->assign(static_cast<size_t>(v), finalize(mixed));
                ++changed;
            }
            return changed;
        }
        for (const auto& [v, f] : hit) {
            const int32_t pj = part_joint_of_vertex != nullptr ? part_joint_of_vertex[v] : -1;
            WeightMap m = effective(skin->at(static_cast<size_t>(v)), part_joint_of_vertex, v);
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
            skin->assign(static_cast<size_t>(v), finalize(m));
            ++changed;
        }
        return changed;
    });
}

int32_t cr_skin_normalize(cr_skin* skin) {
    if (skin == nullptr) return CR_ERR_ARG;
    return guarded<int32_t>("cr_skin_normalize", CR_ERR_RANGE, CR_ERR_ARG, [&]() -> int32_t {
        for (size_t v = 0; v < skin->r.size(); ++v) {
            const Span i = skin->at(v);
            if (i.n == 0) continue;
            WeightMap m = to_map(i);
            skin->assign(v, finalize(m));
        }
        return CR_OK;
    });
}

int32_t cr_skin_prune(cr_skin* skin, float min_weight) {
    if (skin == nullptr) return CR_ERR_ARG;
    return guarded<int32_t>("cr_skin_prune", CR_ERR_RANGE, CR_ERR_ARG, [&]() -> int32_t {
        int32_t removed = 0;
        for (size_t v = 0; v < skin->r.size(); ++v) {
            const Span i = skin->at(v);
            if (i.n <= 1) continue;
            WeightMap m;
            for (uint32_t k = 0; k < i.n; ++k) {
                if (i.p[k].weight >= min_weight || k == 0) {
                    m.add(i.p[k].joint, i.p[k].weight);
                } else {
                    ++removed;
                }
            }
            skin->assign(v, finalize(m));
        }
        return removed;
    });
}

int32_t cr_skin_remove_joint(cr_skin* skin, int32_t joint) {
    if (skin == nullptr) return CR_ERR_ARG;
    return guarded<int32_t>("cr_skin_remove_joint", CR_ERR_RANGE, CR_ERR_ARG, [&]() -> int32_t {
        int32_t touched = 0;
        for (size_t v = 0; v < skin->r.size(); ++v) {
            const Span i = skin->at(v);
            bool has = false;
            WeightMap m;
            for (uint32_t k = 0; k < i.n; ++k) {
                if (i.p[k].joint == joint) {
                    has = true;
                } else {
                    m.add(i.p[k].joint, i.p[k].weight);
                }
            }
            if (has) {
                skin->assign(v, finalize(m));
                ++touched;
            }
        }
        return touched;
    });
}

int32_t cr_skin_remap_joints(cr_skin* skin, const int32_t* map, int32_t map_count) {
    if (skin == nullptr || map == nullptr || map_count < 0) {
        set_error("cr_skin_remap_joints: bad arguments");
        return CR_ERR_ARG;
    }
    return guarded<int32_t>("cr_skin_remap_joints", CR_ERR_RANGE, CR_ERR_ARG, [&]() -> int32_t {
        for (size_t v = 0; v < skin->r.size(); ++v) {
            const Span i = skin->at(v);
            if (i.n == 0) continue;
            WeightMap m;
            for (uint32_t k = 0; k < i.n; ++k) {
                const int32_t j = i.p[k].joint;
                const int32_t to = j >= 0 && j < map_count ? map[j] : -1;
                if (to >= 0) m.add(to, i.p[k].weight);
            }
            skin->assign(v, finalize(m));
        }
        return CR_OK;
    });
}

int32_t cr_skin_mirror(cr_skin* skin, const float* positions, int32_t axis, int32_t source_sign, float tolerance,
                       const int32_t* joint_mirror, int32_t joint_count) {
    if (skin == nullptr || positions == nullptr || axis < 0 || axis > 2 || (source_sign != 1 && source_sign != -1)
        || tolerance < 0.f) {
        set_error("cr_skin_mirror: bad arguments");
        return -1;
    }
    return guarded<int32_t>("cr_skin_mirror", -1, -1, [&]() -> int32_t {
        const int32_t n = skin->size();
        Grid grid;
        grid.build(positions, n);
        const cr_skin before = skin->snapshot();
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
            const Span src = before.at(static_cast<size_t>(v));
            WeightMap m;
            for (uint32_t k = 0; k < src.n; ++k) {
                int32_t j = src.p[k].joint;
                if (joint_mirror != nullptr && j >= 0 && j < joint_count && joint_mirror[j] >= 0) j = joint_mirror[j];
                m.add(j, src.p[k].weight);
            }
            const Infs mirrored = finalize(m);
            // Every copy of the target position (split UV seams) receives the same weights.
            const double t[3] = {positions[target * 3], positions[target * 3 + 1], positions[target * 3 + 2]};
            grid.within(t, 1e-12, coincident);
            for (int32_t tv : coincident) {
                if (tv == v) continue;
                skin->assign(static_cast<size_t>(tv), mirrored);
                if (!touched[static_cast<size_t>(tv)]) {
                    touched[static_cast<size_t>(tv)] = 1;
                    ++written;
                }
            }
        }
        return written;
    });
}

cr_skin* cr_skin_transfer(const cr_skin* skin, const float* old_positions, const float* new_positions,
                          int32_t new_vertex_count) {
    if (skin == nullptr || new_vertex_count < 0 || (new_vertex_count > 0 && new_positions == nullptr)
        || (!skin->r.empty() && old_positions == nullptr)) {
        set_error("cr_skin_transfer: bad arguments");
        return nullptr;
    }
    return guarded<cr_skin*>("cr_skin_transfer", nullptr, nullptr, [&]() -> cr_skin* {
        auto out = std::make_unique<cr_skin>();
        out->r.resize(static_cast<size_t>(new_vertex_count));
        if (skin->r.empty()) return out.release();
        Grid grid;
        grid.build(old_positions, skin->size());
        for (int32_t v = 0; v < new_vertex_count; ++v) {
            const double p[3] = {new_positions[v * 3], new_positions[v * 3 + 1], new_positions[v * 3 + 2]};
            const int32_t src = grid.nearest(p, -1.0, nullptr);
            if (src >= 0) {
                const Span i = skin->at(static_cast<size_t>(src));
                out->assign(static_cast<size_t>(v), i.p, i.n);
            }
        }
        return out.release();
    });
}

int32_t cr_skin_deform(const cr_skin* skin, const int32_t* part_joint_of_vertex, const float* positions,
                       int32_t vertex_count, const float* skin_matrices, int32_t joint_count, float* out_positions) {
    if (positions == nullptr || skin_matrices == nullptr || out_positions == nullptr || vertex_count < 0
        || joint_count <= 0 || (skin != nullptr && skin->size() < vertex_count)) {
        set_error("cr_skin_deform: bad arguments");
        return CR_ERR_ARG;
    }
    return guarded<int32_t>("cr_skin_deform", CR_ERR_RANGE, CR_ERR_ARG, [&]() -> int32_t {
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
            const Span inf = skin != nullptr ? skin->at(static_cast<size_t>(v)) : Span{};
            if (inf.n > 0) {
                // The GPU's weights (cr_skin_pack), so CPU picking / bounds match the drawn mesh.
                const uint16_t* q = quantize(inf);
                for (uint32_t k = 0; k < inf.n; ++k) apply(inf.p[k].joint, static_cast<double>(q[k]) / 65535.0);
            } else {
                apply(rigid_joint(part_joint_of_vertex, static_cast<size_t>(v), joint_count), 1.0);
            }
            for (int c = 0; c < 3; ++c) out_positions[v * 3 + c] = static_cast<float>(acc[c]);
        }
        return CR_OK;
    });
}

}  // extern "C"
