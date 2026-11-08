package com.openmason.main.systems.particles.fx;

import com.openmason.engine.rendering.particles.FxEmitter;
import com.openmason.engine.rendering.particles.FxPresets;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleLibrary;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Timeline tracks and the preview's enable/solo filter (OM #104). */
@Tag("regression")
class FxTimelineTest {

    @Test
    void cyclesFollowDelayDurationIntervalAndLoops() {
        FxEmitter e = FxEmitter.builder("e", ParticleLibrary.Presets.SPARKS).delay(0.5f).duration(1f).interval(0.5f)
                .loops(3).burst(10).build();
        List<FxTimeline.Cycle> c = FxTimeline.cycles(e, 10);
        assertEquals(3, c.size());
        assertEquals(0.5, c.get(0).start(), 1e-9);
        assertEquals(1.5, c.get(0).end(), 1e-9);
        assertEquals(2.0, c.get(1).start(), 1e-9);
        assertEquals(3.5, c.get(2).start(), 1e-9);
        assertTrue(c.get(0).burst());
        assertEquals(4.5, FxTimeline.length(FxSystem.builder("s").emitter(e).build()), 1e-6);
    }

    @Test
    void endlessEmittersRunToTheHorizon() {
        FxEmitter forever = FxEmitter.builder("f", ParticleLibrary.Presets.SMOKE).delay(1f).build();
        assertEquals(List.of(new FxTimeline.Cycle(0, 1.0, 6.0, false)), FxTimeline.cycles(forever, 6));
        FxEmitter loop = FxEmitter.builder("l", ParticleLibrary.Presets.SPARKS).duration(0f).interval(1f).loops(0)
                .burst(5).build();
        assertEquals(6, FxTimeline.cycles(loop, 6).size(), "cut at the horizon");
        assertEquals(Double.POSITIVE_INFINITY, FxTimeline.length(FxPresets.CAMPFIRE));
    }

    @Test
    void muteAndSoloSilenceEmittersButKeepThemAsEventTargets() {
        FxSystem s = FxPresets.CAMPFIRE;
        assertSame(s, FxTimeline.filter(s, Set.of(), Set.of()), "nothing filtered: the same system");
        FxSystem muted = FxTimeline.filter(s, Set.of("smoke"), Set.of());
        assertEquals(0f, muted.emitters().get(2).rateScale());
        assertEquals(1f, muted.emitters().get(0).rateScale());
        assertEquals(s.emitters().get(2).delay(), muted.emitters().get(2).delay(), "timeline kept");
        FxSystem solo = FxTimeline.filter(s, Set.of(), Set.of("embers"));
        assertEquals(List.of(0f, 1f, 0f), solo.emitters().stream().map(FxEmitter::rateScale).toList());
        assertEquals(s.emitters().size(), solo.emitters().size());
    }
}
