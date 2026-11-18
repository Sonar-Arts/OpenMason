// Octree LOD runtime: the selection (Selector) on the planner thread, the planner's node states and its commits
// to the owner. Shared runtime structures: lod_runtime.hpp.
#include "lod_runtime.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

namespace cenda::voxel::lod {

// ---- selection (planner thread) -----------------------------------------------------------------------------
// The selection runs on the PLANNER thread, per root, only for roots something changed in (a prepare result, a data
// change, readiness, the view). Each changed root becomes a Commit (that root's whole cover). The owner tick only
// applies prepared commits within its budget and keeps the drawn cover valid on its own in between (readiness
// patches + gap fills, see "owner" below), so cv_lod_tick never runs an O(nodes) step.

namespace {

void stack_range(const cv_lod* L, int32_t level, int64_t* lo, int64_t* hi) {
    *lo = floor_div64(L->min_y, span(level));
    *hi = floor_div64(L->max_y - 1, span(level));
}

} // namespace

double foot_dmin(const FootKey& f, const std::vector<cv_lod_viewer>& viewers) {
    const double s = static_cast<double>(span(f.level));
    const double x0 = static_cast<double>(f.x) * s, z0 = static_cast<double>(f.z) * s;
    double best = std::numeric_limits<double>::infinity();
    for (const cv_lod_viewer& v : viewers) {
        const double vx = static_cast<double>(v.x) + 0.5, vz = static_cast<double>(v.z) + 0.5;
        const double dx = std::max({x0 - vx, 0.0, vx - (x0 + s)});
        const double dz = std::max({z0 - vz, 0.0, vz - (z0 + s)});
        best = std::min(best, std::sqrt(dx * dx + dz * dz));
    }
    return best;
}

// Farthest point of the footprint from the viewer nearest to that point, bounded above by the minimum over
// viewers of the farthest corner (conservative: "the whole footprint lies within r of some viewer").
double foot_dmax(const FootKey& f, const std::vector<cv_lod_viewer>& viewers) {
    const double s = static_cast<double>(span(f.level));
    const double x0 = static_cast<double>(f.x) * s, z0 = static_cast<double>(f.z) * s;
    double best = std::numeric_limits<double>::infinity();
    for (const cv_lod_viewer& v : viewers) {
        const double vx = static_cast<double>(v.x) + 0.5, vz = static_cast<double>(v.z) + 0.5;
        const double dx = std::max(std::abs(x0 - vx), std::abs(x0 + s - vx));
        const double dz = std::max(std::abs(z0 - vz), std::abs(z0 + s - vz));
        best = std::min(best, std::sqrt(dx * dx + dz * dz));
    }
    return best;
}

namespace {

// The finest level an INCOMPLETE stack is refined to (policy.incomplete_min_level: 0 = 1, < 0 = 0).
inline int32_t incomplete_min_level(const cv_lod_policy& P) {
    const int32_t v = P.incomplete_min_level;
    return v == 0 ? 1 : (v < 0 ? 0 : v);
}

struct Selector {
    cv_lod* L;
    const cv_lod_policy& P;
    double K;
    int32_t min_inc;

    double dmin(const FootKey& f) const { return foot_dmin(f, L->p_viewers); }
    bool leaf(const FootKey& f, double d) const {
        return f.level == 0 || d >= std::ldexp(K, f.level);
    }
    int64_t ready(const FootKey& f) const {
        auto it = L->p_ready_count.find(f);
        return it == L->p_ready_count.end() ? 0 : it->second;
    }
    static int64_t total(int32_t level) { return foot_total(level); }
    static FootKey child(const FootKey& f, int i) { return foot_child(f, i); }

    NodeState& state(const NodeKey& k) {
        NodeState& s = L->states[k];
        s.touched = L->epoch;
        return s;
    }

    void request(const FootKey& f, double d) {
        int64_t lo, hi;
        stack_range(L, f.level, &lo, &hi);
        for (int64_t ny = lo; ny <= hi; ++ny) {
            const NodeKey k{f.level, f.x, ny, f.z};
            NodeState& s = state(k);
            if (s.stale()) L->candidates.push_back(Candidate{d, k});
        }
    }

    enum Status { kBuilt, kPending, kIncomplete };
    Status status(const FootKey& f) {
        int64_t lo, hi;
        stack_range(L, f.level, &lo, &hi);
        bool pending = false;
        for (int64_t ny = lo; ny <= hi; ++ny) {
            auto it = L->states.find(NodeKey{f.level, f.x, ny, f.z});
            if (it == L->states.end() || !it->second.known) {
                pending = true;
                continue;
            }
            const NodeState& s = it->second;
            if (!s.complete) {
                if (!s.stale()) return kIncomplete;
                pending = true;
                continue;
            }
            if (!s.mesh) pending = true;
        }
        return pending ? kPending : kBuilt;
    }

    void add_stack(const FootKey& f, Group& out) {
        int64_t lo, hi;
        stack_range(L, f.level, &lo, &hi);
        out.stacks.push_back(f);
        for (int64_t ny = lo; ny <= hi; ++ny) {
            const NodeKey k{f.level, f.x, ny, f.z};
            NodeState& s = state(k);
            if (s.mesh && !s.mesh->empty()) out.nodes.push_back(k);
        }
    }

    static void append(Group& to, Group& from) {
        to.nodes.insert(to.nodes.end(), from.nodes.begin(), from.nodes.end());
        to.fallbacks.insert(to.fallbacks.end(), from.fallbacks.begin(), from.fallbacks.end());
        to.stacks.insert(to.stacks.end(), from.stacks.begin(), from.stacks.end());
    }

    // True when F's area (minus ready columns) is covered by built stacks below F (no requests).
    bool probe(const FootKey& f, int depth, Group& out) {
        if (depth == 0 || f.level == 0) return false;
        for (int i = 0; i < 4; ++i) {
            const FootKey c = child(f, i);
            const int64_t rc = ready(c);
            if (rc == total(c.level)) continue;
            if (rc == 0 && status(c) == kBuilt) {
                add_stack(c, out);
                continue;
            }
            if (!probe(c, depth - 1, out)) return false;
        }
        return true;
    }

    // F must be covered at its level or finer; returns true when the cover holds a FALLBACK.
    bool cover_leaf(const FootKey& f, double d, bool partial, int idepth, Group& out) {
        if (partial) {
            bool fb = false;
            for (int i = 0; i < 4; ++i) fb |= cover_sub(child(f, i), idepth, out);
            return fb;
        }
        request(f, d);
        const Status st = status(f);
        if (st == kBuilt) {
            add_stack(f, out);
            return false;
        }
        if (f.level == 0) {
            out.fallbacks.push_back(f);
            return true;
        }
        if (st == kIncomplete) {
            // Unexplored land: its explored parts are drawn down to min_inc, the rest is the host's FALLBACK
            // (cheaper than many fine incomplete stacks).
            if (idepth > 0 && f.level > min_inc) {
                bool fb = false;
                for (int i = 0; i < 4; ++i) fb |= cover_sub(child(f, i), idepth - 1, out);
                return fb;
            }
            out.fallbacks.push_back(f);
            return true;
        }
        Group tmp;
        if (probe(f, 2, tmp)) {
            append(out, tmp);
            return false;
        }
        out.fallbacks.push_back(f);
        return true;
    }

    bool cover_sub(const FootKey& c, int idepth, Group& out) {
        const int64_t rc = ready(c);
        if (rc == total(c.level)) return false;
        return cover_leaf(c, dmin(c), rc > 0, idepth, out);
    }

    void standby(const FootKey& f, double d) {
        if (P.standby_from < 0) return;
        const double diag = static_cast<double>(span(f.level)) * 1.4142135623730951;
        if (d + diag < static_cast<double>(P.standby_from)) return;
        if (leaf(f, d)) {
            if (d >= static_cast<double>(P.standby_from)) request(f, d);
            return;
        }
        for (int i = 0; i < 4; ++i) {
            const FootKey c = child(f, i);
            const double dc = dmin(c);
            if (dc <= static_cast<double>(P.radius)) standby(c, dc);
        }
    }

    bool cover(const FootKey& f, Group& out) {
        const double d = dmin(f);
        if (d > static_cast<double>(P.radius)) return false;
        const int64_t rc = ready(f);
        if (rc == total(f.level)) {
            standby(f, d);
            return false;
        }
        if (leaf(f, d)) return cover_leaf(f, d, rc > 0, P.incomplete_depth, out);
        Group tmp;
        bool fb = false;
        for (int i = 0; i < 4; ++i) fb |= cover(child(f, i), tmp);
        if (fb && rc == 0 && status(f) == kBuilt) {
            add_stack(f, out);   // the parent until all children are ready
            return false;
        }
        append(out, tmp);
        return fb;
    }
};

} // namespace

// Roots (footprints of the root level) within the radius of the viewers.
void roots_in_range(const std::vector<cv_lod_viewer>& viewers, const cv_lod_policy& P, std::vector<FootKey>& out) {
    out.clear();
    const int32_t rl = P.max_level;
    const int64_t rs = span(rl);
    for (const cv_lod_viewer& v : viewers) {
        const int64_t x0 = floor_div64(v.x - P.radius, rs), x1 = floor_div64(v.x + P.radius, rs);
        const int64_t z0 = floor_div64(v.z - P.radius, rs), z1 = floor_div64(v.z + P.radius, rs);
        for (int64_t x = x0; x <= x1; ++x) {
            for (int64_t z = z0; z <= z1; ++z) {
                const FootKey r{rl, x, z};
                if (foot_dmin(r, viewers) <= static_cast<double>(P.radius)) out.push_back(r);
            }
        }
    }
    sort_unique(out);
}

namespace {

// ---- planner state helpers -------------------------------------------------------------------------------------
void p_mark_root(cv_lod* L, const NodeKey& k) {
    if (L->p_root_level < 0 || k.level > L->p_root_level) return;
    L->p_dirty_roots.insert(foot_at(foot_of(k), L->p_root_level));
}

void p_mark_stale(cv_lod* L, const NodeKey& k) {
    auto it = L->states.find(k);
    if (it == L->states.end()) return;
    ++it->second.want;
    p_mark_root(L, k);
}

void p_sent_refs(cv_lod* L, const Group& g, int32_t delta) {
    for (const NodeKey& k : g.nodes) {
        auto it = L->states.find(k);
        if (it != L->states.end()) it->second.sent += delta;
    }
}

void p_forget_sent(cv_lod* L, const FootKey& r) {
    auto it = L->p_sent.find(r);
    if (it == L->p_sent.end()) return;
    p_sent_refs(L, it->second.g, -1);
    L->p_sent.erase(it);
}

// Re-publishes the built state of a stack (StackTable) after its node states moved.
void p_publish_stack(cv_lod* L, const FootKey& f) {
    int64_t lo, hi;
    stack_range(L, f.level, &lo, &hi);
    auto st = std::make_shared<Stack>();
    for (int64_t ny = lo; ny <= hi; ++ny) {
        auto it = L->states.find(NodeKey{f.level, f.x, ny, f.z});
        if (it == L->states.end() || !it->second.known || !it->second.complete || !it->second.mesh) {
            L->stacks.put(f, nullptr);
            return;
        }
        if (!it->second.mesh->empty()) {
            st->nodes.push_back(it->first);
            st->meshes.push_back(it->second.mesh);
        }
    }
    L->stacks.put(f, std::move(st));
}

void p_set_ready(cv_lod* L, int64_t cx, int64_t cz, bool ready) {
    const FootKey col{0, cx, cz};
    const bool had = L->p_ready.count(col) != 0;
    if (had == ready) return;
    if (ready) L->p_ready.insert(col);
    else L->p_ready.erase(col);
    for (int lv = 0; lv <= L->max_level; ++lv) {
        const FootKey f{lv, cx >> lv, cz >> lv};
        int64_t& c = L->p_ready_count[f];
        c += ready ? 1 : -1;
        if (c == 0) L->p_ready_count.erase(f);
    }
    if (L->p_root_level >= 0) {
        const FootKey r = foot_at(col, L->p_root_level);
        L->p_dirty_roots.insert(r);
        p_forget_sent(L, r);   // the owner patched that root: its cover is no longer what was sent
    }
}

// Selects one root and queues its commit when it differs from what was sent.
void p_select_root(cv_lod* L, Selector& S, const FootKey& r, std::vector<CommitPtr>& out) {
    L->candidates.clear();
    Group g;
    S.cover(r, g);
    sort_unique(g.nodes);
    sort_unique(g.fallbacks);
    sort_unique(g.stacks);
    L->p_root_epoch[r] = L->epoch;
    if (L->candidates.empty()) L->p_cands.erase(r);
    else L->p_cands[r] = L->candidates;
    std::vector<MeshPtr> meshes;
    meshes.reserve(g.nodes.size());
    for (const NodeKey& k : g.nodes) meshes.push_back(L->states[k].mesh);
    auto sit = L->p_sent.find(r);
    if (sit != L->p_sent.end() && sit->second.g.nodes == g.nodes && sit->second.g.fallbacks == g.fallbacks &&
        sit->second.g.stacks == g.stacks && sit->second.meshes == meshes) {
        return;
    }
    if (sit == L->p_sent.end() && g.empty()) {
        // Nothing sent and nothing wanted: still tell the owner (it may hold its own patches / gap fills there).
        auto c = std::make_shared<Commit>();
        c->root = r;
        c->seq = L->p_seq;
        c->view_version = L->p_view_version;
        c->root_level = L->p_root_level;
        c->d = S.dmin(r);
        c->viewers = L->p_viewers;
        c->radius = L->p_policy.radius;
        out.push_back(std::move(c));
        SentGroup sg;
        L->p_sent.emplace(r, std::move(sg));
        return;
    }
    p_forget_sent(L, r);
    auto c = std::make_shared<Commit>();
    c->root = r;
    c->seq = L->p_seq;
    c->view_version = L->p_view_version;
    c->root_level = L->p_root_level;
    c->d = S.dmin(r);
    c->g = g;
    c->meshes = meshes;
    c->viewers = L->p_viewers;
    c->radius = L->p_policy.radius;
    out.push_back(c);
    SentGroup sg;
    sg.g = std::move(g);
    sg.meshes = std::move(meshes);
    p_sent_refs(L, sg.g, +1);
    L->p_sent.emplace(r, std::move(sg));
}

void p_prune(cv_lod* L, const std::unordered_set<FootKey, FootKeyHash>& in_range) {
    int64_t standby = 0, bytes = 0;
    for (auto it = L->states.begin(); it != L->states.end();) {
        const NodeKey& k = it->first;
        const NodeState& s = it->second;
        bool drop = false;
        if (s.sent == 0) {
            if (L->p_root_level < 0 || k.level > L->p_root_level) {
                drop = true;
            } else {
                const FootKey r = foot_at(foot_of(k), L->p_root_level);
                auto e = L->p_root_epoch.find(r);
                drop = in_range.count(r) == 0 || e == L->p_root_epoch.end() || s.touched < e->second;
            }
        }
        if (drop) {
            L->p_feet_touched.insert(foot_of(k));
            it = L->states.erase(it);
            continue;
        }
        if (s.mesh) {
            bytes += s.mesh->bytes();
            if (s.sent == 0 && !s.mesh->empty()) ++standby;
        }
        ++it;
    }
    for (auto it = L->p_root_epoch.begin(); it != L->p_root_epoch.end();) {
        if (in_range.count(it->first) == 0) it = L->p_root_epoch.erase(it);
        else ++it;
    }
    L->p_standby.store(standby);
    L->p_states.store(static_cast<int64_t>(L->states.size()));
    L->p_resident_bytes.store(bytes);
}

// Standby / resident counters (stats only).
void p_count(cv_lod* L) {
    int64_t standby = 0, bytes = 0;
    for (const auto& [k, s] : L->states) {
        if (!s.mesh) continue;
        bytes += s.mesh->bytes();
        if (s.sent == 0 && !s.mesh->empty()) ++standby;
    }
    L->p_standby.store(standby);
    L->p_states.store(static_cast<int64_t>(L->states.size()));
    L->p_resident_bytes.store(bytes);
}

} // namespace

void planner_main(cv_lod* L) {
    std::vector<PrepResult> results;
    std::vector<NodeKey> changes;
    std::vector<ReadyChange> readies;
    std::vector<FootKey> touched;
    std::vector<CommitPtr> out;
    std::vector<FootKey> roots;
    std::unordered_set<FootKey, FootKeyHash> in_range;
    std::unique_lock<std::mutex> lk(L->m);
    for (;;) {
        L->plan_cv.wait_for(lk, std::chrono::milliseconds(200), [&] { return L->stopping || L->plan_wake; });
        if (L->stopping) return;
        const bool timer = !L->plan_wake;
        L->plan_wake = false;
        L->plan_busy = true;
        results.clear();
        changes.clear();
        readies.clear();
        touched.clear();
        results.swap(L->done);
        changes.swap(L->changed);
        readies.swap(L->ready_in);
        touched.swap(L->owner_touched);
        const bool overflow = L->changed_overflow;
        L->changed_overflow = false;
        lk.unlock();

        const int64_t t0 = now_us();
        ++L->epoch;
        // View / policy.
        bool all_dirty = false;
        {
            std::lock_guard<std::mutex> g(L->view_m);
            if (L->view_version != L->p_view_version) {
                L->p_view_version = L->view_version;
                L->p_viewers = L->viewers;
                L->p_policy = L->policy;
                L->p_have_view = true;
                all_dirty = true;
            }
        }
        const int32_t rl = L->p_have_view && !L->p_viewers.empty() ? L->p_policy.max_level : -1;
        if (rl != L->p_root_level) {
            for (auto& [r, sg] : L->p_sent) p_sent_refs(L, sg.g, -1);
            L->p_sent.clear();
            L->p_cands.clear();
            L->p_dirty_roots.clear();
            L->p_root_epoch.clear();
            L->p_root_level = rl;
            all_dirty = true;
        }
        if (overflow) {
            for (auto& [k, st] : L->states) ++st.want;
            all_dirty = true;
        }
        for (const NodeKey& k : changes) {
            p_mark_stale(L, k);
            for (int f = 0; f < 6; ++f) p_mark_stale(L, neighbour(k, f));
        }
        for (PrepResult& r : results) {
            auto it = L->states.find(r.key);
            if (it == L->states.end()) continue;
            NodeState& s = it->second;
            if (s.inflight == r.serial) s.inflight = 0;
            if (r.serial < s.have) continue;   // older than what we have
            s.have = r.serial;
            s.known = true;
            s.complete = r.complete;
            s.digest = r.digest;
            if (!r.complete) {
                s.mesh.reset();
            } else if (!s.mesh || !r.mesh || s.mesh->input_hash != r.mesh->input_hash) {
                s.mesh = std::move(r.mesh);
            }
            L->p_feet_touched.insert(foot_of(r.key));
            p_mark_root(L, r.key);
        }
        for (const ReadyChange& c : readies) {
            p_set_ready(L, c.cx, c.cz, c.ready);
            L->p_seq = c.seq;
        }
        for (const FootKey& r : touched) {
            if (r.level != L->p_root_level) continue;
            p_forget_sent(L, r);
            L->p_dirty_roots.insert(r);
        }
        for (const FootKey& f : L->p_feet_touched) p_publish_stack(L, f);
        L->p_feet_touched.clear();

        // Selection of the dirty roots (all of them after a view change), then roots that left the range.
        out.clear();
        bool selected = false;
        if (L->p_root_level >= 0) {
            roots_in_range(L->p_viewers, L->p_policy, roots);
            in_range.clear();
            in_range.insert(roots.begin(), roots.end());
            Selector S{L, L->p_policy, static_cast<double>(L->p_policy.blocks_per_cell), incomplete_min_level(L->p_policy)};
            for (const FootKey& r : roots) {
                if (!all_dirty && L->p_dirty_roots.count(r) == 0) continue;
                p_select_root(L, S, r, out);
                selected = true;
            }
            std::vector<FootKey> gone;
            for (auto& [r, sg] : L->p_sent) {
                if (in_range.count(r) == 0) gone.push_back(r);
            }
            for (const FootKey& r : gone) {
                p_forget_sent(L, r);
                auto c = std::make_shared<Commit>();
                c->root = r;
                c->seq = L->p_seq;
                c->view_version = L->p_view_version;
                c->root_level = L->p_root_level;
                c->d = foot_dmin(r, L->p_viewers);
                c->viewers = L->p_viewers;
                c->radius = L->p_policy.radius;
                out.push_back(std::move(c));
            }
            for (auto it = L->p_cands.begin(); it != L->p_cands.end();) {
                if (in_range.count(it->first) == 0) it = L->p_cands.erase(it);
                else ++it;
            }
        } else {
            in_range.clear();
            L->p_cands.clear();
        }
        L->p_dirty_roots.clear();
        const int64_t now = now_us();
        if (selected || timer || now - L->p_last_prune_us > 250000) {
            if (now - L->p_last_prune_us > 100000 || L->p_root_level < 0) {
                p_prune(L, in_range);
                for (const FootKey& f : L->p_feet_touched) p_publish_stack(L, f);
                L->p_feet_touched.clear();
                L->p_last_prune_us = now;
            }
        }
        // Requests -> the prepare queue (nearest first, finer first on ties); jobs no longer wanted are dropped.
        std::vector<Candidate> all;
        if (selected) {
            for (auto& [r, v] : L->p_cands) all.insert(all.end(), v.begin(), v.end());
            std::sort(all.begin(), all.end(), [](const Candidate& a, const Candidate& b) {
                if (a.d != b.d) return a.d < b.d;
                return a.key < b.key;
            });
        }
        if (selected) L->p_select_us.store(now_us() - t0);

        lk.lock();
        if (selected) {
            for (const PrepJob& j : L->prep_q) {
                auto it = L->states.find(j.key);
                if (it != L->states.end() && it->second.inflight == j.serial) it->second.inflight = 0;
            }
            L->prep_q.clear();
            for (const Candidate& c : all) {
                auto it = L->states.find(c.key);
                if (it == L->states.end()) continue;
                NodeState& s = it->second;
                if (!s.stale() || s.inflight == s.want) continue;   // fresh, queued (this loop) or running
                s.inflight = s.want;
                L->prep_q.push_back(PrepJob{c.key, s.want});
            }
            if (!L->prep_q.empty()) L->work_cv.notify_all();
        }
        for (CommitPtr& c : out) L->commits[c->root] = std::move(c);
        L->planner_seq = L->p_seq;
        if (!L->plan_wake && (selected || !results.empty())) {   // going idle: refresh the counters
            lk.unlock();
            p_count(L);
            lk.lock();
        }
        L->plan_busy = false;
        L->idle_cv.notify_all();
    }
}

void wake_planner_locked(cv_lod* L) {
    L->plan_wake = true;
    L->plan_cv.notify_one();
}

} // namespace cenda::voxel::lod
