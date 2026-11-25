package com.openmason.engine.character;

import com.openmason.engine.character.CharacterDefinition.Chain;
import com.openmason.engine.character.CharacterDefinition.Issue;
import com.openmason.engine.character.CharacterDefinition.IssueCode;
import com.openmason.engine.character.CharacterDefinition.Metric;
import com.openmason.engine.character.CharacterDefinition.Metrics;
import com.openmason.engine.character.CharacterDefinition.Severity;
import com.openmason.engine.character.CharacterDefinition.SkeletonJoint;
import com.openmason.engine.character.CharacterDefinition.Socket;
import com.openmason.engine.character.CharacterDefinition.Vec3;
import com.openmason.engine.rig.Rig;
import com.openmason.engine.rig.RigJoint;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The FFM binding of the native {@code .omchar} character definition ({@code cenda/character.h}; the native
 * conformance suite is ctest {@code character}): every accessor through a byte-identical round trip, zip
 * interop with {@code java.util.zip} both ways, checks against a rig, metrics, and refusals.
 */
class CharacterDefinitionTest {

    private static final float EPS = 1e-5f;

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaCharacter.isAvailable(), "native character module unavailable: " + CendaCharacter.unavailableReason());
    }

    /** hips (y 1.0, tail 1.2) -> spine (tail +0.4) -> head (tail +0.25) -> eye_l / eye_r (head tail - 0.15). */
    private static Rig biped() {
        return Rig.create(List.of(
            RigJoint.bone("hips", "Hips", -1, false, null, new Vector3f(0, 1.0f, 0), null, new Vector3f(0, 0.2f, 0)),
            RigJoint.bone("spine", "Spine", 0, false, null, null, null, new Vector3f(0, 0.4f, 0)),
            RigJoint.bone("head", "Head", 1, true, null, null, null, new Vector3f(0, 0.25f, 0)),
            RigJoint.bone("eye_l", "EyeL", 2, false, null, new Vector3f(0.05f, -0.15f, 0.1f), null, new Vector3f(0, 0.02f, 0)),
            RigJoint.bone("eye_r", "EyeR", 2, false, null, new Vector3f(-0.05f, -0.15f, 0.1f), null, new Vector3f(0, 0.02f, 0))));
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) out.put(e.getName(), in.readAllBytes());
        }
        return out;
    }

    private static byte[] zip(String manifest, String extraName, String extraText) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("manifest.json"));
            out.write(manifest.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            if (extraName != null) {
                out.putNextEntry(new ZipEntry(extraName));
                out.write(extraText.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void everyFieldSurvivesAByteIdenticalRoundTrip(@TempDir Path dir) throws IOException {
        byte[] model = {'P', 'K', 3, 4, 42};
        byte[] first;
        try (Rig rig = biped(); CharacterDefinition c = CharacterDefinition.create()) {
            c.setName("Player");
            c.setObjectId("test:player");
            c.setProfile("humanoid");
            c.setAnimGraph("graphs/humanoid.omanimgraph");
            c.setModelRef("");
            c.setAppearanceJson("{\"tints\": {\"skin\": [0.8, 0.6, 0.5]}}");
            c.setSkeleton(rig);
            c.setEmbeddedModel(model, null);
            c.setRole("hips", "hips");
            c.setRole("head", "head");
            c.setReferenceRotation("spine", new Vec3(0, 0, 12.5f));
            c.setLimit("head", new Vec3(-30, -45, -10), new Vec3(30, 45, 10));
            c.setIkGoal("look", "head");
            c.setChain(new Chain("spine", "hips", "head", "look"));
            c.setSocket(new Socket("grip_r", "spine", new Vec3(0.1f, 1.5f, 0.2f), null, null));
            c.setMetrics(new Metrics(1.8f, 1.62f, 0.6f, 0.96f, Set.of(Metric.EYE_HEIGHT)));
            first = c.write();
            c.write(dir.resolve("player.omchar"));
        }

        try (CharacterDefinition back = CharacterDefinition.read(dir.resolve("player.omchar"))) {
            assertArrayEquals(first, back.write(), "write -> read -> write must be byte-identical");
            assertEquals("Player", back.name());
            assertEquals("test:player", back.objectId());
            assertEquals("humanoid", back.profile());
            assertEquals("graphs/humanoid.omanimgraph", back.animGraph());
            assertEquals("", back.modelRef());
            assertEquals("{\"tints\":{\"skin\":[0.8,0.6,0.5]}}", back.appearanceJson());
            assertTrue(back.jointHash().startsWith("fnv1a64:"));

            List<SkeletonJoint> joints = back.skeleton();
            assertEquals(5, joints.size());
            assertEquals(new SkeletonJoint("hips", "Hips", null, RigJoint.Kind.BONE), joints.get(0));
            assertEquals(new SkeletonJoint("eye_r", "EyeR", "head", RigJoint.Kind.BONE), joints.get(4));

            assertArrayEquals(model, back.embeddedModel().orElseThrow());
            assertEquals(Map.of("hips", "hips", "head", "head"), back.roles());
            assertEquals(List.of("hips", "head"), List.copyOf(back.roles().keySet()));
            assertEquals(12.5f, back.referencePose().getFirst().rotDeg().z(), EPS);
            assertEquals(-45f, back.limits().getFirst().minDeg().y(), EPS);
            assertEquals("head", back.ikGoals().getFirst().jointId());
            assertEquals(new Chain("spine", "hips", "head", "look"), back.chains().getFirst());
            Socket grip = back.sockets().getFirst();
            assertEquals(new Socket("grip_r", "spine", new Vec3(0.1f, 1.5f, 0.2f), Vec3.ZERO, Vec3.ONE), grip);
            Metrics m = back.metrics();
            assertEquals(0.96f, m.hipHeight(), EPS);
            assertEquals(Set.of(Metric.EYE_HEIGHT), m.overridden());
        }

        // The native archive is an ordinary zip: java.util.zip reads it, the model is stored as given.
        Map<String, byte[]> entries = unzip(first);
        assertArrayEquals(model, entries.get("model.omo"));
        String manifest = new String(entries.get("manifest.json"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("\"format\": \"omchar\""), manifest);
        assertTrue(manifest.contains("\"hipHeight\": 0.96"), "floats are written in single precision: " + manifest);
    }

    @Test
    void editingReplacesInPlaceAndRemoves() {
        try (CharacterDefinition c = CharacterDefinition.create()) {
            c.setRole("a", "j1");
            c.setRole("b", "j2");
            c.setRole("a", "j3");
            assertEquals(List.of("a", "b"), List.copyOf(c.roles().keySet()));
            assertEquals("j3", c.roles().get("a"));
            c.removeRole("a");
            assertEquals(Map.of("b", "j2"), c.roles());
            c.setSocket(new Socket("eyes", "head", Vec3.ZERO, null, null));
            c.removeSocket("eyes");
            assertTrue(c.sockets().isEmpty());
            c.setChain(new Chain("arm", "s", "e", null));
            assertNull(c.chains().getFirst().goal());
            c.removeChain("arm");
            assertTrue(c.chains().isEmpty());
            c.setEmbeddedModel(new byte[]{1, 2}, "skeleton.omo");
            c.setEmbeddedModel(null, null);
            assertTrue(c.embeddedModel().isEmpty());

            assertThrows(IllegalArgumentException.class, () -> c.setRole("", "j"));
            assertThrows(IllegalArgumentException.class, () -> c.setReferenceRotation("j", new Vec3(Float.NaN, 0, 0)));
            assertThrows(IllegalArgumentException.class, () -> c.setAppearanceJson("[1, 2]"));
        }
    }

    @Test
    void unknownSectionsAndEntriesFromAJavaWrittenZipSurvive() throws IOException {
        String manifest = """
            {"format": "omchar", "version": "1.3", "name": "Future",
             "futureSection": {"weights": [1, 2.5]}, "roles": {"hips": "j0"}}
            """;
        try (CharacterDefinition c = CharacterDefinition.read(zip(manifest, "notes/readme.txt", "keep me"))) {
            assertEquals("Future", c.name());
            Map<String, byte[]> out = unzip(c.write());
            String written = new String(out.get("manifest.json"), StandardCharsets.UTF_8);
            assertTrue(written.contains("\"futureSection\""), written);
            assertEquals("keep me", new String(out.get("notes/readme.txt"), StandardCharsets.UTF_8));
        }
    }

    @Test
    void refusesNewerMajorVersionsAndCorruptFiles() throws IOException {
        CharacterFormatException newer = assertThrows(CharacterFormatException.class,
            () -> CharacterDefinition.read(zip("{\"format\":\"omchar\",\"version\":\"2.0\"}", null, null)));
        assertTrue(newer.newerVersion());
        CharacterFormatException corrupt = assertThrows(CharacterFormatException.class,
            () -> CharacterDefinition.read(new byte[]{1, 2, 3}));
        assertFalse(corrupt.newerVersion());
        assertThrows(CharacterFormatException.class,
            () -> CharacterDefinition.read(zip("{\"format\":\"omo\",\"version\":\"1.0\"}", null, null)));
    }

    @Test
    void checkReportsSkeletonChangesAndBadReferences() {
        try (Rig rig = biped(); CharacterDefinition c = CharacterDefinition.create()) {
            assertTrue(c.check(null).stream().anyMatch(i -> i.code() == IssueCode.NO_SKELETON));
            c.setSkeleton(rig);
            c.setRole("hips", "hips");
            c.setChain(new Chain("spine", "hips", "head", null));
            assertEquals(List.of(), c.check(rig));

            c.setRole("tail", "no_such_joint");
            c.setChain(new Chain("backwards", "head", "hips", null));
            List<Issue> issues = c.check(rig);
            assertTrue(issues.contains(new Issue(Severity.ERROR, IssueCode.UNKNOWN_JOINT, "no_such_joint",
                "role tail names unknown joint no_such_joint")), issues.toString());
            assertTrue(issues.stream().anyMatch(i -> i.code() == IssueCode.CHAIN_ORDER && i.subject().equals("backwards")));

            // A model whose head was renamed and eyes reparented reports what changed.
            try (Rig changed = Rig.create(List.of(
                RigJoint.bone("hips", "Hips", -1, false, null, new Vector3f(0, 1.0f, 0), null, new Vector3f(0, 0.2f, 0)),
                RigJoint.bone("spine", "Spine", 0, false, null, null, null, new Vector3f(0, 0.4f, 0)),
                RigJoint.bone("head", "Skull", 1, true, null, null, null, new Vector3f(0, 0.25f, 0)),
                RigJoint.bone("eye_l", "EyeL", 1, false, null, null, null, null)))) {
                List<Issue> diff = c.check(changed);
                assertTrue(diff.stream().anyMatch(i -> i.code() == IssueCode.JOINT_RENAMED && i.subject().equals("head")));
                assertTrue(diff.stream().anyMatch(i -> i.code() == IssueCode.JOINT_REPARENTED && i.subject().equals("eye_l")));
                assertTrue(diff.stream().anyMatch(i -> i.code() == IssueCode.JOINT_MISSING && i.subject().equals("eye_r")));
            }
        }
    }

    @Test
    void metricsAreMeasuredInTheReferencePose() {
        try (Rig rig = biped(); CharacterDefinition c = CharacterDefinition.create()) {
            c.setRole("hips", "hips");
            c.setRole("eye_l", "eye_l");
            c.setRole("eye_r", "eye_r");
            Vec3 min = new Vec3(-0.3f, 0f, -0.15f);
            Vec3 max = new Vec3(0.3f, 1.85f, 0.15f);
            Metrics m = c.computeMetrics(rig, min, max);
            assertEquals(1.85f, m.height(), EPS);
            assertEquals(0.6f, m.width(), EPS);
            assertEquals(1.0f, m.hipHeight(), EPS);
            assertEquals(1.7f, m.eyeHeight(), EPS);

            // Spine turned 90 degrees about Z in the reference pose: the eyes swing down to 1.2.
            c.setReferenceRotation("spine", new Vec3(0, 0, 90));
            assertEquals(1.2f, c.computeMetrics(rig, min, max).eyeHeight(), 1e-4f);

            // An overridden metric keeps its stored value.
            c.setMetrics(new Metrics(2.5f, 0, 0, 0, Set.of(Metric.HEIGHT)));
            Metrics kept = c.computeMetrics(rig, min, max);
            assertEquals(2.5f, kept.height(), EPS);
            assertEquals(Set.of(Metric.HEIGHT), kept.overridden());
        }
    }

    @Test
    void closedDefinitionsRefuseUse() {
        CharacterDefinition c = CharacterDefinition.create();
        c.close();
        c.close();
        assertThrows(IllegalStateException.class, c::name);
    }
}
