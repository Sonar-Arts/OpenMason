package com.openmason.api.fx;

import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.FxWorld;
import com.openmason.engine.rendering.particles.ParticleImage;
import com.openmason.engine.rendering.particles.ParticleSystem;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.openmason.engine.rendering.particles.omfx.OmfxSprite;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What an {@link FxExporter} turns into a game's shipped FX file (#102): the engine-neutral source document
 * ({@code .omfx} v2, OM #101), already checked to compile in the engine runtime, plus its embedded sprites decoded
 * to pixels. Game data a plugin's export sections wrote lives in {@link OmfxDocument#extensions()} under the
 * plugin's namespace.
 *
 * @param document the source document (immutable)
 * @param sprites  the embedded sprites decoded, by texture name, in document order (straight-alpha RGBA)
 */
public record FxExportInput(OmfxDocument document, Map<String, ParticleImage> sprites) {

    public FxExportInput {
        Objects.requireNonNull(document, "document");
        sprites = Collections.unmodifiableMap(new LinkedHashMap<>(sprites == null ? Map.of() : sprites));
    }

    /**
     * Checks {@code document} the way the game runtime will run it — every effect and the system compiled by the
     * engine (graphs type-checked, sub-emitters resolved) — and decodes its sprites. Fails with a reason.
     */
    public static FxExportInput prepare(OmfxDocument document) throws IOException {
        Objects.requireNonNull(document, "document");
        try (FxWorld world = new FxWorld(new ParticleSystem(64))) {
            world.register(document.system());
        } catch (RuntimeException e) {
            throw new IOException(document.id() + " does not compile: " + e.getMessage(), e);
        }
        Map<String, ParticleImage> decoded = new LinkedHashMap<>();
        for (OmfxSprite s : document.sprites()) {
            try {
                decoded.put(s.name(), s.decode());
            } catch (IOException | RuntimeException e) {
                throw new IOException(document.id() + ": sprite '" + s.name() + "' cannot be decoded: "
                        + e.getMessage(), e);
            }
        }
        return new FxExportInput(document, decoded);
    }

    /** The document id (the system's name). */
    public String id() {
        return document.id();
    }

    /** The FX system. */
    public FxSystem system() {
        return document.system();
    }

    /** Texture names the effects list that the document does not embed (the game must already have them). */
    public Set<String> externalTextures() {
        return document.externalTextures();
    }
}
