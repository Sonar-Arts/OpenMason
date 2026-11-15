package com.openmason.engine.platform.window;

import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLDebugMessageCallback;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openmason.engine.platform.input.GlfwKeys;
import com.openmason.engine.platform.input.InputChain;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * A GLFW window with its GL context (#96, from Stonebreak's {@code GameWindow} and Open Mason's
 * {@code AppWindow}): creation from a {@link WindowSpec}, the size/scale/refresh-rate bookkeeping
 * everything else measures against, the window's {@link InputChain} and its {@link Clipboard}.
 *
 * <p><b>Two coordinate spaces.</b> {@code glfwGetCursorPos} reports window (screen) coordinates while
 * rendering and UI layout use framebuffer pixels: the same on Windows and non-HiDPI X11, different on
 * Wayland/HiDPI. {@link #width()}/{@link #height()} are framebuffer pixels; {@link #toUiX}/{@link #toUiY}
 * and {@link #uiCursorPos} convert cursor positions into that space.
 *
 * <p><b>Early resize.</b> GLFW can fire the framebuffer-size callback while the window is shown,
 * before the context is current (notably on Linux). GL calls there would abort the JVM, so the
 * viewport update is gated until the context is ready and the real size is adopted afterwards.
 *
 * <p><b>Refresh rate.</b> {@link #monitorRefreshHz()} is the rate of the monitor the window overlaps
 * most, kept current on moves and monitor hot-plug. Wayland never exposes window positions, so there
 * it is the fastest connected monitor: over-capping is harmless, capping a 144 Hz display at a 60 Hz
 * primary is not.
 *
 * <p>Lifecycle: construct, register listeners, {@link #create()}, use, {@link #destroy()}; then
 * {@link DisplayBackend#terminate()}. Main thread only.
 */
public final class Window {

    private static final Logger logger = LoggerFactory.getLogger(Window.class);

    /** Notified after the framebuffer size changes, once the viewport has been updated. */
    @FunctionalInterface
    public interface ResizeListener {
        void onFramebufferResized(int width, int height);
    }

    /** Notified when the window's size in screen coordinates changes. */
    @FunctionalInterface
    public interface WindowSizeListener {
        void onWindowResized(int width, int height);
    }

    private final WindowSpec spec;
    private final InputChain input = new InputChain();
    private final Clipboard clipboard = new Clipboard(this::handle);
    private final List<ResizeListener> resizeListeners = new CopyOnWriteArrayList<>();
    private final List<WindowSizeListener> sizeListeners = new CopyOnWriteArrayList<>();

    private long handle = NULL;
    private int width;
    private int height;
    private double cursorScaleX = 1.0;
    private double cursorScaleY = 1.0;
    private volatile boolean glReady;
    private int monitorRefreshHz = 60;
    private Runnable closeHandler;
    private GLDebugMessageCallback debugCallback;

    public Window(WindowSpec spec) {
        this.spec = spec;
        this.width = spec.width();
        this.height = spec.height();
    }

    public WindowSpec spec() {
        return spec;
    }

    /** Native GLFW handle; {@code 0} before {@link #create()} and after {@link #destroy()}. */
    public long handle() {
        return handle;
    }

    /** Framebuffer width in pixels. */
    public int width() {
        return width;
    }

    /** Framebuffer height in pixels. */
    public int height() {
        return height;
    }

    /** This window's input event chain. */
    public InputChain input() {
        return input;
    }

    /** This window's system clipboard (Masonry text fields, chat). */
    public Clipboard clipboard() {
        return clipboard;
    }

    public void addResizeListener(ResizeListener listener) {
        resizeListeners.add(listener);
    }

    public void removeResizeListener(ResizeListener listener) {
        resizeListeners.remove(listener);
    }

    public void addWindowSizeListener(WindowSizeListener listener) {
        sizeListeners.add(listener);
    }

    /**
     * Intercepts close requests (the title-bar button, Alt+F4): the window stays open and
     * {@code handler} decides (stop the loop, ask about unsaved changes). Null restores GLFW's default
     * of flagging {@link #shouldClose()}.
     */
    public void setCloseHandler(Runnable handler) {
        this.closeHandler = handler;
    }

    // ── creation ─────────────────────────────────────────────────────────────

    /**
     * Creates the window hidden, makes its GL context current, installs the callbacks and the input
     * chain, runs {@link WindowSpec#onCreated()}, and shows it. GLFW must already be initialized
     * ({@link DisplayBackend#initialize}).
     *
     * @throws IllegalStateException if no window can be created
     */
    public void create() {
        if (handle != NULL) {
            throw new IllegalStateException("window already created");
        }
        applyHints();
        handle = glfwCreateWindow(spec.width(), spec.height(), spec.title(), NULL, NULL);
        if (handle == NULL && spec.fallbackWidth() > 0 && spec.fallbackHeight() > 0) {
            logger.warn("[Display] Failed to create a {}x{} window; retrying at {}x{}",
                    spec.width(), spec.height(), spec.fallbackWidth(), spec.fallbackHeight());
            handle = glfwCreateWindow(spec.fallbackWidth(), spec.fallbackHeight(), spec.title(), NULL, NULL);
        }
        if (handle == NULL) {
            throw new IllegalStateException("Failed to create the GLFW window");
        }
        if (spec.minWidth() > 0 || spec.minHeight() > 0) {
            glfwSetWindowSizeLimits(handle,
                    spec.minWidth() > 0 ? spec.minWidth() : GLFW_DONT_CARE,
                    spec.minHeight() > 0 ? spec.minHeight() : GLFW_DONT_CARE,
                    GLFW_DONT_CARE, GLFW_DONT_CARE);
        }

        installWindowCallbacks();
        input.install(handle);
        if (spec.center()) {
            center();
        }

        // Capture the refresh rate of the monitor the window sits on, and keep it current as
        // monitors are hot-plugged.
        refreshMonitorHz();
        glfwSetMonitorCallback((monitor, event) -> refreshMonitorHz());

        glfwMakeContextCurrent(handle);
        glfwSwapInterval(spec.swapInterval());
        if (spec.onCreated() != null) {
            spec.onCreated().accept(handle);
        }
        glfwShowWindow(handle);

        // LWJGL needs the context current on this thread to create its GLCapabilities.
        GL.createCapabilities();
        glReady = true;
        if (spec.debugContext()) {
            installDebugOutput();
        }

        adoptActualFramebufferSize();
        updateCursorScale();
    }

    private void applyHints() {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, spec.resizable() ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, spec.glMajor());
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, spec.glMinor());
        glfwWindowHint(GLFW_OPENGL_PROFILE, spec.profile() == WindowSpec.GlProfile.CORE
                ? GLFW_OPENGL_CORE_PROFILE : GLFW_OPENGL_COMPAT_PROFILE);
        if (spec.forwardCompat()) {
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        }
        if (spec.debugContext()) {
            glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);
        }
        if (spec.waylandAppId() != null) {
            // Wayland identifies applications by app_id; ignored on other platforms.
            glfwWindowHintString(GLFW_WAYLAND_APP_ID, spec.waylandAppId());
        }
    }

    private void installWindowCallbacks() {
        glfwSetFramebufferSizeCallback(handle, (w, fbw, fbh) -> onFramebufferResized(fbw, fbh));
        // Window (screen-coordinate) size can change independently of the framebuffer on
        // Wayland/HiDPI; keep the cursor-to-UI scale in sync.
        glfwSetWindowSizeCallback(handle, (w, ww, wh) -> {
            updateCursorScale();
            for (WindowSizeListener l : sizeListeners) {
                l.onWindowResized(ww, wh);
            }
        });
        // A moved window may sit on a different monitor (never fires on Wayland).
        glfwSetWindowPosCallback(handle, (w, x, y) -> refreshMonitorHz());
        glfwSetWindowCloseCallback(handle, w -> {
            Runnable handler = closeHandler;
            if (handler != null) {
                glfwSetWindowShouldClose(w, false);
                handler.run();
            }
        });
    }

    /**
     * Driver debug messages on stderr, synchronous so a stack trace points at the GL call. Notifications
     * are dropped (NVIDIA reports every buffer placement). Contexts without GL 4.3 / KHR_debug get
     * LWJGL's generic callback instead.
     */
    private void installDebugOutput() {
        GLCapabilities caps = GL.getCapabilities();
        if (caps.OpenGL43 || caps.GL_KHR_debug) {
            GL11.glEnable(GL43.GL_DEBUG_OUTPUT);
            GL11.glEnable(GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS);
            debugCallback = GLDebugMessageCallback.create((source, type, id, severity, length, message, user) -> {
                if (severity == GL43.GL_DEBUG_SEVERITY_NOTIFICATION) {
                    return;
                }
                System.err.println("[gldebug] " + GLDebugMessageCallback.getMessage(length, message));
                new Throwable("[gldebug] GL call").printStackTrace(System.err);
            });
            GL43.glDebugMessageCallback(debugCallback, 0L);
        } else {
            GLUtil.setupDebugMessageCallback(System.err);
        }
    }

    /**
     * Centres the window on the primary monitor's work area (panels and taskbars excluded). The
     * primary monitor is not necessarily at virtual-screen origin, so its work-area origin anchors the
     * position; falls back to the video mode at the monitor position when the work area is empty
     * (some drivers, XWayland). A no-op on Wayland, where clients cannot position windows.
     */
    private void center() {
        if (DisplayBackend.isWayland()) {
            return;
        }
        long monitor = glfwGetPrimaryMonitor();
        if (monitor == NULL) {
            logger.warn("[Display] No primary monitor reported; leaving the window at its default position");
            return;
        }
        try (MemoryStack stack = stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            glfwGetWindowSize(handle, w, h);

            IntBuffer ax = stack.mallocInt(1);
            IntBuffer ay = stack.mallocInt(1);
            IntBuffer aw = stack.mallocInt(1);
            IntBuffer ah = stack.mallocInt(1);
            glfwGetMonitorWorkarea(monitor, ax, ay, aw, ah);
            int originX = ax.get(0);
            int originY = ay.get(0);
            int monW = aw.get(0);
            int monH = ah.get(0);
            if (monW <= 0 || monH <= 0) {
                GLFWVidMode mode = glfwGetVideoMode(monitor);
                if (mode == null) {
                    logger.warn("[Display] Primary monitor has no video mode; leaving the window uncentred");
                    return;
                }
                monW = mode.width();
                monH = mode.height();
                glfwGetMonitorPos(monitor, ax, ay);
                originX = ax.get(0);
                originY = ay.get(0);
            }
            glfwSetWindowPos(handle, originX + (monW - w.get(0)) / 2, originY + (monH - h.get(0)) / 2);
        }
    }

    // ── size and scale ───────────────────────────────────────────────────────

    /**
     * Adopts the framebuffer size GLFW actually gave us (HiDPI) and any resize skipped while GL was
     * not yet ready.
     */
    private void adoptActualFramebufferSize() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer fbw = stack.mallocInt(1);
            IntBuffer fbh = stack.mallocInt(1);
            glfwGetFramebufferSize(handle, fbw, fbh);
            width = fbw.get(0);
            height = fbh.get(0);
            GL11.glViewport(0, 0, width, height);
            notifyResized();

            IntBuffer ww = stack.mallocInt(1);
            IntBuffer wh = stack.mallocInt(1);
            glfwGetWindowSize(handle, ww, wh);
            logger.info("[Display] window={}x{} framebuffer={}x{}", ww.get(0), wh.get(0), width, height);
        }
    }

    /** A framebuffer resize reported by GLFW; the viewport only once GL is ready. */
    private void onFramebufferResized(int newWidth, int newHeight) {
        width = newWidth;
        height = newHeight;
        if (glReady) {
            GL11.glViewport(0, 0, newWidth, newHeight);
        }
        updateCursorScale();
        notifyResized();
    }

    private void notifyResized() {
        for (ResizeListener l : resizeListeners) {
            l.onFramebufferResized(width, height);
        }
    }

    /**
     * Re-reads the ACTUAL framebuffer size. Call after changing the window size programmatically:
     * GLFW/Wayland may clamp or ignore the request and may not deliver the size callback
     * synchronously, so the real size is read back to avoid a stale viewport.
     */
    public void syncFramebufferSize() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer fbw = stack.mallocInt(1);
            IntBuffer fbh = stack.mallocInt(1);
            glfwGetFramebufferSize(handle, fbw, fbh);
            width = fbw.get(0);
            height = fbh.get(0);
        }
        if (glReady) {
            GL11.glViewport(0, 0, width, height);
        }
        updateCursorScale();
        notifyResized();
        refreshMonitorHz(); // the window may now sit on a different monitor
    }

    /** Recomputes the window-coordinate to framebuffer-pixel scale (on every size change). */
    public void updateCursorScale() {
        if (handle == NULL) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            IntBuffer ww = stack.mallocInt(1);
            IntBuffer wh = stack.mallocInt(1);
            glfwGetWindowSize(handle, ww, wh);
            cursorScaleX = cursorScale(width, ww.get(0));
            cursorScaleY = cursorScale(height, wh.get(0));
        }
    }

    /** Framebuffer pixels per window coordinate; 1 when the window size is unknown. */
    static double cursorScale(int framebufferSize, int windowSize) {
        return windowSize > 0 ? (double) framebufferSize / windowSize : 1.0;
    }

    /** A window-coordinate cursor X in framebuffer pixels. */
    public double toUiX(double windowX) {
        return windowX * cursorScaleX;
    }

    /** A window-coordinate cursor Y in framebuffer pixels. */
    public double toUiY(double windowY) {
        return windowY * cursorScaleY;
    }

    /** The cursor in framebuffer pixels: use for all UI hit-testing. */
    public void uiCursorPos(DoubleBuffer x, DoubleBuffer y) {
        glfwGetCursorPos(handle, x, y);
        x.put(0, toUiX(x.get(0)));
        y.put(0, toUiY(y.get(0)));
    }

    /** The cursor in window coordinates, as {x, y}. */
    public double[] cursorPos() {
        double[] x = new double[1];
        double[] y = new double[1];
        glfwGetCursorPos(handle, x, y);
        return new double[] {x[0], y[0]};
    }

    /** Size in screen coordinates, as {width, height}. */
    public int[] windowSize() {
        int[] w = new int[1];
        int[] h = new int[1];
        glfwGetWindowSize(handle, w, h);
        return new int[] {w[0], h[0]};
    }

    /** Requests a new size in screen coordinates; follow with {@link #syncFramebufferSize()}. */
    public void setWindowSize(int w, int h) {
        glfwSetWindowSize(handle, w, h);
    }

    public boolean isMaximized() {
        return glfwGetWindowAttrib(handle, GLFW_MAXIMIZED) == GLFW_TRUE;
    }

    public boolean isFocused() {
        return glfwGetWindowAttrib(handle, GLFW_FOCUSED) == GLFW_TRUE;
    }

    // ── refresh rate ─────────────────────────────────────────────────────────

    /** The refresh rate of the monitor the window is shown on (see the class doc). */
    public int monitorRefreshHz() {
        return monitorRefreshHz;
    }

    /** Re-reads {@link #monitorRefreshHz()} (moves, hot-plug and programmatic resizes call this). */
    public void refreshMonitorHz() {
        if (handle == NULL) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            long monitor = DisplayBackend.isWayland() ? fastestMonitor() : monitorUnderWindow(stack);
            GLFWVidMode mode = monitor != NULL ? glfwGetVideoMode(monitor) : null;
            if (mode != null && mode.refreshRate() > 0 && mode.refreshRate() != monitorRefreshHz) {
                monitorRefreshHz = mode.refreshRate();
                logger.info("[Display] Using monitor refresh rate: {} Hz", monitorRefreshHz);
            }
        }
    }

    private static long fastestMonitor() {
        long best = glfwGetPrimaryMonitor();
        PointerBuffer monitors = glfwGetMonitors();
        if (monitors == null) {
            return best;
        }
        int bestHz = -1;
        for (int i = 0; i < monitors.limit(); i++) {
            long monitor = monitors.get(i);
            GLFWVidMode mode = glfwGetVideoMode(monitor);
            if (mode != null && mode.refreshRate() > bestHz) {
                bestHz = mode.refreshRate();
                best = monitor;
            }
        }
        return best;
    }

    private long monitorUnderWindow(MemoryStack stack) {
        long best = glfwGetPrimaryMonitor();
        PointerBuffer monitors = glfwGetMonitors();
        if (monitors == null) {
            return best;
        }
        IntBuffer wx = stack.mallocInt(1);
        IntBuffer wy = stack.mallocInt(1);
        IntBuffer ww = stack.mallocInt(1);
        IntBuffer wh = stack.mallocInt(1);
        glfwGetWindowPos(handle, wx, wy);
        glfwGetWindowSize(handle, ww, wh);
        IntBuffer mx = stack.mallocInt(1);
        IntBuffer my = stack.mallocInt(1);
        long bestArea = -1;
        for (int i = 0; i < monitors.limit(); i++) {
            long monitor = monitors.get(i);
            GLFWVidMode mode = glfwGetVideoMode(monitor);
            if (mode == null) {
                continue;
            }
            glfwGetMonitorPos(monitor, mx, my);
            long area = overlapArea(wx.get(0), wy.get(0), ww.get(0), wh.get(0),
                    mx.get(0), my.get(0), mode.width(), mode.height());
            if (area > bestArea) {
                bestArea = area;
                best = monitor;
            }
        }
        return best;
    }

    /** Overlap of two rectangles (window, monitor) in square screen coordinates. */
    static long overlapArea(int ax, int ay, int aw, int ah, int bx, int by, int bw, int bh) {
        int ox = Math.max(0, Math.min(ax + aw, bx + bw) - Math.max(ax, bx));
        int oy = Math.max(0, Math.min(ay + ah, by + bh) - Math.max(ay, by));
        return (long) ox * oy;
    }

    // ── frame and context ────────────────────────────────────────────────────

    /** Processes pending events for every window (GLFW's queue is global). */
    public static void pollEvents() {
        glfwPollEvents();
    }

    public boolean shouldClose() {
        return handle == NULL || glfwWindowShouldClose(handle);
    }

    /** Flags the window to close (the loop exits at its next check). */
    public void requestClose() {
        if (handle != NULL) {
            glfwSetWindowShouldClose(handle, true);
        }
    }

    public void swapBuffers() {
        glfwSwapBuffers(handle);
    }

    public void setSwapInterval(int interval) {
        glfwSwapInterval(interval);
    }

    /** True when this window's context is current on the calling thread. */
    public boolean isContextCurrent() {
        return handle != NULL && glfwGetCurrentContext() == handle;
    }

    public void makeContextCurrent() {
        glfwMakeContextCurrent(handle);
    }

    /** {@code key} mapped to the keyboard layout's letter (see {@link GlfwKeys#layoutKey}). */
    public static int layoutKey(int key, int scancode) {
        return GlfwKeys.layoutKey(key, scancode);
    }

    /** True while {@code key} is physically held in this window. */
    public boolean isKeyDown(int key) {
        return GlfwKeys.isDown(handle, key);
    }

    /** Destroys the window and its context; safe to call repeatedly. */
    public void destroy() {
        if (handle != NULL) {
            glfwDestroyWindow(handle);
            handle = NULL;
            glReady = false;
        }
        if (debugCallback != null) {
            debugCallback.free();
            debugCallback = null;
        }
    }
}
