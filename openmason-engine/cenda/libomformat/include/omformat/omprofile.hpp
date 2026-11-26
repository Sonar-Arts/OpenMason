#pragma once

// .omprofile — Open Mason skeleton profile (OpenMason #154): the named roles a character's joints map onto
// (Unity Humanoid Avatar / Godot SkeletonProfile / HumanIK skeleton definition). Plain JSON:
//
//   format        "omprofile"
//   version       "1.0" (readers accept 1.x; a newer major is refused)
//   id, name, description
//   facing        how auto-characterization finds which way the model faces:
//                   {"roles": [...]}       the horizontal direction of these roles' joints (feet point forward)
//                   {"from": r, "to": r}   the horizontal vector between two roles' joints (hips -> head)
//   roles         [ { name, parent|null, required, side: center|left|right, mirror?, toward?: [roles],
//                     direction?: [x,y,z], match?: [[alias, weight]], chain?: {under, keywords, position} } ]
//   chains        (1.x, optional; #160) [ {name, roles: [role, ...] parents first, goal?, bend?: [x,y,z]} ] retarget
//                 chains; `bend` = the way the limb's middle joint bends in the profile frame (knees -Z, elbows +Z),
//                 used by IK retargeting when the source limb is straight
//   ikGoals       (1.x, optional; #160) [ {name, role} ] the IK goals chains end in
//   poses         (1.1, optional; #163) [ {name, directions: {"<role>": [x,y,z], ...}} ] named reference poses
//                 ("A"): per-role direction overrides on top of the roles' own `direction` (which is the default
//                 pose, "T" for the humanoid); cc_char_fit_reference_pose_named fits one
//   roles[].diagram  (1.1, optional; #163) [x, y] the role's slot on the editor's body diagram, 0..1, x to the
//                 viewer's right, y down (the character faces the viewer); roles without one are laid out
//                 from the directions
//
// Frame of every profile: +Y up, the character faces -Z, its own left is -X (a model that faces another way
// is mapped through its .omchar `facing`). `direction` is the role's direction in the reference pose: from
// the role's joint toward the first mapped role in `toward`, else along the joint's own bone. `match`
// aliases are space-separated name tokens with a weight in (0, 1]. A `chain` role is matched structurally:
// the position-th joint (by depth) under the joint of role `under` whose name holds one of `keywords`
// (fingers, tails). Parents come before their children in the list.

#include "omformat/visibility.hpp"
#include "omformat/zip_archive.hpp"

#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace cenda::omformat::omprofile {

inline constexpr int kVersionMajor = 1;
inline constexpr int kVersionMinor = 1;

class OMFORMAT_EXCEPTION_TYPE version_error final : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

enum class Side : int { Center = 0, Left = 1, Right = 2 };

struct Alias {
    std::vector<std::string> tokens;  // lower case
    float weight = 1.f;
};

struct ChainRule {
    std::string under;
    std::vector<std::string> keywords;  // lower case
    int position = 0;
};

struct Role {
    std::string name;
    std::string parent;  // "" = root
    bool required = false;
    Side side = Side::Center;
    std::string mirror;  // "" = none
    std::vector<std::string> toward;
    bool has_direction = false;
    float direction[3]{0.f, 0.f, 0.f};  // unit length when has_direction
    std::vector<Alias> match;
    std::optional<ChainRule> chain;
    bool has_diagram = false;
    float diagram[2]{0.f, 0.f};  // body-diagram slot (1.1)
};

// A named reference pose (1.1, #163): direction overrides by role (unit length, profile frame).
struct PoseDirection {
    std::string role;
    float direction[3]{0.f, 0.f, 0.f};
};

struct NamedPose {
    std::string name;
    std::vector<PoseDirection> directions;
}; 

struct Facing {
    std::vector<std::string> roles;
    std::string from;
    std::string to;
};

// A retarget chain (OpenMason #160): the roles it runs through, parents first. A character's chain runs from its
// first mapped role to its last (auto-map writes it into the .omchar `chains`); retargeting pairs chains by name.
struct RetargetChain {
    std::string name;
    std::vector<std::string> roles;
    std::string goal;  // an ikGoals name, "" = none
    bool has_bend = false;
    float bend[3]{0.f, 0.f, 0.f};  // which way the limb's middle joint bends, profile frame (unit length)
};

// An IK goal: the role whose joint it moves (auto-map writes it into the .omchar `ikGoals`).
struct IkGoal {
    std::string name;
    std::string role;
};

struct Profile {
    std::string id;
    std::string name;
    std::string description;
    Facing facing;
    std::vector<Role> roles;
    std::vector<RetargetChain> chains;
    std::vector<IkGoal> ik_goals;
    std::vector<NamedPose> poses;

    // Index of the role with this name, -1 when none.
    [[nodiscard]] int find(std::string_view role) const;
    // Index of the named pose, -1 when none.
    [[nodiscard]] int find_pose(std::string_view pose_name) const;
};

// Parses and validates (unique names, parents/mirrors/toward/chain/facing refer to roles, parents listed
// first, mirrors mutual with opposite sides, directions non-zero). Throws format_error / version_error.
Profile read(std::string_view json);

// Serializes (pretty JSON, deterministic).
std::string write(const Profile& profile);

// The profiles that ship with the engine ("humanoid", "quadruped", "bird").
const std::vector<std::string>& builtin_ids();
// A shipped profile; throws format_error for an unknown id.
const Profile& builtin(std::string_view id);

}  // namespace cenda::omformat::omprofile
