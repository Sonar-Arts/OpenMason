package com.openmason.engine.rendering.particles;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Objects;

/**
 * Runs {@link FxSystem}s on one {@link ParticleSystem}: a pool of {@link FxInstance}s, their timelines, parameter
 * bindings, event routing, lights and sounds. All instances share the system's particle pool, budget and (in the
 * renderer) its single draw call; per-emitter budgets stay on the effects ({@code maxLive}, {@code priority},
 * {@code lodDistance}).
 *
 * <pre>{@code
 * FxWorld fx = new FxWorld(particles);
 * fx.setHost(myHost);                                  // forwarded events + sounds
 * FxInstance fire = fx.play(FxPresets.CAMPFIRE, x, y, z).setParam("intensity", 0.7f);
 * ...
 * fx.update(dt);                                       // instead of particles.update(dt)
 * fx.forEachLight(lights::add);
 * ...
 * fire.stop(1.5f); fire.release();
 * }</pre>
 *
 * <h2>Frame order</h2>
 * {@link #update} (1) starts the cycles due at the frame's start (bursts, {@code PLAY}/{@code EMITTER_START} cues),
 * (2) steps the particle system (which also runs its plain emitters), (3) handles the events that step produced and
 * the custom events raised since the last frame, (4) emits continuously over the frame, then retires finished
 * instances. A single-emitter system therefore draws random numbers in exactly the order a plain
 * {@link ParticleEmitter} or {@link ParticleSystem#burst} followed by updates does: the same seed gives the same
 * particles ({@link FxSystem#of}).
 *
 * <p>Zero heap allocation per frame once warm (instances, their arrays and the compiled tables are pooled), like
 * the particle system itself. One FxWorld per particle system; simulation thread only.
 */
public final class FxWorld implements AutoCloseable {

    /** Default instance budget. */
    public static final int DEFAULT_MAX_INSTANCES = 4096;
    /** Largest instance budget (owner tags keep the slot in 23 bits). */
    public static final int MAX_INSTANCES = 1 << 22;
    /** Events handled per frame; more are dropped and counted. */
    public static final int EVENT_CAPACITY = 4096;

    static final FxBinding.Target[] TARGETS = FxBinding.Target.values();

    private final ParticleSystem system;
    private final int maxInstances;
    private final ParticleSystem.OwnerListener listener = new Listener();
    private FxHost host = FxHost.NONE;

    private FxInstance[] slots = new FxInstance[16];
    private int slotCount;
    private int[] free = new int[16];
    private int freeCount;
    private int[] active = new int[16];
    private int activeCount;
    private final IdentityHashMap<FxSystem, CompiledFx> compiled = new IdentityHashMap<>();
    private final ParticleRandom random = new ParticleRandom(0xF0C0FFEEL);
    private int seedCounter;
    /** Largest emitter / parameter counts of any compiled system: pooled instances are sized to them. */
    private int maxEmitters, maxParams;

    // ---- event queue ----
    private final int[] qSlot = new int[EVENT_CAPACITY];
    private final int[] qHandler = new int[EVENT_CAPACITY];
    private final boolean[] qAtInstance = new boolean[EVENT_CAPACITY];
    private final double[] qX = new double[EVENT_CAPACITY], qY = new double[EVENT_CAPACITY], qZ = new double[EVENT_CAPACITY];
    private final float[] qVx = new float[EVENT_CAPACITY], qVy = new float[EVENT_CAPACITY], qVz = new float[EVENT_CAPACITY];
    private int qTail;

    // ---- stats ----
    private long eventsDroppedTotal;
    private long playsRefusedTotal;
    private long eventsHandledTotal;
    private boolean closed;

    public FxWorld(ParticleSystem system) {
        this(system, DEFAULT_MAX_INSTANCES);
    }

    public FxWorld(ParticleSystem system, int maxInstances) {
        this.system = Objects.requireNonNull(system, "system");
        if (maxInstances < 1 || maxInstances > MAX_INSTANCES) {
            throw new IllegalArgumentException("maxInstances must be in [1, " + MAX_INSTANCES + "]: " + maxInstances);
        }
        this.maxInstances = maxInstances;
        system.setOwnerListener(listener);
    }

    public ParticleSystem system() { return system; }

    public void setHost(FxHost host) {
        this.host = host == null ? FxHost.NONE : host;
    }

    /** Instances in use (playing, finished-but-retained, or released and finishing). */
    public int instanceCount() { return activeCount; }
    /** Pooled instance objects ever created (grows with peak concurrency, never shrinks). */
    int pooledInstances() { return slotCount; }
    /** Events dropped because a frame queued more than {@value #EVENT_CAPACITY}. */
    public long eventsDroppedTotal() { return eventsDroppedTotal; }
    /** Events handled since creation. */
    public long eventsHandledTotal() { return eventsHandledTotal; }
    /** {@link #play} calls refused by the instance budget. */
    public long playsRefusedTotal() { return playsRefusedTotal; }

    /** Compiles {@code fx} for this world ahead of its first play (keeps the first play allocation-free). */
    public void register(FxSystem fx) {
        compile(fx);
        for (ParticleEffect e : fx.emitters().stream().map(FxEmitter::effect).toList()) {
            system.register(e);
        }
    }

    private CompiledFx compile(FxSystem fx) {
        CompiledFx c = compiled.get(Objects.requireNonNull(fx, "fx"));
        if (c == null) {
            c = new CompiledFx(fx);
            compiled.put(fx, c);
            maxEmitters = Math.max(maxEmitters, c.emitters);
            maxParams = Math.max(maxParams, c.paramNames.length);
        }
        return c;
    }

    /**
     * Starts {@code fx} at world {@code (x, y, z)} and returns its retained handle (set parameters before the next
     * {@link #update}; nothing spawns until then). Null when the instance budget is full.
     */
    public FxInstance play(FxSystem fx, double x, double y, double z) {
        checkOpen();
        CompiledFx c = compile(fx);
        if (activeCount >= maxInstances) {
            playsRefusedTotal++;
            return null;
        }
        FxInstance inst;
        if (freeCount > 0) {
            inst = slots[free[--freeCount]];
        } else {
            if (slotCount == slots.length) {
                slots = Arrays.copyOf(slots, slots.length * 2);
                free = Arrays.copyOf(free, slots.length);
                active = Arrays.copyOf(active, slots.length);
            }
            inst = new FxInstance(this, slotCount);
            slots[slotCount++] = inst;
        }
        seedCounter += 0x9E3779B9;
        inst.assign(c, x, y, z, seedCounter, maxEmitters, maxParams);
        active[activeCount++] = inst.slot;
        return inst;
    }

    /** Fire and forget: play, then release (it returns to the pool when finished). False when refused. */
    public boolean fire(FxSystem fx, double x, double y, double z) {
        FxInstance inst = play(fx, x, y, z);
        if (inst == null) {
            return false;
        }
        inst.release();
        return true;
    }

    /** {@link FxInstance#retarget}: switch {@code inst} to {@code next}, keeping its clock and live particles. */
    void retarget(FxInstance inst, FxSystem next) {
        checkOpen();
        CompiledFx old = inst.fx;
        if (old.source == next) {
            return;
        }
        CompiledFx c = compile(next);
        int[] map = new int[old.emitters];
        for (int e = 0; e < old.emitters; e++) {
            map[e] = c.emitterIndex(old.source.emitters().get(e).name());
        }
        inst.adopt(c, map, maxEmitters, maxParams);
        system.retagOwners(inst.slot + 1, map);
        boolean stillUsed = false;
        for (int k = 0; k < activeCount && !stillUsed; k++) {
            stillUsed = slots[active[k]].fx == old;
        }
        if (!stillUsed) {
            compiled.remove(old.source); // an editor retargets on every edit: do not keep each version
        }
    }

    /** Stops every instance (see {@link FxInstance#stop}). */
    public void stopAll(float fadeSeconds) {
        for (int k = 0; k < activeCount; k++) {
            slots[active[k]].stop(fadeSeconds);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Frame
    // ------------------------------------------------------------------------------------------------

    /** Advances every instance and the particle system by {@code dt} seconds (clamped like the system's step). */
    public void update(float dt) {
        checkOpen();
        if (!(dt > 0f)) {
            return;
        }
        if (dt > ParticleSystem.MAX_STEP) {
            dt = ParticleSystem.MAX_STEP;
        }
        for (int k = 0; k < activeCount; k++) {
            startCycles(slots[active[k]]);
        }
        system.flushSubEvents();
        system.update(dt);
        handleEvents();
        for (int k = 0; k < activeCount; k++) {
            advance(slots[active[k]], dt);
        }
        system.flushSubEvents();
        // Backwards: retiring swaps the last active entry into this position.
        for (int k = activeCount - 1; k >= 0; k--) {
            FxInstance inst = slots[active[k]];
            if (!inst.retained && !inst.isAlive()) {
                inst.inUse = false;
                active[k] = active[--activeCount];
                free[freeCount++] = inst.slot;
            }
        }
    }

    /** Phase 1: cycles whose start time has been reached by the frame's start (bursts and start cues). */
    private void startCycles(FxInstance inst) {
        if (inst.dirty) {
            inst.resolve();
        }
        CompiledFx f = inst.fx;
        if (!inst.started) {
            inst.started = true;
            playCues(inst, f.playCues);
        }
        if (inst.stopping || inst.tracksDone) {
            return;
        }
        double t = inst.time;
        for (int e = 0; e < f.emitters; e++) {
            while (!inst.done[e]) {
                double start = inst.nextCycleStart(e);
                if (start > t) {
                    break;
                }
                inst.cycleStart[e] = start;
                inst.cycle[e]++;
                inst.active[e] = f.duration[e] > 0f;
                int n = Math.round(f.burst[e] * inst.ch[e * CompiledFx.CHANNELS + CompiledFx.CH_BURST]);
                if (n > 0) {
                    spawn(inst, e, inst.x + inst.offCh[e * 3], inst.y + inst.offCh[e * 3 + 1],
                            inst.z + inst.offCh[e * 3 + 2], n, 0f, 0f, 0f, 0f);
                }
                playCues(inst, f.startCues[e]);
                if (!inst.active[e] && f.loops[e] != 0 && inst.cycle[e] >= f.loops[e]) {
                    inst.done[e] = true;
                }
            }
        }
        finishIfDone(inst);
    }

    /** Phase 4: continuous emission over {@code (t, t + dt]}, cycle ends, the stop fade and the clock. */
    private void advance(FxInstance inst, float dt) {
        if (inst.dirty) {
            inst.resolve();
        }
        CompiledFx f = inst.fx;
        double t0 = inst.time, t1 = t0 + dt;
        if (!inst.tracksDone) {
            float mul = inst.emissionMul();
            for (int e = 0; e < f.emitters; e++) {
                if (!inst.active[e]) {
                    continue;
                }
                double start = inst.cycleStart[e];
                double end = start + f.duration[e];
                // The whole frame inside the cycle is exactly dt (keeps single-emitter systems bit-identical).
                float span = start <= t0 && end >= t1 ? dt : (float) (Math.min(end, t1) - Math.max(start, t0));
                if (span > 0f) {
                    float rate = inst.ch[e * CompiledFx.CHANNELS + CompiledFx.CH_RATE] * mul;
                    CompiledEffect c = system.compiled(f.effects[e]);
                    float spawnRate = system.emitterRate(c, (float) (t0 - start), dt);
                    inst.acc[e] += spawnRate * rate * span;
                    int n = (int) inst.acc[e];
                    if (n > 0) {
                        inst.acc[e] -= n;
                        spawn(inst, e, inst.x + inst.offCh[e * 3], inst.y + inst.offCh[e * 3 + 1],
                                inst.z + inst.offCh[e * 3 + 2], n, span, 0f, 0f, 0f);
                    }
                }
                if (end <= t1) {
                    inst.active[e] = false;
                    if (f.loops[e] != 0 && inst.cycle[e] >= f.loops[e]) {
                        inst.done[e] = true;
                    }
                }
            }
            if (inst.stopping) {
                inst.fadeLeft -= dt;
                if (inst.fadeLeft <= 0f) {
                    inst.fadeLeft = 0f;
                    inst.endTracks();
                }
            }
        }
        inst.time = t1;
        finishIfDone(inst);
    }

    private void finishIfDone(FxInstance inst) {
        if (!inst.tracksDone && inst.allDone()) {
            inst.tracksDone = true;
        }
        if (inst.tracksDone && !inst.stopCuesFired) {
            inst.stopCuesFired = true;
            playCues(inst, inst.fx.stopCues);
        }
    }

    private void playCues(FxInstance inst, int[] cues) {
        CompiledFx f = inst.fx;
        for (int c : cues) {
            host.sound(inst, f.cueSound[c], inst.x, inst.y, inst.z, f.cueVolume[c] * inst.volume);
        }
    }

    /** Spawns {@code count} particles of emitter {@code e} owned by {@code inst}. */
    private void spawn(FxInstance inst, int e, double x, double y, double z, int count, float preAge,
                       float ivx, float ivy, float ivz) {
        CompiledFx f = inst.fx;
        CompiledEffect c = system.compiled(f.effects[e]);
        float dx = inst.dirCh[e * 3], dy, dz;
        if (Float.isNaN(dx)) {
            dx = c.dirX;
            dy = c.dirY;
            dz = c.dirZ;
        } else {
            dy = inst.dirCh[e * 3 + 1];
            dz = inst.dirCh[e * 3 + 2];
        }
        int o = e * CompiledFx.CHANNELS;
        int owner = ((inst.slot + 1) << 8) | e;
        system.spawnOwned(c, x, y, z, dx, dy, dz, count, inst.tintCh[e], preAge, ivx, ivy, ivz, owner,
                inst.ch[o + CompiledFx.CH_SIZE], inst.ch[o + CompiledFx.CH_SPEED]);
    }

    // ------------------------------------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------------------------------------

    void raise(FxInstance inst, int[] handlers) {
        for (int h : handlers) {
            enqueue(inst, h, true, inst.x, inst.y, inst.z, 0f, 0f, 0f);
        }
    }

    private void enqueue(FxInstance inst, int handler, boolean atInstance, double x, double y, double z,
                         float vx, float vy, float vz) {
        float chance = inst.fx.hChance[handler];
        if (chance < 1f && random.nextFloat() >= chance) {
            return;
        }
        if (qTail == EVENT_CAPACITY) {
            eventsDroppedTotal++;
            return;
        }
        int q = qTail++;
        qSlot[q] = inst.slot;
        qHandler[q] = handler;
        qAtInstance[q] = atInstance;
        qX[q] = x;
        qY[q] = y;
        qZ[q] = z;
        qVx[q] = vx;
        qVy[q] = vy;
        qVz[q] = vz;
    }

    private void handleEvents() {
        int n = qTail;
        for (int q = 0; q < n; q++) {
            FxInstance inst = slots[qSlot[q]];
            if (!inst.inUse) {
                continue;
            }
            CompiledFx f = inst.fx;
            int h = qHandler[q];
            double x = qX[q], y = qY[q], z = qZ[q];
            int target = f.hSpawn[h];
            if (target >= 0 && f.hCount[h] > 0) {
                double sx = x, sy = y, sz = z;
                if (qAtInstance[q]) {
                    sx = inst.x + inst.offCh[target * 3];
                    sy = inst.y + inst.offCh[target * 3 + 1];
                    sz = inst.z + inst.offCh[target * 3 + 2];
                }
                float inherit = f.hInherit[h];
                spawn(inst, target, sx, sy, sz, f.hCount[h], 0f, qVx[q] * inherit, qVy[q] * inherit, qVz[q] * inherit);
            }
            if (f.hForward[h] != null) {
                host.event(inst, f.hForward[h], x, y, z);
            }
            if (f.hSound[h] != null) {
                host.sound(inst, f.hSound[h], x, y, z, inst.volume);
            }
        }
        eventsHandledTotal += n;
        qTail = 0;
        system.flushSubEvents();
    }

    /** Receives owned-particle notifications during the particle step; only counts and queues. */
    private final class Listener implements ParticleSystem.OwnerListener {
        @Override
        public void spawned(int owner, int count) {
            slots[(owner >>> 8) - 1].live += count;
        }

        @Override
        public void died(int owner, float x, float y, float z, float vx, float vy, float vz) {
            FxInstance inst = slots[(owner >>> 8) - 1];
            inst.live--;
            int e = owner & 0xFF;
            if (e != ParticleSystem.OWNER_DERIVED) {
                route(inst, inst.fx.deathHandlers[e], x, y, z, vx, vy, vz);
            }
        }

        @Override
        public void collided(int owner, float x, float y, float z, float vx, float vy, float vz) {
            int e = owner & 0xFF;
            if (e != ParticleSystem.OWNER_DERIVED) {
                FxInstance inst = slots[(owner >>> 8) - 1];
                route(inst, inst.fx.collisionHandlers[e], x, y, z, vx, vy, vz);
            }
        }

        @Override
        public void retired(int owner, int count) {
            FxInstance inst = slots[(owner >>> 8) - 1];
            inst.live = Math.max(0, inst.live - count);
        }

        @Override
        public void deathEvent(int owner, float x, float y, float z, float vx, float vy, float vz) {
            int e = owner & 0xFF;
            if (e != ParticleSystem.OWNER_DERIVED) {
                FxInstance inst = slots[(owner >>> 8) - 1];
                route(inst, inst.fx.deathHandlers[e], x, y, z, vx, vy, vz);
            }
        }

        @Override
        public void cleared() {
            for (int k = 0; k < activeCount; k++) {
                slots[active[k]].live = 0;
            }
            qTail = 0;
        }

        private void route(FxInstance inst, int[] handlers, float x, float y, float z, float vx, float vy, float vz) {
            if (handlers.length == 0) {
                return;
            }
            double wx = system.anchorX() + x, wy = system.anchorY() + y, wz = system.anchorZ() + z;
            for (int h : handlers) {
                enqueue(inst, h, false, wx, wy, wz, vx, vy, vz);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Lights
    // ------------------------------------------------------------------------------------------------

    /** Hands every lit instance's light to {@code sink} (skips lights at zero intensity). Allocation-free. */
    public void forEachLight(FxLightSink sink) {
        for (int k = 0; k < activeCount; k++) {
            FxInstance inst = slots[active[k]];
            FxLight light = inst.fx.light;
            if (light == null || !inst.isAlive()) {
                continue;
            }
            if (inst.dirty) {
                inst.resolve();
            }
            float intensity = inst.lightIntensity * light.curve(inst.time) * inst.emissionMul()
                    * inst.flicker(light.flicker(), light.flickerHz());
            if (intensity <= 0f) {
                continue;
            }
            sink.light(inst, inst.x + light.offsetX(), inst.y + light.offsetY(), inst.z + light.offsetZ(),
                    inst.lightR, inst.lightG, inst.lightB, intensity, light.radius());
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("FX world is closed");
        }
    }

    /** Detaches from the particle system (its particles keep living, untracked). */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        system.setOwnerListener(null);
        system.clearOwners();
        for (int k = 0; k < activeCount; k++) {
            slots[active[k]].inUse = false;
        }
        activeCount = 0;
    }
}
