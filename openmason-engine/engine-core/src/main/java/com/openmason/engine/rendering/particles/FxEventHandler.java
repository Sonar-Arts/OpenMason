package com.openmason.engine.rendering.particles;

import java.util.Objects;

/**
 * What an {@link FxSystem} does when one of its events fires — Niagara event handlers / VFX Graph GPU events. An
 * event is a particle of an emitter dying ({@link Trigger#DEATH}) or hitting something ({@link Trigger#COLLISION}),
 * or a named custom event raised on the instance ({@link FxInstance#raise}). The handler can spawn into another
 * emitter of the same instance (with that emitter's parameters, tint and scales), forward the event to the host as a
 * named callback ({@link FxHost#event}, so game logic stays in the game) and play a sound. This generalises a
 * {@link ParticleEffect.SubEmitter}: the spawned particles belong to the instance and obey its parameters.
 *
 * @param trigger         what fires it
 * @param source          {@link Trigger#DEATH}/{@link Trigger#COLLISION}: the emitter whose particles fire it;
 *                        {@link Trigger#CUSTOM}: the event name
 * @param spawnInto       emitter that receives {@code count} particles at the event (null = none)
 * @param count           particles spawned per event
 * @param inheritVelocity fraction of the firing particle's velocity the spawned particles inherit
 * @param chance          probability 0..1 the handler runs for one event (thins per-particle events)
 * @param forward         host event name ({@link FxHost#event}), or null
 * @param sound           sound event played at the event ({@link FxHost#sound}), or null
 */
public record FxEventHandler(Trigger trigger, String source, String spawnInto, int count, float inheritVelocity,
                             float chance, String forward, String sound) {

    public enum Trigger {
        /** A particle of {@link #source} died of old age. */
        DEATH,
        /** A particle of {@link #source} hit a solid block faster than 1 block/s. */
        COLLISION,
        /** {@link FxInstance#raise} with the name {@link #source}. */
        CUSTOM
    }

    public FxEventHandler {
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(source, "source");
        if (source.isBlank()) {
            throw new IllegalArgumentException("event handler source is blank");
        }
        if (count < 0) {
            throw new IllegalArgumentException("event handler count must be >= 0");
        }
        if (!(chance >= 0f && chance <= 1f) || !Float.isFinite(inheritVelocity)) {
            throw new IllegalArgumentException("event handler chance must be in [0, 1] and inherit finite");
        }
        forward = blankToNull(forward);
        sound = blankToNull(sound);
        spawnInto = blankToNull(spawnInto);
        if (spawnInto == null && forward == null && sound == null) {
            throw new IllegalArgumentException("event handler on '" + source + "' does nothing");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** When particles of {@code source} trigger, spawn {@code count} into {@code target}. */
    public static FxEventHandler spawn(Trigger trigger, String source, String target, int count) {
        return new FxEventHandler(trigger, source, target, count, 0f, 1f, null, null);
    }

    /** When particles of {@code source} trigger, call the host's {@code event}. */
    public static FxEventHandler forward(Trigger trigger, String source, String event) {
        return new FxEventHandler(trigger, source, null, 0, 0f, 1f, event, null);
    }

    public FxEventHandler inheriting(float fraction) {
        return new FxEventHandler(trigger, source, spawnInto, count, fraction, chance, forward, sound);
    }

    public FxEventHandler withChance(float p) {
        return new FxEventHandler(trigger, source, spawnInto, count, inheritVelocity, p, forward, sound);
    }

    public FxEventHandler forwarding(String event) {
        return new FxEventHandler(trigger, source, spawnInto, count, inheritVelocity, chance, event, sound);
    }

    public FxEventHandler playing(String soundEvent) {
        return new FxEventHandler(trigger, source, spawnInto, count, inheritVelocity, chance, forward, soundEvent);
    }
}
