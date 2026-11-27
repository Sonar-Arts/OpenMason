package com.openmason.engine.modeling.editable;

import com.openmason.engine.cenda.CendaKernels;
import com.openmason.engine.format.omo.CendaOmo;
import com.openmason.engine.modeling.uv.FaceTextureMapping;
import com.openmason.engine.modeling.uv.IFaceTextureManager;
import org.joml.Vector3f;
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

/**
 * FFM binding of the native mesh pipeline ({@code co_mesh_*} in {@code libcenda_kernels}; contract in
 * {@code cenda/native/kernels/include/cenda/model_mesh.h}, OpenMason #175 phase F3): soup weld, soup import (polygon
 * loop reconstruction), polygon triangulation, render mesh derivation (corners, projected / authored UVs) and the
 * topology arrays, plus an incremental positions-only refresh ({@link #moveVertices}). Every rule is native; this
 * class marshals, owns the handle and copies results out. Part of the {@code co_*} ABI: available exactly when
 * {@link CendaOmo} is (same library, same {@code co_abi_version} handshake). No Java fallback.
 *
 * <p>A mesh belongs to one thread at a time. Closing is optional: an unreachable mesh is destroyed by a cleaner.
 * {@link #triangulate} is stateless and reentrant.
 */
public final class CendaMesh implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(CendaMesh.class);
    private static final Cleaner CLEANER = Cleaner.create();

    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;
    private static final AddressLayout ADDR = ValueLayout.ADDRESS;

    // --- Constants (model_mesh.h) -------------------------------------------------------------------------------

    public static final float DEFAULT_WELD_EPSILON = 1e-4f;

    public static final int STAGE_WELD = 1;
    public static final int STAGE_FACES = 1 << 1;
    public static final int STAGE_TRIANGULATE = 1 << 2;
    public static final int STAGE_RENDER = 1 << 3;
    public static final int STAGE_TOPOLOGY = 1 << 4;
    public static final int STAGE_ALL = 0x1F;

    public static final int FACE_AUTHORED_UVS = 1;
    public static final int FACE_WALK_FAILED = 1 << 1;
    public static final int FACE_TRI_DEGENERATE_NORMAL = 1 << 2;
    public static final int FACE_TRI_ZERO_AREA = 1 << 3;
    public static final int FACE_TRI_NO_EAR = 1 << 4;
    public static final int FACE_UV_PROJECTED = 1 << 5;
    public static final int FACE_UV_DEGENERATE = 1 << 6;

    public static final int WARN_BOUNDARY_WALK_FAILED = 0;
    public static final int WARN_DEGENERATE_FACE = 1;
    public static final int WARN_CORRUPT_INDICES = 2;

    public static final int MAPPING_AUTHORED_UVS = 1;

    public static final int CHANGED_POSITIONS = 1;
    public static final int CHANGED_UVS = 1 << 1;
    public static final int CHANGED_INDICES = 1 << 2;
    public static final int CHANGED_TOPOLOGY = 1 << 3;

    public static final int A_SOUP_TO_VERTEX = 0;
    public static final int A_VERTEX_SOUP_OFFSETS = 1;
    public static final int A_VERTEX_SOUP = 2;
    public static final int A_VERTEX_POSITIONS = 3;
    public static final int A_FACE_IDS = 4;
    public static final int A_LOOP_OFFSETS = 5;
    public static final int A_LOOP_VERTICES = 6;
    public static final int A_LOOP_UVS = 7;
    public static final int A_FACE_FLAGS = 8;
    public static final int A_CORNER_FACES = 9;
    public static final int A_FACE_SLOTS = 10;
    public static final int A_WARNINGS = 11;
    public static final int A_TRI_INDICES = 12;
    public static final int A_TRI_FACE_IDS = 13;
    public static final int A_CORNER_POSITIONS = 14;
    public static final int A_CORNER_UVS = 15;
    public static final int A_VERTEX_CORNER_OFFSETS = 16;
    public static final int A_VERTEX_CORNERS = 17;
    public static final int A_EDGE_VERTICES = 18;
    public static final int A_EDGE_FACE_OFFSETS = 19;
    public static final int A_EDGE_FACES = 20;
    public static final int A_CORNER_EDGES = 21;
    public static final int A_VERTEX_EDGE_OFFSETS = 22;
    public static final int A_VERTEX_EDGES = 23;
    public static final int A_VERTEX_FACE_OFFSETS = 24;
    public static final int A_VERTEX_FACES = 25;
    public static final int A_FACE_ADJ_OFFSETS = 26;
    public static final int A_FACE_ADJ = 27;
    public static final int A_FACE_ADJ_EDGES = 28;
    public static final int A_FACE_NORMALS = 29;
    public static final int A_FACE_CENTROIDS = 30;
    public static final int A_FACE_AREAS = 31;
    public static final int A_EDGE_DIHEDRAL = 32;
    public static final int A_EDGE_KIND = 33;
    public static final int A_MOVED_FACES = 34;
    public static final int A_COUNT = 35;

    /** Arrays holding floats (the others hold int32). */
    public static boolean isFloatArray(int which) {
        return switch (which) {
            case A_VERTEX_POSITIONS, A_LOOP_UVS, A_CORNER_POSITIONS, A_CORNER_UVS, A_FACE_NORMALS,
                 A_FACE_CENTROIDS, A_FACE_AREAS, A_EDGE_DIHEDRAL -> true;
            default -> false;
        };
    }

    private static final int CO_ERR_RANGE = -2;
    private static final long MAPPING_BYTES = 32;

    // --- Library ------------------------------------------------------------------------------------------------

    private static final String FAILURE;
    private static final MethodHandle CREATE;
    private static final MethodHandle CLONE;
    private static final MethodHandle DESTROY;
    private static final MethodHandle INFO;
    private static final MethodHandle ARRAY;
    private static final MethodHandle SET_MAPPINGS;
    private static final MethodHandle WELD;
    private static final MethodHandle IMPORT;
    private static final MethodHandle SET_FACES;
    private static final MethodHandle BUILD;
    private static final MethodHandle MOVE;
    private static final MethodHandle FIND_EDGE;
    private static final MethodHandle TRIANGULATE;
    private static final MethodHandle LAST_ERROR;

    static {
        String failure = null;
        SymbolLookup lookup = null;
        if (!CendaOmo.isAvailable()) {
            failure = CendaOmo.unavailableReason();
        } else {
            try {
                Path lib = CendaKernels.libraryPath().orElseThrow();
                lookup = SymbolLookup.libraryLookup(lib, Arena.global());
                lookup.find("co_mesh_create").orElseThrow(
                        () -> new IllegalStateException("library has no mesh pipeline (co_mesh_create missing)"));
            } catch (Throwable t) {
                failure = "Cenda mesh pipeline failed to load: " + t;
                lookup = null;
            }
        }
        if (failure != null) {
            LOGGER.error("Native mesh pipeline unavailable: {}", failure);
        }
        FAILURE = failure;
        CREATE = handle(lookup, "co_mesh_create", FunctionDescriptor.of(ADDR));
        CLONE = handle(lookup, "co_mesh_clone", FunctionDescriptor.of(ADDR, ADDR));
        DESTROY = handle(lookup, "co_mesh_destroy", FunctionDescriptor.ofVoid(ADDR));
        INFO = handle(lookup, "co_mesh_info_get", FunctionDescriptor.of(I32, ADDR, ADDR));
        ARRAY = handle(lookup, "co_mesh_array", FunctionDescriptor.of(I64, ADDR, I32, ADDR));
        SET_MAPPINGS = handle(lookup, "co_mesh_set_mappings", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, I32));
        WELD = handle(lookup, "co_mesh_weld", FunctionDescriptor.of(I32, ADDR, ADDR, I64, F32));
        IMPORT = handle(lookup, "co_mesh_import",
                FunctionDescriptor.of(I32, ADDR, ADDR, I64, ADDR, I64, ADDR, I64, ADDR, I64, F32, I32));
        SET_FACES = handle(lookup, "co_mesh_set_faces",
                FunctionDescriptor.of(I32, ADDR, ADDR, I32, ADDR, I32, ADDR, ADDR, ADDR, ADDR, I32, I32));
        BUILD = handle(lookup, "co_mesh_build", FunctionDescriptor.of(I32, ADDR, I32));
        MOVE = handle(lookup, "co_mesh_move_vertices", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, I64, ADDR));
        FIND_EDGE = handle(lookup, "co_mesh_find_edge", FunctionDescriptor.of(I32, ADDR, I32, I32));
        TRIANGULATE = handle(lookup, "co_polygon_triangulate", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR));
        LAST_ERROR = handle(lookup, "co_last_error", FunctionDescriptor.of(ADDR));
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor fd) {
        if (lookup == null) return null;
        return lookup.find(name)
                .map(symbol -> Linker.nativeLinker().downcallHandle(symbol, fd))
                .orElseThrow(() -> new IllegalStateException("Cenda mesh symbol missing: " + name));
    }

    /** True when the native mesh pipeline is usable. */
    public static boolean isAvailable() {
        return FAILURE == null;
    }

    /** Why the native mesh pipeline is unavailable, or null when it is available. */
    public static String unavailableReason() {
        return FAILURE;
    }

    private static void requireAvailable() {
        if (FAILURE != null) throw new IllegalStateException("Cenda mesh pipeline unavailable: " + FAILURE);
    }

    private static String lastError() {
        try {
            MemorySegment s = (MemorySegment) LAST_ERROR.invokeExact();
            if (s == null || s.equals(MemorySegment.NULL)) return "unknown error";
            return s.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            return "unknown error";
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }

    private static int check(int rc, String fn) {
        if (rc < 0) {
            String why = lastError();
            throw rc == CO_ERR_RANGE
                    ? new IndexOutOfBoundsException(fn + ": " + why)
                    : new IllegalArgumentException(fn + ": " + why);
        }
        return rc;
    }

    // --- Lifetime -----------------------------------------------------------------------------------------------

    private static final class State implements Runnable {
        private MemorySegment handle;

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

    private final State state;
    private final Cleaner.Cleanable cleanable;

    private CendaMesh(MemorySegment handle) {
        if (handle == null || handle.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("co_mesh: " + lastError());
        }
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
    }

    /** An empty mesh (no stages, no mappings). */
    public static CendaMesh create() {
        requireAvailable();
        try {
            return new CendaMesh((MemorySegment) CREATE.invokeExact());
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** An independent copy (stages, arrays, mappings). */
    public CendaMesh copy() {
        try {
            return new CendaMesh((MemorySegment) CLONE.invokeExact(h()));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public void close() {
        cleanable.clean();
    }

    private MemorySegment h() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("CendaMesh is closed");
        return h;
    }

    // --- Info / arrays ------------------------------------------------------------------------------------------

    /** {@code co_mesh_info}. */
    public record Info(int stages, int soupVertexCount, int vertexCount, int faceCount, int faceIdUpperBound,
                       int cornerCount, int triangleCount, int edgeCount, int warningCount, boolean uniform,
                       int firstFaceVertexCount, int approximateOrderSets, long revision) {
    }

    public Info info() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(56, 8);
            check((int) INFO.invokeExact(h(), out), "co_mesh_info_get");
            return new Info(out.get(I32, 0), out.get(I32, 4), out.get(I32, 8), out.get(I32, 12), out.get(I32, 16),
                    out.get(I32, 20), out.get(I32, 24), out.get(I32, 28), out.get(I32, 32), out.get(I32, 36) != 0,
                    out.get(I32, 40), out.get(I32, 44), out.get(I64, 48));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The borrowed elements of an array (valid until the next mutating call), or null when its stage is not current. */
    private MemorySegment borrow(int which) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDR);
            long count = (long) ARRAY.invokeExact(h(), which, out);
            if (count < 0) return null;
            if (count == 0) return MemorySegment.NULL;
            return out.get(ADDR, 0).reinterpret(count * 4L);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private MemorySegment require(int which) {
        MemorySegment s = borrow(which);
        if (s == null) {
            throw new IllegalStateException("co_mesh_array(" + which + "): " + lastError());
        }
        return s;
    }

    /** A copy of an int32 array (see model_mesh.h); throws when its stage is not current. */
    public int[] ints(int which) {
        MemorySegment s = require(which);
        return s.equals(MemorySegment.NULL) ? new int[0] : s.toArray(I32);
    }

    /** A copy of a float array (see model_mesh.h); throws when its stage is not current. */
    public float[] floats(int which) {
        MemorySegment s = require(which);
        return s.equals(MemorySegment.NULL) ? new float[0] : s.toArray(F32);
    }

    /** True when the array's stage is current. */
    public boolean has(int which) {
        return borrow(which) != null;
    }

    /** A CSR pair (offsets, values) as one {@code int[rows][]}. */
    public int[][] csr(int offsetsArray, int valuesArray) {
        int[] offsets = ints(offsetsArray);
        int[] values = ints(valuesArray);
        int rows = Math.max(0, offsets.length - 1);
        int[][] out = new int[rows][];
        for (int r = 0; r < rows; r++) {
            out[r] = java.util.Arrays.copyOfRange(values, offsets[r], offsets[r + 1]);
        }
        return out;
    }

    // --- Settings -----------------------------------------------------------------------------------------------

    /**
     * Replaces the face mapping table with the mappings {@code textureManager} holds for the mesh's live faces
     * ({@code co_mesh_set_mappings}; {@link FaceTextureMapping#authoredUVs()} -> {@link #MAPPING_AUTHORED_UVS}).
     * A null manager clears it. The id passed is the face's own id.
     */
    public void setMappings(EditableMesh mesh, IFaceTextureManager textureManager) {
        if (textureManager == null || mesh == null || mesh.faceCount() == 0) {
            setMappings(new int[0], null, 0);
            return;
        }
        int n = mesh.faceCount();
        int[] faceIds = new int[n];
        FaceTextureMapping[] mappings = new FaceTextureMapping[n];
        int count = 0;
        for (EditableFace face : mesh.faces()) {
            FaceTextureMapping m = textureManager.getFaceMapping(face.faceId());
            if (m != null) {
                faceIds[count] = face.faceId();
                mappings[count] = m;
                count++;
            }
        }
        setMappings(faceIds, mappings, count);
    }

    /** Replaces the face mapping table: {@code mappings[i]} applies to face {@code faceIds[i]}, i < count. */
    public void setMappings(int[] faceIds, FaceTextureMapping[] mappings, int count) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment table = count == 0 ? MemorySegment.NULL : arena.allocate(MAPPING_BYTES * count, 4);
            MemorySegment flags = count == 0 ? MemorySegment.NULL : arena.allocate(4L * count, 4);
            for (int i = 0; i < count; i++) {
                FaceTextureMapping m = mappings[i];
                long o = MAPPING_BYTES * i;
                FaceTextureMapping.UVRegion r = m.uvRegion() != null ? m.uvRegion() : FaceTextureMapping.FULL_REGION;
                table.set(I32, o, faceIds[i]);
                table.set(I32, o + 4, m.materialId());
                table.set(F32, o + 8, r.u0());
                table.set(F32, o + 12, r.v0());
                table.set(F32, o + 16, r.u1());
                table.set(F32, o + 20, r.v1());
                table.set(I32, o + 24, m.uvRotation() != null ? m.uvRotation().degrees() : 0);
                table.set(I32, o + 28, m.autoResize() ? 1 : 0);
                flags.setAtIndex(I32, i, m.authoredUVs() ? MAPPING_AUTHORED_UVS : 0);
            }
            check((int) SET_MAPPINGS.invokeExact(h(), table, flags, count), "co_mesh_set_mappings");
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // --- Stages -------------------------------------------------------------------------------------------------

    private static MemorySegment floatsOrNull(Arena arena, float[] a) {
        return a == null || a.length == 0 ? MemorySegment.NULL : arena.allocateFrom(F32, a);
    }

    private static MemorySegment intsOrNull(Arena arena, int[] a) {
        return a == null || a.length == 0 ? MemorySegment.NULL : arena.allocateFrom(I32, a);
    }

    /** {@code co_mesh_weld}: VertexWelder.weld. {@code positions.length} must be a multiple of 3. */
    public void weld(float[] positions, float epsilon) {
        int len = positions == null ? 0 : positions.length;
        if (len % 3 != 0) throw new IllegalArgumentException("Vertex array length must be divisible by 3: " + len);
        try (Arena arena = Arena.ofConfined()) {
            check((int) WELD.invokeExact(h(), floatsOrNull(arena, positions), (long) (len / 3), epsilon),
                    "co_mesh_weld");
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * {@code co_mesh_import}: MeshImporter.importSoup (= WELD + FACES) plus the extra {@code stages}.
     * {@code positions.length} must be a multiple of 3; texCoords / triFaceIds may be null.
     */
    public void importSoup(float[] positions, float[] texCoords, int[] indices, int[] triFaceIds, float epsilon,
                           int stages) {
        int len = positions == null ? 0 : positions.length;
        if (len % 3 != 0) throw new IllegalArgumentException("Vertex array length must be divisible by 3: " + len);
        try (Arena arena = Arena.ofConfined()) {
            check((int) IMPORT.invokeExact(h(), floatsOrNull(arena, positions), (long) (len / 3),
                    floatsOrNull(arena, texCoords), (long) (texCoords == null ? 0 : texCoords.length),
                    intsOrNull(arena, indices), (long) (indices == null ? 0 : indices.length),
                    intsOrNull(arena, triFaceIds), (long) (triFaceIds == null ? 0 : triFaceIds.length),
                    epsilon, stages), "co_mesh_import");
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code co_mesh_set_faces} with the mesh's own positions. */
    public void setFaces(EditableMesh mesh, int stages) {
        setFaces(mesh, null, stages);
    }

    /**
     * {@code co_mesh_set_faces}: hands over an {@link EditableMesh} (faces in insertion order, authored corner UVs,
     * {@link EditableMesh#faceIdUpperBound()}) then builds {@code stages}. {@code positions} (3 floats per vertex)
     * overrides the mesh's positions when not null.
     */
    public void setFaces(EditableMesh mesh, float[] positions, int stages) {
        int vertexCount = mesh.vertexCount();
        int faceCount = mesh.faceCount();
        int corners = 0;
        boolean anyUVs = false;
        for (EditableFace face : mesh.faces()) {
            corners += face.loopLength();
            anyUVs |= face.hasAuthoredUVs();
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pos;
            if (positions != null) {
                if (positions.length != vertexCount * 3) {
                    throw new IllegalArgumentException("positions: " + positions.length + " floats for "
                            + vertexCount + " vertices");
                }
                pos = floatsOrNull(arena, positions);
            } else if (vertexCount == 0) {
                pos = MemorySegment.NULL;
            } else {
                pos = arena.allocate(12L * vertexCount, 4);
                Vector3f p = new Vector3f();
                for (int v = 0; v < vertexCount; v++) {
                    mesh.position(v, p);
                    pos.setAtIndex(F32, 3L * v, p.x);
                    pos.setAtIndex(F32, 3L * v + 1, p.y);
                    pos.setAtIndex(F32, 3L * v + 2, p.z);
                }
            }
            MemorySegment ids = faceCount == 0 ? MemorySegment.NULL : arena.allocate(4L * faceCount, 4);
            MemorySegment offsets = arena.allocate(4L * (faceCount + 1), 4);
            MemorySegment loops = corners == 0 ? MemorySegment.NULL : arena.allocate(4L * corners, 4);
            MemorySegment hasUVs = anyUVs ? arena.allocate(4L * faceCount, 4) : MemorySegment.NULL;
            MemorySegment uvs = anyUVs ? arena.allocate(8L * corners, 4) : MemorySegment.NULL;
            int f = 0;
            int c = 0;
            offsets.setAtIndex(I32, 0, 0);
            for (EditableFace face : mesh.faces()) {
                ids.setAtIndex(I32, f, face.faceId());
                int n = face.loopLength();
                float[] cornerUVs = anyUVs ? face.cornerUVs() : null;
                for (int i = 0; i < n; i++) {
                    loops.setAtIndex(I32, c + i, face.vertexAt(i));
                    if (anyUVs) {
                        uvs.setAtIndex(F32, 2L * (c + i), cornerUVs != null ? cornerUVs[2 * i] : 0f);
                        uvs.setAtIndex(F32, 2L * (c + i) + 1, cornerUVs != null ? cornerUVs[2 * i + 1] : 0f);
                    }
                }
                if (anyUVs) hasUVs.setAtIndex(I32, f, cornerUVs != null ? 1 : 0);
                c += n;
                f++;
                offsets.setAtIndex(I32, f, c);
            }
            check((int) SET_FACES.invokeExact(h(), pos, vertexCount, ids, faceCount, offsets, loops, hasUVs, uvs,
                    mesh.faceIdUpperBound(), stages), "co_mesh_set_faces");
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code co_mesh_build}: (re)builds TRIANGULATE / RENDER / TOPOLOGY from the current faces and mappings. */
    public void build(int stages) {
        try {
            check((int) BUILD.invokeExact(h(), stages), "co_mesh_build");
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * {@code co_mesh_move_vertices}: positions-only refresh (a drag frame). {@code xyz} holds 3 floats per id.
     * Every current array then equals a full rebuild for the new positions; {@link #A_MOVED_FACES} lists the
     * touched face slots.
     *
     * @return {@code CHANGED_*} bits
     */
    public int moveVertices(int[] vertexIds, float[] xyz) {
        int count = vertexIds == null ? 0 : vertexIds.length;
        if (xyz == null || xyz.length < 3 * count) {
            throw new IllegalArgumentException("xyz needs 3 floats per vertex id");
        }
        if (count == 0) return 0;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment changes = arena.allocate(I32);
            check((int) MOVE.invokeExact(h(), arena.allocateFrom(I32, vertexIds),
                    floatsOrNull(arena, xyz), (long) count, changes), "co_mesh_move_vertices");
            return changes.get(I32, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Edge id joining a and b (either order), -1 when none or TOPOLOGY is not current. */
    public int findEdge(int a, int b) {
        try {
            return (int) FIND_EDGE.invokeExact(h(), a, b);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // --- Stateless ----------------------------------------------------------------------------------------------

    /** {@code co_polygon_triangulate} result: loop-local triangles, the fan-fallback flag (0 = regular), Newell. */
    public record Triangulation(int[] indices, int flags, float[] newell) {
    }

    /** {@code co_polygon_triangulate}: PolygonTriangulator.triangulate of one loop (xyz = 3 floats per corner). */
    public static Triangulation triangulate(float[] xyz) {
        requireAvailable();
        int n = xyz.length / 3;
        if (n < 3) throw new IllegalArgumentException("Polygon needs >= 3 vertices: " + n);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(12L * (n - 2), 4);
            MemorySegment newell = arena.allocate(12, 4);
            int flags = check((int) TRIANGULATE.invokeExact(arena.allocateFrom(F32, xyz), n, out, newell),
                    "co_polygon_triangulate");
            return new Triangulation(out.toArray(I32), flags, newell.toArray(F32));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }
}
