package com.openmason.main.systems.rendering.model.editing.subrenders.vertex;

import com.openmason.main.systems.rendering.model.editing.picking.ModelPicking;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects the hovered vertex: from the viewport's GPU ID buffer when it can answer ({@link ModelPicking}, #175 F4 -
 * visible vertices only), else by projecting positions to screen space and checking distance against the vertex
 * point size.
 */
public final class VertexHoverDetector {

    private static final Logger logger = LoggerFactory.getLogger(VertexHoverDetector.class);

    private VertexHoverDetector() {
        throw new AssertionError("VertexHoverDetector is a utility class and should not be instantiated");
    }

    /**
     * Returns the index of the hovered vertex, or -1 if none.
     *
     * @param mouseX Mouse X coordinate in viewport space
     * @param mouseY Mouse Y coordinate in viewport space
     * @param viewportWidth Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @param viewMatrix Camera view matrix
     * @param projectionMatrix Camera projection matrix
     * @param modelMatrix Model transformation matrix (transforms vertices from model space to world space)
     * @param vertexPositions Vertex positions in MODEL SPACE
     * @param vertexCount Number of vertices
     * @param pointSize Point size in pixels for hit testing
     * @return Index of hovered vertex, or -1 if none
     */
    public static int detectHoveredVertex(float mouseX, float mouseY,
                                         int viewportWidth, int viewportHeight,
                                         Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                         Matrix4f modelMatrix,
                                         float[] vertexPositions, int vertexCount,
                                         float pointSize) {
        if (viewMatrix != null && projectionMatrix != null && modelMatrix != null && vertexCount > 0
                && viewportWidth > 0 && viewportHeight > 0 && pointSize > 0.0f) {
            // GPU ID buffer first (#175 F4): occlusion-correct, follows the displayed (skinned / posed) mesh
            int gpu = ModelPicking.vertex(mouseX, mouseY, viewportWidth, viewportHeight, viewMatrix,
                    projectionMatrix, modelMatrix, vertexCount, pointSize);
            if (gpu != ModelPicking.UNAVAILABLE) {
                return gpu;
            }
        }
        return detectHoveredVertexCpu(mouseX, mouseY, viewportWidth, viewportHeight, viewMatrix, projectionMatrix,
                modelMatrix, vertexPositions, vertexCount, pointSize);
    }

    /**
     * The CPU test (fallback without GPU picking): every vertex projected, the nearest-depth one whose point radius
     * covers the mouse. Not occlusion-aware. Same parameters and result as {@link #detectHoveredVertex}.
     */
    public static int detectHoveredVertexCpu(float mouseX, float mouseY,
                                             int viewportWidth, int viewportHeight,
                                             Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                             Matrix4f modelMatrix,
                                             float[] vertexPositions, int vertexCount,
                                             float pointSize) {
        // Validate inputs
        if (viewMatrix == null || projectionMatrix == null || modelMatrix == null) {
            return -1;
        }

        if (vertexPositions == null || vertexCount == 0) {
            return -1;
        }

        if (viewportWidth <= 0 || viewportHeight <= 0) {
            return -1;
        }

        if (pointSize <= 0.0f) {
            return -1;
        }

        try {
            // Create MVP matrix for projection: projection * view * model
            // This transforms vertices from model space → world space → view space → clip space
            Matrix4f mvpMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix).mul(modelMatrix);

            // Track closest vertex (by depth)
            int closestVertexIndex = -1;
            float closestDepth = Float.POSITIVE_INFINITY;

            // Point size radius in pixels (half the point size)
            float radiusPixels = pointSize / 2.0f;

            // Test each vertex
            Vector4f modelPos = new Vector4f();
            Vector4f clipPos = new Vector4f(); // reused: no allocation per vertex (#175)
            for (int i = 0; i < vertexCount; i++) {
                int posIndex = i * 3;

                // Get vertex position in MODEL SPACE
                modelPos.set(
                    vertexPositions[posIndex + 0],
                    vertexPositions[posIndex + 1],
                    vertexPositions[posIndex + 2],
                    1.0f
                );

                // Transform from model space to clip space using MVP matrix
                mvpMatrix.transform(modelPos, clipPos);

                // Check if behind camera
                if (clipPos.w <= 0) {
                    continue;
                }

                // Convert to NDC
                float ndcX = clipPos.x / clipPos.w;
                float ndcY = clipPos.y / clipPos.w;
                float depth = clipPos.z / clipPos.w;

                // Convert NDC to screen space
                float screenX = (ndcX + 1.0f) * 0.5f * viewportWidth;
                float screenY = (1.0f - ndcY) * 0.5f * viewportHeight; // Flip Y

                // Calculate distance from mouse to projected vertex
                float dx = mouseX - screenX;
                float dy = mouseY - screenY;
                float distancePixels = (float) Math.sqrt(dx * dx + dy * dy);

                // Check if mouse is within point radius
                if (distancePixels <= radiusPixels && depth < closestDepth) {
                    closestDepth = depth;
                    closestVertexIndex = i;
                }
            }

            return closestVertexIndex;

        } catch (Exception e) {
            logger.error("Error detecting hovered vertex", e);
            return -1;
        }
    }
}
