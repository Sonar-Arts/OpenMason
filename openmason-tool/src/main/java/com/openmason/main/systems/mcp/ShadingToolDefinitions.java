package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static com.openmason.main.systems.mcp.McpArgs.optBool;
import static com.openmason.main.systems.mcp.McpArgs.optFloatBoxed;
import static com.openmason.main.systems.mcp.McpArgs.optIntArray;
import static com.openmason.main.systems.mcp.McpArgs.optString;
import static com.openmason.main.systems.mcp.McpArgs.reqString;

/**
 * {@code shading_set} / {@code shading_sharp} / {@code shading_get} over {@link ShadingEditingService} (OpenMason
 * #169, Blender semantics: flat by default; smooth faces blend across smooth edges). Undo domain {@code model}.
 */
public final class ShadingToolDefinitions {

    private final ShadingEditingService shading;
    private final ObjectMapper mapper;

    public ShadingToolDefinitions(ShadingEditingService shading, ObjectMapper mapper) {
        this.shading = shading;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        registry.register(new McpTool(
                "shading_set",
                "Shade faces smooth|flat|auto (smooth + auto smooth angle)|clear_custom (drop custom normals). "
                        + "No part = all parts; no faces = whole part.",
                McpSchema.of(mapper).str("part", "Part").intArr("faces", "Part-local face ids")
                        .enumStr("mode", "Mode", "smooth", "flat", "auto", "clear_custom")
                        .num("angle", "Auto smooth degrees").required("mode").build(),
                args -> shading.set(optString(args, "part"), optIntArray(args, "faces"), reqString(args, "mode"),
                        optFloatBoxed(args, "angle"))));

        registry.register(new McpTool(
                "shading_sharp",
                "Mark (sharp true, default) or clear sharp edges [[a,b],...] by part-local vertex; "
                        + "sharp false without edges clears all.",
                McpSchema.of(mapper).str("part", "Part")
                        .arr("edges", "array", "[[a, b], ...]").bool("sharp", "Mark (true) or clear").build(),
                args -> shading.sharp(optString(args, "part"), edges(args), optBool(args, "sharp", true))));

        registry.register(new McpTool(
                "shading_get",
                "Per part: faces, smooth faces, sharp edges, custom normals, auto smooth.",
                McpSchema.of(mapper).str("part", "Part (default all)").build(),
                args -> shading.get(optString(args, "part"))));
    }

    private static List<int[]> edges(JsonNode args) {
        JsonNode n = args.get("edges");
        if (n == null || n.isNull()) return null;
        if (!n.isArray()) throw new IllegalArgumentException("edges must be [[a, b], ...]");
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < n.size(); i++) {
            JsonNode e = n.get(i);
            if (e == null || !e.isArray() || e.size() != 2 || !e.get(0).isInt() || !e.get(1).isInt()) {
                throw new IllegalArgumentException("edges[" + i + "] must be [a, b] (integers)");
            }
            out.add(new int[] {e.get(0).intValue(), e.get(1).intValue()});
        }
        return out;
    }
}
