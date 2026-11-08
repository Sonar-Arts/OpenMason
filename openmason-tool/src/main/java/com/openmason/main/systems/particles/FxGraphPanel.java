package com.openmason.main.systems.particles;

import com.openmason.engine.rendering.particles.graph.FxAttribute;
import com.openmason.engine.rendering.particles.graph.FxOp;
import com.openmason.engine.rendering.particles.graph.FxStageKind;
import com.openmason.main.systems.particles.fx.FxEditorDocument;
import com.openmason.main.systems.particles.fx.FxGraphDraft;
import com.openmason.main.systems.uiPreview.graph.GraphCanvasView;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiMouseButton;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The FX Graph panel (OM #104): a node canvas for the custom module opened from the emitter stack. It edits the
 * document's {@link FxGraphDraft} - drag a node to move it, drag from an output pin to an input pin to wire them,
 * drag from a wired input to pull the wire off, right-click the canvas to add a node, right-click a node to delete
 * it, middle-drag to pan, wheel to zoom. Problems (unconnected inputs, cycles, compile errors such as mismatched
 * types) are drawn on their nodes; the document's module follows the draft whenever it compiles.
 *
 * <p>The pan/zoom/hit-test geometry is the UI graph editor's ({@link GraphCanvasView}, #291).
 */
final class FxGraphPanel {

    private static final String[] PARTICLE_ATTRIBUTES = {"position", "velocity", "age", "lifetime", "normalizedAge",
            "rotation", "spin", "startSize", "endSize"};
    private static final String[] EMITTER_ATTRIBUTES = {"spawnRate", "emitterAge"};
    private static final FxOp[][] PALETTE = {
            {FxOp.READ, FxOp.WRITE, FxOp.CONST, FxOp.DT, FxOp.WIND, FxOp.RANDOM},
            {FxOp.ADD, FxOp.SUB, FxOp.MUL, FxOp.DIV, FxOp.MIN, FxOp.MAX, FxOp.NEG, FxOp.ABS, FxOp.SQRT, FxOp.FLOOR},
            {FxOp.SIN, FxOp.COS, FxOp.EXP, FxOp.LERP, FxOp.CLAMP, FxOp.CURVE},
            {FxOp.VEC3, FxOp.X, FxOp.Y, FxOp.Z, FxOp.DOT, FxOp.CROSS, FxOp.LENGTH, FxOp.NORMALIZE},
            {FxOp.NOISE, FxOp.CURL}};
    private static final String[] PALETTE_GROUPS = {"Sources and sinks", "Math", "Functions", "Vectors", "Noise"};
    private static final double NODE_WIDTH = 150;
    private static final double MIN_FIT_ZOOM = 0.7;

    private final FxEditorContext ctx;
    private final GraphCanvasView view = new GraphCanvasView();
    private String selected;
    private String dragging;
    private float dragDx, dragDy;
    /** A wire being pulled from this node's output. */
    private String wireFrom;
    private float popupGx, popupGy;
    private String popupNode;
    private boolean framed;
    /** The user panned, zoomed or moved a node: stop re-framing when the canvas resizes. */
    private boolean navigated;
    private float lastW, lastH;
    private FxEditorDocument.ModuleRef framedFor;
    private final ImString idBuffer = new ImString(64);
    private String idBufferFor;

    FxGraphPanel(FxEditorContext ctx) {
        this.ctx = ctx;
    }

    /** Opens a module (from the stack's "Edit graph"). */
    void open(FxEditorDocument.ModuleRef ref) {
        ctx.apply(() -> ctx.doc().openGraph(ref));
        selected = null;
        framed = false;
        navigated = false;
    }

    void render() {
        FxEditorDocument doc = ctx.doc();
        FxGraphDraft draft = doc.draft();
        if (draft == null) {
            ImGui.textDisabled("No graph open. In the Emitter Stack, add a custom module (+ Add module) or press");
            ImGui.textDisabled("'Edit graph' on one. Built-in modules have fields instead of a graph.");
            return;
        }
        FxEditorDocument.ModuleRef ref = doc.draftRef();
        if (!ref.equals(framedFor)) {
            framed = false;
            navigated = false;
            framedFor = ref;
        }
        ImGui.text("'" + draft.name() + "' in " + ref.effect() + " / " + ParticleEditorWidgets.pretty(ref.stage()));
        ImGui.sameLine();
        if (ImGui.smallButton("Frame")) {
            framed = false;
            navigated = false;
        }
        ImGui.sameLine();
        if (ImGui.smallButton("Close")) {
            ctx.apply(() -> doc.openGraph(null));
            return;
        }
        Map<String, String> problems = doc.draftProblems();
        if (problems.isEmpty()) {
            ImGui.sameLine();
            ImGui.textColored(0.45f, 0.85f, 0.5f, 1f, "compiles - live in the preview");
        } else {
            String general = problems.get("");
            ImGui.textColored(0.95f, 0.45f, 0.4f, 1f, "Not applied yet: " + (general != null ? general
                    : problems.size() + " node(s) need attention (red)"));
        }

        float inspectorHeight = selected != null && draft.node(selected) != null ? 120f : 0f;
        float w = ImGui.getContentRegionAvailX();
        float h = Math.max(120f, ImGui.getContentRegionAvailY() - inspectorHeight);
        renderCanvas(draft, problems, w, h);
        if (inspectorHeight > 0) {
            renderNodeInspector(draft.node(selected), ref);
        }
    }

    private List<GraphCanvasView.NodeBox> boxes(FxGraphDraft draft) {
        List<GraphCanvasView.NodeBox> out = new ArrayList<>();
        for (FxGraphDraft.DraftNode n : draft.nodes()) {
            out.add(GraphCanvasView.NodeBox.of(n.id(), n.x(), n.y(), NODE_WIDTH, n.inputs().size(),
                    n.op() == FxOp.WRITE ? 0 : 1));
        }
        return out;
    }

    private void renderCanvas(FxGraphDraft draft, Map<String, String> problems, float w, float h) {
        float ox = ImGui.getCursorScreenPosX(), oy = ImGui.getCursorScreenPosY();
        List<GraphCanvasView.NodeBox> boxes = boxes(draft);
        // Docked windows settle their size over the first frames: keep framing until the user takes over.
        if (!navigated && (Math.abs(w - lastW) > 1f || Math.abs(h - lastH) > 1f)) {
            framed = false;
        }
        lastW = w;
        lastH = h;
        if (!framed && !boxes.isEmpty()) {
            view.frameBoxes(boxes, w, h);
            view.setZoom(Math.min(view.zoom(), 1.0));
            if (view.zoom() < MIN_FIT_ZOOM) {
                // Too big to fit readably: keep text legible and show the top-left (pan for the rest).
                double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
                for (GraphCanvasView.NodeBox b : boxes) {
                    minX = Math.min(minX, b.x());
                    minY = Math.min(minY, b.y());
                }
                view.setZoom(MIN_FIT_ZOOM);
                view.pan(24 - view.toScreenX(minX), 24 - view.toScreenY(minY));
            }
            framed = true;
        }
        ImGui.invisibleButton("##fxcanvas", w, h, ImGuiMouseButton.Left | ImGuiMouseButton.Right
                | ImGuiMouseButton.Middle);
        boolean hovered = ImGui.isItemHovered();
        boolean active = ImGui.isItemActive();
        float mx = ImGui.getMousePosX() - ox, my = ImGui.getMousePosY() - oy;

        ImDrawList dl = ImGui.getWindowDrawList();
        dl.pushClipRect(ox, oy, ox + w, oy + h, true);
        dl.addRectFilled(ox, oy, ox + w, oy + h, ImGui.getColorU32(0.09f, 0.1f, 0.13f, 1f));
        float grid = (float) (40 * view.zoom());
        int gridCol = ImGui.getColorU32(0.2f, 0.22f, 0.27f, 0.6f);
        for (float x = (float) (view.panX() % grid); x < w; x += grid) dl.addLine(ox + x, oy, ox + x, oy + h, gridCol);
        for (float y = (float) (view.panY() % grid); y < h; y += grid) dl.addLine(ox, oy + y, ox + w, oy + y, gridCol);

        Map<String, GraphCanvasView.NodeBox> byId = new LinkedHashMap<>();
        boxes.forEach(b -> byId.put(b.id(), b));
        int wire = ImGui.getColorU32(0.75f, 0.78f, 0.85f, 0.9f);
        for (FxGraphDraft.DraftNode n : draft.nodes()) {
            for (int i = 0; i < n.inputs().size(); i++) {
                GraphCanvasView.NodeBox from = byId.get(n.inputs().get(i));
                if (from == null) {
                    continue;
                }
                double[] a = from.anchor(true, 0);
                double[] b = byId.get(n.id()).anchor(false, i);
                drawCurve(dl, ox, oy, a[0], a[1], b[0], b[1], wire);
            }
        }

        // ---- interaction ----
        GraphCanvasView.PinHit pin = hovered ? view.hitPin(boxes, mx, my, 9) : null;
        GraphCanvasView.NodeBox overNode = hovered ? view.hitNode(boxes, mx, my) : null;
        if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            if (pin != null && pin.output()) {
                wireFrom = pin.node();
            } else if (pin != null) {
                FxGraphDraft.DraftNode target = draft.node(pin.node());
                String source = target.inputs().get(pin.index());
                if (!source.isEmpty()) {
                    // Pull the wire off its input and keep dragging it from its source.
                    int idx = pin.index();
                    ctx.apply(() -> ctx.doc().editGraph(null, g -> g.disconnect(target.id(), idx)));
                    wireFrom = source;
                }
            } else if (overNode != null) {
                selected = overNode.id();
                dragging = overNode.id();
                navigated = true;
                dragDx = (float) (view.toGraphX(mx) - overNode.x());
                dragDy = (float) (view.toGraphY(my) - overNode.y());
            } else {
                selected = null;
            }
        }
        if (dragging != null && active && ImGui.isMouseDragging(ImGuiMouseButton.Left, 1f)) {
            float gx = (float) view.toGraphX(mx) - dragDx, gy = (float) view.toGraphY(my) - dragDy;
            String id = dragging;
            ctx.apply(() -> ctx.doc().editGraph("move:" + id, g -> g.move(id, Math.round(gx), Math.round(gy))));
        }
        if (wireFrom != null) {
            GraphCanvasView.NodeBox fromBox = byId.get(wireFrom);
            if (fromBox != null) {
                double[] a = fromBox.anchor(true, 0);
                drawCurve(dl, ox, oy, a[0], a[1], view.toGraphX(mx), view.toGraphY(my),
                        ImGui.getColorU32(1f, 0.8f, 0.3f, 1f));
            }
            if (ImGui.isMouseReleased(ImGuiMouseButton.Left)) {
                if (pin != null && !pin.output()) {
                    String from = wireFrom, to = pin.node();
                    int idx = pin.index();
                    ctx.apply(() -> ctx.doc().editGraph(null, g -> g.connect(from, to, idx)));
                }
                wireFrom = null;
            }
        }
        if (ImGui.isMouseReleased(ImGuiMouseButton.Left)) {
            dragging = null;
        }
        if (active && ImGui.isMouseDragging(ImGuiMouseButton.Middle, 0f)) {
            view.pan(ImGui.getIO().getMouseDeltaX(), ImGui.getIO().getMouseDeltaY());
            navigated = true;
        }
        if (hovered && ImGui.getIO().getMouseWheel() != 0f) {
            view.zoomAbout(mx, my, Math.pow(1.1, ImGui.getIO().getMouseWheel()));
            navigated = true;
        }
        if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
            popupNode = overNode == null ? null : overNode.id();
            popupGx = (float) view.toGraphX(mx);
            popupGy = (float) view.toGraphY(my);
            ImGui.openPopup("##fxgraphmenu");
        }
        if (hovered && selected != null && ImGui.isKeyPressed(ImGuiKey.Delete)) {
            String id = selected;
            selected = null;
            ctx.apply(() -> ctx.doc().editGraph(null, g -> g.removeNode(id)));
        }

        // ---- nodes ----
        FxGraphDraft current = ctx.doc().draft();
        if (current != null) {
            for (FxGraphDraft.DraftNode n : current.nodes()) {
                drawNode(dl, ox, oy, n, GraphCanvasView.NodeBox.of(n.id(), n.x(), n.y(), NODE_WIDTH,
                        n.inputs().size(), n.op() == FxOp.WRITE ? 0 : 1), problems.get(n.id()), pin);
            }
        }
        dl.popClipRect();
        if (overNode != null && problems.containsKey(overNode.id())) {
            ImGui.setTooltip(overNode.id() + ": " + problems.get(overNode.id()));
        }
        renderPopup();
    }

    private void drawCurve(ImDrawList dl, float ox, float oy, double ax, double ay, double bx, double by, int col) {
        GraphCanvasView.Bezier c = GraphCanvasView.Bezier.between(ax, ay, bx, by);
        dl.addBezierCubic(ox + (float) view.toScreenX(c.x0()), oy + (float) view.toScreenY(c.y0()),
                ox + (float) view.toScreenX(c.x1()), oy + (float) view.toScreenY(c.y1()),
                ox + (float) view.toScreenX(c.x2()), oy + (float) view.toScreenY(c.y2()),
                ox + (float) view.toScreenX(c.x3()), oy + (float) view.toScreenY(c.y3()), col, 2f);
    }

    private void drawNode(ImDrawList dl, float ox, float oy, FxGraphDraft.DraftNode n, GraphCanvasView.NodeBox box,
                          String problem, GraphCanvasView.PinHit pin) {
        float x0 = ox + (float) view.toScreenX(box.x()), y0 = oy + (float) view.toScreenY(box.y());
        float x1 = ox + (float) view.toScreenX(box.x() + box.width()), y1 = oy + (float) view.toScreenY(box.y() + box.height());
        float head = (float) (GraphCanvasView.HEADER * view.zoom());
        int body = ImGui.getColorU32(0.17f, 0.18f, 0.23f, 0.96f);
        int header = headerColor(n.op());
        dl.addRectFilled(x0, y0, x1, y1, body, 5f);
        dl.addRectFilled(x0, y0, x1, y0 + head, header, 5f);
        int border = problem != null ? ImGui.getColorU32(0.95f, 0.35f, 0.3f, 1f)
                : n.id().equals(selected) ? ImGui.getColorU32(1f, 0.85f, 0.35f, 1f)
                : ImGui.getColorU32(0.3f, 0.32f, 0.4f, 1f);
        dl.addRect(x0, y0, x1, y1, border, 5f, 0, problem != null || n.id().equals(selected) ? 2f : 1f);
        int text = ImGui.getColorU32(0.95f, 0.95f, 0.97f, 1f);
        int dim = ImGui.getColorU32(0.7f, 0.72f, 0.78f, 1f);
        text(dl, x0 + 6, y0 + 5, text, title(n));
        String[] labels = inputLabels(n.op());
        for (int i = 0; i < n.inputs().size(); i++) {
            double[] a = box.anchor(false, i);
            float px = ox + (float) view.toScreenX(a[0]), py = oy + (float) view.toScreenY(a[1]);
            boolean wired = !n.inputs().get(i).isEmpty();
            boolean hot = pin != null && !pin.output() && pin.node().equals(n.id()) && pin.index() == i;
            int col = hot ? ImGui.getColorU32(1f, 0.85f, 0.35f, 1f)
                    : wired ? ImGui.getColorU32(0.6f, 0.8f, 1f, 1f) : ImGui.getColorU32(0.95f, 0.45f, 0.4f, 1f);
            dl.addCircleFilled(px, py, (float) (GraphCanvasView.PIN_RADIUS * view.zoom()), col);
            text(dl, px + 9, py - 7 * z(), dim, i < labels.length ? labels[i] : "in");
        }
        if (n.op() != FxOp.WRITE) {
            double[] a = box.anchor(true, 0);
            float px = ox + (float) view.toScreenX(a[0]), py = oy + (float) view.toScreenY(a[1]);
            boolean hot = pin != null && pin.output() && pin.node().equals(n.id());
            dl.addCircleFilled(px, py, (float) (GraphCanvasView.PIN_RADIUS * view.zoom()),
                    hot ? ImGui.getColorU32(1f, 0.85f, 0.35f, 1f) : ImGui.getColorU32(0.6f, 0.8f, 1f, 1f));
            String detail = detail(n);
            if (!detail.isEmpty()) {
                text(dl, px - 9 - ImGui.calcTextSizeX(detail) * z(), py - 7 * z(), dim, detail);
            }
        } else if (n.attribute() != null) {
            text(dl, x0 + 6, y0 + head + (float) (26 * view.zoom()), dim, "-> " + n.attribute());
        }
    }

    private float z() {
        return (float) Math.min(1.0, view.zoom());
    }

    /** Text scaled with the canvas zoom (hidden when too small to read). */
    private void text(ImDrawList dl, float x, float y, int col, String s) {
        if (view.zoom() < 0.45) {
            return;
        }
        dl.addText(ImGui.getFont(), Math.max(1, Math.round(ImGui.getFontSize() * z())), x, y, col, s);
    }

    private static String title(FxGraphDraft.DraftNode n) {
        return n.op().id() + "  " + n.id();
    }

    private static String detail(FxGraphDraft.DraftNode n) {
        return switch (n.op()) {
            case READ -> n.attribute() == null ? "?" : n.attribute();
            case CONST -> n.values().size() == 1 ? fmt(n.values().get(0))
                    : "(" + fmt(n.values().get(0)) + ", " + fmt(n.values().get(1)) + ", " + fmt(n.values().get(2)) + ")";
            case CURVE -> (n.values().size() / 2) + " keys";
            default -> "";
        };
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, v == Math.rint(v) ? "%.0f" : "%.3g", v);
    }

    private static int headerColor(FxOp op) {
        return switch (op) {
            case READ, CONST, DT, WIND, RANDOM -> ImGui.getColorU32(0.2f, 0.45f, 0.35f, 1f);
            case WRITE -> ImGui.getColorU32(0.55f, 0.3f, 0.25f, 1f);
            case NOISE, CURL -> ImGui.getColorU32(0.45f, 0.3f, 0.55f, 1f);
            default -> ImGui.getColorU32(0.25f, 0.33f, 0.5f, 1f);
        };
    }

    static String[] inputLabels(FxOp op) {
        return switch (op) {
            case LERP -> new String[]{"a", "b", "t"};
            case CLAMP -> new String[]{"x", "min", "max"};
            case VEC3 -> new String[]{"x", "y", "z"};
            case WRITE -> new String[]{"value"};
            case CURVE -> new String[]{"t"};
            case NOISE, CURL -> new String[]{"p"};
            default -> op.arity == 2 ? new String[]{"a", "b"} : new String[]{"in"};
        };
    }

    private void renderPopup() {
        if (!ImGui.beginPopup("##fxgraphmenu")) {
            return;
        }
        if (popupNode != null) {
            ImGui.textDisabled(popupNode);
            if (ImGui.menuItem("Delete node")) {
                String id = popupNode;
                if (id.equals(selected)) {
                    selected = null;
                }
                ctx.apply(() -> ctx.doc().editGraph(null, g -> g.removeNode(id)));
            }
        } else {
            ImGui.textDisabled("Add node");
            for (int g = 0; g < PALETTE.length; g++) {
                if (ImGui.beginMenu(PALETTE_GROUPS[g])) {
                    for (FxOp op : PALETTE[g]) {
                        if (ImGui.menuItem(op.id())) {
                            float x = popupGx, y = popupGy;
                            ctx.apply(() -> ctx.doc().editGraph(null, d -> d.addNode(op, Math.round(x), Math.round(y))));
                            FxGraphDraft after = ctx.doc().draft();
                            if (after != null && !after.nodes().isEmpty()) {
                                selected = after.nodes().get(after.nodes().size() - 1).id();
                            }
                        }
                    }
                    ImGui.endMenu();
                }
            }
        }
        ImGui.endPopup();
    }

    private void renderNodeInspector(FxGraphDraft.DraftNode n, FxEditorDocument.ModuleRef ref) {
        ImGui.separator();
        ImGui.pushID("node_" + n.id());
        if (!n.id().equals(idBufferFor)) {
            idBuffer.set(n.id());
            idBufferFor = n.id();
        }
        ImGui.setNextItemWidth(140);
        ImGui.inputText("id", idBuffer);
        if (ImGui.isItemDeactivatedAfterEdit() && !idBuffer.get().strip().equals(n.id())) {
            String from = n.id(), to = idBuffer.get().strip();
            if (ctx.apply(() -> ctx.doc().editGraph(null, g -> g.rename(from, to))) != null) {
                selected = to;
            }
        }
        ImGui.sameLine();
        ImGui.setNextItemWidth(120);
        if (ImGui.beginCombo("op", n.op().id())) {
            for (FxOp op : FxOp.values()) {
                if (!op.intrinsic() && ImGui.selectable(op.id(), op == n.op())) {
                    ctx.apply(() -> ctx.doc().editGraph(null, g -> g.setOp(n.id(), op)));
                }
            }
            ImGui.endCombo();
        }
        if (n.op() == FxOp.READ || n.op() == FxOp.WRITE) {
            ImGui.sameLine();
            ImGui.setNextItemWidth(150);
            if (ImGui.beginCombo("attribute", n.attribute() == null ? "" : n.attribute())) {
                for (String a : attributeChoices(ref)) {
                    if (ImGui.selectable(a, a.equals(n.attribute()))) {
                        ctx.apply(() -> ctx.doc().editGraph(null, g -> g.setAttribute(n.id(), a)));
                    }
                }
                ImGui.endCombo();
            }
        }
        if (n.op() == FxOp.CONST) {
            boolean vector = n.values().size() == 3;
            float[] v = new float[3];
            for (int i = 0; i < n.values().size(); i++) v[i] = n.values().get(i);
            ImGui.setNextItemWidth(240);
            boolean changed = vector ? ImGui.dragFloat3("value", v, 0.01f) : ImGui.dragFloat("value", v, 0.01f);
            ImGui.sameLine();
            if (ImGui.checkbox("vector", vector)) {
                List<Float> next = vector ? List.of(v[0]) : List.of(v[0], v[0], v[0]);
                ctx.apply(() -> ctx.doc().editGraph(null, g -> g.setValues(n.id(), next)));
            } else if (changed) {
                List<Float> next = vector ? List.of(v[0], v[1], v[2]) : List.of(v[0]);
                ctx.apply(() -> ctx.doc().editGraph("values:" + n.id(), g -> g.setValues(n.id(), next)));
            }
        }
        if (n.op() == FxOp.CURVE) {
            renderCurveKeys(n);
        }
        ImGui.popID();
    }

    private void renderCurveKeys(FxGraphDraft.DraftNode n) {
        List<Float> values = new ArrayList<>(n.values());
        boolean changed = false;
        for (int k = 0; k + 1 < values.size(); k += 2) {
            ImGui.pushID(k);
            float[] key = {values.get(k), values.get(k + 1)};
            ImGui.setNextItemWidth(180);
            if (ImGui.dragFloat2("t, value", key, 0.01f)) {
                values.set(k, key[0]);
                values.set(k + 1, key[1]);
                changed = true;
            }
            ImGui.sameLine();
            if (values.size() > 2 && ImGui.smallButton("x")) {
                values.remove(k + 1);
                values.remove(k);
                changed = true;
                ImGui.popID();
                break;
            }
            ImGui.popID();
        }
        if (ImGui.smallButton("+ key")) {
            float t = values.isEmpty() ? 0f : values.get(values.size() - 2) + 0.25f;
            values.add(t);
            values.add(values.isEmpty() ? 0f : values.get(values.size() - 2));
            changed = true;
        }
        if (changed) {
            // Keys must ascend in t: sort pairs.
            List<float[]> pairs = new ArrayList<>();
            for (int k = 0; k + 1 < values.size(); k += 2) pairs.add(new float[]{values.get(k), values.get(k + 1)});
            pairs.sort((a, b) -> Float.compare(a[0], b[0]));
            List<Float> sorted = new ArrayList<>();
            for (float[] p : pairs) {
                sorted.add(p[0]);
                sorted.add(p[1]);
            }
            ctx.apply(() -> ctx.doc().editGraph("values:" + n.id(), g -> g.setValues(n.id(), sorted)));
        }
    }

    private List<String> attributeChoices(FxEditorDocument.ModuleRef ref) {
        List<String> out = new ArrayList<>();
        if (ref.stage() == FxStageKind.EMITTER_UPDATE) {
            out.addAll(List.of(EMITTER_ATTRIBUTES));
            return out;
        }
        out.addAll(List.of(PARTICLE_ATTRIBUTES));
        for (FxAttribute a : ctx.doc().attributes(ref.effect())) {
            out.add(a.name());
        }
        return out;
    }
}
