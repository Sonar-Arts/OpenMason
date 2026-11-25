// Cenda character internals shared by character.cpp (definitions), profile.cpp (profiles) and
// characterize.cpp (auto-map, validation, reference-pose fitting, lock).
#pragma once

#include "cenda/character.h"

#include "../rig/rig_internal.hpp"
#include "omformat/omchar.hpp"
#include "omformat/omprofile.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <exception>
#include <string>
#include <vector>

struct cc_char {
    cenda::omformat::omchar::Character def;
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

    void add(int32_t severity, int32_t code, std::string subject, std::string message) {
        items.push_back({severity, code, std::move(subject), std::move(message)});
    }
};

struct cc_profile {
    cenda::omformat::omprofile::Profile profile;
};

struct cc_automap {
    struct Match {
        std::string role;
        std::string joint;
        float confidence;
    };
    std::vector<Match> matches;
    cc_report notes;
};

namespace cenda::character {

namespace oc = cenda::omformat::omchar;
namespace op = cenda::omformat::omprofile;

// The calling thread's last error (cc_last_error).
std::string& last_error();

inline int32_t error(int32_t code, const std::string& message) {
    last_error() = message;
    return code;
}

inline void clear_error() { last_error().clear(); }

template <typename T>
void set_out(T* out, T value) {
    if (out != nullptr) *out = value;
}

inline bool valid_key(const char* s) { return s != nullptr && s[0] != '\0'; }

template <typename T>
bool in_range(const std::vector<T>& items, int32_t index) {
    return index >= 0 && static_cast<std::size_t>(index) < items.size();
}

inline int32_t rig_index(const cr_rig* rig, const std::string& id) {
    auto it = rig->by_id.find(id);
    return it == rig->by_id.end() ? -1 : it->second;
}

// The point a joint stands for: a bone's head, a part's pivot, from world matrices W (column-major float[16]
// per joint).
inline void joint_point(const cr_rig* rig, int32_t j, const float* world, double out[3]) {
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

// The joint mapped to a role, or "".
inline std::string role_joint(const oc::Character& def, std::string_view role) {
    for (const oc::RoleBinding& r : def.roles) {
        if (r.role == role) return r.joint;
    }
    return {};
}

// The reference pose as rig locals (9 floats per joint: pos, rot_deg, scale): rest locals with the definition's
// reference rotations (character_edit.cpp).
void reference_locals(const oc::Character& def, const cr_rig* rig, float* out);

}  // namespace cenda::character

#define CC_GUARD_BEGIN try {
#define CC_GUARD_END(fallback)                                   \
    }                                                            \
    catch (const std::exception& e) {                            \
        ::cenda::character::last_error() = e.what();             \
        return fallback;                                         \
    }                                                            \
    catch (...) {                                                \
        ::cenda::character::last_error() = "unknown error";      \
        return fallback;                                         \
    }
