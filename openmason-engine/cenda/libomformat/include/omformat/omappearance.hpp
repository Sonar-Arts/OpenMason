#pragma once

// The .omchar "appearance" section (OpenMason #162): the appearance slots a character declares. A JSON object
// (cenda/appearance.h documents the meaning):
//
//   tints      [{name, default?: "#rrggbb" (white)}]
//   materials  [{id, tint} | {id, mask, channels: {r?, g?, b?, a?}}]   material id (int), tint / channel = a tint
//              name, mask = an archive entry of the .omchar (an RGBA image)
//   groups     [{name, parts: [joint id, ...], visible?: true}]
//   bodies     [{name, model}]   model = an archive entry (an .omo for the same joint set)
//   equipment  [{name, kind?: "rigid" | "skinned", socket?, hides?: [group, ...]}]
//
// Slot names (tints, groups, equipment) are unique; "body" is reserved. Material ids are unique; a material's
// tints and an equipment slot's groups must be declared. Unknown keys are ignored (they stay in the .omchar's
// JSON). Throws format_error.

#include "omformat/zip_archive.hpp"

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace cenda::omformat::omappearance {

inline constexpr const char* kBodySlot = "body";

struct Tint {
    std::string name;
    std::uint8_t rgb[3]{255, 255, 255};
};

struct MaterialDye {
    int id = 0;
    std::string tint;         // whole-material tint ("" when masked)
    std::string mask;         // archive entry of an RGBA dye mask ("" = none)
    std::string channels[4];  // r, g, b, a -> tint name ("" = none)
};

struct Group {
    std::string name;
    std::vector<std::string> parts;
    bool visible = true;
};

struct Body {
    std::string name;
    std::string model;
};

enum class EquipmentKind : int { Rigid = 0, Skinned = 1 };

struct Equipment {
    std::string name;
    EquipmentKind kind = EquipmentKind::Rigid;
    std::string socket;
    std::vector<std::string> hides;
};

struct Section {
    std::vector<Tint> tints;
    std::vector<MaterialDye> materials;
    std::vector<Group> groups;
    std::vector<Body> bodies;
    std::vector<Equipment> equipment;

    [[nodiscard]] int tint(std::string_view name) const;
    [[nodiscard]] int group(std::string_view name) const;
};

// "" = an empty section.
Section parse(std::string_view json);

}  // namespace cenda::omformat::omappearance
