package com.openmason.engine.rendering.particles;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** GL-free simulation behaviour: budget, lifetime, integration, emitters, shapes, collision, anchor. */
class ParticleSystemTest {

    private static ParticleEffect.Builder still(String name) {
        return ParticleEffect.builder(name).lifetime(10, 10).speed(0, 0).spread(0).rate(0).fade(0, 0);
    }

    @Test
    void budgetIsHardAndOverflowIsCountedNotGrown() {
        ParticleSystem s = new ParticleSystem(100);
        ParticleEffect e = still("e").priority(ParticleEffect.Priority.CRITICAL).build();
        assertEquals(60, s.burst(e, 0, 0, 0, 60));
        assertEquals(40, s.burst(e, 0, 0, 0, 60));
        assertEquals(0, s.burst(e, 0, 0, 0, 5));
        assertEquals(100, s.liveCount());
        assertEquals(100, s.spawnedTotal());
        assertEquals(25, s.droppedTotal());
        assertEquals(100, s.px.length, "storage never grows past the budget");
    }

    @Test
    void expiredParticlesAreSwapRemovedWithoutDisturbingSurvivors() {
        ParticleSystem s = new ParticleSystem(16);
        ParticleEffect shortLived = still("short").lifetime(0.5f, 0.5f).build();
        ParticleEffect longLived = still("long").lifetime(5, 5).build();
        // Interleave so removals hit the middle of the pool.
        for (int i = 0; i < 6; i++) {
            s.burst(i % 2 == 0 ? shortLived : longLived, i, 0, 0, 1);
        }
        for (int i = 0; i < 3; i++) {
            s.update(0.2f);
        }
        assertEquals(3, s.liveCount());
        Set<Double> xs = new HashSet<>();
        for (int i = 0; i < s.liveCount(); i++) {
            assertSame(longLived, s.effectOf(i));
            xs.add(s.worldX(i));
        }
        assertEquals(Set.of(1.0, 3.0, 5.0), xs);
    }

    @Test
    void gravityAndDragIntegrateLikeTheAnalyticSolution() {
        ParticleSystem s = new ParticleSystem(4);
        ParticleEffect falling = still("fall").gravity(0, -10, 0).build();
        s.burst(falling, 0, 100, 0, 1);
        float dt = 1f / 240f;
        for (int i = 0; i < 240; i++) {
            s.update(dt);
        }
        // Semi-implicit Euler: y = y0 - g·dt²·n(n+1)/2.
        double expected = 100 - 10 * dt * dt * 240 * 241 / 2.0;
        assertEquals(expected, s.worldY(0), 1e-3);
        assertEquals(-10f, s.vy[0], 1e-3f);

        ParticleSystem d = new ParticleSystem(4);
        ParticleEffect dragged = still("drag").speed(4, 4).direction(1, 0, 0).drag(2).build();
        d.burst(dragged, 0, 0, 0, 1);
        for (int i = 0; i < 60; i++) {
            d.update(1f / 60f);
        }
        assertEquals(4 * Math.exp(-2), d.vx[0], 1e-4, "v(1s) = v0·e^(-drag)");
    }

    @Test
    void hitchesAreClampedToMaxStep() {
        ParticleSystem s = new ParticleSystem(4);
        s.burst(still("e").speed(1, 1).direction(1, 0, 0).build(), 0, 0, 0, 1);
        s.update(5f);
        assertEquals(ParticleSystem.MAX_STEP, s.worldX(0), 1e-6);
        s.update(0f);
        s.update(-1f);
        s.update(Float.NaN);
        assertEquals(ParticleSystem.MAX_STEP, s.worldX(0), 1e-6, "non-positive dt is a no-op");
    }

    @Test
    void emitterRateIsExactOverTimeAtLowAndFractionalRates() {
        ParticleSystem s = new ParticleSystem(10_000);
        ParticleEffect e = still("rate").rate(7.5f).build();
        s.createEmitter(e, 0, 0, 0);
        for (int i = 0; i < 600; i++) {
            s.update(1f / 60f);
        }
        assertEquals(75, s.spawnedTotal(), 1, "10 s × 7.5/s");

        ParticleSystem scaled = new ParticleSystem(10_000);
        scaled.createEmitter(e, 0, 0, 0).setRateScale(2);
        for (int i = 0; i < 60; i++) {
            scaled.update(1f / 60f);
        }
        assertEquals(15, scaled.spawnedTotal(), 1);
    }

    @Test
    void continuousEmissionSpreadsBirthsAcrossTheStep() {
        ParticleSystem s = new ParticleSystem(1000);
        ParticleEffect e = still("stream").rate(400).speed(10, 10).direction(1, 0, 0).build();
        s.createEmitter(e, 0, 0, 0);
        s.update(0.1f); // one coarse step spawns 40
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = 0; i < s.liveCount(); i++) {
            min = Math.min(min, s.worldX(i));
            max = Math.max(max, s.worldX(i));
        }
        assertTrue(max - min > 0.6, "births spread along the stream, not clumped: " + (max - min));
        assertTrue(max <= 1.0 + 1e-4, "no particle is older than the step");
    }

    @Test
    void durationStopsAndOptionallyRemovesTheEmitter() {
        ParticleSystem s = new ParticleSystem(1000);
        ParticleEffect e = still("d").rate(100).build();
        ParticleEmitter keep = s.createEmitter(e, 0, 0, 0).setDuration(0.5f, false);
        ParticleEmitter drop = s.createEmitter(e, 0, 0, 0).setDuration(0.25f, true);
        for (int i = 0; i < 60; i++) {
            s.update(1f / 60f);
        }
        assertFalse(keep.isActive());
        assertFalse(keep.isRemoved());
        assertTrue(drop.isRemoved());
        assertEquals(1, s.emitterCount());
        assertEquals(75, s.spawnedTotal(), 2, "0.5 s + 0.25 s at 100/s");
    }

    @Test
    void sameSeedReplaysIdentically() {
        ParticleEffect e = ParticleLibrary.Presets.MAGIC;
        ParticleSystem a = new ParticleSystem(500, 42);
        ParticleSystem b = new ParticleSystem(500, 42);
        for (ParticleSystem s : new ParticleSystem[]{a, b}) {
            s.createEmitter(e, 1, 2, 3);
            for (int i = 0; i < 90; i++) {
                s.update(1f / 60f);
            }
        }
        assertEquals(a.liveCount(), b.liveCount());
        for (int i = 0; i < a.liveCount(); i++) {
            assertEquals(a.worldX(i), b.worldX(i));
            assertEquals(a.worldY(i), b.worldY(i));
            assertEquals(a.tint[i], b.tint[i]);
        }
    }

    @Test
    void emitterShapesBoundSpawnPositions() {
        ParticleSystem s = new ParticleSystem(3000);
        ParticleEffect sphere = still("sphere").sphere(2).build();
        ParticleEffect box = still("box").box(1, 2, 3).build();
        ParticleEffect disc = still("disc").disc(1.5f).build();
        s.burst(sphere, 10, 10, 10, 1000);
        for (int i = 0; i < 1000; i++) {
            double dx = s.worldX(i) - 10, dy = s.worldY(i) - 10, dz = s.worldZ(i) - 10;
            assertTrue(Math.sqrt(dx * dx + dy * dy + dz * dz) <= 2 + 1e-4);
        }
        s.clearParticles();
        s.burst(box, 0, 0, 0, 1000);
        double maxZ = 0;
        for (int i = 0; i < 1000; i++) {
            assertTrue(Math.abs(s.worldX(i)) <= 1 + 1e-5 && Math.abs(s.worldY(i)) <= 2 + 1e-5
                    && Math.abs(s.worldZ(i)) <= 3 + 1e-5);
            maxZ = Math.max(maxZ, Math.abs(s.worldZ(i)));
        }
        assertTrue(maxZ > 2.5, "box fills its extent");
        s.clearParticles();
        s.burst(disc, 0, 5, 0, 1000);
        for (int i = 0; i < 1000; i++) {
            assertEquals(5, s.worldY(i), 1e-6, "disc is flat in XZ");
            assertTrue(Math.hypot(s.worldX(i), s.worldZ(i)) <= 1.5 + 1e-4);
        }
    }

    @Test
    void launchVelocitiesStayInsideTheSpreadCone() {
        ParticleSystem s = new ParticleSystem(4000);
        ParticleEffect cone = still("cone").speed(3, 3).direction(1, 1, 0).spread(20).build();
        s.burst(cone, 0, 0, 0, 1000);
        // Emitter override direction too.
        s.burst(cone, 0, 0, 0, 0, 0, -5, 1000, 0xFFFFFFFF);
        double cosLimit = Math.cos(Math.toRadians(20)) - 1e-4;
        float inv = (float) (1 / Math.sqrt(2));
        for (int i = 0; i < 2000; i++) {
            float vx = s.vx[i], vy = s.vy[i], vz = s.vz[i];
            float speed = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
            assertEquals(3f, speed, 1e-4f);
            double cos = i < 1000 ? (vx * inv + vy * inv) / speed : -vz / speed;
            assertTrue(cos >= cosLimit, "inside the 20° cone: " + Math.toDegrees(Math.acos(cos)));
        }
    }

    @Test
    void fullSpreadCoversEveryDirection() {
        ParticleSystem s = new ParticleSystem(8000);
        s.burst(still("omni").speed(1, 1).spread(180).build(), 0, 0, 0, 4000);
        double sx = 0, sy = 0, sz = 0;
        int down = 0;
        for (int i = 0; i < 4000; i++) {
            sx += s.vx[i];
            sy += s.vy[i];
            sz += s.vz[i];
            if (s.vy[i] < -0.5f) down++;
        }
        assertEquals(0, sx / 4000, 0.05);
        assertEquals(0, sy / 4000, 0.05);
        assertEquals(0, sz / 4000, 0.05);
        assertEquals(1000, down, 120, "a quarter of uniform directions have y < -0.5");
    }

    @Test
    void collidingParticlesLandOnSolidCellsAndSettle() {
        ParticleSystem s = new ParticleSystem(200);
        s.setCollider((x, y, z) -> y < 0);
        ParticleEffect debris = still("debris").speed(2, 4).spread(60).gravity(0, -20, 0).collides(0.3f, 0.5f).build();
        ParticleEffect ghost = still("ghost").gravity(0, -20, 0).build();
        s.burst(debris, 0.5, 2, 0.5, 100);
        s.burst(ghost, 0.5, 2, 0.5, 1);
        for (int i = 0; i < 180; i++) {
            s.update(1f / 60f);
            for (int p = 0; p < s.liveCount(); p++) {
                if (s.effectOf(p) == debris) {
                    assertTrue(s.worldY(p) >= 0.12, "never inside the floor");
                }
            }
        }
        int resting = 0;
        for (int p = 0; p < s.liveCount(); p++) {
            if (s.effectOf(p) == debris) {
                assertEquals(0.125, s.worldY(p), 0.02, "settled on the floor, a radius (half of 0.25) above it");
                if (s.vy[p] == 0f) resting++;
            } else {
                assertTrue(s.worldY(p) < -10, "non-colliding effects fall through");
            }
        }
        assertEquals(100, resting);
    }

    @Test
    void movingTheAnchorKeepsWorldPositions() {
        ParticleSystem s = new ParticleSystem(10);
        s.burst(still("e").build(), 1_000_000.25, 64.5, -2_000_000.75, 3);
        s.setAnchor(1_000_000, 64, -2_000_000);
        for (int i = 0; i < 3; i++) {
            assertEquals(1_000_000.25, s.worldX(i), 1e-6);
            assertEquals(64.5, s.worldY(i), 1e-6);
            assertEquals(-2_000_000.75, s.worldZ(i), 1e-6);
            assertEquals(0.25f, s.px[i], 1e-6f, "stored relative to the anchor at full precision");
        }
    }

    @Test
    void tintMultipliesColoursAndJitterOnlyDarkens() {
        ParticleSystem s = new ParticleSystem(500);
        ParticleEffect e = still("t").colors(0xFFFFFFFF, 0x80808080).colorJitter(0.5f).build();
        s.burst(e, 0, 0, 0, 0, 1, 0, 400, 0xFF000080);
        for (int i = 0; i < 400; i++) {
            int c = s.tint[i];
            int r = c >>> 24;
            assertEquals(0, (c >>> 16) & 0xFF);
            assertEquals(0, (c >>> 8) & 0xFF);
            assertEquals(0x80, c & 0xFF, "alpha tinted, never jittered");
            assertTrue(r >= 127 && r <= 255, "brightness jitter within 50%: " + r);
        }
    }

    @Test
    void invalidEffectsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ParticleEffect.builder("x").lifetime(0, 1).build());
        assertThrows(IllegalArgumentException.class, () -> ParticleEffect.builder("x").additive(2).build());
        assertThrows(IllegalArgumentException.class, () -> ParticleEffect.builder("x").spread(200).build());
        assertThrows(IllegalArgumentException.class, () -> ParticleEffect.builder("x").speed(2, 1).build());
        assertThrows(IllegalArgumentException.class, () -> ParticleEffect.builder(" ").build());
        assertThrows(IllegalArgumentException.class, () -> new ParticleSystem(0));
        assertThrows(IllegalArgumentException.class, () -> new ParticleSystem(ParticleSystem.MAX_CAPACITY + 1));
    }
}
