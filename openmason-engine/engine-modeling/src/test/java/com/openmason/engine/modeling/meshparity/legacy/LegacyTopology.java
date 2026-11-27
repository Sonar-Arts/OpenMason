package com.openmason.engine.modeling.meshparity.legacy;

/* FROZEN COPY (test scope) of the algorithmic part of the pre-#175-F3 topology/MeshTopologyBuilder.build (steps 1-7,
 * the dihedral angles, EdgeClassifier's kinds) and topology/MeshGeometry.computeNormal / computeCentroid /
 * computeArea / computeDihedralAngle, kept verbatim as the parity reference for the native mesh pipeline
 * (MeshPipelineParityTest). Instead of wiring the MeshTopology sub-services it returns the primitive state they are
 * built from (the same collections, in the same iteration order). Do not "fix" it. */

import com.openmason.engine.modeling.ModelingConstants;
import com.openmason.engine.modeling.editable.EditableFace;
import com.openmason.engine.modeling.editable.EditableMesh;
import com.openmason.engine.modeling.editable.RenderMesh;
import org.joml.Vector3f;

import java.util.*;

/**
 * @param edges               (vertexA, vertexB) per edge id
 * @param edgeFaces           MeshEdge.adjacentFaceIds per edge id
 * @param faceIds             face id per face (insertion order)
 * @param faceEdges           MeshFace.edgeIds per face (insertion order)
 * @param vertexEdges         edge ids per vertex
 * @param vertexFaces         face ids per vertex
 * @param faceAdjacent        adjacent face ids per face id (U rows)
 * @param facePairToEdgeId    canonical face pair -> edge id
 * @param faceNormals         by face id (null for gaps)
 * @param faceCentroids       by face id (null for gaps)
 * @param faceAreas           by face id
 * @param dihedral            per edge
 * @param edgeKinds           adjacent face count class per edge: 0 OPEN, 1 MANIFOLD, 2 NON_MANIFOLD
 * @param uniform             TopologyMetadataQuery
 * @param firstCount          TopologyMetadataQuery
 */
public record LegacyTopology(int[][] edges, int[][] edgeFaces, int[] faceIds, int[][] faceEdges,
                             int[][] vertexEdges, int[][] vertexFaces, int[][] faceAdjacent,
                             Map<Long, Integer> facePairToEdgeId,
                             Vector3f[] faceNormals, Vector3f[] faceCentroids, float[] faceAreas,
                             float[] dihedral, int[] edgeKinds, boolean uniform, int firstCount) {

    public static LegacyTopology build(EditableMesh mesh, RenderMesh renderMesh) {
        if (mesh == null || renderMesh == null || mesh.faceCount() == 0
                || renderMesh.cornerCount() == 0) {
            return null;
        }

        int faceIdUpperBound = mesh.faceIdUpperBound();
        int uniqueVertexCount = mesh.vertexCount();
        float[] vertices = renderMesh.vertices();

        // Step 1: Collect edges from face loops (undirected, keyed by vertex ids).
        Map<Long, Set<Integer>> edgeToFaceIds = new LinkedHashMap<>();
        Map<Long, int[]> edgeToVertices = new HashMap<>();

        for (EditableFace face : mesh.faces()) {
            int n = face.loopLength();
            for (int i = 0; i < n; i++) {
                int a = face.vertexAt(i);
                int b = face.vertexAt((i + 1) % n);
                long key = canonicalKey(a, b);
                edgeToFaceIds.computeIfAbsent(key, k -> new HashSet<>()).add(face.faceId());
                edgeToVertices.putIfAbsent(key, new int[]{Math.min(a, b), Math.max(a, b)});
            }
        }

        // Step 2: edges with stable ids.
        int edgeCount = edgeToFaceIds.size();
        int[][] edges = new int[edgeCount][];
        int[][] edgeFaces = new int[edgeCount][];
        Map<Long, Integer> edgeKeyToId = new HashMap<>(edgeCount * 2);
        int e = 0;
        for (Map.Entry<Long, Set<Integer>> entry : edgeToFaceIds.entrySet()) {
            long key = entry.getKey();
            int[] verts = edgeToVertices.get(key);
            int[] adjacentFaceArray = entry.getValue().stream().mapToInt(Integer::intValue).toArray();
            edges[e] = new int[]{Math.min(verts[0], verts[1]), Math.max(verts[0], verts[1])};
            edgeFaces[e] = adjacentFaceArray;
            edgeKeyToId.put(key, e);
            e++;
        }

        // Step 3: faces from authoritative loops.
        int faceCount = mesh.faceCount();
        int[] faceIds = new int[faceCount];
        int[][] faceLoops = new int[faceCount][];
        int[][] faceEdges = new int[faceCount][];
        int fi = 0;
        for (EditableFace face : mesh.faces()) {
            int[] loop = face.loop();
            int[] faceEdgeIds = new int[loop.length];
            for (int i = 0; i < loop.length; i++) {
                long edgeKey = canonicalKey(loop[i], loop[(i + 1) % loop.length]);
                Integer edgeId = edgeKeyToId.get(edgeKey);
                faceEdgeIds[i] = edgeId != null ? edgeId : -1;
            }
            faceIds[fi] = face.faceId();
            faceLoops[fi] = loop;
            faceEdges[fi] = faceEdgeIds;
            fi++;
        }

        // Step 4: vertex adjacency.
        List<List<Integer>> vertexToEdges = new ArrayList<>(uniqueVertexCount);
        List<List<Integer>> vertexToFaces = new ArrayList<>(uniqueVertexCount);
        for (int i = 0; i < uniqueVertexCount; i++) {
            vertexToEdges.add(new ArrayList<>());
            vertexToFaces.add(new ArrayList<>());
        }
        for (int id = 0; id < edgeCount; id++) {
            int a = edges[id][0];
            int b = edges[id][1];
            if (a >= 0 && a < uniqueVertexCount) {
                vertexToEdges.get(a).add(id);
            }
            if (b >= 0 && b < uniqueVertexCount) {
                vertexToEdges.get(b).add(id);
            }
        }
        for (int f = 0; f < faceCount; f++) {
            for (int v : faceLoops[f]) {
                if (v >= 0 && v < uniqueVertexCount) {
                    List<Integer> faceIdsForVertex = vertexToFaces.get(v);
                    if (!faceIdsForVertex.contains(faceIds[f])) {
                        faceIdsForVertex.add(faceIds[f]);
                    }
                }
            }
        }

        // Step 4b: face-to-face adjacency.
        List<Set<Integer>> faceAdjSets = new ArrayList<>(faceIdUpperBound);
        for (int i = 0; i < faceIdUpperBound; i++) {
            faceAdjSets.add(new HashSet<>());
        }
        Map<Long, Integer> facePairToEdgeId = new HashMap<>();
        for (int id = 0; id < edgeCount; id++) {
            int[] adjFaces = edgeFaces[id];
            for (int i = 0; i < adjFaces.length; i++) {
                for (int j = i + 1; j < adjFaces.length; j++) {
                    int fA = adjFaces[i];
                    int fB = adjFaces[j];
                    if (fA >= 0 && fA < faceIdUpperBound && fB >= 0 && fB < faceIdUpperBound) {
                        faceAdjSets.get(fA).add(fB);
                        faceAdjSets.get(fB).add(fA);
                        long pairKey = canonicalKey(fA, fB);
                        facePairToEdgeId.put(pairKey, id);
                    }
                }
            }
        }
        int[][] faceAdjacent = new int[faceIdUpperBound][];
        for (int i = 0; i < faceIdUpperBound; i++) {
            faceAdjacent[i] = new ArrayList<>(faceAdjSets.get(i)).stream().mapToInt(Integer::intValue).toArray();
        }

        // Step 5: uniform topology.
        boolean uniform = true;
        int firstCount = faceCount > 0 ? faceLoops[0].length : 0;
        for (int i = 1; i < faceCount; i++) {
            if (faceLoops[i].length != firstCount) {
                uniform = false;
                break;
            }
        }

        // Step 6/7: geometry through the render mesh's exact corner maps.
        int[][] vertexIdToCorners = renderMesh.vertexIdToCorners();
        int[][] uniqueToMeshIndices = new int[uniqueVertexCount][];
        for (int i = 0; i < uniqueVertexCount; i++) {
            uniqueToMeshIndices[i] = i < vertexIdToCorners.length ? vertexIdToCorners[i].clone() : new int[0];
        }
        Vector3f[] faceNormals = new Vector3f[faceIdUpperBound];
        Vector3f[] faceCentroids = new Vector3f[faceIdUpperBound];
        float[] faceAreas = new float[faceIdUpperBound];
        for (int f = 0; f < faceCount; f++) {
            int fid = faceIds[f];
            int[] verts = faceLoops[f];
            faceNormals[fid] = computeNormal(verts, uniqueToMeshIndices, vertices);
            faceCentroids[fid] = computeCentroid(verts, uniqueToMeshIndices, vertices);
            faceAreas[fid] = computeArea(verts, uniqueToMeshIndices, vertices);
        }

        int[] kinds = new int[edgeCount];
        float[] dihedralAngles = new float[edgeCount];
        for (int i = 0; i < edgeCount; i++) {
            int[] adjFaces = edgeFaces[i];
            int count = adjFaces.length;
            kinds[i] = count <= 1 ? 0 : count == 2 ? 1 : 2;
            if (adjFaces.length == 2
                    && adjFaces[0] >= 0 && adjFaces[0] < faceIdUpperBound
                    && adjFaces[1] >= 0 && adjFaces[1] < faceIdUpperBound
                    && faceNormals[adjFaces[0]] != null
                    && faceNormals[adjFaces[1]] != null) {
                dihedralAngles[i] = computeDihedralAngle(faceNormals[adjFaces[0]], faceNormals[adjFaces[1]]);
            } else {
                dihedralAngles[i] = Float.NaN;
            }
        }

        int[][] vEdges = new int[uniqueVertexCount][];
        int[][] vFaces = new int[uniqueVertexCount][];
        for (int i = 0; i < uniqueVertexCount; i++) {
            vEdges[i] = vertexToEdges.get(i).stream().mapToInt(Integer::intValue).toArray();
            vFaces[i] = vertexToFaces.get(i).stream().mapToInt(Integer::intValue).toArray();
        }
        return new LegacyTopology(edges, edgeFaces, faceIds, faceEdges, vEdges, vFaces, faceAdjacent,
                facePairToEdgeId, faceNormals, faceCentroids, faceAreas, dihedralAngles, kinds, uniform, firstCount);
    }

    public static long canonicalKey(int v0, int v1) {
        int min = Math.min(v0, v1);
        int max = Math.max(v0, v1);
        return ((long) min << 32) | (max & 0xFFFFFFFFL);
    }

    // ── MeshGeometry (verbatim) ──────────────────────────────────────────────

    public static Vector3f computeNormal(int[] uniqueVertexIndices, int[][] uniqueToMesh, float[] vertices) {
        float normalX = 0, normalY = 0, normalZ = 0;
        int count = uniqueVertexIndices.length;

        for (int i = 0; i < count; i++) {
            int currMesh = uniqueToMesh[uniqueVertexIndices[i]][0];
            int nextMesh = uniqueToMesh[uniqueVertexIndices[(i + 1) % count]][0];

            float cx = vertices[currMesh * 3],     cy = vertices[currMesh * 3 + 1],     cz = vertices[currMesh * 3 + 2];
            float nx = vertices[nextMesh * 3],     ny = vertices[nextMesh * 3 + 1],     nz = vertices[nextMesh * 3 + 2];

            normalX += (cy - ny) * (cz + nz);
            normalY += (cz - nz) * (cx + nx);
            normalZ += (cx - nx) * (cy + ny);
        }

        Vector3f result = new Vector3f(normalX, normalY, normalZ);
        float len = result.length();
        if (len > ModelingConstants.DEGENERATE_NORMAL_EPSILON) {
            result.div(len);
        }
        return result;
    }

    public static Vector3f computeCentroid(int[] uniqueVertexIndices, int[][] uniqueToMesh, float[] vertices) {
        float cx = 0, cy = 0, cz = 0;
        int count = uniqueVertexIndices.length;

        for (int i = 0; i < count; i++) {
            int meshIdx = uniqueToMesh[uniqueVertexIndices[i]][0];
            cx += vertices[meshIdx * 3];
            cy += vertices[meshIdx * 3 + 1];
            cz += vertices[meshIdx * 3 + 2];
        }

        return new Vector3f(cx / count, cy / count, cz / count);
    }

    public static float computeArea(int[] uniqueVertexIndices, int[][] uniqueToMesh, float[] vertices) {
        float normalX = 0, normalY = 0, normalZ = 0;
        int count = uniqueVertexIndices.length;

        for (int i = 0; i < count; i++) {
            int currMesh = uniqueToMesh[uniqueVertexIndices[i]][0];
            int nextMesh = uniqueToMesh[uniqueVertexIndices[(i + 1) % count]][0];

            float cx = vertices[currMesh * 3],     cy = vertices[currMesh * 3 + 1],     cz = vertices[currMesh * 3 + 2];
            float nx = vertices[nextMesh * 3],     ny = vertices[nextMesh * 3 + 1],     nz = vertices[nextMesh * 3 + 2];

            normalX += (cy - ny) * (cz + nz);
            normalY += (cz - nz) * (cx + nx);
            normalZ += (cx - nx) * (cy + ny);
        }

        return 0.5f * (float) Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
    }

    public static float computeDihedralAngle(Vector3f n0, Vector3f n1) {
        float dot = Math.clamp(n0.dot(n1), -1.0f, 1.0f);
        return (float) Math.acos(dot);
    }
}
