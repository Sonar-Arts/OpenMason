package com.openmason.engine.voxel.world;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static com.openmason.engine.voxel.world.CendaVoxelWorld.ADDR;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.I32;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.I64;

/**
 * A GPU-ready cube mesh handed out by {@link CubeStream#mesh} ({@code cv_gpu_mesh}): region-local packed
 * records in the engine's GPU formats (QUAD16 per layer, WATERQUAD16, COMPACT20 stamp vertices + u32
 * indices), ready to copy into an arena or staging buffer. The segments point into native memory owned by
 * the stream - no heap copies - and are valid only until {@link CubeStream#releaseMesh} or the next tick:
 * upload (or copy) them before. Reading after that is undefined (the memory is recycled).
 */
public final class StreamMesh {

    public static final int QUAD16_BYTES = 16;
    public static final int WATERQUAD16_BYTES = 16;
    public static final int COMPACT20_BYTES = 20;

    /** Quad layers. */
    public static final int OPAQUE = 0;
    public static final int CUTOUT = 1;
    public static final int TRANSLUCENT = 2;

    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;
    private static final ValueLayout.OfShort U16 = ValueLayout.JAVA_SHORT;

    private final MemorySegment m;

    StreamMesh(MemorySegment mesh) {
        this.m = mesh.reinterpret(CendaVoxelWorld.GPU_MESH_BYTES);
    }

    public long cx() {
        return m.get(I64, 0);
    }

    public long cy() {
        return m.get(I64, 8);
    }

    public long cz() {
        return m.get(I64, 16);
    }

    /** Quads of one layer ({@link #OPAQUE}, {@link #CUTOUT}, {@link #TRANSLUCENT}). */
    public int quadCount(int layer) {
        checkLayer(layer);
        return m.get(I32, 48 + layer * 4L);
    }

    /** The QUAD16 records of one layer ({@code quadCount * 16} bytes; empty when none). */
    public MemorySegment quads(int layer) {
        checkLayer(layer);
        return records(m.get(ADDR, 24 + layer * 8L), quadCount(layer), QUAD16_BYTES);
    }

    public int waterCount() {
        return m.get(I32, 72);
    }

    /** WATERQUAD16 records ({@code waterCount * 16} bytes). */
    public MemorySegment water() {
        return records(m.get(ADDR, 64), waterCount(), WATERQUAD16_BYTES);
    }

    public int stampVertexCount() {
        return m.get(I32, 88);
    }

    /** COMPACT20 stamp vertices ({@code stampVertexCount * 20} bytes). */
    public MemorySegment stampVertices() {
        return records(m.get(ADDR, 80), stampVertexCount(), COMPACT20_BYTES);
    }

    public int stampIndexCount() {
        return m.get(I32, 104);
    }

    /** u32 stamp indices ({@code stampIndexCount * 4} bytes). */
    public MemorySegment stampIndices() {
        return records(m.get(ADDR, 96), stampIndexCount(), 4);
    }

    /** Region-local bounds of the geometry, axis 0..2. */
    public float aabbMin(int axis) {
        return m.get(F32, 108 + checkAxis(axis) * 4L);
    }

    public float aabbMax(int axis) {
        return m.get(F32, 120 + checkAxis(axis) * 4L);
    }

    /** 15-bit visibility graph of the cube (face pairs, as {@code cv_mesh_out}). */
    public int visibility() {
        return Short.toUnsignedInt(m.get(U16, 132));
    }

    public int flags() {
        return Short.toUnsignedInt(m.get(U16, 134));
    }

    /** No drawable geometry (the host should drop the cube's previous mesh). */
    public boolean isEmpty() {
        return (flags() & 1) != 0;
    }

    public boolean hasTranslucent() {
        return (flags() & 2) != 0;
    }

    /** Holds an exposed {@link BlockFlags#DETAIL} block: its look depends on the detail mode. */
    public boolean hasDetail() {
        return (flags() & 4) != 0;
    }

    /** Built with {@link CubeStream#MESH_DETAIL} (detail blocks drawn as their models). */
    public boolean builtWithDetail() {
        return (flags() & 8) != 0;
    }

    /** The cube revision the mesh was built from. */
    public long revision() {
        return m.get(I64, 136);
    }

    /** Total bytes of all record segments. */
    public long byteSize() {
        long n = 0;
        for (int l = 0; l < 3; l++) n += (long) quadCount(l) * QUAD16_BYTES;
        return n + (long) waterCount() * WATERQUAD16_BYTES + (long) stampVertexCount() * COMPACT20_BYTES
            + stampIndexCount() * 4L;
    }

    private static MemorySegment records(MemorySegment address, int count, int bytes) {
        if (count <= 0 || address.equals(MemorySegment.NULL)) return MemorySegment.NULL;
        return address.reinterpret((long) count * bytes);
    }

    private static void checkLayer(int layer) {
        if (layer < 0 || layer > 2) throw new IllegalArgumentException("quad layer " + layer);
    }

    private static int checkAxis(int axis) {
        if (axis < 0 || axis > 2) throw new IllegalArgumentException("axis " + axis);
        return axis;
    }
}
