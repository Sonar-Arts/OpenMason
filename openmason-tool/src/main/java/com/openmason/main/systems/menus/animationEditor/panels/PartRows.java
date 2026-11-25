package com.openmason.main.systems.menus.animationEditor.panels;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import com.openmason.main.systems.skeleton.BoneStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The row order shared by the part list and the timeline: the model's JOINTS — parts and bones,
 * one hierarchy (rig revamp: a part may hang under a bone and a bone under a part) — depth-first
 * from the roots, children indented under their parent (part children first, then bone children,
 * each in model order), followed by any <em>unbound</em> tracks — tracks whose id matches no part or
 * bone on the model. Keeping one ordering here means the two panels always line up and both show
 * orphans, which would otherwise be invisible yet still saved.
 */
public final class PartRows {

    /**
     * One row.
     *
     * @param id     joint id (part or bone), or the orphan track's saved id
     * @param label  display name
     * @param depth  hierarchy depth (0 = root); orphans are 0
     * @param part   the model part, or null for a bone / orphan row
     * @param track  the orphan track, or null for a joint row
     * @param bone   the bone, or null for a part / orphan row
     */
    public record Row(String id, String label, int depth, ModelPartDescriptor part, Track track,
                      OMOFormat.BoneEntry bone) {

        public Row(String id, String label, int depth, ModelPartDescriptor part, Track track) {
            this(id, label, depth, part, track, null);
        }

        public boolean isOrphan() { return part == null && bone == null; }

        public boolean isBone() { return bone != null; }
    }

    private PartRows() {}

    /** Parts only (no skeleton). */
    public static List<Row> build(ModelPartManager pm, List<Track> orphans) {
        return build(pm, null, orphans);
    }

    public static List<Row> build(ModelPartManager pm, BoneStore bones, List<Track> orphans) {
        List<Row> rows = new ArrayList<>();
        List<ModelPartDescriptor> parts = pm != null ? pm.getAllParts() : List.of();
        List<OMOFormat.BoneEntry> boneList = bones != null ? bones.getBones() : List.of();

        Set<String> partIds = new HashSet<>();
        for (ModelPartDescriptor p : parts) partIds.add(p.id());
        Set<String> boneIds = new HashSet<>();
        for (OMOFormat.BoneEntry b : boneList) {
            if (!partIds.contains(b.id())) boneIds.add(b.id());
        }

        // Children of every joint, in model order (parts first, then bones).
        Map<String, List<Object>> children = new LinkedHashMap<>();
        List<Object> roots = new ArrayList<>();
        for (ModelPartDescriptor p : parts) {
            String parent = p.parentId();
            if (parent != null && !parent.equals(p.id()) && (partIds.contains(parent) || boneIds.contains(parent))) {
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(p);
            } else {
                roots.add(p);
            }
        }
        for (OMOFormat.BoneEntry b : boneList) {
            if (!boneIds.contains(b.id())) continue;
            String parent = b.parentBoneId();
            if (parent != null && !parent.equals(b.id()) && (partIds.contains(parent) || boneIds.contains(parent))) {
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(b);
            } else {
                roots.add(b);
            }
        }

        Set<String> visited = new HashSet<>();
        for (Object root : roots) walk(root, 0, children, rows, visited);
        // Anything left (cycles in malformed data) is appended flat.
        for (ModelPartDescriptor p : parts) {
            if (visited.add(p.id())) rows.add(new Row(p.id(), p.name(), 0, p, null, null));
        }
        for (OMOFormat.BoneEntry b : boneList) {
            if (boneIds.contains(b.id()) && visited.add(b.id())) {
                rows.add(new Row(b.id(), b.name(), 0, null, null, b));
            }
        }

        if (orphans != null) {
            for (Track t : orphans) {
                String hint = t.partNameHint();
                String label = (hint != null && !hint.isBlank()) ? hint : shortId(t.partId());
                rows.add(new Row(t.partId(), label, 0, null, t, null));
            }
        }
        return rows;
    }

    private static void walk(Object node, int depth, Map<String, List<Object>> children,
                             List<Row> out, Set<String> visited) {
        String id;
        if (node instanceof ModelPartDescriptor p) {
            id = p.id();
            if (!visited.add(id)) return;
            out.add(new Row(id, p.name(), depth, p, null, null));
        } else {
            OMOFormat.BoneEntry b = (OMOFormat.BoneEntry) node;
            id = b.id();
            if (!visited.add(id)) return;
            out.add(new Row(id, b.name(), depth, null, null, b));
        }
        for (Object child : children.getOrDefault(id, List.of())) {
            walk(child, depth + 1, children, out, visited);
        }
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) + "…" : id;
    }
}
