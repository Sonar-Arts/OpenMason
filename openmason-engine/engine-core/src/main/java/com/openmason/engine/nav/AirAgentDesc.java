package com.openmason.engine.nav;

/**
 * Everything that tunes an {@link AirAgent} ({@code cn_air_agent_desc}).
 *
 * @param profile        how the flyer routes (its cruiseY is ignored: each agent has its own)
 * @param limits         bounds on each search
 * @param planRange      how far ahead one search plans
 * @param replanTravel   distance flown before re-planning
 * @param replanInterval minimum seconds between searches
 * @param noPathRetry    back-off after a search found nothing
 * @param saturatedRetry back-off when the service refused
 * @param waypointRadius distance that passes a waypoint
 * @param goalRadius     a search's arrival radius, in blocks
 * @param goalSnapCells  a search's goal is nudged onto flyable air within this many cells
 */
public record AirAgentDesc(
        AirProfile profile,
        NavLimits limits,
        float planRange,
        float replanTravel,
        float replanInterval,
        float noPathRetry,
        float saturatedRetry,
        float waypointRadius,
        float goalRadius,
        int goalSnapCells) {

    void write(java.lang.foreign.MemorySegment s) {
        CendaNav.writeAirProfile(s, 0, profile);
        CendaNav.writeLimits(s, 40, limits);
        float[] f = {planRange, replanTravel, replanInterval, noPathRetry, saturatedRetry, waypointRadius, goalRadius};
        for (int i = 0; i < f.length; i++) {
            s.set(CendaNav.F32, 56 + 4L * i, f[i]);
        }
        s.set(CendaNav.I32, 84, goalSnapCells);
        s.set(CendaNav.I32, 88, 0);
        s.set(CendaNav.I32, 92, 0);
    }
}
