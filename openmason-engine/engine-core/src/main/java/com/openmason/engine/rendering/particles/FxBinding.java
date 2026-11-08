package com.openmason.engine.rendering.particles;

import java.util.Objects;

/**
 * Routes an {@link FxParameter} into a value of the system. Float targets multiply: the target becomes
 * {@code base × Π(bias + scale × param)} over all its bindings (so "intensity" bound to {@link Target#RATE} with
 * scale 1 and bias 0 scales emission linearly, and bias 1 / scale 0.5 makes it gentler). Colour targets multiply
 * component-wise, {@link Target#OFFSET} adds {@code scale × param}, {@link Target#DIRECTION} replaces the launch
 * direction.
 *
 * @param parameter parameter name
 * @param target    what it drives
 * @param emitter   the emitter it drives, or null for every emitter (emitter targets) / the system (light, volume)
 * @param scale     float and offset targets: multiplier of the parameter
 * @param bias      float targets: added after scaling
 */
public record FxBinding(String parameter, Target target, String emitter, float scale, float bias) {

    /** What a binding drives, and the parameter type it takes. */
    public enum Target {
        /** Continuous emission rate. */
        RATE(FxParameter.Type.FLOAT, true),
        /** Particles per cycle burst. */
        BURST(FxParameter.Type.FLOAT, true),
        /** Spawned particle size. */
        SIZE(FxParameter.Type.FLOAT, true),
        /** Spawned particle launch speed. */
        SPEED(FxParameter.Type.FLOAT, true),
        /** Spawned particle colour. */
        TINT(FxParameter.Type.COLOR, true),
        /** Launch direction. */
        DIRECTION(FxParameter.Type.VECTOR, true),
        /** Emitter position relative to the instance. */
        OFFSET(FxParameter.Type.VECTOR, true),
        /** The system light's intensity. */
        LIGHT_INTENSITY(FxParameter.Type.FLOAT, false),
        /** The system light's colour. */
        LIGHT_COLOR(FxParameter.Type.COLOR, false),
        /** Volume of every sound the system plays. */
        VOLUME(FxParameter.Type.FLOAT, false);

        public final FxParameter.Type type;
        /** True for per-emitter targets; false for system-wide outputs (emitter must be null). */
        public final boolean perEmitter;

        Target(FxParameter.Type type, boolean perEmitter) {
            this.type = type;
            this.perEmitter = perEmitter;
        }
    }

    public FxBinding {
        Objects.requireNonNull(parameter, "parameter");
        Objects.requireNonNull(target, "target");
        if (!target.perEmitter && emitter != null) {
            throw new IllegalArgumentException(target + " is system-wide: a binding to it names no emitter");
        }
        if (!Float.isFinite(scale) || !Float.isFinite(bias)) {
            throw new IllegalArgumentException("binding scale and bias must be finite");
        }
    }

    /** {@code parameter} drives {@code target} on every emitter (or the system), as a plain multiplier. */
    public static FxBinding of(String parameter, Target target) {
        return new FxBinding(parameter, target, null, 1f, 0f);
    }

    /** {@code parameter} drives {@code target} on one emitter, as a plain multiplier. */
    public static FxBinding of(String parameter, Target target, String emitter) {
        return new FxBinding(parameter, target, emitter, 1f, 0f);
    }

    /** {@code bias + scale × parameter} multiplies {@code target} ({@code emitter} null = every emitter / system). */
    public static FxBinding mapped(String parameter, Target target, String emitter, float scale, float bias) {
        return new FxBinding(parameter, target, emitter, scale, bias);
    }
}
