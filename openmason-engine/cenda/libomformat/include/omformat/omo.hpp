#pragma once

// .omo — Open Mason Object, the engine's model format (native codec: OpenMason #167; 1.9 sections: #168).
//
// A zip archive, written in this entry order:
//   manifest.json        required, the model (below)
//   texture.omt          the base texture: itself a zip (an .omt, see namespace omt below), kept verbatim
//   material_<id>.png    per-face material images (OMO 1.2+), named by the materials' textureFile, kept verbatim
//                        and in their archive order
//   anything else        kept verbatim on a round trip
//
// manifest.json is what the Java writer (openmason-tool OMOSerializer, Jackson INDENT_OUTPUT) produces, and the
// native writer reproduces it byte for byte: Jackson's DefaultPrettyPrinter layout (2-space object indent,
// "key" : value, inline arrays "[ a, b ]", empty "[ ]" / "{ }", no trailing newline; the line separator of the
// file that was read, LF by default), floats in Java's Float.toString shortest form ("1.0", "0.010000001",
// "1.0E-4"), doubles in Double.toString form, non-finite numbers as the strings "NaN" / "Infinity".
// Keys in the order the writer emits them:
//   version          "1.8" (1.0 .. 1.x read; a newer MAJOR is refused with version_error). Compared as integer
//                    major.minor ("1.10" > "1.9"). The writer keeps the document's version, raised to "1.9" when
//                    any 1.9 section below is present.
//   objectName, modelType   strings
//   geometry         {width, height, depth (ints), position {x, y, z (doubles)}}
//   textureFile      string (the base texture's entry; readers load "texture.omt")
//   mesh             null | {vertices [x,y,z...] floats, texCoords [u,v...] floats, indices [] ints,
//                    triangleToFaceId [] ints, uvMode string}; each member may be null. The soup: 1 vertex per
//                    face corner, the "soup vertex index" every per-vertex section below refers to.
//   faceTextures     null | {mappings [{faceId, materialId, u0, v0, u1, v1, uvRotationDegrees, autoResize}],
//                    materials [{materialId, name, textureFile, renderLayer, emissive, tintColor, baseColor?}]}
//                    (1.9: baseColor [r, g, b, a] floats, optional: a material may have no image)
//   parts            null (= none) | [{id, name, originX..Z, posX..Z, rotX..Z (Euler XYZ degrees), scaleX..Z,
//                    vertexStart, vertexCount, indexStart, indexCount, faceStart, faceCount, visible, locked,
//                    parentId (a part or bone id; null = root), boneId (legacy, null = unbound)}]
//   modelTransform   null (= identity) | {posX..Z, rotX..Z, scaleX..Z}
//   bones            null (= none) | [{id, name, parentBoneId, originX..Z, posX..Z, rotX..Z, endpointX..Z}]
//   attachmentPoints null (= none) | [{id, name, parentPartId, parentPartName, posX..Z, rotX..Z, scaleX..Z}]
//   skin             null | {joints [ids], parts [{partId, vertices [[localVertex, j0, w0, j1, w1, ...]]}]}
//                    (1.8; localVertex = saved vertex - part.vertexStart; j = index into joints)
//   --- OMO 1.9 (#168), each key left out when empty ---
//   shading          {smooth, sharpEdges, autoSmoothAngle, customNormals}, each member left out when empty:
//                      smooth           [[firstFace, count], ...]: the face ids that shade smooth, as sorted
//                                       maximal ranges (Blender's sharp_face, inverted); absent = all flat.
//                                       In memory: a bitset, bit f%32 of word f/32.
//                      sharpEdges       [[a, b], ...] soup vertex index pairs (survive weld-by-position)
//                      autoSmoothAngle  degrees
//                      customNormals    [[v, ox, oy], ...] sparse rows: soup vertex v's normal, octahedral
//                                       encoded, snorm16 x 2
//   vertexGroups     [{name, parts [{partId, vertices [[localVertex, weight], ...]}]}]   (skin's row layout)
//   morphs           [{name, defaultWeight, min, max, parts [{partId, deltas [[localVertex, dx, dy, dz] or
//                    [localVertex, dx, dy, dz, nx, ny, nz]]}]}]   shape keys: sparse deltas from the basis;
//                    a part's rows all carry normal deltas or none
//   <other keys>     unknown top-level sections, kept on a round trip (numbers verbatim), written last
//
// Reading follows the Java readers' defaults: a missing part/bone/socket float reads 0 (scales 1), a missing
// autoResize/visible reads true, a missing tintColor -1 (0xFFFFFFFF); parts/bones/sockets without an id and
// malformed skin rows (wrong length, joint out of range) are skipped; a skin without weighted vertices reads
// as none. Unknown keys inside known records are dropped.

#include "omformat/visibility.hpp"
#include "omformat/zip_archive.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace cenda::omformat::omo {

inline constexpr int kVersionMajor = 1;
inline constexpr int kVersionMinor = 9;
inline constexpr const char* kManifestEntry = "manifest.json";
inline constexpr const char* kTextureEntry = "texture.omt";
inline constexpr const char* kDefaultVersion = "1.8";  // what a new document (and the Java writer) writes

// A file whose major version is newer than this reader.
class OMFORMAT_EXCEPTION_TYPE version_error final : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

struct Version {
    int major = 0;
    int minor = 0;
    auto operator<=>(const Version&) const = default;
};

// "<major>.<minor>" (both non-negative integers); nullopt when malformed.
std::optional<Version> parse_version(std::string_view text);
// Integer major.minor comparison (<0, 0, >0). Throws format_error when either is malformed.
int compare_versions(std::string_view a, std::string_view b);

struct Vec3 {
    float x = 0.f;
    float y = 0.f;
    float z = 0.f;
    bool operator==(const Vec3&) const = default;
};

struct Position {
    double x = 0.0;
    double y = 0.0;
    double z = 0.0;
    bool operator==(const Position&) const = default;
};

struct Geometry {
    std::int32_t width = 16;
    std::int32_t height = 16;
    std::int32_t depth = 16;
    Position position;
    bool operator==(const Geometry&) const = default;
};

// Every member may be null (absent), exactly as in the Java MeshData.
struct Mesh {
    std::optional<std::vector<float>> vertices;              // x, y, z per soup vertex
    std::optional<std::vector<float>> tex_coords;            // u, v per soup vertex
    std::optional<std::vector<std::int32_t>> indices;        // 3 per triangle
    std::optional<std::vector<std::int32_t>> triangle_to_face_id;
    std::optional<std::string> uv_mode;
    bool operator==(const Mesh&) const = default;
};

struct FaceMapping {
    std::int32_t face_id = 0;
    std::int32_t material_id = 0;
    float u0 = 0.f;
    float v0 = 0.f;
    float u1 = 1.f;
    float v1 = 1.f;
    std::int32_t uv_rotation_degrees = 0;
    bool auto_resize = true;
    bool operator==(const FaceMapping&) const = default;
};

struct Material {
    std::int32_t material_id = 0;
    std::optional<std::string> name;
    std::optional<std::string> texture_file;  // "material_<id>.png"; names an Image
    std::optional<std::string> render_layer;  // OPAQUE | CUTOUT | TRANSLUCENT
    bool emissive = false;
    std::int32_t tint_color = -1;  // 0xRRGGBBAA as a signed int (Java int)
    std::optional<std::array<float, 4>> base_color;  // 1.9: RGBA
    bool operator==(const Material&) const = default;
};

struct FaceTextures {
    std::vector<FaceMapping> mappings;
    std::vector<Material> materials;
    bool operator==(const FaceTextures&) const = default;
};

struct Part {
    std::string id;
    std::string name;
    Vec3 origin;
    Vec3 pos;
    Vec3 rot;
    Vec3 scale{1.f, 1.f, 1.f};
    std::int32_t vertex_start = 0;
    std::int32_t vertex_count = 0;
    std::int32_t index_start = 0;
    std::int32_t index_count = 0;
    std::int32_t face_start = 0;
    std::int32_t face_count = 0;
    bool visible = true;
    bool locked = false;
    std::optional<std::string> parent_id;  // a part or bone id; nullopt = root
    std::optional<std::string> bone_id;    // legacy (1.6), not read by the runtime
    bool operator==(const Part&) const = default;
};

struct Bone {
    std::string id;
    std::string name;
    std::optional<std::string> parent_bone_id;  // a bone or part id; nullopt = root
    Vec3 origin;
    Vec3 pos;
    Vec3 rot;
    Vec3 endpoint;
    bool operator==(const Bone&) const = default;
};

struct AttachmentPoint {
    std::string id;
    std::string name;
    std::optional<std::string> parent_part_id;
    std::optional<std::string> parent_part_name;
    Vec3 pos;
    Vec3 rot;
    Vec3 scale{1.f, 1.f, 1.f};
    bool operator==(const AttachmentPoint&) const = default;
};

struct ModelTransform {
    Vec3 pos;
    Vec3 rot;
    Vec3 scale{1.f, 1.f, 1.f};
    [[nodiscard]] bool identity() const { return *this == ModelTransform{}; }
    bool operator==(const ModelTransform&) const = default;
};

// One part's weighted vertices, CSR: row r = vertex vertices[r] with influences
// [offsets[r], offsets[r+1]) of joints/weights. offsets has rows()+1 entries (empty = no rows).
struct SkinPart {
    std::string part_id;
    std::vector<std::int32_t> vertices;  // part-local vertex per row
    std::vector<std::int32_t> offsets;
    std::vector<std::int32_t> joints;    // indices into Skin::joints
    std::vector<float> weights;
    [[nodiscard]] std::size_t rows() const { return vertices.size(); }
    bool operator==(const SkinPart&) const = default;
};

struct Skin {
    std::vector<std::string> joints;  // part or bone ids
    std::vector<SkinPart> parts;
    // True when no vertex carries weights (written as null).
    [[nodiscard]] bool empty() const;
    bool operator==(const Skin&) const = default;
};

struct Shading {
    std::vector<std::uint32_t> smooth;         // bitset over face ids, trailing zero words trimmed on read
    std::vector<std::uint32_t> sharp_edges;    // soup vertex index pairs, 2 per edge
    std::optional<float> auto_smooth_angle;    // degrees
    std::vector<std::int32_t> normal_vertices; // custom normals: soup vertex per row
    std::vector<std::int16_t> normal_oct;      // 2 per row (octahedral snorm16 x, y)
    [[nodiscard]] bool empty() const;
    [[nodiscard]] bool smooth_face(std::uint32_t face) const;
    void set_smooth_face(std::uint32_t face, bool smooth);
    bool operator==(const Shading&) const = default;
};

// Weighted part-local vertices of one part (same layout as a skin row with one influence).
struct GroupPart {
    std::string part_id;
    std::vector<std::int32_t> vertices;
    std::vector<float> weights;
    bool operator==(const GroupPart&) const = default;
};

struct VertexGroup {
    std::string name;
    std::vector<GroupPart> parts;
    bool operator==(const VertexGroup&) const = default;
};

struct MorphPart {
    std::string part_id;
    std::vector<std::int32_t> vertices;  // part-local vertex per row
    std::vector<float> deltas;           // 3 per row
    std::vector<float> normals;          // 3 per row, or empty (no normal deltas in this part)
    bool operator==(const MorphPart&) const = default;
};

struct Morph {
    std::string name;
    float default_weight = 0.f;
    float min = 0.f;
    float max = 1.f;
    std::vector<MorphPart> parts;
    bool operator==(const Morph&) const = default;
};

// An archive entry kept as bytes (material images, unknown entries).
struct Image {
    std::string name;
    std::vector<std::byte> data;
    bool operator==(const Image&) const = default;
};

struct UnknownSection {
    std::string key;
    std::string json;  // the value, minified JSON; numbers keep their original text
    bool operator==(const UnknownSection&) const = default;
};

struct Document {
    std::string version = kDefaultVersion;
    std::string object_name;
    std::string model_type;
    Geometry geometry;
    std::string texture_file = kTextureEntry;
    std::optional<Mesh> mesh;
    std::optional<FaceTextures> face_textures;
    std::vector<Part> parts;
    ModelTransform model_transform;
    std::vector<Bone> bones;
    std::vector<AttachmentPoint> attachment_points;
    Skin skin;
    // OMO 1.9.
    Shading shading;
    std::vector<VertexGroup> vertex_groups;
    std::vector<Morph> morphs;

    std::vector<UnknownSection> unknown_sections;

    std::vector<std::byte> texture;  // the texture.omt entry verbatim (empty = absent)
    std::vector<Image> images;       // material_*.png entries, archive order
    std::vector<Image> extra_entries;
    bool crlf = false;  // manifest line separator (files written by the Java tool on Windows use CRLF)

    // True when any OMO 1.9 data is present (the written version is then at least 1.9).
    [[nodiscard]] bool has_1_9_data() const;
    // The version write() puts in the manifest.
    [[nodiscard]] std::string written_version() const;
    // The material image entry with this name, or nullptr.
    [[nodiscard]] const Image* image(std::string_view name) const;

    bool operator==(const Document&) const = default;
};

// Parses an archive. Throws format_error (corrupt archive, missing or bad manifest, duplicate top-level key)
// or version_error (newer major version).
Document read(std::span<const std::byte> archive);

// The manifest.json text of a document, exactly as write() stores it.
std::string write_manifest(const Document& document);

// Serializes. Deterministic. Throws format_error on an invalid version, an unknown section that is not valid
// JSON, malformed CSR data (offsets / row lengths) or colliding entry names.
std::vector<std::byte> write(const Document& document);

// Java Float.toString / Double.toString (JDK 19+ shortest-decimal) text of a finite value; what the Java
// writer puts in a manifest.
std::string java_float(float value);
std::string java_double(double value);

// ------------------------------------------------------------------------------------------------------
// .omt — Open Mason Texture (the base texture embedded in an .omo as texture.omt). A zip archive:
//   manifest.json   {version "1.0", canvasSize {width, height}, layers [{name, visible, opacity, dataFile}],
//                    activeLayerIndex, faceMappings [{faceId, materialId, u0, v0, u1, v1, uvRotation}],
//                    materials [{materialId, name, renderLayer, textureFile}]}   (Jackson layout, as above)
//   layer_<i>.png   one PNG per layer, named by its dataFile (raw bytes; never decoded here)
//   anything else   (material_*.png, unknown) kept verbatim, in archive order
namespace omt {

struct Layer {
    std::string name;
    bool visible = true;
    float opacity = 1.f;
    std::string data_file;        // "layer_<i>.png"
    std::vector<std::byte> png;   // empty when the archive lacks the entry
    bool operator==(const Layer&) const = default;
};

struct FaceMapping {
    std::int32_t face_id = 0;
    std::int32_t material_id = 0;
    float u0 = 0.f;
    float v0 = 0.f;
    float u1 = 1.f;
    float v1 = 1.f;
    std::int32_t uv_rotation = 0;
    bool operator==(const FaceMapping&) const = default;
};

struct Material {
    std::int32_t material_id = 0;
    std::string name;
    std::string render_layer;
    std::string texture_file;
    bool operator==(const Material&) const = default;
};

struct Texture {
    std::string version = "1.0";
    std::int32_t width = 16;
    std::int32_t height = 16;
    std::vector<Layer> layers;
    std::int32_t active_layer = 0;
    std::vector<FaceMapping> face_mappings;
    std::vector<Material> materials;
    std::vector<UnknownSection> unknown_sections;
    std::vector<Image> other_entries;
    bool crlf = false;
    bool operator==(const Texture&) const = default;
};

// Throws format_error on a corrupt archive or manifest.
Texture read(std::span<const std::byte> archive);
std::string write_manifest(const Texture& texture);
std::vector<std::byte> write(const Texture& texture);

}  // namespace omt

}  // namespace cenda::omformat::omo
