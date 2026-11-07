package com.openmason.engine.rendering.particles;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** GL-free: on-demand storage, budget rationing, sub-emitters, size-aware and partial-block collision. */
class ParticleFeaturesTest {

    private static ParticleEffect.Builder still(String name) {
        return ParticleEffect.builder(name).lifetime(10, 10).speed(0, 0).spread(0).rate(0).fade(0, 0);
    }

    // ------------------------------------------------------------------------------------------------
    // Memory
    // ------------------------------------------------------------------------------------------------

    @Test
    void storageStartsSmallAndGrowsWithLoadUpToTheBudget() {
        ParticleSystem s = new ParticleSystem(100_000);
        assertEquals(ParticleSystem.INITIAL_SLOTS, s.allocatedSlots(), "a big budget costs nothing up front");
        long idle = s.memoryBytes();
        ParticleEffect e = still("e").build();
        s.burst(e, 0, 0, 0, 1000);
        assertTrue(s.allocatedSlots() >= 1000 && s.allocatedSlots() < 4096, "grew to fit: " + s.allocatedSlots());
        assertTrue(s.memoryBytes() > idle);
        for (int i = 0; i < s.liveCount(); i++) {
            assertEquals(0, s.worldX(i), "particles survive growth intact");
        }
        s.burst(e, 0, 0, 0, 200_000);
        assertEquals(90_000, s.liveCount(), "NORMAL priority fills at most 90% of the budget");
        assertTrue(s.allocatedSlots() <= s.capacity(), "never past the budget");
    }

    @Test
    void prioritiesReserveTheBudgetForMoreImportantEffects() {
        ParticleSystem s = new ParticleSystem(100);
        ParticleEffect ambient = still("weather").priority(ParticleEffect.Priority.AMBIENT).build();
        ParticleEffect normal = still("dust").build();
        ParticleEffect critical = still("hit").priority(ParticleEffect.Priority.CRITICAL).build();
        assertEquals(60, s.burst(ambient, 0, 0, 0, 500), "ambient stops at 60%");
        assertEquals(30, s.burst(normal, 0, 0, 0, 500), "normal stops at 90%");
        assertEquals(10, s.burst(critical, 0, 0, 0, 500), "critical gets the rest");
        assertEquals(0, s.burst(ambient, 0, 0, 0, 1));
        assertEquals((500 - 60) + (500 - 30) + (500 - 10) + 1, s.droppedTotal());
    }

    @Test
    void maxLiveCapsOneEffectAndFreesAsParticlesDie() {
        ParticleSystem s = new ParticleSystem(1000);
        ParticleEffect capped = still("capped").lifetime(0.5f, 0.5f).maxLive(25).build();
        assertEquals(25, s.burst(capped, 0, 0, 0, 100));
        assertEquals(25, s.liveCount(capped));
        assertEquals(10, s.burst(still("other").build(), 0, 0, 0, 10), "other effects are unaffected");
        for (int i = 0; i < 4; i++) {
            s.update(0.2f);
        }
        assertEquals(0, s.liveCount(capped), "deaths are counted back");
        assertEquals(25, s.burst(capped, 0, 0, 0, 100));
    }

    @Test
    void spawnsThinOutBeyondTheLodDistance() {
        ParticleEffect e = still("far").lodDistance(10).build();
        ParticleSystem s = new ParticleSystem(100_000);
        assertEquals(1000, s.burst(e, 50, 0, 0, 1000), "no viewer = no thinning");
        s.clearParticles();
        s.setViewer(0, 0, 0);
        assertEquals(1000, s.burst(e, 8, 0, 0, 1000), "inside the distance: everything");
        int half = s.burst(e, 15, 0, 0, 1000);
        assertEquals(500, half, 60, "1.5× the distance: about half");
        assertEquals(0, s.burst(e, 25, 0, 0, 1000), "past twice the distance: nothing");
        assertEquals(500 + 1000, s.lodSkippedTotal(), 60);
        assertEquals(1000, s.burst(still("near-only").build(), 25, 0, 0, 1000), "lodDistance 0 = never thinned");
    }

    // ------------------------------------------------------------------------------------------------
    // Sub-emitters
    // ------------------------------------------------------------------------------------------------

    @Test
    void deathSubEmittersSpawnWhereTheParentDiedWithInheritedVelocity() {
        ParticleEffect child = still("child").build();
        ParticleEffect parent = still("parent").lifetime(0.5f, 0.5f).speed(4, 4).direction(1, 0, 0)
                .subEmitter(ParticleEffect.SubEmitter.on(ParticleEffect.Trigger.DEATH, child, 3).inheriting(0.5f))
                .build();
        ParticleSystem s = new ParticleSystem(100);
        s.burst(parent, 0, 0, 0, 1);
        for (int i = 0; i < 3; i++) {
            s.update(0.2f);
        }
        assertEquals(0, s.liveCount(parent));
        assertEquals(3, s.liveCount(child));
        for (int i = 0; i < s.liveCount(); i++) {
            assertEquals(1.6, s.worldX(i), 1e-4, "spawned where the parent last was (4 b/s × 0.4 s)");
            assertEquals(2f, s.vx[i], 1e-5f, "half the parent's velocity");
        }
    }

    @Test
    void birthAndTrailSubEmittersFire() {
        ParticleEffect spark = still("spark").lifetime(0.2f, 0.2f).build();
        ParticleEffect rocket = still("rocket").speed(10, 10)
                .subEmitter(ParticleEffect.SubEmitter.on(ParticleEffect.Trigger.BIRTH, spark, 2))
                .subEmitter(ParticleEffect.SubEmitter.trail(spark, 120)).build();
        ParticleSystem s = new ParticleSystem(10_000);
        s.burst(rocket, 0, 0, 0, 1);
        assertEquals(2, s.liveCount(spark), "birth sub-emitters fire with the burst");
        long before = s.spawnedTotal();
        for (int i = 0; i < 60; i++) {
            s.update(1f / 60f);
        }
        assertEquals(120, s.spawnedTotal() - before, 12, "trail at 120/s for one second");
        double maxY = 0;
        for (int i = 0; i < s.liveCount(); i++) {
            if (s.effectOf(i) == spark) maxY = Math.max(maxY, s.worldY(i));
        }
        assertTrue(maxY > 9, "the trail follows the rocket up: " + maxY);
    }

    @Test
    void collisionSubEmittersFireOnImpactNotWhileResting() {
        ParticleEffect splash = still("splash").lifetime(0.1f, 0.1f).build();
        ParticleEffect drop = still("drop").gravity(0, -20, 0).collides(0f, 0f)
                .subEmitter(ParticleEffect.SubEmitter.on(ParticleEffect.Trigger.COLLISION, splash, 1)).build();
        ParticleSystem s = new ParticleSystem(100);
        s.setCollider((x, y, z) -> y < 0);
        s.burst(drop, 0.5, 3, 0.5, 1);
        long splashes = 0;
        for (int i = 0; i < 120; i++) {
            long before = s.spawnedTotal();
            s.update(1f / 60f);
            splashes += s.spawnedTotal() - before;
        }
        assertEquals(1, splashes, "one splash on landing, none while it lies there");
    }

    @Test
    void subEmitterEventsBeyondTheQueueAreDroppedAndCounted() {
        ParticleEffect child = still("c").build();
        ParticleEffect parent = still("p").lifetime(0.1f, 0.1f)
                .subEmitter(ParticleEffect.SubEmitter.on(ParticleEffect.Trigger.DEATH, child, 1)).build();
        int parents = ParticleSystem.EVENT_CAPACITY + 500;
        ParticleSystem s = new ParticleSystem(ParticleSystem.MAX_CAPACITY);
        s.burst(parent, 0, 0, 0, parents);
        s.update(0.2f);
        assertEquals(ParticleSystem.EVENT_CAPACITY, s.liveCount(child));
        assertEquals(500, s.eventsDroppedTotal());
    }

    @Test
    void fireworkPresetClimbsTrailsAndBursts() {
        ParticleSystem s = new ParticleSystem(20_000, 5);
        s.burst(ParticleLibrary.Presets.FIREWORK, 0, 0, 0, 1);
        for (int i = 0; i < 80; i++) {
            s.update(1f / 60f);
        }
        assertEquals(0, s.liveCount(ParticleLibrary.Presets.FIREWORK), "the rocket burned out");
        assertTrue(s.liveCount(ParticleLibrary.Presets.FIREWORK_BURST) > 100, "it burst into stars");
        assertTrue(s.liveCount(ParticleLibrary.Presets.FIREWORK_TRAIL) > 0, "trail still glittering");
    }

    @Test
    void rainSplashesAndRipplesWhereItLands() {
        ParticleSystem s = new ParticleSystem(20_000, 9);
        s.setCollider((x, y, z) -> y < 0);
        s.createEmitter(ParticleLibrary.Presets.RAIN, 0, 8, 0);
        for (int i = 0; i < 90; i++) {
            s.update(1f / 60f);
        }
        assertTrue(s.liveCount(ParticleLibrary.Presets.RIPPLE) > 50, "ripples: " + s.liveCount(ParticleLibrary.Presets.RIPPLE));
        assertTrue(s.liveCount(ParticleLibrary.Presets.RAIN_SPLASH) > 50);
        for (int i = 0; i < s.liveCount(); i++) {
            if (s.effectOf(i) == ParticleLibrary.Presets.RIPPLE) {
                assertEquals(0, s.worldY(i), 0.05, "ripples sit on the surface");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Collision
    // ------------------------------------------------------------------------------------------------

    @Test
    void particlesRestOnTopOfSurfacesNotHalfInsideThem() {
        ParticleSystem s = new ParticleSystem(10);
        s.setCollider((x, y, z) -> y < 0);
        s.burst(still("ball").size(0.5f, 0.5f).gravity(0, -20, 0).collides(0f, 0.5f).build(), 0.5, 2, 0.5, 1);
        for (int i = 0; i < 120; i++) {
            s.update(1f / 60f);
        }
        assertEquals(0.25, s.worldY(0), 0.03, "centre a radius above the floor");
    }

    @Test
    void partialHeightBlocksAreLandedOnAtTheirHeight() {
        ParticleCollider snowLayer = new ParticleCollider() {
            @Override
            public boolean isSolid(int x, int y, int z) {
                return y < 0 || y == 0;
            }

            @Override
            public float solidHeight(int x, int y, int z) {
                return y < 0 ? 1f : y == 0 ? 0.375f : 0f;
            }
        };
        ParticleSystem s = new ParticleSystem(10);
        s.setCollider(snowLayer);
        s.burst(still("flake").size(0.1f, 0.1f).gravity(0, -10, 0).collides(0f, 0f).build(), 0.5, 2, 0.5, 1);
        for (int i = 0; i < 180; i++) {
            s.update(1f / 60f);
        }
        assertEquals(0.375 + 0.05, s.worldY(0), 0.03, "rests on the snow layer, not the block below it");
    }

    @Test
    void fastParticlesCannotTunnelThroughAThinWall() {
        ParticleSystem s = new ParticleSystem(10);
        s.setCollider((x, y, z) -> x == 5);
        s.burst(still("bullet").size(0.1f, 0.1f).speed(40, 40).direction(1, 0, 0).collides(0f, 1f).build(),
                0.5, 0.5, 0.5, 1);
        s.update(0.25f); // 10 blocks in one step
        assertTrue(s.worldX(0) < 5, "stopped at the wall, not through it: " + s.worldX(0));
        assertEquals(0f, s.vx[0], 1e-6f, "no bounce");
    }

    @Test
    void particlesSlideAlongWallsAndKeepTheirOtherAxes() {
        ParticleSystem s = new ParticleSystem(10);
        s.setCollider((x, y, z) -> x >= 2);
        s.burst(still("slide").size(0.1f, 0.1f).speed(2, 2).direction(1, 0, 1).collides(0f, 1f).build(),
                0.5, 0.5, 0.5, 1);
        for (int i = 0; i < 120; i++) {
            s.update(1f / 60f);
        }
        assertTrue(s.worldX(0) < 2, "held by the wall");
        assertTrue(s.worldZ(0) > 2, "still moving along it: " + s.worldZ(0));
    }
    // ------------------------------------------------------------------------------------------------
    // Live editing
    // ------------------------------------------------------------------------------------------------

    @Test
    void replacingAnEffectHotSwapsLiveParticlesEmittersAndSubEmitters() {
        ParticleEffect v1 = still("edit").lifetime(5, 5).gravity(0, 0, 0).rate(60).build();
        ParticleEffect parent = still("parent").lifetime(0.1f, 0.1f)
                .subEmitter(ParticleEffect.SubEmitter.on(ParticleEffect.Trigger.DEATH, v1, 1)).build();
        ParticleSystem s = new ParticleSystem(1000);
        ParticleEmitter emitter = s.createEmitter(v1, 0, 0, 0);
        s.update(0.5f);
        int alive = s.liveCount(v1);
        assertTrue(alive > 0);

        ParticleEffect v2 = v1.toBuilder().gravity(0, -10, 0).build();
        assertTrue(s.replace(v1, v2));
        assertSame(v2, emitter.effect(), "the emitter now spawns the edited effect");
        assertEquals(alive, s.liveCount(v2), "live particles carried over, none restarted");
        assertEquals(0, s.liveCount(v1));
        s.update(0.25f);
        for (int i = 0; i < s.liveCount(); i++) {
            assertSame(v2, s.effectOf(i));
            if (s.age[i] > 0.25f) { // alive before the swap (newborns of this step have not fallen yet)
                assertTrue(s.vy[i] < 0f, "old particles obey the new gravity");
            }
        }

        // A sub-emitter that targeted the old effect now spawns the new one.
        s.burst(parent, 0, 0, 0, 1);
        int before = s.liveCount(v2);
        s.update(0.2f);
        assertTrue(s.liveCount(v2) > before);
        assertFalse(s.replace(still("never-used").build(), v2), "unknown effects are not swapped");
    }
}
