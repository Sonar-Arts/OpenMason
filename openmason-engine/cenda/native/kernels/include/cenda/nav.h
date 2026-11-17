/* Cenda navigation — Wayfind route search, the search service and the agents that walk and fly the
 * routes, behind a C ABI, consumed by Java via FFM (com.openmason.engine.nav.CendaNav / NavWorld /
 * GroundAgent / AirAgent / NavProbes). OpenMason #116 (#89 G2c).
 *
 * THE WORLD AS NAVIGATION SEES IT is the physics cache (physics.h): a cn_world is created over a
 * cp_world and reads the same cell shapes and fluids bodies collide with, so a route and the body
 * walking it cannot disagree about a block. Each cell is classified once per search:
 *  - UNKNOWN: outside [min_y, max_y), or in a region the host reported not loaded (never cached by
 *    physics, so the next search asks again). Impassable to walkers; flyers may allow it.
 *  - SOLID: its shape is in solid_mask; its TOP is cp_cell_top over the whole cell (a full block 1,
 *    a snow layer or stair tread its real height).
 *  - HAZARD: its shape is in hazard_mask. Impassable.
 *  - LIQUID: its fluid is in fluid_mask; its top is the fluid's surface height.
 *  - otherwise OPEN.
 * Tops are quantised to 1/1024 of a cell per search.
 *
 * SEARCHES are A* (ties broken by f, then h, then discovery order: the same query always gives the
 * same route) over one of two domains:
 *  - GROUND: a node is the cell a walker's feet occupy; each cell has one canonical standing surface
 *    (a partial solid is stood on in its own cell, the cell above it is not standable; water is
 *    stood in at its waterline); eight horizontal moves, each landing flat, else up to the first
 *    standable cell within the climb limit, else down within the fall limit; diagonals need both
 *    flanking columns clear; water costs a wade/swim multiplier; a rise above max_step_up costs
 *    jump_cost, a smaller one step_cost, a drop fall_cost_per_block per block. The route is the
 *    collinear-collapsed node chain, as waypoints at cell centres at the feet's resting height.
 *  - AIR: a node is a cube of cell_size blocks, flyable when every cell in it is open (unknown
 *    cells make it UNKNOWN: passable at unknown_cost when allowed); 26 moves, diagonals need the
 *    face-neighbours they brush; costs: length, climb/descend surcharge, altitude_cost per cell away
 *    from cruise_y. The route is string-pulled through known-flyable cells, waypoints at cube centres.
 * Unreachable goals return the chain to the node nearest the goal (partial) — a walker should still
 * walk as close as it can.
 *
 * THE SERVICE: searches are queued (cn_search_*, and the agents' own) and run by cn_world_step on the
 * owner thread, a bounded number of expansions per step; a queue holding max_in_flight searches
 * refuses more (load sheds: the asker retries after its own back-off). Results wait for their asker.
 *
 * AGENTS carry one body each along routes:
 *  - a GROUND AGENT is told where to go (move_to, cheap to repeat every tick) and each tick it
 *    throttles searches, adopts results (dropping one whose goal has since drifted), follows the
 *    waypoints, steers (turns toward the heading at a capped rate, sets horizontal velocity, hops an
 *    obstacle in the way, strokes over a shore from water), jumps where the route planned a rise the
 *    step cannot make, notices being wedged and replans. It never moves the body itself: the tick
 *    returns what to apply (yaw, horizontal velocity, a jump or a swim stroke) and the host applies it.
 *  - an AIR AGENT routes to a horizon plan_range along the bearing to a far destination, re-planned
 *    as the flyer advances, and publishes the point to fly at; it does not steer.
 * Every number that tunes them (radii, back-offs, cooldowns, limits, speeds) comes from the host's
 * descriptor.
 *
 * THREADING: a cn_world, its agents' ticks and commands, and the probes run on the owner thread of
 * the cp_world they read. cn_stats_get may be called from any thread. Destroy the cn_world before its
 * cp_world.
 */
#pragma once

#include <stdint.h>

#include "cenda/physics.h"

#ifdef __cplusplus
extern "C" {
#endif

#define CN_ABI_VERSION 2

typedef struct cn_world cn_world;
typedef struct cn_steering cn_steering;
typedef struct cn_ground_agent cn_ground_agent;
typedef struct cn_air_agent cn_air_agent;

/* Results. */
enum {
    CN_OK = 0,
    CN_ERR_ARG = -1  /* null world/agent, bad sizes, non-finite input */
};

/* Cell flags (cn_cell). OPEN is 0. */
#define CN_CELL_UNKNOWN 1u
#define CN_CELL_SOLID 2u
#define CN_CELL_LIQUID 4u
#define CN_CELL_HAZARD 8u

/* Search outcomes. */
enum {
    CN_SEARCH_FOUND = 0,               /* reached the goal */
    CN_SEARCH_PARTIAL_BUDGET = 1,      /* ran out of expansions: chain to the best node so far */
    CN_SEARCH_PARTIAL_UNREACHABLE = 2, /* explored everything reachable: chain to the best node */
    CN_SEARCH_NO_PATH = 3,             /* nothing beat the start (boxed in), or no standable start */
    CN_SEARCH_CANCELLED = 4,
    CN_SEARCH_OUT_OF_RANGE = 5         /* start or goal outside the node range: not searched */
};

/* Bounds on one search (16 bytes). */
typedef struct cn_limits {
    int32_t max_expansions;  /* > 0 */
    float max_cost;          /* > 0; FLT_MAX for none */
    float heuristic_weight;  /* >= 1 */
    int32_t reserved;        /* 0 */
} cn_limits;

/* How a walker moves (48 bytes). */
typedef struct cn_ground_profile {
    float height;              /* standing height: headroom every cell must give */
    int32_t column_radius;     /* extra columns of clearance either side */
    float max_step_up;         /* rise walked without a jump */
    float max_climb;           /* greatest rise on land, jumping included */
    float water_escape_climb;  /* greatest rise leaving water */
    float max_fall;            /* greatest voluntary drop */
    int32_t can_swim;          /* may enter water deeper than its feet */
    float wade_cost;           /* cost multiplier, water it stands in (>= 1) */
    float swim_cost;           /* cost multiplier, water over water (>= 1) */
    float step_cost;           /* surcharge for a step up */
    float jump_cost;           /* surcharge for a jump up */
    float fall_cost_per_block; /* surcharge per block dropped */
} cn_ground_profile;

/* How a flyer routes (40 bytes). */
typedef struct cn_air_profile {
    int32_t cell_size;     /* blocks per node edge; also the route's clearance */
    float cruise_y;        /* world y the route prefers */
    float altitude_cost;   /* per cell away from cruise_y */
    float climb_cost;      /* per cell of ascent */
    float descend_cost;    /* per cell of descent */
    int32_t allow_unknown; /* route through unloaded cells */
    float unknown_cost;    /* surcharge for entering one */
    int32_t min_y;         /* lowest world y a route may occupy */
    int32_t max_y;         /* highest */
    int32_t reserved;      /* 0 */
} cn_air_profile;

typedef struct cn_world_desc {
    cp_world* physics;
    uint32_t solid_mask;    /* shape categories that occupy space */
    uint32_t hazard_mask;   /* shape categories that are impassable hazards (0 = none) */
    uint32_t fluid_mask;    /* fluid categories that are liquid */
    int32_t min_y;          /* cells below are UNKNOWN */
    int32_t max_y;          /* cells at or above are UNKNOWN */
    int32_t max_in_flight;  /* queued searches before refusing; <= 0 picks 64 */
} cn_world_desc;

int32_t cn_abi_version(void);
/* The calling thread's last error message ("" when none). */
const char* cn_last_error(void);

cn_world* cn_world_create(const cn_world_desc* desc);
/* Drops every queued search and result. */
void cn_world_destroy(cn_world* world);

/* The cell's flags (CN_CELL_*); *out_top (may be NULL) receives its quantised top (0 when OPEN or
 * UNKNOWN). */
uint32_t cn_cell(cn_world* world, int32_t x, int32_t y, int32_t z, float* out_top);

/* Runs queued searches in submission order until expansion_budget expansions have been spent this
 * step (a search always runs to its own limit once started). Returns how many ran. */
int32_t cn_world_step(cn_world* world, int32_t expansion_budget);

/* Service counters (56 bytes). Any thread. */
typedef struct cn_stats {
    int64_t submitted;    /* searches queued */
    int64_t rejected;     /* refused (queue full) */
    int64_t completed;    /* searches run */
    int64_t failed;       /* searches that failed inside */
    int64_t partial;      /* completed with a partial chain */
    int64_t search_nanos; /* time spent searching */
    int32_t in_flight;    /* queued now */
    int32_t reserved;
} cn_stats;
void cn_stats_get(const cn_world* world, cn_stats* out);

/* ---- raw searches --------------------------------------------------------------------------- */
typedef struct cn_ground_query { /* 104 bytes */
    float feet[3];         /* the walker's FEET (cell = floor(x), floor(y + 0.001), floor(z)) */
    float goal[3];         /* cell = floor(x), floor(y + 0.001), floor(z) */
    float goal_radius;     /* arrival distance in cells (0 = the exact cell) */
    int32_t snap_down;     /* start/goal are snapped onto a standable cell this far down ... */
    int32_t snap_up;       /* ... and up */
    int32_t reserved;      /* 0 */
    cn_ground_profile profile;
    cn_limits limits;
} cn_ground_query;

typedef struct cn_air_query { /* 88 bytes */
    float from[3];           /* the flyer's position (cell = floor) */
    float goal[3];
    float goal_radius;       /* in blocks */
    int32_t goal_snap_cells; /* the goal is nudged onto flyable air within this many cells */
    cn_air_profile profile;
    cn_limits limits;
} cn_air_query;

typedef struct cn_search_info { /* 24 bytes */
    int32_t status;      /* CN_SEARCH_* */
    int32_t points;      /* waypoints in the route */
    int32_t complete;    /* the route reaches the goal */
    int32_t expansions;
    float cost;
    int32_t reserved;
} cn_search_info;

/* Queue a search. Returns its id (> 0), or 0 when the queue is full. A query out of range
 * resolves at once (CN_SEARCH_OUT_OF_RANGE). */
int64_t cn_search_ground(cn_world* world, const cn_ground_query* query);
int64_t cn_search_air(cn_world* world, const cn_air_query* query);
/* 1 when done (info filled), 0 while queued, CN_ERR_ARG for an unknown id. */
int32_t cn_search_poll(cn_world* world, int64_t id, cn_search_info* info);
/* Copies up to cap waypoints (xyz) of a finished search; returns its waypoint count. */
int32_t cn_search_points(cn_world* world, int64_t id, float* xyz, int32_t cap);
/* Cancels a queued search or forgets a result. */
void cn_search_release(cn_world* world, int64_t id);

/* ---- agents -------------------------------------------------------------------------------- */
/* Agent status. */
enum {
    CN_AGENT_IDLE = 0,      /* no destination */
    CN_AGENT_SEARCHING = 1, /* a destination, no route yet */
    CN_AGENT_FOLLOWING = 2, /* walking / flying a route */
    CN_AGENT_ARRIVED = 3,   /* ground: reached the end of the route */
    CN_AGENT_NO_PATH = 4,   /* no route there (ground: for now; air: flying the bearing) */
    CN_AGENT_STUCK = 5      /* ground: wedged, replanning */
};

/* Body flags (in). */
#define CN_BODY_ON_GROUND 1u
#define CN_BODY_IN_WATER 2u
#define CN_BODY_GLIDING 4u  /* floats down slowly: over a waypoint well below it, the agent holds and
                               lets it sink rather than overshooting (ABI 2) */

/* A ground body as it stands this tick (48 bytes). */
typedef struct cn_body {
    float x, y, z;             /* origin (the top of the legs) */
    float yaw;                 /* degrees */
    float leg_height;          /* feet = y - leg_height */
    float half_width;          /* half the body's x extent (obstacle reach) */
    float half_length;         /* half its z extent */
    float move_speed;          /* base speed */
    float speed_multiplier;    /* the body's own multiplier (status effects) */
    float water_speed_factor;  /* speed factor while in water */
    float swim_stroke_reach;   /* how high a swim stroke carries it */
    uint32_t flags;            /* CN_BODY_* */
} cn_body;

/* What to apply to the body, in this order: yaw, jump/stroke, horizontal velocity (24 bytes). */
#define CN_APPLY_YAW 1u
#define CN_APPLY_VELOCITY 2u   /* set horizontal velocity to (vx, vz) */
#define CN_APPLY_JUMP 4u       /* jump (it was standing) */
#define CN_APPLY_SWIM_UP 8u    /* a swim stroke (it was in water) */
typedef struct cn_apply {
    uint32_t apply;
    float yaw;
    float vx, vz;
    uint32_t reserved[2];
} cn_apply;

/* An agent's state after a call (32 bytes). path_version changes whenever the route changes. */
typedef struct cn_agent_view {
    int32_t status;       /* CN_AGENT_* */
    int32_t cursor;       /* waypoint being walked / flown toward */
    int32_t has_goal;
    int32_t path_version;
    float goal_x, goal_y, goal_z; /* the destination (when has_goal) */
    int32_t path_points;
} cn_agent_view;

/* Steering: how a body turns, hops and strokes. Its state (jump cooldown, hop boost) lives
 * in a cn_steering, shared by the ground agent that steers along routes and by host code that steers
 * the body directly (fleeing straight away, paddling at a bank). */
typedef struct cn_steering_desc { /* 44 bytes */
    float rotation_speed;          /* degrees per second */
    float hop_boost_speed;         /* horizontal speed mid-hop (<= 0: no boost) */
    float hop_duration;            /* hop boost safety cap, seconds (ends on landing) */
    float jump_cooldown;
    float swim_stroke_cooldown;
    float obstacle_look_ahead;     /* probe gap past the leading edge */
    float foot_probe_lift;         /* probe height above the feet */
    int32_t max_shore_probe_cells; /* how far up a shore is measured */
    float model_yaw_offset;        /* degrees added to the travel direction's yaw */
    uint32_t collide_mask;         /* shape categories the obstacle probe treats as solid */
    float step_height;             /* the body's own auto-step: at a full block or more, an obstacle
                                      one block high is stepped, never hopped (ABI 2) */
} cn_steering_desc;

typedef struct cn_ground_agent_desc { /* 112 bytes */
    cn_ground_profile profile;
    cn_limits limits;
    int32_t snap_down, snap_up;    /* the search's start/goal snapping */
    float repath_interval;         /* min seconds between searches */
    float no_path_retry;           /* back-off after a search found nothing */
    float saturated_retry;         /* back-off when the queue refused */
    float goal_drift;              /* goal movement that invalidates a route */
    float waypoint_radius;         /* horizontal distance that passes a waypoint */
    float waypoint_vertical_slack; /* vertical slack when passing one */
    float stuck_speed;             /* below this (blocks/s) a following body is not moving */
    float stuck_seconds;           /* that long and it is wedged */
    float jump_rise_margin;        /* jump where the next waypoint rises above max_step_up + this */
    float jump_approach;           /* ... within this horizontal distance of it */
} cn_ground_agent_desc;

typedef struct cn_air_agent_desc { /* 96 bytes */
    cn_air_profile profile;   /* cruise_y is the agent's cruise altitude, set per agent */
    cn_limits limits;
    float plan_range;         /* how far ahead one search plans */
    float replan_travel;      /* distance flown before re-planning */
    float replan_interval;    /* min seconds between searches */
    float no_path_retry;
    float saturated_retry;
    float waypoint_radius;    /* distance that passes a waypoint */
    float goal_radius;        /* the search's arrival radius, in blocks */
    int32_t goal_snap_cells;  /* the search's goal nudge */
    int32_t reserved[2];      /* 0 */
} cn_air_agent_desc;

/* Ground agents. The body position seeds the stuck detector. */
cn_ground_agent* cn_ground_agent_create(const cn_ground_agent_desc* desc, float x, float y, float z);
/* Frees the agent (a search it still had queued is dropped when its world next steps or expires). */
void cn_ground_agent_destroy(cn_ground_agent* agent);
/* Sets where to go; cheap to repeat every tick. goal_radius: arrival distance; speed: multiplier on
 * the body's speed while walking this route. */
int32_t cn_ground_agent_move_to(cn_ground_agent* agent, float x, float y, float z, float goal_radius,
                                float speed, cn_agent_view* view);
/* Abandons the destination and the route (the world may be NULL); writes a stop to out. */
int32_t cn_ground_agent_stop(cn_ground_agent* agent, cn_world* world, cn_apply* out, cn_agent_view* view);
/* Drops the route, keeps the destination, searches again at once. */
int32_t cn_ground_agent_replan(cn_ground_agent* agent, cn_world* world, cn_agent_view* view);
/* One tick: steering's own tick (cooldowns), then the route. steering: the body's (required); world:
 * the service (NULL when there is none); physics: for the obstacle probe (NULL: no probe). out: what
 * to apply to the body. */
int32_t cn_ground_agent_tick(cn_ground_agent* agent, cn_steering* steering, cn_world* world, cp_world* physics,
                             const cn_body* body, float dt, cn_apply* out, cn_agent_view* view);
/* Copies up to cap waypoints (xyz) of the current route; returns its waypoint count. */
int32_t cn_ground_agent_path(const cn_ground_agent* agent, float* xyz, int32_t cap, int32_t* out_complete);

/* Air agents. cruise starts at y. */
cn_air_agent* cn_air_agent_create(const cn_air_agent_desc* desc, float x, float y, float z);
void cn_air_agent_destroy(cn_air_agent* agent);
void cn_air_agent_set_cruise(cn_air_agent* agent, float cruise_y);
int32_t cn_air_agent_move_to(cn_air_agent* agent, float x, float y, float z, cn_agent_view* view);
int32_t cn_air_agent_stop(cn_air_agent* agent, cn_world* world, cn_agent_view* view);
int32_t cn_air_agent_replan(cn_air_agent* agent, cn_world* world, cn_agent_view* view);
/* One tick at the flyer's position. */
int32_t cn_air_agent_tick(cn_air_agent* agent, cn_world* world, float x, float y, float z, float dt,
                          cn_agent_view* view);
/* The point to fly at from (x, y, z): the next waypoint, or the horizon while there is no route. */
int32_t cn_air_agent_steer_target(const cn_air_agent* agent, float x, float y, float z, float* out_xyz);
int32_t cn_air_agent_path(const cn_air_agent* agent, float* xyz, int32_t cap, int32_t* out_complete);

/* ---- steering ---------------------------------------------------------------------------------- */
cn_steering* cn_steering_create(const cn_steering_desc* desc);
void cn_steering_destroy(cn_steering* steering);
/* Advances the cooldowns; the hop boost ends when the body stands. Call once per tick before steering
 * (cn_ground_agent_tick does). */
int32_t cn_steering_tick(cn_steering* steering, int32_t on_ground, float dt);
/* One tick of steering along the normalised horizontal heading (dir_x, dir_z): turn toward it at the
 * rotation speed, hop an obstacle in the way (allow_jump; from water: stroke over a shore a stroke
 * can top), and set horizontal velocity = heading * (hop boost while hopping, else move_speed *
 * speed_multiplier * the body's own multiplier, times the water factor in water). physics may be
 * NULL (no probe). */
int32_t cn_steering_steer(cn_steering* steering, cp_world* physics, const cn_body* body, float dir_x, float dir_z,
                          float speed_multiplier, float dt, int32_t allow_jump, cn_apply* out);
/* Gets the body upward: a swim stroke in water, a jump when standing, sharing one cooldown. Returns 1
 * when it pushed (out says which), 0 when not. */
int32_t cn_steering_request_jump(cn_steering* steering, const cn_body* body, cn_apply* out);

/* Pure helpers. */
/* The yaw (degrees) that points a model along the horizontal direction (dir_x, dir_z). */
float cn_steer_yaw_for(float dir_x, float dir_z, float model_yaw_offset);
/* yaw turned toward target along the shortest arc by at most max_step degrees. */
float cn_steer_turn(float yaw, float target_yaw, float max_step);
/* Degrees left to turn from yaw to target along the shortest arc (>= 0). */
float cn_steer_yaw_error(float yaw, float target_yaw);

/* ---- probes over the physics cache ------------------------------------------------------------ *
 * A cell BLOCKS when its shape is in mask and its top over the whole cell is a full block. Unloaded
 * cells read as the physics world's unloaded shape. Owner thread. */
/* Top of the first blocking cell with a non-blocking cell above it, scanning down from
 * floor(start_y) + scan_up to floor(start_y) - scan_down in the column at (x, z): that cell's y + 1.
 * -INFINITY when none. */
float cn_probe_ground_level(cp_world* physics, float x, float z, float start_y, int32_t scan_up,
                            int32_t scan_down, uint32_t mask);
/* y + 1 of the highest cell holding a fluid in fluid_mask from floor(y) + scan_up down to
 * floor(y) - scan_down in the column at (x, z); -INFINITY when none. */
float cn_probe_fluid_top(cp_world* physics, float x, float y, float z, int32_t scan_up, int32_t scan_down,
                         uint32_t fluid_mask);
/* 1 when no blocking cell touches the cube of half extent radius around (x, y, z). */
int32_t cn_probe_clear(cp_world* physics, float x, float y, float z, float radius, uint32_t mask);
/* y + 1 of the highest blocking cell within range of floor(from_y) in the column; -INFINITY when none. */
float cn_probe_column_peak(cp_world* physics, float x, float z, float from_y, int32_t range, uint32_t mask);

/* ---- reach ------------------------------------------------------------------------------------- *
 * Every cell a walker can reach, by the same moves a ground search makes: from the start (snapped
 * like a ground search's start), cheapest first (ties: discovery order), within radius columns of it
 * (Chebyshev, horizontal), at most max_cells of them, none costing more than max_cost. The host
 * applies its own goal ("any cell under open sky", "is this site reachable") to the result. Owner
 * thread of the world's physics. (ABI 2) */
typedef struct cn_reach_query { /* 80 bytes */
    float feet[3];         /* the walker's feet (cell = floor(x), floor(y + 0.001), floor(z)) */
    int32_t snap_down;     /* the start snaps onto a standable cell this far down ... */
    int32_t snap_up;       /* ... and up */
    int32_t radius;        /* columns either side of the start that may be entered */
    int32_t max_cells;     /* cells expanded (> 0) */
    float max_cost;        /* FLT_MAX for none */
    cn_ground_profile profile;
} cn_reach_query;

typedef struct cn_reach_cell { /* 24 bytes */
    int32_t x, y, z;       /* the cell the walker's feet occupy */
    int32_t parent;        /* index of the cell it was reached from (-1 for the start) */
    float surface;         /* world y the feet rest at */
    float cost;            /* route cost from the start */
} cn_reach_cell;

/* Writes the reached cells in the order they were settled (cheapest first) to out, the first cap of
 * them; returns how many were settled (0 when the start has nowhere to stand, CN_ERR_ARG on a bad
 * query). A parent always comes before its children. */
int32_t cn_reach_ground(cn_world* world, const cn_reach_query* query, cn_reach_cell* out, int32_t cap);

/* ---- flood ------------------------------------------------------------------------------------ *
 * A bounded breadth-first flood over the six face neighbours, for reachability questions that are not
 * routes ("which leaves can still reach a log"): from the start cell (always expanded, whatever it
 * holds), a neighbour is entered when its y is in [min_y, max_y), its shape's category intersects
 * pass_mask, and it is at most max_steps steps from the start. Cells expand in breadth-first order
 * (by distance, then discovery; neighbours in the order +x, -x, +y, -y, +z, -z), at most max_cells of
 * them. Owner thread of the physics world. */
typedef struct cn_flood_query { /* 32 bytes */
    int32_t x, y, z;     /* start cell */
    int32_t max_steps;   /* farthest distance entered (0 .. 2^20 - 1) */
    int32_t max_cells;   /* expansion cap (> 0) */
    uint32_t pass_mask;  /* shape categories that can be entered */
    int32_t min_y;       /* cells outside [min_y, max_y) are never entered */
    int32_t max_y;
} cn_flood_query;

/* Writes the expanded cells in expansion order as (x, y, z, category) int quadruples, the first cap of
 * them (category = the cell's shape category word, 0 when it holds none); returns how many expanded
 * (CN_ERR_ARG on a bad query). */
int32_t cn_flood(cp_world* physics, const cn_flood_query* query, int32_t* out, int32_t cap);

#ifdef __cplusplus
}
#endif
