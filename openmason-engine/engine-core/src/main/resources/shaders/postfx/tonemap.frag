#version 330 core

// The final pass: the HDR scene to the display. Exposure, the night (Purkinje) grade,
// then the fitted ACES curve.
//
// The scene buffer holds the colour the scene shaders work in: display-referred, gamma
// 2.2, as it always has been, but now unclamped, so light brighter than white survives
// to be rolled off here instead of clipping. That keeps every existing blend exactly
// as it was. This pass decodes it to linear light, exposes it, grades it, tone maps it
// and encodes it for the display.

in vec2 vUv;

out vec4 fragColor;

uniform sampler2D sceneColor;
uniform float exposure;        // multiplier on linear light before the curve
uniform float nightAdaptation; // 0..1: how dark-adapted the eye is

#include "/shaders/postfx/tone_curve.glsl"

// The Purkinje shift. In dim light the rods take over from the cones: colour drains
// away and what is left reads blue, because rods are most sensitive at ~507 nm.
// ROD_TINT is the hue rod vision is drawn in (linear sRGB, unit luminance). Jensen et
// al. 2000 ("A Physically-Based Night Sky Model") use chromaticity (0.25, 0.25), a
// cyan-blue; this is a deeper violet-blue, fitted together with ROD_GAIN and
// PURKINJE_STRENGTH so the graded night matches the indigo night palette Stonebreak
// painted before the grade existed.
const vec3 ROD_TINT = vec3(0.510, 0.793, 4.497);
// Rods see the scotopic luminance, which for bluish light runs several times the
// photopic; this scales it back to the photopic level of the scene it replaces.
const float ROD_GAIN = 0.278;
// Linear luminance, before exposure, below which the rods hold the image fully
// (moonlit ground, the night sky), and above which the cones do (torchlight). Rods
// respond to the light itself, not to the exposed image, so this is measured first.
const float ROD_FULL = 0.004;
const float CONE_FULL = 0.08;
// How far a fully dark-adapted eye goes toward rod vision: a light touch with a
// deep hue, so a moonlit scene keeps most of its colour and leans blue.
const float PURKINJE_STRENGTH = 0.153;

// Linear sRGB to CIE XYZ (D65).
const mat3 SRGB_TO_XYZ = mat3(
    0.4124, 0.2126, 0.0193,
    0.3576, 0.7152, 0.1192,
    0.1805, 0.0722, 0.9505);

// Scotopic (rod) luminance from XYZ, after Larson et al. as Jensen et al. use it.
float scotopic(vec3 xyz) {
    return xyz.y * max(1.33 * (1.0 + (xyz.y + xyz.z) / max(xyz.x, 1e-6)) - 1.68, 0.0);
}

// The low-luminance grade: blends dim light toward rod vision as the eye adapts to the dark.
vec3 purkinje(vec3 linear, float absoluteLuminance) {
    if (nightAdaptation <= 0.0) return linear;
    float rods = nightAdaptation * PURKINJE_STRENGTH
            * (1.0 - smoothstep(ROD_FULL, CONE_FULL, absoluteLuminance));
    vec3 xyz = SRGB_TO_XYZ * linear;
    vec3 rodVision = ROD_TINT * scotopic(xyz) * ROD_GAIN;
    return mix(linear, rodVision, rods);
}

// Dave Hoskins' sine-free hash, as sky.frag uses.
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    vec3 working = max(texture(sceneColor, vUv).rgb, vec3(0.0));
    vec3 linear = pow(working, vec3(TONE_WORKING_GAMMA));

    float luminance = dot(linear, vec3(0.2126, 0.7152, 0.0722));
    linear = purkinje(linear, luminance);

    vec3 display = pow(acesFitted(linear * exposure), vec3(1.0 / TONE_WORKING_GAMMA));

    // Triangular dither of one 8-bit step: the display buffer is 8-bit, and a dark
    // gradient through the curve's toe would otherwise band.
    float dither = (hash12(gl_FragCoord.xy) + hash12(gl_FragCoord.xy + 17.0) - 1.0) / 255.0;
    fragColor = vec4(display + dither, 1.0);
}
