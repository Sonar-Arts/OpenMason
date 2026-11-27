// omo ctest (OpenMason #167, #168): the native OMO codec over every fixture OMO / SBE / SBO, golden identity
// against files re-saved by the Java writer, OMO 1.9 sections, versions, unknown keys, the co_* ABI and error
// paths. Plain asserts.

#include "cenda/model.h"
#include "omformat/omo.hpp"
#include "omformat/zip_archive.hpp"

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <limits>
#include <string>
#include <string_view>
#include <vector>

namespace {

int failures = 0;

#define CHECK(cond)                                                                       \
    do {                                                                                  \
        if (!(cond)) {                                                                    \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); \
            ++failures;                                                                   \
        }                                                                                 \
    } while (0)

namespace om = cenda::omformat;
namespace omo = cenda::omformat::omo;
namespace fs = std::filesystem;

std::vector<std::byte> load(const fs::path& p) {
    std::ifstream in(p, std::ios::binary);
    std::vector<char> raw((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    std::vector<std::byte> out(raw.size());
    if (!raw.empty()) std::memcpy(out.data(), raw.data(), raw.size());
    return out;
}

std::string text(const std::vector<std::byte>& b) { return {reinterpret_cast<const char*>(b.data()), b.size()}; }

const om::ZipEntry* find(const std::vector<om::ZipEntry>& entries, std::string_view name) {
    for (const auto& e : entries) {
        if (e.name == name) return &e;
    }
    return nullptr;
}

void abi_read_back(const std::vector<std::byte>& archive_bytes, const std::string& label);

// --- Fixtures ---------------------------------------------------------------------------------------

struct Totals {
    int omo = 0;
    int omt = 0;
    int failed = 0;
};

// read -> write -> read: the documents must be equal, and a second write byte-identical to the first.
void round_trip(const std::vector<std::byte>& bytes, const std::string& label, Totals& totals) {
    ++totals.omo;
    try {
        const omo::Document a = omo::read(bytes);
        const std::vector<std::byte> written = omo::write(a);
        const omo::Document b = omo::read(written);
        if (!(a == b)) {
            std::fprintf(stderr, "round trip differs: %s\n", label.c_str());
            ++totals.failed;
            return;
        }
        if (omo::write(b) != written) {
            std::fprintf(stderr, "second write differs: %s\n", label.c_str());
            ++totals.failed;
            return;
        }
        abi_read_back(bytes, label);  // every accessor of the co_* ABI agrees with the C++ document
        if (!a.texture.empty()) {
            ++totals.omt;
            const omo::omt::Texture t = omo::omt::read(a.texture);
            const omo::omt::Texture t2 = omo::omt::read(omo::omt::write(t));
            if (!(t == t2)) {
                std::fprintf(stderr, "texture round trip differs: %s\n", label.c_str());
                ++totals.failed;
            }
        }
    } catch (const std::exception& e) {
        std::fprintf(stderr, "%s: %s\n", label.c_str(), e.what());
        ++totals.failed;
    }
}

void fixtures() {
    const fs::path root = CENDA_FIXTURES_ROOT;
    Totals totals;
    int containers = 0;
    if (!fs::exists(root)) {
        std::fprintf(stderr, "fixtures root missing: %s\n", root.string().c_str());
        ++failures;
        return;
    }
    std::vector<fs::path> files;
    for (const auto& e : fs::recursive_directory_iterator(root)) {
        if (e.is_regular_file()) files.push_back(e.path());
    }
    std::sort(files.begin(), files.end());
    for (const fs::path& p : files) {
        const std::string ext = p.extension().string();
        if (ext == ".omo") {
            round_trip(load(p), p.string(), totals);
        } else if (ext == ".sbe" || ext == ".sbo") {
            ++containers;
            std::vector<om::ZipEntry> entries;
            try {
                entries = om::read_zip(load(p));
            } catch (const std::exception& e) {
                std::fprintf(stderr, "%s: %s\n", p.string().c_str(), e.what());
                ++totals.failed;
                continue;
            }
            for (const auto& e : entries) {
                if (e.name.ends_with(".omo")) round_trip(e.data, p.string() + "!" + e.name, totals);
            }
        }
    }
    std::printf("fixtures: %d containers, %d OMOs (%d with a texture.omt) round-tripped, %d failed\n", containers,
                totals.omo, totals.omt, totals.failed);
    CHECK(totals.omo > 0);
    CHECK(totals.failed == 0);
}

// Files re-saved by the current Java writer: read -> write reproduces every entry byte for byte.
void golden() {
    const fs::path dir = CENDA_OMO_GOLDEN_DIR;
    std::vector<fs::path> files;
    if (fs::exists(dir)) {
        for (const auto& e : fs::directory_iterator(dir)) {
            if (e.is_regular_file() && e.path().extension() == ".omo") files.push_back(e.path());
        }
    }
    std::sort(files.begin(), files.end());
    if (files.empty()) {
        std::printf("golden: no *.omo in %s, skipped\n", dir.string().c_str());
        return;
    }
    int identical = 0;
    int omt_identical = 0;
    int omt_other_writer = 0;
    int failed = 0;
    for (const fs::path& p : files) {
        try {
            const std::vector<std::byte> original = load(p);
            const std::vector<om::ZipEntry> before = om::read_zip(original);
            const omo::Document doc = omo::read(original);
            const std::vector<om::ZipEntry> after = om::read_zip(omo::write(doc));
            bool same = before.size() == after.size();
            for (std::size_t i = 0; same && i < before.size(); ++i) {
                same = before[i].name == after[i].name && before[i].data == after[i].data;
            }
            if (!same) {
                ++failed;
                const om::ZipEntry* m0 = find(before, "manifest.json");
                const om::ZipEntry* m1 = find(after, "manifest.json");
                std::string a = m0 ? text(m0->data) : "";
                std::string b = m1 ? text(m1->data) : "";
                std::size_t at = 0;
                while (at < a.size() && at < b.size() && a[at] == b[at]) ++at;
                const std::size_t from = at > 40 ? at - 40 : 0;
                std::fprintf(stderr, "golden differs: %s (manifest %s at byte %zu:\n  java   %s\n  native %s)\n",
                             p.filename().string().c_str(), a == b ? "same" : "differs", at,
                             a.substr(from, 80).c_str(), b.substr(from, 80).c_str());
                continue;
            }
            ++identical;
            abi_read_back(original, p.filename().string());
            // The embedded texture.omt's manifest is reproduced too, when the Java tool wrote it (Jackson's
            // "key" : value; a few shipped textures were written by scripts in another layout).
            if (!doc.texture.empty()) {
                const std::vector<om::ZipEntry> omt = om::read_zip(doc.texture);
                const om::ZipEntry* m = find(omt, "manifest.json");
                const std::string again = omo::omt::write_manifest(omo::omt::read(doc.texture));
                if (m != nullptr && text(m->data).find("\" : ") == std::string::npos) {
                    ++omt_other_writer;
                } else if (m != nullptr && text(m->data) == again) {
                    ++omt_identical;
                } else {
                    std::fprintf(stderr, "golden texture.omt manifest differs: %s\n", p.filename().string().c_str());
                    ++failed;
                }
            }
        } catch (const std::exception& e) {
            std::fprintf(stderr, "golden %s: %s\n", p.filename().string().c_str(), e.what());
            ++failed;
        }
    }
    std::printf("golden: %zu files, %d identical entry by entry (texture.omt manifests: %d identical, %d not "
                "Java-written), %d failed\n",
                files.size(), identical, omt_identical, omt_other_writer, failed);
    CHECK(failed == 0);
}


// --- Java number text ------------------------------------------------------------------------------

void java_numbers() {
    // Float.toString / Double.toString on JDK 25 (values checked against the JDK's documented outputs).
    CHECK(omo::java_float(0.f) == "0.0");
    CHECK(omo::java_float(-0.f) == "-0.0");
    CHECK(omo::java_float(1.f) == "1.0");
    CHECK(omo::java_float(-1.5f) == "-1.5");
    CHECK(omo::java_float(100.f) == "100.0");
    CHECK(omo::java_float(0.1f) == "0.1");
    CHECK(omo::java_float(0.001f) == "0.001");
    CHECK(omo::java_float(0.002f) == "0.002");
    CHECK(omo::java_float(1.0e-4f) == "1.0E-4");
    CHECK(omo::java_float(1.25e-5f) == "1.25E-5");
    CHECK(omo::java_float(1234567.f) == "1234567.0");
    CHECK(omo::java_float(1.0e7f) == "1.0E7");
    CHECK(omo::java_float(12345678.f) == "1.2345678E7");
    CHECK(omo::java_float(0.010000001f) == "0.010000001");
    CHECK(omo::java_float(0.33333334f) == "0.33333334");
    CHECK(omo::java_float(std::numeric_limits<float>::max()) == "3.4028235E38");
    CHECK(omo::java_float(std::numeric_limits<float>::min()) == "1.1754944E-38");  // JDK 19+ (shortest)
    CHECK(omo::java_float(std::numeric_limits<float>::denorm_min()) == "1.4E-45");
    CHECK(omo::java_double(0.0) == "0.0");
    CHECK(omo::java_double(0.1) == "0.1");
    CHECK(omo::java_double(1.0e-5) == "1.0E-5");
    CHECK(omo::java_double(123456789.0) == "1.23456789E8");
    CHECK(omo::java_double(1.0e23) == "1.0E23");
    CHECK(omo::java_double(std::numeric_limits<double>::denorm_min()) == "4.9E-324");
    CHECK(omo::java_double(std::numeric_limits<double>::max()) == "1.7976931348623157E308");
    CHECK(omo::java_double(static_cast<double>(0.1f)) == "0.10000000149011612");
}

// --- Synthetic documents ----------------------------------------------------------------------------

std::vector<std::byte> bytes(std::string_view s) {
    std::vector<std::byte> out(s.size());
    if (!s.empty()) std::memcpy(out.data(), s.data(), s.size());
    return out;
}

std::vector<std::byte> archive(const std::string& manifest, std::vector<om::ZipEntry> extra = {}) {
    std::vector<om::ZipEntry> entries;
    entries.push_back({"manifest.json", bytes(manifest), false});
    for (auto& e : extra) entries.push_back(std::move(e));
    return om::write_zip(entries);
}

std::string manifest_of(const std::vector<std::byte>& archive_bytes) {
    for (const auto& e : om::read_zip(archive_bytes)) {
        if (e.name == "manifest.json") return text(e.data);
    }
    return {};
}

omo::omt::Texture sample_texture() {
    omo::omt::Texture t;
    t.width = 32;
    t.height = 16;
    t.layers.push_back({"Base", true, 1.f, "layer_0.png", bytes("\x89PNG-base")});
    t.layers.push_back({"Paint \"overlay\"", false, 0.5f, "layer_1.png", bytes("\x89PNG-overlay")});
    t.active_layer = 1;
    t.face_mappings.push_back({3, 7, 0.f, 0.25f, 0.5f, 1.f, 90});
    t.materials.push_back({7, "Gold", "OPAQUE", "material_7.png"});
    t.other_entries.push_back({"material_7.png", bytes("\x89PNG-gold")});
    return t;
}

// A document using every section, 1.9 included.
omo::Document sample_document() {
    omo::Document d;
    d.object_name = "Synthetic \"model\"\n";
    d.model_type = "CUSTOM";
    d.geometry = {8, 16, 4, {0.5, -1.25, 1.0e-5}};
    omo::Mesh mesh;
    mesh.vertices = std::vector<float>{0.f, 0.f, 0.f, 1.f, 0.f, 0.f, 1.f, 1.f, 0.f, 0.f, 1.f, 0.f,
                                       0.f, 0.f, 1.f, 1.f, 0.f, 1.f, 1.f, 1.f, 1.f, 0.f, 1.f, 0.1f};
    mesh.tex_coords = std::vector<float>{0.f, 0.f, 1.f, 0.f, 1.f, 1.f, 0.f, 1.f, 0.f, 0.f, 1.f, 0.f, 1.f, 1.f, 0.f, 1.f};
    mesh.indices = std::vector<std::int32_t>{0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7};
    mesh.triangle_to_face_id = std::vector<std::int32_t>{0, 0, 1, 1};
    mesh.uv_mode = "FLAT";
    d.mesh = mesh;
    omo::FaceTextures ft;
    ft.mappings.push_back({0, 1, 0.f, 0.f, 0.5f, 0.25f, 90, false});
    ft.mappings.push_back({1, 2, 0.125f, 0.f, 1.f, 1.f, 0, true});
    omo::Material red;
    red.material_id = 1;
    red.name = "Red";
    red.texture_file = "material_1.png";
    red.render_layer = "OPAQUE";
    red.tint_color = static_cast<std::int32_t>(0xFF0000FFu);
    ft.materials.push_back(red);
    omo::Material flat;
    flat.material_id = 2;
    flat.name = "Flat colour";
    flat.render_layer = "CUTOUT";
    flat.emissive = true;
    flat.base_color = std::array<float, 4>{0.5f, 0.25f, 0.125f, 1.f};
    ft.materials.push_back(flat);
    d.face_textures = ft;

    omo::Part body;
    body.id = "part-body";
    body.name = "body";
    body.origin = {0.5f, 0.5f, 0.5f};
    body.vertex_count = 4;
    body.index_count = 6;
    body.face_count = 1;
    body.bone_id = "bone-root";
    omo::Part head;
    head.id = "part-head";
    head.name = "head";
    head.pos = {0.f, 1.f, 0.f};
    head.rot = {10.f, -20.f, 30.f};
    head.scale = {1.f, 2.f, 0.5f};
    head.vertex_start = 4;
    head.vertex_count = 4;
    head.index_start = 6;
    head.index_count = 6;
    head.face_start = 1;
    head.face_count = 1;
    head.visible = false;
    head.locked = true;
    head.parent_id = "bone-neck";
    d.parts = {body, head};
    d.model_transform.pos = {1.f, 2.f, 3.f};
    d.model_transform.scale = {2.f, 2.f, 2.f};
    omo::Bone root;
    root.id = "bone-root";
    root.name = "root";
    root.endpoint = {0.f, 1.f, 0.f};
    omo::Bone neck;
    neck.id = "bone-neck";
    neck.name = "neck";
    neck.parent_bone_id = "part-body";
    neck.origin = {0.f, 0.5f, 0.f};
    neck.rot = {0.f, 0.f, 45.f};
    neck.endpoint = {0.f, 0.25f, 0.f};
    d.bones = {root, neck};
    omo::AttachmentPoint hat;
    hat.id = "ap-hat";
    hat.name = "Hatzone";
    hat.parent_part_id = "part-head";
    hat.parent_part_name = "head";
    hat.pos = {0.f, 1.5f, 0.f};
    hat.scale = {0.5f, 0.5f, 0.5f};
    omo::AttachmentPoint free;
    free.id = "ap-root";
    free.name = "origin";
    d.attachment_points = {hat, free};
    d.skin.joints = {"bone-root", "bone-neck", "part-head"};
    omo::SkinPart sb;
    sb.part_id = "part-body";
    sb.vertices = {0, 3};
    sb.offsets = {0, 1, 3};
    sb.joints = {0, 0, 1};
    sb.weights = {1.f, 0.75f, 0.25f};
    omo::SkinPart sh;
    sh.part_id = "part-head";
    sh.vertices = {2};
    sh.offsets = {0, 4};
    sh.joints = {0, 1, 2, 1};
    sh.weights = {0.25f, 0.25f, 0.25f, 0.25f};
    d.skin.parts = {sb, sh};

    for (std::uint32_t f : {0u, 2u, 3u, 4u, 31u, 32u, 33u, 100u}) d.shading.set_smooth_face(f, true);
    d.shading.sharp_edges = {0, 1, 2, 3};
    d.shading.auto_smooth_angle = 30.f;
    d.shading.normal_vertices = {1, 5};
    d.shading.normal_oct = {32767, -32768, 0, 12345};
    d.vertex_groups.push_back({"Left Arm", {{"part-body", {0, 1, 2}, {1.f, 0.5f, 0.f}}, {"part-head", {}, {}}}});
    d.vertex_groups.push_back({"Empty", {}});
    omo::Morph smile;
    smile.name = "Smile";
    smile.default_weight = 0.25f;
    smile.min = -1.f;
    smile.max = 2.f;
    smile.parts.push_back({"part-head", {0, 3}, {0.f, 0.1f, 0.f, 0.01f, 0.f, -0.5f}, {0.f, 0.f, 1.f, 0.f, 1.f, 0.f}});
    smile.parts.push_back({"part-body", {1}, {1.f, 2.f, 3.f}, {}});
    omo::Morph blink;
    blink.name = "Blink";
    d.morphs = {smile, blink};

    d.unknown_sections.push_back({"futureThing", R"({"a":1.50,"b":[1e3,true,null,"x\n"],"c":{}})"});
    d.texture = omo::omt::write(sample_texture());
    d.images.push_back({"material_1.png", bytes("\x89PNG-red")});
    d.extra_entries.push_back({"notes/readme.txt", bytes("hello")});
    return d;
}

void synthetic_round_trips() {
    omo::Document d = sample_document();
    CHECK(d.has_1_9_data());
    CHECK(d.written_version() == "1.9");
    const std::vector<std::byte> written = omo::write(d);
    omo::Document back = omo::read(written);
    d.version = "1.9";
    CHECK(back == d);
    CHECK(omo::write(back) == written);
    const std::string manifest = manifest_of(written);
    CHECK(manifest.find("\"version\" : \"1.9\"") != std::string::npos);
    CHECK(manifest.find("\"smooth\" : [ [ 0, 1 ], [ 2, 3 ], [ 31, 3 ], [ 100, 1 ] ]") != std::string::npos);
    CHECK(manifest.find("\"sharpEdges\" : [ [ 0, 1 ], [ 2, 3 ] ]") != std::string::npos);
    CHECK(manifest.find("\"customNormals\" : [ [ 1, 32767, -32768 ], [ 5, 0, 12345 ] ]") != std::string::npos);
    CHECK(manifest.find("\"baseColor\" : [ 0.5, 0.25, 0.125, 1.0 ]") != std::string::npos);
    CHECK(manifest.find("[ 0, 0.0, 0.1, 0.0, 0.0, 0.0, 1.0 ]") != std::string::npos);  // a delta row with normals
    CHECK(manifest.find("[ 1, 1.0, 2.0, 3.0 ]") != std::string::npos);                 // a delta row without
    CHECK(manifest.find("\"futureThing\" : {\n    \"a\" : 1.50,\n    \"b\" : [ 1e3, true, null, \"x\\n\" ],\n"
                        "    \"c\" : { }\n  }\n}") != std::string::npos);
    CHECK(manifest.find("\"vertices\" : [ [ 2, 0, 0.25, 1, 0.25, 2, 0.25, 1, 0.25 ] ]") != std::string::npos);
    CHECK(back.shading.smooth_face(33) && !back.shading.smooth_face(34) && back.shading.smooth_face(100));

    // The embedded texture.
    const omo::omt::Texture t = omo::omt::read(back.texture);
    CHECK(t == sample_texture());

    // Every 1.9 section alone forces 1.9; without them the document's own version is kept.
    auto version_of = [](const omo::Document& doc) {
        const std::string m = omo::write_manifest(doc);
        const auto at = m.find("\"version\" : \"") + 13;
        return m.substr(at, m.find('"', at) - at);
    };
    omo::Document plain;
    CHECK(version_of(plain) == "1.8");
    plain.version = "1.3";
    CHECK(version_of(plain) == "1.3");
    CHECK(omo::read(omo::write(plain)).version == "1.3");
    {
        omo::Document x = plain;
        x.shading.auto_smooth_angle = 60.f;
        CHECK(version_of(x) == "1.9");
        omo::Document y = omo::read(omo::write(x));
        CHECK(y.shading.auto_smooth_angle == 60.f);
    }
    {
        omo::Document x = plain;
        x.shading.set_smooth_face(5, true);
        CHECK(version_of(x) == "1.9");
        CHECK(omo::read(omo::write(x)).shading.smooth_face(5));
    }
    {
        omo::Document x = plain;
        x.shading.normal_vertices = {0};
        x.shading.normal_oct = {1, -1};
        CHECK(version_of(x) == "1.9");
    }
    {
        omo::Document x = plain;
        x.shading.sharp_edges = {4, 9};
        CHECK(version_of(x) == "1.9");
    }
    {
        omo::Document x = plain;
        x.vertex_groups.push_back({"g", {}});
        CHECK(version_of(x) == "1.9");
        CHECK(omo::read(omo::write(x)).vertex_groups == x.vertex_groups);
    }
    {
        omo::Document x = plain;
        x.morphs.push_back(omo::Morph{"m", 0.f, 0.f, 1.f, {}});
        CHECK(version_of(x) == "1.9");
        CHECK(omo::read(omo::write(x)).morphs == x.morphs);
    }
    {
        omo::Document x = plain;
        x.face_textures = omo::FaceTextures{};
        omo::Material m;
        m.base_color = std::array<float, 4>{1.f, 1.f, 1.f, 0.5f};
        x.face_textures->materials.push_back(m);
        CHECK(version_of(x) == "1.9");
        CHECK(omo::read(omo::write(x)).face_textures == x.face_textures);
    }
    {
        omo::Document x = plain;
        x.version = "1.10";
        x.shading.auto_smooth_angle = 1.f;
        CHECK(version_of(x) == "1.10");  // never lowered
    }
    // An all-zero smooth bitset is no data.
    {
        omo::Document x = plain;
        x.shading.smooth = {0, 0};
        CHECK(!x.has_1_9_data());
        CHECK(version_of(x) == "1.3");
    }
    // CRLF manifests stay CRLF.
    {
        omo::Document x = sample_document();
        x.crlf = true;
        const std::string m = omo::write_manifest(x);
        CHECK(m.find("{\r\n  \"version\"") == 0);
        CHECK(m.find("\n") == m.find("\r\n") + 1);
        CHECK(omo::read(omo::write(x)).crlf);
    }
}

// The exact Jackson layout of a small document (what OMOSerializer writes for the same data).
void exact_layout() {
    omo::Document d;
    d.object_name = "Tiny";
    d.model_type = "SINGLE_CUBE";
    omo::Mesh m;
    m.vertices = std::vector<float>{0.f, 0.5f, -1.f};
    m.indices = std::vector<std::int32_t>{};
    m.uv_mode = "FLAT";
    d.mesh = m;
    d.face_textures = omo::FaceTextures{};
    omo::Part p;
    p.id = "p1";
    p.name = "Root";
    d.parts = {p};
    const std::string expected = R"({
  "version" : "1.8",
  "objectName" : "Tiny",
  "modelType" : "SINGLE_CUBE",
  "geometry" : {
    "width" : 16,
    "height" : 16,
    "depth" : 16,
    "position" : {
      "x" : 0.0,
      "y" : 0.0,
      "z" : 0.0
    }
  },
  "textureFile" : "texture.omt",
  "mesh" : {
    "vertices" : [ 0.0, 0.5, -1.0 ],
    "texCoords" : null,
    "indices" : [ ],
    "triangleToFaceId" : null,
    "uvMode" : "FLAT"
  },
  "faceTextures" : {
    "mappings" : [ ],
    "materials" : [ ]
  },
  "parts" : [ {
    "id" : "p1",
    "name" : "Root",
    "originX" : 0.0,
    "originY" : 0.0,
    "originZ" : 0.0,
    "posX" : 0.0,
    "posY" : 0.0,
    "posZ" : 0.0,
    "rotX" : 0.0,
    "rotY" : 0.0,
    "rotZ" : 0.0,
    "scaleX" : 1.0,
    "scaleY" : 1.0,
    "scaleZ" : 1.0,
    "vertexStart" : 0,
    "vertexCount" : 0,
    "indexStart" : 0,
    "indexCount" : 0,
    "faceStart" : 0,
    "faceCount" : 0,
    "visible" : true,
    "locked" : false,
    "parentId" : null,
    "boneId" : null
  } ],
  "modelTransform" : null,
  "bones" : null,
  "attachmentPoints" : null,
  "skin" : null
})";
    CHECK(omo::write_manifest(d) == expected);
    CHECK(omo::read(omo::write(d)) == d);

    const std::string expected_omt = R"({
  "version" : "1.0",
  "canvasSize" : {
    "width" : 32,
    "height" : 16
  },
  "layers" : [ {
    "name" : "Base",
    "visible" : true,
    "opacity" : 1.0,
    "dataFile" : "layer_0.png"
  }, {
    "name" : "Paint \"overlay\"",
    "visible" : false,
    "opacity" : 0.5,
    "dataFile" : "layer_1.png"
  } ],
  "activeLayerIndex" : 1,
  "faceMappings" : [ {
    "faceId" : 3,
    "materialId" : 7,
    "u0" : 0.0,
    "v0" : 0.25,
    "u1" : 0.5,
    "v1" : 1.0,
    "uvRotation" : 90
  } ],
  "materials" : [ {
    "materialId" : 7,
    "name" : "Gold",
    "renderLayer" : "OPAQUE",
    "textureFile" : "material_7.png"
  } ]
})";
    CHECK(omo::omt::write_manifest(sample_texture()) == expected_omt);
    // Entry order: manifest, layers, other entries.
    const auto entries = om::read_zip(omo::omt::write(sample_texture()));
    CHECK(entries.size() == 4 && entries[0].name == "manifest.json" && entries[1].name == "layer_0.png" &&
          entries[2].name == "layer_1.png" && entries[3].name == "material_7.png");
}

void versions_and_unknown_keys() {
    CHECK(omo::compare_versions("1.10", "1.9") > 0);
    CHECK(omo::compare_versions("1.9", "1.10") < 0);
    CHECK(omo::compare_versions("1.9", "1.9") == 0);
    CHECK(omo::compare_versions("1.8", "1.9") < 0);
    CHECK(omo::compare_versions("2.0", "1.10") > 0);
    CHECK(!omo::parse_version("1"));
    CHECK(!omo::parse_version("1."));
    CHECK(!omo::parse_version(".1"));
    CHECK(!omo::parse_version("a.b"));
    CHECK(!omo::parse_version("1.-1"));
    CHECK(!omo::parse_version("1.2.3"));
    CHECK(omo::parse_version("1.10") == (omo::Version{1, 10}));

    auto with_version = [](const std::string& v) {
        return archive("{\"version\":\"" + v + "\",\"objectName\":\"x\",\"modelType\":\"t\",\"textureFile\":\"texture.omt\"}");
    };
    CHECK(omo::read(with_version("1.8")).version == "1.8");
    CHECK(omo::read(with_version("1.0")).version == "1.0");
    CHECK(omo::read(with_version("1.9")).version == "1.9");
    CHECK(omo::read(with_version("1.10")).version == "1.10");
    CHECK(omo::read(archive("\xEF\xBB\xBF{\"version\":\"1.8\"}")).version == "1.8");  // a UTF-8 BOM is skipped
    bool refused = false;
    try {
        (void)omo::read(with_version("2.0"));
    } catch (const omo::version_error& e) {
        refused = std::string(e.what()).find("2.0") != std::string::npos;
    }
    CHECK(refused);
    for (const char* bad : {"0.9", "abc", "1"}) {
        bool failed = false;
        try {
            (void)omo::read(with_version(bad));
        } catch (const omo::version_error&) {
        } catch (const om::format_error&) {
            failed = true;
        }
        CHECK(failed);
    }

    // Unknown top-level keys survive, in order, numbers spelled as in the file.
    const std::string manifest =
            "{\"version\":\"1.8\",\"objectName\":\"x\",\"zeta\":7,\"modelType\":\"t\","
            "\"future\":{\"k\":[1.50,-0e0,12345678901234567890]},\"textureFile\":\"texture.omt\"}";
    omo::Document d = omo::read(archive(manifest));
    CHECK(d.unknown_sections.size() == 2);
    CHECK(d.unknown_sections.size() == 2 && d.unknown_sections[0].key == "zeta" && d.unknown_sections[0].json == "7");
    CHECK(d.unknown_sections.size() == 2 && d.unknown_sections[1].key == "future" &&
          d.unknown_sections[1].json == "{\"k\":[1.50,-0e0,12345678901234567890]}");
    const std::string out = omo::write_manifest(d);
    CHECK(out.find("\"skin\" : null,\n  \"zeta\" : 7,\n  \"future\" : {\n    \"k\" : [ 1.50, -0e0, "
                   "12345678901234567890 ]\n  }\n}") != std::string::npos);
    CHECK(omo::read(omo::write(d)) == d);
    // A known key cannot be smuggled in as an unknown section; invalid JSON is refused on write.
    omo::Document bad = d;
    bad.unknown_sections.push_back({"skin", "{}"});
    bool refused_known = false;
    try {
        (void)omo::write(bad);
    } catch (const om::format_error&) {
        refused_known = true;
    }
    CHECK(refused_known);
    bad = d;
    bad.unknown_sections.push_back({"oops", "{not json"});
    bool refused_json = false;
    try {
        (void)omo::write(bad);
    } catch (const om::format_error&) {
        refused_json = true;
    }
    CHECK(refused_json);
}

// The Java readers' defaults and leniency for 1.0-1.8 data.
void lenient_reading() {
    const std::string manifest = R"({
  "version" : "1.6", "objectName" : "Old", "modelType" : "t", "textureFile" : "texture.omt",
  "geometry" : { "width" : 2, "height" : 3, "depth" : 4.9, "position" : { "x" : 1, "y" : 2.5, "z" : -3 } },
  "mesh" : { "vertices" : [ 1, 2.5, 3 ], "indices" : [ 0, 1, 2 ] },
  "faceTextures" : { "mappings" : [ { "faceId" : 0, "materialId" : 3, "u0" : 0, "v0" : 0, "u1" : 1, "v1" : 1,
                                      "uvRotationDegrees" : 0 } ],
                     "materials" : [ { "materialId" : 3, "name" : "M", "textureFile" : "material_3.png",
                                       "renderLayer" : "OPAQUE" } ] },
  "parts" : [ { "id" : "a", "name" : "A" }, { "id" : "  " }, { "name" : "no id" }, { "id" : "b" } ],
  "bones" : [ { "id" : "bone" } ],
  "attachmentPoints" : [ { "id" : "s", "name" : "S", "posX" : 2 } ],
  "skin" : { "joints" : [ "bone" ], "parts" : [ { "partId" : "a", "vertices" : [ [ 0, 0, 1.0 ], [ 1, 5, 1.0 ],
             [ 2, 0 ], [ 3, 0, 0.5, 0, 0.5 ] ] }, { "partId" : "b", "vertices" : [ [ 0, 9, 1 ] ] } ] }
})";
    const omo::Document d = omo::read(archive(manifest, {{"material_3.png", bytes("png"), false},
                                                          {"texture.omt", bytes("omt"), false},
                                                          {"other.bin", bytes("x"), false}}));
    CHECK(d.version == "1.6");
    CHECK(d.geometry.width == 2 && d.geometry.depth == 4 && d.geometry.position.y == 2.5);
    CHECK(d.mesh && d.mesh->vertices && d.mesh->vertices->size() == 3 && !d.mesh->tex_coords && !d.mesh->uv_mode);
    CHECK(d.face_textures && d.face_textures->mappings.size() == 1 && d.face_textures->mappings[0].auto_resize);
    CHECK(d.face_textures && d.face_textures->materials.size() == 1 &&
          d.face_textures->materials[0].tint_color == -1 && !d.face_textures->materials[0].emissive);
    CHECK(d.parts.size() == 2 && d.parts[1].name == "Unnamed Part" && d.parts[1].scale == (omo::Vec3{1.f, 1.f, 1.f}) &&
          d.parts[1].visible && !d.parts[1].locked && !d.parts[1].parent_id);
    CHECK(d.bones.size() == 1 && d.bones[0].name == "bone");
    CHECK(d.attachment_points.size() == 1 && d.attachment_points[0].pos.x == 2.f &&
          d.attachment_points[0].scale == (omo::Vec3{1.f, 1.f, 1.f}));
    // Malformed rows dropped: joint 5 out of range, an even-length row; part b has no valid row left.
    CHECK(d.skin.parts.size() == 1 && d.skin.parts[0].vertices == (std::vector<std::int32_t>{0, 3}) &&
          d.skin.parts[0].offsets == (std::vector<std::int32_t>{0, 1, 3}));
    CHECK(d.texture.size() == 3 && d.images.size() == 1 && d.extra_entries.size() == 1 && d.image("material_3.png"));
    CHECK(d.model_transform.identity());
    // Written back, then read again: the same document.
    CHECK(omo::read(omo::write(d)) == d);

    // A skin without one valid row reads as none (written as null).
    const omo::Document e = omo::read(archive(
            R"({"version":"1.8","skin":{"joints":["j"],"parts":[{"partId":"p","vertices":[[0,3,1.0]]}]}})"));
    CHECK(e.skin == omo::Skin{});
}

void error_paths() {
    const std::vector<std::byte> good = omo::write(sample_document());
    auto refuses = [](const std::vector<std::byte>& b, const char* fragment) {
        try {
            (void)omo::read(b);
        } catch (const om::format_error& e) {
            if (std::string(e.what()).find(fragment) != std::string::npos) return true;
            std::fprintf(stderr, "unexpected message: %s\n", e.what());
            return false;
        }
        return false;
    };
    std::vector<std::byte> truncated(good.begin(), good.begin() + static_cast<std::ptrdiff_t>(good.size() / 2));
    CHECK(refuses(truncated, "zip"));
    CHECK(refuses(std::vector<std::byte>{}, "zip"));
    CHECK(refuses(bytes("definitely not a zip"), "zip"));
    CHECK(refuses(archive("{\"version\": \"1.8\", "), "not valid JSON"));
    CHECK(refuses(archive("[1, 2]"), "must be a JSON object"));
    CHECK(refuses(archive("{\"objectName\":\"x\"}"), "no \"version\""));
    CHECK(refuses(archive("{\"version\":\"1.8\",\"version\":\"1.8\"}"), "duplicate key"));
    CHECK(refuses(om::write_zip({{"texture.omt", bytes("x"), false}}), "missing manifest.json"));
    CHECK(refuses(archive(R"({"version":"1.9","shading":{"smooth":[[0,1,2]]}})"), "smooth"));
    CHECK(refuses(archive(R"({"version":"1.9","shading":{"smooth":[[0,99999999999]]}})"), "out of range"));
    CHECK(refuses(archive(R"({"version":"1.9","shading":{"customNormals":[[0,40000,0]]}})"), "out of range"));
    CHECK(refuses(archive(R"({"version":"1.9","vertexGroups":[{"name":"g","parts":[{"partId":"p","vertices":[[0]]}]}]})"),
                  "vertexGroups"));
    CHECK(refuses(archive(R"({"version":"1.9","morphs":[{"name":"m","parts":[{"partId":"p","deltas":[[0,1,2]]}]}]})"),
                  "morphs"));
    CHECK(refuses(archive(R"({"version":"1.9","morphs":[{"parts":[]}]})"), "name"));
    CHECK(refuses(archive(R"({"version":"1.9","faceTextures":{"materials":[{"baseColor":[1,1]}]}})"), "baseColor"));
    // Writing refuses malformed CSR data.
    auto write_refuses = [](const omo::Document& doc) {
        try {
            (void)omo::write(doc);
        } catch (const om::format_error&) {
            return true;
        }
        return false;
    };
    omo::Document d;
    d.skin.joints = {"j"};
    d.skin.parts.push_back({"p", {0}, {0, 1}, {3}, {1.f}});  // joint index out of range
    CHECK(write_refuses(d));
    d.skin.parts[0] = {"p", {0}, {0, 0}, {}, {}};  // a row without influences
    CHECK(write_refuses(d));
    d = omo::Document{};
    d.morphs.push_back({"m", 0.f, 0.f, 1.f, {{"p", {0}, {1.f}, {}}}});
    CHECK(write_refuses(d));
    d = omo::Document{};
    d.version = "one.eight";
    CHECK(write_refuses(d));
    d = omo::Document{};
    d.images.push_back({"texture.omt", bytes("x")});
    d.texture = bytes("y");
    CHECK(write_refuses(d));  // colliding entry names
    // .omt errors.
    bool omt_refused = false;
    try {
        (void)omo::omt::read(archive("{\"version\":\"1.0\",\"layers\":5,"));
    } catch (const om::format_error&) {
        omt_refused = true;
    }
    CHECK(omt_refused);
}

// --- The co_* ABI -----------------------------------------------------------------------------------

std::vector<std::byte> take(co_bytes* b) {
    std::vector<std::byte> out;
    if (b == nullptr) return out;
    out.resize(static_cast<std::size_t>(co_bytes_size(b)));
    if (!out.empty()) std::memcpy(out.data(), co_bytes_data(b), out.size());
    co_bytes_destroy(b);
    return out;
}

const uint8_t* u8(const std::vector<std::byte>& b) { return reinterpret_cast<const uint8_t*>(b.data()); }

std::string s_or_null(const char* s) { return s == nullptr ? std::string("<null>") : std::string(s); }

// Fills a co_doc from the C++ sample through the ABI only, writes it and compares with the C++ writer.
void abi_fill_and_write() {
    CHECK(co_abi_version() == CO_ABI_VERSION);
    const omo::Document ref = sample_document();

    co_doc* d = co_doc_create();
    CHECK(d != nullptr);
    CHECK(co_doc_set_string(d, CO_FIELD_OBJECT_NAME, ref.object_name.c_str()) == CO_OK);
    CHECK(co_doc_set_string(d, CO_FIELD_MODEL_TYPE, ref.model_type.c_str()) == CO_OK);
    CHECK(co_doc_set_string(d, CO_FIELD_TEXTURE_FILE, ref.texture_file.c_str()) == CO_OK);
    CHECK(co_doc_set_string(d, CO_FIELD_UV_MODE, "FLAT") == CO_OK);
    CHECK((co_doc_flags(d) & CO_DOC_HAS_MESH) != 0);
    const co_geometry g{ref.geometry.width, ref.geometry.height, ref.geometry.depth, 0,
                        {ref.geometry.position.x, ref.geometry.position.y, ref.geometry.position.z}};
    CHECK(co_doc_geometry_set(d, &g) == CO_OK);
    const float trs[9] = {1.f, 2.f, 3.f, 0.f, 0.f, 0.f, 2.f, 2.f, 2.f};
    CHECK(co_doc_model_transform_set(d, trs) == CO_OK);
    const omo::Mesh& m = *ref.mesh;
    CHECK(co_doc_mesh_set_array(d, CO_MESH_VERTICES, m.vertices->data(), static_cast<int64_t>(m.vertices->size())) == CO_OK);
    CHECK(co_doc_mesh_set_array(d, CO_MESH_TEX_COORDS, m.tex_coords->data(), static_cast<int64_t>(m.tex_coords->size())) == CO_OK);
    CHECK(co_doc_mesh_set_array(d, CO_MESH_INDICES, m.indices->data(), static_cast<int64_t>(m.indices->size())) == CO_OK);
    CHECK(co_doc_mesh_set_array(d, CO_MESH_TRIANGLE_FACE_IDS, m.triangle_to_face_id->data(),
                                static_cast<int64_t>(m.triangle_to_face_id->size())) == CO_OK);
    CHECK(co_doc_mesh_set_array(d, 9, nullptr, 0) == CO_ERR_ARG);

    std::vector<co_face_mapping> maps;
    for (const auto& fm : ref.face_textures->mappings) {
        maps.push_back({fm.face_id, fm.material_id, fm.u0, fm.v0, fm.u1, fm.v1, fm.uv_rotation_degrees, fm.auto_resize ? 1 : 0});
    }
    CHECK(co_doc_face_mappings_set(d, maps.data(), static_cast<int32_t>(maps.size())) == CO_OK);
    std::vector<co_material> mats;
    for (const auto& mt : ref.face_textures->materials) {
        co_material c{};
        c.name = mt.name ? mt.name->c_str() : nullptr;
        c.texture_file = mt.texture_file ? mt.texture_file->c_str() : nullptr;
        c.render_layer = mt.render_layer ? mt.render_layer->c_str() : nullptr;
        c.material_id = mt.material_id;
        c.emissive = mt.emissive ? 1 : 0;
        c.tint_color = mt.tint_color;
        c.has_base_color = mt.base_color ? 1 : 0;
        if (mt.base_color) std::copy(mt.base_color->begin(), mt.base_color->end(), c.base_color);
        mats.push_back(c);
    }
    CHECK(co_doc_materials_set(d, mats.data(), static_cast<int32_t>(mats.size())) == CO_OK);

    std::vector<co_part> parts;
    for (const auto& p : ref.parts) {
        co_part c{};
        c.id = p.id.c_str();
        c.name = p.name.c_str();
        c.parent_id = p.parent_id ? p.parent_id->c_str() : nullptr;
        c.bone_id = p.bone_id ? p.bone_id->c_str() : nullptr;
        const omo::Vec3* src[4] = {&p.origin, &p.pos, &p.rot, &p.scale};
        float* dst[4] = {c.origin, c.pos, c.rot, c.scale};
        for (int k = 0; k < 4; ++k) {
            dst[k][0] = src[k]->x;
            dst[k][1] = src[k]->y;
            dst[k][2] = src[k]->z;
        }
        c.vertex_start = p.vertex_start;
        c.vertex_count = p.vertex_count;
        c.index_start = p.index_start;
        c.index_count = p.index_count;
        c.face_start = p.face_start;
        c.face_count = p.face_count;
        c.visible = p.visible ? 1 : 0;
        c.locked = p.locked ? 1 : 0;
        parts.push_back(c);
    }
    CHECK(co_doc_parts_set(d, parts.data(), static_cast<int32_t>(parts.size())) == CO_OK);
    std::vector<co_bone> bones;
    for (const auto& b : ref.bones) {
        co_bone c{};
        c.id = b.id.c_str();
        c.name = b.name.c_str();
        c.parent_bone_id = b.parent_bone_id ? b.parent_bone_id->c_str() : nullptr;
        const omo::Vec3* src[4] = {&b.origin, &b.pos, &b.rot, &b.endpoint};
        float* dst[4] = {c.origin, c.pos, c.rot, c.endpoint};
        for (int k = 0; k < 4; ++k) {
            dst[k][0] = src[k]->x;
            dst[k][1] = src[k]->y;
            dst[k][2] = src[k]->z;
        }
        bones.push_back(c);
    }
    CHECK(co_doc_bones_set(d, bones.data(), static_cast<int32_t>(bones.size())) == CO_OK);
    std::vector<co_attachment> aps;
    for (const auto& a : ref.attachment_points) {
        co_attachment c{};
        c.id = a.id.c_str();
        c.name = a.name.c_str();
        c.parent_part_id = a.parent_part_id ? a.parent_part_id->c_str() : nullptr;
        c.parent_part_name = a.parent_part_name ? a.parent_part_name->c_str() : nullptr;
        const omo::Vec3* src[3] = {&a.pos, &a.rot, &a.scale};
        float* dst[3] = {c.pos, c.rot, c.scale};
        for (int k = 0; k < 3; ++k) {
            dst[k][0] = src[k]->x;
            dst[k][1] = src[k]->y;
            dst[k][2] = src[k]->z;
        }
        aps.push_back(c);
    }
    CHECK(co_doc_attachments_set(d, aps.data(), static_cast<int32_t>(aps.size())) == CO_OK);

    // Skin as global CSR.
    {
        std::vector<const char*> joint_ids;
        for (const auto& j : ref.skin.joints) joint_ids.push_back(j.c_str());
        std::vector<const char*> part_ids;
        std::vector<int32_t> part_rows{0};
        std::vector<int32_t> row_vertices;
        std::vector<int32_t> row_influences{0};
        std::vector<int32_t> joints;
        std::vector<float> weights;
        for (const auto& sp : ref.skin.parts) {
            part_ids.push_back(sp.part_id.c_str());
            for (std::size_t r = 0; r < sp.vertices.size(); ++r) {
                row_vertices.push_back(sp.vertices[r]);
                for (auto k = static_cast<std::size_t>(sp.offsets[r]); k < static_cast<std::size_t>(sp.offsets[r + 1]); ++k) {
                    joints.push_back(sp.joints[k]);
                    weights.push_back(sp.weights[k]);
                }
                row_influences.push_back(static_cast<int32_t>(joints.size()));
            }
            part_rows.push_back(static_cast<int32_t>(row_vertices.size()));
        }
        // Refusals first (the document stays unchanged).
        std::vector<int32_t> bad_joints = joints;
        bad_joints[0] = 7;
        CHECK(co_doc_skin_set(d, static_cast<int32_t>(joint_ids.size()), joint_ids.data(), static_cast<int32_t>(part_ids.size()),
                              part_ids.data(), part_rows.data(), row_vertices.data(), row_influences.data(),
                              bad_joints.data(), weights.data()) == CO_ERR_ARG);
        CHECK(std::string(co_last_error()).find("out of range") != std::string::npos);
        std::vector<int32_t> bad_rows = part_rows;
        bad_rows[0] = 1;
        CHECK(co_doc_skin_set(d, static_cast<int32_t>(joint_ids.size()), joint_ids.data(), static_cast<int32_t>(part_ids.size()),
                              part_ids.data(), bad_rows.data(), row_vertices.data(), row_influences.data(),
                              joints.data(), weights.data()) == CO_ERR_ARG);
        CHECK(co_doc_skin_set(d, static_cast<int32_t>(joint_ids.size()), joint_ids.data(), static_cast<int32_t>(part_ids.size()),
                              part_ids.data(), part_rows.data(), row_vertices.data(), row_influences.data(),
                              joints.data(), weights.data()) == CO_OK);
    }

    // Shading.
    CHECK(co_doc_shading_set_smooth(d, ref.shading.smooth.data(), static_cast<int64_t>(ref.shading.smooth.size())) == CO_OK);
    CHECK(co_doc_shading_set_sharp_edges(d, ref.shading.sharp_edges.data(),
                                         static_cast<int64_t>(ref.shading.sharp_edges.size() / 2)) == CO_OK);
    const float angle = *ref.shading.auto_smooth_angle;
    CHECK(co_doc_shading_set_auto_smooth(d, &angle) == CO_OK);
    CHECK(co_doc_shading_set_custom_normals(d, ref.shading.normal_vertices.data(), ref.shading.normal_oct.data(),
                                            static_cast<int64_t>(ref.shading.normal_vertices.size())) == CO_OK);

    // Vertex groups.
    {
        std::vector<const char*> names;
        std::vector<int32_t> group_parts{0};
        std::vector<const char*> part_ids;
        std::vector<int32_t> part_rows{0};
        std::vector<int32_t> vertices;
        std::vector<float> weights;
        for (const auto& vg : ref.vertex_groups) {
            names.push_back(vg.name.c_str());
            for (const auto& p : vg.parts) {
                part_ids.push_back(p.part_id.c_str());
                vertices.insert(vertices.end(), p.vertices.begin(), p.vertices.end());
                weights.insert(weights.end(), p.weights.begin(), p.weights.end());
                part_rows.push_back(static_cast<int32_t>(vertices.size()));
            }
            group_parts.push_back(static_cast<int32_t>(part_ids.size()));
        }
        CHECK(co_doc_vertex_groups_set(d, static_cast<int32_t>(names.size()), names.data(), group_parts.data(),
                                       part_ids.data(), part_rows.data(), vertices.data(), weights.data()) == CO_OK);
    }
    // Morphs.
    {
        std::vector<const char*> names;
        std::vector<float> params;
        std::vector<int32_t> morph_parts{0};
        std::vector<const char*> part_ids;
        std::vector<int32_t> flags;
        std::vector<int32_t> part_rows{0};
        std::vector<int32_t> vertices;
        std::vector<float> deltas;
        std::vector<float> normals;
        for (const auto& mo : ref.morphs) {
            names.push_back(mo.name.c_str());
            params.insert(params.end(), {mo.default_weight, mo.min, mo.max});
            for (const auto& p : mo.parts) {
                part_ids.push_back(p.part_id.c_str());
                flags.push_back(p.normals.empty() ? 0 : CO_MORPH_PART_NORMALS);
                vertices.insert(vertices.end(), p.vertices.begin(), p.vertices.end());
                deltas.insert(deltas.end(), p.deltas.begin(), p.deltas.end());
                if (p.normals.empty()) {
                    normals.insert(normals.end(), p.vertices.size() * 3, 0.f);
                } else {
                    normals.insert(normals.end(), p.normals.begin(), p.normals.end());
                }
                part_rows.push_back(static_cast<int32_t>(vertices.size()));
            }
            morph_parts.push_back(static_cast<int32_t>(part_ids.size()));
        }
        CHECK(co_doc_morphs_set(d, static_cast<int32_t>(names.size()), names.data(), params.data(), morph_parts.data(),
                                part_ids.data(), flags.data(), part_rows.data(), vertices.data(), deltas.data(),
                                nullptr) == CO_ERR_ARG);  // a part has normals, none given
        CHECK(co_doc_morphs_set(d, static_cast<int32_t>(names.size()), names.data(), params.data(), morph_parts.data(),
                                part_ids.data(), flags.data(), part_rows.data(), vertices.data(), deltas.data(),
                                normals.data()) == CO_OK);
    }

    // Unknown sections, entries, texture.
    CHECK(co_doc_unknown_set(d, "skin", "{}") == CO_ERR_ARG);
    CHECK(co_doc_unknown_set(d, "futureThing", "{not json") == CO_ERR_ARG);
    CHECK(co_doc_unknown_set(d, "futureThing", ref.unknown_sections[0].json.c_str()) == CO_OK);
    CHECK(co_doc_set_texture(d, u8(ref.texture), static_cast<int64_t>(ref.texture.size())) == CO_OK);
    CHECK(co_doc_entry_set(d, CO_ENTRIES_IMAGES, "notes.txt", u8(ref.texture), 1) == CO_ERR_ARG);
    CHECK(co_doc_entry_set(d, CO_ENTRIES_EXTRA, "material_9.png", u8(ref.texture), 1) == CO_ERR_ARG);
    CHECK(co_doc_entry_set(d, CO_ENTRIES_EXTRA, "manifest.json", u8(ref.texture), 1) == CO_ERR_ARG);
    CHECK(co_doc_entry_set(d, CO_ENTRIES_IMAGES, "material_1.png", u8(ref.images[0].data),
                           static_cast<int64_t>(ref.images[0].data.size())) == CO_OK);
    CHECK(co_doc_entry_set(d, CO_ENTRIES_EXTRA, "notes/readme.txt", u8(ref.extra_entries[0].data),
                           static_cast<int64_t>(ref.extra_entries[0].data.size())) == CO_OK);
    CHECK(co_doc_entry_set(d, CO_ENTRIES_EXTRA, "scratch.bin", u8(ref.texture), 2) == CO_OK);
    CHECK(co_doc_entry_set(d, CO_ENTRIES_EXTRA, "scratch.bin", nullptr, 0) == CO_OK);  // removed again
    CHECK(co_doc_entry_find(d, CO_ENTRIES_EXTRA, "scratch.bin") == -1);
    CHECK(co_doc_entry_find(d, CO_ENTRIES_IMAGES, "material_1.png") == 0);

    CHECK(std::string(co_doc_get_string(d, CO_FIELD_WRITTEN_VERSION)) == "1.9");
    CHECK((co_doc_flags(d) & CO_DOC_HAS_1_9_DATA) != 0);
    const std::vector<std::byte> via_abi = take(co_doc_write(d));
    const std::vector<std::byte> via_cpp = omo::write(ref);
    CHECK(!via_abi.empty());
    CHECK(manifest_of(via_abi) == manifest_of(via_cpp));
    CHECK(via_abi == via_cpp);
    CHECK(text(take(co_doc_write_manifest(d))) == manifest_of(via_cpp));

    // A clone writes the same bytes; changing the original does not touch it.
    co_doc* c = co_doc_clone(d);
    CHECK(co_doc_set_string(d, CO_FIELD_OBJECT_NAME, "changed") == CO_OK);
    CHECK(take(co_doc_write(c)) == via_cpp);
    co_doc_destroy(c);
    co_doc_destroy(d);
}

// Reads through the ABI and compares every accessor with the C++ document.
void abi_read_back(const std::vector<std::byte>& archive_bytes, const std::string& label) {
    const omo::Document ref = omo::read(archive_bytes);
    int32_t result = 1;
    co_doc* d = co_doc_read(u8(archive_bytes), static_cast<int64_t>(archive_bytes.size()), &result);
    CHECK(d != nullptr && result == CO_OK);
    if (d == nullptr) return;
    bool ok = true;
    auto expect = [&](bool cond, const char* what) {
        if (!cond) {
            std::fprintf(stderr, "abi read-back %s: %s\n", label.c_str(), what);
            ok = false;
        }
    };
    expect(co_doc_get_string(d, CO_FIELD_VERSION) == ref.version, "version");
    expect(co_doc_get_string(d, CO_FIELD_OBJECT_NAME) == ref.object_name, "objectName");
    expect(co_doc_get_string(d, CO_FIELD_MODEL_TYPE) == ref.model_type, "modelType");
    expect(co_doc_get_string(d, CO_FIELD_TEXTURE_FILE) == ref.texture_file, "textureFile");
    co_geometry g{};
    expect(co_doc_geometry_get(d, &g) == CO_OK && g.width == ref.geometry.width && g.depth == ref.geometry.depth &&
                   g.position[1] == ref.geometry.position.y,
           "geometry");
    float trs[9];
    expect(co_doc_model_transform_get(d, trs) == CO_OK && trs[0] == ref.model_transform.pos.x &&
                   trs[8] == ref.model_transform.scale.z,
           "model transform");
    const int32_t flags = co_doc_flags(d);
    expect(((flags & CO_DOC_HAS_MESH) != 0) == ref.mesh.has_value(), "mesh flag");
    expect(((flags & CO_DOC_HAS_FACE_TEXTURES) != 0) == ref.face_textures.has_value(), "faceTextures flag");
    expect(((flags & CO_DOC_CRLF) != 0) == ref.crlf, "crlf flag");
    if (ref.mesh) {
        const void* data = nullptr;
        const int64_t n = co_doc_mesh_array(d, CO_MESH_VERTICES, &data);
        expect(ref.mesh->vertices ? (n == static_cast<int64_t>(ref.mesh->vertices->size()) &&
                                     (n == 0 || std::memcmp(data, ref.mesh->vertices->data(), static_cast<std::size_t>(n) * 4) == 0))
                                  : n == -1,
               "vertices");
        const int64_t ni = co_doc_mesh_array(d, CO_MESH_INDICES, &data);
        expect(ref.mesh->indices ? ni == static_cast<int64_t>(ref.mesh->indices->size()) : ni == -1, "indices");
        expect(s_or_null(co_doc_get_string(d, CO_FIELD_UV_MODE)) == ref.mesh->uv_mode.value_or("<null>"), "uvMode");
    } else {
        expect(co_doc_mesh_array(d, CO_MESH_VERTICES, nullptr) == -1, "no mesh");
    }
    // Records.
    const int32_t nm = co_doc_face_mappings_get(d, nullptr, 0);
    std::vector<co_face_mapping> maps(static_cast<std::size_t>(std::max(nm, 0)));
    expect(co_doc_face_mappings_get(d, maps.data(), nm) == nm, "mappings count");
    const auto& rmaps = ref.face_textures ? ref.face_textures->mappings : std::vector<omo::FaceMapping>{};
    expect(maps.size() == rmaps.size(), "mappings size");
    for (std::size_t i = 0; ok && i < maps.size(); ++i) {
        expect(maps[i].face_id == rmaps[i].face_id && maps[i].u1 == rmaps[i].u1 &&
                       (maps[i].auto_resize != 0) == rmaps[i].auto_resize,
               "mapping");
    }
    const int32_t nmat = co_doc_materials_get(d, nullptr, 0);
    std::vector<co_material> mats(static_cast<std::size_t>(std::max(nmat, 0)));
    co_doc_materials_get(d, mats.data(), nmat);
    const auto& rmats = ref.face_textures ? ref.face_textures->materials : std::vector<omo::Material>{};
    expect(mats.size() == rmats.size(), "materials size");
    for (std::size_t i = 0; ok && i < mats.size(); ++i) {
        expect(s_or_null(mats[i].name) == rmats[i].name.value_or("<null>") &&
                       s_or_null(mats[i].texture_file) == rmats[i].texture_file.value_or("<null>") &&
                       mats[i].tint_color == rmats[i].tint_color && (mats[i].has_base_color != 0) == rmats[i].base_color.has_value(),
               "material");
    }
    const int32_t np = co_doc_parts_get(d, nullptr, 0);
    std::vector<co_part> parts(static_cast<std::size_t>(std::max(np, 0)));
    co_doc_parts_get(d, parts.data(), np);
    expect(parts.size() == ref.parts.size(), "parts size");
    for (std::size_t i = 0; ok && i < parts.size(); ++i) {
        const omo::Part& rp = ref.parts[i];
        expect(rp.id == parts[i].id && rp.name == parts[i].name && s_or_null(parts[i].parent_id) == rp.parent_id.value_or("<null>") &&
                       s_or_null(parts[i].bone_id) == rp.bone_id.value_or("<null>") && parts[i].origin[2] == rp.origin.z &&
                       parts[i].scale[1] == rp.scale.y && parts[i].vertex_start == rp.vertex_start &&
                       parts[i].face_count == rp.face_count && (parts[i].visible != 0) == rp.visible,
               "part");
    }
    const int32_t nb = co_doc_bones_get(d, nullptr, 0);
    std::vector<co_bone> bones(static_cast<std::size_t>(std::max(nb, 0)));
    co_doc_bones_get(d, bones.data(), nb);
    expect(bones.size() == ref.bones.size(), "bones size");
    for (std::size_t i = 0; ok && i < bones.size(); ++i) {
        expect(ref.bones[i].id == bones[i].id && bones[i].endpoint[1] == ref.bones[i].endpoint.y &&
                       s_or_null(bones[i].parent_bone_id) == ref.bones[i].parent_bone_id.value_or("<null>"),
               "bone");
    }
    const int32_t na = co_doc_attachments_get(d, nullptr, 0);
    std::vector<co_attachment> aps(static_cast<std::size_t>(std::max(na, 0)));
    co_doc_attachments_get(d, aps.data(), na);
    expect(aps.size() == ref.attachment_points.size(), "attachments size");
    for (std::size_t i = 0; ok && i < aps.size(); ++i) {
        expect(ref.attachment_points[i].name == aps[i].name && aps[i].scale[0] == ref.attachment_points[i].scale.x,
               "attachment");
    }
    // Skin CSR reassembled must equal the C++ skin.
    {
        int32_t nj = 0, nparts = 0, nrows = 0, ninf = 0;
        co_doc_skin_counts(d, &nj, &nparts, &nrows, &ninf);
        std::vector<const char*> jids(static_cast<std::size_t>(nj));
        std::vector<const char*> pids(static_cast<std::size_t>(nparts));
        std::vector<int32_t> prow(static_cast<std::size_t>(nparts) + 1);
        std::vector<int32_t> rv(static_cast<std::size_t>(nrows));
        std::vector<int32_t> rinf(static_cast<std::size_t>(nrows) + 1);
        std::vector<int32_t> js(static_cast<std::size_t>(ninf));
        std::vector<float> ws(static_cast<std::size_t>(ninf));
        expect(co_doc_skin_get(d, jids.data(), pids.data(), prow.data(), rv.data(), rinf.data(), js.data(), ws.data()) == CO_OK,
               "skin get");
        omo::Skin s;
        for (const char* j : jids) s.joints.emplace_back(j);
        for (int32_t p = 0; p < nparts; ++p) {
            omo::SkinPart sp;
            sp.part_id = pids[static_cast<std::size_t>(p)];
            for (int32_t r = prow[static_cast<std::size_t>(p)]; r < prow[static_cast<std::size_t>(p) + 1]; ++r) {
                if (sp.offsets.empty()) sp.offsets.push_back(0);
                sp.vertices.push_back(rv[static_cast<std::size_t>(r)]);
                for (int32_t k = rinf[static_cast<std::size_t>(r)]; k < rinf[static_cast<std::size_t>(r) + 1]; ++k) {
                    sp.joints.push_back(js[static_cast<std::size_t>(k)]);
                    sp.weights.push_back(ws[static_cast<std::size_t>(k)]);
                }
                sp.offsets.push_back(static_cast<int32_t>(sp.joints.size()));
            }
            s.parts.push_back(std::move(sp));
        }
        expect(s == ref.skin, "skin");
    }
    // Shading.
    {
        const uint32_t* words = nullptr;
        const int64_t nw = co_doc_shading_smooth(d, &words);
        expect(static_cast<std::size_t>(nw) == ref.shading.smooth.size() &&
                       (nw == 0 || std::memcmp(words, ref.shading.smooth.data(), static_cast<std::size_t>(nw) * 4) == 0),
               "smooth");
        const uint32_t* pairs = nullptr;
        expect(static_cast<std::size_t>(co_doc_shading_sharp_edges(d, &pairs)) * 2 == ref.shading.sharp_edges.size(), "sharp");
        float deg = 0.f;
        const int32_t has = co_doc_shading_auto_smooth(d, &deg);
        expect(ref.shading.auto_smooth_angle ? (has == 1 && deg == *ref.shading.auto_smooth_angle) : has == 0, "angle");
        const int32_t* nv = nullptr;
        const int16_t* oct = nullptr;
        const int64_t nn = co_doc_shading_custom_normals(d, &nv, &oct);
        expect(static_cast<std::size_t>(nn) == ref.shading.normal_vertices.size() &&
                       (nn == 0 || (nv[nn - 1] == ref.shading.normal_vertices.back() && oct[2 * nn - 1] == ref.shading.normal_oct.back())),
               "custom normals");
    }
    // Vertex groups.
    {
        int32_t ng = 0, npart = 0, nrow = 0;
        co_doc_vertex_group_counts(d, &ng, &npart, &nrow);
        std::vector<const char*> names(static_cast<std::size_t>(ng));
        std::vector<int32_t> gp(static_cast<std::size_t>(ng) + 1);
        std::vector<const char*> pids(static_cast<std::size_t>(npart));
        std::vector<int32_t> pr(static_cast<std::size_t>(npart) + 1);
        std::vector<int32_t> vs(static_cast<std::size_t>(nrow));
        std::vector<float> ws(static_cast<std::size_t>(nrow));
        co_doc_vertex_groups_get(d, names.data(), gp.data(), pids.data(), pr.data(), vs.data(), ws.data());
        std::vector<omo::VertexGroup> groups;
        for (int32_t gi = 0; gi < ng; ++gi) {
            omo::VertexGroup vg;
            vg.name = names[static_cast<std::size_t>(gi)];
            for (int32_t p = gp[static_cast<std::size_t>(gi)]; p < gp[static_cast<std::size_t>(gi) + 1]; ++p) {
                omo::GroupPart part;
                part.part_id = pids[static_cast<std::size_t>(p)];
                for (int32_t r = pr[static_cast<std::size_t>(p)]; r < pr[static_cast<std::size_t>(p) + 1]; ++r) {
                    part.vertices.push_back(vs[static_cast<std::size_t>(r)]);
                    part.weights.push_back(ws[static_cast<std::size_t>(r)]);
                }
                vg.parts.push_back(std::move(part));
            }
            groups.push_back(std::move(vg));
        }
        expect(groups == ref.vertex_groups, "vertex groups");
    }
    // Morphs.
    {
        int32_t nmo = 0, npart = 0, nrow = 0;
        co_doc_morph_counts(d, &nmo, &npart, &nrow);
        const auto um = static_cast<std::size_t>(nmo);
        const auto up = static_cast<std::size_t>(npart);
        const auto ur = static_cast<std::size_t>(nrow);
        std::vector<const char*> names(um);
        std::vector<float> params(3 * um);
        std::vector<int32_t> mp(um + 1);
        std::vector<const char*> pids(up);
        std::vector<int32_t> pflags(up);
        std::vector<int32_t> pr(up + 1);
        std::vector<int32_t> vs(ur);
        std::vector<float> ds(3 * ur);
        std::vector<float> ns(3 * ur);
        co_doc_morphs_get(d, names.data(), params.data(), mp.data(), pids.data(), pflags.data(), pr.data(), vs.data(),
                          ds.data(), ns.data());
        std::vector<omo::Morph> morphs;
        for (std::size_t mi = 0; mi < um; ++mi) {
            omo::Morph mo{names[mi], params[3 * mi], params[3 * mi + 1], params[3 * mi + 2], {}};
            for (auto p = static_cast<std::size_t>(mp[mi]); p < static_cast<std::size_t>(mp[mi + 1]); ++p) {
                omo::MorphPart part;
                part.part_id = pids[p];
                for (auto r = static_cast<std::size_t>(pr[p]); r < static_cast<std::size_t>(pr[p + 1]); ++r) {
                    part.vertices.push_back(vs[r]);
                    part.deltas.insert(part.deltas.end(), ds.begin() + static_cast<std::ptrdiff_t>(3 * r),
                                       ds.begin() + static_cast<std::ptrdiff_t>(3 * r + 3));
                    if ((pflags[p] & CO_MORPH_PART_NORMALS) != 0) {
                        part.normals.insert(part.normals.end(), ns.begin() + static_cast<std::ptrdiff_t>(3 * r),
                                            ns.begin() + static_cast<std::ptrdiff_t>(3 * r + 3));
                    }
                }
                mo.parts.push_back(std::move(part));
            }
            morphs.push_back(std::move(mo));
        }
        expect(morphs == ref.morphs, "morphs");
    }
    // Entries.
    const uint8_t* tex = nullptr;
    const int64_t tex_size = co_doc_texture(d, &tex);
    expect(static_cast<std::size_t>(tex_size) == ref.texture.size() &&
                   (tex_size == 0 || std::memcmp(tex, ref.texture.data(), static_cast<std::size_t>(tex_size)) == 0),
           "texture");
    expect(co_doc_entry_count(d, CO_ENTRIES_IMAGES) == static_cast<int32_t>(ref.images.size()), "images");
    expect(co_doc_entry_count(d, CO_ENTRIES_EXTRA) == static_cast<int32_t>(ref.extra_entries.size()), "extra");
    for (int32_t i = 0; ok && i < co_doc_entry_count(d, CO_ENTRIES_IMAGES); ++i) {
        const char* name = nullptr;
        const uint8_t* data = nullptr;
        int64_t size = 0;
        co_doc_entry_get(d, CO_ENTRIES_IMAGES, i, &name, &data, &size);
        const omo::Image& ri = ref.images[static_cast<std::size_t>(i)];
        expect(ri.name == name && static_cast<std::size_t>(size) == ri.data.size(), "image");
    }
    expect(co_doc_entry_get(d, CO_ENTRIES_IMAGES, 100000, nullptr, nullptr, nullptr) == CO_ERR_RANGE, "range");
    expect(co_doc_unknown_count(d) == static_cast<int32_t>(ref.unknown_sections.size()), "unknown");
    // Written through the ABI = written by C++.
    expect(take(co_doc_write(d)) == omo::write(ref), "write");
    co_doc_destroy(d);
    CHECK(ok);
}

void abi_errors() {
    int32_t result = 0;
    CHECK(co_doc_read(nullptr, 0, &result) == nullptr && result == CO_ERR_ARG);
    const std::vector<std::byte> good = omo::write(sample_document());
    CHECK(co_doc_read(u8(good), static_cast<int64_t>(good.size() / 3), &result) == nullptr && result == CO_ERR_FORMAT);
    CHECK(std::string(co_last_error()).find("zip") != std::string::npos);
    const std::vector<std::byte> newer = archive("{\"version\":\"2.0\"}");
    CHECK(co_doc_read(u8(newer), static_cast<int64_t>(newer.size()), &result) == nullptr && result == CO_ERR_VERSION);
    CHECK(std::string(co_last_error()).find("newer") != std::string::npos);
    const std::vector<std::byte> bad_json = archive("{\"version\":");
    CHECK(co_doc_read(u8(bad_json), static_cast<int64_t>(bad_json.size()), &result) == nullptr && result == CO_ERR_FORMAT);
    CHECK(std::string(co_last_error()).find("not valid JSON") != std::string::npos);

    int32_t order = 9;
    CHECK(co_version_compare("1.10", "1.9", &order) == CO_OK && order == 1);
    CHECK(co_version_compare("1.9", "1.9", &order) == CO_OK && order == 0);
    CHECK(co_version_compare("1.8", "1.10", &order) == CO_OK && order == -1);
    CHECK(co_version_compare("x", "1.9", &order) == CO_ERR_ARG);
    CHECK(co_version_compare(nullptr, "1.9", &order) == CO_ERR_ARG);

    co_doc* d = co_doc_create();
    CHECK(std::string(co_doc_get_string(d, CO_FIELD_VERSION)) == "1.8");
    CHECK(co_doc_set_string(d, CO_FIELD_VERSION, "2.0") == CO_ERR_ARG);
    CHECK(co_doc_set_string(d, CO_FIELD_VERSION, "1.x") == CO_ERR_ARG);
    CHECK(co_doc_set_string(d, CO_FIELD_VERSION, "1.10") == CO_OK);
    CHECK(co_doc_set_string(d, CO_FIELD_WRITTEN_VERSION, "1.9") == CO_ERR_ARG);
    CHECK(co_doc_get_string(d, 77) == nullptr);
    CHECK(co_doc_get_string(d, CO_FIELD_UV_MODE) == nullptr);
    CHECK(co_doc_mesh_array(d, CO_MESH_VERTICES, nullptr) == -1);
    co_part blank_part{};
    blank_part.id = " ";
    CHECK(co_doc_parts_set(d, &blank_part, 1) == CO_ERR_ARG);
    CHECK(co_doc_parts_set(d, nullptr, 2) == CO_ERR_ARG);
    CHECK(co_doc_parts_get(d, nullptr, -1) == CO_ERR_ARG);
    CHECK(co_doc_bones_set(d, nullptr, 0) == CO_OK);
    CHECK(co_doc_entry_set(d, CO_ENTRIES_EXTRA, "texture.omt", u8(good), 1) == CO_ERR_ARG);
    CHECK(co_doc_entry_set(d, 5, "x", u8(good), 1) == CO_ERR_ARG);
    CHECK(co_doc_unknown_get(d, 0, nullptr, nullptr) == CO_ERR_RANGE);
    // Flags: add / drop sections.
    CHECK(co_doc_set_flags(d, CO_DOC_HAS_MESH | CO_DOC_HAS_FACE_TEXTURES | CO_DOC_CRLF) == CO_OK);
    CHECK(co_doc_flags(d) == (CO_DOC_HAS_MESH | CO_DOC_HAS_FACE_TEXTURES | CO_DOC_CRLF));
    const std::string m = text(take(co_doc_write_manifest(d)));
    CHECK(m.find("\"mesh\" : {\r\n    \"vertices\" : null,") != std::string::npos);
    CHECK(m.find("\"faceTextures\" : {\r\n    \"mappings\" : [ ],\r\n    \"materials\" : [ ]\r\n  }") != std::string::npos);
    CHECK(co_doc_set_flags(d, 0) == CO_OK);
    CHECK(co_doc_flags(d) == 0);
    // Null handles.
    CHECK(co_doc_write(nullptr) == nullptr);
    CHECK(co_doc_flags(nullptr) == CO_ERR_ARG);
    CHECK(co_doc_skin_counts(nullptr, nullptr, nullptr, nullptr, nullptr) == CO_ERR_ARG);
    co_doc_destroy(d);
    co_doc_destroy(nullptr);
}

void abi_omt() {
    const omo::omt::Texture ref = sample_texture();
    co_omt* t = co_omt_create();
    CHECK(std::string(co_omt_version(t)) == "1.0");
    const co_omt_info info{ref.width, ref.height, ref.active_layer, 0};
    CHECK(co_omt_info_set(t, &info) == CO_OK);
    std::vector<co_omt_layer> layers;
    for (const auto& l : ref.layers) {
        layers.push_back({l.name.c_str(), l.data_file.c_str(), u8(l.png), static_cast<int64_t>(l.png.size()), l.opacity,
                          l.visible ? 1 : 0});
    }
    CHECK(co_omt_layers_set(t, layers.data(), static_cast<int32_t>(layers.size())) == CO_OK);
    const co_omt_mapping map{3, 7, 0.f, 0.25f, 0.5f, 1.f, 90, 0};
    CHECK(co_omt_mappings_set(t, &map, 1) == CO_OK);
    const co_omt_material mat{"Gold", "OPAQUE", "material_7.png", 7, 0};
    CHECK(co_omt_materials_set(t, &mat, 1) == CO_OK);
    CHECK(co_omt_entry_set(t, "layer_0.png", u8(ref.layers[0].png), 1) == CO_ERR_ARG);
    CHECK(co_omt_entry_set(t, "material_7.png", u8(ref.other_entries[0].data),
                           static_cast<int64_t>(ref.other_entries[0].data.size())) == CO_OK);
    const std::vector<std::byte> written = take(co_omt_write(t));
    CHECK(written == omo::omt::write(ref));
    co_omt_destroy(t);

    int32_t result = 1;
    t = co_omt_read(u8(written), static_cast<int64_t>(written.size()), &result);
    CHECK(t != nullptr && result == CO_OK);
    if (t != nullptr) {
        co_omt_info got{};
        CHECK(co_omt_info_get(t, &got) == CO_OK && got.width == 32 && got.height == 16 && got.active_layer == 1);
        co_omt_layer ls[4];
        CHECK(co_omt_layers_get(t, ls, 4) == 2);
        CHECK(std::string(ls[1].name) == "Paint \"overlay\"" && ls[1].visible == 0 && ls[1].opacity == 0.5f &&
              ls[1].png_size == static_cast<int64_t>(ref.layers[1].png.size()) &&
              std::memcmp(ls[1].png, ref.layers[1].png.data(), ref.layers[1].png.size()) == 0);
        co_omt_mapping mg{};
        CHECK(co_omt_mappings_get(t, &mg, 1) == 1 && mg.uv_rotation == 90 && mg.v0 == 0.25f);
        co_omt_material mm{};
        CHECK(co_omt_materials_get(t, &mm, 1) == 1 && std::string(mm.texture_file) == "material_7.png");
        CHECK(co_omt_entry_count(t) == 1);
        const char* name = nullptr;
        CHECK(co_omt_entry_get(t, 0, &name, nullptr, nullptr) == CO_OK && std::string(name) == "material_7.png");
        co_omt_destroy(t);
    }
    CHECK(co_omt_read(u8(written), 10, &result) == nullptr && result == CO_ERR_FORMAT);
}
}  // namespace

int main() {
    java_numbers();
    synthetic_round_trips();
    exact_layout();
    versions_and_unknown_keys();
    lenient_reading();
    error_paths();
    abi_fill_and_write();
    abi_read_back(omo::write(sample_document()), "sample");
    abi_errors();
    abi_omt();
    fixtures();
    golden();
    if (failures != 0) {
        std::fprintf(stderr, "omo: %d check(s) failed\n", failures);
        return 1;
    }
    std::printf("omo: all checks passed\n");
    return 0;
}
