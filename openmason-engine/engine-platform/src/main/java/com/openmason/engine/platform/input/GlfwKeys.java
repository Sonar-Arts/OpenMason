package com.openmason.engine.platform.input;

import com.openmason.engine.input.InputCodes;
import com.openmason.engine.input.KeyEdges;
import com.openmason.engine.ui.runtime.input.LayoutKeys;
import org.lwjgl.glfw.GLFW;

import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.glfwGetKey;

/** Polled key state of a window, as the engine's GLFW-free {@link KeyEdges.KeySource} (#96). */
public final class GlfwKeys {

    private GlfwKeys() {
    }

    /** True while {@code key} is physically held in {@code window}. */
    public static boolean isDown(long window, int key) {
        return window != 0 && InputCodes.isKey(key) && glfwGetKey(window, key) == GLFW_PRESS;
    }

    /** {@link #isDown} bound to {@code window}. */
    public static KeyEdges.KeySource source(long window) {
        return key -> isDown(window, key);
    }

    /**
     * {@code key} mapped to the active keyboard layout's letter ({@link LayoutKeys}): Ctrl+Z on QWERTZ
     * is the key labelled Z. Main thread only.
     */
    public static int layoutKey(int key, int scancode) {
        return LayoutKeys.translate(key, scancode, GLFW::glfwGetKeyName);
    }
}
