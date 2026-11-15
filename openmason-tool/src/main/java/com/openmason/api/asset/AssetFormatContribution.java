package com.openmason.api.asset;

import com.openmason.api.files.FileType;
import com.openmason.api.menu.MenuEntry;
import com.openmason.api.viewport.ViewportContentProvider;
import com.openmason.engine.format.spi.FormatCodec;
import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A whole asset kind a plugin owns (#88): its file type and what opening one does, the engine
 * {@link FormatCodec} that reads it, the {@link AssetSurface}s its windows offer to sections,
 * its editor/export windows and the menu entries that open them, its viewport content and its
 * project templates. Registered through {@code PluginContext.assetFormats()}, which registers each
 * part with its own registrar (file types, openers, menu entries, viewport content, templates) on
 * behalf of the same plugin, so a failed plugin loses all of it. Without the plugin the editor
 * does not know the kind at all: no menu entry, no opener, no Project Browser listing.
 *
 * @param id             stable id, usually the file type's ({@code "mykind"})
 * @param fileType       the files this kind covers
 * @param opener         what opening one does (Project Browser click, drop), or null when it cannot be opened
 * @param codec          the engine reader, or null; registered with the engine's
 *                       {@link com.openmason.engine.format.spi.Formats} when the engine does not
 *                       discover it itself (a plugin's module layer)
 * @param surfaces       where this kind's windows host {@link AssetEditorSection}s
 * @param windows        floating windows rendered every frame
 * @param menuEntries    menu items/sections that start an editor or export
 * @param viewportContent non-model content the Model Editor viewport can show for this kind
 * @param templates      Project Hub templates built on this kind
 * @param textureActions entries of the Texture Editor's Tools menu (exports to this kind...)
 */
public record AssetFormatContribution(String id,
                                      FileType fileType,
                                      Consumer<Path> opener,
                                      FormatCodec<?> codec,
                                      List<AssetSurface<?>> surfaces,
                                      List<AssetWindow> windows,
                                      List<MenuEntry> menuEntries,
                                      List<ViewportContentProvider> viewportContent,
                                      List<ProjectTemplate> templates,
                                      List<TextureAction> textureActions) {

    public AssetFormatContribution {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fileType, "fileType");
        surfaces = List.copyOf(surfaces == null ? List.of() : surfaces);
        windows = List.copyOf(windows == null ? List.of() : windows);
        menuEntries = List.copyOf(menuEntries == null ? List.of() : menuEntries);
        viewportContent = List.copyOf(viewportContent == null ? List.of() : viewportContent);
        templates = List.copyOf(templates == null ? List.of() : templates);
        textureActions = List.copyOf(textureActions == null ? List.of() : textureActions);
    }

    /** A builder for the kind {@code fileType} describes, with the type's id. */
    public static Builder builder(FileType fileType) {
        return new Builder(fileType.id(), fileType);
    }

    public static final class Builder {
        private final String id;
        private final FileType fileType;
        private Consumer<Path> opener;
        private FormatCodec<?> codec;
        private final List<AssetSurface<?>> surfaces = new ArrayList<>();
        private final List<AssetWindow> windows = new ArrayList<>();
        private final List<MenuEntry> menuEntries = new ArrayList<>();
        private final List<ViewportContentProvider> viewportContent = new ArrayList<>();
        private final List<ProjectTemplate> templates = new ArrayList<>();
        private final List<TextureAction> textureActions = new ArrayList<>();

        private Builder(String id, FileType fileType) {
            this.id = id;
            this.fileType = fileType;
        }

        public Builder opener(Consumer<Path> opener) {
            this.opener = opener;
            return this;
        }

        public Builder codec(FormatCodec<?> codec) {
            this.codec = codec;
            return this;
        }

        public Builder surface(AssetSurface<?> surface) {
            surfaces.add(Objects.requireNonNull(surface));
            return this;
        }

        public Builder window(AssetWindow window) {
            windows.add(Objects.requireNonNull(window));
            return this;
        }

        public Builder menuEntry(MenuEntry entry) {
            menuEntries.add(Objects.requireNonNull(entry));
            return this;
        }

        public Builder viewportContent(ViewportContentProvider provider) {
            viewportContent.add(Objects.requireNonNull(provider));
            return this;
        }

        public Builder template(ProjectTemplate template) {
            templates.add(Objects.requireNonNull(template));
            return this;
        }

        public Builder textureAction(TextureAction action) {
            textureActions.add(Objects.requireNonNull(action));
            return this;
        }

        public AssetFormatContribution build() {
            return new AssetFormatContribution(id, fileType, opener, codec, surfaces, windows, menuEntries,
                    viewportContent, templates, textureActions);
        }
    }
}
