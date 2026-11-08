package com.openmason.engine.rendering.particles;

/**
 * Deterministic 3D value noise with quintic interpolation and an analytic gradient (Íñigo Quílez's formulation), and
 * curl noise built from three of those fields. Pure static float math: the compiler folds and the VM evaluates with
 * the same functions, so results are identical wherever they are computed.
 */
final class FxNoise {

    /** Offsets of the second and third potential fields for curl (decorrelate them from the first). */
    private static final float O2X = 31.416f, O2Y = 17.123f, O2Z = 5.927f;
    private static final float O3X = -11.31f, O3Y = 47.21f, O3Z = 23.71f;

    private FxNoise() {
    }

    /** Lattice value in [−1, 1]. */
    private static float lattice(int x, int y, int z) {
        int h = x * 0x27D4EB2D ^ y * 0x165667B1 ^ z * 0x5BD1E995;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        h *= 0x297A2D39;
        h ^= h >>> 15;
        return (h & 0xFFFFFF) * (2f / 0xFFFFFF) - 1f;
    }

    /** Noise at {@code (x, y, z)} in about [−1, 1]. */
    static float noise(float x, float y, float z) {
        return noise(x, y, z, null, 0);
    }

    /**
     * Noise at {@code (x, y, z)}; when {@code grad} is non-null its {@code [o, o+3)} receive the gradient.
     */
    static float noise(float x, float y, float z, float[] grad, int o) {
        float fx = (float) Math.floor(x), fy = (float) Math.floor(y), fz = (float) Math.floor(z);
        int ix = (int) fx, iy = (int) fy, iz = (int) fz;
        float wx = x - fx, wy = y - fy, wz = z - fz;
        float ux = wx * wx * wx * (wx * (wx * 6f - 15f) + 10f);
        float uy = wy * wy * wy * (wy * (wy * 6f - 15f) + 10f);
        float uz = wz * wz * wz * (wz * (wz * 6f - 15f) + 10f);
        float a = lattice(ix, iy, iz), b = lattice(ix + 1, iy, iz);
        float c = lattice(ix, iy + 1, iz), d = lattice(ix + 1, iy + 1, iz);
        float e = lattice(ix, iy, iz + 1), f = lattice(ix + 1, iy, iz + 1);
        float g = lattice(ix, iy + 1, iz + 1), h = lattice(ix + 1, iy + 1, iz + 1);
        float k1 = b - a, k2 = c - a, k3 = e - a;
        float k4 = a - b - c + d, k5 = a - c - e + g, k6 = a - b - e + f;
        float k7 = -a + b + c - d + e - f - g + h;
        if (grad != null) {
            float dux = 30f * wx * wx * (wx * (wx - 2f) + 1f);
            float duy = 30f * wy * wy * (wy * (wy - 2f) + 1f);
            float duz = 30f * wz * wz * (wz * (wz - 2f) + 1f);
            grad[o] = dux * (k1 + k4 * uy + k6 * uz + k7 * uy * uz);
            grad[o + 1] = duy * (k2 + k5 * uz + k4 * ux + k7 * uz * ux);
            grad[o + 2] = duz * (k3 + k6 * ux + k5 * uy + k7 * ux * uy);
        }
        return a + k1 * ux + k2 * uy + k3 * uz + k4 * ux * uy + k5 * uy * uz + k6 * uz * ux + k7 * ux * uy * uz;
    }

    /**
     * Curl of the potential (n(p), n(p + o2), n(p + o3)) at {@code (x, y, z)} into {@code out[0..3)}, using
     * {@code scratch[0..9)}. Divergence-free: particles swirl without bunching up or thinning out.
     */
    static void curl(float x, float y, float z, float[] out, float[] scratch) {
        noise(x, y, z, scratch, 0);
        noise(x + O2X, y + O2Y, z + O2Z, scratch, 3);
        noise(x + O3X, y + O3Y, z + O3Z, scratch, 6);
        // curl ψ = (∂ψz/∂y − ∂ψy/∂z, ∂ψx/∂z − ∂ψz/∂x, ∂ψy/∂x − ∂ψx/∂y)
        out[0] = scratch[7] - scratch[5];
        out[1] = scratch[2] - scratch[6];
        out[2] = scratch[3] - scratch[1];
    }
}
