package com.openmason.main.systems.particles.export;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.openmason.api.asset.AssetEditorSection;
import com.openmason.api.asset.AssetSectionContribution;
import com.openmason.api.asset.AssetSurface;
import com.openmason.api.files.FileType;
import com.openmason.api.fx.FxExportInput;
import com.openmason.api.fx.FxExportTarget;
import com.openmason.api.fx.FxExporter;
import com.openmason.api.target.AssetFolders;
import com.openmason.api.target.GameTarget;
import com.openmason.api.target.ResourceRoot;
import com.openmason.api.target.WriteRules;
import com.openmason.engine.rendering.particles.FxPresets;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleLibrary;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.openmason.engine.rendering.particles.omfx.OmfxSprite;
import com.openmason.main.systems.io.WriteRoots;
import com.openmason.main.systems.menus.dialogs.AssetSectionSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #102: a test game contributes an FX container; the export service and the Export FX window's controller list
 * it, hand it the checked system, and write its bytes to a file or into the game target's checkout.
 */
class FxExportServiceTest {

    @TempDir
    Path tmp;

    private Path game;
    private GameTarget testGame;
    private final List<FxExportInput> seen = new ArrayList<>();

    /** The test game's container: "TESTFX <id> <emitters> <sprites> <game data>". */
    private final FxExporter exporter = new FxExporter() {
        @Override
        public byte[] export(FxExportInput in) {
            seen.add(in);
            String data = in.document().extensions().tree("testgame:fx").map(Object::toString).orElse("-");
            return ("TESTFX " + in.id() + " " + in.system().emitters().size() + " " + in.sprites().keySet() + " "
                    + data).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public List<String> problems(FxExportInput in) {
            return in.system().light() != null ? List.of("lights are not supported") : List.of();
        }
    };

    private final FxExportTarget testFx = new FxExportTarget("testgame.tfx", "Test FX (.tfx)",
            FileType.of("tfx", "Test FX", ".tfx").withSave("target", "tfx", "fx"), "testgame", exporter);

    @BeforeEach
    void setUp() throws IOException {
        game = Files.createDirectories(tmp.resolve("game"));
        testGame = GameTarget.builder("testgame", "Test Game").resources(ResourceRoot.at(game))
                .folders(AssetFolders.builder().base("tfx", "assets/fx").build()).writes(WriteRules.SHIPPED_ASSETS)
                .build();
        FxExportService.setSource(() -> List.of(OMFX, testFx));
    }

    @AfterEach
    void tearDown() {
        FxExportService.setSource(null);
        AssetSectionSet.setSource(null);
    }

    private static final FxExportTarget OMFX = new FxExportTarget("openmason.omfx", "Open Mason FX (.omfx v2)",
            FileType.of("omfx", "FX", ".omfx").withSave("project", "Particles", "particles"), null,
            in -> com.openmason.engine.rendering.particles.omfx.OmfxCodec.write(in.document()));

    private FxExportService service(GameTarget active) {
        return new FxExportService(WriteRoots.forTarget(tmp.resolve("proj"), active, tmp.resolve("exports")));
    }

    @Test
    void theContainerGetsTheCheckedSystemWithDecodedSprites() throws Exception {
        ParticleEffect puff = ParticleEffect.builder("puff").rate(10).textures(List.of("dot", "host"), false).build();
        OmfxDocument doc = OmfxDocument.of(FxSystem.of(puff, 1)).withSprite(new OmfxSprite("dot", png()));
        FxExportService.Plan plan = service(testGame).plan(testFx, doc);
        assertTrue(plan.ok());
        assertEquals("puff.tfx", plan.fileName());
        assertEquals("TESTFX puff 1 [dot] -", new String(plan.bytes(), StandardCharsets.UTF_8));
        FxExportInput in = seen.get(0);
        assertEquals(2, in.sprites().get("dot").width());
        assertEquals(List.of("host"), List.copyOf(in.externalTextures()));
    }

    @Test
    void problemsBlockTheExport() throws Exception {
        FxExportService.Plan plan = service(testGame).plan(testFx, OmfxDocument.of(FxPresets.CAMPFIRE));
        assertFalse(plan.ok());
        assertEquals(List.of("lights are not supported"), plan.problems());
        assertTrue(seen.isEmpty(), "export is not called");
        assertThrows(IOException.class, () -> service(testGame).deploy(plan, true));
    }

    @Test
    void deployingNeedsTheContainersGameTargetAndAWritableCheckout() {
        FxExportService.Deployment ok = service(testGame).deployment(testFx);
        assertTrue(ok.deployable(), ok.reason());
        assertEquals(game.resolve("assets/fx").toAbsolutePath().normalize(), ok.folder());

        GameTarget other = GameTarget.builder("othergame", "Other").resources(ResourceRoot.at(game)).build();
        assertTrue(service(other).deployment(testFx).reason().contains("belongs to game target 'testgame' (the "
                + "project targets 'othergame')"));
        assertTrue(service(null).deployment(testFx).reason().contains("the project has no game target"));
        assertTrue(service(testGame.withWrites(WriteRules.READ_ONLY)).deployment(testFx).reason()
                .contains("not available or not writable"));
        assertTrue(service(testGame).deployment(OMFX).reason().contains("export format"));
        assertFalse(OMFX.deploys());
        assertTrue(testFx.deploys());
    }

    @Test
    void deployWritesIntoTheCheckoutAndNeverSilentlyReplacesADifferentFile() throws Exception {
        FxExportService svc = service(testGame);
        OmfxDocument doc = OmfxDocument.of(FxSystem.of(ParticleLibrary.Presets.SPARKS, 60));
        FxExportService.Deployed first = svc.deploy(svc.plan(testFx, doc), false);
        Path file = game.resolve("assets/fx/sparks.tfx");
        assertTrue(first.written());
        assertEquals("TESTFX sparks 1 [] -", Files.readString(file));

        FxExportService.Deployed again = svc.deploy(svc.plan(testFx, doc), false);
        assertFalse(again.written(), "identical bytes are left alone");
        assertFalse(again.conflict());

        Files.writeString(file, "the game's own edit");
        FxExportService.Deployed conflict = svc.deploy(svc.plan(testFx, doc), false);
        assertTrue(conflict.conflict());
        assertEquals("the game's own edit", Files.readString(file));
        assertTrue(svc.deploy(svc.plan(testFx, doc), true).written());
        assertEquals("TESTFX sparks 1 [] -", Files.readString(file));
    }

    @Test
    void theWindowListsTargetsPrefersTheGameFormatAndPassesSectionDataToTheContainer() throws Exception {
        // a plugin section on the Export FX surface writes its game data into the document's extensions
        AssetSectionSet.setSource(() -> List.of(new AssetSectionContribution<>("testgame.fx.data",
                AssetSurface.FX_EXPORT, 100, host -> new AssetEditorSection<OmfxDocument>() {
            String loadedFor;

            @Override
            public String title() {
                return "Game Data";
            }

            @Override
            public void load(OmfxDocument manifest) {
                loadedFor = host.objectId() + "/" + host.objectType();
            }

            @Override
            public void render() {
            }

            @Override
            public OmfxDocument write(OmfxDocument manifest) {
                return manifest.withExtensions(manifest.extensions().withTree("testgame:fx",
                        JsonNodeFactory.instance.objectNode().put("loadedFor", loadedFor)));
            }
        })));

        FxExportController c = new FxExportController(service(testGame));
        c.open(OmfxDocument.of(FxSystem.of(ParticleLibrary.Presets.SPARKS, 60)), List.of());
        assertEquals(List.of("openmason.omfx", "testgame.tfx"), c.targets().stream().map(FxExportTarget::id).toList());
        assertEquals("testgame.tfx", c.target().id(), "the deployable game format is preselected");
        assertEquals(List.of("Export", "Game Data"),
                c.sections().tabs(List.of(new AssetSectionSet.Tab("export", "Export", 0, null))).stream()
                        .map(AssetSectionSet.Tab::label).toList());

        c.deploy();
        assertFalse(c.failed(), c.message());
        assertEquals("TESTFX sparks 1 [] {\"loadedFor\":\"sparks/fx\"}",
                Files.readString(game.resolve("assets/fx/sparks.tfx")));

        Files.writeString(game.resolve("assets/fx/sparks.tfx"), "changed in the game");
        c.deploy();
        assertTrue(c.hasConflict());
        c.resolveConflict(false);
        assertEquals("changed in the game", Files.readString(game.resolve("assets/fx/sparks.tfx")));
        assertTrue(c.message().contains("kept the game's"));
        c.deploy();
        c.resolveConflict(true);
        assertTrue(Files.readString(game.resolve("assets/fx/sparks.tfx")).startsWith("TESTFX"));

        c.select(0);
        Path out = tmp.resolve("picked/sparks.omfx");
        c.exportTo(out);
        assertFalse(c.failed(), c.message());
        OmfxDocument written = com.openmason.engine.rendering.particles.omfx.OmfxCodec.read(out);
        assertEquals("{\"loadedFor\":\"sparks/fx\"}", written.extensions().tree("testgame:fx").orElseThrow()
                .toString(), "the neutral source keeps the game data too");

        c.open(OmfxDocument.of(FxPresets.CAMPFIRE), List.of());
        c.deploy();
        assertTrue(c.failed());
        assertTrue(c.message().contains("cannot carry this system: lights are not supported"), c.message());
    }

    private static byte[] png() throws IOException {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
