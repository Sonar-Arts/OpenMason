package com.openmason.engine.platform.window;

import java.util.Locale;

/**
 * The display-backend decision table (#96), with every input passed in so it is testable without a
 * display: the policy, the OS, the user's {@code openmason.glfw.platform} preference, the NVIDIA
 * driver version and which backends GLFW was built with.
 *
 * @param pin       the backend to pin before {@code glfwInit()}
 * @param reason    why, for the log
 * @param warning   true when the reason should be logged as a warning (a wish that cannot be met)
 */
public record BackendDecision(Pin pin, String reason, boolean warning) {

    /** What to hint before {@code glfwInit()}. */
    public enum Pin {
        /** No hint: GLFW picks (native Wayland under a Wayland session, else X11). */
        DEFAULT,
        X11,
        WAYLAND
    }

    /** The default {@code openmason.glfw.platform} value: follow the host's {@link BackendPolicy}. */
    public static final String AUTO = "auto";

    /**
     * @param policy           the host's policy for {@code auto}
     * @param linux            running on Linux (elsewhere there is nothing to choose)
     * @param preference       {@code auto|x11|wayland|any} (null = auto; unknown values = auto)
     * @param nvidiaDriver     the loaded NVIDIA driver version, or null
     * @param x11Supported     {@code glfwPlatformSupported(GLFW_PLATFORM_X11)}
     * @param waylandSupported {@code glfwPlatformSupported(GLFW_PLATFORM_WAYLAND)}
     */
    public static BackendDecision decide(BackendPolicy policy, boolean linux, String preference,
                                         String nvidiaDriver, boolean x11Supported, boolean waylandSupported) {
        if (!linux) {
            return new BackendDecision(Pin.DEFAULT, "not Linux", false);
        }
        String pref = preference == null ? AUTO : preference.trim().toLowerCase(Locale.ROOT);
        switch (pref) {
            case "any" -> {
                return new BackendDecision(Pin.DEFAULT, "left to GLFW by request (glfw.platform=any)", false);
            }
            case "wayland" -> {
                return waylandSupported
                        ? new BackendDecision(Pin.WAYLAND, "requested via glfw.platform=wayland", false)
                        : new BackendDecision(Pin.DEFAULT,
                                "wanted Wayland (glfw.platform=wayland) but the Wayland backend is unavailable", true);
            }
            case "x11" -> {
                return x11(x11Supported, "requested via glfw.platform=x11");
            }
            default -> {
                // "auto" and anything unrecognized follow the policy
            }
        }
        return switch (policy) {
            case PREFER_X11 -> x11(x11Supported, "preferred so ImGui viewports can pop out into their own windows");
            case AUTO_WAYLAND -> NvidiaDriver.isWaylandBlocked(nvidiaDriver)
                    ? x11(x11Supported, "NVIDIA driver " + nvidiaDriver + " has a broken Wayland EGL path")
                    : new BackendDecision(Pin.DEFAULT, "native Wayland unless the driver is blocklisted", false);
        };
    }

    private static BackendDecision x11(boolean supported, String why) {
        return supported
                ? new BackendDecision(Pin.X11, why, false)
                : new BackendDecision(Pin.DEFAULT, "wanted X11 (" + why + ") but the X11 backend is unavailable", true);
    }
}
