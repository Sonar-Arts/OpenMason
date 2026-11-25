package com.openmason.main.systems.animation;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The animation open in the editor (OpenMason #165): ONE session shared by the Animation tab and the
 * {@code anim_*} MCP tools, so an agent's edits show in the tab and both use one native undo history (user
 * decision 2026-11-25). GL- and ImGui-free (works headless for tests). Main thread only.
 */
public final class AnimationSessions {

    private AnimationSession current;
    private Consumer<AnimationSession> onOpened = d -> { };
    private Supplier<Path> projectRoot = () -> null;

    /** Called after a document becomes current (the workspace brings its tab forward). */
    public void setOnOpened(Consumer<AnimationSession> onOpened) {
        this.onOpened = onOpened == null ? d -> { } : onOpened;
    }

    public void setProjectRoot(Supplier<Path> projectRoot) {
        this.projectRoot = projectRoot == null ? () -> null : projectRoot;
    }

    public Path projectRoot() {
        return projectRoot.get();
    }

    public Optional<AnimationSession> current() {
        return Optional.ofNullable(current);
    }

    /** The current document, or an error naming how to get one. */
    public AnimationSession require() {
        if (current == null) {
            throw new IllegalStateException("no animation is open: anim_new or anim_open first");
        }
        return current;
    }

    /** Makes {@code doc} current, closing the previous one (callers ask about unsaved changes first). */
    public void replace(AnimationSession doc) {
        if (current != null && current != doc) {
            current.close();
        }
        current = doc;
        if (doc != null) {
            onOpened.accept(doc);
        }
    }

    /** Closes the current document. */
    public void close() {
        replace(null);
    }
}
