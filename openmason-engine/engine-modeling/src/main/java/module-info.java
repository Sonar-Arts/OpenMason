/**
 * Open Mason engine modeling (#91): the authoring side of the engine that no game ships. GMR, the Generic
 * Model Renderer ({@code rendering.model.gmr}: {@code GenericModelRenderer}, the editable mesh document, edit
 * ops, topology, parts/shapes, UV regions, analysis), and the model viewer ({@code rendering.viewer}: camera,
 * gizmo, passes, picking, scene, transform). Used by the Open Mason tool and, through it, its plugins; stays
 * headless-testable (MCP, tests) and is where a native GMR core (Cenda C4) lands.
 * Builds on {@code openmason.engine.core} only.
 */
module openmason.engine.modeling {

    // Transitive: engine formats, GL, JOML and SLF4J types appear in the exported GMR/viewer APIs.
    requires transitive openmason.engine.core;

    exports com.openmason.engine.rendering.model.gmr;
    exports com.openmason.engine.rendering.model.gmr.analysis;
    exports com.openmason.engine.rendering.model.gmr.core;
    exports com.openmason.engine.rendering.model.gmr.editable;
    exports com.openmason.engine.rendering.model.gmr.editable.ops;
    exports com.openmason.engine.rendering.model.gmr.extraction;
    exports com.openmason.engine.rendering.model.gmr.geometry;
    exports com.openmason.engine.rendering.model.gmr.mapping;
    exports com.openmason.engine.rendering.model.gmr.mesh.edgeOperations;
    exports com.openmason.engine.rendering.model.gmr.notification;
    exports com.openmason.engine.rendering.model.gmr.parts;
    exports com.openmason.engine.rendering.model.gmr.parts.shapes;
    exports com.openmason.engine.rendering.model.gmr.topology;
    exports com.openmason.engine.rendering.model.gmr.uv;
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
