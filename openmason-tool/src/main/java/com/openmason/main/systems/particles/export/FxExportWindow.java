package com.openmason.main.systems.particles.export;

import com.openmason.api.fx.FxExportTarget;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.openmason.main.systems.menus.dialogs.AssetSectionSet;
import com.openmason.main.systems.menus.dialogs.FileDialogService;
import com.openmason.main.systems.themes.utils.ThemeColors.Tone;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * The Particles tab's Export FX window (#102): pick a registered container (the neutral {@code .omfx} v2, or a game
 * plugin's shipped format), fill in the plugins' game-data tabs, then Export... to a file or Deploy to Game into
 * the active game target's checkout. All rules live in {@link FxExportController}; this only draws.
 */
public final class FxExportWindow {

    private static final Logger logger = LoggerFactory.getLogger(FxExportWindow.class);
    private static final float WINDOW_W = 560f, WINDOW_H = 400f;
    private static final String TAB_EXPORT = "export";
    private static final List<AssetSectionSet.Tab> CORE_TABS =
            List.of(new AssetSectionSet.Tab(TAB_EXPORT, "Export", 0, null));

    private final FxExportController controller;
    private final FileDialogService fileDialogs;
    private final ImBoolean visible = new ImBoolean(false);
    private List<AssetSectionSet.Tab> tabs = CORE_TABS;
    private boolean centerOnNextFrame;

    public FxExportWindow(FxExportController controller, FileDialogService fileDialogs) {
        this.controller = controller;
        this.fileDialogs = fileDialogs;
    }

    /** Opens the window on {@code document}; {@code notes} are shown at the top. */
    public void show(OmfxDocument document, List<String> notes) {
        controller.open(document, notes);
        tabs = controller.sections().tabs(CORE_TABS);
        visible.set(true);
        centerOnNextFrame = true;
    }

    public FxExportController controller() {
        return controller;
    }

    public void render() {
        if (!visible.get() || !controller.isOpen()) {
            return;
        }
        ImGui.setNextWindowSize(WINDOW_W, WINDOW_H, ImGuiCond.FirstUseEver);
        if (centerOnNextFrame) {
            var vp = ImGui.getMainViewport();
            ImGui.setNextWindowPos(vp.getPosX() + (vp.getSizeX() - WINDOW_W) * 0.5f,
                    vp.getPosY() + (vp.getSizeY() - WINDOW_H) * 0.5f, ImGuiCond.Always);
            centerOnNextFrame = false;
        }
        if (ImGui.begin("Export FX - " + controller.source().id() + "###fx_export", visible,
                ImGuiWindowFlags.NoCollapse)) {
            try {
                renderBody();
            } catch (RuntimeException e) {
                logger.error("Error rendering the Export FX window", e);
                ThemedWidgets.statusText(Tone.ERROR, "Error rendering the export window (see the log)");
            }
        }
        ImGui.end();
        if (!visible.get()) {
            controller.close();
        }
    }

    private void renderBody() {
        for (String note : controller.notes()) {
            ThemedWidgets.statusTextWrapped(Tone.WARNING, note);
        }
        if (ImGui.beginTabBar("##fxExportTabs")) {
            for (AssetSectionSet.Tab tab : tabs) {
                if (ImGui.beginTabItem(tab.label() + "##" + tab.key())) {
                    if (tab.section() != null) {
                        controller.sections().renderSection(tab.section());
                    } else {
                        renderExportTab();
                    }
                    ImGui.endTabItem();
                }
            }
            ImGui.endTabBar();
        }
        controller.sections().renderOverlays();
    }

    private void renderExportTab() {
        List<FxExportTarget> targets = controller.targets();
        if (targets.isEmpty()) {
            ThemedWidgets.inlineError("No FX export target is registered.");
            return;
        }
        ThemedWidgets.sectionLabel("Format");
        FxExportTarget current = controller.target();
        if (ImGui.beginCombo("##fxTarget", current.displayName())) {
            for (int i = 0; i < targets.size(); i++) {
                if (ImGui.selectable(targets.get(i).displayName() + "##" + targets.get(i).id(),
                        i == controller.selectedIndex())) {
                    controller.select(i);
                }
            }
            ImGui.endCombo();
        }
        ImGui.textDisabled("File: " + controller.fileName());

        FxExportService.Deployment deployment = controller.deployment();
        ImGui.dummy(0, 6);
        if (ImGui.button("Export...")) {
            fileDialogs.showSaveDialog(current.fileType(), controller.fileName(), null,
                    p -> controller.exportTo(Path.of(p)));
        }
        if (deployment.deployable()) {
            ImGui.sameLine();
            if (ThemedWidgets.accentButton("Deploy to Game", 0f, 0f)) {
                controller.deploy();
            }
            ImGui.textDisabled("Deploys to " + deployment.folder());
        } else if (current.deploys()) {
            ThemedWidgets.statusTextWrapped(Tone.WARNING, "Deploy unavailable: " + deployment.reason());
        }

        if (controller.hasConflict()) {
            ImGui.dummy(0, 6);
            ThemedWidgets.statusTextWrapped(Tone.WARNING, "The game already has a different "
                    + controller.conflictPath().getFileName() + ":");
            ImGui.textWrapped(controller.conflictPath().toString());
            if (ThemedWidgets.dangerSoftButton("Replace", 0f, 0f)) {
                controller.resolveConflict(true);
            }
            ImGui.sameLine();
            if (ImGui.button("Keep the game's file")) {
                controller.resolveConflict(false);
            }
        }
        if (controller.message() != null) {
            ImGui.dummy(0, 6);
            ThemedWidgets.statusTextWrapped(controller.failed() ? Tone.ERROR : Tone.SUCCESS, controller.message());
        }
    }
}
