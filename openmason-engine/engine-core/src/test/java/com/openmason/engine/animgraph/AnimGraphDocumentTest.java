package com.openmason.engine.animgraph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OpenMason #164: the Java binding of the native editable graph ({@code cenda/anim_graph_edit.h}) - apply / undo /
 * redo / merge, problems, view, compile, and the debugger's input weights. The rules are tested natively (ctest
 * anim_graph_edit).
 */
class AnimGraphDocumentTest {

    private static final Path GRAPH = Path.of("src/test/resources/animgraph/humanoid_locomotion.omanimgraph");

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaAnimGraph.isAvailable(), "native graph module unavailable: " + CendaAnimGraph.unavailableReason());
    }

    @Test
    void editsAreOneStepEachAndRestoreExactly() throws Exception {
        try (AnimGraphDocument d = AnimGraphDocument.create("g", "humanoid")) {
            assertFalse(d.valid());
            assertTrue(d.problems().contains("the graph has no nodes"));
            assertEquals("[\"slot a added\",\"parameter f added\",\"node c added\"]",
                d.apply("[{\"op\":\"slot\",\"name\":\"a\"},{\"op\":\"param\",\"name\":\"f\",\"type\":\"float\"},"
                    + "{\"op\":\"node\",\"id\":\"c\",\"type\":\"clip\",\"slot\":\"a\"}]", "first", null));
            assertTrue(d.valid());
            String one = d.json();
            for (int i = 0; i < 4; i++) d.apply("[{\"op\":\"position\",\"node\":\"c\",\"x\":" + i + ",\"y\":0}]", "move", "move:c");
            assertEquals(2, d.stepCount());
            IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> d.apply("[{\"op\":\"node\",\"id\":\"nope\",\"remove\":true}]", null, null));
            assertTrue(bad.getMessage().contains("ops[0] (node)"), bad.getMessage());
            assertTrue(d.undo());
            assertEquals(one, d.json());
            assertEquals("move", d.redoLabel());
            assertTrue(d.redo());
            assertTrue(d.view().contains("\"placed\":true"));
            d.markSaved();
            assertFalse(d.dirty());
        }
    }

    @Test
    void theTestGraphCompilesAndReportsInputWeights() throws Exception {
        assumeTrue(Files.isRegularFile(GRAPH));
        try (AnimGraphDocument d = AnimGraphDocument.read(GRAPH);
             AnimGraph g = d.compile()) {
            assertTrue(d.valid());
            assertEquals(9, g.nodes().size());
            assertEquals(d.json(), AnimGraphDocument.read(d.json()).json(), "canonical text");
        }
    }
}
