package com.openmason.engine.character;

import com.openmason.engine.character.CharacterDefinition.Vec3;
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
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OpenMason #163: the Java binding of the native character editing ABI ({@code cenda/character_edit.h}) on the
 * Stonebreak fixture rigs - edit session, role states, profile diagram and named poses, mirroring, standard
 * sockets, the overlay and picking. The rules themselves are tested natively (ctest character_edit).
 */
class CharacterEditBindingTest {

    private static final Path MOBS = Path.of("../../test-fixtures/stonebreak/resources/sbe/Mobs");

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaCharacter.isAvailable(), "native character module unavailable: " + CendaCharacter.unavailableReason());
        assumeTrue(Files.isDirectory(MOBS), "Stonebreak fixtures missing at " + MOBS.toAbsolutePath());
    }

    private static Rig rigOf(String sbe) throws IOException {
        byte[] omo = null;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(MOBS.resolve(sbe)))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().equals("model.omo")) omo = in.readAllBytes();
            }
        }
        assertNotNull(omo, sbe + " has no model.omo");
        OMOReader.ReadResult read = new OMOReader().read(new ByteArrayInputStream(omo));
        List<RigModel.PartSpec> parts = new ArrayList<>();
        for (OMOFormat.PartEntry p : read.parts()) {
            parts.add(new RigModel.PartSpec(p.id(), p.name(), p.parentId(), p.vertexCount() > 0,
                new Vector3f(p.originX(), p.originY(), p.originZ()), new Vector3f(p.posX(), p.posY(), p.posZ()),
                new Vector3f(p.rotX(), p.rotY(), p.rotZ()), new Vector3f(p.scaleX(), p.scaleY(), p.scaleZ())));
        }
        return RigModel.build(parts, read.bones(), null, null, null).rig();
    }

    @Test
    void theSessionRecordsOneStepPerGroupAndRestoresExactly() throws IOException {
        Rig rig = rigOf("SB_Player.sbe");
        try (CharacterProfile h = CharacterProfile.builtin("humanoid");
             CharacterDefinition c = CharacterDefinition.create()) {
            c.setSkeleton(rig);
            byte[] base = c.write();
            try (CharacterEditSession s = CharacterEditSession.create(c, 0)) {
                assertFalse(s.dirty());
                long r0 = s.revision();
                s.edit("auto-map", null, () -> c.autoMap(rig, h));
                assertEquals(1, s.stepCount());
                assertEquals("auto-map", s.undoLabel());
                assertTrue(s.dirty());
                assertNotEquals(r0, s.revision());
                byte[] mapped = c.write();

                // A drag: five commits with one key = one step.
                for (int i = 0; i < 5; i++) {
                    float y = 0.1f * i;
                    s.begin("move", "socket:eyes");
                    c.setSocket(new CharacterDefinition.Socket("eyes", c.roles().get("head"), new Vec3(0, y, 0),
                        Vec3.ZERO, Vec3.ONE));
                    assertEquals(i == 0 ? CharacterEditSession.Commit.NEW_STEP : CharacterEditSession.Commit.MERGED,
                        s.commit());
                }
                assertEquals(2, s.stepCount());

                // A throwing edit is cancelled.
                assertThrows(IllegalArgumentException.class, () -> s.edit("bad", null, () -> {
                    c.setName("half done");
                    c.setRole("hips", "no such joint id");
                    throw new IllegalArgumentException("refused");
                }));
                assertNotEquals("half done", c.name());
                assertEquals(0, s.depth());

                assertTrue(s.undo());
                assertArrayEquals(mapped, c.write());
                assertTrue(s.undo());
                assertArrayEquals(base, c.write());
                assertFalse(s.undo());
                assertTrue(s.redo());
                assertArrayEquals(mapped, c.write());
                s.markSaved();
                assertFalse(s.dirty());
            }
        }
    }

    @Test
    void roleStatesDiagramAndNamedPosesReachJava() throws IOException {
        Rig rig = rigOf("SB_Goblin.sbe");
        try (CharacterProfile h = CharacterProfile.builtin("humanoid");
             CharacterDefinition c = CharacterDefinition.create()) {
            c.setSkeleton(rig);
            int[] before = c.roleStates(rig, h);
            assertEquals(h.roles().size(), before.length);
            int hips = indexOf(h, "hips");
            assertEquals(CharacterOverlay.ROLE_REQUIRED | CharacterOverlay.ROLE_ERROR, before[hips]);
            c.autoMap(rig, h);
            int[] after = c.roleStates(rig, h);
            assertEquals(CharacterOverlay.ROLE_REQUIRED | CharacterOverlay.ROLE_MAPPED, after[hips]);

            float[] xy = h.diagram();
            assertEquals(h.roles().size() * 2, xy.length);
            assertTrue(h.hasDiagram(hips));
            assertTrue(xy[indexOf(h, "hand_l") * 2] > 0.5f, "the character's left is on the viewer's right");
            assertEquals(List.of("A"), h.poseNames());

            assertTrue(c.fitReferencePose(rig, h, "A") > 0);
            float[] aLocals = c.referenceLocals(rig);
            assertEquals(rig.jointCount() * 9, aLocals.length);
            c.fitReferencePose(rig, h, "T");
            assertFalse(java.util.Arrays.equals(aLocals, c.referenceLocals(rig)));
            assertThrows(IllegalArgumentException.class, () -> c.fitReferencePose(rig, h, "no such pose"));
        }
    }

    @Test
    void mirrorStandardSocketsAndTheOverlay() throws IOException {
        Rig rig = rigOf("SB_Player.sbe");
        try (CharacterProfile h = CharacterProfile.builtin("humanoid");
             CharacterDefinition c = CharacterDefinition.create()) {
            c.setSkeleton(rig);
            c.autoMap(rig, h);
            Map<String, String> full = c.roles();
            for (String role : full.keySet()) {
                if (role.endsWith("_r")) c.removeRole(role);
            }
            CharacterDefinition.MirrorResult m = c.mirror(rig, h, CharacterProfile.Side.LEFT, CharacterDefinition.MIRROR_ROLES);
            assertTrue(m.count() > 5, m.notes().toString());
            assertEquals(full, c.roles(), "mirroring the left side rebuilds the auto-map's right side");

            assertEquals(List.of("grip_r", "grip_l", "head", "eyes", "back"),
                CharacterDefinition.standardSockets().stream().map(CharacterDefinition.StandardSocket::name).toList());
            for (CharacterDefinition.StandardSocket s : CharacterDefinition.standardSockets()) {
                c.placeStandardSocket(rig, s.name());
            }
            assertEquals(5, c.sockets().size());
            assertThrows(IllegalArgumentException.class, () -> c.placeStandardSocket(rig, "tail"));

            c.fitReferencePose(rig, h);
            try (CharacterOverlay o = CharacterOverlay.build(c, rig, h, null, null, null)) {
                assertEquals(rig.jointCount(), o.jointCount());
                assertEquals(5, o.socketCount());
                assertTrue(o.capsule().radius() > 0.05f && o.capsule().radius() < 0.5f, o.capsule().toString());
                int hipsJoint = rig.find(full.get("hips"));
                assertEquals(indexOf(h, "hips"), o.roles()[hipsJoint]);
                assertTrue((o.states()[hipsJoint] & CharacterOverlay.ROLE_MAPPED) != 0);
                int chains = 0;
                for (int k : o.segmentKinds()) if (k == CharacterOverlay.SEGMENT_CHAIN) chains++;
                assertTrue(chains >= c.chains().size());
                // A ray straight down onto the head bone's point picks it (bones only: the player has 85 parts too).
                int head = rig.find(full.get("head"));
                float[] p = o.points();
                CharacterOverlay.Pick pick = o.pick(p[head * 3], p[head * 3 + 1] + 5f, p[head * 3 + 2], 0, -1, 0, 0.001f,
                    0f, true);
                assertTrue(pick.kind() == CharacterOverlay.PickKind.JOINT || pick.kind() == CharacterOverlay.PickKind.SOCKET);
                if (pick.kind() == CharacterOverlay.PickKind.JOINT) {
                    assertEquals(RigJoint.Kind.BONE, rig.joints().get(pick.index()).kind());
                }
                assertEquals(rig.jointCount() * 16, o.skin().length);
            }
        }
    }

    private static int indexOf(CharacterProfile p, String role) {
        List<CharacterProfile.Role> roles = p.roles();
        for (int i = 0; i < roles.size(); i++) {
            if (roles.get(i).name().equals(role)) return i;
        }
        throw new AssertionError(role);
    }
}
