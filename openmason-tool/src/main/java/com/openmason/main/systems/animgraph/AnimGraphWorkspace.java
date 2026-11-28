package com.openmason.main.systems.animgraph;

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
 * The Animation Graph workspace (OpenMason #164): a closable tab authoring and debugging {@code .omanimgraph}
 * animation graphs - Unreal's Anim Graph editor, Unity's Animator window and Godot's AnimationTree editor.
 *
 * <p>Docked panels ({@link #PANEL_WINDOWS}): the node graph (central), the state machine canvas, the blend-space
 * editor, parameters and slots (live preview values), the inspector, the preview viewport (a character posed by the
 * graph), the debugger (active states, transition progress, per-node influence and input weights, notifies,
 * curves) and the problems list. Thin ImGui over the native editable graph ({@code cg_doc}, one undo step per edit,
 * shared with the {@code animgraph_*} MCP tools through {@link AnimGraphSessions}) and the native runtime. ASCII only.
 */
public final class AnimGraphWorkspace implements WorkspaceDock, com.openmason.main.systems.mcp.PreviewViewport {

    private static final Logger logger = LoggerFactory.getLogger(AnimGraphWorkspace.class);

    public static final String ID = BuiltinWorkspaces.ANIMGRAPH;
    /** Bump when the curated default layout changes, to force a one-time rebuild. */
    public static final int LAYOUT_VERSION = 1;

    static final String GRAPH_WINDOW = "Anim Graph";
    static final String MACHINE_WINDOW = "State Machine";
    static final String BLEND_WINDOW = "Blend Space";
    static final String PARAMS_WINDOW = "Graph Parameters";
    static final String INSPECTOR_WINDOW = "Graph Inspector";
    static final String PREVIEW_WINDOW = "Graph Preview";
    static final String DEBUGGER_WINDOW = "Graph Debugger";
    static final String PROBLEMS_WINDOW = "Graph Problems";
    static final List<String> PANEL_WINDOWS = List.of(GRAPH_WINDOW, MACHINE_WINDOW, BLEND_WINDOW, PARAMS_WINDOW,
            INSPECTOR_WINDOW, PREVIEW_WINDOW, DEBUGGER_WINDOW, PROBLEMS_WINDOW);
    private static final String UNSAVED_POPUP = "Unsaved Animation Graph##animgraphUnsaved";

    private final FileDialogService fileDialogs;
    private final StatusService status;
    private final WorkspaceState workspaces;
    private final AnimGraphSessions sessions;
    private final AnimGraphEditorContext ctx;
    private final AnimGraphCanvas canvas;
    private final AnimGraphPanels panels;
    private Runnable resetLayout = () -> { };
    private AnimGraphPreview preview;
    private Object framedFor;
    private Runnable pendingAfterUnsaved;
    private boolean unsavedRequested;
    private boolean wasShown;

    public AnimGraphWorkspace(FileDialogService fileDialogs, StatusService status, WorkspaceState workspaces,
                              AnimGraphSessions sessions) {
        this.fileDialogs = fileDialogs;
        this.status = status;
        this.workspaces = workspaces;
        this.sessions = sessions;
        this.ctx = new AnimGraphEditorContext(sessions);
        this.canvas = new AnimGraphCanvas(ctx);
        this.panels = new AnimGraphPanels(ctx);
        sessions.setOnOpened(s -> {
            ctx.reset();
            canvas.resetFraming();
            framedFor = null;
            if (workspaces != null) show();
        });
    }

    public void setResetLayoutAction(Runnable resetLayout) {
        this.resetLayout = resetLayout == null ? () -> { } : resetLayout;
    }

    public void show() {
        workspaces.open(ID);
    }

    public boolean isOpen() {
        return workspaces.isOpen(ID);
    }

    public void openFile(Path path) {
        show();
        guardUnsaved(() -> {
            try {
                sessions.replace(AnimGraphSession.open(path));
                status.updateStatus("Opened animation graph " + path.getFileName());
            } catch (Exception e) {
                logger.warn("Could not open animation graph {}", path, e);
                ctx.error = "Could not open " + path.getFileName() + ": " + e.getMessage();
            }
        });
    }

    public void newGraph() {
        show();
        guardUnsaved(() -> sessions.replace(AnimGraphSession.create("New graph", "humanoid")));
    }

    AnimGraphEditorContext context() {
        return ctx;
    }

    // ------------------------------------------------------------------------------------------------

    /** Parameters / Inspector | Graph over State Machine + Blend Space | Preview over Debugger / Problems. */
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
        ImInt rightTop = new ImInt(), rightBottom = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(dockspaceId, ImGuiDir.Left, 0.22f, left, rest);
        imgui.internal.ImGui.dockBuilderSplitNode(rest.get(), ImGuiDir.Right, 0.32f, right, center);
        imgui.internal.ImGui.dockBuilderSplitNode(left.get(), ImGuiDir.Up, 0.45f, leftTop, leftBottom);
        imgui.internal.ImGui.dockBuilderSplitNode(center.get(), ImGuiDir.Down, 0.38f, centerBottom, centerTop);
        imgui.internal.ImGui.dockBuilderSplitNode(right.get(), ImGuiDir.Up, 0.5f, rightTop, rightBottom);
        imgui.internal.ImGui.dockBuilderDockWindow(PARAMS_WINDOW, leftTop.get());
        imgui.internal.ImGui.dockBuilderDockWindow(INSPECTOR_WINDOW, leftBottom.get());
        imgui.internal.ImGui.dockBuilderDockWindow(GRAPH_WINDOW, centerTop.get());
        imgui.internal.ImGui.dockBuilderDockWindow(MACHINE_WINDOW, centerBottom.get());
        imgui.internal.ImGui.dockBuilderDockWindow(BLEND_WINDOW, centerBottom.get());
        imgui.internal.ImGui.dockBuilderDockWindow(PREVIEW_WINDOW, rightTop.get());
        imgui.internal.ImGui.dockBuilderDockWindow(DEBUGGER_WINDOW, rightBottom.get());
        imgui.internal.ImGui.dockBuilderDockWindow(PROBLEMS_WINDOW, rightBottom.get());
        var node = imgui.internal.ImGui.dockBuilderGetNode(centerTop.get());
        if (node != null) node.addLocalFlags(imgui.internal.flag.ImGuiDockNodeFlags.CentralNode);
        imgui.internal.ImGui.dockBuilderFinish(dockspaceId);
    }

    @Override
    public List<String> panelWindows() {
        return PANEL_WINDOWS;
    }

    @Override
    public void renderHeader() {
        if (ImGui.button("New")) newGraph();
        ImGui.sameLine();
        if (ImGui.button("Open...") && fileDialogs != null) {
            fileDialogs.showOpenDialog(FileDialogService.StartIn.TYPE_FOLDER, p -> openFile(Path.of(p)), BuiltinFileTypes.OMANIMGRAPH);
        }
        AnimGraphSession s = sessions.current().orElse(null);
        if (s == null) ImGui.beginDisabled();
        ImGui.sameLine();
        if (ImGui.button("Save")) save(s == null ? null : s.file().orElse(null), null);
        ImGui.sameLine();
        if (ImGui.button("Save As...")) save(null, null);
        ImGui.sameLine();
        ImGui.textDisabled("|");
        boolean canUndo = s != null && s.document().canUndo();
        boolean canRedo = s != null && s.document().canRedo();
        ImGui.sameLine();
        if (!canUndo) ImGui.beginDisabled();
        if (ImGui.button("Undo")) undo();
        if (canUndo && ImGui.isItemHovered()) ImGui.setTooltip("Undo " + s.document().undoLabel());
        if (!canUndo) ImGui.endDisabled();
        ImGui.sameLine();
        if (!canRedo) ImGui.beginDisabled();
        if (ImGui.button("Redo")) redo();
        if (canRedo && ImGui.isItemHovered()) ImGui.setTooltip("Redo " + s.document().redoLabel());
        if (!canRedo) ImGui.endDisabled();
        ImGui.sameLine();
        if (ImGui.button("Auto Layout")) {
            ctx.apply("[{\"op\":\"layout\",\"onlyMissing\":false}]", "layout", null);
            canvas.resetFraming();
        }
        if (s == null) ImGui.endDisabled();
        ImGui.sameLine();
        if (ImGui.button("Reset Layout")) resetLayout.run();
        ImGui.sameLine();
        if (s == null) {
            ImGui.textDisabled("No graph open");
        } else {
            ImGui.textDisabled(s.displayName() + "  -  " + s.file().map(p -> p.getFileName().toString()).orElse("(unsaved)")
                    + (s.isDirty() ? " *" : "") + (s.document().valid() ? "" : "  [draft: has errors]"));
        }
    }

    @Override
    public void renderPanels(float deltaTime, boolean shown) {
        if (!shown) {
            wasShown = false;
            if (!isOpen()) onClosed();
            return;
        }
        handleShortcuts();
        AnimGraphSession s = sessions.current().orElse(null);
        ctx.tick(deltaTime);
        var view = ctx.view();

        if (ImGui.begin(GRAPH_WINDOW, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse)) {
            panels.renderMessages();
            if (s == null) {
                ImGui.textWrapped("No animation graph is open. New starts one; Open... reads an .omanimgraph.");
            } else {
                canvas.renderGraph(view, ImGui.getContentRegionAvailX(), ImGui.getContentRegionAvailY());
            }
        }
        ImGui.end();
        if (ImGui.begin(MACHINE_WINDOW, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse)) {
            if (s != null) canvas.renderMachine(view, ImGui.getContentRegionAvailX(), ImGui.getContentRegionAvailY());
        }
        ImGui.end();
        if (ImGui.begin(BLEND_WINDOW)) {
            if (s != null) panels.renderBlendSpace(ImGui.getContentRegionAvailX(), ImGui.getContentRegionAvailY());
        }
        ImGui.end();
        if (ImGui.begin(PARAMS_WINDOW)) {
            if (s != null) panels.renderParameters(s);
        }
        ImGui.end();
        if (ImGui.begin(INSPECTOR_WINDOW)) {
            if (s != null) panels.renderInspector();
        }
        ImGui.end();
        if (ImGui.begin(PREVIEW_WINDOW, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse)) {
            if (s != null) renderPreview(s);
        }
        ImGui.end();
        if (ImGui.begin(DEBUGGER_WINDOW)) {
            if (s != null) panels.renderDebugger();
        }
        ImGui.end();
        if (ImGui.begin(PROBLEMS_WINDOW)) {
            if (s != null) panels.renderProblems();
        }
        ImGui.end();

        if (s != null && !ImGui.isAnyItemActive()) s.document().seal();
        if (!wasShown) {
            wasShown = true;
            ImGui.setWindowFocus(GRAPH_WINDOW);
        }
        renderUnsavedDialog();
    }

    private void renderPreview(AnimGraphSession s) {
        panels.renderPreviewSetup(s,
                fileDialogs == null ? null : () -> fileDialogs.showOpenAttachableModelDialog(p -> setCharacter(s, p)),
                fileDialogs == null ? null : slot -> fileDialogs.showOpenOMADialog(p -> setClip(s, slot, p)));
        String problem = s.previewProblem();
        if (problem != null) ImGui.textColored(0.95f, 0.8f, 0.4f, 1f, problem);
        if (preview == null) {
            try {
                preview = new AnimGraphPreview();
            } catch (RuntimeException e) {
                ImGui.textWrapped("Preview unavailable: " + e.getMessage());
                return;
            }
        }
        var character = s.character();
        if (character != null && framedFor != character) {
            preview.frame(character.boundsMin(), character.boundsMax());
            framedFor = character;
        }
        if (ImGui.checkbox("Eyes", preview.eyes)) {
            preview.eyes = !preview.eyes;
            s.setEyes(preview.eyes);
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("First person from the character's eyes: the graph's first-person view layer, arms only");
        }
        if (preview.eyes) {
            ImGui.sameLine();
            String eyesProblem = preview.eyesProblem();
            ImGui.textDisabled(eyesProblem != null ? "Eyes: " + eyesProblem : String.format(
                    "LMB: look (yaw %.0f, pitch %.0f)  wheel: FOV %.0f", preview.eyesView.yaw(), preview.eyesView.pitch(),
                    preview.eyesView.fov()));
        }
        float w = ImGui.getContentRegionAvailX(), h = Math.max(100, ImGui.getContentRegionAvailY());
        int tex = renderPreviewFrame(s, (int) w, (int) h);
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
        ImGui.image(tex, w, h, 0, 1, 1, 0);
        ImGui.setCursorScreenPos(x, y);
        ImGui.invisibleButton("##graphPreview", w, h);
        var io = ImGui.getIO();
        if (preview.eyes) {
            if (ImGui.isItemActive() && ImGui.isMouseDragging(0, 1f)) preview.eyesView.drag(io.getMouseDeltaX(), io.getMouseDeltaY());
            if (ImGui.isItemHovered() && io.getMouseWheel() != 0) preview.eyesView.setFov(preview.eyesView.fov() - io.getMouseWheel() * 2f);
            return;
        }
        if (ImGui.isItemActive() && ImGui.isMouseDragging(0)) preview.orbit(io.getMouseDeltaX(), io.getMouseDeltaY());
        if (ImGui.isItemHovered() && io.getMouseWheel() != 0) preview.zoom(io.getMouseWheel());
    }

    /** One preview frame into the preview's texture (also what an MCP capture renders); 0 when there is no preview. */
    int renderPreviewFrame(AnimGraphSession s, int w, int h) {
        if (preview == null) return 0;
        s.setEyes(preview.eyes);  // a newly opened graph follows the preview's Eyes switch
        var character = s.character();
        String problem = s.previewProblem();
        AnimGraphPreview.EyesInput eyesInput = null;
        if (character != null) {
            try {
                eyesInput = new AnimGraphPreview.EyesInput(character.rig(), character.definition(), s::composeFirstPerson);
            } catch (RuntimeException e) {
                eyesInput = null;
            }
        }
        return preview.render(character == null ? null : character.geometry(), problem == null ? s.palette() : null,
                w, h, eyesInput);
    }

    // ------------------------------------------------------------------------------------------------
    // MCP: capture + Eyes (PreviewViewport)

    @Override
    public String viewName() {
        return "animgraph";
    }

    @Override
    public int[] renderFrame(int width, int height) {
        AnimGraphSession s = sessions.current().orElse(null);
        if (s == null || preview() == null) return null;
        var character = s.character();
        if (character != null && framedFor != character) {
            preview.frame(character.boundsMin(), character.boundsMax());
            framedFor = character;
        }
        s.step(0f);  // builds the instance and poses the current frame; no time passes
        int w = width > 0 ? width : preview.width() > 0 ? preview.width() : 1024;
        int h = height > 0 ? height : preview.height() > 0 ? preview.height() : 576;
        int tex = renderPreviewFrame(s, w, h);
        return new int[] {tex, preview.width(), preview.height()};
    }

    @Override
    public java.util.Map<String, Object> eyes(Boolean enabled, Float yaw, Float pitch, Float fov, Boolean armsFollowLook,
                                              boolean reset) {
        if (preview() == null) throw new IllegalStateException("the graph preview is unavailable");
        if (enabled != null) {
            preview.eyes = enabled;
            sessions.current().ifPresent(s -> s.setEyes(enabled));
        }
        com.openmason.main.systems.mcp.PreviewViewport.applyEyes(preview.eyesView, yaw, pitch, fov, armsFollowLook, reset);
        if (preview.eyes && sessions.current().isPresent()) renderFrame(0, 0);
        return com.openmason.main.systems.mcp.PreviewViewport.eyesState(preview.eyes, preview.eyesView,
                preview.eyes ? preview.eyesProblem() : null);
    }

    /** The preview viewport, created on first use (null when it cannot be). */
    AnimGraphPreview preview() {
        if (preview == null) {
            try {
                preview = new AnimGraphPreview();
            } catch (RuntimeException e) {
                return null;
            }
        }
        return preview;
    }

    private void setCharacter(AnimGraphSession s, String path) {
        try {
            s.setPreview(path, null);
        } catch (RuntimeException e) {
            ctx.error = e.getMessage();
        }
    }

    private void setClip(AnimGraphSession s, String slot, String path) {
        try {
            java.util.Map<String, String> m = new java.util.HashMap<>();
            m.put(slot, "file:" + path);
            s.setPreview(null, m);
        } catch (RuntimeException e) {
            ctx.error = e.getMessage();
        }
    }

    private void handleShortcuts() {
        var io = ImGui.getIO();
        if (io.getWantTextInput() || !io.getKeyCtrl()) return;
        if (ImGui.isKeyPressed(ImGuiKey.Z, false)) {
            if (io.getKeyShift()) redo(); else undo();
        } else if (ImGui.isKeyPressed(ImGuiKey.Y, false)) {
            redo();
        } else if (ImGui.isKeyPressed(ImGuiKey.S, false)) {
            sessions.current().ifPresent(g -> save(g.file().orElse(null), null));
        }
    }

    private void undo() {
        sessions.current().ifPresent(AnimGraphSession::undo);
    }

    private void redo() {
        sessions.current().ifPresent(AnimGraphSession::redo);
    }

    private void save(Path path, Runnable after) {
        AnimGraphSession s = sessions.current().orElse(null);
        if (s == null) return;
        if (path == null) {
            if (fileDialogs == null) return;
            String name = s.file().map(p -> p.getFileName().toString()).orElse(s.displayName() + ".omanimgraph");
            fileDialogs.showSaveDialog(BuiltinFileTypes.OMANIMGRAPH, name, null, p -> save(Path.of(p), after));
            return;
        }
        try {
            s.save(path);
            status.updateStatus("Saved animation graph " + path.getFileName() + (s.document().valid() ? "" : " (draft: it has errors)"));
            if (after != null) after.run();
        } catch (Exception e) {
            logger.warn("Could not save animation graph {}", path, e);
            ctx.error = "Could not save: " + e.getMessage();
        }
    }

    @Override
    public boolean allowClose(Runnable proceed) {
        AnimGraphSession s = sessions.current().orElse(null);
        if (s == null || !s.isDirty()) return true;
        workspaces.set(ID);
        pendingAfterUnsaved = proceed;
        unsavedRequested = true;
        return false;
    }

    private void onClosed() {
        sessions.close();
        ctx.reset();
        framedFor = null;
        if (preview != null) {
            preview.close();
            preview = null;
        }
    }

    private void guardUnsaved(Runnable action) {
        AnimGraphSession s = sessions.current().orElse(null);
        if (s != null && s.isDirty()) {
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
        if (!ModalDialogs.begin(UNSAVED_POPUP, 380)) return;
        AnimGraphSession s = sessions.current().orElse(null);
        ImGui.textWrapped("The animation graph '" + (s == null ? "" : s.displayName()) + "' has unsaved changes. Save them first?");
        ModalDialogs.buttonsBegin();
        if (ModalDialogs.primary("Save", true)) {
            Runnable after = takePending();
            ModalDialogs.close();
            save(s == null ? null : s.file().orElse(null), after);
        }
        ImGui.sameLine();
        if (ModalDialogs.danger("Don't Save", true)) {
            Runnable after = takePending();
            ModalDialogs.close();
            if (after != null) after.run();
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

    public void cleanup() {
        if (preview != null) {
            preview.close();
            preview = null;
        }
    }
}
