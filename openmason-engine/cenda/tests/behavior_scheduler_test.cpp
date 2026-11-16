// Cenda behaviour scheduler (cb_*) conformance: priority groups, flag mutex, preemption, weighted
// choice, the exact host-call order, re-entrant stop_all from a tick, host failures. Plain asserts.

#include "cenda/behavior.h"

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

namespace {

int failures = 0;

#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); \
            ++failures;                                                          \
        }                                                                        \
    } while (0)

constexpr uint32_t MOVE = 1u, LOOK = 2u;

// A scripted host: per behaviour, whether it can start / should continue; a queue of random rolls;
// a log of every call.
struct Host {
    std::vector<int> can_start;
    std::vector<int> keep;
    std::vector<float> rolls;
    size_t next_roll = 0;
    std::string log;
    int fail_on_op = -1;
    cb_scheduler* reenter = nullptr; // stop_all from this behaviour's tick
    int reenter_index = -1;
};

int32_t host_fn(void* user, int32_t op, int32_t index, float dt, float* out_random) {
    auto* h = static_cast<Host*>(user);
    (void) dt;
    if (op == h->fail_on_op) return -1;
    switch (op) {
    case CB_OP_SHOULD_CONTINUE:
        h->log += "c" + std::to_string(index) + " ";
        return h->keep[static_cast<size_t>(index)];
    case CB_OP_CAN_START:
        h->log += "?" + std::to_string(index) + " ";
        return h->can_start[static_cast<size_t>(index)];
    case CB_OP_START:
        h->log += "+" + std::to_string(index) + " ";
        return 0;
    case CB_OP_STOP:
        h->log += "-" + std::to_string(index) + " ";
        return 0;
    case CB_OP_TICK:
        h->log += "t" + std::to_string(index) + " ";
        if (h->reenter != nullptr && index == h->reenter_index) {
            cb_scheduler* s = h->reenter;
            h->reenter = nullptr;
            cb_scheduler_stop_all(s, host_fn, user);
        }
        return 0;
    case CB_OP_RANDOM:
        h->log += "r ";
        *out_random = h->rolls[h->next_roll++];
        return 0;
    default:
        return -1;
    }
}

void priorities_and_flags() {
    // 0: flee (p0, MOVE), 1: wander (p5, MOVE), 2: look (p5, LOOK)
    cb_behavior_desc d[3] = {{0, 1.0f, MOVE, 0}, {5, 1.0f, MOVE, 0}, {5, 1.0f, LOOK, 0}};
    cb_scheduler* s = cb_scheduler_create(d, 3);
    CHECK(s != nullptr);
    Host h;
    h.can_start = std::vector<int>{0, 1, 1};
    h.keep = std::vector<int>{1, 1, 1};
    h.rolls = std::vector<float>{0.0f};
    CHECK(cb_scheduler_tick(s, host_fn, &h, 0.05f) == CB_OK);
    // Flee is asked first (priority 0) and refuses. Wander and look both fit, so the first pass
    // offers both (one roll: wander); the second pass starts look beside it (disjoint flags).
    CHECK(h.log == "?0 ?1 ?2 r +1 ?2 +2 t1 t2 ");
    CHECK(cb_scheduler_is_running(s, 1) == 1);
    CHECK(cb_scheduler_is_running(s, 2) == 1);
    CHECK(cb_scheduler_leader(s) == 1); // equal priority: the older one
    int32_t running[4];
    CHECK(cb_scheduler_running(s, running, 4) == 2);

    // Flee can start now: it preempts wander (shares MOVE) but not look.
    h.log.clear();
    h.can_start[0] = 1;
    CHECK(cb_scheduler_tick(s, host_fn, &h, 0.05f) == CB_OK);
    CHECK(cb_scheduler_is_running(s, 0) == 1);
    CHECK(cb_scheduler_is_running(s, 1) == 0);
    CHECK(cb_scheduler_is_running(s, 2) == 1);
    CHECK(cb_scheduler_leader(s) == 0);
    // continue checks newest first (look, wander), flee asked, starts after stopping wander; wander
    // no longer fits; ticks oldest first (look, flee).
    CHECK(h.log == "c2 c1 ?0 -1 +0 t2 t0 ");
    cb_scheduler_destroy(s);
}

void exact_call_order_and_weighted_pick() {
    // Three ambient behaviours of one priority sharing MOVE: idle (w 6), wander (w 3), graze (w 1).
    cb_behavior_desc d[3] = {{10, 6.0f, MOVE, 0}, {10, 3.0f, MOVE, 0}, {10, 1.0f, MOVE, 0}};
    cb_scheduler* s = cb_scheduler_create(d, 3);
    Host h;
    h.can_start = std::vector<int>{1, 1, 1};
    h.keep = std::vector<int>{1, 1, 1};
    h.rolls = std::vector<float>{0.7f}; // 0.7 * 10 = 7: idle takes 6, wander (3) ends it
    CHECK(cb_scheduler_tick(s, host_fn, &h, 0.05f) == CB_OK);
    CHECK(h.log == "?0 ?1 ?2 r +1 t1 ");
    CHECK(cb_scheduler_leader(s) == 1);

    // While wander runs, nobody else of its priority fits: no predicate is asked.
    h.log.clear();
    CHECK(cb_scheduler_tick(s, host_fn, &h, 0.05f) == CB_OK);
    CHECK(h.log == "c1 t1 ");

    // A single candidate is taken without a roll.
    h.log.clear();
    h.keep[1] = 0;
    h.can_start = std::vector<int>{0, 0, 1};
    CHECK(cb_scheduler_tick(s, host_fn, &h, 0.05f) == CB_OK);
    CHECK(h.log == "c1 -1 ?0 ?1 ?2 +2 t2 ");
    CHECK(h.next_roll == 1);
    cb_scheduler_destroy(s);
}

void zero_weights_pick_the_first() {
    cb_behavior_desc d[2] = {{0, 0.0f, MOVE, 0}, {0, -2.0f, MOVE, 0}};
    cb_scheduler* s = cb_scheduler_create(d, 2);
    Host h;
    h.can_start = std::vector<int>{1, 1};
    h.keep = std::vector<int>{1, 1};
    CHECK(cb_scheduler_tick(s, host_fn, &h, 0.05f) == CB_OK);
    CHECK(cb_scheduler_is_running(s, 0) == 1);
    CHECK(h.next_roll == 0); // no roll when every weight is zero
    cb_scheduler_destroy(s);
}

void stable_priority_sort() {
    // Given out of priority order: list order is (1, 2, 0) — priority 1, 1, 7 — stable among equals.
    cb_behavior_desc d[3] = {{7, 1.0f, LOOK, 0}, {1, 1.0f, MOVE, 0}, {1, 1.0f, MOVE, 0}};
    cb_scheduler* s = cb_scheduler_create(d, 3);
    Host h;
    h.can_start = std::vector<int>{1, 1, 1};
    h.keep = std::vector<int>{1, 1, 1};
    h.rolls = std::vector<float>{0.0f}; // roll 0: the first candidate in list order
    CHECK(cb_scheduler_tick(s, host_fn, &h, 0.05f) == CB_OK);
    CHECK(h.log == "?1 ?2 r +1 ?0 +0 t1 t0 ");
    cb_scheduler_destroy(s);
}

void stop_all_and_reentry() {
    cb_behavior_desc d[2] = {{0, 1.0f, MOVE, 0}, {0, 1.0f, LOOK, 0}};
    cb_scheduler* s = cb_scheduler_create(d, 2);
    Host h;
    h.can_start = std::vector<int>{1, 1};
    h.keep = std::vector<int>{1, 1};
    h.rolls = std::vector<float>{0.0f, 0.0f};
    CHECK(cb_scheduler_tick(s, host_fn, &h, 0.05f) == CB_OK);
    CHECK(cb_scheduler_is_running(s, 0) == 1 && cb_scheduler_is_running(s, 1) == 1);

    // Behaviour 0's tick stops everything: the second tick never happens.
    h.log.clear();
    h.can_start = std::vector<int>{0, 0};
    h.reenter = s;
    h.reenter_index = 0;
    CHECK(cb_scheduler_tick(s, host_fn, &h, 0.05f) == CB_OK);
    CHECK(h.log == "c1 c0 t0 -1 -0 ");
    CHECK(cb_scheduler_leader(s) == -1);

    h.log.clear();
    CHECK(cb_scheduler_stop_all(s, host_fn, &h) == CB_OK);
    CHECK(h.log.empty());
    cb_scheduler_destroy(s);
}

void host_failures_abort() {
    cb_behavior_desc d[1] = {{0, 1.0f, MOVE, 0}};
    cb_scheduler* s = cb_scheduler_create(d, 1);
    Host h;
    h.can_start = std::vector<int>{1};
    h.keep = std::vector<int>{1};
    h.fail_on_op = CB_OP_START;
    CHECK(cb_scheduler_tick(s, host_fn, &h, 0.05f) == CB_ERR_HOST);
    // The behaviour being started was already in the running list.
    CHECK(cb_scheduler_is_running(s, 0) == 1);
    CHECK(std::string(cb_last_error()).size() > 0);
    cb_scheduler_destroy(s);
}

void bad_input() {
    CHECK(cb_scheduler_create(nullptr, 1) == nullptr);
    CHECK(cb_scheduler_create(nullptr, -1) == nullptr);
    cb_scheduler* empty = cb_scheduler_create(nullptr, 0);
    CHECK(empty != nullptr);
    Host h;
    CHECK(cb_scheduler_tick(empty, host_fn, &h, 0.05f) == CB_OK);
    CHECK(cb_scheduler_leader(empty) == -1);
    CHECK(cb_scheduler_is_running(empty, 0) == CB_ERR_ARG);
    CHECK(cb_scheduler_tick(empty, nullptr, &h, 0.05f) == CB_ERR_ARG);
    CHECK(cb_scheduler_tick(nullptr, host_fn, &h, 0.05f) == CB_ERR_ARG);
    cb_scheduler_destroy(empty);
}

} // namespace

int main() {
    CHECK(cb_abi_version() == CB_ABI_VERSION);
    priorities_and_flags();
    exact_call_order_and_weighted_pick();
    zero_weights_pick_the_first();
    stable_priority_sort();
    stop_all_and_reentry();
    host_failures_abort();
    bad_input();
    if (failures != 0) {
        std::fprintf(stderr, "behavior_scheduler: %d check(s) failed\n", failures);
        return 1;
    }
    std::printf("behavior_scheduler: ok\n");
    return 0;
}
