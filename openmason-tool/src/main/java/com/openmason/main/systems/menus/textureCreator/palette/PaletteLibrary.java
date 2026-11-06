package com.openmason.main.systems.menus.textureCreator.palette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Named collection of saved palettes with one active palette. The active
 * palette is exposed through a single, stable {@link PaletteModel} instance —
 * switching palettes swaps its contents in place, so every view bound to the
 * model (color panel section, bottom palette strip) follows automatically.
 */
public final class PaletteLibrary {

    public static final String DEFAULT_PALETTE_NAME = "Default";

    /** Insertion-ordered: combo lists palettes in creation order. */
    private final Map<String, List<Integer>> palettes = new LinkedHashMap<>();
    private final PaletteModel activeModel = new PaletteModel();

    private String activeName = DEFAULT_PALETTE_NAME;
    private Runnable changeListener;

    /**
     * The shared model holding the active palette's swatches. Mutations to it
     * (add/replace/remove swatch) belong to the active palette and are synced
     * back on {@link #snapshotActive()}.
     */
    public PaletteModel getActiveModel() {
        return activeModel;
    }

    public String getActiveName() {
        return activeName;
    }

    public List<String> getNames() {
        return new ArrayList<>(palettes.keySet());
    }

    public int size() {
        return palettes.size();
    }

    /** Replace the whole library (used by persistence on load). */
    public void setAll(Map<String, List<Integer>> loaded, String active) {
        palettes.clear();
        for (Map.Entry<String, List<Integer>> e : loaded.entrySet()) {
            palettes.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        if (palettes.isEmpty()) {
            palettes.put(DEFAULT_PALETTE_NAME, PaletteModel.createDefault().getSwatches());
        }
        activeName = palettes.containsKey(active) ? active : palettes.keySet().iterator().next();
        activeModel.setSwatches(palettes.get(activeName));
    }

    /** Switch the active palette, saving the current one's swatches first. */
    public void switchTo(String name) {
        if (!palettes.containsKey(name) || name.equals(activeName)) {
            return;
        }
        snapshotActive();
        activeName = name;
        activeModel.setSwatches(palettes.get(name));
        notifyChanged();
    }

    /**
     * Create a new palette (seeded with the DB32 default) and switch to it.
     *
     * @return false if the name is blank or already taken
     */
    public boolean createPalette(String name) {
        return createPalette(name, null);
    }

    /**
     * Create a new palette seeded with {@code swatches} (null: the DB32
     * default) and switch to it.
     *
     * @return false if the name is blank or already taken
     */
    public boolean createPalette(String name, List<Integer> swatches) {
        if (name == null || name.isBlank() || palettes.containsKey(name.trim())) {
            return false;
        }
        snapshotActive();
        String trimmed = name.trim();
        palettes.put(trimmed, swatches != null
                ? new ArrayList<>(swatches) : PaletteModel.createDefault().getSwatches());
        activeName = trimmed;
        activeModel.setSwatches(palettes.get(trimmed));
        notifyChanged();
        return true;
    }

    /**
     * Delete the active palette and switch to the first remaining one.
     * Refused when it is the only palette.
     */
    public boolean deleteActivePalette() {
        return deletePalette(activeName);
    }

    /**
     * Delete the named palette. Deleting the active one switches to the first
     * remaining palette; deleting another leaves the active one untouched.
     * Refused when the name is unknown or it is the only palette.
     */
    public boolean deletePalette(String name) {
        if (!palettes.containsKey(name) || palettes.size() <= 1) {
            return false;
        }
        palettes.remove(name);
        if (name.equals(activeName)) {
            activeName = palettes.keySet().iterator().next();
            activeModel.setSwatches(palettes.get(activeName));
        }
        notifyChanged();
        return true;
    }

    public boolean contains(String name) {
        return palettes.containsKey(name);
    }

    /** Sync the active model's swatches back into the named map. */
    public void snapshotActive() {
        palettes.put(activeName, new ArrayList<>(activeModel.getSwatches()));
    }

    /** Snapshot of all palettes for persistence. */
    public Map<String, List<Integer>> getAll() {
        snapshotActive();
        Map<String, List<Integer>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> e : palettes.entrySet()) {
            copy.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return copy;
    }

    /**
     * Fired on palette-level changes (switch/create/delete). Swatch-level
     * changes fire the active model's own listener instead.
     */
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    private void notifyChanged() {
        if (changeListener != null) {
            changeListener.run();
        }
    }
}
