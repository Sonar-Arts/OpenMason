// Cenda character editing (OpenMason #163) conformance: the edit session (groups, merges, cancel, undo / redo,
// dirty, history limit, shared embedded model), role states, .omprofile 1.1 (diagram, named poses), the A-pose
// fit, mirroring (roles, limits, sockets) on two naming styles, standard sockets, the viewport overlay and
// picking. Plain asserts.

#include "cenda/character.h"
#include "cenda/character_edit.h"
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

// Mixamo-like (as characterize_test): facing +Z (its left is +X), A-pose arms; axes_rotated authors the arm bones
// along their local -Y with a rest rotation instead.
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
        if (axes_rotated) arm.rot[2] = sx * 45.f;
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

// Stonebreak player-like: faces -Z, sides named from the viewer (arm_left is the character's right).
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

std::map<std::string, std::string> roles_of(const cc_char* c) {
    std::map<std::string, std::string> out;
    for (int32_t i = 0; i < cc_char_role_count(c); ++i) {
        const char *role = nullptr, *joint = nullptr;
        cc_char_role_get(c, i, &role, &joint);
        out[role] = joint;
    }
    return out;
}

std::vector<uint8_t> bytes_of(const cc_char* c) {
    cc_bytes* b = cc_char_write(c);
    std::vector<uint8_t> out(cc_bytes_data(b), cc_bytes_data(b) + cc_bytes_size(b));
    cc_bytes_destroy(b);
    return out;
}

std::string name_of(const cc_char* c) { return cc_char_get_string(c, CC_FIELD_NAME); }

int32_t role_index(const cc_profile* p, const char* role) { return cc_profile_role_find(p, role); }

// ---------------------------------------------------------------------------------------------------------

void test_session_groups_undo_redo() {
    cc_char* c = cc_char_create();
    cc_charedit* e = cc_charedit_create(c, 0);
    CHECK(e != nullptr);
    const auto base = bytes_of(c);
    CHECK(cc_charedit_dirty(e) == 0);
    CHECK(cc_charedit_can_undo(e) == 0 && cc_charedit_can_redo(e) == 0);
    const int64_t r0 = cc_charedit_revision(e);

    // One group = one step, however many setters it runs; nested groups fold into the outermost.
    CHECK(cc_charedit_begin(e, "rename", nullptr) == CC_OK);
    cc_char_set_string(c, CC_FIELD_NAME, "A");
    CHECK(cc_charedit_begin(e, "inner", nullptr) == CC_OK);
    cc_char_role_set(c, "hips", "j0");
    CHECK(cc_charedit_commit(e) == 0);  // inner
    CHECK(cc_charedit_depth(e) == 1);
    CHECK(cc_charedit_undo(e) == CC_ERR_ARG);  // a group is open
    CHECK(cc_charedit_commit(e) == 1);
    CHECK(cc_charedit_step_count(e) == 1);
    CHECK(std::string(cc_charedit_label(e, 0)) == "rename");
    CHECK(cc_charedit_dirty(e) == 1);
    CHECK(cc_charedit_revision(e) > r0);
    const auto after_a = bytes_of(c);

    // An unchanged group pushes nothing.
    cc_charedit_begin(e, "noop", nullptr);
    cc_char_set_string(c, CC_FIELD_NAME, "A");
    CHECK(cc_charedit_commit(e) == 0);
    CHECK(cc_charedit_step_count(e) == 1);
    CHECK(cc_charedit_commit(e) == CC_ERR_ARG);  // no open group

    // A merge key folds a drag into one step until sealed.
    for (int i = 0; i < 5; ++i) {
        cc_charedit_begin(e, "drag", "metric.height");
        cc_metrics m{};
        m.height = 1.0f + 0.1f * static_cast<float>(i);
        cc_char_metrics_set(c, &m);
        CHECK(cc_charedit_commit(e) == (i == 0 ? 1 : 2));
    }
    CHECK(cc_charedit_step_count(e) == 2);
    cc_charedit_seal(e);
    cc_charedit_begin(e, "drag", "metric.height");
    cc_metrics m{};
    m.height = 3.f;
    cc_char_metrics_set(c, &m);
    CHECK(cc_charedit_commit(e) == 1);  // sealed: a new step
    CHECK(cc_charedit_step_count(e) == 3);
    const auto after_drag = bytes_of(c);

    // Cancel puts back the group's start, all-or-nothing.
    cc_charedit_begin(e, "batch", nullptr);
    cc_char_set_string(c, CC_FIELD_NAME, "B");
    cc_char_role_set(c, "spine", "j1");
    CHECK(cc_charedit_cancel(e) == 1);
    CHECK(cc_charedit_depth(e) == 0);
    CHECK(bytes_of(c) == after_drag);
    CHECK(cc_charedit_step_count(e) == 3);

    // Undo / redo walk the recorded states exactly.
    CHECK(cc_charedit_undo(e) == CC_OK);
    CHECK(cc_charedit_undo(e) == CC_OK);
    CHECK(bytes_of(c) == after_a);
    CHECK(cc_charedit_can_redo(e) == 1);
    CHECK(std::string(cc_charedit_label(e, 1)) == "drag");
    CHECK(cc_charedit_undo(e) == CC_OK);
    CHECK(bytes_of(c) == base);
    CHECK(cc_charedit_dirty(e) == 0);
    CHECK(cc_charedit_undo(e) == CC_ERR_RANGE);
    CHECK(cc_charedit_redo(e) == CC_OK);
    CHECK(bytes_of(c) == after_a);
    CHECK(cc_charedit_redo(e) == CC_OK);
    CHECK(cc_charedit_redo(e) == CC_OK);
    CHECK(bytes_of(c) == after_drag);
    CHECK(cc_charedit_redo(e) == CC_ERR_RANGE);

    // Saved state; a new edit after undo drops the redo tail.
    CHECK(cc_charedit_mark_saved(e) == CC_OK);
    CHECK(cc_charedit_dirty(e) == 0);
    cc_charedit_undo(e);
    CHECK(cc_charedit_dirty(e) == 1);
    cc_charedit_begin(e, "branch", nullptr);
    cc_char_set_string(c, CC_FIELD_NAME, "C");
    cc_charedit_commit(e);
    CHECK(cc_charedit_can_redo(e) == 0);
    CHECK(cc_charedit_dirty(e) == 1);
    CHECK(name_of(c) == "C");

    // An edit outside a group: undo records it, then undoes it.
    cc_char_set_string(c, CC_FIELD_NAME, "stray");
    CHECK(cc_charedit_undo(e) == CC_OK);
    CHECK(name_of(c) == "C");
    CHECK(cc_charedit_redo(e) == CC_OK);
    CHECK(name_of(c) == "stray");

    // Reset forgets the history.
    CHECK(cc_charedit_reset(e) == CC_OK);
    CHECK(cc_charedit_step_count(e) == 0 && cc_charedit_dirty(e) == 0 && cc_charedit_can_undo(e) == 0);
    cc_charedit_destroy(e);
    cc_char_destroy(c);
}

void test_session_limit_and_shared_model() {
    cc_char* c = cc_char_create();
    // A 2 MB embedded model: states share it while it stays the same.
    std::vector<uint8_t> model(2u << 20);
    for (std::size_t i = 0; i < model.size(); ++i) model[i] = static_cast<uint8_t>(i * 31u);
    cc_char_model_set(c, model.data(), static_cast<int64_t>(model.size()), nullptr);
    cc_charedit* e = cc_charedit_create(c, 10);
    for (int i = 0; i < 25; ++i) {
        cc_charedit_begin(e, "n", nullptr);
        cc_char_set_string(c, CC_FIELD_NAME, std::to_string(i).c_str());
        cc_charedit_commit(e);
    }
    CHECK(cc_charedit_step_count(e) == 10);  // oldest dropped
    CHECK(cc_charedit_dirty(e) == 1);        // the saved base is gone
    int undone = 0;
    while (cc_charedit_undo(e) == CC_OK) ++undone;
    CHECK(undone == 10);
    CHECK(name_of(c) == "14");

    // Changing the model is undoable, and undo restores the bytes.
    cc_charedit_begin(e, "model", nullptr);
    std::vector<uint8_t> other(model.begin(), model.begin() + 100);
    cc_char_model_set(c, other.data(), 100, nullptr);
    cc_char_entry_set(c, "masks/body.png", other.data(), 10);
    cc_charedit_commit(e);
    cc_charedit_undo(e);
    const uint8_t* data = nullptr;
    int64_t size = 0;
    cc_char_model_get(c, &data, &size);
    CHECK(size == static_cast<int64_t>(model.size()) && std::memcmp(data, model.data(), model.size()) == 0);
    CHECK(cc_char_entry_find(c, "masks/body.png") < 0);
    cc_charedit_redo(e);
    cc_char_model_get(c, &data, &size);
    CHECK(size == 100);
    CHECK(cc_char_entry_find(c, "masks/body.png") >= 0);
    cc_charedit_destroy(e);
    cc_char_destroy(c);
}

void test_role_states() {
    cr_rig* rig = make_rig(mixamo(false));
    cc_profile* h = cc_profile_builtin("humanoid");
    cc_char* c = cc_char_create();
    cc_automap_destroy(cc_char_auto_map(c, rig, h));
    const int32_t n = cc_profile_role_count(h);
    std::vector<uint32_t> st(static_cast<std::size_t>(n));
    CHECK(cc_char_role_states(c, rig, h, st.data(), n) == n);
    CHECK(cc_char_role_states(c, rig, h, st.data(), n - 1) == CC_ERR_ARG);
    const auto at = [&](const char* r) { return st[static_cast<std::size_t>(role_index(h, r))]; };
    CHECK(at("hips") == (CC_ROLE_MAPPED | CC_ROLE_REQUIRED));
    CHECK(at("jaw") == 0u);  // optional, unmapped
    for (int32_t i = 0; i < n; ++i) CHECK((st[static_cast<std::size_t>(i)] & CC_ROLE_ERROR) == 0u);

    cc_char_role_set(c, "hand_l", nullptr);                       // required, missing
    cc_char_role_set(c, "jaw", "mixamorig:Head");                 // duplicate of head
    cc_char_role_set(c, "upper_arm_r", "mixamorig:LeftArm");      // side + duplicate
    cc_char_role_set(c, "eye_l", "no-such-joint");
    cc_char_role_states(c, rig, h, st.data(), n);
    CHECK(at("hand_l") == (CC_ROLE_REQUIRED | CC_ROLE_ERROR));
    CHECK((at("jaw") & (CC_ROLE_DUPLICATE | CC_ROLE_ERROR)) == (CC_ROLE_DUPLICATE | CC_ROLE_ERROR));
    CHECK((at("head") & CC_ROLE_DUPLICATE) != 0u);
    CHECK((at("upper_arm_r") & CC_ROLE_SIDE_MISMATCH) != 0u);
    CHECK((at("eye_l") & CC_ROLE_JOINT_MISSING) != 0u);
    // Without a rig: no geometry checks, still the required / mapped bits.
    cc_char_role_states(c, nullptr, h, st.data(), n);
    CHECK((at("upper_arm_r") & CC_ROLE_SIDE_MISMATCH) == 0u);
    CHECK(at("hand_l") == (CC_ROLE_REQUIRED | CC_ROLE_ERROR));
    cc_char_destroy(c);
    cc_profile_destroy(h);
    cr_rig_destroy(rig);
}

void test_profile_diagram_and_poses() {
    for (const char* id : {"humanoid", "quadruped", "bird"}) {
        cc_profile* p = cc_profile_builtin(id);
        const int32_t n = cc_profile_role_count(p);
        std::vector<float> xy(static_cast<std::size_t>(n) * 2);
        CHECK(cc_profile_diagram(p, xy.data(), n * 2) == n);
        for (int32_t i = 0; i < n; ++i) {
            CHECK(cc_profile_role_has_diagram(p, i) == 1);
            CHECK(xy[static_cast<std::size_t>(i) * 2] >= 0.f && xy[static_cast<std::size_t>(i) * 2] <= 1.f);
        }
        // Distinct slots.
        for (int32_t i = 0; i < n; ++i)
            for (int32_t j = i + 1; j < n; ++j) {
                const float dx = xy[static_cast<std::size_t>(i) * 2] - xy[static_cast<std::size_t>(j) * 2];
                const float dy = xy[static_cast<std::size_t>(i) * 2 + 1] - xy[static_cast<std::size_t>(j) * 2 + 1];
                CHECK(dx * dx + dy * dy > 1e-5f);
            }
        // 1.1 round trip: write(read(write(p))) == write(p), diagram + poses kept.
        cc_bytes* w = cc_profile_write(p);
        int32_t rc = 0;
        cc_profile* q = cc_profile_read(cc_bytes_data(w), cc_bytes_size(w), &rc);
        CHECK(q != nullptr);
        cc_bytes* w2 = cc_profile_write(q);
        CHECK(cc_bytes_size(w) == cc_bytes_size(w2) && std::memcmp(cc_bytes_data(w), cc_bytes_data(w2), static_cast<std::size_t>(cc_bytes_size(w))) == 0);
        CHECK(std::strstr(reinterpret_cast<const char*>(cc_bytes_data(w)), "\"version\": \"1.1\"") != nullptr);
        CHECK(cc_profile_pose_count(q) == cc_profile_pose_count(p));
        cc_bytes_destroy(w);
        cc_bytes_destroy(w2);
        cc_profile_destroy(q);
        cc_profile_destroy(p);
    }
    cc_profile* h = cc_profile_builtin("humanoid");
    CHECK(cc_profile_pose_count(h) == 1 && std::string(cc_profile_pose_name(h, 0)) == "A");
    CHECK(cc_profile_pose_name(h, 1) == nullptr);
    // Front view: the character's left on the viewer's right.
    std::vector<float> xy(static_cast<std::size_t>(cc_profile_role_count(h)) * 2);
    cc_profile_diagram(h, xy.data(), static_cast<int32_t>(xy.size()));
    CHECK(xy[static_cast<std::size_t>(role_index(h, "hand_l")) * 2] > 0.5f);
    CHECK(xy[static_cast<std::size_t>(role_index(h, "head")) * 2 + 1] < xy[static_cast<std::size_t>(role_index(h, "hips")) * 2 + 1]);
    cc_profile_destroy(h);

    // A 1.0 profile still reads; a computed layout (no diagram) fits [0.05, 0.95].
    const char* old = R"({"format":"omprofile","version":"1.0","id":"x","roles":[
        {"name":"root","direction":[0,1,0]},{"name":"arm_l","parent":"root","side":"left","mirror":"arm_r","direction":[-1,0,0]},
        {"name":"arm_r","parent":"root","side":"right","mirror":"arm_l","direction":[1,0,0]},{"name":"tip","parent":"arm_l"}]})";
    int32_t rc = 0;
    cc_profile* x = cc_profile_read(reinterpret_cast<const uint8_t*>(old), static_cast<int64_t>(std::strlen(old)), &rc);
    CHECK(x != nullptr);
    float g[8];
    CHECK(cc_profile_diagram(x, g, 8) == 4);
    CHECK(cc_profile_role_has_diagram(x, 0) == 0);
    for (float v : g) CHECK(v >= 0.049f && v <= 0.951f);
    CHECK(g[2] > g[0] && g[4] < g[0]);  // arm_l right of the root, arm_r left
    cc_profile_destroy(x);

    // Pose validation: unknown role, duplicate pose.
    const char* bad1 = R"({"format":"omprofile","version":"1.1","id":"x","roles":[{"name":"a"}],"poses":[{"name":"P","directions":{"b":[1,0,0]}}]})";
    const char* bad2 = R"({"format":"omprofile","version":"1.1","id":"x","roles":[{"name":"a"}],"poses":[{"name":"P","directions":{}},{"name":"P","directions":{}}]})";
    const char* bad3 = R"({"format":"omprofile","version":"1.1","id":"x","roles":[{"name":"a","diagram":[0.5,1.5]}]})";
    for (const char* bad : {bad1, bad2, bad3}) {
        rc = 0;
        CHECK(cc_profile_read(reinterpret_cast<const uint8_t*>(bad), static_cast<int64_t>(std::strlen(bad)), &rc) == nullptr);
        CHECK(rc == CC_ERR_FORMAT);
    }
}

// The angle (degrees) between joint a -> b in the posed reference pose and a target direction.
double posed_angle(cr_rig* rig, const cc_char* c, const std::vector<Bone>& bones, const char* a, const char* b, const double t[3]) {
    const std::size_t n = bones.size();
    std::vector<float> locals(n * 9), world(n * 16);
    cc_char_reference_locals(c, rig, locals.data());
    cr_rig_pose_locals(rig, locals.data(), nullptr, 0, nullptr, nullptr, world.data(), nullptr);
    const auto ia = static_cast<std::size_t>(index_of(bones, a));
    const auto ib = static_cast<std::size_t>(index_of(bones, b));
    double d[3], len = 0, dot = 0;
    for (std::size_t i = 0; i < 3; ++i) {
        d[i] = world[ib * 16 + 12 + i] - world[ia * 16 + 12 + i];
        len += d[i] * d[i];
    }
    len = std::sqrt(len);
    for (std::size_t i = 0; i < 3; ++i) dot += d[i] / len * t[i];
    return std::acos(std::fmin(1.0, std::fmax(-1.0, dot))) * 180.0 / 3.14159265358979323846;
}

void test_named_pose_fit() {
    cc_profile* h = cc_profile_builtin("humanoid");
    for (int variant = 0; variant < 2; ++variant) {
        const auto bones = mixamo(variant == 1);
        cr_rig* rig = make_rig(bones);
        cc_char* c = cc_char_create();
        cc_automap_destroy(cc_char_auto_map(c, rig, h));
        // Facing +Z: the character's left is +X.
        const double s = std::sqrt(0.5);
        const double a_left[3] = {s, -s, 0}, a_right[3] = {-s, -s, 0}, t_left[3] = {1, 0, 0}, down[3] = {0, -1, 0};
        CHECK(cc_char_fit_reference_pose_named(c, rig, h, "A") > 10);
        CHECK(posed_angle(rig, c, bones, "mixamorig:LeftArm", "mixamorig:LeftForeArm", a_left) < 0.1);
        CHECK(posed_angle(rig, c, bones, "mixamorig:LeftForeArm", "mixamorig:LeftHand", a_left) < 0.1);
        CHECK(posed_angle(rig, c, bones, "mixamorig:RightArm", "mixamorig:RightForeArm", a_right) < 0.1);
        CHECK(posed_angle(rig, c, bones, "mixamorig:LeftUpLeg", "mixamorig:LeftLeg", down) < 0.1);
        CHECK(posed_angle(rig, c, bones, "mixamorig:LeftShoulder", "mixamorig:LeftArm", t_left) < 0.1);  // not in A
        // T (or none) = the plain fit.
        const auto a_pose = bytes_of(c);
        CHECK(cc_char_fit_reference_pose_named(c, rig, h, "T") > 10);
        CHECK(posed_angle(rig, c, bones, "mixamorig:LeftArm", "mixamorig:LeftForeArm", t_left) < 0.1);
        const auto t_pose = bytes_of(c);
        cc_char_fit_reference_pose(c, rig, h);
        CHECK(bytes_of(c) == t_pose);
        CHECK(bytes_of(c) != a_pose);
        CHECK(cc_char_fit_reference_pose_named(c, rig, h, "Z") == CC_ERR_RANGE);
        cc_char_destroy(c);
        cr_rig_destroy(rig);
    }
    cc_profile_destroy(h);
}

void test_mirror(bool player_rig) {
    const auto bones = player_rig ? player() : mixamo(true);
    cr_rig* rig = make_rig(bones);
    cc_profile* h = cc_profile_builtin("humanoid");
    cc_char* c = cc_char_create();
    cc_automap_destroy(cc_char_auto_map(c, rig, h));
    const auto full = roles_of(c);

    // Roles: drop every right role, mirror from the left: the auto-map's mapping comes back.
    for (const auto& [role, joint] : full) {
        if (role.size() > 2 && role.compare(role.size() - 2, 2, "_r") == 0) cc_char_role_set(c, role.c_str(), nullptr);
    }
    int32_t count = 0;
    cc_report* r = cc_char_mirror(c, rig, h, CC_SIDE_LEFT, CC_MIRROR_ROLES, &count);
    CHECK(r != nullptr);
    CHECK(count > 5);
    CHECK(roles_of(c) == full);
    cc_report_destroy(r);
    r = cc_char_mirror(c, rig, h, CC_SIDE_LEFT, CC_MIRROR_ROLES, &count);
    CHECK(count == 0);  // already mirrored
    cc_report_destroy(r);

    // Limits: per-axis, signs from the reflected axes.
    const std::string arm_l = full.at("upper_arm_l");
    const std::string arm_r = full.at("upper_arm_r");
    const float mn[3] = {-5, -10, -15}, mx[3] = {10, 20, 30};
    cc_char_limit_set(c, arm_l.c_str(), mn, mx);
    r = cc_char_mirror(c, rig, h, CC_SIDE_LEFT, CC_MIRROR_LIMITS, &count);
    CHECK(count == 1);
    cc_report_destroy(r);
    bool found = false;
    for (int32_t i = 0; i < cc_char_limit_count(c); ++i) {
        const char* j = nullptr;
        float a[3], b[3];
        cc_char_limit_get(c, i, &j, a, b);
        if (arm_r != j) continue;
        found = true;
        // A reflection through the X = const midplane keeps a turn about X and flips turns about Y and Z: frames of
        // the two arms are mirror images (variant 1: Rz(+45) vs Rz(-45) rest rotations).
        CHECK(std::fabs(a[0] - mn[0]) < 1e-3f && std::fabs(b[0] - mx[0]) < 1e-3f);
        CHECK(std::fabs(a[1] + mx[1]) < 1e-3f && std::fabs(b[1] + mn[1]) < 1e-3f);
        CHECK(std::fabs(a[2] + mx[2]) < 1e-3f && std::fabs(b[2] + mn[2]) < 1e-3f);
    }
    CHECK(found);

    // Sockets: a placed grip_l mirrors onto grip_r = the placed standard grip_r.
    CHECK(cc_char_place_standard_socket(c, rig, "grip_r") == CC_OK);
    float placed[3]{};
    for (int32_t i = 0; i < cc_char_socket_count(c); ++i) {
        const char* name = nullptr;
        float pos[3], rot[3], scl[3];
        cc_char_socket_get(c, i, &name, nullptr, pos, rot, scl);
        if (std::string(name) == "grip_r") std::memcpy(placed, pos, sizeof placed);
    }
    cc_char_socket_set(c, "grip_r", nullptr, nullptr, nullptr, nullptr);
    CHECK(cc_char_place_standard_socket(c, rig, "grip_l") == CC_OK);
    const float tilt[3] = {10, 20, 30};
    for (int32_t i = 0; i < cc_char_socket_count(c); ++i) {
        const char *name = nullptr, *joint = nullptr;
        float pos[3], rot[3], scl[3];
        cc_char_socket_get(c, i, &name, &joint, pos, rot, scl);
        if (std::string(name) == "grip_l") {
            const std::string jj = joint;
            cc_char_socket_set(c, "grip_l", jj.c_str(), pos, tilt, nullptr);
        }
    }
    r = cc_char_mirror(c, rig, h, CC_SIDE_LEFT, CC_MIRROR_SOCKETS, &count);
    CHECK(count == 1);
    cc_report_destroy(r);
    found = false;
    for (int32_t i = 0; i < cc_char_socket_count(c); ++i) {
        const char *name = nullptr, *joint = nullptr;
        float pos[3], rot[3], scl[3];
        cc_char_socket_get(c, i, &name, &joint, pos, rot, scl);
        if (std::string(name) != "grip_r") continue;
        found = true;
        CHECK(std::string(joint) == full.at("hand_r"));
        for (int k = 0; k < 3; ++k) CHECK(std::fabs(pos[k] - placed[k]) < 1e-4f);
        // Reflected through X: rotation about X kept, Y and Z flipped (Euler XYZ of M R M with M = diag(-1, 1, 1)).
        CHECK(std::fabs(rot[0] - 10.f) < 1e-3f && std::fabs(rot[1] + 20.f) < 1e-3f && std::fabs(rot[2] + 30.f) < 1e-3f);
    }
    CHECK(found);

    // Mirroring roles of a locked mapping is refused; sockets / limits are not frozen by the lock.
    CHECK(cc_char_lock(c, rig, h) == CC_OK);
    CHECK(cc_char_mirror(c, rig, h, CC_SIDE_LEFT, CC_MIRROR_ROLES, &count) == nullptr);
    r = cc_char_mirror(c, rig, h, CC_SIDE_RIGHT, CC_MIRROR_SOCKETS, &count);
    CHECK(r != nullptr && count == 1);  // grip_r -> grip_l
    cc_report_destroy(r);
    CHECK(cc_char_mirror(c, rig, h, 0, CC_MIRROR_SOCKETS, &count) == nullptr);
    cc_char_destroy(c);
    cc_profile_destroy(h);
    cr_rig_destroy(rig);
}

void test_standard_sockets() {
    CHECK(cc_char_standard_socket_count() == 5);
    CHECK(std::string(cc_char_standard_socket_name(0)) == "grip_r");
    CHECK(std::string(cc_char_standard_socket_role(4)) == "upper_chest");
    CHECK(cc_char_standard_socket_name(5) == nullptr);
    const auto bones = player();
    cr_rig* rig = make_rig(bones);
    cc_profile* h = cc_profile_builtin("humanoid");
    cc_char* c = cc_char_create();
    CHECK(cc_char_place_standard_socket(c, rig, "grip_r") == CC_ERR_RANGE);  // nothing mapped yet
    cc_automap_destroy(cc_char_auto_map(c, rig, h));
    for (int32_t i = 0; i < 5; ++i) CHECK(cc_char_place_standard_socket(c, rig, cc_char_standard_socket_name(i)) == CC_OK);
    CHECK(cc_char_place_standard_socket(c, rig, "tail") == CC_ERR_RANGE);
    CHECK(cc_char_socket_count(c) == 5);
    const auto roles = roles_of(c);
    std::vector<float> world(bones.size() * 16), child(bones.size() * 16);
    cr_rig_rest_world(rig, world.data(), child.data());
    for (int32_t i = 0; i < cc_char_socket_count(c); ++i) {
        const char *name = nullptr, *joint = nullptr;
        float pos[3], rot[3], scl[3];
        cc_char_socket_get(c, i, &name, &joint, pos, rot, scl);
        const std::string n = name;
        const auto j = static_cast<std::size_t>(index_of(bones, joint));
        const float* head = &world[j * 16 + 12];
        const float* tail = &child[j * 16 + 12];
        if (n == "grip_r") {
            CHECK(std::string(joint) == roles.at("hand_r") && std::string(joint) == "hand_left");  // viewer-named
            for (int k = 0; k < 3; ++k) CHECK(std::fabs(pos[k] - 0.5f * (head[k] + tail[k])) < 1e-5f);
        } else if (n == "eyes") {
            CHECK(std::string(joint) == "head");
            CHECK(pos[2] < head[2] - 0.01f);  // ahead of the head: the player faces -Z
            CHECK(pos[1] > head[1] && pos[1] < tail[1]);
        } else if (n == "back") {
            CHECK(std::string(joint) == "spine");  // no upper_chest / chest: the spine
            CHECK(pos[2] > head[2] + 0.05f);     // behind
        } else if (n == "head") {
            for (int k = 0; k < 3; ++k) CHECK(std::fabs(pos[k] - head[k]) < 1e-6f);
        }
    }
    cc_char_destroy(c);
    cc_profile_destroy(h);
    cr_rig_destroy(rig);
}

void test_overlay_and_pick() {
    const auto bones = mixamo(false);
    cr_rig* rig = make_rig(bones);
    cc_profile* h = cc_profile_builtin("humanoid");
    cc_char* c = cc_char_create();
    cc_automap_destroy(cc_char_auto_map(c, rig, h));
    cc_char_fit_reference_pose(c, rig, h);
    for (int32_t i = 0; i < 5; ++i) cc_char_place_standard_socket(c, rig, cc_char_standard_socket_name(i));
    cc_metrics m{};
    m.height = 1.8f;
    m.eye_height = 1.6f;
    m.hip_height = 1.0f;
    m.width = 1.6f;
    cc_char_metrics_set(c, &m);
    const float lo[3] = {-0.8f, 0.f, -0.1f}, hi[3] = {0.8f, 1.8f, 0.1f};
    cc_overlay* o = cc_char_overlay(c, rig, h, nullptr, lo, hi);
    CHECK(o != nullptr);
    if (o == nullptr) return;
    const int32_t n = cc_overlay_joint_count(o);
    CHECK(n == static_cast<int32_t>(bones.size()));
    const cc_overlay_joint* j = cc_overlay_joints(o);
    const auto hips = static_cast<std::size_t>(index_of(bones, "mixamorig:Hips"));
    CHECK(j[hips].role == role_index(h, "hips"));
    CHECK(j[hips].state == (CC_ROLE_MAPPED | CC_ROLE_REQUIRED));
    CHECK(std::fabs(j[hips].point[1] - 1.0f) < 1e-6f && std::fabs(j[hips].tail[1] - 1.1f) < 1e-5f);
    // Reference pose: T arms (facing +Z: left along +X).
    const auto la = static_cast<std::size_t>(index_of(bones, "mixamorig:LeftArm"));
    const auto lf = static_cast<std::size_t>(index_of(bones, "mixamorig:LeftForeArm"));
    CHECK(std::fabs(j[lf].point[1] - j[la].point[1]) < 1e-4f && j[lf].point[0] > j[la].point[0]);

    // One chain segment run per chain, joint to joint.
    int chains = 0, eye = 0, hip = 0;
    const cc_overlay_segment* s = cc_overlay_segments(o);
    for (int32_t i = 0; i < cc_overlay_segment_count(o); ++i) {
        chains += s[i].kind == CC_OVERLAY_CHAIN;
        eye += s[i].kind == CC_OVERLAY_EYE_LINE;
        hip += s[i].kind == CC_OVERLAY_HIP_LINE;
        if (s[i].kind == CC_OVERLAY_HIP_LINE) CHECK(std::fabs(s[i].a[1] - 1.0f) < 1e-6f);
    }
    CHECK(chains >= cc_char_chain_count(c));
    CHECK(eye == 1 && hip == 1);

    // Sockets ride their joint's skin matrix.
    CHECK(cc_overlay_socket_count(o) == 5);
    const cc_overlay_socket* so = cc_overlay_sockets(o);
    for (int32_t i = 0; i < 5; ++i) {
        const char* name = nullptr;
        float pos[3], rot[3], scl[3];
        cc_char_socket_get(c, so[i].index, &name, nullptr, pos, rot, scl);
        CHECK(so[i].standard >= 0);
        const float* k = cc_overlay_skin(o) + static_cast<std::size_t>(so[i].joint) * 16;
        for (int r = 0; r < 3; ++r) {
            const float want = k[r] * pos[0] + k[4 + r] * pos[1] + k[8 + r] * pos[2] + k[12 + r];
            CHECK(std::fabs(so[i].matrix[12 + r] - want) < 1e-5f);
        }
    }
    // Capsule: centred on the hips, radius = half the upper-arm span.
    const cc_overlay_capsule* cap = cc_overlay_get_capsule(o);
    const auto ra = static_cast<std::size_t>(index_of(bones, "mixamorig:RightArm"));
    const float span = std::fabs(j[la].point[0] - j[ra].point[0]);
    CHECK(std::fabs(cap->radius - 0.5f * span) < 1e-5f);
    CHECK(cap->height == 1.8f && cap->base[1] == 0.f && std::fabs(cap->base[0]) < 1e-6f);

    // Pick: a ray down onto the left hand's point hits it (or a socket on it: the grip sits mid-hand).
    const auto lh = static_cast<std::size_t>(index_of(bones, "mixamorig:LeftHand"));
    const float org[3] = {j[lh].point[0], 3.f, j[lh].point[2]};
    const float dir[3] = {0, -1, 0};
    int32_t idx = -1;
    float dist = 0;
    CHECK(cc_overlay_pick(o, org, dir, 0.005f, 0.f, 1, &idx, &dist) == CC_PICK_JOINT);
    CHECK(idx == static_cast<int32_t>(lh));
    CHECK(std::fabs(dist - (3.f - j[lh].point[1])) < 1e-4f);
    const float away[3] = {5.f, 3.f, 5.f};
    CHECK(cc_overlay_pick(o, away, dir, 0.005f, 0.f, 1, &idx, &dist) == CC_PICK_NONE && idx == -1);
    // A socket origin is pickable.
    const float sorg[3] = {so[0].matrix[12], so[0].matrix[13] + 2.f, so[0].matrix[14]};
    CHECK(cc_overlay_pick(o, sorg, dir, 0.001f, 0.f, 1, &idx, &dist) == CC_PICK_SOCKET);
    cc_overlay_destroy(o);

    // Explicit locals (rest) instead of the reference pose; no profile = no roles.
    std::vector<float> locals(bones.size() * 9);
    for (std::size_t i = 0; i < bones.size(); ++i) {
        std::memcpy(&locals[i * 9], bones[i].pos, 12);
        std::memcpy(&locals[i * 9 + 3], bones[i].rot, 12);
        locals[i * 9 + 6] = locals[i * 9 + 7] = locals[i * 9 + 8] = 1.f;
    }
    o = cc_char_overlay(c, rig, nullptr, locals.data(), nullptr, nullptr);
    CHECK(o != nullptr);
    CHECK(cc_overlay_joints(o)[hips].role == -2 || cc_overlay_joints(o)[hips].role == -1);
    CHECK(cc_overlay_joints(o)[lf].point[1] < cc_overlay_joints(o)[la].point[1] - 0.1f);  // rest A arms
    cc_overlay_destroy(o);
    cc_char_destroy(c);
    cc_profile_destroy(h);
    cr_rig_destroy(rig);
}

}  // namespace

int main() {
    test_session_groups_undo_redo();
    test_session_limit_and_shared_model();
    test_role_states();
    test_profile_diagram_and_poses();
    test_named_pose_fit();
    test_mirror(false);
    test_mirror(true);
    test_standard_sockets();
    test_overlay_and_pick();
    if (failures != 0) {
        std::fprintf(stderr, "character_edit: %d check(s) failed\n", failures);
        return 1;
    }
    std::printf("character_edit: all checks passed\n");
    return 0;
}
