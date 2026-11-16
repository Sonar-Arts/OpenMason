package com.openmason.engine.entity;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * A growable list of entity handles that {@link EntityWorld} fills from its native output buffer.
 * Reuse one per caller to keep per-frame queries allocation-free.
 */
public final class HandleList {

    private long[] items;
    private int size;

    public HandleList() {
        this(16);
    }

    public HandleList(int capacity) {
        this.items = new long[Math.max(1, capacity)];
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public long get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(index);
        }
        return items[index];
    }

    public void clear() {
        size = 0;
    }

    public long[] toArray() {
        return Arrays.copyOf(items, size);
    }

    void append(MemorySegment handles, int count) {
        if (count <= 0) {
            return;
        }
        if (size + count > items.length) {
            items = Arrays.copyOf(items, Math.max(items.length * 2, size + count));
        }
        MemorySegment.copy(handles, ValueLayout.JAVA_LONG, 0, items, size, count);
        size += count;
    }
}
