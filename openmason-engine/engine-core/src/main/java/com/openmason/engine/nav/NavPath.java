package com.openmason.engine.nav;

/**
 * A finished route: waypoints in order (doubles, in the frame of whoever produced it: the nav
 * world's for a raw search, the agent's for an agent route), and whether it reaches its goal.
 * Immutable, so it can be handed to another thread (a debug overlay) as is.
 */
public final class NavPath {

    public static final NavPath EMPTY = new NavPath(new double[0], false);

    private final double[] points; // x, y, z per waypoint
    private final boolean complete;

    NavPath(double[] points, boolean complete) {
        this.points = points;
        this.complete = complete;
    }

    /** A route from xyz triplets (copied). */
    public static NavPath of(double[] xyz, boolean complete) {
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

    public double x(int index) {
        return points[index * 3];
    }

    public double y(int index) {
        return points[index * 3 + 1];
    }

    public double z(int index) {
        return points[index * 3 + 2];
    }
}
