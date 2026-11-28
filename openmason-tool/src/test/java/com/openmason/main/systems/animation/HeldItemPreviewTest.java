package com.openmason.main.systems.animation;

import com.openmason.api.viewport.HeldItemProvider;
import com.openmason.engine.character.CendaCharacter;
import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.character.CharacterProfile;
import com.openmason.engine.entity.model.EntitySockets;
import com.openmason.engine.rig.CendaRig;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.Rig;
import com.openmason.engine.rig.RigPoser;
import com.openmason.engine.rig.SocketSet;
import com.openmason.main.systems.character.CharacterDocument;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Held items in the previews ({@link HeldItemPreview}) on the goblin fixture with a {@code grip_r} socket: the item is
 * drawn at {@code socket frame . mount offset} on the pose shown (the Eyes view's first-person pose included), with the
 * preview's view and projection; empty hands, unknown items and missing sockets draw nothing and say why.
 */
class HeldItemPreviewTest {

    private static final Path GOBLIN = Path.of("../test-fixtures/stonebreak/resources/sbe/Mobs/SB_Goblin.sbe");

    /** A provider that records what it is asked to draw. */
    private static final class Fake implements HeldItemProvider {
        final List<Matrix4f> drawn = new ArrayList<>();
        final Matrix4f offset = new Matrix4f().translation(0.01f, -0.02f, 0.03f).rotateX(0.4f).scale(0.5f);
        String socket = "grip_r";

        @Override public String id() { return "test"; }
        @Override public String label() { return "Test items"; }
        @Override public List<Item> items() { return List.of(new Item("stick", "Stick"), new Item("rock", "Rock")); }
        @Override public Mount mount(String itemId) {
            return itemId.equals("stick") || itemId.equals("rock") ? new Mount(socket, offset) : null;
        }
        @Override public void render(String itemId, Matrix4fc model, Matrix4fc view, Matrix4fc projection) {
            drawn.add(new Matrix4f(model));
        }
    }

    @TempDir
    Path tmp;

    private CharacterDocument doc;
    private Rig rig;
    private CharacterDefinition character;
    private final JointPalette body = new JointPalette();
    private final Fake provider = new Fake();
    private HeldItemPreview held;

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
        String hand = character.roles().get("hand_r") != null ? character.roles().get("hand_r")
                : character.roles().get("lower_arm_r");
        character.setSocket(new CharacterDefinition.Socket("grip_r", hand, new CharacterDefinition.Vec3(0.2f, 0.8f, -0.05f),
                new CharacterDefinition.Vec3(0, 0, 0), new CharacterDefinition.Vec3(1, 1, 1)));
        RigPoser.local().poseLocals(rig, null, new Matrix4f().translation(1f, 2f, 3f).rotateY(0.7f), body);
        held = new HeldItemPreview(() -> List.of(provider));
    }

    @AfterEach
    void tearDown() {
        if (held != null) held.close();
        if (doc != null) doc.close();
    }

    @Test
    void theItemDrawsOnItsSocketFrameTimesTheMountOffset() {
        held.select("stick");
        assertTrue(held.active());
        assertEquals("test:stick", held.selected());
        assertEquals("Stick", held.selectedLabel());
        held.draw(doc.geometry(), character, body, new Matrix4f(), new Matrix4f());
        assertNull(held.problem(), held.problem());
        assertEquals(1, provider.drawn.size());

        try (SocketSet sockets = EntitySockets.build(doc.geometry(), character)) {
            Matrix4f expected = sockets.frame(sockets.find("grip_r"), body, null, null, new Matrix4f()).mul(provider.offset);
            assertTrue(provider.drawn.get(0).equals(expected, 1e-5f), provider.drawn.get(0) + " vs " + expected);
        }
        assertEquals("grip_r", held.state().get("socket"));
    }

    @Test
    void emptyHandsUnknownItemsAndMissingSocketsDrawNothing() {
        held.draw(doc.geometry(), character, body, new Matrix4f(), new Matrix4f());
        assertTrue(provider.drawn.isEmpty(), "empty hands");
        assertFalse(held.active());

        assertThrows(IllegalArgumentException.class, () -> held.select("sword"));
        held.select("test:rock");
        held.select("");
        assertFalse(held.active(), "blank = empty hands");

        provider.socket = "grip_tail";
        held.select("stick");
        held.draw(doc.geometry(), character, body, new Matrix4f(), new Matrix4f());
        assertTrue(provider.drawn.isEmpty());
        assertNotNull(held.problem());
        assertTrue(held.problem().contains("grip_tail"), held.problem());
    }
}
