// Cenda entities (ce_*): ids, lifecycle, live order, type descriptors, bodies + spatial queries
// and clip clocks for one world. Contract in include/cenda/entity.h (OpenMason #114).
//
// Layout notes:
//  - Slots live in fixed 256-entry pages behind a fixed page table, so a slot never moves:
//    ce_spawn (any thread) can allocate while the owner thread reads other slots.
//  - generation/state are atomics because ce_despawn (any thread) and handle validation race
//    with the owner; everything else in a slot is written once at spawn (published through the
//    world mutex and the owner's commit) and afterwards only by the owner thread.
//  - The spatial index is a sorted (cell, live index) array over a 3D grid of cell_size-block cells,
//    keyed by ABSOLUTE int64 cell triples (frame origin + local), sorted by (x, z, y) so a column's
//    cells are contiguous: a query with an unbounded Y range is still one binary search per column.
//    Rebuilt lazily before the next query whenever bodies, membership or the origin changed.
//  - Positions are doubles in the world's frame (relative to the origin); distances are double.

#include "cenda/entity.h"
#include "../int3_key.hpp"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <deque>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace {

thread_local std::string t_error;

int32_t fail(int32_t code, const char* message) {
    t_error = message;
    return code;
}

constexpr uint32_t kPageBits = 8;
constexpr uint32_t kPageSize = 1u << kPageBits;
constexpr uint32_t kMaxPages = 1u << 12; // 1M entities per world (the page table is 32 KiB)
constexpr uint32_t kMaxSlots = kPageSize * kMaxPages;

// Internal-only state: a PENDING entity despawned before its commit. Reported as NONE.
constexpr uint8_t kStateCancelled = 5;

struct Slot {
    std::atomic<uint32_t> generation{0};
    std::atomic<uint8_t> state{CE_STATE_NONE};
    int32_t type = -1;
    int32_t order = -1; // index in World::order while in the world
    ce_body body{};
    ce_clip_state clip{-1, 0u, 0.0f, 0.0f};
};

struct Page {
    Slot slots[kPageSize];
};

struct TypeEntry {
    bool defined = false;
    bool has_model = false;
    ce_type_desc desc{};
    std::string model;
};

ce_handle make_handle(uint32_t slot, uint32_t generation) {
    return (static_cast<uint64_t>(generation) << 32) | static_cast<uint64_t>(slot + 1u);
}

bool finite(float v) { return std::isfinite(v); }

// Local positions beyond this have no int64 cell (floor would overflow): they are "unplaced".
constexpr double kPlaceLimit = 4.0e18;

bool placeable(double v) { return std::isfinite(v) && std::fabs(v) < kPlaceLimit; }

// Saturating int64 add (origin + local cell).
int64_t add_sat(int64_t a, int64_t b) {
    if (b > 0 && a > INT64_MAX - b) return INT64_MAX;
    if (b < 0 && a < INT64_MIN - b) return INT64_MIN;
    return a + b;
}

// The absolute grid cell of a placeable local coordinate.
int64_t cell_of(double local, int64_t origin, int64_t cell) {
    return cenda::floor_div64(add_sat(origin, static_cast<int64_t>(std::floor(local))), cell);
}

// A grid cell, ordered (x, z, y): every cell of one column is contiguous.
struct GridCell {
    int64_t x, z, y;
    friend bool operator<(const GridCell& a, const GridCell& b) {
        if (a.x != b.x) return a.x < b.x;
        if (a.z != b.z) return a.z < b.z;
        return a.y < b.y;
    }
};

struct GridEntry {
    GridCell cell;
    uint32_t live_index;
    friend bool operator<(const GridEntry& a, const GridEntry& b) {
        if (a.cell < b.cell) return true;
        if (b.cell < a.cell) return false;
        return a.live_index < b.live_index;
    }
};

// Squared distance as the Java code this replaced wrote it (JOML Vector3f.distanceSquared without
// Math.fma): dx*dx + (dy*dy + dz*dz), in double since ABI 2. The library builds with
// -ffp-contract=off, so nothing here fuses into an FMA.
double distance_squared(const ce_body& b, double x, double y, double z) {
    double dx = b.x - x;
    double dy = b.y - y;
    double dz = b.z - z;
    return dx * dx + (dy * dy + dz * dz);
}

bool accepts(const ce_filter* filter, const Slot& s) {
    if (filter == nullptr) {
        return true;
    }
    if (filter->type_id >= 0 && s.type != filter->type_id) {
        return false;
    }
    if ((s.body.flags & filter->require_flags) != filter->require_flags) {
        return false;
    }
    if ((s.body.flags & filter->exclude_flags) != 0u) {
        return false;
    }
    return filter->exclude == 0 || s.body.handle != filter->exclude;
}

} // namespace

struct ce_world {
    int64_t cell_size;
    int64_t origin[3] = {0, 0, 0};

    std::unique_ptr<std::atomic<Page*>[]> pages;
    std::atomic<uint32_t> next_slot{0};

    // Guarded by mutex: everything any thread may touch at spawn/despawn time.
    mutable std::mutex mutex;
    std::vector<uint32_t> free_slots;
    std::deque<ce_handle> pending;
    std::deque<ce_handle> removing;
    std::vector<TypeEntry> types;

    // Owner thread only.
    std::vector<uint32_t> order;   // slots in the world, live order
    std::vector<uint32_t> retired; // slots retired by the last ce_commit_despawns

    // Spatial index (owner thread; rebuilt lazily, hence mutable for the const queries).
    mutable bool index_dirty = true;
    mutable std::vector<GridEntry> cells;   // (cell, live index), sorted
    mutable std::vector<uint32_t> unplaced; // live indices with no placeable x/y/z
    mutable float max_half_xz = 0.0f;
    mutable float max_reach_y = 0.0f;       // the most a body box reaches above or below its position
    mutable std::vector<uint32_t> scratch;

    explicit ce_world(int64_t cell) : cell_size(cell), pages(new std::atomic<Page*>[kMaxPages]) {
        for (uint32_t i = 0; i < kMaxPages; ++i) {
            pages[i].store(nullptr, std::memory_order_relaxed);
        }
    }

    ~ce_world() {
        for (uint32_t i = 0; i < kMaxPages; ++i) {
            delete pages[i].load(std::memory_order_relaxed);
        }
    }

    ce_world(const ce_world&) = delete;
    ce_world& operator=(const ce_world&) = delete;

    Slot* slot_at(uint32_t index) const {
        if (index >= next_slot.load(std::memory_order_acquire)) {
            return nullptr;
        }
        Page* page = pages[index >> kPageBits].load(std::memory_order_acquire);
        return page == nullptr ? nullptr : &page->slots[index & (kPageSize - 1u)];
    }

    // The slot a handle names, or null when the handle is stale/malformed.
    Slot* resolve(ce_handle handle) const {
        uint32_t low = static_cast<uint32_t>(handle & 0xFFFFFFFFull);
        if (low == 0u) {
            return nullptr;
        }
        Slot* s = slot_at(low - 1u);
        if (s == nullptr) {
            return nullptr;
        }
        uint32_t generation = static_cast<uint32_t>(handle >> 32);
        return s->generation.load(std::memory_order_acquire) == generation ? s : nullptr;
    }

    // Caller holds mutex. Returns the slot index or kMaxSlots when full.
    uint32_t allocate_slot() {
        if (!free_slots.empty()) {
            uint32_t index = free_slots.back();
            free_slots.pop_back();
            return index;
        }
        uint32_t index = next_slot.load(std::memory_order_relaxed);
        if (index >= kMaxSlots) {
            return kMaxSlots;
        }
        uint32_t page = index >> kPageBits;
        if (pages[page].load(std::memory_order_relaxed) == nullptr) {
            pages[page].store(new Page(), std::memory_order_release);
        }
        next_slot.store(index + 1u, std::memory_order_release);
        return index;
    }

    // Owner thread, after the order changed: fix every slot's order index from `from` on.
    void reindex(size_t from) {
        for (size_t i = from; i < order.size(); ++i) {
            slot_at(order[i])->order = static_cast<int32_t>(i);
        }
        index_dirty = true;
    }

    void rebuild_index() const {
        cells.clear();
        unplaced.clear();
        max_half_xz = 0.0f;
        max_reach_y = 0.0f;
        for (size_t i = 0; i < order.size(); ++i) {
            const Slot* s = slot_at(order[i]);
            const ce_body& b = s->body;
            float half = std::max(std::fabs(b.width), std::fabs(b.length)) * 0.5f;
            if (finite(half)) {
                max_half_xz = std::max(max_half_xz, half);
            }
            float reach = std::max(std::fabs(b.height), std::fabs(b.leg_height));
            if (finite(reach)) {
                max_reach_y = std::max(max_reach_y, reach);
            }
            uint32_t live_index = static_cast<uint32_t>(i);
            if (placeable(b.x) && placeable(b.y) && placeable(b.z)) {
                cells.push_back(GridEntry{GridCell{cell_of(b.x, origin[0], cell_size), cell_of(b.z, origin[2], cell_size),
                                                   cell_of(b.y, origin[1], cell_size)},
                                          live_index});
            } else {
                unplaced.push_back(live_index);
            }
        }
        std::sort(cells.begin(), cells.end());
        index_dirty = false;
    }

    // Live indices of every entity whose position may lie in [x0,x1] x [y0,y1] x [z0,z1] (local,
    // padded by one cell against rounding; y may be infinite), plus every unplaced entity,
    // unsorted, into scratch. Returns false when the region spans more columns than there are
    // entities, or is unbounded on x/z (scan instead).
    bool gather(double x0, double y0, double z0, double x1, double y1, double z1) const {
        if (index_dirty) {
            rebuild_index();
        }
        scratch.clear();
        if (!(placeable(x0) && placeable(z0) && placeable(x1) && placeable(z1)) || std::isnan(y0) || std::isnan(y1)) {
            return false;
        }
        const int64_t cx0 = cell_of(x0, origin[0], cell_size) - 1, cx1 = cell_of(x1, origin[0], cell_size) + 1;
        const int64_t cz0 = cell_of(z0, origin[2], cell_size) - 1, cz1 = cell_of(z1, origin[2], cell_size) + 1;
        const int64_t cy0 = placeable(y0) ? cell_of(y0, origin[1], cell_size) - 1 : INT64_MIN;
        const int64_t cy1 = placeable(y1) ? cell_of(y1, origin[1], cell_size) + 1 : INT64_MAX;
        const double span = (static_cast<double>(cx1) - static_cast<double>(cx0) + 1.0)
            * (static_cast<double>(cz1) - static_cast<double>(cz0) + 1.0);
        if (span > static_cast<double>(order.size()) + 16.0) {
            return false;
        }
        for (int64_t cx = cx0; cx <= cx1; ++cx) {
            for (int64_t cz = cz0; cz <= cz1; ++cz) {
                auto it = std::lower_bound(cells.begin(), cells.end(), GridEntry{GridCell{cx, cz, cy0}, 0u});
                for (; it != cells.end() && it->cell.x == cx && it->cell.z == cz && it->cell.y <= cy1; ++it) {
                    scratch.push_back(it->live_index);
                }
            }
        }
        scratch.insert(scratch.end(), unplaced.begin(), unplaced.end());
        return true;
    }

    // Every live index, in order, into scratch.
    void gather_all() const {
        scratch.resize(order.size());
        for (size_t i = 0; i < order.size(); ++i) {
            scratch[i] = static_cast<uint32_t>(i);
        }
    }

    // Filters scratch (live indices) by pred, sorts into live order, writes up to cap handles.
    template <typename Pred>
    int32_t answer(const ce_filter* filter, ce_handle* out, int32_t cap, Pred pred) const {
        std::sort(scratch.begin(), scratch.end());
        int32_t total = 0;
        for (uint32_t live_index : scratch) {
            const Slot& s = *slot_at(order[live_index]);
            if (!accepts(filter, s) || !pred(s.body)) {
                continue;
            }
            if (out != nullptr && total < cap) {
                out[total] = s.body.handle;
            }
            ++total;
        }
        return total;
    }
};

extern "C" {

int32_t ce_abi_version(void) { return CE_ABI_VERSION; }

const char* ce_last_error(void) { return t_error.c_str(); }

ce_world* ce_world_create(int32_t cell_size) {
    int64_t cell = cell_size > 0 ? cell_size : 8;
    try {
        return new ce_world(cell);
    } catch (...) {
        fail(CE_ERR_ARG, "ce_world_create: out of memory");
        return nullptr;
    }
}

void ce_world_destroy(ce_world* world) { delete world; }

int32_t ce_set_origin(ce_world* world, int64_t ox, int64_t oy, int64_t oz) {
    if (world == nullptr) {
        return fail(CE_ERR_ARG, "ce_set_origin: null world");
    }
    if (world->origin[0] == ox && world->origin[1] == oy && world->origin[2] == oz) {
        return CE_OK;
    }
    const double shift[3] = {static_cast<double>(world->origin[0] - ox), static_cast<double>(world->origin[1] - oy),
                             static_cast<double>(world->origin[2] - oz)};
    // Every body a query or ce_body_get can still see (pending ones too) keeps its absolute place.
    std::lock_guard<std::mutex> lock(world->mutex);
    const uint32_t n = world->next_slot.load(std::memory_order_acquire);
    for (uint32_t i = 0; i < n; ++i) {
        Slot* s = world->slot_at(i);
        if (s == nullptr) {
            continue;
        }
        uint8_t state = s->state.load(std::memory_order_relaxed);
        if (state != CE_STATE_PENDING && state != CE_STATE_LIVE && state != CE_STATE_REMOVING
            && state != CE_STATE_RETIRED) {
            continue;
        }
        s->body.x += shift[0];
        s->body.y += shift[1];
        s->body.z += shift[2];
    }
    world->origin[0] = ox;
    world->origin[1] = oy;
    world->origin[2] = oz;
    world->index_dirty = true;
    return CE_OK;
}

int32_t ce_get_origin(const ce_world* world, int64_t* out) {
    if (world == nullptr || out == nullptr) {
        return fail(CE_ERR_ARG, "ce_get_origin: null world or output");
    }
    out[0] = world->origin[0];
    out[1] = world->origin[1];
    out[2] = world->origin[2];
    return CE_OK;
}

// ---- types ------------------------------------------------------------------------------------

int32_t ce_type_define(ce_world* world, int32_t type_id, const ce_type_desc* desc, const char* model_id) {
    if (world == nullptr || desc == nullptr) {
        return fail(CE_ERR_ARG, "ce_type_define: null world or descriptor");
    }
    if (type_id < 0 || type_id >= CE_MAX_TYPES) {
        return fail(CE_ERR_ARG, "ce_type_define: type id out of range");
    }
    std::lock_guard<std::mutex> lock(world->mutex);
    size_t index = static_cast<size_t>(type_id);
    if (world->types.size() <= index) {
        world->types.resize(index + 1u);
    }
    TypeEntry& entry = world->types[index];
    entry.defined = true;
    entry.desc = *desc;
    entry.has_model = model_id != nullptr;
    entry.model = model_id != nullptr ? std::string(model_id) : std::string();
    return CE_OK;
}

int32_t ce_type_get(const ce_world* world, int32_t type_id, ce_type_desc* out) {
    if (world == nullptr || type_id < 0 || static_cast<size_t>(type_id) >= world->types.size()) {
        return 0;
    }
    const TypeEntry& entry = world->types[static_cast<size_t>(type_id)];
    if (!entry.defined) {
        return 0;
    }
    if (out != nullptr) {
        *out = entry.desc;
    }
    return 1;
}

const char* ce_type_model_id(const ce_world* world, int32_t type_id) {
    if (world == nullptr || type_id < 0 || static_cast<size_t>(type_id) >= world->types.size()) {
        return nullptr;
    }
    const TypeEntry& entry = world->types[static_cast<size_t>(type_id)];
    return entry.defined && entry.has_model ? entry.model.c_str() : nullptr;
}

// ---- lifecycle --------------------------------------------------------------------------------

ce_handle ce_spawn(ce_world* world, int32_t type_id, uint32_t flags, double x, double y, double z, float yaw) {
    if (world == nullptr) {
        fail(CE_ERR_ARG, "ce_spawn: null world");
        return 0;
    }
    std::lock_guard<std::mutex> lock(world->mutex);
    if (type_id < 0 || static_cast<size_t>(type_id) >= world->types.size()
        || !world->types[static_cast<size_t>(type_id)].defined) {
        fail(CE_ERR_ARG, "ce_spawn: undefined type");
        return 0;
    }
    uint32_t index = world->allocate_slot();
    if (index == kMaxSlots) {
        fail(CE_ERR_ARG, "ce_spawn: world is full");
        return 0;
    }
    Slot* s = world->slot_at(index);
    uint32_t generation = s->generation.load(std::memory_order_relaxed) + 1u;
    if (generation == 0u) {
        generation = 1u;
    }
    const ce_type_desc& desc = world->types[static_cast<size_t>(type_id)].desc;
    ce_handle handle = make_handle(index, generation);
    s->type = type_id;
    s->order = -1;
    s->body = ce_body{handle, x, y, z, yaw, desc.width, desc.height, desc.length, desc.leg_height, flags, {0u, 0u}};
    s->clip = ce_clip_state{-1, 0u, 0.0f, 0.0f};
    s->state.store(CE_STATE_PENDING, std::memory_order_relaxed);
    s->generation.store(generation, std::memory_order_release);
    world->pending.push_back(handle);
    return handle;
}

int32_t ce_despawn(ce_world* world, ce_handle handle) {
    if (world == nullptr) {
        return fail(CE_ERR_ARG, "ce_despawn: null world");
    }
    std::lock_guard<std::mutex> lock(world->mutex);
    Slot* s = world->resolve(handle);
    if (s == nullptr) {
        return 0;
    }
    uint8_t state = s->state.load(std::memory_order_relaxed);
    if (state == CE_STATE_PENDING) {
        s->state.store(kStateCancelled, std::memory_order_relaxed);
        return 1;
    }
    if (state == CE_STATE_LIVE) {
        s->state.store(CE_STATE_REMOVING, std::memory_order_relaxed);
        world->removing.push_back(handle);
        return 1;
    }
    return 0;
}

int32_t ce_commit_spawns(ce_world* world, ce_handle* out, int32_t cap) {
    if (world == nullptr || cap < 0 || (cap > 0 && out == nullptr)) {
        return fail(CE_ERR_ARG, "ce_commit_spawns: bad arguments");
    }
    int32_t committed = 0;
    size_t first_new = world->order.size();
    {
        std::lock_guard<std::mutex> lock(world->mutex);
        while (committed < cap && !world->pending.empty()) {
            ce_handle handle = world->pending.front();
            world->pending.pop_front();
            Slot* s = world->resolve(handle);
            if (s == nullptr) {
                continue;
            }
            uint32_t index = static_cast<uint32_t>(handle & 0xFFFFFFFFull) - 1u;
            if (s->state.load(std::memory_order_relaxed) == kStateCancelled) {
                s->state.store(CE_STATE_NONE, std::memory_order_relaxed);
                world->free_slots.push_back(index);
                continue;
            }
            s->state.store(CE_STATE_LIVE, std::memory_order_relaxed);
            world->order.push_back(index);
            out[committed++] = handle;
        }
        // Cancelled entries at the head would otherwise wait for the next spawn to be skipped.
        while (!world->pending.empty()) {
            Slot* s = world->resolve(world->pending.front());
            if (s != nullptr && s->state.load(std::memory_order_relaxed) != kStateCancelled) {
                break;
            }
            if (s != nullptr) {
                s->state.store(CE_STATE_NONE, std::memory_order_relaxed);
                world->free_slots.push_back(static_cast<uint32_t>(world->pending.front() & 0xFFFFFFFFull) - 1u);
            }
            world->pending.pop_front();
        }
    }
    if (world->order.size() != first_new) {
        world->reindex(first_new);
    }
    return committed;
}

int32_t ce_commit_despawns(ce_world* world, ce_handle* out, int32_t cap) {
    if (world == nullptr || cap < 0 || (cap > 0 && out == nullptr)) {
        return fail(CE_ERR_ARG, "ce_commit_despawns: bad arguments");
    }
    std::vector<uint32_t> leaving;
    {
        std::lock_guard<std::mutex> lock(world->mutex);
        for (uint32_t index : world->retired) {
            world->slot_at(index)->state.store(CE_STATE_NONE, std::memory_order_relaxed);
            world->free_slots.push_back(index);
        }
        world->retired.clear();
        int32_t taken = 0;
        while (taken < cap && !world->removing.empty()) {
            ce_handle handle = world->removing.front();
            world->removing.pop_front();
            Slot* s = world->resolve(handle);
            if (s == nullptr || s->state.load(std::memory_order_relaxed) != CE_STATE_REMOVING) {
                continue;
            }
            s->state.store(CE_STATE_RETIRED, std::memory_order_relaxed);
            uint32_t index = static_cast<uint32_t>(handle & 0xFFFFFFFFull) - 1u;
            leaving.push_back(index);
            world->retired.push_back(index);
            out[taken++] = handle;
        }
    }
    if (leaving.empty()) {
        return 0;
    }
    // Stable compaction of the live order, one pass.
    size_t first = world->order.size();
    for (uint32_t index : leaving) {
        Slot* s = world->slot_at(index);
        first = std::min(first, static_cast<size_t>(s->order));
        s->order = -1;
    }
    size_t write = first;
    for (size_t read = first; read < world->order.size(); ++read) {
        uint32_t index = world->order[read];
        if (world->slot_at(index)->state.load(std::memory_order_relaxed) == CE_STATE_RETIRED
            && world->slot_at(index)->order == -1) {
            continue;
        }
        world->order[write++] = index;
    }
    world->order.resize(write);
    world->reindex(first);
    return static_cast<int32_t>(leaving.size());
}

int32_t ce_state(const ce_world* world, ce_handle handle) {
    if (world == nullptr) {
        return CE_STATE_NONE;
    }
    const Slot* s = world->resolve(handle);
    if (s == nullptr) {
        return CE_STATE_NONE;
    }
    uint8_t state = s->state.load(std::memory_order_relaxed);
    return state == kStateCancelled ? static_cast<int32_t>(CE_STATE_NONE) : static_cast<int32_t>(state);
}

int32_t ce_type_of(const ce_world* world, ce_handle handle) {
    return ce_state(world, handle) == CE_STATE_NONE ? -1 : world->resolve(handle)->type;
}

int32_t ce_count(const ce_world* world, int32_t which) {
    if (world == nullptr) {
        return 0;
    }
    switch (which) {
    case CE_COUNT_LIVE:
        return static_cast<int32_t>(world->order.size());
    case CE_COUNT_PENDING: {
        std::lock_guard<std::mutex> lock(world->mutex);
        int32_t n = 0;
        for (ce_handle h : world->pending) {
            const Slot* s = world->resolve(h);
            if (s != nullptr && s->state.load(std::memory_order_relaxed) == CE_STATE_PENDING) {
                ++n;
            }
        }
        return n;
    }
    case CE_COUNT_REMOVING: {
        std::lock_guard<std::mutex> lock(world->mutex);
        return static_cast<int32_t>(world->removing.size());
    }
    default:
        return 0;
    }
}

int32_t ce_live(const ce_world* world, ce_handle* out, int32_t cap) {
    if (world == nullptr) {
        return 0;
    }
    int32_t total = static_cast<int32_t>(world->order.size());
    if (out != nullptr) {
        int32_t n = std::min(total, cap);
        for (int32_t i = 0; i < n; ++i) {
            out[i] = world->slot_at(world->order[static_cast<size_t>(i)])->body.handle;
        }
    }
    return total;
}

int32_t ce_pending(const ce_world* world, ce_handle* out, int32_t cap) {
    if (world == nullptr) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(world->mutex);
    int32_t total = 0;
    for (ce_handle h : world->pending) {
        const Slot* s = world->resolve(h);
        if (s == nullptr || s->state.load(std::memory_order_relaxed) != CE_STATE_PENDING) {
            continue;
        }
        if (out != nullptr && total < cap) {
            out[total] = h;
        }
        ++total;
    }
    return total;
}

// ---- bodies + queries -------------------------------------------------------------------------

int32_t ce_sync_bodies(ce_world* world, const ce_body* bodies, int32_t count) {
    if (world == nullptr || count < 0 || (count > 0 && bodies == nullptr)) {
        return fail(CE_ERR_ARG, "ce_sync_bodies: bad arguments");
    }
    int32_t applied = 0;
    for (int32_t i = 0; i < count; ++i) {
        const ce_body& in = bodies[i];
        Slot* s = world->resolve(in.handle);
        if (s == nullptr) {
            continue;
        }
        uint8_t state = s->state.load(std::memory_order_relaxed);
        if (state != CE_STATE_PENDING && state != CE_STATE_LIVE && state != CE_STATE_REMOVING) {
            continue;
        }
        s->body = in;
        s->body.reserved[0] = 0u;
        s->body.reserved[1] = 0u;
        ++applied;
    }
    world->index_dirty = true;
    return applied;
}

int32_t ce_body_get(const ce_world* world, ce_handle handle, ce_body* out) {
    int32_t state = ce_state(world, handle);
    if (state == CE_STATE_NONE || state == CE_STATE_RETIRED) {
        return 0;
    }
    if (out != nullptr) {
        *out = world->resolve(handle)->body;
    }
    return 1;
}

int32_t ce_query_radius(const ce_world* world, double x, double y, double z, double r,
                        const ce_filter* filter, ce_handle* out, int32_t cap) {
    if (world == nullptr || cap < 0 || std::isnan(r)) {
        return 0;
    }
    double r2 = r * r;
    if (!world->gather(x - r, y - r, z - r, x + r, y + r, z + r)) {
        world->gather_all();
    }
    return world->answer(filter, out, cap,
                         [&](const ce_body& b) { return distance_squared(b, x, y, z) <= r2; });
}

ce_handle ce_query_nearest(const ce_world* world, double x, double y, double z, double max_r,
                           const ce_filter* filter, double* out_distance) {
    if (world == nullptr || std::isnan(max_r)) {
        return 0;
    }
    // A linear pass in live order: the first strictly nearer entity wins, so ties go to the
    // entity that entered the world first, exactly as the Java scan did.
    double best = std::numeric_limits<double>::max();
    ce_handle best_handle = 0;
    for (uint32_t index : world->order) {
        const Slot& s = *world->slot_at(index);
        if (!accepts(filter, s)) {
            continue;
        }
        double d = std::sqrt(distance_squared(s.body, x, y, z));
        if (d < best && d <= max_r) {
            best = d;
            best_handle = s.body.handle;
        }
    }
    if (out_distance != nullptr) {
        *out_distance = best_handle != 0 ? best : std::numeric_limits<double>::infinity();
    }
    return best_handle;
}

int32_t ce_query_box(const ce_world* world, const double box[6], const ce_filter* filter,
                     ce_handle* out, int32_t cap) {
    if (world == nullptr || box == nullptr || cap < 0) {
        return 0;
    }
    bool test[3];
    for (int axis = 0; axis < 3; ++axis) {
        test[axis] = !(std::isinf(box[axis]) && box[axis] < 0.0 && std::isinf(box[axis + 3]) && box[axis + 3] > 0.0);
    }
    // An untested y axis gathers whole columns (the grid answers an unbounded y range per column).
    const double y0 = test[1] ? box[1] : -std::numeric_limits<double>::infinity();
    const double y1 = test[1] ? box[4] : std::numeric_limits<double>::infinity();
    if (!world->gather(box[0], y0, box[2], box[3], y1, box[5])) {
        world->gather_all();
    }
    return world->answer(filter, out, cap, [&](const ce_body& b) {
        double p[3] = {b.x, b.y, b.z};
        for (int axis = 0; axis < 3; ++axis) {
            if (test[axis] && !(p[axis] >= box[axis] && p[axis] < box[axis + 3])) {
                return false;
            }
        }
        return true;
    });
}

int32_t ce_query_aabb(const ce_world* world, const double box[6], const ce_filter* filter,
                      ce_handle* out, int32_t cap) {
    if (world == nullptr || box == nullptr || cap < 0) {
        return 0;
    }
    if (world->index_dirty) {
        world->rebuild_index();
    }
    const double pad = static_cast<double>(world->max_half_xz);
    const double pad_y = static_cast<double>(world->max_reach_y);
    if (!world->gather(box[0] - pad, box[1] - pad_y, box[2] - pad, box[3] + pad, box[4] + pad_y, box[5] + pad)) {
        world->gather_all();
    }
    return world->answer(filter, out, cap, [&](const ce_body& b) {
        // As the Java BoundingBox (half sizes as w / 2), in double since ABI 2.
        double min_x = b.x - static_cast<double>(b.width) / 2.0;
        double max_x = b.x + static_cast<double>(b.width) / 2.0;
        double min_y = b.y - static_cast<double>(b.leg_height);
        double max_y = b.y + static_cast<double>(b.height);
        double min_z = b.z - static_cast<double>(b.length) / 2.0;
        double max_z = b.z + static_cast<double>(b.length) / 2.0;
        return min_x < box[3] && max_x > box[0] && min_y < box[4] && max_y > box[1]
               && min_z < box[5] && max_z > box[2];
    });
}

int32_t ce_query_all(const ce_world* world, const ce_filter* filter, ce_handle* out, int32_t cap) {
    if (world == nullptr || cap < 0) {
        return 0;
    }
    world->gather_all();
    return world->answer(filter, out, cap, [](const ce_body&) { return true; });
}

// ---- clip clocks ------------------------------------------------------------------------------

namespace {

ce_clip_state* clip_of(const ce_world* world, ce_handle handle) {
    if (world == nullptr) {
        return nullptr;
    }
    Slot* s = world->resolve(handle);
    if (s == nullptr) {
        return nullptr;
    }
    uint8_t state = s->state.load(std::memory_order_relaxed);
    if (state < CE_STATE_PENDING || state > CE_STATE_RETIRED) {
        return nullptr;
    }
    return &s->clip;
}

} // namespace

int32_t ce_clip_set(ce_world* world, ce_handle handle, int32_t clip, uint32_t flags) {
    ce_clip_state* c = clip_of(world, handle);
    if (c == nullptr) {
        return fail(CE_ERR_STALE, "ce_clip_set: stale handle");
    }
    if (c->clip != clip) {
        c->clip = clip;
        c->state_time = 0.0f;
    }
    c->flags = flags;
    return CE_OK;
}

int32_t ce_clip_advance(ce_world* world, ce_handle handle, float dt_total, float dt_state) {
    ce_clip_state* c = clip_of(world, handle);
    if (c == nullptr) {
        return fail(CE_ERR_STALE, "ce_clip_advance: stale handle");
    }
    c->total_time += dt_total;
    c->state_time += dt_state;
    return CE_OK;
}

float ce_clip_time(const ce_world* world, ce_handle handle) {
    const ce_clip_state* c = clip_of(world, handle);
    if (c == nullptr) {
        return std::numeric_limits<float>::quiet_NaN();
    }
    return (c->flags & CE_CLIP_ONE_SHOT) != 0u ? c->state_time : c->total_time;
}

int32_t ce_clip_get(const ce_world* world, ce_handle handle, ce_clip_state* out) {
    const ce_clip_state* c = clip_of(world, handle);
    if (c == nullptr) {
        return fail(CE_ERR_STALE, "ce_clip_get: stale handle");
    }
    if (out != nullptr) {
        *out = *c;
    }
    return CE_OK;
}

int32_t ce_clip_put(ce_world* world, ce_handle handle, const ce_clip_state* state) {
    ce_clip_state* c = clip_of(world, handle);
    if (c == nullptr || state == nullptr) {
        return fail(CE_ERR_STALE, "ce_clip_put: stale handle");
    }
    *c = *state;
    return CE_OK;
}

} // extern "C"
