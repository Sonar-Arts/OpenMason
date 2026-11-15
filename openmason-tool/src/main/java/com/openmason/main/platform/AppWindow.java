package com.openmason.main.platform;

import com.openmason.engine.platform.window.Window;
import com.openmason.engine.platform.window.WindowSpec;
import com.openmason.main.omConfig;

import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * The tool's main window over the engine's {@link Window} (#96): what is Open Mason's here is the
 * spec (title, GL 3.3 core forward-compatible, size limits, the last session's size with a default
 * fallback, VSync from {@link omConfig}), the icon, size persistence and close interception. GL
 * context creation, centring, framebuffer/viewport tracking and the input chain are the engine's.
 */
public final class AppWindow {

    private static final String APP_TITLE = "Open Mason Toolset";
    private static final int MIN_WIDTH = 1200;
    private static final int MIN_HEIGHT = 800;
    private static final int DEFAULT_WIDTH = 1600;
    private static final int DEFAULT_HEIGHT = 1000;

    private final omConfig omConfig;
    private final Runnable closeRequestHandler;
    private Window window;

    /**
     * @param omConfig            source of the last window size / vsync setting; receives size updates
     * @param closeRequestHandler invoked when the OS asks the window to close (the request is
     *                            intercepted so the owner can confirm unsaved changes)
     */
    public AppWindow(omConfig omConfig, Runnable closeRequestHandler) {
        this.omConfig = omConfig;
        this.closeRequestHandler = closeRequestHandler;
    }

    /** Native GLFW window handle ({@code NULL} before {@link #create()} / after {@link #destroy()}). */
    public long handle() {
        return window != null ? window.handle() : NULL;
    }

    /** The engine window, or null before {@link #create()}. */
    public Window window() {
        return window;
    }

    /** Creates the window and its GL context and shows it. */
    public void create() {
        int lastWidth = omConfig.getLastWindowWidth();
        int lastHeight = omConfig.getLastWindowHeight();
        WindowSpec spec = WindowSpec.builder(APP_TITLE,
                        lastWidth > 0 ? lastWidth : DEFAULT_WIDTH,
                        lastHeight > 0 ? lastHeight : DEFAULT_HEIGHT)
                .fallbackSize(DEFAULT_WIDTH, DEFAULT_HEIGHT)
                .minSize(MIN_WIDTH, MIN_HEIGHT)
                .gl(3, 3, WindowSpec.GlProfile.CORE)
                .forwardCompat(true)
                .swapInterval(omConfig.isVSyncEnabled() ? 1 : 0)
                .onCreated(WindowIcon::apply)
                .build();
        window = new Window(spec);
        window.setCloseHandler(closeRequestHandler);
        window.addWindowSizeListener((width, height) -> {
            omConfig.setLastWindowSize(width, height, window.isMaximized());
            omConfig.saveConfiguration();
        });
        window.create();
        ToolInputTap.install(window.input()); // before ImGui, which chains to the engine callbacks

        // GLFW posts the Windows taskbar-icon update as a window message. If the event loop is
        // not pumped within ~500ms of glfwSetWindowIcon, Windows drops the taskbar update (the
        // synchronous title-bar icon still applies). Heavy init (ImGui/Skija/UI) runs before the
        // main loop's first poll, so pump events now to flush the taskbar update. See GLFW #2753.
        glfwPollEvents();
    }

    /** Destroy the native window if it exists; safe to call repeatedly. */
    public void destroy() {
        if (window != null) {
            window.destroy();
        }
    }
}
