package com.openmason.api.asset;

import java.util.Objects;

/**
 * A floating window an {@link AssetFormatContribution} brings with it (an editor or export window):
 * the host renders it every frame on the UI thread, after the workspaces, isolating a throwing
 * render, and closes it once at shutdown (#88). Hidden windows render nothing themselves.
 *
 * @param id     stable id, unique within its contribution ({@code "mykind.editor"})
 * @param title  human name, for error messages ("MyKind Editor Window")
 * @param render draws the window for this frame (a no-op while hidden)
 * @param close  releases what the window holds; runs once at shutdown
 */
public record AssetWindow(String id, String title, Runnable render, Runnable close) {

    public AssetWindow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(render, "render");
        close = close == null ? () -> { } : close;
    }

    public static AssetWindow of(String id, String title, Runnable render, Runnable close) {
        return new AssetWindow(id, title, render, close);
    }
}
