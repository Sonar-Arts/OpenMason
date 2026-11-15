package com.openmason.api.asset;

/**
 * The texture the Texture Editor holds, as a service (#88): what an asset kind's texture export
 * reads. Published by the editor in its {@code ServiceRegistry}.
 */
@FunctionalInterface
public interface TextureSource {

    /** The saved {@code .omt} the Texture Editor has open, or null (unsaved, or a PNG session). */
    String currentOmtPath();
}
