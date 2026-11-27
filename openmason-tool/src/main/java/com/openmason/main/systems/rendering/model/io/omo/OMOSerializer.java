package com.openmason.main.systems.rendering.model.io.omo;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OmoDocument;
import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.main.systems.rendering.model.editable.ModelGeometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Serializer for Open Mason Object (.OMO) file format.
 *
 * <p><strong>TEXTURE SYSTEM LIMITATION:</strong> Current version saves legacy texture data
 * (raw texCoords + uvMode string). Future versions will support per-face texture atlas
 * coordinates, transformations, and flexible mapping modes. Will require texture creator upgrade.
 *
 * <p>Creates a ZIP-based container with:
 * <ul>
 *   <li>manifest.json - Model metadata, geometry, and mesh data</li>
 *   <li>texture.omt - Embedded texture file</li>
 * </ul>
 *
 * <p>The codec is native ({@code libomformat} through the engine's {@link OmoDocument}, OpenMason #167): this class
 * fills a native document from the editor's pending data and writes its archive. The archive's entries (the
 * manifest included) are byte-identical to what the former Java writer produced ({@code OmoGoldenParityTest}).
 *
 * <p>The serializer handles:
 * <ul>
 *   <li>Building manifest from BlockModel</li>
 *   <li>Embedding .OMT texture file</li>
 *   <li>Writing ZIP archive</li>
 *   <li>Atomic file operations (write to temp, then move)</li>
 *   <li>Mesh data for all models (v2.0+) - makes .omo files self-contained</li>
 * </ul>
 *
 * <p><strong>Important:</strong> All .omo files should include mesh data to be self-contained.
 * Files without mesh data are considered legacy and require generation fallback.
 *
 * <p>Design Principles:
 * <ul>
 *   <li>SOLID: Single Responsibility - only handles .OMO file writing</li>
 *   <li>DRY: Reuses existing .OMT files (no re-encoding)</li>
 *   <li>Self-Contained: Always includes full mesh topology</li>
 * </ul>
 *
 * @since 1.0
 * @since 2.0 Added mesh data support
 * @since 2.1 Mesh data now always saved for self-contained files
 */
public class OMOSerializer {

    private static final Logger logger = LoggerFactory.getLogger(OMOSerializer.class);

    // Optional mesh data for saving subdivided models
    private OMOFormat.MeshData pendingMeshData;

    // Optional face texture data for per-face material persistence (v1.2+)
    private OMOFormat.FaceTextureData pendingFaceTextureData;
    private Map<Integer, byte[]> pendingMaterialTexturePNGs;

    // Optional part entries for multi-part models (v1.3+)
    private List<OMOFormat.PartEntry> pendingParts;

    // Optional model-level transform (v1.4+)
    private OMOFormat.ModelTransform pendingModelTransform;

    // Optional bone skeleton entries (v1.6+)
    private List<OMOFormat.BoneEntry> pendingBones;

    // Optional attachment point (socket) entries (v1.7+)
    private List<OMOFormat.AttachmentPointEntry> pendingAttachmentPoints;

    // Optional per-vertex skin weights (v1.8+), by part-local vertex in the WRITTEN layout
    private OMOFormat.SkinData pendingSkin;

    // Optional OMO 1.9 sections (a file carrying any of them is written as 1.9)
    private OMOFormat.ShadingData pendingShading;
    private List<OMOFormat.VertexGroupData> pendingVertexGroups;
    private List<OMOFormat.MorphData> pendingMorphs;

    /**
     * Creates a new .OMO serializer.
     */
    public OMOSerializer() {
    }

    /**
     * Set mesh data to be saved with the next model.
     * Call this before save() to include mesh data in the .OMO file.
     *
     * <p><strong>Note:</strong> Mesh data should ALWAYS be provided to make .omo files self-contained.
     * Files without mesh data require legacy generation fallback.
     *
     * @param meshData The mesh data to save (should not be null for new files)
     */
    public void setMeshData(OMOFormat.MeshData meshData) {
        this.pendingMeshData = meshData;
    }

    /**
     * Clear any pending mesh data.
     */
    public void clearMeshData() {
        this.pendingMeshData = null;
    }

    /**
     * Set face texture data to be saved with the next model (v1.2+).
     *
     * @param faceTextureData face mapping and material metadata (a 1.9 material may carry a base colour and no PNG)
     * @param materialPNGs    material textures as PNG byte arrays, keyed by materialId; written as
     *                        {@code material_<id>.png} in the map's iteration order
     */
    public void setFaceTextureData(OMOFormat.FaceTextureData faceTextureData,
                                    Map<Integer, byte[]> materialPNGs) {
        this.pendingFaceTextureData = faceTextureData;
        this.pendingMaterialTexturePNGs = materialPNGs;
    }

    /**
     * Set model part entries to be saved with the next model (v1.3+).
     * Each entry contains the part's identity, transform, mesh range, and per-part geometry.
     *
     * @param parts List of part entries, or null for single-part models
     */
    public void setPartEntries(List<OMOFormat.PartEntry> parts) {
        this.pendingParts = parts;
    }

    /**
     * Set model-level transform to be saved with the next model (v1.4+).
     *
     * @param transform the model-level position, rotation, and scale
     */
    public void setModelTransform(OMOFormat.ModelTransform transform) {
        this.pendingModelTransform = transform;
    }

    /**
     * Set the bone skeleton entries to be saved with the next model (v1.6+).
     * Null or empty means the file carries no skeleton (backward-compatible).
     */
    public void setBoneEntries(List<OMOFormat.BoneEntry> bones) {
        this.pendingBones = bones;
    }

    /**
     * Set the attachment point (socket) entries to be saved with the next model (v1.7+).
     * Null or empty means the file carries no sockets (backward-compatible).
     */
    public void setAttachmentPointEntries(List<OMOFormat.AttachmentPointEntry> attachmentPoints) {
        this.pendingAttachmentPoints = attachmentPoints;
    }

    /**
     * Set the skin weights to be saved with the next model (v1.8+). Each part's local vertex indices
     * must be positions inside that part's written vertex span ({@code vertexStart} of its part
     * entry) — see {@code OmoSkinExport}. Null or empty writes no {@code skin}.
     */
    public void setSkinData(OMOFormat.SkinData skin) {
        this.pendingSkin = skin;
    }

    /**
     * Set the OMO 1.9 shading section (smooth faces by face id, sharp edges / custom normals by WRITTEN soup
     * vertex). Null or empty writes none.
     */
    public void setShadingData(OMOFormat.ShadingData shading) {
        this.pendingShading = shading;
    }

    /**
     * Set the OMO 1.9 vertex groups, by part-local vertex in the WRITTEN layout (like the skin). Null or empty
     * writes none.
     */
    public void setVertexGroups(List<OMOFormat.VertexGroupData> groups) {
        this.pendingVertexGroups = groups;
    }

    /**
     * Set the OMO 1.9 morphs / shape keys, by part-local vertex in the WRITTEN layout (like the skin). Null or
     * empty writes none.
     */
    public void setMorphs(List<OMOFormat.MorphData> morphs) {
        this.pendingMorphs = morphs;
    }

    /**
     * Saves a BlockModel to a .OMO file with mesh data.
     * Convenience method that sets mesh data and saves in one call.
     *
     * <p><strong>Note:</strong> Mesh data should ALWAYS be provided to make .omo files self-contained.
     * Files without mesh data require legacy generation fallback.
     *
     * @param model the model to save, must not be null
     * @param filePath output file path, will be given .omo extension if missing
     * @param meshData mesh data to save (should not be null for new files)
     * @return true if save succeeded
     */
    public boolean save(BlockModel model, String filePath, OMOFormat.MeshData meshData) {
        setMeshData(meshData);
        return save(model, filePath);
    }

    /**
     * Saves a BlockModel to a .OMO file.
     *
     * <p>The save operation is atomic - writes to a temporary file first,
     * then moves to the final location to prevent corruption on failure.
     *
     * @param model the model to save, must not be null
     * @param filePath output file path, will be given .omo extension if missing
     * @return true if save succeeded
     */
    public boolean save(BlockModel model, String filePath) {
        if (model == null) {
            logger.error("Cannot save null model");
            return false;
        }

        if (filePath == null || filePath.trim().isEmpty()) {
            logger.error("Invalid file path");
            return false;
        }

        // Ensure .omo extension
        filePath = OMOFormat.ensureExtension(filePath);

        // Validate texture exists
        Path texturePath = model.getTexturePath();
        if (texturePath == null || !Files.exists(texturePath)) {
            logger.error("Model has no texture or texture file does not exist: {}", texturePath);
            return false;
        }

        try {
            byte[] archive;
            try (OmoDocument doc = buildDocument(model, Files.readAllBytes(texturePath))) {
                archive = doc.write();
            }

            // Write to temporary file first (atomic operation)
            Path tempFile = Files.createTempFile("omo_save_", ".tmp");
            Files.write(tempFile, archive);

            // Clear pending data after the archive is written
            clearPending();

            // Move temp file to final location (atomic on most filesystems)
            Path finalPath = Path.of(filePath);
            Files.move(tempFile, finalPath,
                      java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // Update model state
            model.setFilePath(finalPath);
            model.markClean();

            logger.info("Saved .OMO file: {}", filePath);
            return true;

        } catch (IOException | RuntimeException e) {
            logger.error("Error saving .OMO file: {}", filePath, e);
            return false;
        }
    }

    private void clearPending() {
        pendingMeshData = null;
        pendingFaceTextureData = null;
        pendingMaterialTexturePNGs = null;
        pendingParts = null;
        pendingModelTransform = null;
        pendingBones = null;
        pendingAttachmentPoints = null;
        pendingSkin = null;
        pendingShading = null;
        pendingVertexGroups = null;
        pendingMorphs = null;
    }

    /**
     * Fills a native document from the model and the pending data. Entry order: manifest.json, texture.omt, then
     * the material PNGs in the pending map's iteration order (what the Java writer did).
     */
    private OmoDocument buildDocument(BlockModel model, byte[] texture) {
        ModelGeometry geometry = model.getGeometry();
        OmoDocument doc = OmoDocument.create();
        try {
            // Jackson's pretty printer used the platform line separator; keep that.
            doc.setCrlf("\r\n".equals(System.lineSeparator()));
            doc.setObjectName(model.getName());
            doc.setModelType(model.getModelType());
            doc.setGeometry(new OMOFormat.GeometryData(
                    geometry.getWidth(), geometry.getHeight(), geometry.getDepth(),
                    new OMOFormat.Position(geometry.getX(), geometry.getY(), geometry.getZ())));
            doc.setTextureFile(OMOFormat.DEFAULT_TEXTURE_FILENAME);
            doc.setMesh(pendingMeshData); // null for a standard cube

            if (pendingFaceTextureData != null) {
                doc.setFaceMappings(pendingFaceTextureData.mappings());
                doc.setMaterials(pendingFaceTextureData.materials());
            }
            if (pendingParts != null && !pendingParts.isEmpty()) doc.setParts(pendingParts);
            doc.setModelTransform(pendingModelTransform); // identity / null is written as null
            if (pendingBones != null && !pendingBones.isEmpty()) doc.setBones(pendingBones);
            if (pendingAttachmentPoints != null && !pendingAttachmentPoints.isEmpty()) {
                doc.setAttachmentPoints(pendingAttachmentPoints);
            }
            doc.setSkin(pendingSkin);
            if (pendingShading != null && !pendingShading.isEmpty()) doc.setShading(pendingShading);
            if (pendingVertexGroups != null && !pendingVertexGroups.isEmpty()) doc.setVertexGroups(pendingVertexGroups);
            if (pendingMorphs != null && !pendingMorphs.isEmpty()) doc.setMorphs(pendingMorphs);

            doc.setTexture(texture);
            if (pendingMaterialTexturePNGs != null) {
                for (Map.Entry<Integer, byte[]> entry : pendingMaterialTexturePNGs.entrySet()) {
                    if (entry.getValue() != null) {
                        doc.setMaterialImage("material_" + entry.getKey() + ".png", entry.getValue());
                    }
                }
            }
            return doc;
        } catch (RuntimeException e) {
            doc.close();
            throw e;
        }
    }
}
