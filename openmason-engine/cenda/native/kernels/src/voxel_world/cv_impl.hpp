// Private structures of the voxel world core (cv_world.cpp, cv_surface.cpp, cv_internal.cpp). Other
// subsystems use cv_internal.hpp, never this file.
//
// LOCK ORDER (never acquire against it):
//   cube-map shard -> cube -> column-map shard -> column -> {dirty mutex, ephemeral-cache mutex}
// - A cube lock is taken while holding its map shard (shared) and the shard is then released; the
//   remover erases under the shard (exclusive), releases it, then takes the cube lock to drain users.
// - Never take a cube-map shard or a second cube lock while holding a cube lock.
// - The host surface callback runs with no lock held.
#pragma once

#include "cenda/voxel_world.h"
#include "cv_section.hpp"
#include "cv_sync.hpp"
#include "../int3_key.hpp"

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace cenda::voxel {

// "No such block" in tracker values (leaf/group/callback entries). A block at y = INT64_MIN cannot be
// represented as a surface (documented limit of unbounded worlds).
constexpr int64_t kNone = INT64_MIN;
constexpr int kChannels = 2;     // tracker channels
constexpr int kSky = 0;          // topmost CV_BLOCK_SKY_OPAQUE
constexpr int kTop = 1;          // topmost non-air (occupancy)
constexpr uint32_t kColumnCells = 1024;

int32_t fail(int32_t code, const char* message);

struct BlockTable {
    uint16_t flags[65536];
};

inline uint32_t section_of(int lx, int ly, int lz) {
    return static_cast<uint32_t>((((ly >> 4) * 2 + (lz >> 4)) * 2) + (lx >> 4));
}
inline uint32_t section_local(int lx, int ly, int lz) {
    return static_cast<uint32_t>((((ly & 15) * 16 + (lz & 15)) * 16) + (lx & 15));
}

// 8 sections of one channel (blocks or a layer).
Section* new_sections(uint16_t fill);
void delete_sections(Section* sec);
// One 32-cell row (ly, lz) of a channel; sec == null means every cell is `uniform`.
void channel_row(const Section* sec, uint16_t uniform, int ly, int lz, uint16_t* out32);
// Whole channel in CV_CELL_INDEX order.
void read_channel(const Section* sec, uint16_t uniform, uint16_t* out32768);
// Cube-local box of a channel into dst[(y-y0)*stride_y + (z-z0)*stride_z + (x-x0)].
void copy_channel_box(const Section* sec, uint16_t uniform, int x0, int y0, int z0, int sx, int sy, int sz,
                      uint16_t* dst, ptrdiff_t stride_z, ptrdiff_t stride_y);
inline uint16_t channel_get(const Section* sec, uint16_t uniform, int lx, int ly, int lz) {
    return sec == nullptr ? uniform : sec[section_of(lx, ly, lz)].get(section_local(lx, ly, lz));
}

struct CubeExtra {
    Section* layer[CV_MAX_LAYERS] = {};   // null: the layer is 0 in every cell
    std::vector<uint8_t> blob;
    CubeExtra() = default;
    CubeExtra(const CubeExtra&) = delete;
    CubeExtra& operator=(const CubeExtra&) = delete;
    ~CubeExtra();
    bool empty() const;
    size_t heap_bytes() const;
};

enum : uint8_t { kOccFull = 1, kOpqFull = 2, kCompactHint = 4 };

struct Cube {
    RwLock lock;
    uint16_t uniform_id = 0;     // the block of every cell when sec == null
    uint8_t flags = 0;           // kOccFull/kOpqFull: value of a mask whose array is null
    uint8_t reserved = 0;
    int64_t revision = 0;
    Section* sec = nullptr;      // 8 block sections, null when the cube is one block
    uint32_t* occ = nullptr;     // 1024 rows or null (uniform, see flags)
    uint32_t* opq = nullptr;
    CubeExtra* extra = nullptr;  // layers + blob, null when all layers are 0 and the blob empty
    // Mesher-owned visibility cache (mesh_core_access.cpp, Phase 1b): bits 0..14 the 15 face-pair bits,
    // bit 15 valid, bits 16..31 block-table generation, bits 32..63 the low 32 bits of `revision` it was
    // computed at. Written under a SHARED lock by any reader (atomic), never read by the core.
    std::atomic<uint64_t> vis_cache{0};

    Cube() = default;
    Cube(const Cube&) = delete;
    Cube& operator=(const Cube&) = delete;
    ~Cube();

    uint16_t get(int lx, int ly, int lz) const { return channel_get(sec, uniform_id, lx, ly, lz); }
    uint32_t occ_row(uint32_t r) const { return occ != nullptr ? occ[r] : ((flags & kOccFull) != 0 ? ~0u : 0u); }
    uint32_t opq_row(uint32_t r) const { return opq != nullptr ? opq[r] : ((flags & kOpqFull) != 0 ? ~0u : 0u); }
    const Section* layer(int32_t l) const { return extra != nullptr ? extra->layer[l] : nullptr; }
    size_t heap_bytes() const;
};

// ---- compressed int64[1024] (tracker leaves, group summaries, callback values) ---------------------
class Packed {
public:
    enum Kind : uint8_t { kNoneKind = 0, kUniform = 1, kW4 = 2, kW8 = 3, kW16 = 4, kW32 = 5, kW64 = 6 };

    int64_t get(uint32_t i) const;
    // Canonical: equal values always give an equal representation (kind, base, bytes).
    void assign(const int64_t* v);
    void set(uint32_t i, int64_t v);
    void decode(int64_t* out) const;
    bool none() const { return kind_ == kNoneKind; }
    // Same representation (implies same values; set() may leave a non-canonical form, so false does not
    // imply different values).
    bool same(const Packed& o) const;
    void copy_from(const Packed& o);
    size_t heap_bytes() const;
    void serialize(std::vector<uint8_t>& out) const;
    // Validates and reads one Packed; false on malformed input.
    static bool deserialize(const uint8_t*& p, const uint8_t* end, Packed& out);

private:
    int64_t base_ = 0;
    std::unique_ptr<uint8_t[]> data_;
    Kind kind_ = kNoneKind;
    static uint32_t width(Kind k);
    uint64_t raw(uint32_t i) const;
    void put(uint32_t i, uint64_t v);
};

// Masks + tracker leaves derived from block content (one pass over the rows).
struct Derived {
    uint32_t* occ = nullptr;     // owned until moved into a cube
    uint32_t* opq = nullptr;
    uint8_t flags = 0;
    bool packed = false;         // leaf_packed[] = Packed::assign(leaf[]) (prepare_cube does it off-thread)
    const BlockTable* table = nullptr;
    int64_t leaf[kChannels][kColumnCells];
    Packed leaf_packed[kChannels];
    Derived() = default;
    Derived(const Derived&) = delete;
    Derived& operator=(const Derived&) = delete;
    ~Derived();
};
// Clears `packed`; pack_leaves() sets it.
void derive(const Section* sec, uint16_t uniform_id, const BlockTable* table, uint16_t air, int64_t cy,
            Derived& out);
void pack_leaves(Derived& d);

// Never-generated surface from the host callback (immutable once built).
struct SurfaceCache {
    bool known = false;
    Packed v[kChannels];        // raw callback values (kNone when unknown)
    Packed answer[kChannels];   // what a column with no record answers (bounds applied)
    int64_t amin[kChannels] = {}, amax[kChannels] = {};
};

// 16 consecutive cube leaves of one channel + their summary (top value per (x, z) over the group).
struct Group {
    int64_t key = 0;   // cy >> 4
    Packed summary;
    Packed leaf[16];
    size_t heap_bytes() const;
};

struct Tracker {
    std::vector<std::unique_ptr<Group>> groups;   // descending key

    int64_t leaf_get(int64_t cy, uint32_t i) const;
    void leaf_set_value(int64_t cy, uint32_t i, int64_t v);
    // v = the cube's leaf values; pre (may be null) = Packed::assign(v), moved from (saves the packing).
    void leaf_set(int64_t cy, const int64_t* v, Packed* pre = nullptr);
    int64_t top_generated(uint32_t i) const;
    Group* find(int64_t key) const;
    Group* find_or_create(int64_t key);
    void drop_if_empty(int64_t key);
    size_t heap_bytes() const;
};

struct Range {
    int64_t lo, hi;   // inclusive cube y
};

struct Column {
    RwLock lock;
    std::vector<int64_t> loaded;      // present cubes' cy, ascending
    std::vector<Range> generated;     // ascending, disjoint, non-adjacent
    Tracker ch[kChannels];
    std::shared_ptr<const SurfaceCache> cb;   // null until fetched
    // The resolved answer per channel and cell (tracker + never-generated callback space + bounds),
    // kept current on every change so queries and the known-air test are O(1); min/max per channel.
    Packed answer[kChannels];
    int64_t amin[kChannels] = {}, amax[kChannels] = {};

    void set_answer(int c, uint32_t i, int64_t v);   // keeps amin/amax exact
    void answer_bounds(int c);                       // recomputes amin/amax from answer[ch]

    bool is_generated(int64_t cy) const;
    void mark_generated(int64_t cy);
    void add_loaded(int64_t cy);
    void remove_loaded(int64_t cy);
    size_t heap_bytes() const;
};

// ---- sharded open-addressing map of full int64 triples ----------------------------------------------
template <class T>
class ShardedMap {
public:
    static constexpr int kShardBits = 8;
    static constexpr size_t kShards = size_t{1} << kShardBits;
    struct Slot {
        int64_t x, y, z;
        T* v;
    };
    struct alignas(64) Shard {
        ShardLock lock;
        std::vector<Slot> slots;
        size_t count = 0;
    };

    Shard& shard(uint64_t h) { return shards_[h >> (64 - kShardBits)]; }

    static T* find(const Shard& s, int64_t x, int64_t y, int64_t z, uint64_t h) {
        if (s.slots.empty()) return nullptr;
        const size_t mask = s.slots.size() - 1;
        for (size_t i = static_cast<size_t>(h) & mask;; i = (i + 1) & mask) {
            const Slot& e = s.slots[i];
            if (e.v == nullptr) return nullptr;
            if (e.x == x && e.y == y && e.z == z) return e.v;
        }
    }

    // Caller holds the shard exclusively and checked absence.
    void insert(Shard& s, int64_t x, int64_t y, int64_t z, uint64_t h, T* v) {
        if ((s.count + 1) * 2 > s.slots.size()) grow(s);
        const size_t mask = s.slots.size() - 1;
        size_t i = static_cast<size_t>(h) & mask;
        while (s.slots[i].v != nullptr) i = (i + 1) & mask;
        s.slots[i] = Slot{x, y, z, v};
        ++s.count;
        size.fetch_add(1, std::memory_order_relaxed);
    }

    // Caller holds the shard exclusively. Backward-shift deletion (no tombstones).
    T* erase(Shard& s, int64_t x, int64_t y, int64_t z, uint64_t h) {
        if (s.slots.empty()) return nullptr;
        const size_t mask = s.slots.size() - 1;
        size_t i = static_cast<size_t>(h) & mask;
        for (;; i = (i + 1) & mask) {
            const Slot& e = s.slots[i];
            if (e.v == nullptr) return nullptr;
            if (e.x == x && e.y == y && e.z == z) break;
        }
        T* found = s.slots[i].v;
        size_t j = i;
        for (;;) {
            j = (j + 1) & mask;
            if (s.slots[j].v == nullptr) break;
            const size_t k = static_cast<size_t>(hash3(s.slots[j].x, s.slots[j].y, s.slots[j].z)) & mask;
            const bool stays = (j > i) ? (k > i && k <= j) : (k > i || k <= j);
            if (!stays) {
                s.slots[i] = s.slots[j];
                i = j;
            }
        }
        s.slots[i].v = nullptr;
        --s.count;
        size.fetch_sub(1, std::memory_order_relaxed);
        return found;
    }

    // f(x, y, z, T*) under each shard's shared lock.
    template <class F>
    void for_each(F&& f) {
        for (Shard& s : shards_) {
            s.lock.lock_shared();
            for (const Slot& e : s.slots) {
                if (e.v != nullptr) f(e.x, e.y, e.z, e.v);
            }
            s.lock.unlock_shared();
        }
    }

    // Deletes every value (world destruction; no other thread may use the map).
    void destroy_all() {
        for (Shard& s : shards_) {
            for (Slot& e : s.slots) {
                delete e.v;
                e.v = nullptr;
            }
            s.slots.clear();
            s.count = 0;
        }
        size.store(0);
    }

    size_t bucket_bytes() const {
        size_t n = 0;
        for (const Shard& s : shards_) n += s.slots.capacity() * sizeof(Slot);
        return n;
    }

    std::atomic<int64_t> size{0};

private:
    std::array<Shard, kShards> shards_{};

    static void grow(Shard& s) {
        std::vector<Slot> old;
        old.swap(s.slots);
        s.slots.assign(old.empty() ? 16 : old.size() * 2, Slot{0, 0, 0, nullptr});
        const size_t mask = s.slots.size() - 1;
        for (const Slot& e : old) {
            if (e.v == nullptr) continue;
            size_t i = static_cast<size_t>(hash3(e.x, e.y, e.z)) & mask;
            while (s.slots[i].v != nullptr) i = (i + 1) & mask;
            s.slots[i] = e;
        }
    }
};

// Cube intervals whose sky input changed, per column offset (dx, dz) in -1..1, index (dz+1)*3 + dx+1.
struct SurfaceDelta {
    int64_t lo[9];
    int64_t hi[9];
    SurfaceDelta() {
        for (int i = 0; i < 9; ++i) {
            lo[i] = INT64_MAX;
            hi[i] = INT64_MIN;
        }
    }
    // The sky surface at local (lx, lz) went from a to b.
    void add(int lx, int lz, int64_t a, int64_t b);
    bool empty() const;
};

// Mesher state of a world (models, bindings, fluids, face textures: mesh_state.cpp, Phase 1b). Owned
// by the world; the destructor is defined by the mesher so the core never sees its type.
struct MeshState;
struct MeshStateHolder {
    std::atomic<MeshState*> p{nullptr};
    MeshStateHolder() = default;
    MeshStateHolder(const MeshStateHolder&) = delete;
    MeshStateHolder& operator=(const MeshStateHolder&) = delete;
    ~MeshStateHolder();
};

} // namespace cenda::voxel

struct cv_world {
    int64_t min_y = 0;
    int64_t max_y = 0;
    cv_surface_fn surface = nullptr;
    void* user = nullptr;
    uint16_t air = 0;

    std::atomic<const cenda::voxel::BlockTable*> table{nullptr};
    std::mutex table_mutex;
    std::vector<std::unique_ptr<cenda::voxel::BlockTable>> tables;   // every table ever published

    std::atomic<uint32_t> layer_flags[CV_MAX_LAYERS] = {};
    std::atomic<int64_t> revision{0};

    cenda::voxel::ShardedMap<cenda::voxel::Cube> cubes;
    cenda::voxel::ShardedMap<cenda::voxel::Column> columns;

    std::mutex dirty_mutex;
    std::unordered_set<cenda::Int3, cenda::Int3Hash> dirty;

    std::mutex ephemeral_mutex;   // callback answers of columns that have no column object
    std::unordered_map<cenda::Int3, std::shared_ptr<const cenda::voxel::SurfaceCache>, cenda::Int3Hash> ephemeral;
    std::shared_ptr<const cenda::voxel::SurfaceCache> unknown_cache;   // "callback knows nothing"

    cenda::voxel::MeshStateHolder mesh;   // mesher-owned (mesh_state.cpp)

    bool y_in(int64_t y) const { return y >= min_y && y < max_y; }
    bool cube_in(int64_t cy) const {
        return cy >= (min_y >> CV_CUBE_SHIFT) && cy <= ((max_y - 1) >> CV_CUBE_SHIFT);
    }
    // True when every cell of cube cy is inside the bounds.
    bool cube_inside(int64_t cy) const {
        return cy * CV_CUBE_EDGE >= min_y && cy * CV_CUBE_EDGE + (CV_CUBE_EDGE - 1) < max_y;
    }
};

namespace cenda::voxel {

constexpr size_t kEphemeralCap = 8192;

// Locks a present cube (shared or exclusive); null when absent.
Cube* cube_shared(const cv_world* w, int64_t cx, int64_t cy, int64_t cz);
Cube* cube_exclusive(cv_world* w, int64_t cx, int64_t cy, int64_t cz);
bool cube_present(const cv_world* w, int64_t cx, int64_t cy, int64_t cz);

// Columns (cv_surface.cpp).
Column* column_shared(const cv_world* w, int64_t cx, int64_t cz);
Column* column_exclusive(cv_world* w, int64_t cx, int64_t cz);
// Finds or creates; adopts an ephemeral callback answer, else `cb`. Returns the column locked exclusive.
Column* column_create_exclusive(cv_world* w, int64_t cx, int64_t cz, std::shared_ptr<const SurfaceCache> cb);
// The callback answer for a column (fetching it, with no lock held, the first time).
std::shared_ptr<const SurfaceCache> ensure_surface_cache(cv_world* w, int64_t cx, int64_t cz);
// The answer of channel ch at column cell i (under the column's lock when col != null).
int64_t column_value(const cv_world* w, const Column* col, const SurfaceCache* cb, int ch, uint32_t i);
// out_min/out_max (may be null): min and max of out[] (answers are never kNone).
void column_values(const cv_world* w, const Column* col, const SurfaceCache* cb, int ch, int64_t* out,
                   int64_t* out_min = nullptr, int64_t* out_max = nullptr);
// Recomputes both answer arrays (and bounds) from the tracker, ranges and callback values.
void column_refresh_answers(const cv_world* w, Column* col);
// Applies a fresh cube's leaves to its (locked) column: generated mark, loaded list, both channels, the
// answers; accumulates sky changes into delta. Moves d.leaf_packed[] into the tracker when d.packed.
void column_apply_cube(const cv_world* w, Column* col, int64_t cy, Derived& d, bool add_loaded,
                       SurfaceDelta& delta);

// Known air (contract "KNOWN AIR"): the TOP answer of one column cell / all 1024; false when nothing is
// known about the column (no record, no callback answer). May fetch the callback (call with no lock).
bool column_answer_at(const cv_world* w, int64_t cx, int64_t cz, int ch, uint32_t i, int64_t* v);
bool column_answers(const cv_world* w, int64_t cx, int64_t cz, int ch, int64_t* out1024);
std::shared_ptr<const SurfaceCache> make_surface_cache(const cv_world* w, bool known, const int64_t* sky,
                                                       const int64_t* top);
// Re-derives masks and tracker leaves of a present cube from its content (cv_world.cpp).
void reapply_cube(cv_world* w, int64_t cx, int64_t cy, int64_t cz);
struct CubeInstall;
int32_t install_prepared_ids(cv_world* w, int64_t cx, int64_t cy, int64_t cz, const CubeInstall& in, bool replace);

// What prepare_cube / prepare_cube_encoded build off the owner thread (cv_internal.hpp PreparedCube): the
// cube's content in its final storage form, its masks and tracker leaves (raw + packed) at `d.table`, and
// the column's surface-callback answer. install_prepared swaps it in.
struct PreparedData {
    const cv_world* world = nullptr;
    int64_t cx = 0, cy = 0, cz = 0;
    uint16_t uniform_id = 0;
    Section* sec = nullptr;      // 8 block sections, null when the cube is one block
    CubeExtra* extra = nullptr;  // layers + blob, null when empty
    Derived d;
    std::shared_ptr<const SurfaceCache> cb;
    PreparedData() = default;
    PreparedData(const PreparedData&) = delete;
    PreparedData& operator=(const PreparedData&) = delete;
    ~PreparedData();
};

// Dirty set.
void mark_present(cv_world* w, const std::vector<Int3>& keys);
void mark_neighbourhood(cv_world* w, int64_t cx, int64_t cy, int64_t cz);   // cube + 26 present ones
void apply_surface_delta(cv_world* w, int64_t cx, int64_t cz, const SurfaceDelta& d);

} // namespace cenda::voxel
