package com.openmason.main.systems.animation.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.mcp.McpSchema;
import com.openmason.main.systems.mcp.McpTool;
import com.openmason.main.systems.mcp.McpToolRegistry;

import java.util.ArrayList;
import java.util.List;

import static com.openmason.main.systems.mcp.McpArgs.optBool;
import static com.openmason.main.systems.mcp.McpArgs.optString;

/**
 * The {@code animation} MCP group (OpenMason #165): author .omanim clips through the clip open in the Animation tab -
 * layers, keys on parts AND bones (or roles), the control rig (IK effectors, look-at, switch-and-match), notifies,
 * curves, markers, root motion, additive, retarget bakes. Every edit is one undo step ({@code undo}/{@code redo}
 * with domain "anim"). Guide: describe_api topic:"animation".
 */
public final class AnimationToolDefinitions {

    private final AnimationEditingService service;
    private final ObjectMapper mapper;

    public AnimationToolDefinitions(AnimationEditingService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        registry.register(new McpTool("anim_new",
                "Start a clip in the Animation tab for 'model' (.omo, a plugin's model container, or an .omchar for "
                        + "role-addressed tracks and retargeting). Guide: describe_api topic:'animation'.",
                schema().str("model", "Model or character file").str("name", "Clip name").num("fps", "Frames per second")
                        .num("duration", "Seconds").bool("discard", "Drop unsaved changes of the open clip")
                        .required("model").build(),
                args -> service.create(optString(args, "model"), optString(args, "name"), optFloat(args, "fps"),
                        optFloat(args, "duration"), optBool(args, "discard", false))));

        registry.register(new McpTool("anim_open",
                "Open an .omanim in the Animation tab, on its modelRef or on 'model'. Authoring layers and IK keys "
                        + "stored in the file come back editable; every other section is kept.",
                schema().str("path", "An .omanim").str("model", "The model / character it animates")
                        .bool("discard", "Drop unsaved changes of the open clip").required("path").build(),
                args -> service.open(optString(args, "path"), optString(args, "model"), optBool(args, "discard", false))));

        registry.register(new McpTool("anim_get_info",
                "The open clip: header, runtime layer, authoring layers with tracks and key times, effectors with "
                        + "keys, notifies, curves, markers, root motion, additive; playhead and dirty flag.",
                schema().build(),
                args -> service.info()));

        registry.register(new McpTool("anim_get_pose",
                "The pose at 'time' (default: playhead): each joint's local pos/rot/scale and world point, the "
                        + "control-rig handles, root motion; 'layer' adds that layer's own values; fk_only skips the rig.",
                schema().num("time", "Seconds").strArray("joints", "Only these joints (id or name)")
                        .str("layer", "Also that layer's own values").bool("fk_only", "Layers without the control rig").build(),
                args -> service.pose(optFloat(args, "time"), strings(args.get("joints")), optString(args, "layer"),
                        optBool(args, "fk_only", false))));

        registry.register(new McpTool("anim_edit",
                "Edit atomically (one undo step). ops: header, clip_layer, key, remove_keys, move_keys, easing, track, "
                        + "layer, layer_weight, notify, curve, marker, root_motion, additive, effector, effector_key, "
                        + "match, bake (see the guide). Joints by id, name or role:<r>; layers by name ('base').",
                schema().arr("ops", "object", "Operations {op, ...}").str("label", "Undo label").required("ops").build(),
                args -> service.edit(args.get("ops"), optString(args, "label"))));

        registry.register(new McpTool("anim_transport",
                "Playback in the tab: play, pause, seek (time), fk_only (value), in_place (value: root motion preview).",
                schema().enumStr("action", "Action", "play", "pause", "seek", "fk_only", "in_place").num("time", "Seconds")
                        .bool("value", "For fk_only / in_place").required("action").build(),
                args -> service.transport(optString(args, "action"), optFloat(args, "time"),
                        args.has("value") ? args.get("value").asBoolean() : null)));

        registry.register(new McpTool("anim_retarget",
                "Bake a clip of another characterized model onto the open character as an authoring layer (#160); "
                        + "the open clip must be on an .omchar.",
                schema().str("source_character", "Source .omchar").str("source_clip", "Source .omanim")
                        .str("layer", "Layer name (default Retarget)").required("source_character", "source_clip").build(),
                args -> service.retarget(optString(args, "source_character"), optString(args, "source_clip"),
                        optString(args, "layer"))));

        registry.register(new McpTool("anim_save",
                "Write the .omanim (baked tracks + authoring data): in place, or 'path' (a bare name lands in the "
                        + "project's Animations folder).",
                schema().str("path", "Target (absolute or project:/target:/exports: path)")
                        .bool("prompt", "Let the user pick").bool("overwrite", "Replace an existing file").build(),
                args -> service.save(optString(args, "path"), optBool(args, "prompt", false), optBool(args, "overwrite", false))));

        registry.register(new McpTool("anim_close",
                "Close the open clip (discard:true drops unsaved changes).",
                schema().bool("discard", "Drop unsaved changes").build(),
                args -> service.close(optBool(args, "discard", false))));
    }

    private McpSchema schema() {
        return McpSchema.of(mapper);
    }

    private static Float optFloat(JsonNode args, String key) {
        JsonNode n = args == null ? null : args.get(key);
        return n == null || n.isNull() ? null : (float) n.asDouble();
    }

    private static List<String> strings(JsonNode n) {
        if (n == null || n.isNull()) return null;
        List<String> out = new ArrayList<>();
        if (n.isArray()) n.forEach(x -> out.add(x.asText()));
        else out.add(n.asText());
        return out;
    }
}
