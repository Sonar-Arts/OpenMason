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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A model's vertex-group MASK PLAN ({@code co_mask_plan}, contract and Blender semantics in
 * {@code cenda/native/kernels/include/cenda/model_mask.h}, OpenMason #172): built ONCE per model, it reorders the
 * triangles of the index buffer (inside their key segments, winding kept) so that for any choice of hidden / kept
 * groups the visible triangles are a few contiguous index ranges. Upload {@link #indices()} instead of the original
 * indices; per appearance change ask {@link #ranges} and cache them. Part of the {@code co_*} ABI (available exactly
 * when {@link CendaOmo} is); all logic is native, this class marshals.
 *
 * <p>HIDE g removes every triangle whose three vertices are all in g (a garment's covered skin: no gap at its edge);
 * KEEP g keeps only triangles inside g (Blender's Mask modifier). Immutable and thread-safe; freed by {@link #close()}
 * or, as a backstop, when unreachable.
 */
public final class MaskPlan implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaskPlan.class);
    private static final Cleaner CLEANER = Cleaner.create();

    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    private static final AddressLayout ADDR = ValueLayout.ADDRESS;

    /** {@code co_mask_range} / {@code co_mask_info} sizes (model_mask.h). */
    private static final long RANGE_BYTES = 16;
    private static final long INFO_BYTES = 32;

    /** {@code CO_MASK_MERGE_SEGMENTS}: visible ranges may span key segments. */
    public static final int MERGE_SEGMENTS = 1;

    private static final String FAILURE;
    private static final MethodHandle CREATE;
    private static final MethodHandle DESTROY;
    private static final MethodHandle INFO;
    private static final MethodHandle INDICES;
    private static final MethodHandle PERMUTATION;
    private static final MethodHandle GROUP_FIND;
    private static final MethodHandle GROUP_NAME;
    private static final MethodHandle RANGES;

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
                failure = "Cenda model masks failed to load: " + t;
            }
        }
        if (failure != null) {
            LOGGER.error("Model masks unavailable: {}", failure);
        }
        FAILURE = failure;
        CREATE = handle(lookup, "co_mask_plan_create",
            FunctionDescriptor.of(ADDR, ADDR, I64, I32, ADDR, I32, ADDR, ADDR, ADDR, ADDR));
        DESTROY = handle(lookup, "co_mask_plan_destroy", FunctionDescriptor.ofVoid(ADDR));
        INFO = handle(lookup, "co_mask_plan_info", FunctionDescriptor.of(I32, ADDR, ADDR));
        INDICES = handle(lookup, "co_mask_indices", FunctionDescriptor.of(I64, ADDR, ADDR));
        PERMUTATION = handle(lookup, "co_mask_permutation", FunctionDescriptor.of(I64, ADDR, ADDR));
        GROUP_FIND = handle(lookup, "co_mask_group_find", FunctionDescriptor.of(I32, ADDR, ADDR));
        GROUP_NAME = handle(lookup, "co_mask_group_name", FunctionDescriptor.of(ADDR, ADDR, I32));
        RANGES = handle(lookup, "co_mask_ranges",
            FunctionDescriptor.of(I32, ADDR, ADDR, I32, ADDR, I32, I32, ADDR, I32));
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor fd) {
        if (lookup == null) return null;
        return lookup.find(name)
            .map(symbol -> Linker.nativeLinker().downcallHandle(symbol, fd))
            .orElseThrow(() -> new IllegalStateException("Cenda model symbol missing: " + name));
    }

    /** True when the native masks are usable. */
    public static boolean isAvailable() {
        return FAILURE == null;
    }

    /** Why the native masks are unavailable, or null when they are available. */
    public static String unavailableReason() {
        return FAILURE;
    }

    /** {@code co_mask_info}. */
    public record Info(long triangleCount, int vertexCount, int groupCount, int segmentCount, int signatureCount,
                       int classCount) {}

    private final State state;
    private final Cleaner.Cleanable cleanable;
    private final Info info;
    private final List<String> groupNames;

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
                    DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    private MaskPlan(MemorySegment handle) {
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment i = arena.allocate(INFO_BYTES, 8);
            int rc = (int) INFO.invokeExact(handle, i);
            if (rc != CendaOmo.OK) throw new IllegalStateException("co_mask_plan_info: " + CendaOmo.lastError());
            this.info = new Info(i.get(I64, 0), i.get(I32, 8), i.get(I32, 12), i.get(I32, 16), i.get(I32, 20),
                i.get(I32, 24));
            List<String> names = new ArrayList<>(info.groupCount());
            for (int g = 0; g < info.groupCount(); g++) {
                String n = CendaOmo.string((MemorySegment) GROUP_NAME.invokeExact(handle, g));
                names.add(n == null ? "" : n);
            }
            this.groupNames = List.copyOf(names);
        } catch (RuntimeException e) {
            cleanable.clean();
            throw e;
        } catch (Throwable t) {
            cleanable.clean();
            throw CendaOmo.rethrow(t);
        }
    }

    /**
     * {@code co_mask_plan_create}.
     *
     * @param indices     3 per triangle, each in [0, vertexCount)
     * @param triKeys     one key per triangle (a draw batch: triangles never leave their key run), or null
     * @param names       group names (one per group)
     * @param groupRows   {@code names.size() + 1} CSR offsets into {@code vertices} / {@code weights}
     * @param vertices    soup vertex per row
     * @param weights     weight per row (a vertex is in its group when its weight is > 0), or null = all in
     * @throws IllegalArgumentException with the native reason
     */
    public static MaskPlan create(int[] indices, int vertexCount, int[] triKeys, List<String> names, int[] groupRows,
                                  int[] vertices, float[] weights) {
        if (FAILURE != null) throw new IllegalStateException("Cenda model masks unavailable: " + FAILURE);
        int groups = names == null ? 0 : names.size();
        if (groups > 0 && (groupRows == null || groupRows.length != groups + 1)) {
            throw new IllegalArgumentException("need " + (groups + 1) + " group row offsets");
        }
        try (Arena arena = Arena.ofConfined()) {
            int n = indices == null ? 0 : indices.length;
            MemorySegment idx = arena.allocateFrom(I32, n == 0 ? new int[3] : indices);
            MemorySegment keys = triKeys == null ? MemorySegment.NULL : arena.allocateFrom(I32,
                triKeys.length == 0 ? new int[1] : triKeys);
            MemorySegment nameSeg = MemorySegment.NULL;
            if (groups > 0) {
                nameSeg = arena.allocate(ADDR, groups);
                for (int g = 0; g < groups; g++) {
                    String s = names.get(g);
                    nameSeg.setAtIndex(ADDR, g, s == null ? MemorySegment.NULL : arena.allocateFrom(s));
                }
            }
            int rows = vertices == null ? 0 : vertices.length;
            MemorySegment rowSeg = groups == 0 ? MemorySegment.NULL : arena.allocateFrom(I32, groupRows);
            MemorySegment vSeg = arena.allocateFrom(I32, rows == 0 ? new int[1] : vertices);
            MemorySegment wSeg = weights == null ? MemorySegment.NULL
                : arena.allocateFrom(ValueLayout.JAVA_FLOAT, rows == 0 ? new float[1] : weights);
            MemorySegment h = (MemorySegment) CREATE.invokeExact(idx, (long) n, vertexCount, keys, groups, nameSeg,
                rowSeg, vSeg, wSeg);
            if (h.equals(MemorySegment.NULL)) {
                throw new IllegalArgumentException("co_mask_plan_create: " + CendaOmo.lastError());
            }
            return new MaskPlan(h);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    private MemorySegment h() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("MaskPlan is closed");
        return h;
    }

    public Info info() {
        return info;
    }

    /** Group names in group order. */
    public List<String> groupNames() {
        return groupNames;
    }

    public int groupCount() {
        return info.groupCount();
    }

    /** Group index by exact name (the first when names repeat); -1 when absent. */
    public int findGroup(String name) {
        if (name == null) return -1;
        try (Arena arena = Arena.ofConfined()) {
            return (int) GROUP_FIND.invokeExact(h(), arena.allocateFrom(name));
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** The reordered index buffer to upload (a copy; 3 per triangle, each triangle's corner order kept). */
    public int[] indices() {
        return borrowed(INDICES, 3);
    }

    /** {@code perm[slot]} = the ORIGINAL triangle stored at triangle slot {@code slot} ({@code out[s] = in[perm[s]]}). */
    public int[] permutation() {
        return borrowed(PERMUTATION, 1);
    }

    private int[] borrowed(MethodHandle mh, int perTriangle) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDR);
            long n = (long) mh.invokeExact(h(), out);
            if (n < 0) throw new IllegalStateException("co_mask: " + CendaOmo.lastError());
            if (n == 0) return new int[0];
            MemorySegment data = out.get(ADDR, 0).reinterpret(4L * n);
            return data.toArray(I32);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** The group bitset words of {@code names} (bit g % 64 of word g / 64); unknown names are ignored. */
    public long[] bits(Collection<String> names) {
        long[] words = new long[(groupCount() + 63) / 64];
        if (names != null) {
            for (String n : names) {
                int g = findGroup(n);
                if (g >= 0) words[g >>> 6] |= 1L << (g & 63);
            }
        }
        return words;
    }

    /**
     * The visible index ranges for the given HIDE and KEEP group bitsets ({@code co_mask_ranges}, buffer order):
     * {@code first, count} pairs (in indices; a range never crosses a key segment unless {@code flags} has
     * {@link #MERGE_SEGMENTS}). An empty array = nothing visible. Call once per appearance change and cache it.
     */
    public int[] ranges(long[] hide, long[] keep, int flags) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment hs = hide == null || hide.length == 0 ? MemorySegment.NULL : arena.allocateFrom(I64, hide);
            MemorySegment ks = keep == null || keep.length == 0 ? MemorySegment.NULL : arena.allocateFrom(I64, keep);
            int cap = Math.max(1, info.classCount());
            MemorySegment out = arena.allocate(RANGE_BYTES * cap, 4);
            int hn = hide == null ? 0 : hide.length;
            int kn = keep == null ? 0 : keep.length;
            int n = (int) RANGES.invokeExact(h(), hs, hn, ks, kn, flags, out, cap);
            if (n < 0) throw new IllegalArgumentException("co_mask_ranges: " + CendaOmo.lastError());
            int got = Math.min(n, cap);
            int[] pairs = new int[got * 2];
            for (int i = 0; i < got; i++) {
                pairs[i * 2] = out.get(I32, RANGE_BYTES * i);
                pairs[i * 2 + 1] = out.get(I32, RANGE_BYTES * i + 4);
            }
            return pairs;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** {@link #ranges(long[], long[], int)} by group names (unknown names ignored), segments kept apart. */
    public int[] ranges(Collection<String> hide, Collection<String> keep) {
        return ranges(bits(hide), bits(keep), 0);
    }

    /** Frees the native plan. Safe to call twice. */
    @Override
    public void close() {
        cleanable.clean();
    }
}
