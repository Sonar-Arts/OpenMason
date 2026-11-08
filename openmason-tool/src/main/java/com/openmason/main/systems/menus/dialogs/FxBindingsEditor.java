package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.fx.FxData;
import com.openmason.engine.format.fx.FxDef;
import com.openmason.engine.format.fx.FxParamValue;
import com.openmason.main.systems.particles.FxCatalog;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The FX tab shared by the SBO editor (1.12+) and the SBE editor (1.6+), #103: which FX play on which of the
 * asset's events. Each row is one {@link FxDef}: event, FX id, offset, parameter overrides and the
 * tint-from-texture flag.
 *
 * <p>Rows are checked against the {@link FxCatalog} of the open project. Structural problems block a save (an
 * empty event or FX, a nameless or repeated parameter, a value of the wrong shape for a parameter the FX
 * declares); an FX or parameter the project does not know only warns, since the game may ship FX this project
 * does not author. The data methods are headless (no ImGui calls).
 */
public final class FxBindingsEditor {

    private static final String[] KIND_LABELS = { "scalar", "vector", "colour" };
    private static final int[] KIND_COMPONENTS = { 1, 3, 4 };
    private static final List<String> ENTITY_EVENTS = List.of("hurt", "death");

    /** One parameter override being edited. */
    static final class ParamRow {
        final ImString name = new ImString(128);
        int components = 1;
        final float[] values = new float[4];

        FxParamValue value() {
            List<Float> c = new ArrayList<>(components);
            for (int i = 0; i < components; i++) c.add(values[i]);
            return new FxParamValue(c);
        }
    }

    /** One binding being edited. */
    static final class Row {
        final ImString event = new ImString(128);
        final ImString fx = new ImString(256);
        final float[] offset = new float[3];
        final ImBoolean tint = new ImBoolean(false);
        final List<ParamRow> params = new ArrayList<>();
    }

    private final Runnable onDirty;
    private final Supplier<FxCatalog> catalogSource;
    private final List<Row> rows = new ArrayList<>();
    private FxCatalog catalog;

    /**
     * @param onDirty       called on every edit
     * @param catalogSource builds the catalog of known FX (scanned lazily, again on "Rescan"); null = none known
     */
    public FxBindingsEditor(Runnable onDirty, Supplier<FxCatalog> catalogSource) {
        this.onDirty = onDirty != null ? onDirty : () -> { };
        this.catalogSource = catalogSource;
    }

    // ---- data ----------------------------------------------------------------------------------------------

    /** Populate from a manifest's {@code fx[]} (null = none). */
    public void load(FxData data) {
        rows.clear();
        if (data == null) return;
        for (FxDef def : data.fx()) {
            Row row = new Row();
            row.event.set(def.event());
            row.fx.set(def.fx());
            row.offset[0] = def.offsetX();
            row.offset[1] = def.offsetY();
            row.offset[2] = def.offsetZ();
            row.tint.set(def.tintFromTexture());
            for (Map.Entry<String, FxParamValue> e : def.params().entrySet()) {
                ParamRow p = new ParamRow();
                p.name.set(e.getKey());
                p.components = e.getValue().size();
                for (int i = 0; i < p.components; i++) p.values[i] = e.getValue().get(i);
                row.params.add(p);
            }
            rows.add(row);
        }
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    int size() {
        return rows.size();
    }

    /** Add a binding (the quick-add buttons, agents, tests); returns its index. */
    int addRow(String event, String fx) {
        Row row = new Row();
        row.event.set(event != null ? event : "");
        row.fx.set(fx != null ? fx : "");
        rows.add(row);
        onDirty.run();
        return rows.size() - 1;
    }

    /** Add a parameter override to binding {@code index}: {@code components} values, all {@code fill}. */
    void addParam(int index, String name, int components, float fill) {
        ParamRow p = new ParamRow();
        p.name.set(name);
        p.components = components;
        java.util.Arrays.fill(p.values, fill);
        rows.get(index).params.add(p);
        onDirty.run();
    }

    /**
     * The edited section, or null when there are no bindings (the manifest key then stays out). Rows that
     * {@link #validate()} would refuse are left out, so a snapshot of an unfinished form never throws.
     */
    public FxData toFxData() {
        List<FxDef> defs = new ArrayList<>(rows.size());
        for (Row row : rows) {
            if (structuralError(row, 0) != null) continue;
            Map<String, FxParamValue> params = new LinkedHashMap<>();
            for (ParamRow p : row.params) params.put(p.name.get().trim(), p.value());
            defs.add(new FxDef(row.event.get().trim(), row.fx.get().trim(),
                    row.offset[0], row.offset[1], row.offset[2], params, row.tint.get()));
        }
        return defs.isEmpty() ? null : new FxData(defs);
    }

    /** Null when the section can be saved, else the first save-blocking problem. */
    public String validate() {
        for (int i = 0; i < rows.size(); i++) {
            String error = rowError(rows.get(i), i);
            if (error != null) return error;
        }
        return null;
    }

    /** Non-blocking notes: FX or parameters the project does not know, unreadable FX files. */
    public List<String> warnings() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) out.addAll(rowWarnings(rows.get(i), i));
        return out;
    }

    private static String label(Row row, int index) {
        String event = row.event.get().trim();
        return "FX binding " + (index + 1) + (event.isEmpty() ? "" : " (" + event + ")");
    }

    /** Problems that make the row unwritable, whatever the project knows. */
    private static String structuralError(Row row, int index) {
        if (row.event.get().trim().isEmpty()) return label(row, index) + " has no event name.";
        if (row.fx.get().trim().isEmpty()) return label(row, index) + " has no FX chosen.";
        Set<String> seen = new HashSet<>();
        for (int k = 0; k < row.params.size(); k++) {
            String name = row.params.get(k).name.get().trim();
            if (name.isEmpty()) return label(row, index) + ": parameter " + (k + 1) + " has no name.";
            if (!seen.add(name)) return label(row, index) + ": parameter '" + name + "' is set twice.";
        }
        return null;
    }

    private String rowError(Row row, int index) {
        String error = structuralError(row, index);
        if (error != null) return error;
        FxCatalog.Entry entry = catalog().find(row.fx.get());
        if (entry == null) return null;
        for (ParamRow p : row.params) {
            FxCatalog.Param declared = entry.param(p.name.get().trim());
            if (declared != null && declared.components() != p.components) {
                return label(row, index) + ": '" + declared.name() + "' of " + entry.id() + " is a "
                        + declared.kind() + ", the binding gives a " + FxCatalog.kindName(p.components) + ".";
            }
        }
        return null;
    }

    private List<String> rowWarnings(Row row, int index) {
        String fx = row.fx.get().trim();
        if (fx.isEmpty()) return List.of();
        FxCatalog.Entry entry = catalog().find(fx);
        if (entry == null) {
            return List.of(label(row, index) + ": '" + fx + "' is not an FX of this project or an engine preset;"
                    + " the game has to ship it.");
        }
        List<String> out = new ArrayList<>();
        for (ParamRow p : row.params) {
            String name = p.name.get().trim();
            if (!name.isEmpty() && entry.param(name) == null) {
                out.add(label(row, index) + ": " + entry.id() + " has no parameter '" + name + "'.");
            }
        }
        return out;
    }

    /** The catalog, scanned on first use. */
    FxCatalog catalog() {
        if (catalog == null) {
            FxCatalog scanned = null;
            try {
                scanned = catalogSource != null ? catalogSource.get() : null;
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(FxBindingsEditor.class).warn("FX catalog scan failed", e);
            }
            catalog = scanned != null ? scanned : FxCatalog.empty();
        }
        return catalog;
    }

    /** Drop the catalog so the next check rescans the project. */
    public void rescan() {
        catalog = null;
    }

    // ---- rendering -----------------------------------------------------------------------------------------

    public void render() {
        FxCatalog known = catalog();
        if (rows.isEmpty()) {
            ImGui.textDisabled("No FX bound - nothing plays on this asset's events.");
        } else {
            ImGui.text("FX bindings (" + rows.size() + ")");
        }
        ImGui.textDisabled("Events: break, hit, place, step, ambient (runs while the asset exists), or any"
                + " custom name the game raises.");
        ImGui.textDisabled("Known FX: " + known.size() + " (project .omfx files + engine presets)");
        ImGui.sameLine();
        if (ImGui.smallButton("Rescan")) {
            rescan();
            known = catalog();
        }
        for (String problem : known.problems()) {
            ThemedWidgets.statusTextWrapped(ThemeColors.Tone.WARNING, "Unreadable FX file " + problem);
        }

        ImGui.dummy(0, 4);
        ImGui.textDisabled("Add:");
        for (String event : FxData.STANDARD_EVENTS) {
            ImGui.sameLine();
            if (ImGui.smallButton(event)) addRow(event, "");
        }
        for (String event : ENTITY_EVENTS) {
            ImGui.sameLine();
            if (ImGui.smallButton(event)) addRow(event, "");
        }
        ImGui.sameLine();
        if (ImGui.smallButton("+ custom")) addRow("", "");

        ImGui.dummy(0, 6);
        ImGui.separator();
        ImGui.dummy(0, 4);

        int remove = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            ImGui.pushID("fx_row_" + i);
            if (renderRow(row, known)) remove = i;
            String error = rowError(row, i);
            if (error != null) {
                ThemedWidgets.inlineError(error);
            }
            for (String warning : rowWarnings(row, i)) {
                ThemedWidgets.statusTextWrapped(ThemeColors.Tone.WARNING, warning);
            }
            ImGui.dummy(0, 8);
            ImGui.popID();
        }
        if (remove >= 0) {
            rows.remove(remove);
            onDirty.run();
        }
    }

    /** One binding's widgets; true when Remove was clicked. */
    private boolean renderRow(Row row, FxCatalog known) {
        String event = row.event.get().trim();
        String fx = row.fx.get().trim();
        ImGui.text((event.isEmpty() ? "(no event)" : event) + "  ->  " + (fx.isEmpty() ? "(no FX)" : fx));
        ImGui.sameLine();
        boolean remove = ThemedWidgets.dangerSoftButton("Remove", EditorWidgets.REMOVE_BUTTON_WIDTH, 0f);

        ImGui.pushItemWidth(EditorWidgets.NAME_FIELD_WIDTH);
        if (ImGui.inputTextWithHint("Event", "event name", row.event)) onDirty.run();
        ImGui.popItemWidth();
        ImGui.sameLine();
        ImGui.pushItemWidth(220f);
        if (ImGui.inputTextWithHint("FX", "fx id", row.fx)) onDirty.run();
        ImGui.popItemWidth();
        ImGui.sameLine();
        renderFxPicker(row, known);

        ImGui.pushItemWidth(260f);
        if (ImGui.dragFloat3("Offset", row.offset, 0.01f)) onDirty.run();
        ImGui.popItemWidth();
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Where the FX plays, in blocks, relative to the event position the game reports"
                    + " (a block's centre, an entity's feet), in the asset's local space.");
        }
        ImGui.sameLine();
        if (ImGui.checkbox("Tint from texture", row.tint)) onDirty.run();
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("The game tints the FX with a colour sampled from this asset's texture"
                    + " (debris coloured like the block).");
        }

        renderParams(row, known.find(fx));
        return remove;
    }

    private void renderFxPicker(Row row, FxCatalog known) {
        if (ImGui.beginCombo("##pick_fx", "Pick...", imgui.flag.ImGuiComboFlags.NoPreview)) {
            for (FxCatalog.Entry entry : known.entries()) {
                String label = entry.id() + "  (" + entry.origin() + ")";
                if (ImGui.selectable(label, entry.id().equals(row.fx.get().trim()))) {
                    row.fx.set(entry.id());
                    onDirty.run();
                }
            }
            ImGui.endCombo();
        }
    }

    private void renderParams(Row row, FxCatalog.Entry entry) {
        int remove = -1;
        for (int k = 0; k < row.params.size(); k++) {
            ParamRow p = row.params.get(k);
            ImGui.pushID("param_" + k);
            ImGui.textDisabled("  ");
            ImGui.sameLine();
            ImGui.pushItemWidth(120f);
            if (ImGui.inputTextWithHint("##name", "parameter", p.name)) onDirty.run();
            ImGui.sameLine();
            int kind = kindIndex(p.components);
            if (ImGui.beginCombo("##kind", KIND_LABELS[kind])) {
                for (int i = 0; i < KIND_LABELS.length; i++) {
                    if (ImGui.selectable(KIND_LABELS[i], i == kind) && i != kind) {
                        p.components = KIND_COMPONENTS[i];
                        onDirty.run();
                    }
                }
                ImGui.endCombo();
            }
            ImGui.popItemWidth();
            ImGui.sameLine();
            ImGui.pushItemWidth(240f);
            boolean changed = switch (p.components) {
                case 1 -> ImGui.dragFloat("##v", p.values, 0.01f);
                case 3 -> ImGui.dragFloat3("##v", p.values, 0.01f);
                default -> ImGui.colorEdit4("##v", p.values);
            };
            if (changed) onDirty.run();
            ImGui.popItemWidth();
            ImGui.sameLine();
            if (ImGui.smallButton("x")) remove = k;
            ImGui.popID();
        }
        if (remove >= 0) {
            row.params.remove(remove);
            onDirty.run();
        }

        ImGui.textDisabled("  ");
        ImGui.sameLine();
        if (ImGui.beginCombo("##add_param", "Override a parameter...", imgui.flag.ImGuiComboFlags.NoPreview)) {
            Set<String> set = new HashSet<>();
            for (ParamRow p : row.params) set.add(p.name.get().trim());
            if (entry != null) {
                for (FxCatalog.Param declared : entry.params()) {
                    if (set.contains(declared.name())) continue;
                    if (ImGui.selectable(declared.name() + "  (" + declared.kind() + ")")) {
                        ParamRow p = new ParamRow();
                        p.name.set(declared.name());
                        p.components = declared.components();
                        for (int i = 0; i < p.components; i++) p.values[i] = declared.defaultValue().get(i);
                        row.params.add(p);
                        onDirty.run();
                    }
                }
                if (entry.params().isEmpty()) ImGui.textDisabled(entry.id() + " declares no parameters");
            }
            if (ImGui.selectable("Custom...")) {
                row.params.add(new ParamRow());
                onDirty.run();
            }
            ImGui.endCombo();
        }
    }

    private static int kindIndex(int components) {
        for (int i = 0; i < KIND_COMPONENTS.length; i++) {
            if (KIND_COMPONENTS[i] == components) return i;
        }
        return 0;
    }

    /** Nothing GPU-backed to release (plain ImGui); kept so the editors close every section alike. */
    public void close() {
    }
}
