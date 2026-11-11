package com.openmason.engine.audio;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Long ambient one-shots that arrive later than their cause — thunder after its flash, a distant
 * blast — played through {@link SoundSystem#playAmbient} when due. Sounds due within the merge
 * window of each other are heard as one, the louder. Times are in the host's own clock (seconds).
 * Not thread-safe.
 */
public final class DelayedSoundQueue {

    /** Speed of sound in air, metres (= blocks) per second. */
    public static final double SPEED_OF_SOUND = 343.0;

    /** One scheduled sound. */
    public record Pending(double atSeconds, String sound, float volume, float pitch) {
    }

    private final double mergeSeconds;
    private final List<Pending> pending = new ArrayList<>();

    /** @param mergeSeconds sounds due closer together than this play as one, the louder */
    public DelayedSoundQueue(double mergeSeconds) {
        this.mergeSeconds = mergeSeconds;
    }

    /** When a sound made at {@code emittedAt}, {@code distance} away, reaches the listener. */
    public static double arrival(double emittedAt, double distance) {
        return emittedAt + Math.max(0.0, distance) / SPEED_OF_SOUND;
    }

    /** Adds a sound, or folds it into one already due within the merge window. */
    public void schedule(double atSeconds, String sound, float volume, float pitch) {
        Pending p = new Pending(atSeconds, sound, volume, pitch);
        for (int i = 0; i < pending.size(); i++) {
            Pending other = pending.get(i);
            if (Math.abs(other.atSeconds() - atSeconds) < mergeSeconds) {
                if (volume > other.volume()) pending.set(i, p);
                return;
            }
        }
        pending.add(p);
    }

    /** Plays (and removes) every sound due at or before {@code nowSeconds}; returns how many. */
    public int playDue(double nowSeconds, SoundSystem sounds) {
        int played = 0;
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.atSeconds() <= nowSeconds) {
                if (sounds != null) sounds.playAmbient(p.sound(), p.volume(), p.pitch());
                it.remove();
                played++;
            }
        }
        return played;
    }

    public List<Pending> pending() {
        return List.copyOf(pending);
    }

    public void clear() {
        pending.clear();
    }
}
