// Cube visibility graph (OpenMason #123/#143, Phase 1b): 15 face-pair bits per cube (Checchi) from its
// CV_BLOCK_VIS_OPAQUE cells, cached per cube revision + block-table generation (Cube::vis_cache), and the
// camera walk behind cv_visible_cubes / the streamer's visibility load rule.
//
// Pair bits: a connected component of open (non-VIS_OPAQUE) cells that touches faces a and b sets
// pair_bit(a, b). Components are found by union-find over the open x-runs of the 1024 (y, z) rows, so the
// cost follows the number of runs, not cells (terrain cube: a few thousand runs, ~10 us).
//
// Walk: breadth-first from the camera cube. A cube entered through face `from` may leave through face d
// only when pair(from, d) is set (the camera cube and open missing cubes connect everything), never in a
// direction opposite to one already taken on the way (no stepping back toward the camera), within the
// Chebyshev radius, inside the vertical bounds and, when planes are given, inside the frustum. Each cube is
// visited once (first arrival). Missing cubes: KNOWN AIR ones are open; UNKNOWN ones are terminal and
// reported as missing. When the camera's own cube is missing and unknown it is reported missing and still
// walked through as open (the camera stands in it).
#include "mesh_internal.hpp"
#include "cv_error.hpp"

#include <algorithm>
#include <bit>
#include <cstring>
#include <vector>

namespace cenda::voxel {

namespace {

struct UnionFind {
    uint16_t parent[16384];
    uint16_t find(uint16_t i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }
    void unite(uint16_t a, uint16_t b) {
        a = find(a);
        b = find(b);
        if (a != b) parent[a < b ? b : a] = a < b ? a : b;
    }
};

struct VisScratch {
    UnionFind uf;
    uint8_t x0[16384];
    uint8_t x1[16384];
    uint8_t faces[16384];
    uint8_t comp[16384];
    uint16_t row_start[1025];
};

uint16_t pairs_of(uint8_t f) {
    uint16_t bits = 0;
    for (int a = 0; a < 6; ++a) {
        if (((static_cast<unsigned>(f) >> a) & 1u) == 0) continue;
        for (int b = a + 1; b < 6; ++b) {
            if (((static_cast<unsigned>(f) >> b) & 1u) != 0) bits = static_cast<uint16_t>(bits | (1u << pair_bit(a, b)));
        }
    }
    return bits;
}

struct PairTable {
    uint16_t v[64];
    PairTable() {
        for (int f = 0; f < 64; ++f) v[f] = pairs_of(static_cast<uint8_t>(f));
    }
};
const PairTable kPairs;

void link_rows(VisScratch& s, int ra, int rb) {
    int i = s.row_start[ra], ie = s.row_start[ra + 1];
    int j = s.row_start[rb], je = s.row_start[rb + 1];
    while (i < ie && j < je) {
        if (s.x0[i] <= s.x1[j] && s.x0[j] <= s.x1[i]) s.uf.unite(static_cast<uint16_t>(i), static_cast<uint16_t>(j));
        if (s.x1[i] < s.x1[j]) ++i;
        else ++j;
    }
}

} // namespace

uint16_t visibility_from_rows(const uint32_t* closed) {
    thread_local std::unique_ptr<VisScratch> scratch;
    if (!scratch) scratch = std::make_unique<VisScratch>();
    VisScratch& s = *scratch;
    int n = 0;
    for (int r = 0; r < 1024; ++r) {
        s.row_start[r] = static_cast<uint16_t>(n);
        const int y = r >> 5, z = r & 31;
        uint8_t row_faces = 0;
        if (y == 0) row_faces |= 1u << 1;
        if (y == 31) row_faces |= 1u << 0;
        if (z == 0) row_faces |= 1u << 2;
        if (z == 31) row_faces |= 1u << 3;
        uint64_t open = static_cast<uint64_t>(~closed[r]);
        while (open != 0) {
            const int a = std::countr_zero(open);
            const int len = std::countr_zero(~(open >> a));
            const int b = a + len - 1;
            open &= ~(((uint64_t{1} << len) - 1) << a);
            s.x0[n] = static_cast<uint8_t>(a);
            s.x1[n] = static_cast<uint8_t>(b);
            uint8_t f = row_faces;
            if (a == 0) f |= 1u << 5;
            if (b == 31) f |= 1u << 4;
            s.faces[n] = f;
            s.uf.parent[n] = static_cast<uint16_t>(n);
            ++n;
        }
    }
    s.row_start[1024] = static_cast<uint16_t>(n);
    for (int r = 0; r < 1024; ++r) {
        if ((r & 31) != 0) link_rows(s, r, r - 1);
        if (r >= 32) link_rows(s, r, r - 32);
    }
    for (int i = 0; i < n; ++i) s.comp[i] = 0;
    for (int i = 0; i < n; ++i) {
        const uint16_t root = s.uf.find(static_cast<uint16_t>(i));
        s.comp[root] = static_cast<uint8_t>(s.comp[root] | s.faces[i]);
    }
    uint16_t bits = 0;
    for (int i = 0; i < n; ++i) {
        if (s.uf.parent[i] == i) bits = static_cast<uint16_t>(bits | kPairs.v[s.comp[i]]);
    }
    return bits;
}

// ---- walk ---------------------------------------------------------------------------------------------------

namespace {

constexpr int32_t kMaxWalkRadius = 255;

struct WalkNode {
    int16_t dx, dy, dz;
    uint8_t from;      // face of this cube the walk entered through; 6 = camera cube
    uint8_t dirs;      // directions taken so far (bit per face)
    uint16_t vis;
};

struct WalkScratch {
    std::vector<uint64_t> visited;
    std::vector<int64_t> col_top;      // column TOP max, per (dx, dz)
    std::vector<uint32_t> col_stamp;
    std::vector<uint8_t> col_known;
    uint32_t stamp = 0;
    std::vector<WalkNode> queue;
};

bool in_frustum(const float* planes, int64_t dx, int64_t dy, int64_t dz) {
    const float x0 = static_cast<float>(dx * 32), y0 = static_cast<float>(dy * 32), z0 = static_cast<float>(dz * 32);
    for (int p = 0; p < 6; ++p) {
        const float a = planes[p * 4], b = planes[p * 4 + 1], c = planes[p * 4 + 2], d = planes[p * 4 + 3];
        const float px = a >= 0.0f ? x0 + 32.0f : x0;
        const float py = b >= 0.0f ? y0 + 32.0f : y0;
        const float pz = c >= 0.0f ? z0 + 32.0f : z0;
        if (a * px + b * py + c * pz + d < 0.0f) return false;
    }
    return true;
}

} // namespace

int32_t walk_visible(cv_world* world, int64_t cam_cx, int64_t cam_cy, int64_t cam_cz, int32_t max_radius,
                     const float* planes, VisibleWalk& out) {
    out.present.clear();
    out.missing.clear();
    if (world == nullptr || max_radius < 0 || max_radius > kMaxWalkRadius) {
        cv_set_error("walk_visible: bad arguments (radius 0..%d)", kMaxWalkRadius);
        return CV_ERR_ARG;
    }
    constexpr int64_t kLim = (INT64_MAX >> CV_CUBE_SHIFT) - 512;
    if (cam_cx < -kLim || cam_cx > kLim || cam_cy < -kLim || cam_cy > kLim || cam_cz < -kLim || cam_cz > kLim) {
        cv_set_error("walk_visible: camera cube out of range");
        return CV_ERR_ARG;
    }
    thread_local std::unique_ptr<WalkScratch> scratch;
    if (!scratch) scratch = std::make_unique<WalkScratch>();
    WalkScratch& s = *scratch;
    const int64_t r = max_radius;
    const int64_t d = 2 * r + 1;
    const auto vol = static_cast<size_t>(d * d * d);
    s.visited.assign((vol + 63) / 64, 0);
    const auto cols = static_cast<size_t>(d * d);
    if (s.col_stamp.size() < cols) {
        s.col_stamp.assign(cols, 0);
        s.col_top.resize(cols);
        s.col_known.resize(cols);
    }
    if (++s.stamp == 0) {
        std::fill(s.col_stamp.begin(), s.col_stamp.end(), 0u);
        s.stamp = 1;
    }
    s.queue.clear();

    uint32_t generation = 0;
    const uint16_t* flags = table_snapshot(world, &generation);
    const int64_t min_y = world_min_y(world), max_y = world_max_y(world);
    const int64_t cmin = min_y == CV_UNBOUNDED_MIN ? INT64_MIN : (min_y >> CV_CUBE_SHIFT);
    const int64_t cmax = max_y == CV_UNBOUNDED_MAX ? INT64_MAX : ((max_y - 1) >> CV_CUBE_SHIFT);

    auto visit = [&](int64_t dx, int64_t dy, int64_t dz) -> bool {
        const auto i = static_cast<size_t>(((dy + r) * d + (dz + r)) * d + (dx + r));
        uint64_t& wv = s.visited[i >> 6];
        const uint64_t m = uint64_t{1} << (i & 63);
        if ((wv & m) != 0) return false;
        wv |= m;
        return true;
    };
    // True when the missing cube is known air.
    auto known_air_cube = [&](int64_t dx, int64_t dy, int64_t dz) -> bool {
        const auto ci = static_cast<size_t>((dz + r) * d + (dx + r));
        if (s.col_stamp[ci] != s.stamp) {
            s.col_stamp[ci] = s.stamp;
            int64_t tmax = 0;
            s.col_known[ci] = cv_top_range(world, cam_cx + dx, cam_cz + dz, nullptr, &tmax, nullptr) == CV_OK ? 1 : 0;
            s.col_top[ci] = tmax;
        }
        return s.col_known[ci] != 0 && (cam_cy + dy) * CV_CUBE_EDGE >= s.col_top[ci];
    };
    auto push_xyz = [](std::vector<int64_t>& v, int64_t x, int64_t y, int64_t z) {
        v.push_back(x);
        v.push_back(y);
        v.push_back(z);
    };

    // Camera cube.
    visit(0, 0, 0);
    {
        uint16_t vis = 0;
        if (vis_probe(world, cam_cx, cam_cy, cam_cz, flags, generation, &vis) != 0) {
            push_xyz(out.present, cam_cx, cam_cy, cam_cz);
        } else if (cam_cy >= cmin && cam_cy <= cmax && !known_air_cube(0, 0, 0)) {
            push_xyz(out.missing, cam_cx, cam_cy, cam_cz);
        }
        s.queue.push_back(WalkNode{0, 0, 0, 6, 0, kAllPairs});
    }

    for (size_t head = 0; head < s.queue.size(); ++head) {
        const WalkNode node = s.queue[head];
        for (int f = 0; f < 6; ++f) {
            if (((node.dirs >> kOpposite[f]) & 1u) != 0) continue;
            if (node.from != 6 && (node.vis & (1u << (node.from < f ? pair_bit(node.from, f) : pair_bit(f, node.from)))) == 0) {
                continue;
            }
            const int64_t nx = node.dx + kFaceDx[f], ny = node.dy + kFaceDy[f], nz = node.dz + kFaceDz[f];
            if (nx < -r || nx > r || ny < -r || ny > r || nz < -r || nz > r) continue;
            const int64_t cy = cam_cy + ny;
            if (cy < cmin || cy > cmax) continue;
            if (!visit(nx, ny, nz)) continue;
            if (planes != nullptr && !in_frustum(planes, nx, ny, nz)) continue;
            const int64_t cx = cam_cx + nx, cz = cam_cz + nz;
            uint16_t vis = 0;
            if (vis_probe(world, cx, cy, cz, flags, generation, &vis) != 0) {
                push_xyz(out.present, cx, cy, cz);
            } else if (known_air_cube(nx, ny, nz)) {
                vis = kAllPairs;
            } else {
                push_xyz(out.missing, cx, cy, cz);
                continue;
            }
            s.queue.push_back(WalkNode{static_cast<int16_t>(nx), static_cast<int16_t>(ny), static_cast<int16_t>(nz),
                                       static_cast<uint8_t>(kOpposite[f]),
                                       static_cast<uint8_t>(node.dirs | (1u << f)), vis});
        }
    }
    return CV_OK;
}

} // namespace cenda::voxel

using namespace cenda::voxel;

extern "C" {

int32_t cv_cube_visibility(cv_world* w, int64_t cx, int64_t cy, int64_t cz) {
    if (w == nullptr) {
        cv_set_error("cv_cube_visibility: null world");
        return CV_ERR_ARG;
    }
    uint32_t generation = 0;
    const uint16_t* flags = table_snapshot(w, &generation);
    uint16_t bits = 0;
    if (vis_probe(w, cx, cy, cz, flags, generation, &bits) == 0) {
        cv_set_error("cv_cube_visibility: cube not loaded");
        return CV_ERR_MISSING;
    }
    return bits;
}

int64_t cv_visible_cubes(cv_world* w, int64_t cam_cx, int64_t cam_cy, int64_t cam_cz, int32_t max_radius,
                         const float* planes6x4, int64_t* out_xyz, int64_t cap, int64_t* out_missing,
                         int64_t missing_cap, int64_t* out_missing_count) {
    if (out_missing_count != nullptr) *out_missing_count = 0;
    if (cap < 0 || (cap > 0 && out_xyz == nullptr) || missing_cap < 0 || (missing_cap > 0 && out_missing == nullptr)) {
        cv_set_error("cv_visible_cubes: bad buffers");
        return CV_ERR_ARG;
    }
    thread_local VisibleWalk walk;
    const int32_t rc = walk_visible(w, cam_cx, cam_cy, cam_cz, max_radius, planes6x4, walk);
    if (rc != CV_OK) return rc;
    const auto present = static_cast<int64_t>(walk.present.size() / 3);
    const auto missing = static_cast<int64_t>(walk.missing.size() / 3);
    if (out_missing_count != nullptr) *out_missing_count = missing;
    if (present > cap) return CV_NEED(present);
    if (out_missing != nullptr && missing > missing_cap) return CV_NEED(missing);
    if (present > 0) std::memcpy(out_xyz, walk.present.data(), walk.present.size() * sizeof(int64_t));
    if (out_missing != nullptr && missing > 0) {
        std::memcpy(out_missing, walk.missing.data(), walk.missing.size() * sizeof(int64_t));
    }
    return present;
}

} // extern "C"
