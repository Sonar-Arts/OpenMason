package com.openmason.main.systems.animgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.uiPreview.graph.GraphCanvasView;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiMouseButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Animation Graph tab's two canvases (OpenMason #164), ImGui presentation over the native document's view
 * ({@code cg_doc_view}): the node graph (blend trees, layers, montage slots, machines as nodes; the output node on
 * the right) and the state-machine canvas of the selected machine. Every gesture becomes a document operation:
 * dragging a node / state = {@code position} (one undo step per drag, merge key), dragging from a node's output
 * onto an input = {@code link}, Delete = {@code node remove} / {@code unlink}, the context menu adds nodes, states
 * and transitions. Pan / zoom math is the UI graph editor's {@link GraphCanvasView}. The debugger's influence tints
 * the nodes while the preview runs.
 */
final class AnimGraphCanvas {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double NODE_W = 190;
    private static final double HEADER = 26;
    private static final double ROW = 22;
    private static final double STATE_W = 130;
    private static final double STATE_H = 40;
    static final String[] NODE_TYPES = {"clip", "blend1d", "blend2d", "stateMachine", "layer", "slot"};

    private final AnimGraphEditorContext ctx;
    private final GraphCanvasView graphView = new GraphCanvasView();
    private final GraphCanvasView machineView = new GraphCanvasView();
    private boolean graphFramed;
    private String machineFramed;

    // Interaction state.
    private String dragNode;            // node (graph) or state (machine) being dragged
    private double dragDx;
    private double dragDy;
    private String linkFrom;            // output pin drag (graph) or transition drag source state (machine)
    private float addX;
    private float addY;
    private float rightX;
    private float rightY;

    AnimGraphCanvas(AnimGraphEditorContext ctx) {
        this.ctx = ctx;
    }

    void resetFraming() {
        graphFramed = false;
        machineFramed = null;
    }

    // ------------------------------------------------------------------------------------------------
    // Node graph

    private record Box(String id, double x, double y, double h, JsonNode node) {
        double inputY(int i) {
            return y + HEADER + ROW * (i + 0.5);
        }
    }

    private List<Box> boxes(JsonNode view) {
        List<Box> out = new ArrayList<>();
        for (JsonNode n : view.path("nodes")) {
            int rows = n.path("inputs").size() + (hasAppend(n) ? 1 : 0);
            out.add(new Box(n.path("id").asText(), n.path("x").asDouble(), n.path("y").asDouble(),
                    HEADER + ROW * Math.max(1, rows) + 6, n));
        }
        return out;
    }

    private static boolean hasAppend(JsonNode n) {
        String t = n.path("type").asText();
        return t.equals("blend1d") || t.equals("blend2d") || t.equals("stateMachine") || t.equals("layer");
    }

    void renderGraph(JsonNode view, float width, float height) {
        float ox = ImGui.getCursorScreenPosX(), oy = ImGui.getCursorScreenPosY();
        ImGui.invisibleButton("##graphCanvas", Math.max(width, 10), Math.max(height, 10));
        boolean hovered = ImGui.isItemHovered();
        boolean active = ImGui.isItemActive();
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.pushClipRect(ox, oy, ox + width, oy + height, true);
        dl.addRectFilled(ox, oy, ox + width, oy + height, col(0.09f, 0.1f, 0.12f, 1f));
        List<Box> boxes = boxes(view);
        if (!graphFramed && !boxes.isEmpty()) {
            double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
            for (Box b : boxes) {
                x0 = Math.min(x0, b.x);
                y0 = Math.min(y0, b.y);
                x1 = Math.max(x1, b.x + NODE_W);
                y1 = Math.max(y1, b.y + b.h);
            }
            graphView.frame(x0, y0, x1, y1, width, height, 40);
            if (graphView.zoom() > 1.2) graphView.setZoom(1.2);
            graphFramed = true;
        }
        drawGrid(dl, graphView, ox, oy, width, height);
        Map<String, Box> byId = new HashMap<>();
        for (Box b : boxes) byId.put(b.id, b);
        Map<String, Double> influence = ctx.influenceById();
        String root = view.path("root").asText("");
        float mx = ImGui.getIO().getMousePosX(), my = ImGui.getIO().getMousePosY();
        if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
            rightX = mx;
            rightY = my;
        }
        double z = graphView.zoom();

        // Links: child's output (right edge) -> parent's input row.
        String hoveredLink = null;
        for (Box b : boxes) {
            int i = 0;
            for (JsonNode in : b.node.path("inputs")) {
                Box child = byId.get(in.path("child").asText());
                if (child != null) {
                    float ax = sx(ox, child.x + NODE_W), ay = sy(oy, child.y + HEADER / 2);
                    float bx = sx(ox, b.x), by = sy(oy, b.inputY(i));
                    double inf = influence.getOrDefault(child.id, 0.0);
                    boolean sel = (b.id + "|" + in.path("at").asText()).equals(ctx.selectedLink);
                    int c = sel ? col(0.3f, 0.75f, 1f, 1f) : inf > 0 ? col(0.4f, 0.9f, 0.5f, (float) (0.5 + 0.5 * inf))
                            : col(0.55f, 0.58f, 0.65f, 0.9f);
                    float dx = Math.max(40f, Math.abs(bx - ax) * 0.5f);
                    dl.addBezierCubic(ax, ay, ax + dx, ay, bx - dx, by, bx, by, c, sel ? 3f : 2f);
                    if (hovered && hoveredLink == null && GraphCanvasView.Bezier.between(ax, ay, bx, by).distanceTo(mx, my) < 5) {
                        hoveredLink = b.id + "|" + in.path("at").asText();
                    }
                }
                i++;
            }
        }
        // Nodes.
        String hoveredNode = null;
        String hoveredInput = null;
        String hoveredOutput = null;
        for (Box b : boxes) {
            float x0 = sx(ox, b.x), y0 = sy(oy, b.y), x1 = sx(ox, b.x + NODE_W), y1 = sy(oy, b.y + b.h);
            boolean sel = b.id.equals(ctx.selectedNode);
            double inf = influence.getOrDefault(b.id, 0.0);
            int errors = b.node.path("errors").asInt();
            float[] head = typeColor(b.node.path("type").asText());
            dl.addRectFilled(x0, y0, x1, y1, col(0.16f, 0.17f, 0.2f, 0.97f), 6f);
            dl.addRectFilled(x0, y0, x1, (float) (y0 + HEADER * z), col(head[0], head[1], head[2], 1f), 6f);
            if (inf > 0) dl.addRect(x0 - 2, y0 - 2, x1 + 2, y1 + 2, col(0.4f, 0.95f, 0.5f, (float) Math.min(1, 0.3 + inf)), 7f, 0, 2.5f);
            dl.addRect(x0, y0, x1, y1, sel ? col(0.3f, 0.75f, 1f, 1f) : errors > 0 ? col(0.95f, 0.35f, 0.3f, 1f)
                    : col(0.3f, 0.32f, 0.38f, 1f), 6f, 0, sel || errors > 0 ? 2f : 1f);
            if (z > 0.45) {
                String title = b.id + (b.id.equals(root) ? "  [output]" : "");
                dl.addText(x0 + 8, y0 + 5, col(1f, 1f, 1f, 1f), title);
                int i = 0;
                for (JsonNode in : b.node.path("inputs")) {
                    float yy = sy(oy, b.inputY(i));
                    dl.addText(x0 + 14, yy - 7, col(0.8f, 0.82f, 0.88f, 1f), in.path("label").asText());
                    i++;
                }
                if (hasAppend(b.node)) {
                    dl.addText(x0 + 14, sy(oy, b.inputY(i)) - 7, col(0.5f, 0.52f, 0.58f, 1f), "+ input");
                }
            }
            // Pins.
            int rows = b.node.path("inputs").size() + (hasAppend(b.node) ? 1 : 0);
            for (int i = 0; i < rows; i++) {
                float px = x0, py = sy(oy, b.inputY(i));
                boolean pinHover = hovered && dist(mx, my, px, py) < 8;
                dl.addCircleFilled(px, py, pinHover ? 6f : 4.5f, col(0.85f, 0.85f, 0.9f, 1f));
                if (pinHover) {
                    hoveredInput = b.id + "|" + (i < b.node.path("inputs").size()
                            ? b.node.path("inputs").get(i).path("at").asText() : "append");
                }
            }
            float opx = x1, opy = sy(oy, b.y + HEADER / 2);
            boolean outHover = hovered && dist(mx, my, opx, opy) < 9;
            dl.addCircleFilled(opx, opy, outHover ? 7f : 5.5f, col(0.95f, 0.8f, 0.35f, 1f));
            if (outHover) hoveredOutput = b.id;
            if (hovered && mx >= x0 && mx <= x1 && my >= y0 && my <= y1) hoveredNode = b.id;
        }

        // Interaction.
        if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            if (hoveredOutput != null) {
                linkFrom = hoveredOutput;
            } else if (hoveredNode != null) {
                ctx.selectNode(hoveredNode);
                Box b = byId.get(hoveredNode);
                dragNode = hoveredNode;
                dragDx = graphView.toGraphX(mx - ox) - b.x;
                dragDy = graphView.toGraphY(my - oy) - b.y;
            } else if (hoveredLink != null) {
                ctx.selectedLink = hoveredLink;
                ctx.selectedNode = null;
            } else {
                ctx.selectNode(null);
            }
        }
        if (dragNode != null && active && ImGui.isMouseDragging(ImGuiMouseButton.Left, 2f)) {
            double gx = graphView.toGraphX(mx - ox) - dragDx, gy = graphView.toGraphY(my - oy) - dragDy;
            ctx.apply(String.format(java.util.Locale.ROOT, "[{\"op\":\"position\",\"node\":%s,\"x\":%.1f,\"y\":%.1f}]",
                    quote(dragNode), gx, gy), "move " + dragNode, "move:" + dragNode);
        }
        if (linkFrom != null) {
            Box from = byId.get(linkFrom);
            if (from != null) {
                dl.addLine(sx(ox, from.x + NODE_W), sy(oy, from.y + HEADER / 2), mx, my, col(0.95f, 0.8f, 0.35f, 1f), 2f);
            }
            if (ImGui.isMouseReleased(ImGuiMouseButton.Left)) {
                if (hoveredInput != null) {
                    String[] pa = hoveredInput.split("\\|", 2);
                    ctx.apply("[{\"op\":\"link\",\"parent\":" + quote(pa[0]) + ",\"child\":" + quote(linkFrom) + ",\"at\":"
                            + quote(pa[1]) + "}]", "link " + linkFrom, null);
                }
                linkFrom = null;
            }
        }
        if (ImGui.isMouseReleased(ImGuiMouseButton.Left)) {
            dragNode = null;
        }
        if (hovered && (ImGui.isMouseDragging(ImGuiMouseButton.Middle) || ImGui.isMouseDragging(ImGuiMouseButton.Right, 4f))) {
            graphView.pan(ImGui.getIO().getMouseDeltaX(), ImGui.getIO().getMouseDeltaY());
        }
        if (hovered && ImGui.getIO().getMouseWheel() != 0) {
            graphView.zoomAbout(mx - ox, my - oy, Math.pow(1.12, ImGui.getIO().getMouseWheel()));
        }
        if (hovered && hoveredNode != null && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)
                && "stateMachine".equals(byId.get(hoveredNode).node.path("type").asText())) {
            ctx.selectedMachine = hoveredNode;
        }
        if ((hovered || ImGui.isWindowFocused()) && ImGui.isKeyPressed(ImGuiKey.Delete, false) && !ImGui.getIO().getWantTextInput()) {
            if (ctx.selectedNode != null) {
                ctx.apply("[{\"op\":\"node\",\"id\":" + quote(ctx.selectedNode) + ",\"remove\":true}]", "delete " + ctx.selectedNode, null);
                ctx.selectNode(null);
            } else if (ctx.selectedLink != null) {
                String[] pa = ctx.selectedLink.split("\\|", 2);
                ctx.apply("[{\"op\":\"unlink\",\"parent\":" + quote(pa[0]) + ",\"at\":" + quote(pa[1]) + "}]", "unlink", null);
                ctx.selectedLink = null;
            }
        }
        if (hovered && ImGui.isMouseReleased(ImGuiMouseButton.Right) && dist(mx, my, rightX, rightY) < 4) {
            addX = (float) graphView.toGraphX(mx - ox);
            addY = (float) graphView.toGraphY(my - oy);
            ImGui.openPopup("##graphMenu");
        }
        dl.popClipRect();
        if (ImGui.beginPopup("##graphMenu")) {
            ImGui.textDisabled("Add node");
            for (String t : NODE_TYPES) {
                if (ImGui.menuItem(t)) {
                    String id = ctx.freshId(t);
                    ctx.apply(String.format(java.util.Locale.ROOT, "[{\"op\":\"node\",\"id\":%s,\"type\":%s},"
                            + "{\"op\":\"position\",\"node\":%s,\"x\":%.1f,\"y\":%.1f}]", quote(id), quote(t), quote(id), addX, addY),
                            "add " + t, null);
                    ctx.selectNode(id);
                }
            }
            ImGui.separator();
            if (ImGui.menuItem("Auto layout")) {
                ctx.apply("[{\"op\":\"layout\",\"onlyMissing\":false}]", "layout", null);
                graphFramed = false;
            }
            if (ImGui.menuItem("Frame all")) graphFramed = false;
            if (ctx.selectedNode != null && ImGui.menuItem("Make output: " + ctx.selectedNode)) {
                ctx.apply("[{\"op\":\"graph\",\"root\":" + quote(ctx.selectedNode) + "}]", "set output", null);
            }
            ImGui.endPopup();
        }
    }

    // ------------------------------------------------------------------------------------------------
    // State machine

    void renderMachine(JsonNode view, float width, float height) {
        String machine = ctx.selectedMachine;
        JsonNode m = machine == null ? null : view.path("machines").get(machine);
        if (m == null) {
            ImGui.textDisabled("Double-click a state machine node (or select one) to edit its states.");
            return;
        }
        ImGui.text("Machine " + machine + "   entry: " + m.path("entry").asText());
        float ox = ImGui.getCursorScreenPosX(), oy = ImGui.getCursorScreenPosY();
        height -= ImGui.getFrameHeightWithSpacing();
        ImGui.invisibleButton("##machineCanvas", Math.max(width, 10), Math.max(height, 10));
        boolean hovered = ImGui.isItemHovered();
        boolean active = ImGui.isItemActive();
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.pushClipRect(ox, oy, ox + width, oy + height, true);
        dl.addRectFilled(ox, oy, ox + width, oy + height, col(0.09f, 0.1f, 0.12f, 1f));
        if (!machine.equals(machineFramed)) {
            double x0 = -200, y0 = -200, x1 = 200, y1 = 200;
            for (JsonNode s : m.path("states")) {
                x0 = Math.min(x0, s.path("x").asDouble() - STATE_W);
                y0 = Math.min(y0, s.path("y").asDouble() - STATE_H);
                x1 = Math.max(x1, s.path("x").asDouble() + STATE_W);
                y1 = Math.max(y1, s.path("y").asDouble() + STATE_H);
            }
            machineView.frame(x0, y0, x1, y1, width, height, 30);
            if (machineView.zoom() > 1.2) machineView.setZoom(1.2);
            machineFramed = machine;
        }
        drawGrid(dl, machineView, ox, oy, width, height);
        Map<String, double[]> at = new HashMap<>();
        for (JsonNode s : m.path("states")) at.put(s.path("name").asText(), new double[] {s.path("x").asDouble(), s.path("y").asDouble()});
        String current = ctx.debugState(machine);
        float mx = ImGui.getIO().getMousePosX(), my = ImGui.getIO().getMousePosY();
        if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
            rightX = mx;
            rightY = my;
        }
        // Transitions.
        int hoveredTransition = -1;
        for (JsonNode t : m.path("transitions")) {
            double[] a = at.get(t.path("from").asText());
            double[] b = at.get(t.path("to").asText());
            if (b == null) continue;
            if (a == null) a = new double[] {b[0] - 180, b[1] - 120};  // "*" = any state
            float ax = msx(ox, a[0]), ay = msy(oy, a[1]), bx = msx(ox, b[0]), by = msy(oy, b[1]);
            // Offset both directions apart.
            float nx = -(by - ay), ny = bx - ax;
            float len = (float) Math.max(1, Math.sqrt(nx * nx + ny * ny));
            nx = nx / len * 6;
            ny = ny / len * 6;
            ax += nx;
            ay += ny;
            bx += nx;
            by += ny;
            int index = t.path("index").asInt();
            boolean sel = index == ctx.selectedTransition;
            int c = sel ? col(0.3f, 0.75f, 1f, 1f) : col(0.75f, 0.77f, 0.82f, 0.9f);
            dl.addLine(ax, ay, bx, by, c, sel ? 3f : 1.6f);
            float mxp = (ax + bx) / 2, myp = (ay + by) / 2;
            float dx = bx - ax, dy = by - ay;
            float l = (float) Math.max(1, Math.sqrt(dx * dx + dy * dy));
            dx /= l;
            dy /= l;
            dl.addTriangleFilled(mxp + dx * 7, myp + dy * 7, mxp - dx * 5 - dy * 5, myp - dy * 5 + dx * 5,
                    mxp - dx * 5 + dy * 5, myp - dy * 5 - dx * 5, c);
            if (hovered && distToSegment(mx, my, ax, ay, bx, by) < 5) hoveredTransition = index;
            if (machineView.zoom() > 0.6 && !t.path("label").asText().isEmpty()) {
                dl.addText(mxp + 6, myp - 14, col(0.6f, 0.63f, 0.7f, 1f), t.path("label").asText());
            }
        }
        // States.
        String hoveredState = null;
        for (JsonNode s : m.path("states")) {
            String name = s.path("name").asText();
            double[] p = at.get(name);
            float x0 = msx(ox, p[0] - STATE_W / 2), y0 = msy(oy, p[1] - STATE_H / 2);
            float x1 = msx(ox, p[0] + STATE_W / 2), y1 = msy(oy, p[1] + STATE_H / 2);
            boolean entry = name.equals(m.path("entry").asText());
            boolean live = name.equals(current);
            boolean conduit = s.path("conduit").asBoolean();
            int fill = live ? col(0.22f, 0.45f, 0.28f, 1f) : conduit ? col(0.2f, 0.2f, 0.26f, 1f) : col(0.18f, 0.19f, 0.23f, 1f);
            dl.addRectFilled(x0, y0, x1, y1, fill, conduit ? 16f : 6f);
            boolean sel = name.equals(ctx.selectedState);
            dl.addRect(x0, y0, x1, y1, sel ? col(0.3f, 0.75f, 1f, 1f) : entry ? col(0.95f, 0.8f, 0.35f, 1f)
                    : col(0.35f, 0.37f, 0.43f, 1f), conduit ? 16f : 6f, 0, sel || entry ? 2.2f : 1f);
            dl.addText(x0 + 8, y0 + 5, col(1f, 1f, 1f, 1f), name);
            dl.addText(x0 + 8, y0 + 21, col(0.6f, 0.63f, 0.7f, 1f), conduit ? "(conduit)" : s.path("node").asText(""));
            if (hovered && mx >= x0 && mx <= x1 && my >= y0 && my <= y1) hoveredState = name;
        }
        if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            if (hoveredState != null) {
                if (ImGui.getIO().getKeyShift() || ImGui.getIO().getKeyCtrl()) {
                    linkFrom = hoveredState;  // drag a new transition
                } else {
                    ctx.selectedState = hoveredState;
                    ctx.selectedTransition = -1;
                    double[] p = at.get(hoveredState);
                    dragNode = hoveredState;
                    dragDx = machineView.toGraphX(mx - ox) - p[0];
                    dragDy = machineView.toGraphY(my - oy) - p[1];
                }
            } else if (hoveredTransition >= 0) {
                ctx.selectedTransition = hoveredTransition;
                ctx.selectedState = null;
            } else {
                ctx.selectedState = null;
                ctx.selectedTransition = -1;
            }
        }
        if (dragNode != null && linkFrom == null && active && ImGui.isMouseDragging(ImGuiMouseButton.Left, 2f)
                && at.containsKey(dragNode)) {
            double gx = machineView.toGraphX(mx - ox) - dragDx, gy = machineView.toGraphY(my - oy) - dragDy;
            ctx.apply(String.format(java.util.Locale.ROOT, "[{\"op\":\"position\",\"machine\":%s,\"state\":%s,\"x\":%.1f,\"y\":%.1f}]",
                    quote(machine), quote(dragNode), gx, gy), "move " + dragNode, "move:" + machine + "/" + dragNode);
        }
        if (linkFrom != null && at.containsKey(linkFrom)) {
            double[] p = at.get(linkFrom);
            dl.addLine(msx(ox, p[0]), msy(oy, p[1]), mx, my, col(0.95f, 0.8f, 0.35f, 1f), 2f);
            if (ImGui.isMouseReleased(ImGuiMouseButton.Left)) {
                if (hoveredState != null && !hoveredState.equals(linkFrom)) {
                    ctx.apply("[{\"op\":\"transition\",\"machine\":" + quote(machine) + ",\"from\":" + quote(linkFrom)
                            + ",\"to\":" + quote(hoveredState) + "}]", "add transition", null);
                }
                linkFrom = null;
            }
        }
        if (ImGui.isMouseReleased(ImGuiMouseButton.Left)) dragNode = null;
        if (hovered && (ImGui.isMouseDragging(ImGuiMouseButton.Middle) || ImGui.isMouseDragging(ImGuiMouseButton.Right, 4f))) {
            machineView.pan(ImGui.getIO().getMouseDeltaX(), ImGui.getIO().getMouseDeltaY());
        }
        if (hovered && ImGui.getIO().getMouseWheel() != 0) {
            machineView.zoomAbout(mx - ox, my - oy, Math.pow(1.12, ImGui.getIO().getMouseWheel()));
        }
        if (hovered && ImGui.isKeyPressed(ImGuiKey.Delete, false) && !ImGui.getIO().getWantTextInput()) {
            if (ctx.selectedState != null) {
                ctx.apply("[{\"op\":\"state\",\"machine\":" + quote(machine) + ",\"name\":" + quote(ctx.selectedState)
                        + ",\"remove\":true}]", "delete state", null);
                ctx.selectedState = null;
            } else if (ctx.selectedTransition >= 0) {
                ctx.apply("[{\"op\":\"transition\",\"machine\":" + quote(machine) + ",\"index\":" + ctx.selectedTransition
                        + ",\"remove\":true}]", "delete transition", null);
                ctx.selectedTransition = -1;
            }
        }
        if (hovered && ImGui.isMouseReleased(ImGuiMouseButton.Right) && dist(mx, my, rightX, rightY) < 4) {
            addX = (float) machineView.toGraphX(mx - ox);
            addY = (float) machineView.toGraphY(my - oy);
            ImGui.openPopup("##machineMenu");
        }
        dl.popClipRect();
        if (ImGui.beginPopup("##machineMenu")) {
            if (ImGui.menuItem("Add state")) {
                String name = ctx.freshState(m, "state");
                ctx.apply(String.format(java.util.Locale.ROOT, "[{\"op\":\"state\",\"machine\":%s,\"name\":%s,\"x\":%.1f,\"y\":%.1f}]",
                        quote(machine), quote(name), addX, addY), "add state", null);
                ctx.selectedState = name;
            }
            if (ImGui.menuItem("Add conduit")) {
                String name = ctx.freshState(m, "conduit");
                ctx.apply(String.format(java.util.Locale.ROOT, "[{\"op\":\"state\",\"machine\":%s,\"name\":%s,\"conduit\":true,\"x\":%.1f,\"y\":%.1f}]",
                        quote(machine), quote(name), addX, addY), "add conduit", null);
            }
            if (ctx.selectedState != null && ImGui.menuItem("Make entry: " + ctx.selectedState)) {
                ctx.apply("[{\"op\":\"state\",\"machine\":" + quote(machine) + ",\"name\":" + quote(ctx.selectedState)
                        + ",\"entry\":true}]", "set entry", null);
            }
            if (ctx.selectedState != null && ImGui.menuItem("Add transition from any state to " + ctx.selectedState)) {
                ctx.apply("[{\"op\":\"transition\",\"machine\":" + quote(machine) + ",\"from\":\"*\",\"to\":"
                        + quote(ctx.selectedState) + "}]", "add transition", null);
            }
            if (ctx.preview() && ctx.selectedState != null && ImGui.menuItem("Travel preview to " + ctx.selectedState)) {
                ctx.travel(machine, ctx.selectedState);
            }
            ImGui.endPopup();
        }
        ImGui.textDisabled("Drag states to move; Shift+drag from a state to another adds a transition; right-click to add.");
    }

    // ------------------------------------------------------------------------------------------------

    private float sx(float ox, double gx) {
        return ox + (float) graphView.toScreenX(gx);
    }

    private float sy(float oy, double gy) {
        return oy + (float) graphView.toScreenY(gy);
    }

    private float msx(float ox, double gx) {
        return ox + (float) machineView.toScreenX(gx);
    }

    private float msy(float oy, double gy) {
        return oy + (float) machineView.toScreenY(gy);
    }

    private static void drawGrid(ImDrawList dl, GraphCanvasView v, float ox, float oy, float w, float h) {
        double step = 40 * v.zoom();
        if (step < 8) return;
        int c = col(1f, 1f, 1f, 0.035f);
        double startX = ((v.toScreenX(0) % step) + step) % step;
        double startY = ((v.toScreenY(0) % step) + step) % step;
        for (double x = startX; x < w; x += step) dl.addLine(ox + (float) x, oy, ox + (float) x, oy + h, c);
        for (double y = startY; y < h; y += step) dl.addLine(ox, oy + (float) y, ox + w, oy + (float) y, c);
    }

    private static float[] typeColor(String type) {
        return switch (type) {
            case "clip" -> new float[] {0.25f, 0.45f, 0.7f};
            case "blend1d", "blend2d" -> new float[] {0.45f, 0.35f, 0.65f};
            case "stateMachine" -> new float[] {0.6f, 0.4f, 0.2f};
            case "layer" -> new float[] {0.25f, 0.55f, 0.45f};
            case "slot" -> new float[] {0.6f, 0.3f, 0.35f};
            default -> new float[] {0.35f, 0.35f, 0.38f};
        };
    }

    static int col(float r, float g, float b, float a) {
        return ImGui.colorConvertFloat4ToU32(r, g, b, a);
    }

    private static float dist(float ax, float ay, float bx, float by) {
        float dx = ax - bx, dy = ay - by;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float distToSegment(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float l2 = dx * dx + dy * dy;
        float t = l2 <= 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
        return dist(px, py, ax + t * dx, ay + t * dy);
    }

    static String quote(String s) {
        try {
            return JSON.writeValueAsString(s);
        } catch (Exception e) {
            return "\"\"";
        }
    }
}
