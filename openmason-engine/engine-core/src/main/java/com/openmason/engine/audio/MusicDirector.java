package com.openmason.engine.audio;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Background music from small playlists, one per scene ({@code S}: the host's own enum or keys).
 * Within a scene it moves to the next track whenever one finishes (a one-track playlist loops);
 * changing scene switches at once; coming back to a scene resumes from its NEXT track. A null
 * scene (or one without a playlist) is silence. Tracks stream (see {@link SoundSystem#loadMusic})
 * and are loaded on their scene's first use unless {@link #preload} ran.
 *
 * <p>Call {@link #update} every frame from one thread.
 */
public final class MusicDirector<S> {

    /** One track: its sound name and where the host finds it. */
    public record Track(String name, String resourcePath) {
    }

    /** Loads a track into the sound system (the host knows where its resources live). */
    @FunctionalInterface
    public interface TrackLoader {
        void load(SoundSystem sounds, Track track);
    }

    private final SoundSystem sounds;
    private final Map<S, List<Track>> playlists;
    private final TrackLoader loader;
    private final Set<S> loaded = new HashSet<>();
    private final Map<S, Integer> lastTrack;
    private boolean enabled;
    private S playing;

    public MusicDirector(SoundSystem sounds, Map<S, List<Track>> playlists, TrackLoader loader) {
        this.sounds = sounds;
        this.playlists = Map.copyOf(playlists);
        this.loader = loader;
        this.lastTrack = new HashMap<>();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** The setting only; {@link #update} decides what is audible on its next call. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setVolume(float volume) {
        sounds.setMusicVolume(volume);
    }

    /** The scene whose playlist is playing, or null. */
    public S playingScene() {
        return playing;
    }

    /**
     * Music plays while enabled and {@code scene} has a playlist; it stops the moment that stops
     * being true, and a change of scene switches playlist at once.
     */
    public void update(S scene) {
        S wanted = enabled && scene != null && hasPlaylist(scene) ? scene : null;
        if (!java.util.Objects.equals(wanted, playing)) {
            if (playing != null) sounds.stopMusic();
            playing = wanted;
        }
        if (wanted == null) return;
        ensureLoaded(wanted);
        if (!sounds.isMusicPlaying()) {
            List<Track> list = playlists.get(wanted);
            int next = (lastTrack.getOrDefault(wanted, -1) + 1) % list.size();
            lastTrack.put(wanted, next);
            sounds.playMusic(list.get(next).name());
        }
    }

    /** Loads a scene's tracks now rather than on its first activation. Idempotent. */
    public void preload(S scene) {
        if (scene != null && hasPlaylist(scene)) ensureLoaded(scene);
    }

    private boolean hasPlaylist(S scene) {
        List<Track> list = playlists.get(scene);
        return list != null && !list.isEmpty();
    }

    private void ensureLoaded(S scene) {
        if (!loaded.add(scene)) return;
        for (Track track : playlists.get(scene)) {
            if (!sounds.isMusicLoaded(track.name())) loader.load(sounds, track);
        }
    }
}
