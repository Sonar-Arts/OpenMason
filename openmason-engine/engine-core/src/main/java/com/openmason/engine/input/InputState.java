package com.openmason.engine.input;

import java.util.Arrays;

import org.joml.Vector2f;

/**
 * Raw input state a host's collaborators share (#96, from Stonebreak's {@code MouseInputState}):
 * held keys and mouse buttons, "pressed this frame" button edges consumed by screens, the cursor in
 * framebuffer pixels and the pending scroll. Pure state, no routing decisions. Fed by the window's
 * event chain or by synthetic events in tests; single-threaded (the window's event thread).
 *
 * <p>Frame contract: {@link #beginFrame()} before polling events expires the previous frame's
 * press edges; {@link #clearAll()} on a state transition forgets everything held, so a click a menu
 * consumed does not leak into the next state as a held button.
 */
public final class InputState {

    private final boolean[] keyDown = new boolean[InputCodes.KEY_LAST + 1];
    private final boolean[] buttonDown = new boolean[InputCodes.MOUSE_BUTTON_LAST + 1];
    private final boolean[] pressedThisFrame = new boolean[InputCodes.MOUSE_BUTTON_LAST + 1];
    // Which buttons saw a press, so beginFrame() only clears what it must.
    private final boolean[] wasPressed = new boolean[InputCodes.MOUSE_BUTTON_LAST + 1];

    private float cursorX;
    private float cursorY;
    private final Vector2f cachedPosition = new Vector2f();

    private double scrollY;

    /** Records a key event ({@link InputCodes#PRESS}/{@link InputCodes#RELEASE}; repeats keep it held). */
    public void onKey(int key, int action) {
        if (!InputCodes.isKey(key)) {
            return;
        }
        if (action == InputCodes.PRESS) {
            keyDown[key] = true;
        } else if (action == InputCodes.RELEASE) {
            keyDown[key] = false;
        }
    }

    /** Records a mouse button event. Must run before routing, so screens polling this frame see it. */
    public void onButton(int button, int action) {
        if (!InputCodes.isMouseButton(button)) {
            return;
        }
        if (action == InputCodes.PRESS) {
            buttonDown[button] = true;
            pressedThisFrame[button] = true;
            wasPressed[button] = true;
        } else if (action == InputCodes.RELEASE) {
            buttonDown[button] = false;
        }
    }

    /** Expires the previous frame's "pressed this frame" edges; call before polling events. */
    public void beginFrame() {
        for (int i = 0; i < wasPressed.length; i++) {
            if (wasPressed[i]) {
                pressedThisFrame[i] = false;
                wasPressed[i] = false;
            }
        }
    }

    public boolean isKeyDown(int key) {
        return InputCodes.isKey(key) && keyDown[key];
    }

    public boolean isDown(int button) {
        return InputCodes.isMouseButton(button) && buttonDown[button];
    }

    public boolean isPressed(int button) {
        return InputCodes.isMouseButton(button) && pressedThisFrame[button];
    }

    /** Marks this frame's press of {@code button} as handled, so no later reader sees it. */
    public void consumePress(int button) {
        if (InputCodes.isMouseButton(button)) {
            pressedThisFrame[button] = false;
        }
    }

    /**
     * Forgets every held key and button and every pending edge: for state transitions, and for
     * releases the host never saw (focus loss, events routed elsewhere).
     */
    public void clearAll() {
        Arrays.fill(keyDown, false);
        Arrays.fill(buttonDown, false);
        Arrays.fill(pressedThisFrame, false);
        Arrays.fill(wasPressed, false);
    }

    /** The cursor in framebuffer pixels. */
    public void setCursor(float x, float y) {
        this.cursorX = x;
        this.cursorY = y;
    }

    public float x() {
        return cursorX;
    }

    public float y() {
        return cursorY;
    }

    /** The cursor in a reused vector (no per-call allocation). */
    public Vector2f position() {
        return cachedPosition.set(cursorX, cursorY);
    }

    /** Replaces the pending vertical scroll with this event's offset. */
    public void setScroll(double yOffset) {
        this.scrollY = yOffset;
    }

    /** Adds this event's offset to the pending vertical scroll. */
    public void addScroll(double yOffset) {
        this.scrollY += yOffset;
    }

    public double scroll() {
        return scrollY;
    }

    public void resetScroll() {
        this.scrollY = 0.0;
    }

    public double getAndResetScroll() {
        double offset = scrollY;
        scrollY = 0.0;
        return offset;
    }
}
