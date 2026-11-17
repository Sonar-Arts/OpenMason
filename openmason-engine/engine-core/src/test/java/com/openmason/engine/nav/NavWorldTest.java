package com.openmason.engine.nav;

import com.openmason.engine.physics.CendaPhysics;
import com.openmason.engine.physics.PhysicsWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Cenda navigation ({@code cn_*}) through its FFM binding, with no game in sight: cells read from a
 * physics world, raw searches through the service, a ground agent walking a simulated body to its
 * goal, an air agent's horizon, probes and steering. The native suite
 * ({@code cenda/tests/nav_world_test.cpp}) covers the rules in depth; this checks marshalling.
 */
class NavWorldTest {

    private static final int SOLID = 1;
    private static final int PICK = 2;
    private static final int WATER = 1;
    private static final int FULL = 1;
    private static final int SNOW = 2;

    private final Map<Long, int[]> cells = new HashMap<>();
    private PhysicsWorld physics;
    private NavWorld nav;

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    private void set(int x, int y, int z, int shape, int fluid) {
        cells.put(key(x, y, z), new int[] {shape, fluid});
    }

    private boolean fill(int x0, int y0, int z0, int sx, int sy, int sz, short[] shapes, short[] fluids) {
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
        assumeTrue(CendaPhysics.isAvailable(), () -> "Cenda physics: " + CendaPhysics.unavailableReason());
        assumeTrue(CendaNav.isAvailable(), () -> "Cenda navigation: " + CendaNav.unavailableReason());
        for (int x = -4; x <= 24; x++) {
            for (int z = -4; z <= 4; z++) {
                set(x, 63, z, FULL, 0);
            }
        }
        physics = PhysicsWorld.create(this::fill, 4, 0, 0);
        physics.defineShape(FULL, new float[] {0, 0, 0, 1, 1, 1}, SOLID | PICK, 0);
        physics.defineShape(SNOW, new float[] {0, 0, 0, 1, 0.25f, 1}, SOLID | PICK, PhysicsWorld.SHAPE_PARTIAL);
        physics.defineFluid(1, 0.875f, 0, 0, 0, WATER);
        nav = NavWorld.create(physics, SOLID, 0, WATER, 0, 256, 8);
    }

    @AfterEach
    void tearDown() {
        if (nav != null) {
            nav.close();
        }
        if (physics != null) {
            physics.close();
        }
    }

    private static GroundProfile walker() {
        return new GroundProfile(1.0f, 0, 0.5f, 1.125f, 1.5f, 3.0f, false, 3.0f, 8.0f, 0.5f, 2.0f, 0.5f);
    }

    @Test
    void cells_are_the_physics_cache() {
        set(2, 64, 0, SNOW, 0);
        set(3, 64, 0, 0, 1);
        physics.invalidateAll();
        assertEquals(NavWorld.CELL_SOLID, nav.cell(0, 63, 0));
        assertEquals(1.0f, nav.cellTop(0, 63, 0));
        assertEquals(NavWorld.CELL_SOLID, nav.cell(2, 64, 0));
        assertEquals(0.25f, nav.cellTop(2, 64, 0));
        assertEquals(NavWorld.CELL_LIQUID, nav.cell(3, 64, 0));
        assertEquals(0.875f, nav.cellTop(3, 64, 0));
        assertEquals(0, nav.cell(0, 64, 0));
        assertEquals(NavWorld.CELL_UNKNOWN, nav.cell(0, 300, 0));
    }

    @Test
    void searches_run_on_step_and_return_routes() {
        NavLimits limits = new NavLimits(600, Float.MAX_VALUE, 1.0f);
        long id = nav.searchGround(new NavWorld.GroundQuery(0.5f, 64, 0.5f, 10.5f, 64, 0.5f, 0, 4, 2, walker(), limits));
        assertTrue(id > 0);
        assertNull(nav.poll(id));
        assertEquals(1, nav.step(1 << 20));
        NavWorld.Outcome outcome = nav.poll(id);
        assertNotNull(outcome);
        assertTrue(outcome.reachedGoal());
        assertEquals(2, outcome.path().size());
        assertEquals(10.5f, outcome.path().x(1));
        assertEquals(64.0f, outcome.path().y(1));
        nav.release(id);
        assertThrows(IllegalArgumentException.class, () -> nav.poll(id));

        AirProfile flyer = new AirProfile(4, 80, 0.35f, 0.5f, 0.15f, true, 0.75f, 1, 254);
        long air = nav.searchAir(new NavWorld.AirQuery(2, 80, 2, 60, 80, 2, 6, 2, flyer,
            new NavLimits(3000, Float.MAX_VALUE, 1.25f)));
        nav.step(1 << 20);
        NavWorld.Outcome flown = nav.poll(air);
        assertTrue(flown.reachedGoal());
        assertTrue(flown.path().size() >= 1);
        nav.release(air);

        NavWorld.Stats stats = nav.stats();
        assertEquals(2, stats.submitted());
        assertEquals(2, stats.completed());
        assertEquals(0, stats.inFlight());
    }

    private static GroundAgentDesc agentDesc() {
        return new GroundAgentDesc(walker(), new NavLimits(600, Float.MAX_VALUE, 1.1f), 4, 2,
            1.0f, 3.0f, 0.5f, 2.0f, 0.7f, 1.6f, 0.15f, 1.5f, 0.05f, 1.5f);
    }

    private static SteeringDesc steeringDesc() {
        return new SteeringDesc(180.0f, 0.0f, 0.0f, 1.0f, 0.35f, 0.15f, 0.1f, 3, 0.0f, SOLID);
    }

    @Test
    void a_ground_agent_walks_a_body_to_its_goal() {
        AgentBody body = new AgentBody();
        body.x = 0.5f;
        body.y = 64.4f;
        body.z = 0.5f;
        body.legHeight = 0.4f;
        body.halfWidth = 0.3f;
        body.halfLength = 0.3f;
        body.moveSpeed = 3.0f;
        body.speedMultiplier = 1.0f;
        body.swimStrokeReach = 1.5f;
        body.onGround = true;
        AgentApply out = new AgentApply();
        try (GroundAgent agent = GroundAgent.create(agentDesc(), body.x, body.y, body.z);
             NavSteering steering = NavSteering.create(steeringDesc())) {
            agent.moveTo(12.5f, 64.0f, 0.5f, 1.0f, 1.0f);
            assertEquals(AgentView.SEARCHING, agent.view().status());
            assertTrue(agent.view().hasGoal());
            assertEquals(12.5f, agent.view().goalX());

            float dt = 0.05f;
            int arrivedAt = -1;
            for (int tick = 0; tick < 200 && arrivedAt < 0; tick++) {
                agent.tick(steering, nav, physics, body, dt, out);
                if (out.turn()) {
                    body.yaw = out.yaw();
                }
                if (out.setVelocity()) {
                    body.x += out.vx() * dt;
                    body.z += out.vz() * dt;
                }
                nav.step(1 << 20);
                if (tick == 2) {
                    assertEquals(AgentView.FOLLOWING, agent.view().status());
                    assertFalse(agent.path().isEmpty());
                    assertTrue(agent.path().isComplete());
                    assertEquals(90.0f, Steer.yawFor(1, 0, 0), 1e-4f);
                }
                if (agent.view().status() == AgentView.ARRIVED) {
                    arrivedAt = tick;
                }
            }
            assertTrue(arrivedAt > 0, "never arrived");
            assertEquals(11.5f, body.x, 0.7f);
            assertSame(NavPath.EMPTY, agent.path()); // arrival drops the route

            agent.stop(nav, out);
            assertTrue(out.setVelocity());
            assertEquals(0.0f, out.vx());
            assertEquals(AgentView.IDLE, agent.view().status());
        }
    }

    @Test
    void an_air_agent_publishes_its_horizon_and_route() {
        AirAgentDesc desc = new AirAgentDesc(new AirProfile(4, 0, 0.35f, 0.5f, 0.15f, true, 0.75f, 1, 254),
            new NavLimits(3000, Float.MAX_VALUE, 1.25f), 112, 32, 1.5f, 3, 0.5f, 4, 6, 2);
        try (AirAgent agent = AirAgent.create(desc, 0, 90, 0)) {
            agent.moveTo(400, 90, 0);
            float[] target = agent.steerTarget(0, 90, 0, new float[3]);
            assertEquals(112.0f, target[0], 1e-4f);
            agent.tick(nav, 0, 90, 0, 0.05f);
            int version = agent.view().pathVersion();
            nav.step(1 << 20);
            agent.tick(nav, 0, 90, 0, 0.05f);
            assertEquals(AgentView.FOLLOWING, agent.view().status());
            assertNotEquals(version, agent.view().pathVersion());
            assertFalse(agent.path().isEmpty());
            agent.stop(nav);
            assertEquals(AgentView.IDLE, agent.view().status());
        }
    }

    @Test
    void probes_and_steering() {
        set(5, 64, 0, FULL, 0);
        set(6, 64, 0, 0, 1);
        physics.invalidateAll();
        assertEquals(64.0f, NavProbes.groundLevel(physics, 0.5f, 0.5f, 66, 5, 10, SOLID));
        assertEquals(65.0f, NavProbes.groundLevel(physics, 5.5f, 0.5f, 66, 5, 10, SOLID));
        assertEquals(65.0f, NavProbes.fluidTop(physics, 6.5f, 64.5f, 0.5f, 1, 3, WATER));
        assertTrue(NavProbes.isClear(physics, 0.5f, 65.5f, 0.5f, 0.4f, SOLID));
        assertFalse(NavProbes.isClear(physics, 5.5f, 64.5f, 0.5f, 0.4f, SOLID));
        assertEquals(65.0f, NavProbes.columnPeak(physics, 5.5f, 0.5f, 66, 32, SOLID));
        assertEquals(Float.NEGATIVE_INFINITY, NavProbes.columnPeak(physics, 50.5f, 50.5f, 66, 4, SOLID));
        assertEquals(175.0f, Steer.turn(170.0f, -170.0f, 5.0f), 1e-4f);
        assertEquals(20.0f, Steer.yawError(350.0f, 10.0f), 1e-4f);
    }

    @Test
    void steering_works_on_its_own() {
        set(2, 64, 0, FULL, 0);
        physics.invalidateAll();
        AgentBody body = new AgentBody();
        body.x = 1.5f;
        body.y = 64.4f;
        body.z = 0.5f;
        body.legHeight = 0.4f;
        body.halfWidth = 0.4f;
        body.halfLength = 0.4f;
        body.moveSpeed = 2.0f;
        body.speedMultiplier = 1.0f;
        body.onGround = true;
        AgentApply out = new AgentApply();
        try (NavSteering steering = NavSteering.create(steeringDesc())) {
            steering.steerAlong(physics, body, 1, 0, 1.0f, 0.1f, true, out);
            assertTrue(out.jump());
            assertTrue(out.turn());
            assertEquals(18.0f, out.yaw(), 1e-4f);
            assertEquals(2.0f, out.vx(), 1e-6f);
            assertFalse(steering.requestJump(body, out), "the cooldown holds a second hop");
            steering.tick(true, 1.5f);
            assertTrue(steering.requestJump(body, out));
            assertTrue(out.jump());
        }
    }

    @Test
    void a_flood_reaches_face_neighbours_in_its_categories() {
        physics.defineShape(3, new float[] {0, 0, 0, 1, 1, 1}, SOLID | 8, 0); // a "leaf" category bit
        for (int x = 0; x < 4; x++) {
            set(x, 70, 0, 3, 0);
        }
        set(1, 71, 1, 3, 0); // only diagonal to the line
        physics.invalidateAll();
        NavFlood flood = new NavFlood(64);
        int n = flood.run(physics, 0, 70, 0, 6, 8, 0, 256);
        assertEquals(4, n);
        for (int i = 0; i < n; i++) {
            assertEquals(i, flood.x(i));
            assertEquals(70, flood.y(i));
            assertEquals(SOLID | 8, flood.category(i));
        }
        assertEquals(2, flood.run(physics, 0, 70, 0, 1, 8, 0, 256), "max steps bounds the distance");
        assertEquals(1, new NavFlood(1).run(physics, 0, 70, 0, 6, 8, 0, 256), "the capacity caps expansion");
    }

    @Test
    void reach_returns_the_walkable_cells_cheapest_first() {
        NavWorld.Reach reach = nav.reachGround(0.5f, 64, 0.5f, 4, 2, 2, 1000, Float.MAX_VALUE, walker());
        assertEquals(25, reach.count(), "5x5 columns of open floor");
        assertEquals(-1, reach.parent(0));
        assertEquals(0.0f, reach.cost(0));
        for (int i = 1; i < reach.count(); i++) {
            assertTrue(reach.parent(i) >= 0 && reach.parent(i) < i);
            assertTrue(reach.cost(i) >= reach.cost(i - 1) - 1e-4f);
            assertEquals(64.0f, reach.surface(i));
        }
    }

    @Test
    void a_body_that_steps_a_full_block_is_never_hopped() {
        set(2, 64, 0, FULL, 0);
        physics.invalidateAll();
        AgentBody body = new AgentBody();
        body.x = 1.5f;
        body.y = 64.4f;
        body.z = 0.5f;
        body.legHeight = 0.4f;
        body.halfWidth = 0.4f;
        body.halfLength = 0.4f;
        body.moveSpeed = 2.0f;
        body.speedMultiplier = 1.0f;
        body.onGround = true;
        AgentApply out = new AgentApply();
        try (NavSteering steering = NavSteering.create(new SteeringDesc(180.0f, 0.0f, 0.0f, 1.0f, 0.35f, 0.15f, 0.1f,
                3, 0.0f, SOLID, 1.0f))) {
            steering.steerAlong(physics, body, 1, 0, 1.0f, 0.1f, true, out);
            assertFalse(out.jump());
        }
    }

    @Test
    void a_closed_world_is_no_service() {
        nav.close();
        assertTrue(nav.isClosed());
        assertEquals(0, nav.stats().submitted());
        assertThrows(IllegalStateException.class, () -> nav.step(10));
        try (GroundAgent agent = GroundAgent.create(agentDesc(), 0.5f, 64.4f, 0.5f);
             NavSteering steering = NavSteering.create(steeringDesc())) {
            AgentBody body = new AgentBody();
            body.x = 0.5f;
            body.y = 64.4f;
            body.z = 0.5f;
            agent.moveTo(5, 64, 0, 1, 1);
            agent.tick(steering, nav, physics, body, 0.05f, new AgentApply());
            assertEquals(AgentView.NO_PATH, agent.view().status());
        }
    }
}
