// Octree LOD runtime persistence: the store meta ("lod.meta", checked on create) and the deferred dirty set
// ("deferred.bin", loaded on create, saved on flush / destroy). Shared runtime structures: lod_runtime.hpp.
#include "lod_runtime.hpp"
#include "cv_error.hpp"

#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <ios>
#include <iterator>
#include <mutex>
#include <string>
#include <system_error>
#include <vector>

namespace cenda::voxel::lod {

namespace {

// ---- meta ------------------------------------------------------------------------------------------------------
constexpr uint32_t kMetaMagic = 0x4D4C5643u;   // "CVLM"
constexpr uint32_t kMetaVersion = 1;

std::vector<uint8_t> meta_bytes(const cv_lod* L) {
    std::vector<uint8_t> o;
    auto p32 = [&](uint32_t v) { for (int i = 0; i < 4; ++i) o.push_back(static_cast<uint8_t>(v >> (8 * i))); };
    auto p64 = [&](uint64_t v) { for (int i = 0; i < 8; ++i) o.push_back(static_cast<uint8_t>(v >> (8 * i))); };
    p32(kMetaMagic);
    p32(kMetaVersion);
    p32(kEdge);
    o.push_back(static_cast<uint8_t>(L->air));
    o.push_back(static_cast<uint8_t>(L->air >> 8));
    o.push_back(static_cast<uint8_t>(L->fill));
    o.push_back(static_cast<uint8_t>(L->fill >> 8));
    p64(static_cast<uint64_t>(L->min_y));
    p64(static_cast<uint64_t>(L->max_y));
    p64(L->content_version);
    p32(static_cast<uint32_t>(L->max_level));
    return o;
}

std::filesystem::path upath(const std::string& utf8) {
    return std::filesystem::path(std::u8string(reinterpret_cast<const char8_t*>(utf8.c_str())));
}

} // namespace

// CV_OK, or CV_ERR_CORRUPT (mismatch, message set) / CV_ERR_IO.
int32_t open_meta(cv_lod* L, bool reset_on_mismatch) {
    namespace fs = std::filesystem;
    std::error_code ec;
    fs::create_directories(upath(L->dir), ec);
    if (ec) {
        cv_set_error("lod store: cannot create %s: %s", L->dir.c_str(), ec.message().c_str());
        return CV_ERR_IO;
    }
    const fs::path meta = upath(L->dir) / "lod.meta";
    const std::vector<uint8_t> want = meta_bytes(L);
    bool write = true;
    if (fs::exists(meta, ec)) {
        std::ifstream in(meta, std::ios::binary);
        std::vector<uint8_t> have((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
        if (have == want) {
            write = false;
        } else if (!reset_on_mismatch) {
            cv_set_error("lod store mismatch in %s (format, node edge, air/fill id, y range, content_version or "
                         "max_level differ); pass CV_LOD_RESET_ON_MISMATCH to rebuild it", L->dir.c_str());
            return CV_ERR_CORRUPT;
        } else {
            for (int lv = 0; lv < kLevels; ++lv) {
                fs::remove_all(upath(L->dir) / ("l" + std::to_string(lv)), ec);
            }
        }
    }
    if (write) {
        const fs::path tmp = upath(L->dir) / "lod.meta.tmp";
        {
            std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
            out.write(reinterpret_cast<const char*>(want.data()), static_cast<std::streamsize>(want.size()));
            out.flush();
            if (!out) {
                cv_set_error("lod store: cannot write %s", tmp.string().c_str());
                return CV_ERR_IO;
            }
        }
        fs::rename(tmp, meta, ec);
        if (ec) {
            cv_set_error("lod store: cannot write %s: %s", meta.string().c_str(), ec.message().c_str());
            return CV_ERR_IO;
        }
    }
    return CV_OK;
}

// ---- deferred dirty set ("deferred.bin": u32 magic "CVLD", u32 count, count x (i32 level, i64 x, y, z)) --------
namespace {

constexpr uint32_t kDeferredMagic = 0x444C5643u;   // "CVLD"

} // namespace

void save_deferred(cv_lod* L) {
    if (!L->persist) return;
    std::vector<NodeKey> keys;
    {
        std::lock_guard<std::mutex> g(L->def_m);
        keys.assign(L->deferred.begin(), L->deferred.end());
    }
    namespace fs = std::filesystem;
    std::error_code ec;
    const fs::path file = upath(L->dir) / "deferred.bin";
    if (keys.empty()) {
        fs::remove(file, ec);
        return;
    }
    std::vector<uint8_t> o;
    auto p32 = [&](uint32_t v) { for (int i = 0; i < 4; ++i) o.push_back(static_cast<uint8_t>(v >> (8 * i))); };
    auto p64 = [&](uint64_t v) { for (int i = 0; i < 8; ++i) o.push_back(static_cast<uint8_t>(v >> (8 * i))); };
    p32(kDeferredMagic);
    p32(static_cast<uint32_t>(keys.size()));
    for (const NodeKey& k : keys) {
        p32(static_cast<uint32_t>(k.level));
        p64(static_cast<uint64_t>(k.x));
        p64(static_cast<uint64_t>(k.y));
        p64(static_cast<uint64_t>(k.z));
    }
    const fs::path tmp = upath(L->dir) / "deferred.bin.tmp";
    {
        std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
        out.write(reinterpret_cast<const char*>(o.data()), static_cast<std::streamsize>(o.size()));
        if (!out) {
            L->errors_total.fetch_add(1);
            return;
        }
    }
    fs::rename(tmp, file, ec);
    if (ec) L->errors_total.fetch_add(1);
}

void load_deferred(cv_lod* L) {
    if (!L->persist) return;
    std::ifstream in(upath(L->dir) / "deferred.bin", std::ios::binary);
    if (!in) return;
    std::vector<uint8_t> b((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    auto g32 = [&](size_t at) { uint32_t v = 0; for (int i = 0; i < 4; ++i) v |= static_cast<uint32_t>(b[at + static_cast<size_t>(i)]) << (8 * i); return v; };
    auto g64 = [&](size_t at) { uint64_t v = 0; for (int i = 0; i < 8; ++i) v |= static_cast<uint64_t>(b[at + static_cast<size_t>(i)]) << (8 * i); return v; };
    if (b.size() < 8 || g32(0) != kDeferredMagic || b.size() != 8 + size_t{g32(4)} * 28) {
        L->corrupt_total.fetch_add(1);
        return;
    }
    std::lock_guard<std::mutex> g(L->def_m);
    for (size_t at = 8; at < b.size(); at += 28) {
        const NodeKey k{static_cast<int32_t>(g32(at)), static_cast<int64_t>(g64(at + 4)), static_cast<int64_t>(g64(at + 12)),
                        static_cast<int64_t>(g64(at + 20))};
        if (k.level >= 1 && k.level <= L->max_level) L->deferred.insert(k);
    }
}

} // namespace cenda::voxel::lod
