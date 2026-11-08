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
 * What the Particles tab exports (#102) while it still edits v1 effect libraries (until OM #104): the selected
 * effect as a one-emitter FX system ({@link FxSystem#of}, burst size by {@link OmfxMigration#defaultBurst}, the
 * preview's rule), its sprite files embedded under the names the effect uses so the export is self-contained.
 * GL-free.
 *
 * @param document the system as an {@code .omfx} v2 document
 * @param missing  texture names whose image file was not found (exported as names the game must resolve)
 */
public record FxExportSource(OmfxDocument document, List<String> missing) {

    public FxExportSource {
        missing = List.copyOf(missing);
    }

    /** {@code effect} as a document; texture names resolve as the tab does (relative to {@code baseDir}). */
    public static FxExportSource of(ParticleEffect effect, Path baseDir) {
        OmfxDocument doc = OmfxDocument.of(FxSystem.of(effect, OmfxMigration.defaultBurst(effect)));
        ParticleSpriteFiles files = new ParticleSpriteFiles(baseDir);
        List<String> missing = new ArrayList<>();
        for (String name : doc.referencedTextures()) {
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
}
