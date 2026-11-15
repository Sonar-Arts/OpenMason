package com.openmason.engine.format.spi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * A whole file format contributed to the engine (#88): its id, extensions, an optional magic
 * sniff and a reader producing its parsed model. The engine itself only ships generic formats;
 * a game registers its own containers through this service (module {@code provides}, a
 * {@code META-INF/services} entry, or {@link Formats#register(FormatCodec)} from an editor plugin
 * whose module layer {@link java.util.ServiceLoader} does not see).
 *
 * <p>Writing is format specific (most game containers are exported from an OMO/OMT plus
 * parameters), so it is not part of this contract.
 *
 * @param <T> the parsed model {@link #read} returns
 */
public interface FormatCodec<T> {

    /** Stable lowercase id ({@code "sbo"}); unique among registered codecs. */
    String id();

    /** Human name ("Stonebreak Object"). */
    String displayName();

    /** Lowercase extensions with the leading dot, primary first ({@code ".sbo"}). */
    List<String> extensions();

    /** The type {@link #read} returns. */
    Class<T> modelType();

    /** Parses {@code file}. */
    T read(Path file) throws IOException;

    /**
     * True when {@code head} (the first bytes of a file, at least 16 when the file has them)
     * is certainly this format. Containers that share a generic signature (ZIP) answer false
     * and are matched by extension only.
     */
    default boolean sniff(byte[] head) {
        return false;
    }

    /** True when {@code fileName} ends with one of {@link #extensions()} (case-insensitive). */
    default boolean matches(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : extensions()) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** The primary extension, with its dot. */
    default String extension() {
        return extensions().get(0);
    }
}
