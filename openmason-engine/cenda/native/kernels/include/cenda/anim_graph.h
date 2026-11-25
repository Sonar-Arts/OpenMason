/* Cenda animation graph (OpenMason #157): the .omanimgraph asset (omformat/omanimgraph.hpp documents the file)
 * compiled to a flat node table and evaluated natively once per character per frame — what Unreal's Animation
 * Blueprint, Unity's Animator Controller and Godot's AnimationTree do. Consumed by Java via FFM
 * (com.openmason.engine.animgraph.*).
 *
 * GRAPH. An immutable compiled asset (cg_graph), shareable between threads and instances: parameters, clip slots,
 * masks and a TREE of nodes (clip players, 1D/2D blend spaces, state machines, override/additive layers, montage
 * slots). Written against profile roles: masks name roles, clips may carry role tracks.
 *
 * INSTANCE. One per character (cg_instance): parameter values, clips bound to the slots (copied and bound to the
 * character's roles), per-node state, pose slots (cr_poses), inertializers. Owned by one thread at a time.
 * cg_instance_evaluate is the one call per frame:
 *   1. weights, top-down: blend-space sample weights from the parameters; state machines take at most one
 *      transition (travel path first, else the highest-priority transition whose conditions hold and whose
 *      exit time has passed; conduits resolve in the same frame; triggers are consumed); crossfades progress.
 *      A node is ACTIVE when its pose is needed, and has an INFLUENCE weight (its share of the output, for
 *      notifies, curves, root motion and sync leadership). The old source of an inertialized transition stops
 *      being evaluated at once.
 *   2. time: clip players that just became active restart at 0; players in a sync group follow the most
 *      influential member through sync markers (cm_sync_advance, blend spaces sync their own samples); others
 *      advance by rate x dt. Montages advance, follow their section links and blend out at their end.
 *   3. pose: the active nodes emit one cr_pose_op program (rig.h), run and composed by cr_poses_evaluate; the
 *      graph's pose-modifier stack (#158) runs last on the root's pose (CR_OP_MODIFY), each modifier's weight =
 *      its weight x weightParam x weightCurve this frame, the base matrix as the stack's world frame.
 *   4. outputs: notifies the step crossed (players at or above the graph's notifyThreshold influence, and
 *      montages), curves (influence-weighted; a clip without the curve counts 0), root motion (influence-weighted
 *      sum over the base chain and montages; with CG_ROOT_MOTION_EXTRACT the root is pinned in the pose).
 * Deterministic: the same bound clips, parameter values and dt sequence give bit-identical poses and outputs.
 *
 * Lifetimes: the rig and the character (if any) must outlive the instance; the graph must outlive it too.
 * Strings returned are owned by the graph / instance and stay valid until the next call that changes it.
 */
#pragma once

#include <stdint.h>

#include "cenda/anim_clip.h"
#include "cenda/character.h"
#include "cenda/rig.h"

#ifdef __cplusplus
extern "C" {
#endif

#define CG_ABI_VERSION 1

typedef struct cg_graph cg_graph;
typedef struct cg_instance cg_instance;

enum {
    CG_OK = 0,
    CG_ERR_ARG = -1,
    CG_ERR_RANGE = -2,
    CG_ERR_FORMAT = -3,   /* not a valid .omanimgraph (cg_last_error says why) */
    CG_ERR_VERSION = -4,  /* a newer major version */
    CG_ERR_STATE = -5     /* not possible now (no path to the state, montage slot idle, ...) */
};

/* Parameter types (= the file's). */
enum {
    CG_PARAM_FLOAT = 0,
    CG_PARAM_INT = 1,
    CG_PARAM_BOOL = 2,
    CG_PARAM_TRIGGER = 3,
    CG_PARAM_VEC2 = 4
};

/* Node types. */
enum {
    CG_NODE_CLIP = 0,
    CG_NODE_BLEND1D = 1,
    CG_NODE_BLEND2D = 2,
    CG_NODE_STATE_MACHINE = 3,
    CG_NODE_LAYER = 4,
    CG_NODE_SLOT = 5
};

int32_t cg_abi_version(void);
const char* cg_last_error(void);

/* --- Graph asset -------------------------------------------------------------------------------------- */

/* Parses and compiles. NULL on failure; *out_result = CG_ERR_FORMAT / CG_ERR_VERSION / CG_ERR_ARG. */
cg_graph* cg_graph_read(const char* json, int64_t length, int32_t* out_result);
void cg_graph_destroy(cg_graph* g);
/* The graph as .omanimgraph JSON (deterministic). Free with cc_bytes_destroy (character.h). */
cc_bytes* cg_graph_write(const cg_graph* g);
const char* cg_graph_name(const cg_graph* g);
const char* cg_graph_profile(const cg_graph* g);

int32_t cg_graph_param_count(const cg_graph* g);
int32_t cg_graph_param_get(const cg_graph* g, int32_t index, const char** out_name, int32_t* out_type);
int32_t cg_graph_param_find(const cg_graph* g, const char* name);
int32_t cg_graph_slot_count(const cg_graph* g);
const char* cg_graph_slot_name(const cg_graph* g, int32_t index);
int32_t cg_graph_slot_find(const cg_graph* g, const char* name);
int32_t cg_graph_node_count(const cg_graph* g);
int32_t cg_graph_node_get(const cg_graph* g, int32_t index, const char** out_id, int32_t* out_type,
                          int32_t* out_parent);
int32_t cg_graph_node_find(const cg_graph* g, const char* id);
/* States of a state-machine node (conduits included, file order). */
int32_t cg_graph_state_count(const cg_graph* g, int32_t node);
const char* cg_graph_state_name(const cg_graph* g, int32_t node, int32_t state);
int32_t cg_graph_state_find(const cg_graph* g, int32_t node, const char* name);
/* Montage slots (slot nodes), by name. */
int32_t cg_graph_montage_slot_count(const cg_graph* g);
const char* cg_graph_montage_slot_name(const cg_graph* g, int32_t index);
int32_t cg_graph_montage_slot_find(const cg_graph* g, const char* name);

/* Pose-modifier targets the host sets (the file's `targets`) and the modifier count (#158). */
int32_t cg_graph_target_count(const cg_graph* g);
const char* cg_graph_target_name(const cg_graph* g, int32_t index);
int32_t cg_graph_target_find(const cg_graph* g, const char* name);
int32_t cg_graph_modifier_count(const cg_graph* g);

/* --- Instance ----------------------------------------------------------------------------------------- */

/* character may be NULL (then role masks and role tracks resolve to nothing). *out_unresolved (may be NULL)
 * counts mask entries, colliders and modifiers whose role / joint / socket the rig or character lacks (such a
 * modifier is left out). */
cg_instance* cg_instance_create(const cg_graph* g, const cr_rig* rig, const cc_char* character,
                                int32_t* out_unresolved);
void cg_instance_destroy(cg_instance* inst);
/* Back to the initial state: parameter defaults, entry states, no montages, clip times 0. Bindings stay. */
int32_t cg_instance_reset(cg_instance* inst);

/* Binds a clip to a slot (copied; role tracks bound to the character; NULL unbinds: the slot plays the rest
 * pose). *out_dropped (may be NULL) counts role tracks the character does not map. */
int32_t cg_instance_bind_slot(cg_instance* inst, int32_t slot, const cm_clip* clip, int32_t* out_dropped);
/* Registers a montage clip (copied and bound like a slot clip; its sections are the point notifies named
 * "section:<name>", else one section "default"). Returns the montage id (>= 0) or CG_ERR_*. */
int32_t cg_instance_add_montage(cg_instance* inst, const cm_clip* clip);

int32_t cg_instance_set_float(cg_instance* inst, int32_t param, float value);
int32_t cg_instance_set_int(cg_instance* inst, int32_t param, int32_t value);
int32_t cg_instance_set_bool(cg_instance* inst, int32_t param, int32_t value);
/* Sets a trigger; it stays set until a transition consumes it (or cg_instance_reset_trigger). */
int32_t cg_instance_fire_trigger(cg_instance* inst, int32_t param);
int32_t cg_instance_reset_trigger(cg_instance* inst, int32_t param);
int32_t cg_instance_set_vec2(cg_instance* inst, int32_t param, float x, float y);
/* out: 2 floats (a scalar parameter in out[0]). */
int32_t cg_instance_get_param(const cg_instance* inst, int32_t param, float* out);

/* Sets a host target for the pose modifiers: kind CR_TARGET_MODEL / CR_TARGET_WORLD (world goes through the
 * frame = the base matrix of the evaluate) / CR_TARGET_NONE (the modifiers reading it do nothing); pos 3 floats,
 * rot a quaternion x, y, z, w (NULL = identity). */
int32_t cg_instance_set_target(cg_instance* inst, int32_t target, int32_t kind, const float* pos, const float* rot);

/* Root motion modes. */
enum {
    CG_ROOT_MOTION_KEEP = 0,     /* the root moves in the pose (default) */
    CG_ROOT_MOTION_EXTRACT = 1   /* pinned in the pose; cg_instance_root_motion reports the motion */
};
int32_t cg_instance_set_root_motion(cg_instance* inst, int32_t mode);

/* Travel (Godot): walk the shortest chain of transitions from the current state to `state`, one transition per
 * frame, ignoring their conditions. CG_ERR_STATE when no chain exists. */
int32_t cg_instance_travel(cg_instance* inst, int32_t machine_node, int32_t state);
/* The machine's current state (the target of a running transition), the state it came from (-1 = none) and the
 * running blend's progress (1 = settled). */
int32_t cg_instance_state(const cg_instance* inst, int32_t machine_node, int32_t* out_state, int32_t* out_previous,
                          float* out_blend);

/* Montages (Unreal): a one-shot clip played into a montage slot over the slot's source pose (under the slot's
 * mask). blend_in: from an idle slot the montage fades in over blend_in seconds; interrupting a playing montage
 * inertializes over blend_in instead. blend_out: the montage fades out over its last blend_out seconds (or on
 * stop). section NULL = the start. */
int32_t cg_instance_montage_play(cg_instance* inst, int32_t slot, int32_t montage, float rate, float blend_in,
                                 float blend_out, const char* section);
int32_t cg_instance_montage_stop(cg_instance* inst, int32_t slot, float blend_out);
int32_t cg_instance_montage_jump(cg_instance* inst, int32_t slot, const char* section);
/* After `section` play `next` (NULL = play on in time order; next == section loops it). */
int32_t cg_instance_montage_set_next(cg_instance* inst, int32_t slot, const char* section, const char* next);

/* A montage slot's state (24 bytes). montage -1 = idle. */
typedef struct cg_montage_status {
    int32_t montage;
    int32_t section;
    float time;
    float weight;
    int32_t blending_out;
    uint32_t reserved;
} cg_montage_status;
int32_t cg_instance_montage_status(const cg_instance* inst, int32_t slot, cg_montage_status* out);

/* One frame: update by dt seconds, then compose like cr_rig_pose (extras, base matrix, outputs as there). */
int32_t cg_instance_evaluate(cg_instance* inst, float dt, const cr_joint_extra* extras, int32_t extra_count,
                             const float* base_matrix, float* out_skin, float* out_world, float* out_child);
/* The last frame's local pose (joint_count * 9 floats, as cr_rig_local_pose). */
int32_t cg_instance_local_pose(const cg_instance* inst, float* out_local);

/* --- Outputs of the last evaluate --------------------------------------------------------------------- */

/* One crossed notify edge (24 bytes). source = the clip slot it came from, or -1 for a montage (then montage =
 * its id); notify = its index in that clip; phase = CM_NOTIFY_*. */
typedef struct cg_notify {
    int32_t source;
    int32_t montage;
    int32_t notify;
    int32_t phase;
    float time;
    float weight;
} cg_notify;
int32_t cg_instance_notify_count(const cg_instance* inst);
/* name / payload (may be NULL): owned by the bound clip. */
int32_t cg_instance_notify_get(const cg_instance* inst, int32_t index, cg_notify* out, const char** out_name,
                               const char** out_payload_json);

/* Curves: every curve name of the bound clips (and montages), in first-bound order. */
int32_t cg_instance_curve_count(const cg_instance* inst);
const char* cg_instance_curve_name(const cg_instance* inst, int32_t index);
int32_t cg_instance_curve_find(const cg_instance* inst, const char* name);
float cg_instance_curve_value(const cg_instance* inst, int32_t index);

int32_t cg_instance_root_motion(const cg_instance* inst, cr_root_motion* out);

/* Debug snapshot (24 bytes per node, cg_graph_node_count entries). state = a state machine's current state. */
enum {
    CG_DEBUG_ACTIVE = 1u << 0,   /* its pose was evaluated */
    CG_DEBUG_BOUND = 1u << 1     /* a clip node whose slot has a clip */
};
typedef struct cg_node_debug {
    float influence;
    float time;        /* clip: clip time; others: 0 */
    float progress;    /* normalized progress (the most influential clip below; loops count up) */
    int32_t state;
    uint32_t flags;
    uint32_t reserved;
} cg_node_debug;
int32_t cg_instance_debug(const cg_instance* inst, cg_node_debug* out);

#ifdef __cplusplus
}
#endif
