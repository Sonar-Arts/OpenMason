package com.openmason.main.systems.particles.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.rendering.particles.FxBinding;
import com.openmason.engine.rendering.particles.FxEventHandler;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleLibrary;
import com.openmason.engine.rendering.particles.omfx.OmfxCodec;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.openmason.main.systems.io.AssetWriteService;
import com.openmason.main.systems.io.WritePolicy;
import com.openmason.main.systems.io.WriteRoots;
import com.openmason.main.systems.io.WriteSandbox;
import com.openmason.main.systems.mcp.McpImageWithData;
import com.openmason.main.systems.mcp.McpToolRegistry;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The {@code particles} MCP group driven through its real handlers, sandbox and engine codec. */
class FxToolsEndToEndTest {

    @TempDir
    Path tmp;

    private final ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private Path project;
    private McpToolRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        MainThreadExecutor.bindToCurrentThread();
        project = Files.createDirectories(tmp.resolve("proj"));
        AssetWriteService writes = new AssetWriteService(null, new WriteSandbox(WriteRoots.of(project,
                Files.createDirectories(tmp.resolve("game")), Files.createDirectories(tmp.resolve("exports")))),
                () -> WritePolicy.ASK_RISKY);
        registry = new McpToolRegistry();
        new FxToolDefinitions(new FxDocumentService(writes), new FxCaptureService(), mapper).registerAll(registry);
    }

    private JsonNode call(String tool, String argsJson) throws Exception {
        Object result = registry.get(tool).handler().call(mapper.readTree(argsJson));
        assertFalse(result instanceof McpImageWithData, "use capture() for images");
        return mapper.valueToTree(result);
    }

    private IllegalArgumentException fails(String tool, String argsJson) {
        return assertThrows(IllegalArgumentException.class,
                () -> registry.get(tool).handler().call(mapper.readTree(argsJson)));
    }

    @Test
    void anAgentBuildsAnFxSystemFromAPresetVerifiesItAndSavesIt() throws Exception {
        JsonNode presets = call("fx_presets", "{}");
        assertTrue(presets.get("systems").toString().contains("campfire"));
        assertTrue(presets.get("effects").toString().contains("sparks"));

        JsonNode created = call("fx_new", "{\"id\":\"bonfire\",\"preset\":\"campfire\"}");
        assertEquals("bonfire", created.get("id").asText());
        assertTrue(created.get("dirty").asBoolean());
        assertEquals("[\"fire\",\"embers\",\"smoke\"]", created.get("emitters").toString());

        JsonNode set = call("fx_set", """
                {"effects":[{"name":"fire","rate":80}],"emitters":[{"name":"smoke","delay":0.2}]}""");
        assertEquals("[\"effects\",\"emitters\"]", set.get("changed").toString());
        assertEquals(80, call("fx_get", "{\"section\":\"effect:fire\"}").get("effect").get("rate").asInt());
        assertEquals(0.2, call("fx_get", "{\"section\":\"emitters\"}").get("emitters").get(2).get("delay").asDouble(),
                1e-6);

        JsonNode sim = call("fx_simulate", "{\"seconds\":2}");
        assertTrue(sim.get("peakByEffect").get("fire").asInt() > 20, sim.toString());
        assertTrue(sim.get("peakByEffect").get("smoke").asInt() > 0, sim.toString());
        assertEquals("endless: runs until stopped", sim.get("stillRunning").asText());
        assertTrue(sim.get("timeline").get(7).get("light").asDouble() > 0.5, "the campfire glows");
        assertEquals(1, sim.get("sounds").get("fire-ignite").asInt());
        assertEquals(0, sim.get("warnings").size(), sim.get("warnings").toString());

        JsonNode saved = call("fx_save", "{}");
        Path file = Path.of(saved.get("saved").asText());
        assertEquals(project.resolve("Particles/bonfire.omfx").toAbsolutePath().normalize(), file.toAbsolutePath()
                .normalize());
        assertFalse(saved.get("promptedUser").asBoolean());
        OmfxDocument onDisk = OmfxCodec.read(file);
        assertEquals("bonfire", onDisk.id());
        assertEquals(80f, onDisk.system().emitter("fire").effect().rate());
        assertFalse(call("fx_documents", "{}").get("documents").get(0).get("dirty").asBoolean());

        call("fx_set", "{\"effects\":[{\"name\":\"fire\",\"rate\":90}]}");
        call("fx_save", "{}");
        assertEquals(90f, OmfxCodec.read(file).system().emitter("fire").effect().rate(), "saved back in place");
        assertEquals("bonfire", call("fx_close", "{}").get("closed").asText());
    }

    @Test
    void renamesRewriteEveryReference() throws Exception {
        call("fx_new", "{\"id\":\"boom\",\"preset\":\"explosion\"}");
        call("fx_set", """
                {"emitters":[{"name":"debris","rename":"rubble"}],"effects":[{"name":"dust","rename":"powder"}],
                 "parameters":[{"name":"scale","rename":"size"}]}""");
        JsonNode system = call("fx_get", "{}").get("system");
        String refs = system.get("events").toString() + system.get("bindings") + system.get("cues");
        assertFalse(refs.contains("\"debris\""), "no reference to the old emitter name is left: " + refs);
        assertEquals("debris", system.get("emitters").get(2).get("effect").asText(), "the effect kept its name");
        var doc = OmfxCodec.withSystemJson(OmfxDocument.of(com.openmason.engine.rendering.particles.FxPresets.EXPLOSION),
                system);
        assertNotNull(doc.system().emitter("rubble"));
        assertEquals("powder", doc.system().emitter("dust").effect().name());
        assertTrue(doc.system().handlers().stream().allMatch(h -> h.trigger() == FxEventHandler.Trigger.CUSTOM
                || h.source().equals("rubble")));
        assertTrue(doc.system().bindings().stream().anyMatch(b -> b.parameter().equals("size")
                && b.target() == FxBinding.Target.SIZE));
        assertTrue(doc.system().bindings().stream().anyMatch(b -> "rubble".equals(b.emitter())));
    }

    @Test
    void aRejectedEditChangesNothingAndUndoRedoStepThroughEdits() throws Exception {
        call("fx_new", "{\"id\":\"fx\",\"preset\":\"campfire\"}");
        String before = call("fx_get", "{}").get("system").toString();
        IllegalArgumentException e = assertThrowsAny(() -> call("fx_set",
                "{\"emitters\":[{\"name\":\"fire\",\"effect\":\"ghost\"}]}"));
        assertTrue(e.getMessage().contains("plays effect 'ghost'"), e.getMessage());
        assertEquals(before, call("fx_get", "{}").get("system").toString());

        e = assertThrowsAny(() -> call("fx_set", "{\"remove\":{\"emitters\":[\"fire\"]}}"));
        assertTrue(e.getMessage().contains("unknown emitter 'fire'"), "the tint binding still names it: "
                + e.getMessage());

        call("fx_set", "{\"effects\":[{\"name\":\"fire\",\"rate\":1}]}");
        call("fx_set", "{\"effects\":[{\"name\":\"fire\",\"rate\":2}]}");
        call("fx_undo", "{}");
        assertEquals(1, rate("fire"));
        call("fx_undo", "{}");
        assertEquals(before, call("fx_get", "{}").get("system").toString());
        call("fx_undo", "{\"redo\":true}");
        assertEquals(1, rate("fire"));
        call("fx_undo", "{\"redo\":true}");
        assertEquals(2, rate("fire"));
        assertTrue(fails("fx_undo", "{\"redo\":true}").getMessage().contains("Nothing to redo"));
        assertTrue(fails("fx_set", "{}").getMessage().contains("Nothing to change"));
        assertTrue(fails("fx_get", "{\"section\":\"lights\"}").getMessage().contains("Did you mean 'light'"));
    }

    @Test
    void spritesAreEmbeddedFromFilesAndRemovable() throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(1, 1, 0xFFFF0000);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        Files.write(project.resolve("puff.png"), png.toByteArray());

        call("fx_new", "{\"id\":\"puffs\",\"preset\":\"smoke\"}");
        call("fx_set", "{\"effects\":[{\"name\":\"smoke\",\"sprite\":\"texture\",\"textures\":[\"puff\"]}]}");
        assertEquals("[\"puff\"]", call("fx_get", "{\"section\":\"light\"}").get("externalTextures").toString());
        JsonNode added = call("fx_sprite", "{\"name\":\"puff\",\"path\":\"project:puff.png\"}");
        assertEquals("4x4", added.get("size").asText());
        assertEquals("[\"smoke\"]", added.get("usedBy").toString());
        JsonNode got = call("fx_get", "{\"section\":\"light\"}");
        assertNull(got.get("externalTextures"));
        assertEquals("png", got.get("sprites").get(0).get("kind").asText());

        Files.writeString(project.resolve("bad.png"), "nope");
        assertThrowsAny(() -> call("fx_sprite", "{\"name\":\"x\",\"path\":\"project:bad.png\"}"));
        call("fx_sprite", "{\"name\":\"puff\",\"remove\":true}");
        assertEquals("[\"puff\"]", call("fx_get", "{\"section\":\"light\"}").get("externalTextures").toString());
    }

    @Test
    void simulationReportsEventsRaisesFinishAndBadInput() throws Exception {
        call("fx_new", "{\"id\":\"rocket\",\"preset\":\"firework\"}");
        JsonNode sim = call("fx_simulate", "{\"seconds\":4}");
        assertEquals(1, sim.get("hostEvents").get("boom").get("count").asInt(), sim.toString());
        assertTrue(sim.get("hostEvents").get("boom").get("first").get(1).asDouble() > 5, "bursts high up");
        assertEquals(1, sim.get("sounds").get("firework-boom").asInt());
        assertTrue(sim.get("finishedAt").asDouble() < 4, sim.toString());
        call("fx_close", "{\"discard\":true}");

        call("fx_new", "{\"id\":\"blast\",\"preset\":\"explosion\"}");
        sim = call("fx_simulate", "{\"seconds\":0.5,\"raise\":[{\"t\":0.1,\"name\":\"shockwave\"},"
                + "{\"t\":0.1,\"name\":\"nope\"}],\"params\":{\"scale\":2,\"debrisTint\":\"#FF0000\"}}");
        assertTrue(sim.get("peakByEffect").get("dust").asInt() >= 40, "the raise spawned the dust ring: " + sim);
        assertTrue(sim.get("warnings").toString().contains("raise 'nope' has no CUSTOM handler"));
        assertTrue(fails("fx_simulate", "{\"params\":{\"scal\":1}}").getMessage().contains("Did you mean 'scale'"));
        assertTrue(fails("fx_simulate", "{\"params\":{\"scale\":[1,2,3]}}").getMessage().contains("pass a number"));
        assertTrue(fails("fx_simulate", "{\"seconds\":500}").getMessage().contains("seconds must be"));

        JsonNode floored = call("fx_simulate", "{\"seconds\":2}");
        assertTrue(floored.get("hostEvents").has("debris-land"), "debris lands on the floor: " + floored);
        call("fx_close", "{\"discard\":true}");

        call("fx_new", "{\"id\":\"spark\",\"preset\":\"sparks\"}");
        assertTrue(lowestY(call("fx_simulate", "{\"seconds\":1,\"no_floor\":true}")) < -1,
                "sparks fall through without a floor");
        assertTrue(lowestY(call("fx_simulate", "{\"seconds\":1}")) > -0.1, "and bounce on it with one");
    }

    @Test
    void v1LibrariesOpenMigratedAndNeverGetOverwritten() throws Exception {
        ParticleLibrary lib = new ParticleLibrary().register(ParticleLibrary.Presets.SPARKS)
                .register(ParticleEffect.builder("glow").rate(5).build());
        Path v1 = project.resolve("old.omfx");
        try (var out = Files.newOutputStream(v1)) {
            lib.write(out);
        }
        byte[] original = Files.readAllBytes(v1);
        JsonNode opened = call("fx_open", "{\"path\":\"project:old.omfx\"}");
        assertEquals(1, opened.get("version").asInt());
        assertEquals(2, opened.get("documents").size());
        assertTrue(opened.get("note").asText().contains("migrated"));
        assertTrue(fails("fx_get", "{}").getMessage().contains("Several FX documents are open"));

        JsonNode saved = call("fx_save", "{\"id\":\"glow\"}");
        assertTrue(saved.get("saved").asText().endsWith("glow.omfx"), saved.toString());
        assertArrayEquals(original, Files.readAllBytes(v1), "the v1 library is untouched");

        JsonNode reopened = call("fx_open", "{\"path\":\"" + saved.get("saved").asText().replace("\\", "\\\\") + "\"}");
        assertEquals(2, reopened.get("version").asInt());
        assertTrue(fails("fx_close", "{\"id\":\"sparks\"}").getMessage().contains("unsaved changes"));
        assertTrue(fails("fx_new", "{\"id\":\"glow\"}").getMessage().contains("already open"));
        assertTrue(fails("fx_new", "{\"id\":\"x\",\"preset\":\"sparkz\"}").getMessage().contains("sparks"));
    }

    @Test
    void captureWithoutAGlContextFailsWithAReason() throws Exception {
        call("fx_new", "{\"id\":\"c\",\"preset\":\"fire\"}");
        Exception e = assertThrows(IllegalStateException.class,
                () -> registry.get("fx_capture").handler().call(mapper.readTree("{\"size\":128}")));
        assertTrue(e.getMessage().contains("needs the editor's GL context"), e.getMessage());
    }

    // ------------------------------------------------------------------------------------------------

    private int rate(String effect) throws Exception {
        return call("fx_get", "{\"section\":\"effect:" + effect + "\"}").get("effect").get("rate").asInt();
    }

    private static double lowestY(JsonNode sim) {
        double low = Double.MAX_VALUE;
        for (JsonNode s : sim.get("timeline")) {
            if (s.has("min")) {
                low = Math.min(low, s.get("min").get(1).asDouble());
            }
        }
        return low;
    }

    private interface Call {
        Object run() throws Exception;
    }

    private static IllegalArgumentException assertThrowsAny(Call c) {
        try {
            c.run();
        } catch (IllegalArgumentException e) {
            return e;
        } catch (Exception e) {
            return new IllegalArgumentException(e.getMessage(), e);
        }
        throw new AssertionError("expected a failure");
    }
}
