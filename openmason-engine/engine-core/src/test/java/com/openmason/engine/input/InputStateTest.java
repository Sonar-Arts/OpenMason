package com.openmason.engine.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared raw input state's frame contract (#96, from Stonebreak's MouseInputState). */
class InputStateTest {

    private final InputState s = new InputState();

    @Test
    void pressEdgesLastUntilTheNextFrame() {
        s.onButton(InputCodes.MOUSE_BUTTON_LEFT, InputCodes.PRESS);
        assertTrue(s.isPressed(InputCodes.MOUSE_BUTTON_LEFT));
        assertTrue(s.isDown(InputCodes.MOUSE_BUTTON_LEFT));
        s.onButton(InputCodes.MOUSE_BUTTON_LEFT, InputCodes.RELEASE);
        assertTrue(s.isPressed(InputCodes.MOUSE_BUTTON_LEFT), "a click within one frame is still seen");
        assertFalse(s.isDown(InputCodes.MOUSE_BUTTON_LEFT));
        s.beginFrame();
        assertFalse(s.isPressed(InputCodes.MOUSE_BUTTON_LEFT));
    }

    @Test
    void consumedPressesAreGoneForLaterReaders() {
        s.onButton(InputCodes.MOUSE_BUTTON_RIGHT, InputCodes.PRESS);
        s.consumePress(InputCodes.MOUSE_BUTTON_RIGHT);
        assertFalse(s.isPressed(InputCodes.MOUSE_BUTTON_RIGHT));
        assertTrue(s.isDown(InputCodes.MOUSE_BUTTON_RIGHT));
    }

    @Test
    void clearAllForgetsHeldButtonsKeysAndEdges() {
        s.onButton(InputCodes.MOUSE_BUTTON_LEFT, InputCodes.PRESS);
        s.onKey(87, InputCodes.PRESS);
        s.clearAll();
        assertFalse(s.isDown(InputCodes.MOUSE_BUTTON_LEFT));
        assertFalse(s.isPressed(InputCodes.MOUSE_BUTTON_LEFT));
        assertFalse(s.isKeyDown(87));
    }

    @Test
    void keysStayHeldThroughRepeats() {
        s.onKey(87, InputCodes.PRESS);
        s.onKey(87, InputCodes.REPEAT);
        assertTrue(s.isKeyDown(87));
        s.onKey(87, InputCodes.RELEASE);
        assertFalse(s.isKeyDown(87));
        s.onKey(-1, InputCodes.PRESS); // GLFW_KEY_UNKNOWN
        s.onButton(99, InputCodes.PRESS);
        assertFalse(s.isDown(99));
    }

    @Test
    void cursorAndScroll() {
        s.setCursor(12.5f, 7f);
        assertEquals(12.5f, s.position().x);
        assertEquals(7f, s.y());
        s.setScroll(1.0);
        s.setScroll(-2.0);
        assertEquals(-2.0, s.scroll(), "setScroll replaces");
        s.addScroll(0.5);
        assertEquals(-1.5, s.getAndResetScroll());
        assertEquals(0.0, s.scroll());
    }
}
