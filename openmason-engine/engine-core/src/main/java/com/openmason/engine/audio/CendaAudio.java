package com.openmason.engine.audio;

import com.openmason.engine.cenda.CendaKernels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * FFM binding to the Cenda audio engine ({@code ca_*} in {@code libcenda_kernels}, contract in
 * {@code cenda/native/kernels/include/cenda/audio.h}). miniaudio supplies the device and the
 * decoders; the mixer, buses, spatializer and streaming are Cenda's.
 *
 * <p>Audio has no Java fallback: when the library is missing or fails the {@code ca_abi_version}
 * handshake, {@link #isAvailable()} is false, {@link #unavailableReason()} says why, and
 * {@link SoundSystem} runs silent. Every {@link Engine} call except {@link Engine#render} and
 * {@link Engine#close} is safe from any thread; a {@link Commands} batch belongs to one thread.
 */
public final class CendaAudio {

    private static final Logger LOGGER = LoggerFactory.getLogger(CendaAudio.class);

    /** Must equal {@code CA_ABI_VERSION} in audio.h (build-kernels.sh checks both sides). */
    static final int EXPECTED_ABI = 1;

    public static final int DEVICE_DEFAULT = 0;
    public static final int DEVICE_NULL = 1;
    public static final int DEVICE_OFFLINE = 2;

    public static final int BUS_COUNT = 8;

    public static final int PLAY_LOOP = 1;
    public static final int PLAY_SPATIAL = 2;

    public static final int VOICE_DONE = 0;
    public static final int VOICE_PENDING = 1;
    public static final int VOICE_PLAYING = 2;
    public static final int VOICE_PAUSED = 3;

    public static final int STAT_VOICES_ACTIVE = 0;
    public static final int STAT_VOICES_PEAK = 1;
    public static final int STAT_VOICES_STOLEN = 2;
    public static final int STAT_PLAYS_DROPPED = 3;
    public static final int STAT_STREAM_UNDERRUNS = 4;
    public static final int STAT_BLOCKS = 5;
    public static final int STAT_MIX_NANOS_LAST = 6;
    public static final int STAT_MIX_NANOS_MAX = 7;
    public static final int STAT_SOUNDS = 8;
    public static final int STAT_SOUND_BYTES = 9;
    public static final int STAT_SAMPLE_RATE = 10;
    public static final int STAT_COMMANDS = 11;
    public static final int STAT_STREAMS = 12;
    public static final int STAT_COUNT = 13;

    static final int CMD_PLAY = 1;
    static final int CMD_STOP = 2;
    static final int CMD_VOICE_GAIN = 3;
    static final int CMD_VOICE_PITCH = 4;
    static final int CMD_VOICE_POSITION = 5;
    static final int CMD_LISTENER = 6;
    static final int CMD_BUS = 7;
    static final int CMD_STOP_BUS = 8;

    private static final int LOAD_STREAM = 1;

    // struct ca_command (72 bytes)
    static final long COMMAND_BYTES = 72;
    private static final long OFF_KIND = 0;
    private static final long OFF_SOUND = 4;
    private static final long OFF_BUS = 8;
    private static final long OFF_FLAGS = 12;
    private static final long OFF_PRIORITY = 16;
    private static final long OFF_VOICE = 24;
    private static final long OFF_F = 32;

    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;
    private static final ValueLayout ADDR = ValueLayout.ADDRESS;

    private static final String FAILURE;
    private static final MethodHandle LAST_ERROR;
    private static final MethodHandle CREATE;
    private static final MethodHandle DESTROY;
    private static final MethodHandle SAMPLE_RATE;
    private static final MethodHandle DEVICE_NAME;
    private static final MethodHandle LOAD;
    private static final MethodHandle LOAD_PCM;
    private static final MethodHandle INFO;
    private static final MethodHandle FREE;
    private static final MethodHandle SUBMIT;
    private static final MethodHandle VOICE_STATE;
    private static final MethodHandle UPDATE;
    private static final MethodHandle RENDER;
    private static final MethodHandle STATS;

    static {
        String failure = null;
        SymbolLookup lookup = null;
        try {
            Path lib = CendaKernels.libraryPath().orElse(null);
            if (lib == null) {
                failure = "Cenda library not found (searched -Dcenda.kernels.path, CENDA_KERNELS_PATH, the build "
                    + "folders and the copy packaged in engine-core); build it with openmason-engine/cenda/build-kernels.sh";
            } else {
                lookup = SymbolLookup.libraryLookup(lib, Arena.global());
                MethodHandle abi = Linker.nativeLinker().downcallHandle(
                    lookup.find("ca_abi_version").orElseThrow(
                        () -> new IllegalStateException("library has no audio engine (ca_abi_version missing)")),
                    FunctionDescriptor.of(I32));
                int have = (int) abi.invokeExact();
                if (have != EXPECTED_ABI) {
                    failure = "Cenda audio at " + lib + " has ABI " + have + " (expected " + EXPECTED_ABI
                        + "); rebuild with openmason-engine/cenda/build-kernels.sh";
                    lookup = null;
                } else {
                    LOGGER.info("Cenda audio (ABI {}) loaded from {}", have, lib);
                }
            }
        } catch (Throwable t) {
            failure = "Cenda audio failed to load: " + t;
            lookup = null;
        }
        if (failure != null) {
            LOGGER.error("Audio disabled: {}", failure);
        }
        FAILURE = failure;
        LAST_ERROR = handle(lookup, "ca_last_error", FunctionDescriptor.of(ADDR));
        CREATE = handle(lookup, "ca_engine_create", FunctionDescriptor.of(ADDR, I32, I32, I32));
        DESTROY = handle(lookup, "ca_engine_destroy", FunctionDescriptor.ofVoid(ADDR));
        SAMPLE_RATE = handle(lookup, "ca_engine_sample_rate", FunctionDescriptor.of(I32, ADDR));
        DEVICE_NAME = handle(lookup, "ca_engine_device_name", FunctionDescriptor.of(ADDR, ADDR));
        LOAD = handle(lookup, "ca_sound_load", FunctionDescriptor.of(I32, ADDR, ADDR, I64, I32));
        LOAD_PCM = handle(lookup, "ca_sound_load_pcm", FunctionDescriptor.of(I32, ADDR, ADDR, I64, I32, I32));
        INFO = handle(lookup, "ca_sound_info", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR));
        FREE = handle(lookup, "ca_sound_free", FunctionDescriptor.of(I32, ADDR, I32));
        SUBMIT = handle(lookup, "ca_submit", FunctionDescriptor.of(I32, ADDR, ADDR, I32), Linker.Option.critical(false));
        VOICE_STATE = handle(lookup, "ca_voice_state", FunctionDescriptor.of(I32, ADDR, I64), Linker.Option.critical(false));
        UPDATE = handle(lookup, "ca_update", FunctionDescriptor.of(I32, ADDR));
        RENDER = handle(lookup, "ca_render", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        STATS = handle(lookup, "ca_stats", FunctionDescriptor.of(I32, ADDR, ADDR, I32), Linker.Option.critical(false));
    }

    private CendaAudio() {
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor fd, Linker.Option... options) {
        if (lookup == null) return null;
        return lookup.find(name)
            .map(symbol -> Linker.nativeLinker().downcallHandle(symbol, fd, options))
            .orElseThrow(() -> new IllegalStateException("Cenda audio symbol missing: " + name));
    }

    /** True when the native audio engine loaded and passed its handshake. */
    public static boolean isAvailable() {
        return FAILURE == null;
    }

    /** Why audio is unavailable, or null when it is available. */
    public static String unavailableReason() {
        return FAILURE;
    }

    private static String lastError() {
        try {
            MemorySegment text = (MemorySegment) LAST_ERROR.invokeExact();
            return text.reinterpret(1024).getString(0);
        } catch (Throwable t) {
            return "unknown error";
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }

    /** What a loaded sound is: {@code frames} is -1 for a stream whose length is unknown. */
    public record SoundInfo(int channels, int sampleRate, long frames) {
    }

    /**
     * One native engine: a device (or an offline mixer), its sounds and its voices. Calls after
     * {@link #close()} are no-ops (a sound played from another thread during shutdown is dropped,
     * never a use-after-free): every call holds a read lock that {@code close} takes for writing.
     */
    public static final class Engine implements AutoCloseable {

        @FunctionalInterface
        private interface NativeCall<T> {
            T call() throws Throwable;
        }

        private final MemorySegment ptr;
        private final int mode;
        private final java.util.concurrent.locks.ReentrantReadWriteLock guard =
                new java.util.concurrent.locks.ReentrantReadWriteLock();
        private boolean closed;

        private Engine(MemorySegment ptr, int mode) {
            this.ptr = ptr;
            this.mode = mode;
        }

        private <T> T guarded(T whenClosed, NativeCall<T> call) {
            guard.readLock().lock();
            try {
                return closed ? whenClosed : call.call();
            } catch (Throwable t) {
                throw rethrow(t);
            } finally {
                guard.readLock().unlock();
            }
        }

        /**
         * @param mode       {@link #DEVICE_DEFAULT}, {@link #DEVICE_NULL} or {@link #DEVICE_OFFLINE}
         * @param sampleRate 0 = the device's own rate (offline needs one)
         * @param maxVoices  0 = the engine default (96)
         * @throws IllegalStateException when the engine is unavailable or the device cannot open
         */
        public static Engine create(int mode, int sampleRate, int maxVoices) {
            if (!isAvailable()) throw new IllegalStateException(FAILURE);
            try {
                MemorySegment ptr = (MemorySegment) CREATE.invokeExact(mode, sampleRate, maxVoices);
                if (ptr.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("audio engine could not start: " + lastError());
                }
                return new Engine(ptr, mode);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        public int mode() {
            return mode;
        }

        public int sampleRate() {
            return guarded(0, () -> (int) SAMPLE_RATE.invokeExact(ptr));
        }

        public String deviceName() {
            return guarded("closed", () -> ((MemorySegment) DEVICE_NAME.invokeExact(ptr)).reinterpret(1024).getString(0));
        }

        /**
         * Decodes an encoded file (WAV, FLAC, MP3, Ogg Vorbis), or with {@code stream} keeps the
         * bytes and decodes while playing (music). The bytes are copied.
         *
         * @return the sound id
         * @throws IllegalArgumentException when the bytes cannot be decoded (message says why)
         * @throws IllegalStateException    after {@link #close()}
         */
        public int load(byte[] bytes, boolean stream) {
            int id = guarded(0, () -> {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment data = arena.allocate(Math.max(1, bytes.length));
                    MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, bytes.length);
                    int r = (int) LOAD.invokeExact(ptr, data, (long) bytes.length, stream ? LOAD_STREAM : 0);
                    if (r <= 0) throw new IllegalArgumentException(lastError());
                    return r;
                }
            });
            if (id == 0) throw new IllegalStateException("audio engine closed");
            return id;
        }

        /** Registers interleaved float PCM (1 or 2 channels); returns the sound id. */
        public int loadPcm(float[] interleaved, int channels, int sampleRate) {
            int id = guarded(0, () -> {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment data = arena.allocateFrom(ValueLayout.JAVA_FLOAT, interleaved);
                    int r = (int) LOAD_PCM.invokeExact(ptr, data, (long) (interleaved.length / channels), channels,
                            sampleRate);
                    if (r <= 0) throw new IllegalArgumentException(lastError());
                    return r;
                }
            });
            if (id == 0) throw new IllegalStateException("audio engine closed");
            return id;
        }

        /** The sound's format, or null when the id is unknown or freed. */
        public SoundInfo info(int sound) {
            return guarded(null, () -> {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment ch = arena.allocate(I32);
                    MemorySegment rate = arena.allocate(I32);
                    MemorySegment frames = arena.allocate(I64);
                    int r = (int) INFO.invokeExact(ptr, sound, ch, rate, frames);
                    if (r != 0) return null;
                    return new SoundInfo(ch.get(I32, 0), rate.get(I32, 0), frames.get(I64, 0));
                }
            });
        }

        /** Stops the sound's voices; its memory goes on a later {@link #update()}. */
        public boolean free(int sound) {
            return guarded(false, () -> (int) FREE.invokeExact(ptr, sound) == 0);
        }

        /** Queues the batch in order and writes the play ids back into it. */
        public void submit(Commands batch) {
            if (batch.count == 0) return;
            guarded(null, () -> {
                int r = (int) SUBMIT.invokeExact(ptr, batch.segment, batch.count);
                if (r < 0) throw new IllegalArgumentException("ca_submit refused the batch (" + r + ")");
                return null;
            });
        }

        /** One of {@link #VOICE_DONE}, {@link #VOICE_PENDING}, {@link #VOICE_PLAYING}, {@link #VOICE_PAUSED}. */
        public int voiceState(long voice) {
            return guarded(VOICE_DONE, () -> (int) VOICE_STATE.invokeExact(ptr, voice));
        }

        /** Once per frame: releases freed sounds. */
        public void update() {
            guarded(null, () -> {
                int ignored = (int) UPDATE.invokeExact(ptr);
                return null;
            });
        }

        /** Offline engines only: mixes {@code frames} interleaved stereo frames into {@code out}. */
        public void render(float[] out, int frames) {
            if (out.length < frames * 2) throw new IllegalArgumentException("out holds fewer than " + frames + " frames");
            guarded(null, () -> {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment buf = arena.allocate(ValueLayout.JAVA_FLOAT, (long) frames * 2);
                    int r = (int) RENDER.invokeExact(ptr, buf, frames);
                    if (r < 0) throw new IllegalStateException("ca_render needs an offline engine (" + r + ")");
                    MemorySegment.copy(buf, ValueLayout.JAVA_FLOAT, 0, out, 0, frames * 2);
                }
                return null;
            });
        }

        /** The {@code STAT_*} slots (all zero after {@link #close()}). */
        public long[] stats() {
            return guarded(new long[STAT_COUNT], () -> {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment out = arena.allocate(I64, STAT_COUNT);
                    int n = (int) STATS.invokeExact(ptr, out, STAT_COUNT);
                    long[] stats = new long[STAT_COUNT];
                    for (int i = 0; i < n; i++) stats[i] = out.getAtIndex(I64, i);
                    return stats;
                }
            });
        }

        /** Waits for calls in flight, then stops the device and frees everything. Idempotent. */
        @Override
        public void close() {
            guard.writeLock().lock();
            try {
                if (closed) return;
                closed = true;
                DESTROY.invokeExact(ptr);
            } catch (Throwable t) {
                throw rethrow(t);
            } finally {
                guard.writeLock().unlock();
            }
        }
    }

    /**
     * A reusable off-heap command batch (one thread at a time). Add commands, {@link Engine#submit}
     * it, read play ids with {@link #voice(int)}, then {@link #clear()}.
     */
    public static final class Commands {

        private final Arena arena = Arena.ofAuto();
        private MemorySegment segment;
        private int count;

        public Commands(int capacity) {
            segment = arena.allocate(COMMAND_BYTES * Math.max(1, capacity), 8);
        }

        public int count() {
            return count;
        }

        public void clear() {
            count = 0;
        }

        /** The voice id {@link Engine#submit} assigned to the play at {@code index}. */
        public long voice(int index) {
            return segment.get(I64, index * COMMAND_BYTES + OFF_VOICE);
        }

        private long next(int kind) {
            long capacity = segment.byteSize() / COMMAND_BYTES;
            if (count == capacity) {
                MemorySegment grown = arena.allocate(segment.byteSize() * 2, 8);
                MemorySegment.copy(segment, 0, grown, 0, segment.byteSize());
                segment = grown;
            }
            long base = count * COMMAND_BYTES;
            segment.asSlice(base, COMMAND_BYTES).fill((byte) 0);
            segment.set(I32, base + OFF_KIND, kind);
            count++;
            return base;
        }

        private void f(long base, int slot, float value) {
            segment.set(F32, base + OFF_F + slot * 4L, value);
        }

        /**
         * Queues a play. Distances of 0/0/0 mean the defaults (ref 1, max 50, rolloff 1).
         *
         * @return the command's index (for {@link #voice(int)} after submitting)
         */
        public int play(int sound, int bus, int flags, int priority, float gain, float pitch,
                        float x, float y, float z, float refDistance, float maxDistance, float rolloff,
                        float fadeInSeconds, float startSeconds) {
            long base = next(CMD_PLAY);
            segment.set(I32, base + OFF_SOUND, sound);
            segment.set(I32, base + OFF_BUS, bus);
            segment.set(I32, base + OFF_FLAGS, flags);
            segment.set(I32, base + OFF_PRIORITY, priority);
            f(base, 0, gain);
            f(base, 1, pitch);
            f(base, 2, x);
            f(base, 3, y);
            f(base, 4, z);
            f(base, 5, refDistance);
            f(base, 6, maxDistance);
            f(base, 7, rolloff);
            f(base, 8, fadeInSeconds);
            f(base, 9, startSeconds);
            return count - 1;
        }

        /** A listener-relative play at default distances. */
        public int play(int sound, int bus, int flags, int priority, float gain, float pitch) {
            return play(sound, bus, flags, priority, gain, pitch, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        public void stop(long voice, float fadeSeconds) {
            long base = next(CMD_STOP);
            segment.set(I64, base + OFF_VOICE, voice);
            f(base, 0, fadeSeconds);
        }

        public void voiceGain(long voice, float gain, float rampSeconds) {
            long base = next(CMD_VOICE_GAIN);
            segment.set(I64, base + OFF_VOICE, voice);
            f(base, 0, gain);
            f(base, 1, rampSeconds);
        }

        public void voicePitch(long voice, float pitch) {
            long base = next(CMD_VOICE_PITCH);
            segment.set(I64, base + OFF_VOICE, voice);
            f(base, 0, pitch);
        }

        public void voicePosition(long voice, float x, float y, float z) {
            long base = next(CMD_VOICE_POSITION);
            segment.set(I64, base + OFF_VOICE, voice);
            f(base, 0, x);
            f(base, 1, y);
            f(base, 2, z);
        }

        public void listener(float x, float y, float z, float fx, float fy, float fz, float ux, float uy, float uz) {
            long base = next(CMD_LISTENER);
            f(base, 0, x);
            f(base, 1, y);
            f(base, 2, z);
            f(base, 3, fx);
            f(base, 4, fy);
            f(base, 5, fz);
            f(base, 6, ux);
            f(base, 7, uy);
            f(base, 8, uz);
        }

        /** @param lowPassHz 0 = no filter */
        public void bus(int bus, float gain, float lowPassHz, float rampSeconds) {
            long base = next(CMD_BUS);
            segment.set(I32, base + OFF_BUS, bus);
            f(base, 0, gain);
            f(base, 1, lowPassHz);
            f(base, 2, rampSeconds);
        }

        public void stopBus(int bus, float fadeSeconds) {
            long base = next(CMD_STOP_BUS);
            segment.set(I32, base + OFF_BUS, bus);
            f(base, 0, fadeSeconds);
        }
    }
}
