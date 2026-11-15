package com.openmason.engine.input;

/**
 * Edge detection for polled keys (#96, from Stonebreak's {@code KeyEdgeTracker}).
 * {@link #pressedOnce(int)} fires only on the poll where a key goes from released to held, so a
 * host needs no per-key "was pressed" fields. Callers must poll the same key every frame for the
 * edge to track.
 *
 * <p>A key the {@link KeyMask} claims (an open UI document's text field, #288) reads as up, and its
 * edge is spent while masked: a key held through a text field does not fire as a fresh press once
 * the field lets go of it.
 */
public final class KeyEdges {

    /** The physical held state of a key ({@code glfwGetKey(window, key) == GLFW_PRESS}). */
    @FunctionalInterface
    public interface KeySource {
        boolean isDown(int key);
    }

    /** Keys another layer owns right now (the UI input layer, #288). */
    @FunctionalInterface
    public interface KeyMask {
        /** Nothing masked. */
        KeyMask NONE = key -> false;

        boolean masks(int key);
    }

    private final KeySource source;
    private volatile KeyMask mask;
    private final boolean[] previouslyDown = new boolean[InputCodes.KEY_LAST + 1];

    public KeyEdges(KeySource source, KeyMask mask) {
        this.source = source;
        this.mask = mask != null ? mask : KeyMask.NONE;
    }

    public void setMask(KeyMask mask) {
        this.mask = mask != null ? mask : KeyMask.NONE;
    }

    /** True only on the poll where the key goes down; false while held, released or masked. */
    public boolean pressedOnce(int key) {
        if (!InputCodes.isKey(key)) {
            return false;
        }
        boolean physical = source.isDown(key);
        if (mask.masks(key)) {
            previouslyDown[key] = physical;
            return false;
        }
        boolean fired = physical && !previouslyDown[key];
        previouslyDown[key] = physical;
        return fired;
    }

    /**
     * Forgets the held state so the next poll fires even if the key never released in between: for
     * hosts that suppress a key in some states but want a held key to act on re-entry.
     */
    public void reset(int key) {
        if (InputCodes.isKey(key)) {
            previouslyDown[key] = false;
        }
    }

    /** True while the key is held and not masked. */
    public boolean isDown(int key) {
        return InputCodes.isKey(key) && source.isDown(key) && !mask.masks(key);
    }

    /** The raw held state, ignoring the mask. */
    public boolean physicallyDown(int key) {
        return InputCodes.isKey(key) && source.isDown(key);
    }
}
