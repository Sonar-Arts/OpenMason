package com.openmason.engine.voxel.mms.mmsRegion;

import com.openmason.engine.voxel.mms.mmsCore.MmsQuadCodec;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL32;

/**
 * One mesh (a cube's layer, or a legacy chunk mesh) living inside a region's
 * arenas: a vertex segment plus an index segment (none for pulled formats,
 * which share the quad EBO). Where a legacy {@code MmsRenderableHandle} owns a
 * whole VAO/VBO/EBO triple, this is just ranges of the region's shared
 * buffers — drawing happens through the region's multidraw / GPU cull, never
 * per handle.
 *
 * <p><b>Bounds</b> are region-local (relative to the region origin, see
 * {@link MmsChunkRegion#originBlockX()}); culling adds the region's
 * camera-relative origin, so no absolute world float is ever involved.
 *
 * <p><b>Enable bit</b>: a disabled mesh is skipped by both cull paths (GPU
 * kernel via the MeshMeta flags word, CPU multidraw via
 * {@link MmsMultiDrawBatch#add(MmsRegionMeshHandle)} / {@link MmsChunkRegion#appendVisible}).
 * Hosts use it for the far-LOD handover: detail cubes of a column that is not
 * READY stay resident but undrawn, so they never z-fight the LOD heightfield.
 *
 * <p><b>Commands</b>: a pulled mesh with more quads than the shared u16 quad
 * EBO addresses ({@link MmsQuadCodec#MAX_QUADS_PER_DRAW}) draws as several
 * commands with advancing base vertices; everything else is one command.
 *
 * <p>{@link #close()} returns the segments to the region (GL-thread only, via
 * the host's deferred cleanup queue). Idempotent.
 */
public final class MmsRegionMeshHandle implements AutoCloseable {

    /** Bit 0 of the MeshMeta flags word ({@code draw.w}): the mesh may be drawn. */
    public static final int FLAG_ENABLED = 1;

    private final MmsChunkRegion region;
    final MmsArenaAllocator.Segment vertexSegment;
    final MmsArenaAllocator.Segment indexSegment;
    private final int indexCount;
    /** Bytes per index element of the owning region (2 or 4). */
    private final int indexBytes;
    /** Pulled meshes: quads (0 for indexed meshes). */
    private final int quadCount;
    private final int commandCount;
    /** Region-local bounding box (GPU/CPU cull). */
    final float minX, minY, minZ, maxX, maxY, maxZ;
    /** Position in the region's live-handle list (swap-remove bookkeeping). */
    int liveIndex;
    private boolean enabled = true;
    private boolean closed;

    MmsRegionMeshHandle(MmsChunkRegion region,
                        MmsArenaAllocator.Segment vertexSegment,
                        MmsArenaAllocator.Segment indexSegment,
                        int indexCount, int indexBytes, int quadCount,
                        float minX, float minY, float minZ,
                        float maxX, float maxY, float maxZ) {
        this.region = region;
        this.vertexSegment = vertexSegment;
        this.indexSegment = indexSegment;
        this.indexCount = indexCount;
        this.indexBytes = indexBytes;
        this.quadCount = quadCount;
        this.commandCount = commandCount(indexSegment == null, quadCount);
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    /** Commands a mesh draws as: pulled meshes split at the shared quad EBO's size. */
    static int commandCount(boolean pulled, int quadCount) {
        if (!pulled) {
            return 1;
        }
        return Math.max(1, (quadCount + MmsQuadCodec.MAX_QUADS_PER_DRAW - 1) / MmsQuadCodec.MAX_QUADS_PER_DRAW);
    }

    public MmsChunkRegion region() {
        return region;
    }

    /** Total indices over every command (6 per quad for pulled meshes). */
    public int getIndexCount() {
        return indexCount;
    }

    /** The mesh's first vertex in the region vertex buffer (first command's base vertex). */
    public int baseVertex() {
        return vertexSegment.offset();
    }

    /** Byte offset of the mesh's indices in the region index buffer (0 for pulled meshes). */
    public long indexOffsetBytes() {
        return indexSegment == null ? 0L : (long) indexSegment.offset() * indexBytes;
    }

    /** Bytes per index element (2 = u16, 4 = u32) of the region this mesh lives in. */
    public int indexBytes() {
        return indexBytes;
    }

    /** Draw commands this mesh needs (1 unless a pulled mesh exceeds the shared quad EBO). */
    public int commandCount() {
        return commandCount;
    }

    /** Index count of command {@code k}. */
    public int commandIndexCount(int k) {
        if (indexSegment != null || quadCount == 0) {
            return indexCount;
        }
        int perDraw = MmsQuadCodec.MAX_QUADS_PER_DRAW;
        return Math.min(perDraw, quadCount - k * perDraw) * 6;
    }

    /** First index (in elements) of command {@code k}. */
    public int commandFirstIndex(int k) {
        return indexSegment == null ? 0 : indexSegment.offset();
    }

    /** Base vertex of command {@code k}. */
    public int commandBaseVertex(int k) {
        return vertexSegment.offset() + k * MmsQuadCodec.MAX_QUADS_PER_DRAW * 4;
    }

    /** Whether the cull paths may draw this mesh (default true). */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables drawing without touching GPU memory. Takes effect at
     * the next cull (GPU metadata is rewritten every cull) or CPU batch.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** The MeshMeta flags word ({@code draw.w}) the GPU cull reads. */
    int metaFlags() {
        return enabled ? FLAG_ENABLED : 0;
    }

    public float localMinX() {
        return minX;
    }

    public float localMinY() {
        return minY;
    }

    public float localMinZ() {
        return minZ;
    }

    public float localMaxX() {
        return maxX;
    }

    public float localMaxY() {
        return maxY;
    }

    public float localMaxZ() {
        return maxZ;
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Draws just this mesh (when enabled): binds the region VAO and issues
     * {@code glDrawElementsBaseVertex} per command. Meant for the rare cases
     * where a region-resident mesh needs individual state (e.g. a per-node
     * fade uniform) — batched paths should go through the region's multidraw.
     * The caller unbinds the VAO after its loop.
     */
    public void render() {
        if (!enabled) {
            return;
        }
        region.bind();
        int type = region.indexType();
        for (int k = 0; k < commandCount; k++) {
            GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, commandIndexCount(k), type,
                (long) commandFirstIndex(k) * indexBytes, commandBaseVertex(k));
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        region.free(this);
    }
}
