package com.openmason.engine.format.omo;

import com.openmason.engine.format.mesh.ParsedFaceMapping;
import com.openmason.engine.format.mesh.ParsedMaterialData;
import com.openmason.engine.format.mesh.ParsedMeshData;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only OMO reader for the engine.
 * Produces {@link ParsedMeshData} and material data — no editor dependencies.
 *
 * <p>The codec is native ({@code libomformat} through {@link OmoDocument}, OpenMason #167); this class only maps the
 * document onto the engine's immutable read records. Unlike the editor's OMODeserializer (which produces an editable
 * BlockModel), this reader is for rendering and texture extraction.
 */
public class OMOReader {

    /**
     * Result of reading an OMO file/stream.
     *
     * @param shading      OMO 1.9 shading, or null
     * @param vertexGroups OMO 1.9 vertex groups (empty when none)
     * @param morphs       OMO 1.9 morphs / shape keys (empty when none)
     */
    public record ReadResult(
            OMOFormat.Document document,
            ParsedMeshData meshData,
            List<ParsedFaceMapping> faceMappings,
            List<ParsedMaterialData> materials,
            byte[] defaultTextureBytes,
            List<OMOFormat.PartEntry> parts,
            List<OMOFormat.AttachmentPointEntry> attachmentPoints,
            List<OMOFormat.BoneEntry> bones,
            OMOFormat.SkinData skin,
            OMOFormat.ShadingData shading,
            List<OMOFormat.VertexGroupData> vertexGroups,
            List<OMOFormat.MorphData> morphs
    ) {
        public ReadResult {
            parts = parts == null ? List.of() : parts;
            attachmentPoints = attachmentPoints == null ? List.of() : attachmentPoints;
            bones = bones == null ? List.of() : List.copyOf(bones);
            vertexGroups = vertexGroups == null ? List.of() : List.copyOf(vertexGroups);
            morphs = morphs == null ? List.of() : List.copyOf(morphs);
        }

        /** A result without OMO 1.9 data. */
        public ReadResult(OMOFormat.Document document, ParsedMeshData meshData,
                          List<ParsedFaceMapping> faceMappings, List<ParsedMaterialData> materials,
                          byte[] defaultTextureBytes, List<OMOFormat.PartEntry> parts,
                          List<OMOFormat.AttachmentPointEntry> attachmentPoints,
                          List<OMOFormat.BoneEntry> bones, OMOFormat.SkinData skin) {
            this(document, meshData, faceMappings, materials, defaultTextureBytes, parts, attachmentPoints,
                    bones, skin, null, List.of(), List.of());
        }

        /** A result without a skeleton or skin (pre-1.6 data, tests). */
        public ReadResult(OMOFormat.Document document, ParsedMeshData meshData,
                          List<ParsedFaceMapping> faceMappings, List<ParsedMaterialData> materials,
                          byte[] defaultTextureBytes, List<OMOFormat.PartEntry> parts,
                          List<OMOFormat.AttachmentPointEntry> attachmentPoints) {
            this(document, meshData, faceMappings, materials, defaultTextureBytes, parts, attachmentPoints,
                    List.of(), null);
        }
    }

    /**
     * Read an OMO file from a stream (e.g. extracted from an SBO ZIP).
     *
     * @param omoStream input stream containing the OMO ZIP data
     * @return parsed result
     * @throws IOException if reading or parsing fails (also when the native model module is unavailable)
     */
    public ReadResult read(InputStream omoStream) throws IOException {
        return read(omoStream.readAllBytes());
    }

    /**
     * Read an OMO archive held in memory.
     *
     * @throws IOException if parsing fails (a newer major version: {@link OmoDocument.VersionException})
     */
    public ReadResult read(byte[] omoBytes) throws IOException {
        try (OmoDocument doc = OmoDocument.read(omoBytes)) {
            OMOFormat.Document document;
            try {
                document = new OMOFormat.Document(doc.version(), doc.objectName(), doc.modelType(), doc.geometry(),
                        doc.textureFile());
            } catch (IllegalArgumentException e) {
                throw new IOException("invalid .omo manifest: " + e.getMessage(), e);
            }

            OMOFormat.MeshData mesh = doc.mesh();
            ParsedMeshData meshData = mesh == null ? null : new ParsedMeshData(mesh.vertices(), mesh.texCoords(),
                    mesh.indices(), mesh.triangleToFaceId(), mesh.uvMode());

            List<ParsedFaceMapping> faceMappings = new ArrayList<>();
            for (OMOFormat.FaceMappingEntry m : doc.faceMappings()) {
                faceMappings.add(new ParsedFaceMapping(m.faceId(), m.materialId(), m.u0(), m.v0(), m.u1(), m.v1(),
                        m.uvRotationDegrees(), m.autoResize()));
            }

            // Join each material with its image entry (a 1.9 material may have only a base colour).
            Map<String, byte[]> images = doc.materialImages();
            List<ParsedMaterialData> materials = new ArrayList<>();
            for (OMOFormat.MaterialEntry me : doc.materials()) {
                byte[] png = me.textureFile() == null ? null : images.get(me.textureFile());
                materials.add(new ParsedMaterialData(me.materialId(), me.name(), me.textureFile(), png,
                        me.renderLayer(), me.emissive(), me.tintColor(), me.baseColor()));
            }

            return new ReadResult(document, meshData, faceMappings, materials, doc.texture(), doc.parts(),
                    doc.attachmentPoints(), doc.bones(), doc.skin(), doc.shading(), doc.vertexGroups(),
                    doc.morphs());
        }
    }
}
