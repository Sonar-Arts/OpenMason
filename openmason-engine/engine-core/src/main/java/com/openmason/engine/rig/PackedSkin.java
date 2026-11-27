package com.openmason.engine.rig;

/**
 * GPU form of a skinned mesh (rig.h ABI 3, OpenMason #170; written by {@code cr_skin_pack} /
 * {@code cc_garment_pack}, read by {@link com.openmason.engine.rendering.model.draw.ModelSkinGlsl}):
 * <ul>
 *   <li>{@code ranges}: 2 x uint32 per vertex, {@code (offset, count)} into {@code influences} - an integer
 *       vertex attribute ({@code uvec2}). Every count is {@code >= 1}.</li>
 *   <li>{@code influences}: 2 x uint16 per influence, {@code (joint, weight)} with the weight as unorm16 - a
 *       {@code GL_RG16UI} buffer texture. A weighted vertex's weights sum to exactly 65535.</li>
 * </ul>
 * A rigid vertex is one influence {@code (partJoint, 65535)}. Java holds the unsigned values in signed arrays
 * (read them with {@link Integer#toUnsignedLong} / {@link Short#toUnsignedInt}).
 *
 * @param ranges     2 per vertex
 * @param influences 2 per influence record
 */
public record PackedSkin(int[] ranges, short[] influences) {

    /** The unorm16 weight that means 1. */
    public static final int WEIGHT_ONE = 65535;

    public int vertexCount() {
        return ranges.length / 2;
    }

    /** Influence records ({@code influences.length / 2}). */
    public int influenceCount() {
        return influences.length / 2;
    }

    /** Every vertex rigid to its part joint (one influence of weight 65535 each). */
    public static PackedSkin rigid(int[] partJointOfVertex) {
        int n = partJointOfVertex.length;
        int[] r = new int[n * 2];
        short[] inf = new short[n * 2];
        for (int v = 0; v < n; v++) {
            r[v * 2] = v;
            r[v * 2 + 1] = 1;
            inf[v * 2] = (short) Math.max(0, Math.min(65535, partJointOfVertex[v]));
            inf[v * 2 + 1] = (short) WEIGHT_ONE;
        }
        return new PackedSkin(r, inf);
    }
}
