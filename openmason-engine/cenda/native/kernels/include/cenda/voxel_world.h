/* Cenda voxel world — cubic chunks with 64-bit coordinates behind a C ABI, consumed by Java via FFM
 * (com.openmason.engine.voxel.world.CubeWorld / CendaVoxelWorld). OpenMason #141 (G5f), #143 (G5g mesher),
 * #144 (G5h surface tracker), #120 (G5a store); design in #89 (comment of 2026-11-17).
 *
 * MODEL
 *  - The world is made of CUBES of 32x32x32 blocks (CV_CUBE_EDGE). A cube is addressed by int64 cube
 *    coordinates (cx, cy, cz) = (x >> 5, y >> 5, z >> 5) with an ARITHMETIC shift (floor division), so
 *    negative coordinates work. Every block coordinate in this ABI is int64; nothing packs coordinates
 *    into bit budgets (keys are full int64 triples, hashed).
 *  - A cube stores 2x2x2 SECTIONS of 16^3 blocks. Each section is paletted: uniform (one id, no array),
 *    4-bit (<= 16 ids), 8-bit (<= 256 ids) or 16-bit direct. Block ids are uint16.
 *  - Per cube the world keeps an OCCUPANCY mask (id != air) and an OPACITY mask (block flag
 *    CV_BLOCK_OPAQUE), one uint32 row per (y, z) with bit x, updated on every write. The mesher, the
 *    visibility graph and the surface tracker read them; nothing re-derives them from ids.
 *  - Block meaning is a host BLOCK TABLE: uint16 flags per block id (CV_BLOCK_*). Ids past the table
 *    have flags 0.
 *  - The world may be BOUNDED vertically ([min_y, max_y) in blocks) or unbounded (CV_UNBOUNDED_*).
 *    Reads outside the bounds answer air; writes outside them fail with CV_ERR_ARG. Horizontally the
 *    world is always unbounded (int64).
 *  - A COLUMN object exists per (cx, cz) that has at least one cube; it holds the SURFACE TRACKER
 *    (#144): for every (x, z) the y of the first free cell above the topmost CV_BLOCK_SKY_OPAQUE block,
 *    the same quantity the column ChunkHeightMap held. It is hierarchical and persistent (column
 *    records, cv_column_encode/decode), so it answers for cubes that are generated but unloaded, and it
 *    falls back to the host SURFACE CALLBACK for space that was never generated.
 *
 * THREADING
 *  - Every call is thread-safe unless stated. Reads take shared per-cube locks, writes exclusive ones;
 *    cube insertion/removal locks a sharded map. cv_mesh_cube reads the cube and its 26 neighbours
 *    under shared locks, so it sees each cube consistently (not the whole neighbourhood atomically;
 *    a racing edit marks the cube dirty again, which re-meshes it).
 *  - The surface callback may run on any thread that calls into the world; it must not call back into
 *    the same world.
 *
 * ERRORS: negative results are CV_ERR_* (-1..-15); cv_last_error() holds a thread-local message. Calls
 * that fill caller buffers return CV_NEED(n) = -(n) - 16 when the buffer is too small (n elements of
 * the buffer's unit), so it never collides with an error code; the caller recovers n = -ret - 16, grows
 * and retries. Below, "-(needed)" always means CV_NEED(needed).
 *
 * Build rules as every Cenda domain: -ffp-contract=off, hidden dependency symbols, asan preset tests.
 */
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CV_ABI_VERSION 1

#define CV_CUBE_SHIFT 5
#define CV_CUBE_EDGE 32
#define CV_CUBE_CELLS 32768      /* 32^3 */
#define CV_SECTION_EDGE 16
#define CV_NEED(n) (-(n) - 16)
#define CV_IS_NEED(r) ((r) <= -16)
#define CV_NEEDED(r) (-(r) - 16)
#define CV_UNBOUNDED_MIN INT64_MIN
#define CV_UNBOUNDED_MAX INT64_MAX
/* Cell index inside a cube: (ly * 32 + lz) * 32 + lx, all 0..31. */
#define CV_CELL_INDEX(lx, ly, lz) ((((ly) << 10) | ((lz) << 5)) | (lx))

enum {
    CV_OK = 0,
    CV_ERR_ARG = -1,      /* null pointer, bad size, out-of-bounds write, bad id */
    CV_ERR_MISSING = -2,  /* the cube is not in the world */
    CV_ERR_EXISTS = -3,   /* the cube is already in the world */
    CV_ERR_CORRUPT = -4,  /* undecodable bytes */
    CV_ERR_IO = -5        /* store: file system error (message in cv_last_error) */
};

/* ---- block table flags ---------------------------------------------------------------------- */
#define CV_BLOCK_CUBE          0x0001u /* meshed by cv_mesh_cube as a unit cube (else: host geometry) */
#define CV_BLOCK_TRANSPARENT   0x0002u /* face-culling transparency (leaves, glass): a face is drawn
                                          against it; transparent cubes cull against the same id */
#define CV_BLOCK_OPAQUE        0x0004u /* solid for ambient occlusion and the opacity mask */
#define CV_BLOCK_SKY_OPAQUE    0x0008u /* stops sky light: counts for the surface tracker */
#define CV_BLOCK_VIS_OPAQUE    0x0010u /* closes the visibility-graph flood (full opaque cubes only) */
#define CV_BLOCK_TRANSLUCENT   0x0020u /* drawn in the translucent layer (ice); implies TRANSPARENT */
#define CV_BLOCK_HOST_GEOMETRY 0x0040u /* not a cube: listed in cv_mesh_out.host_cells for the host to
                                          mesh (stamps, snow layers, crosses, water handled by host) */
#define CV_BLOCK_DETAIL        0x0080u /* (added 2026-11-22, additive) a CUBE that also has a bound model (e.g. an
                                          ore with relief nuggets): a mesh built with CV_MESH_DETAIL draws the
                                          model (the cell is special, as an unflagged bound block), any other
                                          mesh draws the unit cube faces (cv_block_faces_set). Without a bound
                                          model the flag does nothing. Combine with CUBE (+ OPAQUE etc.) */

/* ---- world ---------------------------------------------------------------------------------- */
typedef struct cv_world cv_world;
typedef struct cv_store cv_store; /* declared here because cv_stream_desc names it (store section below) */

/* Surface of never-generated space, for the 32x32 cells of column cube (cx, cz) at [lz * 32 + lx]:
 * out_sky = y of the first free cell above the topmost SKY_OPAQUE block the generator WOULD place;
 * out_top = y of the first free cell above the topmost non-air block (fluids included, e.g. the sea
 * surface over a seabed). Return 1 when known, 0 when unknown (never-generated space is then open sky and
 * contributes no load band). */
typedef int32_t (*cv_surface_fn)(void* user, int64_t cx, int64_t cz, int64_t* out_sky, int64_t* out_top);

typedef struct cv_world_desc {
    int64_t min_y;            /* lowest block y (inclusive), or CV_UNBOUNDED_MIN */
    int64_t max_y;            /* highest block y (exclusive), or CV_UNBOUNDED_MAX */
    cv_surface_fn surface;    /* may be NULL */
    void* user;
    uint16_t air_id;          /* the empty block */
    uint16_t reserved0;       /* 0 */
    uint32_t reserved1;       /* 0 */
} cv_world_desc;              /* 40 bytes */

int32_t cv_abi_version(void);
const char* cv_last_error(void);

cv_world* cv_world_create(const cv_world_desc* desc);
void cv_world_destroy(cv_world* world);

/* Replaces the block table: flags[id] for id in [0, count). Existing cubes' masks are rebuilt. */
int32_t cv_block_table_set(cv_world* world, const uint16_t* flags, int32_t count);

/* ---- cubes ---------------------------------------------------------------------------------- */
/* Inserts a cube filled with fill_id. CV_ERR_EXISTS when present; CV_ERR_ARG when the cube lies wholly
 * outside the vertical bounds. */
int32_t cv_cube_create(cv_world* world, int64_t cx, int64_t cy, int64_t cz, uint16_t fill_id);
/* Removes a cube (its tracker contribution stays: the column remembers what it had). */
int32_t cv_cube_remove(cv_world* world, int64_t cx, int64_t cy, int64_t cz);
/* 1 present, 0 absent. */
int32_t cv_cube_exists(const cv_world* world, int64_t cx, int64_t cy, int64_t cz);
int64_t cv_cube_count(const cv_world* world);
/* Copies up to cap cube coordinates (triples) of every present cube into out; returns the count or
 * -(needed). Order unspecified. */
int64_t cv_cube_list(const cv_world* world, int64_t* out_xyz, int64_t cap);

/* Whole-cube content. ids has CV_CUBE_CELLS entries in CV_CELL_INDEX order. write_ids creates the
 * cube when absent (generation / network install). Like cv_cube_create it replaces the WHOLE cube: every
 * layer back to 0 and the blob emptied (one install = one consistent cube). */
int32_t cv_cube_write_ids(cv_world* world, int64_t cx, int64_t cy, int64_t cz, const uint16_t* ids);
int32_t cv_cube_read_ids(const cv_world* world, int64_t cx, int64_t cy, int64_t cz, uint16_t* out_ids);

/* Compact encoding of one cube (save + network body): version byte, parts mask (u32), then the parts in
 * this order. BLOCKS: 8 sections in (sy, sz, sx) order, each SINGLE (u16 id) | PALETTED (u16 count,
 * u16 ids[count], bit-packed indices in little-endian u64 words, no index spanning two words) | DIRECT
 * (u16[4096]). Each LAYER i in the mask: the same 8-section encoding of its u16 values. BLOB: u32 length +
 * bytes. Not compressed (the store and the wire compress). Returns bytes written or -(needed). */
#define CV_PART_BLOCKS      0x00000001u
#define CV_PART_LAYER(i)    (0x00000100u << (i))   /* i in [0, CV_MAX_LAYERS) */
#define CV_PART_BLOB        0x80000000u
#define CV_PARTS_SAVED      0xFFFFFFFFu            /* every part whose layer is CV_LAYER_SAVED (+ blob) */
#define CV_PARTS_NETWORK    0x7FFFFFFFu            /* blocks + every CV_LAYER_NETWORK layer, no blob */
/* Exact layout (implemented 2026-11-17, all integers little-endian): u8 version = 1; u32 parts = the parts
 * actually written (a requested layer that is 0 everywhere and an empty blob are left out; decode resets
 * them anyway, so the bytes stay canonical). Any other parts value than the two presets is an explicit
 * mask (unknown bits: CV_ERR_ARG). Each section starts with a u8 tag: 0 SINGLE, 1 PALETTED, 2 DIRECT.
 * PALETTED: count in 1..256, bits per index = ceil(log2(count)) (min 1), floor(64 / bits) indices per u64
 * word from bit 0 up, ceil(4096 / per_word) words; cells in section order (y, z, x) of 16 each. Palette
 * order = first occurrence in cell order, so equal content always encodes to equal bytes. Decode rejects
 * (CV_ERR_CORRUPT) bad versions/tags/counts, indices >= count, truncated input and trailing bytes; cells
 * outside the vertical bounds are forced to air (layers 0) as on every bulk write. */
int64_t cv_cube_encode(const cv_world* world, int64_t cx, int64_t cy, int64_t cz, uint32_t parts,
                       uint8_t* dst, int64_t cap);
/* Creates or replaces the cube from cv_cube_encode bytes; parts absent from the bytes are reset (layers 0,
 * blob empty). */
int32_t cv_cube_decode(cv_world* world, int64_t cx, int64_t cy, int64_t cz,
                       const uint8_t* src, int64_t len);

/* 1 when every cell of the cube is one id (written to *out_id), 0 otherwise, CV_ERR_MISSING. */
int32_t cv_cube_uniform(const cv_world* world, int64_t cx, int64_t cy, int64_t cz, uint16_t* out_id);

/* ---- blocks --------------------------------------------------------------------------------- */
/* KNOWN AIR: a cell of a missing cube that lies at or above its column's TOP (cv_top_y: the tracker's
 * persisted value for generated columns, the generator callback for never-generated ones) is known to be
 * air and reads as air everywhere in this ABI (cv_get, cv_read_box, mesher padding, visibility), so
 * bodies flying or falling above the terrain simulate without loading empty cubes. Only cells BELOW the
 * column top of a missing cube are unknown.
 * The block id (>= 0); air outside the vertical bounds or in known air; CV_ERR_MISSING for unknown
 * cells of a missing cube. */
int32_t cv_get(const cv_world* world, int64_t x, int64_t y, int64_t z);
/* Writes one block; returns the previous id (>= 0) or an error. Updates masks, revisions, the surface
 * tracker and the dirty set (the cube, plus every neighbour cube whose padding the cell is in). Writing the
 * id a cell already holds is a no-op (no revision, no dirt). */
int32_t cv_set(cv_world* world, int64_t x, int64_t y, int64_t z, uint16_t id);
/* Reads the box [x0, x0+sx) x [y0, y0+sy) x [z0, z0+sz) into out[((y-y0)*sz + (z-z0))*sx + (x-x0)];
 * known-air cells of missing cubes read as air; unknown cells get missing_id. Returns the number of
 * unknown cells (>= 0) or an error. Used for physics brick fills (0 unknown = cacheable). */
int64_t cv_read_box(const cv_world* world, int64_t x0, int64_t y0, int64_t z0,
                    int32_t sx, int32_t sy, int32_t sz, uint16_t* out, uint16_t missing_id);

/* ---- host layers ------------------------------------------------------------------------------ */
/* Up to CV_MAX_LAYERS per-cell uint16 channels whose meaning is the host's (fluid level + flags, block
 * state id, snow layers). Stored paletted per section exactly like blocks: a layer that is 0 across a
 * section costs nothing. Values default to 0; a cube_create/write_ids/remove resets them. */
#define CV_MAX_LAYERS 8
#define CV_LAYER_SAVED   1u  /* part of CV_PARTS_SAVED encodes */
#define CV_LAYER_NETWORK 2u  /* part of CV_PARTS_NETWORK encodes */
#define CV_LAYER_MESHED  4u  /* a write marks cubes dirty like a block write (it changes geometry) */
int32_t cv_layer_define(cv_world* world, int32_t layer, uint32_t flags);
/* Value (>= 0), CV_ERR_MISSING, or 0 outside the vertical bounds. */
int32_t cv_layer_get(const cv_world* world, int32_t layer, int64_t x, int64_t y, int64_t z);
/* Previous value (>= 0) or an error. */
int32_t cv_layer_set(cv_world* world, int32_t layer, int64_t x, int64_t y, int64_t z, uint16_t value);
int32_t cv_layer_read_cube(const cv_world* world, int32_t layer, int64_t cx, int64_t cy, int64_t cz,
                           uint16_t* out_values);
int32_t cv_layer_write_cube(cv_world* world, int32_t layer, int64_t cx, int64_t cy, int64_t cz,
                            const uint16_t* values);
/* Non-zero cells of a layer in one cube: cube-local indices + values; count or -(needed). Cheap when the
 * layer is empty (section palettes are checked first). */
int64_t cv_layer_scan(const cv_world* world, int32_t layer, int64_t cx, int64_t cy, int64_t cz,
                      uint16_t* out_index, uint16_t* out_value, int64_t cap);

/* ---- host blob ------------------------------------------------------------------------------- */
/* Opaque per-cube bytes the host owns (entities, containers) saved with the cube record. */
int32_t cv_cube_set_blob(cv_world* world, int64_t cx, int64_t cy, int64_t cz,
                         const uint8_t* src, int64_t len);
/* Bytes or -(needed); 0 when empty. */
int64_t cv_cube_get_blob(const cv_world* world, int64_t cx, int64_t cy, int64_t cz,
                         uint8_t* dst, int64_t cap);

/* ---- revisions and dirtiness ---------------------------------------------------------------- */
/* Monotonic: bumps on every block write anywhere (never reset; save/remesh bookkeeping cannot lose an
 * edit). Also bumped by cube create/write/decode/install, layer writes and blob writes; a cube's revision
 * is the world revision of its last change, so it never goes backwards even across remove + re-create. */
int64_t cv_world_revision(const cv_world* world);
/* Per-cube monotonic revision (0 when missing). */
int64_t cv_cube_revision(const cv_world* world, int64_t cx, int64_t cy, int64_t cz);
/* Moves up to cap cube coordinates (triples) that need re-meshing (content, neighbour padding or
 * surface changes) into out and clears them; returns the count or -(needed) without clearing.
 * Marking: cv_set and CV_LAYER_MESHED cv_layer_set mark the cube + the neighbours whose padding holds the
 * cell; bulk writes (create, write_ids, decode, layer_write_cube of a meshed layer) the cube + all 26
 * neighbours; cv_cube_remove the 26 neighbours; a SKY surface change at (x, z) from a to b marks the loaded
 * cubes cy in [floor((min(a,b) - 1) / 32), floor(max(a,b) / 32)] of that column and, for edge cells, of
 * the neighbour columns whose 34x34 ring holds it. TOP changes mark nothing. Only present cubes are
 * marked, but a cube removed concurrently may still be listed (meshing it answers CV_ERR_MISSING). */
int64_t cv_drain_dirty(cv_world* world, int64_t* out_xyz, int64_t cap);

/* ---- surface tracker (#144) ------------------------------------------------------------------ */
/* The y of the first free cell above the topmost sky-opaque block at (x, z): over loaded cubes, the
 * persisted record of generated-but-unloaded cubes and, above the highest generated cube, the surface
 * callback. Returns CV_OK and writes *out_y, or CV_ERR_MISSING when nothing is known (no column, no
 * callback answer); *out_y is then min_y. */
int32_t cv_surface_y(cv_world* world, int64_t x, int64_t z, int64_t* out_y);
/* The 32x32 surface values of column cube (cx, cz), out[lz * 32 + lx]. */
int32_t cv_surface_column(cv_world* world, int64_t cx, int64_t cz, int64_t* out);
/* The tracker keeps a second quantity per (x, z): TOP = y of the first free cell above the topmost
 * NON-AIR block (fluids and non-sky-opaque blocks included), from the occupancy mask, persisted and
 * called back the same way. It drives the streamer's surface band. Same conventions as cv_surface_y. */
int32_t cv_top_y(cv_world* world, int64_t x, int64_t z, int64_t* out_y);
/* Min and max TOP over the 32x32 cells of column cube (cx, cz), and the min SKY value (cheap: kept per
 * column). min(sky_min, top_min) reaches the floor under fluids (the seabed), provided fluids are not
 * CV_BLOCK_SKY_OPAQUE. */
int32_t cv_top_range(cv_world* world, int64_t cx, int64_t cz, int64_t* out_top_min, int64_t* out_top_max,
                     int64_t* out_sky_min);
/* Persistence of a column (tracker nodes, generated-cube range). Bytes or -(needed); decode creates or
 * replaces the column. A column with no record and no cubes is "never generated".
 * SEMANTICS (both channels, implemented 2026-11-17): a cube becomes GENERATED when it is created, written,
 * decoded or installed, and stays generated (with its last content's tracker leaf) after removal. The
 * answer at (x, z) is 1 + the topmost matching cell over (a) generated cubes and (b) never-generated cubes,
 * where a never-generated cell is solid iff the surface callback answered and y < its value (so a
 * never-generated cube under generated ones, below the generator surface, counts as full); min_y when
 * there is none. A callback answering 0 (or no callback) makes never-generated space open. Loaded content
 * is authoritative: decoding a record re-derives the leaves of the column's present cubes. Leaves of
 * unloaded cubes keep the block table they were computed with. Record bytes (little-endian): u8 version 1;
 * u32 n, n x (i64 lo, i64 hi) generated cube-y runs (ascending, non-adjacent); per channel (SKY, then TOP)
 * u32 groups, each: i64 key (= cy >> 4, ascending), u16 leaf mask, per set bit a packed leaf (u8 kind
 * 0 none / 1 uniform / 2..6 = 4/8/16/32/64-bit offsets, then i64 base and 1024 offsets, all-ones = none).
 * Decode validates everything (leaf values inside their cube, leaves only for generated cubes) and answers
 * CV_ERR_CORRUPT otherwise. */
int64_t cv_column_encode(const cv_world* world, int64_t cx, int64_t cz, uint8_t* dst, int64_t cap);
int32_t cv_column_decode(cv_world* world, int64_t cx, int64_t cz, const uint8_t* src, int64_t len);
/* Forgets a column (unload, after encoding it). Present cubes keep it alive: returns CV_ERR_EXISTS. */
int32_t cv_column_remove(cv_world* world, int64_t cx, int64_t cz);

/* ---- mesher (#143) --------------------------------------------------------------------------- */
/* A merged cube face (12 bytes). Coordinates are CUBE-LOCAL. The rectangle covers w cells along the
 * face's u axis and h cells along its v axis starting at (x, y, z), with the same axis convention as the
 * engine winding table (MmsCuboidGenerator.uAxis/vAxis). light[] are the four corner lights quantized
 * exactly as MmsQuadCodec word2 (u8), in FACE_VERTEX_OFFSETS corner order. */
typedef struct cv_quad {
    uint8_t x, y, z;   /* 0..31 */
    uint8_t face;      /* 0 top(+y) 1 bottom(-y) 2 north(-z) 3 south(+z) 4 east(+x) 5 west(-x) */
    uint8_t w, h;      /* 1..32 */
    uint16_t id;       /* block id */
    uint8_t light[4];
} cv_quad;

/* A cell the host meshes itself (CV_BLOCK_HOST_GEOMETRY): cube-local index + id (4 bytes). */
typedef struct cv_host_cell {
    uint16_t index;    /* CV_CELL_INDEX */
    uint16_t id;
} cv_host_cell;

#define CV_MESH_SMOOTH_LIGHTING 1u /* per-corner AO + sky interpolation (else flat per face) */
#define CV_MESH_GREEDY          2u /* merge equal faces (same id, identical quantized corner light) */
#define CV_MESH_DETAIL          4u /* (added 2026-11-22) CV_BLOCK_DETAIL blocks draw their bound model (else
                                      their cube faces); streams choose it per cube, see
                                      cv_stream_set_detail_radius */

typedef struct cv_mesh_out {
    cv_quad* opaque;          int32_t opaque_cap;      int32_t opaque_count;
    cv_quad* cutout;          int32_t cutout_cap;      int32_t cutout_count;   /* TRANSPARENT cubes */
    cv_quad* translucent;     int32_t translucent_cap; int32_t translucent_count; /* TRANSLUCENT */
    cv_host_cell* host_cells; int32_t host_cap;        int32_t host_count;
    uint16_t visibility;      /* 15 face-pair bits (Checchi): bit for (a, b) a < b, face order above,
                                 index a*6 - a*(a+1)/2 + (b - a - 1); set when an open path joins them */
    uint16_t reserved0;
    uint32_t flags_out;       /* bit 0: cube is empty of drawable geometry */
} cv_mesh_out;

/* Meshes cube (cx, cy, cz) against its 26 neighbours (a missing neighbour reads as air for culling and
 * non-solid for AO; outside the vertical bounds reads as air). Sky light per corner is the fraction of
 * the four surrounding columns whose surface y <= the corner's y, as VertexLightSampler; AO 3-tap as
 * today. Returns CV_OK, CV_ERR_MISSING, or -(needed) for the first list that overflowed (all counts are
 * still written so the caller can grow every short list in one retry). */
int32_t cv_mesh_cube(cv_world* world, int64_t cx, int64_t cy, int64_t cz, uint32_t flags,
                     cv_mesh_out* out);
/* Implemented 2026-11-17 (Phase 1b, src/voxel_world/mesh_*.cpp): culling as the old column mesher - an
 * opaque cube draws against air / TRANSPARENT / TRANSLUCENT / fluid cells; a transparent or translucent cube
 * against air or such cells of another id; plus the one-way shape rule: a face is also drawn against a
 * model-bound cell whose model does not cover the shared plane. Cells of missing neighbours (known air and
 * UNKNOWN alike) read as air, so faces toward unloaded space are emitted (cv_visible_cubes' proxies hide
 * them). Greedy merging only joins faces whose four quantized corner lights are all equal (pixel-identical);
 * merged quads have w/h up to 32. Lists are deterministic (direction, slice, then key order). Model-bound
 * and fluid cells are meshed natively (GPU path only) and are not host cells. flags_out bit0 also stays
 * clear when the cube holds model or fluid cells. */

/* Visibility of one cube without meshing it (15 bits as above); CV_ERR_MISSING. */
int32_t cv_cube_visibility(cv_world* world, int64_t cx, int64_t cy, int64_t cz);

/* Visibility-graph walk (Checchi): breadth-first from the camera cube through faces whose pair bits
 * connect, never stepping back toward the camera, limited to cubes within max_radius cubes (Chebyshev)
 * and optionally to a frustum of 6 planes (a,b,c,d) in CAMERA-CUBE-RELATIVE block coordinates (point p
 * is inside when a*px + b*py + c*pz + d >= 0 for all planes; pass NULL to skip). Known-air missing cubes
 * are open. UNKNOWN missing cubes (below their column top) are TERMINAL: they are reported in
 * out_missing (when non-NULL) and the walk does not continue through them - they are what the camera
 * would see but is not loaded (the renderer draws a proxy for them, the streamer loads them). Writes
 * visible PRESENT cube coordinates (triples) to out_xyz; returns the present count or -(needed) for
 * whichever list overflowed; *out_missing_count gets the missing count. */
int64_t cv_visible_cubes(cv_world* world, int64_t cam_cx, int64_t cam_cy, int64_t cam_cz,
                         int32_t max_radius, const float* planes6x4,
                         int64_t* out_xyz, int64_t cap,
                         int64_t* out_missing, int64_t missing_cap, int64_t* out_missing_count);
/* Implemented 2026-11-17: max_radius 0..255 (CV_ERR_ARG beyond). Cubes wholly outside the vertical bounds
 * are neither walked nor reported. Each cube is visited once (first arrival, breadth-first). A camera cube
 * that is missing and unknown is reported missing and still walked through. Visibility bits are cached per
 * cube (revision + block table), so a warm walk costs ~50 ns per reached cube (20k cubes < 1 ms). */

/* ---- host models and fluids (native host geometry) ---------------------------------------------- */
/* Non-cube geometry is meshed natively too: the host DEFINES models once (SBO stamp shapes, snow layer
 * heights, crosses), binds (block id, state value of a layer) to a model, and the stamp mesher emits them
 * with the same culling/normal/AO rules as the engine voxel.stamp emitter it replaces. Fluids are meshed
 * from a host layer (level/falling/source encoding below) into WATERQUAD16. Cells that are
 * CV_BLOCK_HOST_GEOMETRY with no bound model are still listed in cv_mesh_out.host_cells. */
typedef struct cv_model_quad {
    float pos[4][3];        /* cell-local corners, usually [0,1]; may exceed the cell (overhangs, multi-cell
                               models) within [CV_MODEL_CORNER_MIN, CV_MODEL_CORNER_MAX] per axis */
    float uv[4][2];
    float normal[3];
    uint16_t texture_layer;
    uint8_t cull_face;      /* 0..5: dropped when that neighbour face is covered; 6 = never culled */
    uint8_t flags;          /* bit0 translucent, bit1 cutout (alpha test), bit2 no AO */
} cv_model_quad;            /* 96 bytes */
/* Oversize models (widened 2026-11-17 from [-1, 2]): a corner coordinate may lie anywhere in
 * [CV_MODEL_CORNER_MIN, CV_MODEL_CORNER_MAX] = [-4, 5] of its cell, so a model may reach 4 cells past its
 * anchor cell on every side (e.g. a 3-cell stalagmite: y 0..3 upright, -2..1 hanging). cv_model_define refuses
 * (CV_ERR_ARG) any non-finite or out-of-range corner. Real limits and rules:
 *  - Encoding: COMPACT20 stores region-local positions as i16 in 1/32 block (y shifted by -512), i.e. x/z in
 *    [-1024, 1024) and y in [-512, 1536) of the region; a region's cells span [0, 256), so every accepted
 *    corner encodes in every cube of the region, including geometry that crosses into a NEIGHBOURING region
 *    (it simply lies outside [0, 256) of the anchor's region; region-local -4 / 260 are fine). The format
 *    itself would allow ~760 cells; [-4, 5] is a policy cap (bounds, culling and light accuracy below).
 *  - Culling: cull_face only applies to quads whose four corners all lie inside [-1, 2]^3 (the neighbour
 *    cell across that face can hide them). A quad reaching beyond is never culled - one neighbour cell
 *    cannot hide geometry that extends past it - and neighbour face rules (cv_face_rule_add) skip it; its
 *    cull_face (0..5) still picks the side its vertex light is sampled on. Plane coverage (the one-way shape
 *    rule) counts quads lying flat on the cell's boundary plane whatever their in-plane extent.
 *  - Light: vertex sky/AO samples read the anchor cube's 34^3 padded neighbourhood; samples further out
 *    (vertices more than one cell outside the anchor's CUBE) read as non-solid with the sky column clamped
 *    to the ring's edge - approximate, never out of bounds.
 *  - Bounds: cv_gpu_mesh.aabb_min/max cover every emitted vertex, so they can extend up to 5 blocks past the
 *    cube's 32^3 box (and past its region's 256^3 box). Fluid attachment (a fluid's bottom against the model
 *    top) uses the model's top clamped to [0, 1].
 *  - Visibility: a model is drawn with its anchor cube. cv_visible_cubes tests the cube's own 32^3 box
 *    against the frustum, so a part poking into the view from an anchor cube wholly outside the frustum is
 *    not drawn (at most 4 blocks at the screen edge); hosts that care can push their planes out by 5 blocks. */
#define CV_MODEL_CORNER_MIN (-4.0f)
#define CV_MODEL_CORNER_MAX 5.0f
int32_t cv_model_define(cv_world* world, int32_t model_id, const cv_model_quad* quads, int32_t count);
/* state_layer < 0: the model applies to every state of block_id. */
int32_t cv_block_model_bind(cv_world* world, uint16_t block_id, int32_t state_layer,
                            uint16_t state_value, int32_t model_id);

typedef struct cv_fluid_desc {
    int32_t layer;               /* host layer holding fluid cells: value = level(0..7) | falling<<3 |
                                    source<<4, 0 = no fluid; nonzero cells are fluid of block fluid_id */
    uint16_t fluid_id;           /* block id the fluid cells carry */
    uint16_t reserved0;
    float surface_by_level[8];   /* surface height in the cell per level (WaterHeightUtil.surfaceFraction) */
} cv_fluid_desc;                 /* 40 bytes */
int32_t cv_fluid_set(cv_world* world, const cv_fluid_desc* desc);
/* Fluid layer details (implemented 2026-11-17, Phase 1b): a cell is fluid when its BLOCK id is fluid_id
 * (whatever its block flags); a fluid cell whose layer value is 0 is a SOURCE (the host's "no entry" rule).
 * Extension: bits 5..8 of the value = river flow octant + 1 (0 = still water), packed into WATERQUAD16
 * word3 flow. Source and falling cells use surface_by_level[0]. Up to 4 fluids (one per fluid_id; a desc
 * with layer < 0 removes that fluid). Fluid cells are never cube-meshed nor listed as host cells. */

/* Cube face textures for the GPU packer (QUAD16 word1), added 2026-11-17 (Phase 1b): the native packer
 * must know each cube face's texture-array layer and UV orientation (MmsQuadCodec.orientation, the D4
 * frame of the face's authored UVs). faces6 in cv_quad face order. Ids never set pack layer = id,
 * orientation 0, alpha test = (TRANSPARENT && !TRANSLUCENT); the translucent bit is always the block's
 * CV_BLOCK_TRANSLUCENT flag. */
#define CV_FACE_ALPHA_TEST 1u
typedef struct cv_face_tex {
    uint16_t layer;        /* texture-array layer */
    uint8_t orientation;   /* 0..7 */
    uint8_t flags;         /* CV_FACE_ALPHA_TEST */
} cv_face_tex;             /* 4 bytes */
int32_t cv_block_faces_set(cv_world* world, uint16_t block_id, const cv_face_tex* faces6);

/* Neighbour face rules, added 2026-11-17: data-driven per-instance face decisions on top of the culling
 * above (the game's old stamp policies: translucent faces touching water drawn opaque, an ice top under
 * snow culled, cactus-stack tops culled). A rule belongs to (block_id, state) - state_layer < 0: every
 * state; else only cells whose layer state_layer holds state_value (the layer is read like a model
 * binding's) - and applies to the faces in face_mask (bit f = cv_quad face f, 0x3F = all six) whose
 * NEIGHBOUR CELL across that face holds one of neighbour_ids (count 1..65536, duplicates fine):
 *  CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN   the face is dropped (after the normal culling: rules only remove);
 *  CV_FACE_RULE_OPAQUE_WHEN_NEIGHBOUR_IN the face is drawn OPAQUE: cv_mesh_cube lists it in `opaque`
 *    (whatever the block's list), the packer writes it to quad16[0] with alpha test and translucent bits
 *    off (also overriding CV_FACE_ALPHA_TEST), model quads get alpha 0 / translucent 0. CULL wins over it.
 * Applies to cube faces (cv_mesh_cube and the GPU meshes), to model quads whose cull_face is 0..5 (the
 * face they sit on; cull_face 6 quads are never touched) and to fluid faces (CULL only: WATERQUAD16 has
 * no opaque flag). The neighbour is the padded cell, so a missing neighbour cube reads as air (as for
 * culling). Several rules of one block are OR'ed. Greedy merging never joins faces that came out
 * differently. Rules are looked up per block id (a bit per id; blocks without rules cost nothing) and
 * evaluated only for their faces that survived culling. Configuration like models: expected at startup;
 * returns CV_ERR_ARG for a bad kind / face_mask (0 or bits above 0x3F) / count / state_layer. */
#define CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN   1u
#define CV_FACE_RULE_OPAQUE_WHEN_NEIGHBOUR_IN 2u
int32_t cv_face_rule_add(cv_world* world, uint16_t block_id, int32_t state_layer, uint16_t state_value,
                         uint8_t face_mask, uint32_t kind, const uint16_t* neighbour_ids, int32_t count);
/* Removes every rule of block_id (all blocks when block_id < 0, e.g. before an asset reload). */
int32_t cv_face_rules_clear(cv_world* world, int32_t block_id);

/* ---- GPU-ready meshes ------------------------------------------------------------------------------ */
/* Region = 8x8x8 cubes (256^3 blocks), rx = floor(cx / 8). Packed meshes are REGION-local in the engine's
 * GPU formats, bit-exact with the Java codecs (engine-voxel MmsQuadCodec with w/h widened to 5 bits,
 * MmsWaterQuadCodec, MmsVertexFormat COMPACT20 for stamps), ready to memcpy into an arena/staging buffer. */
#define CV_REGION_SHIFT 3
/* Exact packing (implemented 2026-11-17, mesh_pack.cpp; region offset = (c & 7) * 32 per axis):
 *  QUAD16 word0 x:8 y:10 z:8 face:3 (region-local min cell); word1 (h-1)&15:4 | orient:3 | alpha:1 |
 *    translucent:1 | layer:16 | (w-1):5 | (h-1)>>4:1 | spare:1 (cv_block_faces_set gives layer/orient/alpha);
 *    word2 corner lights u8; word3 0.
 *  WATERQUAD16 exactly MmsWaterQuadCodec (w = h = 1, y region-local, sheet 0).
 *  COMPACT20 exactly MmsVertexFormat.encode with origin = the region origin: y stored as
 *    round((y - 512) * 32) (COMPACT_Y_SHIFT), so the region's origin attribute must keep adding the shift.
 *    Indices: 4 vertices + 6 u32 indices (0,1,2)(0,2,3) per model quad, relative to the cube's own vertices.
 *  flags bit1 = translucent quads or translucent model quads (water has its own list). */
typedef struct cv_gpu_mesh {
    int64_t cx, cy, cz;
    const uint8_t* quad16[3];  int32_t quad_count[3];   /* 0 opaque, 1 cutout, 2 translucent */
    const uint8_t* water;      int32_t water_count;     /* WATERQUAD16 records */
    const uint8_t* stamp_vertices; int32_t stamp_vertex_count;  /* COMPACT20 */
    const uint8_t* stamp_indices;  int32_t stamp_index_count;   /* u32 */
    float aabb_min[3], aabb_max[3];  /* region-local bounds of the geometry */
    uint16_t visibility;       /* as cv_mesh_out */
    uint16_t flags;            /* bit0 empty, bit1 has translucent, bit2 holds an exposed CV_BLOCK_DETAIL cell
                                  (its look depends on CV_MESH_DETAIL), bit3 built with CV_MESH_DETAIL */
    int64_t revision;          /* cube revision the mesh was built from */
} cv_gpu_mesh;

/* ---- generation provider (implemented by the GAME's native lib) ----------------------------------- */
/* The engine calls the game's generator directly on its workers; nothing crosses into Java. The game lib
 * does not link libcenda_kernels: it receives this function table. */
typedef struct cv_gen_api {
    int32_t (*get)(cv_world*, int64_t x, int64_t y, int64_t z);
    int32_t (*set)(cv_world*, int64_t x, int64_t y, int64_t z, uint16_t id);
    int32_t (*layer_get)(cv_world*, int32_t layer, int64_t x, int64_t y, int64_t z);
    int32_t (*layer_set)(cv_world*, int32_t layer, int64_t x, int64_t y, int64_t z, uint16_t v);
    int32_t (*surface_y)(cv_world*, int64_t x, int64_t z, int64_t* out_y);
    void* reserved[8];
} cv_gen_api;

typedef struct cv_generator {
    int32_t abi;               /* CV_ABI_VERSION the game lib was built against */
    int32_t populate_reach;    /* population of cube c writes into c..c+1 per axis (2x2x2); 1 */
    void* user;
    /* Never-generated surface (as cv_surface_fn: out_sky[1024], out_top[1024]). */
    int32_t (*column_surface)(void* user, int64_t cx, int64_t cz, int64_t* out_sky, int64_t* out_top);
    /* Raw terrain of one cube into ids[CV_CUBE_CELLS] and, when non-NULL, layers[i][CV_CUBE_CELLS].
     * Pure function of (seed, cx, cy, cz): may run on any worker concurrently. Return 1 = written,
     * 0 = uniform (only ids[0] meaningful: fast path for air/stone cubes), < 0 = error. */
    int32_t (*generate_cube)(void* user, int64_t cx, int64_t cy, int64_t cz,
                             uint16_t* ids, uint16_t* const* layers);
    /* Features (trees, ores, decorations) for cube c, once c..c+1 (2x2x2) are generated and installed;
     * writes through api into those 8 cubes only. Runs on a worker holding the 8 cubes for itself. */
    int32_t (*populate)(void* user, const cv_gen_api* api, cv_world* world,
                        int64_t cx, int64_t cy, int64_t cz);
    void* reserved[4];
} cv_generator;
/* Generator rules the streamer relies on (implemented 2026-11-17, #121):
 *  - The WORLD's surface callback must be the generator's column_surface (cv_world_desc.surface =
 *    generator->column_surface, .user = generator->user; cv_world_create_with_generator below does exactly
 *    that): known air, the visibility walk and the surface band
 *    of never-generated columns all read it. The streamer falls back to calling column_surface itself for
 *    the band only. For deterministic results it should be EXACT for the raw terrain (pre-population).
 *  - generate_cube: `layers` is NULL when the world defines no layer, else an array of CV_MAX_LAYERS
 *    pointers, non-NULL (zero-filled) exactly for the layers cv_layer_define'd with non-zero flags.
 *  - populate(origin o): `world` is NOT the cv_world but an opaque handle valid only for the api calls of
 *    this call. Reads (get/layer_get) and writes (set/layer_set) are confined to the 8 cubes o..o+1 that the
 *    population holds: a read outside answers CV_ERR_MISSING, a write outside CV_ERR_ARG. surface_y reads
 *    the live tracker (may reflect other populations; avoid it where determinism matters). Origins whose
 *    own cube lies outside the vertical bounds are never populated (writes beyond the bounds fail).
 *  - ORDER INDEPENDENCE: populations of different origins run concurrently (non-overlapping holds) and in
 *    timing-dependent order. The resulting world is independent of thread count and timing only when
 *    overlapping features resolve by an order-independent rule (e.g. a cell takes the highest-ranked
 *    block any feature wants there: log > leaves > air), never "last writer wins". Each origin runs exactly
 *    once per world (its flag is persisted in the stream's column record, see below). */

/* NATIVE SURFACE FACTORY (added 2026-11-17): a world whose surface callback is generator->column_surface with
 * generator->user, called directly on whatever thread asks (no host upcall) - what the rule above wants. Same
 * as cv_world_create with desc->surface/user set to those two, which desc must leave NULL (a host-implemented
 * surface goes through cv_world_create). column_surface may be NULL (no callback). NULL + cv_last_error() on
 * bad arguments or a generator built against another CV_ABI_VERSION. The generator must outlive the world. */
cv_world* cv_world_create_with_generator(const cv_world_desc* desc, const cv_generator* generator);

/* ---- streamer (#121): what exists, generation/load/mesh workers, saves ----------------------------- */
typedef struct cv_stream cv_stream;

typedef struct cv_stream_desc {
    cv_store* store;               /* NULL: nothing is loaded from or saved to disk */
    const cv_generator* generator; /* NULL: REMOTE mode (client) - cubes arrive via cv_cube_decode from the
                                      host, the streamer only meshes them and decides what to drop */
    int32_t worker_threads;        /* <= 0: hardware threads - 2, at least 1 */
    uint32_t mesh_flags;           /* CV_MESH_* | CV_STREAM_NO_MESH */
    int32_t reserved[8];
} cv_stream_desc;
/* mesh_flags bit (added 2026-11-17, additive): the stream never builds GPU meshes - a server / integrated
 * world nobody draws. No REMESHED events, no mesh jobs, cv_stream_remesh_all is a no-op; CHANGED events still
 * report content changes (physics invalidation), and COLUMN_READY means "every band cube installed and
 * COMPLETE" (all populations writing into it ran; adopted cubes are complete as installed). */
#define CV_STREAM_NO_MESH 0x80000000u

/* SURFACE-FOLLOWING load shape. A cube is wanted when its column lies inside the horizontal disc
 * (column-cube centre within radius_h + half a cube diagonal of the viewer, Euclidean in x/z) AND one of:
 *  (a) ELLIPSOID: (dx/rh)^2 + (dy/rv)^2 + (dz/rh)^2 <= 1 for the cube centre, radii inflated by half a cube
 *      diagonal (rh = radius_h, rv = radius_v) - caves, digging, building around the viewer;
 *  (b) SURFACE BAND: it intersects [min(sky_min, top_min) - band_below, top_max + band_above) of its column
 *      (cv_top_range; generator callback for never-generated columns) - the terrain, tree tops and the
 *      seabed under water, visible at any height difference;
 *  (c) VISIBLE: the stream's own visibility walk from the viewer (cv_visible_cubes without a frustum,
 *      radius = radius_h in cubes) reports it as an unknown missing cube - cave mouths in cliffs, ground
 *      under overhangs and floating builds. Iterative: once such a cube loads, its own visibility bits
 *      decide whether the walk continues past it. Walked at most every few ticks, incrementally.
 * Priority (revised 2026-11-17): ONE distance order for all rules - the cube centre's distance to the nearest
 * viewer, one cube (32 blocks) less for band cubes and the cube right above/below the band (what completes
 * the column) - so band and ellipsoid cubes interleave and the columns nearest the viewer go READY first;
 * at equal distance (b) before (a) before support before (c). Cubes entirely in known air are never wanted.
 * A column whose top is unknown has no band. Unload uses the same test with radii + one cube of
 * hysteresis (and (c) cubes stay while still reported, plus a few seconds) so cubes don't thrash. */
typedef struct cv_center {
    int64_t x, y, z;               /* block position of a viewer (player) */
    int32_t radius_h;              /* blocks */
    int32_t radius_v;              /* blocks: ellipsoid half-height */
    int32_t band_below;            /* blocks below the column top kept loaded (e.g. 16) */
    int32_t band_above;            /* blocks above the column top kept loaded (e.g. 8) */
} cv_center;                       /* 40 bytes */

typedef struct cv_budget {
    int32_t max_installs;          /* cubes installed into the world this tick */
    int32_t max_meshes;            /* GPU meshes handed out this tick */
    int64_t max_mesh_bytes;        /* bytes of handed-out meshes this tick */
    int32_t max_unloads;
    int32_t reserved[3];
} cv_budget;

/* Events of one tick. Arrays are owned by the stream and valid until the next tick on it. */
#define CV_EV_INSTALLED      1  /* cube is in the world (generated, loaded or decoded) */
#define CV_EV_UNLOAD_PENDING 2  /* cube leaves next tick: attach a blob now (cv_cube_set_blob) */
#define CV_EV_UNLOADED       3  /* cube saved (when a store exists) and removed */
#define CV_EV_REMESHED       4  /* a GPU mesh for the cube is ready: cv_stream_mesh(index) */
#define CV_EV_COLUMN_READY   5  /* every wanted band cube (b) of column cube (cx, cz) is installed and its
                                   mesh handed out (or empty): far LOD may stop drawing that column (cy 0).
                                   CV_STREAM_NO_MESH: every band cube installed and complete */
#define CV_EV_COLUMN_UNREADY 6  /* the column lost readiness (unload, band moved, re-generation): far LOD
                                   must draw it again BEFORE the detail cubes go (sent the tick before
                                   their UNLOADED events) */
#define CV_EV_CHANGED        7  /* (added 2026-11-17) an installed cube's content changed after its INSTALLED
                                   event: a population finished writing into it, or a write since the last
                                   tick (edits, host decodes over it). Both modes (meshing or NO_MESH); the
                                   physics/host-cache invalidation signal - see "CHANGED" in the rules */
typedef struct cv_event { int32_t type; int32_t mesh; int64_t cx, cy, cz; } cv_event; /* 32 bytes */

/* STREAMER RULES (implemented 2026-11-17, src/voxel_world/stream*.cpp):
 *  - LEVELS. Each plan gives every cube a level: WANTED (the load shape above), SUPPORT (local mode only:
 *    every cube within one cube of a wanted one, the 3x3x3 neighbourhood population needs; known-air support
 *    cubes are generated too - cheap uniform air - because features grow into them), KEEP (in the shape
 *    computed with hysteresis: radii, disc and band + one cube, (c) cubes reported in the last 3 s, and
 *    their support; installed cubes stay, nothing new loads) and NONE (unloaded). Plans run on the stream's
 *    planner thread (centers change, tops change, the (c) walk every 200 ms) and reach the owner tick as
 *    incremental diffs applied under a time budget.
 *  - TOPS (2026-11-17). The planner never calls the surface callback itself: a column whose TOP only the
 *    callback can answer (never generated, not asked yet) is resolved on the workers - as a job in the same
 *    distance order as generation, a little ahead of the column's cubes - and is left out of the plan until
 *    its answer arrives (its neighbours' support may still reach into it). Planning starts with the columns
 *    already known and grows as answers come in; adding a column never lowers another cube's level. The (c)
 *    walk waits until no TOP it could reach is pending. Plans and the end result are the same as with every
 *    TOP known up front.
 *  - SCHEDULING (2026-11-17). Installs beyond the tick budget go nearest first; meshes and hand-outs nearest
 *    first; populations nearest first, the nearest runnable origin taking every runnable origin overlapping
 *    it into one job (run in a row under the union of their holds) so a cube's 8 origins need not take 8
 *    ticks. In-flight work is sized from measured job times and the tick interval (about one tick of work,
 *    bounded by the owner's install cost and two ticks of the install / mesh budgets).
 *  - POPULATION / COMPLETENESS. Origin o is populated once all 8 cubes o..o+1 are installed and at least one
 *    of them is WANTED; no two populations with overlapping cubes run at once (stream-level hold set; held
 *    cubes are neither meshed nor unloaded). A cube c is COMPLETE when the 8 origins c-1..c have all run
 *    (origins outside the vertical bounds count as run) - CubicChunks' full-populator rule, with the support
 *    level guaranteeing it is always reached. Cubes adopted from the host (REMOTE decodes, host creates) are
 *    complete as they are.
 *  - MESHING. A WANTED cube is meshed once complete (local) / once its wanted neighbours arrived or 20
 *    ticks passed (REMOTE); later edits re-mesh it from the dirty set (the stream owns cv_drain_dirty: the
 *    host must not drain it). A built mesh whose revision is older than the cube's is dropped and re-queued.
 *    An empty mesh of a cube that never had geometry is not handed out (it still counts as meshed).
 *  - READINESS. COLUMN_READY when every band cube of the column (band of (b), minus known air / bounds) is
 *    installed and meshed; COLUMN_UNREADY in the same tick as (so before) the first UNLOAD_PENDING of one of
 *    the cubes it was ready with, or when its band grows onto cubes that are not ready. A column that leaves
 *    every disc stays READY until its cubes start unloading.
 *  - UNLOAD. UNLOAD_PENDING when a cube's level drops to NONE (adopted cubes: after 40 ticks); the next tick
 *    the cube is saved when changed since load/generation (cv_cube_encode(CV_PARTS_SAVED) on a worker ->
 *    cv_store_write_cube; re-saved when edited meanwhile) and removed: UNLOADED. A cube wanted again before
 *    that is kept (no UNLOADED). Columns without stream cubes outside every keep disc save their record and
 *    are removed (cv_column_remove).
 *  - RECORDS. Cube record = cv_cube_encode(CV_PARTS_SAVED) bytes, unchanged. Column record (the stream's
 *    envelope around cv_column_encode, little-endian): u32 magic 0x31535643 ("CVS1"); u32 n; n x (i64 lo,
 *    i64 hi) runs of populated origin cy (ascending, disjoint); u32 core_len; core_len bytes of
 *    cv_column_encode. A record that fails to parse is reported (errors_total) and treated as absent.
 *  - REMOTE (generator NULL): nothing is generated, loaded or saved; cubes arrive by cv_cube_decode (and
 *    column records by cv_column_decode, which the band needs) and are found through the dirty set;
 *    cv_stream_missing lists what the host should request. Everything else (meshing, events, unload) is
 *    the same.
 *  - CHANGED (added 2026-11-17). Every stream cube remembers the revision it was last reported at (its
 *    INSTALLED revision, then each CHANGED). CV_EV_CHANGED is sent when an installed cube's
 *    cv_cube_revision moved past that: (1) for every cube of a finished population whose revision moved
 *    (once per population; cubes held by a running population are reported only when it finishes, so a
 *    population is never seen half-written), and (2) for cubes the tick finds in the dirty set (block writes,
 *    CV_LAYER_MESHED layer writes, decodes/writes over an existing cube). At most one CHANGED per cube per
 *    tick, never in the tick of its INSTALLED, never for a cube that only received a neighbour's border dirt.
 *    Writes that do not dirty (non-MESHED layers, blobs) are reported only when a later dirtying write or a
 *    population reaches the cube. In meshing streams CHANGED comes in the same tick as (before) the re-mesh
 *    request; REMESHED follows later.
 *  - BUDGET: a NULL budget or a field <= 0 means unlimited. */

/* Starts desc->worker_threads workers + one planner thread. NULL + cv_last_error() on bad arguments or a
 * generator built against another CV_ABI_VERSION. The world, store and generator must outlive the stream.
 * The stream owns the world's dirty set (cv_drain_dirty) from now on. */
cv_stream* cv_stream_create(cv_world* world, const cv_stream_desc* desc);
/* Saves everything dirty (when a store exists) and flushes the store, waits for workers, destroys. Jobs not
 * started are dropped (generated-but-uninstalled cubes are simply regenerated later); running populations and
 * saves finish first. Cubes stay in the world. No other call on the stream may run. Returns the save result. */
int32_t cv_stream_destroy(cv_stream* stream);
/* Any thread. Replaces the viewer set (surface-following shape above). Server: one per player; client:
 * one. */
int32_t cv_stream_set_centers(cv_stream* stream, const cv_center* centers, int32_t count);
/* OWNER THREAD (the thread that owns the world's game state). Installs finished work within budget,
 * schedules new work by distance (nearest first), runs unloads and hands out meshes; writes events to
 * *out_events and returns their count. */
int32_t cv_stream_tick(cv_stream* stream, const cv_budget* budget, const cv_event** out_events);
/* OWNER THREAD. The GPU mesh of a CV_EV_REMESHED event, valid (with every buffer it points to) until
 * cv_stream_release_mesh or the next tick; NULL for a slot not handed out this tick. */
const cv_gpu_mesh* cv_stream_mesh(cv_stream* stream, int32_t mesh);
/* OWNER THREAD. Optional early release of a slot's memory (the next tick releases every slot). */
void cv_stream_release_mesh(cv_stream* stream, int32_t mesh);
/* Saves every dirty cube and column now (blocking), then cv_store_flush (the store commits lazily: this is
 * what makes saves durable - call it on autosave). Any thread (waits for a running tick; runs populations
 * that are in flight to completion first). CV_OK or the first store error. */
int32_t cv_stream_save_all(cv_stream* stream);
/* Any thread. The prioritized wanted-but-absent cubes of the latest plan (WANTED + SUPPORT in local mode,
 * WANTED in REMOTE mode: what the host should request from the server), nearest first, as (cx, cy, cz)
 * triples: returns the count or -(needed). Cubes installed since the plan are left out. */
int64_t cv_stream_missing(cv_stream* stream, int64_t* out_xyz, int64_t cap);
/* (added 2026-11-17) OWNER THREAD (takes the owner lock: waits for a running tick). Re-meshes every installed
 * cube - call it after a mesh-configuration change nothing dirties: cv_block_faces_set, cv_model_define /
 * cv_block_model_bind, cv_fluid_set, cv_face_rule_add / clear (cv_block_table_set already dirties every
 * cube, which re-meshes it; this call also makes sure no mesh built from the old tables is still handed out,
 * e.g. after a leaf-transparency setting). Cubes that were meshed are queued again; cubes not yet meshed keep
 * their first-mesh rules; built meshes not yet handed out and meshes still being built from the old tables are
 * dropped (never handed out) and rebuilt. The work goes through the normal queue: nearest first, bounded by the tick
 * budgets (max_meshes, max_mesh_bytes); every cube with geometry is handed out once more as REMESHED. Returns
 * the number of cubes queued (0 for a CV_STREAM_NO_MESH stream) or CV_ERR_ARG. */
int64_t cv_stream_remesh_all(cv_stream* stream);
/* (added 2026-11-22) OWNER THREAD (takes the owner lock). DETAIL RADIUS: mesh jobs of cubes whose box lies within
 * radius_blocks of a viewer (cv_stream_set_centers) are built with CV_MESH_DETAIL, the others without it, so
 * CV_BLOCK_DETAIL blocks draw their model only near a viewer and as cheap cube faces beyond. A cube already
 * meshed with detail keeps it until it lies more than radius_blocks + 8 away (hysteresis). When the viewers
 * move (or the radius changes), meshed cubes holding exposed detail cells (cv_gpu_mesh.flags bit2) whose side
 * of the radius changed are re-meshed through the normal queue. radius_blocks < 0 (the default) = every mesh
 * with CV_MESH_DETAIL (models everywhere). CV_OK or CV_ERR_ARG. */
int32_t cv_stream_set_detail_radius(cv_stream* stream, int32_t radius_blocks);
/* Counters for the debug overlay; any thread. Computed fresh when no tick/save runs at that moment, otherwise
 * the previous snapshot (never blocks behind the owner). */
typedef struct cv_stream_stats {
    int64_t cubes_loaded;        /* installed cubes the stream tracks */
    int64_t cubes_generating;    /* load/generate jobs in flight */
    int64_t cubes_meshing;       /* mesh jobs in flight */
    int64_t cubes_queued;        /* wanted/support cubes absent from the world (latest plan) */
    int64_t generated_total, loaded_total, saved_total, meshed_total;
    int64_t mesh_bytes_pending;  /* built meshes waiting for the hand-out budget */
    int64_t cubes_wanted;        /* WANTED cubes of the latest plan */
    int64_t cubes_needed;        /* WANTED + SUPPORT */
    int64_t populating, populated_total;
    int64_t installs_pending;    /* finished loads waiting for the install budget */
    int64_t meshes_pending;      /* built meshes waiting for the hand-out budget */
    int64_t unloads_pending;     /* UNLOAD_PENDING cubes not yet removed (incl. saving) */
    int64_t unloaded_total;
    int64_t columns_tracked, columns_ready;
    int64_t walk_cubes;          /* (c) cubes of the latest walks */
    int64_t plans_total, plan_pending; /* plan_pending: a plan is computing or not yet fully applied */
    int64_t plan_us_last;        /* planner thread time of the last plan */
    int64_t tick_us_last, tick_us_max;
    int64_t stale_meshes_total, errors_total;
    int64_t level_changes_total; /* cube level changes applied from plans (unchanged over 2+ plans = settled) */
    int64_t reserved[3];
} cv_stream_stats;               /* 248 bytes */
void cv_stream_stats_get(const cv_stream* stream, cv_stream_stats* out);

/* ---- test support ---------------------------------------------------------------------------------- */
/* A small deterministic generator (heightfield with sea, a cliff plateau with a tunnel, a floating overhang,
 * trees placed by populate across cube borders with an order-independent merge) used by the engine's own
 * tests and the Java binding tests. Block ids: 0 air, 1 stone, 2 grass, 3 water, 4 log, 5 leaves; layer 0
 * (when defined) gets 16 on water cells. NOT for games. */
const cv_generator* cv_test_toy_generator_create(int64_t seed);
void cv_test_toy_generator_destroy(const cv_generator* generator);

/* ---- LOD (#122) ------------------------------------------------------------------------------------ */
/* Octree LOD built from the real cubes (implemented 2026-11-17, src/voxel_world/lod*.cpp; Voxy-style nodes,
 * FastLOD-style constant on-screen bands). Additive: nothing here changes the world, the stream or the store.
 *
 * NODES. A node of level k (0..CV_LOD_MAX_LEVEL) at node coordinates (nx, ny, nz) covers the blocks
 * [n * 32 << k, (n + 1) * 32 << k) per axis (floor division, int64) and holds 32^3 CELLS of 2^k blocks each:
 * level 0 = one cube at full resolution, level k from its 8 children (2n + {0,1} per axis). Per cell: a class
 * (AIR / SOLID / FLUID), a FULL bit (every block of the cell is SOLID) and a representative block id (air id for
 * AIR cells).
 *  - Level 0 classes come from the block class table (cv_lod_block_classes; default: the air id and blocks
 *    without CV_BLOCK_CUBE -> AIR, a cv_fluid_set fluid id -> FLUID, CV_BLOCK_CUBE -> SOLID). FULL = SOLID.
 *  - DOWNSAMPLING (level k from 8 sub-cells): s SOLID, f FLUID, a AIR sub-cells. s >= 4 -> SOLID (half or more:
 *    a 1-block wall or floor survives every level as a 1-cell one, a lone pillar vanishes at level 1); else
 *    f > 0 and f >= a -> FLUID; else AIR. FULL = all 8 sub-cells FULL. Representative id of a SOLID cell: the
 *    most common id among the SOLID sub-cells of the HIGHEST sub-layer (y) that has one (the exposed top keeps
 *    its grass/snow/sand colour); ties -> the smaller id. FLUID: the most common fluid id, ties -> smaller.
 *    Deterministic and order-independent.
 *  - SOURCES of level 0 (cube c): the loaded cube (cv_world); else its record in desc.cube_store
 *    (cv_cube_encode bytes, read WITHOUT installing); else, when its COLUMN was generated (a live column or a
 *    column record in cube_store, raw or the stream's "CVS1" envelope) and c is outside the column's generated
 *    cube runs: synthesized - cells at or above the column TOP are AIR, cells below are desc.fill_id (deep rock
 *    the surface-following stream never generated). Otherwise (never-generated column, or a generated cube whose
 *    record is missing) the cube is UNKNOWN: the node is INCOMPLETE. Cells outside [desc.min_y, desc.max_y) are
 *    AIR. A node is COMPLETE when all its blocks came from those sources; incompleteness propagates to parents.
 *  - Levels >= 1 are cached (desc.cache_bytes) and PERSISTED (except incomplete all-air nodes: unexplored space,
 *    cheap to re-derive); level 0 is never persisted (it IS the cube) and uniform level-0 nodes are not cached.
 *
 * INCREMENTAL. Cube changes reach the LOD through cv_lod_note_events (the stream's INSTALLED / CHANGED /
 * REMESHED / UNLOAD_PENDING events) or cv_lod_note_cubes (hosts editing without a stream). The LOD also remembers the
 * revision of every LOADED cube it read: REMESHED / UNLOAD_PENDING of a cube still at that revision are free, and
 * a scanner thread (every 100 ms, and inside cv_lod_wait_idle) notes loaded cubes whose revision moved without
 * a note (writes no event reported). cv_lod_note_cubes of a loaded cube still at the revision the LOD read is free
 * too (hosts may note every re-mesh). Notes of a column that is not READY (still generating: its cubes and facts
 * keep moving) are HELD until it settles - COLUMN_READY, 300 ms without a note, 2 s held, or cv_lod_wait_idle -
 * then applied once. A note also re-derives its COLUMN's facts (generated runs, TOP): when they
 * moved, every cube of the column is re-noted (its synthesized deep rock / sky may differ). A note marks the
 * level-1 parent DIRTY; workers rebuild
 * dirty nodes bottom-up (a node waits while one of its children is dirty or rebuilding, so each parent is built
 * once per wave: coalesced), compare the new content digest with the old one and only then store it, mark the
 * parent dirty and re-mesh the node and its 6 same-level neighbours. A parent keeps the
 * digests of its 8 children, so an unchanged cube (a reload from disk) stops at level 1. Propagation is eager up
 * to the ACTIVE level (the policy's max_level once a view is set, else desc.max_level); a dirty node above it is
 * only remembered (persisted in "deferred.bin") and rebuilt when something reads it (cv_lod_read_node, a higher
 * policy), so one edit never rebuilds a max_level node over thousands of unexplored blocks.
 *
 * PERSISTENCE. desc.store_dir holds "lod.meta" (u32 magic "CVLM", u32 version 1, u32 node edge 32, u16 air id,
 * u16 fill id, i64 min_y, i64 max_y, u64 content_version, u32 max_level) and one cv_store per level k >= 1 in
 * "l<k>/" whose cube key is the node coordinate. Node record (little-endian, zstd by the store): u32 magic
 * "CVLN", u8 version 1, u8 level, u8 flags (bit0 complete, bit1 uniform), u8 0, i64 nx, ny, nz, u64 digest,
 * u64 child digests[8], then uniform: u16 id, u8 cell (class | FULL<<2), u8 0; else u16 ids[32768] +
 * u8 cells[32768] (CV_CELL_INDEX order). A record that fails validation is CV_ERR_CORRUPT internally (counted in
 * errors_total) and the node is rebuilt. A meta file that disagrees with desc (another version, edge, air/fill,
 * range, content_version, max_level) makes cv_lod_create fail (NULL, cv_last_error says "lod store mismatch")
 * unless desc.flags has CV_LOD_RESET_ON_MISMATCH, which deletes the level stores and starts empty. The host
 * bumps content_version whenever block classes, fill id or face texture layers change. Writes are durable at
 * cv_lod_flush / cv_lod_destroy (cv_store commit rules).
 *
 * MESHES. A node mesh is LODQUAD16 in NODE MODE (engine-voxel MmsLodQuadCodec "node mode"): all coordinates in
 * CELLS of the node (cell = 2^k blocks), relative to the node origin n * 32 << k (int64, cv_lod_mesh.origin;
 * the host makes it camera-relative in double):
 *   word0 x:9 (x + 8) | z:9 (z + 8) | y:11 (y + 1024) | face:3   - the rectangle's min corner ON ITS PLANE
 *   word1 w:6 | h:11 | layer:12 | alpha:1 | smooth:1 (0) | lit:1 - w/h in cells along the face's u/v axes
 *   word2, word3: 0 (no smooth normals in node mode)
 * Faces, u/v axes and corner order as cv_quad / MmsCuboidGenerator. layer/alpha from cv_block_faces_set (layer
 * = id when unset; layers are masked to 12 bits). Greedy merged per slice (same layer + alpha). `quads` holds
 * SOLID cell faces drawn against AIR / FLUID cells; `water` holds the TOP faces of FLUID cells under AIR (the
 * host places its water sheet relative to the cell top). The y range is node-local, so any world height works.
 * SEAMS (crack-free between levels, no skirts needed): faces inside a node cull against the node's own cells.
 * A face on the node's TOP/BOTTOM boundary culls only against a SOLID cell of the same-level node above/below
 * (stacks are always drawn at one level). A face on a SIDE boundary culls only when the neighbour node's cell is
 * FULL and every ancestor of that cell up to 5 levels coarser (aggregated inside the neighbour node with the
 * same >= 4 rule) is SOLID: whatever level 0..k+5 the neighbour is drawn at (or real cubes), that volume is
 * solid there, so a culled face can never open a hole. A missing / incomplete neighbour never culls. Every
 * vertex is on the integer cell grid, so coplanar edges meet exactly (no T-junction cracks beyond rasteriser
 * rules).
 *
 * SELECTION AND HANDOVER (cv_lod_set_view + cv_lod_tick, 2D like FastLOD and the stream's column readiness).
 * A FOOTPRINT (k, fx, fz) is the column of level-k nodes over blocks [f * 32 << k, ...) in x/z; its STACK is
 * every node of it inside [min_y, max_y). Distance d(F) = horizontal Euclidean distance from the nearest viewer
 * to the footprint rectangle. Footprints of policy.max_level within policy.radius are the roots; F is a LEAF
 * when k == 0 or d(F) >= (1 << k) * blocks_per_cell (FastLodBandPolicy; blocks_per_cell >= 46 guarantees that
 * horizontally adjacent leaves differ by at most one level). Each footprint is then covered by exactly one of:
 * READY detail (every column of F reported CV_EV_COLUMN_READY: the LOD draws nothing there), its built stack
 * (every node data COMPLETE and meshed), finer sub-footprints, or a FALLBACK (the host's own far terrain, e.g.
 * the generator heightfield, over that footprint):
 *  - never over READY detail: a footprint with some ready columns is never drawn whole, its children are
 *    resolved instead (down to level 0, where a column is either ready or not);
 *  - never a hole: until a leaf's stack is built, the finer stacks already built under it (zooming out) or the
 *    coarser stack already built over it (zooming in: "the parent until all children are ready", only while it
 *    holds no ready column) stay drawn; failing both, FALLBACK;
 *  - INCOMPLETE stacks (unexplored land) are resolved through their children for policy.incomplete_depth levels,
 *    but never below policy.incomplete_min_level (default 1: one level-1 node is as big as a level-0 one and
 *    covers four columns) - explored parts drawn, the rest FALLBACK at that level;
 *  - STANDBY: stacks of fully ready leaves with d >= policy.standby_from (< 0: never) are built and kept
 *    natively (not handed out), so a COLUMN_UNREADY switches back to the LOD in the same tick.
 * THREADS. The selection runs on the LOD's PLANNER thread, per root and only for roots something changed in; each
 * changed root becomes a prepared commit (that root's whole cover). cv_lod_tick never selects: it applies prepared
 * commits nearest first while budget.max_mesh_bytes and ~0.3 ms last (at least one per tick), and keeps the cover
 * exact on its own in between - a readiness change is patched IN THAT TICK (READY: the footprint drawn over the
 * column is replaced by built stacks / FALLBACKs of its other parts; UNREADY: the column's level-0 stack when built,
 * e.g. standby, else a level-0 FALLBACK; UNREADY arrives the tick before the detail cubes' UNLOADED, so the LOD is
 * back first), a view change fills what the new radius uncovered (built stacks / FALLBACKs) and drops roots out of
 * range, no viewers hides everything. The planner then refines those patches.
 * EVENTS are the tick's net change, all SHOW / FALLBACK_ON first, then UPDATE, then HIDE / FALLBACK_OFF: the drawn
 * set is always a complete cover - crossfade by fading SHOWn nodes in while HIDden ones fade out. Mesh bytes stay
 * valid until the next tick (cv_lod_mesh). */
#define CV_LOD_MAX_LEVEL 16
#define CV_LOD_EDGE 32
#define CV_LOD_RESET_ON_MISMATCH 1u

/* Block classes (cv_lod_block_classes). */
#define CV_LOD_CLASS_DEFAULT 0   /* derived from the block table + fluids (above) */
#define CV_LOD_CLASS_AIR     1
#define CV_LOD_CLASS_SOLID   2
#define CV_LOD_CLASS_FLUID   3
/* Cell byte of cv_lod_read_node: class (0 AIR, 1 SOLID, 2 FLUID) | CV_LOD_CELL_FULL. */
#define CV_LOD_CELL_SOLID 1u
#define CV_LOD_CELL_FLUID 2u
#define CV_LOD_CELL_FULL  4u

typedef struct cv_lod cv_lod;

typedef struct cv_lod_desc {
    const char* store_dir;       /* UTF-8 directory of the persisted nodes (levels >= 1); NULL: memory only.
                                    Must not be cube_store's directory. */
    cv_store* cube_store;        /* where unloaded cubes and columns are read (the stream's store); may be NULL */
    int64_t min_y, max_y;        /* vertical range represented, blocks; both 0: the world's bounds (required for
                                    an unbounded world); at most 8192 blocks */
    uint64_t content_version;    /* host version of everything LOD-relevant (see PERSISTENCE) */
    int64_t cache_bytes;         /* node data cache; <= 0: 256 MiB */
    int32_t max_level;           /* highest level built and persisted, 1..CV_LOD_MAX_LEVEL */
    int32_t worker_threads;      /* <= 0: 2 */
    uint32_t flags;              /* CV_LOD_RESET_ON_MISMATCH */
    uint16_t fill_id;            /* synthesized rock below the TOP of generated columns */
    uint16_t reserved0;
    int32_t reserved[4];
} cv_lod_desc;                   /* 80 bytes */

typedef struct cv_lod_policy {
    int32_t blocks_per_cell;     /* K (>= 46): level k from (1 << k) * K blocks (FastLodQuality) */
    int32_t max_level;           /* coarsest level drawn, 0..desc.max_level */
    int32_t radius;              /* blocks: footprints farther from every viewer are not covered at all */
    int32_t incomplete_depth;    /* levels to refine incomplete stacks before FALLBACK (0..8) */
    int32_t standby_from;        /* blocks (see STANDBY); < 0: no standby meshes */
    int32_t incomplete_min_level;/* finest level INCOMPLETE stacks are refined to (see SELECTION): 0 = 1 (default),
                                    CV_LOD_INCOMPLETE_LEVEL0 (-1) = 0, else 1..16 */
    int32_t reserved[2];
} cv_lod_policy;                 /* 32 bytes */
#define CV_LOD_INCOMPLETE_LEVEL0 (-1)

typedef struct cv_lod_viewer { int64_t x, y, z; } cv_lod_viewer;   /* block position, 24 bytes */

typedef struct cv_lod_budget {
    int64_t max_mesh_bytes;      /* mesh bytes handed out for non-urgent roots this tick; <= 0: unlimited */
    int32_t reserved[6];
} cv_lod_budget;                 /* 32 bytes */

#define CV_LOD_EV_SHOW         1  /* draw node (level, x, y, z) with mesh slot `mesh` (cv_lod_mesh) */
#define CV_LOD_EV_UPDATE       2  /* a drawn node's mesh changed: replace it with slot `mesh` */
#define CV_LOD_EV_HIDE         3  /* stop drawing the node (fade out, then free its GPU mesh) */
#define CV_LOD_EV_FALLBACK_ON  4  /* draw the host's own far terrain over footprint (level, x, z); y = 0 */
#define CV_LOD_EV_FALLBACK_OFF 5
typedef struct cv_lod_event {
    int32_t type;
    int32_t mesh;                /* SHOW / UPDATE slot, else -1 */
    int32_t level;
    int32_t reserved;
    int64_t x, y, z;             /* node coordinates (footprint: y = 0) */
} cv_lod_event;                  /* 40 bytes */

typedef struct cv_lod_mesh {
    int32_t level;
    int32_t cell_size;           /* blocks per cell = 1 << level */
    int64_t nx, ny, nz;
    int64_t origin[3];           /* block coordinates of the node's min corner */
    const uint8_t* quads;  int32_t quad_count;     /* LODQUAD16 node mode (16 bytes each) */
    int32_t reserved0;
    const uint8_t* water;  int32_t water_count;    /* LODQUAD16 node mode, fluid tops */
    int32_t reserved1;
    float aabb_min[3], aabb_max[3];                /* node-local cells */
    uint64_t digest;             /* content digest of the node data the mesh was built from */
    uint32_t flags;              /* bit0 empty */
    uint32_t reserved2;
} cv_lod_mesh;                   /* 128 bytes */

typedef struct cv_lod_stats {
    int64_t nodes_cached, cache_bytes;
    int64_t nodes_dirty, jobs_queued, jobs_running;
    int64_t nodes_built_total, nodes_loaded_total, nodes_saved_total, meshes_built_total;
    int64_t drawn_nodes, drawn_bytes, fallback_footprints, standby_nodes, ready_columns;
    int64_t select_us_last, tick_us_last, tick_us_max;
    int64_t errors_total, corrupt_total;
    int64_t resident_mesh_bytes; /* node meshes the LOD holds (drawn, standby, being replaced) */
    int64_t planner_states;      /* nodes the selection tracks */
    int64_t reserved[3];
} cv_lod_stats;                  /* 192 bytes; select_us_last = the planner's last pass (not in the tick) */

/* Starts the workers. NULL + cv_last_error() on bad arguments, an unopenable store_dir or a meta mismatch.
 * The world and cube_store must outlive the LOD. */
cv_lod* cv_lod_create(cv_world* world, const cv_lod_desc* desc);
/* Waits for running jobs (queued ones are dropped: still dirty nodes are rebuilt from their sources the next
 * time they are noted or requested), flushes the level stores, frees. No other call may run. Returns the first
 * store error or CV_OK. */
int32_t cv_lod_destroy(cv_lod* lod);
/* Per-block classes (CV_LOD_CLASS_*) for ids [0, count); later ids keep DEFAULT. Configuration: call before
 * the first tick (cached nodes are not rebuilt; bump content_version for persisted ones). */
int32_t cv_lod_block_classes(cv_lod* lod, const uint8_t* classes, int32_t count);

/* OWNER THREAD (the stream's). Feeds a tick's stream events (call it right after cv_stream_tick, before
 * cv_lod_tick): INSTALLED / CHANGED / REMESHED / UNLOAD_PENDING note the cube (see INCREMENTAL), COLUMN_READY /
 * COLUMN_UNREADY set column readiness, UNLOADED is ignored. */
int32_t cv_lod_note_events(cv_lod* lod, const cv_event* events, int32_t count);
/* Any thread. Cubes (triples) whose content may have changed. */
int32_t cv_lod_note_cubes(cv_lod* lod, const int64_t* xyz, int64_t count);
/* OWNER THREAD. Column readiness without a stream (what COLUMN_READY / UNREADY do). */
int32_t cv_lod_set_column_ready(cv_lod* lod, int64_t cx, int64_t cz, int32_t ready);

/* Any thread. Viewers + band policy for the next ticks (count 0: draw nothing). */
int32_t cv_lod_set_view(cv_lod* lod, const cv_lod_viewer* viewers, int32_t count, const cv_lod_policy* policy);
/* OWNER THREAD. Collects finished work, re-selects when anything changed, schedules builds (nearest first) and
 * writes this tick's events (valid until the next tick). Returns the event count or an error. */
int32_t cv_lod_tick(cv_lod* lod, const cv_lod_budget* budget, const cv_lod_event** out_events);
/* OWNER THREAD. The mesh of a SHOW / UPDATE event of the current tick; NULL for another slot. */
const cv_lod_mesh* cv_lod_mesh_get(cv_lod* lod, int32_t slot);

/* Any thread, blocking (tools/tests): the node's data now (cache, store, or built from its sources).
 * out_ids / out_cells (CV_LOD_CELL_*) take 32768 entries each in CV_CELL_INDEX order (either may be NULL).
 * Returns 1 complete, 0 incomplete, or an error. */
int32_t cv_lod_read_node(cv_lod* lod, int32_t level, int64_t nx, int64_t ny, int64_t nz,
                         uint16_t* out_ids, uint8_t* out_cells, uint64_t* out_digest);
/* Any thread, blocking: meshes the node now (neighbours as cached/stored). *out stays valid until the calling
 * thread's next cv_lod_build_mesh. CV_OK or an error. */
int32_t cv_lod_build_mesh(cv_lod* lod, int32_t level, int64_t nx, int64_t ny, int64_t nz, cv_lod_mesh* out);
/* Any thread: scans the revision watch, then blocks until no node is dirty and no job runs or is queued; repeats
 * while scans find unnoted writes (tests, shutdown). */
int32_t cv_lod_wait_idle(cv_lod* lod);
/* Any thread: commits written node records (cv_store_flush of every level store). */
int32_t cv_lod_flush(cv_lod* lod);
void cv_lod_stats_get(cv_lod* lod, cv_lod_stats* out);

/* ---- region store (#120) ------------------------------------------------------------------------ */
/* Cube records live in 3D region files of 8x8x8 cubes (r.<rx>.<ry>.<rz>.cvr), column records in 2D
 * region files of 32x32 columns (c.<rx>.<rz>.cvc), under one directory. Records are opaque bytes to the
 * store (the host composes engine + game payloads), zstd-compressed by the store, written with shadow
 * paging (a record is never overwritten in place; the header switches atomically), with a per-file
 * header that records the cube edge. Thread-safe; calls block on I/O. Format and crash rules:
 * src/voxel_store/region_file.hpp.
 *  - DURABILITY: a write is visible to reads as soon as it returns; it becomes durable at the next
 *    COMMIT of its file: cv_store_flush, cv_store_close, eviction of the file from the store's handle
 *    cache, or automatically after many writes to one file. A crash before that leaves the previous
 *    committed record (never a mix, never garbage).
 *  - ERRORS: CV_ERR_CORRUPT for a record failing its checksum, a damaged table entry, a missing external
 *    file, or a region file that is unreadable (no valid header) or incompatible (other format version /
 *    cube edge / coordinates). Writes to an incompatible file are refused (CV_ERR_CORRUPT, file kept);
 *    a write to an UNREADABLE file renames it to "<name>.corrupt" and starts a fresh one. CV_ERR_IO for
 *    file system failures. Records are at most 1 GiB (CV_ERR_ARG beyond).
 *  - One store per directory: open takes an exclusive lock (cvstore.lock); a second open fails (NULL). */
typedef struct cv_store cv_store;

/* Creates the directory when missing. NULL + cv_last_error() on failure. */
cv_store* cv_store_open(const char* dir_utf8);
/* Flushes and closes (also after an error: the handle is always freed). No other call may be running. */
int32_t cv_store_close(cv_store* store);
/* Commits every file with uncommitted writes (data fsync, header switch, fsync), fsync-bound files in
 * parallel. Returns the first error. */
int32_t cv_store_flush(cv_store* store);
/* Record bytes (decompressed) or 0 when absent, -(needed) in bytes, or an error. dst may be NULL with
 * cap 0 to ask for the size. */
int64_t cv_store_read_cube(cv_store* store, int64_t cx, int64_t cy, int64_t cz,
                           uint8_t* dst, int64_t cap);
/* len 0 deletes the record. */
int32_t cv_store_write_cube(cv_store* store, int64_t cx, int64_t cy, int64_t cz,
                            const uint8_t* src, int64_t len);
int64_t cv_store_read_column(cv_store* store, int64_t cx, int64_t cz, uint8_t* dst, int64_t cap);
int32_t cv_store_write_column(cv_store* store, int64_t cx, int64_t cz, const uint8_t* src, int64_t len);

#ifdef __cplusplus
}
#endif
