package com.openmason.engine.rendering.particles;

import java.util.Arrays;

/**
 * The GL-free hand-off between a {@link ParticleSystem} and its GPU backend (OM #100). Everything here is plain
 * arrays filled on the simulation thread and drained by {@link ParticleGpuBackend} on the same thread (the render
 * thread in practice): no GL, no allocation per frame once warm.
 *
 * <ul>
 *   <li><b>Spawn requests</b> — a GPU effect's spawn becomes one record (count, world position, direction, tint,
 *       owner…); the backend uploads the frame's records and a compute kernel expands them. Overflow drops the
 *       spawn (counted).</li>
 *   <li><b>Liveness ledger</b> — the CPU never sees GPU particles die one by one, but it knows when each batch is
 *       certainly dead: its birth time plus the effect's longest lifetime plus the readback latency. Owners
 *       ({@link FxInstance} slots) are retired in bulk then, so FX instances finish without per-particle
 *       callbacks — exact enough for instance lifetime and safe for slot reuse (late events still find their
 *       instance).</li>
 *   <li><b>Event inbox</b> — death/impact events the GPU wrote, read back asynchronously (a few frames late) and
 *       budgeted; delivered to the owner listener at the start of the next update. They route FX handlers but do
 *       not count particles (the ledger does).</li>
 * </ul>
 */
final class ParticleGpuQueue {

    /** Spawn requests per frame. */
    static final int REQUEST_CAPACITY = 4096;
    /** Events held between readback and the next update. */
    static final int INBOX_CAPACITY = 8192;
    /** Seconds GPU events may lag behind the particle that caused them (async readback). */
    static final double READBACK_LATENCY = 0.25;

    // ---- spawn requests ----
    final short[] effect = new short[REQUEST_CAPACITY];
    final int[] count = new int[REQUEST_CAPACITY];
    final int[] tint = new int[REQUEST_CAPACITY];
    final int[] owner = new int[REQUEST_CAPACITY];
    final int[] seed = new int[REQUEST_CAPACITY];
    final double[] x = new double[REQUEST_CAPACITY], y = new double[REQUEST_CAPACITY], z = new double[REQUEST_CAPACITY];
    final float[] dx = new float[REQUEST_CAPACITY], dy = new float[REQUEST_CAPACITY], dz = new float[REQUEST_CAPACITY];
    final float[] preAge = new float[REQUEST_CAPACITY];
    final float[] ivx = new float[REQUEST_CAPACITY], ivy = new float[REQUEST_CAPACITY], ivz = new float[REQUEST_CAPACITY];
    final float[] sizeScale = new float[REQUEST_CAPACITY], speedScale = new float[REQUEST_CAPACITY];
    int requests;
    long requestsDroppedTotal;
    private int seedCounter = 0x2545F491;

    // ---- liveness ledger, by owner slot (owner >>> 8) ----
    private int[] pending = new int[64];
    private double[] expiry = new double[64];
    /** Backend frame that uploaded the owner's latest request ({@link Long#MAX_VALUE} = not uploaded yet). */
    private long[] uploaded = new long[64];
    /** A readback taken after the latest upload counted no live GPU particle of the owner: retire now. */
    private boolean[] drained = new boolean[64];
    private int[] active = new int[64];
    private int[] activeAt = new int[64];
    private int activeCount;

    // ---- event inbox ----
    static final int EVENT_DEATH = 0, EVENT_COLLISION = 1;
    final int[] evOwner = new int[INBOX_CAPACITY];
    final int[] evKind = new int[INBOX_CAPACITY];
    final float[] evX = new float[INBOX_CAPACITY], evY = new float[INBOX_CAPACITY], evZ = new float[INBOX_CAPACITY];
    final float[] evVx = new float[INBOX_CAPACITY], evVy = new float[INBOX_CAPACITY], evVz = new float[INBOX_CAPACITY];
    int events;
    long eventsDroppedTotal;

    // ---- simulation time the backend has not consumed yet ----
    float pendingDt;
    float windX, windY, windZ;
    /** Set by {@link ParticleSystem#clearParticles()}: the backend empties every pool before its next step. */
    boolean clearRequested;
    /** System updates since a backend last stepped (the CPU-fallback watchdog). */
    int idleUpdates;

    ParticleGpuQueue() {
        Arrays.fill(activeAt, -1);
    }

    /** Records a spawn; false when this frame's request table is full. */
    boolean request(CompiledEffect e, double wx, double wy, double wz, float ldx, float ldy, float ldz, int n,
                    int tintRgba, float pre, float ix, float iy, float iz, int ownerTag, float sScale, float vScale) {
        if (requests == REQUEST_CAPACITY) {
            requestsDroppedTotal++;
            return false;
        }
        int r = requests++;
        effect[r] = (short) e.id;
        count[r] = n;
        x[r] = wx;
        y[r] = wy;
        z[r] = wz;
        dx[r] = ldx;
        dy[r] = ldy;
        dz[r] = ldz;
        tint[r] = tintRgba;
        preAge[r] = pre;
        ivx[r] = ix;
        ivy[r] = iy;
        ivz[r] = iz;
        owner[r] = ownerTag;
        sizeScale[r] = sScale;
        speedScale[r] = vScale;
        seedCounter += 0x9E3779B9;
        seed[r] = seedCounter;
        return true;
    }

    // ------------------------------------------------------------------------------------------------
    // Ledger
    // ------------------------------------------------------------------------------------------------

    /** {@code n} particles of {@code ownerTag}'s instance are certainly gone by {@code until} (seconds). */
    void hold(int ownerTag, int n, double until) {
        int key = ownerTag >>> 8;
        if (key == 0 || n <= 0) {
            return;
        }
        if (key >= pending.length) {
            int size = Math.max(key + 1, pending.length * 2);
            pending = Arrays.copyOf(pending, size);
            expiry = Arrays.copyOf(expiry, size);
            uploaded = Arrays.copyOf(uploaded, size);
            drained = Arrays.copyOf(drained, size);
            int old = activeAt.length;
            activeAt = Arrays.copyOf(activeAt, size);
            Arrays.fill(activeAt, old, size, -1);
        }
        if (pending[key] == 0) {
            if (activeCount == active.length) {
                active = Arrays.copyOf(active, active.length * 2);
            }
            activeAt[key] = activeCount;
            active[activeCount++] = key;
            expiry[key] = until;
        } else {
            expiry[key] = Math.max(expiry[key], until);
        }
        pending[key] += n;
        uploaded[key] = Long.MAX_VALUE;
        drained[key] = false;
    }

    /** The backend uploaded a request of {@code ownerTag} in its frame {@code frame}. */
    void markUploaded(int ownerTag, long frame) {
        int key = ownerTag >>> 8;
        if (key > 0 && key < pending.length && pending[key] > 0) {
            uploaded[key] = frame;
        }
    }

    /**
     * Per-owner live counts the GPU kept (i32 per owner slot from {@code base}, {@code slots} of them), read back
     * from backend frame {@code frame}: owners whose every request was on the GPU by then and that had no live
     * particle (descendants included) retire at the next update instead of waiting out their longest lifetime.
     */
    void observeOwnerCounts(java.nio.ByteBuffer counts, int base, int slots, long frame) {
        for (int k = 0; k < activeCount; k++) {
            int key = active[k];
            if (key < slots && uploaded[key] <= frame && counts.getInt(base + key * 4) <= 0) {
                drained[key] = true;
            }
        }
    }

    /** Largest owner slot the ledger holds (the backend sizes its per-owner counters to it). */
    int maxOwnerSlot() {
        int max = 0;
        for (int k = 0; k < activeCount; k++) {
            max = Math.max(max, active[k]);
        }
        return max;
    }

    /** Retires every owner whose batches have all expired at {@code now}. */
    void retire(double now, ParticleSystem.OwnerListener listener) {
        for (int k = activeCount - 1; k >= 0; k--) {
            int key = active[k];
            if (expiry[key] > now && !drained[key]) {
                continue;
            }
            int n = pending[key];
            remove(key);
            if (listener != null) {
                listener.retired(key << 8, n);
            }
        }
    }

    private void remove(int key) {
        int at = activeAt[key];
        int last = active[--activeCount];
        active[at] = last;
        activeAt[last] = at;
        activeAt[key] = -1;
        pending[key] = 0;
        drained[key] = false;
    }

    /** Particles the ledger still holds (an upper bound on live GPU particles that have owners). */
    int held() {
        int n = 0;
        for (int k = 0; k < activeCount; k++) {
            n += pending[active[k]];
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------------
    // Inbox
    // ------------------------------------------------------------------------------------------------

    /** Called by the backend with a read-back event; false (counted) when the inbox is full. */
    boolean post(int ownerTag, int kind, float px, float py, float pz, float vx, float vy, float vz) {
        if (events == INBOX_CAPACITY) {
            eventsDroppedTotal++;
            return false;
        }
        int q = events++;
        evOwner[q] = ownerTag;
        evKind[q] = kind;
        evX[q] = px;
        evY[q] = py;
        evZ[q] = pz;
        evVx[q] = vx;
        evVy[q] = vy;
        evVz[q] = vz;
        return true;
    }

    /** Hands the inbox to {@code listener} (anchor-relative positions) and empties it. */
    void deliver(ParticleSystem.OwnerListener listener) {
        if (listener != null) {
            for (int q = 0; q < events; q++) {
                if (evKind[q] == EVENT_DEATH) {
                    listener.deathEvent(evOwner[q], evX[q], evY[q], evZ[q], evVx[q], evVy[q], evVz[q]);
                } else {
                    listener.collided(evOwner[q], evX[q], evY[q], evZ[q], evVx[q], evVy[q], evVz[q]);
                }
            }
        }
        events = 0;
    }

    /** Retires every held owner now (moving to the CPU: the replayed spawns count again). */
    void settle(ParticleSystem.OwnerListener listener) {
        for (int k = activeCount - 1; k >= 0; k--) {
            int key = active[k];
            int n = pending[key];
            remove(key);
            if (listener != null) {
                listener.retired(key << 8, n);
            }
        }
    }

    /** Forgets everything in flight (the system was cleared; the listener already zeroed its counts). */
    void clear() {
        requests = 0;
        events = 0;
        for (int k = 0; k < activeCount; k++) {
            int key = active[k];
            pending[key] = 0;
            activeAt[key] = -1;
        }
        activeCount = 0;
        clearRequested = true;
    }
}
