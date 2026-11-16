package com.openmason.engine.wayfind.voxel;

import com.openmason.engine.nav.AirProfile;
import com.openmason.engine.nav.CendaNav;
import com.openmason.engine.nav.GroundProfile;
import com.openmason.engine.nav.NavLimits;
import com.openmason.engine.nav.NavPath;
import com.openmason.engine.nav.NavWorld;
import com.openmason.engine.physics.CendaPhysics;
import com.openmason.engine.physics.PhysicsWorld;
import com.openmason.engine.wayfind.AStar;
import com.openmason.engine.wayfind.CancelToken;
import com.openmason.engine.wayfind.SearchLimits;
import com.openmason.engine.wayfind.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The native Wayfind (Cenda {@code cn_*}, OpenMason #116) against the Java core it ports
 * ({@link AStar}, {@link GroundNavDomain}, {@link AirNavDomain}, {@link NavCellCache},
 * {@link PathSmoother}) over the same random worlds: every query must give the same status, the same
 * expansion count, the same cost and the same waypoints, bit for bit.
 *
 * <p>The worlds mix full blocks, partial tops (snow layers, half blocks, an odd 0.6 that exercises
 * the 1/1024 quantisation), water at two surface heights, hazards, overhangs and an unloaded region;
 * the Java side reads them through a {@link NavVolume}, the native side through a physics world
 * filled from the same map. The Java ground/air pipelines below are the ones Stonebreak's
 * {@code PathfindingService} ran before the port (snap start and goal, search, smooth, place).
 */
class NavParityTest {

    private static final int SOLID = 1;
    private static final int LAVA = 4;
    private static final int WATER = 1;
    private static final int MIN_Y = 0;
    private static final int MAX_Y = 256;
    /** x below this is "not loaded" (a whole 16-brick boundary, so physics sees the same region). */
    private static final int UNLOADED_BELOW_X = -16;

    /** shape id -> (top, hazard) */
    private static final float[] SHAPE_TOP = {0f, 1.0f, 0.125f, 0.25f, 0.5f, 0.6f, 1.0f};
    private static final boolean[] SHAPE_HAZARD = {false, false, false, false, false, false, true};
    private static final float[] FLUID_SURFACE = {0f, 0.875f, 0.5f};

    /** A sparse world: (shape, fluid) per cell. */
    private static final class World implements NavVolume {
        final Map<Long, int[]> cells = new HashMap<>();

        static long key(int x, int y, int z) {
            return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
        }

        void set(int x, int y, int z, int shape, int fluid) {
            cells.put(key(x, y, z), new int[] {shape, fluid});
        }

        int[] at(int x, int y, int z) {
            return cells.get(key(x, y, z));
        }

        @Override
        public int flags(int x, int y, int z) {
            if (y < MIN_Y || y >= MAX_Y || x < UNLOADED_BELOW_X) {
                return NavCell.UNKNOWN;
            }
            int[] c = at(x, y, z);
            if (c == null) {
                return NavCell.OPEN;
            }
            int flags = 0;
            if (SHAPE_HAZARD[c[0]]) {
                flags |= NavCell.HAZARD;
            } else if (c[0] != 0) {
                flags |= NavCell.SOLID;
            }
            if (c[1] != 0) {
                flags |= NavCell.LIQUID;
            }
            return flags;
        }

        @Override
        public float topSurface(int x, int y, int z) {
            int[] c = at(x, y, z);
            if (c == null) {
                return 0f;
            }
            if (c[0] != 0 && !SHAPE_HAZARD[c[0]]) {
                return SHAPE_TOP[c[0]];
            }
            return c[1] != 0 ? FLUID_SURFACE[c[1]] : 0f;
        }

        boolean fill(int x0, int y0, int z0, int sx, int sy, int sz, short[] shapes, short[] fluids) {
            if (x0 < UNLOADED_BELOW_X) {
                return false;
            }
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    for (int x = 0; x < sx; x++) {
                        int[] c = at(x0 + x, y0 + y, z0 + z);
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
    }

    /** Rolling terrain with partial tops, ponds, pillars, overhangs and a few hazards. */
    private static World terrain(long seed) {
        Random r = new Random(seed);
        World w = new World();
        int[][] height = new int[64][64];
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                double h = 64 + 3 * Math.sin((x + seed % 7) * 0.21) + 2 * Math.cos((z - seed % 5) * 0.17)
                    + r.nextInt(2);
                height[x][z] = (int) h;
            }
        }
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                int wx = x - 24;
                int wz = z - 32;
                int h = height[x][z];
                for (int y = 56; y < h; y++) {
                    w.set(wx, y, wz, 1, 0);
                }
                int roll = r.nextInt(100);
                if (roll < 8) {
                    w.set(wx, h, wz, 2 + r.nextInt(4), 0);        // a partial top
                } else if (roll < 10) {
                    w.set(wx, h, wz, 6, 0);                       // a hazard
                } else if (roll < 13) {
                    for (int y = h; y < h + 2 + r.nextInt(3); y++) {
                        w.set(wx, y, wz, 1, 0);                   // a pillar
                    }
                } else if (roll < 15) {
                    w.set(wx, h + 2, wz, 1, 0);                   // an overhang
                }
            }
        }
        // Two ponds: one deep with source-height water, one shallow and flowing.
        for (int x = 6; x < 12; x++) {
            for (int z = -4; z < 2; z++) {
                int top = 66;
                for (int y = top - 3; y < top; y++) {
                    w.set(x, y, z, 0, 1);
                }
                for (int y = top; y < top + 4; y++) {
                    w.cells.remove(World.key(x, y, z));
                }
            }
        }
        for (int x = -6; x < -2; x++) {
            for (int z = 8; z < 12; z++) {
                w.set(x, 66, z, 0, 2);
                for (int y = 67; y < 70; y++) {
                    w.cells.remove(World.key(x, y, z));
                }
            }
        }
        return w;
    }

    // ---- the Java pipelines (Stonebreak PathfindingService before #116) ---------------------------

    private record Route(int status, float[] points, int expansions, float cost) {
    }

    private static int nativeStatus(SearchResult.Status s) {
        return switch (s) {
            case FOUND -> NavWorld.SEARCH_FOUND;
            case PARTIAL_BUDGET -> NavWorld.SEARCH_PARTIAL_BUDGET;
            case PARTIAL_UNREACHABLE -> NavWorld.SEARCH_PARTIAL_UNREACHABLE;
            case NO_PATH -> NavWorld.SEARCH_NO_PATH;
            case CANCELLED -> NavWorld.SEARCH_CANCELLED;
        };
    }

    private static Route javaGround(NavVolume volume, float[] feet, float[] goal, float radius, NavProfile profile,
                                    SearchLimits limits) {
        int sx = (int) Math.floor(feet[0]);
        int sy = (int) Math.floor(feet[1] + 0.001f);
        int sz = (int) Math.floor(feet[2]);
        int gx = (int) Math.floor(goal[0]);
        int gy = (int) Math.floor(goal[1] + 0.001f);
        int gz = (int) Math.floor(goal[2]);
        NavCellCache cache = new NavCellCache(volume);
        GroundNavDomain snapper = new GroundNavDomain(cache, profile, gx, gy, gz, radius);
        long start = snapper.snapToSurface(sx, sy, sz, 4, 2);
        if (start == GroundNavDomain.NO_NODE) {
            return new Route(NavWorld.SEARCH_NO_PATH, new float[0], 0, 0f);
        }
        long snapped = snapper.snapToSurface(gx, gy, gz, 4, 2);
        GroundNavDomain domain = snapped == GroundNavDomain.NO_NODE ? snapper
            : new GroundNavDomain(cache, profile, NavNodes.x(snapped), NavNodes.y(snapped), NavNodes.z(snapped),
                radius);
        SearchResult result = new AStar().search(domain, start, limits, CancelToken.NEVER);
        long[] corners = PathSmoother.collapseCollinear(result.nodes());
        float[] points = new float[corners.length * 3];
        for (int i = 0; i < corners.length; i++) {
            long node = corners[i];
            float surface = domain.surfaceOf(node);
            points[i * 3] = NavNodes.x(node) + 0.5f;
            points[i * 3 + 1] = Float.isNaN(surface) ? NavNodes.y(node) : surface;
            points[i * 3 + 2] = NavNodes.z(node) + 0.5f;
        }
        return new Route(nativeStatus(result.status()), points, result.expansions(), result.cost());
    }

    private static Route javaAir(NavVolume volume, float[] from, float[] goal, float radius, AirNavProfile profile,
                                 SearchLimits limits) {
        int sx = (int) Math.floor(from[0]);
        int sy = (int) Math.floor(from[1]);
        int sz = (int) Math.floor(from[2]);
        int gx = (int) Math.floor(goal[0]);
        int gy = (int) Math.floor(goal[1]);
        int gz = (int) Math.floor(goal[2]);
        NavCellCache cache = new NavCellCache(volume);
        long rawGoal = AirNavDomain.cellOf(profile, gx, gy, gz);
        AirNavDomain snapper = new AirNavDomain(cache, profile, rawGoal, radius);
        long snapped = snapper.snapToFlyable(gx, gy, gz, 2);
        AirNavDomain domain = (snapped == AirNavDomain.NO_NODE || snapped == rawGoal) ? snapper
            : new AirNavDomain(cache, profile, snapped, radius);
        long start = AirNavDomain.cellOf(profile, sx, sy, sz);
        SearchResult result = new AStar().search(domain, start, limits, CancelToken.NEVER);
        long[] corners = domain.stringPull(result.nodes());
        int size = profile.cellSize();
        float half = size * 0.5f;
        float[] points = new float[corners.length * 3];
        for (int i = 0; i < corners.length; i++) {
            points[i * 3] = NavNodes.x(corners[i]) * size + half;
            points[i * 3 + 1] = NavNodes.y(corners[i]) * size + half;
            points[i * 3 + 2] = NavNodes.z(corners[i]) * size + half;
        }
        return new Route(nativeStatus(result.status()), points, result.expansions(), result.cost());
    }

    // ---- the native side ------------------------------------------------------------------------

    private static PhysicsWorld physicsOf(World w) {
        PhysicsWorld physics = PhysicsWorld.create(w::fill, 4, 0, 0);
        for (int id = 1; id < SHAPE_TOP.length; id++) {
            physics.defineShape(id, new float[] {0, 0, 0, 1, SHAPE_TOP[id], 1}, SHAPE_HAZARD[id] ? LAVA : SOLID, 0);
        }
        for (int id = 1; id < FLUID_SURFACE.length; id++) {
            physics.defineFluid(id, FLUID_SURFACE[id], 0, 0, 0, WATER);
        }
        return physics;
    }

    private static Route nativeRoute(NavWorld nav, long id) {
        assertTrue(id > 0);
        nav.step(Integer.MAX_VALUE);
        NavWorld.Outcome o = nav.poll(id);
        nav.release(id);
        NavPath p = o.path();
        float[] points = new float[p.size() * 3];
        for (int i = 0; i < p.size(); i++) {
            points[i * 3] = p.x(i);
            points[i * 3 + 1] = p.y(i);
            points[i * 3 + 2] = p.z(i);
        }
        return new Route(o.status(), points, o.expansions(), o.cost());
    }

    private static void assertSame(Route java, Route nat, String what) {
        if (java.points().length == 0) {
            // Java resolves a boxed-in start without searching; both must agree there is no route.
            assertEquals(0, nat.points().length, what);
            return;
        }
        assertEquals(java.status(), nat.status(), what + " status");
        assertEquals(java.expansions(), nat.expansions(), what + " expansions");
        assertEquals(java.cost(), nat.cost(), what + " cost");
        assertArrayEquals(java.points(), nat.points(), what + " waypoints");
    }

    @Test
    void ground_routes_match_the_java_core() {
        assumeTrue(CendaPhysics.isAvailable() && CendaNav.isAvailable(), "Cenda physics/navigation unavailable");
        NavProfile[] javaProfiles = {
            NavProfile.walker(1.0f, 0), NavProfile.swimmer(0.8f, 0), NavProfile.walker(1.9f, 1),
            new NavProfile(1.4f, 0, 0.5f, 1.2f, 1.6f, 3.0f, true, 3.0f, 8.0f, 0.5f, 2.0f, 0.5f)};
        int found = 0;
        int partial = 0;
        for (long seed = 1; seed <= 6; seed++) {
            World w = terrain(seed);
            try (PhysicsWorld physics = physicsOf(w);
                 NavWorld nav = NavWorld.create(physics, SOLID, LAVA, WATER, MIN_Y, MAX_Y, 1024)) {
                Random r = new Random(seed * 977);
                for (int q = 0; q < 60; q++) {
                    float[] feet = {r.nextInt(48) - 20 + r.nextFloat(), 60 + r.nextInt(12) + r.nextFloat(),
                        r.nextInt(48) - 28 + r.nextFloat()};
                    float[] goal = {r.nextInt(52) - 24 + r.nextFloat(), 60 + r.nextInt(12) + r.nextFloat(),
                        r.nextInt(52) - 30 + r.nextFloat()};
                    float radius = new float[] {0f, 1f, 1.5f, 2.5f}[r.nextInt(4)];
                    NavProfile jp = javaProfiles[r.nextInt(javaProfiles.length)];
                    SearchLimits jl = new SearchLimits(r.nextBoolean() ? 600 : 60, Float.MAX_VALUE,
                        r.nextBoolean() ? 1.0f : 1.1f);
                    GroundProfile np = new GroundProfile(jp.height(), jp.columnRadius(), jp.maxStepUp(), jp.maxClimb(),
                        jp.waterEscapeClimb(), jp.maxFall(), jp.canSwim(), jp.wadeCostMultiplier(),
                        jp.swimCostMultiplier(), jp.stepCost(), jp.jumpCost(), jp.fallCostPerBlock());
                    NavLimits nl = new NavLimits(jl.maxExpansions(), jl.maxCost(), jl.heuristicWeight());

                    Route java = javaGround(w, feet, goal, radius, jp, jl);
                    Route nat = nativeRoute(nav, nav.searchGround(new NavWorld.GroundQuery(feet[0], feet[1], feet[2],
                        goal[0], goal[1], goal[2], radius, 4, 2, np, nl)));
                    assertSame(java, nat, "seed " + seed + " query " + q);
                    if (java.points().length > 0 && java.status() == NavWorld.SEARCH_FOUND) {
                        found++;
                    } else if (java.points().length > 0) {
                        partial++;
                    }
                }
            }
        }
        // Both outcomes must be well represented for the comparison to mean much.
        assertTrue(found >= 20 && partial >= 20, "found " + found + ", partial " + partial);
    }

    @Test
    void air_routes_match_the_java_core() {
        assumeTrue(CendaPhysics.isAvailable() && CendaNav.isAvailable(), "Cenda physics/navigation unavailable");
        for (long seed = 11; seed <= 14; seed++) {
            World w = terrain(seed);
            // Towers reaching into the flight band, and a wall with a gap.
            for (int y = 60; y < 100; y++) {
                for (int z = -30; z < 30; z++) {
                    if (z < 10 || y > 85) {
                        w.set(4, y, z, 1, 0);
                    }
                }
                w.set(-10, y, -10, 1, 0);
                w.set(16, y, 6, 1, 0);
            }
            try (PhysicsWorld physics = physicsOf(w);
                 NavWorld nav = NavWorld.create(physics, SOLID, LAVA, WATER, MIN_Y, MAX_Y, 1024)) {
                Random r = new Random(seed * 131);
                for (int q = 0; q < 30; q++) {
                    int cell = r.nextBoolean() ? 4 : 2;
                    float cruise = 70 + r.nextInt(20);
                    AirNavProfile jp = new AirNavProfile(cell, cruise, 0.35f, 0.5f, 0.15f, r.nextBoolean(), 0.75f, 1,
                        254);
                    AirProfile np = new AirProfile(cell, cruise, 0.35f, 0.5f, 0.15f, jp.allowUnknown(), 0.75f, 1, 254);
                    float[] from = {r.nextInt(40) - 30 + r.nextFloat(), 68 + r.nextInt(20) + r.nextFloat(),
                        r.nextInt(40) - 20 + r.nextFloat()};
                    float[] goal = {r.nextInt(40) - 2 + r.nextFloat(), 64 + r.nextInt(28) + r.nextFloat(),
                        r.nextInt(40) - 20 + r.nextFloat()};
                    float radius = new float[] {0f, 6f}[r.nextInt(2)];
                    SearchLimits jl = new SearchLimits(r.nextBoolean() ? 3000 : 120, Float.MAX_VALUE, 1.25f);
                    NavLimits nl = new NavLimits(jl.maxExpansions(), jl.maxCost(), jl.heuristicWeight());

                    Route java = javaAir(w, from, goal, radius, jp, jl);
                    Route nat = nativeRoute(nav, nav.searchAir(new NavWorld.AirQuery(from[0], from[1], from[2],
                        goal[0], goal[1], goal[2], radius, 2, np, nl)));
                    assertSame(java, nat, "seed " + seed + " query " + q);
                }
            }
        }
    }

    @Test
    void cells_classify_like_the_java_volume_through_its_cache() {
        assumeTrue(CendaPhysics.isAvailable() && CendaNav.isAvailable(), "Cenda physics/navigation unavailable");
        World w = terrain(3);
        try (PhysicsWorld physics = physicsOf(w);
             NavWorld nav = NavWorld.create(physics, SOLID, LAVA, WATER, MIN_Y, MAX_Y, 16)) {
            NavCellCache cache = new NavCellCache(w);
            for (int x = -24; x < 40; x += 1) {
                for (int z = -32; z < 32; z += 3) {
                    for (int y = 55; y < 75; y++) {
                        int flags = cache.flags(x, y, z);
                        assertEquals(flags, nav.cell(x, y, z), "flags at " + x + "," + y + "," + z);
                        if (NavCell.isSolid(flags) || NavCell.isLiquid(flags)) {
                            assertEquals(cache.topSurface(x, y, z), nav.cellTop(x, y, z), "top at " + x + "," + y + "," + z);
                        }
                    }
                }
            }
        }
    }
}
