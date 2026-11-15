package com.openmason.main.plugins;

import com.openmason.api.PluginContext;
import com.openmason.api.PluginInfo;
import com.openmason.api.Registration;
import com.openmason.api.asset.AssetFormatContribution;
import com.openmason.api.asset.AssetSurface;
import com.openmason.api.asset.AssetWindow;
import com.openmason.api.files.FileType;
import com.openmason.api.menu.MenuItem;
import com.openmason.api.menu.TopMenu;
import com.openmason.api.viewport.ViewportContentFrame;
import com.openmason.api.viewport.ViewportContentProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code PluginContext.assetFormats()} (#88): one contribution, every part registered and owned. */
class AssetFormatsTest {

    private static final FileType WIDGET = FileType.of("widget", "Widget", ".widget");
    private static final AssetSurface<String> WIDGET_EDITOR = AssetSurface.of("widget.editor", "Widget Editor", String.class);

    private static PluginContext context(EditorRegistries registries, String id) {
        return registries.contextFor(new PluginInfo(id, id, "1", Set.of(), false, "unnamed"));
    }

    private static ViewportContentProvider content(String id) {
        return new ViewportContentProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return id;
            }

            @Override
            public boolean ready() {
                return false;
            }

            @Override
            public void render(ViewportContentFrame frame) {
            }
        };
    }

    private static AssetFormatContribution widget(List<String> log, String menuId) {
        return AssetFormatContribution.builder(WIDGET)
                .opener(path -> log.add("open " + path.getFileName()))
                .surface(WIDGET_EDITOR)
                .window(AssetWindow.of("widget.editor", "Widget Editor Window", () -> log.add("render"),
                        () -> log.add("close")))
                .menuEntry(MenuItem.of(menuId, TopMenu.TOOLS, 500, "Widget Editor...", () -> log.add("menu")))
                .viewportContent(content("hello.widget"))
                .build();
    }

    @Test
    void everyPartIsRegisteredForTheSameOwner() {
        EditorRegistries registries = new EditorRegistries();
        List<String> log = new ArrayList<>();
        context(registries, "hello").assetFormats().register(widget(log, "hello.tools.widget"));

        assertEquals(Set.of("hello"), Set.copyOf(registries.fileTypes().ownerOf("widget").stream().toList()));
        assertEquals("hello", registries.fileOpeners().ownerOf("widget").orElseThrow());
        assertEquals("hello", registries.menuEntries().ownerOf("hello.tools.widget").orElseThrow());
        assertEquals("hello", registries.viewportContent().ownerOf("hello.widget").orElseThrow());

        registries.fileOpeners().find("widget").orElseThrow().open().accept(Path.of("a", "b.widget"));
        registries.assetWindows().forEach(w -> w.render().run());
        assertEquals(List.of("open b.widget", "render"), log);
        assertTrue(registries.assetSurfaces().contains(WIDGET_EDITOR));
        assertTrue(registries.assetSurfaces().contains(AssetSurface.FX_EXPORT));
    }

    @Test
    void closingTheRegistrationRemovesEveryPart() {
        EditorRegistries registries = new EditorRegistries();
        Registration r = context(registries, "hello").assetFormats().register(widget(new ArrayList<>(), "hello.tools.widget"));
        r.close();
        assertTrue(registries.assetFormats().all().isEmpty());
        assertTrue(registries.fileTypes().find("widget").isEmpty());
        assertTrue(registries.fileOpeners().find("widget").isEmpty());
        assertTrue(registries.menuEntries().find("hello.tools.widget").isEmpty());
        assertTrue(registries.viewportContent().find("hello.widget").isEmpty());
        assertTrue(registries.assetWindows().isEmpty());
    }

    @Test
    void aClashingPartRollsTheWholeKindBack() {
        EditorRegistries registries = new EditorRegistries();
        context(registries, "other").menuEntries().register(
                MenuItem.of("taken", TopMenu.TOOLS, 1, "Taken", () -> { }));

        assertThrows(IllegalArgumentException.class,
                () -> context(registries, "hello").assetFormats().register(widget(new ArrayList<>(), "taken")));
        assertTrue(registries.assetFormats().all().isEmpty());
        assertTrue(registries.fileTypes().find("widget").isEmpty());
        assertTrue(registries.fileOpeners().find("widget").isEmpty());
        assertEquals("other", registries.menuEntries().ownerOf("taken").orElseThrow());
    }

    @Test
    void aFailedPluginLosesItsKind() {
        EditorRegistries registries = new EditorRegistries();
        context(registries, "hello").assetFormats().register(widget(new ArrayList<>(), "hello.tools.widget"));
        registries.removeOwner("hello");
        assertTrue(registries.assetFormats().all().isEmpty());
        assertTrue(registries.fileTypes().find("widget").isEmpty());
        assertTrue(registries.menuEntries().find("hello.tools.widget").isEmpty());
    }
}
