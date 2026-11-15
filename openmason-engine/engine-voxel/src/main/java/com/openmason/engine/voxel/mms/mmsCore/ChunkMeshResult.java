package com.openmason.engine.voxel.mms.mmsCore;

import com.openmason.engine.voxel.stamp.StampRenderData;

import java.util.List;

/**
 * Result of chunk mesh generation containing atlas, water and stamp meshes.
 *
 * <p>The atlas mesh is rendered with the game's texture atlas (legacy blocks).
 * The water mesh carries all water geometry and is drawn by a dedicated water
 * renderer with its own shader — its vertex attributes reuse the atlas layout
 * slots with water semantics (tex = face-local UV, flags.x = surface height,
 * flags.y = falling, flags.z = source). Stamp entries are rendered with per-face
 * stamp textures (one entry per stamped block type).
 *
 * @param atlasMesh  mesh data for atlas-textured blocks (legacy cubes, crosses)
 * @param waterMesh  water geometry for the dedicated water renderer, may be null/empty
 * @param blockStamps  per-block-type stamp mesh data and face batches, may be null or empty
 */
public record ChunkMeshResult(MmsMeshData atlasMesh, MmsMeshData waterMesh, List<BlockStampEntry> blockStamps,
                              MmsMeshData stampMesh) {

    /**
     * Three-part result (no stamp mesh): the atlas mesh carries everything
     * atlas-textured. Under a pulled vertex format the mesher passes the
     * non-quad geometry separately as {@code stampMesh}.
     */
    public ChunkMeshResult(MmsMeshData atlasMesh, MmsMeshData waterMesh, List<BlockStampEntry> blockStamps) {
        this(atlasMesh, waterMesh, blockStamps, null);
    }

    /**
     * Whether this result carries atlas geometry that could not be expressed
     * as pulled quads (stamps, crosses) — drawn with the atlas pass from
     * its own per-vertex mesh.
     */
    public boolean hasStampMesh() {
        return stampMesh != null && stampMesh.getVertexCount() > 0;
    }

    /**
     * A single stamped block type's mesh data and face batches.
     *
     * @param meshData  the mesh data for this block type
     * @param batches   per-face render batches (texture IDs + index ranges)
     */
    public record BlockStampEntry(MmsMeshData meshData, StampRenderData.FaceBatch[] batches) {}

    /** Convenience constructor for atlas-only results. */
    public ChunkMeshResult(MmsMeshData atlasMesh) {
        this(atlasMesh, null, null);
    }

    /** Convenience constructor for results without water geometry. */
    public ChunkMeshResult(MmsMeshData atlasMesh, List<BlockStampEntry> blockStamps) {
        this(atlasMesh, null, blockStamps);
    }

    /** Whether this result contains any per-block-type stamp geometry. */
    public boolean hasBlockStamps() {
        return blockStamps != null && !blockStamps.isEmpty();
    }

    /** Whether this result contains any water geometry. */
    public boolean hasWaterMesh() {
        return waterMesh != null && waterMesh.getVertexCount() > 0;
    }
}
