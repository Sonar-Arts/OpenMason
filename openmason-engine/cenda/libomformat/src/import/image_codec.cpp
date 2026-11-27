// Image bytes -> PNG for the importers (OpenMason #173): PNG passes through, JPEG is decoded by stb_image (this TU
// only: STB_IMAGE_STATIC, so no stbi_* symbol exists outside it) and re-encoded by miniz.

#define STB_IMAGE_STATIC
#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_JPEG
#define STBI_ONLY_PNG
#define STBI_NO_STDIO
#define STBI_NO_LINEAR
#define STBI_NO_HDR
#define STBI_MAX_DIMENSIONS 16384
#include <stb_image.h>

#include <miniz.h>

#include "image_resize.hpp"
#include "import_internal.hpp"

#include <climits>
#include <cstring>
#include <initializer_list>
#include <memory>

namespace cenda::omformat::import::detail {

namespace {

bool starts_with(const std::vector<std::byte>& b, std::initializer_list<unsigned char> sig) {
    if (b.size() < sig.size()) return false;
    std::size_t i = 0;
    for (unsigned char c : sig) {
        if (std::to_integer<unsigned char>(b[i++]) != c) return false;
    }
    return true;
}

struct StbiFree {
    void operator()(stbi_uc* p) const { stbi_image_free(p); }
};

}  // namespace

std::vector<std::byte> encode_png(const unsigned char* pixels, int width, int height, int channels) {
    std::size_t len = 0;
    void* png = tdefl_write_image_to_png_file_in_memory_ex(pixels, width, height, channels, &len,
                                                           MZ_DEFAULT_LEVEL, MZ_FALSE);
    if (png == nullptr) throw format_error("import: PNG encoding failed");
    std::vector<std::byte> out(len);
    std::memcpy(out.data(), png, len);
    mz_free(png);
    return out;
}

bool decode_pixels(std::span<const std::byte> bytes, std::vector<unsigned char>& pixels, int& width, int& height,
                   int& channels) {
    pixels.clear();
    width = height = channels = 0;
    if (bytes.empty() || bytes.size() > static_cast<std::size_t>(INT_MAX)) return false;
    int w = 0, h = 0, c = 0;
    std::unique_ptr<stbi_uc, StbiFree> data(stbi_load_from_memory(reinterpret_cast<const stbi_uc*>(bytes.data()),
                                                                   static_cast<int>(bytes.size()), &w, &h, &c, 0));
    if (!data || w <= 0 || h <= 0 || c < 1 || c > 4) return false;
    const std::size_t n = static_cast<std::size_t>(w) * static_cast<std::size_t>(h) * static_cast<std::size_t>(c);
    pixels.assign(data.get(), data.get() + n);
    width = w;
    height = h;
    channels = c;
    return true;
}

void finish_image(Image& img, std::string_view what, std::vector<std::string>& warnings) {
    img.png.clear();
    img.width = img.height = img.channels = 0;
    if (img.bytes.empty()) return;
    const bool png = starts_with(img.bytes, {0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
    const bool jpeg = starts_with(img.bytes, {0xff, 0xd8, 0xff});
    if (png) img.mime = "image/png";
    if (jpeg) img.mime = "image/jpeg";
    if (!png && !jpeg) {
        warnings.push_back("image " + std::string(what) + ": not PNG or JPEG (" +
                           (img.mime.empty() ? std::string("unknown type") : img.mime) + "), not converted");
        return;
    }
    if (img.bytes.size() > static_cast<std::size_t>(INT_MAX)) {
        warnings.push_back("image " + std::string(what) + ": too large");
        return;
    }
    const auto* data = reinterpret_cast<const stbi_uc*>(img.bytes.data());
    const int len = static_cast<int>(img.bytes.size());
    int w = 0, h = 0, c = 0;
    if (png) {
        // Passed through untouched; only the header is read (size, channels).
        if (stbi_info_from_memory(data, len, &w, &h, &c) == 0 || w <= 0 || h <= 0 || c < 1 || c > 4) {
            warnings.push_back("image " + std::string(what) + ": unreadable PNG (" + stbi_failure_reason() + ")");
            return;
        }
        img.png = img.bytes;
    } else {
        std::unique_ptr<stbi_uc, StbiFree> pixels(stbi_load_from_memory(data, len, &w, &h, &c, 0));
        if (!pixels || w <= 0 || h <= 0 || c < 1 || c > 4) {
            warnings.push_back("image " + std::string(what) + ": undecodable JPEG (" +
                               (pixels ? std::string("bad channel count") : std::string(stbi_failure_reason())) + ")");
            return;
        }
        img.png = encode_png(pixels.get(), w, h, c);
    }
    img.width = w;
    img.height = h;
    img.channels = c;
}

}  // namespace cenda::omformat::import::detail
