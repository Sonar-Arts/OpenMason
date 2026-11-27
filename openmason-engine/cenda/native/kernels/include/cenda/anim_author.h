/* Cenda animation authoring (OpenMason #165, epic #166): the native model behind Open Mason's Animation tab and its
 * anim_* MCP tools - Maya's animation layers and HumanIK control rig, Unreal's notify / curve tracks, over the
 * .omanim clip. Part of CM ABI 1 (additions); sources src/anim/anim_author.cpp over libomformat's
 * omformat/omanim_author.hpp (which documents the authoring data and every operation); ctest `anim_author`.
 *
 * A session edits one clip for one rig (and optionally a character, for role-addressed tracks and masks):
 *   - LAYERS: the base layer plus override / additive layers with weight (keyable), mask, mute, solo and
 *     passthrough, composed through the rig's pose operations (cr_poses, #156) - the same code the runtime plays,
 *     so the editor's pose IS the runtime's; an additive layer is a delta against the rest pose (CR_OP_MAKE_ADDITIVE).
 *   - CONTROL RIG: effectors (two-bone IK with an optional pole and rotation pin, aim / look-at) whose keys stay
 *     editable; they are solved by the rig's modifier stack (#158) on top of the layers.
 *   - BAKE: the clip's joint tracks are always the result (layers composed, effectors solved) sampled at the clip's
 *     fps (plus its last frame); a session that is only its base layer keeps the base tracks as they are (lossless).
 *     Every apply re-bakes.
 *   - The authoring data is saved INSIDE the .omanim (an "authoring" section + one entry per layer); the runtime
 *     ignores it. cm_author_write gives the file, cm_author_clip the playable clip.
 *   - Switch-and-match (operation "match"): to IK sets the effector's key at a time to where the FK pose puts the
 *     chain end (and its rotation and the middle joint as pole); to FK keys the chain's joints on a layer to the
 *     solved pose and sets the effector's weight to 0 there. FK -> IK -> FK leaves the pose unchanged.
 * History, merge keys, atomic batches and revision work as cg_doc (cenda/anim_graph_edit.h).
 *
 * Threading: one thread at a time. The rig (and character) must outlive the session.
 */
#pragma once

#include <stdint.h>

#include "cenda/anim_clip.h"
#include "cenda/character.h"
#include "cenda/retarget.h"
#include "cenda/rig.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct cm_author cm_author;

/* Stages of cm_author_pose. */
enum {
    CM_STAGE_LAYERS = 0, /* the composed layers (FK) */
    CM_STAGE_FINAL = 1   /* + the control rig (what is baked) */
};

/* A session over a copy of `clip` (its authoring data read when present). NULL on failure (cm_last_error). */
cm_author* cm_author_create(const cm_clip* clip, const cr_rig* rig, const cc_char* character);
void cm_author_destroy(cm_author* a);
/* The .omanim to save (authoring data included when the session needs it). */
cc_bytes* cm_author_write(const cm_author* a);
/* The playable clip (baked tracks + clip data), owned by the session, valid until its next change. */
const cm_clip* cm_author_clip(const cm_author* a);

/* Applies operations as ONE undo step (all or nothing); returns the count or CM_ERR_FORMAT / CM_ERR_ARG
 * (cm_last_error "ops[i] (op): why"). label NULL = "edit animation"; merge_key folds consecutive applies. */
int32_t cm_author_apply(cm_author* a, const char* ops_json, int64_t length, const char* label, const char* merge_key);
const char* cm_author_results(const cm_author* a);
/* The editor's model as JSON (omanim_author.hpp view), cached per revision. */
const char* cm_author_view(const cm_author* a);

/* The pose at `time` (wrapped like playback): joint_count*9 local floats (pos, rot degrees, scale). */
int32_t cm_author_pose(const cm_author* a, float time, int32_t stage, float* out_local);
/* The morph (shape key) weights of the pose at `time` (#171): the rig's channels (cr_rig_morph_count), composed by
 * the layers like the joints (layer masks do not filter channels); writes min(count, capacity) and returns the
 * count. Keys are set with the morph_key / morph operations (omanim_author.hpp); the baked clip carries the composed
 * morph tracks (a channel only the base layer keys stays as authored). */
int32_t cm_author_morph_weights(const cm_author* a, float time, int32_t stage, float* out, int32_t capacity);
/* The pose composed (cr_rig_pose_locals outputs; any may be NULL). */
int32_t cm_author_compose(const cm_author* a, float time, int32_t stage, const float* base_matrix, float* out_skin,
                          float* out_world);
/* Control-rig handles at `time` as JSON: [{effector, kind, target: [x,y,z], weight, pole?, end: [x,y,z]}] (model
 * space; end = where the FK pose puts the chain end). */
const char* cm_author_handles(const cm_author* a, float time);

/* Root motion preview (#165): how far the clip's root-motion joint moved from time 0 at `time` (FINAL pose), on the
 * clip's root-motion axes: out[0..2] model-space translation, out[3] yaw degrees about +Y. Zeros without root motion.
 * The editor subtracts it to show the clip in place. */
/* A layer's OWN value of a joint at `time` (its track sampled; the rest pose where it has none): 9 floats
 * (pos, rot degrees, scale). What the inspector shows and keys for that layer - for an additive layer the absolute
 * pose whose difference from the rest pose is added. layer = index; joint = rig joint index. */
int32_t cm_author_layer_value(const cm_author* a, int32_t layer, int32_t joint, float time, float* out9);

int32_t cm_author_root_motion(const cm_author* a, float time, float* out4);

/* Bakes a retargeted copy of `source` (cc_retarget_bake at fps, #160) into layer `layer` (created when missing;
 * NULL = "Retarget"), replacing its keys: one undo step. Returns CM_OK. */
int32_t cm_author_import_retarget(cm_author* a, const cm_clip* source, cc_retargeter* retargeter, float fps,
                                  const char* layer);

/* History (as cg_doc). */
int32_t cm_author_undo(cm_author* a);
int32_t cm_author_redo(cm_author* a);
int32_t cm_author_can_undo(const cm_author* a);
int32_t cm_author_can_redo(const cm_author* a);
const char* cm_author_label(const cm_author* a, int32_t which);
int32_t cm_author_seal(cm_author* a);
int64_t cm_author_revision(const cm_author* a);
int32_t cm_author_dirty(const cm_author* a);
int32_t cm_author_mark_saved(cm_author* a);
int32_t cm_author_step_count(const cm_author* a);

#ifdef __cplusplus
}
#endif
