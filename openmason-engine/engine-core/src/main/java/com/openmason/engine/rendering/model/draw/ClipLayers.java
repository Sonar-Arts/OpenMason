package com.openmason.engine.rendering.model.draw;

import com.openmason.engine.format.oma.ParsedAnimClip;

import java.util.List;

/**
 * The animation a model is posed with this frame: a base clip plus weighted
 * overlay clips, already resolved to {@link ParsedAnimClip}s. Times are raw
 * elapsed seconds; {@link ModelPoseSolver} wraps them per clip and combines each
 * overlay's weight with the clip's own fade envelope.
 *
 * <p>This is the neutral input a host's own animation state (named states,
 * blend envelopes, gameplay layering) reduces to before drawing.
 *
 * @param baseClip base clip, or null for the rest pose
 * @param baseTime elapsed base-clip time in seconds
 * @param overlays overlay layers, in priority order (empty for none)
 */
public record ClipLayers(ParsedAnimClip baseClip, float baseTime, List<Overlay> overlays) {

    /** One overlay layer: a clip, its elapsed time and the caller's weight envelope (0..1). */
    public record Overlay(ParsedAnimClip clip, float time, float weight) {}

    /** The rest pose. */
    public static final ClipLayers REST = new ClipLayers(null, 0f, List.of());

    public ClipLayers {
        overlays = overlays == null ? List.of() : List.copyOf(overlays);
    }

    /** Just {@code clip} at {@code time} (null clip = rest pose). */
    public static ClipLayers single(ParsedAnimClip clip, float time) {
        return clip == null ? REST : new ClipLayers(clip, time, List.of());
    }
}
