package com.openmason.main.systems.particles.export;

import com.openmason.api.fx.FxExportInput;
import com.openmason.api.fx.FxExportTarget;
import com.openmason.api.target.GameTarget;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.openmason.main.systems.io.WriteKind;
import com.openmason.main.systems.io.WriteRoot;
import com.openmason.main.systems.io.WriteRoots;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Exports FX systems through the registered {@link FxExportTarget}s (#102): the one place the Particles tab and the
 * {@code fx_export} MCP tool turn a document into a game's file. GL- and ImGui-free.
 *
 * <p>A {@link Plan} is the checked, exported bytes; it is then written where the user picks ({@link #writeTo}) or
 * deployed into the active game target's checkout ({@link #deploy}) when the target's file type saves there.
 */
public final class FxExportService {

    /** The registered targets; wired to the plugin registries by {@code UiComposition}, a fixed list in tests. */
    private static volatile Supplier<List<FxExportTarget>> source = List::of;

    public static void setSource(Supplier<List<FxExportTarget>> targets) {
        source = targets != null ? targets : List::of;
    }

    /** Every registered export target, in registration order. */
    public static List<FxExportTarget> registered() {
        return List.copyOf(source.get());
    }

    /** The registered target {@code id}, if any. */
    public static Optional<FxExportTarget> find(String id) {
        return registered().stream().filter(t -> t.id().equals(id)).findFirst();
    }

    /**
     * Whether a target can deploy right now and where to.
     *
     * @param deployable true when {@link #deploy} would write
     * @param folder     the checkout folder files land in (when deployable)
     * @param reason     why not (when not deployable)
     */
    public record Deployment(boolean deployable, Path folder, String reason) {
    }

    /**
     * An export ready to write.
     *
     * @param target   the container
     * @param input    the checked source
     * @param bytes    the file's bytes; null when {@code problems} blocked the export
     * @param fileName default file name ({@code <id>.<ext>})
     * @param problems why the container cannot carry this system (empty = exported)
     */
    public record Plan(FxExportTarget target, FxExportInput input, byte[] bytes, String fileName,
                       List<String> problems) {
        public Plan {
            problems = List.copyOf(problems);
        }

        public boolean ok() {
            return bytes != null;
        }
    }

    /**
     * A deploy's result.
     *
     * @param path      the file in the checkout
     * @param written   false when the checkout already had exactly these bytes, or a conflict stopped it
     * @param conflict  true when a different file is there and {@code overwrite} was not given
     */
    public record Deployed(Path path, boolean written, boolean conflict) {
    }

    private final WriteRoots roots;

    /** @param roots where the game target's checkout is ({@link WriteRoots#forInterface} in the editor) */
    public FxExportService(WriteRoots roots) {
        this.roots = Objects.requireNonNull(roots, "roots");
    }

    /** Checks the document, asks the container for problems, and exports when there are none. */
    public Plan plan(FxExportTarget target, OmfxDocument document) throws IOException {
        FxExportInput input = FxExportInput.prepare(document);
        String name = fileName(target, document.id());
        List<String> problems;
        try {
            problems = target.exporter().problems(input);
        } catch (RuntimeException e) {
            throw new IOException(target.displayName() + " could not check " + document.id() + ": " + e.getMessage(), e);
        }
        if (problems != null && !problems.isEmpty()) {
            return new Plan(target, input, null, name, problems);
        }
        byte[] bytes;
        try {
            bytes = target.exporter().export(input);
        } catch (RuntimeException e) {
            throw new IOException(target.displayName() + " failed on " + document.id() + ": " + e.getMessage(), e);
        }
        if (bytes == null) {
            throw new IOException(target.displayName() + " returned no bytes for " + document.id());
        }
        return new Plan(target, input, bytes, name, List.of());
    }

    /** Where {@code target} deploys now, or why it cannot. */
    public Deployment deployment(FxExportTarget target) {
        if (!target.deploys()) {
            return new Deployment(false, null, target.displayName() + " is an export format (saves to the "
                    + target.fileType().save().root() + " folder), not a game deploy");
        }
        Optional<GameTarget> active = roots.target();
        if (target.gameTargetId() != null && active.map(t -> !t.id().equals(target.gameTargetId())).orElse(true)) {
            return new Deployment(false, null, target.displayName() + " belongs to game target '"
                    + target.gameTargetId() + "'" + active.map(t -> " (the project targets '" + t.id() + "')")
                    .orElse(" (the project has no game target)"));
        }
        Path root = roots.path(WriteRoot.TARGET);
        if (root == null) {
            return new Deployment(false, null, "the game target's resource folder is not available or not writable");
        }
        String sub = WriteKind.of(target.fileType()).subdirFor(active.orElse(null));
        return new Deployment(true, sub.isEmpty() ? root : root.resolve(sub).normalize(), null);
    }

    /** Writes the plan's bytes to {@code file} (through a sibling temp file); returns the file. */
    public Path writeTo(Plan plan, Path file) throws IOException {
        requireOk(plan);
        write(file, plan.bytes());
        return file;
    }

    /**
     * Writes the plan into the game target's checkout under its default name. A different existing file is only
     * replaced with {@code overwrite}; identical bytes are left alone.
     */
    public Deployed deploy(Plan plan, boolean overwrite) throws IOException {
        requireOk(plan);
        Deployment d = deployment(plan.target());
        if (!d.deployable()) {
            throw new IOException("cannot deploy: " + d.reason());
        }
        Path file = d.folder().resolve(plan.fileName());
        if (Files.isRegularFile(file)) {
            if (Arrays.equals(Files.readAllBytes(file), plan.bytes())) {
                return new Deployed(file, false, false);
            }
            if (!overwrite) {
                return new Deployed(file, false, true);
            }
        }
        write(file, plan.bytes());
        return new Deployed(file, true, false);
    }

    /** {@code <id>.<ext>} with the id reduced to file-name characters. */
    public static String fileName(FxExportTarget target, String id) {
        String stem = id.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^\\.+", "");
        return WriteKind.of(target.fileType()).ensureExtension(stem.isEmpty() ? target.fileType().save()
                .fallbackName() : stem);
    }

    private static void requireOk(Plan plan) throws IOException {
        if (!plan.ok()) {
            throw new IOException("not exported: " + String.join("; ", plan.problems()));
        }
    }

    private static void write(Path file, byte[] bytes) throws IOException {
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
}
