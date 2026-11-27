package com.openmason.main.systems.rendering.model.editing.picking;

import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.topology.MeshEdge;
import com.openmason.engine.modeling.topology.MeshFace;
import com.openmason.engine.modeling.topology.MeshTopology;

import java.util.Arrays;

/**
 * GL-free per-corner lookup the ID pass reads with {@code gl_VertexID} (#175 F4). Render corners are never shared
 * across faces, so every corner names exactly one face; one {@code RGBA32UI} texel per corner:
 * <ul>
 *   <li>R - the face's FIRST triangle in the editor's triangle order (what {@code FaceRenderer} maps to its face);</li>
 *   <li>G - the corner's welded (unique) vertex id, the vertex overlay's index;</li>
 *   <li>B - the next corner of its face loop ({@link #NONE} if unknown), so a drawn triangle side is a mesh edge
 *       exactly when one corner's next is the other - triangulation diagonals are skipped;</li>
 *   <li>A - that loop edge's index in the edge overlay's list ({@code EdgeExtractor}'s per-face order), the LOWEST
 *       index of the edge's duplicates (one per adjacent face) - the one the CPU detector returns.</li>
 * </ul>
 * The ids live in the editor's own index spaces, so the pass works over any index order the GPU mesh draws (the
 * material-sorted buffer, a mask plan's permutation): only {@code gl_VertexID} is used.
 */
public final class PickCornerTable {

    /** Marks "no id" in a texel. */
    public static final int NONE = -1;

    private final int[] texels;
    private final int cornerCount;
    private final int triangleCount;
    private final int uniqueVertexCount;
    private final int edgeRecordCount;

    private PickCornerTable(int[] texels, int cornerCount, int triangleCount, int uniqueVertexCount,
                            int edgeRecordCount) {
        this.texels = texels;
        this.cornerCount = cornerCount;
        this.triangleCount = triangleCount;
        this.uniqueVertexCount = uniqueVertexCount;
        this.edgeRecordCount = edgeRecordCount;
    }

    /** RGBA texels, 4 ints per corner. */
    public int[] texels() {
        return texels;
    }

    public int cornerCount() {
        return cornerCount;
    }

    /** Triangles of the editor's triangle list (the face overlay's count). */
    public int triangleCount() {
        return triangleCount;
    }

    /** Welded vertices (the vertex overlay's count). */
    public int uniqueVertexCount() {
        return uniqueVertexCount;
    }

    /** Loop edges in the edge overlay's list (one per face corner). */
    public int edgeRecordCount() {
        return edgeRecordCount;
    }

    /** Builds the table of {@code model}'s current derivation; null when it has no topology yet. */
    public static PickCornerTable build(EditableModel model) {
        MeshTopology topology = model.getTopology();
        int[] indices = model.triangleIndicesView();
        float[] positions = model.meshVertexPositionsView();
        if (topology == null || indices == null || positions == null) {
            return null;
        }
        int corners = positions.length / 3;
        int tris = indices.length / 3;

        // Face per triangle, first triangle per face, face per corner (first triangle that uses it).
        int[] triFace = new int[tris];
        int maxFace = -1;
        for (int t = 0; t < tris; t++) {
            int f = topology.getOriginalFaceIdForTriangle(t);
            triFace[t] = f;
            if (f > maxFace) maxFace = f;
        }
        int faceSpan = Math.max(maxFace + 1, topology.getFaceCount());
        int[] faceFirstTri = new int[faceSpan];
        Arrays.fill(faceFirstTri, NONE);
        int[] cornerFace = new int[corners];
        Arrays.fill(cornerFace, NONE);
        for (int t = 0; t < tris; t++) {
            int f = triFace[t];
            if (f < 0) continue;
            if (faceFirstTri[f] == NONE) faceFirstTri[f] = t;
            for (int k = 0; k < 3; k++) {
                int c = indices[t * 3 + k];
                if (c >= 0 && c < corners && cornerFace[c] == NONE) cornerFace[c] = f;
            }
        }

        int[] texels = new int[corners * 4];
        Arrays.fill(texels, NONE);
        for (int c = 0; c < corners; c++) {
            int f = cornerFace[c];
            texels[c * 4] = f >= 0 ? faceFirstTri[f] : NONE;
            int u = topology.getUniqueIndexForMeshVertex(c);
            texels[c * 4 + 1] = u >= 0 ? u : NONE;
        }

        // Corners per face (CSR), so a loop vertex finds its corner among the face's few own corners
        int[] faceStart = new int[faceSpan + 1];
        for (int c = 0; c < corners; c++) {
            if (cornerFace[c] >= 0) faceStart[cornerFace[c] + 1]++;
        }
        for (int f = 0; f < faceSpan; f++) faceStart[f + 1] += faceStart[f];
        int[] faceCorners = new int[faceStart[faceSpan]];
        int[] cursor = Arrays.copyOf(faceStart, faceSpan);
        for (int c = 0; c < corners; c++) {
            if (cornerFace[c] >= 0) faceCorners[cursor[cornerFace[c]]++] = c;
        }

        // Loop edges in EdgeExtractor's order: per face id, one record per loop corner. Duplicates of one edge
        // collapse onto its first record.
        int[] firstRecordOfEdge = new int[Math.max(0, topology.getEdgeCount())];
        Arrays.fill(firstRecordOfEdge, NONE);
        int record = 0;
        for (int f = 0; f < topology.getFaceCount(); f++) {
            MeshFace face = topology.getFace(f);
            if (face == null || face.vertexCount() == 0) continue;
            int[] loop = face.vertexIndices();
            int[] edgeIds = face.edgeIds();
            int n = loop.length;
            for (int k = 0; k < n; k++, record++) {
                int u1 = loop[k];
                int u2 = loop[(k + 1) % n];
                int edge = edgeIdOf(topology, edgeIds, k, u1, u2);
                int canonical = record;
                if (edge >= 0 && edge < firstRecordOfEdge.length) {
                    if (firstRecordOfEdge[edge] == NONE) firstRecordOfEdge[edge] = record;
                    canonical = firstRecordOfEdge[edge];
                }
                int c1 = cornerOf(texels, faceCorners, faceStart, f, u1);
                int c2 = cornerOf(texels, faceCorners, faceStart, f, u2);
                if (c1 >= 0 && c2 >= 0) {
                    texels[c1 * 4 + 2] = c2;
                    texels[c1 * 4 + 3] = canonical;
                }
            }
        }
        return new PickCornerTable(texels, corners, tris, model.getUniqueVertexCount(), record);
    }

    /** The topology edge of loop side {@code k} (u1 -> u2): the face's own edge id when it matches, else a lookup. */
    private static int edgeIdOf(MeshTopology topology, int[] edgeIds, int k, int u1, int u2) {
        if (edgeIds != null && k < edgeIds.length) {
            MeshEdge e = topology.getEdge(edgeIds[k]);
            if (e != null && e.vertexA() == Math.min(u1, u2) && e.vertexB() == Math.max(u1, u2)) {
                return edgeIds[k];
            }
        }
        MeshEdge e = topology.getEdgeByVertices(u1, u2);
        return e != null ? e.edgeId() : NONE;
    }

    /** The corner of face {@code f} that carries welded vertex {@code u}, or -1. */
    private static int cornerOf(int[] texels, int[] faceCorners, int[] faceStart, int f, int u) {
        if (f + 1 >= faceStart.length) return NONE;
        for (int i = faceStart[f]; i < faceStart[f + 1]; i++) {
            int c = faceCorners[i];
            if (texels[c * 4 + 1] == u) return c;
        }
        return NONE;
    }
}
