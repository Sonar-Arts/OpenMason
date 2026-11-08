package com.openmason.main.systems.particles.fx;

import com.openmason.engine.rendering.particles.FxEmitter;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns document edits into the smallest live update of a running preview (OM #104). Re-reading the JSON view
 * builds every effect anew, so {@link #canonicalize} first puts back the previous objects of effects that did not
 * change: afterwards an effect is a new object exactly when its definition changed, and {@link #plan} lists those
 * as {@code ParticleSystem.replace} swaps, children before the effects that spawn them (so a parent's swap finds
 * its new children already compiled).
 */
public final class FxHotSwap {

    /** One effect to hot-swap. */
    public record Swap(ParticleEffect old, ParticleEffect updated) {
    }

    private FxHotSwap() {
    }

    /** {@code next} with every effect equal to one of {@code prev}'s (by name) replaced by that object. */
    public static OmfxDocument canonicalize(OmfxDocument prev, OmfxDocument next) {
        if (prev == null || prev.system() == next.system()) {
            return next;
        }
        Map<String, ParticleEffect> before = new HashMap<>();
        for (ParticleEffect e : prev.effects()) {
            before.put(e.name(), e);
        }
        Map<ParticleEffect, ParticleEffect> memo = new IdentityHashMap<>();
        List<FxEmitter> emitters = new ArrayList<>();
        boolean changed = false;
        for (FxEmitter em : next.system().emitters()) {
            ParticleEffect canon = canon(em.effect(), before, memo);
            if (canon != em.effect()) {
                emitters.add(em.toBuilder().effect(canon).build());
                changed = true;
            } else {
                emitters.add(em);
            }
        }
        FxSystem system = next.system();
        if (changed) {
            system = system.toBuilder().emitters(emitters).build();
        }
        if (system.equals(prev.system())) {
            system = prev.system();
        }
        if (system == next.system()) {
            return next;
        }
        return new OmfxDocument(next.author(), system, next.sprites(), next.soundFiles(), next.extensions(),
                next.extraFiles());
    }

    private static ParticleEffect canon(ParticleEffect e, Map<String, ParticleEffect> before,
                                        Map<ParticleEffect, ParticleEffect> memo) {
        ParticleEffect done = memo.get(e);
        if (done != null) {
            return done;
        }
        ParticleEffect old = before.get(e.name());
        ParticleEffect result;
        if (old != null && old.equals(e)) {
            result = old;
        } else {
            List<ParticleEffect.SubEmitter> subs = new ArrayList<>();
            boolean swapped = false;
            for (ParticleEffect.SubEmitter s : e.subEmitters()) {
                ParticleEffect child = canon(s.effect(), before, memo);
                swapped |= child != s.effect();
                subs.add(new ParticleEffect.SubEmitter(child, s.trigger(), s.count(), s.rate(), s.inheritVelocity()));
            }
            result = swapped ? e.toBuilder().subEmitters(subs).build() : e;
        }
        memo.put(e, result);
        return result;
    }

    /**
     * The swaps that turn a simulation of {@code prev} into one of {@code next}: effects present in both under the
     * same name whose object differs, children first. Effects only in {@code next} need no swap (they compile on
     * first use); particles of effects only in {@code prev} finish as they are.
     */
    public static List<Swap> plan(FxSystem prev, FxSystem next) {
        Map<String, ParticleEffect> before = new HashMap<>();
        collect(prev, before);
        List<Swap> out = new ArrayList<>();
        Set<ParticleEffect> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> names = new HashSet<>();
        for (FxEmitter em : next.emitters()) {
            postOrder(em.effect(), before, visited, names, out);
        }
        return out;
    }

    private static void postOrder(ParticleEffect e, Map<String, ParticleEffect> before, Set<ParticleEffect> visited,
                                  Set<String> names, List<Swap> out) {
        if (!visited.add(e)) {
            return;
        }
        for (ParticleEffect.SubEmitter s : e.subEmitters()) {
            postOrder(s.effect(), before, visited, names, out);
        }
        ParticleEffect old = before.get(e.name());
        if (old != null && old != e && names.add(e.name())) {
            out.add(new Swap(old, e));
        }
    }

    private static void collect(FxSystem s, Map<String, ParticleEffect> out) {
        for (FxEmitter em : s.emitters()) {
            collect(em.effect(), out);
        }
    }

    private static void collect(ParticleEffect e, Map<String, ParticleEffect> out) {
        if (out.putIfAbsent(e.name(), e) == null) {
            for (ParticleEffect.SubEmitter s : e.subEmitters()) {
                collect(s.effect(), out);
            }
        }
    }
}
