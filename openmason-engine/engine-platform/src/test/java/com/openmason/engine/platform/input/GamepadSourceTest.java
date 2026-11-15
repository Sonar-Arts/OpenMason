package com.openmason.engine.platform.input;

import com.openmason.engine.ui.runtime.input.GamepadButtons;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gamepad edges and the left stick's D-pad hysteresis (#96, from Stonebreak's GamepadUiSource), display-free. */
class GamepadSourceTest {

    private final GamepadSource pad = new GamepadSource();
    private final List<String> edges = new ArrayList<>();

    private void step(float x, float y, int... down) {
        boolean[] b = new boolean[GamepadButtons.COUNT];
        for (int d : down) {
            b[d] = true;
        }
        pad.step(b, x, y, (button, isDown) -> edges.add(button + (isDown ? "+" : "-")));
    }

    @Test
    void onlyEdgesAreReported() {
        step(0, 0, GamepadButtons.A);
        step(0, 0, GamepadButtons.A);
        step(0, 0);
        assertEquals(List.of(GamepadButtons.A + "+", GamepadButtons.A + "-"), edges);
    }

    @Test
    void stickEngagesPastOnAndReleasesUnderOff() {
        step(0.5f, 0); // below 0.6: nothing
        assertTrue(edges.isEmpty());
        step(0.65f, 0);
        assertEquals(List.of(GamepadButtons.DPAD_RIGHT + "+"), edges);
        step(0.4f, 0); // between off and on: stays engaged
        assertEquals(1, edges.size());
        step(0.3f, 0);
        assertEquals(GamepadButtons.DPAD_RIGHT + "-", edges.getLast());
    }

    @Test
    void stickDirectionsMapToTheDpad() {
        step(0, -0.9f);
        step(0, 0.9f);
        step(-0.9f, 0);
        assertEquals(List.of(GamepadButtons.DPAD_UP + "+",
                GamepadButtons.DPAD_UP + "-", GamepadButtons.DPAD_DOWN + "+",
                GamepadButtons.DPAD_DOWN + "-", GamepadButtons.DPAD_LEFT + "+"), edges);
    }

    @Test
    void aHeldDpadButtonAndTheStickDoNotDoubleReport() {
        step(0, -0.9f, GamepadButtons.DPAD_UP);
        step(0, 0, GamepadButtons.DPAD_UP);
        assertEquals(List.of(GamepadButtons.DPAD_UP + "+"), edges);
    }

    @Test
    void hysteresisThresholds() {
        assertFalse(GamepadSource.hysteresis(false, 0.6f));
        assertTrue(GamepadSource.hysteresis(false, 0.61f));
        assertTrue(GamepadSource.hysteresis(true, 0.36f));
        assertFalse(GamepadSource.hysteresis(true, 0.35f));
    }
}
