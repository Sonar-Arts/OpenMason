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
//     clip            slot, rate?: 1, rateParam?: float param multiplied in, sync?: group name,
//                     start?: 0 = where the clip (re)starts, normalized 0..1 (Unreal Start Position; 1 on a
//                     one-shot = its last frame, held)
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
//                                      meshSpace?: false, reference?: rest | character,
//                                      view?: firstPerson} ]
//                     (view firstPerson, #161: only on the ROOT layer node; the layer is left out of
//                     the body pose and applied, after the modifiers, only to the first-person view)
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
//     aim             joints: [ {joint, weight?: 1, limit?: {min: [x,y,z], max: [x,y,z]}} ] parents first, target,
//                     axis?: [0,0,-1], each?: false, limits?: true (the .omchar joint limits; a joint's own `limit`
//                     - local Euler degrees around the reference pose - applies when the .omchar has none for it)
//     constraint      kind: point | orient | parent | aim | copy, joint, target, offset?: [x,y,z],
//                     offsetRot?: [x,y,z] degrees, axis?: [x,y,z] (aim)
//     spring          start, end, stiffness?: 1, drag?: 0.4, gravity?: [x,y,z], radius?: 0, step?: 1/60
//     fullBody        body, limbs: [ {upper, mid, end, target, pull?: 1, reach?: 1} ] (1..5), hipGoal?,
//                     hipWeight?: 1, iterations?: 3
//     orientationWarp hips, spine?: [ {joint, weight?: 1} ] parents first, angleParam (float, degrees: the
//                     movement direction relative to the facing, counter-clockwise seen from above), maxAngle?: 90,
//                     rate?: 0 (easing 1/s, 0 = instant), counter?: 1 (share of the turn the spine takes back)
//     strideWarp      pelvis, legs: [ {upper, mid, end?} ] (1..5; no end = the mid joint's tail is the ankle),
//                     speedParam (float: the real ground speed), directionParam? (vec2: travel direction in model
//                     x/z; default the character's facing), animSpeed?: the clips' speed at play rate 1,
//                     animSpeedCurve?: a curve that says it (wins when present), node?: the node whose clips get the
//                     play rate (default the root), rateMin?: 0.8, rateMax?: 1.2, strideMin?: 0.5, strideMax?: 2,
//                     pelvisWeight?: 1. Play rate = clamp(speed / animSpeed, rateMin, rateMax); the stride scales
//                     by the rest, clamped to [strideMin, strideMax]
//     footPlacement   pelvis, legs: [ {upper, mid, end?, lockCurve?, lockMarkers?: [down, up]} ] (1..5), probeUp?,
//                     probeDown?, maxDrop?, maxLockDistance? (0 = from the leg length), maxRise?: 0, maxAlign?: 30,
//                     pelvisRate?: 10, footRate?: 15, groundHeight?: 0 (the clips' flat ground, model y),
//                     unlockTime?: 0.15. The host answers the ground query (cg_instance_set_ground)
//                     JOINT references: "role:<role>" (through the character) or a joint id / name.
//                     TARGET references: a name from `targets`, or "role:<r>" / "joint:<j>" (that joint's posed
//                     frame) / "socket:<s>" (a .omchar socket).
//   <other keys>      unknown top-level sections, kept verbatim
//
// The nodes form a DAG under `root` (1.1, #164; 1.0 required a tree): a node may feed several parents (or one
// parent twice) and is then evaluated ONCE per frame - one clip clock, influence summed over its uses, like
// Unreal's cached poses / Godot's blend trees; never itself, no cycles, the root used by none. Ids, parameter, slot, mask and state names are unique within their kind.

#include "omformat/visibility.hpp"
#include "omformat/zip_archive.hpp"

#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace cenda::omformat::omanimgraph {

inline constexpr int kVersionMajor = 1;
inline constexpr int kVersionMinor = 1;

class OMFORMAT_EXCEPTION_TYPE version_error final : public std::runtime_error {
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
    bool first_person = false;  // "view": "firstPerson" (#161)
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
    float start = 0.f;  // normalized (re)start position, 0..1
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

enum class ModType : int {
    TwoBone = 0, Fabrik = 1, Ccd = 2, Aim = 3, Constraint = 4, Spring = 5, FullBody = 6,
    OrientationWarp = 7, StrideWarp = 8, FootPlacement = 9  // locomotion adaptation (#159)
};
enum class ConstraintKind : int { Point = 0, Orient = 1, Parent = 2, Aim = 3, Copy = 4 };

struct AimJoint {
    std::string joint;
    float weight = 1.f;
    // Optional limit (Stonebreak #460): local Euler degrees around the reference pose, per axis. Used when the
    // character has no limit for this joint (the .omchar's wins); only with the modifier's `limits` on.
    bool has_limit = false;
    float limit_min[3]{0.f, 0.f, 0.f};
    float limit_max[3]{0.f, 0.f, 0.f};
};

struct Limb {
    std::string upper, mid, end, target;
    float pull = 1.f;
    float reach = 1.f;
};

// A leg of a stride-warp / foot-placement modifier (#159).
struct Leg {
    std::string upper, mid, end;  // end "" = the mid joint's tail is the ankle
    std::string lock_curve;       // footPlacement: lock weight from this curve
    std::string lock_down, lock_up;  // footPlacement: locked from sync marker `down` to the next `up`
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
    // locomotion (#159): body = hips / pelvis, aim = the spine of an orientation warp
    std::vector<Leg> legs;
    std::string angle_param;       // orientationWarp
    float max_angle = 90.f;
    float rate = 0.f;
    float counter = 1.f;
    std::string speed_param;       // strideWarp
    std::string direction_param;
    float anim_speed = 0.f;
    std::string anim_speed_curve;
    std::string node;
    float rate_min = 0.8f, rate_max = 1.2f;
    float stride_min = 0.5f, stride_max = 2.f;
    float pelvis_weight = 1.f;
    float probe_up = 0.f, probe_down = 0.f, max_drop = 0.f, max_rise = 0.f;  // footPlacement
    float max_align = 30.f;
    float pelvis_rate = 10.f, foot_rate = 15.f;
    float ground_height = 0.f;
    float unlock_time = 0.15f;
    float max_lock_distance = 0.f;
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

// Editing (#164): a DRAFT may miss required members and have empty lists (malformed values still fail);
// write_draft skips validation, read_draft(write_draft(g)) == g. validate throws the first problem.
Graph read_draft(std::string_view json);
std::string write_draft(const Graph& graph);
void validate(const Graph& graph);
// The node ids a node uses, in input order (blend samples, states, layer base then layers, slot source).
std::vector<std::string> node_children(const Node& node);

}  // namespace cenda::omformat::omanimgraph
