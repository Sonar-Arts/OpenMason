package com.openmason.main.systems.layout;

import com.openmason.api.workspace.WorkspaceDescriptor;
import com.openmason.api.workspace.WorkspaceLayout;
import imgui.internal.ImGuiDockNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * Decides, per workspace, when its default dock layout is (re)built, and records which layout
 * version each user was given, per workspace id (#379). Replaces the per-builder version keys:
 * a workspace bumps {@link WorkspaceDescriptor#layoutVersion()} and every user gets exactly one
 * rebuild of that workspace, without touching the others.
 *
 * <p>A rebuild happens when the dockspace has no saved split arrangement, when the recorded
 * version is older than the descriptor's, or after {@link #requestReset}; checked once per session
 * per workspace (and again after a reset).
 *
 * <p><b>Orphaned arrangements.</b> The version record lives in the app config, but the arrangement
 * lives in {@code imgui.ini}, which is per working directory: a tool started from another checkout
 * can find a stale split arrangement (an older version's windows) while the record already says
 * current. So, once a workspace's {@link com.openmason.api.workspace.WorkspaceDock#panelWindows()
 * panels} exist, the gate checks once that its dockspace actually holds a docked window; if it holds
 * none, every panel is floating and the default layout is rebuilt. A user who undocks a few panels
 * keeps their arrangement: only a dockspace holding no window at all counts as orphaned.
 */
public final class WorkspaceLayouts {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceLayouts.class);

    /** Where each workspace's last-built layout version is kept. */
    public interface VersionStore {
        /** The version this user was last given; 0 = never built. */
        int stored(String workspaceId);

        void store(String workspaceId, int version);
    }

    private final VersionStore store;
    private final IntPredicate hasSavedArrangement;
    private final IntPredicate holdsDockedWindow;
    private final Predicate<String> windowExists;
    private final Set<String> checked = new HashSet<>();
    private final Set<String> resets = new HashSet<>();
    /** Workspaces whose dockspace is still to be checked for an orphaned arrangement this session. */
    private final Set<String> verifying = new HashSet<>();

    /** Production gate: saved arrangements and docked panels are read from ImGui's dock nodes. */
    public WorkspaceLayouts(VersionStore store) {
        this(store, WorkspaceLayouts::isSplitDockspace, WorkspaceLayouts::holdsDockedWindow,
                name -> imgui.internal.ImGui.findWindowByName(name).isValidPtr());
    }

    /**
     * Without orphan detection (tests of the version rules).
     *
     * @param hasSavedArrangement whether a dockspace already holds a split (saved) arrangement
     */
    public WorkspaceLayouts(VersionStore store, IntPredicate hasSavedArrangement) {
        this(store, hasSavedArrangement, dockspaceId -> true, name -> false);
    }

    /**
     * @param hasSavedArrangement whether a dockspace already holds a split (saved) arrangement
     * @param holdsDockedWindow   whether any window is docked anywhere inside a dockspace
     * @param windowExists        whether an ImGui window of that name was submitted this session
     */
    public WorkspaceLayouts(VersionStore store, IntPredicate hasSavedArrangement, IntPredicate holdsDockedWindow,
                            Predicate<String> windowExists) {
        this.store = Objects.requireNonNull(store, "store");
        this.hasSavedArrangement = Objects.requireNonNull(hasSavedArrangement, "hasSavedArrangement");
        this.holdsDockedWindow = Objects.requireNonNull(holdsDockedWindow, "holdsDockedWindow");
        this.windowExists = Objects.requireNonNull(windowExists, "windowExists");
    }

    /** The gate a workspace's dock calls after submitting its dockspace. */
    public WorkspaceLayout gate(WorkspaceDescriptor workspace) {
        return (dockspaceId, width, height) -> applyIfNeeded(workspace, dockspaceId, width, height);
    }

    /**
     * Rebuilds {@code workspace}'s default layout if this user needs it.
     *
     * @return true when it was rebuilt
     */
    public boolean applyIfNeeded(WorkspaceDescriptor workspace, int dockspaceId, float width, float height) {
        String id = workspace.id();
        boolean reset = resets.contains(id);
        if (checked.contains(id) && !reset) {
            if (isOrphaned(workspace, dockspaceId)) {
                logger.info("The saved {} dock arrangement holds none of its panels (a stale imgui.ini?);"
                        + " rebuilding the default layout", workspace.label());
                build(workspace, dockspaceId, width, height);
                return true;
            }
            return false;
        }
        checked.add(id);
        verifying.add(id);
        boolean saved = hasSavedArrangement.test(dockspaceId);
        int stored = store.stored(id);
        if (!LayoutRebuildDecision.shouldRebuild(saved, stored, workspace.layoutVersion(), reset)) {
            return false;
        }
        resets.remove(id);
        if (saved && !reset) {
            logger.info("Rebuilding the {} dock layout once (was version {}, now {}) so new windows have a home",
                    workspace.label(), stored, workspace.layoutVersion());
        } else {
            logger.info("Building the default {} dock layout", workspace.label());
        }
        build(workspace, dockspaceId, width, height);
        return true;
    }

    private void build(WorkspaceDescriptor workspace, int dockspaceId, float width, float height) {
        workspace.dock().buildDefaultLayout(dockspaceId, width, height);
        store.store(workspace.id(), workspace.layoutVersion());
    }

    /**
     * Once per session (per reset), as soon as one of the workspace's panels exists (it was submitted on an
     * earlier frame, so its docking is settled): true when the dockspace holds no docked window at all.
     */
    private boolean isOrphaned(WorkspaceDescriptor workspace, int dockspaceId) {
        String id = workspace.id();
        if (!verifying.contains(id)) {
            return false;
        }
        List<String> panels = workspace.dock().panelWindows();
        if (panels.isEmpty()) {
            verifying.remove(id);
            return false;
        }
        if (panels.stream().noneMatch(windowExists)) {
            return false; // not submitted yet: decide on a later frame
        }
        verifying.remove(id);
        return !holdsDockedWindow.test(dockspaceId);
    }

    /** View → Reset Layout: {@code workspaceId}'s default layout is rebuilt on its next frame. */
    public void requestReset(String workspaceId) {
        resets.add(workspaceId);
    }

    /** Whether a reset of {@code workspaceId} is still pending. */
    public boolean isResetPending(String workspaceId) {
        return resets.contains(workspaceId);
    }

    /** Whether ImGui restored (or was given) a split arrangement for {@code dockspaceId}. */
    static boolean isSplitDockspace(int dockspaceId) {
        ImGuiDockNode node = imgui.internal.ImGui.dockBuilderGetNode(dockspaceId);
        return node.isValidPtr() && node.isSplitNode();
    }

    /** Whether any window is docked in a leaf of {@code dockspaceId}'s node tree. */
    static boolean holdsDockedWindow(int dockspaceId) {
        return holdsDockedWindow(imgui.internal.ImGui.dockBuilderGetNode(dockspaceId));
    }

    private static boolean holdsDockedWindow(ImGuiDockNode node) {
        if (!node.isValidPtr()) {
            return false;
        }
        if (node.isLeafNode()) {
            return !node.isEmpty();
        }
        return holdsDockedWindow(node.getChildNodeFirst()) || holdsDockedWindow(node.getChildNodeSecond());
    }
}
