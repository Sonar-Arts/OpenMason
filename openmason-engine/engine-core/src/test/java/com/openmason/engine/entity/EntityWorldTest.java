package com.openmason.engine.entity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Cenda entity model ({@code ce_*}) through its FFM binding, with no game in sight: lifecycle and
 * removal safety, live order, type descriptors, spatial queries against a Java reference written
 * the way game code computes distances (JOML), clip clocks, off-thread spawns and closing.
 */
class EntityWorldTest {

    private static final int COW = 3;
    private static final int ARROW = 7;
    private static final int ALIVE = 1;
    private static final int LIVING = 2;

    private EntityWorld world;
    private final HandleList out = new HandleList();

    @BeforeEach
    void setUp() {
        assumeTrue(CendaEntities.isAvailable(), () -> "Cenda entities not built: " + CendaEntities.unavailableReason());
        world = EntityWorld.create(8);
        world.defineType(COW, new EntityTypeDesc(1.1f, 0.4f, 1.3f, 0.62f, 180.0f, LIVING, "game:cow"));
        world.defineType(ARROW, new EntityTypeDesc(0.05f, 0.1f, 0.5f, 0.0f, 0.0f, 0, null));
    }

    @AfterEach
    void tearDown() {
        if (world != null) {
            world.close();
        }
    }

    @Test
    void typeDescriptorsRoundTrip() {
        EntityTypeDesc cow = world.type(COW);
        assertEquals(new EntityTypeDesc(1.1f, 0.4f, 1.3f, 0.62f, 180.0f, LIVING, "game:cow"), cow);
        assertNull(world.type(ARROW).modelId());
        assertNull(world.type(4), "undefined type");
        assertThrows(IllegalArgumentException.class,
            () -> world.defineType(EntityWorld.MAX_TYPES, world.type(COW)));
        assertEquals(0L, world.spawn(4, 0, 0, 0, 0, 0), "undefined type refuses spawn");
    }

    @Test
    void lifecycleIsDeferredAndOrdered() {
        long a = world.spawn(COW, ALIVE, 1, 2, 3, 0);
        long b = world.spawn(COW, ALIVE, 4, 5, 6, 0);
        long c = world.spawn(ARROW, ALIVE, 7, 8, 9, 0);
        assertEquals(EntityWorld.STATE_PENDING, world.state(a));
        assertEquals(3, world.pendingCount());
        assertEquals(0, world.liveCount());

        assertArrayEquals(new long[]{a, b, c}, world.pending());
        assertTrue(world.despawn(b), "despawning a pending entity cancels it");
        assertEquals(EntityWorld.STATE_NONE, world.state(b));
        assertArrayEquals(new long[]{a, c}, world.pending(), "cancelled leaves the pending listing");

        world.commitSpawns(out);
        assertArrayEquals(new long[]{a, c}, out.toArray(), "commit order = spawn order, cancelled skipped");
        assertEquals(COW, world.typeOf(a));
        assertEquals(ARROW, world.typeOf(c));

        // Remove while "iterating": still in the world, still answers queries, until the commit.
        world.live(out);
        for (int i = 0; i < out.size(); i++) {
            world.despawn(out.get(i));
        }
        assertEquals(EntityWorld.STATE_REMOVING, world.state(a));
        assertFalse(world.despawn(a), "second despawn is a no-op");
        world.queryAll(EntityFilter.ANY, out);
        assertEquals(2, out.size());

        world.commitDespawns(out);
        assertArrayEquals(new long[]{a, c}, out.toArray());
        assertEquals(EntityWorld.STATE_RETIRED, world.state(a));
        assertEquals(0, world.liveCount());

        world.commitDespawns(out);
        assertEquals(EntityWorld.STATE_NONE, world.state(a), "retired handles go stale one commit later");
        long d = world.spawn(COW, 0, 0, 0, 0, 0);
        assertEquals(EntityWorld.slotOf(c), EntityWorld.slotOf(d), "slot reused (free list is LIFO)");
        assertNotEquals(c, d, "with a new generation");
        assertEquals(EntityWorld.STATE_NONE, world.state(c));
    }

    @Test
    void largeBatchesSpillPastTheOutputBuffer() {
        List<Long> spawned = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            spawned.add(world.spawn(i % 2 == 0 ? COW : ARROW, ALIVE, i, 0, 0, 0));
        }
        world.commitSpawns(out);
        assertEquals(1000, out.size());
        world.live(out);
        for (int i = 0; i < 1000; i++) {
            assertEquals(spawned.get(i), out.get(i));
        }
        world.queryAll(EntityFilter.ofType(ARROW), out);
        assertEquals(500, out.size());
    }

    private record Ref(long handle, int type, double x, double y, double z, float w, float h, float l, float leg,
                       int flags) {
        double distanceSquared(double cx, double cy, double cz) {
            // The native rule: dx*dx + (dy*dy + dz*dz) in double.
            double dx = x - cx, dy = y - cy, dz = z - cz;
            return dx * dx + (dy * dy + dz * dz);
        }
    }

    @Test
    void queriesMatchTheReference() {
        queriesMatchTheReference(0, 0, 0, 0.0);
    }

    @Test
    void queriesMatchTheReferenceInAFarFrame() {
        queriesMatchTheReference(1L << 40, -(1L << 40) - 7, -(3L << 40), 0.0);
    }

    @Test
    void queriesMatchTheReferenceAtFarPositionsWithoutRebasing() {
        queriesMatchTheReference(0, 0, 0, (double) (1L << 40));
    }

    /** origin: the world's frame; shift: added to every position (x, z up and y down by it). */
    private void queriesMatchTheReference(long ox, long oy, long oz, double shift) {
        world.setOrigin(ox, oy, oz);
        assertEquals(ox, world.originX());
        Random random = new Random(42);
        List<Ref> refs = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            int type = random.nextBoolean() ? COW : ARROW;
            long h = world.spawn(type, 0, 0, 0, 0, 0);
            refs.add(new Ref(h, type, shift + (random.nextFloat() * 120 - 60), -shift + (random.nextFloat() * 30 - 15),
                shift + (random.nextFloat() * 120 - 60),
                0.1f + random.nextFloat() * 2, 0.1f + random.nextFloat() * 2, 0.1f + random.nextFloat() * 2,
                random.nextInt(3) == 0 ? random.nextFloat() : 0.0f, random.nextInt(4)));
        }
        world.commitSpawns(out);
        for (int i = 0; i < refs.size(); i++) {
            Ref r = refs.get(i);
            world.body(i, r.handle, r.x, r.y, r.z, 0, r.w, r.h, r.l, r.leg, r.flags);
        }
        assertEquals(300, world.syncBodies(300));

        for (int trial = 0; trial < 100; trial++) {
            double cx = shift + (random.nextFloat() * 120 - 60);
            double cy = -shift + (random.nextFloat() * 30 - 15);
            double cz = shift + (random.nextFloat() * 120 - 60);
            double radius = random.nextFloat() * 20;
            EntityFilter f = new EntityFilter(trial % 3 == 0 ? -1 : (trial % 2 == 0 ? COW : ARROW),
                trial % 4 == 0 ? ALIVE : 0, trial % 5 == 0 ? LIVING : 0, refs.get(trial).handle);

            List<Long> expected = new ArrayList<>();
            for (Ref r : refs) {
                if (accepts(f, r) && r.distanceSquared(cx, cy, cz) <= radius * radius) expected.add(r.handle);
            }
            world.queryRadius(cx, cy, cz, radius, f, out);
            assertEquals(expected, boxed(out), "radius trial " + trial);

            // nearest: sqrt'd distance < nearest, first wins
            long nearest = 0;
            double best = Double.MAX_VALUE;
            for (Ref r : refs) {
                double d = Math.sqrt(r.distanceSquared(cx, cy, cz));
                if (accepts(f, r) && d < best) {
                    best = d;
                    nearest = r.handle;
                }
            }
            assertEquals(nearest, world.queryNearest(cx, cy, cz, Double.POSITIVE_INFINITY, f), "nearest " + trial);

            // a column: x/z half-open, y untested
            double bx = Math.floor(cx / 16) * 16;
            double bz = Math.floor(cz / 16) * 16;
            expected.clear();
            for (Ref r : refs) {
                if (accepts(f, r) && r.x >= bx && r.x < bx + 16 && r.z >= bz && r.z < bz + 16) {
                    expected.add(r.handle);
                }
            }
            world.queryBox(bx, Double.NEGATIVE_INFINITY, bz, bx + 16, Double.POSITIVE_INFINITY, bz + 16, f, out);
            assertEquals(expected, boxed(out), "box " + trial);

            // a cube: all three axes half-open (the 3D grid)
            double by = Math.floor(cy / 16) * 16;
            expected.clear();
            for (Ref r : refs) {
                if (accepts(f, r) && r.x >= bx && r.x < bx + 16 && r.y >= by && r.y < by + 16 && r.z >= bz
                    && r.z < bz + 16) {
                    expected.add(r.handle);
                }
            }
            world.queryBox(bx, by, bz, bx + 16, by + 16, bz + 16, f, out);
            assertEquals(expected, boxed(out), "cube " + trial);

            // body overlap, as Entity.BoundingBox.intersects (in double)
            expected.clear();
            double[] box = {cx - 3, cy - 3, cz - 3, cx + 3, cy + 3, cz + 3};
            for (Ref r : refs) {
                boolean hit = r.x - r.w / 2.0 < box[3] && r.x + r.w / 2.0 > box[0]
                    && r.y - r.leg < box[4] && r.y + r.h > box[1]
                    && r.z - r.l / 2.0 < box[5] && r.z + r.l / 2.0 > box[2];
                if (accepts(f, r) && hit) expected.add(r.handle);
            }
            world.queryAabb(box[0], box[1], box[2], box[3], box[4], box[5], f, out);
            assertEquals(expected, boxed(out), "aabb " + trial);
        }
    }

    @Test
    void movingTheOriginShiftsBodiesIntoTheNewFrame() {
        long h = world.spawn(COW, ALIVE, 1.5, 2.0, 3.5, 0);
        world.commitSpawns(out);
        world.setOrigin(-(1L << 40), 4097, (1L << 40) + 3);
        double[] body = new double[8];
        assertTrue(world.bodyOf(h, body));
        assertEquals(1.5 + (1L << 40), body[0]);
        assertEquals(2.0 - 4097, body[1]);
        assertEquals(3.5 - (1L << 40) - 3, body[2]);
        world.queryRadius(body[0], body[1], body[2], 0.1, null, out);
        assertEquals(1, out.size());
        world.queryRadius(1.5, 2.0, 3.5, 0.1, null, out);
        assertEquals(0, out.size(), "no longer at the old local place");
    }

    private static boolean accepts(EntityFilter f, Ref r) {
        return (f.typeId() < 0 || r.type == f.typeId()) && (r.flags & f.requireFlags()) == f.requireFlags()
            && (r.flags & f.excludeFlags()) == 0 && r.handle != f.exclude();
    }

    private static List<Long> boxed(HandleList list) {
        List<Long> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) result.add(list.get(i));
        return result;
    }

    @Test
    void bodiesStartFromTheTypeAndFollowSyncs() {
        long h = world.spawn(COW, ALIVE | LIVING, 1, 2, 3, 90);
        double[] body = new double[8];
        assertTrue(world.bodyOf(h, body));
        assertArrayEquals(new double[]{1, 2, 3, 90, 1.1f, 0.4f, 1.3f, 0.62f}, body);
        assertEquals(ALIVE | LIVING, world.bodyFlags(h));
        world.body(0, h, 5, 6, 7, 45, 2, 2, 2, 0, ALIVE);
        assertEquals(1, world.syncBodies(1));
        assertTrue(world.bodyOf(h, body));
        assertArrayEquals(new double[]{5, 6, 7, 45, 2, 2, 2, 0}, body);
        assertEquals(ALIVE, world.bodyFlags(h));
    }

    @Test
    void clipClocksAddLikeJavaFloats() {
        long h = world.spawn(COW, 0, 0, 0, 0, 0);
        assertEquals(ClipState.INITIAL, world.clip(h));
        world.clipSet(h, 2, 0);
        float total = 0f;
        float state = 0f;
        for (int i = 0; i < 101; i++) {
            float dt = 0.016f + (i % 7) * 0.001f;
            world.clipAdvance(h, dt, dt);
            total += dt;
            state += dt;
        }
        assertEquals(total, world.clipTime(h), 0.0f, "looping: continuous clock, bit-exact");
        world.clipSet(h, 2, ClipState.ONE_SHOT);
        assertEquals(state, world.clipTime(h), 0.0f, "same clip keeps the state clock");
        world.clipSet(h, 5, ClipState.ONE_SHOT);
        assertEquals(0.0f, world.clipTime(h), "a new clip restarts the state clock");
        assertTrue(world.clipPut(h, new ClipState(9, 0, 1.5f, 4.0f)));
        assertEquals(new ClipState(9, 0, 1.5f, 4.0f), world.clip(h));

        world.despawn(h); // cancels the pending entity
        assertTrue(Float.isNaN(world.clipTime(h)));
        assertNull(world.clip(h));
        assertFalse(world.clipSet(h, 1, 0));
    }

    @Test
    void spawnsFromOtherThreadsAreCommittedByTheOwner() throws Exception {
        int threads = 4;
        int each = 2000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            int id = t;
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < each; i++) {
                    world.spawn(COW, 0, id, i, 0, 0);
                }
                return null;
            });
        }
        start.countDown();
        int committed = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (committed < threads * each && System.nanoTime() < deadline) {
            world.commitSpawns(out);
            committed += out.size();
            world.queryRadius(0, 0, 0, 5, EntityFilter.ANY, out);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(threads * each, committed);
        assertEquals(threads * each, world.liveCount());
    }

    @Test
    void closedWorldRefusesWork() {
        EntityWorld other = EntityWorld.create(0);
        other.close();
        other.close(); // idempotent
        assertTrue(other.isClosed());
        assertEquals(0L, other.spawn(0, 0, 0, 0, 0, 0), "off-thread spawn after close is dropped");
        assertFalse(other.despawn(1L));
        assertThrows(IllegalStateException.class, other::liveCount);
    }
}
