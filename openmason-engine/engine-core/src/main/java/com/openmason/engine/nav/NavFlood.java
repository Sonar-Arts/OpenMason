package com.openmason.engine.nav;

import com.openmason.engine.physics.PhysicsWorld;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static com.openmason.engine.nav.CendaNav.I32;
import static com.openmason.engine.nav.CendaNav.rethrow;

/**
 * A bounded breadth-first flood over a physics world's cells (native {@code cn_flood}, OpenMason
 * #116): for reachability questions that are not routes, such as which leaves can still reach a log.
 * From the start cell (always expanded, whatever it holds), a face neighbour is entered when its y is
 * in {@code [minY, maxY)}, its shape's category intersects the pass mask and it is at most
 * {@code maxSteps} steps away; cells expand by distance, then discovery order (neighbours +x, -x, +y,
 * -y, +z, -z), at most {@code maxCells} of them. The rule lives in C++; this class only marshals.
 *
 * <p>Reusable (its buffers are kept): run one flood, read its cells, run the next. Owner thread of
 * the physics world.
 */
public final class NavFlood {

    private final int capacity;
    private final MemorySegment query;
    private final MemorySegment out;
    private int[] cells = new int[0];
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
        this.out = arena.allocate(I32, 4L * capacity);
    }

    /**
     * Floods from (x, y, z). At most {@link #capacity()} cells expand.
     *
     * @return how many cells expanded (readable through {@link #x}/{@link #y}/{@link #z}/{@link #category})
     */
    public int run(PhysicsWorld physics, int x, int y, int z, int maxSteps, int passMask, int minY, int maxY) {
        query.set(I32, 0, x);
        query.set(I32, 4, y);
        query.set(I32, 8, z);
        query.set(I32, 12, maxSteps);
        query.set(I32, 16, capacity);
        query.set(I32, 20, passMask);
        query.set(I32, 24, minY);
        query.set(I32, 28, maxY);
        try {
            int n = (int) CendaNav.FLOOD.invokeExact(physics.nativeHandle(), query, out, capacity);
            if (n < 0) {
                throw new IllegalArgumentException("cn_flood: " + CendaNav.lastError());
            }
            if (cells.length < n * 4) {
                cells = new int[Math.max(n * 4, Math.min(capacity, 64) * 4)];
            }
            MemorySegment.copy(out, I32, 0, cells, 0, n * 4);
            count = n;
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

    public int x(int i) {
        return cells[i * 4];
    }

    public int y(int i) {
        return cells[i * 4 + 1];
    }

    public int z(int i) {
        return cells[i * 4 + 2];
    }

    /** The shape category word of expanded cell {@code i} (0 when it holds no shape). */
    public int category(int i) {
        return cells[i * 4 + 3];
    }
}
