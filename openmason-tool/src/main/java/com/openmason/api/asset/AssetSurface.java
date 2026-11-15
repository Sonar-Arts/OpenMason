package com.openmason.api.asset;

import com.openmason.engine.rendering.particles.omfx.OmfxDocument;

import java.util.List;
import java.util.Objects;

/**
 * Where an {@link AssetEditorSection} is shown, and the manifest model it edits there (#431). The
 * editors edit a parsed manifest; the export windows fill in the parameters of a
 * new file (mutable). A section is written for one surface; a plugin that
 * wants the same data in an editor and an export window registers one section per surface.
 *
 * <p>A surface belongs to the asset kind whose window shows it: the plugin that contributes the kind
 * ({@link AssetFormatContribution}) declares its surfaces with {@link #of} (#88). Section
 * contributions match a surface by identity, so a plugin publishes its surface constants for others.
 * The only surface the editor itself owns is {@link #FX_EXPORT}.
 *
 * @param <M> the manifest model the section reads and writes
 */
public final class AssetSurface<M> {

    /**
     * The Export FX window (#102): the FX system being exported, as its {@code .omfx} v2 document. Sections keep
     * their game data in the document's manifest extensions ({@code OmfxDocument.withExtensions}) under their own
     * namespace; the game's {@code FxExporter} reads it back from there.
     */
    public static final AssetSurface<OmfxDocument> FX_EXPORT =
            new AssetSurface<>("fx.export", "Export FX", OmfxDocument.class);

    private final String id;
    private final String displayName;
    private final Class<M> modelType;

    /**
     * A new surface for an asset kind's window.
     *
     * @param id          stable namespaced-by-kind id ({@code "mykind.editor"})
     * @param displayName human name ("MyKind Editor")
     * @param modelType   the manifest model sections read and write there
     */
    public static <M> AssetSurface<M> of(String id, String displayName, Class<M> modelType) {
        return new AssetSurface<>(id, displayName, modelType);
    }

    private AssetSurface(String id, String displayName, Class<M> modelType) {
        this.id = Objects.requireNonNull(id);
        this.displayName = Objects.requireNonNull(displayName);
        this.modelType = Objects.requireNonNull(modelType);
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public Class<M> modelType() {
        return modelType;
    }

    /**
     * The surfaces declared in this class, in a stable order. Surfaces of plugin-contributed kinds
     * are listed by the host's registry of {@link AssetFormatContribution}s instead.
     */
    public static List<AssetSurface<?>> all() {
        return List.of(FX_EXPORT);
    }

    @Override
    public String toString() {
        return id;
    }
}
