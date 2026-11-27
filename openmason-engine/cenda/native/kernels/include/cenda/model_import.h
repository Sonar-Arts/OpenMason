/* Cenda model import (OpenMason #173, writer half): glTF 2.0 (.gltf / .glb) and Wavefront OBJ (+ MTL) turned into
 * an OMO 1.9 document (a co_doc, cenda/model.h) plus one OMANIM 1.2 clip per glTF animation. The PARSE half is
 * libomformat's omformat/import.hpp (values as authored); everything below is the conversion to Open Mason's
 * conventions. CO_ABI_VERSION stays 1 (functions added).
 *
 * CONVERSION RULES
 *   Axes / units  Open Mason models face -Z (+Y up). glTF and Blender-exported OBJ face +Z, so by default every
 *                 position, direction, rotation and matrix is turned 180 degrees about +Y (x, z negated;
 *                 CO_IMPORT_AXIS_KEEP leaves the axes as authored), then lengths are multiplied by unit_scale.
 *                 Matrices are converted by conjugation (C·M·C^-1), so a joint with no rotation in the file has
 *                 none in Open Mason either. Winding is unchanged (a proper rotation); a node whose world matrix
 *                 mirrors (det < 0) gets its faces' corner order reversed so front faces stay counter-clockwise.
 *   Parts         split (default): one part per mesh node of the default scene, named after the node (else the
 *                 mesh, else "Part N"); names are unique over parts AND bones (".001" suffixes). Every part's
 *                 local transform is the identity (its pivot = the node's world position in its parent's frame):
 *                 vertices are baked to model space (the node's whole world transform, axis + unit applied),
 *                 normals by the inverse-transpose. Parenting: a mesh node under another mesh node -> part under
 *                 that part; under a JOINT node -> part under that bone (rigid attachment: it follows the bone's
 *                 tail frame exactly as glTF moves it); skinned mesh nodes are roots (glTF ignores their node
 *                 transform). Non-mesh, non-joint nodes only contribute their transform. merge
 *                 (CO_IMPORT_SPLIT_PARTS off): one part holding every mesh; meshes rigidly under a joint get skin
 *                 rows (that bone, weight 1) instead of a parent.
 *   Faces         one OMO face id = one simple polygon. glTF triangles are paired into quads (CO_IMPORT_MERGE_QUADS):
 *                 two triangles of one primitive sharing an edge (same vertex indices, opposite direction), normals
 *                 within quad_angle_deg, forming a strictly convex quad -> one face id (the two triangles are kept);
 *                 pairs prefer the triangle's longest edge (a quad's diagonal). OBJ polygons keep their size (one face
 *                 id, ear-clipped triangles, fan fallback for degenerate loops). Every face owns its corners (soup).
 *   Normals       glTF NORMAL -> OMO 1.9 custom normals (octahedral snorm16 x 2) on every corner, faces smooth; no
 *                 NORMAL -> flat faces. OBJ: vn -> custom normals; a face is smooth when its smoothing group is not 0
 *                 ("s off" = flat), with or without vn.
 *   UVs           OMO texCoords are v-down (v = 0 is the image's top row: textures upload unflipped), like glTF;
 *                 OBJ vt are v-up and are flipped (v' = 1 - v). The UV set is the material's base colour texture
 *                 set (KHR_texture_transform's override first) with KHR_texture_transform applied, else TEXCOORD_0.
 *   Materials     every scene material -> an OMO material (ids from 1; 0 stays Open Mason's default): baseColor =
 *                 base colour factor (always written), renderLayer OPAQUE / CUTOUT (MASK) / TRANSLUCENT (BLEND); a base
 *                 colour texture with PNG bytes (JPEG re-encoded by the reader) -> material_<id>.png. Each face gets a
 *                 FULL-region mapping (0,0)-(1,1), autoResize off, with the authored UVs saved verbatim. Faces without
 *                 a material get a white "Default" material. A texture larger than max_texture_size (either side) is
 *                 downscaled, aspect kept, so its longer side = max_texture_size (the other side rounded, >= 1), with
 *                 a warning naming both sizes: an area (box) filter in linear light (sRGB decoded, averaged with each
 *                 source pixel weighted by its exact overlap and its alpha, i.e. premultiplied, then encoded back; grey
 *                 images too), alpha averaged by overlap and stored straight, 8-bit, re-encoded as PNG with miniz.
 *                 UVs are unchanged. An image that cannot be decoded is dropped with a warning (baseColor only).
 *   Bones         every skin joint of the default scene -> a bone, parents = the nearest joint ancestor. The bone's
 *                 rest WORLD frame W = the joint's converted rest world matrix with its scale divided out (bones
 *                 store no scale; a non-uniform or sheared joint frame is orthonormalized with a warning). With
 *                 F(parent) = W(parent)·T(endpoint(parent)) (identity for a root): origin = translation of
 *                 F(parent)^-1·W, rot = its Euler XYZ degrees (R = Rx·Ry·Rz, rig.h), pos = 0, so the rig's rest
 *                 world of every bone reproduces the joint. endpoint = the first child joint's head in the bone's
 *                 frame (so that child's origin is ~0); a bone without a child joint (or one whose child sits on its
 *                 head) points along its local +Y with its parent's length, else 0.1 x unit_scale.
 *   Skins         rest vertices = sum w·(G_rest(joint)·IBM(joint))·v (the glTF skinning equation at the rest pose,
 *                 so a bind pose != rest pose is re-baked), normals by its inverse-transpose; rows: duplicate joints
 *                 merged, zero weights dropped, renormalized, EVERY influence kept (rig ABI 3, unbounded); joint ids =
 *                 bone ids (skin.joints = every bone). A skinned vertex without weights follows its part (warning).
 *   Morphs        (CO_IMPORT_MORPHS) one OMO morph per target name (Blender extras.targetNames, else "Key N",
 *                 N from 1), shared by every part whose mesh has it; default weight = node weights, else mesh
 *                 weights; min/max 0..1 widened to hold the default. Deltas go through the same linear map as their
 *                 vertex (node world, or the skinned vertex's rest matrix); normal deltas through the normal matrix,
 *                 scaled like the base normal's normalization. Rows whose source deltas are all zero are dropped.
 *   Groups        (CO_IMPORT_VERTEX_GROUPS) every '_'-prefixed SCALAR attribute -> a vertex group named without the
 *                 '_' and lower-cased (Blender's "_COVERED_JACKET" -> "covered_jacket"), weight = the value, zeros
 *                 dropped; other component counts are skipped with a warning.
 *   Animations    (CO_IMPORT_ANIMATIONS) one clip per glTF animation (name kept, unique), a track per animated JOINT
 *                 (partId = bone id, targetType bone). Keys are absolute local values in the same conversion as the
 *                 rest pose: pos = sigma(parent)·P·(t - t_rest) (P = the static transform of non-joint nodes between
 *                 the joint and its parent joint), rot = Euler XYZ of R(P)·R(q), scale = s / s_rest; exact for
 *                 uniform scales. Key times = the union of the joint's channel key times; STEP segments become STEP
 *                 keys; LINEAR (slerp) and CUBICSPLINE segments are resampled adaptively until the rig's per-component
 *                 Euler lerp matches the glTF value within 2e-5 (matrix entries) at the quarter points. Euler branches
 *                 are chosen per key closest to the previous key. Then the keys are REDUCED (unless
 *                 CO_IMPORT_KEEP_ALL_KEYS, which gives the plain 2e-5 resampling): the resampling runs at 2e-5 / 16
 *                 (a finer candidate grid) and, from each kept key, the farthest later key whose plain segment (the
 *                 rig's own lerp + shortest-angle lerp) stays within 2e-5 of both the resampled track and the glTF
 *                 value - position (relative to max(1, |p|)), rotation matrix entries, scale and the composed matrix,
 *                 each - at every resampled key time and segment midpoint is kept next; found by doubling then
 *                 bisection (deterministic). Redundant source keys (baked constant / linear stretches) collapse; first
 *                 and last keys always stay; STEP tracks are not reduced. Skipped with a warning: morph weight channels
 *                 (OMANIM morph tracks are not in 1.2), channels on non-joint nodes.
 *
 * Errors: functions return CO_OK or a negative CO_ERR_* (model.h): CO_ERR_ARG (bad argument, unknown extension, file
 * not readable), CO_ERR_FORMAT (malformed / invalid model). co_last_error says why (co_import_last_error returns the
 * same message: one error store for every co_* header). No exception crosses this ABI. Threading: any thread; a result
 * belongs to one thread at a time.
 */
#pragma once

#include <stdint.h>

#include "cenda/model.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct co_import_result co_import_result;

/* Source formats (co_import_bytes). */
enum {
    CO_IMPORT_FORMAT_AUTO = 0, /* glTF binary by its magic, '{' (after whitespace / BOM) = glTF JSON, else OBJ */
    CO_IMPORT_FORMAT_GLTF = 1, /* .gltf JSON or .glb (detected by the magic) */
    CO_IMPORT_FORMAT_OBJ = 2   /* Wavefront OBJ text */
};

/* Axis handling. */
enum {
    CO_IMPORT_AXIS_OPENMASON = 0, /* +Z-forward source -> Open Mason's -Z forward: 180 degrees about +Y (default) */
    CO_IMPORT_AXIS_KEEP = 1       /* keep the axes as authored */
};

/* Option flags. */
enum {
    CO_IMPORT_MERGE_QUADS = 1u << 0,    /* pair coplanar triangles into quad faces */
    CO_IMPORT_SPLIT_PARTS = 1u << 1,    /* one part per mesh node (else one merged part) */
    CO_IMPORT_ANIMATIONS = 1u << 2,     /* glTF animations -> OMANIM clips */
    CO_IMPORT_MORPHS = 1u << 3,         /* morph targets -> OMO morphs */
    CO_IMPORT_VERTEX_GROUPS = 1u << 4,  /* '_' attributes -> OMO vertex groups */
    CO_IMPORT_KEEP_ALL_KEYS = 1u << 5,  /* skip the animation key reduction (not a default; diagnostics / tests) */
    CO_IMPORT_DEFAULT_FLAGS = 0x1F
};

/* Options (24 bytes). NULL options = the defaults of co_import_options_default. */
typedef struct co_import_options {
    float unit_scale;         /* 1.0: source units -> Open Mason units (> 0) */
    int32_t axis;             /* CO_IMPORT_AXIS_OPENMASON */
    uint32_t flags;           /* CO_IMPORT_DEFAULT_FLAGS */
    int32_t max_texture_size; /* 0 = unlimited; larger textures are downscaled to fit, with a warning */
    float quad_angle_deg;     /* 1.0: coplanarity tolerance of a quad merge */
    int32_t reserved;         /* 0 */
} co_import_options;

/* Counts of a result (48 bytes). */
typedef struct co_import_counts {
    int32_t parts;
    int32_t bones;
    int32_t faces;
    int32_t triangles;
    int32_t vertices;        /* soup vertices (corners) */
    int32_t materials;
    int32_t max_influences;  /* the most influences one vertex keeps (0 = no skin) */
    int32_t morphs;
    int32_t vertex_groups;
    int32_t clips;
    int32_t warnings;
    int32_t reserved;        /* 0 */
} co_import_counts;

void co_import_options_default(co_import_options* out);

/* Imports a file; the format comes from the extension (.gltf / .glb / .obj, case-insensitive), external references
 * resolve inside the file's folder (no absolute paths, no ".."). *out_result receives the result (NULL on failure). */
int32_t co_import_file(const char* path, const co_import_options* options, co_import_result** out_result);

/* Imports bytes. base_dir = the folder external references (buffers, images, mtllib) resolve in; NULL = none
 * (a glTF with an external buffer then fails, missing images / MTL are warnings). */
int32_t co_import_bytes(const uint8_t* bytes, int64_t length, int32_t format, const char* base_dir,
                        const co_import_options* options, co_import_result** out_result);

/* The calling thread's last import error ("" when none). */
const char* co_import_last_error(void);

void co_import_result_destroy(co_import_result* r);

/* The document, borrowed (owned by the result; NULL after co_import_result_take_doc). */
const co_doc* co_import_result_doc(const co_import_result* r);
/* Transfers the document to the caller (destroy it with co_doc_destroy); NULL when already taken. */
co_doc* co_import_result_take_doc(co_import_result* r);

int32_t co_import_result_clip_count(const co_import_result* r);
/* Clip index's name and .omanim archive bytes, borrowed from the result. CO_ERR_RANGE for a bad index. */
int32_t co_import_result_clip(const co_import_result* r, int32_t index, const char** out_name,
                              const uint8_t** out_data, int64_t* out_size);

/* Warnings: the reader's (cameras, missing images, ...) then the conversion's. */
int32_t co_import_result_warning_count(const co_import_result* r);
/* NULL for a bad index. */
const char* co_import_result_warning(const co_import_result* r, int32_t index);

int32_t co_import_result_counts(const co_import_result* r, co_import_counts* out);

#ifdef __cplusplus
}
#endif
