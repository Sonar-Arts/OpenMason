/* Region store (#120): the cv_store_* C ABI over region files (region_file.hpp has the format).
 *
 * - Region objects live in a map keyed by (kind, rx, ry, rz), bounded by an LRU (Tuning::max_open_files).
 *   A caller PINS a region for the duration of one call; only unpinned regions are evicted, and an
 *   evicted region is committed and closed by the evicting thread while its key is "retiring" (anyone
 *   asking for that key waits), so two objects never own one file.
 * - Per region, a shared_mutex: reads take it shared (pread is positional), writes/commits exclusive.
 *   Compression happens before the lock, decompression after it, on thread-local zstd contexts.
 */
#include "region_store.hpp"

#include "crc32c.hpp"
#include "../voxel_world/cv_error.hpp"

#include <zstd.h>

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <iterator>
#include <cstring>
#include <list>
#include <string>
#include <thread>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

using cenda::cvstore::DirLock;
using cenda::cvstore::Region;
using cenda::cvstore::RegionKey;
using cenda::cvstore::RegionKeyHash;
using cenda::cvstore::RegionKind;
using cenda::cvstore::StoreContext;
using cenda::cvstore::Tuning;

struct cv_store {
    struct Slot {
        std::shared_ptr<Region> region;
        uint32_t pins = 0;
        std::list<RegionKey>::iterator lru;
    };

    StoreContext ctx;
    DirLock lock;
    std::mutex mu;
    std::condition_variable retired;
    std::unordered_map<RegionKey, Slot, RegionKeyHash> regions;
    std::list<RegionKey> lru; /* front = most recently used */
    std::unordered_set<RegionKey, RegionKeyHash> retiring;
};

namespace cenda::cvstore {

namespace {

constexpr int64_t kMaxRecord = int64_t{1} << 30;
constexpr size_t kFlushThreads = 8;

class Pin {
public:
    Pin(cv_store* store, const RegionKey& key, std::shared_ptr<Region> region)
        : store_(store), key_(key), region_(std::move(region)) {}
    ~Pin() {
        std::lock_guard lk(store_->mu);
        auto it = store_->regions.find(key_);
        if (it != store_->regions.end() && it->second.region == region_ && it->second.pins > 0) --it->second.pins;
    }
    Pin(const Pin&) = delete;
    Pin& operator=(const Pin&) = delete;
    Region& operator*() const { return *region_; }
    Region* operator->() const { return region_.get(); }

private:
    cv_store* store_;
    RegionKey key_;
    std::shared_ptr<Region> region_;
};

/* Finds or creates the region object, pinned; evicts least recently used unpinned regions over the
 * bound (commit + close outside the store mutex). */
std::unique_ptr<Pin> acquire(cv_store* s, const RegionKey& key) {
    std::vector<std::pair<RegionKey, std::shared_ptr<Region>>> victims;
    std::shared_ptr<Region> region;
    {
        std::unique_lock lk(s->mu);
        s->retired.wait(lk, [&] { return s->retiring.count(key) == 0; });
        auto it = s->regions.find(key);
        if (it != s->regions.end()) {
            ++it->second.pins;
            s->lru.splice(s->lru.begin(), s->lru, it->second.lru);
            region = it->second.region;
        } else {
            region = std::make_shared<Region>(s->ctx, key);
            s->lru.push_front(key);
            s->regions.emplace(key, cv_store::Slot{region, 1, s->lru.begin()});
        }
        const size_t bound = std::max<size_t>(1, s->ctx.tuning.max_open_files);
        for (auto vit = s->lru.end(); s->regions.size() > bound && vit != s->lru.begin();) {
            --vit;
            auto rit = s->regions.find(*vit);
            if (rit->second.pins != 0) continue;
            victims.emplace_back(*vit, std::move(rit->second.region));
            s->retiring.insert(*vit);
            s->regions.erase(rit);
            vit = s->lru.erase(vit);
        }
    }
    if (!victims.empty()) {
        std::vector<std::pair<RegionKey, std::shared_ptr<Region>>> failed;
        for (auto& [vkey, victim] : victims) {
            std::unique_lock rl(victim->mu);
            if (s->ctx.crashed.load()) {
                victim->abandon();
            } else if (victim->close() != CV_OK) {
                failed.emplace_back(vkey, victim); // keep its uncommitted writes; retry on a later eviction
            }
        }
        std::lock_guard lk(s->mu);
        for (auto& [vkey, victim] : victims) s->retiring.erase(vkey);
        for (auto& [vkey, victim] : failed) {
            s->lru.push_back(vkey);
            s->regions.emplace(vkey, cv_store::Slot{victim, 0, std::prev(s->lru.end())});
        }
        s->retired.notify_all();
    }
    return std::make_unique<Pin>(s, key, std::move(region));
}

struct ZstdContexts {
    ZSTD_CCtx* c = nullptr;
    ZSTD_DCtx* d = nullptr;
    ~ZstdContexts() {
        ZSTD_freeCCtx(c);
        ZSTD_freeDCtx(d);
    }
};
thread_local ZstdContexts tl_zstd;
thread_local std::vector<uint8_t> tl_compressed;
thread_local std::vector<uint8_t> tl_fetched;

RegionKey cube_key(int64_t cx, int64_t cy, int64_t cz, uint32_t& index) {
    index = static_cast<uint32_t>((static_cast<uint64_t>(cx) & 7u) | (static_cast<uint64_t>(cz) & 7u) << 3 |
                                  (static_cast<uint64_t>(cy) & 7u) << 6);
    return RegionKey{RegionKind::Cube, cx >> 3, cy >> 3, cz >> 3};
}

RegionKey column_key(int64_t cx, int64_t cz, uint32_t& index) {
    index = static_cast<uint32_t>((static_cast<uint64_t>(cx) & 31u) | (static_cast<uint64_t>(cz) & 31u) << 5);
    return RegionKey{RegionKind::Column, cx >> 5, 0, cz >> 5};
}

int64_t read_record(cv_store* s, const RegionKey& key, uint32_t index, uint8_t* dst, int64_t cap) {
    if (s == nullptr || cap < 0 || (cap > 0 && dst == nullptr)) {
        cv_set_error("cv_store: bad read arguments");
        return CV_ERR_ARG;
    }
    if (s->ctx.crashed.load()) {
        cv_set_error("cv_store: store crashed (simulated fault)");
        return CV_ERR_IO;
    }
    Fetched info;
    int32_t found;
    {
        auto pin = acquire(s, key);
        Region& r = **pin;
        std::shared_lock lk(r.mu);
        if (r.state() == Region::State::Unopened) {
            lk.unlock();
            {
                std::unique_lock ul(r.mu);
                const int32_t o = r.open_existing();
                if (o != CV_OK) return o;
            }
            lk.lock();
        }
        found = r.fetch(index, tl_fetched, info);
    }
    if (found <= 0) return found;
    if (int64_t{info.raw_len} > cap) return CV_NEED(int64_t{info.raw_len});
    if (info.codec == 1) {
        std::memcpy(dst, tl_fetched.data(), info.raw_len);
        return info.raw_len;
    }
    if (tl_zstd.d == nullptr) tl_zstd.d = ZSTD_createDCtx();
    if (tl_zstd.d == nullptr) {
        cv_set_error("cv_store: out of memory (zstd)");
        return CV_ERR_IO;
    }
    const size_t n = ZSTD_decompressDCtx(tl_zstd.d, dst, info.raw_len, tl_fetched.data(), tl_fetched.size());
    if (ZSTD_isError(n) || n != info.raw_len) {
        cv_set_error("cv_store: record does not decompress (%s)", ZSTD_isError(n) ? ZSTD_getErrorName(n) : "size");
        return CV_ERR_CORRUPT;
    }
    return static_cast<int64_t>(n);
}

int32_t write_record(cv_store* s, const RegionKey& key, uint32_t index, const uint8_t* src, int64_t len) {
    if (s == nullptr || len < 0 || len > kMaxRecord || (len > 0 && src == nullptr)) {
        cv_set_error("cv_store: bad write arguments (len %lld)", static_cast<long long>(len));
        return CV_ERR_ARG;
    }
    if (s->ctx.crashed.load()) {
        cv_set_error("cv_store: store crashed (simulated fault)");
        return CV_ERR_IO;
    }
    if (len == 0) {
        auto pin = acquire(s, key);
        Region& r = **pin;
        std::unique_lock lk(r.mu);
        const int32_t o = r.open_existing();
        if (o != CV_OK) return o;
        switch (r.state()) {
        case Region::State::Absent:
            return CV_OK;
        case Region::State::Open:
            return r.remove(index);
        default:
            // Incompatible: refused (CV_ERR_CORRUPT). Corrupt (no readable header): moved aside like a write.
            return r.open_for_write();
        }
    }
    const auto raw = static_cast<size_t>(len);
    const uint8_t* stored = src;
    size_t stored_len = raw;
    uint8_t codec = 1;
    if (tl_zstd.c == nullptr) tl_zstd.c = ZSTD_createCCtx();
    if (tl_zstd.c != nullptr) {
        tl_compressed.resize(ZSTD_compressBound(raw));
        const size_t n = ZSTD_compressCCtx(tl_zstd.c, tl_compressed.data(), tl_compressed.size(), src, raw,
                                           s->ctx.tuning.zstd_level);
        if (!ZSTD_isError(n) && n < raw) {
            stored = tl_compressed.data();
            stored_len = n;
            codec = 0;
        }
    }
    const uint32_t crc = crc32c(stored, stored_len);
    auto pin = acquire(s, key);
    Region& r = **pin;
    std::unique_lock lk(r.mu);
    const int32_t o = r.open_for_write();
    if (o != CV_OK) return o;
    return r.put(index, stored, static_cast<uint32_t>(stored_len), static_cast<uint32_t>(raw), codec, crc);
}

int32_t flush_all(cv_store* s) {
    // Pin every resident region (no eviction while flushing); regions evicted earlier were committed then.
    std::vector<std::unique_ptr<Pin>> pins;
    {
        std::lock_guard lk(s->mu);
        pins.reserve(s->regions.size());
        for (auto& [key, slot] : s->regions) {
            ++slot.pins;
            pins.push_back(std::make_unique<Pin>(s, key, slot.region));
        }
    }
    // Commits are fsync-bound: run them on a few threads when several files are dirty. Errors are
    // thread-local messages, so a worker's message is carried back to the calling thread.
    std::vector<size_t> dirty;
    for (size_t i = 0; i < pins.size(); ++i) {
        Region& r = **pins[i];
        std::shared_lock lk(r.mu);
        if (r.dirty()) dirty.push_back(i);
    }
    std::atomic<size_t> next{0};
    std::mutex err_mu;
    int32_t result = CV_OK;
    std::string message;
    const auto work = [&] {
        for (size_t k = next.fetch_add(1); k < dirty.size(); k = next.fetch_add(1)) {
            Region& r = **pins[dirty[k]];
            std::unique_lock lk(r.mu);
            const int32_t c = r.commit();
            if (c != CV_OK) {
                std::lock_guard el(err_mu);
                if (result == CV_OK) {
                    result = c;
                    message = cv_last_error();
                }
            }
        }
    };
    const size_t threads = std::min<size_t>(kFlushThreads, dirty.size() / 2);
    std::vector<std::thread> pool;
    for (size_t t = 1; t < threads; ++t) pool.emplace_back(work);
    work();
    for (auto& t : pool) t.join();
    if (result != CV_OK) cv_set_error("%s", message.c_str());
    return result;
}

std::shared_ptr<Region> find_resident(cv_store* s, RegionKind kind, int64_t rx, int64_t ry, int64_t rz) {
    std::lock_guard lk(s->mu);
    auto it = s->regions.find(RegionKey{kind, rx, ry, rz});
    return it == s->regions.end() ? nullptr : it->second.region;
}

} // namespace

cv_store* open_store(const char* dir_utf8, const Tuning& tuning) {
    if (dir_utf8 == nullptr || dir_utf8[0] == '\0') {
        cv_set_error("cv_store_open: empty directory");
        return nullptr;
    }
    fs::path dir;
    try {
        dir = fs::path(std::u8string(reinterpret_cast<const char8_t*>(dir_utf8)));
        std::error_code ec;
        fs::create_directories(dir, ec);
        if (ec) {
            cv_set_error("cv_store_open: cannot create %s: %s", dir_utf8, ec.message().c_str());
            return nullptr;
        }
    } catch (const std::exception& e) {
        cv_set_error("cv_store_open: %s", e.what());
        return nullptr;
    }
    auto store = std::make_unique<cv_store>();
    store->ctx.dir = dir;
    store->ctx.tuning = tuning;
    std::string err;
    if (!store->lock.acquire(dir / "cvstore.lock", err)) {
        cv_set_error("cv_store_open: %s", err.c_str());
        return nullptr;
    }
    return store.release();
}

size_t resident_regions(cv_store* store) {
    std::lock_guard lk(store->mu);
    return store->regions.size();
}

size_t open_handles(cv_store* store) {
    std::vector<std::shared_ptr<Region>> all;
    {
        std::lock_guard lk(store->mu);
        for (const auto& [key, slot] : store->regions) all.push_back(slot.region);
    }
    size_t n = 0;
    for (auto& r : all) {
        std::shared_lock lk(r->mu);
        if (r->state() == Region::State::Open) ++n;
    }
    return n;
}

uint64_t region_file_sectors(cv_store* store, RegionKind kind, int64_t rx, int64_t ry, int64_t rz) {
    auto r = find_resident(store, kind, rx, ry, rz);
    if (!r) return 0;
    std::shared_lock lk(r->mu);
    return r->file_sectors();
}

uint64_t region_generation(cv_store* store, RegionKind kind, int64_t rx, int64_t ry, int64_t rz) {
    auto r = find_resident(store, kind, rx, ry, rz);
    if (!r) return 0;
    std::shared_lock lk(r->mu);
    return r->generation();
}

} // namespace cenda::cvstore

extern "C" {

cv_store* cv_store_open(const char* dir_utf8) { return cenda::cvstore::open_store(dir_utf8, Tuning{}); }

int32_t cv_store_flush(cv_store* store) {
    if (store == nullptr) {
        cv_set_error("cv_store_flush: null store");
        return CV_ERR_ARG;
    }
    if (store->ctx.crashed.load()) {
        cv_set_error("cv_store: store crashed (simulated fault)");
        return CV_ERR_IO;
    }
    return cenda::cvstore::flush_all(store);
}

int32_t cv_store_close(cv_store* store) {
    if (store == nullptr) return CV_OK;
    int32_t result = CV_OK;
    if (!store->ctx.crashed.load()) result = cenda::cvstore::flush_all(store);
    {
        std::lock_guard lk(store->mu);
        for (auto& [key, slot] : store->regions) {
            std::unique_lock rl(slot.region->mu);
            if (store->ctx.crashed.load()) {
                slot.region->abandon();
            } else {
                const int32_t c = slot.region->close();
                if (c != CV_OK && result == CV_OK) result = c;
                if (c != CV_OK) slot.region->abandon();
            }
        }
        store->regions.clear();
        store->lru.clear();
    }
    store->lock.release();
    delete store;
    return result;
}

int64_t cv_store_read_cube(cv_store* store, int64_t cx, int64_t cy, int64_t cz, uint8_t* dst, int64_t cap) {
    uint32_t index = 0;
    const RegionKey key = cenda::cvstore::cube_key(cx, cy, cz, index);
    return cenda::cvstore::read_record(store, key, index, dst, cap);
}

int32_t cv_store_write_cube(cv_store* store, int64_t cx, int64_t cy, int64_t cz, const uint8_t* src, int64_t len) {
    uint32_t index = 0;
    const RegionKey key = cenda::cvstore::cube_key(cx, cy, cz, index);
    return cenda::cvstore::write_record(store, key, index, src, len);
}

int64_t cv_store_read_column(cv_store* store, int64_t cx, int64_t cz, uint8_t* dst, int64_t cap) {
    uint32_t index = 0;
    const RegionKey key = cenda::cvstore::column_key(cx, cz, index);
    return cenda::cvstore::read_record(store, key, index, dst, cap);
}

int32_t cv_store_write_column(cv_store* store, int64_t cx, int64_t cz, const uint8_t* src, int64_t len) {
    uint32_t index = 0;
    const RegionKey key = cenda::cvstore::column_key(cx, cz, index);
    return cenda::cvstore::write_record(store, key, index, src, len);
}

} // extern "C"
