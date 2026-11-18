/* Cenda physics — voxel collision, movement, raycasts and fluids behind a C ABI, consumed by Java
 * via FFM (com.openmason.engine.voxel.physics.CendaPhysics / PhysicsWorld). OpenMason #117 (#89 G3).
 *
 * One cp_world per simulated voxel world. It owns:
 *  - a SHAPE TABLE: shape id -> up to CP_SHAPE_MAX_BOXES boxes in cell-local [0,1] coordinates,
 *    a category mask and flags. Shape 0 is always empty. The host defines what a block looks like
 *    to physics (full cube, snow layer, stair treads, a spire's tiers) once per distinct shape.
 *  - a FLUID TABLE: fluid id -> surface height inside the cell, flow force and a category mask.
 *    Fluid 0 is "no fluid".
 *  - a BRICK CACHE: the world as (shape id, fluid id) per cell, pulled from the host through
 *    cp_fill_fn one brick (2^brick_shift cells per edge, aligned to multiples of the edge) at a
 *    time, kept between calls and bounded by cache_bricks (least recently used bricks go first).
 *    Uniform bricks (all air, all stone) cost a few bytes. The host keeps it honest with the
 *    cp_invalidate_* calls: an invalidated region is refilled lazily, the next time a query
 *    touches it, with one fill call for the dirty part of each brick.
 *  - DYNAMIC BOXES: world-space boxes that are not cells (door panels posed by animation, scene
 *    colliders), with a category mask, flags and a 64-bit host value, in a grid broadphase.
 *
 * Every query takes a category MASK: a cell shape or dynamic box takes part when
 * (category & mask) != 0. Categories are host-defined (e.g. "solid", "pickable", "door cell"),
 * which is how one cache answers walking, picking and drop landing with different rules.
 *
 * Bodies are axis-aligned boxes anchored at a host point: [x - half_x, x + half_x] x
 * [y - below, y + above] x [z - half_z, z + half_z]. Movement is swept per axis in the order X, Y,
 * Z: each axis' whole displacement is clipped against every collider in its path, so a body
 * cannot tunnel through a floor or a wall at any speed or frame time. (X before Y, Z after it, is
 * the order of the per-axis resolvers this replaced; a horizontal axis sees the height the body
 * has when that axis is resolved, which decides what a jump clears.)
 *
 * Threading: cp_invalidate_* may be called from any thread (they queue). Every other call on a
 * world belongs to the thread that owns it, and the fill callback runs on that thread, inside the
 * call that needed the cells. The fill callback must not call back into the same world.
 *
 * Geometry tolerances: contacts closer than CP_CONTACT_EPSILON count as touching, so a body
 * resting on a surface, or one whose position rounds a hair into it, stays on it.
 *
 * COORDINATES (ABI 2, OpenMason #142): cells are int64 and ABSOLUTE everywhere (fill callback,
 * invalidation, cell queries, hit cells). Positions (bodies, dynamic boxes, rays, sweeps, footprints,
 * fluid sample points, hit points) are doubles RELATIVE to the world's FRAME ORIGIN (cp_set_origin,
 * default 0): absolute position = origin + local, and the cell holding a local position p is
 * origin + floor(p). All geometry runs in the local frame, so precision depends on the distance from
 * the origin, not from 0: doubles hold ~1/4000 of a block out to +-2^40 from it, and a host that keeps
 * the origin near its players (rebasing on a coarse grid) gets that precision across the whole int64
 * range. The brick cache is keyed by absolute cells (full int64 triples, hashed), so moving the origin
 * keeps every cached cell valid; dynamic boxes are shifted into the new frame.
 */
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CP_ABI_VERSION 2

typedef struct cp_world cp_world;

/* Results. */
enum {
    CP_OK = 0,
    CP_ERR_ARG = -1,   /* null world, bad id, bad sizes, non-finite input */
    CP_ERR_FULL = -2   /* a table is full */
};

#define CP_MAX_SHAPES 65536
#define CP_MAX_FLUIDS 65536
#define CP_SHAPE_MAX_BOXES 16
#define CP_CONTACT_EPSILON 1.0e-4f

/* Shape and dynamic-box flags. */
#define CP_SHAPE_PARTIAL 1u /* a body may step onto it with partial_step_height (snow, stairs) */

/* A cell-local box: {min x, min y, min z, max x, max y, max z} in [0,1] (24 bytes). Shapes. */
typedef struct cp_box {
    float min_x, min_y, min_z;
    float max_x, max_y, max_z;
} cp_box;

/* A box in the world's frame (relative to the frame origin), 48 bytes: dynamic boxes, overlap
 * tests, host hitboxes. (ABI 2) */
typedef struct cp_aabb {
    double min_x, min_y, min_z;
    double max_x, max_y, max_z;
} cp_aabb;

/* A fluid (24 bytes). */
typedef struct cp_fluid {
    float surface;        /* surface height above the cell floor, 0..1 */
    float flow_x;         /* the current's force on a body in this cell (acceleration, units/s^2) */
    float flow_y;
    float flow_z;
    uint32_t category;    /* fluid queries take a mask too */
    uint32_t reserved;    /* 0 */
} cp_fluid;

/* Fill callback: write the shape id and fluid id of every cell of the box
 * [x0, x0 + sx) x [y0, y0 + sy) x [z0, z0 + sz) (ABSOLUTE cells) to shapes[] and fluids[], at index
 * ((y - y0) * sz + (z - z0)) * sx + (x - x0). Return 1 when the cells are known, 0 when the
 * region is not loaded (the call then sees unloaded_shape / no fluid there, and nothing is
 * cached). The region always lies inside one brick. */
typedef int32_t (*cp_fill_fn)(void* user, int64_t x0, int64_t y0, int64_t z0,
                              int32_t sx, int32_t sy, int32_t sz,
                              uint16_t* shapes, uint16_t* fluids);

typedef struct cp_world_desc {
    cp_fill_fn fill;
    void* user;
    int32_t brick_shift;     /* brick edge = 1 << brick_shift; 3..5 (8..32 cells). 0 picks 4 */
    int32_t cache_bricks;    /* bricks kept before the least recently used go; <= 0 picks 2048 */
    uint16_t unloaded_shape; /* what an unloaded cell is to physics (0 = empty) */
    uint16_t reserved0;      /* 0 */
    uint32_t reserved1;      /* 0 */
} cp_world_desc;

int32_t cp_abi_version(void);
/* The calling thread's last error message ("" when none). */
const char* cp_last_error(void);

cp_world* cp_world_create(const cp_world_desc* desc);
void cp_world_destroy(cp_world* world);

/* ---- the frame ------------------------------------------------------------------------------ */
/* Moves the frame origin (absolute cell coordinates). Every position passed in or returned after
 * this call is relative to the new origin. Cached cells stay valid; dynamic boxes keep their
 * absolute place (their stored local coordinates are shifted by old - new). Owner thread. (ABI 2) */
int32_t cp_set_origin(cp_world* world, int64_t ox, int64_t oy, int64_t oz);
/* The frame origin (out = {x, y, z}). */
int32_t cp_get_origin(const cp_world* world, int64_t* out);

/* ---- tables ----------------------------------------------------------------------------- */
/* Defines or redefines shape id (1 .. CP_MAX_SHAPES-1). Boxes are clamped to the unit cell; an
 * empty box list makes the shape empty. Redefining a shape changes every cell already using it. */
int32_t cp_shape_define(cp_world* world, int32_t id, const cp_box* boxes, int32_t count,
                        uint32_t category, uint32_t flags);
/* Defines or redefines fluid id (1 .. CP_MAX_FLUIDS-1). */
int32_t cp_fluid_define(cp_world* world, int32_t id, const cp_fluid* fluid);

/* ---- the cache ---------------------------------------------------------------------------- */
/* Any thread. The region is refilled the next time a query touches it. Boxes are half-open:
 * [x0, x1) x [y0, y1) x [z0, z1). */
void cp_invalidate_cell(cp_world* world, int64_t x, int64_t y, int64_t z);
void cp_invalidate_box(cp_world* world, int64_t x0, int64_t y0, int64_t z0,
                       int64_t x1, int64_t y1, int64_t z1);
void cp_invalidate_all(cp_world* world);

/* The cell's shape id and fluid id (either out may be NULL), pulling the brick in if needed. */
int32_t cp_cell_get(cp_world* world, int64_t x, int64_t y, int64_t z,
                    uint16_t* out_shape, uint16_t* out_fluid);

/* Cache counters, for diagnostics. */
typedef struct cp_stats {
    int64_t fills;          /* fill calls made */
    int64_t filled_cells;   /* cells those calls covered */
    int64_t unloaded_fills; /* fill calls that answered "not loaded" */
    int32_t bricks;         /* bricks cached now */
    int32_t dense_bricks;   /* of those, bricks that store per-cell shapes */
    int32_t dynamic_boxes;
    int32_t reserved;
} cp_stats;
void cp_stats_get(const cp_world* world, cp_stats* out);

/* ---- dynamic boxes ------------------------------------------------------------------------ */
/* Boxes are in the world's frame. Returns a box id (> 0), or 0 on a bad argument. */
int32_t cp_box_add(cp_world* world, const cp_aabb* box, uint32_t category, uint32_t flags, uint64_t user);
int32_t cp_box_set(cp_world* world, int32_t id, const cp_aabb* box, uint32_t category, uint32_t flags);
int32_t cp_box_remove(cp_world* world, int32_t id);
void cp_box_clear(cp_world* world);

/* ---- point and cell queries --------------------------------------------------------------- */
/* Height above the cell floor of the tallest box of the cell's shape that overlaps the XZ
 * footprint [min_x, max_x] x [min_z, max_z] (frame positions, inclusive edges, clipped to the
 * cell), among shapes in mask; 0 when none does. The cell is absolute; the footprint-free form of a
 * whole cell is min = (x, z) - origin, max = min + 1. */
float cp_cell_top(cp_world* world, int64_t x, int64_t y, int64_t z,
                  double min_x, double min_z, double max_x, double max_z, uint32_t mask);
/* Whether any collider in mask (cell shape or dynamic box) overlaps the box (strictly, by more
 * than CP_CONTACT_EPSILON). */
int32_t cp_overlaps(cp_world* world, const cp_aabb* box, uint32_t mask);
/* Of count candidate cells (absolute xyz triplets), the index of the one that holds nothing in
 * mask and is nearest the preference point (a frame position; cell centre distance, compared in
 * float; ties go to the earlier candidate). With preference NULL, the first open candidate. -1 when
 * none is open. */
int32_t cp_nearest_open_cell(cp_world* world, const int64_t* cells, int32_t count,
                             const double* preference, uint32_t mask);

/* ---- bodies ------------------------------------------------------------------------------ */
/* Body flags (in and out). */
#define CP_BODY_ON_GROUND 1u        /* in: was standing; out: is standing */
#define CP_BODY_KEEP_VELOCITY 2u    /* a horizontal hit does not zero that axis' velocity (slide) */
#define CP_BODY_NO_GROUND_PROBE 4u  /* skip the probe that drops ON_GROUND after walking off an edge */
#define CP_BODY_AIR_STEP 8u         /* mantle: step rules apply while airborne too (see cp_move) */

/* Contacts (out). */
#define CP_CONTACT_NEG_X 1u
#define CP_CONTACT_POS_X 2u
#define CP_CONTACT_GROUND 4u   /* stopped moving down */
#define CP_CONTACT_CEILING 8u  /* stopped moving up */
#define CP_CONTACT_NEG_Z 16u
#define CP_CONTACT_POS_Z 32u
#define CP_CONTACT_STEPPED 64u /* climbed onto a step */
#define CP_CONTACT_LIFTED 128u /* started inside a low collider and was lifted on top of it */
#define CP_CONTACT_LEFT_GROUND 256u /* the ground probe found nothing under a standing body */

/* A body (80 bytes). */
typedef struct cp_body {
    double x, y, z;             /* anchor, in the world's frame (ABI 2: double) */
    float vx, vy, vz;           /* units/s; blocked axes are zeroed (horizontal: unless KEEP_VELOCITY) */
    float half_x, half_z;
    float below, above;         /* box spans [y - below, y + above] */
    float step_height;          /* any obstacle up to this rise is climbed while standing */
    float partial_step_height;  /* CP_SHAPE_PARTIAL obstacles up to this rise are climbed too */
    uint32_t mask;              /* collider categories */
    uint32_t flags;             /* CP_BODY_* */
    uint32_t contacts;          /* out: CP_CONTACT_* */
    uint32_t reserved[2];       /* 0 */
} cp_body;

/* Moves the body by its velocity times dt through the world, sliding along what it meets,
 * climbing steps while standing, and updating ON_GROUND. With CP_BODY_AIR_STEP an airborne body
 * that is not rising (vy <= 0: the top of a jump, or falling) and is blocked sideways also climbs
 * onto an obstacle whose top is within its step limits of its feet (as they are when that axis is
 * resolved): it ends the move standing there. While it is still rising into such an obstacle it
 * keeps that axis' velocity (the climb at the top of the jump carries its run-up). Returns CP_OK
 * or CP_ERR_ARG. */
int32_t cp_move(cp_world* world, cp_body* body, float dt);
/* Moves count bodies, each as cp_move. Returns how many moved (bad bodies are skipped). */
int32_t cp_move_many(cp_world* world, cp_body* bodies, int32_t count, float dt);

/* ---- integration (gravity, ground friction, buoyancy, flow) ------------------------------- */
typedef struct cp_integrator {
    float gravity;           /* signed acceleration on y (e.g. -40) */
    float ground_friction;   /* horizontal exp decay per second while standing (0 = none) */
    float float_submersion;  /* fraction under at neutral buoyancy (e.g. 0.55); <= 0 = no buoyancy */
    float drift_speed;       /* buoyancy alone never pushes faster than this upward */
    float fluid_drag;        /* vertical exp decay per second, scaled by submersion */
    uint32_t fluid_mask;     /* fluids that act on the body (0 = none) */
    uint32_t flags;          /* 0 */
    uint32_t reserved;       /* 0 */
} cp_integrator;

/* A fluid sample (32 bytes). */
typedef struct cp_fluid_sample {
    double surface_y;        /* frame y of the surface measured (NaN when dry) (ABI 2: double) */
    float submersion;        /* 0..1 of the body height under the surface */
    float flow_x, flow_y, flow_z; /* mean flow force over the samples that were in fluid */
    int32_t flow_samples;    /* how many of the 15 flow samples were in fluid */
    int32_t reserved;
} cp_fluid_sample;

/* Measures a body standing with its feet at feet_y, height tall, at (x, z):
 *  - submersion against the highest fluid cell (in mask) of the column under (x, z) between the
 *    feet's cell and one cell above the head's, using that cell's surface height:
 *    clamp(surface_y - feet_y, 0, height) / height;
 *  - flow as the mean force of the fluid cells found at 3 heights
 *    (flow_base_y + {0.1, 0.5 * flow_height, 0.9 * flow_height}) x 5 points (centre, and
 *    +-0.8 * half_x on x and on z). */
int32_t cp_fluid_measure(cp_world* world, double x, double feet_y, double z, float height,
                         float half_x, double flow_base_y, float flow_height, uint32_t mask,
                         cp_fluid_sample* out);

/* One step of vertical fluid forces as a pure function: weight and buoyancy as one term,
 * gravity * (1 - submersion / float_submersion), capped at drift_speed when the body was slower
 * than that, then exp(-fluid_drag * submersion * dt) damping. */
float cp_buoyancy_vy(float vy, float submersion, float dt, const cp_integrator* params);

/* Body + integration in one call, per body: measure fluid (feet y - below, height below + above,
 * flow sampled from the anchor over `above`); in fluid, buoyancy (when float_submersion > 0) and
 * the current; dry and not standing, gravity; move; ground friction while standing.
 * out_samples (may be NULL) receives each body's fluid sample. Returns how many bodies stepped. */
int32_t cp_step(cp_world* world, cp_body* bodies, int32_t count, float dt,
                const cp_integrator* params, cp_fluid_sample* out_samples);

/* ---- rays and sweeps ---------------------------------------------------------------------- */
/* Hit kinds. */
enum { CP_HIT_NONE = 0, CP_HIT_CELL = 1, CP_HIT_BOX = 2, CP_HIT_FLUID = 3 };
/* Faces (the side of the collider that was hit). */
enum { CP_FACE_NONE = -1, CP_FACE_NEG_X = 0, CP_FACE_POS_X = 1, CP_FACE_NEG_Y = 2,
       CP_FACE_POS_Y = 3, CP_FACE_NEG_Z = 4, CP_FACE_POS_Z = 5 };

/* A hit (80 bytes). */
typedef struct cp_hit {
    int32_t kind;            /* CP_HIT_* */
    int32_t face;            /* CP_FACE_* (NONE when the start was already inside) */
    int64_t x, y, z;         /* the ABSOLUTE cell hit (for a box: the cell holding the hit point) */
    int32_t id;              /* shape id, fluid id or dynamic box id */
    int32_t reserved;
    double distance;         /* along the (normalised) ray, or the sweep fraction 0..1 */
    double px, py, pz;       /* hit point in the frame (the swept box's anchor for a sweep) */
    uint64_t user;           /* dynamic box host value */
} cp_hit;

/* First collider in mask (cells by DDA, then their shape boxes, and dynamic boxes) or fluid cell
 * in fluid_mask (the whole cell) along the ray within max_distance. The direction need not be
 * normalised; distances are in world units. Returns 1 on a hit (out filled), else 0 (out->kind =
 * CP_HIT_NONE, distance = +inf). A start point inside a collider hits it at distance 0. */
int32_t cp_raycast(cp_world* world, const double origin[3], const double direction[3],
                   double max_distance, uint32_t mask, uint32_t fluid_mask, cp_hit* out);

/* Sweeps a box of half extents (hx, hy, hz) centred at from to to; the first collider in mask it
 * would touch. out->distance is the fraction of the segment travelled (0..1) and out->p* the
 * centre there. Returns 1 on a hit, else 0. */
int32_t cp_sweep(cp_world* world, const double from[3], const double to[3],
                 float hx, float hy, float hz, uint32_t mask, cp_hit* out);

/* Ray (origin + t * direction, t >= 0, direction NOT normalised: t is in units of its length)
 * against count boxes grown by margin. Each box's entry t (+inf when missed, 0 when the origin is
 * inside) is written to out_t (may be NULL). Returns the index of the box with the smallest entry
 * t <= max_t (ties to the earlier box), or -1. An axis whose direction component is below 1e-6 in
 * magnitude is a slab test on the origin alone. Host-side hitboxes (models) use this for picking
 * and for projectile segments (direction = to - from, max_t = 1). A pure function: origin and
 * boxes only need to share one frame (the host's). Math in double (ABI 2). */
int32_t cp_ray_boxes(const double origin[3], const double direction[3], double max_t,
                     const cp_aabb* boxes, int32_t count, double margin, double* out_t);

#ifdef __cplusplus
}
#endif
