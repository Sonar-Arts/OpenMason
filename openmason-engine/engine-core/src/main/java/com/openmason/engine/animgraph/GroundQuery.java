package com.openmason.engine.animgraph;

/**
 * The host's answer to foot placement's ground query (OpenMason #159; {@code cr_ground_fn} in
 * {@code cenda/rig.h}). The engine assumes no world type: once per {@code footPlacement} modifier per
 * {@link AnimGraphInstance#evaluate}, it hands over every probe of every leg (heel, then toe, in leg order) as
 * downward segments in WORLD space (the space the evaluate's base matrix maps the model into), and the host fills
 * in the ground each one meets. A probe left without a hit keeps that point's animation.
 *
 * <p>Called on the evaluating thread, inside {@code evaluate}. Must not evaluate the same instance again. An
 * exception fails that evaluate and is rethrown from it.
 */
@FunctionalInterface
public interface GroundQuery {

    /** Answer every probe of {@code probes} (hits start cleared). */
    void query(GroundProbes probes);
}
