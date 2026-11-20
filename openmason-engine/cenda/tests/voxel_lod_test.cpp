// ctest voxel_lod: the octree LOD (cv_lod_*, OpenMason #122 / Phase 5; voxel_world.h "LOD").
//  - downsampling rules against a brute-force reference (classes, >= 4 rule, FULL, representative id, walls,
//    pillars, uniform fast path, incompleteness);
//  - node records + foreign record readers (cube BLOCKS part, column runs raw / "CVS1");
//  - level-0 sources: loaded cube, store record, synthesized deep rock / sky (bulk and per cell), unknown;
//  - levels 1..k equal to a reference pyramid computed from the world's blocks;
//  - incremental dirtiness after edits at every level (== a fresh LOD), early stop for unchanged content;
//  - persistence round trip, meta mismatch (fail / reset), corrupt records;
//  - meshes: node-mode encoding, greedy merging, fluid tops, boundary cover rule, and the CRACK-FREE property
//    between levels (every culled side face is backed by solid at every level the neighbour can be drawn at);
//  - selection + handover invariants (exact cover, never over READY, parent until children ready, standby,
//    incomplete refinement, budgets) and a stream integration run (toy generator + store + note_events);
//  - concurrency (notes + edits + view changes + ticks from several threads) and bad arguments;
//  - performance numbers (CV_LOD_BENCH=1: the full 128 x 128-column r = 2048 area).
// CV_LOD_ONLY=<substring> runs only matching tests.
#include "cenda/voxel_world.h"
#include "cv_internal.hpp"
#include "lod_internal.hpp"

#if defined(_WIN32)
#include <process.h>
#define CV_TEST_PID() _getpid()
#else
#include <unistd.h>
#define CV_TEST_PID() ::getpid()
#endif

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <functional>
#include <map>
#include <memory>
#include <random>
#include <set>
#include <string>
#include <thread>
#include <tuple>
#include <vector>
#include <utility>

using namespace cenda::voxel;
using namespace cenda::voxel::lod;

namespace {

int g_failures = 0;
int g_checks = 0;

#define CHECK(...)                                                                                  \
    do {                                                                                            \
        ++g_checks;                                                                                 \
        if (!(__VA_ARGS__)) {                                                                       \
            ++g_failures;                                                                           \
            if (g_failures < 80) std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #__VA_ARGS__); \
        }                                                                                           \
    } while (0)

constexpr uint16_t AIR = 0, STONE = 1, GRASS = 2, WATER = 3, LOG = 4, LEAVES = 5, FLOWER = 6, DIRT = 7;
constexpr uint16_t kSolidF = CV_BLOCK_CUBE | CV_BLOCK_OPAQUE | CV_BLOCK_SKY_OPAQUE | CV_BLOCK_VIS_OPAQUE;
const uint16_t kFlags[8] = {
    0, kSolidF, kSolidF, CV_BLOCK_TRANSLUCENT | CV_BLOCK_TRANSPARENT, kSolidF,
    CV_BLOCK_CUBE | CV_BLOCK_TRANSPARENT, CV_BLOCK_HOST_GEOMETRY | CV_BLOCK_TRANSPARENT, kSolidF,
};
// Expected level-0 class per id.
uint8_t ref_class(uint16_t id) {
    switch (id) {
        case STONE: case GRASS: case LOG: case LEAVES: case DIRT: return kSolid;
        case WATER: return kFluid;
        default: return kAir;
    }
}

using NK = std::tuple<int, int64_t, int64_t, int64_t>;

int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

std::string tmp_dir(const char* name) {
    const auto base = std::filesystem::temp_directory_path() /
                      ("cv_lod_test_" + std::to_string(static_cast<long long>(CV_TEST_PID())) + "_" + name);
    std::filesystem::remove_all(base);
    std::filesystem::create_directories(base);
    return base.string();
}

void setup_world(cv_world* w) {
    cv_block_table_set(w, kFlags, 8);
    cv_layer_define(w, 0, CV_LAYER_SAVED | CV_LAYER_NETWORK | CV_LAYER_MESHED);
    cv_fluid_desc fd{};
    fd.layer = 0;
    fd.fluid_id = WATER;
    for (int i = 0; i < 8; ++i) fd.surface_by_level[i] = 0.875f;
    cv_fluid_set(w, &fd);
    // Face textures: grass top layer 20, sides 21, bottom 7; others default (layer = id).
    cv_face_tex g[6] = {{20, 0, 0}, {7, 0, 0}, {21, 0, 0}, {21, 0, 0}, {21, 0, 0}, {21, 0, 0}};
    cv_block_faces_set(w, GRASS, g);
}

cv_world* make_world(int64_t min_y, int64_t max_y, cv_surface_fn surface = nullptr) {
    cv_world_desc d{};
    d.min_y = min_y;
    d.max_y = max_y;
    d.air_id = AIR;
    d.surface = surface;
    cv_world* w = cv_world_create(&d);
    setup_world(w);
    return w;
}

// Deterministic terrain: hills, a tunnel band, a lake, trees, flowers.
int64_t height_at(int64_t x, int64_t z) {
    return 40 + static_cast<int64_t>(std::lround(7.0 * std::sin(static_cast<double>(x) / 9.0) +
                                                 6.0 * std::cos(static_cast<double>(z) / 11.0)));
}
uint64_t spot(int64_t x, int64_t z) {
    return cenda::mix64(static_cast<uint64_t>(x) * 31u + static_cast<uint64_t>(z) * 1000003u);
}
uint16_t terrain_block(int64_t x, int64_t y, int64_t z) {
    const int64_t h = height_at(x, z);
    if (y < h - 4) {
        if (y > 20 && y < 24 && ((x + 1000) % 23) < 3) return AIR;   // tunnel band
        return STONE;
    }
    if (y < h - 1) return DIRT;
    if (y < h) return h < 37 ? DIRT : GRASS;
    if (y < 37 && h < 37) return WATER;   // lake up to y 36
    if (h >= 37 && (spot(x, z) % 97) == 0 && y < h + 5) return LOG;
    if (h >= 37 && y >= h + 3 && y < h + 7) {
        for (int dx = -2; dx <= 2; ++dx) {
            for (int dz = -2; dz <= 2; ++dz) {
                if ((spot(x + dx, z + dz) % 97) == 0 && height_at(x + dx, z + dz) == h) return LEAVES;
            }
        }
    }
    if (y == h && h >= 37 && (spot(x, z) % 7) == 0) return FLOWER;
    return AIR;
}

void gen_cube(cv_world* w, int64_t cx, int64_t cy, int64_t cz) {
    std::vector<uint16_t> ids(CV_CUBE_CELLS);
    for (int ly = 0; ly < 32; ++ly) {
        for (int lz = 0; lz < 32; ++lz) {
            for (int lx = 0; lx < 32; ++lx) {
                ids[static_cast<size_t>(CV_CELL_INDEX(lx, ly, lz))] = terrain_block(cx * 32 + lx, cy * 32 + ly, cz * 32 + lz);
            }
        }
    }
    CHECK(cv_cube_write_ids(w, cx, cy, cz, ids.data()) == CV_OK);
}

void gen_columns(cv_world* w, int64_t cx0, int64_t cx1, int64_t cz0, int64_t cz1, int64_t cy0, int64_t cy1) {
    for (int64_t cx = cx0; cx <= cx1; ++cx) {
        for (int64_t cz = cz0; cz <= cz1; ++cz) {
            for (int64_t cy = cy0; cy <= cy1; ++cy) gen_cube(w, cx, cy, cz);
        }
    }
}

cv_lod_desc lod_desc(int32_t max_level) {
    cv_lod_desc d{};
    d.max_level = max_level;
    d.worker_threads = 3;
    d.fill_id = STONE;
    return d;
}

// ---- brute-force reference --------------------------------------------------------------------------------
struct RefCell {
    uint8_t cell;
    uint16_t id;
};
using RefNode = std::vector<RefCell>;   // 32768, CV_CELL_INDEX
using Blk = std::function<uint16_t(int64_t, int64_t, int64_t)>;

RefCell ref_combine(const RefCell sub[8]) {   // sub index: (dy * 2 + dz) * 2 + dx
    int s = 0, f = 0, full = 0;
    for (int k = 0; k < 8; ++k) {
        if ((sub[k].cell & 3) == kSolid) ++s;
        if ((sub[k].cell & 3) == kFluid) ++f;
        if ((sub[k].cell & kFull) != 0) ++full;
    }
    const int a = 8 - s - f;
    auto most_common = [](const std::map<uint16_t, int>& cnt) {
        uint16_t best = 0;
        int bn = -1;
        for (const auto& [id, n] : cnt) {   // ascending id: the first maximum wins ties
            if (n > bn) {
                bn = n;
                best = id;
            }
        }
        return best;
    };
    if (s >= 4) {
        std::map<uint16_t, int> cnt;
        for (int dy = 1; dy >= 0 && cnt.empty(); --dy) {
            for (int k = 0; k < 4; ++k) {
                const RefCell& c = sub[dy * 4 + k];
                if ((c.cell & 3) == kSolid) ++cnt[c.id];
            }
        }
        return RefCell{static_cast<uint8_t>(kSolid | (full == 8 ? kFull : 0)), most_common(cnt)};
    }
    if (f > 0 && f >= a) {
        std::map<uint16_t, int> cnt;
        for (int k = 0; k < 8; ++k) {
            if ((sub[k].cell & 3) == kFluid) ++cnt[sub[k].id];
        }
        return RefCell{kFluid, most_common(cnt)};
    }
    return RefCell{kAir, AIR};
}

RefNode ref_node(int level, int64_t nx, int64_t ny, int64_t nz, const Blk& blk) {
    RefNode out(CV_CUBE_CELLS);
    if (level == 0) {
        for (int y = 0; y < 32; ++y) {
            for (int z = 0; z < 32; ++z) {
                for (int x = 0; x < 32; ++x) {
                    const uint16_t id = blk(nx * 32 + x, ny * 32 + y, nz * 32 + z);
                    const uint8_t c = ref_class(id);
                    out[static_cast<size_t>(CV_CELL_INDEX(x, y, z))] =
                        RefCell{static_cast<uint8_t>(c == kSolid ? (kSolid | kFull) : c), c == kAir ? AIR : id};
                }
            }
        }
        return out;
    }
    RefNode ch[8];
    for (int i = 0; i < 8; ++i) {
        ch[i] = ref_node(level - 1, nx * 2 + (i & 1), ny * 2 + ((i >> 2) & 1), nz * 2 + ((i >> 1) & 1), blk);
    }
    for (int y = 0; y < 32; ++y) {
        for (int z = 0; z < 32; ++z) {
            for (int x = 0; x < 32; ++x) {
                const int ci = ((y >> 4) * 2 + (z >> 4)) * 2 + (x >> 4);
                RefCell sub[8];
                for (int dy = 0; dy < 2; ++dy) {
                    for (int dz = 0; dz < 2; ++dz) {
                        for (int dx = 0; dx < 2; ++dx) {
                            sub[(dy * 2 + dz) * 2 + dx] = ch[ci][static_cast<size_t>(CV_CELL_INDEX(
                                2 * (x & 15) + dx, 2 * (y & 15) + dy, 2 * (z & 15) + dz))];
                        }
                    }
                }
                out[static_cast<size_t>(CV_CELL_INDEX(x, y, z))] = ref_combine(sub);
            }
        }
    }
    return out;
}

bool node_matches(cv_lod* lod, int level, int64_t nx, int64_t ny, int64_t nz, const RefNode& ref, int* complete = nullptr) {
    std::vector<uint16_t> ids(CV_CUBE_CELLS);
    std::vector<uint8_t> cells(CV_CUBE_CELLS);
    const int32_t r = cv_lod_read_node(lod, level, nx, ny, nz, ids.data(), cells.data(), nullptr);
    if (complete != nullptr) *complete = r;
    if (r < 0) return false;
    for (int i = 0; i < CV_CUBE_CELLS; ++i) {
        if (cells[static_cast<size_t>(i)] != ref[static_cast<size_t>(i)].cell || ids[static_cast<size_t>(i)] != ref[static_cast<size_t>(i)].id) {
            return false;
        }
    }
    return true;
}

ClassTable make_ct() {
    ClassTable ct{};
    for (int i = 0; i < 65536; ++i) ct.cls[i] = ref_class(static_cast<uint16_t>(i));
    return ct;
}

NodePtr node_of(int level, const std::function<RefCell(int, int, int)>& f, bool complete = true) {
    auto d = std::make_shared<NodeData>();
    d->key = NodeKey{level, 0, 0, 0};
    d->complete = complete;
    d->ids.resize(kCells);
    d->cells.resize(kCells);
    for (int y = 0; y < 32; ++y) {
        for (int z = 0; z < 32; ++z) {
            for (int x = 0; x < 32; ++x) {
                const RefCell c = f(x, y, z);
                d->ids[static_cast<size_t>(CV_CELL_INDEX(x, y, z))] = c.id;
                d->cells[static_cast<size_t>(CV_CELL_INDEX(x, y, z))] = c.cell;
            }
        }
    }
    finalize(*d);
    return d;
}

void digests_all(cv_lod* lod, int max_level, int64_t c0, int64_t c1, int64_t cy0, int64_t cy1, std::map<NK, uint64_t>& out) {
    for (int lv = 1; lv <= max_level; ++lv) {
        for (int64_t x = c0 >> lv; x <= c1 >> lv; ++x) {
            for (int64_t z = c0 >> lv; z <= c1 >> lv; ++z) {
                for (int64_t y = cy0 >> lv; y <= cy1 >> lv; ++y) {
                    uint64_t dg = 0;
                    cv_lod_read_node(lod, lv, x, y, z, nullptr, nullptr, &dg);
                    out[{lv, x, y, z}] = dg;
                }
            }
        }
    }
}

// ---- 1. downsampling rules ----------------------------------------------------------------------------------
void test_downsample_rules() {
    const RefCell S{kSolid | kFull, STONE}, Gr{kSolid | kFull, GRASS}, Di{kSolid | kFull, DIRT}, Wa{kFluid, WATER},
        Ai{kAir, AIR};
    // A child whose 2x2x2 groups all follow one pattern on (x & 1, y & 1, z & 1).
    auto group = [](const std::function<RefCell(int, int, int)>& sub) {
        return node_of(0, [=](int x, int y, int z) { return sub(x & 1, y & 1, z & 1); });
    };
    auto parent_cell = [](const NodePtr& child) {
        NodePtr ch[8] = {child, child, child, child, child, child, child, child};
        NodeData out;
        node_downsample(out, NodeKey{1, 0, 0, 0}, ch, AIR);
        return std::make_pair(out.cell(0), out.id(0));
    };
    auto p = parent_cell(group([&](int, int y, int) { return y == 0 ? S : Ai; }));   // s = 4
    CHECK(p.first == kSolid && p.second == STONE);
    p = parent_cell(group([&](int x, int y, int z) { return (y == 0 && !(x == 1 && z == 1)) ? S : Wa; }));   // s3 f5
    CHECK(p.first == kFluid && p.second == WATER);
    p = parent_cell(group([&](int x, int y, int z) {   // s3 f2 a3 -> air
        if (y == 0 && !(x == 1 && z == 1)) return S;
        if (y == 0) return Wa;
        return (x == 0 && z == 0) ? Wa : Ai;
    }));
    CHECK(p.first == kAir && p.second == AIR);
    p = parent_cell(group([&](int x, int y, int z) {   // s3 f3 a2 -> fluid
        if (y == 0 && !(x == 1 && z == 1)) return S;
        if (y == 0) return Wa;
        return (x == 0) ? Wa : Ai;
    }));
    CHECK(p.first == kFluid);
    p = parent_cell(group([&](int x, int y, int) { return y == 0 ? Di : (x == 0 ? Gr : Ai); }));   // exposed top
    CHECK(p.first == kSolid && p.second == GRASS);
    p = parent_cell(group([&](int, int y, int) { return y == 0 ? Di : Gr; }));
    CHECK(p.first == (kSolid | kFull) && p.second == GRASS);
    p = parent_cell(group([&](int x, int, int) { return x == 0 ? Di : S; }));   // tie -> smaller id
    CHECK(p.second == STONE);

    // A 1-block wall survives every level as a 1-cell wall; a 1x1 pillar vanishes at level 1.
    {
        NodePtr cur = node_of(0, [&](int x, int, int) { return x == 5 ? S : Ai; });
        for (int lv = 1; lv <= 5; ++lv) {
            NodePtr ch[8] = {cur, cur, cur, cur, cur, cur, cur, cur};
            auto out = std::make_shared<NodeData>();
            node_downsample(*out, NodeKey{lv, 0, 0, 0}, ch, AIR);
            int solid_cells = 0;
            for (int i = 0; i < kCells; ++i) solid_cells += (out->cell(i) & 3) == kSolid ? 1 : 0;
            CHECK(solid_cells == (2048 << (lv - 1)));   // the copies' walls stay 1 cell thick (2, 4, ... planes)
            cur = out;
        }
        auto pillar = node_of(0, [&](int x, int, int z) { return (x == 5 && z == 5) ? S : Ai; });
        NodePtr ch[8] = {pillar, pillar, pillar, pillar, pillar, pillar, pillar, pillar};
        NodeData out;
        node_downsample(out, NodeKey{1, 0, 0, 0}, ch, AIR);
        CHECK(out.uniform && (out.uniform_cell & 3) == kAir);
    }
    // Uniform fast path == general path (same digest), incompleteness.
    {
        NodeData u;
        node_uniform(u, NodeKey{0, 0, 0, 0}, STONE, kSolid | kFull, true);
        auto up = std::make_shared<NodeData>(u);
        auto arr = node_of(0, [&](int, int, int) { return S; });   // canonicalised to uniform by finalize
        CHECK(arr->uniform && arr->digest == up->digest);
        NodePtr a[8] = {up, up, up, up, up, up, up, up};
        NodeData pa, pb;
        node_downsample(pa, NodeKey{1, 0, 0, 0}, a, AIR);
        // General path: children with one dirt block per 2x2x2 group (non-uniform) -> parent all stone, full.
        auto mostly = node_of(0, [&](int x, int y, int z) { return ((x & 1) && (y & 1) && (z & 1)) ? Di : S; });
        CHECK(!mostly->uniform);
        NodePtr b[8] = {mostly, mostly, mostly, mostly, mostly, mostly, mostly, mostly};
        node_downsample(pb, NodeKey{1, 0, 0, 0}, b, AIR);
        CHECK(pa.uniform && pa.complete && pa.uniform_cell == (kSolid | kFull) && pa.uniform_id == STONE);
        CHECK(pb.uniform && pb.uniform_id == STONE && pb.digest == pa.digest);
        NodePtr c[8] = {up, up, nullptr, up, up, up, up, up};
        NodeData pc;
        node_downsample(pc, NodeKey{1, 0, 0, 0}, c, AIR);
        CHECK(!pc.complete && pc.child_digest[2] == 0 && pc.child_digest[0] == up->digest);
        CHECK((pc.cell(CV_CELL_INDEX(0, 0, 16)) & 3) == kAir && (pc.cell(0) & 3) == kSolid);   // unknown = air
        NodePtr e[8] = {up, up, up, up, up, up, up, up};
        auto inc = std::make_shared<NodeData>(u);
        inc->complete = false;
        finalize(*inc);
        e[5] = inc;
        NodeData pe;
        node_downsample(pe, NodeKey{1, 0, 0, 0}, e, AIR);
        CHECK(!pe.complete && pe.digest != pa.digest);   // incompleteness is part of the digest
    }
    // Random children against the reference combine.
    {
        std::mt19937_64 rng(7);
        const RefCell pal[6] = {S, Gr, Di, Wa, Ai, RefCell{kSolid | kFull, LEAVES}};
        NodePtr ch[8];
        std::vector<RefNode> refs(8);
        for (int i = 0; i < 8; ++i) {
            RefNode rn(kCells);
            for (auto& c : rn) c = pal[rng() % 6];
            refs[static_cast<size_t>(i)] = rn;
            ch[i] = node_of(0, [&](int x, int y, int z) { return rn[static_cast<size_t>(CV_CELL_INDEX(x, y, z))]; });
        }
        NodeData out;
        node_downsample(out, NodeKey{1, 0, 0, 0}, ch, AIR);
        bool all = true;
        for (int y = 0; y < 32 && all; ++y) {
            for (int z = 0; z < 32 && all; ++z) {
                for (int x = 0; x < 32 && all; ++x) {
                    const int ci = ((y >> 4) * 2 + (z >> 4)) * 2 + (x >> 4);
                    RefCell sub[8];
                    for (int dy = 0; dy < 2; ++dy) {
                        for (int dz = 0; dz < 2; ++dz) {
                            for (int dx = 0; dx < 2; ++dx) {
                                sub[(dy * 2 + dz) * 2 + dx] = refs[static_cast<size_t>(ci)][static_cast<size_t>(
                                    CV_CELL_INDEX(2 * (x & 15) + dx, 2 * (y & 15) + dy, 2 * (z & 15) + dz))];
                            }
                        }
                    }
                    const RefCell ex = ref_combine(sub);
                    const int i = CV_CELL_INDEX(x, y, z);
                    all = out.cell(i) == ex.cell && out.id(i) == ex.id;
                }
            }
        }
        CHECK(all);
    }
    // Level-0 classification: flowers air, water fluid, leaves solid; cells outside the range air.
    {
        std::vector<uint16_t> ids(kCells, FLOWER);
        ids[0] = LEAVES;
        ids[1] = WATER;
        ids[static_cast<size_t>(CV_CELL_INDEX(0, 31, 0))] = STONE;
        const ClassTable ct = make_ct();
        NodeData d;
        node_from_ids(d, NodeKey{0, 0, 0, 0}, ids.data(), ct, AIR, -100, 31);   // y 31 outside
        CHECK(d.cell(0) == (kSolid | kFull) && d.id(0) == LEAVES);
        CHECK(d.cell(1) == kFluid && d.id(1) == WATER);
        CHECK(d.cell(2) == kAir && d.id(2) == AIR);
        CHECK(d.cell(CV_CELL_INDEX(0, 31, 0)) == kAir);
    }
}

// ---- 2. records ----------------------------------------------------------------------------------------------
void test_records() {
    std::mt19937_64 rng(11);
    auto d = std::make_shared<NodeData>();
    d->key = NodeKey{3, -5, 7, 123456789};
    d->complete = true;
    d->ids.resize(kCells);
    d->cells.resize(kCells);
    for (int i = 0; i < kCells; ++i) {
        const int c = static_cast<int>(rng() % 3);
        d->cells[static_cast<size_t>(i)] = static_cast<uint8_t>(c == 1 ? (kSolid | ((rng() & 1) ? kFull : 0)) : c);
        d->ids[static_cast<size_t>(i)] = static_cast<uint16_t>(rng() % 9);
    }
    for (int i = 0; i < 8; ++i) d->child_digest[i] = rng();
    finalize(*d);
    std::vector<uint8_t> bytes;
    encode_record(*d, bytes);
    NodeData back;
    CHECK(decode_record(bytes.data(), bytes.size(), d->key, back) == CV_OK);
    CHECK(back.digest == d->digest && back.ids == d->ids && back.cells == d->cells && back.complete);
    CHECK(std::equal(back.child_digest, back.child_digest + 8, d->child_digest));
    CHECK(decode_record(bytes.data(), bytes.size(), NodeKey{3, -5, 7, 0}, back) == CV_ERR_CORRUPT);
    CHECK(decode_record(bytes.data(), bytes.size(), NodeKey{2, -5, 7, 123456789}, back) == CV_ERR_CORRUPT);
    CHECK(decode_record(bytes.data(), bytes.size() - 1, d->key, back) == CV_ERR_CORRUPT);
    auto flipped = bytes;
    flipped[200] ^= 1;
    CHECK(decode_record(flipped.data(), flipped.size(), d->key, back) == CV_ERR_CORRUPT);
    auto longer = bytes;
    longer.push_back(0);
    CHECK(decode_record(longer.data(), longer.size(), d->key, back) == CV_ERR_CORRUPT);
    auto badcell = bytes;
    badcell[badcell.size() - 1] = 3;
    CHECK(decode_record(badcell.data(), badcell.size(), d->key, back) == CV_ERR_CORRUPT);
    NodeData u;
    node_uniform(u, NodeKey{1, 0, -1, 0}, STONE, kSolid | kFull, false);
    encode_record(u, bytes);
    CHECK(bytes.size() < 128);
    CHECK(decode_record(bytes.data(), bytes.size(), u.key, back) == CV_OK && back.uniform && !back.complete &&
          back.uniform_id == STONE && back.digest == u.digest);

    // Cube BLOCKS reader vs cv_cube_encode: single, paletted 1/2/5/8 bits and direct sections.
    cv_world* w = make_world(-64, 192);
    std::vector<uint16_t> ids(CV_CUBE_CELLS);
    for (int i = 0; i < CV_CUBE_CELLS; ++i) {
        const int sec = ((i >> 14) & 1) * 4 + ((i >> 9) & 1) * 2 + ((i >> 4) & 1);
        uint16_t v;
        switch (sec) {
            case 0: v = 5; break;
            case 1: v = static_cast<uint16_t>(rng() % 2); break;
            case 2: v = static_cast<uint16_t>(rng() % 3); break;
            case 3: v = static_cast<uint16_t>(rng() % 30); break;
            case 4: v = static_cast<uint16_t>(rng() % 256); break;
            case 5: v = static_cast<uint16_t>(rng() % 4000); break;
            default: v = static_cast<uint16_t>(100 + (i % 7)); break;
        }
        ids[static_cast<size_t>(i)] = v;
    }
    CHECK(cv_cube_write_ids(w, 2, 1, -3, ids.data()) == CV_OK);
    std::vector<uint8_t> enc(200000);
    const int64_t n = cv_cube_encode(w, 2, 1, -3, CV_PARTS_SAVED, enc.data(), static_cast<int64_t>(enc.size()));
    CHECK(n > 0);
    std::vector<uint16_t> out(CV_CUBE_CELLS, 0xFFFF);
    CHECK(decode_cube_blocks(enc.data(), static_cast<size_t>(n), out.data()) == CV_OK);
    CHECK(out == ids);
    CHECK(decode_cube_blocks(enc.data(), 20, out.data()) == CV_ERR_CORRUPT);
    // Column records: raw core and the stream envelope.
    std::vector<uint8_t> col(65536);
    const int64_t cn = cv_column_encode(w, 2, -3, col.data(), static_cast<int64_t>(col.size()));
    CHECK(cn > 0);
    std::vector<Run> runs;
    const uint8_t* core = nullptr;
    size_t core_len = 0;
    CHECK(parse_column_record(col.data(), static_cast<size_t>(cn), runs, &core, &core_len));
    CHECK(runs.size() == 1 && runs[0].lo == 1 && runs[0].hi == 1 && core == col.data() && core_len == static_cast<size_t>(cn));
    std::vector<uint8_t> env;
    auto p32 = [&](uint32_t v) { for (int i = 0; i < 4; ++i) env.push_back(static_cast<uint8_t>(v >> (8 * i))); };
    p32(0x31535643u);
    p32(1);
    for (int i = 0; i < 16; ++i) env.push_back(0);
    p32(static_cast<uint32_t>(cn));
    env.insert(env.end(), col.begin(), col.begin() + cn);
    CHECK(parse_column_record(env.data(), env.size(), runs, &core, &core_len));
    CHECK(runs.size() == 1 && core == env.data() + 28 && core_len == static_cast<size_t>(cn));
    env.pop_back();
    CHECK(!parse_column_record(env.data(), env.size(), runs, &core, &core_len));
    cv_world_destroy(w);
}

// ---- 3. level-0 sources ---------------------------------------------------------------------------------------
int32_t slope_surface(void*, int64_t, int64_t, int64_t* sky, int64_t* top) {
    for (int lz = 0; lz < 32; ++lz) {
        for (int lx = 0; lx < 32; ++lx) {
            sky[lz * 32 + lx] = 40 + lx;
            top[lz * 32 + lx] = 40 + lx;
        }
    }
    return 1;
}

void test_sources() {
    const std::string dir = tmp_dir("sources");
    cv_store* store = cv_store_open((dir + "/cubes").c_str());
    CHECK(store != nullptr);
    cv_world* w = make_world(-64, 192);
    gen_columns(w, 0, 0, 0, 0, 0, 2);
    std::vector<uint8_t> enc(200000);
    int64_t n = cv_cube_encode(w, 0, 1, 0, CV_PARTS_SAVED, enc.data(), static_cast<int64_t>(enc.size()));
    CHECK(cv_store_write_cube(store, 0, 1, 0, enc.data(), n) == CV_OK);
    gen_columns(w, 1, 1, 0, 0, 0, 1);
    cv_lod_desc d = lod_desc(2);
    d.cube_store = store;
    cv_lod* lod = cv_lod_create(w, &d);
    CHECK(lod != nullptr);
    const Blk blk = [](int64_t x, int64_t y, int64_t z) { return terrain_block(x, y, z); };
    const Blk stone = [](int64_t, int64_t, int64_t) { return STONE; };
    const Blk air = [](int64_t, int64_t, int64_t) { return AIR; };
    int complete = -9;
    CHECK(node_matches(lod, 0, 0, 0, 0, ref_node(0, 0, 0, 0, blk), &complete));   // loaded
    CHECK(complete == 1);
    CHECK(cv_cube_remove(w, 0, 1, 0) == CV_OK);
    CHECK(cv_cube_remove(w, 1, 0, 0) == CV_OK);
    CHECK(node_matches(lod, 0, 0, 1, 0, ref_node(0, 0, 1, 0, blk), &complete));   // store record
    CHECK(complete == 1);
    CHECK(node_matches(lod, 0, 0, -2, 0, ref_node(0, 0, -2, 0, stone), &complete));   // deep rock (synthesized)
    CHECK(complete == 1);
    CHECK(node_matches(lod, 0, 0, 4, 0, ref_node(0, 0, 4, 0, air), &complete));   // above TOP (synthesized)
    CHECK(complete == 1);
    CHECK(cv_lod_read_node(lod, 0, 1, 0, 0, nullptr, nullptr, nullptr) == 0);   // generated, record missing
    CHECK(cv_lod_read_node(lod, 0, 5, 0, 5, nullptr, nullptr, nullptr) == 0);   // never generated
    CHECK(cv_lod_read_node(lod, 0, 0, -10, 0, nullptr, nullptr, nullptr) == 1);  // outside the range
    CHECK(cv_lod_destroy(lod) == CV_OK);

    // The column only as a store record (raw core, then the stream's envelope).
    n = cv_column_encode(w, 0, 0, enc.data(), static_cast<int64_t>(enc.size()));
    CHECK(n > 0);
    std::vector<uint8_t> raw(enc.begin(), enc.begin() + n);
    for (int64_t cy = 0; cy <= 2; ++cy) cv_cube_remove(w, 0, cy, 0);
    CHECK(cv_column_remove(w, 0, 0) == CV_OK);
    for (int variant = 0; variant < 2; ++variant) {
        std::vector<uint8_t> rec = raw;
        if (variant == 1) {
            std::vector<uint8_t> env;
            auto p32 = [&](uint32_t v) { for (int i = 0; i < 4; ++i) env.push_back(static_cast<uint8_t>(v >> (8 * i))); };
            p32(0x31535643u);
            p32(0);
            p32(static_cast<uint32_t>(raw.size()));
            env.insert(env.end(), raw.begin(), raw.end());
            rec = env;
        }
        CHECK(cv_store_write_column(store, 0, 0, rec.data(), static_cast<int64_t>(rec.size())) == CV_OK);
        lod = cv_lod_create(w, &d);
        CHECK(node_matches(lod, 0, 0, -2, 0, ref_node(0, 0, -2, 0, stone), &complete));
        CHECK(complete == 1);
        CHECK(cv_lod_read_node(lod, 0, 0, 0, 0, nullptr, nullptr, nullptr) == 0);   // in the runs, no record
        CHECK(node_matches(lod, 0, 0, 1, 0, ref_node(0, 0, 1, 0, blk), &complete));
        CHECK(cv_lod_destroy(lod) == CV_OK);
    }
    // Per-cell synthesis: a world whose surface callback slopes (TOP = 40 + lx); only cube 0 generated, so the
    // never-generated cube 1 (y 32..63) is rock below the slope and air above it.
    cv_world* w2 = make_world(-64, 192, slope_surface);
    std::vector<uint16_t> rock(CV_CUBE_CELLS, STONE);
    CHECK(cv_cube_write_ids(w2, 3, 0, 3, rock.data()) == CV_OK);
    {
        cv_lod_desc d2 = lod_desc(1);
        cv_lod* l2 = cv_lod_create(w2, &d2);
        const Blk slope = [](int64_t x, int64_t y, int64_t) { return y < 40 + (x & 31) ? STONE : AIR; };
        CHECK(node_matches(l2, 0, 3, 1, 3, ref_node(0, 3, 1, 3, slope), &complete));
        CHECK(complete == 1);
        cv_lod_destroy(l2);
    }
    cv_world_destroy(w2);
    cv_world_destroy(w);
    cv_store_close(store);
    std::filesystem::remove_all(dir);
}

// ---- 4. levels vs the reference pyramid ------------------------------------------------------------------------
void test_levels() {
    cv_world* w = make_world(-64, 192);
    gen_columns(w, 0, 7, 0, 7, -2, 5);   // 8x8 columns, every cube of the range
    cv_lod_desc d = lod_desc(3);
    cv_lod* lod = cv_lod_create(w, &d);
    const Blk blk = [](int64_t x, int64_t y, int64_t z) { return (y >= -64 && y < 192) ? terrain_block(x, y, z) : AIR; };
    int complete = 0;
    CHECK(node_matches(lod, 1, 1, 0, 2, ref_node(1, 1, 0, 2, blk), &complete));
    CHECK(complete == 1);
    CHECK(node_matches(lod, 1, 3, -1, 0, ref_node(1, 3, -1, 0, blk), &complete));
    CHECK(node_matches(lod, 2, 1, 0, 0, ref_node(2, 1, 0, 0, blk), &complete));
    CHECK(complete == 1);
    CHECK(node_matches(lod, 3, 0, 0, 0, ref_node(3, 0, 0, 0, blk), &complete));
    CHECK(complete == 1);
    CHECK(node_matches(lod, 3, 0, -1, 0, ref_node(3, 0, -1, 0, blk), &complete));
    CHECK(complete == 1);
    CHECK(cv_lod_read_node(lod, 3, -1, 0, 0, nullptr, nullptr, nullptr) == 0);   // never generated
    CHECK(cv_lod_read_node(lod, 2, 2, 0, 1, nullptr, nullptr, nullptr) == 0);    // half outside the square
    cv_lod_destroy(lod);
    cv_world_destroy(w);
}

// ---- 5. incremental ------------------------------------------------------------------------------------------
void test_incremental() {
    const std::string dir = tmp_dir("incr");
    cv_world* w = make_world(-64, 192);
    gen_columns(w, 0, 7, 0, 7, -2, 5);
    cv_lod_desc d = lod_desc(3);
    d.store_dir = dir.c_str();
    cv_lod* lod = cv_lod_create(w, &d);
    std::map<NK, uint64_t> before, after, fresh;
    digests_all(lod, 3, 0, 7, -2, 5, before);
    cv_lod_stats st{};
    const cv_lod_event* ev = nullptr;
    struct Edit { int64_t x0, y0, z0, n; uint16_t id; };
    const Edit edits[] = {{10, 41, 10, 2, AIR}, {60, 30, 70, 4, AIR}, {100, 60, 100, 16, LOG}, {130, 0, 130, 64, AIR}};
    for (const Edit& e : edits) {
        std::set<std::tuple<int64_t, int64_t, int64_t>> touched;
        for (int64_t x = e.x0; x < e.x0 + e.n; ++x) {
            for (int64_t y = e.y0; y < e.y0 + e.n; ++y) {
                for (int64_t z = e.z0; z < e.z0 + e.n; ++z) {
                    if (cv_set(w, x, y, z, e.id) >= 0) touched.insert({x >> 5, y >> 5, z >> 5});
                }
            }
        }
        std::vector<int64_t> xyz;
        for (const auto& [cx, cy, cz] : touched) {
            xyz.push_back(cx);
            xyz.push_back(cy);
            xyz.push_back(cz);
        }
        CHECK(cv_lod_note_cubes(lod, xyz.data(), static_cast<int64_t>(touched.size())) == CV_OK);
        cv_lod_wait_idle(lod);
        cv_lod_tick(lod, nullptr, &ev);
    }
    digests_all(lod, 3, 0, 7, -2, 5, after);
    cv_lod_desc fd = lod_desc(3);
    cv_lod* fresh_lod = cv_lod_create(w, &fd);
    digests_all(fresh_lod, 3, 0, 7, -2, 5, fresh);
    CHECK(after == fresh);
    std::set<int> levels_changed;
    for (const auto& [k, v] : after) {
        if (before[k] != v) levels_changed.insert(std::get<0>(k));
    }
    CHECK(levels_changed.count(1) && levels_changed.count(2) && levels_changed.count(3));
    cv_lod_destroy(fresh_lod);

    // Unchanged cubes stop at level 1: noting 64 cubes rebuilds and writes nothing.
    cv_lod_tick(lod, nullptr, &ev);
    cv_lod_stats_get(lod, &st);
    const int64_t built0 = st.nodes_built_total, saved0 = st.nodes_saved_total;
    std::vector<int64_t> xyz;
    for (int64_t cx = 0; cx < 4; ++cx) {
        for (int64_t cz = 0; cz < 4; ++cz) {
            for (int64_t cy = 0; cy < 4; ++cy) {
                xyz.push_back(cx);
                xyz.push_back(cy);
                xyz.push_back(cz);
            }
        }
    }
    cv_lod_note_cubes(lod, xyz.data(), 64);
    cv_lod_wait_idle(lod);
    cv_lod_tick(lod, nullptr, &ev);
    cv_lod_stats_get(lod, &st);
    CHECK(st.nodes_built_total == built0);
    CHECK(st.nodes_saved_total == saved0);
    // A change that leaves level 1 unchanged (one block in the air: its level-1 cell stays air) rebuilds exactly
    // one node and propagates nothing.
    {
        std::map<NK, uint64_t> d0, d1;
        digests_all(lod, 3, 0, 7, -2, 5, d0);
        CHECK(cv_set(w, 205, 150, 205, LOG) == AIR);
        const int64_t c[3] = {205 >> 5, 150 >> 5, 205 >> 5};
        cv_lod_stats_get(lod, &st);
        const int64_t b0 = st.nodes_built_total;
        cv_lod_note_cubes(lod, c, 1);
        cv_lod_wait_idle(lod);
        cv_lod_tick(lod, nullptr, &ev);
        cv_lod_stats_get(lod, &st);
        digests_all(lod, 3, 0, 7, -2, 5, d1);
        CHECK(d1 == d0);
        CHECK(st.nodes_built_total - b0 == 1);
    }
    // An UNNOTED write to a loaded cube is found by the revision watch (cv_lod_wait_idle scans it).
    {
        std::map<NK, uint64_t> d0, d1, f;
        digests_all(lod, 3, 0, 7, -2, 5, d0);
        for (int64_t x = 64; x < 96; ++x) {
            for (int64_t z = 64; z < 96; ++z) cv_set(w, x, 100, z, STONE);   // a slab in the sky, nobody notes it
        }
        cv_lod_wait_idle(lod);
        digests_all(lod, 3, 0, 7, -2, 5, d1);
        CHECK(d1 != d0);
        cv_lod* f2 = cv_lod_create(w, &fd);
        digests_all(f2, 3, 0, 7, -2, 5, f);
        CHECK(d1 == f);
        cv_lod_destroy(f2);
    }
    cv_lod_destroy(lod);
    cv_world_destroy(w);
    std::filesystem::remove_all(dir);
}

// ---- 6. persistence -------------------------------------------------------------------------------------------
void test_persistence() {
    const std::string dir = tmp_dir("persist");
    cv_world* w = make_world(-64, 192);
    gen_columns(w, 0, 3, 0, 3, -2, 5);
    cv_lod_desc d = lod_desc(2);
    d.store_dir = dir.c_str();
    d.content_version = 7;
    cv_lod* lod = cv_lod_create(w, &d);
    CHECK(lod != nullptr);
    std::map<NK, uint64_t> a, b;
    digests_all(lod, 2, 0, 3, -2, 5, a);
    CHECK(cv_lod_flush(lod) == CV_OK);
    CHECK(cv_lod_destroy(lod) == CV_OK);
    // Reopen: everything loads, nothing is rebuilt.
    lod = cv_lod_create(w, &d);
    CHECK(lod != nullptr);
    digests_all(lod, 2, 0, 3, -2, 5, b);
    CHECK(a == b);
    cv_lod_stats st{};
    const cv_lod_event* ev = nullptr;
    cv_lod_tick(lod, nullptr, &ev);
    cv_lod_stats_get(lod, &st);
    CHECK(st.nodes_built_total == 0 && st.nodes_loaded_total > 0);
    CHECK(cv_lod_destroy(lod) == CV_OK);
    // Without any source (an empty world) the levels >= 1 still come from the store.
    cv_world* empty = make_world(-64, 192);
    lod = cv_lod_create(empty, &d);
    b.clear();
    digests_all(lod, 2, 0, 3, -2, 5, b);
    CHECK(a == b);
    cv_lod_destroy(lod);
    // Mismatch: another content_version fails; with RESET everything is rebuilt.
    cv_lod_desc d2 = d;
    d2.content_version = 8;
    CHECK(cv_lod_create(w, &d2) == nullptr);
    CHECK(std::strstr(cv_last_error(), "mismatch") != nullptr);
    d2.flags = CV_LOD_RESET_ON_MISMATCH;
    lod = cv_lod_create(w, &d2);
    CHECK(lod != nullptr);
    b.clear();
    digests_all(lod, 2, 0, 3, -2, 5, b);
    CHECK(a == b);
    cv_lod_tick(lod, nullptr, &ev);
    cv_lod_stats_get(lod, &st);
    CHECK(st.nodes_loaded_total == 0 && st.nodes_built_total > 0);
    CHECK(cv_lod_flush(lod) == CV_OK);
    cv_lod_destroy(lod);
    d2.flags = 0;
    lod = cv_lod_create(w, &d2);   // the meta says version 8 now
    CHECK(lod != nullptr);
    cv_lod_destroy(lod);
    // A corrupt record is detected, counted and rebuilt.
    {
        cv_store* s1 = cv_store_open((dir + "/l1").c_str());
        CHECK(s1 != nullptr);
        const uint8_t junk[40] = {1, 2, 3};
        CHECK(cv_store_write_cube(s1, 0, 0, 0, junk, 40) == CV_OK);
        cv_store_close(s1);
    }
    lod = cv_lod_create(w, &d2);
    uint64_t dg = 0;
    CHECK(cv_lod_read_node(lod, 1, 0, 0, 0, nullptr, nullptr, &dg) == 1);
    CHECK(dg == a[{1, 0, 0, 0}]);
    cv_lod_tick(lod, nullptr, &ev);
    cv_lod_stats_get(lod, &st);
    CHECK(st.corrupt_total >= 1 && st.nodes_built_total >= 1);
    cv_lod_destroy(lod);
    cv_lod_desc d3 = d2;
    d3.max_level = 3;   // another max_level: mismatch
    CHECK(cv_lod_create(w, &d3) == nullptr);
    cv_world_destroy(empty);
    cv_world_destroy(w);
    std::filesystem::remove_all(dir);
}

// ---- 7. meshes ------------------------------------------------------------------------------------------------
struct Quad {
    int x, y, z, face, w, h;
    uint32_t layer;
    bool alpha, lit;
};
std::vector<Quad> decode_quads(const uint8_t* p, int32_t n) {
    std::vector<Quad> out;
    for (int32_t i = 0; i < n; ++i) {
        uint32_t w0, w1, w2, w3;
        std::memcpy(&w0, p + 16 * i, 4);
        std::memcpy(&w1, p + 16 * i + 4, 4);
        std::memcpy(&w2, p + 16 * i + 8, 4);
        std::memcpy(&w3, p + 16 * i + 12, 4);
        Quad q;
        q.x = static_cast<int>(w0 & 511) - 8;
        q.z = static_cast<int>((w0 >> 9) & 511) - 8;
        q.y = static_cast<int>((w0 >> 18) & 2047) - 1024;
        q.face = static_cast<int>(w0 >> 29);
        q.w = static_cast<int>(w1 & 63);
        q.h = static_cast<int>((w1 >> 6) & 2047);
        q.layer = (w1 >> 17) & 4095;
        q.alpha = ((w1 >> 29) & 1) != 0;
        q.lit = (w1 >> 31) != 0;
        CHECK(((w1 >> 30) & 1) == 0 && w2 == 0 && w3 == 0);
        out.push_back(q);
    }
    return out;
}

// Unit faces (cell x, y, z, face) covered by quads; rectangles must not overlap.
std::set<std::tuple<int, int, int, int>> unit_faces(const std::vector<Quad>& qs) {
    std::set<std::tuple<int, int, int, int>> s;
    for (const Quad& q : qs) {
        for (int a = 0; a < q.w; ++a) {
            for (int b = 0; b < q.h; ++b) {
                int x = q.x, y = q.y, z = q.z;
                if (q.face <= 1) { x += a; z += b; }
                else if (q.face <= 3) { x += a; y += b; }
                else { z += a; y += b; }
                if (q.face == 0) --y;
                if (q.face == 3) --z;
                if (q.face == 4) --x;
                CHECK(s.insert({x, y, z, q.face}).second);
            }
        }
    }
    return s;
}

struct TestFaces final : FaceLookup {
    void face(uint16_t id, int f, uint32_t* layer, bool* alpha) const override {
        *layer = id == GRASS ? (f == 0 ? 20u : 21u) : id;
        *alpha = id == LEAVES;
    }
};

void test_mesh_basics() {
    TestFaces faces;
    const NodeData* none[6] = {nullptr, nullptr, nullptr, nullptr, nullptr, nullptr};
    auto one = node_of(2, [](int x, int y, int z) {
        return (x == 3 && y == 4 && z == 5) ? RefCell{kSolid | kFull, STONE} : RefCell{kAir, AIR};
    });
    MeshBuild m;
    mesh_node(*one, none, false, 3, faces, m);
    auto qs = decode_quads(m.quads.data(), m.quad_count);
    CHECK(m.quad_count == 6 && m.water_count == 0);
    for (const Quad& q : qs) {
        CHECK(q.w == 1 && q.h == 1 && q.layer == STONE && q.lit && !q.alpha);
        const int ex = q.face == 4 ? 4 : 3, ey = q.face == 0 ? 5 : 4, ez = q.face == 3 ? 6 : 5;
        CHECK(q.x == ex && q.y == ey && q.z == ez);
    }
    CHECK(m.aabb_min[0] == 3 && m.aabb_max[0] == 4 && m.aabb_min[1] == 4 && m.aabb_max[1] == 5 &&
          m.aabb_min[2] == 5 && m.aabb_max[2] == 6);
    // A flat grass layer: greedy -> 1 top quad 32x32 (layer 20), 1 bottom, 4 side strips.
    auto floor = node_of(0, [](int, int y, int) { return y == 10 ? RefCell{kSolid | kFull, GRASS} : RefCell{kAir, AIR}; });
    mesh_node(*floor, none, false, 3, faces, m);
    qs = decode_quads(m.quads.data(), m.quad_count);
    CHECK(m.quad_count == 6);
    for (const Quad& q : qs) {
        if (q.face == 0) CHECK(q.w == 32 && q.h == 32 && q.layer == 20 && q.y == 11);
        if (q.face == 1) CHECK(q.w == 32 && q.h == 32 && q.layer == 21 && q.y == 10);
        if (q.face >= 2) CHECK(q.w == 32 && q.h == 1 && q.layer == 21 && q.y == 10);
    }
    // Leaves carry the alpha bit.
    auto leaf = node_of(0, [](int x, int y, int z) { return (x == 0 && y == 0 && z == 0) ? RefCell{kSolid | kFull, LEAVES} : RefCell{kAir, AIR}; });
    mesh_node(*leaf, none, false, 3, faces, m);
    for (const Quad& q : decode_quads(m.quads.data(), m.quad_count)) CHECK(q.alpha && q.layer == LEAVES);
    // Uniform FULL neighbour above + below_solid: a uniform solid node shows only its sides.
    NodeData full;
    node_uniform(full, NodeKey{0, 0, 1, 0}, STONE, kSolid | kFull, true);
    NodeData solid_node;
    node_uniform(solid_node, NodeKey{0, 0, 0, 0}, STONE, kSolid | kFull, true);
    const NodeData* nb[6] = {&full, nullptr, nullptr, nullptr, nullptr, nullptr};
    mesh_node(solid_node, nb, true, 3, faces, m);
    qs = decode_quads(m.quads.data(), m.quad_count);
    CHECK(m.quad_count == 4);
    for (const Quad& q : qs) CHECK(q.face >= 2 && q.w == 32 && q.h == 32);
    const NodeData* all[6] = {&full, &full, &full, &full, &full, &full};
    mesh_node(solid_node, all, false, 3, faces, m);
    CHECK(m.empty());
    // A side neighbour whose boundary row is FULL but whose level+1 ancestors are air (a 1x1 line) covers only
    // when no coarser level can be drawn (cover_levels 0).
    auto line = node_of(0, [](int, int y, int z) { return (y == 0 && z == 31) ? RefCell{kSolid | kFull, STONE} : RefCell{kAir, AIR}; });
    auto slab = node_of(0, [](int, int y, int) { return y == 0 ? RefCell{kSolid | kFull, STONE} : RefCell{kAir, AIR}; });
    const NodeData* side[6] = {nullptr, nullptr, line.get(), nullptr, nullptr, nullptr};
    auto has_face = [&](int f) {
        for (const Quad& q : decode_quads(m.quads.data(), m.quad_count)) {
            if (q.face == f) return true;
        }
        return false;
    };
    mesh_node(*slab, side, false, 3, faces, m);
    CHECK(has_face(2));
    mesh_node(*slab, side, false, 0, faces, m);
    CHECK(!has_face(2));
    // ...whereas a full single LAYER next door stays solid at every level (>= 4 rule) and covers.
    const NodeData* side2[6] = {nullptr, nullptr, slab.get(), nullptr, nullptr, nullptr};
    mesh_node(*slab, side2, false, 5, faces, m);
    CHECK(!has_face(2));
    // Water: a lake under air -> water tops only (no sides), the seabed's tops drawn under the fluid.
    auto lake = node_of(0, [](int, int y, int) {
        if (y < 4) return RefCell{kSolid | kFull, DIRT};
        if (y < 8) return RefCell{kFluid, WATER};
        return RefCell{kAir, AIR};
    });
    mesh_node(*lake, none, true, 3, faces, m);
    auto wq = decode_quads(m.water.data(), m.water_count);
    CHECK(m.water_count == 1 && wq[0].face == 0 && wq[0].y == 8 && wq[0].w == 32 && wq[0].h == 32 && wq[0].layer == WATER);
    bool seabed = false;
    for (const Quad& q : decode_quads(m.quads.data(), m.quad_count)) seabed |= q.face == 0 && q.y == 4;
    CHECK(seabed);
    NodeData water_above;
    node_uniform(water_above, NodeKey{0, 0, 1, 0}, WATER, kFluid, true);
    const NodeData* wa[6] = {&water_above, nullptr, nullptr, nullptr, nullptr, nullptr};
    auto deep = node_of(0, [](int, int, int) { return RefCell{kFluid, WATER}; });
    mesh_node(*deep, wa, true, 3, faces, m);
    CHECK(m.water_count == 0 && m.quad_count == 0);
    // Greedy covers exactly the brute-force face set without overlaps.
    std::mt19937_64 rng(3);
    std::vector<RefCell> rnd(kCells);
    for (auto& c : rnd) {
        const int r = static_cast<int>(rng() % 6);
        c = r < 2 ? RefCell{kSolid | kFull, STONE} : r < 3 ? RefCell{kSolid | kFull, GRASS} : r < 4 ? RefCell{kFluid, WATER} : RefCell{kAir, AIR};
    }
    auto rn = node_of(1, [&](int x, int y, int z) { return rnd[static_cast<size_t>(CV_CELL_INDEX(x, y, z))]; });
    mesh_node(*rn, none, false, 3, faces, m);
    const auto got = unit_faces(decode_quads(m.quads.data(), m.quad_count));
    std::set<std::tuple<int, int, int, int>> expect;
    const int dx[6] = {0, 0, 0, 0, 1, -1}, dy[6] = {1, -1, 0, 0, 0, 0}, dz[6] = {0, 0, -1, 1, 0, 0};
    for (int y = 0; y < 32; ++y) {
        for (int z = 0; z < 32; ++z) {
            for (int x = 0; x < 32; ++x) {
                if ((rnd[static_cast<size_t>(CV_CELL_INDEX(x, y, z))].cell & 3) != kSolid) continue;
                for (int f = 0; f < 6; ++f) {
                    const int nx = x + dx[f], ny = y + dy[f], nz = z + dz[f];
                    const bool out = nx < 0 || ny < 0 || nz < 0 || nx > 31 || ny > 31 || nz > 31;
                    if (out || (rnd[static_cast<size_t>(CV_CELL_INDEX(nx, ny, nz))].cell & 3) != kSolid) expect.insert({x, y, z, f});
                }
            }
        }
    }
    CHECK(got == expect);
    CHECK(m.quad_count < static_cast<int32_t>(expect.size()));   // some merging happened
}

// CRACK-FREE: in a terrain world, for every node A at level k (0..2) every SOLID side-boundary cell whose outward
// face A's mesh culled faces a volume that is SOLID at every level j in [0, k + 3] - whatever level the
// neighbour is drawn at, the culled face is hidden by solid geometry, never a hole.
void test_crack_free() {
    cv_world* w = make_world(-64, 192);
    gen_columns(w, 0, 7, 0, 7, -2, 5);
    cv_lod_desc d = lod_desc(5);
    cv_lod* lod = cv_lod_create(w, &d);
    int culled_faces = 0, checked_nodes = 0;
    std::map<NK, std::vector<uint8_t>> cache;
    auto cells_of = [&](int lv, int64_t x, int64_t y, int64_t z) -> const std::vector<uint8_t>& {
        const NK key{lv, x, y, z};
        auto it = cache.find(key);
        if (it == cache.end()) {
            std::vector<uint8_t> c(CV_CUBE_CELLS);
            cv_lod_read_node(lod, lv, x, y, z, nullptr, c.data(), nullptr);
            it = cache.emplace(key, std::move(c)).first;
        }
        return it->second;
    };
    // Every level-j cell overlapping the block box [b, b + s)^3 is SOLID.
    auto solid_at = [&](int j, int64_t bx, int64_t by, int64_t bz, int64_t s) {
        const int64_t cs = int64_t{1} << j;
        const int64_t ext = std::max(s, cs);
        for (int64_t x = bx; x < bx + ext; x += cs) {
            for (int64_t y = by; y < by + ext; y += cs) {
                for (int64_t z = bz; z < bz + ext; z += cs) {
                    const int64_t cx = cenda::floor_div64(x, cs), cy = cenda::floor_div64(y, cs), cz = cenda::floor_div64(z, cs);
                    const auto& n = cells_of(j, cenda::floor_div64(cx, 32), cenda::floor_div64(cy, 32), cenda::floor_div64(cz, 32));
                    const uint8_t c = n[static_cast<size_t>(CV_CELL_INDEX(static_cast<int>(cx & 31), static_cast<int>(cy & 31), static_cast<int>(cz & 31)))];
                    if ((c & 3) != kSolid) return false;
                }
            }
        }
        return true;
    };
    for (int k = 0; k <= 2; ++k) {
        const int64_t per = 8 >> k;
        for (int64_t nx = 0; nx < per; ++nx) {
            for (int64_t nz = 0; nz < per; ++nz) {
                for (int64_t ny = (-2 >> k); ny <= (5 >> k); ++ny) {
                    cv_lod_mesh mesh{};
                    CHECK(cv_lod_build_mesh(lod, k, nx, ny, nz, &mesh) == CV_OK);
                    ++checked_nodes;
                    std::set<std::tuple<int, int, int, int>> drawn;
                    for (const Quad& q : decode_quads(mesh.quads, mesh.quad_count)) {
                        for (int a = 0; a < q.w; ++a) {
                            for (int b = 0; b < q.h; ++b) {
                                int x = q.x, y = q.y, z = q.z;
                                if (q.face <= 1) { x += a; z += b; }
                                else if (q.face <= 3) { x += a; y += b; }
                                else { z += a; y += b; }
                                if (q.face == 0) --y;
                                if (q.face == 3) --z;
                                if (q.face == 4) --x;
                                drawn.insert({x, y, z, q.face});
                            }
                        }
                    }
                    const auto& me = cells_of(k, nx, ny, nz);
                    const int64_t cs = int64_t{1} << k;
                    for (int f = 2; f < 6; ++f) {
                        for (int v = 0; v < 32; ++v) {
                            for (int u = 0; u < 32; ++u) {
                                const int s = (f == 3 || f == 4) ? 31 : 0;
                                int x, y, z;
                                if (f <= 3) { x = u; y = v; z = s; } else { x = s; y = v; z = u; }
                                if ((me[static_cast<size_t>(CV_CELL_INDEX(x, y, z))] & 3) != kSolid) continue;
                                if (drawn.count({x, y, z, f}) != 0) continue;
                                ++culled_faces;
                                int64_t bx = (nx * 32 + x) * cs, by = (ny * 32 + y) * cs, bz = (nz * 32 + z) * cs;
                                if (f == 2) bz -= cs;
                                if (f == 3) bz += cs;
                                if (f == 4) bx += cs;
                                if (f == 5) bx -= cs;
                                bool ok = true;
                                for (int j = 0; j <= std::min(5, k + 3) && ok; ++j) ok = solid_at(j, bx, by, bz, cs);
                                CHECK(ok);
                            }
                        }
                    }
                }
            }
        }
    }
    std::printf("  crack-free: %d nodes, %d culled side faces verified\n", checked_nodes, culled_faces);
    CHECK(culled_faces > 1000 && checked_nodes > 50);
    cv_lod_destroy(lod);
    cv_world_destroy(w);
}

// ---- 8. selection + handover -------------------------------------------------------------------------------------
struct Host {
    std::map<NK, uint64_t> drawn;                                // node -> digest
    std::set<std::tuple<int, int64_t, int64_t>> fallback;
    int shows = 0, hides = 0, updates = 0, fb_on = 0, fb_off = 0;
    void apply(cv_lod* lod, const cv_lod_event* ev, int32_t n) {
        for (int32_t i = 0; i < n; ++i) {
            const cv_lod_event& e = ev[i];
            const NK nk{e.level, e.x, e.y, e.z};
            switch (e.type) {
                case CV_LOD_EV_SHOW: {
                    CHECK(drawn.count(nk) == 0);
                    const cv_lod_mesh* m = cv_lod_mesh_get(lod, e.mesh);
                    CHECK(m != nullptr);
                    if (m != nullptr) {
                        CHECK(m->level == e.level && m->nx == e.x && m->ny == e.y && m->nz == e.z);
                        CHECK((m->quad_count + m->water_count) > 0 && (m->flags & 1u) == 0);
                        CHECK(m->origin[0] == e.x * (int64_t{32} << e.level) && m->cell_size == (1 << e.level));
                        drawn[nk] = m->digest;
                    }
                    ++shows;
                    break;
                }
                case CV_LOD_EV_UPDATE: {
                    CHECK(drawn.count(nk) == 1);
                    const cv_lod_mesh* m = cv_lod_mesh_get(lod, e.mesh);
                    CHECK(m != nullptr);
                    if (m != nullptr) drawn[nk] = m->digest;
                    ++updates;
                    break;
                }
                case CV_LOD_EV_HIDE:
                    CHECK(drawn.erase(nk) == 1);
                    ++hides;
                    break;
                case CV_LOD_EV_FALLBACK_ON:
                    CHECK(e.y == 0 && e.mesh == -1);
                    CHECK(fallback.insert({e.level, e.x, e.z}).second);
                    ++fb_on;
                    break;
                case CV_LOD_EV_FALLBACK_OFF:
                    CHECK(fallback.erase({e.level, e.x, e.z}) == 1);
                    ++fb_off;
                    break;
                default:
                    CHECK(false);
            }
        }
    }
};

double foot_dist(int64_t fx, int64_t fz, int64_t vx, int64_t vz, int level = 0) {
    const double s = static_cast<double>(int64_t{32} << level);
    const double x0 = static_cast<double>(fx) * s, z0 = static_cast<double>(fz) * s;
    const double px = static_cast<double>(vx) + 0.5, pz = static_cast<double>(vz) + 0.5;
    const double dx = std::max({x0 - px, 0.0, px - (x0 + s)});
    const double dz = std::max({z0 - pz, 0.0, pz - (z0 + s)});
    return std::sqrt(dx * dx + dz * dz);
}

// Exact cover: every column within the radius is covered exactly once (READY, a drawn stack footprint or a
// fallback footprint) and nothing is drawn over a READY column.
bool check_cover(const Host& h, const std::set<std::pair<int64_t, int64_t>>& ready, int64_t vx, int64_t vz, int radius,
                 int64_t c0, int64_t c1, int* uncovered_out = nullptr) {
    std::set<std::tuple<int, int64_t, int64_t>> feet;
    for (const auto& [k, dg] : h.drawn) feet.insert({std::get<0>(k), std::get<1>(k), std::get<3>(k)});
    bool ok = true;
    int uncovered = 0;
    for (int64_t cx = c0; cx <= c1; ++cx) {
        for (int64_t cz = c0; cz <= c1; ++cz) {
            const bool is_ready = ready.count({cx, cz}) != 0;
            int n = is_ready ? 1 : 0;
            int drawn_here = 0;
            for (int lv = 0; lv <= 8; ++lv) {
                if (feet.count({lv, cx >> lv, cz >> lv})) {
                    ++n;
                    ++drawn_here;
                }
                if (h.fallback.count({lv, cx >> lv, cz >> lv})) ++n;
            }
            if (is_ready && drawn_here > 0) ok = false;
            if (n > 1) ok = false;
            if (foot_dist(cx, cz, vx, vz) <= radius && n == 0) {
                ok = false;
                ++uncovered;
            }
        }
    }
    if (uncovered_out != nullptr) *uncovered_out = uncovered;
    return ok;
}

// Ticks until the LOD is idle (3 quiet ticks in a row). The tick budget alone made this machine-dependent: on a slow
// 2-core CI runner (Windows, 2026-11-20) the workers had not finished when max_ticks ran out, the helper returned
// silently and every later cover check failed in a cascade. Now it keeps ticking past max_ticks until a wall-clock
// deadline (120 s, far above any real convergence) and reports a stall as ONE clear failure.
void run_until_idle(cv_lod* lod, Host& h, int max_ticks = 4000) {
    const cv_lod_event* ev = nullptr;
    int quiet = 0;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(120);
    int t = 0;
    for (; quiet < 3 && (t < max_ticks || std::chrono::steady_clock::now() < deadline); ++t) {
        const int32_t n = cv_lod_tick(lod, nullptr, &ev);
        h.apply(lod, ev, n);
        cv_lod_stats st{};
        cv_lod_stats_get(lod, &st);
        if (n == 0 && st.jobs_queued == 0 && st.jobs_running == 0 && st.nodes_dirty == 0) {
            ++quiet;
        } else {
            quiet = 0;
        }
        if (n == 0) std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    if (quiet < 3) std::printf("run_until_idle: LOD still busy after %d ticks (120 s deadline)\n", t);
    CHECK(quiet >= 3);
}

int fallbacks_inside(const Host& h, int64_t c0, int64_t c1) {
    int n = 0;
    for (const auto& [lv, fx, fz] : h.fallback) {
        const int64_t x0 = fx << lv, x1 = ((fx + 1) << lv) - 1, z0 = fz << lv, z1 = ((fz + 1) << lv) - 1;
        if (x0 >= c0 && x1 <= c1 && z0 >= c0 && z1 <= c1) ++n;
    }
    return n;
}

void test_selection() {
    // 32x32 generated columns (blocks 0..1023), range [-64, 192): the band cubes; deeper/higher are synthesized.
    cv_world* w = make_world(-64, 192);
    gen_columns(w, 0, 31, 0, 31, 0, 2);
    cv_lod_desc d = lod_desc(4);
    d.worker_threads = 4;
    cv_lod* lod = cv_lod_create(w, &d);
    cv_lod_policy p{};
    p.blocks_per_cell = 64;
    p.max_level = 3;
    p.radius = 700;
    p.incomplete_depth = 2;
    p.standby_from = -1;
    cv_lod_viewer v{512, 60, 512};
    CHECK(cv_lod_set_view(lod, &v, 1, &p) == CV_OK);
    Host h;
    std::set<std::pair<int64_t, int64_t>> ready;
    const cv_lod_event* ev = nullptr;
    int32_t n = cv_lod_tick(lod, nullptr, &ev);   // nothing built yet: FALLBACK everywhere, already exact
    h.apply(lod, ev, n);
    CHECK(n > 0 && h.shows == 0 && h.fb_on > 0);
    CHECK(check_cover(h, ready, v.x, v.z, p.radius, -40, 72));
    run_until_idle(lod, h);
    CHECK(check_cover(h, ready, v.x, v.z, p.radius, -40, 72));
    CHECK(h.drawn.size() > 20);
    CHECK(fallbacks_inside(h, 0, 31) == 0);   // the generated square is all octree; fallbacks only outside
    // Band rule: a drawn footprint's level is its leaf level by distance.
    for (const auto& [k, dg] : h.drawn) {
        const int lv = std::get<0>(k);
        const int64_t fx = std::get<1>(k), fz = std::get<3>(k);
        CHECK(lv == 0 || foot_dist(fx, fz, v.x, v.z, lv) >= std::ldexp(64.0, lv));
        if (lv < p.max_level) CHECK(foot_dist(fx >> 1, fz >> 1, v.x, v.z, lv + 1) < std::ldexp(64.0, lv + 1));
    }
    // Horizontal 2:1 balance between drawn footprints.
    {
        std::map<std::pair<int64_t, int64_t>, int> col_level;
        for (const auto& [k, dg] : h.drawn) {
            const int lv = std::get<0>(k);
            for (int64_t a = 0; a < (int64_t{1} << lv); ++a) {
                for (int64_t b = 0; b < (int64_t{1} << lv); ++b) {
                    col_level[{(std::get<1>(k) << lv) + a, (std::get<3>(k) << lv) + b}] = lv;
                }
            }
        }
        for (const auto& [c, lv] : col_level) {
            for (const auto& [ox, oz] : {std::pair<int64_t, int64_t>{1, 0}, std::pair<int64_t, int64_t>{0, 1}}) {
                auto it = col_level.find({c.first + ox, c.second + oz});
                if (it != col_level.end()) CHECK(std::abs(it->second - lv) <= 1);
            }
        }
    }
    // A drawn stack is drawn whole: every non-empty node of its footprint is shown.
    {
        std::set<std::tuple<int, int64_t, int64_t>> feet;
        for (const auto& [k, dg] : h.drawn) feet.insert({std::get<0>(k), std::get<1>(k), std::get<3>(k)});
        int checked = 0;
        for (const auto& [lv, fx, fz] : feet) {
            if (++checked > 16) break;
            const int64_t span = int64_t{32} << lv;
            for (int64_t ny = cenda::floor_div64(-64, span); ny <= cenda::floor_div64(191, span); ++ny) {
                cv_lod_mesh m{};
                cv_lod_build_mesh(lod, lv, fx, ny, fz, &m);
                const bool non_empty = (m.quad_count + m.water_count) > 0;
                CHECK(non_empty == (h.drawn.count({lv, fx, ny, fz}) == 1));
            }
        }
    }

    // READY detail disc (columns within 160 blocks): the LOD steps back in the SAME tick, exact cover holds.
    for (int64_t cx = 0; cx < 32; ++cx) {
        for (int64_t cz = 0; cz < 32; ++cz) {
            if (foot_dist(cx, cz, v.x, v.z) <= 160) {
                ready.insert({cx, cz});
                cv_lod_set_column_ready(lod, cx, cz, 1);
            }
        }
    }
    n = cv_lod_tick(lod, nullptr, &ev);
    h.apply(lod, ev, n);
    CHECK(check_cover(h, ready, v.x, v.z, p.radius, -40, 72));
    run_until_idle(lod, h);
    CHECK(check_cover(h, ready, v.x, v.z, p.radius, -40, 72));
    // UNREADY of an edge column: covered in the same tick (stack or fallback), then by octree nodes.
    {
        int64_t ux = -1, uz = -1;
        for (const auto& c : ready) {
            if (foot_dist(c.first, c.second, v.x, v.z) > 130) {
                ux = c.first;
                uz = c.second;
                break;
            }
        }
        CHECK(ux >= 0);
        ready.erase({ux, uz});
        cv_lod_set_column_ready(lod, ux, uz, 0);
        n = cv_lod_tick(lod, nullptr, &ev);
        h.apply(lod, ev, n);
        int unc = 0;
        CHECK(check_cover(h, ready, v.x, v.z, p.radius, -40, 72, &unc));
        CHECK(unc == 0);
        run_until_idle(lod, h);
        CHECK(check_cover(h, ready, v.x, v.z, p.radius, -40, 72));
        bool drawn_col = false;
        for (const auto& [k, dg] : h.drawn) {
            const int lv = std::get<0>(k);
            drawn_col |= (ux >> lv) == std::get<1>(k) && (uz >> lv) == std::get<3>(k);
        }
        CHECK(drawn_col);
    }
    // STANDBY: ready leaves get meshes natively; an UNREADY inside the level-0 band then SHOWs nodes in the same
    // tick (no fallback).
    {
        p.standby_from = 0;
        cv_lod_set_view(lod, &v, 1, &p);
        run_until_idle(lod, h);
        cv_lod_stats st{};
        cv_lod_stats_get(lod, &st);
        CHECK(st.standby_nodes > 0);
        int64_t ux = -1, uz = -1;
        for (const auto& c : ready) {
            if (foot_dist(c.first, c.second, v.x, v.z) < 100) {
                ux = c.first;
                uz = c.second;
                break;
            }
        }
        CHECK(ux >= 0);
        ready.erase({ux, uz});
        cv_lod_set_column_ready(lod, ux, uz, 0);
        const int fb_before = h.fb_on, shows_before = h.shows;
        n = cv_lod_tick(lod, nullptr, &ev);
        h.apply(lod, ev, n);
        CHECK(check_cover(h, ready, v.x, v.z, p.radius, -40, 72));
        CHECK(h.fb_on == fb_before && h.shows > shows_before);
        p.standby_from = -1;
        cv_lod_set_view(lod, &v, 1, &p);
    }
    for (const auto& c : ready) cv_lod_set_column_ready(lod, c.first, c.second, 0);
    ready.clear();
    run_until_idle(lod, h);
    CHECK(fallbacks_inside(h, 0, 31) == 0);
    // Move: every tick until settled the cover stays exact and no fallback appears inside the square (the old
    // parents / children stay until their replacements are built).
    {
        cv_lod_viewer v2{300, 60, 700};
        cv_lod_set_view(lod, &v2, 1, &p);
        for (int t = 0; t < 300; ++t) {
            n = cv_lod_tick(lod, nullptr, &ev);
            h.apply(lod, ev, n);
            CHECK(check_cover(h, ready, v2.x, v2.z, p.radius, -40, 72));
            CHECK(fallbacks_inside(h, 0, 31) == 0);
            if (n == 0) std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        run_until_idle(lod, h);
        CHECK(check_cover(h, ready, v2.x, v2.z, p.radius, -40, 72));
        CHECK(fallbacks_inside(h, 0, 31) == 0);
        v = v2;
    }
    // An edit inside a drawn node -> UPDATE (no hide / show).
    {
        const int upd0 = h.updates, hides0 = h.hides;
        for (int64_t x = 290; x < 310; ++x) {
            for (int64_t z = 690; z < 710; ++z) {
                for (int64_t y = 30; y < 64; ++y) cv_set(w, x, y, z, AIR);
            }
        }
        const int64_t c[6] = {9, 0, 21, 9, 1, 21};
        cv_lod_note_cubes(lod, c, 2);
        run_until_idle(lod, h);
        CHECK(h.updates > upd0 && h.hides == hides0);
        CHECK(check_cover(h, ready, v.x, v.z, p.radius, -40, 72));
    }
    // Budget: a teleport applies roots progressively; the cover is exact at every tick.
    {
        cv_lod_viewer v3{100, 60, 100};
        cv_lod_set_view(lod, &v3, 1, &p);
        cv_lod_budget b{};
        b.max_mesh_bytes = 4096;
        int ticks = 0, busy = 0;
        for (int quiet = 0; ticks < 5000 && quiet < 3; ++ticks) {
            n = cv_lod_tick(lod, &b, &ev);
            h.apply(lod, ev, n);
            CHECK(check_cover(h, ready, v3.x, v3.z, p.radius, -40, 72));
            busy += n > 0 ? 1 : 0;
            cv_lod_stats st{};
            cv_lod_stats_get(lod, &st);
            quiet = (n == 0 && st.jobs_queued == 0 && st.jobs_running == 0) ? quiet + 1 : 0;
            if (n == 0) std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        CHECK(busy > 2);
        CHECK(fallbacks_inside(h, 0, 31) == 0);
    }
    // No viewers: everything hidden.
    cv_lod_set_view(lod, nullptr, 0, &p);
    n = cv_lod_tick(lod, nullptr, &ev);
    h.apply(lod, ev, n);
    CHECK(h.drawn.empty() && h.fallback.empty());
    cv_lod_destroy(lod);
    cv_world_destroy(w);
}

// ---- 9. stream integration ----------------------------------------------------------------------------------------
void test_stream_integration() {
    const std::string dir = tmp_dir("stream");
    const cv_generator* gen = cv_test_toy_generator_create(42);
    cv_world_desc wd{};
    wd.min_y = -384;
    wd.max_y = 640;
    wd.air_id = 0;
    cv_world* w = cv_world_create_with_generator(&wd, gen);
    const uint16_t tf[6] = {0, kSolidF, kSolidF, CV_BLOCK_TRANSLUCENT | CV_BLOCK_TRANSPARENT,
                            CV_BLOCK_CUBE | CV_BLOCK_OPAQUE | CV_BLOCK_VIS_OPAQUE, CV_BLOCK_CUBE | CV_BLOCK_TRANSPARENT};
    cv_block_table_set(w, tf, 6);
    cv_layer_define(w, 0, CV_LAYER_SAVED | CV_LAYER_NETWORK);
    cv_fluid_desc fd{};
    fd.layer = 0;
    fd.fluid_id = 3;
    cv_fluid_set(w, &fd);
    cv_store* store = cv_store_open((dir + "/cubes").c_str());
    cv_stream_desc sd{};
    sd.store = store;
    sd.generator = gen;
    sd.worker_threads = 4;
    sd.mesh_flags = CV_MESH_GREEDY | CV_MESH_SMOOTH_LIGHTING;
    cv_stream* s = cv_stream_create(w, &sd);
    const std::string lod_dir = dir + "/lod";
    cv_lod_desc ld{};
    ld.max_level = 3;
    ld.worker_threads = 2;
    ld.fill_id = 1;
    ld.cube_store = store;
    ld.store_dir = lod_dir.c_str();
    cv_lod* lod = cv_lod_create(w, &ld);
    CHECK(lod != nullptr);
    cv_center c{0, 40, 0, 96, 64, 16, 8};
    cv_stream_set_centers(s, &c, 1);
    cv_lod_policy p{};
    p.blocks_per_cell = 48;
    p.max_level = 3;
    p.radius = 400;
    p.incomplete_depth = 2;
    p.standby_from = -1;
    cv_lod_viewer v{0, 40, 0};
    cv_lod_set_view(lod, &v, 1, &p);
    Host h;
    std::set<std::pair<int64_t, int64_t>> ready;
    int settled = 0;
    for (int t = 0; t < 6000 && settled < 30; ++t) {
        const cv_event* sev = nullptr;
        const int32_t sn = cv_stream_tick(s, nullptr, &sev);
        for (int32_t i = 0; i < sn; ++i) {
            if (sev[i].type == CV_EV_COLUMN_READY) ready.insert({sev[i].cx, sev[i].cz});
            if (sev[i].type == CV_EV_COLUMN_UNREADY) ready.erase({sev[i].cx, sev[i].cz});
        }
        CHECK(cv_lod_note_events(lod, sev, sn) == CV_OK);
        const cv_lod_event* lev = nullptr;
        const int32_t ln = cv_lod_tick(lod, nullptr, &lev);
        h.apply(lod, lev, ln);
        int unc = 0;
        CHECK(check_cover(h, ready, v.x, v.z, p.radius, -16, 16, &unc));   // every tick
        CHECK(unc == 0);
        cv_stream_stats ss{};
        cv_stream_stats_get(s, &ss);
        settled = (sn == 0 && ln == 0 && ss.cubes_generating == 0 && ss.cubes_meshing == 0 && ss.plan_pending == 0) ? settled + 1 : 0;
        if (sn == 0 && ln == 0) std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    CHECK(!ready.empty());
    cv_lod_wait_idle(lod);
    // The incremental LOD equals a fresh one over the same world + store.
    std::map<NK, uint64_t> a, b;
    digests_all(lod, 3, -4, 3, -2, 5, a);
    cv_lod_desc fdsc{};
    fdsc.max_level = 3;
    fdsc.fill_id = 1;
    fdsc.cube_store = store;
    cv_lod* fresh = cv_lod_create(w, &fdsc);
    digests_all(fresh, 3, -4, 3, -2, 5, b);
    CHECK(a == b);
    if (a != b) {
        int shown = 0;
        for (const auto& [k, dg] : a) {
            if (b[k] != dg && shown++ < 8) {
                const int ra = cv_lod_read_node(lod, std::get<0>(k), std::get<1>(k), std::get<2>(k), std::get<3>(k), nullptr, nullptr, nullptr);
                const int rb = cv_lod_read_node(fresh, std::get<0>(k), std::get<1>(k), std::get<2>(k), std::get<3>(k), nullptr, nullptr, nullptr);
                std::printf("  differs: L%d (%lld %lld %lld) incremental complete=%d fresh complete=%d\n", std::get<0>(k),
                            static_cast<long long>(std::get<1>(k)), static_cast<long long>(std::get<2>(k)),
                            static_cast<long long>(std::get<3>(k)), ra, rb);
            }
        }
    }
    cv_lod_destroy(fresh);
    // Leave: cubes unload (saved); the LOD shows octree nodes where the detail was (from the stored records).
    cv_stream_set_centers(s, nullptr, 0);   // nobody left: everything unloads (saved)
    for (int t = 0; t < 6000; ++t) {
        const cv_event* sev = nullptr;
        const int32_t sn = cv_stream_tick(s, nullptr, &sev);
        for (int32_t i = 0; i < sn; ++i) {
            if (sev[i].type == CV_EV_COLUMN_UNREADY) ready.erase({sev[i].cx, sev[i].cz});
            if (sev[i].type == CV_EV_COLUMN_READY) ready.insert({sev[i].cx, sev[i].cz});
            if (sev[i].type == CV_EV_UNLOADED) CHECK(ready.count({sev[i].cx, sev[i].cz}) == 0);
        }
        cv_lod_note_events(lod, sev, sn);
        const cv_lod_event* lev = nullptr;
        const int32_t ln = cv_lod_tick(lod, nullptr, &lev);
        h.apply(lod, lev, ln);
        int unc = 0;
        CHECK(check_cover(h, ready, v.x, v.z, p.radius, -16, 16, &unc));
        CHECK(unc == 0);
        if (cv_cube_count(w) == 0 && ready.empty()) break;
        if (sn == 0) std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    CHECK(ready.empty() && cv_cube_count(w) == 0);
    run_until_idle(lod, h);
    int unc = 0;
    CHECK(check_cover(h, ready, v.x, v.z, p.radius, -16, 16, &unc));
    CHECK(h.drawn.size() > 4);
    std::map<NK, uint64_t> c2;
    cv_lod* fresh2 = cv_lod_create(w, &fdsc);   // stored records only now
    digests_all(fresh2, 3, -4, 3, -2, 5, c2);
    CHECK(c2 == a);
    cv_lod_destroy(fresh2);
    cv_stream_destroy(s);
    CHECK(cv_lod_destroy(lod) == CV_OK);
    cv_store_close(store);
    cv_world_destroy(w);
    cv_test_toy_generator_destroy(gen);
    std::filesystem::remove_all(dir);
}

// ---- 10. concurrency ---------------------------------------------------------------------------------------------
void test_concurrency() {
    const std::string dir = tmp_dir("conc");
    cv_world* w = make_world(-64, 192);
    gen_columns(w, 0, 7, 0, 7, 0, 2);
    cv_lod_desc d = lod_desc(3);
    d.worker_threads = 4;
    d.store_dir = dir.c_str();
    d.cache_bytes = 4 << 20;   // small: eviction under load
    cv_lod* lod = cv_lod_create(w, &d);
    cv_lod_policy p{};
    p.blocks_per_cell = 48;
    p.max_level = 3;
    p.radius = 300;
    p.incomplete_depth = 1;
    p.standby_from = 100;
    cv_lod_viewer v{128, 50, 128};
    cv_lod_set_view(lod, &v, 1, &p);
    std::atomic<bool> stop{false};
    std::vector<std::thread> editors;
    for (int t = 0; t < 3; ++t) {
        editors.emplace_back([&, t] {
            std::mt19937_64 rng(static_cast<uint64_t>(t) + 100);
            while (!stop.load()) {
                const int64_t x = static_cast<int64_t>(rng() % 256), z = static_cast<int64_t>(rng() % 256);
                const int64_t y = 20 + static_cast<int64_t>(rng() % 50);
                cv_set(w, x, y, z, (rng() & 1) ? AIR : LOG);
                if ((rng() & 3) != 0) {   // a quarter of the writes are never noted: the watch finds them
                    const int64_t c[3] = {x >> 5, y >> 5, z >> 5};
                    cv_lod_note_cubes(lod, c, 1);
                }
                if ((rng() % 16) == 0) {
                    uint64_t dg = 0;
                    cv_lod_read_node(lod, static_cast<int32_t>(rng() % 4), static_cast<int64_t>(rng() % 2), 0, 0, nullptr, nullptr, &dg);
                }
            }
        });
    }
    std::thread viewer([&] {
        std::mt19937_64 rng(9);
        while (!stop.load()) {
            cv_lod_viewer vv{static_cast<int64_t>(rng() % 256), 50, static_cast<int64_t>(rng() % 256)};
            cv_lod_set_view(lod, &vv, 1, &p);
            std::this_thread::sleep_for(std::chrono::milliseconds(3));
            cv_lod_stats st{};
            cv_lod_stats_get(lod, &st);
        }
    });
    Host h;
    const cv_lod_event* ev = nullptr;
    const int64_t t_end = now_us() + 1500000;
    int tick = 0;
    while (now_us() < t_end) {
        const int32_t n = cv_lod_tick(lod, nullptr, &ev);
        h.apply(lod, ev, n);
        ++tick;
        cv_lod_set_column_ready(lod, tick % 8, (tick / 8) % 8, (tick / 64) & 1);
        std::this_thread::sleep_for(std::chrono::microseconds(500));
    }
    stop = true;
    for (auto& t : editors) t.join();
    viewer.join();
    cv_lod_wait_idle(lod);
    std::map<NK, uint64_t> a, b;
    digests_all(lod, 3, 0, 7, 0, 2, a);
    cv_lod_desc fd = lod_desc(3);
    cv_lod* fresh = cv_lod_create(w, &fd);
    digests_all(fresh, 3, 0, 7, 0, 2, b);
    CHECK(a == b);
    cv_lod_destroy(fresh);
    CHECK(cv_lod_destroy(lod) == CV_OK);
    cv_world_destroy(w);
    std::filesystem::remove_all(dir);
}

// ---- 11. bad arguments ------------------------------------------------------------------------------------------
void test_bad_arguments() {
    cv_world* w = make_world(-64, 192);
    cv_lod_desc d = lod_desc(0);
    CHECK(cv_lod_create(w, &d) == nullptr);
    d.max_level = 17;
    CHECK(cv_lod_create(w, &d) == nullptr);
    d.max_level = 3;
    CHECK(cv_lod_create(nullptr, &d) == nullptr);
    d.min_y = 0;
    d.max_y = 10000;
    CHECK(cv_lod_create(w, &d) == nullptr);
    cv_world_desc ud{};
    ud.min_y = CV_UNBOUNDED_MIN;
    ud.max_y = CV_UNBOUNDED_MAX;
    cv_world* uw = cv_world_create(&ud);
    setup_world(uw);
    cv_lod_desc d2 = lod_desc(3);
    CHECK(cv_lod_create(uw, &d2) == nullptr);   // an unbounded world needs a range
    d2.min_y = -1000000;
    d2.max_y = -1000000 + 4096;
    cv_lod* ul = cv_lod_create(uw, &d2);
    CHECK(ul != nullptr);
    std::vector<uint16_t> rock(CV_CUBE_CELLS, 1);
    cv_cube_write_ids(uw, 5, -1000000 / 32, 5, rock.data());
    CHECK(cv_lod_read_node(ul, 0, 5, -1000000 / 32, 5, nullptr, nullptr, nullptr) == 1);
    cv_lod_mesh m{};
    CHECK(cv_lod_build_mesh(ul, 0, 5, -1000000 / 32, 5, &m) == CV_OK);
    if (m.quad_count != 5) std::printf("  unbounded mesh: %d quads, origin y %lld\n", m.quad_count, static_cast<long long>(m.origin[1]));
    CHECK(m.quad_count == 5 && m.origin[1] == -1000000);   // node-local Y at any depth; the floor face is never drawn
    cv_lod_destroy(ul);
    cv_world_destroy(uw);
    d.min_y = 0;
    d.max_y = 0;
    cv_lod* lod = cv_lod_create(w, &d);
    CHECK(lod != nullptr);
    cv_lod_policy p{};
    p.blocks_per_cell = 45;
    p.max_level = 3;
    p.radius = 100;
    cv_lod_viewer v{0, 0, 0};
    CHECK(cv_lod_set_view(lod, &v, 1, &p) == CV_ERR_ARG);
    p.blocks_per_cell = 64;
    p.max_level = 4;
    CHECK(cv_lod_set_view(lod, &v, 1, &p) == CV_ERR_ARG);
    p.max_level = 3;
    p.incomplete_depth = 9;
    CHECK(cv_lod_set_view(lod, &v, 1, &p) == CV_ERR_ARG);
    p.incomplete_depth = 0;
    CHECK(cv_lod_set_view(lod, &v, 1, &p) == CV_OK);
    CHECK(cv_lod_set_view(lod, nullptr, 1, &p) == CV_ERR_ARG);
    CHECK(cv_lod_read_node(lod, 4, 0, 0, 0, nullptr, nullptr, nullptr) == CV_ERR_ARG);
    CHECK(cv_lod_read_node(lod, -1, 0, 0, 0, nullptr, nullptr, nullptr) == CV_ERR_ARG);
    CHECK(cv_lod_tick(lod, nullptr, nullptr) == CV_ERR_ARG);
    CHECK(cv_lod_mesh_get(lod, 0) == nullptr);
    const uint8_t bad = 9;
    CHECK(cv_lod_block_classes(lod, &bad, 1) == CV_ERR_ARG);
    CHECK(cv_lod_note_cubes(lod, nullptr, 1) == CV_ERR_ARG);
    CHECK(cv_lod_note_events(lod, nullptr, 1) == CV_ERR_ARG);
    // Class override: FLOWER as SOLID.
    const uint8_t cls[8] = {0, 0, 0, 0, 0, 0, CV_LOD_CLASS_SOLID, 0};
    CHECK(cv_lod_block_classes(lod, cls, 8) == CV_OK);
    std::vector<uint16_t> ids(CV_CUBE_CELLS, FLOWER);
    cv_cube_write_ids(w, 9, 0, 9, ids.data());
    std::vector<uint8_t> cells(CV_CUBE_CELLS);
    CHECK(cv_lod_read_node(lod, 0, 9, 0, 9, nullptr, cells.data(), nullptr) == 1);
    CHECK(cells[0] == (kSolid | kFull));
    cv_lod_destroy(lod);
    cv_world_destroy(w);
}

// ---- 12. performance ---------------------------------------------------------------------------------------------
void test_performance() {
    const bool big = std::getenv("CV_LOD_BENCH") != nullptr;
    cv_world* w = make_world(-384, 640);
    gen_columns(w, 0, 7, 0, 7, 0, 2);
    cv_lod_desc d = lod_desc(5);
    d.worker_threads = 1;
    cv_lod* lod = cv_lod_create(w, &d);
    int64_t t0 = now_us();
    int n1 = 0;
    for (int64_t x = 0; x < 4; ++x) {
        for (int64_t z = 0; z < 4; ++z) {
            cv_lod_read_node(lod, 1, x, 0, z, nullptr, nullptr, nullptr);
            ++n1;
        }
    }
    const double l1_us = static_cast<double>(now_us() - t0) / n1;
    t0 = now_us();
    int n2 = 0;
    for (int64_t x = 0; x < 2; ++x) {
        for (int64_t z = 0; z < 2; ++z) {
            cv_lod_read_node(lod, 2, x, 0, z, nullptr, nullptr, nullptr);
            ++n2;
        }
    }
    const double l2_us = static_cast<double>(now_us() - t0) / n2;
    t0 = now_us();
    uint64_t dg = 0;
    cv_lod_read_node(lod, 5, 0, 0, 0, nullptr, nullptr, &dg);
    const double l5_ms = static_cast<double>(now_us() - t0) / 1000.0;
    cv_lod_mesh lm{};
    t0 = now_us();
    cv_lod_build_mesh(lod, 1, 1, 0, 1, &lm);
    const double mesh1_us = static_cast<double>(now_us() - t0);
    NodePtr ch[8];
    {
        std::vector<uint16_t> ids(CV_CUBE_CELLS);
        const ClassTable ct = make_ct();
        for (int i = 0; i < 8; ++i) {
            for (int c = 0; c < CV_CUBE_CELLS; ++c) {
                ids[static_cast<size_t>(c)] = terrain_block(i * 32 + (c & 31), 20 + (c >> 10), (c >> 5) & 31);
            }
            auto nd = std::make_shared<NodeData>();
            node_from_ids(*nd, NodeKey{0, i, 0, 0}, ids.data(), ct, AIR, -384, 640);
            ch[i] = nd;
        }
    }
    const int reps = 40;
    size_t node_bytes = 0;
    t0 = now_us();
    for (int r = 0; r < reps; ++r) {
        NodeData out;
        node_downsample(out, NodeKey{1, 0, 0, 0}, ch, AIR);
        node_bytes = out.bytes();
    }
    const double ds_us = static_cast<double>(now_us() - t0) / reps;
    TestFaces faces;
    const NodeData* none[6] = {nullptr, nullptr, nullptr, nullptr, nullptr, nullptr};
    MeshBuild m;
    t0 = now_us();
    for (int r = 0; r < reps; ++r) mesh_node(*ch[0], none, false, 5, faces, m);
    const double mesh_us = static_cast<double>(now_us() - t0) / reps;
    std::vector<uint8_t> rec;
    encode_record(*ch[0], rec);
    NodeData uni;
    node_uniform(uni, NodeKey{1, 0, 0, 0}, STONE, kSolid | kFull, true);
    std::printf("  perf: L1 from 8 terrain cubes %.0f us, L2 from 8 L1 %.0f us, L5 from scratch (8x8 columns loaded, "
                "rest synthesized/unknown) %.1f ms, downsample alone %.0f us, mesh surface L0 node %.0f us (%d quads), "
                "build_mesh L1 incl. neighbours %.0f us (%d quads)\n",
                l1_us, l2_us, l5_ms, ds_us, mesh_us, m.quad_count, mesh1_us, lm.quad_count);
    std::printf("  perf: node memory %zu B non-uniform / %zu B uniform, record %zu B before zstd\n", node_bytes,
                uni.bytes(), rec.size());
    CHECK(ds_us < 50000 && mesh_us < 50000);
    cv_lod_destroy(lod);

    // Selection at r = 2048 blocks (K 64, levels 0..5): a generated area (surface cube per column; deep and sky
    // cubes synthesized) around the viewer; outside it never generated (incomplete -> refined -> fallback).
    const int64_t cols = big ? 128 : 40;
    const int64_t c0 = 64 - cols / 2;
    t0 = now_us();
    for (int64_t cx = c0; cx < c0 + cols; ++cx) {
        for (int64_t cz = c0; cz < c0 + cols; ++cz) {
            std::vector<uint16_t> ids(CV_CUBE_CELLS);
            for (int c = 0; c < CV_CUBE_CELLS; ++c) {
                ids[static_cast<size_t>(c)] = terrain_block(cx * 32 + (c & 31), 32 + (c >> 10), cz * 32 + ((c >> 5) & 31));
            }
            cv_cube_write_ids(w, cx, 1, cz, ids.data());
        }
    }
    const double gen_ms = static_cast<double>(now_us() - t0) / 1000.0;
    cv_lod_desc dd = lod_desc(5);
    dd.worker_threads = static_cast<int32_t>(std::max(2u, std::min(12u, std::thread::hardware_concurrency() / 2)));
    lod = cv_lod_create(w, &dd);
    cv_lod_policy p{};
    p.blocks_per_cell = 64;
    p.max_level = 5;
    p.radius = 2048;
    p.incomplete_depth = 2;
    p.standby_from = -1;
    cv_lod_viewer v{64 * 32, 60, 64 * 32};
    cv_lod_set_view(lod, &v, 1, &p);
    Host h;
    t0 = now_us();
    run_until_idle(lod, h, 400000);
    const double converge_ms = static_cast<double>(now_us() - t0) / 1000.0;
    int64_t sel_sum = 0, sel_max = 0, tick_max = 0;
    for (int r = 0; r < 10; ++r) {
        cv_lod_viewer vv{64 * 32 + r * 7, 60, 64 * 32};
        cv_lod_set_view(lod, &vv, 1, &p);
        const cv_lod_event* ev = nullptr;
        const int32_t n = cv_lod_tick(lod, nullptr, &ev);
        h.apply(lod, ev, n);
        cv_lod_stats st{};
        cv_lod_stats_get(lod, &st);
        sel_sum += st.select_us_last;
        sel_max = std::max(sel_max, st.select_us_last);
        tick_max = std::max(tick_max, st.tick_us_last);
    }
    cv_lod_stats st{};
    cv_lod_stats_get(lod, &st);
    int unc = 0;
    const bool cover_ok = check_cover(h, {}, v.x + 63, v.z, p.radius, -10, 140, &unc);   // the last view
    if (!cover_ok) std::printf("  cover failed: %d uncovered, %zu drawn, %zu fallbacks\n", unc, h.drawn.size(), h.fallback.size());
    CHECK(cover_ok);
    std::printf("  perf: r=2048, %lldx%lld generated columns (written in %.0f ms): converged in %.0f ms on %d workers "
                "(%lld nodes built, %lld meshes), drawn %lld nodes %.2f MiB, %lld fallback footprints; select %lld us "
                "avg / %lld us max, tick max %lld us; cache %.1f MiB / %lld nodes\n",
                static_cast<long long>(cols), static_cast<long long>(cols), gen_ms, converge_ms, dd.worker_threads,
                static_cast<long long>(st.nodes_built_total), static_cast<long long>(st.meshes_built_total),
                static_cast<long long>(st.drawn_nodes), static_cast<double>(st.drawn_bytes) / 1048576.0,
                static_cast<long long>(st.fallback_footprints), static_cast<long long>(sel_sum / 10),
                static_cast<long long>(sel_max), static_cast<long long>(tick_max),
                static_cast<double>(st.cache_bytes) / 1048576.0, static_cast<long long>(st.nodes_cached));
    CHECK(st.drawn_nodes > 0);
    cv_lod_destroy(lod);
    cv_world_destroy(w);
}

// ---- 13. new world stream (tick bound, rebuild churn, resident node memory) ----------------------------------------
// A simulated NEW world driven the way Stonebreak drives the LOD (OctreeLod / OctreeLodRenderer): columns of a
// 1024-tall world generate around the viewer over time (distance order, band cubes installed bottom-up so the column
// TOP keeps moving, population writes trees across cube borders after the 8 neighbours exist, every install and
// population re-meshes the cube and its neighbours: all of it noted through cv_lod_note_cubes like the game's
// install + mesh listeners), columns turn READY once populated with their neighbours, the viewer then walks and
// columns behind it go UNREADY. One cv_lod_tick (8 MiB budget) per ~4 ms frame. Reports tick p50 / p99 / max, nodes
// built vs distinct nodes, meshes, drawn nodes / level-0 nodes / bytes; checks the exact cover all along.
// Small by default; CV_LOD_BENCH=1 runs the game-sized one (r 384 detail, LOD radius 704).
int64_t nw_height(int64_t x, int64_t z) {
    const double fx = static_cast<double>(x), fz = static_cast<double>(z);
    return 60 + static_cast<int64_t>(std::lround(50.0 * std::sin(fx / 37.0) * std::cos(fz / 41.0) +
                                                 20.0 * std::sin(fx / 13.0 + fz / 17.0)));
}
uint16_t nw_block(int64_t x, int64_t y, int64_t h) {
    if (y < h - 3) return (y > -40 && y < -36 && ((x + 4096) % 29) < 3) ? AIR : STONE;
    if (y < h - 1) return DIRT;
    if (y < h) return h < 40 ? DIRT : GRASS;
    if (y < 40) return WATER;
    return AIR;
}

struct NwColumn {
    int64_t lo = 0, hi = 0;    // band cubes [lo, hi]
    int64_t next = 0;          // next cube to install (bottom-up)
    bool installed = false, populated = false, ready = false, queued = false;
};

void test_new_world() {
    const bool big = std::getenv("CV_LOD_BENCH") != nullptr;
    const int gen_r = big ? 12 : 6;                 // detail disc, columns (the stream's render radius)
    const int lod_r = big ? 704 : 400;              // LOD radius, blocks
    const double walk_s = big ? 8.0 : 2.0;
    const int installs_per_frame = big ? 5 : 3;    // ~1.2k cubes/s at 4 ms frames (server 48/tick at 20 Hz ~ 1k/s)
    const std::string dir = tmp_dir("newworld");
    cv_world* w = make_world(-384, 640);
    cv_lod_desc d = lod_desc(8);
    d.worker_threads = 2;
    d.cache_bytes = std::getenv("CV_LOD_NW_CACHE_MB") ? int64_t{std::atoi(std::getenv("CV_LOD_NW_CACHE_MB"))} << 20 : int64_t{192} << 20;
    d.store_dir = dir.c_str();
    cv_lod* lod = cv_lod_create(w, &d);
    CHECK(lod != nullptr);
    cv_lod_policy p{};
    p.blocks_per_cell = 64;
    p.radius = lod_r;
    p.max_level = 0;
    while (p.max_level < 8 && (int64_t{1} << (p.max_level + 1)) * p.blocks_per_cell <= p.radius) ++p.max_level;
    p.incomplete_depth = 3;
    p.standby_from = std::max(0, gen_r * 32 - 64);
    std::map<std::pair<int64_t, int64_t>, NwColumn> cols;
    std::set<std::pair<int64_t, int64_t>> ready;
    std::set<std::tuple<int64_t, int64_t, int64_t>> cubes;   // every cube ever written
    std::vector<int64_t> notes;
    auto note = [&](int64_t cx, int64_t cy, int64_t cz) {
        notes.push_back(cx);
        notes.push_back(cy);
        notes.push_back(cz);
    };
    auto note_mesh_around = [&](int64_t cx, int64_t cy, int64_t cz) {   // REMESHED of the cube and its neighbours
        static const int o[7][3] = {{0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (const auto& e : o) {
            if (cubes.count({cx + e[0], cy + e[1], cz + e[2]})) note(cx + e[0], cy + e[1], cz + e[2]);
        }
    };
    auto column = [&](int64_t cx, int64_t cz) -> NwColumn& {
        auto it = cols.find({cx, cz});
        if (it != cols.end()) return it->second;
        NwColumn c;
        int64_t hmin = INT64_MAX, hmax = INT64_MIN;
        for (int64_t x = cx * 32; x < cx * 32 + 32; ++x) {
            for (int64_t z = cz * 32; z < cz * 32 + 32; ++z) {
                const int64_t h = nw_height(x, z);
                hmin = std::min(hmin, h);
                hmax = std::max(hmax, std::max<int64_t>(h, 40));
            }
        }
        c.lo = cenda::floor_div64(hmin - 16, 32);
        c.hi = cenda::floor_div64(hmax + 8, 32);
        c.next = c.lo;
        return cols.emplace(std::make_pair(cx, cz), c).first->second;
    };
    auto install = [&](int64_t cx, int64_t cy, int64_t cz) {
        std::vector<uint16_t> ids(CV_CUBE_CELLS);
        int64_t hs[32 * 32];
        for (int lz = 0; lz < 32; ++lz) {
            for (int lx = 0; lx < 32; ++lx) hs[lz * 32 + lx] = nw_height(cx * 32 + lx, cz * 32 + lz);
        }
        for (int ly = 0; ly < 32; ++ly) {
            for (int lz = 0; lz < 32; ++lz) {
                for (int lx = 0; lx < 32; ++lx) {
                    ids[static_cast<size_t>(CV_CELL_INDEX(lx, ly, lz))] =
                        nw_block(cx * 32 + lx, cy * 32 + ly, hs[lz * 32 + lx]);
                }
            }
        }
        cv_cube_write_ids(w, cx, cy, cz, ids.data());
        cubes.insert({cx, cy, cz});
        note(cx, cy, cz);
        note_mesh_around(cx, cy, cz);
    };
    auto populate = [&](int64_t cx, int64_t cz) {   // trees: logs + a leaf crown, across cube borders
        std::set<std::tuple<int64_t, int64_t, int64_t>> touched;
        for (int64_t x = cx * 32; x < cx * 32 + 32; ++x) {
            for (int64_t z = cz * 32; z < cz * 32 + 32; ++z) {
                const int64_t h = nw_height(x, z);
                if (h < 42 || (spot(x, z) % 151) != 0) continue;
                for (int64_t y = h; y < h + 6; ++y) {
                    if (cv_set(w, x, y, z, LOG) >= 0) touched.insert({x >> 5, y >> 5, z >> 5});
                }
                for (int64_t dx = -2; dx <= 2; ++dx) {
                    for (int64_t dz = -2; dz <= 2; ++dz) {
                        for (int64_t y = h + 4; y < h + 8; ++y) {
                            if ((dx != 0 || dz != 0 || y >= h + 6) && cv_set(w, x + dx, y, z + dz, LEAVES) >= 0) {
                                touched.insert({(x + dx) >> 5, y >> 5, (z + dz) >> 5});
                            }
                        }
                    }
                }
            }
        }
        for (const auto& [x, y, z] : touched) note_mesh_around(x, y, z);
    };
    auto neighbours_done = [&](int64_t cx, int64_t cz, bool need_populated) {
        for (int64_t dx = -1; dx <= 1; ++dx) {
            for (int64_t dz = -1; dz <= 1; ++dz) {
                auto it = cols.find({cx + dx, cz + dz});
                if (it == cols.end() || !it->second.installed) return false;
                if (need_populated && !it->second.populated) return false;
            }
        }
        return true;
    };

    double vx = 16.0, vz = 16.0;   // viewer (blocks)
    cv_lod_viewer v{16, 80, 16};
    cv_lod_viewer last_v{INT64_MIN, 0, 0};
    Host h;
    std::vector<int64_t> tick_us;
    int64_t peak_drawn_bytes = 0, peak_l0 = 0, cover_fail = 0, frames = 0;
    std::map<NK, int64_t> drawn_bytes;   // node -> mesh bytes (GPU proxy)
    const int64_t t_start = now_us();
    double walked = 0.0;
    bool walking = false;
    int64_t gen_done_us = 0;
    const double frame_s = 0.004;
    for (;;) {
        const int64_t f0 = now_us();
        ++frames;
        // Generation: columns of the detail disc in distance order (+1 ring of support columns, never READY).
        const int64_t pcx = cenda::floor_div64(static_cast<int64_t>(std::floor(vx)), 32);
        const int64_t pcz = cenda::floor_div64(static_cast<int64_t>(std::floor(vz)), 32);
        std::vector<std::pair<double, std::pair<int64_t, int64_t>>> want;
        for (int64_t dx = -gen_r - 4; dx <= gen_r + 4; ++dx) {
            for (int64_t dz = -gen_r - 4; dz <= gen_r + 4; ++dz) {
                const double dd = std::sqrt(static_cast<double>(dx * dx + dz * dz));
                if (dd > gen_r + 3.5) continue;
                NwColumn& c = column(pcx + dx, pcz + dz);
                if (!c.installed) want.push_back({dd, {pcx + dx, pcz + dz}});
            }
        }
        std::sort(want.begin(), want.end());
        int budget = installs_per_frame;
        for (const auto& [dd, cc] : want) {
            if (budget <= 0) break;
            NwColumn& c = column(cc.first, cc.second);
            while (budget > 0 && c.next <= c.hi) {
                install(cc.first, c.next++, cc.second);
                --budget;
            }
            if (c.next > c.hi) c.installed = true;
        }
        // Population once the 8 neighbours are installed; READY once populated with the 8 neighbours populated.
        for (auto& [cc, c] : cols) {
            if (c.installed && !c.populated && neighbours_done(cc.first, cc.second, false)) {
                populate(cc.first, cc.second);
                c.populated = true;
            }
        }
        std::vector<std::pair<int64_t, int64_t>> now_ready, now_unready;
        for (auto& [cc, c] : cols) {
            const double dd = std::hypot(static_cast<double>(cc.first - pcx), static_cast<double>(cc.second - pcz));
            const bool in_disc = dd <= gen_r;
            if (!c.ready && in_disc && c.populated && neighbours_done(cc.first, cc.second, true)) {
                c.ready = true;
                now_ready.push_back(cc);
            } else if (c.ready && !in_disc) {
                c.ready = false;
                now_unready.push_back(cc);
            }
        }
        if (!notes.empty()) {
            cv_lod_note_cubes(lod, notes.data(), static_cast<int64_t>(notes.size() / 3));
            notes.clear();
        }
        for (const auto& cc : now_ready) {
            ready.insert(cc);
            cv_lod_set_column_ready(lod, cc.first, cc.second, 1);
        }
        for (const auto& cc : now_unready) {
            ready.erase(cc);
            cv_lod_set_column_ready(lod, cc.first, cc.second, 0);
        }
        v.x = static_cast<int64_t>(std::floor(vx));
        v.z = static_cast<int64_t>(std::floor(vz));
        if (v.x != last_v.x || v.z != last_v.z) {
            cv_lod_set_view(lod, &v, 1, &p);
            last_v = v;
        }
        cv_lod_budget b{};
        b.max_mesh_bytes = int64_t{8} << 20;
        const cv_lod_event* ev = nullptr;
        const int64_t t0 = now_us();
        const int32_t n = cv_lod_tick(lod, &b, &ev);
        tick_us.push_back(now_us() - t0);
        for (int32_t i = 0; i < n; ++i) {
            const cv_lod_event& e = ev[i];
            if (e.type == CV_LOD_EV_SHOW || e.type == CV_LOD_EV_UPDATE) {
                const cv_lod_mesh* m = cv_lod_mesh_get(lod, e.mesh);
                if (m != nullptr) drawn_bytes[NK{e.level, e.x, e.y, e.z}] = int64_t{m->quad_count + m->water_count} * 16;
            } else if (e.type == CV_LOD_EV_HIDE) {
                drawn_bytes.erase(NK{e.level, e.x, e.y, e.z});
            }
        }
        h.apply(lod, ev, n);
        {
            int64_t bytes = 0, l0 = 0;
            for (const auto& [k, bb] : drawn_bytes) {
                bytes += bb;
                if (std::get<0>(k) == 0) ++l0;
            }
            peak_drawn_bytes = std::max(peak_drawn_bytes, bytes);
            peak_l0 = std::max(peak_l0, l0);
        }
        if (frames % 25 == 0) {
            int unc = 0;
            const int64_t span = (lod_r >> 5) + 2;
            if (!check_cover(h, ready, v.x, v.z, p.radius, pcx - span, pcx + span, &unc) || unc != 0) ++cover_fail;
        }
        // Phases: generate the first disc, then walk +x, then settle.
        bool all_ready = true;
        for (const auto& [cc, c] : cols) {
            const double dd = std::hypot(static_cast<double>(cc.first - pcx), static_cast<double>(cc.second - pcz));
            if (dd <= gen_r && !c.ready) all_ready = false;
        }
        if (!walking && all_ready && gen_done_us == 0) {
            gen_done_us = now_us() - t_start;
            walking = true;
        }
        if (walking && walked < walk_s) {
            vx += 24.0 * frame_s;
            walked += frame_s;
        }
        if (walked >= walk_s && all_ready && now_us() - t_start > gen_done_us + static_cast<int64_t>((walk_s + 3.0) * 1e6)) break;
        if (now_us() - t_start > 60000000) break;
        const int64_t spent = now_us() - f0;
        if (spent < 4000) std::this_thread::sleep_for(std::chrono::microseconds(4000 - spent));
    }
    const int64_t total_ms = (now_us() - t_start) / 1000;
    cv_lod_stats st{};
    cv_lod_stats_get(lod, &st);
    // Distinct level >= 1 nodes over the explored columns (whole stacks of the y range).
    int64_t distinct = 0;
    for (int lv = 1; lv <= 8; ++lv) {
        std::set<std::pair<int64_t, int64_t>> feet;
        for (const auto& [cc, c] : cols) {
            if (c.next > c.lo) feet.insert({cc.first >> lv, cc.second >> lv});
        }
        distinct += static_cast<int64_t>(feet.size()) * std::max<int64_t>(1, 1024 / (int64_t{32} << lv));
    }
    std::vector<int64_t> s = tick_us;
    std::sort(s.begin(), s.end());
    auto pct = [&](double q) { return s.empty() ? 0 : s[std::min(s.size() - 1, static_cast<size_t>(q * static_cast<double>(s.size())))]; };
    int64_t over2 = 0;
    for (int64_t t : s) over2 += t > 2000 ? 1 : 0;
    int64_t bytes_end = 0, l0_end = 0;
    for (const auto& [k, bb] : drawn_bytes) {
        bytes_end += bb;
        if (std::get<0>(k) == 0) ++l0_end;
    }
    std::printf("  newworld%s: %lld columns, %zu cubes, %lld frames in %lld ms (disc READY at %lld ms); tick p50 %lld us "
                "p99 %lld us max %lld us (%lld > 2 ms); nodes built %lld vs %lld distinct (x%.1f), meshes %lld; drawn "
                "%zu nodes (%lld level 0) %.2f MiB at the end, peak %.2f MiB / %lld level 0; fallbacks %zu; cache %.1f MiB / %lld nodes; "
                "cover failures %lld\n",
                big ? " (bench)" : "", static_cast<long long>(cols.size()), cubes.size(), static_cast<long long>(frames),
                static_cast<long long>(total_ms), static_cast<long long>(gen_done_us / 1000), static_cast<long long>(pct(0.5)),
                static_cast<long long>(pct(0.99)), static_cast<long long>(s.empty() ? 0 : s.back()),
                static_cast<long long>(over2), static_cast<long long>(st.nodes_built_total), static_cast<long long>(distinct),
                static_cast<double>(st.nodes_built_total) / static_cast<double>(std::max<int64_t>(1, distinct)),
                static_cast<long long>(st.meshes_built_total), drawn_bytes.size(), static_cast<long long>(l0_end),
                static_cast<double>(bytes_end) / 1048576.0, static_cast<double>(peak_drawn_bytes) / 1048576.0,
                static_cast<long long>(peak_l0), h.fallback.size(), static_cast<double>(st.cache_bytes) / 1048576.0,
                static_cast<long long>(st.nodes_cached), static_cast<long long>(cover_fail));
    if (gen_done_us == 0) {
        int ni = 0, np = 0, nr = 0;
        for (const auto& [cc, c] : cols) { ni += c.installed; np += c.populated; nr += c.ready; }
        std::printf("  newworld: never settled: %d installed %d populated %d ready of %zu\n", ni, np, nr, cols.size());
    }
    CHECK(cover_fail == 0);
    CHECK(gen_done_us > 0);
    cv_lod_destroy(lod);
    cv_world_destroy(w);
    std::filesystem::remove_all(dir);
}

} // namespace

int main() {
    const char* only = std::getenv("CV_LOD_ONLY");
    struct T {
        const char* name;
        void (*fn)();
    };
    const T tests[] = {
        {"downsample", test_downsample_rules}, {"records", test_records},         {"sources", test_sources},
        {"levels", test_levels},               {"incremental", test_incremental}, {"persistence", test_persistence},
        {"mesh", test_mesh_basics},            {"crack", test_crack_free},        {"selection", test_selection},
        {"stream", test_stream_integration},   {"concurrency", test_concurrency}, {"bad_arguments", test_bad_arguments},
        {"performance", test_performance},     {"newworld", test_new_world},
    };
    for (const T& t : tests) {
        if (only != nullptr && std::strstr(t.name, only) == nullptr) continue;
        const int before = g_failures;
        const int64_t t0 = now_us();
        t.fn();
        std::printf("%-14s %s (%.0f ms)\n", t.name, g_failures == before ? "ok" : "FAILED",
                    static_cast<double>(now_us() - t0) / 1000.0);
        std::fflush(stdout);
    }
    std::printf("voxel_lod: %d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
