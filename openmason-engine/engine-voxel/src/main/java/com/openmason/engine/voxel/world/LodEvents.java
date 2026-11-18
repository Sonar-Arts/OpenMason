package com.openmason.engine.voxel.world;

import java.lang.foreign.MemorySegment;

import static com.openmason.engine.voxel.world.CendaVoxelWorld.I32;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.I64;

/**
 * The events of one {@link CubeLod#tick} ({@code cv_lod_event[]}), read straight from native memory; reused by
 * every tick and valid only until the next one. Types: {@link CubeLod#EV_SHOW} ... {@link CubeLod#EV_FALLBACK_OFF}.
 * Node events carry node coordinates (level, x, y, z); fallback events a footprint (level, x, z) with y = 0.
 * A tick's events are a complete change: apply all of them before drawing the next frame.
 */
public final class LodEvents {

    static final long BYTES = CendaVoxelWorld.LOD_EVENT_BYTES;

    /** One event copied to the heap. {@code mesh} is the SHOW / UPDATE slot ({@link CubeLod#mesh}), else -1. */
    public record Event(int type, int mesh, int level, long x, long y, long z) {
    }

    private MemorySegment events = MemorySegment.NULL;
    private int count;

    void reset(MemorySegment address, int n) {
        count = Math.max(0, n);
        events = count == 0 || address.equals(MemorySegment.NULL) ? MemorySegment.NULL : address.reinterpret(count * BYTES);
        if (events.equals(MemorySegment.NULL)) count = 0;
    }

    public int count() {
        return count;
    }

    public int type(int i) {
        return events.get(I32, offset(i));
    }

    public int mesh(int i) {
        return events.get(I32, offset(i) + 4);
    }

    public int level(int i) {
        return events.get(I32, offset(i) + 8);
    }

    public long x(int i) {
        return events.get(I64, offset(i) + 16);
    }

    public long y(int i) {
        return events.get(I64, offset(i) + 24);
    }

    public long z(int i) {
        return events.get(I64, offset(i) + 32);
    }

    public Event get(int i) {
        return new Event(type(i), mesh(i), level(i), x(i), y(i), z(i));
    }

    private long offset(int i) {
        if (i < 0 || i >= count) throw new IndexOutOfBoundsException("event " + i + " of " + count);
        return i * BYTES;
    }
}
