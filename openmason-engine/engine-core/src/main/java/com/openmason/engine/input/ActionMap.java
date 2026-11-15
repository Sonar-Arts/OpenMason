package com.openmason.engine.input;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Bindings from keys and controller buttons to a host's own actions (#96, generalized from the UI's
 * {@code UiActionMap}, #288). The engine owns the mechanism; each host declares its action set
 * ({@link ActionSet}) and its defaults. Immutable: {@link #withKeys}/{@link #withButtons} return a
 * remapped copy, and {@link #toWire}/{@link #fromWire} round-trip it through settings.
 *
 * <p><b>Matching.</b> {@link #actionForKey} tries exact chords first ({@link KeyChord}); among
 * chords with any modifiers the first action in the set's order wins. Lock-key bits are ignored.
 * Polling hosts (held keys, edges) use {@link ActionKeys}.
 *
 * <p><b>Wire form.</b> {@code {"<action id>": ["key:257", "key:32+exact", "key:90+ctrl", "pad:0"]}}:
 * {@code key:<code>} with any modifiers, {@code key:<code>+<mods>} for an exact chord ({@code shift},
 * {@code ctrl}, {@code alt}, {@code super} joined by {@code +}; {@code exact} alone = no modifiers),
 * and {@code pad:<button>}.
 *
 * @param <A> the host's action type (an enum, usually)
 */
public final class ActionMap<A> {

    /**
     * A host's actions in match order, each with a stable wire id ({@code "ui.submit"},
     * {@code "move.forward"}). Ids must be unique.
     */
    public static final class ActionSet<A> {
        private final List<A> actions;
        private final Function<A, String> id;
        private final Map<String, A> byId;

        private ActionSet(List<A> actions, Function<A, String> id) {
            this.actions = List.copyOf(actions);
            this.id = Objects.requireNonNull(id, "id");
            Map<String, A> m = new LinkedHashMap<>();
            for (A a : this.actions) {
                String key = id.apply(a);
                if (m.put(Objects.requireNonNull(key, "action id"), a) != null) {
                    throw new IllegalArgumentException("duplicate action id: " + key);
                }
            }
            this.byId = Collections.unmodifiableMap(m);
        }

        public static <A> ActionSet<A> of(List<A> actions, Function<A, String> id) {
            return new ActionSet<>(actions, id);
        }

        /** Every constant of {@code type}, in declaration order. */
        public static <E extends Enum<E>> ActionSet<E> ofEnum(Class<E> type, Function<E, String> id) {
            return new ActionSet<>(List.of(type.getEnumConstants()), id);
        }

        public List<A> actions() {
            return actions;
        }

        public String id(A action) {
            return id.apply(action);
        }

        /** The action with wire id {@code id}, or null. */
        public A fromId(String id) {
            return byId.get(id);
        }

        boolean contains(A action) {
            return byId.containsKey(id.apply(action));
        }
    }

    private final ActionSet<A> set;
    private final Map<A, List<KeyChord>> keys;
    private final Map<A, List<Integer>> buttons;

    private ActionMap(ActionSet<A> set, Map<A, List<KeyChord>> keys, Map<A, List<Integer>> buttons) {
        this.set = set;
        // Kept in the set's order (match order), and unbound == absent so equality ignores how an
        // action got unbound.
        Map<A, List<KeyChord>> k = new LinkedHashMap<>();
        Map<A, List<Integer>> b = new LinkedHashMap<>();
        for (A a : set.actions()) {
            List<KeyChord> kc = keys.get(a);
            if (kc != null && !kc.isEmpty()) {
                k.put(a, List.copyOf(kc));
            }
            List<Integer> bc = buttons.get(a);
            if (bc != null && !bc.isEmpty()) {
                b.put(a, List.copyOf(bc));
            }
        }
        this.keys = Collections.unmodifiableMap(k);
        this.buttons = Collections.unmodifiableMap(b);
    }

    /** A map of {@code set} with nothing bound. */
    public static <A> ActionMap<A> empty(ActionSet<A> set) {
        return new ActionMap<>(set, Map.of(), Map.of());
    }

    public ActionSet<A> actionSet() {
        return set;
    }

    /** The action {@code key} with {@code modifiers} triggers, or null. */
    public A actionForKey(int key, int modifiers) {
        A loose = null;
        for (Map.Entry<A, List<KeyChord>> e : keys.entrySet()) {
            for (KeyChord c : e.getValue()) {
                if (c.matches(key, modifiers)) {
                    if (!c.anyModifiers()) {
                        return e.getKey();
                    }
                    if (loose == null) {
                        loose = e.getKey();
                    }
                }
            }
        }
        return loose;
    }

    /** The action a controller button triggers, or null. */
    public A actionForButton(int button) {
        for (Map.Entry<A, List<Integer>> e : buttons.entrySet()) {
            if (e.getValue().contains(button)) {
                return e.getKey();
            }
        }
        return null;
    }

    public List<KeyChord> keys(A action) {
        return keys.getOrDefault(action, List.of());
    }

    public List<Integer> buttons(A action) {
        return buttons.getOrDefault(action, List.of());
    }

    /** A copy with {@code action} bound to exactly {@code chords} (empty unbinds). */
    public ActionMap<A> withKeys(A action, List<KeyChord> chords) {
        requireMember(action);
        Map<A, List<KeyChord>> k = new LinkedHashMap<>(keys);
        k.put(action, chords);
        return new ActionMap<>(set, k, buttons);
    }

    /** A copy with {@code action} bound to exactly {@code buttonIds} (empty unbinds). */
    public ActionMap<A> withButtons(A action, List<Integer> buttonIds) {
        requireMember(action);
        Map<A, List<Integer>> b = new LinkedHashMap<>(buttons);
        b.put(action, buttonIds);
        return new ActionMap<>(set, keys, b);
    }

    /** Convenience for declaring defaults: {@code action} bound to {@code keys} with any modifiers. */
    public ActionMap<A> withAnyKeys(A action, int... keyCodes) {
        List<KeyChord> chords = new ArrayList<>(keyCodes.length);
        for (int k : keyCodes) {
            chords.add(KeyChord.any(k));
        }
        return withKeys(action, chords);
    }

    private void requireMember(A action) {
        if (!set.contains(action)) {
            throw new IllegalArgumentException("not in this map's action set: " + action);
        }
    }

    // ── settings round trip ─────────────────────────────────────────────────

    /** Every action of the set (bound or not) in set order; see the class doc for the entry format. */
    public Map<String, List<String>> toWire() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (A a : set.actions()) {
            List<String> list = new ArrayList<>();
            for (KeyChord c : keys(a)) {
                list.add("key:" + c.key() + (c.anyModifiers() ? "" : "+" + modifierNames(c.modifiers())));
            }
            for (int b : buttons(a)) {
                list.add("pad:" + b);
            }
            out.put(set.id(a), list);
        }
        return out;
    }

    /**
     * Reads {@link #toWire} output over {@code defaults}. Actions missing from {@code wire} keep their
     * defaults, so a settings file from an older build gains new actions bound; unknown actions and
     * malformed entries are skipped, and an action whose entries are all unreadable keeps its default
     * rather than being unbound.
     */
    public static <A> ActionMap<A> fromWire(Map<String, List<String>> wire, ActionMap<A> defaults) {
        ActionMap<A> map = defaults;
        if (wire == null) {
            return map;
        }
        for (Map.Entry<String, List<String>> e : wire.entrySet()) {
            A action = defaults.set.fromId(e.getKey());
            if (action == null || e.getValue() == null) {
                continue;
            }
            List<KeyChord> chords = new ArrayList<>();
            List<Integer> pads = new ArrayList<>();
            for (String entry : e.getValue()) {
                parse(entry, chords, pads);
            }
            if (!e.getValue().isEmpty() && chords.isEmpty() && pads.isEmpty()) {
                continue; // nothing readable: keep the default rather than unbind
            }
            map = map.withKeys(action, chords).withButtons(action, pads);
        }
        return map;
    }

    private static void parse(String entry, List<KeyChord> chords, List<Integer> pads) {
        if (entry == null) {
            return;
        }
        try {
            if (entry.startsWith("pad:")) {
                pads.add(Integer.parseInt(entry.substring(4)));
            } else if (entry.startsWith("key:")) {
                String[] parts = entry.substring(4).split("\\+");
                int key = Integer.parseInt(parts[0]);
                if (parts.length == 1) {
                    chords.add(KeyChord.any(key));
                } else {
                    int mods = 0;
                    for (int i = 1; i < parts.length; i++) {
                        int bit = switch (parts[i].toLowerCase(Locale.ROOT)) {
                            case "shift" -> InputCodes.MOD_SHIFT;
                            case "ctrl" -> InputCodes.MOD_CONTROL;
                            case "alt" -> InputCodes.MOD_ALT;
                            case "super" -> InputCodes.MOD_SUPER;
                            case "exact" -> 0;
                            default -> -1;
                        };
                        if (bit < 0) {
                            return; // an unknown modifier: skip the entry instead of guessing a chord
                        }
                        mods |= bit;
                    }
                    chords.add(KeyChord.exact(key, mods));
                }
            }
        } catch (NumberFormatException ignored) {
            // malformed entries are skipped (see fromWire)
        }
    }

    /** {@code "shift+ctrl"}, or {@code "exact"} for no modifiers: the wire spelling of a chord's modifiers. */
    public static String modifierNames(int mods) {
        if ((mods & InputCodes.MODIFIER_MASK) == 0) {
            return "exact";
        }
        List<String> names = new ArrayList<>();
        if ((mods & InputCodes.MOD_SHIFT) != 0) {
            names.add("shift");
        }
        if ((mods & InputCodes.MOD_CONTROL) != 0) {
            names.add("ctrl");
        }
        if ((mods & InputCodes.MOD_ALT) != 0) {
            names.add("alt");
        }
        if ((mods & InputCodes.MOD_SUPER) != 0) {
            names.add("super");
        }
        return String.join("+", names);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ActionMap<?> m && m.set.actions().equals(set.actions()) && m.keys.equals(keys) && m.buttons.equals(buttons);
    }

    @Override
    public int hashCode() {
        return keys.hashCode() * 31 + buttons.hashCode();
    }

    @Override
    public String toString() {
        return "ActionMap" + toWire();
    }
}
