package com.openmason.main.systems.rendering.model.io.omo;

import com.openmason.engine.format.omo.CendaOmo;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OmoDocument;
import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.main.systems.rendering.model.editable.CubeGeometry;
import com.openmason.main.systems.rendering.model.editable.ModelGeometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Deserializer for Open Mason Object (.OMO) file format.
 *
 * <p><strong>TEXTURE SYSTEM LIMITATION:</strong> Current version loads legacy texture data
 * (raw texCoords + uvMode string). Future versions will support per-face texture atlas
 * coordinates, transformations, and flexible mapping modes. Will require texture creator upgrade.
 *
 * <p>Reads ZIP-based .OMO files and reconstructs BlockModel instances. The codec is native
 * ({@code libomformat} through the engine's {@link OmoDocument}, OpenMason #167); this class maps the document
 * onto the editor's model. The deserializer:
 * <ul>
 *   <li>Reads and validates manifest.json</li>
 *   <li>Extracts embedded .OMT texture file</li>
 *   <li>Reconstructs model geometry</li>
 *   <li>Creates BlockModel with loaded data</li>
 *   <li>Loads mesh data for all models (v2.1+)</li>
 * </ul>
 *
 * <p><strong>Loading behavior:</strong>
 * <ul>
 *   <li>Modern files (v2.1+): Include mesh data - self-contained, no generation needed</li>
 *   <li>Legacy files (v1.0-2.0): No mesh data - require generation fallback (see ModelOperationService)</li>
 * </ul>
 *
 * <p>Validation includes:
 * <ul>
 *   <li>Format version compatibility (1.0 .. 1.x; a newer major version is refused natively, versions compare
 *       as integers so "1.10" is newer than "1.9")</li>
 *   <li>Required fields presence</li>
 *   <li>Geometry data validity</li>
 *   <li>Texture file existence</li>
 * </ul>
 *
 * <p>Design Principles:
 * <ul>
 *   <li>SOLID: Single Responsibility - only handles .OMO file reading</li>
 *   <li>Fail-fast: Comprehensive validation with clear error messages</li>
 *   <li>Backward Compatible: Supports legacy files without mesh data</li>
 * </ul>
 *
 * @since 1.0
 * @since 2.0 Added mesh data support
 * @since 2.1 Mesh data expected for all new files
 */
public class OMODeserializer {

    private static final Logger logger = LoggerFactory.getLogger(OMODeserializer.class);

    // Last loaded mesh data (accessible after load())
    private OMOFormat.MeshData lastLoadedMeshData;

    // Last loaded part entries (v1.3+)
    private List<OMOFormat.PartEntry> lastLoadedPartEntries;

    // Last loaded model transform (v1.4+)
    private OMOFormat.ModelTransform lastLoadedModelTransform;

    // Last loaded bone skeleton (v1.6+)
    private List<OMOFormat.BoneEntry> lastLoadedBoneEntries;

    private List<OMOFormat.AttachmentPointEntry> lastLoadedAttachmentPointEntries;

    // Last loaded skin weights (v1.8+)
    private OMOFormat.SkinData lastLoadedSkinData;

    // Last loaded OMO 1.9 sections
    private OMOFormat.ShadingData lastLoadedShadingData;
    private List<OMOFormat.VertexGroupData> lastLoadedVertexGroups;
    private List<OMOFormat.MorphData> lastLoadedMorphs;

    // Last loaded face texture data (v1.2+, accessible after load())
    private OMOFormat.FaceTextureData lastLoadedFaceTextureData;
    private Map<String, byte[]> lastLoadedMaterialTextures;

    // Version of the last loaded file
    private String lastLoadedVersion;

    /**
     * Creates a new .OMO deserializer.
     */
    public OMODeserializer() {
    }

    /**
     * Get the mesh data from the last successful load() call.
     * Returns null if no mesh data was present (v1.0 file or standard cube).
     *
     * @return the loaded mesh data, or null
     */
    public OMOFormat.MeshData getLastLoadedMeshData() {
        return lastLoadedMeshData;
    }

    /**
     * Get face texture data from the last successful load() call.
     * Returns null if no face texture data was present (pre-1.2 file). Materials may carry an OMO 1.9
     * {@link OMOFormat.MaterialEntry#baseColor() base colour} and no image.
     *
     * @return the loaded face texture data, or null
     */
    public OMOFormat.FaceTextureData getLastLoadedFaceTextureData() {
        return lastLoadedFaceTextureData;
    }

    /**
     * Get material texture PNGs from the last successful load() call.
     * Keys are ZIP entry names (e.g. "material_1.png"), values are PNG bytes, in archive order.
     *
     * @return map of material textures, or null if none were present
     */
    public Map<String, byte[]> getLastLoadedMaterialTextures() {
        return lastLoadedMaterialTextures;
    }

    /**
     * Get model part entries from the last successful load() call (v1.3+).
     * Returns null if no part entries were present (single-part model).
     *
     * @return list of part entries, or null
     */
    public List<OMOFormat.PartEntry> getLastLoadedPartEntries() {
        return lastLoadedPartEntries;
    }

    /**
     * Get model-level transform from the last successful load() call (v1.4+).
     * Returns null if no model transform was present (pre-1.4 file or identity transform).
     *
     * @return the model transform, or null
     */
    public OMOFormat.ModelTransform getLastLoadedModelTransform() {
        return lastLoadedModelTransform;
    }

    /**
     * Get bone skeleton entries from the last successful load() call (v1.6+).
     * Returns null for pre-1.6 files or files without a skeleton.
     */
    public List<OMOFormat.BoneEntry> getLastLoadedBoneEntries() {
        return lastLoadedBoneEntries;
    }

    /**
     * Get attachment point (socket) entries from the last successful load() call (v1.7+).
     * Returns null for pre-1.7 files or files without sockets.
     */
    public List<OMOFormat.AttachmentPointEntry> getLastLoadedAttachmentPointEntries() {
        return lastLoadedAttachmentPointEntries;
    }

    /**
     * Skin weights from the last successful load() call (v1.8+), by part-local vertex (the local
     * index inside each part's saved vertex span — the restored part's own vertex order). Null when
     * the file has none.
     */
    public OMOFormat.SkinData getLastLoadedSkinData() {
        return lastLoadedSkinData;
    }

    /** OMO 1.9 shading (smooth faces, sharp edges, auto smooth, custom normals) of the last load, or null. */
    public OMOFormat.ShadingData getLastLoadedShadingData() {
        return lastLoadedShadingData;
    }

    /** OMO 1.9 vertex groups of the last load (by part-local vertex, like the skin), or null when none. */
    public List<OMOFormat.VertexGroupData> getLastLoadedVertexGroups() {
        return lastLoadedVertexGroups;
    }

    /** OMO 1.9 morphs / shape keys of the last load (by part-local vertex, like the skin), or null when none. */
    public List<OMOFormat.MorphData> getLastLoadedMorphs() {
        return lastLoadedMorphs;
    }

    /** The format version of the last loaded file, or null. */
    public String getLastLoadedVersion() {
        return lastLoadedVersion;
    }

    /**
     * Loads a BlockModel from a .OMO file.
     * After loading, call getLastLoadedMeshData() to retrieve any custom mesh data.
     *
     * @param filePath path to the .OMO file
     * @return the loaded BlockModel, or null if loading failed
     */
    public BlockModel load(String filePath) {
        // Clear previous data
        lastLoadedMeshData = null;
        lastLoadedFaceTextureData = null;
        lastLoadedMaterialTextures = null;
        lastLoadedPartEntries = null;
        lastLoadedModelTransform = null;
        lastLoadedBoneEntries = null;
        lastLoadedAttachmentPointEntries = null;
        lastLoadedSkinData = null;
        lastLoadedShadingData = null;
        lastLoadedVertexGroups = null;
        lastLoadedMorphs = null;
        lastLoadedVersion = null;

        if (filePath == null || filePath.trim().isEmpty()) {
            logger.error("Invalid file path");
            return null;
        }

        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            logger.error("File does not exist: {}", filePath);
            return null;
        }

        if (!OMOFormat.hasOMOExtension(filePath)) {
            logger.warn("File does not have .omo extension: {}", filePath);
        }

        try (OmoDocument doc = OmoDocument.read(Files.readAllBytes(path))) {
            String version = doc.version();
            if (CendaOmo.compareVersions(version, OMOFormat.MIN_SUPPORTED_VERSION) < 0) {
                throw new IOException("Unsupported format version: " + version + " (older than "
                        + OMOFormat.MIN_SUPPORTED_VERSION + ")");
            }
            byte[] texture = doc.texture();
            if (texture == null) {
                throw new IOException("Missing texture.omt in .OMO file");
            }
            OMOFormat.GeometryData geometry = doc.geometry();

            OMOFormat.MeshData meshData = doc.mesh();
            OMOFormat.FaceTextureData faceTextureData = doc.hasFaceTextures()
                    ? new OMOFormat.FaceTextureData(doc.faceMappings(), doc.materials()) : null;
            Map<String, byte[]> images = doc.materialImages();
            List<OMOFormat.PartEntry> parts = doc.parts();
            OMOFormat.ModelTransform transform = doc.modelTransform();
            List<OMOFormat.BoneEntry> bones = doc.bones();
            List<OMOFormat.AttachmentPointEntry> sockets = doc.attachmentPoints();
            List<OMOFormat.VertexGroupData> groups = doc.vertexGroups();
            List<OMOFormat.MorphData> morphs = doc.morphs();

            // Store mesh data, face texture data, part entries, and model transform for external access
            lastLoadedVersion = version;
            lastLoadedMeshData = meshData;
            lastLoadedFaceTextureData = faceTextureData;
            lastLoadedMaterialTextures = images.isEmpty() ? null : images;
            lastLoadedPartEntries = parts.isEmpty() ? null : parts;
            lastLoadedModelTransform = transform.isIdentity() ? null : transform;
            lastLoadedBoneEntries = bones.isEmpty() ? null : bones;
            lastLoadedAttachmentPointEntries = sockets.isEmpty() ? null : sockets;
            lastLoadedSkinData = doc.skin();
            lastLoadedShadingData = doc.shading();
            lastLoadedVertexGroups = groups.isEmpty() ? null : groups;
            lastLoadedMorphs = morphs.isEmpty() ? null : morphs;

            // Build BlockModel from loaded data
            BlockModel model = buildModel(doc.objectName(), geometry, extractTexture(texture));

            // Set file path and mark clean (just loaded)
            model.setFilePath(path);
            model.markClean();

            logger.info("Loaded .OMO file: {} (version={}, hasMesh={})",
                    filePath, version, lastLoadedMeshData != null);
            return model;

        } catch (Exception e) {
            logger.error("Error loading .OMO file: {}", filePath, e);
            return null;
        }
    }

    /**
     * Writes the embedded texture.omt to a temporary file (the editor model references its texture by path).
     *
     * @param texture the texture.omt bytes
     * @return path to the extracted texture file
     * @throws IOException if extraction fails
     */
    private Path extractTexture(byte[] texture) throws IOException {
        // Note: Not deleting on exit - texture is needed for model editing
        Path tempFile = Files.createTempFile("omo_texture_", ".omt");
        Files.write(tempFile, texture);
        logger.debug("Extracted texture.omt to: {}", tempFile);
        return tempFile;
    }

    /**
     * Builds a BlockModel from loaded data.
     * Note: Texture format (cube net vs flat) is auto-detected from .OMT dimensions at load time.
     */
    private BlockModel buildModel(String objectName, OMOFormat.GeometryData geometryData, Path texturePath) {
        OMOFormat.Position position = geometryData.position();
        ModelGeometry geometry = new CubeGeometry(
            geometryData.width(),
            geometryData.height(),
            geometryData.depth(),
            position.x(),
            position.y(),
            position.z()
        );
        return new BlockModel(objectName, geometry, texturePath);
    }
}
