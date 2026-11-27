// Cenda animation clips (cm_*, .omanim 1.2, OpenMason #155) conformance: every clip of every fixture SBE read
// and rewritten with bit-identical keys, 1.1 -> 1.2 by hand, the 1.2 sections round trip, refusals, notify firing
// (wraps, reverse play, long steps, non-looping ends, ranges), curves, sync markers, and role binding.

#include "cenda/anim_clip.h"
#include "cenda/character.h"
#include "cenda/rig.h"
#include "omformat/omanim.hpp"
#include "omformat/zip_archive.hpp"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#ifndef CENDA_FIXTURES_DIR
#define CENDA_FIXTURES_DIR ""
#endif

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

bool near(float a, float b, float eps = 1e-5f) { return std::fabs(a - b) <= eps; }

std::vector<uint8_t> bytes_of(const std::vector<std::byte>& v) {
    std::vector<uint8_t> out(v.size());
    if (!v.empty()) std::memcpy(out.data(), v.data(), v.size());
    return out;
}

std::vector<uint8_t> written(const cm_clip* c) {
    cc_bytes* b = cm_clip_write(c);
    if (b == nullptr) {
        std::fprintf(stderr, "write failed: %s\n", cm_last_error());
        return {};
    }
    std::vector<uint8_t> out(cc_bytes_data(b), cc_bytes_data(b) + cc_bytes_size(b));
    cc_bytes_destroy(b);
    return out;
}

cm_clip* read(const std::vector<uint8_t>& b, int32_t* rc = nullptr) {
    return cm_clip_read(b.data(), static_cast<int64_t>(b.size()), rc);
}

om::ZipEntry entry(const std::string& name, const std::string& text) {
    om::ZipEntry e;
    e.name = name;
    e.data.resize(text.size());
    std::memcpy(e.data.data(), text.data(), text.size());
    return e;
}

std::vector<uint8_t> archive(std::vector<om::ZipEntry> entries) { return bytes_of(om::write_zip(entries)); }

std::string manifest_of(const std::vector<uint8_t>& b) {
    for (const auto& e : om::read_zip(std::span<const std::byte>(reinterpret_cast<const std::byte*>(b.data()), b.size()))) {
        if (e.name == "manifest.json") return {reinterpret_cast<const char*>(e.data.data()), e.data.size()};
    }
    return {};
}

// Every track, key and header field equal, floats compared bit for bit.
bool same_clip(const cm_clip* a, const cm_clip* b) {
    cm_header ha{}, hb{};
    cm_clip_header_get(a, &ha);
    cm_clip_header_get(b, &hb);
    if (std::memcmp(&ha, &hb, sizeof ha) != 0) return false;
    for (int f : {CM_FIELD_NAME, CM_FIELD_MODEL_REF, CM_FIELD_LAYER_TYPE}) {
        if (std::strcmp(cm_clip_get_string(a, f), cm_clip_get_string(b, f)) != 0) return false;
    }
    if (cm_clip_model_ref_null(a) != cm_clip_model_ref_null(b)) return false;
    if (cm_clip_mask_count(a) != cm_clip_mask_count(b) || cm_clip_track_count(a) != cm_clip_track_count(b)) return false;
    for (int32_t i = 0; i < cm_clip_mask_count(a); ++i) {
        if (std::strcmp(cm_clip_mask_get(a, i), cm_clip_mask_get(b, i)) != 0) return false;
    }
    for (int32_t t = 0; t < cm_clip_track_count(a); ++t) {
        const char *ida, *idb, *na, *nb, *ra, *rb;
        int32_t ba, bb, ka, kb;
        cm_clip_track_get(a, t, &ida, &na, &ra, &ba, &ka);
        cm_clip_track_get(b, t, &idb, &nb, &rb, &bb, &kb);
        if (std::strcmp(ida, idb) || std::strcmp(na, nb) || std::strcmp(ra, rb) || ba != bb || ka != kb) return false;
        std::vector<cr_key> x(static_cast<std::size_t>(ka)), y(static_cast<std::size_t>(kb));
        cm_clip_track_keys(a, t, x.data(), ka);
        cm_clip_track_keys(b, t, y.data(), kb);
        if (!x.empty() && std::memcmp(x.data(), y.data(), x.size() * sizeof(cr_key)) != 0) return false;
    }
    if (cm_clip_morph_count(a) != cm_clip_morph_count(b)) return false;  // 1.3 (#171)
    for (int32_t m = 0; m < cm_clip_morph_count(a); ++m) {
        const char *na, *nb;
        int32_t ka, kb;
        cm_clip_morph_get(a, m, &na, &ka);
        cm_clip_morph_get(b, m, &nb, &kb);
        if (std::strcmp(na, nb) || ka != kb) return false;
        std::vector<cm_curve_key> x(static_cast<std::size_t>(ka)), y(static_cast<std::size_t>(kb));
        cm_clip_morph_keys(a, m, x.data(), ka);
        cm_clip_morph_keys(b, m, y.data(), kb);
        if (!x.empty() && std::memcmp(x.data(), y.data(), x.size() * sizeof(cm_curve_key)) != 0) return false;
    }
    return true;
}

void test_every_fixture_clip() {
    const std::filesystem::path root = CENDA_FIXTURES_DIR;
    if (root.empty() || !std::filesystem::is_directory(root)) {
        std::printf("anim_clip: fixtures not found at '%s'; skipping the fixture round trip\n", CENDA_FIXTURES_DIR);
        return;
    }
    int clips = 0;
    for (const auto& file : std::filesystem::recursive_directory_iterator(root)) {
        if (file.path().extension() != ".sbe") continue;
        std::ifstream in(file.path(), std::ios::binary);
        std::vector<char> raw((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
        auto sbe = om::read_zip(std::span<const std::byte>(reinterpret_cast<const std::byte*>(raw.data()), raw.size()));
        for (const auto& e : sbe) {
            if (e.name.size() < 7 || e.name.substr(e.name.size() - 7) != ".omanim") continue;
            const auto original = bytes_of(e.data);
            int32_t rc = 0;
            cm_clip* a = read(original, &rc);
            CHECK(a != nullptr);
            if (a == nullptr) {
                std::fprintf(stderr, "  %s/%s: %s\n", file.path().filename().c_str(), e.name.c_str(), cm_last_error());
                continue;
            }
            const auto once = written(a);
            cm_clip* b = read(once);
            CHECK(b != nullptr && same_clip(a, b));
            CHECK(written(b) == once);  // 1.2 -> 1.2 byte-identical
            CHECK(manifest_of(once).find("\"version\": \"1.2\"") != std::string::npos);
            // #171: no morph tracks -> still 1.2, no morphs section; giving it morph tracks writes 1.3, and taking them
            // away again writes the 1.2 bytes once more.
            CHECK(cm_clip_morph_count(a) == 0 && manifest_of(once).find("\"morphs\"") == std::string::npos);
            const cm_curve_key mk[2] = {{0.f, 0.f, 0.f, 0.f, CM_INTERP_LINEAR}, {0.5f, 1.f, 0.f, 0.f, CM_INTERP_LINEAR}};
            CHECK(cm_clip_morph_set(b, "Smile", mk, 2) == 0);
            const auto with = written(b);
            CHECK(manifest_of(with).find("\"version\": \"1.3\"") != std::string::npos);
            cm_clip* c = read(with);
            CHECK(c != nullptr && same_clip(b, c) && written(c) == with);
            if (c != nullptr) cm_clip_destroy(c);
            CHECK(cm_clip_morph_set(b, "Smile", nullptr, 0) == CM_OK && written(b) == once);
            cm_clip_destroy(a);
            cm_clip_destroy(b);
            ++clips;
        }
    }
    CHECK(clips > 50);
    std::printf("anim_clip: %d fixture clips round-tripped\n", clips);
}

const char* kV11Manifest = R"({
  "version" : "1.1",
  "name" : "Walk",
  "fps" : 30.0,
  "duration" : 0.96,
  "loop" : true,
  "modelRef" : null,
  "tracks" : [ {
    "partId" : "p1",
    "partName" : "root",
    "dataFile" : "track_p1.json"
  }, {
    "partId" : "b2",
    "partName" : "arm",
    "dataFile" : "track_b2.json",
    "targetType" : "bone"
  } ],
  "requiredParts" : [ "p1", "b2" ],
  "layer" : {
    "type" : "OVERLAY",
    "maskParts" : [ "arm" ],
    "fadeInSeconds" : 0.2,
    "fadeOutSeconds" : 0.15,
    "priority" : 3
  }
})";

// OMANIM 1.3 morph tracks (#171): keys / interp / tangents round-trip bit-identically, names unique, a leading
// "morph:" stripped, sampling = the rig clip's, 1.2 readers' view (an unknown section), refusals.
void test_morph_tracks() {
    cm_clip* c = cm_clip_create();
    cm_header h{30.f, 2.f, 1, 0, 0.1f, 0.1f, 0};
    CHECK(cm_clip_header_set(c, &h) == CM_OK);
    const cm_curve_key smile[3] = {{0.f, 0.f, 0.f, 0.f, CM_INTERP_LINEAR},
                                   {1.f, 0.75f, 0.f, 0.f, CM_INTERP_CUBIC},
                                   {0.4f, 1.3f, 0.5f, -2.25f, CM_INTERP_CUBIC}};  // unsorted: sorted on set
    const cm_curve_key blink[2] = {{0.f, 1.f, 0.f, 0.f, CM_INTERP_STEP}, {0.25f, 0.f, 0.f, 0.f, CM_INTERP_STEP}};
    CHECK(cm_clip_morph_set(c, "morph:Smile", smile, 3) == 0);
    CHECK(cm_clip_morph_set(c, "Blink", blink, 2) == 1);
    CHECK(cm_clip_morph_find(c, "Smile") == 0 && cm_clip_morph_find(c, "morph:Blink") == 1 && cm_clip_morph_find(c, "x") == -1);
    const cm_curve_key bad{0.f, std::nanf(""), 0.f, 0.f, CM_INTERP_LINEAR};
    CHECK(cm_clip_morph_set(c, "Bad", &bad, 1) == CM_ERR_ARG && cm_clip_morph_count(c) == 2);
    CHECK(cm_clip_morph_set(c, "", smile, 1) == CM_ERR_ARG && cm_clip_morph_set(c, "morph:", smile, 1) == CM_ERR_ARG);
    cm_curve_key keys[3];
    CHECK(cm_clip_morph_keys(c, 0, keys, 3) == 3);
    CHECK(keys[0].time == 0.f && keys[1].time == 0.4f && keys[2].time == 1.f && keys[1].in_tangent == 0.5f);
    // Sampling: clamped, Hermite between the CUBIC keys, step.
    CHECK(cm_clip_morph_sample(c, 0, -1.f) == 0.f && cm_clip_morph_sample(c, 0, 5.f) == 0.75f);
    CHECK(near(cm_clip_morph_sample(c, 0, 0.2f), 0.65f));  // LINEAR 0 -> 1.3 at 0.4
    CHECK(cm_clip_morph_sample(c, 1, 0.1f) == 1.f && cm_clip_morph_sample(c, 1, 0.3f) == 0.f);
    const auto bytes = written(c);
    const std::string man = manifest_of(bytes);
    CHECK(man.find("\"version\": \"1.3\"") != std::string::npos && man.find("\"morphs\"") != std::string::npos);
    cm_clip* r = read(bytes);
    CHECK(r != nullptr && same_clip(c, r) && written(r) == bytes);
    // The LINEAR key at 0 carries no tangents; the CUBIC keys keep theirs (also a non-CUBIC key's non-zero tangent).
    for (float t : {0.f, 0.1f, 0.33f, 0.4f, 0.7f, 0.99f, 1.f, 1.5f}) {
        CHECK(cm_clip_morph_sample(r, 0, t) == cm_clip_morph_sample(c, 0, t));
    }
    // A rig clip carrying the tracks samples the same floats.
    cr_clip_desc d{2.f, 1, CR_LAYER_BASE, 0, 0.f, 0.f};
    cr_clip* rc = cr_clip_create(&d, nullptr, 0, nullptr, 0, nullptr, nullptr, nullptr, 0);
    CHECK(rc != nullptr && cm_clip_apply_morphs(c, rc) == CM_OK && cr_clip_morph_track_count(rc) == 2);
    CHECK(std::strcmp(cr_clip_morph_track_name(rc, 0), "Smile") == 0);
    for (float t : {0.f, 0.1f, 0.33f, 0.4f, 0.7f, 0.99f, 1.f, 1.5f}) {
        CHECK(cr_clip_sample_morph(rc, 0, t) == cm_clip_morph_sample(c, 0, t));
        CHECK(cr_clip_sample_morph(rc, 1, t) == cm_clip_morph_sample(c, 1, t));
    }
    cr_clip_destroy(rc);
    // A 1.2 reader keeps "morphs" as an unknown section: the libomformat model of a 1.3 file read by name.
    const auto clip = om::omanim::read(std::span<const std::byte>(reinterpret_cast<const std::byte*>(bytes.data()), bytes.size()));
    CHECK(clip.version == "1.3" && clip.morphs.size() == 2 && clip.unknown_sections.empty());
    // Duplicate names in a file are refused.
    {
        om::omanim::Clip dup;
        dup.morphs.push_back({"A", {{0.f, 1.f, 0.f, 0.f, om::omanim::CurveInterp::Linear}}});
        dup.morphs.push_back({"A", {{0.f, 1.f, 0.f, 0.f, om::omanim::CurveInterp::Linear}}});
        const auto dbytes = bytes_of(om::omanim::write(dup));
        int32_t res = 0;
        CHECK(read(dbytes, &res) == nullptr && res == CM_ERR_FORMAT);
    }
    if (r != nullptr) cm_clip_destroy(r);
    cm_clip_destroy(c);
}

void test_v11_to_v12() {
    const std::string t1 = R"({"partId":"p1","keyframes":[{"time":0.0,"position":[0.0,-0.023456722,0.0],"rotation":[1.1,2.2,3.3],"scale":[1.0,1.0,1.0],"easing":"LINEAR"},{"time":0.48,"position":[0.1,0.2,0.3],"rotation":[0.0,0.0,0.0],"scale":[1.0,2.0,1.0],"easing":"STEP"}]})";
    const std::string t2 = R"({"partId":"b2","keyframes":[{"time":0.12,"position":[0.0,0.0,0.0],"rotation":[-45.0,10.5,0.0],"scale":[1.0,1.0,1.0],"easing":"EASE_IN_OUT"}]})";
    const auto v11 = archive({entry("manifest.json", kV11Manifest), entry("track_p1.json", t1), entry("track_b2.json", t2)});
    cm_clip* c = read(v11);
    CHECK(c != nullptr);
    if (c == nullptr) return;
    CHECK(std::string(cm_clip_get_string(c, CM_FIELD_VERSION)) == "1.1");
    cm_header h{};
    cm_clip_header_get(c, &h);
    CHECK(h.loop == 1 && h.has_layer == 1 && h.priority == 3 && near(h.fade_in, 0.2f) && near(h.duration, 0.96f));
    CHECK(std::string(cm_clip_get_string(c, CM_FIELD_LAYER_TYPE)) == "OVERLAY");
    CHECK(cm_clip_model_ref_null(c) == 1);
    cr_key k[2];
    CHECK(cm_clip_track_keys(c, 0, k, 2) == 2);
    CHECK(k[0].pos[1] == -0.023456722f && k[1].easing == CR_EASE_STEP && k[1].scale[1] == 2.f);
    int32_t bone = 0;
    cm_clip_track_get(c, 1, nullptr, nullptr, nullptr, &bone, nullptr);
    CHECK(bone == 1);
    cm_clip_track_keys(c, 1, k, 1);
    CHECK(k[0].easing == CR_EASE_IN_OUT && k[0].rot_deg[0] == -45.f);

    const auto v12 = written(c);
    cm_clip* back = read(v12);
    CHECK(back != nullptr && same_clip(c, back));
    const std::string m = manifest_of(v12);
    CHECK(m.find("\"modelRef\": null") != std::string::npos);
    CHECK(m.find("\"targetType\": \"bone\"") != std::string::npos);
    CHECK(m.find("\"dataFile\": \"track_p1.json\"") != std::string::npos);  // data file names kept
    CHECK(m.find("notifies") == std::string::npos);                         // nothing 1.2 added unasked
    cm_clip_destroy(back);
    cm_clip_destroy(c);

    // A 1.0 clip (no layer block) stays one.
    cm_clip* old = read(archive({entry("manifest.json", R"({"name":"Old","fps":24,"duration":1,"tracks":[]})")}));
    CHECK(old != nullptr);
    if (old != nullptr) {
        cm_clip_header_get(old, &h);
        CHECK(h.has_layer == 0 && std::string(cm_clip_get_string(old, CM_FIELD_VERSION)) == "1.0");
        CHECK(manifest_of(written(old)).find("\"layer\"") == std::string::npos);
        cm_clip_destroy(old);
    }
}

void test_sections_round_trip() {
    cm_clip* c = cm_clip_create();
    cm_header h{30.f, 1.f, 1, 0, 0.1f, 0.1f, 0};
    CHECK(cm_clip_header_set(c, &h) == CM_OK);
    cr_key key{};
    key.scale[0] = key.scale[1] = key.scale[2] = 1.f;
    CHECK(cm_clip_track_set(c, nullptr, nullptr, "upper_arm_l", 1, &key, 1) == 0);
    CHECK(cm_clip_track_set(c, "j-root", "Root", nullptr, 0, &key, 1) == 1);
    CHECK(cm_clip_track_set(c, nullptr, nullptr, "upper_arm_l", 1, &key, 1) == 0);  // replaced, not added
    CHECK(cm_clip_notify_add(c, "footstep", 0.5f, 0.f, R"({"foot":"left","volume":0.8})") == 0);
    CHECK(cm_clip_notify_add(c, "swing", 0.2f, 0.3f, nullptr) == 0);  // sorted by time
    CHECK(cm_clip_notify_add(c, "bad", 0.1f, 0.f, "[1]") == CM_ERR_ARG);
    cm_curve_key ck[2] = {{0.f, 0.f, 0.f, 2.f, CM_INTERP_CUBIC}, {1.f, 1.f, 0.f, 0.f, CM_INTERP_LINEAR}};
    CHECK(cm_clip_curve_set(c, "foot_lock_l", ck, 2) == 0);
    CHECK(cm_clip_marker_add(c, "foot_down_r", 0.6f) == 0);
    CHECK(cm_clip_marker_add(c, "foot_down_l", 0.1f) == 0);
    CHECK(cm_clip_root_motion_set(c, "role:hips", CM_ROOT_X | CM_ROOT_Z | CM_ROOT_YAW) == CM_OK);
    CHECK(cm_clip_additive_set(c, CM_ADDITIVE_CLIP, 0.25f, "idle", 1) == CM_OK);
    CHECK(cm_clip_additive_set(c, CM_ADDITIVE_CLIP, 0.f, "", 0) == CM_ERR_ARG);

    const auto once = written(c);
    cm_clip* back = read(once);
    CHECK(back != nullptr);
    if (back == nullptr) return;
    CHECK(written(back) == once);
    CHECK(same_clip(c, back));
    const char *name, *payload, *role, *pid;
    float t, d;
    cm_clip_notify_get(back, 1, &name, &t, &d, &payload);
    CHECK(std::string(name) == "footstep" && std::string(payload) == R"({"foot":"left","volume":0.8})");
    cm_clip_notify_get(back, 0, &name, &t, &d, &payload);
    CHECK(std::string(name) == "swing" && near(d, 0.3f) && payload[0] == '\0');
    cm_clip_track_get(back, 0, &pid, nullptr, &role, nullptr, nullptr);
    CHECK(std::string(role) == "upper_arm_l" && std::string(pid) == "role:upper_arm_l");
    cm_curve_key out[2];
    CHECK(cm_clip_curve_keys(back, cm_clip_curve_find(back, "foot_lock_l"), out, 2) == 2);
    CHECK(out[0].interp == CM_INTERP_CUBIC && out[0].out_tangent == 2.f);
    cm_clip_marker_get(back, 0, &name, &t);
    CHECK(std::string(name) == "foot_down_l");
    const char* joint;
    uint32_t axes = 0;
    cm_clip_root_motion_get(back, &joint, &axes);
    CHECK(std::string(joint) == "role:hips" && axes == (CM_ROOT_X | CM_ROOT_Z | CM_ROOT_YAW));
    int32_t ref = 0, mesh = 0;
    const char* clip_ref;
    cm_clip_additive_get(back, &ref, &t, &clip_ref, &mesh);
    CHECK(ref == CM_ADDITIVE_CLIP && near(t, 0.25f) && std::string(clip_ref) == "idle" && mesh == 1);
    cm_clip_destroy(back);
    cm_clip_destroy(c);

    // Unknown sections and entries survive.
    const auto future = archive({entry("manifest.json", R"({"version":"1.7","name":"F","duration":1,"tracks":[],"ikHints":{"a":[1,2]}})"),
                                 entry("thumb.png", "png!")});
    cm_clip* f = read(future);
    CHECK(f != nullptr);
    if (f != nullptr) {
        const auto rewritten = written(f);
        CHECK(manifest_of(rewritten).find("\"ikHints\"") != std::string::npos);
        bool png = false;
        for (const auto& e : om::read_zip(std::span<const std::byte>(reinterpret_cast<const std::byte*>(rewritten.data()), rewritten.size()))) {
            png |= e.name == "thumb.png";
        }
        CHECK(png);
        cm_clip_destroy(f);
    }
}

void test_refusals() {
    int32_t rc = 0;
    const std::vector<uint8_t> junk = {1, 2, 3};
    CHECK(read(junk, &rc) == nullptr && rc == CM_ERR_FORMAT);
    CHECK(read(archive({entry("manifest.json", R"({"tracks":[{"partId":"a","dataFile":"track_a.json"}]})")}), &rc) == nullptr &&
          rc == CM_ERR_FORMAT);
    CHECK(read(archive({entry("manifest.json", R"({"notifies":[{"name":"x","time":0,"duration":-1}]})")}), &rc) == nullptr);
    CHECK(read(archive({entry("manifest.json", R"({"curves":[{"name":"c","keys":[{"time":0,"value":1,"interp":"BOUNCY"}]}]})")}), &rc) == nullptr);
    CHECK(read(archive({entry("manifest.json", R"({"additive":{"reference":"clip"}})")}), &rc) == nullptr);
    CHECK(read(archive({entry("manifest.json", R"({"name":"a","name":"b"})")}), &rc) == nullptr);
}

std::string fired(const cm_clip* c, float prev, float delta) {
    cm_fired out[32];
    const int32_t n = cm_clip_fire(c, prev, delta, out, 32);
    std::string s;
    for (int32_t i = 0; i < n; ++i) {
        const char* name;
        cm_clip_notify_get(c, out[i].notify, &name, nullptr, nullptr, nullptr);
        s += name;
        if (out[i].phase == CM_NOTIFY_BEGIN) s += "+";
        if (out[i].phase == CM_NOTIFY_END) s += "-";
        s += " ";
    }
    return s;
}

void test_notify_firing() {
    cm_clip* c = cm_clip_create();
    cm_header h{30.f, 1.f, 1, 0, 0.1f, 0.1f, 0};
    cm_clip_header_set(c, &h);
    cm_clip_notify_add(c, "A", 0.f, 0.f, nullptr);
    cm_clip_notify_add(c, "B", 0.5f, 0.f, nullptr);
    cm_clip_notify_add(c, "R", 0.8f, 0.4f, nullptr);  // a range: begin 0.8, end wraps to 0.2

    CHECK(fired(c, 0.f, 0.25f) == "A R- ");        // [0, 0.25)
    CHECK(fired(c, 0.25f, 0.25f) == "");           // [0.25, 0.5): B at 0.5 is the next step's
    CHECK(fired(c, 0.5f, 0.1f) == "B ");
    CHECK(fired(c, 0.9f, 0.2f) == "A ");           // wraps: [0.9, 1) + [0, 0.1)
    CHECK(fired(c, 0.9f, 0.35f) == "A R- ");
    CHECK(fired(c, 1.9f, 0.2f) == "A ");           // prev is wrapped first
    // Reverse play: (prev + delta, prev].
    CHECK(fired(c, 0.6f, -0.2f) == "B ");
    CHECK(fired(c, 0.1f, -0.25f) == "A ");         // [0, 0.1] then (0.85, 1)
    CHECK(fired(c, 0.1f, -0.35f) == "A R+ ");
    // A step as long as the clip or longer: each edge once, in playback order from prev.
    CHECK(fired(c, 0.3f, 2.5f) == "B R+ A R- ");
    CHECK(fired(c, 0.3f, -5.f) == "R- A R+ B ");
    CHECK(fired(c, 0.3f, 0.f) == "");

    // Steady play: three cycles in uneven steps fire every edge exactly three times.
    int counts[3][3] = {};
    double total = 0.0;  // one clock: the clip time is derived from it every step
    const float steps[] = {0.07f, 0.013f, 0.11f, 0.05f};
    for (int i = 0; total < 3.0 - 1e-9; ++i) {
        const double dt = std::min(static_cast<double>(steps[i % 4]), 3.0 - total);
        const auto t = static_cast<float>(std::fmod(total, 1.0));
        cm_fired step[8];
        const int32_t n = cm_clip_fire(c, t, static_cast<float>(dt), step, 8);
        for (int32_t k = 0; k < n; ++k) ++counts[step[k].notify][step[k].phase];
        total += dt;
    }
    // notify order after sorting: A (0), B (0.5), R (0.8).
    if (counts[0][CM_NOTIFY_POINT] != 3 || counts[1][CM_NOTIFY_POINT] != 3) {
        std::fprintf(stderr, "    counts A=%d B=%d R+=%d R-=%d\n", counts[0][0], counts[1][0], counts[2][1], counts[2][2]);
    }
    CHECK(counts[0][CM_NOTIFY_POINT] == 3 && counts[1][CM_NOTIFY_POINT] == 3);
    CHECK(counts[2][CM_NOTIFY_BEGIN] == 3 && counts[2][CM_NOTIFY_END] == 3);
    cm_clip_destroy(c);

    // Non-looping: ends stop playback; the edge at the end fires once when reached.
    c = cm_clip_create();
    cm_header once{30.f, 1.f, 0, 0, 0.1f, 0.1f, 0};
    cm_clip_header_set(c, &once);
    cm_clip_notify_add(c, "Q", 0.f, 0.f, nullptr);
    cm_clip_notify_add(c, "P", 1.f, 0.f, nullptr);
    CHECK(fired(c, 0.f, 0.5f) == "Q ");
    CHECK(fired(c, 0.5f, 0.7f) == "P ");
    CHECK(fired(c, 1.f, 0.3f) == "");
    CHECK(fired(c, 1.f, -0.5f) == "P ");
    CHECK(fired(c, 0.5f, -0.9f) == "Q ");
    CHECK(fired(c, 0.f, -0.2f) == "");
    // The count beyond capacity.
    CHECK(cm_clip_fire(c, 0.f, 5.f, nullptr, 0) == 2);
    cm_clip_destroy(c);
}

void test_curves_and_markers() {
    cm_clip* c = cm_clip_create();
    cm_header h{30.f, 1.f, 1, 0, 0.1f, 0.1f, 0};
    cm_clip_header_set(c, &h);
    cm_curve_key lin[2] = {{0.f, 0.f, 0, 0, CM_INTERP_LINEAR}, {1.f, 10.f, 0, 0, CM_INTERP_LINEAR}};
    cm_curve_key step[2] = {{0.f, 2.f, 0, 0, CM_INTERP_STEP}, {1.f, 5.f, 0, 0, CM_INTERP_LINEAR}};
    cm_curve_key ease[2] = {{0.f, 0.f, 0, 0, CM_INTERP_CUBIC}, {1.f, 1.f, 0, 0, CM_INTERP_LINEAR}};
    cm_curve_key slope[2] = {{0.f, 0.f, 0, 1.f, CM_INTERP_CUBIC}, {1.f, 1.f, 1.f, 0, CM_INTERP_LINEAR}};
    const int32_t a = cm_clip_curve_set(c, "lin", lin, 2);
    const int32_t b = cm_clip_curve_set(c, "step", step, 2);
    const int32_t e = cm_clip_curve_set(c, "ease", ease, 2);
    const int32_t s = cm_clip_curve_set(c, "slope", slope, 2);
    CHECK(near(cm_clip_curve_sample(c, a, 0.25f), 2.5f));
    CHECK(near(cm_clip_curve_sample(c, a, -1.f), 0.f) && near(cm_clip_curve_sample(c, a, 3.f), 10.f));
    CHECK(near(cm_clip_curve_sample(c, b, 0.99f), 2.f) && near(cm_clip_curve_sample(c, b, 1.f), 5.f));
    CHECK(near(cm_clip_curve_sample(c, e, 0.5f), 0.5f) && cm_clip_curve_sample(c, e, 0.25f) < 0.25f);  // eased
    CHECK(near(cm_clip_curve_sample(c, s, 0.25f), 0.25f));  // tangents 1 = the straight line
    CHECK(cm_clip_curve_set(c, "lin", nullptr, 0) == CM_OK && cm_clip_curve_find(c, "lin") == -1);

    cm_clip_marker_add(c, "foot_down_r", 0.6f);
    cm_clip_marker_add(c, "foot_down_l", 0.1f);
    int32_t m = -2;
    float f = -1.f;
    cm_clip_marker_position(c, 0.35f, &m, &f);
    CHECK(m == 0 && near(f, 0.5f));  // halfway from foot_down_l (0.1) to foot_down_r (0.6)
    cm_clip_marker_position(c, 0.05f, &m, &f);
    CHECK(m == 1 && near(f, 0.9f));  // wrapped: foot_down_r (0.6 - 1) .. foot_down_l (0.1)
    CHECK(near(cm_clip_marker_time(c, 1, 0.9f), 0.05f));
    CHECK(near(cm_clip_marker_time(c, 0, 0.5f), 0.35f));
    cm_clip_marker_position(c, 0.85f, &m, &f);
    CHECK(m == 1 && near(f, 0.5f));
    // Non-looping: before the first marker is span -1, after the last runs to the end.
    cm_header once{30.f, 1.f, 0, 0, 0.1f, 0.1f, 0};
    cm_clip_header_set(c, &once);
    cm_clip_marker_position(c, 0.05f, &m, &f);
    CHECK(m == -1 && near(f, 0.5f));
    cm_clip_marker_position(c, 0.8f, &m, &f);
    CHECK(m == 1 && near(f, 0.5f));
    cm_clip_destroy(c);
}

void test_roles() {
    std::vector<cr_joint_desc> d(2);
    for (auto& j : d) {
        j.kind = CR_JOINT_BONE;
        j.scale[0] = j.scale[1] = j.scale[2] = 1.f;
    }
    d[0].parent = -1;
    d[1].parent = 0;
    const char* ids[] = {"j-hips", "j-spine"};
    const char* names[] = {"Hips", "Spine"};
    cr_rig* rig = cr_rig_create(d.data(), 2, ids, names);
    cc_char* ch = cc_char_create();
    cc_char_set_skeleton(ch, rig);
    cc_char_role_set(ch, "hips", "j-hips");
    cc_char_role_set(ch, "spine", "j-spine");

    cm_clip* c = cm_clip_create();
    cr_key key{};
    key.scale[0] = key.scale[1] = key.scale[2] = 1.f;
    key.rot_deg[1] = 30.f;
    cm_clip_track_set(c, nullptr, nullptr, "hips", 0, &key, 1);
    cm_clip_track_set(c, nullptr, nullptr, "tail_1", 0, &key, 1);
    cm_clip_track_set(c, "j-other", "Other", nullptr, 0, &key, 1);
    cm_clip_root_motion_set(c, "role:hips", CM_ROOT_X);

    int32_t dropped = -1;
    cm_clip* bound = cm_clip_bind_roles(c, ch, &dropped);
    CHECK(bound != nullptr && dropped == 1);
    CHECK(cm_clip_track_count(bound) == 2);
    const char *pid, *pname, *role;
    int32_t bone = 0;
    cm_clip_track_get(bound, 0, &pid, &pname, &role, &bone, nullptr);
    CHECK(std::string(pid) == "j-hips" && std::string(pname) == "Hips" && role[0] == '\0' && bone == 1);
    const char* joint;
    cm_clip_root_motion_get(bound, &joint, nullptr);
    CHECK(std::string(joint) == "j-hips");

    cm_clip* portable = cm_clip_to_roles(bound, ch);
    cm_clip_track_get(portable, 0, &pid, nullptr, &role, nullptr, nullptr);
    CHECK(std::string(role) == "hips" && std::string(pid) == "role:hips");
    cm_clip_track_get(portable, 1, &pid, nullptr, &role, nullptr, nullptr);
    CHECK(std::string(pid) == "j-other" && role[0] == '\0');  // no role: stays joint-addressed
    cm_clip_root_motion_get(portable, &joint, nullptr);
    CHECK(std::string(joint) == "role:hips");
    cr_key back{};
    cm_clip_track_keys(portable, 0, &back, 1);
    CHECK(back.rot_deg[1] == 30.f);

    cm_clip_destroy(portable);
    cm_clip_destroy(bound);
    cm_clip_destroy(c);
    cc_char_destroy(ch);
    cr_rig_destroy(rig);
}


// Sync groups (OpenMason #156): a 1.0 s walk leads a 0.6 s run; the run keeps the walk's marker phase.
cm_clip* sync_clip(float duration, std::initializer_list<std::pair<const char*, float>> markers) {
    cm_clip* c = cm_clip_create();
    cm_header h{30.f, duration, 1, 0, 0.f, 0.f, 0};
    cm_clip_header_set(c, &h);
    for (const auto& m : markers) cm_clip_marker_add(c, m.first, m.second);
    return c;
}

std::string marker_name(const cm_clip* c, int32_t index) {
    const char* name = nullptr;
    float t = 0.f;
    if (index < 0 || cm_clip_marker_get(c, index, &name, &t) != CM_OK) return "";
    return name;
}

void test_sync_groups() {
    cm_clip* walk = sync_clip(1.0f, {{"L", 0.1f}, {"R", 0.6f}});
    cm_clip* run = sync_clip(0.6f, {{"L", 0.05f}, {"R", 0.35f}});
    cm_clip* hop = sync_clip(0.8f, {{"L", 0.f}, {"R", 0.2f}, {"L", 0.4f}, {"R", 0.6f}});  // two cycles
    cm_clip* plain = sync_clip(2.0f, {});
    cm_sync_member g[4] = {{walk, 0.f, 1.f, 0.7f, 0}, {run, 0.3f, 1.f, 0.3f, 0}, {hop, 0.f, 1.f, 0.f, 0},
                           {plain, 0.f, 1.f, 0.f, 0}};
    float hop_prev = 0.f;
    float hop_travel = 0.f;
    for (int step = 0; step < 400; ++step) {
        CHECK(cm_sync_advance(g, 4, -1, 1.f / 60.f) == 0);  // the heaviest leads
        int32_t lm = -1, fm = -1, hm = -1;
        float lf = 0.f, ff = 0.f, hf = 0.f;
        cm_clip_marker_position(walk, g[0].time, &lm, &lf);
        cm_clip_marker_position(run, g[1].time, &fm, &ff);
        cm_clip_marker_position(hop, g[2].time, &hm, &hf);
        CHECK(marker_name(walk, lm) == marker_name(run, fm));
        CHECK(std::fabs(lf - ff) < 1e-4f);
        CHECK(marker_name(walk, lm) == marker_name(hop, hm));
        CHECK(std::fabs(lf - hf) < 1e-4f);
        // The follower with no markers runs on normalized time.
        CHECK(std::fabs(g[3].time / 2.f - g[0].time / 1.f) < 1e-5f);
        // The two-cycle follower only ever moves forward (never jumps back a cycle).
        float d = g[2].time - hop_prev;
        if (d < 0.f) d += 0.8f;
        CHECK(d < 0.4f);
        if (step > 0) hop_travel += d;  // step 0 aligns the follower to the leader's phase
        hop_prev = g[2].time;
    }
    // Marker spans: walk 0.5 s, hop 0.2 s, so the hop plays at 0.4x the walk's speed after the first step.
    CHECK(std::fabs(hop_travel - 399.f / 60.f * 0.4f) < 1e-3f);
    // An explicit leader; bad arguments.
    CHECK(cm_sync_advance(g, 4, 1, 0.01f) == 1);
    CHECK(cm_sync_advance(g, 4, 7, 0.01f) == CM_ERR_ARG);
    cm_sync_member none{nullptr, 0.f, 1.f, 1.f, 0};
    CHECK(cm_sync_advance(&none, 1, -1, 0.01f) == CM_ERR_ARG);
    cm_clip_destroy(plain);
    cm_clip_destroy(hop);
    cm_clip_destroy(run);
    cm_clip_destroy(walk);
}

}  // namespace

int main() {
    CHECK(cm_abi_version() == CM_ABI_VERSION);
    test_every_fixture_clip();
    test_morph_tracks();
    test_v11_to_v12();
    test_sections_round_trip();
    test_refusals();
    test_notify_firing();
    test_curves_and_markers();
    test_roles();
    test_sync_groups();
    if (failures != 0) {
        std::fprintf(stderr, "anim_clip: %d check(s) failed\n", failures);
        return 1;
    }
    std::printf("anim_clip: all checks passed\n");
    return 0;
}
