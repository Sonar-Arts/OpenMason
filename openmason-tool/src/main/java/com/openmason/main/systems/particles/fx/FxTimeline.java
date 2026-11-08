package com.openmason.main.systems.particles.fx;

import com.openmason.engine.rendering.particles.FxEmitter;
import com.openmason.engine.rendering.particles.FxSystem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * GL-free timeline maths for the FX editor's system view (OM #104): the cycles an emitter runs, as the bars of its
 * track, and the preview's emitter filter (enable / solo). Mirrors {@code FxWorld}'s schedule: cycle {@code k}
 * starts at {@code delay + k * (duration + interval)}, bursts at its start and emits for {@code duration}.
 */
public final class FxTimeline {

    /** One cycle on a track: {@code [start, end]} seconds ({@code end == start} for a burst-only cycle). */
    public record Cycle(int index, double start, double end, boolean burst) {
    }

    /** Most cycles listed per track (endless loops are cut at the horizon anyway). */
    public static final int MAX_CYCLES = 256;

    private FxTimeline() {
    }

    /** The cycles of {@code e} that start before {@code horizon} seconds (endless emission ends at the horizon). */
    public static List<Cycle> cycles(FxEmitter e, double horizon) {
        List<Cycle> out = new ArrayList<>();
        boolean forever = e.duration() == FxEmitter.FOREVER;
        int loops = forever ? 1 : e.loops();
        double period = forever ? Double.POSITIVE_INFINITY : (double) e.duration() + e.interval();
        for (int k = 0; (loops == 0 || k < loops) && out.size() < MAX_CYCLES; k++) {
            double start = e.delay() + (k == 0 ? 0.0 : k * period);
            if (start >= horizon) {
                break;
            }
            double end = forever ? horizon : Math.min(horizon, start + e.duration());
            out.add(new Cycle(k, start, end, e.burst() > 0));
            if (!(period > 0.0)) {
                break;
            }
        }
        return out;
    }

    /** When the last finite cycle of the system ends; +inf when any emitter runs until stopped. */
    public static double length(FxSystem s) {
        double end = 0.0;
        for (FxEmitter e : s.emitters()) {
            if (e.endless()) {
                return Double.POSITIVE_INFINITY;
            }
            double period = (double) e.duration() + e.interval();
            end = Math.max(end, e.delay() + (e.loops() - 1) * period + e.duration());
        }
        return end;
    }

    /**
     * The system the preview plays: muted emitters (and, while any emitter is soloed, every emitter not soloed)
     * keep their place and timeline but emit nothing by themselves - events can still spawn into them, so a
     * soloed explosion that other emitters feed still shows. Names not in the system are ignored.
     */
    public static FxSystem filter(FxSystem s, Collection<String> muted, Collection<String> soloed) {
        Set<String> solo = Set.copyOf(soloed);
        boolean anySolo = s.emitters().stream().anyMatch(e -> solo.contains(e.name()));
        if (!anySolo && muted.stream().noneMatch(m -> s.emitters().stream().anyMatch(e -> e.name().equals(m)))) {
            return s;
        }
        List<FxEmitter> out = new ArrayList<>();
        for (FxEmitter e : s.emitters()) {
            boolean silent = anySolo ? !solo.contains(e.name()) : muted.contains(e.name());
            out.add(silent ? e.toBuilder().burst(0).rateScale(0f).build() : e);
        }
        return s.toBuilder().emitters(out).build();
    }
}
