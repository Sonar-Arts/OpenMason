package com.openmason.main.systems.particles;

import com.openmason.engine.rendering.particles.ParticleImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The editor's sprite loader: texture names are image paths relative to the document (OM #97). */
class ParticleSpriteFilesTest {

    @TempDir
    Path dir;

    private Path png(Path file, int argb) throws IOException {
        Files.createDirectories(file.getParent());
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) img.setRGB(x, y, argb);
        ImageIO.write(img, "png", file.toFile());
        return file;
    }

    @Test
    void relativeNamesLoadFromTheDocumentFolderAndAbsoluteOnesFromAnywhere() throws IOException {
        png(dir.resolve("fx/smoke.png"), 0xFF808080);
        Path elsewhere = png(dir.resolve("other/spark.png"), 0xFFFFFF00);
        ParticleSpriteFiles files = new ParticleSpriteFiles(dir.resolve("fx"));

        ParticleImage smoke = files.load("smoke.png");
        assertNotNull(smoke);
        assertEquals(0x808080FF, smoke.texel(0, 0));
        assertNotNull(files.load(elsewhere.toString()));
        assertNotNull(files.load("../other/spark.png"));
        assertNull(files.load("nope.png"), "a missing file is null, not an exception");
        assertNull(files.load("fx"), "a folder is not an image");
    }

    @Test
    void unsavedDocumentsOnlyLoadAbsoluteNames() throws IOException {
        Path abs = png(dir.resolve("a.png"), 0xFFFFFFFF);
        ParticleSpriteFiles files = new ParticleSpriteFiles(null);
        assertNull(files.resolve("a.png"));
        assertNull(files.load("a.png"));
        assertNotNull(files.load(abs.toString()));
    }

    @Test
    void namesAreStoredRelativeWhenInsideTheDocumentFolder() {
        assertEquals("sprites/smoke.png", ParticleSpriteFiles.nameFor(dir.resolve("sprites/smoke.png"), dir));
        Path outside = dir.getParent().resolve("x.png");
        assertEquals(outside.toAbsolutePath().normalize().toString(), ParticleSpriteFiles.nameFor(outside, dir));
        assertEquals(dir.resolve("y.png").toString(), ParticleSpriteFiles.nameFor(dir.resolve("y.png"), null));
    }
}
