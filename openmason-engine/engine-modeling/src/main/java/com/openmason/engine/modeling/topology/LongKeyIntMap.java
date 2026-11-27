package com.openmason.engine.modeling.topology;

import java.util.AbstractMap;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Read-only {@code Map<Long, Integer>} over an open-addressing primitive table, for the topology's packed-pair keys
 * ({@link MeshEdge#canonicalKey}, {@link MeshGeometry#canonicalFacePairKey}: {@code min << 32 | max}). Those keys
 * collide massively under {@code Long.hashCode} ({@code min ^ max}), which turned every {@code HashMap} put into a
 * tree-bin insertion: ~80% of a 54k-triangle topology assembly (#175 INTEG, measured with JFR). This table mixes the
 * full 64 bits. Filled by the builder ({@link #put(long, int)}: a repeated key keeps the LAST value, as
 * {@code HashMap.put}); the topology services only {@code get} / {@code containsKey}. Not thread-safe while filling.
 */
final class LongKeyIntMap extends AbstractMap<Long, Integer> {

    private static final long EMPTY = Long.MIN_VALUE;

    private long[] keys;
    private int[] values;
    private int mask;
    private int size;
    private boolean hasEmptyKey;
    private int emptyKeyValue;

    LongKeyIntMap(int expected) {
        int capacity = Integer.highestOneBit(Math.max(4, expected * 2 - 1)) << 1;
        keys = new long[capacity];
        values = new int[capacity];
        java.util.Arrays.fill(keys, EMPTY);
        mask = capacity - 1;
    }

    private static int slot(long key, int mask) {
        long h = key;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (int) h & mask;
    }

    /** Inserts or replaces (the last value for a key wins). */
    void put(long key, int value) {
        if (key == EMPTY) {
            if (!hasEmptyKey) size++;
            hasEmptyKey = true;
            emptyKeyValue = value;
            return;
        }
        if ((size + 1) * 2 > keys.length) grow();
        int i = slot(key, mask);
        while (keys[i] != EMPTY) {
            if (keys[i] == key) {
                values[i] = value;
                return;
            }
            i = (i + 1) & mask;
        }
        keys[i] = key;
        values[i] = value;
        size++;
    }

    private void grow() {
        long[] oldKeys = keys;
        int[] oldValues = values;
        keys = new long[oldKeys.length * 2];
        values = new int[oldKeys.length * 2];
        java.util.Arrays.fill(keys, EMPTY);
        mask = keys.length - 1;
        for (int j = 0; j < oldKeys.length; j++) {
            if (oldKeys[j] == EMPTY) continue;
            int i = slot(oldKeys[j], mask);
            while (keys[i] != EMPTY) i = (i + 1) & mask;
            keys[i] = oldKeys[j];
            values[i] = oldValues[j];
        }
    }

    private boolean has(long key) {
        if (key == EMPTY) return hasEmptyKey;
        int i = slot(key, mask);
        while (keys[i] != EMPTY) {
            if (keys[i] == key) return true;
            i = (i + 1) & mask;
        }
        return false;
    }

    @Override
    public Integer get(Object key) {
        if (!(key instanceof Long k) || !has(k)) return null;
        return k == EMPTY ? emptyKeyValue : values[find(k)];
    }

    private int find(long key) {
        int i = slot(key, mask);
        while (keys[i] != key) i = (i + 1) & mask;
        return i;
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof Long k && has(k);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public Set<Entry<Long, Integer>> entrySet() {
        Set<Entry<Long, Integer>> out = new LinkedHashSet<>(size * 2);
        if (hasEmptyKey) out.add(new SimpleImmutableEntry<>(EMPTY, emptyKeyValue));
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != EMPTY) out.add(new SimpleImmutableEntry<>(keys[i], values[i]));
        }
        return java.util.Collections.unmodifiableSet(out);
    }
}
