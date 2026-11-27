package com.openmason.main.systems.animation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.anim.AnimationAuthoring;
import com.openmason.engine.rig.RigJoint;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The Animation tab's panels (OpenMason #165): thin ImGui over the native authoring session. Every change is an
 * operation batch ({@link AnimationSession#apply}: one undo step; drags share a merge key so a drag is one step);
 * everything shown comes from the session's view / pose / handles. ASCII strings only.
 */
final class AnimationPanels {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] EASINGS = {"LINEAR", "EASE_IN", "EASE_OUT", "EASE_IN_OUT", "STEP"};
    private static final float NAME_COLUMN = 170f;
    private static final float ROW_HEIGHT = 18f;

    private final AnimationSessions sessions;
    private String error;
    private String note;

    // Selection.
    private int layer;            // authoring layer index
    private int joint = -1;       // rig joint index
    private String effector;      // effector name
    private String keyRow;        // dope sheet row id of the selected keys ("t:<joint>", "e:<name>", "n", "m")
    private final TreeSet<Float> keyTimes = new TreeSet<>();

    // Dope sheet drag.
    private boolean draggingKeys;
    private float dragStartTime;
    private float dragApplied;
    private boolean scrubbing;

    private final ImString nameField = new ImString(128);
    private final ImString newLayer = new ImString(64);
    private final ImString newName = new ImString(64);
    private final ImString payload = new ImString(256);
    private final ImInt easing = new ImInt();

    AnimationPanels(AnimationSessions sessions) {
        this.sessions = sessions;
    }

    void reset() {
        layer = 0;
        joint = -1;
        effector = null;
        keyRow = null;
        keyTimes.clear();
        draggingKeys = scrubbing = false;
        error = null;
        note = null;
    }

    void setError(String error) {
        this.error = error;
    }

    void setNote(String note) {
        this.note = note;
    }

    int selectedJoint() {
        return joint;
    }

    String selectedEffector() {
        return effector;
    }

    int selectedLayer() {
        return layer;
    }

    void selectJoint(int j) {
        joint = j;
        if (j >= 0) {
            keyRow = null;
            keyTimes.clear();
        }
    }

    void selectEffector(String name) {
        effector = name;
    }

    static void hint(String text) {
        if (ImGui.isItemHovered()) ImGui.setTooltip(text);
    }

    // ------------------------------------------------------------------------------------------------
    // Operations

    static ObjectNode op(String kind) {
        ObjectNode o = JSON.createObjectNode();
        o.put("op", kind);
        return o;
    }

    static ArrayNode vec(float... v) {
        ArrayNode a = JSON.createArrayNode();
        for (float f : v) a.add(f);
        return a;
    }

    /** Applies operations as one undo step; a refusal becomes the error line. */
    boolean apply(AnimationSession s, String label, String mergeKey, ObjectNode... ops) {
        ArrayNode arr = JSON.createArrayNode();
        for (ObjectNode o : ops) arr.add(o);
        try {
            s.apply(arr.toString(), label, mergeKey);
            return true;
        } catch (RuntimeException e) {
            error = e.getMessage();
            return false;
        }
    }

    void undo() {
        sessions.current().ifPresent(s -> {
            if (!s.undo()) error = "nothing to undo";
        });
    }

    void redo() {
        sessions.current().ifPresent(s -> {
            if (!s.redo()) error = "nothing to redo";
        });
    }

    void renderMessages() {
        if (error != null) {
            ImGui.textColored(0.95f, 0.45f, 0.4f, 1f, error);
            ImGui.sameLine();
            if (ImGui.smallButton("ok##animErr")) error = null;
        }
        if (note != null) {
            ImGui.textColored(0.95f, 0.8f, 0.4f, 1f, note);
            ImGui.sameLine();
            if (ImGui.smallButton("ok##animNote")) note = null;
        }
    }

    private String layerName(AnimationSession s) {
        JsonNode layers = s.view().path("layers");
        if (layer >= layers.size()) layer = 0;
        return layers.path(layer).path("name").asText("Base");
    }

    private String jointRef(AnimationSession s, int j) {
        return s.model().rig().joints().get(j).id();
    }

    private float frame(AnimationSession s) {
        return 1f / Math.max(1f, s.fps());
    }

    private float snap(AnimationSession s, float t) {
        float f = s.fps();
        return Math.max(0f, Math.min(s.duration(), Math.round(t * f) / f));
    }

    // ------------------------------------------------------------------------------------------------
    // Keyboard (only while the tab has focus and no text field is active)

    void handleKeys(AnimationSession s) {
        var io = ImGui.getIO();
        if (io.getWantTextInput() || io.getKeyCtrl()) return;
        if (ImGui.isKeyPressed(ImGuiKey.Space, false)) s.setPlaying(!s.playing());
        if (ImGui.isKeyPressed(ImGuiKey.LeftArrow, true)) step(s, -1);
        if (ImGui.isKeyPressed(ImGuiKey.RightArrow, true)) step(s, 1);
        if (ImGui.isKeyPressed(ImGuiKey.K, false)) keySelected(s);
        if (ImGui.isKeyPressed(ImGuiKey.Delete, false)) deleteSelectedKeys(s);
    }

    private void step(AnimationSession s, int frames) {
        s.setPlaying(false);
        s.setTime(snap(s, s.time() + frames * frame(s)));
    }

    /** Keys the selected joint on the selected layer at the playhead (its current layer value). */
    void keySelected(AnimationSession s) {
        if (joint < 0) {
            error = "select a joint to key";
            return;
        }
        float[] v = s.authoring().layerValue(layer, joint, s.time());
        ObjectNode k = op("key");
        k.put("layer", layerName(s)).put("joint", jointRef(s, joint)).put("time", s.time());
        k.set("pos", vec(v[0], v[1], v[2]));
        k.set("rot", vec(v[3], v[4], v[5]));
        k.set("scale", vec(v[6], v[7], v[8]));
        apply(s, "key " + s.model().rig().joints().get(joint).name(), null, k);
    }

    private void deleteSelectedKeys(AnimationSession s) {
        if (keyRow == null || keyTimes.isEmpty()) return;
        ArrayNode times = JSON.createArrayNode();
        keyTimes.forEach(times::add);
        if (keyRow.startsWith("t:")) {
            ObjectNode o = op("remove_keys");
            o.put("layer", layerName(s)).put("joint", keyRow.substring(2));
            o.set("times", times);
            apply(s, "delete keys", null, o);
        } else if (keyRow.startsWith("e:")) {
            List<ObjectNode> ops = new ArrayList<>();
            for (float t : keyTimes) {
                ObjectNode o = op("effector_key");
                o.put("effector", keyRow.substring(2)).put("time", t).put("remove", true);
                ops.add(o);
            }
            apply(s, "delete effector keys", null, ops.toArray(ObjectNode[]::new));
        } else if (keyRow.startsWith("k:")) {
            List<ObjectNode> ops = new ArrayList<>();
            for (float t : keyTimes) {
                ops.add(op("morph_key").put("layer", layerName(s)).put("morph", keyRow.substring(2)).put("time", t)
                        .put("remove", true));
            }
            apply(s, "delete shape key keys", null, ops.toArray(ObjectNode[]::new));
        } else if (keyRow.equals("w")) {
            List<ObjectNode> ops = new ArrayList<>();
            for (float t : keyTimes) {
                ObjectNode o = op("layer_weight");
                o.put("layer", layerName(s)).put("time", t).put("remove", true);
                ops.add(o);
            }
            apply(s, "delete weight keys", null, ops.toArray(ObjectNode[]::new));
        }
        keyTimes.clear();
    }

    // ------------------------------------------------------------------------------------------------
    // Timeline: transport + dope sheet

    void renderTimeline(AnimationSession s) {
        JsonNode v = s.view();
        if (ImGui.button("|<")) {
            s.setPlaying(false);
            s.setTime(0f);
        }
        ImGui.sameLine();
        if (ImGui.button(s.playing() ? "Pause" : "Play")) s.setPlaying(!s.playing());
        hint("Space");
        ImGui.sameLine();
        if (ImGui.button("<")) step(s, -1);
        ImGui.sameLine();
        if (ImGui.button(">")) step(s, 1);
        ImGui.sameLine();
        if (ImGui.button(">|")) {
            s.setPlaying(false);
            s.setTime(s.duration());
        }
        ImGui.sameLine();
        ImGui.setNextItemWidth(110f);
        float[] t = {s.time()};
        if (ImGui.dragFloat("##animTime", t, 0.005f, 0f, s.duration(), "t %.3f s")) {
            s.setPlaying(false);
            s.setTime(t[0]);
        }
        ImGui.sameLine();
        ImGui.textDisabled(String.format(Locale.ROOT, "frame %d / %d", Math.round(s.time() * s.fps()),
                Math.round(s.duration() * s.fps())));
        ImGui.sameLine();
        if (ImGui.button("Key (K)")) keySelected(s);
        hint("Key the selected joint on the selected layer at the playhead");
        ImGui.sameLine();
        boolean fkOnly = s.stage() == AnimationAuthoring.STAGE_LAYERS;
        if (ImGui.checkbox("FK only", fkOnly)) {
            s.setStage(fkOnly ? AnimationAuthoring.STAGE_FINAL : AnimationAuthoring.STAGE_LAYERS);
        }
        hint("Show the layers without the control rig (the baked result includes it)");
        if (v.has("rootMotion")) {
            ImGui.sameLine();
            if (ImGui.checkbox("In place", s.inPlace())) s.setInPlace(!s.inPlace());
            hint("Root motion preview: in place (travel removed) or extracted (the character moves)");
        }
        ImGui.sameLine();
        ImGui.textDisabled("| layer " + layerName(s));

        renderDopeSheet(s, v);
    }

    private record Row(String id, String label, List<Float> times, boolean header) {
    }

    private List<Row> rows(AnimationSession s, JsonNode v) {
        List<Row> rows = new ArrayList<>();
        List<Float> nt = new ArrayList<>();
        v.path("notifies").forEach(n -> nt.add((float) n.path("time").asDouble()));
        rows.add(new Row("n", "Notifies", nt, true));
        List<Float> mt = new ArrayList<>();
        v.path("markers").forEach(n -> mt.add((float) n.path("time").asDouble()));
        rows.add(new Row("m", "Sync markers", mt, true));
        for (JsonNode e : v.path("effectors")) {
            List<Float> et = new ArrayList<>();
            e.path("keys").forEach(k -> et.add((float) k.path("time").asDouble()));
            rows.add(new Row("e:" + e.path("name").asText(), "IK " + e.path("name").asText(), et, true));
        }
        JsonNode l = v.path("layers").path(layer);
        List<Float> wt = new ArrayList<>();
        l.path("weightKeys").forEach(k -> wt.add((float) k.path(0).asDouble()));
        if (layer > 0) rows.add(new Row("w", "Layer weight", wt, true));
        for (JsonNode tr : l.path("tracks")) {
            List<Float> tt = new ArrayList<>();
            tr.path("keys").forEach(k -> tt.add((float) k.asDouble()));
            String label = tr.has("role") ? "role:" + tr.path("role").asText() : tr.path("name").asText(tr.path("joint").asText());
            String ref = tr.has("role") ? "role:" + tr.path("role").asText() : tr.path("joint").asText();
            rows.add(new Row("t:" + ref, label, tt, false));
        }
        // Shape-key weight tracks of the layer (#171, OMANIM 1.3 morph tracks): one row per key name.
        for (JsonNode mo : l.path("morphs")) {
            List<Float> mt2 = new ArrayList<>();
            mo.path("keys").forEach(k -> mt2.add((float) k.path(0).asDouble()));
            String name = mo.path("name").asText();
            rows.add(new Row("k:" + name, "shape " + name, mt2, false));
        }
        return rows;
    }

    private void renderDopeSheet(AnimationSession s, JsonNode v) {
        List<Row> rows = rows(s, v);
        float width = ImGui.getContentRegionAvailX();
        float height = Math.max(ROW_HEIGHT * 3, ImGui.getContentRegionAvailY());
        if (!ImGui.beginChild("##dopeSheet", width, height, true)) {
            ImGui.endChild();
            return;
        }
        ImDrawList dl = ImGui.getWindowDrawList();
        float x0 = ImGui.getCursorScreenPosX(), y0 = ImGui.getCursorScreenPosY();
        float trackX = x0 + NAME_COLUMN;
        float trackW = Math.max(40f, ImGui.getContentRegionAvailX() - NAME_COLUMN - 8f);
        float dur = Math.max(1e-3f, s.duration());
        float fps = s.fps();
        // Ruler.
        float rulerH = 18f;
        dl.addRectFilled(trackX, y0, trackX + trackW, y0 + rulerH, ImGui.getColorU32(ImGuiCol.FrameBg));
        int frames = Math.round(dur * fps);
        int stepF = Math.max(1, (int) Math.ceil(frames / (trackW / 40f)));
        for (int f = 0; f <= frames; f += stepF) {
            float x = trackX + f / (float) frames * trackW;
            dl.addLine(x, y0 + rulerH - 6, x, y0 + rulerH, ImGui.getColorU32(ImGuiCol.TextDisabled));
            dl.addText(x + 2, y0 + 1, ImGui.getColorU32(ImGuiCol.TextDisabled), Integer.toString(f));
        }
        ImGui.setCursorScreenPos(trackX, y0);
        ImGui.invisibleButton("##ruler", trackW, rulerH);
        if (ImGui.isItemActive()) {
            s.setPlaying(false);
            s.setTime(snap(s, (ImGui.getIO().getMousePosX() - trackX) / trackW * dur));
        }
        // Rows.
        float y = y0 + rulerH + 2;
        String hoverRow = null;
        float mouseX = ImGui.getIO().getMousePosX(), mouseY = ImGui.getIO().getMousePosY();
        for (Row r : rows) {
            boolean jointRow = r.id.startsWith("t:");
            boolean selectedRow = r.id.equals(keyRow) || (jointRow && joint >= 0 && r.id.equals("t:" + jointRef(s, joint)));
            if (selectedRow) dl.addRectFilled(x0, y, trackX + trackW, y + ROW_HEIGHT, ImGui.getColorU32(ImGuiCol.Header));
            dl.addText(x0 + (r.header ? 2 : 10), y + 2, ImGui.getColorU32(r.header ? ImGuiCol.TextDisabled : ImGuiCol.Text), r.label);
            dl.addLine(trackX, y + ROW_HEIGHT, trackX + trackW, y + ROW_HEIGHT, ImGui.getColorU32(ImGuiCol.Separator));
            for (float kt : r.times) {
                float kx = trackX + kt / dur * trackW, ky = y + ROW_HEIGHT / 2;
                boolean sel = r.id.equals(keyRow) && keyTimes.contains(kt);
                int col = sel ? 0xFF40C8FF : (r.header ? 0xFFB0B0B0 : 0xFFE0E0E0);
                dl.addQuadFilled(kx, ky - 5, kx + 5, ky, kx, ky + 5, kx - 5, ky, col);
            }
            if (mouseY >= y && mouseY < y + ROW_HEIGHT && mouseX >= x0 && mouseX < trackX + trackW) hoverRow = r.id;
            ImGui.setCursorScreenPos(x0, y);
            ImGui.invisibleButton("##row" + r.id, NAME_COLUMN + trackW, ROW_HEIGHT);
            if (ImGui.isItemActivated()) {
                float hitT = (mouseX - trackX) / trackW * dur;
                Float hit = null;
                for (float kt : r.times) {
                    if (Math.abs((kt - hitT) / dur * trackW) <= 6f) hit = kt;
                }
                if (mouseX < trackX) {
                    if (jointRow) selectJointByRef(s, r.id.substring(2));
                    if (r.id.startsWith("e:")) effector = r.id.substring(2);
                    keyRow = r.id;
                    keyTimes.clear();
                } else if (hit != null) {
                    if (!r.id.equals(keyRow) || !ImGui.getIO().getKeyShift()) keyTimes.clear();
                    keyRow = r.id;
                    keyTimes.add(hit);
                    if (jointRow) selectJointByRef(s, r.id.substring(2));
                    if (r.id.startsWith("e:")) effector = r.id.substring(2);
                    draggingKeys = !r.id.equals("n") && !r.id.equals("m");
                    dragStartTime = hitT;
                    dragApplied = 0f;
                } else {
                    keyRow = r.id;
                    keyTimes.clear();
                    scrubbing = true;
                }
            }
            y += ROW_HEIGHT;
        }
        // Playhead.
        float px = trackX + s.time() / dur * trackW;
        dl.addLine(px, y0, px, y, 0xFF4060FF, 2f);
        // Drag: move keys, or scrub.
        if (draggingKeys) {
            if (ImGui.isMouseDown(0)) {
                float delta = Math.round(((mouseX - trackX) / trackW * dur - dragStartTime) * fps) / fps;
                if (delta != dragApplied) {
                    moveKeys(s, delta - dragApplied);
                    dragApplied = delta;
                }
            } else {
                draggingKeys = false;
                sessions.current().ifPresent(x -> x.authoring().seal());
            }
        } else if (scrubbing) {
            if (ImGui.isMouseDown(0)) {
                s.setPlaying(false);
                s.setTime(snap(s, (mouseX - trackX) / trackW * dur));
            } else {
                scrubbing = false;
            }
        }
        if (hoverRow != null && (hoverRow.startsWith("t:") || hoverRow.startsWith("k:")) && ImGui.isMouseClicked(1)) {
            ImGui.openPopup("##keyMenu");
        }
        if (ImGui.beginPopup("##keyMenu")) {
            if (ImGui.menuItem("Delete selected keys")) deleteSelectedKeys(s);
            if (keyRow != null && keyRow.startsWith("t:")) {
                for (String e : EASINGS) {
                    if (ImGui.menuItem("Easing " + e) && !keyTimes.isEmpty()) {
                        ObjectNode o = op("easing");
                        o.put("layer", layerName(s)).put("joint", keyRow.substring(2)).put("easing", e);
                        ArrayNode times = JSON.createArrayNode();
                        keyTimes.forEach(times::add);
                        o.set("times", times);
                        apply(s, "easing " + e, null, o);
                    }
                }
                if (ImGui.menuItem("Remove track")) {
                    ObjectNode o = op("track");
                    o.put("layer", layerName(s)).put("joint", keyRow.substring(2)).put("remove", true);
                    apply(s, "remove track", null, o);
                    keyRow = null;
                    keyTimes.clear();
                }
            }
            if (keyRow != null && keyRow.startsWith("k:") && ImGui.menuItem("Remove shape key track")) {
                ObjectNode o = op("morph").put("layer", layerName(s)).put("morph", keyRow.substring(2));
                o.putNull("keys");
                apply(s, "remove shape key track", null, o);
                keyRow = null;
                keyTimes.clear();
            }
            ImGui.endPopup();
        }
        ImGui.setCursorScreenPos(x0, y + 4);
        ImGui.dummy(1, 1);
        ImGui.endChild();
    }

    private void selectJointByRef(AnimationSession s, String ref) {
        int j = ref.startsWith("role:") ? roleJoint(s, ref.substring(5)) : s.model().rig().find(ref);
        if (j >= 0) joint = j;
    }

    private int roleJoint(AnimationSession s, String role) {
        try {
            String id = s.model().definition().roles().get(role);
            return id == null ? -1 : s.model().rig().find(id);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private void moveKeys(AnimationSession s, float offset) {
        if (keyRow == null || keyTimes.isEmpty() || offset == 0f) return;
        TreeSet<Float> moved = new TreeSet<>();
        if (keyRow.startsWith("t:")) {
            ObjectNode o = op("move_keys");
            o.put("layer", layerName(s)).put("joint", keyRow.substring(2)).put("offset", offset);
            ArrayNode times = JSON.createArrayNode();
            keyTimes.forEach(times::add);
            o.set("times", times);
            if (!apply(s, "move keys", "move:" + keyRow, o)) {
                draggingKeys = false;
                return;
            }
        } else if (keyRow.startsWith("k:")) {
            // Shape-key keys: re-key at the new times (the old ones removed) with their values from the view.
            String name = keyRow.substring(2);
            List<ObjectNode> ops = new ArrayList<>();
            List<ObjectNode> adds = new ArrayList<>();
            for (JsonNode mo : s.view().path("layers").path(layer).path("morphs")) {
                if (!mo.path("name").asText().equals(name)) continue;
                for (JsonNode k : mo.path("keys")) {
                    float kt = (float) k.path(0).asDouble();
                    if (!keyTimes.contains(kt)) continue;
                    ops.add(op("morph_key").put("layer", layerName(s)).put("morph", name).put("time", kt).put("remove", true));
                    adds.add(op("morph_key").put("layer", layerName(s)).put("morph", name).put("time", kt + offset)
                            .put("value", (float) k.path(1).asDouble()));
                }
            }
            ops.addAll(adds);
            if (ops.isEmpty() || !apply(s, "move keys", "move:" + keyRow, ops.toArray(ObjectNode[]::new))) {
                draggingKeys = false;
                return;
            }
        } else if (keyRow.startsWith("e:") || keyRow.equals("w")) {
            // Effector / weight keys: re-key at the new times (the old ones removed) from the current view.
            JsonNode v = s.view();
            List<ObjectNode> ops = new ArrayList<>();
            if (keyRow.equals("w")) {
                for (JsonNode k : v.path("layers").path(layer).path("weightKeys")) {
                    float kt = (float) k.path(0).asDouble();
                    if (!keyTimes.contains(kt)) continue;
                    ops.add(op("layer_weight").put("layer", layerName(s)).put("time", kt).put("remove", true));
                }
                for (JsonNode k : v.path("layers").path(layer).path("weightKeys")) {
                    float kt = (float) k.path(0).asDouble();
                    if (!keyTimes.contains(kt)) continue;
                    ops.add(op("layer_weight").put("layer", layerName(s)).put("time", kt + offset)
                            .put("value", (float) k.path(1).asDouble()));
                }
            } else {
                String name = keyRow.substring(2);
                for (JsonNode e : v.path("effectors")) {
                    if (!e.path("name").asText().equals(name)) continue;
                    List<ObjectNode> adds = new ArrayList<>();
                    for (JsonNode k : e.path("keys")) {
                        float kt = (float) k.path("time").asDouble();
                        if (!keyTimes.contains(kt)) continue;
                        ops.add(op("effector_key").put("effector", name).put("time", kt).put("remove", true));
                        ObjectNode add = op("effector_key").put("effector", name).put("time", kt + offset);
                        add.set("pos", k.path("pos"));
                        if (k.has("rot")) add.set("rot", k.path("rot"));
                        if (k.has("pole")) add.set("pole", k.path("pole"));
                        add.put("weight", k.path("weight").asDouble(1));
                        adds.add(add);
                    }
                    ops.addAll(adds);
                }
            }
            if (ops.isEmpty() || !apply(s, "move keys", "move:" + keyRow, ops.toArray(ObjectNode[]::new))) {
                draggingKeys = false;
                return;
            }
        } else {
            return;
        }
        for (float t : keyTimes) moved.add(Math.max(0f, t + offset));
        keyTimes.clear();
        keyTimes.addAll(moved);
    }

    // ------------------------------------------------------------------------------------------------
    // Joints

    void renderJoints(AnimationSession s) {
        List<RigJoint> joints = s.model().rig().joints();
        JsonNode tracks = s.view().path("layers").path(layer).path("tracks");
        java.util.Set<String> animated = new java.util.HashSet<>();
        for (JsonNode t : tracks) animated.add(t.path("joint").asText());
        int[] depth = new int[joints.size()];
        for (int i = 0; i < joints.size(); i++) {
            int p = joints.get(i).parent();
            depth[i] = p >= 0 && p < i ? depth[p] + 1 : 0;
        }
        for (int i = 0; i < joints.size(); i++) {
            RigJoint j = joints.get(i);
            String label = "  ".repeat(Math.min(depth[i], 12)) + (j.kind() == RigJoint.Kind.BONE ? "[b] " : "[p] ")
                    + (j.name() == null || j.name().isBlank() ? j.id() : j.name()) + (animated.contains(j.id()) ? "  *" : "");
            if (ImGui.selectable(label + "##j" + i, joint == i)) selectJoint(i);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Key inspector: the selected joint's value on the selected layer

    void renderInspector(AnimationSession s) {
        renderJointInspector(s);
        renderShapeKeys(s);
    }

    private final float[] shapeValue = {0f};

    /**
     * Shape keys (#171): one slider per shape key of the model (and per morph track the layer keys that the model
     * lacks) showing the baked weight at the playhead; dragging keys it on the selected layer (OMANIM 1.3 morph
     * track, op {@code morph_key}; one undo step per drag). Rows appear in the dope sheet as "shape name".
     */
    private void renderShapeKeys(AnimationSession s) {
        List<com.openmason.engine.rig.RigMorphs.Target> keys = s.shapeKeys();
        java.util.LinkedHashMap<String, float[]> rows = new java.util.LinkedHashMap<>();
        float[] weights = keys.isEmpty() ? null : s.morphWeights(s.time());
        for (int i = 0; i < keys.size(); i++) {
            var k = keys.get(i);
            rows.put(k.name(), new float[] {weights[i], k.min(), k.max()});
        }
        java.util.Set<String> keyed = new java.util.HashSet<>();
        for (JsonNode mo : s.view().path("layers").path(layer).path("morphs")) {
            String name = mo.path("name").asText();
            keyed.add(name);
            rows.putIfAbsent(name, new float[] {lastValue(mo, s.time()), 0f, 1f});
        }
        if (rows.isEmpty()) return;
        ImGui.separator();
        ImGui.text("Shape keys");
        ImGui.textDisabled("layer " + layerName(s) + String.format(Locale.ROOT, "  -  t %.3f", s.time()));
        for (java.util.Map.Entry<String, float[]> e : rows.entrySet()) {
            String name = e.getKey();
            float[] r = e.getValue();
            shapeValue[0] = r[0];
            ImGui.setNextItemWidth(Math.max(80f, ImGui.getContentRegionAvailX() - 110f));
            if (ImGui.sliderFloat(name + (keyed.contains(name) ? " *" : "") + "##morph_" + name, shapeValue, r[1], r[2],
                    "%.3f")) {
                apply(s, "key shape " + name, "morph:" + layer + ":" + name + ":" + s.time(),
                        op("morph_key").put("layer", layerName(s)).put("morph", name).put("time", s.time())
                                .put("value", shapeValue[0]));
            }
            hint("The baked weight at the playhead; dragging keys it on the selected layer (* = this layer keys it)");
            ImGui.sameLine();
            if (ImGui.smallButton("x##morph_del_" + name)) {
                apply(s, "delete shape key key", null, op("morph_key").put("layer", layerName(s)).put("morph", name)
                        .put("time", s.time()).put("remove", true));
            }
            hint("Delete this shape key's key at the playhead (selected layer)");
        }
        if (keys.isEmpty()) ImGui.textDisabled("The model has no shape keys: tracks are kept by name");
    }

    /** A view morph track's value at t (its last key at or before t; the first key before it). */
    private static float lastValue(JsonNode track, float t) {
        float v = 0f;
        boolean any = false;
        for (JsonNode k : track.path("keys")) {
            if (!any || k.path(0).asDouble() <= t) v = (float) k.path(1).asDouble();
            any = true;
        }
        return v;
    }

    private void renderJointInspector(AnimationSession s) {
        if (joint < 0 || joint >= s.model().rig().jointCount()) {
            ImGui.textWrapped("Pick a joint in the viewport or the Joints list.");
            return;
        }
        RigJoint j = s.model().rig().joints().get(joint);
        ImGui.text((j.kind() == RigJoint.Kind.BONE ? "Bone " : "Part ") + (j.name() == null || j.name().isBlank() ? j.id() : j.name()));
        ImGui.textDisabled("layer " + layerName(s) + String.format(Locale.ROOT, "  -  t %.3f", s.time()));
        float[] v = s.authoring().layerValue(layer, joint, s.time());
        float[] pos = {v[0], v[1], v[2]}, rot = {v[3], v[4], v[5]}, scl = {v[6], v[7], v[8]};
        String mk = "key:" + layer + ":" + joint + ":" + s.time();
        String ref = jointRef(s, joint);
        if (ImGui.dragFloat3("Position##ak", pos, 0.002f, -1000f, 1000f, "%.4f")) {
            apply(s, "key " + j.name(), mk, op("key").put("layer", layerName(s)).put("joint", ref).put("time", s.time())
                    .set("pos", vec(pos)));
        }
        if (ImGui.dragFloat3("Rotation##ak", rot, 0.25f, -720f, 720f, "%.2f")) {
            apply(s, "key " + j.name(), mk, op("key").put("layer", layerName(s)).put("joint", ref).put("time", s.time())
                    .set("rot", vec(rot)));
        }
        if (ImGui.dragFloat3("Scale##ak", scl, 0.002f, 0f, 100f, "%.4f")) {
            apply(s, "key " + j.name(), mk, op("key").put("layer", layerName(s)).put("joint", ref).put("time", s.time())
                    .set("scale", vec(scl)));
        }
        hint("Dragging keys the joint at the playhead on the selected layer (one undo step per drag)");
        ImGui.setNextItemWidth(140f);
        ImGui.combo("##easing", easing, EASINGS);
        ImGui.sameLine();
        if (ImGui.button("Key")) {
            ObjectNode k = op("key").put("layer", layerName(s)).put("joint", ref).put("time", s.time()).put("easing", EASINGS[easing.get()]);
            k.set("pos", vec(pos));
            k.set("rot", vec(rot));
            k.set("scale", vec(scl));
            apply(s, "key " + j.name(), null, k);
        }
        ImGui.sameLine();
        if (ImGui.button("Delete key")) {
            ObjectNode o = op("remove_keys").put("layer", layerName(s)).put("joint", ref);
            o.set("times", vec(s.time()));
            apply(s, "delete key", null, o);
        }
        ImGui.sameLine();
        if (ImGui.button("Reset to rest")) {
            ObjectNode k = op("key").put("layer", layerName(s)).put("joint", ref).put("time", s.time());
            k.set("pos", vec(j.pos().x(), j.pos().y(), j.pos().z()));
            k.set("rot", vec(j.rotDeg().x(), j.rotDeg().y(), j.rotDeg().z()));
            k.set("scale", vec(j.scale().x(), j.scale().y(), j.scale().z()));
            apply(s, "rest key", null, k);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Layers

    void renderLayers(AnimationSession s) {
        JsonNode layers = s.view().path("layers");
        if (ImGui.beginTable("##animLayers", 4, ImGuiTableFlags.RowBg | ImGuiTableFlags.BordersInnerH)) {
            ImGui.tableSetupColumn("Layer");
            ImGui.tableSetupColumn("M", imgui.flag.ImGuiTableColumnFlags.WidthFixed, 22f);
            ImGui.tableSetupColumn("S", imgui.flag.ImGuiTableColumnFlags.WidthFixed, 22f);
            ImGui.tableSetupColumn("Weight", imgui.flag.ImGuiTableColumnFlags.WidthFixed, 90f);
            ImGui.tableHeadersRow();
            for (int i = layers.size() - 1; i >= 0; i--) {
                JsonNode l = layers.path(i);
                String name = l.path("name").asText();
                ImGui.tableNextRow();
                ImGui.tableNextColumn();
                if (ImGui.selectable(name + (i > 0 ? "  (" + l.path("mode").asText() + ")" : "  (base)") + "##L" + i, layer == i)) {
                    layer = i;
                    keyRow = null;
                    keyTimes.clear();
                }
                ImGui.tableNextColumn();
                if (ImGui.checkbox("##mute" + i, l.path("mute").asBoolean())) {
                    apply(s, "mute " + name, null, op("layer").put("name", name).put("mute", !l.path("mute").asBoolean()));
                }
                hint("Mute");
                ImGui.tableNextColumn();
                if (i > 0 && ImGui.checkbox("##solo" + i, l.path("solo").asBoolean())) {
                    apply(s, "solo " + name, null, op("layer").put("name", name).put("solo", !l.path("solo").asBoolean()));
                }
                if (i > 0) hint("Solo");
                ImGui.tableNextColumn();
                if (i > 0) {
                    float[] w = {(float) l.path("weight").asDouble(1)};
                    ImGui.setNextItemWidth(86f);
                    if (ImGui.sliderFloat("##w" + i, w, 0f, 1f, "%.2f")) {
                        apply(s, "weight " + name, "weight:" + name, op("layer").put("name", name).put("weight", w[0]));
                    }
                }
            }
            ImGui.endTable();
        }
        ImGui.setNextItemWidth(150f);
        ImGui.inputTextWithHint("##newLayer", "new layer name", newLayer);
        ImGui.sameLine();
        if (ImGui.button("Add Override")) addLayer(s, "override");
        ImGui.sameLine();
        if (ImGui.button("Add Additive")) addLayer(s, "additive");

        JsonNode l = layers.path(layer);
        if (layer <= 0 || l.isMissingNode()) {
            ImGui.textDisabled("The base layer holds the clip's own keys.");
            return;
        }
        String name = l.path("name").asText();
        ImGui.separator();
        ImGui.text("Layer " + name);
        boolean additive = l.path("mode").asText().equals("additive");
        if (ImGui.radioButton("Override", !additive)) apply(s, "mode", null, op("layer").put("name", name).put("mode", "override"));
        ImGui.sameLine();
        if (ImGui.radioButton("Additive", additive)) apply(s, "mode", null, op("layer").put("name", name).put("mode", "additive"));
        if (!additive) {
            if (ImGui.checkbox("Passthrough", l.path("passthrough").asBoolean(true))) {
                apply(s, "passthrough", null, op("layer").put("name", name).put("passthrough", !l.path("passthrough").asBoolean(true)));
            }
            hint("Joints this layer does not key show the layers below (off: the rest pose)");
        } else {
            if (ImGui.checkbox("Mesh space", l.path("meshSpace").asBoolean())) {
                apply(s, "mesh space", null, op("layer").put("name", name).put("meshSpace", !l.path("meshSpace").asBoolean()));
            }
            hint("Add the rotation delta in model space (an aim offset) instead of each joint's local space");
        }
        float[] wk = {(float) l.path("weight").asDouble(1)};
        ImGui.setNextItemWidth(120f);
        ImGui.sliderFloat("##wkv", wk, 0f, 1f, "%.2f");
        ImGui.sameLine();
        if (ImGui.button("Key weight")) {
            apply(s, "weight key", null, op("layer_weight").put("layer", name).put("time", s.time()).put("value", wk[0]));
        }
        hint("Keys the layer's weight at the playhead (it multiplies the layer weight)");
        // Mask.
        ImGui.text("Mask");
        JsonNode mask = l.path("mask");
        if (mask.isEmpty()) ImGui.textDisabled("  whole body");
        int idx = 0;
        for (JsonNode m : mask) {
            String target = m.has("role") ? "role:" + m.path("role").asText() : m.path("joint").asText();
            ImGui.bulletText(target + String.format(Locale.ROOT, "  %.2f%s", m.path("weight").asDouble(1),
                    m.path("branch").asBoolean(true) ? " (branch)" : ""));
            ImGui.sameLine();
            if (ImGui.smallButton("x##mask" + idx)) {
                ArrayNode next = JSON.createArrayNode();
                int k = 0;
                for (JsonNode o : mask) if (k++ != idx) next.add(o);
                apply(s, "mask", null, (ObjectNode) op("layer").put("name", name).set("mask", next));
            }
            idx++;
        }
        if (joint >= 0 && ImGui.button("Add selected joint (branch)")) {
            ArrayNode next = mask.isArray() ? ((ArrayNode) mask).deepCopy() : JSON.createArrayNode();
            next.add(JSON.createObjectNode().put("joint", jointRef(s, joint)).put("weight", 1f).put("branch", true));
            apply(s, "mask", null, (ObjectNode) op("layer").put("name", name).set("mask", next));
        }
        ImGui.separator();
        if (ImGui.button("Move up") && layer < layers.size() - 1) {
            if (apply(s, "reorder", null, op("layer").put("name", name).put("index", layer + 1))) layer++;
        }
        ImGui.sameLine();
        if (ImGui.button("Move down") && layer > 1) {
            if (apply(s, "reorder", null, op("layer").put("name", name).put("index", layer - 1))) layer--;
        }
        ImGui.sameLine();
        if (ImGui.button("Delete layer")) {
            if (apply(s, "delete layer " + name, null, op("layer").put("name", name).put("remove", true))) layer = 0;
        }
    }

    private void addLayer(AnimationSession s, String mode) {
        String name = newLayer.get().trim();
        if (name.isEmpty()) name = (mode.equals("additive") ? "Additive " : "Layer ") + s.view().path("layers").size();
        if (apply(s, "add layer " + name, null, op("layer").put("name", name).put("mode", mode))) {
            layer = s.view().path("layers").size() - 1;
            newLayer.set("");
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Control rig

    void renderControlRig(AnimationSession s) {
        JsonNode effectors = s.view().path("effectors");
        ImGui.textWrapped("Effectors drive limbs by IK; their keys always bake into the joint tracks. Match switches"
                + " a limb between FK and IK at the playhead without moving it.");
        if (joint >= 0) {
            List<RigJoint> joints = s.model().rig().joints();
            int mid = joints.get(joint).parent();
            int upper = mid >= 0 ? joints.get(mid).parent() : -1;
            if (upper >= 0 && ImGui.button("Add limb IK ending at " + joints.get(joint).name())) {
                String name = "IK " + joints.get(joint).name();
                if (apply(s, "add effector", null, op("effector").put("name", name).put("kind", "twoBone")
                        .put("upper", joints.get(upper).id()).put("mid", joints.get(mid).id()).put("end", joints.get(joint).id()))) {
                    effector = name;
                }
            }
            if (ImGui.button("Add look-at on " + joints.get(joint).name())) {
                String name = "Aim " + joints.get(joint).name();
                ObjectNode o = op("effector").put("name", name).put("kind", "aim");
                o.set("joints", JSON.createArrayNode().add(joints.get(joint).id()));
                o.set("axis", vec(0, 0, -1));
                if (apply(s, "add look-at", null, o)) effector = name;
            }
        } else {
            ImGui.textDisabled("Select a limb's end joint (hand, foot) to add IK, or a head for a look-at.");
        }
        ImGui.separator();
        for (JsonNode e : effectors) {
            String name = e.path("name").asText();
            if (ImGui.selectable(name + "  (" + e.path("kind").asText() + ", " + e.path("keys").size() + " keys)##eff" + name,
                    name.equals(effector))) {
                effector = name;
            }
        }
        JsonNode e = null;
        for (JsonNode x : effectors) if (x.path("name").asText().equals(effector)) e = x;
        if (e == null) return;
        ImGui.separator();
        String name = e.path("name").asText();
        JsonNode handle = handle(s, name);
        float w = handle == null ? 0f : (float) handle.path("weight").asDouble();
        ImGui.text(String.format(Locale.ROOT, "%s  -  IK weight %.2f at t %.3f", name, w, s.time()));
        if (ImGui.button("Match to IK")) {
            apply(s, "match " + name + " to IK", null, op("match").put("effector", name).put("time", s.time()).put("to", "ik"));
        }
        hint("Key the effector where FK puts the limb now (weight 1): the pose does not move");
        ImGui.sameLine();
        if (ImGui.button("Match to FK")) {
            apply(s, "match " + name + " to FK", null, op("match").put("effector", name).put("time", s.time()).put("to", "fk")
                    .put("layer", layerName(s)));
        }
        hint("Key the limb's joints on the selected layer to the solved pose, then hand back to FK (weight 0)");
        if (handle != null) {
            float[] target = floats(handle.path("target"));
            if (ImGui.dragFloat3("Target##eff", target, 0.002f, -1000f, 1000f, "%.4f")) {
                apply(s, "move " + name, "eff:" + name + ":" + s.time(), (ObjectNode) op("effector_key").put("effector", name)
                        .put("time", s.time()).put("weight", 1f).set("pos", vec(target)));
            }
            float[] wt = {w};
            if (ImGui.sliderFloat("Weight##eff", wt, 0f, 1f, "%.2f")) {
                ObjectNode o = op("effector_key").put("effector", name).put("time", s.time()).put("weight", wt[0]);
                o.set("pos", vec(target));
                apply(s, "IK weight " + name, "effw:" + name + ":" + s.time(), o);
            }
            hint("Reach: 0 = FK, 1 = the limb reaches the target");
            if (handle.has("pole")) {
                float[] pole = floats(handle.path("pole"));
                if (ImGui.dragFloat3("Pole##eff", pole, 0.002f, -1000f, 1000f, "%.4f")) {
                    ObjectNode o = op("effector_key").put("effector", name).put("time", s.time());
                    o.set("pole", vec(pole));
                    apply(s, "pole " + name, "pole:" + name + ":" + s.time(), o);
                }
            }
        }
        if (ImGui.button("Delete key at playhead")) {
            apply(s, "delete IK key", null, op("effector_key").put("effector", name).put("time", s.time()).put("remove", true));
        }
        ImGui.sameLine();
        if (ImGui.button("Delete effector")) {
            if (apply(s, "delete effector " + name, null, op("effector").put("name", name).put("remove", true))) effector = null;
        }
    }

    JsonNode handle(AnimationSession s, String name) {
        try {
            for (JsonNode h : JSON.readTree(s.authoring().handles(s.time()))) {
                if (h.path("effector").asText().equals(name)) return h;
            }
        } catch (Exception e) {
            error = e.getMessage();
        }
        return null;
    }

    static float[] floats(JsonNode a) {
        return new float[] {(float) a.path(0).asDouble(), (float) a.path(1).asDouble(), (float) a.path(2).asDouble()};
    }

    // ------------------------------------------------------------------------------------------------
    // Clip: header, runtime layer, root motion, additive

    void renderClip(AnimationSession s) {
        JsonNode v = s.view();
        nameField.set(v.path("name").asText());
        if (ImGui.inputText("Name##clip", nameField, imgui.flag.ImGuiInputTextFlags.EnterReturnsTrue)) {
            apply(s, "rename clip", null, op("header").put("name", nameField.get()));
        }
        float[] fps = {(float) v.path("fps").asDouble(30)};
        if (ImGui.dragFloat("FPS##clip", fps, 0.5f, 1f, 240f, "%.0f")) {
            apply(s, "fps", "fps", op("header").put("fps", fps[0]));
        }
        float[] dur = {(float) v.path("duration").asDouble(1)};
        if (ImGui.dragFloat("Duration##clip", dur, 0.01f, 0.01f, 600f, "%.3f s")) {
            apply(s, "duration", "duration", op("header").put("duration", dur[0]));
        }
        if (ImGui.checkbox("Loop##clip", v.path("loop").asBoolean())) {
            apply(s, "loop", null, op("header").put("loop", !v.path("loop").asBoolean()));
        }
        ImGui.textDisabled("Model: " + v.path("modelRef").asText("(set on save)"));

        ImGui.separator();
        ImGui.text("How a game plays it");
        JsonNode cl = v.path("clipLayer");
        boolean overlay = cl.path("type").asText("BASE").equals("OVERLAY");
        if (ImGui.radioButton("Base clip", !overlay)) apply(s, "clip layer", null, op("clip_layer").put("type", "BASE"));
        ImGui.sameLine();
        if (ImGui.radioButton("Overlay", overlay)) apply(s, "clip layer", null, op("clip_layer").put("type", "OVERLAY"));
        hint("An overlay plays over a base clip on the masked joints (an attack over a walk)");
        if (overlay) {
            float[] fin = {(float) cl.path("fadeIn").asDouble(0.1)}, fout = {(float) cl.path("fadeOut").asDouble(0.1)};
            if (ImGui.dragFloat("Fade in##cl", fin, 0.005f, 0f, 10f, "%.3f s")) apply(s, "fade in", "fadeIn", op("clip_layer").put("fadeIn", fin[0]));
            if (ImGui.dragFloat("Fade out##cl", fout, 0.005f, 0f, 10f, "%.3f s")) apply(s, "fade out", "fadeOut", op("clip_layer").put("fadeOut", fout[0]));
            int[] pr = {cl.path("priority").asInt()};
            if (ImGui.dragInt("Priority##cl", pr, 0.1f, -100, 100)) apply(s, "priority", "priority", op("clip_layer").put("priority", pr[0]));
            ImGui.textWrapped("Mask: " + (cl.path("mask").isEmpty() ? "(all)" : join(cl.path("mask"))));
            if (joint >= 0 && ImGui.button("Add selected joint to mask")) {
                ArrayNode m = ((ArrayNode) cl.path("mask")).deepCopy();
                m.add(jointRef(s, joint));
                apply(s, "overlay mask", null, (ObjectNode) op("clip_layer").set("mask", m));
            }
            ImGui.sameLine();
            if (ImGui.button("Clear mask")) apply(s, "overlay mask", null, (ObjectNode) op("clip_layer").set("mask", null));
        }

        ImGui.separator();
        ImGui.text("Root motion");
        JsonNode rm = v.path("rootMotion");
        if (rm.isMissingNode()) {
            ImGui.textDisabled("  off");
        } else {
            ImGui.textDisabled("  " + rm.path("joint").asText() + " on " + join(rm.path("axes")));
        }
        if (joint >= 0 && ImGui.button("Extract from selected joint (x, z, yaw)")) {
            ObjectNode o = op("root_motion").put("joint", jointRef(s, joint));
            o.set("axes", JSON.createArrayNode().add("x").add("z").add("yaw"));
            apply(s, "root motion", null, o);
        }
        if (!rm.isMissingNode()) {
            ImGui.sameLine();
            if (ImGui.button("Off##rm")) apply(s, "root motion off", null, op("root_motion"));
        }

        ImGui.separator();
        ImGui.text("Additive clip");
        JsonNode add = v.path("additive");
        String ref = add.path("reference").asText("none");
        for (String r : new String[] {"none", "frame", "character"}) {
            if (ImGui.radioButton(r + "##addref", ref.equals(r))) {
                ObjectNode o = op("additive").put("reference", r);
                if (r.equals("frame")) o.put("time", s.time());
                apply(s, "additive " + r, null, o);
            }
            ImGui.sameLine();
        }
        ImGui.newLine();
        hint("The pose this clip's keys are a difference from: none (absolute), a frame of this clip, or the"
                + " character's reference pose");
        if (ref.equals("frame")) {
            ImGui.textDisabled(String.format(Locale.ROOT, "  reference frame at %.3f s", add.path("time").asDouble()));
            if (ImGui.button("Use playhead as reference")) {
                apply(s, "additive frame", null, op("additive").put("reference", "frame").put("time", s.time()));
            }
        }
    }

    private static String join(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(a -> out.add(a.asText()));
        return String.join(", ", out);
    }

    // ------------------------------------------------------------------------------------------------
    // Events: notifies, sync markers, curves

    void renderEvents(AnimationSession s) {
        JsonNode v = s.view();
        ImGui.text("Notifies");
        int i = 0;
        for (JsonNode n : v.path("notifies")) {
            String label = String.format(Locale.ROOT, "%s @ %.3f%s", n.path("name").asText(), n.path("time").asDouble(),
                    n.has("duration") ? String.format(Locale.ROOT, " (+%.3f)", n.path("duration").asDouble()) : "");
            ImGui.bulletText(label + (n.has("payload") ? "  " + n.path("payload") : ""));
            ImGui.sameLine();
            if (ImGui.smallButton("go##n" + i)) s.setTime((float) n.path("time").asDouble());
            ImGui.sameLine();
            if (ImGui.smallButton("x##n" + i)) apply(s, "remove notify", null, op("notify").put("index", i).put("remove", true));
            i++;
        }
        ImGui.setNextItemWidth(120f);
        ImGui.inputTextWithHint("##evName", "name", newName);
        ImGui.sameLine();
        ImGui.setNextItemWidth(160f);
        ImGui.inputTextWithHint("##evPayload", "payload {json}", payload);
        if (ImGui.button("Add notify at playhead")) {
            ObjectNode o = op("notify").put("name", newName.get().isBlank() ? "event" : newName.get()).put("time", s.time());
            if (!payload.get().isBlank()) {
                try {
                    o.set("payload", JSON.readTree(payload.get()));
                } catch (Exception e) {
                    error = "payload is not JSON: " + e.getMessage();
                    return;
                }
            }
            apply(s, "add notify", null, o);
        }
        ImGui.sameLine();
        if (ImGui.button("Add range (to end)")) {
            apply(s, "add notify", null, op("notify").put("name", newName.get().isBlank() ? "range" : newName.get())
                    .put("time", s.time()).put("duration", Math.max(frame(s), s.duration() - s.time())));
        }
        ImGui.separator();
        ImGui.text("Sync markers");
        i = 0;
        for (JsonNode m : v.path("markers")) {
            ImGui.bulletText(String.format(Locale.ROOT, "%s @ %.3f", m.path("name").asText(), m.path("time").asDouble()));
            ImGui.sameLine();
            if (ImGui.smallButton("x##m" + i)) apply(s, "remove marker", null, op("marker").put("index", i).put("remove", true));
            i++;
        }
        if (ImGui.button("Add marker at playhead")) {
            apply(s, "add marker", null, op("marker").put("name", newName.get().isBlank() ? "foot" : newName.get()).put("time", s.time()));
        }
        hint("Sync markers (left foot down...) let a graph keep clips of different lengths in step");
        ImGui.separator();
        ImGui.text("Curves");
        for (JsonNode c : v.path("curves")) {
            String name = c.path("name").asText();
            ImGui.bulletText(name + "  (" + c.path("keys").size() + " keys)");
            ImGui.sameLine();
            renderCurveKeyButton(s, c, name);
            ImGui.sameLine();
            if (ImGui.smallButton("x##c" + name)) apply(s, "remove curve", null, (ObjectNode) op("curve").put("name", name));
            renderCurvePlot(s, c);
        }
        if (ImGui.button("New curve")) {
            String name = newName.get().isBlank() ? "curve" : newName.get();
            ObjectNode o = op("curve").put("name", name);
            o.set("keys", JSON.createArrayNode().add(JSON.createObjectNode().put("time", 0f).put("value", 0f)));
            apply(s, "new curve", null, o);
        }
        hint("Float curves (a weapon trail, a blink) the game reads by name while the clip plays");
    }

    private final float[] curveValue = {0f};

    private void renderCurveKeyButton(AnimationSession s, JsonNode c, String name) {
        if (ImGui.smallButton("key##c" + name)) ImGui.openPopup("##curveKey" + name);
        if (ImGui.beginPopup("##curveKey" + name)) {
            ImGui.setNextItemWidth(100f);
            ImGui.dragFloat("value", curveValue, 0.01f);
            if (ImGui.button("Key at playhead")) {
                ArrayNode keys = JSON.createArrayNode();
                boolean replaced = false;
                for (JsonNode k : c.path("keys")) {
                    float kt = (float) k.path(0).asDouble();
                    if (Math.abs(kt - s.time()) < 1e-4f) {
                        replaced = true;
                        keys.add(JSON.createObjectNode().put("time", s.time()).put("value", curveValue[0]));
                    } else {
                        keys.add(JSON.createObjectNode().put("time", kt).put("value", (float) k.path(1).asDouble()));
                    }
                }
                if (!replaced) keys.add(JSON.createObjectNode().put("time", s.time()).put("value", curveValue[0]));
                apply(s, "curve key", null, (ObjectNode) op("curve").put("name", name).set("keys", keys));
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
    }

    private void renderCurvePlot(AnimationSession s, JsonNode c) {
        JsonNode keys = c.path("keys");
        if (keys.size() < 1) return;
        float w = Math.max(60f, ImGui.getContentRegionAvailX()), h = 36f;
        float x0 = ImGui.getCursorScreenPosX(), y0 = ImGui.getCursorScreenPosY();
        float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
        for (JsonNode k : keys) {
            lo = Math.min(lo, (float) k.path(1).asDouble());
            hi = Math.max(hi, (float) k.path(1).asDouble());
        }
        if (hi - lo < 1e-6f) {
            lo -= 0.5f;
            hi += 0.5f;
        }
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addRectFilled(x0, y0, x0 + w, y0 + h, ImGui.getColorU32(ImGuiCol.FrameBg));
        float dur = Math.max(1e-3f, s.duration());
        float px = 0, py = 0;
        for (int i = 0; i < keys.size(); i++) {
            float x = x0 + (float) keys.path(i).path(0).asDouble() / dur * w;
            float y = y0 + h - ((float) keys.path(i).path(1).asDouble() - lo) / (hi - lo) * h;
            if (i > 0) dl.addLine(px, py, x, y, 0xFF80D0FF, 1.5f);
            dl.addCircleFilled(x, y, 3f, 0xFFFFFFFF);
            px = x;
            py = y;
        }
        float t = x0 + s.time() / dur * w;
        dl.addLine(t, y0, t, y0 + h, 0xFF4060FF);
        ImGui.dummy(w, h + 2);
    }
}
