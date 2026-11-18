package com.openmason.engine.voxel.world;

import java.lang.foreign.MemorySegment;

import static com.openmason.engine.voxel.world.CendaVoxelWorld.I32;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.I64;

/**
 * The events of one {@link CubeStream#tick} ({@code cv_event[]}), read straight from native memory. The view
 * is reused by every tick of its stream and is valid only until the next tick: read it on the owner thread
 * right after the tick. Event types: {@link CubeStream#EV_INSTALLED} ... {@link CubeStream#EV_CHANGED};
 * for column events {@link #cy} is 0. Ignore types you do not know (later libraries may add some).
 */
public final class StreamEvents {

    /** {@code sizeof(cv_event)}. */
    static final long BYTES = CendaVoxelWorld.EVENT_BYTES;

    /** One event copied to the heap ({@link #get}). {@code mesh} is the REMESHED slot, else -1. */
    public record Event(int type, int mesh, long cx, long cy, long cz) {
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

    /** The native {@code cv_event[]} of this tick (NULL when empty), for {@link CubeLod#noteEvents}. */
    MemorySegment segment() {
        return events;
    }

    public int type(int i) {
        return events.get(I32, offset(i));
    }

    /** The mesh slot of a REMESHED event ({@link CubeStream#mesh}), -1 otherwise. */
    public int mesh(int i) {
        return events.get(I32, offset(i) + 4);
    }

    public long cx(int i) {
        return events.get(I64, offset(i) + 8);
    }

    public long cy(int i) {
        return events.get(I64, offset(i) + 16);
    }

    public long cz(int i) {
        return events.get(I64, offset(i) + 24);
    }

    public Event get(int i) {
        return new Event(type(i), mesh(i), cx(i), cy(i), cz(i));
    }

    private long offset(int i) {
        if (i < 0 || i >= count) throw new IndexOutOfBoundsException("event " + i + " of " + count);
        return i * BYTES;
    }
}
