// Private structures of the octree LOD (cv_lod_*, OpenMason #122 / Phase 5; contract in voxel_world.h "LOD").
// lod_data.cpp: node data (classification, level-0 sources, downsampling, digests, records, cache);
// lod_mesh.cpp: node meshes (LODQUAD16 node mode, greedy, boundary cover rule);
// the cv_lod object (struct cv_lod + the runtime's shared types / declarations: lod_runtime.hpp), by concern:
//  - lod.cpp: lifecycle (cv_lod_create / cv_lod_destroy) + the cv_lod_* C ABI (cv_lod_tick: lod_owner.cpp);
//  - lod_sources.cpp: block classification, the per-level node stores, level-0 sources (loaded cubes, the cube
//    store, column facts), node reads / rebuilds and prepares (data + mesh);
//  - lod_dirty.cpp: dirty propagation, held notes, the revision watch (scanner), the worker threads;
//  - lod_persist.cpp: the store meta ("lod.meta") + the deferred dirty set ("deferred.bin");
//  - lod_select.cpp: the selection (Selector) + the planner thread and its state;
//  - lod_owner.cpp: the owner's drawn cover, cv_lod_tick, events.
//
// THREADS AND LOCKS
//  - OWNER: cv_lod_tick, cv_lod_note_events, cv_lod_set_column_ready, cv_lod_mesh_get. Owns `Owner` (node
//    states, readiness, drawn groups); nothing else touches it.
//  - WORKERS: rebuild dirty nodes and prepare (data + mesh) requested nodes. They hand results back under `m`.
//  - Node builds take a per-level stripe mutex (build_lock) so one node is never built twice at once and a
//    newer build cannot be overwritten by an older one; a build only takes stripes of LOWER levels while
//    holding one (strict hierarchy: no deadlock). Cache, column cache and store mutexes are leaves.
//  - World access only through cv_* and cv_internal.hpp (CubeRead, column_top); never while holding `m`.
#pragma once

#include "cenda/voxel_world.h"
#include "../int3_key.hpp"
#include "cv_internal.hpp"

#include <array>
#include <atomic>
#include <cstdint>
#include <list>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace cenda::voxel::lod {

constexpr int kEdge = CV_LOD_EDGE;
constexpr int kCells = CV_CUBE_CELLS;
constexpr int kLevels = CV_LOD_MAX_LEVEL + 1;

// Cell byte: class in bits 0..1 (kAir 0, kSolid 1, kFluid 2), kFull in bit 2 (== CV_LOD_CELL_*).
constexpr uint8_t kAir = 0;
constexpr uint8_t kSolid = CV_LOD_CELL_SOLID;
constexpr uint8_t kFluid = CV_LOD_CELL_FLUID;
constexpr uint8_t kClassMask = 3;
constexpr uint8_t kFull = CV_LOD_CELL_FULL;

inline int cell_index(int x, int y, int z) { return CV_CELL_INDEX(x, y, z); }

struct NodeKey {
    int32_t level = 0;
    int64_t x = 0, y = 0, z = 0;
    friend bool operator==(const NodeKey& a, const NodeKey& b) {
        return a.level == b.level && a.x == b.x && a.y == b.y && a.z == b.z;
    }
    friend bool operator!=(const NodeKey& a, const NodeKey& b) { return !(a == b); }
    friend bool operator<(const NodeKey& a, const NodeKey& b) {
        if (a.level != b.level) return a.level < b.level;
        if (a.x != b.x) return a.x < b.x;
        if (a.y != b.y) return a.y < b.y;
        return a.z < b.z;
    }
};
struct NodeKeyHash {
    size_t operator()(const NodeKey& k) const {
        return static_cast<size_t>(mix64(hash3(k.x, k.y, k.z) + static_cast<uint64_t>(k.level) * uint64_t{0x9e3779b97f4a7c15u}));
    }
};
inline NodeKey parent_of(const NodeKey& k) {
    return NodeKey{k.level + 1, k.x >> 1, k.y >> 1, k.z >> 1};
}
inline NodeKey child_of(const NodeKey& k, int i) {   // i = (cy * 2 + cz) * 2 + cx
    return NodeKey{k.level - 1, k.x * 2 + (i & 1), k.y * 2 + ((i >> 2) & 1), k.z * 2 + ((i >> 1) & 1)};
}
// Block coordinate of a node's min corner along one axis.
inline int64_t node_origin(int64_t n, int32_t level) { return n * (int64_t{kEdge} << level); }

// 2D footprint (level, fx, fz).
struct FootKey {
    int32_t level = 0;
    int64_t x = 0, z = 0;
    friend bool operator==(const FootKey& a, const FootKey& b) {
        return a.level == b.level && a.x == b.x && a.z == b.z;
    }
    friend bool operator<(const FootKey& a, const FootKey& b) {
        if (a.level != b.level) return a.level < b.level;
        if (a.x != b.x) return a.x < b.x;
        return a.z < b.z;
    }
};
struct FootKeyHash {
    size_t operator()(const FootKey& k) const {
        return static_cast<size_t>(mix64(hash3(k.x, k.level, k.z)));
    }
};

// ---- node data -------------------------------------------------------------------------------------------
struct NodeData {
    NodeKey key;
    bool complete = false;
    bool uniform = false;
    uint16_t uniform_id = 0;
    uint8_t uniform_cell = 0;
    uint64_t digest = 0;
    uint64_t child_digest[8] = {};   // level >= 1 (0 = an unknown child)
    std::vector<uint16_t> ids;       // kCells, empty when uniform
    std::vector<uint8_t> cells;      // kCells, empty when uniform

    uint16_t id(int i) const { return uniform ? uniform_id : ids[static_cast<size_t>(i)]; }
    uint8_t cell(int i) const { return uniform ? uniform_cell : cells[static_cast<size_t>(i)]; }
    size_t bytes() const { return sizeof(NodeData) + ids.capacity() * 2 + cells.capacity(); }
};
using NodePtr = std::shared_ptr<const NodeData>;

// Collapses a uniform node's arrays and computes its digest (call once the arrays are filled).
void finalize(NodeData& d);

// Classification snapshot: one class per block id (kAir / kSolid / kFluid) for the world's current block table,
// mesh config (fluids) and the LOD's overrides.
struct ClassTable {
    uint8_t cls[65536];
};

// Level-0 node from 32768 cube ids. Cells outside [min_y, max_y) are air.
void node_from_ids(NodeData& out, const NodeKey& key, const uint16_t* ids, const ClassTable& ct, uint16_t air,
                   int64_t min_y, int64_t max_y);
// Uniform node (one cell value everywhere).
void node_uniform(NodeData& out, const NodeKey& key, uint16_t id, uint8_t cell, bool complete);
// Level k >= 1 from its 8 children (null = unknown: counts as air and makes the node incomplete).
void node_downsample(NodeData& out, const NodeKey& key, const NodePtr* children, uint16_t air);

// Node record (voxel_world.h PERSISTENCE). decode_record answers CV_ERR_CORRUPT on anything malformed or
// belonging to another node.
void encode_record(const NodeData& d, std::vector<uint8_t>& out);
int32_t decode_record(const uint8_t* p, size_t n, const NodeKey& key, NodeData& out);

// The BLOCKS part of cv_cube_encode bytes into 32768 ids. CV_OK / CV_ERR_CORRUPT.
int32_t decode_cube_blocks(const uint8_t* p, size_t n, uint16_t* out_ids);

// Generated cube-y runs of a column record (cv_column_encode core bytes, or the stream's "CVS1" envelope,
// which is stripped: *core / *core_len give the core). false when malformed.
struct Run {
    int64_t lo, hi;
};
bool parse_column_record(const uint8_t* p, size_t n, std::vector<Run>& runs, const uint8_t** core, size_t* core_len);

// ---- caches ----------------------------------------------------------------------------------------------
// LRU by bytes. `tag` is caller data (level 0: the cube revision the entry was read at).
class NodeCache {
public:
    explicit NodeCache(size_t cap) : cap_(cap) {}
    NodePtr get(const NodeKey& k, uint64_t* tag = nullptr);
    void put(const NodeKey& k, NodePtr d, uint64_t tag = 0);
    void erase(const NodeKey& k);
    size_t bytes() const;
    size_t count() const;
    void set_cap(size_t cap) {
        std::lock_guard<std::mutex> g(m_);
        cap_ = cap;
    }

private:
    struct Entry {
        NodePtr d;
        uint64_t tag = 0;
        std::list<NodeKey>::iterator it;
    };
    mutable std::mutex m_;
    std::unordered_map<NodeKey, Entry, NodeKeyHash> map_;
    std::list<NodeKey> lru_;   // front = most recent
    size_t bytes_ = 0;
    size_t cap_;
};

struct ColumnInfo {
    bool generated = false;
    std::vector<Run> runs;
    std::vector<int64_t> top;   // 1024 TOP answers (y of the first free cell above the topmost non-air block)
    int64_t top_min = 0, top_max = 0;
    uint64_t digest = 0;        // generated + runs + top: a change means synthesized cubes of the column changed
    bool in_runs(int64_t cy) const {
        for (const Run& r : runs) {
            if (cy >= r.lo && cy <= r.hi) return true;
        }
        return false;
    }
};
using ColumnPtr = std::shared_ptr<const ColumnInfo>;

// ---- meshes ----------------------------------------------------------------------------------------------
struct MeshBuild {
    NodeKey key;
    std::vector<uint8_t> quads;
    std::vector<uint8_t> water;
    int32_t quad_count = 0, water_count = 0;
    float aabb_min[3] = {0, 0, 0}, aabb_max[3] = {0, 0, 0};
    uint64_t digest = 0;      // node data digest
    uint64_t input_hash = 0;  // node + neighbour digests: an equal hash means an identical mesh
    bool empty() const { return quad_count == 0 && water_count == 0; }
    int64_t bytes() const { return static_cast<int64_t>(quads.size() + water.size()); }
};
using MeshPtr = std::shared_ptr<const MeshBuild>;

// Texture layer + alpha of a face of a block id (cv_block_faces_set, packer defaults).
struct FaceLookup {
    virtual ~FaceLookup() = default;
    virtual void face(uint16_t id, int face, uint32_t* layer, bool* alpha) const = 0;
};

// Neighbour order: 0 above (+y), 1 below (-y), 2 north (-z), 3 south (+z), 4 east (+x), 5 west (-x) - cv_quad
// face order. below_solid: the node's bottom lies at/below the LOD range floor (bottom faces never drawn).
// cover_levels: how many coarser levels a side neighbour's ancestors must be SOLID for (min(5, max - level)).
void mesh_node(const NodeData& node, const NodeData* const* nb, bool below_solid, int cover_levels,
               const FaceLookup& faces, MeshBuild& out);

// LODQUAD16 node mode (voxel_world.h MESHES; Java twin MmsLodQuadCodec node mode).
constexpr int kNodeXzBias = 8;
constexpr int kNodeYBias = 1024;
inline uint32_t node_word0(int x, int y, int z, int face) {
    return static_cast<uint32_t>(x + kNodeXzBias) | (static_cast<uint32_t>(z + kNodeXzBias) << 9) |
           (static_cast<uint32_t>(y + kNodeYBias) << 18) | (static_cast<uint32_t>(face) << 29);
}
inline uint32_t node_word1(int w, int h, uint32_t layer, bool alpha, bool lit) {
    return static_cast<uint32_t>(w) | (static_cast<uint32_t>(h) << 6) | ((layer & 0xFFFu) << 17) |
           ((alpha ? 1u : 0u) << 29) | ((lit ? 1u : 0u) << 31);
}

uint64_t hash_bytes(const void* p, size_t n, uint64_t seed);

} // namespace cenda::voxel::lod
