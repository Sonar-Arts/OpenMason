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
 * @param normals    corner normals (OpenMason #169), 2 octahedral snorm16 values per vertex
 *                   ({@code model_shading.h}); the pair {@code (-32768, -32768)}
 *                   ({@link com.openmason.engine.rendering.model.draw.ModelNormalGlsl#FLAT}) marks a flat-shaded
 *                   corner, lit with its drawn face's exact normal. Null = every corner flat (a model without
 *                   smooth faces or custom normals: every OMO before 1.9)
 * @param maskPlan   the vertex-group mask plan (OMO 1.9 {@code vertexGroups}, OpenMason #172), or null when the model
 *                   has no vertex groups. When present, {@code indices} IS its reordered buffer (triangles moved only
 *                   inside their part x material draw batch, so every face's index range is unchanged); hosts hide
 *                   groups through {@code ModelAppearance} and draw {@code ModelDrawPlan.forAppearance}'s ranges.
 *                   The model's morph targets (shape keys, #171) ride its rig: {@link #morphs()}
 */
public record EntityGeometry(
        float[] vertices,
        float[] texCoords,
        int[] indices,
        List<EntityPart> parts,
        Map<Integer, MaterialImage> materials,
        float restMinY,
        List<EntityAttachmentPoint> attachmentPoints,
        RigModel rig,
        short[] normals,
        com.openmason.engine.format.omo.MaskPlan maskPlan
) {
    public EntityGeometry {
        parts = parts == null ? List.of() : List.copyOf(parts);
        materials = materials == null ? Map.of() : Map.copyOf(materials);
        attachmentPoints = attachmentPoints == null ? List.of() : List.copyOf(attachmentPoints);
        if (rig == null) {
            rig = RigModel.ofParts(partSpecs(parts), partJointOfVertex(parts, indices, vertices));
        }
        if (normals != null && vertices != null && normals.length != (vertices.length / 3) * 2) {
            throw new IllegalArgumentException("normals hold 2 values per vertex: " + normals.length + " for "
                    + vertices.length / 3 + " vertices");
        }
    }

    /** Geometry without vertex groups. */
    public EntityGeometry(float[] vertices, float[] texCoords, int[] indices, List<EntityPart> parts,
                          Map<Integer, MaterialImage> materials, float restMinY,
                          List<EntityAttachmentPoint> attachmentPoints, RigModel rig, short[] normals) {
        this(vertices, texCoords, indices, parts, materials, restMinY, attachmentPoints, rig, normals, null);
    }

    /** The model's morph targets (shape keys, OpenMason #171; its rig's morph channels), or null without. */
    public com.openmason.engine.rig.RigMorphs morphs() {
        return rig.morphs();
    }

    /** Geometry without normals (every corner flat). */
    public EntityGeometry(float[] vertices, float[] texCoords, int[] indices, List<EntityPart> parts,
                          Map<Integer, MaterialImage> materials, float restMinY,
                          List<EntityAttachmentPoint> attachmentPoints, RigModel rig) {
        this(vertices, texCoords, indices, parts, materials, restMinY, attachmentPoints, rig, null, null);
    }

    /** True when some corner has its own (smooth or custom) normal. */
    public boolean hasSmoothNormals() {
        if (normals == null) return false;
        for (int i = 0; i + 1 < normals.length; i += 2) {
            if (normals[i] != Short.MIN_VALUE || normals[i + 1] != Short.MIN_VALUE) return true;
        }
        return false;
    }

    /** Geometry whose rig is its parts alone (no bones, no skin). */
    public EntityGeometry(float[] vertices, float[] texCoords, int[] indices, List<EntityPart> parts,
                          Map<Integer, MaterialImage> materials, float restMinY,
                          List<EntityAttachmentPoint> attachmentPoints) {
        this(vertices, texCoords, indices, parts, materials, restMinY, attachmentPoints, null, null, null);
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
                    p.restOrigin(), p.restPos(), p.restRot(), p.restScale(), !p.visible()));
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
