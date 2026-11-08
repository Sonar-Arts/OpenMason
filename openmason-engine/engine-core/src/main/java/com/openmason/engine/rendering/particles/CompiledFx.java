package com.openmason.engine.rendering.particles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An {@link FxSystem} flattened into index tables the {@link FxWorld} reads per frame: emitter timelines as parallel
 * arrays, bindings as (parameter, target, emitter) rows, handlers grouped by emitter and trigger, cues by trigger.
 * Built once per system per world; immutable.
 */
final class CompiledFx {

    static final int CH_RATE = 0, CH_BURST = 1, CH_SIZE = 2, CH_SPEED = 3, CHANNELS = 4;
    private static final int[] NONE = new int[0];

    final FxSystem source;
    final int emitters;
    final ParticleEffect[] effects;
    final float[] offX, offY, offZ, delay, duration, interval, rateScale, sizeScale, speedScale, dirX, dirY, dirZ;
    final int[] loops, burst, tint;

    final String[] paramNames;
    final FxParameter.Type[] paramTypes;
    /** Four floats per parameter. */
    final float[] paramDefaults;

    final int[] bParam, bTarget, bEmitter;
    final float[] bScale, bBias;

    final int[] hSpawn, hCount;
    final float[] hInherit, hChance;
    final String[] hForward, hSound;
    final int[][] deathHandlers, collisionHandlers;
    final String[] customNames;
    final int[][] customHandlers;

    final FxLight light;

    final int[] playCues, stopCues;
    final int[][] startCues;
    final String[] cueSound;
    final float[] cueVolume;

    CompiledFx(FxSystem fx) {
        source = fx;
        List<FxEmitter> list = fx.emitters();
        int n = emitters = list.size();
        effects = new ParticleEffect[n];
        offX = new float[n]; offY = new float[n]; offZ = new float[n];
        delay = new float[n]; duration = new float[n]; interval = new float[n];
        rateScale = new float[n]; sizeScale = new float[n]; speedScale = new float[n];
        dirX = new float[n]; dirY = new float[n]; dirZ = new float[n];
        loops = new int[n]; burst = new int[n]; tint = new int[n];
        Map<String, Integer> emitterIndex = new LinkedHashMap<>();
        for (int e = 0; e < n; e++) {
            FxEmitter em = list.get(e);
            emitterIndex.put(em.name(), e);
            effects[e] = em.effect();
            offX[e] = em.offsetX(); offY[e] = em.offsetY(); offZ[e] = em.offsetZ();
            delay[e] = em.delay();
            duration[e] = em.duration();
            interval[e] = em.interval();
            loops[e] = em.duration() == FxEmitter.FOREVER ? 1 : em.loops();
            burst[e] = em.burst();
            rateScale[e] = em.rateScale(); sizeScale[e] = em.sizeScale(); speedScale[e] = em.speedScale();
            tint[e] = em.tint();
            if (em.hasDirection()) {
                float len = (float) Math.sqrt(em.dirX() * em.dirX() + em.dirY() * em.dirY() + em.dirZ() * em.dirZ());
                if (len < 1e-6f) {
                    dirX[e] = Float.NaN;
                } else {
                    dirX[e] = em.dirX() / len; dirY[e] = em.dirY() / len; dirZ[e] = em.dirZ() / len;
                }
            } else {
                dirX[e] = Float.NaN;
            }
        }

        List<FxParameter> params = fx.parameters();
        paramNames = new String[params.size()];
        paramTypes = new FxParameter.Type[params.size()];
        paramDefaults = new float[params.size() * 4];
        Map<String, Integer> paramIndex = new LinkedHashMap<>();
        for (int p = 0; p < params.size(); p++) {
            FxParameter fp = params.get(p);
            paramIndex.put(fp.name(), p);
            paramNames[p] = fp.name();
            paramTypes[p] = fp.type();
            paramDefaults[p * 4] = fp.x();
            paramDefaults[p * 4 + 1] = fp.y();
            paramDefaults[p * 4 + 2] = fp.z();
            paramDefaults[p * 4 + 3] = fp.w();
        }

        List<FxBinding> bindings = fx.bindings();
        int nb = bindings.size();
        bParam = new int[nb]; bTarget = new int[nb]; bEmitter = new int[nb];
        bScale = new float[nb]; bBias = new float[nb];
        for (int b = 0; b < nb; b++) {
            FxBinding fb = bindings.get(b);
            bParam[b] = paramIndex.get(fb.parameter());
            bTarget[b] = fb.target().ordinal();
            bEmitter[b] = fb.emitter() == null ? -1 : emitterIndex.get(fb.emitter());
            bScale[b] = fb.scale();
            bBias[b] = fb.bias();
        }

        List<FxEventHandler> handlers = fx.handlers();
        int nh = handlers.size();
        hSpawn = new int[nh]; hCount = new int[nh];
        hInherit = new float[nh]; hChance = new float[nh];
        hForward = new String[nh]; hSound = new String[nh];
        List<List<Integer>> death = perEmitter(n), collision = perEmitter(n);
        Map<String, List<Integer>> custom = new LinkedHashMap<>();
        for (int h = 0; h < nh; h++) {
            FxEventHandler fh = handlers.get(h);
            hSpawn[h] = fh.spawnInto() == null ? -1 : emitterIndex.get(fh.spawnInto());
            hCount[h] = fh.count();
            hInherit[h] = fh.inheritVelocity();
            hChance[h] = fh.chance();
            hForward[h] = fh.forward();
            hSound[h] = fh.sound();
            switch (fh.trigger()) {
                case DEATH -> death.get(emitterIndex.get(fh.source())).add(h);
                case COLLISION -> collision.get(emitterIndex.get(fh.source())).add(h);
                case CUSTOM -> custom.computeIfAbsent(fh.source(), k -> new ArrayList<>()).add(h);
            }
        }
        deathHandlers = toArrays(death);
        collisionHandlers = toArrays(collision);
        customNames = custom.keySet().toArray(new String[0]);
        customHandlers = new int[customNames.length][];
        for (int c = 0; c < customNames.length; c++) {
            customHandlers[c] = toArray(custom.get(customNames[c]));
        }

        light = fx.light();

        List<FxSoundCue> cues = fx.cues();
        cueSound = new String[cues.size()];
        cueVolume = new float[cues.size()];
        List<Integer> play = new ArrayList<>(), stop = new ArrayList<>();
        List<List<Integer>> start = perEmitter(n);
        for (int c = 0; c < cues.size(); c++) {
            FxSoundCue cue = cues.get(c);
            cueSound[c] = cue.sound();
            cueVolume[c] = cue.volume();
            switch (cue.trigger()) {
                case PLAY -> play.add(c);
                case STOP -> stop.add(c);
                case EMITTER_START -> start.get(emitterIndex.get(cue.emitter())).add(c);
            }
        }
        playCues = toArray(play);
        stopCues = toArray(stop);
        startCues = toArrays(start);
    }

    /** Index of the custom event {@code name}'s handler group, or -1. */
    int customIndex(String name) {
        for (int c = 0; c < customNames.length; c++) {
            if (customNames[c].equals(name)) {
                return c;
            }
        }
        return -1;
    }

    /** Index of parameter {@code name}, or -1. */
    int paramIndex(String name) {
        for (int p = 0; p < paramNames.length; p++) {
            if (paramNames[p].equals(name)) {
                return p;
            }
        }
        return -1;
    }

    private static List<List<Integer>> perEmitter(int n) {
        List<List<Integer>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new ArrayList<>());
        }
        return out;
    }

    private static int[][] toArrays(List<List<Integer>> lists) {
        int[][] out = new int[lists.size()][];
        for (int i = 0; i < out.length; i++) {
            out[i] = toArray(lists.get(i));
        }
        return out;
    }

    private static int[] toArray(List<Integer> list) {
        if (list.isEmpty()) {
            return NONE;
        }
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = list.get(i);
        }
        return a;
    }
}
