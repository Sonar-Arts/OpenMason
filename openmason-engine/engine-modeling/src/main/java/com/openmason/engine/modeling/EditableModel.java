package com.openmason.engine.modeling;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.openmason.engine.rendering.model.draw.MaterialTextures;
import com.openmason.engine.rendering.model.draw.ModelDrawPlan;
import com.openmason.engine.rendering.model.draw.ModelGpuMesh;
import com.openmason.engine.rendering.model.draw.ModelLighting;
import com.openmason.engine.rendering.model.draw.ModelRenderer;
import com.openmason.engine.rendering.model.draw.PartMatrices;
import com.openmason.engine.modeling.ModelingConstants;
import com.openmason.engine.modeling.core.MeshGpuState;
import com.openmason.engine.modeling.core.MeshMutationCoordinator;
import com.openmason.engine.modeling.core.MeshRebuildPipeline;
import com.openmason.engine.modeling.core.MeshSerializationAdapter;
import com.openmason.engine.modeling.core.PartMeshBridge;
import com.openmason.engine.modeling.core.VertexDataManager;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.modeling.UVMode;
import com.openmason.engine.modeling.extraction.FaceTriangleQuery;
import com.openmason.engine.modeling.extraction.EdgeExtractor;
import com.openmason.engine.modeling.extraction.FaceExtractor;
import com.openmason.engine.modeling.editable.EditableMesh;
import com.openmason.engine.modeling.mapping.EditableMeshVertexMapper;
import com.openmason.engine.modeling.mapping.ITriangleFaceMapper;
import com.openmason.engine.modeling.mapping.IUniqueVertexMapper;
import com.openmason.engine.modeling.mapping.TriangleFaceMapper;
import com.openmason.engine.modeling.notification.IMeshChangeNotifier;
import com.openmason.engine.modeling.notification.MeshChangeNotifier;
import com.openmason.engine.modeling.topology.MeshTopology;
import com.openmason.engine.modeling.uv.*;
import com.openmason.engine.format.omo.OMOFormat;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import java.util.Arrays;

import static org.lwjgl.opengl.GL11.*;

/**
 * The editable model: authoritative {@link EditableMesh}, parts, per-face
 * materials/UVs and topology, plus the mesh operations over them. Coordinator
 * class that wires and delegates to focused subsystems:
 *
 * <ul>
 *   <li>{@link MeshMutationCoordinator} — vertex updates, subdivision, edge/face operations</li>
 *   <li>{@link TextureGPUOperations} — texture I/O, UV regeneration, material management</li>
 *   <li>{@link MeshSerializationAdapter} — OMO load/save, snapshot restore</li>
 *   <li>{@link MeshRebuildPipeline} — shared post-mutation rebuild sequence</li>
 * </ul>
 *
 * <p>It owns no draw code: {@link #render} hands its {@link ModelGpuMesh} and a
 * material-sorted {@link ModelDrawPlan} to the engine {@link ModelRenderer} — the
 * same path games draw entity models through. The GPU copy is synced lazily
 * at draw time from the dirty state the mesh subsystems report.
 */
public class EditableModel {

    private static final Logger logger = LoggerFactory.getLogger(EditableModel.class);

    // Core subsystems (dependency injection via interfaces)
    private final IVertexDataManager vertexManager;
    private final IUniqueVertexMapper uniqueMapper;
    private final IMeshChangeNotifier changeNotifier;
    private final ITriangleFaceMapper faceMapper;
    private final IModelStateManager stateManager;
    private final FaceTextureManager faceTextureManager;

    // Extractors
    private final FaceExtractor faceExtractor;
    private final EdgeExtractor edgeExtractor;

    // Coordinated subsystems
    private final MeshRebuildPipeline rebuildPipeline;
    private final MeshMutationCoordinator mutationCoordinator;
    private final TextureGPUOperations textureOps;
    private final MeshSerializationAdapter serializationAdapter;
    private final PartMeshBridge partMeshBridge;

    // Part management
    private final ModelPartManager partManager;

    // Cached model bounds (AABB) — nulled on geometry changes, recomputed lazily
    private ModelBounds cachedBounds;

    // When true, render() draws every face untextured in the lighting's base colour
    private boolean forceUnrendered = false;

    // GPU copy: created by initialize(), synced from gpuState at draw time
    private final MeshGpuState gpuState = new MeshGpuState();
    private ModelGpuMesh gpuMesh;
    private ModelDrawPlan drawPlan;
    private float[] uploadedUvs;
    private boolean initialized;

    // Engine-level material ID counter (replaces editor-specific FaceMaterialSection.allocateNextMaterialId).
    //
    // Per-instance, NOT static: ids only have to be unique within one model's
    // FaceTextureManager, and allocateMaterialId() already self-heals against this
    // renderer's own highest existing id. A shared counter meant that merely loading
    // other models (a scene full of them) inflated the ids the editor then wrote into
    // saved .omo files.
    private final java.util.concurrent.atomic.AtomicInteger materialIdCounter =
            new java.util.concurrent.atomic.AtomicInteger(100);

    // Cached identity matrix to avoid per-frame allocation in setUniforms
    private static final org.joml.Matrix4f IDENTITY_MATRIX = new org.joml.Matrix4f();

    /**
     * Create an EditableModel with default subsystems.
     */
    public EditableModel() {
        this(new FaceTextureManager());
    }

    /**
     * Create an EditableModel with a specific FaceTextureManager.
     */
    private EditableModel(FaceTextureManager faceTextureManager) {
        this(
            new VertexDataManager(),
            new EditableMeshVertexMapper(),
            new MeshChangeNotifier(),
            new TriangleFaceMapper(),
            new ModelStateManager(),
            new FaceExtractor(),
            new EdgeExtractor(),
            faceTextureManager
        );
    }

    /**
     * Create an EditableModel with custom subsystems (for testing/extension).
     */
    public EditableModel(
            IVertexDataManager vertexManager,
            EditableMeshVertexMapper uniqueMapper,
            IMeshChangeNotifier changeNotifier,
            ITriangleFaceMapper faceMapper,
            IModelStateManager stateManager,
            FaceExtractor faceExtractor,
            EdgeExtractor edgeExtractor,
            FaceTextureManager faceTextureManager) {
        this.vertexManager = vertexManager;
        this.uniqueMapper = uniqueMapper;
        this.changeNotifier = changeNotifier;
        this.faceMapper = faceMapper;
        this.stateManager = stateManager;
        this.faceExtractor = faceExtractor;
        this.edgeExtractor = edgeExtractor;
        this.faceTextureManager = faceTextureManager != null ? faceTextureManager : new FaceTextureManager();

        // Wire rebuild pipeline (owner of the authoritative EditableMesh)
        this.rebuildPipeline = new MeshRebuildPipeline(
            vertexManager, uniqueMapper, faceMapper, changeNotifier, gpuState);
        this.rebuildPipeline.setBoundsInvalidator(() -> cachedBounds = null);
        this.rebuildPipeline.setFaceTextureManager(this.faceTextureManager);

        // Wire texture operations
        this.textureOps = new TextureGPUOperations(this.faceTextureManager, rebuildPipeline);

        // Wire mutation coordinator
        this.mutationCoordinator = new MeshMutationCoordinator(
            vertexManager, uniqueMapper, changeNotifier,
            this.faceTextureManager, rebuildPipeline, gpuState);

        // Wire serialization adapter
        this.serializationAdapter = new MeshSerializationAdapter(
            this.faceTextureManager, stateManager, rebuildPipeline);

        // Wire part→pipeline bridge (shared with headless model documents)
        this.partMeshBridge = new PartMeshBridge(
            rebuildPipeline, this.faceTextureManager, serializationAdapter, stateManager);

        // Wire part manager with mesh consumer that pushes data through the serialization pipeline
        this.partManager = new ModelPartManager();
        this.partManager.setMeshConsumer(this::onPartMeshRebuilt);
        // Render corners diverge from combined-soup indices for shapes that
        // share vertices across faces — translate before part-range lookups.
        this.partManager.setCombinedVertexResolver(rebuildPipeline::cornerToSoupIndices);

        logger.debug("EditableModel created with subsystems");
    }

    // =========================================================================
    // GPU LIFECYCLE + DRAWING (through the engine ModelRenderer)
    // =========================================================================

    /** Creates the GPU mesh. Must run on the GL thread; safe to call twice. */
    public void initialize() {
        if (initialized) return;
        gpuMesh = new ModelGpuMesh(true, GpuMemoryTracker.Category.OTHER);
        gpuState.markAll();
        rebuildPipeline.markDrawBatchesDirty();
        uploadedUvs = null;
        initialized = true;
    }

    /** True once {@link #initialize()} created the GPU mesh. */
    public boolean isInitialized() {
        return initialized;
    }

    /** Releases the GPU mesh (GL thread). The model's data stays usable. */
    public void cleanup() {
        if (gpuMesh != null) {
            gpuMesh.close();
            gpuMesh = null;
        }
        drawPlan = null;
        uploadedUvs = null;
        initialized = false;
    }

    /**
     * Draws the model through the engine {@link ModelRenderer} lit pass with
     * {@code modelMatrix} (part transforms are baked into the vertices, so the
     * whole model is one part). Faces without a material texture use the model's
     * texture, or the lighting's base colour when there is none; in unrendered
     * mode every face uses the base colour.
     */
    public void render(ModelRenderer renderer, Matrix4f modelMatrix, Matrix4f view,
                       Matrix4f projection, ModelLighting lighting) {
        if (!initialized || renderer == null || vertexManager.getTotalVertexCount() == 0) {
            return;
        }
        syncGpu();
        renderer.drawLit(gpuMesh, drawPlan, forceUnrendered ? MaterialTextures.UNTEXTURED : this::textureForBatch,
                PartMatrices.single(modelMatrix), view, projection, lighting, null);
    }

    /** Draws the model as a see-through wireframe overlay in {@code color}. */
    public void renderWireframe(ModelRenderer renderer, Matrix4f modelMatrix, Matrix4f view,
                                Matrix4f projection, org.joml.Vector4f color) {
        if (!initialized || renderer == null || vertexManager.getTotalVertexCount() == 0) {
            return;
        }
        syncGpu();
        renderer.drawWireframe(gpuMesh, drawPlan, PartMatrices.single(modelMatrix), view, projection, color);
    }

    /**
     * Brings the GPU mesh up to date with the latest derivation: positions only
     * for a vertex drag, UVs only when they actually changed, and the
     * material-sorted index buffer when triangles or face materials changed.
     */
    private void syncGpu() {
        if (gpuState.positionsDirty()) {
            gpuMesh.updatePositions(vertexManager.getVertices());
        }
        if (gpuState.uvsDirty()) {
            float[] uvs = vertexManager.getTexCoords();
            if (uploadedUvs == null || !Arrays.equals(uvs, uploadedUvs)) {
                gpuMesh.updateUvs(uvs);
                uploadedUvs = uvs != null ? uvs.clone() : null;
            }
        }
        if (gpuState.indicesDirty() || rebuildPipeline.isDrawBatchesDirty() || drawPlan == null) {
            ModelDrawPlan.Sorted sorted = ModelDrawPlan.materialSorted(vertexManager.getIndices(), triangleMaterialKeys());
            gpuMesh.updateIndices(sorted.indices());
            drawPlan = sorted.plan();
            rebuildPipeline.clearDrawBatchesDirty();
        }
        gpuState.clear();
    }

    /**
     * Per-triangle batch key: the face material's texture id, or 0 for "the model
     * texture". Without custom materials every triangle shares key 0, so the model
     * draws in one batch.
     */
    private int[] triangleMaterialKeys() {
        int[] indices = vertexManager.getIndices();
        int triangleCount = indices != null ? indices.length / 3 : 0;
        int[] keys = new int[triangleCount];
        if (triangleCount == 0 || !textureOps.hasCustomMaterials()) {
            return keys;
        }
        for (int t = 0; t < triangleCount; t++) {
            int faceId = faceMapper.getOriginalFaceIdForTriangle(t);
            if (faceId < 0) continue;
            FaceTextureMapping mapping = faceTextureManager.getFaceMapping(faceId);
            if (mapping == null) continue;
            MaterialDefinition material = faceTextureManager.getMaterial(mapping.materialId());
            if (material != null && material.textureId() > 0) {
                keys[t] = material.textureId();
            }
        }
        return keys;
    }

    /** Batch key → texture: a material texture, else the model texture, else untextured (0). */
    private int textureForBatch(int key) {
        if (key > 0) {
            return key;
        }
        return textureOps.isTextureActive() ? textureOps.getTextureId() : 0;
    }

    /** GPU-sync dirty state (tests and edit-cost diagnostics). */
    public MeshGpuState getGpuState() {
        return gpuState;
    }

    /** Bytes this model has sent to the GPU since {@link #initialize()} (0 before). */
    public long getUploadedGpuBytes() {
        return gpuMesh != null ? gpuMesh.uploadedBytes() : 0L;
    }

    // =========================================================================
    // UV MODE (legacy — delegates to stateManager)
    // =========================================================================

    /** @deprecated Per-face UV is the only mode. Use {@link #getFaceTextureManager()} instead. */
    @Deprecated
    public void setUVMode(UVMode uvMode) {
        logger.debug("setUVMode({}) called — ignored, per-face UV is the only mode", uvMode);
        if (uvMode != null) {
            stateManager.setUVMode(uvMode);
        }
    }

    /** @deprecated Per-face UV is the only mode. Use {@link #getFaceTextureManager()} instead. */
    @Deprecated
    public UVMode getUVMode() {
        return stateManager.getUVMode();
    }

    // =========================================================================
    // VERTEX POSITION MANAGEMENT (delegates to MeshMutationCoordinator)
    // =========================================================================

    public void updateVertexPosition(int globalIndex, Vector3f position) {
        mutationCoordinator.updateVertexPosition(globalIndex, position);
    }

    public void updateVertexPositions(float[] positions) {
        mutationCoordinator.updateVertexPositions(positions);
    }

    public Vector3f getVertexPosition(int globalIndex) {
        return vertexManager.getVertexPosition(globalIndex);
    }

    public int getTotalVertexCount() {
        return vertexManager.getTotalVertexCount();
    }

    public float[] getAllMeshVertexPositions() {
        return vertexManager.getAllMeshVertexPositions();
    }

    public List<Integer> findMeshVerticesAtPosition(Vector3f position, float epsilon) {
        return vertexManager.findMeshVerticesAtPosition(position, epsilon);
    }

    /**
     * Set unrendered mode — when true, every face draws untextured in the
     * lighting's base colour (Blender solid-view style).
     */
    public void setForceUnrendered(boolean forceUnrendered) {
        this.forceUnrendered = forceUnrendered;
    }

    public ModelBounds getModelBounds() {
        if (cachedBounds == null) {
            cachedBounds = ModelBoundsCalculator.compute(vertexManager.getVertices());
        }
        return cachedBounds;
    }

    // =========================================================================
    // TRIANGLE & FACE QUERIES (delegates to faceMapper / topology)
    // =========================================================================

    public int getTriangleCount() {
        int[] indices = vertexManager.getIndices();
        return indices != null ? indices.length / 3 : 0;
    }

    public int[] getTriangleIndices() {
        int[] indices = vertexManager.getIndices();
        return indices != null ? indices.clone() : null;
    }

    public int getOriginalFaceIdForTriangle(int triangleIndex) {
        MeshTopology topology = rebuildPipeline.getTopology();
        if (topology != null) {
            return topology.getOriginalFaceIdForTriangle(triangleIndex);
        }
        return faceMapper.getOriginalFaceIdForTriangle(triangleIndex);
    }

    public int getOriginalFaceCount() {
        return faceMapper.getOriginalFaceCount();
    }

    public boolean hasTriangleToFaceMapping() {
        return faceMapper.hasMapping();
    }

    // =========================================================================
    // UNIQUE VERTEX MAPPING (delegates to uniqueMapper / topology)
    // =========================================================================

    public Vector3f getUniqueVertexPosition(int uniqueIndex) {
        return uniqueMapper.getUniqueVertexPosition(uniqueIndex, vertexManager.getVertices());
    }

    public int[] getMeshIndicesForUniqueVertex(int uniqueIndex) {
        MeshTopology topology = rebuildPipeline.getTopology();
        if (topology != null) {
            return topology.getMeshIndicesForUniqueVertex(uniqueIndex);
        }
        return uniqueMapper.getMeshIndicesForUniqueVertex(uniqueIndex);
    }

    public int getUniqueIndexForMeshVertex(int meshIndex) {
        MeshTopology topology = rebuildPipeline.getTopology();
        if (topology != null) {
            return topology.getUniqueIndexForMeshVertex(meshIndex);
        }
        return uniqueMapper.getUniqueIndexForMeshVertex(meshIndex);
    }

    public float[] getAllUniqueVertexPositions() {
        return uniqueMapper.getAllUniqueVertexPositions(vertexManager.getVertices());
    }

    public MeshTopology getTopology() {
        return rebuildPipeline.getTopology();
    }

    // =========================================================================
    // CHANGE NOTIFICATION (delegates to changeNotifier)
    // =========================================================================

    public void addMeshChangeListener(MeshChangeListener listener) {
        changeNotifier.addListener(listener);
    }

    public void removeMeshChangeListener(MeshChangeListener listener) {
        changeNotifier.removeListener(listener);
    }

    // =========================================================================
    // MESH MUTATIONS (delegates to MeshMutationCoordinator)
    // =========================================================================

    public int applyEdgeSubdivisionByPosition(Vector3f midpointPosition, Vector3f endpoint1, Vector3f endpoint2) {
        return mutationCoordinator.applyEdgeSubdivisionByPosition(midpointPosition, endpoint1, endpoint2);
    }

    public int subdivideEdgeAtParameter(int uniqueVertexA, int uniqueVertexB, float t) {
        return mutationCoordinator.subdivideEdgeAtParameter(uniqueVertexA, uniqueVertexB, t);
    }

    public boolean insertEdgeBetweenVertices(int uniqueVertexA, int uniqueVertexB) {
        return mutationCoordinator.insertEdgeBetweenVertices(uniqueVertexA, uniqueVertexB);
    }

    public boolean deleteFace(int faceId) {
        return mutationCoordinator.deleteFace(faceId);
    }

    public boolean createFaceFromVertices(int[] selectedUniqueVertices) {
        return mutationCoordinator.createFaceFromVertices(selectedUniqueVertices);
    }

    public boolean createFaceFromVertices(int[] selectedUniqueVertices, int activeMaterialId) {
        return mutationCoordinator.createFaceFromVertices(selectedUniqueVertices, activeMaterialId);
    }

    /**
     * Topologically merge vertices into {@code keepUniqueVertexId} — faces
     * genuinely share the kept vertex afterward (edges between merged
     * vertices collapse; degenerated faces are removed). Move the vertices
     * together first; this op does not touch positions.
     */
    public boolean mergeVertices(int keepUniqueVertexId, int[] mergedUniqueVertexIds) {
        return mutationCoordinator.mergeVertices(keepUniqueVertexId, mergedUniqueVertexIds);
    }

    /**
     * Scale the selected faces' vertices about a pivot ({@code null} = the
     * selection's area-weighted centroid). Vertices shared with unselected
     * faces move too (Blender face-scale semantics).
     */
    public boolean scaleFaces(int[] faceIds, float factor, Vector3f pivotOrNull) {
        return mutationCoordinator.scaleFaces(faceIds, factor, pivotOrNull);
    }

    /**
     * Inset each selected face individually (even-thickness border of quads;
     * the original face id keeps the inner cap).
     *
     * @return the new border-quad face ids, or {@code null} on failure
     */
    public int[] insetFaces(int[] faceIds, float amount) {
        return mutationCoordinator.insetFaces(faceIds, amount);
    }

    /**
     * Extrude each selected face individually along its normal (the original
     * face id keeps the moved cap).
     *
     * @return the new side-quad face ids, or {@code null} on failure
     */
    public int[] extrudeFaces(int[] faceIds, float offset) {
        return mutationCoordinator.extrudeFaces(faceIds, offset);
    }

    // =========================================================================
    // TEXTURE MANAGEMENT (delegates to TextureGPUOperations)
    // =========================================================================

    public void setTexture(int textureId) {
        textureOps.setTexture(textureId);
    }

    public int getTextureId() {
        return textureOps.getTextureId();
    }

    public void updateTextureRegion(int targetTextureId, int x, int y,
                                    int width, int height, byte[] rgbaBytes) {
        textureOps.updateTextureRegion(targetTextureId, x, y, width, height, rgbaBytes);
    }

    public byte[] readTexturePixels(int gpuTextureId) {
        return textureOps.readTexturePixels(gpuTextureId);
    }

    public int[] getTextureDimensions(int gpuTextureId) {
        return textureOps.getTextureDimensions(gpuTextureId);
    }

    public void setFaceMaterial(int faceId, int materialId) {
        textureOps.setFaceMaterial(faceId, materialId);
    }

    /**
     * Assign materials to several faces in a single pass, regenerating UVs and
     * re-uploading the mesh exactly once at the end.
     *
     * <p>This is the safe path for bulk per-face texturing: calling
     * {@link #setFaceMaterial(int, int)} N times runs the full-mesh UV
     * seam-duplication + GPU upload N times (O(N²) work that compounds
     * intermediate mesh state). Batching defers that to a single regeneration.
     *
     * @param faceIds    face identifiers
     * @param materialIds material id to assign to the face at the same index
     *                    (must be already registered via
     *                    {@link FaceTextureManager#registerMaterial})
     */
    public void setFaceMaterials(int[] faceIds, int[] materialIds) {
        if (faceIds == null || materialIds == null || faceIds.length != materialIds.length) {
            throw new IllegalArgumentException(
                    "faceIds and materialIds must be non-null and the same length");
        }
        if (faceIds.length == 0) {
            return;
        }
        for (int i = 0; i < faceIds.length; i++) {
            faceTextureManager.assignDefaultMapping(faceIds[i], materialIds[i]);
        }
        textureOps.regenerateUVsAndUpload();
        rebuildPipeline.markDrawBatchesDirty();
    }

    /**
     * Allocate a material id that is unique across both the engine-level counter
     * and any materials already registered on this model (e.g. loaded from disk
     * or created by another allocation path).
     *
     * <p>Single source of truth for material-id allocation — prevents two
     * independent allocators from handing out the same id, which would make
     * {@link FaceTextureManager#registerMaterial} silently overwrite an existing
     * material and orphan its GPU texture.
     */
    public int allocateMaterialId() {
        int maxExisting = 0;
        for (MaterialDefinition m : faceTextureManager.getAllMaterials()) {
            if (m.materialId() > maxExisting) {
                maxExisting = m.materialId();
            }
        }
        int id = materialIdCounter.getAndIncrement();
        if (id <= maxExisting) {
            id = maxExisting + 1;
            materialIdCounter.set(id + 1);
        }
        return id;
    }

    public FaceTextureManager getFaceTextureManager() {
        return faceTextureManager;
    }

    public void markDrawBatchesDirty() {
        rebuildPipeline.markDrawBatchesDirty();
    }

    // =========================================================================
    // PART MANAGEMENT
    // =========================================================================

    /**
     * Get the model part manager for multi-part model operations.
     *
     * @return The ModelPartManager instance
     */
    public ModelPartManager getPartManager() {
        return partManager;
    }

    /**
     * Assign a distinct default material to each face of a model part.
     * Each face gets its own 16x16 solid gray GPU texture and material,
     * ensuring per-face texture edits are fully isolated.
     *
     * @param part The part descriptor (must have a valid meshRange)
     */
    public void assignDefaultMaterialToPartFaces(
            com.openmason.engine.modeling.parts.ModelPartDescriptor part) {
        if (part == null || part.meshRange() == null) {
            logger.warn("Cannot assign default material: part or meshRange is null");
            return;
        }

        com.openmason.engine.modeling.parts.MeshRange range = part.meshRange();

        for (int faceId = range.faceStart(); faceId < range.faceStart() + range.faceCount(); faceId++) {
            int gpuTextureId = createBlankGpuTexture(16, 16);
            if (gpuTextureId <= 0) {
                logger.warn("Failed to create default texture for part '{}' face {}", part.name(), faceId);
                continue;
            }

            int materialId = allocateMaterialId();
            MaterialDefinition material = new MaterialDefinition(
                    materialId, "Face " + faceId, gpuTextureId,
                    MaterialDefinition.RenderLayer.OPAQUE,
                    MaterialDefinition.MaterialProperties.NONE
            );
            faceTextureManager.registerMaterial(material);
            faceTextureManager.assignDefaultMapping(faceId, materialId);
        }

        // Trigger UV refresh and draw batch rebuild
        rebuildPipeline.markDrawBatchesDirty();
        textureOps.regenerateUVsAndUpload();

        logger.info("Assigned per-face materials to part '{}' faces [{}-{})",
                part.name(), range.faceStart(), range.faceStart() + range.faceCount());
    }

    /**
     * Callback from ModelPartManager when the combined mesh buffer is rebuilt.
     * Bridges part-level changes into the existing serialization/render pipeline.
     */
    private void onPartMeshRebuilt(PartMeshRebuilder.RebuildResult result) {
        partMeshBridge.onPartMeshRebuilt(result);
    }

    /**
     * Load mesh data as a single root part via the ModelPartManager.
     * This is the preferred entry point for loading model geometry — it ensures
     * the part manager is the single source of truth for all mesh data.
     *
     * @param meshData  The mesh data to load
     * @param partName  Display name for the root part
     */
    public void loadMeshDataAsPart(OMOFormat.MeshData meshData, String partName) {
        if (meshData == null || !meshData.hasCustomGeometry()) {
            logger.debug("No custom mesh data to load as part");
            return;
        }

        // Clear stale face texture data from any previously loaded model.
        // This is the "load a fresh model" entry point — old per-face materials
        // must not carry over. (updateMeshGeometry intentionally preserves them
        // for part-level changes, so the clear must happen here.)
        faceTextureManager.clear();

        // Update UV mode before importing (so the rebuild callback uses the right mode)
        if (meshData.uvMode() != null) {
            try {
                stateManager.setUVMode(UVMode.valueOf(meshData.uvMode()));
            } catch (IllegalArgumentException e) {
                stateManager.setUVMode(UVMode.FLAT);
            }
        }

        // Build PartGeometry directly from MeshData — preserves the exact
        // triangle-to-face mapping from the .OMO file instead of regenerating it.
        PartMeshRebuilder.PartGeometry geo = PartMeshRebuilder.PartGeometry.of(
                meshData.vertices(),
                meshData.texCoords(),
                meshData.indices(),
                meshData.triangleToFaceId()
        );

        // Import as single part — clears existing parts, adds the root,
        // rebuilds the combined mesh, and pushes through the pipeline via onPartMeshRebuilt
        String name = partName != null ? partName : "Root";
        partManager.importAsSinglePartFromGeometry(name, geo);

        logger.info("Loaded mesh data as part '{}': {} vertices, {} triangles",
                name, meshData.getVertexCount(), meshData.getTriangleCount());
    }

    // =========================================================================
    // SERIALIZATION (delegates to MeshSerializationAdapter)
    // =========================================================================

    public void loadMeshData(OMOFormat.MeshData meshData) {
        serializationAdapter.loadMeshData(meshData);
    }

    public OMOFormat.MeshData toMeshData() {
        // Serialize the derived render mesh — always self-consistent: corners
        // are per-face by construction, so there are no seam-duplicate
        // vertices to strip and indices always stay within part MeshRanges.
        return serializationAdapter.toMeshData();
    }

    /**
     * @deprecated Array-based restore re-imports (and re-welds) the mesh; use
     *             {@link #captureSnapshot()} / {@link #restoreSnapshot} for an
     *             exact restore.
     */
    @Deprecated
    public void restoreFromSnapshot(float[] vertices, float[] texCoords, int[] indices,
                                     int[] triangleToFaceId,
                                     Map<Integer, FaceTextureMapping> faceMappings,
                                     Map<Integer, MaterialDefinition> materials) {
        serializationAdapter.restoreFromSnapshot(vertices, texCoords, indices,
            triangleToFaceId, faceMappings, materials);
    }

    /**
     * Exact snapshot of the authoritative mesh + per-face texture state —
     * the engine-side undo unit. Restoring never re-welds, so vertices
     * dragged within welding distance of each other survive an undo intact.
     */
    public com.openmason.engine.modeling.editable.EditableMeshSnapshot captureSnapshot() {
        Map<Integer, FaceTextureMapping> mappings = new java.util.LinkedHashMap<>();
        for (FaceTextureMapping m : faceTextureManager.getAllMappings()) {
            mappings.put(m.faceId(), m);
        }
        Map<Integer, MaterialDefinition> materials = new java.util.LinkedHashMap<>();
        for (MaterialDefinition md : faceTextureManager.getAllMaterials()) {
            materials.put(md.materialId(), md);
        }
        return com.openmason.engine.modeling.editable.EditableMeshSnapshot.capture(
            rebuildPipeline.getEditableMesh(), mappings, materials,
            rebuildPipeline.getImportSoupMapping());
    }

    /**
     * Restore a {@link #captureSnapshot()} exactly (mesh, textures, part
     * index mapping). Part GEOMETRY is not captured — same as the legacy
     * array-based restore — so undo commands that span part-structure changes
     * must keep using the part-driven paths.
     */
    public void restoreSnapshot(com.openmason.engine.modeling.editable.EditableMeshSnapshot snapshot) {
        faceTextureManager.clear();
        for (MaterialDefinition mat : snapshot.materials().values()) {
            if (mat.materialId() != MaterialDefinition.DEFAULT.materialId()) {
                faceTextureManager.registerMaterial(mat);
            }
        }
        for (FaceTextureMapping mapping : snapshot.faceMappings().values()) {
            faceTextureManager.setFaceMapping(mapping);
        }

        rebuildPipeline.setEditableMesh(snapshot.meshCopy());
        rebuildPipeline.setImportSoupMapping(snapshot.vertexIdToSoupIndices());
        rebuildPipeline.rebuildFromEditable();
    }

    public void refreshUVs() {
        serializationAdapter.refreshUVs();
    }

    // =========================================================================
    // DATA ACCESS & EXTRACTION
    // =========================================================================

    public float[] getTexCoords() {
        float[] texCoords = vertexManager.getTexCoords();
        return texCoords != null ? texCoords.clone() : null;
    }

    public int[] getIndices() {
        int[] indices = vertexManager.getIndices();
        return indices != null ? indices.clone() : null;
    }

    public int[] getTriangleToFaceMapping() {
        return faceMapper.getMappingCopy();
    }

    public FaceExtractor.FaceExtractionResult extractFaceData() {
        MeshTopology topology = rebuildPipeline.getTopology();
        if (topology != null) {
            return faceExtractor.extractFaceData(vertexManager.getVertices(), topology);
        }
        return faceExtractor.extractFaceData(
            vertexManager.getVertices(), vertexManager.getIndices(), faceMapper);
    }

    public float[][] extractFacePolygonFromUVs(int faceId) {
        float[] texCoords = vertexManager.getTexCoords();
        int[] indices = vertexManager.getIndices();
        if (texCoords == null || indices == null) {
            return null;
        }

        FaceTextureMapping mapping = faceTextureManager.getFaceMapping(faceId);
        if (mapping == null) {
            return null;
        }
        FaceTextureMapping.UVRegion uvRegion = mapping.uvRegion();

        // The render mesh emits each face's corners as a contiguous ascending
        // run in loop order, so the sorted unique corner set IS the polygon
        // boundary in winding order.
        java.util.TreeSet<Integer> cornerSet = new java.util.TreeSet<>();
        List<Integer> triangles = FaceTriangleQuery.findTrianglesForFace(faceId, indices, faceMapper);
        for (int triIdx : triangles) {
            cornerSet.add(indices[triIdx * 3]);
            cornerSet.add(indices[triIdx * 3 + 1]);
            cornerSet.add(indices[triIdx * 3 + 2]);
        }
        if (cornerSet.size() < 3) {
            return null;
        }
        Integer[] boundaryIndices = cornerSet.toArray(new Integer[0]);

        float regionW = uvRegion.width();
        float regionH = uvRegion.height();
        if (regionW < ModelingConstants.UV_REGION_MIN || regionH < ModelingConstants.UV_REGION_MIN) {
            return null;
        }

        float[] localX = new float[boundaryIndices.length];
        float[] localY = new float[boundaryIndices.length];
        for (int i = 0; i < boundaryIndices.length; i++) {
            int idx = boundaryIndices[i];
            float u = texCoords[idx * 2];
            float v = texCoords[idx * 2 + 1];
            localX[i] = Math.clamp((u - uvRegion.u0()) / regionW, 0.0f, 1.0f);
            localY[i] = Math.clamp((v - uvRegion.v0()) / regionH, 0.0f, 1.0f);
        }

        return new float[][]{localX, localY};
    }

    public float[] extractEdgePositions() {
        MeshTopology topology = rebuildPipeline.getTopology();
        if (topology != null) {
            return edgeExtractor.extractEdgePositions(vertexManager.getVertices(), topology);
        }
        return edgeExtractor.extractEdgePositions(
            vertexManager.getVertices(), vertexManager.getIndices(), faceMapper);
    }

    public int getFaceCount() {
        return faceMapper.getOriginalFaceCount();
    }

    public int getEdgeCount() {
        int upperBound = faceMapper.getFaceIdUpperBound();
        int totalEdges = 0;
        for (int i = 0; i < upperBound; i++) {
            totalEdges += faceMapper.getEdgeCountForFace(i);
        }
        return totalEdges;
    }

    public int getUniqueVertexCount() {
        return uniqueMapper.getUniqueVertexCount();
    }

    /**
     * Create a blank solid-gray GPU texture of the given dimensions.
     * Used for initializing per-face materials with a default texture.
     *
     * @param width  texture width in pixels
     * @param height texture height in pixels
     * @return OpenGL texture ID, or 0 on failure
     */
    private int createBlankGpuTexture(int width, int height) {
        try {
            int texId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texId);
            // Fill with solid gray (192,192,192,255)
            java.nio.ByteBuffer pixels = java.nio.ByteBuffer.allocateDirect(width * height * 4);
            for (int i = 0; i < width * height; i++) {
                pixels.put((byte) 192).put((byte) 192).put((byte) 192).put((byte) 255);
            }
            pixels.flip();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glBindTexture(GL_TEXTURE_2D, 0);
            return texId;
        } catch (Exception e) {
            logger.error("Failed to create blank GPU texture {}x{}", width, height, e);
            return 0;
        }
    }
}
