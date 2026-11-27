#pragma once

// Model import, PARSE half (OpenMason #173): glTF 2.0 (.gltf / .glb) and Wavefront OBJ (+ MTL) read into one
// in-memory Scene. Turning a Scene into an OMO document (parts, bones, layout, axis / unit conversion) is the
// writer's job; nothing here converts coordinates, units, winding or UV origin. Values are reported AS AUTHORED.
//
// Conventions shared by both readers
//   * Every index into a Scene vector is an int, -1 = none. All indices that come out of a reader are valid
//     (in range); a file that names an out-of-range object is refused with format_error.
//   * Matrices are 4x4, COLUMN-MAJOR (glTF order): m[12], m[13], m[14] are the translation.
//   * Quaternions are x, y, z, w (glTF order).
//   * Axes / units: glTF is +Y up, +Z forward (the model faces +Z), metres, counter-clockwise front faces.
//     OBJ has no declared convention; Blender's OBJ exporter writes +Y up by default, units as modelled.
//     Scene::format and Scene::uv_origin tell the writer which convention it got.
//   * UVs: glTF's (0,0) is the image's TOP-left (v down); OBJ's (0,0) is the BOTTOM-left (v up). Not flipped here.
//   * extras: the object's raw glTF "extras" JSON text exactly as cgltf copied it (an object, array, ...), or ""
//     when absent. OBJ never has extras.
//   * Readers never throw anything but format_error for bad input (plus std::bad_alloc, and whatever the Resolver
//     itself throws, which is propagated unchanged). Recoverable oddities (an unsupported optional extension, a
//     points primitive, a missing texture file, an image that is not PNG/JPEG) are recorded in Scene::warnings.
//     Bounds are checked by the reader itself (overflow-safe) before cgltf_validate runs; accessors are capped at
//     2^24 elements / 2^26 floats; index and sparse-index data must be aligned to their component size (a glTF rule);
//     other misaligned data is tolerated. Under ASan/UBSan, 200k mutated fixtures produced only format_error.
//
// glTF support
//   Core 2.0 (JSON .gltf with data: URIs or external files, binary .glb with its BIN chunk), sparse accessors
//   (resolved to dense everywhere), every TEXCOORD_n, COLOR_0, every JOINTS_n/WEIGHTS_n pair, morph targets
//   (POSITION / NORMAL / TANGENT deltas), '_'-prefixed custom attributes, skins, animations (all paths and
//   interpolations), the default scene. Extensions: KHR_mesh_quantization (int accessors read as float,
//   normalized or not) and KHR_texture_transform are supported. A REQUIRED extension outside that set is refused
//   (format_error); an optional unknown one is ignored with a warning (e.g. KHR_materials_* beyond the core
//   material, KHR_lights_punctual, KHR_draco_mesh_compression / EXT_meshopt_compression when the file still has
//   uncompressed fallback data); cameras / lights are ignored with a warning. Points / lines primitives are skipped
//   with a warning. Buffer URIs with a scheme ("http://...") are refused; external files go through the Resolver.
//
// OBJ support
//   v (xyz, optional w ignored, optional rgb vertex colour), vt (u v, optional w ignored), vn, f (any polygon size;
//   v, v/vt, v//vn, v/vt/vn; negative = relative indices), o, g, s (off / 0 / on / n), usemtl, mtllib, line
//   continuation '\'. MTL: newmtl, Kd, d, Tr, Ke, map_Kd (options such as -s / -o / -bm are skipped). Ignored
//   silently: comments, vp, l, p, cstype & friends, Ka, Ks, Ns, Ni, illum and other map_* statements.

#include "omformat/zip_archive.hpp"  // format_error

#include <array>
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <functional>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace cenda::omformat::import {

using Float2 = std::array<float, 2>;
using Float3 = std::array<float, 3>;
using Float4 = std::array<float, 4>;
using Mat4 = std::array<float, 16>;  // column-major

inline constexpr Mat4 kIdentity{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

// Fetches a file the model refers to by URI (glTF buffer / image, OBJ mtllib, MTL map_Kd). The argument is the
// RELATIVE reference as written in the file, percent-decoded for glTF (a "data:" URI never reaches the resolver);
// OBJ / MTL references have '\\' turned into '/' (Windows exporters).
// Return std::nullopt when the file does not exist: a missing glTF buffer is then a format_error, a missing image
// or MTL a warning. An empty Resolver (no target) resolves nothing.
using Resolver = std::function<std::optional<std::vector<std::byte>>(std::string_view uri)>;

// A Resolver reading files under `dir` (the folder of the model file). Refuses absolute references and any ".."
// component (returns std::nullopt), so a model cannot read outside its folder. For the tool and tests; the readers
// themselves never open files.
Resolver directory_resolver(std::filesystem::path dir);

enum class SourceFormat : std::uint8_t { Gltf, Glb, Obj };
enum class UvOrigin : std::uint8_t { TopLeft, BottomLeft };  // glTF: TopLeft, OBJ: BottomLeft
enum class AlphaMode : std::uint8_t { Opaque, Mask, Blend };

// ---- images -------------------------------------------------------------------------------------------------

struct Image {
    std::string name;   // glTF image name; OBJ: the map_Kd reference
    std::string uri;    // the external reference ("" for data: URIs and buffer views)
    std::string mime;   // "image/png" / "image/jpeg" from the BYTES' signature; else as declared; "" unknown
    std::vector<std::byte> bytes;  // the original file bytes, untouched (empty when missing)
    // The image as PNG: `bytes` itself for a PNG, a decoded + re-encoded copy for a JPEG (8 bit, `channels`
    // channels, deflated by miniz). Empty when the image is missing or not PNG / JPEG (warning recorded).
    std::vector<std::byte> png;
    int width = 0, height = 0;
    int channels = 0;  // 1 grey, 2 grey+alpha, 3 RGB, 4 RGBA (of `png`)
    bool missing = false;  // the resolver did not find the file
};

// ---- materials ----------------------------------------------------------------------------------------------

struct TextureTransform {  // KHR_texture_transform, as authored
    Float2 offset{0.f, 0.f};
    float rotation = 0.f;  // radians
    Float2 scale{1.f, 1.f};
    int texcoord = -1;     // the extension's texCoord override, -1 = none (TextureRef::texcoord applies)
};

struct TextureRef {
    int image = -1;     // Scene::images (-1: the texture has no image glTF could name, e.g. only a webp source)
    int texcoord = 0;   // which TEXCOORD_n set
    std::optional<TextureTransform> transform;
    // Sampler (GL enums as in glTF; 0 = undefined filter). Wrap defaults to REPEAT (10497).
    int mag_filter = 0, min_filter = 0;
    int wrap_s = 10497, wrap_t = 10497;
};

struct Material {
    std::string name;
    Float4 base_color{1.f, 1.f, 1.f, 1.f};  // glTF baseColorFactor (linear); OBJ: Kd + d
    std::optional<TextureRef> base_color_texture;  // glTF baseColorTexture; OBJ: map_Kd
    float metallic = 1.f, roughness = 1.f;  // glTF defaults; OBJ: metallic 0, roughness 1
    AlphaMode alpha_mode = AlphaMode::Opaque;  // OBJ: Blend when d < 1
    float alpha_cutoff = 0.5f;
    bool double_sided = false;
    Float3 emissive{0.f, 0.f, 0.f};  // glTF emissiveFactor; OBJ: Ke
    std::string extras;
};

// ---- meshes -------------------------------------------------------------------------------------------------

// Skin influences of every vertex, CSR: vertex v's influences are [offsets[v], offsets[v+1]) of joints/weights.
// joints are indices into the SKIN's joint list (Skin::joints), not node indices. glTF: every JOINTS_n/WEIGHTS_n
// pair merged in set order (n = 0, 1, ...; component order kept), zero weights dropped; not renormalised.
// Empty (offsets empty) when the primitive has no skin attributes.
struct Influences {
    std::vector<std::uint32_t> offsets;  // vertex_count + 1 entries, or empty
    std::vector<std::uint32_t> joints;
    std::vector<float> weights;
    bool empty() const { return offsets.empty(); }
};

// One morph target of one primitive: dense per-vertex deltas (sparse accessors resolved). An attribute the target
// does not move is empty.
struct MorphTarget {
    std::vector<Float3> positions;
    std::vector<Float3> normals;
    std::vector<Float3> tangents;  // xyz deltas
};

// A '_'-prefixed glTF vertex attribute (e.g. Blender's vertex group export "_COVERED_JACKET"): vertex_count x
// components floats (normalised ints mapped to [0,1] / [-1,1], plain ints converted).
struct CustomAttribute {
    std::string name;  // with the leading '_'
    int components = 1;  // 1 (SCALAR) .. 4 (VEC4), 16 for MAT4 etc.
    std::vector<float> values;
};

struct Primitive {
    std::vector<Float3> positions;  // vertex_count = positions.size()
    std::vector<Float3> normals;    // empty or vertex_count
    std::vector<Float4> tangents;   // empty or vertex_count (xyz + handedness w)
    std::vector<std::vector<Float2>> texcoords;  // texcoords[n] = TEXCOORD_n (each empty or vertex_count)
    std::vector<Float4> colors;     // COLOR_0 as RGBA (alpha 1 for a VEC3 colour); OBJ: "v x y z r g b"; or empty
    Influences influences;
    // Polygons: face_sizes[f] corners each, their vertex indices back to back in `indices`
    // (sum(face_sizes) == indices.size()). glTF: triangles only (strips / fans converted, their degenerate
    // triangles dropped; no index accessor = 0..n-1). OBJ: the polygons as written (quads, n-gons), never
    // triangulated. Winding as authored.
    std::vector<std::uint32_t> indices;
    std::vector<std::uint32_t> face_sizes;
    // OBJ only (else empty): face_smoothing[f] = the face's smoothing group ("s"), 0 = off.
    std::vector<std::uint32_t> face_smoothing;
    // OBJ only (else empty): source_vertex[v] = the 0-based OBJ "v" line vertex v was made from. Corners that share
    // an OBJ position but differ in vt / vn become separate vertices; this lets the writer weld them again.
    std::vector<std::uint32_t> source_vertex;
    int material = -1;  // Scene::materials
    std::vector<MorphTarget> targets;  // same count in every primitive of a mesh
    std::vector<CustomAttribute> custom;  // in file order
    std::string group;  // OBJ: the "g" statement in effect ("" none); glTF: ""
    std::string extras;
};

struct Mesh {
    std::string name;
    std::vector<Primitive> primitives;
    std::vector<float> weights;  // default morph weights, one per target (0 when not authored)
    std::vector<std::string> target_names;  // extras.targetNames (Blender's shape key names), one per target or empty
    std::string extras;
};

// ---- nodes, skins ---------------------------------------------------------------------------------------------

struct Node {
    std::string name;
    int parent = -1;
    std::vector<int> children;
    // The local transform AS GIVEN: TRS components the file wrote, and/or a matrix.
    std::optional<Float3> translation;
    std::optional<Float4> rotation;  // xyzw
    std::optional<Float3> scale;
    std::optional<Mat4> matrix;
    Mat4 local = kIdentity;  // resolved: matrix if given, else T * R * S (missing components = identity)
    int mesh = -1;
    int skin = -1;
    std::vector<float> weights;  // node-level morph weight override (empty = the mesh's)
    std::string extras;
};

struct Skin {
    std::string name;
    std::vector<int> joints;  // node indices; Influences::joints index this list
    std::vector<Mat4> inverse_bind_matrices;  // one per joint (identity when the file has none)
    int skeleton = -1;  // the "skeleton" root node, -1 when absent
    std::string extras;
};

// ---- animations -----------------------------------------------------------------------------------------------

enum class AnimPath : std::uint8_t { Translation, Rotation, Scale, Weights };
enum class Interpolation : std::uint8_t { Linear, Step, CubicSpline };

struct AnimSampler {
    std::vector<float> input;  // key times, seconds
    // Key values flattened, `components` floats per value: translation/scale 3, rotation 4 (xyzw), weights = the
    // mesh's target count. CubicSpline keys are [in-tangent, value, out-tangent] per key (glTF order), so
    // output.size() == input.size() * components * (CubicSpline ? 3 : 1).
    std::vector<float> output;
    int components = 0;
    Interpolation interpolation = Interpolation::Linear;
};

struct AnimChannel {
    int sampler = -1;  // Animation::samplers
    int node = -1;     // target node
    AnimPath path = AnimPath::Translation;
};

struct Animation {
    std::string name;
    std::vector<AnimChannel> channels;  // channels without a target node are dropped (warning)
    std::vector<AnimSampler> samplers;
    std::string extras;
};

// ---- the scene ------------------------------------------------------------------------------------------------

struct Scene {
    SourceFormat format = SourceFormat::Gltf;
    UvOrigin uv_origin = UvOrigin::TopLeft;
    std::string generator;  // glTF asset.generator ("" for OBJ)
    std::string version;    // glTF asset.version
    std::string copyright;
    std::string extras;     // the glTF root object's "extras"

    std::vector<Node> nodes;
    std::vector<int> roots;  // root nodes of the default scene (glTF "scene", else scenes[0], else every
                             // parentless node); OBJ: one root node per object
    std::vector<Mesh> meshes;
    std::vector<Skin> skins;
    std::vector<Material> materials;
    std::vector<Image> images;
    std::vector<Animation> animations;
    std::vector<std::string> warnings;
};

// glTF 2.0: .glb (detected by its magic) or .gltf JSON. Buffers / images given as data: URIs are decoded here;
// other URIs are fetched through `resolve`. Throws format_error on malformed or invalid input (cgltf_validate plus
// this reader's own bounds checks), a missing buffer, or a required extension it does not support.
Scene read_gltf(std::span<const std::byte> bytes, const Resolver& resolve);

// Wavefront OBJ text. mtllib files and map_Kd textures are fetched through `resolve`. OBJ produces one node + mesh
// per "o" object (faces before any "o" go to an unnamed object), each mesh split into primitives by (g, usemtl) in
// order of first use; nodes have identity transforms. Throws format_error on malformed numbers or statements,
// an index 0 or out of range, or a face referring to a vt / vn that does not exist.
Scene read_obj(std::string_view obj_text, const Resolver& resolve);

}  // namespace cenda::omformat::import
