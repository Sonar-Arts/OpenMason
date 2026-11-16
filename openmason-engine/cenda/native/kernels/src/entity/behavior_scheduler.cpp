// Cenda behaviour scheduler (cb_*): priority groups, flag mutex, preemption, weighted choice among
// equals. Contract in include/cenda/behavior.h (OpenMason #115).
//
// Every host call happens in the order the contract states, which is what keeps a seeded host's
// behaviour stream identical to the Java controller this replaced (BehaviorSchedulerTest keeps a copy):
// the same predicates are asked, in the same order, the same number of times, and the weighted pick
// draws a random number only when there is more than one candidate.

#include "cenda/behavior.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <new>
#include <numeric>
#include <string>
#include <vector>

namespace {

thread_local std::string t_error;

int32_t fail(int32_t code, const char* message) {
    t_error = message;
    return code;
}

struct Entry {
    int32_t index;     // as given at create
    int32_t priority;
    float weight;
    uint32_t flags;
};

} // namespace

struct cb_scheduler {
    std::vector<Entry> list;          // stably sorted by priority
    std::vector<int32_t> position;    // index -> position in list
    std::vector<int32_t> running;     // list positions, oldest first
    std::vector<int32_t> candidates;  // scratch: list positions
};

namespace {

bool is_running(const cb_scheduler* s, int32_t pos) {
    return std::find(s->running.begin(), s->running.end(), pos) != s->running.end();
}

// Nothing at least as urgent already holds a flag the candidate needs.
bool fits(const cb_scheduler* s, int32_t pos) {
    const Entry& c = s->list[static_cast<size_t>(pos)];
    for (int32_t active : s->running) {
        const Entry& a = s->list[static_cast<size_t>(active)];
        if (a.priority <= c.priority && (a.flags & c.flags) != 0u) {
            return false;
        }
    }
    return true;
}

int32_t call(cb_host_fn host, void* user, int32_t op, int32_t index, float dt, float* out = nullptr) {
    float scratch = 0.0f;
    return host(user, op, index, dt, out != nullptr ? out : &scratch);
}

// Stops the running behaviours holding a flag the starting one needs, newest first.
int32_t preempt(cb_scheduler* s, cb_host_fn host, void* user, int32_t pos, float dt) {
    const uint32_t needed = s->list[static_cast<size_t>(pos)].flags;
    for (int32_t i = static_cast<int32_t>(s->running.size()) - 1; i >= 0; --i) {
        if (i >= static_cast<int32_t>(s->running.size())) continue; // a stop hook shrank the list
        int32_t active = s->running[static_cast<size_t>(i)];
        if ((s->list[static_cast<size_t>(active)].flags & needed) != 0u) {
            s->running.erase(s->running.begin() + i);
            if (call(host, user, CB_OP_STOP, s->list[static_cast<size_t>(active)].index, dt) < 0) {
                return CB_ERR_HOST;
            }
        }
    }
    return CB_OK;
}

// The weighted pick among candidates (list positions, list order). Writes the chosen position.
int32_t pick(cb_scheduler* s, cb_host_fn host, void* user, float dt, int32_t* chosen) {
    const std::vector<int32_t>& options = s->candidates;
    if (options.size() == 1) {
        *chosen = options[0];
        return CB_OK;
    }
    float total = 0.0f;
    for (int32_t pos : options) {
        total += std::max(0.0f, s->list[static_cast<size_t>(pos)].weight);
    }
    if (total <= 0.0f) {
        *chosen = options[0];
        return CB_OK;
    }
    float r = 0.0f;
    if (call(host, user, CB_OP_RANDOM, -1, dt, &r) < 0) {
        return CB_ERR_HOST;
    }
    float roll = r * total;
    for (int32_t pos : options) {
        roll -= std::max(0.0f, s->list[static_cast<size_t>(pos)].weight);
        if (roll <= 0.0f) {
            *chosen = pos;
            return CB_OK;
        }
    }
    *chosen = options.back();
    return CB_OK;
}

// Starts as many behaviours of one priority group [from, to) as fit alongside each other.
int32_t start_group(cb_scheduler* s, cb_host_fn host, void* user, int32_t from, int32_t to, float dt) {
    while (true) {
        s->candidates.clear();
        for (int32_t pos = from; pos < to; ++pos) {
            if (is_running(s, pos) || !fits(s, pos)) continue;
            int32_t r = call(host, user, CB_OP_CAN_START, s->list[static_cast<size_t>(pos)].index, dt);
            if (r < 0) return CB_ERR_HOST;
            if (r != 0) s->candidates.push_back(pos);
        }
        if (s->candidates.empty()) {
            return CB_OK;
        }
        int32_t chosen = -1;
        if (pick(s, host, user, dt, &chosen) != CB_OK) return CB_ERR_HOST;
        if (preempt(s, host, user, chosen, dt) != CB_OK) return CB_ERR_HOST;
        s->running.push_back(chosen);
        if (call(host, user, CB_OP_START, s->list[static_cast<size_t>(chosen)].index, dt) < 0) {
            return CB_ERR_HOST;
        }
    }
}

} // namespace

extern "C" {

int32_t cb_abi_version(void) {
    return CB_ABI_VERSION;
}

const char* cb_last_error(void) {
    return t_error.c_str();
}

cb_scheduler* cb_scheduler_create(const cb_behavior_desc* behaviors, int32_t count) {
    if (count < 0 || count > CB_MAX_BEHAVIORS || (count > 0 && behaviors == nullptr)) {
        fail(CB_ERR_ARG, "bad behaviour list");
        return nullptr;
    }
    auto* s = new (std::nothrow) cb_scheduler();
    if (s == nullptr) {
        fail(CB_ERR_ARG, "out of memory");
        return nullptr;
    }
    s->list.reserve(static_cast<size_t>(count));
    for (int32_t i = 0; i < count; ++i) {
        const cb_behavior_desc& d = behaviors[i];
        float w = std::isfinite(d.weight) ? d.weight : 0.0f;
        s->list.push_back(Entry{i, d.priority, w, d.flags});
    }
    std::stable_sort(s->list.begin(), s->list.end(),
                     [](const Entry& a, const Entry& b) { return a.priority < b.priority; });
    s->position.assign(static_cast<size_t>(count), -1);
    for (size_t p = 0; p < s->list.size(); ++p) {
        s->position[static_cast<size_t>(s->list[p].index)] = static_cast<int32_t>(p);
    }
    s->running.reserve(4);
    s->candidates.reserve(8);
    return s;
}

void cb_scheduler_destroy(cb_scheduler* scheduler) {
    delete scheduler;
}

int32_t cb_scheduler_tick(cb_scheduler* s, cb_host_fn host, void* user, float dt) {
    if (s == nullptr || host == nullptr) return fail(CB_ERR_ARG, "null scheduler or host");

    // 1. Stop what no longer applies, newest first.
    for (int32_t i = static_cast<int32_t>(s->running.size()) - 1; i >= 0; --i) {
        if (i >= static_cast<int32_t>(s->running.size())) continue; // a hook shrank the list
        int32_t pos = s->running[static_cast<size_t>(i)];
        int32_t index = s->list[static_cast<size_t>(pos)].index;
        int32_t keep = call(host, user, CB_OP_SHOULD_CONTINUE, index, dt);
        if (keep < 0) return fail(CB_ERR_HOST, "host failed in should_continue");
        if (keep == 0) {
            // The hook may have changed the list; remove this behaviour wherever it now is.
            auto it = std::find(s->running.begin(), s->running.end(), pos);
            if (it != s->running.end()) s->running.erase(it);
            if (call(host, user, CB_OP_STOP, index, dt) < 0) return fail(CB_ERR_HOST, "host failed in stop");
        }
    }

    // 2. Start the most urgent things that fit, one priority group at a time.
    const int32_t n = static_cast<int32_t>(s->list.size());
    int32_t from = 0;
    while (from < n) {
        const int32_t priority = s->list[static_cast<size_t>(from)].priority;
        int32_t to = from;
        while (to < n && s->list[static_cast<size_t>(to)].priority == priority) ++to;
        if (start_group(s, host, user, from, to, dt) != CB_OK) return fail(CB_ERR_HOST, "host failed while starting");
        from = to;
    }

    // 3. Tick what runs, oldest first; a tick may change the list.
    for (size_t i = 0; i < s->running.size(); ++i) {
        int32_t index = s->list[static_cast<size_t>(s->running[i])].index;
        if (call(host, user, CB_OP_TICK, index, dt) < 0) return fail(CB_ERR_HOST, "host failed in tick");
    }
    return CB_OK;
}

int32_t cb_scheduler_stop_all(cb_scheduler* s, cb_host_fn host, void* user) {
    if (s == nullptr || host == nullptr) return fail(CB_ERR_ARG, "null scheduler or host");
    while (!s->running.empty()) {
        int32_t pos = s->running.back();
        s->running.pop_back();
        if (call(host, user, CB_OP_STOP, s->list[static_cast<size_t>(pos)].index, 0.0f) < 0) {
            return fail(CB_ERR_HOST, "host failed in stop");
        }
    }
    return CB_OK;
}

int32_t cb_scheduler_leader(const cb_scheduler* s) {
    if (s == nullptr) return -1;
    int32_t leader = -1;
    for (int32_t pos : s->running) {
        if (leader < 0 || s->list[static_cast<size_t>(pos)].priority < s->list[static_cast<size_t>(leader)].priority) {
            leader = pos;
        }
    }
    return leader < 0 ? -1 : s->list[static_cast<size_t>(leader)].index;
}

int32_t cb_scheduler_is_running(const cb_scheduler* s, int32_t index) {
    if (s == nullptr || index < 0 || index >= static_cast<int32_t>(s->position.size())) {
        return fail(CB_ERR_ARG, "bad behaviour index");
    }
    return is_running(s, s->position[static_cast<size_t>(index)]) ? 1 : 0;
}

int32_t cb_scheduler_running(const cb_scheduler* s, int32_t* out, int32_t cap) {
    if (s == nullptr) return 0;
    const int32_t n = static_cast<int32_t>(s->running.size());
    for (int32_t i = 0; i < n && i < cap && out != nullptr; ++i) {
        out[i] = s->list[static_cast<size_t>(s->running[static_cast<size_t>(i)])].index;
    }
    return n;
}

} // extern "C"
