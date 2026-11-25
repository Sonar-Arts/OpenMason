package com.openmason.engine.rig;

import com.openmason.engine.anim.AnimClipDocument;
import com.openmason.engine.anim.SyncGroup;
import com.openmason.engine.character.CendaCharacter;
import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.oma.ParsedAnimTrack;
import com.openmason.engine.format.oma.ParsedKeyframe;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The CR ABI 2 pose pipeline through its Java binding (OpenMason #156): weighted masks, blends, additive layers,
 * inertialized transitions, root motion, role-authored masks, sync groups, and a frame evaluated in one
 * allocation-free call. The native ctests ({@code rig}, {@code anim_clip}, {@code character}) cover the math in
 * depth; this pins the marshalling.
 */
class RigBlendingTest {

    private static final float EPS = 1e-4f;

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaRig.isAvailable(), "native rig unavailable: " + CendaRig.unavailableReason());
    }

    /** spine0 -> spine1 -> spine2 -> spine3 bones up +Y. */
    private static Rig spine() {
        List<RigJoint> joints = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            joints.add(RigJoint.bone("s" + i, "spine" + i, i - 1, true, new Vector3f(), new Vector3f(),
                new Vector3f(), new Vector3f(0, 1, 0)));
        }
        return Rig.create(joints);
    }

    private static ParsedAnimClip turn(String joint, float yDeg, float duration, boolean loop) {
        ParsedKeyframe k0 = new ParsedKeyframe(0f, new Vector3f(), new Vector3f(0, yDeg, 0), new Vector3f(1), "LINEAR");
        ParsedKeyframe k1 = new ParsedKeyframe(duration, new Vector3f(), new Vector3f(0, yDeg, 0), new Vector3f(1), "LINEAR");
        return new ParsedAnimClip("turn", 30f, duration, loop, List.of(new ParsedAnimTrack(joint, joint, List.of(k0, k1))));
    }

    private static float rotY(PoseWorkspace ws, int slot, int joint) {
        float[] v = new float[ws.rig().jointCount() * 9];
        ws.get(slot, v, null);
        return v[joint * 9 + 4];
    }

    @Test
    void weightedMaskRampsALayerDownTheChain() {
        try (Rig rig = spine(); BoneMask mask = BoneMask.create(rig, 0f); PoseWorkspace ws = PoseWorkspace.create(rig, 2)) {
            mask.set("spine1", 1f, BoneMask.BRANCH, 2);
            assertArrayEquals(new float[]{0f, 0.5f, 1f, 1f}, mask.weights(), EPS);
            float[] all = new float[4 * 9];
            for (int j = 0; j < 4; j++) {
                all[j * 9 + 4] = 80f;
                all[j * 9 + 6] = all[j * 9 + 7] = all[j * 9 + 8] = 1f;
            }
            ws.set(1, all, null);
            PoseProgram p = new PoseProgram().rest(0).layer(0, 1, mask, 1f, PoseProgram.OVERRIDE, 0);
            ws.run(p);
            assertEquals(0f, rotY(ws, 0, 0), EPS);
            assertEquals(40f, rotY(ws, 0, 1), EPS);
            assertEquals(80f, rotY(ws, 0, 3), EPS);
            assertThrows(IllegalArgumentException.class, () -> mask.set("nope", 1f, 0, 0));
        }
    }

    @Test
    void blendAndAdditiveThroughAProgram() {
        try (Rig rig = spine(); PoseWorkspace ws = PoseWorkspace.create(rig, 5)) {
            PoseProgram p = new PoseProgram()
                .sample(0, turn("spine0", 0f, 1f, true), 0f, 0)
                .sample(1, turn("spine0", 60f, 1f, true), 0f, 0)
                .blend(2, 0, 2, new float[]{1f, 2f}, 0);
            ws.run(p);
            assertEquals(40f, rotY(ws, 2, 0), EPS);
            // A pose minus itself, added back, changes nothing (bit for bit).
            float[] before = new float[4 * 9];
            ws.get(2, before, null);
            ws.run(new PoseProgram().makeAdditive(3, 2, 2, 0).layer(2, 3, null, 0.7f, PoseProgram.ADDITIVE, 0)
                .makeAdditive(3, 2, 2, PoseProgram.MESH_SPACE)
                .layer(2, 3, null, 1f, PoseProgram.ADDITIVE, PoseProgram.MESH_SPACE));
            float[] after = new float[4 * 9];
            ws.get(2, after, null);
            assertArrayEquals(before, after);
            // +60 delta at half weight over 40 = 70.
            ws.run(new PoseProgram().makeAdditive(3, 1, 0, 0).layer(2, 3, null, 0.5f, PoseProgram.ADDITIVE, 0));
            assertEquals(70f, rotY(ws, 2, 0), EPS);
            byte[] driven = new byte[4];
            ws.get(0, null, driven);
            assertArrayEquals(new byte[]{1, 0, 0, 0}, driven);
        }
    }

    @Test
    void inertializerBlendsAFromBToBWithoutAJump() {
        try (Rig rig = spine(); PoseWorkspace ws = PoseWorkspace.create(rig, 1); Inertializer in = Inertializer.create(rig)) {
            ParsedAnimClip a = turn("spine0", 90f, 1f, true);
            ParsedAnimClip b = turn("spine0", 0f, 1f, true);
            PoseProgram p = new PoseProgram();
            float dt = 1f / 60f;
            for (int i = 0; i < 3; i++) ws.run(p.clear().sample(0, a, 0f, 0).inertialize(0, in, dt));
            assertEquals(90f, rotY(ws, 0, 0), EPS);
            in.request(0.2f);
            assertTrue(in.active());
            ws.run(p.clear().sample(0, b, 0f, 0).inertialize(0, in, dt));
            float first = rotY(ws, 0, 0);
            assertTrue(first > 85f && first < 90.001f, "no jump at the switch: " + first);
            float last = first;
            for (int i = 0; i < 20; i++) {
                ws.run(p.clear().sample(0, b, 0f, 0).inertialize(0, in, dt));
                float now = rotY(ws, 0, 0);
                assertTrue(now <= last + EPS, "decays monotonically");
                last = now;
            }
            assertFalse(in.active());
            assertEquals(0f, last, 0f);
        }
    }

    @Test
    void rootMotionIsExtractedAndRemoved() {
        try (Rig rig = spine(); PoseWorkspace ws = PoseWorkspace.create(rig, 1)) {
            ParsedKeyframe k0 = new ParsedKeyframe(0f, new Vector3f(), new Vector3f(), new Vector3f(1), "LINEAR");
            ParsedKeyframe k1 = new ParsedKeyframe(1f, new Vector3f(0, 0, 3), new Vector3f(), new Vector3f(1), "LINEAR");
            ParsedAnimClip walk = new ParsedAnimClip("walk", 30f, 1f, true,
                List.of(new ParsedAnimTrack("s0", "spine0", List.of(k0, k1))));
            RigClip clip = RigClip.of(walk);
            int all = PoseProgram.ROOT_X | PoseProgram.ROOT_Y | PoseProgram.ROOT_Z | PoseProgram.ROOT_YAW;
            float[] m = clip.rootMotion(rig, 0, all, 0.9f, 0.2f, new float[4]);
            assertEquals(0.6f, m[2], EPS);
            ws.run(new PoseProgram().sample(0, clip, 0.5f, 0).removeRootMotion(0, clip, 0, all));
            float[] v = new float[4 * 9];
            ws.get(0, v, null);
            assertEquals(0f, v[2], 0f);
        }
    }

    @Test
    void roleMasksAndSyncGroupsBind() {
        assumeTrue(CendaCharacter.isAvailable(), "native character module unavailable");
        try (Rig rig = spine(); BoneMask mask = BoneMask.create(rig, 0f); CharacterDefinition c = CharacterDefinition.create()) {
            c.setRole("spine", "s1");
            c.maskRole(rig, mask, "spine", 0.5f, BoneMask.BRANCH, 0);
            assertArrayEquals(new float[]{0f, 0.5f, 0.5f, 0.5f}, mask.weights(), EPS);
            assertThrows(IllegalArgumentException.class, () -> c.maskRole(rig, mask, "head", 1f, 0, 0));
        }
        try (AnimClipDocument walk = AnimClipDocument.create(); AnimClipDocument run = AnimClipDocument.create()) {
            walk.setHeader(new AnimClipDocument.Header(30f, 1f, true, false, 0f, 0f, 0));
            run.setHeader(new AnimClipDocument.Header(30f, 0.5f, true, false, 0f, 0f, 0));
            walk.addMarker(new AnimClipDocument.SyncMarker("L", 0f));
            walk.addMarker(new AnimClipDocument.SyncMarker("R", 0.5f));
            run.addMarker(new AnimClipDocument.SyncMarker("L", 0.1f));
            run.addMarker(new AnimClipDocument.SyncMarker("R", 0.3f));
            SyncGroup g = new SyncGroup(2);
            g.add(walk, 0f, 1f, 1f);
            g.add(run, 0f, 1f, 0.2f);
            assertEquals(0, g.advance(-1, 0.25f));
            assertEquals(0.25f, g.time(0), EPS);
            assertEquals(0.2f, g.time(1), EPS);  // halfway L(0.1) -> R(0.3)
        }
    }

    @Test
    void aFrameIsOneAllocationFreeCall() {
        java.lang.reflect.Method meter = allocationMeter();
        assumeTrue(meter != null, "allocation metering unsupported");
        try (Rig rig = spine(); PoseWorkspace ws = PoseWorkspace.create(rig, 4); BoneMask mask = BoneMask.create(rig, 1f);
             Inertializer in = Inertializer.create(rig)) {
            RigClip a = RigClip.of(turn("spine0", 30f, 1f, true));
            RigClip b = RigClip.of(turn("spine2", -20f, 0.7f, true));
            PoseProgram p = new PoseProgram();
            float[] weights = {0.3f, 0.7f};
            JointPalette palette = new JointPalette();
            Matrix4f base = new Matrix4f().translate(1, 2, 3);
            RigPoser poser = new RigPoser();
            Runnable frame = () -> {
                p.clear().sample(0, a, 0.25f, 0).sample(1, b, 0.4f, 0).blend(2, 0, 2, weights, PoseProgram.QUAT)
                    .layer(2, 1, mask, 0.5f, PoseProgram.OVERRIDE, 0).inertialize(2, in, 1f / 60f);
                poser.clearExtras().extra(3, 10f, 5f, 0f, 0f, 0f);
                poser.evaluate(ws, p, 2, base, palette);
            };
            for (int i = 0; i < 2000; i++) frame.run();  // warm up (JIT, buffers)
            long before = allocated(meter);
            for (int i = 0; i < 1000; i++) frame.run();
            long allocated = allocated(meter) - before;
            assertTrue(allocated < 16 * 1024, "1000 frames allocated " + allocated + " bytes");
        }
    }

    /** {@code com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes}, reflectively (no jdk.management read). */
    private static java.lang.reflect.Method allocationMeter() {
        try {
            java.lang.reflect.Method m = Class.forName("com.sun.management.ThreadMXBean")
                .getMethod("getCurrentThreadAllocatedBytes");
            m.setAccessible(true);
            return (long) m.invoke(ManagementFactory.getThreadMXBean()) >= 0 ? m : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static long allocated(java.lang.reflect.Method meter) {
        try {
            return (long) meter.invoke(ManagementFactory.getThreadMXBean());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
