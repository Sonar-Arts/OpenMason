// Engine-internal access to a cv_world (include/cenda/voxel_world.h) for the other Cenda voxel subsystems
// in the same library: the mesher + visibility graph (Phase 1b, src/voxel_world/mesher*.cpp,
// visibility*.cpp) and the streamer (cv_stream_*). NOT part of any C ABI; the JVM never sees it.
//
// THREADING AND LOCKS
//  - Every function here is thread-safe, like the cv_* calls.
//  - CubeRead holds ONE cube's shared lock for its lifetime. Never hold two CubeReads at once on one
//    thread and never call a cv_* WRITE (cv_set, cv_layer_set, installs...) while holding one: writes
//    take cube locks exclusively and the cube locks prefer writers, so a thread that holds a shared lock
//    and waits for another cube can deadlock against a writer. read_padded() takes the 27 locks one at a
//    time and is the way to read a neighbourhood.
//  - Nothing here calls the host surface callback while a lock is held; surface_column/surface_ring
//    may call it (no locks held at that moment) the first time a column is asked about.
//
// VALUES
//  - Block ids and layer values are uint16. Cells outside the world's vertical bounds always read as the
//    air id (layers: 0); the core enforces this on every bulk write, so a cube's stored content already
//    agrees.
//  - Cell index inside a cube: CV_CELL_INDEX(lx, ly, lz) = (ly * 32 + lz) * 32 + lx.
//  - Mask rows: row index ly * 32 + lz, bit lx. Occupancy = (id != air), opacity = CV_BLOCK_OPAQUE.
//  - Surface values are absolute block y (int64) of the first free cell above the topmost
//    CV_BLOCK_SKY_OPAQUE block; min_y when the column has none (INT64_MIN in an unbounded world).
#pragma once

#include "cenda/voxel_world.h"

#include <cstddef>
#include <cstdint>

namespace cenda::voxel {

struct Cube;

// ---- world facts ----------------------------------------------------------------------------------
int64_t world_min_y(const cv_world* world);   // inclusive (CV_UNBOUNDED_MIN when unbounded)
int64_t world_max_y(const cv_world* world);   // exclusive (CV_UNBOUNDED_MAX when unbounded)
uint16_t world_air(const cv_world* world);
// The block table as 65536 flag words (ids past the host's table are 0). The pointer is a snapshot:
// it stays valid (and unchanged) for the world's lifetime even after cv_block_table_set publishes a
// newer table; re-fetch it per mesh. A table change marks every loaded cube dirty.
const uint16_t* world_block_flags(const cv_world* world);
// CV_LAYER_* flags of a layer (0 when undefined or out of range).
uint32_t world_layer_flags(const cv_world* world, int32_t layer);

// ---- one cube, shared lock -----------------------------------------------------------------------
class CubeRead {
public:
    CubeRead(const cv_world* world, int64_t cx, int64_t cy, int64_t cz);
    ~CubeRead();
    CubeRead(CubeRead&& other) noexcept;
    CubeRead& operator=(CubeRead&&) = delete;
    CubeRead(const CubeRead&) = delete;
    CubeRead& operator=(const CubeRead&) = delete;

    // False when the cube is not in the world (every other method then must not be called).
    explicit operator bool() const { return cube_ != nullptr; }

    int64_t revision() const;
    // True (and *id) when every cell holds one block id. Exact, cheap for uniform storage.
    bool uniform(uint16_t* id) const;

    uint16_t get(int lx, int ly, int lz) const;
    // All 32768 ids in CV_CELL_INDEX order.
    void read_ids(uint16_t* out) const;
    // The cube-local box [x0,x0+sx) x [y0,y0+sy) x [z0,z0+sz) (all inside 0..32) into
    // dst[(y - y0) * stride_y + (z - z0) * stride_z + (x - x0)].
    void copy_ids(int x0, int y0, int z0, int sx, int sy, int sz,
                  uint16_t* dst, ptrdiff_t stride_z, ptrdiff_t stride_y) const;

    // Host layers (layer in [0, CV_MAX_LAYERS)); an undefined or never-written layer reads 0.
    bool layer_empty(int32_t layer) const;   // every cell 0 (exact)
    uint16_t layer(int32_t layer, int lx, int ly, int lz) const;
    void read_layer(int32_t layer, uint16_t* out) const;
    void copy_layer(int32_t layer, int x0, int y0, int z0, int sx, int sy, int sz,
                    uint16_t* dst, ptrdiff_t stride_z, ptrdiff_t stride_y) const;

    uint32_t occupancy_row(int ly, int lz) const;
    uint32_t opacity_row(int ly, int lz) const;
    void occupancy_rows(uint32_t* out1024) const;
    void opacity_rows(uint32_t* out1024) const;

private:
    Cube* cube_ = nullptr;
    const cv_world* world_ = nullptr;
};

// ---- a cube with its 1-cell padding (what the mesher needs, in one call) ---------------------------
// Padded coordinates p = local + 1, 0..33 per axis. ids index (py * 34 + pz) * 34 + px. Mask rows
// index py * 34 + pz, bit px (34 bits used). Cells of missing neighbour cubes and cells outside the
// vertical bounds read as air (layers 0, mask bits 0).
constexpr int kPad = 34;
constexpr int kPadCells = kPad * kPad * kPad;   // 39304
constexpr int kPadRows = kPad * kPad;           // 1156

inline int pad_index(int px, int py, int pz) { return (py * kPad + pz) * kPad + px; }
// Neighbour bit for cube offset (dx, dy, dz) in -1..1: (dy + 1) * 9 + (dz + 1) * 3 + (dx + 1).
inline int neighbour_bit(int dx, int dy, int dz) { return (dy + 1) * 9 + (dz + 1) * 3 + (dx + 1); }

struct PaddedCube {
    uint16_t ids[kPadCells];
    uint64_t occupancy[kPadRows];
    uint64_t opacity[kPadRows];
    // Cells of MISSING neighbour cubes that are NOT known air (below their column's TOP, inside the
    // bounds): what the world does not know. Their ids still read as air (contract: a missing neighbour
    // is air for culling); visibility treats them as open.
    uint64_t unknown[kPadRows];
    uint32_t present = 0;    // neighbour_bit set for every cube of the 3x3x3 that exists (13 = centre)
    int64_t revision = 0;    // centre cube revision at the time it was read
};

// Fills `out` for cube (cx, cy, cz) and, for each of the n requested layers, layer_out[k] (kPadCells
// values, same indexing) with layer layers[k]. Each cube is read under its own shared lock, one at a
// time. Returns CV_OK, CV_ERR_MISSING (centre absent; out untouched) or CV_ERR_ARG.
int32_t read_padded(const cv_world* world, int64_t cx, int64_t cy, int64_t cz, PaddedCube& out,
                    const int32_t* layers, uint16_t* const* layer_out, int32_t n);

// ---- surface (#144) ------------------------------------------------------------------------------
// As cv_surface_column: out[lz * 32 + lx]. CV_OK, or CV_ERR_MISSING when nothing is known (out = min_y).
int32_t surface_column(cv_world* world, int64_t cx, int64_t cz, int64_t* out1024);
// The 34x34 ring around column (cx, cz) that sky light per corner needs: out[pz * 34 + px] is the
// surface at block x = cx*32 - 1 + px, z = cz*32 - 1 + pz. Unknown columns give min_y.
void surface_ring(cv_world* world, int64_t cx, int64_t cz, int64_t* out1156);

// The 34x34 TOP ring, same indexing as surface_ring.
void top_ring(cv_world* world, int64_t cx, int64_t cz, int64_t* out1156);
// cv_top_y / cv_top_range (C ABI), here for symmetry.
inline int32_t top_y(cv_world* world, int64_t x, int64_t z, int64_t* out_y) { return cv_top_y(world, x, z, out_y); }
inline int32_t top_range(cv_world* world, int64_t cx, int64_t cz, int64_t* top_min, int64_t* top_max,
                         int64_t* sky_min) {
    return cv_top_range(world, cx, cz, top_min, top_max, sky_min);
}
// cv_top_range that never calls the host surface callback (the streamer's planner asks it on its own thread
// and resolves the rest on workers): 1 = known (same values cv_top_range gives), 0 = known to be unknown (no
// callback, or its cached answer is "unknown"), -1 = only the callback could answer (nothing cached: a
// never-generated column not asked about yet, or its answer was evicted), CV_ERR_ARG. Outputs as cv_top_range.
int32_t top_range_peek(cv_world* world, int64_t cx, int64_t cz, int64_t* top_min, int64_t* top_max,
                       int64_t* sky_min);

// ---- known air (contract "KNOWN AIR") ------------------------------------------------------------------
// Cells of a missing cube at or above their column's TOP are known air; outside the vertical bounds
// everything is air. These answer from the per-column cached TOP (O(1), one column lookup; the first
// question about a never-generated column fetches the host callback, so call them with no lock held).
// A column nothing is known about (no record, callback NULL or unknown) has no known air.
bool known_air(cv_world* world, int64_t x, int64_t y, int64_t z);
// Every cell of cube (cx, cy, cz) is known air: cy*32 >= the column's highest TOP (or the cube is
// wholly outside the bounds). Says nothing about whether the cube is loaded.
bool cube_known_air(cv_world* world, int64_t cx, int64_t cy, int64_t cz);
// The TOP answers of a column (out[lz*32+lx]) for bulk per-cell tests; false when nothing is known
// (out = min_y, and then no cell is known air).
bool column_top(cv_world* world, int64_t cx, int64_t cz, int64_t* out1024);

// ---- bulk install (streamer) ----------------------------------------------------------------------
struct CubeInstall {
    const uint16_t* ids = nullptr;              // CV_CUBE_CELLS ids, or null: every cell fill_id
    uint16_t fill_id = 0;
    const uint16_t* layers[CV_MAX_LAYERS] = {}; // per layer CV_CUBE_CELLS values, or null: all 0
    const uint8_t* blob = nullptr;              // host blob (copied), may be null
    int64_t blob_len = 0;
};
// Installs a whole cube (blocks, every layer, blob) in one locked step: one revision, one tracker
// update, one dirty marking (cube + 26 neighbours + surface changes) — exactly what cv_cube_write_ids
// + cv_layer_write_cube + cv_cube_set_blob would do, without the intermediate states. With replace =
// false an existing cube is left alone and CV_ERR_EXISTS is returned. CV_ERR_ARG when the cube lies
// wholly outside the vertical bounds.
int32_t install_cube(cv_world* world, int64_t cx, int64_t cy, int64_t cz, const CubeInstall& content,
                     bool replace);

// ---- prepare + commit (streamer: build on a worker, swap in on the owner) ------------------------------
// An install split in two. prepare_cube / prepare_cube_encoded (ANY thread, no cube or column lock held
// while building) turn content into the cube's final storage form: paletted sections with the vertical
// bounds applied, layers, blob, occupancy/opacity masks and both tracker leaves (raw + packed, against the
// block table current at prepare time), and fetch the column's surface-callback answer (the host callback
// may run there, on the preparing thread). install_prepared (normally the OWNER thread) then only inserts or
// locks the cube, swaps the prepared storage in, bumps one revision, applies the precomputed leaves to the
// column and marks the dirty set - the same result as install_cube / cv_cube_decode (same revisions, dirty
// set, tracker values and record bytes). A block table published between prepare and install is detected
// and the masks/leaves are re-derived at install (correct, just not cheaper).
// A PreparedCube is bound to its world and cube; it is move-only and may be dropped unused.
struct PreparedData;
class PreparedCube {
public:
    PreparedCube() noexcept;
    ~PreparedCube();
    PreparedCube(PreparedCube&& other) noexcept;
    PreparedCube& operator=(PreparedCube&& other) noexcept;
    PreparedCube(const PreparedCube&) = delete;
    PreparedCube& operator=(const PreparedCube&) = delete;

    explicit operator bool() const { return data_ != nullptr; }
    void reset();
    // Heap bytes held (sections, masks, layers, blob, leaves): for budgets/diagnostics.
    size_t heap_bytes() const;
    PreparedData* data() const { return data_; }   // core only (cv_world.cpp)
    void adopt(PreparedData* d) noexcept;           // core only

private:
    PreparedData* data_ = nullptr;
};
// From ids/layers/blob as install_cube. CV_OK (out holds the cube), CV_ERR_ARG (bad arguments, cube wholly
// outside the vertical bounds, bad blob). Never touches the cube itself; `out` is replaced.
int32_t prepare_cube(const cv_world* world, int64_t cx, int64_t cy, int64_t cz, const CubeInstall& content,
                     PreparedCube& out);
// From cv_cube_encode bytes as cv_cube_decode: CV_OK, CV_ERR_ARG, CV_ERR_CORRUPT (nothing prepared).
int32_t prepare_cube_encoded(const cv_world* world, int64_t cx, int64_t cy, int64_t cz, const uint8_t* src,
                             int64_t len, PreparedCube& out);
// Commits a prepared cube. replace = false: an existing cube is left alone (CV_ERR_EXISTS). CV_ERR_ARG when
// `prepared` is empty or was prepared for another world / cube. On CV_OK `prepared` is consumed (empty
// afterwards; replaced content is freed here); on an error it is left as it was.
int32_t install_prepared(cv_world* world, int64_t cx, int64_t cy, int64_t cz, PreparedCube&& prepared,
                         bool replace);

// ---- dirty set -------------------------------------------------------------------------------------
// Adds a present cube to the dirty set (no-op when absent).
void mark_dirty(cv_world* world, int64_t cx, int64_t cy, int64_t cz);

// ---- diagnostics (tests, memory reports) ------------------------------------------------------------
// Heap bytes owned by one cube (struct + sections + masks + layers + blob), 0 when absent. Excludes the
// map slot (kCubeSlotBytes per cube at the map's load factor).
size_t cube_heap_bytes(const cv_world* world, int64_t cx, int64_t cy, int64_t cz);
constexpr size_t kCubeSlotBytes = 32;
// Heap bytes owned by a column record (tracker leaves/groups, ranges, loaded list), 0 when absent.
size_t column_heap_bytes(const cv_world* world, int64_t cx, int64_t cz);

} // namespace cenda::voxel
