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
 *
 * SKIN AUTHORING (cr_skin_*): an editable per-vertex weight set (up to CR_MAX_INFLUENCES influences
 * per vertex, sparse: vertices without entries follow their part). Auto weights, brush painting,
 * smoothing, normalize, prune, mirror, transfer onto an edited mesh, and the packed GPU attributes.
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

#define CR_ABI_VERSION 1

typedef struct cr_rig cr_rig;
typedef struct cr_clip cr_clip;
typedef struct cr_skin cr_skin;

/* Results. */
enum {
    CR_OK = 0,
    CR_ERR_ARG = -1,   /* null handle, bad index, bad sizes */
    CR_ERR_RANGE = -2  /* an index refers outside the rig / mesh */
};

#define CR_MAX_JOINTS 65535
#define CR_MAX_INFLUENCES 4

/* Joint kinds. */
enum {
    CR_JOINT_PART = 0,
    CR_JOINT_BONE = 1
};

/* Joint flags. */
enum {
    /* The joint moves rendered geometry: a part with faces, or a joint skin weights reference.
     * Used by cr_rig_find_head. */
    CR_JOINT_HAS_GEOMETRY = 1u << 0
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
/* Skin weights.                                                                                   */

/* A skin over vertex_count vertices, all unweighted (they follow their part). */
cr_skin* cr_skin_create(int32_t vertex_count);
void cr_skin_destroy(cr_skin* skin);
cr_skin* cr_skin_clone(const cr_skin* skin);
int32_t cr_skin_vertex_count(const cr_skin* skin);
/* Number of vertices that carry authored weights. */
int32_t cr_skin_weighted_count(const cr_skin* skin);

/* Replace one vertex's influences (count 0 = unweighted: follows its part). Joints must be >= 0;
 * weights <= 0 are dropped; more than CR_MAX_INFLUENCES keep the heaviest; the rest are normalized
 * to sum 1. */
int32_t cr_skin_set(cr_skin* skin, int32_t vertex, const int32_t* joints, const float* weights,
                    int32_t count);
/* A vertex's influences (up to CR_MAX_INFLUENCES, heaviest first) — returns the count (0 = none). */
int32_t cr_skin_get(const cr_skin* skin, int32_t vertex, int32_t* out_joints, float* out_weights);
/* Weight of one joint at every vertex (0 for unweighted vertices unless part_joint_of_vertex is given:
 * then an unweighted vertex reports 1 for its part's joint). out: vertex_count floats. */
int32_t cr_skin_joint_weights(const cr_skin* skin, int32_t joint, const int32_t* part_joint_of_vertex,
                              float* out);

/* Bulk import/export in the sparse file layout: entries of (vertex, count, joints[4], weights[4]).
 * Export: returns the number of weighted vertices; writes up to capacity entries. */
typedef struct cr_skin_entry {
    int32_t vertex;
    int32_t count;
    int32_t joints[CR_MAX_INFLUENCES];
    float weights[CR_MAX_INFLUENCES];
} cr_skin_entry; /* 40 bytes */
int32_t cr_skin_import(cr_skin* skin, const cr_skin_entry* entries, int32_t count);
int32_t cr_skin_export(const cr_skin* skin, cr_skin_entry* out, int32_t capacity);

/* Packed GPU attributes for every vertex: 4 x uint16 joint indices + 4 x uint8 normalized weights
 * (summing to exactly 255). An unweighted vertex gets (part_joint_of_vertex[v], 0,0,0)/(255,0,0,0);
 * part_joint_of_vertex may be null when every vertex is weighted (others then use joint 0). */
int32_t cr_skin_pack(const cr_skin* skin, const int32_t* part_joint_of_vertex,
                     uint16_t* out_joints, uint8_t* out_weights);

/* Auto weights: each vertex in [vertex_first, vertex_first+vertex_count) is weighted to the bones in
 * `bones` by its distance to their rest-pose segments (head->tail in model space, from the rig):
 * w ~ 1/(d^2 + eps)^falloff over the CR_MAX_INFLUENCES nearest, then normalized and pruned below
 * min_weight. falloff 0 = rigid to the nearest bone. positions: vertex_count_total*3 floats. */
int32_t cr_skin_auto_weights(cr_skin* skin, const cr_rig* rig, const float* positions,
                             int32_t vertex_first, int32_t vertex_count,
                             const int32_t* bones, int32_t bone_count,
                             float falloff, float min_weight);

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

/* Deform positions on the CPU with a palette (K matrices from cr_rig_pose): out = sum w·K·v, using
 * the same packed weights as the GPU. For picking/bounds on a posed mesh. */
int32_t cr_skin_deform(const cr_skin* skin, const int32_t* part_joint_of_vertex,
                       const float* positions, int32_t vertex_count,
                       const float* skin_matrices, int32_t joint_count, float* out_positions);

#ifdef __cplusplus
}
#endif
