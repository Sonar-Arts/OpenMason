// Cenda character (cc_*): the .omchar editing model, checks and measurements. See cenda/character.h.
#include "cenda/character.h"

#include "../rig/rig_internal.hpp"
#include "omformat/omchar.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <exception>
#include <span>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace oc = cenda::omformat::omchar;

struct cc_char {
    oc::Character def;
};

struct cc_bytes {
    std::vector<std::byte> data;
};

struct cc_report {
    struct Item {
        int32_t severity;
        int32_t code;
        std::string subject;
        std::string message;
    };
    std::vector<Item> items;
};

namespace {

thread_local std::string g_last_error;

int32_t error(int32_t code, const std::string& message) {
    g_last_error = message;
    return code;
}

void clear_error() { g_last_error.clear(); }

bool finite3(const float* v) {
    return v == nullptr || (std::isfinite(v[0]) && std::isfinite(v[1]) && std::isfinite(v[2]));
}

oc::Vec3 vec(const float* v, oc::Vec3 fallback) {
    return v == nullptr ? fallback : oc::Vec3{v[0], v[1], v[2]};
}

void put(const oc::Vec3& v, float* out) {
    if (out == nullptr) return;
    out[0] = v.x;
    out[1] = v.y;
    out[2] = v.z;
}

template <typename T>
void set_out(T* out, T value) {
    if (out != nullptr) *out = value;
}

bool valid_key(const char* s) { return s != nullptr && s[0] != '\0'; }

// Replaces the entry whose key matches in place, else appends; erases when value is absent.
template <typename T, typename KeyOf>
void upsert(std::vector<T>& items, const std::string& key, KeyOf key_of, const T* value) {
    auto it = std::find_if(items.begin(), items.end(), [&](const T& item) { return key_of(item) == key; });
    if (value == nullptr) {
        if (it != items.end()) items.erase(it);
    } else if (it != items.end()) {
        *it = *value;
    } else {
        items.push_back(*value);
    }
}

template <typename T>
bool in_range(const std::vector<T>& items, int32_t index) {
    return index >= 0 && static_cast<std::size_t>(index) < items.size();
}

#define CC_GUARD_BEGIN try {
#define CC_GUARD_END(fallback)                                     \
    }                                                              \
    catch (const std::exception& e) {                              \
        g_last_error = e.what();                                   \
        return fallback;                                           \
    }                                                              \
    catch (...) {                                                  \
        g_last_error = "unknown error";                            \
        return fallback;                                           \
    }

// The point a joint stands for: a bone's head, a part's pivot (world matrix W, column-major).
void joint_point(const cr_rig* rig, int32_t j, const float* world, double out[3]) {
    const float* m = world + static_cast<std::size_t>(j) * 16;
    const auto& joint = rig->joints[static_cast<std::size_t>(j)];
    double p[3] = {0.0, 0.0, 0.0};
    if (joint.kind == CR_JOINT_PART) {
        p[0] = joint.origin[0];
        p[1] = joint.origin[1];
        p[2] = joint.origin[2];
    }
    for (int r = 0; r < 3; ++r) {
        out[r] = static_cast<double>(m[r]) * p[0] + static_cast<double>(m[4 + r]) * p[1] +
                 static_cast<double>(m[8 + r]) * p[2] + static_cast<double>(m[12 + r]);
    }
}

int32_t rig_index(const cr_rig* rig, const std::string& id) {
    auto it = rig->by_id.find(id);
    return it == rig->by_id.end() ? -1 : it->second;
}

std::string role_joint(const oc::Character& def, const char* role) {
    for (const oc::RoleBinding& r : def.roles) {
        if (r.role == role) return r.joint;
    }
    return {};
}

}  // namespace

extern "C" {

int32_t cc_abi_version(void) { return CC_ABI_VERSION; }

const char* cc_last_error(void) { return g_last_error.c_str(); }

// --- Lifetime and I/O ------------------------------------------------------------------------------

cc_char* cc_char_create(void) {
    CC_GUARD_BEGIN
    clear_error();
    return new cc_char();
    CC_GUARD_END(nullptr)
}

cc_char* cc_char_read(const uint8_t* bytes, int64_t length, int32_t* out_result) {
    set_out(out_result, int32_t{CC_OK});
    if (bytes == nullptr || length <= 0) {
        set_out(out_result, int32_t{CC_ERR_ARG});
        error(CC_ERR_ARG, "cc_char_read: no bytes");
        return nullptr;
    }
    try {
        clear_error();
        auto* c = new cc_char();
        try {
            c->def = oc::read(std::span<const std::byte>(reinterpret_cast<const std::byte*>(bytes),
                                                         static_cast<std::size_t>(length)));
        } catch (...) {
            delete c;
            throw;
        }
        return c;
    } catch (const oc::version_error& e) {
        set_out(out_result, int32_t{CC_ERR_VERSION});
        error(CC_ERR_VERSION, e.what());
    } catch (const cenda::omformat::format_error& e) {
        set_out(out_result, int32_t{CC_ERR_FORMAT});
        error(CC_ERR_FORMAT, e.what());
    } catch (const std::exception& e) {
        set_out(out_result, int32_t{CC_ERR_FORMAT});
        error(CC_ERR_FORMAT, e.what());
    }
    return nullptr;
}

void cc_char_destroy(cc_char* c) { delete c; }

cc_bytes* cc_char_write(const cc_char* c) {
    if (c == nullptr) {
        error(CC_ERR_ARG, "cc_char_write: null definition");
        return nullptr;
    }
    CC_GUARD_BEGIN
    clear_error();
    auto* b = new cc_bytes();
    try {
        b->data = oc::write(c->def);
    } catch (...) {
        delete b;
        throw;
    }
    return b;
    CC_GUARD_END(nullptr)
}

const uint8_t* cc_bytes_data(const cc_bytes* b) {
    return b == nullptr ? nullptr : reinterpret_cast<const uint8_t*>(b->data.data());
}

int64_t cc_bytes_size(const cc_bytes* b) { return b == nullptr ? 0 : static_cast<int64_t>(b->data.size()); }

void cc_bytes_destroy(cc_bytes* b) { delete b; }

// --- Strings ------------------------------------------------------------------------------------------

const char* cc_char_get_string(const cc_char* c, int32_t field) {
    if (c == nullptr) return "";
    const oc::Character& d = c->def;
    switch (field) {
        case CC_FIELD_NAME: return d.name.c_str();
        case CC_FIELD_OBJECT_ID: return d.object_id.c_str();
        case CC_FIELD_PROFILE: return d.profile.c_str();
        case CC_FIELD_ANIM_GRAPH: return d.anim_graph.c_str();
        case CC_FIELD_MODEL_REF: return d.model_ref.c_str();
        case CC_FIELD_APPEARANCE: return d.appearance_json.c_str();
        case CC_FIELD_JOINT_HASH: return d.joint_hash.c_str();
        default: return "";
    }
}

int32_t cc_char_set_string(cc_char* c, int32_t field, const char* value) {
    if (c == nullptr) return error(CC_ERR_ARG, "cc_char_set_string: null definition");
    CC_GUARD_BEGIN
    clear_error();
    std::string v = value == nullptr ? std::string() : std::string(value);
    oc::Character& d = c->def;
    switch (field) {
        case CC_FIELD_NAME: d.name = std::move(v); break;
        case CC_FIELD_OBJECT_ID: d.object_id = std::move(v); break;
        case CC_FIELD_PROFILE: d.profile = std::move(v); break;
        case CC_FIELD_ANIM_GRAPH: d.anim_graph = std::move(v); break;
        case CC_FIELD_MODEL_REF: d.model_ref = std::move(v); break;
        case CC_FIELD_APPEARANCE:
            try {
                d.appearance_json = v.empty() ? std::string() : oc::minify_json_object(v);
            } catch (const cenda::omformat::format_error& e) {
                return error(CC_ERR_ARG, e.what());
            }
            break;
        default: return error(CC_ERR_ARG, "cc_char_set_string: field " + std::to_string(field) + " is not settable");
    }
    return CC_OK;
    CC_GUARD_END(CC_ERR_ARG)
}

// --- Skeleton -----------------------------------------------------------------------------------------

int32_t cc_char_joint_count(const cc_char* c) { return c == nullptr ? 0 : static_cast<int32_t>(c->def.joints.size()); }

int32_t cc_char_joint_get(const cc_char* c, int32_t index, const char** out_id, const char** out_name,
                          const char** out_parent_id, int32_t* out_kind) {
    if (c == nullptr) return error(CC_ERR_ARG, "cc_char_joint_get: null definition");
    if (!in_range(c->def.joints, index)) return error(CC_ERR_RANGE, "cc_char_joint_get: index out of range");
    const oc::SkeletonJoint& j = c->def.joints[static_cast<std::size_t>(index)];
    set_out(out_id, j.id.c_str());
    set_out(out_name, j.name.c_str());
    set_out(out_parent_id, j.parent.c_str());
    set_out(out_kind, j.kind == oc::JointKind::Bone ? int32_t{CR_JOINT_BONE} : int32_t{CR_JOINT_PART});
    return CC_OK;
}

int32_t cc_char_set_skeleton(cc_char* c, const cr_rig* rig) {
    if (c == nullptr || rig == nullptr) return error(CC_ERR_ARG, "cc_char_set_skeleton: null argument");
    CC_GUARD_BEGIN
    clear_error();
    std::vector<oc::SkeletonJoint> joints;
    joints.reserve(rig->joints.size());
    for (const auto& j : rig->joints) {
        oc::SkeletonJoint sj;
        sj.id = j.id;
        sj.name = j.name;
        if (j.parent >= 0) sj.parent = rig->joints[static_cast<std::size_t>(j.parent)].id;
        sj.kind = j.kind == CR_JOINT_BONE ? oc::JointKind::Bone : oc::JointKind::Part;
        joints.push_back(std::move(sj));
    }
    c->def.set_joints(std::move(joints));
    return CC_OK;
    CC_GUARD_END(CC_ERR_ARG)
}

int32_t cc_char_model_get(const cc_char* c, const uint8_t** out_data, int64_t* out_size) {
    if (c == nullptr) return error(CC_ERR_ARG, "cc_char_model_get: null definition");
    const auto& model = c->def.model;
    set_out(out_data, model.empty() ? static_cast<const uint8_t*>(nullptr)
                                    : reinterpret_cast<const uint8_t*>(model.data()));
    set_out(out_size, static_cast<int64_t>(model.size()));
    return CC_OK;
}

int32_t cc_char_model_set(cc_char* c, const uint8_t* data, int64_t size, const char* entry_name) {
    if (c == nullptr || size < 0) return error(CC_ERR_ARG, "cc_char_model_set: bad argument");
    CC_GUARD_BEGIN
    clear_error();
    oc::Character& d = c->def;
    if (data == nullptr || size == 0) {
        d.model.clear();
        d.model_entry.clear();
        return CC_OK;
    }
    std::string name = valid_key(entry_name) ? std::string(entry_name) : std::string(oc::kDefaultModelEntry);
    if (name == oc::kManifestEntry) return error(CC_ERR_ARG, "cc_char_model_set: reserved entry name");
    const auto* bytes = reinterpret_cast<const std::byte*>(data);
    d.model.assign(bytes, bytes + size);
    d.model_entry = std::move(name);
    // The model replaces any other archive entry of that name.
    std::erase_if(d.unknown_entries, [&](const cenda::omformat::ZipEntry& e) { return e.name == d.model_entry; });
    return CC_OK;
    CC_GUARD_END(CC_ERR_ARG)
}

// --- Roles --------------------------------------------------------------------------------------------

int32_t cc_char_role_count(const cc_char* c) { return c == nullptr ? 0 : static_cast<int32_t>(c->def.roles.size()); }

int32_t cc_char_role_get(const cc_char* c, int32_t index, const char** out_role, const char** out_joint) {
    if (c == nullptr) return error(CC_ERR_ARG, "cc_char_role_get: null definition");
    if (!in_range(c->def.roles, index)) return error(CC_ERR_RANGE, "cc_char_role_get: index out of range");
    const oc::RoleBinding& r = c->def.roles[static_cast<std::size_t>(index)];
    set_out(out_role, r.role.c_str());
    set_out(out_joint, r.joint.c_str());
    return CC_OK;
}

int32_t cc_char_role_set(cc_char* c, const char* role, const char* joint) {
    if (c == nullptr || !valid_key(role)) return error(CC_ERR_ARG, "cc_char_role_set: null definition or empty role");
    if (joint != nullptr && joint[0] == '\0') return error(CC_ERR_ARG, "cc_char_role_set: empty joint id");
    CC_GUARD_BEGIN
    clear_error();
    oc::RoleBinding value{role, joint == nullptr ? std::string() : std::string(joint)};
    upsert(c->def.roles, value.role, [](const oc::RoleBinding& r) { return r.role; },
           joint == nullptr ? nullptr : &value);
    return CC_OK;
    CC_GUARD_END(CC_ERR_ARG)
}

const char* cc_char_role_joint(const cc_char* c, const char* role) {
    if (c == nullptr || role == nullptr) return nullptr;
    for (const oc::RoleBinding& r : c->def.roles) {
        if (r.role == role) return r.joint.c_str();
    }
    return nullptr;
}

// --- Reference pose and limits ------------------------------------------------------------------------

int32_t cc_char_pose_count(const cc_char* c) {
    return c == nullptr ? 0 : static_cast<int32_t>(c->def.reference_pose.size());
}

int32_t cc_char_pose_get(const cc_char* c, int32_t index, const char** out_joint, float* out_rot) {
    if (c == nullptr) return error(CC_ERR_ARG, "cc_char_pose_get: null definition");
    if (!in_range(c->def.reference_pose, index)) return error(CC_ERR_RANGE, "cc_char_pose_get: index out of range");
    const oc::JointRotation& r = c->def.reference_pose[static_cast<std::size_t>(index)];
    set_out(out_joint, r.joint.c_str());
    put(r.rot, out_rot);
    return CC_OK;
}

int32_t cc_char_pose_set(cc_char* c, const char* joint, const float* rot) {
    if (c == nullptr || !valid_key(joint)) return error(CC_ERR_ARG, "cc_char_pose_set: null definition or empty joint");
    if (!finite3(rot)) return error(CC_ERR_ARG, "cc_char_pose_set: non-finite rotation");
    CC_GUARD_BEGIN
    clear_error();
    oc::JointRotation value{joint, vec(rot, {})};
    upsert(c->def.reference_pose, value.joint, [](const oc::JointRotation& r) { return r.joint; },
           rot == nullptr ? nullptr : &value);
    return CC_OK;
    CC_GUARD_END(CC_ERR_ARG)
}

int32_t cc_char_limit_count(const cc_char* c) { return c == nullptr ? 0 : static_cast<int32_t>(c->def.limits.size()); }

int32_t cc_char_limit_get(const cc_char* c, int32_t index, const char** out_joint, float* out_min,
                          float* out_max) {
    if (c == nullptr) return error(CC_ERR_ARG, "cc_char_limit_get: null definition");
    if (!in_range(c->def.limits, index)) return error(CC_ERR_RANGE, "cc_char_limit_get: index out of range");
    const oc::JointLimit& l = c->def.limits[static_cast<std::size_t>(index)];
    set_out(out_joint, l.joint.c_str());
    put(l.min, out_min);
    put(l.max, out_max);
    return CC_OK;
}

int32_t cc_char_limit_set(cc_char* c, const char* joint, const float* min, const float* max) {
    if (c == nullptr || !valid_key(joint)) return error(CC_ERR_ARG, "cc_char_limit_set: null definition or empty joint");
    if (!finite3(min) || !finite3(max)) return error(CC_ERR_ARG, "cc_char_limit_set: non-finite limit");
    CC_GUARD_BEGIN
    clear_error();
    const bool remove = min == nullptr || max == nullptr;
    oc::JointLimit value{joint, vec(min, {}), vec(max, {})};
    upsert(c->def.limits, value.joint, [](const oc::JointLimit& l) { return l.joint; }, remove ? nullptr : &value);
    return CC_OK;
    CC_GUARD_END(CC_ERR_ARG)
}

// --- IK goals and chains ------------------------------------------------------------------------------

int32_t cc_char_goal_count(const cc_char* c) { return c == nullptr ? 0 : static_cast<int32_t>(c->def.ik_goals.size()); }

int32_t cc_char_goal_get(const cc_char* c, int32_t index, const char** out_name, const char** out_joint) {
    if (c == nullptr) return error(CC_ERR_ARG, "cc_char_goal_get: null definition");
    if (!in_range(c->def.ik_goals, index)) return error(CC_ERR_RANGE, "cc_char_goal_get: index out of range");
    const oc::IkGoal& g = c->def.ik_goals[static_cast<std::size_t>(index)];
    set_out(out_name, g.name.c_str());
    set_out(out_joint, g.joint.c_str());
    return CC_OK;
}

int32_t cc_char_goal_set(cc_char* c, const char* name, const char* joint) {
    if (c == nullptr || !valid_key(name)) return error(CC_ERR_ARG, "cc_char_goal_set: null definition or empty name");
    if (joint != nullptr && joint[0] == '\0') return error(CC_ERR_ARG, "cc_char_goal_set: empty joint id");
    CC_GUARD_BEGIN
    clear_error();
    oc::IkGoal value{name, joint == nullptr ? std::string() : std::string(joint)};
    upsert(c->def.ik_goals, value.name, [](const oc::IkGoal& g) { return g.name; }, joint == nullptr ? nullptr : &value);
    return CC_OK;
    CC_GUARD_END(CC_ERR_ARG)
}

int32_t cc_char_chain_count(const cc_char* c) { return c == nullptr ? 0 : static_cast<int32_t>(c->def.chains.size()); }

int32_t cc_char_chain_get(const cc_char* c, int32_t index, const char** out_name, const char** out_start,
                          const char** out_end, const char** out_goal) {
    if (c == nullptr) return error(CC_ERR_ARG, "cc_char_chain_get: null definition");
    if (!in_range(c->def.chains, index)) return error(CC_ERR_RANGE, "cc_char_chain_get: index out of range");
    const oc::Chain& ch = c->def.chains[static_cast<std::size_t>(index)];
    set_out(out_name, ch.name.c_str());
    set_out(out_start, ch.start.c_str());
    set_out(out_end, ch.end.c_str());
    set_out(out_goal, ch.goal.c_str());
    return CC_OK;
}

int32_t cc_char_chain_set(cc_char* c, const char* name, const char* start, const char* end, const char* goal) {
    if (c == nullptr || !valid_key(name)) return error(CC_ERR_ARG, "cc_char_chain_set: null definition or empty name");
    const bool remove = start == nullptr || end == nullptr;
    if (!remove && (start[0] == '\0' || end[0] == '\0')) return error(CC_ERR_ARG, "cc_char_chain_set: empty joint id");
    CC_GUARD_BEGIN
    clear_error();
    oc::Chain value{name, remove ? "" : start, remove ? "" : end, goal == nullptr ? "" : goal};
    upsert(c->def.chains, value.name, [](const oc::Chain& ch) { return ch.name; }, remove ? nullptr : &value);
    return CC_OK;
    CC_GUARD_END(CC_ERR_ARG)
}

// --- Sockets ------------------------------------------------------------------------------------------

int32_t cc_char_socket_count(const cc_char* c) { return c == nullptr ? 0 : static_cast<int32_t>(c->def.sockets.size()); }

int32_t cc_char_socket_get(const cc_char* c, int32_t index, const char** out_name, const char** out_joint,
                           float* out_pos, float* out_rot, float* out_scale) {
    if (c == nullptr) return error(CC_ERR_ARG, "cc_char_socket_get: null definition");
    if (!in_range(c->def.sockets, index)) return error(CC_ERR_RANGE, "cc_char_socket_get: index out of range");
    const oc::Socket& s = c->def.sockets[static_cast<std::size_t>(index)];
    set_out(out_name, s.name.c_str());
    set_out(out_joint, s.joint.c_str());
    put(s.pos, out_pos);
    put(s.rot, out_rot);
    put(s.scale, out_scale);
    return CC_OK;
}

int32_t cc_char_socket_set(cc_char* c, const char* name, const char* joint, const float* pos,
                           const float* rot, const float* scale) {
    if (c == nullptr || !valid_key(name)) return error(CC_ERR_ARG, "cc_char_socket_set: null definition or empty name");
    if (joint != nullptr && joint[0] == '\0') return error(CC_ERR_ARG, "cc_char_socket_set: empty joint id");
    if (!finite3(pos) || !finite3(rot) || !finite3(scale)) return error(CC_ERR_ARG, "cc_char_socket_set: non-finite value");
    CC_GUARD_BEGIN
    clear_error();
    oc::Socket value{name, joint == nullptr ? "" : joint, vec(pos, {}), vec(rot, {}), vec(scale, {1.f, 1.f, 1.f})};
    upsert(c->def.sockets, value.name, [](const oc::Socket& s) { return s.name; }, joint == nullptr ? nullptr : &value);
    return CC_OK;
    CC_GUARD_END(CC_ERR_ARG)
}

// --- Metrics ------------------------------------------------------------------------------------------

int32_t cc_char_metrics_get(const cc_char* c, cc_metrics* out) {
    if (c == nullptr || out == nullptr) return error(CC_ERR_ARG, "cc_char_metrics_get: null argument");
    const oc::Metrics& m = c->def.metrics;
    *out = cc_metrics{m.height, m.eye_height, m.width, m.hip_height, m.overridden, 0};
    return CC_OK;
}

int32_t cc_char_metrics_set(cc_char* c, const cc_metrics* metrics) {
    if (c == nullptr || metrics == nullptr) return error(CC_ERR_ARG, "cc_char_metrics_set: null argument");
    const float values[] = {metrics->height, metrics->eye_height, metrics->width, metrics->hip_height};
    for (float v : values) {
        if (!std::isfinite(v)) return error(CC_ERR_ARG, "cc_char_metrics_set: non-finite metric");
    }
    clear_error();
    c->def.metrics = oc::Metrics{metrics->height, metrics->eye_height, metrics->width, metrics->hip_height,
                                 metrics->overridden & oc::kMetricAll};
    return CC_OK;
}

int32_t cc_char_compute_metrics(const cc_char* c, const cr_rig* rig, const float* bounds_min,
                                const float* bounds_max, cc_metrics* out) {
    if (c == nullptr || rig == nullptr || bounds_min == nullptr || bounds_max == nullptr || out == nullptr) {
        return error(CC_ERR_ARG, "cc_char_compute_metrics: null argument");
    }
    if (!finite3(bounds_min) || !finite3(bounds_max)) return error(CC_ERR_ARG, "cc_char_compute_metrics: non-finite bounds");
    CC_GUARD_BEGIN
    clear_error();
    const oc::Character& d = c->def;
    const std::size_t n = rig->joints.size();

    // Rest locals with the reference rotations applied.
    std::vector<float> locals(n * 9);
    for (std::size_t i = 0; i < n; ++i) {
        const auto& j = rig->joints[i];
        float* l = locals.data() + i * 9;
        std::memcpy(l, j.pos, sizeof j.pos);
        std::memcpy(l + 3, j.rot, sizeof j.rot);
        std::memcpy(l + 6, j.scale, sizeof j.scale);
    }
    for (const oc::JointRotation& r : d.reference_pose) {
        const int32_t j = rig_index(rig, r.joint);
        if (j < 0) continue;
        float* l = locals.data() + static_cast<std::size_t>(j) * 9;
        l[3] = r.rot.x;
        l[4] = r.rot.y;
        l[5] = r.rot.z;
    }
    std::vector<float> skin(n * 16);
    std::vector<float> world(n * 16);
    const int32_t rc = cr_rig_pose_locals(rig, locals.data(), nullptr, 0, nullptr, skin.data(), world.data(), nullptr);
    if (rc != CR_OK) return error(CC_ERR_ARG, std::string("cc_char_compute_metrics: ") + cr_last_error());

    const double floor_y = bounds_min[1];
    auto role_height = [&](const char* role, double* out_y) {
        const std::string id = role_joint(d, role);
        const int32_t j = id.empty() ? -1 : rig_index(rig, id);
        if (j < 0) return false;
        double p[3];
        joint_point(rig, j, world.data(), p);
        *out_y = p[1] - floor_y;
        return true;
    };

    cc_metrics m{};
    m.height = bounds_max[1] - bounds_min[1];
    m.width = bounds_max[0] - bounds_min[0];
    double y = 0.0;
    if (role_height("hips", &y)) m.hip_height = static_cast<float>(y);

    bool eyes = false;
    for (const oc::Socket& s : d.sockets) {
        if (s.name != "eyes") continue;
        const int32_t j = rig_index(rig, s.joint);
        if (j < 0) break;
        const float* k = skin.data() + static_cast<std::size_t>(j) * 16;
        const double sy = static_cast<double>(k[1]) * s.pos.x + static_cast<double>(k[5]) * s.pos.y +
                          static_cast<double>(k[9]) * s.pos.z + static_cast<double>(k[13]);
        m.eye_height = static_cast<float>(sy - floor_y);
        eyes = true;
        break;
    }
    if (!eyes) {
        double l = 0.0;
        double r = 0.0;
        const bool has_l = role_height("eye_l", &l);
        const bool has_r = role_height("eye_r", &r);
        if (has_l || has_r) {
            m.eye_height = static_cast<float>(has_l && has_r ? (l + r) * 0.5 : (has_l ? l : r));
        } else if (role_height("head", &y)) {
            m.eye_height = static_cast<float>(y);
        }
    }

    const oc::Metrics& stored = d.metrics;
    m.overridden = stored.overridden;
    if (stored.overridden & oc::kMetricHeight) m.height = stored.height;
    if (stored.overridden & oc::kMetricEyeHeight) m.eye_height = stored.eye_height;
    if (stored.overridden & oc::kMetricWidth) m.width = stored.width;
    if (stored.overridden & oc::kMetricHipHeight) m.hip_height = stored.hip_height;
    *out = m;
    return CC_OK;
    CC_GUARD_END(CC_ERR_ARG)
}

// --- Checks -------------------------------------------------------------------------------------------

cc_report* cc_char_check(const cc_char* c, const cr_rig* rig) {
    if (c == nullptr) {
        error(CC_ERR_ARG, "cc_char_check: null definition");
        return nullptr;
    }
    CC_GUARD_BEGIN
    clear_error();
    const oc::Character& d = c->def;
    auto* report = new cc_report();
    auto add = [&](int32_t severity, int32_t code, const std::string& subject, const std::string& message) {
        report->items.push_back({severity, code, subject, message});
    };

    // The joint set references are checked against: the rig when given, else the recorded skeleton.
    std::unordered_map<std::string, std::string> parent_of;  // id -> parent id
    if (rig != nullptr) {
        for (const auto& j : rig->joints) {
            parent_of[j.id] = j.parent >= 0 ? rig->joints[static_cast<std::size_t>(j.parent)].id : std::string();
        }
    } else {
        for (const oc::SkeletonJoint& j : d.joints) parent_of[j.id] = j.parent;
    }
    const bool can_resolve = rig != nullptr || !d.joints.empty();

    if (d.joints.empty()) {
        add(CC_SEVERITY_WARNING, CC_CHECK_NO_SKELETON, "", "the definition records no skeleton joints");
    } else {
        if (!d.joint_hash.empty() && d.joint_hash != oc::joint_hash(d.joints)) {
            add(CC_SEVERITY_WARNING, CC_CHECK_HASH_MISMATCH, d.joint_hash,
                "the stored joint hash does not match the recorded joints (edited by hand?)");
        }
        if (rig != nullptr) {
            std::unordered_set<std::string> recorded;
            for (const oc::SkeletonJoint& sj : d.joints) {
                recorded.insert(sj.id);
                const int32_t j = rig_index(rig, sj.id);
                const std::string label = sj.name.empty() ? sj.id : sj.name;
                if (j < 0) {
                    add(CC_SEVERITY_ERROR, CC_CHECK_JOINT_MISSING, sj.id, "joint " + label + " is not in the model");
                    continue;
                }
                const auto& rj = rig->joints[static_cast<std::size_t>(j)];
                const std::string rig_parent = rj.parent >= 0 ? rig->joints[static_cast<std::size_t>(rj.parent)].id : "";
                if (rig_parent != sj.parent) {
                    add(CC_SEVERITY_ERROR, CC_CHECK_JOINT_REPARENTED, sj.id, "joint " + label + " has a different parent");
                }
                const auto kind = rj.kind == CR_JOINT_BONE ? oc::JointKind::Bone : oc::JointKind::Part;
                if (kind != sj.kind) {
                    add(CC_SEVERITY_ERROR, CC_CHECK_JOINT_KIND_CHANGED, sj.id,
                        "joint " + label + " changed between part and bone");
                }
                if (rj.name != sj.name) {
                    add(CC_SEVERITY_INFO, CC_CHECK_JOINT_RENAMED, sj.id,
                        "joint \"" + sj.name + "\" is now named \"" + rj.name + "\"");
                }
            }
            for (const auto& rj : rig->joints) {
                if (!recorded.contains(rj.id)) {
                    add(CC_SEVERITY_WARNING, CC_CHECK_JOINT_ADDED, rj.id,
                        "joint " + (rj.name.empty() ? rj.id : rj.name) + " is new in the model");
                }
            }
        }
    }

    auto ref = [&](const std::string& joint, const std::string& where) {
        if (can_resolve && !parent_of.contains(joint)) {
            add(CC_SEVERITY_ERROR, CC_CHECK_UNKNOWN_JOINT, joint, where + " names unknown joint " + joint);
            return false;
        }
        return true;
    };
    for (const oc::RoleBinding& r : d.roles) ref(r.joint, "role " + r.role);
    for (const oc::JointRotation& r : d.reference_pose) ref(r.joint, "the reference pose");
    for (const oc::JointLimit& l : d.limits) {
        ref(l.joint, "a limit");
        if (l.min.x > l.max.x || l.min.y > l.max.y || l.min.z > l.max.z) {
            add(CC_SEVERITY_ERROR, CC_CHECK_LIMIT_INVERTED, l.joint, "the limit on " + l.joint + " has min above max");
        }
    }
    std::unordered_set<std::string> goals;
    for (const oc::IkGoal& g : d.ik_goals) {
        ref(g.joint, "IK goal " + g.name);
        goals.insert(g.name);
    }
    for (const oc::Chain& ch : d.chains) {
        const bool known = ref(ch.start, "chain " + ch.name) & ref(ch.end, "chain " + ch.name);
        if (known && can_resolve) {
            // Walk up from the end; stop at the start, a root, or after as many steps as there are joints.
            std::string at = ch.end;
            bool found = false;
            for (std::size_t steps = 0; steps <= parent_of.size() && !at.empty(); ++steps) {
                if (at == ch.start) {
                    found = true;
                    break;
                }
                auto it = parent_of.find(at);
                at = it == parent_of.end() ? std::string() : it->second;
            }
            if (!found) {
                add(CC_SEVERITY_ERROR, CC_CHECK_CHAIN_ORDER, ch.name,
                    "chain " + ch.name + ": its start is not an ancestor of its end");
            }
        }
        if (!ch.goal.empty() && !goals.contains(ch.goal)) {
            add(CC_SEVERITY_ERROR, CC_CHECK_CHAIN_GOAL, ch.name, "chain " + ch.name + " names unknown IK goal " + ch.goal);
        }
    }
    for (const oc::Socket& s : d.sockets) ref(s.joint, "socket " + s.name);

    const struct {
        const char* name;
        float value;
    } metrics[] = {{"height", d.metrics.height},
                   {"eyeHeight", d.metrics.eye_height},
                   {"width", d.metrics.width},
                   {"hipHeight", d.metrics.hip_height}};
    for (const auto& m : metrics) {
        if (m.value < 0.f) add(CC_SEVERITY_ERROR, CC_CHECK_METRIC_NEGATIVE, m.name, std::string("metric ") + m.name + " is negative");
    }
    return report;
    CC_GUARD_END(nullptr)
}

int32_t cc_report_count(const cc_report* r) { return r == nullptr ? 0 : static_cast<int32_t>(r->items.size()); }

int32_t cc_report_max_severity(const cc_report* r) {
    int32_t max = -1;
    if (r != nullptr) {
        for (const auto& item : r->items) max = std::max(max, item.severity);
    }
    return max;
}

int32_t cc_report_get(const cc_report* r, int32_t index, int32_t* out_severity, int32_t* out_code,
                      const char** out_subject, const char** out_message) {
    if (r == nullptr) return error(CC_ERR_ARG, "cc_report_get: null report");
    if (!in_range(r->items, index)) return error(CC_ERR_RANGE, "cc_report_get: index out of range");
    const auto& item = r->items[static_cast<std::size_t>(index)];
    set_out(out_severity, item.severity);
    set_out(out_code, item.code);
    set_out(out_subject, item.subject.c_str());
    set_out(out_message, item.message.c_str());
    return CC_OK;
}

void cc_report_destroy(cc_report* r) { delete r; }

}  // extern "C"
