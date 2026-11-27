package com.openmason.engine.modeling.core;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * A uniform hash grid over a fixed set of points for "which points lie within epsilon of p" queries, built once in
 * O(n) (#175: replaces O(moved x N) epsilon scans on drag release). The cell edge equals the query radius, so every
 * point within the radius sits in the 3x3x3 cells around the query; each candidate is still distance-checked, so
 * results equal a brute-force scan.
 */
public final class PointGrid {

    private final float[] xyz;
    private final float cell;
    private final Map<Long, int[]> cells = new HashMap<>();

    /**
     * @param xyz    point coordinates ({@code x,y,z} per point; read, not copied - do not change while in use)
     * @param ids    which points to index (point numbers into {@code xyz}), or null for all
     * @param radius the query radius (cell edge), > 0
     */
    public PointGrid(float[] xyz, int[] ids, float radius) {
        this.xyz = xyz;
        this.cell = radius;
        int n = ids != null ? ids.length : xyz.length / 3;
        Map<Long, int[]> building = cells;
        int[] sizes = new int[0];
        Map<Long, Integer> slot = new HashMap<>();
        for (int k = 0; k < n; k++) {
            int i = ids != null ? ids[k] : k;
            long key = key(cellOf(xyz[i * 3]), cellOf(xyz[i * 3 + 1]), cellOf(xyz[i * 3 + 2]));
            Integer s = slot.get(key);
            if (s == null) {
                s = slot.size();
                slot.put(key, s);
                if (s >= sizes.length) sizes = Arrays.copyOf(sizes, Math.max(8, sizes.length * 2));
                building.put(key, new int[4]);
            }
            int[] list = building.get(key);
            int count = sizes[s];
            if (count == list.length) {
                list = Arrays.copyOf(list, list.length * 2);
                building.put(key, list);
            }
            list[count] = i;
            sizes[s] = count + 1;
        }
        // Trim each list to its size (lists end where their count ends).
        for (Map.Entry<Long, Integer> e : slot.entrySet()) {
            int[] list = building.get(e.getKey());
            building.put(e.getKey(), Arrays.copyOf(list, sizes[e.getValue()]));
        }
    }

    /** Receives each point within the radius. */
    @FunctionalInterface
    public interface Hit {
        void accept(int point, float distanceSq);
    }

    /** Every indexed point with squared distance {@code <= radiusSq} from (x, y, z), in no particular order. */
    public void query(float x, float y, float z, float radiusSq, Hit hit) {
        long cx = cellOf(x), cy = cellOf(y), cz = cellOf(z);
        for (long dx = -1; dx <= 1; dx++) {
            for (long dy = -1; dy <= 1; dy++) {
                for (long dz = -1; dz <= 1; dz++) {
                    int[] list = cells.get(key(cx + dx, cy + dy, cz + dz));
                    if (list == null) continue;
                    for (int i : list) {
                        float ddx = xyz[i * 3] - x, ddy = xyz[i * 3 + 1] - y, ddz = xyz[i * 3 + 2] - z;
                        float d = ddx * ddx + ddy * ddy + ddz * ddz;
                        if (d <= radiusSq) hit.accept(i, d);
                    }
                }
            }
        }
    }

    private long cellOf(float v) {
        return (long) Math.floor(v / cell);
    }

    /** Hash of a cell (collisions only add candidates; every candidate is distance-checked). */
    private static long key(long x, long y, long z) {
        return x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
    }
}
