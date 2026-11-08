package com.openmason.engine.rendering.particles;

/** Receives the lights of live FX instances ({@link FxWorld#forEachLight}); keep it allocation-free. */
@FunctionalInterface
public interface FxLightSink {

    /** One light: world position, linear RGB 0..1, intensity (already curved, flickered, faded) and reach. */
    void light(FxInstance instance, double x, double y, double z, float r, float g, float b, float intensity,
               float radius);
}
