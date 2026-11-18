// Octree LOD runtime: the owner's drawn cover (readiness patches, gap fills, commits applied within budget),
// cv_lod_tick and its net events. Shared runtime structures: lod_runtime.hpp.
#include "lod_runtime.hpp"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

namespace cenda::voxel::lod {

namespace {

// ---- owner: the drawn cover ------------------------------------------------------------------------------------
// The owner keeps a VALID cover at the end of every tick (every column within the radius covered exactly once by
// READY detail, a drawn stack or a fallback; nothing drawn over a READY column), whatever the planner is doing:
//  - a readiness change is patched at once (ensure_column): a column turning READY splits the footprint drawn
//    over it into its other parts (each a built stack if one is published, else a FALLBACK); a column turning
//    UNREADY gets its level-0 stack (standby) or a level-0 FALLBACK;
//  - a view change fills what the new radius uncovered (gap) with root-level stacks / fallbacks;
//  - a commit replaces its root's cover, then readiness changes newer than the commit are re-patched and the
//    radius is re-filled if the view moved since.
// Events are the NET change of the tick: SHOW / FALLBACK_ON, then UPDATE, then HIDE / FALLBACK_OFF.
void o_record_node(cv_lod* L, const NodeKey& k) {
    if (L->tick_nodes.count(k) != 0) return;
    TickNode t;
    auto it = L->node_mesh.find(k);
    if (it != L->node_mesh.end()) {
        t.was_drawn = true;
        t.was_mesh = it->second;
    }
    L->tick_nodes.emplace(k, std::move(t));
}

void o_record_fb(cv_lod* L, const FootKey& f, bool was_on) {
    L->tick_fb.emplace(f, was_on);   // first record wins
}

void o_below(cv_lod* L, const FootKey& f, int32_t delta) {
    for (int32_t lv = f.level + 1; lv <= L->groups_level; ++lv) {
        const FootKey a = foot_at(f, lv);
        int32_t& c = L->below[a];
        c += delta;
        if (c == 0) L->below.erase(a);
    }
}

void o_remove_foot(cv_lod* L, const FootKey& f) {
    auto it = L->drawn.find(f);
    if (it == L->drawn.end()) return;
    if (it->second.fallback) {
        o_record_fb(L, f, true);
        --L->o_fallbacks;
    }
    for (const NodeKey& k : it->second.nodes) {
        o_record_node(L, k);
        auto m = L->node_mesh.find(k);
        if (m != L->node_mesh.end()) {
            L->o_drawn_bytes -= m->second ? m->second->bytes() : 0;
            --L->o_drawn_nodes;
            L->node_mesh.erase(m);
        }
    }
    L->drawn.erase(it);
    o_below(L, f, -1);
    const FootKey r = foot_at(f, L->groups_level);
    auto rf = L->root_feet.find(r);
    if (rf != L->root_feet.end()) {
        rf->second.erase(f);
        if (rf->second.empty()) L->root_feet.erase(rf);
    }
}

void o_add_fallback(cv_lod* L, const FootKey& f) {
    if (L->drawn.count(f) != 0) return;
    DrawnFoot d;
    d.fallback = true;
    L->drawn.emplace(f, std::move(d));
    o_record_fb(L, f, false);
    ++L->o_fallbacks;
    o_below(L, f, +1);
    L->root_feet[foot_at(f, L->groups_level)].insert(f);
}

void o_add_stack(cv_lod* L, const FootKey& f, const NodeKey* nodes, const MeshPtr* meshes, size_t n) {
    if (L->drawn.count(f) != 0) return;
    DrawnFoot d;
    d.nodes.assign(nodes, nodes + n);
    for (size_t i = 0; i < n; ++i) {
        o_record_node(L, nodes[i]);
        L->node_mesh[nodes[i]] = meshes[i];
        L->o_drawn_bytes += meshes[i] ? meshes[i]->bytes() : 0;
        ++L->o_drawn_nodes;
    }
    L->drawn.emplace(f, std::move(d));
    o_below(L, f, +1);
    L->root_feet[foot_at(f, L->groups_level)].insert(f);
}

int64_t o_ready(const cv_lod* L, const FootKey& f) {
    auto it = L->ready_count.find(f);
    return it == L->ready_count.end() ? 0 : it->second;
}

// Covers F's non-ready columns within the radius (nothing of F is drawn): published stacks, else FALLBACK.
void o_min_cover(cv_lod* L, const FootKey& f) {
    if (foot_dmin(f, L->o_viewers) > static_cast<double>(L->o_policy.radius)) return;
    const int64_t rc = o_ready(L, f);
    if (rc == foot_total(f.level)) return;
    if (rc == 0) {
        if (StackPtr st = L->stacks.get(f)) {
            o_add_stack(L, f, st->nodes.data(), st->meshes.data(), st->nodes.size());
        } else {
            o_add_fallback(L, f);
        }
        return;
    }
    for (int i = 0; i < 4; ++i) o_min_cover(L, foot_child(f, i));
}

const FootKey* o_cover_of(cv_lod* L, int64_t cx, int64_t cz, FootKey& tmp) {
    for (int32_t lv = 0; lv <= L->groups_level; ++lv) {
        tmp = FootKey{lv, cx >> lv, cz >> lv};
        if (L->drawn.count(tmp) != 0) return &tmp;
    }
    return nullptr;
}

// Makes the cover right for one column's current readiness (idempotent).
void o_ensure_column(cv_lod* L, int64_t cx, int64_t cz) {
    if (!L->have_view || L->groups_level < 0) return;
    FootKey tmp;
    const FootKey* cov = o_cover_of(L, cx, cz, tmp);
    const bool is_ready = L->ready.count(FootKey{0, cx, cz}) != 0;
    if (is_ready) {
        if (cov == nullptr) return;
        const FootKey f = *cov;
        o_remove_foot(L, f);
        // Re-cover F minus the column path: at each level the three siblings of the path.
        for (int32_t lv = f.level; lv >= 1; --lv) {
            const FootKey p{lv, cx >> lv, cz >> lv};
            const FootKey path{lv - 1, cx >> (lv - 1), cz >> (lv - 1)};
            for (int i = 0; i < 4; ++i) {
                const FootKey c = foot_child(p, i);
                if (!(c == path)) o_min_cover(L, c);
            }
        }
        return;
    }
    if (cov != nullptr) return;
    // The column is not covered: is an ancestor area partly covered? Then only the column itself is missing.
    o_min_cover(L, FootKey{0, cx, cz});
}

// Fills the parts of F within the radius that nothing covers. `ref`: a view F is known to be covered for (whole
// footprints inside its radius are skipped); null: no such view.
void o_gap(cv_lod* L, const FootKey& f, const std::vector<cv_lod_viewer>* ref, int32_t ref_radius) {
    if (foot_dmin(f, L->o_viewers) > static_cast<double>(L->o_policy.radius)) return;
    if (o_ready(L, f) == foot_total(f.level)) return;
    if (L->drawn.count(f) != 0) return;
    if (ref != nullptr && !ref->empty() && foot_dmax(f, *ref) <= static_cast<double>(ref_radius)) return;
    if (L->below.count(f) == 0) {
        o_min_cover(L, f);
        return;
    }
    for (int i = 0; i < 4; ++i) o_gap(L, foot_child(f, i), ref, ref_radius);
}

void o_clear_all(cv_lod* L) {
    std::vector<FootKey> all;
    all.reserve(L->drawn.size());
    for (const auto& [f, d] : L->drawn) all.push_back(f);
    for (const FootKey& f : all) o_remove_foot(L, f);
    L->root_feet.clear();
    L->below.clear();
}

// Replaces root r's cover with the commit's. Returns false when the commit is refused (stale view / root level).
bool o_apply_commit(cv_lod* L, const Commit& c, std::vector<FootKey>& touched) {
    if (c.view_version < L->min_view_version || c.root_level != L->groups_level || !L->have_view) return false;
    auto rf = L->root_feet.find(c.root);
    if (rf != L->root_feet.end()) {
        std::vector<FootKey> old(rf->second.begin(), rf->second.end());
        for (const FootKey& f : old) o_remove_foot(L, f);
    }
    // Nodes grouped per stack footprint (nodes are sorted by (level, x, y, z), so the same x interleaves z).
    std::vector<size_t> idx(c.g.nodes.size());
    for (size_t i = 0; i < idx.size(); ++i) idx[i] = i;
    std::sort(idx.begin(), idx.end(), [&](size_t a, size_t b) {
        const FootKey fa = foot_of(c.g.nodes[a]), fb = foot_of(c.g.nodes[b]);
        if (!(fa == fb)) return fa < fb;
        return c.g.nodes[a].y < c.g.nodes[b].y;
    });
    std::vector<NodeKey> nodes;
    std::vector<MeshPtr> meshes;
    size_t ni = 0;
    for (const FootKey& f : c.g.stacks) {
        while (ni < idx.size() && foot_of(c.g.nodes[idx[ni]]) < f) ++ni;
        nodes.clear();
        meshes.clear();
        while (ni < idx.size() && foot_of(c.g.nodes[idx[ni]]) == f) {
            nodes.push_back(c.g.nodes[idx[ni]]);
            meshes.push_back(c.meshes[idx[ni]]);
            ++ni;
        }
        o_add_stack(L, f, nodes.data(), meshes.data(), nodes.size());
    }
    for (const FootKey& f : c.g.fallbacks) o_add_fallback(L, f);
    // Readiness the selection did not see yet, then the radius of the current view.
    bool patched = false;
    for (const ReadyChange& rc : L->ready_log) {
        if (rc.seq <= c.seq) continue;
        if (!(foot_at(FootKey{0, rc.cx, rc.cz}, L->groups_level) == c.root)) continue;
        o_ensure_column(L, rc.cx, rc.cz);
        patched = true;
    }
    if (c.view_version != L->o_view_version) {
        const size_t before = L->drawn.size();
        o_gap(L, c.root, &c.viewers, c.radius);
        patched |= L->drawn.size() != before;
    }
    if (patched) touched.push_back(c.root);
    return true;
}

} // namespace

void slot_view(const MeshBuild& m, cv_lod_mesh& v) {
    std::memset(&v, 0, sizeof(v));
    v.level = m.key.level;
    v.cell_size = 1 << m.key.level;
    v.nx = m.key.x;
    v.ny = m.key.y;
    v.nz = m.key.z;
    v.origin[0] = node_origin(m.key.x, m.key.level);
    v.origin[1] = node_origin(m.key.y, m.key.level);
    v.origin[2] = node_origin(m.key.z, m.key.level);
    v.quads = m.quads.empty() ? nullptr : m.quads.data();
    v.quad_count = m.quad_count;
    v.water = m.water.empty() ? nullptr : m.water.data();
    v.water_count = m.water_count;
    for (int a = 0; a < 3; ++a) {
        v.aabb_min[a] = m.aabb_min[a];
        v.aabb_max[a] = m.aabb_max[a];
    }
    v.digest = m.digest;
    v.flags = m.empty() ? 1u : 0u;
}

namespace {

int32_t hand_out(cv_lod* L, const MeshPtr& m) {
    L->slots.push_back(m);
    return static_cast<int32_t>(L->slots.size() - 1);
}

void push_event(cv_lod* L, int32_t type, int32_t mesh, int32_t level, int64_t x, int64_t y, int64_t z) {
    cv_lod_event e{};
    e.type = type;
    e.mesh = mesh;
    e.level = level;
    e.x = x;
    e.y = y;
    e.z = z;
    L->events.push_back(e);
}

// The tick's net events (deterministic order inside each class).
void o_emit(cv_lod* L) {
    std::vector<std::pair<NodeKey, const TickNode*>> nodes;
    nodes.reserve(L->tick_nodes.size());
    for (const auto& [k, t] : L->tick_nodes) nodes.emplace_back(k, &t);
    std::sort(nodes.begin(), nodes.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
    std::vector<std::pair<FootKey, bool>> fbs(L->tick_fb.begin(), L->tick_fb.end());
    std::sort(fbs.begin(), fbs.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
    for (const auto& [k, t] : nodes) {
        auto it = L->node_mesh.find(k);
        if (!t->was_drawn && it != L->node_mesh.end()) {
            push_event(L, CV_LOD_EV_SHOW, hand_out(L, it->second), k.level, k.x, k.y, k.z);
        }
    }
    for (const auto& [f, was_on] : fbs) {
        if (!was_on && L->drawn.count(f) != 0) push_event(L, CV_LOD_EV_FALLBACK_ON, -1, f.level, f.x, 0, f.z);
    }
    for (const auto& [k, t] : nodes) {
        auto it = L->node_mesh.find(k);
        if (t->was_drawn && it != L->node_mesh.end() && it->second != t->was_mesh) {
            push_event(L, CV_LOD_EV_UPDATE, hand_out(L, it->second), k.level, k.x, k.y, k.z);
        }
    }
    for (const auto& [k, t] : nodes) {
        if (t->was_drawn && L->node_mesh.count(k) == 0) push_event(L, CV_LOD_EV_HIDE, -1, k.level, k.x, k.y, k.z);
    }
    for (const auto& [f, was_on] : fbs) {
        if (was_on) {
            auto it = L->drawn.find(f);
            if (it == L->drawn.end() || !it->second.fallback) push_event(L, CV_LOD_EV_FALLBACK_OFF, -1, f.level, f.x, 0, f.z);
        }
    }
    L->tick_nodes.clear();
    L->tick_fb.clear();
}

} // namespace

void set_ready(cv_lod* L, int64_t cx, int64_t cz, bool ready) {
    const FootKey col{0, cx, cz};
    const bool had = L->ready.count(col) != 0;
    if (had == ready) return;
    if (ready) L->ready.insert(col);
    else L->ready.erase(col);
    for (int lv = 0; lv <= L->max_level; ++lv) {
        const FootKey f{lv, cx >> lv, cz >> lv};
        int64_t& c = L->ready_count[f];
        c += ready ? 1 : -1;
        if (c == 0) L->ready_count.erase(f);
    }
    const ReadyChange rc{++L->ready_seq, cx, cz, ready};
    L->ready_log.push_back(rc);
    L->ready_todo.emplace_back(cx, cz);
    std::lock_guard<std::mutex> g(L->m);
    if (ready) {
        L->ready_cols.insert(std::make_pair(cx, cz));
        release_held_locked(L, std::make_pair(cx, cz));
    } else {
        L->ready_cols.erase(std::make_pair(cx, cz));
    }
    L->ready_in.push_back(rc);
    wake_planner_locked(L);
}

namespace {

void fill_stats(cv_lod* L, int64_t tick_us) {
    cv_lod_stats st{};
    st.nodes_cached = static_cast<int64_t>(L->cache.count() + L->cache0.count());
    st.cache_bytes = static_cast<int64_t>(L->cache.bytes() + L->cache0.bytes());
    {
        std::lock_guard<std::mutex> g(L->m);
        for (int lv = 1; lv < kLevels; ++lv) st.nodes_dirty += static_cast<int64_t>(L->dirty[lv].size());
        st.nodes_dirty += L->held_cubes;
        st.jobs_queued = static_cast<int64_t>(L->prep_q.size() + L->col_q.size() + L->commits.size() + L->pending.size());
        st.jobs_running = static_cast<int64_t>(L->running.size()) + L->prep_running + L->col_running;
        if (L->plan_busy || L->plan_wake || !L->done.empty() || !L->changed.empty() || !L->ready_in.empty() ||
            !L->owner_touched.empty()) {
            ++st.jobs_running;
        }
    }
    st.nodes_built_total = L->built_total.load();
    st.nodes_loaded_total = L->loaded_total.load();
    st.nodes_saved_total = L->saved_total.load();
    st.meshes_built_total = L->meshes_total.load();
    st.drawn_nodes = L->o_drawn_nodes;
    st.drawn_bytes = L->o_drawn_bytes;
    st.fallback_footprints = L->o_fallbacks;
    st.standby_nodes = L->p_standby.load();
    st.ready_columns = static_cast<int64_t>(L->ready.size());
    st.select_us_last = L->p_select_us.load();
    st.tick_us_last = tick_us;
    L->tick_us_max = std::max(L->tick_us_max, tick_us);
    st.tick_us_max = L->tick_us_max;
    st.errors_total = L->errors_total.load();
    st.corrupt_total = L->corrupt_total.load();
    st.resident_mesh_bytes = L->p_resident_bytes.load();
    st.planner_states = L->p_states.load();
    std::lock_guard<std::mutex> g(L->stats_m);
    L->o_stats = st;
}

} // namespace

} // namespace cenda::voxel::lod

using namespace cenda::voxel::lod;

extern "C" {

int32_t cv_lod_tick(cv_lod* lod, const cv_lod_budget* budget, const cv_lod_event** out_events) {
    if (lod == nullptr || out_events == nullptr) return fail_arg("cv_lod_tick: bad arguments");
    const int64_t t0 = now_us();
    cv_lod* L = lod;
    L->events.clear();
    L->slots.clear();
    L->slot_views.clear();
    std::vector<FootKey> touched;
    // 1. The view: no viewers -> nothing drawn; a new root level -> regroup; else fill what the radius uncovered.
    {
        std::vector<cv_lod_viewer> nv;
        cv_lod_policy np{};
        uint64_t vv = 0;
        bool changed = false;
        {
            std::lock_guard<std::mutex> g(L->view_m);
            if (L->view_version != L->o_view_version) {
                nv = L->viewers;
                np = L->policy;
                vv = L->view_version;
                changed = true;
            }
        }
        if (changed) {
            const std::vector<cv_lod_viewer> old_viewers = L->o_viewers;
            const int32_t old_radius = L->o_policy.radius;
            const bool had_view = L->have_view;
            L->o_viewers = std::move(nv);
            L->o_policy = np;
            L->o_view_version = vv;
            if (L->o_viewers.empty()) {
                o_clear_all(L);
                L->have_view = false;
                L->groups_level = -1;
                L->pending.clear();
                L->min_view_version = vv;
            } else if (!had_view || L->groups_level != np.max_level) {
                o_clear_all(L);
                L->have_view = true;
                L->groups_level = np.max_level;
                L->pending.clear();
                L->min_view_version = vv;
                std::vector<FootKey> roots;
                roots_in_range(L->o_viewers, L->o_policy, roots);
                for (const FootKey& r : roots) o_gap(L, r, nullptr, 0);
            } else {
                // Roots beyond the radius go; the rest only where the old radius did not reach.
                std::vector<FootKey> gone;
                for (const auto& [r, feet] : L->root_feet) {
                    if (foot_dmin(r, L->o_viewers) > static_cast<double>(np.radius)) gone.push_back(r);
                }
                for (const FootKey& r : gone) {
                    auto rf = L->root_feet.find(r);
                    if (rf == L->root_feet.end()) continue;
                    std::vector<FootKey> feet(rf->second.begin(), rf->second.end());
                    for (const FootKey& f : feet) o_remove_foot(L, f);
                    touched.push_back(r);
                }
                std::vector<FootKey> roots;
                roots_in_range(L->o_viewers, L->o_policy, roots);
                for (const FootKey& r : roots) {
                    const size_t before = L->drawn.size();
                    o_gap(L, r, &old_viewers, old_radius);
                    if (L->drawn.size() != before) touched.push_back(r);
                }
            }
        }
    }
    // 2. Readiness changes since the last tick: patched now (same tick, whatever the planner does).
    for (const auto& [cx, cz] : L->ready_todo) o_ensure_column(L, cx, cz);
    L->ready_todo.clear();
    // 3. Prepared commits, nearest first, within the mesh and time budgets (at least one per tick).
    uint64_t planner_seq = 0;
    {
        std::lock_guard<std::mutex> g(L->m);
        for (auto& [r, c] : L->commits) L->pending[r] = std::move(c);
        L->commits.clear();
        planner_seq = L->planner_seq;
    }
    if (!L->pending.empty()) {
        std::vector<std::pair<double, FootKey>> order;
        order.reserve(L->pending.size());
        for (const auto& [r, c] : L->pending) order.emplace_back(c->d, r);
        std::sort(order.begin(), order.end());
        const int64_t limit = budget != nullptr && budget->max_mesh_bytes > 0 ? budget->max_mesh_bytes : INT64_MAX;
        constexpr int64_t kTimeBudgetUs = 300;
        int64_t spent = 0;
        int applied = 0;
        for (const auto& [d, r] : order) {
            auto it = L->pending.find(r);
            const Commit& c = *it->second;
            int64_t cost = 0;
            for (size_t i = 0; i < c.g.nodes.size(); ++i) {
                auto m = L->node_mesh.find(c.g.nodes[i]);
                if (m == L->node_mesh.end() || m->second != c.meshes[i]) cost += c.meshes[i] ? c.meshes[i]->bytes() : 0;
            }
            if (applied > 0 && (spent + cost > limit || now_us() - t0 > kTimeBudgetUs)) break;
            spent += cost;
            if (!o_apply_commit(L, c, touched)) touched.push_back(r);
            L->pending.erase(it);
            ++applied;
        }
    }
    // 4. Readiness log: keep what an outstanding or future commit may still predate.
    {
        uint64_t keep_after = planner_seq;
        for (const auto& [r, c] : L->pending) keep_after = std::min(keep_after, c->seq);
        while (!L->ready_log.empty() && L->ready_log.front().seq <= keep_after) L->ready_log.pop_front();
    }
    if (!touched.empty()) {
        sort_unique(touched);
        std::lock_guard<std::mutex> g(L->m);
        L->owner_touched.insert(L->owner_touched.end(), touched.begin(), touched.end());
        wake_planner_locked(L);
    }
    o_emit(L);
    L->slot_views.resize(L->slots.size());
    for (size_t i = 0; i < L->slots.size(); ++i) slot_view(*L->slots[i], L->slot_views[i]);
    fill_stats(L, now_us() - t0);
    *out_events = L->events.empty() ? nullptr : L->events.data();
    return static_cast<int32_t>(L->events.size());
}

} // extern "C"
