package com.openmason.main.systems.animation;

import com.openmason.engine.character.CendaCharacter;
import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.character.CharacterProfile;
import com.openmason.engine.rig.CendaRig;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.Rig;
import com.openmason.engine.rig.RigPoser;
import com.openmason.main.systems.character.CharacterDocument;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Eyes preview ({@link EyesView}) on the re-rigged goblin fixture, auto-mapped as a humanoid: the camera sits on
 * the character's eye point, only the arm branches are drawn, the arms ride the look when they follow it (same place
 * on screen at every look) and stay put when they do not, and a model without a character says why it cannot.
 */
class EyesViewTest {

    private static final Path GOBLIN = Path.of("../test-fixtures/stonebreak/resources/sbe/Mobs/SB_Goblin.sbe");

    @TempDir
    Path tmp;

    private CharacterDocument doc;
    private Rig rig;
    private CharacterDefinition character;
    private final JointPalette body = new JointPalette();
    private final EyesView eyes = new EyesView();

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(CendaRig.isAvailable() && CendaCharacter.isAvailable(), "native rig / character unavailable");
        assumeTrue(Files.isRegularFile(GOBLIN), "Stonebreak fixtures missing at " + GOBLIN.toAbsolutePath());
        Path omo = tmp.resolve("SB_Goblin.omo");
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(GOBLIN))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().equals("model.omo")) Files.write(omo, in.readAllBytes());
            }
        }
        doc = CharacterDocument.fromModel(omo);
        rig = doc.rig();
        character = doc.definition();
        character.autoMap(rig, CharacterProfile.builtin("humanoid"));
        RigPoser.local().poseLocals(rig, null, null, body);
    }

    @AfterEach
    void tearDown() {
        eyes.close();
        if (doc != null) doc.close();
    }

    private boolean update() {
        return eyes.update(rig, character, body, new Matrix4f(),
                (base, out) -> RigPoser.local().poseLocals(rig, null, new Matrix4f(base), out), 800, 450);
    }

    /** Where a joint lands on screen (x/depth, y/depth in camera space) and its depth. */
    private Vector3f onScreen(int joint) {
        Vector3f p = eyes.palette().worldMatrix(joint, new Matrix4f()).getTranslation(new Vector3f());
        eyes.view().transformPosition(p);
        return new Vector3f(p.x / -p.z, p.y / -p.z, -p.z);
    }

    @Test
    void theCameraSitsOnTheEyesAndOnlyTheArmsDraw() {
        assertTrue(update(), eyes.problem());
        Vector3f expected = new Vector3f();
        character.eyesPoint(rig, body, expected);
        assertTrue(eyes.eyes().distance(expected) < 1e-5f, "camera on the eye point: " + eyes.eyes());
        Vector3f camera = eyes.view().invert(new Matrix4f()).getTranslation(new Vector3f());
        assertTrue(camera.distance(expected) < 1e-4f, "the view looks from the eyes: " + camera);

        JointPalette fp = eyes.palette();
        int head = rig.find(character.roles().get("head")), hips = rig.find(character.roles().get("hips"));
        int hand = rig.find(character.roles().get("hand_r") != null ? character.roles().get("hand_r")
                : character.roles().get("lower_arm_r"));
        Matrix4f m = new Matrix4f();
        assertTrue(Math.abs(fp.skinMatrix(hand, m).determinant3x3()) > 1e-6f, "the hand draws");
        assertTrue(Math.abs(fp.skinMatrix(head, m).determinant3x3()) < 1e-9f, "the head collapses");
        assertTrue(Math.abs(fp.skinMatrix(hips, m).determinant3x3()) < 1e-9f, "the hips collapse");
    }

    @Test
    void theArmsRideTheLookWhenTheyFollowIt() {
        int hand = rig.find(character.roles().get("hand_r") != null ? character.roles().get("hand_r")
                : character.roles().get("lower_arm_r"));
        Vector3f reference = null;
        for (float[] look : new float[][] {{0f, 0f}, {30f, -60f}, {-75f, 45f}, {160f, 10f}}) {
            eyes.setLook(look[0], look[1]);
            assertTrue(update(), eyes.problem());
            Vector3f at = onScreen(hand);
            if (reference == null) reference = at;
            assertEquals(reference.x, at.x, 1e-3f, "same screen x at look " + look[0] + "/" + look[1]);
            assertEquals(reference.y, at.y, 1e-3f, "same screen y at look " + look[0] + "/" + look[1]);
        }
        // Not following: the arms stay with the body, so a different look moves them on screen.
        eyes.setArmsFollowLook(false);
        eyes.setLook(0f, 0f);
        assertTrue(update());
        Vector3f straight = onScreen(hand);
        eyes.setLook(0f, 40f);
        assertTrue(update());
        Vector3f up = onScreen(hand);
        assertTrue(Math.abs(up.y - straight.y) > 0.2f, "looking up leaves the arms below: " + straight + " -> " + up);
    }

    @Test
    void lookAndLensAreClampedAndReset() {
        eyes.setLook(270f, 120f);
        assertEquals(-90f, eyes.yaw(), 1e-4f);
        assertEquals(89f, eyes.pitch(), 1e-4f);
        eyes.setFov(500f);
        assertEquals(120f, eyes.fov(), 1e-4f);
        eyes.reset();
        assertEquals(0f, eyes.yaw());
        assertEquals(0f, eyes.pitch());
        assertEquals(EyesView.DEFAULT_FOV, eyes.fov());
        assertTrue(eyes.armsFollowLook());
    }

    @Test
    void aModelWithoutACharacterSaysWhy() {
        assertFalse(eyes.update(rig, null, body, new Matrix4f(), (b, o) -> o, 800, 450));
        assertNotNull(eyes.problem());
        assertTrue(eyes.problem().contains("character"), eyes.problem());
    }
}
