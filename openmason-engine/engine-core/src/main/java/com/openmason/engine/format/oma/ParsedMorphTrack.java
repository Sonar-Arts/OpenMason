package com.openmason.engine.format.oma;

import java.util.List;
import java.util.Objects;

/**
 * One morph (shape-key) weight track of an {@code .omanim} 1.3 clip (OpenMason #171): the morph channel it keys by
 * NAME (bound to a rig's channels by name at sampling) and its scalar keys - the layout of {@code cm_curve_key}
 * ({@code interp}: 0 linear, 1 step, 2 cubic Hermite with tangents in value per second). Read natively
 * ({@code cm_clip_morph_*}, see {@code anim.AnimClipDocument#morphTracks}); {@code rig.RigClip} hands them to the
 * native clip ({@code cr_clip_set_morph_tracks}).
 *
 * @param name the morph channel name (without a {@code morph:} prefix)
 * @param keys keys sorted by time
 */
public record ParsedMorphTrack(String name, List<Key> keys) {

    /** One key ({@code cm_curve_key}). */
    public record Key(float time, float value, float inTangent, float outTangent, int interp) {
        /** A linear key. */
        public static Key linear(float time, float value) {
            return new Key(time, value, 0f, 0f, 0);
        }
    }

    public ParsedMorphTrack {
        Objects.requireNonNull(name, "name");
        keys = keys == null ? List.of() : List.copyOf(keys);
    }
}
