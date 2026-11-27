// Cenda animation graph (cg_*, OpenMason #157): compile the .omanimgraph tree into a flat node table, then
// evaluate an instance per frame into one cr_pose_op program (rig.h). Contract and frame order in
// cenda/anim_graph.h. Only public cr_* / cm_* / cc_* calls are used for the rig, clips and characters.

#include "cenda/anim_graph.h"
#include "cenda/anim_graph_edit.h"

#include "../character/character_internal.hpp"
#include "omformat/omanimgraph.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <deque>
#include <limits>
#include <new>
#include <string>
#include <unordered_map>
#include <vector>

namespace og = cenda::omformat::omanimgraph;

namespace {

thread_local std::string g_error;

int32_t fail(int32_t code, const std::string& message) {
    g_error = message;
    return code;
}

template <typename T>
void set_out(T* out, T value) {
    if (out != nullptr) *out = value;
}

}  // namespace

namespace cenda::graph {
// cg_last_error for the graph editing ABI (anim_graph_edit.cpp).
void set_error(const std::string& message) { g_error = message; }
}  // namespace cenda::graph

namespace {

constexpr int kMaxFired = 64;
constexpr int kMaxNotifies = 256;
constexpr int kMaxConduitChain = 8;

// ---- compiled graph -----------------------------------------------------------------------------------

struct CCond {
    int param = -1;
    og::CondOp op = og::CondOp::True;
    double value = 0.0;
};

struct CTrans {
    int from = -1;  // -1 = any state
    int to = -1;
    std::vector<CCond> conds;
    float blend = 0.f;
    og::BlendMode mode = og::BlendMode::Inertialize;
    float exit_time = -1.f;
    bool can_interrupt = true;
};

struct CState {
    std::string name;
    int child = -1;  // index into the node's children; -1 = conduit
};

struct CLayer {
    og::LayerMode mode = og::LayerMode::Override;
    int mask = -1;
    float weight = 1.f;
    int weight_param = -1;
    bool passthrough = true;
    bool mesh_space = false;
    og::Reference reference = og::Reference::Rest;
    bool first_person = false;  // a "view": "firstPerson" layer (#161): only the first-person pose
};

struct CNode {
    std::string id;
    og::NodeType type = og::NodeType::Clip;
    int parent = -1;           // the first parent (a DAG node may have several, #164)
    std::vector<int> parents;  // every use, one entry per edge
    std::vector<int> children;
    std::vector<int> child_slots;  // the pose slot each child edge reads (consecutive per node; a shared child's
                                   // later edges get a COPY of its own slot)
    bool reachable = false;
    int out_slot = -1;
    int scratch = -1;
    int weights_at = -1;  // offset into the instance's weight buffer (blends, machines, layers)
    // clip
    int clip_slot = -1;
    float rate = 1.f;
    int rate_param = -1;
    int sync = -1;
    float start = 0.f;  // normalized (re)start position
    // blends
    int param = -1;
    int param_y = -1;
    og::Blend2DMode mode2d = og::Blend2DMode::Cartesian;
    std::vector<float> sx, sy;
    // state machine
    int entry = -1;
    std::vector<CState> states;
    std::vector<CTrans> transitions;  // highest priority first, then file order
    int machine = -1;
    // layer
    std::vector<CLayer> layers;
    // slot
    int montage_slot = -1;
    int mask = -1;
};

}  // namespace

struct cg_graph {
    og::Graph doc;
    std::vector<CNode> nodes;
    std::vector<int> order;  // reachable nodes, parents first (BFS)
    int root = 0;
    int slot_count = 0;
    int rest_slot = 0;
    int ref_slot = 0;
    int fp_slot = -1;  // the first-person pose (#161), when the root layer node has view layers
    int weight_floats = 0;
    int max_ops = 0;
    int sync_groups = 0;
    std::vector<int> machines;       // machine index -> node
    std::vector<int> montage_slots;  // montage slot index -> node
};

namespace {

// ---- instance state -------------------------------------------------------------------------------------

struct BoundClip {
    cm_clip* doc = nullptr;
    cr_clip* rig_clip = nullptr;
    float duration = 0.f;
    bool loop = false;
    int root_joint = -1;
    uint32_t root_axes = 0;
    int additive = CM_ADDITIVE_NONE;
    float additive_time = 0.f;
    bool additive_mesh = false;
    int additive_slot = -1;
    std::vector<int> curve_ids;
    // Runtime retargeting (#160): the clip is the SOURCE character's, sampled on the source rig.
    cc_retargeter* retarget = nullptr;
    cr_poses* src_poses = nullptr;
    std::vector<float> src_local, dst_local;
    std::vector<uint8_t> driven;
    float pin[3]{};            // the target hips' local position at the clip's first frame
    bool has_pin = false;
    // Foot-lock marker channels (#159): per channel, the clip's (time, 1 = down / 0 = up) marker events by time.
    std::vector<std::vector<std::pair<float, uint8_t>>> lock_events;

    void release() {
        if (src_poses != nullptr) cr_poses_destroy(src_poses);
        if (rig_clip != nullptr) cr_clip_destroy(rig_clip);
        if (doc != nullptr) cm_clip_destroy(doc);
        *this = BoundClip{};
    }
};

struct MontageClip {
    BoundClip clip;
    std::vector<std::string> section_names;
    std::vector<float> section_start;
};

struct NodeRt {
    float influence = 0.f;
    float root_influence = 0.f;  // the share of the influence that reaches it along root paths (root motion)
    uint32_t emitted = 0;        // frame stamp: a shared node's ops are emitted once per frame
    bool emitted_delta = false;
    bool active = false;
    bool was_active = false;
    bool root_path = false;
    float progress = 0.f;
};

struct PlayerRt {
    float time = 0.f;
    float prev = 0.f;
    float advanced = 0.f;
    double elapsed = 0.0;
};

struct MachineRt {
    int current = 0;
    int previous = -1;
    std::vector<float> w, snap;  // per child
    bool fading = false;
    float fade_time = 0.f, fade_elapsed = 0.f;
    bool can_interrupt = true;
    float state_time = 0.f;
    std::vector<int> path;  // travel: transition indices
    size_t path_at = 0;
    cr_inertia* inertia = nullptr;
};

enum MontagePhase { PHASE_IN = 0, PHASE_PLAY = 1, PHASE_OUT = 2 };

struct Segment {
    float from = 0.f, delta = 0.f;
};

// Locomotion modifiers (#159): the graph-side half (inputs fed each frame, play-rate warping).
struct OrientRt {
    int mod = -1;    // graph modifier
    int stack = -1;  // stack modifier
    int angle_param = -1;
};

struct StrideRt {
    int mod = -1, stack = -1;
    int speed_param = -1, direction_param = -1;
    std::vector<int> clips;  // clip nodes whose play rate it warps
    float rate = 1.f;        // last frame's play rate (weighted)
    float scale = 1.f;       // last frame's stride scale
};

struct FootRt {
    int mod = -1, stack = -1;
    int legs = 0;
    std::vector<std::string> curve;  // per leg (any number since rig ABI 3)
    std::vector<int> channel;        // per leg, -1 = none
};

struct SlotRt {
    int montage = -1;
    int section = 0;
    float time = 0.f, rate = 1.f, blend_out = 0.f, weight = 0.f;
    int phase = PHASE_PLAY;
    float fade_from = 0.f, fade_elapsed = 0.f, fade_len = 0.f;
    std::vector<int> next;  // per section, -1 = play on
    Segment seg[2];
    int seg_count = 0;
    cr_inertia* inertia = nullptr;
};

}  // namespace

struct cg_instance {
    const cg_graph* g = nullptr;
    const cr_rig* rig = nullptr;
    const cc_char* character = nullptr;
    cr_poses* poses = nullptr;
    std::vector<double> params;  // 2 per parameter
    std::vector<BoundClip> slots;
    std::vector<MontageClip> montages;
    std::vector<NodeRt> rt;
    uint32_t frame = 0;  // emit stamp (shared nodes emit once per frame)
    std::vector<PlayerRt> players;  // per node
    std::vector<MachineRt> machines;
    std::vector<SlotRt> mslots;
    std::vector<cr_mask*> masks;
    std::vector<float> weights;
    std::vector<cr_pose_op> ops;
    int root_motion_mode = CG_ROOT_MOTION_KEEP;
    float dt = 0.f;
    // outputs
    std::vector<cg_notify> notifies;
    std::vector<std::string> curve_names;
    std::vector<float> curve_values;
    std::vector<double> curve_acc;
    cr_root_motion root_motion{};
    // pose modifiers (#158)
    cr_modstack* mods = nullptr;
    std::vector<int> mod_index;          // graph modifier -> stack modifier (-1 = left out)
    std::vector<cr_target> host_targets; // by graph target
    std::vector<std::pair<int, int>> aliases;  // (stack slot, host target) copied each frame
    // locomotion (#159)
    std::vector<OrientRt> orients;
    std::vector<StrideRt> strides;
    std::vector<FootRt> feet;
    std::vector<float> rate_mul;  // per node: play-rate warp of the frame
    std::vector<std::pair<std::string, std::string>> lock_markers;  // per channel: (down, up)
    std::vector<double> lock_acc;
    float facing_x = 0.f, facing_z = -1.f;
    // morph drives (#171): per resolved drive, its channel and params; values = one NaN-filled row per drive
    struct MorphDriveRt {
        int channel = -1;
        int param = -1;
        int weight_param = -1;
        float weight = 1.f;
    };
    std::vector<MorphDriveRt> morph_drives;
    std::vector<float> morph_values;  // drives x morph channel count
    // scratch
    std::vector<cm_sync_member> sync_members;
    std::vector<int> sync_nodes;
    std::vector<cm_fired> fired;

    ~cg_instance() {
        for (BoundClip& b : slots) b.release();
        for (MontageClip& m : montages) m.clip.release();
        for (MachineRt& m : machines) {
            if (m.inertia != nullptr) cr_inertia_destroy(m.inertia);
        }
        for (SlotRt& s : mslots) {
            if (s.inertia != nullptr) cr_inertia_destroy(s.inertia);
        }
        for (cr_mask* m : masks) cr_mask_destroy(m);
        if (mods != nullptr) cr_modstack_destroy(mods);
        if (poses != nullptr) cr_poses_destroy(poses);
    }
};

namespace {

// ---- compile ------------------------------------------------------------------------------------------

void compile(cg_graph& cg) {
    const og::Graph& d = cg.doc;
    const size_t n = d.nodes.size();
    cg.nodes.resize(n);
    std::unordered_map<std::string, int> sync_ids;
    for (size_t i = 0; i < n; ++i) {
        const og::Node& src = d.nodes[i];
        CNode& c = cg.nodes[i];
        c.id = src.id;
        c.type = src.type;
        auto child = [&](const std::string& id) { c.children.push_back(d.find_node(id)); };
        switch (src.type) {
            case og::NodeType::Clip:
                c.clip_slot = d.find_slot(src.slot);
                c.rate = src.rate;
                c.rate_param = src.rate_param.empty() ? -1 : d.find_param(src.rate_param);
                c.start = src.start;
                if (!src.sync.empty()) {
                    auto [it, added] = sync_ids.emplace(src.sync, static_cast<int>(sync_ids.size()));
                    c.sync = it->second;
                }
                break;
            case og::NodeType::Blend1D:
            case og::NodeType::Blend2D:
                c.param = d.find_param(src.param);
                c.param_y = src.param_y.empty() ? -1 : d.find_param(src.param_y);
                c.mode2d = src.mode2d;
                for (const og::Sample& s : src.samples) {
                    child(s.node);
                    c.sx.push_back(s.x);
                    c.sy.push_back(s.y);
                }
                break;
            case og::NodeType::StateMachine: {
                std::unordered_map<std::string, int> state_index;
                for (const og::State& s : src.states) {
                    CState st;
                    st.name = s.name;
                    if (!s.conduit) {
                        st.child = static_cast<int>(c.children.size());
                        child(s.node);
                    }
                    state_index.emplace(s.name, static_cast<int>(c.states.size()));
                    c.states.push_back(std::move(st));
                }
                c.entry = state_index.at(src.entry);
                std::vector<std::pair<int, CTrans>> ordered;
                for (const og::Transition& t : src.transitions) {
                    CTrans ct;
                    ct.from = t.from == "*" ? -1 : state_index.at(t.from);
                    ct.to = state_index.at(t.to);
                    for (const og::Condition& cond : t.conditions) ct.conds.push_back({d.find_param(cond.param), cond.op, cond.value});
                    ct.blend = t.blend;
                    ct.mode = t.mode;
                    ct.exit_time = t.exit_time;
                    ct.can_interrupt = t.can_interrupt;
                    ordered.emplace_back(t.priority, std::move(ct));
                }
                std::stable_sort(ordered.begin(), ordered.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
                for (auto& [p, t] : ordered) c.transitions.push_back(std::move(t));
                break;
            }
            case og::NodeType::Layer:
                child(src.base);
                for (const og::LayerEntry& l : src.layers) {
                    child(l.node);
                    CLayer cl;
                    cl.mode = l.mode;
                    cl.mask = l.mask.empty() ? -1 : d.find_mask(l.mask);
                    cl.weight = l.weight;
                    cl.weight_param = l.weight_param.empty() ? -1 : d.find_param(l.weight_param);
                    cl.passthrough = l.passthrough;
                    cl.mesh_space = l.mesh_space;
                    cl.reference = l.reference;
                    cl.first_person = l.first_person;
                    c.layers.push_back(cl);
                }
                break;
            case og::NodeType::Slot:
                child(src.source);
                c.mask = src.mask.empty() ? -1 : d.find_mask(src.mask);
                break;
        }
        for (int ch : c.children) {
            CNode& k = cg.nodes[static_cast<size_t>(ch)];
            if (k.parent < 0) k.parent = static_cast<int>(i);
            k.parents.push_back(static_cast<int>(i));
        }
    }
    // Blend-space clip samples without their own group are synced as the blend's own group.
    for (size_t i = 0; i < n; ++i) {
        CNode& c = cg.nodes[i];
        if (c.type != og::NodeType::Blend1D && c.type != og::NodeType::Blend2D) continue;
        int group = -1;
        for (int ch : c.children) {
            CNode& k = cg.nodes[static_cast<size_t>(ch)];
            if (k.type != og::NodeType::Clip || k.sync >= 0) continue;
            if (group < 0) {
                group = static_cast<int>(sync_ids.size());
                sync_ids.emplace("#" + c.id, group);
            }
            k.sync = group;
        }
    }
    cg.sync_groups = static_cast<int>(sync_ids.size());

    // Parents first: Kahn's topological order over the nodes reachable from the root, FIFO (for a tree this is
    // the BFS order of 1.0). The children of each node get consecutive pose slots (one per edge); a node's own slot
    // is its first edge's, later edges of a shared node COPY it.
    cg.root = d.find_node(d.root);
    {
        std::vector<int> stack{cg.root};
        cg.nodes[static_cast<size_t>(cg.root)].reachable = true;
        while (!stack.empty()) {
            const int i = stack.back();
            stack.pop_back();
            for (int ch : cg.nodes[static_cast<size_t>(i)].children) {
                CNode& k = cg.nodes[static_cast<size_t>(ch)];
                if (!k.reachable) {
                    k.reachable = true;
                    stack.push_back(ch);
                }
            }
        }
    }
    std::vector<int> indegree(n, 0);
    for (size_t i = 0; i < n; ++i) {
        if (!cg.nodes[i].reachable) continue;
        for (int ch : cg.nodes[i].children) ++indegree[static_cast<size_t>(ch)];
    }
    std::deque<int> queue{cg.root};
    int next_slot = 1;
    int extra_edges = 0;
    cg.nodes[static_cast<size_t>(cg.root)].out_slot = 0;
    while (!queue.empty()) {
        const int i = queue.front();
        queue.pop_front();
        CNode& c = cg.nodes[static_cast<size_t>(i)];
        cg.order.push_back(i);
        for (int ch : c.children) {
            CNode& k = cg.nodes[static_cast<size_t>(ch)];
            const int slot = next_slot++;
            c.child_slots.push_back(slot);
            if (k.out_slot < 0) {
                k.out_slot = slot;
            } else {
                ++extra_edges;
            }
            if (--indegree[static_cast<size_t>(ch)] == 0) queue.push_back(ch);
        }
    }
    int ops = 5 + extra_edges;  // + the modifier stack; one COPY per extra edge of a shared node
    for (int i : cg.order) {
        CNode& c = cg.nodes[static_cast<size_t>(i)];
        switch (c.type) {
            case og::NodeType::Clip:
                c.scratch = next_slot++;
                ops += 4;
                break;
            case og::NodeType::Blend1D:
            case og::NodeType::Blend2D:
                c.weights_at = cg.weight_floats;
                cg.weight_floats += static_cast<int>(c.children.size());
                ops += 1;
                break;
            case og::NodeType::StateMachine:
                c.weights_at = cg.weight_floats;
                cg.weight_floats += static_cast<int>(c.children.size());
                c.machine = static_cast<int>(cg.machines.size());
                cg.machines.push_back(i);
                ops += 2;
                break;
            case og::NodeType::Layer:
                c.scratch = next_slot++;
                c.weights_at = cg.weight_floats;
                cg.weight_floats += static_cast<int>(c.layers.size());
                ops += 1 + 2 * static_cast<int>(c.layers.size());
                break;
            case og::NodeType::Slot:
                c.scratch = next_slot++;
                c.montage_slot = static_cast<int>(cg.montage_slots.size());
                cg.montage_slots.push_back(i);
                ops += 5;
                break;
        }
    }
    {
        const CNode& r = cg.nodes[static_cast<size_t>(cg.root)];
        for (const CLayer& l : r.layers) {
            if (l.first_person) {
                cg.fp_slot = next_slot++;
                ops += 1;  // the copy of the body pose it starts from
                break;
            }
        }
    }
    cg.rest_slot = next_slot++;
    cg.ref_slot = next_slot++;
    cg.slot_count = next_slot;
    cg.max_ops = ops;
    if (cg.slot_count > 1024) throw cenda::omformat::format_error(".omanimgraph: graph too large (pose slots > 1024)");
}

// ---- evaluation helpers ---------------------------------------------------------------------------------

float clip_time(float t, float duration, bool loop) {
    return cr_wrap_time(t, duration, loop ? 1 : 0);
}

// Forward distance from a to b on a clip (looping: wrapped into [0, d)).
float forward_delta(float a, float b, float d, bool loop) {
    float delta = b - a;
    if (loop && d > 0.f && delta < 0.f) delta += d;
    return delta;
}

double param_value(const cg_instance& inst, int p, int c = 0) { return inst.params[static_cast<size_t>(p) * 2 + static_cast<size_t>(c)]; }

bool holds(const cg_instance& inst, const CCond& c) {
    const double v = param_value(inst, c.param);
    switch (c.op) {
        case og::CondOp::Eq: return v == c.value;
        case og::CondOp::Ne: return v != c.value;
        case og::CondOp::Gt: return v > c.value;
        case og::CondOp::Lt: return v < c.value;
        case og::CondOp::Ge: return v >= c.value;
        case og::CondOp::Le: return v <= c.value;
        default: return v != 0.0;
    }
}

bool conditions_hold(const cg_instance& inst, const CTrans& t) {
    for (const CCond& c : t.conds) {
        if (!holds(inst, c)) return false;
    }
    return true;
}

void consume_triggers(cg_instance& inst, const CTrans& t) {
    for (const CCond& c : t.conds) {
        if (inst.g->doc.params[static_cast<size_t>(c.param)].type == og::ParamType::Trigger) {
            inst.params[static_cast<size_t>(c.param) * 2] = 0.0;
        }
    }
}

// Blend-space sample weights (sum 1) into w[0..count).
void blend1d_weights(const CNode& c, double v, float* w) {
    const size_t n = c.sx.size();
    std::fill(w, w + n, 0.f);
    if (n == 1 || v <= c.sx[0]) {
        w[0] = 1.f;
        return;
    }
    if (v >= c.sx[n - 1]) {
        w[n - 1] = 1.f;
        return;
    }
    for (size_t k = 0; k + 1 < n; ++k) {
        const double a = c.sx[k], b = c.sx[k + 1];
        if (v >= a && v <= b) {
            if (b <= a) {
                w[k] = 1.f;
                return;
            }
            const float t = static_cast<float>((v - a) / (b - a));
            w[k] = 1.f - t;
            w[k + 1] = t;
            if (t == 0.f) w[k + 1] = 0.f;
            return;
        }
    }
    w[n - 1] = 1.f;
}

double signed_angle(double ax, double ay, double bx, double by) {
    if ((ax == 0.0 && ay == 0.0) || (bx == 0.0 && by == 0.0)) return 0.0;
    return std::atan2(ax * by - ay * bx, ax * bx + ay * by);
}

// Gradient band interpolation (Johansen 2009): cartesian, or polar ("freeform directional", angle scale 2).
void blend2d_weights(const CNode& c, double px, double py, float* w) {
    const size_t n = c.sx.size();
    if (n == 1) {
        w[0] = 1.f;
        return;
    }
    const bool polar = c.mode2d == og::Blend2DMode::Directional;
    constexpr double kAngle = 2.0;
    const double pm = std::hypot(px, py);
    double total = 0.0;
    for (size_t i = 0; i < n; ++i) {
        const double ix = c.sx[i], iy = c.sy[i];
        double h = 1.0;
        for (size_t j = 0; j < n && h > 0.0; ++j) {
            if (j == i) continue;
            const double jx = c.sx[j], jy = c.sy[j];
            double ax, ay, bx, by;  // a = p_ij, b = p_ip
            if (!polar) {
                ax = jx - ix;
                ay = jy - iy;
                bx = px - ix;
                by = py - iy;
            } else {
                const double im = std::hypot(ix, iy), jm = std::hypot(jx, jy);
                const double avg = (im + jm) * 0.5;
                const double scale = avg > 0.0 ? 1.0 / avg : 1.0;
                ax = (jm - im) * scale;
                ay = signed_angle(ix, iy, jx, jy) * kAngle;
                bx = (pm - im) * scale;
                by = signed_angle(ix, iy, px, py) * kAngle;
            }
            const double len2 = ax * ax + ay * ay;
            if (len2 <= 0.0) continue;  // coincident samples
            h = std::min(h, std::clamp(1.0 - (ax * bx + ay * by) / len2, 0.0, 1.0));
        }
        w[i] = static_cast<float>(h);
        total += h;
    }
    if (total <= 0.0) {
        // Outside every band (cannot happen with >= 2 distinct samples, but be safe): nearest sample.
        size_t best = 0;
        double bd = 1e300;
        for (size_t i = 0; i < n; ++i) {
            const double dd = std::hypot(px - c.sx[i], py - c.sy[i]);
            if (dd < bd) {
                bd = dd;
                best = i;
            }
        }
        std::fill(w, w + n, 0.f);
        w[best] = 1.f;
        return;
    }
    for (size_t i = 0; i < n; ++i) w[i] = static_cast<float>(w[i] / total);
}

void machine_reset(cg_instance& inst, const CNode& c) {
    MachineRt& m = inst.machines[static_cast<size_t>(c.machine)];
    m.current = c.entry;
    m.previous = -1;
    std::fill(m.w.begin(), m.w.end(), 0.f);
    m.w[static_cast<size_t>(c.states[static_cast<size_t>(c.entry)].child)] = 1.f;
    m.fading = false;
    m.can_interrupt = true;
    m.state_time = 0.f;
    m.path.clear();
    m.path_at = 0;
    if (m.inertia != nullptr) cr_inertia_reset(m.inertia);
}

float state_progress(const cg_instance& inst, const CNode& c, int state) {
    const int child = c.states[static_cast<size_t>(state)].child;
    if (child < 0) return 0.f;
    return inst.rt[static_cast<size_t>(c.children[static_cast<size_t>(child)])].progress;
}

// A transition into `to` that ends on a real state: follow conduits (their outgoing transitions must hold now).
// Appends the chain's transitions; false when it dead-ends.
bool resolve_chain(const cg_instance& inst, const CNode& c, int to, std::vector<int>& chain, int depth) {
    if (c.states[static_cast<size_t>(to)].child >= 0) return true;
    if (depth >= kMaxConduitChain) return false;
    for (size_t k = 0; k < c.transitions.size(); ++k) {
        const CTrans& t = c.transitions[k];
        if (t.from != to || t.to == to || !conditions_hold(inst, t)) continue;
        chain.push_back(static_cast<int>(k));
        if (resolve_chain(inst, c, t.to, chain, depth + 1)) return true;
        chain.pop_back();
    }
    return false;
}

void take(cg_instance& /*inst*/, const CNode& c, MachineRt& m, const CTrans& first, int target) {
    m.previous = m.current;
    m.current = target;
    m.state_time = 0.f;
    m.can_interrupt = first.can_interrupt;
    const size_t child = static_cast<size_t>(c.states[static_cast<size_t>(target)].child);
    if (first.mode == og::BlendMode::Crossfade && first.blend > 0.f) {
        m.snap = m.w;
        m.fading = true;
        m.fade_time = first.blend;
        m.fade_elapsed = 0.f;
    } else {
        std::fill(m.w.begin(), m.w.end(), 0.f);
        m.w[child] = 1.f;
        m.fading = false;
        cr_inertia_request(m.inertia, first.blend);
    }
}

void machine_step(cg_instance& inst, const CNode& c, bool fresh, float dt) {
    MachineRt& m = inst.machines[static_cast<size_t>(c.machine)];
    if (fresh) {
        machine_reset(inst, c);
    } else {
        m.state_time += dt;
        if (m.fading) {
            m.fade_elapsed += dt;
            const float f = m.fade_time > 0.f ? std::min(1.f, m.fade_elapsed / m.fade_time) : 1.f;
            const size_t cur = static_cast<size_t>(c.states[static_cast<size_t>(m.current)].child);
            for (size_t k = 0; k < m.w.size(); ++k) m.w[k] = m.snap[k] * (1.f - f);
            m.w[cur] += f;
            if (f >= 1.f) {
                std::fill(m.w.begin(), m.w.end(), 0.f);
                m.w[cur] = 1.f;
                m.fading = false;
            }
        }
    }
    // Travel: one hop per frame (conduits are passed through in the same frame), conditions ignored.
    if (m.path_at < m.path.size()) {
        const CTrans& first = c.transitions[static_cast<size_t>(m.path[m.path_at])];
        int target = first.to;
        ++m.path_at;
        while (c.states[static_cast<size_t>(target)].child < 0 && m.path_at < m.path.size()) {
            target = c.transitions[static_cast<size_t>(m.path[m.path_at++])].to;
        }
        if (m.path_at >= m.path.size()) {
            m.path.clear();
            m.path_at = 0;
        }
        if (c.states[static_cast<size_t>(target)].child >= 0) take(inst, c, m, first, target);
        return;
    }
    if (m.fading && !m.can_interrupt) return;
    thread_local std::vector<int> chain;
    for (size_t k = 0; k < c.transitions.size(); ++k) {
        const CTrans& t = c.transitions[k];
        if (t.from >= 0 && t.from != m.current) continue;
        if (t.from < 0 && t.to == m.current) continue;
        if (!conditions_hold(inst, t)) continue;
        if (t.exit_time >= 0.f && state_progress(inst, c, m.current) < t.exit_time) continue;
        chain.clear();
        if (!resolve_chain(inst, c, t.to, chain, 0)) continue;
        consume_triggers(inst, t);
        int target = t.to;
        for (int ci : chain) {
            consume_triggers(inst, c.transitions[static_cast<size_t>(ci)]);
            target = c.transitions[static_cast<size_t>(ci)].to;
        }
        take(inst, c, m, t, target);
        return;
    }
}

// ---- montages ---------------------------------------------------------------------------------------

void montage_stop_now(SlotRt& s) {
    s.montage = -1;
    s.weight = 0.f;
    s.phase = PHASE_PLAY;
}

void montage_begin_out(SlotRt& s, float len) {
    if (len <= 0.f) {
        montage_stop_now(s);
        return;
    }
    s.phase = PHASE_OUT;
    s.fade_from = s.weight;
    s.fade_elapsed = 0.f;
    s.fade_len = len;
}

int section_at(const MontageClip& mc, float t) {
    int s = 0;
    for (size_t i = 0; i < mc.section_start.size(); ++i) {
        if (mc.section_start[i] <= t) s = static_cast<int>(i);
    }
    return s;
}

float section_end(const MontageClip& mc, int s) {
    const size_t i = static_cast<size_t>(s) + 1;
    return i < mc.section_start.size() ? mc.section_start[i] : mc.clip.duration;
}

void montage_advance(cg_instance& inst, SlotRt& s, float dt) {
    s.seg_count = 0;
    if (s.montage < 0) return;
    const MontageClip& mc = inst.montages[static_cast<size_t>(s.montage)];
    if (s.phase == PHASE_IN) {
        s.fade_elapsed += dt;
        s.weight = s.fade_len > 0.f ? std::min(1.f, s.fade_elapsed / s.fade_len) : 1.f;
        if (s.weight >= 1.f) s.phase = PHASE_PLAY;
    } else if (s.phase == PHASE_OUT) {
        s.fade_elapsed += dt;
        const float f = s.fade_len > 0.f ? std::min(1.f, s.fade_elapsed / s.fade_len) : 1.f;
        s.weight = s.fade_from * (1.f - f);
        if (f >= 1.f) {
            montage_stop_now(s);
            return;
        }
    }
    const float d = mc.clip.duration;
    const float prev = s.time;
    float t = prev + s.rate * dt;
    const float end = section_end(mc, s.section);
    const int link = s.next[static_cast<size_t>(s.section)];
    if (link >= 0 && t >= end && s.rate > 0.f) {
        s.seg[s.seg_count++] = {prev, end - prev};
        const float start = mc.section_start[static_cast<size_t>(link)];
        t = start + (t - end);
        s.seg[s.seg_count++] = {start, t - start};
        s.section = link;
    } else {
        t = std::clamp(t, 0.f, d);
        s.seg[s.seg_count++] = {prev, t - prev};
        s.section = section_at(mc, t);
    }
    s.time = t;
    if (s.phase != PHASE_OUT) {
        const bool linked = s.next[static_cast<size_t>(s.section)] >= 0;
        if (!linked && (t >= d || t >= d - s.blend_out)) montage_begin_out(s, t >= d ? 0.f : s.blend_out);
    }
}

// ---- pose program ---------------------------------------------------------------------------------------

cr_pose_op& push(cg_instance& inst, int32_t op, int32_t dst) {
    inst.ops.push_back(cr_pose_op{});
    cr_pose_op& o = inst.ops.back();
    o.op = op;
    o.dst = dst;
    return o;
}

bool emit(cg_instance& inst, int node);
// Emits child edge k of `c` (once per frame for a shared child) and copies it into the edge's slot when that is not
// the child's own; returns whether the child's pose is a delta.
bool use_child(cg_instance& inst, const CNode& c, size_t k);

int32_t retarget_into(cg_instance& inst, BoundClip& b, float time, int32_t slot);

bool emit_clip(cg_instance& inst, const CNode& c, int node) {
    const BoundClip& b = inst.slots[static_cast<size_t>(c.clip_slot)];
    if (b.rig_clip == nullptr) {
        push(inst, CR_OP_REST, c.out_slot);
        return false;
    }
    const PlayerRt& p = inst.players[static_cast<size_t>(node)];
    if (b.retarget != nullptr) {
        if (retarget_into(inst, inst.slots[static_cast<size_t>(c.clip_slot)], p.time, c.out_slot) != CG_OK) {
            push(inst, CR_OP_REST, c.out_slot);
        }
        return false;
    }
    cr_pose_op& s = push(inst, CR_OP_SAMPLE, c.out_slot);
    s.object = b.rig_clip;
    s.time = p.time;
    if (b.additive != CM_ADDITIVE_NONE) {
        if (b.additive == CM_ADDITIVE_FRAME) {
            cr_pose_op& r = push(inst, CR_OP_SAMPLE, c.scratch);
            r.object = b.rig_clip;
            r.time = b.additive_time;
        } else if (b.additive == CM_ADDITIVE_CLIP && b.additive_slot >= 0
                   && inst.slots[static_cast<size_t>(b.additive_slot)].rig_clip != nullptr) {
            cr_pose_op& r = push(inst, CR_OP_SAMPLE, c.scratch);
            r.object = inst.slots[static_cast<size_t>(b.additive_slot)].rig_clip;
            r.time = b.additive_time;
        } else {
            cr_pose_op& r = push(inst, CR_OP_COPY, c.scratch);
            r.a = b.additive == CM_ADDITIVE_CHARACTER ? inst.g->ref_slot : inst.g->rest_slot;
        }
        cr_pose_op& mk = push(inst, CR_OP_MAKE_ADDITIVE, c.out_slot);
        mk.a = c.out_slot;
        mk.b = c.scratch;
        mk.flags = b.additive_mesh ? static_cast<uint32_t>(CR_POSE_MESH_SPACE) : 0u;
        return true;
    }
    if (inst.root_motion_mode == CG_ROOT_MOTION_EXTRACT && b.root_joint >= 0 && b.root_axes != 0) {
        cr_pose_op& r = push(inst, CR_OP_REMOVE_ROOT, c.out_slot);
        r.object = b.rig_clip;
        r.b = b.root_joint;
        r.axes = b.root_axes;
    }
    return false;
}

// Layer k of a layer node onto dst (its children emitted first). An inactive layer emits nothing.
void emit_layer(cg_instance& inst, const CNode& c, size_t k, int32_t dst) {
    const int ch = c.children[k + 1];
    if (!inst.rt[static_cast<size_t>(ch)].active) return;
    const CLayer& l = c.layers[k];
    const bool child_delta = use_child(inst, c, k + 1);
    int src = c.child_slots[k + 1];
    const float w = inst.weights[static_cast<size_t>(c.weights_at) + k];
    cr_mask* mask = l.mask >= 0 ? inst.masks[static_cast<size_t>(l.mask)] : nullptr;
    if (l.mode == og::LayerMode::Additive) {
        if (!child_delta) {
            cr_pose_op& mk = push(inst, CR_OP_MAKE_ADDITIVE, c.scratch);
            mk.a = src;
            mk.b = l.reference == og::Reference::Character ? inst.g->ref_slot : inst.g->rest_slot;
            mk.flags = l.mesh_space ? static_cast<uint32_t>(CR_POSE_MESH_SPACE) : 0u;
            src = c.scratch;
        }
        cr_pose_op& ly = push(inst, CR_OP_LAYER, dst);
        ly.a = src;
        ly.object = mask;
        ly.weight = w;
        ly.mode = CR_BLEND_ADDITIVE;
        ly.flags = l.mesh_space ? static_cast<uint32_t>(CR_POSE_MESH_SPACE) : 0u;
    } else {
        cr_pose_op& ly = push(inst, CR_OP_LAYER, dst);
        ly.a = src;
        ly.object = mask;
        ly.weight = w;
        ly.mode = CR_BLEND_OVERRIDE;
        ly.flags = l.passthrough ? 0u : static_cast<uint32_t>(CR_POSE_NO_PASSTHROUGH);
    }
}

// The first-person pose (#161): the body pose (after the modifiers) + the root layer node's view layers.
void emit_first_person(cg_instance& inst) {
    const cg_graph& g = *inst.g;
    if (g.fp_slot < 0) return;
    const CNode& c = g.nodes[static_cast<size_t>(g.root)];
    cr_pose_op& cp = push(inst, CR_OP_COPY, g.fp_slot);
    cp.a = c.out_slot;
    for (size_t k = 0; k < c.layers.size(); ++k) {
        if (c.layers[k].first_person) emit_layer(inst, c, k, g.fp_slot);
    }
}

bool emit(cg_instance& inst, int node) {
    const CNode& c = inst.g->nodes[static_cast<size_t>(node)];
    switch (c.type) {
        case og::NodeType::Clip:
            return emit_clip(inst, c, node);
        case og::NodeType::Blend1D:
        case og::NodeType::Blend2D:
        case og::NodeType::StateMachine: {
            bool all_delta = true;
            for (size_t k = 0; k < c.children.size(); ++k) {
                if (inst.rt[static_cast<size_t>(c.children[k])].active) all_delta &= use_child(inst, c, k);
            }
            cr_pose_op& b = push(inst, CR_OP_BLEND, c.out_slot);
            b.a = c.child_slots[0];
            b.b = static_cast<int32_t>(c.children.size());
            b.object = inst.weights.data() + c.weights_at;
            if (c.type == og::NodeType::StateMachine) {
                cr_pose_op& in = push(inst, CR_OP_INERTIALIZE, c.out_slot);
                in.object = inst.machines[static_cast<size_t>(c.machine)].inertia;
                in.param = inst.dt;
            }
            return all_delta;
        }
        case og::NodeType::Layer: {
            const bool delta = use_child(inst, c, 0);
            cr_pose_op& cp = push(inst, CR_OP_COPY, c.out_slot);
            cp.a = c.child_slots[0];
            for (size_t k = 0; k < c.layers.size(); ++k) {
                if (!c.layers[k].first_person) emit_layer(inst, c, k, c.out_slot);
            }
            return delta;
        }
        case og::NodeType::Slot: {
            const bool delta = use_child(inst, c, 0);
            cr_pose_op& cp = push(inst, CR_OP_COPY, c.out_slot);
            cp.a = c.child_slots[0];
            SlotRt& s = inst.mslots[static_cast<size_t>(c.montage_slot)];
            if (s.montage >= 0 && s.weight > 0.f) {
                const BoundClip& b = inst.montages[static_cast<size_t>(s.montage)].clip;
                // A montage overrides its slot outright (Unreal's montage slot, Stonebreak #460): every joint is
                // driven, one the clip does not key at its rest pose - as the clip poses alone, never the source pose
                // underneath (a goblin's attack keys its parts only, and its bones' walk showed through). The blend
                // in/out still runs from / to the source; a partial montage takes a mask.
                cr_pose_op& sm = push(inst, CR_OP_SAMPLE, c.scratch);
                sm.object = b.rig_clip;
                sm.time = s.time;
                sm.flags = static_cast<uint32_t>(CR_POSE_DRIVE_ALL);
                if (inst.root_motion_mode == CG_ROOT_MOTION_EXTRACT && b.root_joint >= 0 && b.root_axes != 0) {
                    cr_pose_op& r = push(inst, CR_OP_REMOVE_ROOT, c.scratch);
                    r.object = b.rig_clip;
                    r.b = b.root_joint;
                    r.axes = b.root_axes;
                }
                cr_pose_op& ly = push(inst, CR_OP_LAYER, c.out_slot);
                ly.a = c.scratch;
                ly.object = c.mask >= 0 ? inst.masks[static_cast<size_t>(c.mask)] : nullptr;
                ly.weight = s.weight;
                ly.mode = CR_BLEND_OVERRIDE;
            }
            cr_pose_op& in = push(inst, CR_OP_INERTIALIZE, c.out_slot);
            in.object = s.inertia;
            in.param = inst.dt;
            return delta;
        }
    }
    return false;
}

bool use_child(cg_instance& inst, const CNode& c, size_t k) {
    const int ch = c.children[k];
    NodeRt& r = inst.rt[static_cast<size_t>(ch)];
    if (r.emitted != inst.frame) {
        r.emitted = inst.frame;
        r.emitted_delta = emit(inst, ch);
    }
    const int own = inst.g->nodes[static_cast<size_t>(ch)].out_slot;
    if (c.child_slots[k] != own) {
        cr_pose_op& cp = push(inst, CR_OP_COPY, c.child_slots[k]);
        cp.a = own;
    }
    return r.emitted_delta;
}

// ---- outputs ----------------------------------------------------------------------------------------

void add_motion(cr_root_motion& acc, const cr_root_motion& m, float w) {
    for (int c = 0; c < 3; ++c) acc.translation[c] += m.translation[c] * w;
    acc.yaw_deg += m.yaw_deg * w;
}

void fire(cg_instance& inst, const BoundClip& b, int source, int montage, float from, float delta, float weight) {
    if (delta == 0.f) return;
    const int32_t total = cm_clip_fire(b.doc, from, delta, inst.fired.data(), kMaxFired);
    const int32_t n = std::min<int32_t>(total, kMaxFired);
    for (int32_t i = 0; i < n && static_cast<int>(inst.notifies.size()) < kMaxNotifies; ++i) {
        inst.notifies.push_back(cg_notify{source, montage, inst.fired[static_cast<size_t>(i)].notify,
                                          inst.fired[static_cast<size_t>(i)].phase, inst.fired[static_cast<size_t>(i)].time,
                                          weight});
    }
}

void sample_curves(cg_instance& inst, const BoundClip& b, float time, float weight, double& total) {
    total += weight;
    for (size_t k = 0; k < b.curve_ids.size(); ++k) {
        inst.curve_acc[static_cast<size_t>(b.curve_ids[k])] +=
            static_cast<double>(weight) * cm_clip_curve_sample(b.doc, static_cast<int32_t>(k), time);
    }
}

double lock_event_value(const std::vector<std::pair<float, uint8_t>>& ev, float time, bool loop);

void sample_locks(cg_instance& inst, const BoundClip& b, float time, float weight) {
    for (size_t c = 0; c < b.lock_events.size() && c < inst.lock_acc.size(); ++c) {
        inst.lock_acc[c] += static_cast<double>(weight) * lock_event_value(b.lock_events[c], time, b.loop);
    }
}

// A modifier's influence this frame: weight x weightParam x weightCurve, clamped to [0, 1].
float modifier_weight(cg_instance& inst, const og::Modifier& md) {
    float w = md.weight;
    if (!md.weight_param.empty()) w *= static_cast<float>(param_value(inst, inst.g->doc.find_param(md.weight_param)));
    if (!md.weight_curve.empty()) {
        const int32_t cv = cg_instance_curve_find(&inst, md.weight_curve.c_str());
        w *= cv >= 0 ? inst.curve_values[static_cast<size_t>(cv)] : 0.f;
    }
    return std::clamp(w, 0.f, 1.f);
}

// The locomotion modifiers' per-frame inputs (#159): angle, stride scale + direction, foot locks.
void feed_locomotion(cg_instance& inst) {
    for (const OrientRt& r : inst.orients) {
        cr_modstack_set_value(inst.mods, r.stack, CR_VALUE_ANGLE, static_cast<float>(param_value(inst, r.angle_param)));
    }
    for (const StrideRt& r : inst.strides) {
        float dx = inst.facing_x, dz = inst.facing_z;
        if (r.direction_param >= 0) {
            const float x = static_cast<float>(param_value(inst, r.direction_param, 0));
            const float z = static_cast<float>(param_value(inst, r.direction_param, 1));
            if (x * x + z * z > 1e-12f) {
                dx = x;
                dz = z;
            }
        }
        cr_modstack_set_value(inst.mods, r.stack, CR_VALUE_SCALE, r.scale);
        cr_modstack_set_value(inst.mods, r.stack, CR_VALUE_DIR_X, dx);
        cr_modstack_set_value(inst.mods, r.stack, CR_VALUE_DIR_Z, dz);
    }
    for (const FootRt& r : inst.feet) {
        for (int k = 0; k < r.legs; ++k) {
            float lock = 0.f;
            const size_t kk = static_cast<size_t>(k);
            if (!r.curve[kk].empty()) {
                const int32_t cv = cg_instance_curve_find(&inst, r.curve[kk].c_str());
                lock = cv >= 0 ? inst.curve_values[static_cast<size_t>(cv)] : 0.f;
            } else if (r.channel[kk] >= 0) {
                lock = static_cast<float>(inst.lock_acc[static_cast<size_t>(r.channel[kk])]);
            }
            cr_modstack_set_value(inst.mods, r.stack, CR_VALUE_LEG(k, CR_LEG_LOCK), std::clamp(lock, 0.f, 1.f));
        }
    }
}

// ---- binding ------------------------------------------------------------------------------------------

int32_t bind_clip_to(cg_instance& inst, const cm_clip* clip, const cc_char* character, const cr_rig* rig, BoundClip& b,
                     int32_t* out_dropped);

int32_t bind_clip(cg_instance& inst, const cm_clip* clip, BoundClip& out, int32_t* out_dropped) {
    BoundClip b;
    const int32_t rc = bind_clip_to(inst, clip, inst.character, inst.rig, b, out_dropped);
    if (rc != CG_OK) return rc;
    out.release();
    out = std::move(b);
    return CG_OK;
}

// Binds `clip` for `character` / `rig` into b (b is released on failure).
int32_t bind_clip_to(cg_instance& inst, const cm_clip* clip, const cc_char* character, const cr_rig* rig, BoundClip& b,
                     int32_t* out_dropped) {
    set_out(out_dropped, int32_t{0});
    if (character != nullptr) {
        b.doc = cm_clip_bind_roles(clip, character, out_dropped);
    } else {
        b.doc = cm_clip_clone(clip);
    }
    if (b.doc == nullptr) return fail(CG_ERR_ARG, std::string("binding a clip: ") + cm_last_error());
    cm_header h{};
    cm_clip_header_get(b.doc, &h);
    b.duration = h.duration;
    b.loop = h.loop != 0;
    // The rig twin: joint-addressed tracks (role tracks were bound above or bind to nothing).
    std::vector<cr_track_desc> tracks;
    std::vector<cr_key> keys;
    std::vector<std::string> ids, names;
    const int32_t tc = cm_clip_track_count(b.doc);
    for (int32_t t = 0; t < tc; ++t) {
        const char* pid = nullptr;
        const char* pname = nullptr;
        const char* role = nullptr;
        int32_t bone = 0, kc = 0;
        cm_clip_track_get(b.doc, t, &pid, &pname, &role, &bone, &kc);
        if (role != nullptr && role[0] != '\0') continue;
        const size_t first = keys.size();
        keys.resize(first + static_cast<size_t>(kc));
        cm_clip_track_keys(b.doc, t, keys.data() + first, kc);
        tracks.push_back(cr_track_desc{static_cast<int32_t>(first), kc});
        ids.emplace_back(pid != nullptr ? pid : "");
        names.emplace_back(pname != nullptr ? pname : "");
    }
    std::vector<const char*> idp, namep;
    for (size_t i = 0; i < ids.size(); ++i) {
        idp.push_back(ids[i].c_str());
        namep.push_back(names[i].c_str());
    }
    cr_clip_desc desc{h.duration, h.loop, CR_LAYER_BASE, 0, 0.f, 0.f};
    b.rig_clip = cr_clip_create(&desc, tracks.data(), static_cast<int32_t>(tracks.size()), keys.data(),
                                static_cast<int32_t>(keys.size()), idp.data(), namep.data(), nullptr, 0);
    if (b.rig_clip == nullptr) {
        const std::string why = cr_last_error();
        b.release();
        return fail(CG_ERR_ARG, "binding a clip: " + why);
    }
    // Morph tracks (OMANIM 1.3, #171) ride along, bound to the rig's morph channels by name.
    if (cm_clip_apply_morphs(b.doc, b.rig_clip) != CM_OK) {
        const std::string why = cm_last_error();
        b.release();
        return fail(CG_ERR_ARG, "binding a clip: " + why);
    }
    const char* root = nullptr;
    uint32_t axes = 0;
    cm_clip_root_motion_get(b.doc, &root, &axes);
    if (root != nullptr && root[0] != '\0') {
        b.root_joint = cr_rig_find(rig, root);
        b.root_axes = axes;
    }
    int32_t reference = CM_ADDITIVE_NONE, mesh = 0;
    float rtime = 0.f;
    const char* ref_clip = nullptr;
    cm_clip_additive_get(b.doc, &reference, &rtime, &ref_clip, &mesh);
    b.additive = reference;
    b.additive_time = rtime;
    b.additive_mesh = mesh != 0;
    if (reference == CM_ADDITIVE_CLIP && ref_clip != nullptr) b.additive_slot = inst.g->doc.find_slot(ref_clip);
    for (int32_t k = 0, n = cm_clip_curve_count(b.doc); k < n; ++k) {
        const char* name = nullptr;
        int32_t kc = 0;
        cm_clip_curve_get(b.doc, k, &name, &kc);
        const std::string s = name != nullptr ? name : "";
        auto it = std::find(inst.curve_names.begin(), inst.curve_names.end(), s);
        int id;
        if (it == inst.curve_names.end()) {
            id = static_cast<int>(inst.curve_names.size());
            inst.curve_names.push_back(s);
            inst.curve_values.push_back(0.f);
            inst.curve_acc.push_back(0.0);
        } else {
            id = static_cast<int>(it - inst.curve_names.begin());
        }
        b.curve_ids.push_back(id);
    }
    b.lock_events.resize(inst.lock_markers.size());
    for (int32_t k = 0, n = cm_clip_marker_count(b.doc); k < n; ++k) {
        const char* name = nullptr;
        float t = 0.f;
        if (cm_clip_marker_get(b.doc, k, &name, &t) != CM_OK || name == nullptr) continue;
        for (size_t c = 0; c < inst.lock_markers.size(); ++c) {
            if (inst.lock_markers[c].first == name) b.lock_events[c].emplace_back(t, uint8_t{1});
            else if (inst.lock_markers[c].second == name) b.lock_events[c].emplace_back(t, uint8_t{0});
        }
    }
    for (auto& ev : b.lock_events) {
        std::stable_sort(ev.begin(), ev.end(), [](const auto& x, const auto& y) { return x.first < y.first; });
    }
    return CG_OK;
}

// A retargeted clip's pose at `time` into the target slot (#160).
int32_t retarget_into(cg_instance& inst, BoundClip& b, float time, int32_t slot) {
    cr_pose_op op{};
    op.op = CR_OP_SAMPLE;
    op.dst = 0;
    op.object = b.rig_clip;
    op.time = time;
    if (cr_poses_run(b.src_poses, &op, 1) != CR_OK || cr_poses_get(b.src_poses, 0, b.src_local.data(), nullptr) != CR_OK) {
        return fail(CG_ERR_ARG, std::string("retargeting: ") + cr_last_error());
    }
    if (cc_retarget_pose(b.retarget, b.src_local.data(), b.dst_local.data(), b.driven.data()) != CC_OK) {
        return fail(CG_ERR_ARG, std::string("retargeting: ") + cc_last_error());
    }
    if (b.has_pin && inst.root_motion_mode == CG_ROOT_MOTION_EXTRACT && inst.character != nullptr) {
        const char* hid = cc_char_role_joint(inst.character, "hips");
        const int h = hid == nullptr ? -1 : cr_rig_find(inst.rig, hid);
        if (h >= 0) {
            const uint32_t axes[3] = {CM_ROOT_X, CM_ROOT_Y, CM_ROOT_Z};
            for (int c = 0; c < 3; ++c) {
                if ((b.root_axes & axes[c]) != 0) b.dst_local[static_cast<size_t>(h) * 9 + static_cast<size_t>(c)] = b.pin[c];
            }
        }
    }
    cr_poses_set(inst.poses, slot, b.dst_local.data(), b.driven.data());
    // Morph tracks pass through by name (#171): the clip's morph tracks onto THIS rig's channels.
    if (cr_rig_morph_count(inst.rig) > 0) {
        cr_pose_op mop{};
        mop.op = CR_OP_SAMPLE;
        mop.dst = slot;
        mop.object = b.rig_clip;
        mop.time = time;
        mop.flags = CR_POSE_MORPHS_ONLY;
        cr_poses_run(inst.poses, &mop, 1);
    }
    return CG_OK;
}

// ---- pose modifiers --------------------------------------------------------------------------------------

int resolve_joint(const cg_instance& inst, const std::string& ref) {
    if (ref.rfind("role:", 0) == 0) {
        if (inst.character == nullptr) return -1;
        const char* j = cc_char_role_joint(inst.character, ref.c_str() + 5);
        return j == nullptr ? -1 : cr_rig_find(inst.rig, j);
    }
    return cr_rig_find(inst.rig, ref.c_str());
}

// A target reference -> a stack slot (-1 = none); static joint / socket targets get their own slots.
bool resolve_target(const cg_instance& inst, const std::string& ref, std::vector<cr_target>& statics, int& slot) {
    slot = -1;
    if (ref.empty()) return true;
    const int hosts = static_cast<int>(inst.g->doc.targets.size());
    cr_target t{};
    if (ref.rfind("socket:", 0) == 0) {
        if (inst.character == nullptr) return false;
        const std::string want = ref.substr(7);
        for (int32_t i = 0, n = cc_char_socket_count(inst.character); i < n; ++i) {
            const char* name = nullptr;
            const char* joint = nullptr;
            float pos[3], rot[3], scale[3];
            if (cc_char_socket_get(inst.character, i, &name, &joint, pos, rot, scale) != CC_OK || want != name) continue;
            const int j = cr_rig_find(inst.rig, joint);
            if (j < 0 || cr_target_from_socket(inst.rig, j, pos, rot, &t) != CR_OK) return false;
            statics.push_back(t);
            slot = hosts + static_cast<int>(statics.size()) - 1;
            return true;
        }
        return false;
    }
    if (ref.rfind("role:", 0) == 0 || ref.rfind("joint:", 0) == 0) {
        const int j = resolve_joint(inst, ref.rfind("role:", 0) == 0 ? ref : ref.substr(6));
        if (j < 0) return false;
        t.kind = CR_TARGET_JOINT;
        t.joint = j;
        statics.push_back(t);
        slot = hosts + static_cast<int>(statics.size()) - 1;
        return true;
    }
    slot = inst.g->doc.find_target(ref);
    return slot >= 0;
}

// The clip nodes under `node` (itself included; every path, the graph is a DAG since #164).
std::vector<int> clips_under(const cg_graph& g, int node) {
    std::vector<int> out;
    std::vector<uint8_t> seen(g.nodes.size(), 0);
    std::vector<int> stack{node};
    while (!stack.empty()) {
        const int i = stack.back();
        stack.pop_back();
        if (seen[static_cast<size_t>(i)] != 0) continue;
        seen[static_cast<size_t>(i)] = 1;
        const CNode& c = g.nodes[static_cast<size_t>(i)];
        if (c.type == og::NodeType::Clip) out.push_back(i);
        for (int ch : c.children) stack.push_back(ch);
    }
    std::sort(out.begin(), out.end());
    return out;
}

// The graph side of the locomotion modifiers (#159), after the stack exists: tuning values, per-frame inputs.
void build_locomotion(cg_instance& inst) {
    const og::Graph& d = inst.g->doc;
    if (inst.character != nullptr) {
        const std::string f = cc_char_get_string(inst.character, CC_FIELD_FACING);
        if (f == "+z") inst.facing_z = 1.f;
        if (f == "-x" || f == "+x") {
            inst.facing_z = 0.f;
            inst.facing_x = f == "-x" ? -1.f : 1.f;
        }
    }
    for (size_t i = 0; i < d.modifiers.size(); ++i) {
        const int si = inst.mod_index[i];
        if (si < 0) continue;
        const og::Modifier& m = d.modifiers[i];
        auto set = [&](int key, float value) { cr_modstack_set_value(inst.mods, si, key, value); };
        switch (m.type) {
            case og::ModType::OrientationWarp: {
                set(CR_VALUE_MAX_ANGLE, m.max_angle);
                set(CR_VALUE_RATE, m.rate);
                set(CR_VALUE_COUNTER, m.counter);
                inst.orients.push_back(OrientRt{static_cast<int>(i), si, d.find_param(m.angle_param)});
                break;
            }
            case og::ModType::StrideWarp: {
                set(CR_VALUE_PELVIS, m.pelvis_weight);
                StrideRt r;
                r.mod = static_cast<int>(i);
                r.stack = si;
                r.speed_param = d.find_param(m.speed_param);
                r.direction_param = m.direction_param.empty() ? -1 : d.find_param(m.direction_param);
                const int node = m.node.empty() ? inst.g->root : d.find_node(m.node);
                r.clips = clips_under(*inst.g, node);
                inst.strides.push_back(std::move(r));
                break;
            }
            case og::ModType::FootPlacement: {
                set(CR_VALUE_PROBE_UP, m.probe_up);
                set(CR_VALUE_PROBE_DOWN, m.probe_down);
                set(CR_VALUE_MAX_DROP, m.max_drop);
                set(CR_VALUE_MAX_RISE, m.max_rise);
                set(CR_VALUE_MAX_ALIGN, m.max_align);
                set(CR_VALUE_PELVIS_RATE, m.pelvis_rate);
                set(CR_VALUE_FOOT_RATE, m.foot_rate);
                set(CR_VALUE_GROUND_Y, m.ground_height);
                set(CR_VALUE_UNLOCK_TIME, m.unlock_time);
                set(CR_VALUE_MAX_LOCK_DISTANCE, m.max_lock_distance);
                FootRt r;
                r.mod = static_cast<int>(i);
                r.stack = si;
                r.legs = static_cast<int>(m.legs.size());
                r.curve.assign(m.legs.size(), std::string());
                r.channel.assign(m.legs.size(), -1);
                for (int k = 0; k < r.legs; ++k) {
                    const og::Leg& l = m.legs[static_cast<size_t>(k)];
                    r.curve[static_cast<size_t>(k)] = l.lock_curve;
                    if (!l.lock_down.empty()) {
                        r.channel[static_cast<size_t>(k)] = static_cast<int>(inst.lock_markers.size());
                        inst.lock_markers.emplace_back(l.lock_down, l.lock_up);
                    }
                }
                inst.feet.push_back(std::move(r));
                break;
            }
            default:
                break;
        }
    }
    inst.lock_acc.assign(inst.lock_markers.size(), 0.0);
}

// A clip's foot-lock channel at a time: 1 when the last marker event at or before it is a `down`.
double lock_event_value(const std::vector<std::pair<float, uint8_t>>& ev, float time, bool loop) {
    if (ev.empty()) return 0.0;
    int last = -1;
    for (size_t k = 0; k < ev.size(); ++k) {
        if (ev[k].first <= time) last = static_cast<int>(k);
    }
    if (last < 0) {
        if (!loop) return 0.0;
        last = static_cast<int>(ev.size()) - 1;  // the previous cycle's last event
    }
    return ev[static_cast<size_t>(last)].second != 0 ? 1.0 : 0.0;
}

// Builds the instance's modifier stack; returns the number of colliders / modifiers left out.
int32_t build_modifiers(cg_instance& inst) {
    const og::Graph& d = inst.g->doc;
    inst.host_targets.assign(d.targets.size(), cr_target{});
    inst.mod_index.assign(d.modifiers.size(), -1);
    if (d.modifiers.empty()) return 0;
    int32_t unresolved = 0;
    std::vector<cr_modifier_desc> descs;
    // Each desc's joint list (rig ABI 3: pointer + count, any length); cr_modstack_create copies them.
    std::vector<std::vector<int32_t>> desc_joints;
    std::vector<std::vector<float>> desc_weights;
    std::vector<cr_target> statics;
    std::vector<std::pair<int, int>> aliases;  // (static index, host target)
    for (size_t i = 0; i < d.modifiers.size(); ++i) {
        const og::Modifier& m = d.modifiers[i];
        cr_modifier_desc c{};
        c.target = c.pole = -1;
        c.weight = m.weight;
        std::vector<int32_t> js;
        std::vector<float> ws;
        bool ok = true;
        // Appends a joint with its weight (0 = none given).
        auto push = [&](int32_t j, float w) {
            js.push_back(j);
            ws.push_back(w);
        };
        auto joint = [&](const std::string& ref, float w = 0.f) {
            const int j = resolve_joint(inst, ref);
            ok &= j >= 0;
            push(j, w);
        };
        auto target = [&](const std::string& ref, int32_t& slot) {
            int s = -1;
            ok &= resolve_target(inst, ref, statics, s);
            slot = s;
        };
        switch (m.type) {
            case og::ModType::TwoBone:
                c.type = CR_MOD_TWO_BONE;
                joint(m.upper);
                joint(m.mid);
                joint(m.end);
                target(m.target, c.target);
                target(m.pole, c.pole);
                c.p[0] = m.soft;
                if (m.match_rotation) c.flags |= CR_MOD_MATCH_ROTATION;
                break;
            case og::ModType::Fabrik:
            case og::ModType::Ccd:
                c.type = m.type == og::ModType::Fabrik ? CR_MOD_FABRIK : CR_MOD_CCD;
                joint(m.start);
                joint(m.end);
                target(m.target, c.target);
                c.p[0] = static_cast<float>(m.iterations);
                c.p[1] = m.tolerance;
                break;
            case og::ModType::Aim:
                c.type = CR_MOD_AIM;
                for (const og::AimJoint& a : m.aim) joint(a.joint, a.weight);
                target(m.target, c.target);
                std::copy(m.axis, m.axis + 3, c.p);
                if (m.each) c.flags |= CR_MOD_AIM_EACH;
                if (m.limits) c.flags |= CR_MOD_LIMITS;
                break;
            case og::ModType::Constraint:
                c.type = CR_MOD_CONSTRAINT;
                c.kind = static_cast<int32_t>(m.kind);
                joint(m.joint);
                target(m.target, c.target);
                if (m.kind == og::ConstraintKind::Aim) {
                    std::copy(m.axis, m.axis + 3, c.p);
                } else {
                    std::copy(m.offset, m.offset + 3, c.p);
                    std::copy(m.offset_rot, m.offset_rot + 3, c.p + 3);
                }
                break;
            case og::ModType::Spring:
                c.type = CR_MOD_SPRING;
                joint(m.start);
                joint(m.end);
                c.p[0] = m.stiffness;
                c.p[1] = m.drag;
                std::copy(m.gravity, m.gravity + 3, c.p + 2);
                c.p[5] = m.radius;
                c.p[6] = m.step;
                break;
            case og::ModType::FullBody: {
                c.type = CR_MOD_FULL_BODY;
                joint(m.body, m.hip_weight);
                // One consecutive slot per limb: static targets go straight in, host targets are aliased.
                const size_t first = statics.size();
                for (const og::Limb& l : m.limbs) {
                    joint(l.upper, l.pull);
                    joint(l.mid, l.reach);
                    joint(l.end);
                    statics.push_back(cr_target{});
                }
                for (size_t k = 0; k < m.limbs.size(); ++k) {
                    const std::string& ref = m.limbs[k].target;
                    const int host = d.find_target(ref);
                    if (host >= 0) {
                        aliases.emplace_back(static_cast<int>(first + k), host);
                    } else {
                        std::vector<cr_target> one;
                        int s = -1;
                        ok &= resolve_target(inst, ref, one, s);
                        if (!one.empty()) statics[first + k] = one[0];
                    }
                }
                c.target = static_cast<int32_t>(d.targets.size() + first);
                target(m.pole, c.pole);
                c.p[0] = static_cast<float>(m.iterations);
                break;
            }
            case og::ModType::OrientationWarp:
                c.type = CR_MOD_ORIENT_WARP;
                joint(m.body);
                // Spine joints the character lacks are left out (counted), the rest share the counter-turn.
                for (const og::AimJoint& a : m.aim) {
                    const int j = resolve_joint(inst, a.joint);
                    if (j < 0) {
                        ++unresolved;
                        continue;
                    }
                    push(j, a.weight);
                }
                break;
            case og::ModType::StrideWarp:
            case og::ModType::FootPlacement:
                c.type = m.type == og::ModType::StrideWarp ? CR_MOD_STRIDE_WARP : CR_MOD_FOOT_PLACEMENT;
                joint(m.body);
                for (const og::Leg& l : m.legs) {
                    joint(l.upper);
                    joint(l.mid);
                    // No (or an unmapped) foot: the mid joint's tail is the ankle; an unmapped one is counted.
                    int e = -1;
                    if (!l.end.empty()) {
                        e = resolve_joint(inst, l.end);
                        if (e < 0) ++unresolved;
                    }
                    push(e, 0.f);
                }
                break;
        }
        if (!ok) {
            ++unresolved;
            continue;
        }
        inst.mod_index[i] = static_cast<int>(descs.size());
        c.joint_count = static_cast<int32_t>(js.size());
        descs.push_back(c);
        desc_joints.push_back(std::move(js));
        desc_weights.push_back(std::move(ws));
    }
    for (size_t k = 0; k < descs.size(); ++k) {
        descs[k].joints = desc_joints[k].data();
        descs[k].joint_weights = desc_weights[k].data();
    }
    const int32_t slots = static_cast<int32_t>(d.targets.size() + statics.size());
    inst.mods = cr_modstack_create(inst.rig, descs.data(), static_cast<int32_t>(descs.size()), slots);
    if (inst.mods == nullptr) return unresolved + static_cast<int32_t>(descs.size());
    for (size_t k = 0; k < statics.size(); ++k) {
        if (statics[k].kind != CR_TARGET_NONE) {
            cr_modstack_set_target(inst.mods, static_cast<int32_t>(d.targets.size() + k), &statics[k]);
        }
    }
    for (const auto& [st, host] : aliases) inst.aliases.emplace_back(static_cast<int>(d.targets.size()) + st, host);
    build_locomotion(inst);
    for (const og::Collider& col : d.colliders) {
        const int j = resolve_joint(inst, col.joint);
        if (j < 0) {
            ++unresolved;
            continue;
        }
        cr_modstack_add_collider(inst.mods, j, col.a, col.b, col.radius);
    }
    const int32_t ref_joints = cr_rig_joint_count(inst.rig);
    std::vector<float> ref(static_cast<size_t>(ref_joints) * 9);
    cr_poses_get(inst.poses, inst.g->ref_slot, ref.data(), nullptr);
    // Aim joints' own limits (Stonebreak #460), relative to the reference pose; the character's below win.
    for (const og::Modifier& m : inst.g->doc.modifiers) {
        if (m.type != og::ModType::Aim || !m.limits) continue;
        for (const og::AimJoint& a : m.aim) {
            if (!a.has_limit) continue;
            const int j = resolve_joint(inst, a.joint);
            if (j >= 0) {
                cr_modstack_set_limit(inst.mods, j, ref.data() + static_cast<size_t>(j) * 9 + 3, a.limit_min,
                                      a.limit_max);
                if (a.limit_yxz) cr_modstack_set_limit_order(inst.mods, j, CR_LIMIT_YXZ);
            }
        }
    }
    // Joint limits from the character, relative to its reference pose.
    if (inst.character != nullptr) {
        for (int32_t i = 0, n = cc_char_limit_count(inst.character); i < n; ++i) {
            const char* jid = nullptr;
            float mn[3], mx[3];
            if (cc_char_limit_get(inst.character, i, &jid, mn, mx) != CC_OK) continue;
            const int j = cr_rig_find(inst.rig, jid);
            if (j >= 0) cr_modstack_set_limit(inst.mods, j, ref.data() + static_cast<size_t>(j) * 9 + 3, mn, mx);
        }
    }
    return unresolved;
}

void reset_instance(cg_instance& inst) {
    const og::Graph& d = inst.g->doc;
    for (size_t p = 0; p < d.params.size(); ++p) {
        inst.params[p * 2] = d.params[p].value[0];
        inst.params[p * 2 + 1] = d.params[p].value[1];
    }
    for (NodeRt& r : inst.rt) r = NodeRt{};
    for (PlayerRt& p : inst.players) p = PlayerRt{};
    for (int i : inst.g->machines) machine_reset(inst, inst.g->nodes[static_cast<size_t>(i)]);
    for (SlotRt& s : inst.mslots) {
        montage_stop_now(s);
        s.seg_count = 0;
        cr_inertia_reset(s.inertia);
    }
    if (inst.mods != nullptr) cr_modstack_reset(inst.mods);
    inst.notifies.clear();
    std::fill(inst.curve_values.begin(), inst.curve_values.end(), 0.f);
    inst.root_motion = cr_root_motion{};
}

bool bad_param(const cg_instance* inst, int32_t p) {
    return inst == nullptr || p < 0 || static_cast<size_t>(p) >= inst->g->doc.params.size();
}

og::ParamType ptype(const cg_instance* inst, int32_t p) { return inst->g->doc.params[static_cast<size_t>(p)].type; }

const CNode* machine_node(const cg_graph* g, int32_t node) {
    if (g == nullptr || node < 0 || static_cast<size_t>(node) >= g->nodes.size()) return nullptr;
    const CNode& c = g->nodes[static_cast<size_t>(node)];
    return c.type == og::NodeType::StateMachine && c.machine >= 0 ? &c : nullptr;
}

SlotRt* montage_slot(cg_instance* inst, int32_t slot) {
    if (inst == nullptr || slot < 0 || static_cast<size_t>(slot) >= inst->mslots.size()) return nullptr;
    return &inst->mslots[static_cast<size_t>(slot)];
}

int find_section(const MontageClip& mc, const char* name) {
    if (name == nullptr) return -1;
    for (size_t i = 0; i < mc.section_names.size(); ++i) {
        if (mc.section_names[i] == name) return static_cast<int>(i);
    }
    return -1;
}

}  // namespace

extern "C" {

int32_t cg_abi_version(void) { return CG_ABI_VERSION; }

const char* cg_last_error(void) { return g_error.c_str(); }

// ---- graph ------------------------------------------------------------------------------------------

cg_graph* cg_graph_read(const char* json, int64_t length, int32_t* out_result) {
    set_out(out_result, int32_t{CG_OK});
    if (json == nullptr || length < 0) {
        set_out(out_result, int32_t{fail(CG_ERR_ARG, "cg_graph_read: null text")});
        return nullptr;
    }
    try {
        auto* g = new cg_graph();
        try {
            g->doc = og::read(std::string_view(json, static_cast<size_t>(length)));
            compile(*g);
        } catch (...) {
            delete g;
            throw;
        }
        return g;
    } catch (const og::version_error& e) {
        set_out(out_result, int32_t{fail(CG_ERR_VERSION, e.what())});
    } catch (const cenda::omformat::format_error& e) {
        set_out(out_result, int32_t{fail(CG_ERR_FORMAT, e.what())});
    } catch (const std::exception& e) {
        set_out(out_result, int32_t{fail(CG_ERR_ARG, e.what())});
    }
    return nullptr;
}

void cg_graph_destroy(cg_graph* g) { delete g; }

cc_bytes* cg_graph_write(const cg_graph* g) {
    if (g == nullptr) {
        fail(CG_ERR_ARG, "cg_graph_write: null graph");
        return nullptr;
    }
    try {
        const std::string text = og::write(g->doc);
        auto* b = new cc_bytes();
        b->data.resize(text.size());
        std::memcpy(b->data.data(), text.data(), text.size());
        return b;
    } catch (const std::exception& e) {
        fail(CG_ERR_ARG, e.what());
        return nullptr;
    }
}

const char* cg_graph_name(const cg_graph* g) { return g == nullptr ? "" : g->doc.name.c_str(); }
const char* cg_graph_profile(const cg_graph* g) { return g == nullptr ? "" : g->doc.profile.c_str(); }

int32_t cg_graph_param_count(const cg_graph* g) { return g == nullptr ? 0 : static_cast<int32_t>(g->doc.params.size()); }

int32_t cg_graph_param_get(const cg_graph* g, int32_t index, const char** out_name, int32_t* out_type) {
    if (g == nullptr || index < 0 || static_cast<size_t>(index) >= g->doc.params.size()) {
        return fail(CG_ERR_RANGE, "cg_graph_param_get: index out of range");
    }
    const og::Param& p = g->doc.params[static_cast<size_t>(index)];
    set_out(out_name, p.name.c_str());
    set_out(out_type, static_cast<int32_t>(p.type));
    return CG_OK;
}

int32_t cg_graph_param_find(const cg_graph* g, const char* name) {
    return g == nullptr || name == nullptr ? -1 : g->doc.find_param(name);
}

int32_t cg_graph_slot_count(const cg_graph* g) { return g == nullptr ? 0 : static_cast<int32_t>(g->doc.slots.size()); }

const char* cg_graph_slot_name(const cg_graph* g, int32_t index) {
    if (g == nullptr || index < 0 || static_cast<size_t>(index) >= g->doc.slots.size()) return nullptr;
    return g->doc.slots[static_cast<size_t>(index)].c_str();
}

int32_t cg_graph_slot_find(const cg_graph* g, const char* name) {
    return g == nullptr || name == nullptr ? -1 : g->doc.find_slot(name);
}

int32_t cg_graph_node_count(const cg_graph* g) { return g == nullptr ? 0 : static_cast<int32_t>(g->nodes.size()); }

int32_t cg_graph_node_get(const cg_graph* g, int32_t index, const char** out_id, int32_t* out_type, int32_t* out_parent) {
    if (g == nullptr || index < 0 || static_cast<size_t>(index) >= g->nodes.size()) {
        return fail(CG_ERR_RANGE, "cg_graph_node_get: index out of range");
    }
    const CNode& c = g->nodes[static_cast<size_t>(index)];
    set_out(out_id, c.id.c_str());
    set_out(out_type, static_cast<int32_t>(c.type));
    set_out(out_parent, static_cast<int32_t>(c.parent));
    return CG_OK;
}

int32_t cg_graph_node_find(const cg_graph* g, const char* id) {
    return g == nullptr || id == nullptr ? -1 : g->doc.find_node(id);
}

int32_t cg_graph_state_count(const cg_graph* g, int32_t node) {
    const CNode* c = machine_node(g, node);
    return c == nullptr ? 0 : static_cast<int32_t>(c->states.size());
}

const char* cg_graph_state_name(const cg_graph* g, int32_t node, int32_t state) {
    const CNode* c = machine_node(g, node);
    if (c == nullptr || state < 0 || static_cast<size_t>(state) >= c->states.size()) return nullptr;
    return c->states[static_cast<size_t>(state)].name.c_str();
}

int32_t cg_graph_state_find(const cg_graph* g, int32_t node, const char* name) {
    const CNode* c = machine_node(g, node);
    if (c == nullptr || name == nullptr) return -1;
    for (size_t i = 0; i < c->states.size(); ++i) {
        if (c->states[i].name == name) return static_cast<int32_t>(i);
    }
    return -1;
}

int32_t cg_graph_montage_slot_count(const cg_graph* g) {
    return g == nullptr ? 0 : static_cast<int32_t>(g->montage_slots.size());
}

const char* cg_graph_montage_slot_name(const cg_graph* g, int32_t index) {
    if (g == nullptr || index < 0 || static_cast<size_t>(index) >= g->montage_slots.size()) return nullptr;
    return g->doc.nodes[static_cast<size_t>(g->montage_slots[static_cast<size_t>(index)])].name.c_str();
}

int32_t cg_graph_montage_slot_find(const cg_graph* g, const char* name) {
    if (g == nullptr || name == nullptr) return -1;
    for (size_t i = 0; i < g->montage_slots.size(); ++i) {
        if (g->doc.nodes[static_cast<size_t>(g->montage_slots[i])].name == name) return static_cast<int32_t>(i);
    }
    return -1;
}

int32_t cg_graph_target_count(const cg_graph* g) { return g == nullptr ? 0 : static_cast<int32_t>(g->doc.targets.size()); }

const char* cg_graph_target_name(const cg_graph* g, int32_t index) {
    if (g == nullptr || index < 0 || static_cast<size_t>(index) >= g->doc.targets.size()) return nullptr;
    return g->doc.targets[static_cast<size_t>(index)].c_str();
}

int32_t cg_graph_target_find(const cg_graph* g, const char* name) {
    return g == nullptr || name == nullptr ? -1 : g->doc.find_target(name);
}

int32_t cg_graph_modifier_count(const cg_graph* g) { return g == nullptr ? 0 : static_cast<int32_t>(g->doc.modifiers.size()); }

// ---- instance -----------------------------------------------------------------------------------------

cg_instance* cg_instance_create(const cg_graph* g, const cr_rig* rig, const cc_char* character, int32_t* out_unresolved) {
    set_out(out_unresolved, int32_t{0});
    if (g == nullptr || rig == nullptr) {
        fail(CG_ERR_ARG, "cg_instance_create: null graph or rig");
        return nullptr;
    }
    auto* inst = new (std::nothrow) cg_instance();
    if (inst == nullptr) {
        fail(CG_ERR_ARG, "cg_instance_create: out of memory");
        return nullptr;
    }
    inst->g = g;
    inst->rig = rig;
    inst->character = character;
    inst->poses = cr_poses_create(rig, g->slot_count);
    if (inst->poses == nullptr) {
        fail(CG_ERR_ARG, std::string("cg_instance_create: ") + cr_last_error());
        delete inst;
        return nullptr;
    }
    const size_t n = g->nodes.size();
    inst->params.assign(g->doc.params.size() * 2, 0.0);
    inst->slots.resize(g->doc.slots.size());
    inst->rt.resize(n);
    inst->players.resize(n);
    inst->rate_mul.assign(n, 1.f);
    inst->weights.assign(static_cast<size_t>(std::max(1, g->weight_floats)), 0.f);
    inst->ops.reserve(static_cast<size_t>(g->max_ops));
    inst->notifies.reserve(kMaxNotifies);
    inst->fired.resize(kMaxFired);
    inst->sync_members.reserve(n);
    inst->sync_nodes.reserve(n);
    inst->machines.resize(g->machines.size());
    for (size_t m = 0; m < g->machines.size(); ++m) {
        const CNode& c = g->nodes[static_cast<size_t>(g->machines[m])];
        inst->machines[m].w.assign(c.children.size(), 0.f);
        inst->machines[m].snap.assign(c.children.size(), 0.f);
        inst->machines[m].path.reserve(c.states.size());
        inst->machines[m].inertia = cr_inertia_create(rig);
    }
    inst->mslots.resize(g->montage_slots.size());
    for (SlotRt& s : inst->mslots) s.inertia = cr_inertia_create(rig);
    // Masks: roles through the character, joints by id / name.
    int32_t unresolved = 0;
    for (const og::Mask& m : g->doc.masks) {
        cr_mask* mask = cr_mask_create(rig, 0.f);
        for (const og::MaskEntry& e : m.entries) {
            const uint32_t flags = e.branch ? static_cast<uint32_t>(CR_MASK_BRANCH) : 0u;
            if (!e.morph.empty()) {  // a morph channel (#171), "*" = every channel
                const int32_t ch = e.morph == "*" ? -1 : cr_rig_morph_find(rig, e.morph.c_str());
                if (e.morph != "*" && ch < 0) {
                    ++unresolved;
                } else {
                    cr_mask_set_morph(mask, ch, e.weight);
                }
            } else if (!e.role.empty()) {
                if (character == nullptr
                    || cc_char_mask_role(character, rig, mask, e.role.c_str(), e.weight, flags, e.depth) != CC_OK) {
                    ++unresolved;
                }
            } else {
                const int32_t j = cr_rig_find(rig, e.joint.c_str());
                if (j < 0) {
                    ++unresolved;
                } else {
                    cr_mask_set(mask, j, e.weight, flags, e.depth);
                }
            }
        }
        inst->masks.push_back(mask);
    }
    set_out(out_unresolved, unresolved);
    // Rest and character reference slots.
    cr_pose_op rest[2]{};
    rest[0].op = CR_OP_REST;
    rest[0].dst = g->rest_slot;
    rest[1].op = CR_OP_REST;
    rest[1].dst = g->ref_slot;
    cr_poses_run(inst->poses, rest, 2);
    if (character != nullptr && cc_char_pose_count(character) > 0) {
        const int32_t joints = cr_rig_joint_count(rig);
        std::vector<float> local(static_cast<size_t>(joints) * 9);
        cr_poses_get(inst->poses, g->ref_slot, local.data(), nullptr);
        for (int32_t i = 0, c = cc_char_pose_count(character); i < c; ++i) {
            const char* joint = nullptr;
            float rot[3];
            if (cc_char_pose_get(character, i, &joint, rot) != CC_OK) continue;
            const int32_t j = cr_rig_find(rig, joint);
            if (j >= 0) std::memcpy(local.data() + static_cast<size_t>(j) * 9 + 3, rot, sizeof rot);
        }
        cr_poses_set(inst->poses, g->ref_slot, local.data(), nullptr);
    }
    unresolved += build_modifiers(*inst);
    // Morph drives (#171): channel by name; a drive whose channel the rig lacks is left out (counted).
    const auto channels = static_cast<size_t>(cr_rig_morph_count(rig));
    for (const og::MorphDrive& md : g->doc.morph_drives) {
        cg_instance::MorphDriveRt d;
        d.channel = cr_rig_morph_find(rig, md.morph.c_str());
        if (d.channel < 0) {
            ++unresolved;
            continue;
        }
        d.param = g->doc.find_param(md.param);
        d.weight_param = md.weight_param.empty() ? -1 : g->doc.find_param(md.weight_param);
        d.weight = md.weight;
        inst->morph_drives.push_back(d);
    }
    inst->morph_values.assign(inst->morph_drives.size() * channels, std::numeric_limits<float>::quiet_NaN());
    inst->ops.reserve(static_cast<size_t>(g->max_ops) + inst->morph_drives.size());
    set_out(out_unresolved, unresolved);
    reset_instance(*inst);
    return inst;
}

void cg_instance_destroy(cg_instance* inst) { delete inst; }

int32_t cg_instance_reset(cg_instance* inst) {
    if (inst == nullptr) return fail(CG_ERR_ARG, "cg_instance_reset: null instance");
    reset_instance(*inst);
    return CG_OK;
}

int32_t cg_instance_bind_slot(cg_instance* inst, int32_t slot, const cm_clip* clip, int32_t* out_dropped) {
    set_out(out_dropped, int32_t{0});
    if (inst == nullptr || slot < 0 || static_cast<size_t>(slot) >= inst->slots.size()) {
        return fail(CG_ERR_RANGE, "cg_instance_bind_slot: bad slot");
    }
    if (clip == nullptr) {
        inst->slots[static_cast<size_t>(slot)].release();
        return CG_OK;
    }
    return bind_clip(*inst, clip, inst->slots[static_cast<size_t>(slot)], out_dropped);
}

int32_t cg_instance_bind_slot_retarget(cg_instance* inst, int32_t slot, const cm_clip* clip, cc_retargeter* retargeter,
                                       int32_t* out_dropped) {
    set_out(out_dropped, int32_t{0});
    if (inst == nullptr || slot < 0 || static_cast<size_t>(slot) >= inst->slots.size()) {
        return fail(CG_ERR_RANGE, "cg_instance_bind_slot_retarget: bad slot");
    }
    if (clip == nullptr || retargeter == nullptr) return fail(CG_ERR_ARG, "cg_instance_bind_slot_retarget: null clip or retargeter");
    if (cc_retarget_target_rig(retargeter) != inst->rig) {
        return fail(CG_ERR_ARG, "cg_instance_bind_slot_retarget: the retargeter's target is another rig");
    }
    const cr_rig* src = cc_retarget_source_rig(retargeter);
    BoundClip b;
    const int32_t rc = bind_clip_to(*inst, clip, cc_retarget_source_char(retargeter), src, b, out_dropped);
    if (rc != CG_OK) return rc;
    if (b.additive != CM_ADDITIVE_NONE) {
        b.release();
        return fail(CG_ERR_ARG, "cg_instance_bind_slot_retarget: additive clips are not retargeted");
    }
    b.retarget = retargeter;
    b.src_poses = cr_poses_create(src, 1);
    b.src_local.assign(static_cast<size_t>(cr_rig_joint_count(src)) * 9, 0.f);
    b.dst_local.assign(static_cast<size_t>(cr_rig_joint_count(inst->rig)) * 9, 0.f);
    b.driven.assign(static_cast<size_t>(cr_rig_joint_count(inst->rig)), 0);
    if (b.src_poses == nullptr) {
        b.release();
        return fail(CG_ERR_ARG, std::string("cg_instance_bind_slot_retarget: ") + cr_last_error());
    }
    // The hips' first-frame position (pinned while root motion is extracted).
    if (b.root_joint >= 0 && b.root_axes != 0 && inst->character != nullptr) {
        const char* hid = cc_char_role_joint(inst->character, "hips");
        const int h = hid == nullptr ? -1 : cr_rig_find(inst->rig, hid);
        if (h >= 0) {
            cr_pose_op op{};
            op.op = CR_OP_SAMPLE;
            op.object = b.rig_clip;
            cr_poses_run(b.src_poses, &op, 1);
            cr_poses_get(b.src_poses, 0, b.src_local.data(), nullptr);
            if (cc_retarget_pose(retargeter, b.src_local.data(), b.dst_local.data(), nullptr) == CC_OK) {
                std::memcpy(b.pin, b.dst_local.data() + static_cast<size_t>(h) * 9, sizeof b.pin);
                b.has_pin = true;
            }
        }
    }
    BoundClip& out = inst->slots[static_cast<size_t>(slot)];
    out.release();
    out = std::move(b);
    return CG_OK;
}

int32_t cg_instance_add_montage(cg_instance* inst, const cm_clip* clip) {
    if (inst == nullptr || clip == nullptr) return fail(CG_ERR_ARG, "cg_instance_add_montage: null argument");
    MontageClip mc;
    const int32_t rc = bind_clip(*inst, clip, mc.clip, nullptr);
    if (rc != CG_OK) return rc;
    for (int32_t i = 0, n = cm_clip_notify_count(mc.clip.doc); i < n; ++i) {
        const char* name = nullptr;
        float time = 0.f, duration = 0.f;
        cm_clip_notify_get(mc.clip.doc, i, &name, &time, &duration, nullptr);
        if (name != nullptr && std::strncmp(name, "section:", 8) == 0) {
            mc.section_names.emplace_back(name + 8);
            mc.section_start.push_back(time);
        }
    }
    if (mc.section_start.empty() || mc.section_start[0] > 0.f) {
        mc.section_names.insert(mc.section_names.begin(), mc.section_start.empty() ? "default" : "");
        mc.section_start.insert(mc.section_start.begin(), 0.f);
    }
    inst->montages.push_back(std::move(mc));
    return static_cast<int32_t>(inst->montages.size() - 1);
}

int32_t cg_instance_set_float(cg_instance* inst, int32_t param, float value) {
    if (bad_param(inst, param) || !std::isfinite(value)) return fail(CG_ERR_ARG, "cg_instance_set_float: bad parameter");
    if (ptype(inst, param) != og::ParamType::Float) return fail(CG_ERR_ARG, "cg_instance_set_float: not a float parameter");
    inst->params[static_cast<size_t>(param) * 2] = value;
    return CG_OK;
}

int32_t cg_instance_set_int(cg_instance* inst, int32_t param, int32_t value) {
    if (bad_param(inst, param) || ptype(inst, param) != og::ParamType::Int) {
        return fail(CG_ERR_ARG, "cg_instance_set_int: not an int parameter");
    }
    inst->params[static_cast<size_t>(param) * 2] = value;
    return CG_OK;
}

int32_t cg_instance_set_bool(cg_instance* inst, int32_t param, int32_t value) {
    if (bad_param(inst, param) || ptype(inst, param) != og::ParamType::Bool) {
        return fail(CG_ERR_ARG, "cg_instance_set_bool: not a bool parameter");
    }
    inst->params[static_cast<size_t>(param) * 2] = value != 0 ? 1.0 : 0.0;
    return CG_OK;
}

int32_t cg_instance_fire_trigger(cg_instance* inst, int32_t param) {
    if (bad_param(inst, param) || ptype(inst, param) != og::ParamType::Trigger) {
        return fail(CG_ERR_ARG, "cg_instance_fire_trigger: not a trigger");
    }
    inst->params[static_cast<size_t>(param) * 2] = 1.0;
    return CG_OK;
}

int32_t cg_instance_reset_trigger(cg_instance* inst, int32_t param) {
    if (bad_param(inst, param) || ptype(inst, param) != og::ParamType::Trigger) {
        return fail(CG_ERR_ARG, "cg_instance_reset_trigger: not a trigger");
    }
    inst->params[static_cast<size_t>(param) * 2] = 0.0;
    return CG_OK;
}

int32_t cg_instance_set_vec2(cg_instance* inst, int32_t param, float x, float y) {
    if (bad_param(inst, param) || ptype(inst, param) != og::ParamType::Vec2 || !std::isfinite(x) || !std::isfinite(y)) {
        return fail(CG_ERR_ARG, "cg_instance_set_vec2: not a vec2 parameter");
    }
    inst->params[static_cast<size_t>(param) * 2] = x;
    inst->params[static_cast<size_t>(param) * 2 + 1] = y;
    return CG_OK;
}

int32_t cg_instance_get_param(const cg_instance* inst, int32_t param, float* out) {
    if (bad_param(inst, param) || out == nullptr) return fail(CG_ERR_ARG, "cg_instance_get_param: bad parameter");
    out[0] = static_cast<float>(inst->params[static_cast<size_t>(param) * 2]);
    out[1] = static_cast<float>(inst->params[static_cast<size_t>(param) * 2 + 1]);
    return CG_OK;
}

int32_t cg_instance_set_target(cg_instance* inst, int32_t target, int32_t kind, const float* pos, const float* rot) {
    if (inst == nullptr || target < 0 || static_cast<size_t>(target) >= inst->host_targets.size()
        || (kind != CR_TARGET_NONE && kind != CR_TARGET_MODEL && kind != CR_TARGET_WORLD)
        || (kind != CR_TARGET_NONE && pos == nullptr)) {
        return fail(CG_ERR_ARG, "cg_instance_set_target: bad target, kind or position");
    }
    cr_target t{};
    t.kind = kind;
    if (pos != nullptr) std::copy(pos, pos + 3, t.pos);
    if (rot != nullptr) std::copy(rot, rot + 4, t.rot);
    if (inst->mods != nullptr && cr_modstack_set_target(inst->mods, target, &t) != CR_OK) {
        return fail(CG_ERR_ARG, std::string("cg_instance_set_target: ") + cr_last_error());
    }
    inst->host_targets[static_cast<size_t>(target)] = t;
    return CG_OK;
}

int32_t cg_instance_set_ground(cg_instance* inst, cr_ground_fn fn, void* user) {
    if (inst == nullptr) return fail(CG_ERR_ARG, "cg_instance_set_ground: null instance");
    if (inst->mods != nullptr) cr_modstack_set_ground(inst->mods, fn, user);
    return CG_OK;
}

float cg_instance_modifier_value(const cg_instance* inst, int32_t modifier, int32_t key) {
    if (inst == nullptr || modifier < 0 || static_cast<size_t>(modifier) >= inst->mod_index.size()) return 0.f;
    if (key == CG_VALUE_PLAY_RATE || key == CG_VALUE_STRIDE) {
        for (const StrideRt& r : inst->strides) {
            if (r.mod == modifier) return key == CG_VALUE_PLAY_RATE ? r.rate : r.scale;
        }
        return 0.f;
    }
    const int si = inst->mod_index[static_cast<size_t>(modifier)];
    return si < 0 || inst->mods == nullptr ? 0.f : cr_modstack_get_value(inst->mods, si, key);
}

int32_t cg_instance_set_root_motion(cg_instance* inst, int32_t mode) {
    if (inst == nullptr || (mode != CG_ROOT_MOTION_KEEP && mode != CG_ROOT_MOTION_EXTRACT)) {
        return fail(CG_ERR_ARG, "cg_instance_set_root_motion: bad mode");
    }
    inst->root_motion_mode = mode;
    return CG_OK;
}

int32_t cg_instance_travel(cg_instance* inst, int32_t node, int32_t state) {
    const CNode* c = inst == nullptr ? nullptr : machine_node(inst->g, node);
    if (c == nullptr || state < 0 || static_cast<size_t>(state) >= c->states.size()) {
        return fail(CG_ERR_ARG, "cg_instance_travel: bad machine or state");
    }
    MachineRt& m = inst->machines[static_cast<size_t>(c->machine)];
    m.path.clear();
    m.path_at = 0;
    if (state == m.current) return CG_OK;
    // BFS over states; edges = transitions (any-state ones from everywhere).
    const size_t ns = c->states.size();
    std::vector<int> via(ns, -1), from(ns, -1);
    std::vector<uint8_t> seen(ns, 0);
    std::deque<int> q{m.current};
    seen[static_cast<size_t>(m.current)] = 1;
    while (!q.empty() && !seen[static_cast<size_t>(state)]) {
        const int s = q.front();
        q.pop_front();
        for (size_t k = 0; k < c->transitions.size(); ++k) {
            const CTrans& t = c->transitions[k];
            if ((t.from >= 0 && t.from != s) || t.to == s || seen[static_cast<size_t>(t.to)]) continue;
            seen[static_cast<size_t>(t.to)] = 1;
            via[static_cast<size_t>(t.to)] = static_cast<int>(k);
            from[static_cast<size_t>(t.to)] = s;
            q.push_back(t.to);
        }
    }
    if (!seen[static_cast<size_t>(state)]) return fail(CG_ERR_STATE, "cg_instance_travel: no transitions lead there");
    for (int s = state; s != m.current; s = from[static_cast<size_t>(s)]) m.path.push_back(via[static_cast<size_t>(s)]);
    std::reverse(m.path.begin(), m.path.end());
    return CG_OK;
}

int32_t cg_instance_state(const cg_instance* inst, int32_t node, int32_t* out_state, int32_t* out_previous, float* out_blend) {
    const CNode* c = inst == nullptr ? nullptr : machine_node(inst->g, node);
    if (c == nullptr) return fail(CG_ERR_ARG, "cg_instance_state: not a state machine");
    const MachineRt& m = inst->machines[static_cast<size_t>(c->machine)];
    set_out(out_state, static_cast<int32_t>(m.current));
    set_out(out_previous, static_cast<int32_t>(m.previous));
    set_out(out_blend, m.fading && m.fade_time > 0.f ? std::min(1.f, m.fade_elapsed / m.fade_time) : 1.f);
    return CG_OK;
}

int32_t cg_instance_montage_play(cg_instance* inst, int32_t slot, int32_t montage, float rate, float blend_in,
                                 float blend_out, const char* section) {
    SlotRt* s = montage_slot(inst, slot);
    if (s == nullptr || montage < 0 || static_cast<size_t>(montage) >= inst->montages.size() || !std::isfinite(rate)
        || !std::isfinite(blend_in) || !std::isfinite(blend_out)) {
        return fail(CG_ERR_ARG, "cg_instance_montage_play: bad slot, montage or numbers");
    }
    const MontageClip& mc = inst->montages[static_cast<size_t>(montage)];
    int sec = 0;
    if (section != nullptr) {
        sec = find_section(mc, section);
        if (sec < 0) return fail(CG_ERR_ARG, std::string("cg_instance_montage_play: no section ") + section);
    }
    const bool busy = s->montage >= 0 && s->weight > 0.f;
    s->montage = montage;
    s->section = sec;
    s->time = mc.section_start[static_cast<size_t>(sec)];
    s->rate = rate;
    s->blend_out = std::max(0.f, blend_out);
    s->next.assign(mc.section_start.size(), -1);
    s->fade_elapsed = 0.f;
    s->fade_len = std::max(0.f, blend_in);
    if (busy) {
        // Interrupting: the new montage is in at once; the old output decays (inertialization).
        cr_inertia_request(s->inertia, blend_in);
        s->weight = 1.f;
        s->phase = PHASE_PLAY;
    } else if (blend_in > 0.f) {
        s->weight = 0.f;
        s->phase = PHASE_IN;
    } else {
        s->weight = 1.f;
        s->phase = PHASE_PLAY;
    }
    return CG_OK;
}

int32_t cg_instance_montage_stop(cg_instance* inst, int32_t slot, float blend_out) {
    SlotRt* s = montage_slot(inst, slot);
    if (s == nullptr || !std::isfinite(blend_out)) return fail(CG_ERR_ARG, "cg_instance_montage_stop: bad slot");
    if (s->montage < 0) return fail(CG_ERR_STATE, "cg_instance_montage_stop: the slot is idle");
    montage_begin_out(*s, blend_out);
    return CG_OK;
}

int32_t cg_instance_montage_jump(cg_instance* inst, int32_t slot, const char* section) {
    SlotRt* s = montage_slot(inst, slot);
    if (s == nullptr || section == nullptr) return fail(CG_ERR_ARG, "cg_instance_montage_jump: bad slot");
    if (s->montage < 0) return fail(CG_ERR_STATE, "cg_instance_montage_jump: the slot is idle");
    const MontageClip& mc = inst->montages[static_cast<size_t>(s->montage)];
    const int sec = find_section(mc, section);
    if (sec < 0) return fail(CG_ERR_ARG, std::string("cg_instance_montage_jump: no section ") + section);
    s->section = sec;
    s->time = mc.section_start[static_cast<size_t>(sec)];
    return CG_OK;
}

int32_t cg_instance_montage_set_next(cg_instance* inst, int32_t slot, const char* section, const char* next) {
    SlotRt* s = montage_slot(inst, slot);
    if (s == nullptr || section == nullptr) return fail(CG_ERR_ARG, "cg_instance_montage_set_next: bad slot");
    if (s->montage < 0) return fail(CG_ERR_STATE, "cg_instance_montage_set_next: the slot is idle");
    const MontageClip& mc = inst->montages[static_cast<size_t>(s->montage)];
    const int sec = find_section(mc, section);
    const int nx = next == nullptr ? -1 : find_section(mc, next);
    if (sec < 0 || (next != nullptr && nx < 0)) return fail(CG_ERR_ARG, "cg_instance_montage_set_next: unknown section");
    s->next[static_cast<size_t>(sec)] = nx;
    return CG_OK;
}

int32_t cg_instance_montage_status(const cg_instance* inst, int32_t slot, cg_montage_status* out) {
    if (inst == nullptr || out == nullptr || slot < 0 || static_cast<size_t>(slot) >= inst->mslots.size()) {
        return fail(CG_ERR_ARG, "cg_instance_montage_status: bad slot");
    }
    const SlotRt& s = inst->mslots[static_cast<size_t>(slot)];
    *out = cg_montage_status{s.montage, s.montage >= 0 ? s.section : -1, s.time, s.weight,
                             s.montage >= 0 && s.phase == PHASE_OUT ? 1 : 0, 0u};
    return CG_OK;
}

int32_t cg_instance_evaluate(cg_instance* inst, float dt, const cr_joint_extra* extras, int32_t extra_count,
                             const float* base_matrix, float* out_skin, float* out_world, float* out_child) {
    if (inst == nullptr || !std::isfinite(dt) || dt < 0.f) return fail(CG_ERR_ARG, "cg_instance_evaluate: bad arguments");
    const cg_graph& g = *inst->g;
    inst->dt = dt;
    inst->notifies.clear();
    inst->root_motion = cr_root_motion{};
    std::fill(inst->curve_acc.begin(), inst->curve_acc.end(), 0.0);

    // 0. montages
    for (SlotRt& s : inst->mslots) montage_advance(*inst, s, dt);

    // 1. weights, parents first
    for (int i : g.order) {
        NodeRt& r = inst->rt[static_cast<size_t>(i)];
        r.was_active = r.active;
        r.active = false;
        r.influence = 0.f;
        r.root_influence = 0.f;
        r.root_path = false;
    }
    {
        NodeRt& root = inst->rt[static_cast<size_t>(g.root)];
        root.active = true;
        root.influence = 1.f;
        root.root_influence = 1.f;
        root.root_path = true;
    }
    for (int i : g.order) {
        const CNode& c = g.nodes[static_cast<size_t>(i)];
        NodeRt& r = inst->rt[static_cast<size_t>(i)];
        if (!r.active) {
            r.progress = 0.f;
            continue;
        }
        // A shared node (#164) sums what its uses give it; a tree node is given exactly once (1.0 results).
        auto give = [&](int child, bool active, float influence, bool root_path) {
            NodeRt& k = inst->rt[static_cast<size_t>(child)];
            if (!active) return;
            k.active = true;
            k.influence += influence;
            if (root_path) {
                k.root_path = true;
                k.root_influence += influence;
            }
        };
        float* w = c.weights_at >= 0 ? inst->weights.data() + c.weights_at : nullptr;
        switch (c.type) {
            case og::NodeType::Clip:
                break;
            case og::NodeType::Blend1D:
            case og::NodeType::Blend2D:
                if (c.type == og::NodeType::Blend1D) {
                    blend1d_weights(c, param_value(*inst, c.param), w);
                } else if (c.param_y >= 0) {
                    blend2d_weights(c, param_value(*inst, c.param), param_value(*inst, c.param_y), w);
                } else {
                    blend2d_weights(c, param_value(*inst, c.param, 0), param_value(*inst, c.param, 1), w);
                }
                for (size_t k = 0; k < c.children.size(); ++k) give(c.children[k], w[k] > 0.f, r.influence * w[k], r.root_path);
                break;
            case og::NodeType::StateMachine: {
                const bool fresh = !r.was_active;
                MachineRt& m = inst->machines[static_cast<size_t>(c.machine)];
                if (fresh && m.inertia != nullptr) cr_inertia_reset(m.inertia);
                machine_step(*inst, c, fresh, dt);
                for (size_t k = 0; k < c.children.size(); ++k) {
                    w[k] = m.w[k];
                    give(c.children[k], w[k] > 0.f, r.influence * w[k], r.root_path);
                }
                break;
            }
            case og::NodeType::Layer: {
                float base = r.influence;
                for (size_t k = 0; k < c.layers.size(); ++k) {
                    const CLayer& l = c.layers[k];
                    float lw = l.weight;
                    if (l.weight_param >= 0) lw *= static_cast<float>(param_value(*inst, l.weight_param));
                    lw = std::clamp(lw, 0.f, 1.f);
                    w[k] = lw;
                    // A first-person view layer is not in the body pose: it takes no influence from the base.
                    if (l.mode == og::LayerMode::Override && l.mask < 0 && !l.first_person) base *= 1.f - lw;
                    give(c.children[k + 1], lw > 0.f, r.influence * lw, false);
                }
                give(c.children[0], true, base, r.root_path);
                break;
            }
            case og::NodeType::Slot: {
                const SlotRt& s = inst->mslots[static_cast<size_t>(c.montage_slot)];
                if (!r.was_active) cr_inertia_reset(s.inertia);
                const float mw = s.montage >= 0 ? s.weight : 0.f;
                give(c.children[0], true, c.mask < 0 ? r.influence * (1.f - mw) : r.influence, r.root_path);
                break;
            }
        }
    }

    // Stride warping (#159): the play rate each stride warp allows, from the host's speed and the clips' speed
    // (curves of the previous frame: this frame's are sampled after the clips advance).
    for (StrideRt& r : inst->strides) {
        for (int i : r.clips) inst->rate_mul[static_cast<size_t>(i)] = 1.f;
    }
    for (StrideRt& r : inst->strides) {
        const og::Modifier& md = g.doc.modifiers[static_cast<size_t>(r.mod)];
        const float w = modifier_weight(*inst, md);
        const double speed = std::max(0.0, param_value(*inst, r.speed_param));
        double anim = md.anim_speed;
        if (!md.anim_speed_curve.empty()) {
            const int32_t cv = cg_instance_curve_find(inst, md.anim_speed_curve.c_str());
            if (cv >= 0 && inst->curve_values[static_cast<size_t>(cv)] > 0.f) anim = inst->curve_values[static_cast<size_t>(cv)];
        }
        double rate = 1.0, scale = 1.0;
        if (anim > 1e-6) {
            rate = std::clamp(speed / anim, static_cast<double>(md.rate_min), static_cast<double>(md.rate_max));
            scale = std::clamp(speed / (anim * rate), static_cast<double>(md.stride_min), static_cast<double>(md.stride_max));
        }
        r.rate = static_cast<float>(1.0 + (rate - 1.0) * w);
        r.scale = static_cast<float>(scale);
        for (int i : r.clips) inst->rate_mul[static_cast<size_t>(i)] *= r.rate;
    }

    // 2. time
    for (int i : g.order) {
        const CNode& c = g.nodes[static_cast<size_t>(i)];
        if (c.type != og::NodeType::Clip) continue;
        const NodeRt& r = inst->rt[static_cast<size_t>(i)];
        PlayerRt& p = inst->players[static_cast<size_t>(i)];
        if (!r.active) continue;
        const BoundClip& b = inst->slots[static_cast<size_t>(c.clip_slot)];
        if (!r.was_active) {
            p = PlayerRt{};
            // Start position (Unreal): a restart begins there; nothing between 0 and it is crossed (no notifies).
            if (c.start > 0.f && b.duration > 0.f) p.time = clip_time(c.start * b.duration, b.duration, b.loop);
        }
        p.prev = p.time;
        p.advanced = 0.f;
        if (b.rig_clip == nullptr || b.duration <= 0.f || c.sync >= 0) continue;
        float rate = c.rate;
        if (c.rate_param >= 0) rate *= static_cast<float>(param_value(*inst, c.rate_param));
        rate *= inst->rate_mul[static_cast<size_t>(i)];
        const float advance = rate * dt;
        p.time = clip_time(p.time + advance, b.duration, b.loop);
        p.advanced = b.loop ? advance : p.time - p.prev;
        p.elapsed += p.advanced;
    }
    for (int group = 0; group < g.sync_groups; ++group) {
        inst->sync_members.clear();
        inst->sync_nodes.clear();
        // A member that (re)starts this frame JOINS the running group (Unreal sync groups): it takes the group's
        // normalized phase - that of a member that played last frame (the most influential now) - instead of time 0, so a
        // change of pace (walk -> run, the new clip leading at once) keeps the gait phase. A group starting from
        // nothing starts at the members' own start positions.
        {
            float best = -1.f;
            double phase = -1.0;
            for (int i : g.order) {
                const CNode& c = g.nodes[static_cast<size_t>(i)];
                const NodeRt& r = inst->rt[static_cast<size_t>(i)];
                // Played last frame (it may have dropped out this very frame: walk -> run at once).
                if (c.type != og::NodeType::Clip || c.sync != group || !r.was_active) continue;
                const BoundClip& b = inst->slots[static_cast<size_t>(c.clip_slot)];
                if (b.rig_clip == nullptr || b.duration <= 0.f) continue;
                if (r.influence > best) {  // this frame's weight (0 for one leaving); ties: the first
                    best = r.influence;
                    phase = static_cast<double>(inst->players[static_cast<size_t>(i)].time) / b.duration;
                }
            }
            if (phase >= 0.0) {
                for (int i : g.order) {
                    const CNode& c = g.nodes[static_cast<size_t>(i)];
                    const NodeRt& r = inst->rt[static_cast<size_t>(i)];
                    if (c.type != og::NodeType::Clip || c.sync != group || !r.active || r.was_active) continue;
                    const BoundClip& b = inst->slots[static_cast<size_t>(c.clip_slot)];
                    if (b.rig_clip == nullptr || b.duration <= 0.f) continue;
                    PlayerRt& p = inst->players[static_cast<size_t>(i)];
                    p.time = clip_time(static_cast<float>(phase * b.duration), b.duration, b.loop);
                    p.prev = p.time;
                    p.elapsed = static_cast<double>(p.time);  // its progress continues the group's phase too (footstep rules read it)
                }
            }
        }
        for (int i : g.order) {
            const CNode& c = g.nodes[static_cast<size_t>(i)];
            if (c.type != og::NodeType::Clip || c.sync != group || !inst->rt[static_cast<size_t>(i)].active) continue;
            const BoundClip& b = inst->slots[static_cast<size_t>(c.clip_slot)];
            if (b.rig_clip == nullptr || b.duration <= 0.f) continue;
            float rate = c.rate;
            if (c.rate_param >= 0) rate *= static_cast<float>(param_value(*inst, c.rate_param));
            rate *= inst->rate_mul[static_cast<size_t>(i)];
            inst->sync_members.push_back(cm_sync_member{b.doc, inst->players[static_cast<size_t>(i)].time, rate,
                                                        inst->rt[static_cast<size_t>(i)].influence, 0});
            inst->sync_nodes.push_back(i);
        }
        if (inst->sync_members.empty()) continue;
        const int32_t leader = cm_sync_advance(inst->sync_members.data(), static_cast<int32_t>(inst->sync_members.size()),
                                               -1, dt);
        for (size_t k = 0; k < inst->sync_nodes.size(); ++k) {
            const int i = inst->sync_nodes[k];
            const BoundClip& b = inst->slots[static_cast<size_t>(g.nodes[static_cast<size_t>(i)].clip_slot)];
            PlayerRt& p = inst->players[static_cast<size_t>(i)];
            const float t = inst->sync_members[k].time;
            if (static_cast<int32_t>(k) == leader && b.loop) {
                p.advanced = inst->sync_members[k].rate * dt;
            } else {
                p.advanced = forward_delta(p.prev, t, b.duration, b.loop);
            }
            p.time = t;
            p.elapsed += p.advanced;
        }
    }
    // Progress, children first.
    for (auto it = g.order.rbegin(); it != g.order.rend(); ++it) {
        const int i = *it;
        const CNode& c = g.nodes[static_cast<size_t>(i)];
        NodeRt& r = inst->rt[static_cast<size_t>(i)];
        if (!r.active) continue;
        switch (c.type) {
            case og::NodeType::Clip: {
                const BoundClip& b = inst->slots[static_cast<size_t>(c.clip_slot)];
                r.progress = b.duration > 0.f ? static_cast<float>(inst->players[static_cast<size_t>(i)].elapsed / b.duration) : 0.f;
                break;
            }
            case og::NodeType::StateMachine: {
                const MachineRt& m = inst->machines[static_cast<size_t>(c.machine)];
                r.progress = state_progress(*inst, c, m.current);
                break;
            }
            case og::NodeType::Blend1D:
            case og::NodeType::Blend2D: {
                float best = -1.f;
                for (size_t k = 0; k < c.children.size(); ++k) {
                    const float wk = inst->weights[static_cast<size_t>(c.weights_at) + k];
                    if (wk > best) {
                        best = wk;
                        r.progress = inst->rt[static_cast<size_t>(c.children[k])].progress;
                    }
                }
                break;
            }
            default:
                r.progress = inst->rt[static_cast<size_t>(c.children[0])].progress;
        }
    }

    // Curves (they weigh the modifiers below) and foot-lock marker channels (#159), weighted alike.
    double total = 0.0;
    std::fill(inst->lock_acc.begin(), inst->lock_acc.end(), 0.0);
    for (int i : g.order) {
        const CNode& c = g.nodes[static_cast<size_t>(i)];
        if (c.type != og::NodeType::Clip) continue;
        const NodeRt& r = inst->rt[static_cast<size_t>(i)];
        if (!r.active || r.influence <= 0.f) continue;
        const BoundClip& b = inst->slots[static_cast<size_t>(c.clip_slot)];
        if (b.rig_clip != nullptr) {
            sample_curves(*inst, b, inst->players[static_cast<size_t>(i)].time, r.influence, total);
            sample_locks(*inst, b, inst->players[static_cast<size_t>(i)].time, r.influence);
        }
    }
    for (int i : g.montage_slots) {
        const CNode& c = g.nodes[static_cast<size_t>(i)];
        const NodeRt& r = inst->rt[static_cast<size_t>(i)];
        const SlotRt& s = inst->mslots[static_cast<size_t>(c.montage_slot)];
        if (!r.active || s.seg_count == 0 || s.montage < 0) continue;
        const float w = r.influence * s.weight;
        if (w > 0.f) {
            const BoundClip& mb = inst->montages[static_cast<size_t>(s.montage)].clip;
            sample_curves(*inst, mb, s.time, w, total);
            sample_locks(*inst, mb, s.time, w);
        }
    }
    for (size_t k = 0; k < inst->curve_values.size(); ++k) {
        inst->curve_values[k] = total > 0.0 ? static_cast<float>(inst->curve_acc[k] / total) : 0.f;
    }
    for (double& a : inst->lock_acc) a = total > 0.0 ? a / total : 0.0;

    // 3. pose
    inst->ops.clear();
    ++inst->frame;
    if (inst->frame == 0) {  // wrapped: stale stamps could match
        for (NodeRt& r : inst->rt) r.emitted = 0;
        inst->frame = 1;
    }
    emit(*inst, g.root);
    if (inst->mods != nullptr) {
        for (size_t m = 0; m < g.doc.modifiers.size(); ++m) {
            const int si = inst->mod_index[m];
            if (si < 0) continue;
            cr_modstack_set_weight(inst->mods, si, modifier_weight(*inst, g.doc.modifiers[m]));
        }
        feed_locomotion(*inst);
        for (const auto& [slot, host] : inst->aliases) {
            cr_modstack_set_target(inst->mods, slot, &inst->host_targets[static_cast<size_t>(host)]);
        }
        cr_modstack_set_frame(inst->mods, base_matrix);
        cr_pose_op& op = push(*inst, CR_OP_MODIFY, g.nodes[static_cast<size_t>(g.root)].out_slot);
        op.object = inst->mods;
        op.param = dt;
    }
    // Morph drives (#171): each an override of its channel toward its parameter, after the modifiers (both views).
    if (!inst->morph_drives.empty()) {
        const size_t channels = inst->morph_values.size() / inst->morph_drives.size();
        for (size_t k = 0; k < inst->morph_drives.size(); ++k) {
            const cg_instance::MorphDriveRt& d = inst->morph_drives[k];
            float w = d.weight;
            if (d.weight_param >= 0) w *= static_cast<float>(inst->params[static_cast<size_t>(d.weight_param) * 2]);
            if (!(w > 0.f)) continue;
            float* row = inst->morph_values.data() + k * channels;
            row[static_cast<size_t>(d.channel)] = static_cast<float>(inst->params[static_cast<size_t>(d.param) * 2]);
            cr_pose_op& op = push(*inst, CR_OP_MORPH, g.nodes[static_cast<size_t>(g.root)].out_slot);
            op.object = row;
            op.weight = w;
        }
    }
    emit_first_person(*inst);
    const int32_t rc = cr_poses_evaluate(inst->poses, inst->ops.data(), static_cast<int32_t>(inst->ops.size()),
                                         g.nodes[static_cast<size_t>(g.root)].out_slot, extras, extra_count,
                                         base_matrix, out_skin, out_world, out_child);
    if (rc != CR_OK) {
        return fail(rc == CR_ERR_HOST ? CG_ERR_HOST : CG_ERR_ARG, std::string("cg_instance_evaluate: ") + cr_last_error());
    }

    // 4. outputs
    const float threshold = g.doc.notify_threshold;
    for (int i : g.order) {
        const CNode& c = g.nodes[static_cast<size_t>(i)];
        if (c.type != og::NodeType::Clip) continue;
        const NodeRt& r = inst->rt[static_cast<size_t>(i)];
        if (!r.active) continue;
        const BoundClip& b = inst->slots[static_cast<size_t>(c.clip_slot)];
        if (b.rig_clip == nullptr) continue;
        const PlayerRt& p = inst->players[static_cast<size_t>(i)];
        if (r.influence >= threshold && r.influence > 0.f) fire(*inst, b, c.clip_slot, -1, p.prev, p.advanced, r.influence);
        if (r.root_path && r.root_influence > 0.f && b.root_joint >= 0 && b.root_axes != 0 && p.advanced != 0.f) {
            cr_root_motion m{};
            if (b.retarget != nullptr) {
                cr_clip_root_motion(b.rig_clip, cc_retarget_source_rig(b.retarget), b.root_joint, b.root_axes, p.prev,
                                    p.advanced, &m);
                cc_retarget_map_motion(b.retarget, &m);
            } else {
                cr_clip_root_motion(b.rig_clip, inst->rig, b.root_joint, b.root_axes, p.prev, p.advanced, &m);
            }
            add_motion(inst->root_motion, m, r.root_influence);
        }
    }
    for (int i : g.montage_slots) {
        const CNode& c = g.nodes[static_cast<size_t>(i)];
        const NodeRt& r = inst->rt[static_cast<size_t>(i)];
        const SlotRt& s = inst->mslots[static_cast<size_t>(c.montage_slot)];
        if (!r.active || s.seg_count == 0) continue;
        const int montage = s.montage;
        if (montage < 0) continue;
        const BoundClip& b = inst->montages[static_cast<size_t>(montage)].clip;
        const float w = r.influence * s.weight;
        for (int k = 0; k < s.seg_count; ++k) {
            fire(*inst, b, -1, montage, s.seg[k].from, s.seg[k].delta, w);
            if (r.root_path && b.root_joint >= 0 && b.root_axes != 0 && s.seg[k].delta != 0.f) {
                cr_root_motion m{};
                cr_clip_root_motion(b.rig_clip, inst->rig, b.root_joint, b.root_axes, s.seg[k].from, s.seg[k].delta, &m);
                add_motion(inst->root_motion, m, w);
            }
        }
    }
    return CG_OK;
}

int32_t cg_instance_compose_view(const cg_instance* inst, int32_t view, const cr_joint_extra* extras,
                                 int32_t extra_count, const float* base_matrix, float* out_skin, float* out_world,
                                 float* out_child) {
    if (inst == nullptr || (view != CG_VIEW_BODY && view != CG_VIEW_FIRST_PERSON)) {
        return fail(CG_ERR_ARG, "cg_instance_compose_view: bad arguments");
    }
    const cg_graph& g = *inst->g;
    const int32_t slot = view == CG_VIEW_FIRST_PERSON && g.fp_slot >= 0 ? g.fp_slot
                                                                         : g.nodes[static_cast<size_t>(g.root)].out_slot;
    if (cr_poses_compose(inst->poses, slot, extras, extra_count, base_matrix, out_skin, out_world, out_child) != CR_OK) {
        return fail(CG_ERR_ARG, std::string("cg_instance_compose_view: ") + cr_last_error());
    }
    return CG_OK;
}

int32_t cg_instance_morph_weights(const cg_instance* inst, int32_t view, float* out, int32_t capacity) {
    if (inst == nullptr || (view != CG_VIEW_BODY && view != CG_VIEW_FIRST_PERSON) || capacity < 0) {
        return fail(CG_ERR_ARG, "cg_instance_morph_weights: bad arguments");
    }
    const cg_graph& g = *inst->g;
    const int32_t slot = view == CG_VIEW_FIRST_PERSON && g.fp_slot >= 0 ? g.fp_slot
                                                                         : g.nodes[static_cast<size_t>(g.root)].out_slot;
    const int32_t n = cr_poses_morph_weights(inst->poses, slot, out, nullptr, capacity);
    if (n < 0) return fail(CG_ERR_ARG, std::string("cg_instance_morph_weights: ") + cr_last_error());
    return n;
}

int32_t cg_graph_first_person_layer_count(const cg_graph* g) {
    if (g == nullptr) return 0;
    int32_t n = 0;
    for (const CLayer& l : g->nodes[static_cast<size_t>(g->root)].layers) n += l.first_person ? 1 : 0;
    return n;
}

int32_t cg_instance_local_pose(const cg_instance* inst, float* out_local) {
    if (inst == nullptr || out_local == nullptr) return fail(CG_ERR_ARG, "cg_instance_local_pose: bad arguments");
    return cr_poses_get(inst->poses, inst->g->nodes[static_cast<size_t>(inst->g->root)].out_slot, out_local, nullptr);
}

int32_t cg_instance_notify_count(const cg_instance* inst) {
    return inst == nullptr ? 0 : static_cast<int32_t>(inst->notifies.size());
}

int32_t cg_instance_notify_get(const cg_instance* inst, int32_t index, cg_notify* out, const char** out_name,
                               const char** out_payload_json) {
    if (inst == nullptr || index < 0 || static_cast<size_t>(index) >= inst->notifies.size()) {
        return fail(CG_ERR_RANGE, "cg_instance_notify_get: index out of range");
    }
    const cg_notify& n = inst->notifies[static_cast<size_t>(index)];
    set_out(out, n);
    const cm_clip* doc = n.source >= 0 ? inst->slots[static_cast<size_t>(n.source)].doc
                                       : inst->montages[static_cast<size_t>(n.montage)].clip.doc;
    const char* name = "";
    const char* payload = "";
    if (doc != nullptr) cm_clip_notify_get(doc, n.notify, &name, nullptr, nullptr, &payload);
    set_out(out_name, name);
    set_out(out_payload_json, payload);
    return CG_OK;
}

int32_t cg_instance_curve_count(const cg_instance* inst) {
    return inst == nullptr ? 0 : static_cast<int32_t>(inst->curve_names.size());
}

const char* cg_instance_curve_name(const cg_instance* inst, int32_t index) {
    if (inst == nullptr || index < 0 || static_cast<size_t>(index) >= inst->curve_names.size()) return nullptr;
    return inst->curve_names[static_cast<size_t>(index)].c_str();
}

int32_t cg_instance_curve_find(const cg_instance* inst, const char* name) {
    if (inst == nullptr || name == nullptr) return -1;
    for (size_t i = 0; i < inst->curve_names.size(); ++i) {
        if (inst->curve_names[i] == name) return static_cast<int32_t>(i);
    }
    return -1;
}

float cg_instance_curve_value(const cg_instance* inst, int32_t index) {
    if (inst == nullptr || index < 0 || static_cast<size_t>(index) >= inst->curve_values.size()) return 0.f;
    return inst->curve_values[static_cast<size_t>(index)];
}

int32_t cg_instance_root_motion(const cg_instance* inst, cr_root_motion* out) {
    if (inst == nullptr || out == nullptr) return fail(CG_ERR_ARG, "cg_instance_root_motion: bad arguments");
    *out = inst->root_motion;
    return CG_OK;
}

int32_t cg_instance_debug(const cg_instance* inst, cg_node_debug* out) {
    if (inst == nullptr || out == nullptr) return fail(CG_ERR_ARG, "cg_instance_debug: bad arguments");
    const cg_graph& g = *inst->g;
    for (size_t i = 0; i < g.nodes.size(); ++i) {
        const CNode& c = g.nodes[i];
        const NodeRt& r = inst->rt[i];
        cg_node_debug d{};
        d.influence = r.influence;
        d.progress = r.progress;
        d.state = -1;
        if (r.active) d.flags |= CG_DEBUG_ACTIVE;
        if (c.type == og::NodeType::Clip) {
            d.time = inst->players[i].time;
            if (inst->slots[static_cast<size_t>(c.clip_slot)].rig_clip != nullptr) d.flags |= CG_DEBUG_BOUND;
        } else if (c.type == og::NodeType::StateMachine) {
            d.state = inst->machines[static_cast<size_t>(c.machine)].current;
        }
        out[i] = d;
    }
    return CG_OK;
}

int32_t cg_instance_debug_weights(const cg_instance* inst, int32_t node, float* out, int32_t capacity) {
    if (inst == nullptr || node < 0 || static_cast<size_t>(node) >= inst->g->nodes.size()) {
        return fail(CG_ERR_ARG, "cg_instance_debug_weights: bad node");
    }
    const CNode& c = inst->g->nodes[static_cast<size_t>(node)];
    if (c.weights_at < 0) return fail(CG_ERR_ARG, "cg_instance_debug_weights: node " + c.id + " has no weighted inputs");
    const float* w = inst->weights.data() + c.weights_at;
    std::vector<float> v;
    if (c.type == og::NodeType::Layer) {
        float base = 1.f;
        for (size_t k = 0; k < c.layers.size(); ++k) {
            const CLayer& l = c.layers[k];
            if (l.mode == og::LayerMode::Override && l.mask < 0 && !l.first_person) base *= 1.f - w[k];
        }
        v.push_back(base);
        v.insert(v.end(), w, w + c.layers.size());
    } else {
        v.assign(w, w + c.children.size());
    }
    const auto n = static_cast<int32_t>(v.size());
    if (out != nullptr) std::memcpy(out, v.data(), static_cast<size_t>(std::min(n, std::max(capacity, 0))) * sizeof(float));
    return n;
}

}  // extern "C"
