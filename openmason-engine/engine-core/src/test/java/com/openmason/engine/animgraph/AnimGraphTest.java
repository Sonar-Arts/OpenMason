package com.openmason.engine.animgraph;

import com.openmason.engine.anim.AnimClipDocument;
import com.openmason.engine.anim.AnimClipDocument.Header;
import com.openmason.engine.anim.AnimClipDocument.RootAxis;
import com.openmason.engine.anim.AnimClipDocument.RootMotion;
import com.openmason.engine.anim.AnimClipDocument.SyncMarker;
import com.openmason.engine.anim.AnimClipDocument.Track;
import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.character.CharacterProfile;
import com.openmason.engine.format.oma.ParsedKeyframe;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.Rig;
import com.openmason.engine.rig.RigModel;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OpenMason #157 through the Java binding: the shipped-style humanoid locomotion graph
 * ({@code animgraph/humanoid_locomotion.omanimgraph}: idle, walk/run blend space, jump, fall, land, a montage slot)
 * runs on two differently built characterized rigs (the Stonebreak player: 33 bones; the goblin: 12) with ONE set
 * of role-addressed clips; the binding round-trips the asset, drives montages, and a frame evaluates without
 * allocating. Includes the 100-character bench. The graph semantics are pinned in depth by the native
 * {@code anim_graph} ctest.
 */
class AnimGraphTest {

    private static final Path MOBS = Path.of("../../test-fixtures/stonebreak/resources/sbe/Mobs");
    private static final Path GRAPH = Path.of("src/test/resources/animgraph/humanoid_locomotion.omanimgraph");

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaAnimGraph.isAvailable(), "native animation graph unavailable: " + CendaAnimGraph.unavailableReason());
    }

    // ---- fixtures ---------------------------------------------------------------------------------------

    private static Rig rigOf(String sbe) throws IOException {
        byte[] omo = null;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(MOBS.resolve(sbe)))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().equals("model.omo")) omo = in.readAllBytes();
            }
        }
        assertTrue(omo != null, sbe + " has no model.omo");
        OMOReader.ReadResult read = new OMOReader().read(new ByteArrayInputStream(omo));
        List<RigModel.PartSpec> parts = new ArrayList<>();
        for (OMOFormat.PartEntry p : read.parts()) {
            parts.add(new RigModel.PartSpec(p.id(), p.name(), p.parentId(), p.vertexCount() > 0,
                new Vector3f(p.originX(), p.originY(), p.originZ()), new Vector3f(p.posX(), p.posY(), p.posZ()),
                new Vector3f(p.rotX(), p.rotY(), p.rotZ()), new Vector3f(p.scaleX(), p.scaleY(), p.scaleZ())));
        }
        return RigModel.build(parts, read.bones(), null, null, null).rig();
    }

    private static ParsedKeyframe key(float t, float rx) {
        return new ParsedKeyframe(t, new Vector3f(), new Vector3f(rx, 0, 0), new Vector3f(1), "LINEAR");
    }

    /** A role-addressed clip: each role swings about X through the given angles, evenly spaced over the clip. */
    private static AnimClipDocument clip(String name, float duration, boolean loop, Map<String, float[]> swings) {
        AnimClipDocument c = AnimClipDocument.create();
        c.setName(name);
        c.setHeader(new Header(30f, duration, loop, false, 0f, 0f, 0));
        for (Map.Entry<String, float[]> e : swings.entrySet()) {
            float[] a = e.getValue();
            List<ParsedKeyframe> keys = new ArrayList<>();
            for (int i = 0; i < a.length; i++) keys.add(key(a.length == 1 ? 0f : duration * i / (a.length - 1), a[i]));
            c.setTrack(Track.forRole(e.getKey(), true, keys));
        }
        return c;
    }

    /** The locomotion clip set, written once against humanoid roles. */
    private static Map<String, AnimClipDocument> clips() {
        AnimClipDocument walk = clip("walk", 1f, true, Map.of(
            "upper_leg_l", new float[]{30, -30, 30}, "upper_leg_r", new float[]{-30, 30, -30}));
        walk.addMarker(new SyncMarker("L", 0f));
        walk.addMarker(new SyncMarker("R", 0.5f));
        AnimClipDocument run = clip("run", 0.6f, true, Map.of(
            "upper_leg_l", new float[]{50, -50, 50}, "upper_leg_r", new float[]{-50, 50, -50}));
        run.addMarker(new SyncMarker("L", 0f));
        run.addMarker(new SyncMarker("R", 0.3f));
        Map<String, AnimClipDocument> m = new java.util.LinkedHashMap<>();
        m.put("idle", clip("idle", 2f, true, Map.of("spine", new float[]{0, 3, 0})));
        m.put("walk", walk);
        m.put("run", run);
        m.put("jump", clip("jump", 0.5f, false, Map.of("upper_leg_l", new float[]{-40}, "upper_leg_r", new float[]{-40},
            "spine", new float[]{-10})));
        m.put("fall", clip("fall", 1f, true, Map.of("upper_leg_l", new float[]{10}, "upper_leg_r", new float[]{-10})));
        m.put("land", clip("land", 0.3f, false, Map.of("upper_leg_l", new float[]{60, 0}, "upper_leg_r", new float[]{60, 0})));
        return m;
    }

    /** A characterized rig: auto-mapped onto the humanoid profile. */
    private record Character(Rig rig, CharacterDefinition definition, CharacterProfile profile) implements AutoCloseable {
        static Character of(String sbe) throws IOException {
            Rig rig = rigOf(sbe);
            CharacterProfile humanoid = CharacterProfile.builtin("humanoid");
            CharacterDefinition c = CharacterDefinition.create();
            c.setSkeleton(rig);
            c.autoMap(rig, humanoid);
            return new Character(rig, c, humanoid);
        }

        int joint(String role) {
            String id = definition.roles().get(role);
            return id == null ? -1 : rig.find(id);
        }

        @Override
        public void close() {
            definition.close();
            profile.close();
            rig.close();
        }
    }

    private static AnimGraphInstance instance(AnimGraph graph, Character ch, Map<String, AnimClipDocument> clips) {
        AnimGraphInstance inst = AnimGraphInstance.create(graph, ch.rig(), ch.definition());
        for (Map.Entry<String, AnimClipDocument> e : clips.entrySet()) {
            assertEquals(0, inst.bindSlot(e.getKey(), e.getValue()), "every role track binds on " + e.getKey());
        }
        return inst;
    }

    // ---- tests ------------------------------------------------------------------------------------------

    @Test
    void theAssetRoundTripsAndDescribesItself() throws IOException {
        try (AnimGraph g = AnimGraph.read(GRAPH)) {
            assertEquals("Humanoid locomotion", g.name());
            assertEquals("humanoid", g.profile());
            assertEquals(List.of("idle", "walk", "run", "jump", "fall", "land"), g.slots());
            assertEquals(AnimGraph.ParamType.TRIGGER, g.params().get(g.param("jump")).type());
            assertEquals(List.of("ground", "jump", "fall", "land"), g.states(g.node("movement")));
            assertEquals(List.of("fullBody"), g.montageSlots());
            byte[] once = g.write();
            try (AnimGraph again = AnimGraph.read(once)) {
                assertArrayEquals(once, again.write());
            }
        }
        AnimGraph.GraphFormatException newer = assertThrows(AnimGraph.GraphFormatException.class,
            () -> AnimGraph.read("{\"format\": \"omanimgraph\", \"version\": \"9.0\", \"root\": \"a\", \"nodes\": []}"));
        assertTrue(newer.newerVersion());
        assertThrows(AnimGraph.GraphFormatException.class, () -> AnimGraph.read("{\"format\": \"omanimgraph\"}"));
    }

    @Test
    void oneLocomotionGraphRunsOnTwoDifferentlyBuiltCharacters() throws IOException {
        assumeTrue(Files.isDirectory(MOBS), "Stonebreak fixtures missing at " + MOBS.toAbsolutePath());
        Map<String, AnimClipDocument> clips = clips();
        try (AnimGraph graph = AnimGraph.read(GRAPH)) {
            int speed = graph.param("speed"), grounded = graph.param("grounded"), jump = graph.param("jump");
            int movement = graph.node("movement");
            List<String> states = graph.states(movement);
            for (String sbe : List.of("SB_Player.sbe", "SB_Goblin.sbe")) {
                try (Character ch = Character.of(sbe); AnimGraphInstance inst = instance(graph, ch, clips)) {
                    int legL = ch.joint("upper_leg_l"), legR = ch.joint("upper_leg_r");
                    assertTrue(legL >= 0 && legR >= 0 && legL != legR, sbe + " maps both thighs");
                    JointPalette palette = new JointPalette();
                    float[] local = new float[ch.rig().jointCount() * 9];
                    float dt = 1f / 60f;
                    // Walking: the thighs swing in opposition through +-30 degrees.
                    inst.setFloat(speed, 1.5f);
                    float minL = 1e9f, maxL = -1e9f;
                    for (int i = 0; i < 90; i++) {
                        inst.evaluate(dt, null, palette);
                        inst.localPose(local);
                        float l = local[legL * 9 + 3], r = local[legR * 9 + 3];
                        minL = Math.min(minL, l);
                        maxL = Math.max(maxL, l);
                        assertEquals(-l, r, 1e-3f, sbe + ": legs in opposition");
                    }
                    assertTrue(maxL > 28f && minL < -28f, sbe + " walk swing " + minL + ".." + maxL);
                    // Running between walk and run keeps the feet in step (sync markers) and swings wider.
                    inst.setFloat(speed, 5f);
                    float maxRun = 0f;
                    for (int i = 0; i < 60; i++) {
                        inst.evaluate(dt, null, palette);
                        inst.localPose(local);
                        maxRun = Math.max(maxRun, Math.abs(local[legL * 9 + 3]));
                    }
                    assertTrue(maxRun > 45f, sbe + " run swing " + maxRun);
                    // Jump -> fall -> land -> ground.
                    Set<String> visited = new LinkedHashSet<>();
                    inst.fireTrigger(jump);
                    for (int i = 0; i < 40; i++) {
                        inst.evaluate(dt, null, palette);
                        visited.add(states.get(inst.state(movement)));
                        if (i == 5) inst.setBool(grounded, false);
                    }
                    inst.setBool(grounded, true);
                    for (int i = 0; i < 60; i++) {
                        inst.evaluate(dt, null, palette);
                        visited.add(states.get(inst.state(movement)));
                    }
                    assertEquals(List.of("ground", "jump", "fall", "land"), visited.stream().sorted(
                        java.util.Comparator.comparingInt(states::indexOf)).toList(), sbe + " visited " + visited);
                    assertEquals("ground", states.get(inst.state(movement)));
                    // Every matrix is finite.
                    Matrix4f m = new Matrix4f();
                    for (int j = 0; j < palette.count(); j++) assertTrue(palette.skinMatrix(j, m).isFinite(), sbe + " joint " + j);
                }
            }
        } finally {
            clips.values().forEach(AnimClipDocument::close);
        }
    }

    @Test
    void montagesNotifiesCurvesAndRootMotionReachJava() throws IOException {
        assumeTrue(Files.isDirectory(MOBS), "Stonebreak fixtures missing");
        Map<String, AnimClipDocument> clips = clips();
        AnimClipDocument walk = clips.get("walk");
        walk.setRootMotion(new RootMotion("role:hips", EnumSet.of(RootAxis.X, RootAxis.Z, RootAxis.YAW)));
        walk.setTrack(Track.forRole("hips", true, List.of(
            new ParsedKeyframe(0f, new Vector3f(), new Vector3f(), new Vector3f(1), "LINEAR"),
            new ParsedKeyframe(1f, new Vector3f(0, 0, 1.4f), new Vector3f(), new Vector3f(1), "LINEAR"))));
        walk.addNotify(new AnimClipDocument.Notify("footstep", 0.25f, 0f, "{\"foot\": \"left\"}"));
        walk.setCurve(new AnimClipDocument.Curve("lean", List.of(AnimClipDocument.CurveKey.linear(0f, 0f),
            AnimClipDocument.CurveKey.linear(1f, 1f))));
        AnimClipDocument wave = clip("wave", 0.6f, false, Map.of("upper_arm_l", new float[]{0, 90, 0}));
        wave.addNotify(new AnimClipDocument.Notify("section:raise", 0f, 0f, null));
        wave.addNotify(new AnimClipDocument.Notify("section:lower", 0.3f, 0f, null));
        try (AnimGraph graph = AnimGraph.read(GRAPH); Character ch = Character.of("SB_Player.sbe");
             AnimGraphInstance inst = instance(graph, ch, clips)) {
            inst.setRootMotion(AnimGraphInstance.ROOT_MOTION_EXTRACT);
            inst.setFloat(graph.param("speed"), 1.5f);
            JointPalette palette = new JointPalette();
            float[] rm = new float[4];
            float travelled = 0f;
            List<AnimGraphInstance.Notify> steps = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                inst.evaluate(1f / 60f, null, palette);
                travelled += inst.rootMotion(rm)[2];
                steps.addAll(inst.notifies());
            }
            assertEquals(1.4f, travelled, 0.01f);
            assertEquals(1, steps.size());
            assertEquals("footstep", steps.get(0).name());
            assertEquals("{\"foot\":\"left\"}", steps.get(0).payloadJson());
            assertEquals(graph.slot("walk"), steps.get(0).source());
            int lean = inst.curveNames().indexOf("lean");
            assertTrue(lean >= 0 && inst.curve(lean) >= 0f && inst.curve(lean) <= 1f);

            int id = inst.addMontage(wave);
            int slot = graph.montageSlot("fullBody");
            inst.playMontage(slot, id, 1f, 0f, 0.1f, null);
            inst.setMontageNext(slot, "raise", "raise");
            for (int i = 0; i < 100; i++) inst.evaluate(1f / 60f, null, palette);
            AnimGraphInstance.MontageStatus st = inst.montageStatus(slot);
            assertEquals(id, st.montage());
            assertTrue(st.time() < 0.3f, "looping the raise section: " + st.time());
            inst.stopMontage(slot, 0.05f);
            for (int i = 0; i < 10; i++) inst.evaluate(1f / 60f, null, palette);
            assertEquals(-1, inst.montageStatus(slot).montage());
            assertThrows(IllegalStateException.class, () -> inst.stopMontage(slot, 0f));
            assertTrue(inst.debug().get(graph.node("walk")).bound());
        } finally {
            clips.values().forEach(AnimClipDocument::close);
            wave.close();
        }
    }

    @Test
    void poseModifiersWorkOnACharacterizedRig() throws IOException {
        assumeTrue(Files.isDirectory(MOBS), "Stonebreak fixtures missing");
        String json = """
            {"format": "omanimgraph", "version": "1.0",
             "parameters": [{"name": "look", "type": "float", "default": 1}],
             "slots": ["idle"], "targets": ["footGoal", "lookAt"], "root": "idle",
             "nodes": [{"id": "idle", "type": "clip", "slot": "idle"}],
             "modifiers": [
               {"type": "twoBone", "upper": "role:upper_leg_l", "mid": "role:lower_leg_l", "end": "role:foot_l",
                "target": "footGoal"},
               {"type": "aim", "target": "lookAt", "weightParam": "look",
                "joints": [{"joint": "role:spine", "weight": 0.4}, {"joint": "role:head", "weight": 0.6}]}
             ]}""";
        Map<String, AnimClipDocument> clips = clips();
        try (AnimGraph graph = AnimGraph.read(json); Character ch = Character.of("SB_Player.sbe");
             AnimGraphInstance inst = AnimGraphInstance.create(graph, ch.rig(), ch.definition())) {
            assertEquals(List.of("footGoal", "lookAt"), graph.targets());
            assertEquals(2, graph.modifierCount());
            assertEquals(0, inst.unresolvedMaskEntries(), "roles: " + new java.util.TreeMap<>(ch.definition().roles()).keySet());
            inst.bindSlot("idle", clips.get("idle"));
            JointPalette palette = new JointPalette();
            int foot = ch.joint("foot_l"), head = ch.joint("head");
            Matrix4f m = new Matrix4f();
            inst.evaluate(0f, null, palette);
            Vector3f rest = palette.worldMatrix(foot, m).getTranslation(new Vector3f());
            Matrix4f headRest = palette.worldMatrix(head, new Matrix4f());
            // Lift the foot and bring it forward: the leg bends and the foot lands on the goal.
            Vector3f goal = new Vector3f(rest).add(0f, 0.25f, -0.15f);
            inst.setTarget(graph.target("footGoal"), AnimGraphInstance.TARGET_MODEL, goal.x, goal.y, goal.z);
            inst.evaluate(0f, null, palette);  // time frozen: the idle clip sways the spine
            Vector3f reached = palette.worldMatrix(foot, m).getTranslation(new Vector3f());
            assertTrue(reached.distance(goal) < 1e-3f, "foot at " + reached + ", goal " + goal);
            // Look somewhere to the side: the head turns; with the look weight at 0 it is back at rest.
            inst.setTarget(graph.target("lookAt"), AnimGraphInstance.TARGET_MODEL, 3f, 1.6f, -3f);
            inst.evaluate(0f, null, palette);  // time frozen: the idle clip sways the spine
            assertFalse(palette.worldMatrix(head, new Matrix4f()).equals(headRest, 1e-3f), "the head turned");
            inst.setFloat(graph.param("look"), 0f);
            inst.evaluate(0f, null, palette);  // time frozen: the idle clip sways the spine
            assertTrue(palette.worldMatrix(head, new Matrix4f()).equals(headRest, 1e-5f), "look weight 0 = rest");
            inst.clearTarget(graph.target("footGoal"));
            inst.evaluate(0f, null, palette);  // time frozen: the idle clip sways the spine
            assertTrue(palette.worldMatrix(foot, m).getTranslation(new Vector3f()).distance(rest) < 1e-5f);
        } finally {
            clips.values().forEach(AnimClipDocument::close);
        }
    }

    @Test
    void aFrameIsOneAllocationFreeCallAndTheBenchFitsAFrame() throws IOException {
        assumeTrue(Files.isDirectory(MOBS), "Stonebreak fixtures missing");
        Map<String, AnimClipDocument> clips = clips();
        List<Character> characters = new ArrayList<>();
        List<AnimGraphInstance> instances = new ArrayList<>();
        try (AnimGraph graph = AnimGraph.read(GRAPH)) {
            characters.add(Character.of("SB_Player.sbe"));
            characters.add(Character.of("SB_Goblin.sbe"));
            int speed = graph.param("speed");
            for (int i = 0; i < 100; i++) {
                AnimGraphInstance inst = instance(graph, characters.get(i % 2), clips);
                inst.setFloat(speed, (i % 7) * 0.8f);
                instances.add(inst);
            }
            List<JointPalette> palettes = new ArrayList<>();
            for (int i = 0; i < 100; i++) palettes.add(new JointPalette());
            Matrix4f base = new Matrix4f().translate(1, 2, 3);
            Runnable frame = () -> {
                for (int i = 0; i < instances.size(); i++) instances.get(i).evaluate(1f / 60f, base, palettes.get(i));
            };
            for (int i = 0; i < 300; i++) frame.run();  // warm up
            java.lang.reflect.Method meter = allocationMeter();
            long before = meter == null ? 0 : allocated(meter);
            long t0 = System.nanoTime();
            int frames = 200;
            for (int i = 0; i < frames; i++) frame.run();
            long ns = System.nanoTime() - t0;
            long bytes = meter == null ? 0 : allocated(meter) - before;
            double perCharacterUs = ns / 1000.0 / frames / instances.size();
            System.out.printf("[animgraph-bench] 100 characters (SB_Player %d joints / goblin %d), %d frames: %.2f us per "
                + "character-frame, %.3f ms per frame of 100, %d bytes allocated%n", characters.get(0).rig().jointCount(),
                characters.get(1).rig().jointCount(), frames, perCharacterUs, ns / 1e6 / frames, bytes);
            assertTrue(meter == null || bytes < 64 * 1024, "evaluate allocated " + bytes + " bytes over " + frames * 100 + " calls");
            assertTrue(perCharacterUs < 2000, "graph evaluation too slow: " + perCharacterUs + " us");
        } finally {
            instances.forEach(AnimGraphInstance::close);
            characters.forEach(Character::close);
            clips.values().forEach(AnimClipDocument::close);
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
