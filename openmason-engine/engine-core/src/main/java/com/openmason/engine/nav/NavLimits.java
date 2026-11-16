package com.openmason.engine.nav;

/**
 * Bounds on one search ({@code cn_limits}).
 *
 * @param maxExpansions   hard cap on expanded nodes; the search returns its best partial chain then
 * @param maxCost         routes costing more are not explored; {@link Float#MAX_VALUE} for none
 * @param heuristicWeight multiplier on the heuristic, at least 1 (above 1 trades optimality for speed)
 */
public record NavLimits(int maxExpansions, float maxCost, float heuristicWeight) {

    public NavLimits {
        if (maxExpansions <= 0) {
            throw new IllegalArgumentException("maxExpansions must be positive: " + maxExpansions);
        }
        if (!(maxCost > 0.0f)) {
            throw new IllegalArgumentException("maxCost must be positive: " + maxCost);
        }
        if (!(heuristicWeight >= 1.0f)) {
            throw new IllegalArgumentException("heuristicWeight must be >= 1: " + heuristicWeight);
        }
    }
}
