// Host mesh configuration of a world (OpenMason #143, Phase 1b): models (cv_model_define), their bindings
// to (block id, state value) (cv_block_model_bind), fluids (cv_fluid_set), cube face textures
// (cv_block_faces_set) and neighbour face rules (cv_face_rule_add, evaluated by face_rule_kinds). Copy-on-write snapshots: a mesh build takes one shared_ptr under the state mutex and
// reads it lock-free; a change copies the snapshot, edits the copy and publishes it. Changes are expected at
// startup (asset load), so the copy cost does not matter.
#include "mesh_internal.hpp"
#include "cv_error.hpp"
#include "cv_impl.hpp"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <mutex>
#include <utility>

namespace cenda::voxel {

// MeshConfig::version comes from ONE process-wide counter: a mesh workspace caches its class table under
// (block-table pointer, config version), and a pointer of a destroyed world can come back for a new world,
// so per-world counters (every world starting at 0) let a reused workspace keep a stale class table.
namespace {
std::atomic<uint64_t> g_config_versions{1};

std::shared_ptr<const MeshConfig> fresh_config() {
    auto c = std::make_shared<MeshConfig>();
    c->version = g_config_versions.fetch_add(1, std::memory_order_relaxed);
    return c;
}
} // namespace

struct MeshState {
    std::mutex m;
    std::shared_ptr<const MeshConfig> cfg = fresh_config();
};

MeshStateHolder::~MeshStateHolder() { delete p.load(); }

namespace {

MeshState& state_of(cv_world* w) {
    MeshState* s = w->mesh.p.load(std::memory_order_acquire);
    if (s != nullptr) return *s;
    auto* fresh = new MeshState();
    if (w->mesh.p.compare_exchange_strong(s, fresh, std::memory_order_acq_rel)) return *fresh;
    delete fresh;
    return *s;
}

template <class F>
int32_t mutate(cv_world* w, F&& edit) {
    MeshState& s = state_of(w);
    std::lock_guard<std::mutex> g(s.m);
    auto next = std::make_shared<MeshConfig>(*s.cfg);
    const int32_t r = edit(*next);
    if (r != CV_OK) return r;
    next->layers.clear();
    for (const auto& [id, b] : next->bindings) {
        (void)id;
        if (b.layer >= 0 && std::find(next->layers.begin(), next->layers.end(), b.layer) == next->layers.end()) {
            next->layers.push_back(b.layer);
        }
    }
    for (const auto& [id, rules] : next->face_rules) {
        (void)id;
        for (const FaceRule& fr : rules) {
            if (fr.layer >= 0 && std::find(next->layers.begin(), next->layers.end(), fr.layer) == next->layers.end()) {
                next->layers.push_back(fr.layer);
            }
        }
    }
    for (const FluidCfg& f : next->fluids) {
        if (std::find(next->layers.begin(), next->layers.end(), f.desc.layer) == next->layers.end()) {
            next->layers.push_back(f.desc.layer);
        }
    }
    std::sort(next->layers.begin(), next->layers.end());
    next->version = g_config_versions.fetch_add(1, std::memory_order_relaxed);
    s.cfg = std::move(next);
    return CV_OK;
}

void set_bit(std::array<uint64_t, 1024>& bits, uint16_t id, bool on) {
    const uint64_t m = uint64_t{1} << (id & 63);
    if (on) bits[id >> 6] |= m;
    else bits[id >> 6] &= ~m;
}

bool finite(float v) { return std::isfinite(v); }

// In-plane coordinates of plane `face` (the two axes other than the normal).
void plane_axes(int face, int* a, int* b) {
    const int n = face <= 1 ? 1 : (face <= 3 ? 2 : 0);
    *a = n == 0 ? 1 : 0;
    *b = n == 2 ? 1 : 2;
}

// Whether the quads culled by `face` cover that whole cell boundary plane (16x16 sample centres).
bool covers_plane(const std::vector<cv_model_quad>& quads, int face) {
    const int n = face <= 1 ? 1 : (face <= 3 ? 2 : 0);
    const float plane = (face == 0 || face == 3 || face == 4) ? 1.0f : 0.0f;
    int a = 0, b = 0;
    plane_axes(face, &a, &b);
    std::vector<const cv_model_quad*> on;
    for (const cv_model_quad& q : quads) {
        if (q.cull_face != face) continue;
        bool flat = true;
        for (int c = 0; c < 4; ++c) flat = flat && std::fabs(q.pos[c][n] - plane) <= 1e-4f;
        if (flat) on.push_back(&q);
    }
    if (on.empty()) return false;
    for (int i = 0; i < 16; ++i) {
        for (int j = 0; j < 16; ++j) {
            const float pa = (static_cast<float>(i) + 0.5f) / 16.0f;
            const float pb = (static_cast<float>(j) + 0.5f) / 16.0f;
            bool inside = false;
            for (const cv_model_quad* q : on) {
                int pos = 0, neg = 0;
                for (int c = 0; c < 4; ++c) {
                    const float ax = q->pos[c][a], ay = q->pos[c][b];
                    const float bx = q->pos[(c + 1) & 3][a], by = q->pos[(c + 1) & 3][b];
                    const float cross = (bx - ax) * (pb - ay) - (by - ay) * (pa - ax);
                    if (cross > 1e-6f) ++pos;
                    else if (cross < -1e-6f) ++neg;
                }
                if (pos == 0 || neg == 0) {
                    inside = true;
                    break;
                }
            }
            if (!inside) return false;
        }
    }
    return true;
}

} // namespace

int MeshConfig::fluid_index(uint16_t id) const {
    if (!is_fluid(id)) return -1;
    for (size_t i = 0; i < fluids.size(); ++i) {
        if (fluids[i].desc.fluid_id == id) return static_cast<int>(i);
    }
    return -1;
}

const Binding* MeshConfig::binding(uint16_t id) const {
    if (!is_bound(id)) return nullptr;
    auto it = bindings.find(id);
    return it == bindings.end() ? nullptr : &it->second;
}

const Model* MeshConfig::model_for(uint16_t id, uint16_t state) const {
    const Binding* b = binding(id);
    if (b == nullptr) return nullptr;
    int32_t m = b->all_model;
    if (b->layer >= 0) {
        auto it = b->by_value.find(state);
        m = it != b->by_value.end() ? it->second : b->all_model;
    }
    if (m < 0 || static_cast<size_t>(m) >= models.size()) return nullptr;
    return models[static_cast<size_t>(m)].get();
}

uint32_t face_rule_kinds(const MeshWork& w, int px, int py, int pz, int f) {
    const MeshConfig& cfg = *w.cfg;
    const uint16_t id = w.id_at(px, py, pz);
    if (!cfg.is_ruled(id)) return 0;
    const auto it = cfg.face_rules.find(id);
    if (it == cfg.face_rules.end()) return 0;
    const uint16_t nid = w.id_at(px + kFaceDx[f], py + kFaceDy[f], pz + kFaceDz[f]);
    uint32_t kinds = 0;
    for (const FaceRule& r : it->second) {
        if (((r.face_mask >> f) & 1u) == 0 || (kinds & r.kind) != 0) continue;
        if (r.layer >= 0 && w.layer_at(r.layer, px, py, pz) != r.value) continue;
        if (std::binary_search(r.ids.begin(), r.ids.end(), nid)) kinds |= r.kind;
    }
    return kinds;
}

std::shared_ptr<const MeshConfig> mesh_config(cv_world* w) {
    MeshState& s = state_of(w);
    std::lock_guard<std::mutex> g(s.m);
    return s.cfg;
}

} // namespace cenda::voxel

using namespace cenda::voxel;

extern "C" {

int32_t cv_model_define(cv_world* w, int32_t model_id, const cv_model_quad* quads, int32_t count) {
    if (w == nullptr || model_id < 0 || model_id >= kMaxModels || count < 0 || (count > 0 && quads == nullptr)) {
        cv_set_error("cv_model_define: bad arguments");
        return CV_ERR_ARG;
    }
    auto m = std::make_shared<Model>();
    m->quads.assign(quads, quads + count);
    float top = 0.0f;
    for (const cv_model_quad& q : m->quads) {
        if (q.cull_face > 6) {
            cv_set_error("cv_model_define: cull_face %d out of range", static_cast<int>(q.cull_face));
            return CV_ERR_ARG;
        }
        for (int c = 0; c < 4; ++c) {
            for (int k = 0; k < 3; ++k) {
                if (!finite(q.pos[c][k]) || q.pos[c][k] < CV_MODEL_CORNER_MIN || q.pos[c][k] > CV_MODEL_CORNER_MAX) {
                    cv_set_error("cv_model_define: corner outside [%g, %g]", static_cast<double>(CV_MODEL_CORNER_MIN),
                                 static_cast<double>(CV_MODEL_CORNER_MAX));
                    return CV_ERR_ARG;
                }
            }
            for (int k = 0; k < 2; ++k) {
                if (!finite(q.uv[c][k])) {
                    cv_set_error("cv_model_define: uv not finite");
                    return CV_ERR_ARG;
                }
            }
            top = std::max(top, q.pos[c][1]);
        }
        for (int k = 0; k < 3; ++k) {
            if (!finite(q.normal[k])) {
                cv_set_error("cv_model_define: normal not finite");
                return CV_ERR_ARG;
            }
        }
    }
    m->top = std::clamp(top, 0.0f, 1.0f);
    // A quad reaching past the neighbour cells ([-1, 2]^3) is never culled: one neighbour cell cannot hide it.
    m->cull.reserve(m->quads.size());
    for (const cv_model_quad& q : m->quads) {
        bool near = true;
        for (int c = 0; c < 4; ++c) {
            for (int k = 0; k < 3; ++k) near = near && q.pos[c][k] >= -1.0f && q.pos[c][k] <= 2.0f;
        }
        m->cull.push_back(near ? q.cull_face : uint8_t{6});
    }
    for (int f = 0; f < 6; ++f) {
        if (covers_plane(m->quads, f)) m->fills = static_cast<uint8_t>(m->fills | (1u << f));
    }
    std::shared_ptr<const Model> model = std::move(m);
    return mutate(w, [&](MeshConfig& c) {
        if (c.models.size() <= static_cast<size_t>(model_id)) c.models.resize(static_cast<size_t>(model_id) + 1);
        c.models[static_cast<size_t>(model_id)] = model;
        return CV_OK;
    });
}

int32_t cv_block_model_bind(cv_world* w, uint16_t block_id, int32_t state_layer, uint16_t state_value,
                            int32_t model_id) {
    if (w == nullptr || state_layer >= CV_MAX_LAYERS || model_id >= kMaxModels) {
        cv_set_error("cv_block_model_bind: bad arguments");
        return CV_ERR_ARG;
    }
    return mutate(w, [&](MeshConfig& c) {
        Binding& b = c.bindings[block_id];
        if (state_layer < 0) {
            b.layer = -1;
            b.by_value.clear();
            b.all_model = model_id < 0 ? -1 : model_id;
        } else {
            if (b.layer != state_layer) {
                b.layer = state_layer;
                b.by_value.clear();
                b.all_model = -1;
            }
            if (model_id < 0) b.by_value.erase(state_value);
            else b.by_value[state_value] = model_id;
        }
        const bool empty = b.all_model < 0 && b.by_value.empty();
        if (empty) c.bindings.erase(block_id);
        set_bit(c.bound, block_id, !empty);
        return CV_OK;
    });
}

int32_t cv_fluid_set(cv_world* w, const cv_fluid_desc* desc) {
    if (w == nullptr || desc == nullptr || desc->layer >= CV_MAX_LAYERS) {
        cv_set_error("cv_fluid_set: bad arguments");
        return CV_ERR_ARG;
    }
    if (desc->layer >= 0) {
        for (float s : desc->surface_by_level) {
            if (!finite(s) || s < 0.0f || s > 1.0f) {
                cv_set_error("cv_fluid_set: surface heights must be in [0, 1]");
                return CV_ERR_ARG;
            }
        }
    }
    return mutate(w, [&](MeshConfig& c) {
        auto it = std::find_if(c.fluids.begin(), c.fluids.end(),
                               [&](const FluidCfg& f) { return f.desc.fluid_id == desc->fluid_id; });
        if (desc->layer < 0) {
            if (it != c.fluids.end()) c.fluids.erase(it);
            set_bit(c.fluid_bits, desc->fluid_id, false);
            return CV_OK;
        }
        if (it == c.fluids.end()) {
            if (static_cast<int>(c.fluids.size()) >= kMaxFluids) {
                cv_set_error("cv_fluid_set: at most %d fluids", kMaxFluids);
                return CV_ERR_ARG;
            }
            c.fluids.push_back(FluidCfg{});
            it = c.fluids.end() - 1;
        }
        it->desc = *desc;
        set_bit(c.fluid_bits, desc->fluid_id, true);
        return CV_OK;
    });
}

int32_t cv_block_faces_set(cv_world* w, uint16_t block_id, const cv_face_tex* faces6) {
    if (w == nullptr || faces6 == nullptr) {
        cv_set_error("cv_block_faces_set: bad arguments");
        return CV_ERR_ARG;
    }
    for (int f = 0; f < 6; ++f) {
        if (faces6[f].orientation > 7) {
            cv_set_error("cv_block_faces_set: orientation %d out of range", static_cast<int>(faces6[f].orientation));
            return CV_ERR_ARG;
        }
    }
    return mutate(w, [&](MeshConfig& c) {
        std::array<cv_face_tex, 6> a{};
        std::copy(faces6, faces6 + 6, a.begin());
        c.faces[block_id] = a;
        return CV_OK;
    });
}

int32_t cv_face_rule_add(cv_world* w, uint16_t block_id, int32_t state_layer, uint16_t state_value,
                         uint8_t face_mask, uint32_t kind, const uint16_t* neighbour_ids, int32_t count) {
    if (w == nullptr || state_layer >= CV_MAX_LAYERS || face_mask == 0 || (face_mask & ~0x3Fu) != 0 ||
        (kind != CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN && kind != CV_FACE_RULE_OPAQUE_WHEN_NEIGHBOUR_IN) ||
        neighbour_ids == nullptr || count <= 0 || count > 65536) {
        cv_set_error("cv_face_rule_add: bad arguments");
        return CV_ERR_ARG;
    }
    FaceRule rule;
    rule.layer = state_layer < 0 ? -1 : state_layer;
    rule.value = state_layer < 0 ? uint16_t{0} : state_value;
    rule.face_mask = face_mask;
    rule.kind = static_cast<uint8_t>(kind);
    rule.ids.assign(neighbour_ids, neighbour_ids + count);
    std::sort(rule.ids.begin(), rule.ids.end());
    rule.ids.erase(std::unique(rule.ids.begin(), rule.ids.end()), rule.ids.end());
    return mutate(w, [&](MeshConfig& c) {
        c.rule_faces[block_id] = static_cast<uint8_t>(c.rule_faces[block_id] | rule.face_mask);
        c.face_rules[block_id].push_back(std::move(rule));
        set_bit(c.ruled, block_id, true);
        return CV_OK;
    });
}

int32_t cv_face_rules_clear(cv_world* w, int32_t block_id) {
    if (w == nullptr || block_id > 65535) {
        cv_set_error("cv_face_rules_clear: bad arguments");
        return CV_ERR_ARG;
    }
    return mutate(w, [&](MeshConfig& c) {
        if (block_id < 0) {
            c.face_rules.clear();
            c.ruled.fill(0);
            c.rule_faces.fill(0);
        } else {
            c.face_rules.erase(static_cast<uint16_t>(block_id));
            set_bit(c.ruled, static_cast<uint16_t>(block_id), false);
            c.rule_faces[static_cast<size_t>(block_id)] = 0;
        }
        return CV_OK;
    });
}

} // extern "C"
