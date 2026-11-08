package com.openmason.main.systems.particles;

import com.openmason.api.workspace.WorkspaceDescriptor;
import com.openmason.api.Contributions;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.main.systems.layout.BuiltinWorkspaces;
import com.openmason.main.systems.layout.WorkspaceFixtures;
import com.openmason.main.systems.layout.WorkspaceState;
import com.openmason.main.systems.particles.fx.FxEditorDocument;
import com.openmason.main.systems.services.StatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The FX editor (OM #104) follows the workspace-tab framework: a closable tab that Tools / file openers open and
 * bring forward, that ends only on the user's close, and whose close waits on unsaved changes; it opens .omfx v2
 * documents, offers a v1 library's effects as new documents, undoes through the tab and exports the whole system.
 * GL- and ImGui-free (the guard, the file paths and export never draw).
 */
class ParticleEditorWorkspaceTest {

    private WorkspaceState tabs;
    private ParticleEditorWorkspace editor;

    @BeforeEach
    void register() {
        Contributions<WorkspaceDescriptor> workspaces = WorkspaceFixtures.builtins();
        tabs = new WorkspaceState(workspaces);
        editor = new ParticleEditorWorkspace(null, new StatusService(), tabs);
        workspaces.register("test", WorkspaceDescriptor.builder(ParticleEditorWorkspace.ID, "Particles")
                .closable(true).dock(editor).build());
    }

    private FxEditorDocument doc() {
        return editor.context().doc();
    }

    @Test
    void theTabExistsOnlyOnceOpened() {
        assertFalse(tabs.isOpen(BuiltinWorkspaces.PARTICLES));
        assertFalse(editor.isOpen());
        editor.show();
        assertTrue(tabs.isOpen(BuiltinWorkspaces.PARTICLES));
        assertEquals(BuiltinWorkspaces.PARTICLES, tabs.current(), "opens at the front");
        assertEquals(ParticleEditorWorkspace.DEFAULT_PRESET, doc().id(), "a fresh tab shows a preset system");
    }

    @Test
    void showingAgainBringsTheOpenTabForward() {
        editor.show();
        tabs.set(BuiltinWorkspaces.UI);
        editor.show();
        assertEquals(BuiltinWorkspaces.PARTICLES, tabs.current());
    }

    @Test
    void aCleanTabClosesOnTheUsersRequest() {
        editor.show();
        assertTrue(tabs.requestClose(BuiltinWorkspaces.PARTICLES), "an untouched preset closes without asking");
        assertFalse(tabs.isOpen(BuiltinWorkspaces.PARTICLES));
        assertEquals(BuiltinWorkspaces.MODELING, tabs.current(), "falls back to Scene");
    }

    @Test
    void unsavedChangesHoldTheTabOpenAndBringItForwardToAsk() {
        editor.show();
        doc().addEmitter("sparks");
        tabs.set(BuiltinWorkspaces.UI);
        assertFalse(tabs.requestClose(BuiltinWorkspaces.PARTICLES), "the guard takes over");
        assertTrue(tabs.isOpen(BuiltinWorkspaces.PARTICLES), "nothing closes until the user answers");
        assertEquals(BuiltinWorkspaces.PARTICLES, tabs.current(), "it came forward to ask");
    }

    @Test
    void openingAnFxFileOpensTheTabOnIt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("boom.omfx");
        FxEditorDocument.create("explosion").save(file);
        editor.openFile(file);
        assertTrue(tabs.isOpen(BuiltinWorkspaces.PARTICLES));
        assertEquals("explosion", doc().id());
        assertEquals(file, doc().file().orElseThrow());
        assertTrue(doc().system().emitters().size() > 1, "a multi-emitter system");
    }

    @Test
    void aV1LibraryOffersItsEffectsAsNewDocuments(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mine.omfx");
        Files.writeString(file, "{ \"effects\": [ { \"name\": \"glow\", \"additive\": 1 }, { \"name\": \"puff\" } ] }");
        editor.openFile(file);
        assertEquals(List.of("glow", "puff"), editor.migrationChoices());
        editor.openMigrated("puff");
        assertEquals("puff", doc().id());
        assertTrue(doc().file().isEmpty(), "never saved over the v1 library");
        assertTrue(editor.migrationChoices().isEmpty());
    }

    @Test
    void openingAFileOverUnsavedChangesWaitsForTheAnswer(@TempDir Path dir) throws Exception {
        editor.show();
        doc().addEmitter("sparks");
        Path file = dir.resolve("other.omfx");
        FxEditorDocument.create("smoke").save(file);
        editor.openFile(file);
        assertEquals(ParticleEditorWorkspace.DEFAULT_PRESET, doc().id(), "the unsaved document is not replaced yet");
    }

    @Test
    void undoAndRedoGoThroughTheTab() {
        editor.show();
        int before = doc().system().emitters().size();
        doc().addEmitter("sparks");
        editor.undo();
        assertEquals(before, doc().system().emitters().size());
        editor.redo();
        assertEquals(before + 1, doc().system().emitters().size());
    }

    @Test
    void refusedEditsBecomeTheErrorLineNotExceptions() {
        editor.show();
        editor.context().apply(() -> doc().removeEmitter("no-such-emitter"));
        assertTrue(editor.context().error().contains("no-such-emitter"));
        editor.context().apply(() -> doc().addEmitter("smoke"));
        assertNull(editor.context().error(), "a successful edit clears it");
    }

    @Test
    void exportFxDeploysTheWholeSystemWithItsSpriteFiles(@TempDir Path dir) throws Exception {
        Path lib = dir.resolve("lib/mine.omfx");
        Files.createDirectories(lib.getParent());
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(2, 2,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javax.imageio.ImageIO.write(img, "png", lib.resolveSibling("dot.png").toFile());
        FxEditorDocument d = FxEditorDocument.create("smoke");
        ParticleEffect smoke = d.effect("smoke");
        d.putEffect(null, "smoke", smoke.toBuilder().textures(List.of("dot.png", "gone.png"), false).build());
        d.save(lib);
        editor.openFile(lib);

        Path game = Files.createDirectories(dir.resolve("game"));
        com.openmason.api.target.GameTarget testGame = com.openmason.api.target.GameTarget.builder("testgame", "Test")
                .resources(com.openmason.api.target.ResourceRoot.at(game))
                .folders(com.openmason.api.target.AssetFolders.builder().base("tfx", "fx").build()).build();
        com.openmason.api.fx.FxExportTarget container = new com.openmason.api.fx.FxExportTarget("testgame.tfx", null,
                com.openmason.api.files.FileType.of("tfx", "Test FX", ".tfx").withSave("target", "tfx", "fx"),
                "testgame", in -> (in.id() + " " + in.sprites().keySet() + " " + in.externalTextures())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var targets = com.openmason.main.systems.target.GameTargets.shared();
        targets.setSource(() -> java.util.List.of(testGame));
        targets.projectOpened("testgame");
        com.openmason.main.systems.particles.export.FxExportService.setSource(() -> java.util.List.of(container));
        try {
            editor.openExport();
            var export = editor.exportController();
            assertTrue(export.isOpen());
            assertEquals("Test FX (.tfx)", export.target().displayName());
            assertTrue(export.notes().get(1).contains("gone.png"), "missing sprite files are reported");
            export.deploy();
            assertFalse(export.failed(), export.message());
            assertEquals("smoke [dot.png] [gone.png]", Files.readString(game.resolve("fx/smoke.tfx")));
        } finally {
            com.openmason.main.systems.particles.export.FxExportService.setSource(null);
            targets.projectClosed();
            targets.setSource(java.util.List::of);
        }
    }
}
