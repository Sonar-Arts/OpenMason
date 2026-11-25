// The atmosphere shared by the LUT passes and the sky: its media, its geometry and
// the LUTs' parametrisations. After Hillaire 2020, "A Scalable and Production Ready
// Sky and Atmosphere Rendering Technique" (EGSR), and its Unreal Engine reference.
//
// Distances are kilometres; the planet's centre is the origin, "up" is +y. Radiance
// is relative to the light's illuminance: a light of illuminance 1 at the top of the
// atmosphere. AtmosphereSettings.applyTo sets the uniforms.

uniform float uBottomRadius;        // planet radius
uniform float uTopRadius;           // top of the atmosphere
uniform vec3 uRayleighScattering;   // at the ground, per km
uniform float uRayleighScaleHeight;
uniform vec3 uMieScattering;
uniform vec3 uMieAbsorption;
uniform float uMieScaleHeight;
uniform float uMieG;                // Cornette-Shanks asymmetry
uniform vec3 uOzoneAbsorption;      // at the tent's peak, per km
uniform float uOzoneCenter;         // the tent's centre altitude
uniform float uOzoneWidth;          // its full width
uniform vec3 uGroundAlbedo;

const float PI = 3.14159265358979;

// ---------------------------------------------------------------------------
// Media
// ---------------------------------------------------------------------------

struct Medium {
    vec3 rayleighScattering;
    vec3 mieScattering;
    vec3 extinction;
};

// The medium `altitude` km above the ground: Rayleigh and Mie fall off
// exponentially; ozone is a tent round uOzoneCenter.
Medium sampleMedium(float altitude) {
    float rayleigh = exp(-altitude / uRayleighScaleHeight);
    float mie = exp(-altitude / uMieScaleHeight);
    float ozone = max(0.0, 1.0 - abs(altitude - uOzoneCenter) / (0.5 * uOzoneWidth));
    Medium m;
    m.rayleighScattering = uRayleighScattering * rayleigh;
    m.mieScattering = uMieScattering * mie;
    m.extinction = m.rayleighScattering + (uMieScattering + uMieAbsorption) * mie
            + uOzoneAbsorption * ozone;
    return m;
}

float rayleighPhase(float cosTheta) {
    return 3.0 / (16.0 * PI) * (1.0 + cosTheta * cosTheta);
}

// Cornette-Shanks: Henyey-Greenstein with Rayleigh's (1 + cos^2) lobe, normalised.
float miePhase(float cosTheta) {
    float g = uMieG;
    float k = 3.0 / (8.0 * PI) * (1.0 - g * g) / (2.0 + g * g);
    return k * (1.0 + cosTheta * cosTheta) / pow(1.0 + g * g - 2.0 * g * cosTheta, 1.5);
}

// ---------------------------------------------------------------------------
// Geometry
// ---------------------------------------------------------------------------

// The nearest non-negative distance along the ray to a sphere of `radius` round the
// origin, or -1 if it misses (or lies wholly behind).
float raySphere(vec3 origin, vec3 dir, float radius) {
    float b = dot(origin, dir);
    float c = dot(origin, origin) - radius * radius;
    float disc = b * b - c;
    if (disc < 0.0) return -1.0;
    float s = sqrt(disc);
    float near = -b - s;
    float far = -b + s;
    if (near >= 0.0) return near;
    if (far >= 0.0) return far;
    return -1.0;
}

// ---------------------------------------------------------------------------
// LUT parametrisations
// ---------------------------------------------------------------------------

// Keeps a LUT's texel centres on the parameter range's ends, so the ends are exact.
vec2 unitToSubUv(vec2 uv, vec2 size) {
    return (uv + 0.5 / size) * (size / (size + 1.0));
}

vec2 subUvToUnit(vec2 uv, vec2 size) {
    return (uv - 0.5 / size) * (size / (size - 1.0));
}

// Transmittance LUT (Bruneton 2017): x is the distance to the top of the atmosphere
// between its least and most for the height, y the height (through the horizon distance).
void transmittanceUvToParams(vec2 uv, out float viewHeight, out float viewZenithCos) {
    float h = sqrt(uTopRadius * uTopRadius - uBottomRadius * uBottomRadius);
    float rho = h * uv.y;
    viewHeight = sqrt(rho * rho + uBottomRadius * uBottomRadius);
    float dMin = uTopRadius - viewHeight;
    float dMax = rho + h;
    float d = dMin + uv.x * (dMax - dMin);
    viewZenithCos = d == 0.0 ? 1.0 : (h * h - rho * rho - d * d) / (2.0 * viewHeight * d);
    viewZenithCos = clamp(viewZenithCos, -1.0, 1.0);
}

vec2 transmittanceParamsToUv(float viewHeight, float viewZenithCos) {
    float h = sqrt(max(0.0, uTopRadius * uTopRadius - uBottomRadius * uBottomRadius));
    float rho = sqrt(max(0.0, viewHeight * viewHeight - uBottomRadius * uBottomRadius));
    float disc = viewHeight * viewHeight * (viewZenithCos * viewZenithCos - 1.0) + uTopRadius * uTopRadius;
    float d = max(0.0, -viewHeight * viewZenithCos + sqrt(max(disc, 0.0)));
    float dMin = uTopRadius - viewHeight;
    float dMax = rho + h;
    return vec2((d - dMin) / (dMax - dMin), rho / h);
}

// Multiple-scattering LUT: x the light's zenith cosine, y the height.
const vec2 MULTISCATTER_LUT_SIZE = vec2(32.0, 32.0);

vec2 multiScatterParamsToUv(float viewHeight, float lightZenithCos) {
    vec2 uv = vec2(lightZenithCos * 0.5 + 0.5,
            (viewHeight - uBottomRadius) / (uTopRadius - uBottomRadius));
    return unitToSubUv(clamp(uv, 0.0, 1.0), MULTISCATTER_LUT_SIZE);
}

// Sky-view LUT: latitude-longitude round the viewer, the longitude measured from the
// light's azimuth (the sky is mirror-symmetric about it). Latitude is non-linear,
// with most texels near the horizon (the top half of v above it, the bottom below),
// longitude has most near the light.
const vec2 SKYVIEW_LUT_SIZE = vec2(192.0, 108.0);

void skyViewUvToParams(vec2 uv, float viewHeight, out float viewZenithCos, out float lightViewCos) {
    uv = subUvToUnit(uv, SKYVIEW_LUT_SIZE);
    float horizonCos = sqrt(max(0.0, viewHeight * viewHeight - uBottomRadius * uBottomRadius)) / viewHeight;
    float beta = acos(horizonCos);
    float zenithHorizonAngle = PI - beta;
    if (uv.y < 0.5) {
        float coord = 1.0 - 2.0 * uv.y;
        coord = 1.0 - coord * coord;
        viewZenithCos = cos(zenithHorizonAngle * coord);
    } else {
        float coord = uv.y * 2.0 - 1.0;
        viewZenithCos = cos(zenithHorizonAngle + beta * coord * coord);
    }
    float coord = uv.x * uv.x;
    lightViewCos = -(coord * 2.0 - 1.0);
}

vec2 skyViewParamsToUv(bool hitsGround, float viewZenithCos, float lightViewCos, float viewHeight) {
    float horizonCos = sqrt(max(0.0, viewHeight * viewHeight - uBottomRadius * uBottomRadius)) / viewHeight;
    float beta = acos(horizonCos);
    float zenithHorizonAngle = PI - beta;
    float viewZenith = acos(clamp(viewZenithCos, -1.0, 1.0));
    vec2 uv;
    if (!hitsGround) {
        float coord = 1.0 - clamp(viewZenith / zenithHorizonAngle, 0.0, 1.0);
        uv.y = (1.0 - sqrt(coord)) * 0.5;
    } else {
        float coord = clamp((viewZenith - zenithHorizonAngle) / beta, 0.0, 1.0);
        uv.y = sqrt(coord) * 0.5 + 0.5;
    }
    uv.x = sqrt(clamp(-lightViewCos * 0.5 + 0.5, 0.0, 1.0));
    return unitToSubUv(uv, SKYVIEW_LUT_SIZE);
}
