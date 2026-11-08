package com.openmason.engine.rendering.particles;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Objects;

/**
 * GL-free particle simulation over one fixed-budget pool.
 *
 * <h2>Resource model</h2>
 * Every particle field lives in its own primitive array (structure of arrays). The arrays start small
 * ({@value #INITIAL_SLOTS} slots) and double on demand up to {@link #capacity()} — the <em>budget</em> — so a
 * system sized for a storm costs almost nothing while the sky is clear. Live particles are always packed in
 * {@code [0, liveCount)}: a dying particle is replaced by the last live one (swap-remove), so there is no free
 * list, no per-particle object and the update loop streams contiguous memory. Once the arrays have reached
 * the peak load, neither {@link #update} nor any spawn path allocates ({@code ParticleAllocationTest}).
 *
 * <p>Per particle: 12 floats + 1 int + 2 shorts = 56 bytes here, plus 20 bytes of sort/cull scratch in a
 * {@link ParticleBatchBuilder} and a usage-sized GPU ring in the {@link ParticleRenderer}.
 *
 * <h2>Rationing</h2>
 * When spawns compete for the budget: an effect's {@link ParticleEffect#priority() priority} caps how much of
 * the budget it may fill (ambient weather can never take the last 40%), {@link ParticleEffect#maxLive()} caps
 * one effect, and {@link ParticleEffect#lodDistance()} thins spawning far from the {@link #setViewer viewer}.
 * Refused spawns are counted ({@link #droppedTotal()}, {@link #lodSkippedTotal()}), never queued.
 *
 * <h2>Sub-emitters</h2>
 * Birth/death/impact/trail sub-emitters are recorded in a fixed {@value #EVENT_CAPACITY}-entry event queue
 * during the step and spawned after it, so a burst of deaths never allocates or re-enters the update loop.
 *
 * <h2>Precision</h2>
 * Positions are stored as floats relative to an <em>anchor</em> (double precision world point). Keep the
 * anchor near the camera with {@link #setAnchor} and particles keep sub-millimetre precision at any world
 * coordinate.
 *
 * <h2>Threading</h2>
 * Single-threaded: spawn, update and build batches on one thread (the render thread in practice).
 */
public final class ParticleSystem {

    /** Largest supported budget. */
    public static final int MAX_CAPACITY = 1 << 20;
    /** Largest dt one update integrates; longer hitches are clamped so particles don't tunnel. */
    public static final float MAX_STEP = 0.25f;
    /** Slots allocated up front; the arrays double from here as load demands. */
    public static final int INITIAL_SLOTS = 256;
    /** Sub-emitter events one step can queue; more are dropped and counted. */
    public static final int EVENT_CAPACITY = 8192;
    /** Collision sub-step length: no particle moves further than this between collider probes. */
    static final float COLLISION_STEP = 0.4f;
    /** Impacts slower than this don't fire {@code COLLISION} sub-emitters (resting particles). */
    static final float IMPACT_SPEED = 1f;

    private final int capacity;
    private int slots;
    private int live;

    // ---- particle state (structure of arrays, `slots` long) ----
    float[] px, py, pz;
    float[] vx, vy, vz;
    float[] age, invLife;
    float[] size0, size1;
    float[] rot, spin;
    /** Per-particle colour multiplier {@code 0xRRGGBBAA} (emitter tint × brightness jitter). */
    int[] tint;
    short[] effectId;
    /** Texture picked at birth by a non-animated textured effect with several textures (0 otherwise). */
    short[] frameOffset;
    /**
     * Who spawned the particle (0 = nobody: plain emitters and bursts). Opaque to the system except that a
     * sub-emitter child gets its parent's tag with the low byte set ({@link #OWNER_DERIVED}). The FX runtime
     * ({@link FxWorld}) tags its particles to route their deaths and impacts back to the instance.
     */
    int[] owner;

    /** Low byte of an owner tag; all ones = "spawned by an owned particle's sub-emitter", not by an FX emitter. */
    static final int OWNER_DERIVED = 0xFF;

    /** Notified about owned particles ({@code owner != 0}); set by the one {@link FxWorld} driving this system. */
    interface OwnerListener {
        void spawned(int owner, int count);

        /** An owned particle died of old age at anchor-relative {@code (x, y, z)} moving at {@code v}. */
        void died(int owner, float x, float y, float z, float vx, float vy, float vz);

        /** An owned particle hit something faster than the impact threshold. */
        void collided(int owner, float x, float y, float z, float vx, float vy, float vz);

        /** Every particle was removed at once ({@link #clearParticles()}). */
        void cleared();
    }

    private OwnerListener ownerListener;

    // ---- effects ----
    private CompiledEffect[] effects = new CompiledEffect[8];
    private int effectCount;
    private final IdentityHashMap<ParticleEffect, CompiledEffect> compiled = new IdentityHashMap<>();

    // ---- emitters ----
    private ParticleEmitter[] emitters = new ParticleEmitter[8];
    private int emitterCount;

    // ---- sub-emitter event queue ----
    private final short[] evEffect = new short[EVENT_CAPACITY];
    private final int[] evCount = new int[EVENT_CAPACITY];
    private final int[] evOwner = new int[EVENT_CAPACITY];
    /** Order key: the old per-particle loop's position of the event (2 × index, +1 for a survivor's events). */
    private final int[] evKey = new int[EVENT_CAPACITY];
    /** TRAIL events of the batch path: expected count, rounded when ordered ({@code NaN} = fixed count). */
    private final float[] evExpected = new float[EVENT_CAPACITY];
    /** The queue holds batch-path events that must be put in the old loop's order before flushing. */
    private boolean keyedEvents;
    private final int[] sortA = new int[EVENT_CAPACITY], sortB = new int[EVENT_CAPACITY];
    private final int[] sortCount = new int[1 << 11];
    private final int[] scratchI = new int[EVENT_CAPACITY];
    private final float[] scratchF = new float[EVENT_CAPACITY];
    private final float[] evX = new float[EVENT_CAPACITY], evY = new float[EVENT_CAPACITY], evZ = new float[EVENT_CAPACITY];
    private final float[] evVx = new float[EVENT_CAPACITY], evVy = new float[EVENT_CAPACITY], evVz = new float[EVENT_CAPACITY];
    private int evTail;

    // ---- environment ----
    private double anchorX, anchorY, anchorZ;
    private double viewerX, viewerY, viewerZ;
    private boolean hasViewer;
    private float windX, windY, windZ;
    private ParticleCollider collider = ParticleCollider.NONE;
    private ParticleSpriteLayers sprites = ParticleSpriteLayers.NONE;
    private final ParticleRandom random;
    /** Random numbers of FX graph programs (RANDOM ops): their own stream, so built-in draws stay where they were. */
    private final ParticleRandom programRandom;
    private final FxVm vm;
    /**
     * True = the old hand-written per-particle loop instead of the compiled programs. Kept as the reference the
     * CPU backend is checked against bit for bit (tests, benchmarks); custom modules are ignored in this mode.
     */
    boolean referenceIntegrator;
    private float stepDt;

    // ---- custom attribute columns (FX graphs), shared by name across effects ----
    private String[] customNames = new String[0];
    private float[][] custom = new float[0][];

    // ---- per-step grouping of particles by effect (batch path) ----
    private int[] order = new int[0];
    private int[] groupStart = new int[8];
    private int[] groupFill = new int[8];

    // ---- stats ----
    private long spawnedTotal, droppedTotal, lodSkippedTotal, eventsDroppedTotal;

    public ParticleSystem(int capacity) {
        this(capacity, 0x5EED_0F_FA57L);
    }

    public ParticleSystem(int capacity, long seed) {
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity must be in [1, " + MAX_CAPACITY + "]: " + capacity);
        }
        this.capacity = capacity;
        random = new ParticleRandom(seed);
        programRandom = new ParticleRandom(seed ^ 0x6A09E667F3BCC909L);
        vm = new FxVm(this, programRandom);
        allocate(Math.min(capacity, INITIAL_SLOTS));
    }

    private void allocate(int n) {
        slots = n;
        px = grow(px, n);
        py = grow(py, n);
        pz = grow(pz, n);
        vx = grow(vx, n);
        vy = grow(vy, n);
        vz = grow(vz, n);
        age = grow(age, n);
        invLife = grow(invLife, n);
        size0 = grow(size0, n);
        size1 = grow(size1, n);
        rot = grow(rot, n);
        spin = grow(spin, n);
        tint = tint == null ? new int[n] : Arrays.copyOf(tint, n);
        effectId = effectId == null ? new short[n] : Arrays.copyOf(effectId, n);
        frameOffset = frameOffset == null ? new short[n] : Arrays.copyOf(frameOffset, n);
        owner = owner == null ? new int[n] : Arrays.copyOf(owner, n);
        order = new int[n];
        for (int c = 0; c < custom.length; c++) {
            custom[c] = Arrays.copyOf(custom[c], n);
        }
    }

    /** System columns for custom attribute components (existing names are shared), allocated on first use. */
    int[] customColumns(String[] names) {
        int[] cols = new int[names.length];
        for (int k = 0; k < names.length; k++) {
            int found = -1;
            for (int c = 0; c < customNames.length; c++) {
                if (customNames[c].equals(names[k])) {
                    found = c;
                    break;
                }
            }
            if (found < 0) {
                found = customNames.length;
                customNames = Arrays.copyOf(customNames, found + 1);
                customNames[found] = names[k];
                custom = Arrays.copyOf(custom, found + 1);
                custom[found] = new float[slots];
            }
            cols[k] = found;
        }
        return cols;
    }

    float[] customColumn(int c) {
        return custom[c];
    }

    private static float[] grow(float[] a, int n) {
        return a == null ? new float[n] : Arrays.copyOf(a, n);
    }

    private void ensureSlots(int needed) {
        if (needed > slots) {
            allocate(Math.min(capacity, Math.max(needed, slots * 2)));
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Configuration and stats
    // ------------------------------------------------------------------------------------------------

    /** The budget: most particles this system will ever hold. */
    public int capacity() { return capacity; }
    /** Slots currently allocated (≤ capacity; grows with peak load). */
    public int allocatedSlots() { return slots; }
    public int liveCount() { return live; }
    public int emitterCount() { return emitterCount; }
    /** Particles ever spawned. */
    public long spawnedTotal() { return spawnedTotal; }
    /** Spawns refused by the budget (system capacity, priority share or an effect's maxLive). */
    public long droppedTotal() { return droppedTotal; }
    /** Spawns skipped by distance thinning ({@link ParticleEffect#lodDistance()}). */
    public long lodSkippedTotal() { return lodSkippedTotal; }
    /** Sub-emitter events that did not fit the per-step queue. */
    public long eventsDroppedTotal() { return eventsDroppedTotal; }
    /** Heap bytes held by particle state (diagnostics). */
    public long memoryBytes() { return (long) slots * (12 * 4 + 4 + 2 + 2 + 4 + 4 + 4L * custom.length); }

    /** Live particles of {@code effect} in this system. */
    public int liveCount(ParticleEffect effect) {
        CompiledEffect c = compiled.get(effect);
        return c == null ? 0 : c.live;
    }

    public double anchorX() { return anchorX; }
    public double anchorY() { return anchorY; }
    public double anchorZ() { return anchorZ; }

    /** Moves the precision anchor; live particles keep their world positions. O(live). */
    public void setAnchor(double x, double y, double z) {
        float dx = (float) (x - anchorX), dy = (float) (y - anchorY), dz = (float) (z - anchorZ);
        anchorX = x;
        anchorY = y;
        anchorZ = z;
        if (dx == 0f && dy == 0f && dz == 0f) {
            return;
        }
        for (int i = 0; i < live; i++) {
            px[i] -= dx;
            py[i] -= dy;
            pz[i] -= dz;
        }
    }

    /** Where distance thinning ({@link ParticleEffect#lodDistance()}) is measured from — usually the camera. */
    public void setViewer(double x, double y, double z) {
        viewerX = x;
        viewerY = y;
        viewerZ = z;
        hasViewer = true;
    }

    /** Turns distance thinning off. */
    public void clearViewer() {
        hasViewer = false;
    }

    /** Wind acceleration (blocks/s²) applied to each effect scaled by its {@code windInfluence}. */
    public void setWind(float x, float y, float z) {
        windX = x;
        windY = y;
        windZ = z;
    }

    public void setCollider(ParticleCollider collider) {
        this.collider = collider == null ? ParticleCollider.NONE : collider;
    }

    /** Reseeds the spawn randomness (deterministic replays and tests). */
    public void setSeed(long seed) {
        random.setSeed(seed);
        programRandom.setSeed(seed ^ 0x6A09E667F3BCC909L);
    }

    /** Kills every particle (emitters stay; no death sub-emitters fire). */
    public void clearParticles() {
        live = 0;
        evTail = 0;
        keyedEvents = false;
        for (int k = 0; k < effectCount; k++) {
            effects[k].live = 0;
        }
        if (ownerListener != null) {
            ownerListener.cleared();
        }
    }

    /**
     * Re-points the particles of one FX instance at a new emitter layout ({@link FxInstance#retarget}): every live
     * particle tagged {@code (slotTag << 8) | e} with {@code e < map.length} becomes {@code (slotTag << 8) | map[e]},
     * or a derived particle ({@link #OWNER_DERIVED}: still counted, fires no handlers) when {@code map[e] < 0}.
     */
    void retagOwners(int slotTag, int[] map) {
        for (int i = 0; i < live; i++) {
            int o = owner[i];
            if (o >>> 8 != slotTag) {
                continue;
            }
            int e = o & 0xFF;
            if (e == OWNER_DERIVED) {
                continue;
            }
            int to = e < map.length ? map[e] : -1;
            owner[i] = (slotTag << 8) | (to < 0 ? OWNER_DERIVED : to);
        }
    }

    /** Forgets every live particle's owner (the FX world that tagged them is gone). */
    void clearOwners() {
        Arrays.fill(owner, 0, live, 0);
    }

    void setOwnerListener(OwnerListener listener) {
        if (listener != null && ownerListener != null && ownerListener != listener) {
            throw new IllegalStateException("this particle system is already driven by another FxWorld");
        }
        ownerListener = listener;
    }

    /** Kills every particle and detaches every emitter. */
    public void clear() {
        clearParticles();
        for (int i = 0; i < emitterCount; i++) {
            emitters[i].removed = true;
            emitters[i] = null;
        }
        emitterCount = 0;
    }

    // ------------------------------------------------------------------------------------------------
    // Effects and emitters
    // ------------------------------------------------------------------------------------------------

    /** Compiles the effect (and its sub-emitter effects) for this system; spawning does it on first use. */
    public void register(ParticleEffect effect) {
        compile(effect);
    }

    CompiledEffect compile(ParticleEffect effect) {
        Objects.requireNonNull(effect, "effect");
        CompiledEffect c = compiled.get(effect);
        if (c != null) {
            return c;
        }
        // Children first: an effect's sub-emitter targets are compiled before it (records are acyclic).
        CompiledEffect[] targets = new CompiledEffect[effect.subEmitters().size()];
        for (int i = 0; i < targets.length; i++) {
            targets[i] = compile(effect.subEmitters().get(i).effect());
        }
        if (effectCount >= Short.MAX_VALUE) {
            throw new IllegalStateException("too many distinct effects in one particle system");
        }
        c = new CompiledEffect(effect, effectCount, targets, this);
        reserve(c);
        c.resolve(sprites);
        if (effectCount == effects.length) {
            effects = Arrays.copyOf(effects, effects.length * 2);
        }
        effects[effectCount++] = c;
        compiled.put(effect, c);
        return c;
    }

    /**
     * Hot-swaps {@code old} for {@code updated} (an editor changing an effect live): live particles of the old
     * effect keep flying under the new parameters, emitters and other effects' sub-emitters that spawned the
     * old one spawn the new one, and nothing restarts. Per-particle values rolled at birth (lifetime, sizes,
     * rotation, tint) keep their rolls. Returns false when {@code old} was never used by this system (then
     * nothing changes; the new effect compiles on first use as usual).
     */
    public boolean replace(ParticleEffect old, ParticleEffect updated) {
        Objects.requireNonNull(updated, "updated");
        CompiledEffect previous = compiled.get(old);
        if (previous == null || old == updated) {
            return false;
        }
        CompiledEffect[] targets = new CompiledEffect[updated.subEmitters().size()];
        for (int i = 0; i < targets.length; i++) {
            ParticleEffect child = updated.subEmitters().get(i).effect();
            targets[i] = child == old ? null : compile(child);
        }
        CompiledEffect c = new CompiledEffect(updated, previous.id, targets, this);
        reserve(c);
        c.resolve(sprites);
        for (int i = 0; i < targets.length; i++) {
            if (targets[i] == null) {
                targets[i] = c; // an effect spawning itself after the swap (records can't, editors can)
            }
        }
        c.live = previous.live;
        effects[previous.id] = c;
        compiled.remove(old);
        compiled.put(updated, c);
        for (int k = 0; k < effectCount; k++) {
            CompiledEffect[] subs = effects[k].subTarget;
            for (int s = 0; s < subs.length; s++) {
                if (subs[s] == previous) {
                    subs[s] = c;
                }
            }
        }
        for (int k = 0; k < emitterCount; k++) {
            if (emitters[k].effect == previous) {
                emitters[k].effect = c;
            }
        }
        return true;
    }

    /**
     * Where textured effects find their sprites: every compiled effect re-resolves its texture names to layers
     * now (no allocation), later ones at compile time. The renderer's batch builder calls this with
     * {@link ParticleFrame#sprites} when set, so a host using a {@link ParticleTextureArray} never has to.
     */
    public void setSprites(ParticleSpriteLayers layers) {
        ParticleSpriteLayers next = layers == null ? ParticleSpriteLayers.NONE : layers;
        if (next == sprites) {
            return;
        }
        sprites = next;
        for (int k = 0; k < effectCount; k++) {
            effects[k].resolve(next);
        }
    }

    public ParticleSpriteLayers sprites() {
        return sprites;
    }

    private void reserve(CompiledEffect c) {
        vm.reserve(c.spawnProgram);
        vm.reserve(c.updateProgram);
        vm.reserve(c.emitterProgram);
        // Called before the new effect is counted: room for it plus the prefix-sum sentinel.
        if (groupStart.length < effectCount + 2) {
            groupStart = Arrays.copyOf(groupStart, Math.max(groupStart.length * 2, effectCount + 2));
            groupFill = Arrays.copyOf(groupFill, groupStart.length);
        }
    }

    /** Particles/s an emitter of {@code c} spawns at {@code emitterAge}: the effect's rate, or its EMITTER_UPDATE program. */
    float emitterRate(CompiledEffect c, float emitterAge, float dt) {
        return c.emitterProgram == null ? c.rate : vm.runEmitter(c.emitterProgram, c, emitterAge, dt, windX, windY, windZ);
    }

    CompiledEffect effect(int id) {
        return effects[id];
    }

    int effectCount() {
        return effectCount;
    }

    public ParticleEmitter createEmitter(ParticleEffect effect, double x, double y, double z) {
        ParticleEmitter e = new ParticleEmitter(this, compile(effect), x, y, z);
        if (emitterCount == emitters.length) {
            emitters = Arrays.copyOf(emitters, emitters.length * 2);
        }
        emitters[emitterCount++] = e;
        return e;
    }

    void removeEmitter(ParticleEmitter e) {
        if (e.removed) {
            return;
        }
        e.removed = true;
        for (int i = 0; i < emitterCount; i++) {
            if (emitters[i] == e) {
                emitters[i] = emitters[--emitterCount];
                emitters[emitterCount] = null;
                return;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Spawning
    // ------------------------------------------------------------------------------------------------

    /** One-shot burst of {@code count} particles at a world position. Returns how many were spawned. */
    public int burst(ParticleEffect effect, double x, double y, double z, int count) {
        CompiledEffect c = compile(effect);
        int n = spawn(c, rel(x, anchorX), rel(y, anchorY), rel(z, anchorZ), c.dirX, c.dirY, c.dirZ,
                count, 0xFFFFFFFF, 0f, 0f, 0f, 0f, 0, 1f, 1f);
        flushEvents();
        return n;
    }

    /** Burst with a launch direction and a colour tint ({@code 0xRRGGBBAA}). */
    public int burst(ParticleEffect effect, double x, double y, double z,
                     float dirX, float dirY, float dirZ, int count, int tint) {
        CompiledEffect c = compile(effect);
        float len = (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        float dx = c.dirX, dy = c.dirY, dz = c.dirZ;
        if (len >= 1e-6f) {
            dx = dirX / len;
            dy = dirY / len;
            dz = dirZ / len;
        }
        int n = spawn(c, rel(x, anchorX), rel(y, anchorY), rel(z, anchorZ), dx, dy, dz, count, tint, 0f, 0f, 0f, 0f,
                0, 1f, 1f);
        flushEvents();
        return n;
    }

    /** Emitter path: world position, already-normalized direction, births spread over {@code preAge}. */
    int emit(ParticleEmitter em, int count, float preAge) {
        CompiledEffect e = em.effect;
        return spawn(e, rel(em.x, anchorX), rel(em.y, anchorY), rel(em.z, anchorZ),
                em.launchX(), em.launchY(), em.launchZ(), count, em.tint, preAge, 0f, 0f, 0f, 0, 1f, 1f);
    }

    /**
     * The FX runtime's spawn: world position, a normalized launch direction, births spread over {@code preAge},
     * inherited velocity, an owner tag and per-instance size/speed multipliers. Does not flush sub-emitter
     * events; call {@link #flushSubEvents()} after a batch.
     */
    int spawnOwned(CompiledEffect e, double x, double y, double z, float dx, float dy, float dz, int count,
                   int tintRgba, float preAge, float ivx, float ivy, float ivz, int ownerTag,
                   float sizeScale, float speedScale) {
        return spawn(e, rel(x, anchorX), rel(y, anchorY), rel(z, anchorZ), dx, dy, dz, count, tintRgba, preAge,
                ivx, ivy, ivz, ownerTag, sizeScale, speedScale);
    }

    void flushSubEvents() {
        flushEvents();
    }

    int emitBurst(ParticleEmitter em, int count) {
        int n = emit(em, count, 0f);
        flushEvents();
        return n;
    }

    private static float rel(double world, double anchor) {
        return (float) (world - anchor);
    }

    /**
     * Spawns up to {@code count} particles at anchor-relative {@code (ox, oy, oz)}, after distance thinning
     * and budget rationing. {@code preAge} > 0 spreads births uniformly over the last {@code preAge} seconds
     * (advancing each along its velocity), so continuous emission at a low frame rate gives an even stream.
     * {@code (ivx, ivy, ivz)} is added to every launch velocity (sub-emitter velocity inheritance).
     */
    private int spawn(CompiledEffect e, float ox, float oy, float oz, float dx, float dy, float dz,
                      int count, int tintRgba, float preAge, float ivx, float ivy, float ivz,
                      int ownerTag, float sizeScale, float speedScale) {
        if (count <= 0) {
            return 0;
        }
        ParticleRandom r = random;
        if (e.lodDistance > 0f && hasViewer) {
            double ddx = anchorX + ox - viewerX, ddy = anchorY + oy - viewerY, ddz = anchorZ + oz - viewerZ;
            float dist = (float) Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
            if (dist > e.lodDistance) {
                float keep = Math.max(0f, 2f - dist / e.lodDistance);
                float expected = count * keep;
                int thinned = (int) expected;
                if (r.nextFloat() < expected - thinned) {
                    thinned++;
                }
                lodSkippedTotal += count - thinned;
                count = thinned;
                if (count == 0) {
                    return 0;
                }
            }
        }
        int room = Math.min((int) (capacity * e.budgetFraction) - live, e.maxLive - e.live);
        int n = Math.max(0, Math.min(count, room));
        droppedTotal += count - n;
        if (n == 0) {
            return 0;
        }
        spawnedTotal += n;
        ensureSlots(live + n);
        e.live += n;
        if (ownerTag != 0 && ownerListener != null) {
            ownerListener.spawned(ownerTag, n);
        }

        // Basis around the launch direction: precomputed for the effect's own direction.
        float ux, uy, uz, wx, wy, wz;
        if (dx == e.dirX && dy == e.dirY && dz == e.dirZ) {
            ux = e.uX; uy = e.uY; uz = e.uZ; wx = e.vX; wy = e.vY; wz = e.vZ;
        } else {
            float hx, hy, hz;
            if (Math.abs(dx) < 0.9f) { hx = 1f; hy = 0f; hz = 0f; } else { hx = 0f; hy = 1f; hz = 0f; }
            ux = dy * hz - dz * hy; uy = dz * hx - dx * hz; uz = dx * hy - dy * hx;
            float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
            ux /= ul; uy /= ul; uz /= ul;
            wx = dy * uz - dz * uy; wy = dz * ux - dx * uz; wz = dx * uy - dy * ux;
        }
        float oneMinusCos = 1f - e.cosSpread;
        short id = (short) e.id;
        int first = live;

        for (int k = 0; k < n; k++) {
            int i = live++;
            // ---- position within the emitter shape ----
            float sx = 0f, sy = 0f, sz = 0f;
            switch (e.shape) {
                case POINT -> { }
                case SPHERE -> {
                    // Uniform in the ball: random direction × r·cbrt(u).
                    float cz = 2f * r.nextFloat() - 1f;
                    float phi = r.nextFloat() * (float) (2 * Math.PI);
                    float sr = (float) Math.sqrt(Math.max(0f, 1f - cz * cz));
                    float rad = e.radius * (float) Math.cbrt(r.nextFloat());
                    sx = (float) Math.cos(phi) * sr * rad;
                    sy = cz * rad;
                    sz = (float) Math.sin(phi) * sr * rad;
                }
                case BOX -> {
                    sx = (r.nextFloat() * 2f - 1f) * e.halfX;
                    sy = (r.nextFloat() * 2f - 1f) * e.halfY;
                    sz = (r.nextFloat() * 2f - 1f) * e.halfZ;
                }
                case DISC -> {
                    float phi = r.nextFloat() * (float) (2 * Math.PI);
                    float rad = e.radius * (float) Math.sqrt(r.nextFloat());
                    sx = (float) Math.cos(phi) * rad;
                    sz = (float) Math.sin(phi) * rad;
                }
            }
            // ---- launch velocity: uniform in the spread cone around the direction ----
            float cosT = 1f - r.nextFloat() * oneMinusCos;
            float sinT = (float) Math.sqrt(Math.max(0f, 1f - cosT * cosT));
            float phi = r.nextFloat() * (float) (2 * Math.PI);
            float cp = (float) Math.cos(phi) * sinT, sp = (float) Math.sin(phi) * sinT;
            float speed = r.range(e.speedMin, e.speedSpan) * speedScale;
            float lvx = (dx * cosT + ux * cp + wx * sp) * speed + ivx;
            float lvy = (dy * cosT + uy * cp + wy * sp) * speed + ivy;
            float lvz = (dz * cosT + uz * cp + wz * sp) * speed + ivz;

            float born = preAge > 0f ? r.nextFloat() * preAge : 0f;
            px[i] = ox + sx + lvx * born;
            py[i] = oy + sy + lvy * born;
            pz[i] = oz + sz + lvz * born;
            vx[i] = lvx;
            vy[i] = lvy;
            vz[i] = lvz;
            age[i] = born;
            invLife[i] = 1f / r.range(e.lifeMin, e.lifeSpan);
            size0[i] = r.range(e.startSizeMin, e.startSizeSpan) * sizeScale;
            size1[i] = r.range(e.endSizeMin, e.endSizeSpan) * sizeScale;
            rot[i] = r.range(e.rotMin, e.rotSpan);
            spin[i] = r.range(e.spinMin, e.spinSpan);
            tint[i] = e.colorJitter > 0f ? scaleRgb(tintRgba, 1f - e.colorJitter * r.nextFloat()) : tintRgba;
            effectId[i] = id;
            owner[i] = ownerTag;
            frameOffset[i] = (short) (e.textured && !e.animate && e.frameCount > 1 ? r.nextInt(e.frameCount) : 0);
            if (e.hasBirth) {
                queueSubs(e, CompiledEffect.TRIGGER_BIRTH, i, 0f, 0);
            }
        }
        float[] defaults = e.customDefaults;
        for (int k = 0; k < defaults.length; k++) {
            Arrays.fill(custom[e.customColumns[k]], first, first + n, defaults[k]);
        }
        if (e.spawnProgram != null) {
            vm.runParticles(e.spawnProgram, e, null, first, n, stepDt, windX, windY, windZ);
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------------
    // Sub-emitter events
    // ------------------------------------------------------------------------------------------------

    /** Queues every sub-emitter of {@code e} with {@code trigger} at particle {@code i}. */
    private void queueSubs(CompiledEffect e, int trigger, int i, float dt, int key) {
        int[] triggers = e.subTrigger;
        for (int s = 0; s < triggers.length; s++) {
            if (triggers[s] != trigger) {
                continue;
            }
            int count = e.subCount[s];
            if (trigger == CompiledEffect.TRIGGER_TRAIL) {
                float expected = e.subRate[s] * dt;
                count = (int) expected;
                if (random.nextFloat() < expected - count) {
                    count++;
                }
                if (count == 0) {
                    continue;
                }
            }
            if (evTail == EVENT_CAPACITY) {
                eventsDroppedTotal++;
                droppedTotal += count;
                continue;
            }
            int q = evTail++;
            float inherit = e.subInherit[s];
            evEffect[q] = (short) e.subTarget[s].id;
            evCount[q] = count;
            int o = owner[i];
            evOwner[q] = o == 0 ? 0 : o | OWNER_DERIVED;
            evKey[q] = key;
            evExpected[q] = Float.NaN;
            evX[q] = px[i];
            evY[q] = py[i];
            evZ[q] = pz[i];
            evVx[q] = vx[i] * inherit;
            evVy[q] = vy[i] * inherit;
            evVz[q] = vz[i] * inherit;
        }
    }

    /**
     * Batch path TRAIL events: queued with their expected count and rounded (one random draw each) only once the
     * queue is in the old loop's order, so the draws happen in the same sequence as the per-particle loop's.
     */
    void queueTrail(CompiledEffect e, int i, float dt) {
        int[] triggers = e.subTrigger;
        for (int s = 0; s < triggers.length; s++) {
            if (triggers[s] != CompiledEffect.TRIGGER_TRAIL) {
                continue;
            }
            float expected = e.subRate[s] * dt;
            if (evTail == EVENT_CAPACITY) {
                eventsDroppedTotal++;
                droppedTotal += (int) expected;
                continue;
            }
            int q = evTail++;
            float inherit = e.subInherit[s];
            evEffect[q] = (short) e.subTarget[s].id;
            evCount[q] = 0;
            int o = owner[i];
            evOwner[q] = o == 0 ? 0 : o | OWNER_DERIVED;
            evKey[q] = 2 * i + 1;
            evExpected[q] = expected;
            evX[q] = px[i];
            evY[q] = py[i];
            evZ[q] = pz[i];
            evVx[q] = vx[i] * inherit;
            evVy[q] = vy[i] * inherit;
            evVz[q] = vz[i] * inherit;
        }
    }

    /**
     * Puts the batch path's events in the order the per-particle loop produced them (stable radix sort on the key),
     * then rounds TRAIL counts in that order and drops the ones that round to zero.
     */
    private void orderKeyedEvents() {
        int n = evTail;
        boolean sorted = true;
        for (int q = 1; q < n && sorted; q++) {
            sorted = evKey[q - 1] <= evKey[q];
        }
        if (!sorted) {
            int[] src = sortA, dst = sortB;
            for (int q = 0; q < n; q++) {
                src[q] = q;
            }
            for (int shift = 0; shift < 22; shift += 11) {
                int[] count = sortCount;
                Arrays.fill(count, 0);
                for (int q = 0; q < n; q++) {
                    count[(evKey[src[q]] >>> shift) & 2047]++;
                }
                int sum = 0;
                for (int b = 0; b < count.length; b++) {
                    int c = count[b];
                    count[b] = sum;
                    sum += c;
                }
                for (int q = 0; q < n; q++) {
                    int e = src[q];
                    dst[count[(evKey[e] >>> shift) & 2047]++] = e;
                }
                int[] t = src;
                src = dst;
                dst = t;
            }
            permute(src, n);
        }
        int w = 0;
        for (int q = 0; q < n; q++) {
            float expected = evExpected[q];
            if (!Float.isNaN(expected)) {
                int count = (int) expected;
                if (random.nextFloat() < expected - count) {
                    count++;
                }
                if (count == 0) {
                    continue;
                }
                evCount[q] = count;
            }
            if (w != q) {
                evEffect[w] = evEffect[q];
                evCount[w] = evCount[q];
                evOwner[w] = evOwner[q];
                evKey[w] = evKey[q];
                evExpected[w] = evExpected[q];
                evX[w] = evX[q];
                evY[w] = evY[q];
                evZ[w] = evZ[q];
                evVx[w] = evVx[q];
                evVy[w] = evVy[q];
                evVz[w] = evVz[q];
            }
            w++;
        }
        evTail = w;
    }

    private void permute(int[] perm, int n) {
        int[] ti = scratchI;
        float[] tf = scratchF;
        for (int q = 0; q < n; q++) ti[q] = evEffect[perm[q]];
        for (int q = 0; q < n; q++) evEffect[q] = (short) ti[q];
        for (int q = 0; q < n; q++) ti[q] = evCount[perm[q]];
        System.arraycopy(ti, 0, evCount, 0, n);
        for (int q = 0; q < n; q++) ti[q] = evOwner[perm[q]];
        System.arraycopy(ti, 0, evOwner, 0, n);
        for (int q = 0; q < n; q++) ti[q] = evKey[perm[q]];
        System.arraycopy(ti, 0, evKey, 0, n);
        permute(evExpected, perm, n, tf);
        permute(evX, perm, n, tf);
        permute(evY, perm, n, tf);
        permute(evZ, perm, n, tf);
        permute(evVx, perm, n, tf);
        permute(evVy, perm, n, tf);
        permute(evVz, perm, n, tf);
    }

    private static void permute(float[] a, int[] perm, int n, float[] tmp) {
        for (int q = 0; q < n; q++) tmp[q] = a[perm[q]];
        System.arraycopy(tmp, 0, a, 0, n);
    }

    /** Spawns every queued sub-emitter event (including ones queued by those spawns). */
    private void flushEvents() {
        if (keyedEvents) {
            keyedEvents = false;
            orderKeyedEvents();
        }
        for (int q = 0; q < evTail; q++) {
            CompiledEffect target = effects[evEffect[q]];
            spawn(target, evX[q], evY[q], evZ[q], target.dirX, target.dirY, target.dirZ, evCount[q],
                    0xFFFFFFFF, 0f, evVx[q], evVy[q], evVz[q], evOwner[q], 1f, 1f);
        }
        evTail = 0;
    }

    // ------------------------------------------------------------------------------------------------
    // Simulation
    // ------------------------------------------------------------------------------------------------

    /** Advances emitters and particles by {@code dt} seconds (clamped to {@link #MAX_STEP}). */
    public void update(float dt) {
        if (!(dt > 0f)) {
            return;
        }
        if (dt > MAX_STEP) {
            dt = MAX_STEP;
        }
        stepDt = dt;
        if (referenceIntegrator) {
            for (int k = 0; k < effectCount; k++) {
                CompiledEffect c = effects[k];
                c.dragMul = c.drag == 0f ? 1f : (float) Math.exp(-c.drag * dt);
            }
            integrate(dt);
        } else {
            step(dt);
        }
        flushEvents();
        runEmitters(dt);
        flushEvents();
    }

    private void runEmitters(float dt) {
        // Backwards: an emitter that removes itself swaps in one already processed.
        for (int k = emitterCount - 1; k >= 0; k--) {
            ParticleEmitter em = emitters[k];
            if (!em.active) {
                continue;
            }
            float step = dt;
            boolean stops = false;
            if (em.remaining > 0f) {
                if (em.remaining <= dt) {
                    step = em.remaining;
                    stops = true;
                }
                em.remaining -= dt;
            }
            float rate = em.effect.emitterProgram == null ? em.effect.rate : emitterRate(em.effect, em.age, step);
            em.age += dt;
            em.accumulator += rate * em.rateScale * step;
            int n = (int) em.accumulator;
            if (n > 0) {
                em.accumulator -= n;
                emit(em, n, step);
            }
            if (stops) {
                em.active = false;
                em.remaining = 0f;
                if (em.removeWhenStopped) {
                    removeEmitter(em);
                }
            }
        }
    }

    /**
     * The CPU backend's step (OM #99): (1) the built-in {@code lifetime} module natively — ages, deaths with their
     * events, swap-removal exactly like the old loop; (2) survivors grouped by effect; (3) each effect's compiled
     * PARTICLE_UPDATE program over its group, one instruction across a chunk at a time. Events carry the old loop's
     * order key and are re-ordered before flushing, so the result matches {@link #integrate} bit for bit.
     */
    private void step(float dt) {
        final CompiledEffect[] effects = this.effects;
        int i = 0;
        while (i < live) {
            CompiledEffect e = effects[effectId[i]];
            float a = age[i] + dt;
            if (a * invLife[i] >= 1f) {
                if (e.hasDeath) {
                    queueSubs(e, CompiledEffect.TRIGGER_DEATH, i, 0f, 2 * i);
                }
                int o = owner[i];
                if (o != 0 && ownerListener != null) {
                    ownerListener.died(o, px[i], py[i], pz[i], vx[i], vy[i], vz[i]);
                }
                kill(i);
                continue;
            }
            age[i] = a;
            i++;
        }
        keyedEvents = true;
        int n = live;
        if (n == 0) {
            return;
        }
        int[] start = groupStart, fill = groupFill;
        Arrays.fill(start, 0, effectCount + 1, 0);
        for (int k = 0; k < n; k++) {
            start[effectId[k] + 1]++;
        }
        for (int k = 0; k < effectCount; k++) {
            start[k + 1] += start[k];
        }
        System.arraycopy(start, 0, fill, 0, effectCount);
        int[] ord = order;
        for (int k = 0; k < n; k++) {
            ord[fill[effectId[k]]++] = k;
        }
        for (int k = 0; k < effectCount; k++) {
            int count = start[k + 1] - start[k];
            if (count > 0) {
                CompiledEffect e = effects[k];
                vm.runParticles(e.updateProgram, e, ord, start[k], count, dt, windX, windY, windZ);
            }
        }
    }

    /** The built-in {@code move} module's COLLIDE: through the collider (if any) or freely, writing position and velocity. */
    void moveParticle(int i, CompiledEffect e, float nvx, float nvy, float nvz, float dt) {
        if (collider != ParticleCollider.NONE) {
            collide(i, e, age[i] * invLife[i], nvx, nvy, nvz, dt);
        } else {
            px[i] += nvx * dt;
            py[i] += nvy * dt;
            pz[i] += nvz * dt;
            vx[i] = nvx;
            vy[i] = nvy;
            vz[i] = nvz;
        }
    }

    /** The old hand-written per-particle loop (the reference for {@link #step}). */
    private void integrate(float dt) {
        final CompiledEffect[] effects = this.effects;
        final boolean anyCollider = collider != ParticleCollider.NONE;

        int i = 0;
        while (i < live) {
            CompiledEffect e = effects[effectId[i]];
            float a = age[i] + dt;
            if (a * invLife[i] >= 1f) {
                if (e.hasDeath) {
                    queueSubs(e, CompiledEffect.TRIGGER_DEATH, i, 0f, 0);
                }
                int o = owner[i];
                if (o != 0 && ownerListener != null) {
                    ownerListener.died(o, px[i], py[i], pz[i], vx[i], vy[i], vz[i]);
                }
                kill(i);
                continue; // the swapped-in particle is processed at the same index
            }
            age[i] = a;
            float m = e.dragMul;
            float nvx = (vx[i] + (e.gravityX + windX * e.wind) * dt) * m;
            float nvy = (vy[i] + (e.gravityY + windY * e.wind) * dt) * m;
            float nvz = (vz[i] + (e.gravityZ + windZ * e.wind) * dt) * m;
            if (e.collides && anyCollider) {
                collide(i, e, a * invLife[i], nvx, nvy, nvz, dt);
            } else {
                px[i] += nvx * dt;
                py[i] += nvy * dt;
                pz[i] += nvz * dt;
                vx[i] = nvx;
                vy[i] = nvy;
                vz[i] = nvz;
            }
            rot[i] += spin[i] * dt;
            if (e.hasTrail) {
                queueSubs(e, CompiledEffect.TRIGGER_TRAIL, i, dt, 0);
            }
            i++;
        }
    }

    /**
     * Moves particle {@code i} through the collider: sub-steps of at most {@value #COLLISION_STEP} blocks so
     * fast particles can't skip a thin wall, each axis resolved separately (slide along walls, rest on
     * floors), probing the particle's edge (half its current size) so it rests <em>on</em> a surface instead
     * of sinking into it.
     */
    private void collide(int i, CompiledEffect e, float t, float nvx, float nvy, float nvz, float dt) {
        float radius = 0.5f * (size0[i] + (size1[i] - size0[i]) * t) * e.sizeScale(t);
        float dx = nvx * dt, dy = nvy * dt, dz = nvz * dt;
        float longest = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        int steps = longest > COLLISION_STEP ? Math.min(16, (int) Math.ceil(longest / COLLISION_STEP)) : 1;
        float sx = dx / steps, sy = dy / steps, sz = dz / steps;
        float x = px[i], y = py[i], z = pz[i];
        boolean hitX = false, hitY = false, hitZ = false;
        float impact = 0f;
        for (int s = 0; s < steps; s++) {
            if (!hitX && sx != 0f) {
                float tx = x + sx;
                if (blocked(tx + Math.copySign(radius, sx), y, z)) {
                    hitX = true;
                    impact = Math.max(impact, Math.abs(nvx));
                    nvx = -nvx * e.bounce;
                } else {
                    x = tx;
                }
            }
            if (!hitY && sy != 0f) {
                float ty = y + sy;
                if (blocked(x, ty + Math.copySign(radius, sy), z)) {
                    hitY = true;
                    impact = Math.max(impact, Math.abs(nvy));
                    boolean landed = sy < 0f;
                    nvy = -nvy * e.bounce;
                    if (landed) {
                        // Snap onto the surface (block or partial-block top) instead of hovering a step above it.
                        y = Math.min(y, (float) (lastBlockedTop - anchorY) + radius + 1e-4f);
                        nvx *= e.friction;
                        nvz *= e.friction;
                        if (nvy < 0.5f) {
                            nvy = 0f; // settle instead of micro-bouncing
                        }
                    }
                } else {
                    y = ty;
                }
            }
            if (!hitZ && sz != 0f) {
                float tz = z + sz;
                if (blocked(x, y, tz + Math.copySign(radius, sz))) {
                    hitZ = true;
                    impact = Math.max(impact, Math.abs(nvz));
                    nvz = -nvz * e.bounce;
                } else {
                    z = tz;
                }
            }
        }
        px[i] = x;
        py[i] = y;
        pz[i] = z;
        vx[i] = nvx;
        vy[i] = nvy;
        vz[i] = nvz;
        if (impact > IMPACT_SPEED) {
            if (e.hasCollision) {
                queueSubs(e, CompiledEffect.TRIGGER_COLLISION, i, 0f, 2 * i + 1);
            }
            int o = owner[i];
            if (o != 0 && ownerListener != null) {
                ownerListener.collided(o, x, y, z, nvx, nvy, nvz);
            }
        }
    }

    /** World Y of the top of the slab the last {@link #blocked} hit. */
    private double lastBlockedTop;

    /** True when the anchor-relative point lies inside a cell's blocking slab. */
    private boolean blocked(float x, float y, float z) {
        double wy = anchorY + y;
        int iy = (int) Math.floor(wy);
        float h = collider.solidHeight((int) Math.floor(anchorX + x), iy, (int) Math.floor(anchorZ + z));
        if (h <= 0f || (h < 1f && wy - iy >= h)) {
            return false;
        }
        lastBlockedTop = iy + Math.min(h, 1f);
        return true;
    }

    private void kill(int i) {
        effects[effectId[i]].live--;
        int last = --live;
        if (i == last) {
            return;
        }
        px[i] = px[last];
        py[i] = py[last];
        pz[i] = pz[last];
        vx[i] = vx[last];
        vy[i] = vy[last];
        vz[i] = vz[last];
        age[i] = age[last];
        invLife[i] = invLife[last];
        size0[i] = size0[last];
        size1[i] = size1[last];
        rot[i] = rot[last];
        spin[i] = spin[last];
        tint[i] = tint[last];
        effectId[i] = effectId[last];
        frameOffset[i] = frameOffset[last];
        owner[i] = owner[last];
        for (float[] c : custom) {
            c[i] = c[last];
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Read access (tools, debug overlays, tests)
    // ------------------------------------------------------------------------------------------------

    /** World X of live particle {@code i} ({@code 0 <= i < liveCount()}). */
    public double worldX(int i) { return anchorX + px[checkLive(i)]; }
    public double worldY(int i) { return anchorY + py[checkLive(i)]; }
    public double worldZ(int i) { return anchorZ + pz[checkLive(i)]; }
    /** Life fraction 0..1 of live particle {@code i}. */
    public float lifeFraction(int i) { return age[checkLive(i)] * invLife[i]; }
    public ParticleEffect effectOf(int i) { return effects[effectId[checkLive(i)]].source; }
    /** Owner tag of live particle {@code i} (0 = not spawned by an FX instance). */
    int ownerOf(int i) { return owner[checkLive(i)]; }
    CompiledEffect compiled(ParticleEffect effect) { return compile(effect); }
    /** The compiled programs of {@code effect} in this system (diagnostics, tests). */
    public FxProgram updateProgram(ParticleEffect effect) { return compile(effect).updateProgram; }
    /** Value of custom attribute component {@code name} ("heat", "swirl.x") of live particle {@code i}. */
    public float customAttribute(int i, String name) {
        checkLive(i);
        for (int c = 0; c < customNames.length; c++) {
            if (customNames[c].equals(name)) {
                return custom[c][i];
            }
        }
        throw new IllegalArgumentException("no custom attribute '" + name + "'");
    }
    ParticleRandom random() { return random; }

    private int checkLive(int i) {
        if (i < 0 || i >= live) {
            throw new IndexOutOfBoundsException("particle " + i + " of " + live);
        }
        return i;
    }

    // ------------------------------------------------------------------------------------------------
    // Colour helpers (0xRRGGBBAA)
    // ------------------------------------------------------------------------------------------------

    static int scaleRgb(int c, float s) {
        int r = Math.round((c >>> 24) * s);
        int g = Math.round(((c >>> 16) & 0xFF) * s);
        int b = Math.round(((c >>> 8) & 0xFF) * s);
        return (r << 24) | (g << 16) | (b << 8) | (c & 0xFF);
    }
}
