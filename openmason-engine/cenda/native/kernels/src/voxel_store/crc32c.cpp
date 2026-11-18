#include "crc32c.hpp"

#include <array>

namespace cenda::cvstore {

namespace {

struct Tables {
    std::array<std::array<uint32_t, 256>, 8> t{};

    constexpr Tables() {
        for (uint32_t i = 0; i < 256; ++i) {
            uint32_t c = i;
            for (int k = 0; k < 8; ++k) c = (c & 1u) ? (c >> 1) ^ 0x82F63B78u : c >> 1;
            t[0][i] = c;
        }
        for (uint32_t i = 0; i < 256; ++i)
            for (size_t s = 1; s < 8; ++s) t[s][i] = (t[s - 1][i] >> 8) ^ t[0][t[s - 1][i] & 0xFFu];
    }
};

constexpr Tables kTables{};

} // namespace

uint32_t crc32c(const void* data, size_t n, uint32_t crc) {
    const auto& t = kTables.t;
    const auto* p = static_cast<const uint8_t*>(data);
    uint32_t c = ~crc;
    while (n >= 8) {
        // Byte-wise loads: endian- and alignment-independent; compilers fold them into one load.
        const uint32_t lo = c ^ (static_cast<uint32_t>(p[0]) | static_cast<uint32_t>(p[1]) << 8 |
                                 static_cast<uint32_t>(p[2]) << 16 | static_cast<uint32_t>(p[3]) << 24);
        c = t[7][lo & 0xFFu] ^ t[6][(lo >> 8) & 0xFFu] ^ t[5][(lo >> 16) & 0xFFu] ^ t[4][lo >> 24] ^
            t[3][p[4]] ^ t[2][p[5]] ^ t[1][p[6]] ^ t[0][p[7]];
        p += 8;
        n -= 8;
    }
    while (n-- > 0) c = (c >> 8) ^ t[0][(c ^ *p++) & 0xFFu];
    return ~c;
}

} // namespace cenda::cvstore
