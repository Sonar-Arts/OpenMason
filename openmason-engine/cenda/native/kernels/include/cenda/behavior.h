/* Cenda behaviour scheduler — decides which of an agent's behaviours run, behind a C ABI, consumed by
 * Java via FFM (com.openmason.engine.entity.CendaBehaviors / BehaviorScheduler). OpenMason #115
 * (#89 G2b).
 *
 * An agent's personality is a list of small behaviours (wander, flee, graze, ...). The behaviours are
 * the host's content: the engine never sees what they do, only three facts about each — a PRIORITY
 * (lower runs first: 0 is the most urgent), a WEIGHT (relative likelihood among equal priorities) and
 * the FLAGS it needs exclusive use of while running (host-defined bits, e.g. "move" and "look").
 *
 * One scheduler tick is:
 *  1. stop every running behaviour whose should_continue is false (newest first);
 *  2. for each priority group, lowest first: repeatedly gather the behaviours of the group that are
 *     not running, FIT (no running behaviour of equal or more urgent priority holds a flag they
 *     need) and can_start, in list order; pick one (by weight when several: one host random roll in
 *     [0,1), scaled by the sum of the non-negative weights, walked in list order), stop every running
 *     behaviour holding a flag it needs (PREEMPTION, newest first), add it as the newest running
 *     behaviour and start it; until nothing in the group can start;
 *  3. tick every running behaviour, oldest first. A tick may change what is running (re-entering
 *     cb_scheduler_stop_all): the loop re-reads the running list after every tick.
 * List order = the order given at create, stably sorted by priority. Behaviours whose flags do not
 * overlap run together.
 *
 * The host answers through ONE callback (cb_host_fn): the predicates, the start/stop/tick hooks and
 * the random roll, so the order of every host call — and so of every random number the host's
 * behaviours draw — is fixed by the rules above. Behaviour indices in callbacks and results are the
 * ones given at create (not sorted positions). A negative callback result aborts the call
 * (CB_ERR_HOST) with the scheduler's state as it was at that point: the behaviour being stopped is
 * already out of the running list, the one being started already in it.
 *
 * Threading: a scheduler belongs to one thread at a time (the one simulating its agent).
 */
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CB_ABI_VERSION 1

typedef struct cb_scheduler cb_scheduler;

/* Results. */
enum {
    CB_OK = 0,
    CB_ERR_ARG = -1,  /* null scheduler/host, bad index, bad sizes */
    CB_ERR_HOST = -2  /* a host callback failed (returned a negative value) */
};

#define CB_MAX_BEHAVIORS 4096

/* A behaviour as the scheduler sees it (16 bytes). */
typedef struct cb_behavior_desc {
    int32_t priority; /* lower runs first */
    float weight;     /* relative likelihood among equal priorities; <= 0 counts as 0 */
    uint32_t flags;   /* what it holds exclusively while running (host-defined bits) */
    uint32_t reserved; /* 0 */
} cb_behavior_desc;

/* Host operations. */
enum {
    CB_OP_SHOULD_CONTINUE = 0, /* return 1 to keep running, 0 to stop */
    CB_OP_CAN_START = 1,       /* return 1 when it can begin, 0 when not */
    CB_OP_START = 2,           /* return 0 */
    CB_OP_STOP = 3,            /* return 0 */
    CB_OP_TICK = 4,            /* return 0; dt is the tick's delta time */
    CB_OP_RANDOM = 5           /* write a uniform float in [0,1) to *out_random, return 0 (index = -1) */
};

/* The host: op on behaviour index (as given at create). A negative return aborts the scheduler call. */
typedef int32_t (*cb_host_fn)(void* user, int32_t op, int32_t index, float dt, float* out_random);

int32_t cb_abi_version(void);
/* The calling thread's last error message ("" when none). */
const char* cb_last_error(void);

/* A scheduler over count behaviours (0 .. CB_MAX_BEHAVIORS). NULL on a bad argument. */
cb_scheduler* cb_scheduler_create(const cb_behavior_desc* behaviors, int32_t count);
void cb_scheduler_destroy(cb_scheduler* scheduler);

/* One tick (see the header note). */
int32_t cb_scheduler_tick(cb_scheduler* scheduler, cb_host_fn host, void* user, float dt);
/* Stops every running behaviour, newest first. */
int32_t cb_scheduler_stop_all(cb_scheduler* scheduler, cb_host_fn host, void* user);

/* The most urgent running behaviour (the oldest among equals), or -1 when none runs. */
int32_t cb_scheduler_leader(const cb_scheduler* scheduler);
/* 1 when the behaviour runs, 0 when not, CB_ERR_ARG on a bad index. */
int32_t cb_scheduler_is_running(const cb_scheduler* scheduler, int32_t index);
/* Writes up to cap running behaviour indices, oldest first; returns how many run. */
int32_t cb_scheduler_running(const cb_scheduler* scheduler, int32_t* out, int32_t cap);

#ifdef __cplusplus
}
#endif
