#version 330 core

// Transmittance LUT (256x64): the light that survives from a point to the top of the
// atmosphere, by height and zenith cosine (atmosphere.glsl's parametrisation).

in vec2 vUv;
out vec4 fragColor;

#include "/shaders/sky/atmosphere/atmosphere.glsl"

const int STEPS = 40;

void main() {
    float viewHeight, viewZenithCos;
    transmittanceUvToParams(vUv, viewHeight, viewZenithCos);
    vec3 origin = vec3(0.0, viewHeight, 0.0);
    vec3 dir = vec3(sqrt(max(0.0, 1.0 - viewZenithCos * viewZenithCos)), viewZenithCos, 0.0);
    float tMax = max(raySphere(origin, dir, uTopRadius), 0.0);
    float dt = tMax / float(STEPS);
    vec3 opticalDepth = vec3(0.0);
    for (int i = 0; i < STEPS; i++) {
        vec3 p = origin + dir * ((float(i) + 0.5) * dt);
        opticalDepth += sampleMedium(length(p) - uBottomRadius).extinction * dt;
    }
    fragColor = vec4(exp(-opticalDepth), 1.0);
}
