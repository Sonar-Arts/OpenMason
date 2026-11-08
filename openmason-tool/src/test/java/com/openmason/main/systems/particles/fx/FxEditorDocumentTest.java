package com.openmason.main.systems.particles.fx;

import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.openmason.engine.rendering.particles.FxParameter;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleLibrary;
import com.openmason.engine.rendering.particles.graph.FxGraph;
import com.openmason.engine.rendering.particles.graph.FxModule;
import com.openmason.engine.rendering.particles.graph.FxModules;
import com.openmason.engine.rendering.particles.graph.FxOp;
import com.openmason.engine.rendering.particles.graph.FxStageKind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The FX editor's document model (OM #104), GL-free: emitter and stack edits, graph edits through drafts,
 * undo/redo and merging, the recompile triggers the preview acts on, sprites, layout and files.
 */
@Tag("regression")
class FxEditorDocumentTest {

    @TempDir
    Path dir;

    private static List<String> emitters(FxEditorDocument d) {
        return d.system().emitters().stream().map(e -> e.name()).toList();
    }

    @Test
    void aNewDocumentStartsFromAPresetUnsaved() {
        assertTrue(FxEditorDocument.presets().containsAll(List.of("campfire", "smoke", "firework")));
        FxEditorDocument d = FxEditorDocument.create("campfire");
        assertEquals(List.of("fire", "embers", "smoke"), emitters(d));
        assertFalse(d.isDirty(), "an untouched preset has nothing to lose");
        assertTrue(d.file().isEmpty());
        assertFalse(d.canUndo());
        assertEquals("smoke", FxEditorDocument.create("smoke").system().emitters().get(0).effect().name());
        assertThrows(IllegalArgumentException.class, () -> FxEditorDocument.create("nope"));
    }

    @Test
    void emitterEditsAreUndoableSteps() {
        FxEditorDocument d = FxEditorDocument.create("campfire");
        String added = d.addEmitter("sparks");
        assertEquals("sparks", added);
        assertEquals(List.of("fire", "embers", "smoke", "sparks"), emitters(d));
        String copy = d.duplicateEmitter("fire");
        assertEquals("fire-2", copy);
        assertEquals("fire-2", d.emitter(copy).effect().name(), "the copy gets its own effect");
        d.moveEmitter("sparks", -10);
        assertEquals("sparks", emitters(d).get(0));

        d.undo();
        d.undo();
        assertEquals(List.of("fire", "embers", "smoke", "sparks"), emitters(d));
        d.redo();
        assertTrue(emitters(d).contains("fire-2"));
    }

    @Test
    void aSliderDragIsOneStepUntilSealed() {
        FxEditorDocument d = FxEditorDocument.create("campfire");
        float original = d.emitter("smoke").delay();
        for (float v : new float[]{0.9f, 1.0f, 1.1f}) {
            d.setEmitterField("smoke", "delay", DoubleNode.valueOf(v));
        }
        assertEquals(1.1f, d.emitter("smoke").delay(), 1e-6);
        d.undo();
        assertEquals(original, d.emitter("smoke").delay(), 1e-6, "one step for the whole drag");

        d.setEmitterField("smoke", "delay", DoubleNode.valueOf(2f));
        d.seal();
        d.setEmitterField("smoke", "delay", DoubleNode.valueOf(3f));
        d.undo();
        assertEquals(2f, d.emitter("smoke").delay(), 1e-6, "sealed: the second drag is its own step");
    }

    @Test
    void anInvalidEditChangesNothing() {
        FxEditorDocument d = FxEditorDocument.create("campfire");
        FxSystem before = d.system();
        assertThrows(IllegalArgumentException.class, () -> d.setEmitterField("fire", "loops", IntNode.valueOf(-1)));
        assertSame(before, d.system());
        assertFalse(d.canUndo());
        assertThrows(IllegalArgumentException.class, () -> d.renameEmitter("fire", "smoke"), "taken");
    }

    @Test
    void renamesFollowAndRemovalsTakeTheirReferencesAlong() {
        FxEditorDocument d = FxEditorDocument.create("campfire");
        d.renameEmitter("fire", "flames");
        assertEquals("flames", d.system().bindings().stream().filter(b -> b.emitter() != null).findFirst()
                .orElseThrow().emitter());
        int dropped = d.removeEmitter("flames");
        assertEquals(1, dropped, "the tint binding scoped to it");
        assertNull(d.effect("fire"), "its effect went with it");
        d.removeEmitter("embers");
        assertThrows(IllegalArgumentException.class, () -> d.removeEmitter("smoke"), "the last emitter stays");
    }

    @Test
    void stackEditsChangeOnlyTheirEffectAndTheDefaultStackIsWrittenAsDefault() {
        FxEditorDocument d = FxEditorDocument.create("campfire");
        ParticleEffect fire = d.effect("fire");
        FxEditorDocument.Change c = d.addModule("smoke", FxStageKind.PARTICLE_UPDATE,
                FxModules.curlNoiseVelocity(2f, 0.5f, 1f), 1);
        assertEquals(Set.of("smoke"), c.effects());
        assertSame(fire, d.effect("fire"), "untouched effects keep their objects (nothing to recompile)");
        List<String> names = d.stack("smoke", FxStageKind.PARTICLE_UPDATE).stream().map(FxModule::name).toList();
        assertEquals(List.of("lifetime", "curl noise velocity", "forces", "move", "spin", "trail"), names);

        d.moveModule("smoke", FxStageKind.PARTICLE_UPDATE, 1, 1);
        assertEquals("curl noise velocity", d.stack("smoke", FxStageKind.PARTICLE_UPDATE).get(2).name());
        d.removeModule("smoke", FxStageKind.PARTICLE_UPDATE, 2);
        assertNull(d.effect("smoke").graph(), "back to the default stacks: no graph at all");

        assertThrows(IllegalArgumentException.class,
                () -> d.removeModule("smoke", FxStageKind.PARTICLE_UPDATE, 0), "lifetime must come first");
        d.removeModule("smoke", FxStageKind.PARTICLE_UPDATE, 4);
        assertEquals(4, d.stack("smoke", FxStageKind.PARTICLE_UPDATE).size(), "built-ins can be dropped");
    }

    @Test
    void effectEditsTriggerExactlyTheSwapsTheyNeed() {
        FxEditorDocument d = FxEditorDocument.create("firework");
        FxSystem before = d.system();
        // The rocket spawns its trail through a sub-emitter: changing the trail touches both, nothing else.
        ParticleEffect stars = d.effect("firework-burst");
        ParticleEffect trail = d.effect("firework-trail");
        FxEditorDocument.Change c = d.putEffect(null, "firework-trail", trail.toBuilder().drag(trail.drag() + 1f).build());
        assertEquals(Set.of("firework-trail", "firework-rocket"), c.effects());
        assertSame(stars, d.effect("firework-burst"));
        List<FxHotSwap.Swap> swaps = FxHotSwap.plan(before, d.system());
        assertEquals(List.of("firework-trail", "firework-rocket"), swaps.stream().map(s -> s.updated().name()).toList(),
                "children first");

        FxSystem mid = d.system();
        d.setEmitterField(d.system().emitters().get(0).name(), "delay", DoubleNode.valueOf(0.3f));
        assertTrue(FxHotSwap.plan(mid, d.system()).isEmpty(), "a timeline edit recompiles no effect");
    }

    @Test
    void effectRenamesFollowEverywhere() {
        FxEditorDocument d = FxEditorDocument.create("firework");
        ParticleEffect trail = d.effect("firework-trail");
        d.putEffect(null, "firework-trail", trail.toBuilder().name("glitter").build());
        assertNull(d.effect("firework-trail"));
        assertEquals("glitter", d.effect("firework-rocket").subEmitters().get(0).effect().name());
    }

    @Test
    void graphEditsCommitWhenTheyCompileAndOtherwiseOnlyMoveTheDraft() {
        FxEditorDocument d = FxEditorDocument.create("smoke");
        d.addModule("smoke", FxStageKind.PARTICLE_UPDATE, FxModules.curlNoiseVelocity(2f, 0.5f, 1f), 1);
        FxEditorDocument.ModuleRef ref = new FxEditorDocument.ModuleRef("smoke", FxStageKind.PARTICLE_UPDATE, 1);
        d.openGraph(ref);
        assertNotNull(d.draft());
        FxModule committed = d.module(ref);

        d.editGraph(null, g -> g.disconnect("out", 0));
        assertEquals(committed, d.module(ref), "an unfinished graph never reaches the document");
        assertTrue(d.draftProblems().get("out").contains("not connected"));

        // Wiring a scalar into the velocity write compiles to a type error, pinned on the node.
        d.editGraph(null, g -> g.connect("freq", "out", 0));
        assertEquals(committed, d.module(ref));
        assertTrue(d.draftProblems().containsKey("out"), d.draftProblems().toString());

        FxEditorDocument.Change ok = d.editGraph(null, g -> g.connect("v2", "out", 0));
        assertTrue(d.draftProblems().isEmpty(), d.draftProblems().toString());
        assertEquals(committed, d.module(ref), "back where it was");

        FxEditorDocument.Change grew = d.editGraph(null, g -> g.addNode(FxOp.CONST, 0, 0).setValues("const", List.of(3f))
                .connect("const", "kdt", 0));
        assertEquals(Set.of("smoke"), grew.effects(), "a compiled graph edit recompiles its effect");
        assertTrue(d.module(ref).nodes().stream().anyMatch(n -> n.id().equals("const")));

        d.undo();
        assertFalse(d.module(ref).nodes().stream().anyMatch(n -> n.id().equals("const")));
        d.undo();
        d.undo();
        assertTrue(d.draftProblems().containsKey("out"), "undo restores the unfinished draft too");
        assertThrows(IllegalArgumentException.class,
                () -> d.openGraph(new FxEditorDocument.ModuleRef("smoke", FxStageKind.PARTICLE_UPDATE, 0)),
                "built-ins have no graph");
    }

    @Test
    void nodePositionsAreSavedWithTheDocument() throws IOException {
        FxEditorDocument d = FxEditorDocument.create("smoke");
        d.addModule("smoke", FxStageKind.PARTICLE_UPDATE, FxModules.attractor(0f, 2f, 0f, 3f), 1);
        FxEditorDocument.ModuleRef ref = new FxEditorDocument.ModuleRef("smoke", FxStageKind.PARTICLE_UPDATE, 1);
        d.openGraph(ref);
        d.editGraph("move:c", g -> g.move("c", 400f, 40f));
        d.editGraph("move:c", g -> g.move("c", 420f, 44f));
        assertTrue(d.isDirty());
        Path file = dir.resolve("attract.omfx");
        d.save(file);
        assertFalse(d.isDirty());

        FxEditorDocument reopened = FxEditorDocument.open(file);
        reopened.openGraph(ref);
        assertEquals(420f, reopened.draft().node("c").x());
        assertEquals(44f, reopened.draft().node("c").y());
        assertEquals(d.module(ref), reopened.module(ref));
        reopened.undo();
        assertFalse(reopened.canUndo(), "a reopened document starts a fresh history");
    }

    @Test
    void parametersRenameAndRemoveWithTheirBindings() {
        FxEditorDocument d = FxEditorDocument.create("campfire");
        d.putParameter(null, null, FxParameter.scalar("heat", 0.5f));
        assertEquals(3, d.system().parameters().size());
        d.putParameter(null, "intensity", FxParameter.scalar("power", 1f));
        assertTrue(d.system().bindings().stream().anyMatch(b -> b.parameter().equals("power")));
        assertEquals(2, d.removeParameter("power"), "rate and light bindings");
        assertThrows(IllegalArgumentException.class,
                () -> d.putParameter(null, null, FxParameter.scalar("tint", 1f)), "taken");
    }

    @Test
    void spritesEmbedAndTheirUsersAreKnown() throws IOException {
        FxEditorDocument d = FxEditorDocument.create("smoke");
        d.embedSprite("puff", png());
        ParticleEffect smoke = d.effect("smoke");
        d.putEffect(null, "smoke", smoke.toBuilder().textures(List.of("puff"), false).build());
        assertEquals(List.of("smoke"), d.spriteUsers("puff"));
        assertEquals(1, d.document().sprites().size());
        assertThrows(IOException.class, () -> d.embedSprite("bad", new byte[]{1, 2, 3}));
        d.removeSprite("puff");
        assertTrue(d.document().sprites().isEmpty());
    }

    @Test
    void v1LibrariesAreMigratedNotOpened() throws IOException {
        ParticleLibrary lib = new ParticleLibrary();
        lib.register(ParticleLibrary.Presets.SMOKE);
        lib.register(ParticleLibrary.Presets.SPARKS);
        Path v1 = dir.resolve("old.omfx");
        try (OutputStream out = Files.newOutputStream(v1)) {
            lib.write(out);
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> FxEditorDocument.open(v1));
        assertTrue(e.getMessage().contains("v1"));
        assertEquals(2, FxEditorDocument.migrate(v1).documents().size());
    }

    @Test
    void customModulesCanBeAddedEmptyAndEdited() {
        FxEditorDocument d = FxEditorDocument.create("sparks");
        FxModule empty = FxGraphDraft.empty("my module").lower().module();
        d.addModule("sparks", FxStageKind.PARTICLE_UPDATE, empty, 1);
        FxEditorDocument.ModuleRef ref = new FxEditorDocument.ModuleRef("sparks", FxStageKind.PARTICLE_UPDATE, 1);
        d.openGraph(ref);
        d.editGraph(null, g -> g.addNode(FxOp.READ, 0, 0).setAttribute("read", "velocity")
                .addNode(FxOp.NEG, 0, 0).connect("read", "neg", 0)
                .addNode(FxOp.WRITE, 0, 0).connect("neg", "write", 0));
        assertTrue(d.draftProblems().isEmpty(), d.draftProblems().toString());
        assertEquals(4, d.module(ref).nodes().size());
        assertNotNull(d.effect("sparks").graph());
        assertEquals(FxGraph.defaultStack(FxStageKind.PARTICLE_SPAWN), d.stack("sparks", FxStageKind.PARTICLE_SPAWN));
    }

    private static byte[] png() throws IOException {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(1, 1, 0xFFFF0000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
