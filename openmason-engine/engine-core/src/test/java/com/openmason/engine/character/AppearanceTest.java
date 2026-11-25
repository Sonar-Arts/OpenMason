package com.openmason.engine.character;

import com.openmason.engine.entity.model.EntityGeometry;
import com.openmason.engine.entity.model.EntityModelLoader;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.rig.BoneMask;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.PackedSkin;
import com.openmason.engine.rig.Rig;
import com.openmason.engine.rig.RigJoint;
import com.openmason.engine.rig.RigPoser;
import com.openmason.engine.rig.Skin;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OpenMason #162 through the Java binding: the {@link Look} record (both encodings, unknown slots, patches, newer
 * versions), {@link Appearance} on the re-rigged goblin (its parts by id, a head socket, equipment hiding a group,
 * a first-person view mask), the authored-hidden flag reaching the rig, and a {@link Garment} (a sleeve skinned to
 * the goblin's arm bones) drawn with the body's own palette. The acceptance with a skinned test body through every
 * clip is the native {@code appearance} ctest; the GL path is {@code AppearanceGlTest}.
 */
class AppearanceTest {

    private static final Path GOBLIN = Path.of("../../test-fixtures/stonebreak/resources/sbe/Mobs/SB_Goblin.sbe");

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaCharacter.isAvailable(), "native character module unavailable: " + CendaCharacter.unavailableReason());
    }

    @Test
    void theRecordRoundTripsKeepsUnknownSlotsAndPatches() {
        try (Look look = Look.create()) {
            look.setCharacter("stonebreak:player").setColor("skin", 0.8f, 0.6f, 0.4f).setBool("hair", false)
                .setString("head", "iron_helmet").setNumber("height", 1.02f).setJson("future", "{\"a\": [1, 2]}");
            assertThrows(IllegalArgumentException.class, () -> look.setString("x", "#aabbcc"));
            byte[] json = look.writeJson();
            byte[] bin = look.writeBinary();
            assertTrue(bin.length < json.length / 2, bin.length + " vs " + json.length);
            try (Look a = Look.read(json); Look b = Look.read(bin)) {
                assertTrue(a.sameAs(look) && b.sameAs(look));
                assertArrayEquals(json, b.writeJson());
                assertArrayEquals(bin, a.writeBinary());
                Look.Slot skin = b.get("skin");
                assertEquals(Look.COLOR, skin.type());
                assertEquals(204 / 255f, skin.color()[0], 1e-6f);
                assertEquals("{\"a\":[1,2]}", b.get("future").text());
                assertEquals("stonebreak:player", b.character());
            }
            try (Look to = look.copy().setBool("hair", true).remove("height");
                 Look patch = look.diff(to); Look sent = Look.read(patch.writeBinary()); Look applied = look.copy()) {
                assertEquals(2, patch.count());
                assertEquals(Look.REMOVE, patch.get("height").type());
                assertTrue(applied.apply(sent).sameAs(to));
            }
        }
        Look.LookFormatException newer = assertThrows(Look.LookFormatException.class,
            () -> Look.read("{\"format\": \"omlook\", \"version\": \"3.0\"}".getBytes(StandardCharsets.UTF_8)));
        assertTrue(newer.newerVersion());
        assertThrows(Look.LookFormatException.class, () -> Look.read(new byte[]{'O', 'L', 1}));
    }

    // ---- the goblin ----------------------------------------------------------------------------------

    private static EntityGeometry goblin() throws Exception {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(GOBLIN))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().equals("model.omo")) {
                    return EntityModelLoader.buildGeometry(new OMOReader().read(new ByteArrayInputStream(in.readAllBytes())));
                }
            }
        }
        throw new AssertionError("no model.omo in the goblin");
    }

    private static String id(Rig rig, String name) {
        return rig.joints().get(rig.find(name)).id();
    }

    private static boolean bit(long[] bits, int j) {
        return (bits[j >>> 6] & (1L << (j & 63))) != 0;
    }

    @Test
    void theGoblinResolvesTintsGroupsAndEquipment() throws Exception {
        assumeTrue(Files.isRegularFile(GOBLIN), "goblin fixture missing");
        EntityGeometry geometry = goblin();
        Rig rig = geometry.rig().rig();
        try (CharacterProfile humanoid = CharacterProfile.builtin("humanoid"); CharacterDefinition def = CharacterDefinition.create()) {
            def.setSkeleton(rig);
            def.autoMap(rig, humanoid);
            def.setSocket(new CharacterDefinition.Socket("head", id(rig, "head"), new CharacterDefinition.Vec3(0, 1.2f, 0),
                new CharacterDefinition.Vec3(0, 0, 0), new CharacterDefinition.Vec3(1, 1, 1)));
            def.setAppearanceJson("""
                {"tints": [{"name": "skin", "default": "#5a8040"}],
                 "materials": [{"id": 0, "tint": "skin"}],
                 "groups": [{"name": "ears", "parts": ["%s", "%s"]}],
                 "equipment": [{"name": "helmet", "socket": "head", "hides": ["ears"]}]}
                """.formatted(id(rig, "ear_l"), id(rig, "ear_r")));
            try (Appearance app = Appearance.create(def, rig); Look look = app.defaults()) {
                assertEquals(0, app.unresolved());
                assertEquals(List.of("skin", "ears", "helmet"), app.slots().stream().map(Appearance.Slot::name).toList());
                int ears = rig.find("ear_l"), head = rig.find("head"), hand = rig.find("hand_l");
                long[] bits = app.visible(null);
                assertTrue(bit(bits, ears) && bit(bits, head));
                assertEquals(0, app.addItem(Appearance.Item.rigid("iron_helmet", "helmet", null, new float[]{0, 0.05f, 0},
                    null)));
                assertEquals(0, app.resolve(look.setString("helmet", "iron_helmet").setColor("skin", 1, 0, 0)));
                bits = app.visible(null);
                assertFalse(bit(bits, ears), "the helmet hides the ears");
                assertTrue(bit(bits, head));
                Appearance.Equipped helmet = app.equipped().getFirst();
                assertEquals(head, helmet.joint());
                assertEquals(1.25f, helmet.socket().m31(), 1e-6f);
                assertArrayEquals(new float[]{1, 0, 0}, java.util.Arrays.copyOf(app.materials().getFirst().tints(), 3));

                // First person: the arms only.
                try (BoneMask fp = BoneMask.create(rig, 0f)) {
                    def.viewMask(rig, CharacterDefinition.VIEW_FIRST_PERSON, fp);
                    bits = app.visible(fp);
                    assertTrue(bit(bits, hand));
                    assertFalse(bit(bits, head));
                }
            }
        }
    }

    @Test
    void anAuthoredHiddenPartStartsHidden() {
        Rig rig = Rig.create(List.of(
            RigJoint.part("a", "a", -1, true, null, null, null, null),
            RigJoint.part("b", "b", 0, true, null, null, null, null).withHidden(true)));
        try (rig; CharacterDefinition def = CharacterDefinition.create()) {
            def.setSkeleton(rig);
            try (Appearance app = Appearance.create(def, rig)) {
                assertEquals(1L, app.visible(null)[0]);
            }
        }
    }

    @Test
    void aSleeveSkinnedToTheGoblinsArmFollowsItWithTheBodysPalette() throws Exception {
        assumeTrue(Files.isRegularFile(GOBLIN), "goblin fixture missing");
        EntityGeometry geometry = goblin();
        Rig rig = geometry.rig().rig();
        int shoulder = rig.find("shoulder_l"), elbow = rig.find("elbow_l"), wrist = rig.find("wrist_l");
        Matrix4f[] world = new Matrix4f[rig.jointCount()], child = new Matrix4f[rig.jointCount()];
        for (int i = 0; i < world.length; i++) {
            world[i] = new Matrix4f();
            child[i] = new Matrix4f();
        }
        rig.restWorld(world, child);
        Vector3f a = world[shoulder].getTranslation(new Vector3f());
        Vector3f b = world[elbow].getTranslation(new Vector3f());
        Vector3f c = world[wrist].getTranslation(new Vector3f());
        // A sleeve: rings of 8 around the shoulder -> elbow -> wrist polyline, radius 0.12.
        List<Float> pts = new ArrayList<>();
        Vector3f[] path = {a, new Vector3f(a).lerp(b, 0.5f), b, new Vector3f(b).lerp(c, 0.5f), c};
        for (Vector3f p : path) {
            for (int s = 0; s < 8; s++) {
                double ang = 2 * Math.PI * s / 8;
                pts.add(p.x + 0.12f * (float) Math.cos(ang));
                pts.add(p.y);
                pts.add(p.z + 0.12f * (float) Math.sin(ang));
            }
        }
        float[] positions = new float[pts.size()];
        for (int i = 0; i < positions.length; i++) positions[i] = pts.get(i);
        int n = positions.length / 3;
        List<String> refs = rig.joints().stream().map(RigJoint::name).toList();
        try (CharacterProfile humanoid = CharacterProfile.builtin("humanoid"); CharacterDefinition def = CharacterDefinition.create();
             Skin skin = Skin.create(n)) {
            def.setSkeleton(rig);
            def.autoMap(rig, humanoid);
            skin.autoWeights(rig, positions, 0, n, new int[]{shoulder, elbow, wrist}, 2f, 0.01f);
            try (Garment sleeve = Garment.fit(def, rig, null, null, refs, positions, skin)) {
                assertEquals(0, sleeve.unresolved());
                assertArrayEquals(positions, sleeve.positions());
                PackedSkin packed = sleeve.pack();
                assertEquals(n, packed.vertexCount());
                // The arm swings: every sleeve ring stays around its posed bone (its distance to the posed arm
                // polyline stays the rest radius within 2 cm), deformed with the BODY's palette.
                float[] locals = RigPoser.local().localPose(rig, null, 0f, List.of(), new float[rig.jointCount() * 9]);
                locals[shoulder * 9 + 3] += 70f;
                locals[elbow * 9 + 3] += 60f;
                JointPalette body = RigPoser.local().poseLocals(rig, locals, null, new JointPalette());
                Vector3f pa = body.worldMatrix(shoulder, new Matrix4f()).getTranslation(new Vector3f());
                Vector3f pb = body.worldMatrix(elbow, new Matrix4f()).getTranslation(new Vector3f());
                Vector3f pc = body.worldMatrix(wrist, new Matrix4f()).getTranslation(new Vector3f());
                assertTrue(pc.distance(c) > 0.1f, "the arm moved");
                int[] partOf = new int[n];
                java.util.Arrays.fill(partOf, shoulder);
                try (Skin fitted = Skin.create(n)) {
                    // The garment's remapped weights deform it: rebuild them from the packed attributes.
                    for (int v = 0; v < n; v++) {
                        int[] j = new int[4];
                        float[] w = new float[4];
                        for (int k = 0; k < 4; k++) {
                            j[k] = packed.joints()[v * 4 + k] & 0xffff;
                            w[k] = (packed.weights()[v * 4 + k] & 0xff) / 255f;
                        }
                        fitted.set(v, j, w);
                    }
                    float[] posed = Skin.deform(fitted, partOf, positions, body, null);
                    // Leader pose: exactly the authored skin deformed with the same palette.
                    float[] direct = Skin.deform(skin, partOf, positions, body, null);
                    float worst = 0;
                    for (int i = 0; i < posed.length; i++) worst = Math.max(worst, Math.abs(posed[i] - direct[i]));
                    // (8-bit packed weights vs the float skin.)
                    assertTrue(worst < 2e-3f, "garment vs direct deformation " + worst);
                    // And it went with the arm: every ring's centre sits on the posed arm.
                    float d = 0;
                    for (int ring = 0; ring < n / 8; ring++) {
                        Vector3f centre = new Vector3f();
                        for (int s2 = 0; s2 < 8; s2++) {
                            int v = ring * 8 + s2;
                            centre.add(posed[v * 3], posed[v * 3 + 1], posed[v * 3 + 2]);
                        }
                        centre.div(8);
                        d = Math.max(d, Math.min(segment(centre, pa, pb), segment(centre, pb, pc)));
                    }
                    System.out.printf("[appearance] goblin sleeve: vs direct %.5f, ring centres off the arm %.4f%n", worst, d);
                    assertTrue(d < 0.03f, "ring centre " + d);
                }
            }
        }
    }

    private static float segment(Vector3f p, Vector3f a, Vector3f b) {
        Vector3f ab = new Vector3f(b).sub(a);
        float t = Math.max(0, Math.min(1, new Vector3f(p).sub(a).dot(ab) / Math.max(1e-9f, ab.lengthSquared())));
        return p.distance(new Vector3f(a).fma(t, ab));
    }

    @Test
    void archiveEntriesRoundTripThroughTheDefinition() throws Exception {
        try (CharacterDefinition def = CharacterDefinition.create()) {
            def.setEntry("masks/tunic.png", new byte[]{1, 2, 3});
            def.setEntry("bodies/broad.omo", new byte[]{9});
            assertEquals(List.of("masks/tunic.png", "bodies/broad.omo"), def.entryNames());
            assertArrayEquals(new byte[]{1, 2, 3}, def.entry("masks/tunic.png").orElseThrow());
            try (CharacterDefinition again = CharacterDefinition.read(def.write())) {
                assertArrayEquals(new byte[]{9}, again.entry("bodies/broad.omo").orElseThrow());
            }
            def.setEntry("bodies/broad.omo", null);
            assertTrue(def.entry("bodies/broad.omo").isEmpty());
            assertThrows(IllegalArgumentException.class, () -> def.setEntry("manifest.json", new byte[]{1}));
            assertNotNull(def.entryNames());
        }
    }
}
