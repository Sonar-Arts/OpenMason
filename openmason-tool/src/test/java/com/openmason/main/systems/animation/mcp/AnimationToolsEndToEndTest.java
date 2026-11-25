package com.openmason.main.systems.animation.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.anim.AnimClipDocument;
import com.openmason.engine.anim.CendaAnimClip;
import com.openmason.main.systems.animation.AnimationSessions;
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
 * OpenMason #165 acceptance through the {@code anim_*} MCP tools alone (real handlers, write sandbox, native
 * authoring), on the re-rigged goblin and its Walking clip: a clip opens and saves losslessly; layers (override,
 * additive, keyed weight), bone and part keys, the control rig (FK -> IK -> FK leaves the pose unchanged), notifies,
 * curves, markers, root motion and the runtime layer all survive a save / reopen; edits are atomic and undoable.
 */
class AnimationToolsEndToEndTest {

    private static final Path GOBLIN = Path.of("../test-fixtures/stonebreak/resources/sbe/Mobs/SB_Goblin.sbe");

    @TempDir
    Path tmp;

    private final ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final AnimationSessions sessions = new AnimationSessions();
    private AnimationEditingService service;
    private Path project;
    private McpToolRegistry registry;
    private byte[] walking;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(CendaAnimClip.isAvailable(), "native clip module unavailable");
        assumeTrue(Files.isRegularFile(GOBLIN), "Stonebreak fixtures missing at " + GOBLIN.toAbsolutePath());
        MainThreadExecutor.bindToCurrentThread();
        project = Files.createDirectories(tmp.resolve("proj"));
        sessions.setProjectRoot(() -> project);
        AssetWriteService writes = new AssetWriteService(null, new WriteSandbox(WriteRoots.of(project,
                Files.createDirectories(tmp.resolve("game")), Files.createDirectories(tmp.resolve("exports")))),
                () -> WritePolicy.ASK_RISKY);
        registry = new McpToolRegistry();
        service = new AnimationEditingService(() -> sessions, writes);
        new AnimationToolDefinitions(service, mapper).registerAll(registry);
        Files.createDirectories(project.resolve("Models"));
        Files.createDirectories(project.resolve("Animations"));
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(GOBLIN))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().equals("model.omo")) Files.write(project.resolve("Models/SB_Goblin.omo"), in.readAllBytes());
                if (e.getName().equals("states/Walking/clip.omanim")) walking = in.readAllBytes();
            }
        }
        assertNotNull(walking);
        Files.write(project.resolve("Animations/Walking.omanim"), walking);
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

    private JsonNode world(float t) throws Exception {
        return call("anim_get_pose", "{\"time\":" + t + "}").get("joints");
    }

    private static double maxWorldDiff(JsonNode a, JsonNode b) {
        double m = 0;
        for (int i = 0; i < a.size(); i++) {
            for (int k = 0; k < 3; k++) {
                m = Math.max(m, Math.abs(a.get(i).get("world").get(k).asDouble() - b.get(i).get("world").get(k).asDouble()));
            }
        }
        return m;
    }

    @Test
    void aClipOpensAndSavesLosslessly() throws Exception {
        JsonNode info = call("anim_open", "{\"path\":\"project:Animations/Walking.omanim\",\"model\":\"project:Models/SB_Goblin.omo\"}");
        assertTrue(info.get("clip").get("trivial").asBoolean());
        assertFalse(info.get("dirty").asBoolean());
        assertTrue(info.get("clip").get("layers").get(0).get("tracks").size() > 0);
        JsonNode saved = call("anim_save", "{\"path\":\"project:Animations/Copy.omanim\"}");
        assertTrue(saved.get("ok").asBoolean(), saved.toString());
        // The only change a save makes is the model reference; the clip itself (tracks, sections) is untouched.
        try (AnimClipDocument a = AnimClipDocument.read(walking);
             AnimClipDocument b = AnimClipDocument.read(Files.readAllBytes(project.resolve("Animations/Copy.omanim")))) {
            assertEquals(a.tracks(), b.tracks());
            assertEquals(a.header(), b.header());
            assertEquals(a.notifies(), b.notifies());
            assertEquals("../Models/SB_Goblin.omo", b.modelRef().orElseThrow());
        }
    }

    @Test
    void layersControlRigAndTracksRoundTripThroughTheTools() throws Exception {
        call("anim_open", "{\"path\":\"project:Animations/Walking.omanim\",\"model\":\"project:Models/SB_Goblin.omo\"}");
        float t = 0.37f;
        JsonNode before = world(t);

        // Layers: an override on the left arm at half weight, an additive nod with a keyed weight.
        call("anim_edit", """
                {"label":"layers","ops":[
                  {"op":"layer","name":"Arms","weight":0.5,"mask":[{"joint":"shoulder_l","branch":true}]},
                  {"op":"key","layer":"Arms","joint":"elbow_l","time":0,"rot":[0,0,70]},
                  {"op":"layer","name":"Nod","mode":"additive"},
                  {"op":"key","layer":"Nod","joint":"neck","time":0,"rot":[15,0,0]},
                  {"op":"layer_weight","layer":"Nod","time":0,"value":0},
                  {"op":"layer_weight","layer":"Nod","time":0.5,"value":1}]}""");
        JsonNode layered = world(t);
        assertTrue(maxWorldDiff(before, layered) > 0.01, "the layers move the goblin");
        JsonNode lv = call("anim_get_pose", "{\"time\":0,\"joints\":[\"elbow_l\"],\"layer\":\"Arms\"}");
        assertEquals(70.0, lv.get("joints").get(0).get("layerValue").get("rot").get(2).asDouble(), 1e-3,
                "the layer's own value is what was keyed");

        // Control rig: FK -> IK -> FK at the same time leaves the pose where it was. Match to FK keys a layer that
        // holds the pose: the half-weight Arms cannot, so it is refused there; at full weight no layer needs naming.
        call("anim_edit", """
                {"ops":[{"op":"effector","name":"Left arm","kind":"twoBone","upper":"shoulder_l","mid":"elbow_l","end":"wrist_l"}]}""");
        call("anim_edit", "{\"ops\":[{\"op\":\"match\",\"effector\":\"Left arm\",\"time\":0.37,\"to\":\"ik\"}]}");
        assertTrue(fails("anim_edit", "{\"ops\":[{\"op\":\"match\",\"effector\":\"Left arm\",\"time\":0.37,\"to\":\"fk\","
                + "\"layer\":\"Arms\"}]}").getMessage().contains("no layer holds the pose"));
        service.undo(false);
        call("anim_edit", "{\"ops\":[{\"op\":\"layer\",\"name\":\"Arms\",\"weight\":1}]}");
        JsonNode fk = world(t);
        call("anim_edit", "{\"ops\":[{\"op\":\"match\",\"effector\":\"Left arm\",\"time\":0.37,\"to\":\"ik\"}]}");
        assertTrue(maxWorldDiff(fk, world(t)) < 1e-4, "match to IK does not move the limb");
        JsonNode handles = call("anim_get_pose", "{\"time\":0.37}").get("handles");
        assertEquals(1.0, handles.get(0).get("weight").asDouble(), 1e-6);
        call("anim_edit", "{\"ops\":[{\"op\":\"match\",\"effector\":\"Left arm\",\"time\":0.37,\"to\":\"fk\"}]}");
        assertTrue(maxWorldDiff(fk, world(t)) < 1e-4, "match back to FK does not move the limb");

        // Tracks, root motion, the runtime layer.
        call("anim_edit", """
                {"ops":[
                  {"op":"notify","name":"footstep","time":0.25,"payload":{"foot":"left"}},
                  {"op":"notify","name":"swing","time":0.1,"duration":0.2},
                  {"op":"marker","name":"left_down","time":0.25},
                  {"op":"curve","name":"lean","keys":[{"time":0,"value":0},{"time":0.5,"value":1,"interp":"CUBIC"}]},
                  {"op":"root_motion","joint":"root","axes":["x","z"]},
                  {"op":"clip_layer","type":"OVERLAY","mask":["shoulder_l"],"fadeIn":0.2,"priority":2}]}""");
        JsonNode view = call("anim_get_info", "{}").get("clip");

        // Atomic: a batch with a bad op changes nothing.
        Exception e = fails("anim_edit", "{\"ops\":[{\"op\":\"header\",\"name\":\"X\"},{\"op\":\"key\",\"joint\":\"no_such\",\"time\":0}]}");
        assertTrue(e.getMessage().contains("ops[1] (key)"), e.getMessage());
        assertEquals(view, call("anim_get_info", "{}").get("clip"));

        // Save, reopen: every authoring layer, IK key and section comes back.
        assertTrue(call("anim_save", "{\"path\":\"project:Animations/Authored.omanim\"}").get("ok").asBoolean());
        call("anim_open", "{\"path\":\"project:Animations/Authored.omanim\"}");  // modelRef finds the model
        JsonNode reopened = call("anim_get_info", "{}");
        assertFalse(reopened.get("dirty").asBoolean());
        assertEquals(view.get("layers"), reopened.get("clip").get("layers"));
        assertEquals(view.get("effectors"), reopened.get("clip").get("effectors"));
        assertEquals(view.get("notifies"), reopened.get("clip").get("notifies"));
        assertEquals(view.get("curves"), reopened.get("clip").get("curves"));
        assertEquals(view.get("markers"), reopened.get("clip").get("markers"));
        assertEquals(view.get("rootMotion"), reopened.get("clip").get("rootMotion"));
        assertEquals(view.get("clipLayer"), reopened.get("clip").get("clipLayer"));
        assertTrue(maxWorldDiff(fk, world(t)) < 1e-4);
        // The joint tracks the runtime plays are the bake: the game reads the file without the authoring section.
        try (AnimClipDocument baked = AnimClipDocument.read(Files.readAllBytes(project.resolve("Animations/Authored.omanim")))) {
            assertTrue(baked.tracks().stream().anyMatch(tr -> "elbow_l".equals(tr.partName()) && tr.bone()));
            int frames = Math.round(baked.header().duration() * baked.header().fps());
            assertEquals(frames + 1, baked.tracks().stream().filter(tr -> "elbow_l".equals(tr.partName())).findFirst()
                    .orElseThrow().keys().size(), "baked at every frame");
        }
    }

    @Test
    void editsUndoAsOneStepAndANewClipKeysBones() throws Exception {
        JsonNode created = call("anim_new", "{\"model\":\"project:Models/SB_Goblin.omo\",\"name\":\"Wave\",\"duration\":0.5}");
        assertEquals("Wave", created.get("clip").get("name").asText());
        assertFalse(created.get("dirty").asBoolean());
        call("anim_edit", """
                {"label":"wave","ops":[
                  {"op":"key","joint":"shoulder_r","time":0,"rot":[0,0,0]},
                  {"op":"key","joint":"shoulder_r","time":0.25,"rot":[0,0,-120]},
                  {"op":"key","joint":"wrist_r","time":0.25,"rot":[0,30,0],"easing":"EASE_IN_OUT"}]}""");
        JsonNode info = call("anim_get_info", "{}");
        assertTrue(info.get("dirty").asBoolean());
        assertEquals(2, info.get("clip").get("layers").get(0).get("tracks").size());
        assertEquals("wave", service.undo(false) instanceof com.openmason.main.systems.mcp.McpAck ack
                ? mapper.valueToTree(ack).get("undone").asText() : null);
        assertEquals(0, call("anim_get_info", "{}").get("clip").get("layers").get(0).get("tracks").size());
        assertFalse(call("anim_get_info", "{}").get("dirty").asBoolean(), "undone back to the saved state");
        service.undo(true);
        assertTrue(call("anim_save", "{\"path\":\"project:Animations/Wave.omanim\"}").get("ok").asBoolean());
        try (AnimClipDocument c = AnimClipDocument.read(Files.readAllBytes(project.resolve("Animations/Wave.omanim")))) {
            assertEquals(2, c.tracks().size());
            assertTrue(c.tracks().stream().allMatch(AnimClipDocument.Track::bone), "bones key like parts");
        }
        // Transport.
        JsonNode tr = call("anim_transport", "{\"action\":\"seek\",\"time\":0.2}");
        assertEquals(0.2, tr.get("time").asDouble(), 1e-6);
        // An unsaved clip is never replaced silently.
        call("anim_edit", "{\"ops\":[{\"op\":\"header\",\"name\":\"Wave 2\"}]}");
        assertTrue(fails("anim_new", "{\"model\":\"project:Models/SB_Goblin.omo\"}").getMessage().contains("unsaved"));
        assertEquals("New Clip", call("anim_new", "{\"model\":\"project:Models/SB_Goblin.omo\",\"discard\":true}")
                .get("clip").get("name").asText());
    }
}
