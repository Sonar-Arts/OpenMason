#version 330 core

in vec3 worldPos;
in vec3 viewDirection;
in vec3 cameraPos;

uniform vec3 sunDirection;
uniform vec3 moonDirection;
uniform float moonLight;      // moonlight relative to a full moon overhead (0..1)
uniform vec3 zenithColor;
uniform vec3 horizonColor;
uniform vec3 sunGlow;         // the afterglow: red horizon band toward the set sun, premultiplied
uniform vec3 sunColor;        // sunlight's hue: white high up, gold low, orange on the horizon
uniform vec3 earthShadowColor;  // the Earth's shadow rising opposite the sun after sunset
uniform float earthShadowHeight; // its edge's height straight opposite the sun (sine of elevation)
uniform float twilightBand;   // 0..1: how strongly the shadow and the Belt of Venus show
uniform vec3 sunsetColor;     // evening gold at the horizon × strength (0 = none)
uniform float sunsetCoverage; // how far round from the sun the gold reaches
uniform float daylight;       // 0 sun down .. 1 full day
uniform float seaHorizon;     // the sea's edge seen from the eye (sine of elevation, <= 0)
uniform vec3 celestialPole;   // the point the sky turns about (unit)
uniform sampler2D moonTexture; // 64x64 pixel art, alpha = disc

out vec4 FragColor;

// Moon disc angular radius: ~5 degrees, so its 64 texels land on ~2 screen pixels each.
const float MOON_RADIUS = 0.0873;
const float MOON_TEXELS = 64.0;
// The disc's light: it reads as bright off-white whatever the phase; only the lit share changes.
const float MOON_INTENSITY = 1.05;
// Earth lit by the sun lights the moon's night side (earthshine), most at new moon.
const float EARTHSHINE = 0.035;
const vec3 MOON_HALO = vec3(0.55, 0.62, 0.80);
// Soft edge of the sunset gold; SkyLight.SUNSET_SOFTNESS.
const float SUNSET_SOFTNESS = 0.3;
// Scale heights (sine of elevation) of the gold opposite the sun and toward it;
// SkyLight.SUNSET_H_AWAY / SUNSET_H_SUN.
const float SUNSET_H_AWAY = 0.06;
const float SUNSET_H_SUN = 0.35;
// The gold's hue at the horizon and at its scale height; SkyLight.SUNSET_GOLD_LOW / HIGH.
const vec3 SUNSET_GOLD_LOW = vec3(1.0, 0.42, 0.18);
const vec3 SUNSET_GOLD_HIGH = vec3(1.0, 0.80, 0.48);
// The gold seen over the land below the horizon, relative to the horizon's.
const float SUNSET_BELOW = 0.67;
// The Belt of Venus: the pink of the sky lit by the reddened sun, just above the Earth's shadow.
const vec3 BELT_PINK = vec3(1.0, 0.58, 0.56);
// The sun disc's own tint over sunlight's hue: a touch yellow at noon.
const vec3 SUN_DISC = vec3(1.0, 0.95, 0.7);
// How far (sun y) below the sea's edge the sun takes to fade out once it reaches it.
const float SUN_SINK = 0.12;

// Dave Hoskins' sine-free hash: uniform on [0, 1), no visible pattern.
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Triangular-PDF dither, one 8-bit step either way. The twilight and night sky
// are gradients so dark and shallow that one 8-bit step spans many pixels, and
// rounding draws them as rings. Noise added before the rounding turns the rings
// into grain too fine to see. Two uniform hashes summed (minus 1) make a
// triangular distribution on [-1, 1], whose error variance does not depend on
// the signal, so the grain is equally fine in every tone.
float ditherTpdf(vec2 fragCoord) {
    return (hash12(fragCoord) + hash12(fragCoord + vec2(57.31, 113.97)) - 1.0) / 255.0;
}

vec3 getTimedSkyColor(vec3 direction) {
    float elevation = direction.y;
    vec3 groundColor = horizonColor * 0.8;

    // Add distance-based fading to reduce hard edges
    float horizontalDistance = length(vec2(direction.x, direction.z));
    float edgeFade = 1.0 - smoothstep(0.85, 0.98, horizontalDistance);

    vec3 horizon = horizonColor;

    if (elevation >= 0.0) {
        float t = smoothstep(0.0, 1.0, elevation);
        vec3 color = mix(horizon, zenithColor, t * 0.8 + 0.2 * smoothstep(0.0, 0.4, elevation));
        // Apply edge fading to reduce cube artifacts
        return mix(horizon * 0.95, color, edgeFade);
    } else {
        float t = smoothstep(-0.3, 0.0, elevation);
        vec3 color = mix(groundColor, horizon, t);
        return mix(horizon * 0.85, color, edgeFade * 0.7 + 0.3);
    }
}

// The evening gold over `sky`. Round the sky it reaches `sunsetCoverage` from
// the sun: 0 is the horizon under the sun, 0.5 overhead, 1 the horizon opposite.
// As the coverage falls the gold leaves the opposite horizon first and ends at
// the sun. Up the sky it stays low: the zenith keeps a deep blue through
// twilight, because the sunlight reaching it has crossed the ozone layer at a
// grazing angle, and ozone's Chappuis band absorbs the orange and red (it, not
// Rayleigh scattering, makes most of the twilight blue). So the gold falls off
// as exp(-y / H), with the scale height H low (SUNSET_H_AWAY) opposite the sun
// and higher (SUNSET_H_SUN) toward it, where forward scattering lifts the glow.
// Its hue follows the reddening: deep orange at the horizon, where the light
// has crossed the most air, paling to a pale gold at height H.
// SkyLight.sunsetWeight mirrors the shape for the fog and clouds.
vec3 applySunset(vec3 direction, vec3 sky) {
    float strength = max(sunsetColor.r, max(sunsetColor.g, sunsetColor.b));
    if (strength <= 0.0) return sky;
    vec2 flatSun = sunDirection.xz / max(length(sunDirection.xz), 1e-4);
    vec2 flatDir = direction.xz / max(length(direction.xz), 1e-4);
    float fromSun = 0.5 - 0.5 * dot(direction, vec3(flatSun.x, 0.0, flatSun.y));
    float weight = 1.0 - smoothstep(sunsetCoverage - SUNSET_SOFTNESS, sunsetCoverage + SUNSET_SOFTNESS, fromSun);
    float h = mix(SUNSET_H_AWAY, SUNSET_H_SUN, 0.5 + 0.5 * dot(flatDir, flatSun));
    weight *= exp(-max(direction.y, 0.0) / h);
    vec3 gold = direction.y >= 0.0
            ? mix(SUNSET_GOLD_LOW, SUNSET_GOLD_HIGH, clamp(direction.y / h, 0.0, 1.0))
            // Below the horizon, the haze over the land darkens a little.
            : SUNSET_GOLD_LOW * mix(SUNSET_BELOW, 1.0, smoothstep(-0.3, 0.0, direction.y));
    // sunsetColor is the horizon gold as the caller tints it (weather greys it):
    // carry that tint over the whole grade.
    vec3 tint = sunsetColor / (strength * SUNSET_GOLD_LOW);
    return mix(sky, gold * tint, weight * strength);
}

// The afterglow over `sky`: low on the horizon where the sun went down (or will
// come up), deeper and redder than the gold, which it lies over.
vec3 applyAfterglow(vec3 direction, vec3 sky) {
    float strength = max(sunGlow.r, max(sunGlow.g, sunGlow.b));
    if (strength <= 0.0) return sky;
    vec2 flatDir = direction.xz / max(length(direction.xz), 1e-4);
    vec2 flatSun = sunDirection.xz / max(length(sunDirection.xz), 1e-4);
    float towardSun = pow(max(dot(flatDir, flatSun), 0.0), 4.0);
    float band = 1.0 - smoothstep(0.0, 0.3, abs(direction.y));
    return mix(sky, sunGlow / strength, strength * towardSun * band);
}

// The Earth's shadow over `sky`: after sunset it rises from the horizon opposite
// the sun as a slate band, lowest toward the sides, with the pink Belt of Venus
// on its edge. The gold draws back from that horizon at the same time, and the
// shadow is the colour it leaves behind. sunset_fog.glsl mirrors it for the land.
vec3 applyEarthShadow(vec3 direction, vec3 sky) {
    if (twilightBand <= 0.0) return sky;
    vec2 flatDir = direction.xz / max(length(direction.xz), 1e-4);
    vec2 flatSun = sunDirection.xz / max(length(sunDirection.xz), 1e-4);
    float opposite = max(-dot(flatDir, flatSun), 0.0);
    float edge = earthShadowHeight * opposite;
    // The edge is soft: the sun is a disc, not a point, and the air blurs it further.
    float inShadow = (1.0 - smoothstep(edge - 0.06, edge + 0.06, direction.y)) * smoothstep(0.0, 0.3, opposite);
    float belt = smoothstep(edge - 0.02, edge + 0.06, direction.y) * (1.0 - smoothstep(edge + 0.08, edge + 0.24, direction.y))
            * smoothstep(0.2, 0.7, opposite);
    vec3 pink = BELT_PINK * max(sky.r, max(sky.g, sky.b));
    sky = mix(sky, pink, belt * twilightBand * 0.55);
    return mix(sky, earthShadowColor, inShadow * twilightBand * 0.85);
}

vec3 getSunContribution(vec3 direction) {
    float sunDot = dot(direction, sunDirection);

    float sunRadius = 0.98;
    float sunIntensity = smoothstep(sunRadius - 0.01, sunRadius + 0.01, sunDot);
    vec3 discColor = SUN_DISC * sunColor;

    float glowRadius = 0.25;
    float glowIntensity = exp(-pow((1.0 - sunDot) / glowRadius, 1.5)) * 0.4;
    vec3 glowColor = vec3(1.0, 0.8, 0.5) * sunColor;

    // Full until the sun reaches the sea's edge, then it fades as it sinks below it.
    float lit = smoothstep(seaHorizon - SUN_SINK, seaHorizon, sunDirection.y);
    return (discColor * sunIntensity + glowColor * glowIntensity) * lit;
}

// The moon over `sky`. Pixel art on a lit sphere: the 64-texel grid is snapped first, then
// each texel is lit from the real sun direction, so the phases (and which limb is lit)
// come out of the geometry, and the terminator steps in whole pixels.
vec3 applyMoon(vec3 direction, vec3 sky) {
    vec3 f = moonDirection;
    float cosAngle = dot(direction, f);
    // Halo: moonlight scattered in the air around the disc.
    float halo = exp(-(1.0 - cosAngle) / 0.004) * 0.10 + exp(-(1.0 - cosAngle) / 0.04) * 0.04;
    sky += MOON_HALO * halo * moonLight * (1.0 - daylight);

    if (cosAngle < cos(MOON_RADIUS * 1.5)) {
        return sky;
    }
    // A frame on the disc, fixed to the celestial pole the sun and moon wheel around,
    // as the real moon's is: it rises tilted one way and sets tilted the other. Its top
    // points away from the pole (toward the noon side of the sky), which keeps Mare
    // Crisium (texture right) toward the evening sun, the first sea a waxing moon
    // lights. The moon's path lies square to the pole, so the frame never degenerates.
    vec3 up = normalize(f * dot(f, celestialPole) - celestialPole);
    vec3 right = cross(f, up);
    float scale = tan(MOON_RADIUS);
    vec2 uv = vec2(dot(direction, right), dot(direction, up)) / cosAngle / scale; // -1..1 over the disc

    vec2 texel = floor((uv * 0.5 + 0.5) * MOON_TEXELS);
    if (any(lessThan(texel, vec2(0.0))) || any(greaterThanEqual(texel, vec2(MOON_TEXELS)))) {
        return sky;
    }
    vec4 albedo = texelFetch(moonTexture, ivec2(texel.x, MOON_TEXELS - 1.0 - texel.y), 0);
    if (albedo.a < 0.5) {
        return sky;
    }
    // The texel's point on the sphere, facing us.
    vec2 p = (texel + 0.5) / MOON_TEXELS * 2.0 - 1.0;
    float z = sqrt(max(1.0 - dot(p, p), 0.0));
    vec3 n = normalize(right * p.x + up * p.y - f * z);

    float ndl = dot(n, sunDirection);
    // Two quantized steps across the terminator, as a pixel artist would shade it.
    float shade = ndl <= 0.0 ? 0.0 : (ndl < 0.12 ? 0.55 : 1.0);
    // Earth's phase seen from the moon is the opposite of the moon's: full Earth at new moon.
    float earthPhase = 0.5 + 0.5 * dot(f, sunDirection);
    vec3 lit = albedo.rgb * MOON_INTENSITY * shade;
    vec3 dark = albedo.rgb * EARTHSHINE * earthPhase;

    // The sky's light is scattered in the air between us and the moon, so it adds
    // on top: the dark face is the sky plus a faint earthshine (the "old moon in the
    // new moon's arms"), and by day the lit face is pale against the blue.
    return sky + lit * mix(1.0, 0.35, daylight) + dark * (1.0 - daylight);
}

void main() {
    vec3 direction = normalize(viewDirection);

    vec3 finalColor = getTimedSkyColor(direction);
    finalColor = applySunset(direction, finalColor);
    finalColor = applyEarthShadow(direction, finalColor);
    finalColor = applyAfterglow(direction, finalColor);
    finalColor = applyMoon(direction, finalColor);

    // Atmospheric perspective with horizon softening. The pale haze is sunlit air:
    // it fades out with the daylight, or it would light the night horizon up.
    float distance = length(worldPos - cameraPos);
    float haze = exp(-distance * 0.00001);
    float horizonSoft = 1.0 - smoothstep(-0.1, 0.1, abs(direction.y));
    float hazeAmount = mix(0.1, 0.3, horizonSoft);
    vec3 hazeColor = mix(horizonColor, vec3(0.8, 0.85, 0.9), daylight);
    finalColor = mix(hazeColor, finalColor, haze * (1.0 - hazeAmount));
    // The sun goes over the haze: the horizon haze would wash it out before it set.
    finalColor += getSunContribution(direction);

    FragColor = vec4(finalColor + ditherTpdf(gl_FragCoord.xy), 1.0);
}
