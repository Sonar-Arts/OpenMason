package com.openmason.engine.rendering.particles;

/**
 * Tiny unsynchronized SplitMix64-seeded xorshift generator: deterministic for a seed, allocation-free and
 * several times cheaper than {@link java.util.Random} (no CAS). Not for concurrent use.
 */
final class ParticleRandom {

    private long state;

    ParticleRandom(long seed) {
        setSeed(seed);
    }

    void setSeed(long seed) {
        // SplitMix64 scramble so nearby seeds diverge and the state is never zero.
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        state = z == 0 ? 0x2545F4914F6CDD1DL : z;
    }

    long nextLong() {
        long x = state;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        state = x;
        return x;
    }

    /** Uniform in [0, 1). */
    float nextFloat() {
        return (nextLong() >>> 40) * 0x1.0p-24f;
    }

    /** Uniform in [min, min + span]. */
    float range(float min, float span) {
        return span == 0f ? min : min + nextFloat() * span;
    }

    int nextInt(int bound) {
        return (int) (((nextLong() >>> 33) * bound) >>> 31);
    }
}
