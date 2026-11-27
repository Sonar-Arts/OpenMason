// libomformat PRIVATE: pixel access for the model importer's texture downscale (OpenMason #173, cenda
// native/kernels/src/model/import_omo.cpp, which includes this header by its relative path; omformat is a static
// library, so these hidden symbols link into libcenda_kernels and the tests). Not part of the omformat API.
#pragma once

#include "import_internal.hpp"  // encode_png: the importer's deterministic miniz PNG encoder

#include <cstddef>
#include <span>
#include <vector>

namespace cenda::omformat::import::detail {

// Decodes PNG or JPEG bytes (any bit depth; 16-bit samples are reduced to 8) to 8-bit pixels, rows top first,
// `channels` interleaved samples per pixel (1 grey, 2 grey + alpha, 3 RGB, 4 RGBA: the image's own count).
// Returns false (outputs cleared) on bytes stb_image cannot decode. Never throws on bad bytes.
bool decode_pixels(std::span<const std::byte> bytes, std::vector<unsigned char>& pixels, int& width, int& height,
                   int& channels);

}  // namespace cenda::omformat::import::detail
