package com.openmason.engine.input;

/**
 * Mouse-look deltas from absolute cursor positions (#96, from Stonebreak's
 * {@code MouseCaptureManager}): the first move after a (re)capture only re-anchors, so the camera
 * never jumps; moves while look is inactive keep the anchor current for the same reason; the Y
 * delta is flipped (screen Y grows downwards, pitch up is positive) and both are scaled by the
 * sensitivity. Pure math; capturing the cursor is the window's job.
 */
public final class PointerLook {

    /** Receives one look delta, already scaled. */
    @FunctionalInterface
    public interface Sink {
        void look(float dx, float dy);
    }

    private float lastX;
    private float lastY;
    private boolean firstMove = true;
    private float sensitivity;

    public PointerLook(float sensitivity) {
        this.sensitivity = sensitivity;
    }

    public float sensitivity() {
        return sensitivity;
    }

    public void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity;
    }

    /** Anchors at {@code (x, y)} and swallows the next move (call on capture/restore). */
    public void reanchor(double x, double y) {
        lastX = (float) x;
        lastY = (float) y;
        firstMove = true;
    }

    /** Swallows the next move without changing the anchor. */
    public void skipNextMove() {
        firstMove = true;
    }

    /**
     * Feeds a cursor position. While {@code active} (captured and the host wants look), the delta
     * since the last position goes to {@code sink}; otherwise only the anchor moves.
     *
     * @return true when a delta was delivered
     */
    public boolean move(double x, double y, boolean active, Sink sink) {
        float mx = (float) x;
        float my = (float) y;
        if (!active || firstMove) {
            lastX = mx;
            lastY = my;
            if (active) {
                firstMove = false;
            }
            return false;
        }
        float dx = (mx - lastX) * sensitivity;
        float dy = (lastY - my) * sensitivity;
        lastX = mx;
        lastY = my;
        sink.look(dx, dy);
        return true;
    }
}
