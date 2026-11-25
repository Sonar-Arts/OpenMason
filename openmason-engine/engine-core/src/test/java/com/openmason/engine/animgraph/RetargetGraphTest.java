package com.openmason.engine.animgraph;

import com.openmason.engine.anim.AnimClipDocument;
import com.openmason.engine.character.CharacterProfile;
import com.openmason.engine.character.Retargeter;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.RigPoser;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OpenMason #160 acceptance through the Java binding: a walk made on the characterized Stonebreak player
 * (role-addressed, bound to its joints) retargeted onto the goblin (re-rigged for #160; 0.6x the player's hip
 * height, arms and legs proportioned differently): no limb flips over the cycle (knees always bend forward), the
 * goblin's contact feet drift less per step than the #159 bench threshold when it moves at the player's speed x the
 * pelvis scale, the baked clip poses like the runtime retarget, and the player retargeted onto itself keeps its pose.
 * The solver is pinned in depth by the native {@code retarget} ctest.
 */
class RetargetGraphTest {

    private static final Path MOBS = Path.of("../../test-fixtures/stonebreak/resources/sbe/Mobs");
    private static final String ONE_CLIP = """
        {"format": "omanimgraph", "version": "1.0", "slots": ["walk"], "root": "walk",
         "nodes": [{"id": "walk", "type": "clip", "slot": "walk"}]}""";
    /** The #159 bench threshold for contact-foot drift per step (foot locks: LocomotionGraphTest / native ctest). */
    private static final double DRIFT_THRESHOLD = 0.005;

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaAnimGraph.isAvailable(), "native animation graph unavailable: " + CendaAnimGraph.unavailableReason());
        assumeTrue(Files.isDirectory(MOBS), "Stonebreak fixtures missing");
    }

    /** A characterized fixture in its fitted reference pose (#154 rest normalization). */
    private static AnimGraphTest.Character fitted(String sbe) throws IOException {
        AnimGraphTest.Character ch = AnimGraphTest.Character.of(sbe);
        ch.definition().fitReferencePose(ch.rig(), ch.profile());
        return ch;
    }

    /**
     * The role walk as a clip made ON the character: joint tracks whose keys carry the character's own rest positions
     * (the shared role clip keys position 0, which would drag each leg bone off its hip).
     */
    private static AnimClipDocument madeOn(AnimGraphTest.Character ch, AnimClipDocument roleClip) {
        AnimClipDocument.Bound bound = roleClip.bindRoles(ch.definition());
        assertEquals(0, bound.droppedTracks());
        AnimClipDocument clip = bound.clip();
        for (AnimClipDocument.Track t : clip.tracks()) {
            int j = ch.rig().find(t.partId());
            Vector3f rest = new Vector3f(ch.rig().joints().get(j).pos());
            List<com.openmason.engine.format.oma.ParsedKeyframe> keys = new ArrayList<>();
            for (com.openmason.engine.format.oma.ParsedKeyframe k : t.keys()) {
                keys.add(new com.openmason.engine.format.oma.ParsedKeyframe(k.time(), new Vector3f(rest), k.rotation(), k.scale(), k.easing()));
            }
            clip.setTrack(new AnimClipDocument.Track(t.partId(), t.partName(), t.role(), t.bone(), keys));
        }
        return clip;
    }

    private static Vector3f pos(JointPalette p, int joint) {
        return p.worldMatrix(joint, new Matrix4f()).getTranslation(new Vector3f());
    }

    /** Contact-foot drift per step of a walking character: each contact (lock curve 1) from start to end. */
    private static final class Drift {
        double total;
        int steps;
        final Vector3f[] start = new Vector3f[2];
        final Vector3f[] last = new Vector3f[2];
        final boolean[] down = new boolean[2];

        void frame(boolean[] contact, Vector3f[] feet, boolean count) {
            for (int k = 0; k < 2; k++) {
                if (contact[k] && !down[k]) start[k] = new Vector3f(feet[k]);
                if (!contact[k] && down[k] && count) {
                    total += Math.hypot(last[k].x - start[k].x, last[k].z - start[k].z);
                    steps++;
                }
                last[k] = new Vector3f(feet[k]);
                down[k] = contact[k];
            }
        }

        double perStep() {
            return steps == 0 ? Double.NaN : total / steps;
        }
    }

    @Test
    void aWalkMadeOnThePlayerRetargetsOntoTheGoblin() throws IOException {
        try (AnimGraph graph = AnimGraph.read(ONE_CLIP); AnimGraphTest.Character player = fitted("SB_Player.sbe");
             AnimGraphTest.Character goblin = fitted("SB_Goblin.sbe"); AnimClipDocument roleWalk = LocomotionGraphTest.walk()) {
            try (AnimClipDocument walk = madeOn(player, roleWalk);
                 Retargeter rt = Retargeter.create(player.rig(), player.definition(), goblin.rig(), goblin.definition());
                 Retargeter fkOnly = Retargeter.create(player.rig(), player.definition(), goblin.rig(), goblin.definition());
                 AnimClipDocument baked = rt.bake(walk, 30f);
                 AnimGraphInstance source = AnimGraphInstance.create(graph, player.rig(), player.definition());
                 AnimGraphInstance fk = AnimGraphInstance.create(graph, goblin.rig(), goblin.definition());
                 AnimGraphInstance live = AnimGraphInstance.create(graph, goblin.rig(), goblin.definition());
                 AnimGraphInstance bake = AnimGraphInstance.create(graph, goblin.rig(), goblin.definition())) {
                Map<String, Retargeter.Chain> chains = rt.chains().stream().collect(Collectors.toMap(Retargeter.Chain::name, c -> c));
                for (String limb : List.of("leg_l", "leg_r", "arm_l", "arm_r")) {
                    assertTrue(chains.containsKey(limb) && chains.get(limb).ik(), limb + " is retargeted with IK: " + chains);
                }
                float scale = rt.scale();
                assertTrue(scale > 0.55f && scale < 0.65f, "goblin / player hip height " + scale);

                source.bindSlot("walk", walk);
                live.bindSlotRetargeted("walk", walk, rt);
                fkOnly.setOps(fkOnly.ops().subList(0, 2));  // pelvis + FK: no IK goals
                fk.bindSlotRetargeted("walk", walk, fkOnly);
                bake.bindSlot("walk", baked);
                int lockL = source.curveNames().indexOf("foot_lock_l"), lockR = source.curveNames().indexOf("foot_lock_r");
                int[] pFeet = {player.joint("foot_l"), player.joint("foot_r")};
                int[] gFeet = {goblin.joint("foot_l"), goblin.joint("foot_r")};
                int[][] legs = {{goblin.joint("upper_leg_l"), goblin.joint("lower_leg_l"), goblin.joint("foot_l")},
                    {goblin.joint("upper_leg_r"), goblin.joint("lower_leg_r"), goblin.joint("foot_r")}};
                List<Integer> chainJoints = new ArrayList<>();
                for (String role : List.of("hips", "spine", "head", "upper_arm_l", "lower_arm_l", "hand_l", "upper_arm_r",
                    "lower_arm_r", "hand_r", "upper_leg_l", "lower_leg_l", "foot_l", "upper_leg_r", "lower_leg_r", "foot_r")) {
                    chainJoints.add(goblin.joint(role));
                }

                // The player's walking speed: how far its clip carries a stance foot back over a contact, per second.
                float dt = 1f / 60f;
                float speed = contactSpeed(graph, player, walk, pFeet, lockL, lockR, dt);
                JointPalette ps = new JointPalette(), pl = new JointPalette(), pb = new JointPalette(), pf = new JointPalette();
                Drift playerDrift = new Drift(), goblinDrift = new Drift(), fkDrift = new Drift();
                Quaternionf[] prev = new Quaternionf[chainJoints.size()];
                float worstTurn = 0f, worstBakedBetween = 0f;
                int bent = 0, forward = 0;
                for (int f = 0; f < 240; f++) {
                    float z = -speed * f * dt;
                    source.evaluate(dt, new Matrix4f().translation(0, 0, z), ps);
                    live.evaluate(dt, new Matrix4f().translation(0, 0, z * scale), pl);
                    bake.evaluate(dt, new Matrix4f().translation(0, 0, z * scale), pb);
                    fk.evaluate(dt, new Matrix4f().translation(0, 0, z * scale), pf);
                    boolean[] contact = {source.curve(lockL) > 0.5f, source.curve(lockR) > 0.5f};
                    playerDrift.frame(contact, new Vector3f[]{pos(ps, pFeet[0]), pos(ps, pFeet[1])}, f > 60);
                    goblinDrift.frame(contact, new Vector3f[]{pos(pl, gFeet[0]), pos(pl, gFeet[1])}, f > 60);
                    fkDrift.frame(contact, new Vector3f[]{pos(pf, gFeet[0]), pos(pf, gFeet[1])}, f > 60);
                    // No flips: no chain joint turns more than 20 degrees in one 60 Hz frame.
                    for (int i = 0; i < chainJoints.size(); i++) {
                        Quaternionf q = pl.worldMatrix(chainJoints.get(i), new Matrix4f()).getNormalizedRotation(new Quaternionf());
                        if (prev[i] != null && f > 1) {
                            worstTurn = Math.max(worstTurn, (float) Math.toDegrees(new Quaternionf(prev[i]).difference(q).angle()));
                        }
                        prev[i] = q;
                    }
                    // Knees bend forward (the goblin faces -Z).
                    for (int[] leg : legs) {
                        Vector3f a = pos(pl, leg[0]), m = pos(pl, leg[1]), e = pos(pl, leg[2]);
                        Vector3f ae = new Vector3f(e).sub(a).normalize();
                        Vector3f am = new Vector3f(m).sub(a);
                        Vector3f off = am.sub(new Vector3f(ae).mul(new Vector3f(m).sub(a).dot(ae)));
                        if (off.length() > 0.01f) {
                            bent++;
                            if (off.z < 0f) forward++;
                        }
                    }
                    // Baked == runtime (60 Hz: half the frames fall between the 30 fps keys).
                    for (int j = 0; j < goblin.rig().jointCount(); j++) {
                        worstBakedBetween = Math.max(worstBakedBetween, diff(pl.worldMatrix(j, new Matrix4f()), pb.worldMatrix(j, new Matrix4f())));
                    }
                }
                System.out.printf("[retarget] SB_Player walk -> goblin (scale %.3f): worst per-frame turn %.2f deg, %d/%d bent "
                        + "knees forward, contact drift per step %.5f (player %.5f, FK only %.4f, threshold %.3f), baked vs live %.4f%n",
                    scale, worstTurn, forward, bent, goblinDrift.perStep(), playerDrift.perStep(), fkDrift.perStep(),
                    DRIFT_THRESHOLD, worstBakedBetween);
                assertTrue(worstTurn < 20f, "a limb flipped: " + worstTurn + " degrees in one frame");
                assertTrue(bent > 100 && forward == bent, forward + " of " + bent + " bent knees forward");
                assertTrue(goblinDrift.steps >= 4 && goblinDrift.perStep() < DRIFT_THRESHOLD,
                    "goblin contact drift per step " + goblinDrift.perStep());
                assertTrue(fkDrift.perStep() > 10 * DRIFT_THRESHOLD, "FK alone slides the goblin's feet: " + fkDrift.perStep());
                assertTrue(worstBakedBetween < 0.02f, "baked vs live " + worstBakedBetween);
            }
        }
    }

    /** The clip's ground speed: stance-foot travel (model space, the character standing still) over contact time. */
    private static float contactSpeed(AnimGraph graph, AnimGraphTest.Character ch, AnimClipDocument clip, int[] feet,
                                      int lockL, int lockR, float dt) {
        try (AnimGraphInstance inst = AnimGraphInstance.create(graph, ch.rig(), ch.definition())) {
            inst.bindSlot("walk", clip);
            JointPalette p = new JointPalette();
            double travel = 0.0, time = 0.0;
            Vector3f[] start = new Vector3f[2], last = new Vector3f[2];
            boolean[] down = new boolean[2];
            int[] frames = new int[2];
            for (int f = 0; f < 240; f++) {
                inst.evaluate(dt, null, p);
                boolean[] contact = {inst.curve(lockL) > 0.5f, inst.curve(lockR) > 0.5f};
                for (int k = 0; k < 2; k++) {
                    Vector3f at = pos(p, feet[k]);
                    if (contact[k] && !down[k]) {
                        start[k] = at;
                        frames[k] = 0;
                    } else if (contact[k]) {
                        frames[k]++;
                    } else if (down[k] && f > 60) {
                        // first to last contact frame, as Drift measures it
                        travel += Math.hypot(last[k].x - start[k].x, last[k].z - start[k].z);
                        time += frames[k] * dt;
                    }
                    last[k] = at;
                    down[k] = contact[k];
                }
            }
            return (float) (travel / time);
        }
    }

    @Test
    void bakedAndRuntimeAgreeAtTheKeys() throws IOException {
        try (AnimGraph graph = AnimGraph.read(ONE_CLIP); AnimGraphTest.Character player = fitted("SB_Player.sbe");
             AnimGraphTest.Character goblin = fitted("SB_Goblin.sbe"); AnimClipDocument roleWalk = LocomotionGraphTest.walk();
             AnimClipDocument walk = madeOn(player, roleWalk);
             Retargeter rt = Retargeter.create(player.rig(), player.definition(), goblin.rig(), goblin.definition());
             AnimClipDocument baked = rt.bake(walk, 30f);
             AnimGraphInstance live = AnimGraphInstance.create(graph, goblin.rig(), goblin.definition());
             AnimGraphInstance bake = AnimGraphInstance.create(graph, goblin.rig(), goblin.definition())) {
            live.bindSlotRetargeted("walk", walk, rt);
            bake.bindSlot("walk", baked);
            JointPalette pl = new JointPalette(), pb = new JointPalette();
            float worst = 0f;
            for (int k = 0; k < 60; k++) {
                live.evaluate(1f / 30f, null, pl);
                bake.evaluate(1f / 30f, null, pb);
                for (int j = 0; j < goblin.rig().jointCount(); j++) {
                    worst = Math.max(worst, diff(pl.worldMatrix(j, new Matrix4f()), pb.worldMatrix(j, new Matrix4f())));
                }
            }
            System.out.printf("[retarget] baked vs runtime at the 30 fps keys: %.3g%n", worst);
            assertTrue(worst < 1e-4f, "baked vs runtime at the keys: " + worst);
        }
    }

    @Test
    void thePlayerRetargetedOntoItselfKeepsItsPose() throws IOException {
        try (AnimGraph graph = AnimGraph.read(ONE_CLIP); AnimGraphTest.Character player = fitted("SB_Player.sbe");
             AnimClipDocument roleWalk = LocomotionGraphTest.walk();
             AnimClipDocument walk = madeOn(player, roleWalk);
             Retargeter rt = Retargeter.create(player.rig(), player.definition(), player.rig(), player.definition());
             AnimGraphInstance inst = AnimGraphInstance.create(graph, player.rig(), player.definition())) {
            assertEquals(1f, rt.scale(), 1e-6f);
            inst.bindSlot("walk", walk);
            int n = player.rig().jointCount();
            float[] src = new float[n * 9], dst = new float[n * 9];
            boolean[] driven = new boolean[n];
            JointPalette a = new JointPalette(), b = new JointPalette();
            RigPoser poser = RigPoser.local().clearExtras();
            float worst = 0f;
            int drivenCount = 0;
            for (int f = 0; f < 60; f++) {
                inst.evaluate(1f / 60f, null, a);
                inst.localPose(src);
                rt.pose(src, dst, driven);
                poser.poseLocals(player.rig(), src, null, a);
                poser.poseLocals(player.rig(), dst, null, b);
                drivenCount = 0;
                for (int j = 0; j < n; j++) {
                    if (!driven[j]) continue;
                    drivenCount++;
                    worst = Math.max(worst, diff(a.worldMatrix(j, new Matrix4f()), b.worldMatrix(j, new Matrix4f())));
                }
            }
            System.out.printf("[retarget] SB_Player onto itself: %d driven joints, worst world-matrix difference %.3g%n", drivenCount, worst);
            assertTrue(drivenCount >= 30, "driven " + drivenCount);
            assertTrue(worst < 1e-5f, "self retarget moved a joint: " + worst);
        }
    }

    private static float diff(Matrix4f a, Matrix4f b) {
        float d = 0f;
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) d = Math.max(d, Math.abs(a.get(c, r) - b.get(c, r)));
        }
        return d;
    }
}
