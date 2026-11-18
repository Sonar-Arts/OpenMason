// Private shared declarations of the native cube mesher (OpenMason #143, Phase 1b): mesh_state.cpp
// (models, bindings, fluids, face textures), mesh_binary.cpp (binary greedy cube faces + lighting),
// mesh_models.cpp (host models -> COMPACT20), mesh_fluid.cpp (fluids -> WATERQUAD16), mesh_pack.cpp
// (cv_mesh_cube, build_gpu_mesh), visibility.cpp (15-bit graph, cache, walk) and mesh_core_access.cpp
// (the only mesher file that touches core-private structs).
#pragma once

#include "cenda/voxel_world.h"
#include "cv_internal.hpp"
#include "mesh_gpu.hpp"

#include <array>
#include <cmath>
#include <cstdint>
#include <memory>
#include <unordered_map>
#include <vector>

namespace cenda::voxel {

// ---- face conventions (MmsCuboidGenerator / mesher.cpp, verbatim) -----------------------------------------
// Faces: 0 top(+y) 1 bottom(-y) 2 north(-z) 3 south(+z) 4 east(+x) 5 west(-x).
constexpr int kFaceOffsets[6][4][3] = {
    {{0, 1, 1}, {1, 1, 1}, {1, 1, 0}, {0, 1, 0}}, // top (+y)
    {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}}, // bottom (-y)
    {{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}}, // north (-z)
    {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}}, // south (+z)
    {{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}}, // east (+x)
    {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}}, // west (-x)
};
constexpr int kFaceDx[6] = {0, 0, 0, 0, 1, -1};
constexpr int kFaceDy[6] = {1, -1, 0, 0, 0, 0};
constexpr int kFaceDz[6] = {0, 0, -1, 1, 0, 0};
constexpr int kOpposite[6] = {1, 0, 3, 2, 5, 4};
// In-plane axes of a merged rectangle (MmsCuboidGenerator.uAxis / vAxis).
constexpr int kUAxis[6] = {0, 0, 0, 0, 2, 2};
constexpr int kVAxis[6] = {2, 2, 1, 1, 1, 1};

// Face-pair bit (cv_mesh_out.visibility): a < b.
constexpr int pair_bit(int a, int b) { return a * 6 - a * (a + 1) / 2 + (b - a - 1); }
constexpr uint16_t kAllPairs = 0x7FFF;

// MmsBufferLayout.toUnsignedByte.
inline uint8_t to_u8(float f) {
    if (f <= 0.0f) return 0;
    if (f >= 1.0f) return 255;
    return static_cast<uint8_t>(static_cast<int>(f * 255.0f + 0.5f));
}

// Java Math.round(float) (round half up, exact), for |a| < 2^23.
inline int32_t java_round(float a) {
    if (a != a) return 0;
    const float r = std::floor(a);
    return static_cast<int32_t>(r) + ((a - r) >= 0.5f ? 1 : 0);
}

// ---- host configuration (immutable snapshots, copy-on-write) ------------------------------------------------
struct Model {
    std::vector<cv_model_quad> quads;
    std::vector<uint8_t> cull;   // per quad: the face that may cull it (cull_face), 6 when the quad reaches
                                 // past [-1, 2]^3 (one neighbour cell cannot hide it; see voxel_world.h)
    uint8_t fills = 0;        // bit f: the quads culled by face f cover that whole cell plane
    float top = 1.0f;         // highest y of the model (fluid bottom attachment), clamped to [0, 1]
};

struct Binding {
    int32_t layer = -1;                              // < 0: all_model applies to every state
    int32_t all_model = -1;
    std::unordered_map<uint16_t, int32_t> by_value;  // state value -> model id
};

struct FluidCfg {
    cv_fluid_desc desc{};
};

// cv_face_rule_add: one neighbour face rule of a block.
struct FaceRule {
    int32_t layer = -1;              // < 0: every state
    uint16_t value = 0;              // state value when layer >= 0
    uint8_t face_mask = 0;           // bit f: applies to face f
    uint8_t kind = 0;                // CV_FACE_RULE_* (bit values: results OR together)
    std::vector<uint16_t> ids;       // neighbour ids, sorted, unique
};

struct MeshConfig {
    std::vector<std::shared_ptr<const Model>> models;   // by model id (null: undefined)
    std::unordered_map<uint16_t, Binding> bindings;
    std::array<uint64_t, 1024> bound{};                 // bit per block id: has a binding
    std::vector<FluidCfg> fluids;                       // <= kMaxFluids
    std::array<uint64_t, 1024> fluid_bits{};            // bit per block id: is a fluid
    std::unordered_map<uint16_t, std::array<cv_face_tex, 6>> faces;
    std::unordered_map<uint16_t, std::vector<FaceRule>> face_rules;
    std::array<uint64_t, 1024> ruled{};                 // bit per block id: has face rules
    std::array<uint8_t, 65536> rule_faces{};            // per block id: bit f = some rule applies to face f
    std::vector<int32_t> layers;                         // distinct layers the mesher must read
    uint64_t version = 0;                                // bumps on every change

    bool is_bound(uint16_t id) const { return ((bound[id >> 6] >> (id & 63)) & 1u) != 0; }
    bool is_fluid(uint16_t id) const { return ((fluid_bits[id >> 6] >> (id & 63)) & 1u) != 0; }
    bool is_ruled(uint16_t id) const { return ((ruled[id >> 6] >> (id & 63)) & 1u) != 0; }
    int fluid_index(uint16_t id) const;
    // Model of (id, state value), null when none.
    const Model* model_for(uint16_t id, uint16_t state) const;
    const Binding* binding(uint16_t id) const;
};
constexpr int kMaxFluids = 4;
constexpr int32_t kMaxModels = 65536;

// The world's current configuration (never null).
std::shared_ptr<const MeshConfig> mesh_config(cv_world* world);

// ---- core-private access (mesh_core_access.cpp) -------------------------------------------------------------
// The current block table and its generation (index among every table the world ever published).
const uint16_t* table_snapshot(const cv_world* world, uint32_t* generation);
// Visibility cache word for (revision, generation, bits).
inline uint64_t vis_word(int64_t revision, uint32_t generation, uint16_t bits) {
    return (static_cast<uint64_t>(static_cast<uint32_t>(revision)) << 32) |
           (static_cast<uint64_t>(generation & 0xFFFFu) << 16) | 0x8000u | (bits & kAllPairs);
}
// One locked lookup: 0 when the cube is absent; 1 and *bits (computed and cached on a miss, using
// `flags` of table `generation`) when present.
int vis_probe(const cv_world* world, int64_t cx, int64_t cy, int64_t cz, const uint16_t* flags,
              uint32_t generation, uint16_t* bits);
// The cached bits of the cube when they were computed for exactly (revision, generation); false otherwise.
bool vis_cached(const cv_world* world, int64_t cx, int64_t cy, int64_t cz, int64_t revision, uint32_t generation,
                uint16_t* bits);
// Stores bits computed for revision (no-op when the cube changed or vanished meanwhile).
void vis_store(const cv_world* world, int64_t cx, int64_t cy, int64_t cz, int64_t revision,
               uint32_t generation, uint16_t bits);

// ---- visibility ------------------------------------------------------------------------------------------------
// The 15 pair bits of one cube from its VIS_OPAQUE cells: closed[ly * 32 + lz] bit lx.
uint16_t visibility_from_rows(const uint32_t* closed1024);

// ---- the per-thread mesh workspace ---------------------------------------------------------------------------
// Cell classes (one byte per block id, per (table, config) pair).
enum : uint8_t {
    kClsSee = 1,      // air or CV_BLOCK_TRANSPARENT: a cube face is drawn against it
    kClsOpaqueCube = 2,
    kClsTranspCube = 4,
    kClsSpecial = 8,  // non-cube with a model binding, a fluid, or CV_BLOCK_HOST_GEOMETRY
    kClsVisClosed = 16,
    kClsAir = 32,
    kClsTranslucentCube = 64,   // kClsTranspCube with CV_BLOCK_TRANSLUCENT (the translucent list)
};

struct MeshWork {
    PaddedCube pad;
    std::vector<uint16_t> layer_buf[CV_MAX_LAYERS];   // kPadCells each, for cfg.layers
    int32_t layer_slot[CV_MAX_LAYERS] = {-1, -1, -1, -1, -1, -1, -1, -1};   // layer -> buffer index
    // Row masks over the padded volume (row = py * 34 + pz, bit px).
    uint64_t see[kPadRows];
    uint64_t opaque_cube[kPadRows];
    uint64_t transp_cube[kPadRows];
    uint64_t special[kPadRows];
    uint64_t id_runs[kPadRows];     // bit px (1..33): ids[px] != ids[px - 1]; bit 0 always (classify)
    uint32_t closed[1024];          // interior VIS_OPAQUE rows (ly * 32 + lz, bit lx)
    int64_t ring[kPad * kPad];      // sky surface ring
    bool ring_ready = false;
    // Context of the current cube.
    cv_world* world = nullptr;
    int64_t cx = 0, cy = 0, cz = 0;
    int64_t y0 = 0;                 // cy * 32
    const uint16_t* flags = nullptr;
    uint32_t generation = 0;
    std::shared_ptr<const MeshConfig> cfg;
    bool smooth = true;
    // Class table cache.
    const uint16_t* cls_flags = nullptr;
    uint64_t cls_version = ~uint64_t{0};
    const MeshConfig* cls_cfg = nullptr;
    uint8_t cls[65536];

    uint16_t id_at(int px, int py, int pz) const { return pad.ids[pad_index(px, py, pz)]; }
    // Layer value (0 when the layer is not read).
    uint16_t layer_at(int32_t layer, int px, int py, int pz) const {
        if (layer < 0 || layer >= CV_MAX_LAYERS || layer_slot[layer] < 0) return 0;
        return layer_buf[layer_slot[layer]][static_cast<size_t>(pad_index(px, py, pz))];
    }
    bool unknown_at(int px, int py, int pz) const { return ((pad.unknown[py * kPad + pz] >> px) & 1u) != 0; }
    bool opaque_at(int px, int py, int pz) const { return ((pad.opacity[py * kPad + pz] >> px) & 1u) != 0; }
    const int64_t* sky_ring();
    // Model of the cell (null when none: cube, air, unbound).
    const Model* model_at(int px, int py, int pz) const;
};

// The calling thread's workspace (heap, reused).
MeshWork& mesh_work();
// Reads cube (cx, cy, cz) into w (padded ids, masks, layers, classes). CV_OK / CV_ERR_MISSING / CV_ERR_ARG.
int32_t mesh_prepare(cv_world* world, int64_t cx, int64_t cy, int64_t cz, uint32_t flags, MeshWork& w);

// Cube faces as cube-local quads: out[0] opaque, [1] cutout, [2] translucent (appended).
void mesh_cube_faces(MeshWork& w, bool greedy, std::vector<cv_quad>* out3);
// The same faces packed straight to QUAD16 (region-local, appended to out.quad16[0..2], counts not set) with
// the integer bounds of the records (lo3 > hi3 when none): what packing mesh_cube_faces' lists gives.
void mesh_cube_faces_gpu(MeshWork& w, bool greedy, GpuMeshBuild& out, int* lo3, int* hi3);
// HOST_GEOMETRY cells with no model and not fluid (appended).
void mesh_host_cells(const MeshWork& w, std::vector<cv_host_cell>& out);
// Host models into COMPACT20 (region-local), extends the bounds.
void mesh_models(MeshWork& w, GpuMeshBuild& out, float* bmin, float* bmax);
// Fluids into WATERQUAD16 (region-local), extends the bounds.
void mesh_fluids(MeshWork& w, GpuMeshBuild& out, float* bmin, float* bmax);
// Fills fills(cell, plane) for any padded cell: does it cover that whole boundary plane.
bool cell_fills(const MeshWork& w, int px, int py, int pz, int face);
// Neighbour face rules (cv_face_rule_add) of the padded cell (px, py, pz) for face f: the OR of the
// CV_FACE_RULE_* kinds whose state, face mask and neighbour set match; 0 when none. Cheap to call for any
// cell (one bit test for blocks without rules).
uint32_t face_rule_kinds(const MeshWork& w, int px, int py, int pz, int f);
// Internal mark on cv_quad::face: a cube face drawn opaque by a rule. Set only in the mesher's own lists;
// cv_mesh_cube strips it, build_gpu_mesh reads it.
constexpr uint8_t kQuadForcedOpaque = 0x80;

// The four corner lights of a cube face (u8 as MmsQuadCodec word2, FACE_VERTEX_OFFSETS order), mesher.cpp
// semantics.
void face_lights(MeshWork& w, int lx, int ly, int lz, int face, uint8_t* out4);
// VertexLightSampler.sampleCombined with the own-cell rule (model vertices), cube-local position.
float model_vertex_light(MeshWork& w, float vx, float vy, float vz, int face, int ox, int oy, int oz, bool ao);

// ---- packing (MmsQuadCodec with w/h widened to 5 bits, MmsWaterQuadCodec, COMPACT20) -----------------------
uint32_t quad16_word0(uint32_t x, uint32_t y, uint32_t z, uint32_t face);
uint32_t quad16_word1(uint32_t w, uint32_t h, uint32_t orientation, bool alpha, bool translucent, uint32_t layer);
uint32_t quad16_word2(const uint8_t light[4]);
uint32_t water_word0(uint32_t x, uint32_t y, uint32_t z, uint32_t face, bool falling, bool source, bool sheet);
uint32_t water_word1(int32_t cell_y, const float vy[4]);
uint32_t water_word2(const float f[4]);
uint32_t water_word3(uint32_t w, uint32_t h, int32_t flow, uint32_t depth, bool inset);
uint16_t float_to_half(float f);   // Java Float.floatToFloat16 (round to nearest even)
// One COMPACT20 vertex (20 bytes) appended to dst; positions region-local blocks.
void compact20_vertex(std::vector<uint8_t>& dst, float x, float y, float z, float u, float v, const float n[3],
                      float alpha, float translucent, float light, uint16_t layer);
constexpr float kCompactYShift = 512.0f;   // MmsVertexFormat.COMPACT_Y_SHIFT
constexpr float kCompactUnits = 32.0f;     // 1 / COMPACT20.positionScale

inline void put_u32(std::vector<uint8_t>& dst, uint32_t v) {
    dst.push_back(static_cast<uint8_t>(v));
    dst.push_back(static_cast<uint8_t>(v >> 8));
    dst.push_back(static_cast<uint8_t>(v >> 16));
    dst.push_back(static_cast<uint8_t>(v >> 24));
}

} // namespace cenda::voxel
