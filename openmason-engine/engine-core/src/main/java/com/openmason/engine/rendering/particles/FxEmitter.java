package com.openmason.engine.rendering.particles;

import java.util.Objects;

/**
 * One layer of an {@link FxSystem}: a {@link ParticleEffect} plus where and when it plays (Niagara's emitter inside
 * a system). Immutable; build with {@link #builder(String, ParticleEffect)}.
 *
 * <h2>Timeline</h2>
 * The emitter runs {@link #loops} cycles (0 = forever). Cycle {@code k} starts at
 * {@code delay + k × (duration + interval)} seconds after the instance starts: it fires {@link #burst} particles at
 * once and then emits continuously at the effect's rate × {@link #rateScale} for {@link #duration} seconds
 * ({@link #FOREVER} = until the instance is stopped; a forever emitter has one cycle). A burst-only emitter has
 * duration 0. An <em>event-only</em> emitter (burst 0, rate scale 0) never emits by itself: other emitters'
 * events and {@link FxInstance#raise} spawn into it ({@link FxEventHandler}).
 *
 * @param name       unique within the system
 * @param effect     the particles this emitter spawns
 * @param offsetX    position relative to the instance, blocks
 * @param offsetY    position relative to the instance
 * @param offsetZ    position relative to the instance
 * @param delay      seconds before the first cycle
 * @param duration   seconds of continuous emission per cycle ({@link #FOREVER}: until stopped)
 * @param loops      cycles to run (≥ 1), or 0 for forever
 * @param interval   seconds between the end of one cycle and the start of the next
 * @param burst      particles spawned at the start of every cycle
 * @param rateScale  multiplies the effect's rate (0 = no continuous emission)
 * @param sizeScale  multiplies spawned particles' sizes
 * @param speedScale multiplies spawned particles' launch speeds
 * @param tint       {@code 0xRRGGBBAA} multiplied into spawned particles' colour
 * @param dirX       launch direction override X ({@code NaN} = the effect's own direction)
 * @param dirY       launch direction override Y
 * @param dirZ       launch direction override Z
 */
public record FxEmitter(
        String name,
        ParticleEffect effect,
        float offsetX, float offsetY, float offsetZ,
        float delay,
        float duration,
        int loops,
        float interval,
        int burst,
        float rateScale,
        float sizeScale,
        float speedScale,
        int tint,
        float dirX, float dirY, float dirZ) {

    /** {@link #duration} of an emitter that runs until its instance is stopped. */
    public static final float FOREVER = Float.POSITIVE_INFINITY;

    public FxEmitter {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(effect, "effect");
        if (name.isBlank()) {
            throw new IllegalArgumentException("emitter name is blank");
        }
        if (!Float.isFinite(offsetX) || !Float.isFinite(offsetY) || !Float.isFinite(offsetZ)) {
            throw new IllegalArgumentException(name + ": offset must be finite");
        }
        if (!(delay >= 0f) || !Float.isFinite(delay) || !(interval >= 0f) || !Float.isFinite(interval)) {
            throw new IllegalArgumentException(name + ": delay and interval must be finite and >= 0");
        }
        if (!(duration >= 0f)) {
            throw new IllegalArgumentException(name + ": duration must be >= 0 (or FOREVER)");
        }
        if (loops < 0 || burst < 0) {
            throw new IllegalArgumentException(name + ": loops and burst must be >= 0");
        }
        if (loops == 0 && duration + interval <= 0f) {
            throw new IllegalArgumentException(name + ": an endlessly looping emitter needs duration + interval > 0");
        }
        for (float v : new float[]{rateScale, sizeScale, speedScale}) {
            if (!(v >= 0f) || !Float.isFinite(v)) {
                throw new IllegalArgumentException(name + ": scales must be finite and >= 0");
            }
        }
        if (!Float.isNaN(dirX) && (!Float.isFinite(dirX) || !Float.isFinite(dirY) || !Float.isFinite(dirZ))) {
            throw new IllegalArgumentException(name + ": direction must be finite (or NaN = the effect's)");
        }
    }

    /** True when this emitter emits until stopped. */
    public boolean endless() {
        return duration == FOREVER || loops == 0;
    }

    /** True when {@link #dirX} overrides the effect's launch direction. */
    public boolean hasDirection() {
        return !Float.isNaN(dirX);
    }

    public static Builder builder(String name, ParticleEffect effect) {
        return new Builder(name, effect);
    }

    /**
     * The emitter that plays {@code effect} the way a plain {@link ParticleEmitter} or {@link ParticleSystem#burst}
     * would: an effect with a rate emits forever; a burst effect (rate 0) fires {@code burstCount} once.
     */
    public static FxEmitter of(String name, ParticleEffect effect, int burstCount) {
        Builder b = builder(name, effect);
        return effect.rate() > 0f ? b.build() : b.burst(burstCount).duration(0f).build();
    }

    public Builder toBuilder() {
        Builder b = new Builder(name, effect);
        b.offsetX = offsetX;
        b.offsetY = offsetY;
        b.offsetZ = offsetZ;
        b.delay = delay;
        b.duration = duration;
        b.loops = loops;
        b.interval = interval;
        b.burst = burst;
        b.rateScale = rateScale;
        b.sizeScale = sizeScale;
        b.speedScale = speedScale;
        b.tint = tint;
        b.dirX = dirX;
        b.dirY = dirY;
        b.dirZ = dirZ;
        return b;
    }

    /** Fluent builder; defaults: at the instance, no delay, forever, no burst, unscaled, untinted. */
    public static final class Builder {
        private String name;
        private ParticleEffect effect;
        private float offsetX, offsetY, offsetZ;
        private float delay;
        private float duration = FOREVER;
        private int loops = 1;
        private float interval;
        private int burst;
        private float rateScale = 1f, sizeScale = 1f, speedScale = 1f;
        private int tint = 0xFFFFFFFF;
        private float dirX = Float.NaN, dirY, dirZ;

        private Builder(String name, ParticleEffect effect) {
            this.name = name;
            this.effect = effect;
        }

        public Builder name(String v) { this.name = v; return this; }
        public Builder effect(ParticleEffect v) { this.effect = v; return this; }
        public Builder offset(float x, float y, float z) { offsetX = x; offsetY = y; offsetZ = z; return this; }
        public Builder delay(float seconds) { this.delay = seconds; return this; }
        public Builder duration(float seconds) { this.duration = seconds; return this; }
        public Builder forever() { this.duration = FOREVER; return this; }
        public Builder loops(int count) { this.loops = count; return this; }
        public Builder interval(float seconds) { this.interval = seconds; return this; }
        public Builder burst(int count) { this.burst = count; return this; }
        public Builder rateScale(float v) { this.rateScale = v; return this; }
        public Builder sizeScale(float v) { this.sizeScale = v; return this; }
        public Builder speedScale(float v) { this.speedScale = v; return this; }
        public Builder tint(int rgba) { this.tint = rgba; return this; }
        public Builder direction(float x, float y, float z) { dirX = x; dirY = y; dirZ = z; return this; }
        public Builder effectDirection() { dirX = Float.NaN; dirY = 0f; dirZ = 0f; return this; }
        /** Never emits by itself: only events spawn into it. */
        public Builder eventOnly() { burst = 0; rateScale = 0f; duration = 0f; loops = 1; return this; }

        public FxEmitter build() {
            return new FxEmitter(name, effect, offsetX, offsetY, offsetZ, delay, duration, loops, interval, burst,
                    rateScale, sizeScale, speedScale, tint, dirX, dirY, dirZ);
        }
    }
}
