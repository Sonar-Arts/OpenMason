package com.openmason.engine.entity;

/**
 * An entity's clip clock ({@code ce_clip_state}): the clip it plays, the clip flags, the state clock
 * (seconds since the clip last changed) and the continuous clock.
 */
public record ClipState(int clip, int flags, float stateTime, float totalTime) {

    /** {@code CE_CLIP_ONE_SHOT}: the clip plays once, sampled on the state clock. */
    public static final int ONE_SHOT = 1;

    /** A fresh clock: no clip yet, both clocks at zero. */
    public static final ClipState INITIAL = new ClipState(-1, 0, 0.0f, 0.0f);

    public boolean oneShot() {
        return (flags & ONE_SHOT) != 0;
    }
}
