package com.openmason.engine.voxel.mms.mmsCore;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * One process-wide u16 element buffer holding the quad pattern
 * {@code (b, b+2, b+1, b, b+3, b+2)} for {@link MmsQuadCodec#MAX_QUADS_PER_DRAW}
 * quads — the winding {@link MmsMeshBuilder#endFace()} emits. Pulled
 * ({@link MmsVertexFormat#QUAD16}) meshes carry no indices of their own: every
 * region VAO and per-chunk handle binds this buffer, and draws address their
 * quads through {@code baseVertex}. Created lazily on the GL thread; never
 * deleted (192 KiB for the lifetime of the context).
 */
public final class MmsSharedQuadIndexBuffer {

    private static int id;
    private static int stampId;

    private MmsSharedQuadIndexBuffer() {
    }

    /** The element buffer id, creating it on first use (GL thread). */
    public static synchronized int id() {
        if (id == 0) {
            int quads = MmsQuadCodec.MAX_QUADS_PER_DRAW;
            ByteBuffer buf = ByteBuffer.allocateDirect(quads * 6 * Short.BYTES).order(ByteOrder.nativeOrder());
            for (int q = 0; q < quads; q++) {
                int b = q * 4;
                buf.putShort((short) b).putShort((short) (b + 2)).putShort((short) (b + 1));
                buf.putShort((short) b).putShort((short) (b + 3)).putShort((short) (b + 2));
            }
            buf.flip();
            id = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, id);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.OTHER, buf.limit());
        }
        return id;
    }

    /**
     * A second u16 element buffer with the STAMP quad pattern {@code (b, b+1, b+2, b, b+2, b+3)} - the winding
     * of the native stamp meshes ({@code cv_gpu_mesh.stamp_vertices}: 4 vertices per quad, triangles (0,1,2)
     * (0,2,3)) - so quad-indexed stamp regions carry no index arena (2026-11-22, a quarter of a stamp quad's
     * bytes). Same size and lifetime as {@link #id()}.
     */
    public static synchronized int stampId() {
        if (stampId == 0) {
            int quads = MmsQuadCodec.MAX_QUADS_PER_DRAW;
            ByteBuffer buf = ByteBuffer.allocateDirect(quads * 6 * Short.BYTES).order(ByteOrder.nativeOrder());
            for (int q = 0; q < quads; q++) {
                int b = q * 4;
                buf.putShort((short) b).putShort((short) (b + 1)).putShort((short) (b + 2));
                buf.putShort((short) b).putShort((short) (b + 2)).putShort((short) (b + 3));
            }
            buf.flip();
            stampId = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, stampId);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.OTHER, buf.limit());
        }
        return stampId;
    }

    /** Forgets the buffers after a context loss / test teardown (does not delete). */
    public static synchronized void invalidate() {
        id = 0;
        stampId = 0;
    }
}
