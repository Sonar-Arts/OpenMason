package com.openmason.main.systems.rendering.model.editing.picking;

import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenMason #175 F4, GL-free: the per-corner table the ID pass reads names the overlays' own ids - the face overlay's
 * first triangle of the corner's face, the vertex overlay's welded vertex, and for every face-loop side the edge
 * overlay's record (the lowest of an edge's duplicates, as the CPU detector returns) - and skips triangulation
 * diagonals.
 */
class PickCornerTableTest {

    /** Two quads sharing an edge, a triangle and a pentagon fan: shared edges, diagonals, an n-gon. */
    private static EditableModel model() {
        EditableModel model = new EditableModel();
        float[] v = {
            0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0,          // quad A (corners 0..3)
            1, 0, 0, 2, 0, 0, 2, 1, 0, 1, 1, 0,          // quad B shares A's right edge (corners 4..7)
            0, 2, 0, 1, 2, 0, 0.5f, 3, 0,                // triangle (corners 8..10)
            3, 0, 0, 4, 0, 0, 4.5f, 1, 0, 3.5f, 2, 0, 2.5f, 1, 0, // pentagon (corners 11..15)
        };
        int[] idx = {0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7, 8, 9, 10, 11, 12, 13, 11, 13, 14, 11, 14, 15};
        int[] faces = {0, 0, 1, 1, 2, 3, 3, 3};
        model.getPartManager().addPartFromGeometry("p", PartMeshRebuilder.PartGeometry.of(v, new float[32], idx,
                faces), new Vector3f());
        return model;
    }

    @Test
    void cornersCarryTheOverlaysIds() {
        EditableModel model = model();
        PickCornerTable table = PickCornerTable.build(model);
        assertNotNull(table);
        int[] t = table.texels();
        int[] idx = model.getTriangleIndices();
        assertEquals(idx.length / 3, table.triangleCount());
        assertEquals(model.getUniqueVertexCount(), table.uniqueVertexCount());
        float[] edgePositions = model.extractEdgePositions();
        assertEquals(edgePositions.length / 6, table.edgeRecordCount());

        float[] corners = model.getAllMeshVertexPositions();
        float[] unique = model.getAllUniqueVertexPositions();
        for (int c = 0; c < table.cornerCount(); c++) {
            // R: the first triangle of the corner's own face
            int firstTri = t[c * 4];
            int face = faceOfCorner(model, idx, c);
            assertEquals(face, model.getOriginalFaceIdForTriangle(firstTri));
            for (int tri = 0; tri < firstTri; tri++) {
                assertTrue(model.getOriginalFaceIdForTriangle(tri) != face, "R is the face's FIRST triangle");
            }
            // G: the welded vertex at the corner's position
            int u = t[c * 4 + 1];
            for (int k = 0; k < 3; k++) assertEquals(corners[c * 3 + k], unique[u * 3 + k], 1e-6f);
            // B / A: the loop successor in the same face, and the edge record between the two
            int next = t[c * 4 + 2];
            int record = t[c * 4 + 3];
            assertTrue(next >= 0 && record >= 0, "every corner has a loop successor");
            assertEquals(face, faceOfCorner(model, idx, next));
            assertTrue(sameSegment(edgePositions, record, corners, c, next), "record " + record + " is side " + c
                    + "->" + next);
            for (int r = 0; r < record; r++) {
                assertTrue(!sameSegment(edgePositions, r, corners, c, next), "the LOWEST duplicate record");
            }
        }

        // Triangle sides that are loop sides = exactly the face's n edges; diagonals (twice per fan) never count
        int[] loopSides = new int[4];
        int[] cornersOfFace = new int[4];
        for (int c = 0; c < table.cornerCount(); c++) cornersOfFace[faceOfCorner(model, idx, c)]++;
        for (int tri = 0; tri < idx.length / 3; tri++) {
            for (int k = 0; k < 3; k++) {
                int a = idx[tri * 3 + k], b = idx[tri * 3 + (k + 1) % 3];
                if (t[a * 4 + 2] == b || t[b * 4 + 2] == a) loopSides[model.getOriginalFaceIdForTriangle(tri)]++;
            }
        }
        for (int f = 0; f < 4; f++) assertEquals(cornersOfFace[f], loopSides[f], "face " + f);
        assertEquals(5, cornersOfFace[3], "the pentagon");

        // The shared edge of quads A and B carries ONE id (A's record, the lower) on both faces' corners
        int sharedA = -1, sharedB = -1;
        for (int c = 0; c < table.cornerCount(); c++) {
            int next = t[c * 4 + 2];
            if (onSharedEdge(corners, c, next)) {
                if (faceOfCorner(model, idx, c) == 0) sharedA = t[c * 4 + 3];
                else sharedB = t[c * 4 + 3];
            }
        }
        assertTrue(sharedA >= 0);
        assertEquals(sharedA, sharedB);
    }

    private static boolean onSharedEdge(float[] p, int a, int b) {
        return p[a * 3] == 1f && p[b * 3] == 1f && p[a * 3 + 2] == 0f && p[b * 3 + 2] == 0f
                && Math.abs(p[a * 3 + 1] - p[b * 3 + 1]) == 1f;
    }

    private static int faceOfCorner(EditableModel model, int[] idx, int corner) {
        for (int i = 0; i < idx.length; i++) {
            if (idx[i] == corner) return model.getOriginalFaceIdForTriangle(i / 3);
        }
        return -1;
    }

    /** True when edge record {@code r} spans the positions of corners a and b (either direction). */
    private static boolean sameSegment(float[] edges, int r, float[] p, int a, int b) {
        boolean forward = true, backward = true;
        for (int k = 0; k < 3; k++) {
            forward &= edges[r * 6 + k] == p[a * 3 + k] && edges[r * 6 + 3 + k] == p[b * 3 + k];
            backward &= edges[r * 6 + k] == p[b * 3 + k] && edges[r * 6 + 3 + k] == p[a * 3 + k];
        }
        return forward || backward;
    }
}
