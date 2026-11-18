#include "store_io.hpp"

#include <system_error>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <cerrno>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace cenda::cvstore {

std::string path_utf8(const fs::path& p) {
    const std::u8string s = p.u8string();
    return std::string(reinterpret_cast<const char*>(s.data()), s.size());
}

namespace {

#ifdef _WIN32
std::string os_message(const char* what, const fs::path& path) {
    const DWORD code = GetLastError();
    return std::string(what) + " " + path_utf8(path) + ": " +
           std::system_category().message(static_cast<int>(code));
}
std::string os_message(const char* what) {
    const DWORD code = GetLastError();
    return std::string(what) + ": " + std::system_category().message(static_cast<int>(code));
}
#else
std::string os_message(const char* what, const fs::path& path) {
    const int code = errno;
    return std::string(what) + " " + path_utf8(path) + ": " + std::system_category().message(code);
}
std::string os_message(const char* what) {
    const int code = errno;
    return std::string(what) + ": " + std::system_category().message(code);
}
#endif

} // namespace

File::~File() { close(); }

#ifdef _WIN32

File::File(File&& other) noexcept : handle_(other.handle_) { other.handle_ = nullptr; }
File& File::operator=(File&& other) noexcept {
    if (this != &other) {
        close();
        handle_ = other.handle_;
        other.handle_ = nullptr;
    }
    return *this;
}

File::Open File::open(const fs::path& path, bool create, bool truncate, std::string& err) {
    close();
    DWORD disposition = OPEN_EXISTING;
    if (create) disposition = truncate ? CREATE_ALWAYS : OPEN_ALWAYS;
    else if (truncate) disposition = TRUNCATE_EXISTING;
    HANDLE h = CreateFileW(path.c_str(), GENERIC_READ | GENERIC_WRITE,
                           FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, disposition,
                           FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        const DWORD code = GetLastError();
        if (!create && (code == ERROR_FILE_NOT_FOUND || code == ERROR_PATH_NOT_FOUND)) return Open::Missing;
        err = os_message("open", path);
        return Open::Error;
    }
    handle_ = h;
    return Open::Ok;
}

bool File::is_open() const { return handle_ != nullptr; }

void File::close() {
    if (handle_ != nullptr) {
        CloseHandle(static_cast<HANDLE>(handle_));
        handle_ = nullptr;
    }
}

bool File::read_at(uint64_t off, void* dst, size_t n, size_t& got, std::string& err) const {
    got = 0;
    auto* out = static_cast<uint8_t*>(dst);
    while (got < n) {
        const size_t left = n - got;
        const DWORD chunk = left > 0x40000000u ? 0x40000000u : static_cast<DWORD>(left);
        OVERLAPPED ov{};
        const uint64_t at = off + got;
        ov.Offset = static_cast<DWORD>(at & 0xFFFFFFFFu);
        ov.OffsetHigh = static_cast<DWORD>(at >> 32);
        DWORD done = 0;
        if (!ReadFile(static_cast<HANDLE>(handle_), out + got, chunk, &done, &ov)) {
            if (GetLastError() == ERROR_HANDLE_EOF) return true;
            err = os_message("read");
            return false;
        }
        if (done == 0) return true;
        got += done;
    }
    return true;
}

bool File::write_at(uint64_t off, const void* src, size_t n, std::string& err) {
    size_t put = 0;
    const auto* in = static_cast<const uint8_t*>(src);
    while (put < n) {
        const size_t left = n - put;
        const DWORD chunk = left > 0x40000000u ? 0x40000000u : static_cast<DWORD>(left);
        OVERLAPPED ov{};
        const uint64_t at = off + put;
        ov.Offset = static_cast<DWORD>(at & 0xFFFFFFFFu);
        ov.OffsetHigh = static_cast<DWORD>(at >> 32);
        DWORD done = 0;
        if (!WriteFile(static_cast<HANDLE>(handle_), in + put, chunk, &done, &ov) || done == 0) {
            err = os_message("write");
            return false;
        }
        put += done;
    }
    return true;
}

bool File::sync(std::string& err) {
    if (!FlushFileBuffers(static_cast<HANDLE>(handle_))) {
        err = os_message("flush");
        return false;
    }
    return true;
}

bool File::size(uint64_t& out, std::string& err) const {
    LARGE_INTEGER li;
    if (!GetFileSizeEx(static_cast<HANDLE>(handle_), &li)) {
        err = os_message("size");
        return false;
    }
    out = static_cast<uint64_t>(li.QuadPart);
    return true;
}

bool File::truncate(uint64_t size, std::string& err) {
    FILE_END_OF_FILE_INFO info{};
    info.EndOfFile.QuadPart = static_cast<LONGLONG>(size);
    if (!SetFileInformationByHandle(static_cast<HANDLE>(handle_), FileEndOfFileInfo, &info, sizeof(info))) {
        err = os_message("truncate");
        return false;
    }
    return true;
}

bool rename_replace(const fs::path& from, const fs::path& to, std::string& err) {
    if (!MoveFileExW(from.c_str(), to.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        err = os_message("rename", from);
        return false;
    }
    return true;
}

bool remove_file(const fs::path& path) {
    if (DeleteFileW(path.c_str())) return true;
    const DWORD code = GetLastError();
    return code == ERROR_FILE_NOT_FOUND || code == ERROR_PATH_NOT_FOUND;
}

bool sync_dir(const fs::path&, std::string&) { return true; }

DirLock::~DirLock() { release(); }

bool DirLock::acquire(const fs::path& lock_path, std::string& err) {
    release();
    HANDLE h = CreateFileW(lock_path.c_str(), GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
                           nullptr, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        err = os_message("open", lock_path);
        return false;
    }
    OVERLAPPED ov{};
    if (!LockFileEx(h, LOCKFILE_EXCLUSIVE_LOCK | LOCKFILE_FAIL_IMMEDIATELY, 0, 1, 0, &ov)) {
        err = "store directory is in use by another store (" + path_utf8(lock_path) + ")";
        CloseHandle(h);
        return false;
    }
    handle_ = h;
    return true;
}

void DirLock::release() {
    if (handle_ != nullptr) {
        OVERLAPPED ov{};
        UnlockFileEx(static_cast<HANDLE>(handle_), 0, 1, 0, &ov);
        CloseHandle(static_cast<HANDLE>(handle_));
        handle_ = nullptr;
    }
}

#else // POSIX

File::File(File&& other) noexcept : fd_(other.fd_) { other.fd_ = -1; }
File& File::operator=(File&& other) noexcept {
    if (this != &other) {
        close();
        fd_ = other.fd_;
        other.fd_ = -1;
    }
    return *this;
}

File::Open File::open(const fs::path& path, bool create, bool truncate, std::string& err) {
    close();
    int flags = O_RDWR | O_CLOEXEC;
    if (create) flags |= O_CREAT;
    if (truncate) flags |= O_TRUNC;
    int fd;
    do {
        fd = ::open(path.c_str(), flags, 0644);
    } while (fd < 0 && errno == EINTR);
    if (fd < 0) {
        if (!create && errno == ENOENT) return Open::Missing;
        err = os_message("open", path);
        return Open::Error;
    }
    fd_ = fd;
    return Open::Ok;
}

bool File::is_open() const { return fd_ >= 0; }

void File::close() {
    if (fd_ >= 0) {
        ::close(fd_);
        fd_ = -1;
    }
}

bool File::read_at(uint64_t off, void* dst, size_t n, size_t& got, std::string& err) const {
    got = 0;
    auto* out = static_cast<uint8_t*>(dst);
    while (got < n) {
        const ssize_t r = ::pread(fd_, out + got, n - got, static_cast<off_t>(off + got));
        if (r < 0) {
            if (errno == EINTR) continue;
            err = os_message("read");
            return false;
        }
        if (r == 0) return true;
        got += static_cast<size_t>(r);
    }
    return true;
}

bool File::write_at(uint64_t off, const void* src, size_t n, std::string& err) {
    size_t put = 0;
    const auto* in = static_cast<const uint8_t*>(src);
    while (put < n) {
        const ssize_t r = ::pwrite(fd_, in + put, n - put, static_cast<off_t>(off + put));
        if (r < 0) {
            if (errno == EINTR) continue;
            err = os_message("write");
            return false;
        }
        if (r == 0) {
            err = "write: no progress";
            return false;
        }
        put += static_cast<size_t>(r);
    }
    return true;
}

bool File::sync(std::string& err) {
#if defined(__APPLE__)
    // fsync on macOS only reaches the drive's cache; F_FULLFSYNC asks the drive to flush it.
    if (::fcntl(fd_, F_FULLFSYNC) == 0) return true;
    if (::fsync(fd_) == 0) return true;
#elif defined(__linux__)
    if (::fdatasync(fd_) == 0) return true;
#else
    if (::fsync(fd_) == 0) return true;
#endif
    err = os_message("sync");
    return false;
}

bool File::size(uint64_t& out, std::string& err) const {
    struct stat st{};
    if (::fstat(fd_, &st) != 0) {
        err = os_message("stat");
        return false;
    }
    out = static_cast<uint64_t>(st.st_size);
    return true;
}

bool File::truncate(uint64_t size, std::string& err) {
    int r;
    do {
        r = ::ftruncate(fd_, static_cast<off_t>(size));
    } while (r != 0 && errno == EINTR);
    if (r != 0) {
        err = os_message("truncate");
        return false;
    }
    return true;
}

bool rename_replace(const fs::path& from, const fs::path& to, std::string& err) {
    if (::rename(from.c_str(), to.c_str()) != 0) {
        err = os_message("rename", from);
        return false;
    }
    return true;
}

bool remove_file(const fs::path& path) {
    if (::unlink(path.c_str()) == 0) return true;
    return errno == ENOENT;
}

bool sync_dir(const fs::path& dir, std::string& err) {
    int fd;
    do {
        fd = ::open(dir.c_str(), O_RDONLY | O_CLOEXEC | O_DIRECTORY);
    } while (fd < 0 && errno == EINTR);
    if (fd < 0) {
        err = os_message("open directory", dir);
        return false;
    }
    bool ok = ::fsync(fd) == 0;
    // Some file systems refuse fsync on a directory (EINVAL); the entry is then as durable as it gets.
    if (!ok && (errno == EINVAL || errno == ENOTSUP)) ok = true;
    if (!ok) err = os_message("sync directory", dir);
    ::close(fd);
    return ok;
}

DirLock::~DirLock() { release(); }

bool DirLock::acquire(const fs::path& lock_path, std::string& err) {
    release();
    int fd;
    do {
        fd = ::open(lock_path.c_str(), O_RDWR | O_CREAT | O_CLOEXEC, 0644);
    } while (fd < 0 && errno == EINTR);
    if (fd < 0) {
        err = os_message("open", lock_path);
        return false;
    }
    // flock locks belong to the open file description, so a second open of the same directory in
    // this process conflicts too (fcntl locks would not).
    if (::flock(fd, LOCK_EX | LOCK_NB) != 0) {
        err = "store directory is in use by another store (" + path_utf8(lock_path) + ")";
        ::close(fd);
        return false;
    }
    fd_ = fd;
    return true;
}

void DirLock::release() {
    if (fd_ >= 0) {
        ::flock(fd_, LOCK_UN);
        ::close(fd_);
        fd_ = -1;
    }
}

#endif

} // namespace cenda::cvstore
