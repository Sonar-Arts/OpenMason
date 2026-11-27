package com.openmason.main.systems.viewport.viewportRendering.gizmo.interaction;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.rendering.viewer.transform.TransformState;
import com.openmason.engine.rig.CendaRig;
import com.openmason.main.systems.skeleton.BoneCommandHistory;
import com.openmason.main.systems.skeleton.BoneSnapshot;
import com.openmason.main.systems.skeleton.BoneStore;
import com.openmason.main.systems.skeleton.RigWiring;
import com.openmason.main.systems.skeleton.SkinStore;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OpenMason #175 F2 through the editor's gizmo targets: a part or bone drag re-bakes nothing until release (the GPU
 * palette draws it), the release bakes the same mesh as the old per-update rebuilds, a multi-part / group drag
 * moves all its parts in ONE update, and bone undo / redo still restores the baked mesh.
 */
class GizmoDragPaletteTest {

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

    private static List<String> build(EditableModel model, int n) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(model.getPartManager().addPartFromGeometry("p" + i, cube(i * 3f), new Vector3f(i * 3f, 0, 0)).id());
        }
        return ids;
    }

    private static void assertSameSave(EditableModel expected, EditableModel actual) {
        OMOFormat.MeshData e = expected.toMeshData();
        OMOFormat.MeshData a = actual.toMeshData();
        assertArrayEquals(e.vertices(), a.vertices(), "saved vertices");
        assertArrayEquals(e.indices(), a.indices(), "saved indices");
        assertArrayEquals(e.triangleToFaceId(), a.triangleToFaceId(), "saved face ids");
    }

    @Test
    void aSinglePartDragRebakesOnceAtRelease() {
        EditableModel model = new EditableModel();
        EditableModel oldPath = new EditableModel();
        List<String> ids = build(model, 2);
        List<String> oldIds = build(oldPath, 2);
        ModelPartManager pm = model.getPartManager();
        pm.selectPart(ids.get(1));
        oldPath.getPartManager().selectPart(oldIds.get(1));
        PartTransformTarget target = new PartTransformTarget(pm, new TransformState());
        PartTransformTarget oldTarget = new PartTransformTarget(oldPath.getPartManager(), new TransformState());
        long rebuilds = pm.getRebuildCount();
        long oldRebuilds = oldPath.getPartManager().getRebuildCount();

        target.beginDrag();
        for (int i = 1; i <= 10; i++) {
            target.setPosition(3f + i * 0.1f, i * 0.05f, 0f);
            target.setRotation(0f, i * 4f, 0f);
            oldTarget.setPosition(3f + i * 0.1f, i * 0.05f, 0f); // no beginDrag: the old per-update rebuilds
            oldTarget.setRotation(0f, i * 4f, 0f);
        }
        assertEquals(rebuilds, pm.getRebuildCount(), "a drag re-bakes nothing");
        assertEquals(oldRebuilds + 20, oldPath.getPartManager().getRebuildCount());
        assertTrue(model.isDisplayDeformed());
        assertArrayEquals(oldPath.getAllMeshVertexPositions(), model.getDisplayedCornerPositions(new Matrix4f()), 1e-5f);

        target.endDrag();
        assertEquals(rebuilds + 1, pm.getRebuildCount());
        assertFalse(model.isDisplayDeformed());
        assertSameSave(oldPath, model);
    }

    @Test
    void aMultiPartDragIsOneUpdatePerMove() {
        EditableModel model = new EditableModel();
        List<String> ids = build(model, 4);
        ModelPartManager pm = model.getPartManager();
        ids.forEach(pm::selectPart);
        PartTransformTarget target = new PartTransformTarget(pm, new TransformState());

        long rebuilds = pm.getRebuildCount();
        target.setPosition(5f, 1f, 0f);           // no drag open: the batch rebuilds once, not once per part
        assertEquals(rebuilds + 1, pm.getRebuildCount());

        target.beginDrag();
        for (int i = 0; i < 5; i++) target.setPosition(5f + i, 1f, 0f);
        assertEquals(rebuilds + 1, pm.getRebuildCount());
        target.endDrag();
        assertEquals(rebuilds + 2, pm.getRebuildCount());
    }

    @Test
    void aGroupDragDefersUntilTheViewportClosesIt() {
        EditableModel model = new EditableModel();
        EditableModel oldPath = new EditableModel();
        build(model, 3);
        build(oldPath, 3);
        ModelPartManager pm = model.getPartManager();
        PartTransformTarget target = new PartTransformTarget(pm, new TransformState());
        PartTransformTarget oldTarget = new PartTransformTarget(oldPath.getPartManager(), new TransformState());
        long rebuilds = pm.getRebuildCount();

        target.beginGroupDrag();
        oldTarget.beginGroupDrag();
        oldPath.getPartManager().endTransformInteraction(); // the old path: no interaction
        for (int i = 1; i <= 6; i++) {
            target.applyGroupTranslationDelta(new Vector3f(i * 0.2f, 0, 0));
            oldTarget.applyGroupTranslationDelta(new Vector3f(i * 0.2f, 0, 0));
        }
        assertEquals(rebuilds, pm.getRebuildCount());
        assertArrayEquals(oldPath.getAllMeshVertexPositions(), model.getDisplayedCornerPositions(new Matrix4f()), 1e-5f);

        // The gizmo reports no end for a group drag: ViewportRenderPipeline closes it once dragging stopped.
        assertTrue(pm.isTransformInteractionActive());
        pm.endTransformInteraction();
        assertEquals(rebuilds + 1, pm.getRebuildCount());
        assertSameSave(oldPath, model);

        // A following drag closes the stale group interaction state without re-baking twice.
        target.beginDrag();
        target.endDrag();
        assertFalse(pm.isTransformInteractionActive());
        assertEquals(rebuilds + 1, pm.getRebuildCount());
    }

    @Test
    void aBoneDragMovesItsChildPartsOnThePaletteAndUndoStillRestores() {
        assumeTrue(CendaRig.isAvailable(), "native rig unavailable");
        EditableModel model = new EditableModel();
        List<String> ids = build(model, 2);
        ModelPartManager pm = model.getPartManager();
        BoneStore bones = new BoneStore();
        RigWiring.connect(pm, bones, new SkinStore());
        bones.setBones(List.of(new OMOFormat.BoneEntry("b", "b", null, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0)));
        pm.setPartParent(ids.get(1), "b");
        float[] restMesh = model.getAllMeshVertexPositions();
        bones.setSelectedBoneId("b");
        BoneTransformTarget target = new BoneTransformTarget(bones, new TransformState());
        BoneCommandHistory history = new BoneCommandHistory(bones);
        BoneSnapshot before = BoneSnapshot.capture(bones);
        long rebuilds = pm.getRebuildCount();

        target.beginDrag();
        for (int i = 1; i <= 8; i++) target.setRotation(0, 0, i * 10f);
        assertEquals(rebuilds, pm.getRebuildCount(), "a bone drag re-bakes nothing");
        assertTrue(model.isDisplayDeformed());
        float[] shown = model.getDisplayedCornerPositions(new Matrix4f());
        target.endDrag();
        history.push(before, BoneSnapshot.capture(bones), "rotate bone");
        assertEquals(rebuilds + 1, pm.getRebuildCount());
        assertArrayEquals(model.getAllMeshVertexPositions(), shown, 1e-5f, "release bakes what the drag showed");

        assertTrue(history.undo());
        assertArrayEquals(restMesh, model.getAllMeshVertexPositions(), 1e-6f, "undo re-bakes the rest mesh");
        assertTrue(history.redo());
        assertArrayEquals(shown, model.getAllMeshVertexPositions(), 1e-5f);
    }
}
