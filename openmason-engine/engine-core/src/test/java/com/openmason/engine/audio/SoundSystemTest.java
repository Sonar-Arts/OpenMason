package com.openmason.engine.audio;

import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static com.openmason.engine.audio.AudioFixtures.constant16;
import static com.openmason.engine.audio.AudioFixtures.pcmLe;
import static com.openmason.engine.audio.AudioFixtures.wav;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The façade end to end on an offline engine: names, the 2D/3D volume chain, master volume and
 * ducking applied live (unlike OpenAL's play-time gain), music streaming and switching, loops
 * that pause at zero, ambient priority, and silent no-ops without an engine.
 */
@Tag("integration")
class SoundSystemTest {

    private static final int RATE = 48000;
    private static final float CENTRE = 0.70710678f;
    private static final float EPS = 2e-3f;

    private SoundSystem sound;

    @BeforeEach
    void open() {
        assumeTrue(CendaAudio.isAvailable(), () -> "Cenda audio not built: " + CendaAudio.unavailableReason());
        sound = SoundSystem.offline(RATE);
        assertTrue(sound.isAvailable());
    }

    @AfterEach
    void close() {
        if (sound != null) sound.cleanup();
    }

    /** A WAV holding {@code frames} frames of 0.5 (16-bit 16384) per channel. */
    private static byte[] half(int frames, int channels) {
        short[] perChannel = new short[channels];
        java.util.Arrays.fill(perChannel, (short) 16384);
        return wav(pcmLe(RATE, 16, channels), constant16(frames, perChannel));
    }

    private float[] render(int frames) {
        float[] out = new float[frames * 2];
        sound.renderOffline(out, frames);
        return out;
    }

    @Test
    void twoDimensionalPlayAppliesVolume() {
        sound.loadSound("blip", new ByteArrayInputStream(half(RATE, 1)));
        assertTrue(sound.isSoundLoaded("blip"));
        sound.playSound("blip", 0.8f, 1f);
        float[] out = render(512);
        assertEquals(0.5f * 0.8f * CENTRE, out[2 * 511], EPS);
    }

    @Test
    void positionalPlayFollowsTheListener() {
        sound.loadSound("blip", half(RATE, 1));
        sound.setListenerFromCamera(new Vector3f(100, 0, 0), new Vector3f(0, 0, -1), new Vector3f(0, 1, 0));
        sound.playSoundAt3D("blip", 1f, 1f, 110f, 0f, 0f); // 10 to the right of the listener
        float[] out = render(512);
        assertEquals(0f, out[2 * 511], EPS);
        assertEquals(0.5f * 0.1f, out[2 * 511 + 1], EPS);
    }

    @Test
    void masterVolumeAndDuckingApplyToPlayingSounds() {
        sound.loadSound("hum", half(RATE, 1));
        sound.setLoopVolume("hum", 1f);
        render(512);
        sound.setMasterVolume(0.5f);
        sound.setEnvironmentGain(0.5f);
        float[] out = render(RATE / 10); // past the 50 ms ramp
        assertEquals(0.5f * 0.25f * CENTRE, out[out.length - 2], EPS);
        assertEquals(0.5f, sound.getMasterVolume());
        assertEquals(0.5f, sound.getEnvironmentGain());
    }

    @Test
    void loopsPauseAtZeroAndResume() {
        sound.loadSound("rain", half(1024, 1));
        sound.setLoopVolume("rain", 0.5f);
        float[] out = render(4096);
        assertEquals(0.25f * CENTRE, out[2 * 4000], EPS);
        sound.setLoopVolume("rain", 0f);
        out = render(RATE / 10);
        assertEquals(0f, out[out.length - 2], EPS);
        sound.setLoopVolume("rain", 1f);
        out = render(RATE / 10);
        assertEquals(0.5f * CENTRE, out[out.length - 2], EPS);
        assertEquals(1L, sound.stats().activeVoices());
    }

    @Test
    void musicStreamsSwitchesAndFinishes() {
        sound.loadMusic("a", new ByteArrayInputStream(half(4800, 2)));
        sound.loadMusic("b", new ByteArrayInputStream(half(RATE, 2)));
        assertTrue(sound.isMusicLoaded("a"));
        sound.setMusicVolume(0.5f);
        sound.playMusic("a");
        assertTrue(sound.isMusicPlaying(), "a queued play counts as playing");
        float[] out = render(4096); // past the 50 ms music-volume ramp
        assertEquals(0.25f, out[2 * 4095], EPS);
        render(4800);
        assertFalse(sound.isMusicPlaying(), "a one-shot track ends so a playlist can advance");

        sound.playMusic("a");
        render(256);
        sound.playMusic("b"); // fades a out (0.15 s), starts the 1 s track b
        render(RATE / 4);
        assertEquals(1L, sound.stats().activeVoices());
        sound.stopMusic();
        render(RATE / 4);
        assertFalse(sound.isMusicPlaying());
    }

    @Test
    void ambientOutranksEffects() {
        sound.loadSound("thunder", half(RATE, 1));
        sound.loadSound("step", half(RATE, 1));
        for (int i = 0; i < SoundSystem.MAX_VOICES; i++) sound.playSound("step", 1f, 1f);
        render(256);
        assertTrue(sound.playAmbient("thunder", 0.1f, 1f));
        render(256);
        AudioStats stats = sound.stats();
        assertEquals(SoundSystem.MAX_VOICES, stats.activeVoices());
        assertEquals(1L, stats.voicesStolen());
    }

    @Test
    void voicesCanBeMovedAndStopped() {
        sound.loadSound("engine", half(RATE, 1));
        long v = sound.play("engine", SoundSystem.BUS_SFX, 1f, 1f, true, true, 0, 10f, 0f, 0f);
        render(256);
        sound.setVoicePosition(v, 0f, 0f, -10f);
        float[] out = render(512);
        assertEquals(out[2 * 511], out[2 * 511 + 1], EPS);
        sound.stopVoice(v, 0.01f);
        render(2048);
        assertFalse(sound.isVoiceActive(v));
    }

    @Test
    void underwaterLowPassMufflesEffectsNotMusic() {
        float[] nyquist = new float[RATE];
        for (int i = 0; i < nyquist.length; i++) nyquist[i] = (i % 2 == 0) ? 0.5f : -0.5f;
        sound.loadSound("hiss", AudioFixtures.wav(pcmLe(RATE, 16, 1), toPcm16(nyquist)));
        sound.setEnvironmentLowPass(400f);
        sound.setLoopVolume("hiss", 1f);
        float[] out = render(RATE / 10);
        assertTrue(Math.abs(out[out.length - 2]) < 0.05f, "muffled: " + out[out.length - 2]);
        assertEquals(400f, sound.getEnvironmentLowPass());
    }

    @Test
    void unknownNamesAndBadVolumesAreHarmless() {
        sound.playSound("nope", 1f, 1f);
        sound.setLoopVolume("nope", 1f);
        assertFalse(sound.playAmbient("nope", 1f, 1f));
        sound.setMusicVolume(Float.NaN);
        assertEquals(1f, sound.getMusicVolume());
        render(256);
        assertNotNull(sound.stats().summary());
    }

    @Test
    void withoutAnEngineEverythingIsSilent() {
        SoundSystem off = new SoundSystem(CendaAudio.DEVICE_OFFLINE, RATE); // never initialized
        off.loadSound("x", new ByteArrayInputStream(half(16, 1)));
        off.playSound("x");
        off.setLoopVolume("x", 1f);
        off.playMusic("x");
        assertFalse(off.isSoundLoaded("x"));
        assertFalse(off.isMusicPlaying());
        assertFalse(off.isAvailable());
        assertEquals(null, off.stats());
    }

    private static byte[] toPcm16(float[] samples) {
        short[] s = new short[samples.length];
        for (int i = 0; i < s.length; i++) s[i] = (short) Math.round(samples[i] * 32767f);
        return AudioFixtures.pcm16(s);
    }
}
