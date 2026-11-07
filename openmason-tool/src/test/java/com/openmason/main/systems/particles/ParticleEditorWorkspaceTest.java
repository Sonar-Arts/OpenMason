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
}
