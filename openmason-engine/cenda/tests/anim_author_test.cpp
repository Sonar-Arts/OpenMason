// Cenda animation authoring (OpenMason #165) conformance: a plain clip passes through unchanged (1.2 sections kept),
// override / additive layers pose exactly as the runtime's graph layer node, the bake equals the final pose, an
// FK -> IK -> FK switch-and-match round trip leaves the pose unchanged, history and reopening. Plain asserts.

#include "cenda/anim_author.h"
#include "cenda/anim_clip.h"
#include "cenda/anim_graph.h"
#include "cenda/rig.h"

#include <cmath>
#include <cstdio>
#include <cstring>
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

// An arm: 0 hips (part), 1 upper (bone, +X), 2 lower (bone), 3 hand (bone).
enum { HIPS = 0, UPPER = 1, LOWER = 2, HAND = 3, N = 4 };

cr_rig* make_rig() {
    static const char* ids[N] = {"hips", "upper", "lower", "hand"};
    cr_joint_desc j[N]{};
    for (int i = 0; i < N; ++i) {
        j[i].scale[0] = j[i].scale[1] = j[i].scale[2] = 1.f;
        j[i].flags = CR_JOINT_HAS_GEOMETRY;
    }
    j[HIPS].parent = -1;
    j[HIPS].kind = CR_JOINT_PART;
    j[HIPS].pos[1] = 1.f;
    for (int i = UPPER; i <= HAND; ++i) {
        j[i].parent = i - 1;
        j[i].kind = CR_JOINT_BONE;
        j[i].endpoint[0] = i == HAND ? 0.1f : 0.3f;
    }
    return cr_rig_create(j, N, ids, ids);
}

cr_key key(float t, float ry, float rz = 0.f) {
    cr_key k{};
    k.time = t;
    k.rot_deg[1] = ry;
    k.rot_deg[2] = rz;
    k.scale[0] = k.scale[1] = k.scale[2] = 1.f;
    k.easing = CR_EASE_LINEAR;
    return k;
}

cm_clip* clip_turning(const char* joint, float a, float b, float duration) {
    cm_clip* c = cm_clip_create();
    cm_header h{30.f, duration, 1, 0, 0.f, 0.f, 0};
    cm_clip_header_set(c, &h);
    cr_key k[2] = {key(0.f, a, a * 0.5f), key(duration, b, b * 0.5f)};
    cm_clip_track_set(c, joint, joint, nullptr, 1, k, 2);
    return c;
}

std::vector<uint8_t> bytes(cc_bytes* b) {
    std::vector<uint8_t> out(cc_bytes_data(b), cc_bytes_data(b) + cc_bytes_size(b));
    cc_bytes_destroy(b);
    return out;
}

int apply(cm_author* a, const std::string& ops, const char* label = nullptr, const char* key = nullptr) {
    const int32_t rc = cm_author_apply(a, ops.data(), static_cast<int64_t>(ops.size()), label, key);
    if (rc < 0) std::fprintf(stderr, "apply refused: %s\n", cm_last_error());
    return rc;
}

double max_diff(const std::vector<float>& a, const std::vector<float>& b) {
    double m = 0;
    for (size_t i = 0; i < a.size(); ++i) m = std::fmax(m, std::fabs(static_cast<double>(a[i]) - b[i]));
    return m;
}

std::vector<float> pose(const cm_author* a, float t, int stage) {
    std::vector<float> v(N * 9);
    CHECK(cm_author_pose(a, t, stage, v.data()) == CM_OK);
    return v;
}

void test_plain_clip_is_lossless() {
    cr_rig* rig = make_rig();
    cm_clip* c = clip_turning("upper", 0.f, 90.f, 1.f);
    cm_clip_notify_add(c, "step", 0.25f, 0.f, "{\"foot\":\"left\"}");
    cm_curve_key ck[2]{};
    ck[1].time = 1.f;
    ck[1].value = 1.f;
    cm_clip_curve_set(c, "lean", ck, 2);
    cm_clip_marker_add(c, "down", 0.5f);
    cm_clip_root_motion_set(c, "hips", CM_ROOT_X | CM_ROOT_Z);
    const std::vector<uint8_t> original = bytes(cm_clip_write(c));
    cm_author* a = cm_author_create(c, rig, nullptr);
    CHECK(a != nullptr);
    CHECK(bytes(cm_author_write(a)) == original);  // nothing authored: the same file
    CHECK(cm_author_dirty(a) == 0);
    const std::string v = cm_author_view(a);
    CHECK(v.find("\"trivial\":true") != std::string::npos);
    CHECK(v.find("\"step\"") != std::string::npos && v.find("\"lean\"") != std::string::npos);
    // A key edit keeps the 1.2 sections.
    CHECK(apply(a, R"([{"op":"key","joint":"upper","time":0.5,"rot":[0,10,0]}])") == 1);
    const cm_clip* out = cm_author_clip(a);
    CHECK(cm_clip_notify_count(out) == 1 && cm_clip_curve_count(out) == 1 && cm_clip_marker_count(out) == 1);
    const char* root = nullptr;
    uint32_t axes = 0;
    cm_clip_root_motion_get(out, &root, &axes);
    CHECK(std::string(root) == "hips" && axes == (CM_ROOT_X | CM_ROOT_Z));
    CHECK(cm_clip_track_count(out) == 1);
    int32_t kc = 0;
    cm_clip_track_get(out, 0, nullptr, nullptr, nullptr, nullptr, &kc);
    CHECK(kc == 3);  // the base keys themselves (trivial: unbaked)
    CHECK(apply(a, R"([{"op":"clip_layer","type":"OVERLAY","mask":["upper","lower"],"fadeIn":0.2,"priority":3}])") == 1);
    CHECK(std::string(cm_author_view(a)).find(R"("clipLayer":{"present":true,"type":"OVERLAY","mask":["upper","lower"])") != std::string::npos);
    cm_author_destroy(a);
    cm_clip_destroy(c);
    cr_rig_destroy(rig);
}

// The runtime's layer node over the same clips, posed at time t (one evaluate of dt = t).
std::vector<float> runtime_layer(cr_rig* rig, cm_clip* base, cm_clip* top, const char* mode, float weight, float t) {
    const std::string json = std::string(R"({"format":"omanimgraph","version":"1.1","slots":["a","b"],"root":"l","nodes":[
        {"id":"ca","type":"clip","slot":"a"},{"id":"cb","type":"clip","slot":"b"},
        {"id":"l","type":"layer","base":"ca","layers":[{"node":"cb","mode":")") + mode + R"(","weight":)" +
                             std::to_string(weight) + "}]}]}";
    int32_t rc = 0;
    cg_graph* g = cg_graph_read(json.data(), static_cast<int64_t>(json.size()), &rc);
    CHECK(g != nullptr);
    cg_instance* inst = cg_instance_create(g, rig, nullptr, nullptr);
    cg_instance_bind_slot(inst, 0, base, nullptr);
    cg_instance_bind_slot(inst, 1, top, nullptr);
    cg_instance_evaluate(inst, t, nullptr, 0, nullptr, nullptr, nullptr, nullptr);
    std::vector<float> v(N * 9);
    cg_instance_local_pose(inst, v.data());
    cg_instance_destroy(inst);
    cg_graph_destroy(g);
    return v;
}

void test_layers_match_the_runtime() {
    cr_rig* rig = make_rig();
    cm_clip* base = clip_turning("upper", 0.f, 60.f, 1.f);
    cm_clip* top = clip_turning("lower", 20.f, -40.f, 1.f);
    cm_author* a = cm_author_create(base, rig, nullptr);
    // The top layer's keys, as the editor would key them (one per top key).
    CHECK(apply(a, R"([{"op":"layer","name":"Arms","weight":0.6},
        {"op":"key","layer":"Arms","joint":"lower","time":0,"rot":[0,20,10]},
        {"op":"key","layer":"Arms","joint":"lower","time":1,"rot":[0,-40,-20]}])", "layer") == 3);
    for (const char* mode : {"override", "additive"}) {
        const std::string op = std::string(R"([{"op":"layer","name":"Arms","mode":")") + mode + "\"}]";
        CHECK(apply(a, op) == 1);
        for (float t : {0.25f, 0.5f, 0.8f}) {
            const std::vector<float> editor = pose(a, t, CM_STAGE_LAYERS);
            const std::vector<float> runtime = runtime_layer(rig, base, top, mode, 0.6f, t);
            // The layer really changes the pose (the comparison is not trivially rest == rest).
            CHECK(max_diff(editor, runtime_layer(rig, base, top, mode, 0.f, t)) > 1.0);
            CHECK(std::fabs(editor[UPPER * 9 + 4]) > 1.f);
            CHECK(max_diff(editor, runtime) < 1e-5);
            if (max_diff(editor, runtime) >= 1e-5) std::fprintf(stderr, "  %s t=%g diff %g\n", mode, t, max_diff(editor, runtime));
        }
    }
    // The bake is the final pose at every key time; the authoring data survives a write / read.
    const cm_clip* out = cm_author_clip(a);
    int32_t kc = 0;
    cm_clip_track_get(out, 0, nullptr, nullptr, nullptr, nullptr, &kc);
    CHECK(kc == 31);  // 30 fps over 1 s
    const std::string v = cm_author_view(a);
    CHECK(v.find("\"trivial\":false") != std::string::npos);
    const std::vector<uint8_t> file = bytes(cm_author_write(a));
    int32_t rc = 0;
    cm_clip* back = cm_clip_read(file.data(), static_cast<int64_t>(file.size()), &rc);
    CHECK(back != nullptr);
    cm_author* b = cm_author_create(back, rig, nullptr);
    CHECK(std::string(cm_author_view(b)) == v);
    CHECK(max_diff(pose(b, 0.4f, CM_STAGE_FINAL), pose(a, 0.4f, CM_STAGE_FINAL)) == 0.0);
    // The inspector's value: the layer's own track, not the composed pose.
    float lv[9];
    CHECK(cm_author_layer_value(a, 1, LOWER, 0.f, lv) == CM_OK && std::fabs(lv[4] - 20.f) < 1e-4f && std::fabs(lv[5] - 10.f) < 1e-4f);
    CHECK(cm_author_layer_value(a, 0, LOWER, 0.f, lv) == CM_OK && lv[4] == 0.f);
    CHECK(cm_author_layer_value(a, 5, LOWER, 0.f, lv) == CM_ERR_RANGE);
    // Mute / solo / weight keys.
    CHECK(apply(a, R"([{"op":"layer","name":"Arms","mute":true}])") == 1);
    CHECK(max_diff(pose(a, 0.5f, CM_STAGE_LAYERS), runtime_layer(rig, base, top, "override", 0.f, 0.5f)) < 1e-5);
    CHECK(apply(a, R"([{"op":"layer","name":"Arms","mute":false},{"op":"layer_weight","layer":"Arms","time":0,"value":0},
        {"op":"layer_weight","layer":"Arms","time":1,"value":1}])") == 3);
    const std::string vw = cm_author_view(a);
    CHECK(vw.find("\"weightKeys\":[[0,0],[1,1]]") != std::string::npos || vw.find("weightKeys") != std::string::npos);
    cm_author_destroy(b);
    cm_clip_destroy(back);
    cm_author_destroy(a);
    cm_clip_destroy(top);
    cm_clip_destroy(base);
    cr_rig_destroy(rig);
}

void test_fk_ik_fk_round_trip() {
    cr_rig* rig = make_rig();
    cm_clip* c = cm_clip_create();
    cm_header h{30.f, 1.f, 1, 0, 0.f, 0.f, 0};
    cm_clip_header_set(c, &h);
    cr_key up[2] = {key(0.f, 0.f, -30.f), key(1.f, 40.f, -60.f)};
    cr_key lo[2] = {key(0.f, 0.f, 50.f), key(1.f, -20.f, 30.f)};
    cm_clip_track_set(c, "upper", "upper", nullptr, 1, up, 2);
    cm_clip_track_set(c, "lower", "lower", nullptr, 1, lo, 2);
    cm_author* a = cm_author_create(c, rig, nullptr);
    CHECK(apply(a, R"([{"op":"effector","name":"armIK","kind":"twoBone","upper":"upper","mid":"lower","end":"hand"}])") == 1);
    const float t = 0.4f;
    const std::vector<float> fk = pose(a, t, CM_STAGE_FINAL);  // no keys yet: weight 0 = FK
    CHECK(apply(a, R"([{"op":"match","effector":"armIK","time":0.4,"to":"ik"}])", "match ik") == 1);
    const std::vector<float> ik = pose(a, t, CM_STAGE_FINAL);
    // World positions of the chain must be where FK put them (local Euler may differ by an equivalent angle).
    std::vector<float> wf(N * 16), wi(N * 16);
    cr_rig_pose_locals(rig, fk.data(), nullptr, 0, nullptr, nullptr, wf.data(), nullptr);
    cr_rig_pose_locals(rig, ik.data(), nullptr, 0, nullptr, nullptr, wi.data(), nullptr);
    CHECK(max_diff(wf, wi) < 1e-4);
    if (max_diff(wf, wi) >= 1e-4) std::fprintf(stderr, "  FK->IK world diff %g\n", max_diff(wf, wi));
    const std::string handles = cm_author_handles(a, t);
    CHECK(handles.find("\"armIK\"") != std::string::npos);
    CHECK(apply(a, R"([{"op":"match","effector":"armIK","time":0.4,"to":"fk"}])", "match fk") == 1);
    const std::vector<float> back = pose(a, t, CM_STAGE_FINAL);
    std::vector<float> wb(N * 16);
    cr_rig_pose_locals(rig, back.data(), nullptr, 0, nullptr, nullptr, wb.data(), nullptr);
    CHECK(max_diff(wf, wb) < 1e-4);
    if (max_diff(wf, wb) >= 1e-4) std::fprintf(stderr, "  FK->IK->FK world diff %g\n", max_diff(wf, wb));
    // Moving the effector moves the hand; undo walks back exactly.
    CHECK(apply(a, R"([{"op":"effector_key","effector":"armIK","time":0.4,"pos":[0.2,0.75,0.1],"weight":1}])", "drag") == 1);
    std::vector<float> wd(N * 16);
    const std::vector<float> dragged = pose(a, t, CM_STAGE_FINAL);
    cr_rig_pose_locals(rig, dragged.data(), nullptr, 0, nullptr, nullptr, wd.data(), nullptr);
    CHECK(max_diff(wd, wf) > 0.05);
    CHECK(cm_author_undo(a) == CM_OK);
    CHECK(max_diff(pose(a, t, CM_STAGE_FINAL), back) == 0.0);
    CHECK(std::string(cm_author_label(a, 1)) == "drag");
    CHECK(cm_author_step_count(a) == 4);
    cm_author_destroy(a);
    cm_clip_destroy(c);
    cr_rig_destroy(rig);
}

// Match to FK keys a layer that can hold the pose: refused on a half-weight layer, picked automatically otherwise.
void test_match_fk_picks_a_layer_that_holds_the_pose() {
    cr_rig* rig = make_rig();
    cm_clip* c = clip_turning("upper", 0.f, 50.f, 1.f);
    cm_author* a = cm_author_create(c, rig, nullptr);
    CHECK(apply(a, R"([{"op":"layer","name":"Half","weight":0.5},
        {"op":"key","layer":"Half","joint":"lower","time":0,"rot":[0,0,40]},
        {"op":"effector","name":"armIK","kind":"twoBone","upper":"upper","mid":"lower","end":"hand"}])") == 3);
    const float t = 0.6f;
    std::vector<float> w0(N * 16), w1(N * 16);
    cr_rig_pose_locals(rig, pose(a, t, CM_STAGE_FINAL).data(), nullptr, 0, nullptr, nullptr, w0.data(), nullptr);
    CHECK(apply(a, R"([{"op":"match","effector":"armIK","time":0.6,"to":"ik"}])") == 1);
    const std::string bad = R"([{"op":"match","effector":"armIK","time":0.6,"to":"fk","layer":"Half"}])";
    CHECK(cm_author_apply(a, bad.data(), static_cast<int64_t>(bad.size()), nullptr, nullptr) == CM_ERR_FORMAT);
    CHECK(std::string(cm_last_error()).find("no layer holds the pose") != std::string::npos);
    const std::string base = R"([{"op":"match","effector":"armIK","time":0.6,"to":"fk","layer":"base"}])";
    CHECK(cm_author_apply(a, base.data(), static_cast<int64_t>(base.size()), nullptr, nullptr) == CM_ERR_FORMAT);
    // A full-weight Half holds it; with no layer named the match finds it.
    CHECK(apply(a, R"([{"op":"layer","name":"Half","weight":1}])") == 1);
    cr_rig_pose_locals(rig, pose(a, t, CM_STAGE_FINAL).data(), nullptr, 0, nullptr, nullptr, w0.data(), nullptr);
    CHECK(apply(a, R"([{"op":"match","effector":"armIK","time":0.6,"to":"fk"}])") == 1);
    cr_rig_pose_locals(rig, pose(a, t, CM_STAGE_FINAL).data(), nullptr, 0, nullptr, nullptr, w1.data(), nullptr);
    CHECK(max_diff(w0, w1) < 1e-4);
    CHECK(std::string(cm_author_view(a)).find(R"("name":"Half")") != std::string::npos);
    cm_author_destroy(a);
    cm_clip_destroy(c);
    cr_rig_destroy(rig);
}

void test_history_and_atomicity() {
    cr_rig* rig = make_rig();
    cm_clip* c = clip_turning("upper", 0.f, 90.f, 1.f);
    cm_author* a = cm_author_create(c, rig, nullptr);
    const std::vector<uint8_t> base = bytes(cm_author_write(a));
    for (int i = 0; i < 4; ++i) {
        const std::string op = R"([{"op":"key","joint":"upper","time":0.5,"rot":[0,)" + std::to_string(10 * i) + ",0]}]";
        CHECK(apply(a, op, "drag key", "key:upper") == 1);
    }
    CHECK(cm_author_step_count(a) == 1);
    const std::vector<uint8_t> before = bytes(cm_author_write(a));
    const std::string bad = R"([{"op":"header","name":"x"},{"op":"key","joint":"nope","time":0}])";
    CHECK(cm_author_apply(a, bad.data(), static_cast<int64_t>(bad.size()), nullptr, nullptr) == CM_ERR_FORMAT);
    CHECK(std::string(cm_last_error()).find("ops[1] (key)") != std::string::npos);
    CHECK(bytes(cm_author_write(a)) == before);
    CHECK(cm_author_undo(a) == CM_OK);
    CHECK(bytes(cm_author_write(a)) == base);
    CHECK(cm_author_dirty(a) == 0);
    CHECK(cm_author_undo(a) == CM_ERR_RANGE);
    cm_author_destroy(a);
    cm_clip_destroy(c);
    cr_rig_destroy(rig);
}

void test_root_motion_preview() {
    cr_rig* rig = make_rig();
    cm_clip* c = cm_clip_create();
    cm_header h{30.f, 1.f, 1, 0, 0.f, 0.f, 0};
    cm_clip_header_set(c, &h);
    cr_key k[2] = {key(0.f, 0.f), key(1.f, 90.f)};
    k[0].pos[1] = 1.f;
    k[1].pos[0] = 2.f;
    k[1].pos[1] = 1.5f;
    cm_clip_track_set(c, "hips", "hips", nullptr, 0, k, 2);
    cm_author* a = cm_author_create(c, rig, nullptr);
    float d[4];
    CHECK(cm_author_root_motion(a, 0.5f, d) == CM_OK && d[0] == 0.f && d[3] == 0.f);  // none set
    CHECK(apply(a, R"([{"op":"root_motion","joint":"hips","axes":["x","z","yaw"]}])") == 1);
    CHECK(cm_author_root_motion(a, 0.5f, d) == CM_OK);
    CHECK(std::fabs(d[0] - 1.f) < 1e-5f && d[1] == 0.f && std::fabs(d[2]) < 1e-5f);  // y not extracted
    CHECK(std::fabs(d[3] - 45.f) < 1e-3f);
    cm_author_destroy(a);
    cm_clip_destroy(c);
    cr_rig_destroy(rig);
}

}  // namespace

int main() {
    test_root_motion_preview();
    test_plain_clip_is_lossless();
    test_layers_match_the_runtime();
    test_fk_ik_fk_round_trip();
    test_history_and_atomicity();
    test_match_fk_picks_a_layer_that_holds_the_pose();
    if (failures != 0) {
        std::fprintf(stderr, "anim_author: %d check(s) failed\n", failures);
        return 1;
    }
    std::printf("anim_author: all checks passed\n");
    return 0;
}
