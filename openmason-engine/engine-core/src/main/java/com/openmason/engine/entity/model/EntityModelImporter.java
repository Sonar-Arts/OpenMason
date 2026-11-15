package com.openmason.engine.entity.model;

import java.io.IOException;
import java.io.InputStream;

/**
 * Turns a game's entity container (Stonebreak's {@code .sbe}, an {@code .sbo} used as an attachable
 * accessory...) into a neutral {@link EntityModelSource} (#88). Implemented by the game's
 * {@link com.openmason.engine.format.spi.FormatCodec} for that container and found through
 * {@link com.openmason.engine.format.spi.Formats} by file extension, so the engine itself only
 * reads bare {@code .omo} models.
 */
public interface EntityModelImporter {

    /**
     * Unpacks {@code in}.
     *
     * @param sourceId the resource path or file the bytes came from, for messages
     * @throws IOException when the bytes are not a valid container, or the container holds no model
     */
    EntityModelSource importEntity(String sourceId, InputStream in) throws IOException;
}
