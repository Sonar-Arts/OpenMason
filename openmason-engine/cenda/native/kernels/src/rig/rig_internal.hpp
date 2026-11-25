// Cenda rig internals shared by rig.cpp (rigs, clips, posing) and skin.cpp (weights).
#pragma once

#include "cenda/rig.h"
#include "rig_math.hpp"

#include <atomic>
#include <cstdint>
#include <list>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace cenda::rig {

void set_error(const std::string& message);

std::string lower(const std::string& s);

struct Joint {
    int32_t parent = -1;
    int32_t kind = CR_JOINT_PART;
    uint32_t flags = 0;
    float origin[3]{};
    float pos[3]{};
    float rot[3]{};
    float scale[3]{1.f, 1.f, 1.f};
    float endpoint[3]{};
    std::string id;
    std::string name;
    std::string name_lower;
};

// Per (rig, clip) binding: the track each joint reads, and whether the clip's mask covers it.
struct Binding {
    uint64_t clip_serial = 0;
    std::vector<int32_t> track;   // per joint, -1 = none
    std::vector<uint8_t> masked;  // per joint
};

// One joint's local pose (the 9 floats of cr_rig_local_pose).
struct Pose {
    float pos[3];
    float rot[3];
    float scale[3];
};

// A local pose of every joint + whether the pose drives it (see rig.h "Pose pipeline").
struct PoseBuf {
    std::vector<Pose> v;
    std::vector<uint8_t> driven;
};

// Float sampling helpers with the retired Java AnimSampler / AnimLayering operation order (rig.cpp).
float lerp_angle(float a, float b, float u);
void sample_track(const cr_clip& clip, int32_t track, float t, Pose& out);
void lerp_pose(Pose& a, const Pose& b, float w);
// lerp_pose with the rotation slerped as a quaternion (pose.cpp).
void lerp_pose_quat(Pose& a, const Pose& b, float w);
float wrap_time(float elapsed, float duration, int32_t loop);
float clip_weight(const cr_clip& clip, float elapsed);
// The clip's per-joint binding on a rig (cached on the rig; a copy).
Binding binding_for(const cr_rig& rig, const cr_clip& clip);
// Into the caller's binding (reuses its vectors: no allocation once they have grown).
void binding_into(const cr_rig& rig, const cr_clip& clip, Binding& out);

// Pose ops shared by cr_rig_pose (ABI 1 entry points) and cr_poses (pose.cpp).
void pose_rest(const cr_rig& rig, PoseBuf& dst);
// scratch: a binding the call may overwrite.
void pose_sample(const cr_rig& rig, const cr_clip& clip, float elapsed, bool clip_mask, PoseBuf& dst,
                 Binding& scratch);
// Override layer: dst = lerp(dst or rest, src, min(weight·mask_j, 1)) on joints src drives.
void pose_layer_override(const cr_rig& rig, PoseBuf& dst, const PoseBuf& src, float weight, const float* mask,
                         uint32_t flags);

// Runs a pose-modifier stack on a pose (modifiers.cpp).
int32_t modstack_apply(cr_modstack& stack, PoseBuf& pose, float dt);

// One joint's local matrix L for a pose (no extras) and the frame its children inherit (rig.cpp).
Mat4 joint_local_matrix(const Joint& j, const Pose& p);
Mat4 joint_child_frame(const Joint& j, const Mat4& world);

// Composes local poses into skin / world / child matrices (rig.cpp). who = the API name for errors.
int32_t compose_pose(const cr_rig* rig, const std::vector<Pose>& poses, const cr_joint_extra* extras,
                     int32_t extra_count, const float* base_matrix, float* out_skin, float* out_world,
                     float* out_child, const char* who);

}  // namespace cenda::rig

struct cr_rig {
    std::vector<cenda::rig::Joint> joints;
    std::vector<int32_t> order;                   // parents before children
    std::vector<cenda::rig::Mat4> rest_world;      // W_rest
    std::vector<cenda::rig::Mat4> rest_child;      // F_rest
    std::vector<cenda::rig::Mat4> rest_world_inv;  // W_rest^-1
    std::unordered_map<std::string, int32_t> by_id;
    std::unordered_map<std::string, int32_t> by_name;
    std::unordered_map<std::string, int32_t> by_name_lower;
    uint32_t flags = 0;

    // Binding cache, most recently used first, bounded.
    mutable std::mutex binding_mutex;
    mutable std::list<cenda::rig::Binding> bindings;
};

struct cr_clip {
    uint64_t serial = 0;
    cr_clip_desc desc{};
    std::vector<cr_track_desc> tracks;
    std::vector<cr_key> keys;
    std::vector<std::string> track_ids;
    std::vector<std::string> track_names;
    std::vector<std::string> mask;
    std::vector<std::string> mask_lower;
    std::unordered_map<std::string, int32_t> track_by_id;
    std::unordered_map<std::string, int32_t> track_by_name;
    std::unordered_map<std::string, int32_t> track_by_name_lower;
};
