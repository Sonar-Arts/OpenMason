package com.openmason.engine.entity.model;

import com.openmason.engine.rig.RigModel;

import com.openmason.engine.format.mesh.ParsedFaceMapping;
import com.openmason.engine.format.mesh.ParsedMaterialData;
import com.openmason.engine.format.mesh.ParsedMeshData;
import com.openmason.engine.format.oma.OMAReader;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.format.oma.AnimationCompatibility;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic, entity-blind loader for entity models (moved from the game, #381; neutral input, #88).
 *
 * <p>Builds a render-ready {@link EntityAsset} from a neutral {@link EntityModelSource} (OMO models,
 * OMANIM clips, shared sound/FX data) with the engine's {@link OMOReader} and {@link OMAReader}. The
 * engine reads bare {@code .omo} files itself; any other container is unpacked by the
 * {@link EntityModelImporter} its format codec registered with
 * {@link com.openmason.engine.format.spi.Formats} (a game's entity container). Results
 * loaded by path are cached, so each file is decoded only once. The loader has no knowledge of which
 * entity a model describes; entity-specific glue (which path to load, AI-state → clip-state mapping)
 * lives with the entity.
 *
 * <p>Resources are read through the caller's {@link ResourceOpener}: under JPMS the engine cannot
 * see another module's resources, so the module that owns them passes its own lookup
 * ({@code MyGame.class::getResourceAsStream}).
 *
 * <p>Decoding is GL-free (textures are decoded to {@code byte[]}), so loading may happen on any
 * thread; the renderer performs GPU upload separately.
 */
public final class EntityModelLoader {

    private static final Logger logger = LoggerFactory.getLogger(EntityModelLoader.class);

    /** The extension the engine reads without an importer. */
    public static final String OMO_EXTENSION = ".omo";

    private static final Map<String, EntityAsset> CACHE = new ConcurrentHashMap<>();

    /** The calling module's resource lookup ({@code Class::getResourceAsStream}); null = absent. */
    @FunctionalInterface
    public interface ResourceOpener {
        InputStream open(String resourcePath) throws IOException;
    }

    private EntityModelLoader() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Loads (or returns the cached) entity asset for a resource, cached by path. The resource is a
     * bare {@code .omo} or a container a registered {@link EntityModelImporter} reads.
     *
     * @param resourcePath absolute resource path, e.g. {@code /entities/cow.<ext>}
     * @param opener       the owning module's resource lookup
     * @throws IllegalStateException if the resource is missing, has no importer or cannot be decoded
     */
    public static EntityAsset load(String resourcePath, ResourceOpener opener) {
        return loadResource(resourcePath, opener);
    }

    /**
     * Loads (or returns the cached) attachable accessory asset from a file on disk (a game's
     * attachment system): a bare {@code .omo} (single variant, clipless) or any container a
     * registered {@link EntityModelImporter} reads (attachments render its default variant at rest
     * pose).
     *
     * @throws IllegalStateException if the file is missing, has no importer or cannot be decoded
     */
    public static EntityAsset loadAttachable(java.nio.file.Path file) {
        String key = file.toAbsolutePath().toString();
        return CACHE.computeIfAbsent(key, k -> {
            try (InputStream in = java.nio.file.Files.newInputStream(file)) {
                return decode(k, in);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load attachable asset: " + k, e);
            }
        });
    }

    /**
     * Resource twin of {@link #loadAttachable(java.nio.file.Path)}.
     *
     * @param resourcePath absolute resource path, e.g. {@code /models/accessories/mustache.omo}
     * @param opener       the owning module's resource lookup
     * @throws IllegalStateException if the resource is missing or cannot be decoded
     */
    public static EntityAsset loadAttachableResource(String resourcePath, ResourceOpener opener) {
        return loadResource(resourcePath, opener);
    }

    /** True when {@code fileName} is a bare OMO or has a registered {@link EntityModelImporter}. */
    public static boolean canLoad(String fileName) {
        return fileName != null && (fileName.toLowerCase(java.util.Locale.ROOT).endsWith(OMO_EXTENSION)
                || importerFor(fileName) != null);
    }

    /** Every extension {@link #loadAttachable} reads: {@code .omo} plus each importer's. */
    public static List<String> loadableExtensions() {
        List<String> out = new ArrayList<>();
        for (var codec : com.openmason.engine.format.spi.Formats.codecs()) {
            if (codec instanceof EntityModelImporter) {
                out.addAll(codec.extensions());
            }
        }
        out.add(OMO_EXTENSION);
        return List.copyOf(out);
    }

    /**
     * The neutral source of a container file, through its registered importer (a bare {@code .omo}
     * becomes a model-only source).
     *
     * @throws IOException when the file cannot be read, or no importer reads it
     */
    public static EntityModelSource importSource(java.nio.file.Path file) throws IOException {
        try (InputStream in = java.nio.file.Files.newInputStream(file)) {
            return importSource(file.toAbsolutePath().toString(), in);
        }
    }

    /** Builds the asset {@code source} describes (not cached). */
    public static EntityAsset build(EntityModelSource source) throws IOException {
        OMOReader omoReader = new OMOReader();

        // Variants: the base OMO is the Default appearance; each further variant supplies its own
        // retextured OMO.
        Map<String, EntityGeometry> variants = new LinkedHashMap<>();
        variants.put(EntityAsset.DEFAULT_VARIANT, buildGeometry(omoReader, source.baseModel()));
        for (Map.Entry<String, byte[]> variant : source.variantModels().entrySet()) {
            if (variant.getValue() != null) {
                variants.put(variant.getKey(), buildGeometry(omoReader, variant.getValue()));
            }
        }

        // States: clips are played against the variant geometry.
        OMAReader omaReader = new OMAReader();
        List<String> basePartIds = partIds(variants.get(EntityAsset.DEFAULT_VARIANT));
        Map<String, ParsedAnimClip> clips = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> state : source.stateClips().entrySet()) {
            if (state.getValue() == null) continue;
            ParsedAnimClip clip = omaReader.read(state.getValue());
            clips.put(state.getKey(), clip);

            AnimationCompatibility.Result compat =
                    AnimationCompatibility.check(clip.requiredParts(), basePartIds);
            if (!compat.isCompatible()) {
                logger.warn("Entity '{}' state '{}' clip animates parts missing from the model: {}",
                        source.displayName(), state.getKey(), compat.describeMissing());
            }
        }

        Map<String, byte[]> clipBytes = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> state : source.stateClips().entrySet()) {
            if (state.getValue() != null) clipBytes.put(state.getKey(), state.getValue());
        }
        return new EntityAsset(source.objectId(), variants, clips,
                source.sounds(), source.soundBytes(), source.fx(), source.character(), clipBytes);
    }

    private static EntityAsset loadResource(String resourcePath, ResourceOpener opener) {
        return CACHE.computeIfAbsent(resourcePath, k -> {
            try (InputStream in = opener.open(k)) {
                if (in == null) {
                    throw new IllegalStateException("Entity model resource not found: " + k);
                }
                return decode(k, in);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load entity model: " + k, e);
            }
        });
    }

    private static EntityAsset decode(String sourceId, InputStream in) throws IOException {
        EntityModelSource source = importSource(sourceId, in);
        EntityAsset asset = build(source);
        logger.info("Loaded entity model '{}' ({}) from {}: {} variant(s), {} animation state(s), {} sound def(s)",
                source.displayName(), source.objectId(), sourceId, asset.variants().size(), asset.clips().size(),
                source.sounds() != null ? source.sounds().sounds().size() : 0);
        return asset;
    }

    /**
     * The neutral source of container bytes, through the importer registered for {@code sourceId}'s
     * extension (a bare {@code .omo} becomes a model-only source).
     *
     * @throws IOException when the bytes cannot be read, or no importer reads the extension
     */
    public static EntityModelSource importSource(String sourceId, InputStream in) throws IOException {
        if (sourceId.toLowerCase(java.util.Locale.ROOT).endsWith(OMO_EXTENSION)) {
            return EntityModelSource.modelOnly(sourceId, in.readAllBytes());
        }
        EntityModelImporter importer = importerFor(sourceId);
        if (importer == null) {
            throw new IOException("No entity model importer is registered for " + sourceId
                    + " (readable: " + loadableExtensions() + ")");
        }
        return importer.importEntity(sourceId, in);
    }

    private static EntityModelImporter importerFor(String fileName) {
        return com.openmason.engine.format.spi.Formats.forFileName(fileName)
                .filter(c -> c instanceof EntityModelImporter)
                .map(c -> (EntityModelImporter) c)
                .orElse(null);
    }

    private static List<String> partIds(EntityGeometry geometry) {
        List<String> ids = new ArrayList<>();
        if (geometry != null) {
            // Clips may key any joint: parts and bones.
            for (var joint : geometry.rig().joints()) {
                ids.add(joint.id());
            }
        }
        return ids;
    }

    /** Decode one embedded OMO into render-ready geometry. */
    private static EntityGeometry buildGeometry(OMOReader omoReader, byte[] omoBytes)
            throws IOException {
        return buildGeometry(omoReader.read(new ByteArrayInputStream(omoBytes)));
    }

    /**
     * Build render-ready geometry from an already-parsed OMO. Public so
     * callers with a {@link OMOReader.ReadResult} in hand (e.g. the
     * animated-block renderer, which gets per-state OMOs from a block model)
     * can reuse the exact mob geometry pipeline.
     */
    public static EntityGeometry buildGeometry(OMOReader.ReadResult omo) throws IOException {
        ParsedMeshData mesh = omo.meshData();
        if (mesh == null || !mesh.hasGeometry()) {
            throw new IOException("OMO model has no mesh geometry");
        }

        // faceId -> materialId
        Map<Integer, Integer> faceMaterial = new HashMap<>();
        for (ParsedFaceMapping mapping : omo.faceMappings()) {
            faceMaterial.put(mapping.faceId(), mapping.materialId());
        }

        // faceId -> [indexStart, indexCount] from the triangle->face map
        Map<Integer, int[]> faceIndexRange = buildFaceIndexRanges(mesh.triangleToFaceId());

        // Parts
        List<EntityPart> parts = new ArrayList<>();
        for (OMOFormat.PartEntry pe : omo.parts()) {
            List<EntityFace> faces = new ArrayList<>();
            for (int faceId = pe.faceStart(); faceId < pe.faceStart() + pe.faceCount(); faceId++) {
                int[] range = faceIndexRange.get(faceId);
                if (range == null) continue;
                int materialId = faceMaterial.getOrDefault(faceId, -1);
                faces.add(new EntityFace(faceId, materialId, range[0], range[1]));
            }
            EntityPart part = new EntityPart(
                    pe.id(), pe.name(), pe.parentId(),
                    new Vector3f(pe.originX(), pe.originY(), pe.originZ()),
                    new Vector3f(pe.posX(), pe.posY(), pe.posZ()),
                    new Vector3f(pe.rotX(), pe.rotY(), pe.rotZ()),
                    new Vector3f(pe.scaleX(), pe.scaleY(), pe.scaleZ()),
                    faces, pe.visible());
            parts.add(part);
        }

        // Partless OMOs (single-cube block models don't persist a parts array):
        // synthesize one identity-rest part covering every face, named after
        // the model so clips authored in the tool still bind — the tool's
        // per-session part UUIDs aren't stable, but clip tracks carry the part
        // NAME as a hint and the renderer falls back to name matching.
        if (parts.isEmpty()) {
            List<EntityFace> allFaces = new ArrayList<>();
            for (Map.Entry<Integer, int[]> e : faceIndexRange.entrySet()) {
                int faceId = e.getKey();
                int[] range = e.getValue();
                allFaces.add(new EntityFace(faceId, faceMaterial.getOrDefault(faceId, -1),
                        range[0], range[1]));
            }
            String name = omo.document() != null && omo.document().objectName() != null
                    ? omo.document().objectName() : "root";
            parts.add(new EntityPart(
                    "synthetic:root", name, null,
                    new Vector3f(0, 0, 0),   // pivot at the model origin
                    new Vector3f(0, 0, 0),
                    new Vector3f(0, 0, 0),
                    new Vector3f(1, 1, 1),
                    allFaces));
        }

        // Saved editor documents retain orphaned materials. Avoid decoding
        // their PNGs and retaining pixel arrays the renderer will never use.
        Set<Integer> usedMaterials = new HashSet<>();
        for (EntityPart part : parts) {
            for (EntityFace face : part.faces()) {
                if (face.indexCount() > 0) usedMaterials.add(face.materialId());
            }
        }
        Map<Integer, MaterialImage> materials = new HashMap<>();
        for (ParsedMaterialData material : omo.materials()) {
            if (usedMaterials.contains(material.materialId()) && material.texturePng() != null) {
                materials.put(material.materialId(), decodePng(material.texturePng()));
            }
        }

        // Mesh and UVs are used exactly as authored in the OMO — no remapping.
        // Guard against null arrays (e.g. models with no UV data) to prevent NPE in uploadVariant.
        float[] vertices  = mesh.vertices()  != null ? mesh.vertices()  : new float[0];
        float[] texCoords = mesh.texCoords() != null ? mesh.texCoords() : new float[0];
        int[]   indices   = mesh.indices()   != null ? mesh.indices()   : new int[0];

        // A vertex is bound to ONE joint; a vertex two parts both index (none of the shipped models
        // has one) is duplicated so each part keeps moving its own copy, as the per-part draw did.
        SplitMesh split = splitSharedVertices(parts, vertices, texCoords, indices);
        vertices = split.vertices();
        texCoords = split.texCoords();
        indices = split.indices();

        // Attachment points (OMO v1.7+)
        List<EntityAttachmentPoint> attachmentPoints = new ArrayList<>();
        for (OMOFormat.AttachmentPointEntry ap : omo.attachmentPoints()) {
            attachmentPoints.add(new EntityAttachmentPoint(
                    ap.id(), ap.name(), ap.parentPartId(), ap.parentPartName(),
                    new Vector3f(ap.posX(), ap.posY(), ap.posZ()),
                    new Vector3f(ap.rotX(), ap.rotY(), ap.rotZ()),
                    new Vector3f(ap.scaleX(), ap.scaleY(), ap.scaleZ())));
        }

        // The rig: parts (joint i = part i), then the skeleton, plus skin weights (OMO 1.8).
        int[] partJointOfVertex = EntityGeometry.partJointOfVertex(parts, indices, vertices);
        int[] partVertexStart = new int[parts.size()];
        for (int i = 0; i < parts.size() && i < omo.parts().size(); i++) {
            partVertexStart[i] = omo.parts().get(i).vertexStart();
        }
        RigModel rig = RigModel.build(EntityGeometry.partSpecs(parts), omo.bones(), omo.skin(),
                partVertexStart, partJointOfVertex);

        return new EntityGeometry(vertices, texCoords, indices, parts, materials,
                computeRestMinY(vertices, indices), attachmentPoints, rig);
    }

    private record SplitMesh(float[] vertices, float[] texCoords, int[] indices) {}

    /** Duplicates every vertex indexed by more than one part's faces (appended; indices rewritten). */
    private static SplitMesh splitSharedVertices(List<EntityPart> parts, float[] vertices, float[] texCoords,
                                                 int[] indices) {
        int vertexCount = vertices.length / 3;
        int[] owner = new int[vertexCount];
        java.util.Arrays.fill(owner, -1);
        int[] rewritten = null;
        List<Integer> extraSources = new ArrayList<>();
        Map<Long, Integer> copies = new HashMap<>();
        for (int p = 0; p < parts.size(); p++) {
            for (EntityFace f : parts.get(p).faces()) {
                int end = Math.min(indices.length, f.indexStart() + f.indexCount());
                for (int i = Math.max(0, f.indexStart()); i < end; i++) {
                    int v = indices[i];
                    if (v < 0 || v >= vertexCount) continue;
                    if (owner[v] < 0) {
                        owner[v] = p;
                    } else if (owner[v] != p) {
                        if (rewritten == null) rewritten = indices.clone();
                        long key = ((long) v << 32) | p;
                        Integer copy = copies.get(key);
                        if (copy == null) {
                            copy = vertexCount + extraSources.size();
                            extraSources.add(v);
                            copies.put(key, copy);
                        }
                        rewritten[i] = copy;
                    }
                }
            }
        }
        if (rewritten == null) return new SplitMesh(vertices, texCoords, indices);
        logger.debug("Split {} vertices shared between parts", extraSources.size());
        int total = vertexCount + extraSources.size();
        float[] v2 = java.util.Arrays.copyOf(vertices, total * 3);
        boolean hasUv = texCoords.length >= vertexCount * 2;
        float[] t2 = hasUv ? java.util.Arrays.copyOf(texCoords, total * 2) : texCoords;
        for (int k = 0; k < extraSources.size(); k++) {
            int src = extraSources.get(k);
            int dst = vertexCount + k;
            System.arraycopy(vertices, src * 3, v2, dst * 3, 3);
            if (hasUv) System.arraycopy(texCoords, src * 2, t2, dst * 2, 2);
        }
        return new SplitMesh(v2, t2, rewritten);
    }

    /**
     * Lowest model-space Y across all rendered (indexed) vertices. Vertices are
     * stored in rest pose, so this is the resting height of the model's feet
     * relative to its origin — the value renderers use to ground-anchor mobs.
     */
    private static float computeRestMinY(float[] vertices, int[] indices) {
        float min = Float.POSITIVE_INFINITY;
        for (int index : indices) {
            float y = vertices[index * 3 + 1];
            if (y < min) {
                min = y;
            }
        }
        return min == Float.POSITIVE_INFINITY ? 0f : min;
    }

    /**
     * Group triangles by face id into contiguous index ranges. {@code value[0]}
     * is the first index, {@code value[1]} the index count.
     */
    private static Map<Integer, int[]> buildFaceIndexRanges(int[] triangleToFaceId) {
        Map<Integer, int[]> ranges = new HashMap<>();
        if (triangleToFaceId == null) return ranges;
        for (int triangle = 0; triangle < triangleToFaceId.length; triangle++) {
            int faceId = triangleToFaceId[triangle];
            int[] range = ranges.get(faceId);
            if (range == null) {
                ranges.put(faceId, new int[]{triangle * 3, 3});
            } else {
                range[1] += 3;
            }
        }
        return ranges;
    }

    /**
     * Decode a PNG into a tightly-packed RGBA8 buffer, rows in source order
     * (top row first) — the texture is uploaded exactly as authored and the
     * OMO's texture coordinates are used verbatim.
     */
    private static MaterialImage decodePng(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) {
            throw new IOException("Unsupported material PNG data");
        }
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] rgba = new byte[w * h * 4];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            image.getRGB(0, y, w, 1, row, 0, w);
            int base = y * w * 4;
            for (int x = 0; x < w; x++) {
                int argb = row[x];
                int o = base + x * 4;
                rgba[o]     = (byte) ((argb >> 16) & 0xFF); // R
                rgba[o + 1] = (byte) ((argb >> 8) & 0xFF);  // G
                rgba[o + 2] = (byte) (argb & 0xFF);         // B
                rgba[o + 3] = (byte) ((argb >> 24) & 0xFF); // A
            }
        }
        return new MaterialImage(w, h, rgba);
    }
}
