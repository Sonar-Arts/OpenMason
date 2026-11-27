// Shared importer helpers (OpenMason #173): the directory resolver, base64 / URI decoding, TRS composition.

#include "import_internal.hpp"

#include <fstream>
#include <iterator>
#include <system_error>

namespace cenda::omformat::import {

Resolver directory_resolver(std::filesystem::path dir) {
    return [dir = std::move(dir)](std::string_view uri) -> std::optional<std::vector<std::byte>> {
        if (uri.empty()) return std::nullopt;
        const std::filesystem::path rel(std::u8string(reinterpret_cast<const char8_t*>(uri.data()), uri.size()));
        if (rel.is_absolute() || rel.has_root_name() || rel.has_root_directory()) return std::nullopt;
        for (const auto& part : rel) {
            if (part == "..") return std::nullopt;
        }
        const std::filesystem::path file = dir / rel;
        std::error_code ec;
        if (!std::filesystem::is_regular_file(file, ec)) return std::nullopt;
        std::ifstream in(file, std::ios::binary);
        if (!in) return std::nullopt;
        std::vector<char> raw((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
        std::vector<std::byte> out(raw.size());
        for (std::size_t i = 0; i < raw.size(); ++i) out[i] = static_cast<std::byte>(raw[i]);
        return out;
    };
}

namespace detail {

std::optional<std::vector<std::byte>> decode_base64(std::string_view text) {
    while (!text.empty() && text.back() == '=') text.remove_suffix(1);
    if (text.size() % 4 == 1) return std::nullopt;
    std::vector<std::byte> out;
    out.reserve(text.size() / 4 * 3 + 2);
    std::uint32_t acc = 0;
    int bits = 0;
    for (char ch : text) {
        std::uint32_t v;
        if (ch >= 'A' && ch <= 'Z') v = static_cast<std::uint32_t>(ch - 'A');
        else if (ch >= 'a' && ch <= 'z') v = static_cast<std::uint32_t>(ch - 'a' + 26);
        else if (ch >= '0' && ch <= '9') v = static_cast<std::uint32_t>(ch - '0' + 52);
        else if (ch == '+' || ch == '-') v = 62;
        else if (ch == '/' || ch == '_') v = 63;
        else return std::nullopt;
        acc = ((acc << 6) | v) & 0xffffffu;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push_back(static_cast<std::byte>((acc >> bits) & 0xffu));
        }
    }
    return out;
}

std::string percent_decode(std::string_view uri) {
    auto hex = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };
    std::string out;
    out.reserve(uri.size());
    for (std::size_t i = 0; i < uri.size(); ++i) {
        if (uri[i] == '%' && i + 2 < uri.size()) {
            const int hi = hex(uri[i + 1]), lo = hex(uri[i + 2]);
            if (hi >= 0 && lo >= 0) {
                out.push_back(static_cast<char>(hi * 16 + lo));
                i += 2;
                continue;
            }
        }
        out.push_back(uri[i]);
    }
    return out;
}

Mat4 compose_trs(const Float3& t, const Float4& r, const Float3& s) {
    const float x = r[0], y = r[1], z = r[2], w = r[3];
    const float xx = x * x, yy = y * y, zz = z * z;
    const float xy = x * y, xz = x * z, yz = y * z, wx = w * x, wy = w * y, wz = w * z;
    Mat4 m{};
    m[0] = (1.f - 2.f * (yy + zz)) * s[0];
    m[1] = (2.f * (xy + wz)) * s[0];
    m[2] = (2.f * (xz - wy)) * s[0];
    m[4] = (2.f * (xy - wz)) * s[1];
    m[5] = (1.f - 2.f * (xx + zz)) * s[1];
    m[6] = (2.f * (yz + wx)) * s[1];
    m[8] = (2.f * (xz + wy)) * s[2];
    m[9] = (2.f * (yz - wx)) * s[2];
    m[10] = (1.f - 2.f * (xx + yy)) * s[2];
    m[12] = t[0];
    m[13] = t[1];
    m[14] = t[2];
    m[15] = 1.f;
    return m;
}

}  // namespace detail
}  // namespace cenda::omformat::import
