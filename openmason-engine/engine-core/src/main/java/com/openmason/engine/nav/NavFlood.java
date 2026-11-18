package com.openmason.engine.nav;

import com.openmason.engine.physics.PhysicsWorld;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static com.openmason.engine.nav.CendaNav.I32;
import static com.openmason.engine.nav.CendaNav.I64;
import static com.openmason.engine.nav.CendaNav.rethrow;

/**
 * A bounded breadth-first flood over a physics world's cells (native {@code cn_flood}, OpenMason
 * #116): for reachability questions that are not routes, such as which leaves can still reach a log.
 * From the start cell (always expanded, whatever it holds), a face neighbour is entered when its y is
 * in {@code [minY, maxY)}, its shape's category intersects the pass mask and it is at most
 * {@code maxSteps} steps away; cells expand by distance, then discovery order (neighbours +x, -x, +y,
 * -y, +z, -z), at most {@code maxCells} of them. The rule lives in C++; this class only marshals.
 *
 * <p>Cells are ABSOLUTE {@code long}s and Y is unbounded ({@link NavWorld#Y_UNBOUNDED_MIN} /
 * {@link NavWorld#Y_UNBOUNDED_MAX}); keys are full triples, so a flood may travel any distance.
 *
 * <p>Reusable (its buffers are kept): run one flood, read its cells, run the next. Owner thread of
 * the physics world.
 */
public final class NavFlood {

    private final int capacity;
    private final MemorySegment query;
    private final MemorySegment out;
    private long[] cells = new long[0];
    private int[] categories = new int[0];
    private int count;

    /** A flood that records up to {@code capacity} expanded cells. */
    public NavFlood(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        CendaNav.requireAvailable();
        this.capacity = capacity;
        Arena arena = Arena.ofAuto();
        this.query = arena.allocate(CendaNav.FLOOD_QUERY_BYTES, 8);
        this.out = arena.allocate(CendaNav.FLOOD_CELL_BYTES * capacity, 8);
    }

    /**
     * Floods from (x, y, z). At most {@link #capacity()} cells expand.
     *
     * @return how many cells expanded (readable through {@link #x}/{@link #y}/{@link #z}/{@link #category})
     */
    public int run(PhysicsWorld physics, long x, long y, long z, int maxSteps, int passMask, long minY, long maxY) {
        // cn_flood_query (56 bytes): int64 x, y, z; int32 max_steps, max_cells; uint32 pass_mask;
        // int32 reserved; int64 min_y, max_y.
        query.set(I64, 0, x);
        query.set(I64, 8, y);
        query.set(I64, 16, z);
        query.set(I32, 24, maxSteps);
        query.set(I32, 28, capacity);
        query.set(I32, 32, passMask);
        query.set(I32, 36, 0);
        query.set(I64, 40, minY);
        query.set(I64, 48, maxY);
        try {
            int n = (int) CendaNav.FLOOD.invokeExact(physics.nativeHandle(), query, out, capacity);
            if (n < 0) {
                throw new IllegalArgumentException("cn_flood: " + CendaNav.lastError());
            }
            int kept = Math.min(n, capacity);
            if (categories.length < kept) {
                int size = Math.max(kept, Math.min(capacity, 64));
                cells = new long[size * 3];
                categories = new int[size];
            }
            // cn_flood_cell (32 bytes): int64 x, y, z; uint32 category, reserved.
            for (int i = 0; i < kept; i++) {
                long o = i * CendaNav.FLOOD_CELL_BYTES;
                cells[i * 3] = out.get(I64, o);
                cells[i * 3 + 1] = out.get(I64, o + 8);
                cells[i * 3 + 2] = out.get(I64, o + 16);
                categories[i] = out.get(I32, o + 24);
            }
            count = kept;
            return n;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public int capacity() {
        return capacity;
    }

    /** Cells the last flood expanded, in expansion order. */
    public int count() {
        return count;
    }

    public long x(int i) {
        return cells[i * 3];
    }

    public long y(int i) {
        return cells[i * 3 + 1];
    }

    public long z(int i) {
        return cells[i * 3 + 2];
    }

    /** The shape category word of expanded cell {@code i} (0 when it holds no shape). */
    public int category(int i) {
        return categories[i];
    }
}
