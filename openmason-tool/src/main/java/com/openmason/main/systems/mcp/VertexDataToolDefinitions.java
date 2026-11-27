package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import static com.openmason.main.systems.mcp.McpArgs.optBool;
import static com.openmason.main.systems.mcp.McpArgs.optFloatArray;
import static com.openmason.main.systems.mcp.McpArgs.optFloatBoxed;
import static com.openmason.main.systems.mcp.McpArgs.optIntArray;
import static com.openmason.main.systems.mcp.McpArgs.optString;
import static com.openmason.main.systems.mcp.McpArgs.reqString;

/**
 * The {@code morph_*} (shape keys, OpenMason #171) and {@code vgroup_*} (vertex groups, #172) MCP tools over
 * {@link VertexDataEditingService}. Undo domain {@code morph}; guide topic {@code describe_api skinning}.
 * Registered with the skin tools (group {@code core.skin}: per-vertex deformation data).
 */
public final class VertexDataToolDefinitions {

    private final VertexDataEditingService data;
    private final ObjectMapper mapper;

    public VertexDataToolDefinitions(VertexDataEditingService data, ObjectMapper mapper) {
        this.data = data;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        registry.register(new McpTool(
                "morph_list",
                "Shape keys; key+part: rows {v,d}.",
                schema().str("key", "Key").str("part", "Part").build(),
                args -> data.morphList(optString(args, "key"), optString(args, "part"))));

        registry.register(new McpTool(
                "morph_set",
                "Shape key: create/rename/default/min/max/remove; value = preview.",
                schema().str("name", "Key").bool("create", "Add").bool("remove", "Delete")
                        .str("rename", "New name").num("value", "Preview")
                        .num("default", "Default").num("min", "Min").num("max", "Max").required("name").build(),
                args -> data.morphSet(new VertexDataEditingService.MorphEdit(reqString(args, "name"),
                        optBool(args, "create", false), optBool(args, "remove", false), optString(args, "rename"),
                        optFloatBoxed(args, "value"), optFloatBoxed(args, "default"), optFloatBoxed(args, "min"),
                        optFloatBoxed(args, "max"), null))));

        registry.register(new McpTool(
                "morph_capture",
                "New shape key: begin, move vertices, mesh {name} (mesh back to basis unless keep_mesh); cancel; "
                        + "deltas {name, part, vertices, deltas: model xyz each}.",
                schema().enumStr("mode", "Mode", "begin", "mesh", "cancel", "deltas").str("name", "Key")
                        .str("part", "Part").intArr("vertices", "Part-local")
                        .arr("deltas", "number", "xyz each").bool("replace", "Replace rows")
                        .bool("keep_mesh", "No reset").required("mode").build(),
                args -> data.morphCapture(reqString(args, "mode"), optString(args, "name"), optString(args, "part"),
                        optIntArray(args, "vertices"), optFloatArray(args, "deltas"), optBool(args, "replace", false),
                        optBool(args, "keep_mesh", false))));

        registry.register(new McpTool(
                "vgroup_list",
                "Vertex groups; group+part: rows {v,w}.",
                schema().str("group", "Group").str("part", "Part").build(),
                args -> data.vgroupList(optString(args, "group"), optString(args, "part"))));

        registry.register(new McpTool(
                "vgroup_set",
                "Vertex group: create/rename/remove; assign part vertices (weight|weights), unassign "
                        + "(none = whole part).",
                schema().str("name", "Group").bool("create", "Add").bool("remove", "Delete")
                        .str("rename", "New name").str("part", "Part").intArr("vertices", "Part-local")
                        .num("weight", "0..1").arr("weights", "number", "Each").bool("unassign", "Remove")
                        .required("name").build(),
                args -> data.vgroupSet(new VertexDataEditingService.GroupEdit(reqString(args, "name"),
                        optBool(args, "create", false), optBool(args, "remove", false), optString(args, "rename"),
                        optString(args, "part"), optIntArray(args, "vertices"), optFloatBoxed(args, "weight"),
                        optFloatArray(args, "weights"), optBool(args, "unassign", false), null, null))));
    }

    private McpSchema schema() {
        return McpSchema.of(mapper);
    }
}
