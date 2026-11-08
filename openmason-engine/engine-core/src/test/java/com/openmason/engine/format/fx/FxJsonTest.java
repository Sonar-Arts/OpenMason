package com.openmason.engine.format.fx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared {@code fx[]} wire shape (#103): exact round trip, compact output, lenient-but-loud reading. */
@Tag("regression")
class FxJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static FxData kitchenSink() {
        Map<String, FxParamValue> params = new LinkedHashMap<>();
        params.put("intensity", FxParamValue.scalar(0.6f));
        params.put("wind", FxParamValue.vector(1f, 0f, -0.5f));
        params.put("tint", FxParamValue.color(1f, 0.5f, 0.25f, 1f));
        return new FxData(List.of(
                new FxDef("break", "debris", 0f, 0.5f, 0f, Map.of(), true),
                new FxDef("ambient", "mygame:campfire", 0f, 0f, 0f, params, false),
                FxDef.of("hurt", "blood"),
                FxDef.of("break", "dust")));
    }

    private static FxData roundTrip(FxData data) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode array = FxJson.toJson(data);
        if (array != null) root.set(FxJson.FIELD, array);
        // Through text, as a manifest is.
        return FxJson.read(JSON.readTree(JSON.writeValueAsString(root)));
    }

    @Test
    void everyFieldSurvivesARoundTrip() throws Exception {
        FxData data = kitchenSink();
        assertEquals(data, roundTrip(data));
    }

    @Test
    void plainBindingsWriteOnlyEventAndFx() {
        ArrayNode array = FxJson.toJson(new FxData(List.of(FxDef.of("place", "puff"))));
        JsonNode only = array.get(0);
        assertEquals(List.of("event", "fx"), fieldNames(only));
        assertEquals("place", only.get("event").asText());
        assertEquals("puff", only.get("fx").asText());
    }

    @Test
    void scalarsAreBareNumbersAndOthersAreArrays() {
        JsonNode params = FxJson.toJson(kitchenSink()).get(1).get("params");
        assertTrue(params.get("intensity").isNumber());
        assertEquals(3, params.get("wind").size());
        assertEquals(4, params.get("tint").size());
    }

    @Test
    void absentEmptyOrNullSectionReadsAsNull() throws Exception {
        assertNull(FxJson.read(JSON.readTree("{}")));
        assertNull(FxJson.read(JSON.readTree("{\"fx\": null}")));
        assertNull(FxJson.read(JSON.readTree("{\"fx\": []}")));
        assertNull(FxJson.read(null));
        assertNull(FxJson.toJson(null));
        assertNull(FxJson.toJson(new FxData(List.of())));
    }

    @Test
    void missingOptionalKeysTakeTheirDefaults() throws Exception {
        FxData data = FxJson.read(JSON.readTree("{\"fx\": [{\"event\": \"step\", \"fx\": \"dust\"}]}"));
        FxDef def = data.fx().get(0);
        assertFalse(def.hasOffset());
        assertTrue(def.params().isEmpty());
        assertFalse(def.tintFromTexture());
    }

    @Test
    void invalidEntriesAreSkippedAndTheRestKept() throws Exception {
        FxData data = FxJson.read(JSON.readTree("""
                {"fx": [
                  {"event": "break", "fx": "debris"},
                  {"event": "", "fx": "x"},
                  {"event": "hit"},
                  {"event": "hit", "fx": "x", "offset": [1, 2]},
                  {"event": "hit", "fx": "x", "params": {"bad": [1, 2]}},
                  {"event": "hit", "fx": "x", "params": {"bad": "red"}},
                  "not an object",
                  {"event": "place", "fx": "puff"}
                ]}"""));
        assertEquals(List.of("debris", "puff"), data.fx().stream().map(FxDef::fx).toList());
    }

    @Test
    void lookupsByEventAndFx() {
        FxData data = kitchenSink();
        assertEquals(List.of("debris", "dust"), data.defsFor("break").stream().map(FxDef::fx).toList());
        assertTrue(data.defsFor("BREAK").isEmpty(), "event names are case-sensitive");
        assertEquals(List.of("break", "ambient", "hurt"), List.copyOf(data.events()));
        assertEquals(List.of("debris", "mygame:campfire", "blood", "dust"), List.copyOf(data.fxIds()));
    }

    @Test
    void definitionsRefuseBadValues() {
        assertThrows(IllegalArgumentException.class, () -> FxDef.of(" ", "x"));
        assertThrows(IllegalArgumentException.class, () -> FxDef.of("break", ""));
        assertThrows(NullPointerException.class, () -> FxDef.of("break", null));
        assertThrows(IllegalArgumentException.class,
                () -> new FxDef("break", "x", Float.NaN, 0f, 0f, Map.of(), false));
        assertThrows(IllegalArgumentException.class,
                () -> new FxDef("break", "x", 0f, 0f, 0f, Map.of(" ", FxParamValue.scalar(1f)), false));
        assertThrows(IllegalArgumentException.class, () -> new FxParamValue(List.of(1f, 2f)));
        assertThrows(IllegalArgumentException.class, () -> new FxParamValue(List.of()));
        assertThrows(IllegalArgumentException.class, () -> FxParamValue.scalar(Float.POSITIVE_INFINITY));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
