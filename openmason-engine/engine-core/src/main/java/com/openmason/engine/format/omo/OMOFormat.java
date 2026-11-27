package com.openmason.engine.format.omo;

import java.util.List;
import java.util.Objects;

/**
 * Open Mason Object (.OMO) file format specification.
 *
 * <p>Version History:
 * <ul>
 *   <li>1.0 - Initial format with basic geometry dimensions</li>
 *   <li>1.1 - Added custom mesh data support for subdivision (vertices, indices, UVs, face mapping)</li>
 *   <li>1.2 - Added per-face texture persistence (face mappings, material entries, material PNGs)</li>
 *   <li>1.3 - Added model part entries for multi-part models (part transforms, mesh ranges)</li>
 *   <li>1.4 - Added model-level transform (position, rotation, scale)</li>
 *   <li>1.5 - Added optional part hierarchy (parentId on PartEntry)</li>
 *   <li>1.6 - Added optional bone skeleton (BoneEntry list, boneId on PartEntry)</li>
 *   <li>1.7 - Added optional attachment points (AttachmentPointEntry list)</li>
 *   <li>1.8 - Rig revamp: parts and bones form one joint hierarchy (a part's {@code parentId} may name a
 *       bone, a bone's {@code parentBoneId} may name a part), clip tracks may key bones, and optional
 *       per-vertex skin weights ({@link SkinData}). Read by the native rig ({@code cenda/rig.h}).</li>
 *   <li>1.9 - Optional sections ({@link ShadingData}, {@link VertexGroupData}, {@link MorphData}) and a material
 *       base colour ({@link MaterialEntry#baseColor()}); a file carrying any of them is written as 1.9.</li>
 * </ul>
 *
 * <p>The codec itself is native ({@code libomformat}, C ABI {@code cenda/model.h}, OpenMason #167): these records
 * are the Java-side data carriers, {@link OmoDocument} moves them across the binding.
 */
public final class OMOFormat {

    /** The version a new document is written as (1.9 data raises it to "1.9" natively). */
    public static final String FORMAT_VERSION = "1.8";

    /** Minimum supported format version for reading */
    public static final String MIN_SUPPORTED_VERSION = "1.0";

    /** File extension for OMO files */
    public static final String FILE_EXTENSION = ".omo";

    /** Manifest filename in ZIP archive */
    public static final String MANIFEST_FILENAME = "manifest.json";

    /** Default texture filename in ZIP archive */
    public static final String DEFAULT_TEXTURE_FILENAME = "texture.omt";

    /** Private constructor - utility class */
    private OMOFormat() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Ensures a file path has the .omo extension.
     */
    public static String ensureExtension(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return filePath;
        }

        String trimmed = filePath.trim();
        if (!trimmed.toLowerCase().endsWith(FILE_EXTENSION)) {
            return trimmed + FILE_EXTENSION;
        }
        return trimmed;
    }

    /**
     * Checks if a file path has the .omo extension.
     *
     * @param filePath the file path to check
     * @return true if the path ends with .omo
     */
    public static boolean hasOMOExtension(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return false;
        }
        return filePath.trim().toLowerCase().endsWith(FILE_EXTENSION);
    }

    /**
     * A 1x1 PNG of an OMO 1.9 material base colour (RGBA 0..1): the stand-in image for a material that has a base
     * colour and no PNG, so loaders that need a texture keep the material instead of dropping it.
     *
     * @param rgba 4 floats
     * @return PNG bytes
     */
    public static byte[] solidColorPng(float[] rgba) {
        if (rgba == null || rgba.length != 4) {
            throw new IllegalArgumentException("rgba must hold 4 floats");
        }
        int argb = 0;
        for (int k : new int[]{3, 0, 1, 2}) {
            argb = (argb << 8) | Math.round(Math.max(0f, Math.min(1f, rgba[k])) * 255f);
        }
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, argb);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(image, "png", out);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
         * Complete .OMO document structure.
         *
         * <p>Represents the entire contents of an .OMO file including
         * manifest data and texture reference.
         *
         * <p>Note: Texture format (cube net vs flat) is auto-detected
         * from the embedded .OMT file dimensions at load time.
         */
        public record Document(String version, String objectName, String modelType, GeometryData geometry,
                               String textureFile) {
            public Document(String version, String objectName, String modelType,
                            GeometryData geometry, String textureFile) {
                this.version = Objects.requireNonNull(version, "version cannot be null");
                this.objectName = Objects.requireNonNull(objectName, "objectName cannot be null");
                this.modelType = Objects.requireNonNull(modelType, "modelType cannot be null");
                this.geometry = Objects.requireNonNull(geometry, "geometry cannot be null");
                this.textureFile = Objects.requireNonNull(textureFile, "textureFile cannot be null");
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof Document document)) return false;
                return Objects.equals(version, document.version) &&
                        Objects.equals(objectName, document.objectName) &&
                        Objects.equals(modelType, document.modelType) &&
                        Objects.equals(geometry, document.geometry) &&
                        Objects.equals(textureFile, document.textureFile);
            }

    }

    /**
         * Geometry data structure for manifest.
         *
         * <p>Stores dimensional and positional information for the model.
         */
        public record GeometryData(int width, int height, int depth, Position position) {
            public GeometryData(int width, int height, int depth, Position position) {
                if (width <= 0 || height <= 0 || depth <= 0) {
                    throw new IllegalArgumentException(
                            String.format("Dimensions must be positive: %dx%dx%d", width, height, depth)
                    );
                }
                this.width = width;
                this.height = height;
                this.depth = depth;
                this.position = Objects.requireNonNull(position, "position cannot be null");
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof GeometryData that)) return false;
                return width == that.width &&
                        height == that.height &&
                        depth == that.depth &&
                        Objects.equals(position, that.position);
            }

    }

    /**
         * 3D position data structure.
         */
        public record Position(double x, double y, double z) {

        @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof Position position)) return false;
                return Double.compare(position.x, x) == 0 &&
                        Double.compare(position.y, y) == 0 &&
                        Double.compare(position.z, z) == 0;
            }

    }

    /**
     * Per-face UV mapping entry for serialization (v1.2+).
     *
     * @param faceId           face identifier
     * @param materialId       material this face uses
     * @param u0               UV region left
     * @param v0               UV region top
     * @param u1               UV region right
     * @param v1               UV region bottom
     * @param uvRotationDegrees rotation in degrees (0, 90, 180, 270)
     * @param autoResize       whether the editor may auto-rescale this face's texture
     *                         when geometry changes. Defaults to true on read for
     *                         files written before this field existed.
     */
    public record FaceMappingEntry(int faceId, int materialId,
                                   float u0, float v0, float u1, float v1,
                                   int uvRotationDegrees,
                                   boolean autoResize) {

        /** Backward-compatible constructor: defaults {@code autoResize} to true. */
        public FaceMappingEntry(int faceId, int materialId,
                                float u0, float v0, float u1, float v1,
                                int uvRotationDegrees) {
            this(faceId, materialId, u0, v0, u1, v1, uvRotationDegrees, true);
        }
    }

    /**
     * Material definition entry for serialization (v1.2+).
     *
     * @param materialId  unique material identifier
     * @param name        display name
     * @param textureFile filename of the material's PNG inside the ZIP (e.g. "material_1.png")
     * @param renderLayer render layer name (OPAQUE, CUTOUT, TRANSLUCENT)
     * @param emissive    whether the material is emissive
     * @param tintColor   RGBA tint color
     */
    public record MaterialEntry(int materialId, String name, String textureFile,
                                String renderLayer, boolean emissive, int tintColor, float[] baseColor) {

        /**
         * @param baseColor OMO 1.9: RGBA base colour (4 floats), or null. A material with a base colour may have
         *                  no image ({@code textureFile} null).
         */
        public MaterialEntry {
            if (baseColor != null) {
                if (baseColor.length != 4) {
                    throw new IllegalArgumentException("baseColor must hold 4 floats (RGBA)");
                }
                baseColor = baseColor.clone();
            }
        }

        /** A material without a base colour (pre-1.9 data). */
        public MaterialEntry(int materialId, String name, String textureFile,
                             String renderLayer, boolean emissive, int tintColor) {
            this(materialId, name, textureFile, renderLayer, emissive, tintColor, null);
        }

        @Override
        public float[] baseColor() {
            return baseColor == null ? null : baseColor.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof MaterialEntry m && m.materialId == materialId && Objects.equals(m.name, name)
                    && Objects.equals(m.textureFile, textureFile) && Objects.equals(m.renderLayer, renderLayer)
                    && m.emissive == emissive && m.tintColor == tintColor
                    && java.util.Arrays.equals(m.baseColor, baseColor);
        }

        @Override
        public int hashCode() {
            return Objects.hash(materialId, name, textureFile, renderLayer, emissive, tintColor)
                    * 31 + java.util.Arrays.hashCode(baseColor);
        }

        @Override
        public String toString() {
            return "MaterialEntry[" + materialId + ", " + name + ", " + textureFile + ", " + renderLayer + ", "
                    + emissive + ", " + tintColor + ", " + java.util.Arrays.toString(baseColor) + "]";
        }
    }

    /**
     * Aggregated per-face texture data for serialization (v1.2+).
     *
     * @param mappings  face-to-material mappings
     * @param materials material definitions (excluding the default material)
     */
    public record FaceTextureData(List<FaceMappingEntry> mappings,
                                  List<MaterialEntry> materials) {}

    /**
     * Model part entry for multi-part models (v1.3+).
     *
     * <p>Describes a single named part within a model, including its local transform,
     * mesh range in the combined buffer, and visibility/lock state. When the parts
     * list is null or empty, the entire mesh is treated as one implicit "Root" part
     * (backward compatible with pre-1.3 files).
     *
     * @param id          Unique part identifier (UUID string)
     * @param name        User-facing display name
     * @param originX     Transform pivot X
     * @param originY     Transform pivot Y
     * @param originZ     Transform pivot Z
     * @param posX        Translation offset X
     * @param posY        Translation offset Y
     * @param posZ        Translation offset Z
     * @param rotX        Euler rotation X (degrees)
     * @param rotY        Euler rotation Y (degrees)
     * @param rotZ        Euler rotation Z (degrees)
     * @param scaleX      Scale factor X
     * @param scaleY      Scale factor Y
     * @param scaleZ      Scale factor Z
     * @param vertexStart First vertex index in combined buffer
     * @param vertexCount Number of vertices this part owns
     * @param indexStart  First index position in combined index buffer
     * @param indexCount  Number of indices this part owns
     * @param faceStart   First face ID this part owns
     * @param faceCount   Number of faces this part owns
     * @param visible     Whether this part is rendered
     * @param locked      Whether this part is protected from editing
     * @param parentId    Optional parent part ID for hierarchical transforms (v1.5+); null = root
     * @param boneId      Optional bone ID this part is attached to (v1.6+); null = unbound
     */
    public record PartEntry(
            String id, String name,
            float originX, float originY, float originZ,
            float posX, float posY, float posZ,
            float rotX, float rotY, float rotZ,
            float scaleX, float scaleY, float scaleZ,
            int vertexStart, int vertexCount,
            int indexStart, int indexCount,
            int faceStart, int faceCount,
            boolean visible, boolean locked,
            String parentId,
            String boneId
    ) {
        /**
         * Backward-compatible constructor for callers that don't supply a parent or bone.
         * Pre-1.5 readers and call sites get a root part (parentId = null, boneId = null).
         */
        public PartEntry(
                String id, String name,
                float originX, float originY, float originZ,
                float posX, float posY, float posZ,
                float rotX, float rotY, float rotZ,
                float scaleX, float scaleY, float scaleZ,
                int vertexStart, int vertexCount,
                int indexStart, int indexCount,
                int faceStart, int faceCount,
                boolean visible, boolean locked
        ) {
            this(id, name, originX, originY, originZ,
                    posX, posY, posZ,
                    rotX, rotY, rotZ,
                    scaleX, scaleY, scaleZ,
                    vertexStart, vertexCount,
                    indexStart, indexCount,
                    faceStart, faceCount,
                    visible, locked, null, null);
        }

        /**
         * Backward-compatible constructor for v1.5 callers that supply parentId but no bone.
         */
        public PartEntry(
                String id, String name,
                float originX, float originY, float originZ,
                float posX, float posY, float posZ,
                float rotX, float rotY, float rotZ,
                float scaleX, float scaleY, float scaleZ,
                int vertexStart, int vertexCount,
                int indexStart, int indexCount,
                int faceStart, int faceCount,
                boolean visible, boolean locked,
                String parentId
        ) {
            this(id, name, originX, originY, originZ,
                    posX, posY, posZ,
                    rotX, rotY, rotZ,
                    scaleX, scaleY, scaleZ,
                    vertexStart, vertexCount,
                    indexStart, indexCount,
                    faceStart, faceCount,
                    visible, locked, parentId, null);
        }
    }

    /**
     * Bone entry for skeletal models (v1.6+).
     *
     * <p>Bones are pure transform data; they carry no mesh. Mesh parts opt-in by
     * setting {@link PartEntry#boneId()} to reference a bone. The skeleton is a
     * tree formed by each bone's {@code parentBoneId} (null = root). Bone-space
     * transforms are local to the parent (or model space for roots), and the
     * recorded values represent the bind / rest pose.
     *
     * <p>Each bone is a directional segment from a <b>head</b> (joint at
     * {@code origin + pos}, rotated by {@code rot}) to a <b>tail</b> (offset
     * {@code endpoint} in the bone's local frame after rotation). Children
     * (other bones or parts) inherit from the tail frame, mirroring how Maya /
     * Blender rigs propagate rotation down the chain.
     *
     * <p>Bones are only rendered as gizmos inside Open Mason when the user enables
     * bone visualization; at runtime they contribute transforms only.
     *
     * @param id            Stable unique identifier (UUID string)
     * @param name          User-facing display name (e.g. "head", "leg_front_left")
     * @param parentBoneId  Parent bone id, or {@code null} for a root bone
     * @param originX       Local pivot X (head joint position in parent's tail space)
     * @param originY       Local pivot Y
     * @param originZ       Local pivot Z
     * @param posX          Rest-pose translation X applied at the head joint
     * @param posY          Rest-pose translation Y
     * @param posZ          Rest-pose translation Z
     * @param rotX          Rest-pose Euler rotation X in degrees (XYZ order, applied at head)
     * @param rotY          Rest-pose Euler rotation Y in degrees
     * @param rotZ          Rest-pose Euler rotation Z in degrees
     * @param endpointX     Tail offset X in the bone's local frame (post-rotation)
     * @param endpointY     Tail offset Y in the bone's local frame
     * @param endpointZ     Tail offset Z in the bone's local frame
     */
    public record BoneEntry(
            String id, String name, String parentBoneId,
            float originX, float originY, float originZ,
            float posX, float posY, float posZ,
            float rotX, float rotY, float rotZ,
            float endpointX, float endpointY, float endpointZ
    ) {
        public BoneEntry {
            Objects.requireNonNull(id, "bone id cannot be null");
            Objects.requireNonNull(name, "bone name cannot be null");
        }

        /** True if this bone has no parent (i.e. it is a skeleton root). */
        public boolean isRoot() {
            return parentBoneId == null || parentBoneId.isBlank();
        }
    }

    /**
     * Attachment point (socket) entry (v1.7+).
     *
     * <p>Attachment points are pure transform markers; they carry no mesh. Each one
     * names a local frame where another model can be visually mounted at runtime
     * (accessories, armor pieces, stuck projectiles), following its host part
     * through animation.
     *
     * <p>{@code pos}/{@code rot}/{@code scale} are authored in <b>rest-pose model
     * space</b> — the same space the mesh vertices and part pivots live in (what
     * the editor viewport shows), NOT relative to the host part's pivot. At runtime
     * the socket's world frame — the model matrix an attached model is drawn with —
     * is the host part's animated world matrix times
     * {@code T(pos) · R_xyz(rot) · S(scale)}: the socket therefore translates,
     * rotates, AND scales whatever is mounted on it (e.g. shrink a generic
     * accessory model to fit a small mob's head).
     *
     * <p>The host part is resolved by {@code parentPartId} first, then by
     * {@code parentPartName} as a fallback (the same id-then-name convention
     * animation tracks use, since tool part UUIDs are not stable across re-imports).
     * A null parent binds the socket to the model root frame.
     *
     * @param id             Stable unique identifier (UUID string)
     * @param name           User-facing name and the runtime lookup key (e.g. "face")
     * @param parentPartId   Host part id, or {@code null} for the model root
     * @param parentPartName Host part name fallback, or {@code null}
     * @param posX           Socket position X in rest-pose model space
     * @param posY           Socket position Y
     * @param posZ           Socket position Z
     * @param rotX           Socket Euler rotation X in degrees (XYZ order)
     * @param rotY           Socket Euler rotation Y in degrees
     * @param rotZ           Socket Euler rotation Z in degrees
     * @param scaleX         Scale X applied to the attached model (1 = unchanged)
     * @param scaleY         Scale Y
     * @param scaleZ         Scale Z
     */
    public record AttachmentPointEntry(
            String id, String name,
            String parentPartId, String parentPartName,
            float posX, float posY, float posZ,
            float rotX, float rotY, float rotZ,
            float scaleX, float scaleY, float scaleZ
    ) {
        public AttachmentPointEntry {
            Objects.requireNonNull(id, "attachment point id cannot be null");
            Objects.requireNonNull(name, "attachment point name cannot be null");
        }

        /** Backward-compatible constructor for callers that don't set a scale (unit scale). */
        public AttachmentPointEntry(
                String id, String name,
                String parentPartId, String parentPartName,
                float posX, float posY, float posZ,
                float rotX, float rotY, float rotZ
        ) {
            this(id, name, parentPartId, parentPartName,
                    posX, posY, posZ, rotX, rotY, rotZ, 1f, 1f, 1f);
        }

        /** True if this socket is bound to the model root rather than a part. */
        public boolean isModelRoot() {
            return (parentPartId == null || parentPartId.isBlank())
                    && (parentPartName == null || parentPartName.isBlank());
        }
    }

    /**
     * Model-level transform data (v1.4+).
     * Represents the overall model position, rotation, and scale in the editor viewport.
     *
     * @param posX     Translation X
     * @param posY     Translation Y
     * @param posZ     Translation Z
     * @param rotX     Euler rotation X (degrees)
     * @param rotY     Euler rotation Y (degrees)
     * @param rotZ     Euler rotation Z (degrees)
     * @param scaleX   Scale factor X
     * @param scaleY   Scale factor Y
     * @param scaleZ   Scale factor Z
     */
    public record ModelTransform(
            float posX, float posY, float posZ,
            float rotX, float rotY, float rotZ,
            float scaleX, float scaleY, float scaleZ
    ) {
        public static ModelTransform identity() {
            return new ModelTransform(0, 0, 0, 0, 0, 0, 1, 1, 1);
        }

        public boolean isIdentity() {
            return posX == 0 && posY == 0 && posZ == 0
                    && rotX == 0 && rotY == 0 && rotZ == 0
                    && scaleX == 1 && scaleY == 1 && scaleZ == 1;
        }
    }

    /**
     * Custom mesh data for subdivided/edited models (v2.0+).
     *
     * <p><strong>TEXTURE SYSTEM LIMITATION:</strong> Current texture assignment uses raw
     * texCoords array with simple uvMode string. Future versions will support:
     * <ul>
     *   <li>Per-face texture atlas coordinate specification</li>
     *   <li>Texture transformation (rotation, scale, offset) per face</li>
     *   <li>Texture wrapping and tiling modes</li>
     *   <li>Support for arbitrary geometry (not cube-locked)</li>
     * </ul>
     *
     * <p>When present, this overrides the standard cube generation.
     * Contains all data needed to reconstruct the exact mesh state.
     *
     * @param vertices Vertex positions (x,y,z interleaved), null for standard cube
     * @param texCoords Texture coordinates (u,v interleaved), null for standard cube - LEGACY: Will be replaced with per-face texture mapping
     * @param indices Triangle indices, null for non-indexed geometry
     * @param triangleToFaceId Face mapping for triangles (preserves original face ID through subdivision)
     * @param uvMode UV mapping mode ("FLAT" or "CUBE_NET") - LEGACY: Cube-specific, will be replaced with flexible texture assignment
     */
    public record MeshData(
            float[] vertices,
            float[] texCoords,
            int[] indices,
            int[] triangleToFaceId,
            String uvMode
    ) {
        /**
         * Check if this mesh data represents custom geometry (not a standard cube).
         *
         * @return true if custom mesh data is present
         */
        public boolean hasCustomGeometry() {
            return vertices != null && vertices.length > 0;
        }

        /**
         * Get the vertex count.
         *
         * @return number of vertices, or 0 if none
         */
        public int getVertexCount() {
            return vertices != null ? vertices.length / 3 : 0;
        }

        /**
         * Get the triangle count.
         *
         * @return number of triangles, or 0 if none
         */
        public int getTriangleCount() {
            return indices != null ? indices.length / 3 : 0;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MeshData meshData)) return false;
            return java.util.Arrays.equals(vertices, meshData.vertices) &&
                    java.util.Arrays.equals(texCoords, meshData.texCoords) &&
                    java.util.Arrays.equals(indices, meshData.indices) &&
                    java.util.Arrays.equals(triangleToFaceId, meshData.triangleToFaceId) &&
                    Objects.equals(uvMode, meshData.uvMode);
        }

        @Override
        public int hashCode() {
            int result = java.util.Arrays.hashCode(vertices);
            result = 31 * result + java.util.Arrays.hashCode(texCoords);
            result = 31 * result + java.util.Arrays.hashCode(indices);
            result = 31 * result + java.util.Arrays.hashCode(triangleToFaceId);
            result = 31 * result + Objects.hashCode(uvMode);
            return result;
        }
    }

    /**
     * Per-vertex skin weights (v1.8+). Weights are stored per PART, indexed by the vertex's position
     * inside that part's geometry (so hiding, reordering or editing other parts never shifts them);
     * a vertex not listed follows its part rigidly. Joints are named by id (part or bone ids) through
     * {@link #jointIds()}, the influence lists index into it. Any number of influences per vertex (rig ABI 3, #170).
     *
     * @param jointIds the joints the weights refer to (bone or part ids)
     * @param parts    weighted vertices per part
     */
    public record SkinData(List<String> jointIds, List<PartSkin> parts) {
        public SkinData {
            jointIds = jointIds == null ? List.of() : List.copyOf(jointIds);
            parts = parts == null ? List.of() : List.copyOf(parts);
        }

        /** True when no vertex carries weights. */
        public boolean isEmpty() {
            for (PartSkin p : parts) {
                if (!p.vertices().isEmpty()) return false;
            }
            return true;
        }
    }

    /**
     * The weighted vertices of one part.
     *
     * @param partId   the part
     * @param vertices weighted vertices, by part-local vertex index
     */
    public record PartSkin(String partId, List<SkinVertex> vertices) {
        public PartSkin {
            vertices = vertices == null ? List.of() : List.copyOf(vertices);
        }
    }

    /**
     * One weighted vertex. In the manifest: {@code [localVertex, joint0, weight0, joint1, weight1, ...]}.
     *
     * @param localVertex vertex index inside its part
     * @param joints      indices into {@link SkinData#jointIds()}
     * @param weights     one weight per joint (normalized on load)
     */
    public record SkinVertex(int localVertex, int[] joints, float[] weights) {
        public SkinVertex {
            joints = joints == null ? new int[0] : joints.clone();
            weights = weights == null ? new float[0] : weights.clone();
            if (joints.length != weights.length) {
                throw new IllegalArgumentException("joints/weights length mismatch");
            }
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof SkinVertex v && v.localVertex == localVertex
                    && java.util.Arrays.equals(v.joints, joints) && java.util.Arrays.equals(v.weights, weights);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * localVertex + java.util.Arrays.hashCode(joints)) + java.util.Arrays.hashCode(weights);
        }

        @Override
        public String toString() {
            return "SkinVertex[" + localVertex + ", " + java.util.Arrays.toString(joints) + ", "
                    + java.util.Arrays.toString(weights) + "]";
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // OMO 1.9 (#168). Thin data carriers: the format rules live in libomformat (omformat/omo.hpp).
    // ------------------------------------------------------------------------------------------------------

    /**
     * Shading (OMO 1.9). Every member may be empty; an all-empty instance means "no shading section".
     *
     * @param smoothWords         the faces that shade smooth: a bitset over face ids, bit {@code f % 32} of word
     *                            {@code f / 32} (unsigned 32-bit words); empty = every face flat
     * @param sharpEdges          sharp edges as soup vertex index pairs, flattened ({@code [a0, b0, a1, b1, ...]})
     * @param autoSmoothAngle     auto-smooth angle in degrees, or null when unset
     * @param customNormalVertices custom normals: the soup vertex of each row
     * @param customNormalOct     custom normals: 2 octahedral snorm16 values per row ({@code [x0, y0, x1, y1, ...]})
     */
    public record ShadingData(int[] smoothWords, int[] sharpEdges, Float autoSmoothAngle,
                              int[] customNormalVertices, short[] customNormalOct) {
        public ShadingData {
            smoothWords = smoothWords == null ? new int[0] : smoothWords.clone();
            sharpEdges = sharpEdges == null ? new int[0] : sharpEdges.clone();
            customNormalVertices = customNormalVertices == null ? new int[0] : customNormalVertices.clone();
            customNormalOct = customNormalOct == null ? new short[0] : customNormalOct.clone();
            if (sharpEdges.length % 2 != 0) {
                throw new IllegalArgumentException("sharpEdges holds vertex pairs (even length)");
            }
            if (customNormalOct.length != customNormalVertices.length * 2) {
                throw new IllegalArgumentException("customNormalOct holds 2 values per custom normal row");
            }
        }

        /** True when nothing is set (no shading section is written). */
        public boolean isEmpty() {
            boolean anySmooth = false;
            for (int w : smoothWords) anySmooth |= w != 0;
            return !anySmooth && sharpEdges.length == 0 && autoSmoothAngle == null
                    && customNormalVertices.length == 0;
        }

        @Override
        public int[] smoothWords() {
            return smoothWords.clone();
        }

        @Override
        public int[] sharpEdges() {
            return sharpEdges.clone();
        }

        @Override
        public int[] customNormalVertices() {
            return customNormalVertices.clone();
        }

        @Override
        public short[] customNormalOct() {
            return customNormalOct.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ShadingData s && java.util.Arrays.equals(s.smoothWords, smoothWords)
                    && java.util.Arrays.equals(s.sharpEdges, sharpEdges)
                    && Objects.equals(s.autoSmoothAngle, autoSmoothAngle)
                    && java.util.Arrays.equals(s.customNormalVertices, customNormalVertices)
                    && java.util.Arrays.equals(s.customNormalOct, customNormalOct);
        }

        @Override
        public int hashCode() {
            return Objects.hash(java.util.Arrays.hashCode(smoothWords), java.util.Arrays.hashCode(sharpEdges),
                    autoSmoothAngle, java.util.Arrays.hashCode(customNormalVertices),
                    java.util.Arrays.hashCode(customNormalOct));
        }

        @Override
        public String toString() {
            return "ShadingData[smooth=" + java.util.Arrays.toString(smoothWords) + ", sharpEdges="
                    + java.util.Arrays.toString(sharpEdges) + ", autoSmoothAngle=" + autoSmoothAngle
                    + ", customNormals=" + customNormalVertices.length + "]";
        }
    }

    /**
     * A named vertex group (OMO 1.9): weighted part-local vertices, skin's row layout with one influence.
     *
     * @param name  group name
     * @param parts the weighted vertices per part
     */
    public record VertexGroupData(String name, List<GroupPart> parts) {
        public VertexGroupData {
            Objects.requireNonNull(name, "vertex group name cannot be null");
            parts = parts == null ? List.of() : List.copyOf(parts);
        }
    }

    /**
     * One part's rows of a vertex group.
     *
     * @param partId   the part
     * @param vertices part-local vertex per row
     * @param weights  one weight per row
     */
    public record GroupPart(String partId, int[] vertices, float[] weights) {
        public GroupPart {
            Objects.requireNonNull(partId, "partId cannot be null");
            vertices = vertices == null ? new int[0] : vertices.clone();
            weights = weights == null ? new float[0] : weights.clone();
            if (vertices.length != weights.length) {
                throw new IllegalArgumentException("vertices/weights length mismatch");
            }
        }

        @Override
        public int[] vertices() {
            return vertices.clone();
        }

        @Override
        public float[] weights() {
            return weights.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof GroupPart p && p.partId.equals(partId)
                    && java.util.Arrays.equals(p.vertices, vertices) && java.util.Arrays.equals(p.weights, weights);
        }

        @Override
        public int hashCode() {
            return Objects.hash(partId, java.util.Arrays.hashCode(vertices), java.util.Arrays.hashCode(weights));
        }

        @Override
        public String toString() {
            return "GroupPart[" + partId + ", " + java.util.Arrays.toString(vertices) + ", "
                    + java.util.Arrays.toString(weights) + "]";
        }
    }

    /**
     * A shape key (OMO 1.9): sparse per-part deltas from the basis.
     *
     * @param name          morph name
     * @param defaultWeight weight when nothing drives it
     * @param min           slider minimum
     * @param max           slider maximum
     * @param parts         deltas per part
     */
    public record MorphData(String name, float defaultWeight, float min, float max, List<MorphPart> parts) {
        public MorphData {
            Objects.requireNonNull(name, "morph name cannot be null");
            parts = parts == null ? List.of() : List.copyOf(parts);
        }
    }

    /**
     * One part's rows of a morph.
     *
     * @param partId   the part
     * @param vertices part-local vertex per row
     * @param deltas   3 position-delta floats per row
     * @param normals  3 normal-delta floats per row, or null when this part has no normal deltas
     */
    public record MorphPart(String partId, int[] vertices, float[] deltas, float[] normals) {
        public MorphPart {
            Objects.requireNonNull(partId, "partId cannot be null");
            vertices = vertices == null ? new int[0] : vertices.clone();
            deltas = deltas == null ? new float[0] : deltas.clone();
            normals = normals == null ? null : normals.clone();
            if (deltas.length != vertices.length * 3) {
                throw new IllegalArgumentException("deltas holds 3 floats per row");
            }
            if (normals != null && normals.length != vertices.length * 3) {
                throw new IllegalArgumentException("normals holds 3 floats per row");
            }
        }

        @Override
        public int[] vertices() {
            return vertices.clone();
        }

        @Override
        public float[] deltas() {
            return deltas.clone();
        }

        @Override
        public float[] normals() {
            return normals == null ? null : normals.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof MorphPart p && p.partId.equals(partId)
                    && java.util.Arrays.equals(p.vertices, vertices) && java.util.Arrays.equals(p.deltas, deltas)
                    && java.util.Arrays.equals(p.normals, normals);
        }

        @Override
        public int hashCode() {
            return Objects.hash(partId, java.util.Arrays.hashCode(vertices), java.util.Arrays.hashCode(deltas),
                    java.util.Arrays.hashCode(normals));
        }

        @Override
        public String toString() {
            return "MorphPart[" + partId + ", rows=" + vertices.length + ", normals=" + (normals != null) + "]";
        }
    }
}
