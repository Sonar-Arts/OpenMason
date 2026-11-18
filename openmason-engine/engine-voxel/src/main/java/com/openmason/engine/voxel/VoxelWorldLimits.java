package com.openmason.engine.voxel;

import com.openmason.engine.voxel.cco.data.palette.CcoSectionIndexing;
import com.openmason.engine.voxel.mms.mmsCore.MmsQuadCodec;
import com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat;
import com.openmason.engine.voxel.mms.mmsCore.MmsWaterQuadCodec;
import com.openmason.engine.voxel.mms.mmsRegion.MmsChunkRegion;
import com.openmason.engine.voxel.mms.mmsRegion.MmsRegionKey;

import java.util.ArrayList;
import java.util.List;

/**
 * What world dimensions the engine's storage and mesh encodings can represent (#385). Every
 * limit here is a bit width or a layout of a format, not a tuning knob: a config past one does
 * not degrade gracefully, it corrupts or silently drops geometry. {@link #requireSupported} runs
 * when a world's {@link VoxelWorldConfig} is installed ({@code CcoBounds.configure}) so an
 * over-limit world fails at startup with every violated limit named.
 *
 * <ul>
 *   <li>{@code worldHeight} is a whole number of {@link CcoSectionIndexing#SECTION_HEIGHT}-block
 *       sections (paletted storage, saves and the chunk wire codec are sectioned);</li>
 *   <li>{@code worldHeight} fits the pulled quads' absolute Y ({@link MmsQuadCodec#MAX_Y},
 *       {@link MmsWaterQuadCodec#MAX_Y}) and the {@link MmsVertexFormat#COMPACT20} Y window;</li>
 *   <li>a region ({@link MmsChunkRegion#REGION_SPAN} chunks a side) fits the pulled quads'
 *       region-local x/z ({@link MmsQuadCodec#MAX_LOCAL_XZ}) and the COMPACT20 X/Z range;</li>
 *   <li>{@code seaLevel} lies inside the column.</li>
 * </ul>
 *
 * Not limits: a chunk wider than {@link MmsQuadCodec#MAX_EXTENT} only caps how far one greedy
 * rectangle may run, and a chunk wider than the native mesher's row masks
 * ({@code CendaKernels.MESH_MAX_CHUNK_SIZE}) meshes on the Java path.
 *
 * <h2>Column path only (deprecated)</h2>
 * Everything above is about the COLUMN world (chunk = full-height column, meshes
 * with absolute Y). The cubic runtime ({@code voxel.world}, 2026-11-17) has no
 * height rules at all: cubes are 32^3, meshes are REGION-local in all three axes
 * (region = {@link MmsRegionKey#REGION_BLOCKS}^3 blocks, which the packed
 * formats address on every axis — see {@link #CUBE_REGION_FITS_FORMATS}), and the
 * world's Y bounds are game data. These checks stay only for code still on the
 * column path ({@code CcoBounds.configure}) and go away with it.
 */
public final class VoxelWorldLimits {

    /**
     * Whether a cubic region ({@link MmsRegionKey#REGION_BLOCKS} blocks a side)
     * fits every packed mesh format's region-local range on all three axes:
     * QUAD16 / WATERQUAD16 x/z (8 bits: cells 0..255) and y (10 bits), and the
     * COMPACT20 window (vertex positions 0..256 inclusive). Pinned by a test, so a
     * format change that breaks the cubic layout fails loudly.
     */
    public static final boolean CUBE_REGION_FITS_FORMATS =
        MmsRegionKey.REGION_BLOCKS - 1 <= Math.min(MmsQuadCodec.MAX_LOCAL_XZ, MmsWaterQuadCodec.MAX_LOCAL_XZ)
            && MmsRegionKey.REGION_BLOCKS - 1 <= Math.min(MmsQuadCodec.MAX_Y, MmsWaterQuadCodec.MAX_Y)
            && MmsRegionKey.REGION_BLOCKS <= MmsVertexFormat.COMPACT_MAX_BLOCKS
            && MmsRegionKey.REGION_BLOCKS <= MmsVertexFormat.COMPACT_MAX_Y
            && 0f >= MmsVertexFormat.COMPACT_MIN_Y;

    /**
     * Tallest supported column.
     *
     * @deprecated column path only; the cubic runtime has no height limit.
     */
    @Deprecated
    public static final int MAX_WORLD_HEIGHT = Math.min(MmsQuadCodec.MAX_Y, MmsWaterQuadCodec.MAX_Y) + 1;

    /** Widest supported chunk: a region of them must fit the pulled quads' local x/z. */
    public static final int MAX_CHUNK_SIZE =
        (Math.min(MmsQuadCodec.MAX_LOCAL_XZ, MmsWaterQuadCodec.MAX_LOCAL_XZ) + 1) / MmsChunkRegion.REGION_SPAN;

    private VoxelWorldLimits() {
    }

    /**
     * Every limit {@code config} breaks, each as one sentence naming the format; empty when supported.
     *
     * @deprecated column path only ({@code CcoBounds.configure}); cubic worlds have no height rules.
     */
    @Deprecated
    public static List<String> problems(VoxelWorldConfig config) {
        List<String> out = new ArrayList<>();
        int cs = config.chunkSize();
        int wh = config.worldHeight();
        int section = CcoSectionIndexing.SECTION_HEIGHT;
        if (wh % section != 0) {
            out.add("worldHeight " + wh + " is not a multiple of the " + section
                + "-block section height (paletted storage, saves and the chunk wire codec are sectioned)");
        }
        if (wh > MAX_WORLD_HEIGHT) {
            out.add("worldHeight " + wh + " exceeds " + MAX_WORLD_HEIGHT
                + " (the QUAD16/WATERQUAD16 mesh records store absolute Y in 10 bits)");
        }
        if (wh > MmsVertexFormat.COMPACT_MAX_Y) {
            out.add("worldHeight " + wh + " exceeds the COMPACT20 stamp mesh Y window (max "
                + (int) MmsVertexFormat.COMPACT_MAX_Y + ")");
        }
        int regionBlocks = cs * MmsChunkRegion.REGION_SPAN;
        if (cs > MAX_CHUNK_SIZE) {
            out.add("chunkSize " + cs + " exceeds " + MAX_CHUNK_SIZE + ": a " + MmsChunkRegion.REGION_SPAN
                + "x" + MmsChunkRegion.REGION_SPAN + "-chunk region would be " + regionBlocks
                + " blocks wide, past the " + (MmsQuadCodec.MAX_LOCAL_XZ + 1)
                + " the QUAD16/WATERQUAD16 records address (8-bit region-local x/z)");
        }
        if (regionBlocks > MmsVertexFormat.COMPACT_MAX_BLOCKS) {
            out.add("chunkSize " + cs + " makes a region " + regionBlocks
                + " blocks wide, past the COMPACT20 stamp mesh X/Z range (" + (int) MmsVertexFormat.COMPACT_MAX_BLOCKS
                + ")");
        }
        if (config.seaLevel() < 0 || config.seaLevel() >= wh) {
            out.add("seaLevel " + config.seaLevel() + " is outside the column [0, " + wh + ")");
        }
        return out;
    }

    /**
     * @throws IllegalArgumentException naming every limit {@code config} breaks
     * @deprecated column path only ({@code CcoBounds.configure}); cubic worlds have no height rules.
     */
    @Deprecated
    public static VoxelWorldConfig requireSupported(VoxelWorldConfig config) {
        List<String> problems = problems(config);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Unsupported voxel world dimensions " + config + ":\n  - "
                + String.join("\n  - ", problems));
        }
        return config;
    }
}
