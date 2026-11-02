package com.openmason.engine.rendering.model.draw;

import com.openmason.engine.rendering.shadow.ShadowGlsl;

/**
 * GLSL of the {@link ModelRenderer} passes, assembled as strings so the lit
 * fragment shader can splice in a {@link ModelShaderExtension}. GL-free: tests
 * inspect the assembled source without a context.
 */
public final class ModelShaderSource {

    private ModelShaderSource() {}

    /** Vertex stage of the lit pass. */
    public static final String LIT_VERTEX = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec2 aTexCoord;

            uniform mat4 model;
            uniform mat4 view;
            uniform mat4 projection;

            out vec2 TexCoord;
            out vec3 FragWorldPos;
            out float ViewDepth;

            void main() {
                vec4 worldPos = model * vec4(aPos, 1.0);
                FragWorldPos = worldPos.xyz;
                ViewDepth = -(view * worldPos).z;
                gl_Position = projection * view * worldPos;
                TexCoord = aTexCoord;
            }
            """;

    private static final String LIT_FRAGMENT_HEAD = """
            #version 330 core
            out vec4 FragColor;

            in vec2 TexCoord;
            in vec3 FragWorldPos;
            in float ViewDepth;

            uniform sampler2D textureSampler;
            uniform bool u_useTexture;
            uniform vec3 u_baseColor;
            uniform vec3 u_cameraPos;
            uniform float u_fogDensity;
            uniform vec3 u_fogColor;
            uniform float u_ambientLight;
            uniform vec3 u_keyDirection;
            uniform float u_skyLight;
            uniform float u_selfGlow;
            """;

    private static final String DEFAULT_EXTENSION = """
            vec3 modelExtraLight(vec3 worldPos, vec3 normal, float sunVisibility) {
                return vec3(0.0);
            }
            vec3 modelApplyExtraLight(vec3 lit, vec3 albedo, vec3 extra) {
                return min(lit + albedo * extra, max(lit, albedo));
            }
            """;

    private static final String LIT_FRAGMENT_MAIN = """
            void main() {
                vec4 texColor = u_useTexture ? texture(textureSampler, TexCoord) : vec4(u_baseColor, 1.0);
                if (texColor.a < 0.01) discard;

                // Model meshes carry no normals: derive the flat face normal from
                // screen-space derivatives. For visible fragments this is the
                // outward (viewer-side) normal, which is what lighting needs.
                vec3 normal = normalize(cross(dFdx(FragWorldPos), dFdy(FragWorldPos)));
                vec3 keyDir = normalize(u_keyDirection);
                float diff = max(dot(normal, keyDir), 0.0);
                float shadowFactor = csmShadowFactor(FragWorldPos, normal, ViewDepth);

                // Ambient + key diffuse, then the sampled sky light at the model so
                // it darkens in caves.
                float brightness = u_ambientLight * (0.5 + 0.55 * diff * shadowFactor);
                brightness *= mix(0.3, 1.0, u_skyLight);
                vec3 lit = texColor.rgb * min(brightness, 1.0);
                // Host extra light (point lights) and the model's own glow on top.
                vec3 extra = modelExtraLight(FragWorldPos, normal, shadowFactor) + vec3(u_selfGlow);
                lit = modelApplyExtraLight(lit, texColor.rgb, extra);

                if (u_fogDensity > 0.0) {
                    float dist = length(FragWorldPos - u_cameraPos);
                    float fogFactor = clamp(exp(-u_fogDensity * dist), 0.0, 1.0);
                    FragColor = mix(vec4(u_fogColor, texColor.a), vec4(lit, texColor.a), fogFactor);
                } else {
                    FragColor = vec4(lit, texColor.a);
                }
            }
            """;

    /** Fragment stage of the lit pass with {@code extension} spliced in. */
    public static String litFragment(ModelShaderExtension extension) {
        return LIT_FRAGMENT_HEAD
                + ShadowGlsl.UNIFORMS
                + ShadowGlsl.FUNCTIONS
                + (extension.hasGlsl() ? extension.glsl() : DEFAULT_EXTENSION)
                + "\n"
                + LIT_FRAGMENT_MAIN;
    }

    /** Vertex stage of the flat-colour and wireframe passes. */
    public static final String FLAT_VERTEX = """
            #version 330 core
            layout (location = 0) in vec3 aPos;

            uniform mat4 model;
            uniform mat4 view;
            uniform mat4 projection;

            void main() {
                gl_Position = projection * view * model * vec4(aPos, 1.0);
            }
            """;

    /** Fragment stage of the flat-colour and wireframe passes. */
    public static final String FLAT_FRAGMENT = """
            #version 330 core
            out vec4 FragColor;

            uniform vec4 color;

            void main() {
                FragColor = color;
            }
            """;
}
