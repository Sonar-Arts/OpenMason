package com.openmason.engine.animgraph;

import com.openmason.engine.anim.AnimClipDocument;
import com.openmason.engine.anim.AnimClipDocument.Header;
import com.openmason.engine.anim.AnimClipDocument.Track;
import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.format.oma.ParsedKeyframe;
import com.openmason.engine.rig.BoneMask;
import com.openmason.engine.rig.FirstPersonProjection;
import com.openmason.engine.rig.HeadBobStabilizer;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.Rig;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OpenMason #161 through the Java binding on the characterized SB_Player (no neck role; names its sides from the
 * viewer) and the re-rigged goblin: role view masks, the eyes point, the graph's first-person view layer (body pose
 * without it bit-identical, first-person pose with it), hiding in a palette (head gone for the owner, sockets kept),
 * and one whole first-person frame (evaluate, eyes, stabilizer, projection, first-person compose, hide) without
 * allocating. The math is pinned in depth by the native {@code first_person} ctest; the GL acceptance is
 * {@code FirstPersonGlTest}.
 */
class FirstPersonGraphTest {

    private static final Path MOBS = Path.of("../../test-fixtures/stonebreak/resources/sbe/Mobs");

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaAnimGraph.isAvailable(), "native animation graph unavailable: " + CendaAnimGraph.unavailableReason());
        assumeTrue(Files.isDirectory(MOBS), "Stonebreak fixtures missing at " + MOBS.toAbsolutePath());
    }

    private static final String GRAPH = """
        {"format": "omanimgraph", "version": "1.0", "profile": "humanoid",
         "parameters": [{"name": "firstPerson", "type": "float"}],
         "slots": ["idle", "inspect"],
         "masks": [{"name": "arms", "entries": [{"role": "upper_arm_l"}, {"role": "upper_arm_r"}]}],
         "root": "out",
         "nodes": [
           {"id": "idle", "type": "clip", "slot": "idle"},
           {"id": "inspect", "type": "clip", "slot": "inspect"},
           {"id": "out", "type": "layer", "base": "idle", "layers": [%s]}]}
        """;

    private static AnimClipDocument clip(String name, String role, float a, float b) {
        AnimClipDocument c = AnimClipDocument.create();
        c.setName(name);
        c.setHeader(new Header(30f, 1f, true, false, 0f, 0f, 0));
        c.setTrack(Track.forRole(role, true, List.of(
            new ParsedKeyframe(0f, new Vector3f(), new Vector3f(a, 0, 0), new Vector3f(1), "LINEAR"),
            new ParsedKeyframe(1f, new Vector3f(), new Vector3f(b, 0, 0), new Vector3f(1), "LINEAR"))));
        return c;
    }

    private static AnimGraphInstance instance(AnimGraph g, AnimGraphTest.Character ch, AnimClipDocument idle,
                                              AnimClipDocument inspect) {
        AnimGraphInstance inst = AnimGraphInstance.create(g, ch.rig(), ch.definition());
        inst.bindSlot("idle", idle);
        inst.bindSlot("inspect", inspect);
        inst.setFloat(g.param("firstPerson"), 1f);
        return inst;
    }

    private static float[] skins(JointPalette p) {
        float[] out = new float[p.count() * 16];
        Matrix4f m = new Matrix4f();
        for (int j = 0; j < p.count(); j++) p.skinMatrix(j, m).get(out, j * 16);
        return out;
    }

    private static float[] weights(BoneMask m) {
        return m.weights();
    }

    @Test
    void viewMasksAndTheEyesPointOnBothCharacters() throws Exception {
        for (String sbe : List.of("SB_Player.sbe", "SB_Goblin.sbe")) {
            try (AnimGraphTest.Character ch = AnimGraphTest.Character.of(sbe);
                 BoneMask m = BoneMask.create(ch.rig(), 0.5f)) {
                Rig rig = ch.rig();
                CharacterDefinition def = ch.definition();
                int head = ch.joint("head"), hips = ch.joint("hips");
                int armL = ch.joint("upper_arm_l"), armR = ch.joint("upper_arm_r");
                int handL = ch.joint("hand_l") >= 0 ? ch.joint("hand_l") : ch.joint("lower_arm_l");
                assertTrue(head >= 0 && hips >= 0 && armL >= 0 && armR >= 0 && handL >= 0, sbe + " roles");

                assertEquals(0, def.viewMask(rig, CharacterDefinition.VIEW_FIRST_PERSON, m), sbe);
                float[] w = weights(m);
                assertEquals(1f, w[armL]);
                assertEquals(1f, w[armR]);
                assertEquals(1f, w[handL]);
                assertEquals(0f, w[head]);
                assertEquals(0f, w[hips]);

                assertEquals(0, def.viewMask(rig, CharacterDefinition.VIEW_HEADLESS, m), sbe);
                w = weights(m);
                assertEquals(0f, w[head]);
                assertEquals(1f, w[hips]);
                assertEquals(1f, w[armL]);

                assertEquals(0, def.viewMask(rig, CharacterDefinition.VIEW_BODY_AWARENESS, m), sbe);
                w = weights(m);
                assertEquals(0f, w[head]);
                assertEquals(0f, w[armL]);
                assertEquals(1f, w[hips]);

                // Eyes: no socket and no eye roles on the fixtures -> the head joint; a socket wins once set.
                JointPalette body = new JointPalette();
                com.openmason.engine.rig.RigPoser.local().pose(rig, null, 0f, List.of(),
                    new Matrix4f().translation(2, 0, -1), body);
                Vector3f eyes = new Vector3f();
                int source = def.eyesPoint(rig, body, eyes);
                Matrix4f hw = body.worldMatrix(head, new Matrix4f());
                assertTrue(source == CharacterDefinition.EYES_HEAD || source == CharacterDefinition.EYES_ROLES, sbe);
                if (source == CharacterDefinition.EYES_HEAD && rig.joints().get(head).kind() == com.openmason.engine.rig.RigJoint.Kind.BONE) {
                    assertEquals(hw.m30(), eyes.x, 1e-5f);
                    assertEquals(hw.m31(), eyes.y, 1e-5f);
                }
                Vector3f socketAt = new Vector3f(eyes).sub(2, 0, -1).add(0, 0.1f, -0.15f);  // rest model space
                def.setSocket(new CharacterDefinition.Socket("eyes", rig.joints().get(head).id(),
                    new com.openmason.engine.character.CharacterDefinition.Vec3(socketAt.x, socketAt.y, socketAt.z),
                    new CharacterDefinition.Vec3(0, 0, 0), new CharacterDefinition.Vec3(1, 1, 1)));
                assertEquals(CharacterDefinition.EYES_SOCKET, def.eyesPoint(rig, body, eyes));
                assertEquals(socketAt.x + 2, eyes.x, 1e-5f);
                assertEquals(socketAt.y, eyes.y, 1e-5f);
                assertEquals(socketAt.z - 1, eyes.z, 1e-5f);
            }
        }
    }

    @Test
    void theFirstPersonLayerShowsOnlyInTheFirstPersonView() throws Exception {
        String view = String.format(GRAPH, """
            {"node": "inspect", "mask": "arms", "view": "firstPerson", "weightParam": "firstPerson"}""");
        String plain = String.format(GRAPH, """
            {"node": "inspect", "mask": "arms", "weightParam": "firstPerson"}""");
        String none = String.format(GRAPH, """
            {"node": "inspect", "mask": "arms", "weight": 0}""");
        for (String sbe : List.of("SB_Player.sbe", "SB_Goblin.sbe")) {
            try (AnimGraph gv = AnimGraph.read(view); AnimGraph gp = AnimGraph.read(plain); AnimGraph gn = AnimGraph.read(none);
                 AnimGraphTest.Character ch = AnimGraphTest.Character.of(sbe);
                 AnimClipDocument idle = clip("idle", "spine", 0, 8);
                 AnimClipDocument inspect = clip("inspect", "upper_arm_l", -20, -75);
                 AnimGraphInstance iv = instance(gv, ch, idle, inspect);
                 AnimGraphInstance ip = instance(gp, ch, idle, inspect);
                 AnimGraphInstance in = instance(gn, ch, idle, inspect)) {
                assertEquals(1, gv.firstPersonLayerCount());
                assertEquals(0, gp.firstPersonLayerCount());
                JointPalette body = new JointPalette(), fp = new JointPalette(), withLayer = new JointPalette(),
                    without = new JointPalette();
                Matrix4f base = new Matrix4f().translation(1, 2, 3).rotateY(0.4f);
                for (int f = 0; f < 10; f++) {
                    iv.evaluate(1 / 30f, base, body);
                    ip.evaluate(1 / 30f, base, withLayer);
                    in.evaluate(1 / 30f, base, without);
                    iv.composeView(AnimGraphInstance.VIEW_FIRST_PERSON, base, fp);
                    assertArrayEquals(skins(without), skins(body), sbe + ": the body never shows the view layer");
                    assertArrayEquals(skins(withLayer), skins(fp), sbe + ": first person = the layer applied");
                    assertFalse(java.util.Arrays.equals(skins(body), skins(fp)), sbe);
                }
                assertThrows(IllegalArgumentException.class, () -> iv.composeView(7, null, new JointPalette()));
            }
        }
    }

    /**
     * On the re-rigged goblin (its head part, snout, brow, eyes and ears hang under the neck bone). The SB_Player's
     * 33 bones bind no geometry yet (all 85 parts have no bone and no skin weights), so role-based hiding cannot
     * remove its visible head until it is re-rigged like the goblin was for #160.
     */
    @Test
    void hidingRemovesTheHeadForTheOwnerAndKeepsItsWorldFrame() throws Exception {
        try (AnimGraphTest.Character ch = AnimGraphTest.Character.of("SB_Goblin.sbe");
             BoneMask headless = BoneMask.create(ch.rig(), 1f)) {
            Rig rig = ch.rig();
            ch.definition().viewMask(rig, CharacterDefinition.VIEW_HEADLESS, headless);
            JointPalette body = new JointPalette();
            com.openmason.engine.rig.RigPoser.local().pose(rig, null, 0f, List.of(),
                new Matrix4f().translation(0, 0, 5), body);
            JointPalette owner = new JointPalette().copyFrom(body).hide(rig, headless);
            int head = rig.find("head");  // the head PART (geometry)
            assertTrue(headless.weights()[head] == 0f, "the head part is in the hidden branch");
            Matrix4f k = owner.skinMatrix(head, new Matrix4f());
            Vector3f a = k.transformPosition(new Vector3f(0.3f, 1.7f, -0.2f));
            Vector3f b = k.transformPosition(new Vector3f(-0.2f, 1.5f, 0.4f));
            assertEquals(0f, a.distance(b), 1e-5f, "every head vertex lands on one point");
            float[] w = headless.weights();
            Matrix4f bm = new Matrix4f(), om = new Matrix4f();
            int hidden = 0;
            for (int j = 0; j < rig.jointCount(); j++) {
                assertEquals(body.worldMatrix(j, bm), owner.worldMatrix(j, om), "world frames (sockets) untouched");
                if (w[j] == 1f) {
                    assertEquals(body.skinMatrix(j, bm), owner.skinMatrix(j, om));
                } else {
                    hidden++;
                }
            }
            assertTrue(hidden >= 7, "the head's branch: " + hidden);  // neck bone, head, snout, brow, eyes, ears
        }
    }

    @Test
    void aWholeFirstPersonFrameAllocatesNothing() throws Exception {
        java.lang.reflect.Method meter = allocationMeter();
        assumeTrue(meter != null, "no allocation meter");
        String graphJson = String.format(GRAPH, """
            {"node": "inspect", "mask": "arms", "view": "firstPerson", "weightParam": "firstPerson"}""");
        try (AnimGraph g = AnimGraph.read(graphJson); AnimGraphTest.Character ch = AnimGraphTest.Character.of("SB_Player.sbe");
             AnimClipDocument idle = clip("idle", "spine", 0, 8);
             AnimClipDocument inspect = clip("inspect", "upper_arm_l", -20, -75);
             AnimGraphInstance inst = instance(g, ch, idle, inspect);
             BoneMask fpMask = BoneMask.create(ch.rig(), 0f);
             BoneMask awareness = BoneMask.create(ch.rig(), 1f);
             HeadBobStabilizer stabilizer = new HeadBobStabilizer()) {
            Rig rig = ch.rig();
            CharacterDefinition def = ch.definition();
            def.viewMask(rig, CharacterDefinition.VIEW_FIRST_PERSON, fpMask);
            def.viewMask(rig, CharacterDefinition.VIEW_BODY_AWARENESS, awareness);
            FirstPersonProjection projection = new FirstPersonProjection().fovY(70f);
            Matrix4f worldProj = new Matrix4f().perspective((float) Math.toRadians(90), 16f / 9f, 0.05f, 500f);
            Matrix4f base = new Matrix4f(), view = new Matrix4f(), fpProj = new Matrix4f(), pull = new Matrix4f(),
                fpBase = new Matrix4f();
            JointPalette body = new JointPalette(), fp = new JointPalette(), owner = new JointPalette();
            Vector3f eyes = new Vector3f();
            Runnable frame = new Runnable() {
                int i;

                @Override
                public void run() {
                    float t = i++ / 60f;
                    base.translation(t * 4f, 0, 0);
                    inst.evaluate(1 / 60f, base, body);
                    def.eyesPoint(rig, body, eyes);
                    stabilizer.update(eyes, base, 1 / 60f, eyes);
                    view.identity().rotateX(0.2f).translate(-eyes.x, -eyes.y, -eyes.z);
                    projection.compute(view, worldProj, fpProj, pull);
                    inst.composeView(AnimGraphInstance.VIEW_FIRST_PERSON, pull.mul(base, fpBase), fp);
                    fp.hide(rig, fpMask);
                    owner.copyFrom(body).hide(rig, awareness);
                }
            };
            for (int i = 0; i < 200; i++) frame.run();
            long before = allocated(meter);
            for (int i = 0; i < 2000; i++) frame.run();
            long bytes = allocated(meter) - before;
            System.out.printf("[first-person] 2000 frames allocated %d bytes%n", bytes);
            assertTrue(bytes < 2048, "allocated " + bytes + " bytes over 2000 frames");
        }
    }

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
