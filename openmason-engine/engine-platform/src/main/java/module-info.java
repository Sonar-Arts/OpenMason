/**
 * Open Mason engine platform (#96): the window, input devices and frame loop over GLFW. One copy of
 * the display-backend policy (native Wayland vs X11, the NVIDIA blocklist, init recovery), the window
 * and GL context with framebuffer/cursor scaling and monitor refresh tracking, the per-window input
 * event chain, pointer capture, gamepads, the clipboard and the frame loop. The GLFW-free halves
 * (bindings, key edges, input state, fixed step, pacing) live in {@code openmason.engine.core}
 * ({@code com.openmason.engine.input}, {@code com.openmason.engine.loop}) so headless consumers use
 * them without GLFW natives. Holds no game content: hosts pass titles, app ids, profiles and policy.
 */
module openmason.engine.platform {

    requires transitive openmason.engine.core;
    // LayoutKeys, GamepadButtons, MasonryEnvironment.ClipboardProvider (Clipboard implements it)
    requires transitive openmason.engine.ui;

    requires transitive org.lwjgl.glfw;
    requires org.lwjgl.opengl;
    requires org.slf4j;

    exports com.openmason.engine.platform.input;
    exports com.openmason.engine.platform.loop;
    exports com.openmason.engine.platform.window;
}
