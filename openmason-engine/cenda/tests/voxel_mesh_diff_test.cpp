// ctest voxel_mesh_diff: the cube mesher (build_gpu_mesh + cv_mesh_cube) against the test-only reference copy
// of the mesher as it was before the bit-sliced lighting rewrite (voxel_mesh_ref.cpp), byte for byte, over
// thousands of random worlds: random and realistic block tables (cutout / translucent / opaque / host
// geometry / non-zero air), models bound per state, fluids, face textures, neighbour face rules, present /
// missing / known-air neighbours, per-column surfaces (known and unknown, bounded and unbounded worlds,
// far coordinates), smooth and flat lighting, greedy and unit faces. Then the timing breakdown of the
// benchmark terrain cube (library vs reference). VOXEL_MESH_DIFF_WORLDS overrides the number of worlds.
#include "cenda/voxel_world.h"
#include "cv_internal.hpp"
#include "mesh_gpu.hpp"
#include "mesh_internal.hpp"
#include "voxel_mesh_ref.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

using namespace cenda::voxel;

namespace {

int g_failures = 0;
long g_checks = 0;

#define CHECK(...)                                                                                  \
    do {                                                                                            \
        ++g_checks;                                                                                 \
        if (!(__VA_ARGS__)) {                                                                       \
            ++g_failures;                                                                           \
            if (g_failures < 40) std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #__VA_ARGS__); \
        }                                                                                           \
    } while (0)

uint64_t mix64(uint64_t z) {
    z += 0x9E3779B97F4A7C15ull;
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
    return z ^ (z >> 31);
}

struct Rng {
    uint64_t s;
    explicit Rng(uint64_t seed) : s(seed) {}
    uint64_t next() { return mix64(s += 0x9E3779B97F4A7C15ull); }
    uint32_t below(uint32_t n) { return static_cast<uint32_t>(next() % n); }
    bool pct(uint32_t p) { return below(100) < p; }
    int range(int lo, int hi) { return lo + static_cast<int>(below(static_cast<uint32_t>(hi - lo + 1))); }
};

// ---- ids and tables ------------------------------------------------------------------------------------------
constexpr uint16_t AIR = 0, STONE = 1, DIRT = 2, LEAVES = 3, GLASS = 4, ICE = 5, WATER = 6, SNOW = 7, FLOWER = 8,
                   CUBEMODEL = 9, GRANITE = 10, GEL = 11, SLAB = 12, LAVA = 13, MOSS = 14, PANE = 15;
constexpr int kIds = 16;
constexpr uint16_t kOpaque = CV_BLOCK_CUBE | CV_BLOCK_OPAQUE | CV_BLOCK_SKY_OPAQUE | CV_BLOCK_VIS_OPAQUE;
const uint16_t kRealFlags[kIds] = {
    0,
    kOpaque,
    kOpaque,
    CV_BLOCK_CUBE | CV_BLOCK_TRANSPARENT | CV_BLOCK_SKY_OPAQUE,
    CV_BLOCK_CUBE | CV_BLOCK_TRANSPARENT,
    CV_BLOCK_CUBE | CV_BLOCK_TRANSPARENT | CV_BLOCK_TRANSLUCENT,
    CV_BLOCK_HOST_GEOMETRY | CV_BLOCK_TRANSPARENT | CV_BLOCK_TRANSLUCENT,
    CV_BLOCK_HOST_GEOMETRY | CV_BLOCK_OPAQUE,
    CV_BLOCK_HOST_GEOMETRY | CV_BLOCK_TRANSPARENT,
    CV_BLOCK_HOST_GEOMETRY | CV_BLOCK_OPAQUE,
    CV_BLOCK_CUBE | CV_BLOCK_OPAQUE | CV_BLOCK_SKY_OPAQUE,
    CV_BLOCK_HOST_GEOMETRY | CV_BLOCK_TRANSPARENT | CV_BLOCK_TRANSLUCENT,
    CV_BLOCK_HOST_GEOMETRY | CV_BLOCK_OPAQUE | CV_BLOCK_SKY_OPAQUE,
    CV_BLOCK_HOST_GEOMETRY | CV_BLOCK_TRANSPARENT | CV_BLOCK_TRANSLUCENT,
    kOpaque,
    CV_BLOCK_HOST_GEOMETRY | CV_BLOCK_TRANSPARENT,
};
constexpr int32_t kWaterLayer = 1, kSnowLayer = 2, kStateLayer = 3;

// ---- surface ----------------------------------------------------------------------------------------------
struct Surf {
    uint64_t seed = 0;
    int64_t base = 0;
    uint32_t amp = 1;
    uint32_t unknown_pct = 0;
};

int32_t surf_cb(void* user, int64_t cx, int64_t cz, int64_t* sky, int64_t* top) {
    const auto* s = static_cast<const Surf*>(user);
    const uint64_t h = mix64(s->seed ^ mix64(static_cast<uint64_t>(cx) * 0x9E3779B97F4A7C15ull + static_cast<uint64_t>(cz)));
    if (h % 100 < s->unknown_pct) return 0;
    const bool flat = (h >> 8) % 4 == 0;
    for (int i = 0; i < 1024; ++i) {
        const uint64_t c = mix64(h + static_cast<uint64_t>(flat ? 0 : i));
        sky[i] = s->base + static_cast<int64_t>(c % s->amp);
        top[i] = sky[i] + static_cast<int64_t>((c >> 20) % 3);
    }
    return 1;
}

// ---- models -------------------------------------------------------------------------------------------------
cv_model_quad model_face(int face, float height, uint8_t flags, uint16_t layer) {
    cv_model_quad q{};
    for (int c = 0; c < 4; ++c) {
        for (int a = 0; a < 3; ++a) q.pos[c][a] = static_cast<float>(kFaceOffsets[face][c][a]);
        q.pos[c][1] *= height;
        q.uv[c][0] = static_cast<float>(c & 1);
        q.uv[c][1] = static_cast<float>(c >> 1);
    }
    q.normal[0] = static_cast<float>(kFaceDx[face]);
    q.normal[1] = static_cast<float>(kFaceDy[face]);
    q.normal[2] = static_cast<float>(kFaceDz[face]);
    q.texture_layer = layer;
    q.cull_face = static_cast<uint8_t>(face == 0 && height < 1.0f ? 6 : face);
    q.flags = flags;
    return q;
}

void define_models(cv_world* w, Rng& r) {
    std::vector<cv_model_quad> qs;
    for (int f = 0; f < 6; ++f) qs.push_back(model_face(f, 1.0f, 0, static_cast<uint16_t>(100 + f)));
    cv_model_define(w, 1, qs.data(), 6);
    for (int n = 1; n <= 8; ++n) {
        qs.clear();
        for (int f = 0; f < 6; ++f) {
            cv_model_quad q = model_face(f, static_cast<float>(n) / 8.0f, 0, static_cast<uint16_t>(200 + n));
            if (n == 8 && f == 0) q.cull_face = 0;
            qs.push_back(q);
        }
        cv_model_define(w, 10 + n, qs.data(), 6);
    }
    qs.clear();   // slab: bottom half, culled sides
    for (int f = 0; f < 6; ++f) qs.push_back(model_face(f, 0.5f, static_cast<uint8_t>(r.below(8)), 300));
    cv_model_define(w, 20, qs.data(), 6);
    qs.clear();   // cross: two diagonal quads, never culled
    for (int k = 0; k < 2; ++k) {
        cv_model_quad q{};
        const float a = k == 0 ? 0.0f : 1.0f;
        const float pos[4][3] = {{0, 0, a}, {1, 0, 1 - a}, {1, 1, 1 - a}, {0, 1, a}};
        std::memcpy(q.pos, pos, sizeof pos);
        q.normal[0] = 0.7f;
        q.normal[2] = k == 0 ? 0.7f : -0.7f;
        q.cull_face = 6;
        q.flags = static_cast<uint8_t>(r.below(8));
        q.texture_layer = 400;
        qs.push_back(q);
    }
    cv_model_define(w, 21, qs.data(), 2);
    qs.clear();   // overhang: a top plate from y 0.75 to 1 with full top and bottom, sides partial
    for (int f = 0; f < 6; ++f) {
        cv_model_quad q = model_face(f, 1.0f, static_cast<uint8_t>(r.below(8)), 500);
        if (f >= 2) {
            for (int c = 0; c < 4; ++c) q.pos[c][1] = 0.75f + 0.25f * q.pos[c][1];
        }
        if (f == 1) {
            for (int c = 0; c < 4; ++c) q.pos[c][1] = 0.75f;
            q.cull_face = 6;
        }
        qs.push_back(q);
    }
    cv_model_define(w, 22, qs.data(), 6);
}

// ---- world setup ------------------------------------------------------------------------------------------
struct Scene {
    cv_world* w = nullptr;
    Surf surf;
    uint16_t air = AIR;
    uint16_t flags[kIds] = {};
    uint16_t fluid = WATER;   // ids carried by the water layer (kIds = none)
    uint16_t fluid2 = kIds;   // a second fluid on the state layer
    bool unbounded = false;
    int64_t min_y = 0, max_y = 0;
};

void configure(Scene& s, Rng& r) {
    const bool real = r.pct(55);
    for (int i = 0; i < kIds; ++i) s.flags[i] = kRealFlags[i];
    if (!real) {
        static const uint16_t bits[] = {CV_BLOCK_CUBE, CV_BLOCK_TRANSPARENT, CV_BLOCK_OPAQUE, CV_BLOCK_SKY_OPAQUE,
                                        CV_BLOCK_VIS_OPAQUE, CV_BLOCK_TRANSLUCENT, CV_BLOCK_HOST_GEOMETRY};
        for (int i = 0; i < kIds; ++i) {
            uint16_t f = 0;
            for (uint16_t b : bits) {
                if (r.pct(b == CV_BLOCK_CUBE ? 60 : 35)) f = static_cast<uint16_t>(f | b);
            }
            s.flags[i] = f;
        }
        if (r.pct(50)) s.flags[s.air] = 0;
    }
    cv_block_table_set(s.w, s.flags, kIds);
    cv_layer_define(s.w, kWaterLayer, CV_LAYER_SAVED | CV_LAYER_MESHED);
    cv_layer_define(s.w, kSnowLayer, CV_LAYER_SAVED | CV_LAYER_MESHED);
    cv_layer_define(s.w, kStateLayer, CV_LAYER_SAVED | CV_LAYER_MESHED);
    define_models(s.w, r);
    if (real || r.pct(50)) {
        cv_block_model_bind(s.w, CUBEMODEL, -1, 0, 1);
        for (int n = 1; n <= 8; ++n) cv_block_model_bind(s.w, SNOW, kSnowLayer, static_cast<uint16_t>(n), 10 + n);
        cv_block_model_bind(s.w, GEL, -1, 0, r.pct(50) ? 21 : 22);
        cv_block_model_bind(s.w, SLAB, kStateLayer, 1, 20);
        cv_block_model_bind(s.w, SLAB, kStateLayer, 2, 22);
    }
    if (!real) {
        const int nb = r.range(0, 4);
        for (int k = 0; k < nb; ++k) {
            const auto id = static_cast<uint16_t>(r.below(kIds));
            if (r.pct(50)) cv_block_model_bind(s.w, id, -1, 0, static_cast<int32_t>(r.pct(20) ? 99 : 20 + r.below(3)));
            else cv_block_model_bind(s.w, id, kStateLayer, static_cast<uint16_t>(r.below(3)), static_cast<int32_t>(11 + r.below(8)));
        }
    }
    s.fluid = kIds;
    s.fluid2 = kIds;
    if (real || r.pct(60)) {
        cv_fluid_desc d{};
        d.layer = kWaterLayer;
        d.fluid_id = real ? WATER : static_cast<uint16_t>(r.below(kIds));
        for (int i = 0; i < 8; ++i) d.surface_by_level[i] = i == 0 ? 0.875f : static_cast<float>(8 - i) * 0.875f / 8.0f;
        if (cv_fluid_set(s.w, &d) == CV_OK) s.fluid = d.fluid_id;
        if (r.pct(25)) {
            d.layer = kStateLayer;
            d.fluid_id = LAVA;
            if (cv_fluid_set(s.w, &d) == CV_OK) s.fluid2 = LAVA;
        }
    }
    if (r.pct(50)) {   // face textures for some ids
        const int n = r.range(1, 6);
        for (int k = 0; k < n; ++k) {
            cv_face_tex t[6];
            for (int f = 0; f < 6; ++f) {
                t[f] = cv_face_tex{static_cast<uint16_t>(r.below(70000)), static_cast<uint8_t>(r.below(8)),
                                   static_cast<uint8_t>(r.below(2))};
            }
            cv_block_faces_set(s.w, static_cast<uint16_t>(r.below(kIds)), t);
        }
    }
    if (r.pct(40)) {   // neighbour face rules
        const int n = r.range(1, 4);
        for (int k = 0; k < n; ++k) {
            uint16_t nb[4];
            const int cnt = r.range(1, 4);
            for (int i = 0; i < cnt; ++i) nb[i] = static_cast<uint16_t>(r.below(kIds));
            const uint32_t kind = r.pct(50) ? CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN : CV_FACE_RULE_OPAQUE_WHEN_NEIGHBOUR_IN;
            const int32_t layer = r.pct(30) ? kStateLayer : -1;
            cv_face_rule_add(s.w, static_cast<uint16_t>(r.below(kIds)), layer, static_cast<uint16_t>(r.below(3)),
                             static_cast<uint8_t>(1 + r.below(63)), kind, nb, cnt);
        }
    }
}

// ---- content ------------------------------------------------------------------------------------------------
int terrain_height(uint64_t seed, int64_t x, int64_t z, int64_t base) {
    const double fx = static_cast<double>(x), fz = static_cast<double>(z);
    const double h = 6.0 * std::sin(fx * 0.11 + static_cast<double>(seed % 7)) + 5.0 * std::cos(fz * 0.07) +
                     2.0 * std::sin((fx + fz) * 0.31);
    return static_cast<int>(base + static_cast<int64_t>(h) + static_cast<int64_t>(mix64(seed ^ static_cast<uint64_t>(x * 31 + z)) % 2));
}

void fill_cube(Scene& s, Rng& r, int64_t cx, int64_t cy, int64_t cz, int kind) {
    std::vector<uint16_t> ids(CV_CUBE_CELLS, s.air), water(CV_CUBE_CELLS, 0), snow(CV_CUBE_CELLS, 0),
        state(CV_CUBE_CELLS, 0);
    const uint16_t pal[] = {STONE, DIRT, LEAVES, GLASS, ICE, WATER, SNOW, FLOWER, CUBEMODEL, GRANITE, GEL, SLAB, LAVA, MOSS, PANE};
    const uint32_t density = 5 + r.below(90);
    const uint64_t seed = r.next();
    const int64_t base = cy * 32 + static_cast<int64_t>(r.below(40)) - 4;
    const int stripe = r.range(1, 6);
    const uint16_t a = pal[r.below(15)], b = pal[r.below(15)];
    for (int ly = 0; ly < 32; ++ly) {
        for (int lz = 0; lz < 32; ++lz) {
            for (int lx = 0; lx < 32; ++lx) {
                const int64_t x = cx * 32 + lx, y = cy * 32 + ly, z = cz * 32 + lz;
                const uint64_t h = mix64(seed ^ static_cast<uint64_t>((ly * 32 + lz) * 32 + lx));
                uint16_t id = s.air;
                switch (kind) {
                    case 0:   // noise
                        if (h % 100 < density) id = pal[(h >> 8) % 15];
                        break;
                    case 1: {   // terrain: stone, dirt, caves, trees, water, snow, flowers
                        const int th = terrain_height(seed & 0xFF, x, z, base);
                        const bool cave = (mix64(seed ^ static_cast<uint64_t>(x / 3 * 7919 + y / 3 * 104729 + z / 3)) % 9) == 0;
                        if (y < th - 3) id = cave ? s.air : ((h % 53) == 0 ? GLASS : STONE);
                        else if (y < th) id = cave ? s.air : DIRT;
                        else if (y == th && (h % 5) == 0) id = (h >> 9) % 3 == 0 ? FLOWER : SNOW;
                        else if (y < th + 5 && ((x / 6 + z / 5) % 4 == 0) && y > th) id = (h % 3) == 0 ? s.air : LEAVES;
                        else if (y < base + 6) id = WATER;
                        break;
                    }
                    case 2:   // stripes and slabs (long merges)
                        id = ((ly / stripe) % 2 == 0) ? a : (((lx + lz) / stripe) % 3 == 0 ? b : s.air);
                        if (h % 200 == 0) id = pal[(h >> 8) % 15];
                        break;
                    case 3:   // sparse
                        if (h % 100 < 3) id = pal[(h >> 8) % 15];
                        break;
                    case 4:   // transparent heavy
                        id = (h % 10) < 3 ? ICE : (h % 10) < 5 ? GLASS : (h % 10) < 6 ? LEAVES : (h % 10) < 7 ? STONE : s.air;
                        break;
                    default:   // half-space with a ragged surface
                        id = ly < 16 + static_cast<int>((h >> 4) % 3) ? a : s.air;
                        break;
                }
                const auto i = static_cast<size_t>(CV_CELL_INDEX(lx, ly, lz));
                ids[i] = id;
                if (id == s.fluid || (h >> 40) % 7 == 0) {
                    water[i] = static_cast<uint16_t>(r.pct(40) ? 0 : ((h >> 13) % 8) | (((h >> 17) % 2) << 3) |
                                                                        (((h >> 18) % 2) << 4) | (((h >> 19) % 9) << 5));
                }
                if (id == SNOW || (h >> 44) % 9 == 0) snow[i] = static_cast<uint16_t>(1 + (h >> 22) % 8);
                if ((h >> 48) % 3 != 0) state[i] = static_cast<uint16_t>((h >> 26) % 3);
            }
        }
    }
    if (cv_cube_write_ids(s.w, cx, cy, cz, ids.data()) != CV_OK) return;
    if (r.pct(85)) cv_layer_write_cube(s.w, kWaterLayer, cx, cy, cz, water.data());
    if (r.pct(85)) cv_layer_write_cube(s.w, kSnowLayer, cx, cy, cz, snow.data());
    if (r.pct(70)) cv_layer_write_cube(s.w, kStateLayer, cx, cy, cz, state.data());
}

void place(Scene& s, Rng& r, int64_t cx, int64_t cy, int64_t cz, int kind) {
    if (!s.unbounded && (cy * 32 + 32 <= s.min_y || cy * 32 >= s.max_y)) return;
    if (r.pct(12)) {
        cv_cube_create(s.w, cx, cy, cz, r.pct(50) ? s.air : static_cast<uint16_t>(r.below(kIds)));
        return;
    }
    fill_cube(s, r, cx, cy, cz, kind);
}

// ---- comparison ---------------------------------------------------------------------------------------------
bool same_vec(const std::vector<uint8_t>& a, const std::vector<uint8_t>& b) {
    return a.size() == b.size() && (a.empty() || std::memcmp(a.data(), b.data(), a.size()) == 0);
}

bool same_build(const GpuMeshBuild& a, const GpuMeshBuild& b) {
    for (int i = 0; i < 3; ++i) {
        if (a.quad_count[i] != b.quad_count[i] || !same_vec(a.quad16[i], b.quad16[i])) return false;
    }
    if (a.water_count != b.water_count || !same_vec(a.water, b.water)) return false;
    if (a.stamp_vertex_count != b.stamp_vertex_count || !same_vec(a.stamp_vertices, b.stamp_vertices)) return false;
    if (a.stamp_indices != b.stamp_indices) return false;
    if (std::memcmp(a.aabb_min, b.aabb_min, sizeof a.aabb_min) != 0) return false;
    if (std::memcmp(a.aabb_max, b.aabb_max, sizeof a.aabb_max) != 0) return false;
    return a.visibility == b.visibility && a.flags == b.flags && a.revision == b.revision;
}

struct OutBufs {
    std::vector<cv_quad> q[3];
    std::vector<cv_host_cell> host;
    OutBufs() {
        for (auto& v : q) v.resize(CV_CUBE_CELLS * 6);
        host.resize(CV_CUBE_CELLS);
    }
};

cv_mesh_out out_of(OutBufs& b, int32_t cap_div) {
    cv_mesh_out o{};
    o.opaque = b.q[0].data();
    o.opaque_cap = static_cast<int32_t>(b.q[0].size()) / cap_div;
    o.cutout = b.q[1].data();
    o.cutout_cap = static_cast<int32_t>(b.q[1].size()) / cap_div;
    o.translucent = b.q[2].data();
    o.translucent_cap = static_cast<int32_t>(b.q[2].size()) / cap_div;
    o.host_cells = b.host.data();
    o.host_cap = static_cast<int32_t>(b.host.size()) / cap_div;
    return o;
}

long g_meshes = 0, g_quads = 0;

bool compare_cube(cv_world* w, int64_t cx, int64_t cy, int64_t cz, uint32_t flags, bool small_caps) {
    static GpuMeshBuild a, b;
    const int32_t ra = build_gpu_mesh(w, cx, cy, cz, flags, a);
    const int32_t rb = meshref::build_gpu_mesh(w, cx, cy, cz, flags, b);
    bool ok = ra == rb && (ra != CV_OK || same_build(a, b));
    ++g_meshes;
    g_quads += a.quad_count[0] + a.quad_count[1] + a.quad_count[2];
    static OutBufs oa, ob;
    const int32_t div = small_caps ? 4096 : 1;
    cv_mesh_out xa = out_of(oa, div), xb = out_of(ob, div);
    const int32_t ma = cv_mesh_cube(w, cx, cy, cz, flags, &xa);
    const int32_t mb = meshref::mesh_cube(w, cx, cy, cz, flags, &xb);
    ok = ok && ma == mb && xa.opaque_count == xb.opaque_count && xa.cutout_count == xb.cutout_count &&
         xa.translucent_count == xb.translucent_count && xa.host_count == xb.host_count &&
         xa.visibility == xb.visibility && xa.flags_out == xb.flags_out;
    if (ok && ma == CV_OK) {
        ok = std::memcmp(oa.q[0].data(), ob.q[0].data(), sizeof(cv_quad) * static_cast<size_t>(xa.opaque_count)) == 0 &&
             std::memcmp(oa.q[1].data(), ob.q[1].data(), sizeof(cv_quad) * static_cast<size_t>(xa.cutout_count)) == 0 &&
             std::memcmp(oa.q[2].data(), ob.q[2].data(), sizeof(cv_quad) * static_cast<size_t>(xa.translucent_count)) == 0 &&
             std::memcmp(oa.host.data(), ob.host.data(), sizeof(cv_host_cell) * static_cast<size_t>(xa.host_count)) == 0;
    }
    if (!ok && g_failures < 10) {
        std::printf("  mismatch cube (%lld, %lld, %lld) flags %u: rc %d/%d mesh %d/%d quads %d,%d,%d / %d,%d,%d\n",
                    static_cast<long long>(cx), static_cast<long long>(cy), static_cast<long long>(cz), flags, ra, rb,
                    ma, mb, a.quad_count[0], a.quad_count[1], a.quad_count[2], b.quad_count[0], b.quad_count[1],
                    b.quad_count[2]);
    }
    return ok;
}

void run_world(uint64_t seed, int& cubes) {
    Rng r(seed);
    Scene s;
    s.surf.seed = r.next();
    s.unbounded = r.pct(15);
    const int64_t far = r.pct(20) ? static_cast<int64_t>(r.next() % (int64_t{1} << 36)) - (int64_t{1} << 35) : 0;
    const int64_t ccy = s.unbounded ? (r.pct(50) ? far : static_cast<int64_t>(r.range(-4, 4)))
                                    : static_cast<int64_t>(r.range(-3, 2));
    s.min_y = s.unbounded ? CV_UNBOUNDED_MIN : -64 - 32 * static_cast<int64_t>(r.below(3)) - (r.pct(30) ? 16 : 0);
    s.max_y = s.unbounded ? CV_UNBOUNDED_MAX : 64 + 32 * static_cast<int64_t>(r.below(3)) - (r.pct(30) ? 8 : 0);
    s.surf.base = ccy * 32 + static_cast<int64_t>(r.below(70)) - 20;
    s.surf.amp = 1 + r.below(r.pct(30) ? 3 : 48);
    s.surf.unknown_pct = r.pct(30) ? r.below(100) : 0;
    cv_world_desc d{};
    d.min_y = s.min_y;
    d.max_y = s.max_y;
    d.surface = r.pct(85) ? surf_cb : nullptr;
    d.user = &s.surf;
    s.air = r.pct(85) ? AIR : static_cast<uint16_t>(r.pct(50) ? MOSS : PANE);
    d.air_id = s.air;
    s.w = cv_world_create(&d);
    configure(s, r);
    meshref::mesh_work().cls_flags = nullptr;   // the reference's class cache is keyed by pointers (see report)
    const int64_t ccx = far != 0 && r.pct(50) ? far : static_cast<int64_t>(r.range(-3, 3));
    const int64_t ccz = r.range(-3, 3);
    const int ckind = static_cast<int>(r.below(6));
    place(s, r, ccx, ccy, ccz, ckind);
    for (int dy = -1; dy <= 1; ++dy) {
        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = -1; dx <= 1; ++dx) {
                if ((dx | dy | dz) == 0 || r.pct(30)) continue;
                place(s, r, ccx + dx, ccy + dy, ccz + dz, r.pct(60) ? ckind : static_cast<int>(r.below(6)));
            }
        }
    }
    if (r.pct(40)) {   // the camera walk graphs cubes first: the mesher may then read the cached graph
        for (int k = 0; k < 4; ++k) cv_cube_visibility(s.w, ccx + r.range(-1, 1), ccy + r.range(-1, 1), ccz + r.range(-1, 1));
    }
    bool ok = true;
    for (uint32_t flags : {3u, 2u, 1u, 0u}) ok = compare_cube(s.w, ccx, ccy, ccz, flags, false) && ok;
    ++cubes;
    // a few edits (block writes + layer writes), then again
    for (int k = 0; k < 6; ++k) {
        const int64_t x = ccx * 32 + r.range(-1, 32), y = ccy * 32 + r.range(-1, 32), z = ccz * 32 + r.range(-1, 32);
        cv_set(s.w, x, y, z, static_cast<uint16_t>(r.below(kIds)));
        cv_layer_set(s.w, static_cast<int32_t>(1 + r.below(3)), x, y, z, static_cast<uint16_t>(r.below(20)));
    }
    ok = compare_cube(s.w, ccx, ccy, ccz, r.pct(50) ? 3u : r.below(4), r.pct(10)) && ok;
    // one neighbour as its own centre
    const int64_t nx = ccx + r.range(-1, 1), ny = ccy + r.range(-1, 1), nz = ccz + r.range(-1, 1);
    ok = compare_cube(s.w, nx, ny, nz, 3u, false) && ok;
    ok = compare_cube(s.w, nx, ny, nz, r.below(4), false) && ok;
    ++cubes;
    CHECK(ok);
    cv_world_destroy(s.w);
}

// ---- the benchmark terrain (voxel_mesh_test terrain_world) ----------------------------------------------------
void terrain_world(cv_world* w, int radius, int cy_lo, int cy_hi) {
    std::vector<uint16_t> ids(CV_CUBE_CELLS);
    for (int cz = -radius; cz <= radius; ++cz) {
        for (int cx = -radius; cx <= radius; ++cx) {
            for (int cy = cy_lo; cy <= cy_hi; ++cy) {
                if (cy < 0) {
                    cv_cube_create(w, cx, cy, cz, STONE);
                    continue;
                }
                if (cy > 0) {
                    cv_cube_create(w, cx, cy, cz, AIR);
                    continue;
                }
                for (int ly = 0; ly < 32; ++ly)
                    for (int lz = 0; lz < 32; ++lz)
                        for (int lx = 0; lx < 32; ++lx) {
                            const int x = cx * 32 + lx, z = cz * 32 + lz;
                            const int h = 14 + static_cast<int>(6.0 * std::sin(x * 0.11) + 5.0 * std::cos(z * 0.07) + 2.0 * std::sin((x + z) * 0.31));
                            uint16_t id = AIR;
                            if (ly < h - 3) id = ((x * 31 + ly * 17 + z * 7) % 53 == 0) ? GLASS : STONE;
                            else if (ly < h) id = DIRT;
                            else if (ly < h + 5 && ((x / 6 + z / 5) % 4 == 0) && ly > h) id = LEAVES;
                            ids[static_cast<size_t>(CV_CELL_INDEX(lx, ly, lz))] = id;
                        }
                cv_cube_write_ids(w, cx, cy, cz, ids.data());
            }
        }
    }
}

double now_us() {
    return std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

template <class F>
double best_of(int reps, int n, F&& f) {
    double best = 1e30;
    for (int k = 0; k < reps; ++k) {
        const double t = now_us();
        for (int i = 0; i < n; ++i) f(i);
        best = std::min(best, (now_us() - t) / n);
    }
    return best;
}

void bench_world(cv_world* w, const char* label) {
    const int n = 100;
    GpuMeshBuild b;
    // A fresh cube (block table republished: every cached visibility graph is stale, as after an install or an
    // edit; the class table is rebuilt outside the timing).
    auto fresh = [&](bool reference) {
        double best = 1e30;
        for (int rep = 0; rep < 12; ++rep) {
            cv_block_table_set(w, kRealFlags, kIds);
            mesh_prepare(w, 0, 0, 0, 3, mesh_work());
            meshref::mesh_prepare(w, 0, 0, 0, 3, meshref::mesh_work());
            const double t = now_us();
            for (int i = 0; i < 25; ++i) {
                if (reference) meshref::build_gpu_mesh(w, i % 5 - 2, 0, i / 5 - 2, 3, b);
                else build_gpu_mesh(w, i % 5 - 2, 0, i / 5 - 2, 3, b);
            }
            best = std::min(best, (now_us() - t) / 25);
        }
        return best;
    };
    const double lib_fresh = std::min(fresh(false), fresh(false)), ref_fresh = fresh(true);
    std::printf("  [%s] fresh surface cube build_gpu_mesh smooth+greedy: %.1f us (reference %.1f us)\n", label, lib_fresh,
                ref_fresh);
    auto surf = [&](int i) { build_gpu_mesh(w, i % 5 - 2, 0, (i / 5) % 5 - 2, 3, b); };
    auto surf_ref = [&](int i) { meshref::build_gpu_mesh(w, i % 5 - 2, 0, (i / 5) % 5 - 2, 3, b); };
    double lib = 1e30, ref = 1e30;
    for (int k = 0; k < 4; ++k) {   // interleaved: both sides see the same machine load
        lib = std::min(lib, best_of(3, n, surf));
        ref = std::min(ref, best_of(3, n, surf_ref));
    }
    const double lib_flat = best_of(3, n, [&](int i) { build_gpu_mesh(w, i % 5 - 2, 0, (i / 5) % 5 - 2, 2, b); });
    const double ref_flat = best_of(3, n, [&](int i) { meshref::build_gpu_mesh(w, i % 5 - 2, 0, (i / 5) % 5 - 2, 2, b); });
    std::printf("  [%s] remeshed surface cube (cached visibility) build_gpu_mesh smooth+greedy: %.1f us (reference %.1f us); "
                "flat+greedy %.1f us (%.1f)\n",
                label, lib, ref, lib_flat, ref_flat);
    // phase breakdown (library), cube (0, 0, 0)
    MeshWork& mw = mesh_work();
    static PaddedCube pc;
    const double rp = best_of(5, n, [&](int) { read_padded(w, 0, 0, 0, pc, nullptr, nullptr, 0); });
    const double prep = best_of(5, n, [&](int) { mesh_prepare(w, 0, 0, 0, 3, mw); });
    std::vector<cv_quad> qs[3];
    const double faces = best_of(5, n, [&](int) {
        for (auto& q : qs) q.clear();
        mw.ring_ready = false;
        mesh_cube_faces(mw, true, qs);
    });
    const double ring = best_of(5, n, [&](int) {
        mw.ring_ready = false;
        mw.sky_ring();
    });
    const double vis = best_of(5, n, [&](int) { visibility_from_rows(mw.closed); });
    const double total = best_of(5, n, [&](int) { build_gpu_mesh(w, 0, 0, 0, 3, b); });
    MeshWork& rw = meshref::mesh_work();
    const double rprep = best_of(5, n, [&](int) { meshref::mesh_prepare(w, 0, 0, 0, 3, rw); });
    const double rfaces = best_of(5, n, [&](int) {
        for (auto& q : qs) q.clear();
        rw.ring_ready = false;
        meshref::mesh_cube_faces(rw, true, qs);
    });
    const double rtotal = best_of(5, n, [&](int) { meshref::build_gpu_mesh(w, 0, 0, 0, 3, b); });
    std::printf("  [%s] cube (0,0,0): total %.1f us (ref %.1f); read_padded %.1f, classify %.1f (ref %.1f), "
                "faces+light+merge %.1f (ref %.1f; sky ring %.1f), visibility graph %.1f, rest %.1f (ref %.1f); "
                "%d quads\n",
                label, total, rtotal, rp, prep - rp, rprep - rp, faces, rfaces, ring, vis, total - prep - faces,
                rtotal - rprep - rfaces, b.quad_count[0] + b.quad_count[1] + b.quad_count[2]);
}

void bench() {
    cv_world* w = cv_world_create([] {
        static cv_world_desc d{};
        d.min_y = -1024;
        d.max_y = 1024;
        d.air_id = AIR;
        return &d;
    }());
    cv_block_table_set(w, kRealFlags, kIds);
    terrain_world(w, 3, -2, 2);
    meshref::mesh_work().cls_flags = nullptr;
    bool ok = true;
    for (int cz = -2; cz <= 2; ++cz)
        for (int cx = -2; cx <= 2; ++cx)
            for (int cy = -1; cy <= 1; ++cy)
                for (uint32_t f : {0u, 1u, 2u, 3u}) ok = compare_cube(w, cx, cy, cz, f, false) && ok;
    CHECK(ok);
    if (std::getenv("VOXEL_MESH_DIFF_NO_BENCH") == nullptr) bench_world(w, "plain");
    // the game's configuration: face textures for every id + the two live face rules
    for (uint16_t id = 1; id < kIds; ++id) {
        cv_face_tex t[6];
        for (int f = 0; f < 6; ++f) t[f] = cv_face_tex{static_cast<uint16_t>(id * 6 + f), static_cast<uint8_t>(f & 3), 0};
        cv_block_faces_set(w, id, t);
    }
    const uint16_t snow[1] = {SNOW};
    cv_face_rule_add(w, ICE, -1, 0, 1u, CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, snow, 1);
    cv_face_rule_add(w, LEAVES, -1, 0, 1u, CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, snow, 1);
    ok = true;
    for (int cz = -2; cz <= 2; ++cz)
        for (int cx = -2; cx <= 2; ++cx)
            for (uint32_t f : {0u, 1u, 2u, 3u}) ok = compare_cube(w, cx, 0, cz, f, false) && ok;
    CHECK(ok);
    if (std::getenv("VOXEL_MESH_DIFF_NO_BENCH") == nullptr) bench_world(w, "game config");
    cv_world_destroy(w);
}

} // namespace

int main() {
    int worlds = 1500;
    if (const char* e = std::getenv("VOXEL_MESH_DIFF_WORLDS")) worlds = std::max(1, std::atoi(e));
    std::printf("[voxel_mesh_diff] %d random worlds\n", worlds);
    int cubes = 0;
    for (int i = 0; i < worlds; ++i) run_world(0xC0FFEEull + static_cast<uint64_t>(i) * 7919u, cubes);
    std::printf("[voxel_mesh_diff] %d centre cubes, %ld meshes (%ld quads) compared\n", cubes, g_meshes, g_quads);
    std::printf("[voxel_mesh_diff] benchmark terrain\n");
    bench();
    std::printf("[voxel_mesh_diff] %ld checks, %d failures, %ld meshes compared\n", g_checks, g_failures, g_meshes);
    return g_failures == 0 ? 0 : 1;
}
