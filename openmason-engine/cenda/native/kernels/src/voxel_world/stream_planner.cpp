// The streamer's planner thread (stream_plan.hpp Planner; its TOP requests run on the workers) and the owner's
// application of the plan batches it hands over (column changes, cube levels, the load list), under a budget.
#include "stream_impl.hpp"
#include "stream_internal.hpp"
#include "stream_plan.hpp"

#include <algorithm>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <utility>
#include <vector>

namespace cenda::voxel::streaming {

namespace {

constexpr int64_t kApplyBudgetUs = 250;     // plan diff application per tick
constexpr int64_t kMinPlanIntervalMs = 25;
constexpr int64_t kTopsPlanIntervalMs = 8;  // re-plan this soon when TOPs arrive for columns a plan left out
constexpr int64_t kWalkIntervalMs = 200;    // rule (c) walk cadence

} // namespace

// ---- planner thread ---------------------------------------------------------------------------------------

void planner_main(cv_stream* s) {
    Planner planner(s->world, s->gen, s->remote);
    // TOPs only the surface callback can answer are resolved on the workers, nearest first and in one
    // distance order with generation (worker_main); the planner plans the columns already known and re-plans
    // as answers arrive.
    planner.set_async([s](const std::vector<LoadItem>& cols) {
        std::vector<Job> jobs;
        jobs.reserve(cols.size());
        for (const LoadItem& k : cols) {
            Job j;
            j.kind = JobKind::Top;
            j.c = k.c;
            j.key = k.key;
            jobs.push_back(std::move(j));
        }
        s->tops_outstanding.fetch_add(static_cast<int64_t>(cols.size()));
        submit_all(s, jobs);
    });
    int64_t last_plan = INT64_MIN / 2, last_walk = INT64_MIN / 2;
    bool partial = false;   // the last plan left columns out for want of their TOP
    std::vector<std::pair<Int2, TopInfo>> got;
    std::unique_lock<std::mutex> lk(s->plan_mutex);
    for (;;) {
        if (s->plan_stop) return;
        const int64_t now = now_ms();
        const bool backlog = s->batches.size() >= 2;
        const bool centers_changed = s->centers_version != s->planned_version;
        const bool tops_arrived = !s->top_results.empty();
        // The walk waits for every TOP it may reach (it would ask the callback on this thread).
        const bool walk_due = !s->centers.empty() && now - last_walk >= kWalkIntervalMs;
        const bool walk_ready = walk_due && planner.tops_pending() == 0;
        const bool want = centers_changed || s->tops_dirty || tops_arrived || walk_ready;
        const int64_t interval = tops_arrived && partial ? kTopsPlanIntervalMs : kMinPlanIntervalMs;
        if (want && !backlog && now - last_plan >= interval) {
            std::vector<cv_center> snap = s->centers;
            s->planned_version = s->centers_version;
            s->tops_dirty = false;
            s->planning = true;
            got.clear();
            got.swap(s->top_results);
            lk.unlock();
            for (const auto& [k, t] : got) planner.add_resolved(k, t);
            s->tops_outstanding.fetch_sub(static_cast<int64_t>(got.size()));
            auto batch = std::make_unique<PlanBatch>();
            planner.compute(snap, now, walk_due, *batch);
            partial = batch->tops_pending > 0 || planner.tops_pending() > 0;
            lk.lock();
            s->planning = false;
            if (batch->walked) last_walk = now;
            last_plan = now;
            ++s->plans_total;
            s->plan_us_last = batch->plan_us;
            s->batches.push_back(std::move(batch));
            continue;
        }
        int64_t wait = 50;
        if (want && !backlog) wait = std::max<int64_t>(1, interval - (now - last_plan));
        if (!s->centers.empty() && !walk_due) wait = std::min(wait, std::max<int64_t>(1, kWalkIntervalMs - (now - last_walk)));
        s->plan_cv.wait_for(lk, std::chrono::milliseconds(wait));
    }
}

// ---- plan application -----------------------------------------------------------------------------------------

namespace {

void apply_column_change(cv_stream* s, const ColumnChange& cc) {
    ColumnState& col = s->columns[cc.c];
    col.tracked = cc.tracked;
    col.has_band = cc.has_band;
    col.band_lo = cc.band_lo;
    col.band_hi = cc.band_hi;
    if (col.tracked) request_column(s, cc.c, col);
    touch(s, cc.c.x, cc.c.z);
}

void apply_level(cv_stream* s, const Int3& c, uint8_t lv) {
    auto it = s->cubes.find(c);
    if (it == s->cubes.end() && lv == kLevelNone) return;
    CubeState& st = it == s->cubes.end() ? get_cube(s, c) : it->second;
    const uint8_t old = st.level;
    if (old == lv) return;
    st.level = lv;
    ++s->level_changes;
    touch(s, c.x, c.z);
    if (lv == kLevelWanted) {
        add_origin_candidates(s, c);
        if (st.phase == kInstalled) consider_mesh(s, c, st);
    }
    if (lv == kLevelNone) {
        if (st.phase == kAbsent) {
            erase_cube(s, c);
            return;
        }
        if (st.phase == kInstalled) s->unload_q.push_back(c);
    }
}

} // namespace

void apply_plans(cv_stream* s) {
    const int64_t deadline = now_us() + kApplyBudgetUs;
    for (;;) {
        if (!s->applying) {
            std::lock_guard<std::mutex> g(s->plan_mutex);
            if (s->batches.empty()) return;
            s->applying = std::move(s->batches.front());
            s->batches.pop_front();
            s->apply_col = s->apply_cube = 0;
        }
        PlanBatch& b = *s->applying;
        while (s->apply_col < b.columns.size()) apply_column_change(s, b.columns[s->apply_col++]);
        while (s->apply_cube < b.cubes.size()) {
            const CubeChange& ch = b.cubes[s->apply_cube++];
            apply_level(s, ch.c, ch.level);
            if ((s->apply_cube & 511) == 0 && now_us() > deadline) return;
        }
        s->plan_wanted = b.wanted;
        s->plan_needed = b.needed;
        s->plan_walk = b.walk_cubes;
        auto list = std::make_shared<const std::vector<LoadItem>>(std::move(b.list));
        s->active_list = list;
        s->list_cursor = 0;
        {
            std::lock_guard<std::mutex> g(s->list_mutex);
            s->missing_list = std::move(list);
        }
        s->applying.reset();
        s->plan_cv.notify_all();
        if (now_us() > deadline) return;
    }
}

} // namespace cenda::voxel::streaming
