// ctest voxel_world: the cv_* core (include/cenda/voxel_world.h) and its engine-internal API
// (src/voxel_world/cv_internal.hpp). Plain checks, brute-force references, fuzzed decoders.
#include "cenda/voxel_world.h"
#include "cv_internal.hpp"
#include "cv_section.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <random>
#include <set>
#include <thread>
#include <tuple>
#include <vector>
#include <memory>
#include <utility>

using namespace cenda::voxel;

namespace {

int g_failures = 0;
int g_checks = 0;

#define CHECK(cond)                                                                            \
    do {                                                                                       \
        ++g_checks;                                                                            \
        if (!(cond)) {                                                                         \
            ++g_failures;                                                                      \
            if (g_failures < 40) std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);   \
        }                                                                                      \
    } while (0)

constexpr uint16_t AIR = 0;
constexpr uint16_t STONE = 1;   // cube, opaque, sky-opaque
constexpr uint16_t GLASS = 2;   // transparent, not sky-opaque
constexpr uint16_t WATER = 3;   // translucent, not sky-opaque
constexpr uint16_t LEAVES = 4;  // transparent, sky-opaque (no OPAQUE)

const uint16_t kFlags[] = {
    0,
    CV_BLOCK_CUBE | CV_BLOCK_OPAQUE | CV_BLOCK_SKY_OPAQUE | CV_BLOCK_VIS_OPAQUE,
    CV_BLOCK_CUBE | CV_BLOCK_TRANSPARENT,
    CV_BLOCK_TRANSLUCENT | CV_BLOCK_TRANSPARENT,
    CV_BLOCK_CUBE | CV_BLOCK_TRANSPARENT | CV_BLOCK_SKY_OPAQUE,
};
uint16_t flag_of(uint16_t id) { return id < 5 ? kFlags[id] : uint16_t{0}; }

cv_world* make_world(int64_t min_y, int64_t max_y, cv_surface_fn fn = nullptr, void* user = nullptr) {
    cv_world_desc d{};
    d.min_y = min_y;
    d.max_y = max_y;
    d.surface = fn;
    d.user = user;
    d.air_id = AIR;
    cv_world* w = cv_world_create(&d);
    cv_block_table_set(w, kFlags, 5);
    return w;
}

std::vector<int64_t> drain(cv_world* w) {
    std::vector<int64_t> out(3 * 4096);
    int64_t n = cv_drain_dirty(w, out.data(), 4096);
    if (CV_IS_NEED(n)) {
        out.resize(static_cast<size_t>(3 * CV_NEEDED(n)));
        n = cv_drain_dirty(w, out.data(), CV_NEEDED(n));
    }
    out.resize(static_cast<size_t>(3 * std::max<int64_t>(n, 0)));
    return out;
}

std::set<std::tuple<int64_t, int64_t, int64_t>> drain_set(cv_world* w) {
    auto v = drain(w);
    std::set<std::tuple<int64_t, int64_t, int64_t>> s;
    for (size_t i = 0; i + 2 < v.size(); i += 3) s.emplace(v[i], v[i + 1], v[i + 2]);
    return s;
}

// 32-bit draws so `rng() % n` stays uint32_t under -Wconversion.
struct Rng {
    std::mt19937 g;
    explicit Rng(unsigned seed) : g(seed) {}
    uint32_t operator()() { return static_cast<uint32_t>(g()); }
};

size_t idx(int lx, int ly, int lz) { return static_cast<size_t>(CV_CELL_INDEX(lx, ly, lz)); }

// ---- sections ------------------------------------------------------------------------------------------

void test_section_tiers() {
    Section s(7);
    CHECK(s.bits() == 0 && s.heap_bytes() == 0);
    s.set(5, 9);
    CHECK(s.bits() == 4 && s.get(5) == 9 && s.get(6) == 7);
    for (uint16_t v = 0; v < 16; ++v) s.set(100u + v, static_cast<uint16_t>(1000 + v));   // 18 distinct -> 8-bit
    CHECK(s.bits() == 8);
    for (uint16_t v = 0; v < 16; ++v) CHECK(s.get(100u + v) == 1000 + v);
    for (uint32_t i = 0; i < 300; ++i) s.set(1000 + i, static_cast<uint16_t>(5000 + i));    // > 256 -> direct
    CHECK(s.bits() == 16);
    CHECK(s.get(1299) == 5299 && s.get(5) == 9 && s.get(0) == 7);
    CHECK(s.count_not(7) == 1 + 16 + 300);
    // Overwrite everything back to 7 -> compact collapses to uniform.
    for (uint32_t i = 0; i < kSectionCells; ++i) s.set(i, 7);
    uint16_t u = 0;
    CHECK(s.uniform(&u) && u == 7);
    s.compact();
    CHECK(s.bits() == 0 && s.uniform_value() == 7);

    // Palette slot reuse under churn: never leaves 4-bit while <= 16 values are live.
    Section t(0);
    Rng rng(1);
    for (int k = 0; k < 20000; ++k) {
        const uint32_t i = rng() % kSectionCells;
        t.set(i, static_cast<uint16_t>(rng() % 12));
    }
    CHECK(t.bits() == 4);
    // Collapse to one value drops the array immediately.
    for (uint32_t i = 0; i < kSectionCells; ++i) t.set(i, 3);
    CHECK(t.bits() == 0 && t.uniform_value() == 3);

    // assign picks the narrowest tier, read round-trips.
    std::vector<uint16_t> v(kSectionCells);
    for (uint32_t i = 0; i < kSectionCells; ++i) v[i] = static_cast<uint16_t>(i % 200);
    Section a;
    a.assign(v.data());
    CHECK(a.bits() == 8);
    std::vector<uint16_t> back(kSectionCells);
    a.read(back.data());
    CHECK(back == v);
    uint16_t row[16];
    a.read_row(3, row);
    CHECK(std::equal(row, row + 16, v.begin() + 48));
    // 8-bit with few live values narrows on compact
    for (uint32_t i = 0; i < kSectionCells; ++i) a.set(i, static_cast<uint16_t>(i % 3));
    CHECK(a.bits() == 8 && a.compactable());
    a.compact();
    CHECK(a.bits() == 4);
    for (uint32_t i = 0; i < kSectionCells; ++i) CHECK(a.get(i) == i % 3);
}

// ---- blocks, bounds, coordinates ---------------------------------------------------------------------

void test_get_set_coordinates() {
    cv_world* w = make_world(CV_UNBOUNDED_MIN, CV_UNBOUNDED_MAX);
    const int64_t big = int64_t{1} << 62;
    const int64_t coords[][3] = {{0, 0, 0}, {-1, -1, -1}, {big, -big, big - 1}, {-big, big - 1, -big},
                                 {INT64_MAX - 3, INT64_MIN + 5, 12345}, {-33, 64, -65}};
    for (const auto& c : coords) {
        const int64_t cx = c[0] >> 5, cy = c[1] >> 5, cz = c[2] >> 5;
        CHECK(cv_get(w, c[0], c[1], c[2]) == CV_ERR_MISSING);
        CHECK(cv_cube_create(w, cx, cy, cz, AIR) == CV_OK);
        CHECK(cv_cube_create(w, cx, cy, cz, AIR) == CV_ERR_EXISTS);
        CHECK(cv_get(w, c[0], c[1], c[2]) == AIR);
        CHECK(cv_set(w, c[0], c[1], c[2], STONE) == AIR);
        CHECK(cv_get(w, c[0], c[1], c[2]) == STONE);
        CHECK(cv_set(w, c[0], c[1], c[2], GLASS) == STONE);
    }
    CHECK(cv_cube_count(w) == 6);
    for (const auto& c : coords) CHECK(cv_get(w, c[0], c[1], c[2]) == GLASS);
    // neighbouring cells of the far cubes stay air (no aliasing)
    CHECK(cv_get(w, big + 1, -big, big - 1) == AIR);
    std::vector<int64_t> list(3 * 16);
    CHECK(cv_cube_list(w, list.data(), 2) == CV_NEED(6));
    CHECK(cv_cube_list(w, list.data(), 16) == 6);
    CHECK(cv_cube_remove(w, big >> 5, (-big) >> 5, (big - 1) >> 5) == CV_OK);
    CHECK(cv_cube_remove(w, big >> 5, (-big) >> 5, (big - 1) >> 5) == CV_ERR_MISSING);
    CHECK(cv_cube_count(w) == 5);
    // A cube coordinate past the shifted int64 range is an argument error, not an overflow.
    CHECK(cv_cube_create(w, INT64_MAX, 0, 0, AIR) == CV_ERR_ARG);
    cv_world_destroy(w);

    // Bounded world
    cv_world* b = make_world(-40, 70);
    CHECK(cv_cube_create(b, 0, -3, 0, STONE) == CV_ERR_ARG);  // [-96, -64): wholly below
    CHECK(cv_cube_create(b, 0, 3, 0, STONE) == CV_ERR_ARG);   // [96, 128): wholly above
    CHECK(cv_cube_create(b, 0, -2, 0, STONE) == CV_OK);       // [-64, -32): partially inside
    CHECK(cv_get(b, 0, -41, 0) == AIR);                        // outside reads air (stored air too)
    CHECK(cv_get(b, 0, -40, 0) == STONE);
    CHECK(cv_get(b, 0, -1000, 0) == AIR);
    CHECK(cv_set(b, 0, -41, 0, STONE) == CV_ERR_ARG);
    CHECK(cv_set(b, 0, 70, 0, STONE) == CV_ERR_ARG);
    std::vector<uint16_t> ids(CV_CUBE_CELLS);
    CHECK(cv_cube_read_ids(b, 0, -2, 0, ids.data()) == CV_OK);
    CHECK(ids[idx(0, 0, 0)] == AIR && ids[idx(0, 23, 0)] == AIR && ids[idx(0, 24, 0)] == STONE);
    uint16_t u = 99;
    CHECK(cv_cube_uniform(b, 0, -2, 0, &u) == 0);
    cv_world_destroy(b);
}

void test_read_box() {
    cv_world* w = make_world(-64, 64);
    Rng rng(7);
    std::vector<uint16_t> ids(CV_CUBE_CELLS);
    for (auto& v : ids) v = static_cast<uint16_t>(rng() % 5);
    CHECK(cv_cube_write_ids(w, 0, 0, 0, ids.data()) == CV_OK);
    CHECK(cv_cube_create(w, -1, 0, 0, STONE) == CV_OK);
    // box spans cubes x -1..1, y -1..0 (y -1 cubes missing; no callback, no column record there... the
    // column (0,0) and (-1,0) exist, so cells at or above their TOP are known air)
    const int32_t sx = 70, sy = 40, sz = 5;
    std::vector<uint16_t> out(static_cast<size_t>(sx * sy * sz));
    const int64_t x0 = -20, y0 = -10, z0 = 3;
    const int64_t missing = cv_read_box(w, x0, y0, z0, sx, sy, sz, out.data(), 777);
    int64_t expect_missing = 0;
    bool same = true;
    for (int y = 0; y < sy; ++y) {
        for (int z = 0; z < sz; ++z) {
            for (int x = 0; x < sx; ++x) {
                const int32_t g = cv_get(w, x0 + x, y0 + y, z0 + z);
                const uint16_t got = out[static_cast<size_t>((y * sz + z) * sx + x)];
                if (g == CV_ERR_MISSING) {
                    ++expect_missing;
                    same = same && got == 777;
                } else {
                    same = same && got == g;
                }
            }
        }
    }
    CHECK(same);
    CHECK(missing == expect_missing);
    CHECK(missing > 0);
    CHECK(cv_read_box(w, INT64_MAX - 2, 0, 0, 5, 1, 1, out.data(), 0) == CV_ERR_ARG);
    cv_world_destroy(w);
}

// ---- masks -------------------------------------------------------------------------------------------

bool masks_match(cv_world* w, int64_t cx, int64_t cy, int64_t cz) {
    std::vector<uint16_t> ids(CV_CUBE_CELLS);
    cv_cube_read_ids(w, cx, cy, cz, ids.data());
    CubeRead r(w, cx, cy, cz);
    if (!r) return false;
    for (int ly = 0; ly < 32; ++ly) {
        for (int lz = 0; lz < 32; ++lz) {
            uint32_t o = 0, p = 0;
            for (int lx = 0; lx < 32; ++lx) {
                const uint16_t id = ids[idx(lx, ly, lz)];
                if (id != AIR) o |= 1u << lx;
                if ((flag_of(id) & CV_BLOCK_OPAQUE) != 0) p |= 1u << lx;
            }
            if (r.occupancy_row(ly, lz) != o || r.opacity_row(ly, lz) != p) return false;
        }
    }
    return true;
}

void test_masks() {
    cv_world* w = make_world(CV_UNBOUNDED_MIN, CV_UNBOUNDED_MAX);
    Rng rng(3);
    std::vector<uint16_t> ids(CV_CUBE_CELLS);
    for (auto& v : ids) v = static_cast<uint16_t>(rng() % 3 == 0 ? STONE : AIR);
    cv_cube_write_ids(w, 2, -3, 4, ids.data());
    CHECK(masks_match(w, 2, -3, 4));
    for (int k = 0; k < 5000; ++k) {
        const int64_t x = 64 + int64_t{rng() % 32}, y = -96 + int64_t{rng() % 32}, z = 128 + int64_t{rng() % 32};
        cv_set(w, x, y, z, static_cast<uint16_t>(rng() % 5));
    }
    CHECK(masks_match(w, 2, -3, 4));
    // uniform cubes: masks with no arrays
    cv_cube_create(w, 0, 0, 0, STONE);
    CHECK(masks_match(w, 0, 0, 0));
    cv_set(w, 3, 3, 3, AIR);
    CHECK(masks_match(w, 0, 0, 0));
    cv_set(w, 3, 3, 3, STONE);   // back to uniform
    uint16_t u = 0;
    CHECK(cv_cube_uniform(w, 0, 0, 0, &u) == 1 && u == STONE);
    CHECK(masks_match(w, 0, 0, 0));
    // table change rebuilds masks: make GLASS opaque
    uint16_t f2[5];
    std::memcpy(f2, kFlags, sizeof f2);
    f2[GLASS] = static_cast<uint16_t>(f2[GLASS] | CV_BLOCK_OPAQUE);
    cv_set(w, 1, 1, 1, GLASS);
    cv_block_table_set(w, f2, 5);
    {
        CubeRead r(w, 0, 0, 0);
        CHECK((r.opacity_row(1, 1) & 2u) != 0);
    }
    cv_block_table_set(w, kFlags, 5);
    CHECK(masks_match(w, 0, 0, 0));
    cv_world_destroy(w);
}

// ---- encode / decode ---------------------------------------------------------------------------------

std::vector<uint8_t> encode(cv_world* w, int64_t cx, int64_t cy, int64_t cz, uint32_t parts) {
    std::vector<uint8_t> b(16);
    int64_t n = cv_cube_encode(w, cx, cy, cz, parts, b.data(), static_cast<int64_t>(b.size()));
    if (CV_IS_NEED(n)) {
        b.resize(static_cast<size_t>(CV_NEEDED(n)));
        n = cv_cube_encode(w, cx, cy, cz, parts, b.data(), static_cast<int64_t>(b.size()));
    }
    b.resize(n > 0 ? static_cast<size_t>(n) : 0);
    return b;
}

void test_encode_decode() {
    cv_world* w = make_world(CV_UNBOUNDED_MIN, CV_UNBOUNDED_MAX);
    cv_layer_define(w, 0, CV_LAYER_SAVED | CV_LAYER_NETWORK | CV_LAYER_MESHED);
    cv_layer_define(w, 1, CV_LAYER_SAVED);
    cv_cube_create(w, 0, 0, 0, AIR);
    auto air = encode(w, 0, 0, 0, CV_PARTS_SAVED);
    CHECK(air.size() == 1 + 4 + 8 * 3);   // version, parts, 8 SINGLE sections
    Rng rng(11);
    std::vector<uint16_t> ids(CV_CUBE_CELLS);
    // section 0: 2 ids, 1: 5 ids, 2: 17, 3: 200, 4: 300 (direct), rest uniform
    for (int ly = 0; ly < 32; ++ly) {
        for (int lz = 0; lz < 32; ++lz) {
            for (int lx = 0; lx < 32; ++lx) {
                const int s = ((ly >> 4) * 2 + (lz >> 4)) * 2 + (lx >> 4);
                const uint32_t k[] = {2, 5, 17, 200, 300, 1, 1, 1};
                ids[idx(lx, ly, lz)] = static_cast<uint16_t>(k[s] == 1 ? 40u + static_cast<uint32_t>(s) : 100u + rng() % k[s]);
            }
        }
    }
    cv_cube_write_ids(w, 1, 2, 3, ids.data());
    for (int i = 0; i < 300; ++i) cv_layer_set(w, 0, 32 + rng() % 32, 64 + rng() % 32, 96 + rng() % 32, static_cast<uint16_t>(1 + rng() % 30));
    cv_layer_set(w, 1, 33, 65, 97, 4242);
    const uint8_t blob[] = {1, 2, 3, 4, 5, 250};
    cv_cube_set_blob(w, 1, 2, 3, blob, sizeof blob);
    const auto saved = encode(w, 1, 2, 3, CV_PARTS_SAVED);
    const auto net = encode(w, 1, 2, 3, CV_PARTS_NETWORK);
    CHECK(saved.size() > net.size());
    CHECK(encode(w, 1, 2, 3, CV_PARTS_SAVED) == saved);   // deterministic
    std::vector<uint8_t> tiny(4);
    CHECK(CV_IS_NEED(cv_cube_encode(w, 1, 2, 3, CV_PARTS_SAVED, tiny.data(), 4)));
    CHECK(cv_cube_encode(w, 1, 2, 3, 0x40u, tiny.data(), 4) == CV_ERR_ARG);

    // decode into another world, compare everything
    cv_world* v = make_world(CV_UNBOUNDED_MIN, CV_UNBOUNDED_MAX);
    cv_layer_define(v, 0, CV_LAYER_SAVED | CV_LAYER_NETWORK | CV_LAYER_MESHED);
    cv_layer_define(v, 1, CV_LAYER_SAVED);
    CHECK(cv_cube_decode(v, 1, 2, 3, saved.data(), static_cast<int64_t>(saved.size())) == CV_OK);
    std::vector<uint16_t> a(CV_CUBE_CELLS), b(CV_CUBE_CELLS);
    cv_cube_read_ids(w, 1, 2, 3, a.data());
    cv_cube_read_ids(v, 1, 2, 3, b.data());
    CHECK(a == b);
    for (int l = 0; l < 2; ++l) {
        cv_layer_read_cube(w, l, 1, 2, 3, a.data());
        cv_layer_read_cube(v, l, 1, 2, 3, b.data());
        CHECK(a == b);
    }
    uint8_t got[16];
    CHECK(cv_cube_get_blob(v, 1, 2, 3, got, 16) == 6 && std::memcmp(got, blob, 6) == 0);
    CHECK(masks_match(v, 1, 2, 3));
    CHECK(encode(v, 1, 2, 3, CV_PARTS_SAVED) == saved);
    // network decode resets the saved-only layer and the blob
    CHECK(cv_cube_decode(v, 1, 2, 3, net.data(), static_cast<int64_t>(net.size())) == CV_OK);
    CHECK(cv_layer_get(v, 1, 33, 65, 97) == 0);
    CHECK(cv_layer_get(v, 0, 33, 65, 97) == cv_layer_get(w, 0, 33, 65, 97));
    CHECK(cv_cube_get_blob(v, 1, 2, 3, got, 16) == 0);

    // fuzz: mutations of valid encodings and pure garbage never crash; decode is OK or CORRUPT
    int ok = 0, corrupt = 0, other = 0;
    std::vector<uint8_t> m;
    for (int it = 0; it < 30000; ++it) {
        const auto& base = (it % 3 == 0) ? air : saved;
        m = base;
        const int kind = static_cast<int>(rng() % 4);
        if (kind == 0) {
            m.resize(rng() % (m.size() + 1));
        } else if (kind == 1) {
            for (int f = 0; f < 1 + static_cast<int>(rng() % 4); ++f) m[rng() % m.size()] ^= static_cast<uint8_t>(1u << (rng() % 8));
        } else if (kind == 2) {
            m.resize(rng() % 200);
            for (auto& x : m) x = static_cast<uint8_t>(rng());
        } else {
            m.insert(m.begin() + static_cast<ptrdiff_t>(rng() % m.size()), static_cast<uint8_t>(rng()));
        }
        const int32_t r = cv_cube_decode(v, 5, 5, 5, m.data(), static_cast<int64_t>(m.size()));
        if (r == CV_OK) {
            ++ok;
            const auto re = encode(v, 5, 5, 5, CV_PARTS_SAVED);
            CHECK(cv_cube_decode(v, 6, 5, 5, re.data(), static_cast<int64_t>(re.size())) == CV_OK);
            CHECK(encode(v, 6, 5, 5, CV_PARTS_SAVED) == re);
        } else if (r == CV_ERR_CORRUPT) {
            ++corrupt;
        } else {
            ++other;
        }
    }
    CHECK(other == 0);
    CHECK(corrupt > 1000 && ok > 10);
    std::printf("  decode fuzz: %d ok, %d corrupt\n", ok, corrupt);
    cv_world_destroy(v);
    cv_world_destroy(w);
}

// ---- layers + blob -------------------------------------------------------------------------------------

void test_layers() {
    cv_world* w = make_world(-64, 64);
    cv_layer_define(w, 2, CV_LAYER_MESHED);
    cv_layer_define(w, 3, 0);
    cv_cube_create(w, 0, 0, 0, STONE);
    cv_cube_create(w, 1, 0, 0, STONE);
    drain(w);
    CHECK(cv_layer_get(w, 2, 5, 5, 5) == 0);
    CHECK(cv_layer_set(w, 2, 31, 5, 5, 9) == 0);
    CHECK(cv_layer_get(w, 2, 31, 5, 5) == 9);
    CHECK(cv_layer_get(w, 2, 31, 500, 5) == 0);
    CHECK(cv_layer_set(w, 2, 31, 500, 5, 1) == CV_ERR_ARG);
    CHECK(cv_layer_set(w, 9, 0, 0, 0, 1) == CV_ERR_ARG);
    auto d = drain_set(w);   // MESHED: the cube + the +x neighbour whose padding holds x=31
    CHECK(d.size() == 2 && d.count({0, 0, 0}) == 1 && d.count({1, 0, 0}) == 1);
    cv_layer_set(w, 3, 2, 2, 2, 5);   // not meshed: no dirt
    CHECK(drain(w).empty());
    std::vector<uint16_t> idx16(8), val(8);
    CHECK(cv_layer_scan(w, 2, 0, 0, 0, idx16.data(), val.data(), 8) == 1);
    CHECK(idx16[0] == CV_CELL_INDEX(31, 5, 5) && val[0] == 9);
    for (int i = 0; i < 20; ++i) cv_layer_set(w, 2, i, 0, 0, static_cast<uint16_t>(i + 1));
    CHECK(cv_layer_scan(w, 2, 0, 0, 0, idx16.data(), val.data(), 8) == CV_NEED(21));
    CHECK(cv_layer_scan(w, 2, 1, 0, 0, idx16.data(), val.data(), 8) == 0);
    std::vector<uint16_t> all(CV_CUBE_CELLS, 3);
    CHECK(cv_layer_write_cube(w, 3, 1, 0, 0, all.data()) == CV_OK);
    CHECK(cv_layer_get(w, 3, 40, 3, 3) == 3);
    std::fill(all.begin(), all.end(), uint16_t{0});
    cv_layer_write_cube(w, 3, 1, 0, 0, all.data());
    // blocks write resets layers
    std::vector<uint16_t> ids(CV_CUBE_CELLS, STONE);
    cv_cube_write_ids(w, 0, 0, 0, ids.data());
    CHECK(cv_layer_get(w, 2, 31, 5, 5) == 0);
    // empty layers + blob cost nothing
    CHECK(cube_heap_bytes(w, 1, 0, 0) == cube_heap_bytes(w, 0, 0, 0));
    // blob
    uint8_t buf[4];
    const uint8_t data[] = {9, 8, 7, 6, 5};
    CHECK(cv_cube_set_blob(w, 0, 0, 0, data, 5) == CV_OK);
    CHECK(cv_cube_get_blob(w, 0, 0, 0, buf, 4) == CV_NEED(5));
    CHECK(cv_cube_set_blob(w, 0, 0, 0, nullptr, 0) == CV_OK);
    CHECK(cv_cube_get_blob(w, 0, 0, 0, buf, 4) == 0);
    CHECK(cv_cube_set_blob(w, 7, 7, 7, data, 5) == CV_ERR_MISSING);
    cv_world_destroy(w);
}

// ---- dirty set --------------------------------------------------------------------------------------------

void test_dirty_exact() {
    cv_world* w = make_world(CV_UNBOUNDED_MIN, CV_UNBOUNDED_MAX);
    for (int64_t y = -1; y <= 1; ++y)
        for (int64_t z = -1; z <= 1; ++z)
            for (int64_t x = -1; x <= 1; ++x) cv_cube_create(w, x, y, z, AIR);
    drain(w);
    // GLASS is neither sky-opaque nor... it is non-air, so only TOP changes (TOP never dirties).
    const int cells[][3] = {{5, 5, 5}, {0, 5, 5}, {31, 5, 5}, {0, 0, 5}, {31, 31, 0}, {0, 0, 0}, {31, 31, 31}};
    for (const auto& c : cells) {
        cv_set(w, c[0], c[1], c[2], GLASS);
        auto d = drain_set(w);
        std::set<std::tuple<int64_t, int64_t, int64_t>> expect;
        for (int dy = -1; dy <= 1; ++dy)
            for (int dz = -1; dz <= 1; ++dz)
                for (int dx = -1; dx <= 1; ++dx) {
                    auto touches = [](int off, int l) { return off == 0 || (off < 0 && l == 0) || (off > 0 && l == 31); };
                    if (touches(dx, c[0]) && touches(dy, c[1]) && touches(dz, c[2])) expect.emplace(dx, dy, dz);
                }
        CHECK(d == expect);
        cv_set(w, c[0], c[1], c[2], GLASS);   // no change: no dirt
        CHECK(drain(w).empty());
    }
    // bulk write: cube + 26
    std::vector<uint16_t> ids(CV_CUBE_CELLS, GLASS);
    cv_cube_write_ids(w, 0, 0, 0, ids.data());
    CHECK(drain_set(w).size() == 27);
    // removal: the 26 neighbours (their padding changed), not itself
    cv_cube_remove(w, 0, 0, 0);
    auto d = drain_set(w);
    CHECK(d.size() == 26 && d.count({0, 0, 0}) == 0);
    // NEED protocol leaves the set alone
    cv_cube_create(w, 0, 0, 0, AIR);
    int64_t tmp[3];
    CHECK(cv_drain_dirty(w, tmp, 1) == CV_NEED(27));
    CHECK(drain(w).size() == 27 * 3);
    cv_world_destroy(w);

    // sky change: cubes below in the column + horizontal neighbours' columns at the edge
    cv_world* s = make_world(0, 128);
    for (int64_t y = 0; y < 4; ++y)
        for (int64_t x = -1; x <= 1; ++x) cv_cube_create(s, x, y, 0, AIR);
    drain(s);
    cv_set(s, 5, 100, 5, STONE);   // surface 0 -> 101 at (5,5): cubes 0..3 of column 0
    auto e = drain_set(s);
    CHECK(e.size() == 4);
    for (int64_t y = 0; y < 4; ++y) CHECK(e.count({0, y, 0}) == 1);
    cv_set(s, 0, 40, 5, STONE);    // lx 0: also column -1's cubes 0..1 (corner range 0..41)
    e = drain_set(s);
    CHECK(e.count({-1, 0, 0}) == 1 && e.count({-1, 1, 0}) == 1 && e.count({-1, 2, 0}) == 0);
    CHECK(e.count({1, 0, 0}) == 0 && e.count({0, 0, 0}) == 1 && e.count({0, 1, 0}) == 1);
    cv_world_destroy(s);
}

// ---- surface tracker vs brute force ---------------------------------------------------------------------

struct Gen {
    bool known = true;
    int64_t sky(int64_t x, int64_t z) const { return 20 + ((x * 7 + z * 13) % 23 + 23) % 23; }
    int64_t top(int64_t x, int64_t z) const { return std::max<int64_t>(sky(x, z), 33); }
};

int32_t gen_surface(void* user, int64_t cx, int64_t cz, int64_t* out_sky, int64_t* out_top) {
    const Gen* g = static_cast<const Gen*>(user);
    if (!g->known) return 0;
    for (int lz = 0; lz < 32; ++lz)
        for (int lx = 0; lx < 32; ++lx) {
            out_sky[lz * 32 + lx] = g->sky(cx * 32 + lx, cz * 32 + lz);
            out_top[lz * 32 + lx] = g->top(cx * 32 + lx, cz * 32 + lz);
        }
    return 1;
}

struct Model {
    int64_t min_y, max_y;
    const Gen* gen;
    std::map<std::tuple<int64_t, int64_t, int64_t>, std::vector<uint16_t>> generated;   // last known content

    // Brute force: a never-generated cell is solid iff y < the callback value (when known).
    int64_t answer(int64_t x, int64_t z, bool sky) const {
        const int64_t cx = x >> 5, cz = z >> 5;
        for (int64_t y = max_y - 1; y >= min_y; --y) {
            auto it = generated.find({cx, y >> 5, cz});
            bool solid;
            if (it != generated.end()) {
                const uint16_t id = it->second[idx(static_cast<int>(x & 31), static_cast<int>(y & 31), static_cast<int>(z & 31))];
                solid = sky ? (flag_of(id) & CV_BLOCK_SKY_OPAQUE) != 0 : id != AIR;
            } else {
                solid = gen != nullptr && gen->known && y < (sky ? gen->sky(x, z) : gen->top(x, z));
            }
            if (solid) return y + 1;
        }
        return min_y;
    }
};

int compare_columns(cv_world* w, const Model& m, int64_t cx0, int64_t cx1, int64_t cz0, int64_t cz1) {
    int bad = 0;
    for (int64_t cx = cx0; cx <= cx1; ++cx)
        for (int64_t cz = cz0; cz <= cz1; ++cz)
            for (int lz = 0; lz < 32; ++lz)
                for (int lx = 0; lx < 32; ++lx) {
                    const int64_t x = cx * 32 + lx, z = cz * 32 + lz;
                    int64_t s = 0, t = 0;
                    cv_surface_y(w, x, z, &s);
                    cv_top_y(w, x, z, &t);
                    if (s != m.answer(x, z, true) || t != m.answer(x, z, false)) {
                        if (bad < 3) std::printf("  mismatch (%lld,%lld): sky %lld vs %lld, top %lld vs %lld\n",
                                                 static_cast<long long>(x), static_cast<long long>(z),
                                                 static_cast<long long>(s), static_cast<long long>(m.answer(x, z, true)),
                                                 static_cast<long long>(t), static_cast<long long>(m.answer(x, z, false)));
                        ++bad;
                    }
                }
    return bad;
}

void test_surface_tracker(bool known) {
    Gen gen;
    gen.known = known;
    const int64_t min_y = -128, max_y = 128;   // cubes -4..3
    cv_world* w = make_world(min_y, max_y, gen_surface, &gen);
    Model m{min_y, max_y, &gen, {}};
    Rng rng(known ? 21u : 22u);
    std::vector<uint16_t> ids(CV_CUBE_CELLS);
    auto random_cube = [&](int64_t cy) {
        // terrain-ish: stone below a height field, some glass/leaves/water, caves
        const int64_t h = cy * 32 + static_cast<int64_t>(rng() % 48) - 8;
        for (int ly = 0; ly < 32; ++ly)
            for (int lz = 0; lz < 32; ++lz)
                for (int lx = 0; lx < 32; ++lx) {
                    const int64_t y = cy * 32 + ly;
                    uint16_t id = y < h + (lx * 3 + lz) % 7 ? STONE : AIR;
                    const uint32_t r = rng() % 100;
                    if (r < 3) id = AIR;
                    else if (r < 5) id = GLASS;
                    else if (r < 7) id = LEAVES;
                    else if (r < 9) id = WATER;
                    ids[idx(lx, ly, lz)] = id;
                }
    };
    for (int round = 0; round < 60; ++round) {
        const int64_t cx = static_cast<int64_t>(rng() % 3) - 1, cz = static_cast<int64_t>(rng() % 2);
        const int64_t cy = static_cast<int64_t>(rng() % 8) - 4;
        const uint32_t op = rng() % 10;
        if (op < 4) {
            random_cube(cy);
            if (rng() % 4 == 0) std::fill(ids.begin(), ids.end(), rng() % 2 ? STONE : AIR);
            cv_cube_write_ids(w, cx, cy, cz, ids.data());
            m.generated[{cx, cy, cz}] = ids;
        } else if (op < 8) {
            auto it = m.generated.find({cx, cy, cz});
            if (it == m.generated.end() || !cv_cube_exists(w, cx, cy, cz)) continue;
            for (int k = 0; k < 400; ++k) {
                const int lx = static_cast<int>(rng() % 32), ly = static_cast<int>(rng() % 32), lz = static_cast<int>(rng() % 32);
                const auto id = static_cast<uint16_t>(rng() % 5);
                cv_set(w, cx * 32 + lx, cy * 32 + ly, cz * 32 + lz, id);
                it->second[idx(lx, ly, lz)] = id;
            }
        } else {
            cv_cube_remove(w, cx, cy, cz);   // the column remembers
        }
    }
    CHECK(compare_columns(w, m, -2, 2, -1, 2) == 0);

    // top range = min/max of the answers; sky_min too
    int64_t tmin = 0, tmax = 0, smin = 0;
    CHECK(cv_top_range(w, 0, 0, &tmin, &tmax, &smin) == CV_OK);
    int64_t emin = INT64_MAX, emax = INT64_MIN, esmin = INT64_MAX;
    for (int lz = 0; lz < 32; ++lz)
        for (int lx = 0; lx < 32; ++lx) {
            emin = std::min(emin, m.answer(lx, lz, false));
            emax = std::max(emax, m.answer(lx, lz, false));
            esmin = std::min(esmin, m.answer(lx, lz, true));
        }
    CHECK(tmin == emin && tmax == emax && smin == esmin);

    // column records: encode, drop everything, decode into a fresh world -> same answers
    cv_world* v = make_world(min_y, max_y, gen_surface, &gen);
    for (int64_t cx = -1; cx <= 1; ++cx)
        for (int64_t cz = 0; cz <= 1; ++cz) {
            std::vector<uint8_t> rec(64);
            int64_t n = cv_column_encode(w, cx, cz, rec.data(), 64);
            if (n == CV_ERR_MISSING) continue;
            if (CV_IS_NEED(n)) {
                rec.resize(static_cast<size_t>(CV_NEEDED(n)));
                n = cv_column_encode(w, cx, cz, rec.data(), static_cast<int64_t>(rec.size()));
            }
            CHECK(n > 0);
            rec.resize(static_cast<size_t>(n));
            CHECK(cv_column_decode(v, cx, cz, rec.data(), n) == CV_OK);
            // re-encode is identical
            std::vector<uint8_t> again(rec.size() + 16);
            CHECK(cv_column_encode(v, cx, cz, again.data(), static_cast<int64_t>(again.size())) == n);
            again.resize(static_cast<size_t>(n));
            CHECK(again == rec);
            // fuzz the record
            int corrupt = 0, other = 0;
            for (int it = 0; it < 3000; ++it) {
                std::vector<uint8_t> mut = rec;
                const uint32_t kind = rng() % 3;
                if (kind == 0) mut.resize(rng() % (mut.size() + 1));
                else if (kind == 1) mut[rng() % mut.size()] ^= static_cast<uint8_t>(1u << (rng() % 8));
                else for (auto& b : mut) b = static_cast<uint8_t>(rng());
                const int32_t r = cv_column_decode(v, 50, 50, mut.data(), static_cast<int64_t>(mut.size()));
                if (r == CV_ERR_CORRUPT) ++corrupt;
                else if (r != CV_OK) ++other;
            }
            CHECK(other == 0 && corrupt > 0);
            cv_column_remove(v, 50, 50);
        }
    CHECK(compare_columns(v, m, -2, 2, -1, 2) == 0);
    // unloading: remove every cube of the original, columns keep answering, then forget them
    std::vector<int64_t> list(3 * 512);
    const int64_t n = cv_cube_list(w, list.data(), 512);
    for (int64_t i = 0; i < n; ++i) cv_cube_remove(w, list[static_cast<size_t>(3 * i)], list[static_cast<size_t>(3 * i + 1)], list[static_cast<size_t>(3 * i + 2)]);
    CHECK(compare_columns(w, m, -2, 2, -1, 2) == 0);
    cv_cube_create(w, 0, 0, 0, AIR);
    CHECK(cv_column_remove(w, 0, 0) == CV_ERR_EXISTS);
    cv_cube_remove(w, 0, 0, 0);
    CHECK(cv_column_remove(w, 0, 0) == CV_OK);
    CHECK(cv_column_remove(w, 0, 0) == CV_ERR_MISSING);
    int64_t y = 0;
    // forgotten column: falls back to the callback (never generated)
    CHECK(cv_surface_y(w, 3, 3, &y) == (known ? CV_OK : CV_ERR_MISSING));
    CHECK(y == (known ? gen.sky(3, 3) : min_y));
    cv_world_destroy(v);
    cv_world_destroy(w);
}

void test_surface_unbounded_and_callbackless() {
    cv_world* w = make_world(CV_UNBOUNDED_MIN, CV_UNBOUNDED_MAX);
    int64_t y = 7;
    CHECK(cv_surface_y(w, 1, 1, &y) == CV_ERR_MISSING && y == CV_UNBOUNDED_MIN);
    const int64_t far = int64_t{1} << 61;
    cv_cube_create(w, 0, far >> 5, 0, AIR);
    CHECK(cv_surface_y(w, 1, 1, &y) == CV_OK && y == CV_UNBOUNDED_MIN);
    cv_set(w, 1, far + 3, 1, STONE);
    CHECK(cv_surface_y(w, 1, 1, &y) == CV_OK && y == far + 4);
    cv_cube_create(w, 0, -(far >> 5), 0, STONE);
    CHECK(cv_surface_y(w, 2, 2, &y) == CV_OK && y == -far + 32);
    CHECK(cv_surface_y(w, 1, 1, &y) == CV_OK && y == far + 4);
    cv_set(w, 1, far + 3, 1, AIR);
    CHECK(cv_surface_y(w, 1, 1, &y) == CV_OK && y == -far + 32);
    cv_world_destroy(w);
}

// ---- known air ------------------------------------------------------------------------------------------

void test_known_air() {
    Gen gen;
    cv_world* w = make_world(-128, 128, gen_surface, &gen);
    // never-generated column: TOP from the callback (>= 33)
    CHECK(cv_get(w, 5, 100, 5) == AIR);
    CHECK(cv_get(w, 5, gen.top(5, 5), 5) == AIR);
    CHECK(cv_get(w, 5, gen.top(5, 5) - 1, 5) == CV_ERR_MISSING);
    CHECK(known_air(w, 5, 100, 5) && !known_air(w, 5, 0, 5));
    CHECK(cube_known_air(w, 0, 2, 0) && !cube_known_air(w, 0, 1, 0));
    // generated column: a pillar raises TOP at one cell
    cv_cube_create(w, 0, 1, 0, AIR);
    cv_cube_create(w, 0, 2, 0, AIR);
    cv_set(w, 5, 90, 5, GLASS);
    cv_cube_remove(w, 0, 2, 0);
    CHECK(cv_get(w, 5, 91, 5) == AIR);
    CHECK(cv_get(w, 5, 90, 5) == CV_ERR_MISSING);   // the removed cube's glass is unknown now
    CHECK(cv_get(w, 6, 90, 6) == AIR);
    std::vector<uint16_t> out(64);
    CHECK(cv_read_box(w, 5, 88, 5, 1, 4, 1, out.data(), 999) == 3);   // 88, 89, 90 unknown; 91 air
    CHECK(out[3] == AIR && out[0] == 999);
    // no callback, no column: nothing is known air
    cv_world* b = make_world(-128, 128);
    CHECK(cv_get(b, 5, 100, 5) == CV_ERR_MISSING);
    CHECK(cv_get(b, 5, 200, 5) == AIR);   // outside the bounds
    cv_world_destroy(b);
    cv_world_destroy(w);
}

// ---- padded neighbourhood + rings -------------------------------------------------------------------------

void test_read_padded() {
    Gen gen;
    cv_world* w = make_world(-128, 128, gen_surface, &gen);
    cv_layer_define(w, 1, CV_LAYER_MESHED);
    Rng rng(5);
    std::vector<uint16_t> ids(CV_CUBE_CELLS);
    for (int64_t y = -1; y <= 1; ++y)
        for (int64_t z = -1; z <= 1; ++z)
            for (int64_t x = -1; x <= 1; ++x) {
                if ((x + y + z) % 3 == 0 && !(x == 0 && y == 0 && z == 0)) continue;   // leave holes
                for (auto& v : ids) v = static_cast<uint16_t>(rng() % 5);
                cv_cube_write_ids(w, x, y, z, ids.data());
            }
    for (int i = 0; i < 500; ++i) cv_layer_set(w, 1, static_cast<int64_t>(rng() % 96) - 32, static_cast<int64_t>(rng() % 96) - 32,
                                               static_cast<int64_t>(rng() % 96) - 32, static_cast<uint16_t>(1 + rng() % 9));
    auto pc = std::make_unique<PaddedCube>();
    std::vector<uint16_t> lay(kPadCells);
    const int32_t layers[] = {1};
    uint16_t* const louts[] = {lay.data()};
    CHECK(read_padded(w, 0, 0, 0, *pc, layers, louts, 1) == CV_OK);
    bool ids_ok = true, masks_ok = true, layer_ok = true, unknown_ok = true;
    for (int py = 0; py < kPad; ++py)
        for (int pz = 0; pz < kPad; ++pz)
            for (int px = 0; px < kPad; ++px) {
                const int64_t x = px - 1, y = py - 1, z = pz - 1;
                const int32_t g = cv_get(w, x, y, z);
                const uint16_t expect = g < 0 ? AIR : static_cast<uint16_t>(g);
                const uint16_t got = pc->ids[pad_index(px, py, pz)];
                ids_ok = ids_ok && got == expect;
                const int r = py * kPad + pz;
                const bool occ = ((pc->occupancy[r] >> px) & 1u) != 0;
                const bool opq = ((pc->opacity[r] >> px) & 1u) != 0;
                masks_ok = masks_ok && occ == (expect != AIR) && opq == ((flag_of(expect) & CV_BLOCK_OPAQUE) != 0);
                const int32_t lg = cv_layer_get(w, 1, x, y, z);
                layer_ok = layer_ok && lay[static_cast<size_t>(pad_index(px, py, pz))] == (lg < 0 ? 0 : lg);
                const bool unk = ((pc->unknown[r] >> px) & 1u) != 0;
                unknown_ok = unknown_ok && unk == (g == CV_ERR_MISSING);
            }
    CHECK(ids_ok);
    CHECK(masks_ok);
    CHECK(layer_ok);
    CHECK(unknown_ok);
    CHECK((pc->present >> 13) & 1u);
    CHECK(read_padded(w, 9, 9, 9, *pc, nullptr, nullptr, 0) == CV_ERR_MISSING);

    // rings agree with cv_surface_y / cv_top_y
    std::vector<int64_t> ring(kPadRows), tring(kPadRows);
    surface_ring(w, 0, 0, ring.data());
    top_ring(w, 0, 0, tring.data());
    bool ring_ok = true;
    for (int pz = 0; pz < kPad; ++pz)
        for (int px = 0; px < kPad; ++px) {
            int64_t s = 0, t = 0;
            cv_surface_y(w, px - 1, pz - 1, &s);
            cv_top_y(w, px - 1, pz - 1, &t);
            ring_ok = ring_ok && ring[static_cast<size_t>(pz * kPad + px)] == s && tring[static_cast<size_t>(pz * kPad + px)] == t;
        }
    CHECK(ring_ok);
    cv_world_destroy(w);
}

// ---- install --------------------------------------------------------------------------------------------

void test_install() {
    cv_world* w = make_world(-128, 128);
    std::vector<uint16_t> ids(CV_CUBE_CELLS, STONE), water(CV_CUBE_CELLS, 0);
    water[5] = 17;
    CubeInstall in;
    in.ids = ids.data();
    in.layers[4] = water.data();
    const uint8_t blob[] = {42};
    in.blob = blob;
    in.blob_len = 1;
    CHECK(install_cube(w, 0, 0, 0, in, false) == CV_OK);
    CHECK(install_cube(w, 0, 0, 0, in, false) == CV_ERR_EXISTS);
    CHECK(cv_layer_get(w, 4, 5, 0, 0) == 17);
    uint8_t b = 0;
    CHECK(cv_cube_get_blob(w, 0, 0, 0, &b, 1) == 1 && b == 42);
    CHECK(drain(w).size() == 3);
    CubeInstall air;
    CHECK(install_cube(w, 0, 0, 0, air, true) == CV_OK);
    CHECK(cv_get(w, 1, 1, 1) == AIR && cv_layer_get(w, 4, 5, 0, 0) == 0);
    CHECK(install_cube(w, 0, 10, 0, air, true) == CV_ERR_ARG);
    cv_world_destroy(w);
}

// ---- prepare + commit (install split, cv_internal.hpp PreparedCube) ------------------------------------------

// Everything observable about a world, hashed: world revision, every cube (sorted) with its revision, its
// CV_PARTS_SAVED record and both mask arrays, the drained dirty set (sorted), and for the columns in
// [-r, r]^2 the SKY column, the TOP range and the column record. full = false leaves out the cube records and
// masks (the expensive part).
struct Digest {
    uint64_t h = 0xcbf29ce484222325ull;
    void add(uint64_t v) {
        h = (h ^ v) * 0x100000001b3ull;
        h ^= h >> 32;
    }
    void bytes(const void* p, size_t n) {
        const auto* b = static_cast<const uint8_t*>(p);
        size_t i = 0;
        for (; i + 8 <= n; i += 8) {
            uint64_t v;
            std::memcpy(&v, b + i, 8);
            add(v);
        }
        for (; i < n; ++i) add(b[i]);
    }
};
uint64_t world_digest(cv_world* w, int64_t r, bool full = true) {
    Digest d;
    d.add(static_cast<uint64_t>(cv_world_revision(w)));
    std::vector<int64_t> xyz(3 * 4096);
    const int64_t n = cv_cube_list(w, xyz.data(), 4096);
    std::vector<std::tuple<int64_t, int64_t, int64_t>> cubes;
    for (int64_t i = 0; i < n; ++i) {
        const auto k = static_cast<size_t>(i) * 3;
        cubes.emplace_back(xyz[k], xyz[k + 1], xyz[k + 2]);
    }
    std::sort(cubes.begin(), cubes.end());
    std::vector<uint8_t> buf(1 << 17);
    uint32_t rows[1024];
    for (const auto& [x, y, z] : cubes) {
        const int64_t head[4] = {x, y, z, cv_cube_revision(w, x, y, z)};
        d.bytes(head, sizeof head);
        if (!full) continue;
        const int64_t len = cv_cube_encode(w, x, y, z, CV_PARTS_SAVED, buf.data(), static_cast<int64_t>(buf.size()));
        d.add(static_cast<uint64_t>(len));
        if (len > 0) d.bytes(buf.data(), static_cast<size_t>(len));
        CubeRead rd(w, x, y, z);
        if (rd) {
            rd.occupancy_rows(rows);
            d.bytes(rows, sizeof rows);
            rd.opacity_rows(rows);
            d.bytes(rows, sizeof rows);
        }
    }
    for (const auto& [x, y, z] : drain_set(w)) {
        const int64_t k[3] = {x, y, z};
        d.bytes(k, sizeof k);
    }
    int64_t col[1024];
    for (int64_t cx = -r; cx <= r; ++cx) {
        for (int64_t cz = -r; cz <= r; ++cz) {
            d.add(static_cast<uint64_t>(cv_surface_column(w, cx, cz, col)));
            d.bytes(col, sizeof col);
            int64_t t[3] = {};
            d.add(static_cast<uint64_t>(cv_top_range(w, cx, cz, &t[0], &t[1], &t[2])));
            d.bytes(t, sizeof t);
            const int64_t len = cv_column_encode(w, cx, cz, buf.data(), static_cast<int64_t>(buf.size()));
            d.add(static_cast<uint64_t>(len));
            if (len > 0) d.bytes(buf.data(), static_cast<size_t>(len));
        }
    }
    return d.h;
}

// Random cube content of a few kinds: flat ground, noise, terrain with ores/leaves, all one block.
void random_content(Rng& rng, int64_t cy, int kind, std::vector<uint16_t>& ids, std::vector<uint16_t>& layer) {
    for (int i = 0; i < CV_CUBE_CELLS; ++i) {
        const int ly = i >> 10, lx = i & 31, lz = (i >> 5) & 31;
        const int64_t y = cy * 32 + ly;
        uint16_t id = AIR;
        switch (kind) {
            case 0: id = y < 0 ? STONE : AIR; break;
            case 1: id = static_cast<uint16_t>(rng() % 6); break;
            case 2: id = y < (lx + lz) / 3 - 5 ? ((i % 11) != 0 ? STONE : LEAVES) : ((i % 97) == 0 ? GLASS : AIR); break;
            default: id = static_cast<uint16_t>(kind - 3); break;
        }
        ids[static_cast<size_t>(i)] = id;
        layer[static_cast<size_t>(i)] = id == WATER ? 16 : ((i % 503) == 0 ? static_cast<uint16_t>(rng() & 0xFFu) : 0);
    }
}

struct Op {
    int kind = 0;   // 0 install, 1 decode, 2 create, 3 remove, 4 sets, 5 table, 6 column re-encode
    int64_t cx = 0, cy = 0, cz = 0;
    bool replace = false;
    CubeInstall in;
    std::vector<uint16_t> ids, layer;
    std::vector<uint8_t> blob, bytes;
    uint16_t fill = 0;
    std::vector<std::tuple<int64_t, int64_t, int64_t, uint16_t>> sets;
    uint16_t table_variant = 0;
};

void apply_other(cv_world* w, const Op& op) {
    switch (op.kind) {
        case 2: cv_cube_create(w, op.cx, op.cy, op.cz, op.fill); break;
        case 3: cv_cube_remove(w, op.cx, op.cy, op.cz); break;
        case 4:
            for (const auto& [x, y, z, id] : op.sets) cv_set(w, x, y, z, id);
            break;
        case 5: {
            uint16_t f[5];
            std::memcpy(f, kFlags, sizeof f);
            if (op.table_variant == 1) f[GLASS] = kFlags[STONE];   // glass becomes sky-opaque + opaque
            cv_block_table_set(w, f, 5);
            break;
        }
        case 6: {
            std::vector<uint8_t> b(1 << 16);
            const int64_t len = cv_column_encode(w, op.cx, op.cz, b.data(), static_cast<int64_t>(b.size()));
            if (len > 0) cv_column_decode(w, op.cx, op.cz, b.data(), len);
            break;
        }
        default: break;
    }
}

// A world fed by prepare (ahead of time, on other threads) + install_prepared ends up IDENTICAL after every
// step to one fed by the one-shot install_cube / cv_cube_decode - revisions, records, masks, dirty set,
// tracker answers and column records - including when edits, removals, column reloads and block-table changes
// fall between a cube's prepare and its install.
void test_prepare_install_equivalence() {
    Gen gen;
    for (int round = 0; round < 3; ++round) {
        const int64_t min_y = round == 2 ? CV_UNBOUNDED_MIN : -40, max_y = round == 2 ? CV_UNBOUNDED_MAX : 70;
        cv_world* a = make_world(min_y, max_y, round == 1 ? nullptr : gen_surface, &gen);
        cv_world* b = make_world(min_y, max_y, round == 1 ? nullptr : gen_surface, &gen);
        for (cv_world* w : {a, b}) {
            cv_layer_define(w, 0, CV_LAYER_SAVED | CV_LAYER_MESHED);
            cv_layer_define(w, 2, CV_LAYER_SAVED);
        }
        cv_world* enc = make_world(min_y, max_y);   // source of decode bytes
        cv_layer_define(enc, 0, CV_LAYER_SAVED);
        Rng rng(1234u + static_cast<unsigned>(round));
        std::vector<Op> ops(220);
        for (Op& op : ops) {
            op.cx = static_cast<int64_t>(rng() % 5) - 2;
            op.cy = static_cast<int64_t>(rng() % 5) - 2;
            op.cz = static_cast<int64_t>(rng() % 5) - 2;
            const uint32_t pick = rng() % 16;
            op.kind = pick < 7 ? 0 : pick < 10 ? 1 : pick < 11 ? 2 : pick < 12 ? 3 : pick < 14 ? 4 : pick < 15 ? 5 : 6;
            op.replace = (rng() & 1u) != 0;
            const int kind = static_cast<int>(rng() % 5);
            if (op.kind == 0 || op.kind == 1) {
                op.ids.resize(CV_CUBE_CELLS);
                op.layer.resize(CV_CUBE_CELLS);
                random_content(rng, op.cy, kind, op.ids, op.layer);
            }
            if (op.kind == 0) {
                if (kind != 4) op.in.ids = op.ids.data();
                op.in.fill_id = static_cast<uint16_t>(rng() % 5);
                op.in.layers[0] = op.layer.data();
                if ((rng() & 3u) == 0) {
                    op.blob = {1, 2, 3, static_cast<uint8_t>(rng())};
                    op.in.blob = op.blob.data();
                    op.in.blob_len = static_cast<int64_t>(op.blob.size());
                }
            } else if (op.kind == 1) {
                CubeInstall in;
                in.ids = op.ids.data();
                in.layers[0] = op.layer.data();
                if (install_cube(enc, op.cx, op.cy, op.cz, in, true) == CV_OK) {
                    op.bytes.resize(1 << 17);
                    const int64_t len = cv_cube_encode(enc, op.cx, op.cy, op.cz, CV_PARTS_SAVED, op.bytes.data(),
                                                       static_cast<int64_t>(op.bytes.size()));
                    op.bytes.resize(len > 0 ? static_cast<size_t>(len) : 0);
                    cv_cube_remove(enc, op.cx, op.cy, op.cz);
                }
            } else if (op.kind == 2) {
                op.fill = static_cast<uint16_t>(rng() % 5);
            } else if (op.kind == 4) {
                for (int k = 0; k < 24; ++k) {
                    op.sets.emplace_back(op.cx * 32 + static_cast<int64_t>(rng() % 32), op.cy * 32 + static_cast<int64_t>(rng() % 32),
                                         op.cz * 32 + static_cast<int64_t>(rng() % 32), static_cast<uint16_t>(rng() % 5));
                }
            } else if (op.kind == 5) {
                op.table_variant = static_cast<uint16_t>(rng() % 2);
            }
        }
        // b: every install/decode prepared up front on 4 threads (before any of the steps runs).
        std::vector<PreparedCube> prepared(ops.size());
        std::vector<int32_t> prep_rc(ops.size(), CV_OK);
        {
            std::vector<std::thread> threads;
            for (unsigned t = 0; t < 4; ++t) {
                threads.emplace_back([&, t] {
                    for (size_t i = t; i < ops.size(); i += 4) {
                        const Op& op = ops[i];
                        if (op.kind == 0) prep_rc[i] = prepare_cube(b, op.cx, op.cy, op.cz, op.in, prepared[i]);
                        if (op.kind == 1) {
                            prep_rc[i] = prepare_cube_encoded(b, op.cx, op.cy, op.cz, op.bytes.data(),
                                                              static_cast<int64_t>(op.bytes.size()), prepared[i]);
                        }
                    }
                });
            }
            for (std::thread& t : threads) t.join();
        }
        bool same = true;
        int first_bad = -1;
        for (size_t i = 0; i < ops.size(); ++i) {
            const Op& op = ops[i];
            int32_t ra = CV_OK, rb = CV_OK;
            if (op.kind == 0) {
                ra = install_cube(a, op.cx, op.cy, op.cz, op.in, op.replace);
                rb = prep_rc[i] != CV_OK ? prep_rc[i]
                                         : install_prepared(b, op.cx, op.cy, op.cz, std::move(prepared[i]), op.replace);
                // A refused install leaves the prepared cube as it was; a committed one consumes it.
                if (rb == CV_OK) CHECK(!prepared[i]);
                if (rb == CV_ERR_EXISTS) CHECK(static_cast<bool>(prepared[i]));
            } else if (op.kind == 1) {
                ra = cv_cube_decode(a, op.cx, op.cy, op.cz, op.bytes.data(), static_cast<int64_t>(op.bytes.size()));
                rb = prep_rc[i] != CV_OK ? prep_rc[i]
                                         : install_prepared(b, op.cx, op.cy, op.cz, std::move(prepared[i]), true);
            } else {
                apply_other(a, op);
                apply_other(b, op);
            }
            const bool full = i % 8 == 7 || i + 1 == ops.size();
            const bool ok = ra == rb && world_digest(a, 3, full) == world_digest(b, 3, full);
            if (!ok && first_bad < 0) first_bad = static_cast<int>(i);
            same = same && ok;
        }
        if (!same) std::printf("  round %d: first difference at op %d (kind %d)\n", round, first_bad, ops[static_cast<size_t>(first_bad)].kind);
        CHECK(same);
        CHECK(cv_cube_count(a) > 10);
        cv_world_destroy(enc);
        cv_world_destroy(a);
        cv_world_destroy(b);
    }
}

// Argument rules of prepare / install_prepared, and prepare racing installs + readers on other threads.
void test_prepare_install_api() {
    Gen gen;
    cv_world* w = make_world(-64, 64, gen_surface, &gen);
    cv_world* other = make_world(-64, 64, gen_surface, &gen);
    std::vector<uint16_t> ids(CV_CUBE_CELLS), layer(CV_CUBE_CELLS);
    Rng rng(9);
    random_content(rng, 0, 2, ids, layer);
    CubeInstall in;
    in.ids = ids.data();
    in.layers[1] = layer.data();

    PreparedCube empty;
    CHECK(!empty && empty.heap_bytes() == 0);
    CHECK(install_prepared(w, 0, 0, 0, std::move(empty), true) == CV_ERR_ARG);
    PreparedCube p;
    CHECK(prepare_cube(w, 0, 0, 0, in, p) == CV_OK && static_cast<bool>(p));
    CHECK(p.heap_bytes() > 4096);
    CHECK(install_prepared(w, 1, 0, 0, std::move(p), true) == CV_ERR_ARG);       // another cube
    CHECK(install_prepared(other, 0, 0, 0, std::move(p), true) == CV_ERR_ARG);   // another world
    CHECK(static_cast<bool>(p) && cv_cube_exists(w, 0, 0, 0) == 0 && cv_world_revision(w) == 0);
    CHECK(cv_cube_create(w, 0, 0, 0, AIR) == CV_OK);
    CHECK(install_prepared(w, 0, 0, 0, std::move(p), false) == CV_ERR_EXISTS);
    CHECK(static_cast<bool>(p));
    CHECK(install_prepared(w, 0, 0, 0, std::move(p), true) == CV_OK);
    CHECK(!p);
    CHECK(cv_get(w, 3, 2, 1) == ids[idx(3, 2, 1)] && cv_layer_get(w, 1, 5, 0, 0) == layer[idx(5, 0, 0)]);
    // prepare failures leave `out` empty
    PreparedCube q;
    CHECK(prepare_cube(w, 0, 0, 0, in, q) == CV_OK);
    CHECK(prepare_cube(w, 0, 5, 0, in, q) == CV_ERR_ARG && !q);   // wholly outside the bounds
    CubeInstall bad = in;
    bad.blob_len = 4;
    CHECK(prepare_cube(w, 0, 0, 0, bad, q) == CV_ERR_ARG && !q);
    CHECK(prepare_cube(nullptr, 0, 0, 0, in, q) == CV_ERR_ARG);
    std::vector<uint8_t> bytes(1 << 17);
    const int64_t len = cv_cube_encode(w, 0, 0, 0, CV_PARTS_SAVED, bytes.data(), static_cast<int64_t>(bytes.size()));
    CHECK(len > 0);
    CHECK(prepare_cube_encoded(w, 0, 0, 0, bytes.data(), len, q) == CV_OK && static_cast<bool>(q));
    CHECK(prepare_cube_encoded(w, 0, 0, 0, bytes.data(), len - 1, q) == CV_ERR_CORRUPT && !q);
    bytes[0] = 99;
    CHECK(prepare_cube_encoded(w, 0, 0, 0, bytes.data(), len, q) == CV_ERR_CORRUPT && !q);
    CHECK(prepare_cube_encoded(w, 0, 9, 0, bytes.data(), len, q) == CV_ERR_ARG && !q);
    cv_world_destroy(other);
    cv_world_destroy(w);

    // Workers prepare while the owner installs in a fixed order and readers query: the result equals the
    // one-shot world built in the same order.
    cv_world* seq = make_world(-64, 64, gen_surface, &gen);
    cv_world* par = make_world(-64, 64, gen_surface, &gen);
    struct Item {
        int64_t x, y, z;
        std::vector<uint16_t> ids;
    };
    std::vector<Item> items;
    for (int64_t y = -2; y < 2; ++y)
        for (int64_t z = -3; z < 3; ++z)
            for (int64_t x = -3; x < 3; ++x) {
                Item it{x, y, z, std::vector<uint16_t>(CV_CUBE_CELLS)};
                random_content(rng, y, static_cast<int>(rng() % 4), it.ids, layer);
                items.push_back(std::move(it));
            }
    for (const Item& it : items) {
        CubeInstall ci;
        ci.ids = it.ids.data();
        CHECK(install_cube(seq, it.x, it.y, it.z, ci, false) == CV_OK);
    }
    std::vector<PreparedCube> ready(items.size());
    std::vector<std::atomic<int>> done(items.size());
    std::atomic<size_t> next{0};
    std::atomic<bool> stop{false};
    std::vector<std::thread> threads;
    for (int t = 0; t < 6; ++t) {
        threads.emplace_back([&] {
            for (size_t i = next.fetch_add(1); i < items.size(); i = next.fetch_add(1)) {
                CubeInstall ci;
                ci.ids = items[i].ids.data();
                if (prepare_cube(par, items[i].x, items[i].y, items[i].z, ci, ready[i]) != CV_OK) std::abort();
                done[i].store(1, std::memory_order_release);
            }
        });
    }
    std::thread reader([&] {
        int64_t y = 0;
        while (!stop.load()) {
            for (int64_t x = -96; x < 96; x += 7) {
                cv_top_y(par, x, x / 2, &y);
                (void)cv_get(par, x, y - 1, x / 2);
            }
        }
    });
    for (size_t i = 0; i < items.size(); ++i) {
        while (done[i].load(std::memory_order_acquire) == 0) std::this_thread::yield();
        CHECK(install_prepared(par, items[i].x, items[i].y, items[i].z, std::move(ready[i]), false) == CV_OK);
    }
    stop.store(true);
    reader.join();
    for (std::thread& t : threads) t.join();
    CHECK(world_digest(seq, 4) == world_digest(par, 4));
    cv_world_destroy(seq);
    cv_world_destroy(par);
}

// ---- concurrency smoke -------------------------------------------------------------------------------------

void test_concurrency() {
    Gen gen;
    cv_world* w = make_world(-256, 256, gen_surface, &gen);
    for (int64_t y = -2; y < 2; ++y)
        for (int64_t z = -2; z < 2; ++z)
            for (int64_t x = -2; x < 2; ++x) cv_cube_create(w, x, y, z, y < 0 ? STONE : AIR);
    std::atomic<bool> stop{false};
    std::atomic<int64_t> reads{0}, bad{0};
    std::vector<std::thread> readers;
    for (int t = 0; t < 6; ++t) {
        readers.emplace_back([&, t] {
            Rng rng(static_cast<unsigned>(100 + t));
            std::vector<uint16_t> box(16 * 16 * 16);
            auto pc = std::make_unique<PaddedCube>();
            while (!stop.load()) {
                const int64_t x = static_cast<int64_t>(rng() % 128) - 64, y = static_cast<int64_t>(rng() % 128) - 64,
                              z = static_cast<int64_t>(rng() % 128) - 64;
                const int32_t g = cv_get(w, x, y, z);
                if (g != CV_ERR_MISSING && (g < 0 || g > 4)) bad.fetch_add(1);
                cv_read_box(w, x, y, z, 16, 16, 16, box.data(), 0);
                if (t % 2 == 0) read_padded(w, x >> 5, y >> 5, z >> 5, *pc, nullptr, nullptr, 0);
                int64_t s = 0;
                cv_surface_y(w, x, z, &s);
                std::vector<uint8_t> enc(64 * 1024);
                cv_cube_encode(w, x >> 5, y >> 5, z >> 5, CV_PARTS_SAVED, enc.data(), static_cast<int64_t>(enc.size()));
                reads.fetch_add(1);
            }
        });
    }
    Rng rng(9);
    std::vector<uint16_t> ids(CV_CUBE_CELLS);
    const auto until = std::chrono::steady_clock::now() + std::chrono::milliseconds(1500);
    int64_t writes = 0;
    while (std::chrono::steady_clock::now() < until) {
        const int64_t x = static_cast<int64_t>(rng() % 128) - 64, y = static_cast<int64_t>(rng() % 128) - 64,
                      z = static_cast<int64_t>(rng() % 128) - 64;
        const uint32_t op = rng() % 100;
        if (op < 90) {
            cv_set(w, x, y, z, static_cast<uint16_t>(rng() % 5));
        } else if (op < 95) {
            for (auto& v : ids) v = static_cast<uint16_t>(rng() % 5);
            cv_cube_write_ids(w, x >> 5, y >> 5, z >> 5, ids.data());
        } else if (op < 98) {
            cv_cube_remove(w, x >> 5, y >> 5, z >> 5);
        } else {
            cv_cube_create(w, x >> 5, y >> 5, z >> 5, AIR);
        }
        if (writes % 512 == 0) drain(w);
        ++writes;
    }
    stop = true;
    for (auto& t : readers) t.join();
    CHECK(bad.load() == 0);
    std::printf("  concurrency: %lld reader iterations, %lld writes\n", static_cast<long long>(reads.load()),
                static_cast<long long>(writes));
    // consistency after the storm
    std::vector<int64_t> list(3 * 256);
    const int64_t n = cv_cube_list(w, list.data(), 256);
    for (int64_t i = 0; i < n; ++i) CHECK(masks_match(w, list[static_cast<size_t>(3 * i)], list[static_cast<size_t>(3 * i + 1)], list[static_cast<size_t>(3 * i + 2)]));
    cv_world_destroy(w);
}

// ---- memory -------------------------------------------------------------------------------------------------

void test_memory() {
    Gen gen;
    cv_world* w = make_world(-384, 640, gen_surface, &gen);
    cv_cube_create(w, 0, 10, 0, AIR);
    const size_t air = cube_heap_bytes(w, 0, 10, 0);
    CHECK(air <= 64);
    // terrain-like cube: stone below a height field with a few ores
    std::vector<uint16_t> ids(CV_CUBE_CELLS);
    Rng rng(4);
    for (int ly = 0; ly < 32; ++ly)
        for (int lz = 0; lz < 32; ++lz)
            for (int lx = 0; lx < 32; ++lx)
                ids[idx(lx, ly, lz)] = ly < 12 + (lx + lz) % 9 ? (rng() % 50 == 0 ? GLASS : STONE) : AIR;
    cv_cube_write_ids(w, 1, 2, 0, ids.data());
    const size_t terrain = cube_heap_bytes(w, 1, 2, 0);
    // a 1024-tall column: stone below, a surface cube, air above
    for (int64_t cy = -12; cy < 20; ++cy) {
        if (cy < 2) cv_cube_create(w, 5, cy, 5, STONE);
        else if (cy == 2) cv_cube_write_ids(w, 5, cy, 5, ids.data());
        else cv_cube_create(w, 5, cy, 5, AIR);
    }
    const size_t column = column_heap_bytes(w, 5, 5);
    CHECK(column < 8192);
    std::printf("  memory: air cube %zu B (+%zu B map slot), terrain cube %zu B, 1024-tall column record %zu B\n", air,
                kCubeSlotBytes, terrain, column);
    cv_world_destroy(w);
}

} // namespace

int main() {
    CHECK(cv_abi_version() == CV_ABI_VERSION);
    const struct {
        const char* name;
        void (*fn)();
    } tests[] = {
        {"section tiers", test_section_tiers},
        {"get/set coordinates + bounds", test_get_set_coordinates},
        {"read_box", test_read_box},
        {"masks", test_masks},
        {"encode/decode + fuzz", test_encode_decode},
        {"layers + blob", test_layers},
        {"dirty exactness", test_dirty_exact},
        {"surface tracker (callback known)", [] { test_surface_tracker(true); }},
        {"surface tracker (callback unknown)", [] { test_surface_tracker(false); }},
        {"surface unbounded", test_surface_unbounded_and_callbackless},
        {"known air", test_known_air},
        {"read_padded + rings", test_read_padded},
        {"install", test_install},
        {"prepare + install == one-shot install", test_prepare_install_equivalence},
        {"prepare + install api + threads", test_prepare_install_api},
        {"concurrency", test_concurrency},
        {"memory", test_memory},
    };
    for (const auto& t : tests) {
        const int before = g_failures;
        std::printf("[voxel_world] %s\n", t.name);
        t.fn();
        if (g_failures != before) std::printf("  -> %d failure(s)\n", g_failures - before);
    }
    std::printf("[voxel_world] %d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
