package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

import static com.openmason.main.systems.mcp.McpArgs.optFloatBoxed;
import static com.openmason.main.systems.mcp.McpArgs.optInt;
import static com.openmason.main.systems.mcp.McpArgs.optString;
import static com.openmason.main.systems.mcp.McpArgs.optStringList;
import static com.openmason.main.systems.mcp.McpArgs.optVec3;

/**
 * Registers MCP tools for the 3D viewport itself (as opposed to the model
 * loaded in it): {@link ViewportCaptureService} plus the view-only camera and
 * gizmo controls of {@link ViewportViewService} (no undo entries, no dirty flag).
 */
public final class ViewportToolDefinitions {

    /** Shared by the camera tools: the state they all return and the axis conventions. */
    private static final String CAMERA_STATE = " Returns {mode, target, distance, yaw, pitch, fov, eye, forward, "
            + "aspect, model_transform_identity}. Orbit camera, Y up, degrees: eye = target + distance * "
            + "(cos(pitch) sin(yaw), sin(pitch), cos(pitch) cos(yaw)); yaw 0 = camera on +Z looking -Z. "
            + "Models face -Z (right hand +X), so their front is yaw 180. Units = model space (as part/bone "
            + "tools report) while model_transform_identity is true.";

    /** The other camera tools return the same state; the conventions are spelled out once. */
    private static final String SEE_GET = " Returns the camera state (fields and axes: viewport_camera_get).";

    private final ViewportCaptureService capture;
    private final ViewportViewService view;
    private final ObjectMapper mapper;
    /** The tab previews by name ("animation", "animgraph"), for capture and the Eyes view; null = none. */
    private final java.util.function.Function<String, PreviewViewport> previews;

    public ViewportToolDefinitions(ViewportCaptureService capture, ViewportViewService view, ObjectMapper mapper) {
        this(capture, view, mapper, null);
    }

    public ViewportToolDefinitions(ViewportCaptureService capture, ViewportViewService view, ObjectMapper mapper,
                                   java.util.function.Function<String, PreviewViewport> previews) {
        this.capture = capture;
        this.view = view;
        this.mapper = mapper;
        this.previews = previews;
    }

    public void registerAll(McpToolRegistry registry) {
        registry.register(new McpTool(
                "viewport_capture",
                "Capture the 3D viewport's last rendered frame (current camera view) as a PNG image - "
                        + "use it to visually verify the model between edits. Downscaled so the longest "
                        + "side is max_size (default 1024, range 64-2048); the source resolution is "
                        + "capped by the on-screen viewport panel size. Aim the camera first with "
                        + "viewport_camera_frame / viewport_camera_set. view: scene (default) or a tab preview - "
                        + "animation (the Animation tab's clip) / animgraph (the Animation Graph preview), rendered "
                        + "at capture time even when the tab is hidden (viewport_eyes switches those to first person).",
                captureSchema(),
                args -> capture.capture(optInt(args, "max_size", ViewportCaptureService.DEFAULT_MAX_SIZE),
                        optString(args, "view"))));

        registry.register(new McpTool(
                "viewport_eyes",
                "The Eyes preview of a tab viewport (animation = the Animation tab, animgraph = the Animation Graph "
                        + "preview): the character seen by itself in first person - camera on its eyes socket, the "
                        + "engine first-person lens (own FOV, depth slab, pull), only the arm branches drawn; in the "
                        + "graph tab its first-person view layers (their weight param driven to 1). yaw/pitch = the "
                        + "look in degrees from the character's facing (+yaw left, +pitch up); arms_follow_look turns "
                        + "the arms with the look (camera-attached, how a game anchors clips that key the torso at "
                        + "rest; default on). Omitted fields keep their value; reset restores look 0/0, FOV 70. "
                        + "View only (no undo). Not the Scene viewport's free-fly first-person camera. Check it with "
                        + "viewport_capture {view}. Returns {enabled, yaw, pitch, fov, arms_follow_look, problem}.",
                McpSchema.of(mapper)
                        .enumStr("view", "Which tab preview (required)", "animation", "animgraph")
                        .bool("enabled", "Eyes on (true) or off (false)")
                        .num("yaw", "Look yaw, degrees (+ = left)")
                        .num("pitch", "Look pitch, degrees (+ = up, -89..89)")
                        .num("fov", "First-person lens FOV, degrees (20-120, default 70)")
                        .bool("arms_follow_look", "Arms turn with the look (default true)")
                        .bool("reset", "Restore look 0/0, FOV 70, arms following first")
                        .required("view")
                        .build(),
                args -> eyes(args)));

        registry.register(new McpTool(
                "viewport_camera_get",
                "Current viewport camera (read-only)." + CAMERA_STATE,
                McpSchema.of(mapper).build(),
                args -> view.getCamera()));

        registry.register(new McpTool(
                "viewport_camera_set",
                "Move the viewport camera; omitted fields keep their value. Snaps at once (the next "
                        + "viewport_capture shows it); view only, no undo. eye [x,y,z] = put the camera there "
                        + "looking at target (replaces distance/yaw/pitch). Clamped like the UI: distance "
                        + "0.05-100, pitch -89..89, fov 10-120; 'clamped' lists adjusted fields." + SEE_GET,
                McpSchema.of(mapper)
                        .vec3("target", "Orbit centre [x,y,z]")
                        .num("distance", "Eye-to-target distance")
                        .num("yaw", "Degrees")
                        .num("pitch", "Degrees, + looks down from above")
                        .num("fov", "Vertical field of view, degrees")
                        .vec3("eye", "Camera position [x,y,z]; aims at target")
                        .build(),
                args -> view.setCamera(new ViewportViewService.CameraChange(
                        optVec3(args, "target"), optFloatBoxed(args, "distance"), optFloatBoxed(args, "yaw"),
                        optFloatBoxed(args, "pitch"), optFloatBoxed(args, "fov"), optVec3(args, "eye")))));

        registry.register(new McpTool(
                "viewport_camera_frame",
                "Aim the camera so a subject fills the view (no subject = all visible parts). Subjects unite: "
                        + "parts (ids or names), bone (its head-tail segment), box_min+box_max, point+radius. "
                        + "Direction: view preset (side of the model seen: front=yaw 180, back=0, right=90, "
                        + "left=270, top/bottom=pitch +-89, three_quarter=front-right above) or yaw/pitch "
                        + "(override the preset), else the current angles. margin scales the fit (default "
                        + "1.15; < 1 crops in). Adds 'framed' {center, radius, min, max}; snaps, view only."
                        + SEE_GET,
                McpSchema.of(mapper)
                        .strArray("parts", "Part ids or names")
                        .str("bone", "Bone id or name")
                        .vec3("box_min", "Box min corner [x,y,z]")
                        .vec3("box_max", "Box max corner [x,y,z]")
                        .vec3("point", "Point [x,y,z]")
                        .num("radius", "Radius around point (default 0.25)")
                        .enumStr("view", "View preset", ViewportCameraFraming.PRESETS.toArray(String[]::new))
                        .num("yaw", "Degrees")
                        .num("pitch", "Degrees")
                        .num("margin", "Fit factor 0.1-50 (default 1.15)")
                        .build(),
                args -> view.frame(new ViewportViewService.FrameRequest(
                        partsArg(args), optString(args, "bone"), optVec3(args, "box_min"),
                        optVec3(args, "box_max"), optVec3(args, "point"), optFloatBoxed(args, "radius"),
                        optString(args, "view"), optFloatBoxed(args, "yaw"), optFloatBoxed(args, "pitch"),
                        optFloatBoxed(args, "margin")))));

        registry.register(new McpTool(
                "viewport_camera_reset",
                "Reset the camera to its default orbit (the Camera pane's Reset; fov kept), snapped at once."
                        + SEE_GET,
                McpSchema.of(mapper).build(),
                args -> view.resetCamera()));

        registry.register(new McpTool(
                "viewport_gizmo",
                "Show/hide the transform gizmo and set its mode; omitted fields keep their value. View only "
                        + "(no undo); visible:false keeps it out of viewport_capture. Axes are always world "
                        + "(no local/world option). In auto_show_on_select display mode a later selection "
                        + "change shows/hides it again, like Ctrl+T. Returns {visible, mode, uniform_scale, "
                        + "space, display_mode}.",
                McpSchema.of(mapper)
                        .bool("visible", "Show (true) or hide (false)")
                        .enumStr("mode", "Gizmo mode", "translate", "rotate", "scale")
                        .bool("uniform_scale", "Scale mode drags all axes together")
                        .build(),
                args -> view.gizmo(optBool(args, "visible"), optString(args, "mode"),
                        optBool(args, "uniform_scale"))));
    }

    private JsonNode captureSchema() {
        return McpSchema.of(mapper)
                .intg("max_size", "Longest-side pixel cap, 64-2048 (default 1024)")
                .enumStr("view", "What to capture (default scene)", "scene", "animation", "animgraph")
                .build();
    }

    /** viewport_eyes on the GL thread. */
    private Object eyes(JsonNode args) {
        String name = optString(args, "view");
        PreviewViewport preview = previews == null || name == null ? null : previews.apply(name);
        if (preview == null) throw new IllegalArgumentException("view must be animation or animgraph");
        Boolean enabled = optBool(args, "enabled");
        Float yaw = optFloat(args, "yaw"), pitch = optFloat(args, "pitch"), fov = optFloat(args, "fov");
        Boolean follow = optBool(args, "arms_follow_look");
        boolean reset = Boolean.TRUE.equals(optBool(args, "reset"));
        try {
            return com.openmason.main.systems.threading.MainThreadExecutor.submit(
                    () -> preview.eyes(enabled, yaw, pitch, fov, follow, reset)).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new RuntimeException(e.getCause());
        } catch (Exception e) {
            throw new RuntimeException("viewport_eyes: " + e.getMessage(), e);
        }
    }

    /** Optional number; null when absent. */
    private static Float optFloat(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull()) return null;
        if (!n.isNumber()) throw new IllegalArgumentException(key + " must be a number");
        return (float) n.asDouble();
    }

    /** {@code parts} as a string array; a lone string is accepted as one part. */
    private static List<String> partsArg(JsonNode args) {
        JsonNode n = args.get("parts");
        if (n != null && n.isTextual()) {
            return n.asText().isBlank() ? List.of() : List.of(n.asText());
        }
        return optStringList(args, "parts");
    }

    /** Optional boolean; null when absent. */
    private static Boolean optBool(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull()) return null;
        if (!n.isBoolean()) throw new IllegalArgumentException(key + " must be true or false");
        return n.asBoolean();
    }
}
