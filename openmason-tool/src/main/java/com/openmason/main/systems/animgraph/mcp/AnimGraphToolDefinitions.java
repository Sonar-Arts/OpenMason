package com.openmason.main.systems.animgraph.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.mcp.McpArgs;
import com.openmason.main.systems.mcp.McpSchema;
import com.openmason.main.systems.mcp.McpTool;
import com.openmason.main.systems.mcp.McpToolRegistry;

import java.util.ArrayList;
import java.util.List;

import static com.openmason.main.systems.mcp.McpArgs.optBool;
import static com.openmason.main.systems.mcp.McpArgs.optString;

/**
 * The {@code animgraph} MCP group (OpenMason #164): build and debug {@code .omanimgraph} animation graphs through the
 * graph open in the Animation Graph tab. Each apply is one undo step. Guide: describe_api topic:"animgraph".
 */
public final class AnimGraphToolDefinitions {

    private final AnimGraphEditingService service;
    private final ObjectMapper mapper;

    public AnimGraphToolDefinitions(AnimGraphEditingService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        registry.register(new McpTool("animgraph_new",
                "New animation graph in the Animation Graph tab. Guide: describe_api topic:'animgraph'.",
                schema().str("name", "Graph name").str("profile", "Skeleton profile (humanoid...)")
                        .bool("discard", "Drop unsaved changes of the open graph").build(),
                args -> service.create(optString(args, "name"), optString(args, "profile"), optBool(args, "discard", false))));

        registry.register(new McpTool("animgraph_open",
                "Open an .omanimgraph (drafts too) in the Animation Graph tab.",
                schema().str("path", "File (absolute or project:/target: path)").bool("discard", "Drop unsaved changes")
                        .required("path").build(),
                args -> service.open(McpArgs.reqString(args, "path"), optBool(args, "discard", false))));

        registry.register(new McpTool("animgraph_get",
                "The open graph: section view (nodes, inputs, machines, problems; default), json (file text) or problems.",
                schema().enumStr("section", "What to return", "view", "json", "problems").build(),
                args -> service.get(optString(args, "section"))));

        registry.register(new McpTool("animgraph_apply",
                "Edit atomically (one undo step): ops graph, param, slot, mask, node, link, unlink, state, transition, "
                        + "sample, section, position, layout, editor (see the guide).",
                schema().arr("ops", "object", "Operations {op, ...}").str("label", "Undo label").required("ops").build(),
                args -> service.apply(args.get("ops"), optString(args, "label"))));

        registry.register(new McpTool("animgraph_undo",
                "Undo (or redo:true) the last graph edit (tab and tools share one history).",
                schema().bool("redo", "Redo instead").build(),
                args -> service.undo(optBool(args, "redo", false))));

        registry.register(new McpTool("animgraph_preview",
                "Preview setup (saved in the graph): character (.omchar or model) and slots {slot: 'model:<clip>' | "
                        + "'file:<.omanim>' | null}; lists the model's clips and binding results.",
                schema().str("character", "Character or model file").obj("slots", "Slot sources").build(),
                args -> service.preview(optString(args, "character"), args.get("slots"))));

        registry.register(new McpTool("animgraph_evaluate",
                "Set params {name: value}, fire triggers, run frames x dt on the live preview (the Debugger's numbers; "
                        + "restart:true first) or a fresh instance (headless:true); returns per-node debug values.",
                schema().obj("params", "Parameter values").strArray("triggers", "Triggers to fire")
                        .intg("frames", "Frames (default 1)").num("dt", "Seconds per frame (default 1/60)")
                        .bool("restart", "Restart the live preview first").bool("headless", "Evaluate a fresh instance")
                        .build(),
                args -> service.evaluate(args.get("params"), strings(args.get("triggers")), McpArgs.optInt(args, "frames", 1),
                        McpArgs.optFloatBoxed(args, "dt") == null ? 1f / 60f : McpArgs.optFloatBoxed(args, "dt"),
                        optBool(args, "restart", false), optBool(args, "headless", false))));

        registry.register(new McpTool("animgraph_save",
                "Write the .omanimgraph (drafts too): in place, or 'path' (a bare name lands in the project's "
                        + "AnimGraphs folder).",
                schema().str("path", "Target").bool("prompt", "Let the user pick").bool("overwrite", "Replace an existing file")
                        .build(),
                args -> service.save(optString(args, "path"), optBool(args, "prompt", false), optBool(args, "overwrite", false))));

        registry.register(new McpTool("animgraph_close",
                "Close the open graph (discard:true drops unsaved changes).",
                schema().bool("discard", "Drop unsaved changes").build(),
                args -> service.close(optBool(args, "discard", false))));
    }

    private McpSchema schema() {
        return McpSchema.of(mapper);
    }

    private static List<String> strings(JsonNode n) {
        if (n == null || n.isNull()) return null;
        List<String> out = new ArrayList<>();
        if (n.isArray()) n.forEach(x -> out.add(x.asText()));
        else out.add(n.asText());
        return out;
    }
}
