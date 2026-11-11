package com.openmason.engine.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.openmason.engine.audio.AudioFixtures.constant16;
import static com.openmason.engine.audio.AudioFixtures.pcmLe;
import static com.openmason.engine.audio.AudioFixtures.wav;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The ca_* ABI through FFM on an offline engine: command layout (every field the native side
 * reads lands where audio.h says), decoding, voice state, stats and the spatial model. The
 * native mixing rules themselves are pinned by cenda/tests/audio_engine_test.cpp.
 */
@Tag("regression")
class CendaAudioTest {

    private static final int RATE = 48000;
    private static final float CENTRE = 0.70710678f;
    private static final float EPS = 1e-4f;

    private CendaAudio.Engine engine;
    private final CendaAudio.Commands batch = new CendaAudio.Commands(2);

    @BeforeEach
    void open() {
        assumeTrue(CendaAudio.isAvailable(), () -> "Cenda audio not built: " + CendaAudio.unavailableReason());
        engine = CendaAudio.Engine.create(CendaAudio.DEVICE_OFFLINE, RATE, 8);
    }

    @AfterEach
    void close() {
        if (engine != null) engine.close();
    }

    private int constant(float value, int frames) {
        float[] pcm = new float[frames];
        java.util.Arrays.fill(pcm, value);
        return engine.loadPcm(pcm, 1, RATE);
    }

    private float[] render(int frames) {
        float[] out = new float[frames * 2];
        engine.render(out, frames);
        return out;
    }

    private long submitPlay(int sound, int flags, float gain, float x, float y, float z) {
        batch.clear();
        int i = batch.play(sound, 1, flags, 0, gain, 1f, x, y, z, 0, 0, 0, 0, 0);
        engine.submit(batch);
        return batch.voice(i);
    }

    @Test
    void reportsTheOfflineEngine() {
        assertEquals(RATE, engine.sampleRate());
        assertEquals("offline", engine.deviceName());
    }

    @Test
    void twoDimensionalMonoPlaysCentred() {
        long v = submitPlay(constant(1f, RATE), 0, 1f, 0, 0, 0);
        assertEquals(CendaAudio.VOICE_PENDING, engine.voiceState(v));
        float[] out = render(256);
        assertEquals(CENTRE, out[0], EPS);
        assertEquals(CENTRE, out[1], EPS);
        assertEquals(CendaAudio.VOICE_PLAYING, engine.voiceState(v));
    }

    @Test
    void spatialPlayReadsPositionAndDistanceFields() {
        // Right of a listener facing -Z, 10 blocks: inverse distance 1/(1+9), hard right.
        submitPlay(constant(1f, RATE), CendaAudio.PLAY_SPATIAL, 1f, 10f, 0f, 0f);
        float[] out = render(256);
        assertEquals(0f, out[0], EPS);
        assertEquals(0.1f, out[1], EPS);

        // Custom distances: ref 2, max 4, rolloff 1 at 10 blocks ahead => 2 / (2 + 2) = 0.5.
        batch.clear();
        batch.stopBus(1, 0f);
        batch.play(constant(1f, RATE), 1, CendaAudio.PLAY_SPATIAL, 0, 1f, 1f, 0, 0, -10f, 2f, 4f, 1f, 0, 0);
        engine.submit(batch);
        out = render(256);
        assertEquals(0.5f * CENTRE, out[0], EPS);
        assertEquals(0.5f * CENTRE, out[1], EPS);
    }

    @Test
    void listenerAndBusCommandsApply() {
        batch.clear();
        batch.listener(0, 0, 0, 1, 0, 0, 0, 1, 0); // face +X
        batch.bus(1, 0.5f, 0f, 0f);
        engine.submit(batch);
        submitPlay(constant(1f, RATE), CendaAudio.PLAY_SPATIAL, 1f, 10f, 0f, 0f);
        float[] out = render(256);
        assertEquals(0.5f * 0.1f * CENTRE, out[0], EPS);
        assertEquals(out[0], out[1], EPS);
    }

    @Test
    void voiceCommandsAddressTheRightVoice() {
        int s = constant(0.5f, RATE);
        long keep = submitPlay(s, CendaAudio.PLAY_LOOP, 1f, 0, 0, 0);
        long stop = submitPlay(s, 0, 1f, 0, 0, 0);
        render(256);
        batch.clear();
        batch.stop(stop, 0f);
        batch.voiceGain(keep, 0f, 0f);
        engine.submit(batch);
        render(512); // one block ramps from the old gain to zero (no click), the next one pauses
        assertEquals(CendaAudio.VOICE_DONE, engine.voiceState(stop));
        assertEquals(CendaAudio.VOICE_PAUSED, engine.voiceState(keep));
    }

    @Test
    void decodesWavAndStreams() {
        byte[] file = wav(pcmLe(RATE, 16, 2), constant16(4800, (short) 16384, (short) 16384));
        int resident = engine.load(file, false);
        assertEquals(new CendaAudio.SoundInfo(2, RATE, 4800), engine.info(resident));
        int streamed = engine.load(file, true);
        assertEquals(2, engine.info(streamed).channels());

        batch.clear();
        int i = batch.play(streamed, 2, CendaAudio.PLAY_LOOP, 0, 1f, 1f);
        engine.submit(batch);
        long music = batch.voice(i);
        float[] out = render(9600); // twice the file: the loop must not gap
        for (int f = 0; f < 9600; f++) assertEquals(0.5f, out[2 * f], 1e-3f, "frame " + f);
        assertEquals(CendaAudio.VOICE_PLAYING, engine.voiceState(music));
        assertTrue(engine.stats()[CendaAudio.STAT_STREAMS] >= 1);
    }

    @Test
    void decodesOggVorbisResidentAndStreamed() throws java.io.IOException {
        byte[] ogg;
        try (var in = CendaAudioTest.class.getResourceAsStream("/audio/tone.ogg")) {
            assertNotNull(in, "test resource audio/tone.ogg");
            ogg = in.readAllBytes();
        }
        int resident = engine.load(ogg, false);
        CendaAudio.SoundInfo info = engine.info(resident);
        assertEquals(2, info.channels());
        assertEquals(44100, info.sampleRate());
        assertEquals(22050, info.frames(), 64, "0.5 s at 44.1 kHz");

        // Streamed and resident playback of the same file (resampled 44.1 -> 48 kHz) carry the
        // same tone: ffmpeg's sine is 1/8 full scale, -3 dB per channel after its stereo upmix.
        double residentEnergy = toneEnergy(resident);
        double streamedEnergy = toneEnergy(engine.load(ogg, true));
        assertEquals(0.125 * 0.125 / 2 / 2, residentEnergy, 4e-4, "resident tone");
        assertEquals(residentEnergy, streamedEnergy, 1e-6, "streamed equals resident");
        assertEquals(0L, engine.stats()[CendaAudio.STAT_STREAM_UNDERRUNS]);
    }

    /** Mean square of the left channel over 0.05..0.2 s of a 2D play of {@code sound}. */
    private double toneEnergy(int sound) {
        batch.clear();
        batch.stopBus(2, 0f);
        batch.play(sound, 2, 0, 0, 1f, 1f);
        engine.submit(batch);
        float[] out = render(9600);
        double energy = 0;
        for (int f = 2400; f < 9600; f++) energy += out[2 * f] * out[2 * f];
        return energy / 7200;
    }

    @Test
    void refusesJunkWithAReason() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> engine.load(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, false));
        assertTrue(e.getMessage().contains("decod"), e.getMessage());
    }

    @Test
    void freeReleasesOnUpdate() {
        int s = constant(0.5f, 1024);
        long v = submitPlay(s, CendaAudio.PLAY_LOOP, 1f, 0, 0, 0);
        render(256);
        long loaded = engine.stats()[CendaAudio.STAT_SOUNDS];
        assertTrue(engine.free(s));
        assertNull(engine.info(s));
        render(256);
        assertEquals(CendaAudio.VOICE_DONE, engine.voiceState(v));
        engine.update();
        assertEquals(loaded - 1, engine.stats()[CendaAudio.STAT_SOUNDS]);
    }

    @Test
    void batchesGrowAndKeepIds() {
        int s = constant(0.1f, RATE);
        CendaAudio.Commands big = new CendaAudio.Commands(1);
        for (int n = 0; n < 5; n++) big.play(s, 1, 0, 0, 0.1f, 1f);
        engine.submit(big);
        assertEquals(5, big.count());
        for (int n = 1; n < 5; n++) assertEquals(big.voice(n - 1) + 1, big.voice(n));
        render(256);
        assertEquals(5, engine.stats()[CendaAudio.STAT_VOICES_ACTIVE]);
    }

    @Test
    void callsAfterCloseAreHarmless() {
        int s = constant(0.5f, 1024);
        engine.close();
        batch.clear();
        batch.play(s, 1, 0, 0, 1f, 1f);
        engine.submit(batch); // dropped, no native call on a freed engine
        assertEquals(CendaAudio.VOICE_DONE, engine.voiceState(1));
        assertNull(engine.info(s));
        engine.update();
        assertEquals(0L, engine.stats()[CendaAudio.STAT_SOUNDS]);
        assertThrows(IllegalStateException.class, () -> engine.load(new byte[]{1}, false));
        engine.close(); // idempotent
    }

    @Test
    void renderNeedsAnOfflineEngine() {
        try (CendaAudio.Engine device = CendaAudio.Engine.create(CendaAudio.DEVICE_NULL, 0, 4)) {
            assertNotNull(device.deviceName());
            assertThrows(IllegalStateException.class, () -> device.render(new float[2], 1));
        }
    }
}
