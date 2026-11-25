package com.openmason.engine.rig;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.List;

/**
 * Editable per-vertex skin weights ({@code cr_skin}): up to {@link #MAX_INFLUENCES} (joint, weight)
 * influences per vertex; a vertex without any follows its part's joint rigidly. All editing logic is
 * native; this class marshals. Joints are {@link Rig} joint indices. A skin belongs to one thread at a
 * time. Freed by {@link #close()} or, as a backstop, when unreachable.
 */
public final class Skin implements AutoCloseable {

    public static final int MAX_INFLUENCES = 4;

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

    /** One vertex's influences (heaviest first); empty arrays for an unweighted vertex. */
    public Influence get(int vertex) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment j = arena.allocate(ValueLayout.JAVA_INT, MAX_INFLUENCES);
            MemorySegment w = arena.allocate(ValueLayout.JAVA_FLOAT, MAX_INFLUENCES);
            int n = (int) CendaRig.SKIN_GET.invokeExact(handle(), vertex, j, w);
            int[] joints = new int[n];
            float[] weights = new float[n];
            for (int i = 0; i < n; i++) {
                joints[i] = j.getAtIndex(ValueLayout.JAVA_INT, i);
                weights[i] = w.getAtIndex(ValueLayout.JAVA_FLOAT, i);
            }
            return new Influence(vertex, joints, weights);
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

    /** Sets every listed vertex (others untouched). */
    public void importInfluences(List<Influence> influences) {
        if (influences == null || influences.isEmpty()) return;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment entries = arena.allocate(CendaRig.SKIN_ENTRY_BYTES * influences.size(), 4);
            for (int i = 0; i < influences.size(); i++) {
                Influence inf = influences.get(i);
                long o = CendaRig.SKIN_ENTRY_BYTES * i;
                int c = Math.min(MAX_INFLUENCES, inf.joints().length);
                entries.set(CendaRig.I32, o, inf.vertex());
                entries.set(CendaRig.I32, o + 4, c);
                for (int k = 0; k < c; k++) {
                    entries.set(CendaRig.I32, o + 8 + 4L * k, inf.joints()[k]);
                    entries.set(CendaRig.F32, o + 24 + 4L * k, inf.weights()[k]);
                }
            }
            int rc = (int) CendaRig.SKIN_IMPORT.invokeExact(handle(), entries, influences.size());
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_skin_import: " + CendaRig.lastError());
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** Every weighted vertex, in vertex order. */
    public List<Influence> exportInfluences() {
        try (Arena arena = Arena.ofConfined()) {
            int n = (int) CendaRig.SKIN_EXPORT.invokeExact(handle(), MemorySegment.NULL, 0);
            List<Influence> out = new ArrayList<>(n);
            if (n == 0) return out;
            MemorySegment entries = arena.allocate(CendaRig.SKIN_ENTRY_BYTES * n, 4);
            int written = (int) CendaRig.SKIN_EXPORT.invokeExact(handle(), entries, n);
            for (int i = 0; i < Math.min(n, written); i++) {
                long o = CendaRig.SKIN_ENTRY_BYTES * i;
                int c = entries.get(CendaRig.I32, o + 4);
                int[] j = new int[c];
                float[] w = new float[c];
                for (int k = 0; k < c; k++) {
                    j[k] = entries.get(CendaRig.I32, o + 8 + 4L * k);
                    w[k] = entries.get(CendaRig.F32, o + 24 + 4L * k);
                }
                out.add(new Influence(entries.get(CendaRig.I32, o), j, w));
            }
            return out;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** Packed GPU attributes (see {@link PackedSkin}). */
    public PackedSkin pack(int[] partJointOfVertex) {
        int n = vertexCount();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment parts = partJointOfVertex == null ? MemorySegment.NULL
                : arena.allocateFrom(ValueLayout.JAVA_INT, partJointOfVertex);
            MemorySegment joints = arena.allocate(8L * Math.max(1, n), 2);
            MemorySegment weights = arena.allocate(4L * Math.max(1, n), 1);
            int rc = (int) CendaRig.SKIN_PACK.invokeExact(handle(), parts, joints, weights);
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_skin_pack: " + CendaRig.lastError());
            short[] j = new short[n * 4];
            byte[] w = new byte[n * 4];
            MemorySegment.copy(joints, ValueLayout.JAVA_SHORT, 0, j, 0, n * 4);
            MemorySegment.copy(weights, ValueLayout.JAVA_BYTE, 0, w, 0, n * 4);
            return new PackedSkin(j, w);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /**
     * Auto weights for vertices {@code [first, first+count)} against {@code joints} (bones: their rest
     * head-tail segments; parts: their pivots). {@code falloff} 0 = rigid to the nearest.
     */
    public void autoWeights(Rig rig, float[] positions, int first, int count, int[] joints,
                            float falloff, float minWeight) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = arena.allocateFrom(ValueLayout.JAVA_FLOAT, positions);
            MemorySegment b = arena.allocateFrom(ValueLayout.JAVA_INT, joints);
            int rc = (int) CendaRig.SKIN_AUTO_WEIGHTS.invokeExact(handle(), rig.handle(), p, first, count, b,
                joints.length, falloff, minWeight);
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
