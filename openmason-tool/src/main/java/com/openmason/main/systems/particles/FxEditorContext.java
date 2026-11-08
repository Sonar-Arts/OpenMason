package com.openmason.main.systems.particles;

import com.openmason.engine.rendering.particles.FxEmitter;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.main.systems.particles.fx.FxEditorDocument;
import com.openmason.main.systems.particles.fx.FxTimeline;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What the FX editor's panels share (OM #104): the open document, the selection, the preview's emitter filter and
 * the last refused edit. Every edit goes through {@link #apply}, which reports a refusal instead of throwing and
 * lets the workspace push the result to the preview. GL- and ImGui-free.
 */
final class FxEditorContext {

    private FxEditorDocument doc;
    private String selectedEmitter;
    private String error;
    private String note;
    private final Set<String> muted = new LinkedHashSet<>();
    private final Set<String> soloed = new LinkedHashSet<>();
    /** Bumped on every applied change, undo or document switch (panels refresh their buffers on it). */
    private int revision;

    FxEditorContext(FxEditorDocument doc) {
        setDocument(doc);
    }

    FxEditorDocument doc() {
        return doc;
    }

    /** Switches to another document (New / Open): selection, filter and messages reset. */
    void setDocument(FxEditorDocument next) {
        doc = next;
        selectedEmitter = next.system().emitters().get(0).name();
        muted.clear();
        soloed.clear();
        error = null;
        note = null;
        revision++;
    }

    int revision() {
        return revision;
    }

    /** Runs an edit; a refusal ({@link IllegalArgumentException}/{@link IllegalStateException}) becomes the error. */
    <T> T apply(Supplier<T> edit) {
        try {
            T result = edit.get();
            error = null;
            afterChange();
            return result;
        } catch (IllegalArgumentException | IllegalStateException e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
            return null;
        }
    }

    void apply(Runnable edit) {
        apply(() -> {
            edit.run();
            return Boolean.TRUE;
        });
    }

    /** After an edit or undo: keep the selection valid, drop filter entries for emitters that went. */
    void afterChange() {
        revision++;
        FxSystem s = doc.system();
        if (selectedEmitter == null || doc.emitter(selectedEmitter) == null) {
            selectedEmitter = s.emitters().get(0).name();
        }
        muted.removeIf(n -> doc.emitter(n) == null);
        soloed.removeIf(n -> doc.emitter(n) == null);
    }

    void undo() {
        doc.undo();
        afterChange();
    }

    void redo() {
        doc.redo();
        afterChange();
    }

    FxEmitter selected() {
        return selectedEmitter == null ? null : doc.emitter(selectedEmitter);
    }

    void select(String emitter) {
        if (doc.emitter(emitter) != null) {
            selectedEmitter = emitter;
        }
    }

    /** The system the preview plays: the document's, with the mute/solo filter applied. */
    FxSystem previewSystem() {
        return FxTimeline.filter(doc.system(), muted, soloed);
    }

    boolean isMuted(String emitter) {
        return muted.contains(emitter);
    }

    boolean isSoloed(String emitter) {
        return soloed.contains(emitter);
    }

    void setMuted(String emitter, boolean value) {
        if (value) muted.add(emitter); else muted.remove(emitter);
    }

    void setSoloed(String emitter, boolean value) {
        if (value) soloed.add(emitter); else soloed.remove(emitter);
    }

    /** Called after a rename so the filter follows. */
    void renamed(String from, String to) {
        if (muted.remove(from)) muted.add(to);
        if (soloed.remove(from)) soloed.add(to);
        if (from.equals(selectedEmitter)) selectedEmitter = to;
    }

    String error() {
        return error;
    }

    void setError(String message) {
        error = message;
    }

    String note() {
        return note;
    }

    void setNote(String message) {
        note = message;
    }

    /** The folder relative sprite file names resolve against (the document's), or null while unsaved. */
    Path documentFolder() {
        return doc.file().map(p -> p.toAbsolutePath().getParent()).orElse(null);
    }
}
