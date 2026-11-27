package com.openmason.engine.rig;

import com.openmason.engine.format.omo.OMOFormat;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A mesh's morph targets / shape keys ({@code cr_morphs}, rig.h "Morph targets", OpenMason #171): sparse per-vertex
 * position (and optional normal) deltas over the SOUP vertex order the host draws. Immutable and thread-safe; the
 * CPU apply, weight clamping and GPU packing are native, this class only marshals.
 *
 * <p>A model's targets become its rig's morph channels ({@link Rig#create(List, RigMorphs)}: channel i = target i),
 * so pose weights, clip morph tracks and graph read-back all index the same way. Per frame a host uploads
 * {@link #clampWeights clamped} weights only ({@code ModelGpuMesh.uploadMorphWeights}).
 *
 * <p>Freed by {@link #close()} or, as a backstop, when unreachable.
 */
public final class RigMorphs implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    /** One target: its name, default weight and the [min, max] range weights are clamped to when applied. */
    public record Target(String name, float defaultWeight, float min, float max) {}

    /**
     * The GPU packing ({@code cr_morphs_pack}): per vertex {@code (offset, count)} into the records ({@code ranges},
     * 2 per vertex), per record {@code (dx, dy, dz, target)} ({@code deltas}, 4 floats) and, when any row has one,
     * {@code (nx, ny, nz, 0)} ({@code normals}, 4 floats, else null).
     */
    public record Packed(int vertexCount, int targetCount, int recordCount, int[] ranges, float[] deltas,
                         float[] normals) {}

    private final State state;
    private final Cleaner.Cleanable cleanable;
    private final int vertexCount;
    private final int targetCount;
    private final List<Target> targets;
    private final Map<String, Integer> byName = new HashMap<>();
    private volatile Packed packed;

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
                    CendaRig.MORPHS_DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    private RigMorphs(MemorySegment handle) {
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
        try {
            this.vertexCount = (int) CendaRig.MORPHS_VERTEX_COUNT.invokeExact(handle);
            this.targetCount = (int) CendaRig.MORPHS_TARGET_COUNT.invokeExact(handle);
            List<Target> list = new ArrayList<>(targetCount);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment params = arena.allocate(12, 4);
                for (int t = 0; t < targetCount; t++) {
                    MemorySegment name = (MemorySegment) CendaRig.MORPHS_TARGET_NAME.invokeExact(handle, t);
                    int rc = (int) CendaRig.MORPHS_TARGET_PARAMS.invokeExact(handle, t, params);
                    if (rc != CendaRig.OK) throw new IllegalStateException("cr_morphs_target_params: " + CendaRig.lastError());
                    String n = name.equals(MemorySegment.NULL) ? "" : name.reinterpret(Integer.MAX_VALUE).getString(0);
                    list.add(new Target(n, params.get(CendaRig.F32, 0), params.get(CendaRig.F32, 4),
                        params.get(CendaRig.F32, 8)));
                }
            }
            this.targets = List.copyOf(list);
        } catch (RuntimeException e) {
            cleanable.clean();
            throw e;
        } catch (Throwable t) {
            cleanable.clean();
            throw CendaRig.rethrow(t);
        }
        for (int t = 0; t < targets.size(); t++) byName.putIfAbsent(targets.get(t).name(), t);
    }

    /**
     * {@code cr_morphs_create}: targets over {@code vertexCount} soup vertices from a target-major CSR.
     *
     * @param names         target names (unique, case-insensitive)
     * @param params        3 floats per target (default, min, max), or null = (0, 0, 1)
     * @param offsets       {@code names.size() + 1} row offsets (from 0, non-decreasing)
     * @param vertices      soup vertex per row
     * @param deltas        3 floats per row
     * @param normalDeltas  3 floats per row, or null = no normal deltas
     * @throws IllegalArgumentException with the native reason
     */
    public static RigMorphs create(int vertexCount, List<String> names, float[] params, int[] offsets, int[] vertices,
                                   float[] deltas, float[] normalDeltas) {
        CendaRig.requireAvailable();
        int targets = names == null ? 0 : names.size();
        int rows = vertices == null ? 0 : vertices.length;
        if (offsets == null || offsets.length != targets + 1) {
            throw new IllegalArgumentException("need " + (targets + 1) + " offsets");
        }
        if (deltas == null || deltas.length < rows * 3 || (normalDeltas != null && normalDeltas.length < rows * 3)) {
            throw new IllegalArgumentException("deltas / normals hold 3 floats per row");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = params == null ? MemorySegment.NULL : arena.allocateFrom(ValueLayout.JAVA_FLOAT, params);
            MemorySegment o = arena.allocateFrom(ValueLayout.JAVA_INT, offsets);
            MemorySegment v = arena.allocateFrom(ValueLayout.JAVA_INT, rows == 0 ? new int[1] : vertices);
            MemorySegment d = arena.allocateFrom(ValueLayout.JAVA_FLOAT, rows == 0 ? new float[3] : deltas);
            MemorySegment n = normalDeltas == null ? MemorySegment.NULL
                : arena.allocateFrom(ValueLayout.JAVA_FLOAT, rows == 0 ? new float[3] : normalDeltas);
            MemorySegment nameSeg = CendaRig.strings(arena, names);
            MemorySegment h = (MemorySegment) CendaRig.MORPHS_CREATE.invokeExact(vertexCount, targets, nameSeg, p,
                o, v, d, n);
            if (h.equals(MemorySegment.NULL)) throw new IllegalArgumentException("cr_morphs_create: " + CendaRig.lastError());
            return new RigMorphs(h);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /**
     * The OMO 1.9 morphs of a model (part-local rows) over its soup: a row of part {@code partIds[p]} names soup vertex
     * {@code partFirstVertex[p] + local}; a row whose part is not listed or whose local vertex is outside
     * {@code [0, partVertexCount[p])} is skipped (the rule of {@code cr_morphs_from_doc}). Null when {@code morphs} is
     * empty.
     */
    public static RigMorphs fromParts(List<OMOFormat.MorphData> morphs, List<String> partIds, int[] partFirstVertex,
                                      int[] partVertexCount, int vertexCount) {
        if (morphs == null || morphs.isEmpty()) return null;
        Map<String, Integer> part = new HashMap<>();
        for (int p = 0; p < partIds.size(); p++) {
            if (partIds.get(p) != null) part.putIfAbsent(partIds.get(p), p);
        }
        boolean anyNormals = false;
        for (OMOFormat.MorphData m : morphs) {
            for (OMOFormat.MorphPart mp : m.parts()) anyNormals |= mp.normals() != null;
        }
        List<String> names = new ArrayList<>(morphs.size());
        float[] params = new float[morphs.size() * 3];
        int[] offsets = new int[morphs.size() + 1];
        IntList vertices = new IntList();
        FloatList deltas = new FloatList();
        FloatList normals = anyNormals ? new FloatList() : null;
        for (int t = 0; t < morphs.size(); t++) {
            OMOFormat.MorphData m = morphs.get(t);
            names.add(m.name());
            params[t * 3] = m.defaultWeight();
            params[t * 3 + 1] = m.min();
            params[t * 3 + 2] = m.max();
            for (OMOFormat.MorphPart mp : m.parts()) {
                Integer p = part.get(mp.partId());
                if (p == null) continue;
                int[] local = mp.vertices();
                float[] d = mp.deltas();
                float[] n = mp.normals();
                for (int r = 0; r < local.length; r++) {
                    if (local[r] < 0 || local[r] >= partVertexCount[p]) continue;
                    vertices.add(partFirstVertex[p] + local[r]);
                    deltas.add(d[r * 3], d[r * 3 + 1], d[r * 3 + 2]);
                    if (normals != null) {
                        if (n != null) normals.add(n[r * 3], n[r * 3 + 1], n[r * 3 + 2]);
                        else normals.add(0f, 0f, 0f);
                    }
                }
            }
            offsets[t + 1] = vertices.size;
        }
        return create(vertexCount, names, params, offsets, vertices.toArray(), deltas.toArray(),
            normals == null ? null : normals.toArray());
    }

    /**
     * {@code cr_morphs_remap}: the same targets over a new vertex list, new vertex v taking every row of
     * {@code sourceVertex[v]} (-1 = none) - for a loader that splits shared vertices. A new set; this one is unchanged.
     */
    public RigMorphs remap(int newVertexCount, int[] sourceVertex) {
        if (sourceVertex == null || sourceVertex.length != newVertexCount) {
            throw new IllegalArgumentException("need one source per new vertex");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocateFrom(ValueLayout.JAVA_INT, newVertexCount == 0 ? new int[1] : sourceVertex);
            MemorySegment h = (MemorySegment) CendaRig.MORPHS_REMAP.invokeExact(handle(), newVertexCount, src);
            if (h.equals(MemorySegment.NULL)) throw new IllegalArgumentException("cr_morphs_remap: " + CendaRig.lastError());
            return new RigMorphs(h);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    MemorySegment handle() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("RigMorphs is closed");
        return h;
    }

    /** The native {@code cr_morphs*} (valid while this set is open; never free it). */
    public MemorySegment nativeHandle() {
        return handle();
    }

    public int vertexCount() {
        return vertexCount;
    }

    public int targetCount() {
        return targetCount;
    }

    public List<Target> targets() {
        return targets;
    }

    /** Stored rows over every target (= the GPU record count). */
    public int rowCount() {
        try {
            return (int) CendaRig.MORPHS_ROW_COUNT.invokeExact(handle());
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** True when any row carries a normal delta. */
    public boolean hasNormals() {
        try {
            return (int) CendaRig.MORPHS_HAS_NORMALS.invokeExact(handle()) != 0;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** Target by name (exact, then case-insensitive; a leading {@code morph:} is accepted); -1 when none. */
    public int find(String name) {
        if (name == null) return -1;
        Integer exact = byName.get(name);
        if (exact != null) return exact;
        try (Arena arena = Arena.ofConfined()) {
            return (int) CendaRig.MORPHS_FIND.invokeExact(handle(), arena.allocateFrom(name));
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** The default weight of every target (what a pose without morph tracks carries). */
    public float[] defaultWeights() {
        float[] out = new float[targetCount];
        for (int t = 0; t < targetCount; t++) out[t] = targets.get(t).defaultWeight();
        return out;
    }

    /** One target's rows in ascending vertex order: {@code vertices}, 3 position and 3 normal floats per row. */
    public record Rows(int[] vertices, float[] deltas, float[] normals) {}

    public Rows targetRows(int target) {
        try (Arena arena = Arena.ofConfined()) {
            int n = (int) CendaRig.MORPHS_TARGET_ROWS.invokeExact(handle(), target, MemorySegment.NULL,
                MemorySegment.NULL, MemorySegment.NULL, 0);
            if (n < 0) throw new IllegalArgumentException("cr_morphs_target_rows: " + CendaRig.lastError());
            MemorySegment v = arena.allocate(ValueLayout.JAVA_INT, Math.max(1, n));
            MemorySegment d = arena.allocate(ValueLayout.JAVA_FLOAT, Math.max(3, n * 3L));
            MemorySegment nn = arena.allocate(ValueLayout.JAVA_FLOAT, Math.max(3, n * 3L));
            int got = (int) CendaRig.MORPHS_TARGET_ROWS.invokeExact(handle(), target, v, d, nn, n);
            if (got < 0) throw new IllegalArgumentException("cr_morphs_target_rows: " + CendaRig.lastError());
            return new Rows(v.asSlice(0, 4L * n).toArray(ValueLayout.JAVA_INT),
                d.asSlice(0, 12L * n).toArray(ValueLayout.JAVA_FLOAT),
                nn.asSlice(0, 12L * n).toArray(ValueLayout.JAVA_FLOAT));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** Per-thread scratch of {@link #clampWeights} (grown as needed, never freed). */
    private static final ThreadLocal<MemorySegment[]> SCRATCH = ThreadLocal.withInitial(() -> new MemorySegment[2]);

    private static MemorySegment scratch(int index, long floats) {
        MemorySegment[] s = SCRATCH.get();
        if (s[index] == null || s[index].byteSize() < 4L * floats) {
            s[index] = Arena.ofAuto().allocate(4L * Math.max(16, floats), 8);
        }
        return s[index];
    }

    /**
     * Weights as applied ({@code cr_morphs_clamp_weights}): {@code out[t] = clamp(weights[t], min_t, max_t)}; a missing
     * weight (beyond {@code weights.length}, or {@code weights} null) and NaN read as 0. {@code out} holds
     * {@link #targetCount()} floats (null = a new array). Allocation-free with an {@code out} array (the per-frame GPU
     * upload).
     */
    public float[] clampWeights(float[] weights, float[] out) {
        float[] result = out != null && out.length >= targetCount ? out : new float[targetCount];
        if (targetCount == 0) return result;
        int count = weights == null ? 0 : weights.length;
        MemorySegment in = scratch(0, Math.max(1, count));
        if (count > 0) MemorySegment.copy(weights, 0, in, CendaRig.F32, 0, count);
        MemorySegment o = scratch(1, targetCount);
        MemorySegment inArg = count == 0 ? MemorySegment.NULL : in;  // a typed local: see invokeExact
        try {
            int rc = (int) CendaRig.MORPHS_CLAMP_WEIGHTS.invokeExact(handle(), inArg, count, o);
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_morphs_clamp_weights: " + CendaRig.lastError());
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
        MemorySegment.copy(o, CendaRig.F32, 0, result, 0, targetCount);
        return result;
    }

    /**
     * CPU apply ({@code cr_morph_apply}): {@code outPositions = positions + sum_k w_k d_k} (clamped weights), and with
     * {@code normals} / {@code outNormals} both given the normals too (see rig.h). Arrays hold 3 floats per vertex.
     */
    public void apply(float[] weights, float[] positions, float[] normals, float[] outPositions, float[] outNormals) {
        int n = positions.length;
        boolean withNormals = normals != null && outNormals != null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment w = weights == null || weights.length == 0 ? MemorySegment.NULL
                : arena.allocateFrom(ValueLayout.JAVA_FLOAT, weights);
            MemorySegment p = arena.allocateFrom(ValueLayout.JAVA_FLOAT, positions);
            MemorySegment nIn = withNormals ? arena.allocateFrom(ValueLayout.JAVA_FLOAT, normals) : MemorySegment.NULL;
            MemorySegment op = arena.allocate(ValueLayout.JAVA_FLOAT, Math.max(1, n));
            MemorySegment on = withNormals ? arena.allocate(ValueLayout.JAVA_FLOAT, Math.max(1, n)) : MemorySegment.NULL;
            int wc = weights == null ? 0 : weights.length;
            int rc = (int) CendaRig.MORPH_APPLY.invokeExact(handle(), w, wc, p, nIn, op, on);
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_morph_apply: " + CendaRig.lastError());
            MemorySegment.copy(op, CendaRig.F32, 0, outPositions, 0, n);
            if (withNormals) MemorySegment.copy(on, CendaRig.F32, 0, outNormals, 0, n);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** The GPU packing (computed once, shared; do not modify its arrays). */
    public Packed pack() {
        Packed p = packed;
        if (p != null) return p;
        synchronized (this) {
            if (packed != null) return packed;
            try (Arena arena = Arena.ofConfined()) {
                int records = (int) CendaRig.MORPHS_PACK_SIZE.invokeExact(handle());
                if (records < 0) throw new IllegalStateException("cr_morphs_pack_size: " + CendaRig.lastError());
                boolean normals = hasNormals();
                MemorySegment r = arena.allocate(ValueLayout.JAVA_INT, Math.max(2, 2L * vertexCount));
                MemorySegment d = arena.allocate(ValueLayout.JAVA_FLOAT, Math.max(4, 4L * records));
                MemorySegment n = normals ? arena.allocate(ValueLayout.JAVA_FLOAT, Math.max(4, 4L * records))
                    : MemorySegment.NULL;
                int rc = (int) CendaRig.MORPHS_PACK.invokeExact(handle(), records, r, d, n);
                if (rc != CendaRig.OK) throw new IllegalStateException("cr_morphs_pack: " + CendaRig.lastError());
                packed = new Packed(vertexCount, targetCount, records,
                    r.asSlice(0, 8L * vertexCount).toArray(ValueLayout.JAVA_INT),
                    d.asSlice(0, 16L * records).toArray(ValueLayout.JAVA_FLOAT),
                    normals ? n.asSlice(0, 16L * records).toArray(ValueLayout.JAVA_FLOAT) : null);
                return packed;
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable t) {
                throw CendaRig.rethrow(t);
            }
        }
    }

    /** Frees the native set. Safe to call twice. */
    @Override
    public void close() {
        cleanable.clean();
    }

    // --- small growable primitive lists (marshalling only) -------------------------------------------------

    private static final class IntList {
        int[] a = new int[64];
        int size;

        void add(int v) {
            if (size == a.length) a = java.util.Arrays.copyOf(a, size * 2);
            a[size++] = v;
        }

        int[] toArray() {
            return java.util.Arrays.copyOf(a, size);
        }
    }

    private static final class FloatList {
        float[] a = new float[192];
        int size;

        void add(float x, float y, float z) {
            if (size + 3 > a.length) a = java.util.Arrays.copyOf(a, Math.max(a.length * 2, size + 3));
            a[size++] = x;
            a[size++] = y;
            a[size++] = z;
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(a, size);
        }
    }
}
