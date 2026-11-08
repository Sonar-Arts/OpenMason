package com.openmason.main.systems.particles.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.mcp.McpArgs;
import com.openmason.main.systems.mcp.McpImageWithData;
import com.openmason.main.systems.mcp.McpSchema;
import com.openmason.main.systems.mcp.McpTool;
import com.openmason.main.systems.mcp.McpToolRegistry;

import java.util.ArrayList;
import java.util.List;

import static com.openmason.main.systems.mcp.McpArgs.optBool;
import static com.openmason.main.systems.mcp.McpArgs.optString;

/**
 * The {@code particles} MCP group (OM #101): author engine FX systems as {@code .omfx} v2 documents headlessly —
 * start from presets, edit through the format's own {@code system.json} view (one validated, undoable step per
 * {@code fx_set}), embed sprites, verify by simulation (numbers) or capture (pixels), save through the write
 * sandbox. Shapes and recipes: {@code describe_api topic:"particles"}.
 */
public final class FxToolDefinitions {

    private final FxDocumentService docs;
    private final FxCaptureService capture;
    private final ObjectMapper mapper;

    public FxToolDefinitions(FxDocumentService docs, FxCaptureService capture, ObjectMapper mapper) {
        this.docs = docs;
        this.capture = capture;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        registry.register(new McpTool("fx_presets",
                "Built-in FX systems and effects (fx_new preset), and registered export containers.",
                schema().build(),
                args -> docs.presets()));

        registry.register(new McpTool("fx_new",
                "New in-memory FX document (.omfx v2) 'id', copied from 'preset'. Guide: describe_api "
                        + "topic:'particles'.",
                schema().str("id", "System name / document id").str("preset", "Preset name (fx_presets)")
                        .required("id").build(),
                args -> docs.create(McpArgs.reqString(args, "id"), optString(args, "preset"))));

        registry.register(new McpTool("fx_open",
                "Open an .omfx: v2 as one document; a v1 library migrates to one document per effect.",
                schema().str("path", "File (absolute or project:/exports:/target: path)").required("path").build(),
                args -> docs.open(McpArgs.reqString(args, "path"))));

        registry.register(new McpTool("fx_documents",
                "Open FX documents: id, file, dirty, emitters.",
                schema().build(),
                args -> docs.documents()));

        registry.register(new McpTool("fx_get",
                "A document as its system.json (fx_set's shape), or one 'section' (emitters, light, "
                        + "effect:<name>...), plus sprites and game data.",
                schema().str("id", "Document (omit when one is open)").str("section", "One section only").build(),
                args -> docs.get(optString(args, "id"), optString(args, "section"))));

        registry.register(new McpTool("fx_set",
                "Edit atomically (one undo step). effects/emitters/parameters upsert by name ('rename' fixes "
                        + "references); remove:{section:[names]}; other sections replace whole.",
                schema().str("id", "Document (omit when one is open)")
                        .arr("effects", "object", "Effect patches {name, ...members, rename?}")
                        .arr("emitters", "object", "Emitter patches {name, ...members, rename?}")
                        .arr("parameters", "object", "Parameter patches {name, type, default, rename?}")
                        .obj("remove", "Names to drop per section")
                        .arr("bindings", "object", "Whole bindings section")
                        .arr("events", "object", "Whole events section")
                        .arr("cues", "object", "Whole cues section")
                        .arr("sounds", "object", "Whole sounds section")
                        .obj("light", "The light (null removes)")
                        .str("author", "Author")
                        .obj("extensions", "Game data {\"ns:name\": value | null}")
                        .build(),
                args -> docs.set(optString(args, "id"), args)));

        registry.register(new McpTool("fx_sprite",
                "Embed a PNG/OMT file as the sprite for texture 'name' (an effect with sprite:'texture' lists it in "
                        + "'textures'), replacing one of that name; or remove:true.",
                schema().str("id", "Document (omit when one is open)").str("name", "Texture name")
                        .str("path", "Image file").bool("remove", "Remove the sprite instead").required("name")
                        .build(),
                args -> docs.sprite(optString(args, "id"), McpArgs.reqString(args, "name"), optString(args, "path"),
                        optBool(args, "remove", false))));

        registry.register(new McpTool("fx_undo",
                "Undo (or redo:true) the last fx_set / fx_sprite of a document.",
                schema().str("id", "Document (omit when one is open)").bool("redo", "Redo instead").build(),
                args -> docs.undo(optString(args, "id"), optBool(args, "redo", false))));

        registry.register(new McpTool("fx_simulate",
                "Run a document headlessly in the engine FX runtime: live counts per effect over time, bounds, "
                        + "light, host events, sounds, warnings. Floor at y=0 unless no_floor.",
                simulationSchema().intg("samples", "Timeline samples (default 8)").build(),
                args -> FxSimulation.report(docs.document(docs.resolveId(optString(args, "id"))),
                        simulation(args, 3f))));

        registry.register(new McpTool("fx_capture",
                "PNG of a document after 'seconds' (fx_simulate options), drawn by the engine renderer; "
                        + "auto-framed unless yaw/pitch/distance/target.",
                simulationSchema().intg("size", "Image size px (128..1024, default 512)")
                        .num("yaw", "Orbit yaw, radians").num("pitch", "Orbit pitch, radians")
                        .num("distance", "Camera distance, blocks").vec3("target", "Look-at point").build(),
                args -> {
                    String id = docs.resolveId(optString(args, "id"));
                    FxCaptureService.Camera cam = new FxCaptureService.Camera(McpArgs.optFloatBoxed(args, "yaw"),
                            McpArgs.optFloatBoxed(args, "pitch"), McpArgs.optFloatBoxed(args, "distance"),
                            vec3(args, "target"));
                    FxSimulation.Options sim = simulation(args, 1f);
                    FxCaptureService.Capture c = capture.capture(docs.document(id), sim, cam,
                            McpArgs.optInt(args, "size", FxCaptureService.DEFAULT_SIZE),
                            !optBool(args, "no_floor", false) && sim.floor() == 0f);
                    return new McpImageWithData(c.image(), c.report());
                }));

        registry.register(new McpTool("fx_save",
                "Save as .omfx v2: its own file, else 'path' (bare name -> project Particles/), or prompt:true.",
                schema().str("id", "Document (omit when one is open)").str("path", "Target file")
                        .bool("prompt", "Ask the user where").bool("overwrite", "Replace an existing file").build(),
                args -> docs.save(optString(args, "id"), optString(args, "path"), optBool(args, "prompt", false),
                        optBool(args, "overwrite", false))));

        registry.register(new McpTool("fx_export",
                "Export a document through a registered container (fx_presets exporters; omit when one game format "
                        + "is registered). Without path a game format deploys into the game target's asset folder.",
                schema().str("id", "Document (omit when one is open)").str("exporter", "Exporter id")
                        .str("path", "Target file").bool("prompt", "Ask the user where")
                        .bool("overwrite", "Replace an existing file").build(),
                args -> docs.export(optString(args, "id"), optString(args, "exporter"), optString(args, "path"),
                        optBool(args, "prompt", false), optBool(args, "overwrite", false))));

        registry.register(new McpTool("fx_close",
                "Close a document (unsaved changes need discard:true).",
                schema().str("id", "Document (omit when one is open)").bool("discard", "Drop unsaved changes").build(),
                args -> docs.close(optString(args, "id"), optBool(args, "discard", false))));
    }

    private McpSchema simulationSchema() {
        return schema().str("id", "Document (omit when one is open)")
                .num("seconds", "Simulated seconds (default 3 simulate / 1 capture, max 120)")
                .obj("params", "Parameter values {name: number | [x,y,z] | '#RRGGBBAA'}")
                .arr("raise", "object", "Custom events [{t, name}]")
                .num("stop_at", "Stop the instance at this time").num("stop_fade", "Stop fade seconds")
                .vec3("at", "Instance position (default origin)").num("floor", "Floor height (default 0)")
                .bool("no_floor", "No floor").intg("seed", "Random seed (default 1)");
    }

    static FxSimulation.Options simulation(JsonNode args, float defaultSeconds) {
        float seconds = (float) McpArgs.optDouble(args, "seconds", defaultSeconds);
        double[] at = vec3(args, "at");
        float floor = optBool(args, "no_floor", false) ? Float.NaN : (float) McpArgs.optDouble(args, "floor", 0.0);
        List<FxSimulation.Raise> raises = new ArrayList<>();
        JsonNode raise = args.get("raise");
        if (raise != null && !raise.isNull()) {
            if (!raise.isArray()) {
                throw new IllegalArgumentException("'raise' must be [{t, name}, ...]");
            }
            for (JsonNode r : raise) {
                if (!r.path("name").isTextual()) {
                    throw new IllegalArgumentException("every 'raise' entry needs a 'name' (and 't' seconds)");
                }
                raises.add(new FxSimulation.Raise((float) r.path("t").asDouble(0), r.get("name").asText()));
            }
        }
        return new FxSimulation.Options(seconds, at, floor, args.get("params"), raises,
                args.hasNonNull("stop_at") ? (float) args.get("stop_at").asDouble() : Float.NaN,
                (float) McpArgs.optDouble(args, "stop_fade", 0.0), McpArgs.optInt(args, "samples", 8),
                McpArgs.optInt(args, "seed", 1));
    }

    private static double[] vec3(JsonNode args, String key) {
        float[] v = McpArgs.optFloatArray(args, key);
        if (v == null) {
            return null;
        }
        if (v.length != 3) {
            throw new IllegalArgumentException("'" + key + "' must be [x, y, z]");
        }
        return new double[]{v[0], v[1], v[2]};
    }

    private McpSchema schema() {
        return McpSchema.of(mapper);
    }
}
