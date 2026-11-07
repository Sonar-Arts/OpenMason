package com.openmason.main.systems.particles;

import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleLibrary;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Particle Editor's document: a working {@link ParticleLibrary}, the selected effect, the file it came from
 * and whether it has unsaved changes. GL- and ImGui-free so every editing rule is unit-testable; the window
 * only draws it.
 *
 * <p>Effects are immutable records that hold their sub-emitter effects by reference, so editing one effect
 * means rebuilding every effect that (transitively) spawns it. {@link #apply} does that cascade and returns
 * the old → new pairs so the live preview can hot-swap them without restarting.
 */
public final class ParticleEditorModel {

    /** One effect replaced by another (preview hot-swap). */
    public record Swap(ParticleEffect old, ParticleEffect updated) {
    }

    private ParticleLibrary library;
    private String selected;
    private Path file;
    private boolean dirty;

    /** A new document holding the built-in presets. */
    public ParticleEditorModel() {
        reset(ParticleLibrary.withBuiltIns(), null);
    }

    private void reset(ParticleLibrary lib, Path from) {
        library = lib;
        file = from;
        dirty = false;
        selected = lib.effects().isEmpty() ? null : lib.effects().iterator().next().name();
    }

    // ------------------------------------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------------------------------------

    public ParticleLibrary library() { return library; }
    public Optional<Path> file() { return Optional.ofNullable(file); }
    public boolean isDirty() { return dirty; }

    /** Effect names in library order. */
    public List<String> names() {
        List<String> names = new ArrayList<>();
        for (ParticleEffect e : library.effects()) {
            names.add(e.name());
        }
        return names;
    }

    public Optional<ParticleEffect> selected() {
        return selected == null ? Optional.empty() : library.find(selected);
    }

    public void select(String name) {
        if (library.contains(name)) {
            selected = name;
        }
    }

    /** Effects whose sub-emitters spawn {@code name} directly. */
    public List<String> usersOf(String name) {
        List<String> users = new ArrayList<>();
        for (ParticleEffect e : library.effects()) {
            for (ParticleEffect.SubEmitter s : e.subEmitters()) {
                if (s.effect().name().equals(name)) {
                    users.add(e.name());
                    break;
                }
            }
        }
        return users;
    }

    /** Whether {@code effect} spawns an effect called {@code name}, directly or through its sub-emitters. */
    public static boolean spawns(ParticleEffect effect, String name) {
        for (ParticleEffect.SubEmitter s : effect.subEmitters()) {
            if (s.effect().name().equals(name) || spawns(s.effect(), name)) {
                return true;
            }
        }
        return false;
    }

    /** Effects the selected one may use as a sub-emitter target (not itself, nothing that spawns it). */
    public List<String> subEmitterCandidates() {
        List<String> out = new ArrayList<>();
        Optional<ParticleEffect> current = selected();
        for (ParticleEffect e : library.effects()) {
            if (current.isPresent() && (e.name().equals(current.get().name()) || spawns(e, current.get().name()))) {
                continue;
            }
            out.add(e.name());
        }
        return out;
    }

    /** {@code base}, or {@code base-2}, {@code base-3}… — the first name not in the library. */
    public String uniqueName(String base) {
        String root = base == null || base.isBlank() ? "effect" : base.strip();
        if (!library.contains(root)) {
            return root;
        }
        for (int i = 2; ; i++) {
            String candidate = root + "-" + i;
            if (!library.contains(candidate)) {
                return candidate;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------------------------------------

    /** Adds a default effect with a unique name and selects it. */
    public ParticleEffect create(String baseName) {
        ParticleEffect e = ParticleEffect.builder(uniqueName(baseName)).build();
        library.register(e);
        selected = e.name();
        dirty = true;
        return e;
    }

    /** Copies the selected effect under a unique name and selects the copy. */
    public Optional<ParticleEffect> duplicateSelected() {
        Optional<ParticleEffect> source = selected();
        source.ifPresent(e -> {
            ParticleEffect copy = e.toBuilder().name(uniqueName(e.name())).build();
            library.register(copy);
            selected = copy.name();
            dirty = true;
        });
        return selected();
    }

    /**
     * Replaces the selected effect with {@code edited} (same name, or a rename — then the name must be free)
     * and rebuilds every effect that spawns it. Returns the swaps for the preview.
     *
     * @throws IllegalArgumentException if a rename collides with an existing effect
     */
    public List<Swap> apply(ParticleEffect edited) {
        ParticleEffect current = selected().orElseThrow(() -> new IllegalStateException("nothing selected"));
        if (!edited.name().equals(current.name()) && library.contains(edited.name())) {
            throw new IllegalArgumentException("an effect named '" + edited.name() + "' already exists");
        }
        for (ParticleEffect.SubEmitter s : edited.subEmitters()) {
            if (spawns(s.effect(), current.name()) || s.effect().name().equals(current.name())) {
                throw new IllegalArgumentException("'" + s.effect().name() + "' already spawns '" + current.name()
                        + "': a sub-emitter loop would never end");
            }
        }
        List<Swap> swaps = new ArrayList<>();
        replaceEverywhere(current, edited, swaps);
        selected = edited.name();
        dirty = true;
        return swaps;
    }

    /** Rebuilds the library with {@code updated} in place of {@code old}, keeping order, cascading to users. */
    private void replaceEverywhere(ParticleEffect old, ParticleEffect updated, List<Swap> swaps) {
        List<ParticleEffect> working = new ArrayList<>(library.effects());
        cascade(working, old, updated, swaps);
        ParticleLibrary next = new ParticleLibrary();
        for (ParticleEffect e : working) {
            next.put(e); // exactly the working set, in document order
        }
        library = next;
    }

    private static void cascade(List<ParticleEffect> working, ParticleEffect old, ParticleEffect updated,
                                List<Swap> swaps) {
        working.replaceAll(e -> e == old ? updated : e);
        swaps.add(new Swap(old, updated));
        // By index, reading each entry's current version: a deeper cascade may already have rebuilt it
        // (diamonds: B spawns both `old` and A, and A spawns `old`).
        for (int i = 0; i < working.size(); i++) {
            ParticleEffect user = working.get(i);
            boolean uses = false;
            List<ParticleEffect.SubEmitter> subs = new ArrayList<>();
            for (ParticleEffect.SubEmitter s : user.subEmitters()) {
                if (s.effect() == old) {
                    uses = true;
                    subs.add(new ParticleEffect.SubEmitter(updated, s.trigger(), s.count(), s.rate(), s.inheritVelocity()));
                } else {
                    subs.add(s);
                }
            }
            if (uses) {
                cascade(working, user, user.toBuilder().subEmitters(subs).build(), swaps);
            }
        }
    }

    /**
     * Deletes the selected effect. Refused (returns the users) while other effects spawn it — delete or edit
     * those sub-emitters first. Returns an empty list on success.
     */
    public List<String> deleteSelected() {
        Optional<ParticleEffect> current = selected();
        if (current.isEmpty()) {
            return List.of();
        }
        List<String> users = usersOf(current.get().name());
        if (!users.isEmpty()) {
            return users;
        }
        List<String> names = names();
        int index = names.indexOf(current.get().name());
        library.remove(current.get().name());
        List<String> remaining = names();
        selected = remaining.isEmpty() ? null : remaining.get(Math.min(index, remaining.size() - 1));
        dirty = true;
        return List.of();
    }

    // ------------------------------------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------------------------------------

    /** A fresh document with the built-in presets. */
    public void newDocument() {
        reset(ParticleLibrary.withBuiltIns(), null);
    }

    /** Replaces the document with the effects in {@code path}. On error the document is unchanged. */
    public void open(Path path) throws IOException {
        ParticleLibrary lib = new ParticleLibrary();
        try (InputStream in = Files.newInputStream(path)) {
            lib.load(in);
        }
        reset(lib, path);
    }

    /** Writes the document to {@code path} (atomically) and makes it the document's file. */
    public void save(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                library.write(out);
            }
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        file = path;
        dirty = false;
    }
}
