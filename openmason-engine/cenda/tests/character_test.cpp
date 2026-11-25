// Cenda character (cc_*) + .omchar conformance: round trips (byte-identical), unknown sections and entries,
// embedded models, version/format refusals, editing semantics, checks against a rig, and metrics measured
// in the reference pose. Plain asserts.

#include "cenda/character.h"
#include "cenda/rig.h"
#include "omformat/omchar.hpp"
#include "omformat/zip_archive.hpp"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
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

bool near(float a, float b, float eps = 1e-5f) { return std::fabs(a - b) <= eps; }

namespace om = cenda::omformat;

std::vector<uint8_t> write(const cc_char* c) {
    cc_bytes* b = cc_char_write(c);
    if (b == nullptr) {
        std::fprintf(stderr, "write failed: %s\n", cc_last_error());
        return {};
    }
    std::vector<uint8_t> out(cc_bytes_data(b), cc_bytes_data(b) + cc_bytes_size(b));
    cc_bytes_destroy(b);
    return out;
}

cc_char* read(const std::vector<uint8_t>& bytes, int32_t* result = nullptr) {
    return cc_char_read(bytes.data(), static_cast<int64_t>(bytes.size()), result);
}

std::vector<uint8_t> archive(const std::string& manifest, std::vector<om::ZipEntry> extra = {}) {
    std::vector<om::ZipEntry> entries;
    om::ZipEntry m;
    m.name = "manifest.json";
    m.data.resize(manifest.size());
    std::memcpy(m.data.data(), manifest.data(), manifest.size());
    entries.push_back(std::move(m));
    for (auto& e : extra) entries.push_back(std::move(e));
    std::vector<std::byte> zip = om::write_zip(entries);
    std::vector<uint8_t> out(zip.size());
    std::memcpy(out.data(), zip.data(), zip.size());
    return out;
}

om::ZipEntry entry(const std::string& name, std::string_view text) {
    om::ZipEntry e;
    e.name = name;
    e.data.resize(text.size());
    std::memcpy(e.data.data(), text.data(), text.size());
    return e;
}

std::string manifest_of(const std::vector<uint8_t>& bytes) {
    auto entries = om::read_zip(std::span<const std::byte>(reinterpret_cast<const std::byte*>(bytes.data()), bytes.size()));
    for (const auto& e : entries) {
        if (e.name == "manifest.json") return {reinterpret_cast<const char*>(e.data.data()), e.data.size()};
    }
    return {};
}

bool has_code(const cc_report* r, int32_t code, const char* subject = nullptr) {
    for (int32_t i = 0; i < cc_report_count(r); ++i) {
        int32_t sev = 0, c = 0;
        const char* subj = nullptr;
        const char* msg = nullptr;
        cc_report_get(r, i, &sev, &c, &subj, &msg);
        if (c == code && (subject == nullptr || std::strcmp(subj, subject) == 0)) return true;
    }
    return false;
}

// A small biped: hips (y 1.0, tail 1.2) -> spine (tail +0.4) -> head (tail +0.25) -> eye_l / eye_r.
cr_joint_desc bone(int parent, float px, float py, float pz, float tail_y) {
    cr_joint_desc d{};
    d.parent = parent;
    d.kind = CR_JOINT_BONE;
    d.pos[0] = px;
    d.pos[1] = py;
    d.pos[2] = pz;
    d.scale[0] = d.scale[1] = d.scale[2] = 1.f;
    d.endpoint[1] = tail_y;
    return d;
}

cr_rig* biped(bool variant = false) {
    std::vector<cr_joint_desc> joints = {
        bone(-1, 0.f, 1.0f, 0.f, 0.2f),       // 0 hips
        bone(0, 0.f, 0.f, 0.f, 0.4f),         // 1 spine
        bone(1, 0.f, 0.f, 0.f, 0.25f),        // 2 head
        bone(2, 0.05f, -0.15f, 0.1f, 0.02f),  // 3 eye_l
        bone(2, -0.05f, -0.15f, 0.1f, 0.02f), // 4 eye_r
    };
    std::vector<const char*> ids = {"hips", "spine", "head", "eye_l", "eye_r"};
    std::vector<const char*> names = {"Hips", "Spine", "Head", "EyeL", "EyeR"};
    if (variant) {
        // eye_r reparented to spine, head renamed, a new "jaw" joint, eye_l removed (replaced by jaw slot).
        joints[4].parent = 1;
        names[2] = "Skull";
        ids[3] = "jaw";
        names[3] = "Jaw";
    }
    return cr_rig_create(joints.data(), static_cast<int32_t>(joints.size()), ids.data(), names.data());
}

void test_round_trip() {
    cc_char* c = cc_char_create();
    CHECK(cc_char_set_string(c, CC_FIELD_NAME, "Player") == CC_OK);
    CHECK(cc_char_set_string(c, CC_FIELD_OBJECT_ID, "test:player") == CC_OK);
    CHECK(cc_char_set_string(c, CC_FIELD_PROFILE, "humanoid") == CC_OK);
    CHECK(cc_char_set_string(c, CC_FIELD_ANIM_GRAPH, "graphs/humanoid.omanimgraph") == CC_OK);
    CHECK(cc_char_set_string(c, CC_FIELD_APPEARANCE, "{ \"tints\": {\"skin\": [0.8, 0.6, 0.5]} }") == CC_OK);
    CHECK(cc_char_set_string(c, CC_FIELD_APPEARANCE, "[1,2]") == CC_ERR_ARG);  // not an object
    CHECK(cc_char_set_string(c, CC_FIELD_JOINT_HASH, "x") == CC_ERR_ARG);      // read-only

    cr_rig* rig = biped();
    CHECK(cc_char_set_skeleton(c, rig) == CC_OK);
    CHECK(cc_char_joint_count(c) == 5);
    CHECK(std::string(cc_char_get_string(c, CC_FIELD_JOINT_HASH)).rfind("fnv1a64:", 0) == 0);

    CHECK(cc_char_role_set(c, "hips", "hips") == CC_OK);
    CHECK(cc_char_role_set(c, "head", "head") == CC_OK);
    CHECK(cc_char_role_set(c, "spine", "spine") == CC_OK);
    const float rot[3] = {0.f, 0.f, 12.5f};
    CHECK(cc_char_pose_set(c, "spine", rot) == CC_OK);
    const float mn[3] = {-30.f, -45.f, -10.f};
    const float mx[3] = {30.f, 45.f, 10.f};
    CHECK(cc_char_limit_set(c, "head", mn, mx) == CC_OK);
    CHECK(cc_char_goal_set(c, "look", "head") == CC_OK);
    CHECK(cc_char_chain_set(c, "spine", "hips", "head", "look") == CC_OK);
    const float pos[3] = {0.1f, 1.5f, 0.2f};
    CHECK(cc_char_socket_set(c, "grip_r", "spine", pos, nullptr, nullptr) == CC_OK);
    cc_metrics m{1.8f, 1.62f, 0.6f, 0.96f, CC_METRIC_EYE_HEIGHT, 0};
    CHECK(cc_char_metrics_set(c, &m) == CC_OK);
    const uint8_t model[] = {'P', 'K', 3, 4, 1, 2, 3};
    CHECK(cc_char_model_set(c, model, sizeof model, nullptr) == CC_OK);

    const auto first = write(c);
    CHECK(!first.empty());
    int32_t rc = 99;
    cc_char* back = read(first, &rc);
    CHECK(back != nullptr && rc == CC_OK);
    if (back != nullptr) {
        const auto second = write(back);
        CHECK(first == second);  // byte-identical

        CHECK(std::string(cc_char_get_string(back, CC_FIELD_NAME)) == "Player");
        CHECK(std::string(cc_char_get_string(back, CC_FIELD_PROFILE)) == "humanoid");
        CHECK(std::string(cc_char_get_string(back, CC_FIELD_APPEARANCE)) == "{\"tints\":{\"skin\":[0.8,0.6,0.5]}}");
        CHECK(cc_char_joint_count(back) == 5);
        const char *id = nullptr, *name = nullptr, *parent = nullptr;
        int32_t kind = -1;
        CHECK(cc_char_joint_get(back, 3, &id, &name, &parent, &kind) == CC_OK);
        CHECK(std::string(id) == "eye_l" && std::string(name) == "EyeL" && std::string(parent) == "head" &&
              kind == CR_JOINT_BONE);
        CHECK(cc_char_joint_get(back, 0, nullptr, nullptr, &parent, nullptr) == CC_OK && parent[0] == '\0');
        CHECK(cc_char_role_count(back) == 3);
        CHECK(std::string(cc_char_role_joint(back, "head")) == "head");
        CHECK(cc_char_role_joint(back, "tail") == nullptr);
        float r[3]{};
        CHECK(cc_char_pose_get(back, 0, &id, r) == CC_OK && near(r[2], 12.5f));
        float lo[3]{}, hi[3]{};
        CHECK(cc_char_limit_get(back, 0, &id, lo, hi) == CC_OK && near(lo[1], -45.f) && near(hi[2], 10.f));
        const char *start = nullptr, *end = nullptr, *goal = nullptr;
        CHECK(cc_char_chain_get(back, 0, &name, &start, &end, &goal) == CC_OK);
        CHECK(std::string(start) == "hips" && std::string(end) == "head" && std::string(goal) == "look");
        float p[3]{}, rr[3]{}, s[3]{};
        CHECK(cc_char_socket_get(back, 0, &name, &id, p, rr, s) == CC_OK);
        CHECK(near(p[1], 1.5f) && near(rr[0], 0.f) && near(s[2], 1.f));
        cc_metrics mb{};
        CHECK(cc_char_metrics_get(back, &mb) == CC_OK);
        CHECK(near(mb.eye_height, 1.62f) && mb.overridden == CC_METRIC_EYE_HEIGHT);
        const uint8_t* data = nullptr;
        int64_t size = 0;
        CHECK(cc_char_model_get(back, &data, &size) == CC_OK && size == sizeof model &&
              std::memcmp(data, model, sizeof model) == 0);

        // Floats are written in single precision ("0.96", not "0.9599999785423279").
        const std::string manifest = manifest_of(first);
        CHECK(manifest.find("\"hipHeight\": 0.96") != std::string::npos);
        CHECK(manifest.find("\"overridden\": [") != std::string::npos);
        cc_char_destroy(back);
    }
    cr_rig_destroy(rig);
    cc_char_destroy(c);
}

void test_unknown_sections_and_entries() {
    const std::string manifest = R"({
      "format": "omchar", "version": "1.4", "name": "Future",
      "futureSection": {"weights": [1, 2.5, "x"], "nested": {"ok": true}},
      "roles": {"hips": "j0"}
    })";
    auto bytes = archive(manifest, {entry("notes/readme.txt", "keep me")});
    int32_t rc = 99;
    cc_char* c = read(bytes, &rc);
    CHECK(c != nullptr && rc == CC_OK);
    if (c == nullptr) return;
    const auto out = write(c);
    const std::string written = manifest_of(out);
    CHECK(written.find("\"futureSection\"") != std::string::npos);
    CHECK(written.find("\"nested\"") != std::string::npos);
    CHECK(written.find("\"version\": \"1.0\"") != std::string::npos);  // the writer's own version
    auto entries = om::read_zip(std::span<const std::byte>(reinterpret_cast<const std::byte*>(out.data()), out.size()));
    bool notes = false;
    for (const auto& e : entries) {
        if (e.name == "notes/readme.txt") notes = std::string(reinterpret_cast<const char*>(e.data.data()), e.data.size()) == "keep me";
    }
    CHECK(notes);
    // Second round trip is byte-identical too.
    cc_char* again = read(out);
    CHECK(again != nullptr && write(again) == out);
    cc_char_destroy(again);
    cc_char_destroy(c);
}

void test_refusals() {
    int32_t rc = 0;
    const std::vector<uint8_t> garbage = {1, 2, 3, 4, 5};
    CHECK(read(garbage, &rc) == nullptr && rc == CC_ERR_FORMAT);
    CHECK(cc_char_read(nullptr, 0, &rc) == nullptr && rc == CC_ERR_ARG);

    CHECK(read(archive(R"({"format":"omchar","version":"2.0"})"), &rc) == nullptr && rc == CC_ERR_VERSION);
    CHECK(std::string(cc_last_error()).find("newer") != std::string::npos);
    CHECK(read(archive(R"({"format":"omo","version":"1.0"})"), &rc) == nullptr && rc == CC_ERR_FORMAT);
    CHECK(read(archive(R"({"format":"omchar"})"), &rc) == nullptr && rc == CC_ERR_FORMAT);
    CHECK(read(archive(R"({"format":"omchar","version":"one"})"), &rc) == nullptr && rc == CC_ERR_FORMAT);
    CHECK(read(archive(R"({"format":"omchar","version":"1.0","name":"a","name":"b"})"), &rc) == nullptr &&
          rc == CC_ERR_FORMAT);
    CHECK(read(archive(R"({"format":"omchar","version":"1.0","roles":{"hips":"a","hips":"b"}})"), &rc) == nullptr &&
          rc == CC_ERR_FORMAT);
    CHECK(read(archive(R"({"format":"omchar","version":"1.0","skeleton":{"model":"model.omo"}})"), &rc) == nullptr &&
          rc == CC_ERR_FORMAT);
    CHECK(read(archive(R"({"format":"omchar","version":"1.0","sockets":[{"name":"a","joint":"j","pos":[1,2]}]})"),
               &rc) == nullptr && rc == CC_ERR_FORMAT);
    CHECK(read(archive(R"({"format":"omchar","version":"1.0","metrics":{"overridden":["weight"]}})"), &rc) ==
              nullptr && rc == CC_ERR_FORMAT);
    auto no_manifest = om::write_zip({entry("other.json", "{}")});
    std::vector<uint8_t> nm(no_manifest.size());
    std::memcpy(nm.data(), no_manifest.data(), nm.size());
    CHECK(read(nm, &rc) == nullptr && rc == CC_ERR_FORMAT);
}

void test_editing() {
    cc_char* c = cc_char_create();
    CHECK(cc_char_role_set(c, "a", "j1") == CC_OK);
    CHECK(cc_char_role_set(c, "b", "j2") == CC_OK);
    CHECK(cc_char_role_set(c, "a", "j3") == CC_OK);  // replaced in place
    const char *role = nullptr, *joint = nullptr;
    CHECK(cc_char_role_get(c, 0, &role, &joint) == CC_OK && std::string(role) == "a" && std::string(joint) == "j3");
    CHECK(cc_char_role_set(c, "a", nullptr) == CC_OK);  // removed
    CHECK(cc_char_role_count(c) == 1);
    CHECK(cc_char_role_get(c, 5, &role, &joint) == CC_ERR_RANGE);
    CHECK(cc_char_role_set(c, "", "j") == CC_ERR_ARG);
    CHECK(cc_char_role_set(c, "x", "") == CC_ERR_ARG);
    const float bad[3] = {NAN, 0.f, 0.f};
    CHECK(cc_char_pose_set(c, "j", bad) == CC_ERR_ARG);
    cc_metrics m{INFINITY, 0, 0, 0, 0, 0};
    CHECK(cc_char_metrics_set(c, &m) == CC_ERR_ARG);
    const float one[3] = {1.f, 1.f, 1.f};
    CHECK(cc_char_limit_set(c, "j", one, nullptr) == CC_OK && cc_char_limit_count(c) == 0);  // null max = remove

    // An embedded model replaces an unknown entry of the same name; removing the model clears both.
    const uint8_t model[] = {9, 9, 9};
    CHECK(cc_char_model_set(c, model, 3, "skeleton.omo") == CC_OK);
    CHECK(cc_char_model_set(c, model, 3, "manifest.json") == CC_ERR_ARG);
    auto bytes = write(c);
    CHECK(manifest_of(bytes).find("\"model\": \"skeleton.omo\"") != std::string::npos);
    CHECK(cc_char_model_set(c, nullptr, 0, nullptr) == CC_OK);
    CHECK(manifest_of(write(c)).find("skeleton.omo") == std::string::npos);
    cc_char_destroy(c);
}

void test_check() {
    cr_rig* rig = biped();
    cc_char* c = cc_char_create();

    cc_report* r = cc_char_check(c, nullptr);
    CHECK(has_code(r, CC_CHECK_NO_SKELETON));
    cc_report_destroy(r);

    cc_char_set_skeleton(c, rig);
    cc_char_role_set(c, "hips", "hips");
    cc_char_role_set(c, "head", "head");
    cc_char_goal_set(c, "look", "head");
    cc_char_chain_set(c, "spine", "hips", "head", "look");
    r = cc_char_check(c, rig);
    CHECK(cc_report_count(r) == 0 && cc_report_max_severity(r) == -1);
    cc_report_destroy(r);

    // Definition-only faults.
    cc_char_role_set(c, "tail", "no_such_joint");
    cc_char_chain_set(c, "backwards", "head", "hips", nullptr);
    cc_char_chain_set(c, "goalless", "hips", "spine", "missing_goal");
    const float mn[3] = {10.f, 0.f, 0.f};
    const float mx[3] = {-10.f, 0.f, 0.f};
    cc_char_limit_set(c, "head", mn, mx);
    cc_metrics m{-1.f, 0, 0, 0, 0, 0};
    cc_char_metrics_set(c, &m);
    r = cc_char_check(c, nullptr);
    CHECK(has_code(r, CC_CHECK_UNKNOWN_JOINT, "no_such_joint"));
    CHECK(has_code(r, CC_CHECK_CHAIN_ORDER, "backwards"));
    CHECK(!has_code(r, CC_CHECK_CHAIN_ORDER, "spine"));
    CHECK(has_code(r, CC_CHECK_CHAIN_GOAL, "goalless"));
    CHECK(has_code(r, CC_CHECK_LIMIT_INVERTED, "head"));
    CHECK(has_code(r, CC_CHECK_METRIC_NEGATIVE, "height"));
    CHECK(cc_report_max_severity(r) == CC_SEVERITY_ERROR);
    cc_report_destroy(r);
    cc_char_destroy(c);

    // A changed model: eye_r reparented, head renamed, eye_l gone, jaw new.
    c = cc_char_create();
    cc_char_set_skeleton(c, rig);
    cc_char_role_set(c, "eye_l", "eye_l");
    cr_rig* changed = biped(true);
    r = cc_char_check(c, changed);
    CHECK(has_code(r, CC_CHECK_JOINT_MISSING, "eye_l"));
    CHECK(has_code(r, CC_CHECK_JOINT_ADDED, "jaw"));
    CHECK(has_code(r, CC_CHECK_JOINT_REPARENTED, "eye_r"));
    CHECK(has_code(r, CC_CHECK_JOINT_RENAMED, "head"));
    CHECK(has_code(r, CC_CHECK_UNKNOWN_JOINT, "eye_l"));  // the role now points nowhere
    cc_report_destroy(r);

    // A hand-edited joint list no longer matches its stored hash.
    auto bytes = write(c);
    std::string manifest = manifest_of(bytes);
    const auto at = manifest.find("\"parent\": \"head\"");
    CHECK(at != std::string::npos);
    manifest.replace(at, std::strlen("\"parent\": \"head\""), "\"parent\": \"spine\"");
    cc_char* edited = read(archive(manifest));
    CHECK(edited != nullptr);
    if (edited != nullptr) {
        r = cc_char_check(edited, nullptr);
        CHECK(has_code(r, CC_CHECK_HASH_MISMATCH));
        cc_report_destroy(r);
        cc_char_destroy(edited);
    }

    cr_rig_destroy(changed);
    cc_char_destroy(c);
    cr_rig_destroy(rig);
}

void test_metrics() {
    cr_rig* rig = biped();
    cc_char* c = cc_char_create();
    cc_char_role_set(c, "hips", "hips");
    cc_char_role_set(c, "head", "head");
    cc_char_role_set(c, "eye_l", "eye_l");
    cc_char_role_set(c, "eye_r", "eye_r");
    const float lo[3] = {-0.3f, 0.f, -0.15f};
    const float hi[3] = {0.3f, 1.85f, 0.15f};

    cc_metrics m{};
    CHECK(cc_char_compute_metrics(c, rig, lo, hi, &m) == CC_OK);
    CHECK(near(m.height, 1.85f) && near(m.width, 0.6f));
    CHECK(near(m.hip_height, 1.0f));
    CHECK(near(m.eye_height, 1.7f));  // mean of the eye joints: head tail 1.85 - 0.15

    // Reference pose: spine turned 90 degrees about Z. The head and eyes swing out to the side; the eyes'
    // mean height drops to the spine's head height (1.2).
    const float turn[3] = {0.f, 0.f, 90.f};
    cc_char_pose_set(c, "spine", turn);
    CHECK(cc_char_compute_metrics(c, rig, lo, hi, &m) == CC_OK);
    CHECK(near(m.eye_height, 1.2f, 1e-4f));
    CHECK(near(m.hip_height, 1.0f));
    cc_char_pose_set(c, "spine", nullptr);

    // Without eye roles: the head joint.
    cc_char_role_set(c, "eye_l", nullptr);
    cc_char_role_set(c, "eye_r", nullptr);
    CHECK(cc_char_compute_metrics(c, rig, lo, hi, &m) == CC_OK && near(m.eye_height, 1.6f));

    // An "eyes" socket wins (rest model space, moved by the joint's skin matrix: identity at rest).
    const float eyes[3] = {0.f, 1.72f, 0.1f};
    cc_char_socket_set(c, "eyes", "head", eyes, nullptr, nullptr);
    CHECK(cc_char_compute_metrics(c, rig, lo, hi, &m) == CC_OK && near(m.eye_height, 1.72f));

    // Overridden metrics keep the stored values; a feet-below-zero model measures from its own floor.
    cc_metrics stored{2.5f, 0, 0, 0, CC_METRIC_HEIGHT, 0};
    cc_char_metrics_set(c, &stored);
    CHECK(cc_char_compute_metrics(c, rig, lo, hi, &m) == CC_OK);
    CHECK(near(m.height, 2.5f) && m.overridden == CC_METRIC_HEIGHT && near(m.width, 0.6f));
    const float lo2[3] = {-0.3f, -0.5f, -0.15f};
    CHECK(cc_char_compute_metrics(c, rig, lo2, hi, &m) == CC_OK && near(m.hip_height, 1.5f));

    // No hips role: 0.
    cc_char_role_set(c, "hips", nullptr);
    CHECK(cc_char_compute_metrics(c, rig, lo, hi, &m) == CC_OK && m.hip_height == 0.f);
    CHECK(cc_char_compute_metrics(c, nullptr, lo, hi, &m) == CC_ERR_ARG);
    cc_char_destroy(c);
    cr_rig_destroy(rig);
}


// A weighted bone mask authored by role (OpenMason #156): the spine role's branch at full weight.
void test_mask_role() {
    cr_rig* rig = biped();
    cc_char* c = cc_char_create();
    CHECK(cc_char_role_set(c, "spine", "spine") == CC_OK);
    CHECK(cc_char_role_set(c, "ghost", "nowhere") == CC_OK);
    cr_mask* m = cr_mask_create(rig, 0.f);
    CHECK(cc_char_mask_role(c, rig, m, "spine", 1.f, CR_MASK_BRANCH, 2) == CC_OK);
    float w[5];
    CHECK(cr_mask_get(m, w) == CR_OK);
    CHECK(w[0] == 0.f && w[1] == 0.5f && w[2] == 1.f && w[3] == 1.f && w[4] == 1.f);
    CHECK(cc_char_mask_role(c, rig, m, "head", 1.f, 0, 0) == CC_ERR_RANGE);   // unmapped
    CHECK(cc_char_mask_role(c, rig, m, "ghost", 1.f, 0, 0) == CC_ERR_RANGE);  // not in the rig
    CHECK(cc_char_mask_role(c, rig, nullptr, "spine", 1.f, 0, 0) == CC_ERR_ARG);
    cr_mask_destroy(m);
    cc_char_destroy(c);
    cr_rig_destroy(rig);
}

}  // namespace

int main() {
    CHECK(cc_abi_version() == CC_ABI_VERSION);
    test_round_trip();
    test_unknown_sections_and_entries();
    test_refusals();
    test_editing();
    test_check();
    test_mask_role();
    test_metrics();
    if (failures != 0) {
        std::fprintf(stderr, "character: %d check(s) failed\n", failures);
        return 1;
    }
    std::printf("character: all checks passed\n");
    return 0;
}
