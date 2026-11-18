package com.openmason.engine.voxel.world;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Cleaner;
import java.nio.file.Path;

import static com.openmason.engine.voxel.world.CendaVoxelWorld.failure;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.isNeed;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.needed;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.rethrow;

/**
 * The native region store ({@code cv_store}, OpenMason #120): cube records in 3D region files of 8x8x8
 * cubes, column records in 2D region files of 32x32 columns, zstd-compressed, written with shadow paging.
 * Records are opaque bytes (the host composes engine + game payloads). Thread-safe; calls block on I/O.
 * File-system failures throw {@link java.io.UncheckedIOException} with the native message.
 */
public final class CubeStore implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    private final Cleaner.Cleanable cleanable;
    private volatile MemorySegment store;

    private CubeStore(MemorySegment store) {
        this.store = store;
        this.cleanable = CLEANER.register(this, new Close(store));
    }

    /** Opens (creating when needed) the store under {@code dir}. */
    public static CubeStore open(Path dir) {
        if (!CendaVoxelWorld.isAvailable()) {
            throw new IllegalStateException("Cenda voxel world unavailable: " + CendaVoxelWorld.unavailableReason());
        }
        MethodHandle h = CendaVoxelWorld.require(CendaVoxelWorld.STORE_OPEN, "cv_store_open");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment s = (MemorySegment) h.invokeExact(a.allocateFrom(dir.toAbsolutePath().toString()));
            if (s.equals(MemorySegment.NULL)) {
                throw new java.io.UncheckedIOException(new java.io.IOException(
                    "cv_store_open failed for " + dir + ": " + CendaVoxelWorld.lastError()));
            }
            return new CubeStore(s);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private record Close(MemorySegment store) implements Runnable {
        @Override
        public void run() {
            try {
                int ignored = (int) CendaVoxelWorld.STORE_CLOSE.invokeExact(store);
            } catch (Throwable ignored) {
                // releasing at exit
            }
        }
    }

    MemorySegment pointer() {
        MemorySegment s = store;
        if (s == null) throw new IllegalStateException("CubeStore is closed");
        return s;
    }

    /** Flushes and closes (must not race other calls). */
    @Override
    public void close() {
        if (store != null) {
            store = null;
            cleanable.clean();
        }
    }

    public void flush() {
        try {
            int r = (int) CendaVoxelWorld.require(CendaVoxelWorld.STORE_FLUSH, "cv_store_flush").invokeExact(pointer());
            if (r < 0) throw failure("cv_store_flush", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The cube record, or null when absent. */
    public byte[] readCube(long cx, long cy, long cz) {
        MethodHandle h = CendaVoxelWorld.require(CendaVoxelWorld.STORE_READ_CUBE, "cv_store_read_cube");
        CubeWorld.Scratch s = CubeWorld.SCRATCH.get();
        long cap = 64 * 1024;
        try {
            for (;;) {
                MemorySegment out = s.bytes(cap);
                long n = (long) h.invokeExact(pointer(), cx, cy, cz, out, out.byteSize());
                if (isNeed(n)) {
                    cap = needed(n);
                    continue;
                }
                if (n < 0) throw failure("cv_store_read_cube", n);
                return n == 0 ? null : out.asSlice(0, n).toArray(ValueLayout.JAVA_BYTE);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Writes (or, with an empty array, deletes) a cube record. */
    public void writeCube(long cx, long cy, long cz, byte[] record) {
        MethodHandle h = CendaVoxelWorld.require(CendaVoxelWorld.STORE_WRITE_CUBE, "cv_store_write_cube");
        MemorySegment seg = CubeWorld.SCRATCH.get().bytes(Math.max(1, record.length));
        MemorySegment.copy(record, 0, seg, ValueLayout.JAVA_BYTE, 0, record.length);
        try {
            int r = (int) h.invokeExact(pointer(), cx, cy, cz, seg, (long) record.length);
            if (r < 0) throw failure("cv_store_write_cube", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void deleteCube(long cx, long cy, long cz) {
        writeCube(cx, cy, cz, new byte[0]);
    }

    /** The column record, or null when absent. */
    public byte[] readColumn(long cx, long cz) {
        MethodHandle h = CendaVoxelWorld.require(CendaVoxelWorld.STORE_READ_COLUMN, "cv_store_read_column");
        CubeWorld.Scratch s = CubeWorld.SCRATCH.get();
        long cap = 16 * 1024;
        try {
            for (;;) {
                MemorySegment out = s.bytes(cap);
                long n = (long) h.invokeExact(pointer(), cx, cz, out, out.byteSize());
                if (isNeed(n)) {
                    cap = needed(n);
                    continue;
                }
                if (n < 0) throw failure("cv_store_read_column", n);
                return n == 0 ? null : out.asSlice(0, n).toArray(ValueLayout.JAVA_BYTE);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Writes (or, with an empty array, deletes) a column record. */
    public void writeColumn(long cx, long cz, byte[] record) {
        MethodHandle h = CendaVoxelWorld.require(CendaVoxelWorld.STORE_WRITE_COLUMN, "cv_store_write_column");
        MemorySegment seg = CubeWorld.SCRATCH.get().bytes(Math.max(1, record.length));
        MemorySegment.copy(record, 0, seg, ValueLayout.JAVA_BYTE, 0, record.length);
        try {
            int r = (int) h.invokeExact(pointer(), cx, cz, seg, (long) record.length);
            if (r < 0) throw failure("cv_store_write_column", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }
}
