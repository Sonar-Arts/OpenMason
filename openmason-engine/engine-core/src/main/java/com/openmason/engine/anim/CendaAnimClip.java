package com.openmason.engine.anim;

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
 * FFM symbols of the Cenda animation clip documents ({@code cm_*} in {@code libcenda_kernels}, contract in
 * {@code cenda/native/kernels/include/cenda/anim_clip.h}; the {@code .omanim} codec is native, in
 * {@code libomformat}). Only the handshake and the method handles live here; {@link AnimClipDocument} is the API.
 *
 * <p>No Java fallback: when the library is missing or fails the {@code cm_abi_version} handshake,
 * {@link #isAvailable()} is false and using a document throws.
 */
public final class CendaAnimClip {

    private static final Logger LOGGER = LoggerFactory.getLogger(CendaAnimClip.class);

    /** Must equal {@code CM_ABI_VERSION} in anim_clip.h (build-kernels.sh checks both sides). */
    static final int EXPECTED_ABI = 1;

    static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;
    static final AddressLayout ADDR = ValueLayout.ADDRESS;

    /** {@code cr_key}. */
    static final long KEY_BYTES = 48;
    /** {@code cm_header}. */
    static final long HEADER_BYTES = 28;
    /** {@code cm_fired}. */
    static final long FIRED_BYTES = 16;
    /** {@code cm_curve_key}. */
    static final long CURVE_KEY_BYTES = 20;
    /** {@code cm_sync_member}. */
    static final long SYNC_MEMBER_BYTES = 24;

    static final int OK = 0;

    static final int FIELD_NAME = 0;
    static final int FIELD_MODEL_REF = 1;
    static final int FIELD_VERSION = 2;
    static final int FIELD_LAYER_TYPE = 3;

    private static final String FAILURE;
    static final MethodHandle LAST_ERROR;
    static final MethodHandle CREATE;
    static final MethodHandle READ;
    static final MethodHandle CLONE;
    static final MethodHandle DESTROY;
    static final MethodHandle WRITE;
    static final MethodHandle BYTES_DATA;
    static final MethodHandle BYTES_SIZE;
    static final MethodHandle BYTES_DESTROY;
    static final MethodHandle HEADER_GET;
    static final MethodHandle HEADER_SET;
    static final MethodHandle GET_STRING;
    static final MethodHandle SET_STRING;
    static final MethodHandle MODEL_REF_NULL;
    static final MethodHandle MASK_COUNT;
    static final MethodHandle MASK_GET;
    static final MethodHandle MASK_SET;
    static final MethodHandle TRACK_COUNT;
    static final MethodHandle TRACK_GET;
    static final MethodHandle TRACK_KEYS;
    static final MethodHandle TRACK_SET;
    static final MethodHandle TRACK_REMOVE;
    static final MethodHandle NOTIFY_COUNT;
    static final MethodHandle NOTIFY_GET;
    static final MethodHandle NOTIFY_ADD;
    static final MethodHandle NOTIFY_REMOVE;
    static final MethodHandle FIRE;
    static final MethodHandle CURVE_COUNT;
    static final MethodHandle CURVE_GET;
    static final MethodHandle CURVE_KEYS;
    static final MethodHandle CURVE_SET;
    static final MethodHandle CURVE_FIND;
    static final MethodHandle CURVE_SAMPLE;
    static final MethodHandle MARKER_COUNT;
    static final MethodHandle MARKER_GET;
    static final MethodHandle MARKER_ADD;
    static final MethodHandle MARKER_REMOVE;
    static final MethodHandle MARKER_POSITION;
    static final MethodHandle MARKER_TIME;
    static final MethodHandle ROOT_GET;
    static final MethodHandle ROOT_SET;
    static final MethodHandle ADDITIVE_GET;
    static final MethodHandle ADDITIVE_SET;
    static final MethodHandle BIND_ROLES;
    static final MethodHandle TO_ROLES;
    static final MethodHandle SYNC_ADVANCE;

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
                    lookup.find("cm_abi_version").orElseThrow(
                        () -> new IllegalStateException("library has no animation clips (cm_abi_version missing)")),
                    FunctionDescriptor.of(I32));
                int have = (int) abi.invokeExact();
                if (have != EXPECTED_ABI) {
                    failure = "Cenda animation clips at " + lib + " have ABI " + have + " (expected " + EXPECTED_ABI
                        + "); rebuild with openmason-engine/cenda/build-kernels.sh";
                    lookup = null;
                } else {
                    LOGGER.info("Cenda animation clips (ABI {}) loaded from {}", have, lib);
                }
            }
        } catch (Throwable t) {
            failure = "Cenda animation clips failed to load: " + t;
            lookup = null;
        }
        if (failure != null) LOGGER.error("Animation clip documents unavailable: {}", failure);
        FAILURE = failure;
        LAST_ERROR = handle(lookup, "cm_last_error", FunctionDescriptor.of(ADDR));
        CREATE = handle(lookup, "cm_clip_create", FunctionDescriptor.of(ADDR));
        READ = handle(lookup, "cm_clip_read", FunctionDescriptor.of(ADDR, ADDR, I64, ADDR));
        CLONE = handle(lookup, "cm_clip_clone", FunctionDescriptor.of(ADDR, ADDR));
        DESTROY = handle(lookup, "cm_clip_destroy", FunctionDescriptor.ofVoid(ADDR));
        WRITE = handle(lookup, "cm_clip_write", FunctionDescriptor.of(ADDR, ADDR));
        BYTES_DATA = handle(lookup, "cc_bytes_data", FunctionDescriptor.of(ADDR, ADDR));
        BYTES_SIZE = handle(lookup, "cc_bytes_size", FunctionDescriptor.of(I64, ADDR));
        BYTES_DESTROY = handle(lookup, "cc_bytes_destroy", FunctionDescriptor.ofVoid(ADDR));
        HEADER_GET = handle(lookup, "cm_clip_header_get", FunctionDescriptor.of(I32, ADDR, ADDR));
        HEADER_SET = handle(lookup, "cm_clip_header_set", FunctionDescriptor.of(I32, ADDR, ADDR));
        GET_STRING = handle(lookup, "cm_clip_get_string", FunctionDescriptor.of(ADDR, ADDR, I32));
        SET_STRING = handle(lookup, "cm_clip_set_string", FunctionDescriptor.of(I32, ADDR, I32, ADDR));
        MODEL_REF_NULL = handle(lookup, "cm_clip_model_ref_null", FunctionDescriptor.of(I32, ADDR));
        MASK_COUNT = handle(lookup, "cm_clip_mask_count", FunctionDescriptor.of(I32, ADDR));
        MASK_GET = handle(lookup, "cm_clip_mask_get", FunctionDescriptor.of(ADDR, ADDR, I32));
        MASK_SET = handle(lookup, "cm_clip_mask_set", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        TRACK_COUNT = handle(lookup, "cm_clip_track_count", FunctionDescriptor.of(I32, ADDR));
        TRACK_GET = handle(lookup, "cm_clip_track_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR, ADDR, ADDR));
        TRACK_KEYS = handle(lookup, "cm_clip_track_keys", FunctionDescriptor.of(I32, ADDR, I32, ADDR, I32));
        TRACK_SET = handle(lookup, "cm_clip_track_set", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR, I32, ADDR, I32));
        TRACK_REMOVE = handle(lookup, "cm_clip_track_remove", FunctionDescriptor.of(I32, ADDR, I32));
        NOTIFY_COUNT = handle(lookup, "cm_clip_notify_count", FunctionDescriptor.of(I32, ADDR));
        NOTIFY_GET = handle(lookup, "cm_clip_notify_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR, ADDR));
        NOTIFY_ADD = handle(lookup, "cm_clip_notify_add", FunctionDescriptor.of(I32, ADDR, ADDR, F32, F32, ADDR));
        NOTIFY_REMOVE = handle(lookup, "cm_clip_notify_remove", FunctionDescriptor.of(I32, ADDR, I32));
        FIRE = handle(lookup, "cm_clip_fire", FunctionDescriptor.of(I32, ADDR, F32, F32, ADDR, I32));
        CURVE_COUNT = handle(lookup, "cm_clip_curve_count", FunctionDescriptor.of(I32, ADDR));
        CURVE_GET = handle(lookup, "cm_clip_curve_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR));
        CURVE_KEYS = handle(lookup, "cm_clip_curve_keys", FunctionDescriptor.of(I32, ADDR, I32, ADDR, I32));
        CURVE_SET = handle(lookup, "cm_clip_curve_set", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, I32));
        CURVE_FIND = handle(lookup, "cm_clip_curve_find", FunctionDescriptor.of(I32, ADDR, ADDR));
        CURVE_SAMPLE = handle(lookup, "cm_clip_curve_sample", FunctionDescriptor.of(F32, ADDR, I32, F32));
        MARKER_COUNT = handle(lookup, "cm_clip_marker_count", FunctionDescriptor.of(I32, ADDR));
        MARKER_GET = handle(lookup, "cm_clip_marker_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR));
        MARKER_ADD = handle(lookup, "cm_clip_marker_add", FunctionDescriptor.of(I32, ADDR, ADDR, F32));
        MARKER_REMOVE = handle(lookup, "cm_clip_marker_remove", FunctionDescriptor.of(I32, ADDR, I32));
        MARKER_POSITION = handle(lookup, "cm_clip_marker_position", FunctionDescriptor.of(I32, ADDR, F32, ADDR, ADDR));
        MARKER_TIME = handle(lookup, "cm_clip_marker_time", FunctionDescriptor.of(F32, ADDR, I32, F32));
        ROOT_GET = handle(lookup, "cm_clip_root_motion_get", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        ROOT_SET = handle(lookup, "cm_clip_root_motion_set", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        ADDITIVE_GET = handle(lookup, "cm_clip_additive_get", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR, ADDR));
        ADDITIVE_SET = handle(lookup, "cm_clip_additive_set", FunctionDescriptor.of(I32, ADDR, I32, F32, ADDR, I32));
        BIND_ROLES = handle(lookup, "cm_clip_bind_roles", FunctionDescriptor.of(ADDR, ADDR, ADDR, ADDR));
        TO_ROLES = handle(lookup, "cm_clip_to_roles", FunctionDescriptor.of(ADDR, ADDR, ADDR));
        SYNC_ADVANCE = handle(lookup, "cm_sync_advance", FunctionDescriptor.of(I32, ADDR, I32, I32, F32));
    }

    private CendaAnimClip() {
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor fd) {
        if (lookup == null) return null;
        return lookup.find(name)
            .map(symbol -> Linker.nativeLinker().downcallHandle(symbol, fd))
            .orElseThrow(() -> new IllegalStateException("Cenda animation clip symbol missing: " + name));
    }

    /** True when the native module loaded and passed its handshake. */
    public static boolean isAvailable() {
        return FAILURE == null;
    }

    /** Why the native module is unavailable, or null when it is available. */
    public static String unavailableReason() {
        return FAILURE;
    }

    static void requireAvailable() {
        if (FAILURE != null) throw new IllegalStateException("Cenda animation clips unavailable: " + FAILURE);
    }

    static String lastError() {
        try {
            return string((MemorySegment) LAST_ERROR.invokeExact());
        } catch (Throwable t) {
            return "unknown error";
        }
    }

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
