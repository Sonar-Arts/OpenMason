// import ctest (OpenMason #173): glTF 2.0 + OBJ readers into omformat::import::Scene. Fixtures (and the script that
// generates them, make_fixtures.py) live in tests/fixtures/import; every expected value below is written there.

#include "omformat/import.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <functional>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

namespace im = cenda::omformat::import;
using cenda::omformat::format_error;

int failures = 0;

void check(bool ok, const std::string& what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", what.c_str());
        ++failures;
    }
}

bool near(float a, float b, float eps = 1e-6f) { return std::fabs(a - b) <= eps; }

template <std::size_t N>
bool same(const std::array<float, N>& a, std::initializer_list<float> b, float eps = 1e-6f) {
    if (b.size() != N) return false;
    std::size_t i = 0;
    for (float v : b) {
        if (!near(a[i++], v, eps)) return false;
    }
    return true;
}

template <class T>
bool same(const std::vector<T>& a, std::initializer_list<T> b) {
    return a == std::vector<T>(b);
}

bool same_f(const std::vector<float>& a, std::initializer_list<float> b, float eps = 1e-6f) {
    if (a.size() != b.size()) return false;
    std::size_t i = 0;
    for (float v : b) {
        if (!near(a[i++], v, eps)) return false;
    }
    return true;
}

bool has_warning(const im::Scene& s, const std::string& part) {
    for (const auto& w : s.warnings) {
        if (w.find(part) != std::string::npos) return true;
    }
    return false;
}

const std::string kDir = CENDA_IMPORT_FIXTURES;

std::vector<std::byte> load(const std::string& name) {
    std::ifstream in(kDir + "/" + name, std::ios::binary);
    if (!in) {
        std::fprintf(stderr, "missing fixture %s\n", name.c_str());
        std::exit(2);
    }
    std::vector<char> raw((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    std::vector<std::byte> out(raw.size());
    for (std::size_t i = 0; i < raw.size(); ++i) out[i] = static_cast<std::byte>(raw[i]);
    return out;
}

std::string text(const std::vector<std::byte>& b) {
    std::string s(b.size(), '\0');
    for (std::size_t i = 0; i < b.size(); ++i) s[i] = static_cast<char>(b[i]);
    return s;
}

const im::Resolver& dir_resolver() {
    static const im::Resolver r = im::directory_resolver(kDir);
    return r;
}

im::Scene gltf(const std::string& name) { return im::read_gltf(load(name), dir_resolver()); }

bool throws_format_error(const std::function<void()>& fn) {
    try {
        fn();
    } catch (const format_error&) {
        return true;
    } catch (const std::exception& e) {
        std::fprintf(stderr, "  wrong exception: %s\n", e.what());
        return false;
    }
    return false;
}

std::uint32_t be32(const std::vector<std::byte>& b, std::size_t at) {
    return (std::to_integer<std::uint32_t>(b[at]) << 24) | (std::to_integer<std::uint32_t>(b[at + 1]) << 16) |
           (std::to_integer<std::uint32_t>(b[at + 2]) << 8) | std::to_integer<std::uint32_t>(b[at + 3]);
}

bool is_png(const std::vector<std::byte>& b) {
    static const unsigned char sig[8] = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    if (b.size() < 33) return false;
    for (std::size_t i = 0; i < 8; ++i) {
        if (std::to_integer<unsigned char>(b[i]) != sig[i]) return false;
    }
    return true;
}

// ---- glTF -----------------------------------------------------------------------------------------------------

void test_skin_strip() {
    const im::Scene s = gltf("skin_strip.gltf");
    check(s.format == im::SourceFormat::Gltf && s.uv_origin == im::UvOrigin::TopLeft, "skin: format");
    check(s.generator == "Khronos glTF Blender I/O v4.2.70", "skin: generator");
    check(s.warnings.empty(), "skin: no warnings (KHR_mesh_quantization is supported)");
    check(s.nodes.size() == 4 && same(s.roots, {0, 1}), "skin: nodes / roots");
    check(s.nodes[0].mesh == 0 && s.nodes[0].skin == 0, "skin: mesh node");
    check(s.nodes[2].parent == 1 && same(s.nodes[2].children, {3}) && s.nodes[3].parent == 2, "skin: bone chain");
    check(s.nodes[3].translation && same(*s.nodes[3].translation, {0.f, 1.5f, 0.f}) && near(s.nodes[3].local[13], 1.5f) &&
              !s.nodes[3].rotation && !s.nodes[3].matrix,
          "skin: bone TRS");
    check(s.skins.size() == 1 && same(s.skins[0].joints, {2, 3}) && s.skins[0].skeleton == 1 &&
              s.skins[0].name == "Armature",
          "skin: joints");
    check(s.skins[0].inverse_bind_matrices.size() == 2 && s.skins[0].inverse_bind_matrices[0] == im::kIdentity &&
              near(s.skins[0].inverse_bind_matrices[1][13], -1.5f) && near(s.skins[0].inverse_bind_matrices[1][0], 1.f),
          "skin: inverse bind matrices");
    const im::Primitive& p = s.meshes[0].primitives[0];
    check(p.positions.size() == 8 && same(p.positions[0], {0, 0, 0}) && same(p.positions[7], {1, 3, 0}),
          "skin: int16 positions (quantized)");
    check(p.indices.size() == 18 && p.face_sizes == std::vector<std::uint32_t>(6, 3u), "skin: 6 triangles");
    check(p.indices[0] == 0 && p.indices[1] == 1 && p.indices[2] == 3 && p.indices[17] == 6, "skin: index values");
    const im::Influences& inf = p.influences;
    check(same(inf.offsets, {0u, 1u, 2u, 8u, 10u, 12u, 18u, 19u, 20u}), "skin: influence offsets (6 on v2 and v5)");
    if (inf.joints.size() != 20) return;
    check(same(std::vector<std::uint32_t>(inf.joints.begin() + 2, inf.joints.begin() + 8), {0u, 1u, 1u, 0u, 1u, 0u}),
          "skin: v2 joints in set order");
    check(same_f(std::vector<float>(inf.weights.begin() + 2, inf.weights.begin() + 8),
                 {0.25f, 0.25f, 0.125f, 0.125f, 51.f / 255.f, 102.f / 255.f}, 0.f),
          "skin: v2 weights (normalized UNSIGNED_BYTE set 1)");
    check(same(std::vector<std::uint32_t>(inf.joints.begin() + 12, inf.joints.begin() + 18), {1u, 0u, 1u, 1u, 0u, 0u}),
          "skin: v5 joints (zero weights dropped)");
    check(same_f(std::vector<float>(inf.weights.begin() + 12, inf.weights.begin() + 18),
                 {0.25f, 0.125f, 0.125f, 51.f / 255.f, 51.f / 255.f, 153.f / 255.f}, 0.f),
          "skin: v5 weights");
    check(inf.joints[18] == 1 && inf.weights[18] == 1.f, "skin: v6 single influence");
    check(p.normals.empty() && p.texcoords.empty() && p.targets.empty(), "skin: no other attributes");
}

void test_morph_cube() {
    const im::Scene s = gltf("morph_cube.gltf");
    check(s.meshes.size() == 1, "morph: one mesh");
    const im::Mesh& m = s.meshes[0];
    check(m.name == "Cube" && same_f(m.weights, {0.25f, 0.75f}, 0.f), "morph: default weights");
    check(m.target_names == std::vector<std::string>{"Smile", "Frown"}, "morph: targetNames");
    check(m.extras.find("\"note\"") != std::string::npos, "morph: mesh extras kept raw");
    const im::Primitive& p = m.primitives[0];
    check(p.indices.size() == 36 && p.face_sizes.size() == 12 && p.indices[1] == 2 && p.indices[35] == 5,
          "morph: UNSIGNED_BYTE indices");
    check(p.targets.size() == 2, "morph: two targets");
    if (p.targets.size() != 2) return;
    check(p.targets[0].positions.size() == 8 && same(p.targets[0].positions[2], {0, 0.5f, 0}) &&
              same(p.targets[0].positions[0], {0, 0, 0}),
          "morph: dense target");
    const auto& t1 = p.targets[1].positions;
    bool sparse_ok = t1.size() == 8 && same(t1[1], {0.25f, 0, 0}) && same(t1[6], {0, 0, -0.75f});
    for (std::size_t i : {0u, 2u, 3u, 4u, 5u, 7u}) sparse_ok = sparse_ok && t1.size() == 8 && same(t1[i], {0, 0, 0});
    check(sparse_ok, "morph: sparse target resolved to dense");
    check(p.targets[0].normals.empty() && p.targets[1].tangents.empty(), "morph: untouched attributes empty");
}

void check_quad(const im::Scene& s, const std::string& tag) {
    check(s.materials.size() == 1, tag + ": one material");
    if (s.materials.size() != 1) return;
    const im::Material& mat = s.materials[0];
    check(mat.name == "Checker" && same(mat.base_color, {0.5f, 0.75f, 1.f, 0.5f}) && mat.metallic == 0.f &&
              mat.roughness == 0.5f,
          tag + ": PBR factors");
    check(mat.alpha_mode == im::AlphaMode::Mask && mat.alpha_cutoff == 0.25f && mat.double_sided &&
              same(mat.emissive, {0.5f, 0.25f, 0.f}),
          tag + ": alpha / sides / emissive");
    check(mat.extras.find("fixture") != std::string::npos, tag + ": material extras");
    check(mat.base_color_texture.has_value(), tag + ": base colour texture");
    if (mat.base_color_texture) {
        const im::TextureRef& t = *mat.base_color_texture;
        check(t.image == 0 && t.texcoord == 0, tag + ": texture image / set");
        check(t.mag_filter == 9728 && t.min_filter == 9728 && t.wrap_s == 33071 && t.wrap_t == 33648,
              tag + ": sampler");
        check(t.transform && same(t.transform->offset, {0.5f, 0.25f}) && t.transform->rotation == 1.5f &&
                  same(t.transform->scale, {2.f, 3.f}) && t.transform->texcoord == 1,
              tag + ": KHR_texture_transform");
    }
    const im::Primitive& p = s.meshes[0].primitives[0];
    check(p.material == 0 && same(p.indices, {0u, 1u, 2u, 0u, 2u, 3u}), tag + ": indices / material");
    check(p.texcoords.size() == 2 && same(p.texcoords[0][1], {1.f, 1.f}) && same(p.texcoords[1][2], {0.5f, 0.5f}),
          tag + ": two UV sets");
    check(p.normals.size() == 4 && same(p.normals[3], {0, 0, 1}), tag + ": normals");
    check(has_warning(s, "KHR_materials_emissive_strength") && !has_warning(s, "KHR_texture_transform"),
          tag + ": optional unknown extension warned, supported one not");
}

void test_textured() {
    const im::Scene png = gltf("textured_png.gltf");
    check_quad(png, "png");
    check(png.images.size() == 1, "png: one image");
    const im::Image& pi = png.images[0];
    check(pi.name == "checker" && pi.uri.empty() && pi.mime == "image/png" && !pi.missing, "png: image fields");
    check(is_png(pi.bytes) && pi.png == pi.bytes, "png: PNG passed through untouched");
    check(pi.width == 2 && pi.height == 2 && pi.channels == 4, "png: size / channels");

    const im::Scene jpg = gltf("textured_jpeg.gltf");
    check_quad(jpg, "jpeg");
    const im::Image& ji = jpg.images[0];
    check(ji.uri == "textures/checker 1.jpg" && ji.mime == "image/jpeg", "jpeg: percent-decoded external uri");
    check(!ji.bytes.empty() && std::to_integer<int>(ji.bytes[0]) == 0xff, "jpeg: original bytes kept");
    check(is_png(ji.png) && be32(ji.png, 16) == 8 && be32(ji.png, 20) == 4 && std::to_integer<int>(ji.png[25]) == 2,
          "jpeg: re-encoded as an 8x4 RGB PNG");
    check(ji.width == 8 && ji.height == 4 && ji.channels == 3, "jpeg: size / channels");

    // The same file with a resolver that finds the buffer but not the image: warning, not an error.
    const im::Resolver no_images = [](std::string_view uri) -> std::optional<std::vector<std::byte>> {
        if (uri == "textured_jpeg.bin") return load("textured_jpeg.bin");
        return std::nullopt;
    };
    const im::Scene missing = im::read_gltf(load("textured_jpeg.gltf"), no_images);
    check(missing.images[0].missing && missing.images[0].png.empty() && has_warning(missing, "checker 1.jpg"),
          "jpeg: missing image = warning");

    const im::Scene glb = gltf("quad.glb");
    check(glb.format == im::SourceFormat::Glb, "glb: format");
    check_quad(glb, "glb");
    check(glb.images.size() == 1 && glb.images[0].bytes == pi.bytes && glb.images[0].png == pi.bytes &&
              glb.images[0].mime == "image/png",
          "glb: image from a buffer view");
    check(glb.meshes[0].primitives[0].positions == png.meshes[0].primitives[0].positions, "glb: same geometry as .gltf");
}

void test_hierarchy() {
    const im::Scene s = gltf("hierarchy.gltf");
    check(same(s.roots, {0, 4}), "hierarchy: default scene (1) roots");
    check(has_warning(s, "default scene"), "hierarchy: multiple scenes warned");
    const im::Node& root = s.nodes[0];
    check(root.name == "Root" && same(root.children, {1, 2}) && root.translation && root.rotation && root.scale &&
              !root.matrix,
          "hierarchy: TRS as given");
    check(root.extras.find("\"root\"") != std::string::npos, "hierarchy: node extras");
    // T(1,2,3) * R(90 deg about +Y) * S(2): columns (0,0,-2), (0,2,0), (2,0,0), (1,2,3).
    check(same(root.local, {0, 0, -2, 0, 0, 2, 0, 0, 2, 0, 0, 0, 1, 2, 3, 1}, 1e-5f), "hierarchy: resolved TRS matrix");
    const im::Node& mc = s.nodes[1];
    check(mc.matrix && !mc.translation && mc.parent == 0, "hierarchy: matrix node");
    check(mc.matrix && mc.local == *mc.matrix && same(mc.local, {1, 0, 0, 0, 0, 0, 1, 0, 0, -1, 0, 0, 5, 6, 7, 1}, 0.f),
          "hierarchy: matrix kept column-major");
    check(s.nodes[2].scale && !s.nodes[2].translation && s.nodes[2].local[5] == 3.f && s.nodes[2].local[0] == 1.f,
          "hierarchy: scale-only node");
    check(s.nodes[3].parent == 2 && s.nodes[3].local == im::kIdentity, "hierarchy: identity leaf");
}

void test_animation() {
    const im::Scene s = gltf("animation.gltf");
    check(s.animations.size() == 1, "anim: one animation");
    if (s.animations.size() != 1) return;
    const im::Animation& a = s.animations[0];
    check(a.name == "Wiggle" && a.samplers.size() == 3 && a.channels.size() == 3, "anim: samplers / channels");
    if (a.samplers.size() != 3 || a.channels.size() != 3) return;
    check(has_warning(s, "no target node"), "anim: targetless channel dropped with a warning");
    const im::AnimSampler& t = a.samplers[0];
    check(t.interpolation == im::Interpolation::Linear && t.components == 3 && same_f(t.input, {0.f, 0.5f, 1.f}, 0.f) &&
              same_f(t.output, {0, 0, 0, 1, 2, 3, 2, 4, 6}, 0.f),
          "anim: LINEAR translation");
    const im::AnimSampler& r = a.samplers[1];
    check(r.interpolation == im::Interpolation::Step && r.components == 4 &&
              same_f(r.output, {0, 0, 0, 1, 0, 1, 0, 0, 0, 0, 1, 0}, 0.f),
          "anim: STEP rotation (normalized SHORT)");
    const im::AnimSampler& w = a.samplers[2];
    check(w.interpolation == im::Interpolation::CubicSpline && w.components == 2 && same_f(w.input, {0.f, 2.f}, 0.f) &&
              same_f(w.output, {0.1f, 0.2f, 0.f, 0.f, 0.3f, 0.4f, 0.5f, 0.6f, 1.f, 0.5f, 0.7f, 0.8f}, 0.f),
          "anim: CUBICSPLINE weights with tangents");
    check(a.channels[0].path == im::AnimPath::Translation && a.channels[0].sampler == 0 && a.channels[0].node == 0 &&
              a.channels[1].path == im::AnimPath::Rotation && a.channels[2].path == im::AnimPath::Weights &&
              a.channels[2].sampler == 2,
          "anim: channel targets");
    const im::Primitive& p = s.meshes[0].primitives[0];
    check(same(p.indices, {0u, 1u, 2u}) && p.targets.size() == 2 && same(p.targets[1].positions[2], {1, 0, 0}),
          "anim: no-index primitive gets 0..n-1");
}

void test_custom() {
    const im::Scene s = gltf("custom_attr.gltf");
    const im::Primitive& p = s.meshes[0].primitives[0];
    check(p.custom.size() == 2, "custom: two '_' attributes");
    if (p.custom.size() == 2) {
        check(p.custom[0].name == "_COVERED_JACKET" && p.custom[0].components == 1 &&
                  same_f(p.custom[0].values, {0.f, 0.5f, 1.f}, 0.f),
              "custom: float attribute");
        check(p.custom[1].name == "_MASK" && same_f(p.custom[1].values, {0.f, 1.f, 51.f / 255.f}, 0.f),
              "custom: normalized byte attribute");
    }
    check(p.colors.size() == 3 && same(p.colors[0], {1, 0, 0, 1}) && same(p.colors[2], {0, 0, 1, 1}),
          "custom: COLOR_0 VEC3 -> RGBA");
    check(p.extras.find("prim") != std::string::npos && same(p.indices, {0u, 1u, 2u}), "custom: primitive extras");
}

void test_strip() {
    const im::Scene s = gltf("strip.gltf");
    const im::Mesh& m = s.meshes[0];
    check(m.primitives.size() == 2 && has_warning(s, "points"), "strip: points primitive skipped with a warning");
    if (m.primitives.size() != 2) return;
    check(same(m.primitives[0].indices, {0u, 1u, 2u, 1u, 3u, 2u, 3u, 4u, 5u}) &&
              m.primitives[0].face_sizes == std::vector<std::uint32_t>(3, 3u),
          "strip: strip -> list (winding kept, degenerates dropped)");
    check(same(m.primitives[1].indices, {2u, 3u, 0u, 3u, 1u, 0u}), "strip: fan -> list");
}

// ---- OBJ ------------------------------------------------------------------------------------------------------

void test_obj() {
    const im::Scene s = im::read_obj(text(load("model.obj")), dir_resolver());
    check(s.format == im::SourceFormat::Obj && s.uv_origin == im::UvOrigin::BottomLeft, "obj: format");
    check(s.materials.size() == 2, "obj: two MTL materials");
    if (s.materials.size() == 2) {
        const im::Material& wood = s.materials[0];
        check(wood.name == "Wood" && same(wood.base_color, {0.8f, 0.6f, 0.4f, 1.f}) &&
                  wood.alpha_mode == im::AlphaMode::Opaque && wood.metallic == 0.f && wood.roughness == 1.f,
              "obj: Kd + d");
        check(wood.base_color_texture && wood.base_color_texture->image == 0, "obj: map_Kd");
        const im::Material& paint = s.materials[1];
        check(paint.name == "Paint" && same(paint.base_color, {0.1f, 0.2f, 0.3f, 0.75f}) &&
                  paint.alpha_mode == im::AlphaMode::Blend && same(paint.emissive, {0.5f, 0.f, 0.f}) &&
                  !paint.base_color_texture,
              "obj: Tr + Ke");
    }
    check(s.images.size() == 1 && s.images[0].name == "tex/wood grain.png" && s.images[0].width == 1 &&
              s.images[0].channels == 3 && s.images[0].png == s.images[0].bytes,
          "obj: texture with options + a space in its name");
    check(s.nodes.size() == 2 && s.meshes.size() == 2 && same(s.roots, {0, 1}) && s.nodes[0].name == "Box" &&
              s.nodes[1].mesh == 1 && s.nodes[1].local == im::kIdentity,
          "obj: one node + mesh per object");
    if (s.meshes.size() != 2) return;
    const im::Mesh& box = s.meshes[0];
    check(box.primitives.size() == 1, "obj: Box has one primitive");
    if (box.primitives.size() != 1) return;
    const im::Primitive& q = box.primitives[0];
    check(q.group == "front" && q.material == 0, "obj: group + material");
    check(same(q.face_sizes, {4u, 3u}) && same(q.indices, {0u, 1u, 2u, 3u, 0u, 2u, 3u}),
          "obj: quad kept + negative indices + line continuation");
    check(same(q.face_smoothing, {1u, 0u}), "obj: smoothing groups");
    check(q.positions.size() == 4 && same(q.positions[2], {1, 1, 0}) && same(q.source_vertex, {0u, 1u, 2u, 3u}),
          "obj: positions + source vertices");
    check(q.texcoords.size() == 1 && q.texcoords[0].size() == 4 && same(q.texcoords[0][2], {1, 1}),
          "obj: texcoords as authored");
    check(q.normals.size() == 4 && same(q.normals[0], {0, 0, 1}), "obj: normals");
    check(q.colors.size() == 4 && same(q.colors[1], {0, 1, 0, 1}), "obj: vertex colours");

    const im::Mesh& panel = s.meshes[1];
    check(panel.name == "Panel" && panel.primitives.size() == 3, "obj: Panel split by material");
    if (panel.primitives.size() != 3) return;
    const im::Primitive& ngon = panel.primitives[0];
    check(ngon.group.empty() && ngon.material == 1 && same(ngon.face_sizes, {5u}) &&
              same(ngon.indices, {0u, 1u, 2u, 3u, 4u}) && same(ngon.face_smoothing, {2u}),
          "obj: pentagon kept as one polygon");
    check(ngon.normals.size() == 5 && ngon.texcoords.empty() && ngon.colors.empty() &&
              same(ngon.source_vertex, {4u, 5u, 6u, 7u, 8u}) && same(ngon.positions[2], {3.5f, 1, 0}),
          "obj: v//vn corners");
    const im::Primitive& tri = panel.primitives[1];
    check(tri.material == 0 && tri.normals.empty() && tri.texcoords.size() == 1 && same(tri.texcoords[0][1], {1, 0}),
          "obj: v/vt corners");
    check(panel.primitives[2].material == -1 && has_warning(s, "'Missing'"), "obj: unknown usemtl warned");
}

// ---- malformed input ------------------------------------------------------------------------------------------

void test_malformed() {
    for (const char* name : {"truncated.glb", "bad_json.gltf", "missing_buffer.gltf", "bad_index.gltf",
                             "unsupported_ext.gltf", "accessor_overrun.gltf"}) {
        check(throws_format_error([&] { gltf(name); }), std::string("malformed: ") + name + " -> format_error");
    }
    for (const char* name : {"bad_index.obj", "zero_index.obj", "bad_number.obj", "bad_relative.obj"}) {
        check(throws_format_error([&] { im::read_obj(text(load(name)), dir_resolver()); }),
              std::string("malformed: ") + name + " -> format_error");
    }
    check(throws_format_error([] { im::read_gltf({}, dir_resolver()); }), "malformed: empty input");
    // An empty resolver resolves nothing: an external buffer is then missing.
    check(throws_format_error([] { im::read_gltf(load("skin_strip.gltf"), im::Resolver{}); }),
          "malformed: no resolver -> missing buffer");
    // A resolver's own exception passes through unchanged (also out of cgltf's C callback).
    bool propagated = false;
    try {
        im::read_gltf(load("skin_strip.gltf"), [](std::string_view) -> std::optional<std::vector<std::byte>> {
            throw std::logic_error("resolver says no");
        });
    } catch (const std::logic_error& e) {
        propagated = std::string(e.what()) == "resolver says no";
    } catch (...) {
    }
    check(propagated, "resolver exception propagated");
    // The directory resolver never leaves its folder.
    check(!dir_resolver()("../import_test.cpp") && !dir_resolver()("/etc/hostname") &&
              dir_resolver()("model.mtl").has_value(),
          "directory resolver refuses absolute / .. paths");
}

// Fuzz-ish: every fixture truncated at many lengths and with single bytes mutated must read or throw format_error,
// never crash (run under ASan too).
void test_truncations() {
    const char* files[] = {"skin_strip.gltf", "morph_cube.gltf", "textured_png.gltf", "textured_jpeg.gltf",
                           "quad.glb", "hierarchy.gltf", "animation.gltf", "custom_attr.gltf", "strip.gltf",
                           "model.obj", "model.mtl", "textures/checker 1.jpg"};
    int runs = 0, bad = 0;
    for (const char* name : files) {
        const std::vector<std::byte> full = load(name);
        const bool obj = std::string(name).ends_with(".obj") || std::string(name).ends_with(".mtl");
        auto attempt = [&](const std::vector<std::byte>& bytes) {
            ++runs;
            try {
                if (obj) im::read_obj(text(bytes), dir_resolver());
                else im::read_gltf(bytes, dir_resolver());
            } catch (const format_error&) {
            } catch (const std::exception& e) {
                ++bad;
                std::fprintf(stderr, "  %s: non-format exception: %s\n", name, e.what());
            }
        };
        const std::size_t n = full.size();
        const std::size_t step = std::max<std::size_t>(1, n / 199);
        for (std::size_t len = 0; len < n; len += (len < 64 ? 1 : step)) {
            attempt(std::vector<std::byte>(full.begin(), full.begin() + static_cast<std::ptrdiff_t>(len)));
        }
        std::uint32_t seed = 12345;
        for (int k = 0; k < 300 && n > 0; ++k) {
            seed = seed * 1664525u + 1013904223u;
            std::vector<std::byte> m = full;
            const std::size_t at = (seed >> 8) % n;
            static const unsigned char vals[] = {0x00, 0xff, '9', '-', '"', '{', 0x7f, 'e'};
            m[at] = static_cast<std::byte>(vals[(seed >> 3) % 8]);
            attempt(m);
        }
    }
    check(bad == 0, "truncations / mutations: only format_error (" + std::to_string(runs) + " runs)");
}

}  // namespace

int main() {
    test_skin_strip();
    test_morph_cube();
    test_textured();
    test_hierarchy();
    test_animation();
    test_custom();
    test_strip();
    test_obj();
    test_malformed();
    test_truncations();
    if (failures != 0) {
        std::fprintf(stderr, "%d import check(s) failed\n", failures);
        return 1;
    }
    std::printf("import: all checks passed\n");
    return 0;
}
