package com.openmason.engine.nav;

import com.openmason.engine.physics.CendaPhysics;
import com.openmason.engine.physics.PhysicsWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The movement rules of the native route search, one at a time, on hand-built scenes: what the
 * retired Java Wayfind tests (AStarTest, GroundNavDomainTest, AirNavDomainTest, PathSmootherTest)
 * pinned on the Java domains, asked of the native search through its routes. A single move's cost is
 * a one-step search's cost; "cannot get there" is a route that never arrives.
 * {@link NavGoldenRoutesTest} pins the same search route-for-route against the Java core it replaced.
 */
class NavRulesTest {

    private static final int SOLID = 1;
    private static final int LAVA = 2;
    private static final int WATER = 1;
    private static final int FULL = 1;
    private static final int HALF = 2;
    private static final int HOT = 3;
    private static final int SOURCE = 1;
    private static final int GROUND_TOP = 63;
    private static final int STAND_Y = 64;
    private static final float EPS = 1e-4f;
    private static final float DIAGONAL = 1.4142135f;
    /** Cells with x below this are unloaded. */
    private static final int UNLOADED_BELOW_X = -32;

    private final Map<Long, int[]> cells = new HashMap<>();
    /** Air scenes: only the [0, SKY)^3 box is loaded, as the retired tests' box world was. */
    private boolean boxed;
    private PhysicsWorld physics;
    private NavWorld nav;

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    private NavRulesTest set(int x, int y, int z, int shape, int fluid) {
        cells.put(key(x, y, z), new int[] {shape, fluid});
        return this;
    }

    private void clear(int x, int y, int z) {
        cells.remove(key(x, y, z));
    }

    /** Ground from y 50 to GROUND_TOP over [0, size) x [0, size); nothing (no floor) outside. */
    private NavRulesTest ground(int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                for (int y = 50; y <= GROUND_TOP; y++) {
                    set(x, y, z, FULL, 0);
                }
            }
        }
        return this;
    }

    private void column(int x, int z, int fromY, int toY) {
        for (int y = fromY; y <= toY; y++) {
            set(x, y, z, FULL, 0);
        }
    }

    /** Water `depth` deep whose top cell is at topY, on whatever is below. */
    private void pond(int x, int z, int topY, int depth) {
        for (int y = topY - depth + 1; y <= topY; y++) {
            set(x, y, z, 0, SOURCE);
        }
    }

    private boolean fill(long ax0, long ay0, long az0, int sx, int sy, int sz, short[] shapes, short[] fluids) {
        if (ax0 < UNLOADED_BELOW_X) {
            return false;
        }
        int x0 = Math.toIntExact(ax0), y0 = Math.toIntExact(ay0), z0 = Math.toIntExact(az0);
        if (x0 < UNLOADED_BELOW_X) {
            return false;
        }
        if (boxed && (x0 < 0 || y0 < 0 || z0 < 0 || x0 >= SKY || y0 >= SKY || z0 >= SKY)) {
            return false; // bricks are 16-aligned and the box is 64: a brick is all in or all out
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
        assumeTrue(CendaPhysics.isAvailable() && CendaNav.isAvailable(), "Cenda physics/navigation unavailable");
        physics = PhysicsWorld.create(this::fill, 4, 0, 0);
        physics.defineShape(FULL, new float[] {0, 0, 0, 1, 1, 1}, SOLID, 0);
        physics.defineShape(HALF, new float[] {0, 0, 0, 1, 0.5f, 1}, SOLID, PhysicsWorld.SHAPE_PARTIAL);
        physics.defineShape(HOT, new float[] {0, 0, 0, 1, 1, 1}, LAVA, 0);
        physics.defineFluid(SOURCE, 0.875f, 0, 0, 0, WATER);
        nav = NavWorld.create(physics, SOLID, LAVA, WATER, 0, 256, 64);
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

    // ---- profiles and searches -----------------------------------------------------------------

    private static GroundProfile walker(float height, int columnRadius) {
        return new GroundProfile(height, columnRadius, 0.5f, 1.125f, 1.5f, 3.0f, false, 3.0f, 8.0f, 0.5f, 2.0f, 0.5f);
    }

    private static GroundProfile swimmer(float height) {
        return new GroundProfile(height, 0, 0.5f, 1.125f, 1.5f, 5.0f, true, 1.0f, 1.2f, 0.5f, 2.0f, 0.2f);
    }

    private static final GroundProfile SHORT = walker(0.9f, 0);
    private static final NavLimits WIDE = new NavLimits(20_000, Float.MAX_VALUE, 1.0f);

    private NavWorld.Outcome walk(GroundProfile p, NavLimits limits, float fx, float fy, float fz,
                                  float gx, float gy, float gz, float radius, int snapDown) {
        physics.invalidateAll();
        long id = nav.searchGround(new NavWorld.GroundQuery(fx, fy, fz, gx, gy, gz, radius, snapDown, 2, p, limits));
        nav.step(Integer.MAX_VALUE);
        NavWorld.Outcome o = nav.poll(id);
        nav.release(id);
        return o;
    }

    /** From the centre of cell (fx, fy, fz) to cell (gx, gy, gz) exactly. */
    private NavWorld.Outcome walk(GroundProfile p, int fx, int fy, int fz, int gx, int gy, int gz) {
        return walk(p, WIDE, fx + 0.5f, fy, fz + 0.5f, gx + 0.5f, gy, gz + 0.5f, 0f, 4);
    }

    private static boolean endsAt(NavWorld.Outcome o, float x, float y, float z) {
        NavPath p = o.path();
        int last = p.size() - 1;
        return o.reachedGoal() && Math.abs(p.x(last) - x) < EPS && Math.abs(p.y(last) - y) < EPS
            && Math.abs(p.z(last) - z) < EPS;
    }

    // ---- ground: flat --------------------------------------------------------------------------

    @Test
    void flatMovesCostTheirOctileLength() {
        ground(9);
        assertEquals(1.0f, walk(SHORT, 4, STAND_Y, 4, 5, STAND_Y, 4).cost(), EPS, "orthogonal step");
        assertEquals(DIAGONAL, walk(SHORT, 4, STAND_Y, 4, 5, STAND_Y, 5).cost(), EPS, "diagonal step");
    }

    @Test
    void feetRestOnTopOfTheBlockBelow() {
        ground(9);
        assertTrue(endsAt(walk(SHORT, 2, STAND_Y, 2, 4, STAND_Y, 2), 4.5f, STAND_Y, 2.5f));
    }

    // ---- ground: vertical rules -------------------------------------------------------------------

    @Test
    void stepUpOntoAPartialBlockIsAStepAtItsRealHeight() {
        ground(9).set(5, STAND_Y, 4, HALF, 0);
        NavWorld.Outcome o = walk(SHORT, 4, STAND_Y, 4, 5, STAND_Y, 4);
        assertTrue(endsAt(o, 5.5f, STAND_Y + 0.5f, 4.5f), "the tread is stood on at its real height");
        assertEquals(1.0f + SHORT.stepCost(), o.cost(), EPS, "and costs a step, not a jump");
    }

    @Test
    void theCellAboveAPartialBlockIsNotASecondNode() {
        ground(9).set(5, STAND_Y, 4, HALF, 0);
        // Asked to stand in the cell above the tread, the search snaps to the tread's own cell.
        NavWorld.Outcome o = walk(SHORT, 4, STAND_Y, 4, 5, STAND_Y + 1, 4);
        assertTrue(endsAt(o, 5.5f, STAND_Y + 0.5f, 4.5f));
        assertEquals(1.0f + SHORT.stepCost(), o.cost(), EPS);
    }

    @Test
    void jumpingOntoAFullBlockCostsAJump() {
        ground(9).set(5, STAND_Y, 4, FULL, 0);
        NavWorld.Outcome o = walk(SHORT, 4, STAND_Y, 4, 5, STAND_Y + 1, 4);
        assertTrue(endsAt(o, 5.5f, STAND_Y + 1, 4.5f));
        assertEquals(1.0f + SHORT.jumpCost(), o.cost(), EPS);
    }

    @Test
    void aTwoBlockLedgeIsOutOfReach() {
        ground(9);
        column(5, 4, STAND_Y, STAND_Y + 1);
        assertFalse(walk(SHORT, 4, STAND_Y, 4, 5, STAND_Y + 2, 4).reachedGoal());
    }

    @Test
    void aNonJumperCannotEvenMountOneBlock() {
        GroundProfile stepper = new GroundProfile(0.9f, 0, 0.5f, 0.5f, 0.5f, 3.0f, false, 3.0f, 8.0f, 0.5f, 2.0f, 0.5f);
        ground(9).set(5, STAND_Y, 4, FULL, 0);
        assertFalse(walk(stepper, 4, STAND_Y, 4, 5, STAND_Y + 1, 4).reachedGoal());
    }

    @Test
    void dropsWithinTheFallLimitAreTakenAndCosted() {
        ground(9);
        for (int y = GROUND_TOP - 2; y <= GROUND_TOP; y++) {
            clear(5, y, 4); // floor left three below
        }
        NavWorld.Outcome o = walk(SHORT, 4, STAND_Y, 4, 5, STAND_Y - 3, 4);
        assertTrue(o.reachedGoal());
        assertEquals(1.0f + SHORT.fallCostPerBlock() * 3, o.cost(), EPS);
    }

    @Test
    void dropsBeyondTheFallLimitAreRefused() {
        ground(9);
        for (int y = GROUND_TOP - 5; y <= GROUND_TOP; y++) {
            clear(5, y, 4); // a six-block drop
        }
        assertFalse(walk(SHORT, 4, STAND_Y, 4, 5, STAND_Y - 6, 4).reachedGoal());
    }

    // ---- ground: shape rules -----------------------------------------------------------------------

    @Test
    void diagonalsWillNotSqueezeThroughACorner() {
        ground(9);
        column(5, 4, STAND_Y, STAND_Y + 1);
        column(4, 5, STAND_Y, STAND_Y + 1);
        NavWorld.Outcome o = walk(SHORT, 4, STAND_Y, 4, 5, STAND_Y, 5);
        assertTrue(o.reachedGoal());
        assertTrue(o.cost() > DIAGONAL + 1.0f, "cutting between two blocks would clip both: " + o.cost());
    }

    @Test
    void headroomIsRequiredForTheAgentsFullHeight() {
        ground(9).set(5, STAND_Y + 1, 4, FULL, 0);
        assertEquals(1.0f, walk(SHORT, 4, STAND_Y, 4, 5, STAND_Y, 4).cost(), EPS, "a 0.9-tall agent fits");
        NavWorld.Outcome tall = walk(walker(1.6f, 0), 4, STAND_Y, 4, 5, STAND_Y, 4);
        assertFalse(endsAt(tall, 5.5f, STAND_Y, 4.5f), "a 1.6-tall agent does not");
    }

    @Test
    void aWideAgentDoesNotFitAOneBlockCorridor() {
        ground(9);
        for (int x = 0; x < 9; x++) {
            column(x, 3, STAND_Y, STAND_Y + 1);
            column(x, 5, STAND_Y, STAND_Y + 1);
        }
        assertEquals(1.0f, walk(SHORT, 4, STAND_Y, 4, 5, STAND_Y, 4).cost(), EPS, "a one-column agent walks it");
        NavWorld.Outcome wide = walk(walker(0.9f, 1), 4, STAND_Y, 4, 5, STAND_Y, 4);
        assertEquals(NavWorld.SEARCH_NO_PATH, wide.status(), "a three-column agent cannot even stand in it");
        assertTrue(wide.path().isEmpty());
    }

    // ---- ground: cell kinds -----------------------------------------------------------------------

    @Test
    void unloadedTerrainIsImpassable() {
        for (int x = UNLOADED_BELOW_X - 16; x < 8; x++) {
            for (int y = 50; y <= GROUND_TOP; y++) {
                set(x, y, 0, FULL, 0);
            }
        }
        NavWorld.Outcome o = walk(SHORT, 4, STAND_Y, 0, UNLOADED_BELOW_X - 4, STAND_Y, 0);
        assertFalse(o.reachedGoal());
        for (int i = 0; i < o.path().size(); i++) {
            assertTrue(o.path().x(i) >= UNLOADED_BELOW_X, "never routed over unloaded ground");
        }
    }

    @Test
    void shallowWaterIsWadedAtAPenalty() {
        ground(9);
        clear(5, GROUND_TOP, 4);
        pond(5, 4, GROUND_TOP, 1);
        NavWorld.Outcome o = walk(SHORT, 4, STAND_Y, 4, 5, GROUND_TOP, 4);
        assertTrue(endsAt(o, 5.5f, GROUND_TOP + 0.875f, 4.5f), "stood in at the waterline");
        assertEquals(SHORT.wadeCostMultiplier() + SHORT.fallCostPerBlock() * 0.125f, o.cost(), EPS);
    }

    @Test
    void deepWaterStopsANonSwimmerAndCarriesASwimmer() {
        ground(9);
        clear(5, GROUND_TOP, 4);
        clear(5, GROUND_TOP - 1, 4);
        pond(5, 4, GROUND_TOP, 2);
        assertFalse(walk(SHORT, 4, STAND_Y, 4, 5, GROUND_TOP, 4).reachedGoal(), "out of its depth");
        GroundProfile swimmer = swimmer(0.9f);
        NavWorld.Outcome o = walk(swimmer, 4, STAND_Y, 4, 5, GROUND_TOP, 4);
        assertEquals(swimmer.swimCostMultiplier() + swimmer.fallCostPerBlock() * 0.125f, o.cost(), EPS);
    }

    @Test
    void aSwimmerCanClimbFromDeepWaterOntoTheShelfAndOutOntoLand() {
        ground(9);
        for (int y = GROUND_TOP - 1; y <= GROUND_TOP; y++) {
            clear(4, y, 4);
        }
        pond(4, 4, GROUND_TOP, 2);    // two deep...
        clear(5, GROUND_TOP, 4);
        pond(5, 4, GROUND_TOP, 1);    // ...beside a one-deep shelf
        set(6, STAND_Y, 4, FULL, 0);  // and a bank one block above the shore
        GroundProfile swimmer = swimmer(0.9f);
        assertTrue(walk(swimmer, 4, GROUND_TOP, 4, 5, GROUND_TOP, 4).reachedGoal(),
            "deep water must not be a one-way trap");
        assertTrue(walk(swimmer, 5, GROUND_TOP, 4, 6, STAND_Y + 1, 4).reachedGoal(), "and then out onto the bank");
    }

    @Test
    void aSwimmerPlansAStrokeOntoAOneBlockShoreButNotUpACliff() {
        ground(9);
        for (int y = GROUND_TOP - 1; y <= GROUND_TOP; y++) {
            clear(4, y, 4);
        }
        pond(4, 4, GROUND_TOP, 2);
        set(5, STAND_Y, 4, FULL, 0); // shore one block proud of the water's own surface
        GroundProfile swimmer = swimmer(0.9f);
        NavWorld.Outcome climb = walk(swimmer, 4, GROUND_TOP, 4, 5, STAND_Y + 1, 4);
        assertTrue(climb.reachedGoal(), "a stroke clears a one-block shore");
        assertEquals(1.0f + swimmer.jumpCost(), climb.cost(), EPS);
        column(5, 4, STAND_Y + 1, STAND_Y + 2); // now three blocks of cliff
        assertFalse(walk(swimmer, 4, GROUND_TOP, 4, 5, STAND_Y + 3, 4).reachedGoal(), "a cliff is not an exit");
    }

    @Test
    void hazardsAreNeverEntered() {
        ground(9).set(5, STAND_Y, 4, HOT, 0);
        NavWorld.Outcome o = walk(SHORT, 4, STAND_Y, 4, 5, STAND_Y, 4);
        assertFalse(endsAt(o, 5.5f, STAND_Y, 4.5f));
        assertEquals(NavWorld.CELL_HAZARD, nav.cell(5, STAND_Y, 4));
    }

    // ---- ground: snapping ----------------------------------------------------------------------

    @Test
    void aGoalInTheAirSnapsToTheGroundUnderIt() {
        ground(9);
        NavWorld.Outcome o = walk(SHORT, WIDE, 2.5f, STAND_Y, 2.5f, 4.5f, STAND_Y + 6, 4.5f, 0f, 8);
        assertTrue(endsAt(o, 4.5f, STAND_Y, 4.5f));
    }

    @Test
    void aStartInsideSolidRockHasNoRoute() {
        ground(9);
        NavWorld.Outcome o = walk(SHORT, WIDE, 4.5f, 55, 4.5f, 6.5f, STAND_Y, 4.5f, 0f, 4);
        assertEquals(NavWorld.SEARCH_NO_PATH, o.status());
        assertTrue(o.path().isEmpty());
        assertEquals(0, o.expansions(), "nothing to search from");
    }

    // ---- ground: end to end ------------------------------------------------------------------------

    @Test
    void routesAroundAWallThroughItsGap() {
        ground(12);
        for (int z = 0; z <= 10; z++) {
            column(5, z, STAND_Y, STAND_Y + 1); // open only at z 11
        }
        NavWorld.Outcome o = walk(SHORT, 0, STAND_Y, 0, 10, STAND_Y, 0);
        assertTrue(o.reachedGoal());
        boolean throughTheGap = false;
        for (int i = 0; i < o.path().size(); i++) {
            if (o.path().z(i) > 11.0f) {
                throughTheGap = true;
            }
        }
        assertTrue(throughTheGap, "the only way past is the gap at z 11");
    }

    @Test
    void aSealedRoomYieldsABestEffortRoute() {
        ground(12);
        for (int z = 0; z < 12; z++) {
            column(5, z, STAND_Y, STAND_Y + 1);
        }
        NavWorld.Outcome o = walk(SHORT, 0, STAND_Y, 0, 10, STAND_Y, 0);
        assertEquals(NavWorld.SEARCH_PARTIAL_UNREACHABLE, o.status());
        assertFalse(o.path().isEmpty(), "the walker should still walk up to the wall");
        assertEquals(4.5f, o.path().x(o.path().size() - 1), EPS, "as close as it can get");
    }

    // ---- the search itself (the retired AStarTest) --------------------------------------------

    @Test
    void aGoalAtTheStartIsTheStartAlone() {
        ground(9);
        NavWorld.Outcome o = walk(SHORT, 4, STAND_Y, 4, 4, STAND_Y, 4);
        assertTrue(o.reachedGoal());
        assertEquals(1, o.path().size());
        assertEquals(0.0f, o.cost());
        assertEquals(0, o.expansions());
    }

    @Test
    void aBoxedInStartHasNoRoute() {
        ground(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    column(4 + dx, 4 + dz, STAND_Y, STAND_Y + 2);
                }
            }
        }
        NavWorld.Outcome o = walk(SHORT, 4, STAND_Y, 4, 8, STAND_Y, 8);
        assertEquals(NavWorld.SEARCH_NO_PATH, o.status());
        assertTrue(o.path().isEmpty());
    }

    @Test
    void anExhaustedBudgetReturnsAPartialTowardTheGoal() {
        ground(40);
        NavWorld.Outcome o = walk(SHORT, new NavLimits(10, Float.MAX_VALUE, 1.0f), 0.5f, STAND_Y, 0.5f,
            35.5f, STAND_Y, 0.5f, 0f, 4);
        assertEquals(NavWorld.SEARCH_PARTIAL_BUDGET, o.status());
        assertEquals(10, o.expansions());
        assertTrue(o.path().x(o.path().size() - 1) > 5.0f, "the partial leads toward the goal");
    }

    @Test
    void maxCostBoundsHowFarTheSearchCommits() {
        ground(40);
        NavWorld.Outcome o = walk(SHORT, new NavLimits(20_000, 5.0f, 1.0f), 0.5f, STAND_Y, 0.5f,
            30.5f, STAND_Y, 0.5f, 0f, 4);
        assertFalse(o.reachedGoal());
        assertTrue(o.cost() <= 5.0f + EPS);
    }

    @Test
    void searchesAreDeterministic() {
        ground(20);
        for (int z = 0; z < 16; z++) {
            column(10, z, STAND_Y, STAND_Y + 1);
        }
        NavWorld.Outcome a = walk(SHORT, 2, STAND_Y, 2, 18, STAND_Y, 3);
        NavWorld.Outcome b = walk(SHORT, 2, STAND_Y, 2, 18, STAND_Y, 3);
        assertEquals(a.cost(), b.cost());
        assertEquals(a.expansions(), b.expansions());
        assertArrayEquals(points(a.path()), points(b.path()));
    }

    @Test
    void aHeavierHeuristicTradesOptimalityForFewerExpansions() {
        ground(30);
        for (int z = 0; z < 24; z++) {
            column(15, z, STAND_Y, STAND_Y + 1);
        }
        NavWorld.Outcome exact = walk(SHORT, new NavLimits(20_000, Float.MAX_VALUE, 1.0f), 2.5f, STAND_Y, 2.5f,
            28.5f, STAND_Y, 2.5f, 0f, 4);
        NavWorld.Outcome greedy = walk(SHORT, new NavLimits(20_000, Float.MAX_VALUE, 3.0f), 2.5f, STAND_Y, 2.5f,
            28.5f, STAND_Y, 2.5f, 0f, 4);
        assertTrue(exact.reachedGoal() && greedy.reachedGoal());
        assertTrue(greedy.expansions() < exact.expansions());
        assertTrue(greedy.cost() >= exact.cost() - EPS, "never cheaper than the optimum");
    }

    @Test
    void limitsRejectNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new NavLimits(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new NavLimits(10, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new NavLimits(10, 1, 0.5f));
    }

    // ---- smoothing (the retired PathSmootherTest) ----------------------------------------------

    private static float[] points(NavPath p) {
        float[] out = new float[p.size() * 3];
        for (int i = 0; i < p.size(); i++) {
            out[i * 3] = (float) p.x(i);
            out[i * 3 + 1] = (float) p.y(i);
            out[i * 3 + 2] = (float) p.z(i);
        }
        return out;
    }

    @Test
    void aStraightRunCollapsesToItsEnds() {
        ground(12);
        assertArrayEquals(new float[] {0.5f, STAND_Y, 0.5f, 9.5f, STAND_Y, 0.5f},
            points(walk(SHORT, 0, STAND_Y, 0, 9, STAND_Y, 0).path()));
    }

    @Test
    void aDiagonalRunCollapsesToo() {
        ground(12);
        assertArrayEquals(new float[] {0.5f, STAND_Y, 0.5f, 6.5f, STAND_Y, 6.5f},
            points(walk(SHORT, 0, STAND_Y, 0, 6, STAND_Y, 6).path()));
    }

    @Test
    void aChangeOfHeightIsACorner() {
        ground(12);
        for (int z = 0; z < 12; z++) {
            for (int x = 5; x < 12; x++) {
                set(x, STAND_Y, z, FULL, 0); // a one-block terrace from x 5 on
            }
        }
        float[] route = points(walk(SHORT, 0, STAND_Y, 0, 9, STAND_Y + 1, 0).path());
        assertArrayEquals(new float[] {0.5f, STAND_Y, 0.5f, 4.5f, STAND_Y, 0.5f, 5.5f, STAND_Y + 1, 0.5f,
            9.5f, STAND_Y + 1, 0.5f}, route, "kept: the last cell below, the first above, the end");
    }

    // ---- air (the retired AirNavDomainTest) ----------------------------------------------------

    private static final int CELL = 4;
    private static final int SKY = 64;

    private void sky() {
        boxed = true;
        for (int x = 0; x < SKY; x++) {
            for (int z = 0; z < SKY; z++) {
                for (int y = 0; y <= 7; y++) {
                    set(x, y, z, FULL, 0);
                }
            }
        }
    }

    private static AirProfile flyer(float cruise, boolean allowUnknown) {
        return new AirProfile(CELL, cruise, 0.35f, 0.5f, 0.15f, allowUnknown, 0.75f, 0, SKY - 1);
    }

    private NavWorld.Outcome fly(AirProfile p, float fx, float fy, float fz, float gx, float gy, float gz, int snap) {
        return fly(p, fx, fy, fz, gx, gy, gz, snap, CELL);
    }

    private NavWorld.Outcome fly(AirProfile p, float fx, float fy, float fz, float gx, float gy, float gz, int snap,
                                 float radius) {
        physics.invalidateAll();
        long id = nav.searchAir(new NavWorld.AirQuery(fx, fy, fz, gx, gy, gz, radius, snap, p,
            new NavLimits(20_000, Float.MAX_VALUE, 1.0f)));
        nav.step(Integer.MAX_VALUE);
        NavWorld.Outcome o = nav.poll(id);
        nav.release(id);
        return o;
    }

    @Test
    void openSkyRoutesStraightAtCruise() {
        sky();
        NavWorld.Outcome o = fly(flyer(32, true), 8, 32, 8, 56, 32, 8, 0);
        assertTrue(o.reachedGoal());
        for (int i = 0; i < o.path().size(); i++) {
            assertEquals(34.0f, o.path().y(i), EPS, "the cruise band's cube centre");
            assertEquals(10.0f, o.path().z(i), EPS, "no sideways detour");
        }
        assertEquals(2, o.path().size(), "string-pulled to one straight leg");
    }

    @Test
    void aRidgeItCanTopIsClimbedOver() {
        sky();
        for (int z = 0; z < SKY; z++) {
            for (int y = 8; y <= 39; y++) {
                set(32, y, z, FULL, 0);
                set(33, y, z, FULL, 0);
            }
        }
        NavWorld.Outcome o = fly(flyer(32, false), 8, 32, 8, 56, 32, 8, 0);
        assertTrue(o.reachedGoal());
        double highest = 0;
        for (int i = 0; i < o.path().size(); i++) {
            highest = Math.max(highest, o.path().y(i));
        }
        assertTrue(highest >= 40.0f, "the route clears the ridge: " + highest);
    }

    @Test
    void aWallTooTallToTopIsFlownAroundThroughItsGap() {
        sky();
        for (int z = 0; z < 44; z++) {
            for (int y = 8; y < SKY; y++) {
                set(32, y, z, FULL, 0);
                set(33, y, z, FULL, 0);
            }
        }
        NavWorld.Outcome o = fly(flyer(32, false), 8, 32, 8, 56, 32, 8, 0);
        assertTrue(o.reachedGoal());
        boolean usedGap = false;
        for (int i = 0; i < o.path().size(); i++) {
            usedGap |= o.path().z(i) >= 44.0f;
        }
        assertTrue(usedGap);
    }

    @Test
    void aSealedGoalReturnsAPartialRoute() {
        sky();
        for (int z = 0; z < SKY; z++) {
            for (int y = 0; y < SKY; y++) {
                set(32, y, z, FULL, 0);
                set(33, y, z, FULL, 0);
            }
        }
        NavWorld.Outcome o = fly(flyer(32, false), 8, 32, 8, 56, 32, 8, 0);
        assertFalse(o.reachedGoal());
        assertFalse(o.path().isEmpty(), "an unreachable goal still points the flyer somewhere");
    }

    @Test
    void waterIsNotFlyable() {
        sky();
        for (int x = 16; x < 24; x++) {
            for (int z = 0; z < SKY; z++) {
                for (int y = 8; y < SKY; y++) {
                    set(x, y, z, 0, SOURCE); // a curtain of water across the whole sky
                }
            }
        }
        assertFalse(fly(flyer(12, false), 8, 12, 8, 40, 12, 8, 0).reachedGoal(),
            "a flight corridor is air; water is landed on, not flown through");
    }

    @Test
    void aGoalInsideTerrainSnapsOutOntoAir() {
        sky();
        for (int x = 30; x < 36; x++) {
            for (int z = 30; z < 36; z++) {
                for (int y = 8; y <= 39; y++) {
                    set(x, y, z, FULL, 0);
                }
            }
        }
        assertFalse(fly(flyer(32, false), 8, 32, 8, 32, 32, 32, 0, 0f).reachedGoal(), "buried, no snapping");
        assertTrue(fly(flyer(32, false), 8, 32, 8, 32, 32, 32, 2, 0f).reachedGoal(), "snapped onto flyable air");
    }

    @Test
    void unloadedSkyIsPassableOnlyWhenAllowedAndAtAPremium() {
        sky();
        // From inside the box to a goal over unloaded sky (x < UNLOADED_BELOW_X reads as not loaded).
        NavWorld.Outcome refusing = fly(flyer(32, false), 8, 32, 8, UNLOADED_BELOW_X - 20, 32, 8, 0);
        NavWorld.Outcome allowing = fly(flyer(32, true), 8, 32, 8, UNLOADED_BELOW_X - 20, 32, 8, 0);
        assertFalse(refusing.reachedGoal());
        assertTrue(allowing.reachedGoal());
        NavWorld.Outcome known = fly(flyer(32, true), 8, 32, 8, 56, 32, 8, 0);
        float perCellKnown = known.cost() / 12.0f;
        float perCellUnknown = allowing.cost() / ((8 - (UNLOADED_BELOW_X - 20)) / 4.0f);
        assertTrue(perCellUnknown > perCellKnown, "unknown air costs more per cell flown");
    }

    @Test
    void theFlightFloorIsRespected() {
        sky();
        AirProfile high = new AirProfile(CELL, 40, 0.35f, 0.5f, 0.15f, false, 0.75f, 32, SKY - 1);
        NavWorld.Outcome o = fly(high, 8, 40, 8, 56, 12, 8, 0);
        for (int i = 0; i < o.path().size(); i++) {
            assertTrue(o.path().y(i) >= 32.0f, "never below the floor: " + o.path().y(i));
        }
        assertNotEquals(NavWorld.SEARCH_FOUND, o.status(), "a goal below the floor is not reached");
    }
}
