// libomformat internals: the shipped .omprofile files, embedded by CMake (builtin_profiles.cpp.in).
#pragma once

#include <span>
#include <string_view>

namespace cenda::omformat::omprofile::detail {

struct BuiltinText {
    const char* id;
    std::string_view json;
};

std::span<const BuiltinText> builtin_texts();

}  // namespace cenda::omformat::omprofile::detail
