/* Cenda character — the .omchar character definition (OpenMason #153, epic #166) behind a C ABI consumed
 * by Java via FFM (com.openmason.engine.character.*). The format itself lives in libomformat
 * (omformat/omchar.hpp, which documents the file layout); this ABI is the editing model on top of it,
 * plus the checks and measurements that need a rig (cenda/rig.h).
 *
 * A definition turns a rigged model into a character: which joint plays which profile role, the
 * reference (retarget) pose, joint limits, retarget chains, IK goals, standard sockets and body
 * measurements. Every reference into the skeleton is a joint id (a part or bone UUID). The definition
 * also stores the joint set it was made for, so a definition opened against a changed skeleton reports
 * what changed instead of binding silently (cc_char_check).
 *
 * Strings returned by getters are owned by the definition and stay valid until the next call that
 * changes it (or destroys it); copy them. Setters copy their arguments. A setter keyed by a name or
 * joint replaces an existing entry in place or appends a new one; a null value argument removes the
 * entry. Vectors are float[3]; rotations are Euler XYZ degrees (the rig's convention).
 *
 * Threading: a definition belongs to one thread at a time. Reports are immutable.
 */
#pragma once

#include <stdint.h>

#include "cenda/rig.h"

#ifdef __cplusplus
extern "C" {
#endif

#define CC_ABI_VERSION 1

typedef struct cc_char cc_char;
typedef struct cc_report cc_report;
typedef struct cc_bytes cc_bytes;

/* Results. */
enum {
    CC_OK = 0,
    CC_ERR_ARG = -1,      /* null handle, bad index, non-finite number, malformed argument */
    CC_ERR_RANGE = -2,    /* index out of range */
    CC_ERR_FORMAT = -3,   /* not a valid .omchar (cc_last_error says why) */
    CC_ERR_VERSION = -4   /* a newer major version of the format */
};

/* String fields (cc_char_get_string / cc_char_set_string). */
enum {
    CC_FIELD_NAME = 0,
    CC_FIELD_OBJECT_ID = 1,
    CC_FIELD_PROFILE = 2,
    CC_FIELD_ANIM_GRAPH = 3,
    CC_FIELD_MODEL_REF = 4,       /* the skeleton model by object id or path, when not embedded */
    CC_FIELD_APPEARANCE = 5,      /* a JSON object (opaque until OpenMason #162); "" = none */
    CC_FIELD_JOINT_HASH = 6       /* read-only: the stored skeleton structure hash */
};

/* Joint kinds (as cr_rig: CR_JOINT_PART / CR_JOINT_BONE). */

/* Metric bits (cc_metrics.overridden). */
enum {
    CC_METRIC_HEIGHT = 1u << 0,
    CC_METRIC_EYE_HEIGHT = 1u << 1,
    CC_METRIC_WIDTH = 1u << 2,
    CC_METRIC_HIP_HEIGHT = 1u << 3
};

/* Body measurements in model units (blocks), feet at 0 (24 bytes). overridden = metric bits the author
 * typed; cc_char_compute_metrics keeps those values. */
typedef struct cc_metrics {
    float height;
    float eye_height;
    float width;
    float hip_height;
    uint32_t overridden;
    uint32_t reserved; /* 0 */
} cc_metrics;

int32_t cc_abi_version(void);
/* The calling thread's last error message ("" when none). */
const char* cc_last_error(void);

/* --- Lifetime and I/O ------------------------------------------------------------------------- */

/* An empty definition. */
cc_char* cc_char_create(void);
/* Parses an .omchar archive. NULL on failure; *out_result (may be null) gets CC_ERR_FORMAT /
 * CC_ERR_VERSION / CC_ERR_ARG and cc_last_error says why. */
cc_char* cc_char_read(const uint8_t* bytes, int64_t length, int32_t* out_result);
void cc_char_destroy(cc_char* c);
/* Serializes (deterministic: reading and writing again gives the same bytes). NULL on failure. */
cc_bytes* cc_char_write(const cc_char* c);
const uint8_t* cc_bytes_data(const cc_bytes* b);
int64_t cc_bytes_size(const cc_bytes* b);
void cc_bytes_destroy(cc_bytes* b);

/* --- Strings ------------------------------------------------------------------------------------- */

const char* cc_char_get_string(const cc_char* c, int32_t field);
/* value NULL or "" clears the field. CC_FIELD_APPEARANCE must be a JSON object. */
int32_t cc_char_set_string(cc_char* c, int32_t field, const char* value);

/* --- Skeleton ------------------------------------------------------------------------------------ */

int32_t cc_char_joint_count(const cc_char* c);
/* out_parent_id gets "" for a root. Any output may be null. */
int32_t cc_char_joint_get(const cc_char* c, int32_t index, const char** out_id, const char** out_name,
                          const char** out_parent_id, int32_t* out_kind);
/* Records the rig's joints (ids, names, parents, kinds) as the definition's skeleton and recomputes
 * the joint hash. */
int32_t cc_char_set_skeleton(cc_char* c, const cr_rig* rig);
/* The embedded skeleton model (*out_size 0 = none). */
int32_t cc_char_model_get(const cc_char* c, const uint8_t** out_data, int64_t* out_size);
/* Embeds a model (an .omo) under entry_name (NULL = "model.omo"); data NULL or size 0 removes it. */
int32_t cc_char_model_set(cc_char* c, const uint8_t* data, int64_t size, const char* entry_name);

/* --- Roles --------------------------------------------------------------------------------------- */

int32_t cc_char_role_count(const cc_char* c);
int32_t cc_char_role_get(const cc_char* c, int32_t index, const char** out_role, const char** out_joint);
/* joint NULL removes the role. */
int32_t cc_char_role_set(cc_char* c, const char* role, const char* joint);
/* The joint id for a role, or NULL. */
const char* cc_char_role_joint(const cc_char* c, const char* role);

/* --- Reference pose and limits ------------------------------------------------------------------ */

int32_t cc_char_pose_count(const cc_char* c);
int32_t cc_char_pose_get(const cc_char* c, int32_t index, const char** out_joint, float* out_rot);
/* rot NULL removes the joint's entry (it then keeps its rest rotation in the reference pose). */
int32_t cc_char_pose_set(cc_char* c, const char* joint, const float* rot);

int32_t cc_char_limit_count(const cc_char* c);
int32_t cc_char_limit_get(const cc_char* c, int32_t index, const char** out_joint, float* out_min,
                          float* out_max);
/* min or max NULL removes the joint's limit. */
int32_t cc_char_limit_set(cc_char* c, const char* joint, const float* min, const float* max);

/* --- IK goals and chains ------------------------------------------------------------------------ */

int32_t cc_char_goal_count(const cc_char* c);
int32_t cc_char_goal_get(const cc_char* c, int32_t index, const char** out_name, const char** out_joint);
/* joint NULL removes the goal. */
int32_t cc_char_goal_set(cc_char* c, const char* name, const char* joint);

int32_t cc_char_chain_count(const cc_char* c);
/* out_goal gets "" when the chain has no goal. */
int32_t cc_char_chain_get(const cc_char* c, int32_t index, const char** out_name, const char** out_start,
                          const char** out_end, const char** out_goal);
/* start or end NULL removes the chain; goal NULL or "" = none. */
int32_t cc_char_chain_set(cc_char* c, const char* name, const char* start, const char* end, const char* goal);

/* --- Sockets ------------------------------------------------------------------------------------- */

int32_t cc_char_socket_count(const cc_char* c);
int32_t cc_char_socket_get(const cc_char* c, int32_t index, const char** out_name, const char** out_joint,
                           float* out_pos, float* out_rot, float* out_scale);
/* joint NULL removes the socket. pos / rot NULL = 0, scale NULL = 1. */
int32_t cc_char_socket_set(cc_char* c, const char* name, const char* joint, const float* pos,
                           const float* rot, const float* scale);

/* --- Metrics ------------------------------------------------------------------------------------- */

int32_t cc_char_metrics_get(const cc_char* c, cc_metrics* out);
int32_t cc_char_metrics_set(cc_char* c, const cc_metrics* metrics);
/* Measures the character in its reference pose (rest pose with the reference rotations applied):
 *   height      bounds height (bounds_max.y - bounds_min.y)
 *   width       bounds width (x)
 *   hip_height  the "hips" role joint above bounds_min.y
 *   eye_height  the "eyes" socket, else the mean of the "eye_l"/"eye_r" role joints, else the "head"
 *               role joint, above bounds_min.y
 * bounds = the model's rest-pose mesh bounds, model space. A joint's point is a bone's head or a part's
 * pivot. Metrics flagged in the definition's overridden bits keep their stored values. A metric whose
 * source is missing comes out 0. Writes *out; does not change the definition. */
int32_t cc_char_compute_metrics(const cc_char* c, const cr_rig* rig, const float* bounds_min,
                                const float* bounds_max, cc_metrics* out);

/* --- Checks -------------------------------------------------------------------------------------- */

/* Severities. */
enum {
    CC_SEVERITY_INFO = 0,
    CC_SEVERITY_WARNING = 1,
    CC_SEVERITY_ERROR = 2
};

/* Check codes. */
enum {
    CC_CHECK_NO_SKELETON = 1,        /* warning: the definition records no joint set */
    CC_CHECK_HASH_MISMATCH = 2,      /* warning: the stored joint hash does not match the joint list */
    CC_CHECK_UNKNOWN_JOINT = 3,      /* error: a reference names a joint the skeleton (and rig) lacks */
    CC_CHECK_JOINT_MISSING = 4,      /* error: a recorded joint is not in the rig */
    CC_CHECK_JOINT_ADDED = 5,        /* warning: the rig has a joint the definition does not record */
    CC_CHECK_JOINT_REPARENTED = 6,   /* error: a joint's parent differs from the record */
    CC_CHECK_JOINT_KIND_CHANGED = 7, /* error: part became bone or the reverse */
    CC_CHECK_JOINT_RENAMED = 8,      /* info: a joint's name differs (ids still bind) */
    CC_CHECK_CHAIN_ORDER = 9,        /* error: a chain's start is not an ancestor of its end */
    CC_CHECK_CHAIN_GOAL = 10,        /* error: a chain names an IK goal that does not exist */
    CC_CHECK_LIMIT_INVERTED = 11,    /* error: a limit's min exceeds its max on some axis */
    CC_CHECK_METRIC_NEGATIVE = 12    /* error: a metric is negative */
};

/* Checks the definition on its own and, with a rig (may be NULL), against that rig: the recorded
 * joint set vs the rig's joints, and every joint reference. Never NULL for a valid handle. */
cc_report* cc_char_check(const cc_char* c, const cr_rig* rig);
int32_t cc_report_count(const cc_report* r);
/* Highest severity in the report, -1 when empty. */
int32_t cc_report_max_severity(const cc_report* r);
int32_t cc_report_get(const cc_report* r, int32_t index, int32_t* out_severity, int32_t* out_code,
                      const char** out_subject, const char** out_message);
void cc_report_destroy(cc_report* r);

#ifdef __cplusplus
}
#endif
