/* CRC-32C (Castagnoli, reflected polynomial 0x82F63B78), slicing-by-8 in portable C++. The region
 * store checksums every record and every header slot with it. crc32c("123456789") == 0xE3069283. */
#pragma once

#include <cstddef>
#include <cstdint>

namespace cenda::cvstore {

/* Continues `crc` (0 to start) over n bytes. */
uint32_t crc32c(const void* data, size_t n, uint32_t crc = 0);

} // namespace cenda::cvstore
