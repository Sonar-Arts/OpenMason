package com.openmason.engine.rendering.model.draw;

/**
 * GLSL of morph targets / shape keys (OpenMason #171, rig.h "Morph targets" GPU packing), applied to the REST
 * position and normal BEFORE skinning (Blender's modifier order). Spliced into every model vertex shader as part of
 * {@link ModelSkinGlsl#VERTEX}, so hosts that already splice the skin code get it too; a shader applies it with
 * {@code skinPosition(skin, morphPosition(aPos))} - and {@link ModelNormalGlsl}'s {@code modelWorldNormal} morphs the
 * corner normal itself.
 * <ul>
 *   <li>{@code aMorphRange} ({@link #RANGE_LOCATION}, {@code uvec2}, an INTEGER attribute): the vertex's
 *       {@code (offset, count)} into the records ({@code RigMorphs.Packed#ranges}); a mesh without morphs leaves the
 *       attribute disabled (the generic value's count is 0).</li>
 *   <li>{@code u_morphDeltas} ({@link #DELTAS_UNIT}): {@code GL_RGBA32F} buffer texture, per record
 *       {@code (dx, dy, dz, float(target))}.</li>
 *   <li>{@code u_morphNormalDeltas} ({@link #NORMALS_UNIT}): {@code GL_RGBA32F} buffer texture, per record
 *       {@code (nx, ny, nz, 0)}; read only while {@code u_morphNormals}.</li>
 *   <li>{@code u_morphWeights} ({@link #WEIGHTS_UNIT}): {@code GL_R32F} buffer texture of one CLAMPED weight per
 *       target - the only per-frame upload ({@code ModelGpuMesh.uploadMorphWeights}).</li>
 * </ul>
 * With {@code u_morphed} false (or a vertex without records) both functions return their input unchanged: models
 * without shape keys draw exactly as before. Positions accumulate {@code d += w * delta} in record order, then
 * {@code p + d} - {@code cr_morph_apply}'s float order (equal up to fused multiply-add rounding); a normal with at
 * least one weighted normal delta becomes {@code normalize(n + dn)}.
 *
 * <p>Texture units: the first three above {@link ModelSkinGlsl#INFLUENCES_UNIT} (Stonebreak binds up to 30, the engine
 * 30 = dye mask, 31 = joint palette, 32 = skin influences); GL 3.3 guarantees 48 combined units. Every program that
 * splices this code must point the three samplers at their units even when unused (samplers of different types may not
 * share a unit) - {@link #bindSamplerUnits}.
 */
public final class ModelMorphGlsl {

    private ModelMorphGlsl() {
    }

    /** Vertex attribute location of the per-vertex {@code (offset, count)} uint32 pair (free since rig ABI 3). */
    public static final int RANGE_LOCATION = 3;
    /** Texture unit of the delta buffer texture. */
    public static final int DELTAS_UNIT = 33;
    /** Texture unit of the normal-delta buffer texture. */
    public static final int NORMALS_UNIT = 34;
    /** Texture unit of the per-frame weight buffer texture. */
    public static final int WEIGHTS_UNIT = 35;

    /** Attribute, uniforms, {@code morphPosition(vec3)} and {@code morphNormal(vec3)}. */
    public static final String VERTEX = """
            layout (location = 3) in uvec2 aMorphRange;
            uniform bool u_morphed;
            uniform bool u_morphNormals;
            uniform samplerBuffer u_morphDeltas;
            uniform samplerBuffer u_morphNormalDeltas;
            uniform samplerBuffer u_morphWeights;

            // The rest position displaced by the weighted shape keys (unchanged without morphs).
            vec3 morphPosition(vec3 position) {
                if (!u_morphed || aMorphRange.y == 0u) return position;
                int first = int(aMorphRange.x);
                int count = int(aMorphRange.y);
                vec3 d = vec3(0.0);
                for (int i = first; i < first + count; i++) {
                    vec4 rec = texelFetch(u_morphDeltas, i);
                    d += texelFetch(u_morphWeights, int(rec.w)).r * rec.xyz;
                }
                return position + d;
            }

            // The rest normal turned by the weighted normal deltas (unchanged without them).
            vec3 morphNormal(vec3 normal) {
                if (!u_morphed || !u_morphNormals || aMorphRange.y == 0u) return normal;
                int first = int(aMorphRange.x);
                int count = int(aMorphRange.y);
                vec3 dn = vec3(0.0);
                bool moved = false;
                for (int i = first; i < first + count; i++) {
                    float w = texelFetch(u_morphWeights, int(texelFetch(u_morphDeltas, i).w)).r;
                    vec3 n = texelFetch(u_morphNormalDeltas, i).xyz;
                    moved = moved || (w != 0.0 && n != vec3(0.0));
                    dn += w * n;
                }
                if (!moved) return normal;
                vec3 r = normal + dn;
                return dot(r, r) > 0.0 ? normalize(r) : normal;
            }
            """;

    /** Points a bound program's morph samplers at their units and switches morphing off (program init). */
    public static void bindSamplerUnits(com.openmason.engine.rendering.shaders.ShaderProgram program) {
        program.setInt("u_morphDeltas", DELTAS_UNIT);
        program.setInt("u_morphNormalDeltas", NORMALS_UNIT);
        program.setInt("u_morphWeights", WEIGHTS_UNIT);
        program.setBool("u_morphed", false);
        program.setBool("u_morphNormals", false);
    }
}
