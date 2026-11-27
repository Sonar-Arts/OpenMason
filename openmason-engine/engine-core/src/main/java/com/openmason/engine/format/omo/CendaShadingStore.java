package com.openmason.engine.format.omo;

import com.openmason.engine.cenda.CendaKernels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Cleaner;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * FFM binding of the editor's native shading store ({@code co_shading_*} in {@code libcenda_kernels}; contract in
 * {@code cenda/native/kernels/include/cenda/model_shading.h}, OpenMason #169). One store holds ONE part's shading in
 * a form that survives a re-soup: smooth flags by face id, sharp edges and custom normals by welded position, the
 * auto smooth angle. Every rule (welding, key resolution, Blender corner normals, reconcile) is native; this class
 * marshals and owns the handle. Part of the {@code co_*} ABI, available exactly when {@link CendaOmo} is.
 *
 * <p>A store belongs to one thread at a time (the editor's main thread). Closing is optional: an unreachable store
 * is destroyed by a cleaner.
 */
public final class CendaShadingStore implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(CendaShadingStore.class);
    private static final Cleaner CLEANER = Cleaner.create();

    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;
    private static final ValueLayout.OfShort I16 = ValueLayout.JAVA_SHORT;
    private static final AddressLayout ADDR = ValueLayout.ADDRESS;

    /** {@code CO_SHADING_DEFAULT_WELD_EPSILON}: the editor's weld epsilon. */
    public static final float DEFAULT_WELD_EPSILON = CendaNormals.DEFAULT_WELD_EPSILON;

    private static final String FAILURE;
    private static final MethodHandle CREATE;
    private static final MethodHandle CLONE;
    private static final MethodHandle DESTROY;
    private static final MethodHandle WELD_EPSILON;
    private static final MethodHandle REVISION;
    private static final MethodHandle FROM_DOC;
    private static final MethodHandle TO_DOC;
    private static final MethodHandle SET_SMOOTH;
    private static final MethodHandle IS_SMOOTH;
    private static final MethodHandle SMOOTH_WORDS;
    private static final MethodHandle SET_AUTO_SMOOTH;
    private static final MethodHandle AUTO_SMOOTH;
    private static final MethodHandle MARK_SHARP;
    private static final MethodHandle CLEAR_ALL_SHARP;
    private static final MethodHandle SHARP_EDGES;
    private static final MethodHandle SET_CUSTOM_NORMALS;
    private static final MethodHandle CLEAR_CUSTOM_NORMALS;
    private static final MethodHandle CUSTOM_NORMALS;
    private static final MethodHandle COMPUTE;
    private static final MethodHandle RECONCILE;
    private static final MethodHandle MOVE_VERTICES;

    static {
        String failure = null;
        SymbolLookup lookup = null;
        if (!CendaOmo.isAvailable()) {
            failure = CendaOmo.unavailableReason();
        } else {
            try {
                Path lib = CendaKernels.libraryPath().orElseThrow();
                lookup = SymbolLookup.libraryLookup(lib, Arena.global());
            } catch (Throwable t) {
                failure = "Cenda shading store failed to load: " + t;
            }
        }
        if (failure != null) {
            LOGGER.error("Shading store unavailable: {}", failure);
        }
        FAILURE = failure;
        CREATE = handle(lookup, "co_shading_create", FunctionDescriptor.of(ADDR, F32));
        CLONE = handle(lookup, "co_shading_clone", FunctionDescriptor.of(ADDR, ADDR));
        DESTROY = handle(lookup, "co_shading_destroy", FunctionDescriptor.ofVoid(ADDR));
        WELD_EPSILON = handle(lookup, "co_shading_weld_epsilon", FunctionDescriptor.of(F32, ADDR));
        REVISION = handle(lookup, "co_shading_revision", FunctionDescriptor.of(I64, ADDR));
        FROM_DOC = handle(lookup, "co_shading_from_doc", FunctionDescriptor.of(ADDR, ADDR, ADDR, F32));
        TO_DOC = handle(lookup, "co_shading_to_doc", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        SET_SMOOTH = handle(lookup, "co_shading_set_smooth", FunctionDescriptor.of(I32, ADDR, ADDR, I64, I32));
        IS_SMOOTH = handle(lookup, "co_shading_is_smooth", FunctionDescriptor.of(I32, ADDR, I32));
        SMOOTH_WORDS = handle(lookup, "co_shading_smooth_words", FunctionDescriptor.of(I64, ADDR, ADDR));
        SET_AUTO_SMOOTH = handle(lookup, "co_shading_set_auto_smooth", FunctionDescriptor.of(I32, ADDR, F32));
        AUTO_SMOOTH = handle(lookup, "co_shading_auto_smooth", FunctionDescriptor.of(F32, ADDR));
        MARK_SHARP = handle(lookup, "co_shading_mark_sharp",
                FunctionDescriptor.of(I32, ADDR, ADDR, I64, ADDR, I64, I32));
        CLEAR_ALL_SHARP = handle(lookup, "co_shading_clear_all_sharp", FunctionDescriptor.of(I32, ADDR));
        SHARP_EDGES = handle(lookup, "co_shading_sharp_edges", FunctionDescriptor.of(I64, ADDR, ADDR, I64));
        SET_CUSTOM_NORMALS = handle(lookup, "co_shading_set_custom_normals",
                FunctionDescriptor.of(I32, ADDR, ADDR, I64, ADDR, I64, ADDR, ADDR, ADDR, I64));
        CLEAR_CUSTOM_NORMALS = handle(lookup, "co_shading_clear_custom_normals",
                FunctionDescriptor.of(I32, ADDR, ADDR, I64));
        CUSTOM_NORMALS = handle(lookup, "co_shading_custom_normals",
                FunctionDescriptor.of(I64, ADDR, ADDR, ADDR, ADDR, I64));
        COMPUTE = handle(lookup, "co_shading_compute",
                FunctionDescriptor.of(I32, ADDR, ADDR, I64, ADDR, I64, ADDR, ADDR, ADDR));
        RECONCILE = handle(lookup, "co_shading_reconcile",
                FunctionDescriptor.of(I32, ADDR, ADDR, I64, ADDR, I64, ADDR, ADDR));
        MOVE_VERTICES = handle(lookup, "co_shading_move_vertices",
                FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, I64));
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor fd) {
        if (lookup == null) return null;
        return lookup.find(name)
                .map(symbol -> Linker.nativeLinker().downcallHandle(symbol, fd))
                .orElseThrow(() -> new IllegalStateException("Cenda shading symbol missing: " + name));
    }

    /** True when the native store is usable (the model module loaded and passed its handshake). */
    public static boolean isAvailable() {
        return FAILURE == null;
    }

    /** Why the native store is unavailable, or null when it is available. */
    public static String unavailableReason() {
        return FAILURE;
    }

    private static void requireAvailable() {
        if (FAILURE != null) throw new IllegalStateException("Cenda shading store unavailable: " + FAILURE);
    }

    /**
     * One part's CURRENT soup (part-local vertex indices and face ids).
     *
     * @param positions  3 floats per soup vertex
     * @param indices    3 per triangle
     * @param triFaceIds face id per triangle, or null = triangle index
     */
    public record Soup(float[] positions, int[] indices, int[] triFaceIds) {
        public Soup {
            positions = positions == null ? new float[0] : positions;
            indices = indices == null ? new int[0] : indices;
        }

        public int vertexCount() {
            return positions.length / 3;
        }
    }

    /** Corner normals of a soup: unit xyz (3 floats) and octahedral snorm16 (2 shorts) per soup vertex. */
    public record Normals(float[] xyz, short[] oct) {
    }

    /** A stored custom normal key: its face, its welded position and its oct value. */
    public record CustomNormal(int face, float x, float y, float z, short octX, short octY) {
    }

    // --- Lifetime ---------------------------------------------------------------------------------------------

    private static final class State implements Runnable {
        private MemorySegment handle;

        State(MemorySegment handle) {
            this.handle = handle;
        }

        @Override
        public void run() {
            MemorySegment h = handle;
            handle = null;
            if (h != null && !h.equals(MemorySegment.NULL)) {
                try {
                    DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    private final State state;
    private final Cleaner.Cleanable cleanable;

    private CendaShadingStore(MemorySegment handle) {
        if (handle == null || handle.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("co_shading: " + CendaOmo.lastError());
        }
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
    }

    /** An empty store: every face flat, no sharp edges, no custom normals, auto smooth off. */
    public static CendaShadingStore create() {
        return create(DEFAULT_WELD_EPSILON);
    }

    /** An empty store with a weld epsilon ({@code <= 0} = the default). */
    public static CendaShadingStore create(float weldEpsilon) {
        requireAvailable();
        try {
            return new CendaShadingStore((MemorySegment) CREATE.invokeExact(weldEpsilon));
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /**
     * A store from a document's shading section ({@code co_shading_from_doc}): the whole mesh when {@code part} is
     * null, else that part's range (its vertex / index / face start + count; soup vertex v becomes
     * {@code v - vertexStart}, face f becomes {@code f - faceStart}). A document without shading gives an empty
     * (all flat) store.
     */
    public static CendaShadingStore fromDocument(OmoDocument doc, OMOFormat.PartEntry part) {
        requireAvailable();
        Objects.requireNonNull(doc, "doc");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = part == null ? MemorySegment.NULL : partStruct(arena, part);
            MemorySegment h = (MemorySegment) FROM_DOC.invokeExact(doc.h(), p, DEFAULT_WELD_EPSILON);
            if (h.equals(MemorySegment.NULL)) {
                throw new IllegalArgumentException("co_shading_from_doc: " + CendaOmo.lastError());
            }
            return new CendaShadingStore(h);
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** An independent copy (undo snapshots); keeps this store's revision. */
    public CendaShadingStore copy() {
        try {
            return new CendaShadingStore((MemorySegment) CLONE.invokeExact(h()));
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    @Override
    public void close() {
        cleanable.clean();
    }

    private MemorySegment h() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("CendaShadingStore is closed");
        return h;
    }

    // --- Queries ----------------------------------------------------------------------------------------------

    public float weldEpsilon() {
        try {
            return (float) WELD_EPSILON.invokeExact(h());
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** Bumped by every change (the "normals dirty" signal next to soup changes). */
    public long revision() {
        try {
            return (long) REVISION.invokeExact(h());
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    public boolean isSmooth(int faceId) {
        try {
            return (int) IS_SMOOTH.invokeExact(h(), faceId) == 1;
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** The smooth bitset over face ids (bit {@code f % 32} of word {@code f / 32}), trailing zero words trimmed. */
    public int[] smoothWords() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDR);
            long n = (long) SMOOTH_WORDS.invokeExact(h(), out);
            check((int) Math.min(0, n), "co_shading_smooth_words");
            return n <= 0 ? new int[0] : out.get(ADDR, 0).reinterpret(n * 4).toArray(I32);
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** True when any face is smooth. */
    public boolean anySmooth() {
        for (int w : smoothWords()) {
            if (w != 0) return true;
        }
        return false;
    }

    /** The auto smooth angle in degrees, 0 = off. */
    public float autoSmooth() {
        try {
            return (float) AUTO_SMOOTH.invokeExact(h());
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** The stored sharp edges: 6 floats per edge (a.xyz, b.xyz). */
    public float[] sharpEdges() {
        try (Arena arena = Arena.ofConfined()) {
            long n = (long) SHARP_EDGES.invokeExact(h(), MemorySegment.NULL, 0L);
            check((int) Math.min(0, n), "co_shading_sharp_edges");
            if (n <= 0) return new float[0];
            MemorySegment out = arena.allocate(F32, 6 * n);
            long got = (long) SHARP_EDGES.invokeExact(h(), out, n);
            return out.asSlice(0, 24L * Math.min(n, got)).toArray(F32);
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    public int sharpEdgeCount() {
        try {
            return (int) (long) SHARP_EDGES.invokeExact(h(), MemorySegment.NULL, 0L);
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** The stored custom normals. */
    public CustomNormal[] customNormals() {
        try (Arena arena = Arena.ofConfined()) {
            long n = (long) CUSTOM_NORMALS.invokeExact(h(), MemorySegment.NULL, MemorySegment.NULL,
                    MemorySegment.NULL, 0L);
            check((int) Math.min(0, n), "co_shading_custom_normals");
            if (n <= 0) return new CustomNormal[0];
            MemorySegment faces = arena.allocate(I32, n);
            MemorySegment pos = arena.allocate(F32, 3 * n);
            MemorySegment oct = arena.allocate(I16, 2 * n);
            long got = Math.min(n, (long) CUSTOM_NORMALS.invokeExact(h(), faces, pos, oct, n));
            CustomNormal[] out = new CustomNormal[(int) got];
            for (int i = 0; i < got; i++) {
                out[i] = new CustomNormal(faces.getAtIndex(I32, i),
                        pos.getAtIndex(F32, 3L * i), pos.getAtIndex(F32, 3L * i + 1), pos.getAtIndex(F32, 3L * i + 2),
                        oct.getAtIndex(I16, 2L * i), oct.getAtIndex(I16, 2L * i + 1));
            }
            return out;
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    public int customNormalCount() {
        try {
            return (int) (long) CUSTOM_NORMALS.invokeExact(h(), MemorySegment.NULL, MemorySegment.NULL,
                    MemorySegment.NULL, 0L);
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** True when nothing is stored (every face flat, no sharp edges, no custom normals, auto smooth off). */
    public boolean isEmpty() {
        return !anySmooth() && sharpEdgeCount() == 0 && customNormalCount() == 0 && autoSmooth() == 0f;
    }

    /** Same smooth set, auto smooth angle, sharp edges and custom normals (undo no-op detection). */
    public boolean contentEquals(CendaShadingStore other) {
        if (other == this) return true;
        if (other == null) return false;
        return Arrays.equals(smoothWords(), other.smoothWords())
                && Float.compare(autoSmooth(), other.autoSmooth()) == 0
                && Arrays.equals(sharpEdges(), other.sharpEdges())
                && Arrays.equals(customNormals(), other.customNormals());
    }

    // --- Edits ------------------------------------------------------------------------------------------------

    /** Shade the faces smooth ({@code true}) or flat. */
    public void setSmooth(int[] faceIds, boolean smooth) {
        if (faceIds == null || faceIds.length == 0) return;
        try (Arena arena = Arena.ofConfined()) {
            check((int) SET_SMOOTH.invokeExact(h(), arena.allocateFrom(I32, faceIds), (long) faceIds.length,
                    smooth ? 1 : 0), "co_shading_set_smooth");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** Auto smooth: degrees {@code > 0} = on, else off. */
    public void setAutoSmooth(float degrees) {
        try {
            check((int) SET_AUTO_SMOOTH.invokeExact(h(), degrees), "co_shading_set_auto_smooth");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /**
     * Marks ({@code sharp}) or clears the edges given as vertex pairs of {@code positions} (3 floats per vertex;
     * welded over the vertices given, no triangles needed).
     */
    public void markSharp(float[] positions, int[] pairs, boolean sharp) {
        if (pairs == null || pairs.length < 2) return;
        try (Arena arena = Arena.ofConfined()) {
            check((int) MARK_SHARP.invokeExact(h(), floats(arena, positions), (long) (positions.length / 3),
                    arena.allocateFrom(I32, pairs), (long) (pairs.length / 2), sharp ? 1 : 0),
                    "co_shading_mark_sharp");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    public void clearAllSharp() {
        try {
            check((int) CLEAR_ALL_SHARP.invokeExact(h()), "co_shading_clear_all_sharp");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /**
     * Sets custom normals per soup vertex of the current soup: {@code vertices[i]} gets the oct pair
     * {@code oct[2i], oct[2i+1]} (absolute object-space normals).
     */
    public void setCustomNormals(Soup soup, int[] vertices, short[] oct) {
        if (vertices == null || vertices.length == 0) return;
        try (Arena arena = Arena.ofConfined()) {
            check((int) SET_CUSTOM_NORMALS.invokeExact(h(), floats(arena, soup.positions()),
                    (long) soup.vertexCount(), ints(arena, soup.indices()), (long) soup.indices().length,
                    ints(arena, soup.triFaceIds()), arena.allocateFrom(I32, vertices),
                    arena.allocateFrom(I16, oct), (long) vertices.length), "co_shading_set_custom_normals");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** Clears the custom normals of the faces; null = all. */
    public void clearCustomNormals(int[] faceIds) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ids = faceIds == null ? MemorySegment.NULL : ints(arena, faceIds);
            check((int) CLEAR_CUSTOM_NORMALS.invokeExact(h(), ids, faceIds == null ? 0L : (long) faceIds.length),
                    "co_shading_clear_custom_normals");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    // --- Soup lifecycle ---------------------------------------------------------------------------------------

    /** Corner normals of the current soup with this store's shading ({@code co_shading_compute}). */
    public Normals compute(Soup soup) {
        int n = soup.vertexCount();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment xyz = arena.allocate(F32, Math.max(1, 3L * n));
            MemorySegment oct = arena.allocate(I16, Math.max(1, 2L * n));
            check((int) COMPUTE.invokeExact(h(), floats(arena, soup.positions()), (long) n,
                    ints(arena, soup.indices()), (long) soup.indices().length, ints(arena, soup.triFaceIds()),
                    xyz, oct), "co_shading_compute");
            return new Normals(xyz.asSlice(0, 12L * n).toArray(F32), oct.asSlice(0, 4L * n).toArray(I16));
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /**
     * After a topology edit: drops keys the soup no longer has, snaps the rest ({@code co_shading_reconcile}).
     *
     * @return {smooth bits, sharp edges, custom normals} dropped
     */
    public long[] reconcile(Soup soup) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dropped = arena.allocate(I64, 3);
            check((int) RECONCILE.invokeExact(h(), floats(arena, soup.positions()), (long) soup.vertexCount(),
                    ints(arena, soup.indices()), (long) soup.indices().length, ints(arena, soup.triFaceIds()),
                    dropped), "co_shading_reconcile");
            return dropped.toArray(I64);
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** Positions moved: keys at {@code oldPositions[i]} follow to {@code newPositions[i]} (3 floats each). */
    public void moveVertices(float[] oldPositions, float[] newPositions) {
        int n = Math.min(oldPositions.length, newPositions.length) / 3;
        if (n == 0) return;
        try (Arena arena = Arena.ofConfined()) {
            check((int) MOVE_VERTICES.invokeExact(h(), floats(arena, oldPositions), floats(arena, newPositions),
                    (long) n), "co_shading_move_vertices");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /**
     * Writes this store into the document's shading section, resolved against the document's CURRENT mesh
     * ({@code co_shading_to_doc}): the whole section when {@code part} is null, else only that part's share (the
     * part's vertex / index / face ranges; everyone else's is kept).
     */
    public void writeTo(OmoDocument doc, OMOFormat.PartEntry part) {
        Objects.requireNonNull(doc, "doc");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = part == null ? MemorySegment.NULL : partStruct(arena, part);
            check((int) TO_DOC.invokeExact(h(), doc.h(), p), "co_shading_to_doc");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    // --- Marshalling ------------------------------------------------------------------------------------------

    /** A {@code co_part} carrying only the ranges ({@code co_shading_*} ignores its strings and transform). */
    private static MemorySegment partStruct(Arena arena, OMOFormat.PartEntry p) {
        MemorySegment s = arena.allocate(CendaOmo.PART_BYTES, 8);
        s.set(I32, 80, p.vertexStart());
        s.set(I32, 84, p.vertexCount());
        s.set(I32, 88, p.indexStart());
        s.set(I32, 92, p.indexCount());
        s.set(I32, 96, p.faceStart());
        s.set(I32, 100, p.faceCount());
        s.set(I32, 104, p.visible() ? 1 : 0);
        s.set(I32, 108, p.locked() ? 1 : 0);
        return s;
    }

    private static MemorySegment ints(Arena arena, int[] values) {
        return values == null || values.length == 0 ? MemorySegment.NULL : arena.allocateFrom(I32, values);
    }

    private static MemorySegment floats(Arena arena, float[] values) {
        return values == null || values.length == 0 ? MemorySegment.NULL : arena.allocateFrom(F32, values);
    }

    private static void check(int rc, String what) {
        if (rc >= 0) return;
        String message = what + ": " + CendaOmo.lastError();
        if (rc == CendaOmo.ERR_ARG || rc == CendaOmo.ERR_RANGE) throw new IllegalArgumentException(message);
        throw new IllegalStateException(message);
    }
}
