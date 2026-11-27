package com.openmason.main.systems.rendering.model.editing.subrenders;

import com.openmason.engine.modeling.core.EditCostStats;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferSubData;

/**
 * Dirty float ranges of an overlay's CPU mirror, flushed to its VBO once per frame (#175): the overlays used to
 * send one {@code glBufferSubData} per moved vertex / per recoloured element. A few spans upload one by one; many
 * collapse into their union (one call).
 */
public final class DirtySpans {

    /** More spans than this upload as one union range. */
    private static final int MAX_SPANS = 32;

    private final int[] starts = new int[MAX_SPANS];
    private final int[] ends = new int[MAX_SPANS];
    private int count;
    private int unionStart = Integer.MAX_VALUE;
    private int unionEnd = -1;

    /** Marks floats {@code [start, end)} of the mirror dirty. */
    public void mark(int start, int end) {
        if (end <= start) return;
        if (count < MAX_SPANS) {
            starts[count] = start;
            ends[count] = end;
        }
        count++;
        unionStart = Math.min(unionStart, start);
        unionEnd = Math.max(unionEnd, end);
    }

    public boolean isEmpty() {
        return count == 0;
    }

    /** Forgets every span (the whole buffer was re-sent). */
    public void clear() {
        count = 0;
        unionStart = Integer.MAX_VALUE;
        unionEnd = -1;
    }

    /** Sends the dirty spans of {@code mirror} to {@code vbo} and clears them. */
    public void flush(int vbo, float[] mirror) {
        if (count == 0 || vbo == 0 || mirror == null) {
            clear();
            return;
        }
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        if (count > MAX_SPANS) {
            upload(mirror, unionStart, Math.min(unionEnd, mirror.length));
        } else {
            for (int i = 0; i < count; i++) {
                upload(mirror, starts[i], Math.min(ends[i], mirror.length));
            }
        }
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        clear();
    }

    private static void upload(float[] mirror, int start, int end) {
        if (end <= start) return;
        FloatBuffer slice = FloatBuffer.wrap(mirror, start, end - start).slice();
        // LWJGL needs a direct buffer: copy through the reusable staging buffer
        glBufferSubData(GL_ARRAY_BUFFER, (long) start * Float.BYTES, Staging.floats(slice));
        EditCostStats.count(EditCostStats.Counter.BUFFER_UPLOADS, 1);
    }

    /** One grow-only direct staging buffer for the overlays' uploads (GL thread only). */
    static final class Staging {
        private static FloatBuffer buffer;

        private Staging() {
        }

        static FloatBuffer floats(FloatBuffer src) {
            int n = src.remaining();
            if (buffer == null || buffer.capacity() < n) {
                if (buffer != null) org.lwjgl.system.MemoryUtil.memFree(buffer);
                buffer = org.lwjgl.system.MemoryUtil.memAllocFloat(Math.max(n, 1024));
            }
            buffer.clear();
            buffer.put(src);
            buffer.flip();
            return buffer;
        }
    }
}
