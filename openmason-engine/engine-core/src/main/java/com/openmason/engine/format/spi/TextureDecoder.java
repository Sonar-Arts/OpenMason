package com.openmason.engine.format.spi;

import java.io.IOException;

/**
 * A texture container a game wraps around an OMT (#88): Stonebreak's SBT is one. The engine's
 * texture probe ({@code TextureBytes}) and the UI's texture loader ({@code MTexture}) try every
 * registered decoder before reading the bytes as a bare OMT, so the engine never names a game's
 * container. Registered like {@link FormatCodec} ({@link Formats#register(TextureDecoder)} or a
 * {@code provides}/{@code META-INF/services} entry).
 */
public interface TextureDecoder {

    /** Stable lowercase id ({@code "sbt"}); unique among registered decoders. */
    String id();

    /** Lowercase extensions with the leading dot this container uses ({@code ".sbt"}). */
    java.util.List<String> extensions();

    /**
     * The OMT bytes wrapped in {@code data}, or null when {@code data} is not this container.
     * Implementations should answer null (not throw) for foreign bytes; an exception is treated
     * the same way by callers.
     */
    byte[] unwrapOmt(byte[] data) throws IOException;

    /**
     * {@code container} with its OMT replaced by {@code omtBytes}, keeping the container's identity
     * and metadata (the UI editor edits a container's layers as a sibling OMT and re-wraps it on
     * save), or null when this container cannot be rewritten.
     */
    default byte[] rewrap(byte[] container, byte[] omtBytes) throws IOException {
        return null;
    }
}
