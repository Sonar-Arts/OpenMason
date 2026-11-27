package com.openmason.engine.rig;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.List;

/**
 * Editable per-vertex skin weights ({@code cr_skin}): any number of (joint, weight) influences per vertex
 * (rig.h ABI 3, OpenMason #170 - no cap); a vertex without any follows its part's joint rigidly. All editing
 * logic is native; this class marshals. Joints are {@link Rig} joint indices. A skin belongs to one thread at a
 * time. Freed by {@link #close()} or, as a backstop, when unreachable.
 */
public final class Skin implements AutoCloseable {

    /** {@link #autoWeights}: weight every vertex to every bone (Blender's default). */
    public static final int UNLIMITED_INFLUENCES = 0;

    /** Brush modes ({@code CR_PAINT_*}). */
    public enum PaintMode { ADD, SUBTRACT, REPLACE, SMOOTH }

    /** One vertex's influences. */
    public record Influence(int vertex, int[] joints, float[] weights) {
        public Influence {
            joints = joints == null ? new int[0] : joints.clone();
            weights = weights == null ? new float[0] : weights.clone();
            if (joints.length != weights.length) {
                throw new IllegalArgumentException("joints/weights length mismatch");
            }
        }
    }

    private static final Cleaner CLEANER = Cleaner.create();

    private static final class State implements Runnable {
        volatile MemorySegment handle;

        State(MemorySegment handle) {
            this.handle = handle;
        }

        @Override
        public void run() {
            MemorySegment h = handle;
            handle = null;
            if (h != null && !h.equals(MemorySegment.NULL)) {
                try {
                    CendaRig.SKIN_DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    private final State state;
    private final Cleaner.Cleanable cleanable;

    private Skin(MemorySegment handle) {
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
    }

    /** An empty skin over {@code vertexCount} vertices (all following their parts). */
    public static Skin create(int vertexCount) {
        CendaRig.requireAvailable();
        try {
            MemorySegment h = (MemorySegment) CendaRig.SKIN_CREATE.invokeExact(vertexCount);
            if (h.equals(MemorySegment.NULL)) throw new IllegalArgumentException("cr_skin_create: " + CendaRig.lastError());
            return new Skin(h);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** A skin over {@code vertexCount} vertices holding {@code influences}. */
    public static Skin of(int vertexCount, List<Influence> influences) {
        Skin s = create(vertexCount);
        s.importInfluences(influences);
        return s;
    }

    MemorySegment handle() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("Skin is closed");
        return h;
    }

    /** The native {@code cr_skin*} for other Cenda bindings (garments, OpenMason #162); never free it. */
    public MemorySegment nativeHandle() {
        return handle();
    }

    /** An independent copy (undo snapshots). */
    public Skin copy() {
        try {
            MemorySegment h = (MemorySegment) CendaRig.SKIN_CLONE.invokeExact(handle());
            if (h.equals(MemorySegment.NULL)) throw new IllegalStateException("cr_skin_clone failed");
            return new Skin(h);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    public int vertexCount() {
        try {
            return (int) CendaRig.SKIN_VERTEX_COUNT.invokeExact(handle());
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** Vertices that carry authored weights. */
    public int weightedCount() {
        try {
            return (int) CendaRig.SKIN_WEIGHTED_COUNT.invokeExact(handle());
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** Replaces one vertex's influences (empty = follows its part); normalized natively. */
    public void set(int vertex, int[] joints, float[] weights) {
        int count = joints == null ? 0 : joints.length;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment j = count == 0 ? MemorySegment.NULL : arena.allocateFrom(ValueLayout.JAVA_INT, joints);
            MemorySegment w = count == 0 ? MemorySegment.NULL : arena.allocateFrom(ValueLayout.JAVA_FLOAT, weights);
            int rc = (int) CendaRig.SKIN_SET.invokeExact(handle(), vertex, j, w, count);
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_skin_set: " + CendaRig.lastError());
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** One vertex's influences (heaviest first, all of them); empty arrays for an unweighted vertex. */
    public Influence get(int vertex) {
        try (Arena arena = Arena.ofConfined()) {
            int n = influenceCount(vertex);
            if (n <= 0) return new Influence(vertex, new int[0], new float[0]);
            MemorySegment j = arena.allocate(ValueLayout.JAVA_INT, n);
            MemorySegment w = arena.allocate(ValueLayout.JAVA_FLOAT, n);
            int full = (int) CendaRig.SKIN_GET.invokeExact(handle(), vertex, j, w, n);
            int c = Math.min(n, full);
            return new Influence(vertex, j.asSlice(0, 4L * c).toArray(ValueLayout.JAVA_INT),
                w.asSlice(0, 4L * c).toArray(ValueLayout.JAVA_FLOAT));
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** A vertex's influence count (0 = unweighted or a bad vertex). */
    public int influenceCount(int vertex) {
        try {
            return (int) CendaRig.SKIN_INFLUENCE_COUNT.invokeExact(handle(), vertex);
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** The largest influence count of any vertex (0 = nothing weighted). */
    public int maxInfluences() {
        try {
            return (int) CendaRig.SKIN_MAX_INFLUENCES.invokeExact(handle());
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** The number of influences over every weighted vertex. */
    public int influenceTotal() {
        try {
            int n = (int) CendaRig.SKIN_INFLUENCE_TOTAL.invokeExact(handle());
            if (n < 0) throw new IllegalStateException("cr_skin_influence_total: " + CendaRig.lastError());
            return n;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /**
     * Weight of {@code joint} at every vertex. With {@code partJointOfVertex}, an unweighted vertex
     * reports 1 for its part's joint.
     */
    public float[] jointWeights(int joint, int[] partJointOfVertex) {
        int n = vertexCount();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment parts = partJointOfVertex == null ? MemorySegment.NULL
                : arena.allocateFrom(ValueLayout.JAVA_INT, partJointOfVertex);
            MemorySegment out = arena.allocate(ValueLayout.JAVA_FLOAT, Math.max(1, n));
            int rc = (int) CendaRig.SKIN_JOINT_WEIGHTS.invokeExact(handle(), joint, parts, out);
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_skin_joint_weights: " + CendaRig.lastError());
            float[] result = new float[n];
            MemorySegment.copy(out, ValueLayout.JAVA_FLOAT, 0, result, 0, n);
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /**
     * Sets every listed vertex (others untouched), every influence kept (CSR {@code cr_skin_import}; duplicate
     * joints merge, the rest is normalized natively).
     */
    public void importInfluences(List<Influence> influences) {
        if (influences == null || influences.isEmpty()) return;
        int count = influences.size();
        long total = 0;
        for (Influence inf : influences) total += inf.joints().length;
        if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("too many influences: " + total);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment vertices = arena.allocate(ValueLayout.JAVA_INT, count);
            MemorySegment offsets = arena.allocate(ValueLayout.JAVA_INT, count + 1L);
            MemorySegment joints = arena.allocate(ValueLayout.JAVA_INT, Math.max(1, total));
            MemorySegment weights = arena.allocate(ValueLayout.JAVA_FLOAT, Math.max(1, total));
            int o = 0;
            for (int i = 0; i < count; i++) {
                Influence inf = influences.get(i);
                vertices.setAtIndex(ValueLayout.JAVA_INT, i, inf.vertex());
                offsets.setAtIndex(ValueLayout.JAVA_INT, i, o);
                int c = inf.joints().length;
                MemorySegment.copy(inf.joints(), 0, joints, ValueLayout.JAVA_INT, 4L * o, c);
                MemorySegment.copy(inf.weights(), 0, weights, ValueLayout.JAVA_FLOAT, 4L * o, c);
                o += c;
            }
            offsets.setAtIndex(ValueLayout.JAVA_INT, count, o);
            int rc = (int) CendaRig.SKIN_IMPORT.invokeExact(handle(), count, vertices, offsets, joints, weights);
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_skin_import: " + CendaRig.lastError());
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** Every weighted vertex, in vertex order, its influences heaviest first. */
    public List<Influence> exportInfluences() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment h = handle();
            int n = (int) CendaRig.SKIN_EXPORT.invokeExact(h, 0, 0, MemorySegment.NULL, MemorySegment.NULL,
                MemorySegment.NULL, MemorySegment.NULL);
            if (n < 0) throw new IllegalStateException("cr_skin_export: " + CendaRig.lastError());
            List<Influence> out = new ArrayList<>(n);
            if (n == 0) return out;
            int total = influenceTotal();
            MemorySegment vertices = arena.allocate(ValueLayout.JAVA_INT, n);
            MemorySegment offsets = arena.allocate(ValueLayout.JAVA_INT, n + 1L);
            MemorySegment joints = arena.allocate(ValueLayout.JAVA_INT, Math.max(1, total));
            MemorySegment weights = arena.allocate(ValueLayout.JAVA_FLOAT, Math.max(1, total));
            int written = (int) CendaRig.SKIN_EXPORT.invokeExact(h, n, total, vertices, offsets, joints, weights);
            if (written != n) throw new IllegalStateException("cr_skin_export: " + CendaRig.lastError());
            int[] v = vertices.toArray(ValueLayout.JAVA_INT);
            int[] off = offsets.toArray(ValueLayout.JAVA_INT);
            int[] j = joints.toArray(ValueLayout.JAVA_INT);
            float[] w = weights.toArray(ValueLayout.JAVA_FLOAT);
            for (int i = 0; i < n; i++) {
                out.add(new Influence(v[i], java.util.Arrays.copyOfRange(j, off[i], off[i + 1]),
                    java.util.Arrays.copyOfRange(w, off[i], off[i + 1])));
            }
            return out;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** Packed GPU form (see {@link PackedSkin}); unweighted vertices are rigid to their part joint. */
    public PackedSkin pack(int[] partJointOfVertex) {
        int n = vertexCount();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment h = handle();
            int records = (int) CendaRig.SKIN_PACK_SIZE.invokeExact(h);
            if (records < 0) throw new IllegalStateException("cr_skin_pack_size: " + CendaRig.lastError());
            MemorySegment parts = partJointOfVertex == null ? MemorySegment.NULL
                : arena.allocateFrom(ValueLayout.JAVA_INT, partJointOfVertex);
            MemorySegment ranges = arena.allocate(ValueLayout.JAVA_INT, 2L * Math.max(1, n));
            MemorySegment influences = arena.allocate(ValueLayout.JAVA_SHORT, 2L * Math.max(1, records));
            int rc = (int) CendaRig.SKIN_PACK.invokeExact(h, parts, records, ranges, influences);
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_skin_pack: " + CendaRig.lastError());
            return new PackedSkin(ranges.asSlice(0, 8L * n).toArray(ValueLayout.JAVA_INT),
                influences.asSlice(0, 4L * records).toArray(ValueLayout.JAVA_SHORT));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /**
     * Auto weights for vertices {@code [first, first+count)} against {@code joints} (bones: their rest
     * head-tail segments; parts: their pivots). {@code falloff} 0 = rigid to the nearest. {@code maxInfluences}
     * limits each vertex to its nearest bones ({@link #UNLIMITED_INFLUENCES} = every bone; 4 = the ABI 2 weights).
     */
    public void autoWeights(Rig rig, float[] positions, int first, int count, int[] joints,
                            float falloff, float minWeight, int maxInfluences) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = arena.allocateFrom(ValueLayout.JAVA_FLOAT, positions);
            MemorySegment b = arena.allocateFrom(ValueLayout.JAVA_INT, joints);
            int rc = (int) CendaRig.SKIN_AUTO_WEIGHTS.invokeExact(handle(), rig.handle(), p, first, count, b,
                joints.length, falloff, minWeight, Math.max(0, maxInfluences));
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_skin_auto_weights: " + CendaRig.lastError());
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /**
     * One spherical brush dab; returns how many vertices changed. {@code indices} are only read by
     * {@link PaintMode#SMOOTH}; {@code onlyVertices} (one flag per vertex, null = all) limits the dab.
     */
    public int paint(float[] positions, int[] partJointOfVertex, int[] indices, boolean[] onlyVertices,
                     float cx, float cy, float cz, float radius, int joint, PaintMode mode, float strength) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = arena.allocateFrom(ValueLayout.JAVA_FLOAT, positions);
            MemorySegment parts = partJointOfVertex == null ? MemorySegment.NULL
                : arena.allocateFrom(ValueLayout.JAVA_INT, partJointOfVertex);
            MemorySegment idx = indices == null || indices.length == 0 ? MemorySegment.NULL
                : arena.allocateFrom(ValueLayout.JAVA_INT, indices);
            MemorySegment only = MemorySegment.NULL;
            if (onlyVertices != null) {
                only = arena.allocate(Math.max(1, onlyVertices.length));
                for (int i = 0; i < onlyVertices.length; i++) only.set(ValueLayout.JAVA_BYTE, i, (byte) (onlyVertices[i] ? 1 : 0));
            }
            MemorySegment c = arena.allocateFrom(ValueLayout.JAVA_FLOAT, cx, cy, cz);
            int changed = (int) CendaRig.SKIN_PAINT.invokeExact(handle(), p, parts, idx,
                indices == null ? 0 : indices.length, only, c, radius, joint, mode.ordinal(), strength);
            if (changed < 0) throw new IllegalArgumentException("cr_skin_paint: " + CendaRig.lastError());
            return changed;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    public void normalize() {
        try {
            int rc = (int) CendaRig.SKIN_NORMALIZE.invokeExact(handle());
            if (rc != CendaRig.OK) throw new IllegalStateException("cr_skin_normalize: " + CendaRig.lastError());
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** Drops influences below {@code minWeight}; returns how many were removed. */
    public int prune(float minWeight) {
        try {
            return (int) CendaRig.SKIN_PRUNE.invokeExact(handle(), minWeight);
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** Removes a joint everywhere; returns how many vertices it touched. */
    public int removeJoint(int joint) {
        try {
            return (int) CendaRig.SKIN_REMOVE_JOINT.invokeExact(handle(), joint);
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** Renumbers joints: {@code map[old] = new} (-1 drops it). */
    public void remapJoints(int[] map) {
        try (Arena arena = Arena.ofConfined()) {
            int rc = (int) CendaRig.SKIN_REMAP_JOINTS.invokeExact(handle(),
                arena.allocateFrom(ValueLayout.JAVA_INT, map), map.length);
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_skin_remap_joints: " + CendaRig.lastError());
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /**
     * Mirrors weights across the plane {@code axis = 0} (0 X, 1 Y, 2 Z) from the {@code sourceSign} side,
     * mapping joints through {@code jointMirror} (null = same joint). Returns vertices written.
     */
    public int mirror(float[] positions, int axis, int sourceSign, float tolerance, int[] jointMirror) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = arena.allocateFrom(ValueLayout.JAVA_FLOAT, positions);
            MemorySegment m = jointMirror == null ? MemorySegment.NULL : arena.allocateFrom(ValueLayout.JAVA_INT, jointMirror);
            int n = (int) CendaRig.SKIN_MIRROR.invokeExact(handle(), p, axis, sourceSign, tolerance, m,
                jointMirror == null ? 0 : jointMirror.length);
            if (n < 0) throw new IllegalArgumentException("cr_skin_mirror: " + CendaRig.lastError());
            return n;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** A new skin over an edited vertex set: each new vertex takes its nearest old vertex's weights. */
    public Skin transfer(float[] oldPositions, float[] newPositions) {
        int newCount = newPositions == null ? 0 : newPositions.length / 3;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment o = oldPositions == null || oldPositions.length == 0 ? MemorySegment.NULL
                : arena.allocateFrom(ValueLayout.JAVA_FLOAT, oldPositions);
            MemorySegment n = newCount == 0 ? MemorySegment.NULL : arena.allocateFrom(ValueLayout.JAVA_FLOAT, newPositions);
            MemorySegment h = (MemorySegment) CendaRig.SKIN_TRANSFER.invokeExact(handle(), o, n, newCount);
            if (h.equals(MemorySegment.NULL)) throw new IllegalArgumentException("cr_skin_transfer: " + CendaRig.lastError());
            return new Skin(h);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /**
     * Deforms {@code positions} with the palette's skin matrices (CPU skinning, identical weights to the
     * GPU path). {@code skin} may be null: every vertex follows its part joint.
     */
    public static float[] deform(Skin skin, int[] partJointOfVertex, float[] positions, JointPalette palette,
                                 float[] out) {
        int n = positions.length / 3;
        float[] result = out != null && out.length >= positions.length ? out : new float[positions.length];
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = arena.allocateFrom(ValueLayout.JAVA_FLOAT, positions);
            MemorySegment parts = partJointOfVertex == null ? MemorySegment.NULL
                : arena.allocateFrom(ValueLayout.JAVA_INT, partJointOfVertex);
            MemorySegment o = arena.allocate(ValueLayout.JAVA_FLOAT, Math.max(1, positions.length));
            MemorySegment skinSeg = skin == null ? MemorySegment.NULL : skin.handle();
            int rc = (int) CendaRig.SKIN_DEFORM.invokeExact(skinSeg,
                parts, p, n, palette.skinSegment(), palette.count(), o);
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_skin_deform: " + CendaRig.lastError());
            MemorySegment.copy(o, ValueLayout.JAVA_FLOAT, 0, result, 0, positions.length);
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    @Override
    public void close() {
        cleanable.clean();
    }
}
