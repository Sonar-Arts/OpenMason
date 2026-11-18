#include "region_file.hpp"

#include "crc32c.hpp"
#include "../voxel_world/cv_error.hpp"
#include "cenda/voxel_world.h"

#include <algorithm>
#include <cstring>
#include <utility>

namespace cenda::cvstore {

namespace {

constexpr uint32_t kFixedHeader = 64;
constexpr uint32_t kEntryBytes = 24;

void put_u16(uint8_t* p, uint16_t v) {
    p[0] = static_cast<uint8_t>(v);
    p[1] = static_cast<uint8_t>(v >> 8);
}
void put_u32(uint8_t* p, uint32_t v) {
    for (int i = 0; i < 4; ++i) p[i] = static_cast<uint8_t>(v >> (8 * i));
}
void put_u64(uint8_t* p, uint64_t v) {
    for (int i = 0; i < 8; ++i) p[i] = static_cast<uint8_t>(v >> (8 * i));
}
uint16_t get_u16(const uint8_t* p) { return static_cast<uint16_t>(p[0] | p[1] << 8); }
uint32_t get_u32(const uint8_t* p) {
    uint32_t v = 0;
    for (int i = 3; i >= 0; --i) v = v << 8 | p[i];
    return v;
}
uint64_t get_u64(const uint8_t* p) {
    uint64_t v = 0;
    for (int i = 7; i >= 0; --i) v = v << 8 | p[i];
    return v;
}

uint32_t sectors_for(uint64_t bytes) { return static_cast<uint32_t>((bytes + kSectorSize - 1) / kSectorSize); }

uint64_t mix64(uint64_t x) {
    x += 0x9E3779B97F4A7C15ull;
    x = (x ^ (x >> 30)) * 0xBF58476D1CE4E5B9ull;
    x = (x ^ (x >> 27)) * 0x94D049BB133111EBull;
    return x ^ (x >> 31);
}

} // namespace

size_t RegionKeyHash::operator()(const RegionKey& k) const noexcept {
    uint64_t h = mix64(static_cast<uint64_t>(k.kind));
    h = mix64(h ^ static_cast<uint64_t>(k.rx));
    h = mix64(h ^ static_cast<uint64_t>(k.ry));
    h = mix64(h ^ static_cast<uint64_t>(k.rz));
    return static_cast<size_t>(h);
}

Region::Region(StoreContext& ctx, const RegionKey& key) : ctx_(ctx), key_(key) {
    std::string name;
    if (key.kind == RegionKind::Cube) {
        region_edge_ = 8;
        entry_count_ = 512;
        name = "r." + std::to_string(key.rx) + "." + std::to_string(key.ry) + "." + std::to_string(key.rz) + ".cvr";
    } else {
        region_edge_ = 32;
        entry_count_ = 1024;
        name = "c." + std::to_string(key.rx) + "." + std::to_string(key.rz) + ".cvc";
    }
    path_ = ctx.dir / name;
    slot_sectors_ = sectors_for(kFixedHeader + uint64_t{kEntryBytes} * entry_count_ + 4);
    header_sectors_ = 2 * slot_sectors_;
}

Region::~Region() { file_.close(); }

bool Region::fault(FaultPoint point, bool* torn) {
    if (ctx_.tuning.hook == nullptr) return false;
    const FaultAction a = ctx_.tuning.hook(ctx_.tuning.hook_user, point);
    if (a == FaultAction::Continue) return false;
    if (a == FaultAction::Torn && torn != nullptr) *torn = true;
    ctx_.crashed.store(true);
    return true;
}

int32_t Region::crashed_error() const {
    cv_set_error("cv_store: store crashed (simulated fault); only close is allowed");
    return CV_ERR_IO;
}

int32_t Region::io_error(const std::string& what) {
    cv_set_error("cv_store: %s", what.c_str());
    return CV_ERR_IO;
}

int32_t Region::state_error() const {
    cv_set_error("cv_store: %s: %s", path_utf8(path_).c_str(), state_why_.c_str());
    return CV_ERR_CORRUPT;
}

void Region::reset_tables() {
    cur_.assign(entry_count_, Entry{});
    com_.assign(entry_count_, Entry{});
    ext_touched_.assign(entry_count_, 0);
    use_[kCur].assign(header_sectors_, 0);
    use_[kCom].assign(header_sectors_, 0);
    pack_.clear();
    pack_idx_.clear();
    alloc_hint_ = header_sectors_;
    dirty_ = false;
    pending_records_ = 0;
    pending_bytes_ = 0;
}

void Region::encode_header(uint64_t gen, const std::vector<Entry>& entries, std::vector<uint8_t>& out) const {
    out.assign(size_t{slot_sectors_} * kSectorSize, 0);
    uint8_t* p = out.data();
    put_u32(p + 0, kMagic);
    put_u16(p + 4, static_cast<uint16_t>(kFormatVersion));
    put_u16(p + 6, static_cast<uint16_t>(key_.kind));
    put_u32(p + 8, kCubeEdge);
    put_u32(p + 12, region_edge_);
    put_u32(p + 16, entry_count_);
    put_u32(p + 20, slot_sectors_);
    put_u64(p + 24, gen);
    put_u64(p + 32, static_cast<uint64_t>(key_.rx));
    put_u64(p + 40, static_cast<uint64_t>(key_.ry));
    put_u64(p + 48, static_cast<uint64_t>(key_.rz));
    put_u32(p + 56, kSectorSize);
    put_u32(p + 60, 0);
    for (uint32_t i = 0; i < entry_count_; ++i) {
        const Entry& e = entries[i];
        uint8_t* q = p + kFixedHeader + size_t{i} * kEntryBytes;
        if ((e.flags & kPresent) == 0) continue;
        if ((e.flags & kBad) != 0) {
            // Keep a damaged record damaged (reads stay CV_ERR_CORRUPT) without pointing at bytes
            // another record may own by now: offset 0 is inside the header area, invalid on load.
            put_u64(q + 0, 0);
            put_u32(q + 8, e.stored_len);
            put_u32(q + 12, e.raw_len);
            put_u32(q + 16, e.crc);
            put_u32(q + 20, e.flags & ~(kBad | kPending | kExternal | kExtSlotB));
            continue;
        }
        put_u64(q + 0, e.off);
        put_u32(q + 8, e.stored_len);
        put_u32(q + 12, e.raw_len);
        put_u32(q + 16, e.crc);
        put_u32(q + 20, e.flags & ~kPending);
    }
    const size_t body = kFixedHeader + size_t{kEntryBytes} * entry_count_;
    put_u32(p + body, crc32c(p, body));
}

bool Region::parse_slot(const uint8_t* bytes, size_t n, uint64_t& gen, std::vector<Entry>& entries,
                        bool& compatible, std::string& why) const {
    compatible = false;
    if (n < kFixedHeader || get_u32(bytes) != kMagic) return false;
    const uint32_t version = get_u16(bytes + 4);
    gen = get_u64(bytes + 24);
    if (version != kFormatVersion) {
        why = "region file format version " + std::to_string(version) + " (this build reads " +
              std::to_string(kFormatVersion) + ")";
        return true; // a real header we cannot read: never treat it as garbage to overwrite
    }
    const uint32_t count = get_u32(bytes + 16);
    if (count > 1u << 20) return false;
    const size_t body = kFixedHeader + size_t{kEntryBytes} * count;
    if (body + 4 > n || crc32c(bytes, body) != get_u32(bytes + body)) return false;
    // A valid header from here on; is it ours?
    const auto kind = get_u16(bytes + 6);
    const auto edge = get_u32(bytes + 8);
    if (kind != static_cast<uint16_t>(key_.kind) || get_u32(bytes + 12) != region_edge_ || count != entry_count_ ||
        get_u32(bytes + 20) != slot_sectors_ || get_u32(bytes + 56) != kSectorSize) {
        why = "region file layout does not match its name";
        return true;
    }
    if (edge != kCubeEdge) {
        why = "region file cube edge " + std::to_string(edge) + " (expected " + std::to_string(kCubeEdge) + ")";
        return true;
    }
    if (static_cast<int64_t>(get_u64(bytes + 32)) != key_.rx || static_cast<int64_t>(get_u64(bytes + 40)) != key_.ry ||
        static_cast<int64_t>(get_u64(bytes + 48)) != key_.rz) {
        why = "region file coordinates do not match its name";
        return true;
    }
    entries.assign(count, Entry{});
    for (uint32_t i = 0; i < count; ++i) {
        const uint8_t* q = bytes + kFixedHeader + size_t{i} * kEntryBytes;
        Entry& e = entries[i];
        e.off = get_u64(q + 0);
        e.stored_len = get_u32(q + 8);
        e.raw_len = get_u32(q + 12);
        e.crc = get_u32(q + 16);
        e.flags = get_u32(q + 20) & ~(kBad | kPending);
    }
    compatible = true;
    return true;
}

bool Region::in_file(const Entry& e) {
    return (e.flags & kPresent) != 0 && (e.flags & (kExternal | kBad | kPending)) == 0;
}

void Region::mark(const Entry& e, Table table, bool on) {
    if (!in_file(e)) return;
    const uint64_t first = e.off / kSectorSize;
    const uint64_t end = (e.off + e.stored_len + kSectorSize - 1) / kSectorSize;
    if (on && end > use_[kCur].size()) {
        use_[kCur].resize(end, 0);
        use_[kCom].resize(end, 0);
    }
    std::vector<uint16_t>& use = use_[table];
    for (uint64_t sct = first; sct < end && sct < use.size(); ++sct) {
        if (on) {
            ++use[sct];
        } else if (use[sct] > 0) {
            --use[sct];
            if (use_[kCur][sct] == 0 && use_[kCom][sct] == 0 && sct < alloc_hint_) alloc_hint_ = static_cast<uint32_t>(sct);
        }
    }
}

void Region::load_table(const std::vector<Entry>& entries, uint64_t file_bytes) {
    reset_tables();
    file_bytes_ = file_bytes;
    const uint64_t file_sectors = (file_bytes + kSectorSize - 1) / kSectorSize;
    if (file_sectors > use_[kCur].size()) {
        use_[kCur].resize(file_sectors, 0);
        use_[kCom].resize(file_sectors, 0);
    }
    const uint64_t header_bytes = uint64_t{header_sectors_} * kSectorSize;
    std::vector<Entry> checked = entries;
    std::vector<std::pair<uint64_t, uint32_t>> placed; // (offset, index) of in-file candidates
    for (uint32_t i = 0; i < entry_count_; ++i) {
        Entry& e = checked[i];
        if ((e.flags & kPresent) == 0) {
            e = Entry{};
            continue;
        }
        const uint32_t codec = (e.flags >> 8) & 0xFFu;
        bool bad = codec > 1 || e.raw_len == 0 || e.stored_len == 0 || (codec == 1 && e.stored_len != e.raw_len);
        if (!bad && (e.flags & kExternal) == 0) {
            // Inside the data area and the file (a truncated file loses its tail records).
            bad = e.off < header_bytes || e.off > file_bytes || file_bytes - e.off < e.stored_len;
            if (!bad) placed.emplace_back(e.off, i);
        }
        if (bad) e.flags |= kBad;
    }
    // Overlapping records cannot both be right: keep the first by offset, the checksum judges it.
    std::sort(placed.begin(), placed.end());
    uint64_t prev_end = 0;
    for (const auto& [off, i] : placed) {
        Entry& e = checked[i];
        if (off < prev_end) {
            e.flags |= kBad;
            continue;
        }
        prev_end = off + e.stored_len;
        mark(e, kCur, true);
        mark(e, kCom, true);
    }
    cur_ = checked;
    com_ = std::move(checked);
    alloc_hint_ = header_sectors_;
}

int32_t Region::create_fresh() {
    if (ctx_.crashed.load()) return crashed_error();
    std::string err;
    if (file_.open(path_, true, true, err) != File::Open::Ok) return io_error(err);
    reset_tables();
    gen_ = 0;
    std::vector<uint8_t> area;
    encode_header(0, cur_, area);
    area.resize(size_t{header_sectors_} * kSectorSize, 0); // slot 1 stays zero (invalid)
    // No fsync here: the first commit's data sync makes this generation-0 header durable before the
    // generation-1 header is written, and that commit also syncs the directory entry.
    if (!file_.write_at(0, area.data(), area.size(), err)) {
        file_.close();
        return io_error(err);
    }
    file_bytes_ = area.size();
    needs_dir_sync_ = true;
    state_ = State::Open;
    state_why_.clear();
    return CV_OK;
}

int32_t Region::open_existing() {
    if (state_ != State::Unopened) return CV_OK;
    std::string err;
    const File::Open r = file_.open(path_, false, false, err);
    if (r == File::Open::Missing) {
        state_ = State::Absent;
        return CV_OK;
    }
    if (r == File::Open::Error) return io_error(err);
    uint64_t size = 0;
    if (!file_.size(size, err)) {
        file_.close();
        return io_error(err);
    }
    const size_t slot_bytes = size_t{slot_sectors_} * kSectorSize;
    std::vector<uint8_t> area(2 * slot_bytes, 0);
    size_t got = 0;
    if (!file_.read_at(0, area.data(), area.size(), got, err)) {
        file_.close();
        return io_error(err);
    }
    int best = -1;
    uint64_t best_gen = 0;
    bool best_compatible = false;
    std::vector<Entry> best_entries;
    std::string best_why;
    for (int s = 0; s < 2; ++s) {
        const size_t off = static_cast<size_t>(s) * slot_bytes;
        const size_t avail = got > off ? std::min(slot_bytes, got - off) : 0;
        uint64_t gen = 0;
        bool compatible = false;
        std::vector<Entry> entries;
        std::string why;
        if (!parse_slot(area.data() + off, avail, gen, entries, compatible, why)) continue;
        if (compatible && (gen & 1u) != static_cast<uint64_t>(s)) continue; // not where we write it
        if (best < 0 || gen > best_gen) {
            best = s;
            best_gen = gen;
            best_compatible = compatible;
            best_entries = std::move(entries);
            best_why = std::move(why);
        }
    }
    if (best < 0) {
        file_.close();
        // Generation 1 is the first commit and lives in slot 1 (compaction also fills both slots), so
        // an all-zero slot 1 means nothing was ever committed: a crash during creation. Treat as absent.
        const auto slot1 = area.begin() + static_cast<std::ptrdiff_t>(std::min(got, slot_bytes));
        const bool never_committed = std::all_of(slot1, area.begin() + static_cast<std::ptrdiff_t>(got),
                                                 [](uint8_t b) { return b == 0; });
        if (never_committed) {
            state_ = State::Absent;
        } else {
            state_ = State::Corrupt;
            state_why_ = "no valid header slot (damaged region file)";
        }
        return CV_OK;
    }
    if (!best_compatible) {
        file_.close();
        state_ = State::Incompatible;
        state_why_ = best_why;
        return CV_OK;
    }
    load_table(best_entries, size);
    gen_ = best_gen;
    state_ = State::Open;
    state_why_.clear();
    return CV_OK;
}

int32_t Region::open_for_write() {
    if (state_ == State::Unopened) {
        const int32_t r = open_existing();
        if (r != CV_OK) return r;
    }
    switch (state_) {
    case State::Open:
        return CV_OK;
    case State::Absent:
        return create_fresh();
    case State::Incompatible:
        return state_error();
    case State::Corrupt: {
        // Nothing in it is readable; keep the bytes for forensics and start over.
        if (ctx_.crashed.load()) return crashed_error();
        std::string err;
        fs::path aside = path_;
        aside += ".corrupt";
        if (!rename_replace(path_, aside, err)) return io_error(err);
        state_ = State::Absent;
        return create_fresh();
    }
    case State::Unopened:
        break;
    }
    return io_error("region in an unexpected state");
}

fs::path Region::ext_path(uint32_t index, bool slot_b) const {
    fs::path p = path_;
    p += "." + std::to_string(index) + (slot_b ? ".b.cvx" : ".a.cvx");
    return p;
}

int32_t Region::read_external(uint32_t index, const Entry& e, std::vector<uint8_t>& out) const {
    File f;
    std::string err;
    const fs::path p = ext_path(index, (e.flags & kExtSlotB) != 0);
    const File::Open r = f.open(p, false, false, err);
    if (r == File::Open::Missing) {
        cv_set_error("cv_store: external record file %s is missing", path_utf8(p).c_str());
        return CV_ERR_CORRUPT;
    }
    if (r == File::Open::Error) {
        cv_set_error("cv_store: %s", err.c_str());
        return CV_ERR_IO;
    }
    out.resize(e.stored_len);
    size_t got = 0;
    if (!f.read_at(0, out.data(), out.size(), got, err)) {
        cv_set_error("cv_store: %s", err.c_str());
        return CV_ERR_IO;
    }
    if (got != e.stored_len) {
        cv_set_error("cv_store: external record file %s is truncated", path_utf8(p).c_str());
        return CV_ERR_CORRUPT;
    }
    return CV_OK;
}

int32_t Region::fetch(uint32_t index, std::vector<uint8_t>& out, Fetched& info) const {
    switch (state_) {
    case State::Absent:
        return 0;
    case State::Corrupt:
    case State::Incompatible:
        return state_error();
    case State::Unopened:
        cv_set_error("cv_store: region not open");
        return CV_ERR_IO;
    case State::Open:
        break;
    }
    const Entry& e = cur_[index];
    if ((e.flags & kPresent) == 0) return 0;
    if ((e.flags & kBad) != 0) {
        cv_set_error("cv_store: record %u of %s has a damaged table entry", index, path_utf8(path_).c_str());
        return CV_ERR_CORRUPT;
    }
    if ((e.flags & kPending) != 0) {
        out.assign(pack_.begin() + static_cast<std::ptrdiff_t>(e.off),
                   pack_.begin() + static_cast<std::ptrdiff_t>(e.off + e.stored_len));
    } else if ((e.flags & kExternal) != 0) {
        const int32_t r = read_external(index, e, out);
        if (r != CV_OK) return r;
    } else {
        out.resize(e.stored_len);
        size_t got = 0;
        std::string err;
        if (!file_.read_at(e.off, out.data(), out.size(), got, err)) {
            cv_set_error("cv_store: %s", err.c_str());
            return CV_ERR_IO;
        }
        if (got != e.stored_len) {
            cv_set_error("cv_store: record %u of %s is truncated", index, path_utf8(path_).c_str());
            return CV_ERR_CORRUPT;
        }
    }
    if (crc32c(out.data(), out.size()) != e.crc) {
        cv_set_error("cv_store: record %u of %s fails its checksum", index, path_utf8(path_).c_str());
        return CV_ERR_CORRUPT;
    }
    info.raw_len = e.raw_len;
    info.codec = static_cast<uint8_t>((e.flags >> 8) & 0xFFu);
    return 1;
}

uint32_t Region::allocate(uint32_t sectors) {
    const std::vector<uint16_t>& cur = use_[kCur];
    const std::vector<uint16_t>& com = use_[kCom];
    const auto is_free = [&](size_t i) { return cur[i] == 0 && com[i] == 0; };
    while (alloc_hint_ < cur.size() && !is_free(alloc_hint_)) ++alloc_hint_;
    size_t run = 0;
    size_t start = cur.size();
    for (size_t i = alloc_hint_; i < cur.size(); ++i) {
        if (!is_free(i)) {
            run = 0;
            continue;
        }
        if (run++ == 0) start = i;
        if (run == sectors) return static_cast<uint32_t>(start);
    }
    if (run == 0) start = cur.size(); // else: the free run at the end grows past the file end
    use_[kCur].resize(start + sectors, 0);
    use_[kCom].resize(start + sectors, 0);
    return static_cast<uint32_t>(start);
}

int32_t Region::flush_pack() {
    if (pack_.empty()) return CV_OK;
    bool live = false;
    for (const uint32_t i : pack_idx_) live = live || (cur_[i].flags & kPending) != 0;
    if (live) {
        const uint64_t base = uint64_t{allocate(1)} * kSectorSize;
        std::string err;
        if (!file_.write_at(base, pack_.data(), pack_.size(), err)) return io_error(err);
        file_bytes_ = std::max(file_bytes_, base + pack_.size());
        for (const uint32_t i : pack_idx_) {
            Entry& e = cur_[i];
            if ((e.flags & kPending) == 0) continue; // replaced, deleted, or already placed (put twice)
            e.off += base;
            e.flags &= ~kPending;
            mark(e, kCur, true);
        }
    }
    pack_.clear();
    pack_idx_.clear();
    if (live && fault(FaultPoint::AfterDataWrite)) return crashed_error();
    return CV_OK;
}

int32_t Region::put(uint32_t index, const uint8_t* stored, uint32_t stored_len, uint32_t raw_len, uint8_t codec,
                    uint32_t crc) {
    if (ctx_.crashed.load()) return crashed_error();
    if (state_ != State::Open) return io_error("region not open for writing");
    Entry ne;
    ne.stored_len = stored_len;
    ne.raw_len = raw_len;
    ne.crc = crc;
    ne.flags = kPresent | static_cast<uint32_t>(codec) << 8;
    std::string err;
    if (stored_len > ctx_.tuning.external_threshold) {
        const Entry& c = com_[index];
        const bool committed_ext = (c.flags & kPresent) != 0 && (c.flags & (kExternal | kBad | kPending)) == kExternal;
        const bool slot_b = committed_ext && (c.flags & kExtSlotB) == 0;
        const fs::path p = ext_path(index, slot_b);
        File f;
        if (f.open(p, true, true, err) != File::Open::Ok) return io_error(err);
        ext_touched_[index] = static_cast<uint8_t>(ext_touched_[index] | (slot_b ? 2u : 1u));
        if (!f.write_at(0, stored, stored_len, err) || !f.sync(err)) return io_error(err);
        f.close();
        needs_dir_sync_ = true; // the new name becomes durable at commit, before a header names it
        if (fault(FaultPoint::AfterExternalWrite)) return crashed_error();
        ne.flags |= kExternal | (slot_b ? kExtSlotB : 0u);
    } else if (stored_len <= ctx_.tuning.pack_threshold && stored_len <= kSectorSize) {
        if (pack_.size() + stored_len > kSectorSize) {
            const int32_t r = flush_pack();
            if (r != CV_OK) return r;
        }
        ne.off = pack_.size();
        ne.flags |= kPending;
        pack_.insert(pack_.end(), stored, stored + stored_len);
        pack_idx_.push_back(index);
    } else {
        ne.off = uint64_t{allocate(sectors_for(stored_len))} * kSectorSize;
        if (!file_.write_at(ne.off, stored, stored_len, err)) return io_error(err);
        file_bytes_ = std::max(file_bytes_, ne.off + stored_len);
        if (fault(FaultPoint::AfterDataWrite)) return crashed_error();
    }
    mark(cur_[index], kCur, false);
    cur_[index] = ne;
    mark(ne, kCur, true);
    dirty_ = true;
    ++pending_records_;
    pending_bytes_ += stored_len;
    if (pending_records_ >= ctx_.tuning.commit_every || pending_bytes_ >= ctx_.tuning.commit_bytes) return commit();
    return CV_OK;
}

int32_t Region::remove(uint32_t index) {
    if (ctx_.crashed.load()) return crashed_error();
    if (state_ != State::Open) return io_error("region not open for writing");
    const Entry old = cur_[index];
    if ((old.flags & kPresent) == 0) return CV_OK;
    mark(old, kCur, false);
    cur_[index] = Entry{};
    dirty_ = true;
    ++pending_records_;
    if (pending_records_ >= ctx_.tuning.commit_every) return commit();
    return CV_OK;
}

void Region::rebuild_committed_use() {
    std::fill(use_[kCom].begin(), use_[kCom].end(), uint16_t{0});
    for (const Entry& e : com_) mark(e, kCom, true);
    alloc_hint_ = header_sectors_;
}

int32_t Region::commit() {
    if (state_ != State::Open || !dirty_) return CV_OK;
    if (ctx_.crashed.load()) return crashed_error();
    const int32_t packed = flush_pack();
    if (packed != CV_OK) return packed;
    std::string err;
    if (fault(FaultPoint::BeforeDataSync)) return crashed_error();
    if (!file_.sync(err)) return io_error(err); // record data durable before any header names it
    if (needs_dir_sync_) { // new region file / external file names
        if (!sync_dir(ctx_.dir, err)) return io_error(err);
        needs_dir_sync_ = false;
    }
    if (fault(FaultPoint::AfterDataSync)) return crashed_error();

    const uint64_t next_gen = gen_ + 1;
    std::vector<uint8_t> header;
    encode_header(next_gen, cur_, header);
    const uint64_t slot_off = (next_gen & 1u) * uint64_t{slot_sectors_} * kSectorSize;
    bool torn = false;
    if (fault(FaultPoint::HeaderWrite, &torn)) {
        if (torn) (void)file_.write_at(slot_off, header.data(), header.size() / 2, err);
        return crashed_error();
    }
    if (!file_.write_at(slot_off, header.data(), header.size(), err)) return io_error(err);
    if (fault(FaultPoint::AfterHeaderWrite)) return crashed_error();
    if (!file_.sync(err)) return io_error(err);

    // Committed: the new table is the durable one.
    std::vector<Entry> old_com = std::move(com_);
    com_ = cur_;
    gen_ = next_gen;
    rebuild_committed_use();
    for (uint32_t i = 0; i < entry_count_; ++i) {
        uint32_t stale = ext_touched_[i];
        const auto ext_bit = [](const Entry& e) -> uint32_t {
            if ((e.flags & kPresent) == 0 || (e.flags & (kExternal | kBad | kPending)) != kExternal) return 0;
            return (e.flags & kExtSlotB) != 0 ? 2u : 1u;
        };
        stale |= ext_bit(old_com[i]);
        stale &= ~ext_bit(com_[i]);
        if ((stale & 1u) != 0) remove_file(ext_path(i, false));
        if ((stale & 2u) != 0) remove_file(ext_path(i, true));
        ext_touched_[i] = 0;
    }
    dirty_ = false;
    pending_records_ = 0;
    pending_bytes_ = 0;
    if (fault(FaultPoint::AfterCommit)) return crashed_error();

    const int32_t t = truncate_tail();
    if (t != CV_OK) return t;
    if (should_compact()) return compact();
    return CV_OK;
}

int32_t Region::truncate_tail() {
    uint64_t end = uint64_t{header_sectors_} * kSectorSize;
    for (const Entry& e : cur_)
        if (in_file(e)) end = std::max(end, e.off + e.stored_len);
    if (file_bytes_ <= end) return CV_OK;
    std::string err;
    if (!file_.truncate(end, err)) return io_error(err);
    file_bytes_ = end;
    // Precondition (just committed): no record lives past `end`, so these counts are all zero.
    const uint64_t sectors = std::max<uint64_t>(header_sectors_, (end + kSectorSize - 1) / kSectorSize);
    use_[kCur].resize(sectors);
    use_[kCom].resize(sectors);
    return CV_OK;
}

uint64_t Region::layout(std::vector<uint64_t>* offsets) const {
    if (offsets != nullptr) offsets->assign(entry_count_, 0);
    uint64_t cursor = uint64_t{header_sectors_} * kSectorSize;
    for (uint32_t i = 0; i < entry_count_; ++i) {
        const Entry& e = cur_[i];
        if (!in_file(e)) continue;
        const uint64_t in_sector = cursor % kSectorSize;
        const bool small = e.stored_len <= ctx_.tuning.pack_threshold && e.stored_len <= kSectorSize;
        // Small records fill the current sector; larger ones (and small ones that do not fit) start one.
        if (in_sector != 0 && (!small || e.stored_len > kSectorSize - in_sector)) cursor += kSectorSize - in_sector;
        if (offsets != nullptr) (*offsets)[i] = cursor;
        cursor += e.stored_len;
    }
    return cursor;
}

bool Region::should_compact() const {
    const uint64_t compacted = layout(nullptr);
    if (file_bytes_ <= compacted) return false;
    const uint64_t reclaim = file_bytes_ - compacted;
    return reclaim >= uint64_t{ctx_.tuning.compact_min_dead_sectors} * kSectorSize &&
           reclaim * 100 >= file_bytes_ * ctx_.tuning.compact_dead_percent;
}

int32_t Region::compact() {
    // Precondition: just committed (cur_ == com_, pack empty). Failures before the rename leave the old
    // file, which is complete and committed, so they are reported but harmless.
    fs::path tmp = path_;
    tmp += ".compact";
    std::string err;
    File out;
    if (out.open(tmp, true, true, err) != File::Open::Ok) return io_error(err);
    std::vector<uint64_t> offsets;
    layout(&offsets);
    std::vector<Entry> moved = cur_;
    std::vector<uint8_t> buf;
    for (uint32_t i = 0; i < entry_count_; ++i) {
        const Entry& e = cur_[i];
        if (!in_file(e)) continue;
        buf.assign(e.stored_len, 0);
        size_t got = 0;
        // A short read leaves zeros: the record keeps failing its checksum (CV_ERR_CORRUPT) as before.
        if (!file_.read_at(e.off, buf.data(), buf.size(), got, err) ||
            !out.write_at(offsets[i], buf.data(), buf.size(), err)) {
            out.close();
            remove_file(tmp);
            return io_error(err);
        }
        moved[i].off = offsets[i];
    }
    // The same table as generations N and N+1, one per slot: both slots valid, so a damaged slot later
    // still has a fallback, and slot 1 is never zero in a file that holds records.
    const uint64_t new_gen = gen_ + 2;
    std::vector<uint8_t> area(size_t{header_sectors_} * kSectorSize, 0);
    std::vector<uint8_t> header;
    for (uint64_t g = new_gen - 1; g <= new_gen; ++g) {
        encode_header(g, moved, header);
        std::memcpy(area.data() + (g & 1u) * header.size(), header.data(), header.size());
    }
    if (!out.write_at(0, area.data(), area.size(), err) || !out.sync(err)) {
        out.close();
        remove_file(tmp);
        return io_error(err);
    }
    if (fault(FaultPoint::CompactBeforeRename)) return crashed_error();
    out.close();
    file_.close();
    if (!rename_replace(tmp, path_, err)) {
        remove_file(tmp);
        std::string reopen_err;
        if (file_.open(path_, false, false, reopen_err) != File::Open::Ok) state_ = State::Unopened;
        return io_error(err);
    }
    if (fault(FaultPoint::CompactAfterRename)) return crashed_error();
    if (!sync_dir(ctx_.dir, err)) {
        state_ = State::Unopened;
        return io_error(err);
    }
    uint64_t size = 0;
    if (file_.open(path_, false, false, err) != File::Open::Ok || !file_.size(size, err)) {
        file_.close();
        state_ = State::Unopened;
        return io_error(err);
    }
    load_table(moved, size);
    gen_ = new_gen;
    // Sweep external files no table names (left by a crash between an external write and its commit).
    for (uint32_t i = 0; i < entry_count_; ++i) {
        const Entry& e = com_[i];
        const bool ext = (e.flags & kPresent) != 0 && (e.flags & (kExternal | kBad)) == kExternal;
        const bool keep_b = ext && (e.flags & kExtSlotB) != 0;
        if (!ext || keep_b) remove_file(ext_path(i, false));
        if (!ext || !keep_b) remove_file(ext_path(i, true));
    }
    return CV_OK;
}

int32_t Region::close() {
    if (state_ == State::Open) {
        const int32_t r = commit();
        if (r != CV_OK) return r;
    }
    file_.close();
    state_ = State::Unopened;
    state_why_.clear();
    cur_.clear();
    com_.clear();
    use_[kCur].clear();
    use_[kCom].clear();
    pack_.clear();
    pack_idx_.clear();
    ext_touched_.clear();
    return CV_OK;
}

void Region::abandon() {
    file_.close();
    state_ = State::Unopened;
}

} // namespace cenda::cvstore
