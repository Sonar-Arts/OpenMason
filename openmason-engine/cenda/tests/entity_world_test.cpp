// Plain-assert checks for the Cenda entity model (ce_*): handles and their generations, deferred
// spawn/despawn commits (cancellation, retirement, slot reuse), live order, type descriptors,
// body sync + spatial queries against a brute-force reference, the float rules shared with the
// Java code it replaced, clip clocks, and spawning from other threads. The Java side re-tests the
// contract through FFM (EntityWorldTest).

#include "cenda/entity.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <random>
#include <thread>
#include <vector>

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    if (!ok) {
        ++failures;
        std::fprintf(stderr, "FAIL: %s\n", what);
    }
}

constexpr float kInf = std::numeric_limits<float>::infinity();

ce_type_desc desc(float w, float h, float l, float leg, float yaw = 0.0f, uint32_t flags = 0u) {
    return ce_type_desc{w, h, l, leg, yaw, flags};
}

std::vector<ce_handle> commit_all(ce_world* w) {
    std::vector<ce_handle> out(64);
    std::vector<ce_handle> all;
    int32_t n;
    while ((n = ce_commit_spawns(w, out.data(), static_cast<int32_t>(out.size()))) > 0) {
        all.insert(all.end(), out.begin(), out.begin() + n);
    }
    return all;
}

std::vector<ce_handle> despawn_all(ce_world* w) {
    std::vector<ce_handle> out(64);
    int32_t n = ce_commit_despawns(w, out.data(), static_cast<int32_t>(out.size()));
    out.resize(static_cast<size_t>(n));
    return out;
}

std::vector<ce_handle> live(ce_world* w) {
    std::vector<ce_handle> out(static_cast<size_t>(ce_live(w, nullptr, 0)));
    ce_live(w, out.data(), static_cast<int32_t>(out.size()));
    return out;
}

void testLifecycle() {
    ce_world* w = ce_world_create(8.0f);
    ce_type_desc cow = desc(1.1f, 0.4f, 1.3f, 0.62f, 180.0f, 1u);
    check(ce_type_define(w, 3, &cow, "stonebreak:cow") == CE_OK, "define type");
    check(ce_spawn(w, 4, 0u, 0, 0, 0, 0) == 0, "undefined type refuses spawn");
    check(ce_type_define(w, CE_MAX_TYPES, &cow, nullptr) == CE_ERR_ARG, "type id range");

    ce_type_desc got{};
    check(ce_type_get(w, 3, &got) == 1 && got.leg_height == 0.62f && got.model_yaw_offset_deg == 180.0f,
          "type descriptor round trip");
    check(std::strcmp(ce_type_model_id(w, 3), "stonebreak:cow") == 0, "model id");
    check(ce_type_get(w, 2, &got) == 0 && ce_type_model_id(w, 2) == nullptr, "undefined type");

    ce_handle a = ce_spawn(w, 3, 1u, 1, 2, 3, 0);
    ce_handle b = ce_spawn(w, 3, 1u, 4, 5, 6, 0);
    ce_handle c = ce_spawn(w, 3, 1u, 7, 8, 9, 0);
    check(a != 0 && b != 0 && c != 0 && a != b, "spawn handles");
    check(ce_state(w, a) == CE_STATE_PENDING, "spawned entity is pending");
    check(ce_count(w, CE_COUNT_PENDING) == 3 && ce_count(w, CE_COUNT_LIVE) == 0, "pending count");
    check(ce_query_all(w, nullptr, nullptr, 0) == 0, "pending entities are not in the world");

    ce_body body{};
    check(ce_body_get(w, a, &body) == 1 && body.width == 1.1f && body.leg_height == 0.62f && body.x == 1.0f,
          "spawn body takes the type's size");

    ce_handle queued[4] = {0, 0, 0, 0};
    check(ce_pending(w, queued, 4) == 3 && queued[0] == a && queued[1] == b && queued[2] == c, "pending listing");
    check(ce_despawn(w, b) == 1, "despawn pending = cancel");
    check(ce_state(w, b) == CE_STATE_NONE, "cancelled entity reads NONE");
    check(ce_despawn(w, b) == 0, "second despawn is a no-op");
    check(ce_pending(w, queued, 4) == 2 && queued[0] == a && queued[1] == c, "cancelled leaves the pending listing");

    ce_handle one[1];
    check(ce_commit_spawns(w, one, 0) == 0 && ce_count(w, CE_COUNT_LIVE) == 0, "cap 0 commits nothing");
    std::vector<ce_handle> added = commit_all(w);
    check(added.size() == 2 && added[0] == a && added[1] == c, "commit in queue order, cancelled skipped");
    check(live(w) == added, "live order = commit order");
    check(ce_state(w, a) == CE_STATE_LIVE, "committed entity is live");

    // Removal during iteration: the despawned entity stays in the world until the commit.
    check(ce_despawn(w, a) == 1 && ce_state(w, a) == CE_STATE_REMOVING, "despawn live -> removing");
    check(ce_despawn(w, a) == 0, "despawn twice queues once");
    check(ce_count(w, CE_COUNT_LIVE) == 2 && ce_count(w, CE_COUNT_REMOVING) == 1, "removing still counted live");
    check(ce_query_all(w, nullptr, nullptr, 0) == 2, "removing entity still answers queries");

    std::vector<ce_handle> removed = despawn_all(w);
    check(removed.size() == 1 && removed[0] == a, "despawn commit reports the entity");
    check(ce_state(w, a) == CE_STATE_RETIRED, "retired until the next despawn commit");
    check(ce_clip_set(w, a, 2, 0u) == CE_OK, "retired handle still has its clip clock");
    check(live(w) == std::vector<ce_handle>{c}, "live order after removal");

    // The retired slot becomes reusable only after the next despawn commit; reuse changes the
    // generation, so the old handle never names the newcomer.
    ce_handle d = ce_spawn(w, 3, 0u, 0, 0, 0, 0);
    check((d & 0xFFFFFFFFull) != (a & 0xFFFFFFFFull), "retired slot not reused yet");
    check(despawn_all(w).empty(), "nothing to despawn");
    check(ce_state(w, a) == CE_STATE_NONE, "retired handle goes stale at the next commit");
    ce_handle e = ce_spawn(w, 3, 0u, 0, 0, 0, 0);
    check((e & 0xFFFFFFFFull) == (a & 0xFFFFFFFFull) && e != a, "slot reused with a new generation");
    check(ce_state(w, a) == CE_STATE_NONE && ce_type_of(w, a) == -1, "old handle stays stale");
    check(ce_clip_set(w, a, 1, 0u) == CE_ERR_STALE && std::isnan(ce_clip_time(w, a)), "stale clip calls fail");
    check(ce_despawn(w, a) == 0, "stale despawn ignored");
    check(ce_state(w, 0) == CE_STATE_NONE && ce_state(w, 0xFFFFFFFF00000001ull) == CE_STATE_NONE, "bogus handles");

    commit_all(w);
    check(live(w) == (std::vector<ce_handle>{c, d, e}), "order after reuse");

    // Stable removal from the middle of a longer list.
    std::vector<ce_handle> many;
    for (int i = 0; i < 10; ++i) {
        many.push_back(ce_spawn(w, 3, 0u, static_cast<float>(i), 0, 0, 0));
    }
    commit_all(w);
    ce_despawn(w, many[7]);
    ce_despawn(w, d);
    ce_despawn(w, many[2]);
    std::vector<ce_handle> gone = despawn_all(w);
    check(gone == (std::vector<ce_handle>{many[7], d, many[2]}), "despawns reported in despawn order");
    std::vector<ce_handle> expect{c, e};
    for (int i = 0; i < 10; ++i) {
        if (i != 2 && i != 7) {
            expect.push_back(many[static_cast<size_t>(i)]);
        }
    }
    check(live(w) == expect, "stable compaction");

    ce_world_destroy(w);
}

struct Ref {
    ce_handle h;
    float x, y, z, w, hgt, l, leg;
    uint32_t flags;
    int32_t type;
};

void testQueriesMatchBruteForce() {
    ce_world* w = ce_world_create(4.0f);
    ce_type_desc t0 = desc(0.6f, 1.8f, 0.6f, 0.0f);
    ce_type_desc t1 = desc(1.1f, 0.4f, 1.3f, 0.62f);
    ce_type_define(w, 0, &t0, nullptr);
    ce_type_define(w, 1, &t1, nullptr);

    std::mt19937 rng(1234u);
    std::uniform_real_distribution<float> pos(-60.0f, 60.0f);
    std::uniform_real_distribution<float> size(0.1f, 3.0f);
    std::vector<Ref> refs;
    for (int i = 0; i < 400; ++i) {
        int32_t type = static_cast<int32_t>(rng() % 2u);
        ce_handle h = ce_spawn(w, type, 0u, 0, 0, 0, 0);
        refs.push_back(Ref{h, pos(rng), pos(rng) * 0.25f, pos(rng), size(rng), size(rng), size(rng),
                           (rng() % 3u) == 0u ? size(rng) : 0.0f, static_cast<uint32_t>(rng() % 8u), type});
    }
    // A few entities on the bad side of float: they must still be found by unbounded scans.
    refs[5].x = std::numeric_limits<float>::quiet_NaN();
    refs[6].z = kInf;
    commit_all(w);
    std::vector<ce_body> bodies;
    for (const Ref& r : refs) {
        bodies.push_back(ce_body{r.h, r.x, r.y, r.z, 0.0f, r.w, r.hgt, r.l, r.leg, r.flags, 0u});
    }
    check(ce_sync_bodies(w, bodies.data(), static_cast<int32_t>(bodies.size())) == 400, "sync applies all");

    std::vector<ce_handle> out(512);
    for (int trial = 0; trial < 200; ++trial) {
        float cx = pos(rng), cy = pos(rng) * 0.25f, cz = pos(rng);
        float r = trial % 50 == 0 ? kInf : size(rng) * 6.0f;
        ce_filter f{trial % 3 == 0 ? -1 : static_cast<int32_t>(trial % 2), (trial % 4 == 0) ? 1u : 0u,
                    (trial % 5 == 0) ? 2u : 0u, 0u, refs[static_cast<size_t>(trial)].h};
        auto pass = [&](const Ref& ref) {
            return (f.type_id < 0 || ref.type == f.type_id) && (ref.flags & f.require_flags) == f.require_flags
                   && (ref.flags & f.exclude_flags) == 0u && ref.h != f.exclude;
        };

        std::vector<ce_handle> expect;
        float r2 = r * r;
        for (const Ref& ref : refs) {
            float dx = ref.x - cx, dy = ref.y - cy, dz = ref.z - cz;
            if (pass(ref) && dx * dx + (dy * dy + dz * dz) <= r2) {
                expect.push_back(ref.h);
            }
        }
        int32_t n = ce_query_radius(w, cx, cy, cz, r, &f, out.data(), 512);
        check(std::vector<ce_handle>(out.begin(), out.begin() + n) == expect, "radius query = brute force");

        ce_handle best = 0;
        float bestD = std::numeric_limits<float>::max();
        for (const Ref& ref : refs) {
            float dx = ref.x - cx, dy = ref.y - cy, dz = ref.z - cz;
            float d = std::sqrt(dx * dx + (dy * dy + dz * dz));
            if (pass(ref) && d < bestD) {
                bestD = d;
                best = ref.h;
            }
        }
        float gotD = 0.0f;
        check(ce_query_nearest(w, cx, cy, cz, kInf, &f, &gotD) == best && gotD == bestD, "nearest = brute force");

        float box[6] = {cx - 8.0f, -kInf, cz - 8.0f, cx + 8.0f, kInf, cz + 8.0f};
        expect.clear();
        for (const Ref& ref : refs) {
            if (pass(ref) && ref.x >= box[0] && ref.x < box[3] && ref.z >= box[2] && ref.z < box[5]) {
                expect.push_back(ref.h);
            }
        }
        n = ce_query_box(w, box, &f, out.data(), 512);
        check(std::vector<ce_handle>(out.begin(), out.begin() + n) == expect, "box query = brute force");

        float aabb[6] = {cx - 2.0f, cy - 2.0f, cz - 2.0f, cx + 2.0f, cy + 2.0f, cz + 2.0f};
        expect.clear();
        for (const Ref& ref : refs) {
            bool hit = ref.x - ref.w / 2.0f < aabb[3] && ref.x + ref.w / 2.0f > aabb[0]
                       && ref.y - ref.leg < aabb[4] && ref.y + ref.hgt > aabb[1]
                       && ref.z - ref.l / 2.0f < aabb[5] && ref.z + ref.l / 2.0f > aabb[2];
            if (pass(ref) && hit) {
                expect.push_back(ref.h);
            }
        }
        n = ce_query_aabb(w, aabb, &f, out.data(), 512);
        check(std::vector<ce_handle>(out.begin(), out.begin() + n) == expect, "aabb query = brute force");
    }

    // cap smaller than the result: total is still reported, only cap written.
    ce_handle two[2] = {0, 0};
    int32_t total = ce_query_all(w, nullptr, two, 2);
    check(total == 400 && two[0] == refs[0].h && two[1] == refs[1].h, "cap truncates, total reported");

    // Membership changes rebuild the index without a new sync.
    ce_handle late = ce_spawn(w, 0, 0u, 100.0f, 0.0f, 100.0f, 0.0f);
    check(ce_query_radius(w, 100.0f, 0.0f, 100.0f, 1.0f, nullptr, out.data(), 512) == 0, "pending not found");
    commit_all(w);
    check(ce_query_radius(w, 100.0f, 0.0f, 100.0f, 1.0f, nullptr, out.data(), 512) == 1 && out[0] == late,
          "committed entity found at its spawn position");
    ce_despawn(w, late);
    despawn_all(w);
    check(ce_query_radius(w, 100.0f, 0.0f, 100.0f, 1.0f, nullptr, out.data(), 512) == 0, "retired not found");

    ce_world_destroy(w);
}

void testNearestTiesAndRadiusBoundary() {
    ce_world* w = ce_world_create(0.0f); // default cell
    ce_type_desc t = desc(1, 1, 1, 0);
    ce_type_define(w, 0, &t, nullptr);
    ce_handle first = ce_spawn(w, 0, 0u, 3, 0, 0, 0);
    ce_handle second = ce_spawn(w, 0, 0u, -3, 0, 0, 0);
    commit_all(w);
    check(ce_query_nearest(w, 0, 0, 0, kInf, nullptr, nullptr) == first, "tie goes to the first in live order");
    check(ce_query_nearest(w, 0, 0, 0, 2.0f, nullptr, nullptr) == 0, "max radius bounds nearest");
    ce_handle out[4];
    check(ce_query_radius(w, 0, 0, 0, 3.0f, nullptr, out, 4) == 2, "radius boundary is inclusive");
    check(ce_query_radius(w, 0, 0, 0, std::nextafter(3.0f, 0.0f), nullptr, out, 4) == 0, "just inside excludes");
    ce_filter onlySecond{-1, 0u, 0u, 0u, first};
    check(ce_query_nearest(w, 0, 0, 0, kInf, &onlySecond, nullptr) == second, "exclude handle");
    ce_world_destroy(w);
}

void testClipClock() {
    ce_world* w = ce_world_create(8.0f);
    ce_type_desc t = desc(1, 1, 1, 0);
    ce_type_define(w, 0, &t, nullptr);
    ce_handle h = ce_spawn(w, 0, 0u, 0, 0, 0, 0);
    ce_clip_state st{};
    check(ce_clip_get(w, h, &st) == CE_OK && st.clip == -1 && st.total_time == 0.0f, "fresh clock");

    ce_clip_set(w, h, 1, 0u);
    float total = 0.0f, state = 0.0f;
    for (int i = 0; i < 37; ++i) {
        ce_clip_advance(w, h, 0.05f, 0.05f);
        total += 0.05f;
        state += 0.05f;
    }
    check(ce_clip_time(w, h) == total, "looping clip samples the continuous clock (same float adds)");
    ce_clip_set(w, h, 1, CE_CLIP_ONE_SHOT);
    check(ce_clip_time(w, h) == state, "same clip keeps its state clock; flags still stored");
    ce_clip_set(w, h, 4, CE_CLIP_ONE_SHOT);
    check(ce_clip_time(w, h) == 0.0f, "a new clip restarts the state clock");
    ce_clip_advance(w, h, 0.0f, 0.25f);
    ce_clip_advance(w, h, 0.5f, 0.0f);
    check(ce_clip_time(w, h) == 0.25f, "one-shot samples the state clock");
    ce_clip_get(w, h, &st);
    check(st.total_time == total + 0.5f && st.clip == 4 && st.flags == CE_CLIP_ONE_SHOT, "clock fields");

    ce_clip_state put{7, 0u, 1.5f, 9.0f};
    check(ce_clip_put(w, h, &put) == CE_OK && ce_clip_time(w, h) == 9.0f, "put overwrites the clock");
    ce_world_destroy(w);
}

void testSpawnFromOtherThreads() {
    ce_world* w = ce_world_create(8.0f);
    ce_type_desc t = desc(1, 1, 1, 0);
    ce_type_define(w, 0, &t, nullptr);
    constexpr int kThreads = 4;
    constexpr int kEach = 3000; // crosses many 256-slot pages while the owner commits
    std::vector<std::thread> threads;
    for (int i = 0; i < kThreads; ++i) {
        threads.emplace_back([w, i] {
            for (int j = 0; j < kEach; ++j) {
                ce_handle h = ce_spawn(w, 0, 0u, static_cast<float>(i), static_cast<float>(j), 0, 0);
                if (j % 3 == 0) {
                    ce_despawn(w, h);
                }
            }
        });
    }
    size_t committed = 0;
    std::vector<ce_handle> buf(97);
    for (int spin = 0; spin < 200000 && committed < static_cast<size_t>(kThreads * kEach * 2 / 3); ++spin) {
        int32_t n = ce_commit_spawns(w, buf.data(), 97);
        committed += static_cast<size_t>(n);
        ce_query_radius(w, 0, 0, 0, 10.0f, nullptr, nullptr, 0);
    }
    for (std::thread& th : threads) {
        th.join();
    }
    committed += commit_all(w).size();
    // Every third spawn was despawned; a despawn lands either before the commit (cancelled) or
    // after it (REMOVING). Either way none of them may be lost or reported twice.
    size_t removing = static_cast<size_t>(ce_count(w, CE_COUNT_REMOVING));
    check(committed - removing == static_cast<size_t>(kThreads * (kEach - kEach / 3)), "threaded spawn accounting");
    check(static_cast<size_t>(ce_count(w, CE_COUNT_LIVE)) == committed, "live count = committed");
    std::vector<ce_handle> out(static_cast<size_t>(removing) + 1);
    check(static_cast<size_t>(ce_commit_despawns(w, out.data(), static_cast<int32_t>(out.size()))) == removing,
          "late despawns retire");
    ce_world_destroy(w);
}

} // namespace

int main() {
    check(ce_abi_version() == CE_ABI_VERSION, "abi version");
    testLifecycle();
    testQueriesMatchBruteForce();
    testNearestTiesAndRadiusBoundary();
    testClipClock();
    testSpawnFromOtherThreads();
    if (failures != 0) {
        std::fprintf(stderr, "%d failure(s)\n", failures);
        return 1;
    }
    std::puts("entity_world: ok");
    return 0;
}
