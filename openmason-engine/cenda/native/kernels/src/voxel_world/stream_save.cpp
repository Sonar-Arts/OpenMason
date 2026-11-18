// The streamer's store side (voxel_world.h "STREAMER RULES", "RECORDS"): column record loads, unloads (pending ->
// saving -> removed), save_all, and the envelope of the stream's column record.
#include "stream_impl.hpp"
#include "stream_internal.hpp"
#include "cv_internal.hpp"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <utility>
#include <vector>

namespace cenda::voxel::streaming {

// ---- column records -------------------------------------------------------------------------------------------

void request_column(cv_stream* s, const Int2& k, ColumnState& col) {
    if (col.rec != 0) return;
    if (s->store == nullptr || s->remote) {
        col.rec = 2;
        return;
    }
    col.rec = 1;
    ++s->colload_in_flight;
    Job j;
    j.kind = JobKind::LoadColumn;
    j.c = Int3{k.x, 0, k.z};
    submit(s, std::move(j));
}

void apply_column_record(cv_stream* s, const Int3& k, ColumnState& col, const Result& r) {
    if (r.status < 0) {
        s->errors_total.fetch_add(1, std::memory_order_relaxed);
        if (r.status != CV_ERR_CORRUPT) {   // I/O: try again later
            col.rec = 0;
            return;
        }
    } else if (!r.bytes.empty()) {
        std::vector<int64_t> populated;
        const uint8_t* core = nullptr;
        size_t core_len = 0;
        if (!parse_column_record(r.bytes.data(), r.bytes.size(), populated, &core, &core_len)) {
            s->errors_total.fetch_add(1, std::memory_order_relaxed);
        } else {
            if (core_len > 0 && cv_column_decode(s->world, k.x, k.z, core, static_cast<int64_t>(core_len)) != CV_OK) {
                s->errors_total.fetch_add(1, std::memory_order_relaxed);
            }
            col.populated = std::move(populated);
        }
    }
    col.rec = 2;
    s->tops_changed = true;
    touch(s, k.x, k.z);
}

// ---- unloads + saves ------------------------------------------------------------------------------------------

namespace {

bool save_needed(cv_stream* s, const Int3& c, const CubeState& st) {
    return s->store != nullptr && !s->remote && cv_cube_revision(s->world, c.x, c.y, c.z) != st.saved_rev;
}

void submit_save(cv_stream* s, const Int3& c, CubeState& st) {
    st.flags |= kSaveInFlight;
    ++s->save_in_flight;
    Job j;
    j.kind = JobKind::SaveCube;
    j.c = c;
    submit(s, std::move(j));
}

void remove_cube(cv_stream* s, const Int3& c) {
    cv_cube_remove(s->world, c.x, c.y, c.z);
    emit(s, CV_EV_UNLOADED, -1, c.x, c.y, c.z);
    ++s->unloaded_total;
    erase_cube(s, c);
}

void finish_unload(cv_stream* s, const Int3& c, CubeState& st) {
    if (save_needed(s, c, st)) {
        st.phase = kUnloadSaving;
        submit_save(s, c, st);
        return;
    }
    remove_cube(s, c);
}

} // namespace

void step_pending_unloads(cv_stream* s) {
    std::vector<Int3> keep;
    std::vector<Int3> list;
    list.swap(s->pending_unloads);
    for (const Int3& c : list) {
        CubeState* st = find_cube(s, c);
        if (st == nullptr || st->phase != kUnloadPending) continue;
        if (st->level > kLevelNone) {   // wanted again before it went: keep it
            st->phase = kInstalled;
            touch(s, c.x, c.z);
            consider_mesh(s, c, *st);
            continue;
        }
        if (st->pending_tick >= s->tick || (st->flags & (kHeld | kSaveInFlight)) != 0) {
            keep.push_back(c);
            continue;
        }
        finish_unload(s, c, *st);
    }
    for (const Int3& c : keep) s->pending_unloads.push_back(c);

    list.clear();
    list.swap(s->resume_unloads);
    for (const Int3& c : list) {
        CubeState* st = find_cube(s, c);
        if (st == nullptr || st->phase != kUnloadSaving || (st->flags & kSaveInFlight) != 0) continue;
        if (st->level > kLevelNone) {
            st->phase = kInstalled;
            touch(s, c.x, c.z);
            consider_mesh(s, c, *st);
            continue;
        }
        if (save_needed(s, c, *st)) {
            submit_save(s, c, *st);   // edited while saving
            continue;
        }
        remove_cube(s, c);
    }
}

void step_unload_select(cv_stream* s, int64_t max_unloads) {
    std::vector<Int3> deferred;
    int64_t n = 0;
    while (!s->unload_q.empty() && n < max_unloads) {
        const Int3 c = s->unload_q.front();
        s->unload_q.pop_front();
        CubeState* st = find_cube(s, c);
        if (st == nullptr || st->level != kLevelNone || st->phase != kInstalled) continue;
        if ((st->flags & kHeld) != 0 || ((st->flags & kAdopted) != 0 && s->tick - st->installed_tick < kAdoptGraceTicks)) {
            deferred.push_back(c);
            continue;
        }
        ColumnState* col = find_col(s, c.x, c.z);
        if (col != nullptr && col->ready && c.y >= col->ready_lo && c.y <= col->ready_hi) {
            col->ready = false;   // far LOD back first (this tick, before UNLOADED next tick)
            emit(s, CV_EV_COLUMN_UNREADY, -1, c.x, 0, c.z);
        }
        emit(s, CV_EV_UNLOAD_PENDING, -1, c.x, c.y, c.z);
        st->phase = kUnloadPending;
        st->pending_tick = s->tick;
        s->pending_unloads.push_back(c);
        touch(s, c.x, c.z);
        ++n;
    }
    for (const Int3& c : deferred) s->unload_q.push_back(c);
}

int32_t save_all_locked(cv_stream* s) {
    if (s->store == nullptr || s->remote) return CV_OK;
    s->saving_all = true;
    s->save_error = 0;
    // Populations in flight write into their cubes: let them finish so the snapshot is consistent.
    wait_for_jobs(s, [&] { return s->pop_in_flight == 0; });
    for (auto& [c, st] : s->cubes) {
        if (!installed(st) || (st.flags & kSaveInFlight) != 0) continue;
        if (cv_cube_revision(s->world, c.x, c.y, c.z) != st.saved_rev) submit_save(s, c, st);
    }
    wait_for_jobs(s, [&] { return s->save_in_flight == 0; });
    for (auto& [k, col] : s->columns) {
        if (!col.dirty || col.rec != 2 || col.saving) continue;
        col.saving = true;
        col.dirty = false;
        ++s->colsave_in_flight;
        Job j;
        j.kind = JobKind::SaveColumn;
        j.c = Int3{k.x, 0, k.z};
        j.populated = col.populated;
        submit(s, std::move(j));
    }
    wait_for_jobs(s, [&] { return s->colsave_in_flight == 0; });
    int32_t rc = s->save_error;
    const int32_t f = cv_store_flush(s->store);
    if (rc == 0 && f < 0) rc = f;
    s->saving_all = false;
    return rc;
}

} // namespace cenda::voxel::streaming

// ---- column record envelope ----------------------------------------------------------------------------------

namespace cenda::voxel::streaming {

namespace {
uint32_t rd32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) | (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}
int64_t rd64(const uint8_t* p) {
    uint64_t v = 0;
    for (int i = 7; i >= 0; --i) v = (v << 8) | p[i];
    return static_cast<int64_t>(v);
}
void wr32(std::vector<uint8_t>& o, uint32_t v) {
    for (int i = 0; i < 4; ++i) o.push_back(static_cast<uint8_t>(v >> (8 * i)));
}
void wr64(std::vector<uint8_t>& o, int64_t v) {
    const auto u = static_cast<uint64_t>(v);
    for (int i = 0; i < 8; ++i) o.push_back(static_cast<uint8_t>(u >> (8 * i)));
}
} // namespace

bool parse_column_record(const uint8_t* p, size_t n, std::vector<int64_t>& populated, const uint8_t** core,
                         size_t* core_len) {
    populated.clear();
    if (p == nullptr || n < 12 || rd32(p) != kColumnMagic) return false;
    const uint32_t runs = rd32(p + 4);
    size_t at = 8;
    if (runs > (n - at) / 16) return false;
    int64_t prev_hi = 0;
    for (uint32_t i = 0; i < runs; ++i) {
        const int64_t lo = rd64(p + at), hi = rd64(p + at + 8);
        at += 16;
        if (lo > hi || (i > 0 && lo <= prev_hi) || hi - lo > (1 << 20)) return false;
        if (populated.size() + static_cast<size_t>(hi - lo + 1) > (size_t{1} << 22)) return false;
        for (int64_t y = lo;; ++y) {
            populated.push_back(y);
            if (y == hi) break;
        }
        prev_hi = hi;
    }
    if (n - at < 4) return false;
    const uint32_t len = rd32(p + at);
    at += 4;
    if (n - at != len) return false;
    *core = p + at;
    *core_len = len;
    return true;
}

void build_column_record(const std::vector<int64_t>& populated, const uint8_t* core, size_t core_len,
                         std::vector<uint8_t>& out) {
    out.clear();
    wr32(out, kColumnMagic);
    std::vector<std::pair<int64_t, int64_t>> runs;
    for (const int64_t y : populated) {
        if (!runs.empty() && runs.back().second + 1 == y) {
            runs.back().second = y;
        } else {
            runs.emplace_back(y, y);
        }
    }
    wr32(out, static_cast<uint32_t>(runs.size()));
    for (const auto& [lo, hi] : runs) {
        wr64(out, lo);
        wr64(out, hi);
    }
    wr32(out, static_cast<uint32_t>(core_len));
    out.insert(out.end(), core, core + core_len);
}

} // namespace cenda::voxel::streaming
