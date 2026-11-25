#pragma once

// .omanimgraph — Open Mason animation graph (OpenMason #157): what Unreal's Animation Blueprint, Unity's
// Animator Controller and Godot's AnimationTree describe, as data. Written against profile ROLES and named
// clip SLOTS, so one graph drives every character of a profile; a character binds clips to the slots.
// Plain JSON:
//
//   format            "omanimgraph"
//   version           "1.0" (readers accept 1.x; a newer major is refused)
//   name, description, profile ("" = any)
//   notifyThreshold   a clip player fires its notifies while its effective weight >= this (default 0.1)
//   parameters        [ {name, type: float|int|bool|trigger|vec2, default?: number | bool | [x, y]} ]
//   slots             [ "idle", "walk", ... ]                       clip slots the character binds
//   masks             [ {name, entries: [ {role | joint, weight?: 1, branch?: true, depth?: 0} ]} ]
//   root              the id of the output node
//   nodes             [ node ... ], each {id, type, ...}:
//     clip            slot, rate?: 1, rateParam?: float param multiplied in, sync?: group name
//     blend1d         param (float), samples: [ {node, value} ] (sorted by value on read)
//     blend2d         param (vec2) | paramX + paramY (floats), mode?: cartesian | directional,
//                     samples: [ {node, x, y} ]   (gradient band interpolation, Johansen 2009)
//     stateMachine    entry, states: [ {name, node} | {name, conduit: true} ],
//                     transitions: [ {from: state | "*", to, conditions?: [ {param, op?, value?} ],
//                                     priority?: 0, blend?: 0.2, mode?: inertialize | crossfade,
//                                     exitTime?: normalized time of the source state, canInterrupt?: true} ]
//                     op: == != > < >= <= (default: the param is true / non-zero; a trigger is consumed
//                     when its transition is taken). Higher priority is tested first, then list order.
//     layer           base, layers: [ {node, mode?: override | additive, mask?, weight?: 1,
//                                      weightParam?: float param multiplied in, passthrough?: true,
//                                      meshSpace?: false, reference?: rest | character} ]
//                     (an additive layer whose node is not an additive clip is made a delta against
//                     `reference`)
//     slot            name, source, mask?   a montage slot: the host plays one-shot clips into it
//   <other keys>      unknown top-level sections, kept verbatim
//
// The nodes form a TREE under `root`: every node is used by at most one parent and never by itself, the
// root by none. Ids, parameter, slot, mask and state names are unique within their kind.

#include "omformat/zip_archive.hpp"

#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace cenda::omformat::omanimgraph {

inline constexpr int kVersionMajor = 1;
inline constexpr int kVersionMinor = 0;

class version_error final : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

enum class ParamType : int { Float = 0, Int = 1, Bool = 2, Trigger = 3, Vec2 = 4 };

struct Param {
    std::string name;
    ParamType type = ParamType::Float;
    double value[2]{0.0, 0.0};  // default (int / bool / trigger in value[0])
};

struct MaskEntry {
    std::string role;   // exactly one of role / joint
    std::string joint;  // id or name
    float weight = 1.f;
    bool branch = true;
    int depth = 0;
};

struct Mask {
    std::string name;
    std::vector<MaskEntry> entries;
};

enum class NodeType : int { Clip = 0, Blend1D = 1, Blend2D = 2, StateMachine = 3, Layer = 4, Slot = 5 };

struct Sample {
    std::string node;
    float x = 0.f;  // blend1d: the value
    float y = 0.f;
};

enum class CondOp : int { True = 0, Eq = 1, Ne = 2, Gt = 3, Lt = 4, Ge = 5, Le = 6 };

struct Condition {
    std::string param;
    CondOp op = CondOp::True;
    double value = 0.0;
};

enum class BlendMode : int { Inertialize = 0, Crossfade = 1 };

struct Transition {
    std::string from;  // "*" = any state
    std::string to;
    std::vector<Condition> conditions;
    int priority = 0;
    float blend = 0.2f;
    BlendMode mode = BlendMode::Inertialize;
    float exit_time = -1.f;  // < 0 = none
    bool can_interrupt = true;
};

struct State {
    std::string name;
    std::string node;  // "" for a conduit
    bool conduit = false;
};

enum class LayerMode : int { Override = 0, Additive = 1 };
enum class Reference : int { Rest = 0, Character = 1 };

struct LayerEntry {
    std::string node;
    LayerMode mode = LayerMode::Override;
    std::string mask;
    float weight = 1.f;
    std::string weight_param;
    bool passthrough = true;
    bool mesh_space = false;
    Reference reference = Reference::Rest;
};

enum class Blend2DMode : int { Cartesian = 0, Directional = 1 };

struct Node {
    std::string id;
    NodeType type = NodeType::Clip;
    // clip
    std::string slot;
    float rate = 1.f;
    std::string rate_param;
    std::string sync;
    // blend1d / blend2d
    std::string param;    // blend1d, or a vec2 for blend2d
    std::string param_y;  // blend2d with two floats: param = x, param_y = y
    Blend2DMode mode2d = Blend2DMode::Cartesian;
    std::vector<Sample> samples;
    // stateMachine
    std::string entry;
    std::vector<State> states;
    std::vector<Transition> transitions;
    // layer
    std::string base;
    std::vector<LayerEntry> layers;
    // slot
    std::string name;
    std::string source;
    std::string mask;
};

struct UnknownSection {
    std::string key;
    std::string json;
};

struct Graph {
    std::string name;
    std::string description;
    std::string profile;
    float notify_threshold = 0.1f;
    std::vector<Param> params;
    std::vector<std::string> slots;
    std::vector<Mask> masks;
    std::string root;
    std::vector<Node> nodes;
    std::vector<UnknownSection> unknown_sections;

    [[nodiscard]] int find_node(std::string_view id) const;
    [[nodiscard]] int find_param(std::string_view name) const;
    [[nodiscard]] int find_slot(std::string_view name) const;
    [[nodiscard]] int find_mask(std::string_view name) const;
};

// Parses and validates (see the header comment). Throws format_error / version_error.
Graph read(std::string_view json);

// Validates an in-memory graph (what read checks). Throws format_error.
void validate(const Graph& graph);

// Serializes (pretty JSON, deterministic; floats in single precision). read(write(g)) == g.
std::string write(const Graph& graph);

}  // namespace cenda::omformat::omanimgraph
