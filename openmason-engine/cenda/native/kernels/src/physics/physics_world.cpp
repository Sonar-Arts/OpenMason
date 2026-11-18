// Cenda physics (cp_*): voxel brick cache, swept AABB movement with step-up and grounding, DDA
// raycasts, box sweeps and fluid buoyancy. Contract in include/cenda/physics.h (OpenMason #117).
//
// Layout notes:
//  - Bricks live in a hash map keyed by ABSOLUTE int64 brick coordinates (full triples, hashed with a
//    splitmix64 mixer: no packed keys, nothing aliases at any distance). A brick stores (shape id,
//    fluid id) per cell, or one value when every cell agrees (most of a world is uniform air or stone).
//  - Invalidations queue under a mutex (any thread) and are applied by the owner at the start of
//    its next call: they only mark a dirty box per cached brick, refilled when a query touches it.
//  - Eviction (least recently used) also runs only at the start of a call, so brick pointers a
//    call is holding never dangle.
//  - All geometry runs in double in the world's LOCAL frame (relative to the frame origin); a cell
//    read adds the origin (CellReader::read takes local cells, read_abs absolute ones). Float math
//    the Java this replaced did on absolute coordinates (fluid surfaces, flow sample cells) runs
//    relative to an anchor on a 4096-block grid near the value, which is 0 within 2048 blocks of the
//    origin: the old arithmetic exactly there, full precision anywhere else.
//  - Dynamic boxes are stored in the local frame and shifted when the origin moves.

#include "cenda/physics.h"
#include "physics_internal.hpp"
#include "../int3_key.hpp"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

thread_local std::string t_error;

int32_t fail(int32_t code, const char* message) {
    t_error = message;
    return code;
}

constexpr double kEps = static_cast<double>(CP_CONTACT_EPSILON);
constexpr double kInf = std::numeric_limits<double>::infinity();
constexpr double kGroundProbe = 0.1;     // how far under a standing body support is looked for
constexpr double kMaxSweepPiece = 4.0;   // a long box sweep is split into pieces this long
constexpr int kDynCellShift = 3;         // dynamic-box broadphase cells are 8 units
constexpr int kDynBruteForce = 64;       // below this many boxes a query just tests them all

struct Aabb {
    double min[3];
    double max[3];
};

Aabb make_aabb(double x0, double y0, double z0, double x1, double y1, double z1) {
    return Aabb{{x0, y0, z0}, {x1, y1, z1}};
}

bool finite3(const double* v) {
    return std::isfinite(v[0]) && std::isfinite(v[1]) && std::isfinite(v[2]);
}

// An integral anchor near v on a 4096-block grid (0 for |v| < 2048): float math done relative to it
// keeps its precision far from the origin, and is the unanchored math near it.
double anchor_of(double v) {
    return std::floor(v / 4096.0 + 0.5) * 4096.0;
}

// Strict overlap with tolerance: touching (or within kEps of touching) is not overlapping.
bool overlaps_strict(const Aabb& a, const Aabb& b) {
    for (int i = 0; i < 3; ++i) {
        if (a.max[i] <= b.min[i] + kEps || a.min[i] >= b.max[i] - kEps) {
            return false;
        }
    }
    return true;
}

// Inclusive overlap (gathering: a collider touching the region may still matter).
bool overlaps_loose(const Aabb& a, const Aabb& b) {
    for (int i = 0; i < 3; ++i) {
        if (a.max[i] < b.min[i] - kEps || a.min[i] > b.max[i] + kEps) {
            return false;
        }
    }
    return true;
}

int64_t floor_i(double v) {
    return static_cast<int64_t>(std::floor(v));
}

// Local cell coordinates are far below 2^53: exact.
double dbl(int64_t v) {
    return static_cast<double>(v);
}

struct Shape {
    uint32_t count = 0;
    uint32_t category = 0;
    uint32_t flags = 0;
    cp_box boxes[CP_SHAPE_MAX_BOXES]{};
};

struct Fluid {
    bool defined = false;
    cp_fluid value{};
};

using cenda::Int3;
using cenda::Int3Hash;

struct Brick {
    Int3 at{};                          // brick coordinates (absolute)
    uint16_t uniform_shape = 0;
    uint16_t uniform_fluid = 0;
    std::unique_ptr<uint16_t[]> shapes; // null: every cell is uniform_shape
    std::unique_ptr<uint16_t[]> fluids; // null: every cell is uniform_fluid
    bool dirty = false;
    int32_t dirty_min[3]{};             // local, inclusive
    int32_t dirty_max[3]{};             // local, exclusive
    uint64_t last_use = 0;
};

struct DynBox {
    bool live = false;
    Aabb box{};
    uint32_t category = 0;
    uint32_t flags = 0;
    uint64_t user = 0;
    uint32_t stamp = 0; // dedup during a gather
};

struct Invalidation {
    int64_t x0, y0, z0, x1, y1, z1; // absolute cells, half-open
};

// A collider gathered for one call: a world box plus what it is.
struct Collider {
    Aabb box;
    uint32_t flags;
};

} // namespace

struct cp_world {
    cp_fill_fn fill = nullptr;
    void* user = nullptr;
    int32_t shift = 4;
    int32_t edge = 16;
    int32_t mask_local = 15;
    int32_t cells_per_brick = 4096;
    size_t capacity = 2048;
    uint16_t unloaded_shape = 0;
    int64_t origin[3] = {0, 0, 0}; // frame origin (absolute cell of local 0,0,0)

    std::vector<Shape> shapes;
    std::vector<Fluid> fluids;

    std::unordered_map<Int3, std::unique_ptr<Brick>, Int3Hash> bricks;
    uint64_t clock = 0;

    // Fill scratch (one brick).
    std::vector<uint16_t> fill_shapes;
    std::vector<uint16_t> fill_fluids;

    // Invalidations from any thread.
    std::mutex pending_mutex;
    std::vector<Invalidation> pending;
    bool pending_all = false;
    std::atomic<bool> has_pending{false};

    // Dynamic boxes.
    std::vector<DynBox> boxes;   // index = id - 1
    std::vector<int32_t> free_boxes;
    std::unordered_map<Int3, std::vector<int32_t>, Int3Hash> box_grid; // local 8-unit cells
    int32_t live_boxes = 0;
    uint32_t box_stamp = 0;

    // Gather scratch.
    std::vector<Collider> colliders;
    std::vector<int32_t> box_scratch;

    cp_stats stats{};
};

namespace {

// ---- cache ----------------------------------------------------------------------------------

void mark_dirty(cp_world* w, Brick& b, int32_t x0, int32_t y0, int32_t z0, int32_t x1, int32_t y1, int32_t z1) {
    if (!b.dirty) {
        b.dirty = true;
        b.dirty_min[0] = x0; b.dirty_min[1] = y0; b.dirty_min[2] = z0;
        b.dirty_max[0] = x1; b.dirty_max[1] = y1; b.dirty_max[2] = z1;
        return;
    }
    b.dirty_min[0] = std::min(b.dirty_min[0], x0);
    b.dirty_min[1] = std::min(b.dirty_min[1], y0);
    b.dirty_min[2] = std::min(b.dirty_min[2], z0);
    b.dirty_max[0] = std::max(b.dirty_max[0], x1);
    b.dirty_max[1] = std::max(b.dirty_max[1], y1);
    b.dirty_max[2] = std::max(b.dirty_max[2], z1);
    (void)w;
}

void apply_invalidation(cp_world* w, const Invalidation& inv) {
    if (inv.x0 >= inv.x1 || inv.y0 >= inv.y1 || inv.z0 >= inv.z1) {
        return;
    }
    const int s = w->shift;
    int64_t bx0 = inv.x0 >> s, bx1 = (inv.x1 - 1) >> s;
    int64_t by0 = inv.y0 >> s, by1 = (inv.y1 - 1) >> s;
    int64_t bz0 = inv.z0 >> s, bz1 = (inv.z1 - 1) >> s;
    auto touch = [&](int64_t bx, int64_t by, int64_t bz, Brick& b) {
        int64_t ox = bx << s, oy = by << s, oz = bz << s;
        int32_t lx0 = static_cast<int32_t>(std::max<int64_t>(inv.x0, ox) - ox);
        int32_t ly0 = static_cast<int32_t>(std::max<int64_t>(inv.y0, oy) - oy);
        int32_t lz0 = static_cast<int32_t>(std::max<int64_t>(inv.z0, oz) - oz);
        int32_t lx1 = static_cast<int32_t>(std::min<int64_t>(inv.x1, ox + w->edge) - ox);
        int32_t ly1 = static_cast<int32_t>(std::min<int64_t>(inv.y1, oy + w->edge) - oy);
        int32_t lz1 = static_cast<int32_t>(std::min<int64_t>(inv.z1, oz + w->edge) - oz);
        mark_dirty(w, b, lx0, ly0, lz0, lx1, ly1, lz1);
    };
    // Span in double: a huge box (the whole world) must not overflow the product.
    const double span = (static_cast<double>(bx1) - static_cast<double>(bx0) + 1.0)
        * (static_cast<double>(by1) - static_cast<double>(by0) + 1.0)
        * (static_cast<double>(bz1) - static_cast<double>(bz0) + 1.0);
    if (span > static_cast<double>(w->bricks.size())) {
        for (auto& [key, brick] : w->bricks) {
            if (key.x >= bx0 && key.x <= bx1 && key.y >= by0 && key.y <= by1 && key.z >= bz0 && key.z <= bz1) {
                touch(key.x, key.y, key.z, *brick);
            }
        }
        return;
    }
    for (int64_t by = by0; by <= by1; ++by) {
        for (int64_t bz = bz0; bz <= bz1; ++bz) {
            for (int64_t bx = bx0; bx <= bx1; ++bx) {
                auto it = w->bricks.find(Int3{bx, by, bz});
                if (it != w->bricks.end()) {
                    touch(bx, by, bz, *it->second);
                }
            }
        }
    }
}

void evict_if_needed(cp_world* w) {
    if (w->bricks.size() <= w->capacity) {
        return;
    }
    // Drop the least recently used eighth (at least the overflow) in one pass.
    size_t target = w->capacity - w->capacity / 8;
    size_t drop = w->bricks.size() - target;
    std::vector<std::pair<uint64_t, Int3>> ages; // (last_use, key)
    ages.reserve(w->bricks.size());
    for (auto& [key, brick] : w->bricks) {
        ages.emplace_back(brick->last_use, key);
    }
    std::nth_element(ages.begin(), ages.begin() + static_cast<std::ptrdiff_t>(drop - 1), ages.end(),
                     [](const auto& a, const auto& b) { return a.first < b.first; });
    for (size_t i = 0; i < drop; ++i) {
        w->bricks.erase(ages[i].second);
    }
}

// Start of every owner-thread call: apply queued invalidations, keep the cache bounded.
void begin_call(cp_world* w) {
    if (w->has_pending.load(std::memory_order_acquire)) {
        std::vector<Invalidation> work;
        bool all;
        {
            std::lock_guard<std::mutex> lock(w->pending_mutex);
            work.swap(w->pending);
            all = w->pending_all;
            w->pending_all = false;
            w->has_pending.store(false, std::memory_order_release);
        }
        if (all) {
            w->bricks.clear();
        } else {
            for (const Invalidation& inv : work) {
                apply_invalidation(w, inv);
            }
        }
    }
    evict_if_needed(w);
}

void recompress(cp_world* w, Brick& b) {
    const int n = w->cells_per_brick;
    if (b.shapes) {
        uint16_t first = b.shapes[0];
        bool same = true;
        for (size_t i = 1; i < static_cast<size_t>(n) && same; ++i) same = b.shapes[i] == first;
        if (same) {
            b.uniform_shape = first;
            b.shapes.reset();
        }
    }
    if (b.fluids) {
        uint16_t first = b.fluids[0];
        bool same = true;
        for (size_t i = 1; i < static_cast<size_t>(n) && same; ++i) same = b.fluids[i] == first;
        if (same) {
            b.uniform_fluid = first;
            b.fluids.reset();
        }
    }
}

// Calls the host for a region of one brick. Returns false when the region is not loaded.
bool host_fill(cp_world* w, int64_t x0, int64_t y0, int64_t z0, int32_t sx, int32_t sy, int32_t sz) {
    const size_t n = static_cast<size_t>(sx) * static_cast<size_t>(sy) * static_cast<size_t>(sz);
    std::fill_n(w->fill_shapes.data(), n, uint16_t{0});
    std::fill_n(w->fill_fluids.data(), n, uint16_t{0});
    w->stats.fills++;
    w->stats.filled_cells += static_cast<int64_t>(n);
    int32_t ok = w->fill == nullptr ? 1
        : w->fill(w->user, x0, y0, z0, sx, sy, sz, w->fill_shapes.data(), w->fill_fluids.data());
    if (ok == 0) {
        w->stats.unloaded_fills++;
        return false;
    }
    return true;
}

// Writes the fill scratch for a local region into a brick (expanding a uniform brick if needed).
void store_region(cp_world* w, Brick& b, int32_t lx0, int32_t ly0, int32_t lz0, int32_t sx, int32_t sy, int32_t sz) {
    const int e = w->edge;
    const int n = w->cells_per_brick;
    if (sx == e && sy == e && sz == e) {
        // A whole brick: keep it uniform without allocating when every cell agrees.
        const uint16_t* fs = w->fill_shapes.data();
        const uint16_t* ff = w->fill_fluids.data();
        bool same_shape = std::all_of(fs, fs + n, [&](uint16_t v) { return v == fs[0]; });
        bool same_fluid = std::all_of(ff, ff + n, [&](uint16_t v) { return v == ff[0]; });
        if (same_shape) {
            b.shapes.reset();
            b.uniform_shape = fs[0];
        } else {
            if (!b.shapes) b.shapes.reset(new uint16_t[static_cast<size_t>(n)]);
            std::memcpy(b.shapes.get(), fs, sizeof(uint16_t) * static_cast<size_t>(n));
        }
        if (same_fluid) {
            b.fluids.reset();
            b.uniform_fluid = ff[0];
        } else {
            if (!b.fluids) b.fluids.reset(new uint16_t[static_cast<size_t>(n)]);
            std::memcpy(b.fluids.get(), ff, sizeof(uint16_t) * static_cast<size_t>(n));
        }
        return;
    }
    if (!b.shapes) {
        b.shapes.reset(new uint16_t[static_cast<size_t>(n)]);
        std::fill_n(b.shapes.get(), n, b.uniform_shape);
    }
    if (!b.fluids) {
        b.fluids.reset(new uint16_t[static_cast<size_t>(n)]);
        std::fill_n(b.fluids.get(), n, b.uniform_fluid);
    }
    size_t src = 0;
    for (int32_t y = 0; y < sy; ++y) {
        for (int32_t z = 0; z < sz; ++z) {
            const size_t ue = static_cast<size_t>(e);
            size_t dst = (static_cast<size_t>(ly0 + y) * ue + static_cast<size_t>(lz0 + z)) * ue + static_cast<size_t>(lx0);
            std::memcpy(&b.shapes[dst], &w->fill_shapes[src], sizeof(uint16_t) * static_cast<size_t>(sx));
            std::memcpy(&b.fluids[dst], &w->fill_fluids[src], sizeof(uint16_t) * static_cast<size_t>(sx));
            src += static_cast<size_t>(sx);
        }
    }
    recompress(w, b);
}

// The brick at brick coordinates, filled and clean, or null when the host says it is not loaded.
Brick* brick_at(cp_world* w, int64_t bx, int64_t by, int64_t bz) {
    const Int3 key{bx, by, bz};
    auto it = w->bricks.find(key);
    const int e = w->edge;
    if (it == w->bricks.end()) {
        if (!host_fill(w, bx << w->shift, by << w->shift, bz << w->shift, e, e, e)) {
            return nullptr;
        }
        auto brick = std::make_unique<Brick>();
        brick->at = key;
        store_region(w, *brick, 0, 0, 0, e, e, e);
        brick->last_use = ++w->clock;
        Brick* raw = brick.get();
        w->bricks.emplace(key, std::move(brick));
        return raw;
    }
    Brick* b = it->second.get();
    if (b->dirty) {
        int32_t sx = b->dirty_max[0] - b->dirty_min[0];
        int32_t sy = b->dirty_max[1] - b->dirty_min[1];
        int32_t sz = b->dirty_max[2] - b->dirty_min[2];
        if (!host_fill(w, (bx << w->shift) + b->dirty_min[0], (by << w->shift) + b->dirty_min[1],
                       (bz << w->shift) + b->dirty_min[2], sx, sy, sz)) {
            w->bricks.erase(it);
            return nullptr;
        }
        store_region(w, *b, b->dirty_min[0], b->dirty_min[1], b->dirty_min[2], sx, sy, sz);
        b->dirty = false;
    }
    b->last_use = ++w->clock;
    return b;
}

// Per-call cell reader with a one-brick memo. read() takes LOCAL cells (relative to the frame
// origin), read_abs() absolute ones.
struct CellReader {
    cp_world* w;
    int64_t kx = 0, ky = 0, kz = 0;
    Brick* brick = nullptr;
    bool known = false;

    explicit CellReader(cp_world* world) : w(world) {}

    void read(int64_t x, int64_t y, int64_t z, uint16_t& shape, uint16_t& fluid) {
        read_abs(x + w->origin[0], y + w->origin[1], z + w->origin[2], shape, fluid);
    }

    void read_abs(int64_t x, int64_t y, int64_t z, uint16_t& shape, uint16_t& fluid) {
        const int s = w->shift;
        int64_t bx = x >> s, by = y >> s, bz = z >> s;
        if (!known || bx != kx || by != ky || bz != kz) {
            kx = bx;
            ky = by;
            kz = bz;
            brick = brick_at(w, bx, by, bz);
            known = true;
        }
        if (brick == nullptr) {
            shape = w->unloaded_shape;
            fluid = 0;
            // An unloaded brick is never cached: ask again next call, not next cell.
            return;
        }
        const int m = w->mask_local, e = w->edge;
        const size_t ue = static_cast<size_t>(e);
        size_t i = (static_cast<size_t>(y & m) * ue + static_cast<size_t>(z & m)) * ue + static_cast<size_t>(x & m);
        shape = brick->shapes ? brick->shapes[i] : brick->uniform_shape;
        fluid = brick->fluids ? brick->fluids[i] : brick->uniform_fluid;
    }

    uint16_t shape_at(int64_t x, int64_t y, int64_t z) {
        uint16_t s, f;
        read(x, y, z, s, f);
        return s;
    }

    uint16_t fluid_at(int64_t x, int64_t y, int64_t z) {
        uint16_t s, f;
        read(x, y, z, s, f);
        return f;
    }
};

const Shape* shape_of(const cp_world* w, uint16_t id) {
    if (id == 0 || id >= w->shapes.size()) return nullptr;
    const Shape& s = w->shapes[id];
    return s.count == 0 ? nullptr : &s;
}

const cp_fluid* fluid_of(const cp_world* w, uint16_t id, uint32_t mask) {
    if (id == 0 || id >= w->fluids.size()) return nullptr;
    const Fluid& f = w->fluids[id];
    if (!f.defined || (f.value.category & mask) == 0u) return nullptr;
    return &f.value;
}

// ---- dynamic boxes ----------------------------------------------------------------------------

// Dynamic boxes are local, so the grid is over local 8-unit cells (rebuilt when the origin moves).
template <typename F>
void for_dyn_cells(const Aabb& box, F&& f) {
    int64_t x0 = floor_i(box.min[0]) >> kDynCellShift, x1 = floor_i(box.max[0]) >> kDynCellShift;
    int64_t y0 = floor_i(box.min[1]) >> kDynCellShift, y1 = floor_i(box.max[1]) >> kDynCellShift;
    int64_t z0 = floor_i(box.min[2]) >> kDynCellShift, z1 = floor_i(box.max[2]) >> kDynCellShift;
    for (int64_t y = y0; y <= y1; ++y)
        for (int64_t z = z0; z <= z1; ++z)
            for (int64_t x = x0; x <= x1; ++x)
                f(Int3{x, y, z});
}

void grid_insert(cp_world* w, int32_t index) {
    for_dyn_cells(w->boxes[static_cast<size_t>(index)].box, [&](const Int3& k) { w->box_grid[k].push_back(index); });
}

void grid_remove(cp_world* w, int32_t index) {
    for_dyn_cells(w->boxes[static_cast<size_t>(index)].box, [&](const Int3& k) {
        auto it = w->box_grid.find(k);
        if (it == w->box_grid.end()) return;
        auto& v = it->second;
        v.erase(std::remove(v.begin(), v.end(), index), v.end());
        if (v.empty()) w->box_grid.erase(it);
    });
}

bool valid_box(const cp_box* b) {
    return b != nullptr && std::isfinite(b->min_x) && std::isfinite(b->min_y) && std::isfinite(b->min_z)
        && std::isfinite(b->max_x) && std::isfinite(b->max_y) && std::isfinite(b->max_z)
        && b->min_x <= b->max_x && b->min_y <= b->max_y && b->min_z <= b->max_z;
}

bool valid_aabb(const cp_aabb* b) {
    return b != nullptr && std::isfinite(b->min_x) && std::isfinite(b->min_y) && std::isfinite(b->min_z)
        && std::isfinite(b->max_x) && std::isfinite(b->max_y) && std::isfinite(b->max_z)
        && b->min_x <= b->max_x && b->min_y <= b->max_y && b->min_z <= b->max_z;
}

Aabb to_aabb(const cp_aabb& b) {
    return make_aabb(b.min_x, b.min_y, b.min_z, b.max_x, b.max_y, b.max_z);
}

// Calls f(index) once for every live dynamic box in mask overlapping region (loosely).
template <typename F>
void for_dyn_boxes(cp_world* w, const Aabb& region, uint32_t mask, F&& f) {
    if (w->live_boxes == 0) return;
    if (w->live_boxes <= kDynBruteForce) {
        for (size_t i = 0; i < w->boxes.size(); ++i) {
            const DynBox& d = w->boxes[i];
            if (d.live && (d.category & mask) != 0u && overlaps_loose(d.box, region)) f(static_cast<int32_t>(i));
        }
        return;
    }
    const uint32_t stamp = ++w->box_stamp;
    for_dyn_cells(region, [&](const Int3& k) {
        auto it = w->box_grid.find(k);
        if (it == w->box_grid.end()) return;
        for (int32_t index : it->second) {
            DynBox& d = w->boxes[static_cast<size_t>(index)];
            if (d.stamp == stamp) continue;
            d.stamp = stamp;
            if (d.live && (d.category & mask) != 0u && overlaps_loose(d.box, region)) f(index);
        }
    });
}

// ---- gathering --------------------------------------------------------------------------------

void gather(cp_world* w, CellReader& cells, const Aabb& region, uint32_t mask, std::vector<Collider>& out) {
    out.clear();
    int64_t x0 = floor_i(region.min[0] - kEps), x1 = floor_i(region.max[0] + kEps);
    int64_t y0 = floor_i(region.min[1] - kEps), y1 = floor_i(region.max[1] + kEps);
    int64_t z0 = floor_i(region.min[2] - kEps), z1 = floor_i(region.max[2] + kEps);
    for (int64_t y = y0; y <= y1; ++y) {
        for (int64_t z = z0; z <= z1; ++z) {
            for (int64_t x = x0; x <= x1; ++x) {
                const Shape* s = shape_of(w, cells.shape_at(x, y, z));
                if (s == nullptr || (s->category & mask) == 0u) continue;
                for (uint32_t i = 0; i < s->count; ++i) {
                    const cp_box& b = s->boxes[i];
                    const double fx = dbl(x), fy = dbl(y), fz = dbl(z);
                    Aabb box = make_aabb(fx + static_cast<double>(b.min_x), fy + static_cast<double>(b.min_y),
                                         fz + static_cast<double>(b.min_z), fx + static_cast<double>(b.max_x),
                                         fy + static_cast<double>(b.max_y), fz + static_cast<double>(b.max_z));
                    if (overlaps_loose(box, region)) out.push_back(Collider{box, s->flags});
                }
            }
        }
    }
    for_dyn_boxes(w, region, mask, [&](int32_t index) {
        const DynBox& d = w->boxes[static_cast<size_t>(index)];
        out.push_back(Collider{d.box, d.flags});
    });
}

// ---- clipping ---------------------------------------------------------------------------------

// Limits a move of `d` along `axis` for `body` by `c`. Sets *hit when it limited the move.
// Only colliders overlapping the body on the other two axes count; a collider the body already
// penetrates deeper than kEps on this axis is ignored (the body can always move out of it).
double clip(const Collider& c, const Aabb& body, int axis, double d, bool* hit, int32_t* by_flags) {
    for (int a = 0; a < 3; ++a) {
        if (a == axis) continue;
        if (body.max[a] <= c.box.min[a] + kEps || body.min[a] >= c.box.max[a] - kEps) return d;
    }
    if (d > 0.0 && body.max[axis] <= c.box.min[axis] + kEps) {
        double gap = c.box.min[axis] - body.max[axis];
        if (gap < d) {
            *hit = true;
            if (by_flags) *by_flags = static_cast<int32_t>(c.flags);
            return gap;
        }
    } else if (d < 0.0 && body.min[axis] >= c.box.max[axis] - kEps) {
        double gap = c.box.max[axis] - body.min[axis];
        if (gap > d) {
            *hit = true;
            if (by_flags) *by_flags = static_cast<int32_t>(c.flags);
            return gap;
        }
    }
    return d;
}

double clip_all(const std::vector<Collider>& cs, const Aabb& body, int axis, double d, bool* hit,
                int32_t* by_flags = nullptr) {
    *hit = false;
    if (d == 0.0) {
        return 0.0;
    }
    for (const Collider& c : cs) {
        d = clip(c, body, axis, d, hit, by_flags);
    }
    return d;
}

void shift_box(Aabb& b, int axis, double d) {
    b.min[axis] += d;
    b.max[axis] += d;
}

Aabb body_box(const cp_body& b) {
    return make_aabb(b.x - b.half_x, b.y - b.below, b.z - b.half_z, b.x + b.half_x, b.y + b.above,
                     b.z + b.half_z);
}

bool valid_body(const cp_body* b) {
    return b != nullptr && std::isfinite(b->x) && std::isfinite(b->y) && std::isfinite(b->z)
        && std::isfinite(b->vx) && std::isfinite(b->vy) && std::isfinite(b->vz)
        && b->half_x >= 0.0f && b->half_z >= 0.0f && b->below + b->above >= 0.0f
        && std::isfinite(b->half_x) && std::isfinite(b->half_z) && std::isfinite(b->below)
        && std::isfinite(b->above) && std::isfinite(b->step_height) && std::isfinite(b->partial_step_height);
}

bool rise_allowed(const cp_body& b, double rise, int32_t support_flags) {
    if (rise <= static_cast<double>(b.step_height) + kEps) return true;
    return (static_cast<uint32_t>(support_flags) & CP_SHAPE_PARTIAL) != 0u
        && rise <= static_cast<double>(b.partial_step_height) + kEps;
}

void move_one(cp_world* w, CellReader& cells, cp_body& b, double dt) {
    const bool was_grounded = (b.flags & CP_BODY_ON_GROUND) != 0u;
    const double step_max = std::max(0.0, static_cast<double>(std::max(b.step_height, b.partial_step_height)));
    double d[3] = {static_cast<double>(b.vx) * dt, static_cast<double>(b.vy) * dt, static_cast<double>(b.vz) * dt};
    Aabb box = body_box(b);
    uint32_t contacts = 0;

    // One gather for the whole call: the swept path, the step-up headroom and the ground probe.
    Aabb region = box;
    for (int a = 0; a < 3; ++a) {
        if (d[a] > 0) region.max[a] += d[a]; else region.min[a] += d[a];
    }
    region.max[1] += step_max;
    region.min[1] -= kGroundProbe;
    gather(w, cells, region, b.mask, w->colliders);
    const std::vector<Collider>& cs = w->colliders;

    // Lift: a body already inside a low collider (snow piling up under it, a step placed into its
    // feet) is put on top when the rise is one it could step and the space above is free.
    {
        double top = -kInf;
        int32_t flags = 0;
        bool inside = false;
        for (const Collider& c : cs) {
            if (!overlaps_strict(c.box, box)) continue;
            inside = true;
            if (c.box.max[1] > top) {
                top = c.box.max[1];
                flags = static_cast<int32_t>(c.flags);
            } else if (c.box.max[1] == top) {
                flags &= static_cast<int32_t>(c.flags); // every collider at the top must be partial
            }
        }
        if (inside) {
            double rise = top - box.min[1];
            if (rise > kEps && rise_allowed(b, rise, flags)) {
                Aabb lifted = box;
                shift_box(lifted, 1, rise);
                bool blocked = false;
                for (const Collider& c : cs) {
                    if (overlaps_strict(c.box, lifted)) { blocked = true; break; }
                }
                if (!blocked) {
                    box = lifted;
                    contacts |= CP_CONTACT_LIFTED;
                }
            }
        }
    }

    // Per axis, in the order X, Y, Z (the order the per-axis resolvers this replaced used, so a
    // horizontal axis sees the height the body had when that axis was resolved: X before this
    // move's vertical part, Z after it). Each axis is one swept clip — nothing tunnels.
    //
    // Step-up, inside each horizontal axis: when the axis is cut short, it is retried raised by
    // max(step, partial step) and lowered back onto what it now stands on; accepted when it gets
    // further and the rise is allowed (rise_allowed). Only while not moving up — a jump into a
    // wall stays a jump — and only while standing (or landed this move), or, for a body that
    // mantles (AIR_STEP), also airborne: at the top of a jump or falling, onto an obstacle whose
    // top is within the step limits of its feet.
    double moved[3] = {0.0, 0.0, 0.0};
    bool hit[3] = {false, false, false};
    bool keep_axis[3] = {false, false, false};
    bool landed = false;
    double stepped_rise = 0.0;
    for (int axis = 0; axis < 3; ++axis) {
        if (axis == 1) {
            int32_t support = 0;
            moved[1] = clip_all(cs, box, 1, d[1], &hit[1], &support);
            shift_box(box, 1, moved[1]);
            if (hit[1] && d[1] < 0.0) landed = true;
            continue;
        }
        double m = clip_all(cs, box, axis, d[axis], &hit[axis]);
        const bool air_step = (b.flags & CP_BODY_AIR_STEP) != 0u;
        const bool may_step = step_max > 0.0 && hit[axis] && d[axis] != 0.0
            && (was_grounded || landed || air_step);
        // While rising nothing is climbed, but a mantling body pressing into an obstacle it will be
        // able to climb keeps that axis' speed (the resolver this replaced let it slide into the
        // block on the way up and kept its run-up), so the climb at the apex carries its momentum.
        if (may_step) {
            bool hit_up = false;
            double up = clip_all(cs, box, 1, step_max, &hit_up);
            Aabb raised = box;
            shift_box(raised, 1, up);
            bool hit_over = false;
            double over = clip_all(cs, raised, axis, d[axis], &hit_over);
            shift_box(raised, axis, over);
            bool hit_down = false;
            int32_t support = 0;
            double down = clip_all(cs, raised, 1, -up, &hit_down, &support);
            shift_box(raised, 1, down);
            double rise = raised.min[1] - box.min[1];
            const bool climbable = std::fabs(over) > std::fabs(m) + 1e-9 && hit_down && rise > -kEps
                && rise_allowed(b, rise, support);
            if (climbable && d[1] > 0.0) {
                if (air_step) keep_axis[axis] = true;
            } else if (climbable) {
                box = raised;
                moved[axis] = over;
                hit[axis] = hit_over;
                landed = true;
                stepped_rise += rise;
                continue;
            }
        }
        shift_box(box, axis, m);
        moved[axis] = m;
    }
    if (stepped_rise > kEps) contacts |= CP_CONTACT_STEPPED;

    // Velocity and contacts.
    if (hit[1]) {
        contacts |= d[1] < 0.0 ? CP_CONTACT_GROUND : CP_CONTACT_CEILING;
        b.vy = 0.0f;
    }
    if (landed) {
        contacts |= CP_CONTACT_GROUND;
        if (b.vy < 0.0f) b.vy = 0.0f;
    }
    const bool keep = (b.flags & CP_BODY_KEEP_VELOCITY) != 0u;
    if (hit[0] && d[0] != 0.0) {
        contacts |= d[0] > 0.0 ? CP_CONTACT_POS_X : CP_CONTACT_NEG_X;
        if (!keep && !keep_axis[0]) b.vx = 0.0f;
    }
    if (hit[2] && d[2] != 0.0) {
        contacts |= d[2] > 0.0 ? CP_CONTACT_POS_Z : CP_CONTACT_NEG_Z;
        if (!keep && !keep_axis[2]) b.vz = 0.0f;
    }

    // Grounding: landing grounds; moving up ungrounds; a standing body that did neither keeps its
    // footing only while something holds it within the probe distance.
    bool grounded;
    if ((contacts & CP_CONTACT_GROUND) != 0u) {
        grounded = true;
    } else if (d[1] > 0.0) {
        grounded = false;
    } else if (was_grounded) {
        if ((b.flags & CP_BODY_NO_GROUND_PROBE) != 0u) {
            grounded = true;
        } else {
            bool hit_probe = false;
            clip_all(cs, box, 1, -kGroundProbe, &hit_probe);
            grounded = hit_probe;
            if (!grounded) contacts |= CP_CONTACT_LEFT_GROUND;
        }
    } else {
        grounded = false;
    }

    b.x = box.min[0] + b.half_x;
    b.y = box.min[1] + b.below;
    b.z = box.min[2] + b.half_z;
    b.flags = grounded ? (b.flags | CP_BODY_ON_GROUND) : (b.flags & ~CP_BODY_ON_GROUND);
    b.contacts = contacts;
}

// Restores anchors that did not move to their exact input value: the min/half round trip could
// otherwise change the last bit, and a resting body would creep.
void move_body(cp_world* w, CellReader& cells, cp_body& b, double dt) {
    const double x = b.x, y = b.y, z = b.z;
    const float vx = b.vx, vy = b.vy, vz = b.vz;
    move_one(w, cells, b, dt);
    if (vx == 0.0f) b.x = x;
    if (vz == 0.0f) b.z = z;
    if (vy == 0.0f && (b.contacts & (CP_CONTACT_LIFTED | CP_CONTACT_STEPPED)) == 0u) b.y = y;
}

// ---- fluids -------------------------------------------------------------------------------------

void measure(cp_world* w, CellReader& cells, double x, double feet_y, double z, float height, float half_x,
             double flow_base_y, float flow_height, uint32_t mask, cp_fluid_sample& out) {
    out.submersion = 0.0f;
    out.surface_y = std::numeric_limits<double>::quiet_NaN();
    out.flow_x = out.flow_y = out.flow_z = 0.0f;
    out.flow_samples = 0;
    out.reserved = 0;
    if (mask == 0u) return;

    if (height > 0.0f) {
        int64_t bx = floor_i(x), bz = floor_i(z);
        // Float, as the Java rule it replaces (relative to an anchor, see anchor_of): floor(feetY +
        // height) with a float add.
        const double ay = anchor_of(feet_y);
        const float feet = static_cast<float>(feet_y - ay);
        int64_t feet_block = floor_i(feet_y);
        int64_t head_block = floor_i(static_cast<double>(feet + height)) + static_cast<int64_t>(ay);
        int64_t top = std::numeric_limits<int64_t>::min();
        const cp_fluid* top_fluid = nullptr;
        for (int64_t y = feet_block; y <= head_block + 1; ++y) {
            const cp_fluid* f = fluid_of(w, cells.fluid_at(bx, y, bz), mask);
            if (f != nullptr) {
                top = y;
                top_fluid = f;
            }
        }
        if (top_fluid != nullptr) {
            // Float arithmetic as the Java rule it replaces: surface = y + fraction (float),
            // depth = clamp(surface - feet, 0, height), fraction = depth / height.
            float surface = static_cast<float>(dbl(top) - ay) + top_fluid->surface;
            float depth = std::max(0.0f, std::min(height, surface - feet));
            out.submersion = depth / height;
            out.surface_y = ay + static_cast<double>(surface);
        }
    }

    // Flow: 3 heights x 5 points, averaged over the samples that were in fluid.
    const float hs[3] = {0.1f, flow_height * 0.5f, flow_height * 0.9f};
    const float width = static_cast<float>(static_cast<double>(half_x) * 2.0);
    const float offs[5][2] = {{0.0f, 0.0f}, {width * 0.4f, 0.0f}, {-width * 0.4f, 0.0f},
                              {0.0f, width * 0.4f}, {0.0f, -width * 0.4f}};
    const double ax = anchor_of(x), ay = anchor_of(flow_base_y), az = anchor_of(z);
    const float lx = static_cast<float>(x - ax), lbase = static_cast<float>(flow_base_y - ay);
    const float lz = static_cast<float>(z - az);
    float fx = 0.0f, fy = 0.0f, fz = 0.0f;
    int32_t n = 0;
    for (float h : hs) {
        int64_t by = floor_i(static_cast<double>(lbase + h)) + static_cast<int64_t>(ay);
        for (const auto& o : offs) {
            int64_t bx = floor_i(static_cast<double>(lx + o[0])) + static_cast<int64_t>(ax);
            int64_t bz = floor_i(static_cast<double>(lz + o[1])) + static_cast<int64_t>(az);
            const cp_fluid* f = fluid_of(w, cells.fluid_at(bx, by, bz), mask);
            if (f == nullptr) continue;
            fx += f->flow_x;
            fy += f->flow_y;
            fz += f->flow_z;
            ++n;
        }
    }
    if (n > 0) {
        out.flow_x = fx / static_cast<float>(n);
        out.flow_y = fy / static_cast<float>(n);
        out.flow_z = fz / static_cast<float>(n);
    }
    out.flow_samples = n;
}

float buoyancy_vy(float vy, float submersion, float dt, const cp_integrator& p) {
    float accel = p.gravity * (1.0f - submersion / p.float_submersion);
    float result = vy + accel * dt;
    if (vy < p.drift_speed) {
        result = std::min(result, p.drift_speed);
    }
    float damping_arg = -p.fluid_drag * submersion * dt;
    return result * static_cast<float>(std::exp(static_cast<double>(damping_arg)));
}

// ---- rays ---------------------------------------------------------------------------------------

// Slab test. Returns the entry t in [0, inf) or +inf; *face is the entry face (NONE when inside).
double ray_aabb(const double o[3], const double d[3], const Aabb& b, int32_t* face) {
    double t_min = 0.0, t_max = kInf;
    int32_t entry = CP_FACE_NONE;
    for (int a = 0; a < 3; ++a) {
        if (std::fabs(d[a]) < 1e-12) {
            if (o[a] < b.min[a] || o[a] > b.max[a]) return kInf;
            continue;
        }
        double inv = 1.0 / d[a];
        double t1 = (b.min[a] - o[a]) * inv;
        double t2 = (b.max[a] - o[a]) * inv;
        int32_t f1 = a * 2;     // entering through the min side
        if (t1 > t2) {
            std::swap(t1, t2);
            f1 = a * 2 + 1;     // entering through the max side
        }
        if (t1 > t_min) {
            t_min = t1;
            entry = f1;
        }
        t_max = std::min(t_max, t2);
        if (t_min > t_max) return kInf;
    }
    if (face) *face = entry;
    return t_min;
}

void clear_hit(cp_hit* out) {
    out->kind = CP_HIT_NONE;
    out->face = CP_FACE_NONE;
    out->x = out->y = out->z = 0;
    out->id = 0;
    out->reserved = 0;
    out->distance = std::numeric_limits<double>::infinity();
    out->px = out->py = out->pz = 0.0;
    out->user = 0;
}

int32_t raycast(cp_world* w, const double* origin, const double* direction, double max_distance, uint32_t mask,
                uint32_t fluid_mask, cp_hit* out) {
    clear_hit(out);
    double len = std::sqrt(direction[0] * direction[0] + direction[1] * direction[1] + direction[2] * direction[2]);
    if (!(len > 0.0) || !(max_distance >= 0.0)) return 0;
    const double o[3] = {origin[0], origin[1], origin[2]};
    const double d[3] = {direction[0] / len, direction[1] / len, direction[2] / len};
    const double max_t = max_distance;

    double best = kInf;
    int32_t best_face = CP_FACE_NONE, best_kind = CP_HIT_NONE, best_id = 0;
    int64_t best_cell[3] = {0, 0, 0};
    uint64_t best_user = 0;

    // Dynamic boxes along the ray's extent.
    if (mask != 0u && w->live_boxes > 0) {
        Aabb extent = make_aabb(std::min(o[0], o[0] + d[0] * max_t), std::min(o[1], o[1] + d[1] * max_t),
                                std::min(o[2], o[2] + d[2] * max_t), std::max(o[0], o[0] + d[0] * max_t),
                                std::max(o[1], o[1] + d[1] * max_t), std::max(o[2], o[2] + d[2] * max_t));
        for_dyn_boxes(w, extent, mask, [&](int32_t index) {
            const DynBox& db = w->boxes[static_cast<size_t>(index)];
            int32_t face = CP_FACE_NONE;
            double t = ray_aabb(o, d, db.box, &face);
            if (t <= max_t && t < best) {
                best = t;
                best_face = face;
                best_kind = CP_HIT_BOX;
                best_id = index + 1;
                best_user = db.user;
            }
        });
    }

    // Cells by DDA (Amanatides & Woo).
    if (mask != 0u || fluid_mask != 0u) {
        CellReader cells(w);
        int64_t c[3] = {floor_i(o[0]), floor_i(o[1]), floor_i(o[2])};
        int64_t step[3];
        double t_next[3], t_delta[3];
        for (int a = 0; a < 3; ++a) {
            if (d[a] > 0.0) {
                step[a] = 1;
                t_next[a] = (static_cast<double>(c[a] + 1) - o[a]) / d[a];
                t_delta[a] = 1.0 / d[a];
            } else if (d[a] < 0.0) {
                step[a] = -1;
                t_next[a] = (o[a] - static_cast<double>(c[a])) / -d[a];
                t_delta[a] = -1.0 / d[a];
            } else {
                step[a] = 0;
                t_next[a] = kInf;
                t_delta[a] = kInf;
            }
        }
        double t_enter = 0.0;
        int32_t enter_face = CP_FACE_NONE;
        while (t_enter <= max_t && t_enter < best) {
            uint16_t sid, fid;
            cells.read(c[0], c[1], c[2], sid, fid);
            const Shape* s = shape_of(w, sid);
            if (s != nullptr && (s->category & mask) != 0u) {
                for (uint32_t i = 0; i < s->count; ++i) {
                    const cp_box& bx = s->boxes[i];
                    const double fx = dbl(c[0]), fy = dbl(c[1]), fz = dbl(c[2]);
                    Aabb box = make_aabb(fx + static_cast<double>(bx.min_x), fy + static_cast<double>(bx.min_y),
                                         fz + static_cast<double>(bx.min_z), fx + static_cast<double>(bx.max_x),
                                         fy + static_cast<double>(bx.max_y), fz + static_cast<double>(bx.max_z));
                    int32_t face = CP_FACE_NONE;
                    double t = ray_aabb(o, d, box, &face);
                    if (t <= max_t && t < best) {
                        best = t;
                        best_face = face;
                        best_kind = CP_HIT_CELL;
                        best_id = sid;
                        best_cell[0] = c[0]; best_cell[1] = c[1]; best_cell[2] = c[2];
                    }
                }
            }
            if (fid != 0 && fluid_of(w, fid, fluid_mask) != nullptr && t_enter < best) {
                best = t_enter;
                best_face = enter_face;
                best_kind = CP_HIT_FLUID;
                best_id = fid;
                best_cell[0] = c[0]; best_cell[1] = c[1]; best_cell[2] = c[2];
            }
            // Boxes of a cell lie inside it, so the loop condition (t_enter < best) ends the walk
            // at the first cell past a hit.
            int a = t_next[0] < t_next[1] ? (t_next[0] < t_next[2] ? 0 : 2) : (t_next[1] < t_next[2] ? 1 : 2);
            if (!std::isfinite(t_next[a])) break;
            t_enter = t_next[a];
            t_next[a] += t_delta[a];
            c[a] += step[a];
            enter_face = a * 2 + (step[a] > 0 ? 0 : 1);
        }
    }

    if (best_kind == CP_HIT_NONE) return 0;
    out->kind = best_kind;
    out->face = best_face;
    out->id = best_id;
    out->distance = best;
    out->px = o[0] + d[0] * best;
    out->py = o[1] + d[1] * best;
    out->pz = o[2] + d[2] * best;
    out->user = best_user;
    if (best_kind == CP_HIT_BOX) {
        // The cell holding the hit point, nudged into the box.
        const double nudge = 1e-4;
        out->x = floor_i(o[0] + d[0] * (best + nudge)) + w->origin[0];
        out->y = floor_i(o[1] + d[1] * (best + nudge)) + w->origin[1];
        out->z = floor_i(o[2] + d[2] * (best + nudge)) + w->origin[2];
    } else {
        out->x = best_cell[0] + w->origin[0];
        out->y = best_cell[1] + w->origin[1];
        out->z = best_cell[2] + w->origin[2];
    }
    return 1;
}

// Swept box piece: the earliest t in [0,1] at which the box (half extents h) moving from a by
// delta overlaps a collider. Minkowski: each collider grown by h (less the contact tolerance, so
// a path that only grazes a surface is not a hit), the centre traced as a ray.
bool sweep_piece(cp_world* w, CellReader& cells, const double a[3], const double delta[3], const double h[3],
                 uint32_t mask, double* out_t, int32_t* out_face, const Collider** out_c,
                 std::vector<Collider>& cs) {
    Aabb region = make_aabb(std::min(a[0], a[0] + delta[0]) - h[0], std::min(a[1], a[1] + delta[1]) - h[1],
                            std::min(a[2], a[2] + delta[2]) - h[2], std::max(a[0], a[0] + delta[0]) + h[0],
                            std::max(a[1], a[1] + delta[1]) + h[1], std::max(a[2], a[2] + delta[2]) + h[2]);
    gather(w, cells, region, mask, cs);
    double best = kInf;
    int32_t best_face = CP_FACE_NONE;
    const Collider* best_c = nullptr;
    for (const Collider& c : cs) {
        Aabb grown = c.box;
        bool empty = false;
        for (int i = 0; i < 3; ++i) {
            grown.min[i] -= h[i] - kEps;
            grown.max[i] += h[i] - kEps;
            if (grown.min[i] > grown.max[i]) empty = true;
        }
        if (empty) continue;
        int32_t face = CP_FACE_NONE;
        double t = ray_aabb(a, delta, grown, &face);
        if (t <= 1.0 && t < best) {
            best = t;
            best_face = face;
            best_c = &c;
        }
    }
    if (best_c == nullptr) return false;
    *out_t = best;
    *out_face = best_face;
    *out_c = best_c;
    return true;
}

} // namespace

// ---- ABI ------------------------------------------------------------------------------------------

extern "C" {

int32_t cp_abi_version(void) {
    return CP_ABI_VERSION;
}

const char* cp_last_error(void) {
    return t_error.c_str();
}

cp_world* cp_world_create(const cp_world_desc* desc) {
    if (desc == nullptr) {
        fail(CP_ERR_ARG, "null world description");
        return nullptr;
    }
    int32_t shift = desc->brick_shift == 0 ? 4 : desc->brick_shift;
    if (shift < 3 || shift > 5) {
        fail(CP_ERR_ARG, "brick_shift must be 3..5");
        return nullptr;
    }
    auto* w = new (std::nothrow) cp_world();
    if (w == nullptr) {
        fail(CP_ERR_ARG, "out of memory");
        return nullptr;
    }
    w->fill = desc->fill;
    w->user = desc->user;
    w->shift = shift;
    w->edge = 1 << shift;
    w->mask_local = w->edge - 1;
    w->cells_per_brick = w->edge * w->edge * w->edge;
    w->capacity = desc->cache_bricks <= 0 ? 2048u : static_cast<size_t>(desc->cache_bricks);
    if (w->capacity < 16) w->capacity = 16;
    w->unloaded_shape = desc->unloaded_shape;
    // The tables grow with the ids the host defines (a full shape table would be ~26 MiB).
    w->shapes.resize(1);
    w->fluids.resize(1);
    w->fill_shapes.resize(static_cast<size_t>(w->cells_per_brick));
    w->fill_fluids.resize(static_cast<size_t>(w->cells_per_brick));
    w->colliders.reserve(256);
    return w;
}

void cp_world_destroy(cp_world* world) {
    delete world;
}

int32_t cp_set_origin(cp_world* world, int64_t ox, int64_t oy, int64_t oz) {
    if (world == nullptr) return fail(CP_ERR_ARG, "null world");
    const int64_t next[3] = {ox, oy, oz};
    if (next[0] == world->origin[0] && next[1] == world->origin[1] && next[2] == world->origin[2]) return CP_OK;
    // Dynamic boxes keep their absolute place: local' = local + (old - new). The cell cache is keyed
    // by absolute cells and needs nothing.
    const double shift[3] = {static_cast<double>(world->origin[0] - ox), static_cast<double>(world->origin[1] - oy),
                             static_cast<double>(world->origin[2] - oz)};
    world->origin[0] = ox;
    world->origin[1] = oy;
    world->origin[2] = oz;
    world->box_grid.clear();
    for (size_t i = 0; i < world->boxes.size(); ++i) {
        DynBox& d = world->boxes[i];
        if (!d.live) continue;
        for (int a = 0; a < 3; ++a) {
            d.box.min[a] += shift[a];
            d.box.max[a] += shift[a];
        }
        grid_insert(world, static_cast<int32_t>(i));
    }
    return CP_OK;
}

int32_t cp_get_origin(const cp_world* world, int64_t* out) {
    if (world == nullptr || out == nullptr) return fail(CP_ERR_ARG, "null world or output");
    out[0] = world->origin[0];
    out[1] = world->origin[1];
    out[2] = world->origin[2];
    return CP_OK;
}

int32_t cp_shape_define(cp_world* world, int32_t id, const cp_box* boxes, int32_t count, uint32_t category,
                        uint32_t flags) {
    if (world == nullptr || id <= 0 || id >= CP_MAX_SHAPES || count < 0 || count > CP_SHAPE_MAX_BOXES
        || (count > 0 && boxes == nullptr)) {
        return fail(CP_ERR_ARG, "bad shape definition");
    }
    Shape s;
    s.category = category;
    s.flags = flags;
    for (int32_t i = 0; i < count; ++i) {
        if (!valid_box(&boxes[i])) return fail(CP_ERR_ARG, "bad shape box");
        cp_box b = boxes[i];
        b.min_x = std::clamp(b.min_x, 0.0f, 1.0f); b.max_x = std::clamp(b.max_x, 0.0f, 1.0f);
        b.min_y = std::clamp(b.min_y, 0.0f, 1.0f); b.max_y = std::clamp(b.max_y, 0.0f, 1.0f);
        b.min_z = std::clamp(b.min_z, 0.0f, 1.0f); b.max_z = std::clamp(b.max_z, 0.0f, 1.0f);
        if (b.max_x <= b.min_x || b.max_y <= b.min_y || b.max_z <= b.min_z) continue; // empty after clamping
        s.boxes[s.count++] = b;
    }
    if (static_cast<size_t>(id) >= world->shapes.size()) world->shapes.resize(static_cast<size_t>(id) + 1);
    world->shapes[static_cast<size_t>(id)] = s;
    return CP_OK;
}

int32_t cp_fluid_define(cp_world* world, int32_t id, const cp_fluid* fluid) {
    if (world == nullptr || id <= 0 || id >= CP_MAX_FLUIDS || fluid == nullptr || !std::isfinite(fluid->surface)
        || !std::isfinite(fluid->flow_x) || !std::isfinite(fluid->flow_y) || !std::isfinite(fluid->flow_z)) {
        return fail(CP_ERR_ARG, "bad fluid definition");
    }
    if (static_cast<size_t>(id) >= world->fluids.size()) world->fluids.resize(static_cast<size_t>(id) + 1);
    Fluid& f = world->fluids[static_cast<size_t>(id)];
    f.defined = true;
    f.value = *fluid;
    f.value.surface = std::clamp(fluid->surface, 0.0f, 1.0f);
    return CP_OK;
}

void cp_invalidate_box(cp_world* world, int64_t x0, int64_t y0, int64_t z0, int64_t x1, int64_t y1, int64_t z1) {
    if (world == nullptr || x0 >= x1 || y0 >= y1 || z0 >= z1) return;
    std::lock_guard<std::mutex> lock(world->pending_mutex);
    if (world->pending_all) return;
    if (world->pending.size() >= 65536) {
        world->pending.clear();
        world->pending_all = true;
    } else {
        world->pending.push_back(Invalidation{x0, y0, z0, x1, y1, z1});
    }
    world->has_pending.store(true, std::memory_order_release);
}

void cp_invalidate_cell(cp_world* world, int64_t x, int64_t y, int64_t z) {
    if (x == INT64_MAX || y == INT64_MAX || z == INT64_MAX) return; // no half-open box holds it
    cp_invalidate_box(world, x, y, z, x + 1, y + 1, z + 1);
}

void cp_invalidate_all(cp_world* world) {
    if (world == nullptr) return;
    std::lock_guard<std::mutex> lock(world->pending_mutex);
    world->pending.clear();
    world->pending_all = true;
    world->has_pending.store(true, std::memory_order_release);
}

int32_t cp_cell_get(cp_world* world, int64_t x, int64_t y, int64_t z, uint16_t* out_shape, uint16_t* out_fluid) {
    if (world == nullptr) return fail(CP_ERR_ARG, "null world");
    begin_call(world);
    CellReader cells(world);
    uint16_t s, f;
    cells.read_abs(x, y, z, s, f);
    if (out_shape) *out_shape = s;
    if (out_fluid) *out_fluid = f;
    return CP_OK;
}

void cp_stats_get(const cp_world* world, cp_stats* out) {
    if (world == nullptr || out == nullptr) return;
    *out = world->stats;
    out->bricks = static_cast<int32_t>(world->bricks.size());
    int32_t dense = 0;
    for (const auto& [key, brick] : world->bricks) {
        if (brick->shapes) ++dense;
    }
    out->dense_bricks = dense;
    out->dynamic_boxes = world->live_boxes;
    out->reserved = 0;
}

int32_t cp_box_add(cp_world* world, const cp_aabb* box, uint32_t category, uint32_t flags, uint64_t user) {
    if (world == nullptr || !valid_aabb(box)) {
        fail(CP_ERR_ARG, "bad dynamic box");
        return 0;
    }
    int32_t index;
    if (!world->free_boxes.empty()) {
        index = world->free_boxes.back();
        world->free_boxes.pop_back();
    } else {
        if (world->boxes.size() >= static_cast<size_t>(std::numeric_limits<int32_t>::max() - 1)) {
            fail(CP_ERR_FULL, "too many dynamic boxes");
            return 0;
        }
        index = static_cast<int32_t>(world->boxes.size());
        world->boxes.emplace_back();
    }
    DynBox& d = world->boxes[static_cast<size_t>(index)];
    d.live = true;
    d.box = to_aabb(*box);
    d.category = category;
    d.flags = flags;
    d.user = user;
    d.stamp = 0;
    grid_insert(world, index);
    world->live_boxes++;
    return index + 1;
}

int32_t cp_box_set(cp_world* world, int32_t id, const cp_aabb* box, uint32_t category, uint32_t flags) {
    if (world == nullptr || id <= 0 || static_cast<size_t>(id) > world->boxes.size() || !valid_aabb(box)
        || !world->boxes[static_cast<size_t>(id - 1)].live) {
        return fail(CP_ERR_ARG, "bad dynamic box");
    }
    int32_t index = id - 1;
    grid_remove(world, index);
    DynBox& d = world->boxes[static_cast<size_t>(index)];
    d.box = to_aabb(*box);
    d.category = category;
    d.flags = flags;
    grid_insert(world, index);
    return CP_OK;
}

int32_t cp_box_remove(cp_world* world, int32_t id) {
    if (world == nullptr || id <= 0 || static_cast<size_t>(id) > world->boxes.size()
        || !world->boxes[static_cast<size_t>(id - 1)].live) {
        return fail(CP_ERR_ARG, "bad dynamic box");
    }
    int32_t index = id - 1;
    grid_remove(world, index);
    world->boxes[static_cast<size_t>(index)].live = false;
    world->free_boxes.push_back(index);
    world->live_boxes--;
    return CP_OK;
}

void cp_box_clear(cp_world* world) {
    if (world == nullptr) return;
    world->boxes.clear();
    world->free_boxes.clear();
    world->box_grid.clear();
    world->live_boxes = 0;
}

float cp_cell_top(cp_world* world, int64_t x, int64_t y, int64_t z, double min_x, double min_z, double max_x,
                  double max_z, uint32_t mask) {
    if (world == nullptr) return 0.0f;
    begin_call(world);
    // Clip the footprint to the cell (inclusive edges), in cell-local coordinates.
    const double lx = dbl(x - world->origin[0]), lz = dbl(z - world->origin[2]);
    double cx0 = std::max(min_x, lx) - lx;
    double cx1 = std::min(max_x, lx + 1.0) - lx;
    double cz0 = std::max(min_z, lz) - lz;
    double cz1 = std::min(max_z, lz + 1.0) - lz;
    if (cx0 > cx1 || cz0 > cz1) return 0.0f;
    CellReader cells(world);
    uint16_t sid, fid;
    cells.read_abs(x, y, z, sid, fid);
    const Shape* s = shape_of(world, sid);
    if (s == nullptr || (s->category & mask) == 0u) return 0.0f;
    float top = 0.0f;
    for (uint32_t i = 0; i < s->count; ++i) {
        const cp_box& b = s->boxes[i];
        if (cx0 <= b.max_x && cx1 >= b.min_x && cz0 <= b.max_z && cz1 >= b.min_z && b.max_y > top) {
            top = b.max_y;
        }
    }
    return top;
}

int32_t cp_overlaps(cp_world* world, const cp_aabb* box, uint32_t mask) {
    if (world == nullptr || !valid_aabb(box)) return 0;
    begin_call(world);
    CellReader cells(world);
    Aabb b = to_aabb(*box);
    gather(world, cells, b, mask, world->colliders);
    for (const Collider& c : world->colliders) {
        if (overlaps_strict(c.box, b)) return 1;
    }
    return 0;
}

int32_t cp_nearest_open_cell(cp_world* world, const int64_t* cells_xyz, int32_t count, const double* preference,
                             uint32_t mask) {
    if (world == nullptr || cells_xyz == nullptr || count <= 0) return -1;
    begin_call(world);
    CellReader cells(world);
    int32_t best = -1;
    float best_d = std::numeric_limits<float>::max();
    for (int32_t i = 0; i < count; ++i) {
        const int64_t* c = &cells_xyz[static_cast<size_t>(i) * 3];
        uint16_t sid, fid;
        cells.read_abs(c[0], c[1], c[2], sid, fid);
        const Shape* s = shape_of(world, sid);
        if (s != nullptr && (s->category & mask) != 0u) continue;
        if (preference == nullptr) return i;
        // Float arithmetic as the Java rule it replaces: dx*dx + dy*dy + dz*dz on cell centres (each
        // difference taken in double in the frame, then rounded once).
        float dx = static_cast<float>(dbl(c[0] - world->origin[0]) + 0.5 - preference[0]);
        float dy = static_cast<float>(dbl(c[1] - world->origin[1]) + 0.5 - preference[1]);
        float dz = static_cast<float>(dbl(c[2] - world->origin[2]) + 0.5 - preference[2]);
        float d = dx * dx + dy * dy + dz * dz;
        if (d < best_d) {
            best_d = d;
            best = i;
        }
    }
    return best;
}

int32_t cp_move(cp_world* world, cp_body* body, float dt) {
    if (world == nullptr || !valid_body(body) || !std::isfinite(dt) || dt < 0.0f) {
        return fail(CP_ERR_ARG, "bad body or dt");
    }
    begin_call(world);
    CellReader cells(world);
    move_body(world, cells, *body, dt);
    return CP_OK;
}

int32_t cp_move_many(cp_world* world, cp_body* bodies, int32_t count, float dt) {
    if (world == nullptr || bodies == nullptr || count < 0 || !std::isfinite(dt) || dt < 0.0f) {
        fail(CP_ERR_ARG, "bad bodies or dt");
        return 0;
    }
    begin_call(world);
    CellReader cells(world);
    int32_t moved = 0;
    for (int32_t i = 0; i < count; ++i) {
        if (!valid_body(&bodies[i])) continue;
        move_body(world, cells, bodies[i], dt);
        ++moved;
    }
    return moved;
}

int32_t cp_fluid_measure(cp_world* world, double x, double feet_y, double z, float height, float half_x,
                         double flow_base_y, float flow_height, uint32_t mask, cp_fluid_sample* out) {
    if (world == nullptr || out == nullptr || !std::isfinite(x) || !std::isfinite(feet_y) || !std::isfinite(z)
        || !std::isfinite(height) || !std::isfinite(half_x) || !std::isfinite(flow_base_y)
        || !std::isfinite(flow_height)) {
        return fail(CP_ERR_ARG, "bad fluid measure");
    }
    begin_call(world);
    CellReader cells(world);
    measure(world, cells, x, feet_y, z, height, half_x, flow_base_y, flow_height, mask, *out);
    return CP_OK;
}

float cp_buoyancy_vy(float vy, float submersion, float dt, const cp_integrator* params) {
    if (params == nullptr || !(params->float_submersion > 0.0f)) return vy;
    return buoyancy_vy(vy, submersion, dt, *params);
}

int32_t cp_step(cp_world* world, cp_body* bodies, int32_t count, float dt, const cp_integrator* params,
                cp_fluid_sample* out_samples) {
    if (world == nullptr || bodies == nullptr || params == nullptr || count < 0 || !std::isfinite(dt) || dt < 0.0f) {
        fail(CP_ERR_ARG, "bad step");
        return 0;
    }
    begin_call(world);
    CellReader cells(world);
    int32_t stepped = 0;
    for (int32_t i = 0; i < count; ++i) {
        cp_body& b = bodies[i];
        if (!valid_body(&b)) continue;
        cp_fluid_sample sample{};
        // As the Java rule it replaces: feet = y - leg, height = height + leg (the feet in double: measure
        // rounds them to float relative to its anchor, which is the old float subtraction near 0).
        const double feet = b.y - static_cast<double>(b.below);
        const float height = b.above + b.below;
        measure(world, cells, b.x, feet, b.z, height, b.half_x, b.y, b.above, params->fluid_mask, sample);
        if (sample.submersion > 0.0f) {
            if (params->float_submersion > 0.0f) {
                b.vy = buoyancy_vy(b.vy, sample.submersion, dt, *params);
            }
            if (sample.flow_samples > 0) {
                b.vx += sample.flow_x * dt;
                b.vy += sample.flow_y * dt;
                b.vz += sample.flow_z * dt;
            }
        } else if ((b.flags & CP_BODY_ON_GROUND) == 0u) {
            b.vy += params->gravity * dt;
        }
        move_body(world, cells, b, dt);
        if ((b.flags & CP_BODY_ON_GROUND) != 0u && params->ground_friction > 0.0f) {
            float factor = static_cast<float>(std::exp(static_cast<double>(-params->ground_friction * dt)));
            b.vx *= factor;
            b.vz *= factor;
        }
        if (out_samples != nullptr) out_samples[i] = sample;
        ++stepped;
    }
    return stepped;
}

int32_t cp_raycast(cp_world* world, const double origin[3], const double direction[3], double max_distance,
                   uint32_t mask, uint32_t fluid_mask, cp_hit* out) {
    if (world == nullptr || origin == nullptr || direction == nullptr || out == nullptr || !finite3(origin)
        || !finite3(direction) || std::isnan(max_distance)) {
        if (out) clear_hit(out);
        fail(CP_ERR_ARG, "bad ray");
        return 0;
    }
    begin_call(world);
    return raycast(world, origin, direction, max_distance, mask, fluid_mask, out);
}

int32_t cp_sweep(cp_world* world, const double from[3], const double to[3], float hx, float hy, float hz,
                 uint32_t mask, cp_hit* out) {
    if (world == nullptr || from == nullptr || to == nullptr || out == nullptr || !finite3(from) || !finite3(to)
        || !(hx >= 0.0f) || !(hy >= 0.0f) || !(hz >= 0.0f)) {
        if (out) clear_hit(out);
        fail(CP_ERR_ARG, "bad sweep");
        return 0;
    }
    begin_call(world);
    clear_hit(out);
    CellReader cells(world);
    const double a[3] = {from[0], from[1], from[2]};
    const double total[3] = {to[0] - from[0], to[1] - from[1], to[2] - from[2]};
    const double h[3] = {hx, hy, hz};
    double length = std::sqrt(total[0] * total[0] + total[1] * total[1] + total[2] * total[2]);
    int pieces = std::max(1, static_cast<int>(std::ceil(length / kMaxSweepPiece)));
    std::vector<Collider> cs;
    for (int p = 0; p < pieces; ++p) {
        double f0 = static_cast<double>(p) / pieces;
        double f1 = static_cast<double>(p + 1) / pieces;
        double start[3] = {a[0] + total[0] * f0, a[1] + total[1] * f0, a[2] + total[2] * f0};
        double delta[3] = {total[0] * (f1 - f0), total[1] * (f1 - f0), total[2] * (f1 - f0)};
        double t;
        int32_t face;
        const Collider* c;
        if (sweep_piece(world, cells, start, delta, h, mask, &t, &face, &c, cs)) {
            double f = f0 + t * (f1 - f0);
            out->kind = CP_HIT_CELL;
            out->face = face;
            out->distance = f;
            out->px = a[0] + total[0] * f;
            out->py = a[1] + total[1] * f;
            out->pz = a[2] + total[2] * f;
            // The collider's cell: the cell of its centre (cell boxes) — for a dynamic box, the cell
            // holding the contact point.
            double cx = (c->box.min[0] + c->box.max[0]) * 0.5;
            double cy = (c->box.min[1] + c->box.max[1]) * 0.5;
            double cz = (c->box.min[2] + c->box.max[2]) * 0.5;
            out->x = floor_i(cx) + world->origin[0];
            out->y = floor_i(cy) + world->origin[1];
            out->z = floor_i(cz) + world->origin[2];
            return 1;
        }
    }
    return 0;
}

int32_t cp_ray_boxes(const double origin[3], const double direction[3], double max_t, const cp_aabb* boxes,
                     int32_t count, double margin, double* out_t) {
    if (origin == nullptr || direction == nullptr || boxes == nullptr || count <= 0) return -1;
    int32_t best = -1;
    double best_t = std::numeric_limits<double>::infinity();
    for (int32_t i = 0; i < count; ++i) {
        const cp_aabb& b = boxes[i];
        // Slab test, as the Java BoundingBox.rayIntersect it replaces (in double since ABI 2).
        double lo[3] = {b.min_x - margin, b.min_y - margin, b.min_z - margin};
        double hi[3] = {b.max_x + margin, b.max_y + margin, b.max_z + margin};
        double t_min = 0.0, t_max = std::numeric_limits<double>::max();
        bool miss = false;
        for (int a = 0; a < 3 && !miss; ++a) {
            if (std::fabs(direction[a]) < 1e-6) {
                if (origin[a] < lo[a] || origin[a] > hi[a]) miss = true;
                continue;
            }
            double t1 = (lo[a] - origin[a]) / direction[a];
            double t2 = (hi[a] - origin[a]) / direction[a];
            t_min = std::max(t_min, std::min(t1, t2));
            t_max = std::min(t_max, std::max(t1, t2));
            if (t_min > t_max) miss = true;
        }
        double t = miss ? std::numeric_limits<double>::infinity() : t_min;
        if (out_t != nullptr) out_t[i] = t;
        if (t <= max_t && t < best_t) {
            best_t = t;
            best = i;
        }
    }
    return best;
}

} // extern "C"

// ---- engine-internal access (physics_internal.hpp) --------------------------------------------

namespace cenda::physics_detail {

void begin(cp_world* world) {
    begin_call(world);
}

bool CellView::read(int64_t x, int64_t y, int64_t z, uint16_t& shape, uint16_t& fluid) {
    const int s = world_->shift;
    int64_t bx = x >> s, by = y >> s, bz = z >> s;
    Memo& memo = memo_[static_cast<size_t>((bx & 3) | ((bz & 3) << 2) | ((by & 1) << 4))];
    if (!memo.valid || memo.bx != bx || memo.by != by || memo.bz != bz) {
        memo.bx = bx;
        memo.by = by;
        memo.bz = bz;
        memo.brick = brick_at(world_, bx, by, bz);
        memo.valid = true;
    }
    const Brick* brick = static_cast<const Brick*>(memo.brick);
    if (brick == nullptr) {
        shape = world_->unloaded_shape;
        fluid = 0;
        return false;
    }
    const int m = world_->mask_local;
    const size_t ue = static_cast<size_t>(world_->edge);
    size_t i = (static_cast<size_t>(y & m) * ue + static_cast<size_t>(z & m)) * ue + static_cast<size_t>(x & m);
    shape = brick->shapes ? brick->shapes[i] : brick->uniform_shape;
    fluid = brick->fluids ? brick->fluids[i] : brick->uniform_fluid;
    return true;
}

void origin(const cp_world* world, int64_t out[3]) {
    out[0] = world->origin[0];
    out[1] = world->origin[1];
    out[2] = world->origin[2];
}

float shape_top(const cp_world* world, uint16_t shape, uint32_t mask) {
    const Shape* s = shape_of(world, shape);
    if (s == nullptr || (s->category & mask) == 0u) return 0.0f;
    float top = 0.0f;
    for (uint32_t i = 0; i < s->count; ++i) {
        // The whole cell [0,1]^2 overlaps every box (inclusive edges, as cp_cell_top).
        if (s->boxes[i].max_y > top) top = s->boxes[i].max_y;
    }
    return top;
}

uint32_t shape_category(const cp_world* world, uint16_t shape) {
    const Shape* s = shape_of(world, shape);
    return s == nullptr ? 0u : s->category;
}

float fluid_surface(const cp_world* world, uint16_t fluid, uint32_t mask) {
    const cp_fluid* f = fluid_of(world, fluid, mask);
    return f == nullptr ? -1.0f : f->surface;
}

} // namespace cenda::physics_detail
