// glTF 2.0 reader (OpenMason #173): cgltf parses and validates; this file turns cgltf_data into an import::Scene.
// cgltf's implementation lives in THIS translation unit only (hidden visibility via the omformat target), which
// also lets the reader use cgltf's internal element decoders for its own bounds-checked accessor reads.

#define CGLTF_IMPLEMENTATION
#include <cgltf.h>

#include "import_internal.hpp"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <map>
#include <memory>
#include <numeric>
#include <set>
#include <type_traits>

namespace cenda::omformat::import {

namespace {

// Accessors larger than this are refused (a tiny file must not be able to ask for gigabytes of zeros).
constexpr std::size_t kMaxElements = std::size_t{1} << 24;
constexpr std::size_t kMaxFloats = std::size_t{1} << 26;

const std::set<std::string, std::less<>> kSupportedRequired = {"KHR_mesh_quantization", "KHR_texture_transform"};

[[noreturn]] void fail(const std::string& msg) { throw format_error("glTF: " + msg); }

std::string str(const char* s) { return s ? std::string(s) : std::string(); }

const char* result_name(cgltf_result r) {
    switch (r) {
        case cgltf_result_success: return "success";
        case cgltf_result_data_too_short: return "data too short";
        case cgltf_result_unknown_format: return "unknown format";
        case cgltf_result_invalid_json: return "invalid JSON";
        case cgltf_result_invalid_gltf: return "invalid glTF";
        case cgltf_result_invalid_options: return "invalid options";
        case cgltf_result_file_not_found: return "file not found";
        case cgltf_result_io_error: return "I/O error";
        case cgltf_result_out_of_memory: return "out of memory";
        case cgltf_result_legacy_gltf: return "legacy glTF 1.0";
        default: return "error";
    }
}

// cgltf stores the sampler's GL enums from the JSON unchecked: read them as plain ints (loading an out-of-range
// value through the enum type is undefined behaviour).
template <class E>
int raw_enum(const E& e) {
    std::underlying_type_t<E> v;
    std::memcpy(&v, &e, sizeof v);
    return static_cast<int>(v);
}

template <class T>
int index_of(const T* p, const T* base, cgltf_size count) {
    if (p == nullptr) return -1;
    const auto d = p - base;
    if (d < 0 || static_cast<cgltf_size>(d) >= count) fail("dangling object reference");
    return static_cast<int>(d);
}

// ---- file callbacks (routed to the Resolver) ----------------------------------------------------------------

struct FileCtx {
    const Resolver* resolve = nullptr;
    std::exception_ptr error;
    std::string missing;
    std::string short_file;
};

cgltf_result file_read(const cgltf_memory_options*, const cgltf_file_options* fo, const char* path, cgltf_size* size,
                       void** data) {
    auto* ctx = static_cast<FileCtx*>(fo->user_data);
    try {
        std::optional<std::vector<std::byte>> got;
        if (ctx->resolve != nullptr && *ctx->resolve) got = (*ctx->resolve)(path);
        if (!got) {
            ctx->missing = path;
            return cgltf_result_file_not_found;
        }
        if (size != nullptr && *size != 0 && got->size() < *size) {
            ctx->short_file = path;
            return cgltf_result_data_too_short;
        }
        void* p = std::malloc(std::max<std::size_t>(got->size(), 1));
        if (p == nullptr) return cgltf_result_out_of_memory;
        if (!got->empty()) std::memcpy(p, got->data(), got->size());
        if (size != nullptr) *size = got->size();
        *data = p;
        return cgltf_result_success;
    } catch (...) {
        ctx->error = std::current_exception();
        return cgltf_result_io_error;
    }
}

void file_release(const cgltf_memory_options*, const cgltf_file_options*, void* data) { std::free(data); }

struct DataFree {
    void operator()(cgltf_data* d) const { cgltf_free(d); }
};

// ---- checked accessor reads ---------------------------------------------------------------------------------

// view's bytes [offset, offset + size) inside its loaded buffer.
const std::uint8_t* view_bytes(const cgltf_buffer_view* view, const char* what) {
    if (view == nullptr) fail(std::string(what) + ": missing buffer view");
    if (view->buffer == nullptr || view->buffer->data == nullptr) fail(std::string(what) + ": buffer not loaded");
    if (view->offset > view->buffer->size || view->size > view->buffer->size - view->offset)
        fail(std::string(what) + ": buffer view out of range");
    const std::uint8_t* p = cgltf_buffer_view_data(view);
    if (p == nullptr) fail(std::string(what) + ": buffer view has no data");
    return p;
}

// count elements of elem bytes at offset + i * stride fit inside limit bytes.
bool fits(std::size_t offset, std::size_t stride, std::size_t count, std::size_t elem, std::size_t limit) {
    if (count == 0) return offset <= limit;
    if (offset > limit || elem > limit - offset) return false;
    const std::size_t room = limit - offset - elem;  // what (count - 1) strides may span
    if (stride != 0 && count - 1 > room / stride) return false;
    return true;
}

void check_accessor(const cgltf_accessor* a, const char* what) {
    if (a == nullptr) fail(std::string(what) + ": missing accessor");
    if (a->count > kMaxElements || a->count * cgltf_num_components(a->type) > kMaxFloats)
        fail(std::string(what) + ": accessor too large");
    const std::size_t elem = cgltf_calc_size(a->type, a->component_type);
    if (elem == 0) fail(std::string(what) + ": invalid accessor type");
    if (a->buffer_view != nullptr) {
        view_bytes(a->buffer_view, what);
        if (a->stride < elem && a->count > 1) fail(std::string(what) + ": accessor stride smaller than its element");
        if (!fits(a->offset, a->stride, a->count, elem, a->buffer_view->size))
            fail(std::string(what) + ": accessor out of range");
    }
    if (a->is_sparse) {
        const auto& sp = a->sparse;
        if (sp.count > a->count) fail(std::string(what) + ": sparse count exceeds accessor count");
        const std::size_t isize = cgltf_component_size(sp.indices_component_type);
        if (sp.indices_component_type != cgltf_component_type_r_8u &&
            sp.indices_component_type != cgltf_component_type_r_16u &&
            sp.indices_component_type != cgltf_component_type_r_32u)
            fail(std::string(what) + ": bad sparse index type");
        view_bytes(sp.indices_buffer_view, what);
        view_bytes(sp.values_buffer_view, what);
        if (!fits(sp.indices_byte_offset, isize, sp.count, isize, sp.indices_buffer_view->size) ||
            !fits(sp.values_byte_offset, elem, sp.count, elem, sp.values_buffer_view->size))
            fail(std::string(what) + ": sparse data out of range");
    }
}

// Calls fn(i, element_bytes) for every element, then again for every sparse override (dense result). Elements of
// an accessor without a buffer view are not visited (they are zero). Each element is copied to an aligned scratch
// first: a malformed file may misalign its data, and cgltf's decoders read through typed pointers.
template <class Fn>
void visit_elements(const cgltf_accessor* a, const char* what, Fn&& fn) {
    check_accessor(a, what);
    const std::size_t elem = cgltf_calc_size(a->type, a->component_type);
    alignas(16) std::uint8_t tmp[64];
    if (elem > sizeof tmp) fail(std::string(what) + ": invalid accessor type");
    if (a->buffer_view != nullptr) {
        const std::uint8_t* base = view_bytes(a->buffer_view, what) + a->offset;
        for (std::size_t i = 0; i < a->count; ++i) {
            std::memcpy(tmp, base + a->stride * i, elem);
            fn(i, static_cast<const std::uint8_t*>(tmp));
        }
    }
    if (a->is_sparse) {
        const auto& sp = a->sparse;
        const std::size_t isize = cgltf_component_size(sp.indices_component_type);
        const std::uint8_t* idx = view_bytes(sp.indices_buffer_view, what) + sp.indices_byte_offset;
        const std::uint8_t* val = view_bytes(sp.values_buffer_view, what) + sp.values_byte_offset;
        for (std::size_t k = 0; k < sp.count; ++k) {
            std::memcpy(tmp, idx + isize * k, isize);
            const std::size_t i = cgltf_component_read_index(tmp, sp.indices_component_type);
            if (i >= a->count) fail(std::string(what) + ": sparse index out of range");
            std::memcpy(tmp, val + elem * k, elem);  // sparse values are tightly packed
            fn(i, static_cast<const std::uint8_t*>(tmp));
        }
    }
}

// cgltf_validate reads index accessors and sparse indices through typed pointers: refuse misaligned ones first
// (glTF requires the alignment anyway; buffer bases are malloc'd, see read_gltf).
void check_index_alignment(const cgltf_data* d) {
    auto aligned = [](const cgltf_buffer_view* v, std::size_t offset, std::size_t size) {
        const auto addr = reinterpret_cast<std::uintptr_t>(cgltf_buffer_view_data(v)) + offset;
        return size == 0 || addr % size == 0;
    };
    for (cgltf_size i = 0; i < d->accessors_count; ++i) {
        const cgltf_accessor& a = d->accessors[i];
        if (a.is_sparse && !aligned(a.sparse.indices_buffer_view, a.sparse.indices_byte_offset,
                                    cgltf_component_size(a.sparse.indices_component_type)))
            fail("accessor " + std::to_string(i) + ": misaligned sparse indices");
    }
    for (cgltf_size m = 0; m < d->meshes_count; ++m) {
        for (cgltf_size p = 0; p < d->meshes[m].primitives_count; ++p) {
            const cgltf_accessor* a = d->meshes[m].primitives[p].indices;
            if (a != nullptr && a->buffer_view != nullptr &&
                !aligned(a->buffer_view, a->offset, cgltf_component_size(a->component_type)))
                fail("mesh " + std::to_string(m) + ": misaligned indices");
        }
    }
}

// Every element as floats (normalized ints mapped, plain ints converted), components per element.
std::vector<float> read_floats(const cgltf_accessor* a, const char* what, std::size_t* components = nullptr) {
    check_accessor(a, what);
    const std::size_t nc = cgltf_num_components(a->type);
    if (components != nullptr) *components = nc;
    std::vector<float> out(a->count * nc, 0.f);
    visit_elements(a, what, [&](std::size_t i, const std::uint8_t* e) {
        if (!cgltf_element_read_float(e, a->type, a->component_type, a->normalized, out.data() + i * nc, nc))
            fail(std::string(what) + ": unreadable accessor element");
    });
    return out;
}

std::vector<std::uint32_t> read_indices(const cgltf_accessor* a, const char* what) {
    check_accessor(a, what);
    if (a->type != cgltf_type_scalar ||
        (a->component_type != cgltf_component_type_r_8u && a->component_type != cgltf_component_type_r_16u &&
         a->component_type != cgltf_component_type_r_32u))
        fail(std::string(what) + ": indices must be unsigned scalars");
    std::vector<std::uint32_t> out(a->count, 0);
    visit_elements(a, what, [&](std::size_t i, const std::uint8_t* e) {
        out[i] = static_cast<std::uint32_t>(cgltf_component_read_index(e, a->component_type));
    });
    return out;
}

void require_type(const cgltf_accessor* a, std::initializer_list<cgltf_type> types, const std::string& what) {
    if (std::find(types.begin(), types.end(), a->type) == types.end()) fail(what + ": wrong accessor type");
}

template <std::size_t N>
std::vector<std::array<float, N>> read_vecs(const cgltf_accessor* a, const std::string& what) {
    const std::vector<float> f = read_floats(a, what.c_str());
    const std::size_t nc = cgltf_num_components(a->type);
    std::vector<std::array<float, N>> out(a->count);
    for (std::size_t i = 0; i < a->count; ++i) {
        std::array<float, N> v{};
        for (std::size_t c = 0; c < N; ++c) v[c] = c < nc ? f[i * nc + c] : (c == 3 ? 1.f : 0.f);
        out[i] = v;
    }
    return out;
}

// ---- conversion ---------------------------------------------------------------------------------------------

struct Reader {
    const cgltf_data* d;
    const Resolver& resolve;
    Scene scene;

    void warn(std::string w) { scene.warnings.push_back(std::move(w)); }

    // Raw extras JSON. cgltf copies most into extras.data; a mesh's extras (parsed for targetNames) only carry the
    // deprecated offsets into the JSON chunk, so fall back to those.
    std::string extras(const cgltf_extras& e) const {
        if (e.data != nullptr) return e.data;
        if (d->json != nullptr && e.end_offset > e.start_offset && e.end_offset <= d->json_size)
            return std::string(d->json + e.start_offset, e.end_offset - e.start_offset);
        return {};
    }

    void extensions() {
        for (cgltf_size i = 0; i < d->extensions_required_count; ++i) {
            const std::string name = str(d->extensions_required[i]);
            if (!kSupportedRequired.contains(name)) fail("required extension " + name + " is not supported");
        }
        for (cgltf_size i = 0; i < d->extensions_used_count; ++i) {
            const std::string name = str(d->extensions_used[i]);
            if (!kSupportedRequired.contains(name)) warn("extension " + name + " ignored");
        }
    }

    void images() {
        scene.images.resize(d->images_count);
        for (cgltf_size i = 0; i < d->images_count; ++i) {
            const cgltf_image& src = d->images[i];
            Image& img = scene.images[i];
            img.name = str(src.name);
            img.mime = str(src.mime_type);
            const std::string label = "#" + std::to_string(i) + (img.name.empty() ? "" : " '" + img.name + "'");
            if (src.buffer_view != nullptr) {
                const std::uint8_t* p = view_bytes(src.buffer_view, "image");
                img.bytes.resize(src.buffer_view->size);
                if (!img.bytes.empty()) std::memcpy(img.bytes.data(), p, img.bytes.size());
            } else if (src.uri != nullptr) {
                const std::string_view uri(src.uri);
                if (uri.starts_with("data:")) {
                    const auto comma = uri.find(',');
                    if (comma == std::string_view::npos || !uri.substr(0, comma).ends_with(";base64"))
                        fail("image " + label + ": unsupported data URI");
                    if (img.mime.empty()) img.mime = std::string(uri.substr(5, comma - 5 - 7));
                    auto bytes = detail::decode_base64(uri.substr(comma + 1));
                    if (!bytes) fail("image " + label + ": bad base64");
                    img.bytes = std::move(*bytes);
                } else {
                    img.uri = detail::percent_decode(uri);
                    std::optional<std::vector<std::byte>> got;
                    if (resolve) got = resolve(img.uri);
                    if (!got) {
                        img.missing = true;
                        warn("image " + label + ": file '" + img.uri + "' not found");
                    } else {
                        img.bytes = std::move(*got);
                    }
                }
            } else {
                warn("image " + label + ": no source");
            }
            detail::finish_image(img, label, scene.warnings);
        }
    }

    std::optional<TextureRef> texture(const cgltf_texture_view& tv) {
        if (tv.texture == nullptr) return std::nullopt;
        TextureRef t;
        const cgltf_texture* tex = tv.texture;
        t.image = index_of(tex->image, d->images, d->images_count);
        if (t.image < 0) warn("texture without a PNG/JPEG image source");
        t.texcoord = tv.texcoord;
        if (tv.has_transform) {
            TextureTransform x;
            x.offset = {tv.transform.offset[0], tv.transform.offset[1]};
            x.rotation = tv.transform.rotation;
            x.scale = {tv.transform.scale[0], tv.transform.scale[1]};
            x.texcoord = tv.transform.has_texcoord ? tv.transform.texcoord : -1;
            t.transform = x;
        }
        if (tex->sampler != nullptr) {
            t.mag_filter = raw_enum(tex->sampler->mag_filter);
            t.min_filter = raw_enum(tex->sampler->min_filter);
            t.wrap_s = raw_enum(tex->sampler->wrap_s);
            t.wrap_t = raw_enum(tex->sampler->wrap_t);
        }
        return t;
    }

    void materials() {
        scene.materials.resize(d->materials_count);
        for (cgltf_size i = 0; i < d->materials_count; ++i) {
            const cgltf_material& src = d->materials[i];
            Material& m = scene.materials[i];
            m.name = str(src.name);
            if (src.has_pbr_metallic_roughness) {
                const auto& pbr = src.pbr_metallic_roughness;
                m.base_color = {pbr.base_color_factor[0], pbr.base_color_factor[1], pbr.base_color_factor[2],
                                pbr.base_color_factor[3]};
                m.base_color_texture = texture(pbr.base_color_texture);
                m.metallic = pbr.metallic_factor;
                m.roughness = pbr.roughness_factor;
            }
            switch (src.alpha_mode) {
                case cgltf_alpha_mode_mask: m.alpha_mode = AlphaMode::Mask; break;
                case cgltf_alpha_mode_blend: m.alpha_mode = AlphaMode::Blend; break;
                default: m.alpha_mode = AlphaMode::Opaque; break;
            }
            m.alpha_cutoff = src.alpha_cutoff;
            m.double_sided = src.double_sided != 0;
            m.emissive = {src.emissive_factor[0], src.emissive_factor[1], src.emissive_factor[2]};
            m.extras = extras(src.extras);
        }
    }

    static void triangulate(cgltf_primitive_type type, const std::vector<std::uint32_t>& in,
                            std::vector<std::uint32_t>& out) {
        out.clear();
        if (type == cgltf_primitive_type_triangles) {
            out.assign(in.begin(), in.begin() + static_cast<std::ptrdiff_t>(in.size() / 3 * 3));
            return;
        }
        auto add = [&](std::uint32_t a, std::uint32_t b, std::uint32_t c) {
            if (a == b || b == c || a == c) return;  // stitching degenerates are not faces
            out.push_back(a);
            out.push_back(b);
            out.push_back(c);
        };
        if (in.size() < 3) return;
        for (std::size_t i = 0; i + 2 < in.size(); ++i) {
            if (type == cgltf_primitive_type_triangle_strip) {
                // glTF: p_i = {v_i, v_{i+(1+i%2)}, v_{i+(2-i%2)}}
                if (i % 2 == 0) add(in[i], in[i + 1], in[i + 2]);
                else add(in[i], in[i + 2], in[i + 1]);
            } else {  // fan: {v_{i+1}, v_{i+2}, v_0}
                add(in[i + 1], in[i + 2], in[0]);
            }
        }
    }

    bool primitive(const cgltf_mesh& mesh, std::size_t mesh_index, const cgltf_primitive& src, Primitive& p) {
        const std::string where = "mesh " + std::to_string(mesh_index) + " primitive";
        if (src.type != cgltf_primitive_type_triangles && src.type != cgltf_primitive_type_triangle_strip &&
            src.type != cgltf_primitive_type_triangle_fan) {
            warn(where + ": points / lines primitive skipped");
            return false;
        }
        const cgltf_accessor* pos = cgltf_find_accessor(&src, cgltf_attribute_type_position, 0);
        if (pos == nullptr) {
            warn(where + ": no POSITION, skipped");
            return false;
        }
        require_type(pos, {cgltf_type_vec3}, where + " POSITION");
        p.positions = read_vecs<3>(pos, where + " POSITION");
        const std::size_t n = p.positions.size();
        auto same_count = [&](const cgltf_accessor* a, const std::string& what) {
            if (a->count != n) fail(what + ": count differs from POSITION");
        };

        std::map<int, const cgltf_accessor*> joints, weights;
        for (cgltf_size k = 0; k < src.attributes_count; ++k) {
            const cgltf_attribute& at = src.attributes[k];
            const std::string name = str(at.name);
            const std::string what = where + " " + name;
            if (at.data == nullptr) fail(what + ": missing accessor");
            same_count(at.data, what);
            switch (at.type) {
                case cgltf_attribute_type_position:
                    if (at.index != 0) warn(what + " ignored");
                    break;
                case cgltf_attribute_type_normal:
                    if (at.index != 0) { warn(what + " ignored"); break; }
                    require_type(at.data, {cgltf_type_vec3}, what);
                    p.normals = read_vecs<3>(at.data, what);
                    break;
                case cgltf_attribute_type_tangent:
                    if (at.index != 0) { warn(what + " ignored"); break; }
                    require_type(at.data, {cgltf_type_vec4}, what);
                    p.tangents = read_vecs<4>(at.data, what);
                    break;
                case cgltf_attribute_type_texcoord: {
                    if (at.index < 0 || at.index > 31) { warn(what + " ignored"); break; }
                    require_type(at.data, {cgltf_type_vec2}, what);
                    const auto set = static_cast<std::size_t>(at.index);
                    if (p.texcoords.size() <= set) p.texcoords.resize(set + 1);
                    p.texcoords[set] = read_vecs<2>(at.data, what);
                    break;
                }
                case cgltf_attribute_type_color:
                    if (at.index != 0) { warn(what + " ignored (only COLOR_0 is read)"); break; }
                    require_type(at.data, {cgltf_type_vec3, cgltf_type_vec4}, what);
                    p.colors = read_vecs<4>(at.data, what);
                    break;
                case cgltf_attribute_type_joints:
                    require_type(at.data, {cgltf_type_vec4}, what);
                    if (at.data->normalized) fail(what + ": joint indices must not be normalized");
                    joints[at.index] = at.data;
                    break;
                case cgltf_attribute_type_weights:
                    require_type(at.data, {cgltf_type_vec4}, what);
                    weights[at.index] = at.data;
                    break;
                case cgltf_attribute_type_custom: {
                    CustomAttribute c;
                    c.name = name;
                    std::size_t nc = 0;
                    c.values = read_floats(at.data, what.c_str(), &nc);
                    c.components = static_cast<int>(nc);
                    p.custom.push_back(std::move(c));
                    break;
                }
                default: warn(what + ": unknown attribute ignored"); break;
            }
        }
        for (std::size_t set = 0; set < p.texcoords.size(); ++set) {
            if (p.texcoords[set].empty()) warn(where + ": TEXCOORD_" + std::to_string(set) + " missing (empty set)");
        }

        // Skin influences: every JOINTS_n/WEIGHTS_n pair, merged per vertex in set order.
        if (!joints.empty() || !weights.empty()) {
            std::vector<std::pair<std::vector<float>, std::vector<float>>> sets;
            for (const auto& [set, ja] : joints) {
                auto it = weights.find(set);
                if (it == weights.end()) {
                    warn(where + ": JOINTS_" + std::to_string(set) + " without WEIGHTS_" + std::to_string(set) +
                         " ignored");
                    continue;
                }
                sets.emplace_back(read_floats(ja, (where + " JOINTS").c_str()),
                                  read_floats(it->second, (where + " WEIGHTS").c_str()));
            }
            for (const auto& [set, wa] : weights) {
                (void)wa;
                if (!joints.contains(set))
                    warn(where + ": WEIGHTS_" + std::to_string(set) + " without JOINTS_" + std::to_string(set) +
                         " ignored");
            }
            if (!sets.empty()) {
                Influences& inf = p.influences;
                inf.offsets.assign(1, 0);
                inf.offsets.reserve(n + 1);
                for (std::size_t v = 0; v < n; ++v) {
                    for (const auto& [j, w] : sets) {
                        for (std::size_t c = 0; c < 4; ++c) {
                            const float wt = w[v * 4 + c];
                            if (wt == 0.f) continue;
                            const float jf = j[v * 4 + c];
                            if (!(jf >= 0.f) || jf > 65535.f) fail(where + ": bad joint index");
                            inf.joints.push_back(static_cast<std::uint32_t>(jf));
                            inf.weights.push_back(wt);
                        }
                    }
                    inf.offsets.push_back(static_cast<std::uint32_t>(inf.joints.size()));
                }
            }
        }

        // Morph targets.
        p.targets.resize(src.targets_count);
        for (cgltf_size t = 0; t < src.targets_count; ++t) {
            const cgltf_morph_target& mt = src.targets[t];
            for (cgltf_size k = 0; k < mt.attributes_count; ++k) {
                const cgltf_attribute& at = mt.attributes[k];
                const std::string what = where + " target " + std::to_string(t) + " " + str(at.name);
                if (at.data == nullptr) fail(what + ": missing accessor");
                same_count(at.data, what);
                if (at.index != 0) { warn(what + " ignored"); continue; }
                switch (at.type) {
                    case cgltf_attribute_type_position:
                        require_type(at.data, {cgltf_type_vec3}, what);
                        p.targets[t].positions = read_vecs<3>(at.data, what);
                        break;
                    case cgltf_attribute_type_normal:
                        require_type(at.data, {cgltf_type_vec3}, what);
                        p.targets[t].normals = read_vecs<3>(at.data, what);
                        break;
                    case cgltf_attribute_type_tangent:
                        require_type(at.data, {cgltf_type_vec3}, what);
                        p.targets[t].tangents = read_vecs<3>(at.data, what);
                        break;
                    default: warn(what + " ignored"); break;
                }
            }
        }

        // Indices -> triangle list.
        std::vector<std::uint32_t> raw;
        if (src.indices != nullptr) {
            raw = read_indices(src.indices, (where + " indices").c_str());
            for (std::uint32_t i : raw) {
                if (i >= n) fail(where + ": index out of range");
            }
        } else {
            raw.resize(n);
            std::iota(raw.begin(), raw.end(), 0u);
        }
        if (src.type == cgltf_primitive_type_triangles && raw.size() % 3 != 0)
            warn(where + ": index count not a multiple of 3, trailing indices dropped");
        triangulate(src.type, raw, p.indices);
        p.face_sizes.assign(p.indices.size() / 3, 3u);

        p.material = index_of(src.material, d->materials, d->materials_count);
        p.extras = extras(src.extras);
        (void)mesh;
        return true;
    }

    void meshes() {
        scene.meshes.resize(d->meshes_count);
        for (cgltf_size i = 0; i < d->meshes_count; ++i) {
            const cgltf_mesh& src = d->meshes[i];
            Mesh& m = scene.meshes[i];
            m.name = str(src.name);
            m.extras = extras(src.extras);
            for (cgltf_size k = 0; k < src.primitives_count; ++k) {
                Primitive p;
                if (primitive(src, i, src.primitives[k], p)) m.primitives.push_back(std::move(p));
            }
            const std::size_t targets = src.primitives_count ? src.primitives[0].targets_count : 0;
            m.weights.assign(targets, 0.f);
            if (src.weights != nullptr) {
                if (src.weights_count != targets) fail("mesh " + std::to_string(i) + ": weights count != targets");
                std::copy_n(src.weights, targets, m.weights.begin());
            }
            if (src.target_names != nullptr) {
                if (src.target_names_count != targets)
                    fail("mesh " + std::to_string(i) + ": targetNames count != targets");
                for (cgltf_size k = 0; k < src.target_names_count; ++k) m.target_names.push_back(str(src.target_names[k]));
            }
        }
    }

    void nodes() {
        scene.nodes.resize(d->nodes_count);
        for (cgltf_size i = 0; i < d->nodes_count; ++i) {
            const cgltf_node& src = d->nodes[i];
            Node& nd = scene.nodes[i];
            nd.name = str(src.name);
            nd.parent = index_of<cgltf_node>(src.parent, d->nodes, d->nodes_count);
            for (cgltf_size k = 0; k < src.children_count; ++k)
                nd.children.push_back(index_of<cgltf_node>(src.children[k], d->nodes, d->nodes_count));
            if (src.has_translation) nd.translation = Float3{src.translation[0], src.translation[1], src.translation[2]};
            if (src.has_rotation)
                nd.rotation = Float4{src.rotation[0], src.rotation[1], src.rotation[2], src.rotation[3]};
            if (src.has_scale) nd.scale = Float3{src.scale[0], src.scale[1], src.scale[2]};
            if (src.has_matrix) {
                Mat4 m{};
                std::copy_n(src.matrix, 16, m.begin());
                nd.matrix = m;
                nd.local = m;
            } else {
                nd.local = detail::compose_trs(nd.translation.value_or(Float3{0.f, 0.f, 0.f}),
                                               nd.rotation.value_or(Float4{0.f, 0.f, 0.f, 1.f}),
                                               nd.scale.value_or(Float3{1.f, 1.f, 1.f}));
            }
            nd.mesh = index_of(src.mesh, d->meshes, d->meshes_count);
            nd.skin = index_of(src.skin, d->skins, d->skins_count);
            if (src.weights != nullptr) nd.weights.assign(src.weights, src.weights + src.weights_count);
            nd.extras = extras(src.extras);
            if (src.camera != nullptr || src.light != nullptr)
                warn("node " + std::to_string(i) + ": camera / light ignored");
        }
    }

    void skins() {
        scene.skins.resize(d->skins_count);
        for (cgltf_size i = 0; i < d->skins_count; ++i) {
            const cgltf_skin& src = d->skins[i];
            Skin& s = scene.skins[i];
            const std::string where = "skin " + std::to_string(i);
            s.name = str(src.name);
            s.extras = extras(src.extras);
            for (cgltf_size k = 0; k < src.joints_count; ++k)
                s.joints.push_back(index_of<cgltf_node>(src.joints[k], d->nodes, d->nodes_count));
            s.skeleton = index_of<cgltf_node>(src.skeleton, d->nodes, d->nodes_count);
            s.inverse_bind_matrices.assign(src.joints_count, kIdentity);
            if (src.inverse_bind_matrices != nullptr) {
                const cgltf_accessor* a = src.inverse_bind_matrices;
                require_type(a, {cgltf_type_mat4}, where + " inverseBindMatrices");
                if (a->count < src.joints_count) fail(where + ": fewer inverse bind matrices than joints");
                const std::vector<float> f = read_floats(a, (where + " inverseBindMatrices").c_str());
                for (cgltf_size k = 0; k < src.joints_count; ++k)
                    std::copy_n(f.begin() + static_cast<std::ptrdiff_t>(k * 16), 16, s.inverse_bind_matrices[k].begin());
            }
        }
    }

    void animations() {
        for (cgltf_size i = 0; i < d->animations_count; ++i) {
            const cgltf_animation& src = d->animations[i];
            Animation a;
            const std::string where = "animation " + std::to_string(i);
            a.name = str(src.name);
            a.extras = extras(src.extras);
            for (cgltf_size k = 0; k < src.samplers_count; ++k) {
                const cgltf_animation_sampler& ss = src.samplers[k];
                AnimSampler s;
                const std::string sw = where + " sampler " + std::to_string(k);
                require_type(ss.input, {cgltf_type_scalar}, sw + " input");
                s.input = read_floats(ss.input, (sw + " input").c_str());
                s.output = read_floats(ss.output, (sw + " output").c_str());
                switch (ss.interpolation) {
                    case cgltf_interpolation_type_step: s.interpolation = Interpolation::Step; break;
                    case cgltf_interpolation_type_cubic_spline: s.interpolation = Interpolation::CubicSpline; break;
                    default: s.interpolation = Interpolation::Linear; break;
                }
                const std::size_t per_key = s.interpolation == Interpolation::CubicSpline ? 3 : 1;
                const std::size_t keys = s.input.size();
                if (keys == 0) {
                    s.components = 0;
                } else {
                    if (s.output.size() % (keys * per_key) != 0) fail(sw + ": output does not match its keys");
                    s.components = static_cast<int>(s.output.size() / (keys * per_key));
                }
                a.samplers.push_back(std::move(s));
            }
            for (cgltf_size k = 0; k < src.channels_count; ++k) {
                const cgltf_animation_channel& sc = src.channels[k];
                const std::string cw = where + " channel " + std::to_string(k);
                AnimChannel c;
                c.sampler = index_of(sc.sampler, src.samplers, src.samplers_count);
                if (sc.target_node == nullptr) {
                    warn(cw + ": no target node, dropped");
                    continue;
                }
                c.node = index_of<cgltf_node>(sc.target_node, d->nodes, d->nodes_count);
                int expected = 0;
                switch (sc.target_path) {
                    case cgltf_animation_path_type_translation: c.path = AnimPath::Translation; expected = 3; break;
                    case cgltf_animation_path_type_rotation: c.path = AnimPath::Rotation; expected = 4; break;
                    case cgltf_animation_path_type_scale: c.path = AnimPath::Scale; expected = 3; break;
                    case cgltf_animation_path_type_weights: {
                        c.path = AnimPath::Weights;
                        const cgltf_mesh* m = sc.target_node->mesh;
                        expected = (m && m->primitives_count) ? static_cast<int>(m->primitives[0].targets_count) : 0;
                        break;
                    }
                    default: warn(cw + ": unsupported path, dropped"); continue;
                }
                const AnimSampler& s = a.samplers[static_cast<std::size_t>(c.sampler)];
                if (!s.input.empty() && s.components != expected) fail(cw + ": output size does not match the path");
                a.channels.push_back(c);
            }
            scene.animations.push_back(std::move(a));
        }
    }

    void roots() {
        const cgltf_scene* sc = d->scene ? d->scene : (d->scenes_count ? &d->scenes[0] : nullptr);
        if (sc != nullptr) {
            for (cgltf_size k = 0; k < sc->nodes_count; ++k)
                scene.roots.push_back(index_of<cgltf_node>(sc->nodes[k], d->nodes, d->nodes_count));
            if (d->scenes_count > 1) warn("only the default scene's roots are listed");
        } else {
            for (std::size_t i = 0; i < scene.nodes.size(); ++i) {
                if (scene.nodes[i].parent < 0) scene.roots.push_back(static_cast<int>(i));
            }
        }
    }

    void check_skinning() {
        for (std::size_t i = 0; i < scene.nodes.size(); ++i) {
            const Node& nd = scene.nodes[i];
            if (nd.mesh < 0) continue;
            const Mesh& m = scene.meshes[static_cast<std::size_t>(nd.mesh)];
            bool skinned = false;
            for (const Primitive& p : m.primitives) {
                if (p.influences.empty()) continue;
                skinned = true;
                if (nd.skin < 0) continue;
                const std::size_t count = scene.skins[static_cast<std::size_t>(nd.skin)].joints.size();
                for (std::uint32_t j : p.influences.joints) {
                    if (j >= count) fail("node " + std::to_string(i) + ": joint index out of range of its skin");
                }
            }
            if (skinned && nd.skin < 0) warn("node " + std::to_string(i) + ": skinned mesh without a skin");
        }
    }

    void run() {
        scene.format = d->file_type == cgltf_file_type_glb ? SourceFormat::Glb : SourceFormat::Gltf;
        scene.uv_origin = UvOrigin::TopLeft;
        scene.generator = str(d->asset.generator);
        scene.version = str(d->asset.version);
        scene.copyright = str(d->asset.copyright);
        scene.extras = extras(d->extras);
        extensions();
        images();
        materials();
        meshes();
        nodes();
        skins();
        animations();
        roots();
        check_skinning();
    }
};

}  // namespace

Scene read_gltf(std::span<const std::byte> bytes, const Resolver& resolve) {
    FileCtx ctx;
    ctx.resolve = &resolve;
    cgltf_options options{};
    options.file.read = &file_read;
    options.file.release = &file_release;
    options.file.user_data = &ctx;

    cgltf_data* raw = nullptr;
    cgltf_result r = cgltf_parse(&options, bytes.data(), bytes.size(), &raw);
    std::unique_ptr<cgltf_data, DataFree> data(raw);
    if (r != cgltf_result_success) fail(std::string("parse failed: ") + result_name(r));

    // A .glb's BIN chunk is copied into its own (malloc-aligned) block: the caller's span may sit anywhere.
    if (data->buffers_count != 0 && data->buffers[0].data == nullptr && data->buffers[0].uri == nullptr &&
        data->bin != nullptr) {
        cgltf_buffer& b = data->buffers[0];
        if (data->bin_size < b.size) fail("GLB BIN chunk shorter than buffer 0");
        void* copy = std::malloc(std::max<std::size_t>(b.size, 1));
        if (copy == nullptr) fail("out of memory");
        if (b.size != 0) std::memcpy(copy, data->bin, b.size);
        b.data = copy;
        b.data_free_method = cgltf_data_free_method_memory_free;  // cgltf_free -> the default free()
    }
    // The empty base path makes cgltf hand the (percent-decoded) URI itself to file_read.
    r = cgltf_load_buffers(&options, data.get(), "");
    if (ctx.error) std::rethrow_exception(ctx.error);
    if (r != cgltf_result_success) {
        if (!ctx.missing.empty()) fail("buffer file '" + ctx.missing + "' not found");
        if (!ctx.short_file.empty()) fail("buffer file '" + ctx.short_file + "' is shorter than declared");
        fail(std::string("loading buffers failed: ") + result_name(r));
    }
    for (cgltf_size i = 0; i < data->buffers_count; ++i) {
        if (data->buffers[i].data == nullptr) fail("buffer " + std::to_string(i) + " has no data");
    }
    // Our own overflow-safe bounds checks first: cgltf_validate reads sparse indices (to bound them) before it has
    // checked their buffer view against its buffer (found by the truncation/mutation test under ASan).
    for (cgltf_size i = 0; i < data->buffer_views_count; ++i) view_bytes(&data->buffer_views[i], "buffer view");
    for (cgltf_size i = 0; i < data->accessors_count; ++i) check_accessor(&data->accessors[i], "accessor");
    check_index_alignment(data.get());
    r = cgltf_validate(data.get());
    if (r != cgltf_result_success) fail(std::string("validation failed: ") + result_name(r));

    Reader reader{data.get(), resolve, {}};
    reader.run();
    return std::move(reader.scene);
}

}  // namespace cenda::omformat::import
