/**
 * Open Mason engine modeling (#91): the authoring side of the engine that no game ships. The editable model
 * ({@code modeling}: {@code EditableModel}, the editable mesh document, edit ops, topology, parts/shapes, UV
 * regions, analysis — it draws through the engine model renderer, #90), and the model viewer ({@code rendering.viewer}: camera,
 * gizmo, passes, picking, scene, transform). Used by the Open Mason tool and, through it, its plugins; stays
 * headless-testable (MCP, tests) and is where a native EditableModel core (Cenda C4) lands.
 * Builds on {@code openmason.engine.core} only.
 */
module openmason.engine.modeling {

    // Transitive: engine formats, GL, JOML and SLF4J types appear in the exported EditableModel/viewer APIs.
    requires transitive openmason.engine.core;

    exports com.openmason.engine.modeling;
    exports com.openmason.engine.modeling.analysis;
    exports com.openmason.engine.modeling.core;
    exports com.openmason.engine.modeling.editable;
    exports com.openmason.engine.modeling.editable.ops;
    exports com.openmason.engine.modeling.extraction;
    exports com.openmason.engine.modeling.mapping;
    exports com.openmason.engine.modeling.mesh.edgeOperations;
    exports com.openmason.engine.modeling.notification;
    exports com.openmason.engine.modeling.parts;
    exports com.openmason.engine.modeling.parts.shapes;
    exports com.openmason.engine.modeling.topology;
    exports com.openmason.engine.modeling.uv;
    exports com.openmason.engine.rendering.viewer;
    exports com.openmason.engine.rendering.viewer.camera;
    exports com.openmason.engine.rendering.viewer.gizmo;
    exports com.openmason.engine.rendering.viewer.gizmo.geometry;
    exports com.openmason.engine.rendering.viewer.gizmo.interaction;
    exports com.openmason.engine.rendering.viewer.gizmo.modes;
    exports com.openmason.engine.rendering.viewer.gizmo.rendering;
    exports com.openmason.engine.rendering.viewer.math;
    exports com.openmason.engine.rendering.viewer.passes;
    exports com.openmason.engine.rendering.viewer.picking;
    exports com.openmason.engine.rendering.viewer.scene;
    exports com.openmason.engine.rendering.viewer.transform;
}
