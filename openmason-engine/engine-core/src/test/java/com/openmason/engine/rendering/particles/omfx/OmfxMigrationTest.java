package com.openmason.engine.rendering.particles.omfx;

import com.openmason.engine.rendering.particles.FxEmitter;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleImage;
import com.openmason.engine.rendering.particles.ParticleLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OmfxMigrationTest {

    @Test
    void everyV1EffectBecomesASingleEmitterSystemThatPlaysTheSame() throws IOException {
        ParticleLibrary lib = ParticleLibrary.withBuiltIns();
        OmfxMigration.Result result = OmfxMigration.fromV1(v1(lib), OmfxMigration.Options.defaults());

        assertEquals(lib.size(), result.documents().size());
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
        for (ParticleEffect e : lib.effects()) {
            OmfxDocument d = result.document(e.name());
            assertNotNull(d, e.name());
            assertEquals(FxSystem.of(e, OmfxMigration.defaultBurst(e)), d.system(), e.name());
            FxEmitter only = d.system().emitters().get(0);
            assertEquals(1, d.system().emitters().size());
            if (e.rate() > 0f) {
                assertTrue(only.endless(), e.name() + ": a rate effect emits until stopped");
            } else {
                assertEquals(OmfxMigration.defaultBurst(e), only.burst(), e.name());
                assertEquals(0f, only.duration(), e.name() + ": a burst effect fires once");
            }
        }
        assertEquals(1, result.document("firework").system().emitters().get(0).burst(),
                "a rocket with sub-emitters fires one, as the editor preview did");
        assertEquals(60, result.document("sparks").system().emitters().get(0).burst());
    }

    @Test
    void migratedDocumentsRoundTripThroughTheV2Codec() throws IOException {
        OmfxMigration.Result result = OmfxMigration.fromV1(v1(ParticleLibrary.withBuiltIns()),
                OmfxMigration.Options.defaults().withAuthor("migrator"));
        for (OmfxDocument d : result.documents()) {
            assertEquals("migrator", d.author());
            byte[] bytes = OmfxCodec.write(d);
            assertEquals(d, OmfxCodec.read(bytes), d.id());
            assertEquals(d, OmfxMigration.load(bytes, OmfxMigration.Options.defaults()).documents().get(0));
        }
    }

    @Test
    void subEmitterTargetsTravelWithTheSystemThatUsesThem() throws IOException {
        OmfxMigration.Result result = OmfxMigration.fromV1(v1(ParticleLibrary.withBuiltIns()),
                OmfxMigration.Options.defaults());
        OmfxDocument rain = result.document("rain");
        assertEquals(List.of("rain-splash", "ripple", "rain"), rain.effects().stream().map(ParticleEffect::name).toList());
        assertEquals(rain, OmfxCodec.read(OmfxCodec.write(rain)));
    }

    @Test
    void textureImagesAreEmbeddedWhenAvailableAndOtherwiseLeftToTheHost(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("sprites"));
        Files.write(dir.resolve("sprites/smoke_0.png"), png(0xFF0000FF));
        Files.write(dir.resolve("sprites/smoke_1.png"), png(0x00FF00FF));
        Files.write(dir.resolve("broken.png"), "not an image".getBytes(StandardCharsets.UTF_8));
        String json = """
                { "format": "openmason.particles", "version": 1, "effects": [
                  { "name": "smoke", "sprite": "texture", "textures": ["sprites/smoke_0.png", "sprites/smoke_1.png"],
                    "animate": true },
                  { "name": "glow", "sprite": "texture", "textures": ["sprites/smoke_0.png", "missing.png"] },
                  { "name": "bad", "sprite": "texture", "textures": ["broken.png"] } ] }
                """;
        OmfxMigration.Result result = OmfxMigration.fromV1(json.getBytes(StandardCharsets.UTF_8),
                OmfxMigration.Options.defaults().withSprites(OmfxMigration.files(dir)));

        OmfxDocument smoke = result.document("smoke");
        assertEquals(List.of("sprites/smoke_0.png", "sprites/smoke_1.png"),
                smoke.sprites().stream().map(OmfxSprite::name).toList(), "names unchanged: the effect is the same");
        assertEquals(0x00FF00FF, smoke.decodeSprite("sprites/smoke_1.png").texel(0, 0));
        assertTrue(smoke.externalTextures().isEmpty());

        OmfxDocument glow = result.document("glow");
        assertEquals(List.of("missing.png"), List.copyOf(glow.externalTextures()));
        assertNull(result.document("bad").sprite("broken.png"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("'missing.png' was not found")),
                result.warnings().toString());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("'broken.png' could not be embedded")),
                result.warnings().toString());

        // the embedded document no longer needs the folder
        ParticleImage fromArchive = OmfxCodec.read(OmfxCodec.write(smoke)).spriteLoader(null)
                .load("sprites/smoke_0.png");
        assertEquals(0xFF0000FF, fromArchive.texel(0, 0));
    }

    @Test
    void legacyAtlasFramesBecomeEmbeddedSpritesWhenTheAtlasIsGiven() throws IOException {
        // a 4x2 grid of 2x2 cells; cell f is filled with colour (f * 30) red
        BufferedImage atlas = new BufferedImage(8, 4, BufferedImage.TYPE_INT_ARGB);
        for (int f = 0; f < 8; f++) {
            int argb = 0xFF000000 | ((f * 30) << 16);
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    atlas.setRGB((f % 4) * 2 + x, (f / 4) * 2 + y, argb);
                }
            }
        }
        OmfxMigration.Atlas grid = new OmfxMigration.Atlas(encode(atlas), 4, 2);
        OmfxMigration.Result result = OmfxMigration.fromV1(legacyAtlasLibrary(),
                OmfxMigration.Options.defaults().withAtlas(grid));

        ParticleEffect flame = result.document("flame").system().emitters().get(0).effect();
        assertEquals(ParticleEffect.Sprite.TEXTURE, flame.sprite());
        assertEquals(List.of("atlas-3", "atlas-4", "atlas-5"), flame.textures());
        assertTrue(flame.animateFrames());
        OmfxDocument doc = result.document("flame");
        for (int f = 3; f <= 5; f++) {
            ParticleImage cell = doc.decodeSprite("atlas-" + f);
            assertEquals(2, cell.width());
            assertEquals(((f * 30) << 24) | 0xFF, cell.texel(1, 1), "frame " + f + " is its own cell");
        }
        assertEquals(ParticleEffect.Sprite.SOFT_DISC,
                result.document("puff").system().emitters().get(0).effect().sprite(),
                "a legacy frames block on a procedural sprite is ignored");
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
        assertEquals(doc, OmfxCodec.read(OmfxCodec.write(doc)));
    }

    @Test
    void legacyAtlasFramesWithoutTheAtlasFallBackToTheSoftDisc() throws IOException {
        OmfxMigration.Result result = OmfxMigration.fromV1(legacyAtlasLibrary(), OmfxMigration.Options.defaults());
        ParticleEffect flame = result.document("flame").system().emitters().get(0).effect();
        assertEquals(ParticleEffect.Sprite.SOFT_DISC, flame.sprite());
        assertTrue(flame.textures().isEmpty());
        assertEquals(1, result.warnings().size(), result.warnings().toString());
        assertTrue(result.warnings().get(0).contains("atlas frames 3..5 dropped"), result.warnings().get(0));
    }

    @Test
    void loadReadsEitherVersion() throws IOException {
        OmfxDocument v2 = OmfxCodecTest.kitchenSink();
        assertEquals(List.of(v2), OmfxMigration.load(OmfxCodec.write(v2), OmfxMigration.Options.defaults()).documents());
        assertEquals(17, OmfxMigration.load(v1(ParticleLibrary.withBuiltIns()), OmfxMigration.Options.defaults())
                .documents().size());
    }

    @Test
    void malformedV1DocumentsFailWithAReason() {
        assertFails("not a particle library", "{ nope");
        assertFails("must be a JSON object", "[1, 2]");
        assertFails("not a particle library: format 'something.else'",
                "{ \"format\": \"something.else\", \"effects\": [] }");
        assertFails("sub-emitter effect 'ghost' is not defined",
                "{ \"effects\": [ { \"name\": \"a\", \"subEmitters\": [ { \"effect\": \"ghost\" } ] } ] }");
        assertFails("atlas frame 9 is outside the 2x2 grid",
                "{ \"effects\": [ { \"name\": \"a\", \"sprite\": \"atlas\", \"frames\": { \"start\": 9 } } ] }",
                OmfxMigration.Options.defaults().withAtlas(new OmfxMigration.Atlas(pngQuiet(), 2, 2)));
    }

    // ------------------------------------------------------------------------------------------------

    private static byte[] legacyAtlasLibrary() {
        return """
                { "format": "openmason.particles", "version": 1, "effects": [
                  { "name": "flame", "rate": 30, "sprite": "atlas", "frames": { "start": 3, "count": 3, "animate": true } },
                  { "name": "puff", "rate": 5, "sprite": "soft_disc", "frames": { "start": 0, "count": 1 } } ] }
                """.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertFails(String reason, String json) {
        assertFails(reason, json, OmfxMigration.Options.defaults());
    }

    private static void assertFails(String reason, String json, OmfxMigration.Options options) {
        IOException e = assertThrows(IOException.class,
                () -> OmfxMigration.fromV1(json.getBytes(StandardCharsets.UTF_8), options), reason);
        assertTrue(e.getMessage().contains(reason), "expected '" + reason + "' in: " + e.getMessage());
    }

    private static byte[] v1(ParticleLibrary lib) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        lib.write(out);
        return out.toByteArray();
    }

    private static byte[] png(int rgba) throws IOException {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, (rgba >>> 8) | (rgba << 24));
        return encode(img);
    }

    private static byte[] pngQuiet() {
        try {
            return encode(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] encode(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
