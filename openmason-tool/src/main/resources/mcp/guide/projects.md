# Projects (.omp)

A project is an `.omp` file plus its folder (models, scenes, textures beside it).
The Project Hub creates and opens them; these tools do the same, from the hub
or from the editor.

- `project_info` — hub or editor, the open project (name, path, game target),
  unsaved project/model changes, the projects folder.
- `project_list` — templates (`id`, `targetId` when a template fixes the game),
  installed game targets (with the default), recent projects, and the `.omp`
  files in the projects folder.
- `project_create {name, directory?, template?, target?}` — like the hub's
  Create button: `<projects folder>/<name>/<name>.omp`, the project scaffold,
  the template's starter models (one may open in the editor), the recent
  entry, then the editor on the new project. `directory` relative = under the
  projects folder; a folder outside it asks the user. `target`: a target id,
  `"none"` for a project-only project, default = the template's target, else
  the default target. Never overwrites an existing `.omp` (`project_exists`:
  use `project_open`).
- `project_open {project}` — an `.omp` path (relative = under the projects
  folder; a project folder works too) or the name of a recent / projects-folder
  project.

Both create and open replace the open session: with unsaved project or model
changes the user gets an approval dialog first (`user_declined` / `timeout` come
back as a structured result, not an error). Save with `model_save` first if you
made the changes.
