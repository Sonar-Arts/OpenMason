/* Cenda entities — the Open Mason entity model behind a C ABI, consumed by Java via
 * FFM (com.openmason.engine.entity.CendaEntities / EntityWorld). OpenMason #114 (#89 G2a).
 *
 * One ce_world per simulated world. It owns:
 *  - entity ids: 64-bit handles, (generation << 32) | (slot + 1). 0 is never a handle.
 *    A slot's generation changes when the slot is reused, so a handle kept after its
 *    entity left the world stops matching anything instead of naming a newcomer.
 *  - the lifecycle: spawn queues an entity (PENDING); ce_commit_spawns brings queued
 *    entities into the world (LIVE) in the order they were queued; despawn marks a live
 *    entity REMOVING, and ce_commit_despawns takes it out (RETIRED). Removal is always
 *    deferred, so the host can despawn while it walks the live list. A despawned PENDING
 *    entity is cancelled: it is reported neither as spawned nor as despawned.
 *    A RETIRED handle still answers ce_state and the clip calls until the NEXT
 *    ce_commit_despawns, so the host can read an entity's final state after it left.
 *  - the live order: entities are listed, and every query answers, in the order they
 *    entered the world (removal keeps the order of the rest).
 *  - entity-type descriptors the host fills in (body size, leg height, model id, model
 *    yaw offset), the defaults a spawned entity's body starts from.
 *  - bodies: position, yaw, size and 32 host-defined flag bits per entity. The host
 *    pushes them in one ce_sync_bodies call (it simulates; this is the index), and
 *    spatial queries (radius, nearest, box, body overlap) answer from the last push.
 *  - clip clocks: per entity, the animation clip it plays, whether that clip is one-shot,
 *    a state clock (reset when the clip changes) and a continuous clock. The clip time a
 *    renderer samples is the state clock for one-shot clips and the continuous clock for
 *    looping ones.
 *
 * Threading: ce_spawn and ce_despawn may be called from any thread. Every other call
 * on a world belongs to the thread that owns it (the one that commits); the commits
 * are the only point where other threads' spawns become visible.
 *
 * Distance rules: dx*dx + (dy*dy + dz*dz) with no FMA contraction (in double since ABI 2),
 * nearest compares sqrt'd distances, ties go to the entity that entered the world first.
 *
 * Coordinates (ABI 2, OpenMason #142): positions are doubles RELATIVE to the world's frame
 * origin (ce_set_origin, default 0; absolute = origin + local). The spatial index is a 3D grid of
 * cell_size-block cells keyed by ABSOLUTE int64 cell triples (no packed keys), so it is exact at any
 * distance; moving the origin shifts the stored bodies into the new frame.
 */
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CE_ABI_VERSION 2

typedef struct ce_world ce_world;
typedef uint64_t ce_handle;

/* Results. */
enum {
    CE_OK = 0,
    CE_ERR_ARG = -1,   /* null world, bad type id, bad sizes */
    CE_ERR_STALE = -2  /* the handle names no entity (never issued, or its slot was reused) */
};

/* Entity states (ce_state). */
enum {
    CE_STATE_NONE = 0,     /* stale or never issued */
    CE_STATE_PENDING = 1,  /* spawned, not committed into the world yet */
    CE_STATE_LIVE = 2,
    CE_STATE_REMOVING = 3, /* despawned, still in the world until ce_commit_despawns */
    CE_STATE_RETIRED = 4   /* left the world at the last ce_commit_despawns */
};

/* Type ids are 0 .. CE_MAX_TYPES-1. */
#define CE_MAX_TYPES 4096

/* Clip flags. */
#define CE_CLIP_ONE_SHOT 1u

/* An entity-type descriptor (24 bytes). Flags are host-defined. */
typedef struct ce_type_desc {
    float width;               /* body size on x */
    float height;              /* body height above the position */
    float length;              /* body size on z */
    float leg_height;          /* legs below the position: the body box starts at y - leg_height */
    float model_yaw_offset_deg;/* yaw between world forward (+Z) and the way the model was authored */
    uint32_t flags;
} ce_type_desc;

/* One entity's body as the host pushes it (64 bytes). */
typedef struct ce_body {
    ce_handle handle;
    double x, y, z;    /* in the world's frame (ABI 2: double) */
    float yaw;
    float width, height, length, leg_height;
    uint32_t flags;    /* host-defined; queries filter on them */
    uint32_t reserved[2]; /* 0 */
} ce_body;

/* Which entities a query accepts (24 bytes). */
typedef struct ce_filter {
    int32_t type_id;        /* -1 = any type */
    uint32_t require_flags; /* every one of these body flags must be set */
    uint32_t exclude_flags; /* none of these may be set */
    uint32_t reserved;      /* 0 */
    ce_handle exclude;      /* never answer this entity (0 = none) */
} ce_filter;

/* An entity's clip clock (16 bytes). */
typedef struct ce_clip_state {
    int32_t clip;      /* host clip id; -1 before the first ce_clip_set */
    uint32_t flags;    /* CE_CLIP_* */
    float state_time;  /* seconds since the clip last changed (as advanced) */
    float total_time;  /* the continuous clock */
} ce_clip_state;

int32_t ce_abi_version(void);
/* The calling thread's last error message ("" when none). */
const char* ce_last_error(void);

/* cell_size: the spatial grid's cell edge in blocks (<= 0 picks 8). The grid is 3D (ABI 2). */
ce_world* ce_world_create(int32_t cell_size);
void ce_world_destroy(ce_world* world);

/* Moves the frame origin (absolute cells). Stored bodies (pending ones too) are shifted by
 * old - new so they keep their absolute place; positions passed or returned afterwards are relative
 * to the new origin. Owner thread. (ABI 2) */
int32_t ce_set_origin(ce_world* world, int64_t ox, int64_t oy, int64_t oz);
int32_t ce_get_origin(const ce_world* world, int64_t* out);

/* ---- types --------------------------------------------------------------------------------- */
/* Defines or redefines a type. model_id may be NULL (the type has no model). */
int32_t ce_type_define(ce_world* world, int32_t type_id, const ce_type_desc* desc, const char* model_id);
/* 1 and *out filled when the type is defined, else 0. */
int32_t ce_type_get(const ce_world* world, int32_t type_id, ce_type_desc* out);
/* The type's model id, or NULL. Valid until the type is redefined or the world destroyed. */
const char* ce_type_model_id(const ce_world* world, int32_t type_id);

/* ---- lifecycle ----------------------------------------------------------------------------- */
/* Queues an entity of a defined type (any thread). Its body starts at the position with the
 * type's size and the given flags. Returns its handle, or 0 (bad world/type). */
ce_handle ce_spawn(ce_world* world, int32_t type_id, uint32_t flags, double x, double y, double z, float yaw);
/* Queues the entity's removal (any thread). 1 when it was PENDING (cancelled) or LIVE (now
 * REMOVING), 0 when it was already REMOVING/RETIRED or the handle is stale. */
int32_t ce_despawn(ce_world* world, ce_handle handle);
/* Brings up to cap queued entities into the world, in queue order, and writes their handles
 * to out (cap 0 commits nothing). Returns how many were committed; call again while it
 * returns cap. */
int32_t ce_commit_spawns(ce_world* world, ce_handle* out, int32_t cap);
/* Takes up to cap REMOVING entities out of the world (in the order they were despawned) and
 * writes their handles to out. Slots retired by the PREVIOUS call become reusable first. */
int32_t ce_commit_despawns(ce_world* world, ce_handle* out, int32_t cap);
int32_t ce_state(const ce_world* world, ce_handle handle);
int32_t ce_type_of(const ce_world* world, ce_handle handle); /* -1 when stale */

/* Counts: LIVE + REMOVING in the world, PENDING spawns, REMOVING despawns. */
enum { CE_COUNT_LIVE = 0, CE_COUNT_PENDING = 1, CE_COUNT_REMOVING = 2 };
int32_t ce_count(const ce_world* world, int32_t which);
/* Writes the handles of the entities in the world (LIVE and REMOVING) in live order. Returns
 * the total (may exceed cap; only cap are written). */
int32_t ce_live(const ce_world* world, ce_handle* out, int32_t cap);

/* Writes the handles of the PENDING entities (queued, not committed, not cancelled) in queue
 * order. Any thread. Returns the total (may exceed cap; only cap are written). */
int32_t ce_pending(const ce_world* world, ce_handle* out, int32_t cap);

/* ---- bodies + spatial queries -------------------------------------------------------------- */
/* Updates the bodies of entities in the world (PENDING ones too) and rebuilds the spatial index.
 * Stale or retired handles are skipped. Returns how many bodies were applied. */
int32_t ce_sync_bodies(ce_world* world, const ce_body* bodies, int32_t count);
/* 1 and *out filled (out->handle = handle) when the handle is in the world or pending. */
int32_t ce_body_get(const ce_world* world, ce_handle handle, ce_body* out);

/* Every query answers only entities in the world (LIVE, REMOVING), in live order, writes up to
 * cap handles and returns the total number of matches. filter may be NULL (accept all). */

/* Entities whose position lies within r of (x, y, z): dist^2 <= r^2. r may be +inf. */
int32_t ce_query_radius(const ce_world* world, double x, double y, double z, double r,
                        const ce_filter* filter, ce_handle* out, int32_t cap);
/* The entity whose position is nearest (x, y, z) and within max_r (+inf = unbounded), or 0.
 * *out_distance (may be NULL) receives its distance. */
ce_handle ce_query_nearest(const ce_world* world, double x, double y, double z, double max_r,
                           const ce_filter* filter, double* out_distance);
/* Entities whose position p satisfies min <= p < max on every axis (half-open box: cubes and
 * columns tile without double counting). box = {minX, minY, minZ, maxX, maxY, maxZ}. An axis
 * whose min is -inf and max is +inf is not tested at all (NaN/inf coordinates pass it). */
int32_t ce_query_box(const ce_world* world, const double box[6], const ce_filter* filter,
                     ce_handle* out, int32_t cap);
/* Entities whose body box overlaps box (strict: touching faces do not overlap). The body box is
 * [x - w/2, x + w/2] x [y - leg, y + height] x [z - l/2, z + l/2]. */
int32_t ce_query_aabb(const ce_world* world, const double box[6], const ce_filter* filter,
                      ce_handle* out, int32_t cap);
/* Entities matching the filter, wherever they are. */
int32_t ce_query_all(const ce_world* world, const ce_filter* filter, ce_handle* out, int32_t cap);

/* ---- clip clocks (PENDING, LIVE, REMOVING and RETIRED handles) ------------------------------ */
/* Plays clip: when it differs from the current clip the state clock restarts at 0. The flags
 * are stored either way. Returns CE_OK or CE_ERR_STALE. */
int32_t ce_clip_set(ce_world* world, ce_handle handle, int32_t clip, uint32_t flags);
/* total_time += dt_total; state_time += dt_state (float adds, in that order). */
int32_t ce_clip_advance(ce_world* world, ce_handle handle, float dt_total, float dt_state);
/* The time to sample the current clip at: state_time for a one-shot clip, else total_time.
 * NaN when the handle is stale. */
float ce_clip_time(const ce_world* world, ce_handle handle);
int32_t ce_clip_get(const ce_world* world, ce_handle handle, ce_clip_state* out);
/* Overwrites the whole clock (a host moving an entity's clock in or out of the world). */
int32_t ce_clip_put(ce_world* world, ce_handle handle, const ce_clip_state* state);

#ifdef __cplusplus
}
#endif
