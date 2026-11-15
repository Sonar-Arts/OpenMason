package com.openmason.engine.platform.window;

import com.openmason.engine.rendering.gl.GlDriverThreading;
import com.openmason.engine.util.EngineProperties;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWMonitorCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.glfw.GLFW.GLFW_ANY_PLATFORM;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_COCOA;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WAYLAND;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WIN32;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_X11;
import static org.lwjgl.glfw.GLFW.glfwGetPlatform;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwInitHint;
import static org.lwjgl.glfw.GLFW.glfwPlatformSupported;
import static org.lwjgl.glfw.GLFW.glfwSetErrorCallback;
import static org.lwjgl.glfw.GLFW.glfwSetMonitorCallback;
import static org.lwjgl.glfw.GLFW.glfwTerminate;

/**
 * Starts and stops GLFW (#96): the one copy of the steps every host used to repeat. In order:
 * <ol>
 *   <li>the error callback (stderr);</li>
 *   <li>{@link GlDriverThreading}: NVIDIA threaded optimization off before the driver loads (it
 *       garbles Skia GPU paints), opt out with {@code -Dopenmason.gl.threaded=true};</li>
 *   <li>the Linux backend choice ({@link BackendDecision}, from the host's {@link BackendPolicy} and
 *       {@code -Dopenmason.glfw.platform=auto|x11|wayland|any});</li>
 *   <li>{@code glfwInit()}; a pinned backend that fails to start (no reachable X display, no
 *       Wayland compositor) is retried with {@code GLFW_ANY_PLATFORM}.</li>
 * </ol>
 * The legacy {@code stonebreak.glfw.platform} and {@code stonebreak.gl.threaded} spellings are still
 * read ({@link EngineProperties}, one-time deprecation warning).
 */
public final class DisplayBackend {

    private static final Logger logger = LoggerFactory.getLogger(DisplayBackend.class);

    private static volatile BackendPolicy activePolicy;

    private DisplayBackend() {
    }

    /**
     * Installs the error callback, selects the display backend and initializes GLFW.
     *
     * @throws IllegalStateException if GLFW cannot be initialized on any platform
     */
    public static void initialize(BackendPolicy policy) {
        GLFWErrorCallback.createPrint(System.err).set();
        GlDriverThreading.disableThreadedDispatch(EngineProperties.flag("gl.threaded"),
                EngineProperties.name("gl.threaded"));

        BackendDecision.Pin pin = selectBackend(policy);
        if (!glfwInit()) {
            if (pin == BackendDecision.Pin.DEFAULT) {
                throw new IllegalStateException("Unable to initialize GLFW");
            }
            logger.warn("[Display] GLFW init with the pinned {} backend failed (no reachable display?); "
                    + "retrying with the default platform.", pin);
            glfwInitHint(GLFW_PLATFORM, GLFW_ANY_PLATFORM);
            if (!glfwInit()) {
                throw new IllegalStateException("Unable to initialize GLFW");
            }
        }
        activePolicy = policy;
        logger.info("[Display] GLFW platform: {}", platformName());
    }

    /** The policy GLFW was started with, or null before {@link #initialize}. */
    public static BackendPolicy policy() {
        return activePolicy;
    }

    private static BackendDecision.Pin selectBackend(BackendPolicy policy) {
        boolean linux = System.getProperty("os.name", "").toLowerCase().contains("linux");
        String preference = EngineProperties.get("glfw.platform", BackendDecision.AUTO);
        boolean auto = BackendDecision.AUTO.equalsIgnoreCase(preference.trim());
        String driver = null;
        if (linux && policy == BackendPolicy.AUTO_WAYLAND && auto) {
            driver = NvidiaDriver.detect();
            if (driver != null) {
                logger.info("[Display] Detected NVIDIA driver version: {}", driver);
            }
        }
        BackendDecision d = BackendDecision.decide(policy, linux, preference, driver,
                linux && glfwPlatformSupported(GLFW_PLATFORM_X11),
                linux && glfwPlatformSupported(GLFW_PLATFORM_WAYLAND));
        if (d.warning()) {
            logger.warn("[Display] {}; using the default platform.", d.reason());
        }
        switch (d.pin()) {
            case X11 -> {
                logger.info("[Display] Pinning GLFW backend to X11: {}.", d.reason());
                glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11);
            }
            case WAYLAND -> {
                logger.info("[Display] Pinning GLFW backend to Wayland: {}.", d.reason());
                glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_WAYLAND);
            }
            case DEFAULT -> {
                if (linux && !d.warning()) {
                    logger.debug("[Display] Backend left to GLFW: {}", d.reason());
                }
            }
        }
        return d.pin();
    }

    /** True when GLFW runs on the native Wayland backend. */
    public static boolean isWayland() {
        return glfwGetPlatform() == GLFW_PLATFORM_WAYLAND;
    }

    /** "Wayland", "X11", "Win32", "Cocoa" or "unknown". */
    public static String platformName() {
        return switch (glfwGetPlatform()) {
            case GLFW_PLATFORM_WAYLAND -> "Wayland";
            case GLFW_PLATFORM_X11 -> "X11";
            case GLFW_PLATFORM_WIN32 -> "Win32";
            case GLFW_PLATFORM_COCOA -> "Cocoa";
            default -> "unknown";
        };
    }

    /**
     * Terminates GLFW and frees the global callbacks. Every window must already be destroyed
     * ({@link Window#destroy()}).
     */
    public static void terminate() {
        GLFWMonitorCallback monitor = glfwSetMonitorCallback(null);
        if (monitor != null) {
            monitor.free();
        }
        glfwTerminate();
        GLFWErrorCallback error = glfwSetErrorCallback(null);
        if (error != null) {
            error.free();
        }
        activePolicy = null;
    }
}
