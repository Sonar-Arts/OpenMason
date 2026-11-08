package com.openmason.engine.rendering.particles;

/**
 * The 32-byte per-instance record the particle shader reads. There is no per-vertex buffer at all: the four
 * quad corners come from {@code gl_VertexID}, so one particle costs exactly one record.
 *
 * <pre>
 * offset  size  attribute                                   GL type
 *  0      12    position (render space)                     3 × GL_FLOAT
 * 12       4    colour, premultiplied                        4 × GL_UNSIGNED_BYTE normalized (R,G,B,A in memory)
 * 16       4    size, rotation (radians)                     2 × GL_HALF_FLOAT
 * 20       4    frame code, flags                            2 × GL_UNSIGNED_SHORT (integer attribute)
 * 24       8    trail axis xyz (velocity × stretch), softness 4 × GL_HALF_FLOAT
 * </pre>
 *
 * Colour alpha is coverage × (1 − additive): with {@code glBlendFunc(ONE, ONE_MINUS_SRC_ALPHA)} an alpha of 0
 * adds and an alpha of 1 blends, so additive and alpha-blended particles share a single draw call.
 * Frame codes {@code 0xFFFF}/{@code 0xFFFE} are the procedural soft disc / square, {@code 0xFFFD} the
 * missing-texture checker; codes below {@code 0xFFF0} are layers of the {@link ParticleTextureArray}. Flags: bit 0 = emissive, bits 1–2 = {@link ParticleEffect.Orientation} ordinal.
 */
public final class ParticleVertexFormat {

    public static final int STRIDE = 32;
    public static final int OFFSET_POSITION = 0;
    public static final int OFFSET_COLOR = 12;
    public static final int OFFSET_SIZE_ROTATION = 16;
    public static final int OFFSET_FRAME_FLAGS = 20;
    public static final int OFFSET_AXIS_SOFTNESS = 24;

    public static final int ATTRIB_POSITION = 0;
    public static final int ATTRIB_COLOR = 1;
    public static final int ATTRIB_SIZE_ROTATION = 2;
    public static final int ATTRIB_FRAME_FLAGS = 3;
    public static final int ATTRIB_AXIS_SOFTNESS = 4;

    public static final int FRAME_SOFT_DISC = CompiledEffect.FRAME_SOFT_DISC;
    public static final int FRAME_SQUARE = CompiledEffect.FRAME_SQUARE;
    public static final int FRAME_MISSING = CompiledEffect.FRAME_MISSING;
    public static final int FLAG_EMISSIVE = 1;
    public static final int ORIENTATION_SHIFT = 1;

    private ParticleVertexFormat() {
    }
}
