// Cenda animation graph editing (OpenMason #164) conformance: the #157 locomotion test graph built from nothing
// with operations equals the file; history (one step per apply, merge keys, all-or-nothing); renames rewrite every
// reference; drafts and problems; shared nodes; layout; compile; debug weights. Plain asserts.

#include "cenda/anim_clip.h"
#include "cenda/anim_graph.h"
#include "cenda/anim_graph_edit.h"
#include "cenda/rig.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
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

int apply(cg_doc* d, const std::string& ops, const char* label = nullptr, const char* key = nullptr) {
    const int32_t rc = cg_doc_apply(d, ops.data(), static_cast<int64_t>(ops.size()), label, key);
    if (rc < 0) std::fprintf(stderr, "apply refused: %s\n", cg_last_error());
    return rc;
}

bool has(const char* json, const std::string& needle) { return std::string(json).find(needle) != std::string::npos; }

std::string read_file(const char* path) {
    std::ifstream in(path);
    std::stringstream ss;
    ss << in.rdbuf();
    return ss.str();
}

// The #157 locomotion graph, as an agent would build it: a handful of batches.
void build_locomotion(cg_doc* d) {
    CHECK(apply(d, R"([{"op":"graph","description":"Idle, walk/run blend space, jump, fall, land; a full-body montage slot on top."},
        {"op":"param","name":"speed","type":"float"},
        {"op":"param","name":"grounded","type":"bool","default":true},
        {"op":"param","name":"jump","type":"trigger"},
        {"op":"slot","name":"idle"},{"op":"slot","name":"walk"},{"op":"slot","name":"run"},
        {"op":"slot","name":"jump"},{"op":"slot","name":"fall"},{"op":"slot","name":"land"}])", "parameters and slots") == 10);
    CHECK(apply(d, R"([{"op":"node","id":"idle","type":"clip","slot":"idle"},
        {"op":"node","id":"walk","type":"clip","slot":"walk","sync":"feet"},
        {"op":"node","id":"run","type":"clip","slot":"run","sync":"feet"},
        {"op":"node","id":"locomotion","type":"blend1d","param":"speed"},
        {"op":"link","parent":"locomotion","child":"idle"},
        {"op":"link","parent":"locomotion","child":"walk"},
        {"op":"link","parent":"locomotion","child":"run"},
        {"op":"sample","node":"locomotion","index":1,"value":1.5},
        {"op":"sample","node":"locomotion","index":2,"value":5}])", "locomotion blend") == 9);
    CHECK(apply(d, R"([{"op":"node","id":"jumpClip","type":"clip","slot":"jump"},
        {"op":"node","id":"fallClip","type":"clip","slot":"fall"},
        {"op":"node","id":"landClip","type":"clip","slot":"land"},
        {"op":"node","id":"movement","type":"stateMachine"},
        {"op":"state","machine":"movement","name":"ground","node":"locomotion","entry":true},
        {"op":"state","machine":"movement","name":"jump","node":"jumpClip"},
        {"op":"state","machine":"movement","name":"fall","node":"fallClip"},
        {"op":"state","machine":"movement","name":"land","node":"landClip"},
        {"op":"transition","machine":"movement","from":"ground","to":"jump","conditions":[{"param":"jump"}],"priority":2,"blend":0.1},
        {"op":"transition","machine":"movement","from":"ground","to":"fall","conditions":[{"param":"grounded","op":"==","value":false}],"priority":1,"blend":0.2},
        {"op":"transition","machine":"movement","from":"jump","to":"fall","exitTime":0.9,"blend":0.15},
        {"op":"transition","machine":"movement","from":"fall","to":"land","conditions":[{"param":"grounded"}],"blend":0.05},
        {"op":"transition","machine":"movement","from":"land","to":"ground","exitTime":1,"blend":0.2,"mode":"crossfade"}])",
              "movement machine") == 13);
    CHECK(apply(d, R"([{"op":"node","id":"full","type":"slot","name":"fullBody","source":"movement"},
        {"op":"graph","root":"full"}])", "montage slot") == 2);
}

void test_build_from_scratch_equals_the_file() {
    const std::string file = read_file(CENDA_LOCOMOTION_GRAPH);
    CHECK(!file.empty());
    int32_t rc = 0;
    cg_doc* ref = cg_doc_read(file.data(), static_cast<int64_t>(file.size()), &rc);
    CHECK(ref != nullptr);
    if (ref == nullptr) return;
    cg_doc* d = cg_doc_create("Humanoid locomotion", "humanoid");
    CHECK(cg_doc_valid(d) == 0);  // no nodes yet
    CHECK(has(cg_doc_problems(d), "the graph has no nodes"));
    build_locomotion(d);
    if (cg_doc_valid(d) == 0) std::fprintf(stderr, "problems: %s\n", cg_doc_problems(d));
    CHECK(cg_doc_valid(d) == 1);
    CHECK(std::string(cg_doc_json(d)) == cg_doc_json(ref));  // byte-identical canonical text
    if (std::string(cg_doc_json(d)) != cg_doc_json(ref)) std::fprintf(stderr, "built:\n%s\nfile:\n%s\n", cg_doc_json(d), cg_doc_json(ref));
    CHECK(cg_doc_step_count(d) == 4);
    CHECK(std::string(cg_doc_label(d, 0)) == "montage slot");
    cg_graph* g = cg_doc_compile(d);
    CHECK(g != nullptr);
    CHECK(cg_graph_node_count(g) == 9);
    cg_graph_destroy(g);
    cg_doc_destroy(ref);
    cg_doc_destroy(d);
}

void test_history_merge_and_atomicity() {
    cg_doc* d = cg_doc_create("g", nullptr);
    build_locomotion(d);
    const std::string built = cg_doc_json(d);
    CHECK(cg_doc_dirty(d) == 1);
    CHECK(cg_doc_mark_saved(d) == CG_OK);
    CHECK(cg_doc_dirty(d) == 0);
    // A drag: five moves with one key = one step.
    for (int i = 0; i < 5; ++i) {
        const std::string op = R"([{"op":"position","node":"idle","x":)" + std::to_string(10 * i) + R"(,"y":5}])";
        CHECK(apply(d, op, "move idle", "move:idle") == 1);
    }
    CHECK(cg_doc_step_count(d) == 5);
    CHECK(has(cg_doc_json(d), "\"editor\""));
    CHECK(has(cg_doc_view(d), "\"placed\":true"));
    cg_doc_seal(d);
    CHECK(apply(d, R"([{"op":"position","node":"idle","x":1,"y":1}])", "move idle", "move:idle") == 1);
    CHECK(cg_doc_step_count(d) == 6);
    // All or nothing: the second op fails, the first is not kept.
    const std::string before = cg_doc_json(d);
    const int64_t rev = cg_doc_revision(d);
    const std::string bad = R"([{"op":"param","name":"extra","type":"float"},{"op":"link","parent":"idle","child":"walk"}])";
    CHECK(cg_doc_apply(d, bad.data(), static_cast<int64_t>(bad.size()), nullptr, nullptr) == CG_ERR_FORMAT);
    CHECK(has(cg_last_error(), "ops[1] (link)"));
    CHECK(before == cg_doc_json(d));
    CHECK(rev == cg_doc_revision(d));
    // An apply that changes nothing records nothing.
    CHECK(apply(d, R"([{"op":"slot","name":"idle"}])") == 1);
    CHECK(cg_doc_step_count(d) == 6);
    // Undo / redo walk the states exactly.
    CHECK(cg_doc_undo(d) == CG_OK);
    CHECK(cg_doc_undo(d) == CG_OK);
    CHECK(built == cg_doc_json(d));
    CHECK(cg_doc_dirty(d) == 0);
    CHECK(cg_doc_redo(d) == CG_OK);
    CHECK(cg_doc_dirty(d) == 1);
    CHECK(has(cg_doc_json(d), "\"editor\""));
    while (cg_doc_undo(d) == CG_OK) {
    }
    CHECK(cg_doc_can_undo(d) == 0 && cg_doc_can_redo(d) == 1);
    cg_doc_destroy(d);
}

void test_renames_rewrite_references() {
    cg_doc* d = cg_doc_create("g", nullptr);
    build_locomotion(d);
    CHECK(apply(d, R"([{"op":"param","name":"speed","rename":"velocity"},
        {"op":"node","id":"locomotion","rename":"moveBlend"},
        {"op":"state","machine":"movement","name":"ground","rename":"grounded"},
        {"op":"slot","name":"walk","rename":"stroll"},
        {"op":"node","id":"movement","rename":"moves"}])", "renames") == 5);
    CHECK(cg_doc_valid(d) == 1);
    const std::string j = cg_doc_json(d);
    CHECK(j.find("\"speed\"") == std::string::npos);
    CHECK(j.find("\"param\": \"velocity\"") != std::string::npos);
    CHECK(j.find("\"node\": \"moveBlend\"") != std::string::npos);
    CHECK(j.find("\"entry\": \"grounded\"") != std::string::npos);
    CHECK(j.find("\"from\": \"grounded\"") != std::string::npos);
    CHECK(j.find("\"slot\": \"stroll\"") != std::string::npos);
    CHECK(j.find("\"source\": \"moves\"") != std::string::npos);
    CHECK(apply(d, R"([{"op":"param","name":"velocity","rename":"grounded"}])") == CG_ERR_FORMAT);  // taken
    // Removing a node unlinks it everywhere: the state loses its node (an error), the graph stays editable.
    CHECK(apply(d, R"([{"op":"node","id":"landClip","remove":true}])") == 1);
    CHECK(cg_doc_valid(d) == 0);
    CHECK(has(cg_doc_problems(d), "state:land"));
    CHECK(cg_doc_compile(d) == nullptr);
    CHECK(cg_doc_undo(d) == CG_OK);
    CHECK(cg_doc_valid(d) == 1);
    cg_doc_destroy(d);
}

void test_drafts_problems_and_sharing() {
    cg_doc* d = cg_doc_create("g", nullptr);
    CHECK(apply(d, R"([{"op":"param","name":"f","type":"float"},{"op":"param","name":"b","type":"bool"},{"op":"slot","name":"a"},
        {"op":"node","id":"blend","type":"blend1d"}])") == 4);
    CHECK(has(cg_doc_json(d), "\"root\": \"blend\""));  // the first node becomes the output
    const char* p = cg_doc_problems(d);
    CHECK(has(p, "no parameter chosen"));
    CHECK(has(p, "no samples"));
    // A draft survives a write / read round trip.
    int32_t rc = 0;
    const std::string text = cg_doc_json(d);
    cg_doc* again = cg_doc_read(text.data(), static_cast<int64_t>(text.size()), &rc);
    CHECK(again != nullptr && std::string(cg_doc_json(again)) == text);
    cg_doc_destroy(again);
    // Wrong parameter type, then right.
    CHECK(apply(d, R"([{"op":"node","id":"blend","param":"b"}])") == 1);
    CHECK(has(cg_doc_problems(d), "wrong type"));
    CHECK(apply(d, R"([{"op":"node","id":"blend","param":"f"},{"op":"node","id":"c","type":"clip","slot":"a"},
        {"op":"link","parent":"blend","child":"c"},{"op":"link","parent":"blend","child":"c"}])") == 4);
    if (cg_doc_valid(d) == 0) std::fprintf(stderr, "problems: %s\n", cg_doc_problems(d));
    CHECK(cg_doc_valid(d) == 1);  // c is shared by two samples: a DAG (#164)
    // An unused node is a warning, not an error; a cycle is an error.
    CHECK(apply(d, R"([{"op":"node","id":"spare","type":"clip","slot":"a"}])") == 1);
    CHECK(cg_doc_valid(d) == 1);
    CHECK(has(cg_doc_problems(d), "not connected to the output"));
    CHECK(apply(d, R"([{"op":"node","id":"s1","type":"slot","name":"m"},{"op":"node","id":"s2","type":"slot","name":"n"},
        {"op":"link","parent":"s1","child":"s2"},{"op":"link","parent":"s2","child":"s1"}])") == 4);
    CHECK(has(cg_doc_problems(d), "part of a cycle"));
    CHECK(cg_doc_valid(d) == 0);
    CHECK(apply(d, R"([{"op":"node","id":"s1","remove":true},{"op":"node","id":"s2","remove":true}])") == 2);
    CHECK(cg_doc_valid(d) == 1);
    // The view lists the inputs and the problems.
    const std::string v = cg_doc_view(d);
    CHECK(v.find("\"at\":\"sample:1\"") != std::string::npos);
    CHECK(v.find("\"child\":\"c\"") != std::string::npos);
    CHECK(v.find("\"problems\":[") != std::string::npos);
    cg_doc_destroy(d);
}

void test_layout() {
    cg_doc* d = cg_doc_create("g", nullptr);
    build_locomotion(d);
    CHECK(apply(d, R"([{"op":"layout"}])", "layout") == 1);
    const std::string v = cg_doc_view(d);
    CHECK(v.find("\"placed\":false") == std::string::npos);  // every node placed
    // The root sits right of everything it uses; the machine's states are spread out.
    CHECK(has(cg_doc_json(d), "\"full\": [\n        0.0,"));
    CHECK(has(cg_doc_json(d), "\"idle\": [\n        -840.0"));
    CHECK(has(cg_doc_json(d), "\"movement\": {"));
    // only missing: a moved node stays where it is.
    CHECK(apply(d, R"([{"op":"position","node":"idle","x":-999,"y":7}])") == 1);
    CHECK(apply(d, R"([{"op":"layout","onlyMissing":true}])") == 1);
    CHECK(has(cg_doc_json(d), "-999"));
    CHECK(apply(d, R"([{"op":"layout","onlyMissing":false}])") == 1);
    CHECK(!has(cg_doc_json(d), "-999"));
    cg_doc_destroy(d);
}

void test_debug_weights() {
    static const char* names[2] = {"hips", "spine"};
    cr_joint_desc j[2]{};
    j[1].parent = 0;
    j[0].parent = -1;
    for (auto& x : j) {
        x.kind = CR_JOINT_PART;
        x.flags = CR_JOINT_HAS_GEOMETRY;
        x.scale[0] = x.scale[1] = x.scale[2] = 1.f;
    }
    cr_rig* rig = cr_rig_create(j, 2, names, names);
    cg_doc* d = cg_doc_create("g", nullptr);
    build_locomotion(d);
    cg_graph* g = cg_doc_compile(d);
    cg_instance* inst = cg_instance_create(g, rig, nullptr, nullptr);
    cg_instance_set_float(inst, cg_graph_param_find(g, "speed"), 3.25f);  // halfway walk (1.5) -> run (5)
    CHECK(cg_instance_evaluate(inst, 1.f / 60.f, nullptr, 0, nullptr, nullptr, nullptr, nullptr) == CG_OK);
    float w[4] = {};
    const int blend = cg_graph_node_find(g, "locomotion");
    CHECK(cg_instance_debug_weights(inst, blend, nullptr, 0) == 3);
    CHECK(cg_instance_debug_weights(inst, blend, w, 4) == 3);
    CHECK(std::fabs(w[0]) < 1e-6f && std::fabs(w[1] - 0.5f) < 1e-5f && std::fabs(w[2] - 0.5f) < 1e-5f);
    const int machine = cg_graph_node_find(g, "movement");
    CHECK(cg_instance_debug_weights(inst, machine, w, 4) == 4);
    CHECK(w[0] == 1.f);
    CHECK(cg_instance_debug_weights(inst, cg_graph_node_find(g, "idle"), w, 4) == CG_ERR_ARG);
    cg_instance_destroy(inst);
    cg_graph_destroy(g);
    cg_doc_destroy(d);
    cr_rig_destroy(rig);
}

}  // namespace

int main() {
    test_build_from_scratch_equals_the_file();
    test_history_merge_and_atomicity();
    test_renames_rewrite_references();
    test_drafts_problems_and_sharing();
    test_layout();
    test_debug_weights();
    if (failures != 0) {
        std::fprintf(stderr, "anim_graph_edit: %d check(s) failed\n", failures);
        return 1;
    }
    std::printf("anim_graph_edit: all checks passed\n");
    return 0;
}
