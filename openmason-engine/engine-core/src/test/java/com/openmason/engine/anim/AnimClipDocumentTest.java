package com.openmason.engine.anim;

import com.openmason.engine.anim.AnimClipDocument.Additive;
import com.openmason.engine.anim.AnimClipDocument.AdditiveReference;
import com.openmason.engine.anim.AnimClipDocument.Bound;
import com.openmason.engine.anim.AnimClipDocument.Curve;
import com.openmason.engine.anim.AnimClipDocument.CurveKey;
import com.openmason.engine.anim.AnimClipDocument.Fired;
import com.openmason.engine.anim.AnimClipDocument.Header;
import com.openmason.engine.anim.AnimClipDocument.Interp;
import com.openmason.engine.anim.AnimClipDocument.Notify;
import com.openmason.engine.anim.AnimClipDocument.Phase;
import com.openmason.engine.anim.AnimClipDocument.RootAxis;
import com.openmason.engine.anim.AnimClipDocument.RootMotion;
import com.openmason.engine.anim.AnimClipDocument.SyncMarker;
import com.openmason.engine.anim.AnimClipDocument.Track;
import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.format.oma.OMAReader;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.oma.ParsedAnimTrack;
import com.openmason.engine.format.oma.ParsedKeyframe;
import com.openmason.engine.rig.Rig;
import com.openmason.engine.rig.RigJoint;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OpenMason #155: the native {@code .omanim} 1.2 document. Acceptance: every clip of every fixture SBE, read with
 * the 1.1 Java reader before and after a native 1.2 rewrite, keeps every key bit-identical (the old reader also
 * proves 1.2 stays readable by 1.1 code). Plus the 1.2 sections, notify firing and role binding through the binding
 * (the native conformance suite is ctest {@code anim_clip}).
 */
class AnimClipDocumentTest {

    private static final Path SBE = Path.of("../../test-fixtures/stonebreak/resources/sbe");

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaAnimClip.isAvailable(), "native animation clips unavailable: " + CendaAnimClip.unavailableReason());
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws IOException {
        Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) out.put(e.getName(), in.readAllBytes());
        }
        return out;
    }

    private static void assertBits(float expected, float actual, String what) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual), what + ": " + expected + " vs " + actual);
    }

    private static void assertVec(Vector3f a, Vector3f b, String what) {
        assertBits(a.x, b.x, what + ".x");
        assertBits(a.y, b.y, what + ".y");
        assertBits(a.z, b.z, what + ".z");
    }

    /** Every field the 1.1 reader exposes, floats bit for bit. */
    private static void assertSameClip(ParsedAnimClip a, ParsedAnimClip b, String where) {
        assertEquals(a.name(), b.name(), where);
        assertBits(a.fps(), b.fps(), where + " fps");
        assertBits(a.duration(), b.duration(), where + " duration");
        assertEquals(a.loop(), b.loop(), where);
        assertEquals(a.layer().type(), b.layer().type(), where);
        assertEquals(a.layer().maskParts(), b.layer().maskParts(), where);
        assertBits(a.layer().fadeInSeconds(), b.layer().fadeInSeconds(), where + " fadeIn");
        assertBits(a.layer().fadeOutSeconds(), b.layer().fadeOutSeconds(), where + " fadeOut");
        assertEquals(a.layer().priority(), b.layer().priority(), where);
        assertEquals(a.tracks().size(), b.tracks().size(), where);
        for (int t = 0; t < a.tracks().size(); t++) {
            ParsedAnimTrack x = a.tracks().get(t), y = b.tracks().get(t);
            String tw = where + " track " + x.partName();
            assertEquals(x.partId(), y.partId(), tw);
            assertEquals(x.partName(), y.partName(), tw);
            assertEquals(x.keyframes().size(), y.keyframes().size(), tw);
            for (int k = 0; k < x.keyframes().size(); k++) {
                ParsedKeyframe p = x.keyframes().get(k), q = y.keyframes().get(k);
                String kw = tw + " key " + k;
                assertBits(p.time(), q.time(), kw + " time");
                assertVec(p.position(), q.position(), kw + " position");
                assertVec(p.rotation(), q.rotation(), kw + " rotation");
                assertVec(p.scale(), q.scale(), kw + " scale");
                assertEquals(p.easing(), q.easing(), kw);
            }
        }
    }

    @Test
    void everyFixtureClipKeepsEveryKeyBitIdenticalThroughA12Rewrite() throws IOException {
        assumeTrue(Files.isDirectory(SBE), "fixtures missing at " + SBE.toAbsolutePath());
        List<Path> sbes;
        try (Stream<Path> walk = Files.walk(SBE)) {
            sbes = walk.filter(p -> p.toString().endsWith(".sbe")).sorted().toList();
        }
        OMAReader reader = new OMAReader();
        int clips = 0;
        for (Path sbe : sbes) {
            for (Map.Entry<String, byte[]> e : unzip(Files.readAllBytes(sbe)).entrySet()) {
                if (!e.getKey().endsWith(".omanim")) continue;
                String where = sbe.getFileName() + "/" + e.getKey();
                ParsedAnimClip original = reader.read(e.getValue());
                try (AnimClipDocument doc = AnimClipDocument.read(e.getValue())) {
                    byte[] v12 = doc.write();
                    assertTrue(new String(unzip(v12).get("manifest.json"), StandardCharsets.UTF_8).contains("\"version\": \"1.2\""), where);
                    assertSameClip(original, reader.read(v12), where + " (1.2 via the 1.1 reader)");
                    assertSameClip(original, doc.toParsedClip(), where + " (toParsedClip)");
                }
                clips++;
            }
        }
        assertTrue(clips > 50, "only " + clips + " fixture clips");
    }

    @Test
    void sectionsRoundTrip() throws IOException {
        byte[] bytes;
        ParsedKeyframe key = new ParsedKeyframe(0.25f, new Vector3f(0, 1, 0), new Vector3f(0, 45, 0), null, "EASE_OUT");
        try (AnimClipDocument doc = AnimClipDocument.create()) {
            doc.setName("Walk");
            doc.setHeader(new Header(30f, 1f, true, false, 0.1f, 0.1f, 0));
            doc.setTrack(new Track("j-root", "Root", null, true, List.of(key)));
            doc.setTrack(Track.forRole("upper_arm_l", true, List.of(key)));
            doc.addNotify(new Notify("footstep", 0.5f, 0f, "{\"foot\": \"left\"}"));
            doc.addNotify(new Notify("swing", 0.2f, 0.3f, null));
            doc.setCurve(new Curve("foot_lock_l", List.of(new CurveKey(0f, 0f, 0f, 2f, Interp.CUBIC), CurveKey.linear(1f, 1f))));
            doc.addMarker(new SyncMarker("foot_down_r", 0.6f));
            doc.addMarker(new SyncMarker("foot_down_l", 0.1f));
            doc.setRootMotion(new RootMotion("role:hips", Set.of(RootAxis.X, RootAxis.Z, RootAxis.YAW)));
            doc.setAdditive(new Additive(AdditiveReference.FRAME, 0f, null, false));
            bytes = doc.write();
        }
        try (AnimClipDocument back = AnimClipDocument.read(bytes)) {
            assertEquals("1.2", back.version());
            assertTrue(back.modelRef().isEmpty());
            List<Track> tracks = back.tracks();
            assertEquals("upper_arm_l", tracks.get(1).role());
            assertEquals("role:upper_arm_l", tracks.get(1).partId());
            assertEquals("EASE_OUT", tracks.get(0).keys().getFirst().easing());
            assertEquals(List.of(new Notify("swing", 0.2f, 0.3f, null), new Notify("footstep", 0.5f, 0f, "{\"foot\":\"left\"}")),
                back.notifies());
            assertEquals(Interp.CUBIC, back.curves().getFirst().keys().getFirst().interp());
            assertEquals(List.of(new SyncMarker("foot_down_l", 0.1f), new SyncMarker("foot_down_r", 0.6f)), back.markers());
            assertEquals(Set.of(RootAxis.X, RootAxis.Z, RootAxis.YAW), back.rootMotion().orElseThrow().axes());
            assertEquals(AdditiveReference.FRAME, back.additive().reference());
            assertTrue(java.util.Arrays.equals(bytes, back.write()), "1.2 -> 1.2 is byte-identical");

            assertEquals(0.5f, back.sampleCurve(back.curveIndex("foot_lock_l"), 1f) - 0.5f, 1e-6f);
            AnimClipDocument.MarkerPosition at = back.markerPosition(0.35f);
            assertEquals(0, at.marker());
            assertEquals(0.5f, at.fraction(), 1e-5f);
            assertEquals(0.35f, back.markerTime(0, 0.5f), 1e-5f);
        }
        // The 1.1 reader still reads it: it sees the joint track and skips nothing it understands.
        ParsedAnimClip old = new OMAReader().read(bytes);
        assertEquals(2, old.tracks().size());
        assertEquals("role:upper_arm_l", old.tracks().get(1).partId());
    }

    @Test
    void notifiesFireAcrossWrapsReversePlayAndLongSteps() {
        try (AnimClipDocument doc = AnimClipDocument.create()) {
            doc.setHeader(new Header(30f, 1f, true, false, 0.1f, 0.1f, 0));
            doc.addNotify(new Notify("A", 0f, 0f, null));
            doc.addNotify(new Notify("B", 0.5f, 0f, null));
            doc.addNotify(new Notify("R", 0.8f, 0.4f, null));
            List<Fired> first = doc.fire(0f, 0.25f);
            assertEquals(2, first.size());
            assertEquals(new Fired(0, Phase.POINT, 0f), first.get(0));
            assertEquals(2, first.get(1).notifyIndex());
            assertEquals(Phase.END, first.get(1).phase());
            assertEquals(0.2f, first.get(1).time(), 1e-6f);  // 0.8 + 0.4 wrapped
            assertEquals(List.of(new Fired(0, Phase.POINT, 0f)), doc.fire(0.9f, 0.2f));
            assertEquals(List.of(new Fired(1, Phase.POINT, 0.5f)), doc.fire(0.6f, -0.2f));
            assertEquals(4, doc.fire(0.3f, 7.5f).size());
            assertEquals(List.of(), doc.fire(0.25f, 0.25f));
        }
    }

    @Test
    void roleTracksBindToACharactersJoints() {
        try (Rig rig = Rig.create(List.of(
                RigJoint.bone("j-hips", "Hips", -1, false, null, null, null, null),
                RigJoint.bone("j-arm", "LeftArm", 0, true, null, null, null, null)));
             CharacterDefinition character = CharacterDefinition.create();
             AnimClipDocument doc = AnimClipDocument.create()) {
            character.setSkeleton(rig);
            character.setRole("hips", "j-hips");
            character.setRole("upper_arm_l", "j-arm");
            ParsedKeyframe key = new ParsedKeyframe(0f, null, new Vector3f(0, 0, 30), null, null);
            doc.setTrack(Track.forRole("upper_arm_l", true, List.of(key)));
            doc.setTrack(Track.forRole("tail_1", false, List.of(key)));
            doc.setRootMotion(new RootMotion("role:hips", Set.of(RootAxis.X)));

            Bound bound = doc.bindRoles(character);
            try (AnimClipDocument clip = bound.clip()) {
                assertEquals(1, bound.droppedTracks());
                Track t = clip.tracks().getFirst();
                assertEquals("j-arm", t.partId());
                assertEquals("LeftArm", t.partName());
                assertEquals(null, t.role());
                assertEquals("j-hips", clip.rootMotion().orElseThrow().joint());
                // Bound, the clip feeds the existing runtime unchanged.
                assertEquals("j-arm", clip.toParsedClip().tracks().getFirst().partId());
                try (AnimClipDocument portable = clip.toRoles(character)) {
                    assertEquals("upper_arm_l", portable.tracks().getFirst().role());
                }
            }
        }
    }

    @Test
    void malformedArchivesAreRefused() {
        IOException e = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
            () -> AnimClipDocument.read(new byte[]{1, 2, 3}));
        assertTrue(e.getMessage().contains(".omanim") || e.getMessage().contains("zip"), e.getMessage());
        List<String> none = new ArrayList<>();
        try (AnimClipDocument doc = AnimClipDocument.create()) {
            doc.setMask(none);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> doc.addNotify(new Notify("bad", 0f, -1f, null)));
        }
    }
}
