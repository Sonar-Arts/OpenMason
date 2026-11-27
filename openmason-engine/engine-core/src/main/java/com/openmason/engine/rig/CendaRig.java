package com.openmason.engine.rig;

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
 * FFM symbols of the Cenda rig ({@code cr_*} in {@code libcenda_kernels}, contract in
 * {@code cenda/native/kernels/include/cenda/rig.h}): skeletons, clips, posing, skinning and skin-weight
 * authoring. Only the library handshake and the method handles live here; {@link Rig}, {@link RigClip},
 * {@link RigPoser} and {@link Skin} are the API.
 *
 * <p>No Java fallback: when the library is missing or fails the {@code cr_abi_version} handshake,
 * {@link #isAvailable()} is false, {@link #unavailableReason()} says why, and creating a rig throws.
 */
public final class CendaRig {

    private static final Logger LOGGER = LoggerFactory.getLogger(CendaRig.class);

    /** Must equal {@code CR_ABI_VERSION} in rig.h (build-kernels.sh checks both sides). */
    static final int EXPECTED_ABI = 2;

    static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;
    static final AddressLayout ADDR = ValueLayout.ADDRESS;

    /** {@code cr_joint_desc}. */
    static final long JOINT_BYTES = 80;
    /** {@code cr_key}. */
    static final long KEY_BYTES = 48;
    /** {@code cr_track_desc}. */
    static final long TRACK_BYTES = 8;
    /** {@code cr_clip_desc}. */
    static final long CLIP_DESC_BYTES = 24;
    /** {@code cr_layer}: pointer + two floats. */
    static final long LAYER_BYTES = 16;
    /** {@code cr_joint_extra}. */
    static final long EXTRA_BYTES = 24;
    /** {@code cr_skin_entry}. */
    static final long SKIN_ENTRY_BYTES = 40;
    /** {@code cr_pose_op} (ABI 2). */
    static final long OP_BYTES = 48;
    /** {@code cr_root_motion}. */
    static final long ROOT_MOTION_BYTES = 16;

    static final int OK = 0;

    private static final String FAILURE;
    static final MethodHandle LAST_ERROR;
    static final MethodHandle RIG_CREATE;
    static final MethodHandle RIG_DESTROY;
    static final MethodHandle RIG_FLAGS;
    static final MethodHandle RIG_FIND;
    static final MethodHandle RIG_FIND_HEAD;
    static final MethodHandle RIG_REST_WORLD;
    static final MethodHandle CLIP_CREATE;
    static final MethodHandle CLIP_DESTROY;
    static final MethodHandle WRAP_TIME;
    static final MethodHandle CLIP_WEIGHT;
    static final MethodHandle CLIP_SAMPLE_TRACK;
    static final MethodHandle CLIP_TRACK_FOR;
    static final MethodHandle RIG_POSE;
    static final MethodHandle RIG_POSE_LOCALS;
    static final MethodHandle RIG_LOCAL_POSE;
    static final MethodHandle POSES_CREATE;
    static final MethodHandle POSES_DESTROY;
    static final MethodHandle POSES_GET;
    static final MethodHandle POSES_SET;
    static final MethodHandle POSES_RUN;
    static final MethodHandle POSES_EVALUATE;
    static final MethodHandle MASK_CREATE;
    static final MethodHandle MASK_DESTROY;
    static final MethodHandle MASK_FILL;
    static final MethodHandle MASK_SET;
    static final MethodHandle MASK_GET;
    static final MethodHandle MASK_SET_ALL;
    static final MethodHandle MASK_FROM_CLIP;
    static final MethodHandle INERTIA_CREATE;
    static final MethodHandle INERTIA_DESTROY;
    static final MethodHandle INERTIA_REQUEST;
    static final MethodHandle INERTIA_RESET;
    static final MethodHandle INERTIA_ACTIVE;
    static final MethodHandle CLIP_ROOT_MOTION;
    static final MethodHandle SKIN_CREATE;
    static final MethodHandle SKIN_DESTROY;
    static final MethodHandle SKIN_CLONE;
    static final MethodHandle SKIN_VERTEX_COUNT;
    static final MethodHandle SKIN_WEIGHTED_COUNT;
    static final MethodHandle SKIN_SET;
    static final MethodHandle SKIN_GET;
    static final MethodHandle SKIN_JOINT_WEIGHTS;
    static final MethodHandle SKIN_IMPORT;
    static final MethodHandle SKIN_EXPORT;
    static final MethodHandle SKIN_PACK;
    static final MethodHandle SKIN_AUTO_WEIGHTS;
    static final MethodHandle SKIN_PAINT;
    static final MethodHandle SKIN_NORMALIZE;
    static final MethodHandle SKIN_PRUNE;
    static final MethodHandle SKIN_REMOVE_JOINT;
    static final MethodHandle SKIN_REMAP_JOINTS;
    static final MethodHandle SKIN_MIRROR;
    static final MethodHandle SKIN_TRANSFER;
    static final MethodHandle SKIN_DEFORM;
    // First-person view (#161).
    static final MethodHandle PALETTE_HIDE;
    static final MethodHandle FIRST_PERSON_PROJECTION;
    static final MethodHandle STABILIZER_CREATE;
    static final MethodHandle STABILIZER_DESTROY;
    static final MethodHandle STABILIZER_RESET;
    static final MethodHandle STABILIZER_UPDATE;
    // Sockets and mounts (socket.h, Stonebreak #459).
    static final MethodHandle SOCKETS_CREATE;
    static final MethodHandle SOCKETS_DESTROY;
    static final MethodHandle SOCKETS_ADD;
    static final MethodHandle SOCKETS_ADD_REF;
    static final MethodHandle SOCKETS_COUNT;
    static final MethodHandle SOCKETS_FIND;
    static final MethodHandle SOCKETS_GET;
    static final MethodHandle SOCKETS_FRAME;
    static final MethodHandle CHAR_SOCKETS_INTO;
    static final MethodHandle MOUNTS_CREATE;
    static final MethodHandle MOUNTS_DESTROY;
    static final MethodHandle MOUNTS_SET;
    static final MethodHandle MOUNTS_REMOVE;
    static final MethodHandle MOUNTS_CLEAR;
    static final MethodHandle MOUNTS_COUNT;
    static final MethodHandle MOUNTS_FIND;
    static final MethodHandle MOUNTS_SET_POINT;
    static final MethodHandle MOUNTS_POINT_COUNT;
    static final MethodHandle MOUNTS_FIND_POINT;
    static final MethodHandle MOUNTS_TOTAL_POINTS;
    static final MethodHandle MOUNTS_POINT_BASE;
    static final MethodHandle MOUNTS_RESOLVE;
    /** {@code cr_first_person_desc} / {@code cr_stabilizer_desc} sizes. */
    static final long FIRST_PERSON_DESC_BYTES = 32;
    static final long STABILIZER_DESC_BYTES = 16;

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
                    lookup.find("cr_abi_version").orElseThrow(
                        () -> new IllegalStateException("library has no rig (cr_abi_version missing)")),
                    FunctionDescriptor.of(I32));
                int have = (int) abi.invokeExact();
                if (have != EXPECTED_ABI) {
                    failure = "Cenda rig at " + lib + " has ABI " + have + " (expected " + EXPECTED_ABI
                        + "); rebuild with openmason-engine/cenda/build-kernels.sh";
                    lookup = null;
                } else {
                    LOGGER.info("Cenda rig (ABI {}) loaded from {}", have, lib);
                }
            }
        } catch (Throwable t) {
            failure = "Cenda rig failed to load: " + t;
            lookup = null;
        }
        if (failure != null) {
            LOGGER.error("Rig unavailable: {}", failure);
        }
        FAILURE = failure;
        // No call reaches back into Java, and none runs long enough to stall a GC (pose: microseconds;
        // skin ops are editor actions), but keep the heavy authoring calls non-critical.
        Linker.Option critical = Linker.Option.critical(false);
        LAST_ERROR = handle(lookup, "cr_last_error", FunctionDescriptor.of(ADDR));
        RIG_CREATE = handle(lookup, "cr_rig_create", FunctionDescriptor.of(ADDR, ADDR, I32, ADDR, ADDR));
        RIG_DESTROY = handle(lookup, "cr_rig_destroy", FunctionDescriptor.ofVoid(ADDR));
        RIG_FLAGS = handle(lookup, "cr_rig_flags", FunctionDescriptor.of(I32, ADDR), critical);
        RIG_FIND = handle(lookup, "cr_rig_find", FunctionDescriptor.of(I32, ADDR, ADDR));
        RIG_FIND_HEAD = handle(lookup, "cr_rig_find_head", FunctionDescriptor.of(I32, ADDR), critical);
        RIG_REST_WORLD = handle(lookup, "cr_rig_rest_world", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        CLIP_CREATE = handle(lookup, "cr_clip_create",
            FunctionDescriptor.of(ADDR, ADDR, ADDR, I32, ADDR, I32, ADDR, ADDR, ADDR, I32));
        CLIP_DESTROY = handle(lookup, "cr_clip_destroy", FunctionDescriptor.ofVoid(ADDR));
        WRAP_TIME = handle(lookup, "cr_wrap_time", FunctionDescriptor.of(F32, F32, F32, I32), critical);
        CLIP_WEIGHT = handle(lookup, "cr_clip_weight", FunctionDescriptor.of(F32, ADDR, F32), critical);
        CLIP_SAMPLE_TRACK = handle(lookup, "cr_clip_sample_track",
            FunctionDescriptor.of(I32, ADDR, I32, F32, ADDR, ADDR, ADDR));
        CLIP_TRACK_FOR = handle(lookup, "cr_clip_track_for", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        RIG_POSE = handle(lookup, "cr_rig_pose",
            FunctionDescriptor.of(I32, ADDR, ADDR, F32, ADDR, I32, ADDR, I32, ADDR, ADDR, ADDR, ADDR));
        RIG_POSE_LOCALS = handle(lookup, "cr_rig_pose_locals",
            FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, I32, ADDR, ADDR, ADDR, ADDR));
        RIG_LOCAL_POSE = handle(lookup, "cr_rig_local_pose",
            FunctionDescriptor.of(I32, ADDR, ADDR, F32, ADDR, I32, ADDR));
        POSES_CREATE = handle(lookup, "cr_poses_create", FunctionDescriptor.of(ADDR, ADDR, I32));
        POSES_DESTROY = handle(lookup, "cr_poses_destroy", FunctionDescriptor.ofVoid(ADDR));
        POSES_GET = handle(lookup, "cr_poses_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR));
        POSES_SET = handle(lookup, "cr_poses_set", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR));
        POSES_RUN = handle(lookup, "cr_poses_run", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        POSES_EVALUATE = handle(lookup, "cr_poses_evaluate",
            FunctionDescriptor.of(I32, ADDR, ADDR, I32, I32, ADDR, I32, ADDR, ADDR, ADDR, ADDR));
        MASK_CREATE = handle(lookup, "cr_mask_create", FunctionDescriptor.of(ADDR, ADDR, F32));
        MASK_DESTROY = handle(lookup, "cr_mask_destroy", FunctionDescriptor.ofVoid(ADDR));
        MASK_FILL = handle(lookup, "cr_mask_fill", FunctionDescriptor.of(I32, ADDR, F32));
        MASK_SET = handle(lookup, "cr_mask_set", FunctionDescriptor.of(I32, ADDR, I32, F32, I32, I32));
        MASK_GET = handle(lookup, "cr_mask_get", FunctionDescriptor.of(I32, ADDR, ADDR));
        MASK_SET_ALL = handle(lookup, "cr_mask_set_all", FunctionDescriptor.of(I32, ADDR, ADDR));
        MASK_FROM_CLIP = handle(lookup, "cr_mask_from_clip", FunctionDescriptor.of(I32, ADDR, ADDR));
        INERTIA_CREATE = handle(lookup, "cr_inertia_create", FunctionDescriptor.of(ADDR, ADDR));
        INERTIA_DESTROY = handle(lookup, "cr_inertia_destroy", FunctionDescriptor.ofVoid(ADDR));
        INERTIA_REQUEST = handle(lookup, "cr_inertia_request", FunctionDescriptor.of(I32, ADDR, F32), critical);
        INERTIA_RESET = handle(lookup, "cr_inertia_reset", FunctionDescriptor.of(I32, ADDR), critical);
        INERTIA_ACTIVE = handle(lookup, "cr_inertia_active", FunctionDescriptor.of(I32, ADDR), critical);
        CLIP_ROOT_MOTION = handle(lookup, "cr_clip_root_motion",
            FunctionDescriptor.of(I32, ADDR, ADDR, I32, I32, F32, F32, ADDR));
        SKIN_CREATE = handle(lookup, "cr_skin_create", FunctionDescriptor.of(ADDR, I32));
        SKIN_DESTROY = handle(lookup, "cr_skin_destroy", FunctionDescriptor.ofVoid(ADDR));
        SKIN_CLONE = handle(lookup, "cr_skin_clone", FunctionDescriptor.of(ADDR, ADDR));
        SKIN_VERTEX_COUNT = handle(lookup, "cr_skin_vertex_count", FunctionDescriptor.of(I32, ADDR), critical);
        SKIN_WEIGHTED_COUNT = handle(lookup, "cr_skin_weighted_count", FunctionDescriptor.of(I32, ADDR));
        SKIN_SET = handle(lookup, "cr_skin_set", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, I32));
        SKIN_GET = handle(lookup, "cr_skin_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR));
        SKIN_JOINT_WEIGHTS = handle(lookup, "cr_skin_joint_weights", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR));
        SKIN_IMPORT = handle(lookup, "cr_skin_import", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        SKIN_EXPORT = handle(lookup, "cr_skin_export", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        SKIN_PACK = handle(lookup, "cr_skin_pack", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR));
        SKIN_AUTO_WEIGHTS = handle(lookup, "cr_skin_auto_weights",
            FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, I32, I32, ADDR, I32, F32, F32));
        SKIN_PAINT = handle(lookup, "cr_skin_paint",
            FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR, I32, ADDR, ADDR, F32, I32, I32, F32));
        SKIN_NORMALIZE = handle(lookup, "cr_skin_normalize", FunctionDescriptor.of(I32, ADDR));
        SKIN_PRUNE = handle(lookup, "cr_skin_prune", FunctionDescriptor.of(I32, ADDR, F32));
        SKIN_REMOVE_JOINT = handle(lookup, "cr_skin_remove_joint", FunctionDescriptor.of(I32, ADDR, I32));
        SKIN_REMAP_JOINTS = handle(lookup, "cr_skin_remap_joints", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        SKIN_MIRROR = handle(lookup, "cr_skin_mirror",
            FunctionDescriptor.of(I32, ADDR, ADDR, I32, I32, F32, ADDR, I32));
        SKIN_TRANSFER = handle(lookup, "cr_skin_transfer", FunctionDescriptor.of(ADDR, ADDR, ADDR, ADDR, I32));
        SKIN_DEFORM = handle(lookup, "cr_skin_deform",
            FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, I32, ADDR, I32, ADDR));
        PALETTE_HIDE = handle(lookup, "cr_palette_hide", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR), critical);
        FIRST_PERSON_PROJECTION = handle(lookup, "cr_first_person_projection",
            FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR, ADDR), critical);
        STABILIZER_CREATE = handle(lookup, "cr_stabilizer_create", FunctionDescriptor.of(ADDR, ADDR));
        STABILIZER_DESTROY = handle(lookup, "cr_stabilizer_destroy", FunctionDescriptor.ofVoid(ADDR));
        STABILIZER_RESET = handle(lookup, "cr_stabilizer_reset", FunctionDescriptor.of(I32, ADDR), critical);
        STABILIZER_UPDATE = handle(lookup, "cr_stabilizer_update",
            FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, F32, ADDR), critical);
        SOCKETS_CREATE = handle(lookup, "cr_sockets_create", FunctionDescriptor.of(ADDR, ADDR));
        SOCKETS_DESTROY = handle(lookup, "cr_sockets_destroy", FunctionDescriptor.ofVoid(ADDR));
        SOCKETS_ADD = handle(lookup, "cr_sockets_add", FunctionDescriptor.of(I32, ADDR, ADDR, I32, ADDR, ADDR, ADDR, I32));
        SOCKETS_ADD_REF = handle(lookup, "cr_sockets_add_ref",
            FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR, I32));
        SOCKETS_COUNT = handle(lookup, "cr_sockets_count", FunctionDescriptor.of(I32, ADDR), critical);
        SOCKETS_FIND = handle(lookup, "cr_sockets_find", FunctionDescriptor.of(I32, ADDR, ADDR));
        SOCKETS_GET = handle(lookup, "cr_sockets_get", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR, ADDR));
        SOCKETS_FRAME = handle(lookup, "cr_sockets_frame",
            FunctionDescriptor.of(I32, ADDR, I32, ADDR, I32, ADDR, ADDR, ADDR), critical);
        CHAR_SOCKETS_INTO = handle(lookup, "cc_char_sockets_into", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        MOUNTS_CREATE = handle(lookup, "cr_mounts_create", FunctionDescriptor.of(ADDR));
        MOUNTS_DESTROY = handle(lookup, "cr_mounts_destroy", FunctionDescriptor.ofVoid(ADDR));
        MOUNTS_SET = handle(lookup, "cr_mounts_set", FunctionDescriptor.of(I32, ADDR, ADDR, I32, ADDR, ADDR));
        MOUNTS_REMOVE = handle(lookup, "cr_mounts_remove", FunctionDescriptor.of(I32, ADDR, ADDR));
        MOUNTS_CLEAR = handle(lookup, "cr_mounts_clear", FunctionDescriptor.of(I32, ADDR));
        MOUNTS_COUNT = handle(lookup, "cr_mounts_count", FunctionDescriptor.of(I32, ADDR), critical);
        MOUNTS_FIND = handle(lookup, "cr_mounts_find", FunctionDescriptor.of(I32, ADDR, ADDR));
        MOUNTS_SET_POINT = handle(lookup, "cr_mounts_set_point", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR));
        MOUNTS_POINT_COUNT = handle(lookup, "cr_mounts_point_count", FunctionDescriptor.of(I32, ADDR, I32), critical);
        MOUNTS_FIND_POINT = handle(lookup, "cr_mounts_find_point", FunctionDescriptor.of(I32, ADDR, I32, ADDR));
        MOUNTS_TOTAL_POINTS = handle(lookup, "cr_mounts_total_points", FunctionDescriptor.of(I32, ADDR), critical);
        MOUNTS_POINT_BASE = handle(lookup, "cr_mounts_point_base", FunctionDescriptor.of(I32, ADDR, I32), critical);
        MOUNTS_RESOLVE = handle(lookup, "cr_mounts_resolve",
            FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, I32, ADDR, ADDR, ADDR, ADDR, ADDR), critical);
    }

    private CendaRig() {
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor fd, Linker.Option... options) {
        if (lookup == null) return null;
        return lookup.find(name)
            .map(symbol -> Linker.nativeLinker().downcallHandle(symbol, fd, options))
            .orElseThrow(() -> new IllegalStateException("Cenda rig symbol missing: " + name));
    }

    /** True when the native rig loaded and passed its handshake. */
    public static boolean isAvailable() {
        return FAILURE == null;
    }

    /** Why the native rig is unavailable, or null when it is available. */
    public static String unavailableReason() {
        return FAILURE;
    }

    static void requireAvailable() {
        if (FAILURE != null) {
            throw new IllegalStateException("Cenda rig unavailable: " + FAILURE);
        }
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

    /** Marshals a list of strings as a {@code const char* const*} array (null entries stay null). */
    static MemorySegment strings(Arena arena, java.util.List<String> values) {
        if (values == null || values.isEmpty()) return MemorySegment.NULL;
        MemorySegment array = arena.allocate(ADDR, values.size());
        for (int i = 0; i < values.size(); i++) {
            String v = values.get(i);
            array.setAtIndex(ADDR, i, v == null ? MemorySegment.NULL : arena.allocateFrom(v));
        }
        return array;
    }
}
