package com.openmason.main.systems.animation;

import com.fasterxml.jackson.databind.JsonNode;
import com.openmason.api.workspace.WorkspaceDock;
import com.openmason.engine.character.CharacterOverlay;
import com.openmason.engine.rig.JointPalette;
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
import imgui.type.ImString;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * The Animation workspace (OpenMason #165): a closable tab authoring .omanim clips - Maya's Animation Layer Editor
 * and HumanIK control rig, Unreal's notify / curve tracks and retarget preview, over the native authoring session
 * ({@code cm_author_*}). USER DECISIONS 2026-11-25: native authoring + this tab replace the floating Java editor;
 * authoring layers and effector keys live inside the .omanim and always bake into its joint tracks; the tab has its
 * OWN viewport (not the Scene's).
 *
 * <p>Docked panels, arranged by {@link #buildDefaultLayout}:
 * <ul>
 *   <li>{@value #VIEWPORT_WINDOW} - the animated model, skeleton in the current pose, IK targets to drag (central);</li>
 *   <li>{@value #TIMELINE_WINDOW} - transport and the dope sheet (notifies, markers, effectors, layer weight,
 *       joint tracks of the selected layer);</li>
 *   <li>{@value #JOINTS_WINDOW} and {@value #LAYERS_WINDOW} - the rig, and the layer stack;</li>
 *   <li>{@value #INSPECTOR_WINDOW}, {@value #RIG_WINDOW}, {@value #CLIP_WINDOW}, {@value #EVENTS_WINDOW},
 *       {@value #RETARGET_WINDOW} - keys, control rig, clip settings, events, retarget preview and bake.</li>
 * </ul>
 * The open clip is shared with the {@code anim_*} MCP tools ({@link AnimationSessions}). Ends only on the user's
 * close, which asks about unsaved changes. ASCII strings only.
 */
public final class AnimationWorkspace implements WorkspaceDock {

    private static final Logger logger = LoggerFactory.getLogger(AnimationWorkspace.class);

    public static final String ID = BuiltinWorkspaces.ANIMATION;
    /** Bump when the curated default layout changes, to force a one-time rebuild. */
    public static final int LAYOUT_VERSION = 1;

    static final String VIEWPORT_WINDOW = "Animation Viewport";
    static final String TIMELINE_WINDOW = "Animation Timeline";
    static final String JOINTS_WINDOW = "Animation Joints";
    static final String LAYERS_WINDOW = "Animation Layers";
    static final String INSPECTOR_WINDOW = "Key Inspector";
    static final String RIG_WINDOW = "Control Rig";
    static final String CLIP_WINDOW = "Animation Clip";
    static final String EVENTS_WINDOW = "Animation Events";
    static final String RETARGET_WINDOW = "Retarget";
    /** Every docked panel {@link #renderPanels} submits; {@link #buildDefaultLayout} places each one. */
    static final List<String> PANEL_WINDOWS = List.of(VIEWPORT_WINDOW, TIMELINE_WINDOW, JOINTS_WINDOW, LAYERS_WINDOW,
            INSPECTOR_WINDOW, RIG_WINDOW, CLIP_WINDOW, EVENTS_WINDOW, RETARGET_WINDOW);
    private static final String UNSAVED_POPUP = "Unsaved Animation##animationUnsaved";
    private static final float[] SKELETON = {0.55f, 0.85f, 1f, 0.9f};
    private static final float[] SELECTED = {1f, 0.75f, 0.2f, 1f};
    private static final float[] TARGET = {1f, 0.9f, 0.2f, 1f};
    private static final float[] POLE = {0.3f, 0.9f, 0.9f, 1f};

    private final FileDialogService fileDialogs;
    private final StatusService status;
    private final WorkspaceState workspaces;
    private final AnimationSessions sessions;
    private final AnimationPanels panels;
    private Runnable resetLayout = () -> { };
    private Supplier<Path> sceneModel = () -> null;
    private AnimationViewport viewport;
    private AnimationViewport sourceViewport;
    private AnimationSession framedFor;
    private RetargetSource retarget;
    private Path retargetCharacter;
    private final ImString retargetLayer = new ImString("Retarget", 64);
    private boolean retargetPreview = true;
    private float pressX;
    private float pressY;
    private String dragEffector;
    private final Vector3f dragPoint = new Vector3f();

    private Runnable pendingAfterUnsaved;
    private boolean unsavedRequested;
    private boolean wasShown;

    public AnimationWorkspace(FileDialogService fileDialogs, StatusService status, WorkspaceState workspaces,
                              AnimationSessions sessions) {
        this.fileDialogs = fileDialogs;
        this.status = status;
        this.workspaces = workspaces;
        this.sessions = sessions;
        this.panels = new AnimationPanels(sessions);
        sessions.setOnOpened(doc -> {
            panels.reset();
            if (workspaces != null) show();
        });
    }

    public void setResetLayoutAction(Runnable resetLayout) {
        this.resetLayout = resetLayout == null ? () -> { } : resetLayout;
    }

    /** The model file open in the Scene (offered when a clip names no model it can find). */
    public void setSceneModel(Supplier<Path> sceneModel) {
        this.sceneModel = sceneModel == null ? () -> null : sceneModel;
    }

    public void show() {
        workspaces.open(ID);
    }

    public boolean isOpen() {
        return workspaces.isOpen(ID);
    }

    /** Opens an .omanim (file opener / Open...): its model is the clip's modelRef, else the Scene's model, else asked. */
    public void openFile(Path path) {
        show();
        guardUnsaved(() -> {
            try {
                sessions.replace(AnimationSession.open(path, null, sessions.projectRoot()));
                status.updateStatus("Opened animation " + path.getFileName());
            } catch (Exception first) {
                Path scene = sceneModel.get();
                try {
                    if (scene == null) throw first;
                    sessions.replace(AnimationSession.open(path, scene, sessions.projectRoot()));
                    status.updateStatus("Opened animation " + path.getFileName() + " on " + scene.getFileName());
                } catch (Exception e) {
                    logger.info("Animation {} needs a model: {}", path, e.getMessage());
                    panels.setNote(e.getMessage() + " - choose the model or character it animates.");
                    if (fileDialogs != null) {
                        fileDialogs.showOpenDialog(FileDialogService.StartIn.PROJECT, p -> openFor(path, Path.of(p)),
                                BuiltinFileTypes.OMCHAR, BuiltinFileTypes.OMO);
                    }
                }
            }
        });
    }

    /** Opens a clip on a given model / character file. */
    public void openFor(Path clip, Path model) {
        show();
        guardUnsaved(() -> {
            try {
                sessions.replace(AnimationSession.open(clip, model, sessions.projectRoot()));
                status.updateStatus("Opened animation " + clip.getFileName());
            } catch (Exception e) {
                logger.warn("Could not open animation {}", clip, e);
                panels.setError("Could not open " + clip.getFileName() + ": " + e.getMessage());
            }
        });
    }

    /** A new clip for a model (.omo or a plugin container) or a character (.omchar: role-addressed tracks). */
    public void newFor(Path model) {
        show();
        guardUnsaved(() -> {
            try {
                sessions.replace(AnimationSession.create(model, "New Clip", 30f, 1f));
                status.updateStatus("New animation for " + model.getFileName());
            } catch (Exception e) {
                logger.warn("Could not load model {}", model, e);
                panels.setError("Could not load " + model.getFileName() + ": " + e.getMessage());
            }
        });
    }

    /**
     * Opens clip bytes from another editor (the plugin's "Edit in editor"): written to a temporary file next to
     * nothing, then opened on its modelRef or the Scene's model; Save asks where.
     */
    public void openBytes(byte[] bytes, String name) {
        show();
        guardUnsaved(() -> {
            try {
                Path tmp = Files.createTempFile("openmason-clip-", ".omanim");
                Files.write(tmp, bytes);
                AnimationSession s;
                try {
                    s = AnimationSession.open(tmp, null, sessions.projectRoot());
                } catch (Exception noRef) {
                    Path scene = sceneModel.get();
                    if (scene == null) throw noRef;
                    s = AnimationSession.open(tmp, scene, sessions.projectRoot());
                } finally {
                    Files.deleteIfExists(tmp);
                }
                s.detachFile();
                sessions.replace(s);
                status.updateStatus("Editing animation " + (name == null ? "" : name));
            } catch (Exception e) {
                logger.warn("Could not open the clip {}", name, e);
                panels.setError("Could not open " + name + ": " + e.getMessage());
            }
        });
    }

    AnimationPanels panels() {
        return panels;
    }

    // ------------------------------------------------------------------------------------------------
    // WorkspaceDock

    /** Joints / Layers | Viewport over Timeline | Inspector / Control Rig / Clip / Events / Retarget tabs. */
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
        imgui.internal.ImGui.dockBuilderSplitNode(dockspaceId, ImGuiDir.Left, 0.2f, left, rest);
        imgui.internal.ImGui.dockBuilderSplitNode(rest.get(), ImGuiDir.Right, 0.3f, right, center);
        imgui.internal.ImGui.dockBuilderSplitNode(left.get(), ImGuiDir.Up, 0.55f, leftTop, leftBottom);
        // The viewport stays the dockspace's one central node: split the timeline off BELOW it.
        imgui.internal.ImGui.dockBuilderSplitNode(center.get(), ImGuiDir.Down, 0.34f, centerBottom, centerTop);
        imgui.internal.ImGui.dockBuilderDockWindow(JOINTS_WINDOW, leftTop.get());
        imgui.internal.ImGui.dockBuilderDockWindow(LAYERS_WINDOW, leftBottom.get());
        imgui.internal.ImGui.dockBuilderDockWindow(VIEWPORT_WINDOW, centerTop.get());
        imgui.internal.ImGui.dockBuilderDockWindow(TIMELINE_WINDOW, centerBottom.get());
        imgui.internal.ImGui.dockBuilderDockWindow(INSPECTOR_WINDOW, right.get());
        imgui.internal.ImGui.dockBuilderDockWindow(RIG_WINDOW, right.get());
        imgui.internal.ImGui.dockBuilderDockWindow(CLIP_WINDOW, right.get());
        imgui.internal.ImGui.dockBuilderDockWindow(EVENTS_WINDOW, right.get());
        imgui.internal.ImGui.dockBuilderDockWindow(RETARGET_WINDOW, right.get());
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
        if (ImGui.button("New for Model...") && fileDialogs != null) {
            fileDialogs.showOpenAttachableModelDialog(p -> newFor(Path.of(p)));
        }
        AnimationPanels.hint("A new clip for a model (.omo, or any model container a plugin reads)");
        ImGui.sameLine();
        if (ImGui.button("New for Character...") && fileDialogs != null) {
            fileDialogs.showOpenDialog(FileDialogService.StartIn.TYPE_FOLDER, p -> newFor(Path.of(p)), BuiltinFileTypes.OMCHAR);
        }
        AnimationPanels.hint("A new clip for a characterized model (.omchar): IK by role, retargeting");
        ImGui.sameLine();
        if (ImGui.button("Open...") && fileDialogs != null) {
            fileDialogs.showOpenDialog(FileDialogService.StartIn.TYPE_FOLDER, p -> openFile(Path.of(p)), BuiltinFileTypes.OMANIM);
        }
        AnimationSession doc = sessions.current().orElse(null);
        if (doc == null) ImGui.beginDisabled();
        ImGui.sameLine();
        if (ImGui.button("Save")) save(doc == null ? null : doc.file().orElse(null), null);
        ImGui.sameLine();
        if (ImGui.button("Save As...")) save(null, null);
        ImGui.sameLine();
        ImGui.textDisabled("|");
        ImGui.sameLine();
        boolean canUndo = doc != null && doc.authoring().canUndo();
        boolean canRedo = doc != null && doc.authoring().canRedo();
        if (!canUndo) ImGui.beginDisabled();
        if (ImGui.button("Undo")) panels.undo();
        if (canUndo && ImGui.isItemHovered()) ImGui.setTooltip("Undo " + doc.authoring().undoLabel());
        if (!canUndo) ImGui.endDisabled();
        ImGui.sameLine();
        if (!canRedo) ImGui.beginDisabled();
        if (ImGui.button("Redo")) panels.redo();
        if (canRedo && ImGui.isItemHovered()) ImGui.setTooltip("Redo " + doc.authoring().redoLabel());
        if (!canRedo) ImGui.endDisabled();
        if (doc == null) ImGui.endDisabled();
        ImGui.sameLine();
        ImGui.textDisabled("|");
        ImGui.sameLine();
        if (ImGui.button("Reset Layout")) resetLayout.run();
        ImGui.sameLine();
        if (doc == null) {
            ImGui.textDisabled("No animation open");
        } else {
            ImGui.textDisabled(doc.displayName() + "  -  " + doc.file().map(p -> p.getFileName().toString()).orElse("(unsaved)")
                    + (doc.isDirty() ? " *" : "") + "  on " + doc.model().displayName());
        }
    }

    @Override
    public void renderPanels(float deltaTime, boolean shown) {
        if (!shown) {
            wasShown = false;
            if (!isOpen()) onClosed();
            return;
        }
        AnimationSession doc = sessions.current().orElse(null);
        handleShortcuts(doc);
        JointPalette palette = null;
        if (doc != null) {
            try {
                palette = doc.tick(deltaTime);
            } catch (RuntimeException e) {
                panels.setError("Pose: " + e.getMessage());
                doc.setPlaying(false);
            }
        }

        if (ImGui.begin(VIEWPORT_WINDOW, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse)) {
            panels.renderMessages();
            if (doc == null) renderEmpty();
            else renderViewport(doc, palette);
        }
        ImGui.end();
        if (ImGui.begin(TIMELINE_WINDOW, ImGuiWindowFlags.NoScrollbar)) {
            if (doc != null) panels.renderTimeline(doc);
        }
        ImGui.end();
        if (ImGui.begin(JOINTS_WINDOW)) {
            if (doc != null) panels.renderJoints(doc);
        }
        ImGui.end();
        if (ImGui.begin(LAYERS_WINDOW)) {
            if (doc != null) panels.renderLayers(doc);
        }
        ImGui.end();
        if (ImGui.begin(INSPECTOR_WINDOW)) {
            if (doc != null) panels.renderInspector(doc);
        }
        ImGui.end();
        if (ImGui.begin(RIG_WINDOW)) {
            if (doc != null) panels.renderControlRig(doc);
        }
        ImGui.end();
        if (ImGui.begin(CLIP_WINDOW)) {
            if (doc != null) panels.renderClip(doc);
        }
        ImGui.end();
        if (ImGui.begin(EVENTS_WINDOW)) {
            if (doc != null) panels.renderEvents(doc);
        }
        ImGui.end();
        if (ImGui.begin(RETARGET_WINDOW)) {
            if (doc != null) renderRetarget(doc);
        }
        ImGui.end();

        if (doc != null && !ImGui.isAnyItemActive() && dragEffector == null) {
            doc.authoring().seal(); // a drag (if any) ended: the next edit is its own step
        }
        if (!wasShown) {
            wasShown = true;
            ImGui.setWindowFocus(VIEWPORT_WINDOW);
        }
        renderUnsavedDialog();
    }

    private void renderEmpty() {
        ImGui.textWrapped("No animation is open. Start a clip with New for Model... / New for Character..., or Open... an"
                + " .omanim.");
    }

    private void handleShortcuts(AnimationSession doc) {
        if (doc == null || !ImGui.isWindowFocused(imgui.flag.ImGuiFocusedFlags.AnyWindow)) return;
        var io = ImGui.getIO();
        if (io.getWantTextInput()) return;
        if (io.getKeyCtrl()) {
            if (ImGui.isKeyPressed(ImGuiKey.Z, false)) {
                if (io.getKeyShift()) panels.redo(); else panels.undo();
            } else if (ImGui.isKeyPressed(ImGuiKey.Y, false)) {
                panels.redo();
            } else if (ImGui.isKeyPressed(ImGuiKey.S, false)) {
                save(doc.file().orElse(null), null);
            }
            return;
        }
        panels.handleKeys(doc);
    }

    // ------------------------------------------------------------------------------------------------
    // Viewport

    private void renderViewport(AnimationSession doc, JointPalette palette) {
        if (viewport == null) {
            try {
                viewport = new AnimationViewport();
            } catch (RuntimeException e) {
                ImGui.textWrapped("Viewport unavailable: " + e.getMessage());
                return;
            }
        }
        if (framedFor != doc) {
            viewport.frame(doc.model().boundsMin(), doc.model().boundsMax());
            framedFor = doc;
        }
        Matrix4f base = doc.baseMatrix(doc.time());
        CharacterOverlay overlay;
        try {
            overlay = doc.overlay();
        } catch (RuntimeException e) {
            overlay = null;
        }
        fillOverlay(doc, overlay, base);
        float width = ImGui.getContentRegionAvailX();
        float controls = ImGui.getFrameHeightWithSpacing() + 4f;
        float height = Math.max(120f, ImGui.getContentRegionAvailY() - controls);
        int texture;
        try {
            texture = viewport.render(doc.model().geometry(), palette, (int) width, (int) height);
        } catch (RuntimeException e) {
            ImGui.textWrapped("Viewport: " + e.getMessage());
            return;
        }
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
        ImGui.image(texture, width, height, 0, 1, 1, 0);
        ImGui.setCursorScreenPos(x, y);
        ImGui.invisibleButton("##animationViewport", width, height);
        var io = ImGui.getIO();
        float mx = io.getMousePosX() - x, my = io.getMousePosY() - y;
        if (ImGui.isItemActivated()) {
            pressX = io.getMousePosX();
            pressY = io.getMousePosY();
            dragEffector = grabHandle(doc, base, mx, my);
        }
        if (dragEffector != null) {
            if (ImGui.isMouseDown(0)) {
                dragHandle(doc, base, mx, my);
            } else {
                dragEffector = null;
                doc.authoring().seal();
            }
        } else if (ImGui.isItemHovered() || ImGui.isItemActive()) {
            if (ImGui.isItemActive() && ImGui.isMouseDragging(0, 3f)) {
                viewport.orbit(io.getMouseDeltaX(), io.getMouseDeltaY());
            } else if (ImGui.isMouseDragging(2) || ImGui.isMouseDragging(1)) {
                viewport.pan(io.getMouseDeltaX(), io.getMouseDeltaY());
            }
            if (io.getMouseWheel() != 0f) viewport.zoom(io.getMouseWheel());
            float mdx = io.getMousePosX() - pressX, mdy = io.getMousePosY() - pressY;
            if (ImGui.isItemDeactivated() && mdx * mdx + mdy * mdy < 9f && overlay != null) {
                float[] ray = modelRay(base, mx, my);
                if (ray != null) {
                    CharacterOverlay.Pick pick = overlay.pick(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], 0.004f,
                            viewport.tolerancePerDistance(), false);
                    if (pick.kind() == CharacterOverlay.PickKind.JOINT) panels.selectJoint(pick.index());
                }
            }
        }
        if (ImGui.checkbox("Mesh", viewport.showMesh)) viewport.showMesh = !viewport.showMesh;
        ImGui.sameLine();
        if (ImGui.checkbox("Skeleton", viewport.showSkeleton)) viewport.showSkeleton = !viewport.showSkeleton;
        ImGui.sameLine();
        if (ImGui.checkbox("X-ray", viewport.xray)) viewport.xray = !viewport.xray;
        ImGui.sameLine();
        ImGui.textDisabled("LMB: pick / drag IK targets / orbit  RMB/MMB: pan  wheel: zoom");
    }

    private void fillOverlay(AnimationSession doc, CharacterOverlay overlay, Matrix4f base) {
        AnimationViewport.Lines lines = viewport.lines();
        lines.clear();
        Vector3f a = new Vector3f(), b = new Vector3f();
        if (overlay != null && viewport.showSkeleton) {
            float[] p = overlay.points(), t = overlay.tails();
            var joints = doc.model().rig().joints();
            int sel = panels.selectedJoint();
            for (int i = 0; i < overlay.jointCount(); i++) {
                float[] col = i == sel ? SELECTED : SKELETON;
                base.transformPosition(p[i * 3], p[i * 3 + 1], p[i * 3 + 2], a);
                base.transformPosition(t[i * 3], t[i * 3 + 1], t[i * 3 + 2], b);
                if (a.distanceSquared(b) > 1e-10f) lines.line(a.x, a.y, a.z, b.x, b.y, b.z, col);
                int parent = joints.get(i).parent();
                if (parent >= 0 && joints.get(i).kind() != com.openmason.engine.rig.RigJoint.Kind.BONE
                        || parent >= 0 && joints.get(parent).kind() != com.openmason.engine.rig.RigJoint.Kind.BONE) {
                    base.transformPosition(p[parent * 3], p[parent * 3 + 1], p[parent * 3 + 2], b);
                    lines.line(b.x, b.y, b.z, a.x, a.y, a.z, new float[] {col[0], col[1], col[2], 0.45f});
                }
                lines.point(a.x, a.y, a.z, col, i == sel ? 9f : 5f);
            }
        }
        // Control-rig handles.
        try {
            JsonNode handles = new com.fasterxml.jackson.databind.ObjectMapper().readTree(doc.authoring().handles(doc.time()));
            for (JsonNode h : handles) {
                float[] tg = AnimationPanels.floats(h.path("target")), end = AnimationPanels.floats(h.path("end"));
                boolean sel = h.path("effector").asText().equals(panels.selectedEffector());
                float w = (float) h.path("weight").asDouble();
                base.transformPosition(tg[0], tg[1], tg[2], a);
                base.transformPosition(end[0], end[1], end[2], b);
                float[] col = {TARGET[0], TARGET[1], TARGET[2], 0.35f + 0.65f * w};
                lines.point(a.x, a.y, a.z, col, sel ? 14f : 10f);
                lines.line(b.x, b.y, b.z, a.x, a.y, a.z, col);
                if (h.has("pole")) {
                    float[] pl = AnimationPanels.floats(h.path("pole"));
                    base.transformPosition(pl[0], pl[1], pl[2], b);
                    lines.point(b.x, b.y, b.z, POLE, sel ? 10f : 7f);
                }
            }
        } catch (Exception e) {
            panels.setError("Control rig: " + e.getMessage());
        }
    }

    /** The ray through a viewport pixel in MODEL space (the in-place offset undone). */
    private float[] modelRay(Matrix4f base, float px, float py) {
        float[] r = viewport.ray(px, py);
        if (r == null) return null;
        Matrix4f inv = new Matrix4f(base).invert();
        Vector3f o = inv.transformPosition(new Vector3f(r[0], r[1], r[2]));
        Vector3f d = inv.transformDirection(new Vector3f(r[3], r[4], r[5]));
        return new float[] {o.x, o.y, o.z, d.x, d.y, d.z};
    }

    private String grabHandle(AnimationSession doc, Matrix4f base, float mx, float my) {
        try {
            JsonNode handles = new com.fasterxml.jackson.databind.ObjectMapper().readTree(doc.authoring().handles(doc.time()));
            String best = null;
            float bestD = 10f * 10f;
            Vector3f a = new Vector3f();
            for (JsonNode h : handles) {
                float[] tg = AnimationPanels.floats(h.path("target"));
                base.transformPosition(tg[0], tg[1], tg[2], a);
                float[] sp = viewport.project(a.x, a.y, a.z);
                if (sp == null) continue;
                float d = (sp[0] - mx) * (sp[0] - mx) + (sp[1] - my) * (sp[1] - my);
                if (d < bestD) {
                    bestD = d;
                    best = h.path("effector").asText();
                    dragPoint.set(tg);
                }
            }
            if (best != null) {
                panels.selectEffector(best);
                doc.setPlaying(false);
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    /** Moves the dragged target in the plane facing the camera through its current position (one undo step). */
    private void dragHandle(AnimationSession doc, Matrix4f base, float mx, float my) {
        float[] r = modelRay(base, mx, my);
        if (r == null) return;
        Matrix4f inv = new Matrix4f(base).invert();
        Vector3f n = inv.transformPosition(viewport.eye()).sub(dragPoint).normalize();
        Vector3f o = new Vector3f(r[0], r[1], r[2]), d = new Vector3f(r[3], r[4], r[5]);
        float denom = d.dot(n);
        if (Math.abs(denom) < 1e-6f) return;
        float t = new Vector3f(dragPoint).sub(o).dot(n) / denom;
        Vector3f p = o.add(d.mul(t));
        var op = AnimationPanels.op("effector_key").put("effector", dragEffector).put("time", doc.time()).put("weight", 1f);
        op.set("pos", AnimationPanels.vec(p.x, p.y, p.z));
        if (panels.apply(doc, "move " + dragEffector, "drag:" + dragEffector + ":" + doc.time(), op)) {
            dragPoint.set(p);
        } else {
            dragEffector = null;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Retarget: a clip of another characterized model, side by side, then baked onto this one

    private void renderRetarget(AnimationSession doc) {
        ImGui.textWrapped("Preview a clip of another characterized model next to this one, then bake it onto this"
                + " character as a layer (#160 retargeting; both need a character definition).");
        if (ImGui.button("Source character...") && fileDialogs != null) {
            fileDialogs.showOpenDialog(FileDialogService.StartIn.TYPE_FOLDER, p -> {
                retargetCharacter = Path.of(p);
                closeRetarget();
            }, BuiltinFileTypes.OMCHAR);
        }
        ImGui.sameLine();
        ImGui.textDisabled(retargetCharacter == null ? "(none)" : retargetCharacter.getFileName().toString());
        if (retargetCharacter == null) ImGui.beginDisabled();
        if (ImGui.button("Source clip...") && fileDialogs != null) {
            Path character = retargetCharacter;
            fileDialogs.showOpenDialog(FileDialogService.StartIn.TYPE_FOLDER, p -> {
                closeRetarget();
                try {
                    retarget = RetargetSource.open(character, Path.of(p), sessions.projectRoot());
                } catch (Exception e) {
                    panels.setError("Retarget source: " + e.getMessage());
                }
            }, BuiltinFileTypes.OMANIM);
        }
        if (retargetCharacter == null) ImGui.endDisabled();
        if (retarget == null) return;
        ImGui.sameLine();
        ImGui.textDisabled(retarget.clipFile().getFileName().toString());
        if (ImGui.checkbox("Preview side by side", retargetPreview)) retargetPreview = !retargetPreview;
        if (retargetPreview) {
            if (sourceViewport == null) {
                try {
                    sourceViewport = new AnimationViewport();
                    sourceViewport.frame(retarget.character().boundsMin(), retarget.character().boundsMax());
                } catch (RuntimeException e) {
                    panels.setError("Preview: " + e.getMessage());
                }
            }
            if (sourceViewport != null) {
                float w = ImGui.getContentRegionAvailX();
                float h = Math.min(320f, w);
                try {
                    int tex = sourceViewport.render(retarget.character().geometry(), retarget.poseAt(doc.time()), (int) w, (int) h);
                    ImGui.image(tex, w, h, 0, 1, 1, 0);
                    ImGui.textDisabled("source, in step with this clip's playhead (play to compare)");
                } catch (RuntimeException e) {
                    ImGui.textWrapped("Preview: " + e.getMessage());
                }
            }
        }
        ImGui.setNextItemWidth(160f);
        ImGui.inputText("Layer##retarget", retargetLayer);
        if (ImGui.button("Bake onto this character")) {
            try {
                retarget.bakeOnto(doc, retargetLayer.get().isBlank() ? "Retarget" : retargetLayer.get());
                status.updateStatus("Baked " + retarget.clipFile().getFileName() + " onto " + doc.model().displayName());
            } catch (RuntimeException e) {
                panels.setError("Bake: " + e.getMessage());
            }
        }
        AnimationPanels.hint("Creates (or replaces the keys of) that layer: one undo step");
    }

    private void closeRetarget() {
        if (retarget != null) retarget.close();
        retarget = null;
        if (sourceViewport != null) {
            sourceViewport.close();
            sourceViewport = null;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Files and dialogs

    private void save(Path path, Runnable after) {
        AnimationSession doc = sessions.current().orElse(null);
        if (doc == null) return;
        if (path == null) {
            if (fileDialogs == null) return;
            String name = doc.file().map(p -> p.getFileName().toString()).orElse(doc.displayName() + ".omanim");
            fileDialogs.showSaveDialog(BuiltinFileTypes.OMANIM, name, null, p -> save(Path.of(p), after));
            return;
        }
        try {
            doc.save(path);
            status.updateStatus("Saved animation " + path.getFileName());
            if (after != null) after.run();
        } catch (Exception e) {
            logger.warn("Could not save animation {}", path, e);
            panels.setError("Could not save: " + e.getMessage());
        }
    }

    @Override
    public boolean allowClose(Runnable proceed) {
        AnimationSession doc = sessions.current().orElse(null);
        if (doc == null || !doc.isDirty()) return true;
        workspaces.set(ID);
        pendingAfterUnsaved = proceed;
        unsavedRequested = true;
        return false;
    }

    /** True when the open clip has unsaved changes (the plugin bridge asks before replacing it). */
    public boolean hasUnsaved() {
        return sessions.current().map(AnimationSession::isDirty).orElse(false);
    }

    /** Runs {@code action} once the open clip may be replaced (asks to save first when unsaved). */
    public void confirmDiscardThen(Runnable action) {
        show();
        guardUnsaved(action);
    }

    private void onClosed() {
        sessions.close();
        panels.reset();
        framedFor = null;
        closeRetarget();
        if (viewport != null) {
            viewport.close();
            viewport = null;
        }
    }

    private void guardUnsaved(Runnable action) {
        AnimationSession doc = sessions.current().orElse(null);
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
        if (!ModalDialogs.begin(UNSAVED_POPUP, 380)) return;
        AnimationSession doc = sessions.current().orElse(null);
        ImGui.textWrapped("The animation '" + (doc == null ? "" : doc.displayName()) + "' has unsaved changes. Save them first?");
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

    /** Releases GL (render thread, at shutdown). */
    public void cleanup() {
        closeRetarget();
        if (viewport != null) {
            viewport.close();
            viewport = null;
        }
    }
}
