package com.openmason.engine.physics;

/** A ray or sweep hit ({@code cp_hit}), filled by {@link PhysicsWorld}. Mutable, reusable. */
public final class RayHit {

    public static final int KIND_NONE = 0;
    public static final int KIND_CELL = 1;
    public static final int KIND_BOX = 2;
    public static final int KIND_FLUID = 3;

    public static final int FACE_NONE = -1;
    public static final int FACE_NEG_X = 0;
    public static final int FACE_POS_X = 1;
    public static final int FACE_NEG_Y = 2;
    public static final int FACE_POS_Y = 3;
    public static final int FACE_NEG_Z = 4;
    public static final int FACE_POS_Z = 5;

    public int kind;
    /** The side of the collider that was hit; {@link #FACE_NONE} when the start was already inside. */
    public int face = FACE_NONE;
    /** The cell hit (for a dynamic box: the cell holding the hit point). */
    public int x, y, z;
    /** Shape id, fluid id or dynamic box id. */
    public int id;
    /** Along the ray in world units, or the fraction 0..1 of a sweep. */
    public float distance = Float.POSITIVE_INFINITY;
    /** The hit point (a sweep: the swept box's centre there). */
    public float px, py, pz;
    /** The dynamic box's host value. */
    public long user;

    public boolean hit() {
        return kind != KIND_NONE;
    }
}
