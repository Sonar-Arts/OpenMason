// Host-installed cubes and REMOTE mode (voxel_world.h "STREAMER RULES"): adoption of cubes the host installed,
// the wait for their wanted neighbours before a first mesh, the adopt grace period, and the missing-cube list a
// REMOTE host requests from its server (cv_stream_missing).
#include "stream_impl.hpp"
#include "stream_internal.hpp"
#include "cv_error.hpp"
#include "cv_internal.hpp"

#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

using namespace cenda::voxel::streaming;

namespace cenda::voxel::streaming {

bool neighbours_ok(cv_stream* s, const Int3& c) {
    for (int64_t dy = -1; dy <= 1; ++dy) {
        for (int64_t dz = -1; dz <= 1; ++dz) {
            for (int64_t dx = -1; dx <= 1; ++dx) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                const Int3 n = add3(c, dx, dy, dz);
                if (!cube_in(s, n.y)) continue;
                const CubeState* ns = find_cube(s, n);
                if (ns == nullptr || ns->level < kLevelWanted || installed(*ns)) continue;
                if (cube_known_air(s->world, n.x, n.y, n.z)) continue;
                return false;
            }
        }
    }
    return true;
}

void adopt(cv_stream* s, const Int3& c) {
    CubeState& st = get_cube(s, c);
    st.saved_rev = (s->store != nullptr && !s->remote) ? 0 : cv_cube_revision(s->world, c.x, c.y, c.z);
    on_installed(s, c, st, true);
    if (st.level == kLevelNone) s->adopt_wait.push(TickItem{s->tick + kAdoptGraceTicks, c});
}

void step_waits(cv_stream* s) {
    while (!s->remote_wait.empty() && s->remote_wait.top().tick <= s->tick) {
        const Int3 c = s->remote_wait.top().c;
        s->remote_wait.pop();
        CubeState* st = find_cube(s, c);
        if (st == nullptr || (st->flags & kRemoteWait) == 0) continue;
        st->flags = static_cast<uint16_t>((st->flags & ~kRemoteWait) | kWaitOver);
        consider_mesh(s, c, *st);
    }
    while (!s->adopt_wait.empty() && s->adopt_wait.top().tick <= s->tick) {
        const Int3 c = s->adopt_wait.top().c;
        s->adopt_wait.pop();
        CubeState* st = find_cube(s, c);
        if (st != nullptr && st->level == kLevelNone && st->phase == kInstalled) s->unload_q.push_back(c);
    }
}

} // namespace cenda::voxel::streaming

extern "C" {

int64_t cv_stream_missing(cv_stream* s, int64_t* out_xyz, int64_t cap) {
    if (s == nullptr || cap < 0 || (cap > 0 && out_xyz == nullptr)) {
        cv_set_error("cv_stream_missing: bad arguments");
        return CV_ERR_ARG;
    }
    std::shared_ptr<const std::vector<LoadItem>> list;
    {
        std::lock_guard<std::mutex> g(s->list_mutex);
        list = s->missing_list;
    }
    if (!list) return 0;
    int64_t n = 0;
    for (const LoadItem& it : *list) {
        if (cv_cube_exists(s->world, it.c.x, it.c.y, it.c.z) == 1) continue;
        if (n < cap) {
            const auto k = static_cast<size_t>(n) * 3;
            out_xyz[k] = it.c.x;
            out_xyz[k + 1] = it.c.y;
            out_xyz[k + 2] = it.c.z;
        }
        ++n;
    }
    return n > cap ? CV_NEED(n) : n;
}

} // extern "C"
