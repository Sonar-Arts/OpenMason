/* Region store (#120): one region file — format, sector allocation, shadow-paged commits, compaction.
 *
 * FILE FORMAT (little-endian)
 *  - 4 KiB sectors. The file starts with TWO header slots of S sectors each (S = 4 for cube regions,
 *    7 for column regions). A slot holds:
 *        0  u32 magic 'CVRG'          4  u16 version (1)       6  u16 kind (1 cube, 2 column)
 *        8  u32 cube edge (32)       12  u32 region edge (8 / 32)
 *       16  u32 entry count (512 / 1024)                       20  u32 slot sectors S
 *       24  u64 generation           32  i64 rx  40 i64 ry (0 for columns)  48 i64 rz
 *       56  u32 sector size (4096)   60  u32 reserved (0)
 *       64  entries[count] of 24 bytes:
 *               u64 byte offset in the file (0 for external), u32 stored length, u32 raw length,
 *               u32 crc32c of the stored bytes, u32 flags (1 present, 2 external, 4 external slot B,
 *               bits 8..15 codec: 0 zstd, 1 stored raw)
 *       then u32 crc32c of bytes [0, 64 + 24 * count); the rest of the slot is zero.
 *    Generation g always lives in slot (g & 1). Open picks the newest slot whose checksum and fields are
 *    valid; generation 0 (empty) is written to slot 0 when the file is created.
 *  - Record data follows the header area. A record larger than the pack threshold (3 KiB stored) gets
 *    its own run of whole sectors. Smaller records (uniform / air cubes encode to tens of bytes) are
 *    collected in an in-memory PACK and written together into one fresh sector when the pack is full or
 *    at commit, so a 30-byte record does not cost 4 KiB. Either way a sector is written only while no
 *    live record (committed or not) uses it, and never again until it is completely free: a torn write
 *    of a 4 KiB physical sector can only hit dead or uncommitted bytes. Records whose stored size
 *    exceeds the external threshold live in a file next to the region,
 *    "<region file>.<index>.<a|b>.cvx", the slot letter alternating so the committed copy is never the
 *    one being written.
 *
 * SHADOW PAGING
 *  - A write never touches a sector the COMMITTED header references: the allocator only hands out
 *    sectors that neither the committed table nor the in-memory table use. Writes become visible to
 *    readers at once (in-memory table) and durable at the next commit: data fsync, then the new header
 *    into the other slot, then fsync. A crash anywhere leaves the old header (old records) or the new one
 *    (new records); a torn header slot fails its checksum and the other slot wins.
 *  - Commits happen on flush, on handle eviction and close, and automatically after commit_every record
 *    writes / commit_bytes stored bytes to one file. The new name of a created region / external file is
 *    made durable (directory fsync) in the commit, before the header that needs it.
 *  - Compaction rewrites the live records into "<region file>.compact", fsyncs it and renames it over
 *    the region (atomic), so a crash leaves either the old or the new file. Trailing free sectors are
 *    truncated after every commit.
 */
#pragma once

#include "store_io.hpp"

#include <atomic>
#include <cstdint>
#include <shared_mutex>
#include <string>
#include <vector>

namespace cenda::cvstore {

enum class RegionKind : uint16_t { Cube = 1, Column = 2 };

struct RegionKey {
    RegionKind kind;
    int64_t rx, ry, rz;
    bool operator==(const RegionKey&) const = default;
};

struct RegionKeyHash {
    size_t operator()(const RegionKey& k) const noexcept;
};

/* Test seam: named points in the write/commit/compact paths. A hook returning Crash makes the store
 * behave like a killed process from that instant: nothing more is written, every later call fails with
 * CV_ERR_IO, close only releases handles. Torn at HeaderWrite writes the first half of the slot first. */
enum class FaultPoint {
    AfterDataWrite,      /* record bytes written, header not committed */
    AfterExternalWrite,  /* external record file written and synced */
    BeforeDataSync,      /* commit: before the data fsync */
    AfterDataSync,       /* commit: data durable, header not written */
    HeaderWrite,         /* commit: the header slot write itself (Torn allowed) */
    AfterHeaderWrite,    /* commit: header written, not yet synced */
    AfterCommit,         /* commit done, before external cleanup / truncation / compaction */
    CompactBeforeRename, /* compaction: new file synced, not renamed */
    CompactAfterRename   /* compaction: renamed, directory not synced */
};
enum class FaultAction { Continue, Crash, Torn };
using FaultHook = FaultAction (*)(void* user, FaultPoint point);

struct Tuning {
    int32_t zstd_level = 3;
    uint32_t external_threshold = 1u << 20;   /* stored bytes above this go to an external file */
    uint32_t pack_threshold = 3072;           /* stored bytes up to this share pack sectors */
    /* Auto commit (2 fsyncs, ~5-15 ms on a real disk) bounds what a crash can lose between flushes;
     * the host's flush (autosave) is the normal commit point, so these are generous. */
    uint32_t commit_every = 1024;             /* uncommitted record writes per file before auto commit */
    uint64_t commit_bytes = 32ull << 20;      /* uncommitted stored bytes per file before auto commit */
    size_t max_open_files = 256;              /* LRU bound of region objects (each holds <= 1 handle) */
    /* Compaction runs when the bytes it would reclaim (file size minus the size of a compacted copy)
     * reach both thresholds. */
    uint32_t compact_min_dead_sectors = 64;   /* x 4 KiB = 256 KiB */
    uint32_t compact_dead_percent = 25;       /* reclaimable / file bytes */
    FaultHook hook = nullptr;
    void* hook_user = nullptr;
};

/* Shared state of one store that regions need. */
struct StoreContext {
    fs::path dir;
    Tuning tuning;
    std::atomic<bool> crashed{false};
};

constexpr uint32_t kSectorSize = 4096;
constexpr uint32_t kFormatVersion = 1;
constexpr uint32_t kMagic = 0x47525643u; /* 'C' 'V' 'R' 'G' */
constexpr uint32_t kCubeEdge = 32;

/* Result of a raw record fetch (under the region lock) before decompression (outside it). */
struct Fetched {
    uint32_t raw_len = 0;
    uint8_t codec = 0;
};

class Region {
public:
    enum class State { Unopened, Absent, Open, Corrupt, Incompatible };

    Region(StoreContext& ctx, const RegionKey& key);
    ~Region();
    Region(const Region&) = delete;
    Region& operator=(const Region&) = delete;

    /* All members below require the caller to hold `mu`: shared for fetch, exclusive for the rest. */
    std::shared_mutex mu;

    State state() const { return state_; }
    uint32_t entry_count() const { return entry_count_; }

    /* Opens the file if it exists (exclusive). Absent / Corrupt / Incompatible are states, not errors;
     * only an I/O failure returns an error (CV_ERR_*). */
    int32_t open_existing();
    /* Makes the file writable: creates it when absent, moves a garbage file aside (exclusive). */
    int32_t open_for_write();

    /* Copies a record's stored bytes into `out` (shared lock is enough). Returns 1 present, 0 absent, or
     * CV_ERR_*; for a too-small dst this is still 1 (the caller compares raw_len to its capacity). */
    int32_t fetch(uint32_t index, std::vector<uint8_t>& out, Fetched& info) const;

    /* Stores a record (already compressed) or deletes it (stored == nullptr). Exclusive. */
    int32_t put(uint32_t index, const uint8_t* stored, uint32_t stored_len, uint32_t raw_len, uint8_t codec,
                uint32_t crc);
    int32_t remove(uint32_t index);

    bool dirty() const { return dirty_; }
    /* Commits the in-memory table if dirty, then truncates the free tail and compacts when worth it. */
    int32_t commit();
    /* Commits, then releases the file handle (state back to Unopened). */
    int32_t close();
    /* Releases the handle without writing anything (simulated crash). */
    void abandon();

    /* Test introspection. */
    uint64_t file_sectors() const { return use_[kCur].size(); }
    uint64_t generation() const { return gen_; }

private:
    struct Entry {
        uint64_t off = 0; /* file byte offset; while kPending: offset inside pack_ */
        uint32_t stored_len = 0, raw_len = 0, crc = 0, flags = 0;
    };
    static constexpr uint32_t kPresent = 1u, kExternal = 2u, kExtSlotB = 4u;
    static constexpr uint32_t kBad = 0x80000000u;     /* in memory only: entry fails validation */
    static constexpr uint32_t kPending = 0x40000000u; /* in memory only: bytes still in pack_ */
    enum Table { kCur = 0, kCom = 1 };

    bool fault(FaultPoint point, bool* torn = nullptr);
    int32_t crashed_error() const;
    int32_t io_error(const std::string& what);
    int32_t state_error() const;

    void reset_tables();
    bool parse_slot(const uint8_t* bytes, size_t n, uint64_t& gen, std::vector<Entry>& entries,
                    bool& compatible, std::string& why) const;
    void encode_header(uint64_t gen, const std::vector<Entry>& entries, std::vector<uint8_t>& out) const;
    void load_table(const std::vector<Entry>& entries, uint64_t file_bytes);
    static bool in_file(const Entry& e);
    void mark(const Entry& e, Table table, bool on);
    uint32_t allocate(uint32_t sectors);
    int32_t flush_pack();
    void rebuild_committed_use();
    int32_t create_fresh();
    int32_t truncate_tail();
    int32_t compact();
    bool should_compact() const;
    /* Where compaction would place every in-file record (offsets[i], 0 for others); returns the end. */
    uint64_t layout(std::vector<uint64_t>* offsets) const;
    fs::path ext_path(uint32_t index, bool slot_b) const;
    int32_t read_external(uint32_t index, const Entry& e, std::vector<uint8_t>& out) const;

    StoreContext& ctx_;
    RegionKey key_;
    fs::path path_;
    uint32_t region_edge_;
    uint32_t entry_count_;
    uint32_t slot_sectors_;
    uint32_t header_sectors_;

    State state_ = State::Unopened;
    std::string state_why_;
    File file_;
    uint64_t file_bytes_ = 0;
    uint64_t gen_ = 0;
    std::vector<Entry> cur_, com_;
    std::vector<uint16_t> use_[2];     /* per sector: live records of the cur / com table in it */
    std::vector<uint8_t> pack_;        /* small records not yet written (<= one sector) */
    std::vector<uint32_t> pack_idx_;   /* entries that were put into pack_ */
    std::vector<uint8_t> ext_touched_; /* per entry: bit0 slot A, bit1 slot B written since last commit */
    uint32_t alloc_hint_ = 0;
    bool dirty_ = false;
    bool needs_dir_sync_ = false; /* a file name was created since the last commit */
    uint32_t pending_records_ = 0;
    uint64_t pending_bytes_ = 0;
};

} // namespace cenda::cvstore
