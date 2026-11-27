#pragma once

// .omanim — Open Mason animation clip (OpenMason #155: version 1.2; #171: 1.3 = morph tracks; reads 1.0 - 1.3).
//
// A zip archive:
//   manifest.json        the clip (below)
//   track_<id>.json      one per track: {partId, keyframes: [{time, position[3], rotation[3], scale[3], easing}]}
//   anything else        kept verbatim on a round trip
//
// manifest.json (keys in the order the writer emits them):
//   version              "1.2" (readers take any 1.x and 1.0; version numbers are not gated, as before)
//   name, fps, duration, loop, modelRef (string or null)
//   tracks               [{partId, partName, dataFile, targetType?: "bone", role?}]
//   requiredParts        derived on write: every track's partId
//   layer                {type: BASE|OVERLAY, maskParts, fadeInSeconds, fadeOutSeconds, priority}   (1.1)
//   -- 1.2 --
//   notifies             [{name, time, duration?, payload?: {...}}]   duration > 0 = a range (begin/end)
//   curves               [{name, keys: [{time, value, interp?: LINEAR|STEP|CUBIC, inTangent?, outTangent?}]}]
//   syncMarkers          [{name, time}]
//   rootMotion           {joint, translation: [x, y, z], rotationY}   joint = a track's partId or "role:<role>"
//   additive             {reference: frame|clip|character, time?, clip?, space?: local|mesh}
//   -- 1.3 (#171) --
//   morphs               [{name, keys: [{time, value, interp?: LINEAR|STEP|CUBIC, inTangent?, outTangent?}]}]
//                        morph (shape key) weight tracks: `name` = the morph channel (the mesh's shape key name,
//                        bound by name, exact then case-insensitive; addressed as "morph:<name>" where a joint
//                        reference could also be meant); keys exactly like `curves` (CUBIC = Hermite, tangents in
//                        value per second; glTF CUBICSPLINE maps 1:1)
//   <other keys>         unknown sections, kept verbatim
//
// The writer writes "1.3" only when the clip has morph tracks, else "1.2" (a clip without morph tracks writes byte for
// byte what a 1.2 writer did); 1.2 readers keep a 1.3 file's `morphs` as an unknown section.
// Every 1.2 addition is optional and ignored by 1.1 readers (the Java OMAReader / the tool's deserializer
// ignore unknown keys). A ROLE-ADDRESSED track carries "role" and, for those older readers, partId and
// partName "role:<role>" (which bind to no joint, so they skip the track instead of mis-binding it).

#include "omformat/zip_archive.hpp"

#include <cstdint>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace cenda::omformat::omanim {

inline constexpr const char* kWriteVersion = "1.2";        // a clip without morph tracks
inline constexpr const char* kMorphWriteVersion = "1.3";   // a clip with morph tracks (#171)
inline constexpr const char* kMorphPrefix = "morph:";
inline constexpr const char* kManifestEntry = "manifest.json";
inline constexpr const char* kRolePrefix = "role:";

// Easing of the segment after a key (values = CR_EASE_*).
enum class Easing : std::int32_t { Linear = 0, EaseIn = 1, EaseOut = 2, EaseInOut = 3, Step = 4 };

struct Key {
    float time = 0.f;
    float pos[3]{0.f, 0.f, 0.f};
    float rot[3]{0.f, 0.f, 0.f};
    float scale[3]{1.f, 1.f, 1.f};
    Easing easing = Easing::Linear;
};

struct Track {
    std::string part_id;    // joint id (or "role:<role>" for a role track)
    std::string part_name;  // rebind hint
    std::string role;       // "" = joint-addressed
    bool bone = false;      // "targetType": "bone"
    std::string data_file;  // as read ("" = derived on write)
    std::vector<Key> keys;
};

struct Layer {
    bool present = false;  // a 1.0 clip has none (BASE defaults)
    std::string type = "BASE";
    std::vector<std::string> mask_parts;
    float fade_in = 0.1f;
    float fade_out = 0.1f;
    std::int32_t priority = 0;
};

struct Notify {
    std::string name;
    float time = 0.f;
    float duration = 0.f;      // > 0: a range with a begin and an end
    std::string payload_json;  // "" or a minified JSON object
};

enum class CurveInterp : std::int32_t { Linear = 0, Step = 1, Cubic = 2 };

struct CurveKey {
    float time = 0.f;
    float value = 0.f;
    float in_tangent = 0.f;   // value per second, CUBIC only
    float out_tangent = 0.f;
    CurveInterp interp = CurveInterp::Linear;  // of the segment after this key
};

struct Curve {
    std::string name;
    std::vector<CurveKey> keys;  // sorted by time
};

struct SyncMarker {
    std::string name;
    float time = 0.f;
};

enum RootAxis : std::uint32_t { kRootX = 1u << 0, kRootY = 1u << 1, kRootZ = 1u << 2, kRootYaw = 1u << 3 };

struct RootMotion {
    std::string joint;      // "" = none
    std::uint32_t axes = 0; // RootAxis bits extracted
};

enum class AdditiveReference : std::int32_t { None = 0, Frame = 1, Clip = 2, Character = 3 };

struct Additive {
    AdditiveReference reference = AdditiveReference::None;
    float time = 0.f;          // Frame / Clip: the reference frame's time
    std::string clip;          // Clip: the reference clip (name or path)
    bool mesh_space = false;   // false = local space
};

struct UnknownSection {
    std::string key;
    std::string json;
};

struct Clip {
    std::string version = kWriteVersion;  // as read; writing always writes kWriteVersion
    std::string name = "Unnamed";
    float fps = 30.f;
    float duration = 0.f;
    bool loop = false;
    std::string model_ref;
    bool model_ref_null = true;
    std::vector<Track> tracks;
    Layer layer;
    std::vector<Notify> notifies;   // sorted by time (stable)
    std::vector<Curve> curves;
    std::vector<SyncMarker> markers;  // sorted by time (stable)
    std::vector<Curve> morphs;        // 1.3 (#171): morph weight tracks, unique names, keys sorted by time
    RootMotion root_motion;
    Additive additive;
    std::vector<UnknownSection> unknown_sections;
    std::vector<ZipEntry> unknown_entries;
};

// Parses an archive (any 1.x or 1.0). Throws format_error on a corrupt archive, a track without its data
// file, a non-finite number, or malformed 1.2 sections.
Clip read(std::span<const std::byte> archive);

// Serializes as 1.2 (1.3 when the clip has morph tracks). Deterministic; keys are written as floats (shortest form), so every value read
// comes back bit-identical.
std::vector<std::byte> write(const Clip& clip);

// "LINEAR" etc. (unknown names read as Linear, like the Java reader).
Easing easing_from_name(std::string_view name);
const char* easing_name(Easing easing);

// Validates a JSON object and returns it minified (notify payloads).
std::string minify_object(std::string_view json, std::string_view what);

}  // namespace cenda::omformat::omanim
