package com.openmason.engine.rendering.particles.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JSON form of the {@link FxGraph} IR ({@value #FORMAT} v{@value FxGraph#VERSION}):
 * <pre>{@code
 * { "format": "openmason.fxgraph", "version": 1,
 *   "attributes": [ { "name": "heat", "type": "float", "default": [0] } ],
 *   "stages": [ { "stage": "particle_update", "modules": [
 *       { "name": "lifetime", "builtin": "lifetime" },
 *       { "name": "swirl", "nodes": [
 *           { "id": "p", "op": "read", "attribute": "position" },
 *           { "id": "c", "op": "curl", "in": ["p"] },
 *           { "id": "k", "op": "const", "values": [0.8] }, ... ] } ] } ] }
 * }</pre>
 * Writing then reading reproduces the graph exactly (records compare equal).
 */
public final class FxGraphJson {

    public static final String FORMAT = "openmason.fxgraph";

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private FxGraphJson() {
    }

    public static String write(FxGraph graph) {
        try {
            return MAPPER.writeValueAsString(toJson(graph));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static FxGraph read(String json) throws IOException {
        return fromJson(MAPPER.readTree(json));
    }

    public static ObjectNode toJson(FxGraph graph) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("format", FORMAT);
        root.put("version", graph.version());
        ArrayNode attrs = root.putArray("attributes");
        for (FxAttribute a : graph.attributes()) {
            ObjectNode n = attrs.addObject();
            n.put("name", a.name());
            n.put("type", lower(a.type()));
            ArrayNode d = n.putArray("default");
            a.defaults().forEach(d::add);
        }
        ArrayNode stages = root.putArray("stages");
        for (FxStage s : graph.stages()) {
            ObjectNode sn = stages.addObject();
            sn.put("stage", lower(s.kind()));
            ArrayNode mods = sn.putArray("modules");
            for (FxModule m : s.modules()) {
                ObjectNode mn = mods.addObject();
                mn.put("name", m.name());
                if (m.isBuiltin()) {
                    mn.put("builtin", m.builtin());
                    continue;
                }
                ArrayNode nodes = mn.putArray("nodes");
                for (FxNode node : m.nodes()) {
                    ObjectNode nn = nodes.addObject();
                    nn.put("id", node.id());
                    nn.put("op", node.op().id());
                    if (!node.inputs().isEmpty()) {
                        ArrayNode in = nn.putArray("in");
                        node.inputs().forEach(in::add);
                    }
                    if (!node.values().isEmpty()) {
                        ArrayNode v = nn.putArray("values");
                        node.values().forEach(v::add);
                    }
                    if (node.attribute() != null) {
                        nn.put("attribute", node.attribute());
                    }
                }
            }
        }
        return root;
    }

    public static FxGraph fromJson(JsonNode root) throws IOException {
        if (root == null || !root.isObject()) {
            throw new IOException("FX graph must be a JSON object");
        }
        String format = root.path("format").asText(FORMAT);
        if (!FORMAT.equals(format)) {
            throw new IOException("not an FX graph: format '" + format + "'");
        }
        int version = root.path("version").asInt(FxGraph.VERSION);
        try {
            List<FxAttribute> attributes = new ArrayList<>();
            for (JsonNode a : root.path("attributes")) {
                attributes.add(new FxAttribute(text(a, "name"), FxType.valueOf(upper(text(a, "type"))), floats(a.get("default"))));
            }
            List<FxStage> stages = new ArrayList<>();
            for (JsonNode s : root.path("stages")) {
                List<FxModule> modules = new ArrayList<>();
                for (JsonNode m : s.path("modules")) {
                    String name = m.path("name").asText(m.path("builtin").asText(""));
                    if (m.has("builtin")) {
                        modules.add(new FxModule(name, m.get("builtin").asText(), List.of()));
                        continue;
                    }
                    List<FxNode> nodes = new ArrayList<>();
                    for (JsonNode n : m.path("nodes")) {
                        List<String> in = new ArrayList<>();
                        for (JsonNode i : n.path("in")) {
                            in.add(i.asText());
                        }
                        nodes.add(new FxNode(text(n, "id"), FxOp.of(text(n, "op")), in, floats(n.get("values")),
                                n.has("attribute") ? n.get("attribute").asText() : null));
                    }
                    modules.add(new FxModule(name, null, nodes));
                }
                stages.add(new FxStage(FxStageKind.valueOf(upper(text(s, "stage"))), modules));
            }
            return new FxGraph(version, attributes, stages);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid FX graph: " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || !v.isTextual()) {
            throw new IllegalArgumentException("missing '" + key + "'");
        }
        return v.asText();
    }

    private static List<Float> floats(JsonNode n) {
        List<Float> out = new ArrayList<>();
        if (n != null) {
            for (JsonNode v : n) {
                if (!v.isNumber()) {
                    throw new IllegalArgumentException("values must be numbers");
                }
                out.add(v.floatValue());
            }
        }
        return out;
    }

    private static String lower(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    private static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }
}
