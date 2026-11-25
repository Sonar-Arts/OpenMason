// Cenda characterization (OpenMason #154) conformance: built-in and custom profiles, auto-map on three naming
// styles (Mixamo camelCase facing +Z, Stonebreak player sides named from the viewer, goblin pivot names), the
// validation fault kinds, lock, and reference-pose fitting on rigs whose bones are authored along different
// axes. Plain asserts.

#include "cenda/character.h"
#include "cenda/rig.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <map>
#include <string>
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

struct Bone {
    std::string id;
    int parent;
    float pos[3];
    float rot[3];
    float end[3];
};

cr_rig* make_rig(const std::vector<Bone>& bones) {
    std::vector<cr_joint_desc> d(bones.size());
    std::vector<const char*> ids;
    for (std::size_t i = 0; i < bones.size(); ++i) {
        cr_joint_desc& j = d[i];
        j.parent = bones[i].parent;
        j.kind = CR_JOINT_BONE;
        std::memcpy(j.pos, bones[i].pos, sizeof j.pos);
        std::memcpy(j.rot_deg, bones[i].rot, sizeof j.rot_deg);
        std::memcpy(j.endpoint, bones[i].end, sizeof j.endpoint);
        j.scale[0] = j.scale[1] = j.scale[2] = 1.f;
        ids.push_back(bones[i].id.c_str());
    }
    return cr_rig_create(d.data(), static_cast<int32_t>(d.size()), ids.data(), ids.data());
}

int index_of(const std::vector<Bone>& bones, const std::string& id) {
    for (std::size_t i = 0; i < bones.size(); ++i) {
        if (bones[i].id == id) return static_cast<int>(i);
    }
    return -1;
}

// Mixamo-like: "mixamorig:" namespace, camelCase, facing +Z (its left is +X), arms in an A-pose.
// axes_rotated: the arm bones carry a rest rotation and point down their local -Y instead of being authored
// straight along the arm (same joint positions, different bone axes).
std::vector<Bone> mixamo(bool axes_rotated) {
    const float s = std::sqrt(0.5f);
    std::vector<Bone> b = {
        {"mixamorig:Hips", -1, {0, 1.0f, 0}, {}, {0, 0.1f, 0}},
        {"mixamorig:Spine", 0, {}, {}, {0, 0.15f, 0}},
        {"mixamorig:Spine1", 1, {}, {}, {0, 0.15f, 0}},
        {"mixamorig:Spine2", 2, {}, {}, {0, 0.15f, 0}},
        {"mixamorig:Neck", 3, {}, {}, {0, 0.1f, 0}},
        {"mixamorig:Head", 4, {}, {}, {0, 0.2f, 0}},
    };
    for (int side = 0; side < 2; ++side) {
        const float sx = side == 0 ? 1.f : -1.f;
        const std::string n = side == 0 ? "Left" : "Right";
        auto add = [&](const std::string& name, const std::string& parent, Bone bone) {
            bone.id = "mixamorig:" + n + name;
            bone.parent = parent.empty() ? 3 : index_of(b, "mixamorig:" + n + parent);
            b.push_back(bone);
        };
        add("Shoulder", "", {"", 0, {0.05f * sx, -0.05f, 0}, {}, {0.1f * sx, 0, 0}});
        auto segment = [&](float len) {
            Bone bone{"", 0, {}, {}, {}};
            if (axes_rotated) {
                bone.end[1] = -len;
            } else {
                bone.end[0] = sx * s * len;
                bone.end[1] = -s * len;
            }
            return bone;
        };
        Bone arm = segment(0.3f);
        if (axes_rotated) arm.rot[2] = sx * 45.f;  // Rz(+-45) turns local -Y onto the A-pose direction
        add("Arm", "Shoulder", arm);
        add("ForeArm", "Arm", segment(0.25f));
        add("Hand", "ForeArm", segment(0.08f));
        add("HandIndex1", "Hand", segment(0.03f));
        add("HandIndex2", "HandIndex1", segment(0.03f));
        Bone thumb = segment(0.03f);
        thumb.pos[2] = 0.02f;
        add("HandThumb1", "Hand", thumb);
        b.push_back({"mixamorig:" + n + "UpLeg", 0, {0.1f * sx, -0.15f, 0}, {}, {0, -0.45f, 0}});
        add("Leg", "UpLeg", {"", 0, {}, {}, {0, -0.42f, 0}});
        add("Foot", "Leg", {"", 0, {}, {}, {0, -0.05f, 0.12f}});
        add("ToeBase", "Foot", {"", 0, {}, {}, {0, 0, 0.05f}});
    }
    return b;
}

// Stonebreak player-like: faces -Z, sides named from the viewer (arm_left is at +X = the character's right).
std::vector<Bone> player() {
    std::vector<Bone> b = {
        {"root", -1, {0, 0.96f, 0}, {}, {0, 0.075f, 0}},
        {"spine", 0, {}, {}, {0, 0.445f, 0}},
        {"head", 1, {0, 0.02f, 0}, {}, {0, 0.26f, 0}},
    };
    for (int side = 0; side < 2; ++side) {
        const float sx = side == 0 ? 1.f : -1.f;
        const std::string n = side == 0 ? "_left" : "_right";
        const int arm = static_cast<int>(b.size());
        b.push_back({"arm" + n, 1, {0.185f * sx, -0.05f, 0}, {}, {0.022f * sx, -0.27f, 0}});
        b.push_back({"forearm" + n, arm, {}, {}, {0.014f * sx, -0.244f, 0}});
        b.push_back({"hand" + n, arm + 1, {}, {}, {0.002f * sx, -0.14f, 0}});
        b.push_back({"finger_index" + n, arm + 2, {0, 0.069f, -0.016f}, {}, {0, -0.029f, 0}});
        b.push_back({"finger_index_tip" + n, arm + 3, {}, {}, {0, -0.042f, 0}});
        const int leg = static_cast<int>(b.size());
        b.push_back({"leg" + n, 0, {0.075f * sx, -0.075f, 0}, {}, {0.006f * sx, -0.435f, 0}});
        b.push_back({"calf" + n, leg, {}, {}, {0.002f * sx, -0.39f, 0}});
        b.push_back({"foot" + n, leg + 1, {}, {}, {0, -0.09f, -0.11f}});
    }
    return b;
}

// Goblin-like: joints named by their pivot (shoulder = upper arm, hip_l = thigh); no head, hand or foot bones.
std::vector<Bone> goblin() {
    return {
        {"root", -1, {}, {}, {0, 1, 0}},
        {"hips", 0, {}, {}, {0, 1, 0}},
        {"spine", 1, {0, 1, 0}, {}, {0, 1, 0}},
        {"neck", 2, {0, 0.8f, 0}, {}, {0, 1, 0}},
        {"shoulder_l", 2, {-1.2f, 0.5f, 0}, {}, {0, 1, 0}},
        {"elbow_l", 4, {0, -0.8f, 0}, {}, {0, 1, 0}},
        {"shoulder_r", 2, {1.2f, 0.5f, 0}, {}, {0, 1, 0}},
        {"elbow_r", 6, {0, -0.8f, 0}, {}, {0, 1, 0}},
        {"hip_l", 1, {-0.5f, 0, 0}, {}, {0, 1, 0}},
        {"knee_l", 8, {0, -1, 0}, {}, {0, 1, 0}},
        {"hip_r", 1, {0.5f, 0, 0}, {}, {0, 1, 0}},
        {"knee_r", 10, {0, -1, 0}, {}, {0, 1, 0}},
    };
}

std::map<std::string, std::string> roles_of(const cc_char* c) {
    std::map<std::string, std::string> out;
    for (int32_t i = 0; i < cc_char_role_count(c); ++i) {
        const char *role = nullptr, *joint = nullptr;
        cc_char_role_get(c, i, &role, &joint);
        out[role] = joint;
    }
    return out;
}

bool has_code(const cc_report* r, int32_t code, const char* subject = nullptr) {
    for (int32_t i = 0; i < cc_report_count(r); ++i) {
        int32_t sev = 0, c = 0;
        const char *subj = nullptr, *msg = nullptr;
        cc_report_get(r, i, &sev, &c, &subj, &msg);
        if (c == code && (subject == nullptr || std::strcmp(subj, subject) == 0)) return true;
    }
    return false;
}

int count_code(const cc_report* r, int32_t code) {
    int n = 0;
    for (int32_t i = 0; i < cc_report_count(r); ++i) {
        int32_t sev = 0, c = 0;
        cc_report_get(r, i, &sev, &c, nullptr, nullptr);
        n += c == code;
    }
    return n;
}

bool has_errors(const cc_report* r) { return cc_report_max_severity(r) >= CC_SEVERITY_ERROR; }

void dump(const cc_report* r) {
    for (int32_t i = 0; i < cc_report_count(r); ++i) {
        int32_t sev = 0, c = 0;
        const char *subj = nullptr, *msg = nullptr;
        cc_report_get(r, i, &sev, &c, &subj, &msg);
        std::fprintf(stderr, "    [%d/%d] %s: %s\n", sev, c, subj, msg);
    }
}

void test_profiles() {
    CHECK(cc_profile_builtin_count() == 3);
    CHECK(std::string(cc_profile_builtin_id(0)) == "humanoid");
    CHECK(cc_profile_builtin("nope") == nullptr);
    for (int32_t i = 0; i < cc_profile_builtin_count(); ++i) {
        cc_profile* p = cc_profile_builtin(cc_profile_builtin_id(i));
        CHECK(p != nullptr);
        // write -> read -> write is stable.
        cc_bytes* a = cc_profile_write(p);
        int32_t rc = 99;
        cc_profile* q = cc_profile_read(cc_bytes_data(a), cc_bytes_size(a), &rc);
        CHECK(q != nullptr && rc == CC_OK);
        cc_bytes* b = cc_profile_write(q);
        CHECK(cc_bytes_size(a) == cc_bytes_size(b) && std::memcmp(cc_bytes_data(a), cc_bytes_data(b), static_cast<std::size_t>(cc_bytes_size(a))) == 0);
        cc_bytes_destroy(a);
        cc_bytes_destroy(b);
        cc_profile_destroy(q);
        cc_profile_destroy(p);
    }
    cc_profile* h = cc_profile_builtin("humanoid");
    CHECK(cc_profile_role_count(h) == 63);
    int required = 0;
    for (int32_t i = 0; i < cc_profile_role_count(h); ++i) {
        int32_t req = 0;
        cc_profile_role_get(h, i, nullptr, nullptr, nullptr, nullptr, &req, nullptr, nullptr);
        required += req;
    }
    CHECK(required == 15);
    const int32_t ua = cc_profile_role_find(h, "upper_arm_l");
    const char *name = nullptr, *parent = nullptr, *mirror = nullptr;
    int32_t side = -1, req = 0, has = 0;
    float dir[3]{};
    CHECK(cc_profile_role_get(h, ua, &name, &parent, &mirror, &side, &req, dir, &has) == CC_OK);
    CHECK(std::string(parent) == "shoulder_l" && std::string(mirror) == "upper_arm_r" && side == CC_SIDE_LEFT && req == 1);
    CHECK(has == 1 && dir[0] == -1.f && dir[1] == 0.f);  // the character's left is -X
    cc_profile_destroy(h);

    auto bad = [](const char* json) {
        int32_t rc = 0;
        cc_profile* p = cc_profile_read(reinterpret_cast<const uint8_t*>(json), static_cast<int64_t>(std::strlen(json)), &rc);
        cc_profile_destroy(p);
        return p == nullptr ? rc : CC_OK;
    };
    CHECK(bad(R"({"format":"omprofile","version":"2.0","id":"x","roles":[]})") == CC_ERR_VERSION);
    CHECK(bad(R"({"format":"omprofile","version":"1.0","id":"x","roles":[]})") == CC_ERR_FORMAT);
    CHECK(bad(R"({"format":"omprofile","version":"1.0","id":"x","roles":[{"name":"a"},{"name":"a"}]})") == CC_ERR_FORMAT);
    CHECK(bad(R"({"format":"omprofile","version":"1.0","id":"x","roles":[{"name":"b","parent":"a"},{"name":"a"}]})") == CC_ERR_FORMAT);
    CHECK(bad(R"({"format":"omprofile","version":"1.0","id":"x","roles":[{"name":"a","side":"left","mirror":"b"},{"name":"b","side":"left","mirror":"a"}]})") == CC_ERR_FORMAT);
    CHECK(bad(R"({"format":"omprofile","version":"1.0","id":"x","roles":[{"name":"a","toward":["z"]}]})") == CC_ERR_FORMAT);
    CHECK(bad(R"({"format":"omprofile","version":"1.0","id":"x","roles":[{"name":"a","direction":[0,0,0]}]})") == CC_ERR_FORMAT);
    CHECK(bad(R"({"format":"omprofile","version":"1.0","id":"x","roles":[{"name":"a","match":[["arm",1.5]]}]})") == CC_ERR_FORMAT);
}

void test_mixamo_auto_map() {
    const auto bones = mixamo(false);
    cr_rig* rig = make_rig(bones);
    cc_profile* h = cc_profile_builtin("humanoid");
    cc_char* c = cc_char_create();
    cc_automap* m = cc_char_auto_map(c, rig, h);
    CHECK(m != nullptr);
    if (m == nullptr) return;
    CHECK(std::string(cc_char_get_string(c, CC_FIELD_FACING)) == "+z");  // from the feet
    CHECK(std::string(cc_char_get_string(c, CC_FIELD_PROFILE)) == "humanoid");
    const auto r = roles_of(c);
    CHECK(cc_automap_count(m) == static_cast<int32_t>(bones.size()));  // every joint has a role
    CHECK(r.at("hips") == "mixamorig:Hips");
    CHECK(r.at("spine") == "mixamorig:Spine");
    CHECK(r.at("chest") == "mixamorig:Spine1");
    CHECK(r.at("upper_chest") == "mixamorig:Spine2");
    CHECK(r.at("head") == "mixamorig:Head");
    CHECK(r.at("shoulder_l") == "mixamorig:LeftShoulder");
    CHECK(r.at("upper_arm_l") == "mixamorig:LeftArm");
    CHECK(r.at("lower_arm_r") == "mixamorig:RightForeArm");
    CHECK(r.at("hand_l") == "mixamorig:LeftHand");
    CHECK(r.at("index_proximal_l") == "mixamorig:LeftHandIndex1");
    CHECK(r.at("index_intermediate_l") == "mixamorig:LeftHandIndex2");
    CHECK(r.at("thumb_proximal_r") == "mixamorig:RightHandThumb1");
    CHECK(r.at("upper_leg_l") == "mixamorig:LeftUpLeg");
    CHECK(r.at("lower_leg_l") == "mixamorig:LeftLeg");
    CHECK(r.at("foot_r") == "mixamorig:RightFoot");
    CHECK(r.at("toes_l") == "mixamorig:LeftToeBase");
    CHECK(!has_code(cc_automap_notes(m), CC_CHECK_SIDE_FROM_GEOMETRY));
    for (int32_t i = 0; i < cc_automap_count(m); ++i) {
        float conf = 0;
        cc_automap_get(m, i, nullptr, nullptr, &conf);
        CHECK(conf > 0.f && conf <= 1.f);
    }
    cc_automap_destroy(m);

    cc_report* v = cc_char_validate(c, rig, h);
    CHECK(!has_errors(v));
    if (has_errors(v)) dump(v);
    cc_report_destroy(v);
    cc_char_destroy(c);
    cc_profile_destroy(h);
    cr_rig_destroy(rig);
}

void test_player_sides_from_geometry() {
    cr_rig* rig = make_rig(player());
    cc_profile* h = cc_profile_builtin("humanoid");
    cc_char* c = cc_char_create();
    cc_automap* m = cc_char_auto_map(c, rig, h);
    CHECK(m != nullptr);
    CHECK(std::string(cc_char_get_string(c, CC_FIELD_FACING)) == "-z");
    const auto r = roles_of(c);
    CHECK(r.at("hips") == "root");
    CHECK(r.at("spine") == "spine");
    CHECK(r.at("head") == "head");
    // arm_left lies at +X; facing -Z that is the character's RIGHT.
    CHECK(r.at("upper_arm_r") == "arm_left");
    CHECK(r.at("lower_arm_r") == "forearm_left");
    CHECK(r.at("hand_l") == "hand_right");
    CHECK(r.at("index_proximal_r") == "finger_index_left");
    CHECK(r.at("index_intermediate_r") == "finger_index_tip_left");
    CHECK(r.at("upper_leg_l") == "leg_right");
    CHECK(r.at("lower_leg_r") == "calf_left");
    CHECK(r.at("foot_l") == "foot_right");
    CHECK(has_code(cc_automap_notes(m), CC_CHECK_SIDE_FROM_GEOMETRY));
    CHECK(!has_code(cc_automap_notes(m), CC_CHECK_REQUIRED_MISSING));
    cc_automap_destroy(m);
    cc_report* v = cc_char_validate(c, rig, h);
    CHECK(!has_errors(v));
    if (has_errors(v)) dump(v);
    cc_report_destroy(v);

    // Mapping by the names instead puts every limb on the wrong side.
    cc_char_role_set(c, "upper_arm_r", "arm_right");
    cc_char_role_set(c, "upper_arm_l", "arm_left");
    v = cc_char_validate(c, rig, h);
    CHECK(has_code(v, CC_CHECK_SIDE_MISMATCH, "upper_arm_l"));
    CHECK(has_code(v, CC_CHECK_HIERARCHY_ORDER, "lower_arm_l"));  // hand_right's forearm is not under arm_left
    cc_report_destroy(v);
    cc_char_destroy(c);
    cc_profile_destroy(h);
    cr_rig_destroy(rig);
}

void test_goblin_promotions_and_gaps() {
    cr_rig* rig = make_rig(goblin());
    cc_profile* h = cc_profile_builtin("humanoid");
    cc_char* c = cc_char_create();
    cc_automap* m = cc_char_auto_map(c, rig, h);
    CHECK(m != nullptr);
    CHECK(std::string(cc_char_get_string(c, CC_FIELD_FACING)).empty());  // no feet: default, not stored
    const auto r = roles_of(c);
    CHECK(r.at("hips") == "hips");
    CHECK(r.at("spine") == "spine");
    CHECK(r.at("head") == "neck");           // promoted from neck
    CHECK(!r.contains("neck"));
    CHECK(r.at("upper_arm_l") == "shoulder_l");  // promoted from shoulder
    CHECK(!r.contains("shoulder_l"));
    CHECK(r.at("lower_arm_l") == "elbow_l");
    CHECK(r.at("upper_leg_r") == "hip_r");
    CHECK(r.at("lower_leg_r") == "knee_r");
    CHECK(!r.contains("hips") || r.at("hips") != "root");
    const cc_report* notes = cc_automap_notes(m);
    CHECK(count_code(notes, CC_CHECK_PROMOTED) == 3);         // head + two upper arms
    CHECK(count_code(notes, CC_CHECK_REQUIRED_MISSING) == 4); // hands and feet: the expected gaps
    CHECK(has_code(notes, CC_CHECK_REQUIRED_MISSING, "hand_l") && has_code(notes, CC_CHECK_REQUIRED_MISSING, "foot_r"));
    cc_automap_destroy(m);

    // The gaps keep it from locking.
    CHECK(cc_char_lock(c, rig, h) == CC_ERR_INVALID);
    CHECK(std::string(cc_last_error()).find("required role") != std::string::npos);
    CHECK(cc_char_locked(c) == 0);
    cc_char_destroy(c);
    cc_profile_destroy(h);
    cr_rig_destroy(rig);
}

void test_validation_faults_and_lock() {
    const auto bones = mixamo(false);
    cr_rig* rig = make_rig(bones);
    cc_profile* h = cc_profile_builtin("humanoid");
    cc_char* c = cc_char_create();
    cc_char_set_skeleton(c, rig);
    cc_automap_destroy(cc_char_auto_map(c, rig, h));

    // Lock, then everything the lock covers refuses.
    CHECK(cc_char_lock(c, rig, h) == CC_OK && cc_char_locked(c) == 1);
    CHECK(cc_char_role_set(c, "hips", "mixamorig:Spine") == CC_ERR_LOCKED);
    const float rot[3] = {1, 2, 3};
    CHECK(cc_char_pose_set(c, "mixamorig:Spine", rot) == CC_ERR_LOCKED);
    CHECK(cc_char_set_string(c, CC_FIELD_FACING, "+x") == CC_ERR_LOCKED);
    CHECK(cc_char_set_skeleton(c, rig) == CC_ERR_LOCKED);
    CHECK(cc_char_auto_map(c, rig, h) == nullptr);
    CHECK(cc_char_fit_reference_pose(c, rig, h) == CC_ERR_LOCKED);
    // Things outside the mapping stay editable; the lock survives a round trip.
    CHECK(cc_char_set_string(c, CC_FIELD_NAME, "Locked") == CC_OK);
    cc_bytes* bytes = cc_char_write(c);
    cc_char* back = cc_char_read(cc_bytes_data(bytes), cc_bytes_size(bytes), nullptr);
    CHECK(back != nullptr && cc_char_locked(back) == 1);
    CHECK(std::string(cc_char_get_string(back, CC_FIELD_FACING)) == "+z");
    cc_char_destroy(back);
    cc_bytes_destroy(bytes);
    CHECK(cc_char_unlock(c) == CC_OK && cc_char_locked(c) == 0);

    // Each fault kind.
    cc_char_role_set(c, "chest", "mixamorig:Spine");                // duplicate joint (spine has it too)
    cc_char_role_set(c, "lower_arm_l", "mixamorig:RightForeArm");   // not under upper_arm_l; on the wrong side
    cc_char_role_set(c, "tail", "mixamorig:Head");                  // not a humanoid role
    cc_char_role_set(c, "foot_l", nullptr);                         // required
    cc_char_set_string(c, CC_FIELD_PROFILE, "quadruped");
    cc_report* v = cc_char_validate(c, rig, h);
    CHECK(has_code(v, CC_CHECK_DUPLICATE_JOINT, "mixamorig:Spine"));
    CHECK(has_code(v, CC_CHECK_HIERARCHY_ORDER, "lower_arm_l"));
    CHECK(has_code(v, CC_CHECK_SIDE_MISMATCH, "lower_arm_l"));
    CHECK(has_code(v, CC_CHECK_UNKNOWN_ROLE, "tail"));
    CHECK(has_code(v, CC_CHECK_REQUIRED_MISSING, "foot_l"));
    CHECK(has_code(v, CC_CHECK_PROFILE_MISMATCH));
    CHECK(cc_char_lock(c, rig, h) == CC_ERR_INVALID);
    cc_report_destroy(v);
    cc_char_destroy(c);
    cr_rig_destroy(rig);

    // An asymmetric pair: the right arm hangs lower than the left.
    auto lopsided = mixamo(false);
    lopsided[static_cast<std::size_t>(index_of(lopsided, "mixamorig:RightShoulder"))].pos[1] = -0.3f;
    rig = make_rig(lopsided);
    c = cc_char_create();
    cc_automap_destroy(cc_char_auto_map(c, rig, h));
    v = cc_char_validate(c, rig, h);
    CHECK(has_code(v, CC_CHECK_MIRROR_ASYMMETRIC, "upper_arm_l"));
    CHECK(!has_errors(v));  // a warning: asymmetric characters exist
    cc_report_destroy(v);
    cc_char_destroy(c);
    cr_rig_destroy(rig);
    cc_profile_destroy(h);
}

// Fits the reference pose, poses the rig in it, and returns the world head position of every joint.
std::vector<float> fitted_pose(cr_rig* rig, cc_char* c, cc_profile* h, const std::vector<Bone>& bones, int32_t* fitted) {
    *fitted = cc_char_fit_reference_pose(c, rig, h);
    std::vector<float> locals(bones.size() * 9);
    for (std::size_t i = 0; i < bones.size(); ++i) {
        std::memcpy(&locals[i * 9], bones[i].pos, 12);
        std::memcpy(&locals[i * 9 + 3], bones[i].rot, 12);
        locals[i * 9 + 6] = locals[i * 9 + 7] = locals[i * 9 + 8] = 1.f;
    }
    for (int32_t i = 0; i < cc_char_pose_count(c); ++i) {
        const char* joint = nullptr;
        float rot[3];
        cc_char_pose_get(c, i, &joint, rot);
        const int j = index_of(bones, joint);
        std::memcpy(&locals[static_cast<std::size_t>(j) * 9 + 3], rot, 12);
    }
    std::vector<float> world(bones.size() * 16);
    cr_rig_pose_locals(rig, locals.data(), nullptr, 0, nullptr, nullptr, world.data(), nullptr);
    std::vector<float> heads;
    for (std::size_t i = 0; i < bones.size(); ++i) {
        heads.push_back(world[i * 16 + 12]);
        heads.push_back(world[i * 16 + 13]);
        heads.push_back(world[i * 16 + 14]);
    }
    return heads;
}

double angle_deg(const float* heads, int from, int to, const double target[3]) {
    double d[3];
    double len = 0;
    for (int i = 0; i < 3; ++i) {
        d[i] = heads[to * 3 + i] - heads[from * 3 + i];
        len += d[i] * d[i];
    }
    len = std::sqrt(len);
    double dot = 0;
    for (int i = 0; i < 3; ++i) dot += d[i] / len * target[i];
    return std::acos(std::fmin(1.0, std::fmax(-1.0, dot))) * 180.0 / 3.14159265358979323846;
}

void test_fit_reference_pose() {
    cc_profile* h = cc_profile_builtin("humanoid");
    std::vector<float> poses[2];
    for (int variant = 0; variant < 2; ++variant) {
        const auto bones = mixamo(variant == 1);
        cr_rig* rig = make_rig(bones);
        cc_char* c = cc_char_create();
        cc_automap_destroy(cc_char_auto_map(c, rig, h));
        int32_t fitted = 0;
        poses[variant] = fitted_pose(rig, c, h, bones, &fitted);
        CHECK(fitted > 10);
        const float* p = poses[variant].data();
        // Facing +Z: the character's left is +X. Arms out to the sides, legs down, spine up: a T-pose.
        const double left[3] = {1, 0, 0}, right[3] = {-1, 0, 0}, up[3] = {0, 1, 0}, down[3] = {0, -1, 0};
        auto at = [&](const char* id) { return index_of(bones, id); };
        CHECK(angle_deg(p, at("mixamorig:LeftArm"), at("mixamorig:LeftForeArm"), left) < 0.1);
        CHECK(angle_deg(p, at("mixamorig:LeftForeArm"), at("mixamorig:LeftHand"), left) < 0.1);
        CHECK(angle_deg(p, at("mixamorig:RightArm"), at("mixamorig:RightForeArm"), right) < 0.1);
        CHECK(angle_deg(p, at("mixamorig:RightHand"), at("mixamorig:RightHandIndex1"), right) < 0.1);
        CHECK(angle_deg(p, at("mixamorig:LeftShoulder"), at("mixamorig:LeftArm"), left) < 0.1);
        CHECK(angle_deg(p, at("mixamorig:LeftUpLeg"), at("mixamorig:LeftLeg"), down) < 0.1);
        CHECK(angle_deg(p, at("mixamorig:Spine"), at("mixamorig:Spine1"), up) < 0.1);
        CHECK(angle_deg(p, at("mixamorig:Hips"), at("mixamorig:Spine"), up) < 0.1);
        // The fit is idempotent: fitting again turns nothing further.
        poses[variant] = fitted_pose(rig, c, h, bones, &fitted);
        CHECK(angle_deg(poses[variant].data(), at("mixamorig:LeftArm"), at("mixamorig:LeftForeArm"), left) < 0.1);
        cc_char_destroy(c);
        cr_rig_destroy(rig);
    }
    // Different bone axes, same character: the same T-pose joint positions.
    double worst = 0;
    for (std::size_t i = 0; i < poses[0].size(); ++i) worst = std::fmax(worst, std::fabs(poses[0][i] - poses[1][i]));
    CHECK(worst < 1e-4);
    cc_profile_destroy(h);
}

void test_profile_from_rig() {
    cr_rig* rig = make_rig(goblin());
    cc_char* source = cc_char_create();
    cc_profile* custom = cc_profile_from_rig(rig, source, "goblin");
    CHECK(custom != nullptr);
    if (custom == nullptr) return;
    CHECK(std::string(cc_profile_get_string(custom, CC_PROFILE_ID)) == "goblin");
    CHECK(cc_profile_role_count(custom) == 12);
    const int32_t sl = cc_profile_role_find(custom, "shoulder_l");
    const char* mirror = nullptr;
    int32_t side = -1;
    cc_profile_role_get(custom, sl, nullptr, nullptr, &mirror, &side, nullptr, nullptr, nullptr);
    CHECK(std::string(mirror) == "shoulder_r" && side == CC_SIDE_LEFT);
    // Mapping the same rig with its own profile: every joint, full confidence, valid.
    cc_char* c = cc_char_create();
    cc_automap* m = cc_char_auto_map(c, rig, custom);
    CHECK(cc_automap_count(m) == 12);
    for (int32_t i = 0; i < cc_automap_count(m); ++i) {
        float conf = 0;
        cc_automap_get(m, i, nullptr, nullptr, &conf);
        CHECK(conf == 1.f);
    }
    cc_automap_destroy(m);
    cc_report* v = cc_char_validate(c, rig, custom);
    CHECK(!has_errors(v));
    if (has_errors(v)) dump(v);
    cc_report_destroy(v);
    cc_char_destroy(c);
    cc_char_destroy(source);
    cc_profile_destroy(custom);
    cr_rig_destroy(rig);
}

}  // namespace

int main() {
    test_profiles();
    test_mixamo_auto_map();
    test_player_sides_from_geometry();
    test_goblin_promotions_and_gaps();
    test_validation_faults_and_lock();
    test_fit_reference_pose();
    test_profile_from_rig();
    if (failures != 0) {
        std::fprintf(stderr, "characterize: %d check(s) failed\n", failures);
        return 1;
    }
    std::printf("characterize: all checks passed\n");
    return 0;
}
