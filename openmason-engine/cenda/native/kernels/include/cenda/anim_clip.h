/* Cenda animation clip documents — the .omanim format (OpenMason #155: version 1.2; #171: 1.3) behind a C ABI consumed by
 * Java via FFM (com.openmason.engine.anim.*). The codec is libomformat (omformat/omanim.hpp documents the file
 * layout); this ABI is the editing model plus the clip-data queries the runtime needs: which notifies a step of
 * playback crosses, float curve values, sync-marker phase, and binding role-addressed tracks to a character.
 *
 * Reads 1.0 - 1.3 and writes 1.2, or 1.3 when the clip has morph tracks (#171; a clip without them writes what a 1.2
 * writer did, byte for byte); every value read comes back bit-identical, and 1.1 / 1.2 readers still read a newer
 * file (they ignore the new sections and skip role tracks).
 *
 * Strings and arrays returned by getters are owned by the clip and stay valid until the next call that changes
 * it; copy them. Keys use the rig's key struct (cr_key, rig.h). Times are seconds.
 *
 * Threading: a clip belongs to one thread at a time; read-only queries may run concurrently.
 */
#pragma once

#include <stdint.h>

#include "cenda/character.h"
#include "cenda/rig.h"

#ifdef __cplusplus
extern "C" {
#endif

#define CM_ABI_VERSION 1

typedef struct cm_clip cm_clip;

enum {
    CM_OK = 0,
    CM_ERR_ARG = -1,
    CM_ERR_RANGE = -2,
    CM_ERR_FORMAT = -3
};

/* String fields. */
enum {
    CM_FIELD_NAME = 0,
    CM_FIELD_MODEL_REF = 1,     /* "" with cm_clip_model_ref_null = 1 is JSON null */
    CM_FIELD_VERSION = 2,       /* read-only: the version the clip was read as */
    CM_FIELD_LAYER_TYPE = 3     /* "BASE" | "OVERLAY" */
};

/* Clip header (28 bytes). has_layer = 0: a 1.0 clip without a layer block (BASE defaults). */
typedef struct cm_header {
    float fps;
    float duration;
    int32_t loop;
    int32_t has_layer;
    float fade_in;
    float fade_out;
    int32_t priority;
} cm_header;

int32_t cm_abi_version(void);
const char* cm_last_error(void);

/* --- Lifetime and I/O ------------------------------------------------------------------------------- */

cm_clip* cm_clip_create(void);
/* NULL on failure; *out_result = CM_ERR_FORMAT / CM_ERR_ARG and cm_last_error says why. */
cm_clip* cm_clip_read(const uint8_t* bytes, int64_t length, int32_t* out_result);
cm_clip* cm_clip_clone(const cm_clip* c);
void cm_clip_destroy(cm_clip* c);
/* Serializes as 1.2 (1.3 with morph tracks; deterministic). NULL on failure. Free with cc_bytes_destroy (character.h). */
cc_bytes* cm_clip_write(const cm_clip* c);

/* --- Header, strings, layer mask -------------------------------------------------------------------- */

int32_t cm_clip_header_get(const cm_clip* c, cm_header* out);
int32_t cm_clip_header_set(cm_clip* c, const cm_header* header);
const char* cm_clip_get_string(const cm_clip* c, int32_t field);
/* CM_FIELD_MODEL_REF: NULL = JSON null. CM_FIELD_LAYER_TYPE: BASE or OVERLAY (case-insensitive). */
int32_t cm_clip_set_string(cm_clip* c, int32_t field, const char* value);
int32_t cm_clip_model_ref_null(const cm_clip* c);
int32_t cm_clip_mask_count(const cm_clip* c);
const char* cm_clip_mask_get(const cm_clip* c, int32_t index);
int32_t cm_clip_mask_set(cm_clip* c, const char* const* parts, int32_t count);

/* --- Tracks ------------------------------------------------------------------------------------------ */

int32_t cm_clip_track_count(const cm_clip* c);
/* out_role "" = joint-addressed. */
int32_t cm_clip_track_get(const cm_clip* c, int32_t index, const char** out_part_id, const char** out_part_name,
                          const char** out_role, int32_t* out_bone, int32_t* out_key_count);
/* Copies up to capacity keys; returns the track's key count. Easing = CR_EASE_*. */
int32_t cm_clip_track_keys(const cm_clip* c, int32_t index, cr_key* out, int32_t capacity);
/* Adds or replaces the track keyed by role (role non-empty) or else part_id; keys are sorted by time (stably).
 * A role track gets part_id / part_name "role:<role>" (what 1.1 readers see). Returns the track index. */
int32_t cm_clip_track_set(cm_clip* c, const char* part_id, const char* part_name, const char* role, int32_t bone,
                          const cr_key* keys, int32_t count);
int32_t cm_clip_track_remove(cm_clip* c, int32_t index);

/* --- Notifies ---------------------------------------------------------------------------------------- */

int32_t cm_clip_notify_count(const cm_clip* c);
/* out_payload_json "" = none. */
int32_t cm_clip_notify_get(const cm_clip* c, int32_t index, const char** out_name, float* out_time,
                           float* out_duration, const char** out_payload_json);
/* Inserts in time order (after equal times); duration > 0 = a range; payload_json NULL/"" or a JSON object.
 * Returns the new index. */
int32_t cm_clip_notify_add(cm_clip* c, const char* name, float time, float duration, const char* payload_json);
int32_t cm_clip_notify_remove(cm_clip* c, int32_t index);

/* Notify phases (cm_fired.phase). */
enum {
    CM_NOTIFY_POINT = 0,
    CM_NOTIFY_BEGIN = 1,
    CM_NOTIFY_END = 2
};

/* One crossed notify edge (16 bytes). */
typedef struct cm_fired {
    int32_t notify;   /* index into the clip's notifies */
    int32_t phase;    /* CM_NOTIFY_* */
    float time;       /* the edge's clip time */
    uint32_t reserved;
} cm_fired;

/* The notify edges a step of playback crosses, in playback order. prev_time = the clip time before the step
 * (looping: wrapped into [0, duration); else clamped), delta = signed advance (play rate x dt).
 * Forward the step covers [prev, prev+delta), backward (prev+delta, prev]; a looping clip wraps (an edge at
 * `duration` is the edge at 0); a non-looping clip stops at its ends and an edge exactly AT the end it reaches
 * fires (once: the next step starts there). A step as long as the clip or longer fires every edge once.
 * A range notify fires BEGIN at its time and END at time + duration (wrapped when looping). Writes up to
 * capacity records; returns the total count. */
int32_t cm_clip_fire(const cm_clip* c, float prev_time, float delta, cm_fired* out, int32_t capacity);

/* --- Float curves ------------------------------------------------------------------------------------ */

enum {
    CM_INTERP_LINEAR = 0,
    CM_INTERP_STEP = 1,
    CM_INTERP_CUBIC = 2   /* Hermite with the keys' tangents (value per second) */
};

/* One curve key (20 bytes). interp shapes the segment after the key. */
typedef struct cm_curve_key {
    float time;
    float value;
    float in_tangent;
    float out_tangent;
    int32_t interp;
} cm_curve_key;

int32_t cm_clip_curve_count(const cm_clip* c);
int32_t cm_clip_curve_get(const cm_clip* c, int32_t index, const char** out_name, int32_t* out_key_count);
int32_t cm_clip_curve_keys(const cm_clip* c, int32_t index, cm_curve_key* out, int32_t capacity);
/* Adds or replaces a curve (keys sorted by time); keys NULL or count 0 removes it. */
int32_t cm_clip_curve_set(cm_clip* c, const char* name, const cm_curve_key* keys, int32_t count);
int32_t cm_clip_curve_find(const cm_clip* c, const char* name);
/* The curve's value at a time (clamped to its first / last key; 0 for an empty curve or a bad index). */
float cm_clip_curve_sample(const cm_clip* c, int32_t index, float time);

/* --- Morph tracks (OMANIM 1.3, OpenMason #171) ---------------------------------------------------------- */

/* Shape-key weight tracks: one per morph channel NAME (the mesh's shape key; bound to a rig's channels by name,
 * rig.h "Morph targets"), scalar keys exactly like the float curves (cm_curve_key, CM_INTERP_*; CUBIC tangents in
 * value per second). Names may be given with a leading "morph:" (stripped). In CM ABI 1 (additions). */
int32_t cm_clip_morph_count(const cm_clip* c);
int32_t cm_clip_morph_get(const cm_clip* c, int32_t index, const char** out_name, int32_t* out_key_count);
/* Copies up to capacity keys; returns the track's key count. */
int32_t cm_clip_morph_keys(const cm_clip* c, int32_t index, cm_curve_key* out, int32_t capacity);
/* Adds or replaces a morph track (keys sorted by time, stably); keys NULL or count 0 removes it. Returns the index. */
int32_t cm_clip_morph_set(cm_clip* c, const char* name, const cm_curve_key* keys, int32_t count);
int32_t cm_clip_morph_find(const cm_clip* c, const char* name);
/* The track's value at a time (clamped to its first / last key; 0 for an empty track or a bad index) - bit-identical
 * to what a rig clip carrying the track samples (cr_clip_sample_morph). */
float cm_clip_morph_sample(const cm_clip* c, int32_t index, float time);
/* Gives a rig clip (rig.h, not yet bound) the document's morph tracks (cr_clip_set_morph_tracks). CM_OK when the
 * document has none. What the animation graph, authoring and retargeting do with every clip they build. */
int32_t cm_clip_apply_morphs(const cm_clip* c, cr_clip* rig_clip);

/* --- Sync markers ------------------------------------------------------------------------------------ */

int32_t cm_clip_marker_count(const cm_clip* c);
int32_t cm_clip_marker_get(const cm_clip* c, int32_t index, const char** out_name, float* out_time);
int32_t cm_clip_marker_add(cm_clip* c, const char* name, float time);
int32_t cm_clip_marker_remove(cm_clip* c, int32_t index);
/* Where a time lies between markers: *out_marker = the last marker at or before it (a looping clip wraps to the
 * last marker of the previous cycle; -1 before the first marker of a non-looping clip) and *out_fraction in
 * [0, 1) its position toward the next marker (the clip's end when there is no next one and it does not loop). */
int32_t cm_clip_marker_position(const cm_clip* c, float time, int32_t* out_marker, float* out_fraction);
/* The inverse: the clip time at fraction of the way from marker to the next. */
float cm_clip_marker_time(const cm_clip* c, int32_t marker, float fraction);

/* --- Sync groups (OpenMason #156) ------------------------------------------------------------------ */

/* One clip of a sync group (24 bytes; pointer padded to 8). */
typedef struct cm_sync_member {
    const cm_clip* clip;
    float time;      /* in/out: the clip's time (looping clips: wrapped into [0, duration)) */
    float rate;      /* playback rate (only the leader's is used) */
    float weight;    /* blend weight: with leader < 0 the heaviest member leads (the first of equals) */
    int32_t reserved;
} cm_sync_member;

/* Advances a sync group by dt seconds (Unreal sync groups and markers): the leader plays at its own rate
 * (looping clips wrap, others clamp); every follower then takes the leader's PHASE. With sync markers that
 * phase is the leader's position between markers (cm_clip_marker_position): a follower moves to the same
 * fraction between its own markers of the same name, choosing among same-named markers the one reached
 * soonest going forward from its current time, so clips of different lengths keep their feet in step. A
 * follower without that marker (or a leader before its first marker / without markers) takes the leader's
 * normalized time instead. leader = an index, or -1 for the heaviest. Returns the leader's index, or
 * CM_ERR_ARG. */
int32_t cm_sync_advance(cm_sync_member* members, int32_t count, int32_t leader, float dt);

/* --- Root motion and additive ------------------------------------------------------------------------ */

enum {
    CM_ROOT_X = 1u << 0,
    CM_ROOT_Y = 1u << 1,
    CM_ROOT_Z = 1u << 2,
    CM_ROOT_YAW = 1u << 3
};

/* out_joint "" = no root motion. */
int32_t cm_clip_root_motion_get(const cm_clip* c, const char** out_joint, uint32_t* out_axes);
/* joint NULL or "" clears it; joint = a track's part id, or "role:<role>". */
int32_t cm_clip_root_motion_set(cm_clip* c, const char* joint, uint32_t axes);

enum {
    CM_ADDITIVE_NONE = 0,
    CM_ADDITIVE_FRAME = 1,      /* a frame of this clip (time) */
    CM_ADDITIVE_CLIP = 2,       /* a frame (time) of another clip (clip_ref) */
    CM_ADDITIVE_CHARACTER = 3   /* the character's reference pose (.omchar) */
};

int32_t cm_clip_additive_get(const cm_clip* c, int32_t* out_reference, float* out_time, const char** out_clip_ref,
                             int32_t* out_mesh_space);
int32_t cm_clip_additive_set(cm_clip* c, int32_t reference, float time, const char* clip_ref, int32_t mesh_space);

/* --- Roles ------------------------------------------------------------------------------------------- */

/* A copy whose role tracks address the character's joints (part id = the joint id, part name = its name in the
 * definition's skeleton); role tracks for roles the character does not map are dropped (*out_dropped counts
 * them). Root motion addressed by role follows. NULL on failure. */
cm_clip* cm_clip_bind_roles(const cm_clip* c, const cc_char* character, int32_t* out_dropped);
/* The reverse: a copy whose tracks on joints the character maps to roles become role tracks (portable to any
 * character of the same profile); other tracks stay joint-addressed. */
cm_clip* cm_clip_to_roles(const cm_clip* c, const cc_char* character);

#ifdef __cplusplus
}
#endif
