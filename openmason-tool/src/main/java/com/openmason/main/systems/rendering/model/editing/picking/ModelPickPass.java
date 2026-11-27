package com.openmason.main.systems.rendering.model.editing.picking;

import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.MeshChangeListener;
import com.openmason.engine.modeling.topology.MeshTopology;
import com.openmason.engine.rendering.model.IndexedDrawBatch;
import com.openmason.engine.rendering.model.draw.JointPaletteTexture;
import com.openmason.engine.rendering.model.draw.MaterialTextures;
import com.openmason.engine.rendering.model.draw.ModelDrawPlan;
import com.openmason.engine.rendering.model.draw.ModelGpuMesh;
import com.openmason.engine.rendering.model.draw.ModelMorphGlsl;
import com.openmason.engine.rendering.model.draw.ModelRenderer;
import com.openmason.engine.rendering.model.draw.ModelSkinGlsl;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rig.JointPalette;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * The editor viewport's ID-buffer picking pass (#175 F4). After the model's lit draw it replays exactly that draw
 * ({@link ModelRenderer#capturedDraw}: plan, visible parts, skin palette, shape keys) into a small {@code R32UI}
 * window around the cursor - faces, then (depth-tested against them) the face-loop edges as 1-pixel lines and the
 * welded vertices as round points of the overlay's size - and reads the window back through a PBO, used one frame
 * later without a stall. Picks are occlusion-correct by construction and follow what is on screen (a part dragged
 * through the palette, a pose, hidden parts). The ids are the overlays' own (see {@link PickCornerTable}).
 *
 * <p>Queries ({@code query*}) run in the input phase. A query is ANSWERED from the latest readback when that one was
 * rendered for the same camera / model matrix / viewport, the same drawn geometry, and the cursor (with the kind's
 * pick radius) lies inside its window; otherwise it is PENDING and the next frame renders a window around it (a
 * window is also re-centred before the cursor walks out of it). The readback landing bumps {@link #epoch()}, which
 * makes the hover gates ask again. Nothing renders while nobody asks: a still mouse costs nothing. A query that stays
 * pending for {@value #STALE_FRAMES} frames, a pass that cannot run (no GL, no captured draw, another model) and a
 * count that does not match the overlay asking report UNAVAILABLE, and the caller falls back to its CPU test.
 * {@link #resolveNow()} renders and reads synchronously (a click on a stale window).
 *
 * <p>Faces (and the brush's surface point) are picked on the DISPLAYED mesh: the plan after the appearance's
 * vertex-group mask, shape keys applied. Vertices and edges are picked on the EDIT CAGE (Blender's): the plan before
 * the mask, no shape keys - where the overlays draw them; both follow the skin palette (part / bone drags).
 *
 * <p>Back faces: the lit pass draws both sides with no culling, so this pass does too - what is visible is
 * pickable, whichever way it faces; anything behind a nearer surface is not.
 *
 * <p>Index order never matters: ids come from {@code gl_VertexID} (a render corner) through {@link PickCornerTable},
 * never from {@code gl_PrimitiveID}, so a material-sorted or mask-reordered buffer needs no triangle-order map.
 */
public final class ModelPickPass implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ModelPickPass.class);

    /** Query results besides an id (>= 0) or -1 (nothing there). */
    public static final int UNAVAILABLE = Integer.MIN_VALUE;
    public static final int PENDING = Integer.MIN_VALUE + 1;

    /** Window edge in pixels (a cursor moving less than about a quarter of it per frame never waits). */
    public static final int SIZE = 64;
    /** Texture unit of the corner table (above the engine's 30..35). */
    static final int TABLE_UNIT = 36;
    static final int STALE_FRAMES = 4;
    private static final int RING = 3;
    private static final int MAX_QUERIES_WITHOUT_FRAME = 48;
    private static final int TARGET_BYTES = SIZE * SIZE * 4;
    private static final int READBACK_BYTES = TARGET_BYTES * 4;

    // ---- GL resources -----------------------------------------------------------------------------------------
    private boolean glReady;
    private boolean glFailed;
    private ShaderProgram faceProgram, edgeProgram, pointProgram;
    private final int[] fbo = new int[3];
    private final int[] colorTex = new int[3];
    private final int[] depthRb = new int[3];
    private int tableBuffer, tableTexture;
    private int maxTableTexels;
    private final JointPaletteTexture paletteTexture = new JointPaletteTexture();
    private final int[] pbo = new int[RING];
    private final long[] fence = new long[RING];
    private final PickRegion[] slotStamp = new PickRegion[RING];
    private ByteBuffer syncBuffer;

    // ---- What is drawn ----------------------------------------------------------------------------------------
    private EditableModel model;
    private long geometryRevision;
    /** Bumped by re-triangulations / rebuilds: arrays may change IN PLACE (drags), so identity is not enough. */
    private long structureRevision;
    private long tableStructure = -1;
    private Object seenTopology;
    private int[] seenIndices;
    private float[] seenPositions;
    private final MeshChangeListener listener = new MeshChangeListener() {
        @Override
        public void onVertexPositionChanged(int uniqueIndex, Vector3f newPosition, int[] affectedMeshIndices) {
            geometryRevision++;
        }

        @Override
        public void onVertexPositionsChanged(int[] uniqueIndices, float[] positions, int[][] affected) {
            geometryRevision++;
        }

        @Override
        public void onGeometryRebuilt() {
            geometryRevision++;
            structureRevision++;
        }

        @Override
        public void onTopologyRebuilt(MeshTopology topology) {
            geometryRevision++;
            structureRevision++;
        }
    };
    private ModelRenderer.DrawCapture capture;
    private long captureSerial = -1;
    private boolean available;
    private long frame;
    private int queriesSinceFrame;
    private long drawSignature;
    private final Matrix4f frameModel = new Matrix4f();

    private PickCornerTable table;
    private int tableVersion;
    private Object tableTopology;
    private int[] tableIndices;
    private int tableCorners;

    // ---- Readbacks and queries ------------------------------------------------------------------------------------
    private final PickRegion ready = new PickRegion(SIZE);
    private long issued;
    private long readySerial;
    private final long[] slotSerial = new long[RING];
    private long epoch;

    private boolean queried;
    private float queryX, queryY;
    private int queryWidth, queryHeight;
    private final Matrix4f queryView = new Matrix4f();
    private final Matrix4f queryProjection = new Matrix4f();
    private float queryPointSize = 7f;
    private int wanted;
    private boolean requested;
    private long pendingSince = -1;
    private long lastPendingFrame = -1;

    private int lastVertex = -1, lastEdge = -1, lastTriangle = -1;
    private boolean lastSurfaceHit;
    private final Vector3f lastSurface = new Vector3f();

    // ---- Statistics (benchmarks, tests) ---------------------------------------------------------------------------
    private long passes, syncPasses, landed;
    private long passNanos;
    private long tableNanos, tableBuilds;

    public ModelPickPass() {
        for (int i = 0; i < RING; i++) slotStamp[i] = new PickRegion(0);
    }

    // =========================================================================================================
    // Frame hook
    // =========================================================================================================

    /**
     * Called by the viewport right after the model's lit draw, every frame. Tracks what was drawn, bumps the epoch
     * when it changed, and renders a window when a query waits for one (or the drawn model changed under a cursor
     * that is being asked about). {@code shown} false (other content, no model) makes queries UNAVAILABLE.
     */
    public void afterModelDraw(ModelRenderer renderer, EditableModel editableModel, Matrix4f modelMatrix,
                               boolean shown) {
        frame++;
        queriesSinceFrame = 0;
        if (renderer == null || editableModel == null || !shown || glFailed
                || editableModel.getGpuMesh() == null) {
            setAvailable(false);
            return;
        }
        attach(editableModel);
        renderer.captureDrawsOf(editableModel.getGpuMesh());
        ModelRenderer.DrawCapture cap = renderer.capturedDraw();
        if (cap == null || cap.serial() == captureSerial) {
            // Nothing drawn this frame (empty model): no window to pick from.
            setAvailable(false);
            return;
        }
        captureSerial = cap.serial();
        capture = cap;
        frameModel.set(modelMatrix);
        trackGeometry();
        if (!ensureGl()) {
            setAvailable(false);
            return;
        }
        long signature = signature(cap);
        boolean changed = signature != drawSignature;
        drawSignature = signature;
        setAvailable(true);
        if (changed) epoch++;
        poll();
        if (requested || queried) {
            if (current(ready) || (!inFlightCovers() && issue(false))) {
                requested = false;
                queried = false;
            }
        }
    }

    /**
     * True when {@code r} was rendered for what is drawn now and answers the last query: its view, its cursor (not
     * near the window's edge), the kinds asked since the last pass, the vertex point size.
     */
    private boolean current(PickRegion r) {
        return r.valid && r.drawSignature == drawSignature && r.geometryRevision == geometryRevision
                && r.tableVersion == tableVersion && (wanted & ~r.kinds) == 0
                && ((wanted & PickRegion.VERTICES) == 0 || r.pointSize == queryPointSize)
                && r.sameView(queryWidth, queryHeight, queryView, queryProjection, frameModel)
                && r.offCentre(queryX, queryY) <= SIZE / 4f;
    }

    /** A readback still in flight will answer the last query: no second pass for it. */
    private boolean inFlightCovers() {
        for (int i = 0; i < RING; i++) {
            if (fence[i] != 0 && current(slotStamp[i])) return true;
        }
        return false;
    }

    private void setAvailable(boolean value) {
        if (value != available) {
            available = value;
            epoch++; // the gates re-pick with (or without) the GPU
        }
    }

    private void attach(EditableModel editableModel) {
        if (editableModel == model) return;
        if (model != null) model.removeMeshChangeListener(listener);
        model = editableModel;
        model.addMeshChangeListener(listener);
        geometryRevision++;
        table = null;
        tableTopology = null;
        tableIndices = null;
        tableCorners = -1;
    }

    private void trackGeometry() {
        Object topology = model.getTopology();
        int[] indices = model.triangleIndicesView();
        float[] positions = model.meshVertexPositionsView();
        if (topology != seenTopology || indices != seenIndices || positions != seenPositions) {
            seenTopology = topology;
            seenIndices = indices;
            seenPositions = positions;
            geometryRevision++;
        }
    }

    /** Hash of everything the capture draws: plan, part matrices, skin palette, shape-key weights, mesh shape. */
    private static long signature(ModelRenderer.DrawCapture cap) {
        long[] h = {0xcbf29ce484222325L};
        ModelGpuMesh mesh = cap.mesh();
        h[0] = mix(h[0], System.identityHashCode(cap.plan()));
        h[0] = mix(h[0], System.identityHashCode(cap.basePlan()));
        h[0] = mix(h[0], System.identityHashCode(mesh));
        h[0] = mix(h[0], mesh.indexCount());
        h[0] = mix(h[0], mesh.vertexCount());
        h[0] = mix(h[0], (mesh.hasSkin() ? 1 : 0) | (mesh.hasMorphs() ? 2 : 0));
        float[] m = new float[16];
        cap.parts().forEachPart((part, matrix) -> {
            h[0] = mix(h[0], part);
            matrix.get(m);
            for (float f : m) h[0] = mix(h[0], Float.floatToRawIntBits(f));
        });
        JointPalette palette = cap.skinPalette();
        if (palette != null) {
            FloatBuffer fb = palette.skinBuffer();
            for (int i = 0; i < fb.limit(); i++) h[0] = mix(h[0], Float.floatToRawIntBits(fb.get(i)));
        }
        float[] weights = cap.morphWeights();
        h[0] = mix(h[0], weights == null ? 0 : Arrays.hashCode(weights));
        return h[0];
    }

    private static long mix(long h, int v) {
        return (h ^ (v & 0xffffffffL)) * 0x100000001b3L;
    }

    /**
     * Brings the corner table up to date with the model's derivation - lazily, on the first query or pass after a
     * structural change, so edits nobody picks after cost nothing here. False when there is none.
     */
    private boolean ensureTable() {
        if (model == null || !glReady) return false;
        int[] indices = model.triangleIndicesView();
        float[] positions = model.meshVertexPositionsView();
        Object topology = model.getTopology();
        int corners = positions != null ? positions.length / 3 : 0;
        if (topology == tableTopology && indices == tableIndices && corners == tableCorners
                && tableStructure == structureRevision) {
            return table != null;
        }
        tableStructure = structureRevision;
        tableTopology = topology;
        tableIndices = indices;
        tableCorners = corners;
        long t0 = System.nanoTime();
        table = PickCornerTable.build(model);
        tableNanos += System.nanoTime() - t0;
        tableBuilds++;
        tableVersion++;
        if (table == null) return false;
        if (table.cornerCount() > maxTableTexels) {
            logger.warn("Model has {} corners, over the GL buffer texture limit {}: CPU picking",
                    table.cornerCount(), maxTableTexels);
            table = null;
            return false;
        }
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, tableBuffer);
        GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, table.texels(), GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
        int previousActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + TABLE_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, tableTexture);
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32UI, tableBuffer);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        GL13.glActiveTexture(previousActive);
        return true;
    }

    // =========================================================================================================
    // Queries
    // =========================================================================================================

    /** Hovered vertex (welded id), -1, {@link #PENDING} or {@link #UNAVAILABLE}. */
    public int queryVertex(float mx, float my, int w, int h, Matrix4f view, Matrix4f projection, Matrix4f modelMatrix,
                           int vertexCount, float pointSize) {
        if (!usable() || vertexCount != table.uniqueVertexCount()) return UNAVAILABLE;
        remember(mx, my, w, h, view, projection, modelMatrix, PickRegion.VERTICES);
        queryPointSize = pointSize;
        poll();
        if (answers(PickRegion.VERTICES, w, h, view, projection, modelMatrix) && ready.pointSize == pointSize) {
            int cell = ready.cell(mx, my, (int) Math.ceil(pointSize * 0.5f) + 1);
            if (cell >= 0) {
                int id = PickRegion.idAt(ready.vertices, cell);
                return answered(mx, my, lastVertex = id < vertexCount ? id : -1);
            }
        }
        return pending();
    }

    /** Hovered edge (the edge overlay's index), -1, {@link #PENDING} or {@link #UNAVAILABLE}. */
    public int queryEdge(float mx, float my, int w, int h, Matrix4f view, Matrix4f projection, Matrix4f modelMatrix,
                         int edgeCount, float thresholdPixels) {
        if (!usable() || edgeCount != table.edgeRecordCount()) return UNAVAILABLE;
        remember(mx, my, w, h, view, projection, modelMatrix, PickRegion.EDGES);
        poll();
        if (answers(PickRegion.EDGES, w, h, view, projection, modelMatrix)) {
            if (ready.cell(mx, my, (int) Math.ceil(thresholdPixels) + 1) >= 0) {
                int id = ready.nearestId(ready.edges, mx, my, thresholdPixels);
                return answered(mx, my, lastEdge = id < edgeCount ? id : -1);
            }
        }
        return pending();
    }

    /**
     * Hovered triangle: the FIRST triangle of the face under the cursor in the editor's triangle order (the face
     * overlay maps it to the face), -1, {@link #PENDING} or {@link #UNAVAILABLE}.
     */
    public int queryTriangle(float mx, float my, int w, int h, Matrix4f view, Matrix4f projection,
                             Matrix4f modelMatrix, int triangleCount) {
        if (!usable() || triangleCount != table.triangleCount()) return UNAVAILABLE;
        remember(mx, my, w, h, view, projection, modelMatrix, PickRegion.FACES);
        poll();
        if (answers(PickRegion.FACES, w, h, view, projection, modelMatrix)) {
            int cell = ready.cell(mx, my, 0);
            if (cell >= 0) {
                int id = PickRegion.idAt(ready.faces, cell);
                return answered(mx, my, lastTriangle = id < triangleCount ? id : -1);
            }
        }
        return pending();
    }

    /**
     * The visible surface point under the cursor in MODEL space ({@code modelMatrix}'s inverse) into {@code out}:
     * 1 hit, -1 miss, {@link #PENDING} or {@link #UNAVAILABLE}.
     */
    public int querySurface(float mx, float my, int w, int h, Matrix4f view, Matrix4f projection,
                            Matrix4f modelMatrix, Vector3f out) {
        if (!usable()) return UNAVAILABLE;
        remember(mx, my, w, h, view, projection, modelMatrix, PickRegion.FACES);
        poll();
        if (answers(PickRegion.FACES, w, h, view, projection, modelMatrix)) {
            int cell = ready.cell(mx, my, 0);
            if (cell >= 0) {
                lastSurfaceHit = ready.surfacePoint(cell, mx, my, lastSurface);
                out.set(lastSurface);
                return answered(mx, my, lastSurfaceHit ? 1 : -1);
            }
        }
        return pending();
    }

    public int lastVertex() {
        return lastVertex;
    }

    public int lastEdge() {
        return lastEdge;
    }

    public int lastTriangle() {
        return lastTriangle;
    }

    /** The last answered surface pick: 1 / -1, the point into {@code out}. */
    public int lastSurface(Vector3f out) {
        out.set(lastSurface);
        return lastSurfaceHit ? 1 : -1;
    }

    /** Bumped whenever an earlier answer may have changed (a readback landed, the drawn model changed). */
    public long epoch() {
        return epoch;
    }

    /**
     * A click while the window is stale: renders and reads it NOW (a pipeline stall, clicks only). True when that
     * ran, so the caller asks again and gets the answer. Runs in the input phase on the GL thread.
     */
    public boolean resolveNow() {
        if (!usable() || pendingSince < 0 || !queried) return false;
        poll();
        if (!issue(true)) return false;
        requested = false;
        queried = false;
        return true;
    }

    private boolean usable() {
        return available && ++queriesSinceFrame <= MAX_QUERIES_WITHOUT_FRAME && ensureTable();
    }

    private void remember(float mx, float my, int w, int h, Matrix4f view, Matrix4f projection, Matrix4f modelMatrix,
                          int kind) {
        queried = true;
        queryX = mx;
        queryY = my;
        queryWidth = w;
        queryHeight = h;
        queryView.set(view);
        queryProjection.set(projection);
        wanted |= kind;
    }

    private boolean answers(int kind, int w, int h, Matrix4f view, Matrix4f projection, Matrix4f modelMatrix) {
        return ready.valid && (ready.kinds & kind) != 0 && ready.geometryRevision == geometryRevision
                && ready.drawSignature == drawSignature && ready.tableVersion == tableVersion
                && ready.sameView(w, h, view, projection, modelMatrix);
    }

    private int answered(float mx, float my, int id) {
        pendingSince = -1;
        if (ready.offCentre(mx, my) > SIZE / 4f) {
            requested = true; // re-centre before the cursor walks out of the window
        }
        return id;
    }

    private int pending() {
        requested = true;
        // Only an unbroken streak of pending frames counts (a cursor that comes back later starts afresh)
        if (pendingSince < 0 || frame - lastPendingFrame > 1) pendingSince = frame;
        lastPendingFrame = frame;
        return frame - pendingSince >= STALE_FRAMES ? UNAVAILABLE : PENDING;
    }

    // =========================================================================================================
    // The pass
    // =========================================================================================================

    /** Renders a window around the last query and starts its readback (or reads it now). False when it could not. */
    private boolean issue(boolean sync) {
        if (capture == null || !ensureTable() || queryWidth <= 0 || queryHeight <= 0) return false;
        int slot = -1;
        PickRegion stamp = ready;
        if (!sync) {
            for (int i = 0; i < RING; i++) {
                if (fence[i] == 0) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) return false; // every readback still in flight: try next frame
            stamp = slotStamp[slot];
        }
        long t0 = System.nanoTime();
        stamp.valid = false;
        stamp.originX = (int) Math.floor(queryX) - SIZE / 2;
        stamp.originY = (int) Math.floor(queryHeight - queryY) - SIZE / 2;
        stamp.viewportWidth = queryWidth;
        stamp.viewportHeight = queryHeight;
        stamp.view.set(queryView);
        stamp.projection.set(queryProjection);
        stamp.model.set(frameModel);
        stamp.drawSignature = drawSignature;
        stamp.geometryRevision = geometryRevision;
        stamp.tableVersion = tableVersion;
        stamp.pointSize = queryPointSize;
        stamp.kinds = PickRegion.FACES | wanted;
        wanted = 0;

        GlState state = GlState.save();
        try {
            render(stamp);
            if (sync) {
                readNow();
                stamp.valid = true;
                readySerial = ++issued;
                syncPasses++;
                epoch++;
            } else {
                readAsync(slot);
                slotSerial[slot] = ++issued;
                stamp.valid = true;
            }
            passes++;
        } finally {
            state.restore();
        }
        passNanos += System.nanoTime() - t0;
        return true;
    }

    private final Matrix4f pickProjection = new Matrix4f();

    private void render(PickRegion stamp) {
        // Pick matrix: the window's pixels fill the whole target, everything else exactly as the full viewport.
        float w = stamp.viewportWidth, h = stamp.viewportHeight;
        float sx = w / SIZE, sy = h / SIZE;
        float tx = sx - 2f * stamp.originX / SIZE - 1f;
        float ty = sy - 2f * stamp.originY / SIZE - 1f;
        pickProjection.identity().m00(sx).m11(sy).m30(tx).m31(ty).mul(stamp.projection);

        GL11.glViewport(0, 0, SIZE, SIZE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        GL11.glColorMask(true, true, true, true);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        for (int i = 0; i < 3; i++) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo[i]);
            GL30.glClearBufferuiv(GL11.GL_COLOR, 0, ZERO);
            GL30.glClearBufferfv(GL11.GL_DEPTH, 0, ONE);
        }

        boolean skinned = capture.skinPalette() != null && capture.mesh().hasSkin();
        if (skinned) paletteTexture.upload(capture.skinPalette());

        // Faces, pushed back a little so edges / points on their own surface win the depth test.
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo[0]);
        GL11.glDepthFunc(GL11.GL_LESS);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(1f, 1f);
        replay(faceProgram, GL11.GL_TRIANGLES, stamp, skinned, false);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);

        // Vertices and edges are picked on the EDIT CAGE (Blender's): the overlays draw the undeformed, unmasked
        // mesh, so a scene preview's shape keys / hidden vertex groups must not move or hide what they pick.
        // Without a preview the cage is the displayed mesh and the faces' depth is reused.
        boolean wantsCage = (stamp.kinds & (PickRegion.EDGES | PickRegion.VERTICES)) != 0;
        boolean cageDiffers = capture.basePlan() != capture.plan() || capture.mesh().hasMorphs();
        if (wantsCage && cageDiffers) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo[1]);
            GL11.glColorMask(false, false, false, false);
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            replay(faceProgram, GL11.GL_TRIANGLES, stamp, skinned, true);
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glColorMask(true, true, true, true);
            if ((stamp.kinds & PickRegion.VERTICES) != 0) copyDepth(fbo[1], fbo[2]);
        }

        GL11.glDepthFunc(GL11.GL_LEQUAL);
        if ((stamp.kinds & PickRegion.EDGES) != 0) {
            if (cageDiffers) GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo[1]);
            else copyDepth(fbo[0], fbo[1]);
            replay(edgeProgram, GL11.GL_TRIANGLES, stamp, skinned, true);
        }
        if ((stamp.kinds & PickRegion.VERTICES) != 0) {
            if (cageDiffers) GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo[2]);
            else copyDepth(fbo[0], fbo[2]);
            GL11.glEnable(GL32.GL_PROGRAM_POINT_SIZE);
            replay(pointProgram, GL11.GL_POINTS, stamp, skinned, true);
            GL11.glDisable(GL32.GL_PROGRAM_POINT_SIZE);
        }
    }

    private static final int[] ZERO = {0, 0, 0, 0};
    private static final float[] ONE = {1f};

    private static void copyDepth(int from, int to) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to);
        GL30.glBlitFramebuffer(0, 0, SIZE, SIZE, 0, 0, SIZE, SIZE, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, to);
    }

    /**
     * Replays the captured lit draw with {@code program} as {@code mode} primitives over the same batches: as
     * displayed (the appearance's plan, shape keys), or the edit {@code cage} (the plan before the vertex-group mask,
     * no shape keys). Both keep the visible parts and the skin palette (a part / bone drag moves the overlays too).
     */
    private void replay(ShaderProgram program, int mode, PickRegion stamp, boolean skinned, boolean cage) {
        ModelGpuMesh mesh = capture.mesh();
        program.bind();
        program.setMat4("view", stamp.view);
        program.setMat4("projection", pickProjection);
        program.setFloat("u_pointSize", stamp.pointSize);
        program.setBool("u_skinned", skinned);
        if (skinned) {
            paletteTexture.bind(JointPaletteTexture.DEFAULT_UNIT);
            mesh.bindSkinInfluences(ModelSkinGlsl.INFLUENCES_UNIT);
        }
        // The lit draw just uploaded this frame's shape-key weights: bind, never re-upload.
        boolean morphed = !cage && mesh.hasMorphs();
        if (morphed) mesh.bindMorphs();
        program.setBool("u_morphed", morphed);
        program.setBool("u_morphNormals", false);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + TABLE_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, tableTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        GL30.glBindVertexArray(mesh.vao());
        ModelDrawPlan plan = cage ? capture.basePlan() : capture.plan();
        MaterialTextures textures = capture.textures();
        capture.parts().forEachPart((part, matrix) -> {
            program.setMat4("model", matrix);
            for (IndexedDrawBatch batch : plan.forPart(part).textured()) {
                if (textures != null && textures.textureFor(batch.materialId()) < 0) continue; // the lit pass skips it
                GL11.glDrawElements(mode, batch.indexCount(), GL11.GL_UNSIGNED_INT, batch.byteOffset());
            }
        });
        GL30.glBindVertexArray(0);

        if (morphed) ModelGpuMesh.unbindMorphs();
        if (skinned) {
            JointPaletteTexture.unbind(JointPaletteTexture.DEFAULT_UNIT);
            JointPaletteTexture.unbind(ModelSkinGlsl.INFLUENCES_UNIT);
        }
        JointPaletteTexture.unbind(TABLE_UNIT);
        program.unbind();
    }

    private void readAsync(int slot) {
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[slot]);
        for (int i = 0; i < 3; i++) {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo[i]);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glReadPixels(0, 0, SIZE, SIZE, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (long) i * TARGET_BYTES);
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo[0]);
        GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, 3L * TARGET_BYTES);
        fence[slot] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
    }

    private void readNow() {
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        syncBuffer.clear();
        for (int i = 0; i < 3; i++) {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo[i]);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            syncBuffer.position(i * TARGET_BYTES);
            GL11.glReadPixels(0, 0, SIZE, SIZE, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, syncBuffer);
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo[0]);
        syncBuffer.position(3 * TARGET_BYTES);
        GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, syncBuffer);
        syncBuffer.clear();
        copyInto(ready, syncBuffer);
    }

    private static void copyInto(PickRegion region, ByteBuffer data) {
        data.order(ByteOrder.nativeOrder());
        data.position(0);
        data.asIntBuffer().get(region.faces);
        data.position(TARGET_BYTES);
        data.asIntBuffer().get(region.edges);
        data.position(2 * TARGET_BYTES);
        data.asIntBuffer().get(region.vertices);
        data.position(3 * TARGET_BYTES);
        data.asFloatBuffer().get(region.depth);
        data.position(0);
    }

    /** Takes every readback that finished (never waits); the newest one becomes the answer. */
    private void poll() {
        if (!glReady) return;
        for (int pass = 0; pass < RING; pass++) {
            int oldest = -1;
            for (int i = 0; i < RING; i++) {
                if (fence[i] != 0 && (oldest < 0 || slotSerial[i] < slotSerial[oldest])) oldest = i;
            }
            if (oldest < 0) return;
            int status = GL32.glClientWaitSync(fence[oldest], GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 0L);
            if (status == GL32.GL_TIMEOUT_EXPIRED) return;
            GL32.glDeleteSync(fence[oldest]);
            fence[oldest] = 0;
            if (status == GL32.GL_WAIT_FAILED || slotSerial[oldest] < readySerial) continue;
            int previous = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[oldest]);
            ByteBuffer data = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, READBACK_BYTES,
                    GL30.GL_MAP_READ_BIT);
            if (data != null) {
                copyInto(ready, data);
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                ready.stampFrom(slotStamp[oldest]);
                readySerial = slotSerial[oldest];
                landed++;
                epoch++;
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previous);
        }
    }

    // =========================================================================================================
    // GL setup
    // =========================================================================================================

    private boolean ensureGl() {
        if (glReady) return true;
        if (glFailed) return false;
        try {
            faceProgram = program(ModelPickShaders.FACE_FRAGMENT, null);
            edgeProgram = program(ModelPickShaders.EDGE_FRAGMENT, ModelPickShaders.EDGE_GEOMETRY);
            pointProgram = program(ModelPickShaders.POINT_FRAGMENT, null);
            for (int i = 0; i < 3; i++) {
                colorTex[i] = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex[i]);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32UI, SIZE, SIZE, 0, GL30.GL_RED_INTEGER,
                        GL11.GL_UNSIGNED_INT, (ByteBuffer) null);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                depthRb[i] = GL30.glGenRenderbuffers();
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthRb[i]);
                GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH_COMPONENT32F, SIZE, SIZE);
                fbo[i] = GL30.glGenFramebuffers();
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
            int previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            boolean complete = true;
            for (int i = 0; i < 3; i++) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo[i]);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                        colorTex[i], 0);
                GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER,
                        depthRb[i]);
                complete &= GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
            if (!complete) throw new IllegalStateException("ID framebuffer incomplete");
            tableBuffer = GL15.glGenBuffers();
            tableTexture = GL11.glGenTextures();
            maxTableTexels = GL11.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE);
            for (int i = 0; i < RING; i++) {
                pbo[i] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[i]);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, READBACK_BYTES, GL15.GL_STREAM_READ);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            syncBuffer = MemoryUtil.memAlloc(READBACK_BYTES);
            glReady = true;
            return true;
        } catch (RuntimeException e) {
            logger.warn("GPU picking unavailable ({}); the CPU detectors stay in use", e.getMessage());
            glFailed = true;
            close();
            return false;
        }
    }

    private static ShaderProgram program(String fragment, String geometry) {
        ShaderProgram p = new ShaderProgram();
        p.createVertexShader(ModelPickShaders.VERTEX);
        if (geometry != null) p.createGeometryShader(geometry);
        p.createFragmentShader(fragment);
        p.link();
        p.bind();
        p.setInt("u_jointPalette", JointPaletteTexture.DEFAULT_UNIT);
        p.setInt("u_skinInfluences", ModelSkinGlsl.INFLUENCES_UNIT);
        p.setInt("u_pickCorners", TABLE_UNIT);
        p.setBool("u_skinned", false);
        ModelMorphGlsl.bindSamplerUnits(p);
        p.unbind();
        return p;
    }

    /** Frees the GL objects and stops listening to the model. Needs the GL context. */
    @Override
    public void close() {
        if (model != null) {
            model.removeMeshChangeListener(listener);
            model = null;
        }
        for (int i = 0; i < RING; i++) {
            if (fence[i] != 0) GL32.glDeleteSync(fence[i]);
            fence[i] = 0;
            if (pbo[i] != 0) GL15.glDeleteBuffers(pbo[i]);
            pbo[i] = 0;
        }
        for (int i = 0; i < 3; i++) {
            if (fbo[i] != 0) GL30.glDeleteFramebuffers(fbo[i]);
            if (colorTex[i] != 0) GL11.glDeleteTextures(colorTex[i]);
            if (depthRb[i] != 0) GL30.glDeleteRenderbuffers(depthRb[i]);
            fbo[i] = colorTex[i] = depthRb[i] = 0;
        }
        if (tableBuffer != 0) GL15.glDeleteBuffers(tableBuffer);
        if (tableTexture != 0) GL11.glDeleteTextures(tableTexture);
        tableBuffer = tableTexture = 0;
        for (ShaderProgram p : new ShaderProgram[]{faceProgram, edgeProgram, pointProgram}) {
            if (p != null) p.cleanup();
        }
        faceProgram = edgeProgram = pointProgram = null;
        paletteTexture.close();
        if (syncBuffer != null) MemoryUtil.memFree(syncBuffer);
        syncBuffer = null;
        glReady = false;
        available = false;
        ready.valid = false;
        table = null;
        tableTopology = null;
    }

    // =========================================================================================================
    // Diagnostics
    // =========================================================================================================

    /** Passes rendered (async + sync), sync passes, readbacks landed, and total CPU time issuing passes. */
    public long passCount() {
        return passes;
    }

    public long syncPassCount() {
        return syncPasses;
    }

    public long landedCount() {
        return landed;
    }

    public long passNanos() {
        return passNanos;
    }

    /** Corner tables built (structural changes) and their total CPU time. */
    public long tableBuildCount() {
        return tableBuilds;
    }

    public long tableBuildNanos() {
        return tableNanos;
    }

    /** True while queries can be answered by the GPU (the last frame drew the model and GL is set up). */
    public boolean isAvailable() {
        return available;
    }

    /** The GL state a pass touches, saved before and restored after. */
    private static final class GlState {
        int drawFbo, readFbo, program, vao, activeTexture, packBuffer, depthFunc;
        final int[] viewport = new int[4];
        final int[] polygonMode = new int[2];
        boolean scissor, blend, cull, depthTest, depthMask, polygonOffset, pointSize;
        float offsetFactor, offsetUnits;
        final boolean[] colorMask = new boolean[4];

        static GlState save() {
            GlState s = new GlState();
            s.drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            s.readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            s.program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            s.vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            s.activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            s.packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            s.depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, s.viewport);
            GL11.glGetIntegerv(GL11.GL_POLYGON_MODE, s.polygonMode);
            s.scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            s.blend = GL11.glIsEnabled(GL11.GL_BLEND);
            s.cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            s.depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            s.depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            s.polygonOffset = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
            s.pointSize = GL11.glIsEnabled(GL32.GL_PROGRAM_POINT_SIZE);
            s.offsetFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
            s.offsetUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                ByteBuffer mask = stack.malloc(4);
                GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
                for (int i = 0; i < 4; i++) s.colorMask[i] = mask.get(i) != 0;
            }
            return s;
        }

        void restore() {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vao);
            GL13.glActiveTexture(activeTexture);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
            GL11.glDepthFunc(depthFunc);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, polygonMode[0]);
            set(GL11.GL_SCISSOR_TEST, scissor);
            set(GL11.GL_BLEND, blend);
            set(GL11.GL_CULL_FACE, cull);
            set(GL11.GL_DEPTH_TEST, depthTest);
            GL11.glDepthMask(depthMask);
            set(GL11.GL_POLYGON_OFFSET_FILL, polygonOffset);
            set(GL32.GL_PROGRAM_POINT_SIZE, pointSize);
            GL11.glPolygonOffset(offsetFactor, offsetUnits);
            GL11.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        }

        private static void set(int cap, boolean on) {
            if (on) GL11.glEnable(cap);
            else GL11.glDisable(cap);
        }
    }
}
