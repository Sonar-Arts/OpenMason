package com.openmason.engine.input;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Jump-free mouse-look deltas (#96, from Stonebreak's MouseCaptureManager). */
class PointerLookTest {

    private final List<float[]> deltas = new ArrayList<>();
    private final PointerLook.Sink sink = (dx, dy) -> deltas.add(new float[] {dx, dy});

    @Test
    void firstMoveAfterReanchorOnlyAnchors() {
        PointerLook look = new PointerLook(0.1f);
        look.reanchor(100, 100);
        assertFalse(look.move(500, 500, true, sink), "the first move never produces a jump");
        assertTrue(look.move(510, 480, true, sink));
        assertEquals(1, deltas.size());
        assertArrayEquals(new float[] {1f, 2f}, deltas.getFirst(), 1e-5f, "x scaled, y flipped and scaled");
    }

    @Test
    void inactiveMovesKeepTheAnchorCurrent() {
        PointerLook look = new PointerLook(1f);
        look.reanchor(0, 0);
        look.move(0, 0, true, sink); // spend the first move
        look.move(300, 300, false, sink); // e.g. chat open
        look.move(301, 300, true, sink);
        assertArrayEquals(new float[] {1f, 0f}, deltas.getFirst(), 1e-5f);
    }

    @Test
    void skipNextMoveSwallowsOneMove() {
        PointerLook look = new PointerLook(1f);
        look.reanchor(0, 0);
        look.move(0, 0, true, sink);
        look.skipNextMove();
        assertFalse(look.move(50, 50, true, sink));
        assertTrue(look.move(51, 50, true, sink));
        assertEquals(1, deltas.size());
    }
}
