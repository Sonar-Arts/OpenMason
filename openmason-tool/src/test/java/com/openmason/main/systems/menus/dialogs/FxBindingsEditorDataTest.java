package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.fx.FxData;
import com.openmason.engine.format.fx.FxDef;
import com.openmason.engine.format.fx.FxParamValue;
import com.openmason.main.systems.particles.FxCatalog;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Data contract of the SBO/SBE FX tab (#103): lossless load/save, save-blocking problems, and non-blocking
 * warnings for FX the project does not know. Headless (no ImGui).
 */
@Tag("regression")
class FxBindingsEditorDataTest {

    private static FxBindingsEditor editor(FxCatalog catalog) {
        return new FxBindingsEditor(() -> { }, () -> catalog);
    }

    private static FxData sample() {
        Map<String, FxParamValue> params = new LinkedHashMap<>();
        params.put("intensity", FxParamValue.scalar(0.25f));
        params.put("tint", FxParamValue.color(1f, 0.5f, 0f, 1f));
        return new FxData(List.of(
                new FxDef("break", "debris", 0f, 0.5f, 0f, Map.of(), true),
                new FxDef("ambient", "mygame:campfire", 0.1f, 0f, -0.1f, params, false)));
    }

    @Test
    void noBindingsMeansNoSection() {
        FxBindingsEditor editor = editor(FxCatalog.presets());
        assertTrue(editor.isEmpty());
        assertNull(editor.toFxData());
        assertNull(editor.validate());
        editor.load(null);
        assertNull(editor.toFxData());
    }

    @Test
    void loadThenSaveIsLossless() {
        FxBindingsEditor editor = editor(FxCatalog.presets());
        editor.load(sample());
        assertEquals(sample(), editor.toFxData());
        assertNull(editor.validate());
    }

    @Test
    void emptyEventOrFxBlocksTheSaveButNotTheSnapshot() {
        FxBindingsEditor editor = editor(FxCatalog.presets());
        editor.load(sample());
        editor.addRow("hit", "");
        assertNotNull(editor.validate());
        assertTrue(editor.validate().contains("no FX"), editor.validate());
        assertEquals(sample(), editor.toFxData(), "unfinished rows are left out of a snapshot");

        FxBindingsEditor noEvent = editor(FxCatalog.presets());
        noEvent.addRow("", "smoke");
        assertTrue(noEvent.validate().contains("no event"), noEvent.validate());
    }

    @Test
    void parameterNamesMustBeSetAndUnique() {
        FxBindingsEditor editor = editor(FxCatalog.presets());
        int row = editor.addRow("break", "smoke");
        editor.addParam(row, "size", 1, 2f);
        editor.addParam(row, "size", 1, 3f);
        assertTrue(editor.validate().contains("set twice"), editor.validate());

        FxBindingsEditor blank = editor(FxCatalog.presets());
        int r = blank.addRow("break", "smoke");
        blank.addParam(r, " ", 1, 2f);
        assertTrue(blank.validate().contains("no name"), blank.validate());
    }

    @Test
    void aValueOfTheWrongShapeForAKnownParameterBlocks() {
        FxCatalog catalog = FxCatalog.of(List.of(new FxCatalog.Entry("debris", FxCatalog.Source.PROJECT,
                "Particles/debris.omfx", List.of(new FxCatalog.Param("tint", 4,
                        FxParamValue.color(1f, 1f, 1f, 1f))))));
        FxBindingsEditor editor = editor(catalog);
        int row = editor.addRow("break", "mygame:debris");
        editor.addParam(row, "tint", 3, 1f);
        String error = editor.validate();
        assertNotNull(error);
        assertTrue(error.contains("colour") && error.contains("vector"), error);
    }

    @Test
    void unknownFxAndParametersOnlyWarn() {
        FxCatalog catalog = FxCatalog.presets();
        FxBindingsEditor editor = editor(catalog);
        editor.addRow("break", "mygame:not_authored_here");
        int row = editor.addRow("ambient", "campfire");
        editor.addParam(row, "no_such_param", 1, 1f);
        assertNull(editor.validate());
        List<String> warnings = editor.warnings();
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("not_authored_here"));
        assertTrue(warnings.get(1).contains("no_such_param"));
    }

    @Test
    void rescanAsksTheSourceAgain() {
        int[] scans = {0};
        FxBindingsEditor editor = new FxBindingsEditor(() -> { }, () -> {
            scans[0]++;
            return FxCatalog.presets();
        });
        editor.addRow("break", "smoke");
        editor.warnings();
        editor.warnings();
        assertEquals(1, scans[0], "the catalog is scanned once");
        editor.rescan();
        editor.warnings();
        assertEquals(2, scans[0]);
    }

    @Test
    void aFailingScanKnowsNothingInsteadOfThrowing() {
        FxBindingsEditor editor = new FxBindingsEditor(() -> { }, () -> {
            throw new IllegalStateException("disk on fire");
        });
        editor.addRow("break", "smoke");
        assertNull(editor.validate());
        assertEquals(1, editor.warnings().size());
    }
}
