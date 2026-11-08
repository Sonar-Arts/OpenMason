package com.openmason.main.systems.particles;

import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.openmason.engine.rendering.particles.omfx.OmfxMigration;
import com.openmason.engine.rendering.particles.omfx.OmfxSprite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What the FX editor exports (#102, OM #104): the document as it is, plus its sprite files - texture names that are
 * not embedded but resolve to an image next to the document - embedded under those names, so the export is
 * self-contained. GL-free.
 *
 * @param document the document to export
 * @param missing  texture names whose image file was not found (exported as names the game must resolve)
 */
public record FxExportSource(OmfxDocument document, List<String> missing) {

    public FxExportSource {
        missing = List.copyOf(missing);
    }

    /** {@code doc} with its external texture files (relative to {@code baseDir}, or absolute) embedded. */
    public static FxExportSource of(OmfxDocument doc, Path baseDir) {
        ParticleSpriteFiles files = new ParticleSpriteFiles(baseDir);
        List<String> missing = new ArrayList<>();
        for (String name : doc.externalTextures()) {
            Path file = files.resolve(name);
            try {
                if (file == null || !Files.isRegularFile(file)) {
                    missing.add(name);
                    continue;
                }
                OmfxSprite sprite = new OmfxSprite(name, Files.readAllBytes(file));
                sprite.decode(); // only images the engine can read are embedded
                doc = doc.withSprite(sprite);
            } catch (IOException | RuntimeException e) {
                missing.add(name);
            }
        }
        return new FxExportSource(doc, missing);
    }

    /** {@code effect} as a one-emitter document (burst size by {@link OmfxMigration#defaultBurst}). */
    public static FxExportSource of(ParticleEffect effect, Path baseDir) {
        return of(OmfxDocument.of(FxSystem.of(effect, OmfxMigration.defaultBurst(effect))), baseDir);
    }
}
