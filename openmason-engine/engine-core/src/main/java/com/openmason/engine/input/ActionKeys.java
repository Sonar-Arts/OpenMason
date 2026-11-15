package com.openmason.engine.input;

import java.util.List;

/**
 * Polls a host's {@link ActionMap} through {@link KeyEdges} (#96): "is the move-forward action held",
 * "did the inventory action fire this frame". An action fires when any of its keys does; every bound
 * key is polled each call (no short-circuit), so the edges of all of them stay in sync. Modifiers
 * are not checked here: polled gameplay actions bind keys with any modifiers. Controller buttons
 * are the host's {@link ActionMap#actionForButton} lookup.
 *
 * <p>The map can be swapped at runtime ({@link #setBindings}), e.g. after a rebinding screen.
 */
public final class ActionKeys<A> {

    private final KeyEdges edges;
    private volatile ActionMap<A> bindings;

    public ActionKeys(ActionMap<A> bindings, KeyEdges edges) {
        this.bindings = bindings;
        this.edges = edges;
    }

    public ActionMap<A> bindings() {
        return bindings;
    }

    public void setBindings(ActionMap<A> bindings) {
        this.bindings = bindings;
    }

    public KeyEdges edges() {
        return edges;
    }

    /** True while any key bound to {@code action} is held (and not masked). */
    public boolean held(A action) {
        for (KeyChord c : bindings.keys(action)) {
            if (edges.isDown(c.key())) {
                return true;
            }
        }
        return false;
    }

    /** True on the poll where any key bound to {@code action} goes down. */
    public boolean pressedOnce(A action) {
        boolean fired = false;
        List<KeyChord> chords = bindings.keys(action);
        for (int i = 0; i < chords.size(); i++) {
            fired |= edges.pressedOnce(chords.get(i).key());
        }
        return fired;
    }

    /** {@link KeyEdges#reset} for every key bound to {@code action}. */
    public void reset(A action) {
        for (KeyChord c : bindings.keys(action)) {
            edges.reset(c.key());
        }
    }
}
