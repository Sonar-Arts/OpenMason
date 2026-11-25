package com.openmason.main.systems.animgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImString;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static com.openmason.main.systems.animgraph.AnimGraphCanvas.col;
import static com.openmason.main.systems.animgraph.AnimGraphCanvas.quote;

/**
 * The Animation Graph tab's side panels (OpenMason #164), ImGui only: parameters (with live preview values),
 * the inspector (the selected node's members, the selected state / transition), the blend-space editor, the
 * debugger, the problems list and the preview setup. Every change is a document operation through
 * {@link AnimGraphEditorContext#apply} (drags use merge keys).
 */
final class AnimGraphPanels {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] PARAM_TYPES = {"float", "int", "bool", "trigger", "vec2"};
    private static final String[] OPS = {"", "==", "!=", ">", "<", ">=", "<="};

    private final AnimGraphEditorContext ctx;
    private final ImString newParam = new ImString(64);
    private final ImString newSlot = new ImString(64);
    private final ImString rename = new ImString(64);
    private final ImString characterField = new ImString(512);
    private String renaming;

    AnimGraphPanels(AnimGraphEditorContext ctx) {
        this.ctx = ctx;
    }

    static void hint(String text) {
        if (ImGui.isItemHovered()) ImGui.setTooltip(text);
    }

    void renderMessages() {
        if (ctx.error != null) {
            ImGui.textColored(0.95f, 0.45f, 0.4f, 1f, ctx.error);
            ImGui.sameLine();
            if (ImGui.smallButton("ok##err")) ctx.error = null;
        }
        if (ctx.note != null) {
            ImGui.textColored(0.95f, 0.8f, 0.4f, 1f, ctx.note);
            ImGui.sameLine();
            if (ImGui.smallButton("ok##note")) ctx.note = null;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Parameters and slots

    void renderParameters(AnimGraphSession s) {
        JsonNode view = ctx.view();
        ImGui.textDisabled("Parameters (values drive the preview; not saved)");
        for (JsonNode p : view.path("parameters")) {
            String name = p.path("name").asText();
            String type = p.path("type").asText();
            ImGui.pushID("p" + name);
            ImGui.setNextItemWidth(120);
            switch (type) {
                case "float" -> {
                    float[] v = {s.param(name) instanceof Number n ? n.floatValue() : 0f};
                    if (ImGui.dragFloat(name, v, 0.02f)) s.setParam(name, v[0]);
                }
                case "int" -> {
                    int[] v = {s.param(name) instanceof Number n ? n.intValue() : 0};
                    if (ImGui.dragInt(name, v)) s.setParam(name, v[0]);
                }
                case "bool" -> {
                    boolean v = Boolean.TRUE.equals(s.param(name));
                    if (ImGui.checkbox(name, v)) s.setParam(name, !v);
                }
                case "vec2" -> {
                    float[] v = s.param(name) instanceof float[] f ? f.clone() : new float[2];
                    if (ImGui.dragFloat2(name, v, 0.02f)) s.setParam(name, v);
                }
                default -> {
                    if (ImGui.button("Fire " + name)) s.fireTrigger(name);
                }
            }
            ImGui.sameLine();
            ImGui.textDisabled(type);
            ImGui.sameLine();
            if (ImGui.smallButton("rename")) {
                renaming = "param:" + name;
                rename.set(name);
            }
            ImGui.sameLine();
            if (ImGui.smallButton("x")) ctx.apply("[{\"op\":\"param\",\"name\":" + quote(name) + ",\"remove\":true}]", "remove parameter", null);
            if (("param:" + name).equals(renaming)) renderRename("param", name);
            ImGui.popID();
        }
        ImGui.setNextItemWidth(120);
        ImGui.inputTextWithHint("##newParam", "new parameter", newParam);
        for (String t : PARAM_TYPES) {
            ImGui.sameLine();
            if (ImGui.smallButton(t + "##add") && !newParam.get().isBlank()) {
                ctx.apply("[{\"op\":\"param\",\"name\":" + quote(newParam.get().trim()) + ",\"type\":" + quote(t) + "}]",
                        "add parameter", null);
                newParam.set("");
            }
        }
        ImGui.separator();
        ImGui.textDisabled("Clip slots (the character binds a clip to each)");
        for (JsonNode sl : view.path("slots")) {
            String name = sl.asText();
            ImGui.pushID("s" + name);
            ImGui.bulletText(name);
            ImGui.sameLine();
            if (ImGui.smallButton("rename")) {
                renaming = "slot:" + name;
                rename.set(name);
            }
            ImGui.sameLine();
            if (ImGui.smallButton("x")) ctx.apply("[{\"op\":\"slot\",\"name\":" + quote(name) + ",\"remove\":true}]", "remove slot", null);
            if (("slot:" + name).equals(renaming)) renderRename("slot", name);
            ImGui.popID();
        }
        ImGui.setNextItemWidth(120);
        ImGui.inputTextWithHint("##newSlot", "new slot", newSlot);
        ImGui.sameLine();
        if (ImGui.smallButton("Add slot") && !newSlot.get().isBlank()) {
            ctx.apply("[{\"op\":\"slot\",\"name\":" + quote(newSlot.get().trim()) + "}]", "add slot", null);
            newSlot.set("");
        }
    }

    private void renderRename(String op, String name) {
        ImGui.setNextItemWidth(140);
        ImGui.inputText("##rename", rename);
        ImGui.sameLine();
        if (ImGui.smallButton("OK")) {
            String key = op.equals("node") ? "id" : "name";
            boolean ok = ctx.apply("[{\"op\":\"" + op + "\",\"" + key + "\":" + quote(name) + ",\"rename\":"
                    + quote(rename.get().trim()) + "}]", "rename " + name, null);
            if (ok && op.equals("node")) ctx.selectNode(rename.get().trim());
            renaming = null;
        }
        ImGui.sameLine();
        if (ImGui.smallButton("Cancel")) renaming = null;
    }

    // ------------------------------------------------------------------------------------------------
    // Inspector

    void renderInspector() {
        JsonNode n = ctx.nodeJson(ctx.selectedNode);
        if (n == null) {
            renderTransitionOrState();
            if (ctx.selectedState == null && ctx.selectedTransition < 0) ImGui.textDisabled("Select a node, state or transition.");
            return;
        }
        String id = n.path("id").asText();
        String type = n.path("type").asText();
        ImGui.text(type + "  " + id);
        ImGui.sameLine();
        if (ImGui.smallButton("rename##node")) {
            renaming = "node:" + id;
            rename.set(id);
        }
        if (("node:" + id).equals(renaming)) renderRename("node", id);
        JsonNode doc = ctx.documentJson();
        Consumer<String> patch = members -> ctx.apply("[{\"op\":\"node\",\"id\":" + quote(id) + "," + members + "}]",
                "edit " + id, "edit:" + id);
        switch (type) {
            case "clip" -> {
                choose("Slot", n.path("slot").asText(""), doc.path("slots"), v -> patch.accept("\"slot\":" + quote(v)));
                floatMember("Rate", n, "rate", 1f, 0.01f, patch);
                paramChoose("Rate param", n.path("rateParam").asText(""), doc, "float", v -> patch.accept("\"rateParam\":" + (v.isEmpty() ? "null" : quote(v))));
                textMember("Sync group", n, "sync", patch);
            }
            case "blend1d" -> paramChoose("Parameter", n.path("param").asText(""), doc, "float", v -> patch.accept("\"param\":" + quote(v)));
            case "blend2d" -> {
                paramChoose("Parameter (vec2)", n.path("param").asText(""), doc, "vec2", v -> patch.accept("\"param\":" + quote(v)
                        + ",\"paramX\":null,\"paramY\":null"));
                String mode = n.path("mode").asText("cartesian");
                if (ImGui.beginCombo("Mode", mode)) {
                    for (String m : new String[] {"cartesian", "directional"}) {
                        if (ImGui.selectable(m, m.equals(mode))) patch.accept("\"mode\":" + quote(m));
                    }
                    ImGui.endCombo();
                }
            }
            case "stateMachine" -> {
                ImGui.textDisabled("Edit its states in the State Machine panel.");
                ctx.selectedMachine = id;
            }
            case "layer" -> renderLayers(n, patch);
            case "slot" -> {
                textMember("Montage slot name", n, "name", patch);
                maskChoose("Mask", n.path("mask").asText(""), doc, v -> patch.accept("\"mask\":" + (v.isEmpty() ? "null" : quote(v))));
            }
            default -> {
            }
        }
        ImGui.separator();
        if (ImGui.button("Delete node")) {
            ctx.apply("[{\"op\":\"node\",\"id\":" + quote(id) + ",\"remove\":true}]", "delete " + id, null);
            ctx.selectNode(null);
        }
        ImGui.sameLine();
        if (!id.equals(ctx.view().path("root").asText()) && ImGui.button("Make output")) {
            ctx.apply("[{\"op\":\"graph\",\"root\":" + quote(id) + "}]", "set output", null);
        }
    }

    private void renderLayers(JsonNode n, Consumer<String> patch) {
        ArrayNode layers = n.path("layers").isArray() ? (ArrayNode) n.path("layers").deepCopy() : JSON.createArrayNode();
        JsonNode doc = ctx.documentJson();
        boolean changed = false;
        for (int i = 0; i < layers.size(); i++) {
            ObjectNode l = (ObjectNode) layers.get(i);
            ImGui.pushID("layer" + i);
            ImGui.text("Layer " + i + ": " + l.path("node").asText("(none)"));
            String mode = l.path("mode").asText("override");
            if (ImGui.beginCombo("Mode", mode)) {
                for (String m : new String[] {"override", "additive"}) {
                    if (ImGui.selectable(m, m.equals(mode))) {
                        l.put("mode", m);
                        changed = true;
                    }
                }
                ImGui.endCombo();
            }
            float[] w = {(float) l.path("weight").asDouble(1)};
            if (ImGui.sliderFloat("Weight", w, 0f, 1f)) {
                l.put("weight", w[0]);
                changed = true;
            }
            String mask = l.path("mask").asText("");
            if (ImGui.beginCombo("Mask", mask.isEmpty() ? "(whole body)" : mask)) {
                if (ImGui.selectable("(whole body)", mask.isEmpty())) {
                    l.remove("mask");
                    changed = true;
                }
                for (JsonNode m : doc.path("masks")) {
                    String name = m.path("name").asText();
                    if (ImGui.selectable(name, name.equals(mask))) {
                        l.put("mask", name);
                        changed = true;
                    }
                }
                ImGui.endCombo();
            }
            String wp = l.path("weightParam").asText("");
            if (ImGui.beginCombo("Weight param", wp.isEmpty() ? "(none)" : wp)) {
                if (ImGui.selectable("(none)", wp.isEmpty())) {
                    l.remove("weightParam");
                    changed = true;
                }
                for (JsonNode p : doc.path("parameters")) {
                    if (!p.path("type").asText().equals("float")) continue;
                    String name = p.path("name").asText();
                    if (ImGui.selectable(name, name.equals(wp))) {
                        l.put("weightParam", name);
                        changed = true;
                    }
                }
                ImGui.endCombo();
            }
            boolean pass = l.path("passthrough").asBoolean(true);
            if (ImGui.checkbox("Passthrough", pass)) {
                l.put("passthrough", !pass);
                changed = true;
            }
            ImGui.popID();
            ImGui.separator();
        }
        if (changed) patch.accept("\"layers\":" + layers);
        ImGui.textDisabled("Connect the base and layers on the canvas (drag an output onto an input).");
    }

    private void renderTransitionOrState() {
        String machine = ctx.selectedMachine;
        if (machine == null) return;
        JsonNode m = ctx.nodeJson(machine);
        if (m == null) return;
        if (ctx.selectedState != null) {
            ImGui.text("State " + ctx.selectedState + " of " + machine);
            for (JsonNode s : m.path("states")) {
                if (!s.path("name").asText().equals(ctx.selectedState)) continue;
                boolean conduit = s.path("conduit").asBoolean(false);
                if (ImGui.checkbox("Conduit", conduit)) {
                    ctx.apply("[{\"op\":\"state\",\"machine\":" + quote(machine) + ",\"name\":" + quote(ctx.selectedState)
                            + ",\"conduit\":" + (!conduit) + "}]", "conduit", null);
                }
                if (!conduit) {
                    String node = s.path("node").asText("");
                    if (ImGui.beginCombo("Node", node.isEmpty() ? "(none)" : node)) {
                        for (JsonNode n : ctx.view().path("nodes")) {
                            String id = n.path("id").asText();
                            if (id.equals(machine)) continue;
                            if (ImGui.selectable(id, id.equals(node))) {
                                ctx.apply("[{\"op\":\"state\",\"machine\":" + quote(machine) + ",\"name\":" + quote(ctx.selectedState)
                                        + ",\"node\":" + quote(id) + "}]", "state node", null);
                            }
                        }
                        ImGui.endCombo();
                    }
                }
            }
            if (ImGui.button("Rename state")) {
                renaming = "state:" + ctx.selectedState;
                rename.set(ctx.selectedState);
            }
            if (("state:" + ctx.selectedState).equals(renaming)) {
                ImGui.setNextItemWidth(140);
                ImGui.inputText("##renameState", rename);
                ImGui.sameLine();
                if (ImGui.smallButton("OK")) {
                    String to = rename.get().trim();
                    if (ctx.apply("[{\"op\":\"state\",\"machine\":" + quote(machine) + ",\"name\":" + quote(ctx.selectedState)
                            + ",\"rename\":" + quote(to) + "}]", "rename state", null)) {
                        ctx.selectedState = to;
                    }
                    renaming = null;
                }
            }
            return;
        }
        int ti = ctx.selectedTransition;
        if (ti < 0 || ti >= m.path("transitions").size()) return;
        ObjectNode t = (ObjectNode) m.path("transitions").get(ti).deepCopy();
        ImGui.text("Transition " + t.path("from").asText() + " -> " + t.path("to").asText());
        String key = "transition:" + machine + ":" + ti;
        Consumer<ObjectNode> set = changed -> {
            ObjectNode op = changed.deepCopy();
            op.put("op", "transition");
            op.put("machine", machine);
            op.put("index", ti);
            ctx.apply("[" + op + "]", "edit transition", key);
        };
        imgui.type.ImInt prio = new imgui.type.ImInt(t.path("priority").asInt(0));
        if (ImGui.inputInt("Priority", prio)) set.accept(JSON.createObjectNode().put("priority", prio.get()));
        float[] blend = {(float) t.path("blend").asDouble(0.2)};
        if (ImGui.dragFloat("Blend (s)", blend, 0.005f, 0f, 5f)) set.accept(JSON.createObjectNode().put("blend", blend[0]));
        String mode = t.path("mode").asText("inertialize");
        if (ImGui.beginCombo("Mode##t", mode)) {
            for (String mm : new String[] {"inertialize", "crossfade"}) {
                if (ImGui.selectable(mm, mm.equals(mode))) set.accept(JSON.createObjectNode().put("mode", mm));
            }
            ImGui.endCombo();
        }
        boolean hasExit = t.has("exitTime");
        if (ImGui.checkbox("Exit time", hasExit)) {
            ObjectNode o = JSON.createObjectNode();
            if (hasExit) o.putNull("exitTime");
            else o.put("exitTime", 1.0);
            set.accept(o);
        }
        if (hasExit) {
            ImGui.sameLine();
            float[] ex = {(float) t.path("exitTime").asDouble()};
            ImGui.setNextItemWidth(100);
            if (ImGui.dragFloat("##exit", ex, 0.01f, 0f, 10f)) set.accept(JSON.createObjectNode().put("exitTime", ex[0]));
        }
        boolean interrupt = t.path("canInterrupt").asBoolean(true);
        if (ImGui.checkbox("Can interrupt", interrupt)) set.accept(JSON.createObjectNode().put("canInterrupt", !interrupt));
        // Conditions.
        ImGui.separator();
        ImGui.textDisabled("Conditions (all must hold)");
        ArrayNode conds = t.path("conditions").isArray() ? (ArrayNode) t.path("conditions") : JSON.createArrayNode();
        JsonNode doc = ctx.documentJson();
        boolean changed = false;
        for (int i = 0; i < conds.size(); i++) {
            ObjectNode c = (ObjectNode) conds.get(i);
            ImGui.pushID("c" + i);
            ImGui.setNextItemWidth(110);
            String param = c.path("param").asText("");
            if (ImGui.beginCombo("##param", param)) {
                for (JsonNode p : doc.path("parameters")) {
                    String name = p.path("name").asText();
                    if (ImGui.selectable(name, name.equals(param))) {
                        c.put("param", name);
                        changed = true;
                    }
                }
                ImGui.endCombo();
            }
            ImGui.sameLine();
            ImGui.setNextItemWidth(55);
            String op = c.path("op").asText("");
            if (ImGui.beginCombo("##op", op.isEmpty() ? "true" : op)) {
                for (String o : OPS) {
                    if (ImGui.selectable(o.isEmpty() ? "true" : o, o.equals(op))) {
                        if (o.isEmpty()) {
                            c.remove("op");
                            c.remove("value");
                        } else {
                            c.put("op", o);
                            if (!c.has("value")) c.put("value", 0);
                        }
                        changed = true;
                    }
                }
                ImGui.endCombo();
            }
            if (c.has("op")) {
                ImGui.sameLine();
                ImGui.setNextItemWidth(70);
                float[] v = {(float) c.path("value").asDouble()};
                if (ImGui.dragFloat("##v", v, 0.05f)) {
                    c.put("value", v[0]);
                    changed = true;
                }
            }
            ImGui.sameLine();
            if (ImGui.smallButton("x")) {
                conds.remove(i);
                changed = true;
                ImGui.popID();
                break;
            }
            ImGui.popID();
        }
        if (ImGui.smallButton("+ condition")) {
            ObjectNode c = conds.addObject();
            Iterator<JsonNode> ps = doc.path("parameters").elements();
            c.put("param", ps.hasNext() ? ps.next().path("name").asText() : "");
            changed = true;
        }
        if (changed) {
            ObjectNode o = JSON.createObjectNode();
            o.set("conditions", conds);
            set.accept(o);
        }
        ImGui.separator();
        if (ImGui.button("Delete transition")) {
            ctx.apply("[{\"op\":\"transition\",\"machine\":" + quote(machine) + ",\"index\":" + ti + ",\"remove\":true}]",
                    "delete transition", null);
            ctx.selectedTransition = -1;
        }
    }

    private void choose(String label, String current, JsonNode names, Consumer<String> pick) {
        if (ImGui.beginCombo(label, current.isEmpty() ? "(none)" : current)) {
            for (JsonNode n : names) {
                String v = n.isTextual() ? n.asText() : n.path("name").asText();
                if (ImGui.selectable(v, v.equals(current))) pick.accept(v);
            }
            ImGui.endCombo();
        }
    }

    private void paramChoose(String label, String current, JsonNode doc, String type, Consumer<String> pick) {
        if (ImGui.beginCombo(label, current.isEmpty() ? "(none)" : current)) {
            if (label.contains("Rate") && ImGui.selectable("(none)", current.isEmpty())) pick.accept("");
            for (JsonNode p : doc.path("parameters")) {
                if (!p.path("type").asText().equals(type)) continue;
                String v = p.path("name").asText();
                if (ImGui.selectable(v, v.equals(current))) pick.accept(v);
            }
            ImGui.endCombo();
        }
    }

    private void maskChoose(String label, String current, JsonNode doc, Consumer<String> pick) {
        if (ImGui.beginCombo(label, current.isEmpty() ? "(whole body)" : current)) {
            if (ImGui.selectable("(whole body)", current.isEmpty())) pick.accept("");
            for (JsonNode m : doc.path("masks")) {
                String v = m.path("name").asText();
                if (ImGui.selectable(v, v.equals(current))) pick.accept(v);
            }
            ImGui.endCombo();
        }
    }

    private void floatMember(String label, JsonNode n, String key, float fallback, float speed, Consumer<String> patch) {
        float[] v = {(float) n.path(key).asDouble(fallback)};
        if (ImGui.dragFloat(label, v, speed)) patch.accept(quote(key) + ":" + v[0]);
    }

    private void textMember(String label, JsonNode n, String key, Consumer<String> patch) {
        ImString s = new ImString(n.path(key).asText(""), 128);
        if (ImGui.inputText(label, s)) patch.accept(quote(key) + ":" + (s.get().isEmpty() ? "null" : quote(s.get())));
    }

    // ------------------------------------------------------------------------------------------------
    // Blend space

    void renderBlendSpace(float width, float height) {
        JsonNode n = ctx.nodeJson(ctx.selectedNode);
        if (n == null || !(n.path("type").asText().equals("blend1d") || n.path("type").asText().equals("blend2d"))) {
            ImGui.textDisabled("Select a blend1d / blend2d node.");
            return;
        }
        String id = n.path("id").asText();
        boolean two = n.path("type").asText().equals("blend2d");
        JsonNode samples = n.path("samples");
        if (samples.isEmpty()) {
            ImGui.textDisabled("No samples: connect clips to the node's + input on the canvas.");
            return;
        }
        double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y0 = Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (JsonNode s : samples) {
            double x = two ? s.path("x").asDouble() : s.path("value").asDouble();
            double y = two ? s.path("y").asDouble() : 0;
            x0 = Math.min(x0, x);
            x1 = Math.max(x1, x);
            y0 = Math.min(y0, y);
            y1 = Math.max(y1, y);
        }
        double padX = Math.max(0.5, (x1 - x0) * 0.15), padY = Math.max(0.5, (y1 - y0) * 0.15);
        x0 -= padX;
        x1 += padX;
        y0 -= padY;
        y1 += padY;
        float h = two ? Math.max(120, height - 60) : 70;
        float ox = ImGui.getCursorScreenPosX(), oy = ImGui.getCursorScreenPosY();
        ImGui.invisibleButton("##blendspace", Math.max(width, 20), h);
        boolean hovered = ImGui.isItemHovered();
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addRectFilled(ox, oy, ox + width, oy + h, col(0.09f, 0.1f, 0.12f, 1f), 4f);
        final double fx0 = x0, fx1 = x1, fy0 = y0, fy1 = y1;
        java.util.function.DoubleUnaryOperator px = v -> ox + (v - fx0) / (fx1 - fx0) * width;
        java.util.function.DoubleUnaryOperator py = v -> two ? oy + h - (v - fy0) / (fy1 - fy0) * h : oy + h / 2;
        // Axes through 0.
        dl.addLine((float) px.applyAsDouble(0), oy, (float) px.applyAsDouble(0), oy + h, col(1, 1, 1, 0.1f));
        if (two) dl.addLine(ox, (float) py.applyAsDouble(0), ox + width, (float) py.applyAsDouble(0), col(1, 1, 1, 0.1f));
        // Sample weights from the debugger.
        float[] weights = null;
        if (ctx.debug() != null) {
            for (JsonNode d : ctx.debug().path("nodes")) {
                if (d.path("id").asText().equals(id) && d.has("weights")) {
                    weights = new float[d.path("weights").size()];
                    for (int i = 0; i < weights.length; i++) weights[i] = (float) d.path("weights").get(i).asDouble();
                }
            }
        }
        float mx = ImGui.getIO().getMousePosX(), my = ImGui.getIO().getMousePosY();
        int i = 0;
        for (JsonNode s : samples) {
            double x = two ? s.path("x").asDouble() : s.path("value").asDouble();
            double y = two ? s.path("y").asDouble() : 0;
            float sx = (float) px.applyAsDouble(x), sy = (float) py.applyAsDouble(y);
            float w = weights != null && i < weights.length ? weights[i] : 0f;
            dl.addCircleFilled(sx, sy, 6 + 8 * w, col(0.4f, 0.8f, 0.5f, 0.35f + 0.65f * w));
            dl.addCircle(sx, sy, 6, col(1, 1, 1, 0.9f));
            dl.addText(sx + 8, sy - 16, col(0.85f, 0.87f, 0.92f, 1f), s.path("node").asText() + String.format(Locale.ROOT, " %.2f", w));
            if (hovered && ImGui.isMouseDragging(ImGuiMouseButton.Left, 1f) && Math.hypot(mx - sx, my - sy) < 12) {
                double nx = fx0 + (mx - ox) / width * (fx1 - fx0);
                String op = two
                        ? String.format(Locale.ROOT, "{\"op\":\"sample\",\"node\":%s,\"index\":%d,\"x\":%.3f,\"y\":%.3f}", quote(id), i, nx,
                                fy0 + (oy + h - my) / h * (fy1 - fy0))
                        : String.format(Locale.ROOT, "{\"op\":\"sample\",\"node\":%s,\"index\":%d,\"value\":%.3f}", quote(id), i, nx);
                ctx.apply("[" + op + "]", "move sample", "sample:" + id + ":" + i);
            }
            i++;
        }
        // The input: the preview's parameter value; Ctrl+drag moves it (scrub).
        AnimGraphSession s = ctx.session();
        String param = n.path("param").asText("");
        if (s != null && !param.isEmpty()) {
            float vx, vy = 0;
            Object v = s.param(param);
            if (two && v instanceof float[] f) {
                vx = f[0];
                vy = f[1];
            } else {
                vx = v instanceof Number num ? num.floatValue() : 0f;
            }
            float ix = (float) px.applyAsDouble(vx), iy = (float) py.applyAsDouble(vy);
            dl.addLine(ix - 8, iy, ix + 8, iy, col(0.95f, 0.8f, 0.35f, 1f), 2f);
            dl.addLine(ix, iy - 8, ix, iy + 8, col(0.95f, 0.8f, 0.35f, 1f), 2f);
            if (hovered && ImGui.getIO().getKeyCtrl() && ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                double nx = fx0 + (mx - ox) / width * (fx1 - fx0);
                if (two) s.setParam(param, new float[] {(float) nx, (float) (fy0 + (oy + h - my) / h * (fy1 - fy0))});
                else s.setParam(param, (float) nx);
            }
        }
        ImGui.textDisabled("Drag a sample to move it; Ctrl+drag scrubs the input (" + param + ").");
    }

    // ------------------------------------------------------------------------------------------------
    // Debugger

    void renderDebugger() {
        AnimGraphSession s = ctx.session();
        if (s == null) return;
        if (ImGui.button(ctx.playing ? "Pause" : "Play")) ctx.playing = !ctx.playing;
        ImGui.sameLine();
        if (ImGui.button("Step")) {
            ctx.playing = false;
            ctx.stepOnce();
        }
        ImGui.sameLine();
        if (ImGui.button("Restart")) s.restart();
        ImGui.sameLine();
        ImGui.setNextItemWidth(90);
        float[] sp = {ctx.speed};
        if (ImGui.sliderFloat("speed", sp, 0.05f, 2f, "%.2fx")) ctx.speed = sp[0];
        ObjectNode d = ctx.debug();
        if (d == null) return;
        if (d.has("problem")) {
            ImGui.textColored(0.95f, 0.8f, 0.4f, 1f, "Preview: " + d.path("problem").asText());
            return;
        }
        ImGui.text(String.format(Locale.ROOT, "t %.2fs", d.path("time").asDouble()));
        if (ImGui.beginTable("##debug", 5, ImGuiTableFlags.RowBg | ImGuiTableFlags.BordersInnerV | ImGuiTableFlags.Resizable
                | ImGuiTableFlags.ScrollY, 0, Math.max(120, ImGui.getContentRegionAvailY() - 90))) {
            ImGui.tableSetupColumn("Node");
            ImGui.tableSetupColumn("Influence");
            ImGui.tableSetupColumn("Time / progress");
            ImGui.tableSetupColumn("State / blend");
            ImGui.tableSetupColumn("Input weights");
            ImGui.tableHeadersRow();
            for (JsonNode n : d.path("nodes")) {
                boolean active = n.path("active").asBoolean();
                ImGui.tableNextRow();
                ImGui.tableNextColumn();
                if (active) ImGui.text(n.path("id").asText());
                else ImGui.textDisabled(n.path("id").asText());
                ImGui.tableNextColumn();
                float inf = (float) n.path("influence").asDouble();
                ImGui.progressBar(inf, -1, 0, String.format(Locale.ROOT, "%.3f", inf));
                ImGui.tableNextColumn();
                ImGui.text(n.has("time") ? String.format(Locale.ROOT, "%.2f / %.2f", n.path("time").asDouble(), n.path("progress").asDouble())
                        : String.format(Locale.ROOT, "%.2f", n.path("progress").asDouble()));
                ImGui.tableNextColumn();
                if (n.has("state")) ImGui.text(n.path("state").asText() + String.format(Locale.ROOT, " %.2f", n.path("blend").asDouble()));
                ImGui.tableNextColumn();
                if (n.has("weights")) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode w : n.path("weights")) sb.append(String.format(Locale.ROOT, "%.2f ", w.asDouble()));
                    ImGui.text(sb.toString().trim());
                }
            }
            ImGui.endTable();
        }
        StringBuilder nt = new StringBuilder();
        for (JsonNode x : d.path("notifies")) nt.append(x.asText()).append(' ');
        ImGui.text("Notifies: " + (nt.isEmpty() ? "-" : nt.toString().trim()));
        StringBuilder cv = new StringBuilder();
        d.path("curves").fields().forEachRemaining(e -> cv.append(e.getKey()).append(String.format(Locale.ROOT, " %.3f  ", e.getValue().asDouble())));
        ImGui.text("Curves: " + (cv.isEmpty() ? "-" : cv.toString().trim()));
    }

    // ------------------------------------------------------------------------------------------------
    // Problems

    void renderProblems() {
        JsonNode probs = ctx.view().path("problems");
        int errors = 0;
        for (JsonNode p : probs) if (p.path("severity").asText().equals("error")) errors++;
        if (errors == 0) ImGui.textColored(0.35f, 0.85f, 0.45f, 1f, "No errors: the graph compiles");
        else ImGui.textColored(0.95f, 0.45f, 0.4f, 1f, errors + " error(s)");
        int i = 0;
        for (JsonNode p : probs) {
            String sev = p.path("severity").asText();
            float[] c = sev.equals("error") ? new float[] {0.95f, 0.45f, 0.4f} : sev.equals("warning") ? new float[] {0.95f, 0.8f, 0.4f}
                    : new float[] {0.7f, 0.72f, 0.78f};
            String node = p.path("node").asText("");
            String text = (node.isEmpty() ? "" : node + ": ") + (p.path("subject").asText("").isEmpty() ? "" : p.path("subject").asText() + ": ")
                    + p.path("message").asText();
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, c[0], c[1], c[2], 1f);
            if (ImGui.selectable(text + "##pr" + i++) && !node.isEmpty()) ctx.selectNode(node);
            ImGui.popStyleColor();
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Preview setup

    void renderPreviewSetup(AnimGraphSession s, Runnable browseCharacter, Consumer<String> browseClip) {
        if (!ImGui.isAnyItemActive()) characterField.set(s.characterPath() == null ? "" : s.characterPath());
        ImGui.setNextItemWidth(Math.max(120, ImGui.getContentRegionAvailX() - 170));
        ImGui.inputTextWithHint("##character", "character (.omchar) or model", characterField);
        ImGui.sameLine();
        if (ImGui.button("Set")) apply(s, () -> s.setPreview(characterField.get().trim(), null));
        ImGui.sameLine();
        if (ImGui.button("Browse...") && browseCharacter != null) browseCharacter.run();
        Map<String, String> sources = s.slotSources();
        var bound = s.boundSlots();
        for (String slot : s.slotNames()) {
            ImGui.pushID("slot" + slot);
            String src = sources.getOrDefault(slot, "");
            ImGui.setNextItemWidth(160);
            if (ImGui.beginCombo(slot, src.isEmpty() ? "(no clip)" : src)) {
                if (ImGui.selectable("(no clip)", src.isEmpty())) apply(s, () -> s.setPreview(null, singleton(slot, null)));
                for (String c : s.embeddedClipNames()) {
                    String v = "model:" + c;
                    if (ImGui.selectable(c, v.equals(src))) apply(s, () -> s.setPreview(null, singleton(slot, v)));
                }
                ImGui.endCombo();
            }
            ImGui.sameLine();
            if (ImGui.smallButton("file...") && browseClip != null) browseClip.accept(slot);
            Integer r = bound.get(slot);
            if (r != null && r > 0) {
                ImGui.sameLine();
                ImGui.textColored(0.95f, 0.8f, 0.4f, 1f, r + " track(s) dropped");
            }
            ImGui.popID();
        }
    }

    private void apply(AnimGraphSession s, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            ctx.error = e.getMessage();
        }
    }

    private static Map<String, String> singleton(String k, String v) {
        Map<String, String> m = new java.util.HashMap<>();
        m.put(k, v);
        return m;
    }
}
