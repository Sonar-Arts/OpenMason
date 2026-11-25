package com.openmason.engine.rendering.sky;

import org.joml.Vector3f;

/**
 * One frame's sky, as the caller's time-of-day system resolves it. Keeps
 * {@link SkyRenderer} free of any game-world coupling. By day and by night the sky's colours
 * come from the atmosphere ({@link com.openmason.engine.rendering.sky.atmosphere.AtmosphereSettings});
 * through the golden hour, the sunset and the blue hour the caller's hand-painted sky takes
 * over ({@code paintedSky}), drawn from the palette and shapes below.
 *
 * @param sunDirection   normalized direction to the sun (world space)
 * @param moonDirection  normalized direction to the moon (world space)
 * @param moonLight      moonlight relative to a full moon overhead (0..1): scales the moonlit sky
 * @param zenithColor    the zenith's screen colour the sky's brightness is matched to: only its
 *                       luminance is used (see {@code sky.frag} brightnessGain), so the sky sits with
 *                       a scene whose other lighting is not yet physical
 * @param daylight       0 with the sun down, 1 in full day: hides the stars and washes the moon out
 * @param seaHorizon     the sea's edge as the eye sees it (sine of elevation, 0 or below): the sun
 *                       shines fully until it reaches it, then fades as it sinks
 * @param celestialPole  normalized direction the sky turns about (world space): the moon's disc
 *                       is set square to it
 * @param skyAngle       how far the sky has turned about the pole (radians, right-handed, 0 with
 *                       the sun on the meridian): the stars turn with it
 * @param time           seconds, any origin: the stars' twinkle
 * @param exposure       the final pass's exposure
 * @param viewAltitude   the eye's height above the planet's surface, km (0 or more)
 * @param paintedSky     0..1: how much of the sky is the hand-painted one rather than the atmosphere
 * @param horizonColor   the painted sky's colour at the horizon, away from any sunset
 * @param sunsetColor    the painted sunset gold at the horizon × its strength (black for none)
 * @param sunsetCoverage how far round from the sun the gold reaches: 0.5 is overhead, 1 the
 *                       horizon opposite; below 0 none
 * @param sunGlow        the painted afterglow: red light low on the horizon toward the sun, premultiplied
 * @param sunColor       sunlight's hue (brightest channel 1): the painted disc and its glow take it
 * @param earthShadowColor  the Earth's shadow, the band rising opposite the sun after sunset
 * @param earthShadowHeight its edge's height straight opposite the sun (sine of elevation)
 * @param twilightBand   0..1: how strongly the shadow and the pink Belt of Venus above it show
 * @param aerosol        the air's haze relative to a clear day's: widens the painted aureole and afterglow
 * @param aureole        0..1: how much of the painted aureole round the sun is drawn (0 with bloom off)
 */
public record SkyParams(Vector3f sunDirection, Vector3f moonDirection, float moonLight,
                        Vector3f zenithColor, float daylight, float seaHorizon, Vector3f celestialPole,
                        float skyAngle, float time, float exposure, float viewAltitude,
                        float paintedSky, Vector3f horizonColor, Vector3f sunsetColor, float sunsetCoverage,
                        Vector3f sunGlow, Vector3f sunColor, Vector3f earthShadowColor, float earthShadowHeight,
                        float twilightBand, float aerosol, float aureole) {
}
