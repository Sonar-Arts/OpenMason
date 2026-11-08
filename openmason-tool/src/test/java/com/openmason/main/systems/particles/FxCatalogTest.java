package com.openmason.main.systems.particles;

import com.openmason.engine.rendering.particles.FxParameter;
import com.openmason.engine.rendering.particles.FxPresets;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleLibrary;
import com.openmason.engine.rendering.particles.omfx.OmfxCodec;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the FX tab checks bindings against (#103): project .omfx files (v1 and v2) plus the engine presets. */
@Tag("regression")
class FxCatalogTest {

    @TempDir
    Path project;

    @Test
    void presetsAreAlwaysKnown() {
        FxCatalog catalog = FxCatalog.scan(null);
        FxCatalog.Entry campfire = catalog.find("campfire");
        assertNotNull(campfire);
        assertEquals(FxCatalog.Source.PRESET, campfire.source());
        assertEquals(FxPresets.CAMPFIRE.parameters().size(), campfire.params().size());
        assertNotNull(catalog.find("smoke"), "effect presets play as one-emitter systems");
        assertNull(catalog.find("no-such-fx"));
        assertNull(FxCatalog.empty().find("campfire"));
    }

    @Test
    void projectSystemsAndLibrariesAreScanned() throws IOException {
        FxSystem sparkle = FxSystem.of(ParticleLibrary.Presets.MAGIC, 30).toBuilder().name("sparkle")
                .parameter(FxParameter.scalar("intensity", 0.5f))
                .parameter(FxParameter.color("tint", 0xFF8040FF))
                .parameter(FxParameter.vector("wind", 1f, 0f, 0f))
                .build();
        Path particles = Files.createDirectories(project.resolve("Particles"));
        OmfxCodec.write(OmfxDocument.of(sparkle), particles.resolve("sparkle.omfx"));

        ParticleLibrary v1 = new ParticleLibrary();
        v1.register(ParticleLibrary.Presets.SMOKE.toBuilder().name("chimney").build());
        try (OutputStream out = Files.newOutputStream(particles.resolve("legacy.omfx"))) {
            v1.write(out);
        }
        Files.writeString(particles.resolve("broken.omfx"), "not an fx file");
        Path hidden = Files.createDirectories(project.resolve(".cache"));
        OmfxCodec.write(OmfxDocument.of(FxSystem.of(ParticleLibrary.Presets.FIRE, 1).toBuilder().name("ghost")
                .build()), hidden.resolve("ghost.omfx"));

        FxCatalog catalog = FxCatalog.scan(project);
        FxCatalog.Entry entry = catalog.find("sparkle");
        assertNotNull(entry);
        assertEquals(FxCatalog.Source.PROJECT, entry.source());
        assertEquals("Particles/sparkle.omfx", entry.origin());
        assertEquals(1, entry.param("intensity").components());
        assertEquals(4, entry.param("tint").components());
        assertEquals(3, entry.param("wind").components());
        assertEquals(1f, entry.param("tint").defaultValue().get(0), 1e-6);

        assertNotNull(catalog.find("chimney"), "a v1 library contributes its effects");
        assertNull(catalog.find("ghost"), "hidden folders are not scanned");
        assertEquals(1, catalog.problems().size());
        assertTrue(catalog.problems().get(0).startsWith("Particles/broken.omfx"), catalog.problems().toString());
    }

    @Test
    void namespacedIdsMatchByName() {
        FxCatalog catalog = FxCatalog.presets();
        assertEquals("campfire", catalog.find("mygame:campfire").id());
        assertNull(catalog.find("mygame:"));
        assertFalse(catalog.entries().isEmpty());
    }
}
