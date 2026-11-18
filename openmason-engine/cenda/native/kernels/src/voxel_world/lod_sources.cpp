// Octree LOD runtime: block classification, the per-level node stores, the level-0 sources (loaded cubes, the
// cube store, column facts synthesizing unsaved cubes) and node reads / rebuilds / prepares (data + mesh).
// Shared runtime structures: lod_runtime.hpp.
#include "lod_runtime.hpp"
#include "mesh_internal.hpp"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace cenda::voxel::lod {

namespace {

// Node y range inside [min_y, max_y)?
bool node_in_range(const cv_lod* L, const NodeKey& k) {
    const int64_t y0 = node_origin(k.y, k.level);
    return y0 < L->max_y && y0 + span(k.level) > L->min_y;
}

std::mutex& stripe(cv_lod* L, const NodeKey& k) {
    return L->stripes[k.level][NodeKeyHash{}(k) & 63];
}

// ---- classification ------------------------------------------------------------------------------------------
const ClassTable& class_table(cv_lod* L) {
    struct Cached {
        uint64_t lod = 0;
        const uint16_t* flags = nullptr;
        uint64_t cfg_version = ~uint64_t{0};
        uint64_t ovr_version = 0;
        ClassTable ct;
    };
    thread_local std::unique_ptr<Cached> c;
    if (!c) c = std::make_unique<Cached>();
    const uint16_t* flags = world_block_flags(L->world);
    std::shared_ptr<const MeshConfig> cfg = mesh_config(L->world);
    const uint64_t ov = L->overrides_version.load(std::memory_order_acquire);
    if (c->lod != L->serial || c->flags != flags || c->cfg_version != cfg->version || c->ovr_version != ov) {
        std::shared_ptr<const std::vector<uint8_t>> over;
        {
            std::lock_guard<std::mutex> g(L->class_m);
            over = L->overrides;
        }
        for (uint32_t id = 0; id < 65536; ++id) {
            uint8_t cls;
            switch ((*over)[id]) {
                case CV_LOD_CLASS_AIR: cls = kAir; break;
                case CV_LOD_CLASS_SOLID: cls = kSolid; break;
                case CV_LOD_CLASS_FLUID: cls = kFluid; break;
                default:
                    if (id == L->air) cls = kAir;
                    else if (cfg->is_fluid(static_cast<uint16_t>(id))) cls = kFluid;
                    else if ((flags[id] & CV_BLOCK_CUBE) != 0) cls = kSolid;
                    else cls = kAir;
            }
            c->ct.cls[id] = cls;
        }
        c->lod = L->serial;
        c->flags = flags;
        c->cfg_version = cfg->version;
        c->ovr_version = ov;
    }
    return c->ct;
}

struct WorldFaces final : FaceLookup {
    std::shared_ptr<const MeshConfig> cfg;
    const uint16_t* flags = nullptr;
    void face(uint16_t id, int f, uint32_t* layer, bool* alpha) const override {
        auto it = cfg->faces.find(id);
        if (it != cfg->faces.end()) {
            const cv_face_tex& t = it->second[static_cast<size_t>(f)];
            *layer = t.layer;
            *alpha = (t.flags & CV_FACE_ALPHA_TEST) != 0;
            return;
        }
        *layer = id;
        *alpha = (flags[id] & CV_BLOCK_TRANSPARENT) != 0 && (flags[id] & CV_BLOCK_TRANSLUCENT) == 0;
    }
};

// ---- stores --------------------------------------------------------------------------------------------------
cv_store* level_store(cv_lod* L, int32_t level) {
    if (!L->persist || level < 1) return nullptr;
    std::lock_guard<std::mutex> g(L->store_m);
    if (L->stores[level] == nullptr) {
        const std::string d = L->dir + "/l" + std::to_string(level);
        L->stores[level] = cv_store_open(d.c_str());
        if (L->stores[level] == nullptr) L->errors_total.fetch_add(1);
    }
    return L->stores[level];
}

void note_store_error(cv_lod* L, int32_t r) {
    L->errors_total.fetch_add(1);
    int32_t expected = 0;
    L->first_store_error.compare_exchange_strong(expected, r);
}

NodePtr load_record(cv_lod* L, const NodeKey& k) {
    cv_store* s = level_store(L, k.level);
    if (s == nullptr) return nullptr;
    thread_local std::vector<uint8_t> buf;
    if (buf.size() < 4096) buf.resize(4096);
    int64_t n = cv_store_read_cube(s, k.x, k.y, k.z, buf.data(), static_cast<int64_t>(buf.size()));
    if (CV_IS_NEED(n)) {
        buf.resize(static_cast<size_t>(CV_NEEDED(n)));
        n = cv_store_read_cube(s, k.x, k.y, k.z, buf.data(), static_cast<int64_t>(buf.size()));
    }
    if (n == 0) return nullptr;
    if (n < 0) {
        if (n == CV_ERR_CORRUPT) L->corrupt_total.fetch_add(1);
        else note_store_error(L, static_cast<int32_t>(n));
        return nullptr;
    }
    auto d = std::make_shared<NodeData>();
    if (decode_record(buf.data(), static_cast<size_t>(n), k, *d) != CV_OK) {
        L->corrupt_total.fetch_add(1);
        return nullptr;
    }
    L->loaded_total.fetch_add(1);
    return d;
}

void save_record(cv_lod* L, const NodeData& d) {
    // Unexplored space (incomplete and all air) is cheap to re-derive and would only fill the store.
    if (!d.complete && d.uniform && (d.uniform_cell & kClassMask) == kAir) return;
    cv_store* s = level_store(L, d.key.level);
    if (s == nullptr) return;
    thread_local std::vector<uint8_t> buf;
    encode_record(d, buf);
    const int32_t r = cv_store_write_cube(s, d.key.x, d.key.y, d.key.z, buf.data(), static_cast<int64_t>(buf.size()));
    if (r != CV_OK) note_store_error(L, r);
    else L->saved_total.fetch_add(1);
}

// ---- level-0 sources ----------------------------------------------------------------------------------------
cv_world* scratch_world(cv_lod* L) {
    struct Holder {
        cv_world* w = nullptr;
        int64_t lo = 0, hi = 0;
        uint16_t air = 0;
        ~Holder() {
            if (w != nullptr) cv_world_destroy(w);
        }
    };
    thread_local Holder h;
    const int64_t lo = world_min_y(L->world), hi = world_max_y(L->world);
    if (h.w == nullptr || h.lo != lo || h.hi != hi || h.air != L->air) {
        if (h.w != nullptr) cv_world_destroy(h.w);
        cv_world_desc d{};
        d.min_y = lo;
        d.max_y = hi;
        d.air_id = L->air;
        h.w = cv_world_create(&d);
        h.lo = lo;
        h.hi = hi;
        h.air = L->air;
    }
    return h.w;
}

uint64_t column_version(cv_lod* L, const std::pair<int64_t, int64_t>& key) {   // col_m held
    auto it = L->col_ver.find(key);
    return it == L->col_ver.end() ? 0 : it->second;
}

} // namespace

// check: a noted column re-derived by its check job (always recomputed; a first sight counts as a change, since
// nodes persisted by an earlier session may have used older column facts).
ColumnPtr column_info(cv_lod* L, int64_t cx, int64_t cz, bool check) {
    const auto key = std::make_pair(cx, cz);
    uint64_t ver = 0;
    {
        std::lock_guard<std::mutex> g(L->col_m);
        if (!check) {
            auto it = L->columns.find(key);
            if (it != L->columns.end()) return it->second;
        }
        ver = column_version(L, key);
    }
    auto info = std::make_shared<ColumnInfo>();
    thread_local std::vector<uint8_t> buf;
    if (buf.size() < 8192) buf.resize(8192);
    int64_t n = cv_column_encode(L->world, cx, cz, buf.data(), static_cast<int64_t>(buf.size()));
    if (CV_IS_NEED(n)) {
        buf.resize(static_cast<size_t>(CV_NEEDED(n)));
        n = cv_column_encode(L->world, cx, cz, buf.data(), static_cast<int64_t>(buf.size()));
    }
    const uint8_t* core = nullptr;
    size_t core_len = 0;
    if (n > 0) {
        if (parse_column_record(buf.data(), static_cast<size_t>(n), info->runs, &core, &core_len) && !info->runs.empty()) {
            info->top.resize(1024);
            column_top(L->world, cx, cz, info->top.data());
            info->generated = true;
            info->top_min = *std::min_element(info->top.begin(), info->top.end());
            info->top_max = *std::max_element(info->top.begin(), info->top.end());
        }
    } else if (L->cube_store != nullptr) {
        n = cv_store_read_column(L->cube_store, cx, cz, buf.data(), static_cast<int64_t>(buf.size()));
        if (CV_IS_NEED(n)) {
            buf.resize(static_cast<size_t>(CV_NEEDED(n)));
            n = cv_store_read_column(L->cube_store, cx, cz, buf.data(), static_cast<int64_t>(buf.size()));
        }
        if (n > 0) {
            if (parse_column_record(buf.data(), static_cast<size_t>(n), info->runs, &core, &core_len) &&
                !info->runs.empty()) {
                cv_world* sw = scratch_world(L);
                if (sw != nullptr && cv_column_decode(sw, cx, cz, core, static_cast<int64_t>(core_len)) == CV_OK) {
                    info->top.resize(1024);
                    column_top(sw, cx, cz, info->top.data());
                    cv_column_remove(sw, cx, cz);
                    info->generated = true;
                    info->top_min = *std::min_element(info->top.begin(), info->top.end());
                    info->top_max = *std::max_element(info->top.begin(), info->top.end());
                } else {
                    info->runs.clear();
                    L->corrupt_total.fetch_add(1);
                }
            } else {
                info->runs.clear();
                L->corrupt_total.fetch_add(1);
            }
        } else if (n < 0) {
            L->errors_total.fetch_add(1);
        }
    }
    {
        uint64_t h = cenda::mix64(info->generated ? 2 : 1);
        for (const Run& r : info->runs) h = cenda::mix64(h ^ static_cast<uint64_t>(r.lo)) + static_cast<uint64_t>(r.hi);
        if (!info->top.empty()) h = cenda::mix64(h ^ hash_bytes(info->top.data(), info->top.size() * 8, 5));
        info->digest = h;
    }
    bool changed = false;
    {
        std::lock_guard<std::mutex> g(L->col_m);
        auto seen = L->col_seen.find(key);
        changed = seen == L->col_seen.end() ? check : seen->second != info->digest;
        if (L->col_seen.size() > (size_t{1} << 20)) L->col_seen.clear();
        L->col_seen[key] = info->digest;
        if (column_version(L, key) == ver) {
            if (L->columns.size() > 16384) L->columns.clear();
            L->columns[key] = info;
        }
    }
    if (changed) column_changed(L, cx, cz);
    return info;
}

namespace {

NodePtr l0_data(cv_lod* L, const NodeKey& k) {
    if (!node_in_range(L, k)) {
        auto d = std::make_shared<NodeData>();
        node_uniform(*d, k, L->air, kAir, true);
        return d;
    }
    const int64_t rev = cv_cube_revision(L->world, k.x, k.y, k.z);
    uint64_t tag = 0;
    if (NodePtr p = L->cache0.get(k, &tag); p && tag == static_cast<uint64_t>(rev)) return p;
    uint64_t ver = 0;
    {
        std::lock_guard<std::mutex> g(L->col_m);
        ver = column_version(L, std::make_pair(k.x, k.z));
    }
    const ClassTable& ct = class_table(L);
    thread_local std::vector<uint16_t> ids;
    ids.resize(kCells);
    auto d = std::make_shared<NodeData>();
    bool built = false;
    bool uniform_cube = false;
    uint16_t uniform_value = 0;
    const bool inside = k.y * kEdge >= L->min_y && k.y * kEdge + kEdge <= L->max_y;
    int64_t got_rev = 0;
    {
        CubeRead cr(L->world, k.x, k.y, k.z);
        if (cr) {
            got_rev = cr.revision();
            uint16_t u = 0;
            if (cr.uniform(&u)) {
                uniform_cube = true;
                uniform_value = u;
            } else {
                cr.read_ids(ids.data());
            }
            built = true;
        }
    }
    if (built) {
        if (uniform_cube && inside) {
            const uint8_t c = ct.cls[uniform_value];
            node_uniform(*d, k, c == kAir ? L->air : uniform_value,
                         c == kSolid ? static_cast<uint8_t>(kSolid | kFull) : c, true);
        } else {
            if (uniform_cube) std::fill(ids.begin(), ids.end(), uniform_value);
            node_from_ids(*d, k, ids.data(), ct, L->air, L->min_y, L->max_y);
        }
    } else if (L->cube_store != nullptr) {
        thread_local std::vector<uint8_t> buf;
        if (buf.size() < 65536) buf.resize(65536);
        int64_t n = cv_store_read_cube(L->cube_store, k.x, k.y, k.z, buf.data(), static_cast<int64_t>(buf.size()));
        if (CV_IS_NEED(n)) {
            buf.resize(static_cast<size_t>(CV_NEEDED(n)));
            n = cv_store_read_cube(L->cube_store, k.x, k.y, k.z, buf.data(), static_cast<int64_t>(buf.size()));
        }
        if (n > 0) {
            if (decode_cube_blocks(buf.data(), static_cast<size_t>(n), ids.data()) == CV_OK) {
                node_from_ids(*d, k, ids.data(), ct, L->air, L->min_y, L->max_y);
                built = true;
            } else {
                L->corrupt_total.fetch_add(1);
            }
        } else if (n < 0) {
            L->errors_total.fetch_add(1);
        }
    }
    if (!built) {
        ColumnPtr col = column_info(L, k.x, k.z);
        const int64_t y0 = k.y * kEdge;
        const uint8_t fill_cls = ct.cls[L->fill];
        if (col->generated && !col->in_runs(k.y) && inside && y0 >= col->top_max) {
            node_uniform(*d, k, L->air, kAir, true);
        } else if (col->generated && !col->in_runs(k.y) && inside && y0 + kEdge <= col->top_min) {
            node_uniform(*d, k, fill_cls == kAir ? L->air : L->fill,
                         fill_cls == kSolid ? static_cast<uint8_t>(kSolid | kFull) : fill_cls, true);
        } else if (col->generated && !col->in_runs(k.y)) {
            for (int ly = 0; ly < kEdge; ++ly) {
                const int64_t wy = y0 + ly;
                for (int lz = 0; lz < kEdge; ++lz) {
                    for (int lx = 0; lx < kEdge; ++lx) {
                        ids[static_cast<size_t>(cell_index(lx, ly, lz))] =
                            wy >= col->top[static_cast<size_t>(lz * kEdge + lx)] ? L->air : L->fill;
                    }
                }
            }
            node_from_ids(*d, k, ids.data(), ct, L->air, L->min_y, L->max_y);
        } else {
            node_uniform(*d, k, L->air, kAir, false);   // unknown
        }
    }
    bool fresh;
    {
        std::lock_guard<std::mutex> g(L->col_m);
        fresh = column_version(L, std::make_pair(k.x, k.z)) == ver;
    }
    // Uniform level-0 nodes (sky, deep rock, uniform cubes) are cheaper to re-derive than to keep.
    if (fresh && !d->uniform && cv_cube_revision(L->world, k.x, k.y, k.z) == got_rev) {
        L->cache0.put(k, d, static_cast<uint64_t>(got_rev));
    }
    if (got_rev != 0) {
        std::lock_guard<std::mutex> g(L->watch_m);
        L->watch[Int3{k.x, k.y, k.z}] = got_rev;
    }
    return d;
}

NodePtr build_from_children(cv_lod* L, const NodeKey& k) {
    NodePtr ch[8];
    for (int i = 0; i < 8; ++i) ch[i] = get_data(L, child_of(k, i), true);
    auto d = std::make_shared<NodeData>();
    node_downsample(*d, k, ch, L->air);
    L->built_total.fetch_add(1);
    return d;
}

// A deferred-dirty node is rebuilt before it is read (its parent then becomes deferred-dirty in turn).
void settle_deferred(cv_lod* L, const NodeKey& k) {
    {
        std::lock_guard<std::mutex> g(L->def_m);
        if (L->deferred.empty() || L->deferred.erase(k) == 0) return;
    }
    if (rebuild(L, k) > 0 && k.level < L->max_level) {
        std::lock_guard<std::mutex> g(L->def_m);
        L->deferred.insert(parent_of(k));
    }
}

} // namespace

NodePtr get_data(cv_lod* L, const NodeKey& k, bool build) {
    if (k.level == 0) return l0_data(L, k);
    if (!node_in_range(L, k)) {
        auto d = std::make_shared<NodeData>();
        node_uniform(*d, k, L->air, kAir, true);
        return d;
    }
    if (k.level > L->active_level.load(std::memory_order_relaxed)) settle_deferred(L, k);
    if (NodePtr p = L->cache.get(k)) return p;
    std::lock_guard<std::mutex> g(stripe(L, k));
    if (NodePtr p = L->cache.get(k)) return p;
    if (NodePtr p = load_record(L, k)) {
        L->cache.put(k, p);
        return p;
    }
    if (!build) return nullptr;
    NodePtr p = build_from_children(L, k);
    L->cache.put(k, p);
    save_record(L, *p);
    return p;
}

// Rebuilds a dirty node from its children. 1 changed, 0 unchanged.
int rebuild(cv_lod* L, const NodeKey& k) {
    if (!node_in_range(L, k)) return 0;
    std::lock_guard<std::mutex> g(stripe(L, k));
    NodePtr old = L->cache.get(k);
    if (!old) old = load_record(L, k);
    NodePtr ch[8];
    bool same = old != nullptr;
    for (int i = 0; i < 8; ++i) {
        ch[i] = get_data(L, child_of(k, i), true);
        if (same && ch[i]->digest != old->child_digest[i]) same = false;
    }
    if (same) {
        L->cache.put(k, old);
        return 0;
    }
    auto d = std::make_shared<NodeData>();
    node_downsample(*d, k, ch, L->air);
    L->built_total.fetch_add(1);
    const bool changed = !old || old->digest != d->digest;
    L->cache.put(k, d);
    save_record(L, *d);   // child digests may differ even when the content does not
    return changed ? 1 : 0;
}

// ---- prepare (data + mesh) -------------------------------------------------------------------------------------
void prepare(cv_lod* L, const NodeKey& k, PrepResult& out) {
    out.key = k;
    NodePtr d = get_data(L, k, true);
    out.complete = d->complete;
    out.digest = d->digest;
    if (!d->complete) return;
    NodePtr nb[6];
    const NodeData* nbp[6];
    uint64_t h = mix64(d->digest);
    for (int f = 0; f < 6; ++f) {
        nb[f] = get_data(L, neighbour(k, f), true);
        nbp[f] = nb[f].get();
        h = mix64(h ^ (nb[f] ? nb[f]->digest : 0) ^ static_cast<uint64_t>(f + 1));
    }
    const bool below_solid = node_origin(k.y, k.level) <= L->min_y;
    const int cover_levels = std::min(5, std::max(0, L->max_level - k.level));
    WorldFaces faces;
    faces.cfg = mesh_config(L->world);
    faces.flags = world_block_flags(L->world);
    auto mesh = std::make_shared<MeshBuild>();
    mesh_node(*d, nbp, below_solid, cover_levels, faces, *mesh);
    mesh->input_hash = h;
    L->meshes_total.fetch_add(1);
    out.mesh = std::move(mesh);
}

} // namespace cenda::voxel::lod
