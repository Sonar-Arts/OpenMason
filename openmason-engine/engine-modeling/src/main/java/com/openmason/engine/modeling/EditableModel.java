package com.openmason.engine.modeling;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.openmason.engine.rendering.model.draw.MaterialTextures;
import com.openmason.engine.rendering.model.draw.ModelDrawPlan;
import com.openmason.engine.rendering.model.draw.ModelGpuMesh;
import com.openmason.engine.rendering.model.draw.ModelLighting;
import com.openmason.engine.rendering.model.draw.ModelRenderer;
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

    // Smooth shading (#169): one native store per part; corner normals derived per rebuild
    private final com.openmason.engine.modeling.shading.ModelShading shading;
    private boolean normalsUploaded;
    private short[] uploadedNormals;
    private ModelGpuMesh gpuMesh;
    private ModelDrawPlan drawPlan;
    private float[] uploadedUvs;
    private boolean initialized;

    // Scene-viewport preview of shape keys / vertex groups (#171 / #172, #175 INTEG): fed by the host's rigging data.
    private VertexDataPreviewSource vertexDataPreview;
    private final com.openmason.engine.rendering.model.draw.ModelAppearance appearance =
            new com.openmason.engine.rendering.model.draw.ModelAppearance();
    private com.openmason.engine.rig.RigMorphs cornerMorphs;          // on the GPU mesh, owned here
    private long morphsDataRevision = Long.MIN_VALUE;
    private com.openmason.engine.modeling.editable.RenderMesh morphsMesh;
    private long previewRevision = Long.MIN_VALUE;
    private com.openmason.engine.format.omo.MaskPlan maskPlan;         // over the uploaded index buffer, owned here
    private long maskDataRevision = Long.MIN_VALUE;
    private com.openmason.engine.modeling.editable.RenderMesh maskMesh;
    // The render corner -> combined-soup vertex map the preview data is translated through (per structure).
    private int[] cornerSource;
    private com.openmason.engine.modeling.editable.RenderMesh cornerSourceMesh;
    private int[][] cornerSourceMapping;
    // Triangle order of the uploaded index buffer (slot -> render triangle), built on request.
    private int[] uploadedTriangleKeys;
    private int[] uploadedMaskPermutation;
    private int[] uploadedTriangleOrder;

    // Part / bone transforms as GPU matrices (#175 F2): a per-corner skin bound at each structural rebuild and
    // a palette filled every draw, so a gizmo drag or a preview pose costs a palette upload, not a re-bake.
    private final com.openmason.engine.modeling.parts.PartPalette partPalette =
            new com.openmason.engine.modeling.parts.PartPalette();
    private final com.openmason.engine.rig.JointPalette palette = new com.openmason.engine.rig.JointPalette();

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

        // Shading stores follow every derivation and report edits as "normals dirty"
        this.shading = new com.openmason.engine.modeling.shading.ModelShading(
            rebuildPipeline::getLastRenderMesh, this::partSpans);
        this.shading.setChangeListener(gpuState::normalsChanged);
        this.rebuildPipeline.setDeriveHooks(shading::beforeDerive, shading::afterDerive);

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
        uploadedSkinVersion = -1;
        normalsUploaded = false;
        rebuildPipeline.markDrawBatchesDirty();
        uploadedUvs = null;
        initialized = true;
    }

    /** True once {@link #initialize()} created the GPU mesh. */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Releases the GPU mesh (GL thread) and the native derivation / preview data. The model's data stays usable (the
     * next structural edit or drag commit derives again).
     */
    public void cleanup() {
        if (gpuMesh != null) {
            gpuMesh.close();
            gpuMesh = null;
        }
        drawPlan = null;
        uploadedUvs = null;
        uploadedNormals = null;
        normalsUploaded = false;
        partPalette.invalidate();
        uploadedSkinVersion = -1;
        initialized = false;
        releasePreviewData();
        rebuildPipeline.releaseNative();
    }

    private void releasePreviewData() {
        if (cornerMorphs != null) {
            cornerMorphs.close();
            cornerMorphs = null;
        }
        if (maskPlan != null) {
            maskPlan.close();
            maskPlan = null;
        }
        morphsDataRevision = Long.MIN_VALUE;
        maskDataRevision = Long.MIN_VALUE;
        previewRevision = Long.MIN_VALUE;
        morphsMesh = null;
        maskMesh = null;
        uploadedTriangleKeys = null;
        uploadedMaskPermutation = null;
        uploadedTriangleOrder = null;
    }

    /**
     * Draws the model through the engine {@link ModelRenderer} lit pass. Part transforms are baked into the
     * vertices at each structural rebuild; every corner is GPU-skinned by its part's palette slot
     * ({@link com.openmason.engine.modeling.parts.PartPalette}): {@code modelMatrix} while the part sits where it was
     * baked, {@code modelMatrix · W · B⁻¹} while a gizmo drag moves it, posed skin matrices while a preview pose
     * deforms a skinned part. Faces without a material texture use the model's texture, or the lighting's base
     * colour when there is none; in unrendered mode every face uses the base colour.
     */
    public void render(ModelRenderer renderer, Matrix4f modelMatrix, Matrix4f view,
                       Matrix4f projection, ModelLighting lighting) {
        if (!initialized || renderer == null || vertexManager.getTotalVertexCount() == 0) {
            return;
        }
        syncGpu();
        fillPalette(modelMatrix);
        renderer.drawLit(gpuMesh, drawPlan, forceUnrendered ? MaterialTextures.UNTEXTURED : batchTextures,
                palette, view, projection, lighting, null, previewAppearance());
    }

    /** Draws the model as a see-through wireframe overlay in {@code color}. */
    public void renderWireframe(ModelRenderer renderer, Matrix4f modelMatrix, Matrix4f view,
                                Matrix4f projection, org.joml.Vector4f color) {
        if (!initialized || renderer == null || vertexManager.getTotalVertexCount() == 0) {
            return;
        }
        syncGpu();
        fillPalette(modelMatrix);
        renderer.drawWireframe(gpuMesh, drawPlan, palette, view, projection, color, previewAppearance());
    }

    private void fillPalette(Matrix4f modelMatrix) {
        partPalette.fill(partManager, modelMatrix, palette);
    }

    /**
     * GL-free: brings the per-corner skin up to date and fills the joint palette the next draw would use with
     * {@code modelMatrix} (tests, diagnostics). The returned palette is reused by the next call.
     */
    public com.openmason.engine.rig.JointPalette computePalette(Matrix4f modelMatrix) {
        // GL-free callers never clear the GPU dirty state: a new derivation (a new render mesh) is the signal.
        if (rebuildPipeline.getLastRenderMesh() != paletteMesh) {
            partPalette.invalidate();
        }
        refreshPartSkin();
        fillPalette(modelMatrix);
        return palette;
    }

    /** The GPU mesh (null before {@link #initialize()}): diagnostics and GL tests read its skin attributes back. */
    public ModelGpuMesh getGpuMesh() {
        return gpuMesh;
    }

    /** The per-corner skin the GPU draws with (null before the first sync / {@link #computePalette}). */
    public com.openmason.engine.rig.PackedSkin getCornerSkin() {
        return partPalette.packed();
    }

    /**
     * The corner positions as the GPU draws them with {@link #computePalette}'s palette (CPU twin of the vertex
     * shader's skinning; tests and diagnostics).
     */
    public float[] getDisplayedCornerPositions(Matrix4f modelMatrix) {
        com.openmason.engine.rig.JointPalette p = computePalette(modelMatrix);
        float[] baked = vertexManager.getVertices();
        return partPalette.deform(baked != null ? baked : new float[0], p);
    }

    /**
     * True while the drawn model differs from its baked vertices (a part / bone gizmo drag, a preview pose): the
     * CPU-side element overlays and picking still show the baked positions, so viewports hide them meanwhile.
     */
    public boolean isDisplayDeformed() {
        return partManager.isTransformInteractionActive() || partPalette.lastFillDeformed(partManager);
    }

    /** Rebinds corners to palette slots when the structure or the skins changed; returns the skin to upload. */
    private com.openmason.engine.rig.PackedSkin refreshPartSkin() {
        paletteMesh = rebuildPipeline.getLastRenderMesh();
        return partPalette.refresh(paletteMesh, rebuildPipeline.getImportSoupMapping(), partManager);
    }

    // The render mesh the corner bindings were last refreshed against, and the binding version on the GPU.
    private com.openmason.engine.modeling.editable.RenderMesh paletteMesh;
    private long uploadedSkinVersion = -1;

    /**
     * Brings the GPU mesh up to date with the latest derivation: positions only
     * for a vertex drag (only the touched corner ranges when the live derivation reported them, #175 INTEG), UVs only
     * when they actually changed, the material-sorted index buffer when triangles or face materials changed, the
     * corner normals' changed range, and the shape-key / vertex-group preview data when it changed.
     */
    private void syncGpu() {
        long span = com.openmason.engine.modeling.core.EditCostStats.begin();
        try {
            syncGpuNow();
        } finally {
            com.openmason.engine.modeling.core.EditCostStats.end(
                com.openmason.engine.modeling.core.EditCostStats.Span.GPU_SYNC, span);
        }
    }

    private void syncGpuNow() {
        if (gpuState.positionsDirty()) {
            float[] positions = vertexManager.getVertices();
            int[] spans = gpuState.positionSpans();
            if (spans != null && positions != null && gpuMesh.vertexCount() * 3 == positions.length) {
                for (int i = 0; i < spans.length; i += 2) {
                    gpuMesh.updatePositionRange(positions, spans[i], spans[i + 1]);
                }
            } else {
                gpuMesh.updatePositions(positions);
            }
        }
        if (gpuState.uvsDirty()) {
            float[] uvs = vertexManager.getTexCoords();
            int[] spans = gpuState.uvSpans();
            if (spans != null && uvs != null && uploadedUvs != null && uploadedUvs.length == uvs.length) {
                for (int i = 0; i < spans.length; i += 2) {
                    gpuMesh.updateUvRange(uvs, spans[i], spans[i + 1]);
                    int from = spans[i] * 2;
                    int count = Math.min(spans[i + 1] * 2, uvs.length - from);
                    if (count > 0) System.arraycopy(uvs, from, uploadedUvs, from, count);
                }
            } else if (uploadedUvs == null || !Arrays.equals(uvs, uploadedUvs)) {
                gpuMesh.updateUvs(uvs);
                uploadedUvs = uvs != null ? uvs.clone() : null;
            }
        }
        if (gpuState.indicesDirty() || drawPlan == null) {
            partPalette.invalidate();
        }
        refreshPartSkin();
        com.openmason.engine.rig.PackedSkin skin = partPalette.packed();
        if (skin != null && partPalette.version() != uploadedSkinVersion) {
            uploadedSkinVersion = partPalette.version();
            // A skin that does not cover every uploaded corner must not be bound (the range attribute would be
            // read past its end); without one the palette's slot 0 (the model matrix) draws the whole mesh.
            boolean covers = skin.vertexCount() > 0 && skin.vertexCount() == vertexManager.getTotalVertexCount();
            gpuMesh.updateSkin(covers ? skin : null);
        }
        syncMorphs();
        if (gpuState.indicesDirty() || rebuildPipeline.isDrawBatchesDirty() || drawPlan == null || masksStale()) {
            uploadSortedIndices();
            rebuildPipeline.clearDrawBatchesDirty();
        }
        syncPreviewChoices();
        refreshBaseColors();
        if (gpuState.normalsDirty()) {
            syncNormals();
        }
        gpuState.clear();
    }

    /** Corner normals (smooth shading): only the changed range is re-sent; null = every corner flat. */
    private void syncNormals() {
        short[] normals = shading.cornerNormals();
        if (normals != null && normalsUploaded && uploadedNormals != null
                && uploadedNormals.length == normals.length) {
            int lo = 0;
            int hi = normals.length;
            while (lo < hi && normals[lo] == uploadedNormals[lo]) lo++;
            if (lo == hi) return;
            while (hi > lo && normals[hi - 1] == uploadedNormals[hi - 1]) hi--;
            int first = lo / 2;
            int end = (hi + 1) / 2;
            gpuMesh.updateNormalsRange(normals, first, end - first);
            System.arraycopy(normals, first * 2, uploadedNormals, first * 2, (end - first) * 2);
            return;
        }
        if (normals != null || normalsUploaded) {
            gpuMesh.updateNormals(normals);
            normalsUploaded = normals != null;
            uploadedNormals = normals != null ? normals.clone() : null;
        }
    }

    /**
     * Re-sorts the triangles by material into the GPU index buffer; with vertex groups to preview, a mask plan is
     * built over that buffer (triangles keep their material run, so the batches stay valid) and its reordered
     * indices are uploaded instead.
     */
    private void uploadSortedIndices() {
        int[] indices = vertexManager.getIndices();
        int[] keys = triangleMaterialKeys();
        ModelDrawPlan.Sorted sorted = ModelDrawPlan.materialSorted(indices, keys);
        int[] upload = sorted.indices();
        ModelDrawPlan plan = sorted.plan();
        com.openmason.engine.format.omo.MaskPlan nextMask = buildMaskPlan(upload, keys);
        int[] permutation = null;
        if (nextMask != null) {
            upload = nextMask.indices();
            permutation = nextMask.permutation();
            plan = plan.withMaskPlan(nextMask);
        }
        gpuMesh.updateIndices(upload);
        drawPlan = plan;
        if (maskPlan != null && maskPlan != nextMask) {
            maskPlan.close();
        }
        maskPlan = nextMask;
        uploadedTriangleKeys = keys;
        uploadedMaskPermutation = permutation;
        uploadedTriangleOrder = null;
    }

    // =========================================================================
    // SHAPE KEY / VERTEX GROUP PREVIEW (#171 / #172 in the scene viewport, #175 INTEG)
    // =========================================================================

    /**
     * Feeds the scene viewport's preview of shape keys and vertex groups (null = none): the draw then deforms the
     * mesh by the preview weights on the GPU (morph targets mapped from the combined soup onto the render corners)
     * and leaves out the triangles of hidden groups (a mask plan over the uploaded index buffer). The overlays and the
     * CPU picking stay on the undeformed, unmasked mesh (Blender's edit cage).
     */
    public void setVertexDataPreview(VertexDataPreviewSource source) {
        if (source == vertexDataPreview) return;
        this.vertexDataPreview = source;
        morphsDataRevision = Long.MIN_VALUE;
        maskDataRevision = Long.MIN_VALUE;
        previewRevision = Long.MIN_VALUE;
        rebuildPipeline.markDrawBatchesDirty();
    }

    /** The preview source (null = none). */
    public VertexDataPreviewSource getVertexDataPreview() {
        return vertexDataPreview;
    }

    /** The appearance the lit / wireframe passes draw with (null without a preview source). */
    private com.openmason.engine.rendering.model.draw.ModelAppearance previewAppearance() {
        return vertexDataPreview != null ? appearance : null;
    }

    /** The preview appearance (morph weights, hidden groups) of the last sync - tests and diagnostics. */
    public com.openmason.engine.rendering.model.draw.ModelAppearance getPreviewAppearance() {
        return appearance;
    }

    /** The draw plan of the last sync (null before one) - tests and diagnostics. */
    public ModelDrawPlan getDrawPlan() {
        return drawPlan;
    }

    /**
     * The triangle order of the GPU index buffer ({@code order[slot]} = the render triangle - {@link #getIndices()}
     * order - drawn at triangle slot {@code slot}, i.e. what {@code gl_PrimitiveID} indexes): the material sort, then
     * the vertex-group mask plan's reorder when groups are previewed. Null before the first sync. GPU picking maps
     * primitive ids back through it.
     */
    public int[] getUploadedTriangleOrder() {
        if (uploadedTriangleOrder == null && uploadedTriangleKeys != null) {
            int[] order = ModelDrawPlan.materialSortOrder(uploadedTriangleKeys, uploadedTriangleKeys.length);
            if (uploadedMaskPermutation != null) {
                int[] masked = new int[uploadedMaskPermutation.length];
                for (int i = 0; i < masked.length; i++) masked[i] = order[uploadedMaskPermutation[i]];
                order = masked;
            }
            uploadedTriangleOrder = order;
        }
        return uploadedTriangleOrder;
    }

    /** True when the vertex-group data or the structure changed since the mask plan was built. */
    private boolean masksStale() {
        if (vertexDataPreview == null) {
            return maskPlan != null;
        }
        return vertexDataPreview.dataRevision() != maskDataRevision
            || rebuildPipeline.getLastRenderMesh() != maskMesh;
    }

    /** A mask plan over {@code sortedIndices} for the preview's vertex groups, or null (no source / no groups). */
    private com.openmason.engine.format.omo.MaskPlan buildMaskPlan(int[] sortedIndices, int[] keys) {
        VertexDataPreviewSource source = vertexDataPreview;
        maskMesh = rebuildPipeline.getLastRenderMesh();
        maskDataRevision = source != null ? source.dataRevision() : Long.MIN_VALUE;
        if (source == null || sortedIndices.length == 0
                || !com.openmason.engine.format.omo.MaskPlan.isAvailable()) {
            return null;
        }
        VertexDataPreviewSource.SoupGroups groups = source.soupGroups();
        if (groups == null || groups.names().isEmpty()) {
            return null;
        }
        int corners = vertexManager.getTotalVertexCount();
        int[] src = cornerSource();
        if (src == null) {
            return null;
        }
        // Soup vertex -> its corners (CSR), then every group row onto those corners.
        int soupCount = 0;
        for (int s : src) soupCount = Math.max(soupCount, s + 1);
        int[] start = new int[soupCount + 1];
        for (int s : src) if (s >= 0) start[s + 1]++;
        for (int i = 0; i < soupCount; i++) start[i + 1] += start[i];
        int[] fill = Arrays.copyOf(start, soupCount);
        int[] cornersOf = new int[start[soupCount]];
        for (int c = 0; c < src.length; c++) if (src[c] >= 0) cornersOf[fill[src[c]]++] = c;
        int groupCount = groups.names().size();
        int[] rowOffsets = new int[groupCount + 1];
        int[] rowVertices = new int[64];
        float[] rowWeights = new float[64];
        int rows = 0;
        for (int g = 0; g < groupCount; g++) {
            for (int r = groups.rowOffsets()[g]; r < groups.rowOffsets()[g + 1]; r++) {
                int s = groups.vertices()[r];
                if (s < 0 || s >= soupCount) continue;
                for (int k = start[s]; k < start[s + 1]; k++) {
                    if (rows == rowVertices.length) {
                        rowVertices = Arrays.copyOf(rowVertices, rows * 2);
                        rowWeights = Arrays.copyOf(rowWeights, rows * 2);
                    }
                    rowVertices[rows] = cornersOf[k];
                    rowWeights[rows++] = groups.weights() == null ? 1f : groups.weights()[r];
                }
            }
            rowOffsets[g + 1] = rows;
        }
        // Key of each sorted triangle = its material run (the mask plan never moves a triangle out of its run).
        int[] order = ModelDrawPlan.materialSortOrder(keys, keys.length);
        int[] slotKeys = new int[order.length];
        for (int t = 0; t < order.length; t++) slotKeys[t] = keys[order[t]];
        try {
            return com.openmason.engine.format.omo.MaskPlan.create(sortedIndices, corners, slotKeys, groups.names(),
                    rowOffsets, Arrays.copyOf(rowVertices, rows), Arrays.copyOf(rowWeights, rows));
        } catch (RuntimeException e) {
            logger.warn("Vertex-group preview unavailable: {}", e.getMessage());
            return null;
        }
    }

    /** Uploads the shape keys as corner morph targets when the data or the structure changed. */
    private void syncMorphs() {
        VertexDataPreviewSource source = vertexDataPreview;
        if (source == null) {
            if (cornerMorphs != null) {
                gpuMesh.updateMorphs(null);
                cornerMorphs.close();
                cornerMorphs = null;
            }
            return;
        }
        long revision = source.dataRevision();
        com.openmason.engine.modeling.editable.RenderMesh rm = rebuildPipeline.getLastRenderMesh();
        if (revision == morphsDataRevision && rm == morphsMesh
                && (cornerMorphs == null || cornerMorphs.vertexCount() == gpuMesh.vertexCount())) {
            return;
        }
        morphsDataRevision = revision;
        morphsMesh = rm;
        previewRevision = Long.MIN_VALUE; // the weights follow the targets
        com.openmason.engine.rig.RigMorphs next = null;
        int[] src = cornerSource();
        if (src != null && src.length == gpuMesh.vertexCount()) {
            try (com.openmason.engine.rig.RigMorphs soup = source.soupMorphs()) {
                if (soup != null && soup.targetCount() > 0) {
                    int[] clamped = new int[src.length];
                    for (int c = 0; c < src.length; c++) clamped[c] = src[c] < soup.vertexCount() ? src[c] : -1;
                    next = soup.remap(src.length, clamped);
                }
            } catch (RuntimeException e) {
                logger.warn("Shape-key preview unavailable: {}", e.getMessage());
                next = null;
            }
        }
        gpuMesh.updateMorphs(next);
        if (cornerMorphs != null) cornerMorphs.close();
        cornerMorphs = next;
    }

    /** Copies the preview choices (slider weights, hidden groups) into the appearance when they changed. */
    private void syncPreviewChoices() {
        VertexDataPreviewSource source = vertexDataPreview;
        if (source == null) return;
        long revision = source.previewRevision();
        if (revision == previewRevision) return;
        previewRevision = revision;
        float[] weights = source.previewWeights();
        appearance.setMorphWeights(cornerMorphs != null && weights != null
                && weights.length == cornerMorphs.targetCount() ? weights : null);
        appearance.setHiddenGroups(source.hiddenGroups());
    }

    /**
     * Render corner -> the combined-soup vertex its preview data comes from (-1 = none, e.g. a vertex created by an
     * edit after the import): the corner's welded vertex's soup vertex inside the part that owns the corner's face
     * (welding joins seams across parts; each corner keeps its own part's data). Cached per structure.
     */
    private int[] cornerSource() {
        com.openmason.engine.modeling.editable.RenderMesh rm = rebuildPipeline.getLastRenderMesh();
        int[][] vertexToSoup = rebuildPipeline.getImportSoupMapping();
        if (rm == null || vertexToSoup == null) {
            return null;
        }
        if (rm == cornerSourceMesh && vertexToSoup == cornerSourceMapping && cornerSource != null) {
            return cornerSource;
        }
        int corners = rm.cornerCount();
        int[] cornerFace = new int[corners];
        Arrays.fill(cornerFace, -1);
        int[] idx = rm.indices();
        int[] triToFace = rm.triangleToFaceId();
        for (int t = 0; t < triToFace.length; t++) {
            for (int k = 0; k < 3; k++) {
                int c = idx[t * 3 + k];
                if (c >= 0 && c < corners && cornerFace[c] < 0) cornerFace[c] = triToFace[t];
            }
        }
        // Part ranges by face start (the owner of a face: the greatest faceStart <= face).
        List<com.openmason.engine.modeling.parts.MeshRange> ranges = new java.util.ArrayList<>();
        for (com.openmason.engine.modeling.parts.ModelPartDescriptor part : partManager.getAllParts()) {
            if (part.meshRange() != null && part.meshRange().vertexCount() > 0) ranges.add(part.meshRange());
        }
        ranges.sort(java.util.Comparator.comparingInt(com.openmason.engine.modeling.parts.MeshRange::faceStart));
        int[] faceStarts = new int[ranges.size()];
        for (int i = 0; i < faceStarts.length; i++) faceStarts[i] = ranges.get(i).faceStart();
        int[] src = new int[corners];
        int[] cornerToVertex = rm.cornerToVertexId();
        for (int c = 0; c < corners; c++) {
            int v = cornerToVertex[c];
            int[] soups = v >= 0 && v < vertexToSoup.length ? vertexToSoup[v] : null;
            if (soups == null || soups.length == 0) {
                src[c] = -1;
                continue;
            }
            int pick = soups[0];
            int f = cornerFace[c];
            if (f >= 0 && soups.length > 1 && faceStarts.length > 0) {
                int r = Arrays.binarySearch(faceStarts, f);
                if (r < 0) r = -r - 2;
                if (r >= 0) {
                    com.openmason.engine.modeling.parts.MeshRange range = ranges.get(r);
                    for (int s : soups) {
                        if (range.containsVertex(s)) {
                            pick = s;
                            break;
                        }
                    }
                }
            }
            src[c] = pick;
        }
        cornerSource = src;
        cornerSourceMesh = rm;
        cornerSourceMapping = vertexToSoup;
        return src;
    }

    /** The parts' face ranges in the render mesh (shading store ownership). */
    private List<com.openmason.engine.modeling.shading.ModelShading.PartSpan> partSpans() {
        if (partManager == null) {
            return List.of();
        }
        List<com.openmason.engine.modeling.shading.ModelShading.PartSpan> out = new java.util.ArrayList<>();
        for (com.openmason.engine.modeling.parts.ModelPartDescriptor part : partManager.getAllParts()) {
            com.openmason.engine.modeling.parts.MeshRange r = part.meshRange();
            if (r != null) {
                out.add(new com.openmason.engine.modeling.shading.ModelShading.PartSpan(
                    part.id(), r.faceStart(), r.faceCount()));
            }
        }
        return out;
    }

    /**
     * The model's smooth shading (#169): Shade Smooth / Flat / Auto Smooth, sharp edges, custom normals, OMO 1.9
     * load / save. Edits through it mark the GPU normals dirty; undo snapshots capture it.
     */
    public com.openmason.engine.modeling.shading.ModelShading getShading() {
        return shading;
    }

    /**
     * The corner normals the GPU draws with (octahedral snorm16 pairs per render corner, the FLAT marker for flat
     * corners), or null when nothing is smooth. GL-free (tests, diagnostics).
     */
    public short[] getCornerNormals() {
        return shading.cornerNormals();
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

    // Batch key (a material's texture id) → its OMO 1.9 base colour, refreshed every sync (materials are few).
    private final Map<Integer, float[]> baseColorByTexture = new java.util.HashMap<>();

    /** The lit pass's textures: texture × base colour, the runtime rule (MaterialTextures.baseColorFor). */
    private final MaterialTextures batchTextures = new MaterialTextures() {
        @Override
        public int textureFor(int materialId) {
            return textureForBatch(materialId);
        }

        @Override
        public float[] baseColorFor(int materialId) {
            return materialId > 0 ? baseColorByTexture.get(materialId) : null;
        }
    };

    private void refreshBaseColors() {
        baseColorByTexture.clear();
        for (MaterialDefinition m : faceTextureManager.getAllMaterials()) {
            float[] base = m.properties().baseColor();
            if (base != null && m.textureId() > 0) {
                baseColorByTexture.put(m.textureId(), base);
            }
        }
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

    /**
     * Positions-only move of several vertices (editable vertex ids = unique vertex indices) with one batched
     * listener notification: the interactive drag path (#175). Call {@link #commitVertexPositions()} when the
     * interaction ends so projected UVs, triangulation and topology follow.
     */
    public void moveVertices(int[] uniqueVertexIds, float[] positions) {
        mutationCoordinator.moveVertices(uniqueVertexIds, positions);
    }

    /**
     * Coalesces the mesh derivations of a multi-step load (parts, face textures, UV refresh) into one, run by
     * {@link #endDeferredRebuild()} (#175). Inside the bracket only edit parts / face textures; derived data
     * (render mesh, topology, overlays) is stale until the end. Always pair in try/finally.
     */
    public void beginDeferredRebuild() {
        rebuildPipeline.beginDeferredDerive();
    }

    /** Ends {@link #beginDeferredRebuild()}: the outermost end runs the one pending derivation. */
    public void endDeferredRebuild() {
        rebuildPipeline.endDeferredDerive();
    }

    /** Ends an interactive position edit ({@link #moveVertices}): one position refresh. */
    public void commitVertexPositions() {
        mutationCoordinator.commitVertexPositions();
    }

    /**
     * The live triangle index array (render corners, 3 per triangle) WITHOUT a copy - read only, valid until the
     * next derivation. For per-frame readers that must not copy the whole index buffer.
     */
    public int[] triangleIndicesView() {
        return vertexManager.getIndices();
    }

    /** The live corner position array ({@code x,y,z} per render corner) WITHOUT a copy - read only. */
    public float[] meshVertexPositionsView() {
        return vertexManager.getVertices();
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
        shading.clear();

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
        shading.clear();
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
            rebuildPipeline.getImportSoupMapping(),
            com.openmason.engine.modeling.shading.ModelShading.isAvailable() ? shading.capture() : null);
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
        shading.restore(snapshot.shading());
    }

    public void refreshUVs() {
        serializationAdapter.refreshUVs();
    }

    /**
     * After a model's face mappings were restored from a file: every mapped face whose loaded corner UVs are not
     * the projection into its region (an imported or hand-authored UV layout) keeps them
     * ({@link FaceTextureMapping#authoredUVs()}); faces whose UVs ARE the projection (what the texture editor
     * saves) stay projected, so they keep following later geometry and region edits exactly as before. Call
     * before {@link #refreshUVs()}.
     *
     * @return the number of faces that keep their authored UVs
     */
    public int adoptAuthoredUVs() {
        com.openmason.engine.modeling.editable.EditableMesh mesh = rebuildPipeline.getEditableMesh();
        List<com.openmason.engine.modeling.editable.EditableFace> candidates = new java.util.ArrayList<>();
        List<FaceTextureMapping> mappings = new java.util.ArrayList<>();
        for (com.openmason.engine.modeling.editable.EditableFace face : mesh.faces()) {
            if (!face.hasAuthoredUVs()) continue;
            FaceTextureMapping mapping = faceTextureManager.getFaceMapping(face.faceId());
            if (mapping == null || mapping.authoredUVs()
                    || !com.openmason.engine.modeling.editable.RenderMeshBuilder.isExplicit(mapping)) {
                continue;
            }
            candidates.add(face);
            mappings.add(mapping);
        }
        // Every candidate's projection in one native derivation (#175 INTEG), the per-face rule unchanged.
        boolean[] matches = com.openmason.engine.modeling.editable.RenderMeshBuilder.authoredUVsMatchProjection(
                mesh, candidates, mappings, AUTHORED_UV_TOLERANCE);
        int adopted = 0;
        for (int i = 0; i < candidates.size(); i++) {
            if (matches[i]) continue;
            faceTextureManager.setFaceMapping(mappings.get(i).withAuthoredUVs(true));
            adopted++;
        }
        if (adopted > 0) {
            logger.debug("{} face(s) keep their authored UVs", adopted);
        }
        return adopted;
    }

    /** UV distance under which loaded UVs count as the region projection (about a texel of a 4096 texture). */
    private static final float AUTHORED_UV_TOLERANCE = 2.5e-4f;

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
