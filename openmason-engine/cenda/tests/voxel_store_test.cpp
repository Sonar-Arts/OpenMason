// Cenda region store (cv_store_*, OpenMason #120) conformance: round trips, persistence, many files,
// concurrency, simulated crashes at every commit step (+ torn / lost writes), corruption detection,
// compaction, external records, the handle LRU, and a throughput benchmark (printed, never failing).
// Plain asserts as the other kernel tests. The store sources are compiled into this test directly
// (object library) so the internal tuning / fault seams are reachable; cv_set_error is stubbed below.

#include "cenda/voxel_world.h"

#include "../native/kernels/src/voxel_store/crc32c.hpp"
#include "../native/kernels/src/voxel_store/region_store.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <map>
#include <random>
#include <string>
#include <thread>
#include <tuple>
#include <vector>
#include <utility>

namespace fs = std::filesystem;
using namespace cenda::cvstore;

// ---- cv_set_error stub (the voxel_world core owns the real definition) -----------------------
namespace {
thread_local char g_error[512];
}
void cv_set_error(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    std::vsnprintf(g_error, sizeof(g_error), fmt, ap);
    va_end(ap);
}
extern "C" const char* cv_last_error(void) { return g_error; }

namespace {

int failures = 0;

#define CHECK(cond)                                                                       \
    do {                                                                                  \
        if (!(cond)) {                                                                    \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s  [last error: %s]\n", __FILE__, \
                         __LINE__, #cond, g_error);                                       \
            ++failures;                                                                   \
        }                                                                                 \
    } while (0)

// ---- helpers ---------------------------------------------------------------------------------

struct TempDir {
    fs::path path;
    explicit TempDir(const std::string& name) {
        static std::atomic<int> counter{0};
        const auto stamp = std::chrono::steady_clock::now().time_since_epoch().count();
        path = fs::temp_directory_path() /
               ("cvstore_test_" + name + "_" + std::to_string(stamp) + "_" + std::to_string(counter++));
        fs::remove_all(path);
        fs::create_directories(path);
    }
    ~TempDir() {
        std::error_code ec;
        fs::remove_all(path, ec);
    }
    std::string str() const { return path.string(); }
    fs::path file(const std::string& name) const { return path / name; }
};

uint64_t splitmix(uint64_t& s) {
    uint64_t z = (s += 0x9E3779B97F4A7C15ull);
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
    return z ^ (z >> 31);
}

// Deterministic record content. compressible: runs of repeated u16 "block ids" with some noise (what a
// paletted cube encoding looks like); otherwise random bytes. The first 16 bytes carry seed and length.
std::vector<uint8_t> make_record(uint64_t seed, size_t len, bool compressible = true) {
    std::vector<uint8_t> out(len);
    uint64_t s = seed * 0x2545F4914F6CDD1Dull + len;
    size_t i = 0;
    for (; i < len && i < 16; ++i) out[i] = static_cast<uint8_t>((i < 8 ? seed : uint64_t{len}) >> (8 * (i % 8)));
    if (!compressible) {
        for (; i < len; ++i) out[i] = static_cast<uint8_t>(splitmix(s));
        return out;
    }
    while (i < len) {
        const uint64_t r = splitmix(s);
        const auto id = static_cast<uint16_t>(r % 12);
        size_t run = 4 + (r >> 8) % 60;
        if ((r >> 20) % 8 == 0) { // noise burst
            for (; run > 0 && i < len; --run) out[i++] = static_cast<uint8_t>(splitmix(s));
            continue;
        }
        for (; run > 0 && i < len; --run, ++i) out[i] = static_cast<uint8_t>(i % 2 == 0 ? id : id >> 8);
    }
    return out;
}

std::vector<uint8_t> read_cube(cv_store* s, int64_t cx, int64_t cy, int64_t cz, int64_t* ret = nullptr) {
    std::vector<uint8_t> buf(64);
    int64_t r = cv_store_read_cube(s, cx, cy, cz, buf.data(), static_cast<int64_t>(buf.size()));
    while (CV_IS_NEED(r)) { // loop: a concurrent writer may grow the record between the two reads
        buf.resize(static_cast<size_t>(CV_NEEDED(r)));
        r = cv_store_read_cube(s, cx, cy, cz, buf.data(), static_cast<int64_t>(buf.size()));
    }
    if (ret != nullptr) *ret = r;
    buf.resize(r > 0 ? static_cast<size_t>(r) : 0);
    return buf;
}

std::vector<uint8_t> read_column(cv_store* s, int64_t cx, int64_t cz, int64_t* ret = nullptr) {
    std::vector<uint8_t> buf(64);
    int64_t r = cv_store_read_column(s, cx, cz, buf.data(), static_cast<int64_t>(buf.size()));
    while (CV_IS_NEED(r)) { // loop: a concurrent writer may grow the record between the two reads
        buf.resize(static_cast<size_t>(CV_NEEDED(r)));
        r = cv_store_read_column(s, cx, cz, buf.data(), static_cast<int64_t>(buf.size()));
    }
    if (ret != nullptr) *ret = r;
    buf.resize(r > 0 ? static_cast<size_t>(r) : 0);
    return buf;
}

int32_t write_cube(cv_store* s, int64_t cx, int64_t cy, int64_t cz, const std::vector<uint8_t>& v) {
    return cv_store_write_cube(s, cx, cy, cz, v.data(), static_cast<int64_t>(v.size()));
}

std::vector<uint8_t> slurp(const fs::path& p) {
    std::ifstream in(p, std::ios::binary);
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
}

void spit(const fs::path& p, const std::vector<uint8_t>& bytes) {
    std::ofstream out(p, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
}

size_t count_files(const fs::path& dir, const std::string& suffix) {
    size_t n = 0;
    for (const auto& e : fs::directory_iterator(dir)) {
        const std::string name = e.path().filename().string();
        if (name.size() >= suffix.size() && name.compare(name.size() - suffix.size(), suffix.size(), suffix) == 0) ++n;
    }
    return n;
}

constexpr uint32_t kCubeHeaderSectors = 8; // 2 slots x 4 sectors

// ---- fault hook ----------------------------------------------------------------------------------
struct Fault {
    bool armed = false;
    FaultPoint point = FaultPoint::AfterDataWrite;
    FaultAction action = FaultAction::Crash;
    int fired = 0;
} g_fault;

FaultAction fault_hook(void*, FaultPoint point) {
    if (!g_fault.armed || point != g_fault.point) return FaultAction::Continue;
    g_fault.armed = false;
    ++g_fault.fired;
    return g_fault.action;
}

// ---- tests -----------------------------------------------------------------------------------------

void crc32c_matches_the_reference() {
    const char* v = "123456789";
    CHECK(crc32c(v, 9) == 0xE3069283u);
    CHECK(crc32c(v, 4, crc32c(v, 0)) == crc32c(v, 4));
    CHECK(crc32c(v + 4, 5, crc32c(v, 4)) == 0xE3069283u); // incremental
    std::vector<uint8_t> zeros(32, 0);
    CHECK(crc32c(zeros.data(), zeros.size()) == 0x8A9136AAu);
}

void cubes_and_columns_round_trip() {
    TempDir dir("roundtrip");
    cv_store* s = cv_store_open(dir.str().c_str());
    CHECK(s != nullptr);
    const int64_t big = INT64_C(1) << 40;
    const std::vector<std::tuple<int64_t, int64_t, int64_t>> coords = {
        {0, 0, 0}, {7, 7, 7}, {8, 0, 0}, {-1, -1, -1}, {-8, -9, -17}, {3, -12, 19},
        {big, -big, big + 3}, {INT64_MAX, INT64_MIN, INT64_MAX}, {INT64_MIN, INT64_MAX, -1}};
    const std::vector<size_t> sizes = {1, 17, 100, 4095, 4096, 4097, 8000, 70000, 300000};
    uint64_t seed = 1;
    std::map<std::tuple<int64_t, int64_t, int64_t>, std::vector<uint8_t>> expect;
    for (const auto& [cx, cy, cz] : coords) {
        for (size_t si = 0; si < 2; ++si) {
            // two records per coordinate pattern: neighbour in the same region (index differs)
            const int64_t x = cx + static_cast<int64_t>(si) * (cx == INT64_MAX ? -1 : 1);
            auto rec = make_record(seed, sizes[seed % sizes.size()], seed % 3 != 0);
            ++seed;
            CHECK(write_cube(s, x, cy, cz, rec) == CV_OK);
            expect[{x, cy, cz}] = rec;
        }
    }
    for (const auto& [k, v] : expect) {
        int64_t r = 0;
        CHECK(read_cube(s, std::get<0>(k), std::get<1>(k), std::get<2>(k), &r) == v);
        CHECK(r == static_cast<int64_t>(v.size()));
    }
    // columns, incl. the same numbers as cubes (separate files)
    std::map<std::pair<int64_t, int64_t>, std::vector<uint8_t>> cols;
    for (const auto& [cx, cy, cz] : coords) {
        auto rec = make_record(seed, 200 + static_cast<size_t>(seed % 5000));
        ++seed;
        CHECK(cv_store_write_column(s, cx, cz, rec.data(), static_cast<int64_t>(rec.size())) == CV_OK);
        cols[{cx, cz}] = rec;
    }
    for (const auto& [k, v] : cols) CHECK(read_column(s, k.first, k.second) == v);
    // absent records answer 0; absent regions create no file
    std::vector<uint8_t> buf(16);
    CHECK(cv_store_read_cube(s, 1000, 1000, 1000, buf.data(), 16) == 0);
    CHECK(cv_store_read_column(s, 99999, 5, buf.data(), 16) == 0);
    CHECK(cv_store_read_cube(s, 2, 0, 0, buf.data(), 16) == 0); // same region as (0,0,0), empty slot
    CHECK(!fs::exists(dir.file("r.125.125.125.cvr")));
    // file names: negative coordinates floor
    CHECK(fs::exists(dir.file("r.-1.-2.-3.cvr")));  // (-8, -9, -17)
    CHECK(fs::exists(dir.file("r.0.-2.2.cvr")));     // (3, -12, 19)
    CHECK(fs::exists(dir.file("c.-1.-1.cvc")));
    CHECK(cv_store_close(s) == CV_OK);
    CHECK(!fs::exists(dir.file("r.125.125.125.cvr")));
}

void buffer_protocol_and_bad_arguments() {
    TempDir dir("args");
    cv_store* s = cv_store_open(dir.str().c_str());
    auto rec = make_record(5, 5000);
    CHECK(write_cube(s, 1, 2, 3, rec) == CV_OK);
    std::vector<uint8_t> small(100);
    const int64_t r = cv_store_read_cube(s, 1, 2, 3, small.data(), 100);
    CHECK(CV_IS_NEED(r) && CV_NEEDED(r) == 5000);
    CHECK(cv_store_read_cube(s, 1, 2, 3, nullptr, 0) == CV_NEED(5000));
    CHECK(cv_store_read_cube(s, 1, 2, 3, nullptr, 10) == CV_ERR_ARG);
    CHECK(cv_store_read_cube(s, 1, 2, 3, small.data(), -1) == CV_ERR_ARG);
    CHECK(cv_store_write_cube(s, 1, 2, 3, nullptr, 10) == CV_ERR_ARG);
    CHECK(cv_store_write_cube(s, 1, 2, 3, rec.data(), -5) == CV_ERR_ARG);
    CHECK(cv_store_write_cube(nullptr, 1, 2, 3, rec.data(), 5) == CV_ERR_ARG);
    CHECK(cv_store_read_cube(nullptr, 1, 2, 3, small.data(), 5) == CV_ERR_ARG);
    CHECK(cv_store_flush(nullptr) == CV_ERR_ARG);
    CHECK(cv_store_open(nullptr) == nullptr);
    CHECK(cv_store_open("") == nullptr);
    // exact-size buffer
    std::vector<uint8_t> exact(5000);
    CHECK(cv_store_read_cube(s, 1, 2, 3, exact.data(), 5000) == 5000 && exact == rec);
    CHECK(cv_store_close(s) == CV_OK);
    CHECK(cv_store_close(nullptr) == CV_OK);
}

void the_directory_is_locked_while_open() {
    TempDir dir("lock");
    cv_store* a = cv_store_open(dir.str().c_str());
    CHECK(a != nullptr);
    cv_store* b = cv_store_open(dir.str().c_str());
    CHECK(b == nullptr);
    CHECK(std::strstr(g_error, "in use") != nullptr);
    CHECK(cv_store_close(a) == CV_OK);
    b = cv_store_open(dir.str().c_str());
    CHECK(b != nullptr);
    cv_store_close(b);
    // a nested directory that does not exist yet is created
    cv_store* c = cv_store_open((dir.path / "x" / "y").string().c_str());
    CHECK(c != nullptr);
    cv_store_close(c);
}

void overwrite_delete_and_reopen() {
    TempDir dir("overwrite");
    {
        cv_store* s = cv_store_open(dir.str().c_str());
        for (int i = 0; i < 64; ++i) CHECK(write_cube(s, i % 8, i / 8 % 8, 0, make_record(100 + static_cast<uint64_t>(i), 3000)) == CV_OK);
        // overwrite half with different sizes, delete a quarter
        for (int i = 0; i < 64; i += 2) CHECK(write_cube(s, i % 8, i / 8 % 8, 0, make_record(500 + static_cast<uint64_t>(i), 9000)) == CV_OK);
        for (int i = 1; i < 64; i += 4) CHECK(cv_store_write_cube(s, i % 8, i / 8 % 8, 0, nullptr, 0) == CV_OK);
        CHECK(cv_store_write_cube(s, 1, 0, 0, nullptr, 0) == CV_OK);     // deleting twice is fine
        CHECK(cv_store_write_cube(s, 900, 0, 0, nullptr, 0) == CV_OK);   // deleting in an absent region
        CHECK(!fs::exists(dir.file("r.112.0.0.cvr")));
        CHECK(cv_store_write_column(s, 4, 4, nullptr, 0) == CV_OK);
        CHECK(cv_store_close(s) == CV_OK);
    }
    cv_store* s = cv_store_open(dir.str().c_str());
    for (int i = 0; i < 64; ++i) {
        int64_t r = 0;
        auto got = read_cube(s, i % 8, i / 8 % 8, 0, &r);
        if (i % 4 == 1) {
            CHECK(r == 0);
        } else if (i % 2 == 0) {
            CHECK(got == make_record(500 + static_cast<uint64_t>(i), 9000));
        } else {
            CHECK(got == make_record(100 + static_cast<uint64_t>(i), 3000));
        }
    }
    // overwrite after reopen, flush, reopen again
    CHECK(write_cube(s, 0, 0, 0, make_record(77, 123)) == CV_OK);
    CHECK(cv_store_flush(s) == CV_OK);
    CHECK(cv_store_close(s) == CV_OK);
    s = cv_store_open(dir.str().c_str());
    CHECK(read_cube(s, 0, 0, 0) == make_record(77, 123));
    cv_store_close(s);
}

void many_records_across_many_files() {
    TempDir dir("many");
    Tuning t;
    t.max_open_files = 16; // forces evictions along the way
    cv_store* s = open_store(dir.str().c_str(), t);
    int n = 0;
    for (int64_t rx = -3; rx < 3; ++rx)
        for (int64_t ry = -2; ry < 2; ++ry)
            for (int64_t i = 0; i < 40; ++i) {
                const int64_t cx = rx * 8 + i % 8, cy = ry * 8 + i / 8, cz = (rx + ry) * 8 + i % 3;
                CHECK(write_cube(s, cx, cy, cz, make_record(static_cast<uint64_t>(cx * 31 + cy * 7 + cz) + 1000000,
                                                            static_cast<size_t>(2000 + (i * 97) % 6000))) == CV_OK);
                ++n;
            }
    for (int64_t cx = -40; cx < 40; cx += 3)
        for (int64_t cz = -40; cz < 40; cz += 5) {
            auto rec = make_record(static_cast<uint64_t>(cx * 1000 + cz) + 7, 1500);
            CHECK(cv_store_write_column(s, cx, cz, rec.data(), static_cast<int64_t>(rec.size())) == CV_OK);
        }
    CHECK(resident_regions(s) <= 16);
    CHECK(cv_store_close(s) == CV_OK);
    CHECK(count_files(dir.path, ".cvr") == 24);
    CHECK(count_files(dir.path, ".cvc") == 16); // cx -40..38 and cz -40..35 span region columns -2..1
    s = open_store(dir.str().c_str(), t);
    int ok = 0;
    for (int64_t rx = -3; rx < 3; ++rx)
        for (int64_t ry = -2; ry < 2; ++ry)
            for (int64_t i = 0; i < 40; ++i) {
                const int64_t cx = rx * 8 + i % 8, cy = ry * 8 + i / 8, cz = (rx + ry) * 8 + i % 3;
                ok += read_cube(s, cx, cy, cz) ==
                              make_record(static_cast<uint64_t>(cx * 31 + cy * 7 + cz) + 1000000,
                                          static_cast<size_t>(2000 + (i * 97) % 6000))
                          ? 1
                          : 0;
            }
    CHECK(ok == n);
    for (int64_t cx = -40; cx < 40; cx += 3)
        for (int64_t cz = -40; cz < 40; cz += 5)
            CHECK(read_column(s, cx, cz) == make_record(static_cast<uint64_t>(cx * 1000 + cz) + 7, 1500));
    cv_store_close(s);
}

void handle_lru_evicts_and_commits() {
    TempDir dir("lru");
    Tuning t;
    t.max_open_files = 4;
    t.commit_every = 1u << 30; // only eviction / flush commit
    t.commit_bytes = ~0ull;
    cv_store* s = open_store(dir.str().c_str(), t);
    for (int64_t r = 0; r < 20; ++r) CHECK(write_cube(s, r * 8, 0, 0, make_record(static_cast<uint64_t>(r), 3000)) == CV_OK);
    CHECK(resident_regions(s) <= 4);
    CHECK(open_handles(s) <= 4);
    // evicted regions were committed: their generation on disk is >= 1. Read them back through the cache.
    for (int64_t r = 0; r < 20; ++r) CHECK(read_cube(s, r * 8, 0, 0) == make_record(static_cast<uint64_t>(r), 3000));
    CHECK(resident_regions(s) <= 4);
    // concurrent traffic over more regions than the bound
    std::vector<std::thread> threads;
    std::atomic<int> bad{0};
    for (int th = 0; th < 8; ++th)
        threads.emplace_back([&, th] {
            for (int k = 0; k < 300; ++k) {
                const int64_t r = (th * 7 + k * 3) % 20;
                if (k % 3 == 0) {
                    if (write_cube(s, r * 8 + 1 + th % 7, 0, 0, make_record(static_cast<uint64_t>(th * 100000 + k), 2500)) != CV_OK) {
                        std::fprintf(stderr, "lru write failed: %s\n", g_error);
                        ++bad;
                    }
                } else {
                    int64_t ret = 0;
                    if (read_cube(s, r * 8, 0, 0, &ret) != make_record(static_cast<uint64_t>(r), 3000)) {
                        std::fprintf(stderr, "lru read %lld failed: ret %lld %s\n", static_cast<long long>(r),
                                     static_cast<long long>(ret), g_error);
                        ++bad;
                    }
                }
            }
        });
    for (auto& th : threads) th.join();
    CHECK(bad.load() == 0);
    CHECK(cv_store_close(s) == CV_OK);
    s = cv_store_open(dir.str().c_str());
    for (int64_t r = 0; r < 20; ++r) CHECK(read_cube(s, r * 8, 0, 0) == make_record(static_cast<uint64_t>(r), 3000));
    cv_store_close(s);
}

void concurrent_readers_and_writers() {
    TempDir dir("concurrent");
    Tuning t;
    t.commit_every = 32;
    t.max_open_files = 8;
    cv_store* s = open_store(dir.str().c_str(), t);
    constexpr int kWriters = 6, kReaders = 6, kKeys = 64, kRounds = 120;
    // key k lives at cube (k % 16, k / 16, 0) -> regions (0..1, 0..0): shared files between writers.
    // Record content embeds (key, version): seed = key * 1000 + version.
    std::vector<std::atomic<int>> latest(kKeys);
    for (auto& l : latest) l.store(-1);
    std::atomic<bool> stop{false};
    std::atomic<int> bad{0}, reads{0};
    std::vector<std::thread> threads;
    for (int w = 0; w < kWriters; ++w)
        threads.emplace_back([&, w] {
            for (int round = 0; round < kRounds; ++round)
                for (int k = w; k < kKeys; k += kWriters) {
                    const auto len = static_cast<size_t>(1000 + (k * 131 + round * 17) % 9000);
                    if (write_cube(s, k % 16, k / 16, 0, make_record(static_cast<uint64_t>(k * 1000 + round), len)) != CV_OK)
                        ++bad;
                    latest[static_cast<size_t>(k)].store(round);
                    if (round % 40 == 39 && k == w && cv_store_flush(s) != CV_OK) ++bad;
                }
        });
    for (int r = 0; r < kReaders; ++r)
        threads.emplace_back([&, r] {
            uint64_t rng = static_cast<uint64_t>(r) + 99;
            while (!stop.load()) {
                const int k = static_cast<int>(splitmix(rng) % kKeys);
                const int floor_version = latest[static_cast<size_t>(k)].load();
                int64_t ret = 0;
                auto got = read_cube(s, k % 16, k / 16, 0, &ret);
                reads.fetch_add(1);
                if (ret < 0) {
                    ++bad;
                    continue;
                }
                if (ret == 0) {
                    if (floor_version >= 0) ++bad;
                    continue;
                }
                // which version is it? decode the seed from the first 8 bytes, check the whole record
                uint64_t seed = 0;
                for (int i = 7; i >= 0; --i) seed = seed << 8 | got[static_cast<size_t>(i)];
                const auto version = static_cast<int>(seed % 1000);
                if (static_cast<int>(seed / 1000) != k || version < floor_version || got != make_record(seed, got.size()))
                    ++bad;
            }
        });
    for (int w = 0; w < kWriters; ++w) threads[static_cast<size_t>(w)].join();
    stop.store(true);
    for (size_t i = kWriters; i < threads.size(); ++i) threads[i].join();
    CHECK(bad.load() == 0);
    CHECK(reads.load() > 100);
    CHECK(cv_store_close(s) == CV_OK);
    s = cv_store_open(dir.str().c_str());
    for (int k = 0; k < kKeys; ++k) {
        const auto len = static_cast<size_t>(1000 + (k * 131 + (kRounds - 1) * 17) % 9000);
        CHECK(read_cube(s, k % 16, k / 16, 0) == make_record(static_cast<uint64_t>(k * 1000 + kRounds - 1), len));
    }
    cv_store_close(s);
}

// One crash scenario: v1 (and neighbours) committed, then v2 written with a fault at `point`, the
// process "dies", the file optionally suffers `damage`, and a fresh store must read v1 or v2 exactly
// (`expect_new`: -1 either, 0 old, 1 new) and every neighbour unchanged.
enum class Damage { None, GarbageNewData, TruncateToOld, RevertHeader, GarbageHeader };

void crash_case(FaultPoint point, FaultAction action, Damage damage, int expect_new, bool external, bool delete_v2,
                const char* label) {
    TempDir dir("crash");
    Tuning t;
    t.commit_every = 1; // every write commits, so commit-phase faults fire inside the write
    t.hook = fault_hook;
    t.compact_min_dead_sectors = 1u << 30; // keep layout simple
    if (external) t.external_threshold = 4096;
    const fs::path file = dir.file("r.0.0.0.cvr");
    const auto v1 = make_record(1, 6000, !external);
    const auto v2 = make_record(2, 7000, !external);
    {
        cv_store* s = open_store(dir.str().c_str(), t);
        CHECK(write_cube(s, 0, 0, 0, v1) == CV_OK);
        for (int64_t i = 1; i < 6; ++i) CHECK(write_cube(s, i, 0, 0, make_record(static_cast<uint64_t>(10 + i), 3000)) == CV_OK);
        CHECK(cv_store_write_cube(s, 3, 0, 0, nullptr, 0) == CV_OK); // a hole new data may land in
        CHECK(cv_store_flush(s) == CV_OK);
        const auto before = slurp(file);
        g_fault = Fault{true, point, action, 0};
        const int32_t r = delete_v2 ? cv_store_write_cube(s, 0, 0, 0, nullptr, 0) : write_cube(s, 0, 0, 0, v2);
        if (g_fault.fired == 0) {
            std::fprintf(stderr, "crash case %s: fault never fired\n", label);
            ++failures;
        }
        CHECK(r == CV_ERR_IO || point == FaultPoint::AfterCommit);
        CHECK(cv_store_write_cube(s, 7, 0, 0, v1.data(), 10) == CV_ERR_IO); // dead after the crash
        cv_store_close(s);
        auto after = slurp(file);
        const size_t data_from = size_t{kCubeHeaderSectors} * 4096;
        const size_t header_to = data_from;
        uint64_t rng = 12345;
        switch (damage) {
        case Damage::None:
            break;
        case Damage::GarbageNewData: // unsynced data: anything may be on disk where it changed
            for (size_t i = data_from; i < after.size(); ++i)
                if (i >= before.size() || after[i] != before[i]) after[i] = static_cast<uint8_t>(splitmix(rng));
            break;
        case Damage::TruncateToOld:
            after.resize(std::min(after.size(), before.size()));
            break;
        case Damage::RevertHeader: // the header write never reached the disk
            for (size_t i = 0; i < header_to && i < before.size(); ++i) after[i] = before[i];
            break;
        case Damage::GarbageHeader: // ... or reached it torn
            for (size_t i = 0; i < header_to && i < before.size(); ++i)
                if (after[i] != before[i]) after[i] = static_cast<uint8_t>(splitmix(rng));
            break;
        }
        spit(file, after);
    }
    cv_store* s = cv_store_open(dir.str().c_str());
    int64_t ret = 0;
    const auto got = read_cube(s, 0, 0, 0, &ret);
    const bool is_old = ret > 0 && got == v1;
    const bool is_new = delete_v2 ? ret == 0 : (ret > 0 && got == v2);
    if (!(is_old || is_new) || (expect_new == 0 && !is_old) || (expect_new == 1 && !is_new)) {
        std::fprintf(stderr, "crash case %s: ret %lld old %d new %d (expected %d) [%s]\n", label,
                     static_cast<long long>(ret), is_old, is_new, expect_new, g_error);
        ++failures;
    }
    for (int64_t i = 1; i < 6; ++i) {
        int64_t r = 0;
        const auto n = read_cube(s, i, 0, 0, &r);
        if (i == 3) CHECK(r == 0);
        else CHECK(n == make_record(static_cast<uint64_t>(10 + i), 3000));
    }
    // the store keeps working on the recovered file
    CHECK(write_cube(s, 0, 0, 0, v2) == CV_OK);
    CHECK(cv_store_flush(s) == CV_OK);
    CHECK(read_cube(s, 0, 0, 0) == v2);
    cv_store_close(s);
}

void crashes_leave_the_old_or_the_new_record() {
    using P = FaultPoint;
    using A = FaultAction;
    for (int ext = 0; ext < 2; ++ext) {
        const bool e = ext == 1;
        for (int del = 0; del < 2; ++del) {
            const bool d = del == 1;
            if (!d) {
                if (!e) {
                    crash_case(P::AfterDataWrite, A::Crash, Damage::None, 0, e, d, "after data write");
                    crash_case(P::AfterDataWrite, A::Crash, Damage::GarbageNewData, 0, e, d, "after data write, data lost");
                    crash_case(P::AfterDataWrite, A::Crash, Damage::TruncateToOld, 0, e, d, "after data write, truncated");
                } else {
                    crash_case(P::AfterExternalWrite, A::Crash, Damage::None, 0, e, d, "after external write");
                }
            }
            crash_case(P::BeforeDataSync, A::Crash, Damage::None, 0, e, d, "before data sync");
            crash_case(P::BeforeDataSync, A::Crash, Damage::GarbageNewData, 0, e, d, "before data sync, data lost");
            crash_case(P::BeforeDataSync, A::Crash, Damage::TruncateToOld, 0, e, d, "before data sync, truncated");
            crash_case(P::AfterDataSync, A::Crash, Damage::None, 0, e, d, "after data sync");
            crash_case(P::HeaderWrite, A::Crash, Damage::None, 0, e, d, "header write skipped");
            crash_case(P::HeaderWrite, A::Torn, Damage::None, 0, e, d, "header write torn");
            crash_case(P::AfterHeaderWrite, A::Crash, Damage::None, 1, e, d, "header written unsynced");
            crash_case(P::AfterHeaderWrite, A::Crash, Damage::RevertHeader, 0, e, d, "header write lost");
            crash_case(P::AfterHeaderWrite, A::Crash, Damage::GarbageHeader, 0, e, d, "header write garbled");
            crash_case(P::AfterCommit, A::Crash, Damage::None, 1, e, d, "after commit");
        }
    }
}

void crashes_during_compaction_keep_every_record() {
    for (const FaultPoint point : {FaultPoint::CompactBeforeRename, FaultPoint::CompactAfterRename}) {
        TempDir dir("crashcompact");
        Tuning t;
        t.hook = fault_hook;
        t.compact_min_dead_sectors = 4;
        t.compact_dead_percent = 10;
        t.commit_every = 1u << 30;
        t.commit_bytes = ~0ull;
        {
            cv_store* s = open_store(dir.str().c_str(), t);
            for (int64_t i = 0; i < 64; ++i) CHECK(write_cube(s, i % 8, i / 8, 0, make_record(static_cast<uint64_t>(i), 9000, false)) == CV_OK);
            CHECK(cv_store_flush(s) == CV_OK);
            for (int64_t i = 0; i < 64; i += 2) CHECK(cv_store_write_cube(s, i % 8, i / 8, 0, nullptr, 0) == CV_OK);
            g_fault = Fault{true, point, FaultAction::Crash, 0};
            CHECK(cv_store_flush(s) == CV_ERR_IO);
            CHECK(g_fault.fired == 1);
            cv_store_close(s);
        }
        cv_store* s = cv_store_open(dir.str().c_str());
        for (int64_t i = 0; i < 64; ++i) {
            int64_t r = 0;
            const auto got = read_cube(s, i % 8, i / 8, 0, &r);
            if (i % 2 == 0) CHECK(r == 0);
            else CHECK(got == make_record(static_cast<uint64_t>(i), 9000, false));
        }
        cv_store_close(s);
    }
}

void corrupt_bytes_read_as_corrupt() {
    TempDir dir("corrupt");
    const fs::path file = dir.file("r.0.0.0.cvr");
    {
        cv_store* s = cv_store_open(dir.str().c_str());
        CHECK(write_cube(s, 0, 0, 0, make_record(1, 9000)) == CV_OK); // sectors 8.. (first allocation)
        CHECK(cv_store_flush(s) == CV_OK);
        CHECK(write_cube(s, 1, 0, 0, make_record(2, 9000)) == CV_OK);
        CHECK(cv_store_close(s) == CV_OK);
    }
    // 1. a flipped byte inside record 0 -> CORRUPT for it, record 1 fine
    auto bytes = slurp(file);
    bytes[size_t{kCubeHeaderSectors} * 4096 + 20] ^= 0x5A;
    spit(file, bytes);
    {
        cv_store* s = cv_store_open(dir.str().c_str());
        int64_t r = 0;
        read_cube(s, 0, 0, 0, &r);
        CHECK(r == CV_ERR_CORRUPT);
        CHECK(std::strstr(g_error, "checksum") != nullptr);
        CHECK(read_cube(s, 1, 0, 0) == make_record(2, 9000));
        // rewriting the damaged record heals it
        CHECK(write_cube(s, 0, 0, 0, make_record(3, 500)) == CV_OK);
        CHECK(read_cube(s, 0, 0, 0) == make_record(3, 500));
        CHECK(cv_store_close(s) == CV_OK);
    }
    // 2. the newest header slot garbled -> the older generation (record 1 absent there)
    bytes = slurp(file);
    {
        // find the newest slot: generation at offset 24 of each slot
        auto gen_of = [&](size_t off) {
            uint64_t g = 0;
            for (int i = 7; i >= 0; --i) g = g << 8 | bytes[off + 24 + static_cast<size_t>(i)];
            return g;
        };
        const size_t newest = gen_of(0) > gen_of(4 * 4096) ? 0 : 4 * 4096;
        bytes[newest + 64 + 5] ^= 0xFF; // inside the entry table: checksum fails
        spit(file, bytes);
        cv_store* s = cv_store_open(dir.str().c_str());
        int64_t r0 = 0, r1 = 0;
        read_cube(s, 0, 0, 0, &r0);
        read_cube(s, 1, 0, 0, &r1);
        // generation 2 is back: record 0 is the damaged original again, record 1 its first version
        CHECK(r0 == CV_ERR_CORRUPT);
        CHECK(r1 == 9000);
        CHECK(read_cube(s, 1, 0, 0) == make_record(2, 9000));
        cv_store_close(s);
    }
    // 3. both slots garbled -> every read CORRUPT; a write moves the file aside and starts fresh
    bytes = slurp(file);
    bytes[10] ^= 0xFF;
    bytes[4 * 4096 + 10] ^= 0xFF;
    spit(file, bytes);
    {
        cv_store* s = cv_store_open(dir.str().c_str());
        int64_t r = 0;
        read_cube(s, 1, 0, 0, &r);
        CHECK(r == CV_ERR_CORRUPT);
        CHECK(write_cube(s, 2, 0, 0, make_record(4, 100)) == CV_OK);
        CHECK(fs::exists(dir.file("r.0.0.0.cvr.corrupt")));
        CHECK(read_cube(s, 2, 0, 0) == make_record(4, 100));
        read_cube(s, 1, 0, 0, &r);
        CHECK(r == 0);
        CHECK(cv_store_close(s) == CV_OK);
    }
    // 4. truncated in the middle of a record -> CORRUPT for that record only
    {
        cv_store* s = cv_store_open(dir.str().c_str());
        CHECK(write_cube(s, 3, 0, 0, make_record(5, 20000, false)) == CV_OK); // last in the file
        CHECK(cv_store_close(s) == CV_OK);
        fs::resize_file(file, fs::file_size(file) - 3000);
        s = cv_store_open(dir.str().c_str());
        int64_t r = 0;
        read_cube(s, 3, 0, 0, &r);
        CHECK(r == CV_ERR_CORRUPT);
        CHECK(read_cube(s, 2, 0, 0) == make_record(4, 100));
        // the store keeps working: new writes never land on the damaged record's sectors
        CHECK(write_cube(s, 4, 0, 0, make_record(6, 5000)) == CV_OK);
        CHECK(cv_store_close(s) == CV_OK);
        s = cv_store_open(dir.str().c_str());
        CHECK(read_cube(s, 4, 0, 0) == make_record(6, 5000));
        read_cube(s, 3, 0, 0, &r);
        CHECK(r == CV_ERR_CORRUPT);
        cv_store_close(s);
    }
    // 5. random garbage everywhere never crashes; reads answer data, 0 or an error
    for (uint64_t seed = 1; seed <= 40; ++seed) {
        bytes = slurp(file);
        uint64_t rng = seed;
        for (int k = 0; k < 50; ++k) bytes[splitmix(rng) % bytes.size()] = static_cast<uint8_t>(splitmix(rng));
        const fs::path copy_dir = dir.path / ("fuzz" + std::to_string(seed));
        fs::create_directories(copy_dir);
        spit(copy_dir / "r.0.0.0.cvr", bytes);
        cv_store* s = cv_store_open(copy_dir.string().c_str());
        for (int64_t i = 0; i < 8; ++i) {
            int64_t r = 0;
            const auto got = read_cube(s, i, 0, 0, &r);
            CHECK(r >= 0 || r == CV_ERR_CORRUPT);
            if (i == 4 && r > 0) CHECK(got == make_record(6, 5000)); // never garbage
        }
        cv_store_close(s);
    }
}

void incompatible_files_are_refused_not_destroyed() {
    TempDir dir("incompatible");
    const fs::path file = dir.file("r.0.0.0.cvr");
    {
        cv_store* s = cv_store_open(dir.str().c_str());
        CHECK(write_cube(s, 0, 0, 0, make_record(1, 100)) == CV_OK);
        cv_store_close(s);
    }
    auto bytes = slurp(file);
    for (size_t slot : {size_t{0}, size_t{4 * 4096}}) bytes[slot + 4] = 9; // version 9
    spit(file, bytes);
    cv_store* s = cv_store_open(dir.str().c_str());
    int64_t r = 0;
    read_cube(s, 0, 0, 0, &r);
    CHECK(r == CV_ERR_CORRUPT);
    CHECK(std::strstr(g_error, "version 9") != nullptr);
    CHECK(write_cube(s, 1, 0, 0, make_record(2, 100)) == CV_ERR_CORRUPT);
    CHECK(cv_store_write_cube(s, 0, 0, 0, nullptr, 0) == CV_ERR_CORRUPT);
    cv_store_close(s);
    CHECK(slurp(file) == bytes);
    CHECK(!fs::exists(dir.file("r.0.0.0.cvr.corrupt")));
    // a region file renamed to another position is refused as well
    fs::rename(file, dir.file("r.5.0.0.cvr"));
    {
        cv_store* s2 = cv_store_open(dir.str().c_str());
        CHECK(write_cube(s2, 7, 0, 0, make_record(3, 100)) == CV_OK); // fresh r.0.0.0
        cv_store_close(s2);
    }
    bytes = slurp(dir.file("r.0.0.0.cvr"));
    spit(dir.file("r.5.0.0.cvr"), bytes);
    s = cv_store_open(dir.str().c_str());
    read_cube(s, 40, 0, 0, &r);
    CHECK(r == CV_ERR_CORRUPT);
    CHECK(std::strstr(g_error, "coordinates") != nullptr);
    cv_store_close(s);
}

void compaction_keeps_every_record() {
    TempDir dir("compact");
    Tuning t;
    t.compact_min_dead_sectors = 16;
    t.compact_dead_percent = 25;
    t.commit_every = 1u << 30;
    t.commit_bytes = ~0ull;
    const fs::path file = dir.file("r.0.0.0.cvr");
    cv_store* s = open_store(dir.str().c_str(), t);
    std::map<int64_t, std::vector<uint8_t>> expect;
    for (int64_t i = 0; i < 200; ++i) {
        expect[i] = make_record(static_cast<uint64_t>(i), 12000, false); // 3 sectors each
        CHECK(write_cube(s, i % 8, i / 8 % 8, i / 64, expect[i]) == CV_OK);
    }
    CHECK(cv_store_flush(s) == CV_OK);
    const uint64_t full = fs::file_size(file);
    const uint64_t gen_before = region_generation(s, RegionKind::Cube, 0, 0, 0);
    // churn: shrink every other record, delete every third, rewrite some twice before a commit
    for (int64_t i = 0; i < 200; ++i) {
        if (i % 3 == 0) {
            CHECK(cv_store_write_cube(s, i % 8, i / 8 % 8, i / 64, nullptr, 0) == CV_OK);
            expect.erase(i);
        } else if (i % 2 == 0) {
            expect[i] = make_record(static_cast<uint64_t>(i) + 5000, 1000, false);
            CHECK(write_cube(s, i % 8, i / 8 % 8, i / 64, make_record(1, 3000)) == CV_OK);
            CHECK(write_cube(s, i % 8, i / 8 % 8, i / 64, expect[i]) == CV_OK);
        }
    }
    CHECK(cv_store_flush(s) == CV_OK);
    const uint64_t compacted = fs::file_size(file);
    CHECK(compacted < full * 6 / 10);
    CHECK(region_generation(s, RegionKind::Cube, 0, 0, 0) >= gen_before + 2); // commit + compaction
    CHECK(!fs::exists(dir.file("r.0.0.0.cvr.compact")));
    for (int64_t i = 0; i < 200; ++i) {
        int64_t r = 0;
        const auto got = read_cube(s, i % 8, i / 8 % 8, i / 64, &r);
        if (expect.count(i) != 0) CHECK(got == expect[i]);
        else CHECK(r == 0);
    }
    // the compacted file is dense: free space ends up appended to, not fragmented
    CHECK(write_cube(s, 0, 0, 0, make_record(9, 4000, false)) == CV_OK);
    expect[0] = make_record(9, 4000, false);
    CHECK(cv_store_close(s) == CV_OK);
    s = cv_store_open(dir.str().c_str());
    for (int64_t i = 0; i < 200; ++i) {
        int64_t r = 0;
        const auto got = read_cube(s, i % 8, i / 8 % 8, i / 64, &r);
        if (expect.count(i) != 0) CHECK(got == expect[i]);
        else CHECK(r == 0);
    }
    // deleting everything shrinks the file to its header
    for (int64_t i = 0; i < 200; ++i) CHECK(cv_store_write_cube(s, i % 8, i / 8 % 8, i / 64, nullptr, 0) == CV_OK);
    CHECK(cv_store_close(s) == CV_OK);
    CHECK(fs::file_size(file) == uint64_t{kCubeHeaderSectors} * 4096);
}

void external_records() {
    TempDir dir("external");
    Tuning t;
    t.external_threshold = 16 * 1024;
    const auto big1 = make_record(1, 100000, false);
    const auto big2 = make_record(2, 150000, false);
    {
        cv_store* s = open_store(dir.str().c_str(), t);
        CHECK(write_cube(s, 2, 0, 0, big1) == CV_OK);
        CHECK(write_cube(s, 3, 0, 0, make_record(3, 5000)) == CV_OK); // in-file neighbour
        CHECK(fs::exists(dir.file("r.0.0.0.cvr.2.a.cvx")));
        CHECK(read_cube(s, 2, 0, 0) == big1);
        CHECK(cv_store_flush(s) == CV_OK);
        CHECK(write_cube(s, 2, 0, 0, big2) == CV_OK); // committed copy in slot a -> slot b
        CHECK(fs::exists(dir.file("r.0.0.0.cvr.2.b.cvx")));
        CHECK(fs::exists(dir.file("r.0.0.0.cvr.2.a.cvx")));
        CHECK(read_cube(s, 2, 0, 0) == big2);
        CHECK(cv_store_flush(s) == CV_OK);
        CHECK(!fs::exists(dir.file("r.0.0.0.cvr.2.a.cvx"))); // old committed copy removed after commit
        CHECK(cv_store_close(s) == CV_OK);
    }
    {
        cv_store* s = open_store(dir.str().c_str(), t);
        CHECK(read_cube(s, 2, 0, 0) == big2);
        CHECK(read_cube(s, 3, 0, 0) == make_record(3, 5000));
        // external -> small in-file record, then delete
        CHECK(write_cube(s, 2, 0, 0, make_record(4, 100)) == CV_OK);
        CHECK(cv_store_flush(s) == CV_OK);
        CHECK(count_files(dir.path, ".cvx") == 0);
        CHECK(write_cube(s, 5, 0, 0, big1) == CV_OK);
        CHECK(write_cube(s, 5, 0, 0, big2) == CV_OK); // twice before a commit: same uncommitted slot
        CHECK(count_files(dir.path, ".cvx") == 1);
        CHECK(cv_store_write_cube(s, 5, 0, 0, nullptr, 0) == CV_OK);
        CHECK(cv_store_close(s) == CV_OK);
        CHECK(count_files(dir.path, ".cvx") == 0);
    }
    {
        // a missing external file reads as CORRUPT
        cv_store* s = open_store(dir.str().c_str(), t);
        CHECK(write_cube(s, 6, 0, 0, big1) == CV_OK);
        CHECK(cv_store_close(s) == CV_OK);
        fs::remove(dir.file("r.0.0.0.cvr.6.a.cvx"));
        s = open_store(dir.str().c_str(), t);
        int64_t r = 0;
        read_cube(s, 6, 0, 0, &r);
        CHECK(r == CV_ERR_CORRUPT);
        cv_store_close(s);
    }
    {
        // stray external files (a crash after an external write, before its commit) are swept by compaction
        Tuning c = t;
        c.compact_min_dead_sectors = 4;
        c.compact_dead_percent = 10;
        spit(dir.file("r.0.0.0.cvr.9.b.cvx"), make_record(1, 100));
        spit(dir.file("r.0.0.0.cvr.10.a.cvx"), make_record(1, 100));
        cv_store* s = open_store(dir.str().c_str(), c);
        CHECK(write_cube(s, 5, 0, 0, big1) == CV_OK); // a live external record survives the sweep
        for (int64_t i = 16; i < 48; ++i) CHECK(write_cube(s, i % 8, i / 8, 0, make_record(static_cast<uint64_t>(i), 12000, false)) == CV_OK);
        CHECK(write_cube(s, 0, 7, 0, make_record(99, 9000, false)) == CV_OK); // after them: no tail truncation
        CHECK(cv_store_flush(s) == CV_OK);
        for (int64_t i = 16; i < 48; ++i) CHECK(cv_store_write_cube(s, i % 8, i / 8, 0, nullptr, 0) == CV_OK);
        const uint64_t gen = region_generation(s, RegionKind::Cube, 0, 0, 0);
        CHECK(cv_store_flush(s) == CV_OK);
        CHECK(region_generation(s, RegionKind::Cube, 0, 0, 0) >= gen + 2); // compacted
        CHECK(!fs::exists(dir.file("r.0.0.0.cvr.9.b.cvx")));
        CHECK(!fs::exists(dir.file("r.0.0.0.cvr.10.a.cvx")));
        CHECK(read_cube(s, 5, 0, 0) == big1);
        CHECK(cv_store_close(s) == CV_OK);
    }
    {
        // default threshold (1 MiB stored): a 3 MiB incompressible record goes external
        TempDir dir2("external_default");
        cv_store* s = cv_store_open(dir2.str().c_str());
        const auto huge = make_record(7, 3u << 20, false);
        CHECK(write_cube(s, -1, -1, -1, huge) == CV_OK);
        CHECK(count_files(dir2.path, ".cvx") == 1);
        CHECK(cv_store_close(s) == CV_OK);
        s = cv_store_open(dir2.str().c_str());
        CHECK(read_cube(s, -1, -1, -1) == huge);
        cv_store_close(s);
    }
}

void a_crash_during_creation_reads_as_absent() {
    TempDir dir("creation");
    // an empty and an all-zero file: what a crash right after creating the file leaves
    spit(dir.file("r.0.0.0.cvr"), {});
    spit(dir.file("r.1.0.0.cvr"), std::vector<uint8_t>(10000, 0));
    cv_store* s = cv_store_open(dir.str().c_str());
    std::vector<uint8_t> buf(10);
    CHECK(cv_store_read_cube(s, 0, 0, 0, buf.data(), 10) == 0);
    CHECK(cv_store_read_cube(s, 8, 0, 0, buf.data(), 10) == 0);
    CHECK(write_cube(s, 8, 0, 0, make_record(1, 300)) == CV_OK);
    CHECK(cv_store_close(s) == CV_OK);
    s = cv_store_open(dir.str().c_str());
    CHECK(read_cube(s, 8, 0, 0) == make_record(1, 300));
    cv_store_close(s);
}

void small_records_share_pack_sectors() {
    TempDir dir("pack");
    const fs::path file = dir.file("r.0.0.0.cvr");
    cv_store* s = cv_store_open(dir.str().c_str());
    // 512 tiny records (what a uniform cube encodes to), each rewritten once before the first commit
    for (int64_t i = 0; i < 512; ++i) CHECK(write_cube(s, i % 8, i / 64, i / 8 % 8, make_record(static_cast<uint64_t>(i), 40, false)) == CV_OK);
    for (int64_t i = 0; i < 512; i += 3) CHECK(write_cube(s, i % 8, i / 64, i / 8 % 8, make_record(static_cast<uint64_t>(i) + 9000, 24, false)) == CV_OK);
    for (int64_t i = 1; i < 512; i += 7) CHECK(cv_store_write_cube(s, i % 8, i / 64, i / 8 % 8, nullptr, 0) == CV_OK);
    // visible before any commit (served from the in-memory pack)
    CHECK(read_cube(s, 0, 0, 0) == make_record(9000, 24, false));
    CHECK(cv_store_close(s) == CV_OK);
    // ~512 x 40 B = 20 KiB of records: a handful of pack sectors, not 512 of them
    CHECK(fs::file_size(file) <= (kCubeHeaderSectors + 8) * 4096u);
    s = cv_store_open(dir.str().c_str());
    for (int64_t i = 0; i < 512; ++i) {
        int64_t r = 0;
        const auto got = read_cube(s, i % 8, i / 64, i / 8 % 8, &r);
        if (i % 7 == 1) CHECK(r == 0);
        else if (i % 3 == 0) CHECK(got == make_record(static_cast<uint64_t>(i) + 9000, 24, false));
        else CHECK(got == make_record(static_cast<uint64_t>(i), 40, false));
    }
    // mixing pack-sized and whole-sector records in one region
    for (int64_t i = 0; i < 64; ++i)
        CHECK(write_cube(s, i % 8, i / 64, i / 8 % 8, make_record(static_cast<uint64_t>(i) + 50000, i % 2 == 0 ? 9000 : 300, false)) == CV_OK);
    CHECK(cv_store_close(s) == CV_OK);
    s = cv_store_open(dir.str().c_str());
    for (int64_t i = 0; i < 64; ++i)
        CHECK(read_cube(s, i % 8, i / 64, i / 8 % 8) == make_record(static_cast<uint64_t>(i) + 50000, i % 2 == 0 ? 9000 : 300, false));
    cv_store_close(s);
}

void benchmark() {
    TempDir dir("bench");
    constexpr int kRecords = 10000;
    std::vector<std::vector<uint8_t>> recs;
    recs.reserve(kRecords);
    uint64_t raw = 0;
    for (int i = 0; i < kRecords; ++i) {
        recs.push_back(make_record(static_cast<uint64_t>(i) + 1, static_cast<size_t>(2048 + (static_cast<uint32_t>(i) * 2654435761u) % 6144)));
        raw += recs.back().size();
    }
    // a 24 x 24 column patch, 18 cubes tall (~ what a world save covers), as cube coordinates
    auto coord = [](int i) { return std::tuple<int64_t, int64_t, int64_t>{i % 24 - 12, i / 576 - 6, i / 24 % 24 - 12}; };
    using clock = std::chrono::steady_clock;
    auto secs = [](clock::time_point a, clock::time_point b) { return std::chrono::duration<double>(b - a).count(); };
    cv_store* s = cv_store_open(dir.str().c_str());
    const auto t0 = clock::now();
    for (int i = 0; i < kRecords; ++i) {
        const auto [x, y, z] = coord(i);
        CHECK(write_cube(s, x, y, z, recs[static_cast<size_t>(i)]) == CV_OK);
    }
    const auto t1 = clock::now();
    CHECK(cv_store_flush(s) == CV_OK);
    const auto t2 = clock::now();
    std::vector<uint8_t> buf(8192);
    int ok = 0;
    for (int i = 0; i < kRecords; ++i) {
        const auto [x, y, z] = coord(i);
        const int64_t r = cv_store_read_cube(s, x, y, z, buf.data(), 8192);
        ok += r == static_cast<int64_t>(recs[static_cast<size_t>(i)].size()) &&
                      std::memcmp(buf.data(), recs[static_cast<size_t>(i)].data(), static_cast<size_t>(r)) == 0
                  ? 1
                  : 0;
    }
    const auto t3 = clock::now();
    CHECK(ok == kRecords);
    CHECK(cv_store_close(s) == CV_OK);
    const auto t4 = clock::now();
    s = cv_store_open(dir.str().c_str());
    for (int i = 0; i < kRecords; ++i) {
        const auto [x, y, z] = coord(i);
        CHECK(cv_store_read_cube(s, x, y, z, buf.data(), 8192) > 0);
    }
    const auto t5 = clock::now();
    cv_store_close(s);
    // 8 threads writing disjoint records
    TempDir dir2("bench_mt");
    s = cv_store_open(dir2.str().c_str());
    const auto m0 = clock::now();
    std::vector<std::thread> threads;
    for (int th = 0; th < 8; ++th)
        threads.emplace_back([&, th] {
            for (int i = th; i < kRecords; i += 8) {
                const auto [x, y, z] = coord(i);
                write_cube(s, x, y, z, recs[static_cast<size_t>(i)]);
            }
        });
    for (auto& th : threads) th.join();
    CHECK(cv_store_flush(s) == CV_OK);
    const auto m1 = clock::now();
    threads.clear();
    std::atomic<int> mt_ok{0};
    for (int th = 0; th < 8; ++th)
        threads.emplace_back([&, th] {
            std::vector<uint8_t> b(8192);
            for (int i = th; i < kRecords; i += 8) {
                const auto [x, y, z] = coord(i);
                if (cv_store_read_cube(s, x, y, z, b.data(), 8192) == static_cast<int64_t>(recs[static_cast<size_t>(i)].size()))
                    mt_ok.fetch_add(1);
            }
        });
    for (auto& th : threads) th.join();
    const auto m2 = clock::now();
    CHECK(mt_ok.load() == kRecords);
    cv_store_close(s);
    uint64_t disk = 0;
    for (const auto& e : fs::directory_iterator(dir.path)) disk += e.is_regular_file() ? e.file_size() : 0;
    const double mb = static_cast<double>(raw) / (1024.0 * 1024.0);
    std::printf("[cv_store bench] %d records, %.1f MiB raw, %.1f MiB on disk (%zu region files)\n", kRecords, mb,
                static_cast<double>(disk) / (1024.0 * 1024.0), count_files(dir.path, ".cvr"));
    std::printf("[cv_store bench] write  1 thread : %.0f rec/s, %.1f MiB/s (+ flush %.1f ms)\n",
                kRecords / secs(t0, t1), mb / secs(t0, t1), secs(t1, t2) * 1000.0);
    std::printf("[cv_store bench] read   1 thread : %.0f rec/s, %.1f MiB/s (resident handles)\n",
                kRecords / secs(t2, t3), mb / secs(t2, t3));
    std::printf("[cv_store bench] close %.1f ms; reopen + read: %.0f rec/s\n", secs(t3, t4) * 1000.0,
                kRecords / secs(t4, t5));
    std::printf("[cv_store bench] write  8 threads: %.0f rec/s incl. flush; read 8 threads: %.0f rec/s\n",
                kRecords / secs(m0, m1), kRecords / secs(m1, m2));
}

} // namespace

int main() {
    crc32c_matches_the_reference();
    cubes_and_columns_round_trip();
    buffer_protocol_and_bad_arguments();
    the_directory_is_locked_while_open();
    overwrite_delete_and_reopen();
    many_records_across_many_files();
    handle_lru_evicts_and_commits();
    concurrent_readers_and_writers();
    crashes_leave_the_old_or_the_new_record();
    crashes_during_compaction_keep_every_record();
    corrupt_bytes_read_as_corrupt();
    incompatible_files_are_refused_not_destroyed();
    compaction_keeps_every_record();
    external_records();
    a_crash_during_creation_reads_as_absent();
    small_records_share_pack_sectors();
    benchmark();
    if (failures != 0) {
        std::fprintf(stderr, "voxel_store: %d check(s) failed\n", failures);
        return 1;
    }
    std::printf("voxel_store: all checks passed\n");
    return 0;
}
