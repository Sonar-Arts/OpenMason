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

struct Clip;

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
