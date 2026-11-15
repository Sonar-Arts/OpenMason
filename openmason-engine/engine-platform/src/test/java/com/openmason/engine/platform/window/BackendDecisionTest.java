package com.openmason.engine.platform.window;

import com.openmason.engine.platform.window.BackendDecision.Pin;
import org.junit.jupiter.api.Test;

import static com.openmason.engine.platform.window.BackendPolicy.AUTO_WAYLAND;
import static com.openmason.engine.platform.window.BackendPolicy.PREFER_X11;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The display-backend decision table (#96): the game's and the tool's policies plus the overrides. */
class BackendDecisionTest {

    private static final String BLOCKED = "595.71.05";
    private static final String FINE = "610.43.02";

    private static Pin pin(BackendPolicy policy, String pref, String driver, boolean x11, boolean wayland) {
        return BackendDecision.decide(policy, true, pref, driver, x11, wayland).pin();
    }

    @Test
    void nothingToChooseOffLinux() {
        assertEquals(Pin.DEFAULT, BackendDecision.decide(PREFER_X11, false, "x11", BLOCKED, true, true).pin());
        assertEquals(Pin.DEFAULT, BackendDecision.decide(AUTO_WAYLAND, false, "auto", BLOCKED, true, true).pin());
    }

    @Test
    void gamePolicyStaysOnWaylandUnlessTheDriverIsBlocklisted() {
        assertEquals(Pin.DEFAULT, pin(AUTO_WAYLAND, "auto", null, true, true));
        assertEquals(Pin.DEFAULT, pin(AUTO_WAYLAND, "auto", FINE, true, true));
        assertEquals(Pin.X11, pin(AUTO_WAYLAND, "auto", BLOCKED, true, true));
        assertEquals(Pin.X11, pin(AUTO_WAYLAND, null, BLOCKED, true, true), "null preference = auto");
    }

    @Test
    void toolPolicyPrefersX11ForViewportPopOuts() {
        assertEquals(Pin.X11, pin(PREFER_X11, "auto", null, true, true));
        assertEquals(Pin.X11, pin(PREFER_X11, " AUTO ", FINE, true, true));
    }

    @Test
    void anUnavailableX11FallsBackWithAWarning() {
        BackendDecision d = BackendDecision.decide(AUTO_WAYLAND, true, "auto", BLOCKED, false, true);
        assertEquals(Pin.DEFAULT, d.pin());
        assertTrue(d.warning());
        assertEquals(Pin.DEFAULT, pin(PREFER_X11, "auto", null, false, true));
        assertEquals(Pin.DEFAULT, pin(PREFER_X11, "x11", null, false, true));
    }

    @Test
    void explicitPreferencesOverrideEitherPolicy() {
        for (BackendPolicy p : BackendPolicy.values()) {
            assertEquals(Pin.X11, pin(p, "x11", null, true, true), p.name());
            assertEquals(Pin.WAYLAND, pin(p, "wayland", BLOCKED, true, true), p.name());
            assertEquals(Pin.DEFAULT, pin(p, "any", BLOCKED, true, true), p.name());
            assertEquals(Pin.DEFAULT, pin(p, "wayland", null, true, false), p.name() + ": no Wayland backend");
        }
        assertFalse(BackendDecision.decide(PREFER_X11, true, "any", null, true, true).warning());
    }

    @Test
    void unknownPreferencesFollowThePolicy() {
        assertEquals(Pin.X11, pin(PREFER_X11, "sdl", null, true, true));
        assertEquals(Pin.DEFAULT, pin(AUTO_WAYLAND, "sdl", FINE, true, true));
        assertEquals(Pin.X11, pin(AUTO_WAYLAND, "sdl", BLOCKED, true, true));
    }
}
