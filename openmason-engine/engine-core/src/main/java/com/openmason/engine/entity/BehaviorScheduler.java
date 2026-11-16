package com.openmason.engine.entity;

import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.Cleaner;
import java.util.List;

import static com.openmason.engine.entity.CendaBehaviors.F32;
import static com.openmason.engine.entity.CendaBehaviors.I32;
import static com.openmason.engine.entity.CendaBehaviors.rethrow;

/**
 * Decides which of an agent's behaviours run (native {@code cb_scheduler}, OpenMason #115): stop what
 * no longer applies, start the most urgent behaviours whose flags are free — preempting less urgent
 * holders, choosing by weight among equal priorities — then tick what runs. The rules live in C++
 * ({@code cenda/native/kernels/src/entity/behavior_scheduler.cpp}, contract in {@code behavior.h});
 * this class only marshals, and the behaviours themselves stay with the host ({@link BehaviorHost}).
 *
 * <p>Owned by the thread that simulates its agent. A host may re-enter {@link #stopAll} from inside a
 * tick.
 */
public final class BehaviorScheduler implements AutoCloseable {

    /** One behaviour as the scheduler sees it: lower priority runs first; flags are host-defined bits. */
    public record Desc(int priority, float weight, int flags) {
    }

    private static final Cleaner CLEANER = Cleaner.create();
    /** One upcall stub for every scheduler: the host of the current call is a per-thread frame. */
    private static final MemorySegment HOST_STUB;
    private static final ThreadLocal<Frame> FRAME = new ThreadLocal<>();

    static {
        MemorySegment stub = MemorySegment.NULL;
        if (CendaBehaviors.isAvailable()) {
            try {
                MethodHandle target = MethodHandles.lookup().findStatic(BehaviorScheduler.class, "hostUpcall",
                    MethodType.methodType(int.class, MemorySegment.class, int.class, int.class, float.class,
                        MemorySegment.class));
                stub = Linker.nativeLinker().upcallStub(target, CendaBehaviors.HOST_FN, Arena.global());
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        HOST_STUB = stub;
    }

    /** The host of the scheduler call in progress on this thread, and what it threw. */
    private static final class Frame {
        final BehaviorHost host;
        final Frame outer;
        Throwable failure;

        Frame(BehaviorHost host, Frame outer) {
            this.host = host;
            this.outer = outer;
        }
    }

    private final MemorySegment scheduler;
    private final Cleaner.Cleanable cleanable;
    private final int count;
    private final MemorySegment runningOut;
    private boolean closed;

    private BehaviorScheduler(MemorySegment scheduler, int count) {
        this.scheduler = scheduler;
        this.count = count;
        this.runningOut = Arena.ofAuto().allocate(I32, Math.max(1, count));
        this.cleanable = CLEANER.register(this, new Destroy(scheduler));
    }

    /**
     * A scheduler over {@code behaviors}; host callbacks name them by their index in this list.
     *
     * @throws IllegalStateException when the native scheduler is unavailable (the message says why)
     */
    public static BehaviorScheduler create(List<Desc> behaviors) {
        if (!CendaBehaviors.isAvailable()) {
            throw new IllegalStateException("Cenda behaviour scheduler unavailable: " + CendaBehaviors.unavailableReason());
        }
        int n = behaviors.size();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment descs = arena.allocate(CendaBehaviors.DESC_BYTES * Math.max(1, n), 4);
            for (int i = 0; i < n; i++) {
                Desc d = behaviors.get(i);
                long o = i * CendaBehaviors.DESC_BYTES;
                descs.set(I32, o, d.priority());
                descs.set(F32, o + 4, d.weight());
                descs.set(I32, o + 8, d.flags());
                descs.set(I32, o + 12, 0);
            }
            MemorySegment s = (MemorySegment) CendaBehaviors.CREATE.invokeExact(descs, n);
            if (s.equals(MemorySegment.NULL)) {
                throw new IllegalArgumentException("cb_scheduler_create: " + CendaBehaviors.lastError());
            }
            return new BehaviorScheduler(s, n);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private record Destroy(MemorySegment scheduler) implements Runnable {
        @Override
        public void run() {
            try {
                CendaBehaviors.DESTROY.invokeExact(scheduler);
            } catch (Throwable ignored) {
                // the process is releasing the scheduler
            }
        }
    }

    // Native code calls this inside a tick/stopAll on the calling thread. Must never throw: an
    // exception escaping an upcall terminates the JVM. A host failure is kept and rethrown.
    @SuppressWarnings("unused")
    private static int hostUpcall(MemorySegment user, int op, int index, float dt, MemorySegment outRandom) {
        Frame frame = FRAME.get();
        if (frame == null) {
            return -1;
        }
        try {
            BehaviorHost host = frame.host;
            switch (op) {
                case CendaBehaviors.OP_SHOULD_CONTINUE:
                    return host.shouldContinue(index) ? 1 : 0;
                case CendaBehaviors.OP_CAN_START:
                    return host.canStart(index) ? 1 : 0;
                case CendaBehaviors.OP_START:
                    host.start(index);
                    return 0;
                case CendaBehaviors.OP_STOP:
                    host.stop(index);
                    return 0;
                case CendaBehaviors.OP_TICK:
                    host.tick(index, dt);
                    return 0;
                case CendaBehaviors.OP_RANDOM:
                    outRandom.reinterpret(4).set(F32, 0, host.random());
                    return 0;
                default:
                    return -1;
            }
        } catch (Throwable t) {
            if (frame.failure == null) {
                frame.failure = t;
            }
            return -1;
        }
    }

    private MemorySegment live() {
        if (closed) {
            throw new IllegalStateException("BehaviorScheduler is closed");
        }
        return scheduler;
    }

    /** One tick: stop, start, tick (see the class note). Host exceptions propagate. */
    public void tick(BehaviorHost host, float deltaTime) {
        MemorySegment s = live();
        Frame frame = new Frame(host, FRAME.get());
        FRAME.set(frame);
        int rc;
        try {
            rc = (int) CendaBehaviors.TICK.invokeExact(s, HOST_STUB, MemorySegment.NULL, deltaTime);
        } catch (Throwable t) {
            throw rethrow(t);
        } finally {
            restore(frame);
        }
        finish(frame, rc, "cb_scheduler_tick");
    }

    /** Stops every running behaviour, newest first. Host exceptions propagate. */
    public void stopAll(BehaviorHost host) {
        MemorySegment s = live();
        Frame frame = new Frame(host, FRAME.get());
        FRAME.set(frame);
        int rc;
        try {
            rc = (int) CendaBehaviors.STOP_ALL.invokeExact(s, HOST_STUB, MemorySegment.NULL);
        } catch (Throwable t) {
            throw rethrow(t);
        } finally {
            restore(frame);
        }
        finish(frame, rc, "cb_scheduler_stop_all");
    }

    private static void restore(Frame frame) {
        if (frame.outer == null) {
            FRAME.remove();
        } else {
            FRAME.set(frame.outer);
        }
    }

    private static void finish(Frame frame, int rc, String call) {
        if (frame.failure != null) {
            throw rethrow(frame.failure);
        }
        if (rc != CendaBehaviors.OK) {
            throw new IllegalStateException(call + ": " + CendaBehaviors.lastError());
        }
    }

    /** The most urgent running behaviour (the oldest among equals), or -1 when none runs. */
    public int leader() {
        try {
            return (int) CendaBehaviors.LEADER.invokeExact(live());
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public boolean isRunning(int index) {
        if (index < 0 || index >= count) {
            return false;
        }
        try {
            return (int) CendaBehaviors.IS_RUNNING.invokeExact(live(), index) == 1;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The running behaviours, oldest first. */
    public int[] running() {
        try {
            int n = (int) CendaBehaviors.RUNNING.invokeExact(live(), runningOut, count);
            int[] out = new int[n];
            MemorySegment.copy(runningOut, I32, 0, out, 0, Math.min(n, count));
            return out;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public int size() {
        return count;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            cleanable.clean();
        }
    }
}
