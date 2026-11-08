package com.openmason.main.systems.particles;

import com.openmason.api.workspace.WorkspaceDescriptor;
import com.openmason.api.Contributions;
import com.openmason.main.systems.layout.BuiltinWorkspaces;
import com.openmason.main.systems.layout.WorkspaceFixtures;
import com.openmason.main.systems.layout.WorkspaceState;
import com.openmason.main.systems.services.StatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Particle Editor follows the workspace-tab framework: a closable tab that Tools / file openers open and
 * bring forward, that ends only on the user's close, and whose close waits on unsaved changes. GL- and
 * ImGui-free (the guard and the open/close paths never draw).
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

    @Test
    void theTabExistsOnlyOnceOpened() {
        assertFalse(tabs.isOpen(BuiltinWorkspaces.PARTICLES));
        assertFalse(editor.isOpen());
        editor.show();
        assertTrue(tabs.isOpen(BuiltinWorkspaces.PARTICLES));
        assertEquals(BuiltinWorkspaces.PARTICLES, tabs.current(), "opens at the front");
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
        assertTrue(tabs.requestClose(BuiltinWorkspaces.PARTICLES));
        assertFalse(tabs.isOpen(BuiltinWorkspaces.PARTICLES));
        assertEquals(BuiltinWorkspaces.MODELING, tabs.current(), "falls back to Scene");
    }

    @Test
    void unsavedChangesHoldTheTabOpenAndBringItForwardToAsk() {
        editor.show();
        editor.model().create("mine");
        tabs.set(BuiltinWorkspaces.UI);
        assertFalse(tabs.requestClose(BuiltinWorkspaces.PARTICLES), "the guard takes over");
        assertTrue(tabs.isOpen(BuiltinWorkspaces.PARTICLES), "nothing closes until the user answers");
        assertEquals(BuiltinWorkspaces.PARTICLES, tabs.current(), "it came forward to ask");
    }

    @Test
    void openingALibraryFileOpensTheTabOnIt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mine.omfx");
        Files.writeString(file, "{ \"effects\": [ { \"name\": \"glow\", \"additive\": 1 } ] }");
        editor.openFile(file);
        assertTrue(tabs.isOpen(BuiltinWorkspaces.PARTICLES));
        assertEquals(java.util.List.of("glow"), editor.model().names());
        assertEquals(file, editor.model().file().orElseThrow());
    }

    @Test
    void openingAFileOverUnsavedChangesWaitsForTheAnswer(@TempDir Path dir) throws Exception {
        editor.show();
        editor.model().create("mine");
        Path file = dir.resolve("other.omfx");
        Files.writeString(file, "{ \"effects\": [ { \"name\": \"glow\" } ] }");
        editor.openFile(file);
        assertTrue(editor.model().library().contains("mine"), "the unsaved document is not replaced yet");
    }

    @Test
    void exportFxListsTheGamesContainerAndDeploysTheSelectedEffectWithItsSprites(@TempDir Path dir) throws Exception {
        // a library with an effect whose sprite file sits next to it
        Path lib = dir.resolve("lib/mine.omfx");
        Files.createDirectories(lib.getParent());
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(2, 2,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javax.imageio.ImageIO.write(img, "png", lib.resolveSibling("dot.png").toFile());
        Files.writeString(lib, "{ \"effects\": [ { \"name\": \"glow\", \"sprite\": \"texture\", "
                + "\"textures\": [\"dot.png\", \"gone.png\"] } ] }");
        editor.openFile(lib);
        editor.model().select("glow");

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
            editor.openExport(editor.model().selected().orElseThrow());
            var export = editor.exportController();
            assertTrue(export.isOpen());
            assertEquals("Test FX (.tfx)", export.target().displayName());
            assertTrue(export.notes().get(1).contains("gone.png"), "missing sprite files are reported");
            export.deploy();
            assertFalse(export.failed(), export.message());
            assertEquals("glow [dot.png] [gone.png]", Files.readString(game.resolve("fx/glow.tfx")));
        } finally {
            com.openmason.main.systems.particles.export.FxExportService.setSource(null);
            targets.projectClosed();
            targets.setSource(java.util.List::of);
        }
    }
}
