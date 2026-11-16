package com.openmason.engine.nav;

import com.openmason.engine.physics.CendaPhysics;
import com.openmason.engine.physics.PhysicsWorld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Native route search against the routes the engine's Java Wayfind core (AStar + GroundNavDomain +
 * AirNavDomain + NavCellCache + PathSmoother, retired 2026-11-16) produced over the same random worlds.
 * Those routes were frozen into {@code nav/wayfind-golden-routes.txt} — status, expansion count, cost
 * and every waypoint as raw float bits — while the two implementations were still compared side by side
 * (they agreed bit for bit), so the native port stays pinned to the behaviour it replaced.
 *
 * <p>The worlds mix full blocks, partial tops (snow layers, half blocks, an odd 0.6 that exercises the
 * 1/1024 quantisation), water at two surface heights, hazards, overhangs and an unloaded region. The
 * ground queries are what a game's path service asks (feet/goal snapped onto standable cells 4 down
 * and 2 up); the air ones nudge the goal onto flyable air within 2 cells.
 */
class NavGoldenRoutesTest {

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

    /** A sparse world: (shape, fluid) per cell, filled into physics like a game would. */
    private static final class World {
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

    /** The Java core's routes, by "kind seed query". */
    private static final Map<String, String> GOLDEN = new HashMap<>();

    @BeforeAll
    static void loadGolden() throws IOException {
        try (InputStream in = NavGoldenRoutesTest.class.getResourceAsStream("/nav/wayfind-golden-routes.txt")) {
            assertNotNull(in, "nav/wayfind-golden-routes.txt");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] f = line.split(" ", 4);
                GOLDEN.put(f[0] + " " + f[1] + " " + f[2], line);
            }
        }
    }

    private static String routeLine(String kind, long seed, int q, NavWorld nav, long id) {
        nav.step(Integer.MAX_VALUE);
        NavWorld.Outcome o = nav.poll(id);
        nav.release(id);
        StringBuilder b = new StringBuilder();
        b.append(kind).append(' ').append(seed).append(' ').append(q).append(' ').append(o.status()).append(' ')
            .append(o.expansions()).append(' ').append(Integer.toHexString(Float.floatToRawIntBits(o.cost())))
            .append(' ').append(o.path().size());
        NavPath p = o.path();
        for (int i = 0; i < p.size(); i++) {
            b.append(' ').append(Integer.toHexString(Float.floatToRawIntBits(p.x(i))))
                .append(' ').append(Integer.toHexString(Float.floatToRawIntBits(p.y(i))))
                .append(' ').append(Integer.toHexString(Float.floatToRawIntBits(p.z(i))));
        }
        return b.toString();
    }

    private static GroundProfile walker(float height, int columnRadius) {
        return new GroundProfile(height, columnRadius, 0.5f, 1.125f, 1.5f, 3.0f, false, 3.0f, 8.0f, 0.5f, 2.0f, 0.5f);
    }

    private static GroundProfile swimmer(float height, int columnRadius) {
        return new GroundProfile(height, columnRadius, 0.5f, 1.125f, 1.5f, 5.0f, true, 1.0f, 1.2f, 0.5f, 2.0f, 0.2f);
    }

    @Test
    void groundRoutesMatchTheRetiredJavaCore() {
        assumeTrue(CendaPhysics.isAvailable() && CendaNav.isAvailable(), "Cenda physics/navigation unavailable");
        GroundProfile[] profiles = {walker(1.0f, 0), swimmer(0.8f, 0), walker(1.9f, 1),
            new GroundProfile(1.4f, 0, 0.5f, 1.2f, 1.6f, 3.0f, true, 3.0f, 8.0f, 0.5f, 2.0f, 0.5f)};
        int checked = 0;
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
                    GroundProfile profile = profiles[r.nextInt(profiles.length)];
                    NavLimits limits = new NavLimits(r.nextBoolean() ? 600 : 60, Float.MAX_VALUE,
                        r.nextBoolean() ? 1.0f : 1.1f);
                    long id = nav.searchGround(new NavWorld.GroundQuery(feet[0], feet[1], feet[2], goal[0], goal[1],
                        goal[2], radius, 4, 2, profile, limits));
                    assertEquals(GOLDEN.get("ground " + seed + " " + q), routeLine("ground", seed, q, nav, id));
                    checked++;
                }
            }
        }
        assertEquals(360, checked);
    }

    @Test
    void airRoutesMatchTheRetiredJavaCore() {
        assumeTrue(CendaPhysics.isAvailable() && CendaNav.isAvailable(), "Cenda physics/navigation unavailable");
        int checked = 0;
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
                    AirProfile profile = new AirProfile(cell, cruise, 0.35f, 0.5f, 0.15f, r.nextBoolean(), 0.75f, 1, 254);
                    float[] from = {r.nextInt(40) - 30 + r.nextFloat(), 68 + r.nextInt(20) + r.nextFloat(),
                        r.nextInt(40) - 20 + r.nextFloat()};
                    float[] goal = {r.nextInt(40) - 2 + r.nextFloat(), 64 + r.nextInt(28) + r.nextFloat(),
                        r.nextInt(40) - 20 + r.nextFloat()};
                    float radius = new float[] {0f, 6f}[r.nextInt(2)];
                    NavLimits limits = new NavLimits(r.nextBoolean() ? 3000 : 120, Float.MAX_VALUE, 1.25f);
                    long id = nav.searchAir(new NavWorld.AirQuery(from[0], from[1], from[2], goal[0], goal[1], goal[2],
                        radius, 2, profile, limits));
                    assertEquals(GOLDEN.get("air " + seed + " " + q), routeLine("air", seed, q, nav, id));
                    checked++;
                }
            }
        }
        assertEquals(120, checked);
    }
}
