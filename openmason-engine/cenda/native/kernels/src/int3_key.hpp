// Full int64 coordinate triples as hash keys (OpenMason #142): no packed coordinate keys anywhere —
// a packed key aliases two far-apart cells silently once a coordinate leaves its bit field. Engine
// internal (physics bricks, navigation nodes and floods, entity grid cells).
#pragma once

#include <cstddef>
#include <cstdint>

namespace cenda {

// splitmix64's finaliser: a strong 64-bit mixer (every input bit affects every output bit).
inline uint64_t mix64(uint64_t h) {
    h ^= h >> 30;
    h *= 0xbf58476d1ce4e5b9ull;
    h ^= h >> 27;
    h *= 0x94d049bb133111ebull;
    h ^= h >> 31;
    return h;
}

struct Int3 {
    int64_t x = 0, y = 0, z = 0;

    friend bool operator==(const Int3& a, const Int3& b) { return a.x == b.x && a.y == b.y && a.z == b.z; }
    friend bool operator!=(const Int3& a, const Int3& b) { return !(a == b); }
};

// Hash of a triple: a chained polynomial ((x * K1 + y) * K2 + z) - injective for every neighbourhood a
// table ever holds (an XOR of per-axis products collided: 4096 keys of a 16^3 block around a negative
// corner gave only 2518 hashes) - then one splitmix64 finalisation. Tables always compare the full
// triple, so a collision costs a probe, never a wrong answer. Java twin: CubePos.hash.
inline uint64_t hash3(int64_t x, int64_t y, int64_t z) {
    uint64_t h = static_cast<uint64_t>(x) * 0x9e3779b97f4a7c15ull + static_cast<uint64_t>(y);
    h = h * 0xc2b2ae3d27d4eb4full + static_cast<uint64_t>(z);
    return mix64(h);
}

struct Int3Hash {
    size_t operator()(const Int3& k) const { return static_cast<size_t>(hash3(k.x, k.y, k.z)); }
};

// Floor division for int64 (b > 0).
inline int64_t floor_div64(int64_t a, int64_t b) {
    int64_t q = a / b;
    if ((a % b != 0) && ((a < 0) != (b < 0))) --q;
    return q;
}

} // namespace cenda
