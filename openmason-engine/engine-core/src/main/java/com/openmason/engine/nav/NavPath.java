package com.openmason.engine.nav;

/**
 * A finished route: waypoints in order, and whether it reaches its goal. Immutable, so it can be
 * handed to another thread (a debug overlay) as is.
 */
public final class NavPath {

    public static final NavPath EMPTY = new NavPath(new float[0], false);

    private final float[] points; // x, y, z per waypoint
    private final boolean complete;

    NavPath(float[] points, boolean complete) {
        this.points = points;
        this.complete = complete;
    }

    /** A route from xyz triplets (copied). */
    public static NavPath of(float[] xyz, boolean complete) {
        return xyz.length == 0 ? EMPTY : new NavPath(xyz.clone(), complete);
    }

    public int size() {
        return points.length / 3;
    }

    public boolean isEmpty() {
        return points.length == 0;
    }

    /** Whether this route reaches the goal, rather than getting as close as it could. */
    public boolean isComplete() {
        return complete;
    }

    public float x(int index) {
        return points[index * 3];
    }

    public float y(int index) {
        return points[index * 3 + 1];
    }

    public float z(int index) {
        return points[index * 3 + 2];
    }
}
