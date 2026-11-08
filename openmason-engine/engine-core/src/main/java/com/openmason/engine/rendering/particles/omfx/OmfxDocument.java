package com.openmason.engine.rendering.particles.omfx;

import com.openmason.engine.format.ext.FormatExtensions;
import com.openmason.engine.format.sound.SoundDef;
import com.openmason.engine.rendering.particles.FxEmitter;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleImage;
import com.openmason.engine.rendering.particles.ParticleSpriteLoader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * One {@code .omfx} v2 document (OM #101): the engine-owned, game-neutral source of an FX system — what the editor
 * opens and saves, and what game plugins export their shipped containers from (#102). Read and written by
 * {@link OmfxCodec}; v1 particle libraries become documents through {@link OmfxMigration}.
 *
 * <p>The document is self-contained: the system's sprites ({@link #sprites}) and embedded sound samples
 * ({@link #soundFiles}) travel inside it, and game data rides along untouched — namespaced manifest
 * {@link #extensions} and archive entries the format does not own ({@link #extraFiles}), both written back exactly.
 *
 * @param author     free-form author ("" when unknown)
 * @param system     the FX system; its name is the document id
 * @param sprites    embedded sprite images by texture name, in document order (names an effect lists but this
 *                   leaves out are the host's to resolve)
 * @param soundFiles embedded sound samples by archive path ({@code sounds/...}), exactly the files the system's
 *                   embedded {@link SoundDef}s name
 * @param extensions namespaced manifest extensions and foreign manifest fields (kept as their exact text)
 * @param extraFiles archive entries outside the format's own files, by path, written back unchanged
 */
public record OmfxDocument(String author, FxSystem system, List<OmfxSprite> sprites, Map<String, byte[]> soundFiles,
                           FormatExtensions extensions, Map<String, byte[]> extraFiles) {

    public OmfxDocument {
        author = author == null ? "" : author;
        Objects.requireNonNull(system, "system");
        sprites = List.copyOf(sprites == null ? List.of() : sprites);
        soundFiles = copy(soundFiles);
        extensions = extensions == null ? FormatExtensions.EMPTY : extensions;
        extraFiles = copy(extraFiles);
        Set<String> spriteNames = new HashSet<>();
        for (OmfxSprite s : sprites) {
            if (!spriteNames.add(s.name())) {
                throw new IllegalArgumentException(system.name() + ": sprite '" + s.name() + "' embedded twice");
            }
        }
        Set<String> embeddedSounds = new HashSet<>();
        for (SoundDef d : system.sounds().sounds()) {
            if (d.isEmbedded()) {
                if (!OmfxCodec.isSoundPath(d.filename())) {
                    throw new IllegalArgumentException(system.name() + ": embedded sound '" + d.filename()
                            + "' must live under " + OmfxCodec.SOUNDS_DIR);
                }
                if (!soundFiles.containsKey(d.filename())) {
                    throw new IllegalArgumentException(system.name() + ": sound '" + d.event() + "' embeds '"
                            + d.filename() + "' but the document has no such file");
                }
                embeddedSounds.add(d.filename());
            }
        }
        for (String path : soundFiles.keySet()) {
            if (!embeddedSounds.contains(path)) {
                throw new IllegalArgumentException(system.name() + ": sound file '" + path
                        + "' is not used by any sound definition");
            }
        }
        for (String path : extraFiles.keySet()) {
            OmfxCodec.checkEntryName(path);
            if (OmfxCodec.isReserved(path)) {
                throw new IllegalArgumentException(system.name() + ": extra file '" + path
                        + "' is in the format's own space (manifest, system, graphs/, textures/, sounds/)");
            }
        }
    }

    /** A document holding just {@code system}: no author, sprites, embedded sounds or game data. */
    public static OmfxDocument of(FxSystem system) {
        return new OmfxDocument("", system, List.of(), Map.of(), FormatExtensions.EMPTY, Map.of());
    }

    /** The document id: the system's name. */
    public String id() {
        return system.name();
    }

    /** The embedded sprite named {@code name}, or null. */
    public OmfxSprite sprite(String name) {
        for (OmfxSprite s : sprites) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }

    /** Embedded sound bytes at {@code path} (a copy), or null. */
    public byte[] soundFile(String path) {
        byte[] b = soundFiles.get(path);
        return b == null ? null : b.clone();
    }

    /**
     * Every effect the system plays — emitter effects and, depth first, the effects their sub-emitters spawn — each
     * once, sub-emitter targets before the effects that use them. Throws when two different effects share a name
     * (a document stores effects by name).
     */
    public List<ParticleEffect> effects() {
        Map<String, ParticleEffect> out = new LinkedHashMap<>();
        for (FxEmitter e : system.emitters()) {
            collect(e.effect(), out);
        }
        return List.copyOf(out.values());
    }

    private void collect(ParticleEffect effect, Map<String, ParticleEffect> out) {
        ParticleEffect seen = out.get(effect.name());
        if (seen != null) {
            if (!seen.equals(effect)) {
                throw new IllegalArgumentException(system.name() + ": two different effects are named '"
                        + effect.name() + "'");
            }
            return;
        }
        for (ParticleEffect.SubEmitter s : effect.subEmitters()) {
            collect(s.effect(), out);
        }
        out.put(effect.name(), effect);
    }

    /** Texture names the system's effects list, in first-use order. */
    public Set<String> referencedTextures() {
        Set<String> names = new LinkedHashSet<>();
        for (ParticleEffect e : effects()) {
            names.addAll(e.textures());
        }
        return names;
    }

    /** Texture names the effects list that this document does not embed (left to the host's loader). */
    public Set<String> externalTextures() {
        Set<String> names = new LinkedHashSet<>(referencedTextures());
        for (OmfxSprite s : sprites) {
            names.remove(s.name());
        }
        return names;
    }

    /**
     * A sprite loader for {@link com.openmason.engine.rendering.particles.ParticleSpriteLayout}: embedded sprites
     * decode from the document, every other name goes to {@code fallback} (null = unresolved: the missing checker).
     */
    public ParticleSpriteLoader spriteLoader(ParticleSpriteLoader fallback) {
        return name -> {
            OmfxSprite s = sprite(name);
            if (s != null) {
                return s.decode();
            }
            return fallback == null ? null : fallback.load(name);
        };
    }

    /** With {@code system} in place of the current one (sprites, sounds and game data kept). */
    public OmfxDocument withSystem(FxSystem next) {
        return new OmfxDocument(author, next, sprites, soundFiles, extensions, extraFiles);
    }

    /** With {@code sprite} embedded (replacing one of the same name in place, else appended). */
    public OmfxDocument withSprite(OmfxSprite sprite) {
        List<OmfxSprite> next = new ArrayList<>(sprites);
        int at = -1;
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).name().equals(sprite.name())) {
                at = i;
            }
        }
        if (at >= 0) {
            next.set(at, sprite);
        } else {
            next.add(sprite);
        }
        return new OmfxDocument(author, system, next, soundFiles, extensions, extraFiles);
    }

    /** Without the embedded sprite {@code name} (the name, if effects list it, becomes the host's to resolve). */
    public OmfxDocument withoutSprite(String name) {
        List<OmfxSprite> next = new ArrayList<>(sprites);
        next.removeIf(s -> s.name().equals(name));
        return next.size() == sprites.size() ? this
                : new OmfxDocument(author, system, next, soundFiles, extensions, extraFiles);
    }

    /** With {@code next} as the manifest's game data (extensions + foreign fields), e.g. a plugin section's edit. */
    public OmfxDocument withExtensions(FormatExtensions next) {
        return new OmfxDocument(author, system, sprites, soundFiles, next, extraFiles);
    }

    /** With {@code name} as the author. */
    public OmfxDocument withAuthor(String name) {
        return new OmfxDocument(name, system, sprites, soundFiles, extensions, extraFiles);
    }

    /** Decodes the embedded sprite {@code name}, or null when it is not embedded. */
    public ParticleImage decodeSprite(String name) throws IOException {
        OmfxSprite s = sprite(name);
        return s == null ? null : s.decode();
    }

    private static Map<String, byte[]> copy(Map<String, byte[]> in) {
        TreeMap<String, byte[]> out = new TreeMap<>();
        if (in != null) {
            in.forEach((k, v) -> out.put(Objects.requireNonNull(k, "path"), Objects.requireNonNull(v, k).clone()));
        }
        return Collections.unmodifiableMap(out);
    }

    private static boolean sameBytes(Map<String, byte[]> a, Map<String, byte[]> b) {
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        for (Map.Entry<String, byte[]> e : a.entrySet()) {
            if (!Arrays.equals(e.getValue(), b.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static int bytesHash(Map<String, byte[]> m) {
        int h = 0;
        for (Map.Entry<String, byte[]> e : m.entrySet()) {
            h += e.getKey().hashCode() ^ Arrays.hashCode(e.getValue());
        }
        return h;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof OmfxDocument d
                && author.equals(d.author) && system.equals(d.system) && sprites.equals(d.sprites)
                && sameBytes(soundFiles, d.soundFiles) && extensions.equals(d.extensions)
                && sameBytes(extraFiles, d.extraFiles);
    }

    @Override
    public int hashCode() {
        return Objects.hash(author, system, sprites, bytesHash(soundFiles), extensions, bytesHash(extraFiles));
    }

    @Override
    public String toString() {
        return "OmfxDocument[" + id() + ", " + system.emitters().size() + " emitter(s), " + sprites.size()
                + " sprite(s), " + soundFiles.size() + " sound file(s), " + extraFiles.size() + " extra file(s)]";
    }
}
