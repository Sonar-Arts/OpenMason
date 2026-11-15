package com.openmason.engine.voxel.stamp.render;

import com.openmason.engine.voxel.stamp.BlockModelSource;
import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.ILayerIndexProvider;
import com.openmason.engine.voxel.ITextureCoordProvider;
import com.openmason.engine.voxel.stamp.StampMeshProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Facade for the SBO block rendering pipeline.
 *
 * <p>Provides a single entry point for initializing and accessing the complete
 * SBO rendering system: stamp computation, caching, and chunk mesh emission.
 *
 * <h3>Initialization</h3>
 * <pre>{@code
 * StampRendererAPI sboRenderer = new StampRendererAPI();
 * sboRenderer.initialize(sboBlockMap, uvProvider);
 * }</pre>
 *
 * <h3>Chunk Mesh Generation</h3>
 * <pre>{@code
 * StampEmitter emitter = sboRenderer.createEmitter(cullingPolicy);
 * // During chunk iteration:
 * if (emitter.hasBlock(blockType)) {
 *     emitter.emitBlock(builder, blockType, lx, ly, lz, worldX, worldY, worldZ, chunkData);
 * }
 * }</pre>
 */
public class StampRendererAPI {

    private static final Logger logger = LoggerFactory.getLogger(StampRendererAPI.class);

    private final StampMeshProcessor meshProcessor;
    private final StampCache stampCache;
    private boolean initialized = false;

    public StampRendererAPI() {
        this.meshProcessor = new StampMeshProcessor();
        this.stampCache = new StampCache();
    }

    /**
     * Initialize the SBO renderer by processing all SBO block definitions into stamps.
     *
     * <p>For each SBO block, computes flat normals, de-indexes geometry, remaps UVs
     * to atlas coordinates, and caches the result as a {@link com.openmason.engine.voxel.stamp.StampMeshProcessor.BlockStamp}.
     *
     * @param sboBlocks     map of block types to their neutral block models
     * @param uvProvider    texture coordinate provider for tile-local UV lookups
     * @param layerProvider texture-array layer index provider
     * @return number of block types successfully processed
     */
    public int initialize(Map<? extends IBlockType, BlockModelSource> sboBlocks,
                          ITextureCoordProvider uvProvider,
                          ILayerIndexProvider layerProvider) {
        int processed = 0;

        for (var entry : sboBlocks.entrySet()) {
            IBlockType blockType = entry.getKey();
            BlockModelSource sbo = entry.getValue();

            if (meshProcessor.process(blockType, sbo, uvProvider, layerProvider, stampCache)) {
                processed++;
            }
        }

        initialized = true;
        logger.info("SBO Renderer initialized: {} block types processed into stamps", processed);
        return processed;
    }

    /**
     * Create a stamp emitter for chunk mesh generation.
     *
     * @param cullingPolicy face culling strategy to use
     * @return a new emitter backed by this API's stamp cache
     */
    public StampEmitter createEmitter(StampCullingPolicy cullingPolicy) {
        return new StampEmitter(stampCache, cullingPolicy);
    }

    /**
     * Create a stamp emitter with an explicit translucency policy.
     *
     * @param cullingPolicy      face culling strategy
     * @param translucencyPolicy predicate identifying translucent blocks
     * @return a new emitter backed by this API's stamp cache
     */
    public StampEmitter createEmitter(StampCullingPolicy cullingPolicy,
                                         java.util.function.Predicate<com.openmason.engine.voxel.IBlockType> translucencyPolicy) {
        return new StampEmitter(stampCache, cullingPolicy, translucencyPolicy);
    }

    /**
     * Check if a block type has SBO geometry.
     *
     * @param blockType the block type to check
     * @return true if an SBO stamp exists for this type
     */
    public boolean hasBlock(IBlockType blockType) {
        return stampCache.has(blockType);
    }

    /**
     * Get the stamp cache for direct access (e.g., by legacy providers).
     */
    public StampCache getStampCache() {
        return stampCache;
    }

    /**
     * Get the underlying mesh processor.
     */
    public StampMeshProcessor getMeshProcessor() {
        return meshProcessor;
    }

    /**
     * Whether the API has been initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Get the number of processed SBO block types.
     */
    public int size() {
        return stampCache.size();
    }
}
