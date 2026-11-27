# mesh_pipeline fixtures (OpenMason #175 F3)

Golden expectations for the native mesh pipeline (`cenda/model_mesh.h`), written by HAND-TRACING the Java classes of
`engine-modeling` (VertexWelder, MeshImporter, PolygonTriangulator, RenderMeshBuilder, MeshTopologyBuilder,
MeshGeometry, EdgeClassifier) - no Java ran to produce them. The native ctest `mesh_pipeline` checks every `*.json`
here; the agent that switches the Java classes to bindings must add the Java-side dump described below so parity
is proven by the Java code itself.

## File format (`"format": "openmason.meshPipelineFixture"`, version 1)

```
{
  "format": "openmason.meshPipelineFixture", "version": 1,
  "name": "...", "javaSource": "the Java test / code path it mirrors", "notes": "...",
  "tolerance": 1e-6,                       // float comparisons (absolute); ints are exact
  "input": {
    "kind": "soup" | "editable" | "weld",
    // soup     -> MeshImporter.importSoup(vertices, texCoords, indices, triangleToFaceId)
    "vertices": [x,y,z,...], "texCoords": [u,v,...] (optional), "indices": [...], "triangleToFaceId": [...] (optional),
    // weld     -> VertexWelder.weld(vertices, epsilon)
    "epsilon": 1e-4 (optional),
    // editable -> an EditableMesh: positions + faces in insertion order (addFaceWithId)
    "positions": [x,y,z,...], "faces": [{"id": 0, "loop": [...], "cornerUVs": [...] (optional)}],
    "faceIdUpperBound": n (optional),
    // all kinds with faces: FaceTextureManager content
    "faceMappings": [{"faceId", "materialId", "u0", "v0", "u1", "v1", "rotation": 0|90|180|270,
                      "authoredUVs": false (optional; FaceTextureMapping.authoredUVs)}] (optional)
  },
  "expected": {                             // every section optional
    "weld":   {"soupToVertex": [...], "positions": [...], "vertexToSoup": [[...], ...]},
    "faces":  {"faces": [{"id", "loop", "cornerUVs": [...] | null}], "faceIdUpperBound": n,
               "warnings": [{"kind": "BOUNDARY_WALK_FAILED"|"DEGENERATE_FACE"|"CORRUPT_INDICES", "faceId", "value"}]},
    "render": {"vertices", "texCoords", "indices", "triangleToFaceId", "cornerToVertexId",
               "vertexIdToCorners": [[...]], "triangleCount"},
    "topology": {"edges": [[a,b],...], "edgeFaces": [[...]], "faceEdges": {"<faceId>": [...]},
                 "vertexEdges": [[...]], "vertexFaces": [[...]],
                 "faceAdjacency": {"<faceId>": {"faces": [...], "edges": [...]}},   // ids without an entry: empty
                 "faceNormals"/"faceCentroids": {"<faceId>": [x,y,z]}, "faceAreas": {"<faceId>": a},
                 "edgeKinds": ["OPEN"|"MANIFOLD"|"NON_MANIFOLD"], "edgeDihedral": [... | null for NaN]}
  }
}
```

Floats are written so that `Float.parseFloat` / `strtof` give the intended float (the native test parses with
`strtof`, never via double). Orders are significant everywhere (they are what the Java code produces).

Java name mapping: `vertexToSoup` = `ImportResult.vertexIdToSoupIndices`; `faces` = `mesh.faces()` order with
`faceId()`, `loop()`, `cornerUVs()`; `render.*` = the `RenderMesh` record components; `topology.edges[e]` =
`getEdge(e).vertexA/B`, `edgeFaces[e]` = `getEdge(e).adjacentFaceIds()`, `faceEdges[id]` = `getFace(id).edgeIds()`,
`vertexEdges[v]` = `getEdgesForVertex(v)`, `vertexFaces[v]` = `getFacesForVertex(v)`, `faceAdjacency[id].faces` =
`getAdjacentFaces(id)`, `.edges[i]` = `getSharedEdge(id, faces[i]).edgeId()`, `faceNormals` = `getFaceNormal`,
`faceCentroids` = `getFaceCentroid`, `faceAreas` = `getFaceArea`, `edgeKinds`/`edgeDihedral` = `getEdgeKind`/
`getDihedralAngle`. These follow the #175 Java topology (C2): vertex normals, sharp / seam / crease flags and the
auto-sharp threshold are gone from MeshTopology (smooth shading = the native shading store), so neither side has them.

## Java parity harness to build (the switch agent)

1. **Java golden dump** (engine-modeling test, opt-in `-Dopenmason.meshPipeline.dumpGoldens=<dir>`): for every
   fixture here, run the Java classes on `input` (soup -> `MeshImporter.importSoup` with a warning sink; editable ->
   build an `EditableMesh` with `addVertex` + `addFaceWithId(id, loop, cornerUVs)`; mappings -> a
   `FaceTextureManager` with `setFaceMapping(new FaceTextureMapping(id, material, region, rotation, true,
   authoredUVs))`; `RenderMeshBuilder.build`; `MeshTopologyBuilder.build`) and assert
   `expected` within `tolerance` - this proves the hand traces. Then write the Java outputs with EXACT floats
   (`Float.floatToRawIntBits` as ints, arrays as above) to `<dir>/<name>.java.json`.
2. **Generated corpus**: the same dump over (a) every OMO in `test-fixtures/stonebreak/resources` (load the mesh
   soup, import, render with the file's face mappings, topology), (b) seeded random soups (grid positions so
   welding coincides, random triangle -> face grouping incl. ring bands / bow ties / degenerate / out-of-range
   indices / negative face ids, random texCoords), (c) seeded random editable meshes (quads, n-gons, concave and
   collinear loops, non-manifold fins, face id gaps) with random mappings (regions, -0.0 regions, rotations,
   authoredUVs on / off) on faces with and without authored UVs.
3. **Comparison test** (after the native switch, both paths alive in one test JVM): run the Java classes and the
   FFM binding (`co_mesh_import` / `co_mesh_set_faces` + `co_mesh_build(CO_MESH_STAGE_ALL)`) on the same inputs and
   compare every array bit for bit (`floatToRawIntBits`, NaN only where Java has NaN; `co_mesh_set_mappings` takes
   `authoredUVs` as `CO_MESH_MAPPING_AUTHORED_UVS` in its parallel flags array). Allowed difference:
   `edgeDihedral` may differ in the last float bit (Java fdlibm acos vs C acos) - compare with 1 ulp there only.
4. **Drag parity**: apply N random `co_mesh_move_vertices` frames and compare against Java's full
   `RenderMeshBuilder` + `MeshTopologyBuilder` rebuild after the same `setPosition` calls (the native ctest already
   proves move == native full rebuild bit for bit).
5. Keep the cases in "Known Java behaviours reproduced" (model_mesh.h) as named regression cases so a future Java
   "fix" shows up as an intended parity break.

## Fixtures

| file | Java path | what it pins |
|---|---|---|
| `quad_authored_uvs.json` | MeshImporterTest.authoredUVsAttachPerFaceCorner | loop start, authored corner UVs, render copy, open-edge topology |
| `quad_welded_midpoints.json` | MeshImporterTest.coincidentDuplicateMidpointsWeldIntoOneVertex | welding duplicate corners, interior edge cancel, projected UVs + V flip |
| `weld_epsilon_edges.json` | VertexWelder | first-match chain welding (not nearest), strict `<` at exactly epsilon, negative cells |
| `ring_band_walk_failure.json` | MeshImporter BOUNDARY_WALK_FAILED | annulus face: walk closes early, insertion-order fallback + warning |
| `concave_l_face.json` | PolygonTriangulatorTest.concaveLShape | boundary walk on a 4-triangle hexagon, ear clipping order, projected UVs, face id 7 |
| `mapped_regions_projected.json` | RenderMeshBuilder.emitFaceUVs / projectFaceUVs | regions + rotations 90/180/270, a non-explicit mapping with a rotation, an explicit mapping projecting over authored UVs (`authoredUVs` false) |
| `mapped_regions_authored_flag.json` | RenderMeshBuilder (`FaceTextureMapping.authoredUVs`) | the same mesh, the explicit mapping with `authoredUVs` true draws the authored UVs |
| `nonmanifold_fin_hash_order.json` | MeshTopologyBuilder | 3-face edge, HashSet order of edge faces / adjacency (ids 20, 3, 35) |
| `cube_face_id_gap.json` | MeshImporterTest.faceIdsPreservedVerbatimIncludingGaps | full cube: weld map, loops, render, every topology array, -Y UV frame |
