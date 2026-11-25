#version 330 core

// Multiple-scattering LUT (32x32), Hillaire 2020 section 5.5: at each height and light
// zenith cosine, the second-order light L2 arriving from all directions (isotropic
// phase, ground included) and the fraction f_ms of light re-scattered there. The
// infinite series of higher orders sums to L2 / (1 - f_ms).

in vec2 vUv;
out vec4 fragColor;

#include "/shaders/sky/atmosphere/atmosphere.glsl"
#include "/shaders/sky/atmosphere/scattering.glsl"

const int SQRT_DIRECTIONS = 8;
const int STEPS = 20;

void main() {
    vec2 uv = subUvToUnit(vUv, MULTISCATTER_LUT_SIZE);
    float lightZenithCos = uv.x * 2.0 - 1.0;
    float height = uBottomRadius + clamp(uv.y + 1e-4, 0.0, 1.0) * (uTopRadius - uBottomRadius - 0.01);
    vec3 origin = vec3(0.0, height, 0.0);
    vec3 lightDir = vec3(sqrt(max(0.0, 1.0 - lightZenithCos * lightZenithCos)), lightZenithCos, 0.0);

    vec3 secondOrder = vec3(0.0);
    vec3 transfer = vec3(0.0);
    for (int i = 0; i < SQRT_DIRECTIONS; i++) {
        for (int j = 0; j < SQRT_DIRECTIONS; j++) {
            // A uniform grid over the sphere.
            float theta = 2.0 * PI * (float(i) + 0.5) / float(SQRT_DIRECTIONS);
            float phi = acos(1.0 - 2.0 * (float(j) + 0.5) / float(SQRT_DIRECTIONS));
            vec3 dir = vec3(cos(theta) * sin(phi), cos(phi), sin(theta) * sin(phi));
            Scattering s = integrateScattering(origin, dir, lightDir, STEPS, true, false, true);
            secondOrder += s.luminance;
            transfer += s.multiScatterAs1;
        }
    }
    // Each direction stands for 4pi/N of the sphere, under the isotropic phase 1/(4pi).
    float n = float(SQRT_DIRECTIONS * SQRT_DIRECTIONS);
    secondOrder /= n;
    transfer /= n;
    fragColor = vec4(secondOrder / (1.0 - transfer), 1.0);
}
