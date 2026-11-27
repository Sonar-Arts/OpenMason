/* Cenda model mesh pipeline - the editor's soup -> editable mesh -> render mesh -> topology chain, native (OpenMason
 * #175 phase F3). Part of the co_* ABI (CO_ABI_VERSION 1: these functions are additions; result codes and
 * co_face_mapping are cenda/model.h's).
 *
 * It reproduces, array for array, what engine-modeling's Java classes compute (they are the wire truth; a switch
 * of the Java side to this ABI is parity-tested against them):
 *   stage WELD         editable/VertexWelder.weld                     soup positions -> welded vertex ids
 *   stage FACES        editable/MeshImporter.importSoup               soup + triangle face ids -> polygon loops
 *                      (or co_mesh_set_faces: an EditableMesh handed over as is)
 *   stage TRIANGULATE  editable/PolygonTriangulator.triangulate       loops -> triangles (RenderMeshBuilder order)
 *   stage RENDER       editable/RenderMeshBuilder.build               corners, corner UVs, corner <-> vertex maps
 *   stage TOPOLOGY     topology/MeshTopologyBuilder.build             edges, adjacency, face geometry,
 *                      (+ MeshGeometry, EdgeClassifier)               dihedral angles, edge kinds
 *   co_mesh_move_vertices: a positions-only refresh (a drag) in O(affected corners); the result equals a full
 *                      RENDER + TOPOLOGY rebuild after the move, bit for bit.
 *
 * ERRORS: functions return CO_OK / a count >= 0 / a negative CO_ERR_* (NULL / -1 where documented) and report the
 * reason through co_last_error() (model.h; the one store behind every co_* header). A successful call clears it.
 * No exception crosses this ABI. A failed call leaves the mesh unchanged unless documented otherwise.
 *
 * THREADING: a co_mesh belongs to one thread at a time. co_polygon_triangulate is reentrant.
 *
 * FLOATS: every float result is computed with the Java code's exact IEEE operation order (single precision where
 * Java uses float, double where it uses double, JOML's dot = x*x' + (y*y' + z*z'), no FMA contraction), so results
 * are bit-identical to the Java classes. One exception: dihedral angles go through acos (Java: fdlibm StrictMath,
 * here the C library); the double results can differ in the last bit in rare cases, the float result practically
 * never.
 *
 * ------------------------------------------------------------------------------------------------------------------
 * ACCESS: the handle owns every result; one call per array. co_mesh_array(m, CO_MESH_A_*, &ptr) returns the element
 * count and borrows the elements (4 bytes each: int32 or float as listed below) until the next call that changes
 * the mesh (any co_mesh_* call except the const getters) or co_mesh_destroy. Copy what you keep. An array of a stage
 * that is not current returns -1 (co_last_error says which stage is missing).
 *
 * Words used below: V = vertex count (welded / editable vertices, orphans included), F = face count (live faces in
 * insertion order; a face's position in that order is its SLOT), U = face id upper bound (ids are sparse-safe: arrays
 * "by face id" have U entries, gaps = deleted ids), C = corner count (= sum of loop lengths = render vertex count),
 * T = triangle count (= C - 2F), E = edge count, S = soup vertex count.
 *
 * CSR: a pair (OFFSETS, VALUES) - row r's values are VALUES[OFFSETS[r] .. OFFSETS[r+1]), OFFSETS[0] = 0.
 *
 * CORNERS = LOOP POSITIONS: face slot f's corners are [LOOP_OFFSETS[f], LOOP_OFFSETS[f+1]) in loop order, exactly as
 * RenderMeshBuilder emits them (face insertion order, loop order). So LOOP_VERTICES is RenderMesh.cornerToVertexId,
 * and face f's triangles are [LOOP_OFFSETS[f] - 2f, LOOP_OFFSETS[f+1] - 2(f+1)) (every face emits loop length - 2).
 *
 * Arrays (which, element type, size, stage):
 *   WELD
 *     CO_MESH_A_SOUP_TO_VERTEX      int32[S]      welded vertex id per soup vertex   (VertexWelder.soupToWelded)
 *     CO_MESH_A_VERTEX_SOUP_OFFSETS int32[V+1]    CSR: every soup vertex welded into vertex v, ascending
 *     CO_MESH_A_VERTEX_SOUP         int32[S]      (MeshImporter.ImportResult.vertexIdToSoupIndices)
 *   WELD or FACES
 *     CO_MESH_A_VERTEX_POSITIONS    float[3V]     welded positions (first soup occurrence) / the editable positions;
 *                                                 co_mesh_move_vertices updates them
 *   FACES
 *     CO_MESH_A_FACE_IDS            int32[F]      face id per slot (insertion order)
 *     CO_MESH_A_LOOP_OFFSETS        int32[F+1]    CSR over slots: the polygon loops (authoritative winding)
 *     CO_MESH_A_LOOP_VERTICES       int32[C]      vertex id per corner (= RenderMesh.cornerToVertexId)
 *     CO_MESH_A_LOOP_UVS            float[2C]     authored corner UVs (EditableFace.cornerUVs); 0 where a face has
 *                                                 none (CO_MESH_FACE_AUTHORED_UVS clear)
 *     CO_MESH_A_FACE_FLAGS          int32[F]      CO_MESH_FACE_* bits
 *     CO_MESH_A_CORNER_FACES        int32[C]      face slot per corner
 *     CO_MESH_A_FACE_SLOTS          int32[U]      slot per face id, -1 for a gap
 *     CO_MESH_A_WARNINGS            int32[3W]     (kind, face id or -1, value) per import warning, in the order Java's
 *                                                 warning sink receives MeshImporter.ImportWarning (see co_mesh_import)
 *   TRIANGULATE
 *     CO_MESH_A_TRI_INDICES         int32[3T]     corner indices (= RenderMesh.indices)
 *     CO_MESH_A_TRI_FACE_IDS        int32[T]      face id per triangle (= RenderMesh.triangleToFaceId)
 *   RENDER
 *     CO_MESH_A_CORNER_POSITIONS    float[3C]     (= RenderMesh.vertices)
 *     CO_MESH_A_CORNER_UVS          float[2C]     (= RenderMesh.texCoords)
 *     CO_MESH_A_VERTEX_CORNER_OFFSETS int32[V+1]  CSR: corners per vertex, ascending (= RenderMesh.vertexIdToCorners;
 *     CO_MESH_A_VERTEX_CORNERS      int32[C]      orphan vertices have empty rows)
 *   TOPOLOGY (MeshTopology's primitive state; the derived queries - loops, rings, islands - read these)
 *     CO_MESH_A_EDGE_VERTICES       int32[2E]     (vertexA, vertexB), vertexA < vertexB; edge ids in first-use order
 *                                                 walking faces in order, loop edge i = (loop[i], loop[i+1 mod n])
 *     CO_MESH_A_EDGE_FACE_OFFSETS   int32[E+1]    CSR: face ids per edge (MeshEdge.adjacentFaceIds) in the iteration
 *     CO_MESH_A_EDGE_FACES          int32[]       order of the java.util.HashSet the Java builder collects them in
 *     CO_MESH_A_CORNER_EDGES        int32[C]      edge id of loop edge (corner c -> next corner) = MeshFace.edgeIds
 *     CO_MESH_A_VERTEX_EDGE_OFFSETS int32[V+1]    CSR: edge ids per vertex, ascending (ElementAdjacencyQuery)
 *     CO_MESH_A_VERTEX_EDGES        int32[2E]
 *     CO_MESH_A_VERTEX_FACE_OFFSETS int32[V+1]    CSR: face ids per vertex in face insertion order
 *     CO_MESH_A_VERTEX_FACES        int32[C]
 *     CO_MESH_A_FACE_ADJ_OFFSETS    int32[U+1]    CSR BY FACE ID: edge-adjacent face ids (getAdjacentFaces), Java
 *     CO_MESH_A_FACE_ADJ            int32[]       HashSet iteration order; gaps have empty rows
 *     CO_MESH_A_FACE_ADJ_EDGES      int32[]       parallel to FACE_ADJ: the shared edge id Java's facePairToEdgeId
 *                                                 holds for that pair (the highest edge id joining the two faces)
 *     CO_MESH_A_FACE_NORMALS        float[3U]     by face id, MeshGeometry.computeNormal (float Newell, normalized
 *                                                 when length > 1e-6); gaps 0 (Java: null)
 *     CO_MESH_A_FACE_CENTROIDS      float[3U]     by face id, MeshGeometry.computeCentroid; gaps 0
 *     CO_MESH_A_FACE_AREAS          float[U]      by face id, MeshGeometry.computeArea; gaps 0
 *     CO_MESH_A_EDGE_DIHEDRAL       float[E]      radians (DihedralAngleCache); NaN unless the edge has exactly 2 faces
 *     CO_MESH_A_EDGE_KIND           int32[E]      CO_MESH_EDGE_OPEN / MANIFOLD / NON_MANIFOLD (= EdgeKind ordinal)
 *   (No vertex normals, sharp / seam / crease flags or auto-sharp threshold: the Java topology dropped them in #175
 *   - smooth shading is the native shading store's, cenda/model_shading.h.)
 *   co_mesh_move_vertices
 *     CO_MESH_A_MOVED_FACES         int32[]       face slots the last move touched, ascending (empty before one)
 *
 * ------------------------------------------------------------------------------------------------------------------
 * JAVA BEHAVIOURS REPRODUCED ON PURPOSE (also where they look accidental - parity first):
 *   - Welding visits ALL soup vertices in index order (referenced or not); a vertex joins the weld of the FIRST
 *     earlier soup vertex found within epsilon (float (dx*dx + dy*dy) + dz*dz < eps*eps, strict) scanning the 27
 *     cells dx-major, then dy, then dz, each cell's vertices in insertion order - not the nearest one, so chains of
 *     near points can weld differently depending on soup order. Cells are floor(x / (2 eps)) (float division,
 *     Java's saturating double->int cast, NaN -> 0) packed into the same 21-bit-per-axis key, so cells 2^21 apart
 *     share a bucket exactly like the Java HashMap (harmless: they are far beyond epsilon).
 *   - Import groups triangles by face id in first-occurrence order (LinkedHashMap); triangles with an index out of
 *     the soup are dropped (one CORRUPT_INDICES warning, value = count), triangles degenerate after welding and
 *     negative face ids are dropped silently. triangleToFaceId shorter than the triangle count: the rest use their
 *     triangle index as face id. Fewer than 3 soup vertices or 3 indices: an empty mesh (no weld either).
 *   - Loop reconstruction: 1 triangle = its corners. Else directed boundary edges (reverse absent in the face) are
 *     put into an insertion-ordered map src -> dst in first-occurrence order of the directed edges; a src with two
 *     boundary edges keeps its FIRST position but its LAST dst. The walk starts at the first triangle's first vertex
 *     (else the map's first key) and succeeds only when it closes having visited every map entry. Otherwise (holes,
 *     ring bands, bow ties, multiple loops) the loop is the triangles' distinct vertices in insertion order and a
 *     BOUNDARY_WALK_FAILED warning (value = triangle count) is recorded - the winding is then NOT guaranteed.
 *   - Authored corner UVs (texCoords present with >= 2 floats per soup vertex): per face, each loop vertex takes the
 *     UV of the FIRST soup corner (in triangle order) of that face that welded into it.
 *   - Topology edge -> face lists and face -> adjacent faces come out in java.util.HashSet<Integer> iteration order
 *     (bucket (id ^ id >>> 16) & (capacity - 1), insertion order inside a bucket, capacity 16 doubling at 0.75 load
 *     and on 9-node buckets below 64). A bucket that Java would turn into a tree (9+ colliding ids in a 64+ table)
 *     is not reproduced (counted in co_mesh_info.approximate_order_sets; never seen on real meshes).
 *   - The triangulator works on the loop normalized to its extent (translation / scale invariant) and always emits
 *     n - 2 triangles: n == 3 as is; a degenerate Newell normal or a zero 2D area -> fan; convex (within a relative
 *     1e-7 area epsilon) -> fan from corner 0; else ear clipping (first strictly convex unblocked ear in active-list
 *     order, on-boundary points block); no ear found -> fan over the remaining active vertices.
 *
 * ------------------------------------------------------------------------------------------------------------------
 * Struct layouts (64-bit; static_asserts in mesh_pipeline.cpp):
 *   co_mesh_info  56 B: int32 stages, soup_vertex_count, vertex_count, face_count, face_id_upper_bound,
 *                       corner_count, triangle_count, edge_count, warning_count, uniform, first_face_vertex_count,
 *                       approximate_order_sets; uint64 revision (@48)
 */
#pragma once

#include "cenda/model.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ModelingConstants.POSITION_EPSILON. Every epsilon argument <= 0 (or NaN) means it. */
#define CO_MESH_DEFAULT_WELD_EPSILON 1e-4f
typedef struct co_mesh co_mesh;

/* Stages (bitmask). A later stage needs FACES; RENDER needs (and builds) TRIANGULATE. */
enum {
    CO_MESH_STAGE_WELD = 1 << 0,
    CO_MESH_STAGE_FACES = 1 << 1,
    CO_MESH_STAGE_TRIANGULATE = 1 << 2,
    CO_MESH_STAGE_RENDER = 1 << 3,
    CO_MESH_STAGE_TOPOLOGY = 1 << 4,
    CO_MESH_STAGE_ALL = 0x1F
};

/* Face flags (CO_MESH_A_FACE_FLAGS). */
enum {
    CO_MESH_FACE_AUTHORED_UVS = 1 << 0,        /* the face carries authored corner UVs */
    CO_MESH_FACE_WALK_FAILED = 1 << 1,         /* import: boundary walk failed, insertion-order loop */
    CO_MESH_FACE_TRI_DEGENERATE_NORMAL = 1 << 2, /* triangulation fell back to a fan: Newell normal ~ 0 */
    CO_MESH_FACE_TRI_ZERO_AREA = 1 << 3,       /* ... signed 2D area exactly 0 */
    CO_MESH_FACE_TRI_NO_EAR = 1 << 4,          /* ... ear clipping got stuck (the rest is a fan) */
    CO_MESH_FACE_UV_PROJECTED = 1 << 5,        /* render: the corner UVs were projected (not authored) */
    CO_MESH_FACE_UV_DEGENERATE = 1 << 6        /* render: projection frame degenerate, region centre used */
};

/* Import warnings (kind = MeshImporter.ImportWarning.Kind ordinal). */
enum {
    CO_MESH_WARN_BOUNDARY_WALK_FAILED = 0, /* face id, value = the face's triangle count */
    CO_MESH_WARN_DEGENERATE_FACE = 1,      /* face id, value = loop length (unreachable with valid input) */
    CO_MESH_WARN_CORRUPT_INDICES = 2       /* face id -1, value = dropped triangle count */
};

/* Edge kinds (EdgeKind ordinal). */
enum { CO_MESH_EDGE_OPEN = 0, CO_MESH_EDGE_MANIFOLD = 1, CO_MESH_EDGE_NON_MANIFOLD = 2 };

/* Per-mapping flags (co_mesh_set_mappings). RenderMeshBuilder.emitFaceUVs draws a face's AUTHORED corner UVs when
 * it has some and (it has no mapping, or the mapping is not EXPLICIT, or the mapping has CO_MESH_MAPPING_AUTHORED_UVS
 * = FaceTextureMapping.authoredUVs()); otherwise it projects the face into its mapping's region (FULL_REGION / no
 * rotation without a mapping). EXPLICIT = material id != 0 or a region that is not exactly (0,0,1,1) by
 * Float.compare (so a -0.0 bound is explicit); the rotation alone does not make a mapping explicit. */
enum {
    CO_MESH_MAPPING_AUTHORED_UVS = 1 << 0
};

/* co_mesh_move_vertices *out_changes bits. */
enum {
    CO_MESH_CHANGED_POSITIONS = 1 << 0, /* corner positions (always, when anything moved) */
    CO_MESH_CHANGED_UVS = 1 << 1,       /* some projected corner UV changed value */
    CO_MESH_CHANGED_INDICES = 1 << 2,   /* some face re-triangulated differently (re-upload the index ranges) */
    CO_MESH_CHANGED_TOPOLOGY = 1 << 3   /* topology geometry (face normals, centroids, areas, dihedrals) refreshed */
};

/* Array ids for co_mesh_array (see the table above). */
enum {
    CO_MESH_A_SOUP_TO_VERTEX = 0,
    CO_MESH_A_VERTEX_SOUP_OFFSETS = 1,
    CO_MESH_A_VERTEX_SOUP = 2,
    CO_MESH_A_VERTEX_POSITIONS = 3,
    CO_MESH_A_FACE_IDS = 4,
    CO_MESH_A_LOOP_OFFSETS = 5,
    CO_MESH_A_LOOP_VERTICES = 6,
    CO_MESH_A_LOOP_UVS = 7,
    CO_MESH_A_FACE_FLAGS = 8,
    CO_MESH_A_CORNER_FACES = 9,
    CO_MESH_A_FACE_SLOTS = 10,
    CO_MESH_A_WARNINGS = 11,
    CO_MESH_A_TRI_INDICES = 12,
    CO_MESH_A_TRI_FACE_IDS = 13,
    CO_MESH_A_CORNER_POSITIONS = 14,
    CO_MESH_A_CORNER_UVS = 15,
    CO_MESH_A_VERTEX_CORNER_OFFSETS = 16,
    CO_MESH_A_VERTEX_CORNERS = 17,
    CO_MESH_A_EDGE_VERTICES = 18,
    CO_MESH_A_EDGE_FACE_OFFSETS = 19,
    CO_MESH_A_EDGE_FACES = 20,
    CO_MESH_A_CORNER_EDGES = 21,
    CO_MESH_A_VERTEX_EDGE_OFFSETS = 22,
    CO_MESH_A_VERTEX_EDGES = 23,
    CO_MESH_A_VERTEX_FACE_OFFSETS = 24,
    CO_MESH_A_VERTEX_FACES = 25,
    CO_MESH_A_FACE_ADJ_OFFSETS = 26,
    CO_MESH_A_FACE_ADJ = 27,
    CO_MESH_A_FACE_ADJ_EDGES = 28,
    CO_MESH_A_FACE_NORMALS = 29,
    CO_MESH_A_FACE_CENTROIDS = 30,
    CO_MESH_A_FACE_AREAS = 31,
    CO_MESH_A_EDGE_DIHEDRAL = 32,
    CO_MESH_A_EDGE_KIND = 33,
    CO_MESH_A_MOVED_FACES = 34,
    CO_MESH_A_COUNT = 35
};

typedef struct co_mesh_info {
    int32_t stages;                  /* CO_MESH_STAGE_* bits that are current */
    int32_t soup_vertex_count;       /* S of the last weld / import (0 after co_mesh_set_faces) */
    int32_t vertex_count;            /* V */
    int32_t face_count;              /* F */
    int32_t face_id_upper_bound;     /* U (EditableMesh.faceIdUpperBound) */
    int32_t corner_count;            /* C */
    int32_t triangle_count;          /* T (0 before TRIANGULATE) */
    int32_t edge_count;              /* E (0 before TOPOLOGY) */
    int32_t warning_count;           /* import warnings (CO_MESH_A_WARNINGS / 3) */
    int32_t uniform;                 /* 1 when every face has first_face_vertex_count vertices (TopologyMetadataQuery) */
    int32_t first_face_vertex_count; /* loop length of slot 0; 0 without faces */
    int32_t approximate_order_sets;  /* HashSet orders not reproduced exactly (tree bins); 0 in practice */
    uint64_t revision;               /* bumped by every call that changes the mesh */
} co_mesh_info;

/* --- Lifetime ------------------------------------------------------------------------------------------------- */

/* An empty mesh (no stages, no mappings). NULL only on OOM. */
co_mesh* co_mesh_create(void);
co_mesh* co_mesh_clone(const co_mesh* m);
void co_mesh_destroy(co_mesh* m);
int32_t co_mesh_info_get(const co_mesh* m, co_mesh_info* out);

/* Element count of an array (see the table) and *out_data (may be NULL) borrowing it; -1 when the array's stage
 * is not current or `which` is unknown. */
int64_t co_mesh_array(const co_mesh* m, int32_t which, const void** out_data);

/* --- Settings (kept across rebuilds; they take effect at the next build) ------------------------------------- */

/* Replaces the face mapping table (FaceTextureManager.getFaceMapping): one co_face_mapping per mapped face id; a later
 * entry for the same face id wins; ids not in the mesh are kept (they apply once such a face exists). count 0 = no
 * mappings (every face unmapped). mapping_flags int32[count] or NULL (= 0 each): CO_MESH_MAPPING_* bits per mapping
 * (FaceTextureMapping.authoredUVs). uv_rotation_degrees must be 0, 90, 180 or 270 (CO_ERR_ARG, nothing changes).
 * auto_resize is ignored (an editor flag). Rebuild RENDER (co_mesh_build) to apply. */
int32_t co_mesh_set_mappings(co_mesh* m, const co_face_mapping* mappings, const int32_t* mapping_flags, int32_t count);

/* --- Stages ----------------------------------------------------------------------------------------------------- */

/* WELD alone (VertexWelder.weld): positions float[3 * soup_vertex_count]. Replaces the mesh: afterwards only WELD is
 * current (vertex positions = welded positions, no faces). soup_vertex_count 0 = an empty weld. */
int32_t co_mesh_weld(co_mesh* m, const float* positions, int64_t soup_vertex_count, float epsilon);

/* The import (MeshImporter.importSoup) = WELD + FACES, then the extra `stages` (TRIANGULATE / RENDER / TOPOLOGY; one
 * call = the editor's whole load / part-rebuild path: CO_MESH_STAGE_ALL).
 *   positions        float[3 * soup_vertex_count] (Java's VertexWelder throws when vertices.length is not a multiple
 *                    of 3: the binding checks that before calling)
 *   tex_coords       float[tex_coord_count] or NULL; used (authored corner UVs) only when tex_coord_count >=
 *                    2 * soup_vertex_count (Java: texCoords.length >= (vertices.length / 3) * 2)
 *   indices          int32[index_count]; triangles = index_count / 3 (a remainder is ignored)
 *   tri_face_ids     int32[tri_face_id_count] or NULL (NULL = face id = triangle index)
 * Warnings (CO_MESH_A_WARNINGS) in Java's emission order: CORRUPT_INDICES first (if any), then per face in face order
 * BOUNDARY_WALK_FAILED / DEGENERATE_FACE. CO_ERR_ARG: NULL handle, NULL arrays with a positive count, negative counts,
 * a soup or corner count beyond int32. CO_ERR_RANGE: a face id >= 2^26 anywhere in tri_face_ids (also on a triangle
 * Java would drop; Java itself overflows faceId + 1 at Integer.MAX_VALUE and sizes topology arrays by the largest id).
 * Nothing changes on an argument / range error. */
int32_t co_mesh_import(co_mesh* m, const float* positions, int64_t soup_vertex_count, const float* tex_coords,
                       int64_t tex_coord_count, const int32_t* indices, int64_t index_count,
                       const int32_t* tri_face_ids, int64_t tri_face_id_count, float epsilon, int32_t stages);

/* Hands over an EditableMesh as is (after editor ops) = FACES, then the extra `stages`.
 *   positions        float[3 * vertex_count]
 *   face_ids         int32[face_count], >= 0, distinct (the mesh's insertion order)
 *   loop_offsets     int32[face_count + 1] CSR, loop_offsets[0] = 0, every loop >= 3 vertices
 *   loop_vertices    int32[loop_offsets[face_count]]: vertex ids in [0, vertex_count), no repeat inside a loop
 *   face_has_uvs     int32[face_count] or NULL (= none): 1 = corner_uvs holds the face's authored UVs
 *   corner_uvs       float[2 * corners] or NULL (required when any face_has_uvs is set)
 *   face_id_upper_bound  EditableMesh.faceIdUpperBound (a value <= the largest id is raised to largest id + 1)
 * Validation as EditableMesh.addFaceWithId (CO_ERR_ARG / CO_ERR_RANGE, nothing changes). Face ids >= 2^26 are
 * refused (CO_ERR_RANGE): arrays by face id are sized by the upper bound. */
int32_t co_mesh_set_faces(co_mesh* m, const float* positions, int32_t vertex_count, const int32_t* face_ids,
                          int32_t face_count, const int32_t* loop_offsets, const int32_t* loop_vertices,
                          const int32_t* face_has_uvs, const float* corner_uvs, int32_t face_id_upper_bound,
                          int32_t stages);

/* (Re)builds TRIANGULATE / RENDER / TOPOLOGY from the current faces, positions and mappings (WELD and FACES bits
 * are ignored). CO_ERR_ARG when FACES is not current or a face id upper bound is >= 2^26 for
 * TOPOLOGY (Java would allocate per-id arrays that large too). */
int32_t co_mesh_build(co_mesh* m, int32_t stages);

/* Positions-only refresh (a drag frame): vertex_ids[count] get xyz[3 * count] (a repeated id: the last one wins).
 * Updates VERTEX_POSITIONS and, for the faces using a moved vertex: corner positions, projected corner UVs, the
 * face's triangles (re-triangulated in place - the triangle count per face never changes), triangulation flags,
 * and when TOPOLOGY is current the face normals / centroids / areas and the dihedral angles of those faces' edges. Every array then equals what co_mesh_build would
 * produce for the new positions. Needs FACES; works with whatever later stages are current (RENDER needs
 * TRIANGULATE). WELD arrays are not touched. *out_changes (may be NULL) gets CO_MESH_CHANGED_* bits;
 * CO_MESH_A_MOVED_FACES lists the touched slots. CO_ERR_RANGE: a vertex id out of range (nothing changes).
 * Cost: O(moved corners + sum over touched faces of their triangulation and edges). */
int32_t co_mesh_move_vertices(co_mesh* m, const int32_t* vertex_ids, const float* xyz, int64_t count,
                              int32_t* out_changes);

/* Edge id joining vertices a and b (either order; MeshTopology.getEdgeByVertices), -1 when none or TOPOLOGY is not
 * current. O(1). */
int32_t co_mesh_find_edge(const co_mesh* m, int32_t a, int32_t b);

/* --- Stateless ------------------------------------------------------------------------------------------------ */

/* PolygonTriangulator.triangulate for one loop: xyz float[3n] (n >= 3) -> out_indices int32[3(n-2)] loop-local,
 * and out_newell float[3] (may be NULL) = PolygonTriangulator.newellNormal(loop) (double accumulation, not
 * normalized). Returns 0 = regular, or a CO_MESH_FACE_TRI_* bit when it fell back to a fan (Java logs a warning);
 * CO_ERR_ARG for NULL arrays or n < 3. Uses a thread-local scratch (reentrant across threads). */
int32_t co_polygon_triangulate(const float* xyz, int32_t n, int32_t* out_indices, float* out_newell);

#ifdef __cplusplus
}
#endif
