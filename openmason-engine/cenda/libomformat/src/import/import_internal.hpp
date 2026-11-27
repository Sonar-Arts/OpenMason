// libomformat internals shared by the glTF and OBJ readers (OpenMason #173).
#pragma once

#include "omformat/import.hpp"

#include <cstddef>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace cenda::omformat::import::detail {

// Fills img.mime / png / width / height / channels from img.bytes (PNG passed through, JPEG decoded with stb_image
// and re-encoded with miniz). Anything else, or an undecodable image, leaves png empty and adds a warning naming
// `what`. Never throws on bad image bytes.
void finish_image(Image& img, std::string_view what, std::vector<std::string>& warnings);

// The PNG encoding of 8-bit pixels (channels 1..4), deterministic. Throws format_error if miniz fails.
std::vector<std::byte> encode_png(const unsigned char* pixels, int width, int height, int channels);

// Standard base64 (RFC 4648, '=' padding optional, no whitespace). nullopt on a bad character / length.
std::optional<std::vector<std::byte>> decode_base64(std::string_view text);

// "%41" -> "A"; a malformed escape is kept verbatim.
std::string percent_decode(std::string_view uri);

// Column-major T * R * S.
Mat4 compose_trs(const Float3& t, const Float4& r, const Float3& s);

}  // namespace cenda::omformat::import::detail
