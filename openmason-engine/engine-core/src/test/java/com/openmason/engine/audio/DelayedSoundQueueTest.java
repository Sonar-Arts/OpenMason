package com.openmason.engine.audio;

import com.openmason.engine.audio.emitters.SoundEmitter;
import com.openmason.engine.audio.emitters.SoundEmitterManager;
import org.joml.Vector3f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Delayed ambience (thunder) and interval emitters: pure timing, no device. */
@Tag("regression")
class DelayedSoundQueueTest {

    @Test
    void arrivalIsDelayedBySpeedOfSound() {
        assertEquals(10.0 + 1.0, DelayedSoundQueue.arrival(10.0, 343.0), 1e-9);
        assertEquals(5.0, DelayedSoundQueue.arrival(5.0, -1.0), 1e-9);
    }

    @Test
    void closeSoundsMergeKeepingTheLouder() {
        DelayedSoundQueue q = new DelayedSoundQueue(0.4);
        q.schedule(1.0, "a", 0.2f, 1f);
        q.schedule(1.3, "b", 0.6f, 0.9f); // within 0.4 s: replaces the fainter one
        q.schedule(1.5, "c", 0.1f, 1f);   // within 0.4 s of b: dropped, fainter
        q.schedule(3.0, "d", 0.1f, 1f);
        assertEquals(2, q.pending().size());
        assertEquals("b", q.pending().get(0).sound());
    }

    @Test
    void playsOnlyWhatIsDue() {
        DelayedSoundQueue q = new DelayedSoundQueue(0.1);
        q.schedule(1.0, "a", 1f, 1f);
        q.schedule(2.0, "b", 1f, 1f);
        assertEquals(0, q.playDue(0.5, null));
        assertEquals(1, q.playDue(1.0, null));
        assertEquals(1, q.pending().size());
        q.clear();
        assertEquals(0, q.playDue(10, null));
    }

    @Test
    void emittersFireOnTheirIntervalAndHonourTheVeto() {
        AtomicInteger plays = new AtomicInteger();
        boolean[] allowed = {true};
        SoundEmitter emitter = new SoundEmitter(new Vector3f(1, 2, 3), 1.0f) {
            @Override
            protected void playSound() {
                plays.incrementAndGet();
            }

            @Override
            protected boolean canPlaySounds() {
                return allowed[0];
            }

            @Override
            public String getEmitterType() {
                return "test";
            }
        };
        SoundEmitterManager manager = new SoundEmitterManager();
        manager.addEmitter(emitter);
        for (int i = 0; i < 10; i++) manager.update(0.25f);
        assertEquals(2, plays.get(), "2.5 s at a 1 s interval");
        allowed[0] = false;
        for (int i = 0; i < 10; i++) manager.update(0.25f);
        assertEquals(2, plays.get(), "vetoed");
        assertEquals(1, manager.removeEmittersInRadius(new Vector3f(1, 2, 3.5f), 1f));
        assertTrue(manager.getEmitters().isEmpty());
    }
}
