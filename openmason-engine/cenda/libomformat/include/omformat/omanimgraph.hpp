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
//   targets           [ "lookAt", "handGoalR", ... ]   transforms the host sets each frame (#158)
//   colliders         [ {joint, a: [x,y,z], b?: [x,y,z], radius} ]   spheres / capsules for spring bones, in
//                     the joint's posed frame
//   modifiers         the pose-modifier stack applied to the root's pose, in order (#158; cenda/rig.h "Pose
//                     modifiers" defines each solver). Every modifier: type, weight?: 1, weightParam?: float
//                     param multiplied in, weightCurve?: curve name multiplied in.
//     twoBone         upper, mid, end, target, pole?, soft?: 0, matchRotation?: false
//     fabrik | ccd    start, end, target, iterations?: 10, tolerance?: 1e-4
//     aim             joints: [ {joint, weight?: 1} ] parents first, target, axis?: [0,0,-1], each?: false,
//                     limits?: true (the .omchar joint limits)
//     constraint      kind: point | orient | parent | aim | copy, joint, target, offset?: [x,y,z],
//                     offsetRot?: [x,y,z] degrees, axis?: [x,y,z] (aim)
//     spring          start, end, stiffness?: 1, drag?: 0.4, gravity?: [x,y,z], radius?: 0, step?: 1/60
//     fullBody        body, limbs: [ {upper, mid, end, target, pull?: 1, reach?: 1} ] (1..5), hipGoal?,
//                     hipWeight?: 1, iterations?: 3
//                     JOINT references: "role:<role>" (through the character) or a joint id / name.
//                     TARGET references: a name from `targets`, or "role:<r>" / "joint:<j>" (that joint's posed
//                     frame) / "socket:<s>" (a .omchar socket).
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

enum class ModType : int { TwoBone = 0, Fabrik = 1, Ccd = 2, Aim = 3, Constraint = 4, Spring = 5, FullBody = 6 };
enum class ConstraintKind : int { Point = 0, Orient = 1, Parent = 2, Aim = 3, Copy = 4 };

struct AimJoint {
    std::string joint;
    float weight = 1.f;
};

struct Limb {
    std::string upper, mid, end, target;
    float pull = 1.f;
    float reach = 1.f;
};

struct Modifier {
    ModType type = ModType::TwoBone;
    float weight = 1.f;
    std::string weight_param;
    std::string weight_curve;
    // joint references
    std::string upper, mid, end, start, joint, body;
    // target references
    std::string target, pole;
    float soft = 0.f;
    bool match_rotation = false;
    int iterations = 0;     // 0 = the solver's default
    float tolerance = 0.f;  // 0 = default
    std::vector<AimJoint> aim;
    float axis[3]{0.f, 0.f, -1.f};
    bool each = false;
    bool limits = true;
    ConstraintKind kind = ConstraintKind::Point;
    float offset[3]{0.f, 0.f, 0.f};
    float offset_rot[3]{0.f, 0.f, 0.f};
    float stiffness = 1.f;
    float drag = 0.4f;
    float gravity[3]{0.f, 0.f, 0.f};
    float radius = 0.f;
    float step = 0.f;  // 0 = 1/60
    std::vector<Limb> limbs;
    float hip_weight = 1.f;
};

struct Collider {
    std::string joint;
    float a[3]{0.f, 0.f, 0.f};
    float b[3]{0.f, 0.f, 0.f};
    bool capsule = false;
    float radius = 0.f;
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
    std::vector<std::string> targets;
    std::vector<Collider> colliders;
    std::vector<Modifier> modifiers;
    std::vector<UnknownSection> unknown_sections;

    [[nodiscard]] int find_node(std::string_view id) const;
    [[nodiscard]] int find_param(std::string_view name) const;
    [[nodiscard]] int find_slot(std::string_view name) const;
    [[nodiscard]] int find_mask(std::string_view name) const;
    [[nodiscard]] int find_target(std::string_view name) const;
};

// Parses and validates (see the header comment). Throws format_error / version_error.
Graph read(std::string_view json);

// Validates an in-memory graph (what read checks). Throws format_error.
void validate(const Graph& graph);

// Serializes (pretty JSON, deterministic; floats in single precision). read(write(g)) == g.
std::string write(const Graph& graph);

}  // namespace cenda::omformat::omanimgraph
