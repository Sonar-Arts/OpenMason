// Single scattering of one light along a view ray, with Hillaire's multiple-scattering
// term, integrated per step analytically (each step's in-scattering is exact for a
// constant medium over the step). Needs atmosphere.glsl.

uniform sampler2D uTransmittanceLut;
uniform sampler2D uMultiScatterLut; // read only when withMultiScatter

struct Scattering {
    vec3 luminance;        // light scattered toward the viewer, per unit illuminance
    vec3 multiScatterAs1;  // the same for unit in-scattered light (Hillaire's f_ms integrand)
    vec3 transmittance;    // along the whole ray
};

// Transmittance from a point `height` km from the centre to the top of the atmosphere
// toward a direction whose zenith cosine there is `zenithCos`.
vec3 transmittanceToTop(float height, float zenithCos) {
    return texture(uTransmittanceLut, transmittanceParamsToUv(height, zenithCos)).rgb;
}

// Whether the planet stands between a point and the light. The point is nudged up so a
// ray starting on the ground does not hit the ground it starts from.
float planetShadow(vec3 p, vec3 lightDir) {
    vec3 up = normalize(p);
    return raySphere(p + up * 1e-3, lightDir, uBottomRadius) >= 0.0 ? 0.0 : 1.0;
}

Scattering integrateScattering(vec3 origin, vec3 dir, vec3 lightDir, int sampleCount,
                               bool uniformPhase, bool withMultiScatter, bool withGround) {
    Scattering r;
    r.luminance = vec3(0.0);
    r.multiScatterAs1 = vec3(0.0);
    r.transmittance = vec3(1.0);

    float tBottom = raySphere(origin, dir, uBottomRadius);
    float tTop = raySphere(origin, dir, uTopRadius);
    bool hitsGround = tBottom >= 0.0;
    float tMax;
    if (!hitsGround) {
        if (tTop < 0.0) return r; // outside the atmosphere, looking away from it
        tMax = tTop;
    } else {
        tMax = tTop > 0.0 ? min(tTop, tBottom) : tBottom;
    }

    float cosTheta = dot(dir, lightDir);
    const float ISOTROPIC = 1.0 / (4.0 * PI);
    float phaseR = uniformPhase ? ISOTROPIC : rayleighPhase(cosTheta);
    float phaseM = uniformPhase ? ISOTROPIC : miePhase(cosTheta);

    float dt = tMax / float(sampleCount);
    vec3 throughput = vec3(1.0);
    for (int i = 0; i < sampleCount; i++) {
        // Hillaire's 0.3 offset into each step.
        vec3 p = origin + dir * ((float(i) + 0.3) * dt);
        float height = length(p);
        vec3 up = p / height;
        Medium m = sampleMedium(height - uBottomRadius);
        vec3 stepTransmittance = exp(-m.extinction * dt);

        float lightZenithCos = dot(lightDir, up);
        vec3 lightTransmittance = transmittanceToTop(height, lightZenithCos) * planetShadow(p, lightDir);
        vec3 scattering = m.rayleighScattering + m.mieScattering;
        vec3 multiScatter = withMultiScatter
                ? texture(uMultiScatterLut, multiScatterParamsToUv(height, lightZenithCos)).rgb
                : vec3(0.0);

        vec3 source = lightTransmittance * (m.rayleighScattering * phaseR + m.mieScattering * phaseM)
                + multiScatter * scattering;
        vec3 extinction = max(m.extinction, vec3(1e-9));
        r.luminance += throughput * (source - source * stepTransmittance) / extinction;
        r.multiScatterAs1 += throughput * (scattering - scattering * stepTransmittance) / extinction;
        throughput *= stepTransmittance;
    }
    r.transmittance = throughput;

    if (withGround && hitsGround && tBottom <= tMax) {
        // Sunlit ground, Lambertian, seen through the air in front of it.
        vec3 p = origin + dir * tBottom;
        float height = length(p);
        float lightZenithCos = dot(lightDir, p / height);
        r.luminance += transmittanceToTop(height, lightZenithCos) * throughput
                * max(lightZenithCos, 0.0) * uGroundAlbedo / PI;
    }
    return r;
}
