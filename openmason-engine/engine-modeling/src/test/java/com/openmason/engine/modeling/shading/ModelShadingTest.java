package com.openmason.engine.modeling.shading;

import com.openmason.engine.format.omo.CendaNormals;
import com.openmason.engine.format.omo.CendaShadingStore;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.editable.EditableMeshSnapshot;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.modeling.parts.PartTransform;
import com.openmason.engine.rendering.model.draw.ModelNormalGlsl;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The editor's smooth shading wiring (#169): per-part native stores keyed to the editable model, corner normals per
 * rebuild with the runtime's FLAT rule, keys following drags / part transforms, reconcile after topology edits, undo
 * snapshots and the OMO 1.9 saved-layout round trip. GL-free; the shading rules themselves are native (ctest).
 */
class ModelShadingTest {

    @BeforeAll
    static void needsNative() {
        assumeTrue(CendaShadingStore.isAvailable(), "Cenda shading store unavailable: "
                + CendaShadingStore.unavailableReason());
    }

    /** Unit cube soup (24 corners, 6 quads), optionally offset along x. */
    static OMOFormat.MeshData cube(float dx) {
        float h = 0.5f;
        float[] v = {
            -h, -h,  h,   h, -h,  h,   h,  h,  h,  -h,  h,  h,
             h, -h, -h,  -h, -h, -h,  -h,  h, -h,   h,  h, -h,
            -h, -h, -h,  -h, -h,  h,  -h,  h,  h,  -h,  h, -h,
             h, -h,  h,   h, -h, -h,   h,  h, -h,   h,  h,  h,
            -h,  h,  h,   h,  h,  h,   h,  h, -h,  -h,  h, -h,
            -h, -h, -h,   h, -h, -h,   h, -h,  h,  -h, -h,  h,
        };
        for (int i = 0; i < v.length; i += 3) v[i] += dx;
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

    private static EditableModel cubeModel() {
        EditableModel model = new EditableModel();
        model.loadMeshDataAsPart(cube(0f), "Root");
        return model;
    }

    /** co_mesh_normals over the model's current render mesh (one group, every face smooth unless given). */
    private static short[] nativeNormals(EditableModel model, int[] smoothWords, int[] sharpPairs, float auto) {
        return CendaNormals.meshNormals(model.getAllMeshVertexPositions(), model.getIndices(),
                model.getTriangleToFaceMapping(), null, smoothWords, sharpPairs, auto, null, null, 0f).oct();
    }

    @Test
    void aModelWithoutShadingHasNoNormalsSoItDrawsFlatAsBefore() {
        EditableModel model = cubeModel();
        assertNull(model.getCornerNormals());
        assertFalse(model.getShading().hasShading());
    }

    @Test
    void shadeSmoothGivesTheNativeCornerNormals() {
        EditableModel model = cubeModel();
        model.getShading().setSmoothAll(null, true);
        short[] normals = model.getCornerNormals();
        assertNotNull(normals);
        assertArrayEquals(nativeNormals(model, null, null, 0f), normals);
        // A smooth cube corner points along the diagonal.
        float[] n = CendaNormals.octDecode(new short[] {normals[0], normals[1]});
        assertEquals(1f / (float) Math.sqrt(3), Math.abs(n[0]), 1e-3f);
    }

    @Test
    void flatFacesCarryTheFlatMarker() {
        EditableModel model = cubeModel();
        model.getShading().setSmooth(new int[] {0}, true);
        short[] normals = model.getCornerNormals();
        int[] triFace = model.getTriangleToFaceMapping();
        int[] idx = model.getIndices();
        short[] expected = nativeNormals(model, new int[] {1}, null, 0f);
        for (int t = 0; t < triFace.length; t++) {
            for (int c = 0; c < 3; c++) {
                int v = idx[t * 3 + c];
                if (triFace[t] == 0) {
                    assertEquals(expected[v * 2], normals[v * 2]);
                    assertEquals(expected[v * 2 + 1], normals[v * 2 + 1]);
                } else {
                    assertEquals(ModelNormalGlsl.FLAT, normals[v * 2]);
                    assertEquals(ModelNormalGlsl.FLAT, normals[v * 2 + 1]);
                }
            }
        }
    }

    /** Face 0's two edges at corner 0 (0-1 and 3-0): with both sharp, face 0 leaves the corner's fan. */
    private static final int[] CORNER0_EDGES = {0, 1, 3, 0};

    private static void markCorner0Sharp(EditableModel model) {
        float[] p = model.getAllMeshVertexPositions();
        float[] edges = new float[12];
        for (int i = 0; i < 4; i++) System.arraycopy(p, CORNER0_EDGES[i] * 3, edges, i * 3, 3);
        model.getShading().markSharp(null, edges, true);
    }

    @Test
    void aDraggedVertexKeepsItsSharpEdges() {
        EditableModel model = cubeModel();
        ModelShading shading = model.getShading();
        shading.setSmoothAll(null, true);
        markCorner0Sharp(model);
        short[] marked = model.getCornerNormals();
        assertArrayEquals(nativeNormals(model, null, CORNER0_EDGES, 0f), marked);
        assertFalse(Arrays.equals(nativeNormals(model, null, null, 0f), marked), "the sharp edges split a fan");

        // Light drag path: positions only, then the next normals pass follows the keys.
        model.updateVertexPosition(0, new Vector3f(-0.8f, -0.7f, 0.9f));
        short[] dragged = model.getCornerNormals();
        assertArrayEquals(nativeNormals(model, null, CORNER0_EDGES, 0f), dragged);
        assertFalse(Arrays.equals(nativeNormals(model, null, null, 0f), dragged),
                "the sharp edges must still split the fan after the drag");
        assertEquals(2, shading.existingStore(shading.keys().get(0)).sharpEdgeCount());
    }

    @Test
    void aPartTransformMovesTheKeysWithThePart() {
        EditableModel model = cubeModel();
        ModelShading shading = model.getShading();
        shading.setSmoothAll(null, true);
        markCorner0Sharp(model);

        ModelPartDescriptor root = model.getPartManager().getAllParts().get(0);
        model.getPartManager().setPartTransform(root.id(), new PartTransform(new Vector3f(),
                new Vector3f(2f, 1f, 0f), new Vector3f(0f, 30f, 0f), new Vector3f(1f, 1f, 1f)));

        // Find the moved edge's corners in the re-derived mesh and compare with native normals there.
        short[] normals = model.getCornerNormals();
        CendaShadingStore store = shading.existingStore(root.id());
        float[] edge = store.sharpEdges();
        assertEquals(12, edge.length);
        float[] now = model.getAllMeshVertexPositions();
        int[] pairs = new int[4];
        for (int i = 0; i < 4; i++) {
            pairs[i] = cornerAt(now, edge, i * 3);
            assertTrue(pairs[i] >= 0, "the sharp edges must sit on the transformed cube");
        }
        assertArrayEquals(nativeNormals(model, null, pairs, 0f), normals);
        assertFalse(Arrays.equals(nativeNormals(model, null, null, 0f), normals));
    }

    private static int cornerAt(float[] positions, float[] edge, int o) {
        for (int c = 0; c < positions.length / 3; c++) {
            float dx = positions[c * 3] - edge[o], dy = positions[c * 3 + 1] - edge[o + 1];
            float dz = positions[c * 3 + 2] - edge[o + 2];
            if (dx * dx + dy * dy + dz * dz < 1e-8f) return c;
        }
        return -1;
    }

    @Test
    void aTopologyEditReconcilesTheStore() {
        EditableModel model = cubeModel();
        ModelShading shading = model.getShading();
        shading.setSmoothAll(null, true);
        assertTrue(model.deleteFace(2));
        CendaShadingStore store = shading.existingStore(shading.keys().get(0));
        assertFalse(store.isSmooth(2), "the deleted face's smooth bit is dropped");
        assertTrue(store.isSmooth(0) && store.isSmooth(5));
        assertEquals(5, shading.summary().get(0).smoothFaces());
        assertArrayEquals(nativeNormals(model, null, null, 0f), model.getCornerNormals());
    }

    @Test
    void undoSnapshotsRestoreTheShading() {
        EditableModel model = cubeModel();
        EditableMeshSnapshot before = model.captureSnapshot();
        model.getShading().setSmoothAll(null, true);
        EditableMeshSnapshot after = model.captureSnapshot();
        assertFalse(before.contentEquals(after), "a shading-only change is an undo step");

        model.restoreSnapshot(before);
        assertNull(model.getCornerNormals());
        model.restoreSnapshot(after);
        assertArrayEquals(nativeNormals(model, null, null, 0f), model.getCornerNormals());

        // Undo of a topology edit brings back the bits reconcile dropped.
        EditableMeshSnapshot smooth = model.captureSnapshot();
        assertTrue(model.deleteFace(3));
        model.restoreSnapshot(smooth);
        assertTrue(model.getShading().existingStore(model.getShading().keys().get(0)).isSmooth(3));
    }

    @Test
    void theSavedLayoutRoundTripsThroughAFreshModel() {
        EditableModel model = cubeModel();
        ModelShading shading = model.getShading();
        shading.setSmooth(new int[] {0, 1, 4}, true);
        shading.setAutoSmooth(null, new int[] {5}, 40f);
        float[] p = model.getAllMeshVertexPositions();
        shading.markSharp(null, new float[] {p[0], p[1], p[2], p[3], p[4], p[5]}, true);
        short[] normals = model.getCornerNormals();

        OMOFormat.MeshData saved = model.toMeshData();
        OMOFormat.ShadingData data = shading.toSavedLayout(saved, null);
        assertNotNull(data);
        assertEquals(40f, data.autoSmoothAngle());
        assertEquals(1, data.sharpEdges().length / 2);

        EditableModel loaded = new EditableModel();
        loaded.loadMeshDataAsPart(saved, "Root");
        loaded.getShading().load(saved, null, data,
                loaded.getPartManager().getAllParts().get(0).id());
        assertArrayEquals(normals, loaded.getCornerNormals());
        assertEquals(data, loaded.getShading().toSavedLayout(loaded.toMeshData(), null));
    }

    @Test
    void partsShadeApartLikeTheRuntime() {
        EditableModel model = new EditableModel();
        OMOFormat.MeshData a = cube(0f);
        OMOFormat.MeshData b = cube(1f); // shares the x = 0.5 face positions with a
        model.getPartManager().addPartFromGeometry("A", PartMeshRebuilder.PartGeometry.of(a.vertices(),
                a.texCoords(), a.indices(), a.triangleToFaceId()), null);
        model.getPartManager().addPartFromGeometry("B", PartMeshRebuilder.PartGeometry.of(b.vertices(),
                b.texCoords(), b.indices(), b.triangleToFaceId()), null);
        ModelShading shading = model.getShading();
        shading.setSmoothAll(null, true);
        assertEquals(2, shading.keys().size());

        int[] triFace = model.getTriangleToFaceMapping();
        int[] groups = new int[triFace.length];
        List<ModelPartDescriptor> parts = model.getPartManager().getAllParts();
        for (int t = 0; t < groups.length; t++) {
            groups[t] = triFace[t] >= parts.get(1).meshRange().faceStart() ? 1 : 0;
        }
        short[] expected = CendaNormals.meshNormals(model.getAllMeshVertexPositions(), model.getIndices(), triFace,
                groups, null, null, 0f, null, null, 0f).oct();
        assertArrayEquals(expected, model.getCornerNormals());

        // Saved with part entries and reloaded part by part.
        OMOFormat.MeshData saved = model.toMeshData();
        List<OMOFormat.PartEntry> entries = parts.stream().map(p -> new OMOFormat.PartEntry(p.id(), p.name(),
                0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, p.meshRange().vertexStart(), p.meshRange().vertexCount(),
                p.meshRange().indexStart(), p.meshRange().indexCount(), p.meshRange().faceStart(),
                p.meshRange().faceCount(), true, false, null, null)).toList();
        OMOFormat.ShadingData data = shading.toSavedLayout(saved, entries);
        shading.load(saved, entries, data, null);
        assertArrayEquals(expected, model.getCornerNormals());
    }

    @Test
    void clearingCustomNormalsAndSharpEdgesIsUndoable() {
        EditableModel model = cubeModel();
        ModelShading shading = model.getShading();
        shading.setSmoothAll(null, true);
        float[] p = model.getAllMeshVertexPositions();
        shading.markSharp(null, new float[] {p[0], p[1], p[2], p[3], p[4], p[5]}, true);
        EditableMeshSnapshot marked = model.captureSnapshot();
        shading.clearAllSharp(null);
        assertEquals(0, shading.summary().get(0).sharpEdges());
        model.restoreSnapshot(marked);
        assertEquals(1, shading.summary().get(0).sharpEdges());
    }
}
