package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.input.ActionMap;
import com.openmason.engine.input.KeyChord;
import com.openmason.engine.ui.masonry.MKeys;

import java.util.List;
import java.util.Map;

/**
 * The player's bindings from keys and controller buttons to {@link UiAction}s (#288): the UI's
 * instance of the engine's generic {@link ActionMap} (#96), which owns matching and the settings
 * wire form. Immutable; {@link #withKeys}/{@link #withButtons} return a remapped copy, and
 * {@link #toWire}/{@link #fromWire} round-trip it through settings. Every action-hint glyph is read
 * from here ({@link ActionHints}), so remapping a key changes the hints with it.
 *
 * <p><b>Matching.</b> A {@link KeyChord} with explicit modifiers matches only exactly those
 * (Shift+Tab is "previous", Tab alone is "next"); a chord with {@link KeyChord#ANY_MODIFIERS}
 * matches whatever is held. Exact chords are tried first. Lock-key bits (Caps/Num Lock) are
 * ignored.
 */
public final class UiActionMap {

    /** Shift, Control, Alt, Super; Caps/Num Lock are not modifiers for bindings. */
    public static final int MODIFIER_MASK = MKeys.MOD_SHIFT | MKeys.MOD_CONTROL | MKeys.MOD_ALT | MKeys.MOD_SUPER;

    /** Every {@link UiAction} in declaration order, by its {@link UiAction#id()}. */
    public static final ActionMap.ActionSet<UiAction> ACTIONS = ActionMap.ActionSet.ofEnum(UiAction.class, UiAction::id);

    private static final UiActionMap DEFAULTS = new UiActionMap(createDefaults());

    private final ActionMap<UiAction> map;

    private UiActionMap(ActionMap<UiAction> map) {
        this.map = map;
    }

    /**
     * Arrows navigate, Tab/Shift+Tab move through the tab order, Enter/keypad Enter/Space
     * submit, Escape cancels, Page Up/Down page; controller: D-pad navigates, bumpers move
     * through the tab order, A submits, B cancels.
     */
    public static UiActionMap defaults() {
        return DEFAULTS;
    }

    private static ActionMap<UiAction> createDefaults() {
        return ActionMap.empty(ACTIONS)
            .withKeys(UiAction.NAVIGATE_UP, List.of(KeyChord.any(MKeys.KEY_UP)))
            .withKeys(UiAction.NAVIGATE_DOWN, List.of(KeyChord.any(MKeys.KEY_DOWN)))
            .withKeys(UiAction.NAVIGATE_LEFT, List.of(KeyChord.any(MKeys.KEY_LEFT)))
            .withKeys(UiAction.NAVIGATE_RIGHT, List.of(KeyChord.any(MKeys.KEY_RIGHT)))
            .withKeys(UiAction.NEXT, List.of(KeyChord.exact(MKeys.KEY_TAB, 0)))
            .withKeys(UiAction.PREVIOUS, List.of(KeyChord.exact(MKeys.KEY_TAB, MKeys.MOD_SHIFT)))
            .withKeys(UiAction.SUBMIT, List.of(KeyChord.any(MKeys.KEY_ENTER), KeyChord.any(MKeys.KEY_KP_ENTER),
                KeyChord.exact(MKeys.KEY_SPACE, 0)))
            .withKeys(UiAction.CANCEL, List.of(KeyChord.any(MKeys.KEY_ESCAPE)))
            .withKeys(UiAction.PAGE_UP, List.of(KeyChord.any(MKeys.KEY_PAGE_UP)))
            .withKeys(UiAction.PAGE_DOWN, List.of(KeyChord.any(MKeys.KEY_PAGE_DOWN)))
            .withButtons(UiAction.NAVIGATE_UP, List.of(GamepadButtons.DPAD_UP))
            .withButtons(UiAction.NAVIGATE_DOWN, List.of(GamepadButtons.DPAD_DOWN))
            .withButtons(UiAction.NAVIGATE_LEFT, List.of(GamepadButtons.DPAD_LEFT))
            .withButtons(UiAction.NAVIGATE_RIGHT, List.of(GamepadButtons.DPAD_RIGHT))
            .withButtons(UiAction.NEXT, List.of(GamepadButtons.RIGHT_BUMPER))
            .withButtons(UiAction.PREVIOUS, List.of(GamepadButtons.LEFT_BUMPER))
            .withButtons(UiAction.SUBMIT, List.of(GamepadButtons.A))
            .withButtons(UiAction.CANCEL, List.of(GamepadButtons.B));
    }

    /** The generic map behind this one. */
    public ActionMap<UiAction> asActionMap() {
        return map;
    }

    /** The action {@code key} with {@code modifiers} triggers, or null. */
    public UiAction actionForKey(int key, int modifiers) {
        return map.actionForKey(key, modifiers);
    }

    /** The action a controller button triggers, or null. */
    public UiAction actionForButton(int button) {
        return map.actionForButton(button);
    }

    public List<KeyChord> keys(UiAction action) {
        return map.keys(action);
    }

    public List<Integer> buttons(UiAction action) {
        return map.buttons(action);
    }

    /** A copy with {@code action} bound to exactly {@code chords} (empty unbinds). */
    public UiActionMap withKeys(UiAction action, List<KeyChord> chords) {
        return new UiActionMap(map.withKeys(action, chords));
    }

    /** A copy with {@code action} bound to exactly {@code buttonIds} (empty unbinds). */
    public UiActionMap withButtons(UiAction action, List<Integer> buttonIds) {
        return new UiActionMap(map.withButtons(action, buttonIds));
    }

    // ── settings round trip ─────────────────────────────────────────────────

    /**
     * {@code {"ui.submit": ["key:257", "key:32+exact", "pad:0"], ...}}: {@code key:<glfw>} with
     * any modifiers, {@code key:<glfw>+<mods>} for an exact chord ({@code shift}, {@code ctrl},
     * {@code alt}, {@code super}, joined by {@code +}; {@code exact} alone = no modifiers), and
     * {@code pad:<button>}.
     */
    public Map<String, List<String>> toWire() {
        return map.toWire();
    }

    /**
     * Reads {@link #toWire} output. Actions missing from {@code wire} keep their defaults, so a
     * settings file from an older build gains new actions bound; unknown actions and malformed
     * entries are skipped (cancel must stay reachable).
     */
    public static UiActionMap fromWire(Map<String, List<String>> wire) {
        return new UiActionMap(ActionMap.fromWire(wire, DEFAULTS.map));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UiActionMap m && m.map.equals(map);
    }

    @Override
    public int hashCode() {
        return map.hashCode();
    }
}
