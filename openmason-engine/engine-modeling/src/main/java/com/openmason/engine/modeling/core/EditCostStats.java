package com.openmason.engine.modeling.core;

/**
 * Edit-cost instrumentation (#175 F0): named time spans and counters around the model rebuild/overlay/picking work,
 * read by the editor's stats overlay and the high-poly benchmark.
 *
 * <p>Cost when nobody looks: {@link #begin()} returns 0 and {@link #end} returns at once while disabled; enabled, a
 * span is two {@code System.nanoTime()} calls. Spans are per EVENT (a rebuild, a hover query, a frame's overlay
 * upload), never per element. Not thread-safe by design: written and read on the editor (GL/UI) thread; a worker
 * that records a span may race a reader, which only skews a displayed number.
 *
 * <p>Per frame ({@link #endFrame()}): each span's sum over the frame becomes its "last" value when it ran, the
 * per-frame maximum over a rolling ~1 s window becomes "max"; counters (draw calls, buffer uploads, uploaded bytes)
 * are per-frame totals with the same window maximum. {@link #total} keeps the all-time sum and call count (bench).
 */
public final class EditCostStats {

    /** Timed spans. */
    public enum Span {
        /** ModelPartManager: combined-mesh rebuild (bake + import + derive, the whole event). */
        REBUILD_COMBINED("Part rebuild"),
        /** MeshImporter.importSoup (weld + loops). */
        IMPORT_SOUP("Import soup"),
        /** MeshRebuildPipeline.derive (render mesh + topology + listeners). */
        DERIVE("Derive"),
        /** RenderMeshBuilder.build inside a derive. */
        RENDER_MESH("Render mesh"),
        /** MeshTopologyBuilder.build inside a derive. */
        TOPOLOGY("Topology"),
        /** Overlay listeners notified by a derive (vertex/edge/face overlay rebuilds). */
        OVERLAY_REBUILD("Overlay rebuild"),
        /** FaceRenderer data rebuild (lines, fills, dots). */
        FACE_OVERLAY("Face overlay"),
        /** EdgeRenderer data rebuild. */
        EDGE_OVERLAY("Edge overlay"),
        /** VertexRenderer data rebuild. */
        VERTEX_OVERLAY("Vertex overlay"),
        /** Overlay listeners handling moved vertices (positions-only path). */
        OVERLAY_MOVE("Overlay move"),
        /** ModelShading: store sync + corner normals. */
        SHADING("Shading"),
        /** Hover picking (vertex / edge / face detectors). */
        HOVER("Hover"),
        /** A drag frame (translation handler mouse move). */
        DRAG_FRAME("Drag frame"),
        /** A drag release (commit). */
        DRAG_COMMIT("Drag commit"),
        /** Overlay draw calls submission (vertex + edge + face render). */
        OVERLAY_DRAW("Overlay draw");

        private final String label;

        Span(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Per-frame counters. */
    public enum Counter {
        /** glDraw* calls issued by the editor overlays. */
        DRAW_CALLS("Overlay draws"),
        /** glBufferData / glBufferSubData calls issued by the editor overlays. */
        BUFFER_UPLOADS("Overlay uploads"),
        /** Bytes the model mesh sent to the GPU (ModelGpuMesh). */
        MODEL_UPLOAD_BYTES("Model upload B");

        private final String label;

        Counter(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final int SPANS = Span.values().length;
    private static final int COUNTERS = Counter.values().length;
    private static final long WINDOW_NANOS = 1_000_000_000L;

    private static boolean enabled;

    private static final long[] frameNanos = new long[SPANS];
    private static final long[] lastNanos = new long[SPANS];
    private static final long[] windowMaxNanos = new long[SPANS];
    private static final long[] shownMaxNanos = new long[SPANS];
    private static final long[] totalNanos = new long[SPANS];
    private static final long[] totalCalls = new long[SPANS];

    private static final long[] frameCount = new long[COUNTERS];
    private static final long[] lastCount = new long[COUNTERS];
    private static final long[] windowMaxCount = new long[COUNTERS];
    private static final long[] shownMaxCount = new long[COUNTERS];
    private static final long[] totalCount = new long[COUNTERS];

    private static long frameStart;
    private static long windowStart;
    private static long lastFrameNanos;
    private static long windowMaxFrameNanos;
    private static long shownMaxFrameNanos;

    private EditCostStats() {
    }

    /** Switches recording on or off (off: every call returns at once). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Start of a span: pass the value to {@link #end}. 0 while disabled. */
    public static long begin() {
        return enabled ? System.nanoTime() : 0L;
    }

    /** End of a span started with {@link #begin()}. */
    public static void end(Span span, long start) {
        if (!enabled || start == 0L) return;
        long d = System.nanoTime() - start;
        int i = span.ordinal();
        frameNanos[i] += d;
        totalNanos[i] += d;
        totalCalls[i]++;
    }

    /** Adds {@code n} to a per-frame counter. */
    public static void count(Counter counter, long n) {
        if (!enabled) return;
        frameCount[counter.ordinal()] += n;
        totalCount[counter.ordinal()] += n;
    }

    /** Closes the current frame: per-frame sums become "last", window maxima roll every second. */
    public static void endFrame() {
        if (!enabled) return;
        long now = System.nanoTime();
        if (frameStart != 0L) {
            lastFrameNanos = now - frameStart;
            windowMaxFrameNanos = Math.max(windowMaxFrameNanos, lastFrameNanos);
        }
        frameStart = now;
        for (int i = 0; i < SPANS; i++) {
            if (frameNanos[i] > 0) {
                lastNanos[i] = frameNanos[i];
                windowMaxNanos[i] = Math.max(windowMaxNanos[i], frameNanos[i]);
            }
            frameNanos[i] = 0;
        }
        for (int i = 0; i < COUNTERS; i++) {
            lastCount[i] = frameCount[i];
            windowMaxCount[i] = Math.max(windowMaxCount[i], frameCount[i]);
            frameCount[i] = 0;
        }
        if (windowStart == 0L) windowStart = now;
        if (now - windowStart >= WINDOW_NANOS) {
            System.arraycopy(windowMaxNanos, 0, shownMaxNanos, 0, SPANS);
            System.arraycopy(windowMaxCount, 0, shownMaxCount, 0, COUNTERS);
            shownMaxFrameNanos = windowMaxFrameNanos;
            java.util.Arrays.fill(windowMaxNanos, 0L);
            java.util.Arrays.fill(windowMaxCount, 0L);
            windowMaxFrameNanos = 0L;
            windowStart = now;
        }
    }

    /** The span's sum in the last frame it ran, in milliseconds. */
    public static double lastMs(Span span) {
        return lastNanos[span.ordinal()] / 1e6;
    }

    /** The span's largest per-frame sum over the last full second, in milliseconds. */
    public static double maxMs(Span span) {
        return Math.max(shownMaxNanos[span.ordinal()], windowMaxNanos[span.ordinal()]) / 1e6;
    }

    /** The counter's value in the last closed frame. */
    public static long last(Counter counter) {
        return lastCount[counter.ordinal()];
    }

    /** The counter's largest per-frame value over the last full second. */
    public static long max(Counter counter) {
        return Math.max(shownMaxCount[counter.ordinal()], windowMaxCount[counter.ordinal()]);
    }

    /** Wall time between the last two {@link #endFrame()} calls, in milliseconds. */
    public static double frameMs() {
        return lastFrameNanos / 1e6;
    }

    /** Largest frame time over the last full second, in milliseconds. */
    public static double maxFrameMs() {
        return Math.max(shownMaxFrameNanos, windowMaxFrameNanos) / 1e6;
    }

    /** All-time sum of a span since the last {@link #reset()}, in nanoseconds. */
    public static long totalNanos(Span span) {
        return totalNanos[span.ordinal()];
    }

    /** All-time number of times a span ran since the last {@link #reset()}. */
    public static long totalCalls(Span span) {
        return totalCalls[span.ordinal()];
    }

    /** All-time counter total since the last {@link #reset()}. */
    public static long total(Counter counter) {
        return totalCount[counter.ordinal()];
    }

    /** Clears every value (bench phases, tests). */
    public static void reset() {
        java.util.Arrays.fill(frameNanos, 0L);
        java.util.Arrays.fill(lastNanos, 0L);
        java.util.Arrays.fill(windowMaxNanos, 0L);
        java.util.Arrays.fill(shownMaxNanos, 0L);
        java.util.Arrays.fill(totalNanos, 0L);
        java.util.Arrays.fill(totalCalls, 0L);
        java.util.Arrays.fill(frameCount, 0L);
        java.util.Arrays.fill(lastCount, 0L);
        java.util.Arrays.fill(windowMaxCount, 0L);
        java.util.Arrays.fill(shownMaxCount, 0L);
        java.util.Arrays.fill(totalCount, 0L);
        frameStart = windowStart = lastFrameNanos = windowMaxFrameNanos = shownMaxFrameNanos = 0L;
    }
}
