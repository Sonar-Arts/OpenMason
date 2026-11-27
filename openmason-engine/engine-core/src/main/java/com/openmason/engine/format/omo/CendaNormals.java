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
import java.nio.file.Path;

/**
 * FFM binding of the native corner normals ({@code co_mesh_normals}, {@code co_oct_encode/decode} in
 * {@code libcenda_kernels}; contract and Blender semantics in {@code cenda/native/kernels/include/cenda/model_shading.h},
 * OpenMason #169). Part of the {@code co_*} ABI, so it is available exactly when {@link CendaOmo} is. All shading
 * logic is native; this class marshals.
 */
public final class CendaNormals {

    private static final Logger LOGGER = LoggerFactory.getLogger(CendaNormals.class);

    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;
    private static final AddressLayout ADDR = ValueLayout.ADDRESS;

    /** {@code CO_SHADING_DEFAULT_WELD_EPSILON} (the editor's weld epsilon); any epsilon {@code <= 0} means it. */
    public static final float DEFAULT_WELD_EPSILON = 1e-4f;

    private static final String FAILURE;
    private static final MethodHandle LAST_ERROR;
    private static final MethodHandle MESH_NORMALS;
    private static final MethodHandle OCT_ENCODE;
    private static final MethodHandle OCT_DECODE;

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
                failure = "Cenda model normals failed to load: " + t;
            }
        }
        if (failure != null) {
            LOGGER.error("Model normals unavailable: {}", failure);
        }
        FAILURE = failure;
        LAST_ERROR = handle(lookup, "co_shading_last_error", FunctionDescriptor.of(ADDR));
        MESH_NORMALS = handle(lookup, "co_mesh_normals", FunctionDescriptor.of(I32,
            ADDR, I64, ADDR, I64, ADDR, ADDR, ADDR, I64, ADDR, I64, F32, ADDR, ADDR, I64, F32, ADDR, ADDR));
        OCT_ENCODE = handle(lookup, "co_oct_encode", FunctionDescriptor.of(I32, ADDR, ADDR, I64));
        OCT_DECODE = handle(lookup, "co_oct_decode", FunctionDescriptor.of(I32, ADDR, ADDR, I64));
    }

    private CendaNormals() {
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor fd) {
        if (lookup == null) return null;
        return lookup.find(name)
            .map(symbol -> Linker.nativeLinker().downcallHandle(symbol, fd))
            .orElseThrow(() -> new IllegalStateException("Cenda model symbol missing: " + name));
    }

    /** True when the native normals are usable (the model module loaded and passed its handshake). */
    public static boolean isAvailable() {
        return FAILURE == null;
    }

    /** Why the native normals are unavailable, or null when they are available. */
    public static String unavailableReason() {
        return FAILURE;
    }

    /**
     * Corner normals of one soup.
     *
     * @param xyz unit normal per soup vertex (3 floats)
     * @param oct the same normals as octahedral snorm16 (2 shorts; custom rows verbatim)
     */
    public record Normals(float[] xyz, short[] oct) {
    }

    /**
     * {@code co_mesh_normals}: Blender corner normals for one soup (see model_shading.h for every rule).
     *
     * @param positions         3 floats per soup vertex
     * @param indices           3 per triangle
     * @param triFaceIds        face id per triangle, or null = every triangle its own face
     * @param triGroups         group per triangle (e.g. the part index: groups never weld), or null = one group
     * @param smoothWords       the smooth bitset over face ids (bit f % 32 of word f / 32); null = EVERY face smooth,
     *                          an empty array = every face flat
     * @param sharpPairs        soup vertex pairs of sharp edges (2 per edge), or null
     * @param autoSmoothDegrees {@code > 0} = auto smooth angle; 0 / NaN = off
     * @param customVertices    soup vertex per custom normal row, or null
     * @param customOct         2 oct values per custom row, or null
     * @param weldEpsilon       {@code <= 0} = {@link #DEFAULT_WELD_EPSILON}
     * @throws IllegalArgumentException with the native reason when the arguments are malformed
     */
    public static Normals meshNormals(float[] positions, int[] indices, int[] triFaceIds, int[] triGroups,
                                      int[] smoothWords, int[] sharpPairs, float autoSmoothDegrees,
                                      int[] customVertices, short[] customOct, float weldEpsilon) {
        requireAvailable();
        int vertexCount = positions == null ? 0 : positions.length / 3;
        int indexCount = indices == null ? 0 : indices.length;
        int customCount = customVertices == null ? 0 : customVertices.length;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = ints(arena, null);
            if (vertexCount > 0) p = arena.allocateFrom(F32, positions);
            MemorySegment smooth = smoothWords == null ? MemorySegment.NULL
                : arena.allocate(I32, Math.max(1, smoothWords.length));
            if (smoothWords != null && smoothWords.length > 0) {
                MemorySegment.copy(smoothWords, 0, smooth, I32, 0, smoothWords.length);
            }
            MemorySegment outXyz = arena.allocate(F32, Math.max(1, 3L * vertexCount));
            MemorySegment outOct = arena.allocate(ValueLayout.JAVA_SHORT, Math.max(1, 2L * vertexCount));
            MemorySegment custOct = customCount == 0 ? MemorySegment.NULL
                : arena.allocateFrom(ValueLayout.JAVA_SHORT, customOct);
            int rc = (int) MESH_NORMALS.invokeExact(p, (long) vertexCount, ints(arena, indices), (long) indexCount,
                ints(arena, triFaceIds), ints(arena, triGroups), smooth,
                smoothWords == null ? 0L : (long) smoothWords.length,
                ints(arena, sharpPairs), sharpPairs == null ? 0L : (long) (sharpPairs.length / 2),
                autoSmoothDegrees, ints(arena, customCount == 0 ? null : customVertices), custOct, (long) customCount,
                weldEpsilon, outXyz, outOct);
            if (rc != 0) throw new IllegalArgumentException("co_mesh_normals: " + lastError());
            return new Normals(outXyz.asSlice(0, 12L * vertexCount).toArray(F32),
                outOct.asSlice(0, 4L * vertexCount).toArray(ValueLayout.JAVA_SHORT));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** {@code co_oct_encode}: 3 floats per normal to 2 snorm16 values. */
    public static short[] octEncode(float[] xyz) {
        requireAvailable();
        int n = xyz.length / 3;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = arena.allocate(F32, Math.max(1, 3L * n));
            MemorySegment.copy(xyz, 0, in, F32, 0, 3 * n);
            MemorySegment out = arena.allocate(ValueLayout.JAVA_SHORT, Math.max(1, 2L * n));
            int rc = (int) OCT_ENCODE.invokeExact(in, out, (long) n);
            if (rc != 0) throw new IllegalArgumentException("co_oct_encode: " + lastError());
            return out.asSlice(0, 4L * n).toArray(ValueLayout.JAVA_SHORT);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** {@code co_oct_decode}: 2 snorm16 values per normal to a unit vector (3 floats). */
    public static float[] octDecode(short[] oct) {
        requireAvailable();
        int n = oct.length / 2;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = arena.allocate(ValueLayout.JAVA_SHORT, Math.max(1, 2L * n));
            MemorySegment.copy(oct, 0, in, ValueLayout.JAVA_SHORT, 0, 2 * n);
            MemorySegment out = arena.allocate(F32, Math.max(1, 3L * n));
            int rc = (int) OCT_DECODE.invokeExact(in, out, (long) n);
            if (rc != 0) throw new IllegalArgumentException("co_oct_decode: " + lastError());
            return out.asSlice(0, 12L * n).toArray(F32);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static MemorySegment ints(Arena arena, int[] values) {
        return values == null || values.length == 0 ? MemorySegment.NULL : arena.allocateFrom(I32, values);
    }

    private static void requireAvailable() {
        if (FAILURE != null) throw new IllegalStateException("Cenda model normals unavailable: " + FAILURE);
    }

    private static String lastError() {
        try {
            MemorySegment text = (MemorySegment) LAST_ERROR.invokeExact();
            return CendaOmo.string(text);
        } catch (Throwable t) {
            return "unknown error";
        }
    }
}
