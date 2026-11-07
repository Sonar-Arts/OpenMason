package com.openmason.engine.rendering.particles;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * GL-free stage between the simulation and the GPU: frustum/distance culls the live particles, orders them
 * back to front, optionally partitions them at a split plane, and packs the survivors into
 * {@link ParticleVertexFormat} records at a raw memory address (a mapped GL buffer in production, any direct
 * buffer in tests).
 *
 * <h2>Cost</h2>
 * <ul>
 *   <li>Cull: one pass, a sphere-vs-frustum test per particle (bounding radius = half the quad diagonal, plus
 *       the trail length for velocity-stretched particles, so nothing pops). Culled particles are never
 *       sorted, packed or uploaded.</li>
 *   <li>Sort: two-pass LSD radix sort on 16-bit view-depth keys — O(n), stable, no comparisons. Skipped
 *       entirely when every visible particle is purely additive (order-independent).</li>
 *   <li>Split: one stable partition pass, only when {@link ParticleFrame#split} is set.</li>
 *   <li>Pack: colour and size from per-effect key arrays (≤ 8 keys, exact), direct {@link MemoryUtil} stores.</li>
 * </ul>
 * Scratch arrays (20 bytes per allocated particle slot) follow the largest system seen; building a batch never
 * allocates after that. Single-threaded.
 */
public final class ParticleBatchBuilder {

    private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    private static final float TWO_PI = (float) (2 * Math.PI);
    /** Half the diagonal of a unit quad. */
    private static final float BOUND_RADIUS = 0.70710677f;
    private static final int VELOCITY = ParticleEffect.Orientation.VELOCITY.ordinal();

    private int[] visible = new int[0];
    private int[] visibleTmp = new int[0];
    private char[] keys = new char[0];
    private char[] keysTmp = new char[0];
    /** Indexed by particle slot, valid for visible particles of the last prepare. */
    private float[] sizeScratch = new float[0];
    private float[] depthScratch = new float[0];
    private final int[] histogram = new int[256];

    private final Matrix4f viewProjection = new Matrix4f();
    private final FrustumIntersection frustum = new FrustumIntersection();

    private ParticleSystem system;
    private int count;
    private int behindCount;
    private boolean sorted;
    private float offX, offY, offZ;

    /**
     * Culls, sorts and (if the frame asks) splits {@code system}'s live particles for {@code frame}. Returns
     * how many records {@link #pack} will write. The system must not change between this call and {@code pack}.
     */
    public int prepare(ParticleSystem system, ParticleFrame frame) {
        this.system = system;
        ensureCapacity(system.allocatedSlots());
        int live = system.liveCount();
        count = 0;
        behindCount = 0;
        sorted = false;
        if (live == 0) {
            return 0;
        }
        offX = (float) (system.anchorX() - frame.originX);
        offY = (float) (system.anchorY() - frame.originY);
        offZ = (float) (system.anchorZ() - frame.originZ);
        frame.projection.mul(frame.view, viewProjection);
        frustum.set(viewProjection);

        // Camera forward = -Z row of the view matrix (render space).
        final float fx = -frame.view.m02(), fy = -frame.view.m12(), fz = -frame.view.m22();
        final float cx = frame.cameraPosition.x, cy = frame.cameraPosition.y, cz = frame.cameraPosition.z;
        final float maxDist = frame.maxDistance;
        final float maxDistSq = maxDist == Float.POSITIVE_INFINITY ? Float.POSITIVE_INFINITY : maxDist * maxDist;

        final float[] px = system.px, py = system.py, pz = system.pz;
        final float[] vx = system.vx, vy = system.vy, vz = system.vz;
        final float[] age = system.age, invLife = system.invLife, s0 = system.size0, s1 = system.size1;
        final short[] effectId = system.effectId;
        final int[] visible = this.visible;
        final float[] sizes = sizeScratch, depths = depthScratch;
        float minDepth = Float.POSITIVE_INFINITY, maxDepth = Float.NEGATIVE_INFINITY;
        boolean needsOrder = false;
        int n = 0;
        for (int i = 0; i < live; i++) {
            CompiledEffect e = system.effect(effectId[i]);
            float t = age[i] * invLife[i];
            float size = (s0[i] + (s1[i] - s0[i]) * t) * e.sizeScale(t);
            if (size <= 0f) {
                continue;
            }
            float rx = px[i] + offX, ry = py[i] + offY, rz = pz[i] + offZ;
            float dx = rx - cx, dy = ry - cy, dz = rz - cz;
            if (dx * dx + dy * dy + dz * dz > maxDistSq) {
                continue;
            }
            float bound = size * BOUND_RADIUS;
            if (e.orientation == VELOCITY) {
                float speed = (float) Math.sqrt(vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i]);
                bound += speed * e.stretch;
            }
            if (!frustum.testSphere(rx, ry, rz, bound)) {
                continue;
            }
            float depth = dx * fx + dy * fy + dz * fz;
            sizes[i] = size;
            depths[i] = depth;
            if (depth < minDepth) minDepth = depth;
            if (depth > maxDepth) maxDepth = depth;
            if (e.additive < 1f) {
                needsOrder = true;
            }
            visible[n++] = i;
        }
        count = n;
        if (needsOrder && n > 1 && maxDepth > minDepth) {
            radixSortBackToFront(n, minDepth, maxDepth);
            sorted = true;
        }
        if (frame.split && n > 0) {
            partition(frame, cx, cy, cz);
        }
        return n;
    }

    private void radixSortBackToFront(int n, float minDepth, float maxDepth) {
        final float scale = 65535f / (maxDepth - minDepth);
        final float[] depths = depthScratch;
        int[] idx = visible, idxTmp = visibleTmp;
        char[] k = keys, kTmp = keysTmp;
        for (int j = 0; j < n; j++) {
            // Far first: the largest depth gets the smallest key.
            int q = (int) ((depths[idx[j]] - minDepth) * scale);
            k[j] = (char) (65535 - Math.min(65535, Math.max(0, q)));
        }
        for (int shift = 0; shift < 16; shift += 8) {
            int[] h = histogram;
            Arrays.fill(h, 0);
            for (int j = 0; j < n; j++) {
                h[(k[j] >>> shift) & 0xFF]++;
            }
            int sum = 0;
            for (int b = 0; b < 256; b++) {
                int c = h[b];
                h[b] = sum;
                sum += c;
            }
            for (int j = 0; j < n; j++) {
                char key = k[j];
                int dst = h[(key >>> shift) & 0xFF]++;
                kTmp[dst] = key;
                idxTmp[dst] = idx[j];
            }
            int[] ti = idx; idx = idxTmp; idxTmp = ti;
            char[] tk = k; k = kTmp; kTmp = tk;
        }
        // Two passes: the result is back in the original arrays.
        visible = idx;
        visibleTmp = idxTmp;
        keys = k;
        keysTmp = kTmp;
    }

    /** Stable partition: particles across the split plane from the camera first, each group keeping its order. */
    private void partition(ParticleFrame f, float cx, float cy, float cz) {
        final float a = f.splitA, b = f.splitB, c = f.splitC, d = f.splitD;
        final boolean cameraAbove = a * cx + b * cy + c * cz + d >= 0f;
        final float[] px = system.px, py = system.py, pz = system.pz;
        final int[] src = visible, dst = visibleTmp;
        int behind = 0;
        for (int j = 0; j < count; j++) {
            int i = src[j];
            float side = a * (px[i] + offX) + b * (py[i] + offY) + c * (pz[i] + offZ) + d;
            if ((side >= 0f) != cameraAbove) {
                dst[behind++] = i;
            }
        }
        int front = behind;
        for (int j = 0; j < count; j++) {
            int i = src[j];
            float side = a * (px[i] + offX) + b * (py[i] + offY) + c * (pz[i] + offZ) + d;
            if ((side >= 0f) == cameraAbove) {
                dst[front++] = i;
            }
        }
        visible = dst;
        visibleTmp = src;
        behindCount = behind;
    }

    /** Writes the prepared records ({@code count × 32} bytes) starting at {@code address}. */
    public void pack(long address) {
        final ParticleSystem s = system;
        final int n = count;
        if (n == 0) {
            return;
        }
        final float[] px = s.px, py = s.py, pz = s.pz, vx = s.vx, vy = s.vy, vz = s.vz;
        final float[] age = s.age, invLife = s.invLife, rot = s.rot;
        final int[] tint = s.tint;
        final short[] effectId = s.effectId, frameOffset = s.frameOffset;
        final float[] sizes = sizeScratch;
        final int[] visible = this.visible;
        long p = address;
        for (int k = 0; k < n; k++, p += ParticleVertexFormat.STRIDE) {
            int i = visible[k];
            CompiledEffect e = s.effect(effectId[i]);
            float t = age[i] * invLife[i];

            // Colour over life from the effect's keys (exact at each key), times the per-particle tint.
            float[] keyT = e.colorT, kv = e.colorV;
            int seg = CompiledEffect.segment(keyT, t);
            float w = CompiledEffect.weight(keyT, seg, t);
            int o = seg * 4;
            int o2 = w == 0f ? o : o + 4;
            int tc = tint[i];
            float r = (kv[o] + (kv[o2] - kv[o]) * w) * (tc >>> 24);
            float g = (kv[o + 1] + (kv[o2 + 1] - kv[o + 1]) * w) * ((tc >>> 16) & 0xFF);
            float b = (kv[o + 2] + (kv[o2 + 2] - kv[o + 2]) * w) * ((tc >>> 8) & 0xFF);
            float alpha = (kv[o + 3] + (kv[o2 + 3] - kv[o + 3]) * w) * ((tc & 0xFF) * (1f / 255f)) * e.fade(t);
            int ri = (int) (r * alpha + 0.5f);
            int gi = (int) (g * alpha + 0.5f);
            int bi = (int) (b * alpha + 0.5f);
            int ai = (int) (alpha * (1f - e.additive) * 255f + 0.5f);

            float angle = rot[i];
            if (angle > Math.PI || angle < -Math.PI) {
                angle -= TWO_PI * (float) Math.floor((angle + Math.PI) / TWO_PI);
            }
            int frame;
            if (!e.atlas) {
                frame = e.frameBase;
            } else if (e.animate) {
                frame = e.frameBase + Math.min((int) (t * e.frameCount), e.frameCount - 1);
            } else {
                frame = e.frameBase + (frameOffset[i] & 0xFFFF);
            }
            int flags = (e.emissive ? ParticleVertexFormat.FLAG_EMISSIVE : 0)
                    | (e.orientation << ParticleVertexFormat.ORIENTATION_SHIFT);
            float stretch = e.orientation == VELOCITY ? e.stretch : 0f;

            MemoryUtil.memPutFloat(p, px[i] + offX);
            MemoryUtil.memPutFloat(p + 4, py[i] + offY);
            MemoryUtil.memPutFloat(p + 8, pz[i] + offZ);
            MemoryUtil.memPutInt(p + 12, LITTLE_ENDIAN
                    ? ri | (gi << 8) | (bi << 16) | (ai << 24)
                    : (ri << 24) | (gi << 16) | (bi << 8) | ai);
            MemoryUtil.memPutShort(p + 16, Float.floatToFloat16(sizes[i]));
            MemoryUtil.memPutShort(p + 18, Float.floatToFloat16(angle));
            MemoryUtil.memPutShort(p + 20, (short) frame);
            MemoryUtil.memPutShort(p + 22, (short) flags);
            MemoryUtil.memPutShort(p + 24, Float.floatToFloat16(vx[i] * stretch));
            MemoryUtil.memPutShort(p + 26, Float.floatToFloat16(vy[i] * stretch));
            MemoryUtil.memPutShort(p + 28, Float.floatToFloat16(vz[i] * stretch));
            MemoryUtil.memPutShort(p + 30, Float.floatToFloat16(e.softness));
        }
    }

    /** {@link #pack(long)} into a direct buffer from its position (which is not moved). */
    public void pack(ByteBuffer direct) {
        if (!direct.isDirect()) {
            throw new IllegalArgumentException("buffer must be direct");
        }
        if (direct.remaining() < (long) count * ParticleVertexFormat.STRIDE) {
            throw new IllegalArgumentException("buffer too small: " + direct.remaining() + " < "
                    + (long) count * ParticleVertexFormat.STRIDE);
        }
        pack(MemoryUtil.memAddress(direct) + direct.position());
    }

    private void ensureCapacity(int slots) {
        if (visible.length >= slots) {
            return;
        }
        visible = new int[slots];
        visibleTmp = new int[slots];
        keys = new char[slots];
        keysTmp = new char[slots];
        sizeScratch = new float[slots];
        depthScratch = new float[slots];
    }

    /** Records the last {@link #prepare} produced. */
    public int count() {
        return count;
    }

    /** Leading records on the far side of the split plane (0 when the frame did not split). */
    public int behindCount() {
        return behindCount;
    }

    /** Whether the last {@link #prepare} had to sort (some visible particle is not purely additive). */
    public boolean sortedLastBuild() {
        return sorted;
    }

    /** Pool slot of the {@code k}-th record (draw order) of the last {@link #prepare}. */
    public int visibleSlot(int k) {
        if (k < 0 || k >= count) {
            throw new IndexOutOfBoundsException(k + " of " + count);
        }
        return visible[k];
    }

    /** Scratch bytes held (diagnostics). */
    public long scratchBytes() {
        return (long) visible.length * (4 + 4 + 2 + 2 + 4 + 4);
    }
}
