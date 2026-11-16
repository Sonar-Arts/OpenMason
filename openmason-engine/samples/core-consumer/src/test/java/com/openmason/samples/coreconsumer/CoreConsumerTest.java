package com.openmason.samples.coreconsumer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #387 acceptance: a consumer of {@code engine-core} alone builds and runs. */
class CoreConsumerTest {

    @Test
    void runsOnTheEngineCoreAlone() {
        CoreConsumer.Report report = CoreConsumer.run();
        assertTrue(report.payloadRoundTripped());
        if (report.waypoints() >= 0) { // the native library is built
            assertTrue(report.routedThroughTheGap(), "the only way past the wall is its gap at z 7");
            assertTrue(report.pathCost() > 14.0f, "a detour, not the straight 7: " + report.pathCost());
        }
        CoreConsumer.main(new String[0]);
    }

    @Test
    void neitherTheUiNorTheVoxelModuleNorSkijaIsReachable() {
        // Class lookups, not ModuleLayer queries: they hold on the module path and the class path alike.
        for (String cls : new String[]{"com.openmason.engine.ui.runtime.UiDocumentInstance",
                "com.openmason.engine.voxel.cco.data.CcoBlockStorage", "io.github.humbleui.skija.Canvas",
                "com.openmason.engine.modeling.EditableModel",
                "com.openmason.engine.rendering.viewer.ModelViewer"}) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(cls),
                cls + " leaked into a core-only consumer");
        }
    }
}
