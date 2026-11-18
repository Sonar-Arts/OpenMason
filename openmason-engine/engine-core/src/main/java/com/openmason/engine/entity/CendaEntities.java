package com.openmason.engine.entity;

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
 * FFM symbols of the Cenda entity model ({@code ce_*} in {@code libcenda_kernels}, contract in
 * {@code cenda/native/kernels/include/cenda/entity.h}, OpenMason #114). Only the library handshake
 * and the method handles live here; {@link EntityWorld} is the API.
 *
 * <p>The entity model has no Java fallback: when the library is missing or fails the
 * {@code ce_abi_version} handshake, {@link #isAvailable()} is false, {@link #unavailableReason()}
 * says why, and {@link EntityWorld#create} throws with that reason.
 */
public final class CendaEntities {

    private static final Logger LOGGER = LoggerFactory.getLogger(CendaEntities.class);

    /** Must equal {@code CE_ABI_VERSION} in entity.h (build-kernels.sh checks both sides). */
    static final int EXPECTED_ABI = 2;

    static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;
    static final ValueLayout.OfDouble F64 = ValueLayout.JAVA_DOUBLE;
    static final ValueLayout ADDR = ValueLayout.ADDRESS;

    // struct sizes (entity.h)
    static final long TYPE_DESC_BYTES = 24;
    static final long BODY_BYTES = 64; // ABI 2: double x, y, z
    static final long FILTER_BYTES = 24;
    static final long CLIP_BYTES = 16;

    private static final String FAILURE;
    static final MethodHandle LAST_ERROR;
    static final MethodHandle WORLD_CREATE;
    static final MethodHandle SET_ORIGIN;
    static final MethodHandle WORLD_DESTROY;
    static final MethodHandle TYPE_DEFINE;
    static final MethodHandle TYPE_GET;
    static final MethodHandle TYPE_MODEL_ID;
    static final MethodHandle SPAWN;
    static final MethodHandle DESPAWN;
    static final MethodHandle COMMIT_SPAWNS;
    static final MethodHandle COMMIT_DESPAWNS;
    static final MethodHandle STATE;
    static final MethodHandle TYPE_OF;
    static final MethodHandle COUNT;
    static final MethodHandle LIVE;
    static final MethodHandle PENDING;
    static final MethodHandle SYNC_BODIES;
    static final MethodHandle BODY_GET;
    static final MethodHandle QUERY_RADIUS;
    static final MethodHandle QUERY_NEAREST;
    static final MethodHandle QUERY_BOX;
    static final MethodHandle QUERY_AABB;
    static final MethodHandle QUERY_ALL;
    static final MethodHandle CLIP_SET;
    static final MethodHandle CLIP_ADVANCE;
    static final MethodHandle CLIP_TIME;
    static final MethodHandle CLIP_GET;
    static final MethodHandle CLIP_PUT;

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
                    lookup.find("ce_abi_version").orElseThrow(
                        () -> new IllegalStateException("library has no entity model (ce_abi_version missing)")),
                    FunctionDescriptor.of(I32));
                int have = (int) abi.invokeExact();
                if (have != EXPECTED_ABI) {
                    failure = "Cenda entities at " + lib + " have ABI " + have + " (expected " + EXPECTED_ABI
                        + "); rebuild with openmason-engine/cenda/build-kernels.sh";
                    lookup = null;
                } else {
                    LOGGER.info("Cenda entities (ABI {}) loaded from {}", have, lib);
                }
            }
        } catch (Throwable t) {
            failure = "Cenda entities failed to load: " + t;
            lookup = null;
        }
        if (failure != null) {
            LOGGER.error("Entity model unavailable: {}", failure);
        }
        FAILURE = failure;
        Linker.Option fast = Linker.Option.critical(false);
        LAST_ERROR = handle(lookup, "ce_last_error", FunctionDescriptor.of(ADDR));
        WORLD_CREATE = handle(lookup, "ce_world_create", FunctionDescriptor.of(ADDR, I32));
        SET_ORIGIN = handle(lookup, "ce_set_origin", FunctionDescriptor.of(I32, ADDR, I64, I64, I64), fast);
        WORLD_DESTROY = handle(lookup, "ce_world_destroy", FunctionDescriptor.ofVoid(ADDR));
        TYPE_DEFINE = handle(lookup, "ce_type_define", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR));
        TYPE_GET = handle(lookup, "ce_type_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR), fast);
        TYPE_MODEL_ID = handle(lookup, "ce_type_model_id", FunctionDescriptor.of(ADDR, ADDR, I32), fast);
        SPAWN = handle(lookup, "ce_spawn", FunctionDescriptor.of(I64, ADDR, I32, I32, F64, F64, F64, F32));
        DESPAWN = handle(lookup, "ce_despawn", FunctionDescriptor.of(I32, ADDR, I64));
        COMMIT_SPAWNS = handle(lookup, "ce_commit_spawns", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        COMMIT_DESPAWNS = handle(lookup, "ce_commit_despawns", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        STATE = handle(lookup, "ce_state", FunctionDescriptor.of(I32, ADDR, I64), fast);
        TYPE_OF = handle(lookup, "ce_type_of", FunctionDescriptor.of(I32, ADDR, I64), fast);
        COUNT = handle(lookup, "ce_count", FunctionDescriptor.of(I32, ADDR, I32));
        LIVE = handle(lookup, "ce_live", FunctionDescriptor.of(I32, ADDR, ADDR, I32), fast);
        PENDING = handle(lookup, "ce_pending", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        SYNC_BODIES = handle(lookup, "ce_sync_bodies", FunctionDescriptor.of(I32, ADDR, ADDR, I32), fast);
        BODY_GET = handle(lookup, "ce_body_get", FunctionDescriptor.of(I32, ADDR, I64, ADDR), fast);
        QUERY_RADIUS = handle(lookup, "ce_query_radius",
            FunctionDescriptor.of(I32, ADDR, F64, F64, F64, F64, ADDR, ADDR, I32), fast);
        QUERY_NEAREST = handle(lookup, "ce_query_nearest",
            FunctionDescriptor.of(I64, ADDR, F64, F64, F64, F64, ADDR, ADDR), fast);
        QUERY_BOX = handle(lookup, "ce_query_box", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR, I32), fast);
        QUERY_AABB = handle(lookup, "ce_query_aabb", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR, I32), fast);
        QUERY_ALL = handle(lookup, "ce_query_all", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, I32), fast);
        CLIP_SET = handle(lookup, "ce_clip_set", FunctionDescriptor.of(I32, ADDR, I64, I32, I32), fast);
        CLIP_ADVANCE = handle(lookup, "ce_clip_advance", FunctionDescriptor.of(I32, ADDR, I64, F32, F32), fast);
        CLIP_TIME = handle(lookup, "ce_clip_time", FunctionDescriptor.of(F32, ADDR, I64), fast);
        CLIP_GET = handle(lookup, "ce_clip_get", FunctionDescriptor.of(I32, ADDR, I64, ADDR), fast);
        CLIP_PUT = handle(lookup, "ce_clip_put", FunctionDescriptor.of(I32, ADDR, I64, ADDR), fast);
    }

    private CendaEntities() {
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor fd, Linker.Option... options) {
        if (lookup == null) return null;
        return lookup.find(name)
            .map(symbol -> Linker.nativeLinker().downcallHandle(symbol, fd, options))
            .orElseThrow(() -> new IllegalStateException("Cenda entity symbol missing: " + name));
    }

    /** True when the native entity model loaded and passed its handshake. */
    public static boolean isAvailable() {
        return FAILURE == null;
    }

    /** Why the entity model is unavailable, or null when it is available. */
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
