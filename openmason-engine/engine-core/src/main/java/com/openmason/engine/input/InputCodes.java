package com.openmason.engine.input;

/**
 * The engine's input code space (#96): GLFW's numbering for keys, mouse buttons, actions and
 * modifier bits. The engine keeps that numbering everywhere (UI routers, bindings, edge tracking)
 * instead of inventing a second one, so a GLFW host passes its events through unchanged while this
 * package never links GLFW.
 */
public final class InputCodes {

    /** Key or button released ({@code GLFW_RELEASE}). */
    public static final int RELEASE = 0;
    /** Key or button pressed ({@code GLFW_PRESS}). */
    public static final int PRESS = 1;
    /** Key held long enough to repeat ({@code GLFW_REPEAT}). */
    public static final int REPEAT = 2;

    /** Highest key token ({@code GLFW_KEY_LAST}); arrays indexed by key are this + 1 long. */
    public static final int KEY_LAST = 348;
    /** Highest mouse button ({@code GLFW_MOUSE_BUTTON_LAST}). */
    public static final int MOUSE_BUTTON_LAST = 7;
    public static final int MOUSE_BUTTON_LEFT = 0;
    public static final int MOUSE_BUTTON_RIGHT = 1;
    public static final int MOUSE_BUTTON_MIDDLE = 2;

    public static final int MOD_SHIFT = 0x0001;
    public static final int MOD_CONTROL = 0x0002;
    public static final int MOD_ALT = 0x0004;
    public static final int MOD_SUPER = 0x0008;
    public static final int MOD_CAPS_LOCK = 0x0010;
    public static final int MOD_NUM_LOCK = 0x0020;

    /** Shift, Control, Alt, Super; Caps/Num Lock are not modifiers for bindings. */
    public static final int MODIFIER_MASK = MOD_SHIFT | MOD_CONTROL | MOD_ALT | MOD_SUPER;

    private InputCodes() {
    }

    /** True for a valid key token (0..{@link #KEY_LAST}); GLFW reports unknown keys as -1. */
    public static boolean isKey(int key) {
        return key >= 0 && key <= KEY_LAST;
    }

    /** True for a valid mouse button (0..{@link #MOUSE_BUTTON_LAST}). */
    public static boolean isMouseButton(int button) {
        return button >= 0 && button <= MOUSE_BUTTON_LAST;
    }
}
