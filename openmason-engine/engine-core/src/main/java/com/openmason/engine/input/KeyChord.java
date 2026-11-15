package com.openmason.engine.input;

/**
 * A key with the modifiers it needs (#288, generalized for any action set in #96). A chord with
 * explicit modifiers matches only exactly those (Shift+Tab is not Tab); a chord with
 * {@link #ANY_MODIFIERS} matches whatever is held. Lock-key bits never count.
 */
public record KeyChord(int key, int modifiers) {

    /** Matches with any modifiers held. */
    public static final int ANY_MODIFIERS = -1;

    public static KeyChord any(int key) {
        return new KeyChord(key, ANY_MODIFIERS);
    }

    public static KeyChord exact(int key, int modifiers) {
        return new KeyChord(key, modifiers & InputCodes.MODIFIER_MASK);
    }

    /** True when this chord ignores the held modifiers. */
    public boolean anyModifiers() {
        return modifiers == ANY_MODIFIERS;
    }

    /** True when {@code key} with {@code mods} held triggers this chord. */
    public boolean matches(int key, int mods) {
        return this.key == key && (modifiers == ANY_MODIFIERS || modifiers == (mods & InputCodes.MODIFIER_MASK));
    }
}
