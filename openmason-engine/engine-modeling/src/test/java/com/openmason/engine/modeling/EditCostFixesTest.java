package com.openmason.engine.modeling;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.core.EditCostStats;
import com.openmason.engine.modeling.core.MeshGpuState;
import com.openmason.engine.modeling.core.PointGrid;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.modeling.parts.PartTransform;
import com.openmason.engine.modeling.topology.MeshTopology;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #175 F1, GL-free: the positions-only drag path (batched move + one commit) ends where the old full path ended,
 * listeners get one batched call per move, a position refresh keeps the draw batches when the triangles did not
 * change, a deferred load derives once, the batched part sync equals the one-by-one sync, and the point grid
 * equals a brute-force scan.
 */
class EditCostFixesTest {

    @AfterEach
    void stats() {
        EditCostStats.setEnabled(false);
        EditCostStats.reset();
    }

    /** A grid of quads (w x h), one face per quad, 4 own corners per quad (an OMO soup). */
    private static PartMeshRebuilder.PartGeometry grid(int w, int h, float x0) {
        float[] v = new float[w * h * 12];
        int[] idx = new int[w * h * 6];
        int[] faces = new int[w * h * 2];
        int q = 0;
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                float[][] c = {{i, j}, {i + 1, j}, {i + 1, j + 1}, {i, j + 1}};
                for (int k = 0; k < 4; k++) {
                    v[q * 12 + k * 3] = x0 + c[k][0];
                    v[q * 12 + k * 3 + 1] = c[k][1];
                    v[q * 12 + k * 3 + 2] = 0.1f * (float) Math.sin(c[k][0] + c[k][1]);
                }
                int b = q * 4;
                System.arraycopy(new int[] {b, b + 1, b + 2, b, b + 2, b + 3}, 0, idx, q * 6, 6);
                faces[q * 2] = q;
                faces[q * 2 + 1] = q;
                q++;
            }
        }
        return PartMeshRebuilder.PartGeometry.of(v, new float[w * h * 8], idx, faces);
    }

    private static OMOFormat.MeshData gridMesh(int w, int h) {
        PartMeshRebuilder.PartGeometry g = grid(w, h, 0f);
        return new OMOFormat.MeshData(g.vertices(), g.texCoords(), g.indices(), g.triangleToFaceId(), "FLAT");
    }

    private static EditableModel model(int w, int h) {
        EditableModel m = new EditableModel();
        m.loadMeshDataAsPart(gridMesh(w, h), "Root");
        return m;
    }

    @Test
    void batchedMoveThenCommitEqualsTheOldFullPath() {
        EditableModel fast = model(6, 5);
        EditableModel full = model(6, 5);
        int[] ids = {0, 3, 7, 12};
        float[] positions = {0.2f, 0.1f, 0.5f, 1.5f, -0.2f, 0.3f, 2.2f, 1.9f, -0.4f, 0.7f, 3.1f, 0.25f};

        // New path: positions-only move + one commit
        fast.moveVertices(ids, positions);
        fast.commitVertexPositions();

        // Old path: corner-parallel bulk array through updateVertexPositions (a full derive)
        float[] corners = full.getAllMeshVertexPositions();
        for (int i = 0; i < ids.length; i++) {
            for (int c : full.getMeshIndicesForUniqueVertex(ids[i])) {
                System.arraycopy(positions, i * 3, corners, c * 3, 3);
            }
        }
        full.updateVertexPositions(corners);

        assertArrayEquals(full.getAllMeshVertexPositions(), fast.getAllMeshVertexPositions());
        assertArrayEquals(full.getTexCoords(), fast.getTexCoords());
        assertArrayEquals(full.getIndices(), fast.getIndices());
        assertArrayEquals(full.getTriangleToFaceMapping(), fast.getTriangleToFaceMapping());
        assertArrayEquals(full.getAllUniqueVertexPositions(), fast.getAllUniqueVertexPositions());
        MeshTopology a = full.getTopology();
        MeshTopology b = fast.getTopology();
        assertEquals(a.getEdgeCount(), b.getEdgeCount());
        assertEquals(a.getFaceCount(), b.getFaceCount());
        for (int f = 0; f < a.getFaceCount(); f++) {
            assertEquals(a.getFaceNormal(f), b.getFaceNormal(f), "face " + f);
        }
    }

    @Test
    void aBatchedMoveNotifiesOnceAndMovesEveryCorner() {
        EditableModel m = model(4, 4);
        List<int[]> calls = new ArrayList<>();
        int[] single = {0};
        m.addMeshChangeListener(new MeshChangeListener() {
            @Override
            public void onVertexPositionChanged(int uniqueIndex, Vector3f newPosition, int[] affected) {
                single[0]++;
            }

            @Override
            public void onVertexPositionsChanged(int[] ids, float[] positions, int[][] affected) {
                calls.add(ids.clone());
                for (int i = 0; i < ids.length; i++) {
                    assertArrayEquals(m.getMeshIndicesForUniqueVertex(ids[i]), affected[i]);
                }
            }

            @Override
            public void onGeometryRebuilt() {
            }
        });
        MeshGpuState gpu = m.getGpuState();
        gpu.clear();
        int[] ids = {1, 5, 6};
        float[] p = {9, 8, 7, 6, 5, 4, 3, 2, 1};
        m.moveVertices(ids, p);

        assertEquals(1, calls.size(), "one batched notification per move");
        assertEquals(0, single[0], "no per-vertex notifications");
        assertArrayEquals(ids, calls.get(0));
        for (int i = 0; i < ids.length; i++) {
            for (int c : m.getMeshIndicesForUniqueVertex(ids[i])) {
                assertEquals(new Vector3f(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]), m.getVertexPosition(c));
            }
        }
        assertTrue(gpu.positionsDirty());
        assertFalse(gpu.uvsDirty(), "a drag frame must not re-send UVs");
        assertFalse(gpu.indicesDirty(), "a drag frame must not re-send indices");
    }

    @Test
    void aPositionRefreshKeepsTheDrawBatchesWhenTheTrianglesDidNotChange() {
        EditableModel m = model(3, 3);
        m.markDrawBatchesDirty();
        // the draw plan is rebuilt by the next GPU sync; emulate it having run
        clearBatches(m);
        m.moveVertices(new int[] {4}, new float[] {1.1f, 1.2f, 0.05f});
        m.commitVertexPositions();
        assertFalse(isBatchesDirty(m), "same triangles: the material-sorted index buffer stays valid");

        assertTrue(m.deleteFace(0));
        assertTrue(isBatchesDirty(m), "a structural change re-sorts");
    }

    private static void clearBatches(EditableModel m) {
        pipeline(m).clearDrawBatchesDirty();
    }

    private static boolean isBatchesDirty(EditableModel m) {
        return pipeline(m).isDrawBatchesDirty();
    }

    private static com.openmason.engine.modeling.core.MeshRebuildPipeline pipeline(EditableModel m) {
        try {
            var f = EditableModel.class.getDeclaredField("rebuildPipeline");
            f.setAccessible(true);
            return (com.openmason.engine.modeling.core.MeshRebuildPipeline) f.get(m);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aDeferredLoadDerivesOnce() {
        EditableModel m = new EditableModel();
        List<ModelPartDescriptor> parts = new ArrayList<>();
        Map<String, PartMeshRebuilder.PartGeometry> geometry = new LinkedHashMap<>();
        for (int p = 0; p < 3; p++) {
            parts.add(new ModelPartDescriptor("p" + p, "P" + p, PartTransform.identity(), null, true, false, null));
            geometry.put("p" + p, grid(4, 3, p * 6f));
        }
        EditCostStats.reset();
        EditCostStats.setEnabled(true);
        m.beginDeferredRebuild();
        try {
            m.getPartManager().replaceAllParts(parts, geometry);
            for (int f = 0; f < 36; f++) m.getFaceTextureManager().assignDefaultMapping(f, 0);
            m.adoptAuthoredUVs();
            m.markDrawBatchesDirty();
            m.refreshUVs();
            assertEquals(0, EditCostStats.totalCalls(EditCostStats.Span.DERIVE), "nothing derived inside");
        } finally {
            m.endDeferredRebuild();
        }
        assertEquals(1, EditCostStats.totalCalls(EditCostStats.Span.REBUILD_COMBINED));
        assertEquals(1, EditCostStats.totalCalls(EditCostStats.Span.IMPORT_SOUP));
        assertEquals(1, EditCostStats.totalCalls(EditCostStats.Span.DERIVE), "one derivation for the load");
        assertEquals(36 * 2, m.getTriangleCount());

        // Same result as the undeferred sequence
        EditableModel plain = new EditableModel();
        plain.getPartManager().replaceAllParts(parts, geometry);
        for (int f = 0; f < 36; f++) plain.getFaceTextureManager().assignDefaultMapping(f, 0);
        plain.refreshUVs();
        assertArrayEquals(plain.getAllMeshVertexPositions(), m.getAllMeshVertexPositions());
        assertArrayEquals(plain.getTexCoords(), m.getTexCoords());
        assertArrayEquals(plain.getIndices(), m.getIndices());
    }

    @Test
    void theBatchedPartSyncEqualsTheOneByOneSync() {
        ModelPartManager one = twoParts();
        ModelPartManager batch = twoParts();
        // combined (= world, identity transforms but part 1 moved) indices and new positions
        int[] corners = {0, 1, 5, 30, 33, 47};
        float[] positions = new float[corners.length * 3];
        Random r = new Random(7);
        for (int i = 0; i < positions.length; i++) positions[i] = r.nextFloat() * 4f;
        for (int i = 0; i < corners.length; i++) {
            one.syncPartVertexFromWorldPos(corners[i],
                    new Vector3f(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]));
        }
        batch.syncPartVerticesFromWorldPos(corners, positions);
        for (String id : List.of("a", "b")) {
            assertArrayEquals(one.getPartGeometry(id).vertices(), batch.getPartGeometry(id).vertices(), id);
        }
    }

    private static ModelPartManager twoParts() {
        ModelPartManager pm = new ModelPartManager();
        List<ModelPartDescriptor> parts = List.of(
                new ModelPartDescriptor("a", "A", PartTransform.identity(), null, true, false, null),
                new ModelPartDescriptor("b", "B",
                        PartTransform.identity().withTranslation(new Vector3f(0, 2, 0)), null, true, false, null));
        Map<String, PartMeshRebuilder.PartGeometry> geometry = new LinkedHashMap<>();
        geometry.put("a", copy(grid(2, 2, 0f)));
        geometry.put("b", copy(grid(2, 2, 5f)));
        pm.replaceAllParts(parts, geometry);
        return pm;
    }

    private static PartMeshRebuilder.PartGeometry copy(PartMeshRebuilder.PartGeometry g) {
        return PartMeshRebuilder.PartGeometry.of(g.vertices().clone(), g.texCoords().clone(), g.indices().clone(),
                g.triangleToFaceId().clone());
    }

    @Test
    void thePointGridFindsWhatABruteForceScanFinds() {
        Random r = new Random(11);
        float[] xyz = new float[3000];
        for (int i = 0; i < xyz.length; i++) xyz[i] = Math.round(r.nextFloat() * 40f) * 0.05f;
        float radius = 0.06f;
        PointGrid grid = new PointGrid(xyz, null, radius);
        for (int q = 0; q < 200; q++) {
            float x = r.nextFloat() * 2f, y = r.nextFloat() * 2f, z = r.nextFloat() * 2f;
            boolean[] hit = new boolean[1000];
            grid.query(x, y, z, radius * radius, (i, d) -> hit[i] = true);
            for (int i = 0; i < 1000; i++) {
                float dx = xyz[i * 3] - x, dy = xyz[i * 3 + 1] - y, dz = xyz[i * 3 + 2] - z;
                assertEquals(dx * dx + dy * dy + dz * dz <= radius * radius, hit[i], "point " + i);
            }
        }
    }
}
