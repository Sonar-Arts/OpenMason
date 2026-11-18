// The streamer's owner helpers (stream_impl.hpp CubeState / ColumnState; owner thread only): column touches,
// priority, population origins and completeness, the first-mesh and remesh rules, CHANGED reports and the
// bookkeeping of an install.
#include "stream_impl.hpp"
#include "stream_internal.hpp"
#include "cv_internal.hpp"
#include "stream_plan.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace cenda::voxel::streaming {

// ---- owner helpers ------------------------------------------------------------------------------------------

void touch(cv_stream* s, int64_t x, int64_t z) {
    ColumnState* col = find_col(s, x, z);
    if (col == nullptr || col->touched) return;
    col->touched = true;
    s->touched.push_back(Int2{x, z});
}
CubeState& get_cube(cv_stream* s, const Int3& c) {
    auto [it, inserted] = s->cubes.try_emplace(c);
    if (inserted) {
        ColumnState& col = s->columns[Int2{c.x, c.z}];
        ++col.states;
        if (col.rec == 0 && (s->store == nullptr || s->remote)) col.rec = 2;
    }
    return it->second;
}
void erase_cube(cv_stream* s, const Int3& c) {
    if (s->cubes.erase(c) == 0) return;
    ColumnState* col = find_col(s, c.x, c.z);
    if (col != nullptr) --col->states;
    touch(s, c.x, c.z);
}

namespace {

// Squared distance (blocks) from the point cube c * 32 + off (per axis) to the nearest viewer.
uint64_t prio_at(const cv_stream* s, const Int3& c, double off) {
    if (s->owner_centers.empty()) return 0;
    double best = 1e300;
    for (const cv_center& v : s->owner_centers) {
        const double dx = static_cast<double>(c.x - (v.x >> 5)) * 32.0 + off - static_cast<double>(v.x & 31);
        const double dy = static_cast<double>(c.y - (v.y >> 5)) * 32.0 + off - static_cast<double>(v.y & 31);
        const double dz = static_cast<double>(c.z - (v.z >> 5)) * 32.0 + off - static_cast<double>(v.z & 31);
        best = std::min(best, dx * dx + dy * dy + dz * dz);
    }
    return static_cast<uint64_t>(std::min(best, 1.8e19));
}

} // namespace

// Owner-side priority of a cube (install order, mesh queue, mesh hand-out; lower = sooner): the planner's
// order (stream_plan.hpp "PRIORITY") - distance of its centre to the nearest viewer in 1/16 blocks, kBandBias
// less for the column's band and the cube above/below it.
uint64_t prio_of(cv_stream* s, const Int3& c) {
    double d = std::sqrt(static_cast<double>(prio_at(s, c, 16.0)));
    const auto it = s->columns.find(Int2{c.x, c.z});
    if (it != s->columns.end() && it->second.has_band && c.y >= it->second.band_lo - 1 && c.y <= it->second.band_hi + 1) {
        d = std::max(0.0, d - kBandBias);
    }
    return static_cast<uint64_t>(std::min(d * 16.0, 1.8e19));
}

// The cubes o..o+1 of every origin of a chain, each once.
void held_union(const std::vector<Int3>& origins, std::vector<Int3>& out) {
    out.clear();
    for (const Int3& o : origins) {
        for (int64_t d = 0; d < 8; ++d) {
            const Int3 x = add3(o, d & 1, (d >> 1) & 1, (d >> 2) & 1);
            if (std::find(out.begin(), out.end(), x) == out.end()) out.push_back(x);
        }
    }
}

bool origin_done(cv_stream* s, const Int3& o) {
    if (!cube_in(s, o.y)) return true;
    ColumnState* col = find_col(s, o.x, o.z);
    if (col == nullptr || col->rec != 2) return false;
    return std::binary_search(col->populated.begin(), col->populated.end(), o.y);
}

void enqueue_mesh(cv_stream* s, const Int3& c, CubeState& st) {
    if (s->no_mesh) return;
    st.flags |= kMeshQueued;
    s->mesh_q.push(HeapItem{prio_of(s, c), c});
}

// First mesh of a cube: once wanted, installed, complete (and, for host-installed cubes, its wanted
// neighbours arrived or the wait expired).
void consider_mesh(cv_stream* s, const Int3& c, CubeState& st) {
    if (s->no_mesh) return;   // readiness counts kComplete instead (step_columns)
    if (st.phase != kInstalled || st.level != kLevelWanted) return;
    if ((st.flags & kComplete) == 0) return;
    if ((st.flags & (kMeshed | kMeshQueued | kMeshInFlight | kHeld)) != 0) return;
    if ((st.flags & kAdopted) != 0 && (st.flags & kWaitOver) == 0 && !neighbours_ok(s, c)) {
        if (s->tick - st.installed_tick < kRemoteWaitTicks) {
            if ((st.flags & kRemoteWait) == 0) {
                st.flags |= kRemoteWait;
                s->remote_wait.push(TickItem{st.installed_tick + kRemoteWaitTicks, c});
            }
            return;
        }
    }
    st.flags &= static_cast<uint16_t>(~kRemoteWait);
    enqueue_mesh(s, c, st);
}

void request_remesh(cv_stream* s, const Int3& c, CubeState& st) {
    if (s->no_mesh || !installed(st)) return;
    if ((st.flags & kMeshInFlight) != 0) {
        st.flags |= kRemesh;
        return;
    }
    if ((st.flags & kMeshQueued) != 0) return;
    if ((st.flags & kMeshed) != 0) {
        enqueue_mesh(s, c, st);
    } else {
        consider_mesh(s, c, st);
    }
}

// CV_EV_CHANGED when the cube's revision moved past the one last reported (coalesces per tick: the second
// call in a tick finds seen_rev current).
void note_changed(cv_stream* s, const Int3& c, CubeState& st) {
    if (!installed(st)) return;
    const int64_t rev = cv_cube_revision(s->world, c.x, c.y, c.z);
    if (rev <= 0 || rev == st.seen_rev) return;
    st.seen_rev = rev;
    emit(s, CV_EV_CHANGED, -1, c.x, c.y, c.z);
}

void check_complete(cv_stream* s, const Int3& c, CubeState& st) {
    if ((st.flags & kComplete) != 0 || st.phase != kInstalled) return;
    for (int64_t d = 0; d < 8; ++d) {
        if (!origin_done(s, add3(c, -(d & 1), -((d >> 1) & 1), -((d >> 2) & 1)))) return;
    }
    st.flags |= kComplete;
    touch(s, c.x, c.z);
    consider_mesh(s, c, st);
}

void add_origin_candidates(cv_stream* s, const Int3& c) {
    if (s->remote) return;
    for (int64_t d = 0; d < 8; ++d) {
        const Int3 o = add3(c, -(d & 1), -((d >> 1) & 1), -((d >> 2) & 1));
        // Population origin o: the centre of its 2x2x2 cubes o..o+1.
        if (cube_in(s, o.y) && !origin_done(s, o) && s->pop_cand.insert(o).second) {
            s->pop_q.push(HeapItem{prio_at(s, o, 32.0), o});
        }
    }
}

void on_installed(cv_stream* s, const Int3& c, CubeState& st, bool adopted) {
    st.phase = kInstalled;
    st.installed_tick = s->tick;
    st.flags = static_cast<uint16_t>(st.flags & (kSaveInFlight | kHeld | kMeshInFlight));
    if (adopted) st.flags |= kComplete | kAdopted;
    st.seen_rev = cv_cube_revision(s->world, c.x, c.y, c.z);
    emit(s, CV_EV_INSTALLED, -1, c.x, c.y, c.z);
    touch(s, c.x, c.z);
    s->tops_changed = true;
    add_origin_candidates(s, c);
    if (adopted) {
        consider_mesh(s, c, st);
    } else {
        check_complete(s, c, st);
    }
    for (int64_t dy = -1; dy <= 1; ++dy) {
        for (int64_t dz = -1; dz <= 1; ++dz) {
            for (int64_t dx = -1; dx <= 1; ++dx) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                const Int3 n = add3(c, dx, dy, dz);
                CubeState* ns = find_cube(s, n);
                if (ns != nullptr && (ns->flags & kRemoteWait) != 0) consider_mesh(s, n, *ns);
            }
        }
    }
}

} // namespace cenda::voxel::streaming
