package com.openmason.main.systems.animgraph;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The animation graph open in the editor (OpenMason #164): ONE session shared by the Animation Graph tab and the
 * {@code animgraph_*} MCP tools, so an agent's edits show in the tab and both use one native undo history (user
 * decision 2026-11-25). GL- and ImGui-free (works headless for tests). Main thread only.
 */
public final class AnimGraphSessions {

    private AnimGraphSession current;
    private Consumer<AnimGraphSession> onOpened = d -> { };
    private Supplier<Path> projectRoot = () -> null;

    /** Called after a document becomes current (the workspace brings its tab forward). */
    public void setOnOpened(Consumer<AnimGraphSession> onOpened) {
        this.onOpened = onOpened == null ? d -> { } : onOpened;
    }

    public void setProjectRoot(Supplier<Path> projectRoot) {
        this.projectRoot = projectRoot == null ? () -> null : projectRoot;
    }

    public Path projectRoot() {
        return projectRoot.get();
    }

    public Optional<AnimGraphSession> current() {
        return Optional.ofNullable(current);
    }

    /** The current document, or an error naming how to get one. */
    public AnimGraphSession require() {
        if (current == null) {
            throw new IllegalStateException("no animation graph is open: animgraph_new or animgraph_open first");
        }
        return current;
    }

    /** Makes {@code doc} current, closing the previous one (callers ask about unsaved changes first). */
    public void replace(AnimGraphSession doc) {
        if (current != null && current != doc) {
            current.close();
        }
        current = doc;
        if (doc != null) {
            doc.setProjectRoot(projectRoot.get());
            onOpened.accept(doc);
        }
    }

    /** Closes the current document. */
    public void close() {
        replace(null);
    }
}
