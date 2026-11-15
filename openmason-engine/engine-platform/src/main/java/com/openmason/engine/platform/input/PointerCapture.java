package com.openmason.engine.platform.input;

import com.openmason.engine.input.PointerLook;
import com.openmason.engine.platform.window.DisplayBackend;
import com.openmason.engine.platform.window.Window;

import static org.lwjgl.glfw.GLFW.GLFW_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_NORMAL;
import static org.lwjgl.glfw.GLFW.GLFW_RAW_MOUSE_MOTION;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.glfwFocusWindow;
import static org.lwjgl.glfw.GLFW.glfwRawMouseMotionSupported;
import static org.lwjgl.glfw.GLFW.glfwRequestWindowAttention;
import static org.lwjgl.glfw.GLFW.glfwSetInputMode;

/**
 * The mechanics of a captured mouse for look controls (#96, from Stonebreak's
 * {@code MouseCaptureManager}): hiding and locking the cursor with raw (unaccelerated) motion,
 * asking for focus first, a temporary release on focus loss, and jump-free deltas
 * ({@link PointerLook}). <b>Whether</b> to capture is the host's decision ({@link #setCaptured}).
 */
public final class PointerCapture {

    private final Window window;
    private final PointerLook look;
    private boolean captured;

    public PointerCapture(Window window, float sensitivity) {
        this.window = window;
        this.look = new PointerLook(sensitivity);
    }

    public boolean isCaptured() {
        return captured;
    }

    /** Captures or releases to match {@code capture}; no-op when already there. */
    public void setCaptured(boolean capture) {
        if (capture && !captured) {
            capture();
        } else if (!capture && captured) {
            release();
        }
    }

    /** Hides and locks the cursor (requesting focus first) and re-anchors the look deltas. */
    public void capture() {
        long handle = window.handle();
        if (handle == 0) {
            return;
        }
        if (!window.isFocused()) {
            glfwRequestWindowAttention(handle);
            // Wayland compositors do not let clients steal focus (glfwFocusWindow would only emit
            // GLFW_FEATURE_UNAVAILABLE); the attention request is the supported path there.
            if (!DisplayBackend.isWayland()) {
                glfwFocusWindow(handle);
                try {
                    Thread.sleep(50); // let the focus change take effect
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        glfwSetInputMode(handle, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        // Unfiltered deltas keep look feel consistent across backends (Wayland would otherwise
        // deliver the accelerated relative-pointer stream).
        if (glfwRawMouseMotionSupported()) {
            glfwSetInputMode(handle, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);
        }
        captured = true;
        double[] pos = window.cursorPos();
        look.reanchor(pos[0], pos[1]);
    }

    /** Shows the cursor again. */
    public void release() {
        if (window.handle() != 0) {
            glfwSetInputMode(window.handle(), GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        }
        captured = false;
        look.skipNextMove();
    }

    /** Shows the cursor but stays logically captured (focus loss, Alt+Tab); see {@link #restoreCapture}. */
    public void temporaryRelease() {
        if (captured && window.handle() != 0) {
            glfwSetInputMode(window.handle(), GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        }
    }

    /** Re-locks the cursor after {@link #temporaryRelease}, without a look jump. */
    public void restoreCapture() {
        if (captured && window.handle() != 0) {
            glfwSetInputMode(window.handle(), GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            look.skipNextMove();
        }
    }

    /**
     * Feeds a cursor position (window coordinates). Deltas reach {@code sink} only while captured and
     * {@code lookEnabled} (the host's veto, e.g. a chat box open over the game).
     *
     * @return true when a delta was delivered
     */
    public boolean onCursor(double x, double y, boolean lookEnabled, PointerLook.Sink sink) {
        return look.move(x, y, captured && lookEnabled && sink != null, sink);
    }

    public float sensitivity() {
        return look.sensitivity();
    }

    public void setSensitivity(float sensitivity) {
        look.setSensitivity(sensitivity);
    }
}
