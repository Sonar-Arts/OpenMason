package com.openmason.engine.rendering.particles;

import java.util.Arrays;
import java.util.Objects;

/**
 * One playing {@link FxSystem}: a pooled handle from {@link FxWorld#play}. Move it, drive its exposed parameters,
 * raise custom events and stop it with a fade; it reports whether it is still alive (emitting, or particles of it
 * still flying). Every call is a field write or a short scan — nothing allocates.
 *
 * <h2>Lifetime</h2>
 * {@link FxWorld#play} hands out a <em>retained</em> instance: it stays valid (with {@link #isAlive()} false once
 * finished) until you {@link #release()} it. A released instance keeps playing to its natural end, then returns to
 * the pool and may be handed out again — do not touch it after releasing it. {@link FxWorld#fire} is play +
 * release. Simulation-thread only, like the {@link ParticleSystem}.
 */
public final class FxInstance {

    final FxWorld world;
    final int slot;
    CompiledFx fx;

    boolean inUse;
    boolean retained;
    boolean started;
    boolean stopping;
    boolean tracksDone;
    boolean stopCuesFired;
    float fadeTotal, fadeLeft;
    double x, y, z;
    double time;
    int live;
    int seed;

    // ---- parameters (4 floats each) ----
    float[] params = new float[0];
    boolean dirty;

    // ---- timeline, per emitter ----
    int[] cycle = new int[0];
    double[] cycleStart = new double[0];
    boolean[] active = new boolean[0];
    boolean[] done = new boolean[0];
    float[] acc = new float[0];

    // ---- resolved channels (base values × bindings), per emitter ----
    float[] ch = new float[0];
    int[] tintCh = new int[0];
    float[] dirCh = new float[0];
    float[] offCh = new float[0];
    float lightIntensity, lightR, lightG, lightB, volume;

    FxInstance(FxWorld world, int slot) {
        this.world = world;
        this.slot = slot;
    }

    /**
     * Prepares this pooled object for {@code compiled} at {@code (px, py, pz)}. Arrays grow only when too small, and
     * then to the world's largest system ({@code minEmitters}, {@code minParams}), so a slot that ran a small system
     * never reallocates for a bigger one later.
     */
    void assign(CompiledFx compiled, double px, double py, double pz, int seed, int minEmitters, int minParams) {
        fx = compiled;
        inUse = true;
        retained = true;
        started = false;
        stopping = false;
        tracksDone = false;
        stopCuesFired = false;
        fadeTotal = fadeLeft = 0f;
        x = px;
        y = py;
        z = pz;
        time = 0.0;
        live = 0;
        this.seed = seed;
        int n = compiled.emitters;
        if (cycle.length < n) {
            int cap = Math.max(n, minEmitters);
            cycle = new int[cap];
            cycleStart = new double[cap];
            active = new boolean[cap];
            done = new boolean[cap];
            acc = new float[cap];
            ch = new float[cap * CompiledFx.CHANNELS];
            tintCh = new int[cap];
            dirCh = new float[cap * 3];
            offCh = new float[cap * 3];
        }
        for (int e = 0; e < n; e++) {
            cycle[e] = 0;
            cycleStart[e] = 0.0;
            active[e] = false;
            done[e] = false;
            acc[e] = 0f;
        }
        int pn = compiled.paramDefaults.length;
        if (params.length < pn) {
            params = new float[Math.max(pn, minParams * 4)];
        }
        System.arraycopy(compiled.paramDefaults, 0, params, 0, pn);
        resolve();
    }

    // ------------------------------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------------------------------

    public FxSystem system() {
        check();
        return fx.source;
    }

    /** True while it emits or any of its particles is alive. */
    public boolean isAlive() {
        return inUse && (!tracksDone || live > 0);
    }

    /** True once {@link #stop} was called. */
    public boolean isStopping() {
        return stopping;
    }

    /** Seconds since it started (advances with {@link FxWorld#update}). */
    public double time() {
        return time;
    }

    /** Live particles spawned by it (including its particles' sub-emitter children). */
    public int liveParticles() {
        return live;
    }

    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }

    /** Moves the instance; emitters follow, particles already spawned stay where they are. */
    public FxInstance move(double px, double py, double pz) {
        check();
        x = px;
        y = py;
        z = pz;
        return this;
    }

    /** Index of parameter {@code name} for the fast setters, or -1. */
    public int paramIndex(String name) {
        check();
        return fx.paramIndex(name);
    }

    /** Sets a {@link FxParameter.Type#FLOAT} parameter. */
    public FxInstance setParam(String name, float value) {
        return setParam(index(name, FxParameter.Type.FLOAT), value);
    }

    public FxInstance setParam(int index, float value) {
        checkIndex(index, FxParameter.Type.FLOAT);
        return write(index, value, 0f, 0f, 0f);
    }

    /** Sets a {@link FxParameter.Type#VECTOR} parameter. */
    public FxInstance setParam(String name, float vx, float vy, float vz) {
        return setParam(index(name, FxParameter.Type.VECTOR), vx, vy, vz);
    }

    public FxInstance setParam(int index, float vx, float vy, float vz) {
        checkIndex(index, FxParameter.Type.VECTOR);
        return write(index, vx, vy, vz, 0f);
    }

    /** Sets a {@link FxParameter.Type#COLOR} parameter from {@code 0xRRGGBBAA}. */
    public FxInstance setParamColor(String name, int rgba) {
        int index = index(name, FxParameter.Type.COLOR);
        return write(index, (rgba >>> 24) / 255f, ((rgba >>> 16) & 0xFF) / 255f, ((rgba >>> 8) & 0xFF) / 255f,
                (rgba & 0xFF) / 255f);
    }

    /** The first component of parameter {@code name} (the value of a float parameter). */
    public float param(String name) {
        check();
        int i = fx.paramIndex(name);
        if (i < 0) {
            throw new IllegalArgumentException(fx.source.name() + " has no parameter '" + name + "'");
        }
        return params[i * 4];
    }

    /**
     * Raises the custom event {@code name}: its {@link FxEventHandler}s run on the next {@link FxWorld#update}, at
     * the instance (plus the target emitter's offset). Returns false when no handler listens to it.
     */
    public boolean raise(String name) {
        check();
        int c = fx.customIndex(name);
        if (c < 0) {
            return false;
        }
        world.raise(this, fx.customHandlers[c]);
        return true;
    }

    /**
     * Stops emitting: no new cycles start, continuous emission and the light fade to zero over {@code fadeSeconds}
     * (0 = at once). Particles already alive finish their lives. Calling it again can only shorten the fade.
     */
    public void stop(float fadeSeconds) {
        check();
        float fade = Math.max(0f, fadeSeconds);
        if (stopping && fade >= fadeLeft) {
            return;
        }
        stopping = true;
        fadeTotal = fade;
        fadeLeft = fade;
        if (fade <= 0f) {
            endTracks();
        }
    }

    /**
     * Switches this running instance to {@code next} (an editor changing the system live) without restarting it:
     * the clock, the position and every live particle stay. Emitters are matched by name - a kept emitter keeps
     * its timeline state (current cycle, accumulated emission) under its new settings, a new emitter joins as if it
     * had been there from the start (only the cycle due now fires, not every cycle it missed), and the particles
     * of a removed emitter finish their lives without firing its handlers. Parameters are matched by name and
     * type (values set on this instance are kept, others start at their defaults). Changed effects are the
     * particle system's business: replace them there ({@link ParticleSystem#replace}) before retargeting.
     */
    public FxInstance retarget(FxSystem next) {
        check();
        world.retarget(this, Objects.requireNonNull(next, "next"));
        return this;
    }

    /**
     * Gives the handle back: the instance plays on to its natural end, then returns to the pool. Stop it first if it
     * should end. Do not use this object afterwards.
     */
    public void release() {
        check();
        retained = false;
    }

    // ------------------------------------------------------------------------------------------------
    // Runtime (FxWorld)
    // ------------------------------------------------------------------------------------------------

    /**
     * Adopts {@code next} ({@link #retarget}): {@code map[old emitter] = new emitter or -1}; arrays grow to the
     * world's sizes like {@link #assign}.
     */
    void adopt(CompiledFx next, int[] map, int minEmitters, int minParams) {
        CompiledFx old = fx;
        int n = next.emitters;
        int[] oldCycle = Arrays.copyOf(cycle, old.emitters);
        double[] oldStart = Arrays.copyOf(cycleStart, old.emitters);
        boolean[] oldActive = Arrays.copyOf(active, old.emitters);
        boolean[] oldDone = Arrays.copyOf(done, old.emitters);
        float[] oldAcc = Arrays.copyOf(acc, old.emitters);
        float[] oldParams = Arrays.copyOf(params, old.paramDefaults.length);
        if (cycle.length < n) {
            int cap = Math.max(n, minEmitters);
            cycle = new int[cap];
            cycleStart = new double[cap];
            active = new boolean[cap];
            done = new boolean[cap];
            acc = new float[cap];
            ch = new float[cap * CompiledFx.CHANNELS];
            tintCh = new int[cap];
            dirCh = new float[cap * 3];
            offCh = new float[cap * 3];
        }
        for (int e = 0; e < n; e++) {
            cycle[e] = 0;
            cycleStart[e] = 0.0;
            active[e] = false;
            done[e] = false;
            acc[e] = 0f;
        }
        fx = next;
        for (int o = 0; o < map.length; o++) {
            int e = map[o];
            if (e >= 0) {
                cycle[e] = oldCycle[o];
                cycleStart[e] = oldStart[o];
                active[e] = oldActive[o];
                done[e] = oldDone[o];
                acc[e] = oldAcc[o];
            }
        }
        for (int e = 0; e < n; e++) {
            catchUp(e);
        }

        int pn = next.paramDefaults.length;
        if (params.length < pn) {
            params = new float[Math.max(pn, minParams * 4)];
        }
        System.arraycopy(next.paramDefaults, 0, params, 0, pn);
        for (int p = 0; p < next.paramNames.length; p++) {
            int q = old.paramIndex(next.paramNames[p]);
            if (q >= 0 && old.paramTypes[q] == next.paramTypes[p]) {
                System.arraycopy(oldParams, q * 4, params, p * 4, 4);
            }
        }
        if (!stopping) {
            tracksDone = allDone();
            if (!tracksDone) {
                stopCuesFired = false;
            }
        } else if (tracksDone) {
            endTracks();
        }
        resolve();
    }

    /**
     * Skips the cycles of emitter {@code e} that started and ended before now (a new emitter, or a timeline edit),
     * so the next frame fires at most the one cycle in progress instead of a burst for every cycle missed.
     */
    private void catchUp(int e) {
        if (done[e]) {
            return;
        }
        CompiledFx f = fx;
        double first = f.delay[e];
        if (time < first) {
            return; // nothing due yet
        }
        double period = (double) f.duration[e] + f.interval[e];
        long latest = period > 0.0 && !Double.isInfinite(period) ? (long) Math.floor((time - first) / period) : 0L;
        if (f.loops[e] != 0 && latest >= f.loops[e]) {
            latest = f.loops[e] - 1L;
            if (cycle[e] <= latest && first + latest * period + f.duration[e] <= time) {
                cycle[e] = f.loops[e]; // every cycle is over
                active[e] = false;
                done[e] = true;
                return;
            }
        }
        if (cycle[e] < latest) {
            cycle[e] = (int) Math.min(latest, Integer.MAX_VALUE - 1L); // the next frame starts cycle `latest`
            active[e] = false;
        }
    }

    /** Continuous emission and light multiplier: 1, ramping to 0 over a stop fade, 0 once stopped. */
    float emissionMul() {
        if (!stopping) {
            return 1f;
        }
        return fadeTotal > 0f ? Math.max(0f, fadeLeft / fadeTotal) : 0f;
    }

    /** Ends every emitter's timeline (stop finished). */
    void endTracks() {
        for (int e = 0; e < fx.emitters; e++) {
            active[e] = false;
            done[e] = true;
        }
        tracksDone = true;
    }

    /** True when every emitter's timeline is finished. */
    boolean allDone() {
        for (int e = 0; e < fx.emitters; e++) {
            if (!done[e]) {
                return false;
            }
        }
        return true;
    }

    /** Start time of emitter {@code e}'s next cycle, or +∞ when it has no more. */
    double nextCycleStart(int e) {
        CompiledFx f = fx;
        int k = cycle[e];
        if (f.loops[e] != 0 && k >= f.loops[e]) {
            return Double.POSITIVE_INFINITY;
        }
        if (k == 0) {
            return f.delay[e];
        }
        return f.delay[e] + (double) k * ((double) f.duration[e] + f.interval[e]);
    }

    /** Recomputes the per-emitter channels and system outputs from the base values and parameter bindings. */
    void resolve() {
        CompiledFx f = fx;
        int n = f.emitters;
        for (int e = 0; e < n; e++) {
            int o = e * CompiledFx.CHANNELS;
            ch[o + CompiledFx.CH_RATE] = f.rateScale[e];
            ch[o + CompiledFx.CH_BURST] = 1f;
            ch[o + CompiledFx.CH_SIZE] = f.sizeScale[e];
            ch[o + CompiledFx.CH_SPEED] = f.speedScale[e];
            tintCh[e] = f.tint[e];
            dirCh[e * 3] = f.dirX[e];
            dirCh[e * 3 + 1] = f.dirY[e];
            dirCh[e * 3 + 2] = f.dirZ[e];
            offCh[e * 3] = f.offX[e];
            offCh[e * 3 + 1] = f.offY[e];
            offCh[e * 3 + 2] = f.offZ[e];
        }
        FxLight light = f.light;
        if (light != null) {
            lightIntensity = light.intensity();
            lightR = (light.color() >>> 24) / 255f;
            lightG = ((light.color() >>> 16) & 0xFF) / 255f;
            lightB = ((light.color() >>> 8) & 0xFF) / 255f;
        }
        volume = 1f;
        FxBinding.Target[] targets = FxWorld.TARGETS;
        for (int b = 0; b < f.bParam.length; b++) {
            int p = f.bParam[b] * 4;
            float px = params[p], py = params[p + 1], pz = params[p + 2], pw = params[p + 3];
            float k = f.bBias[b] + f.bScale[b] * px;
            int only = f.bEmitter[b];
            int from = only < 0 ? 0 : only, to = only < 0 ? n : only + 1;
            switch (targets[f.bTarget[b]]) {
                case RATE -> scaleChannel(from, to, CompiledFx.CH_RATE, k);
                case BURST -> scaleChannel(from, to, CompiledFx.CH_BURST, k);
                case SIZE -> scaleChannel(from, to, CompiledFx.CH_SIZE, k);
                case SPEED -> scaleChannel(from, to, CompiledFx.CH_SPEED, k);
                case TINT -> {
                    for (int e = from; e < to; e++) {
                        tintCh[e] = multiply(tintCh[e], px, py, pz, pw);
                    }
                }
                case DIRECTION -> {
                    float len = (float) Math.sqrt(px * px + py * py + pz * pz);
                    for (int e = from; e < to; e++) {
                        if (len < 1e-6f) {
                            dirCh[e * 3] = Float.NaN;
                        } else {
                            dirCh[e * 3] = px / len;
                            dirCh[e * 3 + 1] = py / len;
                            dirCh[e * 3 + 2] = pz / len;
                        }
                    }
                }
                case OFFSET -> {
                    float s = f.bScale[b];
                    for (int e = from; e < to; e++) {
                        offCh[e * 3] += px * s;
                        offCh[e * 3 + 1] += py * s;
                        offCh[e * 3 + 2] += pz * s;
                    }
                }
                case LIGHT_INTENSITY -> lightIntensity *= k;
                case LIGHT_COLOR -> {
                    lightR *= px;
                    lightG *= py;
                    lightB *= pz;
                }
                case VOLUME -> volume *= k;
            }
        }
        dirty = false;
    }

    private void scaleChannel(int from, int to, int channel, float k) {
        float v = Math.max(0f, k);
        for (int e = from; e < to; e++) {
            ch[e * CompiledFx.CHANNELS + channel] *= v;
        }
    }

    private static int multiply(int rgba, float r, float g, float b, float a) {
        int cr = Math.round((rgba >>> 24) * clamp01(r));
        int cg = Math.round(((rgba >>> 16) & 0xFF) * clamp01(g));
        int cb = Math.round(((rgba >>> 8) & 0xFF) * clamp01(b));
        int ca = Math.round((rgba & 0xFF) * clamp01(a));
        return (cr << 24) | (cg << 16) | (cb << 8) | ca;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    /** Smooth per-instance flicker multiplier in {@code [1 − depth, 1]}. */
    float flicker(float depth, float hz) {
        if (depth <= 0f || hz <= 0f) {
            return 1f;
        }
        double t = time * hz;
        long i = (long) Math.floor(t);
        float f = (float) (t - i);
        f = f * f * (3f - 2f * f);
        float a = hash(i), b = hash(i + 1);
        return 1f - depth * (a + (b - a) * f);
    }

    private float hash(long i) {
        long h = i * 0x9E3779B97F4A7C15L + seed * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h >>> 40) / (float) (1L << 24);
    }

    private FxInstance write(int index, float a, float b, float c, float d) {
        int o = index * 4;
        params[o] = a;
        params[o + 1] = b;
        params[o + 2] = c;
        params[o + 3] = d;
        dirty = true;
        return this;
    }

    private int index(String name, FxParameter.Type type) {
        check();
        int i = fx.paramIndex(name);
        if (i < 0) {
            throw new IllegalArgumentException(fx.source.name() + " has no parameter '" + name + "'");
        }
        checkIndex(i, type);
        return i;
    }

    private void checkIndex(int index, FxParameter.Type type) {
        check();
        if (index < 0 || index >= fx.paramTypes.length) {
            throw new IllegalArgumentException("parameter index out of range: " + index);
        }
        if (fx.paramTypes[index] != type) {
            throw new IllegalArgumentException("parameter '" + fx.paramNames[index] + "' is "
                    + fx.paramTypes[index] + ", not " + type);
        }
    }

    private void check() {
        if (!inUse) {
            throw new IllegalStateException("FX instance was released and returned to the pool");
        }
    }
}
