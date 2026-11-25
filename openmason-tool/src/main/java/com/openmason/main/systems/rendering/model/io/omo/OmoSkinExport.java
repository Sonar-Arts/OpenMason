package com.openmason.main.systems.rendering.model.io.omo;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.parts.MeshRange;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.main.systems.skeleton.BoneStore;
import com.openmason.main.systems.skeleton.SkinStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skin weights in the layout an OMO file is WRITTEN in (OMO 1.8 {@code skin}).
 *
 * <p>The session keeps weights per part over the part's own (combined-soup) vertices. The saved mesh
 * can have another layout: the live save writes the derived render mesh (corners duplicated per face,
 * coincident soup vertices welded). The runtime — and the tool's loader, which slices each part's
 * vertices out of the saved span — reads a weight's local index as {@code savedVertex - vertexStart}
 * of the part entry. So every saved vertex of a part takes the weights of the soup vertex it stands
 * for ({@link ModelPartManager#resolveCombinedVertex}; identity when the soup itself is written, as
 * the headless writer does). Index marshalling only — no weight math.
 */
public final class OmoSkinExport {

    private OmoSkinExport() {
    }

    /**
     * The skin of {@code parts} in the layout of the part entries being written, or null when no
     * written vertex carries weights. Joints that are no longer a part or bone are dropped.
     *
     * @param entries the part entries being written (their spans index the saved mesh); null = none
     */
    public static OMOFormat.SkinData toSavedLayout(SkinStore skins, ModelPartManager parts, BoneStore bones,
                                                   List<OMOFormat.PartEntry> entries) {
        if (skins == null || parts == null || entries == null || entries.isEmpty() || skins.isEmpty()) {
            return null;
        }
        Set<String> live = new HashSet<>();
        for (ModelPartDescriptor p : parts.getAllParts()) live.add(p.id());
        if (bones != null) {
            for (OMOFormat.BoneEntry b : bones.getBones()) live.add(b.id());
        }
        OMOFormat.SkinData soup = skins.toSkinData(live);
        if (soup == null) return null;

        Map<String, Map<Integer, OMOFormat.SkinVertex>> soupByPart = new HashMap<>();
        for (OMOFormat.PartSkin ps : soup.parts()) {
            Map<Integer, OMOFormat.SkinVertex> byLocal = new HashMap<>();
            for (OMOFormat.SkinVertex v : ps.vertices()) byLocal.put(v.localVertex(), v);
            soupByPart.put(ps.partId(), byLocal);
        }

        List<OMOFormat.PartSkin> out = new ArrayList<>();
        for (OMOFormat.PartEntry entry : entries) {
            Map<Integer, OMOFormat.SkinVertex> byLocal = soupByPart.get(entry.id());
            if (byLocal == null || entry.vertexCount() <= 0) continue;
            MeshRange range = parts.getPartById(entry.id()).map(ModelPartDescriptor::meshRange).orElse(null);
            if (range == null || range.vertexCount() <= 0) continue;
            List<OMOFormat.SkinVertex> vertices = new ArrayList<>();
            for (int local = 0; local < entry.vertexCount(); local++) {
                int saved = entry.vertexStart() + local;
                for (int soupIndex : parts.resolveCombinedVertex(saved)) {
                    if (!range.containsVertex(soupIndex)) continue;
                    OMOFormat.SkinVertex sv = byLocal.get(range.toLocalVertex(soupIndex));
                    if (sv != null) {
                        vertices.add(new OMOFormat.SkinVertex(local, sv.joints(), sv.weights()));
                    }
                    break;
                }
            }
            if (!vertices.isEmpty()) out.add(new OMOFormat.PartSkin(entry.id(), vertices));
        }
        return out.isEmpty() ? null : new OMOFormat.SkinData(soup.jointIds(), out);
    }
}
