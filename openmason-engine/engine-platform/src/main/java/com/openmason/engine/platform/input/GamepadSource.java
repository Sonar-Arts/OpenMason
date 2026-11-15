package com.openmason.engine.platform.input;

import com.openmason.engine.ui.runtime.input.GamepadButtons;
import org.lwjgl.glfw.GLFWGamepadState;

import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_AXIS_LEFT_X;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y;
import static org.lwjgl.glfw.GLFW.GLFW_JOYSTICK_1;
import static org.lwjgl.glfw.GLFW.GLFW_JOYSTICK_LAST;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.glfwGetGamepadState;
import static org.lwjgl.glfw.GLFW.glfwJoystickIsGamepad;

/**
 * Polls GLFW's standard-mapping gamepads (#96, from Stonebreak's {@code GamepadUiSource}, #288).
 * Every connected pad counts (their buttons are OR-ed, the strongest stick deflection wins), and the
 * left stick acts as a D-pad with hysteresis (on past {@value #STICK_ON}, off under
 * {@value #STICK_OFF}) so it neither chatters nor needs a second binding. Only button edges are
 * reported; holding and repeat are the consumer's job. Button numbering is {@link GamepadButtons}.
 */
public final class GamepadSource {

    /** Receives one button edge. */
    @FunctionalInterface
    public interface Sink {
        void button(int button, boolean down);
    }

    static final float STICK_ON = 0.6f;
    static final float STICK_OFF = 0.35f;

    private GLFWGamepadState state; // native memory, created on the first poll (tests never poll)
    private final boolean[] now = new boolean[GamepadButtons.COUNT];
    private final boolean[] previous = new boolean[GamepadButtons.COUNT];
    private final boolean[] stick = new boolean[4]; // up, right, down, left

    /** Polls every pad and reports the edges since the last poll. */
    public void poll(Sink sink) {
        if (state == null) {
            state = GLFWGamepadState.create();
        }
        java.util.Arrays.fill(now, false);
        float x = 0;
        float y = 0;
        for (int jid = GLFW_JOYSTICK_1; jid <= GLFW_JOYSTICK_LAST; jid++) {
            if (!glfwJoystickIsGamepad(jid) || !glfwGetGamepadState(jid, state)) {
                continue;
            }
            for (int b = 0; b < GamepadButtons.COUNT; b++) {
                now[b] |= state.buttons(b) == GLFW_PRESS;
            }
            float sx = state.axes(GLFW_GAMEPAD_AXIS_LEFT_X);
            float sy = state.axes(GLFW_GAMEPAD_AXIS_LEFT_Y);
            if (Math.abs(sx) > Math.abs(x)) {
                x = sx;
            }
            if (Math.abs(sy) > Math.abs(y)) {
                y = sy;
            }
        }
        step(now, x, y, sink);
    }

    /**
     * One poll's worth of logic, display-free: {@code buttons} is the OR of every pad's buttons (it is
     * modified: stick directions are OR-ed into the D-pad), {@code x}/{@code y} the left stick.
     */
    void step(boolean[] buttons, float x, float y, Sink sink) {
        stick[0] = hysteresis(stick[0], -y);
        stick[1] = hysteresis(stick[1], x);
        stick[2] = hysteresis(stick[2], y);
        stick[3] = hysteresis(stick[3], -x);
        buttons[GamepadButtons.DPAD_UP] |= stick[0];
        buttons[GamepadButtons.DPAD_RIGHT] |= stick[1];
        buttons[GamepadButtons.DPAD_DOWN] |= stick[2];
        buttons[GamepadButtons.DPAD_LEFT] |= stick[3];
        for (int b = 0; b < GamepadButtons.COUNT; b++) {
            if (buttons[b] != previous[b]) {
                previous[b] = buttons[b];
                sink.button(b, buttons[b]);
            }
        }
    }

    static boolean hysteresis(boolean on, float value) {
        return on ? value > STICK_OFF : value > STICK_ON;
    }
}
