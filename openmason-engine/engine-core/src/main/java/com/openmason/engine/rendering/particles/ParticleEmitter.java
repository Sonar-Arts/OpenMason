package com.openmason.engine.rendering.particles;

/**
 * A continuous source of one effect's particles, owned by a {@link ParticleSystem} (create it with
 * {@link ParticleSystem#createEmitter}). It spawns {@code effect.rate × rateScale} particles per second with
 * a fractional accumulator, so low rates are exact over time and never round to zero. Mutators are cheap
 * field writes — move an emitter every frame freely. Not thread-safe; use it on the simulation thread.
 */
public final class ParticleEmitter {

    final ParticleSystem system;
    /** Rewritten by {@link ParticleSystem#replace} when the effect is hot-swapped. */
    CompiledEffect effect;

    double x, y, z;
    /** Launch direction override; {@code NaN} in X = use the effect's direction. */
    float dirX = Float.NaN, dirY, dirZ;
    float rateScale = 1f;
    int tint = 0xFFFFFFFF;
    boolean active = true;
    boolean removed;
    /** Seconds left before the emitter stops on its own; ≤ 0 = forever. */
    float remaining;
    boolean removeWhenStopped;
    float accumulator;

    ParticleEmitter(ParticleSystem system, CompiledEffect effect, double x, double y, double z) {
        this.system = system;
        this.effect = effect;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public ParticleEffect effect() {
        return effect.source;
    }

    public ParticleEmitter setPosition(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }

    /** Overrides the effect's launch direction (need not be normalized). */
    public ParticleEmitter setDirection(float x, float y, float z) {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-6f) {
            dirX = Float.NaN;
        } else {
            dirX = x / len;
            dirY = y / len;
            dirZ = z / len;
        }
        return this;
    }

    /** Back to the effect's own launch direction. */
    public ParticleEmitter clearDirection() {
        dirX = Float.NaN;
        return this;
    }

    /** Multiplies the effect's rate (0 pauses emission without stopping). */
    public ParticleEmitter setRateScale(float scale) {
        if (!(scale >= 0f) || !Float.isFinite(scale)) {
            throw new IllegalArgumentException("rate scale must be finite and >= 0: " + scale);
        }
        this.rateScale = scale;
        return this;
    }

    /** Multiplies every spawned particle's colours by {@code 0xRRGGBBAA} (e.g. debris tinted by its block). */
    public ParticleEmitter setTint(int rgba) {
        this.tint = rgba;
        return this;
    }

    /** Stops emitting after {@code seconds}; {@code removeWhenDone} also detaches it from the system. */
    public ParticleEmitter setDuration(float seconds, boolean removeWhenDone) {
        this.remaining = seconds;
        this.removeWhenStopped = removeWhenDone;
        return this;
    }

    /** Pauses or resumes emission. A {@link #remove() removed} emitter stays removed. */
    public ParticleEmitter setActive(boolean active) {
        this.active = active;
        return this;
    }

    public boolean isActive() {
        return active && !removed;
    }

    /** Spawns {@code count} particles right now at the emitter (independent of the rate). */
    public int burst(int count) {
        return system.emitBurst(this, count);
    }

    /** Detaches the emitter; particles already alive keep living. */
    public void remove() {
        system.removeEmitter(this);
    }

    public boolean isRemoved() {
        return removed;
    }

    float launchX() { return Float.isNaN(dirX) ? effect.dirX : dirX; }
    float launchY() { return Float.isNaN(dirX) ? effect.dirY : dirY; }
    float launchZ() { return Float.isNaN(dirX) ? effect.dirZ : dirZ; }
}
