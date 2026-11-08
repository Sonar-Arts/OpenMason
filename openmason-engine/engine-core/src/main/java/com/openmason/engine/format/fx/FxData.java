package com.openmason.engine.format.fx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Optional {@code fx[]} section of an asset manifest (SBO 1.12+, SBE 1.6+, any container that wants it): which
 * FX play on which of the asset's events, so a game keeps no hard-coded "block → effect" tables. The FX twin of
 * the {@code sounds[]} section ({@link com.openmason.engine.format.sound.SoundData}); the standard event names are
 * the same.
 *
 * <p>Event names are free-form: besides the standard block events an asset may bind any other (an entity's
 * {@code hurt}/{@code death}, a door's {@code open}) and the runtime looks them up by name. {@link #EVENT_AMBIENT}
 * differs from the rest in being a state rather than a moment: its FX runs for as long as the asset is present
 * (a campfire's smoke) and stops when it goes away.
 *
 * @param fx the bindings in manifest order; never null but may be empty
 */
public record FxData(List<FxDef> fx) {

    /** Standard event: the block was destroyed. */
    public static final String EVENT_BREAK = "break";
    /** Standard event: the block is being hit while it is broken. */
    public static final String EVENT_HIT = "hit";
    /** Standard event: the block was placed in the world. */
    public static final String EVENT_PLACE = "place";
    /** Standard event: an entity stepped on the block. */
    public static final String EVENT_STEP = "step";
    /** Standard event, continuous: the FX runs while the asset is present and stops when it is removed. */
    public static final String EVENT_AMBIENT = "ambient";

    /** The standard events, in the order editors offer them. */
    public static final List<String> STANDARD_EVENTS =
            List.of(EVENT_BREAK, EVENT_HIT, EVENT_PLACE, EVENT_STEP, EVENT_AMBIENT);

    public FxData {
        fx = fx == null ? Collections.emptyList() : List.copyOf(fx);
    }

    /** All bindings for {@code event} (case-sensitive), in manifest order; empty when none. */
    public List<FxDef> defsFor(String event) {
        if (event == null || fx.isEmpty()) return List.of();
        List<FxDef> matches = new ArrayList<>();
        for (FxDef def : fx) {
            if (def.event().equals(event)) matches.add(def);
        }
        return matches;
    }

    /** Distinct event names bound, in manifest order. */
    public Set<String> events() {
        Set<String> names = new LinkedHashSet<>();
        for (FxDef def : fx) names.add(def.event());
        return names;
    }

    /** Distinct FX ids referenced, in manifest order. */
    public Set<String> fxIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (FxDef def : fx) ids.add(def.fx());
        return ids;
    }

    public boolean isEmpty() {
        return fx.isEmpty();
    }
}
