package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartShapeFactory;
import com.openmason.main.systems.skeleton.vertexdata.VertexDataAuthoring;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code morph_*} / {@code vgroup_*} MCP tools through their real handlers over a headless one-cube model:
 * create / rename / range / remove, explicit deltas, a capture from vertex moves, group assign / unassign /
 * hidden preview, refusals that name the fix, and the {@code morph} undo domain through {@code undo} / {@code redo}.
 */
class VertexDataEditingServiceTest {

    private final ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private ModelPartManager parts;
    private VertexDataAuthoring data;
    private McpToolRegistry registry;

    @BeforeEach
    void setUp() {
        MainThreadExecutor.bindToCurrentThread();
        parts = new ModelPartManager();
        parts.addPartFromGeometry("body", PartShapeFactory.createGeometry(PartShapeFactory.Shape.CUBE, "c",
                new Vector3f(1, 1, 1)), new Vector3f());
        data = new VertexDataAuthoring(parts);
        VertexDataEditingService service = new VertexDataEditingService(
                () -> new VertexDataEditingService.Context(data, null));
        registry = new McpToolRegistry();
        new VertexDataToolDefinitions(service, mapper).registerAll(registry);
        new MetaToolDefinitions(null, null, null, null, null, null, null, service, mapper, McpGuide.builtin())
                .registerAll(registry);
    }

    private JsonNode call(String tool, String argsJson) throws Exception {
        return mapper.valueToTree(registry.get(tool).handler().call(mapper.readTree(argsJson.replace('\'', '"'))));
    }

    private String fails(String tool, String argsJson) {
        return assertThrows(RuntimeException.class,
                () -> registry.get(tool).handler().call(mapper.readTree(argsJson.replace('\'', '"')))).getMessage();
    }

    @Test
    void shapeKeysThroughTheTools() throws Exception {
        call("morph_set", "{'name':'Smile','create':true,'max':2}");
        JsonNode ack = call("morph_capture", "{'mode':'deltas','name':'Smile','part':'body','vertices':[0,1],"
                + "'deltas':[0,0.5,0, 0,0,0]}");
        assertEquals(1, ack.at("/key/vertices").asInt(), "the zero row is dropped");
        call("morph_capture", "{'mode':'deltas','name':'Frown','part':'body','vertices':[2],'deltas':[1,0,0]}");
        call("morph_set", "{'name':'Smile','rename':'Grin','default':0.5,'value':5}");

        JsonNode list = call("morph_list", "{}");
        assertEquals("Grin", list.at("/keys/0/name").asText());
        assertEquals("Frown", list.at("/keys/1/name").asText());
        assertEquals(0.5, list.at("/keys/0/default").asDouble(), 1e-6);
        assertEquals(2.0, list.at("/keys/0/value").asDouble(), 1e-6, "the preview value is clamped to the range");
        JsonNode rows = call("morph_list", "{'key':'Grin','part':'body'}");
        assertEquals(0, rows.at("/rows/0/v").asInt());
        assertEquals(0.5, rows.at("/rows/0/d/1").asDouble(), 1e-6);

        assertTrue(fails("morph_set", "{'name':'Nope','rename':'X'}").contains("morph_list"));
        assertTrue(fails("morph_set", "{'name':'Grin','min':3}").contains("above max"));
        assertTrue(fails("morph_capture", "{'mode':'deltas','name':'K','part':'body','vertices':[999],'deltas':[0,0,1]}")
                .contains("out of range"));
        assertTrue(fails("morph_capture", "{'mode':'mesh','name':'K'}").contains("begin"));

        // Capture from vertex moves (headless: no viewport to restore the basis into).
        call("morph_capture", "{'mode':'begin'}");
        assertTrue(call("morph_list", "{}").get("capturing").asBoolean());
        parts.updatePartVertex(parts.getAllParts().get(0).id(), 3, 9f, 9f, 9f);
        JsonNode cap = call("morph_capture", "{'mode':'mesh','name':'Pull'}");
        assertTrue(cap.at("/key/vertices").asInt() >= 1);
        assertEquals(3, data.shapeKeys().size());

        call("morph_set", "{'name':'Pull','remove':true}");
        assertEquals(2, data.shapeKeys().size());
        assertTrue(call("undo", "{'domain':'morph'}").asBoolean());
        assertEquals(3, data.shapeKeys().size());
        assertTrue(call("redo", "{'domain':'morph'}").asBoolean());
        assertEquals(2, data.shapeKeys().size());
    }

    @Test
    void vertexGroupsThroughTheTools() throws Exception {
        call("vgroup_set", "{'name':'Skin','create':true,'part':'body','vertices':[0,1,2],'weight':0.5}");
        call("vgroup_set", "{'name':'Skin','part':'body','vertices':[2],'weights':[1]}");
        call("vgroup_set", "{'name':'Hair','create':true}");
        data.preview().setHidden("Hair", true); // the rigging pane's Hide toggle
        JsonNode list = call("vgroup_list", "{}");
        assertEquals(3, list.at("/groups/0/vertices").asInt());
        assertTrue(list.at("/groups/1/hidden").asBoolean(), "the list reports the preview's hidden groups");
        JsonNode rows = call("vgroup_list", "{'group':'Skin','part':'body'}");
        assertEquals(1.0, rows.at("/rows/2/w").asDouble(), 1e-6);
        assertEquals(0.5, rows.at("/rows/0/w").asDouble(), 1e-6);

        call("vgroup_set", "{'name':'Skin','part':'body','vertices':[1],'unassign':true}");
        assertEquals(2, call("vgroup_list", "{'group':'Skin'}").at("/group/vertices").asInt());
        call("vgroup_set", "{'name':'Skin','rename':'Torso'}");
        assertEquals("Torso", call("vgroup_list", "{}").at("/groups/0/name").asText());
        call("vgroup_set", "{'name':'Torso','part':'body','unassign':true}");
        assertEquals(0, call("vgroup_list", "{'group':'Torso'}").at("/group/vertices").asInt());

        assertTrue(fails("vgroup_set", "{'name':'Torso','vertices':[0]}").contains("part is required"));
        assertTrue(fails("vgroup_list", "{'group':'Nope'}").contains("vgroup_list"));

        call("vgroup_set", "{'name':'Hair','remove':true}");
        assertFalse(data.preview().isHidden("Hair"));
        assertTrue(call("undo", "{'domain':'morph'}").asBoolean());
        assertEquals(2, data.groups().size());
    }
}
