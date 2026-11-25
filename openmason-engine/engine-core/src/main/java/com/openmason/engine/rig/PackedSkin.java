package com.openmason.engine.rig;

/**
 * GPU vertex attributes of a skinned mesh: per vertex 4 joint indices (unsigned 16-bit, in a
 * {@code short[]}) and 4 weights (unsigned normalized 8-bit, summing to exactly 255, in a
 * {@code byte[]}). A rigid vertex is {@code (partJoint,0,0,0)/(255,0,0,0)}.
 *
 * @param joints  4 per vertex
 * @param weights 4 per vertex
 */
public record PackedSkin(short[] joints, byte[] weights) {

    public int vertexCount() {
        return joints.length / 4;
    }

    /** Every vertex rigid to its part joint. */
    public static PackedSkin rigid(int[] partJointOfVertex) {
        int n = partJointOfVertex.length;
        short[] j = new short[n * 4];
        byte[] w = new byte[n * 4];
        for (int v = 0; v < n; v++) {
            j[v * 4] = (short) Math.max(0, Math.min(65535, partJointOfVertex[v]));
            w[v * 4] = (byte) 255;
        }
        return new PackedSkin(j, w);
    }
}
