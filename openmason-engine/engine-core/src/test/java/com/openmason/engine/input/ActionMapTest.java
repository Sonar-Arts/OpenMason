package com.openmason.engine.input;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The generic binding map (#96): matching, remapping and the settings wire round trip. */
class ActionMapTest {

    enum Act {
        FORWARD("move.forward"), JUMP("move.jump"), UNDO("edit.undo"), REDO("edit.redo");

        final String id;

        Act(String id) {
            this.id = id;
        }
    }

    static final ActionMap.ActionSet<Act> SET = ActionMap.ActionSet.ofEnum(Act.class, a -> a.id);
    static final int W = 87;
    static final int Z = 90;
    static final int SPACE = 32;

    static ActionMap<Act> defaults() {
        return ActionMap.empty(SET)
                .withAnyKeys(Act.FORWARD, W, 265)
                .withAnyKeys(Act.JUMP, SPACE)
                .withButtons(Act.JUMP, List.of(0))
                .withKeys(Act.UNDO, List.of(KeyChord.exact(Z, InputCodes.MOD_CONTROL)))
                .withKeys(Act.REDO, List.of(KeyChord.exact(Z, InputCodes.MOD_CONTROL | InputCodes.MOD_SHIFT)));
    }

    @Test
    void exactChordsMatchOnlyTheirModifiersAndIgnoreLockBits() {
        ActionMap<Act> m = defaults();
        assertEquals(Act.UNDO, m.actionForKey(Z, InputCodes.MOD_CONTROL));
        assertEquals(Act.UNDO, m.actionForKey(Z, InputCodes.MOD_CONTROL | InputCodes.MOD_CAPS_LOCK));
        assertEquals(Act.REDO, m.actionForKey(Z, InputCodes.MOD_CONTROL | InputCodes.MOD_SHIFT));
        assertNull(m.actionForKey(Z, 0));
    }

    @Test
    void anyModifierChordsMatchWhateverIsHeld() {
        ActionMap<Act> m = defaults();
        assertEquals(Act.FORWARD, m.actionForKey(W, 0));
        assertEquals(Act.FORWARD, m.actionForKey(W, InputCodes.MOD_SHIFT | InputCodes.MOD_ALT));
        assertEquals(Act.FORWARD, m.actionForKey(265, 0));
        assertEquals(Act.JUMP, m.actionForButton(0));
        assertNull(m.actionForButton(1));
    }

    @Test
    void exactChordBeatsALooseOneOnTheSameKey() {
        // FORWARD comes first in set order but is loose; the exact UNDO chord on W must still win.
        ActionMap<Act> m = defaults().withKeys(Act.UNDO, List.of(KeyChord.exact(W, InputCodes.MOD_CONTROL)));
        assertEquals(Act.UNDO, m.actionForKey(W, InputCodes.MOD_CONTROL));
        assertEquals(Act.FORWARD, m.actionForKey(W, 0));
    }

    @Test
    void wireRoundTripsARemappedMap() {
        ActionMap<Act> remapped = defaults()
                .withAnyKeys(Act.FORWARD, 73)
                .withKeys(Act.UNDO, List.of(KeyChord.exact(Z, InputCodes.MOD_SUPER), KeyChord.exact(85, 0)))
                .withButtons(Act.JUMP, List.of(2, 3));
        Map<String, List<String>> wire = remapped.toWire();
        assertEquals(List.of("key:73"), wire.get("move.forward"));
        assertEquals(List.of("key:90+super", "key:85+exact"), wire.get("edit.undo"));
        assertEquals(List.of("key:32", "pad:2", "pad:3"), wire.get("move.jump"));
        assertEquals(remapped, ActionMap.fromWire(wire, defaults()));
    }

    @Test
    void missingUnknownAndUnreadableEntriesKeepDefaults() {
        ActionMap<Act> read = ActionMap.fromWire(Map.of(
                "move.forward", List.of("key:nope", "key:5+hyper"),
                "no.such.action", List.of("key:1"),
                "move.jump", List.of("pad:4")), defaults());
        assertEquals(defaults().keys(Act.FORWARD), read.keys(Act.FORWARD), "all-unreadable keeps the default");
        assertEquals(defaults().keys(Act.UNDO), read.keys(Act.UNDO), "missing keeps the default");
        assertEquals(List.of(), read.keys(Act.JUMP), "a readable list replaces keys and buttons");
        assertEquals(List.of(4), read.buttons(Act.JUMP));
        assertEquals(defaults(), ActionMap.fromWire(null, defaults()));
    }

    @Test
    void anEmptyListUnbinds() {
        ActionMap<Act> read = ActionMap.fromWire(Map.of("move.jump", List.of()), defaults());
        assertTrue(read.keys(Act.JUMP).isEmpty());
        assertTrue(read.buttons(Act.JUMP).isEmpty());
        assertEquals(defaults().withKeys(Act.JUMP, List.of()).withButtons(Act.JUMP, List.of()), read);
    }

    @Test
    void actionSetsRejectDuplicateIdsAndForeignActions() {
        assertThrows(IllegalArgumentException.class,
                () -> ActionMap.ActionSet.of(List.of("a", "b"), s -> "same"));
        ActionMap<String> strings = ActionMap.empty(ActionMap.ActionSet.of(List.of("a"), s -> s));
        assertThrows(IllegalArgumentException.class, () -> strings.withAnyKeys("b", 1));
        assertEquals("a", strings.actionSet().fromId("a"));
        assertNull(strings.actionSet().fromId("b"));
    }

    @Test
    void modifierNamesUseTheWireSpelling() {
        assertEquals("exact", ActionMap.modifierNames(0));
        assertEquals("exact", ActionMap.modifierNames(InputCodes.MOD_NUM_LOCK));
        assertEquals("shift+ctrl+alt+super", ActionMap.modifierNames(InputCodes.MODIFIER_MASK));
    }
}
