package com.openmason.engine.voxel.world;

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
 * FFM symbols of the Cenda voxel world ({@code cv_*} in {@code libcenda_kernels}, contract in
 * {@code cenda/native/kernels/include/cenda/voxel_world.h}, OpenMason #141/#143/#144/#120). Only the
 * library handshake and the method handles live here; {@link CubeWorld}, {@link CubeStore} and
 * {@link CubeMesh} are the API.
 *
 * <p>The voxel world has no Java fallback: when the library is missing or fails the {@code cv_abi_version}
 * handshake, {@link #isAvailable()} is false, {@link #unavailableReason()} says why, and
 * {@link CubeWorld#create} throws with that reason.
 *
 * <p>Every {@code cv_*} symbol of the contract is bound here, including the mesher, host model, fluid,
 * streamer and store sections, so later phases only add API methods. The handles of those later sections
 * are package-visible ({@code MESH_CUBE}, {@code STREAM_*}, {@code MODEL_DEFINE}, ...). A symbol the
 * library does not export yet leaves its handle null; {@link #require} turns that into a clear error at
 * the call.
 *
 * <p>Value layouts: {@code uint16_t} parameters are {@link ValueLayout#JAVA_CHAR} (zero-extended, as the
 * C ABIs that extend narrow arguments expect for unsigned types); {@code uint32_t} ones are passed as int.
 */
public final class CendaVoxelWorld {

    private static final Logger LOGGER = LoggerFactory.getLogger(CendaVoxelWorld.class);

    /** Must equal {@code CV_ABI_VERSION} in voxel_world.h (build-kernels.sh checks both sides). */
    static final int EXPECTED_ABI = 1;

    static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    static final ValueLayout.OfShort I16 = ValueLayout.JAVA_SHORT;
    static final ValueLayout.OfChar U16 = ValueLayout.JAVA_CHAR;
    static final AddressLayout ADDR = ValueLayout.ADDRESS;

    // struct sizes (voxel_world.h, checked with offsetof on the C side)
    static final long WORLD_DESC_BYTES = 40;
    static final long QUAD_BYTES = 12;
    static final long HOST_CELL_BYTES = 4;
    static final long MESH_OUT_BYTES = 72;
    static final long MODEL_QUAD_BYTES = 96;
    static final long FLUID_DESC_BYTES = 40;
    static final long GPU_MESH_BYTES = 144;
    static final long CENTER_BYTES = 40;
    static final long BUDGET_BYTES = 32;
    static final long EVENT_BYTES = 32;
    static final long STREAM_DESC_BYTES = 56;
    static final long STREAM_STATS_BYTES = 248;
    static final long GENERATOR_BYTES = 72;
    // octree LOD (#122), checked with static_assert in lod.cpp
    static final long LOD_DESC_BYTES = 80;
    static final long LOD_POLICY_BYTES = 32;
    static final long LOD_VIEWER_BYTES = 24;
    static final long LOD_BUDGET_BYTES = 32;
    static final long LOD_EVENT_BYTES = 40;
    static final long LOD_MESH_BYTES = 128;
    static final long LOD_STATS_BYTES = 192;

    static final int CUBE_CELLS = 32768;
    static final int COLUMN_CELLS = 1024;

    // error codes
    static final int OK = 0;
    static final int ERR_ARG = -1;
    static final int ERR_MISSING = -2;
    static final int ERR_EXISTS = -3;
    static final int ERR_CORRUPT = -4;
    static final int ERR_IO = -5;

    static boolean isNeed(long r) {
        return r <= -16;
    }

    static long needed(long r) {
        return -r - 16;
    }

    /** {@code cv_surface_fn}: (user, cx, cz, out_sky, out_top) -> int. */
    static final FunctionDescriptor SURFACE_FN = FunctionDescriptor.of(I32, ADDR, I64, I64, ADDR, ADDR);

    private static final String FAILURE;
    private static final SymbolLookup LOOKUP;

    // world + blocks
    static final MethodHandle LAST_ERROR;
    static final MethodHandle WORLD_CREATE;
    static final MethodHandle WORLD_DESTROY;
    /** {@code cv_world_create_with_generator} (2026-11-17): may be null in an older library. */
    static final MethodHandle WORLD_CREATE_WITH_GENERATOR;
    static final MethodHandle BLOCK_TABLE_SET;
    static final MethodHandle CUBE_CREATE;
    static final MethodHandle CUBE_REMOVE;
    static final MethodHandle CUBE_EXISTS;
    static final MethodHandle CUBE_COUNT;
    static final MethodHandle CUBE_LIST;
    static final MethodHandle CUBE_WRITE_IDS;
    static final MethodHandle CUBE_READ_IDS;
    static final MethodHandle CUBE_ENCODE;
    static final MethodHandle CUBE_DECODE;
    static final MethodHandle CUBE_UNIFORM;
    static final MethodHandle GET;
    static final MethodHandle SET;
    static final MethodHandle READ_BOX;
    // layers + blob
    static final MethodHandle LAYER_DEFINE;
    static final MethodHandle LAYER_GET;
    static final MethodHandle LAYER_SET;
    static final MethodHandle LAYER_READ_CUBE;
    static final MethodHandle LAYER_WRITE_CUBE;
    static final MethodHandle LAYER_SCAN;
    static final MethodHandle CUBE_SET_BLOB;
    static final MethodHandle CUBE_GET_BLOB;
    // revisions + dirt
    static final MethodHandle WORLD_REVISION;
    static final MethodHandle CUBE_REVISION;
    static final MethodHandle DRAIN_DIRTY;
    // surface tracker
    static final MethodHandle SURFACE_Y;
    static final MethodHandle SURFACE_COLUMN;
    static final MethodHandle TOP_Y;
    static final MethodHandle TOP_RANGE;
    static final MethodHandle COLUMN_ENCODE;
    static final MethodHandle COLUMN_DECODE;
    static final MethodHandle COLUMN_REMOVE;
    // mesher + visibility (#143)
    static final MethodHandle MESH_CUBE;
    static final MethodHandle CUBE_VISIBILITY;
    static final MethodHandle VISIBLE_CUBES;
    // host models + fluids
    static final MethodHandle MODEL_DEFINE;
    static final MethodHandle BLOCK_MODEL_BIND;
    static final MethodHandle FLUID_SET;
    static final MethodHandle BLOCK_FACES_SET;
    static final MethodHandle FACE_RULE_ADD;
    static final MethodHandle FACE_RULES_CLEAR;
    // streamer (#121)
    static final MethodHandle STREAM_CREATE;
    static final MethodHandle STREAM_DESTROY;
    static final MethodHandle STREAM_SET_CENTERS;
    static final MethodHandle STREAM_TICK;
    static final MethodHandle STREAM_MESH;
    static final MethodHandle STREAM_RELEASE_MESH;
    static final MethodHandle STREAM_SAVE_ALL;
    static final MethodHandle STREAM_STATS_GET;
    static final MethodHandle STREAM_MISSING;
    static final MethodHandle STREAM_REMESH_ALL;
    static final MethodHandle STREAM_SET_DETAIL_RADIUS;
    static final MethodHandle TEST_TOY_GENERATOR_CREATE;
    static final MethodHandle TEST_TOY_GENERATOR_DESTROY;
    // octree LOD (#122)
    static final MethodHandle LOD_CREATE;
    static final MethodHandle LOD_DESTROY;
    static final MethodHandle LOD_BLOCK_CLASSES;
    static final MethodHandle LOD_NOTE_EVENTS;
    static final MethodHandle LOD_NOTE_CUBES;
    static final MethodHandle LOD_SET_COLUMN_READY;
    static final MethodHandle LOD_SET_VIEW;
    static final MethodHandle LOD_TICK;
    static final MethodHandle LOD_MESH_GET;
    static final MethodHandle LOD_READ_NODE;
    static final MethodHandle LOD_BUILD_MESH;
    static final MethodHandle LOD_WAIT_IDLE;
    static final MethodHandle LOD_FLUSH;
    static final MethodHandle LOD_STATS_GET;
    // region store (#120)
    static final MethodHandle STORE_OPEN;
    static final MethodHandle STORE_CLOSE;
    static final MethodHandle STORE_FLUSH;
    static final MethodHandle STORE_READ_CUBE;
    static final MethodHandle STORE_WRITE_CUBE;
    static final MethodHandle STORE_READ_COLUMN;
    static final MethodHandle STORE_WRITE_COLUMN;

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
                    lookup.find("cv_abi_version").orElseThrow(
                        () -> new IllegalStateException("library has no voxel world (cv_abi_version missing)")),
                    FunctionDescriptor.of(I32));
                int have = (int) abi.invokeExact();
                if (have != EXPECTED_ABI) {
                    failure = "Cenda voxel world at " + lib + " has ABI " + have + " (expected " + EXPECTED_ABI
                        + "); rebuild with openmason-engine/cenda/build-kernels.sh";
                    lookup = null;
                } else {
                    LOGGER.info("Cenda voxel world (ABI {}) loaded from {}", have, lib);
                }
            }
        } catch (Throwable t) {
            failure = "Cenda voxel world failed to load: " + t;
            lookup = null;
        }
        if (failure != null) {
            LOGGER.error("Voxel world unavailable: {}", failure);
        }
        FAILURE = failure;
        LOOKUP = lookup;

        // Critical (no thread-state transition) only for calls that are short, never block on a cube lock
        // for long and never reach the surface upcall.
        Linker.Option critical = Linker.Option.critical(false);
        FunctionDescriptor cube3 = FunctionDescriptor.of(I32, ADDR, I64, I64, I64);

        LAST_ERROR = core("cv_last_error", FunctionDescriptor.of(ADDR));
        WORLD_CREATE = core("cv_world_create", FunctionDescriptor.of(ADDR, ADDR));
        WORLD_DESTROY = core("cv_world_destroy", FunctionDescriptor.ofVoid(ADDR));
        WORLD_CREATE_WITH_GENERATOR = optional("cv_world_create_with_generator", FunctionDescriptor.of(ADDR, ADDR, ADDR));
        BLOCK_TABLE_SET = core("cv_block_table_set", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        CUBE_CREATE = core("cv_cube_create", FunctionDescriptor.of(I32, ADDR, I64, I64, I64, U16));
        CUBE_REMOVE = core("cv_cube_remove", cube3);
        CUBE_EXISTS = core("cv_cube_exists", cube3);
        CUBE_COUNT = core("cv_cube_count", FunctionDescriptor.of(I64, ADDR), critical);
        CUBE_LIST = core("cv_cube_list", FunctionDescriptor.of(I64, ADDR, ADDR, I64));
        CUBE_WRITE_IDS = core("cv_cube_write_ids", FunctionDescriptor.of(I32, ADDR, I64, I64, I64, ADDR));
        CUBE_READ_IDS = core("cv_cube_read_ids", FunctionDescriptor.of(I32, ADDR, I64, I64, I64, ADDR));
        CUBE_ENCODE = core("cv_cube_encode", FunctionDescriptor.of(I64, ADDR, I64, I64, I64, I32, ADDR, I64));
        CUBE_DECODE = core("cv_cube_decode", FunctionDescriptor.of(I32, ADDR, I64, I64, I64, ADDR, I64));
        CUBE_UNIFORM = core("cv_cube_uniform", FunctionDescriptor.of(I32, ADDR, I64, I64, I64, ADDR));
        GET = core("cv_get", cube3);
        SET = core("cv_set", FunctionDescriptor.of(I32, ADDR, I64, I64, I64, U16));
        READ_BOX = core("cv_read_box", FunctionDescriptor.of(I64, ADDR, I64, I64, I64, I32, I32, I32, ADDR, U16));

        LAYER_DEFINE = core("cv_layer_define", FunctionDescriptor.of(I32, ADDR, I32, I32));
        LAYER_GET = core("cv_layer_get", FunctionDescriptor.of(I32, ADDR, I32, I64, I64, I64));
        LAYER_SET = core("cv_layer_set", FunctionDescriptor.of(I32, ADDR, I32, I64, I64, I64, U16));
        LAYER_READ_CUBE = core("cv_layer_read_cube", FunctionDescriptor.of(I32, ADDR, I32, I64, I64, I64, ADDR));
        LAYER_WRITE_CUBE = core("cv_layer_write_cube", FunctionDescriptor.of(I32, ADDR, I32, I64, I64, I64, ADDR));
        LAYER_SCAN = core("cv_layer_scan", FunctionDescriptor.of(I64, ADDR, I32, I64, I64, I64, ADDR, ADDR, I64));
        CUBE_SET_BLOB = core("cv_cube_set_blob", FunctionDescriptor.of(I32, ADDR, I64, I64, I64, ADDR, I64));
        CUBE_GET_BLOB = core("cv_cube_get_blob", FunctionDescriptor.of(I64, ADDR, I64, I64, I64, ADDR, I64));

        WORLD_REVISION = core("cv_world_revision", FunctionDescriptor.of(I64, ADDR), critical);
        CUBE_REVISION = core("cv_cube_revision", FunctionDescriptor.of(I64, ADDR, I64, I64, I64));
        DRAIN_DIRTY = core("cv_drain_dirty", FunctionDescriptor.of(I64, ADDR, ADDR, I64));

        SURFACE_Y = core("cv_surface_y", FunctionDescriptor.of(I32, ADDR, I64, I64, ADDR));
        SURFACE_COLUMN = core("cv_surface_column", FunctionDescriptor.of(I32, ADDR, I64, I64, ADDR));
        TOP_Y = core("cv_top_y", FunctionDescriptor.of(I32, ADDR, I64, I64, ADDR));
        TOP_RANGE = core("cv_top_range", FunctionDescriptor.of(I32, ADDR, I64, I64, ADDR, ADDR, ADDR));
        COLUMN_ENCODE = core("cv_column_encode", FunctionDescriptor.of(I64, ADDR, I64, I64, ADDR, I64));
        COLUMN_DECODE = core("cv_column_decode", FunctionDescriptor.of(I32, ADDR, I64, I64, ADDR, I64));
        COLUMN_REMOVE = core("cv_column_remove", FunctionDescriptor.of(I32, ADDR, I64, I64));

        MESH_CUBE = optional("cv_mesh_cube", FunctionDescriptor.of(I32, ADDR, I64, I64, I64, I32, ADDR));
        CUBE_VISIBILITY = optional("cv_cube_visibility", cube3);
        VISIBLE_CUBES = optional("cv_visible_cubes",
            FunctionDescriptor.of(I64, ADDR, I64, I64, I64, I32, ADDR, ADDR, I64, ADDR, I64, ADDR));

        MODEL_DEFINE = optional("cv_model_define", FunctionDescriptor.of(I32, ADDR, I32, ADDR, I32));
        BLOCK_MODEL_BIND = optional("cv_block_model_bind", FunctionDescriptor.of(I32, ADDR, U16, I32, U16, I32));
        FLUID_SET = optional("cv_fluid_set", FunctionDescriptor.of(I32, ADDR, ADDR));
        BLOCK_FACES_SET = optional("cv_block_faces_set", FunctionDescriptor.of(I32, ADDR, U16, ADDR));
        FACE_RULE_ADD = optional("cv_face_rule_add",
            FunctionDescriptor.of(I32, ADDR, U16, I32, U16, ValueLayout.JAVA_BYTE, I32, ADDR, I32));
        FACE_RULES_CLEAR = optional("cv_face_rules_clear", FunctionDescriptor.of(I32, ADDR, I32));

        STREAM_CREATE = optional("cv_stream_create", FunctionDescriptor.of(ADDR, ADDR, ADDR));
        STREAM_DESTROY = optional("cv_stream_destroy", FunctionDescriptor.of(I32, ADDR));
        STREAM_SET_CENTERS = optional("cv_stream_set_centers", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        STREAM_TICK = optional("cv_stream_tick", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        STREAM_MESH = optional("cv_stream_mesh", FunctionDescriptor.of(ADDR, ADDR, I32));
        STREAM_RELEASE_MESH = optional("cv_stream_release_mesh", FunctionDescriptor.ofVoid(ADDR, I32));
        STREAM_SAVE_ALL = optional("cv_stream_save_all", FunctionDescriptor.of(I32, ADDR));
        STREAM_STATS_GET = optional("cv_stream_stats_get", FunctionDescriptor.ofVoid(ADDR, ADDR));
        STREAM_MISSING = optional("cv_stream_missing", FunctionDescriptor.of(I64, ADDR, ADDR, I64));
        STREAM_REMESH_ALL = optional("cv_stream_remesh_all", FunctionDescriptor.of(I64, ADDR));
        STREAM_SET_DETAIL_RADIUS = optional("cv_stream_set_detail_radius", FunctionDescriptor.of(I32, ADDR, I32));
        TEST_TOY_GENERATOR_CREATE = optional("cv_test_toy_generator_create", FunctionDescriptor.of(ADDR, I64));
        TEST_TOY_GENERATOR_DESTROY = optional("cv_test_toy_generator_destroy", FunctionDescriptor.ofVoid(ADDR));

        LOD_CREATE = optional("cv_lod_create", FunctionDescriptor.of(ADDR, ADDR, ADDR));
        LOD_DESTROY = optional("cv_lod_destroy", FunctionDescriptor.of(I32, ADDR));
        LOD_BLOCK_CLASSES = optional("cv_lod_block_classes", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        LOD_NOTE_EVENTS = optional("cv_lod_note_events", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        LOD_NOTE_CUBES = optional("cv_lod_note_cubes", FunctionDescriptor.of(I32, ADDR, ADDR, I64));
        LOD_SET_COLUMN_READY = optional("cv_lod_set_column_ready", FunctionDescriptor.of(I32, ADDR, I64, I64, I32));
        LOD_SET_VIEW = optional("cv_lod_set_view", FunctionDescriptor.of(I32, ADDR, ADDR, I32, ADDR));
        LOD_TICK = optional("cv_lod_tick", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        LOD_MESH_GET = optional("cv_lod_mesh_get", FunctionDescriptor.of(ADDR, ADDR, I32));
        LOD_READ_NODE = optional("cv_lod_read_node",
            FunctionDescriptor.of(I32, ADDR, I32, I64, I64, I64, ADDR, ADDR, ADDR));
        LOD_BUILD_MESH = optional("cv_lod_build_mesh", FunctionDescriptor.of(I32, ADDR, I32, I64, I64, I64, ADDR));
        LOD_WAIT_IDLE = optional("cv_lod_wait_idle", FunctionDescriptor.of(I32, ADDR));
        LOD_FLUSH = optional("cv_lod_flush", FunctionDescriptor.of(I32, ADDR));
        LOD_STATS_GET = optional("cv_lod_stats_get", FunctionDescriptor.ofVoid(ADDR, ADDR));

        STORE_OPEN = optional("cv_store_open", FunctionDescriptor.of(ADDR, ADDR));
        STORE_CLOSE = optional("cv_store_close", FunctionDescriptor.of(I32, ADDR));
        STORE_FLUSH = optional("cv_store_flush", FunctionDescriptor.of(I32, ADDR));
        STORE_READ_CUBE = optional("cv_store_read_cube", FunctionDescriptor.of(I64, ADDR, I64, I64, I64, ADDR, I64));
        STORE_WRITE_CUBE = optional("cv_store_write_cube", FunctionDescriptor.of(I32, ADDR, I64, I64, I64, ADDR, I64));
        STORE_READ_COLUMN = optional("cv_store_read_column", FunctionDescriptor.of(I64, ADDR, I64, I64, ADDR, I64));
        STORE_WRITE_COLUMN = optional("cv_store_write_column", FunctionDescriptor.of(I32, ADDR, I64, I64, ADDR, I64));
    }

    private CendaVoxelWorld() {
    }

    // Core symbols must exist once the handshake passed.
    private static MethodHandle core(String name, FunctionDescriptor fd, Linker.Option... options) {
        if (LOOKUP == null) return null;
        return LOOKUP.find(name)
            .map(symbol -> Linker.nativeLinker().downcallHandle(symbol, fd, options))
            .orElseThrow(() -> new IllegalStateException("Cenda voxel world symbol missing: " + name));
    }

    // Later-phase symbols may be absent from a library built before that phase landed.
    private static MethodHandle optional(String name, FunctionDescriptor fd) {
        if (LOOKUP == null) return null;
        return LOOKUP.find(name).map(symbol -> Linker.nativeLinker().downcallHandle(symbol, fd)).orElse(null);
    }

    /** The handle, or an exception naming the symbol the library lacks. */
    static MethodHandle require(MethodHandle handle, String name) {
        if (handle == null) {
            if (FAILURE != null) {
                throw new IllegalStateException("Cenda voxel world unavailable: " + FAILURE);
            }
            throw new UnsupportedOperationException(name + " is not in this Cenda library (rebuild it)");
        }
        return handle;
    }

    /** True when the native voxel world loaded and passed its handshake. */
    public static boolean isAvailable() {
        return FAILURE == null;
    }

    /** Why the native voxel world is unavailable, or null when it is available. */
    public static String unavailableReason() {
        return FAILURE;
    }

    /** True when the library exports the region store ({@code cv_store_*}). */
    public static boolean hasStore() {
        return STORE_OPEN != null;
    }

    /** True when the library exports a mesher ({@code cv_mesh_cube}). */
    public static boolean hasMesher() {
        return MESH_CUBE != null;
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

    /** Turns a negative cv_* result into an exception (CV_ERR_* or an unexpected CV_NEED). */
    static RuntimeException failure(String call, long code) {
        String message = call + " failed (" + code + "): " + lastError();
        if (code == ERR_ARG) return new IllegalArgumentException(message);
        if (code == ERR_CORRUPT) return new IllegalArgumentException(message);
        if (code == ERR_IO) return new java.io.UncheckedIOException(new java.io.IOException(message));
        return new IllegalStateException(message);
    }
}
