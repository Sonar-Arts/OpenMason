/* Region store (#120): the platform layer. Positional file I/O (pread/pwrite on POSIX, ReadFile/
 * WriteFile with an OVERLAPPED offset on Windows), durability (fdatasync / F_FULLFSYNC on macOS /
 * FlushFileBuffers), atomic replace-rename, directory sync and an exclusive directory lock. Every call
 * reports failure through its return value and a message; nothing throws. */
#pragma once

#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <string>

namespace cenda::cvstore {

namespace fs = std::filesystem;

class File {
public:
    enum class Open { Ok, Missing, Error };

    File() = default;
    ~File();
    File(const File&) = delete;
    File& operator=(const File&) = delete;
    File(File&& other) noexcept;
    File& operator=(File&& other) noexcept;

    /* Read-write open. create: make the file when missing; truncate: empty it. */
    Open open(const fs::path& path, bool create, bool truncate, std::string& err);
    bool is_open() const;
    void close();

    /* Reads up to n bytes at off; *got < n only at end of file. Safe from several threads at once. */
    bool read_at(uint64_t off, void* dst, size_t n, size_t& got, std::string& err) const;
    bool write_at(uint64_t off, const void* src, size_t n, std::string& err);
    /* Makes every completed write (data and the file size) durable. */
    bool sync(std::string& err);
    bool size(uint64_t& out, std::string& err) const;
    bool truncate(uint64_t size, std::string& err);

private:
#ifdef _WIN32
    void* handle_ = nullptr; /* HANDLE; nullptr when closed */
#else
    int fd_ = -1;
#endif
};

/* Replaces `to` with `from` atomically (rename(2) / MoveFileExW REPLACE_EXISTING|WRITE_THROUGH). */
bool rename_replace(const fs::path& from, const fs::path& to, std::string& err);
/* Removes a file; a missing file is success. */
bool remove_file(const fs::path& path);
/* Makes directory entries (creations, renames) durable. No-op on Windows (NTFS journals them and the
 * rename is write-through). */
bool sync_dir(const fs::path& dir, std::string& err);

/* Exclusive advisory lock on a file inside the store directory, so two stores (two processes, or the
 * same directory opened twice) never write the same region files. */
class DirLock {
public:
    DirLock() = default;
    ~DirLock();
    DirLock(const DirLock&) = delete;
    DirLock& operator=(const DirLock&) = delete;
    bool acquire(const fs::path& lock_path, std::string& err);
    void release();

private:
#ifdef _WIN32
    void* handle_ = nullptr;
#else
    int fd_ = -1;
#endif
};

std::string path_utf8(const fs::path& p);

} // namespace cenda::cvstore
