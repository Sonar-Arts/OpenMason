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
 * FFM symbols of the Cenda model document ({@code co_*} in {@code libcenda_kernels}, contract in
 * {@code cenda/native/kernels/include/cenda/model.h}; the {@code .omo} codec itself is native, in
 * {@code libomformat}, OpenMason #167). Only the library handshake and the method handles live here;
 * {@link OmoDocument} is the API, {@link OMOReader} and the editor's OMO serializer sit on top of it.
 *
 * <p>No Java fallback: when the library is missing or fails the {@code co_abi_version} handshake,
 * {@link #isAvailable()} is false, {@link #unavailableReason()} says why, and reading or writing an OMO fails.
 */
public final class CendaOmo {

    private static final Logger LOGGER = LoggerFactory.getLogger(CendaOmo.class);

    /** Must equal {@code CO_ABI_VERSION} in model.h (build-kernels.sh checks both sides). */
    static final int EXPECTED_ABI = 1;

    static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;
    static final AddressLayout ADDR = ValueLayout.ADDRESS;

    // Results.
    static final int OK = 0;
    static final int ERR_ARG = -1;
    static final int ERR_RANGE = -2;
    static final int ERR_FORMAT = -3;
    static final int ERR_VERSION = -4;

    // co_doc_get_string / co_doc_set_string fields.
    static final int FIELD_VERSION = 0;
    static final int FIELD_OBJECT_NAME = 1;
    static final int FIELD_MODEL_TYPE = 2;
    static final int FIELD_TEXTURE_FILE = 3;
    static final int FIELD_UV_MODE = 4;
    static final int FIELD_WRITTEN_VERSION = 5;

    // co_doc_flags.
    static final int HAS_MESH = 1;
    static final int HAS_FACE_TEXTURES = 1 << 1;
    static final int CRLF = 1 << 2;
    static final int HAS_1_9_DATA = 1 << 3;

    // co_doc_mesh_array.
    static final int MESH_VERTICES = 0;
    static final int MESH_TEX_COORDS = 1;
    static final int MESH_INDICES = 2;
    static final int MESH_TRIANGLE_FACE_IDS = 3;

    // co_doc_entry_*.
    static final int ENTRIES_IMAGES = 0;
    static final int ENTRIES_EXTRA = 1;

    static final int MORPH_PART_NORMALS = 1;

    // Struct sizes (model.h; static_asserts in model_abi.cpp).
    static final long GEOMETRY_BYTES = 40;
    static final long FACE_MAPPING_BYTES = 32;
    static final long MATERIAL_BYTES = 56;
    static final long PART_BYTES = 112;
    static final long BONE_BYTES = 72;
    static final long ATTACHMENT_BYTES = 72;

    private static final String FAILURE;
    static final MethodHandle LAST_ERROR;
    static final MethodHandle BYTES_DATA;
    static final MethodHandle BYTES_SIZE;
    static final MethodHandle BYTES_DESTROY;
    static final MethodHandle VERSION_COMPARE;
    static final MethodHandle DOC_CREATE;
    static final MethodHandle DOC_READ;
    static final MethodHandle DOC_DESTROY;
    static final MethodHandle DOC_WRITE;
    static final MethodHandle DOC_WRITE_MANIFEST;
    static final MethodHandle GET_STRING;
    static final MethodHandle SET_STRING;
    static final MethodHandle FLAGS;
    static final MethodHandle SET_FLAGS;
    static final MethodHandle GEOMETRY_GET;
    static final MethodHandle GEOMETRY_SET;
    static final MethodHandle MODEL_TRANSFORM_GET;
    static final MethodHandle MODEL_TRANSFORM_SET;
    static final MethodHandle MESH_ARRAY;
    static final MethodHandle MESH_SET_ARRAY;
    static final MethodHandle FACE_MAPPINGS_GET;
    static final MethodHandle FACE_MAPPINGS_SET;
    static final MethodHandle MATERIALS_GET;
    static final MethodHandle MATERIALS_SET;
    static final MethodHandle PARTS_GET;
    static final MethodHandle PARTS_SET;
    static final MethodHandle BONES_GET;
    static final MethodHandle BONES_SET;
    static final MethodHandle ATTACHMENTS_GET;
    static final MethodHandle ATTACHMENTS_SET;
    static final MethodHandle SKIN_COUNTS;
    static final MethodHandle SKIN_GET;
    static final MethodHandle SKIN_SET;
    static final MethodHandle SHADING_SMOOTH;
    static final MethodHandle SHADING_SET_SMOOTH;
    static final MethodHandle SHADING_SHARP_EDGES;
    static final MethodHandle SHADING_SET_SHARP_EDGES;
    static final MethodHandle SHADING_AUTO_SMOOTH;
    static final MethodHandle SHADING_SET_AUTO_SMOOTH;
    static final MethodHandle SHADING_CUSTOM_NORMALS;
    static final MethodHandle SHADING_SET_CUSTOM_NORMALS;
    static final MethodHandle VERTEX_GROUP_COUNTS;
    static final MethodHandle VERTEX_GROUPS_GET;
    static final MethodHandle VERTEX_GROUPS_SET;
    static final MethodHandle MORPH_COUNTS;
    static final MethodHandle MORPHS_GET;
    static final MethodHandle MORPHS_SET;
    static final MethodHandle TEXTURE;
    static final MethodHandle SET_TEXTURE;
    static final MethodHandle ENTRY_COUNT;
    static final MethodHandle ENTRY_GET;
    static final MethodHandle ENTRY_SET;

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
                    lookup.find("co_abi_version").orElseThrow(
                        () -> new IllegalStateException("library has no model module (co_abi_version missing)")),
                    FunctionDescriptor.of(I32));
                int have = (int) abi.invokeExact();
                if (have != EXPECTED_ABI) {
                    failure = "Cenda model module at " + lib + " has ABI " + have + " (expected " + EXPECTED_ABI
                        + "); rebuild with openmason-engine/cenda/build-kernels.sh";
                    lookup = null;
                } else {
                    LOGGER.info("Cenda model module (ABI {}) loaded from {}", have, lib);
                }
            }
        } catch (Throwable t) {
            failure = "Cenda model module failed to load: " + t;
            lookup = null;
        }
        if (failure != null) {
            LOGGER.error("OMO models unavailable: {}", failure);
        }
        FAILURE = failure;
        LAST_ERROR = handle(lookup, "co_last_error", FunctionDescriptor.of(ADDR));
        BYTES_DATA = handle(lookup, "co_bytes_data", FunctionDescriptor.of(ADDR, ADDR));
        BYTES_SIZE = handle(lookup, "co_bytes_size", FunctionDescriptor.of(I64, ADDR));
        BYTES_DESTROY = handle(lookup, "co_bytes_destroy", FunctionDescriptor.ofVoid(ADDR));
        VERSION_COMPARE = handle(lookup, "co_version_compare", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        DOC_CREATE = handle(lookup, "co_doc_create", FunctionDescriptor.of(ADDR));
        DOC_READ = handle(lookup, "co_doc_read", FunctionDescriptor.of(ADDR, ADDR, I64, ADDR));
        DOC_DESTROY = handle(lookup, "co_doc_destroy", FunctionDescriptor.ofVoid(ADDR));
        DOC_WRITE = handle(lookup, "co_doc_write", FunctionDescriptor.of(ADDR, ADDR));
        DOC_WRITE_MANIFEST = handle(lookup, "co_doc_write_manifest", FunctionDescriptor.of(ADDR, ADDR));
        GET_STRING = handle(lookup, "co_doc_get_string", FunctionDescriptor.of(ADDR, ADDR, I32));
        SET_STRING = handle(lookup, "co_doc_set_string", FunctionDescriptor.of(I32, ADDR, I32, ADDR));
        FLAGS = handle(lookup, "co_doc_flags", FunctionDescriptor.of(I32, ADDR));
        SET_FLAGS = handle(lookup, "co_doc_set_flags", FunctionDescriptor.of(I32, ADDR, I32));
        GEOMETRY_GET = handle(lookup, "co_doc_geometry_get", FunctionDescriptor.of(I32, ADDR, ADDR));
        GEOMETRY_SET = handle(lookup, "co_doc_geometry_set", FunctionDescriptor.of(I32, ADDR, ADDR));
        MODEL_TRANSFORM_GET = handle(lookup, "co_doc_model_transform_get", FunctionDescriptor.of(I32, ADDR, ADDR));
        MODEL_TRANSFORM_SET = handle(lookup, "co_doc_model_transform_set", FunctionDescriptor.of(I32, ADDR, ADDR));
        MESH_ARRAY = handle(lookup, "co_doc_mesh_array", FunctionDescriptor.of(I64, ADDR, I32, ADDR));
        MESH_SET_ARRAY = handle(lookup, "co_doc_mesh_set_array", FunctionDescriptor.of(I32, ADDR, I32, ADDR, I64));
        FACE_MAPPINGS_GET = handle(lookup, "co_doc_face_mappings_get", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        FACE_MAPPINGS_SET = handle(lookup, "co_doc_face_mappings_set", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        MATERIALS_GET = handle(lookup, "co_doc_materials_get", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        MATERIALS_SET = handle(lookup, "co_doc_materials_set", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        PARTS_GET = handle(lookup, "co_doc_parts_get", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        PARTS_SET = handle(lookup, "co_doc_parts_set", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        BONES_GET = handle(lookup, "co_doc_bones_get", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        BONES_SET = handle(lookup, "co_doc_bones_set", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        ATTACHMENTS_GET = handle(lookup, "co_doc_attachments_get", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        ATTACHMENTS_SET = handle(lookup, "co_doc_attachments_set", FunctionDescriptor.of(I32, ADDR, ADDR, I32));
        SKIN_COUNTS = handle(lookup, "co_doc_skin_counts", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR, ADDR));
        SKIN_GET = handle(lookup, "co_doc_skin_get",
            FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR));
        SKIN_SET = handle(lookup, "co_doc_skin_set",
            FunctionDescriptor.of(I32, ADDR, I32, ADDR, I32, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR));
        SHADING_SMOOTH = handle(lookup, "co_doc_shading_smooth", FunctionDescriptor.of(I64, ADDR, ADDR));
        SHADING_SET_SMOOTH = handle(lookup, "co_doc_shading_set_smooth", FunctionDescriptor.of(I32, ADDR, ADDR, I64));
        SHADING_SHARP_EDGES = handle(lookup, "co_doc_shading_sharp_edges", FunctionDescriptor.of(I64, ADDR, ADDR));
        SHADING_SET_SHARP_EDGES = handle(lookup, "co_doc_shading_set_sharp_edges",
            FunctionDescriptor.of(I32, ADDR, ADDR, I64));
        SHADING_AUTO_SMOOTH = handle(lookup, "co_doc_shading_auto_smooth", FunctionDescriptor.of(I32, ADDR, ADDR));
        SHADING_SET_AUTO_SMOOTH = handle(lookup, "co_doc_shading_set_auto_smooth",
            FunctionDescriptor.of(I32, ADDR, ADDR));
        SHADING_CUSTOM_NORMALS = handle(lookup, "co_doc_shading_custom_normals",
            FunctionDescriptor.of(I64, ADDR, ADDR, ADDR));
        SHADING_SET_CUSTOM_NORMALS = handle(lookup, "co_doc_shading_set_custom_normals",
            FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, I64));
        VERTEX_GROUP_COUNTS = handle(lookup, "co_doc_vertex_group_counts",
            FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR));
        VERTEX_GROUPS_GET = handle(lookup, "co_doc_vertex_groups_get",
            FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR));
        VERTEX_GROUPS_SET = handle(lookup, "co_doc_vertex_groups_set",
            FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR));
        MORPH_COUNTS = handle(lookup, "co_doc_morph_counts", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR));
        MORPHS_GET = handle(lookup, "co_doc_morphs_get",
            FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR));
        MORPHS_SET = handle(lookup, "co_doc_morphs_set",
            FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR));
        TEXTURE = handle(lookup, "co_doc_texture", FunctionDescriptor.of(I64, ADDR, ADDR));
        SET_TEXTURE = handle(lookup, "co_doc_set_texture", FunctionDescriptor.of(I32, ADDR, ADDR, I64));
        ENTRY_COUNT = handle(lookup, "co_doc_entry_count", FunctionDescriptor.of(I32, ADDR, I32));
        ENTRY_GET = handle(lookup, "co_doc_entry_get", FunctionDescriptor.of(I32, ADDR, I32, I32, ADDR, ADDR, ADDR));
        ENTRY_SET = handle(lookup, "co_doc_entry_set", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, I64));
    }

    private CendaOmo() {
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor fd) {
        if (lookup == null) return null;
        return lookup.find(name)
            .map(symbol -> Linker.nativeLinker().downcallHandle(symbol, fd))
            .orElseThrow(() -> new IllegalStateException("Cenda model symbol missing: " + name));
    }

    /** True when the native model module loaded and passed its handshake. */
    public static boolean isAvailable() {
        return FAILURE == null;
    }

    /** Why the native model module is unavailable, or null when it is available. */
    public static String unavailableReason() {
        return FAILURE;
    }

    static void requireAvailable() {
        if (FAILURE != null) {
            throw new IllegalStateException("Cenda model module unavailable: " + FAILURE);
        }
    }

    /**
     * Compares two OMO versions ({@code "<major>.<minor>"}) as integers: {@code "1.10"} is newer than {@code "1.9"}.
     *
     * @return negative, 0 or positive like {@link Comparable#compareTo}
     * @throws IllegalArgumentException when either version is malformed
     */
    public static int compareVersions(String a, String b) {
        requireAvailable();
        if (a == null || b == null) {
            throw new IllegalArgumentException("version is null");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment order = arena.allocate(I32);
            int rc = (int) VERSION_COMPARE.invokeExact(arena.allocateFrom(a), arena.allocateFrom(b), order);
            if (rc != OK) {
                throw new IllegalArgumentException("malformed OMO version: \"" + a + "\" / \"" + b + "\"");
            }
            return order.get(I32, 0);
        } catch (Throwable t) {
            throw rethrow(t);
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
