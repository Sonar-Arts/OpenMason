package com.openmason.main.systems.character;

import com.openmason.api.workspace.WorkspaceDock;
import com.openmason.main.systems.io.BuiltinFileTypes;
import com.openmason.main.systems.layout.BuiltinWorkspaces;
import com.openmason.main.systems.layout.WorkspaceState;
import com.openmason.main.systems.menus.dialogs.FileDialogService;
import com.openmason.main.systems.menus.dialogs.ModalDialogs;
import com.openmason.main.systems.services.StatusService;
import imgui.ImGui;
import imgui.flag.ImGuiDir;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * The Character workspace (OpenMason #163): a closable tab building {@code .omchar} character definitions - Maya
 * HumanIK's Character Controls (Definition), Unreal's IK Rig editor and Godot's BoneMap editor in one.
 *
 * <p>Docked panels, arranged by {@link #buildDefaultLayout}:
 * <ul>
 *   <li>{@value #DEFINITION_WINDOW} - profile, facing, Auto-Map, Mirror, Lock, and the profile's body diagram
 *       with role slots coloured by state (pick a slot, then a joint);</li>
 *   <li>{@value #HIERARCHY_WINDOW} - the model's joints with their roles;</li>
 *   <li>{@value #VIEWPORT_WINDOW} - the model in the reference pose with joints by role, chains, sockets, eye and
 *       hip lines and the capsule (central);</li>
 *   <li>{@value #POSE_WINDOW} - fit a profile pose (T, A...), rotate joints by hand;</li>
 *   <li>{@value #CHAINS_WINDOW} - retarget chains and IK goals;</li>
 *   <li>{@value #SOCKETS_WINDOW} - standard and custom sockets;</li>
 *   <li>{@value #METRICS_WINDOW} - height, eye height, width, hip height, overrides;</li>
 *   <li>{@value #VALIDATION_WINDOW} - the live report.</li>
 * </ul>
 * Thin ImGui over native functionality (Java feature freeze): edits are {@code cc_*} setters run through
 * {@link CharacterDocument#edit} (one native undo step each; drags merge into one), and every check, layout and
 * pick comes from Cenda. The open character is shared with the {@code char_*} MCP tools ({@link CharacterSessions}).
 * Like every closable tab it ends only on the user's close, which asks about unsaved changes. ASCII strings only.
 */
public final class CharacterWorkspace implements WorkspaceDock {

    private static final Logger logger = LoggerFactory.getLogger(CharacterWorkspace.class);

    public static final String ID = BuiltinWorkspaces.CHARACTER;
    /** Bump when the curated default layout changes, to force a one-time rebuild. */
    public static final int LAYOUT_VERSION = 1;

    static final String DEFINITION_WINDOW = "Characterization";
    static final String HIERARCHY_WINDOW = "Character Hierarchy";
    static final String VIEWPORT_WINDOW = "Character Viewport";
    static final String POSE_WINDOW = "Reference Pose";
    static final String CHAINS_WINDOW = "Chains and Goals";
    static final String SOCKETS_WINDOW = "Character Sockets";
    static final String METRICS_WINDOW = "Character Metrics";
    static final String VALIDATION_WINDOW = "Character Validation";
    /** Every docked panel {@link #renderPanels} submits; {@link #buildDefaultLayout} places each one. */
    static final List<String> PANEL_WINDOWS = List.of(DEFINITION_WINDOW, HIERARCHY_WINDOW, VIEWPORT_WINDOW,
            POSE_WINDOW, CHAINS_WINDOW, SOCKETS_WINDOW, METRICS_WINDOW, VALIDATION_WINDOW);
    private static final String UNSAVED_POPUP = "Unsaved Character##characterUnsaved";

    private final FileDialogService fileDialogs;
    private final StatusService status;
    private final WorkspaceState workspaces;
    private final CharacterSessions sessions;
    private final CharacterPanels panels;
    private Runnable resetLayout = () -> { };
    private CharacterViewport viewport;
    private CharacterDocument framedFor;
    private float pressX;
    private float pressY;

    private Runnable pendingAfterUnsaved;
    private boolean unsavedRequested;
    private boolean wasShown;

    public CharacterWorkspace(FileDialogService fileDialogs, StatusService status, WorkspaceState workspaces,
                              CharacterSessions sessions) {
        this.fileDialogs = fileDialogs;
        this.status = status;
        this.workspaces = workspaces;
        this.sessions = sessions;
        this.panels = new CharacterPanels(sessions);
        sessions.setOnOpened(doc -> {
            panels.reset();
            if (workspaces != null) {
                show();
            }
        });
    }

    public void setResetLayoutAction(Runnable resetLayout) {
        this.resetLayout = resetLayout == null ? () -> { } : resetLayout;
    }

    /** Opens the tab (or brings it forward). */
    public void show() {
        workspaces.open(ID);
    }

    public boolean isOpen() {
        return workspaces.isOpen(ID);
    }

    /** Opens an .omchar in the tab (file opener / Open...); asks first when the open character is unsaved. */
    public void openFile(Path path) {
        show();
        guardUnsaved(() -> {
            try {
                sessions.replace(CharacterDocument.open(path, sessions.projectRoot()));
                status.updateStatus("Opened character " + path.getFileName());
            } catch (Exception e) {
                logger.warn("Could not open character {}", path, e);
                panels.setError("Could not open " + path.getFileName() + ": " + e.getMessage());
            }
        });
    }

    /** Starts a character for a model file (any format the engine model loader reads). */
    public void newFromModel(Path model) {
        show();
        guardUnsaved(() -> {
            try {
                sessions.replace(CharacterDocument.fromModel(model));
                status.updateStatus("New character for " + model.getFileName());
            } catch (Exception e) {
                logger.warn("Could not load model {}", model, e);
                panels.setError("Could not load " + model.getFileName() + ": " + e.getMessage());
            }
        });
    }

    CharacterPanels panels() {
        return panels;
    }

    // ------------------------------------------------------------------------------------------------
    // WorkspaceDock

    /** Characterization + Hierarchy | Viewport over Validation | Pose / Chains / Sockets / Metrics tabs. */
    @Override
    public void buildDefaultLayout(int dockspaceId, float width, float height) {
        if (width <= 0 || height <= 0) {
            width = 1400;
            height = 860;
        }
        imgui.internal.ImGui.dockBuilderRemoveNode(dockspaceId);
        imgui.internal.ImGui.dockBuilderAddNode(dockspaceId, imgui.internal.flag.ImGuiDockNodeFlags.DockSpace);
        imgui.internal.ImGui.dockBuilderSetNodeSize(dockspaceId, width, height);
        ImInt left = new ImInt(), rest = new ImInt(), right = new ImInt(), center = new ImInt();
        ImInt leftTop = new ImInt(), leftBottom = new ImInt(), centerTop = new ImInt(), centerBottom = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(dockspaceId, ImGuiDir.Left, 0.28f, left, rest);
        imgui.internal.ImGui.dockBuilderSplitNode(rest.get(), ImGuiDir.Right, 0.33f, right, center);
        imgui.internal.ImGui.dockBuilderSplitNode(left.get(), ImGuiDir.Up, 0.55f, leftTop, leftBottom);
        // The viewport stays the dockspace's one central node: split the report off BELOW it.
        imgui.internal.ImGui.dockBuilderSplitNode(center.get(), ImGuiDir.Down, 0.26f, centerBottom, centerTop);
        imgui.internal.ImGui.dockBuilderDockWindow(DEFINITION_WINDOW, leftTop.get());
        imgui.internal.ImGui.dockBuilderDockWindow(HIERARCHY_WINDOW, leftBottom.get());
        imgui.internal.ImGui.dockBuilderDockWindow(VIEWPORT_WINDOW, centerTop.get());
        imgui.internal.ImGui.dockBuilderDockWindow(VALIDATION_WINDOW, centerBottom.get());
        imgui.internal.ImGui.dockBuilderDockWindow(POSE_WINDOW, right.get());
        imgui.internal.ImGui.dockBuilderDockWindow(CHAINS_WINDOW, right.get());
        imgui.internal.ImGui.dockBuilderDockWindow(SOCKETS_WINDOW, right.get());
        imgui.internal.ImGui.dockBuilderDockWindow(METRICS_WINDOW, right.get());
        var node = imgui.internal.ImGui.dockBuilderGetNode(centerTop.get());
        if (node != null) {
            node.addLocalFlags(imgui.internal.flag.ImGuiDockNodeFlags.CentralNode);
        }
        imgui.internal.ImGui.dockBuilderFinish(dockspaceId);
    }

    @Override
    public List<String> panelWindows() {
        return PANEL_WINDOWS;
    }

    @Override
    public void renderHeader() {
        if (ImGui.button("New from Model...") && fileDialogs != null) {
            fileDialogs.showOpenAttachableModelDialog(p -> newFromModel(Path.of(p)));
        }
        CharacterPanels.hint("Characterize a model (.omo, or any model container a plugin reads)");
        ImGui.sameLine();
        if (ImGui.button("Open...") && fileDialogs != null) {
            fileDialogs.showOpenDialog(FileDialogService.StartIn.TYPE_FOLDER, p -> openFile(Path.of(p)),
                    BuiltinFileTypes.OMCHAR);
        }
        CharacterDocument doc = sessions.current().orElse(null);
        if (doc == null) ImGui.beginDisabled();
        ImGui.sameLine();
        if (ImGui.button("Save")) {
            save(doc == null ? null : doc.file().orElse(null), null);
        }
        ImGui.sameLine();
        if (ImGui.button("Save As...")) {
            save(null, null);
        }
        ImGui.sameLine();
        ImGui.textDisabled("|");
        ImGui.sameLine();
        boolean canUndo = doc != null && doc.session().canUndo();
        boolean canRedo = doc != null && doc.session().canRedo();
        if (!canUndo) ImGui.beginDisabled();
        if (ImGui.button("Undo")) {
            panels.undo();
        }
        if (canUndo && ImGui.isItemHovered()) ImGui.setTooltip("Undo " + doc.session().undoLabel());
        if (!canUndo) ImGui.endDisabled();
        ImGui.sameLine();
        if (!canRedo) ImGui.beginDisabled();
        if (ImGui.button("Redo")) {
            panels.redo();
        }
        if (canRedo && ImGui.isItemHovered()) ImGui.setTooltip("Redo " + doc.session().redoLabel());
        if (!canRedo) ImGui.endDisabled();
        if (doc == null) ImGui.endDisabled();
        ImGui.sameLine();
        ImGui.textDisabled("|");
        ImGui.sameLine();
        if (ImGui.button("Reset Layout")) {
            resetLayout.run();
        }
        ImGui.sameLine();
        if (doc == null) {
            ImGui.textDisabled("No character open");
        } else {
            ImGui.textDisabled(doc.displayName() + "  -  " + doc.file().map(p -> p.getFileName().toString()).orElse("(unsaved)")
                    + (doc.isDirty() ? " *" : "") + (doc.definition().locked() ? "  [locked]" : ""));
        }
    }

    @Override
    public void renderPanels(float deltaTime, boolean shown) {
        if (!shown) {
            wasShown = false;
            if (!isOpen()) {
                onClosed();
            }
            return;
        }
        handleShortcuts();
        CharacterDocument doc = sessions.current().orElse(null);

        if (ImGui.begin(DEFINITION_WINDOW)) {
            panels.renderMessages();
            if (doc == null) {
                renderEmpty();
            } else {
                panels.renderDefinition(doc);
            }
        }
        ImGui.end();
        if (ImGui.begin(HIERARCHY_WINDOW)) {
            if (doc != null) panels.renderHierarchy(doc);
        }
        ImGui.end();
        if (ImGui.begin(VIEWPORT_WINDOW, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse)) {
            if (doc != null) renderViewport(doc);
        }
        ImGui.end();
        if (ImGui.begin(POSE_WINDOW)) {
            if (doc != null) panels.renderPose(doc);
        }
        ImGui.end();
        if (ImGui.begin(CHAINS_WINDOW)) {
            if (doc != null) panels.renderChains(doc);
        }
        ImGui.end();
        if (ImGui.begin(SOCKETS_WINDOW)) {
            if (doc != null) panels.renderSockets(doc);
        }
        ImGui.end();
        if (ImGui.begin(METRICS_WINDOW)) {
            if (doc != null) panels.renderMetrics(doc, viewport);
        }
        ImGui.end();
        if (ImGui.begin(VALIDATION_WINDOW)) {
            if (doc != null) panels.renderValidation(doc);
        }
        ImGui.end();

        if (doc != null && !ImGui.isAnyItemActive()) {
            doc.session().seal(); // a drag (if any) ended: the next edit is its own step
        }
        if (!wasShown) {
            wasShown = true;
            ImGui.setWindowFocus(VIEWPORT_WINDOW);
        }
        renderUnsavedDialog();
    }

    private void renderEmpty() {
        ImGui.textWrapped("No character is open. Characterize a model with New from Model..., or Open... an .omchar.");
        ImGui.spacing();
        if (ImGui.button("New from Model...##empty") && fileDialogs != null) {
            fileDialogs.showOpenAttachableModelDialog(p -> newFromModel(Path.of(p)));
        }
    }

    private void handleShortcuts() {
        var io = ImGui.getIO();
        if (io.getWantTextInput() || !io.getKeyCtrl()) {
            return;
        }
        if (ImGui.isKeyPressed(ImGuiKey.Z, false)) {
            if (io.getKeyShift()) panels.redo(); else panels.undo();
        } else if (ImGui.isKeyPressed(ImGuiKey.Y, false)) {
            panels.redo();
        } else if (ImGui.isKeyPressed(ImGuiKey.S, false)) {
            sessions.current().ifPresent(d -> save(d.file().orElse(null), null));
        }
    }

    private void renderViewport(CharacterDocument doc) {
        if (viewport == null) {
            try {
                viewport = new CharacterViewport();
            } catch (RuntimeException e) {
                ImGui.textWrapped("Viewport unavailable: " + e.getMessage());
                return;
            }
        }
        if (framedFor != doc) {
            viewport.frame(doc.boundsMin(), doc.boundsMax());
            framedFor = doc;
        }
        float width = ImGui.getContentRegionAvailX();
        float controls = ImGui.getFrameHeightWithSpacing() + 4f;
        float height = Math.max(120f, ImGui.getContentRegionAvailY() - controls);
        int texture;
        try {
            texture = viewport.render(doc, (int) width, (int) height, panels.selectedJoint(), panels.selectedSocket());
        } catch (RuntimeException e) {
            ImGui.textWrapped("Viewport: " + e.getMessage());
            return;
        }
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
        ImGui.image(texture, width, height, 0, 1, 1, 0);
        ImGui.setCursorScreenPos(x, y);
        ImGui.invisibleButton("##characterViewport", width, height);
        if (ImGui.isItemHovered() || ImGui.isItemActive()) {
            var io = ImGui.getIO();
            if (ImGui.isItemActive() && ImGui.isMouseDragging(0, 3f)) {
                viewport.orbit(io.getMouseDeltaX(), io.getMouseDeltaY());
            } else if (ImGui.isMouseDragging(2) || (ImGui.isMouseDragging(1))) {
                viewport.pan(io.getMouseDeltaX(), io.getMouseDeltaY());
            }
            if (io.getMouseWheel() != 0f) {
                viewport.zoom(io.getMouseWheel());
            }
            if (ImGui.isItemActivated()) {
                pressX = io.getMousePosX();
                pressY = io.getMousePosY();
            }
            float mdx = io.getMousePosX() - pressX, mdy = io.getMousePosY() - pressY;
            if (ImGui.isItemDeactivated() && mdx * mdx + mdy * mdy < 9f) {
                float[] ray = viewport.ray(io.getMousePosX() - x, io.getMousePosY() - y);
                if (ray != null) {
                    panels.pick(doc, doc.overlay().pick(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], 0.004f,
                            viewport.tolerancePerDistance(), true));
                }
            }
        }
        panels.renderViewportToggles(viewport);
    }

    // ------------------------------------------------------------------------------------------------
    // Files and dialogs

    private void save(Path path, Runnable after) {
        CharacterDocument doc = sessions.current().orElse(null);
        if (doc == null) {
            return;
        }
        if (path == null) {
            if (fileDialogs == null) {
                return;
            }
            String name = doc.file().map(p -> p.getFileName().toString()).orElse(doc.displayName() + ".omchar");
            fileDialogs.showSaveDialog(BuiltinFileTypes.OMCHAR, name, null, p -> save(Path.of(p), after));
            return;
        }
        try {
            doc.save(path);
            status.updateStatus("Saved character " + path.getFileName());
            if (after != null) {
                after.run();
            }
        } catch (Exception e) {
            logger.warn("Could not save character {}", path, e);
            panels.setError("Could not save: " + e.getMessage());
        }
    }

    @Override
    public boolean allowClose(Runnable proceed) {
        CharacterDocument doc = sessions.current().orElse(null);
        if (doc == null || !doc.isDirty()) {
            return true;
        }
        workspaces.set(ID);
        pendingAfterUnsaved = proceed;
        unsavedRequested = true;
        return false;
    }

    /** The tab closed: the character closes (MCP tools then have none until char_open). */
    private void onClosed() {
        sessions.close();
        panels.reset();
        framedFor = null;
        if (viewport != null) {
            viewport.close();
            viewport = null;
        }
    }

    private void guardUnsaved(Runnable action) {
        CharacterDocument doc = sessions.current().orElse(null);
        if (doc != null && doc.isDirty()) {
            pendingAfterUnsaved = action;
            unsavedRequested = true;
        } else {
            action.run();
        }
    }

    private void renderUnsavedDialog() {
        if (unsavedRequested) {
            ModalDialogs.openIfNeeded(UNSAVED_POPUP);
            unsavedRequested = false;
        }
        if (!ModalDialogs.begin(UNSAVED_POPUP, 380)) {
            return;
        }
        CharacterDocument doc = sessions.current().orElse(null);
        ImGui.textWrapped("The character '" + (doc == null ? "" : doc.displayName()) + "' has unsaved changes. Save them first?");
        ModalDialogs.buttonsBegin();
        if (ModalDialogs.primary("Save", true)) {
            Runnable after = takePending();
            ModalDialogs.close();
            save(doc == null ? null : doc.file().orElse(null), after);
        }
        ImGui.sameLine();
        if (ModalDialogs.danger("Don't Save", true)) {
            Runnable after = takePending();
            ModalDialogs.close();
            if (after != null) {
                after.run();
            }
        }
        ImGui.sameLine();
        if (ModalDialogs.cancel()) {
            pendingAfterUnsaved = null;
            ModalDialogs.close();
        }
        ModalDialogs.end();
    }

    private Runnable takePending() {
        Runnable r = pendingAfterUnsaved;
        pendingAfterUnsaved = null;
        return r;
    }

    /** Releases GL (render thread, at shutdown). */
    public void cleanup() {
        if (viewport != null) {
            viewport.close();
            viewport = null;
        }
    }
}
