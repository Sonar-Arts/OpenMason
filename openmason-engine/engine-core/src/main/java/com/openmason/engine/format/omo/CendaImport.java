package com.openmason.engine.format.omo;

import com.openmason.engine.cenda.CendaKernels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * FFM binding of the native model importer ({@code co_import_*} in {@code libcenda_kernels}; contract and every
 * conversion rule in {@code cenda/native/kernels/include/cenda/model_import.h}, OpenMason #173): glTF 2.0
 * ({@code .gltf} / {@code .glb}) and Wavefront OBJ (+ MTL) become an OMO 1.9 archive plus one OMANIM 1.2 clip per
 * glTF animation. Parsing and conversion are native; this class marshals (one call per import) and copies the
 * result out. Part of the {@code co_*} ABI, available exactly when {@link CendaOmo} is.
 */
public final class CendaImport {

    private static final Logger LOGGER = LoggerFactory.getLogger(CendaImport.class);

    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;
    private static final AddressLayout ADDR = ValueLayout.ADDRESS;

    /** Extensions the importer reads (lowercase, with the dot). */
    public static final List<String> EXTENSIONS = List.of(".glb", ".gltf", ".obj");

    // co_import_options (24 bytes) / co_import_counts (48 bytes).
    private static final long OPTIONS_BYTES = 24;
    private static final long COUNTS_BYTES = 48;

    // Flags.
    private static final int MERGE_QUADS = 1;
    private static final int SPLIT_PARTS = 1 << 1;
    private static final int ANIMATIONS = 1 << 2;
    private static final int MORPHS = 1 << 3;
    private static final int VERTEX_GROUPS = 1 << 4;
    private static final int KEEP_ALL_KEYS = 1 << 5;

    private static final String FAILURE;
    private static final MethodHandle IMPORT_FILE;
    private static final MethodHandle IMPORT_BYTES;
    private static final MethodHandle RESULT_DESTROY;
    private static final MethodHandle RESULT_DOC;
    private static final MethodHandle CLIP_COUNT;
    private static final MethodHandle CLIP;
    private static final MethodHandle WARNING_COUNT;
    private static final MethodHandle WARNING;
    private static final MethodHandle COUNTS;

    static {
        String failure = null;
        SymbolLookup lookup = null;
        if (!CendaOmo.isAvailable()) {
            failure = CendaOmo.unavailableReason();
        } else {
            try {
                Path lib = CendaKernels.libraryPath().orElseThrow();
                lookup = SymbolLookup.libraryLookup(lib, Arena.global());
                if (lookup.find("co_import_file").isEmpty()) {
                    failure = "library " + lib + " has no model importer (co_import_file missing); rebuild it";
                    lookup = null;
                }
            } catch (Throwable t) {
                failure = "Cenda model importer failed to load: " + t;
                lookup = null;
            }
        }
        if (failure != null) {
            LOGGER.error("Model import unavailable: {}", failure);
        }
        FAILURE = failure;
        IMPORT_FILE = handle(lookup, "co_import_file", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        IMPORT_BYTES = handle(lookup, "co_import_bytes",
                FunctionDescriptor.of(I32, ADDR, I64, I32, ADDR, ADDR, ADDR));
        RESULT_DESTROY = handle(lookup, "co_import_result_destroy", FunctionDescriptor.ofVoid(ADDR));
        RESULT_DOC = handle(lookup, "co_import_result_doc", FunctionDescriptor.of(ADDR, ADDR));
        CLIP_COUNT = handle(lookup, "co_import_result_clip_count", FunctionDescriptor.of(I32, ADDR));
        CLIP = handle(lookup, "co_import_result_clip", FunctionDescriptor.of(I32, ADDR, I32, ADDR, ADDR, ADDR));
        WARNING_COUNT = handle(lookup, "co_import_result_warning_count", FunctionDescriptor.of(I32, ADDR));
        WARNING = handle(lookup, "co_import_result_warning", FunctionDescriptor.of(ADDR, ADDR, I32));
        COUNTS = handle(lookup, "co_import_result_counts", FunctionDescriptor.of(I32, ADDR, ADDR));
    }

    private CendaImport() {
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor fd) {
        if (lookup == null) return null;
        return lookup.find(name)
                .map(symbol -> Linker.nativeLinker().downcallHandle(symbol, fd))
                .orElseThrow(() -> new IllegalStateException("Cenda import symbol missing: " + name));
    }

    /** True when the native importer is usable. */
    public static boolean isAvailable() {
        return FAILURE == null;
    }

    /** Why the native importer is unavailable, or null when it is available. */
    public static String unavailableReason() {
        return FAILURE;
    }

    /** True when {@code fileName} has an extension the importer reads. */
    public static boolean canImport(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    /** Source formats of {@link #importBytes}. */
    public enum Format {
        /** glTF binary by its magic, JSON by a leading '{', else OBJ. */
        AUTO(0),
        GLTF(1),
        OBJ(2);

        final int code;

        Format(int code) {
            this.code = code;
        }
    }

    /**
     * Import options ({@code co_import_options}).
     *
     * @param unitScale       source units to Open Mason units ({@code > 0})
     * @param keepAxis        true = keep the axes as authored; false = turn +Z-forward sources to Open Mason's -Z
     * @param mergeQuads      pair coplanar triangles into quad faces
     * @param splitParts      one part per mesh node (else one merged part)
     * @param animations      glTF animations to OMANIM clips
     * @param morphs          morph targets to OMO morphs
     * @param vertexGroups    '_' attributes to OMO vertex groups
     * @param keepAllKeys     skip the animation key reduction (diagnostics)
     * @param maxTextureSize  0 = unlimited; larger textures are downscaled to fit
     * @param quadAngleDegrees coplanarity tolerance of a quad merge
     */
    public record Options(float unitScale, boolean keepAxis, boolean mergeQuads, boolean splitParts,
                          boolean animations, boolean morphs, boolean vertexGroups, boolean keepAllKeys,
                          int maxTextureSize, float quadAngleDegrees) {

        /** {@code co_import_options_default}: scale 1, Open Mason axis, every feature but keepAllKeys, no limit. */
        public static Options defaults() {
            return new Options(1f, false, true, true, true, true, true, false, 0, 1f);
        }

        public Options withUnitScale(float scale) {
            return new Options(scale, keepAxis, mergeQuads, splitParts, animations, morphs, vertexGroups,
                    keepAllKeys, maxTextureSize, quadAngleDegrees);
        }

        public Options withMaxTextureSize(int size) {
            return new Options(unitScale, keepAxis, mergeQuads, splitParts, animations, morphs, vertexGroups,
                    keepAllKeys, size, quadAngleDegrees);
        }

        int flags() {
            int f = 0;
            if (mergeQuads) f |= MERGE_QUADS;
            if (splitParts) f |= SPLIT_PARTS;
            if (animations) f |= ANIMATIONS;
            if (morphs) f |= MORPHS;
            if (vertexGroups) f |= VERTEX_GROUPS;
            if (keepAllKeys) f |= KEEP_ALL_KEYS;
            return f;
        }
    }

    /** What an import produced ({@code co_import_counts}). */
    public record Counts(int parts, int bones, int faces, int triangles, int vertices, int materials,
                         int maxInfluences, int morphs, int vertexGroups, int clips, int warnings) {
    }

    /** One converted glTF animation: its (unique) name and {@code .omanim} archive bytes. */
    public record Clip(String name, byte[] omanim) {
        public Clip {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(omanim, "omanim");
        }
    }

    /**
     * An import result, copied out of native memory.
     *
     * @param omo      the {@code .omo} archive (OMO 1.9)
     * @param clips    one clip per glTF animation (empty for OBJ or without {@link Options#animations})
     * @param warnings the reader's then the conversion's warnings
     * @param counts   what was produced
     */
    public record Result(byte[] omo, List<Clip> clips, List<String> warnings, Counts counts) {
        public Result {
            clips = List.copyOf(clips);
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * Imports a file; the format comes from its extension, external references resolve inside its folder.
     *
     * @throws IOException with the native reason (unreadable file, unknown extension, malformed model)
     */
    public static Result importFile(Path file, Options options) throws IOException {
        requireAvailable();
        Objects.requireNonNull(file, "file");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDR);
            int rc = (int) IMPORT_FILE.invokeExact(arena.allocateFrom(file.toAbsolutePath().toString()),
                    optionsStruct(arena, options), out);
            return take(rc, out.get(ADDR, 0), "import " + file.getFileName());
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    /**
     * Imports bytes.
     *
     * @param baseDir the folder external references (buffers, images, mtllib) resolve in, or null for none
     */
    public static Result importBytes(byte[] bytes, Format format, Path baseDir, Options options) throws IOException {
        requireAvailable();
        Objects.requireNonNull(bytes, "bytes");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(Math.max(1, bytes.length));
            MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, bytes.length);
            MemorySegment dir = baseDir == null ? MemorySegment.NULL
                    : arena.allocateFrom(baseDir.toAbsolutePath().toString());
            MemorySegment out = arena.allocate(ADDR);
            int rc = (int) IMPORT_BYTES.invokeExact(data, (long) bytes.length,
                    (format == null ? Format.AUTO : format).code, dir, optionsStruct(arena, options), out);
            return take(rc, out.get(ADDR, 0), "import");
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaOmo.rethrow(t);
        }
    }

    private static Result take(int rc, MemorySegment result, String what) throws Throwable {
        if (rc != CendaOmo.OK || result.equals(MemorySegment.NULL)) {
            throw new IOException(what + " failed: " + CendaOmo.lastError());
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment doc = (MemorySegment) RESULT_DOC.invokeExact(result);
            if (doc.equals(MemorySegment.NULL)) throw new IOException(what + ": the result holds no document");
            byte[] omo = OmoDocument.takeBytes((MemorySegment) CendaOmo.DOC_WRITE.invokeExact(doc), "co_doc_write");

            List<Clip> clips = new ArrayList<>();
            int clipCount = (int) CLIP_COUNT.invokeExact(result);
            MemorySegment name = arena.allocate(ADDR);
            MemorySegment data = arena.allocate(ADDR);
            MemorySegment size = arena.allocate(I64);
            for (int i = 0; i < clipCount; i++) {
                int c = (int) CLIP.invokeExact(result, i, name, data, size);
                if (c != CendaOmo.OK) continue;
                long n = size.get(I64, 0);
                byte[] bytes = n <= 0 ? new byte[0]
                        : data.get(ADDR, 0).reinterpret(n).toArray(ValueLayout.JAVA_BYTE);
                String clipName = CendaOmo.string(name.get(ADDR, 0));
                clips.add(new Clip(clipName == null ? "Clip " + (i + 1) : clipName, bytes));
            }

            List<String> warnings = new ArrayList<>();
            int warningCount = (int) WARNING_COUNT.invokeExact(result);
            for (int i = 0; i < warningCount; i++) {
                String w = CendaOmo.string((MemorySegment) WARNING.invokeExact(result, i));
                if (w != null) warnings.add(w);
            }

            MemorySegment c = arena.allocate(COUNTS_BYTES, 4);
            int crc = (int) COUNTS.invokeExact(result, c);
            Counts counts = crc != CendaOmo.OK ? new Counts(0, 0, 0, 0, 0, 0, 0, 0, 0, clips.size(), warnings.size())
                    : new Counts(c.get(I32, 0), c.get(I32, 4), c.get(I32, 8), c.get(I32, 12), c.get(I32, 16),
                            c.get(I32, 20), c.get(I32, 24), c.get(I32, 28), c.get(I32, 32), c.get(I32, 36),
                            c.get(I32, 40));
            return new Result(omo, clips, warnings, counts);
        } finally {
            RESULT_DESTROY.invokeExact(result);
        }
    }

    private static MemorySegment optionsStruct(Arena arena, Options options) {
        Options o = options == null ? Options.defaults() : options;
        MemorySegment s = arena.allocate(OPTIONS_BYTES, 4);
        s.set(F32, 0, o.unitScale());
        s.set(I32, 4, o.keepAxis() ? 1 : 0);
        s.set(I32, 8, o.flags());
        s.set(I32, 12, Math.max(0, o.maxTextureSize()));
        s.set(F32, 16, o.quadAngleDegrees());
        s.set(I32, 20, 0);
        return s;
    }

    private static void requireAvailable() throws IOException {
        if (FAILURE != null) throw new IOException("Cenda model importer unavailable: " + FAILURE);
    }
}
