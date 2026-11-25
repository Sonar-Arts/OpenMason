package com.openmason.main.systems.project;

import java.nio.file.Path;
import java.util.List;

/**
 * The application shell's project-session actions — what the Project Hub does when the user creates
 * or opens a project — exposed to non-UI callers (the {@code project_*} MCP tools). Registered in the
 * editor's service registry by the application once the UI exists. Every method runs on the main
 * (UI) thread; callers marshal.
 */
public interface ProjectSessions {

    /** A template the hub offers. {@code targetId} non-null = the template fixes the game target. */
    record TemplateInfo(String id, String name, String description, String category, String targetId) {}

    /** A project the hub knows: recent, or found in the projects base folder. */
    record ProjectRef(String name, String path, String lastOpened) {}

    /** The folder the hub creates projects in and scans for them. */
    Path projectsBaseFolder();

    /** True while the Project Hub (home screen) is showing instead of the editor. */
    boolean onHub();

    List<TemplateInfo> templates();

    /** Recently opened projects, most recent first. */
    List<ProjectRef> recentProjects();

    /** {@code .omp} files in the projects base folder (one sub-folder deep). */
    List<ProjectRef> projectsInBaseFolder();

    /**
     * Creates {@code directory/<name>.omp} exactly as the hub's Create button does: the directory chain
     * and project scaffold, the template's starter models, the pre-saved project file, the recent-projects
     * entry, then the editor on the new project (any open session is replaced without a prompt — the
     * caller asks first).
     *
     * @param templateId a {@link #templates()} id, or null for the blank template
     * @param targetId   the game target id, or null for none (a project-only project)
     * @return the written {@code .omp}, or null when the project file could not be written
     */
    Path createProject(String name, Path directory, String templateId, String targetId);

    /**
     * Opens an {@code .omp} as the hub's recent list does (session reset, project restore) and records it in
     * recent projects. Any open session is replaced without a prompt — the caller asks first.
     *
     * @return true when the project loaded
     */
    boolean openProject(Path ompPath);
}
