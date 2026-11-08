package com.openmason.main.systems.particles.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.openmason.engine.rendering.particles.FxEmitter;
import com.openmason.engine.rendering.particles.FxHost;
import com.openmason.engine.rendering.particles.FxInstance;
import com.openmason.engine.rendering.particles.FxParameter;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.FxWorld;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleLibrary;
import com.openmason.engine.rendering.particles.ParticleSystem;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.openmason.main.systems.mcp.McpErrors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plays an FX document headlessly through the real engine runtime ({@link FxWorld} over a {@link ParticleSystem},
 * exactly what a game runs) and reports what happened: live particles over time per effect, the volume they fill,
 * forwarded events, sounds, the light, budget losses, and warnings for emitters that never spawned. The vision-free
 * way for an agent to check an effect does what it should; {@code fx_capture} renders the same run. GL-free.
 */
public final class FxSimulation {

    /** Most simulated seconds per call. */
    static final float MAX_SECONDS = 120f;
    /** Particle budget of the simulated system (the Particle Editor preview's). */
    static final int BUDGET = 60_000;
    static final float DT = 1f / 60f;

    /**
     * What to run.
     *
     * @param seconds  simulated time
     * @param at       instance position
     * @param floor    y of a solid floor colliding particles land on, or NaN for none
     * @param params   parameter values by name: number (float), [x,y,z] (vector), "#RRGGBB[AA]" or [r,g,b,a] (colour)
     * @param raises   custom events to raise at given times
     * @param stopAt   time to call stop, or NaN
     * @param stopFade fade seconds of that stop
     * @param samples  timeline samples reported
     * @param seed     random seed (same seed = same run)
     */
    public record Options(float seconds, double[] at, float floor, JsonNode params, List<Raise> raises, float stopAt,
                          float stopFade, int samples, long seed) {
        public Options {
            if (!(seconds > 0f) || seconds > MAX_SECONDS) {
                throw new IllegalArgumentException("seconds must be in (0, " + MAX_SECONDS + "]");
            }
            at = at == null ? new double[3] : at;
            raises = raises == null ? List.of() : List.copyOf(raises);
            samples = Math.clamp(samples, 1, 60);
        }

        public static Options of(float seconds) {
            return new Options(seconds, null, 0f, null, List.of(), Float.NaN, 0f, 8, 1L);
        }
    }

    /** {@code raise(name)} on the instance at {@code time} seconds. */
    public record Raise(float time, String name) {
    }

    /** A finished run: the system in its final state (for rendering) and the report. */
    public record Run(ParticleSystem system, Map<String, Object> report) {
    }

    private FxSimulation() {
    }

    /** Runs {@code doc} and returns the report. */
    public static Map<String, Object> report(OmfxDocument doc, Options o) {
        return run(doc, o).report();
    }

    public static Run run(OmfxDocument doc, Options o) {
        FxSystem system = doc.system();
        ParticleSystem particles = new ParticleSystem(BUDGET, o.seed());
        if (!Float.isNaN(o.floor())) {
            int floorTop = (int) Math.floor(o.floor());
            particles.setCollider((x, y, z) -> y < floorTop);
        }
        FxWorld world = new FxWorld(particles);
        Map<String, int[]> events = new LinkedHashMap<>();
        Map<String, double[]> firstEventAt = new LinkedHashMap<>();
        Map<String, Integer> sounds = new LinkedHashMap<>();
        world.setHost(new FxHost() {
            @Override
            public void event(FxInstance instance, String name, double x, double y, double z) {
                events.computeIfAbsent(name, k -> new int[1])[0]++;
                firstEventAt.putIfAbsent(name, new double[]{round(x), round(y), round(z)});
            }

            @Override
            public void sound(FxInstance instance, String sound, double x, double y, double z, float volume) {
                sounds.merge(sound, 1, Integer::sum);
            }
        });

        FxInstance instance = world.play(system, o.at()[0], o.at()[1], o.at()[2]);
        if (instance == null) {
            throw new IllegalStateException("the FX world refused to play the system");
        }
        applyParams(instance, system, o.params());

        List<ParticleEffect> effects = doc.effects();
        Map<ParticleEffect, Integer> peakByEffect = new LinkedHashMap<>();
        effects.forEach(e -> peakByEffect.put(e, 0));
        List<Raise> raises = new ArrayList<>(o.raises());
        raises.sort((a, b) -> Float.compare(a.time(), b.time()));
        int steps = Math.max(1, Math.round(o.seconds() / DT));
        int sampleEvery = Math.max(1, steps / o.samples());
        List<Object> timeline = new ArrayList<>();
        int peak = 0;
        float peakAt = 0f;
        float finishedAt = Float.NaN;
        boolean stopped = false;
        float[] light = new float[2];
        int nextRaise = 0;
        Set<String> unknownRaises = new LinkedHashSet<>();
        for (int step = 1; step <= steps; step++) {
            float t = step * DT;
            while (nextRaise < raises.size() && raises.get(nextRaise).time() <= t) {
                String name = raises.get(nextRaise++).name();
                if (!instance.raise(name)) {
                    unknownRaises.add(name);
                }
            }
            if (!stopped && !Float.isNaN(o.stopAt()) && t >= o.stopAt()) {
                instance.stop(o.stopFade());
                stopped = true;
            }
            world.update(DT);
            int live = particles.liveCount();
            if (live > peak) {
                peak = live;
                peakAt = t;
            }
            for (ParticleEffect e : effects) {
                int n = particles.liveCount(e);
                if (n > peakByEffect.get(e)) {
                    peakByEffect.put(e, n);
                }
            }
            if (Float.isNaN(finishedAt) && !instance.isAlive()) {
                finishedAt = t;
            }
            if (step % sampleEvery == 0 || step == steps) {
                light[0] = 0f;
                light[1] = 0f;
                world.forEachLight((inst, x, y, z, r, g, b, intensity, radius) -> {
                    light[0] = Math.max(light[0], intensity);
                    light[1] = Math.max(light[1], radius);
                });
                timeline.add(sample(t, particles, effects, system.light() != null ? light[0] : Float.NaN));
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("id", doc.id());
        report.put("seconds", o.seconds());
        report.put("finishedAt", Float.isNaN(finishedAt) ? null : round(finishedAt));
        if (Float.isNaN(finishedAt)) {
            report.put("stillRunning", system.endless() && !stopped ? "endless: runs until stopped" : "yes");
        }
        report.put("peakLive", peak);
        report.put("peakAt", round(peakAt));
        report.put("spawned", particles.spawnedTotal());
        Map<String, Integer> peaks = new LinkedHashMap<>();
        peakByEffect.forEach((e, n) -> peaks.put(e.name(), n));
        report.put("peakByEffect", peaks);
        report.put("timeline", timeline);
        if (!events.isEmpty()) {
            Map<String, Object> ev = new LinkedHashMap<>();
            events.forEach((k, v) -> ev.put(k, Map.of("count", v[0], "first", firstEventAt.get(k))));
            report.put("hostEvents", ev);
        }
        if (!sounds.isEmpty()) {
            report.put("sounds", sounds);
        }
        report.put("warnings", warnings(system, peakByEffect, particles, world, unknownRaises));
        instance.release();
        return new Run(particles, report);
    }

    private static Map<String, Object> sample(float t, ParticleSystem particles, List<ParticleEffect> effects,
                                              float light) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", round(t));
        int live = particles.liveCount();
        m.put("live", live);
        if (effects.size() > 1) {
            Map<String, Integer> by = new LinkedHashMap<>();
            for (ParticleEffect e : effects) {
                int n = particles.liveCount(e);
                if (n > 0) {
                    by.put(e.name(), n);
                }
            }
            m.put("byEffect", by);
        }
        if (live > 0) {
            double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
            double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (int i = 0; i < live; i++) {
                double[] p = {particles.worldX(i), particles.worldY(i), particles.worldZ(i)};
                for (int a = 0; a < 3; a++) {
                    min[a] = Math.min(min[a], p[a]);
                    max[a] = Math.max(max[a], p[a]);
                }
            }
            m.put("min", new double[]{round(min[0]), round(min[1]), round(min[2])});
            m.put("max", new double[]{round(max[0]), round(max[1]), round(max[2])});
        }
        if (!Float.isNaN(light)) {
            m.put("light", round(light));
        }
        return m;
    }

    private static List<String> warnings(FxSystem system, Map<ParticleEffect, Integer> peakByEffect,
                                         ParticleSystem particles, FxWorld world, Set<String> unknownRaises) {
        List<String> out = new ArrayList<>();
        for (FxEmitter e : system.emitters()) {
            if (peakByEffect.getOrDefault(e.effect(), 0) == 0) {
                boolean eventOnly = e.burst() == 0 && e.rateScale() == 0f;
                out.add("emitter '" + e.name() + "' never had a live particle"
                        + (eventOnly ? " (event-only: no event spawned into it)"
                        : e.delay() > 0f ? " (delay " + e.delay() + " s: run longer?)" : ""));
            }
        }
        if (particles.droppedTotal() > 0) {
            out.add(particles.droppedTotal() + " spawns refused by the " + BUDGET + "-particle budget");
        }
        if (particles.eventsDroppedTotal() > 0 || world.eventsDroppedTotal() > 0) {
            out.add((particles.eventsDroppedTotal() + world.eventsDroppedTotal())
                    + " sub-emitter/FX events dropped (event queue full)");
        }
        for (String name : unknownRaises) {
            out.add("raise '" + name + "' has no CUSTOM handler in this system");
        }
        return out;
    }

    private static void applyParams(FxInstance instance, FxSystem system, JsonNode params) {
        if (params == null || params.isNull()) {
            return;
        }
        if (!params.isObject()) {
            throw new IllegalArgumentException("'params' must be an object {name: value}");
        }
        for (var it = params.fields(); it.hasNext(); ) {
            var f = it.next();
            FxParameter p = null;
            for (FxParameter q : system.parameters()) {
                if (q.name().equals(f.getKey())) {
                    p = q;
                }
            }
            if (p == null) {
                throw McpErrors.unknownEntity("parameter", f.getKey(),
                        system.parameters().stream().map(FxParameter::name).toList(), "fx_get");
            }
            JsonNode v = f.getValue();
            switch (p.type()) {
                case FLOAT -> {
                    if (!v.isNumber()) {
                        throw new IllegalArgumentException("parameter '" + p.name() + "' is a float: pass a number");
                    }
                    instance.setParam(p.name(), v.floatValue());
                }
                case VECTOR -> {
                    if (!v.isArray() || v.size() != 3) {
                        throw new IllegalArgumentException("parameter '" + p.name() + "' is a vector: pass [x,y,z]");
                    }
                    instance.setParam(p.name(), v.get(0).floatValue(), v.get(1).floatValue(), v.get(2).floatValue());
                }
                case COLOR -> instance.setParamColor(p.name(), color(p.name(), v));
            }
        }
    }

    private static int color(String name, JsonNode v) {
        if (v.isTextual()) {
            return ParticleLibrary.parseColor(v.asText());
        }
        if (v.isArray() && v.size() == 4) {
            int rgba = 0;
            for (int i = 0; i < 4; i++) {
                rgba = (rgba << 8) | Math.clamp(Math.round(v.get(i).floatValue() * 255f), 0, 255);
            }
            return rgba;
        }
        throw new IllegalArgumentException("parameter '" + name + "' is a colour: pass \"#RRGGBB[AA]\" or [r,g,b,a]");
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
