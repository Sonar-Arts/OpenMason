package com.openmason.main.systems.mcp;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.modeling.parts.PartShapeFactory;
import com.openmason.engine.modeling.parts.PartTransform;
import com.openmason.engine.rig.CendaRig;
import com.openmason.main.systems.skeleton.BoneStore;
import com.openmason.main.systems.skeleton.SkinAuthoring;
import com.openmason.main.systems.skeleton.SkinCommandHistory;
import com.openmason.main.systems.skeleton.SkinStore;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The MCP skin tools' service over a tiny two-arm model: a left and a right cube, each with a vertical
 * bone through it ({@code bone_l} / {@code bone_r}), mirror images across x = 0. Weight math is native,
 * so the test skips without the Cenda rig library.
 */
class SkinEditingServiceTest {

    private ModelPartManager parts;
    private SkinStore skins;
    private SkinCommandHistory history;
    private SkinEditingService service;
    private SkinAuthoring authoring;

    @BeforeEach
    void setUp() {
        assumeTrue(CendaRig.isAvailable(), "native rig unavailable: " + CendaRig.unavailableReason());
        MainThreadExecutor.bindToCurrentThread();

        parts = new ModelPartManager();
        PartMeshRebuilder.PartGeometry cube = PartShapeFactory.createGeometry(PartShapeFactory.Shape.CUBE, "c",
                new Vector3f(1, 1, 1));
        float cx = centerX(cube.vertices());
        place(parts.addPartFromGeometry("arm_l", cube, new Vector3f()), 2f - cx);
        PartMeshRebuilder.PartGeometry cube2 = PartShapeFactory.createGeometry(PartShapeFactory.Shape.CUBE, "c",
                new Vector3f(1, 1, 1));
        place(parts.addPartFromGeometry("arm_r", cube2, new Vector3f()), -2f - cx);

        BoneStore bones = new BoneStore();
        float cy = centerY(cube.vertices());
        bones.put(bone("b-l", "bone_l", 2f, cy - 1f));
        bones.put(bone("b-r", "bone_r", -2f, cy - 1f));

        skins = new SkinStore();
        history = new SkinCommandHistory(skins);
        authoring = new SkinAuthoring(parts, bones, skins);
        service = new SkinEditingService(() -> new SkinEditingService.Context(authoring, history));
    }

    private void place(ModelPartDescriptor part, float x) {
        parts.setPartTransform(part.id(), new PartTransform(new Vector3f(), new Vector3f(x, 0, 0),
                new Vector3f(), new Vector3f(1, 1, 1)));
    }

    /** A bone from (x, y0, 0) two units up. */
    private static OMOFormat.BoneEntry bone(String id, String name, float x, float y0) {
        return new OMOFormat.BoneEntry(id, name, null, x, y0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0);
    }

    @Test
    void rigidAutoWeightsBindEachArmToItsBone() {
        service.autoWeights(null, null, null, null, true);
        assertEveryRow("arm_l", "bone_l");
        assertEveryRow("arm_r", "bone_r");
        assertTrue(history.canUndo());
    }

    @Test
    void falloffAutoWeightsPruneTheFarBone() {
        service.autoWeights(List.of("arm_l"), List.of("bone_l", "bone_r"), 2f, 0.05f, false);
        assertEveryRow("arm_l", "bone_l");
        assertEquals(0, weighted("arm_r"));
    }

    @Test
    void setAndGetWeightsResolveJointsByName() {
        service.setWeights("arm_r", List.of(new SkinEditingService.WeightEntry(0, List.of("bone_r", "arm_r"),
                new float[] {0.25f, 0.75f})));
        Map<String, Object> got = service.getWeights("arm_r", new int[] {0, 1});
        List<?> rows = (List<?>) got.get("vertices");
        Map<?, ?> v0 = (Map<?, ?>) rows.get(0);
        assertEquals(List.of("arm_r", "bone_r"), v0.get("joints"), "heaviest first");
        float[] w = (float[]) v0.get("weights");
        assertEquals(0.75f, w[0], 1e-4f);
        assertEquals(0.25f, w[1], 1e-4f);
        assertEquals(Boolean.TRUE, ((Map<?, ?>) rows.get(1)).get("follows_part"));
        assertEquals(1, weighted("arm_r"));
    }

    @Test
    void normalizeAndPrune() {
        service.setWeights("arm_l", List.of(new SkinEditingService.WeightEntry(3, List.of("bone_l", "bone_r"),
                new float[] {0.98f, 0.02f})));
        McpAck pruned = service.prune(0.05f, List.of("arm_l"));
        assertEquals(1, pruned.fields().get("removed"));
        Map<?, ?> row = (Map<?, ?>) ((List<?>) service.getWeights("arm_l", new int[] {3}).get("vertices")).get(0);
        assertEquals(List.of("bone_l"), row.get("joints"));
        assertEquals(1f, ((float[]) row.get("weights"))[0], 1e-4f);
        assertEquals(1, service.normalize(null).fields().get("weighted"));
    }

    @Test
    void mirrorCopiesTheLeftArmOntoTheRightAndUndoes() {
        service.autoWeights(List.of("arm_l"), null, null, null, true);
        assertEquals(0, weighted("arm_r"));

        McpAck ack = service.mirror("x", 1, 0.01f, null);
        assertEquals(vertexCount("arm_l"), ack.fields().get("written"), "every left vertex found a partner");
        assertEveryRow("arm_r", "bone_r"); // split-UV duplicates of each corner included

        assertTrue(service.undo());
        assertEquals(0, weighted("arm_r"));
        assertTrue(service.redo());
        assertEveryRow("arm_r", "bone_r");
    }

    @Test
    void paintRaisesTheJointNearTheBrush() {
        service.autoWeights(List.of("arm_l"), null, null, null, true);
        float[] center = {2f, centerY(parts.getPartGeometry(id("arm_l")).vertices()), 0f};
        McpAck ack = service.paint("arm_l", center, 5f, "arm_l", "replace", 1f);
        assertTrue((Integer) ack.fields().get("changed") > 0);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) service.getWeights("arm_l", new int[] {0}).get("vertices")).get(0);
        assertTrue(((List<?>) row.get("joints")).contains("arm_l"), "the part's own joint gained weight: " + row);
    }

    @Test
    void clearUnweightsAndInfoReports() {
        service.autoWeights(null, null, null, null, true);
        Map<String, Object> info = service.info();
        assertEquals(vertexCount("arm_l") + vertexCount("arm_r"), info.get("weighted"));
        service.clear("arm_l");
        assertEquals(0, weighted("arm_l"));
        assertEquals(vertexCount("arm_r"), weighted("arm_r"));
    }

    @Test
    void unknownNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.setWeights("arm_l",
                List.of(new SkinEditingService.WeightEntry(0, List.of("nope"), new float[] {1f}))));
        assertThrows(IllegalArgumentException.class, () -> service.getWeights("leg", null));
        assertThrows(IllegalArgumentException.class, () -> service.autoWeights(null, List.of("arm_l"), null, null, false));
        assertThrows(IllegalArgumentException.class, () -> service.mirror("w", 1, null, null));
        assertFalse(history.canUndo(), "a refused call records nothing");
    }

    @Test
    void toolsRouteThroughTheSkinUndoDomainAndGuide() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        McpToolRegistry registry = new McpToolRegistry();
        new SkinToolDefinitions(service, mapper).registerAll(registry);
        new MetaToolDefinitions(null, null, null, null, service, null, null, mapper, McpGuide.builtin())
                .registerAll(registry);

        registry.get("skin_auto_weights").handler().call(mapper.readTree("{\"rigid\": true}"));
        registry.get("skin_set_weights").handler().call(mapper.readTree(
                "{\"part\":\"arm_l\",\"entries\":[{\"vertex\":0,\"joints\":[\"bone_r\"],\"weights\":[1]}]}"));
        Map<?, ?> row = (Map<?, ?>) ((List<?>) service.getWeights("arm_l", new int[] {0}).get("vertices")).get(0);
        assertEquals(List.of("bone_r"), row.get("joints"));

        assertEquals(true, registry.get("undo").handler().call(mapper.readTree("{\"domain\":\"skin\"}")));
        row = (Map<?, ?>) ((List<?>) service.getWeights("arm_l", new int[] {0}).get("vertices")).get(0);
        assertEquals(List.of("bone_l"), row.get("joints"), "undo restored the auto weights");

        String guide = (String) registry.get("describe_api").handler().call(mapper.readTree("{\"topic\":\"skinning\"}"));
        assertTrue(guide.contains("skin_auto_weights"));
    }

    @Test
    void heatMapWeightsCountUnweightedVerticesForTheirPart() {
        service.setWeights("arm_l", List.of(new SkinEditingService.WeightEntry(0, List.of("bone_l", "arm_l"),
                new float[] {0.25f, 0.75f})));
        float[] own = authoring.jointWeights(id("arm_l"), id("arm_l"));
        assertEquals(0.75f, own[0], 1e-4f);
        assertEquals(1f, own[1], 1e-6f, "an unweighted vertex follows its part");
        float[] bone = authoring.jointWeights(id("arm_l"), "b-l");
        assertEquals(0.25f, bone[0], 1e-4f);
        assertEquals(0f, bone[1], 1e-6f);
        assertEquals(0f, authoring.jointWeights(id("arm_r"), "b-l")[0], 1e-6f, "no skin: only the part's joint");
    }

    // ---- helpers ----

    private void assertEveryRow(String part, String joint) {
        Map<String, Object> got = service.getWeights(part, null);
        List<?> rows = (List<?>) got.get("vertices");
        assertEquals(vertexCount(part), rows.size(), part + " fully weighted");
        for (Object r : rows) {
            Map<?, ?> row = (Map<?, ?>) r;
            assertEquals(List.of(joint), row.get("joints"), part + " vertex " + row.get("v"));
        }
    }

    private int weighted(String part) {
        return (Integer) service.getWeights(part, null).get("weighted");
    }

    private int vertexCount(String part) {
        return parts.getPartGeometry(id(part)).vertices().length / 3;
    }

    private String id(String name) {
        return parts.getPartByName(name).orElseThrow().id();
    }

    private static float centerX(float[] v) {
        return center(v, 0);
    }

    private static float centerY(float[] v) {
        return center(v, 1);
    }

    private static float center(float[] v, int axis) {
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int i = axis; i < v.length; i += 3) {
            min = Math.min(min, v[i]);
            max = Math.max(max, v[i]);
        }
        return (min + max) * 0.5f;
    }
}
