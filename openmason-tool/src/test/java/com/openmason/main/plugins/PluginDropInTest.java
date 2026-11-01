package com.openmason.main.plugins;

import com.openmason.api.OpenMasonPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drop-in plugins: a plain jar (no module-info, a {@code META-INF/services} entry) placed in the
 * plugin folder is found, loaded through its own class loader and registered like any plugin.
 */
class PluginDropInTest {

    private static final String PLUGIN = """
            package dropin;

            import com.openmason.api.OpenMasonPlugin;
            import com.openmason.api.PluginContext;

            public final class DropInPlugin implements OpenMasonPlugin {
                @Override public String id() { return "dropin"; }
                @Override public String displayName() { return "Drop-in"; }
                @Override public String version() { return "0.2"; }
                @Override public void register(PluginContext c) { }
            }
            """;

    private static Path classes;

    private final PluginManager manager = new PluginManager(new EditorRegistries());

    @BeforeAll
    static void compilePlugin(@TempDir Path build) throws Exception {
        // The tool and its API on the class path for javac, whichever way surefire launched us.
        String path = Stream.of(System.getProperty("jdk.module.path"), System.getProperty("java.class.path"))
                .filter(p -> p != null && !p.isBlank())
                .reduce((a, b) -> a + File.pathSeparator + b)
                .orElse("");
        var javac = java.util.spi.ToolProvider.findFirst("javac").orElse(null);
        assumeTrue(javac != null, "no javac tool in this runtime");
        Path src = build.resolve("src/dropin/DropInPlugin.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, PLUGIN);
        classes = build.resolve("classes");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);
        int rc = javac.run(errStream, errStream, "-cp", path, "-d", classes.toString(), "-proc:none", src.toString());
        assertEquals(0, rc, () -> "drop-in plugin failed to compile:\n" + err.toString(StandardCharsets.UTF_8));
    }

    @AfterEach
    void clearProperty() {
        System.clearProperty(PluginManager.PLUGINS_DIR_PROPERTY);
    }

    @Test
    void loadsAPluginJarFromTheFolder(@TempDir Path plugins) throws Exception {
        writeJar(plugins.resolve("dropin.jar"), true);

        List<OpenMasonPlugin> found = manager.discoverJars(plugins);

        assertEquals(List.of("dropin"), found.stream().map(OpenMasonPlugin::id).toList());
        assertNotSame(PluginManager.class.getClassLoader(), found.getFirst().getClass().getClassLoader(),
                "each jar gets its own loader");
        manager.registerAll(List.of(), found);
        assertTrue(manager.isActive("dropin"), () -> "not registered: " + manager.statuses());
    }

    @Test
    void readsThePropertyFolders(@TempDir Path a, @TempDir Path b) throws Exception {
        writeJar(b.resolve("dropin.jar"), true);
        System.setProperty(PluginManager.PLUGINS_DIR_PROPERTY, a + File.pathSeparator + b);

        assertEquals(List.of(a, b), PluginManager.pluginDirs());
        assertEquals(List.of("dropin"), manager.discoverDropIns().stream().map(OpenMasonPlugin::id).toList());
    }

    @Test
    void ignoresJarsWithoutAPluginAndOtherFiles(@TempDir Path plugins) throws Exception {
        writeJar(plugins.resolve("library.jar"), false);
        Files.writeString(plugins.resolve("notes.txt"), "not a plugin");
        Files.write(plugins.resolve("broken.jar"), new byte[]{1, 2, 3, 4});

        assertEquals(List.of(), manager.discoverJars(plugins));
    }

    @Test
    void aMissingFolderHasNoPlugins(@TempDir Path root) {
        assertEquals(List.of(), manager.discoverJars(root.resolve("absent")));
    }

    private static void writeJar(Path jar, boolean withService) throws Exception {
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream j = new JarOutputStream(out)) {
            if (withService) {
                j.putNextEntry(new JarEntry("META-INF/services/" + OpenMasonPlugin.class.getName()));
                j.write("dropin.DropInPlugin\n".getBytes(StandardCharsets.UTF_8));
                j.closeEntry();
            }
            j.putNextEntry(new JarEntry("dropin/DropInPlugin.class"));
            j.write(Files.readAllBytes(classes.resolve("dropin/DropInPlugin.class")));
            j.closeEntry();
        }
    }
}
