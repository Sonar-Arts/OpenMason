package com.openmason.main.systems.viewport.viewportRendering.common;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.ModelingConstants;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #175 F1 drag release: the drag-onto-vertex merge finds its target through a point grid built once (no scan of
 * every vertex per dragged vertex) and picks what the old scan picked: the NEAREST live vertex within
 * {@link ModelingConstants#POSITION_MATCH_EPSILON}. GL-free.
 */
class DragReleaseMergeTest {

    /** Two quads side by side in a strip (x 0..2), 4 own corners each. */
    private static EditableModel strip() {
        float[] v = {
            0, 0, 0,  1, 0, 0,  1, 1, 0,  0, 1, 0,
            1, 0, 0,  2, 0, 0,  2, 1, 0,  1, 1, 0,
        };
        int[] idx = {0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7};
        int[] faces = {0, 0, 1, 1};
        EditableModel m = new EditableModel();
        m.loadMeshDataAsPart(new OMOFormat.MeshData(v, new float[16], idx, faces, "FLAT"), "Root");
        return m;
    }

    private static int vertexAt(EditableModel m, float x, float y) {
        float[] p = m.getAllUniqueVertexPositions();
        for (int i = 0; i < p.length / 3; i++) {
            if (Math.abs(p[i * 3] - x) < 1e-6f && Math.abs(p[i * 3 + 1] - y) < 1e-6f) return i;
        }
        throw new AssertionError("no vertex at " + x + "," + y);
    }

    @Test
    void aVertexDraggedNextToTwoMergesIntoTheNearest() {
        EditableModel m = strip();
        int dragged = vertexAt(m, 2, 1);
        int near = vertexAt(m, 2, 0);
        int corners = m.getMeshIndicesForUniqueVertex(near).length;

        // Within epsilon of (2,0) only; the old scan and the grid both pick it
        float e = ModelingConstants.POSITION_MATCH_EPSILON * 0.3f;
        m.moveVertices(new int[] {dragged}, new float[] {2f + e, 0f, 0f});
        m.commitVertexPositions();
        assertTrue(TranslationHandlerBase.mergeCoincidentDraggedVertices(m, Set.of(dragged)));

        assertTrue(m.getMeshIndicesForUniqueVertex(near).length >= corners, "kept vertex takes the faces");
        int[] gone = m.getMeshIndicesForUniqueVertex(dragged);
        assertEquals(0, gone == null ? 0 : gone.length, "the dragged vertex is merged away");
    }

    @Test
    void nothingWithinEpsilonMergesNothing() {
        EditableModel m = strip();
        int dragged = vertexAt(m, 2, 1);
        m.moveVertices(new int[] {dragged}, new float[] {2.5f, 0.5f, 0f});
        m.commitVertexPositions();
        assertFalse(TranslationHandlerBase.mergeCoincidentDraggedVertices(m, Set.of(dragged)));
    }
}
