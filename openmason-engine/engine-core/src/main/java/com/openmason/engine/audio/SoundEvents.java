package com.openmason.engine.audio;

import com.openmason.engine.format.sound.SoundData;
import com.openmason.engine.format.sound.SoundDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * An asset's manifest {@code sounds[]} section ({@link SoundData}, shared by SBO, SBE and FX
 * systems) resolved for playback: every sample loaded into the {@link SoundSystem} under a
 * content-derived key, grouped by event name.
 *
 * <p>Keys dedupe shared samples however many assets reference them: resource-referenced defs
 * key by path ({@code res:/sounds/GrassWalk.wav}), embedded defs by their SHA-256
 * ({@code snd:<checksum>}), so two assets embedding identical audio share one sound.
 *
 * <p>{@link #pick(String)} draws a random sample for an event and {@link Entry#pitch()} its
 * playback pitch: random within the def's authored range when its {@code variation} switch is
 * on, the natural 1.0 otherwise. {@link #play2D}/{@link #playAt} do both and play.
 */
public final class SoundEvents {

    private static final Logger logger = LoggerFactory.getLogger(SoundEvents.class);

    /** Shared empty instance for assets that declare no sounds. */
    public static final SoundEvents EMPTY = new SoundEvents(Map.of());

    /** Opens a referenced sample. Resources usually live in the caller's module (JPMS). */
    @FunctionalInterface
    public interface ResourceOpener {
        /** @return the resource's bytes as a stream, or null when it does not exist */
        InputStream open(String path) throws IOException;
    }

    /** One playable sample: sound key, authored gain, pitch range and the variation switch. */
    public record Entry(String soundKey, float volume, float pitchMin, float pitchMax, boolean variation) {
        /** The pitch for one playback: random in the authored range with variation on, else 1.0. */
        public float pitch() {
            if (!variation) return 1.0f;
            return pitchMin >= pitchMax
                    ? pitchMin
                    : pitchMin + ThreadLocalRandom.current().nextFloat() * (pitchMax - pitchMin);
        }
    }

    private final Map<String, List<Entry>> byEvent;

    public SoundEvents(Map<String, List<Entry>> byEvent) {
        Map<String, List<Entry>> copy = new LinkedHashMap<>();
        byEvent.forEach((event, entries) -> copy.put(event, List.copyOf(entries)));
        this.byEvent = Map.copyOf(copy);
    }

    /** True when at least one sample is bound to {@code event}. */
    public boolean has(String event) {
        return event != null && byEvent.containsKey(event);
    }

    /** A random sample for {@code event}, or null when the event is unbound. */
    public Entry pick(String event) {
        if (event == null) return null;
        List<Entry> entries = byEvent.get(event);
        if (entries == null || entries.isEmpty()) return null;
        return entries.size() == 1
                ? entries.get(0)
                : entries.get(ThreadLocalRandom.current().nextInt(entries.size()));
    }

    public boolean isEmpty() {
        return byEvent.isEmpty();
    }

    /**
     * Plays {@code event} listener-relative (e.g. the local player's own footsteps).
     *
     * @return false when the event is unbound
     */
    public boolean play2D(SoundSystem sounds, String event, float volumeScale) {
        Entry entry = pick(event);
        if (entry == null || sounds == null) return false;
        sounds.playSound(entry.soundKey(), entry.volume() * volumeScale, entry.pitch());
        return true;
    }

    /**
     * Plays {@code event} at a world position.
     *
     * @return false when the event is unbound
     */
    public boolean playAt(SoundSystem sounds, String event, float x, float y, float z, float volumeScale) {
        Entry entry = pick(event);
        if (entry == null || sounds == null) return false;
        sounds.playSoundAt3D(entry.soundKey(), entry.volume() * volumeScale, entry.pitch(), x, y, z);
        return true;
    }

    /**
     * Loads every sample of {@code data} (skipping keys already loaded) and returns the playable
     * event table. Defs whose audio fails to load are dropped, so playback never warns per frame.
     *
     * @param ownerId       label for logs and the fallback key of a def with a blank checksum
     *                      (e.g. the asset's objectId)
     * @param data          the asset's manifest sound section (null-safe)
     * @param embeddedBytes embedded entry filename -> raw sample bytes (the parse result's sound map)
     * @param resources     opens resource-referenced samples; null = none can be opened
     */
    public static SoundEvents resolve(SoundSystem sounds, String ownerId, SoundData data,
                                      Function<String, byte[]> embeddedBytes, ResourceOpener resources) {
        if (sounds == null || data == null || data.isEmpty()) {
            return EMPTY;
        }
        Map<String, List<Entry>> byEvent = new LinkedHashMap<>();
        for (SoundDef def : data.sounds()) {
            String key = keyFor(def, ownerId);
            if (!sounds.isSoundLoaded(key)) {
                InputStream in = open(def, ownerId, embeddedBytes, resources);
                if (in == null) continue;
                sounds.loadSound(key, in);
            }
            if (!sounds.isSoundLoaded(key)) {
                logger.warn("Sound '{}' of {} failed to load ({}) — skipped", def.event(), ownerId,
                        def.isEmbedded() ? def.filename() : def.resourcePath());
                continue;
            }
            byEvent.computeIfAbsent(def.event(), e -> new ArrayList<>())
                    .add(new Entry(key, def.volume(), def.pitchMin(), def.pitchMax(), def.variation()));
        }
        return byEvent.isEmpty() ? EMPTY : new SoundEvents(byEvent);
    }

    private static InputStream open(SoundDef def, String ownerId, Function<String, byte[]> embeddedBytes,
                                    ResourceOpener resources) {
        if (def.isEmbedded()) {
            byte[] bytes = embeddedBytes != null ? embeddedBytes.apply(def.filename()) : null;
            if (bytes == null) {
                logger.warn("Sound '{}' of {} has no embedded bytes for {} — skipped",
                        def.event(), ownerId, def.filename());
                return null;
            }
            return new ByteArrayInputStream(bytes);
        }
        try {
            InputStream in = resources != null ? resources.open(def.resourcePath()) : null;
            if (in == null) {
                logger.warn("Sound '{}' of {}: resource {} not found — skipped", def.event(), ownerId,
                        def.resourcePath());
            }
            return in;
        } catch (IOException e) {
            logger.warn("Sound '{}' of {}: cannot read {} ({}) — skipped", def.event(), ownerId,
                    def.resourcePath(), e.getMessage());
            return null;
        }
    }

    /** Content-derived sound key for a def. */
    public static String keyFor(SoundDef def, String ownerId) {
        if (!def.isEmbedded()) {
            return "res:" + def.resourcePath();
        }
        String checksum = def.checksum();
        if (checksum != null && !checksum.isBlank()) {
            return "snd:" + checksum;
        }
        return "snd:" + ownerId + "/" + def.filename();
    }
}
