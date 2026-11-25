package com.openmason.engine.animgraph;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * The probes of one ground query and the hits the host writes back: a view over the native
 * {@code cr_ground_probe} / {@code cr_ground_hit} arrays (rig.h), valid only inside
 * {@link GroundQuery#query}. Reading and writing allocate nothing.
 *
 * <p>A probe is the segment {@code origin + t * direction}, {@code t} in {@code [0, maxDistance]}, in world space;
 * {@code direction} is unit length (straight down the character's up axis).
 */
public final class GroundProbes {

    /** Every address: probes are read by raw address so an upcall allocates no segment. */
    private static final MemorySegment ALL = MemorySegment.NULL.reinterpret(Long.MAX_VALUE);
    private static final ValueLayout.OfDouble F64 = ValueLayout.JAVA_DOUBLE;
    private static final long PROBE_BYTES = 64;
    private static final long HIT_BYTES = 48;

    private long probes;
    private long hits;
    private int count;

    GroundProbes() {
    }

    void bind(long probesAddress, long hitsAddress, int probeCount) {
        probes = probesAddress;
        hits = hitsAddress;
        count = probeCount;
    }

    public int count() {
        return count;
    }

    private long probe(int i) {
        return probes + Objects.checkIndex(i, count) * PROBE_BYTES;
    }

    private long hit(int i) {
        return hits + Objects.checkIndex(i, count) * HIT_BYTES;
    }

    public double originX(int i) {
        return ALL.get(F64, probe(i));
    }

    public double originY(int i) {
        return ALL.get(F64, probe(i) + 8);
    }

    public double originZ(int i) {
        return ALL.get(F64, probe(i) + 16);
    }

    public double directionX(int i) {
        return ALL.get(F64, probe(i) + 24);
    }

    public double directionY(int i) {
        return ALL.get(F64, probe(i) + 32);
    }

    public double directionZ(int i) {
        return ALL.get(F64, probe(i) + 40);
    }

    public double maxDistance(int i) {
        return ALL.get(F64, probe(i) + 48);
    }

    /** The leg the probe belongs to (the modifier's leg order). */
    public int foot(int i) {
        return ALL.get(ValueLayout.JAVA_INT, probe(i) + 56);
    }

    /** 0 = heel (the ankle), 1 = toe. */
    public int point(int i) {
        return ALL.get(ValueLayout.JAVA_INT, probe(i) + 60);
    }

    /** Probe {@code i} met the ground at (x, y, z) with that surface normal (need not be unit; zero = up). */
    public void hit(int i, double x, double y, double z, float nx, float ny, float nz, int surface) {
        long h = hit(i);
        ALL.set(ValueLayout.JAVA_INT, h, 1);
        ALL.set(ValueLayout.JAVA_INT, h + 4, surface);
        ALL.set(F64, h + 8, x);
        ALL.set(F64, h + 16, y);
        ALL.set(F64, h + 24, z);
        ALL.set(ValueLayout.JAVA_FLOAT, h + 32, nx);
        ALL.set(ValueLayout.JAVA_FLOAT, h + 36, ny);
        ALL.set(ValueLayout.JAVA_FLOAT, h + 40, nz);
    }

    /** Probe {@code i} met level ground at (x, y, z). */
    public void hit(int i, double x, double y, double z) {
        hit(i, x, y, z, 0f, 0f, 0f, 0);
    }

    /** Probe {@code i} met no ground (the default). */
    public void miss(int i) {
        ALL.set(ValueLayout.JAVA_INT, hit(i), 0);
    }
}
