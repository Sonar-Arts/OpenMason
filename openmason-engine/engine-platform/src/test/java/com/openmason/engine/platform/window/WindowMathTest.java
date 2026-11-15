package com.openmason.engine.platform.window;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Display-free window math (#96): cursor scaling, monitor overlap, spec defaults. */
class WindowMathTest {

    @Test
    void cursorScaleIsFramebufferPixelsPerWindowCoordinate() {
        assertEquals(1.0, Window.cursorScale(1280, 1280));
        assertEquals(2.0, Window.cursorScale(2560, 1280), "HiDPI / Wayland scale 2");
        assertEquals(1.25, Window.cursorScale(1600, 1280));
        assertEquals(1.0, Window.cursorScale(1600, 0), "unknown window size reads as 1");
    }

    @Test
    void overlapPicksTheMonitorTheWindowMostlySitsOn() {
        // window 1000x800 at (1500, 100); monitors 1920x1080 at (0,0) and (1920,0)
        long left = Window.overlapArea(1500, 100, 1000, 800, 0, 0, 1920, 1080);
        long right = Window.overlapArea(1500, 100, 1000, 800, 1920, 0, 1920, 1080);
        assertEquals(420L * 800, left);
        assertEquals(580L * 800, right);
        assertTrue(right > left);
        assertEquals(0L, Window.overlapArea(0, 0, 10, 10, 50, 50, 10, 10), "disjoint");
    }

    @Test
    void specDefaultsAndValidation() {
        WindowSpec s = WindowSpec.builder("t", 800, 600).build();
        assertEquals(3, s.glMajor());
        assertEquals(3, s.glMinor());
        assertEquals(WindowSpec.GlProfile.CORE, s.profile());
        assertTrue(s.resizable());
        assertTrue(s.center());
        assertEquals(0, s.swapInterval());
        assertThrows(IllegalArgumentException.class, () -> WindowSpec.builder("t", 0, 600).build());
        assertThrows(NullPointerException.class, () -> WindowSpec.builder(null, 800, 600).build());
    }
}
