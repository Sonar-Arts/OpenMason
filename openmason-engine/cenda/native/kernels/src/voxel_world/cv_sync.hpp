// 4-byte reader/writer spin locks of the voxel world (one per cube, column and map shard: a pthread
// rwlock is 56 bytes, which an all-air cube cannot afford). Readers are one CAS; contention backs off
// to yield. Not recursive.
#pragma once

#include <atomic>
#include <cstdint>
#include <thread>

#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)
#include <immintrin.h>
#define CV_CPU_RELAX() _mm_pause()
#elif defined(__aarch64__) && (defined(__GNUC__) || defined(__clang__))
#define CV_CPU_RELAX() __asm__ __volatile__("yield")
#else
#define CV_CPU_RELAX() ((void)0)
#endif

namespace cenda::voxel {

inline void backoff(uint32_t& spins) noexcept {
    if (spins < 64) {
        ++spins;
        CV_CPU_RELAX();
    } else {
        std::this_thread::yield();
    }
}

// Writer-preferring: a waiting writer stops new readers (cube and column content, so an edit is never
// starved by a stream of mesh reads).
class RwLock {
public:
    void lock_shared() noexcept {
        uint32_t spins = 0;
        for (;;) {
            uint32_t v = s_.load(std::memory_order_relaxed);
            if ((v & (kWriter | kWaiting)) == 0 &&
                s_.compare_exchange_weak(v, v + 1, std::memory_order_acquire, std::memory_order_relaxed)) {
                return;
            }
            backoff(spins);
        }
    }
    void unlock_shared() noexcept { s_.fetch_sub(1, std::memory_order_release); }

    void lock() noexcept {
        uint32_t spins = 0;
        for (;;) {
            uint32_t v = s_.load(std::memory_order_relaxed);
            if ((v & ~kWaiting) == 0) {
                if (s_.compare_exchange_weak(v, kWriter, std::memory_order_acquire, std::memory_order_relaxed)) {
                    return;
                }
                continue;
            }
            if ((v & kWaiting) == 0) {
                s_.fetch_or(kWaiting, std::memory_order_relaxed);
            }
            backoff(spins);
        }
    }
    void unlock() noexcept { s_.fetch_and(~kWriter, std::memory_order_release); }

private:
    static constexpr uint32_t kWriter = 0x80000000u;
    static constexpr uint32_t kWaiting = 0x40000000u;
    std::atomic<uint32_t> s_{0};
};

// Reader-preferring: map shards. Exclusive holders (insert/erase) hold nothing else and are brief;
// shared holders may wait on a cube lock while holding the shard, so a waiting writer must not block
// new readers (that would chain shard waits through cube locks).
class ShardLock {
public:
    void lock_shared() noexcept {
        uint32_t spins = 0;
        for (;;) {
            uint32_t v = s_.load(std::memory_order_relaxed);
            if ((v & kWriter) == 0 &&
                s_.compare_exchange_weak(v, v + 1, std::memory_order_acquire, std::memory_order_relaxed)) {
                return;
            }
            backoff(spins);
        }
    }
    void unlock_shared() noexcept { s_.fetch_sub(1, std::memory_order_release); }

    void lock() noexcept {
        uint32_t spins = 0;
        for (;;) {
            uint32_t v = 0;
            if (s_.compare_exchange_weak(v, kWriter, std::memory_order_acquire, std::memory_order_relaxed)) {
                return;
            }
            backoff(spins);
        }
    }
    void unlock() noexcept { s_.store(0, std::memory_order_release); }

private:
    static constexpr uint32_t kWriter = 0x80000000u;
    std::atomic<uint32_t> s_{0};
};

} // namespace cenda::voxel
