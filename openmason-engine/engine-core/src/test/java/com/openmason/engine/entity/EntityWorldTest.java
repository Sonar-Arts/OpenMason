package com.openmason.engine.entity;

import org.joml.Vector3f;
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
        world = EntityWorld.create(8.0f);
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

    private record Ref(long handle, int type, Vector3f pos, float w, float h, float l, float leg, int flags) {
    }

    @Test
    void queriesMatchTheJavaReference() {
        Random random = new Random(42);
        List<Ref> refs = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            int type = random.nextBoolean() ? COW : ARROW;
            long h = world.spawn(type, 0, 0, 0, 0, 0);
            refs.add(new Ref(h, type,
                new Vector3f(random.nextFloat() * 120 - 60, random.nextFloat() * 30 - 15, random.nextFloat() * 120 - 60),
                0.1f + random.nextFloat() * 2, 0.1f + random.nextFloat() * 2, 0.1f + random.nextFloat() * 2,
                random.nextInt(3) == 0 ? random.nextFloat() : 0.0f, random.nextInt(4)));
        }
        world.commitSpawns(out);
        for (int i = 0; i < refs.size(); i++) {
            Ref r = refs.get(i);
            world.body(i, r.handle, r.pos.x, r.pos.y, r.pos.z, 0, r.w, r.h, r.l, r.leg, r.flags);
        }
        assertEquals(300, world.syncBodies(300));

        for (int trial = 0; trial < 100; trial++) {
            Vector3f c = new Vector3f(random.nextFloat() * 120 - 60, random.nextFloat() * 30 - 15, random.nextFloat() * 120 - 60);
            float radius = random.nextFloat() * 20;
            EntityFilter f = new EntityFilter(trial % 3 == 0 ? -1 : (trial % 2 == 0 ? COW : ARROW),
                trial % 4 == 0 ? ALIVE : 0, trial % 5 == 0 ? LIVING : 0, refs.get(trial).handle);

            // getEntitiesInRange: position.distanceSquared(center) <= r*r
            List<Long> expected = new ArrayList<>();
            for (Ref r : refs) {
                if (accepts(f, r) && r.pos.distanceSquared(c) <= radius * radius) expected.add(r.handle);
            }
            world.queryRadius(c.x, c.y, c.z, radius, f, out);
            assertEquals(expected, boxed(out), "radius trial " + trial);

            // getNearestEntity: position.distance(p) < nearest, first wins
            long nearest = 0;
            float best = Float.MAX_VALUE;
            for (Ref r : refs) {
                float d = r.pos.distance(c);
                if (accepts(f, r) && d < best) {
                    best = d;
                    nearest = r.handle;
                }
            }
            assertEquals(nearest, world.queryNearest(c.x, c.y, c.z, Float.POSITIVE_INFINITY, f), "nearest " + trial);

            // chunk column: x/z half-open, y untested
            int cx = Math.floorDiv((int) Math.floor(c.x), 16) * 16;
            int cz = Math.floorDiv((int) Math.floor(c.z), 16) * 16;
            expected.clear();
            for (Ref r : refs) {
                if (accepts(f, r) && r.pos.x >= cx && r.pos.x < cx + 16 && r.pos.z >= cz && r.pos.z < cz + 16) {
                    expected.add(r.handle);
                }
            }
            world.queryBox(cx, Float.NEGATIVE_INFINITY, cz, cx + 16, Float.POSITIVE_INFINITY, cz + 16, f, out);
            assertEquals(expected, boxed(out), "box " + trial);

            // body overlap, as Entity.BoundingBox.intersects
            expected.clear();
            float[] box = {c.x - 3, c.y - 3, c.z - 3, c.x + 3, c.y + 3, c.z + 3};
            for (Ref r : refs) {
                boolean hit = r.pos.x - r.w / 2.0f < box[3] && r.pos.x + r.w / 2.0f > box[0]
                    && r.pos.y - r.leg < box[4] && r.pos.y + r.h > box[1]
                    && r.pos.z - r.l / 2.0f < box[5] && r.pos.z + r.l / 2.0f > box[2];
                if (accepts(f, r) && hit) expected.add(r.handle);
            }
            world.queryAabb(box[0], box[1], box[2], box[3], box[4], box[5], f, out);
            assertEquals(expected, boxed(out), "aabb " + trial);
        }
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
        float[] body = new float[8];
        assertTrue(world.bodyOf(h, body));
        assertArrayEquals(new float[]{1, 2, 3, 90, 1.1f, 0.4f, 1.3f, 0.62f}, body);
        assertEquals(ALIVE | LIVING, world.bodyFlags(h));
        world.body(0, h, 5, 6, 7, 45, 2, 2, 2, 0, ALIVE);
        assertEquals(1, world.syncBodies(1));
        assertTrue(world.bodyOf(h, body));
        assertArrayEquals(new float[]{5, 6, 7, 45, 2, 2, 2, 0}, body);
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
