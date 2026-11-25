package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import static com.openmason.main.systems.mcp.McpArgs.optInt;
import static com.openmason.main.systems.mcp.McpArgs.optString;
import static com.openmason.main.systems.mcp.McpArgs.reqString;

/**
 * {@code project_info} / {@code project_list} / {@code project_create} / {@code project_open} over
 * {@link ProjectSessionService}: the Project Hub's create and open flows for agents. Guide topic:
 * {@code describe_api projects}.
 */
public final class ProjectToolDefinitions {

    private final ProjectSessionService projects;
    private final ObjectMapper mapper;

    public ProjectToolDefinitions(ProjectSessionService projects, ObjectMapper mapper) {
        this.projects = projects;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        registry.register(new McpTool(
                "project_info",
                "Hub/editor, open project, unsaved state.",
                McpSchema.of(mapper).build(),
                args -> projects.info()));

        registry.register(new McpTool(
                "project_list",
                "Templates, game targets, recent and projects-folder projects.",
                McpSchema.of(mapper).build(),
                args -> projects.list()));

        registry.register(new McpTool(
                "project_create",
                "Create + open a project (hub Create): <projects folder>/<name>/<name>.omp. Outside the "
                        + "projects folder or unsaved work asks the user; never overwrites.",
                McpSchema.of(mapper)
                        .str("name", "Project name").required("name")
                        .str("directory", "Folder (relative = projects folder)")
                        .str("template", "Template id (default blank)")
                        .str("target", "Target id or none")
                        .intg("timeout_seconds", "User wait, default 60")
                        .build(),
                args -> projects.create(reqString(args, "name"), optString(args, "directory"),
                        optString(args, "template"), optString(args, "target"), optInt(args, "timeout_seconds", 0))));

        registry.register(new McpTool(
                "project_open",
                "Open a project by .omp path, folder or name (works from the hub). Unsaved work asks the user.",
                McpSchema.of(mapper)
                        .str("project", ".omp path, folder or name").required("project")
                        .intg("timeout_seconds", "User wait, default 60")
                        .build(),
                args -> projects.open(reqString(args, "project"), optInt(args, "timeout_seconds", 0))));
    }
}
