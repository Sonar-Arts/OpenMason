package com.openmason.engine.platform.loop;

import com.openmason.engine.loop.FramePacing;
import com.openmason.engine.loop.FrameTimer;
import com.openmason.engine.platform.window.Window;

/**
 * The frame loop (#96): poll, update with a clamped variable delta, render, swap, pace. Runs until
 * the window should close, {@link #stop()} is called, or a pacing sleep is interrupted. What a frame
 * does is the host's ({@link Frame}); hosts that need a fixed simulation rate drive a
 * {@link com.openmason.engine.loop.FixedStep} from {@link Frame#update}.
 *
 * <p>One frame, in order: {@link Frame#beforePoll()} (expire last frame's input edges),
 * {@link Window#pollEvents()}, {@link Frame#update(float)}, {@link Frame#afterUpdate()},
 * {@link Frame#render()}, {@link Frame#beforeSwap()} (screenshots, dev automation), swap, then sleep
 * to the {@link Pacing} budget measured from the frame's start.
 */
public final class FrameLoop {

    /** What one frame does. Main thread. */
    public interface Frame {
        default void beforePoll() {
        }

        /** @param dt seconds since the previous frame, clamped to the loop's maximum */
        void update(float dt);

        default void afterUpdate() {
        }

        void render();

        default void beforeSwap() {
        }
    }

    /** The current frame budget in nanoseconds; 0 = uncapped. Read once per frame. */
    @FunctionalInterface
    public interface Pacing {
        /** No cap: never sleeps (VSync by swap interval, or benchmarks). */
        Pacing UNCAPPED = window -> 0L;

        long budgetNanos(Window window);
    }

    private final Window window;
    private final FrameTimer timer;
    private final Pacing pacing;
    private volatile boolean running;

    /**
     * @param maxDeltaSeconds the largest delta {@link Frame#update} sees (Stonebreak: 0.1 s)
     */
    public FrameLoop(Window window, float maxDeltaSeconds, Pacing pacing) {
        this.window = window;
        this.timer = new FrameTimer(maxDeltaSeconds);
        this.pacing = pacing != null ? pacing : Pacing.UNCAPPED;
    }

    /** Runs frames until the window closes or {@link #stop()}; returns on the calling thread. */
    public void run(Frame frame) {
        running = true;
        timer.start(System.nanoTime());
        while (running && !window.shouldClose()) {
            long frameStart = System.nanoTime();
            frame.beforePoll();
            Window.pollEvents();
            frame.update(timer.tick(System.nanoTime()));
            frame.afterUpdate();
            frame.render();
            frame.beforeSwap();
            window.swapBuffers();
            if (!FramePacing.sleepRemaining(pacing.budgetNanos(window), System.nanoTime() - frameStart)) {
                break;
            }
        }
        running = false;
    }

    /** Ends the loop after the current frame. */
    public void stop() {
        running = false;
    }

    public boolean isRunning() {
        return running;
    }
}
