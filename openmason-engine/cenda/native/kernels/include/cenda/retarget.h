/* Cenda animation retargeting (OpenMason #160, epic #166): play animation made on one characterized skeleton on
 * another with different proportions, bone counts and bone axes — Unreal's IK Retargeter, Unity's humanoid
 * retargeting, Maya HumanIK. Part of the character ABI (cc_*, CC ABI still 1); its own header because baking
 * returns an OMANIM clip (anim_clip.h includes character.h).
 *
 * A RETARGETER pairs a source and a target: each a rig (rig.h) and its .omchar (character.h) with a role map and
 * retarget CHAINS (auto-map writes them from the profile: arm_l, leg_l, spine, fingers, ...). Chains pair BY NAME;
 * a chain's joints are the path from its start to its end, keeping only joints of the start's kind (a bone chain
 * skips the parts it passes through, which ride along). Both characters are taken in their REFERENCE pose (the
 * .omchar reference rotations over the rest pose; cc_char_fit_reference_pose normalizes it), seen in the profile
 * frame (+Y up, faces -Z: each .omchar's facing is turned into it). Everything is a delta from those poses.
 *
 * OP STACK (Unreal 5.6+), run in order on the target's reference pose; each op has a weight (blends what it
 * changes back toward the pose it got) and parameters (cc_retarget_op):
 *   PELVIS       the hips role: its world rotation delta, and its translation from the reference scaled by the
 *                hip-height ratio (p[0] horizontal, p[1] vertical scale; 0 = the ratio: hips height above the
 *                lowest IK-goal joint, target / source).
 *   FK           every chain: each target joint takes the source rotation delta at its normalized position along
 *                the chain (INTERPOLATED: slerped between the source joints around it, so chains of different
 *                bone counts map; ONE_TO_ONE: by index), in GLOBAL (world delta) or LOCAL (parent-relative delta)
 *                space, times an AUTO-ALIGN turn that matches the target chain's reference directions to the
 *                source's (Unreal's auto align). Per-chain settings: cc_retarget_set_chain.
 *   STRIDE_WARP  p[0] multiplies the horizontal offset of every IK goal from the hips (stride length).
 *   ROOT_HEIGHT  lowers the hips until every GROUND IK goal is within reach of its limb (p[0] = most it drops, as
 *                a share of the target's hip height; 0 = 0.5).
 *   IK           chains with an IK goal (and >= 3 joints), solved two-bone with the #158 modifier (upper = the
 *                chain's first joint, mid = the one before the last, end = the last; the end keeps its FK rotation;
 *                the limb bends toward the source limb's bend when the source limb is clearly bent, else
 *                toward the chain's bend direction (cc_retarget_set_bend; the profile's), else keeps the FK
 *                bend). Per-chain weight. Where the goal goes (cc_retarget_chain.goal_space):
 *                GROUND (feet: contacts) — horizontally the source end's offset from the source chain start,
 *                scaled by the horizontal pelvis scale x the stride warp, from the target chain start (keeps the
 *                target's hip width, moves the foot at the speed the root motion is scaled to); vertically the
 *                target end's reference height + the source end's rise over its reference height x the vertical
 *                pelvis scale. CHAIN (hands: reach) — the source end's offset from the source chain start scaled
 *                by the chain-length ratio, from the target chain start. AUTO: GROUND when the chain's end lies in
 *                the lowest quarter of the source's hip height in the reference pose, else CHAIN.
 * Default stack: PELVIS, FK, STRIDE_WARP (1), ROOT_HEIGHT, IK, all weight 1.
 *
 * DRIVEN joints: the target hips and every target chain joint; the rest keep their reference pose (output) and
 * are reported undriven (a graph layer leaves them alone). Only the hips get a translation; other joints keep
 * their reference position.
 *
 * MODES: runtime — cc_retarget_pose (one source local pose in, one target local pose out), and in the animation
 * graph cg_instance_bind_slot_retarget (anim_graph.h): a clip node plays a source clip through a retargeter; baked —
 * cc_retarget_bake writes a target clip (OMANIM 1.2) sampled at a frame rate. At the bake's key times the baked
 * clip and the runtime path pose the same (Euler keys may be the equivalent dual angles: equal within float
 * rounding). Deterministic. A retargeter belongs to one thread at a time (working buffers); the rigs and
 * characters must outlive it.
 */
#pragma once

#include <stdint.h>

#include "cenda/anim_clip.h"
#include "cenda/character.h"
#include "cenda/rig.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct cc_retargeter cc_retargeter;

enum {
    CC_RETARGET_PELVIS = 0,
    CC_RETARGET_FK = 1,
    CC_RETARGET_STRIDE_WARP = 2,
    CC_RETARGET_ROOT_HEIGHT = 3,
    CC_RETARGET_IK = 4
};

/* One op of the stack (32 bytes). */
typedef struct cc_retarget_op {
    int32_t type;    /* CC_RETARGET_* */
    float weight;    /* 0..1 */
    float p[6];
} cc_retarget_op;

enum {
    CC_FK_INTERPOLATED = 0,
    CC_FK_ONE_TO_ONE = 1,
    CC_FK_OFF = 2
};
enum {
    CC_FK_GLOBAL = 0,
    CC_FK_LOCAL = 1
};

enum {
    CC_GOAL_AUTO = 0,
    CC_GOAL_GROUND = 1,
    CC_GOAL_CHAIN = 2
};

/* Per-chain settings (24 bytes); defaults: fk_weight 1, INTERPOLATED, GLOBAL, ik_weight 1, auto_align 1, AUTO. */
typedef struct cc_retarget_chain {
    float fk_weight;
    int32_t fk_mode;
    int32_t fk_space;
    float ik_weight;
    int32_t auto_align;
    int32_t goal_space;  /* CC_GOAL_* */
} cc_retarget_chain;

/* NULL on failure (cc_last_error says why: a missing rig / character, no chain in common, ...). */
cc_retargeter* cc_retarget_create(const cr_rig* source_rig, const cc_char* source, const cr_rig* target_rig,
                                  const cc_char* target);
void cc_retarget_destroy(cc_retargeter* rt);

/* Replaces the op stack (count 0..16). */
int32_t cc_retarget_set_ops(cc_retargeter* rt, const cc_retarget_op* ops, int32_t count);
int32_t cc_retarget_op_count(const cc_retargeter* rt);
int32_t cc_retarget_op_get(const cc_retargeter* rt, int32_t index, cc_retarget_op* out);

/* The paired chains (source and target both have them), in the target's chain order. */
int32_t cc_retarget_chain_count(const cc_retargeter* rt);
/* out_source_joints / out_target_joints: how many joints each side's chain has; out_goal: 1 when the chain is
 * solved by IK. */
int32_t cc_retarget_chain_get(const cc_retargeter* rt, int32_t index, const char** out_name,
                              int32_t* out_source_joints, int32_t* out_target_joints, int32_t* out_goal);
int32_t cc_retarget_set_chain(cc_retargeter* rt, const char* chain, const cc_retarget_chain* settings);
int32_t cc_retarget_get_chain(const cc_retargeter* rt, const char* chain, cc_retarget_chain* out);
/* The way the chain's middle joint bends when the source limb is straight (profile frame, turned with the chain
 * start's parent at runtime; NULL = none: the FK bend). Defaults to the target's built-in profile chain `bend`
 * (humanoid knees -Z, elbows +Z). */
int32_t cc_retarget_set_bend(cc_retargeter* rt, const char* chain, const float* direction);

/* The rigs and characters it was made for. */
const cr_rig* cc_retarget_source_rig(const cc_retargeter* rt);
const cr_rig* cc_retarget_target_rig(const cc_retargeter* rt);
const cc_char* cc_retarget_source_char(const cc_retargeter* rt);

/* The automatic pelvis scales (hip-height ratio; horizontal and vertical are equal unless an op overrides them). */
float cc_retarget_scale(const cc_retargeter* rt);

/* A source root motion as the target's: translation turned from the source's facing into the target's and scaled
 * by the pelvis scales (horizontal / vertical), yaw unchanged. */
int32_t cc_retarget_map_motion(const cc_retargeter* rt, cr_root_motion* inout);

/* One pose: source local pose (cr_rig_joint_count(source) x 9 floats, as cr_rig_local_pose) in, target local pose
 * out (target count x 9); out_driven (may be NULL) gets 1 per target joint the retarget drives. */
int32_t cc_retarget_pose(cc_retargeter* rt, const float* source_local, float* out_target_local, uint8_t* out_driven);

/* Bakes a source clip (joint- or role-addressed for the source character) for the target: sampled at fps (keys at
 * k / fps and at the duration), one track per driven target joint (keyed by joint id; Euler keys continuous: the
 * dual angle nearer the previous key), the clip's notifies, curves, sync markers and timing kept; root motion
 * moves to the target hips when it was on the source hips (the baked hips translation is already scaled).
 * Additive clips are refused. NULL on failure (*out_result = CC_ERR_*). Free with cm_clip_destroy. */
cm_clip* cc_retarget_bake(cc_retargeter* rt, const cm_clip* source_clip, float fps, int32_t* out_result);

#ifdef __cplusplus
}
#endif
