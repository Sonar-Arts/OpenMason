package com.openmason.engine.rendering.particles;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** GL-free tests of composite FX (OM #98): timelines, parameters, events, outputs, instances and pooling. */
class FxWorldTest {

    private static final float DT = 1f / 60f;
    private static final ParticleCollider FLOOR = (x, y, z) -> y < 0;

    /** Particles that sit still for a long time (counting spawns). */
    private static ParticleEffect.Builder still(String name) {
        return ParticleEffect.builder(name).lifetime(100, 100).speed(0, 0).spread(0).rate(0).fade(0, 0)
                .size(1, 1).color(0xFFFFFFFF);
    }

    /** Records host callbacks. */
    static final class Recorder implements FxHost {
        final List<String> log = new ArrayList<>();
        final List<double[]> where = new ArrayList<>();
        final List<Float> volumes = new ArrayList<>();
        FxWorld world;

        @Override
        public void event(FxInstance instance, String name, double x, double y, double z) {
            log.add("event:" + name + "@" + time(instance));
            where.add(new double[]{x, y, z});
        }

        @Override
        public void sound(FxInstance instance, String sound, double x, double y, double z, float volume) {
            log.add("sound:" + sound + "@" + time(instance));
            volumes.add(volume);
        }

        private static String time(FxInstance i) {
            return String.format(java.util.Locale.ROOT, "%.3f", i.time());
        }

        long count(String prefix) {
            return log.stream().filter(s -> s.startsWith(prefix)).count();
        }
    }

    private static void run(FxWorld fx, float seconds) {
        int frames = Math.round(seconds / DT);
        for (int i = 0; i < frames; i++) {
            fx.update(DT);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Today's presets as single-emitter systems
    // ------------------------------------------------------------------------------------------------

    @Test
    void everyPresetAsASingleEmitterSystemMatchesThePlainPathBitForBit() {
        for (ParticleEffect effect : ParticleLibrary.Presets.ALL) {
            ParticleSystem plain = new ParticleSystem(20_000, 77);
            ParticleSystem viaFx = new ParticleSystem(20_000, 77);
            for (ParticleSystem s : List.of(plain, viaFx)) {
                s.setCollider(FLOOR);
                s.setWind(0.7f, 0, -0.3f);
                s.setViewer(3, 2, 5);
            }
            if (effect.rate() > 0f) {
                plain.createEmitter(effect, 0.25, 1.5, -0.5);
            } else {
                plain.burst(effect, 0.25, 1.5, -0.5, 60);
            }
            FxWorld fx = new FxWorld(viaFx);
            FxInstance inst = fx.play(FxSystem.of(effect, 60), 0.25, 1.5, -0.5);
            for (int f = 0; f < 150; f++) {
                plain.update(DT);
                fx.update(DT);
            }
            String n = effect.name();
            assertTrue(plain.liveCount() > 0 || plain.spawnedTotal() > 0, n + " spawned something");
            assertEquals(plain.spawnedTotal(), viaFx.spawnedTotal(), n + " spawned");
            assertEquals(plain.liveCount(), viaFx.liveCount(), n + " live");
            int live = plain.liveCount();
            for (int i = 0; i < live; i++) {
                assertEquals(plain.px[i], viaFx.px[i], 0f, n + " px " + i);
                assertEquals(plain.py[i], viaFx.py[i], 0f, n + " py " + i);
                assertEquals(plain.pz[i], viaFx.pz[i], 0f, n + " pz " + i);
                assertEquals(plain.vx[i], viaFx.vx[i], 0f, n + " vx " + i);
                assertEquals(plain.age[i], viaFx.age[i], 0f, n + " age " + i);
                assertEquals(plain.size0[i], viaFx.size0[i], 0f, n + " size " + i);
                assertEquals(plain.tint[i], viaFx.tint[i], n + " tint " + i);
                assertEquals(plain.effectId[i], viaFx.effectId[i], n + " effect " + i);
            }
            assertEquals(live, inst.liveParticles(), n + ": the instance counts every particle it caused");
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Timeline
    // ------------------------------------------------------------------------------------------------

    @Test
    void emittersFollowTheirDelaysDurationsAndLoops() {
        ParticleEffect dot = still("dot").build();
        ParticleEffect stream = still("stream").rate(60).build();
        FxSystem fx = FxSystem.builder("timeline")
                .emitter(FxEmitter.builder("first", dot).burst(5).duration(0).build())
                .emitter(FxEmitter.builder("later", dot).burst(3).duration(0).delay(0.5f).build())
                .emitter(FxEmitter.builder("ticks", dot).burst(1).duration(0).loops(3).interval(0.25f).build())
                .emitter(FxEmitter.builder("half", stream).duration(0.5f).delay(0.2f).build())
                .cue(FxSoundCue.onEmitterStart("first", "first"))
                .cue(FxSoundCue.onEmitterStart("later", "later"))
                .cue(FxSoundCue.onEmitterStart("ticks", "tick"))
                .cue(FxSoundCue.onEmitterStart("half", "half"))
                .cue(FxSoundCue.onStop("done"))
                .build();
        ParticleSystem s = new ParticleSystem(10_000);
        FxWorld world = new FxWorld(s);
        Recorder host = new Recorder();
        world.setHost(host);
        FxInstance inst = world.play(fx, 0, 0, 0);
        run(world, 1.5f);

        List<String> starts = host.log.stream().filter(l -> l.startsWith("sound:")).toList();
        assertEquals(List.of("first", "tick", "half", "tick", "later", "tick", "done"),
                starts.stream().map(l -> l.substring(6, l.indexOf('@'))).toList(), "start order: " + starts);
        // Each start happens within one frame of its scheduled time.
        double[] scheduled = {0, 0, 0.2, 0.25, 0.5, 0.5};
        for (int i = 0; i < scheduled.length; i++) {
            double at = Double.parseDouble(starts.get(i).substring(starts.get(i).indexOf('@') + 1));
            assertEquals(scheduled[i], at, DT + 1e-6, starts.get(i));
        }
        // 5 + 3 + 3 bursts, plus 0.5 s of a 60/s stream.
        assertEquals(5 + 3 + 3 + 30, inst.liveParticles(), 1);
        assertTrue(inst.isAlive(), "particles still alive");
        assertFalse(fx.endless());
    }

    @Test
    void endlessLoopsRestartEveryPeriodUntilStopped() {
        FxSystem fx = FxSystem.builder("pulse")
                .emitter(FxEmitter.builder("pulse", still("p").lifetime(0.1f, 0.1f).build()).burst(2).duration(0)
                        .loops(0).interval(0.5f).build())
                .build();
        FxWorld world = new FxWorld(new ParticleSystem(1000));
        FxInstance inst = world.play(fx, 0, 0, 0);
        run(world, 2.02f);
        assertEquals(10, world.system().spawnedTotal(), "cycles at 0, .5, 1, 1.5, 2");
        assertTrue(fx.endless());
        inst.stop(0);
        run(world, 0.5f);
        assertEquals(10, world.system().spawnedTotal(), "no cycle after stop");
        assertFalse(inst.isAlive());
    }

    // ------------------------------------------------------------------------------------------------
    // Parameters
    // ------------------------------------------------------------------------------------------------

    @Test
    void parametersDriveRateSizeSpeedTintDirectionAndOffset() {
        ParticleEffect stream = still("stream").rate(100).speed(2, 2).direction(0, 1, 0).build();
        FxSystem fx = FxSystem.builder("params")
                .emitter(FxEmitter.builder("main", stream).build())
                .parameter(FxParameter.scalar("intensity", 1f))
                .parameter(FxParameter.scalar("scale", 1f))
                .parameter(FxParameter.color("tint", 0xFFFFFFFF))
                .parameter(FxParameter.vector("aim", 0, 1, 0))
                .parameter(FxParameter.vector("shift", 0, 0, 0))
                .bind(FxBinding.of("intensity", FxBinding.Target.RATE))
                .bind(FxBinding.of("scale", FxBinding.Target.SIZE))
                .bind(FxBinding.mapped("scale", FxBinding.Target.SPEED, null, 0.5f, 0.5f))
                .bind(FxBinding.of("tint", FxBinding.Target.TINT))
                .bind(FxBinding.of("aim", FxBinding.Target.DIRECTION))
                .bind(FxBinding.mapped("shift", FxBinding.Target.OFFSET, "main", 2f, 0f))
                .build();
        ParticleSystem s = new ParticleSystem(10_000);
        FxWorld world = new FxWorld(s);
        FxInstance inst = world.play(fx, 10, 0, 0)
                .setParam("intensity", 0.5f).setParam("scale", 3f).setParamColor("tint", 0xFF800080)
                .setParam("aim", 1, 0, 0).setParam("shift", 0, 0, 1);
        run(world, 1f);
        assertEquals(50, inst.liveParticles(), 1, "intensity 0.5 halves the 100/s rate");
        for (int i = 0; i < s.liveCount(); i++) {
            assertEquals(3f, s.size0[i], 1e-6f, "size × 3");
            assertEquals(0xFF800080, s.tint[i], "tint multiplied in");
            float speed = (float) Math.sqrt(s.vx[i] * s.vx[i] + s.vy[i] * s.vy[i] + s.vz[i] * s.vz[i]);
            assertEquals(2f * (0.5f + 0.5f * 3f), speed, 1e-4f, "speed × (0.5 + 0.5 × scale)");
            assertTrue(s.vx[i] > 0.99f * speed, "launched along the aim parameter");
            assertEquals(2.0, s.worldZ(i), 1e-4, "offset = 2 × shift");
        }

        // Changing a parameter mid-flight changes new spawns only.
        inst.setParam("intensity", 0f);
        int before = s.liveCount();
        run(world, 0.5f);
        assertEquals(before, s.liveCount(), "rate 0 spawns nothing");
        assertThrows(IllegalArgumentException.class, () -> inst.setParam("nope", 1f));
        assertThrows(IllegalArgumentException.class, () -> inst.setParam("tint", 1f), "type is checked");
        assertEquals(0f, inst.param("intensity"));
    }

    @Test
    void lightFollowsItsCurveFlickerParametersAndStopFade() {
        FxSystem fx = FxSystem.builder("lamp")
                .emitter(FxEmitter.builder("glow", still("g").rate(1).build()).build())
                .parameter(FxParameter.scalar("bright", 1f))
                .parameter(FxParameter.color("hue", 0xFFFFFFFF))
                .bind(FxBinding.of("bright", FxBinding.Target.LIGHT_INTENSITY))
                .bind(FxBinding.of("hue", FxBinding.Target.LIGHT_COLOR))
                .light(FxLight.steady(0xFF8000FF, 2f, 10f).offset(0, 1, 0)
                        .withKeys(List.of(new FxLight.Key(0f, 0f), new FxLight.Key(1f, 1f)), false))
                .build();
        FxWorld world = new FxWorld(new ParticleSystem(100));
        FxInstance inst = world.play(fx, 5, 0, 0).setParam("bright", 1.5f).setParamColor("hue", 0x80FFFFFF);
        List<float[]> seen = new ArrayList<>();
        FxLightSink sink = (i, x, y, z, r, g, b, intensity, radius) ->
                seen.add(new float[]{(float) x, (float) y, r, g, b, intensity, radius});
        run(world, 0.5f);
        world.forEachLight(sink);
        assertEquals(1, seen.size());
        float[] l = seen.get(0);
        assertEquals(5f, l[0]);
        assertEquals(1f, l[1], "light offset");
        assertEquals(128 / 255f, l[2], 1e-3f, "colour × hue");
        assertEquals(128 / 255f, l[3], 1e-3f);
        assertEquals(0f, l[4], 1e-6f);
        assertEquals(2f * 1.5f * 0.5f, l[5], 0.06f, "intensity × bright × curve(0.5)");
        assertEquals(10f, l[6]);

        inst.stop(1f);
        run(world, 0.5f);
        seen.clear();
        world.forEachLight(sink);
        assertEquals(2f * 1.5f * 1f * 0.5f, seen.get(0)[5], 0.06f, "half-way through the stop fade");
        run(world, 0.6f);
        seen.clear();
        world.forEachLight(sink);
        assertTrue(seen.isEmpty(), "dark once the fade ends");
    }

    @Test
    void flickerVariesPerInstanceWithinItsDepth() {
        FxSystem fx = FxSystem.builder("flicker")
                .emitter(FxEmitter.builder("e", still("f").rate(1).build()).build())
                .light(FxLight.steady(0xFFFFFFFF, 1f, 4f).flickering(0.4f, 8f)).build();
        FxWorld world = new FxWorld(new ParticleSystem(100));
        world.play(fx, 0, 0, 0);
        world.play(fx, 0, 0, 0);
        float[] min = {9f}, max = {0f};
        List<Float> pair = new ArrayList<>();
        boolean differ = false;
        for (int f = 0; f < 120; f++) {
            world.update(DT);
            pair.clear();
            world.forEachLight((i, x, y, z, r, g, b, intensity, radius) -> {
                pair.add(intensity);
                min[0] = Math.min(min[0], intensity);
                max[0] = Math.max(max[0], intensity);
            });
            differ |= !pair.get(0).equals(pair.get(1));
        }
        assertTrue(min[0] >= 0.6f - 1e-6f && max[0] <= 1f, min[0] + ".." + max[0]);
        assertTrue(max[0] - min[0] > 0.1f, "it actually flickers");
        assertTrue(differ, "instances flicker independently");
    }

    // ------------------------------------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------------------------------------

    @Test
    void deathsSpawnIntoAnotherEmitterAndReachTheHost() {
        ParticleEffect shortLived = still("short").lifetime(0.2f, 0.2f).build();
        ParticleEffect child = still("child").build();
        FxSystem fx = FxSystem.builder("chain")
                .emitter(FxEmitter.builder("a", shortLived).burst(10).duration(0).build())
                .emitter(FxEmitter.builder("b", child).eventOnly().tint(0x00FF00FF).build())
                .on(FxEventHandler.spawn(FxEventHandler.Trigger.DEATH, "a", "b", 2).forwarding("pop").playing("pop"))
                .build();
        ParticleSystem s = new ParticleSystem(1000);
        FxWorld world = new FxWorld(s);
        Recorder host = new Recorder();
        world.setHost(host);
        FxInstance inst = world.play(fx, 1, 2, 3);
        run(world, 0.4f);
        assertEquals(10, host.count("event:pop"), "one forwarded event per death");
        assertEquals(10, host.count("sound:pop"));
        for (double[] w : host.where) {
            assertArrayEquals(new double[]{1, 2, 3}, w, 1e-5, "at the dying particle");
        }
        assertEquals(20, s.liveCount());
        for (int i = 0; i < s.liveCount(); i++) {
            assertSame(child, s.effectOf(i));
            assertEquals(0x00FF00FF, s.tint[i], "spawned with the target emitter's tint");
        }
        assertEquals(20, inst.liveParticles());
    }

    @Test
    void collisionsAndCustomEventsRoute() {
        ParticleEffect falling = still("fall").lifetime(3, 3).speed(4, 4).direction(0, -1, 0).collides(0f, 0f).build();
        FxSystem fx = FxSystem.builder("impacts")
                .emitter(FxEmitter.builder("drops", falling).burst(6).duration(0).build())
                .emitter(FxEmitter.builder("puff", still("puff").build()).eventOnly().offset(0, 5, 0).build())
                .on(FxEventHandler.forward(FxEventHandler.Trigger.COLLISION, "drops", "hit"))
                .on(FxEventHandler.spawn(FxEventHandler.Trigger.CUSTOM, "boom", "puff", 4).forwarding("boom"))
                .build();
        ParticleSystem s = new ParticleSystem(1000);
        s.setCollider(FLOOR);
        FxWorld world = new FxWorld(s);
        Recorder host = new Recorder();
        world.setHost(host);
        FxInstance inst = world.play(fx, 0, 2, 0);
        run(world, 1f);
        assertEquals(6, host.count("event:hit"), "each drop reports its impact once (it then rests)");
        for (double[] w : host.where) {
            assertEquals(0.5, w[1], 0.1, "impact on the floor top (half the 1-block particle above it)");
        }

        assertTrue(inst.raise("boom"));
        assertFalse(inst.raise("unknown"), "nothing listens");
        world.update(DT);
        assertEquals(1, host.count("event:boom"));
        int puffs = 0;
        for (int i = 0; i < s.liveCount(); i++) {
            if (s.effectOf(i).name().equals("puff")) {
                puffs++;
                assertEquals(7.0, s.worldY(i), 1e-5, "custom events spawn at the instance + the emitter offset");
            }
        }
        assertEquals(4, puffs);
    }

    @Test
    void chanceThinsEventsDeterministically() {
        FxSystem fx = FxSystem.builder("thin")
                .emitter(FxEmitter.builder("a", still("x").lifetime(0.05f, 0.05f).build()).burst(1000).duration(0).build())
                .on(FxEventHandler.forward(FxEventHandler.Trigger.DEATH, "a", "e").withChance(0.25f))
                .build();
        FxWorld world = new FxWorld(new ParticleSystem(2000));
        Recorder host = new Recorder();
        world.setHost(host);
        world.play(fx, 0, 0, 0);
        run(world, 0.2f);
        assertEquals(250, host.count("event:e"), 50);
    }

    @Test
    void particleSubEmitterChildrenCountForLifeButFireNoHandlers() {
        // The rocket's own (effect-level) trail sparks keep the instance alive but never trigger rocket handlers.
        Recorder host = new Recorder();
        ParticleSystem s = new ParticleSystem(20_000);
        FxWorld world = new FxWorld(s);
        world.setHost(host);
        FxInstance inst = world.play(FxPresets.FIREWORK, 0, 0, 0);
        run(world, 1.0f);
        assertEquals(0, host.count("event:boom"), "still climbing");
        assertTrue(inst.liveParticles() > 1, "trail sparks are the instance's");
        run(world, 0.3f);
        assertEquals(1, host.count("event:boom"), "one boom per rocket, not per trail spark");
        assertEquals(1, host.count("sound:firework-boom"));
        run(world, 3f);
        assertFalse(inst.isAlive(), "everything burnt out");
        assertEquals(0, inst.liveParticles());
    }

    // ------------------------------------------------------------------------------------------------
    // Instances: stop, fade, pooling, budget
    // ------------------------------------------------------------------------------------------------

    @Test
    void stopFadesEmissionThenEndsAndFiresStopCuesOnce() {
        FxSystem fx = FxSystem.builder("stream")
                .emitter(FxEmitter.builder("s", still("s").lifetime(0.5f, 0.5f).rate(600).build()).build())
                .cue(FxSoundCue.onStop("end"))
                .build();
        ParticleSystem s = new ParticleSystem(10_000);
        FxWorld world = new FxWorld(s);
        Recorder host = new Recorder();
        world.setHost(host);
        FxInstance inst = world.play(fx, 0, 0, 0);
        run(world, 1f);
        long full = s.spawnedTotal();
        inst.stop(1f);
        run(world, 0.5f);
        long firstHalf = s.spawnedTotal() - full;
        run(world, 0.5f);
        long secondHalf = s.spawnedTotal() - full - firstHalf;
        assertEquals(600 * 0.5 * 0.75, firstHalf, 10, "rate ramps 1 → 0.5 over the first half second");
        assertEquals(600 * 0.5 * 0.25, secondHalf, 10, "and 0.5 → 0 over the next");
        assertTrue(inst.isAlive(), "its last particles still live");
        run(world, 0.6f);
        assertFalse(inst.isAlive());
        assertEquals(1, host.count("sound:end"));
        assertEquals(1, world.instanceCount(), "a retained instance waits for release");
        inst.release();
        world.update(DT);
        assertEquals(0, world.instanceCount());
        assertThrows(IllegalStateException.class, () -> inst.move(0, 0, 0), "released and recycled");
    }

    @Test
    void firedInstancesRecycleTheirSlots() {
        FxSystem fx = FxSystem.builder("blip")
                .emitter(FxEmitter.builder("b", still("b").lifetime(0.1f, 0.1f).build()).burst(3).duration(0).build())
                .build();
        FxWorld world = new FxWorld(new ParticleSystem(1000));
        for (int wave = 0; wave < 20; wave++) {
            for (int k = 0; k < 8; k++) {
                assertTrue(world.fire(fx, k, 0, 0));
            }
            run(world, 0.2f);
            assertEquals(0, world.instanceCount(), "wave " + wave + " finished and returned to the pool");
        }
        assertEquals(20 * 8 * 3, world.system().spawnedTotal());
    }

    @Test
    void theInstanceBudgetRefusesPlays() {
        FxSystem fx = FxSystem.of(still("x").rate(1).build(), 0);
        FxWorld world = new FxWorld(new ParticleSystem(100), 2);
        assertNotNull(world.play(fx, 0, 0, 0));
        assertNotNull(world.play(fx, 0, 0, 0));
        assertNull(world.play(fx, 0, 0, 0));
        assertFalse(world.fire(fx, 0, 0, 0));
        assertEquals(2, world.playsRefusedTotal());
    }

    @Test
    void clearingParticlesResetsInstanceCountsAndOneWorldPerSystem() {
        ParticleSystem s = new ParticleSystem(1000);
        FxWorld world = new FxWorld(s);
        FxInstance inst = world.play(FxSystem.of(still("x").build(), 50), 0, 0, 0);
        world.update(DT);
        assertEquals(50, inst.liveParticles());
        s.clearParticles();
        assertEquals(0, inst.liveParticles());
        assertFalse(inst.isAlive(), "a burst system with nothing left is done");
        assertThrows(IllegalStateException.class, () -> new FxWorld(s), "already driven by a world");
        world.close();
        new FxWorld(s).close(); // free again after close
    }

    @Test
    void movingAnInstanceMovesItsEmittersNotItsParticles() {
        FxSystem fx = FxSystem.of(still("m").rate(60).build(), 0);
        ParticleSystem s = new ParticleSystem(1000);
        FxWorld world = new FxWorld(s);
        FxInstance inst = world.play(fx, 0, 0, 0);
        run(world, 0.5f);
        int atOrigin = s.liveCount();
        inst.move(10, 0, 0);
        run(world, 0.5f);
        int moved = 0, stayed = 0;
        for (int i = 0; i < s.liveCount(); i++) {
            if (s.worldX(i) == 10.0) moved++;
            if (s.worldX(i) == 0.0) stayed++;
        }
        assertEquals(atOrigin, stayed);
        assertEquals(s.liveCount() - atOrigin, moved);
    }

    // ------------------------------------------------------------------------------------------------
    // Presets and validation
    // ------------------------------------------------------------------------------------------------

    @Test
    void explosionPresetFlashesThrowsDebrisAndReportsLandings() {
        ParticleSystem s = new ParticleSystem(20_000);
        s.setCollider(FLOOR);
        FxWorld world = new FxWorld(s);
        Recorder host = new Recorder();
        world.setHost(host);
        FxInstance inst = world.play(FxPresets.EXPLOSION, 0, 1, 0).setParamColor("debrisTint", 0x808080FF);
        float[] flash = new float[3];
        world.update(DT);
        world.forEachLight((i, x, y, z, r, g, b, intensity, radius) -> flash[0] = intensity);
        run(world, 0.3f);
        world.forEachLight((i, x, y, z, r, g, b, intensity, radius) -> flash[1] = intensity);
        run(world, 0.5f);
        world.forEachLight((i, x, y, z, r, g, b, intensity, radius) -> flash[2] = intensity);
        assertTrue(flash[0] > 5f && flash[1] < flash[0] && flash[2] == 0f, java.util.Arrays.toString(flash));
        assertEquals(1, host.count("sound:explosion"));
        assertTrue(host.count("event:debris-land") > 0, "some debris reported landing");
        assertTrue(host.count("event:debris-land") < 40, "thinned by chance");
        boolean dust = false, greyDebris = false;
        for (int i = 0; i < s.liveCount(); i++) {
            dust |= s.effectOf(i) == ParticleLibrary.Presets.DUST;
            greyDebris |= s.effectOf(i) == ParticleLibrary.Presets.DEBRIS && (s.tint[i] >>> 24) <= 0x80;
        }
        assertTrue(dust, "landings kicked up dust");
        assertTrue(greyDebris, "debris tinted by the parameter");
        run(world, 5f);
        assertFalse(inst.isAlive());
    }

    @Test
    void campfireRunsUntilStoppedAndItsSmokeComesInLate() {
        ParticleSystem s = new ParticleSystem(20_000);
        FxWorld world = new FxWorld(s);
        FxInstance fire = world.play(FxPresets.CAMPFIRE, 0, 0, 0);
        run(world, 0.5f);
        assertEquals(0, s.liveCount(ParticleLibrary.Presets.SMOKE), "smoke waits 0.8 s");
        run(world, 1f);
        assertTrue(s.liveCount(ParticleLibrary.Presets.SMOKE) > 0);
        assertTrue(FxPresets.CAMPFIRE.endless());
        run(world, 5f);
        assertTrue(fire.isAlive());
        fire.stop(0.5f);
        run(world, 6f);
        assertFalse(fire.isAlive());
    }

    @Test
    void systemsRejectBrokenReferences() {
        ParticleEffect e = still("e").build();
        FxEmitter a = FxEmitter.builder("a", e).build();
        assertThrows(IllegalArgumentException.class, () -> FxSystem.builder("x").build(), "no emitters");
        assertThrows(IllegalArgumentException.class, () -> FxSystem.builder("x").emitter(a).emitter(a).build());
        assertThrows(IllegalArgumentException.class, () -> FxSystem.builder("x").emitter(a)
                .bind(FxBinding.of("missing", FxBinding.Target.RATE)).build());
        assertThrows(IllegalArgumentException.class, () -> FxSystem.builder("x").emitter(a)
                .parameter(FxParameter.color("c", 0)).bind(FxBinding.of("c", FxBinding.Target.RATE)).build(),
                "colour parameter on a float target");
        assertThrows(IllegalArgumentException.class, () -> FxSystem.builder("x").emitter(a)
                .parameter(FxParameter.scalar("f", 1)).bind(FxBinding.of("f", FxBinding.Target.LIGHT_INTENSITY)).build(),
                "light binding without a light");
        assertThrows(IllegalArgumentException.class, () -> FxSystem.builder("x").emitter(a)
                .on(FxEventHandler.spawn(FxEventHandler.Trigger.DEATH, "a", "ghost", 1)).build());
        assertThrows(IllegalArgumentException.class, () -> FxSystem.builder("x").emitter(a)
                .on(FxEventHandler.forward(FxEventHandler.Trigger.COLLISION, "ghost", "e")).build());
        assertThrows(IllegalArgumentException.class, () -> FxSystem.builder("x").emitter(a)
                .cue(FxSoundCue.onEmitterStart("ghost", "s")).build());
        assertThrows(IllegalArgumentException.class, () -> FxSystem.builder("x").emitter(a)
                .sounds(new com.openmason.engine.format.sound.SoundData(List.of(new com.openmason.engine.format.sound.SoundDef(
                        "known", null, null, "/s.wav", 1f, 1f, 1f, false))))
                .cue(FxSoundCue.onPlay("typo")).build(), "a sound outside the declared definitions");
        assertThrows(IllegalArgumentException.class,
                () -> FxEmitter.builder("loop", e).duration(0).loops(0).interval(0).build(), "zero-length endless loop");
        assertThrows(IllegalArgumentException.class,
                () -> FxEventHandler.spawn(FxEventHandler.Trigger.DEATH, "a", null, 1), "a handler that does nothing");
        assertThrows(IllegalArgumentException.class,
                () -> new FxBinding("p", FxBinding.Target.VOLUME, "a", 1, 0), "system-wide target on an emitter");
        assertNotNull(FxPresets.ALL);
    }
}
