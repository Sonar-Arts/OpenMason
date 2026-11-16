package com.openmason.engine.nav;

/**
 * Everything that tunes a {@link GroundAgent} ({@code cn_ground_agent_desc}). The engine has no
 * numbers of its own: the host passes its profile, its search limits and every radius and back-off
 * (steering has its own, {@link SteeringDesc}).
 *
 * @param profile               how the body moves (routes are planned with it)
 * @param limits                bounds on each search
 * @param snapDown              a search's start/goal snap onto a standable cell this far down ...
 * @param snapUp                ... and up
 * @param repathInterval        minimum seconds between searches
 * @param noPathRetry           back-off after a search found nothing
 * @param saturatedRetry        back-off when the service refused
 * @param goalDrift             goal movement that invalidates a route
 * @param waypointRadius        horizontal distance that passes a waypoint
 * @param waypointVerticalSlack vertical slack when passing one
 * @param stuckSpeed            below this speed (blocks/s) a following body is not moving
 * @param stuckSeconds          ... for this long, and it is wedged
 * @param jumpRiseMargin        jump where the next waypoint rises above maxStepUp + this ...
 * @param jumpApproach          ... once within this horizontal distance of it
 */
public record GroundAgentDesc(
        GroundProfile profile,
        NavLimits limits,
        int snapDown,
        int snapUp,
        float repathInterval,
        float noPathRetry,
        float saturatedRetry,
        float goalDrift,
        float waypointRadius,
        float waypointVerticalSlack,
        float stuckSpeed,
        float stuckSeconds,
        float jumpRiseMargin,
        float jumpApproach) {

    void write(java.lang.foreign.MemorySegment s) {
        CendaNav.writeGroundProfile(s, 0, profile);
        CendaNav.writeLimits(s, 48, limits);
        s.set(CendaNav.I32, 64, snapDown);
        s.set(CendaNav.I32, 68, snapUp);
        float[] f = {repathInterval, noPathRetry, saturatedRetry, goalDrift, waypointRadius, waypointVerticalSlack,
            stuckSpeed, stuckSeconds, jumpRiseMargin, jumpApproach};
        for (int i = 0; i < f.length; i++) {
            s.set(CendaNav.F32, 72 + 4L * i, f[i]);
        }
    }
}
