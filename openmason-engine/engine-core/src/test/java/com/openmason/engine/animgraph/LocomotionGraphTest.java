package com.openmason.engine.animgraph;

import com.openmason.engine.anim.AnimClipDocument;
import com.openmason.engine.anim.AnimClipDocument.Curve;
import com.openmason.engine.anim.AnimClipDocument.CurveKey;
import com.openmason.engine.anim.AnimClipDocument.Header;
import com.openmason.engine.anim.AnimClipDocument.Interp;
import com.openmason.engine.anim.AnimClipDocument.Track;
import com.openmason.engine.format.oma.ParsedKeyframe;
import com.openmason.engine.rig.JointPalette;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OpenMason #159 through the Java binding, on the characterized Stonebreak player (humanoid auto-map; it names its
 * sides from the viewer, characterization fixes that): the host answers foot placement's batched ground query from
 * Java (one upcall per evaluate), a role-addressed flat-ground walk climbs Stonebreak stairs (0.5 up, 0.5 deep) with
 * every locked contact foot within 0.01 of its tread (its height above the tread = the same clip's foot height above
 * flat ground, frame for frame) and pinned in place; host exceptions come back out of evaluate; the re-rigged goblin
 * gets heel + toe probes; a frame with the full locomotion stack + the upcall allocates nothing (bench).
 * The solvers themselves are pinned by the native {@code locomotion} ctest.
 */
class LocomotionGraphTest {

    private static final Path MOBS = Path.of("../../test-fixtures/stonebreak/resources/sbe/Mobs");
    private static final float DT = 1f / 60f;

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaAnimGraph.isAvailable(), "native animation graph unavailable: " + CendaAnimGraph.unavailableReason());
        assumeTrue(Files.isDirectory(MOBS), "Stonebreak fixtures missing");
    }

    // A role-addressed flat-ground walk (1 s loop, keys at 30/s): thighs swing +-25 degrees, the swing knee bends up
    // to 50, the feet stay level; foot_lock_l / foot_lock_r = the straight-knee keys (left 8/30..22/30, right
    // 23/30..7/30).
    static AnimClipDocument walk() {
        AnimClipDocument c = AnimClipDocument.create();
        c.setName("walk");
        c.setHeader(new Header(30f, 1f, true, false, 0f, 0f, 0));
        String[] roles = {"upper_leg_l", "lower_leg_l", "foot_l", "upper_leg_r", "lower_leg_r", "foot_r"};
        for (int r = 0; r < roles.length; r++) {
            List<ParsedKeyframe> keys = new ArrayList<>();
            for (int i = 0; i <= 30; i++) {
                double t = i / 30.0;
                double th = 25.0 * Math.sin(2 * Math.PI * t) * (r < 3 ? 1 : -1);
                double cs = Math.cos(2 * Math.PI * t) * (r < 3 ? 1 : -1);
                double knee = -50.0 * Math.max(0.0, cs);
                double rx = switch (r % 3) {
                    case 0 -> th;
                    case 1 -> knee;
                    default -> -th - knee;
                };
                keys.add(new ParsedKeyframe((float) t, new Vector3f(), new Vector3f((float) rx, 0, 0), new Vector3f(1), "LINEAR"));
            }
            c.setTrack(Track.forRole(roles[r], true, keys));
        }
        float lOn = 8f / 30f, lOff = 22f / 30f, rOn = 23f / 30f, rOff = 7f / 30f;
        c.setCurve(new Curve("foot_lock_l", List.of(new CurveKey(0f, 0f, 0f, 0f, Interp.STEP),
            new CurveKey(lOn, 1f, 0f, 0f, Interp.STEP), new CurveKey(lOff, 0f, 0f, 0f, Interp.STEP))));
        c.setCurve(new Curve("foot_lock_r", List.of(new CurveKey(0f, 1f, 0f, 0f, Interp.STEP),
            new CurveKey(rOff, 0f, 0f, 0f, Interp.STEP), new CurveKey(rOn, 1f, 0f, 0f, Interp.STEP))));
        return c;
    }

    private static final String GRAPH = """
        {"format": "omanimgraph", "version": "1.0", "profile": "humanoid",
         "parameters": [{"name": "speed", "type": "float"}, {"name": "angle", "type": "float"}],
         "slots": ["walk"], "root": "walk",
         "nodes": [{"id": "walk", "type": "clip", "slot": "walk"}],
         "modifiers": [
           {"type": "orientationWarp", "hips": "role:hips", "angleParam": "angle",
            "spine": [{"joint": "role:spine"}, {"joint": "role:chest"}]},
           {"type": "strideWarp", "pelvis": "role:hips", "speedParam": "speed", "animSpeed": 1.49,
            "legs": [{"upper": "role:upper_leg_l", "mid": "role:lower_leg_l", "end": "role:foot_l"},
                     {"upper": "role:upper_leg_r", "mid": "role:lower_leg_r", "end": "role:foot_r"}]},
           {"type": "footPlacement", "pelvis": "role:hips", "maxDrop": 0.6,
            "legs": [{"upper": "role:upper_leg_l", "mid": "role:lower_leg_l", "end": "role:foot_l", "lockCurve": "foot_lock_l"},
                     {"upper": "role:upper_leg_r", "mid": "role:lower_leg_r", "end": "role:foot_r", "lockCurve": "foot_lock_r"}]}
         ]}""";
    private static final int FOOT = 2;  // the footPlacement modifier

    /** Stonebreak stairs going up toward -Z from z = 0. */
    private static double tread(double z) {
        return z >= 0 ? 0 : 0.5 * (Math.floor(-z / 0.5) + 1);
    }

    /** Answers from the stairs; remembers, per leg, the highest ground it reported this frame. */
    private static final class Stairs implements GroundQuery {
        int calls;
        int lastCount;
        final double[] highest = new double[2];
        final int[] feet = new int[8];
        final int[] points = new int[8];
        RuntimeException fail;

        @Override
        public void query(GroundProbes p) {
            calls++;
            if (fail != null) throw fail;
            lastCount = p.count();
            highest[0] = highest[1] = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < p.count(); i++) {
                if (i < 8) {
                    feet[i] = p.foot(i);
                    points[i] = p.point(i);
                }
                double h = tread(p.originZ(i));
                double t = (p.originY(i) - h) / -p.directionY(i);
                if (t >= 0 && t <= p.maxDistance(i)) {
                    p.hit(i, p.originX(i), h, p.originZ(i));
                    highest[p.foot(i)] = Math.max(highest[p.foot(i)], h);
                }
            }
        }
    }

    private static Matrix4f at(double y, double z) {
        return new Matrix4f().translation(0f, (float) y, (float) z);
    }

    private static Vector3f pos(JointPalette p, int joint) {
        return p.worldMatrix(joint, new Matrix4f()).getTranslation(new Vector3f());
    }

    @Test
    void aFlatGroundWalkClimbsStonebreakStairsWithItsContactFeetOnTheTreads() throws IOException {
        try (AnimGraph graph = AnimGraph.read(GRAPH); AnimGraphTest.Character ch = AnimGraphTest.Character.of("SB_Player.sbe");
             AnimClipDocument clip = walk();
             AnimGraphInstance placed = AnimGraphInstance.create(graph, ch.rig(), ch.definition());
             AnimGraphInstance flat = AnimGraphInstance.create(graph, ch.rig(), ch.definition())) {
            // SB_Player has no `chest` role: that spine joint is left out (counted), the warp still runs.
            assertEquals(1, placed.unresolvedMaskEntries());
            for (AnimGraphInstance i : List.of(placed, flat)) {
                assertEquals(0, i.bindSlot("walk", clip));
                i.setFloat(graph.param("speed"), 1.49f);
            }
            Stairs stairs = new Stairs();
            placed.setGround(stairs);
            int[] feet = {ch.joint("foot_l"), ch.joint("foot_r")};
            Vector3f[] toeOffset = new Vector3f[2];
            for (int k = 0; k < 2; k++) toeOffset[k] = new Vector3f(ch.rig().joints().get(feet[k]).endpoint());
            JointPalette pp = new JointPalette(), fp = new JointPalette();
            double[] lockTread = new double[2];
            Vector3f[] lockAt = new Vector3f[2];
            boolean[] was = new boolean[2];
            double worst = 0, slide = 0;
            int checked = 0, onRealTread = 0, locks = 0;
            for (int f = 0; f < 360; f++) {
                double z = 1.0 - 1.49 * f * DT;
                double y = z >= 0 ? 0 : -z + 0.25;  // the host's smoothed root on the stairs (tread midpoints)
                placed.evaluate(DT, at(y, z), pp);
                flat.evaluate(DT, at(0, z), fp);
                assertEquals(f + 1, stairs.calls, "one ground query per evaluate");
                for (int k = 0; k < 2; k++) {
                    boolean full = placed.modifierValue(FOOT,
                            AnimGraphInstance.valueLeg(k, AnimGraphInstance.LEG_OUT_LOCKED)) == 1f
                        && placed.modifierValue(FOOT, AnimGraphInstance.valueLeg(k, AnimGraphInstance.LEG_OUT_ALPHA)) == 1f;
                    Vector3f ankle = pos(pp, feet[k]);
                    if (full && !was[k]) {
                        // Locked this frame, on the ground the host reported under the foot.
                        lockTread[k] = stairs.highest[k];
                        lockAt[k] = ankle;
                        locks++;
                        Vector3f toe = pp.worldMatrix(feet[k], new Matrix4f()).transformPosition(new Vector3f(toeOffset[k]));
                        if (Math.abs(Math.max(tread(ankle.z), tread(toe.z)) - lockTread[k]) < 1e-9) onRealTread++;
                    }
                    if (full && z < -0.5) {
                        double above = ankle.y - lockTread[k];
                        double flatAbove = pos(fp, feet[k]).y;  // the same clip on flat ground at y = 0
                        worst = Math.max(worst, Math.abs(above - flatAbove));
                        slide = Math.max(slide, Math.hypot(ankle.x - lockAt[k].x, ankle.z - lockAt[k].z));
                        checked++;
                    }
                    was[k] = full;
                }
            }
            System.out.printf("[locomotion] SB_Player on Stonebreak stairs: %d contact foot-frames, worst %.5f from the tread "
                + "(vs the clip on flat ground), slide %.5f, %d/%d locks on the tread under heel or toe%n",
                checked, worst, slide, onRealTread, locks);
            assertTrue(checked > 200, "contact frames checked: " + checked);
            assertTrue(worst <= 0.01, "a contact foot strayed " + worst + " from its tread");
            assertTrue(slide <= 0.001, "a locked foot slid " + slide);
            assertEquals(locks, onRealTread, "every lock sits on the tread under the foot");
            // The query's layout: heel then toe per leg, in leg order (the player's feet are bones with length).
            assertEquals(4, stairs.lastCount);
            assertEquals(List.of(0, 0, 1, 1), List.of(stairs.feet[0], stairs.feet[1], stairs.feet[2], stairs.feet[3]));
            assertEquals(List.of(0, 1, 0, 1), List.of(stairs.points[0], stairs.points[1], stairs.points[2], stairs.points[3]));
        }
    }

    @Test
    void hostExceptionsComeBackOutOfEvaluateAndTheGroundCanBeCleared() throws IOException {
        try (AnimGraph graph = AnimGraph.read(GRAPH); AnimGraphTest.Character ch = AnimGraphTest.Character.of("SB_Player.sbe");
             AnimClipDocument clip = walk();
             AnimGraphInstance inst = AnimGraphInstance.create(graph, ch.rig(), ch.definition())) {
            inst.bindSlot("walk", clip);
            Stairs stairs = new Stairs();
            inst.setGround(stairs);
            JointPalette p = new JointPalette();
            inst.evaluate(DT, at(0, 0), p);
            IllegalStateException boom = new IllegalStateException("no world here");
            stairs.fail = boom;
            assertSame(boom, assertThrows(IllegalStateException.class, () -> inst.evaluate(DT, at(0, 0), p)));
            stairs.fail = null;
            inst.evaluate(DT, at(0, 0), p);  // usable again
            int calls = stairs.calls;
            inst.setGround(null);
            inst.evaluate(DT, at(0, 0), p);
            assertEquals(calls, stairs.calls, "no query without a ground");
            // The stride warp's play rate and stride are readable: at 2x the clip speed the rate caps at 1.2.
            inst.setFloat(graph.param("speed"), 2.98f);
            inst.evaluate(DT, at(0, 0), p);
            assertEquals(1.2f, inst.modifierValue(1, AnimGraphInstance.VALUE_PLAY_RATE), 1e-6f);
            assertEquals(2.98f / (1.49f * 1.2f), inst.modifierValue(1, AnimGraphInstance.VALUE_STRIDE), 1e-5f);
        }
    }

    @Test
    void theReRiggedGoblinGetsFullFootPlacement() throws IOException {
        try (AnimGraph graph = AnimGraph.read(GRAPH); AnimGraphTest.Character ch = AnimGraphTest.Character.of("SB_Goblin.sbe");
             AnimClipDocument clip = walk();
             AnimGraphInstance inst = AnimGraphInstance.create(graph, ch.rig(), ch.definition())) {
            // Since the #160 re-rig it maps feet (ankle bones with toe tails): only `chest` is missing.
            assertEquals(1, inst.unresolvedMaskEntries());
            inst.bindSlot("walk", clip);
            Stairs stairs = new Stairs();
            inst.setGround(stairs);
            inst.evaluate(DT, at(0, 0), new JointPalette());
            assertEquals(4, stairs.lastCount, "heel and toe per leg");
        }
    }

    @Test
    void aFrameWithTheLocomotionStackAndTheUpcallAllocatesNothing() throws IOException {
        List<AnimGraphInstance> instances = new ArrayList<>();
        try (AnimGraph graph = AnimGraph.read(GRAPH); AnimGraphTest.Character ch = AnimGraphTest.Character.of("SB_Player.sbe");
             AnimClipDocument clip = walk()) {
            int speed = graph.param("speed"), angle = graph.param("angle");
            Stairs stairs = new Stairs();
            for (int i = 0; i < 100; i++) {
                AnimGraphInstance inst = AnimGraphInstance.create(graph, ch.rig(), ch.definition());
                inst.bindSlot("walk", clip);
                inst.setGround(stairs);
                inst.setFloat(speed, 0.5f + (i % 7) * 0.4f);
                inst.setFloat(angle, (i % 5) * 20f - 40f);
                instances.add(inst);
            }
            List<JointPalette> palettes = new ArrayList<>();
            for (int i = 0; i < 100; i++) palettes.add(new JointPalette());
            Matrix4f base = new Matrix4f();
            float[] z = {0f};
            Runnable frame = () -> {
                z[0] -= 0.02f;
                base.translation(0f, Math.max(0f, -z[0] + 0.25f), z[0]);
                for (int i = 0; i < instances.size(); i++) instances.get(i).evaluate(DT, base, palettes.get(i));
            };
            for (int i = 0; i < 300; i++) frame.run();  // warm up
            java.lang.reflect.Method meter = allocationMeter();
            long before = meter == null ? 0 : allocated(meter);
            long t0 = System.nanoTime();
            int frames = 200;
            for (int i = 0; i < frames; i++) frame.run();
            long ns = System.nanoTime() - t0;
            long bytes = meter == null ? 0 : allocated(meter) - before;
            double us = ns / 1000.0 / frames / instances.size();
            System.out.printf("[locomotion-bench] 100 SB_Players (orientation + stride warp + foot placement, Java ground "
                + "query), %d frames: %.2f us per character-frame, %.3f ms per frame of 100, %d bytes allocated%n",
                frames, us, ns / 1e6 / frames, bytes);
            assertTrue(meter == null || bytes < 64 * 1024, "evaluate allocated " + bytes + " bytes over " + frames * 100 + " calls");
            assertTrue(us < 2000, "too slow: " + us + " us");
        } finally {
            instances.forEach(AnimGraphInstance::close);
        }
    }

    private static java.lang.reflect.Method allocationMeter() {
        try {
            java.lang.reflect.Method m = Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes");
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
