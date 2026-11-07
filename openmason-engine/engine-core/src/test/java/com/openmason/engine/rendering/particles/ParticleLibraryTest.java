package com.openmason.engine.rendering.particles;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** The effect library: presets, lookup, and the JSON format. */
class ParticleLibraryTest {

    private static ParticleLibrary read(String json) throws IOException {
        ParticleLibrary lib = new ParticleLibrary();
        lib.load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        return lib;
    }

    @Test
    void builtInsAreRegisteredByNameAndSimulate() {
        ParticleLibrary lib = ParticleLibrary.withBuiltIns();
        assertEquals(ParticleLibrary.Presets.ALL.size(), lib.size());
        for (String name : new String[]{"fire", "smoke", "embers", "sparks", "debris", "dust", "splash", "snow",
                "magic", "explosion"}) {
            assertTrue(lib.contains(name), name);
        }
        assertSame(ParticleLibrary.Presets.FIRE, lib.get("fire"));
        assertTrue(lib.find("nope").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> lib.get("nope"));

        // Every preset spawns, simulates and builds a batch without error.
        ParticleSystem s = new ParticleSystem(20_000);
        s.setCollider((x, y, z) -> y < -5);
        for (ParticleEffect e : lib.effects()) {
            s.burst(e, 0, 0, -6, 200);
            s.createEmitter(e, 0, 0, -6);
        }
        for (int i = 0; i < 120; i++) {
            s.update(1f / 60f);
        }
        assertTrue(s.liveCount() > 0);
        assertTrue(new ParticleBatchBuilder().prepare(s, ParticleBatchBuilderTest.frame()) > 0);
    }

    @Test
    void presetsThatFallReachTheGroundBeforeTheyDie() {
        // Found by the golden images: snow died ~3 m up and most sparks mid-air — lifetimes shorter than the fall.
        for (ParticleEffect e : new ParticleEffect[]{ParticleLibrary.Presets.SNOW, ParticleLibrary.Presets.DEBRIS,
                ParticleLibrary.Presets.SPARKS, ParticleLibrary.Presets.SPLASH}) {
            ParticleSystem s = new ParticleSystem(5000, 11);
            s.setCollider((x, y, z) -> y < 0);
            double height = e == ParticleLibrary.Presets.SNOW ? 6 : 0.5;
            s.burst(e, 0, height, 0, 200);
            int landed = 0;
            for (int i = 0; i < 20 * 60 && s.liveCount() > 0; i++) {
                s.update(1f / 60f);
                for (int p = 0; p < s.liveCount(); p++) {
                    if (s.worldY(p) < 0.05) {
                        landed++;
                    }
                }
                if (landed > 0) break;
            }
            assertTrue(landed > 0, e.name() + " never lands before dying");
        }
    }

    @Test
    void writeThenReadReproducesEveryEffectExactly() throws IOException {
        ParticleLibrary lib = ParticleLibrary.withBuiltIns();
        lib.register(ParticleEffect.builder("custom").lifetime(0.3f, 0.7f).box(1, 2, 3).atlas(5, 7, true)
                .colors(0x12345678, 0x9ABCDEF0).collides(0.25f, 0.125f).collides(false).wind(0.33f).build());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        lib.write(bytes);

        ParticleLibrary back = new ParticleLibrary();
        assertEquals(lib.size(), back.load(new ByteArrayInputStream(bytes.toByteArray())));
        for (ParticleEffect e : lib.effects()) {
            assertEquals(e, back.get(e.name()), e.name());
        }
    }

    @Test
    void minimalDocumentsUseBuilderDefaultsAndScalarRanges() throws IOException {
        ParticleLibrary lib = read("""
                { "effects": [ { "name": "puff", "lifetime": 2, "colorStart": "#FF8000",
                                 "shape": { "type": "sphere", "radius": 0.5 }, "frames": { "count": 4 } } ] }
                """);
        ParticleEffect e = lib.get("puff");
        ParticleEffect d = ParticleEffect.builder("puff").build();
        assertEquals(ParticleEffect.Range.of(2), e.lifetime());
        assertEquals(0xFF8000FF, e.colorStart(), "#RRGGBB is opaque");
        assertEquals(0xFF8000FF, e.colorEnd(), "end colour defaults to the start colour");
        assertEquals(ParticleEffect.EmitterShape.SPHERE, e.shape());
        assertEquals(0.5f, e.radius());
        assertEquals(ParticleEffect.Sprite.ATLAS, e.sprite(), "frames without a sprite means atlas");
        assertEquals(4, e.frameCount());
        assertEquals(d.rate(), e.rate());
        assertEquals(d.speed(), e.speed());
        assertFalse(e.collides());
    }

    @Test
    void collisionBlockTurnsCollisionOn() throws IOException {
        ParticleEffect e = read("""
                { "effects": [ { "name": "rock", "collision": { "bounce": 0.5 } } ] }
                """).get("rock");
        assertTrue(e.collides());
        assertEquals(0.5f, e.bounce());
    }

    @Test
    void malformedDocumentsFailWithAReason() {
        IOException wrongFormat = assertThrows(IOException.class,
                () -> read("{ \"format\": \"something.else\", \"effects\": [] }"));
        assertTrue(wrongFormat.getMessage().contains("something.else"));
        assertThrows(IOException.class, () -> read("{ \"version\": 99, \"effects\": [] }"));
        assertThrows(IOException.class, () -> read("{ }"));
        assertThrows(IOException.class, () -> read("[]"));
        IOException bad = assertThrows(IOException.class,
                () -> read("{ \"effects\": [ { \"name\": \"x\", \"additive\": 3 } ] }"));
        assertTrue(bad.getMessage().contains("additive"), bad.getMessage());
        assertThrows(IOException.class, () -> read("{ \"effects\": [ { \"name\": \"x\", \"sprite\": \"star\" } ] }"));
        assertThrows(IOException.class, () -> read("{ \"effects\": [ { \"name\": \"x\", \"colorStart\": \"#12\" } ] }"));
        assertThrows(IOException.class, () -> read("{ \"effects\": [ { \"lifetime\": 1 } ] }"));
    }

    @Test
    void colourHelpersRoundTrip() {
        assertEquals(0x11223344, ParticleLibrary.parseColor("#11223344"));
        assertEquals(0xAABBCCFF, ParticleLibrary.parseColor("aabbcc"));
        assertEquals("#DEADBEEF", ParticleLibrary.formatColor(0xDEADBEEF));
    }
    @Test
    void subEmittersResolveByNameInAnyOrderAndFromTheLibrary() throws IOException {
        ParticleLibrary lib = ParticleLibrary.withBuiltIns();
        lib.load(new ByteArrayInputStream("""
                { "effects": [
                    { "name": "rocket", "subEmitters": [
                        { "effect": "pop", "trigger": "death", "count": 40, "inherit": 0.25 },
                        { "effect": "smoke", "trigger": "trail", "rate": 12 } ] },
                    { "name": "pop", "orientation": "velocity", "stretch": 0.05, "priority": "critical",
                      "maxLive": 300, "lodDistance": 80, "softness": 0.4,
                      "colorKeys": [[0, "#FFFFFF"], [0.3, "#FF8000FF"], [1, "#FF000000"]],
                      "sizeKeys": [[0, 1], [1, 0]] } ] }
                """.getBytes(StandardCharsets.UTF_8)));
        ParticleEffect rocket = lib.get("rocket");
        ParticleEffect pop = lib.get("pop");
        assertSame(pop, rocket.subEmitters().get(0).effect(), "forward reference in the same document");
        assertSame(ParticleLibrary.Presets.SMOKE, rocket.subEmitters().get(1).effect(), "reference to a library effect");
        assertEquals(ParticleEffect.Trigger.TRAIL, rocket.subEmitters().get(1).trigger());
        assertEquals(12f, rocket.subEmitters().get(1).rate());
        assertEquals(0.25f, rocket.subEmitters().get(0).inheritVelocity());
        assertEquals(ParticleEffect.Orientation.VELOCITY, pop.orientation());
        assertEquals(ParticleEffect.Priority.CRITICAL, pop.priority());
        assertEquals(300, pop.maxLive());
        assertEquals(3, pop.colorKeys().size());
        assertEquals(0xFFFFFFFF, pop.colorKeys().get(0).rgba());
        assertEquals(2, pop.sizeKeys().size());
    }

    @Test
    void subEmitterCyclesAndMissingTargetsAreRejected() {
        IOException cycle = assertThrows(IOException.class, () -> read("""
                { "effects": [ { "name": "a", "subEmitters": [ { "effect": "b" } ] },
                               { "name": "b", "subEmitters": [ { "effect": "a" } ] } ] }
                """));
        assertTrue(cycle.getMessage().contains("cycle"), cycle.getMessage());
        IOException missing = assertThrows(IOException.class, () -> read("""
                { "effects": [ { "name": "a", "subEmitters": [ { "effect": "nowhere" } ] } ] }
                """));
        assertTrue(missing.getMessage().contains("nowhere"), missing.getMessage());
        assertThrows(IOException.class, () -> read("""
                { "effects": [ { "name": "a" }, { "name": "a" } ] }
                """), "duplicate names");
        assertThrows(IOException.class, () -> read("""
                { "effects": [ { "name": "a", "colorKeys": [[0.5, "#FFFFFF"], [0.2, "#000000"]] } ] }
                """), "keys out of order");
    }

    @Test
    void registeringAnEffectAlsoRegistersWhatItSpawns() {
        ParticleLibrary lib = new ParticleLibrary();
        lib.register(ParticleLibrary.Presets.FIREWORK);
        assertTrue(lib.contains("firework-burst") && lib.contains("firework-trail"));
    }
}
