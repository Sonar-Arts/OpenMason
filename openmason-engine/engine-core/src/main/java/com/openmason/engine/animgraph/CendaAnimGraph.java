package com.openmason.engine.animgraph;

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
 * FFM symbols of the Cenda animation graph ({@code cg_*} in {@code libcenda_kernels}, contract in
 * {@code cenda/native/kernels/include/cenda/anim_graph.h}; OpenMason #157). Only the handshake and the method
 * handles live here; {@link AnimGraph} and {@link AnimGraphInstance} are the API.
 *
 * <p>No Java fallback: when the library is missing or fails the {@code cg_abi_version} handshake,
 * {@link #isAvailable()} is false and using a graph throws.
 */
public final class CendaAnimGraph {

    private static final Logger LOGGER = LoggerFactory.getLogger(CendaAnimGraph.class);

    /** Must equal {@code CG_ABI_VERSION} in anim_graph.h (build-kernels.sh checks both sides). */
    static final int EXPECTED_ABI = 1;

    static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;
    static final AddressLayout ADDR = ValueLayout.ADDRESS;

    static final int OK = 0;
    static final int ERR_FORMAT = -3;
    static final int ERR_VERSION = -4;
    static final int ERR_STATE = -5;
    static final int ERR_HOST = -6;

    /** {@code cr_ground_fn} (rig.h, #159) with its pointers as raw addresses: an upcall allocates no segment. */
    static final FunctionDescriptor GROUND_FN = FunctionDescriptor.of(I32, I64, I64, I32, I64);

    /** {@code cg_notify}. */
    static final long NOTIFY_BYTES = 24;
    /** {@code cg_montage_status}. */
    static final long MONTAGE_STATUS_BYTES = 24;
    /** {@code cg_node_debug}. */
    static final long DEBUG_BYTES = 24;
    /** {@code cr_joint_extra}. */
    static final long EXTRA_BYTES = 24;

    private static final String FAILURE;
    static final MethodHandle LAST_ERROR;
    static final MethodHandle BYTES_DATA;
    static final MethodHandle BYTES_SIZE;
    static final MethodHandle BYTES_DESTROY;
    static final MethodHandle GRAPH_READ;
    static final MethodHandle GRAPH_DESTROY;
    static final MethodHandle GRAPH_WRITE;
    static final MethodHandle GRAPH_NAME;
    static final MethodHandle GRAPH_PROFILE;
    static final MethodHandle PARAM_COUNT;
    static final MethodHandle PARAM_GET;
    static final MethodHandle SLOT_COUNT;
    static final MethodHandle SLOT_NAME;
    static final MethodHandle NODE_COUNT;
    static final MethodHandle NODE_GET;
    static final MethodHandle STATE_COUNT;
    static final MethodHandle STATE_NAME;
    static final MethodHandle MONTAGE_SLOT_COUNT;
    static final MethodHandle MONTAGE_SLOT_NAME;
    static final MethodHandle TARGET_COUNT;
    static final MethodHandle TARGET_NAME;
    static final MethodHandle MODIFIER_COUNT;
    static final MethodHandle SET_TARGET;
    static final MethodHandle SET_GROUND;
    static final MethodHandle MODIFIER_VALUE;
    static final MethodHandle INSTANCE_CREATE;
    static final MethodHandle INSTANCE_DESTROY;
    static final MethodHandle INSTANCE_RESET;
    static final MethodHandle BIND_SLOT;
    static final MethodHandle BIND_SLOT_RETARGET;
    static final MethodHandle ADD_MONTAGE;
    static final MethodHandle SET_FLOAT;
    static final MethodHandle SET_INT;
    static final MethodHandle SET_BOOL;
    static final MethodHandle FIRE_TRIGGER;
    static final MethodHandle RESET_TRIGGER;
    static final MethodHandle SET_VEC2;
    static final MethodHandle GET_PARAM;
    static final MethodHandle SET_ROOT_MOTION;
    static final MethodHandle TRAVEL;
    static final MethodHandle STATE;
    static final MethodHandle MONTAGE_PLAY;
    static final MethodHandle MONTAGE_STOP;
    static final MethodHandle MONTAGE_JUMP;
    static final MethodHandle MONTAGE_SET_NEXT;
    static final MethodHandle MONTAGE_STATUS;
    static final MethodHandle EVALUATE;
    static final MethodHandle LOCAL_POSE;
    static final MethodHandle COMPOSE_VIEW;
    static final MethodHandle FIRST_PERSON_LAYER_COUNT;
    static final MethodHandle NOTIFY_COUNT;
    static final MethodHandle NOTIFY_GET;
    static final MethodHandle CURVE_COUNT;
    static final MethodHandle CURVE_NAME;
    static final MethodHandle CURVE_VALUE;
    static final MethodHandle ROOT_MOTION;
    static final MethodHandle DEBUG;

    static {
        String failure = null;
        SymbolLookup lookup = null;
        try {
            Path lib = CendaKernels.libraryPath().orElse(null);
            if (lib == null) {
                failure = "Cenda library not found; build it with openmason-engine/cenda/build-kernels.sh";
            } else {
                lookup = SymbolLookup.libraryLookup(lib, Arena.global());
                MethodHandle abi = Linker.nativeLinker().downcallHandle(
                    lookup.find("cg_abi_version").orElseThrow(
                        () -> new IllegalStateException("library has no animation graph (cg_abi_version missing)")),
                    FunctionDescriptor.of(I32));
                int have = (int) abi.invokeExact();
                if (have != EXPECTED_ABI) {
                    failure = "Cenda animation graph at " + lib + " has ABI " + have + " (expected " + EXPECTED_ABI
                        + "); rebuild with openmason-engine/cenda/build-kernels.sh";
                    lookup = null;
                } else {
                    LOGGER.info("Cenda animation graph (ABI {}) loaded from {}", have, lib);
                }
            }
        } catch (Throwable t) {
            failure = "Cenda animation graph failed to load: " + t;
            lookup = null;
        }
        if (failure != null) LOGGER.error("Animation graph unavailable: {}", failure);
        FAILURE = failure;
        Linker.Option critical = Linker.Option.critical(false);
        LAST_ERROR = handle(lookup, "cg_last_error", FunctionDescriptor.of(ADDR));
        BYTES_DATA = handle(lookup, "cc_bytes_data", FunctionDescriptor.of(ADDR, ADDR));
        BYTES_SIZE = handle(lookup, "cc_bytes_size", FunctionDescriptor.of(I64, ADDR));
        BYTES_DESTROY = handle(lookup, "cc_bytes_destroy", FunctionDescriptor.ofVoid(ADDR));
        GRAPH_READ = handle(lookup, "cg_graph_read", FunctionDescriptor.of(ADDR, ADDR, I64, ADDR));
        GRAPH_DESTROY = handle(lookup, "cg_graph_destroy", FunctionDescriptor.ofVoid(ADDR));
        GRAPH_WRITE = handle(lookup, "cg_graph_write", FunctionDescriptor.of(ADDR, ADDR));
        GRAPH_NAME = handle(lookup, "cg_graph_name", FunctionDescriptor.of(ADDR, ADDR));
        GRAPH_PROFILE = handle(lookup, "cg_graph_profile", FunctionDescriptor.of(ADDR, ADDR));
        PARAM_COUNT = handle(lookup, "cg_graph_param_count", FunctionDescriptor.of(I32, ADDR));
        PARAM_GET = handle(lookup, "cg_graph_param_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR));
        SLOT_COUNT = handle(lookup, "cg_graph_slot_count", FunctionDescriptor.of(I32, ADDR));
        SLOT_NAME = handle(lookup, "cg_graph_slot_name", FunctionDescriptor.of(ADDR, ADDR, I32));
        NODE_COUNT = handle(lookup, "cg_graph_node_count", FunctionDescriptor.of(I32, ADDR));
        NODE_GET = handle(lookup, "cg_graph_node_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR));
        STATE_COUNT = handle(lookup, "cg_graph_state_count", FunctionDescriptor.of(I32, ADDR, I32));
        STATE_NAME = handle(lookup, "cg_graph_state_name", FunctionDescriptor.of(ADDR, ADDR, I32, I32));
        MONTAGE_SLOT_COUNT = handle(lookup, "cg_graph_montage_slot_count", FunctionDescriptor.of(I32, ADDR));
        MONTAGE_SLOT_NAME = handle(lookup, "cg_graph_montage_slot_name", FunctionDescriptor.of(ADDR, ADDR, I32));
        TARGET_COUNT = handle(lookup, "cg_graph_target_count", FunctionDescriptor.of(I32, ADDR));
        TARGET_NAME = handle(lookup, "cg_graph_target_name", FunctionDescriptor.of(ADDR, ADDR, I32));
        MODIFIER_COUNT = handle(lookup, "cg_graph_modifier_count", FunctionDescriptor.of(I32, ADDR));
        SET_TARGET = handle(lookup, "cg_instance_set_target", FunctionDescriptor.of(I32, ADDR, I32, I32, ADDR, ADDR));
        SET_GROUND = handle(lookup, "cg_instance_set_ground", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        MODIFIER_VALUE = handle(lookup, "cg_instance_modifier_value", FunctionDescriptor.of(F32, ADDR, I32, I32), critical);
        INSTANCE_CREATE = handle(lookup, "cg_instance_create", FunctionDescriptor.of(ADDR, ADDR, ADDR, ADDR, ADDR));
        INSTANCE_DESTROY = handle(lookup, "cg_instance_destroy", FunctionDescriptor.ofVoid(ADDR));
        INSTANCE_RESET = handle(lookup, "cg_instance_reset", FunctionDescriptor.of(I32, ADDR));
        BIND_SLOT = handle(lookup, "cg_instance_bind_slot", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR));
        BIND_SLOT_RETARGET = handle(lookup, "cg_instance_bind_slot_retarget",
            FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR));
        ADD_MONTAGE = handle(lookup, "cg_instance_add_montage", FunctionDescriptor.of(I32, ADDR, ADDR));
        SET_FLOAT = handle(lookup, "cg_instance_set_float", FunctionDescriptor.of(I32, ADDR, I32, F32), critical);
        SET_INT = handle(lookup, "cg_instance_set_int", FunctionDescriptor.of(I32, ADDR, I32, I32), critical);
        SET_BOOL = handle(lookup, "cg_instance_set_bool", FunctionDescriptor.of(I32, ADDR, I32, I32), critical);
        FIRE_TRIGGER = handle(lookup, "cg_instance_fire_trigger", FunctionDescriptor.of(I32, ADDR, I32), critical);
        RESET_TRIGGER = handle(lookup, "cg_instance_reset_trigger", FunctionDescriptor.of(I32, ADDR, I32), critical);
        SET_VEC2 = handle(lookup, "cg_instance_set_vec2", FunctionDescriptor.of(I32, ADDR, I32, F32, F32), critical);
        GET_PARAM = handle(lookup, "cg_instance_get_param", FunctionDescriptor.of(I32, ADDR, I32, ADDR));
        SET_ROOT_MOTION = handle(lookup, "cg_instance_set_root_motion", FunctionDescriptor.of(I32, ADDR, I32));
        TRAVEL = handle(lookup, "cg_instance_travel", FunctionDescriptor.of(I32, ADDR, I32, I32));
        STATE = handle(lookup, "cg_instance_state", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR));
        MONTAGE_PLAY = handle(lookup, "cg_instance_montage_play",
            FunctionDescriptor.of(I32, ADDR, I32, I32, F32, F32, F32, ADDR));
        MONTAGE_STOP = handle(lookup, "cg_instance_montage_stop", FunctionDescriptor.of(I32, ADDR, I32, F32));
        MONTAGE_JUMP = handle(lookup, "cg_instance_montage_jump", FunctionDescriptor.of(I32, ADDR, I32, ADDR));
        MONTAGE_SET_NEXT = handle(lookup, "cg_instance_montage_set_next", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR));
        MONTAGE_STATUS = handle(lookup, "cg_instance_montage_status", FunctionDescriptor.of(I32, ADDR, I32, ADDR));
        EVALUATE = handle(lookup, "cg_instance_evaluate",
            FunctionDescriptor.of(I32, ADDR, F32, ADDR, I32, ADDR, ADDR, ADDR, ADDR));
        LOCAL_POSE = handle(lookup, "cg_instance_local_pose", FunctionDescriptor.of(I32, ADDR, ADDR));
        COMPOSE_VIEW = handle(lookup, "cg_instance_compose_view",
            FunctionDescriptor.of(I32, ADDR, I32, ADDR, I32, ADDR, ADDR, ADDR, ADDR));
        FIRST_PERSON_LAYER_COUNT = handle(lookup, "cg_graph_first_person_layer_count", FunctionDescriptor.of(I32, ADDR));
        NOTIFY_COUNT = handle(lookup, "cg_instance_notify_count", FunctionDescriptor.of(I32, ADDR), critical);
        NOTIFY_GET = handle(lookup, "cg_instance_notify_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR));
        CURVE_COUNT = handle(lookup, "cg_instance_curve_count", FunctionDescriptor.of(I32, ADDR), critical);
        CURVE_NAME = handle(lookup, "cg_instance_curve_name", FunctionDescriptor.of(ADDR, ADDR, I32));
        CURVE_VALUE = handle(lookup, "cg_instance_curve_value", FunctionDescriptor.of(F32, ADDR, I32), critical);
        ROOT_MOTION = handle(lookup, "cg_instance_root_motion", FunctionDescriptor.of(I32, ADDR, ADDR));
        DEBUG = handle(lookup, "cg_instance_debug", FunctionDescriptor.of(I32, ADDR, ADDR));
    }

    private CendaAnimGraph() {
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor fd, Linker.Option... options) {
        if (lookup == null) return null;
        return lookup.find(name)
            .map(symbol -> Linker.nativeLinker().downcallHandle(symbol, fd, options))
            .orElseThrow(() -> new IllegalStateException("Cenda animation graph symbol missing: " + name));
    }

    /** True when the native graph loaded and passed its handshake. */
    public static boolean isAvailable() {
        return FAILURE == null;
    }

    /** Why the native graph is unavailable, or null. */
    public static String unavailableReason() {
        return FAILURE;
    }

    static void requireAvailable() {
        if (FAILURE != null) throw new IllegalStateException("Cenda animation graph unavailable: " + FAILURE);
    }

    static String lastError() {
        try {
            return ((MemorySegment) LAST_ERROR.invokeExact()).reinterpret(4096).getString(0);
        } catch (Throwable t) {
            return "unknown error";
        }
    }

    static String string(MemorySegment s) {
        return s == null || s.equals(MemorySegment.NULL) ? null : s.reinterpret(1 << 20).getString(0);
    }

    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }

    static void check(int rc, String what) {
        if (rc == ERR_STATE) throw new IllegalStateException(what + ": " + lastError());
        if (rc < 0) throw new IllegalArgumentException(what + ": " + lastError());
    }
}
