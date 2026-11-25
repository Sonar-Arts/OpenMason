package com.openmason.engine.rendering.sky;

import org.joml.Vector3f;

/**
 * One frame's sky, as the caller's time-of-day system resolves it. Keeps
 * {@link SkyRenderer} free of any game-world coupling.
 *
 * @param sunDirection   normalized direction to the sun (world space)
 * @param moonDirection  normalized direction to the moon (world space)
 * @param moonLight      moonlight relative to a full moon overhead (0..1): drives the halo
 * @param zenithColor    sky colour straight up, away from any sunset
 * @param horizonColor   sky colour at the horizon, away from any sunset
 * @param sunsetColor    sunset gold at the horizon × its strength (black for none), drawn low over
 *                       the sky's sun side; {@code sky.frag} grades it paler up to its scale height
 * @param sunsetCoverage how far round from the sun the gold reaches: 0.5 is overhead, 1 the
 *                       horizon opposite; below 0 none (see {@code sky.frag})
 * @param sunGlow        the afterglow: red light low on the horizon toward the sun, premultiplied by strength
 * @param sunColor       sunlight's hue (brightest channel 1): the disc and its glow take it
 * @param earthShadowColor  the Earth's shadow, the band rising opposite the sun after sunset
 * @param earthShadowHeight its edge's height straight opposite the sun (sine of elevation)
 * @param twilightBand   0..1: how strongly the shadow and the pink Belt of Venus above it show
 * @param daylight       0 with the sun down, 1 in full day: fades the haze in and washes the moon out
 * @param seaHorizon     the sea's edge as the eye sees it (sine of elevation, 0 or below): the sun
 *                       shines fully until it reaches it, then fades as it sinks
 * @param celestialPole  normalized direction the sky turns about (world space): the moon's disc
 *                       is set square to it
 * @param skyAngle       how far the sky has turned about the pole (radians, right-handed, 0 with
 *                       the sun on the meridian): the stars turn with it
 * @param time           seconds, any origin: the stars' twinkle
 * @param aerosol        the air's haze relative to a clear day's (1): above 1 (dusk) the sun's
 *                       aureole and the afterglow broaden and the horizon hazes; below (dawn) the
 *                       air is clean
 */
public record SkyParams(Vector3f sunDirection, Vector3f moonDirection, float moonLight,
                        Vector3f zenithColor, Vector3f horizonColor,
                        Vector3f sunsetColor, float sunsetCoverage, Vector3f sunGlow,
                        Vector3f sunColor, Vector3f earthShadowColor, float earthShadowHeight,
                        float twilightBand, float daylight, float seaHorizon, Vector3f celestialPole,
                        float skyAngle, float time, float aerosol) {

    /** The pole of a sky seen from the equator, turning about the horizon's +Z point. */
    public static final Vector3f EQUATOR_POLE = new Vector3f(0f, 0f, 1f);

    /**
     * A sky seen from the equator ({@link #EQUATOR_POLE}) whose sea edge is the geometric
     * horizon ({@code seaHorizon} 0), for callers without a sea or a latitude.
     */
    public SkyParams(Vector3f sunDirection, Vector3f moonDirection, float moonLight,
                     Vector3f zenithColor, Vector3f horizonColor,
                     Vector3f sunsetColor, float sunsetCoverage, Vector3f sunGlow,
                     Vector3f sunColor, Vector3f earthShadowColor, float earthShadowHeight,
                     float twilightBand, float daylight) {
        this(sunDirection, moonDirection, moonLight, zenithColor, horizonColor, sunsetColor, sunsetCoverage,
                sunGlow, sunColor, earthShadowColor, earthShadowHeight, twilightBand, daylight, 0f, EQUATOR_POLE,
                0f, 0f, 1f);
    }
}
