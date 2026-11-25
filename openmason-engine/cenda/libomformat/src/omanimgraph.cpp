#include "omformat/omanimgraph.hpp"

#include "json_util.hpp"

#include <algorithm>
#include <charconv>
#include <cmath>
#include <functional>
#include <unordered_map>
#include <unordered_set>

namespace cenda::omformat::omanimgraph {

namespace {

using json::CharPtr;
using json::DocPtr;
using json::MutDocPtr;

[[noreturn]] void fail(const std::string& message) { throw format_error(".omanimgraph: " + message); }

std::string str(yyjson_val* v) { return {yyjson_get_str(v), yyjson_get_len(v)}; }

yyjson_val* field(yyjson_val* obj, const char* key) {
    yyjson_val* v = yyjson_obj_get(obj, key);
    return v == nullptr || yyjson_is_null(v) ? nullptr : v;
}

std::string str_field(yyjson_val* obj, const char* key, const std::string& where, bool required) {
    yyjson_val* v = field(obj, key);
    if (v == nullptr) {
        if (required) fail(where + ": missing \"" + key + "\"");
        return {};
    }
    if (!yyjson_is_str(v)) fail(where + ": \"" + key + "\" must be a string");
    return str(v);
}

double num_field(yyjson_val* obj, const char* key, const std::string& where, double fallback) {
    yyjson_val* v = field(obj, key);
    if (v == nullptr) return fallback;
    if (!yyjson_is_num(v) || !std::isfinite(yyjson_get_num(v))) fail(where + ": \"" + key + "\" must be a number");
    return yyjson_get_num(v);
}

bool bool_field(yyjson_val* obj, const char* key, const std::string& where, bool fallback) {
    yyjson_val* v = field(obj, key);
    if (v == nullptr) return fallback;
    if (!yyjson_is_bool(v)) fail(where + ": \"" + key + "\" must be true or false");
    return yyjson_get_bool(v);
}

yyjson_val* arr_field(yyjson_val* obj, const char* key, const std::string& where, bool required) {
    yyjson_val* v = field(obj, key);
    if (v == nullptr) {
        if (required) fail(where + ": missing \"" + key + "\"");
        return nullptr;
    }
    if (!yyjson_is_arr(v)) fail(where + ": \"" + key + "\" must be an array");
    return v;
}

void parse_version(const std::string& version) {
    const auto dot = version.find('.');
    int major = -1;
    int minor = -1;
    const char* b = version.data();
    const char* e = b + version.size();
    bool ok = dot != std::string::npos && dot > 0;
    if (ok) {
        auto [p1, e1] = std::from_chars(b, b + dot, major);
        auto [p2, e2] = std::from_chars(b + dot + 1, e, minor);
        ok = e1 == std::errc{} && p1 == b + dot && e2 == std::errc{} && p2 == e && major >= 1 && minor >= 0;
    }
    if (!ok) fail("version \"" + version + "\" is not <major>.<minor>");
    if (major > kVersionMajor) {
        throw version_error(".omanimgraph version " + version + " is newer than this reader (" +
                            std::to_string(kVersionMajor) + "." + std::to_string(kVersionMinor) + ")");
    }
}

// ---- names <-> enums ------------------------------------------------------------------------------

const char* param_type_name(ParamType t) {
    switch (t) {
        case ParamType::Int: return "int";
        case ParamType::Bool: return "bool";
        case ParamType::Trigger: return "trigger";
        case ParamType::Vec2: return "vec2";
        default: return "float";
    }
}

ParamType parse_param_type(const std::string& s, const std::string& where) {
    if (s == "float") return ParamType::Float;
    if (s == "int") return ParamType::Int;
    if (s == "bool") return ParamType::Bool;
    if (s == "trigger") return ParamType::Trigger;
    if (s == "vec2") return ParamType::Vec2;
    fail(where + ": type must be float, int, bool, trigger or vec2");
}

const char* node_type_name(NodeType t) {
    switch (t) {
        case NodeType::Blend1D: return "blend1d";
        case NodeType::Blend2D: return "blend2d";
        case NodeType::StateMachine: return "stateMachine";
        case NodeType::Layer: return "layer";
        case NodeType::Slot: return "slot";
        default: return "clip";
    }
}

NodeType parse_node_type(const std::string& s, const std::string& where) {
    if (s == "clip") return NodeType::Clip;
    if (s == "blend1d") return NodeType::Blend1D;
    if (s == "blend2d") return NodeType::Blend2D;
    if (s == "stateMachine") return NodeType::StateMachine;
    if (s == "layer") return NodeType::Layer;
    if (s == "slot") return NodeType::Slot;
    fail(where + ": unknown node type \"" + s + "\"");
}

const char* op_name(CondOp op) {
    switch (op) {
        case CondOp::Eq: return "==";
        case CondOp::Ne: return "!=";
        case CondOp::Gt: return ">";
        case CondOp::Lt: return "<";
        case CondOp::Ge: return ">=";
        case CondOp::Le: return "<=";
        default: return "";
    }
}

CondOp parse_op(const std::string& s, const std::string& where) {
    if (s.empty()) return CondOp::True;
    for (CondOp op : {CondOp::Eq, CondOp::Ne, CondOp::Gt, CondOp::Lt, CondOp::Ge, CondOp::Le}) {
        if (s == op_name(op)) return op;
    }
    fail(where + ": op must be ==, !=, >, <, >= or <=");
}

// ---- parsing ---------------------------------------------------------------------------------------

Param parse_param(yyjson_val* p, std::size_t i) {
    const std::string where = "parameters[" + std::to_string(i) + "]";
    if (!yyjson_is_obj(p)) fail(where + " must be an object");
    Param out;
    out.name = str_field(p, "name", where, true);
    if (out.name.empty()) fail(where + ": empty name");
    const std::string at = "parameter " + out.name;
    out.type = parse_param_type(str_field(p, "type", at, true), at);
    if (yyjson_val* d = field(p, "default")) {
        switch (out.type) {
            case ParamType::Vec2:
                if (!yyjson_is_arr(d) || yyjson_arr_size(d) != 2) fail(at + ": default must be [x, y]");
                for (std::size_t c = 0; c < 2; ++c) {
                    yyjson_val* v = yyjson_arr_get(d, c);
                    if (!yyjson_is_num(v) || !std::isfinite(yyjson_get_num(v))) fail(at + ": default must be numbers");
                    out.value[c] = yyjson_get_num(v);
                }
                break;
            case ParamType::Bool:
            case ParamType::Trigger:
                if (!yyjson_is_bool(d)) fail(at + ": default must be true or false");
                out.value[0] = yyjson_get_bool(d) ? 1.0 : 0.0;
                break;
            case ParamType::Int:
                if (!yyjson_is_int(d)) fail(at + ": default must be an integer");
                out.value[0] = static_cast<double>(yyjson_get_sint(d));
                break;
            default:
                if (!yyjson_is_num(d) || !std::isfinite(yyjson_get_num(d))) fail(at + ": default must be a number");
                out.value[0] = yyjson_get_num(d);
        }
    }
    return out;
}

Mask parse_mask(yyjson_val* m, std::size_t i) {
    const std::string where = "masks[" + std::to_string(i) + "]";
    if (!yyjson_is_obj(m)) fail(where + " must be an object");
    Mask out;
    out.name = str_field(m, "name", where, true);
    if (out.name.empty()) fail(where + ": empty name");
    const std::string at = "mask " + out.name;
    yyjson_val* entries = arr_field(m, "entries", at, true);
    for (std::size_t k = 0, n = yyjson_arr_size(entries); k < n; ++k) {
        yyjson_val* e = yyjson_arr_get(entries, k);
        const std::string ew = at + " entries[" + std::to_string(k) + "]";
        if (!yyjson_is_obj(e)) fail(ew + " must be an object");
        MaskEntry me;
        me.role = str_field(e, "role", ew, false);
        me.joint = str_field(e, "joint", ew, false);
        if (me.role.empty() == me.joint.empty()) fail(ew + ": give exactly one of \"role\" or \"joint\"");
        me.weight = static_cast<float>(num_field(e, "weight", ew, 1.0));
        if (me.weight < 0.f || me.weight > 1.f) fail(ew + ": weight must be in [0, 1]");
        me.branch = bool_field(e, "branch", ew, true);
        const double depth = num_field(e, "depth", ew, 0.0);
        if (depth < 0 || depth > 64 || depth != std::floor(depth)) fail(ew + ": depth must be an integer 0..64");
        me.depth = static_cast<int>(depth);
        out.entries.push_back(std::move(me));
    }
    return out;
}

Condition parse_condition(yyjson_val* c, const std::string& where) {
    if (!yyjson_is_obj(c)) fail(where + " must be an object");
    Condition out;
    out.param = str_field(c, "param", where, true);
    out.op = parse_op(str_field(c, "op", where, false), where);
    if (yyjson_val* v = field(c, "value")) {
        if (yyjson_is_bool(v)) {
            out.value = yyjson_get_bool(v) ? 1.0 : 0.0;
        } else if (yyjson_is_num(v) && std::isfinite(yyjson_get_num(v))) {
            out.value = yyjson_get_num(v);
        } else {
            fail(where + ": value must be a number or a boolean");
        }
    } else if (out.op != CondOp::True) {
        fail(where + ": op " + op_name(out.op) + " needs a value");
    }
    return out;
}

Node parse_node(yyjson_val* v, std::size_t i) {
    const std::string where = "nodes[" + std::to_string(i) + "]";
    if (!yyjson_is_obj(v)) fail(where + " must be an object");
    Node n;
    n.id = str_field(v, "id", where, true);
    if (n.id.empty()) fail(where + ": empty id");
    const std::string at = "node " + n.id;
    n.type = parse_node_type(str_field(v, "type", at, true), at);
    switch (n.type) {
        case NodeType::Clip:
            n.slot = str_field(v, "slot", at, true);
            n.rate = static_cast<float>(num_field(v, "rate", at, 1.0));
            n.rate_param = str_field(v, "rateParam", at, false);
            n.sync = str_field(v, "sync", at, false);
            break;
        case NodeType::Blend1D:
        case NodeType::Blend2D: {
            const bool two = n.type == NodeType::Blend2D;
            n.param = str_field(v, two ? "param" : "param", at, !two);
            if (two) {
                const std::string px = str_field(v, "paramX", at, false);
                n.param_y = str_field(v, "paramY", at, false);
                if (!px.empty() || !n.param_y.empty()) {
                    if (!n.param.empty() || px.empty() || n.param_y.empty()) {
                        fail(at + ": give \"param\" (vec2) or both \"paramX\" and \"paramY\"");
                    }
                    n.param = px;
                } else if (n.param.empty()) {
                    fail(at + ": missing \"param\"");
                }
                const std::string mode = str_field(v, "mode", at, false);
                if (mode.empty() || mode == "cartesian") {
                    n.mode2d = Blend2DMode::Cartesian;
                } else if (mode == "directional") {
                    n.mode2d = Blend2DMode::Directional;
                } else {
                    fail(at + ": mode must be cartesian or directional");
                }
            }
            yyjson_val* samples = arr_field(v, "samples", at, true);
            for (std::size_t k = 0, c = yyjson_arr_size(samples); k < c; ++k) {
                yyjson_val* s = yyjson_arr_get(samples, k);
                const std::string sw = at + " samples[" + std::to_string(k) + "]";
                if (!yyjson_is_obj(s)) fail(sw + " must be an object");
                Sample smp;
                smp.node = str_field(s, "node", sw, true);
                if (two) {
                    if (field(s, "x") == nullptr || field(s, "y") == nullptr) fail(sw + ": needs x and y");
                    smp.x = static_cast<float>(num_field(s, "x", sw, 0.0));
                    smp.y = static_cast<float>(num_field(s, "y", sw, 0.0));
                } else {
                    if (field(s, "value") == nullptr) fail(sw + ": needs a value");
                    smp.x = static_cast<float>(num_field(s, "value", sw, 0.0));
                }
                n.samples.push_back(std::move(smp));
            }
            if (n.samples.empty()) fail(at + ": a blend needs samples");
            if (!two) {
                std::stable_sort(n.samples.begin(), n.samples.end(), [](const Sample& a, const Sample& b) { return a.x < b.x; });
            }
            break;
        }
        case NodeType::StateMachine: {
            n.entry = str_field(v, "entry", at, true);
            yyjson_val* states = arr_field(v, "states", at, true);
            for (std::size_t k = 0, c = yyjson_arr_size(states); k < c; ++k) {
                yyjson_val* s = yyjson_arr_get(states, k);
                const std::string sw = at + " states[" + std::to_string(k) + "]";
                if (!yyjson_is_obj(s)) fail(sw + " must be an object");
                State st;
                st.name = str_field(s, "name", sw, true);
                if (st.name.empty() || st.name == "*") fail(sw + ": bad state name");
                st.conduit = bool_field(s, "conduit", sw, false);
                st.node = str_field(s, "node", sw, !st.conduit);
                if (st.conduit && !st.node.empty()) fail(sw + ": a conduit has no node");
                n.states.push_back(std::move(st));
            }
            if (yyjson_val* ts = arr_field(v, "transitions", at, false)) {
                for (std::size_t k = 0, c = yyjson_arr_size(ts); k < c; ++k) {
                    yyjson_val* t = yyjson_arr_get(ts, k);
                    const std::string tw = at + " transitions[" + std::to_string(k) + "]";
                    if (!yyjson_is_obj(t)) fail(tw + " must be an object");
                    Transition tr;
                    tr.from = str_field(t, "from", tw, true);
                    tr.to = str_field(t, "to", tw, true);
                    if (yyjson_val* cs = arr_field(t, "conditions", tw, false)) {
                        for (std::size_t q = 0, cc = yyjson_arr_size(cs); q < cc; ++q) {
                            tr.conditions.push_back(parse_condition(yyjson_arr_get(cs, q),
                                                                    tw + " conditions[" + std::to_string(q) + "]"));
                        }
                    }
                    const double prio = num_field(t, "priority", tw, 0.0);
                    if (prio != std::floor(prio) || std::fabs(prio) > 1e6) fail(tw + ": priority must be an integer");
                    tr.priority = static_cast<int>(prio);
                    tr.blend = static_cast<float>(num_field(t, "blend", tw, 0.2));
                    if (tr.blend < 0.f) fail(tw + ": blend must be >= 0");
                    const std::string mode = str_field(t, "mode", tw, false);
                    if (mode.empty() || mode == "inertialize") {
                        tr.mode = BlendMode::Inertialize;
                    } else if (mode == "crossfade") {
                        tr.mode = BlendMode::Crossfade;
                    } else {
                        fail(tw + ": mode must be inertialize or crossfade");
                    }
                    tr.exit_time = static_cast<float>(num_field(t, "exitTime", tw, -1.0));
                    tr.can_interrupt = bool_field(t, "canInterrupt", tw, true);
                    n.transitions.push_back(std::move(tr));
                }
            }
            break;
        }
        case NodeType::Layer: {
            n.base = str_field(v, "base", at, true);
            yyjson_val* ls = arr_field(v, "layers", at, true);
            for (std::size_t k = 0, c = yyjson_arr_size(ls); k < c; ++k) {
                yyjson_val* l = yyjson_arr_get(ls, k);
                const std::string lw = at + " layers[" + std::to_string(k) + "]";
                if (!yyjson_is_obj(l)) fail(lw + " must be an object");
                LayerEntry le;
                le.node = str_field(l, "node", lw, true);
                const std::string mode = str_field(l, "mode", lw, false);
                if (mode.empty() || mode == "override") {
                    le.mode = LayerMode::Override;
                } else if (mode == "additive") {
                    le.mode = LayerMode::Additive;
                } else {
                    fail(lw + ": mode must be override or additive");
                }
                le.mask = str_field(l, "mask", lw, false);
                le.weight = static_cast<float>(num_field(l, "weight", lw, 1.0));
                le.weight_param = str_field(l, "weightParam", lw, false);
                le.passthrough = bool_field(l, "passthrough", lw, true);
                le.mesh_space = bool_field(l, "meshSpace", lw, false);
                const std::string ref = str_field(l, "reference", lw, false);
                if (ref.empty() || ref == "rest") {
                    le.reference = Reference::Rest;
                } else if (ref == "character") {
                    le.reference = Reference::Character;
                } else {
                    fail(lw + ": reference must be rest or character");
                }
                n.layers.push_back(std::move(le));
            }
            break;
        }
        case NodeType::Slot:
            n.name = str_field(v, "name", at, true);
            n.source = str_field(v, "source", at, true);
            n.mask = str_field(v, "mask", at, false);
            break;
    }
    return n;
}

// The child node ids of a node, in slot order.
std::vector<std::string> children(const Node& n) {
    std::vector<std::string> out;
    switch (n.type) {
        case NodeType::Blend1D:
        case NodeType::Blend2D:
            for (const Sample& s : n.samples) out.push_back(s.node);
            break;
        case NodeType::StateMachine:
            for (const State& s : n.states) {
                if (!s.conduit) out.push_back(s.node);
            }
            break;
        case NodeType::Layer:
            out.push_back(n.base);
            for (const LayerEntry& l : n.layers) out.push_back(l.node);
            break;
        case NodeType::Slot:
            out.push_back(n.source);
            break;
        default:
            break;
    }
    return out;
}

// ---- writing ---------------------------------------------------------------------------------------

yyjson_mut_val* mstr(yyjson_mut_doc* d, const std::string& s) { return yyjson_mut_strncpy(d, s.data(), s.size()); }

void put(yyjson_mut_doc* d, yyjson_mut_val* obj, const char* key, yyjson_mut_val* val) {
    if (!val || !yyjson_mut_obj_add(obj, yyjson_mut_strcpy(d, key), val)) fail("out of memory");
}

void put_str(yyjson_mut_doc* d, yyjson_mut_val* obj, const char* key, const std::string& s) {
    if (!s.empty()) put(d, obj, key, mstr(d, s));
}

yyjson_mut_val* mnum(yyjson_mut_doc* d, double v) {
    if (v == std::floor(v) && std::fabs(v) < 1e15) return yyjson_mut_sint(d, static_cast<int64_t>(v));
    return yyjson_mut_float(d, static_cast<float>(v));
}

}  // namespace

int Graph::find_node(std::string_view id) const {
    for (std::size_t i = 0; i < nodes.size(); ++i) {
        if (nodes[i].id == id) return static_cast<int>(i);
    }
    return -1;
}

int Graph::find_param(std::string_view n) const {
    for (std::size_t i = 0; i < params.size(); ++i) {
        if (params[i].name == n) return static_cast<int>(i);
    }
    return -1;
}

int Graph::find_slot(std::string_view n) const {
    for (std::size_t i = 0; i < slots.size(); ++i) {
        if (slots[i] == n) return static_cast<int>(i);
    }
    return -1;
}

int Graph::find_mask(std::string_view n) const {
    for (std::size_t i = 0; i < masks.size(); ++i) {
        if (masks[i].name == n) return static_cast<int>(i);
    }
    return -1;
}

void validate(const Graph& g) {
    std::unordered_set<std::string> seen;
    for (const Param& p : g.params) {
        if (!seen.insert(p.name).second) fail("duplicate parameter " + p.name);
    }
    seen.clear();
    for (const std::string& s : g.slots) {
        if (s.empty()) fail("empty slot name");
        if (!seen.insert(s).second) fail("duplicate slot " + s);
    }
    seen.clear();
    for (const Mask& m : g.masks) {
        if (!seen.insert(m.name).second) fail("duplicate mask " + m.name);
    }
    seen.clear();
    for (const Node& n : g.nodes) {
        if (!seen.insert(n.id).second) fail("duplicate node " + n.id);
    }
    if (g.nodes.empty()) fail("no nodes");
    if (g.find_node(g.root) < 0) fail("root \"" + g.root + "\" is not a node");
    if (!(g.notify_threshold >= 0.f && g.notify_threshold <= 1.f)) fail("notifyThreshold must be in [0, 1]");

    auto param_of = [&](const std::string& name, const std::string& at, std::initializer_list<ParamType> types) {
        const int i = g.find_param(name);
        if (i < 0) fail(at + ": unknown parameter " + name);
        const ParamType t = g.params[static_cast<std::size_t>(i)].type;
        for (ParamType ok : types) {
            if (t == ok) return;
        }
        fail(at + ": parameter " + name + " has the wrong type (" + param_type_name(t) + ")");
    };
    auto mask_of = [&](const std::string& name, const std::string& at) {
        if (!name.empty() && g.find_mask(name) < 0) fail(at + ": unknown mask " + name);
    };

    std::unordered_map<std::string, std::string> parent;  // child id -> parent id
    std::unordered_set<std::string> slot_names;
    for (const Node& n : g.nodes) {
        const std::string at = "node " + n.id;
        for (const std::string& c : children(n)) {
            if (g.find_node(c) < 0) fail(at + ": unknown node " + c);
            if (c == n.id) fail(at + " uses itself");
            if (c == g.root) fail(at + " uses the root node " + c);
            if (!parent.emplace(c, n.id).second) fail("node " + c + " is used by both " + parent[c] + " and " + n.id);
        }
        switch (n.type) {
            case NodeType::Clip:
                if (g.find_slot(n.slot) < 0) fail(at + ": unknown slot " + n.slot);
                if (!std::isfinite(n.rate)) fail(at + ": bad rate");
                if (!n.rate_param.empty()) param_of(n.rate_param, at, {ParamType::Float});
                break;
            case NodeType::Blend1D:
                param_of(n.param, at, {ParamType::Float, ParamType::Int});
                break;
            case NodeType::Blend2D:
                if (n.param_y.empty()) {
                    param_of(n.param, at, {ParamType::Vec2});
                } else {
                    param_of(n.param, at, {ParamType::Float});
                    param_of(n.param_y, at, {ParamType::Float});
                }
                break;
            case NodeType::StateMachine: {
                std::unordered_set<std::string> names;
                bool any_real = false;
                for (const State& s : n.states) {
                    if (!names.insert(s.name).second) fail(at + ": duplicate state " + s.name);
                    any_real |= !s.conduit;
                }
                if (!any_real) fail(at + ": needs a state with a node");
                if (!names.contains(n.entry)) fail(at + ": entry \"" + n.entry + "\" is not a state");
                for (const State& s : n.states) {
                    if (s.name == n.entry && s.conduit) fail(at + ": the entry state cannot be a conduit");
                }
                for (const Transition& t : n.transitions) {
                    const std::string tw = at + " transition " + t.from + " -> " + t.to;
                    if (t.from != "*" && !names.contains(t.from)) fail(tw + ": unknown state " + t.from);
                    if (!names.contains(t.to)) fail(tw + ": unknown state " + t.to);
                    for (const Condition& c : t.conditions) {
                        param_of(c.param, tw, {ParamType::Float, ParamType::Int, ParamType::Bool, ParamType::Trigger});
                    }
                }
                break;
            }
            case NodeType::Layer:
                for (const LayerEntry& l : n.layers) {
                    mask_of(l.mask, at);
                    if (!l.weight_param.empty()) param_of(l.weight_param, at, {ParamType::Float});
                    if (!std::isfinite(l.weight)) fail(at + ": bad layer weight");
                }
                break;
            case NodeType::Slot:
                if (n.name.empty()) fail(at + ": empty slot name");
                if (!slot_names.insert(n.name).second) fail("two montage slots named " + n.name);
                mask_of(n.mask, at);
                break;
        }
    }
    // Every node reachable from the root sits in a tree (parents are unique, the root has none); a cycle can
    // only hide among unreachable nodes: walk up from each node.
    for (const Node& n : g.nodes) {
        std::string cur = n.id;
        for (std::size_t steps = 0; steps <= g.nodes.size(); ++steps) {
            auto it = parent.find(cur);
            if (it == parent.end()) break;
            cur = it->second;
            if (cur == n.id) fail("node " + n.id + " is part of a cycle");
        }
    }
}

Graph read(std::string_view text) {
    yyjson_read_err err{};
    DocPtr doc(yyjson_read_opts(const_cast<char*>(text.data()), text.size(), YYJSON_READ_NOFLAG, nullptr, &err));
    if (!doc) fail(std::string("not valid JSON (") + err.msg + " at byte " + std::to_string(err.pos) + ")");
    yyjson_val* root = yyjson_doc_get_root(doc.get());
    if (!yyjson_is_obj(root)) fail("must be a JSON object");
    if (str_field(root, "format", "graph", true) != "omanimgraph") fail("format is not \"omanimgraph\"");
    parse_version(str_field(root, "version", "graph", true));

    Graph g;
    g.name = str_field(root, "name", "graph", false);
    g.description = str_field(root, "description", "graph", false);
    g.profile = str_field(root, "profile", "graph", false);
    g.notify_threshold = static_cast<float>(num_field(root, "notifyThreshold", "graph", 0.1));
    if (yyjson_val* ps = arr_field(root, "parameters", "graph", false)) {
        for (std::size_t i = 0, n = yyjson_arr_size(ps); i < n; ++i) g.params.push_back(parse_param(yyjson_arr_get(ps, i), i));
    }
    if (yyjson_val* ss = arr_field(root, "slots", "graph", false)) {
        for (std::size_t i = 0, n = yyjson_arr_size(ss); i < n; ++i) {
            yyjson_val* s = yyjson_arr_get(ss, i);
            if (!yyjson_is_str(s)) fail("slots must be strings");
            g.slots.push_back(str(s));
        }
    }
    if (yyjson_val* ms = arr_field(root, "masks", "graph", false)) {
        for (std::size_t i = 0, n = yyjson_arr_size(ms); i < n; ++i) g.masks.push_back(parse_mask(yyjson_arr_get(ms, i), i));
    }
    g.root = str_field(root, "root", "graph", true);
    yyjson_val* nodes = arr_field(root, "nodes", "graph", true);
    for (std::size_t i = 0, n = yyjson_arr_size(nodes); i < n; ++i) g.nodes.push_back(parse_node(yyjson_arr_get(nodes, i), i));

    static const std::unordered_set<std::string> known = {"format", "version", "name", "description", "profile",
                                                          "notifyThreshold", "parameters", "slots", "masks", "root",
                                                          "nodes"};
    std::unordered_set<std::string> keys;
    yyjson_obj_iter it = yyjson_obj_iter_with(root);
    yyjson_val* key;
    while ((key = yyjson_obj_iter_next(&it)) != nullptr) {
        std::string k = str(key);
        if (!keys.insert(k).second) fail("duplicate key \"" + k + "\"");
        if (known.contains(k)) continue;
        yyjson_val* val = yyjson_obj_iter_get_val(key);
        std::size_t len = 0;
        CharPtr s(yyjson_val_write(val, YYJSON_WRITE_NOFLAG, &len));
        if (!s) fail("out of memory");
        g.unknown_sections.push_back({std::move(k), std::string(s.get(), len)});
    }
    validate(g);
    return g;
}

std::string write(const Graph& g) {
    validate(g);
    MutDocPtr doc(yyjson_mut_doc_new(nullptr));
    if (!doc) fail("out of memory");
    yyjson_mut_doc* d = doc.get();
    yyjson_mut_val* root = yyjson_mut_obj(d);
    yyjson_mut_doc_set_root(d, root);
    put(d, root, "format", yyjson_mut_str(d, "omanimgraph"));
    put(d, root, "version", mstr(d, std::to_string(kVersionMajor) + "." + std::to_string(kVersionMinor)));
    put_str(d, root, "name", g.name);
    put_str(d, root, "description", g.description);
    put_str(d, root, "profile", g.profile);
    if (g.notify_threshold != 0.1f) put(d, root, "notifyThreshold", yyjson_mut_float(d, g.notify_threshold));

    yyjson_mut_val* params = yyjson_mut_arr(d);
    for (const Param& p : g.params) {
        yyjson_mut_val* o = yyjson_mut_obj(d);
        put(d, o, "name", mstr(d, p.name));
        put(d, o, "type", yyjson_mut_str(d, param_type_name(p.type)));
        switch (p.type) {
            case ParamType::Vec2:
                if (p.value[0] != 0.0 || p.value[1] != 0.0) {
                    yyjson_mut_val* a = yyjson_mut_arr(d);
                    yyjson_mut_arr_append(a, mnum(d, p.value[0]));
                    yyjson_mut_arr_append(a, mnum(d, p.value[1]));
                    put(d, o, "default", a);
                }
                break;
            case ParamType::Bool:
                if (p.value[0] != 0.0) put(d, o, "default", yyjson_mut_bool(d, true));
                break;
            case ParamType::Trigger:
                break;
            case ParamType::Int:
                if (p.value[0] != 0.0) put(d, o, "default", yyjson_mut_sint(d, static_cast<int64_t>(p.value[0])));
                break;
            default:
                if (p.value[0] != 0.0) put(d, o, "default", mnum(d, p.value[0]));
        }
        yyjson_mut_arr_append(params, o);
    }
    put(d, root, "parameters", params);

    yyjson_mut_val* slots = yyjson_mut_arr(d);
    for (const std::string& s : g.slots) yyjson_mut_arr_append(slots, mstr(d, s));
    put(d, root, "slots", slots);

    if (!g.masks.empty()) {
        yyjson_mut_val* masks = yyjson_mut_arr(d);
        for (const Mask& m : g.masks) {
            yyjson_mut_val* o = yyjson_mut_obj(d);
            put(d, o, "name", mstr(d, m.name));
            yyjson_mut_val* es = yyjson_mut_arr(d);
            for (const MaskEntry& e : m.entries) {
                yyjson_mut_val* eo = yyjson_mut_obj(d);
                put_str(d, eo, "role", e.role);
                put_str(d, eo, "joint", e.joint);
                if (e.weight != 1.f) put(d, eo, "weight", yyjson_mut_float(d, e.weight));
                if (!e.branch) put(d, eo, "branch", yyjson_mut_bool(d, false));
                if (e.depth != 0) put(d, eo, "depth", yyjson_mut_sint(d, e.depth));
                yyjson_mut_arr_append(es, eo);
            }
            put(d, o, "entries", es);
            yyjson_mut_arr_append(masks, o);
        }
        put(d, root, "masks", masks);
    }

    put(d, root, "root", mstr(d, g.root));
    yyjson_mut_val* nodes = yyjson_mut_arr(d);
    for (const Node& n : g.nodes) {
        yyjson_mut_val* o = yyjson_mut_obj(d);
        put(d, o, "id", mstr(d, n.id));
        put(d, o, "type", yyjson_mut_str(d, node_type_name(n.type)));
        switch (n.type) {
            case NodeType::Clip:
                put(d, o, "slot", mstr(d, n.slot));
                if (n.rate != 1.f) put(d, o, "rate", yyjson_mut_float(d, n.rate));
                put_str(d, o, "rateParam", n.rate_param);
                put_str(d, o, "sync", n.sync);
                break;
            case NodeType::Blend1D:
            case NodeType::Blend2D: {
                const bool two = n.type == NodeType::Blend2D;
                if (two && !n.param_y.empty()) {
                    put(d, o, "paramX", mstr(d, n.param));
                    put(d, o, "paramY", mstr(d, n.param_y));
                } else {
                    put(d, o, "param", mstr(d, n.param));
                }
                if (two && n.mode2d == Blend2DMode::Directional) put(d, o, "mode", yyjson_mut_str(d, "directional"));
                yyjson_mut_val* ss = yyjson_mut_arr(d);
                for (const Sample& s : n.samples) {
                    yyjson_mut_val* so = yyjson_mut_obj(d);
                    put(d, so, "node", mstr(d, s.node));
                    if (two) {
                        put(d, so, "x", yyjson_mut_float(d, s.x));
                        put(d, so, "y", yyjson_mut_float(d, s.y));
                    } else {
                        put(d, so, "value", yyjson_mut_float(d, s.x));
                    }
                    yyjson_mut_arr_append(ss, so);
                }
                put(d, o, "samples", ss);
                break;
            }
            case NodeType::StateMachine: {
                put(d, o, "entry", mstr(d, n.entry));
                yyjson_mut_val* ss = yyjson_mut_arr(d);
                for (const State& s : n.states) {
                    yyjson_mut_val* so = yyjson_mut_obj(d);
                    put(d, so, "name", mstr(d, s.name));
                    if (s.conduit) {
                        put(d, so, "conduit", yyjson_mut_bool(d, true));
                    } else {
                        put(d, so, "node", mstr(d, s.node));
                    }
                    yyjson_mut_arr_append(ss, so);
                }
                put(d, o, "states", ss);
                yyjson_mut_val* ts = yyjson_mut_arr(d);
                for (const Transition& t : n.transitions) {
                    yyjson_mut_val* to = yyjson_mut_obj(d);
                    put(d, to, "from", mstr(d, t.from));
                    put(d, to, "to", mstr(d, t.to));
                    if (!t.conditions.empty()) {
                        yyjson_mut_val* cs = yyjson_mut_arr(d);
                        for (const Condition& c : t.conditions) {
                            yyjson_mut_val* co = yyjson_mut_obj(d);
                            put(d, co, "param", mstr(d, c.param));
                            if (c.op != CondOp::True) {
                                put(d, co, "op", yyjson_mut_str(d, op_name(c.op)));
                                put(d, co, "value", mnum(d, c.value));
                            }
                            yyjson_mut_arr_append(cs, co);
                        }
                        put(d, to, "conditions", cs);
                    }
                    if (t.priority != 0) put(d, to, "priority", yyjson_mut_sint(d, t.priority));
                    put(d, to, "blend", yyjson_mut_float(d, t.blend));
                    put(d, to, "mode", yyjson_mut_str(d, t.mode == BlendMode::Crossfade ? "crossfade" : "inertialize"));
                    if (t.exit_time >= 0.f) put(d, to, "exitTime", yyjson_mut_float(d, t.exit_time));
                    if (!t.can_interrupt) put(d, to, "canInterrupt", yyjson_mut_bool(d, false));
                    yyjson_mut_arr_append(ts, to);
                }
                put(d, o, "transitions", ts);
                break;
            }
            case NodeType::Layer: {
                put(d, o, "base", mstr(d, n.base));
                yyjson_mut_val* ls = yyjson_mut_arr(d);
                for (const LayerEntry& l : n.layers) {
                    yyjson_mut_val* lo = yyjson_mut_obj(d);
                    put(d, lo, "node", mstr(d, l.node));
                    put(d, lo, "mode", yyjson_mut_str(d, l.mode == LayerMode::Additive ? "additive" : "override"));
                    put_str(d, lo, "mask", l.mask);
                    if (l.weight != 1.f) put(d, lo, "weight", yyjson_mut_float(d, l.weight));
                    put_str(d, lo, "weightParam", l.weight_param);
                    if (!l.passthrough) put(d, lo, "passthrough", yyjson_mut_bool(d, false));
                    if (l.mesh_space) put(d, lo, "meshSpace", yyjson_mut_bool(d, true));
                    if (l.reference == Reference::Character) put(d, lo, "reference", yyjson_mut_str(d, "character"));
                    yyjson_mut_arr_append(ls, lo);
                }
                put(d, o, "layers", ls);
                break;
            }
            case NodeType::Slot:
                put(d, o, "name", mstr(d, n.name));
                put(d, o, "source", mstr(d, n.source));
                put_str(d, o, "mask", n.mask);
                break;
        }
        yyjson_mut_arr_append(nodes, o);
    }
    put(d, root, "nodes", nodes);

    for (const UnknownSection& u : g.unknown_sections) {
        yyjson_read_err err{};
        DocPtr sub(yyjson_read_opts(const_cast<char*>(u.json.data()), u.json.size(), YYJSON_READ_NOFLAG, nullptr, &err));
        if (!sub) fail("unknown section \"" + u.key + "\" is not valid JSON");
        put(d, root, u.key.c_str(), yyjson_val_mut_copy(d, yyjson_doc_get_root(sub.get())));
    }

    std::size_t len = 0;
    yyjson_write_err err{};
    CharPtr text(yyjson_mut_write_opts(d, YYJSON_WRITE_PRETTY_TWO_SPACES | YYJSON_WRITE_NEWLINE_AT_END, nullptr, &len, &err));
    if (!text) fail(std::string("write: ") + err.msg);
    return {text.get(), len};
}

}  // namespace cenda::omformat::omanimgraph
