// ctest voxel_mesh: the native cube mesher + visibility (OpenMason #143/#123, Phase 1b): cv_mesh_cube against
// a brute-force reference (unit-face reconstruction of the greedy output, identical corner lights), the
// transparent/translucent rules, 32-wide merges, the 15-bit visibility graph against a brute-force flood,
// the camera walk (terminal unknown, open known air, frustum, no backtracking), host models, fluids,
// oversize (multi-cell) models, golden packing vectors of the Java codecs, concurrency and timings.
#include "cenda/voxel_world.h"
#include "cv_internal.hpp"
#include "mesh_gpu.hpp"
#include "mesh_internal.hpp"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <random>
#include <set>
#include <thread>
#include <tuple>
#include <vector>
#include <initializer_list>
#include <utility>

using namespace cenda::voxel;

namespace {

int g_failures = 0;
int g_checks = 0;

#define CHECK(...)                                                                                  \
    do {                                                                                            \
        ++g_checks;                                                                                 \
        if (!(__VA_ARGS__)) {                                                                       \
            ++g_failures;                                                                           \
            if (g_failures < 60) std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #__VA_ARGS__); \
        }                                                                                           \
    } while (0)

constexpr uint16_t AIR = 0;
constexpr uint16_t STONE = 1;
constexpr uint16_t DIRT = 2;
constexpr uint16_t LEAVES = 3;   // cutout cube, not OPAQUE
constexpr uint16_t GLASS = 4;    // cutout cube
constexpr uint16_t ICE = 5;      // translucent cube
constexpr uint16_t WATER = 6;    // fluid (host geometry)
constexpr uint16_t SNOW = 7;     // host geometry with per-layer models (not TRANSPARENT)
constexpr uint16_t FLOWER = 8;   // host geometry, transparent, no model (listed as a host cell)
constexpr uint16_t CUBEMODEL = 9;  // host geometry bound to a full-cube model
constexpr uint16_t GRANITE = 10; // opaque cube, NOT vis-opaque (tests the VIS flag)
constexpr int kIds = 11;

constexpr uint16_t kOpaque = CV_BLOCK_CUBE | CV_BLOCK_OPAQUE | CV_BLOCK_SKY_OPAQUE | CV_BLOCK_VIS_OPAQUE;
const uint16_t kFlags[kIds] = {
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
};

int idx(int x, int y, int z) { return CV_CELL_INDEX(x, y, z); }

struct SurfaceGen {
    int64_t top = 0;     // never-generated TOP (and sky) answer
    bool known = true;
};

int32_t surface_cb(void* user, int64_t, int64_t, int64_t* sky, int64_t* top) {
    auto* g = static_cast<SurfaceGen*>(user);
    if (!g->known) return 0;
    for (int i = 0; i < 1024; ++i) {
        sky[i] = g->top;
        top[i] = g->top;
    }
    return 1;
}

cv_world* make_world(int64_t min_y, int64_t max_y, SurfaceGen* gen = nullptr) {
    cv_world_desc d{};
    d.min_y = min_y;
    d.max_y = max_y;
    d.surface = gen != nullptr ? surface_cb : nullptr;
    d.user = gen;
    d.air_id = AIR;
    cv_world* w = cv_world_create(&d);
    cv_block_table_set(w, kFlags, kIds);
    return w;
}

uint16_t flag_of(uint16_t id) { return id < kIds ? kFlags[id] : uint16_t{0}; }

// ---- brute-force reference --------------------------------------------------------------------------------

uint16_t ref_get(cv_world* w, int64_t x, int64_t y, int64_t z) {
    const int32_t r = cv_get(w, x, y, z);
    return r < 0 ? AIR : static_cast<uint16_t>(r);
}
bool ref_see(uint16_t id) {
    return id == AIR || (flag_of(id) & (CV_BLOCK_TRANSPARENT | CV_BLOCK_TRANSLUCENT)) != 0 || id == WATER;
}
bool ref_solid(cv_world* w, int64_t x, int64_t y, int64_t z) {
    const int32_t r = cv_get(w, x, y, z);
    return r >= 0 && (flag_of(static_cast<uint16_t>(r)) & CV_BLOCK_OPAQUE) != 0;
}
int64_t ref_surface(cv_world* w, int64_t x, int64_t z) {
    int64_t y = 0;
    cv_surface_y(w, x, z, &y);
    return y;
}

const int kAoN[6][3] = {{0, 0, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 0}, {0, 0, 0}, {-1, 0, 0}};
const int kAoT1[6][3] = {{-1, 0, 0}, {-1, 0, 0}, {-1, 0, 0}, {-1, 0, 0}, {0, -1, 0}, {0, -1, 0}};
const int kAoT2[6][3] = {{0, 0, -1}, {0, 0, -1}, {0, -1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, -1}};

uint8_t ref_light(cv_world* w, int64_t bx, int64_t by, int64_t bz, int face, int corner, bool smooth) {
    const int64_t vx = bx + kFaceOffsets[face][corner][0];
    const int64_t vy = by + kFaceOffsets[face][corner][1];
    const int64_t vz = bz + kFaceOffsets[face][corner][2];
    int lit = 0, sampled = 0;
    const int lo = smooth ? -1 : 0;
    for (int a = lo; a <= 0; ++a) {
        for (int b = lo; b <= 0; ++b) {
            int64_t cx, cy, cz;
            switch (face) {
                case 0: cx = vx + a; cy = vy; cz = vz + b; break;
                case 1: cx = vx + a; cy = vy - 1; cz = vz + b; break;
                case 2: cx = vx + a; cy = vy + b; cz = vz - 1; break;
                case 3: cx = vx + a; cy = vy + b; cz = vz; break;
                case 4: cx = vx; cy = vy + a; cz = vz + b; break;
                default: cx = vx - 1; cy = vy + a; cz = vz + b; break;
            }
            ++sampled;
            if (cy >= ref_surface(w, cx, cz)) ++lit;
        }
    }
    float light = static_cast<float>(lit) / static_cast<float>(sampled);
    if (smooth) {
        const int* n = kAoN[face];
        const int* t1 = kAoT1[face];
        const int* t2 = kAoT2[face];
        const bool s1 = ref_solid(w, vx + n[0] + t1[0], vy + n[1] + t1[1], vz + n[2] + t1[2]);
        const bool s2 = ref_solid(w, vx + n[0] + t2[0], vy + n[1] + t2[1], vz + n[2] + t2[2]);
        const bool c = ref_solid(w, vx + n[0] + t1[0] + t2[0], vy + n[1] + t1[1] + t2[1], vz + n[2] + t1[2] + t2[2]);
        int count = (s1 ? 1 : 0) + (s2 ? 1 : 0) + (c ? 1 : 0);
        if (s1 && s2) count = 3;
        light *= 1.0f - 0.13f * static_cast<float>(count);
    }
    return to_u8(light);
}

struct RefFace {
    uint16_t id;
    int list;
    std::array<uint8_t, 4> light;
};
using FaceKey = std::tuple<int, int, int, int>;   // lx, ly, lz, face

std::map<FaceKey, RefFace> ref_faces(cv_world* w, int64_t cx, int64_t cy, int64_t cz, bool smooth) {
    std::map<FaceKey, RefFace> out;
    for (int ly = 0; ly < 32; ++ly) {
        for (int lz = 0; lz < 32; ++lz) {
            for (int lx = 0; lx < 32; ++lx) {
                const int64_t x = cx * 32 + lx, y = cy * 32 + ly, z = cz * 32 + lz;
                const uint16_t id = ref_get(w, x, y, z);
                const uint16_t f = flag_of(id);
                if ((f & CV_BLOCK_CUBE) == 0 || id == AIR) continue;
                const bool transp = (f & (CV_BLOCK_TRANSPARENT | CV_BLOCK_TRANSLUCENT)) != 0;
                for (int face = 0; face < 6; ++face) {
                    const uint16_t n = ref_get(w, x + kFaceDx[face], y + kFaceDy[face], z + kFaceDz[face]);
                    bool vis;
                    if (!ref_see(n)) vis = false;   // unbound host cells (SNOW is model-bound only in model tests)
                    else if (!transp) vis = true;
                    else vis = n == AIR || n != id;
                    if (!vis) continue;
                    RefFace rf{id, !transp ? 0 : ((f & CV_BLOCK_TRANSLUCENT) != 0 ? 2 : 1), {}};
                    for (int c = 0; c < 4; ++c) rf.light[static_cast<size_t>(c)] = ref_light(w, x, y, z, face, c, smooth);
                    out.emplace(FaceKey{lx, ly, lz, face}, rf);
                }
            }
        }
    }
    return out;
}

struct MeshLists {
    std::vector<cv_quad> q[3];
    std::vector<cv_host_cell> host;
    uint16_t visibility = 0;
    uint32_t flags_out = 0;
    int32_t rc = 0;
};

MeshLists mesh(cv_world* w, int64_t cx, int64_t cy, int64_t cz, uint32_t flags) {
    MeshLists m;
    for (auto& v : m.q) v.resize(16);
    m.host.resize(4);
    for (int attempt = 0; attempt < 2; ++attempt) {
        cv_mesh_out out{};
        out.opaque = m.q[0].data();
        out.opaque_cap = static_cast<int32_t>(m.q[0].size());
        out.cutout = m.q[1].data();
        out.cutout_cap = static_cast<int32_t>(m.q[1].size());
        out.translucent = m.q[2].data();
        out.translucent_cap = static_cast<int32_t>(m.q[2].size());
        out.host_cells = m.host.data();
        out.host_cap = static_cast<int32_t>(m.host.size());
        m.rc = cv_mesh_cube(w, cx, cy, cz, flags, &out);
        if (CV_IS_NEED(m.rc)) {
            CHECK(attempt == 0);
            m.q[0].resize(static_cast<size_t>(std::max(out.opaque_count, 1)));
            m.q[1].resize(static_cast<size_t>(std::max(out.cutout_count, 1)));
            m.q[2].resize(static_cast<size_t>(std::max(out.translucent_count, 1)));
            m.host.resize(static_cast<size_t>(std::max(out.host_count, 1)));
            continue;
        }
        m.q[0].resize(static_cast<size_t>(out.opaque_count));
        m.q[1].resize(static_cast<size_t>(out.cutout_count));
        m.q[2].resize(static_cast<size_t>(out.translucent_count));
        m.host.resize(static_cast<size_t>(out.host_count));
        m.visibility = out.visibility;
        m.flags_out = out.flags_out;
        break;
    }
    return m;
}

// Unit-face reconstruction of the (greedy) output must equal the brute-force faces with identical lights.
// `adjust` (optional) edits the reference before the comparison (face rules).
void compare_with_reference(cv_world* w, int64_t cx, int64_t cy, int64_t cz, uint32_t flags,
                            void (*adjust)(cv_world*, int64_t, int64_t, int64_t, std::map<FaceKey, RefFace>&) = nullptr) {
    auto ref = ref_faces(w, cx, cy, cz, (flags & CV_MESH_SMOOTH_LIGHTING) != 0);
    if (adjust != nullptr) adjust(w, cx, cy, cz, ref);
    const MeshLists m = mesh(w, cx, cy, cz, flags);
    CHECK(m.rc == CV_OK);
    std::set<FaceKey> seen;
    bool ok = true;
    for (int list = 0; list < 3; ++list) {
        for (const cv_quad& q : m.q[list]) {
            if (q.face > 5) ok = false;   // the internal forced-opaque mark never leaks
            const bool merged = q.w * q.h > 1;
            if (merged && (q.light[0] != q.light[1] || q.light[0] != q.light[2] || q.light[0] != q.light[3])) ok = false;
            if ((flags & CV_MESH_GREEDY) == 0 && merged) ok = false;
            for (int i = 0; i < q.w; ++i) {
                for (int j = 0; j < q.h; ++j) {
                    int p[3] = {q.x, q.y, q.z};
                    p[kUAxis[q.face]] += i;
                    p[kVAxis[q.face]] += j;
                    const FaceKey k{p[0], p[1], p[2], q.face};
                    if (p[0] > 31 || p[1] > 31 || p[2] > 31 || !seen.insert(k).second) {
                        ok = false;
                        continue;
                    }
                    auto it = ref.find(k);
                    if (it == ref.end()) {
                        ok = false;
                        continue;
                    }
                    if (it->second.id != q.id || it->second.list != list) ok = false;
                    for (int c = 0; c < 4; ++c) {
                        if (it->second.light[static_cast<size_t>(c)] != q.light[c]) ok = false;
                    }
                }
            }
        }
    }
    CHECK(ok);
    CHECK(seen.size() == ref.size());
}

std::mt19937 g_rng(12345);
uint32_t rnd(uint32_t n) { return static_cast<uint32_t>(g_rng() % n); }

void fill_random(cv_world* w, int64_t cx, int64_t cy, int64_t cz, int kind) {
    std::vector<uint16_t> ids(CV_CUBE_CELLS, AIR);
    const uint16_t palette[] = {STONE, DIRT, LEAVES, GLASS, ICE, FLOWER, GRANITE};
    for (int ly = 0; ly < 32; ++ly) {
        for (int lz = 0; lz < 32; ++lz) {
            for (int lx = 0; lx < 32; ++lx) {
                uint16_t id = AIR;
                if (kind == 0) {   // noise
                    if (rnd(100) < 45) id = palette[rnd(7)];
                } else if (kind == 1) {   // terrain: stone below a height field, dirt, leaves blobs
                    const int h = 10 + (lx * 7 + lz * 3) % 13 + static_cast<int>(rnd(2));
                    if (ly < h - 3) id = rnd(40) == 0 ? GLASS : STONE;
                    else if (ly < h) id = DIRT;
                    else if (ly < h + 4 && (lx / 5 + lz / 7) % 3 == 0) id = rnd(3) == 0 ? AIR : LEAVES;
                    else if (ly == h && rnd(9) == 0) id = FLOWER;
                } else {   // ice/glass heavy (transparent rules)
                    const uint32_t r = rnd(10);
                    id = r < 3 ? ICE : r < 5 ? GLASS : r < 6 ? LEAVES : r < 7 ? STONE : AIR;
                }
                ids[static_cast<size_t>(idx(lx, ly, lz))] = id;
            }
        }
    }
    cv_cube_write_ids(w, cx, cy, cz, ids.data());
}

void test_reference() {
    const uint32_t modes[] = {CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY, CV_MESH_SMOOTH_LIGHTING, CV_MESH_GREEDY, 0};
    for (int round = 0; round < 12; ++round) {
        SurfaceGen gen;
        gen.top = round % 3 == 0 ? 1000 : (round % 3 == 1 ? 40 : -1000);
        gen.known = round % 4 != 3;
        cv_world* w = make_world(-64, 96, &gen);
        const int kind = round % 3;
        fill_random(w, 0, 0, 0, kind);
        // neighbours: some present (random content), some missing
        for (int dy = -1; dy <= 1; ++dy) {
            for (int dz = -1; dz <= 1; ++dz) {
                for (int dx = -1; dx <= 1; ++dx) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    if (rnd(3) == 0) continue;
                    fill_random(w, dx, dy, dz, static_cast<int>(rnd(3)));
                }
            }
        }
        for (uint32_t mode : modes) compare_with_reference(w, 0, 0, 0, mode);
        // a cube touching the vertical bound (cy 2 = y 64..95, above it is outside)
        fill_random(w, 0, 2, 0, kind);
        compare_with_reference(w, 0, 2, 0, CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY);
        // negative coordinates
        fill_random(w, -3, -2, -5, kind);
        compare_with_reference(w, -3, -2, -5, CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY);
        cv_world_destroy(w);
    }
}

void test_rules_and_merge() {
    cv_world* w = make_world(-1024, 1024);
    std::vector<uint16_t> ids(CV_CUBE_CELLS, AIR);
    // a 32x32 stone floor at ly 10: the top merges into ONE 32x32 quad (constant light)
    for (int lz = 0; lz < 32; ++lz)
        for (int lx = 0; lx < 32; ++lx) ids[static_cast<size_t>(idx(lx, 10, lz))] = STONE;
    cv_cube_write_ids(w, 0, 0, 0, ids.data());
    MeshLists m = mesh(w, 0, 0, 0, CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY);
    int tops = 0;
    for (const cv_quad& q : m.q[0]) {
        if (q.face == 0) {
            ++tops;
            CHECK(q.w == 32 && q.h == 32 && q.x == 0 && q.y == 10 && q.z == 0 && q.light[0] == 255);
        }
    }
    CHECK(tops == 1);
    compare_with_reference(w, 0, 0, 0, CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY);
    // a 32-tall stone wall (x = 5): the east face merges 32 (z) x 32 (y)? light varies with height, so it
    // must not; under a roof everything is dark: put a roof cube above so the sky is 0 everywhere.
    std::fill(ids.begin(), ids.end(), AIR);
    for (int ly = 0; ly < 32; ++ly)
        for (int lz = 0; lz < 32; ++lz) ids[static_cast<size_t>(idx(5, ly, lz))] = STONE;
    cv_cube_write_ids(w, 4, 0, 0, ids.data());
    cv_cube_create(w, 4, 1, 0, STONE);
    for (int dz = -1; dz <= 1; ++dz)
        for (int dx = -1; dx <= 1; ++dx) cv_cube_create(w, 4 + dx, 1, dz, STONE);
    m = mesh(w, 4, 0, 0, CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY);
    int easts = 0;
    for (const cv_quad& q : m.q[0]) {
        if (q.face == 4) {
            ++easts;
            CHECK(q.w == 32 && q.h == 32 && q.light[0] == 0);
        }
    }
    CHECK(easts == 1);
    compare_with_reference(w, 4, 0, 0, CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY);

    // transparent rules
    auto faces_of = [&](int64_t cx, int lx, int ly, int lz) {
        MeshLists mm = mesh(w, cx, 0, 0, CV_MESH_SMOOTH_LIGHTING);
        std::set<int> f;
        for (int l = 0; l < 3; ++l)
            for (const cv_quad& q : mm.q[l])
                if (q.x == lx && q.y == ly && q.z == lz) f.insert(q.face);
        return f;
    };
    auto put = [&](int64_t cx, std::initializer_list<std::tuple<int, int, int, uint16_t>> cells) {
        std::vector<uint16_t> v(CV_CUBE_CELLS, AIR);
        for (auto [x, y, z, id] : cells) v[static_cast<size_t>(idx(x, y, z))] = id;
        cv_cube_write_ids(w, cx, 0, 0, v.data());
    };
    put(20, {{5, 5, 5, LEAVES}, {6, 5, 5, LEAVES}});
    CHECK(faces_of(20, 5, 5, 5).count(4) == 0);          // leaves vs leaves: culled
    put(20, {{5, 5, 5, LEAVES}, {6, 5, 5, STONE}});
    CHECK(faces_of(20, 5, 5, 5).count(4) == 0);          // leaves vs stone: culled
    CHECK(faces_of(20, 6, 5, 5).count(5) == 1);          // stone vs leaves: drawn
    put(20, {{5, 5, 5, LEAVES}, {6, 5, 5, GLASS}});
    CHECK(faces_of(20, 5, 5, 5).count(4) == 1 && faces_of(20, 6, 5, 5).count(5) == 1);
    put(20, {{5, 5, 5, ICE}, {6, 5, 5, ICE}});
    CHECK(faces_of(20, 5, 5, 5).count(4) == 0);
    put(20, {{5, 5, 5, ICE}, {6, 5, 5, STONE}});
    CHECK(faces_of(20, 5, 5, 5).count(4) == 0 && faces_of(20, 6, 5, 5).count(5) == 1);
    put(20, {{5, 5, 5, ICE}});
    {
        MeshLists mm = mesh(w, 20, 0, 0, 0);
        CHECK(mm.q[2].size() == 6 && mm.q[0].empty() && mm.q[1].empty());   // translucent list
    }
    put(20, {{5, 5, 5, FLOWER}, {6, 5, 5, STONE}});
    {
        MeshLists mm = mesh(w, 20, 0, 0, 0);
        CHECK(mm.host.size() == 1 && mm.host[0].id == FLOWER && mm.host[0].index == idx(5, 5, 5));
        CHECK(faces_of(20, 6, 5, 5).count(5) == 1);   // stone face toward a transparent host cell
    }
    // overflow protocol: every count is written, the first short list's need is returned
    {
        put(20, {{1, 1, 1, STONE}, {3, 3, 3, GLASS}});
        cv_quad one[1];
        cv_mesh_out out{};
        out.opaque = one;
        out.opaque_cap = 1;
        const int32_t rc = cv_mesh_cube(w, 20, 0, 0, 0, &out);
        CHECK(CV_IS_NEED(rc) && CV_NEEDED(rc) == 6 && out.opaque_count == 6 && out.cutout_count == 6);
        CHECK(cv_mesh_cube(w, 99, 0, 0, 0, &out) == CV_ERR_MISSING);
    }
    // empty cube
    cv_cube_create(w, 30, 0, 0, AIR);
    m = mesh(w, 30, 0, 0, 3);
    CHECK(m.flags_out == 1 && m.visibility == kAllPairs);
    cv_world_destroy(w);
}

// ---- visibility -------------------------------------------------------------------------------------------

uint16_t brute_visibility(const std::vector<uint16_t>& ids) {
    std::vector<int> comp(CV_CUBE_CELLS, -1);
    uint16_t bits = 0;
    int ncomp = 0;
    std::vector<int> stack;
    for (int start = 0; start < CV_CUBE_CELLS; ++start) {
        if (comp[static_cast<size_t>(start)] >= 0 || (flag_of(ids[static_cast<size_t>(start)]) & CV_BLOCK_VIS_OPAQUE) != 0) continue;
        uint8_t faces = 0;
        stack.assign(1, start);
        comp[static_cast<size_t>(start)] = ncomp;
        while (!stack.empty()) {
            const int c = stack.back();
            stack.pop_back();
            const int x = c & 31, z = (c >> 5) & 31, y = c >> 10;
            if (y == 31) faces |= 1;
            if (y == 0) faces |= 2;
            if (z == 0) faces |= 4;
            if (z == 31) faces |= 8;
            if (x == 31) faces |= 16;
            if (x == 0) faces |= 32;
            for (int f = 0; f < 6; ++f) {
                const int nx = x + kFaceDx[f], ny = y + kFaceDy[f], nz = z + kFaceDz[f];
                if (nx < 0 || ny < 0 || nz < 0 || nx > 31 || ny > 31 || nz > 31) continue;
                const int n = idx(nx, ny, nz);
                if (comp[static_cast<size_t>(n)] >= 0 || (flag_of(ids[static_cast<size_t>(n)]) & CV_BLOCK_VIS_OPAQUE) != 0) continue;
                comp[static_cast<size_t>(n)] = ncomp;
                stack.push_back(n);
            }
        }
        for (int a = 0; a < 6; ++a)
            for (int b = a + 1; b < 6; ++b)
                if (((faces >> a) & 1) != 0 && ((faces >> b) & 1) != 0) bits = static_cast<uint16_t>(bits | (1u << pair_bit(a, b)));
        ++ncomp;
    }
    return bits;
}

void test_visibility() {
    cv_world* w = make_world(-1024, 1024);
    std::vector<uint16_t> ids(CV_CUBE_CELLS);
    for (int round = 0; round < 60; ++round) {
        const uint32_t density = 20 + rnd(60);
        for (auto& v : ids) v = rnd(100) < density ? (rnd(5) == 0 ? GRANITE : STONE) : (rnd(4) == 0 ? LEAVES : AIR);
        if (round % 5 == 0) {   // walls and tubes
            std::fill(ids.begin(), ids.end(), AIR);
            for (int z = 0; z < 32; ++z)
                for (int x = 0; x < 32; ++x) ids[static_cast<size_t>(idx(x, 16, z))] = STONE;
            if (round % 10 == 0) ids[static_cast<size_t>(idx(7, 16, 9))] = AIR;   // a hole
        }
        cv_cube_write_ids(w, 0, 0, 0, ids.data());
        const uint16_t want = brute_visibility(ids);
        CHECK(cv_cube_visibility(w, 0, 0, 0) == want);
        CHECK(mesh(w, 0, 0, 0, 0).visibility == want);
        GpuMeshBuild b;
        CHECK(build_gpu_mesh(w, 0, 0, 0, 0, b) == CV_OK && b.visibility == want);
    }
    // the wall: top/bottom not connected, sides connected
    std::fill(ids.begin(), ids.end(), AIR);
    for (int z = 0; z < 32; ++z)
        for (int x = 0; x < 32; ++x) ids[static_cast<size_t>(idx(x, 16, z))] = STONE;
    cv_cube_write_ids(w, 0, 0, 0, ids.data());
    const int vis = cv_cube_visibility(w, 0, 0, 0);
    CHECK((vis & (1 << pair_bit(0, 1))) == 0);
    CHECK((vis & (1 << pair_bit(2, 3))) != 0 && (vis & (1 << pair_bit(0, 4))) != 0);
    // cache invalidation: open a hole -> top/bottom connect
    cv_set(w, 3, 16, 3, AIR);
    CHECK((cv_cube_visibility(w, 0, 0, 0) & (1 << pair_bit(0, 1))) != 0);
    // block table change (STONE no longer VIS_OPAQUE) -> everything connects
    uint16_t flags2[kIds];
    std::memcpy(flags2, kFlags, sizeof flags2);
    flags2[STONE] = static_cast<uint16_t>(flags2[STONE] & ~CV_BLOCK_VIS_OPAQUE);
    cv_block_table_set(w, flags2, kIds);
    CHECK(cv_cube_visibility(w, 0, 0, 0) == kAllPairs);
    cv_block_table_set(w, kFlags, kIds);
    CHECK((cv_cube_visibility(w, 0, 0, 0) & (1 << pair_bit(0, 1))) != 0);
    CHECK(cv_cube_visibility(w, 5, 5, 5) == CV_ERR_MISSING);
    cv_cube_create(w, 1, 0, 0, STONE);
    CHECK(cv_cube_visibility(w, 1, 0, 0) == 0);
    cv_world_destroy(w);
}

// ---- walk ---------------------------------------------------------------------------------------------------

std::set<std::tuple<int64_t, int64_t, int64_t>> as_set(const std::vector<int64_t>& v) {
    std::set<std::tuple<int64_t, int64_t, int64_t>> s;
    for (size_t i = 0; i + 2 < v.size(); i += 3) s.emplace(v[i], v[i + 1], v[i + 2]);
    return s;
}

void test_walk() {
    // all present and open (air cubes): every cube within the radius is visible
    {
        cv_world* w = make_world(-1024, 1024);
        for (int y = -3; y <= 3; ++y)
            for (int z = -3; z <= 3; ++z)
                for (int x = -3; x <= 3; ++x) cv_cube_create(w, x, y, z, AIR);
        VisibleWalk vw;
        CHECK(walk_visible(w, 0, 0, 0, 2, nullptr, vw) == CV_OK);
        CHECK(vw.present.size() == 3 * 125 && vw.missing.empty());
        // a solid cube at +x: the cube behind it (2,0,0) is hidden (no stepping back toward the camera)
        cv_cube_remove(w, 1, 0, 0);
        cv_cube_create(w, 1, 0, 0, STONE);
        CHECK(walk_visible(w, 0, 0, 0, 2, nullptr, vw) == CV_OK);
        auto s = as_set(vw.present);
        CHECK(s.count({1, 0, 0}) == 1 && s.count({2, 0, 0}) == 0 && s.count({2, 1, 0}) == 1);
        // frustum: only x >= 0 half-space (plane x + 0 >= 0 for the cube's max corner -> cubes dx >= 0... use
        // x - 1 >= 0 so the cube must reach past x = 1 block)
        const float planes[24] = {1, 0, 0, -1, 0, 0, 0, 1e9f, 0, 0, 0, 1e9f, 0, 0, 0, 1e9f, 0, 0, 0, 1e9f, 0, 0, 0, 1e9f};
        CHECK(walk_visible(w, 0, 0, 0, 2, planes, vw) == CV_OK);
        bool ok = true;
        for (auto [x, y, z] : as_set(vw.present)) {
            (void)y;
            (void)z;
            if (x < 0) ok = false;
        }
        CHECK(ok && as_set(vw.present).count({0, 0, 0}) == 1 && as_set(vw.present).count({2, 1, 0}) == 1);
        // C ABI: sizes and NEED
        CHECK(walk_visible(w, 0, 0, 0, 2, nullptr, vw) == CV_OK);
        const auto n = static_cast<int64_t>(vw.present.size() / 3);
        CHECK(n > 100 && n < 125);
        int64_t buf[3 * 200];
        int64_t miss[3];
        int64_t nmiss = -1;
        CHECK(cv_visible_cubes(w, 0, 0, 0, 2, nullptr, buf, 200, miss, 1, &nmiss) == n);
        CHECK(nmiss == 0 && as_set(std::vector<int64_t>(buf, buf + 3 * n)) == as_set(vw.present));
        const int64_t r = cv_visible_cubes(w, 0, 0, 0, 2, nullptr, buf, 10, nullptr, 0, &nmiss);
        CHECK(CV_IS_NEED(r) && CV_NEEDED(r) == n);
        CHECK(walk_visible(w, 0, 0, 0, -1, nullptr, vw) == CV_ERR_ARG);
        cv_world_destroy(w);
    }
    // unknown missing cubes are terminal; known-air missing cubes are open
    {
        SurfaceGen gen;
        gen.top = 32;   // never-generated columns: terrain up to y 32 -> cubes cy 0 unknown, cy >= 1 known air
        cv_world* w = make_world(-1024, 1024, &gen);
        cv_cube_create(w, 0, 0, 0, AIR);   // the camera cube (its column now has a record: top = 0 here)
        VisibleWalk vw;
        CHECK(walk_visible(w, 0, 0, 0, 2, nullptr, vw) == CV_OK);
        auto missing = as_set(vw.missing);
        // (1,0,0) is never generated, below its top: unknown -> missing, and (2,0,0) beyond it is reached only
        // around it through known air (cy 1) and then never back down
        CHECK(missing.count({1, 0, 0}) == 1 && missing.count({-1, 0, 0}) == 1);
        CHECK(missing.count({2, 0, 0}) == 0);
        CHECK(missing.count({1, 1, 0}) == 0);   // known air: open, not reported
        // the camera's column: cube 0 is generated (air); cube -1 was never generated and lies below the
        // generator surface (32), so it is solid in the TOP answer -> unknown -> reported
        CHECK(missing.count({0, -1, 0}) == 1);
        CHECK(vw.present.size() == 3);
        cv_world_destroy(w);
    }
    // vertical bounds: nothing outside is walked or reported
    {
        cv_world* w = make_world(0, 64);
        cv_cube_create(w, 0, 0, 0, AIR);
        cv_cube_create(w, 0, 1, 0, AIR);
        VisibleWalk vw;
        CHECK(walk_visible(w, 0, 0, 0, 3, nullptr, vw) == CV_OK);
        bool ok = true;
        for (auto [x, y, z] : as_set(vw.present)) {
            (void)x;
            (void)z;
            if (y < 0 || y > 1) ok = false;
        }
        for (auto [x, y, z] : as_set(vw.missing)) {
            (void)x;
            (void)z;
            if (y < 0 || y > 1) ok = false;
        }
        CHECK(ok);
        cv_world_destroy(w);
    }
}

// ---- models -------------------------------------------------------------------------------------------------

cv_model_quad model_face(int face, float height) {
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
    q.texture_layer = static_cast<uint16_t>(100 + face);
    q.cull_face = static_cast<uint8_t>(face == 0 && height < 1.0f ? 6 : face);
    q.flags = 0;
    return q;
}

struct Vert {
    float x, y, z, u, v;
    int8_t n[3];
    uint16_t layer;
    uint8_t flags[4];
};
Vert decode20(const uint8_t* p) {
    Vert v{};
    int16_t s[3];
    std::memcpy(s, p, 6);
    v.x = static_cast<float>(s[0]) / 32.0f;
    v.y = static_cast<float>(s[1]) / 32.0f + 512.0f;
    v.z = static_cast<float>(s[2]) / 32.0f;
    uint16_t h[2];
    std::memcpy(h, p + 6, 4);
    auto half = [](uint16_t b) {
        const int e = (b >> 10) & 31, m = b & 1023;
        const float sgn = (b & 0x8000) != 0 ? -1.0f : 1.0f;
        if (e == 0) return sgn * std::ldexp(static_cast<float>(m), -24);
        return sgn * std::ldexp(static_cast<float>(m + 1024), e - 25);
    };
    v.u = half(h[0]);
    v.v = half(h[1]);
    std::memcpy(v.n, p + 10, 3);
    std::memcpy(&v.layer, p + 14, 2);
    std::memcpy(v.flags, p + 16, 4);
    return v;
}

// The cell a decoded model quad belongs to: its centroid pulled slightly against the face normal.
std::tuple<int, int, int> quad_cell(const Vert* v, int face) {
    float c[3] = {0, 0, 0};
    for (int i = 0; i < 4; ++i) {
        c[0] += v[i].x / 4.0f;
        c[1] += v[i].y / 4.0f;
        c[2] += v[i].z / 4.0f;
    }
    c[0] -= 0.01f * static_cast<float>(kFaceDx[face]);
    c[1] -= 0.01f * static_cast<float>(kFaceDy[face]);
    c[2] -= 0.01f * static_cast<float>(kFaceDz[face]);
    return {static_cast<int>(std::floor(c[0])), static_cast<int>(std::floor(c[1])), static_cast<int>(std::floor(c[2]))};
}

void test_models() {
    cv_world* w = make_world(-1024, 1024);
    constexpr int32_t kSnowLayer = 2;
    cv_layer_define(w, kSnowLayer, CV_LAYER_SAVED | CV_LAYER_MESHED);
    // full-cube model for CUBEMODEL; snow layer models 1..8 eighths
    std::vector<cv_model_quad> cube;
    for (int f = 0; f < 6; ++f) cube.push_back(model_face(f, 1.0f));
    CHECK(cv_model_define(w, 1, cube.data(), 6) == CV_OK);
    CHECK(cv_block_model_bind(w, CUBEMODEL, -1, 0, 1) == CV_OK);
    for (int n = 1; n <= 8; ++n) {
        std::vector<cv_model_quad> snow;
        for (int f = 0; f < 6; ++f) {
            cv_model_quad q = model_face(f, static_cast<float>(n) / 8.0f);
            if (n == 8 && f == 0) q.cull_face = 0;
            snow.push_back(q);
        }
        CHECK(cv_model_define(w, 10 + n, snow.data(), 6) == CV_OK);
        CHECK(cv_block_model_bind(w, SNOW, kSnowLayer, static_cast<uint16_t>(n), 10 + n) == CV_OK);
    }
    CHECK(cv_model_define(w, -1, nullptr, 0) == CV_ERR_ARG);
    cv_model_quad bad = model_face(0, 1.0f);
    bad.cull_face = 9;
    CHECK(cv_model_define(w, 2, &bad, 1) == CV_ERR_ARG);
    {
        auto cfg = mesh_config(w);
        CHECK(cfg->models[1]->fills == 0x3F);
        CHECK(cfg->models[14]->fills == 0x02);   // 4/8 snow covers only its bottom
        CHECK(cfg->models[18]->fills == 0x3F);
    }
    std::vector<uint16_t> ids(CV_CUBE_CELLS, AIR);
    ids[static_cast<size_t>(idx(5, 5, 5))] = CUBEMODEL;
    ids[static_cast<size_t>(idx(6, 5, 5))] = STONE;   // culls CUBEMODEL's east face
    ids[static_cast<size_t>(idx(10, 5, 5))] = SNOW;   // 3 layers
    ids[static_cast<size_t>(idx(11, 5, 5))] = SNOW;   // 6 layers: different heights -> both sides drawn
    ids[static_cast<size_t>(idx(10, 5, 8))] = SNOW;   // 3 layers
    ids[static_cast<size_t>(idx(10, 5, 9))] = SNOW;   // 3 layers: equal -> shared sides culled
    ids[static_cast<size_t>(idx(10, 4, 5))] = STONE;  // under snow: its top is culled (snow bottom covers)
    ids[static_cast<size_t>(idx(9, 5, 5))] = STONE;   // west of the snow: its east face is drawn (side open)
    cv_cube_write_ids(w, 0, 0, 0, ids.data());
    std::vector<uint16_t> layer(CV_CUBE_CELLS, 0);
    layer[static_cast<size_t>(idx(10, 5, 5))] = 3;
    layer[static_cast<size_t>(idx(11, 5, 5))] = 6;
    layer[static_cast<size_t>(idx(10, 5, 8))] = 3;
    layer[static_cast<size_t>(idx(10, 5, 9))] = 3;
    cv_layer_write_cube(w, kSnowLayer, 0, 0, 0, layer.data());

    GpuMeshBuild b;
    CHECK(build_gpu_mesh(w, 0, 0, 0, CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY, b) == CV_OK);
    CHECK(b.stamp_vertex_count * 20 == static_cast<int32_t>(b.stamp_vertices.size()));
    CHECK(b.stamp_indices.size() * 4 == static_cast<size_t>(b.stamp_vertex_count) * 6);
    // collect quads per cell
    std::map<std::tuple<int, int, int>, std::set<int>> faces;
    for (int q = 0; q < b.stamp_vertex_count / 4; ++q) {
        Vert v[4];
        for (int c = 0; c < 4; ++c) v[c] = decode20(b.stamp_vertices.data() + (q * 4 + c) * 20);
        const int face = v[0].layer - 100;
        const auto cell = quad_cell(v, face);
        faces[cell].insert(face);
        CHECK(v[0].n[0] == kFaceDx[face] * 127 && v[0].n[1] == kFaceDy[face] * 127 && v[0].n[2] == kFaceDz[face] * 127);
    }
    CHECK(faces[{5, 5, 5}] == (std::set<int>{0, 1, 2, 3, 5}));
    CHECK(faces[{10, 5, 5}].count(4) == 1 && faces[{11, 5, 5}].count(5) == 1);   // unequal snow: sides drawn
    CHECK(faces[{10, 5, 8}].count(3) == 0 && faces[{10, 5, 9}].count(2) == 0);   // equal snow: culled
    CHECK(faces[{10, 5, 5}].count(5) == 0);   // snow side against stone: culled (stone covers, snow opaque)
    CHECK(faces[{10, 5, 5}].count(1) == 0);   // snow bottom on stone: culled
    // cube faces next to the snow: stone (9,5,5) east face drawn (snow side does not cover), stone (10,4,5) top
    // culled (snow bottom covers)
    MeshLists m = mesh(w, 0, 0, 0, CV_MESH_SMOOTH_LIGHTING);
    bool east9 = false, top10 = false, west6 = false;
    for (const cv_quad& q : m.q[0]) {
        if (q.x == 9 && q.y == 5 && q.z == 5 && q.face == 4) east9 = true;
        if (q.x == 10 && q.y == 4 && q.z == 5 && q.face == 0) top10 = true;
        if (q.x == 6 && q.y == 5 && q.z == 5 && q.face == 5) west6 = true;
    }
    CHECK(east9 && !top10 && !west6);   // stone vs full-cube model: culled both ways
    CHECK(m.host.empty());              // model cells are not host cells
    // AO on the model: the bottom face of CUBEMODEL sits over air; its top corners next to the stone at +x
    // get AO; flags carry the light byte; the full-cube top at (5,5,5): corners at x = 6 touch the stone
    // cell (6,6,5)? no: put a stone above-east and check one corner darker
    cv_set(w, 6, 6, 5, STONE);
    CHECK(build_gpu_mesh(w, 0, 0, 0, CV_MESH_SMOOTH_LIGHTING, b) == CV_OK);
    bool found = false;
    for (int q = 0; q < b.stamp_vertex_count / 4; ++q) {
        Vert v[4];
        for (int c = 0; c < 4; ++c) v[c] = decode20(b.stamp_vertices.data() + (q * 4 + c) * 20);
        if (v[0].layer != 100 || quad_cell(v, 0) != std::make_tuple(5, 5, 5)) continue;
        found = true;
        uint8_t lo = 255, hi = 0;
        for (auto& vv : v) {
            lo = std::min(lo, vv.flags[3]);
            hi = std::max(hi, vv.flags[3]);
        }
        CHECK(hi == 255 && lo < 255);
    }
    CHECK(found);
    // unbinding returns a cell to the host list
    CHECK(cv_block_model_bind(w, CUBEMODEL, -1, 0, -1) == CV_OK);
    m = mesh(w, 0, 0, 0, 0);
    CHECK(m.host.size() == 1 && m.host[0].id == CUBEMODEL);
    cv_world_destroy(w);
}

// ---- oversize models (corners beyond [-1, 2], up to [CV_MODEL_CORNER_MIN, CV_MODEL_CORNER_MAX]) ---------------

// The six faces of the box [lo, hi] (cell-local), each with cull_face = its face, texture layer 100 + face.
std::vector<cv_model_quad> box_model(const float lo[3], const float hi[3]) {
    std::vector<cv_model_quad> out;
    for (int f = 0; f < 6; ++f) {
        cv_model_quad q = model_face(f, 1.0f);
        for (int c = 0; c < 4; ++c) {
            for (int a = 0; a < 3; ++a) {
                q.pos[c][a] = lo[a] + static_cast<float>(kFaceOffsets[f][c][a]) * (hi[a] - lo[a]);
            }
        }
        q.cull_face = static_cast<uint8_t>(f);
        out.push_back(q);
    }
    return out;
}

struct StampSummary {
    std::set<int> faces;                       // texture layer - 100 of every emitted quad
    float lo[3] = {1e9f, 1e9f, 1e9f}, hi[3] = {-1e9f, -1e9f, -1e9f};   // decoded vertex bounds
};
StampSummary stamps(const GpuMeshBuild& b) {
    StampSummary s;
    for (int i = 0; i < b.stamp_vertex_count; ++i) {
        const Vert v = decode20(b.stamp_vertices.data() + i * 20);
        s.faces.insert(v.layer - 100);
        const float p[3] = {v.x, v.y, v.z};
        for (int a = 0; a < 3; ++a) {
            s.lo[a] = std::min(s.lo[a], p[a]);
            s.hi[a] = std::max(s.hi[a], p[a]);
        }
    }
    return s;
}

// CV_BLOCK_DETAIL (2026-11-22): a cube with a bound model draws the model only in CV_MESH_DETAIL meshes and its
// cube faces otherwise; build flags bit2 = an exposed detail cell (the look depends on the mode), bit3 = mode.
void test_detail_blocks() {
    constexpr uint16_t ORE = kIds;        // detail cube with a model (6 faces + a nugget on top)
    constexpr uint16_t BAREORE = kIds + 1; // DETAIL flag but no model: a plain cube in both modes
    uint16_t flags[kIds + 2];
    for (int i = 0; i < kIds; ++i) flags[i] = kFlags[i];
    flags[ORE] = kOpaque | CV_BLOCK_DETAIL;
    flags[BAREORE] = kOpaque | CV_BLOCK_DETAIL;
    cv_world* w = make_world(-1024, 1024);
    cv_block_table_set(w, flags, kIds + 2);
    std::vector<cv_model_quad> ore;
    for (int f = 0; f < 6; ++f) ore.push_back(model_face(f, 1.0f));
    cv_model_quad nugget = model_face(0, 1.0f);   // a raised square on the top face, culled with the top
    for (auto& c : nugget.pos) {
        c[0] = 0.4f + 0.2f * c[0];
        c[2] = 0.4f + 0.2f * c[2];
        c[1] = 1.05f;
    }
    nugget.texture_layer = 200;
    ore.push_back(nugget);
    CHECK(cv_model_define(w, 40, ore.data(), static_cast<int32_t>(ore.size())) == CV_OK);
    CHECK(cv_block_model_bind(w, ORE, -1, 0, 40) == CV_OK);

    auto stamps_of = [](const GpuMeshBuild& b) { return b.stamp_vertex_count / 4; };
    auto count_quads = [](const GpuMeshBuild& b) { return b.quad_count[0] + b.quad_count[1] + b.quad_count[2]; };

    // 1. One exposed ore in air: far = 6 cube faces, no stamps; near = 7 model quads, no cube faces.
    std::vector<uint16_t> ids(CV_CUBE_CELLS, AIR);
    ids[static_cast<size_t>(idx(5, 5, 5))] = ORE;
    cv_cube_write_ids(w, 0, 0, 0, ids.data());
    GpuMeshBuild far_b, near_b;
    CHECK(build_gpu_mesh(w, 0, 0, 0, CV_MESH_SMOOTH_LIGHTING, far_b) == CV_OK);
    CHECK(build_gpu_mesh(w, 0, 0, 0, CV_MESH_SMOOTH_LIGHTING | CV_MESH_DETAIL, near_b) == CV_OK);
    CHECK(count_quads(far_b) == 6 && stamps_of(far_b) == 0);
    CHECK(count_quads(near_b) == 0 && stamps_of(near_b) == 7);
    CHECK((far_b.flags & 4u) != 0 && (far_b.flags & 8u) == 0);
    CHECK((near_b.flags & 4u) != 0 && (near_b.flags & 8u) != 0);
    // The CPU mesher (no detail flag) agrees with the far GPU mesh.
    MeshLists m = mesh(w, 0, 0, 0, CV_MESH_SMOOTH_LIGHTING);
    CHECK(m.q[0].size() == 6 && m.host.empty());

    // 2. Buried ore (stone all around, ore beside ore): nothing exposed, nothing drawn of it, no bit2.
    std::fill(ids.begin(), ids.end(), STONE);
    ids[static_cast<size_t>(idx(10, 10, 10))] = ORE;
    ids[static_cast<size_t>(idx(11, 10, 10))] = ORE;
    cv_cube_write_ids(w, 0, 0, 0, ids.data());
    for (int dy = -1; dy <= 1; ++dy)
        for (int dz = -1; dz <= 1; ++dz)
            for (int dx = -1; dx <= 1; ++dx)
                if (dx != 0 || dy != 0 || dz != 0) {
                    std::vector<uint16_t> solid(CV_CUBE_CELLS, STONE);
                    cv_cube_write_ids(w, dx, dy, dz, solid.data());
                }
    CHECK(build_gpu_mesh(w, 0, 0, 0, CV_MESH_SMOOTH_LIGHTING, far_b) == CV_OK);
    CHECK(build_gpu_mesh(w, 0, 0, 0, CV_MESH_SMOOTH_LIGHTING | CV_MESH_DETAIL, near_b) == CV_OK);
    CHECK((far_b.flags & 4u) == 0 && (near_b.flags & 4u) == 0);
    CHECK(stamps_of(near_b) == 0 && count_quads(far_b) == 0 && count_quads(near_b) == 0);

    // 3. Ore with one air neighbour: exposed; far draws one cube face, near the model's top + nugget only.
    ids[static_cast<size_t>(idx(10, 11, 10))] = AIR;
    cv_cube_write_ids(w, 0, 0, 0, ids.data());
    CHECK(build_gpu_mesh(w, 0, 0, 0, CV_MESH_SMOOTH_LIGHTING, far_b) == CV_OK);
    CHECK(build_gpu_mesh(w, 0, 0, 0, CV_MESH_SMOOTH_LIGHTING | CV_MESH_DETAIL, near_b) == CV_OK);
    CHECK((far_b.flags & 4u) != 0 && (near_b.flags & 4u) != 0);
    CHECK(stamps_of(far_b) == 0 && stamps_of(near_b) == 2);   // top face + nugget (both cull with face 0)
    // far: the ore top + the stone faces around the air cell (5 stone faces, the ore one is the 6th)
    CHECK(count_quads(far_b) == 6);
    CHECK(count_quads(near_b) == 5);   // stone faces only; the ore is a model now

    // 4. DETAIL without a bound model: a plain cube in both modes, never "exposed detail".
    ids[static_cast<size_t>(idx(10, 10, 10))] = BAREORE;
    cv_cube_write_ids(w, 0, 0, 0, ids.data());
    CHECK(build_gpu_mesh(w, 0, 0, 0, CV_MESH_SMOOTH_LIGHTING | CV_MESH_DETAIL, near_b) == CV_OK);
    CHECK((near_b.flags & 4u) == 0 && stamps_of(near_b) == 0 && count_quads(near_b) == 6);
    cv_world_destroy(w);
}

void test_oversize_models() {
    cv_world* w = make_world(-1024, 1024);
    // range: [-4, 5] accepted, beyond refused (the old limit was [-1, 2])
    {
        const float lo[3] = {CV_MODEL_CORNER_MIN, CV_MODEL_CORNER_MIN, CV_MODEL_CORNER_MIN};
        const float hi[3] = {CV_MODEL_CORNER_MAX, CV_MODEL_CORNER_MAX, CV_MODEL_CORNER_MAX};
        std::vector<cv_model_quad> reach = box_model(lo, hi);
        CHECK(cv_model_define(w, 33, reach.data(), 6) == CV_OK);
        const float bads[3] = {CV_MODEL_CORNER_MAX + 0.01f, CV_MODEL_CORNER_MIN - 0.01f, std::nanf("")};
        for (int axis = 0; axis < 3; ++axis) {
            for (float bad : bads) {
                std::vector<cv_model_quad> q = reach;
                q[2].pos[1][axis] = bad;
                CHECK(cv_model_define(w, 34, q.data(), 6) == CV_ERR_ARG);
            }
        }
    }
    // 3-cell stalagmite (y 0..3), 3-cell hanging one (y -2..1), and an in-range 2-cell control (y 0..2)
    const float tall_lo[3] = {0.25f, 0.0f, 0.25f}, tall_hi[3] = {0.75f, 3.0f, 0.75f};
    const float hang_lo[3] = {0.0f, -2.0f, 0.0f}, hang_hi[3] = {1.0f, 1.0f, 1.0f};
    const float ctrl_lo[3] = {0.25f, 0.0f, 0.25f}, ctrl_hi[3] = {0.75f, 2.0f, 0.75f};
    std::vector<cv_model_quad> tall = box_model(tall_lo, tall_hi), hang = box_model(hang_lo, hang_hi),
                               ctrl = box_model(ctrl_lo, ctrl_hi);
    CHECK(cv_model_define(w, 30, tall.data(), 6) == CV_OK);
    CHECK(cv_model_define(w, 31, hang.data(), 6) == CV_OK);
    CHECK(cv_model_define(w, 32, ctrl.data(), 6) == CV_OK);
    {
        auto cfg = mesh_config(w);
        // only quads inside [-1, 2]^3 keep their cull face: the tall bottom, the hanging top
        CHECK(cfg->models[30]->cull == (std::vector<uint8_t>{6, 1, 6, 6, 6, 6}));
        CHECK(cfg->models[31]->cull == (std::vector<uint8_t>{0, 6, 6, 6, 6, 6}));
        CHECK(cfg->models[32]->cull == (std::vector<uint8_t>{0, 1, 2, 3, 4, 5}));
        CHECK(cfg->models[33]->cull == (std::vector<uint8_t>{6, 6, 6, 6, 6, 6}));
        // plane coverage still counts on-plane quads: the hanging top and its full-width sides
        CHECK(cfg->models[31]->fills == 0x3D);
        CHECK(cfg->models[30]->fills == 0);
        CHECK(cfg->models[30]->top == 1.0f);     // fluid attachment stays clamped to the cell
    }
    CHECK(cv_block_model_bind(w, CUBEMODEL, -1, 0, 30) == CV_OK);

    // interior: the tall model next to stone (east) and on stone (below) - all 3 cells meshed, sides never culled
    std::vector<uint16_t> ids(CV_CUBE_CELLS, AIR);
    ids[static_cast<size_t>(idx(5, 5, 5))] = CUBEMODEL;
    ids[static_cast<size_t>(idx(6, 5, 5))] = STONE;
    ids[static_cast<size_t>(idx(5, 4, 5))] = STONE;
    cv_cube_write_ids(w, 0, 0, 0, ids.data());
    GpuMeshBuild b;
    CHECK(build_gpu_mesh(w, 0, 0, 0, CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY, b) == CV_OK);
    StampSummary s = stamps(b);
    CHECK(s.faces == (std::set<int>{0, 2, 3, 4, 5}));   // bottom culled (in range, on stone), east kept
    CHECK(b.stamp_vertex_count == 20);
    CHECK(s.lo[1] == 5.0f && s.hi[1] == 8.0f && s.lo[0] == 5.25f && s.hi[0] == 5.75f);
    CHECK(b.aabb_max[1] >= 8.0f && b.aabb_min[1] <= 5.0f);   // bounds include the whole model
    // a neighbour CULL rule never touches the oversize quads (the bottom is culled anyway)
    const uint16_t stone_ids[1] = {STONE};
    CHECK(cv_face_rule_add(w, CUBEMODEL, -1, 0, 0x3F, CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, stone_ids, 1) == CV_OK);
    CHECK(build_gpu_mesh(w, 0, 0, 0, CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY, b) == CV_OK);
    CHECK(stamps(b).faces == (std::set<int>{0, 2, 3, 4, 5}));
    CHECK(cv_face_rules_clear(w, CUBEMODEL) == CV_OK);
    // control: the same box 2 cells tall is in range, so its east side is culled by the stone as before
    CHECK(cv_block_model_bind(w, CUBEMODEL, -1, 0, 32) == CV_OK);
    CHECK(build_gpu_mesh(w, 0, 0, 0, CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY, b) == CV_OK);
    s = stamps(b);
    CHECK(s.faces == (std::set<int>{0, 2, 3, 5}) && s.hi[1] == 7.0f);

    // region edges: the [-4, 5] box at the far corner of a region (cube & 7 == 7, cell 31) and at the near
    // corner (cube & 7 == 0, cell 0), with positive and negative cube coordinates
    CHECK(cv_block_model_bind(w, CUBEMODEL, -1, 0, 33) == CV_OK);
    const struct {
        int64_t cx, cy, cz;
        int cell;
        float lo, hi;
    } edges[] = {
        {7, 7, 7, 31, 251.0f, 260.0f},
        {-1, -1, -1, 31, 251.0f, 260.0f},
        {8, 8, 8, 0, -4.0f, 5.0f},
        {-8, -8, -8, 0, -4.0f, 5.0f},
    };
    for (const auto& e : edges) {
        std::vector<uint16_t> one(CV_CUBE_CELLS, AIR);
        one[static_cast<size_t>(idx(e.cell, e.cell, e.cell))] = CUBEMODEL;
        cv_cube_write_ids(w, e.cx, e.cy, e.cz, one.data());
        CHECK(build_gpu_mesh(w, e.cx, e.cy, e.cz, CV_MESH_SMOOTH_LIGHTING, b) == CV_OK);
        s = stamps(b);
        CHECK(b.stamp_vertex_count == 24 && s.faces.size() == 6);
        for (int a = 0; a < 3; ++a) {
            CHECK(s.lo[a] == e.lo && s.hi[a] == e.hi);
            CHECK(b.aabb_min[a] == e.lo && b.aabb_max[a] == e.hi);
        }
        bool lit = true;   // every vertex light is a real sample (nothing around: fully lit)
        for (int i = 0; i < b.stamp_vertex_count; ++i) {
            lit = lit && decode20(b.stamp_vertices.data() + i * 20).flags[3] == 255;
        }
        CHECK(lit);
    }
    // the hanging stalagmite at the bottom of a cube that starts a region: reaches 2 below the region origin
    CHECK(cv_block_model_bind(w, CUBEMODEL, -1, 0, 31) == CV_OK);
    {
        std::vector<uint16_t> one(CV_CUBE_CELLS, AIR);
        one[static_cast<size_t>(idx(3, 0, 3))] = CUBEMODEL;
        one[static_cast<size_t>(idx(3, 1, 3))] = STONE;   // ceiling: culls the in-range top quad
        cv_cube_write_ids(w, 16, 16, 16, one.data());
        CHECK(build_gpu_mesh(w, 16, 16, 16, CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY, b) == CV_OK);
        s = stamps(b);
        CHECK(s.faces == (std::set<int>{1, 2, 3, 4, 5}));
        CHECK(s.lo[1] == -2.0f && s.hi[1] == 1.0f && b.aabb_min[1] == -2.0f);
    }
    cv_world_destroy(w);
}

// ---- fluids -------------------------------------------------------------------------------------------------

struct WQ {
    uint32_t w[4];
    uint32_t x() const { return w[0] & 255u; }
    uint32_t y() const { return (w[0] >> 8) & 1023u; }
    uint32_t z() const { return (w[0] >> 18) & 255u; }
    uint32_t face() const { return (w[0] >> 26) & 7u; }
    bool falling() const { return ((w[0] >> 29) & 1u) != 0; }
    bool source() const { return ((w[0] >> 30) & 1u) != 0; }
    uint32_t ybyte(int c) const { return (w[1] >> (8 * c)) & 255u; }
    uint32_t flag(int c) const { return (w[2] >> (8 * c)) & 255u; }
    int flow() const { return static_cast<int>((w[3] >> 8) & 15u) - 1; }
    uint32_t depth() const { return (w[3] >> 12) & 255u; }
    bool inset() const { return ((w[3] >> 20) & 1u) != 0; }
};

void test_fluids() {
    SurfaceGen gen;
    gen.top = 1000;   // unknown space everywhere around
    cv_world* w = make_world(-1024, 1024, &gen);
    constexpr int32_t kWaterLayer = 1;
    cv_layer_define(w, kWaterLayer, CV_LAYER_SAVED | CV_LAYER_MESHED);
    cv_fluid_desc d{};
    d.layer = kWaterLayer;
    d.fluid_id = WATER;
    for (int i = 0; i < 8; ++i) {
        const float h = i == 0 ? 0.875f : static_cast<float>(8 - i) * 0.875f / 8.0f;
        d.surface_by_level[i] = std::max(0.0625f, std::min(0.875f, h));
    }
    CHECK(cv_fluid_set(w, &d) == CV_OK);
    // a pool: stone floor at ly 4 (x,z 2..12), source water at ly 5..6 in x,z 4..8, a flowing level-3 cell at
    // (9,6,6), ice at (4,6,9) (inset face), a river cell with flow octant 2 at (5,6,5)
    std::vector<uint16_t> ids(CV_CUBE_CELLS, AIR), lay(CV_CUBE_CELLS, 0);
    for (int z = 2; z <= 12; ++z)
        for (int x = 2; x <= 12; ++x) ids[static_cast<size_t>(idx(x, 4, z))] = STONE;
    for (int y = 5; y <= 6; ++y)
        for (int z = 4; z <= 8; ++z)
            for (int x = 4; x <= 8; ++x) {
                ids[static_cast<size_t>(idx(x, y, z))] = WATER;
                lay[static_cast<size_t>(idx(x, y, z))] = 16;
            }
    ids[static_cast<size_t>(idx(9, 6, 6))] = WATER;
    lay[static_cast<size_t>(idx(9, 6, 6))] = 3;
    ids[static_cast<size_t>(idx(4, 6, 9))] = ICE;
    lay[static_cast<size_t>(idx(5, 6, 5))] = static_cast<uint16_t>(16 | ((2 + 1) << 5));
    cv_cube_write_ids(w, 0, 0, 0, ids.data());
    cv_layer_write_cube(w, kWaterLayer, 0, 0, 0, lay.data());
    GpuMeshBuild b;
    CHECK(build_gpu_mesh(w, 0, 0, 0, CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY, b) == CV_OK);
    CHECK(static_cast<size_t>(b.water_count) * 16 == b.water.size());
    std::map<std::tuple<uint32_t, uint32_t, uint32_t, uint32_t>, WQ> recs;
    for (int i = 0; i < b.water_count; ++i) {
        WQ q{};
        std::memcpy(q.w, b.water.data() + i * 16, 16);
        recs[{q.x(), q.y(), q.z(), q.face()}] = q;
    }
    // no faces between water cells, none against the stone floor
    CHECK(recs.count({6, 5, 6, 0}) == 0 && recs.count({6, 6, 6, 1}) == 0 && recs.count({6, 5, 6, 1}) == 0);
    // an interior top face: all four corners at source height (0.875 -> (1.875 * 128) = 240), flag 223
    const auto it = recs.find({6, 6, 6, 0});
    CHECK(it != recs.end());
    if (it != recs.end()) {
        for (int c = 0; c < 4; ++c) CHECK(it->second.ybyte(c) == 240 && it->second.flag(c) == 223);
        CHECK(it->second.source() && !it->second.falling() && it->second.depth() == 2 && it->second.flow() == -1);
    }
    CHECK(recs.count({5, 6, 5, 0}) == 1 && recs[{5, 6, 5, 0}].flow() == 2);
    // the side face toward air at x = 3 (west of the pool, lower row): bottom = cell base (stone below is a
    // full block) -> byte 128; top = source height 240
    const auto side = recs.find({4, 5, 6, 5});
    CHECK(side != recs.end());
    if (side != recs.end()) {
        CHECK(side->second.ybyte(0) == 128 && side->second.ybyte(1) == 128);
        CHECK(side->second.ybyte(2) == 240 && side->second.ybyte(3) == 240);
        CHECK(!side->second.inset());
    }
    // the upper row's side bottom attaches to the water below it: 5.875 -> (0.875 * 128) = 112
    CHECK(recs.count({4, 6, 6, 5}) == 1 && recs[{4, 6, 6, 5}].ybyte(0) == 112);
    // the face toward the ice is drawn inset
    CHECK(recs.count({4, 6, 8, 3}) == 1 && recs[{4, 6, 8, 3}].inset());
    // flowing level-3 cell: not a source; its top corners away from the pool are lower
    CHECK(recs.count({9, 6, 6, 0}) == 1 && !recs[{9, 6, 6, 0}].source());
    {
        const WQ& q = recs[{9, 6, 6, 0}];
        // corners 1 (x+1, z+1) and 2 (x+1, z) touch only the flowing cell: (8-3)*0.875/8 = 0.546875
        const uint32_t want = static_cast<uint32_t>(std::lround((0.546875 + 1.0) * 128.0));
        CHECK(q.ybyte(1) == want && q.ybyte(2) == want && q.ybyte(0) == 240 && q.ybyte(3) == 240);
    }
    // side faces into UNKNOWN space are skipped: put water at the cube edge x = 31
    cv_set(w, 31, 10, 10, WATER);
    cv_layer_set(w, kWaterLayer, 31, 10, 10, 16);
    CHECK(build_gpu_mesh(w, 0, 0, 0, CV_MESH_SMOOTH_LIGHTING, b) == CV_OK);
    bool east_edge = false, top_edge = false;
    for (int i = 0; i < b.water_count; ++i) {
        WQ q{};
        std::memcpy(q.w, b.water.data() + i * 16, 16);
        if (q.x() == 31 && q.y() == 10 && q.z() == 10) {
            if (q.face() == 4) east_edge = true;
            if (q.face() == 0) top_edge = true;
        }
    }
    CHECK(!east_edge && top_edge);
    // fluid cells are not host cells
    CHECK(mesh(w, 0, 0, 0, 0).host.empty());
    // region-local: cube (9, -3, 2) -> offsets (1*32, 5*32, 2*32)
    cv_cube_write_ids(w, 9, -3, 2, ids.data());
    cv_layer_write_cube(w, kWaterLayer, 9, -3, 2, lay.data());
    CHECK(build_gpu_mesh(w, 9, -3, 2, CV_MESH_SMOOTH_LIGHTING, b) == CV_OK);
    bool region_ok = b.water_count > 0;
    for (int i = 0; i < b.water_count; ++i) {
        WQ q{};
        std::memcpy(q.w, b.water.data() + i * 16, 16);
        if (q.x() < 32 || q.x() >= 64 || q.y() < 160 || q.y() >= 192 || q.z() < 64 || q.z() >= 96) region_ok = false;
    }
    CHECK(region_ok);
    // removing the fluid: cells become host cells (HOST_GEOMETRY flag)
    d.layer = -1;
    CHECK(cv_fluid_set(w, &d) == CV_OK);
    CHECK(!mesh(w, 0, 0, 0, 0).host.empty());
    cv_world_destroy(w);
}

// ---- packing golden vectors --------------------------------------------------------------------------------

// The Java codecs' formulas, transcribed from engine-voxel (MmsQuadCodec before the widen, for w/h <= 16).
uint32_t java_word0(uint32_t x, uint32_t y, uint32_t z, uint32_t f) { return x | (y << 8) | (z << 18) | (f << 26); }
uint32_t java_word1_old(uint32_t w, uint32_t h, uint32_t o, bool a, bool t, uint32_t layer) {
    return (h - 1) | (o << 4) | ((a ? 1u : 0u) << 7) | ((t ? 1u : 0u) << 8) | (layer << 9) | ((w - 1) << 25);
}

void test_packing() {
    CHECK(quad16_word0(5, 200, 17, 3) == java_word0(5, 200, 17, 3));
    CHECK(quad16_word0(255, 255, 255, 5) == java_word0(255, 255, 255, 5));
    for (uint32_t w = 1; w <= 16; ++w)
        for (uint32_t h = 1; h <= 16; ++h)
            CHECK(quad16_word1(w, h, (w + h) & 7u, (w & 1) != 0, (h & 1) != 0, w * 1000 + h) ==
                  java_word1_old(w, h, (w + h) & 7u, (w & 1) != 0, (h & 1) != 0, w * 1000 + h));
    // widened: w = 32 -> bits 25..29 = 31; h = 32 -> low nibble 15 + bit 30
    CHECK(quad16_word1(32, 1, 0, false, false, 0) == (31u << 25));
    CHECK(quad16_word1(1, 32, 0, false, false, 0) == (15u | (1u << 30)));
    CHECK(quad16_word1(17, 17, 7, true, true, 65535) == (0u | (7u << 4) | (1u << 7) | (1u << 8) | (65535u << 9) | (16u << 25) | (1u << 30)));
    const uint8_t l[4] = {0, 64, 128, 255};
    CHECK(quad16_word2(l) == 0xFF804000u);
    // toUnsignedByte
    CHECK(to_u8(-1.0f) == 0 && to_u8(0.5f) == 128 && to_u8(0.87f) == 222 && to_u8(1.5f) == 255 && to_u8(0.002f) == 1);
    // WATERQUAD16
    CHECK(water_word0(1, 2, 3, 4, true, false, true) == (1u | (2u << 8) | (3u << 18) | (4u << 26) | (1u << 29) | (1u << 31)));
    const float vy[4] = {10.875f, 10.0f, 9.0f, 11.0f};
    CHECK(water_word1(10, vy) == (240u | (128u << 8) | (0u << 16) | (255u << 24)));
    const float vy2[4] = {10.0f + 0.546875f, 10.0f + 0.0625f, 10.0f + 0.10937f, 9.5f};
    // (1.546875 * 128) = 198; 1.0625 * 128 = 136; 1.10937 * 128 = 141.99 -> 142; 0.5 * 128 = 64
    CHECK(water_word1(10, vy2) == (198u | (136u << 8) | (142u << 16) | (64u << 24)));
    const float fl[4] = {0.875f, 0.0001f, 0.5f, 1.0f};
    CHECK(water_word2(fl) == (223u | (0u << 8) | (128u << 16) | (255u << 24)));
    CHECK(water_word3(1, 1, -1, 0, false) == 0u);
    CHECK(water_word3(16, 3, 7, 300, true) == (15u | (2u << 4) | (8u << 8) | (255u << 12) | (1u << 20)));
    // float -> half (Java Float.floatToFloat16)
    CHECK(float_to_half(0.0f) == 0x0000 && float_to_half(-0.0f) == 0x8000 && float_to_half(1.0f) == 0x3C00);
    CHECK(float_to_half(0.5f) == 0x3800 && float_to_half(-2.0f) == 0xC000 && float_to_half(65504.0f) == 0x7BFF);
    CHECK(float_to_half(65520.0f) == 0x7C00 && float_to_half(1e9f) == 0x7C00);
    CHECK(float_to_half(1.0f + std::ldexp(1.0f, -11)) == 0x3C00);       // tie -> even
    CHECK(float_to_half(1.0f + 3.0f * std::ldexp(1.0f, -11)) == 0x3C02);  // tie -> even (up)
    CHECK(float_to_half(std::ldexp(1.0f, -24)) == 0x0001 && float_to_half(std::ldexp(1.0f, -25)) == 0x0000);
    CHECK(float_to_half(3.0f * std::ldexp(1.0f, -25)) == 0x0002);
    CHECK(float_to_half(0.1f) == 0x2E66 && float_to_half(1.0f / 3.0f) == 0x3555);
    // COMPACT20 (MmsVertexFormat.encode): x 1.5 -> 48, y 3 -> (3 - 512) * 32, z 255.96875 -> 8191
    std::vector<uint8_t> v;
    const float n[3] = {0.0f, 1.0f, -0.5f};
    compact20_vertex(v, 1.5f, 3.0f, 255.96875f, 0.25f, 1.0f, n, 1.0f, 0.0f, 0.5f, 777);
    CHECK(v.size() == 20);
    int16_t s[3];
    std::memcpy(s, v.data(), 6);
    CHECK(s[0] == 48 && s[1] == -16288 && s[2] == 8191);
    uint16_t hu[2];
    std::memcpy(hu, v.data() + 6, 4);
    CHECK(hu[0] == 0x3400 && hu[1] == 0x3C00);
    CHECK(static_cast<int8_t>(v[10]) == 0 && static_cast<int8_t>(v[11]) == 127 && static_cast<int8_t>(v[12]) == -63 && v[13] == 0);
    uint16_t layer = 0;
    std::memcpy(&layer, v.data() + 14, 2);
    CHECK(layer == 777);
    uint32_t flags = 0;
    std::memcpy(&flags, v.data() + 16, 4);
    CHECK(flags == (0u | (255u << 8) | (0u << 16) | (128u << 24)));

    // build_gpu_mesh: QUAD16 records region-local, face textures, alpha/translucent bits
    cv_world* w = make_world(-1024, 1024);
    std::vector<uint16_t> ids(CV_CUBE_CELLS, AIR);
    ids[static_cast<size_t>(idx(3, 4, 5))] = LEAVES;
    ids[static_cast<size_t>(idx(9, 4, 5))] = ICE;
    ids[static_cast<size_t>(idx(20, 4, 5))] = STONE;
    cv_cube_write_ids(w, -7, 9, 3, ids.data());   // region offsets: (-7 & 7) = 1, 9 & 7 = 1, 3
    cv_face_tex tex[6];
    for (int f = 0; f < 6; ++f) tex[f] = cv_face_tex{static_cast<uint16_t>(500 + f), static_cast<uint8_t>(f), 0};
    CHECK(cv_block_faces_set(w, STONE, tex) == CV_OK);
    tex[0].orientation = 9;
    CHECK(cv_block_faces_set(w, STONE, tex) == CV_ERR_ARG);
    GpuMeshBuild b;
    CHECK(build_gpu_mesh(w, -7, 9, 3, CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY, b) == CV_OK);
    CHECK(b.quad_count[0] == 6 && b.quad_count[1] == 6 && b.quad_count[2] == 6);
    CHECK((b.flags & 2u) != 0 && (b.flags & 1u) == 0);
    bool ok = true;
    for (int list = 0; list < 3; ++list) {
        for (int i = 0; i < b.quad_count[list]; ++i) {
            uint32_t q[4];
            std::memcpy(q, b.quad16[list].data() + i * 16, 16);
            const uint32_t x = q[0] & 255u, y = (q[0] >> 8) & 1023u, z = (q[0] >> 18) & 255u, f = (q[0] >> 26) & 7u;
            const uint32_t layerq = (q[1] >> 9) & 0xFFFFu, orient = (q[1] >> 4) & 7u;
            const bool alpha = ((q[1] >> 7) & 1u) != 0, transl = ((q[1] >> 8) & 1u) != 0;
            if (y != 32 + 4 || z != 96 + 5 || q[3] != 0) ok = false;
            if (list == 0 && (x != 32 + 20 || layerq != 500 + f || orient != f || alpha || transl)) ok = false;
            if (list == 1 && (x != 32 + 3 || layerq != LEAVES || !alpha || transl)) ok = false;
            if (list == 2 && (x != 32 + 9 || layerq != ICE || alpha || !transl)) ok = false;
        }
    }
    CHECK(ok);
    CHECK(b.aabb_min[0] == 35.0f && b.aabb_max[0] == 53.0f && b.aabb_min[1] == 36.0f && b.aabb_max[1] == 37.0f);
    CHECK(b.revision == cv_cube_revision(w, -7, 9, 3));
    cv_cube_create(w, 0, 0, 0, AIR);
    CHECK(build_gpu_mesh(w, 0, 0, 0, 3, b) == CV_OK && b.flags == 1 && b.visibility == kAllPairs);
    CHECK(build_gpu_mesh(w, 1, 1, 1, 3, b) == CV_ERR_MISSING);
    cv_world_destroy(w);
}

// ---- neighbour face rules (cv_face_rule_add) ------------------------------------------------------------------

constexpr uint16_t GEL = 11;     // translucent host geometry bound to a model (model rules)
constexpr uint16_t NOID = 12;    // never placed
constexpr int32_t kRuleWater = 1, kRuleState = 3;   // layer 2 (snow) is defined by rule_world but unused here

cv_world* rule_world() {
    cv_world* w = make_world(-1024, 1024);
    uint16_t flags[13];
    std::copy(kFlags, kFlags + kIds, flags);
    flags[GEL] = CV_BLOCK_HOST_GEOMETRY | CV_BLOCK_TRANSPARENT | CV_BLOCK_TRANSLUCENT;
    flags[NOID] = kOpaque;
    cv_block_table_set(w, flags, 13);
    cv_layer_define(w, kRuleWater, CV_LAYER_SAVED | CV_LAYER_MESHED);
    cv_layer_define(w, kRuleState, CV_LAYER_SAVED | CV_LAYER_MESHED);
    cv_fluid_desc d{};
    d.layer = kRuleWater;
    d.fluid_id = WATER;
    for (float& h : d.surface_by_level) h = 0.875f;
    cv_fluid_set(w, &d);
    return w;
}

// Reference for the random-cube rules below: ICE faces toward GLASS or FLOWER culled (both drawn natively), GLASS
// top faces toward air opaque.
void ref_random_rules(cv_world* w, int64_t cx, int64_t cy, int64_t cz, std::map<FaceKey, RefFace>& ref) {
    for (auto it = ref.begin(); it != ref.end();) {
        const auto [lx, ly, lz, f] = it->first;
        const uint16_t n = ref_get(w, cx * 32 + lx + kFaceDx[f], cy * 32 + ly + kFaceDy[f], cz * 32 + lz + kFaceDz[f]);
        if (it->second.id == ICE && (n == GLASS || n == FLOWER)) {
            it = ref.erase(it);
            continue;
        }
        if (it->second.id == GLASS && f == 0 && n == AIR) it->second.list = 0;
        ++it;
    }
}

struct Q16 {
    uint32_t w[4];
    uint32_t x() const { return w[0] & 255u; }
    uint32_t y() const { return (w[0] >> 8) & 1023u; }
    uint32_t z() const { return (w[0] >> 18) & 255u; }
    uint32_t face() const { return (w[0] >> 26) & 7u; }
    bool alpha() const { return ((w[1] >> 7) & 1u) != 0; }
    bool transl() const { return ((w[1] >> 8) & 1u) != 0; }
    uint32_t layer() const { return (w[1] >> 9) & 0xFFFFu; }
    uint32_t width() const { return ((w[1] >> 25) & 31u) + 1; }
};

void test_face_rules() {
    cv_world* w = rule_world();
    const uint16_t water[1] = {WATER};
    // argument checks
    CHECK(cv_face_rule_add(w, ICE, -1, 0, 0x3F, 3, water, 1) == CV_ERR_ARG);
    CHECK(cv_face_rule_add(w, ICE, -1, 0, 0x3F, 0, water, 1) == CV_ERR_ARG);
    CHECK(cv_face_rule_add(w, ICE, -1, 0, 0, CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, water, 1) == CV_ERR_ARG);
    CHECK(cv_face_rule_add(w, ICE, -1, 0, 0x40, CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, water, 1) == CV_ERR_ARG);
    CHECK(cv_face_rule_add(w, ICE, -1, 0, 0x3F, CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, water, 0) == CV_ERR_ARG);
    CHECK(cv_face_rule_add(w, ICE, -1, 0, 0x3F, CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, nullptr, 1) == CV_ERR_ARG);
    CHECK(cv_face_rule_add(w, ICE, CV_MAX_LAYERS, 0, 0x3F, CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, water, 1) == CV_ERR_ARG);
    CHECK(cv_face_rule_add(nullptr, ICE, -1, 0, 0x3F, CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, water, 1) == CV_ERR_ARG);
    CHECK(cv_face_rules_clear(w, 65536) == CV_ERR_ARG && cv_face_rules_clear(nullptr, 1) == CV_ERR_ARG);
    CHECK(mesh_config(w)->face_rules.empty());

    // Layout (cube 0,0,0):
    //  ICE (5,5,5): WATER east (6,5,5), FLOWER above (5,6,5)   -> east face opaque, top culled
    //  ICE (10,5,5): alone                                      -> six translucent faces (control)
    //  GLASS (15,5,5): WATER east (16,5,5)                      -> no rule: six cutout faces
    //  ICE row x 0..3 at (y 20, z 10), WATER south of x 0..1   -> forced and plain faces never merge
    //  GLASS (20,5,5) state 7 and GLASS (22,5,5) state 0         -> state rule: only (20,5,5)'s top culled
    std::vector<uint16_t> ids(CV_CUBE_CELLS, AIR), wl(CV_CUBE_CELLS, 0), st(CV_CUBE_CELLS, 0);
    auto put = [&](int x, int y, int z, uint16_t id) { ids[static_cast<size_t>(idx(x, y, z))] = id; };
    put(5, 5, 5, ICE);
    put(6, 5, 5, WATER);
    put(5, 6, 5, FLOWER);
    put(10, 5, 5, ICE);
    put(15, 5, 5, GLASS);
    put(16, 5, 5, WATER);
    for (int x = 0; x < 4; ++x) put(x, 20, 10, ICE);
    put(0, 20, 11, WATER);
    put(1, 20, 11, WATER);
    put(20, 5, 5, GLASS);
    put(22, 5, 5, GLASS);
    st[static_cast<size_t>(idx(20, 5, 5))] = 7;
    for (size_t i = 0; i < ids.size(); ++i) wl[i] = ids[i] == WATER ? 16 : 0;
    cv_cube_write_ids(w, 0, 0, 0, ids.data());
    cv_layer_write_cube(w, kRuleWater, 0, 0, 0, wl.data());
    cv_layer_write_cube(w, kRuleState, 0, 0, 0, st.data());

    const uint32_t mf = CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY;
    GpuMeshBuild before;
    CHECK(build_gpu_mesh(w, 0, 0, 0, mf, before) == CV_OK);
    // Rules on a block that never occurs, and rules whose neighbour never occurs: byte-identical meshes.
    const uint16_t noid[1] = {NOID};
    CHECK(cv_face_rule_add(w, NOID, -1, 0, 0x3F, CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, water, 1) == CV_OK);
    CHECK(cv_face_rule_add(w, ICE, -1, 0, 0x3F, CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, noid, 1) == CV_OK);
    CHECK(cv_face_rule_add(w, WATER, -1, 0, 0x3F, CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, noid, 1) == CV_OK);
    {
        GpuMeshBuild b;
        CHECK(build_gpu_mesh(w, 0, 0, 0, mf, b) == CV_OK);
        CHECK(b.quad16[0] == before.quad16[0] && b.quad16[1] == before.quad16[1] && b.quad16[2] == before.quad16[2]);
        CHECK(b.water == before.water && b.stamp_vertices == before.stamp_vertices);
    }

    // Stonebreak's rule shapes.
    const uint16_t flower[1] = {FLOWER}, ice[1] = {ICE}, air[1] = {AIR};
    CHECK(cv_face_rule_add(w, ICE, -1, 0, 0x3F, CV_FACE_RULE_OPAQUE_WHEN_NEIGHBOUR_IN, water, 1) == CV_OK);
    CHECK(cv_face_rule_add(w, ICE, -1, 0, 1u << 0, CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, flower, 1) == CV_OK);
    CHECK(cv_face_rule_add(w, WATER, -1, 0, 0x3F, CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, ice, 1) == CV_OK);
    CHECK(cv_face_rule_add(w, GLASS, kRuleState, 7, 1u << 0, CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, air, 1) == CV_OK);
    {
        auto cfg = mesh_config(w);
        CHECK(cfg->is_ruled(ICE) && cfg->is_ruled(WATER) && cfg->is_ruled(GLASS) && !cfg->is_ruled(STONE));
        CHECK(std::find(cfg->layers.begin(), cfg->layers.end(), kRuleState) != cfg->layers.end());
    }
    MeshLists m = mesh(w, 0, 0, 0, mf);
    CHECK(m.rc == CV_OK);
    auto faces_of = [&](int list, int x, int y, int z) {
        std::set<int> out;
        for (const cv_quad& q : m.q[list]) {
            for (int i = 0; i < q.w; ++i) {
                for (int j = 0; j < q.h; ++j) {
                    int p[3] = {q.x, q.y, q.z};
                    p[kUAxis[q.face & 7]] += i;
                    p[kVAxis[q.face & 7]] += j;
                    if (p[0] == x && p[1] == y && p[2] == z) out.insert(q.face);
                }
            }
        }
        return out;
    };
    CHECK(faces_of(0, 5, 5, 5) == (std::set<int>{4}));               // toward water: opaque list
    CHECK(faces_of(2, 5, 5, 5) == (std::set<int>{1, 2, 3, 5}));      // top culled under the flower
    CHECK(faces_of(2, 10, 5, 5) == (std::set<int>{0, 1, 2, 3, 4, 5}) && faces_of(0, 10, 5, 5).empty());
    CHECK(faces_of(1, 15, 5, 5) == (std::set<int>{0, 1, 2, 3, 4, 5}) && faces_of(0, 15, 5, 5).empty());
    CHECK(faces_of(1, 20, 5, 5) == (std::set<int>{1, 2, 3, 4, 5}));  // state 7: top culled (face mask: top only)
    CHECK(faces_of(1, 22, 5, 5) == (std::set<int>{0, 1, 2, 3, 4, 5}));   // state 0: untouched
    bool marks = true, row_forced = false, row_plain = false;
    for (int list = 0; list < 3; ++list) {
        for (const cv_quad& q : m.q[list]) {
            if (q.face > 5) marks = false;
            if (q.face == 3 && q.y == 20 && q.z == 10 && q.id == ICE) {
                if (list == 0 && q.x == 0 && q.w == 2 && q.h == 1) row_forced = true;
                if (list == 2 && q.x == 2 && q.w == 2 && q.h == 1) row_plain = true;
            }
        }
    }
    CHECK(marks && row_forced && row_plain);
    CHECK(faces_of(0, 2, 20, 10).empty() && faces_of(2, 0, 20, 10).count(3) == 0);

    // GPU packing: the forced face is opaque (alpha + translucent off, even over a CV_FACE_ALPHA_TEST texture).
    cv_face_tex tex[6];
    for (int f = 0; f < 6; ++f) tex[f] = cv_face_tex{static_cast<uint16_t>(300 + f), 0, CV_FACE_ALPHA_TEST};
    CHECK(cv_block_faces_set(w, ICE, tex) == CV_OK);
    GpuMeshBuild b;
    CHECK(build_gpu_mesh(w, 0, 0, 0, mf, b) == CV_OK);
    bool forced_ok = false, others_ok = true;
    for (int list = 0; list < 3; ++list) {
        for (int i = 0; i < b.quad_count[list]; ++i) {
            Q16 q{};
            std::memcpy(q.w, b.quad16[list].data() + i * 16, 16);
            if (q.face() > 5) others_ok = false;
            if (list == 0) {
                if (q.layer() < 300 || q.layer() > 305 || q.alpha() || q.transl()) others_ok = false;
                if (q.x() == 5 && q.y() == 5 && q.z() == 5 && q.face() == 4 && q.layer() == 304) forced_ok = true;
            } else if (list == 2) {
                if (!q.transl() || !q.alpha()) others_ok = false;   // ICE: translucent + the texture's alpha test
            }
        }
    }
    CHECK(forced_ok && others_ok);
    CHECK(b.quad_count[0] == 2);   // (5,5,5) east + the merged row (x 0..1)
    // Fluids: the water face toward ice is culled; toward glass it stays (inset).
    auto water_faces = [](const GpuMeshBuild& g) {
        std::set<std::tuple<uint32_t, uint32_t, uint32_t, uint32_t>> out;
        for (int i = 0; i < g.water_count; ++i) {
            WQ q{};
            std::memcpy(q.w, g.water.data() + i * 16, 16);
            out.insert({q.x(), q.y(), q.z(), q.face()});
        }
        return out;
    };
    auto wf = water_faces(b);
    CHECK(wf.count({6, 5, 5, 5}) == 0 && wf.count({6, 5, 5, 0}) == 1);
    CHECK(wf.count({16, 5, 5, 5}) == 1);
    CHECK(wf.count({0, 20, 11, 2}) == 0 && wf.count({1, 20, 11, 2}) == 0);
    CHECK(wf.count({0, 20, 11, 3}) == 1);   // toward air: untouched

    // Clearing one block's rules restores it; clearing all restores everything.
    CHECK(cv_face_rules_clear(w, ICE) == CV_OK);
    m = mesh(w, 0, 0, 0, mf);
    CHECK(faces_of(2, 5, 5, 5) == (std::set<int>{0, 1, 2, 3, 4, 5}) && faces_of(0, 5, 5, 5).empty());
    CHECK(water_faces([&] { GpuMeshBuild g; build_gpu_mesh(w, 0, 0, 0, mf, g); return g; }()).count({6, 5, 5, 5}) == 0);
    CHECK(cv_face_rules_clear(w, -1) == CV_OK);
    CHECK(mesh_config(w)->face_rules.empty() && !mesh_config(w)->is_ruled(GLASS));
    cv_block_faces_set(w, ICE, tex);
    {
        GpuMeshBuild g;
        CHECK(build_gpu_mesh(w, 0, 0, 0, mf, g) == CV_OK);
        CHECK(water_faces(g).count({6, 5, 5, 5}) == 1);
        m = mesh(w, 0, 0, 0, mf);
        CHECK(faces_of(1, 20, 5, 5).count(0) == 1);
    }

    // Model quads: GEL bound to a translucent cutout cube model plus a never-culled cross quad facing +x.
    std::vector<cv_model_quad> gel;
    for (int f = 0; f < 6; ++f) {
        cv_model_quad q = model_face(f, 1.0f);
        q.flags = 3;
        gel.push_back(q);
    }
    cv_model_quad cross = model_face(4, 1.0f);
    for (int c = 0; c < 4; ++c) cross.pos[c][0] = 0.5f;
    cross.cull_face = 6;
    cross.flags = 3;
    cross.texture_layer = 200;
    gel.push_back(cross);
    CHECK(cv_model_define(w, 5, gel.data(), static_cast<int32_t>(gel.size())) == CV_OK);
    CHECK(cv_block_model_bind(w, GEL, -1, 0, 5) == CV_OK);
    const uint16_t glass[1] = {GLASS};
    CHECK(cv_face_rule_add(w, GEL, -1, 0, 0x3F, CV_FACE_RULE_OPAQUE_WHEN_NEIGHBOUR_IN, water, 1) == CV_OK);
    CHECK(cv_face_rule_add(w, GEL, -1, 0, 1u << 0, CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, glass, 1) == CV_OK);
    // CULL wins over OPAQUE on the same face.
    CHECK(cv_face_rule_add(w, GEL, -1, 0, 1u << 2, CV_FACE_RULE_OPAQUE_WHEN_NEIGHBOUR_IN, glass, 1) == CV_OK);
    CHECK(cv_face_rule_add(w, GEL, -1, 0, 1u << 2, CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, glass, 1) == CV_OK);
    cv_set(w, 25, 8, 5, GEL);
    cv_set(w, 26, 8, 5, WATER);
    cv_layer_set(w, kRuleWater, 26, 8, 5, 16);
    cv_set(w, 25, 9, 5, GLASS);
    cv_set(w, 25, 8, 4, GLASS);
    CHECK(build_gpu_mesh(w, 0, 0, 0, mf, b) == CV_OK);
    std::map<int, std::pair<uint8_t, uint8_t>> gel_faces;   // layer -> (alpha, translucent)
    for (int q = 0; q < b.stamp_vertex_count / 4; ++q) {
        Vert v[4];
        for (int c = 0; c < 4; ++c) v[c] = decode20(b.stamp_vertices.data() + (q * 4 + c) * 20);
        const int face = v[0].layer == 200 ? 4 : v[0].layer - 100;
        if (quad_cell(v, face) != std::make_tuple(25, 8, 5)) continue;
        gel_faces[v[0].layer] = {v[0].flags[1], v[0].flags[2]};
    }
    CHECK(gel_faces.count(100) == 0);   // top under glass: culled
    CHECK(gel_faces.count(102) == 0);   // north toward glass: CULL beats OPAQUE
    CHECK(gel_faces.count(104) == 1 && gel_faces[104] == std::make_pair(uint8_t{0}, uint8_t{0}));     // toward water
    CHECK(gel_faces.count(101) == 1 && gel_faces[101] == std::make_pair(uint8_t{255}, uint8_t{255}));
    CHECK(gel_faces.count(200) == 1 && gel_faces[200] == std::make_pair(uint8_t{255}, uint8_t{255})); // cull_face 6
    cv_world_destroy(w);

    // Random cubes against a brute-force reference with rules applied (greedy and not, smooth and flat).
    cv_world* r = make_world(-1024, 1024);
    const uint16_t see[3] = {FLOWER, GLASS, FLOWER};   // unsorted with a duplicate on purpose
    CHECK(cv_face_rule_add(r, ICE, -1, 0, 0x3F, CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, see, 3) == CV_OK);
    CHECK(cv_face_rule_add(r, GLASS, -1, 0, 1u << 0, CV_FACE_RULE_OPAQUE_WHEN_NEIGHBOUR_IN, air, 1) == CV_OK);
    for (int kind = 0; kind < 3; ++kind) {
        for (int c = 0; c < 3; ++c) fill_random(r, c - 1, 0, 0, kind);
        for (uint32_t flags : {0u, 1u, 2u, 3u}) compare_with_reference(r, 0, 0, 0, flags, ref_random_rules);
    }
    cv_world_destroy(r);
}

// ---- concurrency + timings -----------------------------------------------------------------------------------

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
                            ids[static_cast<size_t>(idx(lx, ly, lz))] = id;
                        }
                cv_cube_write_ids(w, cx, cy, cz, ids.data());
            }
        }
    }
}

void test_concurrency_and_timing() {
    cv_world* w = make_world(-1024, 1024);
    terrain_world(w, 3, -2, 2);
    // timing: terrain cube (cy 0) GPU meshes
    {
        GpuMeshBuild b;
        const int n = 200;
        int64_t quads = 0;
        const auto t0 = std::chrono::steady_clock::now();
        for (int i = 0; i < n; ++i) {
            build_gpu_mesh(w, i % 5 - 2, 0, (i / 5) % 5 - 2, CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY, b);
            quads += b.quad_count[0] + b.quad_count[1] + b.quad_count[2];
        }
        const double us = std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - t0).count() / n;
        std::printf("  terrain cube build_gpu_mesh: %.1f us/cube (%.2f ns/block), %.0f quads/cube\n", us, us * 1000.0 / 32768.0,
                    static_cast<double>(quads) / n);
        const auto t1 = std::chrono::steady_clock::now();
        for (int i = 0; i < n; ++i) build_gpu_mesh(w, i % 5 - 2, -2, (i / 5) % 5 - 2, CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY, b);
        const double us2 = std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - t1).count() / n;
        std::printf("  buried stone cube: %.1f us/cube\n", us2);
        const auto t2 = std::chrono::steady_clock::now();
        for (int i = 0; i < n; ++i) build_gpu_mesh(w, i % 5 - 2, 2, (i / 5) % 5 - 2, CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY, b);
        const double us3 = std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - t2).count() / n;
        std::printf("  sky air cube: %.1f us/cube\n", us3);
        {   // phase breakdown of the terrain cube
            MeshWork& mw = mesh_work();
            static PaddedCube pc;
            auto t = std::chrono::steady_clock::now();
            for (int i = 0; i < n; ++i) read_padded(w, 0, 0, 0, pc, nullptr, nullptr, 0);
            const double rp = std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - t).count() / n;
            t = std::chrono::steady_clock::now();
            for (int i = 0; i < n; ++i) mesh_prepare(w, 0, 0, 0, CV_MESH_SMOOTH_LIGHTING, mw);
            const double prep = std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - t).count() / n;
            std::vector<cv_quad> qs[3];
            t = std::chrono::steady_clock::now();
            for (int i = 0; i < n; ++i) {
                for (auto& q : qs) q.clear();
                mw.ring_ready = false;
                mesh_cube_faces(mw, true, qs);
            }
            const double faces = std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - t).count() / n;
            t = std::chrono::steady_clock::now();
            for (int i = 0; i < n; ++i) {
                mw.ring_ready = false;
                mw.sky_ring();
            }
            const double ring = std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - t).count() / n;
            std::printf("  phases: read_padded %.1f us, prepare (read + classify) %.1f us, faces+light+merge %.1f us "
                        "(of which sky ring %.1f us)\n", rp, prep, faces, ring);
        }
        GpuMeshBuild g, ng;
        build_gpu_mesh(w, 0, 0, 0, CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY, g);
        build_gpu_mesh(w, 0, 0, 0, CV_MESH_SMOOTH_LIGHTING, ng);
        std::printf("  greedy: %d quads vs %d unit faces\n", g.quad_count[0] + g.quad_count[1], ng.quad_count[0] + ng.quad_count[1]);
        CHECK(g.quad_count[0] < ng.quad_count[0]);
    }
    // concurrency: meshers + walkers + an editor
    std::atomic<bool> stop{false};
    std::atomic<int> errors{0};
    std::vector<std::thread> threads;
    for (int t = 0; t < 4; ++t) {
        threads.emplace_back([&, t] {
            GpuMeshBuild b;
            int i = t;
            while (!stop.load()) {
                const int32_t rc = build_gpu_mesh(w, i % 7 - 3, (i / 7) % 5 - 2, (i / 35) % 7 - 3, 3, b);
                if (rc != CV_OK) errors.fetch_add(1);
                i += 3;
            }
        });
    }
    for (int t = 0; t < 2; ++t) {
        threads.emplace_back([&] {
            VisibleWalk vw;
            while (!stop.load()) {
                if (walk_visible(w, 0, 0, 0, 3, nullptr, vw) != CV_OK) errors.fetch_add(1);
                if (cv_cube_visibility(w, 1, 0, 1) < 0) errors.fetch_add(1);
            }
        });
    }
    threads.emplace_back([&] {
        std::mt19937 r(7);
        int k = 0;
        while (!stop.load()) {
            cv_set(w, static_cast<int64_t>(r() % 200) - 100, static_cast<int64_t>(r() % 64), static_cast<int64_t>(r() % 200) - 100,
                   (++k & 1) != 0 ? STONE : AIR);
        }
    });
    std::this_thread::sleep_for(std::chrono::milliseconds(600));
    stop.store(true);
    for (auto& t : threads) t.join();
    CHECK(errors.load() == 0);
    cv_world_destroy(w);

    // walk timing: ~20k reachable cubes (41 x 41 x 12, open air everywhere except a stone floor layer)
    cv_world* big = make_world(-192, 192);
    for (int cz = -20; cz <= 20; ++cz)
        for (int cx = -20; cx <= 20; ++cx)
            for (int cy = -6; cy < 6; ++cy) cv_cube_create(big, cx, cy, cz, cy == -6 ? STONE : AIR);
    VisibleWalk vw;
    walk_visible(big, 0, 0, 0, 20, nullptr, vw);   // warms the visibility cache
    const int reps = 20;
    const auto t0 = std::chrono::steady_clock::now();
    for (int i = 0; i < reps; ++i) walk_visible(big, 0, 0, 0, 20, nullptr, vw);
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() / reps;
    std::printf("  walk: %zu present, %zu missing cubes in %.3f ms\n", vw.present.size() / 3, vw.missing.size() / 3, ms);
    CHECK(vw.present.size() / 3 == 41u * 41u * 12u);
    const float planes[24] = {1, 0, 0.3f, 0, -1, 0, 0.3f, 0, 0, 1, 0.3f, 0, 0, -1, 0.3f, 0, 0, 0, 1, 0, 0, 0, -1, 4000};
    const auto t1 = std::chrono::steady_clock::now();
    for (int i = 0; i < reps; ++i) walk_visible(big, 0, 0, 0, 20, planes, vw);
    const double ms2 = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t1).count() / reps;
    std::printf("  walk with frustum: %zu present in %.3f ms\n", vw.present.size() / 3, ms2);
    cv_world_destroy(big);
}

} // namespace

int main() {
    const struct {
        const char* name;
        void (*fn)();
    } tests[] = {
        {"reference mesher comparison", test_reference},
        {"rules + merge extents", test_rules_and_merge},
        {"visibility", test_visibility},
        {"walk", test_walk},
        {"models", test_models},
        {"detail blocks", test_detail_blocks},
        {"oversize models", test_oversize_models},
        {"fluids", test_fluids},
        {"packing", test_packing},
        {"face rules", test_face_rules},
        {"concurrency + timing", test_concurrency_and_timing},
    };
    for (const auto& t : tests) {
        const int before = g_failures;
        std::printf("[voxel_mesh] %s\n", t.name);
        t.fn();
        if (g_failures != before) std::printf("  -> %d failure(s)\n", g_failures - before);
    }
    std::printf("[voxel_mesh] %d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
