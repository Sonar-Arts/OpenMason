package com.openmason.main.systems.menus.animationEditor.data;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.main.systems.skeleton.BoneStore;

import java.util.Optional;

/**
 * The animatable JOINTS of the bound model (rig revamp, OMO 1.8): its parts and its bones. Clip
 * tracks key a joint id (part OR bone), with the joint's name as the fallback key. A read-only view
 * over the part manager and the bone store (either may be null).
 */
public record Joints(ModelPartManager parts, BoneStore bones) {

    public static Joints of(ModelPartManager parts, BoneStore bones) {
        return new Joints(parts, bones);
    }

    public boolean isPart(String id) {
        return id != null && parts != null && parts.getPartById(id).isPresent();
    }

    public boolean isBone(String id) {
        return id != null && bones != null && bones.getById(id) != null && !isPart(id);
    }

    /** True when {@code id} names a part or a bone of the model. */
    public boolean exists(String id) {
        return isPart(id) || isBone(id);
    }

    /** The joint's display name, or null when {@code id} is neither a part nor a bone. */
    public String nameOf(String id) {
        if (id == null) return null;
        if (parts != null) {
            Optional<ModelPartDescriptor> p = parts.getPartById(id);
            if (p.isPresent()) return p.get().name();
        }
        if (bones != null) {
            OMOFormat.BoneEntry b = bones.getById(id);
            if (b != null) return b.name();
        }
        return null;
    }

    /**
     * Joint id by name: a part with the exact name, a bone with the exact name, then the same
     * case-insensitively (the runtime binding order after the id). Null when none.
     */
    public String idByName(String name) {
        if (name == null || name.isBlank()) return null;
        if (parts != null) {
            for (ModelPartDescriptor p : parts.getAllParts()) {
                if (name.equals(p.name())) return p.id();
            }
        }
        if (bones != null) {
            for (OMOFormat.BoneEntry b : bones.getBones()) {
                if (name.equals(b.name())) return b.id();
            }
        }
        if (parts != null) {
            for (ModelPartDescriptor p : parts.getAllParts()) {
                if (name.equalsIgnoreCase(p.name())) return p.id();
            }
        }
        if (bones != null) {
            OMOFormat.BoneEntry b = bones.getByName(name);
            if (b != null) return b.id();
        }
        return null;
    }

    /** A joint id from an id or a name (id first); null when nothing matches. */
    public String resolve(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) return null;
        if (exists(idOrName)) return idOrName;
        return idByName(idOrName);
    }
}
