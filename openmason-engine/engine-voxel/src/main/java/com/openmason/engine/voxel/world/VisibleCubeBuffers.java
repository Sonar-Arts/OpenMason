package com.openmason.engine.voxel.world;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

/**
 * Caller-owned, reusable output of {@link CubeWorld#visibleCubes(long, long, long, int, float[],
 * VisibleCubeBuffers)}: native int64 triple lists for the visible PRESENT cubes and the UNKNOWN missing
 * ones, plus the native plane block. One instance per caller (the camera pass); a frame's walk writes into
 * it without allocating. The lists grow (and stay grown) only when a walk reports more cubes than they hold
 * — the native call answers "needed" and the walk is retried once with room — so allocation stops after
 * the first few frames.
 *
 * <p>Read the results through the indexed accessors ({@link #visibleX(int)}...), the raw segments
 * ({@link #visibleSegment()}, x y z per cube, native byte order) or the cached {@link LongBuffer} views
 * ({@link #visibleBuffer()}; position 0, limit = 3 * count, rebuilt only on growth). Contents are valid
 * until the next walk into this instance. Not thread-safe.
 */
public final class VisibleCubeBuffers {

    private MemorySegment visible;
    private MemorySegment missing;
    private LongBuffer visibleView;
    private LongBuffer missingView;
    private final MemorySegment planes;
    private final MemorySegment missingCountOut;
    private int visibleCount;
    private int missingCount;
    private int grows;

    /** Room for {@code visibleCapacity} visible and {@code missingCapacity} missing cubes before the first growth. */
    public VisibleCubeBuffers(int visibleCapacity, int missingCapacity) {
        Arena arena = Arena.ofAuto();
        visible = arena.allocate(Math.max(1, visibleCapacity) * 24L, 8);
        missing = arena.allocate(Math.max(1, missingCapacity) * 24L, 8);
        planes = arena.allocate(24 * 4L, 8);
        missingCountOut = arena.allocate(8, 8);
        rebuildViews();
    }

    /** Defaults sized for a 16-cube render radius (grows on demand). */
    public VisibleCubeBuffers() {
        this(8192, 2048);
    }

    // ---- results ------------------------------------------------------------------------------------------

    /** Visible present cubes of the last walk. */
    public int visibleCount() {
        return visibleCount;
    }

    /** Unknown missing cubes of the last walk (all of them — the lists grow, they never truncate). */
    public int missingCount() {
        return missingCount;
    }

    public long visibleX(int i) {
        return visible.getAtIndex(ValueLayout.JAVA_LONG, 3L * i);
    }

    public long visibleY(int i) {
        return visible.getAtIndex(ValueLayout.JAVA_LONG, 3L * i + 1);
    }

    public long visibleZ(int i) {
        return visible.getAtIndex(ValueLayout.JAVA_LONG, 3L * i + 2);
    }

    public long missingX(int i) {
        return missing.getAtIndex(ValueLayout.JAVA_LONG, 3L * i);
    }

    public long missingY(int i) {
        return missing.getAtIndex(ValueLayout.JAVA_LONG, 3L * i + 1);
    }

    public long missingZ(int i) {
        return missing.getAtIndex(ValueLayout.JAVA_LONG, 3L * i + 2);
    }

    /** The visible triples (x, y, z per cube; {@link #visibleCount()} cubes are valid). */
    public MemorySegment visibleSegment() {
        return visible;
    }

    /** The missing triples (x, y, z per cube; {@link #missingCount()} cubes are valid). */
    public MemorySegment missingSegment() {
        return missing;
    }

    /** A cached view of the visible triples: position 0, limit {@code 3 * visibleCount()}. */
    public LongBuffer visibleBuffer() {
        return visibleView.limit(3 * visibleCount).position(0);
    }

    /** A cached view of the missing triples: position 0, limit {@code 3 * missingCount()}. */
    public LongBuffer missingBuffer() {
        return missingView.limit(3 * missingCount).position(0);
    }

    /** Times a list had to grow (diagnostics: stays constant once the walk size is steady). */
    public int growCount() {
        return grows;
    }

    // ---- CubeWorld side -----------------------------------------------------------------------------------

    MemorySegment planes(float[] src) {
        if (src == null) {
            return MemorySegment.NULL;
        }
        if (src.length < 24) {
            throw new IllegalArgumentException("planes needs 24 floats");
        }
        MemorySegment.copy(src, 0, planes, ValueLayout.JAVA_FLOAT, 0, 24);
        return planes;
    }

    MemorySegment visible() {
        return visible;
    }

    MemorySegment missing() {
        return missing;
    }

    long visibleCapacity() {
        return visible.byteSize() / 24;
    }

    long missingCapacity() {
        return missing.byteSize() / 24;
    }

    MemorySegment missingCountOut() {
        return missingCountOut;
    }

    void ensure(long visibleNeeded, long missingNeeded) {
        boolean grew = false;
        if (visibleNeeded > visibleCapacity()) {
            visible = Arena.ofAuto().allocate(Math.max(visibleNeeded + 256, visibleCapacity() * 3 / 2) * 24L, 8);
            grew = true;
        }
        if (missingNeeded > missingCapacity()) {
            missing = Arena.ofAuto().allocate(Math.max(missingNeeded + 256, missingCapacity() * 3 / 2) * 24L, 8);
            grew = true;
        }
        if (grew) {
            grows++;
            rebuildViews();
        }
    }

    void set(int visibleCount, int missingCount) {
        this.visibleCount = visibleCount;
        this.missingCount = missingCount;
    }

    private void rebuildViews() {
        visibleView = visible.asByteBuffer().order(ByteOrder.nativeOrder()).asLongBuffer();
        missingView = missing.asByteBuffer().order(ByteOrder.nativeOrder()).asLongBuffer();
    }
}
