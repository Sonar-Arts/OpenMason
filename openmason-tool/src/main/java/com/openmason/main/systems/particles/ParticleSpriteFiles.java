package com.openmason.main.systems.particles;

import com.openmason.engine.rendering.particles.ParticleImage;
import com.openmason.engine.rendering.particles.ParticleSpriteLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * The Particle Editor's sprite loader: a texture name is an image file path, relative to the effect document's
 * folder (so a document and its sprites move together) or absolute. An unsaved document has no folder, so only
 * absolute names load until it is saved. GL-free.
 *
 * <p>Until the {@code .omfx} v2 format embeds sprites in the document (OM #101), these files are where the
 * pixels live.
 */
final class ParticleSpriteFiles implements ParticleSpriteLoader {

    private final Path baseDir;

    /** @param baseDir the document's folder, or null for an unsaved document */
    ParticleSpriteFiles(Path baseDir) {
        this.baseDir = baseDir == null ? null : baseDir.toAbsolutePath().normalize();
    }

    /** The file {@code name} refers to, or null when it cannot be resolved (relative name, no folder). */
    Path resolve(String name) {
        Path p;
        try {
            p = Path.of(name);
        } catch (InvalidPathException e) {
            return null;
        }
        if (p.isAbsolute()) {
            return p.normalize();
        }
        return baseDir == null ? null : baseDir.resolve(p).normalize();
    }

    @Override
    public ParticleImage load(String name) throws IOException {
        Path file = resolve(name);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            return ParticleImage.decode(in);
        }
    }

    /**
     * The texture name to store for {@code file}: relative to {@code baseDir} with '/' separators when the file is
     * inside it, else the absolute path.
     */
    static String nameFor(Path file, Path baseDir) {
        Path abs = file.toAbsolutePath().normalize();
        if (baseDir != null) {
            Path base = baseDir.toAbsolutePath().normalize();
            if (abs.startsWith(base)) {
                return base.relativize(abs).toString().replace('\\', '/');
            }
        }
        return abs.toString();
    }
}
