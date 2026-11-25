#pragma once

// .omlook — a character appearance record (OpenMason #162; cenda/appearance.h documents the meaning): slot name
// -> value. Two encodings of the same data, both deterministic (write(read(x)) == x for anything write made):
//
// JSON (saves, tools):
//   {"format": "omlook", "version": "1.0", "character"?: "<objectId>", "slots": {"<slot>": value, ...}}
//   value: "#rrggbb" = colour (lower-case on write), true/false = bool, other strings = string, numbers = number
//   (single precision), null = a removal (patches), {"$type": 6..255, "$hex": "<payload>"} = a value of a type
//   this reader does not know (from a newer binary writer), any other object / array = JSON (kept minified).
//
// Binary (network):
//   'O' 'L' major minor | varint character length, bytes | varint entry count |
//   per entry: varint name length, name | u8 type | varint payload length, payload
//   payloads: colour 3 bytes (r g b), bool 1 byte, string / JSON UTF-8, number f32 little-endian, removal none,
//   other types raw (kept). Varints are unsigned LEB128.
//
// Readers take 1.x and refuse a newer major (version_error). Duplicate slot names are refused.

#include "omformat/zip_archive.hpp"

#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace cenda::omformat::omlook {

inline constexpr int kVersionMajor = 1;
inline constexpr int kVersionMinor = 0;

class version_error final : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

enum Type : std::uint8_t { Remove = 0, Color = 1, Bool = 2, String = 3, Number = 4, Json = 5 };

struct Entry {
    std::string name;
    std::uint8_t type = String;
    std::uint8_t rgb[3]{};
    bool flag = false;
    float number = 0.f;
    std::string text;               // String / Json (minified)
    std::vector<std::uint8_t> raw;  // a type this reader does not know

    // Same type and value (names not compared).
    [[nodiscard]] bool same_value(const Entry& o) const;
};

struct Look {
    std::string character;
    std::vector<Entry> entries;

    [[nodiscard]] int find(std::string_view name) const;
};

// Either encoding (JSON when the first non-blank byte is '{'). Throws format_error / version_error.
Look read(std::span<const std::byte> bytes);
Look read_json(std::string_view json);
Look read_binary(std::span<const std::byte> bytes);
std::vector<std::byte> write_json(const Look& look);
std::vector<std::byte> write_binary(const Look& look);

// Minifies a JSON value (object or array) for an Entry of type Json. Throws format_error.
std::string minify_json_value(std::string_view json);

}  // namespace cenda::omformat::omlook
