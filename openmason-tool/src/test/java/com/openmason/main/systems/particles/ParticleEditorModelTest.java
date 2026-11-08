package com.openmason.main.systems.particles;

import com.openmason.engine.rendering.particles.FxPresets;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleLibrary;
import com.openmason.engine.rendering.particles.ParticleSystem;
import com.openmason.engine.rendering.particles.omfx.OmfxCodec;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The Particle Editor's document rules, GL- and ImGui-free. */
class ParticleEditorModelTest {

    private static ParticleEditorModel modelFrom(String json) throws Exception {
        Path tmp = Files.createTempFile("particles", ".omfx");
        try {
            Files.writeString(tmp, json);
            ParticleEditorModel m = new ParticleEditorModel();
            m.open(tmp);
            return m;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void aNewDocumentHoldsTheBuiltInPresetsAndIsClean() {
        ParticleEditorModel m = new ParticleEditorModel();
        assertEquals(ParticleLibrary.Presets.ALL.size(), m.names().size());
        assertTrue(m.selected().isPresent());
        assertFalse(m.isDirty());
        assertTrue(m.file().isEmpty());
    }

    @Test
    void newAndDuplicatedEffectsGetUniqueNames() {
        ParticleEditorModel m = new ParticleEditorModel();
        assertEquals("effect", m.create("effect").name());
        assertEquals("effect-2", m.create("effect").name());
        m.select("smoke");
        assertEquals("smoke-2", m.duplicateSelected().orElseThrow().name());
        assertEquals("smoke-2", m.selected().orElseThrow().name());
        assertTrue(m.isDirty());
    }

    @Test
    void editingAnEffectRebuildsEveryEffectThatSpawnsIt() {
        ParticleEditorModel m = new ParticleEditorModel();
        m.select("rain-splash");
        ParticleEffect old = m.selected().orElseThrow();
        ParticleEffect edited = old.toBuilder().gravity(0, -30, 0).build();
        List<ParticleEditorModel.Swap> swaps = m.apply(edited);

        ParticleEffect rain = m.library().get("rain");
        assertTrue(rain.subEmitters().stream().anyMatch(s -> s.effect() == edited), "rain now spawns the edit");
        assertEquals(2, swaps.size(), "the splash and the rain that spawns it");
        assertSame(old, swaps.get(0).old());
        assertSame(edited, swaps.get(0).updated());
        assertEquals("rain", swaps.get(1).updated().name());
        assertEquals(ParticleLibrary.Presets.ALL.size(), m.names().size(), "nothing added or lost");
    }

    @Test
    void renamingKeepsReferencesAndLeavesNoGhostOfTheOldName() {
        ParticleEditorModel m = new ParticleEditorModel();
        m.select("smoke");
        m.apply(m.selected().orElseThrow().toBuilder().name("soot").build());
        assertFalse(m.library().contains("smoke"), "no ghost entry under the old name");
        assertEquals("soot", m.selected().orElseThrow().name());
        assertEquals("soot", m.library().get("explosion").subEmitters().get(0).effect().name());
        assertEquals(List.of("soot"), m.names().subList(0, 1), "kept its place in the list");

        m.select("fire");
        assertThrows(IllegalArgumentException.class,
                () -> m.apply(m.selected().orElseThrow().toBuilder().name("soot").build()), "name taken");
    }

    @Test
    void diamondReferencesEndUpPointingAtTheNewestVersions() throws Exception {
        ParticleEditorModel m = modelFrom("""
                { "effects": [
                    { "name": "x" },
                    { "name": "a", "subEmitters": [ { "effect": "x" } ] },
                    { "name": "b", "subEmitters": [ { "effect": "x" }, { "effect": "a" } ] } ] }
                """);
        m.select("x");
        ParticleEffect x2 = m.selected().orElseThrow().toBuilder().rate(99).build();
        m.apply(x2);
        ParticleEffect a = m.library().get("a");
        ParticleEffect b = m.library().get("b");
        assertSame(x2, a.subEmitters().get(0).effect());
        assertSame(x2, b.subEmitters().get(0).effect());
        assertSame(a, b.subEmitters().get(1).effect(), "b holds the rebuilt a, not the stale one");
        assertEquals(List.of("x", "a", "b"), m.names());
    }

    @Test
    void subEmitterLoopsAreRefusedAndNotOffered() {
        ParticleEditorModel m = new ParticleEditorModel();
        m.select("smoke");
        assertFalse(m.subEmitterCandidates().contains("smoke"), "not itself");
        assertFalse(m.subEmitterCandidates().contains("explosion"), "explosion already spawns smoke");
        assertTrue(m.subEmitterCandidates().contains("sparks"));
        ParticleEffect loop = m.selected().orElseThrow().toBuilder()
                .subEmitter(ParticleEffect.SubEmitter.on(ParticleEffect.Trigger.DEATH, m.library().get("explosion"), 1))
                .build();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> m.apply(loop));
        assertTrue(e.getMessage().contains("loop"), e.getMessage());
    }

    @Test
    void deletingIsRefusedWhileAnotherEffectSpawnsIt() {
        ParticleEditorModel m = new ParticleEditorModel();
        m.select("firework-burst");
        assertEquals(List.of("firework"), m.deleteSelected());
        assertTrue(m.library().contains("firework-burst"));
        m.select("firework");
        assertEquals(List.of(), m.deleteSelected());
        assertFalse(m.library().contains("firework"));
        assertTrue(m.selected().isPresent(), "a neighbour is selected");
        m.select("firework-burst");
        assertEquals(List.of(), m.deleteSelected(), "free to delete once nothing spawns it");
    }

    @Test
    void saveAndOpenRoundTripTheDocument(@TempDir Path dir) throws Exception {
        ParticleEditorModel m = new ParticleEditorModel();
        m.select("fire");
        m.apply(m.selected().orElseThrow().toBuilder().rate(123).colorKeys(List.of(
                new ParticleEffect.ColorKey(0f, 0x112233FF), new ParticleEffect.ColorKey(1f, 0x445566FF))).build());
        Path file = dir.resolve("Particles/my.omfx");
        m.save(file);
        assertFalse(m.isDirty());
        assertEquals(file, m.file().orElseThrow());

        ParticleEditorModel back = new ParticleEditorModel();
        back.open(file);
        assertEquals(m.names(), back.names());
        for (String name : m.names()) {
            assertEquals(m.library().get(name), back.library().get(name), name);
        }
        assertEquals(123f, back.library().get("fire").rate());
    }

    @Test
    void anOmfxV2SystemIsRefusedWithAReasonAndTheDocumentKept(@TempDir Path dir) throws Exception {
        ParticleEditorModel m = new ParticleEditorModel();
        m.create("mine");
        Path v2 = dir.resolve("campfire.omfx");
        OmfxCodec.write(OmfxDocument.of(FxPresets.CAMPFIRE), v2);
        Exception e = assertThrows(java.io.IOException.class, () -> m.open(v2));
        assertTrue(e.getMessage().contains("v2 FX system"), e.getMessage());
        assertTrue(m.library().contains("mine"));
        assertTrue(m.isDirty());
    }

    @Test
    void aFailedOpenLeavesTheDocumentAlone(@TempDir Path dir) throws Exception {
        ParticleEditorModel m = new ParticleEditorModel();
        m.create("mine");
        Path bad = dir.resolve("bad.omfx");
        Files.writeString(bad, "{ \"effects\": [ { \"name\": \"a\", \"additive\": 7 } ] }");
        assertThrows(Exception.class, () -> m.open(bad));
        assertTrue(m.library().contains("mine"));
        assertTrue(m.isDirty());
    }

    @Test
    void swapsReplayOntoARunningSystemWithoutRestartingIt() {
        ParticleEditorModel m = new ParticleEditorModel();
        ParticleSystem s = new ParticleSystem(20_000, 3);
        s.setCollider((x, y, z) -> y < 0);
        s.createEmitter(m.library().get("rain"), 0, 6, 0);
        for (int i = 0; i < 60; i++) {
            s.update(1f / 60f);
        }
        int liveRain = s.liveCount(m.library().get("rain"));
        assertTrue(liveRain > 0);

        m.select("ripple");
        ParticleEffect bigRipple = m.selected().orElseThrow().toBuilder().size(0.2f, 2f).build();
        for (ParticleEditorModel.Swap swap : m.apply(bigRipple)) {
            s.replace(swap.old(), swap.updated());
        }
        ParticleEffect rain = m.library().get("rain");
        assertEquals(liveRain, s.liveCount(rain), "rain kept falling through the edit");
        for (int i = 0; i < 60; i++) {
            s.update(1f / 60f);
        }
        assertTrue(s.liveCount(bigRipple) > 0, "the running rain now spawns the edited ripple");
    }

    @Test
    void colourPackingRoundTrips() {
        for (int c : new int[]{0x00000000, 0xFFFFFFFF, 0x12345678, 0xFF8000C0}) {
            assertEquals(c, ParticleEditorWorkspace.pack(ParticleEditorWorkspace.rgba(c)));
        }
    }

    @Test
    void copiedJsonOfAnEffectLoadsBack() throws Exception {
        String json = ParticleLibrary.toJsonText(ParticleLibrary.Presets.SPARKS);
        ParticleLibrary lib = new ParticleLibrary();
        lib.load(new ByteArrayInputStream(("{ \"effects\": [" + json + "] }").getBytes(StandardCharsets.UTF_8)));
        assertEquals(ParticleLibrary.Presets.SPARKS, lib.get("sparks"));
    }
}
