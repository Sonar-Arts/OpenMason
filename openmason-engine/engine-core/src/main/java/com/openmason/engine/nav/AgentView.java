package com.openmason.engine.nav;

/**
 * An agent's state after its last call ({@code cn_agent_view}). Written by the owner thread; the
 * fields are volatile so a reader on another thread (a debug overlay) sees a recent value.
 */
public final class AgentView {

    public static final int IDLE = 0;
    public static final int SEARCHING = 1;
    public static final int FOLLOWING = 2;
    public static final int ARRIVED = 3;
    public static final int NO_PATH = 4;
    public static final int STUCK = 5;

    private volatile int status;
    private volatile int cursor;
    private volatile boolean hasGoal;
    private volatile int pathVersion;
    private volatile float goalX;
    private volatile float goalY;
    private volatile float goalZ;
    private volatile int pathPoints;

    void set(int status, int cursor, boolean hasGoal, int pathVersion, float goalX, float goalY, float goalZ,
             int pathPoints) {
        this.goalX = goalX;
        this.goalY = goalY;
        this.goalZ = goalZ;
        this.pathPoints = pathPoints;
        this.pathVersion = pathVersion;
        this.cursor = cursor;
        this.hasGoal = hasGoal;
        this.status = status;
    }

    /** One of the constants above. */
    public int status() {
        return status;
    }

    /** The waypoint being walked / flown toward. */
    public int cursor() {
        return cursor;
    }

    public boolean hasGoal() {
        return hasGoal;
    }

    /** Changes whenever the route changes. */
    public int pathVersion() {
        return pathVersion;
    }

    public float goalX() {
        return goalX;
    }

    public float goalY() {
        return goalY;
    }

    public float goalZ() {
        return goalZ;
    }

    public int pathPoints() {
        return pathPoints;
    }
}
