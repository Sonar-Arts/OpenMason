/* Cenda character editing (OpenMason #163, epic #166): the native model behind Open Mason's Character workspace
 * and its char_* MCP tools. Part of CC ABI 1 (additions, like retarget.h and appearance.h); sources
 * src/character/character_edit.cpp (session, role states, diagram, overlay, standard sockets) and
 * characterize.cpp (named reference poses, mirroring); ctest `character_edit`.
 *
 * EDIT SESSION. A cc_charedit records the states of one definition so edits can be undone. Every change goes
 * through the ordinary cc_char_* setters between cc_charedit_begin and cc_charedit_commit; the outermost commit
 * compares the definition with the last recorded state and pushes ONE undo step when it changed (an unchanged
 * group pushes nothing). Groups nest (only the outermost one counts), so one MCP call or one panel action is one
 * step however many setters it runs. cc_charedit_cancel puts back the state the outermost group started from
 * (all-or-nothing batches). A merge key folds consecutive commits with the same key into the step on top (a
 * slider drag is one step) until cc_charedit_seal, another key, an undo or a redo. A state is the whole
 * definition; the embedded model and the other archive entries are shared between states while they stay the
 * same, so long histories of a character with an embedded model stay small. Edits made outside a group are
 * folded into the next commit (or recorded as an "edit" step by undo). The revision changes whenever the
 * definition's state changes through the session (commit, cancel, undo, redo): editors key caches on it.
 *
 * Threading: a session belongs to the thread that owns its definition.
 */
#pragma once

#include <stdint.h>

#include "cenda/character.h"
#include "cenda/rig.h"

#ifdef __cplusplus
extern "C" {
#endif

/* --- Edit session ------------------------------------------------------------------------------------- */

typedef struct cc_charedit cc_charedit;

/* A session over c (BORROWED: c must outlive the session; destroy the session first). The current state is
 * the base (not dirty). max_steps <= 0 = 200; the oldest steps drop beyond it. */
cc_charedit* cc_charedit_create(cc_char* c, int32_t max_steps);
void cc_charedit_destroy(cc_charedit* e);
/* Opens a group (label used by the outermost one; NULL = "edit"); merge_key NULL or "" = never merges. */
int32_t cc_charedit_begin(cc_charedit* e, const char* label, const char* merge_key);
/* Closes a group. Outermost: 1 = a new step, 2 = merged into the step on top, 0 = nothing changed; inner: 0.
 * CC_ERR_ARG without an open group. */
int32_t cc_charedit_commit(cc_charedit* e);
/* Closes ALL open groups and puts back the state the outermost one started from. 1 = something was undone,
 * 0 = nothing had changed; CC_ERR_ARG without an open group. */
int32_t cc_charedit_cancel(cc_charedit* e);
/* The next commit starts a new step even with the same merge key. */
int32_t cc_charedit_seal(cc_charedit* e);
/* CC_ERR_RANGE when there is nothing to undo / redo, CC_ERR_ARG while a group is open. */
int32_t cc_charedit_undo(cc_charedit* e);
int32_t cc_charedit_redo(cc_charedit* e);
int32_t cc_charedit_can_undo(const cc_charedit* e);
int32_t cc_charedit_can_redo(const cc_charedit* e);
/* The label of the step undo (which 0) or redo (which 1) would take, "" when none. */
const char* cc_charedit_label(const cc_charedit* e, int32_t which);
int64_t cc_charedit_revision(const cc_charedit* e);
/* 1 when the state differs from the one last marked saved (or the base). */
int32_t cc_charedit_dirty(const cc_charedit* e);
int32_t cc_charedit_mark_saved(cc_charedit* e);
/* Number of open groups. */
int32_t cc_charedit_depth(const cc_charedit* e);
/* Undo steps currently held. */
int32_t cc_charedit_step_count(const cc_charedit* e);
/* Forgets the history; the current state becomes the base (not dirty). CC_ERR_ARG while a group is open. */
int32_t cc_charedit_reset(cc_charedit* e);

/* --- Role states (the Characterization panel's slot colours) ------------------------------------------- */

/* State bits per profile role. */
enum {
    CC_ROLE_MAPPED = 1u << 0,          /* the role maps to a joint */
    CC_ROLE_REQUIRED = 1u << 1,        /* the profile requires it */
    CC_ROLE_JOINT_MISSING = 1u << 2,   /* mapped to a joint the rig lacks */
    CC_ROLE_DUPLICATE = 1u << 3,       /* another role maps to the same joint */
    CC_ROLE_HIERARCHY = 1u << 4,       /* not under its nearest mapped ancestor role's joint */
    CC_ROLE_SIDE_MISMATCH = 1u << 5,   /* its joint lies on the other side */
    CC_ROLE_MIRROR_MISMATCH = 1u << 6, /* it and its mirror role are not mirror images (warning) */
    CC_ROLE_ERROR = 1u << 7            /* summary: unmapped and required, or any error bit above */
};
/* Fills out[i] (capacity entries) with the state bits of the profile's role i, from cc_char_validate (the rig
 * may be NULL: no geometry checks). Returns the profile's role count, or CC_ERR_*. */
int32_t cc_char_role_states(const cc_char* c, const cr_rig* rig, const cc_profile* profile, uint32_t* out,
                            int32_t capacity);

/* --- Profile diagram and named poses (.omprofile 1.1) ------------------------------------------------- */

/* Body-diagram slots: 2 floats per role (x right, y down, 0..1). Roles with an authored `diagram` keep it;
 * the others are laid out from their parent along their direction (front view, or side view for a profile
 * whose root points horizontally), and a profile with no authored slot is fitted into [0.05, 0.95].
 * Returns the role count, or CC_ERR_*. */
int32_t cc_profile_diagram(const cc_profile* p, float* out_xy, int32_t capacity);
/* 1 when role index has an authored slot. */
int32_t cc_profile_role_has_diagram(const cc_profile* p, int32_t index);
int32_t cc_profile_pose_count(const cc_profile* p);
const char* cc_profile_pose_name(const cc_profile* p, int32_t index);
/* cc_char_fit_reference_pose with a named profile pose: its directions replace those of the roles it lists.
 * pose NULL, "" or "T" = the roles' own directions (when the profile has no pose named "T"). CC_ERR_RANGE for an
 * unknown pose. Returns the number of roles turned. */
int32_t cc_char_fit_reference_pose_named(cc_char* c, const cr_rig* rig, const cc_profile* profile, const char* pose);
/* The reference pose as rig locals (9 floats per joint: pos, rot_deg, scale): rest locals with the definition's
 * reference rotations (cr_rig_pose_locals input). */
int32_t cc_char_reference_locals(const cc_char* c, const cr_rig* rig, float* out_locals);

/* --- Mirroring ----------------------------------------------------------------------------------------- */

enum {
    CC_MIRROR_ROLES = 1u << 0,   /* map each mirror role to the joint at the reflected position */
    CC_MIRROR_LIMITS = 1u << 1,  /* copy limits onto the mirror roles' joints, axes reflected */
    CC_MIRROR_SOCKETS = 1u << 2  /* copy sockets named for the side (grip_l, hand_left_socket, ...) */
};
/* Copies one side (CC_SIDE_LEFT / CC_SIDE_RIGHT) of the definition onto the other, by GEOMETRY: the reflection
 * through the body's midplane (normal = the character's left axis from its facing, through the hips role's
 * joint, else the skeleton's centre). Roles: the joint (same kind) nearest the reflected position, within 5%
 * of the skeleton height. Limits: per axis, the reflected axis's nearest axis of the target joint in the
 * reference pose (a rotation about an axis in the midplane changes sign). Sockets: name side tokens swapped,
 * position and rotation reflected, onto the mirror role's joint. Returns a report of what was done (INFO) and
 * what was not (WARNING); *out_count (may be NULL) = entries written. NULL on failure (CC_ERR_LOCKED in
 * cc_last_error when ROLES is asked of a locked definition). */
cc_report* cc_char_mirror(cc_char* c, const cr_rig* rig, const cc_profile* profile, int32_t from_side,
                          uint32_t what, int32_t* out_count);

/* --- Standard sockets ---------------------------------------------------------------------------------- */

/* "grip_r", "grip_l", "head", "eyes", "back" (#153's standard names). */
int32_t cc_char_standard_socket_count(void);
const char* cc_char_standard_socket_name(int32_t index);
/* The role whose joint a standard socket rides ("hand_r", "hand_l", "head", "head", "upper_chest"). */
const char* cc_char_standard_socket_role(int32_t index);
/* Adds or replaces a standard socket at its default spot on the mapped role's joint (rest model space): grips
 * at the middle of the hand bone, head at the head joint, eyes = the mean of the eye roles, else ahead of the
 * middle of the head bone, back behind the chest (upper_chest, else chest, else spine). CC_ERR_RANGE for an
 * unknown name or an unmapped role. */
int32_t cc_char_place_standard_socket(cc_char* c, const cr_rig* rig, const char* name);

/* --- Viewport overlay ---------------------------------------------------------------------------------- */

typedef struct cc_overlay cc_overlay;

/* One joint (32 bytes): its point (bone head / part pivot) and tail (bone tail; a part's pivot again), the
 * profile role it plays (-1 none, -2 a role the profile lacks) and that role's CC_ROLE_* state. */
typedef struct cc_overlay_joint {
    float point[3];
    float tail[3];
    int32_t role;
    uint32_t state;
} cc_overlay_joint;

enum {
    CC_OVERLAY_CHAIN = 0,    /* a retarget chain, joint to joint; index = chain */
    CC_OVERLAY_EYE_LINE = 1, /* across the model at eye height through the eyes point; index -1 */
    CC_OVERLAY_HIP_LINE = 2  /* across the model at hip height; index -1 */
};

/* A line segment (32 bytes). */
typedef struct cc_overlay_segment {
    float a[3];
    float b[3];
    int32_t kind;
    int32_t index;
} cc_overlay_segment;

/* A socket's posed frame (80 bytes): matrix = its joint's skin matrix x T(pos) R(rot) S(scale) (column-major);
 * standard = cc_char_standard_socket index or -1. */
typedef struct cc_overlay_socket {
    float matrix[16];
    int32_t joint;
    int32_t index;
    int32_t standard;
    int32_t reserved;
} cc_overlay_socket;

/* The capsule preview (32 bytes): an upright capsule from base (feet at bounds min y, centred on the hips
 * role's joint in x/z, else the bounds centre) of height = the definition's height metric (else the bounds
 * height) and radius = half the distance between the upper-arm roles' joints, else the upper-leg roles', else
 * a quarter of the width metric. */
typedef struct cc_overlay_capsule {
    float base[3];
    float height;
    float radius;
    float eye_height;
    float hip_height;
    float reserved;
} cc_overlay_capsule;

/* Builds the overlay for the rig posed by locals (9 floats per joint; NULL = the reference pose). profile may
 * be NULL (no role indices or states). bounds = the model's rest mesh bounds (model space), or NULL to use the
 * joints'. NULL on failure. */
cc_overlay* cc_char_overlay(const cc_char* c, const cr_rig* rig, const cc_profile* profile, const float* locals,
                            const float* bounds_min, const float* bounds_max);
void cc_overlay_destroy(cc_overlay* o);
int32_t cc_overlay_joint_count(const cc_overlay* o);
const cc_overlay_joint* cc_overlay_joints(const cc_overlay* o);
int32_t cc_overlay_segment_count(const cc_overlay* o);
const cc_overlay_segment* cc_overlay_segments(const cc_overlay* o);
int32_t cc_overlay_socket_count(const cc_overlay* o);
const cc_overlay_socket* cc_overlay_sockets(const cc_overlay* o);
const cc_overlay_capsule* cc_overlay_get_capsule(const cc_overlay* o);
/* Posed matrices, joint_count x 16 floats each (skin = what the model draws with). */
const float* cc_overlay_skin(const cc_overlay* o);
const float* cc_overlay_world(const cc_overlay* o);

enum {
    CC_PICK_NONE = 0,
    CC_PICK_JOINT = 1,
    CC_PICK_SOCKET = 2
};
/* The joint point or socket origin nearest a ray (origin, dir; model space) within tolerance +
 * tolerance_per_distance x its distance along the ray; the nearest along the ray wins among hits within the
 * tolerance. candidates_only: when the rig has bones, parts are not picked (roles map bones). Returns the
 * CC_PICK_* kind; *out_index = joint or socket index; *out_distance = distance along the ray. */
int32_t cc_overlay_pick(const cc_overlay* o, const float* origin, const float* dir, float tolerance,
                        float tolerance_per_distance, int32_t candidates_only, int32_t* out_index,
                        float* out_distance);

#ifdef __cplusplus
}
#endif
