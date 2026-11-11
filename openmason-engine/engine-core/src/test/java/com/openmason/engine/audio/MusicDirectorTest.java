package com.openmason.engine.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.openmason.engine.audio.AudioFixtures.constant16;
import static com.openmason.engine.audio.AudioFixtures.pcmLe;
import static com.openmason.engine.audio.AudioFixtures.wav;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Scene playlists on an offline engine: lazy loading, advancing, switching and resuming. */
@Tag("integration")
class MusicDirectorTest {

    private static final int RATE = 48000;
    private static final int TRACK_FRAMES = 2400; // 50 ms tracks

    enum Scene { MENU, BATTLE }

    private SoundSystem sounds;
    private final List<String> loads = new ArrayList<>();
    private MusicDirector<Scene> director;

    @BeforeEach
    void open() {
        assumeTrue(CendaAudio.isAvailable(), () -> "Cenda audio not built: " + CendaAudio.unavailableReason());
        sounds = SoundSystem.offline(RATE);
        byte[] track = wav(pcmLe(RATE, 16, 2), constant16(TRACK_FRAMES, (short) 100, (short) 100));
        director = new MusicDirector<>(sounds, Map.of(
                Scene.MENU, List.of(new MusicDirector.Track("m1", "/m1"), new MusicDirector.Track("m2", "/m2")),
                Scene.BATTLE, List.of(new MusicDirector.Track("b1", "/b1"))),
                (s, t) -> {
                    loads.add(t.name());
                    s.loadMusic(t.name(), new ByteArrayInputStream(track));
                });
    }

    @AfterEach
    void close() {
        if (sounds != null) sounds.cleanup();
    }

    private void render(int frames) {
        sounds.renderOffline(new float[frames * 2], frames);
    }

    @Test
    void disabledOrSceneless() {
        director.update(Scene.MENU);
        assertNull(director.playingScene(), "off until enabled");
        assertTrue(loads.isEmpty(), "nothing loads while off");
        director.setEnabled(true);
        director.update(null);
        assertNull(director.playingScene());
    }

    @Test
    void playsAdvancesAndLoadsLazily() {
        director.setEnabled(true);
        director.update(Scene.MENU);
        assertEquals(List.of("m1", "m2"), loads, "the menu playlist loads on first use, battle does not");
        assertTrue(sounds.isMusicPlaying());
        render(TRACK_FRAMES + 512);
        assertFalse(sounds.isMusicPlaying(), "m1 finished");
        director.update(Scene.MENU); // advances to m2
        assertTrue(sounds.isMusicPlaying());
    }

    @Test
    void switchingScenesAndBackResumesWithTheNextTrack() {
        director.setEnabled(true);
        director.update(Scene.MENU);                // m1
        render(256);
        director.update(Scene.BATTLE);              // stops m1, plays b1
        assertEquals(Scene.BATTLE, director.playingScene());
        assertTrue(loads.contains("b1"));
        render(TRACK_FRAMES * 4);
        director.update(Scene.MENU);                // m2: never repeats the track it left
        render(256);
        director.setEnabled(false);
        director.update(Scene.MENU);
        assertNull(director.playingScene());
        render(RATE / 2);
        assertFalse(sounds.isMusicPlaying(), "disabling stops the music");
    }

    @Test
    void preloadIsIdempotent() {
        director.preload(Scene.BATTLE);
        director.preload(Scene.BATTLE);
        assertEquals(List.of("b1"), loads);
    }
}
