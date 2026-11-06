package com.openmason.main.systems.mcp;

import com.openmason.main.systems.menus.textureCreator.TextureCreatorImGui;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.palette.PaletteLibrary;
import com.openmason.main.systems.menus.textureCreator.palette.PaletteModel;
import com.openmason.main.systems.services.EditorServices;
import com.openmason.main.systems.threading.MainThreadExecutor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Thread-safe facade over the Texture Creator's saved-palettes library
 * ({@link PaletteLibrary}) — the same instance the color panel and palette
 * strip render, so every change shows in the UI live and is persisted by the
 * library's own listeners (no save step here).
 *
 * <p>Colors cross the MCP boundary as hex strings: {@code #rrggbb}, with
 * {@code aa} appended only when not opaque — the format tex_describe's legend
 * uses. Input also accepts {@code r,g,b[,a]} ({@link PixelTextCodec#parseColor}).
 */
public final class PaletteEditingService {

    private static final long TIMEOUT_MS = 10_000;

    /** Runs a call on the thread that owns the library. */
    interface Runner {
        <T> T run(Callable<T> call);
    }

    private final Supplier<PaletteLibrary> library;
    private final Runner runner;

    public PaletteEditingService(EditorServices editorServices) {
        this(() -> {
            TextureCreatorImGui ui = editorServices.getTextureCreator();
            if (ui == null || ui.getPaletteLibrary() == null) {
                throw new IllegalStateException("Texture editor not initialized");
            }
            return ui.getPaletteLibrary();
        }, PaletteEditingService::onMain);
    }

    PaletteEditingService(Supplier<PaletteLibrary> library, Runner runner) {
        this.library = library;
        this.runner = runner;
    }

    // ===================== Operations =====================

    public PaletteList list() {
        return runner.run(() -> listOf(library.get()));
    }

    /** New palette (DB32 default when {@code colors} is null), made active like the UI's "New". */
    public PaletteList create(String name, List<String> colors) {
        List<Integer> seed = colors == null ? null : parseAll(colors);
        return runner.run(() -> {
            PaletteLibrary lib = library.get();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Palette name must not be blank");
            }
            if (!lib.createPalette(name, seed == null ? null : distinct(seed))) {
                throw new IllegalArgumentException("A palette named '" + name.trim()
                        + "' already exists (palettes: " + String.join(", ", lib.getNames()) + ")");
            }
            return listOf(lib);
        });
    }

    public PaletteList switchTo(String name) {
        return runner.run(() -> {
            PaletteLibrary lib = library.get();
            requireKnown(lib, name);
            lib.switchTo(name);
            return listOf(lib);
        });
    }

    /** Append colors to the active palette; exact duplicates (already present or repeated) are skipped. */
    public AddResult addSwatches(List<String> colors) {
        if (colors == null || colors.isEmpty()) {
            throw new IllegalArgumentException("colors must be a non-empty list");
        }
        List<Integer> parsed = parseAll(colors);
        return runner.run(() -> {
            PaletteLibrary lib = library.get();
            PaletteModel model = lib.getActiveModel();
            Set<Integer> present = new LinkedHashSet<>(model.getSwatches());
            List<Integer> next = new ArrayList<>(model.getSwatches());
            List<String> skipped = new ArrayList<>();
            for (int c : parsed) {
                if (present.add(c)) {
                    next.add(c);
                } else {
                    skipped.add(hex(c));
                }
            }
            int added = next.size() - model.size();
            if (added > 0) {
                model.setSwatches(next); // one change → one persist, not one per swatch
            }
            return new AddResult(added, skipped, listOf(lib));
        });
    }

    /** Remove from the active palette by index, or every swatch equal to {@code color}. */
    public RemoveResult removeSwatch(Integer index, String color) {
        if ((index == null) == (color == null)) {
            throw new IllegalArgumentException("Give exactly one of index or color");
        }
        Integer target = color == null ? null : PixelTextCodec.parseColor(color);
        return runner.run(() -> {
            PaletteLibrary lib = library.get();
            PaletteModel model = lib.getActiveModel();
            List<Integer> next = new ArrayList<>(model.getSwatches());
            int before = next.size();
            if (index != null) {
                if (index < 0 || index >= before) {
                    throw new IllegalArgumentException("Swatch index " + index + " out of range (palette '"
                            + lib.getActiveName() + "' has " + before + " swatches)");
                }
                next.remove((int) index);
            } else {
                next.removeIf(c -> c.intValue() == target);
                if (next.size() == before) {
                    throw new IllegalArgumentException("Color " + hex(target) + " is not in palette '"
                            + lib.getActiveName() + "'");
                }
            }
            model.setSwatches(next);
            return new RemoveResult(before - next.size(), listOf(lib));
        });
    }

    /** Delete a palette (default: the active one); the last palette can't be deleted. */
    public PaletteList delete(String name) {
        return runner.run(() -> {
            PaletteLibrary lib = library.get();
            String target = name == null ? lib.getActiveName() : name;
            requireKnown(lib, target);
            if (!lib.deletePalette(target)) {
                throw new IllegalStateException("Can't delete '" + target + "': it is the only palette");
            }
            return listOf(lib);
        });
    }

    // ===================== Helpers =====================

    private static void requireKnown(PaletteLibrary lib, String name) {
        if (name == null || !lib.contains(name)) {
            throw new IllegalArgumentException("No palette named '" + name + "' (palettes: "
                    + String.join(", ", lib.getNames()) + ")");
        }
    }

    private static PaletteList listOf(PaletteLibrary lib) {
        List<PaletteEntry> entries = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : lib.getAll().entrySet()) {
            entries.add(new PaletteEntry(e.getKey(), e.getValue().stream().map(PaletteEditingService::hex).toList()));
        }
        return new PaletteList(lib.getActiveName(), entries);
    }

    private static List<Integer> parseAll(List<String> colors) {
        List<Integer> out = new ArrayList<>(colors.size());
        for (String c : colors) {
            if (c == null) throw new IllegalArgumentException("colors must not contain null");
            out.add(PixelTextCodec.parseColor(c));
        }
        return out;
    }

    private static List<Integer> distinct(List<Integer> colors) {
        return new ArrayList<>(new LinkedHashSet<>(colors));
    }

    /** Packed AABBGGRR → {@code #rrggbb[aa]} (alpha only when not opaque). */
    static String hex(int packed) {
        int[] c = PixelCanvas.unpackRGBA(packed);
        return String.format(Locale.ROOT, "#%02x%02x%02x%s", c[0], c[1], c[2],
                c[3] == 255 ? "" : String.format(Locale.ROOT, "%02x", c[3]));
    }

    private static <T> T onMain(Callable<T> call) {
        try {
            return MainThreadExecutor.submit(call).get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new RuntimeException("Palette operation timed out on main thread", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }

    // ===================== DTOs =====================

    public record PaletteEntry(String name, List<String> swatches) {}

    public record PaletteList(String active, List<PaletteEntry> palettes) {}

    public record AddResult(int added, List<String> skippedDuplicates, PaletteList library) {}

    public record RemoveResult(int removed, PaletteList library) {}
}
