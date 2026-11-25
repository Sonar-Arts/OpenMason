#version 330 core

// Sky-view LUT (192x108): the sky's radiance round a viewer at uViewHeight, lit by one
// light (the sun or the moon) of unit illuminance at zenith cosine uLightZenithCos.
// Rebuilt every frame (atmosphere.glsl's latitude-longitude parametrisation).

in vec2 vUv;
out vec4 fragColor;

#include "/shaders/sky/atmosphere/atmosphere.glsl"
#include "/shaders/sky/atmosphere/scattering.glsl"

uniform float uViewHeight;
uniform float uLightZenithCos;

const int STEPS = 30;

void main() {
    float viewZenithCos, lightViewCos;
    skyViewUvToParams(vUv, uViewHeight, viewZenithCos, lightViewCos);
    vec3 origin = vec3(0.0, uViewHeight, 0.0);
    float viewZenithSin = sqrt(max(0.0, 1.0 - viewZenithCos * viewZenithCos));
    vec3 dir = vec3(viewZenithSin * lightViewCos, viewZenithCos,
            viewZenithSin * sqrt(max(0.0, 1.0 - lightViewCos * lightViewCos)));
    vec3 lightDir = vec3(sqrt(max(0.0, 1.0 - uLightZenithCos * uLightZenithCos)), uLightZenithCos, 0.0);
    Scattering s = integrateScattering(origin, dir, lightDir, STEPS, false, true, false);
    fragColor = vec4(s.luminance, 1.0);
}
