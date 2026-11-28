package com.openmason.main.bootstrap;

import com.openmason.api.PluginContext;
import com.openmason.api.PluginInfo;
import com.openmason.api.ServiceRegistry;
import com.openmason.api.files.FileOpener;
import com.openmason.api.menu.MenuItem;
import com.openmason.api.menu.MenuSection;
import com.openmason.api.menu.TopMenu;
import com.openmason.api.lifecycle.LifecycleListener;
import com.openmason.api.workspace.WorkspaceDescriptor;
import com.openmason.api.workspace.WorkspaceDock;
import com.openmason.main.plugins.EditorRegistries;
import com.openmason.main.plugins.PluginManager;
import com.openmason.main.plugins.PluginsPreferencePage;
import com.openmason.main.systems.MainDockLayout;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.io.BuiltinFileTypes;
import com.openmason.main.systems.layout.BuiltinWorkspaces;
import com.openmason.main.systems.layout.MainLayoutBuilder;
import com.openmason.main.systems.layout.SceneWorkspaceDock;
import com.openmason.main.systems.layout.WorkspaceLayouts;
import com.openmason.main.systems.layout.WorkspaceState;
import com.openmason.main.systems.menus.dialogs.FileDialogService;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.mcp.BuiltinKnowledge;
import com.openmason.main.systems.mcp.BuiltinMcpTools;
import com.openmason.main.systems.mcp.KnowledgeBase;
import com.openmason.main.systems.menus.EditMenuHandler;
import com.openmason.main.systems.menus.FileMenuHandler;
import com.openmason.main.systems.menus.ViewMenuHandler;
import com.openmason.main.systems.particles.ParticleEditorWorkspace;
import com.openmason.main.systems.menus.mainHub.services.TemplateService;
import com.openmason.main.systems.menus.preferences.PreferencesPageRenderer;
import com.openmason.main.systems.menus.preferences.PreferencesWindow;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorImGui;
import com.openmason.main.systems.menus.textureCreator.TextureEditorWorkspace;
import com.openmason.main.systems.menus.textureCreator.rendering.TextureEditorLayoutBuilder;
import com.openmason.main.systems.scene.SceneViewerImGuiInterface;
import com.openmason.main.systems.services.ModelOperationService;
import com.openmason.main.systems.services.ProjectDirectory;
import com.openmason.main.systems.services.ScriptingPresenter;
import com.openmason.main.systems.services.TextureEditorPresenter;
import com.openmason.main.systems.stateHandling.UIVisibilityState;
import com.openmason.main.systems.uiEditor.view.UiEditorWorkspace;
import com.openmason.main.systems.uiEditor.view.UiWorkspaceLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * The editor's own features, registered through the same {@link PluginContext} registrars as
 * plugins (#379) so the registries are the one source of truth for what the editor has, but not
 * plugins themselves: they always ship, are not listed on the Plugins page, register before any
 * plugin, and a failure here stops start-up instead of being rolled back quietly.
 *
 * <p>Registered in tab order: the Scene workspace and the general features, then the UI editor, the
 * Texture editor, the Animation editor and scripting. Everything is owned by {@link #ID}, which a
 * plugin may name in {@code requires()} (it is always satisfied).
 */
public final class CoreFeatures {

    private static final Logger logger = LoggerFactory.getLogger(CoreFeatures.class);

    /** Owner id of the core contributions; always active for {@code requires()} checks. */
    public static final String ID = PluginManager.CORE_ID;

    /** How the core appears as a contribution owner. */
    public static final PluginInfo INFO = new PluginInfo(ID, "Open Mason", "1.0", Set.of(), true,
            CoreFeatures.class.getModule().isNamed() ? CoreFeatures.class.getModule().getName() : "unnamed");

    private CoreFeatures() {
    }

    /**
     * Registers every core feature. Throws when one cannot register: the editor must not start
     * with a core feature silently missing.
     */
    public static void register(EditorRegistries registries) {
        PluginContext context = registries.contextFor(INFO);
        general(context);
        uiEditor(context);
        textureEditor(context);
        animation(context);
        scripting(context);
        particles(context);
        character(context);
        animGraph(context);
        logger.info("[core] registered: {}", contributionSummary(registries));
    }

    private static String contributionSummary(EditorRegistries registries) {
        return registries.all().stream()
                .filter(c -> !c.ownedBy(ID).isEmpty())
                .map(c -> c.ownedBy(ID).size() + " " + c.kind())
                .toList().toString();
    }

    /**
     * The Scene workspace (model editor + scene viewer), the File/Edit/View/Tools/About menus, the
     * model/scene/project/PNG file types, the model, bone, attachment, scene, viewport, save,
     * knowledge and meta MCP tools, the general guide topics and knowledge packs, the general
     * preference pages (plus the Plugins page) and the blank template.
     */
    private static void general(PluginContext context) {
        ServiceRegistry services = context.services();

        context.workspaces().register(WorkspaceDescriptor.builder(BuiltinWorkspaces.MODELING, "Scene")
                .description("Models, textures and scenes")
                .layoutVersion(MainLayoutBuilder.LAYOUT_VERSION)
                .dockspaceName(MainDockLayout.DOCKSPACE_ID)
                .dock(services.require(SceneWorkspaceDock.class))
                .build());

        UIVisibilityState ui = services.require(UIVisibilityState.class);
        context.menus().register(TopMenu.of(TopMenu.FILE, "File", 100));
        context.menus().register(TopMenu.of(TopMenu.EDIT, "Edit", 200));
        context.menus().register(TopMenu.of(TopMenu.VIEW, "View", 300));
        context.menus().register(TopMenu.of(TopMenu.TOOLS, "Tools", 400));
        context.menus().register(new TopMenu(TopMenu.ABOUT, "About", 900, ui::showAbout));
        context.menuEntries().register(new MenuSection("core.file", TopMenu.FILE, 100,
                services.require(FileMenuHandler.class)::renderItems));
        context.menuEntries().register(new MenuSection("core.edit", TopMenu.EDIT, 100,
                services.require(EditMenuHandler.class)::renderItems));
        context.menuEntries().register(new MenuSection("core.view", TopMenu.VIEW, 100,
                services.require(ViewMenuHandler.class)::renderItems));

        context.fileTypes().register(BuiltinFileTypes.OMO);
        context.fileTypes().register(BuiltinFileTypes.OMSC);
        context.fileTypes().register(BuiltinFileTypes.OMP);
        context.fileTypes().register(BuiltinFileTypes.PNG);
        context.fileOpeners().register(new FileOpener(BuiltinFileTypes.OMO.id(),
                path -> services.require(ModelOperationService.class).loadOMOModel(path.toString())));
        // glTF / OBJ (#173): opening one shows File > Import Model's options for it.
        context.fileTypes().register(BuiltinFileTypes.GLTF);
        context.fileTypes().register(BuiltinFileTypes.OBJ);
        for (var type : List.of(BuiltinFileTypes.GLTF, BuiltinFileTypes.OBJ)) {
            context.fileOpeners().register(new FileOpener(type.id(),
                    path -> services.require(FileMenuHandler.class).getModelImportDialog().show(path)));
        }
        // The importer as an entity-model format: imported models work wherever an attachable model does.
        com.openmason.engine.format.spi.Formats.register(
                new com.openmason.main.systems.io.ImportedModelCodec());
        context.fileOpeners().register(new FileOpener(BuiltinFileTypes.OMSC.id(), path -> {
            String dir = services.find(ProjectDirectory.class).map(ProjectDirectory::get).orElse(null);
            services.require(SceneViewerImGuiInterface.class).getSceneService()
                    .openScene(path.toString(), dir == null ? null : Path.of(dir));
        }));

        for (var tools : List.of(BuiltinMcpTools.SAVE, BuiltinMcpTools.PROJECTS, BuiltinMcpTools.MODEL_FILES, BuiltinMcpTools.MODEL,
                BuiltinMcpTools.BONES, BuiltinMcpTools.SKIN, BuiltinMcpTools.ATTACHMENTS, BuiltinMcpTools.VIEWPORT, BuiltinMcpTools.SCENE,
                BuiltinMcpTools.INSPECTION, BuiltinMcpTools.KNOWLEDGE, BuiltinMcpTools.META)) {
            context.mcpTools().register(tools);
        }
        for (String topic : List.of("overview", "projects", "parts", "face_textures", "bones", "skinning", "attachments", "recipes",
                "saving")) {
            context.guideTopics().register(BuiltinKnowledge.topic(topic));
        }
        for (String pack : KnowledgeBase.BUILTIN_PACKS) {
            context.knowledgePacks().register(BuiltinKnowledge.pack(pack));
        }

        PreferencesPageRenderer pages = services.require(PreferencesWindow.class).getPageRenderer();
        context.preferencePages().register(pages.modelEditorPage());
        context.preferencePages().register(pages.foldersPage());
        context.preferencePages().register(pages.keybindsPage());
        context.preferencePages().register(pages.assistantPage());
        context.preferencePages().register(pages.commonPage());
        context.preferencePages().register(new PluginsPreferencePage(services.require(PluginManager.class),
                services.require(EditorRegistries.class)));

        context.templates().register(TemplateService.blankTemplate());
    }

    /**
     * The UI Editor (#293): the UI workspace, Tools → UI Editor, the .omui/.sbui file types (opened
     * in the workspace), the {@code ui_*} MCP tools and the {@code ui_editor} topic.
     */
    private static void uiEditor(PluginContext context) {
        ServiceRegistry services = context.services();
        UiEditorWorkspace editor = services.require(UiEditorWorkspace.class);
        WorkspaceState workspaces = services.require(WorkspaceState.class);

        context.workspaces().register(WorkspaceDescriptor.builder(BuiltinWorkspaces.UI, "UI")
                .description("Game UI screens and components")
                .layoutVersion(UiWorkspaceLayout.LAYOUT_VERSION)
                .dockspaceName(MainDockLayout.UI_DOCKSPACE_ID)
                .dock(new WorkspaceDock() {
                    @Override
                    public void buildDefaultLayout(int dockspaceId, float width, float height) {
                        editor.buildDefaultLayout(dockspaceId, width, height);
                    }

                    @Override
                    public List<String> panelWindows() {
                        return UiWorkspaceLayout.PANEL_WINDOWS;
                    }

                    @Override
                    public void renderHeader() {
                        editor.renderHeader();
                    }

                    @Override
                    public void renderPanels(float deltaTime, boolean shown) {
                        // Ticks even while hidden (document services, recovery, released input).
                        editor.render(deltaTime, shown);
                    }
                })
                .build());

        context.menuEntries().register(MenuItem.of("ui.tools.open", TopMenu.TOOLS, 130, "UI Editor",
                () -> workspaces.set(BuiltinWorkspaces.UI)));

        context.fileTypes().register(BuiltinFileTypes.OMUI);
        context.fileTypes().register(BuiltinFileTypes.SBUI);
        for (var type : List.of(BuiltinFileTypes.OMUI, BuiltinFileTypes.SBUI)) {
            context.fileOpeners().register(new FileOpener(type.id(), path -> {
                workspaces.set(BuiltinWorkspaces.UI);
                editor.openFile(path);
            }));
        }

        context.mcpTools().register(BuiltinMcpTools.UI);
        context.guideTopics().register(BuiltinKnowledge.topic("ui_editor"));
    }

    /**
     * The Texture Editor: the closable Texture workspace (asks about unsaved textures before it
     * closes), Tools → Texture Editor, the .omt file type (opened as a document tab), the
     * {@code tex_*} and per-face texture MCP tools, the {@code texture_editor} topic and its
     * preference page.
     */
    private static void textureEditor(PluginContext context) {
        ServiceRegistry services = context.services();
        TextureEditorWorkspace workspace = services.require(TextureEditorWorkspace.class);
        TextureCreatorImGui editor = services.require(TextureCreatorImGui.class);
        WorkspaceState workspaces = services.require(WorkspaceState.class);
        WorkspaceLayouts layouts = services.require(WorkspaceLayouts.class);

        context.workspaces().register(WorkspaceDescriptor.builder(BuiltinWorkspaces.TEXTURE, "Texture")
                .description("Texture Editor: paint .omt textures and per-face model textures")
                .closable(true)
                // Keeps the fixed left chrome (Color 320 + Tools 48) plus a workable canvas and Layers column.
                .minSize(1100f, 700f)
                .layoutVersion(TextureEditorLayoutBuilder.LAYOUT_VERSION)
                .dockspaceName(MainDockLayout.TEXTURE_DOCKSPACE_ID)
                .dock(workspace)
                .build());
        workspace.setResetLayoutAction(() -> layouts.requestReset(BuiltinWorkspaces.TEXTURE));
        // Closing the Texture tab asks about unsaved textures first (it comes forward to ask).
        workspaces.setCloseGuard(BuiltinWorkspaces.TEXTURE, proceed -> {
            if (editor.confirmCloseAll(proceed)) {
                return true;
            }
            workspaces.set(BuiltinWorkspaces.TEXTURE);
            return false;
        });

        // Brings the editor forward with its open textures; a fresh untitled one when none is open.
        context.menuEntries().register(MenuItem.of("texture.tools.open", TopMenu.TOOLS, 100, "Texture Editor", () -> {
            editor.getController().ensureDocument();
            services.find(TextureEditorPresenter.class).ifPresent(TextureEditorPresenter::show);
        }));

        context.fileTypes().register(BuiltinFileTypes.OMT);
        // Opens the texture as a document tab (or focuses its tab).
        context.fileOpeners().register(new FileOpener(BuiltinFileTypes.OMT.id(), path -> {
            if (!editor.getController().loadProject(path.toString())) {
                logger.warn("Failed to open .OMT in texture editor: {}", path);
                return;
            }
            services.find(TextureEditorPresenter.class).ifPresent(TextureEditorPresenter::show);
        }));

        context.mcpTools().register(BuiltinMcpTools.TEXTURE);
        context.mcpTools().register(BuiltinMcpTools.FACE_TEXTURES);
        context.guideTopics().register(BuiltinKnowledge.topic("texture_editor"));
        context.preferencePages().register(services.require(PreferencesWindow.class).getPageRenderer()
                .textureEditorPage());
    }

    /**
     * The Animation workspace (OM #165): a closable tab authoring .omanim clips (layers, control rig, events,
     * retarget) over the native authoring session, the file type, the anim_* MCP tools (which edit the same open
     * clip: one shared undo history), the {@code animation} topic, and the clip bridge the asset kinds of a game
     * plugin use to hand state clips to and from the editor.
     */
    private static void animation(PluginContext context) {
        ServiceRegistry services = context.services();
        WorkspaceState workspaces = services.require(WorkspaceState.class);
        WorkspaceLayouts layouts = services.require(WorkspaceLayouts.class);
        com.openmason.main.systems.animation.AnimationSessions sessions = new com.openmason.main.systems.animation.AnimationSessions();
        sessions.setProjectRoot(() -> services.find(ProjectDirectory.class).map(ProjectDirectory::get)
                .filter(d -> d != null && !d.isBlank()).map(Path::of).orElse(null));
        services.publish(com.openmason.main.systems.animation.AnimationSessions.class, sessions);
        com.openmason.main.systems.animation.AnimationWorkspace editor = new com.openmason.main.systems.animation.AnimationWorkspace(
                services.require(FileDialogService.class), services.require(StatusService.class), workspaces, sessions);
        // A clip without a model it can find opens on the model the Scene holds (when saved).
        editor.setSceneModel(() -> services.find(com.openmason.main.systems.stateHandling.ModelState.class).map(ms -> {
            String omo = ms.getCurrentOMOFilePath();
            String path = omo != null ? omo : ms.getCurrentModelPath();
            return path == null || path.isBlank() ? null : Path.of(path);
        }).orElse(null));
        editor.setHeldItemProviders(() -> services.find(com.openmason.main.plugins.EditorRegistries.class)
                .map(r -> r.heldItems().all()).orElse(java.util.List.of()));
        services.publish(com.openmason.main.systems.animation.AnimationWorkspace.class, editor);
        context.workspaces().register(WorkspaceDescriptor.builder(BuiltinWorkspaces.ANIMATION, "Animation")
                .description("Animation: author .omanim clips - layers, IK control rig, notifies, curves, retargeting")
                .closable(true)
                .minSize(900f, 560f)
                .layoutVersion(com.openmason.main.systems.animation.AnimationWorkspace.LAYOUT_VERSION)
                .dockspaceName(MainDockLayout.ANIMATION_DOCKSPACE_ID)
                .dock(editor)
                .build());
        editor.setResetLayoutAction(() -> layouts.requestReset(BuiltinWorkspaces.ANIMATION));
        context.menuEntries().register(MenuItem.of("animation.tools.open", TopMenu.TOOLS, 110, "Animation Editor", editor::show));
        context.fileTypes().register(BuiltinFileTypes.OMANIM);
        context.fileOpeners().register(new FileOpener(BuiltinFileTypes.OMANIM.id(), editor::openFile));
        context.mcpTools().register(BuiltinMcpTools.ANIMATION);
        context.guideTopics().register(BuiltinKnowledge.topic("animation"));
        // Asset kinds whose states carry clips (a game plugin's editors, #88) hand them over through this.
        services.publish(com.openmason.main.systems.menus.dialogs.AnimationClipBridge.class,
                new com.openmason.main.systems.menus.dialogs.AnimationClipBridge(
                        () -> sessions.current().map(s -> s.authoring().write()).orElse(null),
                        (bytes, label) -> editor.openBytes(bytes, label),
                        () -> sessions.current().map(com.openmason.main.systems.animation.AnimationSession::displayName).orElse(null)));
        context.lifecycle().register(new LifecycleListener() {
            @Override
            public void shuttingDown() {
                editor.cleanup();
                sessions.close();
            }
        });
    }

    /**
     * Programmatic modelling: Tools → Scripting Editor, the script-running and script-library MCP
     * tools, and the {@code scripting} topic (the Python {@code om} cheatsheet).
     */
    /**
     * The Particle Editor: Tools -> Particle Editor and the .omfx particle-library file type (opened in the
     * editor window). Effects are engine {@code ParticleEffect}s, previewed by the engine particle renderer.
     */
    private static void particles(PluginContext context) {
        ServiceRegistry services = context.services();
        WorkspaceState workspaces = services.require(WorkspaceState.class);
        WorkspaceLayouts layouts = services.require(WorkspaceLayouts.class);
        ParticleEditorWorkspace editor = new ParticleEditorWorkspace(services.require(FileDialogService.class),
                services.require(StatusService.class), workspaces);
        services.publish(ParticleEditorWorkspace.class, editor);

        // A closable workspace tab like the Texture Editor: exists while open, ends only on the user's close
        // (its guard asks about unsaved changes), can pop out into its own window.
        context.workspaces().register(WorkspaceDescriptor.builder(BuiltinWorkspaces.PARTICLES, "Particles")
                .description("FX Editor: author .omfx FX systems (emitters, module stacks, graphs) with a live preview")
                .closable(true)
                .minSize(900f, 560f)
                .layoutVersion(ParticleEditorWorkspace.LAYOUT_VERSION)
                .dockspaceName(MainDockLayout.PARTICLES_DOCKSPACE_ID)
                .dock(editor)
                .build());
        editor.setResetLayoutAction(() -> layouts.requestReset(BuiltinWorkspaces.PARTICLES));

        context.menuEntries().register(MenuItem.of("particles.tools.open", TopMenu.TOOLS, 140, "Particle Editor",
                editor::show));
        context.fileTypes().register(BuiltinFileTypes.OMFX);
        context.fileOpeners().register(new FileOpener(BuiltinFileTypes.OMFX.id(), editor::openFile));
        // The neutral source format is itself an export target (#102): Export works without a game plugin, and the
        // plugin point is exercised by the core. Game plugins add their shipped containers the same way.
        context.fxExporters().register(new com.openmason.api.fx.FxExportTarget("openmason.omfx",
                "Open Mason FX (.omfx v2)", BuiltinFileTypes.OMFX, null,
                in -> com.openmason.engine.rendering.particles.omfx.OmfxCodec.write(in.document())));
        // Agents author FX systems (.omfx v2) headlessly through the same JSON view the tab edits (OM #104).
        context.mcpTools().register(BuiltinMcpTools.PARTICLES);
        context.guideTopics().register(BuiltinKnowledge.topic("particles"));
        // The preview's GL resources go while GL still exists.
        context.lifecycle().register(new LifecycleListener() {
            @Override
            public void shuttingDown() {
                editor.cleanup();
            }
        });
    }

    /**
     * The Character workspace (OM #163): a closable tab building .omchar character definitions, the .omchar file
     * type, and the char_* MCP tools, which edit the same open character (one shared native undo history).
     */
    private static void character(PluginContext context) {
        ServiceRegistry services = context.services();
        WorkspaceState workspaces = services.require(WorkspaceState.class);
        WorkspaceLayouts layouts = services.require(WorkspaceLayouts.class);
        com.openmason.main.systems.character.CharacterSessions sessions =
                new com.openmason.main.systems.character.CharacterSessions();
        sessions.setProjectRoot(() -> services.find(ProjectDirectory.class).map(ProjectDirectory::get)
                .filter(d -> d != null && !d.isBlank()).map(Path::of).orElse(null));
        services.publish(com.openmason.main.systems.character.CharacterSessions.class, sessions);
        com.openmason.main.systems.character.CharacterWorkspace editor =
                new com.openmason.main.systems.character.CharacterWorkspace(services.require(FileDialogService.class),
                        services.require(StatusService.class), workspaces, sessions);
        services.publish(com.openmason.main.systems.character.CharacterWorkspace.class, editor);
        context.workspaces().register(WorkspaceDescriptor.builder(BuiltinWorkspaces.CHARACTER, "Character")
                .description("Character: characterize a model into an .omchar (roles, reference pose, chains, sockets, metrics)")
                .closable(true)
                .minSize(900f, 560f)
                .layoutVersion(com.openmason.main.systems.character.CharacterWorkspace.LAYOUT_VERSION)
                .dockspaceName(MainDockLayout.CHARACTER_DOCKSPACE_ID)
                .dock(editor)
                .build());
        editor.setResetLayoutAction(() -> layouts.requestReset(BuiltinWorkspaces.CHARACTER));
        context.menuEntries().register(MenuItem.of("character.tools.open", TopMenu.TOOLS, 145, "Character Editor",
                editor::show));
        context.fileTypes().register(BuiltinFileTypes.OMCHAR);
        context.fileOpeners().register(new FileOpener(BuiltinFileTypes.OMCHAR.id(), editor::openFile));
        context.mcpTools().register(BuiltinMcpTools.CHARACTER);
        context.guideTopics().register(BuiltinKnowledge.topic("character"));
        context.lifecycle().register(new LifecycleListener() {
            @Override
            public void shuttingDown() {
                editor.cleanup();
                sessions.close();
            }
        });
    }

    /**
     * The Animation Graph workspace (OM #164): a closable tab authoring and debugging .omanimgraph graphs, the file
     * type, and the animgraph_* MCP tools, which edit the same open graph (one shared native undo history).
     */
    private static void animGraph(PluginContext context) {
        ServiceRegistry services = context.services();
        WorkspaceState workspaces = services.require(WorkspaceState.class);
        WorkspaceLayouts layouts = services.require(WorkspaceLayouts.class);
        com.openmason.main.systems.animgraph.AnimGraphSessions sessions = new com.openmason.main.systems.animgraph.AnimGraphSessions();
        sessions.setProjectRoot(() -> services.find(ProjectDirectory.class).map(ProjectDirectory::get)
                .filter(d -> d != null && !d.isBlank()).map(Path::of).orElse(null));
        services.publish(com.openmason.main.systems.animgraph.AnimGraphSessions.class, sessions);
        com.openmason.main.systems.animgraph.AnimGraphWorkspace editor = new com.openmason.main.systems.animgraph.AnimGraphWorkspace(
                services.require(FileDialogService.class), services.require(StatusService.class), workspaces, sessions);
        editor.setHeldItemProviders(() -> services.find(com.openmason.main.plugins.EditorRegistries.class)
                .map(r -> r.heldItems().all()).orElse(java.util.List.of()));
        services.publish(com.openmason.main.systems.animgraph.AnimGraphWorkspace.class, editor);
        context.workspaces().register(WorkspaceDescriptor.builder(BuiltinWorkspaces.ANIMGRAPH, "Animation Graph")
                .description("Animation Graph: state machines, blend spaces and layers with a live preview and debugger")
                .closable(true)
                .minSize(900f, 560f)
                .layoutVersion(com.openmason.main.systems.animgraph.AnimGraphWorkspace.LAYOUT_VERSION)
                .dockspaceName(MainDockLayout.ANIMGRAPH_DOCKSPACE_ID)
                .dock(editor)
                .build());
        editor.setResetLayoutAction(() -> layouts.requestReset(BuiltinWorkspaces.ANIMGRAPH));
        context.menuEntries().register(MenuItem.of("animgraph.tools.open", TopMenu.TOOLS, 146, "Animation Graph Editor",
                editor::show));
        context.fileTypes().register(BuiltinFileTypes.OMANIMGRAPH);
        context.fileOpeners().register(new FileOpener(BuiltinFileTypes.OMANIMGRAPH.id(), editor::openFile));
        context.mcpTools().register(BuiltinMcpTools.ANIMGRAPH);
        context.guideTopics().register(BuiltinKnowledge.topic("animgraph"));
        context.lifecycle().register(new LifecycleListener() {
            @Override
            public void shuttingDown() {
                editor.cleanup();
                sessions.close();
            }
        });
    }

    private static void scripting(PluginContext context) {
        context.menuEntries().register(MenuItem.of("scripting.tools.open", TopMenu.TOOLS, 120, "Scripting Editor",
                () -> context.services().find(ScriptingPresenter.class).ifPresent(ScriptingPresenter::show)));
        context.mcpTools().register(BuiltinMcpTools.SCRIPTING);
        context.mcpTools().register(BuiltinMcpTools.SCRIPT_LIBRARY);
        context.guideTopics().register(BuiltinKnowledge.topic("scripting"));
    }
}
