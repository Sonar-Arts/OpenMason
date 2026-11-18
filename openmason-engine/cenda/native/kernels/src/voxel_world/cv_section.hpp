// One 16^3 section of uint16 values, paletted: uniform (no array) / 4-bit (<= 16 values) / 8-bit
// (<= 256) / 16-bit direct. The one storage type of the voxel world: block ids and every host layer.
//
// Paletted tiers keep a use count per palette slot, so a slot whose last cell is overwritten is reused
// (no palette growth under churn) and a section that collapses to one value drops its array at once.
// Over-wide tiers (8-bit with <= 16 live values, 16-bit with few distinct values) are narrowed lazily by
// compact() (the cube encoder calls it). Not thread-safe; the owning cube's lock guards it.
//
// Section-local index: (sly * 16 + slz) * 16 + slx. Row r = sly * 16 + slz holds 16 consecutive cells.
#pragma once

#include <cstddef>
#include <cstdint>

namespace cenda::voxel {

constexpr int kSectionCells = 4096;

// Distinct values of 4096 cells in first-occurrence order (deterministic, independent of any palette
// state). Writes up to 256 values to palette256 and, when index4096 is non-null, each cell's palette
// index. Returns the distinct count, or 257 when there are more than 256 (palette/index then partial).
uint32_t distinct_values(const uint16_t* values, uint16_t* palette256, uint8_t* index4096);

class Section {
public:
    Section() = default;
    explicit Section(uint16_t v) : uniform_(v) {}
    ~Section() { release(); }
    Section(const Section&) = delete;
    Section& operator=(const Section&) = delete;
    Section(Section&& o) noexcept { take(o); }
    Section& operator=(Section&& o) noexcept {
        if (this != &o) {
            release();
            take(o);
        }
        return *this;
    }

    uint16_t get(uint32_t i) const noexcept {
        switch (bits_) {
            case 0: return uniform_;
            case 4: {
                const uint32_t b = idx8()[i >> 1];
                return palette()[(b >> ((i & 1u) << 2)) & 15u];
            }
            case 8: return palette()[idx8()[i]];
            default: return direct()[i];
        }
    }

    // Writes one cell; returns the previous value. Grows the tier when the palette is full.
    uint16_t set(uint32_t i, uint16_t v);
    // Every cell v (drops the array).
    void fill(uint16_t v) {
        release();
        uniform_ = v;
    }
    // All 4096 cells from section-local order, at the narrowest tier.
    void assign(const uint16_t* values);
    // As assign() from an already known palette (n entries) and per-cell indices (decoders): skips the
    // distinct-value pass. values must agree with pal[index[i]].
    void assign_indexed(const uint16_t* values, const uint16_t* pal, uint32_t n, const uint8_t* index);
    void read(uint16_t* out4096) const;
    void read_row(uint32_t row, uint16_t* out16) const;

    // Exact: true (and *v) when every cell holds one value. Scans only 16-bit sections.
    bool uniform(uint16_t* v) const;
    bool is_uniform_storage() const { return bits_ == 0; }
    uint16_t uniform_value() const { return uniform_; }
    // True when every cell is 0 (exact, as uniform()).
    bool all_zero() const {
        uint16_t v = 0;
        return uniform(&v) && v == 0;
    }
    // Number of cells whose value != zero_value; exact (counts for paletted tiers, a scan for direct).
    uint32_t count_not(uint16_t zero_value) const;

    // A narrower tier would hold the content.
    bool compactable() const { return (bits_ == 8 && live_ <= 16) || bits_ == 16; }
    void compact();

    uint8_t bits() const { return bits_; }
    size_t heap_bytes() const;

private:
    uint8_t* data_ = nullptr;   // null: uniform
    uint16_t uniform_ = 0;
    uint16_t live_ = 0;         // live palette slots (4/8-bit)
    uint8_t bits_ = 0;          // 0, 4, 8, 16

    void release();
    void take(Section& o) {
        data_ = o.data_;
        uniform_ = o.uniform_;
        live_ = o.live_;
        bits_ = o.bits_;
        o.data_ = nullptr;
        o.bits_ = 0;
        o.live_ = 0;
    }
    uint32_t cap() const { return bits_ == 4 ? 16u : 256u; }
    // layout (4/8-bit): palette u16[cap] | counts u16[cap] | indices (4-bit packed / u8)
    uint16_t* palette() const { return reinterpret_cast<uint16_t*>(data_); }
    uint16_t* counts() const { return reinterpret_cast<uint16_t*>(data_) + cap(); }
    uint8_t* idx8() const { return data_ + 4u * cap(); }
    uint16_t* direct() const { return reinterpret_cast<uint16_t*>(data_); }
    uint32_t index_at(uint32_t i) const {
        if (bits_ == 4) {
            return (static_cast<uint32_t>(idx8()[i >> 1]) >> ((i & 1u) << 2)) & 15u;
        }
        return idx8()[i];
    }
    void write_index(uint32_t i, uint32_t slot);
    void build(const uint16_t* values, const uint16_t* palette, uint32_t n, const uint8_t* index);
    void upgrade();
};

} // namespace cenda::voxel
