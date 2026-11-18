// Test-only toy generator (voxel_world.h "test support"): exercises every streamer path - a sea with a seabed,
// a cliff plateau with a tunnel into it (only the visibility rule (c) discovers its far end), a floating
// overhang, trees placed by populate across cube borders with an order-independent merge, a water layer.
// Shared by the ctest voxel_stream and the Java CubeStream tests so both see the same world. Not for games.
#include "cenda/voxel_world.h"
#include "../int3_key.hpp"

#include <cmath>
#include <cstring>
#include <new>

namespace {

constexpr uint16_t kAir = 0;
constexpr uint16_t kStone = 1;
constexpr uint16_t kGrass = 2;
constexpr uint16_t kWater = 3;
constexpr uint16_t kLog = 4;
constexpr uint16_t kLeaves = 5;
constexpr int64_t kSea = 0;            // water fills y < 0 over low ground
constexpr uint16_t kWaterLayer = 16;   // layer 0 value of water cells (source bit)

struct Toy {
    cv_generator gen{};
    int64_t seed = 0;
};

// Raw terrain ------------------------------------------------------------------------------------------
int64_t ground(int64_t x, int64_t z) {
    if (x < -150) return -30 + static_cast<int64_t>(std::floor(5.0 * std::sin(static_cast<double>(z) / 17.0)));
    if (x >= 200 && x < 400 && z >= -200 && z < 200) return 160;   // cliff plateau
    return 8 + static_cast<int64_t>(std::floor(10.0 * std::sin(static_cast<double>(x) / 23.0) *
                                                std::cos(static_cast<double>(z) / 31.0)));
}
bool overhang_xz(int64_t x, int64_t z) { return x >= -100 && x < -60 && z >= 100 && z < 140; }
constexpr int64_t kSlabLo = 60, kSlabHi = 64;
bool tunnel(int64_t x, int64_t y, int64_t z) { return x >= 200 && x < 330 && y >= 80 && y < 88 && z >= 0 && z < 8; }

uint16_t cell(int64_t x, int64_t y, int64_t z, int64_t g) {
    if (y < g) {
        if (tunnel(x, y, z)) return kAir;
        return y == g - 1 ? kGrass : kStone;
    }
    if (overhang_xz(x, z) && y >= kSlabLo && y < kSlabHi) return kStone;
    if (y < kSea) return kWater;
    return kAir;
}

int32_t column_surface(void*, int64_t cx, int64_t cz, int64_t* sky, int64_t* top) {
    for (int lz = 0; lz < 32; ++lz) {
        for (int lx = 0; lx < 32; ++lx) {
            const int64_t x = cx * 32 + lx, z = cz * 32 + lz;
            int64_t s = ground(x, z);
            if (overhang_xz(x, z)) s = kSlabHi > s ? kSlabHi : s;
            const int64_t t = s < kSea ? kSea : s;
            sky[lz * 32 + lx] = s;
            top[lz * 32 + lx] = t;
        }
    }
    return 1;
}

int32_t generate_cube(void*, int64_t cx, int64_t cy, int64_t cz, uint16_t* ids, uint16_t* const* layers) {
    int64_t g[1024];
    int64_t gmin = INT64_MAX, tmax = INT64_MIN;
    for (int lz = 0; lz < 32; ++lz) {
        for (int lx = 0; lx < 32; ++lx) {
            const int64_t x = cx * 32 + lx, z = cz * 32 + lz;
            const int64_t v = ground(x, z);
            g[lz * 32 + lx] = v;
            if (v < gmin) gmin = v;
            int64_t t = v < kSea ? kSea : v;
            if (overhang_xz(x, z) && kSlabHi > t) t = kSlabHi;
            if (t > tmax) tmax = t;
        }
    }
    const int64_t y0 = cy * 32;
    if (y0 >= tmax) {
        ids[0] = kAir;
        return 0;
    }
    const bool tunnel_hit = !(cx * 32 + 31 < 200 || cx * 32 >= 330 || y0 + 31 < 80 || y0 >= 88 || cz * 32 + 31 < 0 ||
                              cz * 32 >= 8);
    if (y0 + 32 <= gmin - 1 && !tunnel_hit) {
        ids[0] = kStone;
        return 0;
    }
    uint16_t* water = layers != nullptr ? layers[0] : nullptr;
    for (int ly = 0; ly < 32; ++ly) {
        for (int lz = 0; lz < 32; ++lz) {
            for (int lx = 0; lx < 32; ++lx) {
                const int64_t x = cx * 32 + lx, y = y0 + ly, z = cz * 32 + lz;
                const uint16_t id = cell(x, y, z, g[lz * 32 + lx]);
                const int i = CV_CELL_INDEX(lx, ly, lz);
                ids[i] = id;
                if (water != nullptr) water[i] = id == kWater ? kWaterLayer : uint16_t{0};
            }
        }
    }
    return 1;
}

// Population: trees ------------------------------------------------------------------------------------
int rank(int32_t id) {
    if (id == kAir) return 0;
    if (id == kLeaves) return 1;
    if (id == kLog) return 2;
    return 3;   // terrain (and unknown / errors) is never overwritten
}

// Order-independent merge: a cell takes the highest-ranked block any feature wants there.
void place(const cv_gen_api* api, cv_world* w, int64_t x, int64_t y, int64_t z, uint16_t id) {
    const int32_t cur = api->get(w, x, y, z);
    if (cur < 0) return;
    if (rank(id) > rank(cur)) api->set(w, x, y, z, id);
}

int32_t populate(void* user, const cv_gen_api* api, cv_world* w, int64_t cx, int64_t cy, int64_t cz) {
    const auto* toy = static_cast<const Toy*>(user);
    int32_t trees = 0;
    // Roots inside the origin cube with x/z >= 2 so leaves (radius 2) stay inside the 2x2x2 hold.
    for (int lz = 2; lz < 32; ++lz) {
        for (int lx = 2; lx < 32; ++lx) {
            const int64_t x = cx * 32 + lx, z = cz * 32 + lz;
            const uint64_t h = cenda::hash3(x, static_cast<int64_t>(toy->seed), z);
            if (h % 53 != 0) continue;
            for (int ly = 31; ly >= 0; --ly) {
                const int64_t y = cy * 32 + ly;
                if (api->get(w, x, y, z) != kGrass) continue;
                // Raw-terrain test only (leaves/logs of other trees do not block a root): order-independent.
                if (rank(api->get(w, x, y + 1, z)) >= 3) break;
                const int64_t base = y + 1;
                for (int k = 0; k < 5; ++k) place(api, w, x, base + k, z, kLog);
                for (int dy = 3; dy <= 6; ++dy) {
                    const int r = dy >= 5 ? 1 : 2;
                    for (int dz = -r; dz <= r; ++dz) {
                        for (int dx = -r; dx <= r; ++dx) place(api, w, x + dx, base + dy, z + dz, kLeaves);
                    }
                }
                ++trees;
                break;
            }
        }
    }
    return trees;
}

} // namespace

extern "C" {

const cv_generator* cv_test_toy_generator_create(int64_t seed) {
    auto* toy = new (std::nothrow) Toy();
    if (toy == nullptr) return nullptr;
    toy->seed = seed;
    toy->gen.abi = CV_ABI_VERSION;
    toy->gen.populate_reach = 1;
    toy->gen.user = toy;
    toy->gen.column_surface = column_surface;
    toy->gen.generate_cube = generate_cube;
    toy->gen.populate = populate;
    return &toy->gen;
}

void cv_test_toy_generator_destroy(const cv_generator* generator) {
    if (generator == nullptr) return;
    delete static_cast<Toy*>(generator->user);
}

} // extern "C"
