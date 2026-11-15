package com.openmason.engine.input;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Polled-key edges with a UI mask (#96, #288), and actions polled through them. */
class KeyEdgesTest {

    private final Set<Integer> held = new HashSet<>();
    private final Set<Integer> masked = new HashSet<>();
    private final KeyEdges edges = new KeyEdges(held::contains, masked::contains);

    @Test
    void firesOnlyOnThePollTheKeyGoesDown() {
        assertFalse(edges.pressedOnce(69));
        held.add(69);
        assertTrue(edges.pressedOnce(69));
        assertFalse(edges.pressedOnce(69), "held is not a new press");
        held.remove(69);
        assertFalse(edges.pressedOnce(69));
        held.add(69);
        assertTrue(edges.pressedOnce(69));
    }

    @Test
    void aMaskedKeyReadsUpAndItsEdgeIsSpent() {
        held.add(69);
        masked.add(69);
        assertFalse(edges.pressedOnce(69));
        assertFalse(edges.isDown(69));
        assertTrue(edges.physicallyDown(69));
        masked.clear(); // the text field lets go while the key is still held
        assertFalse(edges.pressedOnce(69), "a key held through the mask must not fire on release of it");
        assertTrue(edges.isDown(69));
    }

    @Test
    void resetMakesAHeldKeyFireAgain() {
        held.add(82);
        assertTrue(edges.pressedOnce(82));
        edges.reset(82);
        assertTrue(edges.pressedOnce(82));
    }

    @Test
    void outOfRangeKeysAreIgnored() {
        assertFalse(edges.pressedOnce(-1));
        assertFalse(edges.isDown(InputCodes.KEY_LAST + 1));
        edges.reset(-1);
    }

    @Test
    void actionsFireWhenAnyBoundKeyDoesAndKeepEveryEdgeInSync() {
        ActionMap<String> map = ActionMap.empty(ActionMap.ActionSet.of(List.of("shift"), s -> s))
                .withAnyKeys("shift", 340, 344);
        ActionKeys<String> keys = new ActionKeys<>(map, edges);
        assertFalse(keys.held("shift"));
        held.add(344);
        assertTrue(keys.held("shift"));
        assertTrue(keys.pressedOnce("shift"));
        held.add(340); // the second key goes down while the first is held: its own edge fires
        assertTrue(keys.pressedOnce("shift"));
        assertFalse(keys.pressedOnce("shift"));
        keys.reset("shift");
        assertTrue(keys.pressedOnce("shift"));
    }
}
