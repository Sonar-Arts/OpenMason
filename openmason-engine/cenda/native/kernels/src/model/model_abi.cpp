// Cenda model (co_*): the .omo document and its .omt texture behind a C ABI. See cenda/model.h; the format is
// libomformat's omformat/omo.hpp. No exception leaves this file: every entry point catches and reports through
// co_last_error.
#include "model_internal.hpp"
#include "cenda/model.h"

#include "omformat/omo.hpp"

#include <algorithm>
#include <cstddef>
#include <cstring>
#include <exception>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace omo = cenda::omformat::omo;

struct co_doc {
    omo::Document doc;
    std::string scratch;  // CO_FIELD_WRITTEN_VERSION
};

struct co_omt {
    omo::omt::Texture tex;
};

struct co_bytes {
    std::vector<std::byte> data;
};

static_assert(sizeof(void*) == 8, "the co_* struct layouts documented in cenda/model.h are 64-bit");
static_assert(sizeof(co_geometry) == 40 && offsetof(co_geometry, position) == 16);
static_assert(sizeof(co_face_mapping) == 32 && offsetof(co_face_mapping, auto_resize) == 28);
static_assert(sizeof(co_material) == 56 && offsetof(co_material, material_id) == 24 &&
              offsetof(co_material, base_color) == 40);
static_assert(sizeof(co_part) == 112 && offsetof(co_part, origin) == 32 && offsetof(co_part, vertex_start) == 80 &&
              offsetof(co_part, locked) == 108);
static_assert(sizeof(co_bone) == 72 && offsetof(co_bone, origin) == 24 && offsetof(co_bone, endpoint) == 60);
static_assert(sizeof(co_attachment) == 72 && offsetof(co_attachment, pos) == 32 &&
              offsetof(co_attachment, reserved) == 68);
static_assert(sizeof(co_omt_info) == 16);
static_assert(sizeof(co_omt_layer) == 40 && offsetof(co_omt_layer, png_size) == 24 &&
              offsetof(co_omt_layer, visible) == 36);
static_assert(sizeof(co_omt_mapping) == 32);
static_assert(sizeof(co_omt_material) == 32 && offsetof(co_omt_material, material_id) == 24);

namespace cenda::model {

std::string& co_error_message() {
    thread_local std::string message;
    return message;
}

}  // namespace cenda::model

namespace {

std::string& last_error() { return cenda::model::co_error_message(); }

int32_t error(int32_t code, const std::string& message) {
    last_error() = message;
    return code;
}

void clear_error() { last_error().clear(); }

template <typename T>
void set_out(T* out, T value) {
    if (out != nullptr) *out = value;
}

#define CO_GUARD_BEGIN try {
#define CO_GUARD_END(fallback)                       \
    }                                                \
    catch (const std::exception& e) {                \
        last_error() = e.what();                     \
        return fallback;                             \
    }                                                \
    catch (...) {                                    \
        last_error() = "unknown error";              \
        return fallback;                             \
    }

std::span<const std::byte> span_of(const uint8_t* bytes, int64_t length) {
    return {reinterpret_cast<const std::byte*>(bytes), static_cast<std::size_t>(length)};
}

std::vector<std::byte> copy_bytes(const uint8_t* data, int64_t size) {
    std::vector<std::byte> out(static_cast<std::size_t>(size));
    if (size > 0) std::memcpy(out.data(), data, static_cast<std::size_t>(size));
    return out;
}

const uint8_t* data_of(const std::vector<std::byte>& v) {
    return v.empty() ? nullptr : reinterpret_cast<const uint8_t*>(v.data());
}

const char* c_str(const std::optional<std::string>& s) { return s ? s->c_str() : nullptr; }

std::optional<std::string> opt(const char* s) { return s == nullptr ? std::nullopt : std::optional<std::string>(s); }

std::string str(const char* s) { return s == nullptr ? std::string() : std::string(s); }

bool blank(const char* s) {
    if (s == nullptr) return true;
    for (; *s != '\0'; ++s) {
        if (*s != ' ' && *s != '\t' && *s != '\n' && *s != '\r' && *s != '\f' && *s != '\v') return false;
    }
    return true;
}

void put3(float* out, const omo::Vec3& v) {
    out[0] = v.x;
    out[1] = v.y;
    out[2] = v.z;
}

omo::Vec3 vec(const float* v) { return {v[0], v[1], v[2]}; }

// Record list getters: copy min(count, capacity), return count.
template <typename Item, typename Out, typename Fill>
int32_t copy_out(const std::vector<Item>& items, Out* out, int32_t capacity, Fill fill) {
    if (capacity < 0) return error(CO_ERR_ARG, "negative capacity");
    if (out != nullptr) {
        const std::size_t n = std::min(items.size(), static_cast<std::size_t>(capacity));
        for (std::size_t i = 0; i < n; ++i) {
            out[i] = Out{};
            fill(items[i], out[i]);
        }
    }
    return static_cast<int32_t>(items.size());
}

bool bad_list(const void* items, int32_t count) { return count < 0 || (count > 0 && items == nullptr); }

// Offsets: count+1 entries, start at 0, non-decreasing, end within limit (the end is returned).
bool valid_offsets(const int32_t* offsets, int32_t count, int32_t* out_end) {
    if (count == 0) {
        *out_end = offsets == nullptr ? 0 : offsets[0];
        return offsets == nullptr || offsets[0] == 0;
    }
    if (offsets == nullptr || offsets[0] != 0) return false;
    for (int32_t i = 0; i < count; ++i) {
        if (offsets[i + 1] < offsets[i]) return false;
    }
    *out_end = offsets[count];
    return true;
}

bool image_name(std::string_view name) { return name.starts_with("material_") && name.ends_with(".png"); }

std::vector<omo::Image>* entries_of(co_doc* d, int32_t kind) {
    if (kind == CO_ENTRIES_IMAGES) return &d->doc.images;
    if (kind == CO_ENTRIES_EXTRA) return &d->doc.extra_entries;
    return nullptr;
}

const std::vector<omo::Image>* entries_of(const co_doc* d, int32_t kind) {
    return entries_of(const_cast<co_doc*>(d), kind);
}

omo::Mesh& mesh_of(co_doc* d) {
    if (!d->doc.mesh) d->doc.mesh = omo::Mesh{};
    return *d->doc.mesh;
}

omo::FaceTextures& face_textures_of(co_doc* d) {
    if (!d->doc.face_textures) d->doc.face_textures = omo::FaceTextures{};
    return *d->doc.face_textures;
}

template <typename Handle>
int32_t null_handle(const Handle* h, const char* fn) {
    if (h == nullptr) return error(CO_ERR_ARG, std::string(fn) + ": null handle");
    return CO_OK;
}

}  // namespace

extern "C" {

int32_t co_abi_version(void) { return CO_ABI_VERSION; }

const char* co_last_error(void) { return last_error().c_str(); }

const uint8_t* co_bytes_data(const co_bytes* b) { return b == nullptr ? nullptr : data_of(b->data); }

int64_t co_bytes_size(const co_bytes* b) { return b == nullptr ? 0 : static_cast<int64_t>(b->data.size()); }

void co_bytes_destroy(co_bytes* b) { delete b; }

int32_t co_version_compare(const char* a, const char* b, int32_t* out_order) {
    if (a == nullptr || b == nullptr) return error(CO_ERR_ARG, "co_version_compare: null version");
    if (!omo::parse_version(a) || !omo::parse_version(b)) {
        return error(CO_ERR_ARG, std::string("co_version_compare: not <major>.<minor>: \"") + a + "\" / \"" + b + "\"");
    }
    CO_GUARD_BEGIN
    clear_error();
    set_out(out_order, static_cast<int32_t>(omo::compare_versions(a, b)));
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

// --- Lifetime and I/O ---------------------------------------------------------------------------------------

co_doc* co_doc_create(void) {
    CO_GUARD_BEGIN
    clear_error();
    return new co_doc();
    CO_GUARD_END(nullptr)
}

co_doc* co_doc_read(const uint8_t* bytes, int64_t length, int32_t* out_result) {
    set_out(out_result, int32_t{CO_OK});
    if (bytes == nullptr || length <= 0) {
        set_out(out_result, int32_t{CO_ERR_ARG});
        error(CO_ERR_ARG, "co_doc_read: no bytes");
        return nullptr;
    }
    try {
        clear_error();
        auto* d = new co_doc();
        try {
            d->doc = omo::read(span_of(bytes, length));
        } catch (...) {
            delete d;
            throw;
        }
        return d;
    } catch (const omo::version_error& e) {
        set_out(out_result, int32_t{CO_ERR_VERSION});
        error(CO_ERR_VERSION, e.what());
    } catch (const std::exception& e) {
        set_out(out_result, int32_t{CO_ERR_FORMAT});
        error(CO_ERR_FORMAT, e.what());
    } catch (...) {
        set_out(out_result, int32_t{CO_ERR_FORMAT});
        error(CO_ERR_FORMAT, "co_doc_read: unknown error");
    }
    return nullptr;
}

co_doc* co_doc_clone(const co_doc* d) {
    if (d == nullptr) {
        error(CO_ERR_ARG, "co_doc_clone: null handle");
        return nullptr;
    }
    CO_GUARD_BEGIN
    clear_error();
    auto* c = new co_doc();
    c->doc = d->doc;
    return c;
    CO_GUARD_END(nullptr)
}

void co_doc_destroy(co_doc* d) { delete d; }

co_bytes* co_doc_write(const co_doc* d) {
    if (d == nullptr) {
        error(CO_ERR_ARG, "co_doc_write: null handle");
        return nullptr;
    }
    CO_GUARD_BEGIN
    clear_error();
    auto* b = new co_bytes();
    try {
        b->data = omo::write(d->doc);
    } catch (...) {
        delete b;
        throw;
    }
    return b;
    CO_GUARD_END(nullptr)
}

co_bytes* co_doc_write_manifest(const co_doc* d) {
    if (d == nullptr) {
        error(CO_ERR_ARG, "co_doc_write_manifest: null handle");
        return nullptr;
    }
    CO_GUARD_BEGIN
    clear_error();
    const std::string text = omo::write_manifest(d->doc);
    auto* b = new co_bytes();
    b->data = copy_bytes(reinterpret_cast<const uint8_t*>(text.data()), static_cast<int64_t>(text.size()));
    return b;
    CO_GUARD_END(nullptr)
}

// --- Strings and flags ---------------------------------------------------------------------------------------

const char* co_doc_get_string(const co_doc* d, int32_t field) {
    if (d == nullptr) return nullptr;
    switch (field) {
        case CO_FIELD_VERSION:
            return d->doc.version.c_str();
        case CO_FIELD_OBJECT_NAME:
            return d->doc.object_name.c_str();
        case CO_FIELD_MODEL_TYPE:
            return d->doc.model_type.c_str();
        case CO_FIELD_TEXTURE_FILE:
            return d->doc.texture_file.c_str();
        case CO_FIELD_UV_MODE:
            return d->doc.mesh ? c_str(d->doc.mesh->uv_mode) : nullptr;
        case CO_FIELD_WRITTEN_VERSION: {
            try {
                auto* self = const_cast<co_doc*>(d);
                self->scratch = d->doc.written_version();
                return self->scratch.c_str();
            } catch (const std::exception& e) {
                last_error() = e.what();
                return nullptr;
            }
        }
        default:
            last_error() = "co_doc_get_string: unknown field " + std::to_string(field);
            return nullptr;
    }
}

int32_t co_doc_set_string(co_doc* d, int32_t field, const char* value) {
    if (int32_t rc = null_handle(d, "co_doc_set_string")) return rc;
    CO_GUARD_BEGIN
    clear_error();
    switch (field) {
        case CO_FIELD_VERSION: {
            const auto v = omo::parse_version(str(value));
            if (!v || v->major != omo::kVersionMajor) {
                return error(CO_ERR_ARG, "co_doc_set_string: version \"" + str(value) + "\" is not 1.<minor>");
            }
            d->doc.version = value;
            return CO_OK;
        }
        case CO_FIELD_OBJECT_NAME:
            d->doc.object_name = str(value);
            return CO_OK;
        case CO_FIELD_MODEL_TYPE:
            d->doc.model_type = str(value);
            return CO_OK;
        case CO_FIELD_TEXTURE_FILE:
            d->doc.texture_file = str(value);
            return CO_OK;
        case CO_FIELD_UV_MODE:
            mesh_of(d).uv_mode = opt(value);
            return CO_OK;
        default:
            return error(CO_ERR_ARG, "co_doc_set_string: field " + std::to_string(field) + " is not writable");
    }
    CO_GUARD_END(CO_ERR_ARG)
}

int32_t co_doc_flags(const co_doc* d) {
    if (int32_t rc = null_handle(d, "co_doc_flags")) return rc;
    int32_t flags = 0;
    if (d->doc.mesh) flags |= CO_DOC_HAS_MESH;
    if (d->doc.face_textures) flags |= CO_DOC_HAS_FACE_TEXTURES;
    if (d->doc.crlf) flags |= CO_DOC_CRLF;
    if (d->doc.has_1_9_data()) flags |= CO_DOC_HAS_1_9_DATA;
    return flags;
}

int32_t co_doc_set_flags(co_doc* d, int32_t flags) {
    if (int32_t rc = null_handle(d, "co_doc_set_flags")) return rc;
    CO_GUARD_BEGIN
    clear_error();
    if ((flags & CO_DOC_HAS_MESH) == 0) {
        d->doc.mesh.reset();
    } else if (!d->doc.mesh) {
        d->doc.mesh = omo::Mesh{};
    }
    if ((flags & CO_DOC_HAS_FACE_TEXTURES) == 0) {
        d->doc.face_textures.reset();
    } else if (!d->doc.face_textures) {
        d->doc.face_textures = omo::FaceTextures{};
    }
    d->doc.crlf = (flags & CO_DOC_CRLF) != 0;
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

// --- Geometry and model transform ----------------------------------------------------------------------------

int32_t co_doc_geometry_get(const co_doc* d, co_geometry* out) {
    if (int32_t rc = null_handle(d, "co_doc_geometry_get")) return rc;
    if (out == nullptr) return error(CO_ERR_ARG, "co_doc_geometry_get: null output");
    const omo::Geometry& g = d->doc.geometry;
    *out = co_geometry{g.width, g.height, g.depth, 0, {g.position.x, g.position.y, g.position.z}};
    return CO_OK;
}

int32_t co_doc_geometry_set(co_doc* d, const co_geometry* g) {
    if (int32_t rc = null_handle(d, "co_doc_geometry_set")) return rc;
    if (g == nullptr) return error(CO_ERR_ARG, "co_doc_geometry_set: null geometry");
    d->doc.geometry = omo::Geometry{g->width, g->height, g->depth, {g->position[0], g->position[1], g->position[2]}};
    return CO_OK;
}

int32_t co_doc_model_transform_get(const co_doc* d, float* out9) {
    if (int32_t rc = null_handle(d, "co_doc_model_transform_get")) return rc;
    if (out9 == nullptr) return error(CO_ERR_ARG, "co_doc_model_transform_get: null output");
    put3(out9, d->doc.model_transform.pos);
    put3(out9 + 3, d->doc.model_transform.rot);
    put3(out9 + 6, d->doc.model_transform.scale);
    return CO_OK;
}

int32_t co_doc_model_transform_set(co_doc* d, const float* trs9) {
    if (int32_t rc = null_handle(d, "co_doc_model_transform_set")) return rc;
    if (trs9 == nullptr) return error(CO_ERR_ARG, "co_doc_model_transform_set: null transform");
    d->doc.model_transform = omo::ModelTransform{vec(trs9), vec(trs9 + 3), vec(trs9 + 6)};
    return CO_OK;
}

// --- Mesh ------------------------------------------------------------------------------------------------------

int64_t co_doc_mesh_array(const co_doc* d, int32_t which, const void** out_data) {
    set_out(out_data, static_cast<const void*>(nullptr));
    if (d == nullptr) {
        error(CO_ERR_ARG, "co_doc_mesh_array: null handle");
        return -1;
    }
    if (!d->doc.mesh) return -1;
    const omo::Mesh& m = *d->doc.mesh;
    auto give = [&](const auto& arr) -> int64_t {
        if (!arr) return -1;
        set_out(out_data, static_cast<const void*>(arr->data()));
        return static_cast<int64_t>(arr->size());
    };
    switch (which) {
        case CO_MESH_VERTICES:
            return give(m.vertices);
        case CO_MESH_TEX_COORDS:
            return give(m.tex_coords);
        case CO_MESH_INDICES:
            return give(m.indices);
        case CO_MESH_TRIANGLE_FACE_IDS:
            return give(m.triangle_to_face_id);
        default:
            error(CO_ERR_ARG, "co_doc_mesh_array: unknown array " + std::to_string(which));
            return -1;
    }
}

int32_t co_doc_mesh_set_array(co_doc* d, int32_t which, const void* data, int64_t count) {
    if (int32_t rc = null_handle(d, "co_doc_mesh_set_array")) return rc;
    if (which < CO_MESH_VERTICES || which > CO_MESH_TRIANGLE_FACE_IDS) {
        return error(CO_ERR_ARG, "co_doc_mesh_set_array: unknown array " + std::to_string(which));
    }
    CO_GUARD_BEGIN
    clear_error();
    omo::Mesh& m = mesh_of(d);
    const bool null = data == nullptr || count < 0;
    const auto n = null ? std::size_t{0} : static_cast<std::size_t>(count);
    auto assign = [&](auto& arr) {
        using V = typename std::remove_reference_t<decltype(arr)>::value_type;
        if (null) {
            arr.reset();
            return;
        }
        V values(n);
        if (n > 0) std::memcpy(values.data(), data, n * sizeof(typename V::value_type));
        arr = std::move(values);
    };
    switch (which) {
        case CO_MESH_VERTICES:
            assign(m.vertices);
            break;
        case CO_MESH_TEX_COORDS:
            assign(m.tex_coords);
            break;
        case CO_MESH_INDICES:
            assign(m.indices);
            break;
        default:
            assign(m.triangle_to_face_id);
            break;
    }
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

// --- Face textures ---------------------------------------------------------------------------------------------

int32_t co_doc_face_mappings_get(const co_doc* d, co_face_mapping* out, int32_t capacity) {
    if (int32_t rc = null_handle(d, "co_doc_face_mappings_get")) return rc;
    static const std::vector<omo::FaceMapping> kNone;
    const auto& items = d->doc.face_textures ? d->doc.face_textures->mappings : kNone;
    return copy_out(items, out, capacity, [](const omo::FaceMapping& m, co_face_mapping& o) {
        o = co_face_mapping{m.face_id, m.material_id, m.u0, m.v0, m.u1, m.v1, m.uv_rotation_degrees,
                            m.auto_resize ? 1 : 0};
    });
}

int32_t co_doc_face_mappings_set(co_doc* d, const co_face_mapping* mappings, int32_t count) {
    if (int32_t rc = null_handle(d, "co_doc_face_mappings_set")) return rc;
    if (bad_list(mappings, count)) return error(CO_ERR_ARG, "co_doc_face_mappings_set: bad list");
    CO_GUARD_BEGIN
    clear_error();
    std::vector<omo::FaceMapping> items;
    items.reserve(static_cast<std::size_t>(count));
    for (int32_t i = 0; i < count; ++i) {
        const co_face_mapping& m = mappings[i];
        items.push_back({m.face_id, m.material_id, m.u0, m.v0, m.u1, m.v1, m.uv_rotation_degrees, m.auto_resize != 0});
    }
    face_textures_of(d).mappings = std::move(items);
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

int32_t co_doc_materials_get(const co_doc* d, co_material* out, int32_t capacity) {
    if (int32_t rc = null_handle(d, "co_doc_materials_get")) return rc;
    static const std::vector<omo::Material> kNone;
    const auto& items = d->doc.face_textures ? d->doc.face_textures->materials : kNone;
    return copy_out(items, out, capacity, [](const omo::Material& m, co_material& o) {
        o.name = c_str(m.name);
        o.texture_file = c_str(m.texture_file);
        o.render_layer = c_str(m.render_layer);
        o.material_id = m.material_id;
        o.emissive = m.emissive ? 1 : 0;
        o.tint_color = m.tint_color;
        o.has_base_color = m.base_color ? 1 : 0;
        if (m.base_color) std::copy(m.base_color->begin(), m.base_color->end(), o.base_color);
    });
}

int32_t co_doc_materials_set(co_doc* d, const co_material* materials, int32_t count) {
    if (int32_t rc = null_handle(d, "co_doc_materials_set")) return rc;
    if (bad_list(materials, count)) return error(CO_ERR_ARG, "co_doc_materials_set: bad list");
    CO_GUARD_BEGIN
    clear_error();
    std::vector<omo::Material> items;
    items.reserve(static_cast<std::size_t>(count));
    for (int32_t i = 0; i < count; ++i) {
        const co_material& m = materials[i];
        omo::Material mat;
        mat.material_id = m.material_id;
        mat.name = opt(m.name);
        mat.texture_file = opt(m.texture_file);
        mat.render_layer = opt(m.render_layer);
        mat.emissive = m.emissive != 0;
        mat.tint_color = m.tint_color;
        if (m.has_base_color != 0) {
            mat.base_color = std::array<float, 4>{m.base_color[0], m.base_color[1], m.base_color[2], m.base_color[3]};
        }
        items.push_back(std::move(mat));
    }
    face_textures_of(d).materials = std::move(items);
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

// --- Parts, bones, attachment points ---------------------------------------------------------------------------

int32_t co_doc_parts_get(const co_doc* d, co_part* out, int32_t capacity) {
    if (int32_t rc = null_handle(d, "co_doc_parts_get")) return rc;
    return copy_out(d->doc.parts, out, capacity, [](const omo::Part& p, co_part& o) {
        o.id = p.id.c_str();
        o.name = p.name.c_str();
        o.parent_id = c_str(p.parent_id);
        o.bone_id = c_str(p.bone_id);
        put3(o.origin, p.origin);
        put3(o.pos, p.pos);
        put3(o.rot, p.rot);
        put3(o.scale, p.scale);
        o.vertex_start = p.vertex_start;
        o.vertex_count = p.vertex_count;
        o.index_start = p.index_start;
        o.index_count = p.index_count;
        o.face_start = p.face_start;
        o.face_count = p.face_count;
        o.visible = p.visible ? 1 : 0;
        o.locked = p.locked ? 1 : 0;
    });
}

int32_t co_doc_parts_set(co_doc* d, const co_part* parts, int32_t count) {
    if (int32_t rc = null_handle(d, "co_doc_parts_set")) return rc;
    if (bad_list(parts, count)) return error(CO_ERR_ARG, "co_doc_parts_set: bad list");
    CO_GUARD_BEGIN
    clear_error();
    std::vector<omo::Part> items;
    items.reserve(static_cast<std::size_t>(count));
    for (int32_t i = 0; i < count; ++i) {
        const co_part& p = parts[i];
        if (blank(p.id)) return error(CO_ERR_ARG, "co_doc_parts_set: part " + std::to_string(i) + " has no id");
        omo::Part part;
        part.id = p.id;
        part.name = str(p.name);
        part.origin = vec(p.origin);
        part.pos = vec(p.pos);
        part.rot = vec(p.rot);
        part.scale = vec(p.scale);
        part.vertex_start = p.vertex_start;
        part.vertex_count = p.vertex_count;
        part.index_start = p.index_start;
        part.index_count = p.index_count;
        part.face_start = p.face_start;
        part.face_count = p.face_count;
        part.visible = p.visible != 0;
        part.locked = p.locked != 0;
        part.parent_id = opt(p.parent_id);
        part.bone_id = opt(p.bone_id);
        items.push_back(std::move(part));
    }
    d->doc.parts = std::move(items);
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

int32_t co_doc_bones_get(const co_doc* d, co_bone* out, int32_t capacity) {
    if (int32_t rc = null_handle(d, "co_doc_bones_get")) return rc;
    return copy_out(d->doc.bones, out, capacity, [](const omo::Bone& b, co_bone& o) {
        o.id = b.id.c_str();
        o.name = b.name.c_str();
        o.parent_bone_id = c_str(b.parent_bone_id);
        put3(o.origin, b.origin);
        put3(o.pos, b.pos);
        put3(o.rot, b.rot);
        put3(o.endpoint, b.endpoint);
    });
}

int32_t co_doc_bones_set(co_doc* d, const co_bone* bones, int32_t count) {
    if (int32_t rc = null_handle(d, "co_doc_bones_set")) return rc;
    if (bad_list(bones, count)) return error(CO_ERR_ARG, "co_doc_bones_set: bad list");
    CO_GUARD_BEGIN
    clear_error();
    std::vector<omo::Bone> items;
    items.reserve(static_cast<std::size_t>(count));
    for (int32_t i = 0; i < count; ++i) {
        const co_bone& b = bones[i];
        if (blank(b.id)) return error(CO_ERR_ARG, "co_doc_bones_set: bone " + std::to_string(i) + " has no id");
        omo::Bone bone;
        bone.id = b.id;
        bone.name = b.name == nullptr ? bone.id : std::string(b.name);
        bone.parent_bone_id = opt(b.parent_bone_id);
        bone.origin = vec(b.origin);
        bone.pos = vec(b.pos);
        bone.rot = vec(b.rot);
        bone.endpoint = vec(b.endpoint);
        items.push_back(std::move(bone));
    }
    d->doc.bones = std::move(items);
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

int32_t co_doc_attachments_get(const co_doc* d, co_attachment* out, int32_t capacity) {
    if (int32_t rc = null_handle(d, "co_doc_attachments_get")) return rc;
    return copy_out(d->doc.attachment_points, out, capacity, [](const omo::AttachmentPoint& a, co_attachment& o) {
        o.id = a.id.c_str();
        o.name = a.name.c_str();
        o.parent_part_id = c_str(a.parent_part_id);
        o.parent_part_name = c_str(a.parent_part_name);
        put3(o.pos, a.pos);
        put3(o.rot, a.rot);
        put3(o.scale, a.scale);
    });
}

int32_t co_doc_attachments_set(co_doc* d, const co_attachment* attachments, int32_t count) {
    if (int32_t rc = null_handle(d, "co_doc_attachments_set")) return rc;
    if (bad_list(attachments, count)) return error(CO_ERR_ARG, "co_doc_attachments_set: bad list");
    CO_GUARD_BEGIN
    clear_error();
    std::vector<omo::AttachmentPoint> items;
    items.reserve(static_cast<std::size_t>(count));
    for (int32_t i = 0; i < count; ++i) {
        const co_attachment& a = attachments[i];
        if (blank(a.id)) {
            return error(CO_ERR_ARG, "co_doc_attachments_set: attachment " + std::to_string(i) + " has no id");
        }
        omo::AttachmentPoint ap;
        ap.id = a.id;
        ap.name = str(a.name);
        ap.parent_part_id = opt(a.parent_part_id);
        ap.parent_part_name = opt(a.parent_part_name);
        ap.pos = vec(a.pos);
        ap.rot = vec(a.rot);
        ap.scale = vec(a.scale);
        items.push_back(std::move(ap));
    }
    d->doc.attachment_points = std::move(items);
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

// --- Skin --------------------------------------------------------------------------------------------------------

int32_t co_doc_skin_counts(const co_doc* d, int32_t* out_joints, int32_t* out_parts, int32_t* out_rows,
                           int32_t* out_influences) {
    if (int32_t rc = null_handle(d, "co_doc_skin_counts")) return rc;
    const omo::Skin& s = d->doc.skin;
    std::size_t rows = 0;
    std::size_t influences = 0;
    for (const omo::SkinPart& p : s.parts) {
        rows += p.vertices.size();
        influences += p.joints.size();
    }
    set_out(out_joints, static_cast<int32_t>(s.joints.size()));
    set_out(out_parts, static_cast<int32_t>(s.parts.size()));
    set_out(out_rows, static_cast<int32_t>(rows));
    set_out(out_influences, static_cast<int32_t>(influences));
    return CO_OK;
}

int32_t co_doc_skin_get(const co_doc* d, const char** joint_ids, const char** part_ids, int32_t* part_rows,
                        int32_t* row_vertices, int32_t* row_influences, int32_t* joints, float* weights) {
    if (int32_t rc = null_handle(d, "co_doc_skin_get")) return rc;
    const omo::Skin& s = d->doc.skin;
    if (joint_ids != nullptr) {
        for (std::size_t j = 0; j < s.joints.size(); ++j) joint_ids[j] = s.joints[j].c_str();
    }
    int32_t row = 0;
    int32_t influence = 0;
    if (part_rows != nullptr) part_rows[0] = 0;
    if (row_influences != nullptr) row_influences[0] = 0;
    for (std::size_t p = 0; p < s.parts.size(); ++p) {
        const omo::SkinPart& sp = s.parts[p];
        if (part_ids != nullptr) part_ids[p] = sp.part_id.c_str();
        for (std::size_t r = 0; r < sp.vertices.size(); ++r) {
            if (row_vertices != nullptr) row_vertices[row] = sp.vertices[r];
            const auto begin = static_cast<std::size_t>(sp.offsets[r]);
            const auto end = static_cast<std::size_t>(sp.offsets[r + 1]);
            for (std::size_t k = begin; k < end; ++k) {
                if (joints != nullptr) joints[influence] = sp.joints[k];
                if (weights != nullptr) weights[influence] = sp.weights[k];
                ++influence;
            }
            ++row;
            if (row_influences != nullptr) row_influences[row] = influence;
        }
        if (part_rows != nullptr) part_rows[p + 1] = row;
    }
    return CO_OK;
}

int32_t co_doc_skin_set(co_doc* d, int32_t joint_count, const char* const* joint_ids, int32_t part_count,
                        const char* const* part_ids, const int32_t* part_rows, const int32_t* row_vertices,
                        const int32_t* row_influences, const int32_t* joints, const float* weights) {
    if (int32_t rc = null_handle(d, "co_doc_skin_set")) return rc;
    CO_GUARD_BEGIN
    clear_error();
    if (part_count == 0) {
        d->doc.skin = omo::Skin{};
        return CO_OK;
    }
    if (bad_list(joint_ids, joint_count) || bad_list(part_ids, part_count)) {
        return error(CO_ERR_ARG, "co_doc_skin_set: bad joint or part list");
    }
    int32_t rows = 0;
    if (!valid_offsets(part_rows, part_count, &rows)) return error(CO_ERR_ARG, "co_doc_skin_set: bad part_rows");
    int32_t influences = 0;
    if (rows > 0 && (row_vertices == nullptr || !valid_offsets(row_influences, rows, &influences))) {
        return error(CO_ERR_ARG, "co_doc_skin_set: bad rows / row_influences");
    }
    if (influences > 0 && (joints == nullptr || weights == nullptr)) {
        return error(CO_ERR_ARG, "co_doc_skin_set: missing joints / weights");
    }
    omo::Skin skin;
    for (int32_t j = 0; j < joint_count; ++j) {
        if (joint_ids[j] == nullptr) return error(CO_ERR_ARG, "co_doc_skin_set: null joint id");
        skin.joints.emplace_back(joint_ids[j]);
    }
    for (int32_t p = 0; p < part_count; ++p) {
        if (part_ids[p] == nullptr) return error(CO_ERR_ARG, "co_doc_skin_set: null part id");
        omo::SkinPart sp;
        sp.part_id = part_ids[p];
        for (int32_t r = part_rows[p]; r < part_rows[p + 1]; ++r) {
            if (row_influences[r + 1] == row_influences[r]) {
                return error(CO_ERR_ARG, "co_doc_skin_set: row " + std::to_string(r) + " has no influences");
            }
            if (sp.offsets.empty()) sp.offsets.push_back(0);
            sp.vertices.push_back(row_vertices[r]);
            for (int32_t k = row_influences[r]; k < row_influences[r + 1]; ++k) {
                if (joints[k] < 0 || joints[k] >= joint_count) {
                    return error(CO_ERR_ARG, "co_doc_skin_set: joint index out of range in row " + std::to_string(r));
                }
                sp.joints.push_back(joints[k]);
                sp.weights.push_back(weights[k]);
            }
            sp.offsets.push_back(static_cast<int32_t>(sp.joints.size()));
        }
        if (!sp.vertices.empty()) skin.parts.push_back(std::move(sp));
    }
    if (skin.empty()) skin = omo::Skin{};
    d->doc.skin = std::move(skin);
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

// --- Shading -------------------------------------------------------------------------------------------------------

int64_t co_doc_shading_smooth(const co_doc* d, const uint32_t** out_words) {
    set_out(out_words, static_cast<const uint32_t*>(nullptr));
    if (d == nullptr) return error(CO_ERR_ARG, "co_doc_shading_smooth: null handle");
    const auto& w = d->doc.shading.smooth;
    if (!w.empty()) set_out(out_words, w.data());
    return static_cast<int64_t>(w.size());
}

int32_t co_doc_shading_set_smooth(co_doc* d, const uint32_t* words, int64_t word_count) {
    if (int32_t rc = null_handle(d, "co_doc_shading_set_smooth")) return rc;
    if (word_count < 0 || (word_count > 0 && words == nullptr)) {
        return error(CO_ERR_ARG, "co_doc_shading_set_smooth: bad words");
    }
    CO_GUARD_BEGIN
    clear_error();
    std::vector<uint32_t> w(words, words + word_count);
    while (!w.empty() && w.back() == 0) w.pop_back();
    d->doc.shading.smooth = std::move(w);
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

int64_t co_doc_shading_sharp_edges(const co_doc* d, const uint32_t** out_pairs) {
    set_out(out_pairs, static_cast<const uint32_t*>(nullptr));
    if (d == nullptr) return error(CO_ERR_ARG, "co_doc_shading_sharp_edges: null handle");
    const auto& e = d->doc.shading.sharp_edges;
    if (!e.empty()) set_out(out_pairs, e.data());
    return static_cast<int64_t>(e.size() / 2);
}

int32_t co_doc_shading_set_sharp_edges(co_doc* d, const uint32_t* pairs, int64_t edge_count) {
    if (int32_t rc = null_handle(d, "co_doc_shading_set_sharp_edges")) return rc;
    if (edge_count < 0 || (edge_count > 0 && pairs == nullptr)) {
        return error(CO_ERR_ARG, "co_doc_shading_set_sharp_edges: bad pairs");
    }
    CO_GUARD_BEGIN
    clear_error();
    for (int64_t i = 0; i < edge_count * 2; ++i) {
        if (pairs[i] > 0x7FFFFFFFu) return error(CO_ERR_ARG, "co_doc_shading_set_sharp_edges: vertex out of range");
    }
    d->doc.shading.sharp_edges.assign(pairs, pairs + edge_count * 2);
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

int32_t co_doc_shading_auto_smooth(const co_doc* d, float* out_degrees) {
    if (int32_t rc = null_handle(d, "co_doc_shading_auto_smooth")) return rc;
    if (!d->doc.shading.auto_smooth_angle) return 0;
    set_out(out_degrees, *d->doc.shading.auto_smooth_angle);
    return 1;
}

int32_t co_doc_shading_set_auto_smooth(co_doc* d, const float* degrees) {
    if (int32_t rc = null_handle(d, "co_doc_shading_set_auto_smooth")) return rc;
    d->doc.shading.auto_smooth_angle = degrees == nullptr ? std::nullopt : std::optional<float>(*degrees);
    return CO_OK;
}

int64_t co_doc_shading_custom_normals(const co_doc* d, const int32_t** out_vertices, const int16_t** out_oct) {
    set_out(out_vertices, static_cast<const int32_t*>(nullptr));
    set_out(out_oct, static_cast<const int16_t*>(nullptr));
    if (d == nullptr) return error(CO_ERR_ARG, "co_doc_shading_custom_normals: null handle");
    const omo::Shading& s = d->doc.shading;
    if (!s.normal_vertices.empty()) {
        set_out(out_vertices, s.normal_vertices.data());
        set_out(out_oct, s.normal_oct.data());
    }
    return static_cast<int64_t>(s.normal_vertices.size());
}

int32_t co_doc_shading_set_custom_normals(co_doc* d, const int32_t* vertices, const int16_t* oct, int64_t count) {
    if (int32_t rc = null_handle(d, "co_doc_shading_set_custom_normals")) return rc;
    if (count < 0 || (count > 0 && (vertices == nullptr || oct == nullptr))) {
        return error(CO_ERR_ARG, "co_doc_shading_set_custom_normals: bad rows");
    }
    CO_GUARD_BEGIN
    clear_error();
    for (int64_t i = 0; i < count; ++i) {
        if (vertices[i] < 0) return error(CO_ERR_ARG, "co_doc_shading_set_custom_normals: negative vertex");
    }
    d->doc.shading.normal_vertices.assign(vertices, vertices + count);
    d->doc.shading.normal_oct.assign(oct, oct + count * 2);
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

// --- Vertex groups --------------------------------------------------------------------------------------------------

int32_t co_doc_vertex_group_counts(const co_doc* d, int32_t* out_groups, int32_t* out_parts, int32_t* out_rows) {
    if (int32_t rc = null_handle(d, "co_doc_vertex_group_counts")) return rc;
    std::size_t parts = 0;
    std::size_t rows = 0;
    for (const omo::VertexGroup& g : d->doc.vertex_groups) {
        parts += g.parts.size();
        for (const omo::GroupPart& p : g.parts) rows += p.vertices.size();
    }
    set_out(out_groups, static_cast<int32_t>(d->doc.vertex_groups.size()));
    set_out(out_parts, static_cast<int32_t>(parts));
    set_out(out_rows, static_cast<int32_t>(rows));
    return CO_OK;
}

int32_t co_doc_vertex_groups_get(const co_doc* d, const char** names, int32_t* group_parts, const char** part_ids,
                                 int32_t* part_rows, int32_t* vertices, float* weights) {
    if (int32_t rc = null_handle(d, "co_doc_vertex_groups_get")) return rc;
    int32_t part = 0;
    int32_t row = 0;
    if (group_parts != nullptr) group_parts[0] = 0;
    if (part_rows != nullptr) part_rows[0] = 0;
    for (std::size_t g = 0; g < d->doc.vertex_groups.size(); ++g) {
        const omo::VertexGroup& group = d->doc.vertex_groups[g];
        if (names != nullptr) names[g] = group.name.c_str();
        for (const omo::GroupPart& p : group.parts) {
            if (part_ids != nullptr) part_ids[part] = p.part_id.c_str();
            for (std::size_t r = 0; r < p.vertices.size(); ++r) {
                if (vertices != nullptr) vertices[row] = p.vertices[r];
                if (weights != nullptr) weights[row] = p.weights[r];
                ++row;
            }
            ++part;
            if (part_rows != nullptr) part_rows[part] = row;
        }
        if (group_parts != nullptr) group_parts[g + 1] = part;
    }
    return CO_OK;
}

int32_t co_doc_vertex_groups_set(co_doc* d, int32_t group_count, const char* const* names, const int32_t* group_parts,
                                 const char* const* part_ids, const int32_t* part_rows, const int32_t* vertices,
                                 const float* weights) {
    if (int32_t rc = null_handle(d, "co_doc_vertex_groups_set")) return rc;
    CO_GUARD_BEGIN
    clear_error();
    if (bad_list(names, group_count)) return error(CO_ERR_ARG, "co_doc_vertex_groups_set: bad group list");
    int32_t parts = 0;
    if (group_count > 0 && !valid_offsets(group_parts, group_count, &parts)) {
        return error(CO_ERR_ARG, "co_doc_vertex_groups_set: bad group_parts");
    }
    int32_t rows = 0;
    if (parts > 0 && (part_ids == nullptr || !valid_offsets(part_rows, parts, &rows))) {
        return error(CO_ERR_ARG, "co_doc_vertex_groups_set: bad part_ids / part_rows");
    }
    if (rows > 0 && (vertices == nullptr || weights == nullptr)) {
        return error(CO_ERR_ARG, "co_doc_vertex_groups_set: missing vertices / weights");
    }
    std::vector<omo::VertexGroup> groups;
    groups.reserve(static_cast<std::size_t>(group_count));
    for (int32_t g = 0; g < group_count; ++g) {
        if (names[g] == nullptr) return error(CO_ERR_ARG, "co_doc_vertex_groups_set: null group name");
        omo::VertexGroup group;
        group.name = names[g];
        for (int32_t p = group_parts[g]; p < group_parts[g + 1]; ++p) {
            if (part_ids[p] == nullptr) return error(CO_ERR_ARG, "co_doc_vertex_groups_set: null part id");
            omo::GroupPart gp;
            gp.part_id = part_ids[p];
            for (int32_t r = part_rows[p]; r < part_rows[p + 1]; ++r) {
                if (vertices[r] < 0) return error(CO_ERR_ARG, "co_doc_vertex_groups_set: negative vertex");
                gp.vertices.push_back(vertices[r]);
                gp.weights.push_back(weights[r]);
            }
            group.parts.push_back(std::move(gp));
        }
        groups.push_back(std::move(group));
    }
    d->doc.vertex_groups = std::move(groups);
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

// --- Morphs ----------------------------------------------------------------------------------------------------------

int32_t co_doc_morph_counts(const co_doc* d, int32_t* out_morphs, int32_t* out_parts, int32_t* out_rows) {
    if (int32_t rc = null_handle(d, "co_doc_morph_counts")) return rc;
    std::size_t parts = 0;
    std::size_t rows = 0;
    for (const omo::Morph& m : d->doc.morphs) {
        parts += m.parts.size();
        for (const omo::MorphPart& p : m.parts) rows += p.vertices.size();
    }
    set_out(out_morphs, static_cast<int32_t>(d->doc.morphs.size()));
    set_out(out_parts, static_cast<int32_t>(parts));
    set_out(out_rows, static_cast<int32_t>(rows));
    return CO_OK;
}

int32_t co_doc_morphs_get(const co_doc* d, const char** names, float* params, int32_t* morph_parts,
                          const char** part_ids, int32_t* part_flags, int32_t* part_rows, int32_t* vertices,
                          float* deltas, float* normals) {
    if (int32_t rc = null_handle(d, "co_doc_morphs_get")) return rc;
    int32_t part = 0;
    std::size_t row = 0;
    if (morph_parts != nullptr) morph_parts[0] = 0;
    if (part_rows != nullptr) part_rows[0] = 0;
    for (std::size_t m = 0; m < d->doc.morphs.size(); ++m) {
        const omo::Morph& morph = d->doc.morphs[m];
        if (names != nullptr) names[m] = morph.name.c_str();
        if (params != nullptr) {
            params[3 * m] = morph.default_weight;
            params[3 * m + 1] = morph.min;
            params[3 * m + 2] = morph.max;
        }
        for (const omo::MorphPart& p : morph.parts) {
            if (part_ids != nullptr) part_ids[part] = p.part_id.c_str();
            if (part_flags != nullptr) part_flags[part] = p.normals.empty() ? 0 : CO_MORPH_PART_NORMALS;
            for (std::size_t r = 0; r < p.vertices.size(); ++r) {
                if (vertices != nullptr) vertices[row] = p.vertices[r];
                for (std::size_t k = 0; k < 3; ++k) {
                    if (deltas != nullptr) deltas[row * 3 + k] = p.deltas[r * 3 + k];
                    if (normals != nullptr) normals[row * 3 + k] = p.normals.empty() ? 0.f : p.normals[r * 3 + k];
                }
                ++row;
            }
            ++part;
            if (part_rows != nullptr) part_rows[part] = static_cast<int32_t>(row);
        }
        if (morph_parts != nullptr) morph_parts[m + 1] = part;
    }
    return CO_OK;
}

int32_t co_doc_morphs_set(co_doc* d, int32_t morph_count, const char* const* names, const float* params,
                          const int32_t* morph_parts, const char* const* part_ids, const int32_t* part_flags,
                          const int32_t* part_rows, const int32_t* vertices, const float* deltas, const float* normals) {
    if (int32_t rc = null_handle(d, "co_doc_morphs_set")) return rc;
    CO_GUARD_BEGIN
    clear_error();
    if (bad_list(names, morph_count)) return error(CO_ERR_ARG, "co_doc_morphs_set: bad morph list");
    int32_t parts = 0;
    if (morph_count > 0 && !valid_offsets(morph_parts, morph_count, &parts)) {
        return error(CO_ERR_ARG, "co_doc_morphs_set: bad morph_parts");
    }
    int32_t rows = 0;
    if (parts > 0 && (part_ids == nullptr || !valid_offsets(part_rows, parts, &rows))) {
        return error(CO_ERR_ARG, "co_doc_morphs_set: bad part_ids / part_rows");
    }
    if (rows > 0 && (vertices == nullptr || deltas == nullptr)) {
        return error(CO_ERR_ARG, "co_doc_morphs_set: missing vertices / deltas");
    }
    std::vector<omo::Morph> morphs;
    morphs.reserve(static_cast<std::size_t>(morph_count));
    for (int32_t m = 0; m < morph_count; ++m) {
        if (names[m] == nullptr) return error(CO_ERR_ARG, "co_doc_morphs_set: null morph name");
        omo::Morph morph;
        morph.name = names[m];
        if (params != nullptr) {
            morph.default_weight = params[3 * m];
            morph.min = params[3 * m + 1];
            morph.max = params[3 * m + 2];
        }
        for (int32_t p = morph_parts[m]; p < morph_parts[m + 1]; ++p) {
            if (part_ids[p] == nullptr) return error(CO_ERR_ARG, "co_doc_morphs_set: null part id");
            const bool with_normals = part_flags != nullptr && (part_flags[p] & CO_MORPH_PART_NORMALS) != 0;
            if (with_normals && normals == nullptr) {
                return error(CO_ERR_ARG, "co_doc_morphs_set: a part has normals but no normals array was given");
            }
            omo::MorphPart mp;
            mp.part_id = part_ids[p];
            for (int32_t r = part_rows[p]; r < part_rows[p + 1]; ++r) {
                if (vertices[r] < 0) return error(CO_ERR_ARG, "co_doc_morphs_set: negative vertex");
                mp.vertices.push_back(vertices[r]);
                const auto base = static_cast<std::size_t>(r) * 3;
                mp.deltas.insert(mp.deltas.end(), deltas + base, deltas + base + 3);
                if (with_normals) mp.normals.insert(mp.normals.end(), normals + base, normals + base + 3);
            }
            morph.parts.push_back(std::move(mp));
        }
        morphs.push_back(std::move(morph));
    }
    d->doc.morphs = std::move(morphs);
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

// --- Archive entries -----------------------------------------------------------------------------------------------

int64_t co_doc_texture(const co_doc* d, const uint8_t** out_data) {
    set_out(out_data, static_cast<const uint8_t*>(nullptr));
    if (d == nullptr) return error(CO_ERR_ARG, "co_doc_texture: null handle");
    set_out(out_data, data_of(d->doc.texture));
    return static_cast<int64_t>(d->doc.texture.size());
}

int32_t co_doc_set_texture(co_doc* d, const uint8_t* data, int64_t size) {
    if (int32_t rc = null_handle(d, "co_doc_set_texture")) return rc;
    if (size < 0) return error(CO_ERR_ARG, "co_doc_set_texture: negative size");
    CO_GUARD_BEGIN
    clear_error();
    d->doc.texture = data == nullptr ? std::vector<std::byte>{} : copy_bytes(data, size);
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

int32_t co_doc_entry_count(const co_doc* d, int32_t kind) {
    if (int32_t rc = null_handle(d, "co_doc_entry_count")) return rc;
    const auto* entries = entries_of(d, kind);
    if (entries == nullptr) return error(CO_ERR_ARG, "co_doc_entry_count: unknown kind");
    return static_cast<int32_t>(entries->size());
}

int32_t co_doc_entry_get(const co_doc* d, int32_t kind, int32_t index, const char** out_name, const uint8_t** out_data,
                         int64_t* out_size) {
    if (int32_t rc = null_handle(d, "co_doc_entry_get")) return rc;
    const auto* entries = entries_of(d, kind);
    if (entries == nullptr) return error(CO_ERR_ARG, "co_doc_entry_get: unknown kind");
    if (index < 0 || static_cast<std::size_t>(index) >= entries->size()) {
        return error(CO_ERR_RANGE, "co_doc_entry_get: index out of range");
    }
    const omo::Image& e = (*entries)[static_cast<std::size_t>(index)];
    set_out(out_name, e.name.c_str());
    set_out(out_data, data_of(e.data));
    set_out(out_size, static_cast<int64_t>(e.data.size()));
    return CO_OK;
}

int32_t co_doc_entry_find(const co_doc* d, int32_t kind, const char* name) {
    if (d == nullptr || name == nullptr) return -1;
    const auto* entries = entries_of(d, kind);
    if (entries == nullptr) return -1;
    for (std::size_t i = 0; i < entries->size(); ++i) {
        if ((*entries)[i].name == name) return static_cast<int32_t>(i);
    }
    return -1;
}

int32_t co_doc_entry_set(co_doc* d, int32_t kind, const char* name, const uint8_t* data, int64_t size) {
    if (int32_t rc = null_handle(d, "co_doc_entry_set")) return rc;
    auto* entries = entries_of(d, kind);
    if (entries == nullptr) return error(CO_ERR_ARG, "co_doc_entry_set: unknown kind");
    if (name == nullptr || name[0] == '\0') return error(CO_ERR_ARG, "co_doc_entry_set: no name");
    if (size < 0) return error(CO_ERR_ARG, "co_doc_entry_set: negative size");
    const std::string_view n(name);
    if (n == omo::kManifestEntry || n == omo::kTextureEntry) {
        return error(CO_ERR_ARG, std::string("co_doc_entry_set: \"") + name + "\" is reserved");
    }
    if ((kind == CO_ENTRIES_IMAGES) != image_name(n)) {
        return error(CO_ERR_ARG, std::string("co_doc_entry_set: \"") + name +
                                         (kind == CO_ENTRIES_IMAGES ? "\" is not a material_*.png name"
                                                                    : "\" is a material image name"));
    }
    CO_GUARD_BEGIN
    clear_error();
    auto it = std::find_if(entries->begin(), entries->end(), [&](const omo::Image& e) { return e.name == n; });
    if (data == nullptr) {
        if (it != entries->end()) entries->erase(it);
    } else if (it != entries->end()) {
        it->data = copy_bytes(data, size);
    } else {
        entries->push_back({std::string(n), copy_bytes(data, size)});
    }
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

// --- Unknown sections ----------------------------------------------------------------------------------------------

int32_t co_doc_unknown_count(const co_doc* d) {
    if (int32_t rc = null_handle(d, "co_doc_unknown_count")) return rc;
    return static_cast<int32_t>(d->doc.unknown_sections.size());
}

int32_t co_doc_unknown_get(const co_doc* d, int32_t index, const char** out_key, const char** out_json) {
    if (int32_t rc = null_handle(d, "co_doc_unknown_get")) return rc;
    if (index < 0 || static_cast<std::size_t>(index) >= d->doc.unknown_sections.size()) {
        return error(CO_ERR_RANGE, "co_doc_unknown_get: index out of range");
    }
    const omo::UnknownSection& s = d->doc.unknown_sections[static_cast<std::size_t>(index)];
    set_out(out_key, s.key.c_str());
    set_out(out_json, s.json.c_str());
    return CO_OK;
}

int32_t co_doc_unknown_set(co_doc* d, const char* key, const char* json) {
    if (int32_t rc = null_handle(d, "co_doc_unknown_set")) return rc;
    if (key == nullptr || key[0] == '\0') return error(CO_ERR_ARG, "co_doc_unknown_set: no key");
    CO_GUARD_BEGIN
    clear_error();
    auto& sections = d->doc.unknown_sections;
    auto it = std::find_if(sections.begin(), sections.end(), [&](const omo::UnknownSection& s) { return s.key == key; });
    if (json == nullptr) {
        if (it != sections.end()) sections.erase(it);
        return CO_OK;
    }
    // Validate by writing a trial copy (known keys and invalid JSON are refused there).
    omo::Document trial;
    trial.unknown_sections.push_back({key, json});
    (void)omo::write_manifest(trial);
    if (it != sections.end()) {
        it->json = json;
    } else {
        sections.push_back({key, json});
    }
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

// --- .omt ------------------------------------------------------------------------------------------------------------

co_omt* co_omt_create(void) {
    CO_GUARD_BEGIN
    clear_error();
    return new co_omt();
    CO_GUARD_END(nullptr)
}

co_omt* co_omt_read(const uint8_t* bytes, int64_t length, int32_t* out_result) {
    set_out(out_result, int32_t{CO_OK});
    if (bytes == nullptr || length <= 0) {
        set_out(out_result, int32_t{CO_ERR_ARG});
        error(CO_ERR_ARG, "co_omt_read: no bytes");
        return nullptr;
    }
    try {
        clear_error();
        auto* t = new co_omt();
        try {
            t->tex = omo::omt::read(span_of(bytes, length));
        } catch (...) {
            delete t;
            throw;
        }
        return t;
    } catch (const std::exception& e) {
        set_out(out_result, int32_t{CO_ERR_FORMAT});
        error(CO_ERR_FORMAT, e.what());
    } catch (...) {
        set_out(out_result, int32_t{CO_ERR_FORMAT});
        error(CO_ERR_FORMAT, "co_omt_read: unknown error");
    }
    return nullptr;
}

void co_omt_destroy(co_omt* t) { delete t; }

co_bytes* co_omt_write(const co_omt* t) {
    if (t == nullptr) {
        error(CO_ERR_ARG, "co_omt_write: null handle");
        return nullptr;
    }
    CO_GUARD_BEGIN
    clear_error();
    auto* b = new co_bytes();
    try {
        b->data = omo::omt::write(t->tex);
    } catch (...) {
        delete b;
        throw;
    }
    return b;
    CO_GUARD_END(nullptr)
}

const char* co_omt_version(const co_omt* t) { return t == nullptr ? nullptr : t->tex.version.c_str(); }

int32_t co_omt_set_version(co_omt* t, const char* version) {
    if (int32_t rc = null_handle(t, "co_omt_set_version")) return rc;
    CO_GUARD_BEGIN
    t->tex.version = str(version);
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

int32_t co_omt_info_get(const co_omt* t, co_omt_info* out) {
    if (int32_t rc = null_handle(t, "co_omt_info_get")) return rc;
    if (out == nullptr) return error(CO_ERR_ARG, "co_omt_info_get: null output");
    *out = co_omt_info{t->tex.width, t->tex.height, t->tex.active_layer, t->tex.crlf ? 1 : 0};
    return CO_OK;
}

int32_t co_omt_info_set(co_omt* t, const co_omt_info* info) {
    if (int32_t rc = null_handle(t, "co_omt_info_set")) return rc;
    if (info == nullptr) return error(CO_ERR_ARG, "co_omt_info_set: null info");
    t->tex.width = info->width;
    t->tex.height = info->height;
    t->tex.active_layer = info->active_layer;
    t->tex.crlf = info->crlf != 0;
    return CO_OK;
}

int32_t co_omt_layers_get(const co_omt* t, co_omt_layer* out, int32_t capacity) {
    if (int32_t rc = null_handle(t, "co_omt_layers_get")) return rc;
    return copy_out(t->tex.layers, out, capacity, [](const omo::omt::Layer& l, co_omt_layer& o) {
        o.name = l.name.c_str();
        o.data_file = l.data_file.c_str();
        o.png = data_of(l.png);
        o.png_size = static_cast<int64_t>(l.png.size());
        o.opacity = l.opacity;
        o.visible = l.visible ? 1 : 0;
    });
}

int32_t co_omt_layers_set(co_omt* t, const co_omt_layer* layers, int32_t count) {
    if (int32_t rc = null_handle(t, "co_omt_layers_set")) return rc;
    if (bad_list(layers, count)) return error(CO_ERR_ARG, "co_omt_layers_set: bad list");
    CO_GUARD_BEGIN
    clear_error();
    std::vector<omo::omt::Layer> items;
    items.reserve(static_cast<std::size_t>(count));
    for (int32_t i = 0; i < count; ++i) {
        const co_omt_layer& l = layers[i];
        if (l.png_size < 0 || (l.png_size > 0 && l.png == nullptr)) {
            return error(CO_ERR_ARG, "co_omt_layers_set: layer " + std::to_string(i) + " has bad PNG bytes");
        }
        omo::omt::Layer layer;
        layer.name = str(l.name);
        layer.data_file = str(l.data_file);
        layer.png = copy_bytes(l.png, l.png_size);
        layer.opacity = l.opacity;
        layer.visible = l.visible != 0;
        items.push_back(std::move(layer));
    }
    t->tex.layers = std::move(items);
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

int32_t co_omt_mappings_get(const co_omt* t, co_omt_mapping* out, int32_t capacity) {
    if (int32_t rc = null_handle(t, "co_omt_mappings_get")) return rc;
    return copy_out(t->tex.face_mappings, out, capacity, [](const omo::omt::FaceMapping& m, co_omt_mapping& o) {
        o = co_omt_mapping{m.face_id, m.material_id, m.u0, m.v0, m.u1, m.v1, m.uv_rotation, 0};
    });
}

int32_t co_omt_mappings_set(co_omt* t, const co_omt_mapping* mappings, int32_t count) {
    if (int32_t rc = null_handle(t, "co_omt_mappings_set")) return rc;
    if (bad_list(mappings, count)) return error(CO_ERR_ARG, "co_omt_mappings_set: bad list");
    CO_GUARD_BEGIN
    clear_error();
    std::vector<omo::omt::FaceMapping> items;
    items.reserve(static_cast<std::size_t>(count));
    for (int32_t i = 0; i < count; ++i) {
        const co_omt_mapping& m = mappings[i];
        items.push_back({m.face_id, m.material_id, m.u0, m.v0, m.u1, m.v1, m.uv_rotation});
    }
    t->tex.face_mappings = std::move(items);
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

int32_t co_omt_materials_get(const co_omt* t, co_omt_material* out, int32_t capacity) {
    if (int32_t rc = null_handle(t, "co_omt_materials_get")) return rc;
    return copy_out(t->tex.materials, out, capacity, [](const omo::omt::Material& m, co_omt_material& o) {
        o.name = m.name.c_str();
        o.render_layer = m.render_layer.c_str();
        o.texture_file = m.texture_file.c_str();
        o.material_id = m.material_id;
    });
}

int32_t co_omt_materials_set(co_omt* t, const co_omt_material* materials, int32_t count) {
    if (int32_t rc = null_handle(t, "co_omt_materials_set")) return rc;
    if (bad_list(materials, count)) return error(CO_ERR_ARG, "co_omt_materials_set: bad list");
    CO_GUARD_BEGIN
    clear_error();
    std::vector<omo::omt::Material> items;
    items.reserve(static_cast<std::size_t>(count));
    for (int32_t i = 0; i < count; ++i) {
        const co_omt_material& m = materials[i];
        items.push_back({m.material_id, str(m.name), str(m.render_layer), str(m.texture_file)});
    }
    t->tex.materials = std::move(items);
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

int32_t co_omt_entry_count(const co_omt* t) {
    if (int32_t rc = null_handle(t, "co_omt_entry_count")) return rc;
    return static_cast<int32_t>(t->tex.other_entries.size());
}

int32_t co_omt_entry_get(const co_omt* t, int32_t index, const char** out_name, const uint8_t** out_data,
                         int64_t* out_size) {
    if (int32_t rc = null_handle(t, "co_omt_entry_get")) return rc;
    if (index < 0 || static_cast<std::size_t>(index) >= t->tex.other_entries.size()) {
        return error(CO_ERR_RANGE, "co_omt_entry_get: index out of range");
    }
    const omo::Image& e = t->tex.other_entries[static_cast<std::size_t>(index)];
    set_out(out_name, e.name.c_str());
    set_out(out_data, data_of(e.data));
    set_out(out_size, static_cast<int64_t>(e.data.size()));
    return CO_OK;
}

int32_t co_omt_entry_set(co_omt* t, const char* name, const uint8_t* data, int64_t size) {
    if (int32_t rc = null_handle(t, "co_omt_entry_set")) return rc;
    if (name == nullptr || name[0] == '\0') return error(CO_ERR_ARG, "co_omt_entry_set: no name");
    if (size < 0) return error(CO_ERR_ARG, "co_omt_entry_set: negative size");
    const std::string_view n(name);
    if (n == omo::kManifestEntry ||
        std::any_of(t->tex.layers.begin(), t->tex.layers.end(), [&](const omo::omt::Layer& l) { return l.data_file == n; })) {
        return error(CO_ERR_ARG, std::string("co_omt_entry_set: \"") + name + "\" is reserved");
    }
    CO_GUARD_BEGIN
    clear_error();
    auto& entries = t->tex.other_entries;
    auto it = std::find_if(entries.begin(), entries.end(), [&](const omo::Image& e) { return e.name == n; });
    if (data == nullptr) {
        if (it != entries.end()) entries.erase(it);
    } else if (it != entries.end()) {
        it->data = copy_bytes(data, size);
    } else {
        entries.push_back({std::string(n), copy_bytes(data, size)});
    }
    return CO_OK;
    CO_GUARD_END(CO_ERR_ARG)
}

}  // extern "C"
