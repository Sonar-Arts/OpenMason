package com.openmason.engine.rig;

import org.joml.Matrix4f;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Off-heap per-joint matrices written by {@link RigPoser}: the SKIN matrices
 * {@code K(j) = base·W_pose(j)·W_rest(j)⁻¹} (what the GPU skins with and what sockets mount on) and,
 * optionally, the posed head frames {@code base·W_pose(j)}. Column-major, 16 floats per joint. Reused
 * across frames: {@link #ensureCapacity} grows it. Not thread-safe; one palette per drawing thread.
 */
public final class JointPalette {

    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;

    private MemorySegment skin = MemorySegment.NULL;
    private MemorySegment world = MemorySegment.NULL;
    private int capacity;
    private int count;

    public JointPalette() {
    }

    /** A palette holding {@code matrix} as its only joint (an unrigged model). */
    public static JointPalette single(Matrix4f matrix) {
        JointPalette p = new JointPalette();
        p.ensureCapacity(1);
        p.setCount(1);
        write(p.skin, 0, matrix);
        write(p.world, 0, matrix);
        return p;
    }

    /** Make room for {@code joints} joints (contents are not preserved on growth). */
    public void ensureCapacity(int joints) {
        if (joints <= capacity) return;
        int cap = Math.max(joints, Math.max(8, capacity * 2));
        // Auto arena: the GC frees the old segments once nothing references them.
        skin = Arena.ofAuto().allocate(64L * cap, 16);
        world = Arena.ofAuto().allocate(64L * cap, 16);
        capacity = cap;
    }

    /** Number of valid joints. */
    public int count() {
        return count;
    }

    void setCount(int joints) {
        this.count = joints;
    }

    MemorySegment skinSegment() {
        return skin;
    }

    MemorySegment worldSegment() {
        return world;
    }

    /**
     * For other Cenda bindings that write palettes natively (for example the animation graph): the skin and
     * world segments (64 bytes per joint, {@link #ensureCapacity} first) and the count they wrote. Never keep
     * the segments past a growth.
     */
    public MemorySegment nativeSkin() {
        return skin;
    }

    /** See {@link #nativeSkin()}. */
    public MemorySegment nativeWorld() {
        return world;
    }

    /** See {@link #nativeSkin()}: marks {@code joints} joints valid after a native write. */
    public void nativeWritten(int joints) {
        if (joints < 0 || joints > capacity) throw new IndexOutOfBoundsException(joints);
        this.count = joints;
    }

    /** The skin matrix of {@code joint} into {@code dest}. */
    public Matrix4f skinMatrix(int joint, Matrix4f dest) {
        checkIndex(joint);
        return read(skin, joint, dest);
    }

    /** The posed head frame ({@code base·W_pose}) of {@code joint} into {@code dest}. */
    public Matrix4f worldMatrix(int joint, Matrix4f dest) {
        checkIndex(joint);
        return read(world, joint, dest);
    }

    /** Overwrites one joint's skin and world matrix (hosts composing palettes by hand). */
    public void set(int joint, Matrix4f skinMatrix, Matrix4f worldMatrix) {
        if (joint < 0 || joint >= capacity) throw new IndexOutOfBoundsException(joint);
        write(skin, joint, skinMatrix);
        write(world, joint, worldMatrix);
        if (joint >= count) count = joint + 1;
    }

    /**
     * The skin matrices as a direct float buffer over the native memory (16 floats per joint,
     * {@link #count()} joints) — upload it as-is.
     */
    public FloatBuffer skinBuffer() {
        return skin.asSlice(0, 64L * count).asByteBuffer().order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private void checkIndex(int joint) {
        if (joint < 0 || joint >= count) throw new IndexOutOfBoundsException("joint " + joint + " of " + count);
    }

    static Matrix4f read(MemorySegment s, int joint, Matrix4f dest) {
        long o = 64L * joint;
        return dest.set(
            s.get(F32, o), s.get(F32, o + 4), s.get(F32, o + 8), s.get(F32, o + 12),
            s.get(F32, o + 16), s.get(F32, o + 20), s.get(F32, o + 24), s.get(F32, o + 28),
            s.get(F32, o + 32), s.get(F32, o + 36), s.get(F32, o + 40), s.get(F32, o + 44),
            s.get(F32, o + 48), s.get(F32, o + 52), s.get(F32, o + 56), s.get(F32, o + 60));
    }

    static void write(MemorySegment s, int joint, Matrix4f m) {
        long o = 64L * joint;
        s.set(F32, o, m.m00()); s.set(F32, o + 4, m.m01()); s.set(F32, o + 8, m.m02()); s.set(F32, o + 12, m.m03());
        s.set(F32, o + 16, m.m10()); s.set(F32, o + 20, m.m11()); s.set(F32, o + 24, m.m12()); s.set(F32, o + 28, m.m13());
        s.set(F32, o + 32, m.m20()); s.set(F32, o + 36, m.m21()); s.set(F32, o + 40, m.m22()); s.set(F32, o + 44, m.m23());
        s.set(F32, o + 48, m.m30()); s.set(F32, o + 52, m.m31()); s.set(F32, o + 56, m.m32()); s.set(F32, o + 60, m.m33());
    }
}
