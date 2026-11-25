package com.openmason.main.systems.mcp;

import com.openmason.api.target.GameTarget;
import com.openmason.main.systems.project.ProjectService;
import com.openmason.main.systems.project.ProjectSessions;
import com.openmason.main.systems.services.EditorServices;
import com.openmason.main.systems.stateHandling.ModelState;
import com.openmason.main.systems.target.GameTargets;
import com.openmason.main.systems.threading.MainThreadExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Project sessions for agents ({@code project_info}, {@code project_list}, {@code project_create},
 * {@code project_open}): what the Project Hub does, through the application's {@link ProjectSessions}.
 *
 * <p>Safety, same rules as {@code model_open}: replacing a session that has unsaved project or model
 * changes asks the user (in-tool approval dialog); a project is created inside the projects base folder
 * (default: {@code <base>/<name>/<name>.omp}) unless the user approves another folder; an existing
 * {@code .omp} is never overwritten (open it instead).
 */
public final class ProjectSessionService {

    private static final long MAIN_TIMEOUT_S = 60;
    static final String OMP = ".omp";

    private final EditorServices editorServices;
    private final Supplier<ProjectSessions> sessions;
    private final Supplier<GameTargets> targets;

    public ProjectSessionService(EditorServices editorServices) {
        this(editorServices,
                () -> editorServices == null ? null
                        : editorServices.registry().find(ProjectSessions.class).orElse(null),
                GameTargets::shared);
    }

    /** Test seam: explicit sessions and targets. */
    ProjectSessionService(EditorServices editorServices, Supplier<ProjectSessions> sessions,
                          Supplier<GameTargets> targets) {
        this.editorServices = editorServices;
        this.sessions = sessions;
        this.targets = targets;
    }

    // ------------------------------------------------------------------ info

    /** Where the editor is: hub or editor, the open project, its target, unsaved state. */
    public Map<String, Object> info() {
        ProjectSessions s = requireSessions();
        return onMain(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("screen", s.onHub() ? "hub" : "editor");
            ProjectService ps = projectService();
            boolean open = ps != null && ps.hasCurrentProject();
            out.put("project_open", open);
            if (open) {
                out.put("name", ps.getCurrentProjectName());
                out.put("path", ps.getCurrentProjectPath());
                out.put("target", ps.getTargetId());
            }
            out.put("unsaved_project", ps != null && ps.hasUnsavedChanges());
            out.put("unsaved_model", modelDirty());
            out.put("projects_folder", s.projectsBaseFolder().toString());
            return out;
        });
    }

    /** Templates, game targets, recent projects and the projects found in the base folder. */
    public Map<String, Object> list() {
        ProjectSessions s = requireSessions();
        return onMain(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("projects_folder", s.projectsBaseFolder().toString());
            out.put("templates", s.templates());
            List<Map<String, Object>> targetRows = new ArrayList<>();
            GameTargets t = targets.get();
            String defaultTarget = t == null ? null : t.defaultTarget().map(GameTarget::id).orElse(null);
            if (t != null) {
                for (GameTarget g : t.all()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", g.id());
                    row.put("name", g.displayName());
                    row.put("default", g.id().equals(defaultTarget));
                    targetRows.add(row);
                }
            }
            out.put("targets", targetRows);
            out.put("recent", s.recentProjects());
            out.put("in_projects_folder", s.projectsInBaseFolder());
            return out;
        });
    }

    // ---------------------------------------------------------------- create

    /**
     * Creates and opens a project.
     *
     * @param directory  null = {@code <base>/<name>}; relative = under the base folder; absolute outside the
     *                   base folder needs the user's approval
     * @param templateId null = blank
     * @param targetId   null = the template's target, else the default target; {@code "none"} = no target
     */
    public Map<String, Object> create(String name, String directory, String templateId, String targetId,
                                      int timeoutSeconds) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        ProjectSessions s = requireSessions();
        String safe = sanitize(name.trim());
        Path base = s.projectsBaseFolder().toAbsolutePath().normalize();
        Path dir;
        if (directory == null || directory.isBlank()) {
            dir = base.resolve(safe);
        } else {
            Path given = Path.of(directory.trim());
            dir = (given.isAbsolute() ? given : base.resolve(given)).toAbsolutePath().normalize();
        }
        Path omp = dir.resolve(safe + OMP);
        if (Files.exists(omp)) {
            throw new IllegalArgumentException("project_exists: " + omp + " already exists — open it with project_open");
        }
        String template = templateId == null || templateId.isBlank() ? null : templateId.trim();
        String target = resolveTarget(s, template, targetId);

        List<String> reasons = new ArrayList<>();
        if (!dir.startsWith(base)) {
            reasons.add("The folder is outside the projects folder (" + base + ").");
        }
        if (sessionDirty()) {
            reasons.add("The open session has unsaved changes; they will be lost.");
        }
        if (!reasons.isEmpty()) {
            List<String> details = new ArrayList<>();
            details.add("Project file: " + omp);
            details.addAll(reasons);
            Map<String, Object> declined = McpApprovals.confirm(gate(), "project_create",
                    "An agent asks to create the project '" + safe + "'", details, timeoutSeconds);
            if (declined != null) {
                return declined;
            }
        }
        final Path targetDir = dir;
        final String finalTarget = target;
        return onMain(() -> {
            Path written = s.createProject(safe, targetDir, template, finalTarget);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("opened", written != null);
            if (written != null) {
                out.put("name", safe);
                out.put("path", written.toString());
                out.put("target", finalTarget);
                out.put("template", template == null ? "blank" : template);
            } else {
                out.put("reason", "write_failed");
                out.put("message", "the project file could not be written to " + omp + " — see the Open Mason log");
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ open

    /**
     * Opens a project: an absolute {@code .omp} path, a path relative to the projects folder, or the name
     * of a recent / projects-folder project (case-insensitive).
     */
    public Map<String, Object> open(String pathOrName, int timeoutSeconds) {
        if (pathOrName == null || pathOrName.isBlank()) {
            throw new IllegalArgumentException("project is required");
        }
        ProjectSessions s = requireSessions();
        Path omp = locate(s, pathOrName.trim());
        if (sessionDirty()) {
            Map<String, Object> declined = McpApprovals.confirm(gate(), "project_open",
                    "An agent asks to open the project '" + stem(omp) + "'",
                    List.of("Project file: " + omp, "The open session has unsaved changes; they will be lost."),
                    timeoutSeconds);
            if (declined != null) {
                return declined;
            }
        }
        return onMain(() -> {
            boolean ok = s.openProject(omp);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("opened", ok);
            out.put("path", omp.toString());
            if (ok) {
                ProjectService ps = projectService();
                out.put("name", ps != null ? ps.getCurrentProjectName() : stem(omp));
                out.put("target", ps != null ? ps.getTargetId() : null);
            } else {
                out.put("reason", "load_failed");
                out.put("message", "the project did not open — see the Open Mason log");
            }
            return out;
        });
    }

    /** An existing .omp from a path or a known project name. */
    Path locate(ProjectSessions s, String spelled) {
        Path given = Path.of(spelled);
        Path base = s.projectsBaseFolder().toAbsolutePath().normalize();
        if (spelled.toLowerCase(java.util.Locale.ROOT).endsWith(OMP) || given.getNameCount() > 1 || given.isAbsolute()) {
            Path p = (given.isAbsolute() ? given : base.resolve(given)).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) {
                p = p.resolve(p.getFileName() + OMP);
            }
            if (!p.toString().toLowerCase(java.util.Locale.ROOT).endsWith(OMP)) {
                throw new IllegalArgumentException("not_a_project: " + p + " is not an .omp file");
            }
            if (!Files.isRegularFile(p)) {
                throw new IllegalArgumentException("no_such_project: " + p);
            }
            return p;
        }
        List<ProjectSessions.ProjectRef> known = onMain(() -> {
            List<ProjectSessions.ProjectRef> all = new ArrayList<>(s.recentProjects());
            all.addAll(s.projectsInBaseFolder());
            return all;
        });
        for (ProjectSessions.ProjectRef ref : known) {
            if (ref.name() != null && ref.name().equalsIgnoreCase(spelled) && ref.path() != null
                    && Files.isRegularFile(Path.of(ref.path()))) {
                return Path.of(ref.path());
            }
        }
        // The hub's layout: <base>/<name>/<name>.omp, names compared case-insensitively.
        try (var dirs = Files.list(base)) {
            for (Path d : dirs.filter(Files::isDirectory).toList()) {
                if (!d.getFileName().toString().equalsIgnoreCase(sanitize(spelled))) continue;
                try (var files = Files.list(d)) {
                    for (Path f : files.toList()) {
                        if (Files.isRegularFile(f) && f.getFileName().toString()
                                .equalsIgnoreCase(sanitize(spelled) + OMP)) {
                            return f;
                        }
                    }
                }
            }
        } catch (java.io.IOException e) {
            // an unreadable projects folder: fall through to "no such project"
        }
        throw new IllegalArgumentException("no_such_project: no recent or projects-folder project named '"
                + spelled + "' — project_list shows them, or pass an .omp path");
    }

    // --------------------------------------------------------------- helpers

    private String resolveTarget(ProjectSessions s, String template, String targetId) {
        GameTargets t = targets.get();
        if (targetId != null && !targetId.isBlank()) {
            String id = targetId.trim();
            if (id.equalsIgnoreCase("none")) return null;
            if (t == null || t.find(id).isEmpty()) {
                throw new IllegalArgumentException("unknown_target: " + id + " — project_list shows the installed targets");
            }
            return id;
        }
        if (template != null) {
            for (ProjectSessions.TemplateInfo info : onMain(s::templates)) {
                if (template.equals(info.id())) {
                    if (info.targetId() != null) return info.targetId();
                    return t == null ? null : t.defaultTarget().map(GameTarget::id).orElse(null);
                }
            }
            throw new IllegalArgumentException("unknown_template: " + template + " — project_list shows the templates");
        }
        return t == null ? null : t.defaultTarget().map(GameTarget::id).orElse(null);
    }

    private boolean sessionDirty() {
        return onMain(() -> {
            ProjectService ps = projectService();
            return (ps != null && ps.hasUnsavedChanges()) || modelDirty();
        });
    }

    private boolean modelDirty() {
        ModelState st = editorServices != null ? editorServices.getModelState() : null;
        return st != null && st.isModelLoaded() && st.hasUnsavedChanges();
    }

    private ProjectService projectService() {
        return editorServices == null ? null : editorServices.registry().find(ProjectService.class).orElse(null);
    }

    private com.openmason.main.systems.mcp.approval.ApprovalGate gate() {
        return editorServices != null ? editorServices.getApprovalGate() : null;
    }

    private ProjectSessions requireSessions() {
        ProjectSessions s = sessions.get();
        if (s == null) {
            throw new IllegalStateException("Project sessions unavailable — is the Open Mason UI running?");
        }
        return s;
    }

    static String sanitize(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "Untitled" : s;
    }

    private static String stem(Path omp) {
        String f = omp.getFileName().toString();
        return f.toLowerCase(java.util.Locale.ROOT).endsWith(OMP) ? f.substring(0, f.length() - OMP.length()) : f;
    }

    private static <T> T onMain(Callable<T> task) {
        try {
            return MainThreadExecutor.submit(task).get(MAIN_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Operation timed out on main thread", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }
}
