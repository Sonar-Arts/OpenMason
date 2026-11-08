package com.openmason.engine.rendering.particles.omfx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.format.ext.FormatExtensions;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleLibrary;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Brings {@code .omfx} v1 particle libraries ({@code openmason.particles} JSON: a flat list of single-layer effects)
 * into v2 documents (OM #101). Every effect becomes a <b>single-emitter system</b> that plays exactly as the effect
 * did ({@link FxSystem#of}): a rate effect emits until stopped, a burst effect (rate 0) fires
 * {@link Options#burstCount} particles once. Sub-emitter targets stay inside the system that uses them and also
 * become systems of their own.
 *
 * <p><b>Sprites.</b> A texture name whose image the {@link SpriteSource} can supply is embedded under the same name
 * (so the effect is unchanged); a name it cannot supply stays external, for the host to resolve, with a warning.
 * Libraries from before OM #97 used atlas frame numbers ({@code "sprite": "atlas", "frames": {start, count,
 * animate}}); with the atlas image ({@link Atlas}) each frame becomes its own embedded sprite
 * ({@code atlas-<frame>}), without it the effect falls back to the procedural soft disc, with a warning.
 */
public final class OmfxMigration {

    /** Name prefix of sprites cut from a legacy atlas. */
    public static final String ATLAS_SPRITE_PREFIX = "atlas-";

    private OmfxMigration() {
    }

    /** Supplies the encoded image (PNG or OMT bytes) behind a v1 texture name, or null when there is none. */
    @FunctionalInterface
    public interface SpriteSource {
        byte[] load(String name) throws IOException;

        /** Supplies nothing: every texture name stays external. */
        SpriteSource NONE = name -> null;
    }

    /**
     * A legacy sprite atlas: frame {@code f} is column {@code f % columns}, row {@code f / columns}, row 0 at the
     * image top, each cell {@code width / columns × height / rows} pixels.
     */
    public record Atlas(byte[] image, int columns, int rows) {
        public Atlas {
            Objects.requireNonNull(image, "image");
            if (columns < 1 || rows < 1) {
                throw new IllegalArgumentException("atlas grid must be at least 1x1");
            }
            image = image.clone();
        }
    }

    /**
     * How to migrate.
     *
     * @param author     author written into every document
     * @param sprites    where texture images come from
     * @param atlas      the legacy atlas, or null
     * @param burstCount particles a burst effect's emitter fires (see {@link #defaultBurst})
     */
    public record Options(String author, SpriteSource sprites, Atlas atlas, ToIntFunction<ParticleEffect> burstCount) {
        public Options {
            author = author == null ? "" : author;
            sprites = sprites == null ? SpriteSource.NONE : sprites;
            burstCount = burstCount == null ? OmfxMigration::defaultBurst : burstCount;
        }

        /** No author, no images, no atlas, default bursts. */
        public static Options defaults() {
            return new Options("", SpriteSource.NONE, null, null);
        }

        public Options withSprites(SpriteSource source) {
            return new Options(author, source, atlas, burstCount);
        }

        public Options withAtlas(Atlas a) {
            return new Options(author, sprites, a, burstCount);
        }

        public Options withAuthor(String name) {
            return new Options(name, sprites, atlas, burstCount);
        }
    }

    /** The migrated documents (in the library's effect order) and what could not be carried over exactly. */
    public record Result(List<OmfxDocument> documents, List<String> warnings) {
        public Result {
            documents = List.copyOf(documents);
            warnings = List.copyOf(warnings);
        }

        /** The document whose id is {@code id}, or null. */
        public OmfxDocument document(String id) {
            for (OmfxDocument d : documents) {
                if (d.id().equals(id)) {
                    return d;
                }
            }
            return null;
        }
    }

    /**
     * The burst a migrated burst effect fires: 60 particles, or 1 when it has sub-emitters (a firework rocket) —
     * the Particle Editor preview's rule, so a migrated effect looks as it did there.
     */
    public static int defaultBurst(ParticleEffect effect) {
        return effect.subEmitters().isEmpty() ? 60 : 1;
    }

    /**
     * Texture names as the v1 Particle Editor stored them: image files relative to the library's folder
     * ({@code baseDir}, null = only absolute names) or absolute.
     */
    public static SpriteSource files(Path baseDir) {
        Path base = baseDir == null ? null : baseDir.toAbsolutePath().normalize();
        return name -> {
            Path p;
            try {
                p = Path.of(name);
            } catch (InvalidPathException e) {
                return null;
            }
            if (!p.isAbsolute()) {
                if (base == null) {
                    return null;
                }
                p = base.resolve(p);
            }
            p = p.normalize();
            return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
        };
    }

    /** Reads either version: a v2 archive as one document, a v1 library migrated. */
    public static Result load(byte[] bytes, Options options) throws IOException {
        if (OmfxCodec.isArchive(bytes)) {
            return new Result(List.of(OmfxCodec.read(bytes)), List.of());
        }
        return fromV1(bytes, options);
    }

    /** Migrates a v1 {@code openmason.particles} document. Malformed input fails with a reason. */
    public static Result fromV1(byte[] json, Options options) throws IOException {
        Objects.requireNonNull(options, "options");
        JsonNode root;
        try {
            root = OmfxCodec.MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IOException("not a particle library: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IOException("particle library must be a JSON object");
        }
        List<String> warnings = new ArrayList<>();
        Map<String, byte[]> atlasSprites = new HashMap<>();
        ObjectNode doc = ((ObjectNode) root).deepCopy();
        JsonNode effects = doc.get("effects");
        if (effects != null && effects.isArray()) {
            AtlasCutter cutter = options.atlas() == null ? null : new AtlasCutter(options.atlas());
            for (JsonNode n : effects) {
                if (n.isObject()) {
                    migrateAtlas((ObjectNode) n, cutter, atlasSprites, warnings);
                }
            }
        }

        ParticleLibrary library = new ParticleLibrary();
        library.load(doc);

        Map<String, OmfxSprite> spriteCache = new HashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        List<OmfxDocument> out = new ArrayList<>();
        for (ParticleEffect effect : library.effects()) {
            FxSystem system;
            try {
                system = FxSystem.of(effect, Math.max(0, options.burstCount().applyAsInt(effect)));
            } catch (IllegalArgumentException e) {
                throw new IOException("cannot migrate effect '" + effect.name() + "': " + e.getMessage(), e);
            }
            OmfxDocument d = new OmfxDocument(options.author(), system, List.of(), Map.of(), FormatExtensions.EMPTY,
                    Map.of());
            for (String name : d.referencedTextures()) {
                OmfxSprite sprite = sprite(name, atlasSprites, options.sprites(), spriteCache, missing, warnings);
                if (sprite != null) {
                    d = d.withSprite(sprite);
                }
            }
            out.add(d);
        }
        for (String name : missing) {
            warnings.add("texture '" + name + "' was not found; it stays a name for the host to resolve");
        }
        return new Result(out, warnings);
    }

    private static OmfxSprite sprite(String name, Map<String, byte[]> atlasSprites, SpriteSource source,
                                     Map<String, OmfxSprite> cache, Set<String> missing, List<String> warnings) {
        if (cache.containsKey(name)) {
            return cache.get(name);
        }
        OmfxSprite sprite = null;
        byte[] bytes = atlasSprites.get(name);
        try {
            if (bytes == null) {
                bytes = source.load(name);
            }
            if (bytes == null) {
                missing.add(name);
            } else {
                sprite = new OmfxSprite(name, bytes);
                sprite.check();
            }
        } catch (IOException | RuntimeException e) {
            warnings.add("texture '" + name + "' could not be embedded (" + e.getMessage()
                    + "); it stays a name for the host to resolve");
            sprite = null;
        }
        cache.put(name, sprite);
        return sprite;
    }

    /** Rewrites a pre-#97 atlas effect to texture sprites (or the soft disc without the atlas image). */
    private static void migrateAtlas(ObjectNode n, AtlasCutter cutter, Map<String, byte[]> sprites,
                                     List<String> warnings) throws IOException {
        JsonNode sprite = n.get("sprite");
        if (sprite == null || !sprite.isTextual() || !sprite.asText().trim().equalsIgnoreCase("atlas")) {
            return;
        }
        String name = n.path("name").asText("?");
        JsonNode frames = n.path("frames");
        int start = frames.path("start").asInt(0);
        int count = Math.max(1, frames.path("count").asInt(1));
        boolean animate = frames.path("animate").asBoolean(false);
        n.remove("frames");
        if (count > ParticleEffect.MAX_TEXTURES) {
            throw new IOException("effect '" + name + "': " + count + " atlas frames, at most "
                    + ParticleEffect.MAX_TEXTURES + " sprites per effect");
        }
        if (cutter == null) {
            n.put("sprite", "soft_disc");
            n.remove("textures");
            warnings.add("effect '" + name + "': atlas frames " + start + ".." + (start + count - 1)
                    + " dropped (no atlas image given); it now uses the soft disc");
            return;
        }
        ArrayNode textures = n.putArray("textures");
        for (int f = start; f < start + count; f++) {
            String spriteName = ATLAS_SPRITE_PREFIX + f;
            if (!sprites.containsKey(spriteName)) {
                sprites.put(spriteName, cutter.cell(name, f));
            }
            textures.add(spriteName);
        }
        n.put("sprite", "texture");
        n.put("animate", animate);
    }

    /** Cuts PNG cells out of a legacy atlas image. */
    private static final class AtlasCutter {
        private final Atlas atlas;
        private BufferedImage image;

        AtlasCutter(Atlas atlas) {
            this.atlas = atlas;
        }

        byte[] cell(String effect, int frame) throws IOException {
            if (image == null) {
                image = ImageIO.read(new ByteArrayInputStream(atlas.image()));
                if (image == null) {
                    throw new IOException("the atlas image is not a readable image");
                }
            }
            int cw = image.getWidth() / atlas.columns();
            int ch = image.getHeight() / atlas.rows();
            if (cw < 1 || ch < 1) {
                throw new IOException(String.format(Locale.ROOT, "the %dx%d atlas image is smaller than its %dx%d grid",
                        image.getWidth(), image.getHeight(), atlas.columns(), atlas.rows()));
            }
            if (frame < 0 || frame >= atlas.columns() * atlas.rows()) {
                throw new IOException("effect '" + effect + "': atlas frame " + frame + " is outside the "
                        + atlas.columns() + "x" + atlas.rows() + " grid");
            }
            BufferedImage cell = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
            int[] argb = image.getRGB((frame % atlas.columns()) * cw, (frame / atlas.columns()) * ch, cw, ch, null, 0, cw);
            cell.setRGB(0, 0, cw, ch, argb, 0, cw);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(cell, "png", png);
            return png.toByteArray();
        }
    }
}
