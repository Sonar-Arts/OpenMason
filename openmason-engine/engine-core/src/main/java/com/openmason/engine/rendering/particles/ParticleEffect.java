package com.openmason.engine.rendering.particles;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable description of one kind of particle — how particles are emitted, how they move, how they look
 * over their life and what they spawn. Effects are pure data: a {@link ParticleSystem} compiles one into flat
 * tables (and colour/size lookup tables) the first time it is used, so the simulation never chases this
 * object per particle.
 *
 * <p>Units: distances in world blocks, times in seconds, angles in <b>degrees</b> (converted to radians once
 * at compile time), colours as {@code 0xRRGGBBAA} with straight (non-premultiplied) alpha.
 *
 * <h2>Look</h2>
 * Colour follows {@link #colorKeys} when present, otherwise a straight line from {@link #colorStart} to
 * {@link #colorEnd}. Alpha is then multiplied by a fade-in ramp over the first {@link #fadeIn} of life and a
 * fade-out ramp over the last {@link #fadeOut} — keep alpha in the end colour and let {@code fadeOut} do the
 * fading, or alpha drops twice. Size is {@code lerp(startSize, endSize, t) × sizeKeys(t)}.
 * {@link #additive} blends between alpha blending (0) and additive glow (1) — both share one draw call
 * through premultiplied alpha. Non-{@link #emissive} particles take the host's light ({@code particleLight}).
 * {@link #softness} > 0 fades the particle where it nears scene geometry (needs the host's depth texture).
 *
 * <h2>Budget</h2>
 * {@link #maxLive} caps this effect's live particles; {@link #priority} decides how much of the system's
 * budget it may use; {@link #lodDistance} thins spawning beyond that distance from the system's viewer.
 *
 * @param name          library key
 * @param lifetime      seconds a particle lives (uniform in the range)
 * @param rate          particles per second a continuous emitter spawns
 * @param shape         where particles appear relative to the emitter
 * @param radius        radius for {@link EmitterShape#SPHERE} / {@link EmitterShape#DISC}
 * @param halfX         box half-extent X ({@link EmitterShape#BOX})
 * @param halfY         box half-extent Y
 * @param halfZ         box half-extent Z
 * @param speed         initial speed, blocks/s
 * @param dirX          launch direction X (normalized at compile time; zero vector = up)
 * @param dirY          launch direction Y
 * @param dirZ          launch direction Z
 * @param spread        half-angle in degrees of the launch cone around the direction (180 = every direction)
 * @param gravityX      constant acceleration X, blocks/s²
 * @param gravityY      constant acceleration Y (negative falls)
 * @param gravityZ      constant acceleration Z
 * @param drag          exponential velocity damping per second ({@code v *= exp(-drag·dt)})
 * @param windInfluence how strongly the system's wind accelerates this effect (0 = immune)
 * @param startSize     world size (quad edge) at birth
 * @param endSize       world size at death
 * @param sizeKeys      size multiplier curve over life (empty = constant 1)
 * @param colorStart    {@code 0xRRGGBBAA} at birth (ignored when {@code colorKeys} is not empty)
 * @param colorEnd      {@code 0xRRGGBBAA} at death (ignored when {@code colorKeys} is not empty)
 * @param colorKeys     colour gradient over life (empty = start → end)
 * @param colorJitter   0..1 random per-particle brightness variation
 * @param fadeIn        fraction of life over which alpha ramps in
 * @param fadeOut       fraction of life over which alpha ramps out
 * @param additive      0 = alpha blended, 1 = additive glow
 * @param emissive      ignore the host's light (fire, sparks, magic)
 * @param softness      depth-fade distance in blocks against scene geometry (0 = hard edges)
 * @param orientation   how the quad faces: camera, stretched along velocity, upright, or flat on the ground
 * @param stretch       {@link Orientation#VELOCITY}: trail length = speed × stretch (seconds)
 * @param rotation      initial rotation in degrees
 * @param spin          angular velocity in degrees/s
 * @param sprite        procedural disc, square, or sprite-atlas frames
 * @param frameStart    first atlas frame ({@link Sprite#ATLAS})
 * @param frameCount    atlas frames available to the effect (≥ 1)
 * @param animateFrames true = play the frames over the particle's life; false = pick one at random
 * @param collides      test the system's {@link ParticleCollider} each step
 * @param bounce        velocity kept (reflected) on impact, 0..1
 * @param friction      horizontal velocity kept per ground contact, 0..1
 * @param subEmitters   effects spawned on birth, death, impact, or as a trail
 * @param maxLive       cap on this effect's live particles (0 = only the system budget)
 * @param priority      share of the system budget this effect may fill
 * @param lodDistance   full spawning within this distance of the viewer, thinning to none at twice it (0 = off)
 */
public record ParticleEffect(
        String name,
        Range lifetime,
        float rate,
        EmitterShape shape,
        float radius,
        float halfX, float halfY, float halfZ,
        Range speed,
        float dirX, float dirY, float dirZ,
        float spread,
        float gravityX, float gravityY, float gravityZ,
        float drag,
        float windInfluence,
        Range startSize,
        Range endSize,
        List<SizeKey> sizeKeys,
        int colorStart,
        int colorEnd,
        List<ColorKey> colorKeys,
        float colorJitter,
        float fadeIn,
        float fadeOut,
        float additive,
        boolean emissive,
        float softness,
        Orientation orientation,
        float stretch,
        Range rotation,
        Range spin,
        Sprite sprite,
        int frameStart,
        int frameCount,
        boolean animateFrames,
        boolean collides,
        float bounce,
        float friction,
        List<SubEmitter> subEmitters,
        int maxLive,
        Priority priority,
        float lodDistance) {

    /** Where particles appear relative to their emitter. */
    public enum EmitterShape { POINT, SPHERE, BOX, DISC }

    /** What a particle's quad shows. */
    public enum Sprite {
        /** Procedural round, soft-edged blob — needs no texture. */
        SOFT_DISC,
        /** Procedural solid square (debris, pixels). */
        SQUARE,
        /** Frames of the host's sprite atlas. */
        ATLAS
    }

    /** How a particle's quad is oriented. */
    public enum Orientation {
        /** Faces the camera, rotated by the particle's rotation (smoke, glows). */
        CAMERA,
        /** Long axis along the velocity, trail length speed × stretch (sparks, rain). */
        VELOCITY,
        /** Upright, turning only around world Y to face the camera (flames, grass bits). */
        AXIS_Y,
        /** Lying flat in the XZ plane, rotated by the particle's rotation (ripples, scorch marks). */
        FLAT
    }

    /** How much of a system's budget an effect may fill — ambient effects never starve gameplay ones. */
    public enum Priority {
        /** Weather, ambience: at most 60% of the budget. */
        AMBIENT(0.6f),
        /** Most effects: at most 90%. */
        NORMAL(0.9f),
        /** Gameplay feedback that must show: the whole budget. */
        CRITICAL(1f);

        public final float budgetFraction;

        Priority(float budgetFraction) {
            this.budgetFraction = budgetFraction;
        }
    }

    /** When a sub-emitter fires. */
    public enum Trigger {
        /** As the parent spawns. */
        BIRTH,
        /** When the parent dies of old age. */
        DEATH,
        /** When the parent hits something solid (faster than 1 block/s). */
        COLLISION,
        /** Continuously while the parent lives, at {@link SubEmitter#rate} per second. */
        TRAIL
    }

    /** Hard limit on atlas frames: frame codes ≥ this are reserved for the procedural sprites. */
    public static final int MAX_ATLAS_FRAMES = 0xFFF0;
    /** Most keys a gradient or curve may have. */
    public static final int MAX_KEYS = 8;
    /** Most sub-emitters an effect may have. */
    public static final int MAX_SUB_EMITTERS = 4;

    /** An inclusive float range sampled uniformly. */
    public record Range(float min, float max) {
        public Range {
            if (!Float.isFinite(min) || !Float.isFinite(max)) {
                throw new IllegalArgumentException("range must be finite: " + min + ".." + max);
            }
            if (max < min) {
                throw new IllegalArgumentException("range max < min: " + min + ".." + max);
            }
        }

        public static Range of(float value) {
            return new Range(value, value);
        }

        public static Range of(float min, float max) {
            return new Range(min, max);
        }

        public float span() {
            return max - min;
        }
    }

    /** A colour gradient key: life fraction {@code t} (0..1) → {@code 0xRRGGBBAA}. */
    public record ColorKey(float t, int rgba) {
        public ColorKey {
            if (!(t >= 0f && t <= 1f)) {
                throw new IllegalArgumentException("colour key t must be in [0, 1]: " + t);
            }
        }
    }

    /** A size curve key: life fraction {@code t} (0..1) → size multiplier (≥ 0). */
    public record SizeKey(float t, float scale) {
        public SizeKey {
            if (!(t >= 0f && t <= 1f)) {
                throw new IllegalArgumentException("size key t must be in [0, 1]: " + t);
            }
            if (!(scale >= 0f) || !Float.isFinite(scale)) {
                throw new IllegalArgumentException("size key scale must be finite and >= 0: " + scale);
            }
        }
    }

    /**
     * Spawns {@code count} particles of {@code effect} at the parent's position when {@link #trigger} fires
     * ({@link Trigger#TRAIL}: {@link #rate} per second instead), launched in the child effect's own cone plus
     * {@code inheritVelocity ×} the parent's velocity.
     */
    public record SubEmitter(ParticleEffect effect, Trigger trigger, int count, float rate, float inheritVelocity) {
        public SubEmitter {
            Objects.requireNonNull(effect, "sub-emitter effect");
            Objects.requireNonNull(trigger, "sub-emitter trigger");
            if (trigger == Trigger.TRAIL ? !(rate > 0f && Float.isFinite(rate)) : count < 1) {
                throw new IllegalArgumentException("sub-emitter needs " + (trigger == Trigger.TRAIL
                        ? "a positive rate" : "count >= 1"));
            }
            if (!Float.isFinite(inheritVelocity)) {
                throw new IllegalArgumentException("inheritVelocity must be finite");
            }
        }

        public static SubEmitter on(Trigger trigger, ParticleEffect effect, int count) {
            return new SubEmitter(effect, trigger, count, 0f, 0f);
        }

        public static SubEmitter trail(ParticleEffect effect, float perSecond) {
            return new SubEmitter(effect, Trigger.TRAIL, 1, perSecond, 0f);
        }

        public SubEmitter inheriting(float fraction) {
            return new SubEmitter(effect, trigger, count, rate, fraction);
        }
    }

    public ParticleEffect {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(lifetime, "lifetime");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(speed, "speed");
        Objects.requireNonNull(startSize, "startSize");
        Objects.requireNonNull(endSize, "endSize");
        Objects.requireNonNull(rotation, "rotation");
        Objects.requireNonNull(spin, "spin");
        Objects.requireNonNull(sprite, "sprite");
        Objects.requireNonNull(orientation, "orientation");
        Objects.requireNonNull(priority, "priority");
        sizeKeys = List.copyOf(sizeKeys == null ? List.of() : sizeKeys);
        colorKeys = List.copyOf(colorKeys == null ? List.of() : colorKeys);
        subEmitters = List.copyOf(subEmitters == null ? List.of() : subEmitters);
        if (name.isBlank()) {
            throw new IllegalArgumentException("effect name is blank");
        }
        if (lifetime.min() <= 0f) {
            throw new IllegalArgumentException(name + ": lifetime must be > 0");
        }
        if (rate < 0f || !Float.isFinite(rate)) {
            throw new IllegalArgumentException(name + ": rate must be finite and >= 0");
        }
        if (radius < 0f || halfX < 0f || halfY < 0f || halfZ < 0f) {
            throw new IllegalArgumentException(name + ": shape extents must be >= 0");
        }
        if (startSize.min() < 0f || endSize.min() < 0f) {
            throw new IllegalArgumentException(name + ": sizes must be >= 0");
        }
        if (spread < 0f || spread > 180f) {
            throw new IllegalArgumentException(name + ": spread must be in [0, 180] degrees");
        }
        if (drag < 0f) {
            throw new IllegalArgumentException(name + ": drag must be >= 0");
        }
        if (!(softness >= 0f) || !(stretch >= 0f) || !(lodDistance >= 0f)) {
            throw new IllegalArgumentException(name + ": softness, stretch and lodDistance must be >= 0");
        }
        if (maxLive < 0) {
            throw new IllegalArgumentException(name + ": maxLive must be >= 0");
        }
        checkUnit(name, "colorJitter", colorJitter);
        checkUnit(name, "fadeIn", fadeIn);
        checkUnit(name, "fadeOut", fadeOut);
        checkUnit(name, "additive", additive);
        checkUnit(name, "bounce", bounce);
        checkUnit(name, "friction", friction);
        if (frameStart < 0 || frameCount < 1 || (long) frameStart + frameCount > MAX_ATLAS_FRAMES) {
            throw new IllegalArgumentException(name + ": atlas frames out of range: "
                    + frameStart + " + " + frameCount);
        }
        List<ColorKey> ck = colorKeys;
        List<SizeKey> sk = sizeKeys;
        checkKeys(name, "colorKeys", ck.size(), i -> ck.get(i).t());
        checkKeys(name, "sizeKeys", sk.size(), i -> sk.get(i).t());
        if (subEmitters.size() > MAX_SUB_EMITTERS) {
            throw new IllegalArgumentException(name + ": at most " + MAX_SUB_EMITTERS + " sub-emitters");
        }
    }

    private static void checkKeys(String name, String field, int size, java.util.function.IntToDoubleFunction t) {
        if (size > MAX_KEYS) {
            throw new IllegalArgumentException(name + ": at most " + MAX_KEYS + " " + field);
        }
        for (int i = 1; i < size; i++) {
            if (t.applyAsDouble(i) < t.applyAsDouble(i - 1)) {
                throw new IllegalArgumentException(name + ": " + field + " must be in ascending t order");
            }
        }
    }

    private static void checkUnit(String name, String field, float value) {
        if (!(value >= 0f && value <= 1f)) {
            throw new IllegalArgumentException(name + ": " + field + " must be in [0, 1], was " + value);
        }
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** A builder pre-filled with this effect's values. */
    public Builder toBuilder() {
        Builder b = new Builder(name);
        b.lifetime = lifetime;
        b.rate = rate;
        b.shape = shape;
        b.radius = radius;
        b.halfX = halfX;
        b.halfY = halfY;
        b.halfZ = halfZ;
        b.speed = speed;
        b.dirX = dirX;
        b.dirY = dirY;
        b.dirZ = dirZ;
        b.spread = spread;
        b.gravityX = gravityX;
        b.gravityY = gravityY;
        b.gravityZ = gravityZ;
        b.drag = drag;
        b.windInfluence = windInfluence;
        b.startSize = startSize;
        b.endSize = endSize;
        b.sizeKeys = new ArrayList<>(sizeKeys);
        b.colorStart = colorStart;
        b.colorEnd = colorEnd;
        b.colorKeys = new ArrayList<>(colorKeys);
        b.colorJitter = colorJitter;
        b.fadeIn = fadeIn;
        b.fadeOut = fadeOut;
        b.additive = additive;
        b.emissive = emissive;
        b.softness = softness;
        b.orientation = orientation;
        b.stretch = stretch;
        b.rotation = rotation;
        b.spin = spin;
        b.sprite = sprite;
        b.frameStart = frameStart;
        b.frameCount = frameCount;
        b.animateFrames = animateFrames;
        b.collides = collides;
        b.bounce = bounce;
        b.friction = friction;
        b.subEmitters = new ArrayList<>(subEmitters);
        b.maxLive = maxLive;
        b.priority = priority;
        b.lodDistance = lodDistance;
        return b;
    }

    /** Fluent builder; every field has a sensible default (a small white soft puff that rises). */
    public static final class Builder {
        private String name;
        private Range lifetime = Range.of(1f);
        private float rate = 10f;
        private EmitterShape shape = EmitterShape.POINT;
        private float radius;
        private float halfX, halfY, halfZ;
        private Range speed = Range.of(1f);
        private float dirX, dirY = 1f, dirZ;
        private float spread = 15f;
        private float gravityX, gravityY, gravityZ;
        private float drag;
        private float windInfluence;
        private Range startSize = Range.of(0.25f);
        private Range endSize = Range.of(0.25f);
        private List<SizeKey> sizeKeys = new ArrayList<>();
        private int colorStart = 0xFFFFFFFF;
        private int colorEnd = 0xFFFFFFFF;
        private List<ColorKey> colorKeys = new ArrayList<>();
        private float colorJitter;
        private float fadeIn;
        private float fadeOut = 0.25f;
        private float additive;
        private boolean emissive;
        private float softness;
        private Orientation orientation = Orientation.CAMERA;
        private float stretch;
        private Range rotation = Range.of(0f);
        private Range spin = Range.of(0f);
        private Sprite sprite = Sprite.SOFT_DISC;
        private int frameStart;
        private int frameCount = 1;
        private boolean animateFrames;
        private boolean collides;
        private float bounce = 0.3f;
        private float friction = 0.7f;
        private List<SubEmitter> subEmitters = new ArrayList<>();
        private int maxLive;
        private Priority priority = Priority.NORMAL;
        private float lodDistance;

        private Builder(String name) {
            this.name = name;
        }

        public Builder name(String v) { this.name = v; return this; }
        public Builder lifetime(float min, float max) { this.lifetime = Range.of(min, max); return this; }
        public Builder rate(float v) { this.rate = v; return this; }
        public Builder point() { this.shape = EmitterShape.POINT; return this; }
        public Builder sphere(float r) { this.shape = EmitterShape.SPHERE; this.radius = r; return this; }
        public Builder disc(float r) { this.shape = EmitterShape.DISC; this.radius = r; return this; }
        public Builder box(float hx, float hy, float hz) {
            this.shape = EmitterShape.BOX; this.halfX = hx; this.halfY = hy; this.halfZ = hz; return this;
        }
        public Builder shape(EmitterShape s, float r, float hx, float hy, float hz) {
            this.shape = s; this.radius = r; this.halfX = hx; this.halfY = hy; this.halfZ = hz; return this;
        }
        public Builder speed(float min, float max) { this.speed = Range.of(min, max); return this; }
        public Builder direction(float x, float y, float z) { dirX = x; dirY = y; dirZ = z; return this; }
        public Builder spread(float degrees) { this.spread = degrees; return this; }
        public Builder gravity(float x, float y, float z) { gravityX = x; gravityY = y; gravityZ = z; return this; }
        public Builder drag(float v) { this.drag = v; return this; }
        public Builder wind(float influence) { this.windInfluence = influence; return this; }
        public Builder startSize(float min, float max) { this.startSize = Range.of(min, max); return this; }
        public Builder endSize(float min, float max) { this.endSize = Range.of(min, max); return this; }
        public Builder size(float start, float end) {
            this.startSize = Range.of(start); this.endSize = Range.of(end); return this;
        }
        /** Adds a size-multiplier key, inserted in t order (after any key with the same t). */
        public Builder sizeKey(float t, float scale) {
            int at = 0;
            while (at < sizeKeys.size() && sizeKeys.get(at).t() <= t) at++;
            this.sizeKeys.add(at, new SizeKey(t, scale));
            return this;
        }
        public Builder sizeKeys(List<SizeKey> keys) { this.sizeKeys = new ArrayList<>(keys); return this; }
        public Builder color(int rgba) { this.colorStart = rgba; this.colorEnd = rgba; return this; }
        public Builder colors(int start, int end) { this.colorStart = start; this.colorEnd = end; return this; }
        /** Adds a gradient key, inserted in t order (after any key with the same t); keys override start/end. */
        public Builder colorKey(float t, int rgba) {
            int at = 0;
            while (at < colorKeys.size() && colorKeys.get(at).t() <= t) at++;
            this.colorKeys.add(at, new ColorKey(t, rgba));
            return this;
        }
        public Builder colorKeys(List<ColorKey> keys) { this.colorKeys = new ArrayList<>(keys); return this; }
        public Builder colorJitter(float v) { this.colorJitter = v; return this; }
        public Builder fade(float in, float out) { this.fadeIn = in; this.fadeOut = out; return this; }
        public Builder additive(float v) { this.additive = v; return this; }
        public Builder emissive(boolean v) { this.emissive = v; return this; }
        public Builder softness(float blocks) { this.softness = blocks; return this; }
        public Builder orientation(Orientation o) { this.orientation = o; return this; }
        /** Velocity-stretched quads with trail length speed × {@code seconds}. */
        public Builder stretched(float seconds) {
            this.orientation = Orientation.VELOCITY; this.stretch = seconds; return this;
        }
        public Builder stretch(float seconds) { this.stretch = seconds; return this; }
        public Builder rotation(float minDeg, float maxDeg) { this.rotation = Range.of(minDeg, maxDeg); return this; }
        public Builder spin(float minDegPerSec, float maxDegPerSec) {
            this.spin = Range.of(minDegPerSec, maxDegPerSec); return this;
        }
        public Builder sprite(Sprite s) { this.sprite = s; return this; }
        public Builder atlas(int start, int count, boolean animate) {
            this.sprite = Sprite.ATLAS; this.frameStart = start; this.frameCount = count;
            this.animateFrames = animate; return this;
        }
        public Builder collides(float bounce, float friction) {
            this.collides = true; this.bounce = bounce; this.friction = friction; return this;
        }
        public Builder collides(boolean v) { this.collides = v; return this; }
        public Builder subEmitter(SubEmitter s) { this.subEmitters.add(s); return this; }
        public Builder subEmitters(List<SubEmitter> s) { this.subEmitters = new ArrayList<>(s); return this; }
        public Builder maxLive(int v) { this.maxLive = v; return this; }
        public Builder priority(Priority p) { this.priority = p; return this; }
        public Builder lodDistance(float blocks) { this.lodDistance = blocks; return this; }

        public ParticleEffect build() {
            return new ParticleEffect(name, lifetime, rate, shape, radius, halfX, halfY, halfZ, speed,
                    dirX, dirY, dirZ, spread, gravityX, gravityY, gravityZ, drag, windInfluence,
                    startSize, endSize, sizeKeys, colorStart, colorEnd, colorKeys, colorJitter, fadeIn, fadeOut,
                    additive, emissive, softness, orientation, stretch, rotation, spin, sprite, frameStart,
                    frameCount, animateFrames, collides, bounce, friction, subEmitters, maxLive, priority,
                    lodDistance);
        }
    }
}
