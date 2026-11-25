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
uniform float skyAngle;       // how far the sky has turned about it (radians; 0 with the sun on the meridian)
uniform float time;           // seconds, for the stars' twinkle
uniform float aerosol;        // haze relative to a clear day's: more at dusk, less at dawn
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
// The width of the sun's aureole (1 - cos of the angle from it) with a clear day's
// aerosol; SunsetFog.AUREOLE_WIDTH. Haze scatters forward in a lobe that widens
// with how much of it there is, so it scales with `aerosol`.
const float AUREOLE_WIDTH = 0.25;
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
// come up), deeper and redder than the gold, which it lies over. Hazier air
// spreads it higher (its strength, from SkyLight, already follows the aerosol).
vec3 applyAfterglow(vec3 direction, vec3 sky) {
    float strength = max(sunGlow.r, max(sunGlow.g, sunGlow.b));
    if (strength <= 0.0) return sky;
    vec2 flatDir = direction.xz / max(length(direction.xz), 1e-4);
    vec2 flatSun = sunDirection.xz / max(length(sunDirection.xz), 1e-4);
    float towardSun = pow(max(dot(flatDir, flatSun), 0.0), 4.0);
    float band = 1.0 - smoothstep(0.0, 0.3 * aerosol, abs(direction.y));
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

    float glowRadius = AUREOLE_WIDTH * aerosol;
    float glowIntensity = exp(-pow((1.0 - sunDot) / glowRadius, 1.5)) * 0.4;
    vec3 glowColor = vec3(1.0, 0.8, 0.5) * sunColor;

    // Full until the sun reaches the sea's edge, then it fades as it sinks below it.
    float lit = smoothstep(seaHorizon - SUN_SINK, seaHorizon, sunDirection.y);
    return (discColor * sunIntensity + glowColor * glowIntensity) * lit;
}


// ---------------------------------------------------------------------------
// The night sky: stars, the Milky Way and airglow, fixed to the celestial
// sphere, which turns about celestialPole by skyAngle with the sun and moon.
// ---------------------------------------------------------------------------

// Star cells per cube-face edge. A cell is ~0.56 degrees; each holds at most
// one star, kept in its middle half so it never crosses into a neighbour.
const float STAR_GRID = 160.0;
// Chance a cell at the middle of a face holds a star: ~9,000 over the sphere,
// about as many as the eye sees down to magnitude 6.5. Corner cells cover less
// sky and get proportionally fewer.
const float STAR_DENSITY = 0.06;
// The magnitudes stars are drawn from, brightest to faintest. Counts rise
// ~3x per magnitude (log N = 0.5 m), as in the real sky: many faint, few bright.
const float STAR_MAG_BRIGHT = -1.0;
const float STAR_MAG_FAINT = 6.5;
// How strongly the sky's own light drowns a star: visibility is
// flux / (flux + STAR_CONTRAST * sky luminance), luminance in linear light.
// First-magnitude stars come out in civil twilight, the faintest last.
const float STAR_CONTRAST = 8.0;
// Screen brightness from flux: compressed (flux^0.45, so magnitude 6.5 is a
// dim point and Sirius a bright one, ~28x apart rather than 1,600x).
const float STAR_GAIN = 1.6;
const float STAR_GAMMA = 0.45;
// Atmospheric extinction, magnitudes per air mass (V band, a clear night).
const float STAR_EXTINCTION = 0.25;
// Twinkle (scintillation): its depth per air mass, and the most it reaches.
const float TWINKLE_PER_AIRMASS = 0.06;
const float TWINKLE_MAX = 0.6;
// Star colours, cool (hot stars) to warm (cool stars); most fall between.
const vec3 STAR_COOL = vec3(0.72, 0.82, 1.0);
const vec3 STAR_WARM = vec3(1.0, 0.78, 0.58);
// The Milky Way: a band about the galactic pole, tilted 62.9 degrees from the
// celestial pole as the real one is, with the galactic centre brightest.
const float MILKY_WAY_TILT = 1.098;
const float MILKY_WAY_WIDTH = 0.14;    // sine of galactic latitude at 1/e
const vec3 MILKY_WAY_COLOR = vec3(0.044, 0.047, 0.060);
// Airglow: the upper air's own faint light (oxygen's green line, ~90 km up),
// seen brightest about 12 degrees up, where the line of sight runs longest
// through the glowing layer before the lower air dims it.
const float AIRGLOW_ALTITUDE = 0.21;   // radians, ~12 degrees
const vec3 AIRGLOW_COLOR = vec3(0.016, 0.022, 0.015);
// The faint diffuse light (Milky Way, airglow) is visible only on a dark sky:
// gone by this sky luminance (log10, linear light), full below the first.
const float DIFFUSE_DARK_LOG = -4.2;
const float DIFFUSE_GONE_LOG = -2.9;

vec3 hash33(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

// Value noise and a few octaves of it, for the Milky Way's clouds of stars.
float valueNoise(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float n000 = hash33(i).x, n100 = hash33(i + vec3(1, 0, 0)).x;
    float n010 = hash33(i + vec3(0, 1, 0)).x, n110 = hash33(i + vec3(1, 1, 0)).x;
    float n001 = hash33(i + vec3(0, 0, 1)).x, n101 = hash33(i + vec3(1, 0, 1)).x;
    float n011 = hash33(i + vec3(0, 1, 1)).x, n111 = hash33(i + vec3(1, 1, 1)).x;
    return mix(mix(mix(n000, n100, f.x), mix(n010, n110, f.x), f.y),
               mix(mix(n001, n101, f.x), mix(n011, n111, f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float sum = 0.0, amp = 0.5;
    for (int i = 0; i < 4; i++) {
        sum += amp * valueNoise(p);
        p *= 2.03;
        amp *= 0.5;
    }
    return sum;
}

// Kasten and Young's relative air mass at altitude `alt` radians (CloudSunlight).
float airMass(float alt) {
    return 1.0 / (sin(alt) + 0.50572 * pow(degrees(alt) + 6.07995, -1.6364));
}

// `v` turned by `angle` about unit axis `k` (Rodrigues).
vec3 rotateAbout(vec3 v, vec3 k, float angle) {
    float c = cos(angle), s = sin(angle);
    return v * c + cross(k, v) * s + k * dot(k, v) * (1.0 - c);
}

// The star in this direction's cell, if any: rgb = light. `c` is the direction
// on the celestial sphere, `pixel` the angle one pixel spans.
vec3 starAt(vec3 c, float pixel, float alt, float skyLum) {
    vec3 a = abs(c);
    vec3 axis; vec2 uv; float face;
    if (a.x >= a.y && a.x >= a.z) { face = c.x > 0.0 ? 0.0 : 1.0; uv = c.yz / a.x; axis = vec3(sign(c.x), 0.0, 0.0); }
    else if (a.y >= a.z)          { face = c.y > 0.0 ? 2.0 : 3.0; uv = c.xz / a.y; axis = vec3(0.0, sign(c.y), 0.0); }
    else                          { face = c.z > 0.0 ? 4.0 : 5.0; uv = c.xy / a.z; axis = vec3(0.0, 0.0, sign(c.z)); }
    vec2 cell = floor((uv * 0.5 + 0.5) * STAR_GRID);
    vec3 h = hash33(vec3(cell, face * 37.0 + 11.0));
    vec2 suv = (cell + 0.25 + 0.5 * h.yz) / STAR_GRID * 2.0 - 1.0;
    // Fewer stars in the small cells near a face's corners: the same density per solid angle.
    float solidAngle = pow(1.0 + dot(suv, suv), -1.5);
    if (h.x > STAR_DENSITY * solidAngle) return vec3(0.0);
    vec3 star = axis.x != 0.0 ? vec3(axis.x, suv) : (axis.y != 0.0 ? vec3(suv.x, axis.y, suv.y) : vec3(suv, axis.z));
    star = normalize(star);

    vec3 h2 = hash33(vec3(cell.yx, face * 53.0 + 5.0));
    // Magnitude: the cumulative count rises as 10^(0.5 m), inverted from a uniform draw.
    float span = pow(10.0, 0.5 * (STAR_MAG_FAINT - STAR_MAG_BRIGHT)) - 1.0;
    float mag = STAR_MAG_BRIGHT + 2.0 * log(1.0 + h2.x * span) / log(10.0);
    // Dimmed by the air it shines through, most near the horizon.
    float airmass = airMass(max(alt, 0.0));
    mag += STAR_EXTINCTION * (airmass - 1.0);
    float flux = pow(10.0, -0.4 * mag);
    float visible = flux / (flux + STAR_CONTRAST * skyLum);
    // Twinkle: deeper and faster near the horizon, where more air churns the light.
    float depth = min(TWINKLE_PER_AIRMASS * airmass, TWINKLE_MAX);
    float twinkle = 1.0 + depth * sin(time * (9.0 + 7.0 * h2.y) + 6.2832 * h2.z)
                                * sin(time * (3.1 + 2.0 * h2.z) + 6.2832 * h2.y);
    // A point a pixel or so wide, the brightest a touch wider.
    float radius = pixel * (0.7 + 0.5 * min(flux, 2.5));
    float d = length(cross(c, star));
    float spot = exp(-(d * d) / (radius * radius));
    vec3 tint = mix(STAR_COOL, STAR_WARM, smoothstep(0.15, 0.85, h2.y));
    return tint * min(STAR_GAIN * pow(flux, STAR_GAMMA), 1.5) * visible * twinkle * spot;
}

// The night sky over `sky`: stars wherever the sky is dark enough to show
// them, then the Milky Way and airglow on the darkest skies. `pixel` is the
// angle one pixel spans.
vec3 applyNight(vec3 direction, vec3 sky, float pixel) {
    if (direction.y <= 0.0 || daylight >= 1.0) return sky;
    // The sky's luminance in linear light: the palette is display-referred.
    float skyLum = dot(pow(max(sky, vec3(0.0)), vec3(2.2)), vec3(0.2126, 0.7152, 0.0722));
    float alt = asin(direction.y);
    // The direction on the celestial sphere: undo the sky's turn.
    vec3 c = rotateAbout(direction, celestialPole, -skyAngle);
    vec3 light = vec3(0.0);
    // The moon's disc hides the stars behind it.
    if (dot(direction, moonDirection) < cos(MOON_RADIUS)) {
        light += starAt(c, pixel, alt, skyLum);
    }
    float dark = (1.0 - smoothstep(DIFFUSE_DARK_LOG, DIFFUSE_GONE_LOG, log(max(skyLum, 1e-8)) / log(10.0)))
               * (1.0 - 0.7 * moonLight);
    if (dark > 0.0) {
        // Galactic coordinates: the galactic pole is the celestial pole tilted about a fixed line.
        vec3 tiltAxis = vec3(1.0, 0.0, 0.0); // square to the pole, which lies in the y-z plane
        vec3 galPole = rotateAbout(celestialPole, tiltAxis, MILKY_WAY_TILT);
        vec3 galCentre = normalize(cross(galPole, tiltAxis));
        float b = dot(c, galPole);
        float band = exp(-(b * b) / (MILKY_WAY_WIDTH * MILKY_WAY_WIDTH));
        float clouds = fbm(c * 7.0);
        // The dark rift: dust lanes down the band's middle.
        float rift = 1.0 - 0.55 * exp(-(b * b) / 0.0012) * smoothstep(0.35, 0.65, fbm(c * 13.0 + 4.0));
        float centre = 0.55 + 0.45 * max(dot(c, galCentre), 0.0);
        vec3 diffuse = MILKY_WAY_COLOR * band * (0.35 + 0.9 * clouds) * rift * centre * smoothstep(0.0, 0.15, alt);
        // Airglow: rises from the horizon to a peak ~12 degrees up, then falls slowly.
        float above = alt - AIRGLOW_ALTITUDE;
        diffuse += AIRGLOW_COLOR * exp(-(above * above) / (above < 0.0 ? 0.012 : 0.16));
        light += diffuse * dark;
    }
    return sky + light * (1.0 - daylight);
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
    // The angle one pixel spans, for the stars' size; here, in uniform control flow.
    float pixel = max(length(dFdx(direction)), length(dFdy(direction)));
    finalColor = applyNight(direction, finalColor, pixel);
    finalColor = applyMoon(direction, finalColor);

    // Atmospheric perspective with horizon softening. The pale haze is sunlit air:
    // it fades out with the daylight, or it would light the night horizon up. The
    // horizon is hazier in the evening's dusty air than in the morning's.
    float distance = length(worldPos - cameraPos);
    float haze = exp(-distance * 0.00001);
    float horizonSoft = 1.0 - smoothstep(-0.1, 0.1, abs(direction.y));
    float hazeAmount = mix(0.1, 0.3 * aerosol, horizonSoft);
    vec3 hazeColor = mix(horizonColor, vec3(0.8, 0.85, 0.9), daylight);
    finalColor = mix(hazeColor, finalColor, haze * (1.0 - hazeAmount));
    // The sun goes over the haze: the horizon haze would wash it out before it set.
    finalColor += getSunContribution(direction);

    FragColor = vec4(finalColor + ditherTpdf(gl_FragCoord.xy), 1.0);
}
