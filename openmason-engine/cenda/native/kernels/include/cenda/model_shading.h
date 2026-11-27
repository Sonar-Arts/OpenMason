/* Cenda model shading - corner normals with Blender semantics + the editor's shading store (OpenMason #169; data in
 * OMO 1.9 "shading", #168). Part of the co_* ABI (CO_ABI_VERSION 1: these functions are additions; the types and
 * result codes are cenda/model.h's).
 *
 * ERRORS: every function here returns CO_OK / a negative CO_ERR_* (or NULL where documented) and reports the reason
 * through co_last_error() (model.h): one thread-local store for every co_* header, so co_shading_last_error() returns
 * the same message (kept as an alias). No exception crosses this ABI.
 *
 * ------------------------------------------------------------------------------------------------------------------
 * THE MESH. An OMO mesh is a per-CORNER triangle soup: `positions` (float3 per soup vertex), `indices` (int32, 3 per
 * triangle) and `tri_face_ids` (int32 per triangle; a face = one simple polygon made of 1+ triangles). Soup vertices
 * are WELDED by position exactly like the editor's VertexWelder (engine-modeling): soup vertices that triangles
 * reference are visited in ascending index order; each one joins the weld of the FIRST earlier vertex (grid cell
 * = 2 * epsilon, 27 cells visited dx-major, then dy, then dz, earlier vertices in ascending order) whose float
 * squared distance is < epsilon^2, else starts a new weld. A weld = one mesh vertex; its position (the "welded
 * position") is that of its first soup vertex. Edges are pairs of welded vertices.
 *
 * A soup vertex's CORNER is (face of the first triangle that references it, its weld). A soup vertex referenced by
 * triangles of two faces (not produced by the editor) takes the corner of its lowest triangle.
 *
 * ------------------------------------------------------------------------------------------------------------------
 * NORMALS = BLENDER'S CORNER ("split") NORMALS (Blender 4.1+ mesh normals, which equal 2.8x-4.0 "Auto Smooth"):
 *   1. Face normal = the polygon's normal: the vector area of its triangles (sum of the triangle cross products =
 *      Newell's normal of the polygon boundary), normalized. Computed in double from the WELDED positions.
 *   2. A corner of a FLAT face (smooth bit clear = Blender's sharp_face) gets its face normal, bit-identical for
 *      every corner of the face.
 *   3. Corners of SMOOTH faces around a welded vertex are grouped into FANS. Two faces around the vertex are in the
 *      same fan when they share an edge incident to it that is SMOOTH. An edge is SHARP (splits fans) when ANY of:
 *        - it is marked sharp (sharp edge pairs: soup vertex pairs compared by welded position),
 *        - it is not manifold: used by 1 face (boundary) or by more than 2 faces,
 *        - its two faces use it in the same direction (inconsistent winding; Blender does the same),
 *        - either face is flat,
 *        - auto smooth is on (degrees > 0) and the angle between the two face normals is ABOVE it
 *          (dot(n1, n2) < cos(angle), so exactly the threshold stays smooth; Blender's rule).
 *      Edges are a polygon's BOUNDARY edges (a triangle edge that the face's other triangles do not cancel); the
 *      internal diagonals of a face made of several triangles are no edges.
 *      Fans are the connected components of corners linked by smooth edges, which is what Blender's fan walk finds
 *      (a "bow-tie" vertex gives two fans).
 *   4. A fan's normal = sum over its corners of (face normal x CORNER ANGLE), normalized. The corner angle is the
 *      polygon's angle at that vertex between its two boundary edges, in [0, pi] (Blender's weighting; a reflex
 *      corner counts as 2 pi minus its interior angle, exactly like Blender). A fan of one corner = the face normal
 *      bit-identically (so smooth faces with all edges sharp equal flat faces).
 *   5. Custom normals (when given for a soup vertex) override the result for that soup vertex.
 * DIFFERENCES FROM BLENDER (all on degenerate or custom data):
 *   - A zero-area face (relative to its edge lengths) has no normal (Blender uses +Z). It adds NO weight to fans;
 *     when smooth its edges do not split (it takes its neighbours' fan normal); the auto smooth test is skipped for
 *     its edges.
 *   - A corner with no usable normal (zero-area flat face, or a fan whose weighted sum cancels) falls back to: the
 *     face normal if the face has one, else the angle-weighted normal of every face around its welded vertex (in
 *     its group), else (0, 0, 1). Soup vertices no triangle references get (0, 0, 1). The output is never NaN.
 *   - Blender stores custom normals relative to the fan ("lnor space") and shares one per fan; here they are
 *     absolute object-space normals per soup vertex (OMO 1.9's customNormals).
 *   - Corner whose polygon boundary at that vertex is ambiguous (not a simple polygon): the angle is the sum of its
 *     triangles' angles there, clamped to [0, pi].
 *
 * ------------------------------------------------------------------------------------------------------------------
 * OCTAHEDRAL NORMALS (the GPU format: the model normal VBO, attribute location 4, 2 x int16 per vertex; also OMO
 * 1.9 customNormals). Signed normalized 16 bit, scale 32767:
 *   encode(n): n /= |n.x| + |n.y| + |n.z|; (u, v) = n.xy; if n.z < 0: (u, v) = ((1 - |v|) * s(u), (1 - |u|) * s(v))
 *              with s(a) = a >= 0 ? 1 : -1. The stored pair is the one of the 4 floor/ceil roundings of
 *              (u, v) * 32767 whose decoded normal is closest to n ("precise" encoding; clamped to +-32767, so the
 *              value -32768 is never written). A zero or non-finite n encodes (0, 0, 1) = (0, 0).
 *   decode(x, y): u = max(x / 32767, -1); v = max(y / 32767, -1); z = 1 - |u| - |v|; t = max(-z, 0);
 *              u += u >= 0 ? -t : t; v += v >= 0 ? -t : t; n = normalize(u, v, z).
 *   GLSL (bind the attribute as INTEGERS, glVertexAttribIPointer(4, 2, GL_SHORT, ...) -> `in ivec2 aNormalOct`, or
 *   as non-normalized floats; do not rely on GL_SHORT normalized=true, whose mapping differs before GL 4.2):
 *     vec3 octDecode(ivec2 o) {
 *         vec2 f = max(vec2(o) / 32767.0, vec2(-1.0));
 *         vec3 n = vec3(f, 1.0 - abs(f.x) - abs(f.y));
 *         float t = max(-n.z, 0.0);
 *         n.x += n.x >= 0.0 ? -t : t;
 *         n.y += n.y >= 0.0 ? -t : t;
 *         return normalize(n);
 *     }
 *   Error (precise encoding, measured by the normals ctest): max angle < 0.004 degrees.
 */
#pragma once

#include "cenda/model.h"

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* The editor's weld epsilon (ModelingConstants.POSITION_EPSILON). Every epsilon argument <= 0 (or NaN) means it. */
#define CO_SHADING_DEFAULT_WELD_EPSILON 1e-4f

/* Alias of co_last_error(): the reason of the last failed co_* call on this thread ("" after a success). */
const char* co_shading_last_error(void);

/* --- Octahedral snorm16 normals ---------------------------------------------------------------------------------- */

/* count normals: xyz[3 * count] -> oct[2 * count]. CO_ERR_ARG on a NULL array with count > 0 or count < 0. */
int32_t co_oct_encode(const float* xyz, int16_t* out_oct, int64_t count);
/* count normals: oct[2 * count] -> xyz[3 * count] (unit length). */
int32_t co_oct_decode(const int16_t* oct, float* out_xyz, int64_t count);

/* --- Corner normals ---------------------------------------------------------------------------------------------- */

/* Blender corner normals for one soup (see the top of this header).
 *   positions          float[3 * vertex_count]
 *   indices            int32[index_count], index_count % 3 == 0, every index in [0, vertex_count)
 *   tri_face_ids       int32[index_count / 3], >= 0; NULL = every triangle is its own face (face id = triangle)
 *   tri_groups         int32[index_count / 3] or NULL (= one group). Triangles of different groups never weld nor
 *                      share an edge (use the part index: parts shade apart, like separate Blender objects). Sharp
 *                      edge pairs still match by welded position in every group.
 *   smooth_words       bitset over face ids (bit f % 32 of word f / 32; a face beyond the words is flat).
 *                      NULL = EVERY face smooth. (A non-NULL pointer with smooth_word_count 0 = every face flat.)
 *   sharp_pairs        uint32[2 * sharp_edge_count] soup vertex pairs; a pair marks the edge between the welded
 *                      vertices of its two soup vertices (any soup vertices at those positions). Pairs naming an
 *                      out-of-range or unreferenced vertex, or two vertices of one weld, are ignored. May be NULL
 *                      when sharp_edge_count is 0.
 *   auto_smooth_degrees  > 0 = auto smooth angle (see rule 3); <= 0 or NaN = off.
 *   custom_vertices / custom_oct  custom_count rows: soup vertex + 2 int16 (oct snorm16) per row; out-of-range rows
 *                      are ignored, a later row for the same vertex wins. NULL / 0 = none.
 *   weld_epsilon       <= 0 = CO_SHADING_DEFAULT_WELD_EPSILON.
 *   out_xyz            float[3 * vertex_count] (required): unit normal per soup vertex.
 *   out_oct            int16[2 * vertex_count] or NULL: the same normals oct-encoded (custom rows: their own oct
 *                      values verbatim, out_xyz = their decode).
 * Returns CO_OK; CO_ERR_ARG (NULL / negative / malformed arguments), CO_ERR_RANGE (an index or face id out of
 * range). On failure the outputs are unspecified. Cost: O(n log n) in the soup size; allocates internal scratch. */
int32_t co_mesh_normals(const float* positions, int64_t vertex_count, const int32_t* indices, int64_t index_count,
                        const int32_t* tri_face_ids, const int32_t* tri_groups, const uint32_t* smooth_words,
                        int64_t smooth_word_count, const uint32_t* sharp_pairs, int64_t sharp_edge_count,
                        float auto_smooth_degrees, const int32_t* custom_vertices, const int16_t* custom_oct,
                        int64_t custom_count, float weld_epsilon, float* out_xyz, int16_t* out_oct);

/* co_mesh_normals over a document's mesh + its shading section (the runtime loader's one call). Triangles are
 * grouped by part (a part owns triangles [index_start / 3, (index_start + index_count) / 3); triangles no part owns
 * form one more group; a partless document is one group). The document's smooth set is used as is: a document
 * without one is ALL FLAT (OMO 1.9: absent = flat). triangleToFaceId null = face id per triangle. Default weld
 * epsilon. out_xyz float[3 * V], out_oct int16[2 * V] or NULL, V = co_doc_mesh_array(d, CO_MESH_VERTICES) / 3.
 * CO_ERR_ARG: null handle / outputs, no mesh vertices. */
int32_t co_doc_compute_normals(const co_doc* d, float* out_xyz, int16_t* out_oct);

/* --- The editor's shading store ----------------------------------------------------------------------------------
 * The Java editor owns geometry (rebuilding its soup after every edit) but not shading logic. A co_shading keeps a
 * part's shading in a form that survives a re-soup:
 *   - smooth flags keyed by FACE ID (a bitset; default: every face flat, like a document without "smooth");
 *   - sharp edges keyed by their two WELDED POSITIONS (float3 each);
 *   - custom normals keyed by (FACE ID, WELDED POSITION), oct snorm16 values;
 *   - the auto smooth angle (0 = off);
 *   - the weld epsilon of the part.
 * A stored position RESOLVES against a soup to the weld of the closest soup vertex within epsilon (none = the key is
 * inert until it resolves again). Soup vertex order, triangle order and soup indices may change freely between
 * calls; face ids must keep meaning the same face (the editor's face ids are stable across most edits).
 * Use one store per part with the part's OWN soup (part-local vertex indices and face ids; a part is its own
 * group). Every function that takes a soup takes the CURRENT one: positions float[3 * vertex_count], indices
 * int32[index_count], tri_face_ids int32[index_count / 3] (NULL = triangle index), same validation as
 * co_mesh_normals.
 *
 * Lifecycle in the editor:
 *   - after a topology edit (faces added/removed, extrude, delete ...): co_shading_reconcile(new soup);
 *   - while a vertex DRAG / transform moves positions: co_shading_move_vertices(old, new) with the moved welded
 *     vertices' positions before and after (call it whenever positions change, e.g. once per drag frame with the
 *     previous frame's positions, BEFORE the next compute), so the sharp edges and custom normals on the moved
 *     vertices follow them. Without it a moved key stops resolving (its edge renders smooth again);
 *   - for rendering: co_shading_compute(current soup) -> corner normals; recompute when the soup or
 *     co_shading_revision changes (the "normals dirty" flag);
 *   - undo snapshots: co_shading_clone. Save: co_shading_to_doc after the document's mesh holds the current soup.
 * Custom normal DIRECTIONS are absolute object-space normals and do not rotate with a drag; only their anchors move.
 * A store belongs to one thread at a time. */

typedef struct co_shading co_shading;

/* An empty store (every face flat, no sharp edges, no custom normals, auto smooth off). Never NULL. */
co_shading* co_shading_create(float weld_epsilon);
co_shading* co_shading_clone(const co_shading* s);
void co_shading_destroy(co_shading* s);
float co_shading_weld_epsilon(const co_shading* s);
/* Bumped by every call that changes the store (0 for a new store; a clone keeps its source's). */
uint64_t co_shading_revision(const co_shading* s);

/* A store from a document's shading section. part NULL = the whole mesh (soup indices and face ids as in the
 * file); else the part's range (co_part's vertex/index/face start + count; strings ignored): soup vertex v becomes
 * v - vertex_start, face f becomes f - face_start, and only triangles inside the index range whose vertices lie in
 * the vertex range count. Smooth bits of the face range, sharp pairs with BOTH vertices in the vertex range and
 * custom rows of the vertex range are converted to keys (rows/pairs of unreferenced vertices are dropped); the
 * auto smooth angle is copied. NULL on failure (null doc, no mesh vertices, bad ranges). */
co_shading* co_shading_from_doc(const co_doc* d, const co_part* part, float weld_epsilon);
/* Writes the store into the document's shading section, resolved against the document's CURRENT mesh (write the
 * mesh first). part NULL = replaces the whole section. With a part: replaces only that part's share - smooth bits
 * of [face_start, face_start + face_count) (store faces >= face_count are not written), sharp pairs with both
 * vertices in the part's vertex range, custom rows in the vertex range - and keeps everyone else's. A sharp edge is
 * written as the soup vertex pair of the first face (lowest face id) whose boundary has that edge, oriented as that
 * face runs it (edges that are no edge of the mesh are not written); a custom normal as one row per soup vertex of
 * its corner. New rows are sorted (pairs by first vertex then second, custom rows by vertex). The auto smooth angle
 * (one per document) is set when the store has one; a whole write without one unsets it, a part write leaves it. */
int32_t co_shading_to_doc(const co_shading* s, co_doc* d, const co_part* part);

/* Smooth flags. face ids >= 0 and < 2^26 (CO_ERR_RANGE otherwise; nothing changes). smooth: 1 = smooth, 0 = flat. */
int32_t co_shading_set_smooth(co_shading* s, const int32_t* face_ids, int64_t count, int32_t smooth);
/* 1 / 0 (0 for a NULL store or a negative id). */
int32_t co_shading_is_smooth(const co_shading* s, int32_t face_id);
/* The bitset (trailing zero words trimmed); returns the word count, *out_words borrows them until the next change. */
int64_t co_shading_smooth_words(const co_shading* s, const uint32_t** out_words);

/* Auto smooth: degrees > 0 = on; <= 0 or NaN = off. */
int32_t co_shading_set_auto_smooth(co_shading* s, float degrees);
/* The angle, 0 = off. */
float co_shading_auto_smooth(const co_shading* s);

/* Marks (sharp = 1) or clears (sharp = 0) the edges given as soup vertex pairs of the CURRENT soup positions
 * (welded over every vertex given; no triangles needed). Marking an edge already marked, or clearing one that is
 * not, does nothing; a pair of one weld is ignored. CO_ERR_RANGE: a vertex out of range (nothing changes). */
int32_t co_shading_mark_sharp(co_shading* s, const float* positions, int64_t vertex_count, const uint32_t* pairs,
                              int64_t edge_count, int32_t sharp);
int32_t co_shading_clear_all_sharp(co_shading* s);
/* The stored sharp edges as positions: float[6] per edge (a.xyz, b.xyz, a < b lexicographically). Returns the
 * total count and copies min(count, capacity) edges (out may be NULL to count). */
int64_t co_shading_sharp_edges(const co_shading* s, float* out_positions, int64_t capacity);

/* Sets custom normals given per soup vertex of the current soup (rows: vertex + oct pair): each row keys
 * (the vertex's face, its welded position) and replaces an existing key. CO_ERR_RANGE: a row's vertex out of range
 * or unreferenced (nothing changes). */
int32_t co_shading_set_custom_normals(co_shading* s, const float* positions, int64_t vertex_count,
                                      const int32_t* indices, int64_t index_count, const int32_t* tri_face_ids,
                                      const int32_t* vertices, const int16_t* oct, int64_t count);
/* Clears the custom normals of the given faces; face_ids NULL = all. */
int32_t co_shading_clear_custom_normals(co_shading* s, const int32_t* face_ids, int64_t count);
/* The stored custom normals: out_faces[n], out_positions[3n], out_oct[2n] (any may be NULL). Returns the total count
 * and copies min(count, capacity). */
int64_t co_shading_custom_normals(const co_shading* s, int32_t* out_faces, float* out_positions, int16_t* out_oct,
                                  int64_t capacity);

/* Corner normals of the current soup with the store's shading (co_mesh_normals semantics, one group, the store's
 * epsilon). out_xyz float[3 * vertex_count]; out_oct int16[2 * vertex_count] or NULL. The store is not changed. */
int32_t co_shading_compute(const co_shading* s, const float* positions, int64_t vertex_count, const int32_t* indices,
                           int64_t index_count, const int32_t* tri_face_ids, float* out_xyz, int16_t* out_oct);

/* After a topology edit: drops smooth bits of face ids the soup no longer has, sharp edges that do not resolve or
 * are no boundary edge of any face (or duplicate another), custom normals whose face is gone or whose position is
 * no corner of that face (a later duplicate wins); snaps every kept key position to its current welded position.
 * out_dropped (may be NULL) int64[3] = {smooth bits, sharp edges, custom normals} dropped. */
int32_t co_shading_reconcile(co_shading* s, const float* positions, int64_t vertex_count, const int32_t* indices,
                             int64_t index_count, const int32_t* tri_face_ids, int64_t* out_dropped);

/* Positions moved (drag, transform): every stored key position within epsilon of old_positions[i] (the closest i;
 * ties = lowest i) becomes new_positions[i]. Both float[3 * count]. Keys that do not match stay. Duplicates this
 * may create are removed by the next reconcile (compute is unaffected by them). */
int32_t co_shading_move_vertices(co_shading* s, const float* old_positions, const float* new_positions, int64_t count);

#ifdef __cplusplus
}
#endif

/* ------------------------------------------------------------------------------------------------------------------
 * PRIVATE: shared by normals.cpp and shading.cpp only (never define CENDA_MODEL_SHADING_INTERNAL elsewhere). */
#if defined(__cplusplus) && defined(CENDA_MODEL_SHADING_INTERNAL)
#include <string>
#include <vector>

namespace cenda::model::shading_detail {

int32_t fail(int32_t code, const std::string& message);
void clear_error();
const char* last_error_text();
float effective_epsilon(float epsilon);
int32_t validate_soup(const float* positions, int64_t vertex_count, const int32_t* indices, int64_t index_count,
                      const int32_t* tri_face_ids, const std::string& name);

// The welded-vertex grid query: for each query point, the weld of the closest point within epsilon (squared float
// distance < epsilon^2; ties = lowest point index), else -1.
void resolve_points(const float* points, const int32_t* point_weld, int64_t point_count, float epsilon,
                    const float* queries, int64_t query_count, int32_t* out_weld);
// Java-equal greedy weld over the vertices with use[v] != 0 (use NULL = all). out_weld[v] = -1 for unused vertices;
// out_rep_vertex[w] = the first soup vertex of weld w.
void weld_vertices(const float* positions, int64_t vertex_count, const uint8_t* use, float epsilon,
                   std::vector<int32_t>& out_weld, std::vector<int32_t>& out_rep_vertex);

struct BoundaryEdge {
    int32_t face;  // face id
    int32_t from;  // weld id (single group meshes) / node id
    int32_t to;
};

// A validated, welded soup (one group).
struct Soup {
    const float* positions = nullptr;
    int64_t vertex_count = 0;
    const int32_t* indices = nullptr;
    int64_t triangle_count = 0;
    const int32_t* tri_face_ids = nullptr;  // NULL = triangle index
    std::vector<int32_t> weld;              // per soup vertex, -1 = unreferenced
    std::vector<int32_t> rep_vertex;        // per weld: its first soup vertex
    std::vector<int32_t> face_of;           // per soup vertex: face of its first triangle, -1 = unreferenced
    std::vector<int32_t> faces;             // sorted unique face ids
    std::vector<BoundaryEdge> boundary;     // polygon boundary edges in weld ids, sorted by (face, from, to)
    [[nodiscard]] int32_t face(int64_t triangle) const {
        return tri_face_ids != nullptr ? tri_face_ids[triangle] : static_cast<int32_t>(triangle);
    }
};

int32_t prepare_soup(Soup& soup, const float* positions, int64_t vertex_count, const int32_t* indices,
                     int64_t index_count, const int32_t* tri_face_ids, float epsilon, const char* fn);
// Resolve query points against a prepared soup's referenced vertices.
void resolve_in_soup(const Soup& soup, float epsilon, const float* queries, int64_t query_count, int32_t* out_weld);

struct CustomRow {
    int32_t vertex;
    int16_t oct[2];
};

// The normals core over a prepared soup: sharp = sorted unique (min weld << 32 | max weld) keys.
int32_t compute_normals(const Soup& soup, const uint32_t* smooth_words, int64_t smooth_word_count, bool all_smooth,
                        const std::vector<uint64_t>& sharp, float auto_smooth_degrees,
                        const std::vector<CustomRow>& custom, float* out_xyz, int16_t* out_oct);

void oct_encode(const float* n, int16_t* out);
void oct_decode(const int16_t* o, float* out);

}  // namespace cenda::model::shading_detail
#endif
