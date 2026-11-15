package com.openmason.engine.voxel.stamp;

import com.openmason.engine.format.mesh.ParsedMeshData;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The neutral input of a block's stamp geometry (#88): the default mesh and any per-state meshes,
 * as the engine's generic mesh records (read from OMO). A game builds one from its own block
 * container (Stonebreak's {@code .sbo}); the stamp pipeline never sees the container format.
 *
 * @param mesh       the default (no-state) mesh
 * @param stateMeshes state name → that state's mesh (insertion order kept); empty when stateless
 */
public record BlockModelSource(ParsedMeshData mesh, Map<String, ParsedMeshData> stateMeshes) {

    public BlockModelSource {
        Objects.requireNonNull(mesh, "mesh");
        stateMeshes = stateMeshes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(stateMeshes));
    }

    /** A stateless block. */
    public static BlockModelSource of(ParsedMeshData mesh) {
        return new BlockModelSource(mesh, Map.of());
    }

    public boolean hasStates() {
        return !stateMeshes.isEmpty();
    }
}
