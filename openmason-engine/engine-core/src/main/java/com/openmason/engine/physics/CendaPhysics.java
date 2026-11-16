package com.openmason.engine.physics;

import com.openmason.engine.cenda.CendaKernels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * FFM symbols of Cenda physics ({@code cp_*} in {@code libcenda_kernels}, contract in
 * {@code cenda/native/kernels/include/cenda/physics.h}, OpenMason #117). Only the library handshake
 * and the method handles live here; {@link PhysicsWorld} is the API.
 *
 * <p>Physics has no Java fallback: when the library is missing or fails the {@code cp_abi_version}
 * handshake, {@link #isAvailable()} is false, {@link #unavailableReason()} says why, and
 * {@link PhysicsWorld#create} throws with that reason.
 */
public final class CendaPhysics {

    private static final Logger LOGGER = LoggerFactory.getLogger(CendaPhysics.class);

    /** Must equal {@code CP_ABI_VERSION} in physics.h (build-kernels.sh checks both sides). */
    static final int EXPECTED_ABI = 1;

    static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;
    static final ValueLayout.OfShort I16 = ValueLayout.JAVA_SHORT;
    static final java.lang.foreign.AddressLayout ADDR = ValueLayout.ADDRESS;

    // struct sizes (physics.h)
    static final long BOX_BYTES = 24;
    static final long FLUID_BYTES = 24;
    static final long DESC_BYTES = 32;
    static final long STATS_BYTES = 40;
    static final long BODY_BYTES = 64;
    static final long INTEGRATOR_BYTES = 32;
    static final long SAMPLE_BYTES = 24;
    static final long HIT_BYTES = 48;

    private static final String FAILURE;
    static final MethodHandle LAST_ERROR;
    static final MethodHandle WORLD_CREATE;
    static final MethodHandle WORLD_DESTROY;
    static final MethodHandle SHAPE_DEFINE;
    static final MethodHandle FLUID_DEFINE;
    static final MethodHandle INVALIDATE_CELL;
    static final MethodHandle INVALIDATE_BOX;
    static final MethodHandle INVALIDATE_ALL;
    static final MethodHandle CELL_GET;
    static final MethodHandle STATS_GET;
    static final MethodHandle BOX_ADD;
    static final MethodHandle BOX_SET;
    static final MethodHandle BOX_REMOVE;
    static final MethodHandle BOX_CLEAR;
    static final MethodHandle CELL_TOP;
    static final MethodHandle OVERLAPS;
    static final MethodHandle NEAREST_OPEN_CELL;
    static final MethodHandle MOVE;
    static final MethodHandle MOVE_MANY;
    static final MethodHandle FLUID_MEASURE;
    static final MethodHandle BUOYANCY_VY;
    static final MethodHandle STEP;
    static final MethodHandle RAYCAST;
    static final MethodHandle SWEEP;
    static final MethodHandle RAY_BOXES;

    /** {@code cp_fill_fn}: (user, x0, y0, z0, sx, sy, sz, shapes, fluids) -> int. */
    static final FunctionDescriptor FILL_FN =
        FunctionDescriptor.of(I32, ADDR, I32, I32, I32, I32, I32, I32, ADDR, ADDR);

    static {
        String failure = null;
        SymbolLookup lookup = null;
        try {
            Path lib = CendaKernels.libraryPath().orElse(null);
            if (lib == null) {
                failure = "Cenda library not found (searched -Dcenda.kernels.path, CENDA_KERNELS_PATH, the build "
                    + "folders and the copy packaged in engine-core); build it with openmason-engine/cenda/build-kernels.sh";
            } else {
                lookup = SymbolLookup.libraryLookup(lib, Arena.global());
                MethodHandle abi = Linker.nativeLinker().downcallHandle(
                    lookup.find("cp_abi_version").orElseThrow(
                        () -> new IllegalStateException("library has no physics (cp_abi_version missing)")),
                    FunctionDescriptor.of(I32));
                int have = (int) abi.invokeExact();
                if (have != EXPECTED_ABI) {
                    failure = "Cenda physics at " + lib + " has ABI " + have + " (expected " + EXPECTED_ABI
                        + "); rebuild with openmason-engine/cenda/build-kernels.sh";
                    lookup = null;
                } else {
                    LOGGER.info("Cenda physics (ABI {}) loaded from {}", have, lib);
                }
            }
        } catch (Throwable t) {
            failure = "Cenda physics failed to load: " + t;
            lookup = null;
        }
        if (failure != null) {
            LOGGER.error("Physics unavailable: {}", failure);
        }
        FAILURE = failure;
        // Calls that can reach the fill upcall must NOT be critical (an upcall needs a normal
        // transition); the pure ones can be.
        Linker.Option critical = Linker.Option.critical(false);
        LAST_ERROR = handle(lookup, "cp_last_error", FunctionDescriptor.of(ADDR));
        WORLD_CREATE = handle(lookup, "cp_world_create", FunctionDescriptor.of(ADDR, ADDR));
        WORLD_DESTROY = handle(lookup, "cp_world_destroy", FunctionDescriptor.ofVoid(ADDR));
        SHAPE_DEFINE = handle(lookup, "cp_shape_define", FunctionDescriptor.of(I32, ADDR, I32, ADDR, I32, I32, I32), critical);
        FLUID_DEFINE = handle(lookup, "cp_fluid_define", FunctionDescriptor.of(I32, ADDR, I32, ADDR), critical);
        INVALIDATE_CELL = handle(lookup, "cp_invalidate_cell", FunctionDescriptor.ofVoid(ADDR, I32, I32, I32));
        INVALIDATE_BOX = handle(lookup, "cp_invalidate_box",
            FunctionDescriptor.ofVoid(ADDR, I32, I32, I32, I32, I32, I32));
        INVALIDATE_ALL = handle(lookup, "cp_invalidate_all", FunctionDescriptor.ofVoid(ADDR));
        CELL_GET = handle(lookup, "cp_cell_get", FunctionDescriptor.of(I32, ADDR, I32, I32, I32, ADDR, ADDR));
        STATS_GET = handle(lookup, "cp_stats_get", FunctionDescriptor.ofVoid(ADDR, ADDR), critical);
        BOX_ADD = handle(lookup, "cp_box_add", FunctionDescriptor.of(I32, ADDR, ADDR, I32, I32, I64), critical);
        BOX_SET = handle(lookup, "cp_box_set", FunctionDescriptor.of(I32, ADDR, I32, ADDR, I32, I32), critical);
        BOX_REMOVE = handle(lookup, "cp_box_remove", FunctionDescriptor.of(I32, ADDR, I32), critical);
        BOX_CLEAR = handle(lookup, "cp_box_clear", FunctionDescriptor.ofVoid(ADDR), critical);
        CELL_TOP = handle(lookup, "cp_cell_top",
            FunctionDescriptor.of(F32, ADDR, I32, I32, I32, F32, F32, F32, F32, I32));
        OVERLAPS = handle(lookup, "cp_overlaps", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        NEAREST_OPEN_CELL = handle(lookup, "cp_nearest_open_cell",
            FunctionDescriptor.of(I32, ADDR, ADDR, I32, ADDR, I32));
        MOVE = handle(lookup, "cp_move", FunctionDescriptor.of(I32, ADDR, ADDR, F32));
        MOVE_MANY = handle(lookup, "cp_move_many", FunctionDescriptor.of(I32, ADDR, ADDR, I32, F32));
        FLUID_MEASURE = handle(lookup, "cp_fluid_measure",
            FunctionDescriptor.of(I32, ADDR, F32, F32, F32, F32, F32, F32, F32, I32, ADDR));
        BUOYANCY_VY = handle(lookup, "cp_buoyancy_vy", FunctionDescriptor.of(F32, F32, F32, F32, ADDR), critical);
        STEP = handle(lookup, "cp_step", FunctionDescriptor.of(I32, ADDR, ADDR, I32, F32, ADDR, ADDR));
        RAYCAST = handle(lookup, "cp_raycast", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, F32, I32, I32, ADDR));
        SWEEP = handle(lookup, "cp_sweep", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, F32, F32, F32, I32, ADDR));
        RAY_BOXES = handle(lookup, "cp_ray_boxes", FunctionDescriptor.of(I32, ADDR, ADDR, F32, ADDR, I32, F32, ADDR),
            critical);
    }

    private CendaPhysics() {
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor fd, Linker.Option... options) {
        if (lookup == null) return null;
        return lookup.find(name)
            .map(symbol -> Linker.nativeLinker().downcallHandle(symbol, fd, options))
            .orElseThrow(() -> new IllegalStateException("Cenda physics symbol missing: " + name));
    }

    /** True when native physics loaded and passed its handshake. */
    public static boolean isAvailable() {
        return FAILURE == null;
    }

    /** Why native physics is unavailable, or null when it is available. */
    public static String unavailableReason() {
        return FAILURE;
    }

    static String lastError() {
        try {
            MemorySegment text = (MemorySegment) LAST_ERROR.invokeExact();
            return text.reinterpret(1024).getString(0);
        } catch (Throwable t) {
            return "unknown error";
        }
    }

    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }
}
