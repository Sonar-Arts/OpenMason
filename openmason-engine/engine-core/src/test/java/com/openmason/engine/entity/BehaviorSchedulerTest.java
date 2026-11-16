package com.openmason.engine.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The behaviour scheduler ({@code cb_*}) through its FFM binding, with no game in sight: the host
 * upcall's call order, the weighted pick drawing from the host's random, exceptions crossing the
 * native call, re-entrant stopAll, and a reference model of the scheduling rules (the Java controller
 * the native one replaced, Stonebreak's BehaviorController) driven side by side with the native one
 * over random hosts. The native suite ({@code cenda/tests/behavior_scheduler_test.cpp}) pins the
 * rules themselves.
 */
class BehaviorSchedulerTest {

    private static final int MOVE = 1;
    private static final int LOOK = 2;

    @BeforeEach
    void native_scheduler_is_available() {
        assumeTrue(CendaBehaviors.isAvailable(), () -> "Cenda behaviour scheduler: " + CendaBehaviors.unavailableReason());
    }

    /** A host whose answers come from arrays, logging every call. */
    private static final class ScriptedHost implements BehaviorHost {
        final boolean[] canStart;
        final boolean[] keep;
        final StringBuilder log = new StringBuilder();
        Runnable onTick0;
        RuntimeException throwOnStart;

        ScriptedHost(int n, long seed) {
            canStart = new boolean[n];
            keep = new boolean[n];
        }

        @Override
        public boolean shouldContinue(int index) {
            log.append('c').append(index).append(' ');
            return keep[index];
        }

        @Override
        public boolean canStart(int index) {
            log.append('?').append(index).append(' ');
            return canStart[index];
        }

        @Override
        public void start(int index) {
            log.append('+').append(index).append(' ');
            if (throwOnStart != null) {
                throw throwOnStart;
            }
        }

        @Override
        public void stop(int index) {
            log.append('-').append(index).append(' ');
        }

        @Override
        public void tick(int index, float deltaTime) {
            log.append('t').append(index).append(' ');
            if (index == 0 && onTick0 != null) {
                Runnable r = onTick0;
                onTick0 = null;
                r.run();
            }
        }

        @Override
        public float random() {
            log.append("r ");
            return 0.0f; // always the first candidate in list order
        }
    }

    @Test
    void preemption_and_flags_in_the_contract_order() {
        try (BehaviorScheduler s = BehaviorScheduler.create(List.of(
                new BehaviorScheduler.Desc(0, 1.0f, MOVE),
                new BehaviorScheduler.Desc(5, 1.0f, MOVE),
                new BehaviorScheduler.Desc(5, 1.0f, LOOK)))) {
            ScriptedHost h = new ScriptedHost(3, 1);
            h.canStart[1] = h.canStart[2] = true;
            h.keep[0] = h.keep[1] = h.keep[2] = true;
            s.tick(h, 0.05f);
            assertEquals("?0 ?1 ?2 r +1 ?2 +2 t1 t2 ", h.log.toString());
            assertArrayEquals(new int[] {1, 2}, s.running());
            assertEquals(1, s.leader());

            h.log.setLength(0);
            h.canStart[0] = true;
            s.tick(h, 0.05f);
            assertEquals("c2 c1 ?0 -1 +0 t2 t0 ", h.log.toString());
            assertTrue(s.isRunning(0));
            assertFalse(s.isRunning(1));
            assertEquals(0, s.leader());
        }
    }

    @Test
    void host_exceptions_cross_the_native_call() {
        try (BehaviorScheduler s = BehaviorScheduler.create(List.of(new BehaviorScheduler.Desc(0, 1.0f, MOVE)))) {
            ScriptedHost h = new ScriptedHost(1, 1);
            h.canStart[0] = true;
            h.keep[0] = true;
            IllegalStateException boom = new IllegalStateException("expected by the test");
            h.throwOnStart = boom;
            IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> s.tick(h, 0.05f));
            assertSame(boom, thrown);
            assertTrue(s.isRunning(0)); // already in the running list when start threw
            h.throwOnStart = null;
            s.tick(h, 0.05f); // the scheduler is still usable
        }
    }

    @Test
    void a_tick_may_stop_everything() {
        try (BehaviorScheduler s = BehaviorScheduler.create(List.of(
                new BehaviorScheduler.Desc(0, 1.0f, MOVE),
                new BehaviorScheduler.Desc(0, 1.0f, LOOK)))) {
            ScriptedHost h = new ScriptedHost(2, 3);
            h.canStart[0] = h.canStart[1] = true;
            h.keep[0] = h.keep[1] = true;
            s.tick(h, 0.05f);
            h.log.setLength(0);
            h.onTick0 = () -> s.stopAll(h);
            s.tick(h, 0.05f);
            assertEquals(-1, s.leader());
            assertEquals(0, s.running().length);
            assertTrue(h.log.toString().endsWith("t0 -1 -0 "), h.log.toString());
        }
    }

    // ---- the reference model: the Java controller the native scheduler replaced -----------------

    private record Behaviour(int index, int priority, float weight, int flags) {
    }

    /** Stonebreak's BehaviorController (before OpenMason #115), over indices and a host. */
    private static final class ReferenceController {
        final List<Behaviour> behaviors;
        final List<Behaviour> running = new ArrayList<>();

        ReferenceController(List<Behaviour> list) {
            behaviors = new ArrayList<>(list);
            behaviors.sort(java.util.Comparator.comparingInt(Behaviour::priority));
        }

        void tick(BehaviorHost host, float dt) {
            for (int i = running.size() - 1; i >= 0; i--) {
                Behaviour b = running.get(i);
                if (!host.shouldContinue(b.index())) {
                    running.remove(i);
                    host.stop(b.index());
                }
            }
            int index = 0;
            while (index < behaviors.size()) {
                int priority = behaviors.get(index).priority();
                int end = index;
                while (end < behaviors.size() && behaviors.get(end).priority() == priority) {
                    end++;
                }
                startFromGroup(host, index, end);
                index = end;
            }
            for (int i = 0; i < running.size(); i++) {
                host.tick(running.get(i).index(), dt);
            }
        }

        void startFromGroup(BehaviorHost host, int from, int to) {
            while (true) {
                List<Behaviour> candidates = new ArrayList<>();
                for (int i = from; i < to; i++) {
                    Behaviour b = behaviors.get(i);
                    if (!running.contains(b) && fits(b) && host.canStart(b.index())) {
                        candidates.add(b);
                    }
                }
                if (candidates.isEmpty()) {
                    return;
                }
                Behaviour chosen = pick(host, candidates);
                for (int i = running.size() - 1; i >= 0; i--) {
                    Behaviour active = running.get(i);
                    if ((active.flags() & chosen.flags()) != 0) {
                        running.remove(i);
                        host.stop(active.index());
                    }
                }
                running.add(chosen);
                host.start(chosen.index());
            }
        }

        boolean fits(Behaviour c) {
            for (Behaviour a : running) {
                if (a.priority() <= c.priority() && (a.flags() & c.flags()) != 0) {
                    return false;
                }
            }
            return true;
        }

        static Behaviour pick(BehaviorHost host, List<Behaviour> options) {
            if (options.size() == 1) {
                return options.get(0);
            }
            float total = 0.0f;
            for (Behaviour o : options) {
                total += Math.max(0.0f, o.weight());
            }
            if (total <= 0.0f) {
                return options.get(0);
            }
            float roll = host.random() * total;
            for (Behaviour o : options) {
                roll -= Math.max(0.0f, o.weight());
                if (roll <= 0.0f) {
                    return o;
                }
            }
            return options.get(options.size() - 1);
        }

        int leader() {
            Behaviour leader = null;
            for (Behaviour a : running) {
                if (leader == null || a.priority() < leader.priority()) {
                    leader = a;
                }
            }
            return leader == null ? -1 : leader.index();
        }
    }

    /** A host whose answers are a pure function of (tick, index, op) and a seeded random. */
    private static final class RandomHost implements BehaviorHost {
        final StringBuilder log = new StringBuilder();
        final Random answers;
        final Random rolls;

        RandomHost(long seed) {
            answers = new Random(seed);
            rolls = new Random(seed * 31 + 7);
        }

        @Override
        public boolean shouldContinue(int index) {
            log.append('c').append(index).append(' ');
            return answers.nextInt(10) < 8;
        }

        @Override
        public boolean canStart(int index) {
            log.append('?').append(index).append(' ');
            return answers.nextInt(10) < 4;
        }

        @Override
        public void start(int index) {
            log.append('+').append(index).append(' ');
        }

        @Override
        public void stop(int index) {
            log.append('-').append(index).append(' ');
        }

        @Override
        public void tick(int index, float deltaTime) {
            log.append('t').append(index).append(' ');
        }

        @Override
        public float random() {
            log.append("r ");
            return rolls.nextFloat();
        }
    }

    @Test
    void matches_the_java_controller_it_replaced_call_for_call() {
        Random setup = new Random(1150);
        for (int round = 0; round < 200; round++) {
            int n = 1 + setup.nextInt(7);
            List<Behaviour> list = new ArrayList<>();
            List<BehaviorScheduler.Desc> descs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int priority = setup.nextInt(4) * 5;
                float weight = setup.nextInt(5) == 0 ? 0.0f : 0.5f + setup.nextInt(8);
                int flags = setup.nextInt(4); // none, MOVE, LOOK, both
                list.add(new Behaviour(i, priority, weight, flags));
                descs.add(new BehaviorScheduler.Desc(priority, weight, flags));
            }
            ReferenceController reference = new ReferenceController(list);
            long seed = setup.nextLong();
            RandomHost expected = new RandomHost(seed);
            RandomHost actual = new RandomHost(seed);
            try (BehaviorScheduler s = BehaviorScheduler.create(descs)) {
                for (int tick = 0; tick < 40; tick++) {
                    reference.tick(expected, 0.05f);
                    s.tick(actual, 0.05f);
                    assertEquals(expected.log.toString(), actual.log.toString(), "round " + round + " tick " + tick);
                    assertEquals(reference.leader(), s.leader(), "round " + round + " tick " + tick);
                }
            }
        }
    }

    @Test
    void closed_schedulers_refuse_calls() {
        BehaviorScheduler s = BehaviorScheduler.create(List.of());
        s.close();
        s.close(); // idempotent
        assertThrows(IllegalStateException.class, () -> s.tick(new ScriptedHost(0, 1), 0.05f));
    }
}
