/* Cenda model - the .omo model document (OpenMason #167; OMO 1.9 sections #168) behind a C ABI consumed by Java
 * via FFM (the engine's OMO reader and the editor's OMO serializer become bindings over it). The format lives in
 * libomformat (omformat/omo.hpp documents the file layout, Java parity rules and the 1.9 sections); this ABI is a
 * document you read, inspect, fill and write.
 *
 * COARSE ACCESS. Every array or record list moves in ONE call, never per element:
 *   - mesh arrays are borrowed pointers into the document (co_doc_mesh_array) or copied in (co_doc_mesh_set_array);
 *   - records (face mappings, materials, parts, bones, attachment points, OMT layers) are arrays of fixed-layout
 *     structs; their string fields are const char* (NULL = JSON null / absent);
 *   - skin, vertex groups, morphs and custom normals are CSR: flat arrays plus offset arrays. Ask for the counts,
 *     allocate, then fetch everything with one call; set the same way.
 * Getters of record arrays return the total count and copy min(count, capacity) records (out may be NULL to just
 * count). Strings and pointers handed out by getters are owned by the document and stay valid until the next call
 * that changes it (or destroys it); copy them. Setters copy their arguments and replace the whole list (a failed
 * setter leaves the document unchanged). Vectors are float[3]; rotations Euler XYZ degrees.
 *
 * Struct layouts (64-bit; checked by static_asserts in model_abi.cpp):
 *   co_geometry      40 B: int32 width, height, depth, reserved; double position[3]                         (@16)
 *   co_face_mapping  32 B: int32 face_id, material_id; float u0, v0, u1, v1; int32 uv_rotation_degrees, auto_resize
 *   co_material      56 B: char* name, texture_file, render_layer; int32 material_id, emissive, tint_color,
 *                          has_base_color; float base_color[4]                                            (@24, @40)
 *   co_part         112 B: char* id, name, parent_id, bone_id; float origin[3], pos[3], rot[3], scale[3] (@32);
 *                          int32 vertex_start, vertex_count, index_start, index_count, face_start, face_count,
 *                          visible, locked (@80)
 *   co_bone          72 B: char* id, name, parent_bone_id; float origin[3], pos[3], rot[3], endpoint[3] (@24)
 *   co_attachment    72 B: char* id, name, parent_part_id, parent_part_name; float pos[3], rot[3], scale[3] (@32);
 *                          int32 reserved
 *   co_omt_info      16 B: int32 width, height, active_layer, crlf
 *   co_omt_layer     40 B: char* name, data_file; uint8* png; int64 png_size; float opacity; int32 visible
 *   co_omt_mapping   32 B: int32 face_id, material_id; float u0, v0, u1, v1; int32 uv_rotation, reserved
 *   co_omt_material  32 B: char* name, render_layer, texture_file; int32 material_id, reserved
 *
 * Errors: functions return CO_OK / a negative CO_ERR_* (or NULL / -1 where documented) and co_last_error says why.
 * No exception crosses this ABI. Threading: a document belongs to one thread at a time.
 */
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CO_ABI_VERSION 1

typedef struct co_doc co_doc;
typedef struct co_omt co_omt;
typedef struct co_bytes co_bytes;

/* Results. */
enum {
    CO_OK = 0,
    CO_ERR_ARG = -1,     /* null handle, malformed argument (bad offsets, unknown field, refused name ...) */
    CO_ERR_RANGE = -2,   /* index out of range */
    CO_ERR_FORMAT = -3,  /* not a valid .omo / .omt, or the document cannot be written (co_last_error says why) */
    CO_ERR_VERSION = -4  /* a newer major version of the format */
};

int32_t co_abi_version(void);
/* The calling thread's last error message ("" when none). */
const char* co_last_error(void);

const uint8_t* co_bytes_data(const co_bytes* b);
int64_t co_bytes_size(const co_bytes* b);
void co_bytes_destroy(co_bytes* b);

/* Compares two "<major>.<minor>" versions as integers ("1.10" > "1.9"): *out_order = -1, 0 or 1. CO_ERR_ARG when
 * either is malformed. */
int32_t co_version_compare(const char* a, const char* b, int32_t* out_order);

/* --- Lifetime and I/O ------------------------------------------------------------------------------------- */

/* An empty document (version "1.8", no mesh, no texture). */
co_doc* co_doc_create(void);
/* Parses an .omo archive (1.0 .. 1.x). NULL on failure; *out_result (may be null) gets CO_ERR_FORMAT /
 * CO_ERR_VERSION / CO_ERR_ARG. */
co_doc* co_doc_read(const uint8_t* bytes, int64_t length, int32_t* out_result);
co_doc* co_doc_clone(const co_doc* d);
void co_doc_destroy(co_doc* d);
/* The archive (manifest.json byte-identical to the Java writer's for the same data). NULL on failure. */
co_bytes* co_doc_write(const co_doc* d);
/* Just the manifest.json text the archive would hold. NULL on failure. */
co_bytes* co_doc_write_manifest(const co_doc* d);

/* --- Strings and flags ------------------------------------------------------------------------------------ */

enum {
    CO_FIELD_VERSION = 0,         /* "<major>.<minor>", major 1 */
    CO_FIELD_OBJECT_NAME = 1,
    CO_FIELD_MODEL_TYPE = 2,
    CO_FIELD_TEXTURE_FILE = 3,
    CO_FIELD_UV_MODE = 4,         /* mesh.uvMode; NULL = null (or no mesh). Setting it creates the mesh object */
    CO_FIELD_WRITTEN_VERSION = 5  /* read-only: the version co_doc_write puts in the file (>= "1.9" with 1.9 data) */
};

const char* co_doc_get_string(const co_doc* d, int32_t field);
/* NULL is allowed for CO_FIELD_UV_MODE only (= null); NULL elsewhere = "". */
int32_t co_doc_set_string(co_doc* d, int32_t field, const char* value);

enum {
    CO_DOC_HAS_MESH = 1 << 0,           /* "mesh" is an object (else null) */
    CO_DOC_HAS_FACE_TEXTURES = 1 << 1,  /* "faceTextures" is an object (else null) */
    CO_DOC_CRLF = 1 << 2,               /* manifest lines end in CRLF (a file the Java tool wrote on Windows) */
    CO_DOC_HAS_1_9_DATA = 1 << 3        /* read-only: shading / vertex groups / morphs / a material base colour */
};

int32_t co_doc_flags(const co_doc* d);
/* Sets the writable flags: clearing HAS_MESH / HAS_FACE_TEXTURES drops that section, setting one on a document
 * without it adds an empty one. Read-only bits are ignored. */
int32_t co_doc_set_flags(co_doc* d, int32_t flags);

/* --- Geometry and model transform ------------------------------------------------------------------------- */

typedef struct co_geometry {
    int32_t width;
    int32_t height;
    int32_t depth;
    int32_t reserved; /* 0 */
    double position[3];
} co_geometry;

int32_t co_doc_geometry_get(const co_doc* d, co_geometry* out);
int32_t co_doc_geometry_set(co_doc* d, const co_geometry* geometry);
/* float[9]: pos[3], rot[3], scale[3]. Identity is written as null. */
int32_t co_doc_model_transform_get(const co_doc* d, float* out9);
int32_t co_doc_model_transform_set(co_doc* d, const float* trs9);

/* --- Mesh (the soup: one vertex per face corner) ----------------------------------------------------------- */

enum {
    CO_MESH_VERTICES = 0,           /* float, 3 per vertex */
    CO_MESH_TEX_COORDS = 1,         /* float, 2 per vertex */
    CO_MESH_INDICES = 2,            /* int32, 3 per triangle */
    CO_MESH_TRIANGLE_FACE_IDS = 3   /* int32, 1 per triangle */
};

/* Element count of a mesh array (elements are 4 bytes: float or int32 as above); -1 when the array is null or
 * there is no mesh. *out_data (may be null) borrows the elements. */
int64_t co_doc_mesh_array(const co_doc* d, int32_t which, const void** out_data);
/* Copies count elements in; count < 0 (or data NULL) sets the array to null. Creates the mesh object. */
int32_t co_doc_mesh_set_array(co_doc* d, int32_t which, const void* data, int64_t count);

/* --- Face textures (OMO 1.2+) ------------------------------------------------------------------------------ */

typedef struct co_face_mapping {
    int32_t face_id;
    int32_t material_id;
    float u0, v0, u1, v1;
    int32_t uv_rotation_degrees;
    int32_t auto_resize; /* 0 / 1 */
} co_face_mapping;

typedef struct co_material {
    const char* name;         /* NULL = null */
    const char* texture_file; /* the material image entry ("material_<id>.png"); NULL = null */
    const char* render_layer; /* OPAQUE | CUTOUT | TRANSLUCENT; NULL = null */
    int32_t material_id;
    int32_t emissive;         /* 0 / 1 */
    int32_t tint_color;       /* RGBA as a signed int (Java int) */
    int32_t has_base_color;   /* 1.9: 1 = base_color is written */
    float base_color[4];      /* RGBA */
} co_material;

int32_t co_doc_face_mappings_get(const co_doc* d, co_face_mapping* out, int32_t capacity);
/* Creates the faceTextures section. */
int32_t co_doc_face_mappings_set(co_doc* d, const co_face_mapping* mappings, int32_t count);
int32_t co_doc_materials_get(const co_doc* d, co_material* out, int32_t capacity);
int32_t co_doc_materials_set(co_doc* d, const co_material* materials, int32_t count);

/* --- Parts, bones, attachment points --------------------------------------------------------------------- */

typedef struct co_part {
    const char* id;        /* required, not blank */
    const char* name;      /* NULL = "" */
    const char* parent_id; /* a part or bone id; NULL = root */
    const char* bone_id;   /* legacy; NULL = unbound */
    float origin[3];
    float pos[3];
    float rot[3];
    float scale[3];
    int32_t vertex_start, vertex_count;
    int32_t index_start, index_count;
    int32_t face_start, face_count;
    int32_t visible; /* 0 / 1 */
    int32_t locked;  /* 0 / 1 */
} co_part;

typedef struct co_bone {
    const char* id;             /* required, not blank */
    const char* name;           /* NULL = the id */
    const char* parent_bone_id; /* a bone or part id; NULL = root */
    float origin[3];
    float pos[3];
    float rot[3];
    float endpoint[3];
} co_bone;

typedef struct co_attachment {
    const char* id;               /* required, not blank */
    const char* name;             /* NULL = "" */
    const char* parent_part_id;   /* NULL = model root (unless a name is given) */
    const char* parent_part_name; /* NULL = none */
    float pos[3];
    float rot[3];
    float scale[3];
    int32_t reserved; /* 0 */
} co_attachment;

int32_t co_doc_parts_get(const co_doc* d, co_part* out, int32_t capacity);
int32_t co_doc_parts_set(co_doc* d, const co_part* parts, int32_t count);
int32_t co_doc_bones_get(const co_doc* d, co_bone* out, int32_t capacity);
int32_t co_doc_bones_set(co_doc* d, const co_bone* bones, int32_t count);
int32_t co_doc_attachments_get(const co_doc* d, co_attachment* out, int32_t capacity);
int32_t co_doc_attachments_set(co_doc* d, const co_attachment* attachments, int32_t count);

/* --- Skin (OMO 1.8): weights per part by part-local vertex --------------------------------------------------
 * CSR over all parts: part p owns rows [part_rows[p], part_rows[p+1]); row r is part-local vertex row_vertices[r]
 * with influences [row_influences[r], row_influences[r+1]) of joints/weights (joints index joint_ids). Offsets
 * start at 0 and are global (not per part). */

int32_t co_doc_skin_counts(const co_doc* d, int32_t* out_joints, int32_t* out_parts, int32_t* out_rows,
                           int32_t* out_influences);
/* Arrays sized from co_doc_skin_counts: joint_ids[joints], part_ids[parts], part_rows[parts+1],
 * row_vertices[rows], row_influences[rows+1], joints[influences], weights[influences]. Any may be NULL. */
int32_t co_doc_skin_get(const co_doc* d, const char** joint_ids, const char** part_ids, int32_t* part_rows,
                        int32_t* row_vertices, int32_t* row_influences, int32_t* joints, float* weights);
/* part_count 0 clears the skin. Refused (CO_ERR_ARG): bad offsets, a row without influences, a joint index out
 * of range. A part without rows is dropped (it is not written). */
int32_t co_doc_skin_set(co_doc* d, int32_t joint_count, const char* const* joint_ids, int32_t part_count,
                        const char* const* part_ids, const int32_t* part_rows, const int32_t* row_vertices,
                        const int32_t* row_influences, const int32_t* joints, const float* weights);

/* --- Shading (OMO 1.9) ------------------------------------------------------------------------------------ */

/* Smooth faces: a bitset over face ids, bit f%32 of word f/32. Returns the word count; *out_words borrows them. */
int64_t co_doc_shading_smooth(const co_doc* d, const uint32_t** out_words);
int32_t co_doc_shading_set_smooth(co_doc* d, const uint32_t* words, int64_t word_count);
/* Sharp edges: soup vertex index pairs (2 uint32 per edge). Returns the edge count. */
int64_t co_doc_shading_sharp_edges(const co_doc* d, const uint32_t** out_pairs);
int32_t co_doc_shading_set_sharp_edges(co_doc* d, const uint32_t* pairs, int64_t edge_count);
/* 1 and *out_degrees when set, 0 when unset. */
int32_t co_doc_shading_auto_smooth(const co_doc* d, float* out_degrees);
/* degrees NULL unsets. */
int32_t co_doc_shading_set_auto_smooth(co_doc* d, const float* degrees);
/* Custom normals: soup vertex per row + 2 int16 (octahedral snorm16 x, y) per row. Returns the row count. */
int64_t co_doc_shading_custom_normals(const co_doc* d, const int32_t** out_vertices, const int16_t** out_oct);
int32_t co_doc_shading_set_custom_normals(co_doc* d, const int32_t* vertices, const int16_t* oct, int64_t count);

/* --- Vertex groups (OMO 1.9) --------------------------------------------------------------------------------
 * Group g owns parts [group_parts[g], group_parts[g+1]); part p owns rows [part_rows[p], part_rows[p+1]) of
 * (vertices, weights). */

int32_t co_doc_vertex_group_counts(const co_doc* d, int32_t* out_groups, int32_t* out_parts, int32_t* out_rows);
/* names[groups], group_parts[groups+1], part_ids[parts], part_rows[parts+1], vertices[rows], weights[rows]. */
int32_t co_doc_vertex_groups_get(const co_doc* d, const char** names, int32_t* group_parts, const char** part_ids,
                                 int32_t* part_rows, int32_t* vertices, float* weights);
/* group_count 0 clears. Part and row counts come from group_parts[group_count] and part_rows[part count]. */
int32_t co_doc_vertex_groups_set(co_doc* d, int32_t group_count, const char* const* names, const int32_t* group_parts,
                                 const char* const* part_ids, const int32_t* part_rows, const int32_t* vertices,
                                 const float* weights);

/* --- Morphs / shape keys (OMO 1.9) --------------------------------------------------------------------------
 * Morph m: params[3m..3m+2] = defaultWeight, min, max; owns parts [morph_parts[m], morph_parts[m+1]); part p owns
 * rows [part_rows[p], part_rows[p+1]): part-local vertex + 3 delta floats (+ 3 normal-delta floats when the part's
 * flags have CO_MORPH_PART_NORMALS; the normals array holds 3 floats for EVERY row, zeros for parts without). */

enum { CO_MORPH_PART_NORMALS = 1 };

int32_t co_doc_morph_counts(const co_doc* d, int32_t* out_morphs, int32_t* out_parts, int32_t* out_rows);
/* names[morphs], params[3*morphs], morph_parts[morphs+1], part_ids[parts], part_flags[parts], part_rows[parts+1],
 * vertices[rows], deltas[3*rows], normals[3*rows]. Any may be NULL. */
int32_t co_doc_morphs_get(const co_doc* d, const char** names, float* params, int32_t* morph_parts,
                          const char** part_ids, int32_t* part_flags, int32_t* part_rows, int32_t* vertices,
                          float* deltas, float* normals);
/* morph_count 0 clears. params NULL = defaults (0, 0, 1); part_flags NULL = no normals; normals may be NULL when no
 * part has CO_MORPH_PART_NORMALS. */
int32_t co_doc_morphs_set(co_doc* d, int32_t morph_count, const char* const* names, const float* params,
                          const int32_t* morph_parts, const char* const* part_ids, const int32_t* part_flags,
                          const int32_t* part_rows, const int32_t* vertices, const float* deltas, const float* normals);

/* --- Archive entries -------------------------------------------------------------------------------------- */

/* The embedded base texture (texture.omt) bytes; returns the size (0 = none), *out_data borrows them. */
int64_t co_doc_texture(const co_doc* d, const uint8_t** out_data);
/* data NULL or size 0 removes it. */
int32_t co_doc_set_texture(co_doc* d, const uint8_t* data, int64_t size);

enum {
    CO_ENTRIES_IMAGES = 0, /* material images: names "material_<...>.png", archive order */
    CO_ENTRIES_EXTRA = 1   /* any other entry, kept verbatim */
};

int32_t co_doc_entry_count(const co_doc* d, int32_t kind);
int32_t co_doc_entry_get(const co_doc* d, int32_t kind, int32_t index, const char** out_name, const uint8_t** out_data,
                         int64_t* out_size);
/* -1 when absent. */
int32_t co_doc_entry_find(const co_doc* d, int32_t kind, const char* name);
/* Replaces the entry with that name in place or appends it; data NULL removes it. CO_ERR_ARG for manifest.json,
 * texture.omt, an image name not "material_*.png", or an extra entry named like an image. */
int32_t co_doc_entry_set(co_doc* d, int32_t kind, const char* name, const uint8_t* data, int64_t size);

/* --- Unknown top-level manifest sections (kept on a round trip, written last) --------------------------- */

int32_t co_doc_unknown_count(const co_doc* d);
/* *out_json = the value as minified JSON (numbers spelled as in the file). */
int32_t co_doc_unknown_get(const co_doc* d, int32_t index, const char** out_key, const char** out_json);
/* json NULL removes the section. CO_ERR_ARG for a known key or invalid JSON. */
int32_t co_doc_unknown_set(co_doc* d, const char* key, const char* json);

/* --- .omt textures (the document inside texture.omt; layer PNGs are bytes, never decoded) ------------------ */

typedef struct co_omt_info {
    int32_t width;
    int32_t height;
    int32_t active_layer;
    int32_t crlf; /* 0 / 1 */
} co_omt_info;

typedef struct co_omt_layer {
    const char* name;
    const char* data_file; /* the layer's entry ("layer_<i>.png") */
    const uint8_t* png;    /* NULL / 0 = no entry */
    int64_t png_size;
    float opacity;
    int32_t visible; /* 0 / 1 */
} co_omt_layer;

typedef struct co_omt_mapping {
    int32_t face_id;
    int32_t material_id;
    float u0, v0, u1, v1;
    int32_t uv_rotation;
    int32_t reserved; /* 0 */
} co_omt_mapping;

typedef struct co_omt_material {
    const char* name;
    const char* render_layer;
    const char* texture_file;
    int32_t material_id;
    int32_t reserved; /* 0 */
} co_omt_material;

/* An empty texture: version "1.0", 16 x 16, no layers. */
co_omt* co_omt_create(void);
co_omt* co_omt_read(const uint8_t* bytes, int64_t length, int32_t* out_result);
void co_omt_destroy(co_omt* t);
co_bytes* co_omt_write(const co_omt* t);
const char* co_omt_version(const co_omt* t);
int32_t co_omt_set_version(co_omt* t, const char* version);
int32_t co_omt_info_get(const co_omt* t, co_omt_info* out);
int32_t co_omt_info_set(co_omt* t, const co_omt_info* info);
int32_t co_omt_layers_get(const co_omt* t, co_omt_layer* out, int32_t capacity);
/* Copies names and PNG bytes. */
int32_t co_omt_layers_set(co_omt* t, const co_omt_layer* layers, int32_t count);
int32_t co_omt_mappings_get(const co_omt* t, co_omt_mapping* out, int32_t capacity);
int32_t co_omt_mappings_set(co_omt* t, const co_omt_mapping* mappings, int32_t count);
int32_t co_omt_materials_get(const co_omt* t, co_omt_material* out, int32_t capacity);
int32_t co_omt_materials_set(co_omt* t, const co_omt_material* materials, int32_t count);
/* Entries that are not layers (material PNGs, unknown), kept verbatim. */
int32_t co_omt_entry_count(const co_omt* t);
int32_t co_omt_entry_get(const co_omt* t, int32_t index, const char** out_name, const uint8_t** out_data,
                         int64_t* out_size);
/* Replaces / appends by name; data NULL removes. CO_ERR_ARG for manifest.json or a layer's data file. */
int32_t co_omt_entry_set(co_omt* t, const char* name, const uint8_t* data, int64_t size);

#ifdef __cplusplus
}
#endif
