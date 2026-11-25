// The final pass's tone curve (tonemap.frag) and its inverse, shared so that colours
// authored as what the screen should show (the sky's palette, the fog that fades into it)
// can be written as the scene values the tone map turns back into exactly those colours.
//
// The scene buffer holds the scene shaders' working colour: display-referred, gamma
// TONE_WORKING_GAMMA, unclamped. The final pass decodes it to linear light, multiplies by
// the exposure and applies Krzysztof Narkowicz's fit of ACES ("ACES Filmic Tone Mapping
// Curve", 2016), per channel, then encodes it again.

const float TONE_WORKING_GAMMA = 2.2;
// The brightest display value converted exactly: the curve only approaches 1, so a
// display white would need an unbounded scene value.
const float TONE_MAX_DISPLAY = 0.995;

// The fitted ACES curve, per channel, in linear light.
vec3 acesFitted(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

// Its inverse on [0, 1): the root of (2.51 - 2.43y)x^2 + (0.03 - 0.59y)x - 0.14y = 0.
vec3 acesFittedInverse(vec3 y) {
    vec3 a = 2.51 - 2.43 * y;
    vec3 b = 0.03 - 0.59 * y;
    vec3 c = -0.14 * y;
    return (-b + sqrt(b * b - 4.0 * a * c)) / (2.0 * a);
}

// What the final pass shows for scene colour `scene` (working gamma) at `exposure`,
// before its night grade and dither: [0, 1].
vec3 sceneToDisplay(vec3 scene, float exposure) {
    vec3 linear = pow(max(scene, vec3(0.0)), vec3(TONE_WORKING_GAMMA));
    return pow(acesFitted(linear * exposure), vec3(1.0 / TONE_WORKING_GAMMA));
}

// The scene colour (working gamma) the final pass shows as `display` at `exposure`,
// before its night grade: the inverse of sceneToDisplay, exact up to TONE_MAX_DISPLAY.
vec3 displayToScene(vec3 display, float exposure) {
    vec3 linear = pow(clamp(display, 0.0, TONE_MAX_DISPLAY), vec3(TONE_WORKING_GAMMA));
    return pow(acesFittedInverse(linear) / exposure, vec3(1.0 / TONE_WORKING_GAMMA));
}
