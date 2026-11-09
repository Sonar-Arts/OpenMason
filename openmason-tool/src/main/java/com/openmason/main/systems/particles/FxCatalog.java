package com.openmason.main.systems.particles;

import com.openmason.api.fx.FxCatalogSource;
import com.openmason.api.target.GameTarget;
import com.openmason.main.systems.target.GameTargets;

import com.openmason.engine.format.fx.FxParamValue;
import com.openmason.engine.rendering.particles.FxParameter;
import com.openmason.engine.rendering.particles.FxPresets;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleLibrary;
import com.openmason.engine.rendering.particles.omfx.OmfxCodec;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The FX a project knows (#103), what the SBO/SBE editors' FX bindings are checked against: every {@code .omfx}
 * in the project (a v2 system by its id, a v1 library by its effect names), the FX plugins contribute
 * ({@link FxCatalogSource}: a game's shipped FX, while its game target is active) and the engine's FX and effect
 * presets, in that order. GL-free. A project entry wins over a preset of the same id; a binding id {@code ns:name} also matches an entry
 * named {@code name} (games namespace the FX they ship).
 */
public final class FxCatalog {

    /** Where an entry came from. */
    public enum Source { PROJECT, PLUGIN, PRESET }

    /**
     * One parameter an FX declares.
     *
     * @param components 1 (scalar), 3 (vector) or 4 (colour), the shape a binding's override must have
     */
    public record Param(String name, int components, FxParamValue defaultValue) {

        static Param of(FxParameter p) {
            return switch (p.type()) {
                case FLOAT -> new Param(p.name(), 1, FxParamValue.scalar(p.x()));
                case VECTOR -> new Param(p.name(), 3, FxParamValue.vector(p.x(), p.y(), p.z()));
                case COLOR -> new Param(p.name(), 4, FxParamValue.color(p.x(), p.y(), p.z(), p.w()));
            };
        }

        /** "scalar", "vector" or "colour". */
        public String kind() {
            return kindName(components);
        }
    }

    /**
     * One known FX.
     *
     * @param origin the project-relative file it came from, "<plugin source>: <origin>", or "engine preset"
     */
    public record Entry(String id, Source source, String origin, List<Param> params) {
        public Entry {
            params = List.copyOf(params);
        }

        public Param param(String name) {
            for (Param p : params) {
                if (p.name().equals(name)) return p;
            }
            return null;
        }
    }

    /** Files deeper than this below the project root are not scanned. */
    static final int MAX_DEPTH = 8;
    /** At most this many .omfx files are read per scan. */
    static final int MAX_FILES = 512;

    private static final FxCatalog EMPTY = new FxCatalog(Map.of(), List.of());

    /** The plugins' catalog sources (set once at start-up from the plugin registries; none in tests). */
    private static volatile Supplier<List<FxCatalogSource>> pluginSources = List::of;

    private final Map<String, Entry> byId;
    private final List<String> problems;

    private FxCatalog(Map<String, Entry> byId, List<String> problems) {
        this.byId = Collections.unmodifiableMap(new LinkedHashMap<>(byId));
        this.problems = List.copyOf(problems);
    }

    /** A catalog that knows nothing (no project, tests). Every binding is then "unknown", which only warns. */
    public static FxCatalog empty() {
        return EMPTY;
    }

    /** The engine presets only. */
    public static FxCatalog presets() {
        Map<String, Entry> byId = new LinkedHashMap<>();
        addPresets(byId);
        return new FxCatalog(byId, List.of());
    }

    /** These entries (first of an id wins), then the engine presets. */
    public static FxCatalog of(List<Entry> entries) {
        Map<String, Entry> byId = new LinkedHashMap<>();
        for (Entry e : entries) byId.putIfAbsent(e.id(), e);
        addPresets(byId);
        return new FxCatalog(byId, List.of());
    }

    /** Where {@link #scan(Path)} finds the plugins' {@link FxCatalogSource}s (null = none). */
    public static void setPluginSources(Supplier<List<FxCatalogSource>> sources) {
        pluginSources = sources != null ? sources : List::of;
    }

    /**
     * The FX of the project at {@code projectRoot} (null = no project files) plus the registered plugin sources that
     * apply to the active game target. Files and sources that cannot be read are skipped and listed in
     * {@link #problems()}.
     */
    public static FxCatalog scan(Path projectRoot) {
        String active = null;
        try {
            active = GameTargets.shared().active().map(GameTarget::id).orElse(null);
        } catch (RuntimeException e) {
            // no target registry (headless): only target-independent sources apply
        }
        List<FxCatalogSource> sources;
        try {
            sources = pluginSources.get();
        } catch (RuntimeException e) {
            sources = List.of();
        }
        return scan(projectRoot, sources, active);
    }

    /** As {@link #scan(Path)} with explicit plugin sources and active game target id (null = none). */
    public static FxCatalog scan(Path projectRoot, List<FxCatalogSource> sources, String activeTargetId) {
        Map<String, Entry> byId = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        if (projectRoot != null && Files.isDirectory(projectRoot)) {
            for (Path file : omfxFiles(projectRoot, problems)) {
                String origin = projectRoot.relativize(file).toString().replace('\\', '/');
                try {
                    for (Entry e : read(file, origin)) byId.putIfAbsent(e.id(), e);
                } catch (IOException | RuntimeException ex) {
                    problems.add(origin + ": " + ex.getMessage());
                }
            }
        }
        addPluginSources(byId, problems, sources, activeTargetId);
        addPresets(byId);
        return new FxCatalog(byId, problems);
    }

    private static void addPluginSources(Map<String, Entry> byId, List<String> problems,
                                         List<FxCatalogSource> sources, String activeTargetId) {
        if (sources == null) return;
        for (FxCatalogSource source : sources) {
            if (source == null || !source.appliesTo(activeTargetId)) continue;
            List<FxCatalogSource.Entry> listed;
            try {
                listed = source.lister().list();
            } catch (IOException | RuntimeException ex) {
                problems.add(source.displayName() + ": " + ex.getMessage());
                continue;
            }
            if (listed == null) continue;
            for (FxCatalogSource.Entry e : listed) {
                if (e == null) continue;
                List<Param> params = new ArrayList<>();
                for (FxParameter p : e.parameters()) params.add(Param.of(p));
                String origin = e.origin().isEmpty() ? source.displayName() : source.displayName() + ": " + e.origin();
                byId.putIfAbsent(e.id(), new Entry(e.id(), Source.PLUGIN, origin, params));
            }
        }
    }

    private static List<Path> omfxFiles(Path root, List<String> problems) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), MAX_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                            return !dir.equals(root) && name.startsWith(".")
                                    ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (attrs.isRegularFile() && file.getFileName().toString().toLowerCase(Locale.ROOT)
                                    .endsWith(OmfxCodec.FILE_EXTENSION)) {
                                files.add(file);
                                if (files.size() >= MAX_FILES) return FileVisitResult.TERMINATE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            problems.add("could not scan " + root + ": " + e.getMessage());
        }
        files.sort(null);
        return files;
    }

    /** A v2 archive is one system; a v1 library JSON contributes one entry per effect (its migration ids). */
    static List<Entry> read(Path file, String origin) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (OmfxCodec.isArchive(bytes)) {
            OmfxDocument doc = OmfxCodec.read(bytes);
            return List.of(entry(doc.system(), Source.PROJECT, origin));
        }
        ParticleLibrary library = new ParticleLibrary();
        try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            library.load(in);
        }
        List<Entry> entries = new ArrayList<>();
        for (ParticleEffect effect : library.effects()) {
            entries.add(new Entry(effect.name(), Source.PROJECT, origin, List.of()));
        }
        return entries;
    }

    private static Entry entry(FxSystem system, Source source, String origin) {
        List<Param> params = new ArrayList<>();
        for (FxParameter p : system.parameters()) params.add(Param.of(p));
        return new Entry(system.name(), source, origin, params);
    }

    private static void addPresets(Map<String, Entry> byId) {
        for (FxSystem system : FxPresets.ALL) {
            byId.putIfAbsent(system.name(), entry(system, Source.PRESET, "engine preset"));
        }
        for (ParticleEffect effect : ParticleLibrary.Presets.ALL) {
            byId.putIfAbsent(effect.name(), new Entry(effect.name(), Source.PRESET, "engine preset", List.of()));
        }
    }

    /** Every known FX: project files, then plugin sources, then presets. */
    public List<Entry> entries() {
        return List.copyOf(byId.values());
    }

    public int size() {
        return byId.size();
    }

    /** The entry a binding's FX id names: exact, else the name after its namespace; null when unknown. */
    public Entry find(String fxId) {
        if (fxId == null || fxId.isBlank()) return null;
        String id = fxId.trim();
        Entry exact = byId.get(id);
        if (exact != null) return exact;
        int colon = id.indexOf(':');
        return colon >= 0 && colon < id.length() - 1 ? byId.get(id.substring(colon + 1)) : null;
    }

    /** Files the scan could not read ("path: reason"). */
    public List<String> problems() {
        return problems;
    }

    /** "scalar", "vector" or "colour" for 1, 3 or 4 components. */
    public static String kindName(int components) {
        return switch (components) {
            case 1 -> "scalar";
            case 3 -> "vector";
            case 4 -> "colour";
            default -> components + " values";
        };
    }
}
