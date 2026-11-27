package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static com.openmason.main.systems.mcp.McpArgs.optBool;
import static com.openmason.main.systems.mcp.McpArgs.optFloatArray;
import static com.openmason.main.systems.mcp.McpArgs.optFloatBoxed;
import static com.openmason.main.systems.mcp.McpArgs.optInt;
import static com.openmason.main.systems.mcp.McpArgs.optIntArray;
import static com.openmason.main.systems.mcp.McpArgs.optString;
import static com.openmason.main.systems.mcp.McpArgs.optStringList;
import static com.openmason.main.systems.mcp.McpArgs.reqFloat;
import static com.openmason.main.systems.mcp.McpArgs.reqInt;
import static com.openmason.main.systems.mcp.McpArgs.reqString;

/**
 * The {@code skin_*} MCP tools over {@link SkinEditingService}: per-vertex skin weights (which joints a
 * vertex follows), auto weights, clean-up, mirroring and brush painting. Undo domain {@code skin}.
 * Guide topic: {@code describe_api skinning}.
 */
public final class SkinToolDefinitions {

    private final SkinEditingService skin;
    private final ObjectMapper mapper;

    public SkinToolDefinitions(SkinEditingService skin, ObjectMapper mapper) {
        this.skin = skin;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        registry.register(new McpTool(
                "skin_info",
                "Skin weights per part: verts, weighted, joints used.",
                schema().build(),
                args -> skin.info()));

        registry.register(new McpTool(
                "skin_get_weights",
                "Vertex influences {v, joints, weights}: listed vertices, else all weighted (max "
                        + SkinEditingService.MAX_ROWS + ").",
                schema().str("part", "Part").intArr("vertices", "Part-local indices")
                        .required("part").build(),
                args -> skin.getWeights(reqString(args, "part"), optIntArray(args, "vertices"))));

        registry.register(new McpTool(
                "skin_set_weights",
                "Set influences: entries [{vertex, joints:[bone/part], weights}], any count, normalized; "
                        + "empty joints = follow the part.",
                schema().str("part", "Part")
                        .arr("entries", "object", "[{vertex, joints, weights}]")
                        .required("part", "entries").build(),
                args -> skin.setWeights(reqString(args, "part"), entries(args))));

        registry.register(new McpTool(
                "skin_auto_weights",
                "Bind parts to bones by distance (replaces weights). Defaults: all parts/bones, "
                        + "falloff 2, min_weight 0.05, max_influences 0 (= every bone); rigid = nearest bone.",
                schema().strArray("parts", "Parts").strArray("bones", "Bones")
                        .num("falloff", "Falloff exponent").num("min_weight", "Prune below")
                        .num("max_influences", "Nearest bones per vertex, 0 = unlimited")
                        .bool("rigid", "Nearest bone only").build(),
                args -> skin.autoWeights(optStringList(args, "parts"), optStringList(args, "bones"),
                        optFloatBoxed(args, "falloff"), optFloatBoxed(args, "min_weight"),
                        optBool(args, "rigid", false),
                        optInt(args, "max_influences", 0))));

        registry.register(new McpTool(
                "skin_normalize",
                "Normalize weights (default all parts).",
                schema().strArray("parts", "Parts").build(),
                args -> skin.normalize(optStringList(args, "parts"))));

        registry.register(new McpTool(
                "skin_prune",
                "Drop influences below min_weight (default all parts).",
                schema().num("min_weight", "Threshold").strArray("parts", "Parts")
                        .required("min_weight").build(),
                args -> skin.prune(reqFloat(args, "min_weight"), optStringList(args, "parts"))));

        registry.register(new McpTool(
                "skin_mirror",
                "Mirror weights across model-space axis=0 from the source_sign side, across parts; "
                        + "joints swap by name (_l/_r, .L/.R, left/right).",
                schema().enumStr("axis", "Mirror axis", "x", "y", "z")
                        .intg("source_sign", "1: + to -, -1: - to +")
                        .num("tolerance", "Match distance (0.01)")
                        .strArray("parts", "Parts (default all)")
                        .required("axis", "source_sign").build(),
                args -> skin.mirror(reqString(args, "axis"), reqInt(args, "source_sign"),
                        optFloatBoxed(args, "tolerance"), optStringList(args, "parts"))));

        registry.register(new McpTool(
                "skin_clear",
                "Remove weights (part, else all); vertices follow their part.",
                schema().str("part", "Part").build(),
                args -> skin.clear(optString(args, "part"))));

        registry.register(new McpTool(
                "skin_paint",
                "One brush dab at a model-space center: add/subtract/replace (toward strength)/smooth "
                        + "the joint's weight.",
                schema().str("part", "Limit to a part")
                        .vec3("center", "Model space").num("radius", "Radius")
                        .str("joint", "Bone/part")
                        .enumStr("mode", "Mode", "add", "subtract", "replace", "smooth")
                        .num("strength", "0..1")
                        .required("center", "radius", "joint", "mode", "strength").build(),
                args -> skin.paint(optString(args, "part"), optFloatArray(args, "center"),
                        reqFloat(args, "radius"), reqString(args, "joint"), reqString(args, "mode"),
                        reqFloat(args, "strength"))));
    }

    private static List<SkinEditingService.WeightEntry> entries(JsonNode args) {
        JsonNode n = args.get("entries");
        if (n == null || !n.isArray() || n.isEmpty()) {
            throw new IllegalArgumentException("entries must be a non-empty array of {vertex, joints, weights}");
        }
        List<SkinEditingService.WeightEntry> out = new ArrayList<>();
        for (int i = 0; i < n.size(); i++) {
            JsonNode e = n.get(i);
            if (e == null || !e.isObject() || !e.has("vertex") || !e.get("vertex").isNumber()) {
                throw new IllegalArgumentException("entries[" + i + "] needs an integer vertex");
            }
            List<String> joints = optStringList(e, "joints");
            float[] weights = optFloatArray(e, "weights");
            out.add(new SkinEditingService.WeightEntry(e.get("vertex").intValue(), joints,
                    weights == null ? new float[0] : weights));
        }
        return out;
    }

    private McpSchema schema() {
        return McpSchema.of(mapper);
    }
}
