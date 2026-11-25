package com.openmason.main.systems.skeleton;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.rig.CendaRig;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * BoneStore over the native rig: parity with the retired Java formulas (head =
 * parentTail·T(origin)·T(pos)·R(rot), tail = head·T(endpoint)) on small hand cases, external
 * (part) parents, the pose layer and the gizmo edit routing.
 */
class BoneStoreTest {

    private static final float EPS = 1e-5f;

    @BeforeEach
    void nativeRig() {
        assumeTrue(CendaRig.isAvailable(), () -> "native rig unavailable: " + CendaRig.unavailableReason());
    }

    static OMOFormat.BoneEntry bone(String id, String parent, float[] origin, float[] pos, float[] rot, float[] end) {
        return new OMOFormat.BoneEntry(id, id, parent, origin[0], origin[1], origin[2],
                pos[0], pos[1], pos[2], rot[0], rot[1], rot[2], end[0], end[1], end[2]);
    }

    private static float[] v(float x, float y, float z) {
        return new float[]{x, y, z};
    }

    // ---- the retired Java formulas (reference) ----

    private static Matrix4f oldHead(OMOFormat.BoneEntry b, Matrix4f parentTail) {
        return new Matrix4f(parentTail)
                .translate(b.originX(), b.originY(), b.originZ())
                .translate(b.posX(), b.posY(), b.posZ())
                .rotateXYZ((float) Math.toRadians(b.rotX()), (float) Math.toRadians(b.rotY()),
                        (float) Math.toRadians(b.rotZ()));
    }

    private static Matrix4f oldTail(OMOFormat.BoneEntry b, Matrix4f head) {
        return new Matrix4f(head).translate(b.endpointX(), b.endpointY(), b.endpointZ());
    }

    private static void assertMatrix(Matrix4f expected, Matrix4f actual) {
        assertNotNull(actual);
        float[] e = expected.get(new float[16]);
        float[] a = actual.get(new float[16]);
        for (int i = 0; i < 16; i++) {
            assertEquals(e[i], a[i], EPS * Math.max(1f, Math.abs(e[i])), "element " + i);
        }
    }

    @Test
    void chainMatchesTheOldFormulas() {
        OMOFormat.BoneEntry root = bone("root", null, v(1, 2, 3), v(0.5f, 0, -1), v(10, 20, 30), v(0, 2, 0));
        OMOFormat.BoneEntry mid = bone("mid", "root", v(0, 0.25f, 0), v(0.1f, 0.2f, 0.3f), v(-45, 5, 90), v(1, 1, 0));
        OMOFormat.BoneEntry tip = bone("tip", "mid", v(0, 0, 0), v(0, 0, 0), v(0, 0, -30), v(0, 0.5f, 0.5f));
        BoneStore store = new BoneStore();
        store.setBones(List.of(tip, root, mid)); // children before parents on purpose

        Matrix4f rootHead = oldHead(root, new Matrix4f());
        Matrix4f rootTail = oldTail(root, rootHead);
        Matrix4f midHead = oldHead(mid, rootTail);
        Matrix4f midTail = oldTail(mid, midHead);
        Matrix4f tipHead = oldHead(tip, midTail);
        Matrix4f tipTail = oldTail(tip, tipHead);

        assertMatrix(rootHead, store.getWorldTransform("root"));
        assertMatrix(rootTail, store.getTailWorldTransform("root"));
        assertMatrix(midHead, store.getWorldTransform("mid"));
        assertMatrix(midTail, store.getTailWorldTransform("mid"));
        assertMatrix(tipHead, store.getWorldTransform("tip"));
        assertMatrix(tipTail, store.getTailWorldTransform("tip"));

        Vector3f tipJoint = store.getJointWorldPosition("tip");
        Vector3f expected = tipHead.transformPosition(new Vector3f());
        assertEquals(expected.x, tipJoint.x, EPS);
        assertEquals(expected.y, tipJoint.y, EPS);
        assertEquals(expected.z, tipJoint.z, EPS);
        assertEquals(3, store.snapshotJointWorldPositions().size());
        assertEquals(3, store.snapshotTailWorldPositions().size());
        assertNull(store.getWorldTransform("nope"));
    }

    @Test
    void externalParentFrameIsComposedOnTheChainRoot() {
        Matrix4f partWorld = new Matrix4f().translate(5, 0, 0).rotateY((float) Math.toRadians(90)).scale(2f);
        OMOFormat.BoneEntry a = bone("a", "part-1", v(0, 1, 0), v(0, 0, 0), v(0, 0, 45), v(0, 1, 0));
        OMOFormat.BoneEntry b = bone("b", "a", v(0, 0, 0), v(1, 0, 0), v(30, 0, 0), v(0, 2, 0));
        BoneStore store = new BoneStore();
        store.setBones(List.of(a, b));
        store.setExternalParentResolver(id -> "part-1".equals(id) ? partWorld : null);

        Matrix4f aHead = oldHead(a, partWorld);
        Matrix4f bHead = oldHead(b, oldTail(a, aHead));
        assertMatrix(aHead, store.getWorldTransform("a"));
        assertMatrix(oldTail(b, bHead), store.getTailWorldTransform("b"));

        // An unknown external parent resolves to identity (a root).
        store.setExternalParentResolver(id -> null);
        assertMatrix(oldHead(a, new Matrix4f()), store.getWorldTransform("a"));
    }

    @Test
    void cyclesAreCutInsteadOfRecursingForever() {
        BoneStore store = new BoneStore();
        store.setBones(List.of(
                bone("x", "y", v(0, 0, 0), v(1, 0, 0), v(0, 0, 0), v(0, 1, 0)),
                bone("y", "x", v(0, 0, 0), v(0, 1, 0), v(0, 0, 0), v(0, 1, 0)),
                bone("self", "self", v(0, 0, 0), v(0, 0, 2), v(0, 0, 0), v(0, 1, 0))));
        assertNotNull(store.getWorldTransform("x"));
        assertNotNull(store.getWorldTransform("y"));
        assertMatrix(new Matrix4f().translate(0, 0, 2), store.getWorldTransform("self"));
    }

    @Test
    void poseLayerOverridesRestUntilCleared() {
        OMOFormat.BoneEntry arm = bone("arm", null, v(0, 1, 0), v(0, 0, 0), v(0, 0, 0), v(0, 2, 0));
        BoneStore store = new BoneStore();
        store.setBones(List.of(arm));
        AtomicInteger changes = new AtomicInteger();
        store.setOnChange(changes::incrementAndGet);

        Vector3f restTail = store.getTailWorldPosition("arm");
        assertEquals(3f, restTail.y, EPS);

        boolean changed = store.setPose(Map.of("arm",
                new BoneStore.BonePose(new Vector3f(), new Vector3f(0, 0, 90))), id -> null, true);
        assertTrue(changed);
        assertTrue(store.isPoseActive());
        assertEquals(1, changes.get());
        Vector3f posedTail = store.getTailWorldPosition("arm");
        assertEquals(-2f, posedTail.x, EPS, "the tail swings about the head");
        assertEquals(1f, posedTail.y, EPS);
        assertEquals(new Vector3f(0, 0, 90), store.getLocalRotation("arm"));
        assertEquals(0f, store.getById("arm").rotZ(), "rest bone untouched");

        assertFalse(store.setPose(Map.of("arm",
                new BoneStore.BonePose(new Vector3f(), new Vector3f(0, 0, 90))), id -> null, true),
                "same pose = no change");
        assertEquals(1, changes.get());

        store.clearPose();
        assertFalse(store.isPoseActive());
        assertEquals(2, changes.get());
        assertEquals(3f, store.getTailWorldPosition("arm").y, EPS);
    }

    @Test
    void gizmoEditsAreRestEdits() {
        BoneStore store = new BoneStore();
        store.setBones(List.of(bone("b", null, v(0, 0, 0), v(0, 0, 0), v(0, 0, 0), v(0, 1, 0))));

        store.editLocal("b", new Vector3f(1, 2, 3), null);
        assertEquals(1f, store.getById("b").posX(), "a rest edit");
        store.editLocal("b", null, new Vector3f(0, 45, 0));
        assertEquals(45f, store.getById("b").rotY());
        assertEquals(new Vector3f(1, 2, 3), store.getLocalPosition("b"), "unchanged component keeps its value");
    }

    @Test
    void removeReparentsChildrenAndPutKeepsOrder() {
        BoneStore store = new BoneStore();
        store.setBones(List.of(
                bone("p", null, v(0, 0, 0), v(0, 0, 0), v(0, 0, 0), v(0, 1, 0)),
                bone("c", "p", v(0, 0, 0), v(0, 0, 0), v(0, 0, 0), v(0, 1, 0)),
                bone("d", null, v(0, 0, 0), v(0, 0, 0), v(0, 0, 0), v(0, 1, 0))));
        store.put(bone("p", null, v(0, 0, 0), v(4, 0, 0), v(0, 0, 0), v(0, 1, 0)));
        assertEquals(List.of("p", "c", "d"), store.getBones().stream().map(OMOFormat.BoneEntry::id).toList());
        assertEquals(4f, store.getJointWorldPosition("c").x, EPS, "child follows the parent's tail");

        assertTrue(store.remove("p"));
        assertTrue(store.getById("c").isRoot());
        assertEquals(0f, store.getJointWorldPosition("c").x, EPS);
    }
}
