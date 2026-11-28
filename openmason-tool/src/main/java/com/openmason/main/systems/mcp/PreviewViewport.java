package com.openmason.main.systems.mcp;

import java.util.Map;

/**
 * A tab's own preview viewport (the Animation tab, the Animation Graph tab) as MCP sees it: renders its current
 * frame on demand (so a capture never reads a stale frame of a hidden tab) and switches its Eyes preview (the
 * character in first person, {@code animation.EyesView}). GL thread only.
 */
public interface PreviewViewport {

    /** The preview's capture name ({@code viewport_capture view}). */
    String viewName();

    /**
     * Renders the current frame now; {@code width}/{@code height} 0 = the size it was last shown at (a default when
     * never shown). Returns {texture, width, height}, or null when there is nothing to show (no clip / graph open).
     */
    int[] renderFrame(int width, int height);

    /**
     * Applies an Eyes request (null fields keep their value; {@code reset} first restores look 0/0, FOV 70 and arms
     * following the look) and returns the state: enabled, yaw, pitch, fov, arms_follow_look, problem.
     */
    Map<String, Object> eyes(Boolean enabled, Float yaw, Float pitch, Float fov, Boolean armsFollowLook, boolean reset);

    /** The Eyes state map shared by the implementations. */
    static Map<String, Object> eyesState(boolean enabled, com.openmason.main.systems.animation.EyesView view,
                                         String problem) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("yaw", view.yaw());
        m.put("pitch", view.pitch());
        m.put("fov", view.fov());
        m.put("arms_follow_look", view.armsFollowLook());
        m.put("problem", problem);
        return m;
    }

    /** Applies the request fields shared by the implementations to {@code view}. */
    static void applyEyes(com.openmason.main.systems.animation.EyesView view, Float yaw, Float pitch, Float fov,
                          Boolean armsFollowLook, boolean reset) {
        if (reset) view.reset();
        if (yaw != null || pitch != null) {
            view.setLook(yaw != null ? yaw : view.yaw(), pitch != null ? pitch : view.pitch());
        }
        if (fov != null) view.setFov(fov);
        if (armsFollowLook != null) view.setArmsFollowLook(armsFollowLook);
    }
}
