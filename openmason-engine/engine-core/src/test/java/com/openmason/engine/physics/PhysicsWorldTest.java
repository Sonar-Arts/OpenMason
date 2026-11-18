package com.openmason.engine.physics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Cenda physics ({@code cp_*}) through its FFM binding, with no game in sight: the fill upcall and
 * the cache it feeds, invalidation from another thread, swept movement and stepping, raycasts with
 * dynamic boxes, sweeps, fluids, the drop helpers and a source that throws; and 64-bit coordinates
 * (CP ABI 2, OpenMason #142): absolute {@code long} cells, double positions relative to a frame origin
 * moved to +-2^40, and far positions without rebasing. The native suite
 * ({@code cenda/tests/physics_world_test.cpp}) covers the geometry in depth; this checks marshalling.
 */
class PhysicsWorldTest {

    private static final int SOLID = 1;
    private static final int PICK = 2;
    private static final int DOOR = 4;
    private static final int WATER = 1;
    private static final int FULL = 1;
    private static final int HALF = 2;
    private static final int FLOWER = 3;

    private static final long FAR = 1L << 40;

    private record Cell(long x, long y, long z) {
    }

    /** A sparse host world: (shape, fluid) per ABSOLUTE cell. */
    private final Map<Cell, int[]> cells = new HashMap<>();
    private final AtomicInteger fills = new AtomicInteger();
    private volatile boolean throwOnFill;
    private PhysicsWorld world;

    private static Cell key(long x, long y, long z) {
        return new Cell(x, y, z);
    }

    private void set(long x, long y, long z, int shape, int fluid) {
        cells.put(key(x, y, z), new int[] {shape, fluid});
    }

    private boolean fill(long x0, long y0, long z0, int sx, int sy, int sz, short[] shapes, short[] fluids) {
        fills.incrementAndGet();
        if (throwOnFill) {
            throw new IllegalStateException("source failure (expected by the test)");
        }
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int[] c = cells.get(key(x0 + x, y0 + y, z0 + z));
                    if (c != null) {
                        int i = (y * sz + z) * sx + x;
                        shapes[i] = (short) c[0];
                        fluids[i] = (short) c[1];
                    }
                }
            }
        }
        return true;
    }

    @BeforeEach
    void setUp() {
        assumeTrue(CendaPhysics.isAvailable(), () -> "Cenda physics not built: " + CendaPhysics.unavailableReason());
        world = PhysicsWorld.create(this::fill, 4, 0, 0);
        world.defineShape(FULL, new float[] {0, 0, 0, 1, 1, 1}, SOLID | PICK, 0);
        world.defineShape(HALF, new float[] {0, 0, 0, 1, 0.5f, 1}, SOLID | PICK, PhysicsWorld.SHAPE_PARTIAL);
        world.defineShape(FLOWER, new float[] {0.3f, 0, 0.3f, 0.7f, 0.6f, 0.7f}, PICK, 0);
        world.defineFluid(1, 0.875f, 0, 0, 0, WATER);
        world.defineFluid(2, 0.5f, 2.5f, 0, 0, WATER);
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                set(x, 0, z, FULL, 0);
            }
        }
    }

    @AfterEach
    void tearDown() {
        if (world != null) {
            world.close();
        }
    }

    private static PhysicsBody player(double x, double y, double z) {
        PhysicsBody b = new PhysicsBody().box(0.3f, 0.3f, 0f, 1.8f).steps(0.5f, 1.0f).position(x, y, z);
        b.mask = SOLID;
        b.flags = PhysicsBody.ON_GROUND;
        return b;
    }

    @Test
    void aFallingBodyLandsOnTheFloorTheSourceDescribes() {
        PhysicsBody b = player(0.5f, 10f, 0.5f);
        b.flags = 0;
        b.vy = -300f;
        world.move(b, 0.05f);
        assertEquals(1.0, b.y, 1e-4);
        assertEquals(0f, b.vy);
        assertTrue(b.onGround());
        assertTrue(b.hasContact(PhysicsBody.CONTACT_GROUND));
        assertTrue(fills.get() > 0);
    }

    @Test
    void cellsAreCachedUntilInvalidatedEvenFromAnotherThread() throws Exception {
        assertEquals(FULL, world.cellShape(2, 0, 2));
        int afterFirst = fills.get();
        assertEquals(FULL, world.cellShape(3, 0, 3));
        assertEquals(afterFirst, fills.get(), "same brick: no second fill");

        cells.remove(key(2, 0, 2));
        assertEquals(FULL, world.cellShape(2, 0, 2), "stale until invalidated");
        Thread other = new Thread(() -> world.invalidateCell(2, 0, 2));
        other.start();
        other.join();
        assertEquals(0, world.cellShape(2, 0, 2));

        PhysicsStats stats = world.stats();
        assertTrue(stats.bricks() >= 1);
        assertEquals(fills.get(), stats.fills());

        set(2, 0, 2, HALF, 0);
        world.invalidateAll();
        assertEquals(HALF, world.cellShape(2, 0, 2));
    }

    @Test
    void stepsUpPartialShapesAndStopsAtWalls() {
        set(2, 1, 0, HALF, 0);
        set(2, 1, 3, FULL, 0);
        world.invalidateAll();
        PhysicsBody step = player(1.5f, 1f, 0.5f);
        step.vx = 10f;
        world.move(step, 0.1f);
        assertEquals(1.5, step.y, 1e-4);
        assertTrue(step.hasContact(PhysicsBody.CONTACT_STEPPED));

        PhysicsBody wall = player(1.5f, 1f, 3.5f);
        wall.vx = 10f;
        world.move(wall, 0.1f);
        assertEquals(1.7, wall.x, 1e-4);
        assertEquals(0f, wall.vx);
        assertTrue(wall.hasContact(PhysicsBody.CONTACT_POS_X));
    }

    @Test
    void stepIntegratesGravityAndFloatsInWater() {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = 1; y <= 3; y++) {
                    set(x, y, z, 0, 1);
                }
            }
        }
        world.invalidateAll();
        PhysicsIntegrator params = new PhysicsIntegrator(-40f, 5f, 0.55f, 1.2f, 2.2f, WATER);
        PhysicsBody mob = new PhysicsBody().box(0.4f, 0.4f, 0.3f, 0.7f).steps(0.5f, 0.5f).position(0.5f, 1.3f, 0.5f);
        mob.mask = SOLID;
        mob.flags = PhysicsBody.ON_GROUND | PhysicsBody.KEEP_VELOCITY;
        FluidSample sample = new FluidSample();
        for (int i = 0; i < 400; i++) {
            world.step(mob, 0.05f, params, sample);
        }
        // Feet float 55% of the 1.0 body height under the 3.875 surface.
        assertEquals(3.875 - 0.55, mob.y - mob.below, 0.05);
        assertTrue(sample.submersion > 0.4f && sample.submersion < 0.7f);

        float vy = PhysicsWorld.buoyancyVy(0f, 0.55f, 0.05f, params);
        assertEquals(0f, vy, 1e-5f, "neutral at the float line");
    }

    @Test
    void measureFluidReportsSurfaceAndFlow() {
        set(0, 1, 0, 0, 1);
        set(1, 1, 0, 0, 2);
        world.invalidateAll();
        FluidSample s = world.measureFluid(0.5f, 1.0f, 0.5f, 1.8f, 0.3f, 1.0f, 1.8f, WATER, new FluidSample());
        assertEquals(1.875, s.surfaceY, 1e-5);
        assertEquals(0.875f / 1.8f, s.submersion, 1e-5f);
        FluidSample flow = world.measureFluid(0.9f, 1.0f, 0.5f, 1.0f, 0.5f, 1.0f, 1.0f, WATER, new FluidSample());
        assertTrue(flow.flowSamples > 0 && flow.flowX > 0f);
        FluidSample dry = world.measureFluid(4.5f, 1.0f, 4.5f, 1.8f, 0.3f, 1.0f, 1.8f, WATER, new FluidSample());
        assertEquals(0f, dry.submersion);
        assertTrue(Double.isNaN(dry.surfaceY));
    }

    @Test
    void raycastsSeeShapesDynamicBoxesAndFluids() {
        set(5, 1, 0, FULL, 0);
        set(3, 1, 0, FLOWER, 0);
        set(2, 1, 0, 0, 1);
        world.invalidateAll();
        RayHit hit = new RayHit();
        assertTrue(world.raycast(0.5f, 1.5f, 0.5f, 1, 0, 0, 10f, SOLID, 0, hit));
        assertEquals(RayHit.KIND_CELL, hit.kind);
        assertEquals(5, hit.x);
        assertEquals(RayHit.FACE_NEG_X, hit.face);
        assertEquals(4.5, hit.distance, 1e-4);

        assertTrue(world.raycast(0.5f, 1.5f, 0.5f, 1, 0, 0, 10f, SOLID | PICK, 0, hit));
        assertEquals(3, hit.x, "the flower's pick box");
        assertTrue(world.raycast(0.5f, 1.5f, 0.5f, 1, 0, 0, 10f, SOLID, WATER, hit));
        assertEquals(RayHit.KIND_FLUID, hit.kind);
        assertEquals(2, hit.x);

        int door = world.addBox(4.0f, 1.0f, 0.0f, 4.2f, 3.0f, 1.0f, DOOR, 0, 4242L);
        assertTrue(world.raycast(0.5f, 1.5f, 0.5f, 1, 0, 0, 10f, SOLID | DOOR, 0, hit));
        assertEquals(RayHit.KIND_BOX, hit.kind);
        assertEquals(4242L, hit.user);
        assertEquals(door, hit.id);
        world.setBox(door, 8.0f, 1.0f, 0.0f, 8.2f, 3.0f, 1.0f, DOOR, 0);
        assertTrue(world.raycast(0.5f, 1.5f, 0.5f, 1, 0, 0, 10f, DOOR, 0, hit));
        assertEquals(8, hit.x);
        assertTrue(world.removeBox(door));
        assertFalse(world.removeBox(door));
        assertFalse(world.raycast(0.5f, 1.5f, 0.5f, 1, 0, 0, 10f, DOOR, 0, hit));
        assertFalse(hit.hit());
    }

    @Test
    void sweepsAndDropHelpers() {
        set(4, 1, 0, FULL, 0);
        set(0, 1, 2, HALF, 0);
        world.invalidateAll();
        RayHit hit = new RayHit();
        assertTrue(world.sweep(0.5f, 1.5f, 0.5f, 10.5f, 1.5f, 0.5f, 0.15f, 0.15f, 0.15f, SOLID, hit));
        assertEquals(3.85, hit.px, 1e-3);
        assertFalse(world.sweep(0.5f, 1.5f, 0.5f, 0.5f, 1.5f, -4.5f, 0.15f, 0.15f, 0.15f, SOLID, hit));

        assertEquals(0.5f, world.cellTop(0, 1, 2, SOLID), 1e-6f);
        assertEquals(0f, world.cellTop(1, 1, 2, SOLID));
        assertTrue(world.overlaps(4.2f, 1.2f, 0.2f, 4.4f, 1.4f, 0.4f, SOLID));
        assertFalse(world.overlaps(3.0f, 1.0f, 0.0f, 4.0f, 2.0f, 1.0f, SOLID), "touching is not overlapping");

        long[] candidates = {4, 1, 0, 3, 1, 0, 5, 1, 0};
        assertEquals(2, world.nearestOpenCell(candidates, 3, true, 5.5f, 1.5f, 0.5f, SOLID));
        assertEquals(1, world.nearestOpenCell(candidates, 3, false, 0, 0, 0, SOLID));

        double[] boxes = {5, 0, 0, 6, 1, 1, 2, 0, 0, 3, 1, 1};
        double[] t = new double[2];
        assertEquals(1, world.rayBoxes(0, 0.5, 0.5, 10, 0, 0, 1, boxes, 2, 0, t));
        assertEquals(0.5, t[0], 1e-12);
        assertEquals(0.2, t[1], 1e-12);
    }

    @Test
    void aFarFrameBehavesExactlyAsTheOrigin() {
        // The same floor, wall and water at +-2^40 (negative Y), with the frame origin moved there:
        // local positions, and so every answer, are those of the origin.
        long ox = FAR, oy = -FAR - 12345, oz = -FAR * 3;
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                set(ox + x, oy, oz + z, FULL, 0);
            }
        }
        set(ox + 2, oy + 1, oz + 3, FULL, 0);
        set(ox, oy + 1, oz, 0, 1);
        world.setOrigin(ox, oy, oz);
        assertEquals(ox, world.originX());
        assertEquals(oz, world.originZ());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new long[] {ox, oy, oz}, world.nativeOrigin());

        PhysicsBody b = player(0.5, 10, -2.5);
        b.flags = 0;
        b.vy = -300f;
        world.move(b, 0.05f);
        assertEquals(1.0, b.y, 0.0);
        assertTrue(b.onGround());

        PhysicsBody wall = player(1.5, 1, 3.5);
        wall.vx = 10f;
        world.move(wall, 0.1f);
        assertEquals(1.7, wall.x, 1e-6);

        RayHit hit = new RayHit();
        assertTrue(world.raycast(-0.5, 1.5, 3.5, 1, 0, 0, 10, SOLID, 0, hit));
        assertEquals(ox + 2, hit.x);
        assertEquals(oy + 1, hit.y);
        assertEquals(oz + 3, hit.z);
        assertEquals(2.5, hit.distance, 1e-9);
        assertEquals(FULL, world.cellShape(ox + 2, oy + 1, oz + 3));
        assertEquals(1.0f, world.cellTop(ox + 2, oy + 1, oz + 3, SOLID));
        FluidSample s = world.measureFluid(0.5, 1.0, 0.5, 1.8f, 0.3f, 1.0, 1.8f, WATER, new FluidSample());
        assertEquals(1.875, s.surfaceY, 1e-9);
        long[] candidates = {ox + 2, oy + 1, oz + 3, ox + 2, oy + 2, oz + 3};
        assertEquals(1, world.nearestOpenCell(candidates, 2, false, 0, 0, 0, SOLID));
    }

    @Test
    void movingTheOriginKeepsTheCacheAndDynamicBoxes() {
        assertEquals(FULL, world.cellShape(1, 0, 1));
        int door = world.addBox(4.0, 1.0, 0.0, 4.2, 3.0, 1.0, DOOR, 0, 7L);
        int before = fills.get();
        world.setOrigin(-37, 1_000_003, 12);
        assertEquals(FULL, world.cellShape(1, 0, 1));
        // A body standing on the (absolute) floor, in the new frame.
        PhysicsBody b = player(0.5 + 37, 1.0 - 1_000_003, 0.5 - 12);
        world.move(b, 0.05f);
        assertTrue(b.onGround());
        assertEquals(before, fills.get(), "nothing refilled");
        RayHit hit = new RayHit();
        assertTrue(world.raycast(0.5 + 37, 1.5 - 1_000_003, 0.5 - 12, 1, 0, 0, 10, DOOR, 0, hit));
        assertEquals(door, hit.id);
        assertEquals(4, hit.x, "absolute cell");
        assertEquals(3.5, hit.distance, 1e-9);
    }

    @Test
    void farPositionsWithoutRebasingStayWithinAThousandthOfABlock() {
        long fx = FAR, fy = -FAR;
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                set(fx + x, fy, fx + z, FULL, 0);
            }
        }
        double top = fy + 1.0;
        PhysicsBody b = player(fx + 0.5, top + 4, fx + 0.5);
        b.flags = 0;
        b.vy = -100f;
        world.move(b, 0.1f);
        assertEquals(top, b.y, 0.0);
        for (int i = 0; i < 120; i++) {
            world.move(b, 1f / 60f);
        }
        assertEquals(top, b.y, 0.0, "a resting body never creeps");
        b.vx = 2f;
        world.move(b, 0.25f);
        assertEquals(fx + 1.0, b.x, 1e-3);
        RayHit hit = new RayHit();
        assertTrue(world.raycast(fx + 0.5, top + 3, fx + 0.5, 0, -1, 0, 10, SOLID, 0, hit));
        assertEquals(fy, hit.y);
        assertEquals(3.0, hit.distance, 1e-3);
    }

    @Test
    void aThrowingSourceReadsAsUnloadedAndIsRetried() {
        throwOnFill = true;
        assertEquals(0, world.cellShape(-20, 0, -20), "unloaded shape");
        throwOnFill = false;
        set(-20, 0, -20, FULL, 0);
        assertEquals(FULL, world.cellShape(-20, 0, -20), "nothing was cached for the failed region");
    }

    @Test
    void badInputIsRefusedAndClosingIsFinal() {
        assertThrows(IllegalArgumentException.class, () -> world.defineShape(0, new float[] {0, 0, 0, 1, 1, 1}, SOLID, 0));
        assertThrows(IllegalArgumentException.class, () -> world.defineShape(5, new float[6 * 17], SOLID, 0));
        PhysicsBody bad = player(Double.NaN, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> world.move(bad, 0.05f));
        assertThrows(IllegalArgumentException.class, () -> PhysicsWorld.create(this::fill, 9, 0, 0));
        world.close();
        assertTrue(world.isClosed());
        world.invalidateCell(0, 0, 0); // any-thread calls are no-ops after close
        assertThrows(IllegalStateException.class, () -> world.cellShape(0, 0, 0));
        world = null;
    }
}
