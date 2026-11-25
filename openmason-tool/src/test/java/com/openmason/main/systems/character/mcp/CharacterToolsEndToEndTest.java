package com.openmason.main.systems.character.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.character.CendaCharacter;
import com.openmason.engine.character.CharacterDefinition;
import com.openmason.main.systems.character.CharacterSessions;
import com.openmason.main.systems.io.AssetWriteService;
import com.openmason.main.systems.io.WritePolicy;
import com.openmason.main.systems.io.WriteRoots;
import com.openmason.main.systems.io.WriteSandbox;
import com.openmason.main.systems.mcp.McpToolRegistry;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OpenMason #163 acceptance: SB_Player characterized end to end through the {@code char_*} MCP tools alone (real
 * handlers, write sandbox, native character module), then the goblin. The tool has no game formats (Stonebreak's
 * plugin reads .sbe), so the tests characterize the fixture SBEs' base models ({@code model.omo}); the live app run
 * with the plugin opens the .sbe itself.
 */
class CharacterToolsEndToEndTest {

    private static final Path MOBS = Path.of("../test-fixtures/stonebreak/resources/sbe/Mobs");

    @TempDir
    Path tmp;

    private final ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final CharacterSessions sessions = new CharacterSessions();
    private Path project;
    private McpToolRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(CendaCharacter.isAvailable(), "native character module unavailable: " + CendaCharacter.unavailableReason());
        assumeTrue(Files.isDirectory(MOBS), "Stonebreak fixtures missing at " + MOBS.toAbsolutePath());
        MainThreadExecutor.bindToCurrentThread();
        project = Files.createDirectories(tmp.resolve("proj"));
        sessions.setProjectRoot(() -> project);
        AssetWriteService writes = new AssetWriteService(null, new WriteSandbox(WriteRoots.of(project,
                Files.createDirectories(tmp.resolve("game")), Files.createDirectories(tmp.resolve("exports")))),
                () -> WritePolicy.ASK_RISKY);
        registry = new McpToolRegistry();
        new CharacterToolDefinitions(new CharacterEditingService(() -> sessions, writes), mapper).registerAll(registry);
    }

    @AfterEach
    void tearDown() {
        sessions.close();
    }

    /** The fixture SBE's base model, extracted into the project. */
    private Path model(String sbe) throws Exception {
        Path out = project.resolve("Models").resolve(sbe.replace(".sbe", ".omo"));
        Files.createDirectories(out.getParent());
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(MOBS.resolve(sbe)))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().equals("model.omo")) {
                    Files.write(out, in.readAllBytes());
                    return out;
                }
            }
        }
        throw new AssertionError(sbe + " has no model.omo");
    }

    private JsonNode call(String tool, String argsJson) throws Exception {
        return mapper.valueToTree(registry.get(tool).handler().call(mapper.readTree(argsJson)));
    }

    private Exception fails(String tool, String argsJson) {
        return assertThrows(RuntimeException.class, () -> registry.get(tool).handler().call(mapper.readTree(argsJson)));
    }

    private static Map<String, String> roles(JsonNode get) {
        Map<String, String> out = new TreeMap<>();
        for (JsonNode r : get.get("roles")) {
            if (r.hasNonNull("joint")) out.put(r.get("role").asText(), r.get("joint").asText());
        }
        return out;
    }

    @Test
    void sbPlayerIsCharacterizedEndToEndThroughTheTools() throws Exception {
        Path omo = model("SB_Player.sbe");
        JsonNode opened = call("char_open", "{\"model\":\"project:Models/SB_Player.omo\"}");
        assertEquals("SB_Player", opened.get("name").asText());
        assertEquals(33, opened.get("bones").asInt());
        assertEquals(0, opened.get("mapped").asInt());
        assertFalse(opened.get("dirty").asBoolean(), "a fresh character is not dirty");

        // 1. Characterize: every bone maps; sides come from geometry (the player names them from the viewer).
        JsonNode mapped = call("char_auto_map", "{\"profile\":\"humanoid\"}");
        assertEquals("humanoid", mapped.get("profile").asText());
        assertEquals("-z", mapped.get("facing").asText());
        assertEquals(33, mapped.get("mapped").asInt());
        assertEquals(0, mapped.get("unmappedRequired").size(), mapped.toString());
        assertEquals(0, mapped.get("errors").asInt());
        assertTrue(mapped.get("notes").toString().contains("side_from_geometry"), "viewer-named sides were noticed");
        Map<String, String> r = roles(call("char_get", "{\"section\":null}"));
        assertEquals("arm_left", r.get("upper_arm_r"), "arm_left is the character's RIGHT arm (faces -Z)");

        // 2. Mirror is a no-op on a symmetric mapping.
        JsonNode mirrored = call("char_mirror", "{\"from\":\"left\",\"what\":[\"roles\"]}");
        assertEquals(0, mirrored.get("written").asInt(), mirrored.toString());

        // 3. Reference pose: the profile's T and A poses.
        JsonNode fitA = call("char_reference_pose", "{\"fit\":\"A\"}");
        assertTrue(fitA.get("fitted").asInt() > 10, fitA.toString());
        assertEquals("[\"T\",\"A\"]", fitA.get("poses").toString());
        JsonNode fitT = call("char_reference_pose", "{\"fit\":\"T\"}");
        assertTrue(fitT.get("posedJoints").asInt() > 10);

        // 4. Sockets: every standard socket the mapping allows, then the eyes nudged forward.
        JsonNode sockets = call("char_sockets", "{\"place\":[\"all\"]}");
        Set<String> names = new HashSet<>();
        sockets.get("sockets").forEach(s -> names.add(s.get("name").asText()));
        assertTrue(names.containsAll(Set.of("grip_r", "grip_l", "head", "eyes", "back")), names.toString());
        JsonNode eyes = null;
        for (JsonNode s : sockets.get("sockets")) if (s.get("name").asText().equals("eyes")) eyes = s;
        double eyeZ = eyes.get("pos").get(2).asDouble();
        call("char_edit", "{\"label\":\"eyes forward\",\"ops\":[{\"op\":\"socket\",\"name\":\"eyes\",\"pos\":["
                + eyes.get("pos").get(0).asDouble() + "," + eyes.get("pos").get(1).asDouble() + "," + (eyeZ - 0.02) + "]}]}");

        // 5. Metrics from the reference pose; name and object id.
        JsonNode edited = call("char_edit", """
                {"ops":[{"op":"compute_metrics"},{"op":"field","name":"objectId","value":"stonebreak:player"},
                        {"op":"field","name":"name","value":"SB_Player"}]}""");
        assertEquals(3, edited.get("applied").size());
        JsonNode metrics = call("char_get", "{\"section\":\"metrics\"}");
        double height = metrics.get("height").asDouble();
        assertTrue(height > 1.6 && height < 2.2, metrics.toString());
        assertTrue(metrics.get("eye_height").asDouble() > 0.8 * height, "eyes near the top: " + metrics);
        assertTrue(metrics.get("hip_height").asDouble() > 0.4 * height && metrics.get("hip_height").asDouble() < 0.65 * height);

        // An edit batch is all or nothing.
        String before = call("char_get", "{\"section\":\"sockets\"}").toString();
        Exception bad = fails("char_edit", "{\"ops\":[{\"op\":\"socket\",\"name\":\"eyes\",\"pos\":[0,9,0]},"
                + "{\"op\":\"role\",\"role\":\"hips\",\"joint\":\"no_such_joint\"}]}");
        assertTrue(bad.getMessage().contains("ops[1]"), bad.getMessage());
        assertEquals(before, call("char_get", "{\"section\":\"sockets\"}").toString());

        // 6. Validate, lock, save.
        JsonNode valid = call("char_validate", "{}");
        assertEquals(0, valid.get("errors").asInt(), valid.toString());
        assertTrue(call("char_lock", "{}").get("locked").asBoolean());
        assertTrue(fails("char_auto_map", "{}").getMessage().contains("locked"));
        JsonNode saved = call("char_save", "{\"path\":\"SB_Player\"}");
        Path file = Path.of(saved.get("saved").asText());
        assertEquals(project.resolve("Characters/SB_Player.omchar").toAbsolutePath().normalize(),
                file.toAbsolutePath().normalize());
        assertFalse(call("char_get", "{\"section\":null}").get("dirty").asBoolean());

        // On disk: a valid, locked definition referencing the model relative to the project layout.
        try (CharacterDefinition def = CharacterDefinition.read(file)) {
            assertTrue(def.locked());
            assertEquals("humanoid", def.profile());
            assertEquals("stonebreak:player", def.objectId());
            assertEquals(33, def.roles().size());
            assertEquals(5, def.sockets().size());
            assertEquals("../Models/SB_Player.omo", def.modelRef());
            assertTrue(def.embeddedModel().isEmpty());
        }

        // Re-open through the tools: same mapping, nothing to save.
        call("char_close", "{}");
        JsonNode reopened = call("char_open", "{\"path\":\"project:Characters/SB_Player.omchar\"}");
        assertEquals(33, reopened.get("mapped").asInt());
        assertTrue(reopened.get("locked").asBoolean());
        assertEquals(r, roles(call("char_get", "{\"section\":null}")));
        assertEquals(omo.toAbsolutePath().normalize().toString(), reopened.get("model").asText());
    }

    @Test
    void everyToolCallIsOneUndoStep() throws Exception {
        model("SB_Player.sbe");
        call("char_open", "{\"model\":\"project:Models/SB_Player.omo\"}");
        call("char_auto_map", "{}");
        call("char_reference_pose", "{\"fit\":\"T\"}");
        call("char_sockets", "{\"place\":[\"all\"]}");
        call("char_edit", "{\"ops\":[{\"op\":\"compute_metrics\"},{\"op\":\"goal\",\"name\":\"look\",\"joint\":\"role:head\"}]}");
        assertEquals(4, sessions.require().session().stepCount());

        assertEquals("edit character", call("char_undo", "{}").get("undone").asText());
        assertEquals("place sockets", call("char_undo", "{}").get("undone").asText());
        assertEquals(0, call("char_get", "{\"section\":\"sockets\"}").size());
        assertEquals("reference pose", call("char_undo", "{}").get("undone").asText());
        assertEquals(0, call("char_get", "{\"section\":\"pose\"}").size());
        assertEquals("auto-map", call("char_undo", "{}").get("undone").asText());
        assertEquals(0, call("char_get", "{\"section\":null}").get("mapped").asInt());
        assertNull(call("char_undo", "{}").get("undone"), "nothing left");
        assertEquals("auto-map", call("char_undo", "{\"redo\":true}").get("redone").asText());
        assertEquals(33, call("char_get", "{\"section\":null}").get("mapped").asInt());

        // Unsaved changes guard open / close.
        assertTrue(fails("char_close", "{}").getMessage().contains("unsaved"));
        assertTrue(call("char_close", "{\"discard\":true}").get("closed").asBoolean());
        assertTrue(fails("char_get", "{}").getMessage().contains("no character is open"));
    }

    @Test
    void theGoblinMapsItsRequiredRolesAndFixesARoleByHand() throws Exception {
        model("SB_Goblin.sbe");
        call("char_open", "{\"model\":\"project:Models/SB_Goblin.omo\"}");
        JsonNode mapped = call("char_auto_map", "{\"profile\":\"humanoid\"}");
        assertEquals(0, mapped.get("unmappedRequired").size(), mapped.toString());
        String hand = null;
        for (JsonNode role : call("char_get", "{\"section\":\"roles\"}")) {
            if (role.get("role").asText().equals("hand_l")) hand = role.get("jointId").asText();
        }
        assertNotNull(hand);
        // Unmap, see it reported, map it back by role reference.
        call("char_edit", "{\"ops\":[{\"op\":\"role\",\"role\":\"hand_l\",\"joint\":null}]}");
        JsonNode v = call("char_validate", "{}");
        assertTrue(v.get("unmappedRequired").toString().contains("hand_l"), v.toString());
        assertTrue(v.get("errors").asInt() > 0);
        call("char_edit", "{\"ops\":[{\"op\":\"role\",\"role\":\"hand_l\",\"joint\":\"" + hand + "\"}]}");
        assertEquals(0, call("char_validate", "{}").get("errors").asInt());
        // The profile section lists poses and roles.
        JsonNode profile = call("char_get", "{\"section\":\"profile\"}");
        assertEquals("humanoid", profile.get("id").asText());
        assertTrue(profile.get("builtin").toString().contains("quadruped"));
    }
}
