package com.openmason.main.systems.rendering.model.editing.picking;

import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.rendering.model.draw.ModelRenderer;
import com.openmason.main.systems.rendering.model.editing.subrenders.HoverQueryGate;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The model viewport's GPU picking as the hover detectors, the hover gate and the input controllers see it (#175 F4):
 * one {@link ModelPickPass} fed by the viewport after the model's draw. Every query returns an id / -1, or
 * {@link #UNAVAILABLE} - the caller then runs its CPU test (no GL, another model, an overlay whose element count does
 * not match the drawn model, or {@code -Dopenmason.picking.cpu=true}). While the GPU answer is a frame away the query
 * returns the previous answer of its kind and asks the current {@link HoverQueryGate} to try again next frame.
 * All calls on the GL / UI thread.
 */
public final class ModelPicking {

    public static final int UNAVAILABLE = ModelPickPass.UNAVAILABLE;

    private static final boolean CPU_ONLY = Boolean.getBoolean("openmason.picking.cpu");

    private static ModelPickPass pass;

    private ModelPicking() {
    }

    /**
     * The viewport's frame hook, right after the editable model's lit draw (GL thread): see
     * {@link ModelPickPass#afterModelDraw}. {@code shown} false when the viewport shows other content.
     */
    public static void afterModelDraw(ModelRenderer renderer, EditableModel model, Matrix4f modelMatrix,
                                      boolean shown) {
        if (CPU_ONLY) return;
        if (pass == null) {
            if (!shown || renderer == null || model == null) return;
            pass = new ModelPickPass();
        }
        pass.afterModelDraw(renderer, model, modelMatrix, shown);
    }

    /** Frees the pass (viewport cleanup; GL thread). */
    public static void close() {
        if (pass != null) {
            pass.close();
            pass = null;
        }
    }

    /** The active pass, or null (tests, diagnostics). */
    public static ModelPickPass pass() {
        return pass;
    }

    /** Changes whenever an earlier pick may have a new answer; 0 without GPU picking. */
    public static long epoch() {
        return pass != null ? pass.epoch() : 0L;
    }

    /** Hovered welded vertex (the vertex overlay's index), -1, or {@link #UNAVAILABLE}. */
    public static int vertex(float mx, float my, int w, int h, Matrix4f view, Matrix4f projection,
                             Matrix4f model, int vertexCount, float pointSize) {
        if (pass == null) return UNAVAILABLE;
        int r = pass.queryVertex(mx, my, w, h, view, projection, model, vertexCount, pointSize);
        return r == ModelPickPass.PENDING ? retry(pass.lastVertex()) : r;
    }

    /** Hovered edge (the edge overlay's index) within {@code thresholdPixels}, -1, or {@link #UNAVAILABLE}. */
    public static int edge(float mx, float my, int w, int h, Matrix4f view, Matrix4f projection,
                           Matrix4f model, int edgeCount, float thresholdPixels) {
        if (pass == null) return UNAVAILABLE;
        int r = pass.queryEdge(mx, my, w, h, view, projection, model, edgeCount, thresholdPixels);
        return r == ModelPickPass.PENDING ? retry(pass.lastEdge()) : r;
    }

    /** A triangle of the hovered face (the face overlay maps it to the face), -1, or {@link #UNAVAILABLE}. */
    public static int triangle(float mx, float my, int w, int h, Matrix4f view, Matrix4f projection,
                               Matrix4f model, int triangleCount) {
        if (pass == null) return UNAVAILABLE;
        int r = pass.queryTriangle(mx, my, w, h, view, projection, model, triangleCount);
        return r == ModelPickPass.PENDING ? retry(pass.lastTriangle()) : r;
    }

    /** The visible surface point (MODEL space) under the cursor into {@code out}: 1 hit, -1 miss, or UNAVAILABLE. */
    public static int surface(float mx, float my, int w, int h, Matrix4f view, Matrix4f projection,
                              Matrix4f model, Vector3f out) {
        if (pass == null) return UNAVAILABLE;
        int r = pass.querySurface(mx, my, w, h, view, projection, model, out);
        return r == ModelPickPass.PENDING ? pass.lastSurface(out) : r;
    }

    /**
     * On a click: when the last query is still waiting for its window, render and read it now (a stall, clicks
     * only). True when it ran - ask again to get the fresh answer.
     */
    public static boolean resolveForClick() {
        return pass != null && pass.resolveNow();
    }

    private static int retry(int lastAnswer) {
        HoverQueryGate.retryCurrentPick();
        return lastAnswer;
    }
}
