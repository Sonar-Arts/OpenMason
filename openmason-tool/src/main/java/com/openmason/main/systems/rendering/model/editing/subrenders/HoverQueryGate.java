package com.openmason.main.systems.rendering.model.editing.subrenders;

import com.openmason.main.systems.rendering.model.editing.picking.ModelPicking;
import org.joml.Matrix4f;

/**
 * Skips a hover pick whose inputs did not change (#175): the controllers ask for hover every frame, but the answer
 * only changes when the mouse, the camera / model matrices, the viewport or the picked geometry changed. Each
 * overlay keeps one gate and bumps {@link #invalidate()} when its geometry changes.
 *
 * <p>GPU picking (#175 F4) adds two inputs: the {@link ModelPicking#epoch() picking epoch} (a readback landed or the
 * drawn model changed, so the answer may be new) and {@link #retryCurrentPick()}, which a detector calls when its GPU
 * answer is still a frame away so the same inputs are picked again next frame.
 */
public final class HoverQueryGate {

    /** The gate whose pick is running (detectors run right after {@link #shouldPick} returned true). */
    private static HoverQueryGate current;

    private boolean valid;
    private float mouseX, mouseY;
    private int width, height;
    private long epoch;
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f model = new Matrix4f();

    /**
     * True when the pick must run (and remembers these inputs); false when they equal the last pick's inputs and
     * nothing was invalidated since.
     */
    public boolean shouldPick(float mx, float my, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                              Matrix4f modelMatrix, int viewportWidth, int viewportHeight) {
        if (viewMatrix == null || projectionMatrix == null || modelMatrix == null) {
            valid = false;
            return true;
        }
        long pickingEpoch = ModelPicking.epoch();
        if (valid && mx == mouseX && my == mouseY && viewportWidth == width && viewportHeight == height
                && pickingEpoch == epoch
                && view.equals(viewMatrix) && projection.equals(projectionMatrix) && model.equals(modelMatrix)) {
            return false;
        }
        valid = true;
        mouseX = mx;
        mouseY = my;
        width = viewportWidth;
        height = viewportHeight;
        epoch = pickingEpoch;
        view.set(viewMatrix);
        projection.set(projectionMatrix);
        model.set(modelMatrix);
        current = this;
        return true;
    }

    /** The next {@link #shouldPick} runs the pick (geometry, mode or enablement changed). */
    public void invalidate() {
        valid = false;
    }

    /** The running pick's answer is provisional (GPU readback pending): pick the same inputs again next frame. */
    public static void retryCurrentPick() {
        if (current != null) {
            current.valid = false;
        }
    }
}
