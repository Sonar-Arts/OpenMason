package com.openmason.engine.entity.model;

import com.openmason.engine.rig.RigModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CPU-side resolved geometry for one OMO model (a cow variant).
 *
 * <p>The mesh arrays are taken verbatim from the OMO — vertices, texture
 * coordinates and part transforms are exactly as authored in the OMO, with no
 * remapping or re-anchoring. Vertices are in MODEL space at the rest (bind) pose.
 * Parts index into the combined {@code indices} buffer; faces within a part
 * select their material texture by id.
 *
 * @param vertices   interleaved x,y,z vertex positions (model space, rest pose)
 * @param texCoords  interleaved u,v texture coordinates
 * @param indices    combined triangle index buffer
 * @param parts      model parts in draw order
 * @param materials  material id → decoded texture image
 * @param restMinY   lowest model-space Y across all rendered vertices in the
 *                   rest pose — the model's "feet". Renderers ground-anchor a
 *                   mob by placing this at {@code position.y - legHeight}, so
 *                   a mob never floats or sinks regardless of where the model
 *                   author put the origin.
 * @param attachmentPoints authored attachment points (sockets) where other
 *                   models can be mounted at runtime; empty for most models
 * @param rig        the model's rig: one joint per part (joint i = part i), then its
 *                   bones, plus skin weights. Posed by the native rig
 *                   ({@link com.openmason.engine.rig.RigPoser}); never null
 */
public record EntityGeometry(
        float[] vertices,
        float[] texCoords,
        int[] indices,
        List<EntityPart> parts,
        Map<Integer, MaterialImage> materials,
        float restMinY,
        List<EntityAttachmentPoint> attachmentPoints,
        RigModel rig
) {
    public EntityGeometry {
        parts = parts == null ? List.of() : List.copyOf(parts);
        materials = materials == null ? Map.of() : Map.copyOf(materials);
        attachmentPoints = attachmentPoints == null ? List.of() : List.copyOf(attachmentPoints);
        if (rig == null) {
            rig = RigModel.ofParts(partSpecs(parts), partJointOfVertex(parts, indices, vertices));
        }
    }

    /** Geometry whose rig is its parts alone (no bones, no skin). */
    public EntityGeometry(float[] vertices, float[] texCoords, int[] indices, List<EntityPart> parts,
                          Map<Integer, MaterialImage> materials, float restMinY,
                          List<EntityAttachmentPoint> attachmentPoints) {
        this(vertices, texCoords, indices, parts, materials, restMinY, attachmentPoints, null);
    }

    /** The parts as rig joints (joint i = part i). */
    public static List<RigModel.PartSpec> partSpecs(List<EntityPart> parts) {
        List<RigModel.PartSpec> specs = new ArrayList<>(parts == null ? 0 : parts.size());
        if (parts == null) return specs;
        for (EntityPart p : parts) {
            boolean geometry = false;
            for (EntityFace f : p.faces()) {
                if (f.indexCount() > 0) {
                    geometry = true;
                    break;
                }
            }
            specs.add(new RigModel.PartSpec(p.id(), p.name(), p.parentId(), geometry,
                    p.restOrigin(), p.restPos(), p.restRot(), p.restScale()));
        }
        return specs;
    }

    /**
     * The part each vertex belongs to, from the parts' face index ranges (a vertex indexed by no part
     * gets part 0). This is how the pre-rig renderer bound vertices: by the part whose faces draw them.
     */
    public static int[] partJointOfVertex(List<EntityPart> parts, int[] indices, float[] vertices) {
        int vertexCount = vertices == null ? 0 : vertices.length / 3;
        int[] out = new int[vertexCount];
        java.util.Arrays.fill(out, -1);
        if (parts != null && indices != null) {
            for (int p = 0; p < parts.size(); p++) {
                for (EntityFace f : parts.get(p).faces()) {
                    int end = Math.min(indices.length, f.indexStart() + f.indexCount());
                    for (int i = Math.max(0, f.indexStart()); i < end; i++) {
                        int v = indices[i];
                        if (v >= 0 && v < vertexCount && out[v] < 0) out[v] = p;
                    }
                }
            }
        }
        for (int v = 0; v < vertexCount; v++) {
            if (out[v] < 0) out[v] = 0;
        }
        return out;
    }
}
