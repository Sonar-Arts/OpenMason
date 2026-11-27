package com.openmason.engine.rig;

import com.openmason.engine.anim.AnimClipDocument;
import com.openmason.engine.animgraph.AnimGraph;
import com.openmason.engine.animgraph.AnimGraphInstance;
import com.openmason.engine.entity.model.EntityFace;
import com.openmason.engine.entity.model.EntityGeometry;
import com.openmason.engine.entity.model.EntityPart;
import com.openmason.engine.format.oma.AnimLayerMeta;
import com.openmason.engine.format.oma.OMAReader;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.oma.ParsedMorphTrack;
import com.openmason.engine.rendering.model.draw.ClipLayers;
import com.openmason.engine.rendering.model.draw.ModelPoseSolver;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Shape-key runtime bindings (OpenMason #171): {@link RigMorphs} (create, remap, clamp, CPU apply, GPU packing), morph
 * channels on a rig, OMANIM 1.3 morph tracks through {@link RigClip} / {@link ModelPoseSolver#morphWeights} and the
 * graph read-back, and morph-then-skin on the CPU. The math is native; these tests pin the marshalling.
 */
class RigMorphsTest {

    @BeforeAll
    static void needsNative() {
        assumeTrue(CendaRig.isAvailable(), () -> "native rig: " + CendaRig.unavailableReason());
    }

    /** Four vertices; "smile" moves v1 up by 1 (normal delta +x) and v3 by 2, "blink" moves v2 by -1 in z. */
    static RigMorphs twoTargets(int vertexCount) {
        return RigMorphs.create(vertexCount, List.of("smile", "blink"),
                new float[]{0f, 0f, 1f, 0.25f, -1f, 2f},
                new int[]{0, 2, 3},
                new int[]{1, 3, 2},
                new float[]{0, 1, 0, 0, 2, 0, 0, 0, -1},
                new float[]{1, 0, 0, 0, 0, 0, 0, 0, 0});
    }

    @Test
    void targetsRowsAndClamping() {
        try (RigMorphs m = twoTargets(4)) {
            assertEquals(4, m.vertexCount());
            assertEquals(2, m.targetCount());
            assertEquals(3, m.rowCount());
            assertTrue(m.hasNormals());
            assertEquals(new RigMorphs.Target("smile", 0f, 0f, 1f), m.targets().get(0));
            assertEquals(new RigMorphs.Target("blink", 0.25f, -1f, 2f), m.targets().get(1));
            assertEquals(1, m.find("BLINK"));
            assertEquals(0, m.find("morph:smile"));
            assertEquals(-1, m.find("frown"));
            assertArrayEquals(new int[]{1, 3}, m.targetRows(0).vertices());
            // Blender's slider range: clamped when applied; NaN and missing weights read as 0.
            assertArrayEquals(new float[]{1f, -1f}, m.clampWeights(new float[]{3f, -5f}, null));
            assertArrayEquals(new float[]{0f, 0f}, m.clampWeights(new float[]{Float.NaN}, new float[2]));
            assertArrayEquals(new float[]{0f, 0.25f}, m.clampWeights(m.defaultWeights(), null));
        }
    }

    @Test
    void applyAddsTheWeightedDeltas() {
        try (RigMorphs m = twoTargets(4)) {
            float[] p = {0, 0, 0, 1, 0, 0, 2, 0, 0, 3, 0, 0};
            float[] n = {0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0};
            float[] op = new float[12], on = new float[12];
            m.apply(new float[]{0.5f, 2f}, p, n, op, on);
            assertArrayEquals(new float[]{0, 0, 0, 1, 0.5f, 0, 2, 0, -2, 3, 1, 0}, op, 1e-6f);
            // v1's normal turns by its weighted normal delta, normalized; the others are copied exactly.
            float s = (float) (1 / Math.sqrt(1.25));
            assertArrayEquals(new float[]{0.5f * s, s, 0}, java.util.Arrays.copyOfRange(on, 3, 6), 1e-6f);
            assertArrayEquals(new float[]{0, 1, 0}, java.util.Arrays.copyOfRange(on, 6, 9));
        }
    }

    @Test
    void remapCopiesASplitVerticesRows() {
        try (RigMorphs m = twoTargets(4); RigMorphs r = m.remap(5, new int[]{0, 1, 2, 3, 1})) {
            assertEquals(5, r.vertexCount());
            assertArrayEquals(new int[]{1, 3, 4}, r.targetRows(0).vertices(), "the copy of v1 moves like v1");
            float[] p = new float[15], out = new float[15];
            r.apply(new float[]{1f, 0f}, p, null, out, null);
            assertEquals(1f, out[4 * 3 + 1]);
        }
    }

    @Test
    void thePackingIsOneRangePerVertexAndOneRecordPerRow() {
        try (RigMorphs m = twoTargets(4)) {
            RigMorphs.Packed p = m.pack();
            assertEquals(3, p.recordCount());
            // v0: none; v1: one (smile); v2: one (blink); v3: one (smile) - records in vertex order.
            assertArrayEquals(new int[]{0, 0, 0, 1, 1, 1, 2, 1}, p.ranges());
            assertArrayEquals(new float[]{0, 1, 0, 0, 0, 0, -1, 1, 0, 2, 0, 0}, p.deltas());
            assertArrayEquals(new float[]{1, 0, 0, 0}, java.util.Arrays.copyOf(p.normals(), 4));
            assertTrue(p == m.pack(), "packed once");
        }
    }

    @Test
    void createRejectsBadRows() {
        assertThrows(IllegalArgumentException.class, () -> RigMorphs.create(2, List.of("a"), null,
                new int[]{0, 1}, new int[]{5}, new float[3], null));
    }

    // --- rig channels, clip tracks, pose / graph read-back -----------------------------------------------

    private static EntityGeometry quad(RigMorphs morphs) {
        float[] v = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};
        int[] idx = {0, 1, 2, 0, 2, 3};
        List<EntityPart> parts = List.of(new EntityPart("p", "body", null, new Vector3f(), new Vector3f(),
                new Vector3f(), new Vector3f(1), List.of(new EntityFace(0, 0, 0, 6))));
        RigModel rig = RigModel.build(EntityGeometry.partSpecs(parts), List.of(), null, null,
                EntityGeometry.partJointOfVertex(parts, idx, v), morphs);
        return new EntityGeometry(v, new float[8], idx, parts, Map.of(), 0f, List.of(), rig);
    }

    private static ParsedMorphTrack ramp(String name, float from, float to) {
        return new ParsedMorphTrack(name, List.of(ParsedMorphTrack.Key.linear(0f, from), ParsedMorphTrack.Key.linear(1f, to)));
    }

    @Test
    void theRigCarriesTheTargetsAsChannels() {
        RigMorphs morphs = twoTargets(4);
        EntityGeometry g = quad(morphs);
        assertTrue(g.morphs() == morphs);
        Rig rig = g.rig().rig();
        assertEquals(2, rig.morphCount());
        assertEquals("smile", rig.morphName(0));
        assertEquals(1, rig.findMorph("morph:Blink"));
        // A model without targets: no channels, no weights.
        EntityGeometry plain = quad(null);
        assertEquals(0, plain.rig().rig().morphCount());
        assertNull(plain.morphs());
        assertNull(ModelPoseSolver.morphWeights(plain, ClipLayers.REST, null));
    }

    @Test
    void clipMorphTracksReachThePoseWeights() {
        EntityGeometry g = quad(twoTargets(4));
        ParsedAnimClip clip = new ParsedAnimClip("talk", 30f, 1f, false, List.of(), AnimLayerMeta.base(),
                List.of(ramp("smile", 0f, 1f)));
        assertEquals(1, RigClip.of(clip).morphTrackCount());
        // REST = the defaults; the clip keys smile (blink keeps its default, undriven).
        assertArrayEquals(new float[]{0f, 0.25f}, ModelPoseSolver.morphWeights(g, ClipLayers.REST, null));
        assertArrayEquals(new float[]{0.5f, 0.25f}, ModelPoseSolver.morphWeights(g, ClipLayers.single(clip, 0.5f), null),
                1e-6f);
        // An overlay keying blink at half weight folds over the base like a joint would.
        ParsedAnimClip blink = new ParsedAnimClip("blink", 30f, 1f, true, List.of(),
                new AnimLayerMeta(AnimLayerMeta.LayerType.OVERLAY, List.of(), 0f, 0f, 0),
                List.of(new ParsedMorphTrack("blink", List.of(ParsedMorphTrack.Key.linear(0f, 1f)))));
        float[] w = ModelPoseSolver.morphWeights(g, new ClipLayers(clip, 0.5f,
                List.of(new ClipLayers.Overlay(blink, 0.2f, 0.5f))), new float[2]);
        assertArrayEquals(new float[]{0.5f, 0.625f}, w, 1e-6f);
        // A clip without morph tracks samples exactly as before (no channel driven).
        ParsedAnimClip plain = new ParsedAnimClip("plain", 30f, 1f, false, List.of());
        assertEquals(0, RigClip.of(plain).morphTrackCount());
        assertArrayEquals(new float[]{0f, 0.25f}, ModelPoseSolver.morphWeights(g, ClipLayers.single(plain, 0.5f), null));
    }

    @Test
    void anOmanim13ClipRoundTripsItsMorphTracks() throws Exception {
        byte[] bytes;
        try (AnimClipDocument doc = AnimClipDocument.create()) {
            doc.setName("talk");
            doc.setHeader(new AnimClipDocument.Header(30f, 1f, false, false, 0f, 0f, 0));
            doc.setMorphTrack(new AnimClipDocument.Curve("morph:smile", List.of(
                    AnimClipDocument.CurveKey.linear(0f, 0f), AnimClipDocument.CurveKey.linear(1f, 1f))));
            bytes = doc.write();
        }
        // The Java reader keeps reading 1.3 (its morph section is not its business) ...
        ParsedAnimClip parsed = new OMAReader().read(bytes);
        assertTrue(parsed.morphTracks().isEmpty());
        // ... the native document carries them, and the runtime clip samples them.
        try (AnimClipDocument doc = AnimClipDocument.read(bytes)) {
            List<ParsedMorphTrack> tracks = doc.parsedMorphTracks();
            assertEquals(1, tracks.size());
            assertEquals("smile", tracks.get(0).name());
            assertEquals(0.25f, doc.sampleMorph(0, 0.25f), 1e-6f);
            ParsedAnimClip clip = parsed.withMorphTracks(tracks);
            assertEquals(clip.morphTracks(), doc.toParsedClip().morphTracks());
            EntityGeometry g = quad(twoTargets(4));
            assertEquals(0.25f, ModelPoseSolver.morphWeights(g, ClipLayers.single(clip, 0.25f), null)[0], 1e-6f);
        }
    }

    @Test
    void theGraphReadsBackItsMorphWeights() throws Exception {
        EntityGeometry g = quad(twoTargets(4));
        String json = """
            {"format": "omanimgraph", "version": "1.0", "slots": ["talk"], "root": "c",
             "nodes": [{"id": "c", "type": "clip", "slot": "talk"}]}
            """;
        try (AnimGraph graph = AnimGraph.read(json);
             AnimGraphInstance inst = AnimGraphInstance.create(graph, g.rig().rig(), null);
             AnimClipDocument talk = AnimClipDocument.create()) {
            talk.setName("talk");
            talk.setHeader(new AnimClipDocument.Header(30f, 1f, false, false, 0f, 0f, 0));
            talk.setMorphTrack(new AnimClipDocument.Curve("smile", List.of(
                    AnimClipDocument.CurveKey.linear(0f, 0f), AnimClipDocument.CurveKey.linear(1f, 1f))));
            inst.bindSlot("talk", talk);
            JointPalette palette = new JointPalette();
            inst.evaluate(0f, null, palette);
            inst.evaluate(0.5f, null, palette);
            float[] w = new float[2];
            assertEquals(2, inst.morphWeights(AnimGraphInstance.VIEW_BODY, w));
            assertEquals(0.5f, w[0], 1e-5f, "the clip's smile track at its time");
            assertEquals(0.25f, w[1], 1e-6f, "blink keeps its default");
        }
    }

    @Test
    void morphThenSkinOnTheCpu() {
        try (RigMorphs m = twoTargets(4)) {
            EntityGeometry g = quad(m);
            float[] weights = {1f, 0f};
            JointPalette palette = ModelPoseSolver.pose(g, ClipLayers.REST,
                    new Matrix4f().translation(10, 0, 0), 0f, 0f, null, new JointPalette());
            float[] out = Skin.deformMorphed(null, g.rig().partJointOfVertex(), m, weights, g.vertices(), palette, null);
            float[] morphed = new float[12];
            m.apply(weights, g.vertices(), null, morphed, null);
            float[] expected = Skin.deform(null, g.rig().partJointOfVertex(), morphed, palette, null);
            assertArrayEquals(expected, out);
            assertEquals(11f, out[3]);
            assertEquals(1f, out[4], "v1 moved up by smile, then by the base");
            assertFalse(java.util.Arrays.equals(out, Skin.deform(null, g.rig().partJointOfVertex(), g.vertices(),
                    palette, null)));
        }
    }
}
