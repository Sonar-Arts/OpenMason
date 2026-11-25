// Cenda animation graph (cg_*, OpenMason #157) conformance: the .omanimgraph format, blend-space weights on and
// between samples, transition conditions / priority / triggers / exit time / conduits / travel, crossfade and
// inertialized transitions, montages (blend in, sections, blend out, interrupt), layer masks (joint and role),
// additive layers, sync groups, root motion, notifies, curves, and determinism over 10k random parameter
// sequences. Plain asserts.

#include "cenda/anim_graph.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <random>
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

bool near(float a, float b, float eps = 1e-4f) { return std::fabs(a - b) <= eps; }

// Joints (parts, ids = names): 0 hips, 1 spine (hips), 2 head (spine), 3 arm (spine), 4 leg (hips).
enum { HIPS = 0, SPINE = 1, HEAD = 2, ARM = 3, LEG = 4, JOINTS = 5 };

cr_rig* make_rig() {
    static const char* names[JOINTS] = {"hips", "spine", "head", "arm", "leg"};
    const int parents[JOINTS] = {-1, 0, 1, 1, 0};
    cr_joint_desc j[JOINTS]{};
    for (int i = 0; i < JOINTS; ++i) {
        j[i].parent = parents[i];
        j[i].kind = CR_JOINT_PART;
        j[i].flags = CR_JOINT_HAS_GEOMETRY;
        j[i].scale[0] = j[i].scale[1] = j[i].scale[2] = 1.f;
    }
    return cr_rig_create(j, JOINTS, names, names);
}

cr_key key(float t, float ry, float pz = 0.f) {
    cr_key k{};
    k.time = t;
    k.rot_deg[1] = ry;
    k.pos[2] = pz;
    k.scale[0] = k.scale[1] = k.scale[2] = 1.f;
    k.easing = CR_EASE_LINEAR;
    return k;
}

// A clip turning `joint` about Y from a to b degrees over `duration` (constant when a == b).
cm_clip* turn(const char* joint, float a, float b, float duration, bool loop) {
    cm_clip* c = cm_clip_create();
    cm_header h{30.f, duration, loop ? 1 : 0, 0, 0.f, 0.f, 0};
    cm_clip_header_set(c, &h);
    cr_key k[2] = {key(0.f, a), key(duration, b)};
    cm_clip_track_set(c, joint, joint, nullptr, 0, k, 2);
    return c;
}

cg_graph* graph(const std::string& json) {
    int32_t rc = 0;
    cg_graph* g = cg_graph_read(json.data(), static_cast<int64_t>(json.size()), &rc);
    if (g == nullptr) std::fprintf(stderr, "graph refused: %s\n", cg_last_error());
    CHECK(g != nullptr && rc == CG_OK);
    return g;
}

int32_t refuse(const std::string& json) {
    int32_t rc = 0;
    cg_graph* g = cg_graph_read(json.data(), static_cast<int64_t>(json.size()), &rc);
    CHECK(g == nullptr);
    cg_graph_destroy(g);
    return rc;
}

std::vector<float> local(cg_instance* inst) {
    std::vector<float> v(JOINTS * 9);
    CHECK(cg_instance_local_pose(inst, v.data()) == CG_OK);
    return v;
}

float rot_y(cg_instance* inst, int joint) { return local(inst)[static_cast<size_t>(joint) * 9 + 4]; }

void eval(cg_instance* inst, float dt) {
    CHECK(cg_instance_evaluate(inst, dt, nullptr, 0, nullptr, nullptr, nullptr, nullptr) == CG_OK);
}

std::vector<cg_node_debug> debug(const cg_graph* g, cg_instance* inst) {
    std::vector<cg_node_debug> d(static_cast<size_t>(cg_graph_node_count(g)));
    CHECK(cg_instance_debug(inst, d.data()) == CG_OK);
    return d;
}

float influence(const cg_graph* g, cg_instance* inst, const char* node) {
    return debug(g, inst)[static_cast<size_t>(cg_graph_node_find(g, node))].influence;
}

void bind(cg_instance* inst, const cg_graph* g, const char* slot, cm_clip* clip) {
    CHECK(cg_instance_bind_slot(inst, cg_graph_slot_find(g, slot), clip, nullptr) == CG_OK);
}

// ---- format -------------------------------------------------------------------------------------------

const char* kLocomotion = R"({
  "format": "omanimgraph", "version": "1.0", "name": "Locomotion", "profile": "humanoid",
  "parameters": [
    {"name": "speed", "type": "float"},
    {"name": "move", "type": "vec2", "default": [0, 1]},
    {"name": "grounded", "type": "bool", "default": true},
    {"name": "stance", "type": "int"},
    {"name": "jump", "type": "trigger"},
    {"name": "aim", "type": "float", "default": 1}
  ],
  "slots": ["idle", "walk", "run", "jump", "aim"],
  "masks": [{"name": "upper", "entries": [{"joint": "spine"}]}],
  "root": "out",
  "nodes": [
    {"id": "idle", "type": "clip", "slot": "idle"},
    {"id": "walk", "type": "clip", "slot": "walk", "sync": "feet"},
    {"id": "run", "type": "clip", "slot": "run", "sync": "feet"},
    {"id": "loco", "type": "blend1d", "param": "speed",
     "samples": [{"node": "run", "value": 6}, {"node": "idle", "value": 0}, {"node": "walk", "value": 2}]},
    {"id": "jumpClip", "type": "clip", "slot": "jump"},
    {"id": "sm", "type": "stateMachine", "entry": "ground",
     "states": [{"name": "ground", "node": "loco"}, {"name": "air", "node": "jumpClip"}, {"name": "via", "conduit": true}],
     "transitions": [
       {"from": "ground", "to": "via", "conditions": [{"param": "jump"}], "blend": 0},
       {"from": "via", "to": "air", "conditions": [{"param": "grounded"}], "blend": 0},
       {"from": "air", "to": "ground", "exitTime": 1, "blend": 0.25, "mode": "crossfade"}
     ]},
    {"id": "aimClip", "type": "clip", "slot": "aim"},
    {"id": "layered", "type": "layer", "base": "sm",
     "layers": [{"node": "aimClip", "mask": "upper", "weightParam": "aim"}]},
    {"id": "out", "type": "slot", "name": "full", "source": "layered"}
  ],
  "x-editor": {"zoom": 2}
})";

void test_format() {
    cg_graph* g = graph(kLocomotion);
    CHECK(std::string(cg_graph_name(g)) == "Locomotion");
    CHECK(cg_graph_param_count(g) == 6 && cg_graph_slot_count(g) == 5 && cg_graph_node_count(g) == 9);
    const int32_t sm = cg_graph_node_find(g, "sm");
    CHECK(cg_graph_state_count(g, sm) == 3 && cg_graph_state_find(g, sm, "air") == 1);
    CHECK(cg_graph_montage_slot_find(g, "full") == 0);
    int32_t type = -1, parent = -9;
    const char* id = nullptr;
    CHECK(cg_graph_node_get(g, cg_graph_node_find(g, "loco"), &id, &type, &parent) == CG_OK);
    CHECK(type == CG_NODE_BLEND1D && parent == sm);
    // write(read(write)) is byte-identical, the unknown section survives.
    cc_bytes* a = cg_graph_write(g);
    std::string text(reinterpret_cast<const char*>(cc_bytes_data(a)), static_cast<size_t>(cc_bytes_size(a)));
    CHECK(text.find("x-editor") != std::string::npos);
    cg_graph* g2 = graph(text);
    cc_bytes* b = cg_graph_write(g2);
    CHECK(cc_bytes_size(a) == cc_bytes_size(b) && std::memcmp(cc_bytes_data(a), cc_bytes_data(b), static_cast<size_t>(cc_bytes_size(a))) == 0);
    cc_bytes_destroy(a);
    cc_bytes_destroy(b);
    cg_graph_destroy(g2);
    cg_graph_destroy(g);

    const std::string head = R"({"format": "omanimgraph", "version": "1.0", "slots": ["a"], "parameters": [{"name": "f", "type": "float"}, {"name": "b", "type": "bool"}], )";
    CHECK(refuse(head + R"("root": "x", "nodes": [{"id": "y", "type": "clip", "slot": "a"}]})") == CG_ERR_FORMAT);
    CHECK(refuse(head + R"("root": "s", "nodes": [{"id": "s", "type": "slot", "name": "m", "source": "s"}]})") == CG_ERR_FORMAT);
    CHECK(refuse(head + R"("root": "b", "nodes": [{"id": "c", "type": "clip", "slot": "a"},
        {"id": "b", "type": "blend1d", "param": "f", "samples": [{"node": "c", "value": 0}, {"node": "c", "value": 1}]}]})") == CG_ERR_FORMAT);
    CHECK(refuse(head + R"("root": "b", "nodes": [{"id": "c", "type": "clip", "slot": "a"},
        {"id": "b", "type": "blend1d", "param": "b", "samples": [{"node": "c", "value": 0}]}]})") == CG_ERR_FORMAT);
    CHECK(refuse(head + R"("root": "c", "nodes": [{"id": "c", "type": "clip", "slot": "nope"}]})") == CG_ERR_FORMAT);
    CHECK(refuse(head + R"("root": "c", "nodes": [{"id": "c", "type": "clip", "slot": "a"},
        {"id": "p", "type": "slot", "name": "m", "source": "q"}, {"id": "q", "type": "slot", "name": "n", "source": "p"}]})") == CG_ERR_FORMAT);
    CHECK(refuse(R"({"format": "omanimgraph", "version": "2.0", "root": "c", "nodes": []})") == CG_ERR_VERSION);
}

// ---- blend spaces -------------------------------------------------------------------------------------

void test_blend_spaces() {
    cr_rig* rig = make_rig();
    cm_clip* idle = turn("leg", 0.f, 0.f, 1.f, true);
    cm_clip* walk = turn("leg", 30.f, 30.f, 1.f, true);
    cm_clip* run = turn("leg", 60.f, 60.f, 0.6f, true);
    cg_graph* g = graph(kLocomotion);
    cg_instance* inst = cg_instance_create(g, rig, nullptr, nullptr);
    bind(inst, g, "idle", idle);
    bind(inst, g, "walk", walk);
    bind(inst, g, "run", run);
    const int32_t speed = cg_graph_param_find(g, "speed");
    // On sample points: exactly that sample.
    for (auto [v, want] : {std::pair{0.f, 0.f}, {2.f, 30.f}, {6.f, 60.f}, {-3.f, 0.f}, {9.f, 60.f}}) {
        CHECK(cg_instance_set_float(inst, speed, v) == CG_OK);
        eval(inst, 1.f / 60.f);
        CHECK(rot_y(inst, LEG) == want);
    }
    CHECK(cg_instance_set_float(inst, speed, 2.f) == CG_OK);
    eval(inst, 1.f / 60.f);
    CHECK(influence(g, inst, "walk") == 1.f && influence(g, inst, "idle") == 0.f);
    // Between: linear.
    CHECK(cg_instance_set_float(inst, speed, 4.f) == CG_OK);
    eval(inst, 1.f / 60.f);
    CHECK(near(rot_y(inst, LEG), 45.f));
    CHECK(near(influence(g, inst, "walk"), 0.5f) && near(influence(g, inst, "run"), 0.5f));
    CHECK(cg_instance_set_float(inst, speed, 0.5f) == CG_OK);
    eval(inst, 1.f / 60.f);
    CHECK(near(rot_y(inst, LEG), 7.5f));
    CHECK(cg_instance_set_int(inst, speed, 1) == CG_ERR_ARG);  // wrong type
    cg_instance_destroy(inst);
    cg_graph_destroy(g);

    // 2D: five samples (idle at the origin, a ring of four).
    for (const char* mode : {"cartesian", "directional"}) {
        std::string json = std::string(R"({"format": "omanimgraph", "version": "1.0",
          "parameters": [{"name": "move", "type": "vec2"}], "slots": ["c", "f", "r", "l", "b"], "root": "bs",
          "nodes": [{"id": "c", "type": "clip", "slot": "c"}, {"id": "f", "type": "clip", "slot": "f"},
            {"id": "r", "type": "clip", "slot": "r"}, {"id": "l", "type": "clip", "slot": "l"},
            {"id": "b", "type": "clip", "slot": "b"},
            {"id": "bs", "type": "blend2d", "param": "move", "mode": ")") + mode + R"(", "samples": [
              {"node": "c", "x": 0, "y": 0}, {"node": "f", "x": 0, "y": 1}, {"node": "r", "x": 1, "y": 0},
              {"node": "l", "x": -1, "y": 0}, {"node": "b", "x": 0, "y": -1}]}]})";
        g = graph(json);
        inst = cg_instance_create(g, rig, nullptr, nullptr);
        const char* ids[5] = {"c", "f", "r", "l", "b"};
        const float xs[5] = {0, 0, 1, -1, 0}, ys[5] = {0, 1, 0, 0, -1};
        const int32_t move = cg_graph_param_find(g, "move");
        for (int s = 0; s < 5; ++s) {
            CHECK(cg_instance_set_vec2(inst, move, xs[s], ys[s]) == CG_OK);
            eval(inst, 0.f);
            for (int k = 0; k < 5; ++k) CHECK(influence(g, inst, ids[k]) == (k == s ? 1.f : 0.f));
        }
        // Between samples: weights are non-negative and sum to 1; symmetric points blend symmetrically.
        CHECK(cg_instance_set_vec2(inst, move, 0.5f, 0.5f) == CG_OK);
        eval(inst, 0.f);
        float sum = 0.f;
        for (const char* id2 : ids) {
            const float w = influence(g, inst, id2);
            CHECK(w >= 0.f);
            sum += w;
        }
        CHECK(near(sum, 1.f, 1e-5f));
        CHECK(near(influence(g, inst, "f"), influence(g, inst, "r"), 1e-5f) && influence(g, inst, "f") > 0.2f);
        CHECK(influence(g, inst, "l") < 1e-6f && influence(g, inst, "b") < 1e-6f);
        cg_instance_destroy(inst);
        cg_graph_destroy(g);
    }
    cm_clip_destroy(run);
    cm_clip_destroy(walk);
    cm_clip_destroy(idle);
    cr_rig_destroy(rig);
}

// ---- state machines -----------------------------------------------------------------------------------

const char* kMachine = R"({"format": "omanimgraph", "version": "1.0",
  "parameters": [{"name": "speed", "type": "float"}, {"name": "jump", "type": "trigger"},
                 {"name": "grounded", "type": "bool", "default": true}],
  "slots": ["idle", "walk", "jump", "fall"], "root": "sm",
  "nodes": [
    {"id": "idle", "type": "clip", "slot": "idle"}, {"id": "walk", "type": "clip", "slot": "walk"},
    {"id": "jump", "type": "clip", "slot": "jump"}, {"id": "fall", "type": "clip", "slot": "fall"},
    {"id": "sm", "type": "stateMachine", "entry": "idle",
     "states": [{"name": "idle", "node": "idle"}, {"name": "walk", "node": "walk"}, {"name": "jump", "node": "jump"},
                {"name": "fall", "node": "fall"}, {"name": "air", "conduit": true}],
     "transitions": [
       {"from": "idle", "to": "walk", "conditions": [{"param": "speed", "op": ">", "value": 0.5}], "blend": 0},
       {"from": "idle", "to": "air", "conditions": [{"param": "jump"}], "priority": 5, "blend": 0},
       {"from": "air", "to": "jump", "conditions": [{"param": "grounded"}], "blend": 0},
       {"from": "air", "to": "fall", "conditions": [{"param": "grounded", "op": "==", "value": false}], "blend": 0},
       {"from": "walk", "to": "idle", "conditions": [{"param": "speed", "op": "<=", "value": 0.5}], "blend": 0.2, "mode": "crossfade"},
       {"from": "jump", "to": "idle", "exitTime": 1, "blend": 0},
       {"from": "*", "to": "fall", "conditions": [{"param": "grounded", "op": "==", "value": false}], "priority": -1, "blend": 0.3}
     ]}]})";

int32_t state_of(const cg_graph* g, cg_instance* inst) {
    int32_t s = -1;
    cg_instance_state(inst, cg_graph_node_find(g, "sm"), &s, nullptr, nullptr);
    return s;
}

void test_state_machine() {
    cr_rig* rig = make_rig();
    cm_clip* idle = turn("leg", 0.f, 0.f, 1.f, true);
    cm_clip* walk = turn("leg", 30.f, 30.f, 1.f, true);
    cm_clip* jump = turn("leg", 90.f, 90.f, 0.5f, false);
    cm_clip* fall = turn("leg", -40.f, -40.f, 1.f, true);
    cg_graph* g = graph(kMachine);
    cg_instance* inst = cg_instance_create(g, rig, nullptr, nullptr);
    bind(inst, g, "idle", idle);
    bind(inst, g, "walk", walk);
    bind(inst, g, "jump", jump);
    bind(inst, g, "fall", fall);
    const int32_t sm = cg_graph_node_find(g, "sm");
    const int32_t speed = cg_graph_param_find(g, "speed"), trig = cg_graph_param_find(g, "jump"),
                  grounded = cg_graph_param_find(g, "grounded");
    const float dt = 1.f / 60.f;
    eval(inst, dt);
    CHECK(state_of(g, inst) == 0 && rot_y(inst, LEG) == 0.f);
    // Both idle->walk and the jump conduit hold: the higher priority wins and the conduit resolves at once.
    cg_instance_set_float(inst, speed, 1.f);
    cg_instance_fire_trigger(inst, trig);
    eval(inst, dt);
    CHECK(state_of(g, inst) == cg_graph_state_find(g, sm, "jump"));
    CHECK(rot_y(inst, LEG) == 90.f);
    float tv[2];
    cg_instance_get_param(inst, trig, tv);
    CHECK(tv[0] == 0.f);  // consumed
    // Exit time 1 of a 0.5 s clip: back to idle after half a second (and on to walk the frame after).
    for (int i = 0; i < 29; ++i) eval(inst, dt);
    CHECK(state_of(g, inst) == cg_graph_state_find(g, sm, "jump"));
    eval(inst, dt);
    CHECK(state_of(g, inst) == cg_graph_state_find(g, sm, "idle"));
    eval(inst, dt);
    CHECK(state_of(g, inst) == cg_graph_state_find(g, sm, "walk"));
    eval(inst, dt);
    CHECK(rot_y(inst, LEG) == 30.f);
    // Crossfade walk -> idle over 0.2 s: halfway after 0.1 s.
    cg_instance_set_float(inst, speed, 0.f);
    eval(inst, 0.f);  // takes the transition
    for (int i = 0; i < 5; ++i) eval(inst, 0.02f);
    float blend = 0.f;
    int32_t prev = -1;
    cg_instance_state(inst, sm, nullptr, &prev, &blend);
    CHECK(near(blend, 0.5f) && prev == cg_graph_state_find(g, sm, "walk"));
    CHECK(near(rot_y(inst, LEG), 15.f, 1e-3f));
    for (int i = 0; i < 10; ++i) eval(inst, 0.02f);
    CHECK(rot_y(inst, LEG) == 0.f);
    // Any-state at the lowest priority, inertialized: fall. No jump (the conduit needs grounded).
    cg_instance_set_bool(inst, grounded, 0);
    eval(inst, dt);
    CHECK(state_of(g, inst) == cg_graph_state_find(g, sm, "fall"));
    const float first = rot_y(inst, LEG);
    CHECK(first > -2.f && first <= 0.f);  // inertialized: starts where idle was
    for (int i = 0; i < 30; ++i) eval(inst, dt);
    CHECK(rot_y(inst, LEG) == -40.f);
    // A conduit whose exits all fail blocks its transition: jump fired while airborne does nothing.
    cg_instance_fire_trigger(inst, trig);
    eval(inst, dt);
    CHECK(state_of(g, inst) == cg_graph_state_find(g, sm, "fall"));
    cg_instance_reset_trigger(inst, trig);
    // Travel: fall has no way out but the any-state edge into itself -> no path to walk.
    CHECK(cg_instance_travel(inst, sm, cg_graph_state_find(g, sm, "walk")) == CG_ERR_STATE);
    cg_instance_set_bool(inst, grounded, 1);
    CHECK(cg_instance_reset(inst) == CG_OK);
    eval(inst, dt);
    // From idle to fall through the any-state edge, conditions ignored.
    CHECK(cg_instance_travel(inst, sm, cg_graph_state_find(g, sm, "walk")) == CG_OK);
    eval(inst, dt);
    CHECK(state_of(g, inst) == cg_graph_state_find(g, sm, "walk"));
    cg_instance_destroy(inst);
    cg_graph_destroy(g);
    for (cm_clip* c : {idle, walk, jump, fall}) cm_clip_destroy(c);
    cr_rig_destroy(rig);
}

// ---- montages -----------------------------------------------------------------------------------------

void test_montages() {
    cr_rig* rig = make_rig();
    cm_clip* idle = turn("leg", 0.f, 0.f, 1.f, true);
    cm_clip* attack = turn("leg", 90.f, 90.f, 1.f, false);
    cm_clip_notify_add(attack, "section:start", 0.f, 0.f, nullptr);
    cm_clip_notify_add(attack, "section:loop", 0.4f, 0.f, nullptr);
    cm_clip_notify_add(attack, "section:end", 0.7f, 0.f, nullptr);
    cm_clip_notify_add(attack, "hit", 0.5f, 0.f, R"({"damage": 3})");
    cm_clip* wave = turn("leg", -50.f, -50.f, 1.f, false);
    cg_graph* g = graph(R"({"format": "omanimgraph", "version": "1.0", "slots": ["idle"], "root": "out",
      "nodes": [{"id": "idle", "type": "clip", "slot": "idle"},
                {"id": "out", "type": "slot", "name": "full", "source": "idle"}]})");
    cg_instance* inst = cg_instance_create(g, rig, nullptr, nullptr);
    bind(inst, g, "idle", idle);
    const int32_t a = cg_instance_add_montage(inst, attack);
    const int32_t w = cg_instance_add_montage(inst, wave);
    CHECK(a == 0 && w == 1);
    const float dt = 0.05f;
    eval(inst, dt);
    // Blend in over 0.2 s from an idle slot.
    CHECK(cg_instance_montage_play(inst, 0, a, 1.f, 0.2f, 0.2f, nullptr) == CG_OK);
    eval(inst, dt);
    eval(inst, dt);
    CHECK(near(rot_y(inst, LEG), 45.f, 1e-3f));
    cg_montage_status st{};
    cg_instance_montage_status(inst, 0, &st);
    CHECK(st.montage == a && near(st.weight, 0.5f) && near(st.time, 0.1f));
    // Loop the middle section: it never reaches the end.
    CHECK(cg_instance_montage_set_next(inst, 0, "loop", "loop") == CG_OK);
    int hits = 0;
    for (int i = 0; i < 100; ++i) {
        eval(inst, dt);
        cg_instance_montage_status(inst, 0, &st);
        CHECK(st.montage == a && st.weight > 0.f);
        for (int32_t k = 0, n = cg_instance_notify_count(inst); k < n; ++k) {
            cg_notify nf{};
            const char* name = nullptr;
            const char* payload = nullptr;
            cg_instance_notify_get(inst, k, &nf, &name, &payload);
            if (std::string(name) == "hit") {
                ++hits;
                CHECK(nf.source == -1 && nf.montage == a && std::string(payload) == R"({"damage":3})");
            }
        }
    }
    CHECK(st.time >= 0.4f && st.time < 0.7f && st.weight == 1.f);
    CHECK(hits >= 15);  // once per 0.3 s loop over 5 s
    CHECK(rot_y(inst, LEG) == 90.f);
    // Release the loop: it plays to the end, fading out over its last 0.2 s.
    CHECK(cg_instance_montage_set_next(inst, 0, "loop", nullptr) == CG_OK);
    CHECK(cg_instance_montage_jump(inst, 0, "end") == CG_OK);
    eval(inst, dt);  // 0.75
    eval(inst, dt);  // 0.80: fade starts
    cg_instance_montage_status(inst, 0, &st);
    CHECK(st.blending_out == 1);
    eval(inst, dt);
    eval(inst, dt);
    const float fading = rot_y(inst, LEG);
    CHECK(fading > 1.f && fading < 89.f);
    for (int i = 0; i < 4; ++i) eval(inst, dt);
    cg_instance_montage_status(inst, 0, &st);
    CHECK(st.montage == -1);
    CHECK(rot_y(inst, LEG) == 0.f);
    // Interrupt: wave over a playing attack is in at once, the change inertialized (no jump).
    CHECK(cg_instance_montage_play(inst, 0, a, 1.f, 0.f, 0.f, nullptr) == CG_OK);
    eval(inst, dt);
    CHECK(rot_y(inst, LEG) == 90.f);
    CHECK(cg_instance_montage_play(inst, 0, w, 1.f, 0.3f, 0.f, nullptr) == CG_OK);
    eval(inst, 1.f / 120.f);
    const float after = rot_y(inst, LEG);
    CHECK(after > 85.f);  // continuous with the attack pose
    for (int i = 0; i < 40; ++i) eval(inst, 1.f / 120.f);
    CHECK(rot_y(inst, LEG) == -50.f);
    CHECK(cg_instance_montage_stop(inst, 0, 0.f) == CG_OK);
    eval(inst, dt);
    CHECK(cg_instance_montage_stop(inst, 0, 0.f) == CG_ERR_STATE);
    cg_instance_destroy(inst);
    cg_graph_destroy(g);
    for (cm_clip* c : {idle, attack, wave}) cm_clip_destroy(c);
    cr_rig_destroy(rig);
}

// ---- layers, masks, additive ------------------------------------------------------------------------------

void test_layers() {
    cr_rig* rig = make_rig();
    cm_clip* base = cm_clip_create();
    cm_header h{30.f, 1.f, 1, 0, 0.f, 0.f, 0};
    cm_clip_header_set(base, &h);
    cr_key k0[1] = {key(0.f, 30.f)};
    cm_clip_track_set(base, "leg", "leg", nullptr, 0, k0, 1);
    // The aim clip keys every joint to 50.
    cm_clip* aim = cm_clip_create();
    cm_clip_header_set(aim, &h);
    for (const char* j : {"hips", "spine", "head", "arm", "leg"}) {
        cr_key k[1] = {key(0.f, 50.f)};
        cm_clip_track_set(aim, j, j, nullptr, 0, k, 1);
    }
    // An additive nod: leg 0 -> 20 over the clip, reference frame 0.
    cm_clip* nod = turn("leg", 0.f, 20.f, 1.f, true);
    cm_clip_additive_set(nod, CM_ADDITIVE_FRAME, 0.f, nullptr, 0);
    const char* json = R"({"format": "omanimgraph", "version": "1.0",
      "parameters": [{"name": "aim", "type": "float", "default": 1}],
      "slots": ["base", "aim", "nod"],
      "masks": [{"name": "upper", "entries": [{"joint": "spine"}]},
                {"name": "spineRole", "entries": [{"role": "chest", "depth": 2}]}],
      "root": "l",
      "nodes": [{"id": "base", "type": "clip", "slot": "base"}, {"id": "aim", "type": "clip", "slot": "aim"},
                {"id": "nod", "type": "clip", "slot": "nod"},
                {"id": "l", "type": "layer", "base": "base", "layers": [
                   {"node": "aim", "mask": "upper", "weightParam": "aim"},
                   {"node": "nod", "mode": "additive"}]}]})";
    cg_graph* g = graph(json);
    int32_t unresolved = -1;
    cg_instance* inst = cg_instance_create(g, rig, nullptr, &unresolved);
    CHECK(unresolved == 1);  // the role entry, without a character
    bind(inst, g, "base", base);
    bind(inst, g, "aim", aim);
    bind(inst, g, "nod", nod);
    eval(inst, 0.f);
    std::vector<float> v = local(inst);
    CHECK(v[HIPS * 9 + 4] == 0.f && v[SPINE * 9 + 4] == 50.f && v[HEAD * 9 + 4] == 50.f && v[ARM * 9 + 4] == 50.f);
    CHECK(v[LEG * 9 + 4] == 30.f);  // outside the mask; the nod adds 0 at frame 0
    eval(inst, 0.5f);
    CHECK(near(rot_y(inst, LEG), 40.f, 1e-3f));  // base 30 + half the nod's 20
    cg_instance_set_float(inst, cg_graph_param_find(g, "aim"), 0.5f);
    eval(inst, 0.f);
    CHECK(near(rot_y(inst, SPINE), 25.f));
    cg_instance_destroy(inst);

    // Role masks resolve through the character.
    cc_char* c = cc_char_create();
    cc_char_role_set(c, "chest", "spine");
    inst = cg_instance_create(g, rig, c, &unresolved);
    CHECK(unresolved == 0);
    cg_instance_destroy(inst);
    cc_char_destroy(c);
    cg_graph_destroy(g);
    for (cm_clip* cl : {base, aim, nod}) cm_clip_destroy(cl);
    cr_rig_destroy(rig);
}

// ---- sync, root motion, notifies, curves ---------------------------------------------------------------

void test_sync_root_motion_notifies_curves() {
    cr_rig* rig = make_rig();
    cm_clip* walk = cm_clip_create();
    cm_header wh{30.f, 1.f, 1, 0, 0.f, 0.f, 0};
    cm_clip_header_set(walk, &wh);
    cr_key wk[2] = {key(0.f, 0.f, 0.f), key(1.f, 0.f, 2.f)};
    cm_clip_track_set(walk, "hips", "hips", nullptr, 0, wk, 2);
    cm_clip_marker_add(walk, "L", 0.1f);
    cm_clip_marker_add(walk, "R", 0.6f);
    cm_clip_notify_add(walk, "step", 0.6f, 0.f, nullptr);
    cm_clip_root_motion_set(walk, "hips", CM_ROOT_X | CM_ROOT_Z | CM_ROOT_YAW);
    cm_curve_key ck[2] = {{0.f, 0.f, 0.f, 0.f, CM_INTERP_LINEAR}, {1.f, 1.f, 0.f, 0.f, CM_INTERP_LINEAR}};
    cm_clip_curve_set(walk, "lean", ck, 2);
    cm_clip* run = cm_clip_create();
    cm_header rh{30.f, 0.6f, 1, 0, 0.f, 0.f, 0};
    cm_clip_header_set(run, &rh);
    cr_key rk[2] = {key(0.f, 0.f, 0.f), key(0.6f, 0.f, 3.f)};
    cm_clip_track_set(run, "hips", "hips", nullptr, 0, rk, 2);
    cm_clip_marker_add(run, "L", 0.05f);
    cm_clip_marker_add(run, "R", 0.35f);
    cm_clip_root_motion_set(run, "hips", CM_ROOT_X | CM_ROOT_Z | CM_ROOT_YAW);
    cg_graph* g = graph(R"({"format": "omanimgraph", "version": "1.0",
      "parameters": [{"name": "speed", "type": "float"}], "slots": ["walk", "run"], "root": "b",
      "nodes": [{"id": "walk", "type": "clip", "slot": "walk"}, {"id": "run", "type": "clip", "slot": "run"},
                {"id": "b", "type": "blend1d", "param": "speed",
                 "samples": [{"node": "walk", "value": 0}, {"node": "run", "value": 1}]}]})");
    cg_instance* inst = cg_instance_create(g, rig, nullptr, nullptr);
    bind(inst, g, "walk", walk);
    bind(inst, g, "run", run);
    CHECK(cg_instance_set_root_motion(inst, CG_ROOT_MOTION_EXTRACT) == CG_OK);
    // Walk only: 2 blocks a second forward, the hips pinned, one "step" a cycle, the curve follows time.
    const float dt = 0.1f;
    int steps = 0;
    for (int i = 0; i < 10; ++i) {
        eval(inst, dt);
        cr_root_motion m{};
        cg_instance_root_motion(inst, &m);
        CHECK(near(m.translation[2], 0.2f, 1e-5f));
        CHECK(local(inst)[HIPS * 9 + 2] == 0.f);
        steps += cg_instance_notify_count(inst);
    }
    CHECK(steps == 1);
    const int32_t lean = cg_instance_curve_find(inst, "lean");
    CHECK(lean == 0);
    eval(inst, 0.25f);
    const float t = debug(g, inst)[static_cast<size_t>(cg_graph_node_find(g, "walk"))].time;
    CHECK(near(t, 0.25f, 1e-4f) || near(t, 1.25f - 1.f, 1e-4f));
    CHECK(near(cg_instance_curve_value(inst, lean), t, 1e-5f));
    // Blend halfway: the run follows the walk's marker phase although it is shorter.
    cg_instance_set_float(inst, cg_graph_param_find(g, "speed"), 0.5f);
    std::vector<cg_node_debug> d;
    for (int i = 0; i < 50; ++i) {
        eval(inst, 1.f / 60.f);
        d = debug(g, inst);
        int32_t wm = -1, rm = -1;
        float wf = 0.f, rf = 0.f;
        cm_clip_marker_position(walk, d[static_cast<size_t>(cg_graph_node_find(g, "walk"))].time, &wm, &wf);
        cm_clip_marker_position(run, d[static_cast<size_t>(cg_graph_node_find(g, "run"))].time, &rm, &rf);
        CHECK(wm == rm && near(wf, rf, 1e-4f));
        // Root motion blends by weight: 0.5 * 2 + 0.5 * 5 blocks per walk-second, the run sped up to match.
        cr_root_motion m{};
        cg_instance_root_motion(inst, &m);
        CHECK(m.translation[2] > 0.f);
    }
    CHECK(near(cg_instance_curve_value(inst, lean), 0.5f * cm_clip_curve_sample(walk, 0, d[0].time), 1e-4f));
    cg_instance_destroy(inst);
    cg_graph_destroy(g);
    cm_clip_destroy(run);
    cm_clip_destroy(walk);
    cr_rig_destroy(rig);
}

// ---- determinism ----------------------------------------------------------------------------------------

void test_determinism() {
    cr_rig* rig = make_rig();
    cm_clip* idle = turn("leg", 0.f, 10.f, 1.3f, true);
    cm_clip* walk = turn("leg", 30.f, -20.f, 1.f, true);
    cm_clip_marker_add(walk, "L", 0.2f);
    cm_clip* run = turn("leg", 60.f, 5.f, 0.6f, true);
    cm_clip_marker_add(run, "L", 0.1f);
    cm_clip* jump = turn("hips", 0.f, 70.f, 0.5f, false);
    cm_clip* aim = turn("spine", -30.f, 30.f, 2.f, true);
    cm_clip* mont = turn("arm", 0.f, 120.f, 0.8f, false);
    cg_graph* g = graph(kLocomotion);
    cg_instance* a = cg_instance_create(g, rig, nullptr, nullptr);
    cg_instance* b = cg_instance_create(g, rig, nullptr, nullptr);
    for (cg_instance* inst : {a, b}) {
        bind(inst, g, "idle", idle);
        bind(inst, g, "walk", walk);
        bind(inst, g, "run", run);
        bind(inst, g, "jump", jump);
        bind(inst, g, "aim", aim);
        CHECK(cg_instance_add_montage(inst, mont) == 0);
    }
    const int32_t speed = cg_graph_param_find(g, "speed"), trig = cg_graph_param_find(g, "jump"),
                  grounded = cg_graph_param_find(g, "grounded"), aimw = cg_graph_param_find(g, "aim");
    std::mt19937 rng(156157u);
    std::uniform_real_distribution<float> unit(0.f, 1.f);
    std::vector<float> sa(JOINTS * 16), sb(JOINTS * 16);
    int mismatches = 0;
    for (int seq = 0; seq < 10000; ++seq) {
        cg_instance_reset(a);
        cg_instance_reset(b);
        const int frames = 4 + static_cast<int>(unit(rng) * 12.f);
        for (int f = 0; f < frames; ++f) {
            const float s = unit(rng) * 8.f, w = unit(rng);
            const bool fire = unit(rng) < 0.2f, ground = unit(rng) < 0.8f, play = unit(rng) < 0.05f;
            const float dt = 1.f / (30.f + unit(rng) * 114.f);
            for (cg_instance* inst : {a, b}) {
                cg_instance_set_float(inst, speed, s);
                cg_instance_set_float(inst, aimw, w);
                cg_instance_set_bool(inst, grounded, ground ? 1 : 0);
                if (fire) cg_instance_fire_trigger(inst, trig);
                if (play) cg_instance_montage_play(inst, 0, 0, 1.f, 0.1f, 0.1f, nullptr);
            }
            CHECK(cg_instance_evaluate(a, dt, nullptr, 0, nullptr, sa.data(), nullptr, nullptr) == CG_OK);
            CHECK(cg_instance_evaluate(b, dt, nullptr, 0, nullptr, sb.data(), nullptr, nullptr) == CG_OK);
            if (std::memcmp(sa.data(), sb.data(), sa.size() * sizeof(float)) != 0) ++mismatches;
            for (float x : sa) {
                if (!std::isfinite(x)) ++mismatches;
            }
        }
    }
    CHECK(mismatches == 0);
    cg_instance_destroy(b);
    cg_instance_destroy(a);
    cg_graph_destroy(g);
    for (cm_clip* c : {idle, walk, run, jump, aim, mont}) cm_clip_destroy(c);
    cr_rig_destroy(rig);
}


// ---- pose modifiers in the graph (#158) ------------------------------------------------------------------

const char* kModifiers = R"({"format": "omanimgraph", "version": "1.0",
  "parameters": [{"name": "ik", "type": "float", "default": 1}],
  "slots": ["pose"], "targets": ["footGoal"], "root": "c",
  "colliders": [{"joint": "role:hips", "a": [0, 0, 0], "radius": 0.1}],
  "nodes": [{"id": "c", "type": "clip", "slot": "pose"}],
  "modifiers": [
    {"type": "twoBone", "upper": "role:upper_leg_l", "mid": "role:lower_leg_l", "end": "role:foot_l",
     "target": "footGoal", "pole": "joint:pole", "weightParam": "ik"},
    {"type": "constraint", "kind": "point", "joint": "marker", "target": "role:foot_l", "weightCurve": "follow"}
  ]})";

void test_modifiers() {
    // Bones: hips (0) at y 2; thigh (1) down 1; shin (2) down 1; foot (3); a pole marker (4) in front; marker (5).
    auto bone = [](int parent, float px, float py, float pz, float ex, float ey, float ez) {
        cr_joint_desc d{};
        d.parent = parent;
        d.kind = CR_JOINT_BONE;
        d.flags = CR_JOINT_HAS_GEOMETRY;
        d.pos[0] = px;
        d.pos[1] = py;
        d.pos[2] = pz;
        d.scale[0] = d.scale[1] = d.scale[2] = 1.f;
        d.endpoint[0] = ex;
        d.endpoint[1] = ey;
        d.endpoint[2] = ez;
        return d;
    };
    cr_joint_desc j[6] = {bone(-1, 0, 2, 0, 0, 0.01f, 0), bone(0, 0, 0, 0, 0, -1, 0), bone(1, 0, 0, 0, 0, -1, 0),
                          bone(2, 0, 0, 0, 0, 0, 0.2f), bone(-1, 0, 1, 1, 0, 0, 0), bone(-1, 5, 5, 5, 0, 0, 0)};
    const char* names[6] = {"hips", "thigh", "shin", "foot", "pole", "marker"};
    cr_rig* rig = cr_rig_create(j, 6, names, names);
    cc_char* c = cc_char_create();
    cc_char_role_set(c, "hips", "hips");
    cc_char_role_set(c, "upper_leg_l", "thigh");
    cc_char_role_set(c, "lower_leg_l", "shin");
    cc_char_role_set(c, "foot_l", "foot");
    // The format round-trips its modifier sections; unknown targets are refused.
    cg_graph* g = graph(kModifiers);
    CHECK(cg_graph_target_count(g) == 1 && cg_graph_target_find(g, "footGoal") == 0 && cg_graph_modifier_count(g) == 2);
    cc_bytes* a = cg_graph_write(g);
    std::string text(reinterpret_cast<const char*>(cc_bytes_data(a)), static_cast<size_t>(cc_bytes_size(a)));
    cg_graph* g2 = graph(text);
    cc_bytes* b = cg_graph_write(g2);
    CHECK(cc_bytes_size(a) == cc_bytes_size(b) && std::memcmp(cc_bytes_data(a), cc_bytes_data(b), static_cast<size_t>(cc_bytes_size(a))) == 0);
    cc_bytes_destroy(a);
    cc_bytes_destroy(b);
    cg_graph_destroy(g2);
    std::string bad = kModifiers;
    bad.replace(bad.find("\"target\": \"footGoal\""), 20, "\"target\": \"nowhere\" ");
    CHECK(refuse(bad) == CG_ERR_FORMAT);

    int32_t unresolved = -1;
    cg_instance* inst = cg_instance_create(g, rig, c, &unresolved);
    CHECK(unresolved == 0);
    cm_clip* follow = cm_clip_create();
    cm_header h{30.f, 1.f, 1, 0, 0.f, 0.f, 0};
    cm_clip_header_set(follow, &h);
    cm_curve_key ck[1] = {{0.f, 0.f, 0.f, 0.f, CM_INTERP_LINEAR}};
    cm_clip_curve_set(follow, "follow", ck, 1);
    bind(inst, g, "pose", follow);
    std::vector<float> w(6 * 16);
    auto foot = [&]() { return std::vector<float>{w[3 * 16 + 12], w[3 * 16 + 13], w[3 * 16 + 14]}; };
    const float goal[3] = {0.4f, 0.6f, 0.5f};
    // No target yet: the IK modifier does nothing.
    CHECK(cg_instance_evaluate(inst, 0.f, nullptr, 0, nullptr, nullptr, w.data(), nullptr) == CG_OK);
    CHECK(near(foot()[1], 0.01f, 1e-5f));  // at rest (the hips bone's 0.01 tail)
    CHECK(cg_instance_set_target(inst, 0, CR_TARGET_MODEL, goal, nullptr) == CG_OK);
    CHECK(cg_instance_evaluate(inst, 1.f / 60.f, nullptr, 0, nullptr, nullptr, w.data(), nullptr) == CG_OK);
    std::vector<float> f = foot();
    CHECK(near(f[0], goal[0], 1e-4f) && near(f[1], goal[1], 1e-4f) && near(f[2], goal[2], 1e-4f));
    const float knee_z = w[2 * 16 + 14];
    CHECK(knee_z > 0.5f);  // bent toward the pole joint in front
    // The curve weight is 0: the marker stays; the ik parameter at 0 releases the leg.
    CHECK(near(w[5 * 16 + 12], 5.f, 1e-6f));
    cg_instance_set_float(inst, cg_graph_param_find(g, "ik"), 0.f);
    eval(inst, 1.f / 60.f);
    CHECK(cg_instance_evaluate(inst, 0.f, nullptr, 0, nullptr, nullptr, w.data(), nullptr) == CG_OK);
    CHECK(near(foot()[1], 0.01f, 1e-5f));  // at rest (the hips bone's 0.01 tail)
    // Curve 1: the marker follows the foot (a static joint target).
    ck[0].value = 1.f;
    cm_clip_curve_set(follow, "follow", ck, 1);
    bind(inst, g, "pose", follow);
    cg_instance_set_float(inst, cg_graph_param_find(g, "ik"), 1.f);
    CHECK(cg_instance_evaluate(inst, 0.f, nullptr, 0, nullptr, nullptr, w.data(), nullptr) == CG_OK);
    CHECK(near(w[5 * 16 + 12], goal[0], 1e-4f) && near(w[5 * 16 + 13], goal[1], 1e-4f));
    CHECK(cg_instance_set_target(inst, 3, CR_TARGET_MODEL, goal, nullptr) == CG_ERR_ARG);
    cg_instance_destroy(inst);
    // Without the character the role-addressed modifiers and the collider are left out (the joint:/name ones stay).
    inst = cg_instance_create(g, rig, nullptr, &unresolved);
    CHECK(unresolved == 3);
    cg_instance_destroy(inst);
    cm_clip_destroy(follow);
    cg_graph_destroy(g);
    cc_char_destroy(c);
    cr_rig_destroy(rig);
}

}  // namespace

int main() {
    CHECK(cg_abi_version() == CG_ABI_VERSION);
    test_format();
    test_blend_spaces();
    test_state_machine();
    test_montages();
    test_layers();
    test_sync_root_motion_notifies_curves();
    test_determinism();
    test_modifiers();
    if (failures != 0) {
        std::fprintf(stderr, "anim_graph: %d check(s) failed\n", failures);
        return 1;
    }
    std::printf("anim_graph: all checks passed\n");
    return 0;
}
