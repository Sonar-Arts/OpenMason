package com.openmason.main.systems.animgraph.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.animgraph.AnimGraphDocument;
import com.openmason.engine.character.CendaCharacter;
import com.openmason.engine.character.CharacterProfile;
import com.openmason.main.systems.animgraph.AnimGraphSessions;
import com.openmason.main.systems.character.CharacterDocument;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OpenMason #164 acceptance through the {@code animgraph_*} MCP tools alone (real handlers, sandbox, native graph
 * model and runtime): the #157 locomotion test graph built from nothing equals the shipped test file, and the
 * debugger's values (the live preview) match a headless evaluation of the same inputs.
 */
class AnimGraphToolsEndToEndTest {

    private static final Path GRAPH = Path.of("../openmason-engine/engine-core/src/test/resources/animgraph/humanoid_locomotion.omanimgraph");
    private static final Path GOBLIN = Path.of("../test-fixtures/stonebreak/resources/sbe/Mobs/SB_Goblin.sbe");

    @TempDir
    Path tmp;

    private final ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final AnimGraphSessions sessions = new AnimGraphSessions();
    private Path project;
    private McpToolRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(CendaCharacter.isAvailable(), "native module unavailable: " + CendaCharacter.unavailableReason());
        assumeTrue(Files.isRegularFile(GRAPH), "missing " + GRAPH.toAbsolutePath());
        MainThreadExecutor.bindToCurrentThread();
        project = Files.createDirectories(tmp.resolve("proj"));
        sessions.setProjectRoot(() -> project);
        AssetWriteService writes = new AssetWriteService(null, new WriteSandbox(WriteRoots.of(project,
                Files.createDirectories(tmp.resolve("game")), Files.createDirectories(tmp.resolve("exports")))),
                () -> WritePolicy.ASK_RISKY);
        registry = new McpToolRegistry();
        new AnimGraphToolDefinitions(new AnimGraphEditingService(() -> sessions, writes), mapper).registerAll(registry);
    }

    @AfterEach
    void tearDown() {
        sessions.close();
    }

    private JsonNode call(String tool, String argsJson) throws Exception {
        return mapper.valueToTree(registry.get(tool).handler().call(mapper.readTree(argsJson)));
    }

    private Exception fails(String tool, String argsJson) {
        return assertThrows(RuntimeException.class, () -> registry.get(tool).handler().call(mapper.readTree(argsJson)));
    }

    /** The #157 locomotion graph, as an agent builds it: four batches. */
    private void buildLocomotion() throws Exception {
        call("animgraph_new", "{\"name\":\"Humanoid locomotion\",\"profile\":\"humanoid\"}");
        call("animgraph_apply", """
                {"label":"parameters and slots","ops":[
                 {"op":"graph","description":"Idle, walk/run blend space, jump, fall, land; a full-body montage slot on top."},
                 {"op":"param","name":"speed","type":"float"},{"op":"param","name":"grounded","type":"bool","default":true},
                 {"op":"param","name":"jump","type":"trigger"},
                 {"op":"slot","name":"idle"},{"op":"slot","name":"walk"},{"op":"slot","name":"run"},
                 {"op":"slot","name":"jump"},{"op":"slot","name":"fall"},{"op":"slot","name":"land"}]}""");
        call("animgraph_apply", """
                {"label":"locomotion blend","ops":[
                 {"op":"node","id":"idle","type":"clip","slot":"idle"},
                 {"op":"node","id":"walk","type":"clip","slot":"walk","sync":"feet"},
                 {"op":"node","id":"run","type":"clip","slot":"run","sync":"feet"},
                 {"op":"node","id":"locomotion","type":"blend1d","param":"speed"},
                 {"op":"link","parent":"locomotion","child":"idle"},{"op":"link","parent":"locomotion","child":"walk"},
                 {"op":"link","parent":"locomotion","child":"run"},
                 {"op":"sample","node":"locomotion","index":1,"value":1.5},{"op":"sample","node":"locomotion","index":2,"value":5}]}""");
        JsonNode machine = call("animgraph_apply", """
                {"label":"movement machine","ops":[
                 {"op":"node","id":"jumpClip","type":"clip","slot":"jump"},{"op":"node","id":"fallClip","type":"clip","slot":"fall"},
                 {"op":"node","id":"landClip","type":"clip","slot":"land"},{"op":"node","id":"movement","type":"stateMachine"},
                 {"op":"state","machine":"movement","name":"ground","node":"locomotion","entry":true},
                 {"op":"state","machine":"movement","name":"jump","node":"jumpClip"},
                 {"op":"state","machine":"movement","name":"fall","node":"fallClip"},
                 {"op":"state","machine":"movement","name":"land","node":"landClip"},
                 {"op":"transition","machine":"movement","from":"ground","to":"jump","conditions":[{"param":"jump"}],"priority":2,"blend":0.1},
                 {"op":"transition","machine":"movement","from":"ground","to":"fall","conditions":[{"param":"grounded","op":"==","value":false}],"priority":1,"blend":0.2},
                 {"op":"transition","machine":"movement","from":"jump","to":"fall","exitTime":0.9,"blend":0.15},
                 {"op":"transition","machine":"movement","from":"fall","to":"land","conditions":[{"param":"grounded"}],"blend":0.05},
                 {"op":"transition","machine":"movement","from":"land","to":"ground","exitTime":1,"blend":0.2,"mode":"crossfade"}]}""");
        assertFalse(machine.get("valid").asBoolean(), "the montage slot output is still missing");
        JsonNode last = call("animgraph_apply", """
                {"label":"montage slot","ops":[
                 {"op":"node","id":"full","type":"slot","name":"fullBody","source":"movement"},{"op":"graph","root":"full"}]}""");
        assertTrue(last.get("valid").asBoolean(), last.toString());
        assertEquals(0, last.get("errors").asInt());
    }

    @Test
    void theLocomotionTestGraphIsBuiltFromScratchThroughTheTools() throws Exception {
        buildLocomotion();
        String built = call("animgraph_get", "{\"section\":\"json\"}").toString();
        try (AnimGraphDocument file = AnimGraphDocument.read(GRAPH)) {
            assertEquals(mapper.readTree(file.json()).toString(), built, "the built graph equals the #157 test file");
        }
        // Problems are reported while it is a draft; one undo step per apply.
        assertEquals("montage slot", call("animgraph_undo", "{}").get("undone").asText());
        JsonNode problems = call("animgraph_get", "{\"section\":\"problems\"}");
        assertTrue(problems.toString().contains("not connected to the output") || problems.toString().contains("root"), problems.toString());
        assertEquals("montage slot", call("animgraph_undo", "{\"redo\":true}").get("redone").asText());

        // A refused batch changes nothing.
        String before = call("animgraph_get", "{\"section\":\"json\"}").toString();
        Exception bad = fails("animgraph_apply", "{\"ops\":[{\"op\":\"param\",\"name\":\"x\",\"type\":\"float\"},"
                + "{\"op\":\"link\",\"parent\":\"idle\",\"child\":\"walk\"}]}");
        assertTrue(bad.getMessage().contains("ops[1] (link)"), bad.getMessage());
        assertEquals(before, call("animgraph_get", "{\"section\":\"json\"}").toString());

        // Layout, then save into the project's AnimGraphs folder; the file reads back as the same graph.
        call("animgraph_apply", "{\"ops\":[{\"op\":\"layout\"}]}");
        JsonNode saved = call("animgraph_save", "{\"path\":\"Locomotion\"}");
        Path file = Path.of(saved.get("saved").asText());
        assertEquals(project.resolve("AnimGraphs/Locomotion.omanimgraph").toAbsolutePath().normalize(), file.toAbsolutePath().normalize());
        try (AnimGraphDocument back = AnimGraphDocument.read(file)) {
            assertTrue(back.valid());
            assertTrue(back.json().contains("\"editor\""), "node positions are saved in the editor section");
        }
        assertTrue(call("animgraph_close", "{}").get("closed").asBoolean());
    }

    /** The goblin's base model, characterized onto the humanoid profile, and its clips as .omanim files. */
    private Path goblinCharacter() throws Exception {
        assumeTrue(Files.isRegularFile(GOBLIN), "missing " + GOBLIN.toAbsolutePath());
        Path models = Files.createDirectories(project.resolve("Models"));
        Path clips = Files.createDirectories(project.resolve("Clips"));
        Path omo = models.resolve("goblin.omo");
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(GOBLIN))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                String n = e.getName();
                if (n.equals("model.omo")) Files.write(omo, in.readAllBytes());
                else if (n.endsWith("clip.omanim")) Files.write(clips.resolve(n.split("/")[1] + ".omanim"), in.readAllBytes());
            }
        }
        Path omchar = project.resolve("Characters/goblin.omchar");
        try (CharacterDocument c = CharacterDocument.fromModel(omo); CharacterProfile h = CharacterProfile.builtin("humanoid")) {
            c.edit("map", null, () -> c.definition().autoMap(c.rig(), h));
            c.save(omchar);
        }
        return omchar;
    }

    @Test
    void theDebuggerMatchesAHeadlessEvaluationOfTheSameInputs() throws Exception {
        Path omchar = goblinCharacter();
        buildLocomotion();
        JsonNode p = call("animgraph_preview", "{\"character\":" + mapper.writeValueAsString(project.relativize(omchar).toString())
                + ",\"slots\":{\"idle\":\"file:Clips/Idle.omanim\",\"walk\":\"file:Clips/Walking.omanim\","
                + "\"run\":\"file:Clips/Walking.omanim\",\"jump\":\"file:Clips/Stab.omanim\",\"fall\":\"file:Clips/Smash.omanim\","
                + "\"land\":\"file:Clips/DrawBow.omanim\"}}");
        assertNull(p.get("problem"), p.toString());
        for (String slot : new String[] {"idle", "walk", "run", "jump", "fall", "land"}) {
            assertEquals(0, p.get("bound").get(slot).asInt(), "slot " + slot + " binds: " + p);
        }
        // The preview setup lives in the graph (one undo step).
        assertTrue(call("animgraph_get", "{\"section\":\"json\"}").toString().contains("Walking.omanim"));

        // Live: restart, walk at 3.25 for 45 frames. Headless: the same inputs on a fresh instance.
        JsonNode live = call("animgraph_evaluate", "{\"params\":{\"speed\":3.25},\"restart\":true,\"frames\":45}");
        JsonNode headless = call("animgraph_evaluate", "{\"params\":{\"speed\":3.25},\"frames\":45,\"headless\":true}");
        assertEquals(headless.get("nodes"), live.get("nodes"), "debugger == headless evaluation");
        assertEquals(headless.get("time"), live.get("time"));
        JsonNode blend = node(live, "locomotion");
        assertEquals(0.0, blend.get("weights").get(0).asDouble(), 1e-6);
        assertEquals(0.5, blend.get("weights").get(1).asDouble(), 1e-4);
        assertEquals(0.5, blend.get("weights").get(2).asDouble(), 1e-4);
        assertEquals("ground", node(live, "movement").get("state").asText());
        assertTrue(node(live, "walk").get("active").asBoolean() && node(live, "walk").get("bound").asBoolean());

        // A jump: trigger, then the machine is in "jump" in both.
        JsonNode jl = call("animgraph_evaluate", "{\"triggers\":[\"jump\"],\"restart\":true,\"frames\":3}");
        JsonNode jh = call("animgraph_evaluate", "{\"triggers\":[\"jump\"],\"frames\":3,\"headless\":true}");
        assertEquals("jump", node(jl, "movement").get("state").asText());
        assertEquals(jh.get("nodes"), jl.get("nodes"));

        // Editing the graph hot-reloads the preview; an error stops it with a reason.
        call("animgraph_apply", "{\"ops\":[{\"op\":\"node\",\"id\":\"locomotion\",\"param\":\"grounded\"}]}");
        JsonNode broken = call("animgraph_evaluate", "{\"frames\":1}");
        assertTrue(broken.get("problem").asText().contains("errors"), broken.toString());
        call("animgraph_undo", "{}");
        assertNull(call("animgraph_evaluate", "{\"frames\":1}").get("problem"));
    }

    private static JsonNode node(JsonNode debug, String id) {
        for (JsonNode n : debug.get("nodes")) if (n.get("id").asText().equals(id)) return n;
        throw new AssertionError("no node " + id + " in " + debug);
    }
}
