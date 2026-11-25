#include "omformat/omanimgraph.hpp"

#include "json_util.hpp"

#include <algorithm>
#include <charconv>
#include <cmath>
#include <cstring>
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

void vec3_field(yyjson_val* obj, const char* key, const std::string& where, float out[3]) {
    yyjson_val* v = field(obj, key);
    if (v == nullptr) return;
    if (!yyjson_is_arr(v) || yyjson_arr_size(v) != 3) fail(where + ": \"" + key + "\" must be [x, y, z]");
    for (std::size_t c = 0; c < 3; ++c) {
        yyjson_val* e = yyjson_arr_get(v, c);
        if (!yyjson_is_num(e) || !std::isfinite(yyjson_get_num(e))) fail(where + ": \"" + key + "\" must be numbers");
        out[c] = static_cast<float>(yyjson_get_num(e));
    }
}

const char* mod_type_name(ModType t) {
    switch (t) {
        case ModType::Fabrik: return "fabrik";
        case ModType::Ccd: return "ccd";
        case ModType::Aim: return "aim";
        case ModType::Constraint: return "constraint";
        case ModType::Spring: return "spring";
        case ModType::FullBody: return "fullBody";
        case ModType::OrientationWarp: return "orientationWarp";
        case ModType::StrideWarp: return "strideWarp";
        case ModType::FootPlacement: return "footPlacement";
        default: return "twoBone";
    }
}

const char* kind_name(ConstraintKind k) {
    switch (k) {
        case ConstraintKind::Orient: return "orient";
        case ConstraintKind::Parent: return "parent";
        case ConstraintKind::Aim: return "aim";
        case ConstraintKind::Copy: return "copy";
        default: return "point";
    }
}

Modifier parse_modifier(yyjson_val* v, std::size_t i) {
    const std::string at = "modifiers[" + std::to_string(i) + "]";
    if (!yyjson_is_obj(v)) fail(at + " must be an object");
    Modifier m;
    const std::string type = str_field(v, "type", at, true);
    bool known = false;
    for (ModType t : {ModType::TwoBone, ModType::Fabrik, ModType::Ccd, ModType::Aim, ModType::Constraint, ModType::Spring,
                      ModType::FullBody, ModType::OrientationWarp, ModType::StrideWarp, ModType::FootPlacement}) {
        if (type == mod_type_name(t)) {
            m.type = t;
            known = true;
        }
    }
    if (!known) fail(at + ": unknown modifier type \"" + type + "\"");
    m.weight = static_cast<float>(num_field(v, "weight", at, 1.0));
    if (!(m.weight >= 0.f && m.weight <= 1.f)) fail(at + ": weight must be in [0, 1]");
    m.weight_param = str_field(v, "weightParam", at, false);
    m.weight_curve = str_field(v, "weightCurve", at, false);
    auto iterations = [&]() {
        const double it = num_field(v, "iterations", at, 0.0);
        if (it < 0 || it > 1000 || it != std::floor(it)) fail(at + ": iterations must be an integer 0..1000");
        m.iterations = static_cast<int>(it);
    };
    switch (m.type) {
        case ModType::TwoBone:
            m.upper = str_field(v, "upper", at, true);
            m.mid = str_field(v, "mid", at, true);
            m.end = str_field(v, "end", at, true);
            m.target = str_field(v, "target", at, true);
            m.pole = str_field(v, "pole", at, false);
            m.soft = static_cast<float>(num_field(v, "soft", at, 0.0));
            if (m.soft < 0.f || m.soft > 1.f) fail(at + ": soft must be in [0, 1]");
            m.match_rotation = bool_field(v, "matchRotation", at, false);
            break;
        case ModType::Fabrik:
        case ModType::Ccd:
            m.start = str_field(v, "start", at, true);
            m.end = str_field(v, "end", at, true);
            m.target = str_field(v, "target", at, true);
            iterations();
            m.tolerance = static_cast<float>(num_field(v, "tolerance", at, 0.0));
            if (m.tolerance < 0.f) fail(at + ": tolerance must be >= 0");
            break;
        case ModType::Aim: {
            yyjson_val* js = arr_field(v, "joints", at, true);
            for (std::size_t k = 0, n = yyjson_arr_size(js); k < n; ++k) {
                yyjson_val* e = yyjson_arr_get(js, k);
                const std::string ew = at + " joints[" + std::to_string(k) + "]";
                if (!yyjson_is_obj(e)) fail(ew + " must be an object");
                AimJoint aj;
                aj.joint = str_field(e, "joint", ew, true);
                aj.weight = static_cast<float>(num_field(e, "weight", ew, 1.0));
                if (aj.weight < 0.f) fail(ew + ": weight must be >= 0");
                m.aim.push_back(std::move(aj));
            }
            if (m.aim.empty() || m.aim.size() > 16) fail(at + ": aim needs 1..16 joints");
            m.target = str_field(v, "target", at, true);
            vec3_field(v, "axis", at, m.axis);
            m.each = bool_field(v, "each", at, false);
            m.limits = bool_field(v, "limits", at, true);
            break;
        }
        case ModType::Constraint: {
            const std::string kind = str_field(v, "kind", at, true);
            known = false;
            for (ConstraintKind k : {ConstraintKind::Point, ConstraintKind::Orient, ConstraintKind::Parent,
                                     ConstraintKind::Aim, ConstraintKind::Copy}) {
                if (kind == kind_name(k)) {
                    m.kind = k;
                    known = true;
                }
            }
            if (!known) fail(at + ": kind must be point, orient, parent, aim or copy");
            m.joint = str_field(v, "joint", at, true);
            m.target = str_field(v, "target", at, true);
            vec3_field(v, "offset", at, m.offset);
            vec3_field(v, "offsetRot", at, m.offset_rot);
            vec3_field(v, "axis", at, m.axis);
            break;
        }
        case ModType::Spring:
            m.start = str_field(v, "start", at, true);
            m.end = str_field(v, "end", at, true);
            m.stiffness = static_cast<float>(num_field(v, "stiffness", at, 1.0));
            m.drag = static_cast<float>(num_field(v, "drag", at, 0.4));
            if (m.drag < 0.f || m.drag > 1.f) fail(at + ": drag must be in [0, 1]");
            vec3_field(v, "gravity", at, m.gravity);
            m.radius = static_cast<float>(num_field(v, "radius", at, 0.0));
            m.step = static_cast<float>(num_field(v, "step", at, 0.0));
            if (m.radius < 0.f || m.step < 0.f) fail(at + ": radius and step must be >= 0");
            break;
        case ModType::FullBody: {
            m.body = str_field(v, "body", at, true);
            yyjson_val* ls = arr_field(v, "limbs", at, true);
            for (std::size_t k = 0, n = yyjson_arr_size(ls); k < n; ++k) {
                yyjson_val* e = yyjson_arr_get(ls, k);
                const std::string lw = at + " limbs[" + std::to_string(k) + "]";
                if (!yyjson_is_obj(e)) fail(lw + " must be an object");
                Limb l;
                l.upper = str_field(e, "upper", lw, true);
                l.mid = str_field(e, "mid", lw, true);
                l.end = str_field(e, "end", lw, true);
                l.target = str_field(e, "target", lw, true);
                l.pull = static_cast<float>(num_field(e, "pull", lw, 1.0));
                l.reach = static_cast<float>(num_field(e, "reach", lw, 1.0));
                if (l.pull < 0.f || l.pull > 1.f || l.reach < 0.f || l.reach > 1.f) fail(lw + ": pull and reach must be in [0, 1]");
                m.limbs.push_back(std::move(l));
            }
            if (m.limbs.empty() || m.limbs.size() > 5) fail(at + ": a full-body modifier needs 1..5 limbs");
            m.pole = str_field(v, "hipGoal", at, false);
            m.hip_weight = static_cast<float>(num_field(v, "hipWeight", at, 1.0));
            iterations();
            break;
        }
        case ModType::OrientationWarp: {
            m.body = str_field(v, "hips", at, true);
            if (yyjson_val* js = arr_field(v, "spine", at, false)) {
                for (std::size_t k = 0, n = yyjson_arr_size(js); k < n; ++k) {
                    yyjson_val* e = yyjson_arr_get(js, k);
                    const std::string ew = at + " spine[" + std::to_string(k) + "]";
                    if (!yyjson_is_obj(e)) fail(ew + " must be an object");
                    AimJoint aj;
                    aj.joint = str_field(e, "joint", ew, true);
                    aj.weight = static_cast<float>(num_field(e, "weight", ew, 1.0));
                    if (aj.weight < 0.f) fail(ew + ": weight must be >= 0");
                    m.aim.push_back(std::move(aj));
                }
            }
            if (m.aim.size() > 15) fail(at + ": an orientation warp takes at most 15 spine joints");
            m.angle_param = str_field(v, "angleParam", at, true);
            m.max_angle = static_cast<float>(num_field(v, "maxAngle", at, 90.0));
            m.rate = static_cast<float>(num_field(v, "rate", at, 0.0));
            m.counter = static_cast<float>(num_field(v, "counter", at, 1.0));
            if (m.max_angle < 0.f || m.max_angle > 180.f) fail(at + ": maxAngle must be in [0, 180]");
            if (m.rate < 0.f) fail(at + ": rate must be >= 0");
            break;
        }
        case ModType::StrideWarp:
        case ModType::FootPlacement: {
            const bool foot = m.type == ModType::FootPlacement;
            m.body = str_field(v, "pelvis", at, true);
            yyjson_val* ls = arr_field(v, "legs", at, true);
            for (std::size_t k = 0, n = yyjson_arr_size(ls); k < n; ++k) {
                yyjson_val* e = yyjson_arr_get(ls, k);
                const std::string lw = at + " legs[" + std::to_string(k) + "]";
                if (!yyjson_is_obj(e)) fail(lw + " must be an object");
                Leg l;
                l.upper = str_field(e, "upper", lw, true);
                l.mid = str_field(e, "mid", lw, true);
                l.end = str_field(e, "end", lw, false);
                if (foot) {
                    l.lock_curve = str_field(e, "lockCurve", lw, false);
                    if (yyjson_val* mk = arr_field(e, "lockMarkers", lw, false)) {
                        if (yyjson_arr_size(mk) != 2 || !yyjson_is_str(yyjson_arr_get(mk, 0))
                            || !yyjson_is_str(yyjson_arr_get(mk, 1))) {
                            fail(lw + ": lockMarkers must be [down, up] marker names");
                        }
                        l.lock_down = yyjson_get_str(yyjson_arr_get(mk, 0));
                        l.lock_up = yyjson_get_str(yyjson_arr_get(mk, 1));
                        if (l.lock_down.empty() || l.lock_up.empty()) fail(lw + ": empty lockMarkers name");
                    }
                }
                m.legs.push_back(std::move(l));
            }
            if (m.legs.empty() || m.legs.size() > 5) fail(at + ": needs 1..5 legs");
            auto nonneg = [&](const char* key, double fallback) {
                const double x = num_field(v, key, at, fallback);
                if (x < 0.0) fail(at + ": " + key + " must be >= 0");
                return static_cast<float>(x);
            };
            if (!foot) {
                m.speed_param = str_field(v, "speedParam", at, true);
                m.direction_param = str_field(v, "directionParam", at, false);
                m.anim_speed = nonneg("animSpeed", 0.0);
                m.anim_speed_curve = str_field(v, "animSpeedCurve", at, false);
                m.node = str_field(v, "node", at, false);
                m.rate_min = nonneg("rateMin", 0.8);
                m.rate_max = nonneg("rateMax", 1.2);
                m.stride_min = nonneg("strideMin", 0.5);
                m.stride_max = nonneg("strideMax", 2.0);
                m.pelvis_weight = nonneg("pelvisWeight", 1.0);
                if (!(m.rate_min > 0.f) || m.rate_min > m.rate_max) fail(at + ": needs 0 < rateMin <= rateMax");
                if (m.stride_min > m.stride_max) fail(at + ": needs strideMin <= strideMax");
                if (m.pelvis_weight > 1.f) fail(at + ": pelvisWeight must be in [0, 1]");
            } else {
                m.probe_up = nonneg("probeUp", 0.0);
                m.probe_down = nonneg("probeDown", 0.0);
                m.max_drop = nonneg("maxDrop", 0.0);
                m.max_rise = nonneg("maxRise", 0.0);
                m.max_align = nonneg("maxAlign", 30.0);
                m.pelvis_rate = nonneg("pelvisRate", 10.0);
                m.foot_rate = nonneg("footRate", 15.0);
                m.ground_height = static_cast<float>(num_field(v, "groundHeight", at, 0.0));
                m.unlock_time = nonneg("unlockTime", 0.15);
                m.max_lock_distance = nonneg("maxLockDistance", 0.0);
                if (m.max_align > 90.f) fail(at + ": maxAlign must be in [0, 90]");
            }
            break;
        }
    }
    return m;
}

Collider parse_collider(yyjson_val* v, std::size_t i) {
    const std::string at = "colliders[" + std::to_string(i) + "]";
    if (!yyjson_is_obj(v)) fail(at + " must be an object");
    Collider c;
    c.joint = str_field(v, "joint", at, true);
    vec3_field(v, "a", at, c.a);
    if (field(v, "b") != nullptr) {
        vec3_field(v, "b", at, c.b);
        c.capsule = true;
    } else {
        std::copy(c.a, c.a + 3, c.b);
    }
    c.radius = static_cast<float>(num_field(v, "radius", at, 0.0));
    if (!(c.radius > 0.f)) fail(at + ": radius must be > 0");
    return c;
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

int Graph::find_target(std::string_view n) const {
    for (std::size_t i = 0; i < targets.size(); ++i) {
        if (targets[i] == n) return static_cast<int>(i);
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
    seen.clear();
    for (const std::string& t : g.targets) {
        if (t.empty() || t.find(':') != std::string::npos) fail("target names must be non-empty and hold no ':' (" + t + ")");
        if (!seen.insert(t).second) fail("duplicate target " + t);
    }
    auto target_ref = [&](const std::string& ref, const std::string& at, bool required) {
        if (ref.empty()) {
            if (required) fail(at + ": missing target");
            return;
        }
        for (const char* prefix : {"role:", "joint:", "socket:"}) {
            if (ref.rfind(prefix, 0) == 0) {
                if (ref.size() == std::strlen(prefix)) fail(at + ": empty target reference " + ref);
                return;
            }
        }
        if (g.find_target(ref) < 0) fail(at + ": unknown target " + ref);
    };
    for (const Collider& c : g.colliders) {
        if (c.joint.empty()) fail("a collider needs a joint");
    }
    for (std::size_t i = 0; i < g.modifiers.size(); ++i) {
        const Modifier& m = g.modifiers[i];
        const std::string at = "modifier " + std::to_string(i) + " (" + mod_type_name(m.type) + ")";
        if (!m.weight_param.empty()) param_of(m.weight_param, at, {ParamType::Float});
        switch (m.type) {
            case ModType::TwoBone:
                target_ref(m.target, at, true);
                target_ref(m.pole, at, false);
                break;
            case ModType::FullBody:
                for (const Limb& l : m.limbs) target_ref(l.target, at, true);
                target_ref(m.pole, at, false);
                break;
            case ModType::Spring:
                break;
            case ModType::OrientationWarp:
                param_of(m.angle_param, at, {ParamType::Float});
                break;
            case ModType::StrideWarp:
                param_of(m.speed_param, at, {ParamType::Float});
                if (!m.direction_param.empty()) param_of(m.direction_param, at, {ParamType::Vec2});
                if (!m.node.empty() && g.find_node(m.node) < 0) fail(at + ": unknown node " + m.node);
                break;
            case ModType::FootPlacement:
                break;
            default:
                target_ref(m.target, at, true);
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
    if (yyjson_val* ts = arr_field(root, "targets", "graph", false)) {
        for (std::size_t i = 0, n = yyjson_arr_size(ts); i < n; ++i) {
            yyjson_val* t = yyjson_arr_get(ts, i);
            if (!yyjson_is_str(t)) fail("targets must be strings");
            g.targets.push_back(str(t));
        }
    }
    if (yyjson_val* cs = arr_field(root, "colliders", "graph", false)) {
        for (std::size_t i = 0, n = yyjson_arr_size(cs); i < n; ++i) g.colliders.push_back(parse_collider(yyjson_arr_get(cs, i), i));
    }
    if (yyjson_val* ms = arr_field(root, "modifiers", "graph", false)) {
        for (std::size_t i = 0, n = yyjson_arr_size(ms); i < n; ++i) g.modifiers.push_back(parse_modifier(yyjson_arr_get(ms, i), i));
    }

    static const std::unordered_set<std::string> known = {"format", "version", "name", "description", "profile",
                                                          "notifyThreshold", "parameters", "slots", "masks", "root",
                                                          "nodes", "targets", "colliders", "modifiers"};
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

    auto vec3 = [&](const float* f) {
        yyjson_mut_val* arr = yyjson_mut_arr(d);
        for (int c = 0; c < 3; ++c) yyjson_mut_arr_append(arr, yyjson_mut_float(d, f[c]));
        return arr;
    };
    auto is = [](const float* f, float x, float y, float z) { return f[0] == x && f[1] == y && f[2] == z; };
    if (!g.targets.empty()) {
        yyjson_mut_val* ts = yyjson_mut_arr(d);
        for (const std::string& t : g.targets) yyjson_mut_arr_append(ts, mstr(d, t));
        put(d, root, "targets", ts);
    }
    if (!g.colliders.empty()) {
        yyjson_mut_val* cs = yyjson_mut_arr(d);
        for (const Collider& c : g.colliders) {
            yyjson_mut_val* o = yyjson_mut_obj(d);
            put(d, o, "joint", mstr(d, c.joint));
            put(d, o, "a", vec3(c.a));
            if (c.capsule) put(d, o, "b", vec3(c.b));
            put(d, o, "radius", yyjson_mut_float(d, c.radius));
            yyjson_mut_arr_append(cs, o);
        }
        put(d, root, "colliders", cs);
    }
    if (!g.modifiers.empty()) {
        yyjson_mut_val* ms = yyjson_mut_arr(d);
        for (const Modifier& m : g.modifiers) {
            yyjson_mut_val* o = yyjson_mut_obj(d);
            put(d, o, "type", yyjson_mut_str(d, mod_type_name(m.type)));
            if (m.weight != 1.f) put(d, o, "weight", yyjson_mut_float(d, m.weight));
            put_str(d, o, "weightParam", m.weight_param);
            put_str(d, o, "weightCurve", m.weight_curve);
            switch (m.type) {
                case ModType::TwoBone:
                    put(d, o, "upper", mstr(d, m.upper));
                    put(d, o, "mid", mstr(d, m.mid));
                    put(d, o, "end", mstr(d, m.end));
                    put(d, o, "target", mstr(d, m.target));
                    put_str(d, o, "pole", m.pole);
                    if (m.soft != 0.f) put(d, o, "soft", yyjson_mut_float(d, m.soft));
                    if (m.match_rotation) put(d, o, "matchRotation", yyjson_mut_bool(d, true));
                    break;
                case ModType::Fabrik:
                case ModType::Ccd:
                    put(d, o, "start", mstr(d, m.start));
                    put(d, o, "end", mstr(d, m.end));
                    put(d, o, "target", mstr(d, m.target));
                    if (m.iterations != 0) put(d, o, "iterations", yyjson_mut_sint(d, m.iterations));
                    if (m.tolerance != 0.f) put(d, o, "tolerance", yyjson_mut_float(d, m.tolerance));
                    break;
                case ModType::Aim: {
                    yyjson_mut_val* js = yyjson_mut_arr(d);
                    for (const AimJoint& a : m.aim) {
                        yyjson_mut_val* jo = yyjson_mut_obj(d);
                        put(d, jo, "joint", mstr(d, a.joint));
                        if (a.weight != 1.f) put(d, jo, "weight", yyjson_mut_float(d, a.weight));
                        yyjson_mut_arr_append(js, jo);
                    }
                    put(d, o, "joints", js);
                    put(d, o, "target", mstr(d, m.target));
                    if (!is(m.axis, 0.f, 0.f, -1.f)) put(d, o, "axis", vec3(m.axis));
                    if (m.each) put(d, o, "each", yyjson_mut_bool(d, true));
                    if (!m.limits) put(d, o, "limits", yyjson_mut_bool(d, false));
                    break;
                }
                case ModType::Constraint:
                    put(d, o, "kind", yyjson_mut_str(d, kind_name(m.kind)));
                    put(d, o, "joint", mstr(d, m.joint));
                    put(d, o, "target", mstr(d, m.target));
                    if (!is(m.offset, 0.f, 0.f, 0.f)) put(d, o, "offset", vec3(m.offset));
                    if (!is(m.offset_rot, 0.f, 0.f, 0.f)) put(d, o, "offsetRot", vec3(m.offset_rot));
                    if (!is(m.axis, 0.f, 0.f, -1.f)) put(d, o, "axis", vec3(m.axis));
                    break;
                case ModType::Spring:
                    put(d, o, "start", mstr(d, m.start));
                    put(d, o, "end", mstr(d, m.end));
                    if (m.stiffness != 1.f) put(d, o, "stiffness", yyjson_mut_float(d, m.stiffness));
                    if (m.drag != 0.4f) put(d, o, "drag", yyjson_mut_float(d, m.drag));
                    if (!is(m.gravity, 0.f, 0.f, 0.f)) put(d, o, "gravity", vec3(m.gravity));
                    if (m.radius != 0.f) put(d, o, "radius", yyjson_mut_float(d, m.radius));
                    if (m.step != 0.f) put(d, o, "step", yyjson_mut_float(d, m.step));
                    break;
                case ModType::FullBody: {
                    put(d, o, "body", mstr(d, m.body));
                    yyjson_mut_val* ls = yyjson_mut_arr(d);
                    for (const Limb& l : m.limbs) {
                        yyjson_mut_val* lo = yyjson_mut_obj(d);
                        put(d, lo, "upper", mstr(d, l.upper));
                        put(d, lo, "mid", mstr(d, l.mid));
                        put(d, lo, "end", mstr(d, l.end));
                        put(d, lo, "target", mstr(d, l.target));
                        if (l.pull != 1.f) put(d, lo, "pull", yyjson_mut_float(d, l.pull));
                        if (l.reach != 1.f) put(d, lo, "reach", yyjson_mut_float(d, l.reach));
                        yyjson_mut_arr_append(ls, lo);
                    }
                    put(d, o, "limbs", ls);
                    put_str(d, o, "hipGoal", m.pole);
                    if (m.hip_weight != 1.f) put(d, o, "hipWeight", yyjson_mut_float(d, m.hip_weight));
                    if (m.iterations != 0) put(d, o, "iterations", yyjson_mut_sint(d, m.iterations));
                    break;
                }
                case ModType::OrientationWarp: {
                    put(d, o, "hips", mstr(d, m.body));
                    if (!m.aim.empty()) {
                        yyjson_mut_val* js = yyjson_mut_arr(d);
                        for (const AimJoint& a : m.aim) {
                            yyjson_mut_val* jo = yyjson_mut_obj(d);
                            put(d, jo, "joint", mstr(d, a.joint));
                            if (a.weight != 1.f) put(d, jo, "weight", yyjson_mut_float(d, a.weight));
                            yyjson_mut_arr_append(js, jo);
                        }
                        put(d, o, "spine", js);
                    }
                    put(d, o, "angleParam", mstr(d, m.angle_param));
                    if (m.max_angle != 90.f) put(d, o, "maxAngle", yyjson_mut_float(d, m.max_angle));
                    if (m.rate != 0.f) put(d, o, "rate", yyjson_mut_float(d, m.rate));
                    if (m.counter != 1.f) put(d, o, "counter", yyjson_mut_float(d, m.counter));
                    break;
                }
                case ModType::StrideWarp:
                case ModType::FootPlacement: {
                    const bool foot = m.type == ModType::FootPlacement;
                    put(d, o, "pelvis", mstr(d, m.body));
                    yyjson_mut_val* ls = yyjson_mut_arr(d);
                    for (const Leg& l : m.legs) {
                        yyjson_mut_val* lo = yyjson_mut_obj(d);
                        put(d, lo, "upper", mstr(d, l.upper));
                        put(d, lo, "mid", mstr(d, l.mid));
                        put_str(d, lo, "end", l.end);
                        put_str(d, lo, "lockCurve", l.lock_curve);
                        if (!l.lock_down.empty()) {
                            yyjson_mut_val* mk = yyjson_mut_arr(d);
                            yyjson_mut_arr_append(mk, mstr(d, l.lock_down));
                            yyjson_mut_arr_append(mk, mstr(d, l.lock_up));
                            put(d, lo, "lockMarkers", mk);
                        }
                        yyjson_mut_arr_append(ls, lo);
                    }
                    put(d, o, "legs", ls);
                    auto num = [&](const char* key, float value, float fallback) {
                        if (value != fallback) put(d, o, key, yyjson_mut_float(d, value));
                    };
                    if (!foot) {
                        put(d, o, "speedParam", mstr(d, m.speed_param));
                        put_str(d, o, "directionParam", m.direction_param);
                        num("animSpeed", m.anim_speed, 0.f);
                        put_str(d, o, "animSpeedCurve", m.anim_speed_curve);
                        put_str(d, o, "node", m.node);
                        num("rateMin", m.rate_min, 0.8f);
                        num("rateMax", m.rate_max, 1.2f);
                        num("strideMin", m.stride_min, 0.5f);
                        num("strideMax", m.stride_max, 2.f);
                        num("pelvisWeight", m.pelvis_weight, 1.f);
                    } else {
                        num("probeUp", m.probe_up, 0.f);
                        num("probeDown", m.probe_down, 0.f);
                        num("maxDrop", m.max_drop, 0.f);
                        num("maxRise", m.max_rise, 0.f);
                        num("maxAlign", m.max_align, 30.f);
                        num("pelvisRate", m.pelvis_rate, 10.f);
                        num("footRate", m.foot_rate, 15.f);
                        num("groundHeight", m.ground_height, 0.f);
                        num("unlockTime", m.unlock_time, 0.15f);
                        num("maxLockDistance", m.max_lock_distance, 0.f);
                    }
                    break;
                }
            }
            yyjson_mut_arr_append(ms, o);
        }
        put(d, root, "modifiers", ms);
    }

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
