// Paletted section storage (cv_section.hpp).
#include "cv_section.hpp"

#include <cstdlib>
#include <cstring>
#include <new>

namespace cenda::voxel {

namespace {

// value -> palette slot, 0xFFFF = unseen. One per thread; every user restores the entries it touched.
struct SlotTable {
    uint16_t slot[65536];
    SlotTable() { std::memset(slot, 0xFF, sizeof slot); }
};

SlotTable& slots() {
    thread_local SlotTable table;
    return table;
}

uint8_t* alloc_bytes(size_t n) {
    void* p = std::malloc(n);
    if (p == nullptr) {
        throw std::bad_alloc();
    }
    return static_cast<uint8_t*>(p);
}

size_t bytes_for(uint8_t bits) {
    switch (bits) {
        case 4: return 16 * 2 * 2 + kSectionCells / 2;   // 2112
        case 8: return 256 * 2 * 2 + kSectionCells;      // 5120
        case 16: return kSectionCells * 2;               // 8192
        default: return 0;
    }
}

} // namespace

uint32_t distinct_values(const uint16_t* values, uint16_t* palette256, uint8_t* index4096) {
    uint16_t* slot = slots().slot;
    uint32_t n = 0;
    bool over = false;
    uint16_t last_v = 0;
    uint16_t last_s = 0xFFFF;
    for (uint32_t i = 0; i < kSectionCells; ++i) {
        const uint16_t v = values[i];
        if (v == last_v && last_s != 0xFFFF) {   // runs are the common case
            if (index4096 != nullptr) index4096[i] = static_cast<uint8_t>(last_s);
            continue;
        }
        uint16_t s = slot[v];
        if (s == 0xFFFF) {
            if (n == 256) {
                over = true;
                break;
            }
            s = static_cast<uint16_t>(n);
            slot[v] = s;
            palette256[n++] = v;
        }
        if (index4096 != nullptr) {
            index4096[i] = static_cast<uint8_t>(s);
        }
        last_v = v;
        last_s = s;
    }
    for (uint32_t k = 0; k < n; ++k) {
        slot[palette256[k]] = 0xFFFF;
    }
    return over ? 257u : n;
}

void Section::release() {
    std::free(data_);
    data_ = nullptr;
    bits_ = 0;
    live_ = 0;
}

size_t Section::heap_bytes() const {
    return bytes_for(bits_);
}

void Section::write_index(uint32_t i, uint32_t slot) {
    if (bits_ == 4) {
        uint8_t& b = idx8()[i >> 1];
        const uint32_t shift = (i & 1u) << 2;
        b = static_cast<uint8_t>((b & ~(15u << shift)) | (slot << shift));
    } else {
        idx8()[i] = static_cast<uint8_t>(slot);
    }
}

void Section::build(const uint16_t* values, const uint16_t* pal, uint32_t n, const uint8_t* index) {
    release();
    if (n == 1) {
        uniform_ = pal[0];
        return;
    }
    if (n > 256) {
        data_ = alloc_bytes(bytes_for(16));
        bits_ = 16;
        std::memcpy(data_, values, kSectionCells * 2);
        return;
    }
    const uint8_t bits = n <= 16 ? 4 : 8;
    data_ = alloc_bytes(bytes_for(bits));
    bits_ = bits;
    const uint32_t c = cap();
    std::memset(data_, 0, 4u * c);
    std::memcpy(palette(), pal, n * 2u);
    uint16_t* cnt = counts();
    if (bits == 4) {
        uint8_t* idx = idx8();
        for (uint32_t i = 0; i < kSectionCells; i += 2) {
            idx[i >> 1] = static_cast<uint8_t>(index[i] | (index[i + 1] << 4));
        }
    } else {
        std::memcpy(idx8(), index, kSectionCells);
    }
    // Run-length counting: back-to-back increments of one counter would serialise on the store.
    uint32_t run_slot = index[0], run_len = 0;
    for (uint32_t i = 0; i < kSectionCells; ++i) {
        if (index[i] == run_slot) {
            ++run_len;
        } else {
            cnt[run_slot] = static_cast<uint16_t>(cnt[run_slot] + run_len);
            run_slot = index[i];
            run_len = 1;
        }
    }
    cnt[run_slot] = static_cast<uint16_t>(cnt[run_slot] + run_len);
    uint16_t live = 0;
    uint32_t last = 0;
    for (uint32_t k = 0; k < n; ++k) {
        if (cnt[k] != 0) {
            ++live;
            last = k;
        }
    }
    live_ = live;
    if (live_ == 1) fill(pal[last]);   // an adopted palette may carry unused entries
}

void Section::assign_indexed(const uint16_t* values, const uint16_t* pal, uint32_t n, const uint8_t* index) {
    if (n == 0 || n > 256) {
        assign(values);
        return;
    }
    // Duplicate palette values would break the one-slot-per-value invariant: rebuild those.
    uint16_t* slot = slots().slot;
    bool dup = false;
    uint32_t k = 0;
    for (; k < n; ++k) {
        if (slot[pal[k]] != 0xFFFF) {
            dup = true;
            break;
        }
        slot[pal[k]] = 0;
    }
    for (uint32_t j = 0; j < k; ++j) slot[pal[j]] = 0xFFFF;
    if (dup) {
        assign(values);
        return;
    }
    build(values, pal, n, index);
}

void Section::assign(const uint16_t* values) {
    uint16_t pal[256];
    uint8_t index[kSectionCells];
    const uint32_t n = distinct_values(values, pal, index);
    build(values, pal, n, index);
}

void Section::read(uint16_t* out) const {
    switch (bits_) {
        case 0:
            for (uint32_t i = 0; i < kSectionCells; ++i) out[i] = uniform_;
            return;
        case 4: {
            const uint16_t* pal = palette();
            const uint8_t* idx = idx8();
            for (uint32_t i = 0; i < kSectionCells; i += 2) {
                const uint8_t b = idx[i >> 1];
                out[i] = pal[b & 15u];
                out[i + 1] = pal[b >> 4];
            }
            return;
        }
        case 8: {
            const uint16_t* pal = palette();
            const uint8_t* idx = idx8();
            for (uint32_t i = 0; i < kSectionCells; ++i) out[i] = pal[idx[i]];
            return;
        }
        default:
            std::memcpy(out, direct(), kSectionCells * 2);
            return;
    }
}

void Section::read_row(uint32_t row, uint16_t* out) const {
    const uint32_t base = row << 4;
    switch (bits_) {
        case 0:
            for (uint32_t i = 0; i < 16; ++i) out[i] = uniform_;
            return;
        case 4: {
            const uint16_t* pal = palette();
            const uint8_t* idx = idx8() + (base >> 1);
            for (uint32_t i = 0; i < 8; ++i) {
                const uint8_t b = idx[i];
                out[2 * i] = pal[b & 15u];
                out[2 * i + 1] = pal[b >> 4];
            }
            return;
        }
        case 8: {
            const uint16_t* pal = palette();
            const uint8_t* idx = idx8() + base;
            for (uint32_t i = 0; i < 16; ++i) out[i] = pal[idx[i]];
            return;
        }
        default:
            std::memcpy(out, direct() + base, 32);
            return;
    }
}

void Section::upgrade() {
    if (bits_ == 4) {
        uint8_t* next = alloc_bytes(bytes_for(8));
        std::memset(next, 0, 256 * 4);
        auto* npal = reinterpret_cast<uint16_t*>(next);
        uint16_t* ncnt = npal + 256;
        std::memcpy(npal, palette(), 16 * 2);
        std::memcpy(ncnt, counts(), 16 * 2);
        uint8_t* nidx = next + 256 * 4;
        const uint8_t* idx = idx8();
        for (uint32_t i = 0; i < kSectionCells; i += 2) {
            nidx[i] = static_cast<uint8_t>(idx[i >> 1] & 15u);
            nidx[i + 1] = static_cast<uint8_t>(idx[i >> 1] >> 4);
        }
        std::free(data_);
        data_ = next;
        bits_ = 8;
        return;
    }
    // 8 -> 16
    uint8_t* next = alloc_bytes(bytes_for(16));
    auto* out = reinterpret_cast<uint16_t*>(next);
    const uint16_t* pal = palette();
    const uint8_t* idx = idx8();
    for (uint32_t i = 0; i < kSectionCells; ++i) out[i] = pal[idx[i]];
    std::free(data_);
    data_ = next;
    bits_ = 16;
    live_ = 0;
}

uint16_t Section::set(uint32_t i, uint16_t v) {
    if (bits_ == 0) {
        if (v == uniform_) {
            return v;
        }
        data_ = alloc_bytes(bytes_for(4));
        bits_ = 4;
        std::memset(data_, 0, bytes_for(4));
        palette()[0] = uniform_;
        counts()[0] = kSectionCells;
        live_ = 1;
    }
    if (bits_ == 16) {
        const uint16_t old = direct()[i];
        direct()[i] = v;
        return old;
    }
    const uint32_t oi = index_at(i);
    uint16_t* pal = palette();
    uint16_t* cnt = counts();
    const uint16_t old = pal[oi];
    if (old == v) {
        return old;
    }
    const uint32_t c = cap();
    uint32_t slot = c;
    uint32_t free_slot = c;
    for (uint32_t s = 0; s < c; ++s) {
        if (cnt[s] != 0) {
            if (pal[s] == v) {
                slot = s;
                break;
            }
        } else if (free_slot == c) {
            free_slot = s;
        }
    }
    if (slot == c) {
        if (free_slot == c) {
            upgrade();
            return set(i, v);
        }
        slot = free_slot;
        pal[slot] = v;
        ++live_;
    }
    write_index(i, slot);
    ++cnt[slot];
    if (--cnt[oi] == 0) {
        --live_;
    }
    if (live_ == 1) {
        fill(v);   // only v's slot is left
    }
    return old;
}

bool Section::uniform(uint16_t* v) const {
    if (bits_ == 0) {
        *v = uniform_;
        return true;
    }
    if (bits_ == 16) {
        const uint16_t* d = direct();
        for (uint32_t i = 1; i < kSectionCells; ++i) {
            if (d[i] != d[0]) return false;
        }
        *v = d[0];
        return true;
    }
    // Paletted tiers collapse to uniform as soon as one slot is live.
    return false;
}

uint32_t Section::count_not(uint16_t zero) const {
    switch (bits_) {
        case 0: return uniform_ == zero ? 0u : static_cast<uint32_t>(kSectionCells);
        case 16: {
            uint32_t n = 0;
            const uint16_t* d = direct();
            for (uint32_t i = 0; i < kSectionCells; ++i) n += d[i] != zero ? 1u : 0u;
            return n;
        }
        default: {
            uint32_t n = 0;
            const uint32_t c = cap();
            const uint16_t* pal = palette();
            const uint16_t* cnt = counts();
            for (uint32_t s = 0; s < c; ++s) {
                if (cnt[s] != 0 && pal[s] != zero) n += cnt[s];
            }
            return n;
        }
    }
}

void Section::compact() {
    if (!compactable()) {
        return;
    }
    uint16_t values[kSectionCells];
    read(values);
    assign(values);
}

} // namespace cenda::voxel
