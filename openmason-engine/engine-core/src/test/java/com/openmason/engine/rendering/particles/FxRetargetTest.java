package com.openmason.engine.rendering.particles;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FxInstance#retarget} (OM #104): an editor swaps the system of a running instance on every edit, and the
 * simulation must carry on - clock, particles, timelines and parameter values - instead of restarting.
 */
@Tag("regression")
class FxRetargetTest {

    private static final float DT = 1f / 60f;

    private static ParticleEffect.Builder still(String name) {
        return ParticleEffect.builder(name).lifetime(100, 100).speed(0, 0).spread(0).rate(0).fade(0, 0)
                .size(1, 1).color(0xFFFFFFFF);
    }

    private static void run(FxWorld fx, float seconds) {
        int frames = Math.round(seconds / DT);
        for (int i = 0; i < frames; i++) {
            fx.update(DT);
        }
    }

    @Test
    void retargetKeepsTheClockParticlesAndRunningEmitters() {
        ParticleEffect steady = still("steady").rate(30).build();
        FxSystem v1 = FxSystem.builder("fx").emitter(FxEmitter.builder("a", steady).build()).build();
        ParticleSystem s = new ParticleSystem(10_000);
        FxWorld world = new FxWorld(s);
        FxInstance inst = world.play(v1, 0, 0, 0);
        run(world, 1f);
        int before = s.liveCount();
        double time = inst.time();
        assertTrue(before > 20, "emitting: " + before);

        FxSystem v2 = v1.toBuilder().emitters(java.util.List.of(
                FxEmitter.builder("a", steady).rateScale(2f).build())).build();
        inst.retarget(v2);
        assertSame(v2, inst.system());
        assertEquals(time, inst.time(), "the clock carries on");
        assertEquals(before, s.liveCount(), "no particle is lost");
        assertEquals(before, inst.liveParticles());

        run(world, 1f);
        int added = s.liveCount() - before;
        assertTrue(added > 50 && added < 70, "emits at the new rate (about 60/s), not from scratch: " + added);
    }

    @Test
    void aNewEmitterFiresOnlyTheCycleDueNowNotEveryMissedOne() {
        ParticleEffect dot = still("dot").build();
        FxSystem v1 = FxSystem.builder("fx").emitter(FxEmitter.builder("a", dot).burst(1).duration(0).build()).build();
        ParticleSystem s = new ParticleSystem(10_000);
        FxWorld world = new FxWorld(s);
        FxInstance inst = world.play(v1, 0, 0, 0);
        run(world, 5f);
        assertEquals(1, s.liveCount());

        // Bursts 10 every 0.5 s forever: ten cycles have "passed" at t = 5.
        FxSystem v2 = v1.toBuilder().emitter(FxEmitter.builder("b", dot).burst(10).duration(0).interval(0.5f)
                .loops(0).build()).build();
        inst.retarget(v2);
        world.update(DT);
        assertEquals(11, s.liveCount(), "one cycle fires, not ten");
        run(world, 0.5f);
        assertEquals(21, s.liveCount(), "then it follows its period");
    }

    @Test
    void removedEmittersParticlesStayButFireNoHandlersAndRenamedRoutesFollow() {
        ParticleEffect brief = still("brief").lifetime(0.5f, 0.5f).build();
        ParticleEffect child = still("child").build();
        FxSystem v1 = FxSystem.builder("fx")
                .emitter(FxEmitter.builder("x", still("pad").build()).eventOnly().build())
                .emitter(FxEmitter.builder("a", brief).burst(5).duration(0).build())
                .emitter(FxEmitter.builder("b", child).eventOnly().build())
                .on(FxEventHandler.spawn(FxEventHandler.Trigger.DEATH, "a", "b", 1))
                .build();
        ParticleSystem s = new ParticleSystem(1000);
        FxWorld world = new FxWorld(s);
        FxInstance inst = world.play(v1, 0, 0, 0);
        world.update(DT);
        assertEquals(5, s.liveCount());

        // Emitter "x" goes away: "a" moves from index 1 to 0, and its deaths must still route to "b".
        FxSystem v2 = FxSystem.builder("fx")
                .emitter(FxEmitter.builder("a", brief).burst(5).duration(0).build())
                .emitter(FxEmitter.builder("b", child).eventOnly().build())
                .on(FxEventHandler.spawn(FxEventHandler.Trigger.DEATH, "a", "b", 1))
                .build();
        inst.retarget(v2);
        run(world, 1f);
        assertEquals(5, s.liveCount(), "five deaths spawned five children");
        for (int i = 0; i < s.liveCount(); i++) {
            assertSame(child, s.effectOf(i));
        }

        // Now "a" disappears while a fresh burst is alive: its deaths fire nothing, but they still count.
        FxSystem v3 = FxSystem.builder("fx")
                .emitter(FxEmitter.builder("b", child).eventOnly().build())
                .build();
        FxInstance second = world.play(v2, 0, 0, 0);
        world.update(DT);
        int alive = s.liveCount();
        second.retarget(v3);
        run(world, 1f);
        assertEquals(alive - 5, s.liveCount(), "the orphaned burst died without spawning");
        assertEquals(0 + inst.liveParticles() + second.liveParticles(), s.liveCount(), "life counts stay exact");
    }

    @Test
    void parameterValuesSurviveByNameAndType() {
        ParticleEffect steady = still("steady").rate(10).build();
        FxSystem v1 = FxSystem.builder("fx").emitter(FxEmitter.builder("a", steady).build())
                .parameter(FxParameter.scalar("intensity", 1f))
                .parameter(FxParameter.scalar("gone", 1f))
                .build();
        ParticleSystem s = new ParticleSystem(1000);
        FxWorld world = new FxWorld(s);
        FxInstance inst = world.play(v1, 0, 0, 0);
        inst.setParam("intensity", 0.25f);
        world.update(DT);

        FxSystem v2 = v1.toBuilder().parameters(java.util.List.of(
                FxParameter.scalar("intensity", 1f), FxParameter.scalar("fresh", 3f))).build();
        inst.retarget(v2);
        assertEquals(0.25f, inst.param("intensity"), "kept");
        assertEquals(3f, inst.param("fresh"), "defaults for new ones");
    }

    @Test
    void retargetingToTheSameSystemIsANoOp() {
        FxSystem fx = FxSystem.of(still("dot").rate(5).build(), 1);
        FxWorld world = new FxWorld(new ParticleSystem(100));
        FxInstance inst = world.play(fx, 0, 0, 0);
        run(world, 0.5f);
        double t = inst.time();
        inst.retarget(fx);
        assertEquals(t, inst.time());
        assertSame(fx, inst.system());
    }
}
