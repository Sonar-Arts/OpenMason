package com.openmason.engine.rendering.model.draw;

/**
 * GLSL of GPU skinning, shared by every model vertex shader (the engine's {@link ModelRenderer} passes
 * and hosts' own entity shaders). Rig ABI 3 (OpenMason #170): any number of influences per vertex.
 * <ul>
 *   <li>{@code aSkinRange} ({@link #RANGE_LOCATION}, {@code uvec2}, an INTEGER attribute): the vertex's
 *       {@code (offset, count)} into the influence list ({@link com.openmason.engine.rig.PackedSkin#ranges}).</li>
 *   <li>{@code u_skinInfluences}: a {@code GL_RG16UI} buffer texture on {@link #INFLUENCES_UNIT}, one texel
 *       {@code (joint, unorm16 weight)} per influence ({@link com.openmason.engine.rig.PackedSkin#influences}).</li>
 *   <li>{@code u_jointPalette}: the joint palette ({@link JointPaletteTexture}, 4 RGBA32F texels = one
 *       column-major matrix per joint) on {@link JointPaletteTexture#DEFAULT_UNIT}.</li>
 * </ul>
 * The blended matrix {@code sum_k (q_k / 65535) K(joint_k)} uses exactly the weights {@code cr_skin_deform} uses on
 * the CPU. With {@code u_skinned} false (or a vertex with no influences) the matrix is the identity, so a host can
 * keep drawing rigid parts with a per-part {@code model} matrix. Attribute location 3 (the ABI 2 weights) carries the
 * morph ranges since #171 ({@link ModelMorphGlsl}).
 *
 * <p>Splice {@link #VERTEX} after the {@code #version} line. Positions: {@code vec4 local = skinPosition(aPos);}.
 * A shader that needs both the position and the normal blends once: {@code mat4 s = skinMatrix();} and uses
 * {@code skinPosition(s, aPos)} / {@code skinNormal(s, n)} (normals ride the same blended matrix, its {@code mat3}).
 * Morph targets (shape keys, OpenMason #171) displace the rest position and normal BEFORE skinning: {@link #VERTEX}
 * starts with {@link ModelMorphGlsl#VERTEX}, so every shader splicing it has {@code morphPosition} / {@code morphNormal}
 * and draws {@code skinPosition(s, morphPosition(aPos))} (a no-op unless the mesh has morphs and {@code u_morphed}).
 */
public final class ModelSkinGlsl {

    private ModelSkinGlsl() {
    }

    /** Vertex attribute location of the per-vertex {@code (offset, count)} uint32 pair. */
    public static final int RANGE_LOCATION = 2;

    /**
     * Texture unit of the influence buffer texture: the first unit above the 0..31 range the engine and
     * Stonebreak bind (palette 31, appearance mask 30, Stonebreak's world passes up to 30); GL 3.3 guarantees
     * 48 combined units. Hosts may bind elsewhere and point {@code u_skinInfluences} there.
     */
    public static final int INFLUENCES_UNIT = 32;

    /** Attributes, uniforms, {@code skinMatrix()}, {@code skinPosition(...)} and {@code skinNormal(...)}. */
    public static final String VERTEX = ModelMorphGlsl.VERTEX + """
            layout (location = 2) in uvec2 aSkinRange;
            uniform samplerBuffer u_jointPalette;
            uniform usamplerBuffer u_skinInfluences;
            uniform bool u_skinned;

            mat4 skinJointMatrix(uint joint) {
                int base = int(joint) * 4;
                return mat4(texelFetch(u_jointPalette, base),
                            texelFetch(u_jointPalette, base + 1),
                            texelFetch(u_jointPalette, base + 2),
                            texelFetch(u_jointPalette, base + 3));
            }

            // The vertex's blended skin matrix (identity when unskinned or without influences).
            mat4 skinMatrix() {
                if (!u_skinned || aSkinRange.y == 0u) return mat4(1.0);
                int first = int(aSkinRange.x);
                int count = int(aSkinRange.y);
                mat4 m = mat4(0.0);
                for (int k = 0; k < count; k++) {
                    uvec2 inf = texelFetch(u_skinInfluences, first + k).xy;
                    m += (float(inf.y) / 65535.0) * skinJointMatrix(inf.x);
                }
                return m;
            }

            vec4 skinPosition(mat4 skin, vec3 position) {
                return vec4((skin * vec4(position, 1.0)).xyz, 1.0);
            }

            vec4 skinPosition(vec3 position) {
                if (!u_skinned) return vec4(position, 1.0);
                return skinPosition(skinMatrix(), position);
            }

            // Not normalized: the caller applies its normal matrix and normalizes.
            vec3 skinNormal(mat4 skin, vec3 normal) {
                return mat3(skin) * normal;
            }

            vec3 skinNormal(vec3 normal) {
                if (!u_skinned) return normal;
                return skinNormal(skinMatrix(), normal);
            }
            """;
}
