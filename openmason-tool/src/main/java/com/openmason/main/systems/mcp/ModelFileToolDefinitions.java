package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import static com.openmason.main.systems.mcp.McpArgs.optBool;
import static com.openmason.main.systems.mcp.McpArgs.optInt;
import static com.openmason.main.systems.mcp.McpArgs.optString;
import static com.openmason.main.systems.mcp.McpArgs.reqString;

/** {@code model_save} / {@code model_open} / {@code model_new}. */
public final class ModelFileToolDefinitions {

    private final ModelFileService files;
    private final ObjectMapper mapper;

    public ModelFileToolDefinitions(ModelFileService files, ObjectMapper mapper) {
        this.files = files;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        registry.register(new McpTool(
                "model_save",
                "Save the model editor's model as .omo. Without file_path: re-save its own file, "
                        + "or — for a new / imported-asset model — open the in-app Save Sheet. "
                        + "file_path: absolute, project:<rel> or a bare name (see save_targets); "
                        + "risky writes ask the user. Returns a structured outcome, never a protocol "
                        + "error when the user declines.",
                McpSchema.of(mapper)
                        .str("file_path", "Target .omo path")
                        .bool("prompt", "Always ask the user in the in-app Save Sheet")
                        .bool("overwrite", "Acknowledge replacing an existing file")
                        .build(),
                args -> files.save(optString(args, "file_path"), optBool(args, "prompt", false),
                        optBool(args, "overwrite", false))));

        registry.register(new McpTool(
                "model_open",
                "Open a .omo from a writable root (absolute, project:<rel>, target:<rel> or a bare "
                        + "name in the project) into the model editor. Unsaved changes trigger the "
                        + "in-tool approval dialog (the user may save first).",
                McpSchema.of(mapper)
                        .str("file_path", "The .omo to open").required("file_path")
                        .intg("timeout_seconds", "How long to wait for the user (default 60, max 240)")
                        .build(),
                args -> files.open(reqString(args, "file_path"), optInt(args, "timeout_seconds", 0))));

        registry.register(new McpTool(
                "model_import",
                "Import a .gltf/.glb/.obj (path like model_open) as an unsaved model; returns counts and warnings. "
                        + "save_clips_to (e.g. project:Animations) writes its animations as .omanim.",
                McpSchema.of(mapper)
                        .str("path", "File to import").required("path")
                        .num("unit_scale", "Source units to Open Mason units (1)")
                        .bool("keep_axis", "Keep the source axes (default: turn +Z-forward to -Z)")
                        .bool("merge_quads", "Pair triangles into quads (true)")
                        .bool("split_parts", "One part per mesh node (true)")
                        .bool("animations", "Convert animations (true)")
                        .bool("morphs", "Shape keys (true)")
                        .bool("vertex_groups", "Vertex groups (true)")
                        .intg("max_texture_size", "Downscale larger textures, 0 = no limit")
                        .str("save_clips_to", "Folder for the clips")
                        .intg("timeout_seconds", "How long to wait for the user (default 60, max 240)")
                        .build(),
                args -> files.importModel(reqString(args, "path"), importOptions(args),
                        optString(args, "save_clips_to"), optInt(args, "timeout_seconds", 0))));

        registry.register(new McpTool(
                "model_new",
                "Start a new blank cube model in the editor. Unsaved changes trigger the in-tool "
                        + "approval dialog.",
                McpSchema.of(mapper)
                        .intg("timeout_seconds", "How long to wait for the user (default 60, max 240)")
                        .build(),
                args -> files.newModel(optInt(args, "timeout_seconds", 0))));
    }

    private static com.openmason.engine.format.omo.CendaImport.Options importOptions(
            com.fasterxml.jackson.databind.JsonNode args) {
        var d = com.openmason.engine.format.omo.CendaImport.Options.defaults();
        Float scale = McpArgs.optFloatBoxed(args, "unit_scale");
        if (scale != null && !(scale > 0f)) {
            throw new IllegalArgumentException("unit_scale must be > 0");
        }
        return new com.openmason.engine.format.omo.CendaImport.Options(
                scale != null ? scale : d.unitScale(),
                optBool(args, "keep_axis", d.keepAxis()),
                optBool(args, "merge_quads", d.mergeQuads()),
                optBool(args, "split_parts", d.splitParts()),
                optBool(args, "animations", d.animations()),
                optBool(args, "morphs", d.morphs()),
                optBool(args, "vertex_groups", d.vertexGroups()),
                false,
                Math.max(0, optInt(args, "max_texture_size", 0)),
                d.quadAngleDegrees());
    }
}
