package com.openmason.engine.rig;

import com.openmason.engine.format.oma.ParsedAnimClip;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The goblin re-rig (OpenMason #160; OpenMason CLAUDE.md "Retargeting") changed only its skeleton:
 * every shipped clip poses every part of the re-rigged asset exactly as the frozen pre-re-rig copy did (skin and
 * world matrices of all 21 parts, with the mob head look applied, at many times per clip), while the new bones
 * form a real limb skeleton (knees between hips and ankles, tails along the limbs).
 */
class GoblinRerigParityTest {

    private static final Path NEW = Path.of("../../test-fixtures/stonebreak/resources/sbe/Mobs/SB_Goblin.sbe");
    private static final Path OLD = RigAbiParityTest.FROZEN_ROOT.resolve("Mobs/SB_Goblin.sbe");

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaRig.isAvailable(), "native rig unavailable: " + CendaRig.unavailableReason());
        assumeTrue(Files.isRegularFile(NEW) && Files.isRegularFile(OLD), "goblin fixtures missing");
    }

    @Test
    void everyShippedClipPosesThePartsExactlyAsBefore() throws IOException {
        RigAbiParityTest.Fixture before = RigAbiParityTest.load(OLD, "old");
        RigAbiParityTest.Fixture after = RigAbiParityTest.load(NEW, "new");
        assertEquals(before.clips().keySet(), after.clips().keySet());
        try (Rig oldRig = before.model().rig(); Rig newRig = after.model().rig()) {
            int parts = before.model().partCount();
            assertEquals(parts, after.model().partCount());
            for (int i = 0; i < parts; i++) {
                assertEquals(oldRig.joints().get(i).id(), newRig.joints().get(i).id(), "part order kept");
            }
            int head = oldRig.headJoint();
            assertEquals(head, newRig.headJoint(), "head tracking still turns the same part");
            Matrix4f base = RigAbiParityTest.baseMatrix();
            JointPalette po = new JointPalette(), pn = new JointPalette();
            Matrix4f a = new Matrix4f(), b = new Matrix4f();
            float worst = 0f;
            int checked = 0;
            for (Map.Entry<String, ParsedAnimClip> e : before.clips().entrySet()) {
                ParsedAnimClip oldClip = e.getValue(), newClip = after.clips().get(e.getKey());
                for (int k = 0; k <= 48; k++) {
                    float t = oldClip.duration() * k / 48f;
                    for (boolean look : new boolean[]{false, true}) {
                        RigPoser poser = RigPoser.local().clearExtras();
                        if (look) poser.extra(head, 23f, -11f, 0f, 0f, 0f);
                        poser.pose(oldRig, oldClip, t, List.of(), base, po);
                        poser.pose(newRig, newClip, t, List.of(), base, pn);
                        poser.clearExtras();
                        for (int j = 0; j < parts; j++) {
                            worst = Math.max(worst, diff(po.skinMatrix(j, a), pn.skinMatrix(j, b)));
                            worst = Math.max(worst, diff(po.worldMatrix(j, a), pn.worldMatrix(j, b)));
                            checked++;
                        }
                    }
                }
            }
            System.out.printf("[goblin-rerig] %d part poses over %d clips: worst matrix difference %.3g%n", checked,
                before.clips().size(), worst);
            assertTrue(worst < 2e-6f, "a part moved: " + worst);
        }
    }

    @Test
    void theNewBonesAreARealLimbSkeleton() throws IOException {
        RigAbiParityTest.Fixture after = RigAbiParityTest.load(NEW, "new");
        try (Rig rig = after.model().rig()) {
            int n = rig.jointCount();
            Matrix4f[] world = new Matrix4f[n], child = new Matrix4f[n];
            for (int i = 0; i < n; i++) {
                world[i] = new Matrix4f();
                child[i] = new Matrix4f();
            }
            rig.restWorld(world, child);
            for (String side : List.of("l", "r")) {
                float hip = y(world, rig, "hip_" + side), knee = y(world, rig, "knee_" + side), ankle = y(world, rig, "ankle_" + side);
                assertTrue(hip > knee && knee > ankle, side + " leg: hip " + hip + " > knee " + knee + " > ankle " + ankle);
                float shoulder = y(world, rig, "shoulder_" + side), elbow = y(world, rig, "elbow_" + side),
                    wrist = y(world, rig, "wrist_" + side);
                assertTrue(shoulder > elbow && elbow > wrist, side + " arm");
                // A limb bone's tail is the next joint's head.
                assertTrue(child[rig.find("hip_" + side)].getTranslation(new org.joml.Vector3f())
                    .distance(world[rig.find("knee_" + side)].getTranslation(new org.joml.Vector3f())) < 1e-5f);
            }
        }
    }

    private static float y(Matrix4f[] world, Rig rig, String bone) {
        int j = rig.find(bone);
        assertTrue(j >= 0 && rig.joints().get(j).kind() == RigJoint.Kind.BONE, bone + " is a bone");
        return world[j].m31();
    }

    private static float diff(Matrix4f a, Matrix4f b) {
        float d = 0f;
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) d = Math.max(d, Math.abs(a.get(c, r) - b.get(c, r)));
        }
        return d;
    }
}
