// Streamer planner: the SURFACE-FOLLOWING load shape of voxel_world.h (cv_center) as per-column runs of
// levels, the population support shell, hysteresis, the load priority list and the diff against the
// previous plan. See stream_plan.hpp.
#include "stream_plan.hpp"
#include "cv_internal.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>

namespace cenda::voxel::streaming {

namespace {

constexpr double kHalfDiag = 27.712812921102035;   // half the diagonal of a 32^3 cube (16 * sqrt(3))
constexpr double kHyst = 32.0;                      // hysteresis: one cube
constexpr int64_t kHoldMs = 3000;                   // (c) cubes stay this long after their last report

int64_t sat_add(int64_t a, int64_t b) {
    if (b > 0 && a > INT64_MAX - b) return INT64_MAX;
    if (b < 0 && a < INT64_MIN - b) return INT64_MIN;
    return a + b;
}
int64_t sat_sub(int64_t a, int64_t b) {
    if (b == INT64_MIN) return a >= 0 ? INT64_MAX : a - b;
    return sat_add(a, -b);
}
int64_t cube_of(int64_t y) { return y >> CV_CUBE_SHIFT; }

// Highest cube y that is not wholly known air under a column top maximum (cy * 32 >= tmax is known air).
int64_t known_air_cap(int64_t tmax) {
    if (tmax == INT64_MIN) return INT64_MIN;
    return cube_of(tmax - 1);
}

// Cube-y interval of cube centres inside the ellipsoid of a viewer at height vy whose column centre lies at
// horizontal distance^2 d2, horizontal radius R and vertical radius V (both already inflated).
void add_ellipsoid(Ivs& out, int64_t vy, double d2, double R, double V) {
    if (R <= 0.0 || V <= 0.0 || d2 > R * R) return;
    const double half = V * std::sqrt(std::max(0.0, 1.0 - d2 / (R * R)));
    const int64_t shifted = sat_sub(vy, 16);
    const int64_t base = cube_of(shifted);
    const double frac = static_cast<double>(shifted - base * CV_CUBE_EDGE);   // 0..31
    const auto lo = static_cast<int64_t>(std::ceil((frac - half) / 32.0));
    const auto hi = static_cast<int64_t>(std::floor((frac + half) / 32.0));
    if (lo > hi) return;
    out.push_back(Iv{sat_add(base, lo), sat_add(base, hi)});
}

void dilate_into(const Ivs& src, Ivs& dst) {
    for (const Iv& iv : src) dst.push_back(Iv{sat_sub(iv.lo, 1), sat_add(iv.hi, 1)});
}

bool contains(const Ivs& v, int64_t y) {
    for (const Iv& iv : v) {
        if (y < iv.lo) return false;
        if (y <= iv.hi) return true;
    }
    return false;
}

void diff_runs(const Int2& col, const Runs& a, const Runs& b, std::vector<CubeChange>& out) {
    if (a == b) return;
    std::vector<int64_t> bp;
    bp.reserve((a.size() + b.size()) * 2);
    for (const Runs* rs : {&a, &b}) {
        for (const Run& r : *rs) {
            bp.push_back(r.lo);
            if (r.hi != INT64_MAX) bp.push_back(r.hi + 1);
        }
    }
    std::sort(bp.begin(), bp.end());
    bp.erase(std::unique(bp.begin(), bp.end()), bp.end());
    for (size_t i = 0; i + 1 < bp.size(); ++i) {
        const int64_t s = bp[i], e = bp[i + 1] - 1;
        const uint8_t la = level_at(a, s), lb = level_at(b, s);
        if (la == lb) continue;
        for (int64_t cy = s;; ++cy) {
            out.push_back(CubeChange{Int3{col.x, cy, col.z}, lb});
            if (cy == e) break;
        }
    }
}

} // namespace

// ---- interval helpers --------------------------------------------------------------------------------------

void normalize(Ivs& v) {
    if (v.size() < 2) {
        if (v.size() == 1 && v[0].lo > v[0].hi) v.clear();
        return;
    }
    std::sort(v.begin(), v.end(), [](const Iv& a, const Iv& b) { return a.lo < b.lo; });
    size_t w = 0;
    for (size_t i = 0; i < v.size(); ++i) {
        if (v[i].lo > v[i].hi) continue;
        if (w > 0 && (v[w - 1].hi == INT64_MAX || v[i].lo <= v[w - 1].hi + 1)) {
            v[w - 1].hi = std::max(v[w - 1].hi, v[i].hi);
        } else {
            v[w++] = v[i];
        }
    }
    v.resize(w);
}

void clip(Ivs& v, int64_t lo, int64_t hi) {
    size_t w = 0;
    for (const Iv& iv : v) {
        const Iv c{std::max(iv.lo, lo), std::min(iv.hi, hi)};
        if (c.lo <= c.hi) v[w++] = c;
    }
    v.resize(w);
}

uint8_t level_at(const Runs& runs, int64_t cy) {
    for (const Run& r : runs) {
        if (cy < r.lo) return kLevelNone;
        if (cy <= r.hi) return r.level;
    }
    return kLevelNone;
}

Runs build_runs(const Ivs& wn, const Ivs& n, const Ivs& k) {
    Runs out;
    if (k.empty() && n.empty() && wn.empty()) return out;
    std::vector<int64_t> bp;
    for (const Ivs* s : {&wn, &n, &k}) {
        for (const Iv& iv : *s) {
            bp.push_back(iv.lo);
            if (iv.hi != INT64_MAX) bp.push_back(iv.hi + 1);
        }
    }
    std::sort(bp.begin(), bp.end());
    bp.erase(std::unique(bp.begin(), bp.end()), bp.end());
    for (size_t i = 0; i + 1 < bp.size(); ++i) {
        const int64_t s = bp[i], e = bp[i + 1] - 1;
        const uint8_t lv = contains(wn, s) ? kLevelWanted : contains(n, s) ? kLevelSupport
                                                          : contains(k, s) ? kLevelKeep
                                                                           : kLevelNone;
        if (lv == kLevelNone) continue;
        if (!out.empty() && out.back().level == lv && out.back().hi + 1 == s) {
            out.back().hi = e;
        } else {
            out.push_back(Run{s, e, lv});
        }
    }
    return out;
}

// ---- planner -------------------------------------------------------------------------------------------------

Planner::Planner(cv_world* world, const cv_generator* gen, bool remote)
    : world_(world), gen_(gen), remote_(remote),
      cmin_(cube_of(world_min_y(world))), cmax_(cube_of(world_max_y(world) == INT64_MIN ? INT64_MIN : world_max_y(world) - 1)) {}

TopInfo resolve_top(cv_world* world, const cv_generator* gen, int64_t cx, int64_t cz) {
    TopInfo t;
    int64_t a = 0, b = 0, c = 0;
    if (cv_top_range(world, cx, cz, &a, &b, &c) == CV_OK) {
        t.known = true;
        t.top_min = a;
        t.top_max = b;
        t.sky_min = c;
        return t;
    }
    if (gen == nullptr || gen->column_surface == nullptr) return t;
    std::vector<int64_t> sky(1024), top(1024);
    if (gen->column_surface(gen->user, cx, cz, sky.data(), top.data()) == 1) {
        t.known = true;
        t.top_min = INT64_MAX;
        t.top_max = INT64_MIN;
        t.sky_min = INT64_MAX;
        const int64_t lo = world_min_y(world), hi = world_max_y(world);
        for (int i = 0; i < 1024; ++i) {
            const auto ui = static_cast<size_t>(i);
            const int64_t tv = std::clamp(top[ui], lo, hi), sv = std::clamp(sky[ui], lo, hi);
            t.top_min = std::min(t.top_min, tv);
            t.top_max = std::max(t.top_max, tv);
            t.sky_min = std::min(t.sky_min, sv);
        }
    }
    return t;
}

TopInfo Planner::top_of(int64_t cx, int64_t cz, double d2) {
    TopInfo t;
    int64_t a = 0, b = 0, c = 0;
    const int32_t peek = top_range_peek(world_, cx, cz, &a, &b, &c);
    if (peek == 1) {   // a column record, or a cached callback answer: cheap
        t.known = true;
        t.top_min = a;
        t.top_max = b;
        t.sky_min = c;
        return t;
    }
    const bool fallback = gen_ != nullptr && gen_->column_surface != nullptr;
    if (peek == 0 && !fallback) return t;   // nothing anybody could know
    const Int2 key{cx, cz};
    if (const auto it = resolved_.find(key); it != resolved_.end()) return it->second;
    if (!request_) {   // synchronous planner
        t = resolve_top(world_, gen_, cx, cz);
        resolved_.emplace(key, t);
        return t;
    }
    t.pending = true;
    if (requested_.count(key) == 0) {
        auto [it, inserted] = to_request_.try_emplace(key, d2);
        if (!inserted) it->second = std::min(it->second, d2);
    }
    return t;
}

void Planner::add_resolved(const Int2& c, const TopInfo& t) {
    requested_.erase(c);
    TopInfo v = t;
    v.pending = false;
    resolved_[c] = v;
}

// Answers of columns far from every viewer go once the cache is large (they are re-resolved if needed again).
void Planner::evict_resolved(const std::vector<cv_center>& centers) {
    size_t keep_area = 0;
    for (const cv_center& v : centers) {
        const auto r = static_cast<size_t>((std::max(0, v.radius_h) + 95) / 32 + 3);
        keep_area += (2 * r + 1) * (2 * r + 1);
    }
    if (resolved_.size() <= 2 * keep_area + 4096) return;
    for (auto it = resolved_.begin(); it != resolved_.end();) {
        bool near = false;
        for (const cv_center& v : centers) {
            const int64_t r = (std::max(0, v.radius_h) + 95) / 32 + 3;
            if (std::abs(it->first.x - cube_of(v.x)) <= r && std::abs(it->first.z - cube_of(v.z)) <= r) {
                near = true;
                break;
            }
        }
        if (near) {
            ++it;
        } else {
            it = resolved_.erase(it);
        }
    }
}

void Planner::walk_viewers(const std::vector<cv_center>& centers, int64_t now_ms) {
    walk_latest_.resize(centers.size());
    for (size_t i = 0; i < centers.size(); ++i) {
        const cv_center& v = centers[i];
        std::vector<Int3>& latest = walk_latest_[i];
        latest.clear();
        const int32_t radius = std::clamp((std::max(0, v.radius_h) + 31) / 32, 1, 255);   // walk limit
        walk_buf_.present.clear();
        walk_buf_.missing.clear();
        if (walk_visible(world_, cube_of(v.x), cube_of(v.y), cube_of(v.z), radius, nullptr, walk_buf_) < 0) continue;
        for (const std::vector<int64_t>* list : {&walk_buf_.present, &walk_buf_.missing}) {
            for (size_t j = 0; j + 2 < list->size(); j += 3) {
                const Int3 c{(*list)[j], (*list)[j + 1], (*list)[j + 2]};
                latest.push_back(c);
                seen_[c] = now_ms;
            }
        }
    }
}

uint64_t Planner::key_of(const std::vector<cv_center>& centers, const Int3& c, const Work& w, uint8_t level) const {
    uint64_t best = UINT64_MAX;
    const double extra = level >= kLevelWanted ? 0.0 : kHyst;
    // The band, and (for support cubes) the cube right above/below it: what completes the column.
    const int64_t band_pad = level >= kLevelWanted ? 0 : 1;
    const bool in_band = w.band && c.y >= sat_sub(w.band_lo, band_pad) && c.y <= sat_add(w.band_hi, band_pad);
    constexpr double kCap = static_cast<double>((uint64_t{1} << 61) - 1);
    for (const cv_center& v : centers) {
        const double ddx = static_cast<double>(c.x - cube_of(v.x)) * 32.0 + 16.0 - static_cast<double>(v.x & 31);
        const double ddy = static_cast<double>(c.y - cube_of(v.y)) * 32.0 + 16.0 - static_cast<double>(v.y & 31);
        const double ddz = static_cast<double>(c.z - cube_of(v.z)) * 32.0 + 16.0 - static_cast<double>(v.z & 31);
        const double rh = std::max(1, v.radius_h) + kHalfDiag + extra;
        const double rv = std::max(1, v.radius_v) + kHalfDiag + extra;
        const double h2 = ddx * ddx + ddz * ddz;
        double dist = std::sqrt(h2 + ddy * ddy);
        uint64_t rule = level >= kLevelWanted ? kRuleVisible : kRuleSupport;
        if (in_band) {
            rule = kRuleBand;
            dist = std::max(0.0, dist - kBandBias);
        } else if (h2 / (rh * rh) + ddy * ddy / (rv * rv) <= 1.0) {
            rule = kRuleEllipsoid;
        }
        const auto q = static_cast<uint64_t>(std::min(dist * 16.0, kCap));
        best = std::min(best, (q << 2) | rule);
    }
    return best;
}

void Planner::compute(const std::vector<cv_center>& centers, int64_t now_ms, bool walk, PlanBatch& out) {
    const auto t0 = std::chrono::steady_clock::now();
    out.columns.clear();
    out.cubes.clear();
    out.list.clear();
    out.wanted = out.needed = out.walk_cubes = 0;

    to_request_.clear();
    // Prefetch for the visibility walk: its square reaches past the disc in the corners and it asks each
    // column's TOP on this thread, so it runs only once every TOP it can reach is known (see TOPS).
    if (walk && request_) {
        for (const cv_center& v : centers) {
            const int64_t r = std::clamp((std::max(0, v.radius_h) + 31) / 32, 1, 255);
            const int64_t vcx = cube_of(v.x), vcz = cube_of(v.z);
            for (int64_t dz = -r; dz <= r; ++dz) {
                for (int64_t dx = -r; dx <= r; ++dx) {
                    const double d2 = (static_cast<double>(dx * dx + dz * dz) + 1.0) * 1024.0;
                    (void)top_of(sat_add(vcx, dx), sat_add(vcz, dz), d2);
                }
            }
        }
    }
    const bool walk_now = walk && (!request_ || (requested_.empty() && to_request_.empty()));
    if (walk_now) walk_viewers(centers, now_ms);
    out.walked = walk_now;
    if (walk_latest_.size() > centers.size()) walk_latest_.resize(centers.size());
    for (auto it = seen_.begin(); it != seen_.end();) {
        if (now_ms - it->second > kHoldMs) {
            it = seen_.erase(it);
        } else {
            ++it;
        }
    }

    std::unordered_map<Int2, Work, Int2Hash> work;
    work.reserve(prev_.size() + 1024);
    auto fetch = [&](const Int2& key, double d2) -> Work& {
        Work& w = work[key];
        if (!w.top_fetched) {
            w.top = top_of(key.x, key.z, d2);
            w.top_fetched = true;
            if (w.top.pending) ++out.tops_pending;
        }
        return w;
    };

    // (a) ellipsoid + (b) surface band, normal and hysteresis shapes, per viewer and column.
    for (const cv_center& v : centers) {
        const double rh = std::max(0, v.radius_h), rv = std::max(0, v.radius_v);
        const double Rn = rh + kHalfDiag, Rk = Rn + kHyst;
        const double Vn = rv + kHalfDiag, Vk = Vn + kHyst;
        const int64_t vcx = cube_of(v.x), vcz = cube_of(v.z);
        const double fx = static_cast<double>(v.x & 31), fz = static_cast<double>(v.z & 31);
        const auto R = static_cast<int64_t>(std::ceil(Rk / 32.0)) + 1;
        const int64_t below = std::max(0, v.band_below), above = std::max(0, v.band_above);
        for (int64_t dcz = -R; dcz <= R; ++dcz) {
            for (int64_t dcx = -R; dcx <= R; ++dcx) {
                const double ddx = static_cast<double>(dcx) * 32.0 + 16.0 - fx;
                const double ddz = static_cast<double>(dcz) * 32.0 + 16.0 - fz;
                const double d2 = ddx * ddx + ddz * ddz;
                if (d2 > Rk * Rk) continue;
                const bool in_n = d2 <= Rn * Rn;
                Work& w = fetch(Int2{sat_add(vcx, dcx), sat_add(vcz, dcz)}, d2);
                if (w.top.pending) continue;   // planned once its TOP is known
                add_ellipsoid(w.wk, v.y, d2, Rk, Vk);
                if (in_n) add_ellipsoid(w.wn, v.y, d2, Rn, Vn);
                if (!w.top.known) continue;
                const int64_t lo_y = sat_sub(std::min(w.top.sky_min, w.top.top_min), below);
                const int64_t hi_y = sat_add(w.top.top_max, above);   // exclusive
                if (hi_y <= lo_y) continue;
                const int64_t blo = cube_of(lo_y), bhi = cube_of(hi_y - 1);
                w.wk.push_back(Iv{sat_sub(blo, 1), sat_add(bhi, 1)});
                if (in_n) {
                    w.wn.push_back(Iv{blo, bhi});
                    w.band = true;
                    w.band_lo = blo;
                    w.band_hi = bhi;
                }
            }
        }
    }

    // (c) visible cubes: the latest walk of each viewer (inside its disc) wants them; recent reports keep them.
    for (size_t i = 0; i < walk_latest_.size() && i < centers.size(); ++i) {
        const cv_center& v = centers[i];
        const double Rn = std::max(0, v.radius_h) + kHalfDiag;
        for (const Int3& c : walk_latest_[i]) {
            const double ddx = static_cast<double>(c.x - cube_of(v.x)) * 32.0 + 16.0 - static_cast<double>(v.x & 31);
            const double ddz = static_cast<double>(c.z - cube_of(v.z)) * 32.0 + 16.0 - static_cast<double>(v.z & 31);
            if (ddx * ddx + ddz * ddz > Rn * Rn) continue;
            Work& w = fetch(Int2{c.x, c.z}, ddx * ddx + ddz * ddz);
            if (w.top.pending) continue;
            w.wn.push_back(Iv{c.y, c.y});
            w.wk.push_back(Iv{c.y, c.y});
            ++out.walk_cubes;
        }
    }
    // Held (c) cubes stay only inside some viewer's hysteresis disc (a teleport drops them at once).
    for (const auto& [c, t] : seen_) {
        (void)t;
        bool in_disc = false;
        for (const cv_center& v : centers) {
            const double Rk = std::max(0, v.radius_h) + kHalfDiag + kHyst;
            const double ddx = static_cast<double>(c.x - cube_of(v.x)) * 32.0 + 16.0 - static_cast<double>(v.x & 31);
            const double ddz = static_cast<double>(c.z - cube_of(v.z)) * 32.0 + 16.0 - static_cast<double>(v.z & 31);
            if (ddx * ddx + ddz * ddz <= Rk * Rk) {
                in_disc = true;
                break;
            }
        }
        if (!in_disc) continue;
        Work& w = fetch(Int2{c.x, c.z}, 1e18);
        if (w.top.pending) continue;
        w.wk.push_back(Iv{c.y, c.y});
    }

    // Bounds and known air; readiness band.
    for (auto& [key, w] : work) {
        (void)key;
        normalize(w.wn);
        normalize(w.wk);
        int64_t cap = cmax_;
        if (w.top.known) cap = std::min(cap, known_air_cap(w.top.top_max));
        clip(w.wn, cmin_, cap);
        clip(w.wk, cmin_, cap);
        if (w.band) {
            w.band_lo = std::max(w.band_lo, cmin_);
            w.band_hi = std::min(w.band_hi, cap);
            if (w.band_lo > w.band_hi) w.band = false;
        }
    }

    // Needed = wanted + population support (3x3x3 dilation), keep = hysteresis shape + its support.
    if (remote_) {
        for (auto& [key, w] : work) {
            (void)key;
            w.n = w.wn;
            w.k = w.wk;
            w.k.insert(w.k.end(), w.wn.begin(), w.wn.end());
            normalize(w.k);
        }
    } else {
        std::vector<Int2> sources;
        sources.reserve(work.size());
        for (const auto& [key, w] : work) {
            if (!w.wn.empty() || !w.wk.empty()) sources.push_back(key);
        }
        std::vector<std::pair<Int2, Ivs>> dn, dk;
        dn.reserve(sources.size());
        dk.reserve(sources.size());
        for (const Int2& key : sources) {
            const Work& w = work[key];
            Ivs a, b;
            dilate_into(w.wn, a);
            dilate_into(w.wk, b);
            dn.emplace_back(key, std::move(a));
            dk.emplace_back(key, std::move(b));
        }
        for (size_t s = 0; s < sources.size(); ++s) {
            for (int64_t dz = -1; dz <= 1; ++dz) {
                for (int64_t dx = -1; dx <= 1; ++dx) {
                    Work& nw = work[Int2{sat_add(sources[s].x, dx), sat_add(sources[s].z, dz)}];
                    nw.n.insert(nw.n.end(), dn[s].second.begin(), dn[s].second.end());
                    nw.k.insert(nw.k.end(), dk[s].second.begin(), dk[s].second.end());
                }
            }
        }
        for (auto& [key, w] : work) {
            (void)key;
            normalize(w.n);
            clip(w.n, cmin_, cmax_);
            w.k.insert(w.k.end(), w.n.begin(), w.n.end());
            normalize(w.k);
            clip(w.k, cmin_, cmax_);
        }
    }

    // Runs, diff, column changes.
    std::unordered_map<Int2, ColumnPlan, Int2Hash> next;
    next.reserve(work.size());
    for (auto& [key, w] : work) {
        ColumnPlan cp;
        cp.runs = build_runs(w.wn, w.n, w.k);
        cp.has_band = w.band;
        cp.band_lo = w.band ? w.band_lo : 0;
        cp.band_hi = w.band ? w.band_hi : -1;
        if (cp.runs.empty()) continue;
        next.emplace(key, std::move(cp));
    }
    static const Runs kEmpty;
    for (const auto& [key, cp] : next) {
        const auto old = prev_.find(key);
        const Runs& before = old == prev_.end() ? kEmpty : old->second.runs;
        diff_runs(key, before, cp.runs, out.cubes);
        const bool was_tracked = old != prev_.end();
        if (!was_tracked || old->second.has_band != cp.has_band ||
            (cp.has_band && (old->second.band_lo != cp.band_lo || old->second.band_hi != cp.band_hi))) {
            out.columns.push_back(ColumnChange{key, true, cp.has_band, cp.band_lo, cp.band_hi});
        }
        for (const Run& r : cp.runs) {
            const int64_t n = r.hi - r.lo + 1;
            if (r.level == kLevelWanted) out.wanted += n;
            if (r.level >= kLevelSupport) out.needed += n;
        }
    }
    for (const auto& [key, cp] : prev_) {
        if (next.find(key) != next.end()) continue;
        diff_runs(key, cp.runs, kEmpty, out.cubes);
        out.columns.push_back(ColumnChange{key, false, false, 0, -1});
    }
    prev_.swap(next);

    // Load list: absent cubes worth loading, by priority.
    const uint8_t min_level = remote_ ? kLevelWanted : kLevelSupport;
    for (const auto& [key, cp] : prev_) {
        const auto wit = work.find(key);
        for (const Run& r : cp.runs) {
            if (r.level < min_level) continue;
            for (int64_t cy = r.lo;; ++cy) {
                if (cv_cube_exists(world_, key.x, cy, key.z) == 0) {
                    const Int3 c{key.x, cy, key.z};
                    out.list.push_back(LoadItem{key_of(centers, c, wit->second, r.level), c});
                }
                if (cy == r.hi) break;
            }
        }
    }
    std::sort(out.list.begin(), out.list.end(), [](const LoadItem& a, const LoadItem& b) {
        if (a.key != b.key) return a.key < b.key;
        if (a.c.x != b.c.x) return a.c.x < b.c.x;
        if (a.c.y != b.c.y) return a.c.y < b.c.y;
        return a.c.z < b.c.z;
    });

    // New TOP requests, nearest first.
    if (!to_request_.empty()) {
        std::vector<std::pair<double, Int2>> order;
        order.reserve(to_request_.size());
        for (const auto& [k, d2] : to_request_) order.emplace_back(d2, k);
        std::sort(order.begin(), order.end(), [](const auto& a, const auto& b) {
            if (a.first != b.first) return a.first < b.first;
            if (a.second.x != b.second.x) return a.second.x < b.second.x;
            return a.second.z < b.second.z;
        });
        std::vector<LoadItem> cols;
        cols.reserve(order.size());
        for (const auto& [d2, k] : order) {
            cols.push_back(LoadItem{top_request_key(d2), Int3{k.x, 0, k.z}});
            requested_.insert(k);
        }
        to_request_.clear();
        request_(cols);
    }
    evict_resolved(centers);
    out.plan_us = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - t0).count();
}

} // namespace cenda::voxel::streaming
