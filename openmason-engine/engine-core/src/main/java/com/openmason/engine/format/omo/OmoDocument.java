package com.openmason.engine.format.omo;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.openmason.engine.format.omo.CendaOmo.ADDR;
import static com.openmason.engine.format.omo.CendaOmo.F32;
import static com.openmason.engine.format.omo.CendaOmo.I32;
import static com.openmason.engine.format.omo.CendaOmo.I64;

/**
 * A native {@code .omo} document ({@code co_doc}, {@code cenda/model.h}): read an archive, inspect or fill it, write
 * it. The codec is native ({@code libomformat}, OpenMason #167); this class only moves the {@link OMOFormat} records
 * across the binding, one call per array or record list.
 *
 * <p>Not thread-safe (a document belongs to one thread at a time). Close it when done; a cleaner frees a leaked one.
 */
public final class OmoDocument implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    /** The archive is a newer major OMO version than the native reader knows. */
    public static final class VersionException extends IOException {
        public VersionException(String message) {
            super(message);
        }
    }

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
                    CendaOmo.DOC_DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort at shutdown
                }
            }
        }
    }

    private final State state;
    private final Cleaner.Cleanable cleanable;

    private OmoDocument(MemorySegment handle) {
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
    }

    // --- Lifetime and I/O ------------------------------------------------------------------------------------

    /** An empty document (version "1.8", no mesh, no texture). */
    public static OmoDocument create() {
        CendaOmo.requireAvailable();
        try {
            MemorySegment h = (MemorySegment) CendaOmo.DOC_CREATE.invokeExact();
            if (h.equals(MemorySegment.NULL)) throw new IllegalStateException("co_doc_create: " + CendaOmo.lastError());
            return new OmoDocument(h);
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /**
     * Parses an {@code .omo} archive (1.0 .. 1.x).
     *
     * @throws VersionException for a newer major version
     * @throws IOException      for anything that is not a valid archive (or when the native module is unavailable)
     */
    public static OmoDocument read(byte[] bytes) throws IOException {
        if (!CendaOmo.isAvailable()) {
            throw new IOException("Cenda model module unavailable: " + CendaOmo.unavailableReason());
        }
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0) throw new IOException("empty .omo");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(bytes.length);
            MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, bytes.length);
            MemorySegment result = arena.allocate(I32);
            MemorySegment h = (MemorySegment) CendaOmo.DOC_READ.invokeExact(data, (long) bytes.length, result);
            if (h.equals(MemorySegment.NULL)) {
                String why = CendaOmo.lastError();
                if (result.get(I32, 0) == CendaOmo.ERR_VERSION) throw new VersionException(why);
                throw new IOException("invalid .omo: " + why);
            }
            return new OmoDocument(h);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** The archive bytes (manifest.json, texture.omt, material images, extra entries). */
    public byte[] write() throws IOException {
        return takeBytes(invokeBytes(true), "co_doc_write");
    }

    /** The manifest.json text {@link #write()} stores. */
    public String manifest() throws IOException {
        return new String(takeBytes(invokeBytes(false), "co_doc_write_manifest"),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private MemorySegment invokeBytes(boolean archive) {
        try {
            return archive ? (MemorySegment) CendaOmo.DOC_WRITE.invokeExact(h())
                    : (MemorySegment) CendaOmo.DOC_WRITE_MANIFEST.invokeExact(h());
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    private static byte[] takeBytes(MemorySegment bytes, String what) throws IOException {
        if (bytes.equals(MemorySegment.NULL)) throw new IOException(what + ": " + CendaOmo.lastError());
        try {
            long size = (long) CendaOmo.BYTES_SIZE.invokeExact(bytes);
            MemorySegment data = (MemorySegment) CendaOmo.BYTES_DATA.invokeExact(bytes);
            return size <= 0 ? new byte[0] : data.reinterpret(size).toArray(ValueLayout.JAVA_BYTE);
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        } finally {
            try {
                CendaOmo.BYTES_DESTROY.invokeExact(bytes);
            } catch (Throwable ignored) {
                // nothing left to do
            }
        }
    }

    @Override
    public void close() {
        cleanable.clean();
    }

    private MemorySegment h() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("OmoDocument is closed");
        return h;
    }

    private static void check(int rc, String what) {
        if (rc >= 0) return;
        String message = what + ": " + CendaOmo.lastError();
        if (rc == CendaOmo.ERR_ARG || rc == CendaOmo.ERR_RANGE) throw new IllegalArgumentException(message);
        throw new IllegalStateException(message);
    }

    // --- Strings and flags -------------------------------------------------------------------------------------

    private String string(int field) {
        try {
            return CendaOmo.string((MemorySegment) CendaOmo.GET_STRING.invokeExact(h(), field));
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    private void setString(int field, String value, String what) {
        try (Arena arena = Arena.ofConfined()) {
            check((int) CendaOmo.SET_STRING.invokeExact(h(), field, cstr(arena, value)), what);
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** The document's version ({@code "<major>.<minor>"}). */
    public String version() {
        return string(CendaOmo.FIELD_VERSION);
    }

    /** The version {@link #write()} puts in the manifest (at least "1.9" when 1.9 data is present). */
    public String writtenVersion() {
        return string(CendaOmo.FIELD_WRITTEN_VERSION);
    }

    public String objectName() {
        return string(CendaOmo.FIELD_OBJECT_NAME);
    }

    public String modelType() {
        return string(CendaOmo.FIELD_MODEL_TYPE);
    }

    public String textureFile() {
        return string(CendaOmo.FIELD_TEXTURE_FILE);
    }

    public void setVersion(String version) {
        setString(CendaOmo.FIELD_VERSION, version, "co_doc_set_string(version)");
    }

    public void setObjectName(String name) {
        setString(CendaOmo.FIELD_OBJECT_NAME, name, "co_doc_set_string(objectName)");
    }

    public void setModelType(String type) {
        setString(CendaOmo.FIELD_MODEL_TYPE, type, "co_doc_set_string(modelType)");
    }

    public void setTextureFile(String file) {
        setString(CendaOmo.FIELD_TEXTURE_FILE, file, "co_doc_set_string(textureFile)");
    }

    private int flags() {
        try {
            int flags = (int) CendaOmo.FLAGS.invokeExact(h());
            check(flags, "co_doc_flags");
            return flags;
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    private void setFlag(int flag, boolean on) {
        int flags = flags();
        flags = on ? flags | flag : flags & ~flag;
        try {
            check((int) CendaOmo.SET_FLAGS.invokeExact(h(), flags), "co_doc_set_flags");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    public boolean hasMesh() {
        return (flags() & CendaOmo.HAS_MESH) != 0;
    }

    public boolean hasFaceTextures() {
        return (flags() & CendaOmo.HAS_FACE_TEXTURES) != 0;
    }

    /** Manifest lines end in CRLF (a file the Java tool wrote on Windows). */
    public boolean crlf() {
        return (flags() & CendaOmo.CRLF) != 0;
    }

    public void setCrlf(boolean crlf) {
        setFlag(CendaOmo.CRLF, crlf);
    }

    /** True when shading / vertex groups / morphs / a material base colour are present. */
    public boolean has19Data() {
        return (flags() & CendaOmo.HAS_1_9_DATA) != 0;
    }

    /** Adds an empty {@code faceTextures} section (true) or drops it (false). */
    public void setHasFaceTextures(boolean present) {
        setFlag(CendaOmo.HAS_FACE_TEXTURES, present);
    }

    // --- Geometry and model transform ------------------------------------------------------------------------

    /**
     * @throws IllegalArgumentException when the stored dimensions are not positive (as {@link OMOFormat.GeometryData})
     */
    public OMOFormat.GeometryData geometry() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment g = arena.allocate(CendaOmo.GEOMETRY_BYTES, 8);
            check((int) CendaOmo.GEOMETRY_GET.invokeExact(h(), g), "co_doc_geometry_get");
            return new OMOFormat.GeometryData(g.get(I32, 0), g.get(I32, 4), g.get(I32, 8),
                    new OMOFormat.Position(g.get(ValueLayout.JAVA_DOUBLE, 16), g.get(ValueLayout.JAVA_DOUBLE, 24),
                            g.get(ValueLayout.JAVA_DOUBLE, 32)));
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    public void setGeometry(OMOFormat.GeometryData geometry) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment g = arena.allocate(CendaOmo.GEOMETRY_BYTES, 8);
            g.set(I32, 0, geometry.width());
            g.set(I32, 4, geometry.height());
            g.set(I32, 8, geometry.depth());
            g.set(ValueLayout.JAVA_DOUBLE, 16, geometry.position().x());
            g.set(ValueLayout.JAVA_DOUBLE, 24, geometry.position().y());
            g.set(ValueLayout.JAVA_DOUBLE, 32, geometry.position().z());
            check((int) CendaOmo.GEOMETRY_SET.invokeExact(h(), g), "co_doc_geometry_set");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** The model transform (identity when the file has none). */
    public OMOFormat.ModelTransform modelTransform() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment t9 = arena.allocate(F32, 9);
            check((int) CendaOmo.MODEL_TRANSFORM_GET.invokeExact(h(), t9), "co_doc_model_transform_get");
            float[] v = t9.toArray(F32);
            return new OMOFormat.ModelTransform(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8]);
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** Null = identity (written as null). */
    public void setModelTransform(OMOFormat.ModelTransform t) {
        OMOFormat.ModelTransform m = t == null ? OMOFormat.ModelTransform.identity() : t;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment t9 = arena.allocateFrom(F32, m.posX(), m.posY(), m.posZ(), m.rotX(), m.rotY(), m.rotZ(),
                    m.scaleX(), m.scaleY(), m.scaleZ());
            check((int) CendaOmo.MODEL_TRANSFORM_SET.invokeExact(h(), t9), "co_doc_model_transform_set");
        } catch (Throwable t2) {
            throw CendaOmo.rethrow(t2);
        }
    }

    // --- Mesh --------------------------------------------------------------------------------------------------

    private MemorySegment meshArray(Arena arena, int which, long[] count) throws Throwable {
        MemorySegment out = arena.allocate(ADDR);
        long n = (long) CendaOmo.MESH_ARRAY.invokeExact(h(), which, out);
        count[0] = n;
        return n <= 0 ? MemorySegment.NULL : out.get(ADDR, 0).reinterpret(n * 4);
    }

    private float[] floatArray(Arena arena, int which) throws Throwable {
        long[] n = new long[1];
        MemorySegment s = meshArray(arena, which, n);
        if (n[0] < 0) return null;
        return n[0] == 0 ? new float[0] : s.toArray(F32);
    }

    private int[] intArray(Arena arena, int which) throws Throwable {
        long[] n = new long[1];
        MemorySegment s = meshArray(arena, which, n);
        if (n[0] < 0) return null;
        return n[0] == 0 ? new int[0] : s.toArray(I32);
    }

    /** The mesh soup, or null when the document has none ("mesh": null). Each member may be null. */
    public OMOFormat.MeshData mesh() {
        if (!hasMesh()) return null;
        try (Arena arena = Arena.ofConfined()) {
            return new OMOFormat.MeshData(
                    floatArray(arena, CendaOmo.MESH_VERTICES),
                    floatArray(arena, CendaOmo.MESH_TEX_COORDS),
                    intArray(arena, CendaOmo.MESH_INDICES),
                    intArray(arena, CendaOmo.MESH_TRIANGLE_FACE_IDS),
                    string(CendaOmo.FIELD_UV_MODE));
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** Null drops the mesh ("mesh": null); null members are written as null. */
    public void setMesh(OMOFormat.MeshData mesh) {
        if (mesh == null) {
            setFlag(CendaOmo.HAS_MESH, false);
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            setMeshArray(arena, CendaOmo.MESH_VERTICES, mesh.vertices());
            setMeshArray(arena, CendaOmo.MESH_TEX_COORDS, mesh.texCoords());
            setMeshArray(arena, CendaOmo.MESH_INDICES, mesh.indices());
            setMeshArray(arena, CendaOmo.MESH_TRIANGLE_FACE_IDS, mesh.triangleToFaceId());
            check((int) CendaOmo.SET_STRING.invokeExact(h(), CendaOmo.FIELD_UV_MODE, cstr(arena, mesh.uvMode())),
                    "co_doc_set_string(uvMode)");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    private void setMeshArray(Arena arena, int which, Object array) throws Throwable {
        MemorySegment data;
        long count;
        if (array == null) {
            data = MemorySegment.NULL;
            count = -1;
        } else if (array instanceof float[] f) {
            data = floats(arena, f);
            count = f.length;
        } else {
            int[] i = (int[]) array;
            data = ints(arena, i);
            count = i.length;
        }
        check((int) CendaOmo.MESH_SET_ARRAY.invokeExact(h(), which, data, count), "co_doc_mesh_set_array");
    }

    // --- Face textures -------------------------------------------------------------------------------------------

    public List<OMOFormat.FaceMappingEntry> faceMappings() {
        try (Arena arena = Arena.ofConfined()) {
            int n = (int) CendaOmo.FACE_MAPPINGS_GET.invokeExact(h(), MemorySegment.NULL, 0);
            check(n, "co_doc_face_mappings_get");
            List<OMOFormat.FaceMappingEntry> out = new ArrayList<>(n);
            if (n == 0) return out;
            MemorySegment s = arena.allocate(CendaOmo.FACE_MAPPING_BYTES * n, 8);
            check((int) CendaOmo.FACE_MAPPINGS_GET.invokeExact(h(), s, n), "co_doc_face_mappings_get");
            for (int i = 0; i < n; i++) {
                long o = i * CendaOmo.FACE_MAPPING_BYTES;
                out.add(new OMOFormat.FaceMappingEntry(s.get(I32, o), s.get(I32, o + 4),
                        s.get(F32, o + 8), s.get(F32, o + 12), s.get(F32, o + 16), s.get(F32, o + 20),
                        s.get(I32, o + 24), s.get(I32, o + 28) != 0));
            }
            return out;
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** Replaces the face mappings (creates the faceTextures section). */
    public void setFaceMappings(List<OMOFormat.FaceMappingEntry> mappings) {
        List<OMOFormat.FaceMappingEntry> list = mappings == null ? List.of() : mappings;
        try (Arena arena = Arena.ofConfined()) {
            int n = list.size();
            MemorySegment s = arena.allocate(CendaOmo.FACE_MAPPING_BYTES * Math.max(1, n), 8);
            for (int i = 0; i < n; i++) {
                OMOFormat.FaceMappingEntry m = list.get(i);
                long o = i * CendaOmo.FACE_MAPPING_BYTES;
                s.set(I32, o, m.faceId());
                s.set(I32, o + 4, m.materialId());
                s.set(F32, o + 8, m.u0());
                s.set(F32, o + 12, m.v0());
                s.set(F32, o + 16, m.u1());
                s.set(F32, o + 20, m.v1());
                s.set(I32, o + 24, m.uvRotationDegrees());
                s.set(I32, o + 28, m.autoResize() ? 1 : 0);
            }
            check((int) CendaOmo.FACE_MAPPINGS_SET.invokeExact(h(), s, n), "co_doc_face_mappings_set");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    public List<OMOFormat.MaterialEntry> materials() {
        try (Arena arena = Arena.ofConfined()) {
            int n = (int) CendaOmo.MATERIALS_GET.invokeExact(h(), MemorySegment.NULL, 0);
            check(n, "co_doc_materials_get");
            List<OMOFormat.MaterialEntry> out = new ArrayList<>(n);
            if (n == 0) return out;
            MemorySegment s = arena.allocate(CendaOmo.MATERIAL_BYTES * n, 8);
            check((int) CendaOmo.MATERIALS_GET.invokeExact(h(), s, n), "co_doc_materials_get");
            for (int i = 0; i < n; i++) {
                long o = i * CendaOmo.MATERIAL_BYTES;
                float[] base = s.get(I32, o + 36) != 0
                        ? new float[]{s.get(F32, o + 40), s.get(F32, o + 44), s.get(F32, o + 48), s.get(F32, o + 52)}
                        : null;
                out.add(new OMOFormat.MaterialEntry(s.get(I32, o + 24), str(s, o), str(s, o + 8), str(s, o + 16),
                        s.get(I32, o + 28) != 0, s.get(I32, o + 32), base));
            }
            return out;
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** Replaces the materials (creates the faceTextures section). */
    public void setMaterials(List<OMOFormat.MaterialEntry> materials) {
        List<OMOFormat.MaterialEntry> list = materials == null ? List.of() : materials;
        try (Arena arena = Arena.ofConfined()) {
            int n = list.size();
            MemorySegment s = arena.allocate(CendaOmo.MATERIAL_BYTES * Math.max(1, n), 8);
            for (int i = 0; i < n; i++) {
                OMOFormat.MaterialEntry m = list.get(i);
                long o = i * CendaOmo.MATERIAL_BYTES;
                s.set(ADDR, o, cstr(arena, m.name()));
                s.set(ADDR, o + 8, cstr(arena, m.textureFile()));
                s.set(ADDR, o + 16, cstr(arena, m.renderLayer()));
                s.set(I32, o + 24, m.materialId());
                s.set(I32, o + 28, m.emissive() ? 1 : 0);
                s.set(I32, o + 32, m.tintColor());
                float[] base = m.baseColor();
                s.set(I32, o + 36, base != null ? 1 : 0);
                for (int k = 0; k < 4; k++) s.set(F32, o + 40 + 4L * k, base != null ? base[k] : 0f);
            }
            check((int) CendaOmo.MATERIALS_SET.invokeExact(h(), s, n), "co_doc_materials_set");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    // --- Parts, bones, attachment points -------------------------------------------------------------------------

    public List<OMOFormat.PartEntry> parts() {
        try (Arena arena = Arena.ofConfined()) {
            int n = (int) CendaOmo.PARTS_GET.invokeExact(h(), MemorySegment.NULL, 0);
            check(n, "co_doc_parts_get");
            List<OMOFormat.PartEntry> out = new ArrayList<>(n);
            if (n == 0) return out;
            MemorySegment s = arena.allocate(CendaOmo.PART_BYTES * n, 8);
            check((int) CendaOmo.PARTS_GET.invokeExact(h(), s, n), "co_doc_parts_get");
            for (int i = 0; i < n; i++) {
                long o = i * CendaOmo.PART_BYTES;
                out.add(new OMOFormat.PartEntry(str(s, o), str(s, o + 8),
                        s.get(F32, o + 32), s.get(F32, o + 36), s.get(F32, o + 40),
                        s.get(F32, o + 44), s.get(F32, o + 48), s.get(F32, o + 52),
                        s.get(F32, o + 56), s.get(F32, o + 60), s.get(F32, o + 64),
                        s.get(F32, o + 68), s.get(F32, o + 72), s.get(F32, o + 76),
                        s.get(I32, o + 80), s.get(I32, o + 84), s.get(I32, o + 88), s.get(I32, o + 92),
                        s.get(I32, o + 96), s.get(I32, o + 100),
                        s.get(I32, o + 104) != 0, s.get(I32, o + 108) != 0,
                        str(s, o + 16), str(s, o + 24)));
            }
            return out;
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** Replaces the parts; null or empty = none (written as null). */
    public void setParts(List<OMOFormat.PartEntry> parts) {
        List<OMOFormat.PartEntry> list = parts == null ? List.of() : parts;
        try (Arena arena = Arena.ofConfined()) {
            int n = list.size();
            MemorySegment s = arena.allocate(CendaOmo.PART_BYTES * Math.max(1, n), 8);
            for (int i = 0; i < n; i++) {
                OMOFormat.PartEntry p = list.get(i);
                long o = i * CendaOmo.PART_BYTES;
                s.set(ADDR, o, cstr(arena, p.id()));
                s.set(ADDR, o + 8, cstr(arena, p.name()));
                s.set(ADDR, o + 16, cstr(arena, p.parentId()));
                s.set(ADDR, o + 24, cstr(arena, p.boneId()));
                put3(s, o + 32, p.originX(), p.originY(), p.originZ());
                put3(s, o + 44, p.posX(), p.posY(), p.posZ());
                put3(s, o + 56, p.rotX(), p.rotY(), p.rotZ());
                put3(s, o + 68, p.scaleX(), p.scaleY(), p.scaleZ());
                s.set(I32, o + 80, p.vertexStart());
                s.set(I32, o + 84, p.vertexCount());
                s.set(I32, o + 88, p.indexStart());
                s.set(I32, o + 92, p.indexCount());
                s.set(I32, o + 96, p.faceStart());
                s.set(I32, o + 100, p.faceCount());
                s.set(I32, o + 104, p.visible() ? 1 : 0);
                s.set(I32, o + 108, p.locked() ? 1 : 0);
            }
            check((int) CendaOmo.PARTS_SET.invokeExact(h(), s, n), "co_doc_parts_set");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    public List<OMOFormat.BoneEntry> bones() {
        try (Arena arena = Arena.ofConfined()) {
            int n = (int) CendaOmo.BONES_GET.invokeExact(h(), MemorySegment.NULL, 0);
            check(n, "co_doc_bones_get");
            List<OMOFormat.BoneEntry> out = new ArrayList<>(n);
            if (n == 0) return out;
            MemorySegment s = arena.allocate(CendaOmo.BONE_BYTES * n, 8);
            check((int) CendaOmo.BONES_GET.invokeExact(h(), s, n), "co_doc_bones_get");
            for (int i = 0; i < n; i++) {
                long o = i * CendaOmo.BONE_BYTES;
                out.add(new OMOFormat.BoneEntry(str(s, o), str(s, o + 8), str(s, o + 16),
                        s.get(F32, o + 24), s.get(F32, o + 28), s.get(F32, o + 32),
                        s.get(F32, o + 36), s.get(F32, o + 40), s.get(F32, o + 44),
                        s.get(F32, o + 48), s.get(F32, o + 52), s.get(F32, o + 56),
                        s.get(F32, o + 60), s.get(F32, o + 64), s.get(F32, o + 68)));
            }
            return out;
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** Replaces the bones; null or empty = none (written as null). */
    public void setBones(List<OMOFormat.BoneEntry> bones) {
        List<OMOFormat.BoneEntry> list = bones == null ? List.of() : bones;
        try (Arena arena = Arena.ofConfined()) {
            int n = list.size();
            MemorySegment s = arena.allocate(CendaOmo.BONE_BYTES * Math.max(1, n), 8);
            for (int i = 0; i < n; i++) {
                OMOFormat.BoneEntry b = list.get(i);
                long o = i * CendaOmo.BONE_BYTES;
                s.set(ADDR, o, cstr(arena, b.id()));
                s.set(ADDR, o + 8, cstr(arena, b.name()));
                s.set(ADDR, o + 16, cstr(arena, b.parentBoneId()));
                put3(s, o + 24, b.originX(), b.originY(), b.originZ());
                put3(s, o + 36, b.posX(), b.posY(), b.posZ());
                put3(s, o + 48, b.rotX(), b.rotY(), b.rotZ());
                put3(s, o + 60, b.endpointX(), b.endpointY(), b.endpointZ());
            }
            check((int) CendaOmo.BONES_SET.invokeExact(h(), s, n), "co_doc_bones_set");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    public List<OMOFormat.AttachmentPointEntry> attachmentPoints() {
        try (Arena arena = Arena.ofConfined()) {
            int n = (int) CendaOmo.ATTACHMENTS_GET.invokeExact(h(), MemorySegment.NULL, 0);
            check(n, "co_doc_attachments_get");
            List<OMOFormat.AttachmentPointEntry> out = new ArrayList<>(n);
            if (n == 0) return out;
            MemorySegment s = arena.allocate(CendaOmo.ATTACHMENT_BYTES * n, 8);
            check((int) CendaOmo.ATTACHMENTS_GET.invokeExact(h(), s, n), "co_doc_attachments_get");
            for (int i = 0; i < n; i++) {
                long o = i * CendaOmo.ATTACHMENT_BYTES;
                out.add(new OMOFormat.AttachmentPointEntry(str(s, o), str(s, o + 8), str(s, o + 16),
                        str(s, o + 24),
                        s.get(F32, o + 32), s.get(F32, o + 36), s.get(F32, o + 40),
                        s.get(F32, o + 44), s.get(F32, o + 48), s.get(F32, o + 52),
                        s.get(F32, o + 56), s.get(F32, o + 60), s.get(F32, o + 64)));
            }
            return out;
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** Replaces the attachment points; null or empty = none (written as null). */
    public void setAttachmentPoints(List<OMOFormat.AttachmentPointEntry> points) {
        List<OMOFormat.AttachmentPointEntry> list = points == null ? List.of() : points;
        try (Arena arena = Arena.ofConfined()) {
            int n = list.size();
            MemorySegment s = arena.allocate(CendaOmo.ATTACHMENT_BYTES * Math.max(1, n), 8);
            for (int i = 0; i < n; i++) {
                OMOFormat.AttachmentPointEntry a = list.get(i);
                long o = i * CendaOmo.ATTACHMENT_BYTES;
                s.set(ADDR, o, cstr(arena, a.id()));
                s.set(ADDR, o + 8, cstr(arena, a.name()));
                s.set(ADDR, o + 16, cstr(arena, a.parentPartId()));
                s.set(ADDR, o + 24, cstr(arena, a.parentPartName()));
                put3(s, o + 32, a.posX(), a.posY(), a.posZ());
                put3(s, o + 44, a.rotX(), a.rotY(), a.rotZ());
                put3(s, o + 56, a.scaleX(), a.scaleY(), a.scaleZ());
                s.set(I32, o + 68, 0);
            }
            check((int) CendaOmo.ATTACHMENTS_SET.invokeExact(h(), s, n), "co_doc_attachments_set");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    // --- Skin (1.8) ---------------------------------------------------------------------------------------------

    /** The skin weights, or null when no vertex is weighted. */
    public OMOFormat.SkinData skin() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment c = arena.allocate(I32, 4);
            check((int) CendaOmo.SKIN_COUNTS.invokeExact(h(), c, c.asSlice(4), c.asSlice(8), c.asSlice(12)),
                    "co_doc_skin_counts");
            int joints = c.get(I32, 0);
            int parts = c.get(I32, 4);
            int rows = c.get(I32, 8);
            int influences = c.get(I32, 12);
            if (parts == 0) return null;
            MemorySegment jointIds = arena.allocate(ADDR, Math.max(1, joints));
            MemorySegment partIds = arena.allocate(ADDR, parts);
            MemorySegment partRows = arena.allocate(I32, parts + 1L);
            MemorySegment rowVertices = arena.allocate(I32, Math.max(1, rows));
            MemorySegment rowInfluences = arena.allocate(I32, rows + 1L);
            MemorySegment js = arena.allocate(I32, Math.max(1, influences));
            MemorySegment ws = arena.allocate(F32, Math.max(1, influences));
            check((int) CendaOmo.SKIN_GET.invokeExact(h(), jointIds, partIds, partRows, rowVertices, rowInfluences,
                    js, ws), "co_doc_skin_get");
            List<String> jointList = strings(jointIds, joints);
            List<OMOFormat.PartSkin> partList = new ArrayList<>(parts);
            for (int p = 0; p < parts; p++) {
                int r0 = partRows.getAtIndex(I32, p);
                int r1 = partRows.getAtIndex(I32, p + 1);
                List<OMOFormat.SkinVertex> vertices = new ArrayList<>(r1 - r0);
                for (int r = r0; r < r1; r++) {
                    int k0 = rowInfluences.getAtIndex(I32, r);
                    int k1 = rowInfluences.getAtIndex(I32, r + 1);
                    int[] jj = new int[k1 - k0];
                    float[] ww = new float[k1 - k0];
                    for (int k = k0; k < k1; k++) {
                        jj[k - k0] = js.getAtIndex(I32, k);
                        ww[k - k0] = ws.getAtIndex(F32, k);
                    }
                    vertices.add(new OMOFormat.SkinVertex(rowVertices.getAtIndex(I32, r), jj, ww));
                }
                partList.add(new OMOFormat.PartSkin(CendaOmo.string(partIds.getAtIndex(ADDR, p)), vertices));
            }
            return new OMOFormat.SkinData(jointList, partList);
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /**
     * Replaces the skin; null or empty clears it. Parts without rows are dropped; a row without influences is dropped
     * (no reader accepts one).
     */
    public void setSkin(OMOFormat.SkinData skin) {
        try (Arena arena = Arena.ofConfined()) {
            if (skin == null || skin.isEmpty()) {
                check((int) CendaOmo.SKIN_SET.invokeExact(h(), 0, MemorySegment.NULL, 0, MemorySegment.NULL,
                        MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
                        MemorySegment.NULL), "co_doc_skin_set");
                return;
            }
            List<String> joints = skin.jointIds();
            List<OMOFormat.PartSkin> parts = skin.parts();
            int rows = 0;
            int influences = 0;
            for (OMOFormat.PartSkin p : parts) {
                for (OMOFormat.SkinVertex v : p.vertices()) {
                    if (v.joints().length == 0) continue;
                    rows++;
                    influences += v.joints().length;
                }
            }
            MemorySegment partRows = arena.allocate(I32, parts.size() + 1L);
            MemorySegment rowVertices = arena.allocate(I32, Math.max(1, rows));
            MemorySegment rowInfluences = arena.allocate(I32, rows + 1L);
            MemorySegment js = arena.allocate(I32, Math.max(1, influences));
            MemorySegment ws = arena.allocate(F32, Math.max(1, influences));
            List<String> partIds = new ArrayList<>(parts.size());
            int row = 0;
            int k = 0;
            for (int p = 0; p < parts.size(); p++) {
                OMOFormat.PartSkin part = parts.get(p);
                partIds.add(part.partId());
                for (OMOFormat.SkinVertex v : part.vertices()) {
                    int[] jj = v.joints();
                    if (jj.length == 0) continue;
                    float[] ww = v.weights();
                    rowVertices.setAtIndex(I32, row, v.localVertex());
                    for (int i = 0; i < jj.length; i++, k++) {
                        js.setAtIndex(I32, k, jj[i]);
                        ws.setAtIndex(F32, k, ww[i]);
                    }
                    row++;
                    rowInfluences.setAtIndex(I32, row, k);
                }
                partRows.setAtIndex(I32, p + 1, row);
            }
            check((int) CendaOmo.SKIN_SET.invokeExact(h(), joints.size(), cstrs(arena, joints), parts.size(),
                    cstrs(arena, partIds), partRows, rowVertices, rowInfluences, js, ws), "co_doc_skin_set");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    // --- Shading (1.9) --------------------------------------------------------------------------------------------

    /** The shading section, or null when the document has none. */
    public OMOFormat.ShadingData shading() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p1 = arena.allocate(ADDR);
            MemorySegment p2 = arena.allocate(ADDR);
            long words = (long) CendaOmo.SHADING_SMOOTH.invokeExact(h(), p1);
            check((int) Math.min(0, words), "co_doc_shading_smooth");
            int[] smooth = words <= 0 ? new int[0] : p1.get(ADDR, 0).reinterpret(words * 4).toArray(I32);
            long edges = (long) CendaOmo.SHADING_SHARP_EDGES.invokeExact(h(), p1);
            check((int) Math.min(0, edges), "co_doc_shading_sharp_edges");
            int[] sharp = edges <= 0 ? new int[0] : p1.get(ADDR, 0).reinterpret(edges * 8).toArray(I32);
            MemorySegment deg = arena.allocate(F32);
            int hasAngle = (int) CendaOmo.SHADING_AUTO_SMOOTH.invokeExact(h(), deg);
            check(hasAngle, "co_doc_shading_auto_smooth");
            Float angle = hasAngle == 1 ? deg.get(F32, 0) : null;
            long normals = (long) CendaOmo.SHADING_CUSTOM_NORMALS.invokeExact(h(), p1, p2);
            check((int) Math.min(0, normals), "co_doc_shading_custom_normals");
            int[] nv = normals <= 0 ? new int[0] : p1.get(ADDR, 0).reinterpret(normals * 4).toArray(I32);
            short[] no = normals <= 0 ? new short[0]
                    : p2.get(ADDR, 0).reinterpret(normals * 4).toArray(ValueLayout.JAVA_SHORT);
            OMOFormat.ShadingData shading = new OMOFormat.ShadingData(smooth, sharp, angle, nv, no);
            return shading.isEmpty() ? null : shading;
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** Replaces the shading section; null clears it. */
    public void setShading(OMOFormat.ShadingData shading) {
        OMOFormat.ShadingData s = shading == null
                ? new OMOFormat.ShadingData(null, null, null, null, null) : shading;
        try (Arena arena = Arena.ofConfined()) {
            int[] smooth = s.smoothWords();
            check((int) CendaOmo.SHADING_SET_SMOOTH.invokeExact(h(), ints(arena, smooth), (long) smooth.length),
                    "co_doc_shading_set_smooth");
            int[] sharp = s.sharpEdges();
            check((int) CendaOmo.SHADING_SET_SHARP_EDGES.invokeExact(h(), ints(arena, sharp), (long) sharp.length / 2),
                    "co_doc_shading_set_sharp_edges");
            MemorySegment deg = s.autoSmoothAngle() == null ? MemorySegment.NULL
                    : arena.allocateFrom(F32, s.autoSmoothAngle());
            check((int) CendaOmo.SHADING_SET_AUTO_SMOOTH.invokeExact(h(), deg), "co_doc_shading_set_auto_smooth");
            int[] nv = s.customNormalVertices();
            short[] no = s.customNormalOct();
            MemorySegment oct = arena.allocate(ValueLayout.JAVA_SHORT, Math.max(2, no.length));
            MemorySegment.copy(no, 0, oct, ValueLayout.JAVA_SHORT, 0, no.length);
            check((int) CendaOmo.SHADING_SET_CUSTOM_NORMALS.invokeExact(h(), ints(arena, nv), oct, (long) nv.length),
                    "co_doc_shading_set_custom_normals");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    // --- Vertex groups (1.9) -------------------------------------------------------------------------------------

    public List<OMOFormat.VertexGroupData> vertexGroups() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment c = arena.allocate(I32, 3);
            check((int) CendaOmo.VERTEX_GROUP_COUNTS.invokeExact(h(), c, c.asSlice(4), c.asSlice(8)),
                    "co_doc_vertex_group_counts");
            int groups = c.get(I32, 0);
            int parts = c.get(I32, 4);
            int rows = c.get(I32, 8);
            List<OMOFormat.VertexGroupData> out = new ArrayList<>(groups);
            if (groups == 0) return out;
            MemorySegment names = arena.allocate(ADDR, groups);
            MemorySegment groupParts = arena.allocate(I32, groups + 1L);
            MemorySegment partIds = arena.allocate(ADDR, Math.max(1, parts));
            MemorySegment partRows = arena.allocate(I32, parts + 1L);
            MemorySegment vertices = arena.allocate(I32, Math.max(1, rows));
            MemorySegment weights = arena.allocate(F32, Math.max(1, rows));
            check((int) CendaOmo.VERTEX_GROUPS_GET.invokeExact(h(), names, groupParts, partIds, partRows, vertices,
                    weights), "co_doc_vertex_groups_get");
            for (int g = 0; g < groups; g++) {
                List<OMOFormat.GroupPart> gp = new ArrayList<>();
                for (int p = groupParts.getAtIndex(I32, g); p < groupParts.getAtIndex(I32, g + 1); p++) {
                    int r0 = partRows.getAtIndex(I32, p);
                    int r1 = partRows.getAtIndex(I32, p + 1);
                    gp.add(new OMOFormat.GroupPart(CendaOmo.string(partIds.getAtIndex(ADDR, p)),
                            slice(vertices, I32, r0, r1).toArray(I32), slice(weights, F32, r0, r1).toArray(F32)));
                }
                out.add(new OMOFormat.VertexGroupData(CendaOmo.string(names.getAtIndex(ADDR, g)), gp));
            }
            return out;
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** Replaces the vertex groups; null or empty clears them. */
    public void setVertexGroups(List<OMOFormat.VertexGroupData> groups) {
        List<OMOFormat.VertexGroupData> list = groups == null ? List.of() : groups;
        try (Arena arena = Arena.ofConfined()) {
            int parts = 0;
            int rows = 0;
            for (OMOFormat.VertexGroupData g : list) {
                parts += g.parts().size();
                for (OMOFormat.GroupPart p : g.parts()) rows += p.vertices().length;
            }
            List<String> names = new ArrayList<>(list.size());
            List<String> partIds = new ArrayList<>(parts);
            MemorySegment groupParts = arena.allocate(I32, list.size() + 1L);
            MemorySegment partRows = arena.allocate(I32, parts + 1L);
            MemorySegment vertices = arena.allocate(I32, Math.max(1, rows));
            MemorySegment weights = arena.allocate(F32, Math.max(1, rows));
            int part = 0;
            int row = 0;
            for (int g = 0; g < list.size(); g++) {
                OMOFormat.VertexGroupData group = list.get(g);
                names.add(group.name());
                for (OMOFormat.GroupPart p : group.parts()) {
                    partIds.add(p.partId());
                    int[] v = p.vertices();
                    MemorySegment.copy(v, 0, vertices, I32, 4L * row, v.length);
                    MemorySegment.copy(p.weights(), 0, weights, F32, 4L * row, v.length);
                    row += v.length;
                    part++;
                    partRows.setAtIndex(I32, part, row);
                }
                groupParts.setAtIndex(I32, g + 1, part);
            }
            check((int) CendaOmo.VERTEX_GROUPS_SET.invokeExact(h(), list.size(), cstrs(arena, names), groupParts,
                    cstrs(arena, partIds), partRows, vertices, weights), "co_doc_vertex_groups_set");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    // --- Morphs (1.9) ----------------------------------------------------------------------------------------------

    public List<OMOFormat.MorphData> morphs() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment c = arena.allocate(I32, 3);
            check((int) CendaOmo.MORPH_COUNTS.invokeExact(h(), c, c.asSlice(4), c.asSlice(8)), "co_doc_morph_counts");
            int morphs = c.get(I32, 0);
            int parts = c.get(I32, 4);
            int rows = c.get(I32, 8);
            List<OMOFormat.MorphData> out = new ArrayList<>(morphs);
            if (morphs == 0) return out;
            MemorySegment names = arena.allocate(ADDR, morphs);
            MemorySegment params = arena.allocate(F32, 3L * morphs);
            MemorySegment morphParts = arena.allocate(I32, morphs + 1L);
            MemorySegment partIds = arena.allocate(ADDR, Math.max(1, parts));
            MemorySegment partFlags = arena.allocate(I32, Math.max(1, parts));
            MemorySegment partRows = arena.allocate(I32, parts + 1L);
            MemorySegment vertices = arena.allocate(I32, Math.max(1, rows));
            MemorySegment deltas = arena.allocate(F32, Math.max(3, 3L * rows));
            MemorySegment normals = arena.allocate(F32, Math.max(3, 3L * rows));
            check((int) CendaOmo.MORPHS_GET.invokeExact(h(), names, params, morphParts, partIds, partFlags, partRows,
                    vertices, deltas, normals), "co_doc_morphs_get");
            for (int m = 0; m < morphs; m++) {
                List<OMOFormat.MorphPart> mp = new ArrayList<>();
                for (int p = morphParts.getAtIndex(I32, m); p < morphParts.getAtIndex(I32, m + 1); p++) {
                    int r0 = partRows.getAtIndex(I32, p);
                    int r1 = partRows.getAtIndex(I32, p + 1);
                    boolean withNormals = (partFlags.getAtIndex(I32, p) & CendaOmo.MORPH_PART_NORMALS) != 0;
                    mp.add(new OMOFormat.MorphPart(CendaOmo.string(partIds.getAtIndex(ADDR, p)),
                            slice(vertices, I32, r0, r1).toArray(I32),
                            slice(deltas, F32, 3L * r0, 3L * r1).toArray(F32),
                            withNormals ? slice(normals, F32, 3L * r0, 3L * r1).toArray(F32) : null));
                }
                out.add(new OMOFormat.MorphData(CendaOmo.string(names.getAtIndex(ADDR, m)),
                        params.getAtIndex(F32, 3L * m), params.getAtIndex(F32, 3L * m + 1),
                        params.getAtIndex(F32, 3L * m + 2), mp));
            }
            return out;
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** Replaces the morphs; null or empty clears them. */
    public void setMorphs(List<OMOFormat.MorphData> morphs) {
        List<OMOFormat.MorphData> list = morphs == null ? List.of() : morphs;
        try (Arena arena = Arena.ofConfined()) {
            int parts = 0;
            int rows = 0;
            for (OMOFormat.MorphData m : list) {
                parts += m.parts().size();
                for (OMOFormat.MorphPart p : m.parts()) rows += p.vertices().length;
            }
            List<String> names = new ArrayList<>(list.size());
            List<String> partIds = new ArrayList<>(parts);
            MemorySegment params = arena.allocate(F32, Math.max(3, 3L * list.size()));
            MemorySegment morphParts = arena.allocate(I32, list.size() + 1L);
            MemorySegment partFlags = arena.allocate(I32, Math.max(1, parts));
            MemorySegment partRows = arena.allocate(I32, parts + 1L);
            MemorySegment vertices = arena.allocate(I32, Math.max(1, rows));
            MemorySegment deltas = arena.allocate(F32, Math.max(3, 3L * rows));
            MemorySegment normals = arena.allocate(F32, Math.max(3, 3L * rows));
            int part = 0;
            int row = 0;
            for (int m = 0; m < list.size(); m++) {
                OMOFormat.MorphData morph = list.get(m);
                names.add(morph.name());
                params.setAtIndex(F32, 3L * m, morph.defaultWeight());
                params.setAtIndex(F32, 3L * m + 1, morph.min());
                params.setAtIndex(F32, 3L * m + 2, morph.max());
                for (OMOFormat.MorphPart p : morph.parts()) {
                    partIds.add(p.partId());
                    int[] v = p.vertices();
                    float[] n = p.normals();
                    partFlags.setAtIndex(I32, part, n != null ? CendaOmo.MORPH_PART_NORMALS : 0);
                    MemorySegment.copy(v, 0, vertices, I32, 4L * row, v.length);
                    MemorySegment.copy(p.deltas(), 0, deltas, F32, 12L * row, 3 * v.length);
                    if (n != null) MemorySegment.copy(n, 0, normals, F32, 12L * row, 3 * v.length);
                    row += v.length;
                    part++;
                    partRows.setAtIndex(I32, part, row);
                }
                morphParts.setAtIndex(I32, m + 1, part);
            }
            check((int) CendaOmo.MORPHS_SET.invokeExact(h(), list.size(), cstrs(arena, names), params, morphParts,
                    cstrs(arena, partIds), partFlags, partRows, vertices, deltas, normals), "co_doc_morphs_set");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    // --- Archive entries ---------------------------------------------------------------------------------------

    /** The embedded texture.omt bytes, or null when the archive has none. */
    public byte[] texture() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = arena.allocate(ADDR);
            long n = (long) CendaOmo.TEXTURE.invokeExact(h(), p);
            check((int) Math.min(0, n), "co_doc_texture");
            return n <= 0 ? null : p.get(ADDR, 0).reinterpret(n).toArray(ValueLayout.JAVA_BYTE);
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** Null or empty removes it. */
    public void setTexture(byte[] omt) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = MemorySegment.NULL;
            long size = 0;
            if (omt != null && omt.length > 0) {
                data = arena.allocate(omt.length);
                MemorySegment.copy(omt, 0, data, ValueLayout.JAVA_BYTE, 0, omt.length);
                size = omt.length;
            }
            check((int) CendaOmo.SET_TEXTURE.invokeExact(h(), data, size), "co_doc_set_texture");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /** The material images ({@code material_*.png}), by entry name, in archive order. */
    public Map<String, byte[]> materialImages() {
        return entries(CendaOmo.ENTRIES_IMAGES);
    }

    /** Any other archive entries (kept verbatim), by name, in archive order. */
    public Map<String, byte[]> extraEntries() {
        return entries(CendaOmo.ENTRIES_EXTRA);
    }

    private Map<String, byte[]> entries(int kind) {
        try (Arena arena = Arena.ofConfined()) {
            int n = (int) CendaOmo.ENTRY_COUNT.invokeExact(h(), kind);
            check(n, "co_doc_entry_count");
            Map<String, byte[]> out = new LinkedHashMap<>();
            MemorySegment name = arena.allocate(ADDR);
            MemorySegment data = arena.allocate(ADDR);
            MemorySegment size = arena.allocate(I64);
            for (int i = 0; i < n; i++) {
                check((int) CendaOmo.ENTRY_GET.invokeExact(h(), kind, i, name, data, size), "co_doc_entry_get");
                long len = size.get(I64, 0);
                out.put(CendaOmo.string(name.get(ADDR, 0)),
                        len <= 0 ? new byte[0] : data.get(ADDR, 0).reinterpret(len).toArray(ValueLayout.JAVA_BYTE));
            }
            return out;
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /**
     * Adds (appended, archive order = insertion order) or replaces a material image; null removes it. The name must be
     * {@code material_*.png}.
     */
    public void setMaterialImage(String name, byte[] png) {
        setEntry(CendaOmo.ENTRIES_IMAGES, name, png);
    }

    /** Adds or replaces another archive entry; null removes it. */
    public void setExtraEntry(String name, byte[] bytes) {
        setEntry(CendaOmo.ENTRIES_EXTRA, name, bytes);
    }

    private void setEntry(int kind, String name, byte[] bytes) {
        Objects.requireNonNull(name, "name");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = MemorySegment.NULL;
            long size = 0;
            if (bytes != null) {
                data = arena.allocate(Math.max(1, bytes.length));
                MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, bytes.length);
                size = bytes.length;
            }
            check((int) CendaOmo.ENTRY_SET.invokeExact(h(), kind, arena.allocateFrom(name), data, size),
                    "co_doc_entry_set");
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    // --- Marshalling helpers ----------------------------------------------------------------------------------

    private static MemorySegment cstr(Arena arena, String s) {
        return s == null ? MemorySegment.NULL : arena.allocateFrom(s);
    }

    private static MemorySegment cstrs(Arena arena, List<String> strings) {
        MemorySegment s = arena.allocate(ADDR, Math.max(1, strings.size()));
        for (int i = 0; i < strings.size(); i++) s.setAtIndex(ADDR, i, cstr(arena, strings.get(i)));
        return s;
    }

    private static List<String> strings(MemorySegment array, int count) {
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(CendaOmo.string(array.getAtIndex(ADDR, i)));
        return out;
    }

    /** A string field of a native struct. */
    private static String str(MemorySegment struct, long offset) {
        return CendaOmo.string(struct.get(ADDR, offset));
    }

    private static MemorySegment ints(Arena arena, int[] values) {
        MemorySegment s = arena.allocate(I32, Math.max(1, values.length));
        MemorySegment.copy(values, 0, s, I32, 0, values.length);
        return s;
    }

    private static MemorySegment floats(Arena arena, float[] values) {
        MemorySegment s = arena.allocate(F32, Math.max(1, values.length));
        MemorySegment.copy(values, 0, s, F32, 0, values.length);
        return s;
    }

    private static MemorySegment slice(MemorySegment s, ValueLayout layout, long from, long to) {
        return s.asSlice(from * layout.byteSize(), (to - from) * layout.byteSize());
    }

    private static void put3(MemorySegment s, long offset, float x, float y, float z) {
        s.set(F32, offset, x);
        s.set(F32, offset + 4, y);
        s.set(F32, offset + 8, z);
    }
}
