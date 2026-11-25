package com.openmason.engine.character;

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
 * FFM symbols of the Cenda character definition ({@code cc_*} in {@code libcenda_kernels}, contract in
 * {@code cenda/native/kernels/include/cenda/character.h}; the {@code .omchar} format is native too, in
 * {@code libomformat}). Only the library handshake and the method handles live here;
 * {@link CharacterDefinition} is the API.
 *
 * <p>No Java fallback: when the library is missing or fails the {@code cc_abi_version} handshake,
 * {@link #isAvailable()} is false, {@link #unavailableReason()} says why, and using a definition throws.
 */
public final class CendaCharacter {

    private static final Logger LOGGER = LoggerFactory.getLogger(CendaCharacter.class);

    /** Must equal {@code CC_ABI_VERSION} in character.h (build-kernels.sh checks both sides). */
    static final int EXPECTED_ABI = 1;

    static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;
    static final AddressLayout ADDR = ValueLayout.ADDRESS;

    /** {@code cc_metrics}. */
    static final long METRICS_BYTES = 24;

    static final int OK = 0;
    static final int ERR_VERSION = -4;
    static final int ERR_LOCKED = -5;
    static final int ERR_INVALID = -6;

    static final int FIELD_NAME = 0;
    static final int FIELD_OBJECT_ID = 1;
    static final int FIELD_PROFILE = 2;
    static final int FIELD_ANIM_GRAPH = 3;
    static final int FIELD_MODEL_REF = 4;
    static final int FIELD_APPEARANCE = 5;
    static final int FIELD_JOINT_HASH = 6;
    static final int FIELD_FACING = 7;

    static final int PROFILE_ID = 0;
    static final int PROFILE_NAME = 1;
    static final int PROFILE_DESCRIPTION = 2;

    private static final String FAILURE;
    static final MethodHandle LAST_ERROR;
    // Retargeting (#160, cenda/retarget.h; still CC ABI 1).
    static final MethodHandle RT_CREATE;
    static final MethodHandle RT_DESTROY;
    static final MethodHandle RT_SET_OPS;
    static final MethodHandle RT_OP_COUNT;
    static final MethodHandle RT_OP_GET;
    static final MethodHandle RT_CHAIN_COUNT;
    static final MethodHandle RT_CHAIN_GET;
    static final MethodHandle RT_SET_CHAIN;
    static final MethodHandle RT_GET_CHAIN;
    static final MethodHandle RT_SET_BEND;
    static final MethodHandle RT_SCALE;
    static final MethodHandle RT_MAP_MOTION;
    static final MethodHandle RT_POSE;
    static final MethodHandle RT_BAKE;
    static final MethodHandle CREATE;
    static final MethodHandle READ;
    static final MethodHandle DESTROY;
    static final MethodHandle WRITE;
    static final MethodHandle BYTES_DATA;
    static final MethodHandle BYTES_SIZE;
    static final MethodHandle BYTES_DESTROY;
    static final MethodHandle GET_STRING;
    static final MethodHandle SET_STRING;
    static final MethodHandle JOINT_COUNT;
    static final MethodHandle JOINT_GET;
    static final MethodHandle SET_SKELETON;
    static final MethodHandle MODEL_GET;
    static final MethodHandle MODEL_SET;
    static final MethodHandle ROLE_COUNT;
    static final MethodHandle ROLE_GET;
    static final MethodHandle ROLE_SET;
    static final MethodHandle POSE_COUNT;
    static final MethodHandle POSE_GET;
    static final MethodHandle POSE_SET;
    static final MethodHandle LIMIT_COUNT;
    static final MethodHandle LIMIT_GET;
    static final MethodHandle LIMIT_SET;
    static final MethodHandle GOAL_COUNT;
    static final MethodHandle GOAL_GET;
    static final MethodHandle GOAL_SET;
    static final MethodHandle CHAIN_COUNT;
    static final MethodHandle CHAIN_GET;
    static final MethodHandle CHAIN_SET;
    static final MethodHandle SOCKET_COUNT;
    static final MethodHandle SOCKET_GET;
    static final MethodHandle SOCKET_SET;
    static final MethodHandle METRICS_GET;
    static final MethodHandle METRICS_SET;
    static final MethodHandle COMPUTE_METRICS;
    static final MethodHandle MASK_ROLE;
    static final MethodHandle CHECK;
    static final MethodHandle REPORT_COUNT;
    static final MethodHandle REPORT_GET;
    static final MethodHandle REPORT_DESTROY;
    static final MethodHandle PROFILE_BUILTIN_COUNT;
    static final MethodHandle PROFILE_BUILTIN_ID;
    static final MethodHandle PROFILE_BUILTIN;
    static final MethodHandle PROFILE_READ;
    static final MethodHandle PROFILE_WRITE;
    static final MethodHandle PROFILE_FROM_RIG;
    static final MethodHandle PROFILE_DESTROY;
    static final MethodHandle PROFILE_GET_STRING;
    static final MethodHandle PROFILE_ROLE_COUNT;
    static final MethodHandle PROFILE_ROLE_GET;
    static final MethodHandle AUTO_MAP;
    static final MethodHandle AUTOMAP_COUNT;
    static final MethodHandle AUTOMAP_GET;
    static final MethodHandle AUTOMAP_NOTES;
    static final MethodHandle AUTOMAP_DESTROY;
    static final MethodHandle VALIDATE;
    static final MethodHandle FIT_REFERENCE_POSE;
    static final MethodHandle LOCK;
    static final MethodHandle UNLOCK;
    static final MethodHandle LOCKED;

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
                    lookup.find("cc_abi_version").orElseThrow(
                        () -> new IllegalStateException("library has no character module (cc_abi_version missing)")),
                    FunctionDescriptor.of(I32));
                int have = (int) abi.invokeExact();
                if (have != EXPECTED_ABI) {
                    failure = "Cenda character module at " + lib + " has ABI " + have + " (expected " + EXPECTED_ABI
                        + "); rebuild with openmason-engine/cenda/build-kernels.sh";
                    lookup = null;
                } else {
                    LOGGER.info("Cenda character module (ABI {}) loaded from {}", have, lib);
                }
            }
        } catch (Throwable t) {
            failure = "Cenda character module failed to load: " + t;
            lookup = null;
        }
        if (failure != null) {
            LOGGER.error("Character definitions unavailable: {}", failure);
        }
        FAILURE = failure;
        LAST_ERROR = handle(lookup, "cc_last_error", FunctionDescriptor.of(ADDR));
        RT_CREATE = handle(lookup, "cc_retarget_create", FunctionDescriptor.of(ADDR, ADDR, ADDR, ADDR, ADDR));
        RT_DESTROY = handle(lookup, "cc_retarget_destroy", FunctionDescriptor.ofVoid(ADDR));
        RT_SET_OPS = handle(lookup, "cc_retarget_set_ops", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        RT_OP_COUNT = handle(lookup, "cc_retarget_op_count", FunctionDescriptor.of(I32, ADDR));
        RT_OP_GET = handle(lookup, "cc_retarget_op_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR));
        RT_CHAIN_COUNT = handle(lookup, "cc_retarget_chain_count", FunctionDescriptor.of(I32, ADDR));
        RT_CHAIN_GET = handle(lookup, "cc_retarget_chain_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR, ADDR));
        RT_SET_CHAIN = handle(lookup, "cc_retarget_set_chain", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        RT_GET_CHAIN = handle(lookup, "cc_retarget_get_chain", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        RT_SET_BEND = handle(lookup, "cc_retarget_set_bend", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        RT_SCALE = handle(lookup, "cc_retarget_scale", FunctionDescriptor.of(F32, ADDR));
        RT_MAP_MOTION = handle(lookup, "cc_retarget_map_motion", FunctionDescriptor.of(I32, ADDR, ADDR));
        RT_POSE = handle(lookup, "cc_retarget_pose", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR));
        RT_BAKE = handle(lookup, "cc_retarget_bake", FunctionDescriptor.of(ADDR, ADDR, ADDR, F32, ADDR));
        CREATE = handle(lookup, "cc_char_create", FunctionDescriptor.of(ADDR));
        READ = handle(lookup, "cc_char_read", FunctionDescriptor.of(ADDR, ADDR, I64, ADDR));
        DESTROY = handle(lookup, "cc_char_destroy", FunctionDescriptor.ofVoid(ADDR));
        WRITE = handle(lookup, "cc_char_write", FunctionDescriptor.of(ADDR, ADDR));
        BYTES_DATA = handle(lookup, "cc_bytes_data", FunctionDescriptor.of(ADDR, ADDR));
        BYTES_SIZE = handle(lookup, "cc_bytes_size", FunctionDescriptor.of(I64, ADDR));
        BYTES_DESTROY = handle(lookup, "cc_bytes_destroy", FunctionDescriptor.ofVoid(ADDR));
        GET_STRING = handle(lookup, "cc_char_get_string", FunctionDescriptor.of(ADDR, ADDR, I32));
        SET_STRING = handle(lookup, "cc_char_set_string", FunctionDescriptor.of(I32, ADDR, I32, ADDR));
        JOINT_COUNT = handle(lookup, "cc_char_joint_count", FunctionDescriptor.of(I32, ADDR));
        JOINT_GET = handle(lookup, "cc_char_joint_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR, ADDR));
        SET_SKELETON = handle(lookup, "cc_char_set_skeleton", FunctionDescriptor.of(I32, ADDR, ADDR));
        MODEL_GET = handle(lookup, "cc_char_model_get", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        MODEL_SET = handle(lookup, "cc_char_model_set", FunctionDescriptor.of(I32, ADDR, ADDR, I64, ADDR));
        ROLE_COUNT = handle(lookup, "cc_char_role_count", FunctionDescriptor.of(I32, ADDR));
        ROLE_GET = handle(lookup, "cc_char_role_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR));
        ROLE_SET = handle(lookup, "cc_char_role_set", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        POSE_COUNT = handle(lookup, "cc_char_pose_count", FunctionDescriptor.of(I32, ADDR));
        POSE_GET = handle(lookup, "cc_char_pose_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR));
        POSE_SET = handle(lookup, "cc_char_pose_set", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        LIMIT_COUNT = handle(lookup, "cc_char_limit_count", FunctionDescriptor.of(I32, ADDR));
        LIMIT_GET = handle(lookup, "cc_char_limit_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR));
        LIMIT_SET = handle(lookup, "cc_char_limit_set", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR));
        GOAL_COUNT = handle(lookup, "cc_char_goal_count", FunctionDescriptor.of(I32, ADDR));
        GOAL_GET = handle(lookup, "cc_char_goal_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR));
        GOAL_SET = handle(lookup, "cc_char_goal_set", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        CHAIN_COUNT = handle(lookup, "cc_char_chain_count", FunctionDescriptor.of(I32, ADDR));
        CHAIN_GET = handle(lookup, "cc_char_chain_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR, ADDR));
        CHAIN_SET = handle(lookup, "cc_char_chain_set", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR, ADDR));
        SOCKET_COUNT = handle(lookup, "cc_char_socket_count", FunctionDescriptor.of(I32, ADDR));
        SOCKET_GET = handle(lookup, "cc_char_socket_get",
            FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR, ADDR, ADDR));
        SOCKET_SET = handle(lookup, "cc_char_socket_set",
            FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR));
        METRICS_GET = handle(lookup, "cc_char_metrics_get", FunctionDescriptor.of(I32, ADDR, ADDR));
        METRICS_SET = handle(lookup, "cc_char_metrics_set", FunctionDescriptor.of(I32, ADDR, ADDR));
        COMPUTE_METRICS = handle(lookup, "cc_char_compute_metrics",
            FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR, ADDR));
        MASK_ROLE = handle(lookup, "cc_char_mask_role",
            FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR, F32, I32, I32));
        CHECK = handle(lookup, "cc_char_check", FunctionDescriptor.of(ADDR, ADDR, ADDR));
        REPORT_COUNT = handle(lookup, "cc_report_count", FunctionDescriptor.of(I32, ADDR));
        REPORT_GET = handle(lookup, "cc_report_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR, ADDR));
        REPORT_DESTROY = handle(lookup, "cc_report_destroy", FunctionDescriptor.ofVoid(ADDR));
        PROFILE_BUILTIN_COUNT = handle(lookup, "cc_profile_builtin_count", FunctionDescriptor.of(I32));
        PROFILE_BUILTIN_ID = handle(lookup, "cc_profile_builtin_id", FunctionDescriptor.of(ADDR, I32));
        PROFILE_BUILTIN = handle(lookup, "cc_profile_builtin", FunctionDescriptor.of(ADDR, ADDR));
        PROFILE_READ = handle(lookup, "cc_profile_read", FunctionDescriptor.of(ADDR, ADDR, I64, ADDR));
        PROFILE_WRITE = handle(lookup, "cc_profile_write", FunctionDescriptor.of(ADDR, ADDR));
        PROFILE_FROM_RIG = handle(lookup, "cc_profile_from_rig", FunctionDescriptor.of(ADDR, ADDR, ADDR, ADDR));
        PROFILE_DESTROY = handle(lookup, "cc_profile_destroy", FunctionDescriptor.ofVoid(ADDR));
        PROFILE_GET_STRING = handle(lookup, "cc_profile_get_string", FunctionDescriptor.of(ADDR, ADDR, I32));
        PROFILE_ROLE_COUNT = handle(lookup, "cc_profile_role_count", FunctionDescriptor.of(I32, ADDR));
        PROFILE_ROLE_GET = handle(lookup, "cc_profile_role_get",
            FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR));
        AUTO_MAP = handle(lookup, "cc_char_auto_map", FunctionDescriptor.of(ADDR, ADDR, ADDR, ADDR));
        AUTOMAP_COUNT = handle(lookup, "cc_automap_count", FunctionDescriptor.of(I32, ADDR));
        AUTOMAP_GET = handle(lookup, "cc_automap_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR));
        AUTOMAP_NOTES = handle(lookup, "cc_automap_notes", FunctionDescriptor.of(ADDR, ADDR));
        AUTOMAP_DESTROY = handle(lookup, "cc_automap_destroy", FunctionDescriptor.ofVoid(ADDR));
        VALIDATE = handle(lookup, "cc_char_validate", FunctionDescriptor.of(ADDR, ADDR, ADDR, ADDR));
        FIT_REFERENCE_POSE = handle(lookup, "cc_char_fit_reference_pose", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        LOCK = handle(lookup, "cc_char_lock", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        UNLOCK = handle(lookup, "cc_char_unlock", FunctionDescriptor.of(I32, ADDR));
        LOCKED = handle(lookup, "cc_char_locked", FunctionDescriptor.of(I32, ADDR));
    }

    private CendaCharacter() {
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor fd) {
        if (lookup == null) return null;
        return lookup.find(name)
            .map(symbol -> Linker.nativeLinker().downcallHandle(symbol, fd))
            .orElseThrow(() -> new IllegalStateException("Cenda character symbol missing: " + name));
    }

    /** True when the native character module loaded and passed its handshake. */
    public static boolean isAvailable() {
        return FAILURE == null;
    }

    /** Why the native character module is unavailable, or null when it is available. */
    public static String unavailableReason() {
        return FAILURE;
    }

    static void requireAvailable() {
        if (FAILURE != null) {
            throw new IllegalStateException("Cenda character module unavailable: " + FAILURE);
        }
    }

    static String lastError() {
        try {
            return string((MemorySegment) LAST_ERROR.invokeExact());
        } catch (Throwable t) {
            return "unknown error";
        }
    }

    /** A native NUL-terminated string (owned by the library) as a Java string; NULL gives null. */
    static String string(MemorySegment cString) {
        if (cString == null || cString.equals(MemorySegment.NULL)) return null;
        return cString.reinterpret(Long.MAX_VALUE).getString(0);
    }

    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }
}
