package com.openmason.engine.nav;

/**
 * How a flyer routes ({@code cn_air_profile}). The cell size is the clearance model: a cube is
 * flyable only when every cell in it is open.
 *
 * @param cellSize     blocks per node edge
 * @param cruiseY      world y the route prefers (an air agent sets its own)
 * @param altitudeCost cost per cell away from cruiseY
 * @param climbCost    surcharge per cell of ascent
 * @param descendCost  surcharge per cell of descent
 * @param allowUnknown route through unloaded cells
 * @param unknownCost  surcharge for entering one
 * @param minY         lowest world y a route may occupy
 * @param maxY         highest world y a route may occupy
 */
public record AirProfile(
        int cellSize,
        float cruiseY,
        float altitudeCost,
        float climbCost,
        float descendCost,
        boolean allowUnknown,
        float unknownCost,
        int minY,
        int maxY) {

    public AirProfile {
        if (cellSize <= 0) {
            throw new IllegalArgumentException("cellSize must be positive: " + cellSize);
        }
        if (altitudeCost < 0.0f || climbCost < 0.0f || descendCost < 0.0f || unknownCost < 0.0f) {
            throw new IllegalArgumentException("surcharges must not be negative");
        }
        if (minY > maxY) {
            throw new IllegalArgumentException("minY must not exceed maxY: " + minY + " > " + maxY);
        }
    }
}
