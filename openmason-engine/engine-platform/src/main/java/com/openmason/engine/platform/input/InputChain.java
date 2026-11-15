package com.openmason.engine.platform.input;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.lwjgl.glfw.GLFW.glfwSetCharCallback;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback;
import static org.lwjgl.glfw.GLFW.glfwSetScrollCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowFocusCallback;

/**
 * One listener chain per window for its input callbacks (#96). GLFW holds a single callback of each
 * kind per window; the chain owns them and fans every event out to its listeners in registration
 * order, so several consumers (an editor and an embedded runtime, #83; a UI document preview) can
 * share a window. Installed when the window is created, before any other library (ImGui's GLFW
 * backend) installs its own callbacks; such a library chains to these, so both keep receiving
 * everything.
 *
 * <p>Keys arrive as raw GLFW tokens with their scancode; a listener that wants letter keys by their
 * layout label translates them itself ({@code Window.layoutKey}). Cursor positions are in window
 * coordinates; convert with {@code Window.toUiX/Y} for framebuffer pixels.
 */
public final class InputChain {

    /** Receives a window's input events; implement what you need. Runs on the GLFW main thread. */
    public interface Listener {
        default void key(int key, int scancode, int action, int mods) {
        }

        default void character(int codePoint) {
        }

        default void mouseButton(int button, int action, int mods) {
        }

        /** Cursor position in window (screen) coordinates. */
        default void cursor(double x, double y) {
        }

        default void scroll(double xOffset, double yOffset) {
        }

        default void focus(boolean focused) {
        }
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** Installs this chain's GLFW callbacks on {@code window}. */
    public void install(long window) {
        glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            for (Listener l : listeners) {
                l.key(key, scancode, action, mods);
            }
        });
        glfwSetCharCallback(window, (w, codePoint) -> {
            for (Listener l : listeners) {
                l.character(codePoint);
            }
        });
        glfwSetMouseButtonCallback(window, (w, button, action, mods) -> {
            for (Listener l : listeners) {
                l.mouseButton(button, action, mods);
            }
        });
        glfwSetCursorPosCallback(window, (w, x, y) -> {
            for (Listener l : listeners) {
                l.cursor(x, y);
            }
        });
        glfwSetScrollCallback(window, (w, dx, dy) -> {
            for (Listener l : listeners) {
                l.scroll(dx, dy);
            }
        });
        glfwSetWindowFocusCallback(window, (w, focused) -> {
            for (Listener l : listeners) {
                l.focus(focused);
            }
        });
    }

    public void add(Listener listener) {
        listeners.add(listener);
    }

    public void remove(Listener listener) {
        listeners.remove(listener);
    }

    /** Listeners in delivery order (tests, diagnostics). */
    public List<Listener> listeners() {
        return List.copyOf(listeners);
    }
}
