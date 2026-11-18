package com.openmason.engine.voxel.world;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static com.openmason.engine.voxel.world.CendaVoxelWorld.ADDR;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.I32;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.I64;

/**
 * An octree LOD node mesh ({@code cv_lod_mesh}): LODQUAD16 records in NODE MODE
 * ({@link com.openmason.engine.voxel.mms.mmsCore.MmsLodQuadCodec#nodeWord0}) - cell coordinates relative to the
 * node's block {@link #originX() origin}, {@link #cellSize()} blocks per cell. The segments point into native
 * memory: valid until the next {@link CubeLod#tick} (event meshes) or the thread's next
 * {@link CubeLod#buildMesh} - upload or copy them before.
 */
public final class LodMesh {

    public static final int QUAD_BYTES = 16;

    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;

    private final MemorySegment m;

    LodMesh(MemorySegment mesh) {
        this.m = mesh.reinterpret(CendaVoxelWorld.LOD_MESH_BYTES);
    }

    public int level() {
        return m.get(I32, 0);
    }

    /** Blocks per cell ({@code 1 << level}). */
    public int cellSize() {
        return m.get(I32, 4);
    }

    public long nx() {
        return m.get(I64, 8);
    }

    public long ny() {
        return m.get(I64, 16);
    }

    public long nz() {
        return m.get(I64, 24);
    }

    /** Block coordinates of the node's min corner. */
    public long originX() {
        return m.get(I64, 32);
    }

    public long originY() {
        return m.get(I64, 40);
    }

    public long originZ() {
        return m.get(I64, 48);
    }

    public int quadCount() {
        return m.get(I32, 64);
    }

    /** Terrain records ({@code quadCount * 16} bytes; empty when none). */
    public MemorySegment quads() {
        return records(m.get(ADDR, 56), quadCount());
    }

    public int waterCount() {
        return m.get(I32, 80);
    }

    /** Fluid top records ({@code waterCount * 16} bytes; empty when none). */
    public MemorySegment water() {
        return records(m.get(ADDR, 72), waterCount());
    }

    /** Node-local bounds in cells: {min x, y, z, max x, y, z}. */
    public float[] bounds() {
        float[] b = new float[6];
        for (int i = 0; i < 6; i++) b[i] = m.get(F32, 88 + 4L * i);
        return b;
    }

    /** Content digest of the node data the mesh was built from. */
    public long digest() {
        return m.get(I64, 112);
    }

    public boolean isEmpty() {
        return (m.get(I32, 120) & 1) != 0;
    }

    public long bytes() {
        return (long) (quadCount() + waterCount()) * QUAD_BYTES;
    }

    private static MemorySegment records(MemorySegment address, int count) {
        if (count <= 0 || address.equals(MemorySegment.NULL)) return MemorySegment.NULL;
        return address.reinterpret((long) count * QUAD_BYTES);
    }
}
