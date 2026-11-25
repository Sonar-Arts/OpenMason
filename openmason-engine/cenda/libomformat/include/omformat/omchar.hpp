#pragma once

// .omchar — Open Mason character definition (OpenMason #153).
//
// A zip archive:
//   manifest.json   required, the definition (below)
//   <model entry>   optional embedded skeleton model (an .omo, deflated at the fastest level), named by
//                   skeleton.model
//   anything else   kept verbatim on a round trip
//
// manifest.json (keys in the order the writer emits them; empty optional sections are left out):
//   format         "omchar"
//   version        "1.0" (readers accept 1.x; a newer major is refused)
//   name, objectId, profile                     strings
//   facing         "-z" | "+z" | "-x" | "+x": the model's forward axis (absent = "-z", the engine convention)
//   locked         true when the role mapping is locked (only a definition that passes validation locks)
//   skeleton       { model, modelRef, jointHash, joints: [{id, name, parent|null, kind: part|bone}] }
//   roles          { "<role>": "<joint id>", ... }            (profile roles, see OpenMason #154)
//   referencePose  [{joint, rot:[x,y,z]}]   local Euler XYZ degrees, absolute (like an OMANIM key)
//   limits         [{joint, min:[x,y,z], max:[x,y,z]}]  degrees, relative to the reference pose
//   ikGoals        [{name, joint}]
//   chains         [{name, start, end, goal?}]   start is an ancestor of end
//   sockets        [{name, joint, pos, rot, scale}]   rest model space, moved by the joint's skin
//                                                    matrix (the OMO attachment-point convention)
//   metrics        {height, eyeHeight, width, hipHeight, overridden:[names]}   blocks
//   animGraph      string (OpenMason #157)
//   appearance     object, opaque until OpenMason #162
//   <other keys>   unknown sections, kept verbatim on a round trip
//
// Every reference into the skeleton is a joint id; role names appear only in `roles`.

#include "omformat/zip_archive.hpp"

#include <cstddef>
#include <cstdint>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace cenda::omformat::omchar {

inline constexpr int kVersionMajor = 1;
inline constexpr int kVersionMinor = 0;
inline constexpr const char* kManifestEntry = "manifest.json";
inline constexpr const char* kDefaultModelEntry = "model.omo";

// A file whose major version is newer than this reader.
class version_error final : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

enum class JointKind : std::int32_t { Part = 0, Bone = 1 };

struct Vec3 {
    float x = 0.f;
    float y = 0.f;
    float z = 0.f;
    bool operator==(const Vec3&) const = default;
};

struct SkeletonJoint {
    std::string id;
    std::string name;
    std::string parent;  // "" = root
    JointKind kind = JointKind::Part;
    bool operator==(const SkeletonJoint&) const = default;
};

struct RoleBinding {
    std::string role;
    std::string joint;
};

struct JointRotation {
    std::string joint;
    Vec3 rot;
};

struct JointLimit {
    std::string joint;
    Vec3 min;
    Vec3 max;
};

struct IkGoal {
    std::string name;
    std::string joint;
};

struct Chain {
    std::string name;
    std::string start;
    std::string end;
    std::string goal;  // "" = none
};

struct Socket {
    std::string name;
    std::string joint;
    Vec3 pos;
    Vec3 rot;
    Vec3 scale{1.f, 1.f, 1.f};
};

enum MetricBit : std::uint32_t {
    kMetricHeight = 1u << 0,
    kMetricEyeHeight = 1u << 1,
    kMetricWidth = 1u << 2,
    kMetricHipHeight = 1u << 3,
    kMetricAll = kMetricHeight | kMetricEyeHeight | kMetricWidth | kMetricHipHeight
};

struct Metrics {
    float height = 0.f;
    float eye_height = 0.f;
    float width = 0.f;
    float hip_height = 0.f;
    std::uint32_t overridden = 0;  // MetricBit set: values the author typed, kept by recomputation

    [[nodiscard]] bool empty() const {
        return height == 0.f && eye_height == 0.f && width == 0.f && hip_height == 0.f && overridden == 0;
    }
};

struct UnknownSection {
    std::string key;
    std::string json;  // the value, minified JSON
};

struct Character {
    std::string name;
    std::string object_id;
    std::string profile;
    std::string facing;  // "" = "-z"
    bool locked = false;
    std::string anim_graph;

    // Skeleton.
    std::string model_entry;            // embedded model's entry name, "" = none
    std::vector<std::byte> model;       // embedded model bytes (only with model_entry)
    std::string model_ref;              // the model by object id or path when not embedded
    std::vector<SkeletonJoint> joints;  // the joint set this definition was made for
    std::string joint_hash;             // as stored; set_joints() recomputes it

    std::vector<RoleBinding> roles;
    std::vector<JointRotation> reference_pose;
    std::vector<JointLimit> limits;
    std::vector<IkGoal> ik_goals;
    std::vector<Chain> chains;
    std::vector<Socket> sockets;
    Metrics metrics;

    std::string appearance_json;  // "" = none, else a JSON object (minified)

    std::vector<UnknownSection> unknown_sections;
    std::vector<ZipEntry> unknown_entries;

    // Replaces the joint set and recomputes joint_hash.
    void set_joints(std::vector<SkeletonJoint> joints);
};

// Structure hash of a joint set: FNV-1a 64 over (id, parent id, kind) of every joint, sorted by id.
// Names are left out (a rename breaks nothing: references are ids). Format "fnv1a64:<16 hex>".
std::string joint_hash(const std::vector<SkeletonJoint>& joints);

// True for a valid facing value ("-z", "+z", "-x", "+x").
bool valid_facing(std::string_view facing);

// Validates a JSON object and returns it minified (for Character::appearance_json). Throws format_error
// when the text is not a JSON object.
std::string minify_json_object(std::string_view json);

// Parses an archive. Throws format_error (corrupt archive, bad manifest, duplicate keys or names) or
// version_error (newer major version).
Character read(std::span<const std::byte> archive);

// Serializes. Deterministic: write(read(write(c))) == write(c). Throws format_error when appearance_json
// or an unknown section is not valid JSON, or an entry name collides.
std::vector<std::byte> write(const Character& character);

}  // namespace cenda::omformat::omchar
