package com.openmason.main.systems.layout;

import com.openmason.api.Contributions;
import com.openmason.api.workspace.WorkspaceDescriptor;
import com.openmason.api.workspace.WorkspaceDock;
import com.openmason.main.systems.menus.textureCreator.rendering.TextureEditorLayoutBuilder;
import com.openmason.main.systems.particles.ParticleEditorWorkspace;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.uiEditor.view.UiWorkspaceLayout;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiDir;
import imgui.flag.ImGuiWindowFlags;
import imgui.internal.ImGuiDockNode;
import imgui.internal.flag.ImGuiDockNodeFlags;
import imgui.type.ImInt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every editor ships a default layout (headless ImGui, no renderer): each workspace's default arrangement docks every
 * panel it declares in {@code panelWindows()} into its own dockspace, so no panel opens floating; and a stale saved
 * arrangement holding none of a workspace's panels (an {@code imgui.ini} from another checkout while the version
 * record says current) is replaced by the default instead of leaving the panels floating.
 */
class WorkspaceDefaultLayoutTest {

    private static final float W = 1400f, H = 860f;

    @BeforeEach
    void context() {
        ImGui.createContext();
        ImGuiIO io = ImGui.getIO();
        io.setIniFilename(null);
        io.addConfigFlags(ImGuiConfigFlags.DockingEnable);
        io.setDisplaySize(W, H);
        io.setDeltaTime(1f / 60f);
        io.getFonts().addFontDefault();
        io.getFonts().build();
    }

    @AfterEach
    void destroy() {
        ImGui.destroyContext();
    }

    /** One frame: the dockspace in a host window, {@code beforePanels} (the gate), then each panel submitted. */
    private Map<String, Integer> frame(List<String> panels, java.util.function.IntConsumer beforePanels) {
        ImGui.newFrame();
        ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
        ImGui.setNextWindowSize(W, H, ImGuiCond.Always);
        ImGui.begin("host", ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoSavedSettings);
        int dockspace = ImGui.getID("dockspace");
        ImGui.dockSpace(dockspace, 0f, 0f, imgui.flag.ImGuiDockNodeFlags.None);
        beforePanels.accept(dockspace);
        ImGui.end();
        Map<String, Integer> roots = new LinkedHashMap<>();
        for (String panel : panels) {
            ImGui.begin(panel);
            roots.put(panel, rootOf(ImGui.getWindowDockID()));
            ImGui.end();
        }
        ImGui.render();
        roots.put("##dockspace", dockspace);
        return roots;
    }

    /** The dockspace a docked window's node belongs to; 0 when floating. */
    private static int rootOf(int dockId) {
        if (dockId == 0) {
            return 0;
        }
        ImGuiDockNode node = imgui.internal.ImGui.dockBuilderGetNode(dockId);
        if (!node.isValidPtr()) {
            return 0;
        }
        while (node.getParentNode().isValidPtr()) {
            node = node.getParentNode();
        }
        return node.getID();
    }

    private void assertDocksEveryPanel(String workspace, List<String> panels, WorkspaceDock dock) {
        assertFalse(panels.isEmpty(), workspace + " declares its panels");
        boolean[] built = {false};
        Map<String, Integer> roots = Map.of();
        for (int i = 0; i < 3; i++) {
            roots = frame(panels, id -> {
                if (!built[0]) {
                    dock.buildDefaultLayout(id, W, H);
                    built[0] = true;
                }
            });
        }
        int dockspace = roots.get("##dockspace");
        for (String panel : panels) {
            assertEquals(dockspace, roots.get(panel), workspace + ": the default layout docks '" + panel + "'");
        }
    }

    private static ParticleEditorWorkspace particles() {
        Contributions<WorkspaceDescriptor> workspaces = WorkspaceFixtures.builtins();
        return new ParticleEditorWorkspace(null, new StatusService(), new WorkspaceState(workspaces));
    }

    @Test
    void theParticlesLayoutDocksEveryPanel() {
        ParticleEditorWorkspace fx = particles();
        assertDocksEveryPanel("Particles", fx.panelWindows(), fx);
    }

    @Test
    void theSceneLayoutDocksEveryPanel() {
        MainLayoutBuilder builder = new MainLayoutBuilder();
        assertDocksEveryPanel("Scene", MainLayoutBuilder.PANEL_WINDOWS, builder::build);
    }

    @Test
    void theTextureLayoutDocksEveryPanel() {
        TextureEditorLayoutBuilder builder = new TextureEditorLayoutBuilder();
        assertDocksEveryPanel("Texture", TextureEditorLayoutBuilder.PANEL_WINDOWS, builder::build);
    }

    @Test
    void theUiLayoutDocksEveryPanel() {
        UiWorkspaceLayout builder = new UiWorkspaceLayout();
        assertDocksEveryPanel("UI", UiWorkspaceLayout.PANEL_WINDOWS, builder::build);
    }

    /** In-memory version record that already says current. */
    private static final class CurrentStore implements WorkspaceLayouts.VersionStore {
        final Map<String, Integer> versions = new java.util.HashMap<>();

        @Override
        public int stored(String workspaceId) {
            return versions.getOrDefault(workspaceId, 0);
        }

        @Override
        public void store(String workspaceId, int version) {
            versions.put(workspaceId, version);
        }
    }

    @Test
    void aStaleArrangementHoldingNoneOfThePanelsIsReplacedByTheDefault() {
        Map<String, Integer> roots = staleArrangement(store -> new WorkspaceLayouts(store)); // production predicates
        int dockspace = roots.get("##dockspace");
        for (String panel : particles().panelWindows()) {
            assertEquals(dockspace, roots.get(panel), "'" + panel + "' is docked, not left floating");
        }
        assertTrue(WorkspaceLayouts.holdsDockedWindow(dockspace));
    }

    @Test
    void controlTheVersionRulesAloneLeaveThePanelsFloating() {
        Map<String, Integer> roots = staleArrangement(
                store -> new WorkspaceLayouts(store, WorkspaceLayouts::isSplitDockspace));
        for (String panel : particles().panelWindows()) {
            assertEquals(0, roots.get(panel), "the bug this guards against: '" + panel + "' floats");
        }
    }

    /**
     * The Particles workspace meets a split arrangement an older version saved (its windows are never submitted
     * again) while its version record already says current; returns where its panels end up after a few frames.
     */
    private Map<String, Integer> staleArrangement(java.util.function.Function<CurrentStore, WorkspaceLayouts> gate) {
        ParticleEditorWorkspace fx = particles();
        WorkspaceDescriptor ws = WorkspaceDescriptor.builder(ParticleEditorWorkspace.ID, "Particles")
                .closable(true).layoutVersion(ParticleEditorWorkspace.LAYOUT_VERSION).dock(fx).build();
        CurrentStore store = new CurrentStore();
        store.store(ws.id(), ws.layoutVersion());
        WorkspaceLayouts layouts = gate.apply(store);

        // An older version's split, as an imgui.ini restores it: its windows are never submitted again.
        frame(List.of(), id -> {
            imgui.internal.ImGui.dockBuilderRemoveNode(id);
            imgui.internal.ImGui.dockBuilderAddNode(id, ImGuiDockNodeFlags.DockSpace);
            imgui.internal.ImGui.dockBuilderSetNodeSize(id, W, H);
            ImInt left = new ImInt(), right = new ImInt();
            imgui.internal.ImGui.dockBuilderSplitNode(id, ImGuiDir.Left, 0.3f, left, right);
            imgui.internal.ImGui.dockBuilderDockWindow("Particle Effects", left.get());
            imgui.internal.ImGui.dockBuilderDockWindow("Particle Preview", right.get());
            imgui.internal.ImGui.dockBuilderFinish(id);
        });

        Map<String, Integer> roots = Map.of();
        for (int i = 0; i < 4; i++) {
            roots = frame(fx.panelWindows(), id -> layouts.applyIfNeeded(ws, id, W, H));
        }
        return roots;
    }
}
