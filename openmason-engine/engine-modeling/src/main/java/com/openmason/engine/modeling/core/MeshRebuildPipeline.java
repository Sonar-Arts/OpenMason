package com.openmason.engine.modeling.core;

import com.openmason.engine.modeling.editable.CendaMesh;
import com.openmason.engine.modeling.editable.EditableMesh;
import com.openmason.engine.modeling.editable.MeshImporter;
import com.openmason.engine.modeling.editable.RenderMesh;
import com.openmason.engine.modeling.editable.RenderMeshBuilder;
import com.openmason.engine.modeling.mapping.EditableMeshVertexMapper;
import com.openmason.engine.modeling.mapping.ITriangleFaceMapper;
import com.openmason.engine.modeling.notification.IMeshChangeNotifier;
import com.openmason.engine.modeling.topology.MeshTopology;
import com.openmason.engine.modeling.uv.IFaceTextureManager;
import com.openmason.engine.modeling.uv.IVertexDataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owner of the authoritative {@link EditableMesh} and the shared post-mutation
 * rebuild sequence. Every structural or positional edit funnels through
 * {@link #rebuildFromEditable()} / {@link #refreshPositionsFromEditable()}:
 *
 * <p>Sequence: derive render mesh (corners + projected UVs + triangulation) →
 * write the render-facing caches (vertex manager, face mapper, corner maps) →
 * rebuild topology from authoritative loops → mark the GPU copy stale →
 * invalidate caches → notify listeners. The GPU copy itself syncs on the
 * next draw (GL thread), so several edits in one frame upload once.
 *
 * <p>UVs are produced during derivation ({@link RenderMeshBuilder}), so there
 * is no separate UV-regeneration re-entry and no seam-duplication pass —
 * corners are per-face by construction.
 *
 * <p><b>Native pipeline (#175 INTEG):</b> a derivation is ONE native pass ({@link RenderMeshBuilder#derive}: render
 * mesh + topology) whose {@link RenderMeshBuilder.Derivation} stays alive. An import (load, part re-bake) hands its
 * native mesh over ({@link #setImportedMesh}), so the soup is imported once and derived once. Positions-only moves
 * ({@link #movePositions}, the drag path) refresh that derivation in place: corner positions, projected UVs,
 * re-triangulated faces and the topology's face geometry follow every drag frame in O(moved corners), the GPU sync
 * uploads only the touched corner ranges, and {@link #commitPositions} on release costs nothing unless a face
 * re-triangulated. Structural edits, authored-UV and mapping changes still derive again. The derivation's native
 * mesh is closed when it is replaced and by {@link #releaseNative()}.
 */
public class MeshRebuildPipeline implements IMeshRebuildPipeline {

    private static final Logger logger = LoggerFactory.getLogger(MeshRebuildPipeline.class);

    private final IVertexDataManager vertexManager;
    private final EditableMeshVertexMapper vertexMapper;
    private final ITriangleFaceMapper faceMapper;
    private final IMeshChangeNotifier changeNotifier;
    private final IMeshGpuSync gpuSync;

    // UV region/rotation source for render-mesh derivation — set by EditableModel after
    // the FaceTextureManager is constructed.
    private IFaceTextureManager faceTextureManager;

    // Authoritative mesh + latest derivation
    private EditableMesh editableMesh = new EditableMesh();
    private RenderMesh lastRenderMesh;

    // The live native derivation behind lastRenderMesh / topology (#175 INTEG); current = it matches editableMesh.
    private RenderMeshBuilder.Derivation derivation;
    private boolean derivationCurrent;
    // Structure the derivation was made from (a cheap guard against a mesh edited without a rebuild).
    private int derivedVertices;
    private int derivedFaces;
    // An import's native mesh waiting for the next derive (consumed by it), with the mesh it was imported into.
    private CendaMesh pendingImport;
    private EditableMesh pendingImportMesh;
    private int pendingImportFaces;
    private int pendingImportVertices;
    // Moves since the last commit: any, ones that re-triangulated a face, ones the derivation could not take.
    private boolean movedSinceCommit;
    private boolean trianglesMovedSinceCommit;
    private boolean positionsPending;

    // ALL combined-soup vertex indices per editable vertex id, from the last
    // import — the part system's MeshRange index space. Welding crosses part
    // boundaries, so one editable vertex can map into several parts' ranges.
    // Vertices created by ops after the import have no soup indices.
    private int[][] vertexIdToSoupIndices;

    private static final int[] NO_SOUP_INDICES = new int[0];

    // Shared mutable state
    private MeshTopology topology;
    private boolean drawBatchesDirty = true;

    // Cached model bounds — nulled on geometry changes, recomputed lazily by EditableModel
    private Runnable boundsInvalidator;

    // Shading stores follow every derivation (#169): before = catch up with the outgoing mesh, after = follow
    // the new one (moved positions or a new topology).
    private Runnable beforeDerive = () -> { };
    private Runnable afterDerive = () -> { };

    public MeshRebuildPipeline(
            IVertexDataManager vertexManager,
            EditableMeshVertexMapper vertexMapper,
            ITriangleFaceMapper faceMapper,
            IMeshChangeNotifier changeNotifier,
            IMeshGpuSync gpuSync) {
        this.vertexManager = vertexManager;
        this.vertexMapper = vertexMapper;
        this.faceMapper = faceMapper;
        this.changeNotifier = changeNotifier;
        this.gpuSync = gpuSync;
    }

    /** Set the per-face UV mapping source used during render-mesh derivation. */
    public void setFaceTextureManager(IFaceTextureManager faceTextureManager) {
        this.faceTextureManager = faceTextureManager;
    }

    /**
     * Set the bounds invalidation callback.
     * Called during rebuild to clear cached model bounds.
     */
    public void setBoundsInvalidator(Runnable boundsInvalidator) {
        this.boundsInvalidator = boundsInvalidator;
    }

    /** Hooks run around every derivation (the shading stores keep their keys in step with the mesh). */
    public void setDeriveHooks(Runnable before, Runnable after) {
        this.beforeDerive = before != null ? before : () -> { };
        this.afterDerive = after != null ? after : () -> { };
    }

    // ── Editable mesh ownership ─────────────────────────────────────────────

    /** The authoritative mesh. Never null (empty mesh before any load). */
    public EditableMesh getEditableMesh() {
        return editableMesh;
    }

    /** Replace the authoritative mesh (import/load/snapshot-restore path). */
    public void setEditableMesh(EditableMesh mesh) {
        this.editableMesh = mesh != null ? mesh : new EditableMesh();
        this.vertexIdToSoupIndices = null;
        dropPendingImport();
        releaseDerivation();
    }

    /**
     * Replace the authoritative mesh with a fresh import and keep the import's native mesh for the next derivation
     * (one import + one derive, #175 INTEG). Takes ownership of {@code imported}'s native mesh.
     */
    public void setImportedMesh(MeshImporter.Imported imported) {
        setEditableMesh(imported.result().mesh());
        setImportSoupMapping(imported.result().vertexIdToSoupIndices());
        if (imported.nativeMesh() != null) {
            pendingImport = imported.nativeMesh();
            pendingImportMesh = editableMesh;
            pendingImportFaces = editableMesh.faceCount();
            pendingImportVertices = editableMesh.vertexCount();
        }
    }

    /**
     * Frees the native meshes (the live derivation, a pending import). The derived Java data stays readable; the next
     * move falls back to the full refresh on commit and the next derivation starts a new native mesh. Safe to call
     * twice (model cleanup / dispose).
     */
    public void releaseNative() {
        dropPendingImport();
        releaseDerivation();
    }

    private void dropPendingImport() {
        if (pendingImport != null) {
            pendingImport.close();
            pendingImport = null;
            pendingImportMesh = null;
        }
    }

    private void releaseDerivation() {
        if (derivation != null) {
            derivation.close();
            derivation = null;
        }
        derivationCurrent = false;
    }

    /** True while moves refresh the live derivation incrementally (diagnostics and tests). */
    public boolean isDerivationLive() {
        return derivation != null && derivationCurrent && !deferredPending
            && derivedVertices == editableMesh.vertexCount() && derivedFaces == editableMesh.faceCount();
    }

    /** Record the import's vertexId→soup-indices mapping (part index space). */
    public void setImportSoupMapping(int[][] vertexIdToSoupIndices) {
        this.vertexIdToSoupIndices = vertexIdToSoupIndices;
    }

    /** The import's vertexId→soup-indices mapping, or null (snapshot support). */
    public int[][] getImportSoupMapping() {
        return vertexIdToSoupIndices;
    }

    /**
     * ALL combined-soup vertex indices (the part system's {@code MeshRange}
     * index space) welded into the vertex a render corner belongs to. Empty
     * when unknown (no import yet, or the vertex was created by an edit after
     * the import). A shared seam vertex can map into several parts' ranges.
     */
    public int[] cornerToSoupIndices(int cornerIndex) {
        if (lastRenderMesh == null || vertexIdToSoupIndices == null
                || cornerIndex < 0 || cornerIndex >= lastRenderMesh.cornerToVertexId().length) {
            return NO_SOUP_INDICES;
        }
        int vertexId = lastRenderMesh.cornerToVertexId()[cornerIndex];
        return vertexId >= 0 && vertexId < vertexIdToSoupIndices.length
            ? vertexIdToSoupIndices[vertexId] : NO_SOUP_INDICES;
    }

    /** The render mesh from the most recent derivation, or null before it. */
    public RenderMesh getLastRenderMesh() {
        return lastRenderMesh;
    }

    // ── Rebuild entry points ────────────────────────────────────────────────

    @Override
    public void rebuildFromEditable() {
        derive(true);
        logger.trace("Structural rebuild complete: {} corners, {} triangles",
            lastRenderMesh != null ? lastRenderMesh.cornerCount() : 0,
            lastRenderMesh != null ? lastRenderMesh.triangleCount() : 0);
    }

    /**
     * Positions changed (which ones unknown: a scale op, a bulk update). With a live derivation every vertex moves
     * through it in one native call (no re-import of the structure, no new topology); otherwise a full derive.
     * Listeners get the usual "rebuilt" notices either way.
     */
    @Override
    public void refreshPositionsFromEditable() {
        if (!isDerivationLive() || positionsPending || deferDepth > 0) {
            derive(false);
            logger.trace("Position refresh complete");
            return;
        }
        long span = EditCostStats.begin();
        beforeDerive.run();
        int n = editableMesh.vertexCount();
        int[] all = new int[n];
        for (int v = 0; v < n; v++) all[v] = v;
        int changes = moveLive(all);
        if (changes != 0) {
            gpuSync.verticesChanged();
        }
        movedSinceCommit = false;
        trianglesMovedSinceCommit = false;
        afterDerive.run();
        long t = EditCostStats.begin();
        changeNotifier.notifyTopologyRebuilt(topology);
        changeNotifier.notifyGeometryRebuilt();
        EditCostStats.end(EditCostStats.Span.OVERLAY_REBUILD, t);
        EditCostStats.end(EditCostStats.Span.DERIVE, span);
    }

    /**
     * Positions-only refresh of the live derivation after {@code EditableMesh.setPosition} on {@code vertexIds}
     * (a drag frame, #175 INTEG): the render mesh's corner positions / projected UVs / triangles and the topology's
     * face geometry are updated in place, the GPU copy is marked stale for the touched corner ranges only, and the
     * draw batches when a face re-triangulated. Listeners are NOT notified (the caller sends the batched move, then
     * {@link #notifyRetriangulated()} when the result has {@code CHANGED_INDICES}).
     *
     * @return the {@link CendaMesh} {@code CHANGED_*} bits, or -1 when there is no live derivation (deferred or stale:
     *         the caller writes the corners itself and {@link #commitPositions} derives in full)
     */
    public int movePositions(int[] vertexIds) {
        if (!isDerivationLive()) {
            positionsPending = true;
            return -1;
        }
        if (vertexIds == null || vertexIds.length == 0) {
            return 0;
        }
        int changes = moveLive(vertexIds);
        if (changes != 0) {
            gpuSync.cornersChanged(derivation.lastCornerSpans(), (changes & CendaMesh.CHANGED_UVS) != 0);
            movedSinceCommit = true;
        }
        return changes;
    }

    /** The native move in place + the derived-state bookkeeping shared by every live move. */
    private int moveLive(int[] vertexIds) {
        long t = EditCostStats.begin();
        int changes = derivation.movePositionsInPlace(editableMesh, vertexIds);
        EditCostStats.end(EditCostStats.Span.MOVE_REFRESH, t);
        if (changes == 0) {
            return 0;
        }
        invalidateBounds();
        if ((changes & CendaMesh.CHANGED_INDICES) != 0) {
            // A face re-triangulated: the material-sorted index buffer is stale (same triangles per face).
            drawBatchesDirty = true;
            trianglesMovedSinceCommit = true;
        }
        return changes;
    }

    /**
     * After a live move that re-triangulated a face ({@code CHANGED_INDICES}): the listeners rebuild from the current
     * render mesh (the overlays' per-move updates only cover positions; their triangle data must follow the new
     * triangulation). Only frames where a face's triangulation flipped pay for it.
     */
    public void notifyRetriangulated() {
        if (!trianglesMovedSinceCommit) {
            return;
        }
        trianglesMovedSinceCommit = false;
        long t = EditCostStats.begin();
        changeNotifier.notifyTopologyRebuilt(topology);
        changeNotifier.notifyGeometryRebuilt();
        EditCostStats.end(EditCostStats.Span.OVERLAY_REBUILD, t);
    }

    /**
     * Ends an interactive position edit ({@link #movePositions} frames): the derivation already holds everything,
     * so only the shading stores catch up (and the overlays rebuild if a re-triangulation was not reported yet:
     * their per-move updates cover positions). Moves the derivation could not take, or a stale derivation, get the
     * full position refresh instead.
     */
    public void commitPositions() {
        if (positionsPending || !isDerivationLive() || deferDepth > 0) {
            positionsPending = false;
            derive(false);
            return;
        }
        if (!movedSinceCommit) {
            return;
        }
        long span = EditCostStats.begin();
        boolean retriangulated = trianglesMovedSinceCommit;
        movedSinceCommit = false;
        trianglesMovedSinceCommit = false;
        afterDerive.run();
        if (retriangulated) {
            long t = EditCostStats.begin();
            changeNotifier.notifyTopologyRebuilt(topology);
            changeNotifier.notifyGeometryRebuilt();
            EditCostStats.end(EditCostStats.Span.OVERLAY_REBUILD, t);
        }
        EditCostStats.end(EditCostStats.Span.DRAG_COMMIT, span);
    }

    /**
     * Shared derivation. {@code structural} marks the whole GPU copy stale
     * (loop/face changes); otherwise only the vertex data (positions + UVs).
     */
    // Deferred derivation (#175): while > 0, derive requests only accumulate; the last end runs one.
    private int deferDepth;
    private boolean deferredPending;
    private boolean deferredStructural;

    /**
     * Starts coalescing derivations: until the matching {@link #endDeferredDerive()}, rebuild / refresh requests
     * are recorded instead of run, and the end runs ONE derivation (structural if any request was). For multi-step
     * loads (parts, then face textures) that would otherwise derive the whole mesh once per step. Derived data
     * (render mesh, topology, overlays) is stale in between - only touch the editable mesh, parts and face
     * texture state inside the bracket. Nests.
     */
    public void beginDeferredDerive() {
        deferDepth++;
    }

    /** Ends a {@link #beginDeferredDerive()} bracket; the outermost end runs the pending derivation, if any. */
    public void endDeferredDerive() {
        if (deferDepth == 0) return;
        if (--deferDepth == 0 && deferredPending) {
            boolean structural = deferredStructural;
            deferredPending = false;
            deferredStructural = false;
            derive(structural);
        }
    }

    private void derive(boolean structural) {
        if (deferDepth > 0) {
            deferredPending = true;
            deferredStructural |= structural;
            return;
        }
        long span = EditCostStats.begin();
        beforeDerive.run();
        invalidateBounds();

        RenderMesh previous = lastRenderMesh;
        long t = EditCostStats.begin();
        RenderMeshBuilder.Derivation next = takePendingImport();
        if (next == null) {
            next = RenderMeshBuilder.derive(editableMesh, faceTextureManager);
        }
        EditCostStats.end(EditCostStats.Span.RENDER_MESH, t);
        releaseDerivation();
        this.derivation = next;
        this.derivationCurrent = true;
        this.derivedVertices = editableMesh.vertexCount();
        this.derivedFaces = editableMesh.faceCount();
        this.positionsPending = false;
        this.movedSinceCommit = false;
        this.trianglesMovedSinceCommit = false;
        RenderMesh rm = next.renderMesh();
        this.lastRenderMesh = rm;

        // Write the render-facing caches (GPU sync, extractors and serialization
        // all keep reading these).
        vertexManager.setData(rm.vertices(), rm.texCoords(), rm.indices());
        if (rm.triangleCount() > 0) {
            faceMapper.setMapping(rm.triangleToFaceId().clone());
        } else {
            faceMapper.clear();
        }
        vertexMapper.update(editableMesh, rm);

        // Topology from authoritative loops (same native pass) — winding is never reconstructed.
        this.topology = next.topology();

        if (structural) {
            gpuSync.structureChanged();
        } else {
            gpuSync.verticesChanged();
        }
        // The material-sorted index buffer only goes stale when the triangles (or their faces) changed: a
        // position refresh can re-triangulate a non-planar polygon, so compare instead of assuming.
        if (structural || !sameTriangles(previous, rm)) {
            drawBatchesDirty = true;
        }

        afterDerive.run();

        // Notify listeners
        t = EditCostStats.begin();
        changeNotifier.notifyTopologyRebuilt(topology);
        changeNotifier.notifyGeometryRebuilt();
        EditCostStats.end(EditCostStats.Span.OVERLAY_REBUILD, t);
        EditCostStats.end(EditCostStats.Span.DERIVE, span);
    }

    /** The pending import's native mesh as a derivation when it still matches the mesh, else null (dropped). */
    private RenderMeshBuilder.Derivation takePendingImport() {
        CendaMesh imported = pendingImport;
        if (imported == null) {
            return null;
        }
        boolean matches = pendingImportMesh == editableMesh
            && pendingImportFaces == editableMesh.faceCount()
            && pendingImportVertices == editableMesh.vertexCount();
        pendingImport = null;
        pendingImportMesh = null;
        if (!matches) {
            imported.close();
            return null;
        }
        return RenderMeshBuilder.deriveImported(imported, editableMesh, faceTextureManager);
    }

    /** Same triangles in the same order with the same faces (the draw batches stay valid). */
    private static boolean sameTriangles(RenderMesh a, RenderMesh b) {
        return a != null && b != null
            && java.util.Arrays.equals(a.indices(), b.indices())
            && java.util.Arrays.equals(a.triangleToFaceId(), b.triangleToFaceId());
    }

    // ── Shared state ────────────────────────────────────────────────────────

    @Override
    public MeshTopology getTopology() {
        return topology;
    }

    @Override
    public void setTopology(MeshTopology topology) {
        if (topology != this.topology) {
            derivationCurrent = false; // moves can no longer refresh this topology
        }
        this.topology = topology;
    }

    @Override
    public void markDrawBatchesDirty() {
        drawBatchesDirty = true;
    }

    @Override
    public boolean isDrawBatchesDirty() {
        return drawBatchesDirty;
    }

    /**
     * Clear the dirty flag after batches are rebuilt.
     */
    public void clearDrawBatchesDirty() {
        drawBatchesDirty = false;
    }

    private void invalidateBounds() {
        if (boundsInvalidator != null) {
            boundsInvalidator.run();
        }
    }
}
