package com.openmason.engine.audio;

import com.openmason.engine.format.sound.SoundData;
import com.openmason.engine.format.sound.SoundDef;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.openmason.engine.audio.AudioFixtures.constant16;
import static com.openmason.engine.audio.AudioFixtures.pcmLe;
import static com.openmason.engine.audio.AudioFixtures.wav;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link SoundEvents} and {@link SoundEventCache}: event lookup, the per-def pitch variation
 * switch, content-derived keys (moved from Stonebreak's ResolvedSoundEventsTest), and resolving
 * a sound section against a live offline {@link SoundSystem}.
 */
@Tag("regression")
class SoundEventsTest {

    private static SoundEvents.Entry entry(float pitchMin, float pitchMax, boolean variation) {
        return new SoundEvents.Entry("res:/sounds/x.wav", 0.5f, pitchMin, pitchMax, variation);
    }

    @Test
    void pickReturnsNullForUnboundEvents() {
        SoundEvents events = new SoundEvents(Map.of("step", List.of(entry(0.9f, 1.1f, true))));
        assertTrue(events.has("step"));
        assertFalse(events.has("break"));
        assertNotNull(events.pick("step"));
        assertNull(events.pick("break"));
        assertNull(events.pick(null));
        assertNull(SoundEvents.EMPTY.pick("step"));
    }

    @Test
    void pickDrawsFromAllEntriesOfAnEvent() {
        SoundEvents.Entry a = new SoundEvents.Entry("a", 1f, 1f, 1f, true);
        SoundEvents.Entry b = new SoundEvents.Entry("b", 1f, 1f, 1f, true);
        SoundEvents events = new SoundEvents(Map.of("break", List.of(a, b)));
        boolean sawA = false, sawB = false;
        for (int i = 0; i < 200 && !(sawA && sawB); i++) {
            SoundEvents.Entry picked = events.pick("break");
            sawA |= picked == a;
            sawB |= picked == b;
        }
        assertTrue(sawA && sawB);
    }

    @Test
    void pitchHonorsTheVariationSwitch() {
        for (int i = 0; i < 50; i++) assertEquals(1.0f, entry(0.5f, 2.0f, false).pitch(), 1e-6);
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int i = 0; i < 500; i++) {
            float p = entry(0.9f, 1.1f, true).pitch();
            assertTrue(p >= 0.9f && p <= 1.1f, "pitch outside authored range: " + p);
            min = Math.min(min, p);
            max = Math.max(max, p);
        }
        assertTrue(max - min > 0.01f);
        assertEquals(1.05f, entry(1.05f, 1.05f, true).pitch(), 1e-6);
    }

    @Test
    void soundKeysAreContentDerived() {
        SoundDef resource = new SoundDef("step", null, null, "/sounds/GrassWalk.wav", 1f, 0.9f, 1.1f, true);
        assertEquals("res:/sounds/GrassWalk.wav", SoundEvents.keyFor(resource, "a:b"));
        SoundDef embedded = new SoundDef("break", "sounds/break_0.wav", "abc123", null, 1f, 1f, 1f, true);
        assertEquals("snd:abc123", SoundEvents.keyFor(embedded, "a:b"));
        SoundDef stub = new SoundDef("break", "sounds/break_0.wav", "", null, 1f, 1f, 1f, true);
        assertEquals("snd:a:b/sounds/break_0.wav", SoundEvents.keyFor(stub, "a:b"));
    }

    @Test
    void resolveLoadsSharedSamplesOnceAndDropsMissingOnes() {
        assumeTrue(CendaAudio.isAvailable(), () -> "Cenda audio not built: " + CendaAudio.unavailableReason());
        SoundSystem sounds = SoundSystem.offline(48000);
        try {
            byte[] sample = wav(pcmLe(48000, 16, 1), constant16(480, (short) 1000));
            AtomicInteger opens = new AtomicInteger();
            SoundEvents.ResourceOpener resources = path -> {
                opens.incrementAndGet();
                return path.equals("/sounds/step.wav") ? new ByteArrayInputStream(sample) : null;
            };
            SoundData data = new SoundData(List.of(
                    new SoundDef("step", null, null, "/sounds/step.wav", 0.7f, 0.9f, 1.1f, true),
                    new SoundDef("break", "sounds/b.wav", "feed", null, 1f, 1f, 1f, false),
                    new SoundDef("hit", null, null, "/sounds/missing.wav", 1f, 1f, 1f, false)));

            SoundEvents a = SoundEvents.resolve(sounds, "x:a", data, f -> f.equals("sounds/b.wav") ? sample : null, resources);
            assertTrue(a.has("step"));
            assertTrue(a.has("break"));
            assertFalse(a.has("hit"), "a sample that cannot be opened is dropped");
            assertEquals(0.7f, a.pick("step").volume());
            assertTrue(sounds.isSoundLoaded("res:/sounds/step.wav"));
            assertTrue(sounds.isSoundLoaded("snd:feed"));

            int before = opens.get();
            SoundEvents.resolve(sounds, "x:b", data, f -> sample, resources);
            assertEquals(before + 1, opens.get(), "only the still-missing sample is opened again");

            assertTrue(a.playAt(sounds, "step", 1, 2, 3, 1f));
            assertFalse(a.play2D(sounds, "nope", 1f));
            float[] out = new float[512];
            sounds.renderOffline(out, 256);
            assertEquals(1L, sounds.stats().activeVoices());
        } finally {
            sounds.cleanup();
        }
    }

    @Test
    void cacheKeepsResolutionsButRetriesMisses() {
        AtomicInteger calls = new AtomicInteger();
        SoundEvents ready = new SoundEvents(Map.of("step", List.of(entry(1f, 1f, false))));
        SoundEventCache<String> cache = new SoundEventCache<>(key -> {
            calls.incrementAndGet();
            return key.equals("late") && calls.get() < 3 ? null : ready;
        });
        assertSame(ready, cache.get("a"));
        assertSame(ready, cache.get("a"));
        assertEquals(1, calls.get(), "resolved once");
        assertSame(SoundEvents.EMPTY, cache.get("late"), "not ready yet");
        assertSame(ready, cache.get("late"), "tried again and cached");
        assertSame(SoundEvents.EMPTY, cache.get(null));
        cache.invalidate();
        assertEquals(0, cache.size());
    }
}
