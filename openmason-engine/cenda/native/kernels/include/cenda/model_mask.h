/* Cenda model masks - vertex groups hide triangles at draw time, Blender's Mask modifier as a draw plan (OpenMason
 * #172; data: OMO 1.9 `vertexGroups`, #168). Part of the co_* ABI (CO_ABI_VERSION 1: these functions are additions;
 * the result codes and co_doc are cenda/model.h's).
 *
 * ERRORS: every function returns CO_OK / a count >= 0 / a negative CO_ERR_* (or NULL / -1 where documented) and
 * reports the reason through co_last_error() (the one store behind every co_* header). A successful call clears it.
 * No exception crosses this ABI.
 *
 * THREADING: a plan is immutable once created; every function taking a `const co_mask_plan*` may run on any number
 * of threads at once (errors are per thread).
 *
 * ------------------------------------------------------------------------------------------------------------------
 * THE IDEA. Built ONCE per model (on load and after a vertex-group edit), used every draw with no CPU work on the
 * mesh: the plan REORDERS the triangles of the index buffer so that for ANY choice of hidden groups the visible
 * triangles are a few contiguous index ranges. Upload co_mask_indices() once as the element buffer (instead of the
 * original indices), then per appearance change call co_mask_ranges() and draw the ranges it returns (cache them
 * until the hidden set changes). Vertex buffers are untouched (the reorder only moves whole triangles, and keeps
 * each triangle's three indices in their order, so winding is unchanged).
 *
 * MEMBERSHIP. A soup vertex is IN group g when any row of g names it with a weight > 0 (Blender's Mask modifier
 * threshold 0; a weight <= 0 or NaN is "not in"; NULL weights = every row counts). The SIGNATURE of a triangle is the
 * set of groups that contain ALL THREE of its vertices (the intersection of its vertices' groups; empty for a
 * triangle with any vertex in no group).
 *
 * TWO MODES, chosen per group at query time (a group may be in neither, in one or in both bitsets):
 *   HIDE (bitset `hide`): "hide the skin a garment covers". Hiding g removes every triangle whose vertices are ALL in
 *     g (g in the signature). Triangles that only touch g (one or two vertices in it) stay, so the border of a
 *     covered area never opens a gap at the garment's edge. Hiding several groups removes the union.
 *     NOTE: this is NOT Blender's inverted Mask (which removes every face with ANY vertex in the group).
 *   KEEP (bitset `keep`): Blender's Mask modifier, vertex group mode, threshold 0, not inverted: keeping only g
 *     removes every triangle with ANY vertex outside g (keeps exactly the triangles whose signature has g). Several
 *     kept groups = Mask modifiers stacked: a triangle survives when it is inside every kept group. For one group,
 *     KEEP g is the exact complement of HIDE g (KEEP = HIDE with Blender's invert flag).
 *   A triangle is VISIBLE iff signature & hide == {} AND keep is a subset of signature. No bits = everything drawn.
 *
 * KEYS (draw batches stay intact). An optional per-triangle int32 KEY splits the triangles into SEGMENTS = maximal
 * runs of consecutive triangles with the same key (a part, a material batch, a part x material batch ...). Triangles
 * are only reordered INSIDE their segment, so every segment keeps its exact index range: an existing draw plan that
 * addresses index ranges per part / per material batch (aligned to segments) stays valid on the reordered buffer.
 * A key may repeat in non-adjacent runs; each run is its own segment. No keys = one segment over the whole mesh.
 *
 * ORDER. Inside a segment the triangles are stably sorted by signature; signatures are ordered lexicographically by
 * their ascending group ids, the empty signature first ({} < {0} < {0,1} < {0,1,5} < {0,2} < {1} ...). Each run of
 * one signature inside one segment is a CLASS. Triangles of one class keep their original relative order.
 *
 * COMPLEXITY. T triangles, R group rows, V vertices, S distinct signatures, C classes (C <= min(T, segments x S),
 * S <= min(T, 2^groups)). Build: O(V + R + T x (cost of intersecting three sorted group lists) + T log T) time,
 * O(V + R + T) memory (100k triangles x 20 groups: a few ms, see the mask ctest). Query: O(sum of signature sizes +
 * C + bitset words), no allocation after a thread's first calls; it returns at most C ranges. There is no cap: a
 * pathological mesh whose triangles all differ in signature still gets a correct plan, only with many classes (=
 * many ranges per query); a garment-style authoring (a few overlapping areas) gives a few classes per segment.
 *
 * BITSETS. Group g = bit (g % 64) of word (g / 64); words = (group_count + 63) / 64. A shorter array (or NULL with
 * word_count 0) reads as zeros; a set bit at or beyond group_count is refused (CO_ERR_RANGE).
 *
 * LIMITS. Index counts must fit an int32 (ranges are GL element counts). Any number of groups (no fixed cap).
 *
 * Struct layouts (64-bit):
 *   co_mask_info  32 B: int64 triangle_count; int32 vertex_count, group_count, segment_count, signature_count,
 *                       class_count, reserved
 *   co_mask_range 16 B: int32 first, count, key, segment
 *   co_mask_class 16 B: int32 first, count, segment, signature
 */
#pragma once

#include "cenda/model.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct co_mask_plan co_mask_plan;

typedef struct co_mask_info {
    int64_t triangle_count;
    int32_t vertex_count;    /* the soup vertex count the plan was built for */
    int32_t group_count;
    int32_t segment_count;   /* key runs (0 for an empty mesh) */
    int32_t signature_count; /* distinct signatures among the triangles (the empty one included when used) */
    int32_t class_count;     /* (segment, signature) runs = the most ranges co_mask_ranges can return */
    int32_t reserved;
} co_mask_info;

/* An index range of the plan's buffer: glDrawElements(mode, count, GL_UNSIGNED_INT, first * 4). */
typedef struct co_mask_range {
    int32_t first;   /* first index (a multiple of 3) */
    int32_t count;   /* index count (a multiple of 3, > 0) */
    int32_t key;     /* key of the segment the range starts in */
    int32_t segment; /* that segment's ordinal (segments in buffer order) */
} co_mask_range;

/* A class: the triangles of one signature inside one segment. */
typedef struct co_mask_class {
    int32_t first;     /* first index (a multiple of 3) */
    int32_t count;     /* index count */
    int32_t segment;
    int32_t signature; /* signature id (co_mask_signature); ids follow the order described above */
} co_mask_class;

/* Key modes of co_mask_plan_from_doc. */
enum {
    CO_MASK_KEY_NONE = 0,   /* one segment: triangles may move across parts (only for single-part draws) */
    CO_MASK_KEY_PART = 1,   /* key = index (co_doc_parts_get order) of the first part whose index range holds the
                             * triangle, -1 when none: per-part draws keep their index ranges */
    CO_MASK_KEY_CUSTOM = 2  /* key = tri_keys[t] (tri_key_count must equal the triangle count) */
};

/* co_mask_ranges flags. */
enum {
    CO_MASK_MERGE_SEGMENTS = 1 /* also merge visible ranges that touch across a segment boundary (one range may then
                                * span several segments; key / segment name the first). Default: never. */
};

/* From raw arrays. indices: 3 per triangle (index_count a multiple of 3), each in [0, vertex_count). tri_keys: one
 * per triangle, NULL = no keys. Groups as CSR: group g owns rows [group_rows[g], group_rows[g+1]) of `vertices`
 * (SOUP vertex ids in [0, vertex_count)) and `weights` (NULL = all rows in the group); group_rows[0] must be 0 and
 * the offsets non-decreasing; group_rows may be NULL when group_count is 0. names: group_count strings or NULL (no
 * names: co_mask_group_find never matches, co_mask_group_name returns ""); a NULL entry = "". Returns NULL on error. */
co_mask_plan* co_mask_plan_create(const int32_t* indices, int64_t index_count, int32_t vertex_count,
                                  const int32_t* tri_keys, int32_t group_count, const char* const* names,
                                  const int32_t* group_rows, const int32_t* vertices, const float* weights);

/* From a document: its mesh (CO_MESH_INDICES over the CO_MESH_VERTICES soup) and its vertex groups, whose rows are
 * PART-LOCAL (soup vertex = the part's vertex_start + local vertex; a local vertex outside [0, vertex_count) of the
 * part is CO_ERR_RANGE, a part id no part has is CO_ERR_ARG). key_mode = CO_MASK_KEY_*; tri_keys / tri_key_count
 * only for CO_MASK_KEY_CUSTOM (ignored otherwise). A document without a mesh gives an empty plan. NULL on error. */
co_mask_plan* co_mask_plan_from_doc(const co_doc* d, int32_t key_mode, const int32_t* tri_keys, int64_t tri_key_count);

void co_mask_plan_destroy(co_mask_plan* p);

int32_t co_mask_plan_info(const co_mask_plan* p, co_mask_info* out);

/* The reordered index buffer to upload (3 per triangle; borrowed, valid until destroy). Returns the index count,
 * -1 on a null plan. */
int64_t co_mask_indices(const co_mask_plan* p, const int32_t** out_data);
/* perm[slot] = the ORIGINAL triangle stored at triangle slot `slot` of the reordered buffer (borrowed). Returns the
 * triangle count, -1 on a null plan. To reorder per-triangle data (face ids ...): out[s] = in[perm[s]]. */
int64_t co_mask_permutation(const co_mask_plan* p, const int32_t** out_data);

/* Segments in buffer order as ranges (first, count, key, segment). Returns the total, copies min(total, capacity)
 * (out may be NULL to count). */
int32_t co_mask_segments(const co_mask_plan* p, co_mask_range* out, int32_t capacity);
/* Classes in buffer order. Returns the total, copies min(total, capacity). */
int32_t co_mask_classes(const co_mask_plan* p, co_mask_class* out, int32_t capacity);
/* Signature `signature`'s groups (ascending; borrowed). Returns their count, negative on error. */
int32_t co_mask_signature(const co_mask_plan* p, int32_t signature, const int32_t** out_groups);

/* Group index by exact name (the first when names repeat); -1 when absent or on error (co_last_error says which). */
int32_t co_mask_group_find(const co_mask_plan* p, const char* name);
/* Group name (borrowed; "" when unnamed); NULL on error. */
const char* co_mask_group_name(const co_mask_plan* p, int32_t group);

/* The visible index ranges for the given HIDE and KEEP group bitsets (see the header comment), in buffer order:
 * per segment in segment order, adjacent visible classes merged (and across segments with CO_MASK_MERGE_SEGMENTS).
 * Returns the total range count (0 = nothing visible), copies min(total, capacity) (out may be NULL to count;
 * co_mask_info.class_count is always enough); negative on error. */
int32_t co_mask_ranges(const co_mask_plan* p, const uint64_t* hide_words, int32_t hide_word_count,
                       const uint64_t* keep_words, int32_t keep_word_count, int32_t flags, co_mask_range* out,
                       int32_t capacity);

#ifdef __cplusplus
}
#endif
