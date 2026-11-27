package com.openmason.engine.rig;

import com.openmason.engine.format.oma.AnimLayerMeta;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.oma.ParsedAnimTrack;
import com.openmason.engine.format.oma.ParsedKeyframe;
import com.openmason.engine.format.omo.OMOFormat;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The FFM binding of the native rig ({@code cenda/rig.h}): rigs over parts + bones built from OMO
 * records, clip posing through {@link RigPoser}, palettes, explicit-local posing, and the skin-weight
 * operations through {@link Skin} (the native conformance suite is ctest {@code rig}).
 */
class RigBindingTest {

    private static final float EPS = 1e-5f;

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaRig.isAvailable(), "native rig unavailable: " + CendaRig.unavailableReason());
    }

    /** A body part, a spine bone under it (head at y=1, tail +1), an arm part hanging under the bone. */
    private static RigModel model(OMOFormat.SkinData skin) {
        List<RigModel.PartSpec> parts = List.of(
                new RigModel.PartSpec("body", "body", null, true,
                        new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(1)),
                new RigModel.PartSpec("arm", "arm", "spine", true,
                        new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(1)));
        List<OMOFormat.BoneEntry> bones = List.of(new OMOFormat.BoneEntry("spine", "spine", "body",
                0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0));
        // 4 vertices: 0,1 body; 2,3 arm. Arm part's vertices start at 2.
        return RigModel.build(parts, bones, skin, new int[]{0, 2}, new int[]{0, 0, 1, 1});
    }

    @Test
    void partsComeFirstThenBonesAndPartsMayHangUnderBones() {
        RigModel m = model(null);
        assertEquals(3, m.jointCount());
        assertEquals(2, m.partCount());
        assertEquals(2, m.jointOf("spine"));
        assertEquals(RigJoint.Kind.BONE, m.joints().get(2).kind());
        assertEquals(2, m.joints().get(1).parent(), "arm hangs under the spine bone");
        assertEquals(0, m.joints().get(2).parent(), "spine hangs under the body part");
        assertTrue(m.isSkeletal());

        Matrix4f[] world = new Matrix4f[3];
        Matrix4f[] child = new Matrix4f[3];
        m.rig().restWorld(world, child);
        assertEquals(1f, world[2].m31(), EPS);   // spine head
        assertEquals(2f, child[2].m31(), EPS);   // spine tail
        assertEquals(2f, world[1].m31(), EPS);   // arm sits at the tail
        assertEquals(-1, m.rig().find("missing"));
        assertEquals(2, m.rig().find("SPINE"));
    }

    @Test
    void aBoneClipSwingsThePartUnderIt() {
        RigModel m = model(null);
        ParsedAnimClip clip = new ParsedAnimClip("bend", 30f, 1f, true, List.of(
                new ParsedAnimTrack("spine", "spine", List.of(
                        new ParsedKeyframe(0f, new Vector3f(0, 1, 0), new Vector3f(0, 0, 90), new Vector3f(1), "LINEAR")))));
        JointPalette palette = RigPoser.local().clearExtras().pose(m.rig(), clip, 0.5f, List.of(), null, new JointPalette());
        Vector3f armVertex = palette.skinMatrix(1, new Matrix4f()).transformPosition(new Vector3f(0, 2, 0));
        assertEquals(-1f, armVertex.x, EPS);
        assertEquals(1f, armVertex.y, EPS);
        // The body part is untouched.
        Vector3f bodyVertex = palette.skinMatrix(0, new Matrix4f()).transformPosition(new Vector3f(0, 2, 0));
        assertEquals(0f, bodyVertex.x, EPS);
        assertEquals(2f, bodyVertex.y, EPS);

        // The same pose from explicit locals.
        float[] locals = new float[27];
        for (int j = 0; j < 3; j++) locals[j * 9 + 6] = locals[j * 9 + 7] = locals[j * 9 + 8] = 1f;
        locals[2 * 9 + 1] = 1f;  // spine pos.y (rest)
        locals[2 * 9 + 5] = 90f; // spine rot.z
        JointPalette fromLocals = RigPoser.local().poseLocals(m.rig(), locals, null, new JointPalette());
        Matrix4f a = palette.skinMatrix(1, new Matrix4f());
        Matrix4f b = fromLocals.skinMatrix(1, new Matrix4f());
        assertTrue(a.equals(b, EPS));

        // Local pose readback.
        float[] local = RigPoser.local().localPose(m.rig(), clip, 0.5f, List.of(), new float[27]);
        assertEquals(90f, local[2 * 9 + 5], EPS);
    }

    @Test
    void overlaysFadeAndRespectMasks() {
        RigModel m = model(null);
        ParsedAnimClip over = new ParsedAnimClip("wave", 30f, 2f, false, List.of(
                new ParsedAnimTrack("arm", "arm", List.of(
                        new ParsedKeyframe(0f, null, new Vector3f(90, 0, 0), null, "LINEAR")))),
                new AnimLayerMeta(AnimLayerMeta.LayerType.OVERLAY, List.of("ARM"), 0.5f, 0.5f, 0));
        RigClip rc = RigClip.of(over);
        assertTrue(rc == RigClip.of(over), "cached by identity");
        assertEquals(0.5f, rc.weightAt(0.25f), EPS);
        assertEquals(1, rc.trackCount());
        assertEquals(0, rc.trackFor(m.rig(), 1));
        assertEquals(-1, rc.trackFor(m.rig(), 0));
        float[] local = RigPoser.local().localPose(m.rig(), null, 0f,
                List.of(new RigPoser.Overlay(over, 0.25f, 1f)), new float[27]);
        assertEquals(45f, local[9 + 3], EPS);
        assertEquals(1.5f, RigClip.wrapTime(3.5f, 2f, true), EPS);
    }

    @Test
    void headExtrasTurnTheHeadJoint() {
        List<RigModel.PartSpec> parts = List.of(new RigModel.PartSpec("h", "Head", null, true,
                new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(1)));
        RigModel m = RigModel.ofParts(parts, new int[]{0});
        assertEquals(0, m.rig().headJoint());
        JointPalette p = RigPoser.local().clearExtras().extra(0, 90f, 0f, 0, 0, 0)
                .pose(m.rig(), null, 0f, List.of(), null, new JointPalette());
        RigPoser.local().clearExtras();
        Vector3f v = p.skinMatrix(0, new Matrix4f()).transformPosition(new Vector3f(1, 0, 0));
        assertEquals(0f, v.x, EPS);
        assertEquals(-1f, v.z, EPS);
    }

    @Test
    void skinDataLoadsPerPartAndPacksForTheGpu() {
        OMOFormat.SkinData skin = new OMOFormat.SkinData(List.of("spine", "body"), List.of(
                new OMOFormat.PartSkin("arm", List.of(
                        new OMOFormat.SkinVertex(1, new int[]{0, 1}, new float[]{3f, 1f})))));
        RigModel m = model(skin);
        assertEquals(1, m.influences().size());
        Skin.Influence inf = m.influences().get(0);
        assertEquals(3, inf.vertex(), "arm local vertex 1 = combined vertex 2+1");
        PackedSkin packed = m.packed();
        assertEquals(4, packed.vertexCount());
        assertEquals(5, packed.influenceCount(), "3 rigid vertices + 2 influences");
        // Vertex 0: rigid to body (joint 0): one influence of weight 65535.
        assertArrayEquals(new int[]{0, 1}, java.util.Arrays.copyOfRange(packed.ranges(), 0, 2));
        assertEquals(0, packed.influences()[0]);
        assertEquals(65535, Short.toUnsignedInt(packed.influences()[1]));
        // Vertex 3: spine (joint 2) 0.75, body (joint 0) 0.25 - records 3 and 4, unorm16 summing to 65535.
        assertArrayEquals(new int[]{3, 2}, java.util.Arrays.copyOfRange(packed.ranges(), 6, 8));
        assertEquals(2, packed.influences()[6]);
        assertEquals(0, packed.influences()[8]);
        int w0 = Short.toUnsignedInt(packed.influences()[7]), w1 = Short.toUnsignedInt(packed.influences()[9]);
        assertEquals(65535, w0 + w1);
        assertEquals(49151, w0);
        assertTrue(m.joints().get(2).hasGeometry(), "the spine moves skinned geometry");
    }

    @Test
    void skinOperationsRoundTripThroughTheBinding() {
        RigModel m = model(null);
        float[] positions = {0, 0, 0, 0, 1, 0, 0, 2, 0, 0, 3, 0};
        try (Skin skin = Skin.create(4)) {
            assertEquals(4, skin.vertexCount());
            skin.set(1, new int[]{0, 2}, new float[]{1f, 1f});
            Skin.Influence got = skin.get(1);
            assertEquals(2, got.joints().length);
            assertEquals(0.5f, got.weights()[0], EPS);
            skin.autoWeights(m.rig(), positions, 2, 2, new int[]{2}, 0f, 0f, Skin.UNLIMITED_INFLUENCES);
            assertArrayEquals(new int[]{2}, skin.get(3).joints());
            float[] heat = skin.jointWeights(2, m.partJointOfVertex());
            assertEquals(0.5f, heat[1], EPS);
            assertEquals(1f, heat[3], EPS);
            int changed = skin.paint(positions, m.partJointOfVertex(), null, null, 0, 0, 0, 0.5f, 2,
                    Skin.PaintMode.REPLACE, 1f);
            assertEquals(1, changed);
            assertEquals(1f, skin.jointWeights(2, m.partJointOfVertex())[0], EPS);
            assertEquals(4, skin.exportInfluences().size()); // 1 set, 2+3 auto, 0 painted
            assertTrue(skin.removeJoint(2) >= 1);
            try (Skin copy = skin.copy()) {
                assertEquals(skin.weightedCount(), copy.weightedCount());
            }
            try (Skin moved = skin.transfer(positions, new float[]{0, 1.1f, 0})) {
                assertEquals(1, moved.vertexCount());
                assertEquals(skin.get(1).joints().length, moved.get(0).joints().length);
            }
            // CPU deform at rest = identity.
            JointPalette rest = RigPoser.local().clearExtras().pose(m.rig(), null, 0f, List.of(), null, new JointPalette());
            float[] out = Skin.deform(skin, m.partJointOfVertex(), positions, rest, null);
            assertArrayEquals(positions, out, EPS);
        }
    }

    @Test
    void cyclesAreCutNotFatal() {
        List<RigModel.PartSpec> parts = List.of(
                new RigModel.PartSpec("a", "a", "b", true, null, new Vector3f(), new Vector3f(), new Vector3f(1)),
                new RigModel.PartSpec("b", "b", "a", true, null, new Vector3f(), new Vector3f(), new Vector3f(1)));
        RigModel m = RigModel.ofParts(parts, new int[0]);
        assertTrue((m.rig().flags() & Rig.FLAG_CYCLE_BROKEN) != 0);
    }

    /** A body part with a chain of 9 bones (joints 1..9), each posed a little further. */
    private static RigModel chainOfNine() {
        List<RigModel.PartSpec> parts = List.of(new RigModel.PartSpec("body", "body", null, true,
                new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(1)));
        List<OMOFormat.BoneEntry> bones = new java.util.ArrayList<>();
        String parent = "body";
        for (int b = 0; b < 9; b++) {
            String id = "b" + b;
            bones.add(new OMOFormat.BoneEntry(id, id, parent, 0, 0, 0, 0.1f * b, 0.5f, 0, 0, 0, 0, 0, 0.5f, 0));
            parent = id;
        }
        return RigModel.build(parts, bones, null, new int[]{0}, new int[]{0, 0, 0});
    }

    @Test
    void nineInfluencesPackAndDeformThroughTheBinding() {
        RigModel m = chainOfNine();
        assertEquals(10, m.jointCount());
        int[] joints = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        float[] weights = {0.02f, 0.03f, 0.05f, 0.07f, 0.09f, 0.11f, 0.13f, 0.2f, 0.3f};
        float[] positions = {0.3f, 2.1f, -0.2f, 0.5f, 0.5f, 0.5f, -1f, 3f, 0.25f};
        try (Skin skin = Skin.create(3)) {
            // Lightest first, as a file may list them: every influence is kept (ABI 2 kept the first 4 in file
            // order through the 40-byte entries), heaviest first after the round trip.
            skin.importInfluences(List.of(new Skin.Influence(0, joints, weights),
                    new Skin.Influence(2, new int[]{3, 7}, new float[]{1f, 3f})));
            Skin.Influence got = skin.get(0);
            assertEquals(9, got.joints().length);
            assertEquals(9, skin.influenceCount(0));
            assertEquals(9, skin.maxInfluences());
            assertEquals(11, skin.influenceTotal());
            assertEquals(9, got.joints()[0], "heaviest first");
            assertEquals(1, got.joints()[8]);
            float sum = 0;
            for (float w : got.weights()) sum += w;
            assertEquals(1f, sum, EPS);
            try (Skin copy = Skin.of(3, skin.exportInfluences())) {
                assertArrayEquals(got.joints(), copy.get(0).joints());
                assertArrayEquals(got.weights(), copy.get(0).weights(), 0f);
                assertArrayEquals(new int[]{7, 3}, copy.get(2).joints());
            }

            int[] partJoint = {0, 0, 0};
            PackedSkin packed = skin.pack(partJoint);
            assertEquals(3, packed.vertexCount());
            assertEquals(12, packed.influenceCount(), "9 + the unweighted vertex's part joint + 2");
            assertArrayEquals(new int[]{0, 9, 9, 1, 10, 2}, packed.ranges());
            int qSum = 0;
            for (int k = 0; k < 9; k++) qSum += Short.toUnsignedInt(packed.influences()[k * 2 + 1]);
            assertEquals(65535, qSum, "unorm16 weights sum to exactly 1");
            assertEquals(0, packed.influences()[18], "unweighted: rigid to its part joint");
            assertEquals(65535, Short.toUnsignedInt(packed.influences()[19]));

            // CPU deform = the GPU blend: sum (q / 65535) K(joint) v with the packed weights.
            float[] locals = RigPoser.local().localPose(m.rig(), null, 0f, List.of(), new float[m.jointCount() * 9]);
            for (int j = 1; j < m.jointCount(); j++) {
                locals[j * 9 + 3] += 7f * j;
                locals[j * 9 + 5] += 3f * j;
            }
            JointPalette palette = RigPoser.local().poseLocals(m.rig(), locals, null, new JointPalette());
            float[] deformed = Skin.deform(skin, partJoint, positions, palette, null);
            for (int v = 0; v < 3; v++) {
                int first = packed.ranges()[v * 2], count = packed.ranges()[v * 2 + 1];
                Vector3f expected = new Vector3f();
                for (int k = 0; k < count; k++) {
                    int joint = Short.toUnsignedInt(packed.influences()[(first + k) * 2]);
                    float w = Short.toUnsignedInt(packed.influences()[(first + k) * 2 + 1]) / 65535f;
                    Vector3f p = palette.skinMatrix(joint, new Matrix4f())
                            .transformPosition(new Vector3f(positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2]));
                    expected.fma(w, p);
                }
                assertEquals(expected.x, deformed[v * 3], 1e-4f, "vertex " + v);
                assertEquals(expected.y, deformed[v * 3 + 1], 1e-4f, "vertex " + v);
                assertEquals(expected.z, deformed[v * 3 + 2], 1e-4f, "vertex " + v);
            }
        }
    }

    @Test
    void autoWeightsUseEveryBoneUnlessLimited() {
        RigModel m = chainOfNine();
        float[] positions = {0.2f, 2.0f, 0.1f};
        int[] bones = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        try (Skin all = Skin.create(1); Skin four = Skin.create(1)) {
            all.autoWeights(m.rig(), positions, 0, 1, bones, 1f, 0f, Skin.UNLIMITED_INFLUENCES);
            four.autoWeights(m.rig(), positions, 0, 1, bones, 1f, 0f, 4);
            assertEquals(9, all.maxInfluences(), "no cap: every bone");
            assertEquals(4, four.maxInfluences(), "max 4 = the ABI 2 weights");
        }
    }
}
