package com.openmason.engine.rendering.particles.omfx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.format.ext.FormatExtensions;
import com.openmason.engine.format.sound.SoundData;
import com.openmason.engine.format.sound.SoundDef;
import com.openmason.engine.rendering.particles.FxBinding;
import com.openmason.engine.rendering.particles.FxEmitter;
import com.openmason.engine.rendering.particles.FxEventHandler;
import com.openmason.engine.rendering.particles.FxLight;
import com.openmason.engine.rendering.particles.FxParameter;
import com.openmason.engine.rendering.particles.FxPresets;
import com.openmason.engine.rendering.particles.FxSoundCue;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleImage;
import com.openmason.engine.rendering.particles.ParticleLibrary;
import com.openmason.engine.rendering.particles.graph.FxAttribute;
import com.openmason.engine.rendering.particles.graph.FxGraph;
import com.openmason.engine.rendering.particles.graph.FxGraphJson;
import com.openmason.engine.rendering.particles.graph.FxModules;
import com.openmason.engine.rendering.particles.graph.FxStageKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class OmfxCodecTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ------------------------------------------------------------------------------------------------
    // Round trips
    // ------------------------------------------------------------------------------------------------

    @Test
    void everyEffectPresetRoundTripsAsASingleEmitterSystem() throws IOException {
        for (ParticleEffect e : ParticleLibrary.Presets.ALL) {
            assertRoundTrip(OmfxDocument.of(FxSystem.of(e, 60)));
        }
    }

    @Test
    void everyFxPresetRoundTrips() throws IOException {
        for (FxSystem s : FxPresets.ALL) {
            assertRoundTrip(OmfxDocument.of(s));
        }
    }

    @Test
    void aDocumentUsingEveryFeatureRoundTripsExactly() throws IOException {
        OmfxDocument doc = kitchenSink();
        assertRoundTrip(doc);
        OmfxDocument back = OmfxCodec.read(OmfxCodec.write(doc));
        assertEquals("Sonar Arts", back.author());
        assertEquals("kitchen", back.id());
        assertEquals(List.of("puff", "omt-smoke"), back.sprites().stream().map(OmfxSprite::name).toList());
        assertEquals(OmfxSprite.Kind.PNG, back.sprite("puff").kind());
        assertEquals(OmfxSprite.Kind.OMT, back.sprite("omt-smoke").kind());
        assertArrayEquals("WAVE".getBytes(StandardCharsets.UTF_8), back.soundFile("sounds/whoosh.wav"));
        assertNotNull(back.system().emitter("swirl").effect().graph(), "graph survives");
        assertTrue(back.system().emitter("aimed").hasDirection());
        assertEquals(FxEmitter.FOREVER, back.system().emitter("swirl").duration());
    }

    @Test
    void theArchiveHasTheDocumentedLayout() throws IOException {
        Map<String, byte[]> entries = entries(OmfxCodec.write(kitchenSink()));
        assertEquals(List.of("manifest.json", "system.json", "graphs/swirl.json", "textures/puff.png",
                        "textures/omt-smoke.omt", "sounds/whoosh.wav", "stonebreak/notes.txt"),
                new ArrayList<>(entries.keySet()));

        var manifest = JSON.readTree(entries.get("manifest.json"));
        assertEquals("openmason.fx", manifest.get("format").asText());
        assertEquals(2, manifest.get("version").asInt());
        assertEquals("kitchen", manifest.get("id").asText());
        assertEquals("Sonar Arts", manifest.get("author").asText());

        var graph = FxGraphJson.read(new String(entries.get("graphs/swirl.json"), StandardCharsets.UTF_8));
        assertEquals(kitchenSink().system().emitter("swirl").effect().graph(), graph, "graph IR is a plain fxgraph");

        var system = JSON.readTree(entries.get("system.json"));
        assertEquals("graphs/swirl.json", findByName(system.get("effects"), "swirl").get("graph").asText());
        assertEquals("forever", findByName(system.get("emitters"), "swirl").get("duration").asText());
        assertTrue(findByName(system.get("emitters"), "swirl").get("direction").isNull());
        assertEquals(OmfxCodec.sha256("WAVE".getBytes(StandardCharsets.UTF_8)),
                system.get("sounds").get(0).get("checksum").asText());
    }

    @Test
    void extensionsAndForeignManifestFieldsAreKeptByteForByte() throws IOException {
        String quirky = "{\"tier\":  3, \"tags\" : [ \"a\",\"b\" ] }";
        OmfxDocument doc = kitchenSink();
        byte[] bytes = OmfxCodec.write(doc);
        // hand-edit the manifest the way another tool would: odd spacing, a foreign top-level field
        Map<String, byte[]> entries = entries(bytes);
        String manifest = """
                { "format": "openmason.fx", "version": 2, "id": "kitchen", "author": "Sonar Arts",
                  "legacyThing":   [1,2 ,3],
                  "extensions": { "stonebreak:gameplay": %s } }
                """.formatted(quirky);
        entries.put("manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
        OmfxDocument read = OmfxCodec.read(zip(entries));
        assertTrue(read.extensions().has("stonebreak:gameplay"));
        assertEquals(List.of("legacyThing"), new ArrayList<>(read.extensions().legacyFieldNames()));

        String rewritten = new String(entries(OmfxCodec.write(read)).get("manifest.json"), StandardCharsets.UTF_8);
        assertTrue(rewritten.contains(quirky), "extension text written back unchanged:\n" + rewritten);
        assertTrue(rewritten.contains("[1,2 ,3]"), "foreign field written back unchanged:\n" + rewritten);
        assertEquals(read, OmfxCodec.read(OmfxCodec.write(read)));
    }

    @Test
    void writingIsDeterministic() throws IOException {
        assertArrayEquals(OmfxCodec.write(kitchenSink()), OmfxCodec.write(kitchenSink()));
    }

    @Test
    void writingFillsInChecksumsOfStubSoundDefs() throws IOException {
        FxSystem system = FxSystem.builder("stub").emitter(FxEmitter.builder("a", ParticleLibrary.Presets.SMOKE).build())
                .sounds(new SoundData(List.of(new SoundDef("puff", "sounds/puff.ogg", "", null, 1f, 1f, 1f, false))))
                .cue(FxSoundCue.onPlay("puff")).build();
        OmfxDocument doc = new OmfxDocument("", system, List.of(), Map.of("sounds/puff.ogg", new byte[]{1, 2, 3}),
                null, null);
        OmfxDocument back = OmfxCodec.read(OmfxCodec.write(doc));
        assertEquals(OmfxCodec.sha256(new byte[]{1, 2, 3}), back.system().sounds().sounds().get(0).checksum());
        assertEquals(back, OmfxCodec.read(OmfxCodec.write(back)));
    }

    @Test
    void writingToAFileReplacesItAndReadsBack(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("sub/kitchen.omfx");
        OmfxCodec.write(kitchenSink(), file);
        OmfxCodec.write(kitchenSink(), file);
        assertEquals(kitchenSink(), OmfxCodec.read(file));
        assertEquals(1, java.nio.file.Files.list(file.getParent()).count(), "no temp file left behind");
    }

    // ------------------------------------------------------------------------------------------------
    // Sprites, effects, extra data
    // ------------------------------------------------------------------------------------------------

    @Test
    void embeddedSpritesDecodeAndOtherNamesGoToTheHost() throws IOException {
        OmfxDocument doc = OmfxCodec.read(OmfxCodec.write(kitchenSink()));
        ParticleImage fromHost = new ParticleImage(1, 1, new int[]{0x123456FF});
        var loader = doc.spriteLoader(name -> name.equals("host-flare") ? fromHost : null);

        ParticleImage puff = loader.load("puff");
        assertEquals(2, puff.width());
        assertEquals(0xFF0000FF, puff.texel(0, 0));
        assertEquals(0x00FF0080, puff.texel(1, 1));
        ParticleImage omt = loader.load("omt-smoke");
        assertEquals(0x00FF00FF, omt.texel(0, 0), "OMT layers are flattened (top layer wins where opaque)");
        assertEquals(0x0000FFFF, omt.texel(1, 0), "lower layer shows where the top one is clear");
        assertSame(fromHost, loader.load("host-flare"));
        assertNull(loader.load("nowhere"));

        assertEquals(List.of("puff", "omt-smoke", "host-flare"), new ArrayList<>(doc.referencedTextures()));
        assertEquals(List.of("host-flare"), new ArrayList<>(doc.externalTextures()));
    }

    @Test
    void effectsAreStoredOnceAndSharedByEmittersAndSubEmitters() throws IOException {
        Map<String, byte[]> entries = entries(OmfxCodec.write(OmfxDocument.of(FxPresets.EXPLOSION)));
        var effects = JSON.readTree(entries.get("system.json")).get("effects");
        List<String> names = new ArrayList<>();
        effects.forEach(e -> names.add(e.get("name").asText()));
        assertEquals(List.of("smoke", "explosion", "sparks", "debris", "dust"), names,
                "sub-emitter targets first, each effect once");
    }

    @Test
    void twoDifferentEffectsWithOneNameCannotBeWritten() {
        ParticleEffect a = ParticleEffect.builder("same").rate(5).build();
        ParticleEffect b = ParticleEffect.builder("same").rate(9).build();
        FxSystem s = FxSystem.builder("clash").emitter(FxEmitter.builder("a", a).build())
                .emitter(FxEmitter.builder("b", b).build()).build();
        IOException e = assertThrows(IOException.class, () -> OmfxCodec.write(OmfxDocument.of(s)));
        assertTrue(e.getMessage().contains("two different effects are named 'same'"), e.getMessage());
    }

    @Test
    void entriesOutsideTheFormatAreKeptAndUnreferencedFormatEntriesDropped() throws IOException {
        Map<String, byte[]> entries = entries(OmfxCodec.write(kitchenSink()));
        entries.put("textures/orphan.png", png(1, 1, 0xFFFFFFFF));
        entries.put("graphs/orphan.json", "{}".getBytes(StandardCharsets.UTF_8));
        entries.put("mygame/data.bin", new byte[]{9, 8, 7});
        OmfxDocument doc = OmfxCodec.read(zip(entries));
        assertEquals(List.of("mygame/data.bin", "stonebreak/notes.txt"), new ArrayList<>(doc.extraFiles().keySet()));
        Map<String, byte[]> rewritten = entries(OmfxCodec.write(doc));
        assertFalse(rewritten.containsKey("textures/orphan.png"));
        assertFalse(rewritten.containsKey("graphs/orphan.json"));
        assertArrayEquals(new byte[]{9, 8, 7}, rewritten.get("mygame/data.bin"));
    }

    @Test
    void fileNamesAreSanitisedAndNeverCollide() throws IOException {
        ParticleEffect weird = ParticleEffect.builder("a/b c").textures(List.of("x/y", "x_y", "X_Y"), true).graph(
                FxGraph.of(List.of(), FxGraph.withModule(FxStageKind.PARTICLE_UPDATE, FxModules.attractor(0, 1, 0, 2f),
                        "move"))).build();
        OmfxDocument doc = OmfxDocument.of(FxSystem.of(weird, 1))
                .withSprite(new OmfxSprite("x/y", png(1, 1, 0xFF0000FF)))
                .withSprite(new OmfxSprite("x_y", png(1, 1, 0x00FF00FF)))
                .withSprite(new OmfxSprite("X_Y", png(1, 1, 0x0000FFFF)));
        Map<String, byte[]> entries = entries(OmfxCodec.write(doc));
        assertTrue(entries.containsKey("graphs/a_b_c.json"), entries.keySet().toString());
        assertTrue(entries.containsKey("textures/x_y.png"));
        assertTrue(entries.containsKey("textures/x_y-2.png"));
        assertTrue(entries.containsKey("textures/X_Y-3.png"), "case-insensitive file systems stay safe");
        OmfxDocument back = OmfxCodec.read(OmfxCodec.write(doc));
        assertEquals(doc, back);
        assertEquals(0x0000FFFF, back.decodeSprite("X_Y").texel(0, 0));
    }

    @Test
    void aMinimalHandWrittenSystemUsesDefaults() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.json", """
                { "format": "openmason.fx", "version": 2, "id": "tiny" }""".getBytes(StandardCharsets.UTF_8));
        entries.put("system.json", """
                { "effects": [ { "name": "puff", "rate": 4 } ],
                  "emitters": [ { "name": "main", "effect": "puff" } ],
                  "parameters": [ { "name": "k", "type": "float" }, { "name": "c", "type": "color" } ] }
                """.getBytes(StandardCharsets.UTF_8));
        OmfxDocument doc = OmfxCodec.read(zip(entries));
        assertEquals("", doc.author());
        FxEmitter main = doc.system().emitter("main");
        assertEquals(4f, main.effect().rate());
        assertEquals(FxEmitter.builder("main", main.effect()).build(), main, "emitter members default like the builder");
        assertEquals(FxParameter.scalar("k", 0f), doc.system().parameters().get(0));
        assertEquals(FxParameter.color("c", 0xFFFFFFFF), doc.system().parameters().get(1));
        assertNull(doc.system().light());
    }

    // ------------------------------------------------------------------------------------------------
    // Malformed documents
    // ------------------------------------------------------------------------------------------------

    @Test
    void malformedDocumentsFailWithAReason() throws IOException {
        byte[] good = OmfxCodec.write(kitchenSink());

        assertFails("not an .omfx v2 archive", "{ \"effects\": [] }".getBytes(StandardCharsets.UTF_8));
        assertFails("manifest.json is missing", without(good, "manifest.json"));
        assertFails("system.json is missing", without(good, "system.json"));
        assertFails("format must be 'openmason.fx'", manifest(good, m -> m.put("format", "openmason.particles")));
        assertFails("newer than supported 2", manifest(good, m -> m.put("version", 3)));
        assertFails("'version' must be an integer", manifest(good, m -> m.put("version", "2")));
        assertFails("'id' must be a non-blank string", manifest(good, m -> m.remove("id")));
        assertFails("manifest.json: not valid JSON", replace(good, "manifest.json", "{ nope"));
        assertFails("system.json: not valid JSON", replace(good, "system.json", "[1, 2"));
        assertFails("plays effect 'ghost', which is not in 'effects'",
                system(good, s -> ((ObjectNode) s.get("emitters").get(0)).put("effect", "ghost")));
        assertFails("unknown Target 'warp'",
                system(good, s -> ((ObjectNode) s.get("bindings").get(0)).put("target", "warp")));
        assertFails("'duration' must be a number or \"forever\"",
                system(good, s -> ((ObjectNode) s.get("emitters").get(0)).put("duration", "always")));
        assertFails("binding names unknown parameter 'nope'",
                system(good, s -> ((ObjectNode) s.get("bindings").get(0)).put("parameter", "nope")));
        assertFails("graph 'graphs/swirl.json' is missing", without(good, "graphs/swirl.json"));
        assertFails("invalid FX graph", replace(good, "graphs/swirl.json",
                "{ \"format\": \"openmason.fxgraph\", \"version\": 1, \"stages\": [ { \"stage\": \"warp\" } ] }"));
        assertFails("file 'textures/puff.png' is missing", without(good, "textures/puff.png"));
        assertFails("sprite 'omt-smoke' is neither a PNG nor an OMT", replace(good, "textures/omt-smoke.omt", "junk"));
        assertFails("does not match its checksum", replace(good, "sounds/whoosh.wav", "WAVX"));
        assertFails("file 'sounds/whoosh.wav' is missing", without(good, "sounds/whoosh.wav"));
        assertFails("sub-emitter cycle", system(good, s -> {
            ObjectNode smoke = (ObjectNode) findByName(s.get("effects"), "omt");
            smoke.putArray("subEmitters").addObject().put("effect", "omt").put("trigger", "death");
        }));
        assertFails("is not a relative '/'-separated path", with(good, "/etc/passwd", "x"));
        assertFails("has an empty, '.' or '..' segment", with(good, "textures/../../evil.png", "x"));
        IOException truncated = assertThrows(IOException.class,
                () -> OmfxCodec.read(java.util.Arrays.copyOf(good, good.length / 2)));
        assertFalse(truncated.getMessage().isBlank());
    }

    // ------------------------------------------------------------------------------------------------
    // Fixtures and helpers
    // ------------------------------------------------------------------------------------------------

    /** A system exercising every member of the format. */
    static OmfxDocument kitchenSink() throws IOException {
        FxGraph graph = FxGraph.of(List.of(FxAttribute.scalar("heat", 0.5f)),
                FxGraph.withModule(FxStageKind.PARTICLE_UPDATE, FxModules.curlNoiseVelocity(2f, 0.5f, 0.1f), "forces"));
        ParticleEffect swirl = ParticleEffect.builder("swirl").rate(20).lifetime(1, 2).graph(graph)
                .textures(List.of("puff", "omt-smoke"), true).build();
        ParticleEffect omt = ParticleEffect.builder("omt").rate(0).textures(List.of("omt-smoke"), false)
                .subEmitter(ParticleEffect.SubEmitter.on(ParticleEffect.Trigger.DEATH, ParticleLibrary.Presets.DUST, 3))
                .build();
        ParticleEffect flare = ParticleEffect.builder("flare").rate(0).textures(List.of("host-flare"), false).build();
        FxSystem system = FxSystem.builder("kitchen")
                .emitter(FxEmitter.builder("swirl", swirl).build())
                .emitter(FxEmitter.builder("aimed", omt).offset(1, 2, 3).delay(0.5f).duration(1.25f).loops(3)
                        .interval(0.75f).burst(12).rateScale(0.5f).sizeScale(1.5f).speedScale(2f).tint(0x80FF40C0)
                        .direction(0, 0, 1).build())
                .emitter(FxEmitter.builder("flare", flare).eventOnly().build())
                .emitter(FxEmitter.builder("again", swirl).loops(0).duration(2f).build())
                .parameter(FxParameter.scalar("intensity", 0.7f))
                .parameter(FxParameter.vector("push", 0.1f, -2f, 3.5f))
                .parameter(FxParameter.color("tint", 0x336699CC))
                .bind(FxBinding.of("intensity", FxBinding.Target.RATE))
                .bind(FxBinding.mapped("intensity", FxBinding.Target.SIZE, "aimed", 0.5f, 1f))
                .bind(FxBinding.of("push", FxBinding.Target.OFFSET, "swirl"))
                .bind(FxBinding.of("tint", FxBinding.Target.TINT))
                .bind(FxBinding.of("tint", FxBinding.Target.LIGHT_COLOR))
                .bind(FxBinding.of("intensity", FxBinding.Target.VOLUME))
                .on(FxEventHandler.spawn(FxEventHandler.Trigger.DEATH, "aimed", "flare", 4).inheriting(0.25f)
                        .withChance(0.5f).forwarding("aimed-died").playing("whoosh"))
                .on(FxEventHandler.forward(FxEventHandler.Trigger.COLLISION, "swirl", "hit"))
                .on(FxEventHandler.spawn(FxEventHandler.Trigger.CUSTOM, "kaboom", "flare", 30))
                .light(FxLight.steady(0xFFAA33FF, 2.5f, 12f).flickering(0.4f, 6f).offset(0, 1, 0)
                        .withKeys(List.of(new FxLight.Key(0, 0), new FxLight.Key(0.2f, 1), new FxLight.Key(1, 0.5f)), true))
                .sounds(new SoundData(List.of(
                        new SoundDef("whoosh", "sounds/whoosh.wav",
                                OmfxCodec.sha256("WAVE".getBytes(StandardCharsets.UTF_8)), null, 0.8f, 0.9f, 1.2f, true),
                        new SoundDef("ignite", null, null, "/sounds/Ignite.ogg", 1f, 1f, 1f, false))))
                .cue(FxSoundCue.onPlay("ignite"))
                .cue(FxSoundCue.onEmitterStart("aimed", "whoosh").withVolume(0.5f))
                .cue(FxSoundCue.onStop("ignite"))
                .build();
        String ext = "{\"tier\":3}";
        return new OmfxDocument("Sonar Arts", system,
                List.of(new OmfxSprite("puff", png2x2()), new OmfxSprite("omt-smoke", omt())),
                Map.of("sounds/whoosh.wav", "WAVE".getBytes(StandardCharsets.UTF_8)),
                FormatExtensions.ofRaw(Map.of("stonebreak:gameplay", ext), Map.of()),
                Map.of("stonebreak/notes.txt", "hello".getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertRoundTrip(OmfxDocument doc) throws IOException {
        byte[] bytes = OmfxCodec.write(doc);
        OmfxDocument back = OmfxCodec.read(bytes);
        assertEquals(doc, back, doc.id());
        assertEquals(doc.system(), back.system(), doc.id());
        assertArrayEquals(bytes, OmfxCodec.write(back), doc.id() + ": write -> read -> write is byte-identical");
    }

    private static void assertFails(String reason, byte[] bytes) {
        IOException e = assertThrows(IOException.class, () -> OmfxCodec.read(bytes), reason);
        assertTrue(e.getMessage().contains(reason), "expected '" + reason + "' in: " + e.getMessage());
    }

    static com.fasterxml.jackson.databind.JsonNode findByName(com.fasterxml.jackson.databind.JsonNode list, String name) {
        for (var n : list) {
            if (name.equals(n.path("name").asText())) {
                return n;
            }
        }
        throw new AssertionError("no '" + name + "' in " + list);
    }

    static Map<String, byte[]> entries(byte[] archive) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                out.put(e.getName(), zip.readAllBytes());
            }
        }
        return out;
    }

    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] without(byte[] archive, String entry) throws IOException {
        Map<String, byte[]> m = entries(archive);
        assertNotNull(m.remove(entry), entry);
        return zip(m);
    }

    private static byte[] with(byte[] archive, String entry, String text) throws IOException {
        Map<String, byte[]> m = entries(archive);
        m.put(entry, text.getBytes(StandardCharsets.UTF_8));
        return zip(m);
    }

    private static byte[] replace(byte[] archive, String entry, String text) throws IOException {
        Map<String, byte[]> m = entries(archive);
        assertTrue(m.containsKey(entry), entry);
        m.put(entry, text.getBytes(StandardCharsets.UTF_8));
        return zip(m);
    }

    private static byte[] manifest(byte[] archive, Consumer<ObjectNode> edit) throws IOException {
        return editJson(archive, "manifest.json", edit);
    }

    private static byte[] system(byte[] archive, Consumer<ObjectNode> edit) throws IOException {
        return editJson(archive, "system.json", edit);
    }

    private static byte[] editJson(byte[] archive, String entry, Consumer<ObjectNode> edit) throws IOException {
        Map<String, byte[]> m = entries(archive);
        ObjectNode n = (ObjectNode) JSON.readTree(m.get(entry));
        edit.accept(n);
        m.put(entry, JSON.writeValueAsBytes(n));
        return zip(m);
    }

    /** A 2x2 PNG: red, green; blue, half-transparent green. */
    static byte[] png2x2() throws IOException {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xFFFF0000);
        img.setRGB(1, 0, 0xFF00FF00);
        img.setRGB(0, 1, 0xFF0000FF);
        img.setRGB(1, 1, 0x8000FF00);
        return encode(img);
    }

    /** A 1x1 opaque PNG of {@code rgba}. */
    static byte[] png(int w, int h, int rgba) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int argb = (rgba >>> 8) | (rgba << 24);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, argb);
            }
        }
        return encode(img);
    }

    private static byte[] encode(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** A 2x1 two-layer OMT: blue base, top layer green on the left texel only. */
    static byte[] omt() throws IOException {
        BufferedImage base = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        base.setRGB(0, 0, 0xFF0000FF);
        base.setRGB(1, 0, 0xFF0000FF);
        BufferedImage top = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        top.setRGB(0, 0, 0xFF00FF00);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.json", """
                { "version": "1.0", "canvasSize": { "width": 2, "height": 1 }, "activeLayerIndex": 0,
                  "layers": [ { "name": "base", "visible": true, "opacity": 1.0, "dataFile": "layer_0.png" },
                              { "name": "top", "visible": true, "opacity": 1.0, "dataFile": "layer_1.png" } ] }
                """.getBytes(StandardCharsets.UTF_8));
        entries.put("layer_0.png", encode(base));
        entries.put("layer_1.png", encode(top));
        return zip(entries);
    }
}
