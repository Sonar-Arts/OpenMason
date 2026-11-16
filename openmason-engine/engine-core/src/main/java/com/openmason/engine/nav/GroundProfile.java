package com.openmason.engine.nav;

/**
 * How a walker moves ({@code cn_ground_profile}): the headroom it needs, the heights it climbs and
 * drops, and what water costs it. The climb limits must mirror the body's own step rules, or routes
 * plan moves the body then fails to make.
 *
 * @param height             standing height: the headroom every cell must give
 * @param columnRadius       extra columns of clearance either side (0 = one column)
 * @param maxStepUp          rise walked without a jump
 * @param maxClimb           greatest rise on land, jumping included
 * @param waterEscapeClimb   greatest rise when leaving water (a stroke is not a standing jump)
 * @param maxFall            greatest voluntary drop
 * @param canSwim            may enter water deeper than its feet
 * @param wadeCostMultiplier cost multiplier for water it stands in (at least 1)
 * @param swimCostMultiplier cost multiplier for water over water (at least 1)
 * @param stepCost           surcharge for a step up
 * @param jumpCost           surcharge for a jump up
 * @param fallCostPerBlock   surcharge per block dropped
 */
public record GroundProfile(
        float height,
        int columnRadius,
        float maxStepUp,
        float maxClimb,
        float waterEscapeClimb,
        float maxFall,
        boolean canSwim,
        float wadeCostMultiplier,
        float swimCostMultiplier,
        float stepCost,
        float jumpCost,
        float fallCostPerBlock) {

    public GroundProfile {
        if (!(height > 0.0f)) {
            throw new IllegalArgumentException("height must be positive: " + height);
        }
        if (columnRadius < 0) {
            throw new IllegalArgumentException("columnRadius must not be negative: " + columnRadius);
        }
        if (maxStepUp < 0.0f || maxClimb < 0.0f || waterEscapeClimb < 0.0f || maxFall < 0.0f) {
            throw new IllegalArgumentException("climb and fall limits must not be negative");
        }
        // Below 1 the octile heuristic would over-estimate, silently costing optimality.
        if (wadeCostMultiplier < 1.0f || swimCostMultiplier < 1.0f) {
            throw new IllegalArgumentException("liquid cost multipliers must be >= 1");
        }
        if (stepCost < 0.0f || jumpCost < 0.0f || fallCostPerBlock < 0.0f) {
            throw new IllegalArgumentException("surcharges must not be negative");
        }
    }
}
