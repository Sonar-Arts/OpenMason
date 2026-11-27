package com.openmason.engine.modeling.parts;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.rig.JointPalette;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenMason #175 F2: part transforms as GPU matrices. Inside a transform interaction (a gizmo drag) a part move
 * re-bakes nothing; the palette ({@code base · W · B⁻¹}) draws it, and the release bakes exactly the vertices the
 * old per-change rebuilds ended with. GL-free (the palette math through {@link PartPalette#deform}).
 */
class PartPaletteTest {

    private static final Matrix4f BASE = new Matrix4f().translate(0.25f, -1f, 3f).rotateY(0.3f);

    /** Unit cube soup centred on {@code (cx, 0, 0)}: 24 corners, 12 triangles, 6 quads. */
    private static PartMeshRebuilder.PartGeometry cube(float cx) {
        float h = 0.5f;
        float[] v = {
            -h, -h,  h,   h, -h,  h,   h,  h,  h,  -h,  h,  h,
             h, -h, -h,  -h, -h, -h,  -h,  h, -h,   h,  h, -h,
            -h, -h, -h,  -h, -h,  h,  -h,  h,  h,  -h,  h, -h,
             h, -h,  h,   h, -h, -h,   h,  h, -h,   h,  h,  h,
            -h,  h,  h,   h,  h,  h,   h,  h, -h,  -h,  h, -h,
            -h, -h, -h,   h, -h, -h,   h, -h,  h,  -h, -h,  h,
        };
        for (int i = 0; i < v.length; i += 3) v[i] += cx;
        int[] idx = new int[36];
        int[] faceIds = new int[12];
        for (int f = 0; f < 6; f++) {
            int b = f * 4;
            System.arraycopy(new int[]{b, b + 1, b + 2, b, b + 2, b + 3}, 0, idx, f * 6, 6);
            faceIds[f * 2] = f;
            faceIds[f * 2 + 1] = f;
        }
        return PartMeshRebuilder.PartGeometry.of(v, new float[48], idx, faceIds);
    }

    /** A model of {@code n} cubes 3 units apart; returns the part ids in order. */
    private static List<String> build(EditableModel model, int n) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(model.getPartManager().addPartFromGeometry("p" + i, cube(i * 3f), new Vector3f(i * 3f, 0, 0)).id());
        }
        return ids;
    }

    private static PartTransform moved(PartTransform t, float step) {
        return new PartTransform(t.origin(), new Vector3f(step * 0.1f, step * 0.05f, -step * 0.02f),
                new Vector3f(step * 3f, step * 2f, step), new Vector3f(1f + step * 0.01f, 1f, 1f));
    }

    private static void assertSameSave(EditableModel expected, EditableModel actual) {
        OMOFormat.MeshData e = expected.toMeshData();
        OMOFormat.MeshData a = actual.toMeshData();
        assertArrayEquals(e.vertices(), a.vertices(), "saved vertices");
        assertArrayEquals(e.texCoords(), a.texCoords(), "saved UVs");
        assertArrayEquals(e.indices(), a.indices(), "saved indices");
        assertArrayEquals(e.triangleToFaceId(), a.triangleToFaceId(), "saved face ids");
    }

    @Test
    void atRestEverySlotIsExactlyTheModelMatrix() {
        EditableModel model = new EditableModel();
        build(model, 3);
        JointPalette palette = model.computePalette(BASE);
        assertEquals(4, palette.count(), "slot 0 + one per part");
        Matrix4f m = new Matrix4f();
        for (int s = 0; s < palette.count(); s++) {
            assertEquals(BASE, palette.skinMatrix(s, m), "slot " + s);
        }
        assertFalse(model.isDisplayDeformed());
        assertEquals(model.getAllMeshVertexPositions().length / 3, model.getCornerSkin().vertexCount());
    }

    @Test
    void aPartDragIsPaletteOnlyAndTheReleaseBakesTheOldPathsVertices() {
        EditableModel dragged = new EditableModel();
        EditableModel oldPath = new EditableModel();
        List<String> ids = build(dragged, 3);
        List<String> oldIds = build(oldPath, 3);
        ModelPartManager pm = dragged.getPartManager();
        String part = ids.get(1);
        PartTransform start = pm.getPartById(part).orElseThrow().transform();
        float[] bakedBefore = dragged.getAllMeshVertexPositions();
        long rebuilds = pm.getRebuildCount();

        pm.beginTransformInteraction();
        for (int step = 1; step <= 20; step++) {
            pm.setPartTransform(part, moved(start, step));
            // The old path: one full rebuild per drag update.
            oldPath.getPartManager().setPartTransform(oldIds.get(1), moved(start, step));
            assertTrue(dragged.isDisplayDeformed());
        }
        assertEquals(rebuilds, pm.getRebuildCount(), "no rebuild during the drag");
        assertArrayEquals(bakedBefore, dragged.getAllMeshVertexPositions(), "baked vertices untouched");

        // What the GPU draws == the old path's freshly baked vertices (corner order is the same).
        float[] shown = dragged.getDisplayedCornerPositions(new Matrix4f());
        float[] expected = oldPath.getAllMeshVertexPositions();
        assertArrayEquals(expected, shown, 1e-5f);

        assertTrue(pm.endTransformInteraction(), "the release re-bakes");
        assertEquals(rebuilds + 1, pm.getRebuildCount(), "exactly one rebuild for the whole drag");
        assertFalse(dragged.isDisplayDeformed());
        assertSameSave(oldPath, dragged);
        assertArrayEquals(dragged.getAllMeshVertexPositions(), dragged.getDisplayedCornerPositions(new Matrix4f()),
                "after the bake the palette is the identity again");
    }

    @Test
    void aChildFollowsItsDraggedParentThroughThePalette() {
        EditableModel dragged = new EditableModel();
        EditableModel oldPath = new EditableModel();
        List<String> ids = build(dragged, 2);
        List<String> oldIds = build(oldPath, 2);
        dragged.getPartManager().setPartParent(ids.get(1), ids.get(0));
        oldPath.getPartManager().setPartParent(oldIds.get(1), oldIds.get(0));
        ModelPartManager pm = dragged.getPartManager();
        PartTransform start = pm.getPartById(ids.get(0)).orElseThrow().transform();

        pm.beginTransformInteraction();
        pm.setPartTransform(ids.get(0), moved(start, 7));
        oldPath.getPartManager().setPartTransform(oldIds.get(0), moved(start, 7));
        assertArrayEquals(oldPath.getAllMeshVertexPositions(), dragged.getDisplayedCornerPositions(new Matrix4f()), 1e-5f);
        pm.endTransformInteraction();
        assertSameSave(oldPath, dragged);
    }

    @Test
    void aGroupMoveOfNPartsIsOneUpdate() {
        EditableModel model = new EditableModel();
        List<String> ids = build(model, 4);
        ModelPartManager pm = model.getPartManager();
        List<String> notified = new ArrayList<>();
        pm.addPartChangeListener(new IPartChangeListener() {
            @Override public void onPartAdded(ModelPartDescriptor part) { }
            @Override public void onPartRemoved(String partId) { }
            @Override public void onPartTransformChanged(String partId, PartTransform t) { notified.add(partId); }
            @Override public void onPartSelectionChanged(java.util.Set<String> selectedIds) { }
            @Override public void onPartsMerged(List<String> sourceIds, ModelPartDescriptor merged) { }
            @Override public void onPartsRebuilt() { }
        });
        Map<String, PartTransform> updates = new LinkedHashMap<>();
        for (String id : ids) {
            updates.put(id, moved(pm.getPartById(id).orElseThrow().transform(), 2));
        }
        long rebuilds = pm.getRebuildCount();
        assertTrue(pm.setPartTransformsBatch(updates));
        assertEquals(rebuilds + 1, pm.getRebuildCount(), "outside a drag: one rebuild for N parts");
        assertEquals(ids, notified, "every part notified, in order");

        updates.replaceAll((id, t) -> moved(t, 3));
        pm.beginTransformInteraction();
        pm.setPartTransformsBatch(updates);
        assertEquals(rebuilds + 1, pm.getRebuildCount(), "inside a drag: none");
        pm.endTransformInteraction();
        assertEquals(rebuilds + 2, pm.getRebuildCount());
    }

    @Test
    void anUnchangedDragDoesNotRebake() {
        EditableModel model = new EditableModel();
        build(model, 2);
        ModelPartManager pm = model.getPartManager();
        long rebuilds = pm.getRebuildCount();
        pm.beginTransformInteraction();
        assertFalse(pm.endTransformInteraction());
        assertEquals(rebuilds, pm.getRebuildCount());
    }

    @Test
    void structuralChangesStillRebuildDuringADrag() {
        EditableModel model = new EditableModel();
        List<String> ids = build(model, 2);
        ModelPartManager pm = model.getPartManager();
        PartTransform start = pm.getPartById(ids.get(0)).orElseThrow().transform();
        pm.beginTransformInteraction();
        pm.setPartTransform(ids.get(0), moved(start, 4));
        long rebuilds = pm.getRebuildCount();
        pm.setPartVisible(ids.get(1), false);
        assertEquals(rebuilds + 1, pm.getRebuildCount(), "a visibility change bakes immediately (current matrices)");
        assertFalse(pm.isBakePending(), "that bake already holds the moved part");
        assertFalse(pm.endTransformInteraction());
        assertArrayEquals(model.getAllMeshVertexPositions(), model.getDisplayedCornerPositions(new Matrix4f()));
    }

    @Test
    void vertexEditsWhileDeformedWriteBackThroughTheBakedMatrix() {
        EditableModel model = new EditableModel();
        List<String> ids = build(model, 1);
        ModelPartManager pm = model.getPartManager();
        PartTransform start = pm.getPartById(ids.get(0)).orElseThrow().transform();
        float[] local = pm.getPartGeometry(ids.get(0)).vertices().clone();
        pm.beginTransformInteraction();
        pm.setPartTransform(ids.get(0), moved(start, 5));
        // A baked (CPU) position written back unchanged must leave the part-local geometry as it was.
        Vector3f p = model.getVertexPosition(0);
        pm.syncPartVertexFromWorldPos(0, p);
        assertArrayEquals(local, pm.getPartGeometry(ids.get(0)).vertices(), 1e-5f);
        pm.endTransformInteraction();
    }
}
