#version 330 core
// Premultiplied-alpha output: blend with glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA).

in vec2 v_local;
in vec4 v_color;
in float v_viewDepth;
in float v_softness;
flat in uint v_frame;

uniform sampler2DArray u_sprites; // one sprite / flipbook frame per layer, premultiplied, row 0 = image top
uniform sampler2D u_sceneDepth;   // opaque scene depth, viewport-sized, not the bound depth attachment
uniform int u_softEnabled;
uniform vec2 u_depthParams;       // projection m22, m32: view depth = m32 / (ndcZ + m22)

out vec4 fragColor;

void main() {
    vec4 shape;
    if (v_frame == 0xFFFFu) {
        // Soft disc: (1 - d²)² — full at the centre, smooth to zero at the inscribed circle, and a third
        // of the quad's area in coverage (a plain (1 - d)² leaves only a sixth and reads as a small dot).
        vec2 p = v_local * 2.0 - 1.0;
        float a = clamp(1.0 - dot(p, p), 0.0, 1.0);
        a *= a;
        shape = vec4(a);
    } else if (v_frame == 0xFFFEu) {
        shape = vec4(1.0);
    } else if (v_frame == 0xFFFDu) {
        // Missing sprite texture: a loud magenta/black checker, never a silent white square.
        ivec2 cell = ivec2(v_local * 4.0);
        shape = ((cell.x + cell.y) & 1) == 0 ? vec4(1.0, 0.0, 1.0, 1.0) : vec4(0.0, 0.0, 0.0, 1.0);
    } else {
        // Layers are uploaded top row first, so flip V; texels are already premultiplied.
        shape = texture(u_sprites, vec3(v_local.x, 1.0 - v_local.y, float(v_frame)));
    }
    vec4 c = v_color * shape;
    if (u_softEnabled == 1 && v_softness > 0.0) {
        // Fade out where the particle nears the scene behind it: no hard line where it cuts into geometry.
        float d = texelFetch(u_sceneDepth, ivec2(gl_FragCoord.xy), 0).r;
        float sceneDepth = u_depthParams.y / ((d * 2.0 - 1.0) + u_depthParams.x);
        c *= clamp((sceneDepth - v_viewDepth) / v_softness, 0.0, 1.0);
    }
    if (c.a < 0.002 && max(c.r, max(c.g, c.b)) < 0.002) {
        discard;
    }
    fragColor = c;
}
