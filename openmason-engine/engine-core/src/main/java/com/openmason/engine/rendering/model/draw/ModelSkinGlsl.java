package com.openmason.engine.rendering.model.draw;

/**
 * GLSL of GPU skinning, shared by every model vertex shader (the engine's {@link ModelRenderer} passes
 * and hosts' own entity shaders): per-vertex joints/weights ({@link ModelGpuMesh} attributes 2 and 3)
 * blend the joint palette ({@link JointPaletteTexture}, 4 RGBA32F texels = one column-major matrix per
 * joint). With {@code u_skinned} false the position passes through unchanged, so a host can keep
 * drawing rigid parts with a per-part {@code model} matrix.
 *
 * <p>Splice {@link #VERTEX} after the {@code #version} line and use
 * {@code vec4 local = skinPosition(aPos);}.
 */
public final class ModelSkinGlsl {

    private ModelSkinGlsl() {
    }

    /** Vertex attribute location of the 4 x uint16 joint indices. */
    public static final int JOINTS_LOCATION = 2;
    /** Vertex attribute location of the 4 x unorm8 weights. */
    public static final int WEIGHTS_LOCATION = 3;

    /** Attributes, uniforms and {@code skinPosition(vec3)}. */
    public static final String VERTEX = """
            layout (location = 2) in uvec4 aJoints;
            layout (location = 3) in vec4 aWeights;
            uniform samplerBuffer u_jointPalette;
            uniform bool u_skinned;

            mat4 skinJointMatrix(uint joint) {
                int base = int(joint) * 4;
                return mat4(texelFetch(u_jointPalette, base),
                            texelFetch(u_jointPalette, base + 1),
                            texelFetch(u_jointPalette, base + 2),
                            texelFetch(u_jointPalette, base + 3));
            }

            vec4 skinPosition(vec3 position) {
                vec4 p = vec4(position, 1.0);
                if (!u_skinned) return p;
                vec4 r = aWeights.x * (skinJointMatrix(aJoints.x) * p);
                if (aWeights.y > 0.0) r += aWeights.y * (skinJointMatrix(aJoints.y) * p);
                if (aWeights.z > 0.0) r += aWeights.z * (skinJointMatrix(aJoints.z) * p);
                if (aWeights.w > 0.0) r += aWeights.w * (skinJointMatrix(aJoints.w) * p);
                return vec4(r.xyz, 1.0);
            }
            """;
}
