#pragma once

#include "omformat/visibility.hpp"
#include <cstddef>
#include <cstdint>
#include <span>
#include <stdexcept>
#include <string>
#include <vector>

namespace cenda::omformat {

class OMFORMAT_EXCEPTION_TYPE format_error final : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

// One file in a zip archive. Directories are not entries.
struct ZipEntry {
    std::string name;
    std::vector<std::byte> data;
    // Writing only: the payload is already compressed (e.g. an embedded .omo, itself a zip), so deflate it
    // at the fastest level. Entries are never STORED: miniz's in-memory writer always adds a data
    // descriptor, and java.util.zip.ZipInputStream rejects a descriptor on a stored entry.
    bool precompressed = false;
};

// Largest uncompressed entry a reader accepts (guards against zip bombs).
inline constexpr std::uint64_t kMaxZipEntryBytes = 512ull * 1024 * 1024;

// Every file entry of an in-memory zip archive, in central-directory order. Throws format_error on a
// corrupt archive, an encrypted entry, or an entry over kMaxZipEntryBytes.
std::vector<ZipEntry> read_zip(std::span<const std::byte> archive);

// A zip archive of the entries, in the given order. Deterministic: no timestamps, fixed compression
// level, so identical entries give identical bytes. Throws format_error on a duplicate or empty name.
std::vector<std::byte> write_zip(const std::vector<ZipEntry>& entries);

}  // namespace cenda::omformat
