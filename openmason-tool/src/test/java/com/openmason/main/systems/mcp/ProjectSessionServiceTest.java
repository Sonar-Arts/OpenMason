package com.openmason.main.systems.mcp;

import com.openmason.api.ServiceRegistry;
import com.openmason.main.systems.project.ProjectService;
import com.openmason.main.systems.project.ProjectSessions;
import com.openmason.main.systems.services.EditorServices;
import com.openmason.main.systems.target.GameTargets;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The project_* tools' policy over a fake hub: locations, approvals, name lookup, refusals. */
class ProjectSessionServiceTest {

    @TempDir
    Path tmp;

    private Path base;
    private FakeSessions sessions;
    private ServiceRegistry registry;
    private ProjectSessionService service;

    /** Records what the hub was asked to do; "creates" the .omp so later lookups see it. */
    private final class FakeSessions implements ProjectSessions {
        final List<String> calls = new ArrayList<>();
        final List<ProjectRef> recent = new ArrayList<>();
        boolean failWrites;

        @Override public Path projectsBaseFolder() { return base; }
        @Override public boolean onHub() { return true; }
        @Override public List<TemplateInfo> templates() {
            return List.of(new TemplateInfo("blank", "Blank", "", "Basic", null),
                    new TemplateInfo("mob", "Mob", "", "Game", "stonebreak"));
        }
        @Override public List<ProjectRef> recentProjects() { return recent; }
        @Override public List<ProjectRef> projectsInBaseFolder() { return List.of(); }

        @Override
        public Path createProject(String name, Path directory, String templateId, String targetId) {
            calls.add("create " + name + " " + directory + " " + templateId + " " + targetId);
            if (failWrites) return null;
            try {
                Files.createDirectories(directory);
                return Files.writeString(directory.resolve(name + ".omp"), "{}");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean openProject(Path ompPath) {
            calls.add("open " + ompPath);
            return true;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        MainThreadExecutor.bindToCurrentThread();
        base = Files.createDirectories(tmp.resolve("Projects"));
        sessions = new FakeSessions();
        registry = new ServiceRegistry();
        registry.put(ProjectService.class, new ProjectService(new GameTargets(List::of)));
        service = new ProjectSessionService(new EditorServices(registry), () -> sessions,
                () -> new GameTargets(List::of));
    }

    @Test
    void createDefaultsToAFolderPerProjectUnderTheProjectsFolder() {
        Map<String, Object> out = service.create("My Mob", null, null, null, 0);
        assertEquals(true, out.get("opened"));
        Path omp = base.resolve("My Mob").resolve("My Mob.omp");
        assertEquals(omp.toString(), out.get("path"));
        assertEquals(List.of("create My Mob " + base.resolve("My Mob") + " null null"), sessions.calls);
        assertNull(out.get("target"), "no targets installed: a project-only project");
    }

    @Test
    void namesAreSanitizedAndRelativeFoldersLandUnderTheProjectsFolder() {
        Map<String, Object> out = service.create("a/b:c", "group", null, null, 0);
        assertEquals(base.resolve("group").resolve("a_b_c.omp").toString(), out.get("path"));
    }

    @Test
    void aTemplateThatFixesItsGameDecidesTheTarget() {
        Map<String, Object> out = service.create("Cow", null, "mob", null, 0);
        assertEquals("stonebreak", out.get("target"));
        assertEquals("mob", out.get("template"));
        assertThrows(IllegalArgumentException.class, () -> service.create("X", null, "nope", null, 0));
        assertThrows(IllegalArgumentException.class, () -> service.create("X", null, null, "unknown-game", 0));
        assertNull(service.create("Y", null, null, "none", 0).get("target"));
    }

    @Test
    void anExistingProjectIsNeverOverwritten() {
        service.create("Dup", null, null, null, 0);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create("Dup", null, null, null, 0));
        assertTrue(e.getMessage().startsWith("project_exists"), e.getMessage());
        assertEquals(1, sessions.calls.size());
    }

    @Test
    void aFolderOutsideTheProjectsFolderNeedsTheUser() {
        Map<String, Object> out = service.create("Away", tmp.resolve("elsewhere").toString(), null, null, 0);
        assertEquals(false, out.get("opened"));
        assertEquals("approval_unavailable", out.get("reason"), "no UI to ask in a test: declined, not written");
        assertTrue(sessions.calls.isEmpty());
        // A relative path that climbs out counts as outside too.
        assertEquals(false, service.create("Up", "../up", null, null, 0).get("opened"));
    }

    @Test
    void unsavedWorkNeedsTheUserBeforeCreatingOrOpening() throws IOException {
        Path omp = Files.createDirectories(base.resolve("Old")).resolve("Old.omp");
        Files.writeString(omp, "{}");
        registry.find(ProjectService.class).orElseThrow().markDirty();
        assertEquals("approval_unavailable", service.create("New", null, null, null, 0).get("reason"));
        assertEquals("approval_unavailable", service.open(omp.toString(), 0).get("reason"));
        assertTrue(sessions.calls.isEmpty());
    }

    @Test
    void openAcceptsPathsFoldersAndKnownNames() throws IOException {
        Path dir = Files.createDirectories(base.resolve("Castle"));
        Path omp = Files.writeString(dir.resolve("Castle.omp"), "{}");
        assertEquals(true, service.open(omp.toString(), 0).get("opened"));
        assertEquals(omp.toString(), service.open("Castle/Castle.omp", 0).get("path"));
        assertEquals(omp.toString(), service.open(dir.toString(), 0).get("path"), "a project folder");
        assertEquals(omp.toString(), service.open("castle", 0).get("path"), "folder-per-project name");

        Path elsewhere = Files.createDirectories(tmp.resolve("Other")).resolve("Tower.omp");
        Files.writeString(elsewhere, "{}");
        sessions.recent.add(new ProjectSessions.ProjectRef("Tower", elsewhere.toString(), null));
        assertEquals(elsewhere.toString(), service.open("TOWER", 0).get("path"), "recent name, any case");
        assertEquals(4 + 1, sessions.calls.size());
    }

    @Test
    void openRefusesWhatIsNotAProject() throws IOException {
        Path txt = Files.writeString(base.resolve("notes.txt"), "x");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.open(txt.toString(), 0))
                .getMessage().startsWith("not_a_project"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.open("missing.omp", 0))
                .getMessage().startsWith("no_such_project"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.open("Nobody", 0))
                .getMessage().startsWith("no_such_project"));
    }

    @Test
    void aFailedWriteIsReportedNotThrown() {
        sessions.failWrites = true;
        Map<String, Object> out = service.create("Broken", null, null, null, 0);
        assertEquals(false, out.get("opened"));
        assertEquals("write_failed", out.get("reason"));
    }

    @Test
    void infoAndListDescribeTheHub() {
        Map<String, Object> info = service.info();
        assertEquals("hub", info.get("screen"));
        assertEquals(false, info.get("project_open"));
        assertEquals(base.toString(), info.get("projects_folder"));
        Map<String, Object> list = service.list();
        assertEquals(2, ((List<?>) list.get("templates")).size());
    }
}
