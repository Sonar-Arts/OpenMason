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
//
// Frame of every profile: +Y up, the character faces -Z, its own left is -X (a model that faces another way
// is mapped through its .omchar `facing`). `direction` is the role's direction in the reference pose: from
// the role's joint toward the first mapped role in `toward`, else along the joint's own bone. `match`
// aliases are space-separated name tokens with a weight in (0, 1]. A `chain` role is matched structurally:
// the position-th joint (by depth) under the joint of role `under` whose name holds one of `keywords`
// (fingers, tails). Parents come before their children in the list.

#include "omformat/zip_archive.hpp"

#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace cenda::omformat::omprofile {

inline constexpr int kVersionMajor = 1;
inline constexpr int kVersionMinor = 0;

class version_error final : public std::runtime_error {
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
};

struct Facing {
    std::vector<std::string> roles;
    std::string from;
    std::string to;
};

struct Profile {
    std::string id;
    std::string name;
    std::string description;
    Facing facing;
    std::vector<Role> roles;

    // Index of the role with this name, -1 when none.
    [[nodiscard]] int find(std::string_view role) const;
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
