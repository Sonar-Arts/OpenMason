package com.openmason.main.systems.mcp;

import com.openmason.main.systems.menus.textureCreator.palette.PaletteLibrary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #94: the MCP palette tools drive the same library the UI binds to. */
class PaletteEditingServiceTest {

    private static final int RED = 0xFF0000FF;   // AABBGGRR
    private static final int GREEN = 0xFF00FF00;

    private PaletteLibrary library;
    private PaletteEditingService service;
    private final AtomicInteger modelChanges = new AtomicInteger();

    @BeforeEach
    void setUp() {
        library = new PaletteLibrary();
        Map<String, List<Integer>> seed = new LinkedHashMap<>();
        seed.put("Default", List.of(RED, GREEN));
        seed.put("Stone", List.of(0xFF808080));
        library.setAll(seed, "Default");
        library.getActiveModel().setChangeListener(modelChanges::incrementAndGet);
        service = new PaletteEditingService(() -> library, new PaletteEditingService.Runner() {
            @Override
            public <T> T run(Callable<T> call) {
                try {
                    return call.call();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
    }

    @Test
    void listReportsEveryPaletteAsHex() {
        PaletteEditingService.PaletteList list = service.list();
        assertEquals("Default", list.active());
        assertEquals(List.of("#ff0000", "#00ff00"), list.palettes().get(0).swatches());
        assertEquals(List.of("#808080"), list.palettes().get(1).swatches());
    }

    @Test
    void hexKeepsAlphaOnlyWhenTranslucent() {
        assertEquals("#ff000080", PaletteEditingService.hex(0x800000FF));
    }

    @Test
    void createSeedsSwatchesDedupedAndSwitches() {
        PaletteEditingService.PaletteList list = service.create("Ore", List.of("#112233", "1,2,3", "#112233"));
        assertEquals("Ore", list.active());
        assertEquals(List.of("#112233", "#010203"), library.getActiveModel().getSwatches().stream()
                .map(PaletteEditingService::hex).toList());
    }

    @Test
    void createWithoutColorsUsesDb32() {
        service.create("Fresh", null);
        assertEquals(32, library.getActiveModel().size());
    }

    @Test
    void createRefusesTakenName() {
        assertThrows(IllegalArgumentException.class, () -> service.create("Stone", null));
    }

    @Test
    void switchChangesActiveAndRejectsUnknown() {
        assertEquals("Stone", service.switchTo("Stone").active());
        assertEquals(List.of(0xFF808080), library.getActiveModel().getSwatches());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.switchTo("Nope"));
        assertTrue(e.getMessage().contains("Default"), e.getMessage());
    }

    @Test
    void addSkipsDuplicatesInOneModelChange() {
        PaletteEditingService.AddResult r = service.addSwatches(List.of("#ff0000", "#0000ff", "#0000ff"));
        assertEquals(1, r.added());
        assertEquals(List.of("#ff0000", "#0000ff"), r.skippedDuplicates());
        assertEquals(3, library.getActiveModel().size());
        assertEquals(1, modelChanges.get(), "one persist, not one per swatch");
    }

    @Test
    void addTolerantOfDuplicatesAlreadyInPalette() {
        library.getActiveModel().setSwatches(List.of(RED, RED));
        PaletteEditingService.AddResult r = service.addSwatches(List.of("#00ff00"));
        assertEquals(1, r.added());
        assertEquals(List.of(RED, RED, GREEN), library.getActiveModel().getSwatches());
    }

    @Test
    void removeByIndexOrColor() {
        assertEquals(1, service.removeSwatch(0, null).removed());
        assertEquals(List.of(GREEN), library.getActiveModel().getSwatches());
        assertEquals(1, service.removeSwatch(null, "#00ff00").removed());
        assertTrue(library.getActiveModel().getSwatches().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.removeSwatch(null, "#00ff00"));
        assertThrows(IllegalArgumentException.class, () -> service.removeSwatch(0, null));
        assertThrows(IllegalArgumentException.class, () -> service.removeSwatch(0, "#00ff00"));
    }

    @Test
    void removeAndAddPersistToTheActivePaletteOnly() {
        service.addSwatches(List.of("#0000ff"));
        service.switchTo("Stone");
        service.switchTo("Default");
        assertEquals(3, library.getActiveModel().size());
        assertEquals(1, library.getAll().get("Stone").size());
    }

    @Test
    void deleteNamedKeepsActiveAndDeleteActiveFallsBack() {
        PaletteEditingService.PaletteList afterNamed = service.delete("Stone");
        assertEquals("Default", afterNamed.active());
        assertEquals(1, afterNamed.palettes().size());
        assertThrows(IllegalStateException.class, () -> service.delete(null), "last palette is kept");

        service.create("Ore", List.of());
        assertEquals("Default", service.delete(null).active());
    }
}
