package com.openmason.main.systems.particles.fx;

import com.openmason.engine.rendering.particles.graph.FxModule;
import com.openmason.engine.rendering.particles.graph.FxModules;
import com.openmason.engine.rendering.particles.graph.FxNode;
import com.openmason.engine.rendering.particles.graph.FxOp;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The graph editor's draft of a custom module (OM #104): edits, problems per node, lowering. */
@Tag("regression")
class FxGraphDraftTest {

    private static final FxModule CURL = FxModules.curlNoiseVelocity(2f, 0.5f, 1f);

    @Test
    void aModuleRoundTripsThroughItsDraft() {
        FxGraphDraft d = FxGraphDraft.of(CURL, Map.of());
        FxGraphDraft.Lowered l = d.lower();
        assertTrue(l.ok(), l.problems().toString());
        assertEquals(CURL, l.module());
    }

    @Test
    void autoLayoutPutsReadersRightOfTheirInputsAndKeepsSavedPositions() {
        FxGraphDraft d = FxGraphDraft.of(CURL, Map.of("p", new float[]{-50f, 7f}));
        assertEquals(-50f, d.node("p").x());
        assertEquals(7f, d.node("p").y());
        assertTrue(d.node("out").x() > d.node("v2").x(), "a node sits right of what it reads");
        assertTrue(d.node("v2").x() > d.node("v").x());
    }

    @Test
    void unconnectedInputsAndMissingAttributesAreNamedPerNode() {
        FxGraphDraft d = FxGraphDraft.of(CURL, Map.of()).disconnect("out", 0).addNode(FxOp.ADD, 0, 0);
        FxGraphDraft.Lowered l = d.lower();
        assertFalse(l.ok());
        assertTrue(l.problems().get("out").contains("not connected"), l.problems().toString());
        assertTrue(l.problems().get("add").contains("not connected"));
        FxGraphDraft noAttr = FxGraphDraft.of(CURL, Map.of()).setAttribute("p", "  ");
        assertTrue(noAttr.lower().problems().get("p").contains("attribute"));
    }

    @Test
    void wiresMayRunBackwardsInTheListAndLoweringSortsThem() {
        // A node made late feeding a node made early: lowering reorders them.
        FxGraphDraft d = FxGraphDraft.of(FxModule.custom("m",
                        FxNode.read("v", "velocity"),
                        FxNode.write("out", "velocity", "v")), Map.of())
                .addNode(FxOp.NEG, 0, 0)
                .connect("v", "neg", 0)
                .connect("neg", "out", 0);
        FxGraphDraft.Lowered l = d.lower();
        assertTrue(l.ok(), l.problems().toString());
        List<String> order = l.module().nodes().stream().map(FxNode::id).toList();
        assertEquals(List.of("v", "neg", "out"), order);
    }

    @Test
    void cyclesAreReportedOnEveryNodeInThem() {
        FxGraphDraft d = new FxGraphDraft("m", List.of())
                .addNode(FxOp.NEG, 0, 0).addNode(FxOp.ABS, 0, 0)
                .connect("neg", "abs", 0).connect("abs", "neg", 0);
        FxGraphDraft.Lowered l = d.lower();
        assertFalse(l.ok());
        assertEquals(Map.of("neg", "is part of a cycle", "abs", "is part of a cycle"), l.problems());
    }

    @Test
    void removingANodeUnwiresItsReaders() {
        FxGraphDraft d = FxGraphDraft.of(CURL, Map.of()).removeNode("v2");
        assertNull(d.node("v2"));
        assertEquals("", d.node("out").inputs().get(0));
    }

    @Test
    void renamingANodeKeepsItsWires() {
        FxGraphDraft d = FxGraphDraft.of(CURL, Map.of()).rename("v2", "newVelocity");
        assertEquals("newVelocity", d.node("out").inputs().get(0));
        assertTrue(d.lower().ok());
        assertThrows(IllegalArgumentException.class, () -> d.rename("p", "age"), "taken");
    }

    @Test
    void newNodesGetFreeIdsAndDefaultsAndIntrinsicsAreRefused() {
        FxGraphDraft d = new FxGraphDraft("m", List.of()).addNode(FxOp.CONST, 0, 0).addNode(FxOp.CONST, 0, 0)
                .addNode(FxOp.CURVE, 0, 0).addNode(FxOp.READ, 0, 0);
        assertNotNull(d.node("const"));
        assertNotNull(d.node("const-2"));
        assertEquals(List.of(0f), d.node("const").values());
        assertEquals(4, d.node("curve").values().size());
        assertEquals("position", d.node("read").attribute());
        assertThrows(IllegalArgumentException.class, () -> d.addNode(FxOp.COLLIDE, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> d.connect("const", "const", 0));
    }

    @Test
    void changingTheOperatorResizesInputs() {
        FxGraphDraft d = new FxGraphDraft("m", List.of()).addNode(FxOp.CONST, 0, 0).addNode(FxOp.ADD, 0, 0)
                .connect("const", "add", 0).connect("const", "add", 1).setOp("add", FxOp.LERP);
        assertEquals(List.of("const", "const", ""), d.node("add").inputs());
        assertEquals(List.of("const"), d.setOp("add", FxOp.NEG).node("add").inputs());
    }

    @Test
    void anEmptyDraftIsNotAModule() {
        assertFalse(new FxGraphDraft("m", List.of()).lower().ok());
        assertTrue(FxGraphDraft.empty("m").lower().ok());
    }
}
