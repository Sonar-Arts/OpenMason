package com.openmason.engine.format.fx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared JSON glue for the manifest {@code fx[]} section, used by the SBO and SBE parsers/serializers (and any
 * other container) so the wire shape is the same everywhere.
 *
 * <p>Wire shape (one object per {@link FxDef}):
 * <pre>{@code
 * "fx": [
 *   {"event": "break", "fx": "debris", "offset": [0, 0.5, 0], "tintFromTexture": true},
 *   {"event": "ambient", "fx": "campfire", "params": {"intensity": 0.6, "wind": [1, 0, 0]}}
 * ]
 * }</pre>
 *
 * <p>Absent keys mean: no offset, no parameter overrides, no tint. The writer leaves them out in that case, so a
 * plain binding is just {@code {"event", "fx"}}.
 */
public final class FxJson {

    /** The manifest key of the section. */
    public static final String FIELD = "fx";

    private static final Logger logger = LoggerFactory.getLogger(FxJson.class);

    private FxJson() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads the optional {@code fx} array from a manifest root node. Invalid entries are skipped with a warning
     * (the {@code sounds[]} convention); returns null when the section is absent or ends up empty, so older files
     * simply lack it.
     */
    public static FxData read(JsonNode root) {
        if (root == null || !root.has(FIELD) || !root.get(FIELD).isArray()) {
            return null;
        }
        List<FxDef> defs = new ArrayList<>();
        int index = 0;
        for (JsonNode node : root.get(FIELD)) {
            try {
                defs.add(readDef(node));
            } catch (IllegalArgumentException | NullPointerException ex) {
                logger.warn("Skipping invalid fx binding #{} in manifest: {}", index, ex.getMessage());
            }
            index++;
        }
        return defs.isEmpty() ? null : new FxData(defs);
    }

    private static FxDef readDef(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("an fx binding is a JSON object");
        }
        float ox = 0f;
        float oy = 0f;
        float oz = 0f;
        JsonNode offset = node.get("offset");
        if (offset != null && !offset.isNull()) {
            if (!offset.isArray() || offset.size() != 3 || !allNumbers(offset)) {
                throw new IllegalArgumentException("fx offset must be [x, y, z], got " + offset);
            }
            ox = (float) offset.get(0).asDouble();
            oy = (float) offset.get(1).asDouble();
            oz = (float) offset.get(2).asDouble();
        }
        Map<String, FxParamValue> params = new LinkedHashMap<>();
        JsonNode p = node.get("params");
        if (p != null && !p.isNull()) {
            if (!p.isObject()) {
                throw new IllegalArgumentException("fx params must be an object, got " + p);
            }
            Iterator<Map.Entry<String, JsonNode>> fields = p.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                params.put(f.getKey(), readValue(f.getKey(), f.getValue()));
            }
        }
        return new FxDef(
                node.hasNonNull("event") ? node.get("event").asText() : null,
                node.hasNonNull(FIELD) ? node.get(FIELD).asText() : null,
                ox, oy, oz,
                params,
                node.hasNonNull("tintFromTexture") && node.get("tintFromTexture").asBoolean());
    }

    private static FxParamValue readValue(String name, JsonNode value) {
        if (value.isNumber()) {
            return FxParamValue.scalar((float) value.asDouble());
        }
        if (value.isArray() && allNumbers(value)) {
            List<Float> components = new ArrayList<>(value.size());
            for (JsonNode c : value) components.add((float) c.asDouble());
            return new FxParamValue(components);
        }
        throw new IllegalArgumentException("fx parameter '" + name + "' must be a number or a number array, got "
                + value);
    }

    private static boolean allNumbers(JsonNode array) {
        for (JsonNode c : array) {
            if (!c.isNumber()) return false;
        }
        return true;
    }

    /** The section as JSON for a manifest writer; null when there is nothing to write (the key stays out). */
    public static ArrayNode toJson(FxData data) {
        if (data == null || data.isEmpty()) return null;
        JsonNodeFactory f = JsonNodeFactory.instance;
        ArrayNode array = f.arrayNode();
        for (FxDef def : data.fx()) {
            ObjectNode o = array.addObject();
            o.put("event", def.event());
            o.put(FIELD, def.fx());
            if (def.hasOffset()) {
                o.putArray("offset").add(def.offsetX()).add(def.offsetY()).add(def.offsetZ());
            }
            if (!def.params().isEmpty()) {
                ObjectNode params = o.putObject("params");
                for (Map.Entry<String, FxParamValue> e : def.params().entrySet()) {
                    FxParamValue v = e.getValue();
                    if (v.isScalar()) {
                        params.put(e.getKey(), v.get(0));
                    } else {
                        ArrayNode components = params.putArray(e.getKey());
                        for (Float c : v.components()) components.add(c);
                    }
                }
            }
            if (def.tintFromTexture()) {
                o.put("tintFromTexture", true);
            }
        }
        return array;
    }
}
