package com.openmason.engine.format.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * The file formats and texture containers of this process (#88): every {@link FormatCodec} and
 * {@link TextureDecoder} on the module/class path (found once, lazily, through
 * {@link ServiceLoader}) plus every one a host {@link #register registered} (an Open Mason
 * plugin's module layer is invisible to {@link ServiceLoader}). One entry per id: a discovered
 * entry wins over a later registration with the same id, which is logged and ignored.
 *
 * <p>The engine registers no game format of its own; generic formats (OMO, OMT, OMANIM, OMSC)
 * are read through their own classes and are not listed here.
 */
public final class Formats {

    private static final Logger logger = LoggerFactory.getLogger(Formats.class);

    private static final Map<String, FormatCodec<?>> registeredCodecs = new LinkedHashMap<>();
    private static final Map<String, TextureDecoder> registeredDecoders = new LinkedHashMap<>();
    private static volatile List<FormatCodec<?>> codecs;
    private static volatile List<TextureDecoder> decoders;

    private Formats() {
    }

    /** Every format codec, discovered ones first, in a stable order. */
    public static List<FormatCodec<?>> codecs() {
        List<FormatCodec<?>> c = codecs;
        if (c == null) {
            synchronized (Formats.class) {
                c = codecs;
                if (c == null) {
                    Map<String, FormatCodec<?>> all = new LinkedHashMap<>();
                    for (FormatCodec<?> codec : ServiceLoader.load(FormatCodec.class)) {
                        putUnique(all, codec.id(), codec, "format codec");
                    }
                    registeredCodecs.forEach((id, codec) -> putUnique(all, id, codec, "format codec"));
                    codecs = c = List.copyOf(all.values());
                }
            }
        }
        return c;
    }

    /** Every texture decoder, discovered ones first, in a stable order. */
    public static List<TextureDecoder> textureDecoders() {
        List<TextureDecoder> d = decoders;
        if (d == null) {
            synchronized (Formats.class) {
                d = decoders;
                if (d == null) {
                    Map<String, TextureDecoder> all = new LinkedHashMap<>();
                    for (TextureDecoder decoder : ServiceLoader.load(TextureDecoder.class)) {
                        putUnique(all, decoder.id(), decoder, "texture decoder");
                    }
                    registeredDecoders.forEach((id, decoder) -> putUnique(all, id, decoder, "texture decoder"));
                    decoders = d = List.copyOf(all.values());
                }
            }
        }
        return d;
    }

    /** Adds {@code codec}; idempotent per id. */
    public static synchronized void register(FormatCodec<?> codec) {
        if (registeredCodecs.putIfAbsent(codec.id(), codec) == null) {
            codecs = null;
        }
    }

    /** Adds {@code decoder}; idempotent per id. */
    public static synchronized void register(TextureDecoder decoder) {
        if (registeredDecoders.putIfAbsent(decoder.id(), decoder) == null) {
            decoders = null;
        }
    }

    /** The codec with {@code id}. */
    public static Optional<FormatCodec<?>> codec(String id) {
        for (FormatCodec<?> c : codecs()) {
            if (c.id().equals(id)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /** The codec whose extension {@code fileName} has (the longest match wins). */
    public static Optional<FormatCodec<?>> forFileName(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        FormatCodec<?> best = null;
        int bestLength = -1;
        for (FormatCodec<?> c : codecs()) {
            for (String ext : c.extensions()) {
                if (lower.endsWith(ext) && ext.length() > bestLength) {
                    best = c;
                    bestLength = ext.length();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** {@link #forFileName} of {@code path}'s file name. */
    public static Optional<FormatCodec<?>> forPath(Path path) {
        return path == null || path.getFileName() == null ? Optional.empty()
                : forFileName(path.getFileName().toString());
    }

    /** The first codec whose {@link FormatCodec#sniff} recognises {@code head}. */
    public static Optional<FormatCodec<?>> sniff(byte[] head) {
        if (head == null || head.length == 0) {
            return Optional.empty();
        }
        for (FormatCodec<?> c : codecs()) {
            if (c.sniff(head)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /**
     * The OMT bytes a registered texture container wraps in {@code data}, trying every decoder in
     * order, or null when none recognises it (bare OMT and PNG included).
     */
    public static byte[] unwrapTexture(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        for (TextureDecoder d : textureDecoders()) {
            try {
                byte[] omt = d.unwrapOmt(data);
                if (omt != null) {
                    return omt;
                }
            } catch (Exception notThisContainer) {
                // a foreign container: try the next decoder
            }
        }
        return null;
    }

    /** The texture container whose extension {@code fileName} has (case-insensitive). */
    public static Optional<TextureDecoder> textureDecoderFor(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        for (TextureDecoder d : textureDecoders()) {
            for (String ext : d.extensions()) {
                if (lower.endsWith(ext)) {
                    return Optional.of(d);
                }
            }
        }
        return Optional.empty();
    }

    /** Every extension a registered texture container uses. */
    public static List<String> textureContainerExtensions() {
        List<String> out = new ArrayList<>();
        for (TextureDecoder d : textureDecoders()) {
            out.addAll(d.extensions());
        }
        return List.copyOf(out);
    }

    private static <T> void putUnique(Map<String, T> into, String id, T item, String kind) {
        T existing = into.putIfAbsent(id, item);
        if (existing != null && existing != item && !existing.getClass().equals(item.getClass())) {
            logger.warn("Ignoring {} {} from {}: id already taken by {}", kind, id,
                    item.getClass().getName(), existing.getClass().getName());
        }
    }
}
