package com.openmason.engine.modeling;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.core.MeshGpuState;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #90 edit cost: the model only reports what went stale and the GPU copy syncs at
 * draw time, so an interactive drag re-sends positions alone (no UVs, no index
 * buffer) and any number of drags between two frames upload once. GL-free.
 */
class EditableModelGpuSyncTest {

    private EditableModel model;
    private MeshGpuState gpu;

    /** Unit cube soup: 24 corners, 12 triangles, 6 quads. */
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
        float[] uv = new float[24 * 2];
        int[] idx = new int[36];
        int[] faceIds = new int[12];
        for (int f = 0; f < 6; f++) {
            int b = f * 4;
            int[] q = {b, b + 1, b + 2, b, b + 2, b + 3};
            System.arraycopy(q, 0, idx, f * 6, 6);
            faceIds[f * 2] = f;
            faceIds[f * 2 + 1] = f;
        }
        return new OMOFormat.MeshData(v, uv, idx, faceIds, "FLAT");
    }

    @BeforeEach
    void setUp() {
        model = new EditableModel();
        model.loadMeshData(cube());
        gpu = model.getGpuState();
        gpu.clear(); // as if a frame had just synced
    }

    @Test
    void draggingAVertexMarksOnlyPositionsStale() {
        model.updateVertexPosition(0, new Vector3f(-1f, -1f, 1f));

        assertTrue(gpu.positionsDirty());
        assertFalse(gpu.uvsDirty(), "a drag must not re-send UVs");
        assertFalse(gpu.indicesDirty(), "a drag must not re-send the index buffer");
    }

    @Test
    void manyDragsBetweenFramesCoalesceIntoOneStaleState() {
        for (int i = 0; i < 50; i++) {
            model.updateVertexPosition(0, new Vector3f(-1f - i * 0.01f, -1f, 1f));
        }
        assertTrue(gpu.positionsDirty());
        assertFalse(gpu.indicesDirty());
    }

    @Test
    void bulkPositionRefreshReDerivesUvsButKeepsTriangles() {
        model.updateVertexPositions(model.getAllMeshVertexPositions());

        assertTrue(gpu.positionsDirty());
        assertTrue(gpu.uvsDirty(), "projected UVs follow positions on a refresh");
        assertFalse(gpu.indicesDirty());
    }

    @Test
    void topologyChangeMarksTheWholeMeshStale() {
        assertTrue(model.deleteFace(0));

        assertTrue(gpu.positionsDirty());
        assertTrue(gpu.uvsDirty());
        assertTrue(gpu.indicesDirty());
    }

    @Test
    void editsWorkWithoutAnyGlInitialization() {
        assertFalse(model.isInitialized(), "no GL in this test");
        assertTrue(model.deleteFace(1), "mesh operations no longer wait for a GPU");
        assertTrue(model.getUploadedGpuBytes() == 0L);
    }
}
