package com.openmason.engine.rendering.particles;

import com.openmason.engine.format.sound.SoundData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A composite effect ("campfire", "explosion"): several {@link FxEmitter}s that start, stop, move and are tuned
 * together, following Unreal Niagara's System → Emitter model. Immutable data; play it with {@link FxWorld#play}.
 *
 * <ul>
 *   <li><b>Emitters</b> — each a {@link ParticleEffect} with an offset and its own timeline (delay, duration,
 *       loops, bursts). Together they are the system's timeline.</li>
 *   <li><b>Exposed parameters</b> — typed inputs ({@link FxParameter}) wired to emitter values, the light and the
 *       volume by {@link FxBinding}s, so a game drives "rain at 0.7" without editing the asset.</li>
 *   <li><b>Events</b> — particle deaths, impacts and custom events spawn into other emitters, reach the host as
 *       named callbacks and play sounds ({@link FxEventHandler}).</li>
 *   <li><b>Outputs besides sprites</b> — an optional point {@link FxLight} and {@link FxSoundCue}s against shared
 *       {@code format.sound} definitions ({@link #sounds}).</li>
 * </ul>
 * There is no scripting language in FX (decision, OM #105): behaviour is data now and compiled graphs later (OM #99).
 *
 * @param name       library key
 * @param emitters   the layers (1..{@value #MAX_EMITTERS}, unique names)
 * @param parameters exposed inputs (unique names)
 * @param bindings   parameter → value wiring
 * @param handlers   event handlers
 * @param light      the system's light, or null
 * @param sounds     sound definitions the cues and handlers name (may be empty: the host resolves names itself)
 * @param cues       timeline sounds
 */
public record FxSystem(String name, List<FxEmitter> emitters, List<FxParameter> parameters, List<FxBinding> bindings,
                       List<FxEventHandler> handlers, FxLight light, SoundData sounds, List<FxSoundCue> cues) {

    /** Most emitters one system may have (owner tags keep the emitter index in a byte, one value reserved). */
    public static final int MAX_EMITTERS = 64;
    /** Most exposed parameters one system may have. */
    public static final int MAX_PARAMETERS = 64;

    public FxSystem {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("FX system name is blank");
        }
        emitters = List.copyOf(emitters == null ? List.of() : emitters);
        parameters = List.copyOf(parameters == null ? List.of() : parameters);
        bindings = List.copyOf(bindings == null ? List.of() : bindings);
        handlers = List.copyOf(handlers == null ? List.of() : handlers);
        cues = List.copyOf(cues == null ? List.of() : cues);
        sounds = sounds == null ? new SoundData(List.of()) : sounds;
        if (emitters.isEmpty() || emitters.size() > MAX_EMITTERS) {
            throw new IllegalArgumentException(name + ": needs 1.." + MAX_EMITTERS + " emitters");
        }
        Set<String> emitterNames = new HashSet<>();
        for (FxEmitter e : emitters) {
            if (!emitterNames.add(e.name())) {
                throw new IllegalArgumentException(name + ": duplicate emitter '" + e.name() + "'");
            }
        }
        if (parameters.size() > MAX_PARAMETERS) {
            throw new IllegalArgumentException(name + ": at most " + MAX_PARAMETERS + " parameters");
        }
        Map<String, FxParameter> params = new HashMap<>();
        for (FxParameter p : parameters) {
            if (params.put(p.name(), p) != null) {
                throw new IllegalArgumentException(name + ": duplicate parameter '" + p.name() + "'");
            }
        }
        for (FxBinding b : bindings) {
            FxParameter p = params.get(b.parameter());
            if (p == null) {
                throw new IllegalArgumentException(name + ": binding names unknown parameter '" + b.parameter() + "'");
            }
            if (p.type() != b.target().type) {
                throw new IllegalArgumentException(name + ": " + b.target() + " takes a " + b.target().type
                        + " parameter, '" + p.name() + "' is " + p.type());
            }
            if (b.emitter() != null && !emitterNames.contains(b.emitter())) {
                throw new IllegalArgumentException(name + ": binding names unknown emitter '" + b.emitter() + "'");
            }
            boolean lightTarget = b.target() == FxBinding.Target.LIGHT_INTENSITY
                    || b.target() == FxBinding.Target.LIGHT_COLOR;
            if (lightTarget && light == null) {
                throw new IllegalArgumentException(name + ": " + b.target() + " binding but the system has no light");
            }
        }
        for (FxEventHandler h : handlers) {
            if (h.trigger() != FxEventHandler.Trigger.CUSTOM && !emitterNames.contains(h.source())) {
                throw new IllegalArgumentException(name + ": event handler listens to unknown emitter '"
                        + h.source() + "'");
            }
            if (h.spawnInto() != null && !emitterNames.contains(h.spawnInto())) {
                throw new IllegalArgumentException(name + ": event handler spawns into unknown emitter '"
                        + h.spawnInto() + "'");
            }
        }
        Set<String> soundEvents = sounds.events();
        for (FxSoundCue c : cues) {
            if (c.emitter() != null && !emitterNames.contains(c.emitter())) {
                throw new IllegalArgumentException(name + ": sound cue names unknown emitter '" + c.emitter() + "'");
            }
            checkSound(name, soundEvents, c.sound());
        }
        for (FxEventHandler h : handlers) {
            if (h.sound() != null) {
                checkSound(name, soundEvents, h.sound());
            }
        }
    }

    /** With sound definitions present, every sound named must be one of them (typos fail at load, not in game). */
    private static void checkSound(String name, Set<String> defined, String sound) {
        if (!defined.isEmpty() && !defined.contains(sound)) {
            throw new IllegalArgumentException(name + ": sound '" + sound + "' is not in the system's sounds");
        }
    }

    /**
     * Today's single-layer effect as a one-emitter system playing exactly as a plain {@link ParticleEmitter} (rate
     * effects, forever) or a {@link ParticleSystem#burst} of {@code burstCount} (burst effects) would.
     */
    public static FxSystem of(ParticleEffect effect, int burstCount) {
        return builder(effect.name()).emitter(FxEmitter.of(effect.name(), effect, burstCount)).build();
    }

    /** Emitter by name, or null. */
    public FxEmitter emitter(String emitterName) {
        for (FxEmitter e : emitters) {
            if (e.name().equals(emitterName)) {
                return e;
            }
        }
        return null;
    }

    /** True when some emitter runs until the instance is stopped. */
    public boolean endless() {
        for (FxEmitter e : emitters) {
            if (e.endless()) {
                return true;
            }
        }
        return false;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public Builder toBuilder() {
        Builder b = new Builder(name);
        b.emitters.addAll(emitters);
        b.parameters.addAll(parameters);
        b.bindings.addAll(bindings);
        b.handlers.addAll(handlers);
        b.light = light;
        b.sounds = sounds;
        b.cues.addAll(cues);
        return b;
    }

    public static final class Builder {
        private String name;
        private final List<FxEmitter> emitters = new ArrayList<>();
        private final List<FxParameter> parameters = new ArrayList<>();
        private final List<FxBinding> bindings = new ArrayList<>();
        private final List<FxEventHandler> handlers = new ArrayList<>();
        private FxLight light;
        private SoundData sounds;
        private final List<FxSoundCue> cues = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder name(String v) { this.name = v; return this; }
        public Builder emitter(FxEmitter e) { emitters.add(e); return this; }
        public Builder emitters(List<FxEmitter> list) { emitters.clear(); emitters.addAll(list); return this; }
        public Builder parameter(FxParameter p) { parameters.add(p); return this; }
        public Builder parameters(List<FxParameter> list) { parameters.clear(); parameters.addAll(list); return this; }
        public Builder bind(FxBinding b) { bindings.add(b); return this; }
        public Builder on(FxEventHandler h) { handlers.add(h); return this; }
        public Builder light(FxLight l) { this.light = l; return this; }
        public Builder sounds(SoundData s) { this.sounds = s; return this; }
        public Builder cue(FxSoundCue c) { cues.add(c); return this; }

        public FxSystem build() {
            return new FxSystem(name, emitters, parameters, bindings, handlers, light, sounds, cues);
        }
    }
}
