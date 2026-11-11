package com.openmason.engine.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Game-agnostic sound system over the native Cenda audio engine ({@link CendaAudio}): named
 * sounds, 2D and positional 3D playback, looping ambience, long ambient one-shots, streamed
 * music, the listener, and volume buses.
 *
 * <p>Every method is safe from any thread: plays are queued to the native mixer, which applies
 * them at its next block. Loading decodes on the calling thread.
 *
 * <p>Buses: {@link #BUS_SFX} (one-shot effects), {@link #BUS_MUSIC}, {@link #BUS_AMBIENT}
 * (loops and long ambience) and {@link #BUS_UI}, all under {@link #BUS_MASTER}. Master volume
 * and environmental ducking apply live to everything already playing.
 *
 * <p>Without the native library (or with no audio device) every call is a silent no-op; the
 * reason is logged once and reported by {@link #unavailableReason()}.
 */
public class SoundSystem {

    private static final Logger logger = LoggerFactory.getLogger(SoundSystem.class);

    public static final int BUS_MASTER = 0;
    public static final int BUS_SFX = 1;
    public static final int BUS_MUSIC = 2;
    public static final int BUS_AMBIENT = 3;
    public static final int BUS_UI = 4;

    /** Voices shared by every sound (the OpenAL setup it replaced had 4 sources per sound name). */
    static final int MAX_VOICES = 128;
    /** Ambient one-shots (thunder) outrank effects in voice stealing. */
    static final int AMBIENT_PRIORITY = 1;
    /** Ramp for live volume changes: long enough not to click, short enough to feel instant. */
    private static final float VOLUME_RAMP_SECONDS = 0.05f;
    /** Music switches fade the old track this fast instead of cutting it. */
    private static final float MUSIC_SWITCH_FADE_SECONDS = 0.15f;
    /** Default 2D volume of {@link #playSound(String)}. */
    private static final float DEFAULT_VOLUME = 0.5f;

    private static SoundSystem instance;

    private final int deviceMode;
    private final int requestedRate;
    private volatile CendaAudio.Engine engine;
    private volatile String unavailableReason = "not initialized";

    private final Map<String, Integer> sounds = new ConcurrentHashMap<>();
    private final Map<String, Integer> music = new ConcurrentHashMap<>();
    private final Map<String, Long> loopVoices = new ConcurrentHashMap<>();
    private final Map<String, Float> loopGains = new ConcurrentHashMap<>();
    private final Set<String> warnedMissing = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<CendaAudio.Commands> batches =
            ThreadLocal.withInitial(() -> new CendaAudio.Commands(4));

    private final VolumeController volumeController = new VolumeController();
    private volatile float musicVolume = 1.0f;
    private volatile float environmentLowPassHz = 0.0f;
    private volatile long musicVoice;

    private final float[] listener = {0, 0, 0, 0, 0, -1, 0, 1, 0};
    /** Last gain / low-pass sent per bus, so per-frame volume calls only cross FFM on a change. */
    private final float[][] sentBus = new float[CendaAudio.BUS_COUNT][];

    private SoundSystem() {
        this(CendaAudio.DEVICE_DEFAULT, 0);
    }

    /** Test seam: a non-singleton system over a null or offline engine. */
    SoundSystem(int deviceMode, int sampleRate) {
        this.deviceMode = deviceMode;
        this.requestedRate = sampleRate;
    }

    public static synchronized SoundSystem getInstance() {
        if (instance == null) {
            instance = new SoundSystem();
        }
        return instance;
    }

    /**
     * An initialized system with no device that mixes only when {@link #renderOffline} is called:
     * deterministic output for tests and tools.
     */
    public static SoundSystem offline(int sampleRate) {
        SoundSystem s = new SoundSystem(CendaAudio.DEVICE_OFFLINE, sampleRate);
        s.initialize();
        return s;
    }

    /** Opens the audio device. Safe to call again after {@link #cleanup()}. */
    public synchronized void initialize() {
        if (engine != null) return;
        if (!CendaAudio.isAvailable()) {
            unavailableReason = CendaAudio.unavailableReason();
            return;
        }
        try {
            engine = CendaAudio.Engine.create(deviceMode, requestedRate, MAX_VOICES);
            unavailableReason = null;
            logger.info("Audio on '{}' at {} Hz", engine.deviceName(), engine.sampleRate());
            applyMasterBus(0f);
            applyMusicBus(0f);
            applyEnvironmentFilter(0f);
            submitListener();
        } catch (IllegalStateException e) {
            unavailableReason = e.getMessage();
            logger.error("Audio disabled: {}", e.getMessage());
        }
    }

    /** True once the native engine is running. */
    public boolean isAvailable() {
        return engine != null;
    }

    /** Why audio is silent, or null when it is running. */
    public String unavailableReason() {
        return unavailableReason;
    }

    /** Once per frame from the main loop: releases memory of freed sounds. */
    public void update() {
        CendaAudio.Engine e = engine;
        if (e != null) e.update();
    }

    // ---- loading ----------------------------------------------------------------------------

    /** Loads a sound from the engine's own class path (prefer the stream/bytes overloads across modules). */
    public void loadSound(String name, String resourcePath) {
        String path = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        InputStream in = SoundSystem.class.getClassLoader().getResourceAsStream(path);
        if (in == null) in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
        if (in == null) {
            logger.error("Sound resource not found: {}", resourcePath);
            return;
        }
        loadSound(name, in);
    }

    /**
     * Loads a sound from a caller-supplied stream (the caller resolves resources in its own module).
     * The stream is always closed. Re-loading a name replaces it.
     */
    public void loadSound(String name, InputStream stream) {
        byte[] bytes = readAll("sound", name, stream);
        if (bytes != null) loadSound(name, bytes);
    }

    /** Loads (decodes) a sound from encoded bytes: WAV, FLAC, MP3 or Ogg Vorbis. */
    public void loadSound(String name, byte[] bytes) {
        register(sounds, name, bytes, false);
    }

    /**
     * Loads a background-music track. Music is streamed (decoded while it plays), so a long track
     * costs only its encoded size. The stream is always closed.
     */
    public void loadMusic(String name, InputStream stream) {
        byte[] bytes = readAll("music", name, stream);
        if (bytes != null) register(music, name, bytes, true);
    }

    /** Releases a loaded sound (its voices stop). */
    public void unloadSound(String name) {
        Integer id = sounds.remove(name);
        CendaAudio.Engine e = engine;
        if (id != null && e != null) e.free(id);
    }

    private byte[] readAll(String kind, String name, InputStream stream) {
        if (stream == null) {
            logger.error("InputStream is NULL for {}: {}", kind, name);
            return null;
        }
        try (InputStream in = stream) {
            if (engine == null) {
                logger.debug("Audio unavailable; skipping {} '{}'", kind, name);
                return null;
            }
            return in.readAllBytes();
        } catch (IOException e) {
            logger.error("Failed to read {} '{}': {}", kind, name, e.getMessage());
            return null;
        }
    }

    private void register(Map<String, Integer> table, String name, byte[] bytes, boolean stream) {
        CendaAudio.Engine e = engine;
        if (e == null) return;
        try {
            int id = e.load(bytes, stream);
            Integer old = table.put(name, id);
            if (old != null) e.free(old);
            warnedMissing.remove(name);
        } catch (IllegalArgumentException ex) {
            logger.error("Failed to load {} '{}': {}", stream ? "music" : "sound", name, ex.getMessage());
        }
    }

    public boolean isSoundLoaded(String name) {
        return name != null && sounds.containsKey(name);
    }

    public boolean isMusicLoaded(String name) {
        return name != null && music.containsKey(name);
    }

    // ---- playback ---------------------------------------------------------------------------

    /**
     * The general play: returns the voice id (0 when nothing was queued) for later
     * {@link #stopVoice}/{@link #setVoiceGain}/{@link #setVoicePosition} calls.
     *
     * @param spatial  positioned at (x, y, z) with distance attenuation, else listener-relative
     * @param priority higher survives voice stealing
     */
    public long play(String name, int bus, float volume, float pitch, boolean spatial, boolean loop,
                     int priority, float x, float y, float z) {
        CendaAudio.Engine e = engine;
        if (e == null) return 0;
        Integer id = sounds.get(name);
        if (id == null) {
            if (warnedMissing.add(name)) logger.warn("Sound not found: {}", name);
            return 0;
        }
        int flags = (spatial ? CendaAudio.PLAY_SPATIAL : 0) | (loop ? CendaAudio.PLAY_LOOP : 0);
        CendaAudio.Commands batch = batches.get();
        batch.clear();
        batch.play(id, bus, flags, priority, sanitize(volume), pitch, x, y, z, 0, 0, 0, 0, 0);
        e.submit(batch);
        return batch.voice(0);
    }

    public void playSound(String name) {
        playSoundWithVolume(name, DEFAULT_VOLUME);
    }

    public void playSoundWithVolume(String name, float volume) {
        playSound(name, volume, 1.0f);
    }

    /** Plays a 2D sound at a random pitch within ±10%. */
    public void playSoundWithVariation(String name, float volume) {
        playSound(name, volume, variedPitch());
    }

    /** Plays a 2D (listener-relative) sound at an explicit pitch. */
    public void playSound(String name, float volume, float pitch) {
        play(name, BUS_SFX, volume, pitch, false, false, 0, 0, 0, 0);
    }

    /** Plays a sound at a 3D world position at an explicit pitch. */
    public void playSoundAt3D(String name, float volume, float pitch, float x, float y, float z) {
        play(name, BUS_SFX, volume, pitch, true, false, 0, x, y, z);
    }

    public void playSoundAt3D(String name, float volume, float pitch, org.joml.Vector3f position) {
        playSoundAt3D(name, volume, pitch, position.x, position.y, position.z);
    }

    /** Plays a sound at a 3D world position (inverse distance, full at 1 block, floor at 50). */
    public void playSoundAt3D(String name, float volume, float x, float y, float z) {
        playSoundAt3D(name, volume, 1.0f, x, y, z);
    }

    public void playSoundAt3DWithVariation(String name, float volume, float x, float y, float z) {
        playSoundAt3D(name, volume, variedPitch(), x, y, z);
    }

    public void playSoundAt3D(String name, float volume, org.joml.Vector3f position) {
        playSoundAt3D(name, volume, position.x, position.y, position.z);
    }

    public void playSoundAt3DWithVariation(String name, float volume, org.joml.Vector3f position) {
        playSoundAt3DWithVariation(name, volume, position.x, position.y, position.z);
    }

    /**
     * Plays loaded sound {@code name} as a looping 2D ambience at {@code volume} (0..1, before the
     * master volume), starting it if needed. Call every frame with the current level: the gain
     * ramps live, and at 0 the loop pauses where it is and resumes without a restart.
     */
    public void setLoopVolume(String name, float volume) {
        CendaAudio.Engine e = engine;
        if (e == null) return;
        float gain = sanitize(volume);
        Long voice = loopVoices.get(name);
        if (voice != null && e.voiceState(voice) == CendaAudio.VOICE_DONE) {
            loopVoices.remove(name);
            loopGains.remove(name);
            voice = null;
        }
        if (voice == null) {
            if (gain <= 0f) return;
            long v = play(name, BUS_AMBIENT, gain, 1.0f, false, true, AMBIENT_PRIORITY, 0, 0, 0);
            if (v != 0) {
                loopVoices.put(name, v);
                loopGains.put(name, gain);
            }
            return;
        }
        Float last = loopGains.get(name);
        if (last != null && last == gain) return;
        loopGains.put(name, gain);
        setVoiceGain(voice, gain, VOLUME_RAMP_SECONDS);
    }

    /**
     * Plays loaded sound {@code name} once as long 2D ambience (thunder). Ambience outranks
     * effects when voices run out, and among ambience the louder sound keeps its voice.
     *
     * @param volume 0..1, before the master volume
     * @return whether it was queued
     */
    public boolean playAmbient(String name, float volume, float pitch) {
        return play(name, BUS_AMBIENT, volume, pitch, false, false, AMBIENT_PRIORITY, 0, 0, 0) != 0;
    }

    /** Stops a voice from {@link #play}, fading out over {@code fadeSeconds} (0 = cut). */
    public void stopVoice(long voice, float fadeSeconds) {
        CendaAudio.Engine e = engine;
        if (e == null || voice == 0) return;
        CendaAudio.Commands batch = batches.get();
        batch.clear();
        batch.stop(voice, fadeSeconds);
        e.submit(batch);
    }

    public void setVoiceGain(long voice, float volume, float rampSeconds) {
        CendaAudio.Engine e = engine;
        if (e == null || voice == 0) return;
        CendaAudio.Commands batch = batches.get();
        batch.clear();
        batch.voiceGain(voice, sanitize(volume), rampSeconds);
        e.submit(batch);
    }

    public void setVoicePosition(long voice, float x, float y, float z) {
        CendaAudio.Engine e = engine;
        if (e == null || voice == 0) return;
        CendaAudio.Commands batch = batches.get();
        batch.clear();
        batch.voicePosition(voice, x, y, z);
        e.submit(batch);
    }

    /** True while the voice is queued, playing or paused. */
    public boolean isVoiceActive(long voice) {
        CendaAudio.Engine e = engine;
        return e != null && voice != 0 && e.voiceState(voice) != CendaAudio.VOICE_DONE;
    }

    // ---- music ------------------------------------------------------------------------------

    /** Plays {@code name} on the music bus, fading out whatever was playing. */
    public void playMusic(String name) {
        CendaAudio.Engine e = engine;
        if (e == null) return;
        Integer id = music.get(name);
        if (id == null) {
            logger.warn("Music track not found: {}", name);
            return;
        }
        CendaAudio.Commands batch = batches.get();
        batch.clear();
        long previous = musicVoice;
        if (previous != 0) batch.stop(previous, MUSIC_SWITCH_FADE_SECONDS);
        int index = batch.play(id, BUS_MUSIC, 0, 0, 1.0f, 1.0f);
        e.submit(batch);
        musicVoice = batch.voice(index);
    }

    public void stopMusic() {
        long voice = musicVoice;
        musicVoice = 0;
        stopVoice(voice, MUSIC_SWITCH_FADE_SECONDS);
    }

    /** False once the current track has finished — callers poll this to advance a playlist. */
    public boolean isMusicPlaying() {
        return isVoiceActive(musicVoice);
    }

    /** Background-music volume (0..1), applied live on the music bus. */
    public void setMusicVolume(float volume) {
        if (Float.isNaN(volume)) return; // keep the current volume: a NaN gain would silence music for good
        this.musicVolume = Math.max(0f, Math.min(1f, volume));
        applyMusicBus(VOLUME_RAMP_SECONDS);
    }

    public float getMusicVolume() {
        return musicVolume;
    }

    // ---- listener ---------------------------------------------------------------------------

    public void setListenerPosition(float x, float y, float z) {
        synchronized (listener) {
            listener[0] = x;
            listener[1] = y;
            listener[2] = z;
        }
        submitListener();
    }

    public void setListenerOrientation(org.joml.Vector3f forward, org.joml.Vector3f up) {
        synchronized (listener) {
            listener[3] = forward.x;
            listener[4] = forward.y;
            listener[5] = forward.z;
            listener[6] = up.x;
            listener[7] = up.y;
            listener[8] = up.z;
        }
        submitListener();
    }

    /** Listener position and orientation from the camera; call every frame. */
    public void setListenerFromCamera(org.joml.Vector3f position, org.joml.Vector3f front, org.joml.Vector3f up) {
        synchronized (listener) {
            listener[0] = position.x;
            listener[1] = position.y;
            listener[2] = position.z;
            listener[3] = front.x;
            listener[4] = front.y;
            listener[5] = front.z;
            listener[6] = up.x;
            listener[7] = up.y;
            listener[8] = up.z;
        }
        submitListener();
    }

    private void submitListener() {
        CendaAudio.Engine e = engine;
        if (e == null) return;
        CendaAudio.Commands batch = batches.get();
        batch.clear();
        synchronized (listener) {
            batch.listener(listener[0], listener[1], listener[2], listener[3], listener[4], listener[5],
                    listener[6], listener[7], listener[8]);
        }
        e.submit(batch);
    }

    // ---- volume -----------------------------------------------------------------------------

    /** The user's master volume (0..1), applied live to everything. */
    public void setMasterVolume(float volume) {
        volumeController.setMasterVolume(volume);
        applyMasterBus(VOLUME_RAMP_SECONDS);
    }

    public float getMasterVolume() {
        return volumeController.getMasterVolume();
    }

    /**
     * Multiplicative ducking on top of the master volume (e.g. underwater). 1.0 = none.
     * Applies live to everything playing, music included.
     */
    public void setEnvironmentGain(float gain) {
        volumeController.setEnvironmentGain(gain);
        applyMasterBus(VOLUME_RAMP_SECONDS);
    }

    public float getEnvironmentGain() {
        return volumeController.getEnvironmentGain();
    }

    /**
     * Muffles world sound (effects and ambience, not music or UI) through a low-pass filter at
     * {@code cutoffHz}; 0 removes it. E.g. ~800 Hz while the listener is underwater.
     */
    public void setEnvironmentLowPass(float cutoffHz) {
        if (Float.isNaN(cutoffHz)) return;
        environmentLowPassHz = Math.max(0f, cutoffHz);
        applyEnvironmentFilter(VOLUME_RAMP_SECONDS);
    }

    public float getEnvironmentLowPass() {
        return environmentLowPassHz;
    }

    private void applyMasterBus(float ramp) {
        submitBus(BUS_MASTER, volumeController.getEffectiveVolume(), 0f, ramp);
    }

    private void applyMusicBus(float ramp) {
        submitBus(BUS_MUSIC, musicVolume, 0f, ramp);
    }

    private void applyEnvironmentFilter(float ramp) {
        submitBus(BUS_SFX, 1f, environmentLowPassHz, ramp);
        submitBus(BUS_AMBIENT, 1f, environmentLowPassHz, ramp);
    }

    private void submitBus(int bus, float gain, float lowPassHz, float ramp) {
        CendaAudio.Engine e = engine;
        if (e == null) return;
        synchronized (sentBus) {
            float[] sent = sentBus[bus];
            if (sent != null && sent[0] == gain && sent[1] == lowPassHz) return;
            sentBus[bus] = new float[]{gain, lowPassHz};
        }
        CendaAudio.Commands batch = batches.get();
        batch.clear();
        batch.bus(bus, gain, lowPassHz, ramp);
        e.submit(batch);
    }

    // ---- diagnostics ------------------------------------------------------------------------

    /** Live mixer numbers, or null while audio is unavailable. */
    public AudioStats stats() {
        CendaAudio.Engine e = engine;
        if (e == null) return null;
        return AudioStats.of(e.stats(), e.deviceName());
    }

    public void testBasicFunctionality() {
        AudioStats s = stats();
        if (s == null) {
            System.out.println("Audio is disabled: " + unavailableReason);
            return;
        }
        System.out.println("=== SoundSystem ===");
        System.out.println(s.summary());
        System.out.println("Loaded sounds (" + sounds.size() + "): " + sounds.keySet());
        System.out.println("Loaded music (" + music.size() + "): " + music.keySet());
        System.out.println("===================");
    }

    /** @deprecated OpenAL is gone; prints the native mixer's state instead. */
    @Deprecated
    public void diagnoseOpenAL3D() {
        testBasicFunctionality();
    }

    /** Offline systems only (see {@link #offline(int)}): mixes {@code frames} stereo frames. */
    public void renderOffline(float[] out, int frames) {
        CendaAudio.Engine e = engine;
        if (e == null) throw new IllegalStateException("audio unavailable: " + unavailableReason);
        e.render(out, frames);
    }

    /** Stops the device and frees every sound. {@link #initialize()} may run again afterwards. */
    public synchronized void cleanup() {
        CendaAudio.Engine e = engine;
        engine = null;
        sounds.clear();
        music.clear();
        loopVoices.clear();
        loopGains.clear();
        musicVoice = 0;
        synchronized (sentBus) {
            java.util.Arrays.fill(sentBus, null);
        }
        unavailableReason = "shut down";
        if (e != null) e.close();
    }

    private static float sanitize(float volume) {
        return Float.isNaN(volume) ? 0f : Math.max(0f, volume);
    }

    private static float variedPitch() {
        return 0.9f + ThreadLocalRandom.current().nextFloat() * 0.2f;
    }
}
