package com.openmason.main.systems.mcp;

import com.openmason.engine.format.omo.CendaShadingStore;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.EditableModel;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import com.openmason.main.systems.services.commands.RendererSynchronizer;
import com.openmason.main.systems.threading.MainThreadExecutor;
import com.openmason.main.systems.viewport.state.EdgeSelectionState;
import com.openmason.main.systems.viewport.state.FaceSelectionState;
import com.openmason.main.systems.viewport.state.VertexSelectionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code shading_*} MCP tools (#169) over a one-part cube: Shade Smooth / Flat / Auto, sharp edges by part-local
 * vertex pairs, the summary, and that every change is one undo step of the model history (snapshots carry the
 * shading).
 */
class ShadingEditingServiceTest {

    private EditableModel model;
    private ModelCommandHistory history;
    private ShadingEditingService service;
    private String part;

    @BeforeEach
    void setUp() {
        assumeTrue(CendaShadingStore.isAvailable(), "native shading: " + CendaShadingStore.unavailableReason());
        MainThreadExecutor.bindToCurrentThread();
        model = new EditableModel();
        model.loadMeshDataAsPart(cube(), "Body");
        part = model.getPartManager().getAllParts().get(0).id();
        history = new ModelCommandHistory();
        RendererSynchronizer sync = new RendererSynchronizer(model, null, new VertexSelectionState(),
                new EdgeSelectionState(), new FaceSelectionState());
        service = new ShadingEditingService(() -> new ShadingEditingService.Context(model, model.getPartManager(),
                history, sync));
    }

    private static OMOFormat.MeshData cube() {
        float h = 0.5f;
        float[] v = {
            -h, -h,  h,   h, -h,  h,   h,  h,  h,  -h,  h,  h,
             h, -h, -h,  -h, -h, -h,  -h,  h, -h,   h,  h, -h,
            -h, -h, -h,  -h, -h,  h,  -h,  h,  h,  -h,  h, -h,
             h, -h,  h,   h, -h, -h,   h,  h, -h,   h,  h,  h,
            -h,  h,  h,   h,  h,  h,   h,  h, -h,  -h,  h, -h,
            -h, -h, -h,   h, -h, -h,   h, -h,  h,  -h, -h,  h,
        };
        int[] idx = new int[36];
        int[] faceIds = new int[12];
        for (int f = 0; f < 6; f++) {
            int b = f * 4;
            System.arraycopy(new int[] {b, b + 1, b + 2, b, b + 2, b + 3}, 0, idx, f * 6, 6);
            faceIds[f * 2] = f;
            faceIds[f * 2 + 1] = f;
        }
        return new OMOFormat.MeshData(v, new float[48], idx, faceIds, "FLAT");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Map<String, Object> summary) {
        List<Map<String, Object>> parts = (List<Map<String, Object>>) summary.get("parts");
        assertEquals(1, parts.size());
        return parts.get(0);
    }

    @Test
    void shadeSmoothThenUndo() {
        Map<String, Object> after = service.set("Body", null, "smooth", null);
        assertEquals(6, row(after).get("smooth_faces"));
        assertNotNull(model.getCornerNormals());
        assertTrue(history.canUndo());

        history.undo();
        assertEquals(0, row(service.get("Body")).get("smooth_faces"));
        assertNull(model.getCornerNormals());
        history.redo();
        assertEquals(6, row(service.get(null)).get("smooth_faces"));
    }

    @Test
    void facesArePartLocalAndFlatUndoesSmooth() {
        service.set("Body", new int[] {0, 2}, "smooth", null);
        Map<String, Object> r = row(service.get("Body"));
        assertEquals(2, r.get("smooth_faces"));
        assertEquals(List.of(0, 2), r.get("smooth_face_ids"));
        service.set("Body", new int[] {2}, "flat", null);
        assertEquals(1, row(service.get("Body")).get("smooth_faces"));
        assertThrows(IllegalArgumentException.class, () -> service.set("Body", new int[] {6}, "smooth", null));
        assertThrows(IllegalArgumentException.class, () -> service.set(null, new int[] {0}, "smooth", null));
    }

    @Test
    void autoSmoothNeedsAnAngle() {
        assertThrows(IllegalArgumentException.class, () -> service.set("Body", null, "auto", null));
        Map<String, Object> r = row(service.set("Body", null, "auto", 30f));
        assertEquals(30f, r.get("auto_smooth"));
        assertEquals(6, r.get("smooth_faces"));
        assertThrows(IllegalArgumentException.class, () -> service.set("Body", null, "rough", null));
    }

    @Test
    void sharpEdgesByPartVertexPairs() {
        service.set("Body", null, "smooth", null);
        // Corner 0's two edges on face 0 (part-local vertices = the cube soup's).
        Map<String, Object> r = row(service.sharp("Body", List.of(new int[] {0, 1}, new int[] {3, 0}), true));
        assertEquals(2, r.get("sharp_edges"));
        short[] marked = model.getCornerNormals();

        history.undo();
        assertEquals(0, row(service.get("Body")).get("sharp_edges"));
        history.redo();
        assertEquals(2, row(service.get("Body")).get("sharp_edges"));
        assertTrue(java.util.Arrays.equals(marked, model.getCornerNormals()));

        assertEquals(1, row(service.sharp("Body", List.of(new int[] {0, 1}), false)).get("sharp_edges"));
        assertEquals(0, row(service.sharp(null, null, false)).get("sharp_edges"));
        assertThrows(IllegalArgumentException.class, () -> service.sharp("Body", null, true));
        assertThrows(IllegalArgumentException.class, () -> service.sharp("Body", List.of(new int[] {0, 99}), true));
    }

    @Test
    void anUnknownPartTeachesTheNames() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.set("Nope", null, "smooth", null));
        assertTrue(e.getMessage().contains("Body"));
        assertFalse(history.canUndo());
    }

    @Test
    void aNoOpRecordsNothing() {
        service.set("Body", null, "flat", null); // already flat
        assertFalse(history.canUndo());
        assertEquals(part, model.getPartManager().getAllParts().get(0).id());
    }
}
