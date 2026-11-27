package com.openmason.main.systems.rendering.model.editing.subrenders.edge;

import com.openmason.engine.rendering.viewer.math.ScreenProjectionUtil;
import com.openmason.main.systems.rendering.model.editing.picking.ModelPicking;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for detecting edge hover interactions.
 * Mirrors VertexHoverDetector pattern but for line segments: the viewport's GPU ID buffer answers when it can
 * ({@link ModelPicking}, #175 F4 - visible edges only), else every edge is projected on the CPU.
 */
public final class EdgeHoverDetector {

    private static final Logger logger = LoggerFactory.getLogger(EdgeHoverDetector.class);

    /**
     * Result of an edge hit test that includes the parametric position along the edge.
     *
     * @param edgeIndex Index of the hit edge, or -1 if no hit
     * @param t Parametric position along the edge (0 = start, 1 = end)
     * @param screenDistance Screen-space distance from mouse to edge in pixels
     */
    public record EdgeHitResult(int edgeIndex, float t, float screenDistance) {
        public static final EdgeHitResult NONE = new EdgeHitResult(-1, 0f, Float.MAX_VALUE);

        public boolean isHit() {
            return edgeIndex >= 0;
        }
    }

    /**
     * Internal result from point-to-line-segment distance calculation.
     */

    // Private constructor to prevent instantiation (utility class)
    private EdgeHoverDetector() {
        throw new AssertionError("EdgeHoverDetector is a utility class and should not be instantiated");
    }

    /**
     * Detects which edge (if any) is currently hovered by the mouse.
     * Uses screen-space point-to-line distance with line width as threshold.
     *
     * @param mouseX Mouse X coordinate in viewport space
     * @param mouseY Mouse Y coordinate in viewport space
     * @param viewportWidth Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @param viewMatrix Camera view matrix
     * @param projectionMatrix Camera projection matrix
     * @param modelMatrix Model transformation matrix (transforms edges from model space to world space)
     * @param edgePositions Edge positions in MODEL SPACE (6 floats per edge: x1,y1,z1, x2,y2,z2)
     * @param edgeCount Number of edges
     * @param lineWidth Line width in pixels for hit testing
     * @return Index of hovered edge, or -1 if none
     */
    public static int detectHoveredEdge(float mouseX, float mouseY,
                                       int viewportWidth, int viewportHeight,
                                       Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                       Matrix4f modelMatrix,
                                       float[] edgePositions, int edgeCount,
                                       float lineWidth) {
        int gpu = gpuEdge(mouseX, mouseY, viewportWidth, viewportHeight, viewMatrix, projectionMatrix, modelMatrix,
                edgePositions, edgeCount, lineWidth);
        if (gpu != ModelPicking.UNAVAILABLE) {
            return gpu;
        }
        return detectHoveredEdgeCpu(mouseX, mouseY, viewportWidth, viewportHeight, viewMatrix, projectionMatrix,
                modelMatrix, edgePositions, edgeCount, lineWidth);
    }

    /**
     * The GPU ID buffer's edge (#175 F4): the visible edge pixel nearest the mouse within the hit threshold
     * ({@code lineWidth * 2}), -1, or {@link ModelPicking#UNAVAILABLE} (then the CPU test runs).
     */
    private static int gpuEdge(float mouseX, float mouseY, int viewportWidth, int viewportHeight,
                               Matrix4f viewMatrix, Matrix4f projectionMatrix, Matrix4f modelMatrix,
                               float[] edgePositions, int edgeCount, float lineWidth) {
        if (viewMatrix == null || projectionMatrix == null || modelMatrix == null || edgePositions == null
                || edgeCount <= 0 || viewportWidth <= 0 || viewportHeight <= 0 || lineWidth <= 0.0f) {
            return ModelPicking.UNAVAILABLE;
        }
        return ModelPicking.edge(mouseX, mouseY, viewportWidth, viewportHeight, viewMatrix, projectionMatrix,
                modelMatrix, edgeCount, lineWidth * 2.0f);
    }

    /**
     * The CPU test (fallback without GPU picking): every edge projected, the nearest by screen distance within
     * {@code lineWidth * 2} (depth breaks near-ties). Not occlusion-aware. Same contract as
     * {@link #detectHoveredEdge}.
     */
    public static int detectHoveredEdgeCpu(float mouseX, float mouseY,
                                          int viewportWidth, int viewportHeight,
                                          Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                          Matrix4f modelMatrix,
                                          float[] edgePositions, int edgeCount,
                                          float lineWidth) {
        // Validate inputs
        if (viewMatrix == null || projectionMatrix == null || modelMatrix == null) {
            return -1;
        }

        if (edgePositions == null || edgeCount == 0) {
            return -1;
        }

        if (viewportWidth <= 0 || viewportHeight <= 0) {
            return -1;
        }

        if (lineWidth <= 0.0f) {
            return -1;
        }

        try {
            // Create MVP matrix for projection: projection * view * model
            // This transforms edges from model space → world space → view space → clip space
            Matrix4f mvpMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix).mul(modelMatrix);

            // Track closest edge (by distance)
            int closestEdgeIndex = -1;
            float closestDistance = Float.POSITIVE_INFINITY;
            float closestDepth = Float.POSITIVE_INFINITY;

            // Line width threshold (slightly larger for easier selection)
            float thresholdPixels = lineWidth * 2.0f;

            float[] scratch = new float[6];
            Vector4f clip = new Vector4f();
            for (int i = 0; i < edgeCount; i++) {
                int posIndex = i * 6; // Each edge has 2 endpoints × 3 coords = 6 floats

                // Project both endpoints from MODEL SPACE to screen space (with NDC depth), no allocation
                if (!project(edgePositions, posIndex, mvpMatrix, viewportWidth, viewportHeight, clip, scratch, 0)
                        || !project(edgePositions, posIndex + 3, mvpMatrix, viewportWidth, viewportHeight,
                                clip, scratch, 3)) {
                    continue; // an endpoint is behind the camera
                }

                float distance = pointToSegment(mouseX, mouseY, scratch[0], scratch[1], scratch[3], scratch[4],
                        null);
                float avgDepth = (scratch[2] + scratch[5]) * 0.5f;

                if (distance <= thresholdPixels) {
                    // Prefer closer edges when distances are similar
                    if (distance < closestDistance - 0.5f ||
                        (Math.abs(distance - closestDistance) < 0.5f && avgDepth < closestDepth)) {
                        closestDistance = distance;
                        closestDepth = avgDepth;
                        closestEdgeIndex = i;
                    }
                }
            }

            return closestEdgeIndex;

        } catch (Exception e) {
            logger.error("Error detecting hovered edge", e);
            return -1;
        }
    }

    /**
     * Projects one model-space point (3 floats of {@code src} at {@code at}) to (screenX, screenY, ndcDepth) in
     * {@code out[o..o+2]} with exactly {@link ScreenProjectionUtil#projectToScreenWithDepth}'s math, reusing
     * {@code clip}. False when the point is behind the camera.
     */
    private static boolean project(float[] src, int at, Matrix4f mvp, int width, int height,
                                   Vector4f clip, float[] out, int o) {
        clip.set(src[at], src[at + 1], src[at + 2], 1.0f);
        mvp.transform(clip);
        if (clip.w <= 0) {
            return false;
        }
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        out[o] = (ndcX + 1.0f) * 0.5f * width;
        out[o + 1] = (1.0f - ndcY) * 0.5f * height;
        out[o + 2] = clip.z / clip.w;
        return true;
    }

    /** Screen distance from (px, py) to the segment, clamped to its ends; {@code tOut[0]} gets the segment parameter if given. */
    private static float pointToSegment(float px, float py, float x1, float y1, float x2, float y2, float[] tOut) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float lengthSquared = dx * dx + dy * dy;
        if (lengthSquared < 0.0001f) {
            float dpx = px - x1;
            float dpy = py - y1;
            if (tOut != null) tOut[0] = 0f;
            return (float) Math.sqrt(dpx * dpx + dpy * dpy);
        }
        float t = ((px - x1) * dx + (py - y1) * dy) / lengthSquared;
        t = Math.max(0.0f, Math.min(1.0f, t));
        float distX = px - (x1 + t * dx);
        float distY = py - (y1 + t * dy);
        if (tOut != null) tOut[0] = t;
        return (float) Math.sqrt(distX * distX + distY * distY);
    }

    /**
     * Detects which edge is hovered and returns the parametric position along it.
     * Same logic as {@link #detectHoveredEdge} but returns an {@link EdgeHitResult}
     * including the {@code t} parameter for precise cut placement.
     *
     * @param mouseX Mouse X coordinate in viewport space
     * @param mouseY Mouse Y coordinate in viewport space
     * @param viewportWidth Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @param viewMatrix Camera view matrix
     * @param projectionMatrix Camera projection matrix
     * @param modelMatrix Model transformation matrix
     * @param edgePositions Edge positions in MODEL SPACE (6 floats per edge)
     * @param edgeCount Number of edges
     * @param lineWidth Line width in pixels for hit testing
     * @return EdgeHitResult with edge index, t parameter, and screen distance
     */
    public static EdgeHitResult detectHoveredEdgeWithParameter(
            float mouseX, float mouseY,
            int viewportWidth, int viewportHeight,
            Matrix4f viewMatrix, Matrix4f projectionMatrix,
            Matrix4f modelMatrix,
            float[] edgePositions, int edgeCount,
            float lineWidth) {

        if (viewMatrix == null || projectionMatrix == null || modelMatrix == null) {
            return EdgeHitResult.NONE;
        }
        if (edgePositions == null || edgeCount == 0) {
            return EdgeHitResult.NONE;
        }
        if (viewportWidth <= 0 || viewportHeight <= 0 || lineWidth <= 0.0f) {
            return EdgeHitResult.NONE;
        }

        int gpu = gpuEdge(mouseX, mouseY, viewportWidth, viewportHeight, viewMatrix, projectionMatrix, modelMatrix,
                edgePositions, edgeCount, lineWidth);
        if (gpu != ModelPicking.UNAVAILABLE) {
            return parameterOf(gpu, mouseX, mouseY, viewportWidth, viewportHeight, viewMatrix, projectionMatrix,
                    modelMatrix, edgePositions, edgeCount);
        }
        return detectHoveredEdgeWithParameterCpu(mouseX, mouseY, viewportWidth, viewportHeight, viewMatrix,
                projectionMatrix, modelMatrix, edgePositions, edgeCount, lineWidth);
    }

    /** The GPU-picked edge's {@code t} and screen distance: its two endpoints projected (O(1)). */
    private static EdgeHitResult parameterOf(int edge, float mouseX, float mouseY, int viewportWidth,
                                             int viewportHeight, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                             Matrix4f modelMatrix, float[] edgePositions, int edgeCount) {
        if (edge < 0 || edge >= edgeCount || edge * 6 + 5 >= edgePositions.length) {
            return EdgeHitResult.NONE;
        }
        Matrix4f mvp = new Matrix4f(projectionMatrix).mul(viewMatrix).mul(modelMatrix);
        float[] scratch = new float[6];
        float[] tOut = new float[1];
        Vector4f clip = new Vector4f();
        if (!project(edgePositions, edge * 6, mvp, viewportWidth, viewportHeight, clip, scratch, 0)
                || !project(edgePositions, edge * 6 + 3, mvp, viewportWidth, viewportHeight, clip, scratch, 3)) {
            return new EdgeHitResult(edge, 0f, 0f);
        }
        float distance = pointToSegment(mouseX, mouseY, scratch[0], scratch[1], scratch[3], scratch[4], tOut);
        return new EdgeHitResult(edge, tOut[0], distance);
    }

    /** The CPU twin of {@link #detectHoveredEdgeWithParameter} (fallback without GPU picking). */
    public static EdgeHitResult detectHoveredEdgeWithParameterCpu(
            float mouseX, float mouseY,
            int viewportWidth, int viewportHeight,
            Matrix4f viewMatrix, Matrix4f projectionMatrix,
            Matrix4f modelMatrix,
            float[] edgePositions, int edgeCount,
            float lineWidth) {
        if (viewMatrix == null || projectionMatrix == null || modelMatrix == null
                || edgePositions == null || edgeCount == 0
                || viewportWidth <= 0 || viewportHeight <= 0 || lineWidth <= 0.0f) {
            return EdgeHitResult.NONE;
        }

        try {
            Matrix4f mvpMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix).mul(modelMatrix);

            int closestEdgeIndex = -1;
            float closestDistance = Float.POSITIVE_INFINITY;
            float closestDepth = Float.POSITIVE_INFINITY;
            float closestT = 0f;

            float thresholdPixels = lineWidth * 2.0f;

            float[] scratch = new float[6];
            float[] tOut = new float[1];
            Vector4f clip = new Vector4f();
            for (int i = 0; i < edgeCount; i++) {
                int posIndex = i * 6;

                if (!project(edgePositions, posIndex, mvpMatrix, viewportWidth, viewportHeight, clip, scratch, 0)
                        || !project(edgePositions, posIndex + 3, mvpMatrix, viewportWidth, viewportHeight,
                                clip, scratch, 3)) {
                    continue;
                }

                float distance = pointToSegment(mouseX, mouseY, scratch[0], scratch[1], scratch[3], scratch[4],
                        tOut);
                float avgDepth = (scratch[2] + scratch[5]) * 0.5f;

                if (distance <= thresholdPixels) {
                    if (distance < closestDistance - 0.5f ||
                        (Math.abs(distance - closestDistance) < 0.5f && avgDepth < closestDepth)) {
                        closestDistance = distance;
                        closestDepth = avgDepth;
                        closestEdgeIndex = i;
                        closestT = tOut[0];
                    }
                }
            }

            if (closestEdgeIndex >= 0) {
                return new EdgeHitResult(closestEdgeIndex, closestT, closestDistance);
            }
            return EdgeHitResult.NONE;

        } catch (Exception e) {
            logger.error("Error detecting hovered edge with parameter", e);
            return EdgeHitResult.NONE;
        }
    }
}
