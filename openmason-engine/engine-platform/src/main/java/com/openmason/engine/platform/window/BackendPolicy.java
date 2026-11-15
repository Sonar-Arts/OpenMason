package com.openmason.engine.platform.window;

/**
 * How a host wants GLFW's Linux display backend chosen when the user asks for nothing specific
 * ({@code -Dopenmason.glfw.platform=auto}, the default). Windows and macOS have one backend each,
 * so the policy only matters on Linux.
 */
public enum BackendPolicy {

    /**
     * Native Wayland under a Wayland session, unless the loaded NVIDIA driver is on the
     * {@link NvidiaDriver#WAYLAND_BLOCKED} list; then X11 (XWayland). Games use this.
     */
    AUTO_WAYLAND,

    /**
     * X11 (XWayland on Wayland desktops) whenever GLFW was built with it: Wayland forbids clients
     * from positioning their own windows, so ImGui multi-viewport pop-outs stay clamped inside the
     * main window there. The Open Mason editor uses this.
     */
    PREFER_X11
}
