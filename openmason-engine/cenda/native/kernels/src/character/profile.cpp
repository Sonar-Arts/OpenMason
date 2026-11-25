// Cenda character: skeleton profiles (cc_profile_*). See cenda/character.h and omformat/omprofile.hpp.
#include "character_internal.hpp"

#include <cstring>
#include <string_view>

using namespace cenda::character;

extern "C" {

int32_t cc_profile_builtin_count(void) { return static_cast<int32_t>(op::builtin_ids().size()); }

const char* cc_profile_builtin_id(int32_t index) {
    const auto& ids = op::builtin_ids();
    return in_range(ids, index) ? ids[static_cast<std::size_t>(index)].c_str() : nullptr;
}

cc_profile* cc_profile_builtin(const char* id) {
    if (!valid_key(id)) {
        error(CC_ERR_ARG, "cc_profile_builtin: empty id");
        return nullptr;
    }
    CC_GUARD_BEGIN
    clear_error();
    const auto& ids = op::builtin_ids();
    if (std::find(ids.begin(), ids.end(), std::string(id)) == ids.end()) {
        error(CC_ERR_ARG, std::string("no built-in profile \"") + id + "\"");
        return nullptr;
    }
    return new cc_profile{op::builtin(id)};
    CC_GUARD_END(nullptr)
}

cc_profile* cc_profile_read(const uint8_t* json, int64_t length, int32_t* out_result) {
    set_out(out_result, int32_t{CC_OK});
    if (json == nullptr || length <= 0) {
        set_out(out_result, int32_t{CC_ERR_ARG});
        error(CC_ERR_ARG, "cc_profile_read: no bytes");
        return nullptr;
    }
    try {
        clear_error();
        return new cc_profile{op::read(std::string_view(reinterpret_cast<const char*>(json),
                                                        static_cast<std::size_t>(length)))};
    } catch (const op::version_error& e) {
        set_out(out_result, int32_t{CC_ERR_VERSION});
        error(CC_ERR_VERSION, e.what());
    } catch (const std::exception& e) {
        set_out(out_result, int32_t{CC_ERR_FORMAT});
        error(CC_ERR_FORMAT, e.what());
    }
    return nullptr;
}

cc_bytes* cc_profile_write(const cc_profile* p) {
    if (p == nullptr) {
        error(CC_ERR_ARG, "cc_profile_write: null profile");
        return nullptr;
    }
    CC_GUARD_BEGIN
    clear_error();
    const std::string text = op::write(p->profile);
    auto* b = new cc_bytes();
    b->data.resize(text.size());
    std::memcpy(b->data.data(), text.data(), text.size());
    return b;
    CC_GUARD_END(nullptr)
}

void cc_profile_destroy(cc_profile* p) { delete p; }

const char* cc_profile_get_string(const cc_profile* p, int32_t field) {
    if (p == nullptr) return "";
    switch (field) {
        case CC_PROFILE_ID: return p->profile.id.c_str();
        case CC_PROFILE_NAME: return p->profile.name.c_str();
        case CC_PROFILE_DESCRIPTION: return p->profile.description.c_str();
        default: return "";
    }
}

int32_t cc_profile_role_count(const cc_profile* p) {
    return p == nullptr ? 0 : static_cast<int32_t>(p->profile.roles.size());
}

int32_t cc_profile_role_get(const cc_profile* p, int32_t index, const char** out_name, const char** out_parent,
                            const char** out_mirror, int32_t* out_side, int32_t* out_required,
                            float* out_direction, int32_t* out_has_direction) {
    if (p == nullptr) return error(CC_ERR_ARG, "cc_profile_role_get: null profile");
    if (!in_range(p->profile.roles, index)) return error(CC_ERR_RANGE, "cc_profile_role_get: index out of range");
    const op::Role& r = p->profile.roles[static_cast<std::size_t>(index)];
    set_out(out_name, r.name.c_str());
    set_out(out_parent, r.parent.c_str());
    set_out(out_mirror, r.mirror.c_str());
    set_out(out_side, static_cast<int32_t>(r.side));
    set_out(out_required, r.required ? int32_t{1} : int32_t{0});
    set_out(out_has_direction, r.has_direction ? int32_t{1} : int32_t{0});
    if (out_direction != nullptr) {
        for (int i = 0; i < 3; ++i) out_direction[i] = r.has_direction ? r.direction[i] : 0.f;
    }
    return CC_OK;
}

int32_t cc_profile_role_find(const cc_profile* p, const char* name) {
    if (p == nullptr || name == nullptr) return -1;
    return p->profile.find(name);
}

}  // extern "C"
