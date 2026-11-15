package com.openmason.engine.platform.window;

import java.util.Objects;
import java.util.function.LongConsumer;

import com.openmason.engine.util.EngineProperties;

/**
 * What a host asks of its window (#96). Everything host-specific lives here (title, size, GL
 * profile, Wayland {@code app_id}); the engine supplies no defaults that name a product.
 *
 * @param title          window title
 * @param width          requested width in screen coordinates
 * @param height         requested height in screen coordinates
 * @param fallbackWidth  size to retry with when creation at {@code width x height} fails (0 = no retry)
 * @param fallbackHeight see {@code fallbackWidth}
 * @param minWidth       minimum size (0 = none)
 * @param minHeight      minimum size (0 = none)
 * @param glMajor        requested GL context version
 * @param glMinor        requested GL context version
 * @param profile        core or compatibility profile
 * @param forwardCompat  {@code GLFW_OPENGL_FORWARD_COMPAT} (macOS needs it for core profiles)
 * @param waylandAppId   Wayland {@code app_id} (taskbar grouping, icon, window rules); null = none
 * @param resizable      user-resizable
 * @param center         centre on the primary monitor's work area (never on Wayland, which forbids it)
 * @param swapInterval   {@code glfwSwapInterval} after the context is current (0 = never block on vblank)
 * @param debugContext   a debug GL context with driver messages on stderr; defaults to
 *                       {@code -Dopenmason.gldebug} (legacy {@code stonebreak.gldebug})
 * @param onCreated      runs with the native handle after creation, before the window is shown
 *                       (icons, size limits a host computes itself); may be null
 */
public record WindowSpec(String title, int width, int height, int fallbackWidth, int fallbackHeight,
                         int minWidth, int minHeight, int glMajor, int glMinor, GlProfile profile,
                         boolean forwardCompat, String waylandAppId, boolean resizable, boolean center,
                         int swapInterval, boolean debugContext, LongConsumer onCreated) {

    /** The GL profile to request. */
    public enum GlProfile { CORE, COMPAT }

    public WindowSpec {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(profile, "profile");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("window size must be positive: " + width + "x" + height);
        }
    }

    public static Builder builder(String title, int width, int height) {
        return new Builder(title, width, height);
    }

    /** Builder; defaults: GL 3.3 core, resizable, centred, swap interval 0, no app id. */
    public static final class Builder {
        private final String title;
        private final int width;
        private final int height;
        private int fallbackWidth;
        private int fallbackHeight;
        private int minWidth;
        private int minHeight;
        private int glMajor = 3;
        private int glMinor = 3;
        private GlProfile profile = GlProfile.CORE;
        private boolean forwardCompat;
        private String waylandAppId;
        private boolean resizable = true;
        private boolean center = true;
        private int swapInterval;
        private boolean debugContext = EngineProperties.flag("gldebug");
        private LongConsumer onCreated;

        private Builder(String title, int width, int height) {
            this.title = title;
            this.width = width;
            this.height = height;
        }

        public Builder fallbackSize(int w, int h) {
            this.fallbackWidth = w;
            this.fallbackHeight = h;
            return this;
        }

        public Builder minSize(int w, int h) {
            this.minWidth = w;
            this.minHeight = h;
            return this;
        }

        public Builder gl(int major, int minor, GlProfile profile) {
            this.glMajor = major;
            this.glMinor = minor;
            this.profile = profile;
            return this;
        }

        public Builder forwardCompat(boolean on) {
            this.forwardCompat = on;
            return this;
        }

        public Builder waylandAppId(String appId) {
            this.waylandAppId = appId;
            return this;
        }

        public Builder resizable(boolean on) {
            this.resizable = on;
            return this;
        }

        public Builder center(boolean on) {
            this.center = on;
            return this;
        }

        public Builder swapInterval(int interval) {
            this.swapInterval = interval;
            return this;
        }

        public Builder debugContext(boolean on) {
            this.debugContext = on;
            return this;
        }

        public Builder onCreated(LongConsumer hook) {
            this.onCreated = hook;
            return this;
        }

        public WindowSpec build() {
            return new WindowSpec(title, width, height, fallbackWidth, fallbackHeight, minWidth, minHeight,
                    glMajor, glMinor, profile, forwardCompat, waylandAppId, resizable, center, swapInterval,
                    debugContext, onCreated);
        }
    }
}
