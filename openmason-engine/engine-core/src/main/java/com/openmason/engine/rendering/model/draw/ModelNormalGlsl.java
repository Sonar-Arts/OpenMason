package com.openmason.engine.rendering.model.draw;

/**
 * GLSL of model normals (OpenMason #169), shared by every lit model shader (the engine's {@link ModelRenderer} and
 * hosts' own entity shaders).
 *
 * <p>Per vertex, attribute {@link #NORMAL_LOCATION} holds an octahedral snorm16 pair (an INTEGER attribute,
 * {@code ivec2}; {@code model_shading.h}'s {@code octDecode}). The pair {@link #FLAT} {@code (-32768, -32768)}, which
 * the encoder never writes, marks a FLAT-shaded corner: such corners are lit with the exact face normal of the drawn
 * (posed, skinned) triangle from screen-space derivatives, as every model was before #169 - so flat faces stay exactly
 * flat even when skinning bends them (Blender recomputes normals after deforming, too). A mesh without a normal buffer
 * ({@code u_hasNormals} false) is flat everywhere.
 *
 * <p>Vertex stage: splice {@link #VERTEX} (after {@link ModelSkinGlsl#VERTEX}) and write the two varyings
 * {@code vNormal} / {@code vSmooth}:
 * <pre>
 *   mat4 skin = skinMatrix();
 *   vec4 worldPos = model * skinPosition(skin, aPos);
 *   vSmooth = modelNormalSmooth();
 *   vNormal = modelWorldNormal(model, skin);
 * </pre>
 * Fragment stage: splice {@link #FRAGMENT} and light with {@code modelShadingNormal(vNormal, vSmooth, worldPos)}
 * (normalized, on the side of the drawn triangle that faces the viewer; double-sided as before).
 */
public final class ModelNormalGlsl {

    private ModelNormalGlsl() {
    }

    /** Vertex attribute location of the octahedral normal (2 x int16, integer attribute). */
    public static final int NORMAL_LOCATION = 4;

    /** The value of both components of a flat-shaded corner's normal (never written by the oct encoder). */
    public static final short FLAT = Short.MIN_VALUE;

    /** Vertex stage: attribute, {@code u_hasNormals}, {@code octDecode}, {@code modelNormalSmooth}, {@code modelWorldNormal}. */
    public static final String VERTEX = """
            layout (location = 4) in ivec2 aNormalOct;
            uniform bool u_hasNormals;
            out vec3 vNormal;
            out float vSmooth;

            vec3 octDecode(ivec2 o) {
                vec2 f = max(vec2(o) / 32767.0, vec2(-1.0));
                vec3 n = vec3(f, 1.0 - abs(f.x) - abs(f.y));
                float t = max(-n.z, 0.0);
                n.x += n.x >= 0.0 ? -t : t;
                n.y += n.y >= 0.0 ? -t : t;
                return normalize(n);
            }

            // 1 when this corner carries its own (smooth or custom) normal, 0 = flat (face normal).
            float modelNormalSmooth() {
                return (u_hasNormals && !(aNormalOct.x == -32768 && aNormalOct.y == -32768)) ? 1.0 : 0.0;
            }

            // The corner normal in world space: skinned by the blended matrix, then the normal matrix of `model`
            // (inverse transpose: part matrices may scale non-uniformly). Zero for a flat corner.
            vec3 modelWorldNormal(mat4 model, mat4 skin) {
                if (modelNormalSmooth() < 0.5) return vec3(0.0);
                vec3 n = skinNormal(skin, octDecode(aNormalOct));
                return transpose(inverse(mat3(model))) * n;
            }
            """;

    /** Fragment stage: the varyings and {@code modelShadingNormal(vNormal, vSmooth, worldPos)}. */
    public static final String FRAGMENT = """
            in vec3 vNormal;
            in float vSmooth;

            // The lighting normal: the interpolated corner normal on smooth corners, the drawn triangle's face normal
            // on flat ones; always on the side of the triangle the viewer sees (models draw double-sided).
            vec3 modelShadingNormal(vec3 normal, float smoothShade, vec3 worldPos) {
                vec3 face = cross(dFdx(worldPos), dFdy(worldPos));
                if (smoothShade < 0.5) return normalize(face);
                vec3 n = normalize(normal);
                return dot(n, face) < 0.0 ? -n : n;
            }
            """;
}
