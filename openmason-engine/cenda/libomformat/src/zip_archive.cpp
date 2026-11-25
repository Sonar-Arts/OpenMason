#include "omformat/zip_archive.hpp"

#include <miniz.h>

#include <cstring>
#include <memory>
#include <unordered_set>

namespace cenda::omformat {

namespace {

struct ReaderGuard {
    mz_zip_archive* zip;
    ~ReaderGuard() { mz_zip_reader_end(zip); }
};

struct WriterGuard {
    mz_zip_archive* zip;
    bool active = true;
    ~WriterGuard() {
        if (active) mz_zip_writer_end(zip);
    }
};

std::string zip_error(mz_zip_archive& zip) {
    return mz_zip_get_error_string(mz_zip_get_last_error(&zip));
}

}  // namespace

std::vector<ZipEntry> read_zip(std::span<const std::byte> archive) {
    mz_zip_archive zip{};
    if (!mz_zip_reader_init_mem(&zip, archive.data(), archive.size(), 0)) {
        throw format_error("not a zip archive: " + zip_error(zip));
    }
    ReaderGuard guard{&zip};

    const mz_uint count = mz_zip_reader_get_num_files(&zip);
    std::vector<ZipEntry> entries;
    entries.reserve(count);
    for (mz_uint i = 0; i < count; ++i) {
        mz_zip_archive_file_stat stat{};
        if (!mz_zip_reader_file_stat(&zip, i, &stat)) {
            throw format_error("zip entry " + std::to_string(i) + ": " + zip_error(zip));
        }
        if (stat.m_is_directory) continue;
        if (stat.m_is_encrypted) throw format_error(std::string("encrypted zip entry: ") + stat.m_filename);
        if (!stat.m_is_supported) throw format_error(std::string("unsupported zip entry: ") + stat.m_filename);
        if (stat.m_uncomp_size > kMaxZipEntryBytes) {
            throw format_error(std::string("zip entry too large: ") + stat.m_filename);
        }
        ZipEntry entry;
        entry.name = stat.m_filename;
        entry.data.resize(static_cast<std::size_t>(stat.m_uncomp_size));
        if (stat.m_uncomp_size > 0 &&
            !mz_zip_reader_extract_to_mem(&zip, i, entry.data.data(), entry.data.size(), 0)) {
            throw format_error("zip entry " + entry.name + ": " + zip_error(zip));
        }
        entries.push_back(std::move(entry));
    }
    return entries;
}

std::vector<std::byte> write_zip(const std::vector<ZipEntry>& entries) {
    mz_zip_archive zip{};
    if (!mz_zip_writer_init_heap(&zip, 0, 0)) {
        throw format_error("zip writer: " + zip_error(zip));
    }
    WriterGuard guard{&zip};

    std::unordered_set<std::string> names;
    for (const ZipEntry& entry : entries) {
        if (entry.name.empty()) throw format_error("zip entry without a name");
        if (!names.insert(entry.name).second) throw format_error("duplicate zip entry: " + entry.name);
        const mz_uint level = static_cast<mz_uint>(entry.precompressed ? int{MZ_BEST_SPEED} : int{MZ_DEFAULT_LEVEL});
        if (!mz_zip_writer_add_mem(&zip, entry.name.c_str(), entry.data.data(), entry.data.size(), level)) {
            throw format_error("zip entry " + entry.name + ": " + zip_error(zip));
        }
    }

    void* buffer = nullptr;
    std::size_t size = 0;
    if (!mz_zip_writer_finalize_heap_archive(&zip, &buffer, &size)) {
        throw format_error("zip finalize: " + zip_error(zip));
    }
    std::vector<std::byte> out(size);
    if (size > 0) std::memcpy(out.data(), buffer, size);
    // mz_zip_writer_end frees the heap buffer.
    return out;
}

}  // namespace cenda::omformat
