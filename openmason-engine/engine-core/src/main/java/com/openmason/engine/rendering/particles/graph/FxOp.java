package com.openmason.engine.rendering.particles.graph;

import java.util.Locale;

/**
 * Operators of the FX graph IR. Arithmetic is elementwise and broadcasts a {@link FxType#FLOAT} operand over a
 * {@link FxType#VEC3} one; everything evaluates in IEEE float exactly as written (no algebraic rewrites), so a graph
 * reproduces hand-written float code bit for bit.
 *
 * <p>Arity is the number of {@link FxNode#inputs()}; {@code values} and {@code attribute} are listed per op.
 */
public enum FxOp {
    // ---- sources ----
    /** {@code values}: 1 (float) or 3 (vec3) numbers. */
    CONST(0),
    /** Reads {@code attribute} (a built-in or declared particle/emitter attribute). */
    READ(0),
    /** Seconds this step. */
    DT(0),
    /** The system wind (vec3, blocks/s²). */
    WIND(0),
    /** Uniform random in [0, 1), a fresh number per particle per evaluation (the program's own stream). */
    RANDOM(0),

    // ---- sinks ----
    /** Writes input 0 to {@code attribute}. */
    WRITE(1),

    // ---- math ----
    ADD(2), SUB(2), MUL(2), DIV(2), MIN(2), MAX(2),
    NEG(1), ABS(1), SQRT(1), SIN(1), COS(1), EXP(1), FLOOR(1),
    /** {@code a + (b − a) × t}. */
    LERP(3),
    /** {@code min(max(x, lo), hi)}. */
    CLAMP(3),
    /** Builds a vec3 from three floats. */
    VEC3(3),
    /** Components of a vec3. */
    X(1), Y(1), Z(1),
    DOT(2), CROSS(2), LENGTH(1), NORMALIZE(1),
    /** Piecewise-linear curve of input 0: {@code values} = t0, v0, t1, v1, … with ascending t (held at the ends). */
    CURVE(1),
    /** Smooth 3D gradient noise of a vec3 position, in about [−1, 1]. */
    NOISE(1),
    /** Divergence-free curl of 3D noise at a vec3 position (swirling, incompressible flow). */
    CURL(1),

    // ---- intrinsics (built-in modules only) ----
    /** Moves particles by input 0 (their new velocity) through the host collider, or freely without one. */
    COLLIDE(1),
    /** Queues the effect's TRAIL sub-emitters for every particle. */
    TRAIL(0);

    public final int arity;

    FxOp(int arity) {
        this.arity = arity;
    }

    /** The IR's spelling ({@code "add"}). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static FxOp of(String id) {
        return valueOf(id.toUpperCase(Locale.ROOT));
    }

    /** True for ops only built-in modules may use. */
    public boolean intrinsic() {
        return this == COLLIDE || this == TRAIL;
    }
}
