package com.openmason.engine.voxel.world;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static com.openmason.engine.voxel.world.CendaVoxelWorld.ADDR;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.ERR_MISSING;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.HOST_CELL_BYTES;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.I16;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.I32;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.QUAD_BYTES;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.failure;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.isNeed;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.rethrow;

/**
 * The result of {@link CubeWorld#meshCube} ({@code cv_mesh_out}): cube-local {@code cv_quad} lists (12 bytes
 * each: x, y, z, face, w, h, u16 id, 4 corner lights) for the opaque, cutout and translucent layers and the
 * {@code cv_host_cell}s (4 bytes: u16 cell index, u16 id) the host meshes itself, plus the cube's 15
 * visibility bits. One instance per thread, reused (and grown) by every mesh on that thread: copy what you
 * keep before meshing again. The lists are native memory; {@code ...Buffer()} views are little-endian.
 */
public final class CubeMesh {

    public static final int QUAD_SIZE = (int) QUAD_BYTES;
    public static final int HOST_CELL_SIZE = (int) HOST_CELL_BYTES;
    /** {@code CV_MESH_*} flags. */
    public static final int SMOOTH_LIGHTING = 1;
    public static final int GREEDY = 2;

    private static final int OPAQUE = 0, CUTOUT = 1, TRANSLUCENT = 2, HOST = 3;
    private static final long[] PTR = {0, 16, 32, 48};
    private static final long[] CAP = {8, 24, 40, 56};
    private static final long[] COUNT = {12, 28, 44, 60};
    private static final long[] UNIT = {QUAD_BYTES, QUAD_BYTES, QUAD_BYTES, HOST_CELL_BYTES};

    private static final ThreadLocal<CubeMesh> PER_THREAD = ThreadLocal.withInitial(CubeMesh::new);

    private final MemorySegment out = Arena.ofAuto().allocate(CendaVoxelWorld.MESH_OUT_BYTES, 8);
    private final MemorySegment[] lists = new MemorySegment[4];
    private final int[] caps = {4096, 1024, 1024, 1024};
    private final int[] counts = new int[4];
    private int visibility;
    private int flagsOut;

    private CubeMesh() {
        for (int k = 0; k < 4; k++) lists[k] = Arena.ofAuto().allocate(caps[k] * UNIT[k], 8);
    }

    static CubeMesh forThread() {
        return PER_THREAD.get();
    }

    boolean mesh(MemorySegment world, long cx, long cy, long cz, int flags) {
        MethodHandle h = CendaVoxelWorld.require(CendaVoxelWorld.MESH_CUBE, "cv_mesh_cube");
        try {
            for (int attempt = 0; ; attempt++) {
                for (int k = 0; k < 4; k++) {
                    out.set(ADDR, PTR[k], lists[k]);
                    out.set(I32, CAP[k], caps[k]);
                    out.set(I32, COUNT[k], 0);
                }
                int r = (int) h.invokeExact(world, cx, cy, cz, flags, out);
                if (r == ERR_MISSING) return false;
                if (isNeed(r) && attempt < 4) {
                    for (int k = 0; k < 4; k++) {
                        int needed = out.get(I32, COUNT[k]);
                        if (needed > caps[k]) {
                            caps[k] = Math.max(needed, caps[k] * 2);
                            lists[k] = Arena.ofAuto().allocate(caps[k] * UNIT[k], 8);
                        }
                    }
                    continue;
                }
                if (r < 0) throw failure("cv_mesh_cube", r);
                for (int k = 0; k < 4; k++) counts[k] = out.get(I32, COUNT[k]);
                visibility = Short.toUnsignedInt(out.get(I16, 64));
                flagsOut = out.get(I32, 68);
                return true;
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private MemorySegment list(int k) {
        return lists[k].asSlice(0, counts[k] * UNIT[k]);
    }

    public MemorySegment opaque() {
        return list(OPAQUE);
    }

    public MemorySegment cutout() {
        return list(CUTOUT);
    }

    public MemorySegment translucent() {
        return list(TRANSLUCENT);
    }

    public MemorySegment hostCells() {
        return list(HOST);
    }

    public ByteBuffer opaqueBuffer() {
        return opaque().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    public ByteBuffer cutoutBuffer() {
        return cutout().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    public ByteBuffer translucentBuffer() {
        return translucent().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    public ByteBuffer hostCellBuffer() {
        return hostCells().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    public int opaqueCount() {
        return counts[OPAQUE];
    }

    public int cutoutCount() {
        return counts[CUTOUT];
    }

    public int translucentCount() {
        return counts[TRANSLUCENT];
    }

    public int hostCellCount() {
        return counts[HOST];
    }

    /** 15 face-pair bits (voxel_world.h cv_mesh_out.visibility). */
    public int visibility() {
        return visibility;
    }

    /** True when the cube has no drawable geometry ({@code flags_out} bit 0). */
    public boolean empty() {
        return (flagsOut & 1) != 0;
    }
}
