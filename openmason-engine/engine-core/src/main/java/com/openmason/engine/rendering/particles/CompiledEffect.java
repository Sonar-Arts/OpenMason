package com.openmason.engine.rendering.particles;

import java.util.List;

/**
 * A {@link ParticleEffect} flattened into the plain fields the simulation and the batch builder read in
 * their inner loops: radians instead of degrees, spans instead of max, a normalized launch direction with a
 * precomputed orthonormal basis, colour and size keys as flat arrays (evaluated exactly at every key), the
 * sprite as its GPU frame code and the sub-emitters as parallel arrays. Built once per system per effect.
 * {@link #dragMul} (once per update) and {@link #live} (per spawn/death) are the only fields that change.
 */
final class CompiledEffect {

    /** GPU frame code for the procedural soft disc. */
    static final int FRAME_SOFT_DISC = 0xFFFF;
    /** GPU frame code for the procedural square. */
    static final int FRAME_SQUARE = 0xFFFE;
    /** GPU frame code for a texture the sprite layers do not hold (a magenta checker, so it is noticed). */
    static final int FRAME_MISSING = 0xFFFD;
    /** Frame codes at or above this are procedural; below it a code is a texture-array layer. */
    static final int FIRST_PROCEDURAL_FRAME = 0xFFF0;
    static final int TRIGGER_BIRTH = 0, TRIGGER_DEATH = 1, TRIGGER_COLLISION = 2, TRIGGER_TRAIL = 3;

    final ParticleEffect source;
    final int id;

    final float lifeMin, lifeSpan;
    final float rate;
    final ParticleEffect.EmitterShape shape;
    final float radius, halfX, halfY, halfZ;
    final float speedMin, speedSpan;
    final float dirX, dirY, dirZ;
    /** Orthonormal basis perpendicular to the direction (cone sampling). */
    final float uX, uY, uZ, vX, vY, vZ;
    final float cosSpread;
    final float gravityX, gravityY, gravityZ;
    final float drag, wind;
    final float startSizeMin, startSizeSpan, endSizeMin, endSizeSpan;
    /** Colour keys: life fraction (ascending) and straight RGBA 0..1 ({@code 4 ×}). Start/end = two keys. */
    final float[] colorT;
    final float[] colorV;
    /** Size multiplier keys; null = constant 1. */
    final float[] sizeT;
    final float[] sizeV;
    final float colorJitter;
    final float fadeIn, fadeOut, invFadeIn, invFadeOut;
    final float additive;
    final boolean emissive;
    final float softness;
    /** {@link ParticleEffect.Orientation#ordinal()}. */
    final int orientation;
    final float stretch;
    final float rotMin, rotSpan, spinMin, spinSpan;
    /**
     * GPU frame code per flipbook frame: {@link #FRAME_SOFT_DISC}/{@link #FRAME_SQUARE} (one entry) for the
     * procedural sprites, else each texture's array layer or {@link #FRAME_MISSING}. Rewritten in place by
     * {@link #resolve} when the sprite layers change.
     */
    final int[] frames;
    /** {@link ParticleEffect#textures} in frame order (empty for the procedural sprites). */
    final String[] textureNames;
    /** Number of {@link #frames} (≥ 1). */
    final int frameCount;
    final boolean textured, animate;
    final boolean collides;
    final float bounce, friction;

    // ---- sub-emitters (parallel arrays; resolved to compiled targets by the system) ----
    /** Rewritten in place by {@link ParticleSystem#replace} when a target is hot-swapped. */
    final CompiledEffect[] subTarget;
    final int[] subTrigger;
    final int[] subCount;
    final float[] subRate;
    final float[] subInherit;
    final boolean hasBirth, hasDeath, hasCollision, hasTrail;

    // ---- budget ----
    final int maxLive;
    final float budgetFraction;
    final float lodDistance;
    /** Live particles of this effect in the owning system. */
    int live;

    /** {@code exp(-drag·dt)} for the current update (reference integrator only). */
    float dragMul = 1f;

    // ---- FX graph programs (OM #99) ----
    /** Runs over newly spawned particles after the built-in initialize; null = no custom spawn modules. */
    final FxProgram spawnProgram;
    /** The PARTICLE_UPDATE stage (built-in modules + custom ones), run by the CPU backend every step. */
    final FxProgram updateProgram;
    /** Computes an emitter's spawn rate; null = the effect's rate. */
    final FxProgram emitterProgram;
    /** System column of each custom attribute component the programs use (program column BUILTIN_COLUMNS + k). */
    final int[] customColumns;
    final float[] customDefaults;

    CompiledEffect(ParticleEffect e, int id, CompiledEffect[] subTargets, ParticleSystem system) {
        this.source = e;
        this.id = id;
        lifeMin = e.lifetime().min();
        lifeSpan = e.lifetime().span();
        rate = e.rate();
        shape = e.shape();
        radius = e.radius();
        halfX = e.halfX();
        halfY = e.halfY();
        halfZ = e.halfZ();
        speedMin = e.speed().min();
        speedSpan = e.speed().span();

        float dx = e.dirX(), dy = e.dirY(), dz = e.dirZ();
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6f) {
            dx = 0f; dy = 1f; dz = 0f;
        } else {
            dx /= len; dy /= len; dz /= len;
        }
        dirX = dx; dirY = dy; dirZ = dz;
        // u = normalize(cross(dir, helper)), v = cross(dir, u), helper = the least-aligned axis.
        float hx, hy, hz;
        if (Math.abs(dx) < 0.9f) { hx = 1f; hy = 0f; hz = 0f; } else { hx = 0f; hy = 1f; hz = 0f; }
        float ux = dy * hz - dz * hy, uy = dz * hx - dx * hz, uz = dx * hy - dy * hx;
        float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul; uy /= ul; uz /= ul;
        uX = ux; uY = uy; uZ = uz;
        vX = dy * uz - dz * uy;
        vY = dz * ux - dx * uz;
        vZ = dx * uy - dy * ux;
        cosSpread = (float) Math.cos(Math.toRadians(e.spread()));

        gravityX = e.gravityX();
        gravityY = e.gravityY();
        gravityZ = e.gravityZ();
        drag = e.drag();
        wind = e.windInfluence();
        startSizeMin = e.startSize().min();
        startSizeSpan = e.startSize().span();
        endSizeMin = e.endSize().min();
        endSizeSpan = e.endSize().span();
        List<ParticleEffect.ColorKey> ck = e.colorKeys().isEmpty()
                ? List.of(new ParticleEffect.ColorKey(0f, e.colorStart()), new ParticleEffect.ColorKey(1f, e.colorEnd()))
                : e.colorKeys();
        colorT = new float[ck.size()];
        colorV = new float[ck.size() * 4];
        for (int k = 0; k < ck.size(); k++) {
            colorT[k] = ck.get(k).t();
            int c = ck.get(k).rgba();
            colorV[k * 4] = (c >>> 24) / 255f;
            colorV[k * 4 + 1] = ((c >>> 16) & 0xFF) / 255f;
            colorV[k * 4 + 2] = ((c >>> 8) & 0xFF) / 255f;
            colorV[k * 4 + 3] = (c & 0xFF) / 255f;
        }
        if (e.sizeKeys().isEmpty()) {
            sizeT = null;
            sizeV = null;
        } else {
            sizeT = new float[e.sizeKeys().size()];
            sizeV = new float[sizeT.length];
            for (int k = 0; k < sizeT.length; k++) {
                sizeT[k] = e.sizeKeys().get(k).t();
                sizeV[k] = e.sizeKeys().get(k).scale();
            }
        }
        colorJitter = e.colorJitter();
        fadeIn = e.fadeIn();
        fadeOut = e.fadeOut();
        invFadeIn = fadeIn > 0f ? 1f / fadeIn : 0f;
        invFadeOut = fadeOut > 0f ? 1f / fadeOut : 0f;
        additive = e.additive();
        emissive = e.emissive();
        softness = e.softness();
        orientation = e.orientation().ordinal();
        stretch = e.stretch();
        rotMin = (float) Math.toRadians(e.rotation().min());
        rotSpan = (float) Math.toRadians(e.rotation().span());
        spinMin = (float) Math.toRadians(e.spin().min());
        spinSpan = (float) Math.toRadians(e.spin().span());
        textured = e.sprite() == ParticleEffect.Sprite.TEXTURE;
        textureNames = textured ? e.textures().toArray(new String[0]) : new String[0];
        frames = switch (e.sprite()) {
            case SOFT_DISC -> new int[]{FRAME_SOFT_DISC};
            case SQUARE -> new int[]{FRAME_SQUARE};
            case TEXTURE -> new int[textureNames.length];
        };
        frameCount = frames.length;
        animate = textured && e.animateFrames();
        collides = e.collides();
        bounce = e.bounce();
        friction = e.friction();

        List<ParticleEffect.SubEmitter> subs = e.subEmitters();
        int n = subs.size();
        subTarget = subTargets;
        subTrigger = new int[n];
        subCount = new int[n];
        subRate = new float[n];
        subInherit = new float[n];
        boolean birth = false, death = false, collision = false, trail = false;
        for (int i = 0; i < n; i++) {
            ParticleEffect.SubEmitter s = subs.get(i);
            subTrigger[i] = switch (s.trigger()) {
                case BIRTH -> { birth = true; yield TRIGGER_BIRTH; }
                case DEATH -> { death = true; yield TRIGGER_DEATH; }
                case COLLISION -> { collision = true; yield TRIGGER_COLLISION; }
                case TRAIL -> { trail = true; yield TRIGGER_TRAIL; }
            };
            subCount[i] = s.count();
            subRate[i] = s.rate();
            subInherit[i] = s.inheritVelocity();
        }
        hasBirth = birth;
        hasDeath = death;
        hasCollision = collision;
        hasTrail = trail;

        FxCompiler.Result programs = FxCompiler.compile(FxCompiler.Input.of(e));
        spawnProgram = programs.spawn;
        updateProgram = programs.update;
        emitterProgram = programs.emitter;
        customColumns = system.customColumns(programs.customNames);
        customDefaults = programs.customDefaults;

        maxLive = e.maxLive() == 0 ? Integer.MAX_VALUE : e.maxLive();
        budgetFraction = e.priority().budgetFraction;
        lodDistance = e.lodDistance();
    }

    /** Maps the texture names to frame codes through {@code layers}; procedural sprites are left as they are. */
    void resolve(ParticleSpriteLayers layers) {
        for (int i = 0; i < textureNames.length; i++) {
            int layer = layers.layerOf(textureNames[i]);
            frames[i] = layer >= 0 && layer < FIRST_PROCEDURAL_FRAME ? layer : FRAME_MISSING;
        }
    }

    /**
     * Index of the key segment containing {@code t}: the last key with {@code keyT <= t} (0 before the first).
     * At most {@value ParticleEffect#MAX_KEYS} keys, so a forward scan beats anything cleverer.
     */
    static int segment(float[] keyT, float t) {
        int k = 0;
        int last = keyT.length - 1;
        while (k < last && keyT[k + 1] <= t) {
            k++;
        }
        return k;
    }

    /** Interpolation weight of {@code t} inside segment {@code k} (0 = key k, 1 = key k+1; held at the ends). */
    static float weight(float[] keyT, int k, float t) {
        if (k + 1 >= keyT.length) {
            return 0f;
        }
        float span = keyT[k + 1] - keyT[k];
        if (span <= 0f || t <= keyT[k]) {
            return 0f;
        }
        return Math.min(1f, (t - keyT[k]) / span);
    }

    /** Size multiplier at life fraction {@code t} (exact at every key). */
    float sizeScale(float t) {
        float[] keyT = sizeT;
        if (keyT == null) {
            return 1f;
        }
        int k = segment(keyT, t);
        float w = weight(keyT, k, t);
        return w == 0f ? sizeV[k] : sizeV[k] + (sizeV[k + 1] - sizeV[k]) * w;
    }

    /** Alpha multiplier of the fade-in/out ramps at life fraction {@code t}. */
    float fade(float t) {
        float f = 1f;
        if (t < fadeIn) {
            f = t * invFadeIn;
        }
        float out = 1f - t;
        if (out < fadeOut) {
            f = Math.min(f, out * invFadeOut);
        }
        return f;
    }
}
