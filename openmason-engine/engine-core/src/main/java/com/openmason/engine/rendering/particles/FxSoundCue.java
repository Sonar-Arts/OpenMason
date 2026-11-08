package com.openmason.engine.rendering.particles;

import java.util.Objects;

/**
 * A sound an {@link FxSystem} plays at a moment of its timeline. {@link #sound} is an event name of the system's
 * shared {@code format.sound} definitions ({@link FxSystem#sounds()}, the same {@code sounds[]} SBO/SBE carry); the
 * host plays it through its audio ({@link FxHost#sound}). Sounds for particle events go on
 * {@link FxEventHandler#sound}.
 *
 * @param trigger when it plays
 * @param emitter {@link Trigger#EMITTER_START}: the emitter whose cycles start it; null otherwise
 * @param sound   sound event name
 * @param volume  gain multiplier (× {@link FxBinding.Target#VOLUME} bindings)
 */
public record FxSoundCue(Trigger trigger, String emitter, String sound, float volume) {

    public enum Trigger {
        /** The instance starts (its first update). */
        PLAY,
        /** Every cycle start of {@link #emitter}. */
        EMITTER_START,
        /** The instance stops emitting: {@link FxInstance#stop} finished fading, or every emitter completed. */
        STOP
    }

    public FxSoundCue {
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(sound, "sound");
        if (sound.isBlank()) {
            throw new IllegalArgumentException("sound cue names no sound");
        }
        if ((trigger == Trigger.EMITTER_START) != (emitter != null)) {
            throw new IllegalArgumentException("only an EMITTER_START cue names an emitter");
        }
        if (!(volume > 0f) || !Float.isFinite(volume)) {
            throw new IllegalArgumentException("sound cue volume must be finite and > 0");
        }
    }

    public static FxSoundCue onPlay(String sound) {
        return new FxSoundCue(Trigger.PLAY, null, sound, 1f);
    }

    public static FxSoundCue onStop(String sound) {
        return new FxSoundCue(Trigger.STOP, null, sound, 1f);
    }

    public static FxSoundCue onEmitterStart(String emitter, String sound) {
        return new FxSoundCue(Trigger.EMITTER_START, emitter, sound, 1f);
    }

    public FxSoundCue withVolume(float v) {
        return new FxSoundCue(trigger, emitter, sound, v);
    }
}
