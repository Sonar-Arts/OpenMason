package com.openmason.main.systems.skeleton.vertexdata;

import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.services.commands.MeshSnapshot;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import com.openmason.main.systems.services.commands.RendererSynchronizer;
import com.openmason.main.systems.services.commands.SnapshotCommand;

import java.util.Map;

/**
 * Puts the captured basis back after "Capture" turned the sculpt into a shape key: the moved parts' positions are
 * replaced ({@link ModelPartManager#replacePartGeometry}, topology unchanged) as ONE model undo step, like any other
 * mesh edit (the same snapshot command the MCP model tools record). Shared by the rigging pane and
 * {@code morph_capture}.
 */
public final class BasisRestore {

    private BasisRestore() {
    }

    /** Writes the geometry back; returns the parts restored. */
    public static int apply(ViewportController viewport, Map<String, PartMeshRebuilder.PartGeometry> geometry) {
        if (viewport == null || geometry == null || geometry.isEmpty()) return 0;
        ModelPartManager parts = viewport.getPartManager();
        EditableModel gmr = viewport.getModelRenderer();
        ModelCommandHistory history = viewport.getCommandHistory();
        RendererSynchronizer sync = viewport.getRendererSynchronizer();
        boolean record = gmr != null && history != null && sync != null;
        MeshSnapshot before = record ? MeshSnapshot.capture(gmr) : null;
        int n = 0;
        for (Map.Entry<String, PartMeshRebuilder.PartGeometry> e : geometry.entrySet()) {
            if (parts.replacePartGeometry(e.getKey(), e.getValue())) n++;
        }
        if (record && n > 0) {
            history.pushCompleted(SnapshotCommand.custom("Restore Shape Key Basis", before, MeshSnapshot.capture(gmr),
                    gmr, sync));
        }
        viewport.invalidateSubRenderers();
        return n;
    }
}
