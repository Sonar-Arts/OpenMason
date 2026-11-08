package com.openmason.engine.rendering.particles.omfx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.format.ext.FormatExtensions;
import com.openmason.engine.format.ext.ManifestJson;
import com.openmason.engine.format.sound.SoundDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Reads and writes {@code .omfx} v2 (OM #101), the engine-owned, game-neutral source format for FX systems.
 *
 * <h2>Container</h2>
 * A zip archive, like OMO/OMT:
 * <pre>
 * manifest.json   { "format": "openmason.fx", "version": 2, "id": "campfire", "author": "...",
 *                   "extensions": { "&lt;namespace&gt;:&lt;name&gt;": { ...game data, kept byte for byte... } } }
 * system.json     the FX system: effects, emitters (timeline), exposed parameters, bindings, events, light,
 *                 sound definitions + cues, and the sprite table (see below)
 * graphs/*.json   the FX graph IR ({@code openmason.fxgraph}) of each effect that has custom modules
 * textures/*      embedded sprites, PNG or OMT
 * sounds/*        embedded samples of the shared {@code format.sound} definitions
 * (anything else) game data entries, written back unchanged
 * </pre>
 *
 * <h2>system.json</h2>
 * <pre>{@code
 * { "effects":    [ { ...an openmason.particles v1 effect..., "graph": "graphs/fire.json" } ],
 *   "emitters":   [ { "name": "fire", "effect": "fire", "offset": [0, 0, 0], "delay": 0,
 *                     "duration": "forever", "loops": 1, "interval": 0, "burst": 0, "rateScale": 1,
 *                     "sizeScale": 1, "speedScale": 1, "tint": "#FFFFFFFF", "direction": null } ],
 *   "parameters": [ { "name": "intensity", "type": "float", "default": 1 },
 *                   { "name": "wind", "type": "vector", "default": [1, 0, 0] },
 *                   { "name": "tint", "type": "color", "default": [1, 1, 1, 1] } ],
 *   "bindings":   [ { "parameter": "intensity", "target": "rate", "emitter": null, "scale": 1, "bias": 0 } ],
 *   "events":     [ { "trigger": "collision", "source": "debris", "spawnInto": "dust", "count": 2,
 *                     "inherit": 0.1, "chance": 1, "forward": null, "sound": null } ],
 *   "light":      { "color": "#FF9A48FF", "intensity": 1.4, "radius": 9, "keys": [[0, 1]], "loopKeys": false,
 *                   "flicker": 0.3, "flickerHz": 7, "offset": [0, 0.4, 0] },
 *   "sounds":     [ ...the shared sounds[] wire shape (event, file|resource, checksum, volume, pitch)... ],
 *   "cues":       [ { "trigger": "play", "emitter": null, "sound": "fire-ignite", "volume": 1 } ],
 *   "sprites":    [ { "name": "smoke_0", "file": "textures/smoke_0.png" } ] }
 * }</pre>
 * Effects are stored once by name; emitters and sub-emitters reference them. A texture name an effect lists that
 * the sprite table leaves out is the host's to resolve. Every member is written, so a write/read round trip
 * reproduces the document exactly; writing is deterministic (fixed entry order and timestamps).
 *
 * <p>Malformed documents fail with an {@link IOException} saying what and where. Version 1 {@code .omfx} files
 * (plain {@code openmason.particles} JSON) are read by {@link OmfxMigration}.
 */
public final class OmfxCodec {

    private static final Logger logger = LoggerFactory.getLogger(OmfxCodec.class);

    /** The manifest's {@code format}. */
    public static final String FORMAT = "openmason.fx";
    /** The version this engine writes and reads. */
    public static final int VERSION = 2;
    public static final String FILE_EXTENSION = ".omfx";

    public static final String MANIFEST = "manifest.json";
    public static final String SYSTEM = "system.json";
    public static final String GRAPHS_DIR = "graphs/";
    public static final String TEXTURES_DIR = "textures/";
    public static final String SOUNDS_DIR = "sounds/";

    /** Largest single entry read (a sprite, a sample). */
    static final long MAX_ENTRY_BYTES = 64L << 20;
    /** Largest total of all entries read. */
    static final long MAX_TOTAL_BYTES = 256L << 20;
    /** Most entries read. */
    static final int MAX_ENTRIES = 4096;

    /** Manifest members the format owns; everything else is foreign data kept for its owner. */
    static final Set<String> CORE_FIELDS = Set.of("format", "version", "id", "author", ManifestJson.EXTENSIONS_FIELD);

    static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Every written entry gets this timestamp, so the same document always writes the same bytes. */
    private static final long ENTRY_TIME = 315_532_800_000L; // 1980-01-01T00:00:00Z, the zip epoch

    private OmfxCodec() {
    }

    // ------------------------------------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------------------------------------

    /** True when {@code bytes} start like a zip archive (a v2 document; v1 is JSON text). */
    public static boolean isArchive(byte[] bytes) {
        return bytes != null && bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3
                && bytes[3] == 4;
    }

    public static OmfxDocument read(Path file) throws IOException {
        return read(Files.readAllBytes(file));
    }

    public static OmfxDocument read(byte[] bytes) throws IOException {
        if (!isArchive(bytes)) {
            throw new IOException("not an .omfx v2 archive (v1 particle libraries are JSON: use OmfxMigration)");
        }
        return read(new ByteArrayInputStream(bytes));
    }

    /** Reads a v2 document; the stream is read to its end but not closed. */
    public static OmfxDocument read(InputStream in) throws IOException {
        Map<String, byte[]> entries = readEntries(in);
        byte[] manifestBytes = entries.remove(MANIFEST);
        if (manifestBytes == null) {
            throw new IOException("not an .omfx v2 document: " + MANIFEST + " is missing");
        }
        String manifestText = new String(manifestBytes, StandardCharsets.UTF_8);
        JsonNode manifest = parseJson(manifestText, MANIFEST);
        String format = manifest.path("format").asText("");
        if (!FORMAT.equals(format)) {
            throw new IOException(MANIFEST + ": format must be '" + FORMAT + "', got '" + format + "'");
        }
        JsonNode version = manifest.get("version");
        if (version == null || !version.isInt()) {
            throw new IOException(MANIFEST + ": 'version' must be an integer");
        }
        if (version.intValue() > VERSION) {
            throw new IOException(".omfx version " + version.intValue() + " is newer than supported " + VERSION);
        }
        if (version.intValue() < VERSION) {
            throw new IOException(".omfx version " + version.intValue() + " is not an archive version (v1 is JSON)");
        }
        JsonNode id = manifest.get("id");
        if (id == null || !id.isTextual() || id.asText().isBlank()) {
            throw new IOException(MANIFEST + ": 'id' must be a non-blank string");
        }
        JsonNode author = manifest.get("author");
        if (author != null && !author.isNull() && !author.isTextual()) {
            throw new IOException(MANIFEST + ": 'author' must be a string");
        }
        FormatExtensions extensions;
        try {
            extensions = ManifestJson.readExtensions(manifestText, CORE_FIELDS);
        } catch (RuntimeException e) {
            throw new IOException(MANIFEST + ": unreadable extensions: " + e.getMessage(), e);
        }

        byte[] systemBytes = entries.remove(SYSTEM);
        if (systemBytes == null) {
            throw new IOException("not an .omfx v2 document: " + SYSTEM + " is missing");
        }
        JsonNode system = parseJson(new String(systemBytes, StandardCharsets.UTF_8), SYSTEM);
        OmfxSystemJson.Parsed parsed = OmfxSystemJson.read(id.asText(), system, entries);

        Map<String, byte[]> extra = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (isReserved(e.getKey())) {
                if (!parsed.usedEntries().contains(e.getKey())) {
                    logger.debug("Dropping unreferenced .omfx entry {}", e.getKey());
                }
            } else {
                extra.put(e.getKey(), e.getValue());
            }
        }
        try {
            return new OmfxDocument(author == null || author.isNull() ? "" : author.asText(), parsed.system(),
                    parsed.sprites(), parsed.soundFiles(), extensions, extra);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid .omfx document: " + e.getMessage(), e);
        }
    }

    private static Map<String, byte[]> readEntries(InputStream in) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        try {
            ZipInputStream zip = new ZipInputStream(in);
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                try {
                    checkEntryName(name);
                } catch (IllegalArgumentException e) {
                    throw new IOException(e.getMessage(), e);
                }
                if (entries.size() >= MAX_ENTRIES) {
                    throw new IOException(".omfx has more than " + MAX_ENTRIES + " entries");
                }
                byte[] data = readLimited(zip, name, MAX_TOTAL_BYTES - total);
                total += data.length;
                if (entries.put(name, data) != null) {
                    throw new IOException(".omfx entry '" + name + "' appears twice");
                }
            }
        } catch (ZipException | java.io.EOFException e) {
            throw new IOException("damaged .omfx archive: " + e.getMessage(), e);
        }
        return entries;
    }

    private static byte[] readLimited(ZipInputStream zip, String name, long remaining) throws IOException {
        long limit = Math.min(MAX_ENTRY_BYTES, remaining);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long size = 0;
        int n;
        while ((n = zip.read(buf)) != -1) {
            size += n;
            if (size > limit) {
                throw new IOException(".omfx entry '" + name + "' is too large (limit " + MAX_ENTRY_BYTES
                        + " bytes per entry, " + MAX_TOTAL_BYTES + " in all)");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static JsonNode parseJson(String text, String what) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IOException(what + ": not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IOException(what + " must be a JSON object");
        }
        return root;
    }

    // ------------------------------------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------------------------------------

    /** The document as archive bytes. */
    public static byte[] write(OmfxDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(doc, out);
        return out.toByteArray();
    }

    /** Writes the document to {@code file} through a sibling temp file, so a failed write leaves the old file. */
    public static void write(OmfxDocument doc, Path file) throws IOException {
        byte[] bytes = write(doc);
        Path abs = file.toAbsolutePath();
        Path dir = abs.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = Files.createTempFile(dir, abs.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Writes the document as a zip archive; the stream is finished but not closed. */
    public static void write(OmfxDocument doc, OutputStream out) throws IOException {
        OmfxSystemJson.Written system;
        try {
            system = OmfxSystemJson.write(doc);
        } catch (IllegalArgumentException e) {
            throw new IOException("cannot write " + doc.id() + ": " + e.getMessage(), e);
        }
        ObjectNode core = MAPPER.createObjectNode();
        core.put("format", FORMAT);
        core.put("version", VERSION);
        core.put("id", doc.id());
        core.put("author", doc.author());
        String manifest = ManifestJson.write(core, doc.extensions());

        ZipOutputStream zip = new ZipOutputStream(out);
        put(zip, MANIFEST, manifest.getBytes(StandardCharsets.UTF_8));
        put(zip, SYSTEM, MAPPER.writeValueAsBytes(system.json()));
        for (Map.Entry<String, byte[]> e : system.files().entrySet()) {
            put(zip, e.getKey(), e.getValue());
        }
        for (Map.Entry<String, byte[]> e : doc.soundFiles().entrySet()) {
            put(zip, e.getKey(), e.getValue());
        }
        for (Map.Entry<String, byte[]> e : doc.extraFiles().entrySet()) {
            put(zip, e.getKey(), e.getValue());
        }
        zip.finish();
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(ENTRY_TIME);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    // ------------------------------------------------------------------------------------------------
    // Editable view (editors, MCP tools)
    // ------------------------------------------------------------------------------------------------

    /**
     * The document's system as an editable {@code system.json} tree: the archive form with every graph inline (a
     * {@code openmason.fxgraph} object in its effect) and no {@code sprites} table — the document's sprites and
     * sound files are its own, untouched by edits of this view. Feed an edited copy to {@link #withSystemJson}.
     */
    public static ObjectNode toSystemJson(OmfxDocument doc) {
        try {
            return OmfxSystemJson.write(doc, true).json();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(doc.id() + ": " + e.getMessage(), e);
        }
    }

    /**
     * {@code doc} with its system replaced by {@code system} (the {@link #toSystemJson} shape; graphs inline or
     * omitted), read by the same strict reader as a file. The id, author, sprites, extensions and extra files are
     * kept; embedded sound defs must name sound files the document already holds (files no def uses any more are
     * dropped); a {@code sprites} member is ignored. Fails with a reason, leaving {@code doc} as it was.
     */
    public static OmfxDocument withSystemJson(OmfxDocument doc, JsonNode system) throws IOException {
        if (system == null || !system.isObject()) {
            throw new IOException(SYSTEM + " must be a JSON object");
        }
        ObjectNode copy = ((ObjectNode) system).deepCopy();
        copy.remove("sprites");
        Map<String, byte[]> entries = new LinkedHashMap<>(doc.soundFiles());
        OmfxSystemJson.Parsed parsed = OmfxSystemJson.read(doc.id(), copy, entries);
        try {
            return new OmfxDocument(doc.author(), parsed.system(), doc.sprites(), parsed.soundFiles(),
                    doc.extensions(), doc.extraFiles());
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid .omfx document: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Entry names
    // ------------------------------------------------------------------------------------------------

    /** Rejects entry names that could escape an extraction folder or that zip tools disagree on. */
    static void checkEntryName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException(".omfx entry with an empty name");
        }
        if (name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(".omfx entry '" + name + "' is not a relative '/'-separated path");
        }
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(".omfx entry '" + name + "' has an empty, '.' or '..' segment");
            }
        }
    }

    /** True for paths the format owns (written from the document model, never preserved as extra data). */
    static boolean isReserved(String path) {
        return path.equals(MANIFEST) || path.equals(SYSTEM) || path.startsWith(GRAPHS_DIR)
                || path.startsWith(TEXTURES_DIR) || path.startsWith(SOUNDS_DIR);
    }

    /** True for a valid embedded sound path ({@code sounds/<name>}). */
    static boolean isSoundPath(String path) {
        if (path == null || !path.startsWith(SOUNDS_DIR) || path.length() == SOUNDS_DIR.length()) {
            return false;
        }
        try {
            checkEntryName(path);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** {@code text} reduced to {@code [A-Za-z0-9._-]} for use as a file name (never empty or dot-only). */
    static String fileStem(String text) {
        StringBuilder b = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            b.append(c < 128 && (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') ? c : '_');
        }
        String s = b.toString();
        while (s.startsWith(".")) {
            s = s.substring(1);
        }
        return s.isEmpty() ? "_" : s;
    }

    /** {@code dir + stem + ext}, with a numeric suffix when {@code taken} (compared case-insensitively) has it. */
    static String uniquePath(String dir, String stem, String ext, Set<String> taken) {
        String candidate = dir + stem + ext;
        for (int n = 2; taken.contains(candidate.toLowerCase(Locale.ROOT)); n++) {
            candidate = dir + stem + "-" + n + ext;
        }
        taken.add(candidate.toLowerCase(Locale.ROOT));
        return candidate;
    }

    /** SHA-256 of {@code data} as lower-case hex (the sound-def checksum). */
    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** The checksum {@code def} gets on write: its embedded bytes' SHA-256. */
    static String checksumFor(SoundDef def, OmfxDocument doc) {
        return sha256(doc.soundFiles().get(def.filename()));
    }
}
