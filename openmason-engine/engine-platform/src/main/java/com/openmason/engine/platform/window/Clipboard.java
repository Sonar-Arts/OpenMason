package com.openmason.engine.platform.window;

import java.util.function.LongSupplier;

import com.openmason.engine.ui.masonry.MasonryEnvironment;

import static org.lwjgl.glfw.GLFW.glfwGetClipboardString;
import static org.lwjgl.glfw.GLFW.glfwSetClipboardString;

/**
 * A window's system clipboard (#96, from Stonebreak's {@code GlfwClipboard}), usable directly and as
 * Masonry's {@link MasonryEnvironment.ClipboardProvider} for text fields. GLFW clipboard calls need
 * the window handle and the main thread; both hold for input callbacks. Failures read as empty: a
 * missing clipboard never takes the host down.
 */
public final class Clipboard implements MasonryEnvironment.ClipboardProvider {

    private final LongSupplier window;

    /** @param window the window handle, read on every call (0 = no window yet: reads empty, writes nothing) */
    public Clipboard(LongSupplier window) {
        this.window = window;
    }

    @Override
    public String read() {
        try {
            long w = window.getAsLong();
            if (w != 0) {
                String s = glfwGetClipboardString(w);
                if (s != null) {
                    return s;
                }
            }
        } catch (Exception | UnsatisfiedLinkError ignored) {
            // see class doc
        }
        return "";
    }

    @Override
    public void write(String text) {
        if (text == null) {
            return;
        }
        try {
            long w = window.getAsLong();
            if (w != 0) {
                glfwSetClipboardString(w, text);
            }
        } catch (Exception | UnsatisfiedLinkError ignored) {
            // see class doc
        }
    }
}
