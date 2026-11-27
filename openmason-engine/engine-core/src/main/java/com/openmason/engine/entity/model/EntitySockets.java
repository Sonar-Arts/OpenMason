package com.openmason.engine.entity.model;

import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.rig.SocketSet;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The native socket set ({@link SocketSet}, Stonebreak #459) of an entity model: its OMO attachment points, plus an
 * {@code .omchar}'s sockets when the model has a character (they win over an attachment point of the same name).
 * Attachment points resolve their joint by id, then exact name; a point whose joint is gone is left out (it never
 * resolves). Of several points with one name the FIRST counts (the old lookup's rule).
 */
public final class EntitySockets {

    /** OMO-only sets, one per rig model (identity keys: {@code RigModel} does not override equals). */
    private static final Map<Object, SocketSet> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private EntitySockets() {
    }

    /** The OMO attachment points of {@code geometry} (cached per rig model); null when the native rig is missing. */
    public static SocketSet of(EntityGeometry geometry) {
        if (geometry == null) return null;
        return CACHE.computeIfAbsent(geometry.rig(), k -> build(geometry, null));
    }

    /**
     * A new set over {@code geometry}'s rig: its attachment points, then {@code character}'s sockets (null = none).
     * The caller owns it.
     */
    public static SocketSet build(EntityGeometry geometry, CharacterDefinition character) {
        SocketSet set = SocketSet.create(geometry.rig().rig());
        Set<String> seen = new HashSet<>();
        for (EntityAttachmentPoint p : geometry.attachmentPoints()) {
            if (p.name() == null || p.name().isBlank() || !seen.add(p.name().toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            set.addRef(p.name(), p.parentPartId(), p.parentPartName(), p.localPos(), p.localRotDeg(), p.localScale(),
                    SocketSet.OMO);
        }
        if (character != null) set.addCharacter(character);
        return set;
    }
}
