package com.openmason.engine.character;

import com.openmason.engine.character.CharacterDefinition.AutoMapResult;
import com.openmason.engine.character.CharacterDefinition.Issue;
import com.openmason.engine.character.CharacterDefinition.IssueCode;
import com.openmason.engine.character.CharacterDefinition.Severity;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.rig.Rig;
import com.openmason.engine.rig.RigJoint;
import com.openmason.engine.rig.RigModel;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OpenMason #154 acceptance: the Stonebreak fixture copies of SB_Player (33 bones), the goblin (16, re-rigged for
 * #160) and the Ice Archon (18) auto-map onto the built-in humanoid profile with only the expected gaps.
 *
 * <p>SB_Player and the Archon name their sides from the viewer ({@code arm_left} sits at +X while the model
 * faces -Z: the character's right), the goblin anatomically; characterization maps by position, so all three
 * end up anatomically correct.
 */
class CharacterizationFixtureTest {

    private static final Path MOBS = Path.of("../../test-fixtures/stonebreak/resources/sbe/Mobs");

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaCharacter.isAvailable(), "native character module unavailable: " + CendaCharacter.unavailableReason());
        assumeTrue(Files.isDirectory(MOBS), "Stonebreak fixtures missing at " + MOBS.toAbsolutePath());
    }

    /** The rig of an SBE's base model (model.omo), bones and parts. */
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

    /** Role -> joint NAME (ids are UUIDs). */
    private static Map<String, String> roleNames(CharacterDefinition c, Rig rig) {
        Map<String, String> nameOf = new HashMap<>();
        for (RigJoint j : rig.joints()) nameOf.put(j.id(), j.name());
        Map<String, String> out = new TreeMap<>();
        c.roles().forEach((role, joint) -> out.put(role, nameOf.get(joint)));
        return out;
    }

    private static Set<String> codes(List<Issue> issues, IssueCode code) {
        return issues.stream().filter(i -> i.code() == code).map(Issue::subject).collect(Collectors.toSet());
    }

    private static List<Issue> errors(List<Issue> issues) {
        return issues.stream().filter(i -> i.severity() == Severity.ERROR).toList();
    }

    @Test
    void playerMapsEveryBoneWithSidesFromItsGeometry() throws IOException {
        try (Rig rig = rigOf("SB_Player.sbe");
             CharacterProfile humanoid = CharacterProfile.builtin("humanoid");
             CharacterDefinition c = CharacterDefinition.create()) {
            c.setSkeleton(rig);
            AutoMapResult result = c.autoMap(rig, humanoid);
            Map<String, String> roles = roleNames(c, rig);

            assertEquals(33, result.matches().size(), "every bone gets a role: " + roles);
            assertEquals("-z", c.facing());
            assertEquals("root", roles.get("hips"));
            assertEquals("spine", roles.get("spine"));
            assertEquals("head", roles.get("head"));
            // arm_left lies at +X; the player faces -Z, so it is the character's RIGHT arm.
            assertEquals("arm_left", roles.get("upper_arm_r"));
            assertEquals("forearm_left", roles.get("lower_arm_r"));
            assertEquals("hand_left", roles.get("hand_r"));
            assertEquals("thumb_left", roles.get("thumb_proximal_r"));
            assertEquals("finger_middle_left", roles.get("middle_proximal_r"));
            assertEquals("finger_little_tip_left", roles.get("little_intermediate_r"));
            assertEquals("arm_right", roles.get("upper_arm_l"));
            assertEquals("leg_right", roles.get("upper_leg_l"));
            assertEquals("calf_right", roles.get("lower_leg_l"));
            assertEquals("foot_right", roles.get("foot_l"));
            assertFalse(codes(result.notes(), IssueCode.SIDE_FROM_GEOMETRY).isEmpty());
            assertTrue(codes(result.notes(), IssueCode.REQUIRED_MISSING).isEmpty());

            List<Issue> issues = c.validate(rig, humanoid);
            assertEquals(List.of(), errors(issues));
            assertTrue(c.fitReferencePose(rig, humanoid) > 20);
            c.lock(rig, humanoid);
            assertTrue(c.locked());
        }
    }

    @Test
    void archonMapsItsBodyAndLeavesItsWeaponsAlone() throws IOException {
        try (Rig rig = rigOf("SB_Ice_Archon.sbe");
             CharacterProfile humanoid = CharacterProfile.builtin("humanoid");
             CharacterDefinition c = CharacterDefinition.create()) {
            AutoMapResult result = c.autoMap(rig, humanoid);
            Map<String, String> roles = roleNames(c, rig);

            assertEquals(16, result.matches().size(), roles.toString());
            assertFalse(roles.containsValue("glacier_guard"));
            assertFalse(roles.containsValue("frostblade"));
            assertEquals("-z", c.facing());
            assertEquals("root", roles.get("hips"));
            assertEquals("torso", roles.get("spine"));
            assertEquals("neck", roles.get("neck"));
            assertEquals("head", roles.get("head"));
            assertEquals("arm_left", roles.get("upper_arm_r"));
            assertEquals("hand_right", roles.get("hand_l"));
            assertEquals("foot_left", roles.get("foot_r"));
            assertTrue(codes(result.notes(), IssueCode.REQUIRED_MISSING).isEmpty());
            assertEquals(List.of(), errors(c.validate(rig, humanoid)));
        }
    }

    @Test
    void goblinMapsEveryRequiredRole() throws IOException {
        try (Rig rig = rigOf("SB_Goblin.sbe");
             CharacterProfile humanoid = CharacterProfile.builtin("humanoid");
             CharacterDefinition c = CharacterDefinition.create()) {
            AutoMapResult result = c.autoMap(rig, humanoid);
            Map<String, String> roles = roleNames(c, rig);

            assertEquals(Map.ofEntries(
                Map.entry("hips", "hips"), Map.entry("spine", "spine"), Map.entry("head", "neck"),
                Map.entry("upper_arm_l", "shoulder_l"), Map.entry("lower_arm_l", "elbow_l"),
                Map.entry("upper_arm_r", "shoulder_r"), Map.entry("lower_arm_r", "elbow_r"),
                Map.entry("upper_leg_l", "hip_l"), Map.entry("lower_leg_l", "knee_l"),
                Map.entry("upper_leg_r", "hip_r"), Map.entry("lower_leg_r", "knee_r"),
                Map.entry("hand_l", "wrist_l"), Map.entry("hand_r", "wrist_r"),
                Map.entry("foot_l", "ankle_l"), Map.entry("foot_r", "ankle_r")), roles);
            // No gaps since the #160 re-rig (wrists and ankles; the old placeholder bones had none). Its shoulder
            // and neck bones are promoted to the required arm / head roles.
            assertTrue(codes(result.notes(), IssueCode.REQUIRED_MISSING).isEmpty());
            assertEquals(Set.of("head", "upper_arm_l", "upper_arm_r"), codes(result.notes(), IssueCode.PROMOTED));
            assertTrue(codes(result.notes(), IssueCode.SIDE_FROM_GEOMETRY).isEmpty(), "the goblin names its sides anatomically");

            // It validates against the humanoid profile; its own exported profile fits it completely.
            assertEquals(List.of(), errors(c.validate(rig, humanoid)));
            try (CharacterProfile own = CharacterProfile.fromRig(rig, c, "goblin");
                 CharacterDefinition mapped = CharacterDefinition.create()) {
                assertEquals(16, mapped.autoMap(rig, own).matches().size());
                assertEquals(List.of(), errors(mapped.validate(rig, own)));
            }
        }
    }

    @Test
    void builtinProfilesRoundTripThroughJson() throws IOException {
        assertEquals(List.of("humanoid", "quadruped", "bird"), CharacterProfile.builtinIds());
        try (CharacterProfile humanoid = CharacterProfile.builtin("humanoid");
             CharacterProfile again = CharacterProfile.read(humanoid.write().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            assertEquals(humanoid.roles(), again.roles());
            assertEquals(63, again.roles().size());
            assertEquals(15, again.roles().stream().filter(CharacterProfile.Role::required).count());
            CharacterProfile.Role arm = again.roles().stream().filter(r -> r.name().equals("upper_arm_l")).findFirst().orElseThrow();
            assertEquals(CharacterProfile.Side.LEFT, arm.side());
            assertEquals("upper_arm_r", arm.mirror());
            assertEquals(-1f, arm.direction().orElseThrow().x());
        }
    }
}
