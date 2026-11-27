/* Cenda rig — skeletons, animation clips, posing, skinning and skin-weight authoring for part/bone
 * models, behind a C ABI consumed by Java via FFM (com.openmason.engine.rig.*). Replaces the Java
 * ModelPoseSolver / AnimSampler / AnimLayering pose pipeline and the editor's BoneStore math.
 *
 * MODEL. A rig is a list of JOINTS forming a forest. Parts (mesh pieces) and bones (mesh-less rig
 * segments) are both joints and may parent each other in any combination. Two kinds share one
 * transform pipeline (all rotations are Euler XYZ degrees, applied R = Rx·Ry·Rz like JOML rotateXYZ):
 *
 *   PART joint: L = T(pos)·T(origin)·Ry(pre_yaw)·Rx(pre_pitch)·R(rot)·S(scale)·T(-origin)
 *               children inherit F = W
 *   BONE joint: L = T(origin)·T(pos)·Ry(pre_yaw)·Rx(pre_pitch)·R(rot)·S(scale)
 *               children inherit F = W·T(endpoint)          (the bone's tail)
 *
 *   W(j) = F(parent(j))·L(j), F(no parent) = identity.
 *
 * Mesh vertices are authored in model space at the REST pose (the bind pose). The SKIN matrix of a
 * joint is K(j) = B·W_pose(j)·W_rest(j)^-1 (B = the caller's base/model matrix); a vertex with
 * influences (j_i, w_i) renders at sum_i w_i·K(j_i)·v. A vertex without authored weights follows its
 * part's joint rigidly (one influence, weight 1) — exactly the old per-part draw.
 *
 * CLIPS. A clip is a set of tracks, each keyed by a joint id (part or bone UUID) with the joint's
 * name as a fallback key. Keys hold the ABSOLUTE local pose (pos, rot degrees, scale) at a time; the
 * outgoing key's easing shapes each segment; rotations take the shortest path per component. Times
 * before the first / after the last key clamp. A clip binds to a joint by id, else exact name, else
 * case-insensitive name. An OVERLAY clip carries a mask (joint names, case-insensitive, or ids; empty
 * = every joint), a priority (higher wins contested joints), fade-in/out seconds; it blends over the
 * base pose per joint with lerp weight = min(1, caller weight · fade envelope).
 *
 * POSE (cr_rig_pose): per joint, start from the base clip's sample (or the rest pose), fold the
 * overlays in ascending priority (stable), add the caller's per-joint extras (pre-rotation yaw/pitch
 * for head tracking, additive Euler degrees for code-driven turns), compose W, write K and/or B·W.
 * Since ABI 2 cr_rig_pose is itself a small pose program (sample, then one override layer per
 * overlay), bit-identical to ABI 1.
 *
 * POSE PIPELINE (ABI 2, OpenMason #156). The operations an animation graph is built from, on POSE
 * SLOTS (cr_poses: N local poses of one rig, allocated once): rest, sample, copy, n-way weighted
 * blend, override / additive layers under a weighted bone mask (cr_mask), additive deltas against a
 * reference pose in local or mesh space, inertialized transitions (cr_inertia) and root-motion
 * removal. A frame is a list of cr_pose_op run by ONE call (cr_poses_evaluate: run + compose); the
 * graph (#157) compiles to such lists. Clip-time helpers sit beside it: root-motion extraction
 * (cr_clip_root_motion) here, sync groups over sync markers in anim_clip.h (cm_sync_advance).
 *
 * SKIN AUTHORING (cr_skin_*): an editable per-vertex weight set (any number of influences per
 * vertex since ABI 3, OpenMason #170 - no cap; sparse: vertices without entries follow their part).
 * Auto weights, brush painting, smoothing, normalize, prune, mirror, transfer onto an edited mesh,
 * and the packed GPU form (a per-vertex range into a flat influence list, unorm16 weights).
 *
 * ABI 3 (OpenMason #170): unbounded skin influences (cr_skin_get takes a capacity, CSR import /
 * export, a variable-length GPU packing that cr_skin_deform uses exactly), modifier joint lists of
 * any length (cr_modifier_desc holds a pointer + count) and any number of legs (per-leg values are
 * keyed CR_VALUE_LEG(leg, field)). Pose results of the ABI 2 entry points are unchanged.
 *
 * ABI 3 (#171 additions, OpenMason #171): MORPH TARGETS (shape keys). A mesh's targets (cr_morphs: sparse deltas,
 * CPU apply, GPU packing), MORPH CHANNELS on a rig (named weights every pose carries next to its joints; clips
 * key them with morph tracks bound by name, the pose ops blend / layer / mask / inertialize them, CR_OP_MORPH
 * overrides them). A rig without morph channels and clips without morph tracks pose bit-identically to before.
 * See "Morph targets" below.
 *
 * Threading: rigs and clips are immutable after create and may be posed from any number of threads at
 * once (the clip-binding cache is internally locked). A skin belongs to one thread at a time.
 * Matrices are column-major float[16] (OpenGL / JOML order).
 */
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CR_ABI_VERSION 3

typedef struct cr_rig cr_rig;
typedef struct cr_clip cr_clip;
typedef struct cr_skin cr_skin;
typedef struct cr_poses cr_poses;
typedef struct cr_mask cr_mask;
typedef struct cr_inertia cr_inertia;
typedef struct cr_modstack cr_modstack;

/* Results. */
enum {
    CR_OK = 0,
    CR_ERR_ARG = -1,   /* null handle, bad index, bad sizes */
    CR_ERR_RANGE = -2, /* an index refers outside the rig / mesh */
    CR_ERR_HOST = -3   /* a host callback failed (the ground query, #159) */
};

#define CR_MAX_JOINTS 65535
/* (CR_MAX_INFLUENCES is gone since ABI 3: a vertex may carry any number of influences.) */

/* Joint kinds. */
enum {
    CR_JOINT_PART = 0,
    CR_JOINT_BONE = 1
};

/* Joint flags. */
enum {
    /* The joint moves rendered geometry: a part with faces, or a joint skin weights reference.
     * Used by cr_rig_find_head. */
    CR_JOINT_HAS_GEOMETRY = 1u << 0,
    /* The author hid the part (the OMO part `visible` flag is false). Posing ignores it; appearance resolution
     * (cenda/appearance.h, OpenMason #162) starts a part's visibility from it. */
    CR_JOINT_HIDDEN = 1u << 1
};

/* One joint (80 bytes). parent = index into the joint list, -1 = root. Parents may come after their
 * children in the list; a cycle is broken at the joint where it is detected (that joint becomes a
 * root) and reported by cr_rig_flags. rest_scale of a bone is normally (1,1,1). */
typedef struct cr_joint_desc {
    int32_t parent;
    int32_t kind;          /* CR_JOINT_PART / CR_JOINT_BONE */
    uint32_t flags;        /* CR_JOINT_* */
    uint32_t reserved;     /* 0 */
    float origin[3];       /* part: pivot; bone: head offset in the parent frame */
    float pos[3];          /* rest translation */
    float rot_deg[3];      /* rest Euler XYZ degrees */
    float scale[3];        /* rest scale */
    float endpoint[3];     /* bone tail in the bone's local frame (ignored for parts) */
    float reserved2;       /* 0 */
} cr_joint_desc;

/* Rig flags (cr_rig_flags). */
enum {
    CR_RIG_CYCLE_BROKEN = 1u << 0,  /* a parent cycle was cut */
    CR_RIG_BAD_PARENT = 1u << 1     /* a parent index was out of range (treated as root) */
};

int32_t cr_abi_version(void);
/* The calling thread's last error message ("" when none). */
const char* cr_last_error(void);

/* A rig over count joints (1 .. CR_MAX_JOINTS). ids / names: count NUL-terminated UTF-8 strings each
 * (a null entry = empty). NULL on a bad argument (cr_last_error says why). */
cr_rig* cr_rig_create(const cr_joint_desc* joints, int32_t count,
                      const char* const* ids, const char* const* names);
void cr_rig_destroy(cr_rig* rig);
int32_t cr_rig_joint_count(const cr_rig* rig);
uint32_t cr_rig_flags(const cr_rig* rig);
/* Index of the joint with this id (exact), else this name (exact), else name (case-insensitive);
 * -1 when none. */
int32_t cr_rig_find(const cr_rig* rig, const char* id_or_name);
/* The head-tracking joint: the first BONE whose name contains "head" (case-insensitive) and that has
 * CR_JOINT_HAS_GEOMETRY on itself or a descendant; else the first PART whose name contains "head"
 * (the pre-rig rule); -1 when none. */
int32_t cr_rig_find_head(const cr_rig* rig);
/* Rest-pose world matrices (no base matrix): head frame W_rest(j) and child frame F_rest(j) (for a
 * bone, its tail). Either output may be null; each holds joint_count*16 floats. */
int32_t cr_rig_rest_world(const cr_rig* rig, float* out_world, float* out_child_frame);

/* Easing curves (a key's easing shapes the segment to the next key). */
enum {
    CR_EASE_LINEAR = 0,
    CR_EASE_IN = 1,
    CR_EASE_OUT = 2,
    CR_EASE_IN_OUT = 3,
    CR_EASE_STEP = 4
};

/* One keyframe (48 bytes). */
typedef struct cr_key {
    float time;
    float pos[3];
    float rot_deg[3];
    float scale[3];
    int32_t easing;   /* CR_EASE_* */
    uint32_t reserved; /* 0 */
} cr_key;

/* One track: keys[first .. first+count) of the clip's key array, sorted by time (cr_clip_create sorts
 * each track's keys stably). An empty track never binds. */
typedef struct cr_track_desc {
    int32_t first_key;
    int32_t key_count;
} cr_track_desc;

/* Layer types. */
enum {
    CR_LAYER_BASE = 0,
    CR_LAYER_OVERLAY = 1
};

/* Clip header (24 bytes). */
typedef struct cr_clip_desc {
    float duration;
    int32_t loop;        /* 0/1 */
    int32_t layer_type;  /* CR_LAYER_* */
    int32_t priority;
    float fade_in;       /* seconds, < 0 = 0 */
    float fade_out;      /* seconds, < 0 = 0 */
} cr_clip_desc;

/* A clip. track_ids / track_names: track_count strings each (the joint id / name each track drives).
 * mask: mask_count strings (joint names or ids; 0 = every joint). NULL on a bad argument. */
cr_clip* cr_clip_create(const cr_clip_desc* desc,
                        const cr_track_desc* tracks, int32_t track_count,
                        const cr_key* keys, int32_t key_count,
                        const char* const* track_ids, const char* const* track_names,
                        const char* const* mask, int32_t mask_count);
void cr_clip_destroy(cr_clip* clip);

/* Clip time helpers (the same rules cr_rig_pose applies). */
float cr_wrap_time(float elapsed, float duration, int32_t loop);
/* An overlay's own fade envelope at elapsed (unwrapped) seconds, in [0,1]. */
float cr_clip_weight(const cr_clip* clip, float elapsed);
/* Sample one track's local pose at a (wrapped) time into out_pos/out_rot_deg/out_scale. */
int32_t cr_clip_sample_track(const cr_clip* clip, int32_t track, float time,
                             float* out_pos, float* out_rot_deg, float* out_scale);
/* The track this clip uses for a rig joint (-1 = none). */
int32_t cr_clip_track_for(const cr_clip* clip, const cr_rig* rig, int32_t joint);

/* One overlay layer (16 bytes on every platform: pointer padded to 8). time = elapsed seconds since
 * the overlay started (unwrapped: the fade envelope reads it); weight = caller envelope in [0,1]. */
typedef struct cr_layer {
    const cr_clip* clip;
    float time;
    float weight;
} cr_layer;

/* Per-joint additions (24 bytes): a pre-rotation (yaw about Y then pitch about X, degrees, applied
 * before the local rotation about the joint's pivot — head tracking) and additive Euler degrees
 * (code-driven turns). */
typedef struct cr_joint_extra {
    int32_t joint;
    float pre_yaw_deg;
    float pre_pitch_deg;
    float add_rot_deg[3];
} cr_joint_extra;

/* Pose the rig. base_clip may be null (rest pose); base_time is elapsed seconds (wrapped here).
 * base_matrix: 16 floats, null = identity. out_skin: K(j) = B·W_pose·W_rest^-1, out_world: B·W_pose
 * (head frame), out_child: B·F_pose (bone tail frame / part world); each joint_count*16 floats or
 * null. Returns CR_OK or CR_ERR_*. */
int32_t cr_rig_pose(const cr_rig* rig,
                    const cr_clip* base_clip, float base_time,
                    const cr_layer* overlays, int32_t overlay_count,
                    const cr_joint_extra* extras, int32_t extra_count,
                    const float* base_matrix,
                    float* out_skin, float* out_world, float* out_child);

/* Pose the rig from explicit local values (an editor's current transforms) instead of clips:
 * locals = joint_count*9 floats (pos[3], rot_deg[3], scale[3]) or null for the rest pose. Extras,
 * base and outputs as cr_rig_pose. */
int32_t cr_rig_pose_locals(const cr_rig* rig, const float* locals,
                           const cr_joint_extra* extras, int32_t extra_count,
                           const float* base_matrix,
                           float* out_skin, float* out_world, float* out_child);

/* Local pose of every joint for a frame (what cr_rig_pose composes), 9 floats per joint:
 * pos[3], rot_deg[3], scale[3]; a joint nothing animates reports its rest values. For editors that
 * key the current pose. */
int32_t cr_rig_local_pose(const cr_rig* rig,
                          const cr_clip* base_clip, float base_time,
                          const cr_layer* overlays, int32_t overlay_count,
                          float* out_local);

/* ---------------------------------------------------------------------------------------------- */
/* Pose pipeline (ABI 2).                                                                          */
/*                                                                                                 */
/* A POSE is the local transform of every joint (pos, rot Euler XYZ degrees, scale: the 9 floats   */
/* of cr_rig_local_pose) plus a DRIVEN flag per joint: whether the pose sets that joint (a sampled */
/* clip drives the joints it has tracks for; the rest pose drives none). Layers and additive       */
/* deltas only touch joints their source drives, so an overlay leaves the joints it does not key   */
/* alone (the ABI 1 rule). Blends and layers interpolate rotations per Euler component along the   */
/* shortest path (the ABI 1 rule, bit-identical) unless CR_POSE_QUAT asks for quaternions.         */

/* Pose flags (cr_pose_op.flags). */
enum {
    CR_POSE_QUAT = 1u << 0,           /* BLEND / override LAYER: blend rotations as quaternions
                                         (normalized weighted sum, hemisphere-aligned) instead of
                                         per Euler component */
    CR_POSE_MESH_SPACE = 1u << 1,     /* MAKE_ADDITIVE / additive LAYER: the rotation delta lives in
                                         model (mesh) space: applied after the parent chain, so a
                                         delta on the spine does not swing the arms' mesh-space aim */
    CR_POSE_NO_PASSTHROUGH = 1u << 2, /* override LAYER (Maya passthrough off): a partial weight blends
                                         from the REST pose, not from the layers below */
    CR_POSE_CLIP_MASK = 1u << 3,      /* SAMPLE: drive only the joints the clip's name mask covers
                                         (an ABI 1 overlay) */
    CR_POSE_DRIVE_ALL = 1u << 4,      /* SAMPLE: every joint is driven, a joint the clip does not key at its
                                         rest pose - a pose an override LAYER applies outright (a montage,
                                         Stonebreak #460); every morph channel too (#171) */
    CR_POSE_MORPHS_ONLY = 1u << 5     /* SAMPLE (#171): only the morph weights are sampled (reset to the
                                         channel defaults, then the clip's morph tracks); joints untouched (a
                                         retargeted clip's morph tracks onto the target's pose) */
};

/* Layer blend modes (cr_pose_op.mode for CR_OP_LAYER). */
enum {
    CR_BLEND_OVERRIDE = 0,  /* dst = lerp(dst, src, w) on joints src drives */
    CR_BLEND_ADDITIVE = 1   /* dst = dst (+) w·src, src a delta from CR_OP_MAKE_ADDITIVE */
};

/* Root-motion axes (same bits as anim_clip.h CM_ROOT_*). */
enum {
    CR_ROOT_X = 1u << 0,
    CR_ROOT_Y = 1u << 1,
    CR_ROOT_Z = 1u << 2,
    CR_ROOT_YAW = 1u << 3
};

/* Pose operations. Slots are indices into the cr_poses the program runs on; `object` is the clip /
 * mask / inertializer / weights pointer the op names. */
enum {
    CR_OP_REST = 0,         /* dst = the rest pose, nothing driven */
    CR_OP_SAMPLE = 1,       /* dst = rest + `object` (cr_clip) sampled at `time` elapsed seconds (wrapped
                               or clamped like cr_rig_pose); drives the joints the clip has tracks for
                               (with CR_POSE_CLIP_MASK: only those its mask covers) */
    CR_OP_COPY = 2,         /* dst = slot a */
    CR_OP_BLEND = 3,        /* dst = weighted blend of slots a .. a+b-1 (b = count >= 1), `object` = const
                               float weights[b] (normalized by their sum; <= 0 = skipped; all zero = rest).
                               Every source contributes its value for every joint (rest where it drives
                               nothing); drives the union. A single positive weight copies exactly */
    CR_OP_LAYER = 4,        /* dst (in/out) layered with slot a by `mode` at `weight` x the mask weight of
                               each joint (`object` = cr_mask, null = 1 everywhere); weights clamp to 1 */
    CR_OP_MAKE_ADDITIVE = 5,/* dst = slot a - slot b (the reference): translation difference, scale ratio,
                               rotation q_b^-1·q_a (local) or Q_a·Q_b^-1 (mesh space, CR_POSE_MESH_SPACE).
                               A joint whose values are equal in both gets an exact zero delta */
    CR_OP_INERTIALIZE = 6,  /* dst (in/out) = dst + the inertializer's decaying offset after `param` (dt)
                               more seconds; records dst as the inertializer's latest output (`object`) */
    CR_OP_REMOVE_ROOT = 7   /* in dst, joint b keeps the extracted `axes` of clip `object` at time 0: the
                               translation axes are pinned to the clip's first frame and the yaw turned
                               back to it (what cr_clip_root_motion reported moves the character) */
    ,CR_OP_MODIFY = 8       /* dst (in/out) through the modifier stack `object` (cr_modstack), spring bones
                               advanced by `param` (dt) seconds (pose modifiers, #158) */
    ,CR_OP_MORPH = 9        /* (#171) dst's morph weights overridden by `object` = const float values[morph channel
                               count]: channel c = lerp(c, values[c], min(weight, 1)); a NaN value leaves its
                               channel alone; the channels set become driven. Joints untouched. (Graph morph
                               drives: a weight from a float parameter.) */
};

/* One operation (48 bytes on every 64-bit platform). Unused fields 0. */
typedef struct cr_pose_op {
    int32_t op;           /* CR_OP_* */
    int32_t dst;          /* destination slot */
    int32_t a;            /* source slot / first slot */
    int32_t b;            /* BLEND: slot count; MAKE_ADDITIVE: reference slot; REMOVE_ROOT: joint */
    const void* object;   /* clip / mask / inertializer / weights (pointer padded to 8 bytes) */
    float time;           /* SAMPLE: elapsed seconds */
    float weight;         /* LAYER: layer weight */
    float param;          /* INERTIALIZE: dt seconds */
    int32_t mode;         /* LAYER: CR_BLEND_* */
    uint32_t flags;       /* CR_POSE_* */
    uint32_t axes;        /* REMOVE_ROOT: CR_ROOT_* */
} cr_pose_op;

/* slot_count local poses (1..1024) of one rig, all at rest. The rig must outlive it. A cr_poses belongs
 * to one thread at a time; running a program never allocates. */
cr_poses* cr_poses_create(const cr_rig* rig, int32_t slot_count);
void cr_poses_destroy(cr_poses* poses);
int32_t cr_poses_slot_count(const cr_poses* poses);
/* A slot's local values (joint_count*9 floats, as cr_rig_local_pose) and driven flags (joint_count
 * bytes, 0/1); either output may be null. */
int32_t cr_poses_get(const cr_poses* poses, int32_t slot, float* out_local, uint8_t* out_driven);
/* Sets a slot (an editor's current transforms). driven null = every joint driven. */
int32_t cr_poses_set(cr_poses* poses, int32_t slot, const float* local, const uint8_t* driven);
/* Runs ops in order. On a bad op returns CR_ERR_ARG / CR_ERR_RANGE (cr_last_error names the op index);
 * the ops before it have run. */
int32_t cr_poses_run(cr_poses* poses, const cr_pose_op* ops, int32_t op_count);
/* Composes one slot like cr_rig_pose (extras, base matrix, outputs as there). */
int32_t cr_poses_compose(const cr_poses* poses, int32_t slot,
                         const cr_joint_extra* extras, int32_t extra_count,
                         const float* base_matrix,
                         float* out_skin, float* out_world, float* out_child);
/* The one call per character per frame: cr_poses_run, then cr_poses_compose of `slot`. */
int32_t cr_poses_evaluate(cr_poses* poses, const cr_pose_op* ops, int32_t op_count, int32_t slot,
                          const cr_joint_extra* extras, int32_t extra_count,
                          const float* base_matrix,
                          float* out_skin, float* out_world, float* out_child);

/* Weighted bone masks (Unity Avatar Mask, Unreal layered blend per bone): a weight in [0,1] per joint (and per
 * morph channel since #171: `fill` and cr_mask_fill set those too; cr_mask_set_morph below). The rig must outlive
 * the mask. */
enum {
    CR_MASK_BRANCH = 1u << 0   /* cr_mask_set: the joint's descendants take the weight too */
};
cr_mask* cr_mask_create(const cr_rig* rig, float fill);
void cr_mask_destroy(cr_mask* mask);
int32_t cr_mask_joint_count(const cr_mask* mask);
int32_t cr_mask_fill(cr_mask* mask, float weight);
/* Sets `joint` to weight (clamped to [0,1]); with CR_MASK_BRANCH every descendant too. blend_depth > 0
 * ramps the weight down the branch (Unreal's blend depth): a joint d levels below `joint` gets
 * weight·min(1, (d+1)/blend_depth). Later sets overwrite earlier ones. */
int32_t cr_mask_set(cr_mask* mask, int32_t joint, float weight, uint32_t flags, int32_t blend_depth);
/* joint_count floats in / out (joints only; morph channels: cr_mask_get_morph / cr_mask_set_morph). */
int32_t cr_mask_get(const cr_mask* mask, float* out_weights);
int32_t cr_mask_set_all(cr_mask* mask, const float* weights);
/* 1 on the joints a clip's name mask covers (every joint for an empty mask), else 0; morph channels by the same
 * mask ("Morph targets": the channel name or "morph:<name>"). */
int32_t cr_mask_from_clip(cr_mask* mask, const cr_clip* clip);

/* Inertialization (Unreal; Bollo, GDC 2018): on a source switch the old pose is not evaluated any
 * more; the difference between the last output and the new source's pose, and the last output's
 * velocity, decay to zero over the blend time with a quintic (continuous in position, velocity and
 * acceleration, no overshoot). Per joint: translation and rotation as a magnitude along one axis,
 * scale per component. */
cr_inertia* cr_inertia_create(const cr_rig* rig);
void cr_inertia_destroy(cr_inertia* inertia);
/* Start a transition: the next CR_OP_INERTIALIZE measures the offset from the recorded outputs (needs
 * one recorded frame; velocity needs two) to the pose it is given. blend_time <= 0 = an instant cut. */
int32_t cr_inertia_request(cr_inertia* inertia, float blend_time);
/* Forget the history and any running transition. */
int32_t cr_inertia_reset(cr_inertia* inertia);
/* 1 while a transition is running. */
int32_t cr_inertia_active(const cr_inertia* inertia);

/* Root motion (16 bytes): what the root joint moved between two times, for the host to move the
 * character by. With CR_ROOT_YAW extracted, translation is in the root's frame at the start of the
 * step (its heading removed), so the host rotates it by the character's facing; without it, in the
 * root's parent frame (model space for a root joint). yaw_deg is the turn about +Y. */
typedef struct cr_root_motion {
    float translation[3];
    float yaw_deg;
} cr_root_motion;

/* The root motion of `joint`'s track in `clip` over [prev_time, prev_time + delta] elapsed seconds
 * (looping clips wrap and count whole cycles; others clamp), on `axes` (CR_ROOT_*). A joint the clip does
 * not key moves nothing. The yaw is the heading of the joint's +Z axis about +Y. */
int32_t cr_clip_root_motion(const cr_clip* clip, const cr_rig* rig, int32_t joint, uint32_t axes,
                            float prev_time, float delta, cr_root_motion* out);

/* ---------------------------------------------------------------------------------------------- */
/* Pose modifiers (OpenMason #158; still CR ABI 2: functions and an op were added).                */
/*                                                                                                 */
/* Procedural adjustments applied after the animation, as an ordered stack with an influence per   */
/* modifier (Godot SkeletonModifier3D, Unity Animation Rigging, Unreal IK Rig / Control Rig, Maya  */
/* constraints). A cr_modstack belongs to one character (spring bones keep state) and runs as      */
/* CR_OP_MODIFY on a pose slot: every modifier solves at full strength on the current pose (model  */
/* space, posed by forward kinematics) and its joints are then blended back toward the pose it got */
/* by its weight (translation and scale lerp, rotation slerp). Rotations act about each joint's    */
/* pivot (a part's origin, a bone's head) and are written back as local Euler degrees; parent      */
/* frames are treated as rotation (+ uniform scale).                                               */
/*                                                                                                 */
/* TARGETS are slots the stack reads: a model-space or world-space transform the host sets, or a   */
/* joint's posed frame times an offset (sockets). World targets go through the stack's FRAME (the  */
/* model -> world matrix, cr_modstack_set_frame); spring bones simulate in world space through it, */
/* so moving the character swings them.                                                            */

enum {
    CR_MOD_TWO_BONE = 0,  /* joints [upper, mid, end]; target; pole (optional) sets the bend plane; p[0] =
                             softness (0 = off: the fraction of the full length over which the reach eases
                             in, so the limb never snaps straight); flag MATCH_ROTATION also turns the end
                             joint to the target's rotation. No stretch. */
    CR_MOD_FABRIK = 1,    /* joints [start, end] (the chain is the path between them); target; p[0] =
                             iterations (default 10), p[1] = tolerance (default 1e-4) */
    CR_MOD_CCD = 2,       /* as FABRIK, cyclic coordinate descent */
    CR_MOD_AIM = 3,       /* joints[0..count) parents first, joint_weights; target; p[0..2] = the aim axis in
                             the last joint's local frame (default (0,0,-1)): the turn that points it at the
                             target is shared by the joints in proportion to their weights (they sum to the
                             requested total). Flag AIM_EACH: every joint aims its own axis fully (eyes);
                             flag LIMITS: each joint is clamped by its cr_modstack_set_limit range. */
    CR_MOD_CONSTRAINT = 4,/* joints[0]; target; kind = CR_CONSTRAINT_*; p[0..2] offset position, p[3..5] offset
                             rotation (Euler XYZ degrees) in the target's frame; AIM uses p[0..2] as the axis */
    CR_MOD_SPRING = 5,    /* joints [start, end]: each chain joint swings toward its child; p[0] stiffness
                             (default 1), p[1] drag 0..1 (0.4), p[2..4] gravity (world units/s^2, a vector),
                             p[5] tail radius, p[6] fixed step (1/60 s; at most 8 steps a frame). Collides
                             with the stack's colliders. */
    CR_MOD_FULL_BODY = 6  /* joints[0] = the body (hips); limb k (any number) = joints [1+3k, 2+3k, 3+3k] (upper,
                             mid, end) with target slot `target + k` (kind NONE = no goal), pull weight
                             joint_weights[1+3k] (how far an unreachable goal drags the body) and reach weight
                             joint_weights[2+3k]; `pole` = an optional hip goal with weight joint_weights[0];
                             p[0] = iterations (3). The body moves, then each limb is solved two-bone. */
};

enum {
    CR_CONSTRAINT_POINT = 0,
    CR_CONSTRAINT_ORIENT = 1,
    CR_CONSTRAINT_PARENT = 2,  /* point + orient: the joint follows target x offset */
    CR_CONSTRAINT_AIM = 3,
    CR_CONSTRAINT_COPY = 4     /* the target's transform exactly (no offset) */
};

enum {
    CR_MOD_MATCH_ROTATION = 1u << 0,
    CR_MOD_AIM_EACH = 1u << 1,
    CR_MOD_LIMITS = 1u << 2
};

/* One modifier (80 bytes on 64-bit hosts: 4 x i32, 2 pointers, 2 x i32, f32, f32[8], u32; 8-byte aligned).
 * ABI 3: the joint list is a pointer + count of any length (ABI 2 had joints[16] / joint_weights[16] inline,
 * CR_MOD_MAX_JOINTS, 192 bytes). cr_modstack_create COPIES both arrays, so they only have to live for that call.
 * Each type still asks for the joint counts it documents above (TWO_BONE 3, FABRIK / CCD / SPRING 2,
 * CONSTRAINT 1, AIM / ORIENT_WARP >= 1, FULL_BODY / STRIDE_WARP / FOOT_PLACEMENT 1 + 3 per limb or leg). */
typedef struct cr_modifier_desc {
    int32_t type;          /* CR_MOD_* */
    uint32_t flags;        /* CR_MOD_MATCH_ROTATION | ... */
    int32_t kind;          /* CONSTRAINT: CR_CONSTRAINT_* */
    int32_t joint_count;   /* entries of joints (and of joint_weights); >= 0 */
    const int32_t* joints;       /* joint_count joint indices (a leg's end may be -1, see below); NULL only when
                                    joint_count is 0 */
    const float* joint_weights;  /* joint_count weights, or NULL = every weight 0 */
    int32_t target;        /* target slot, -1 = none */
    int32_t pole;          /* TWO_BONE: pole slot; FULL_BODY: hip goal slot; -1 = none */
    float weight;          /* influence 0..1 (cr_modstack_set_weight changes it) */
    float p[8];
    uint32_t reserved;
} cr_modifier_desc;

enum {
    CR_TARGET_NONE = 0,
    CR_TARGET_MODEL = 1,   /* pos + rot in model space */
    CR_TARGET_WORLD = 2,   /* pos + rot in world space (through the frame) */
    CR_TARGET_JOINT = 3    /* the posed frame of `joint` (its pivot, its rotation) x (pos, rot) */
};

/* A target (36 bytes). rot = quaternion x, y, z, w (0,0,0,0 = identity). */
typedef struct cr_target {
    int32_t kind;
    int32_t joint;
    float pos[3];
    float rot[4];
} cr_target;

/* A stack over `rig` (which must outlive it) with target_count target slots (all NONE). NULL on a bad
 * modifier (a joint out of range, a chain whose start is not an ancestor of its end, ...). */
cr_modstack* cr_modstack_create(const cr_rig* rig, const cr_modifier_desc* mods, int32_t mod_count,
                                int32_t target_count);
void cr_modstack_destroy(cr_modstack* stack);
int32_t cr_modstack_set_target(cr_modstack* stack, int32_t target, const cr_target* value);
int32_t cr_modstack_set_weight(cr_modstack* stack, int32_t mod, float weight);
/* A joint's range for CR_MOD_LIMITS: local Euler degrees ref + [min, max] per axis. */
int32_t cr_modstack_set_limit(cr_modstack* stack, int32_t joint, const float* ref_rot_deg, const float* min_deg,
                              const float* max_deg);
/* The Euler order a joint's limit box is read in (Stonebreak #460): CR_LIMIT_XYZ (default; every
 * cr_modstack_set_limit resets it) or CR_LIMIT_YXZ = yaw about the parent's Y, then pitch about X, then roll about
 * Z (a head's look range: a yaw x pitch rectangle). The YXZ box is applied to the turn relative to the reference
 * rotation (exact for any reference); a turn inside the box is not changed. */
#define CR_LIMIT_XYZ 0
#define CR_LIMIT_YXZ 1
int32_t cr_modstack_set_limit_order(cr_modstack* stack, int32_t joint, int32_t order);
/* A collider for spring bones in `joint`'s posed frame: a sphere (a == b) or a capsule a -> b. */
int32_t cr_modstack_add_collider(cr_modstack* stack, int32_t joint, const float* a, const float* b, float radius);
/* Model -> world (16 floats, column-major; NULL = identity). */
int32_t cr_modstack_set_frame(cr_modstack* stack, const float* world_from_model);
/* Forgets the spring bones' motion (a teleport, a respawn): they restart from the next pose. */
int32_t cr_modstack_reset(cr_modstack* stack);
/* A JOINT target for a socket given in rest model space (the OMO / .omchar convention: the socket moves with
 * the joint's skin matrix): fills out (kind JOINT, joint, the offset in the joint's frame). rot_deg = Euler XYZ. */
int32_t cr_target_from_socket(const cr_rig* rig, int32_t joint, const float* pos, const float* rot_deg, cr_target* out);
/* Runs the stack on one slot, advancing spring bones by dt (what CR_OP_MODIFY does). */
int32_t cr_modstack_apply(cr_modstack* stack, cr_poses* poses, int32_t slot, float dt);

/* ---------------------------------------------------------------------------------------------- */
/* Locomotion adaptation (OpenMason #159; still CR ABI 2: modifier types, functions and an error  */
/* code were added). Unreal's Game Animation Sample model: authored locomotion fitted to the       */
/* ground and to the character's real speed and direction.                                        */
/*                                                                                                 */
/* Three more modifier types, run in the stack like the others (influence = the modifier weight).  */
/* Lengths are in MODEL units, "up" is model +Y and the stack's frame maps model to world (uniform */
/* scale). Their tuning and per-frame inputs are keyed VALUES (cr_modstack_set_value, CR_VALUE_*), */
/* not p[]. A LEG is joints [upper, mid, end]; end -1 = the mid joint's tail (a bone's endpoint) is */
/* the ankle and the leg has no foot to align. Order them orientation warp, stride warp, foot      */
/* placement: each reads the pose the one before left.                                             */
/*                                                                                                 */
/*  CR_MOD_ORIENT_WARP   joints[0] = the hips, joints[1..) = the spine parents first, joint_weights  */
/*      [1..) their shares of the counter-rotation (normalized; all 0 = equal). The hips turn about */
/*      up by ANGLE (the movement direction relative to the facing, degrees, counter-clockwise seen */
/*      from above = toward the character's left for a -Z facing), clamped to MAX_ANGLE and eased  */
/*      by RATE; the spine turns back COUNTER x that angle, so the legs run toward the movement    */
/*      while the chest keeps facing forward.                                                       */
/*  CR_MOD_STRIDE_WARP   joints[0] = the pelvis, legs k >= 0 at [1+3k, 2+3k, 3+3k] (any number of   */
/*      legs since ABI 3; ABI 2 stopped at 5). Each foot's                                          */
/*      offset from its thigh along the travel direction (DIR_X, DIR_Z) is scaled by SCALE (the    */
/*      host's speed / (the clip's speed x the play rate it allowed)); with PELVIS > 0 the pelvis  */
/*      drops (PELVIS x what it takes) until every stretched leg reaches; two-bone per leg, the    */
/*      feet keep their animated rotation.                                                         */
/*  CR_MOD_FOOT_PLACEMENT joints[0] = the pelvis, legs as above. Per apply: one ground query for   */
/*      every leg (heel = the ankle; toe = the foot bone's tail when the foot is a bone with       */
/*      length), rays straight down from PROBE_UP above the clips' flat ground (GROUND_Y, model y)  */
/*      to PROBE_DOWN below it. A foot's ground offset = its highest hit - GROUND_Y (it sits on the */
/*      higher tread at a stair edge; no hit = 0: the animation), eased by FOOT_RATE while free.   */
/*      CR_VALUE_LEG(k, CR_LEG_LOCK) in [0,1] (the graph feeds foot_lock curves or sync-marker      */
/*      contacts): rising above                                                                     */
/*      0 pins foot k where it stands, on the ground there, in WORLD space (its height above that  */
/*      ground keeps following the animation: heel roll, a contact keyed a frame early); its target */
/*      is lerp(live, lock, lock value); at 0 (or when the lock is MAX_LOCK_DISTANCE from the live */
/*      foot) it releases and the pull fades out over UNLOCK_TIME. The pelvis drops to the lowest  */
/*      foot's need (clamped to [-MAX_DROP, MAX_RISE], eased by PELVIS_RATE) and never stays so    */
/*      high that a target is out of reach; two-bone per leg (bend plane = the animated knee); the */
/*      foot's animated rotation is turned toward the hit normal by at most MAX_ALIGN degrees.     */
/*      Without a ground function the ground is the flat plane GROUND_Y (locking still works).    */

enum {
    CR_MOD_ORIENT_WARP = 7,
    CR_MOD_STRIDE_WARP = 8,
    CR_MOD_FOOT_PLACEMENT = 9
};

/* (CR_MAX_LEGS is gone since ABI 3: STRIDE_WARP / FOOT_PLACEMENT take any number of legs.) */

/* Keyed modifier values. Inputs (set and get; defaults in brackets). Retired in ABI 3 (per-leg values are keyed
 * by CR_VALUE_LEG below): keys 8..12 (the ABI 2 locks CR_VALUE_LOCK + k, k < 5) - set: CR_ERR_RANGE, get: 0 - and
 * keys 66..80 (the ABI 2 outputs OUT_LOCKED / OUT_GROUND / OUT_ALPHA + k) - get: 0. */
enum {
    CR_VALUE_ANGLE = 0,              /* ORIENT: degrees [0] */
    CR_VALUE_MAX_ANGLE = 1,          /* ORIENT: degrees [90] */
    CR_VALUE_RATE = 2,               /* ORIENT: easing rate 1/s [0 = instant] */
    CR_VALUE_COUNTER = 3,            /* ORIENT: share of the turn the spine takes back [1] */
    CR_VALUE_SCALE = 4,              /* STRIDE: stride scale [1] */
    CR_VALUE_DIR_X = 5,              /* STRIDE: travel direction in model XZ [0, -1] */
    CR_VALUE_DIR_Z = 6,
    CR_VALUE_PELVIS = 7,             /* STRIDE: pelvis adjustment 0..1 [1] */
    CR_VALUE_PROBE_UP = 13,          /* FOOT: [0 = the mean leg length: a ray never starts below the hips] */
    CR_VALUE_PROBE_DOWN = 14,        /* FOOT: [0 = 0.75 x the mean leg length] */
    CR_VALUE_MAX_DROP = 15,          /* FOOT: [0 = half the mean leg length] */
    CR_VALUE_MAX_RISE = 16,          /* FOOT: [0] */
    CR_VALUE_MAX_ALIGN = 17,         /* FOOT: degrees [30] */
    CR_VALUE_PELVIS_RATE = 18,       /* FOOT: 1/s [10; 0 = instant] */
    CR_VALUE_FOOT_RATE = 19,         /* FOOT: 1/s [15; 0 = instant] */
    CR_VALUE_GROUND_Y = 20,          /* FOOT: the clips' flat ground, model y [0] */
    CR_VALUE_UNLOCK_TIME = 21,       /* FOOT: seconds [0.15] */
    CR_VALUE_MAX_LOCK_DISTANCE = 22, /* FOOT: [0 = half the mean leg length] */
    CR_VALUE_INPUT_COUNT = 23        /* one past the last scalar input key */
};
/* Outputs of the last apply (get only): */
enum {
    CR_VALUE_OUT_ANGLE = 64,         /* ORIENT: the eased angle */
    CR_VALUE_OUT_PELVIS = 65         /* STRIDE / FOOT: the pelvis offset along up */
};

/* Per-leg values (ABI 3; replaces the 5-wide ABI 2 numbering LOCK + k = 8.., OUT_LOCKED + k = 66..,
 * OUT_GROUND + k = 71.., OUT_ALPHA + k = 76..). The key of field f of leg k (0-based, in the modifier's leg
 * order) is
 *     CR_VALUE_LEG(k, f) = CR_VALUE_LEG_BASE + CR_VALUE_LEG_STRIDE * k + f      (256 + 16k + f)
 * read / written through the same cr_modstack_set_value / cr_modstack_get_value (and cg_instance_modifier_value).
 * Keys at or above CR_VALUE_LEG_BASE belong to legs only; a leg index >= the modifier's leg count, or a field
 * the type does not have, is not a key of that modifier (set: CR_ERR_RANGE, get: 0). Fields 4..15 of each leg
 * are reserved. */
#define CR_VALUE_LEG_BASE 256
#define CR_VALUE_LEG_STRIDE 16
#define CR_VALUE_LEG(leg, field) (CR_VALUE_LEG_BASE + CR_VALUE_LEG_STRIDE * (leg) + (field))
enum {
    CR_LEG_LOCK = 0,        /* FOOT input: the leg's lock 0..1 [0] */
    CR_LEG_OUT_LOCKED = 1,  /* FOOT output: 1 while the leg is locked */
    CR_LEG_OUT_GROUND = 2,  /* FOOT output: the leg's eased ground offset */
    CR_LEG_OUT_ALPHA = 3    /* FOOT output: how far the leg is pulled to its lock */
};

/* Sets an input value; CR_ERR_RANGE when the modifier's type does not read that key. */
int32_t cr_modstack_set_value(cr_modstack* stack, int32_t mod, int32_t key, float value);
/* An input or output value (0 for a key the type does not have). */
float cr_modstack_get_value(const cr_modstack* stack, int32_t mod, int32_t key);

/* Ground query (the host answers; the engine assumes no world type). ONE call per foot-placement
 * modifier per apply with every probe of every leg, in leg order (heel, then toe), so the order of
 * host calls is fixed. Probes and hits are in WORLD space (through the stack's frame); a probe is the
 * segment origin + t * direction, t in [0, max_distance]. */
typedef struct cr_ground_probe {   /* 64 bytes */
    double origin[3];
    double direction[3];  /* unit length */
    double max_distance;
    int32_t foot;         /* leg index */
    int32_t point;        /* 0 = heel (the ankle), 1 = toe */
} cr_ground_probe;

typedef struct cr_ground_hit {     /* 48 bytes; the host fills one per probe */
    int32_t hit;          /* 0 = no ground along the probe (that point keeps the animation) */
    int32_t surface;      /* host value (e.g. a block id), unused by the engine */
    double point[3];
    float normal[3];      /* need not be normalized; zero = up */
    float reserved;
} cr_ground_hit;

/* Fills out[0..count) (zeroed before the call). A negative return aborts the apply with CR_ERR_HOST. */
typedef int32_t (*cr_ground_fn)(void* user, const cr_ground_probe* probes, int32_t count, cr_ground_hit* out);

/* fn NULL = no ground function (the flat plane). */
int32_t cr_modstack_set_ground(cr_modstack* stack, cr_ground_fn fn, void* user);

/* ---------------------------------------------------------------------------------------------- */
/* Skin weights.                                                                                   */

/* A skin over vertex_count vertices, all unweighted (they follow their part). */
cr_skin* cr_skin_create(int32_t vertex_count);
void cr_skin_destroy(cr_skin* skin);
cr_skin* cr_skin_clone(const cr_skin* skin);
int32_t cr_skin_vertex_count(const cr_skin* skin);
/* Number of vertices that carry authored weights. */
int32_t cr_skin_weighted_count(const cr_skin* skin);

/* Replace one vertex's influences (count 0 = unweighted: follows its part). Joints must be >= 0 and weights
 * finite (NaN / inf, or a joint listed twice whose merged weight overflows to inf: CR_ERR_ARG, nothing changed);
 * a joint listed twice is merged (weights add); weights <= 0 are dropped; the rest are normalized to sum 1 and
 * kept heaviest first (ties: lower joint first). A weight whose normalized float underflows to 0 is dropped too
 * (the rest renormalized), so every stored weight is > 0. ABI 3: every influence is kept (ABI 2 kept the heaviest
 * 4). Storage: one pool per skin, compacted as it goes (at most about 2x the live influences + 4096);
 * CR_ERR_RANGE when the skin would hold more than 2^32 - 1 influences, CR_ERR_ARG (cr_last_error says so) when
 * out of memory - no skin function lets an exception out. */
int32_t cr_skin_set(cr_skin* skin, int32_t vertex, const int32_t* joints, const float* weights,
                    int32_t count);
/* A vertex's influences, heaviest first: writes the first min(count, capacity) of them (either output may be
 * NULL; capacity <= 0 writes nothing) and returns the vertex's FULL count (0 = unweighted or a bad vertex), so a
 * return above capacity means the arrays were too small. */
int32_t cr_skin_get(const cr_skin* skin, int32_t vertex, int32_t* out_joints, float* out_weights,
                    int32_t capacity);
/* A vertex's influence count (0 = unweighted or a bad vertex); no copy. */
int32_t cr_skin_influence_count(const cr_skin* skin, int32_t vertex);
/* The largest influence count of any vertex (0 = nothing weighted). */
int32_t cr_skin_max_influences(const cr_skin* skin);
/* The number of influences over every weighted vertex (the joints / weights length of cr_skin_export);
 * CR_ERR_RANGE (negative) when that exceeds INT32_MAX - never a clamped value. */
int32_t cr_skin_influence_total(const cr_skin* skin);
/* Weight of one joint at every vertex (0 for unweighted vertices unless part_joint_of_vertex is given:
 * then an unweighted vertex reports 1 for its part's joint). out: vertex_count floats. */
int32_t cr_skin_joint_weights(const cr_skin* skin, int32_t joint, const int32_t* part_joint_of_vertex,
                              float* out);

/* Bulk import/export in a sparse CSR layout (ABI 3; replaces the 40-byte cr_skin_entry with 4 fixed slots):
 *   vertices[i]                      the vertex of entry i (i < count),
 *   offsets[i] .. offsets[i+1]       its influences in joints[] / weights[] (count + 1 offsets, non-decreasing,
 *                                    offsets[0] >= 0; the arrays hold offsets[count] entries),
 *   joints[k], weights[k]            int32 joint, float weight.
 * Import: each entry is cr_skin_set(vertices[i], joints + offsets[i], weights + offsets[i], offsets[i+1] -
 * offsets[i]) (an entry with no influences makes its vertex unweighted). vertices may be NULL = entry i is
 * vertex i (a dense layout). CR_ERR_ARG on a bad layout or a non-finite weight anywhere in
 * [offsets[0], offsets[count]) (nothing written); CR_ERR_RANGE when an entry fails cr_skin_set (a vertex outside
 * the skin, a negative joint, merged weights overflowing, a full pool / out of memory; the entries before it are
 * applied). */
int32_t cr_skin_import(cr_skin* skin, int32_t count, const int32_t* vertices, const int32_t* offsets,
                       const int32_t* joints, const float* weights);
/* Export: every weighted vertex in ascending order, its influences heaviest first (what cr_skin_get returns).
 * Returns the number of weighted vertices n (= cr_skin_weighted_count). Writes out_vertices[n],
 * out_offsets[n + 1] (out_offsets[0] = 0, out_offsets[n] = cr_skin_influence_total), out_joints and
 * out_weights[cr_skin_influence_total] only when all four are non-NULL, vertex_capacity >= n and
 * influence_capacity >= the total; otherwise it writes nothing (a size query). CR_ERR_RANGE when the total
 * exceeds INT32_MAX. */
int32_t cr_skin_export(const cr_skin* skin, int32_t vertex_capacity, int32_t influence_capacity,
                       int32_t* out_vertices, int32_t* out_offsets, int32_t* out_joints, float* out_weights);

/* GPU packing (ABI 3, OpenMason #170; GL 3.3: a vertex attribute + a buffer texture, no SSBO). Two arrays:
 *   RANGES      2 x uint32 per vertex (vertex_count entries, every vertex): (offset, count) into INFLUENCES -
 *               an integer vertex attribute (uvec2, glVertexAttribIPointer GL_UNSIGNED_INT).
 *   INFLUENCES  2 x uint16 per influence: (joint, weight) - a buffer texture of format GL_RG16UI (texelFetch
 *               -> uvec2: x = joint index into the palette, y = weight as unorm16: w = y / 65535.0).
 * Vertex v's influences are INFLUENCES[offset .. offset + count), laid out in vertex order (offset of v + 1 =
 * offset + count of v), heaviest first. A weighted vertex's quantized weights sum to EXACTLY 65535: q_k =
 * floor(65535 w_k / sum w) plus one unit to the largest remainders (ties: the earlier, i.e. heavier,
 * influence) until the sum is 65535; a q_k may be 0 (a tiny influence; it still occupies its slot). An
 * unweighted vertex gets one influence (part_joint_of_vertex[v], 65535); joint 0 when part_joint_of_vertex is
 * NULL or the entry is outside [0, CR_MAX_JOINTS]. An influence joint above 65535 is written as 65535. Every count
 * is >= 1. The skinned position is sum_k (q_k / 65535) K(joint_k) v - cr_skin_deform computes exactly that (same
 * q_k), so CPU picking / bounds match the GPU. */
/* The number of influence records cr_skin_pack writes: sum over every vertex of max(count, 1); CR_ERR_RANGE
 * (negative) when that exceeds INT32_MAX. */
int32_t cr_skin_pack_size(const cr_skin* skin);
/* out_ranges: vertex_count * 2 uint32; out_influences: influence_capacity * 2 uint16 (influence_capacity >=
 * cr_skin_pack_size, else CR_ERR_ARG and nothing written; CR_ERR_RANGE when the record count exceeds INT32_MAX).
 * Joints are written as uint16 (CR_MAX_JOINTS). */
int32_t cr_skin_pack(const cr_skin* skin, const int32_t* part_joint_of_vertex, int32_t influence_capacity,
                     uint32_t* out_ranges, uint16_t* out_influences);

/* Auto weights: each vertex in [vertex_first, vertex_first+vertex_count) is weighted to the bones in
 * `bones` by its distance to their rest-pose segments (head->tail in model space, from the rig):
 * w ~ 1/(d^2 + eps)^falloff over EVERY bone (nearest first; ties: lower joint), or only the max_influences
 * nearest when max_influences > 0 (0 = unlimited; ABI 2 always took the 4 nearest - max_influences 4 gives
 * exactly the ABI 2 weights); then normalized and pruned below min_weight (the heaviest always stays) and
 * renormalized. falloff 0 = rigid to the nearest bone. positions: vertex_count_total*3 floats. */
int32_t cr_skin_auto_weights(cr_skin* skin, const cr_rig* rig, const float* positions,
                             int32_t vertex_first, int32_t vertex_count,
                             const int32_t* bones, int32_t bone_count,
                             float falloff, float min_weight, int32_t max_influences);

/* Brush modes. */
enum {
    CR_PAINT_ADD = 0,       /* raise the joint's weight toward 1 */
    CR_PAINT_SUBTRACT = 1,  /* lower it toward 0 */
    CR_PAINT_REPLACE = 2,   /* move it toward `strength` */
    CR_PAINT_SMOOTH = 3     /* average each influence with the vertex's mesh neighbours */
};

/* A spherical brush stroke at `center` (model space) with `radius`: each vertex inside is changed by
 * strength * falloff(d/radius) (smooth cubic), the other influences rescaled so the total stays 1.
 * Unweighted vertices start from their part joint (part_joint_of_vertex, may be null = none).
 * indices (triangles) are only read by SMOOTH. only_vertices (mask of vertex_count bytes, null = all)
 * limits the stroke. Returns the number of vertices changed. */
int32_t cr_skin_paint(cr_skin* skin, const float* positions, const int32_t* part_joint_of_vertex,
                      const int32_t* indices, int32_t index_count,
                      const uint8_t* only_vertices,
                      const float* center, float radius, int32_t joint, int32_t mode, float strength);

/* Normalize every weighted vertex to sum 1 (vertices whose weights vanished become unweighted). */
int32_t cr_skin_normalize(cr_skin* skin);
/* Drop influences below min_weight and renormalize. Returns the number of influences removed. */
int32_t cr_skin_prune(cr_skin* skin, float min_weight);
/* Remove a joint from every vertex (renormalizing); vertices left empty become unweighted. */
int32_t cr_skin_remove_joint(cr_skin* skin, int32_t joint);
/* Remap joint indices (old -> new; -1 drops the influence). map_count = old joint count. */
int32_t cr_skin_remap_joints(cr_skin* skin, const int32_t* map, int32_t map_count);
/* Mirror across the plane axis = 0 (0 = X, 1 = Y, 2 = Z): for every vertex on the source side
 * (coordinate sign `source_sign`, +1/-1) the nearest vertex on the other side within `tolerance` —
 * and every vertex at exactly that position (split UV seams) — receives its weights with joints mapped through joint_mirror (index -> mirrored index; null or
 * -1 = same joint). Returns the number of distinct vertices written. */
int32_t cr_skin_mirror(cr_skin* skin, const float* positions, int32_t axis, int32_t source_sign,
                       float tolerance, const int32_t* joint_mirror, int32_t joint_count);
/* Transfer onto a new vertex set (the mesh was edited): every new vertex takes the weights of the
 * nearest old vertex (old_positions: old count*3, new_positions: new_count*3). Returns a new skin. */
cr_skin* cr_skin_transfer(const cr_skin* skin, const float* old_positions,
                          const float* new_positions, int32_t new_vertex_count);

/* Deform positions on the CPU with a palette (K matrices from cr_rig_pose): out = sum (q/65535)·K·v with the
 * SAME quantized unorm16 weights q as cr_skin_pack (accumulated in double, one float cast per component). ABI 3
 * CHANGED THE NUMBERS: ABI 2 blended with the float weights (and the GPU with unorm8), so a weighted vertex moves
 * by up to about 1/65535 of its influences' spread. An unweighted vertex is rigid to part_joint_of_vertex[v], or
 * joint 0 when the array is NULL or the entry is outside [0, joint_count) (pack's rule; ABI 2 left such a vertex
 * at the origin); a weighted influence whose joint is outside [0, joint_count) is skipped (its share is lost).
 * skin may be NULL (every vertex rigid). No heap allocation per call once a thread has run it (per-thread scratch).
 * For picking/bounds on a posed mesh. */
int32_t cr_skin_deform(const cr_skin* skin, const int32_t* part_joint_of_vertex,
                       const float* positions, int32_t vertex_count,
                       const float* skin_matrices, int32_t joint_count, float* out_positions);

/* ---------------------------------------------------------------------------------------------- */
/* First-person view (OpenMason #161; still CR ABI 2).                                             */
/*                                                                                                 */
/* One character, three views (Unreal 5.5 first-person rendering): the owner's camera sees the     */
/* character's first-person joints (arms, hands, held items) with their own field of view, squeezed */
/* into a thin depth slab just past the world's near plane so they never clip into walls; the full  */
/* world-space body casts shadows and is what every other camera sees; the owner may also see the  */
/* body minus head and arms (body awareness). The pieces here are rig-generic: character.h names    */
/* the role masks and the eyes point, anim_graph.h the first-person layers. A frame:               */
/*   1. cg_instance_evaluate -> the body palette (shadow passes, other cameras);                    */
/*   2. camera = cr_stabilizer_update(cc_char_eyes_point(body palette)) + the host's look rotation; */
/*   3. cr_first_person_projection(camera view, world projection) -> FP projection + pull matrix;  */
/*   4. cg_instance_compose_view(FIRST_PERSON, base = pull x base) + cr_palette_hide(FP mask) ->    */
/*      the FP palette, drawn with the camera view and the FP projection (sockets of this palette   */
/*      mount held items in first-person space);                                                   */
/*   5. optional: a copy of the body palette + cr_palette_hide(body-awareness mask), world projection. */

/* Hides joints by SCALING THEM TO ZERO in the skin matrices (in place); world matrices are not
 * touched, so sockets and attachments read from them (or read before hiding) stay valid. visible =
 * a weight per joint (1 = untouched, 0 = gone, between = shrunk part way). A hidden joint shrinks
 * toward the point of its HIDE ROOT (the joint itself when its parent is visible or it has none,
 * else its parent's hide root): a hidden branch collapses to one point like an inherited zero
 * scale, so its triangles have no area; vertices weighted between a visible and a hidden joint are
 * pulled toward that point. The point is a part's pivot / a bone's head (world matrices, so the
 * host's base matrix is included): K'(j) = T(p)·S(v_j)·T(-p)·K(j). world, skin: joint_count*16. */
int32_t cr_palette_hide(const cr_rig* rig, const cr_mask* visible, const float* world, float* skin);

/* First-person projection parameters (32 bytes). A field <= 0 takes its default. */
typedef struct cr_first_person_desc {
    float fov_y_deg;    /* vertical field of view of the first-person view, independent of the world's
                           (default: the world projection's own) */
    float near;         /* first-person near plane: true view depth (default 0.01) */
    float far;          /* far end of the first-person depth range: true view depth (default 2) */
    float depth_scale;  /* thickness of the depth slab the range [near, far] is squeezed into, as a
                           fraction of the world's near plane n: the slab is [n, n·(1 + depth_scale)]
                           (default 0.5) */
    float pull;         /* uniform scale about the eye (0 < pull <= 1) applied to first-person geometry in
                           WORLD space: the screen image is unchanged (a ray through the eye) but
                           lighting, fog and shadow lookups happen at the pulled point, near the camera,
                           never inside the wall the real arm would poke into (default 0.2; 1 = true
                           positions) */
    uint32_t flags;     /* 0 */
    float reserved[2];  /* 0 */
} cr_first_person_desc;

/* The first-person projection for a camera. view: world -> view (rigid, column-major); world_projection:
 * the world's OpenGL perspective (clip z in [-w, w], w = view depth; off-centre and jitter terms are
 * kept). out_projection: same aspect, the first-person FOV (x and y scaled by tan(world/2)/tan(fp/2)),
 * w = view depth (perspective-correct), and a depth row that maps (pulled) view depth d into the
 * world's depth range so that 1/d' is affine in 1/d: the first-person near plane lands exactly on the
 * world near plane, the far end on n·(1 + depth_scale). Depths stay ordered (the arm keeps its own
 * occlusion) and every first-person fragment is in front of world geometry farther than the slab.
 * out_pull: T(eye)·S(pull)·T(-eye) in world space; compose the first-person palette with
 * base = out_pull x the character's base matrix and draw it with `view` and out_projection.
 * Either output may be null. CR_ERR_ARG on a non-perspective world projection or bad desc. */
int32_t cr_first_person_projection(const float* view, const float* world_projection, const cr_first_person_desc* desc,
                                   float* out_projection, float* out_pull);

/* Head-bob stabilizer for a camera on a posed point (the eyes). It separates bob from intended motion by
 * AMPLITUDE: the point is followed in the character's MODEL space (through the inverse of the base matrix,
 * so walking and turning never lag) by a slow critically damped filter; the camera = filtered + the raw
 * point's deviation from it clamped to `threshold` (length). Bob up to the threshold passes through exactly,
 * larger bob is removed; a deviation beyond `max_offset` counts as intended motion (crouch, mantle, a
 * stagger): the filter is dragged along so it never trails the point by more than max_offset (the camera by
 * max_offset - threshold), then settles within about the time constant. */
typedef struct cr_stabilizer cr_stabilizer;
typedef struct cr_stabilizer_desc {  /* 16 bytes; a field <= 0 takes its default */
    float threshold;      /* largest deviation that passes through (default 0.02) */
    float max_offset;     /* deviations beyond this are intended motion, not bob (default 0.1; >= threshold) */
    float time_constant;  /* seconds the filter takes to catch up, about (default 0.3) */
    float snap_distance;  /* a raw point this far from the filter resets it (teleport; default 1) */
} cr_stabilizer_desc;

/* desc NULL = defaults. */
cr_stabilizer* cr_stabilizer_create(const cr_stabilizer_desc* desc);
void cr_stabilizer_destroy(cr_stabilizer* s);
/* The next update starts the filter at its raw point. */
int32_t cr_stabilizer_reset(cr_stabilizer* s);
/* point: the raw world point (3 floats, e.g. cc_char_eyes_point); base: the character's base matrix of
 * the frame (16 floats, NULL = identity); dt seconds (<= 0 = no time passes); out: the stabilized world
 * point (3 floats; may alias point). */
int32_t cr_stabilizer_update(cr_stabilizer* s, const float* point, const float* base, float dt, float* out);

/* ---------------------------------------------------------------------------------------------- */
/* Morph targets / shape keys (ABI 3, #171 additions; src/rig/morph.cpp, ctest `morph`).           */
/*                                                                                                 */
/* Blender's RELATIVE shape keys: a TARGET is a set of sparse per-vertex position deltas (and,      */
/* optionally, normal deltas) from the basis mesh; the deformed vertex is                          */
/*     v = basis + sum_k w_k d_k        n = normalize(n_basis + sum_k w_k dn_k)                     */
/* before skinning (morph, then skin: cr_skin_deform_morphed). Weights are clamped to each target's */
/* [min, max] when APPLIED (cr_morph_apply, cr_morphs_clamp_weights for the GPU upload): Blender's  */
/* shape key "Range Min / Max" are the value's hard range (slider_min / slider_max; keyed, driven   */
/* and typed values all land clamped, -10..10 at most). Weights inside the pose pipeline are NOT    */
/* clamped (an additive layer may push past a range; the mesh clamps).                             */
/*                                                                                                 */
/* MORPH CHANNELS: a rig may carry named weight channels (cr_rig_set_morph_channels, normally the  */
/* targets of the mesh it poses, in target order: cr_rig_set_morph_channels_from). Every pose slot   */
/* then holds one weight + a DRIVEN flag per channel beside its joints:                            */
/*   REST          weights = the channel defaults, none driven                                     */
/*   SAMPLE        the clip's morph tracks bound BY NAME (exact, then case-insensitive; a track     */
/*                 naming no channel is ignored, a channel no track names keeps its default and is  */
/*                 not driven); CLIP_MASK: a channel is covered when the clip's mask is empty or    */
/*                 lists the channel name or "morph:<name>" (case-insensitive); DRIVE_ALL drives     */
/*                 every channel; MORPHS_ONLY samples the channels and leaves the joints            */
/*   COPY          copies them                                                                     */
/*   BLEND         weighted like joint translations (Euler mode: the running lerp; CR_POSE_QUAT:    */
/*                 the normalized weighted sum in double); a single positive weight copies exactly; */
/*                 drives the union                                                                */
/*   LAYER         OVERRIDE: on channels src drives, w = min(weight x the mask's morph weight, 1),   */
/*                 dst = dst + (src - dst) w (w >= 1: src exactly; NO_PASSTHROUGH: from the default) */
/*                 ADDITIVE: dst += delta x w on channels the delta drives                          */
/*   MAKE_ADDITIVE delta = a - b (driven = a | b, both spaces; equal values: exact 0)               */
/*   INERTIALIZE   per channel the same quintic (Bollo) on the offset magnitude, its sign kept      */
/*   REMOVE_ROOT / MODIFY   leave the channels alone                                               */
/*   MORPH         (CR_OP_MORPH) overrides channels from a value array                             */
/* cr_mask carries a weight per channel too (filled like the joints at create / cr_mask_fill;      */
/* cr_mask_set_morph; cr_mask_from_clip covers channels by the SAMPLE CLIP_MASK rule).             */
/* cr_rig_pose (the ABI 1 entry points) composes joints only; read weights from cr_poses.          */
/*                                                                                                 */
/* Lifetimes / threading: cr_rig_set_morph_channels and cr_clip_set_morph_tracks configure an      */
/* object BEFORE it is used: they fail (CR_ERR_ARG) once the rig has a cr_poses / cr_mask /         */
/* cr_inertia or once a clip was bound to a rig (any pose call that samples it). A cr_morphs is     */
/* immutable and may be read by any number of threads.                                             */

typedef struct cr_morphs cr_morphs;
struct co_doc; /* cenda/model.h */

/* Morph-key interpolation (the values of anim_clip.h CM_INTERP_*). CUBIC = Hermite with the keys' tangents in
 * value per second (glTF CUBICSPLINE: in = a_k, out = b_k). */
enum {
    CR_MORPH_LINEAR = 0,
    CR_MORPH_STEP = 1,
    CR_MORPH_CUBIC = 2
};

/* One morph-weight key (20 bytes; the layout of anim_clip.h cm_curve_key). interp shapes the segment after it. */
typedef struct cr_morph_key {
    float time;
    float value;
    float in_tangent;
    float out_tangent;
    int32_t interp;  /* CR_MORPH_* */
} cr_morph_key;

#define CR_MAX_MORPH_TARGETS 65535

/* --- Rig morph channels ---------------------------------------------------------------------------------- */

/* Sets the rig's morph channels (count 0..CR_MAX_MORPH_TARGETS; 0 clears). names: count NUL-terminated strings,
 * unique (case-insensitive) and non-empty; defaults NULL = 0. CR_ERR_ARG on bad names or once the rig is in use
 * (see above). */
int32_t cr_rig_set_morph_channels(cr_rig* rig, int32_t count, const char* const* names, const float* defaults);
/* The targets of `morphs` as the rig's channels (channel i = target i: weights index the same way everywhere). */
int32_t cr_rig_set_morph_channels_from(cr_rig* rig, const cr_morphs* morphs);
int32_t cr_rig_morph_count(const cr_rig* rig);
/* NULL for a bad channel. */
const char* cr_rig_morph_name(const cr_rig* rig, int32_t channel);
float cr_rig_morph_default(const cr_rig* rig, int32_t channel);
/* Channel by name (exact, then case-insensitive; a leading "morph:" is accepted); -1 when none. */
int32_t cr_rig_morph_find(const cr_rig* rig, const char* name);

/* --- Clip morph tracks ----------------------------------------------------------------------------------- */

/* Sets a clip's morph tracks (replacing any): track t = names[t] (non-empty, unique by exact name), keys
 * keys[offsets[t] .. offsets[t+1]) (count + 1 offsets, non-decreasing from 0; sorted by time here, stably; finite
 * values; interp CR_MORPH_*). count 0 clears. Sampling follows anim_clip.h cm_clip_curve_sample exactly (clamped
 * before the first / after the last key; in double, one float cast), at the time SAMPLE uses for the joints
 * (wrapped / clamped like cr_rig_pose). CR_ERR_ARG on bad data or once the clip was bound (see above). */
int32_t cr_clip_set_morph_tracks(cr_clip* clip, int32_t count, const char* const* names, const int32_t* offsets,
                                 const cr_morph_key* keys);
int32_t cr_clip_morph_track_count(const cr_clip* clip);
const char* cr_clip_morph_track_name(const cr_clip* clip, int32_t track);
/* A morph track's value at a clip time (not wrapped; clamped to its keys); 0 for a bad track. */
float cr_clip_sample_morph(const cr_clip* clip, int32_t track, float time);
/* The track the clip uses for a rig channel (-1 = none). */
int32_t cr_clip_morph_track_for(const cr_clip* clip, const cr_rig* rig, int32_t channel);

/* --- Pose slots and masks -------------------------------------------------------------------------------- */

/* A slot's morph weights and driven flags (either output may be NULL): writes min(count, capacity) entries and
 * returns the rig's channel count (negative = error). */
int32_t cr_poses_morph_weights(const cr_poses* poses, int32_t slot, float* out_weights, uint8_t* out_driven,
                               int32_t capacity);
/* Sets a slot's morph weights (cr_rig_morph_count floats); driven NULL = every channel driven. */
int32_t cr_poses_set_morph_weights(cr_poses* poses, int32_t slot, const float* weights, const uint8_t* driven);

int32_t cr_mask_morph_count(const cr_mask* mask);
/* Sets a channel's weight (clamped to [0,1]); channel -1 = every channel. */
int32_t cr_mask_set_morph(cr_mask* mask, int32_t channel, float weight);
/* cr_mask_morph_count floats. */
int32_t cr_mask_get_morph(const cr_mask* mask, float* out_weights);

/* --- Mesh targets (cr_morphs) ---------------------------------------------------------------------------- */

/* A target set over vertex_count vertices (the SOUP vertex order the host draws), from a target-major CSR:
 *   names[t]                         target names (non-empty, unique case-insensitive)
 *   params[3t .. 3t+2]               default weight, min, max (NULL = 0, 0, 1; min <= max, finite)
 *   offsets[t] .. offsets[t+1]       target t's rows (target_count + 1 offsets, non-decreasing from 0)
 *   vertices[r], deltas[3r..3r+2]    a row: a vertex and its position delta
 *   normal_deltas[3r..3r+2]          its normal delta (NULL = none for every row)
 * Rows naming the same (target, vertex) are summed (in row order); a row whose position AND normal deltas are all
 * exactly 0 is dropped (sparse). NULL on a bad argument (a vertex outside [0, vertex_count), non-finite data, too
 * many targets: cr_last_error says why). */
cr_morphs* cr_morphs_create(int32_t vertex_count, int32_t target_count, const char* const* names, const float* params,
                            const int32_t* offsets, const int32_t* vertices, const float* deltas,
                            const float* normal_deltas);
/* From an OMO 1.9 document's morphs (model.h co_doc_morphs_get): part-local rows mapped to soup vertices as
 * part_first_vertex[p] + local for the part whose id is part_ids[p] (part_count parts; a row whose part is not
 * listed or whose local vertex is outside [0, part_vertex_count[p]) is skipped and counted in *out_skipped, which
 * may be NULL). Morph names, defaults and ranges as stored; normal deltas where the part has them. A document
 * without morphs gives a set with 0 targets. NULL on a bad argument. */
cr_morphs* cr_morphs_from_doc(const struct co_doc* doc, int32_t vertex_count, int32_t part_count,
                              const char* const* part_ids, const int32_t* part_first_vertex,
                              const int32_t* part_vertex_count, int32_t* out_skipped);
/* The same targets over a new vertex list: new vertex v takes every row of source_vertex[v] (-1 = none) - what a
 * loader that splits shared vertices (or re-orders them) needs. NULL on a bad argument. */
cr_morphs* cr_morphs_remap(const cr_morphs* morphs, int32_t vertex_count, const int32_t* source_vertex);
void cr_morphs_destroy(cr_morphs* morphs);

int32_t cr_morphs_vertex_count(const cr_morphs* morphs);
int32_t cr_morphs_target_count(const cr_morphs* morphs);
/* Stored rows over every target (after merging and dropping zeros) = cr_morphs_pack_size. */
int32_t cr_morphs_row_count(const cr_morphs* morphs);
/* 1 when any row carries a normal delta. */
int32_t cr_morphs_has_normals(const cr_morphs* morphs);
const char* cr_morphs_target_name(const cr_morphs* morphs, int32_t target);
/* out3 = default weight, min, max. */
int32_t cr_morphs_target_params(const cr_morphs* morphs, int32_t target, float* out3);
/* Target by name (exact, then case-insensitive; a leading "morph:" is accepted); -1 when none. */
int32_t cr_morphs_find(const cr_morphs* morphs, const char* name);
/* A target's rows in ascending vertex order: writes min(rows, capacity) (each output may be NULL; normals 3 per
 * row, zeros where none) and returns the row count. */
int32_t cr_morphs_target_rows(const cr_morphs* morphs, int32_t target, int32_t* out_vertices, float* out_deltas,
                              float* out_normal_deltas, int32_t capacity);

/* Weights as applied: out[t] = clamp(in[t], min_t, max_t) for t < count; a missing weight (t >= count, or weights
 * NULL) and a NaN read as 0 before clamping. out: target_count floats - the per-frame GPU upload (see the packing
 * below). */
int32_t cr_morphs_clamp_weights(const cr_morphs* morphs, const float* weights, int32_t count, float* out);

/* CPU apply: positions_out = positions + sum_k w_k d_k per vertex (w = cr_morphs_clamp_weights of the given
 * weights), accumulated in FLOAT in the packed record order (vertex, then target ascending): acc = 0; acc += w·d
 * (x, y, z); out = in + acc - what the GPU program below does, so the two agree up to its fused-multiply-add
 * rounding. A vertex without rows (or whose rows all have weight 0) is copied exactly. Normals (both pointers
 * non-NULL; else ignored): a vertex with at least one row of non-zero weight that carries a normal delta gets
 * normalize(n + sum_k w_k dn_k) (accumulated like the positions, normalized in double, one float cast; a zero-length
 * result keeps n); every other normal is copied exactly (no renormalization). positions / normals: vertex_count x 3
 * floats; the outputs may alias the inputs. No allocation. */
int32_t cr_morph_apply(const cr_morphs* morphs, const float* weights, int32_t weight_count, const float* positions,
                       const float* normals, float* out_positions, float* out_normals);

/* Morph, then skin (positions only): cr_morph_apply into a per-thread scratch buffer, then cr_skin_deform (same
 * arguments as there) on the morphed positions. morphs NULL = cr_skin_deform. The documented order for every
 * host: shape keys act on the bind-pose mesh, skinning moves the result (Blender's modifier order). */
int32_t cr_skin_deform_morphed(const cr_skin* skin, const int32_t* part_joint_of_vertex, const cr_morphs* morphs,
                               const float* weights, int32_t weight_count, const float* positions, int32_t vertex_count,
                               const float* skin_matrices, int32_t joint_count, float* out_positions);

/* GPU packing (GL 3.3: a vertex attribute + buffer textures, no SSBO). Uploaded ONCE per mesh:
 *   RANGES   2 x uint32 per vertex (vertex_count entries, every vertex): (offset, count) into the records; count 0 =
 *            no morph rows. An integer vertex attribute (uvec2, glVertexAttribIPointer GL_UNSIGNED_INT; e.g.
 *            location 3).
 *   DELTAS   4 x float per record, a GL_RGBA32F buffer texture: texelFetch -> (dx, dy, dz, target), the target index
 *            stored as an exact float (< 2^24).
 *   NORMALS  4 x float per record, a second GL_RGBA32F buffer texture: (nx, ny, nz, 0), same record index (only
 *            when cr_morphs_has_normals; zeros for rows without a normal delta).
 * Records are laid out in vertex order (offset of v + 1 = offset + count of v), each vertex's rows by ascending
 * target; zero rows are not stored. Per FRAME only the WEIGHTS are uploaded: target_count floats from
 * cr_morphs_clamp_weights, a GL_R32F buffer texture (texelFetch(weights, int(rec.w)).r). Vertex shader, BEFORE
 * skinning:
 *     vec3 d = vec3(0), dn = vec3(0);
 *     for (uint i = r.x; i < r.x + r.y; ++i) { vec4 rec = texelFetch(deltas, int(i)); float w = texelFetch(weights,
 *         int(rec.w)).r; d += w * rec.xyz; dn += w * texelFetch(normals, int(i)).xyz; }
 *     pos = skinPosition(s, aPos + d); normal = skinNormal(s, normalize(aNormal + dn));
 * Sizes in bytes: ranges 8 x vertex_count, deltas / normals 16 x cr_morphs_pack_size. */
int32_t cr_morphs_pack_size(const cr_morphs* morphs);
/* out_ranges: vertex_count x 2 uint32; out_deltas / out_normals (may be NULL): record_capacity x 4 floats
 * (record_capacity >= cr_morphs_pack_size, else CR_ERR_ARG and nothing written). Returns CR_OK. */
int32_t cr_morphs_pack(const cr_morphs* morphs, int32_t record_capacity, uint32_t* out_ranges, float* out_deltas,
                       float* out_normals);

#ifdef __cplusplus
}
#endif
