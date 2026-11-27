package com.openmason.engine.modeling.uv;

/**
 * Immutable record defining a material that can be assigned to mesh faces.
 *
 * <p>Each material references a texture source (a {@code PixelCanvas} or atlas region)
 * via {@code textureId}, and carries rendering metadata that determines how the
 * material is drawn. The {@code materialId} field links to
 * {@link FaceTextureMapping#materialId()}.
 *
 * <p>A default material (ID&nbsp;0) is always available via {@link #DEFAULT} and is
 * used whenever no material is explicitly assigned.
 *
 * @param materialId  Unique identifier for this material (0&nbsp;= default)
 * @param name        Human-readable display name
 * @param textureId   Reference to a PixelCanvas or atlas region providing pixel data
 * @param renderLayer Transparency/sorting hint for the renderer
 * @param properties  Optional visual properties (emissive, tint)
 */
public record MaterialDefinition(
    int materialId,
    String name,
    int textureId,
    RenderLayer renderLayer,
    MaterialProperties properties
) {

    /** Default material used when no material is explicitly assigned. */
    public static final MaterialDefinition DEFAULT =
        new MaterialDefinition(0, "Default", 0, RenderLayer.OPAQUE, MaterialProperties.NONE);

    /**
     * Compact constructor — validates invariants.
     */
    public MaterialDefinition {
        if (materialId < 0) {
            throw new IllegalArgumentException("materialId must be non-negative: " + materialId);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be null or blank");
        }
        if (textureId < 0) {
            throw new IllegalArgumentException("textureId must be non-negative: " + textureId);
        }
        if (renderLayer == null) {
            throw new IllegalArgumentException("renderLayer must not be null");
        }
        if (properties == null) {
            properties = MaterialProperties.NONE;
        }
    }

    /**
     * Transparency and sorting behaviour for a material.
     *
     * <p>Mirrors the concept from the game's block render layers but scoped
     * to Open Mason's material system.
     */
    public enum RenderLayer {
        /** Fully opaque — fastest path, no blending. */
        OPAQUE,
        /** Binary transparency — alpha testing, no sorting. */
        CUTOUT,
        /** Semi-transparent — requires depth sorting. */
        TRANSLUCENT
    }

    /**
     * Optional visual properties attached to a material.
     *
     * @param emissive  Whether the material emits light (ignores scene lighting)
     * @param tintColor RGBA tint applied multiplicatively, packed as 0xRRGGBBAA.
     *                  Use {@code 0xFFFFFFFF} (white, fully opaque) for no tint.
     * @param baseColor OMO 1.9 base colour (RGBA, 4 floats; Blender / glTF semantics: it multiplies the
     *                  material's texture, exactly as the runtime draws it), or null = none (white)
     */
    public record MaterialProperties(boolean emissive, int tintColor, float[] baseColor) {

        /** No-op properties: non-emissive, white tint (identity). */
        public static final MaterialProperties NONE = new MaterialProperties(false, 0xFFFFFFFF);

        public MaterialProperties {
            if (baseColor != null) {
                if (baseColor.length != 4) {
                    throw new IllegalArgumentException("baseColor must hold 4 floats (RGBA)");
                }
                baseColor = baseColor.clone();
            }
        }

        /** Properties without a base colour. */
        public MaterialProperties(boolean emissive, int tintColor) {
            this(emissive, tintColor, null);
        }

        @Override
        public float[] baseColor() {
            return baseColor == null ? null : baseColor.clone();
        }

        /** @return copy with another base colour (null = none) */
        public MaterialProperties withBaseColor(float[] rgba) {
            return new MaterialProperties(emissive, tintColor, rgba);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof MaterialProperties p && p.emissive == emissive && p.tintColor == tintColor
                && java.util.Arrays.equals(p.baseColor, baseColor);
        }

        @Override
        public int hashCode() {
            return (Boolean.hashCode(emissive) * 31 + tintColor) * 31 + java.util.Arrays.hashCode(baseColor);
        }

        @Override
        public String toString() {
            return "MaterialProperties[emissive=" + emissive + ", tintColor=" + tintColor
                + ", baseColor=" + java.util.Arrays.toString(baseColor) + "]";
        }

        /**
         * Extract the red channel (0–255).
         */
        public int red() {
            return (tintColor >>> 24) & 0xFF;
        }

        /**
         * Extract the green channel (0–255).
         */
        public int green() {
            return (tintColor >>> 16) & 0xFF;
        }

        /**
         * Extract the blue channel (0–255).
         */
        public int blue() {
            return (tintColor >>> 8) & 0xFF;
        }

        /**
         * Extract the alpha channel (0–255).
         */
        public int alpha() {
            return tintColor & 0xFF;
        }

        /**
         * Convenience factory for an emissive material with no tint.
         */
        public static MaterialProperties withEmissive() {
            return new MaterialProperties(true, 0xFFFFFFFF);
        }

        /**
         * Convenience factory for a tinted, non-emissive material.
         *
         * @param rgba Tint colour packed as 0xRRGGBBAA
         */
        public static MaterialProperties tinted(int rgba) {
            return new MaterialProperties(false, rgba);
        }
    }
}
