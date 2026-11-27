// Cenda model masks (OpenMason #172): co_mask_plan over vertex groups, checked against a brute-force reference.
// Garment-style overlapping groups (exhaustive hide / keep / mixed over 6 groups), random soups, > 64 groups, empty
// and missing groups, key runs kept, the reordered buffer a permutation of whole triangles, the co_doc path with
// part-local rows, error paths, and a build timing for a 100k-triangle mesh with 20 groups. Plain CHECKs.

#include "cenda/model.h"
#include "cenda/model_mask.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <random>
#include <string>
#include <utility>
#include <vector>

namespace {

int failures = 0;

#define CHECK(cond)                                                                       \
    do {                                                                                  \
        if (!(cond)) {                                                                    \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); \
            ++failures;                                                                   \
        }                                                                                 \
    } while (0)

template <typename Vec>
auto& el(Vec& v, int64_t i) {
    return v[static_cast<size_t>(i)];
}

// ---- Test data -----------------------------------------------------------------------------------------------

struct Groups {
    std::vector<std::string> names;
    std::vector<int32_t> rows{0};
    std::vector<int32_t> vertices;
    std::vector<float> weights;

    void add(const std::string& name, const std::vector<std::pair<int32_t, float>>& members) {
        names.push_back(name);
        for (const auto& [v, w] : members) {
            vertices.push_back(v);
            weights.push_back(w);
        }
        rows.push_back(static_cast<int32_t>(vertices.size()));
    }
    [[nodiscard]] int32_t count() const { return static_cast<int32_t>(names.size()); }
};

struct Mesh {
    std::vector<int32_t> indices;
    int32_t vertex_count = 0;
    std::vector<int32_t> keys;  // empty = none
    [[nodiscard]] int64_t triangles() const { return static_cast<int64_t>(indices.size() / 3); }
};

co_mask_plan* make_plan(const Mesh& m, const Groups& g) {
    std::vector<const char*> names;
    for (const auto& n : g.names) names.push_back(n.c_str());
    return co_mask_plan_create(m.indices.data(), static_cast<int64_t>(m.indices.size()), m.vertex_count,
                               m.keys.empty() ? nullptr : m.keys.data(), g.count(),
                               names.empty() ? nullptr : names.data(), g.rows.data(), g.vertices.data(),
                               g.weights.data());
}

// in[v][g]: any row of g names v with a weight > 0.
std::vector<std::vector<char>> membership(const Mesh& m, const Groups& g) {
    std::vector<std::vector<char>> in(static_cast<size_t>(m.vertex_count),
                                      std::vector<char>(static_cast<size_t>(g.count()), 0));
    for (int32_t k = 0; k < g.count(); ++k) {
        for (int32_t r = el(g.rows, k); r < el(g.rows, k + 1); ++r) {
            if (el(g.weights, r) > 0.0f) el(el(in, el(g.vertices, r)), k) = 1;
        }
    }
    return in;
}

// Brute force: per triangle, the groups containing all three vertices; visibility for (hide, keep).
struct Reference {
    std::vector<std::vector<char>> all_in;  // [t][g]

    Reference(const Mesh& m, const Groups& g) {
        const auto in = membership(m, g);
        for (int64_t t = 0; t < m.triangles(); ++t) {
            std::vector<char> row(static_cast<size_t>(g.count()), 0);
            for (int32_t k = 0; k < g.count(); ++k) {
                bool all = true;
                for (int c = 0; c < 3; ++c) all = all && el(el(in, el(m.indices, 3 * t + c)), k) != 0;
                el(row, k) = all ? 1 : 0;
            }
            all_in.push_back(std::move(row));
        }
    }
    [[nodiscard]] bool visible(int64_t t, const std::vector<char>& hide, const std::vector<char>& keep) const {
        const auto& row = el(all_in, t);
        for (size_t k = 0; k < row.size(); ++k) {
            if (hide[k] != 0 && row[k] != 0) return false;
            if (keep[k] != 0 && row[k] == 0) return false;
        }
        return true;
    }
};

std::vector<uint64_t> words_of(const std::vector<char>& bits) {
    std::vector<uint64_t> w((bits.size() + 63) / 64, 0);
    for (size_t i = 0; i < bits.size(); ++i) {
        if (bits[i] != 0) w[i / 64] |= uint64_t{1} << (i % 64);
    }
    return w;
}

// Structural invariants of a plan: the buffer is a permutation of whole triangles (corner order kept), every
// triangle stays inside its key run (so the key at every triangle slot is unchanged), segments = key runs,
// classes tile the segments with ascending signatures whose groups are the brute-force ones.
void check_structure(const co_mask_plan* p, const Mesh& m, const Groups& g) {
    co_mask_info info{};
    CHECK(co_mask_plan_info(p, &info) == CO_OK);
    CHECK(info.triangle_count == m.triangles());
    CHECK(info.group_count == g.count());
    CHECK(info.vertex_count == m.vertex_count);

    const int32_t* idx = nullptr;
    const int32_t* perm = nullptr;
    CHECK(co_mask_indices(p, &idx) == static_cast<int64_t>(m.indices.size()));
    CHECK(co_mask_permutation(p, &perm) == m.triangles());
    std::vector<char> seen(static_cast<size_t>(m.triangles()), 0);
    for (int64_t s = 0; s < m.triangles(); ++s) {
        const int32_t t = perm[s];
        CHECK(t >= 0 && t < m.triangles());
        if (t < 0 || t >= m.triangles()) continue;
        CHECK(el(seen, t) == 0);
        el(seen, t) = 1;
        for (int c = 0; c < 3; ++c) CHECK(idx[3 * s + c] == el(m.indices, 3 * int64_t{t} + c));
        if (!m.keys.empty()) CHECK(el(m.keys, t) == el(m.keys, s));
    }

    // Segments = maximal key runs.
    std::vector<co_mask_range> segs(static_cast<size_t>(info.segment_count));
    CHECK(co_mask_segments(p, segs.data(), info.segment_count) == info.segment_count);
    int64_t runs = 0;
    for (int64_t t = 0; t < m.triangles(); ++t) {
        if (t == 0 || (!m.keys.empty() && el(m.keys, t) != el(m.keys, t - 1))) ++runs;
    }
    CHECK(runs == info.segment_count);
    int32_t at = 0;
    for (int32_t s = 0; s < info.segment_count; ++s) {
        const auto& r = el(segs, s);
        CHECK(r.first == at && r.count > 0 && r.segment == s);
        CHECK(r.key == (m.keys.empty() ? 0 : el(m.keys, r.first / 3)));
        at += r.count;
    }
    CHECK(at == static_cast<int32_t>(m.indices.size()));

    // Classes.
    std::vector<co_mask_class> classes(static_cast<size_t>(info.class_count));
    CHECK(co_mask_classes(p, classes.data(), info.class_count) == info.class_count);
    const Reference ref(m, g);
    at = 0;
    for (int32_t c = 0; c < info.class_count; ++c) {
        const auto& k = el(classes, c);
        CHECK(k.first == at && k.count > 0 && k.count % 3 == 0);
        CHECK(k.signature >= 0 && k.signature < info.signature_count);
        const auto& seg = el(segs, k.segment);
        CHECK(k.first >= seg.first && k.first + k.count <= seg.first + seg.count);
        if (c > 0 && el(classes, c - 1).segment == k.segment) CHECK(el(classes, c - 1).signature < k.signature);
        const int32_t* sg = nullptr;
        const int32_t n = co_mask_signature(p, k.signature, &sg);
        CHECK(n >= 0);
        std::vector<char> expect(static_cast<size_t>(g.count()), 0);
        for (int32_t i = 0; i < n; ++i) el(expect, sg[i]) = 1;
        for (int32_t s = k.first / 3; s < (k.first + k.count) / 3; ++s) {
            CHECK(el(ref.all_in, perm[s]) == expect);
        }
        at += k.count;
    }
    CHECK(at == static_cast<int32_t>(m.indices.size()));
    // Signature order: lexicographic over ascending group ids.
    std::vector<std::vector<int32_t>> sigs;
    for (int32_t s = 0; s < info.signature_count; ++s) {
        const int32_t* sg = nullptr;
        const int32_t n = co_mask_signature(p, s, &sg);
        sigs.emplace_back(sg, sg + n);
        CHECK(std::is_sorted(sigs.back().begin(), sigs.back().end()));
        if (s > 0) CHECK(el(sigs, s - 1) < el(sigs, s));
    }
}

// Ranges for (hide, keep) vs the reference; checks the range invariants too. Returns the range count.
int32_t check_query(const co_mask_plan* p, const Mesh& m, const Reference& ref, const std::vector<char>& hide,
                    const std::vector<char>& keep, int32_t flags) {
    const auto hw = words_of(hide);
    const auto kw = words_of(keep);
    co_mask_info info{};
    co_mask_plan_info(p, &info);
    const int32_t total = co_mask_ranges(p, hw.data(), static_cast<int32_t>(hw.size()), kw.data(),
                                         static_cast<int32_t>(kw.size()), flags, nullptr, 0);
    CHECK(total >= 0 && total <= std::max(info.class_count, 0));
    std::vector<co_mask_range> ranges(static_cast<size_t>(std::max(total, 0)));
    CHECK(co_mask_ranges(p, hw.data(), static_cast<int32_t>(hw.size()), kw.data(), static_cast<int32_t>(kw.size()),
                         flags, ranges.data(), total) == total);
    std::vector<co_mask_range> segs(static_cast<size_t>(info.segment_count));
    co_mask_segments(p, segs.data(), info.segment_count);
    const int32_t* perm = nullptr;
    co_mask_permutation(p, &perm);

    std::vector<char> got(static_cast<size_t>(m.triangles()), 0);
    int32_t prev_end = -1;
    int32_t prev_seg = -1;
    for (const auto& r : ranges) {
        CHECK(r.first % 3 == 0 && r.count > 0 && r.count % 3 == 0);
        CHECK(r.first >= prev_end);
        // Adjacent ranges are merged (inside a segment always; everywhere with CO_MASK_MERGE_SEGMENTS).
        if (r.first == prev_end) CHECK((flags & CO_MASK_MERGE_SEGMENTS) == 0 && r.segment != prev_seg);
        CHECK(r.segment >= 0 && r.segment < info.segment_count);
        const auto& seg = el(segs, r.segment);
        CHECK(r.key == seg.key);
        CHECK(r.first >= seg.first && r.first < seg.first + seg.count);
        if ((flags & CO_MASK_MERGE_SEGMENTS) == 0) CHECK(r.first + r.count <= seg.first + seg.count);
        for (int32_t s = r.first / 3; s < (r.first + r.count) / 3; ++s) el(got, perm[s]) = 1;
        prev_end = r.first + r.count;
        prev_seg = r.segment;
    }
    for (int64_t t = 0; t < m.triangles(); ++t) {
        const bool want = ref.visible(t, hide, keep);
        if ((el(got, t) != 0) != want) {
            std::fprintf(stderr, "triangle %lld: visible %d, reference %d\n", static_cast<long long>(t),
                         el(got, t) != 0 ? 1 : 0, want ? 1 : 0);
            ++failures;
            break;
        }
    }
    return total;
}

// ---- Garment-style body: rings of quads, a soup (every triangle owns its 3 corners) ----------------------------

constexpr int kRings = 36;  // quad rows
constexpr int kSides = 12;  // quads per ring

struct Body {
    Mesh mesh;
    std::vector<int32_t> ring_of_vertex;  // the vertex's ring LINE (0..kRings)
    std::vector<int32_t> side_of_vertex;  // 0..kSides
};

Body make_body() {
    Body b;
    auto corner = [&](int ring, int side) {
        b.ring_of_vertex.push_back(ring);
        b.side_of_vertex.push_back(side % kSides);
        return b.mesh.vertex_count++;
    };
    // Parts by ring: legs [0,14), torso [14,28), head [28,32), torso again [32,36) (a key run that repeats).
    auto key_of = [](int ring) { return ring < 14 ? 0 : ring < 28 ? 1 : ring < 32 ? 2 : 1; };
    for (int r = 0; r < kRings; ++r) {
        for (int s = 0; s < kSides; ++s) {
            const int32_t a = corner(r, s), bb = corner(r, s + 1), c = corner(r + 1, s + 1);
            const int32_t d = corner(r, s), e = corner(r + 1, s + 1), f = corner(r + 1, s);
            b.mesh.indices.insert(b.mesh.indices.end(), {a, bb, c, d, e, f});
            b.mesh.keys.push_back(key_of(r));
            b.mesh.keys.push_back(key_of(r));
        }
    }
    return b;
}

// Every corner whose ring line is in [r0, r1] (and side in [s0, s1]) - all soup copies of those positions.
std::vector<std::pair<int32_t, float>> band(const Body& b, int r0, int r1, int s0 = 0, int s1 = kSides, float w = 1.0f) {
    std::vector<std::pair<int32_t, float>> out;
    for (int32_t v = 0; v < b.mesh.vertex_count; ++v) {
        const int r = el(b.ring_of_vertex, v), s = el(b.side_of_vertex, v);
        if (r >= r0 && r <= r1 && s >= s0 && s <= s1) out.emplace_back(v, w);
    }
    return out;
}

Groups body_groups(const Body& b) {
    Groups g;
    g.add("trousers", band(b, 0, 13));
    g.add("shirt", band(b, 11, 26));
    auto jacket = band(b, 9, 27, 0, 6);  // front half only
    auto rim = band(b, 28, 28, 0, 6, 0.0f);  // zero weights: listed but NOT in the group
    jacket.insert(jacket.end(), rim.begin(), rim.end());
    auto again = band(b, 12, 12, 0, 6, 0.5f);  // a second row for some vertices: still one membership
    jacket.insert(jacket.end(), again.begin(), again.end());
    g.add("jacket", jacket);
    g.add("boots", band(b, 0, 4));
    g.add("belt", band(b, 12, 15));
    g.add("hat", band(b, 30, 36));
    return g;
}

void test_garments() {
    const Body b = make_body();
    const Groups g = body_groups(b);
    co_mask_plan* p = make_plan(b.mesh, g);
    CHECK(p != nullptr);
    if (p == nullptr) {
        std::fprintf(stderr, "co_mask_plan_create: %s\n", co_last_error());
        return;
    }
    CHECK(std::strcmp(co_last_error(), "") == 0);
    check_structure(p, b.mesh, g);
    const Reference ref(b.mesh, g);

    co_mask_info info{};
    co_mask_plan_info(p, &info);
    std::printf("garments: %lld triangles, %d segments, %d signatures, %d classes\n",
                static_cast<long long>(info.triangle_count), info.segment_count, info.signature_count,
                info.class_count);

    // Names.
    CHECK(co_mask_group_find(p, "shirt") == 1);
    CHECK(co_mask_group_find(p, "hat") == 5);
    CHECK(co_mask_group_find(p, "cape") == -1);
    CHECK(std::strlen(co_last_error()) > 0);
    CHECK(std::strcmp(co_mask_group_name(p, 2), "jacket") == 0);

    // Exhaustive: every group none / hide / keep (3^6 = 729), with and without merging across segments.
    const int n = g.count();
    int64_t max_ranges = 0;
    int combos = 1;
    for (int i = 0; i < n; ++i) combos *= 3;
    for (int code = 0; code < combos; ++code) {
        std::vector<char> hide(static_cast<size_t>(n), 0), keep(static_cast<size_t>(n), 0);
        int c = code;
        for (int i = 0; i < n; ++i, c /= 3) {
            if (c % 3 == 1) el(hide, i) = 1;
            if (c % 3 == 2) el(keep, i) = 1;
        }
        max_ranges = std::max<int64_t>(max_ranges, check_query(p, b.mesh, ref, hide, keep, 0));
        check_query(p, b.mesh, ref, hide, keep, CO_MASK_MERGE_SEGMENTS);
    }
    // A group in BOTH bitsets hides everything it would keep.
    for (int i = 0; i < n; ++i) {
        std::vector<char> both(static_cast<size_t>(n), 0);
        el(both, i) = 1;
        check_query(p, b.mesh, ref, both, both, 0);
    }
    std::printf("garments: 3^%d hide/keep combinations OK, at most %lld ranges per query\n", n,
                static_cast<long long>(max_ranges));

    // Nothing hidden: one range per segment; merged: one range.
    CHECK(co_mask_ranges(p, nullptr, 0, nullptr, 0, 0, nullptr, 0) == info.segment_count);
    co_mask_range whole{};
    CHECK(co_mask_ranges(p, nullptr, 0, nullptr, 0, CO_MASK_MERGE_SEGMENTS, &whole, 1) == 1);
    CHECK(whole.first == 0 && whole.count == static_cast<int32_t>(b.mesh.indices.size()) && whole.segment == 0);

    // The hide rule spelled out: hiding the shirt removes the quads of rings 11..25 (all corners on lines 11..26),
    // and keeps ring 10 (its lower corners are outside) - the border never opens.
    {
        const uint64_t shirt = uint64_t{1} << 1;
        const int32_t total = co_mask_ranges(p, &shirt, 1, nullptr, 0, 0, nullptr, 0);
        std::vector<co_mask_range> r(static_cast<size_t>(total));
        co_mask_ranges(p, &shirt, 1, nullptr, 0, 0, r.data(), total);
        int64_t drawn = 0;
        for (const auto& x : r) drawn += x.count / 3;
        CHECK(drawn == b.mesh.triangles() - int64_t{15} * kSides * 2);
        // KEEP shirt = exactly the complement.
        const int32_t kt = co_mask_ranges(p, nullptr, 0, &shirt, 1, 0, nullptr, 0);
        std::vector<co_mask_range> k(static_cast<size_t>(kt));
        co_mask_ranges(p, nullptr, 0, &shirt, 1, 0, k.data(), kt);
        int64_t kept = 0;
        for (const auto& x : k) kept += x.count / 3;
        CHECK(kept == int64_t{15} * kSides * 2);
    }
    // Zero-weight rows: the jacket does not reach ring line 28, so its quads on ring 27 are not "covered".
    {
        const uint64_t jacket = uint64_t{1} << 2;
        const int32_t kt = co_mask_ranges(p, nullptr, 0, &jacket, 1, 0, nullptr, 0);
        std::vector<co_mask_range> k(static_cast<size_t>(kt));
        co_mask_ranges(p, nullptr, 0, &jacket, 1, 0, k.data(), kt);
        int64_t kept = 0;
        for (const auto& x : k) kept += x.count / 3;
        CHECK(kept == int64_t{18} * 6 * 2);  // rings 9..26, sides 0..5
    }
    // Capacity smaller than the total: the total is returned, only `capacity` ranges written.
    {
        const uint64_t hide = (uint64_t{1} << 1) | (uint64_t{1} << 5);
        const int32_t total = co_mask_ranges(p, &hide, 1, nullptr, 0, 0, nullptr, 0);
        CHECK(total >= 2);
        std::vector<co_mask_range> r(static_cast<size_t>(total) + 1, co_mask_range{-7, -7, -7, -7});
        CHECK(co_mask_ranges(p, &hide, 1, nullptr, 0, 0, r.data(), 1) == total);
        CHECK(el(r, 0).first >= 0 && el(r, 1).first == -7);
    }
    co_mask_plan_destroy(p);
}

// ---- Random soups --------------------------------------------------------------------------------------------

Mesh random_mesh(std::mt19937& rng, int32_t vertices, int32_t triangles, bool keys) {
    Mesh m;
    m.vertex_count = vertices;
    std::uniform_int_distribution<int32_t> vd(0, vertices - 1);
    for (int32_t t = 0; t < 3 * triangles; ++t) m.indices.push_back(vd(rng));
    if (keys) {
        std::uniform_int_distribution<int32_t> kd(0, 4);
        std::uniform_int_distribution<int32_t> len(1, 40);
        while (static_cast<int32_t>(m.keys.size()) < triangles) {
            const int32_t k = kd(rng), n = len(rng);
            for (int32_t i = 0; i < n && static_cast<int32_t>(m.keys.size()) < triangles; ++i) m.keys.push_back(k);
        }
    }
    return m;
}

Groups random_groups(std::mt19937& rng, const Mesh& m, int32_t count, double density) {
    Groups g;
    std::uniform_real_distribution<double> u(0.0, 1.0);
    for (int32_t k = 0; k < count; ++k) {
        std::vector<std::pair<int32_t, float>> rows;
        for (int32_t v = 0; v < m.vertex_count; ++v) {
            if (u(rng) < density) {
                const double w = u(rng);
                // ~15% zero, a few NaN / negative: listed but not in the group.
                float weight = w < 0.15 ? 0.0f : w < 0.17 ? std::numeric_limits<float>::quiet_NaN()
                                                 : w < 0.19 ? -0.5f
                                                            : static_cast<float>(w);
                rows.emplace_back(v, weight);
                if (u(rng) < 0.05) rows.emplace_back(v, 0.25f);  // duplicate row
            }
        }
        std::shuffle(rows.begin(), rows.end(), rng);
        g.add("g" + std::to_string(k), rows);
    }
    return g;
}

void test_random() {
    std::mt19937 rng(172);
    for (int seed = 0; seed < 16; ++seed) {
        const bool keys = seed % 2 == 0;
        const Mesh m = random_mesh(rng, 60, 400, keys);
        const Groups g = random_groups(rng, m, 6, 0.75);
        co_mask_plan* p = make_plan(m, g);
        CHECK(p != nullptr);
        if (p == nullptr) continue;
        check_structure(p, m, g);
        const Reference ref(m, g);
        for (int code = 0; code < 729; ++code) {
            std::vector<char> hide(6, 0), keep(6, 0);
            int c = code;
            for (int i = 0; i < 6; ++i, c /= 3) {
                if (c % 3 == 1) el(hide, i) = 1;
                if (c % 3 == 2) el(keep, i) = 1;
            }
            check_query(p, m, ref, hide, keep, 0);
            if (code % 7 == 0) check_query(p, m, ref, hide, keep, CO_MASK_MERGE_SEGMENTS);
        }
        co_mask_plan_destroy(p);
    }
    std::printf("random: 16 soups x 729 combinations OK\n");
}

void test_many_groups() {
    std::mt19937 rng(4242);
    const Mesh m = random_mesh(rng, 300, 900, true);
    const int32_t n = 130;
    const Groups g = random_groups(rng, m, n, 0.8);
    co_mask_plan* p = make_plan(m, g);
    CHECK(p != nullptr);
    if (p == nullptr) return;
    check_structure(p, m, g);
    const Reference ref(m, g);
    co_mask_info info{};
    co_mask_plan_info(p, &info);
    std::uniform_real_distribution<double> u(0.0, 1.0);
    std::uniform_int_distribution<int32_t> pick(0, n - 1);
    for (int trial = 0; trial < 300; ++trial) {
        std::vector<char> hide(static_cast<size_t>(n), 0), keep(static_cast<size_t>(n), 0);
        const double rate = trial % 3 == 0 ? 0.01 : trial % 3 == 1 ? 0.05 : 0.2;
        for (int32_t k = 0; k < n; ++k) el(hide, k) = u(rng) < rate ? 1 : 0;
        const int keeps = trial % 4;  // 0..3 kept groups (more keeps = nearly nothing left)
        for (int i = 0; i < keeps; ++i) el(keep, pick(rng)) = 1;
        check_query(p, m, ref, hide, keep, trial % 2 == 0 ? 0 : CO_MASK_MERGE_SEGMENTS);
    }
    // Groups 64.. and 128.. live in later words; a bit past the last group is refused, extra zero words are fine.
    {
        std::vector<char> hide(static_cast<size_t>(n), 0), keep(static_cast<size_t>(n), 0);
        el(hide, 129) = 1;
        el(hide, 64) = 1;
        check_query(p, m, ref, hide, keep, 0);
        std::vector<uint64_t> w(5, 0);
        w[2] = uint64_t{1} << 1;  // group 129
        CHECK(co_mask_ranges(p, w.data(), 5, nullptr, 0, 0, nullptr, 0) >= 0);
        w[2] = uint64_t{1} << 2;  // group 130: none
        CHECK(co_mask_ranges(p, w.data(), 5, nullptr, 0, 0, nullptr, 0) == CO_ERR_RANGE);
        CHECK(std::strstr(co_last_error(), "130") != nullptr);
        w[2] = 0;
        w[4] = 1;
        CHECK(co_mask_ranges(p, nullptr, 0, w.data(), 5, 0, nullptr, 0) == CO_ERR_RANGE);
    }
    std::printf("many groups: %d groups, %d signatures, %d classes, 300 random queries OK\n", n,
                info.signature_count, info.class_count);
    co_mask_plan_destroy(p);
}

void test_empty_cases() {
    // No groups: one range per key run (no reorder), group lookups fail cleanly.
    {
        Mesh m;
        m.vertex_count = 4;
        m.indices = {0, 1, 2, 2, 3, 0, 1, 2, 3, 0, 1, 3};
        m.keys = {5, 5, 9, 5};
        const Groups g;
        co_mask_plan* p = make_plan(m, g);
        CHECK(p != nullptr);
        if (p != nullptr) {
            check_structure(p, m, g);
            co_mask_info info{};
            co_mask_plan_info(p, &info);
            CHECK(info.segment_count == 3 && info.signature_count == 1 && info.class_count == 3);
            co_mask_range r[4];
            CHECK(co_mask_ranges(p, nullptr, 0, nullptr, 0, 0, r, 4) == 3);
            CHECK(r[0].first == 0 && r[0].count == 6 && r[0].key == 5);
            CHECK(r[1].first == 6 && r[1].count == 3 && r[1].key == 9);
            CHECK(r[2].first == 9 && r[2].count == 3 && r[2].key == 5 && r[2].segment == 2);
            const int32_t* perm = nullptr;
            co_mask_permutation(p, &perm);
            CHECK(perm[0] == 0 && perm[1] == 1 && perm[2] == 2 && perm[3] == 3);
            CHECK(co_mask_group_find(p, "x") == -1);
            CHECK(co_mask_group_name(p, 0) == nullptr);
            const uint64_t one = 1;
            CHECK(co_mask_ranges(p, &one, 1, nullptr, 0, 0, nullptr, 0) == CO_ERR_RANGE);
            co_mask_plan_destroy(p);
        }
    }
    // No triangles at all.
    {
        co_mask_plan* p = co_mask_plan_create(nullptr, 0, 0, nullptr, 0, nullptr, nullptr, nullptr, nullptr);
        CHECK(p != nullptr);
        co_mask_info info{};
        CHECK(co_mask_plan_info(p, &info) == CO_OK);
        CHECK(info.triangle_count == 0 && info.segment_count == 0 && info.class_count == 0);
        CHECK(co_mask_ranges(p, nullptr, 0, nullptr, 0, 0, nullptr, 0) == 0);
        const int32_t* idx = nullptr;
        CHECK(co_mask_indices(p, &idx) == 0);
        co_mask_plan_destroy(p);
    }
    // Empty groups (no rows / only zero weights), triangles in no group, unnamed groups.
    {
        Mesh m;
        m.vertex_count = 6;
        m.indices = {0, 1, 2, 3, 4, 5, 0, 1, 5};
        Groups g;
        g.add("", {});
        g.add("zero", {{0, 0.0f}, {1, 0.0f}, {2, 0.0f}});
        g.add("tri0", {{0, 1.0f}, {1, 1.0f}, {2, 1.0f}});
        co_mask_plan* p = make_plan(m, g);
        CHECK(p != nullptr);
        if (p != nullptr) {
            check_structure(p, m, g);
            const Reference ref(m, g);
            for (int code = 0; code < 27; ++code) {
                std::vector<char> hide(3, 0), keep(3, 0);
                int c = code;
                for (int i = 0; i < 3; ++i, c /= 3) {
                    if (c % 3 == 1) el(hide, i) = 1;
                    if (c % 3 == 2) el(keep, i) = 1;
                }
                check_query(p, m, ref, hide, keep, 0);
            }
            const uint64_t empty = 1, zero = 2, tri0 = 4;
            const int32_t all = co_mask_ranges(p, nullptr, 0, nullptr, 0, 0, nullptr, 0);
            CHECK(all == 1);
            CHECK(co_mask_ranges(p, &empty, 1, nullptr, 0, 0, nullptr, 0) == 1);  // hiding nothing-groups: all drawn
            CHECK(co_mask_ranges(p, &zero, 1, nullptr, 0, 0, nullptr, 0) == 1);
            CHECK(co_mask_ranges(p, nullptr, 0, &empty, 1, 0, nullptr, 0) == 0);  // keep-only an empty group: none
            CHECK(co_mask_ranges(p, nullptr, 0, &zero, 1, 0, nullptr, 0) == 0);
            co_mask_range r{};
            CHECK(co_mask_ranges(p, &tri0, 1, nullptr, 0, 0, &r, 1) == 1);
            CHECK(r.count == 6);  // triangles 1 and 2 (in no group / touching only) stay
            CHECK(co_mask_group_find(p, "") == -1);  // an unnamed group is never found
            CHECK(co_mask_group_find(p, "tri0") == 2);
            CHECK(std::strcmp(co_mask_group_name(p, 0), "") == 0);
            co_mask_plan_destroy(p);
        }
        // NULL weights = every row in its group; NULL names.
        const int32_t rows[] = {0, 3};
        const int32_t verts[] = {0, 1, 2};
        co_mask_plan* q = co_mask_plan_create(m.indices.data(), 9, 6, nullptr, 1, nullptr, rows, verts, nullptr);
        CHECK(q != nullptr);
        if (q != nullptr) {
            const uint64_t one = 1;
            co_mask_range r{};
            CHECK(co_mask_ranges(q, &one, 1, nullptr, 0, 0, &r, 1) == 1);
            CHECK(r.count == 6);
            CHECK(std::strcmp(co_mask_group_name(q, 0), "") == 0);
            co_mask_plan_destroy(q);
        }
    }
}

// ---- co_doc path: part-local rows -----------------------------------------------------------------------------

void test_from_doc() {
    // Two parts: "body" (a 4-ring strip, 48 triangles, vertices [0, 144)) and "hat" (12 triangles after it).
    Mesh soup;
    std::vector<float> positions;
    std::vector<int32_t> part_of_tri;
    auto add_tri = [&](int part, float x, float y) {
        for (int c = 0; c < 3; ++c) {
            positions.insert(positions.end(), {x + static_cast<float>(c), y, 0.0f});
            soup.indices.push_back(soup.vertex_count++);
        }
        part_of_tri.push_back(part);
    };
    for (int t = 0; t < 48; ++t) add_tri(0, static_cast<float>(t % 12), static_cast<float>(t / 12));
    for (int t = 0; t < 12; ++t) add_tri(1, static_cast<float>(t), 10.0f);

    co_doc* d = co_doc_create();
    CHECK(d != nullptr);
    CHECK(co_doc_mesh_set_array(d, CO_MESH_VERTICES, positions.data(), static_cast<int64_t>(positions.size())) ==
          CO_OK);
    CHECK(co_doc_mesh_set_array(d, CO_MESH_INDICES, soup.indices.data(),
                                static_cast<int64_t>(soup.indices.size())) == CO_OK);
    co_part parts[2]{};
    parts[0].id = "body";
    parts[0].scale[0] = parts[0].scale[1] = parts[0].scale[2] = 1.0f;
    parts[0].vertex_start = 0;
    parts[0].vertex_count = 144;
    parts[0].index_start = 0;
    parts[0].index_count = 144;
    parts[0].face_count = 48;
    parts[0].visible = 1;
    parts[1] = parts[0];
    parts[1].id = "hat";
    parts[1].vertex_start = 144;
    parts[1].vertex_count = 36;
    parts[1].index_start = 144;
    parts[1].index_count = 36;
    parts[1].face_start = 48;
    parts[1].face_count = 12;
    CHECK(co_doc_parts_set(d, parts, 2) == CO_OK);

    // Groups (part-local rows): "skin_under_shirt" = body triangles 12..35 + the first hat triangles' corners;
    // "brim" = hat triangles 0..5; "odd" = every odd body triangle (weights 0.3).
    Groups soup_groups;  // the same groups with SOUP rows, for the raw-array reference plan
    std::vector<const char*> names = {"skin_under_shirt", "brim", "odd"};
    std::vector<int32_t> group_parts = {0};
    std::vector<const char*> part_ids;
    std::vector<int32_t> part_rows = {0};
    std::vector<int32_t> local;
    std::vector<float> weights;
    auto add_part_rows = [&](const char* pid, int32_t start, const std::vector<std::pair<int32_t, float>>& rows,
                             std::vector<std::pair<int32_t, float>>& soup_rows) {
        part_ids.push_back(pid);
        for (const auto& [v, w] : rows) {
            local.push_back(v);
            weights.push_back(w);
            soup_rows.emplace_back(start + v, w);
        }
        part_rows.push_back(static_cast<int32_t>(local.size()));
    };
    {
        std::vector<std::pair<int32_t, float>> s, body, hat;
        for (int32_t v = 36; v < 108; ++v) body.emplace_back(v, 1.0f);
        for (int32_t v = 0; v < 6; ++v) hat.emplace_back(v, 1.0f);
        add_part_rows("body", 0, body, s);
        add_part_rows("hat", 144, hat, s);
        group_parts.push_back(static_cast<int32_t>(part_ids.size()));
        soup_groups.add(names[0], s);
    }
    {
        std::vector<std::pair<int32_t, float>> s, hat;
        for (int32_t v = 0; v < 18; ++v) hat.emplace_back(v, 0.8f);
        add_part_rows("hat", 144, hat, s);
        group_parts.push_back(static_cast<int32_t>(part_ids.size()));
        soup_groups.add(names[1], s);
    }
    {
        std::vector<std::pair<int32_t, float>> s, body;
        for (int32_t t = 1; t < 48; t += 2) {
            for (int c = 0; c < 3; ++c) body.emplace_back(3 * t + c, 0.3f);
        }
        add_part_rows("body", 0, body, s);
        group_parts.push_back(static_cast<int32_t>(part_ids.size()));
        soup_groups.add(names[2], s);
    }
    CHECK(co_doc_vertex_groups_set(d, 3, names.data(), group_parts.data(), part_ids.data(), part_rows.data(),
                                   local.data(), weights.data()) == CO_OK);

    // KEY_PART vs the raw plan with part keys.
    soup.keys = part_of_tri;
    co_mask_plan* doc_plan = co_mask_plan_from_doc(d, CO_MASK_KEY_PART, nullptr, 0);
    co_mask_plan* raw_plan = make_plan(soup, soup_groups);
    CHECK(doc_plan != nullptr && raw_plan != nullptr);
    if (doc_plan != nullptr && raw_plan != nullptr) {
        check_structure(doc_plan, soup, soup_groups);
        const int32_t *a = nullptr, *b = nullptr;
        const int64_t na = co_mask_indices(doc_plan, &a), nb = co_mask_indices(raw_plan, &b);
        CHECK(na == nb && na == 180);
        CHECK(na == nb && std::equal(a, a + na, b));
        // Each part keeps its index range: the hat's triangles are still [144, 180).
        co_mask_range segs[4];
        CHECK(co_mask_segments(doc_plan, segs, 4) == 2);
        CHECK(segs[0].key == 0 && segs[0].first == 0 && segs[0].count == 144);
        CHECK(segs[1].key == 1 && segs[1].first == 144 && segs[1].count == 36);
        CHECK(co_mask_group_find(doc_plan, "brim") == 1);
        const Reference ref(soup, soup_groups);
        for (int code = 0; code < 27; ++code) {
            std::vector<char> hide(3, 0), keep(3, 0);
            int c = code;
            for (int i = 0; i < 3; ++i, c /= 3) {
                if (c % 3 == 1) el(hide, i) = 1;
                if (c % 3 == 2) el(keep, i) = 1;
            }
            check_query(doc_plan, soup, ref, hide, keep, 0);
        }
        // Hide "skin_under_shirt": body triangles 12..35 (72 indices) and hat triangles 0..1 go.
        const uint64_t skin = 1;
        co_mask_range r[8];
        const int32_t n = co_mask_ranges(doc_plan, &skin, 1, nullptr, 0, 0, r, 8);
        int32_t drawn = 0;
        for (int32_t i = 0; i < n; ++i) drawn += r[i].count;
        CHECK(drawn == 180 - 72 - 6);
    }
    co_mask_plan_destroy(doc_plan);
    co_mask_plan_destroy(raw_plan);

    // KEY_NONE == raw without keys; KEY_CUSTOM == raw with those keys.
    {
        Mesh flat = soup;
        flat.keys.clear();
        co_mask_plan* x = co_mask_plan_from_doc(d, CO_MASK_KEY_NONE, nullptr, 0);
        co_mask_plan* y = make_plan(flat, soup_groups);
        CHECK(x != nullptr && y != nullptr);
        if (x != nullptr && y != nullptr) {
            const int32_t *a = nullptr, *b = nullptr;
            CHECK(co_mask_indices(x, &a) == co_mask_indices(y, &b));
            CHECK(std::equal(a, a + 180, b));
            check_structure(x, flat, soup_groups);
        }
        co_mask_plan_destroy(x);
        co_mask_plan_destroy(y);

        Mesh custom = soup;
        custom.keys.clear();
        for (int t = 0; t < 60; ++t) custom.keys.push_back(t / 7);
        x = co_mask_plan_from_doc(d, CO_MASK_KEY_CUSTOM, custom.keys.data(), 60);
        y = make_plan(custom, soup_groups);
        CHECK(x != nullptr && y != nullptr);
        if (x != nullptr && y != nullptr) {
            const int32_t *a = nullptr, *b = nullptr;
            CHECK(co_mask_indices(x, &a) == co_mask_indices(y, &b));
            CHECK(std::equal(a, a + 180, b));
            check_structure(x, custom, soup_groups);
        }
        co_mask_plan_destroy(x);
        co_mask_plan_destroy(y);
        CHECK(co_mask_plan_from_doc(d, CO_MASK_KEY_CUSTOM, custom.keys.data(), 59) == nullptr);
        CHECK(std::strstr(co_last_error(), "CO_MASK_KEY_CUSTOM") != nullptr);
        CHECK(co_mask_plan_from_doc(d, CO_MASK_KEY_CUSTOM, nullptr, 60) == nullptr);
        CHECK(co_mask_plan_from_doc(d, 7, nullptr, 0) == nullptr);
    }

    // Errors: a group row naming a part the document lacks; a local vertex outside its part.
    {
        std::vector<const char*> bad_ids = part_ids;
        bad_ids[0] = "torso";
        CHECK(co_doc_vertex_groups_set(d, 3, names.data(), group_parts.data(), bad_ids.data(), part_rows.data(),
                                       local.data(), weights.data()) == CO_OK);
        CHECK(co_mask_plan_from_doc(d, CO_MASK_KEY_PART, nullptr, 0) == nullptr);
        CHECK(std::strstr(co_last_error(), "torso") != nullptr);

        std::vector<int32_t> bad_local = local;
        bad_local[static_cast<size_t>(part_rows[1])] = 36;  // first "hat" row of group 0: the hat has 36 vertices
        CHECK(co_doc_vertex_groups_set(d, 3, names.data(), group_parts.data(), part_ids.data(), part_rows.data(),
                                       bad_local.data(), weights.data()) == CO_OK);
        CHECK(co_mask_plan_from_doc(d, CO_MASK_KEY_PART, nullptr, 0) == nullptr);
        CHECK(std::strstr(co_last_error(), "outside part 'hat'") != nullptr);
        bad_local[static_cast<size_t>(part_rows[1])] = -1;  // a negative local vertex: the document refuses it
        CHECK(co_doc_vertex_groups_set(d, 3, names.data(), group_parts.data(), part_ids.data(), part_rows.data(),
                                       bad_local.data(), weights.data()) != CO_OK);
    }
    // No groups: a plan with one class per part.
    {
        CHECK(co_doc_vertex_groups_set(d, 0, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr) == CO_OK);
        co_mask_plan* p = co_mask_plan_from_doc(d, CO_MASK_KEY_PART, nullptr, 0);
        CHECK(p != nullptr);
        co_mask_info info{};
        CHECK(co_mask_plan_info(p, &info) == CO_OK);
        CHECK(info.group_count == 0 && info.segment_count == 2 && info.class_count == 2);
        co_mask_plan_destroy(p);
    }
    co_doc_destroy(d);

    // A document without a mesh: an empty plan.
    co_doc* e = co_doc_create();
    co_mask_plan* p = co_mask_plan_from_doc(e, CO_MASK_KEY_PART, nullptr, 0);
    CHECK(p != nullptr);
    co_mask_info info{};
    co_mask_plan_info(p, &info);
    CHECK(info.triangle_count == 0);
    co_mask_plan_destroy(p);
    co_doc_destroy(e);
    CHECK(co_mask_plan_from_doc(nullptr, 0, nullptr, 0) == nullptr);
    std::printf("from co_doc: part-local rows, KEY_PART / NONE / CUSTOM, errors OK\n");
}

// ---- Errors -------------------------------------------------------------------------------------------------

void test_errors() {
    const int32_t idx[] = {0, 1, 2, 2, 1, 3};
    const int32_t rows[] = {0, 2};
    const int32_t verts[] = {0, 1};
    auto refused = [](co_mask_plan* p, const char* needle) {
        CHECK(p == nullptr);
        CHECK(std::strstr(co_last_error(), "co_mask_plan_create") != nullptr);
        CHECK(std::strstr(co_last_error(), needle) != nullptr);
        co_mask_plan_destroy(p);
    };
    refused(co_mask_plan_create(idx, 5, 4, nullptr, 0, nullptr, nullptr, nullptr, nullptr), "multiple of 3");
    refused(co_mask_plan_create(idx, -3, 4, nullptr, 0, nullptr, nullptr, nullptr, nullptr), "negative");
    refused(co_mask_plan_create(nullptr, 6, 4, nullptr, 0, nullptr, nullptr, nullptr, nullptr), "null indices");
    refused(co_mask_plan_create(idx, 6, 3, nullptr, 0, nullptr, nullptr, nullptr, nullptr), "outside");
    refused(co_mask_plan_create(idx, 6, -1, nullptr, 0, nullptr, nullptr, nullptr, nullptr), "negative vertex");
    refused(co_mask_plan_create(idx, 6, 4, nullptr, -1, nullptr, rows, verts, nullptr), "negative group");
    refused(co_mask_plan_create(idx, 6, 4, nullptr, 1, nullptr, nullptr, verts, nullptr), "null group_rows");
    const int32_t bad_first[] = {1, 2};
    refused(co_mask_plan_create(idx, 6, 4, nullptr, 1, nullptr, bad_first, verts, nullptr), "group_rows[0]");
    const int32_t decreasing[] = {0, 2, 1};
    refused(co_mask_plan_create(idx, 6, 4, nullptr, 2, nullptr, decreasing, verts, nullptr), "decrease");
    refused(co_mask_plan_create(idx, 6, 4, nullptr, 1, nullptr, rows, nullptr, nullptr), "null group vertices");
    const int32_t far[] = {0, 9};
    refused(co_mask_plan_create(idx, 6, 4, nullptr, 1, nullptr, rows, far, nullptr), "names vertex 9");

    // Null plans.
    co_mask_info info{};
    CHECK(co_mask_plan_info(nullptr, &info) == CO_ERR_ARG);
    CHECK(std::strstr(co_last_error(), "null plan") != nullptr);
    CHECK(co_mask_indices(nullptr, nullptr) == -1);
    CHECK(co_mask_permutation(nullptr, nullptr) == -1);
    CHECK(co_mask_segments(nullptr, nullptr, 0) == CO_ERR_ARG);
    CHECK(co_mask_classes(nullptr, nullptr, 0) == CO_ERR_ARG);
    CHECK(co_mask_signature(nullptr, 0, nullptr) == CO_ERR_ARG);
    CHECK(co_mask_group_find(nullptr, "a") == -1);
    CHECK(co_mask_group_name(nullptr, 0) == nullptr);
    CHECK(co_mask_ranges(nullptr, nullptr, 0, nullptr, 0, 0, nullptr, 0) == CO_ERR_ARG);
    co_mask_plan_destroy(nullptr);

    co_mask_plan* p = co_mask_plan_create(idx, 6, 4, nullptr, 1, nullptr, rows, verts, nullptr);
    CHECK(p != nullptr);
    CHECK(std::strcmp(co_last_error(), "") == 0);
    co_mask_range r{};
    CHECK(co_mask_plan_info(p, nullptr) == CO_ERR_ARG);
    CHECK(co_mask_ranges(p, nullptr, 0, nullptr, 0, 0, nullptr, 1) == CO_ERR_ARG);   // capacity without out
    CHECK(co_mask_ranges(p, nullptr, 0, nullptr, 0, 0, &r, -1) == CO_ERR_ARG);       // negative capacity
    CHECK(co_mask_ranges(p, nullptr, 0, nullptr, 0, 4, &r, 1) == CO_ERR_ARG);        // unknown flag
    CHECK(co_mask_ranges(p, nullptr, 1, nullptr, 0, 0, &r, 1) == CO_ERR_ARG);        // words missing
    CHECK(co_mask_ranges(p, nullptr, -1, nullptr, 0, 0, &r, 1) == CO_ERR_ARG);       // negative word count
    const uint64_t two = 2;
    CHECK(co_mask_ranges(p, &two, 1, nullptr, 0, 0, &r, 1) == CO_ERR_RANGE);        // group 1 of 1
    CHECK(co_mask_signature(p, 99, nullptr) == CO_ERR_RANGE);
    CHECK(co_mask_group_name(p, 1) == nullptr);
    CHECK(co_mask_group_find(p, nullptr) == -1);
    CHECK(co_mask_segments(p, nullptr, 1) == CO_ERR_ARG);
    CHECK(co_mask_classes(p, nullptr, -1) == CO_ERR_ARG);
    // A good call clears the message.
    CHECK(co_mask_ranges(p, nullptr, 0, nullptr, 0, 0, &r, 1) == 1);
    CHECK(std::strcmp(co_last_error(), "") == 0);
    co_mask_plan_destroy(p);
}

// ---- Timing ---------------------------------------------------------------------------------------------------

void test_timing() {
    // ~100k triangles: 250 rings x 200 quads, a soup (300k vertices), 20 overlapping ring bands, 8 parts.
    constexpr int rings = 250, sides = 200;
    Mesh m;
    std::vector<int32_t> ring_of;
    for (int r = 0; r < rings; ++r) {
        for (int s = 0; s < sides; ++s) {
            for (const int c : {r, r, r + 1, r, r + 1, r + 1}) {
                ring_of.push_back(c);
                m.indices.push_back(m.vertex_count++);
            }
            m.keys.push_back(r * 8 / rings);
            m.keys.push_back(r * 8 / rings);
        }
    }
    Groups g;
    std::mt19937 rng(20);
    std::uniform_int_distribution<int> start(0, rings - 1), width(5, 60);
    for (int k = 0; k < 20; ++k) {
        const int a = start(rng), b = std::min(rings, a + width(rng));
        std::vector<std::pair<int32_t, float>> rows;
        for (int32_t v = 0; v < m.vertex_count; ++v) {
            if (el(ring_of, v) >= a && el(ring_of, v) <= b) rows.emplace_back(v, 1.0f);
        }
        g.add("band" + std::to_string(k), rows);
    }
    const auto t0 = std::chrono::steady_clock::now();
    co_mask_plan* p = make_plan(m, g);
    const auto t1 = std::chrono::steady_clock::now();
    CHECK(p != nullptr);
    if (p == nullptr) return;
    co_mask_info info{};
    co_mask_plan_info(p, &info);
    std::vector<co_mask_range> r(static_cast<size_t>(info.class_count));
    int64_t sink = 0;
    const auto t2 = std::chrono::steady_clock::now();
    for (int q = 0; q < 1000; ++q) {
        const uint64_t hide = static_cast<uint64_t>(q) & 0xFFFFF;
        sink += co_mask_ranges(p, &hide, 1, nullptr, 0, 0, r.data(), info.class_count);
    }
    const auto t3 = std::chrono::steady_clock::now();
    CHECK(sink > 0);
    const double build_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    const double query_us = std::chrono::duration<double, std::micro>(t3 - t2).count() / 1000.0;
    std::printf("timing: %lld triangles, %d groups, %d segments, %d signatures, %d classes: build %.2f ms, "
                "query %.2f us (avg %.1f ranges)\n",
                static_cast<long long>(info.triangle_count), info.group_count, info.segment_count,
                info.signature_count, info.class_count, build_ms, query_us, static_cast<double>(sink) / 1000.0);
    // Spot-check a few queries against the reference (the full reference is cheap enough here).
    const Reference ref(m, g);
    std::vector<char> keep(20, 0);
    for (uint64_t hide_bits : {uint64_t{0}, uint64_t{1}, uint64_t{0x5A5A5}, uint64_t{0xFFFFF}}) {
        std::vector<char> hide(20, 0);
        for (int k = 0; k < 20; ++k) el(hide, k) = ((hide_bits >> k) & 1u) != 0 ? 1 : 0;
        check_query(p, m, ref, hide, keep, 0);
    }
    co_mask_plan_destroy(p);
}

}  // namespace

int main() {
    test_garments();
    test_random();
    test_many_groups();
    test_empty_cases();
    test_from_doc();
    test_errors();
    test_timing();
    if (failures != 0) {
        std::fprintf(stderr, "mask: %d failure(s)\n", failures);
        return 1;
    }
    std::printf("mask: OK\n");
    return 0;
}
