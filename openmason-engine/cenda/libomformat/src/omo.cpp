// .omo / .omt codec (OpenMason #167, OMO 1.9 sections #168). The header documents the layout; the writer
// reproduces the Java tool's Jackson output byte for byte (omo_json.hpp).
#include "omformat/omo.hpp"

#include "json_util.hpp"
#include "omo_json.hpp"

#include <algorithm>
#include <charconv>
#include <cmath>
#include <cstring>
#include <limits>
#include <type_traits>
#include <unordered_set>
#include <utility>

namespace cenda::omformat::omo {

namespace {

using json::CharPtr;
using json::DocPtr;

// Face ids a smooth bitset may address (8 MiB of words): guards against a malicious range.
constexpr std::int64_t kMaxFaceId = std::int64_t{1} << 26;
constexpr std::int64_t kI32Max = std::numeric_limits<std::int32_t>::max();

[[noreturn]] void fail(const std::string& message) { throw format_error(".omo: " + message); }

DocPtr parse_json(std::string_view text, yyjson_read_flag flags, const std::string& what) {
    yyjson_read_err err{};
    // Without YYJSON_READ_INSITU the input is not modified.
    yyjson_doc* doc = yyjson_read_opts(const_cast<char*>(text.data()), text.size(), flags, nullptr, &err);
    if (doc == nullptr) {
        throw format_error(what + " is not valid JSON (" + err.msg + " at byte " + std::to_string(err.pos) + ")");
    }
    return DocPtr(doc);
}

std::string minified(yyjson_val* val) {
    std::size_t len = 0;
    CharPtr text(yyjson_val_write(val, YYJSON_WRITE_NOFLAG, &len));
    if (!text) fail("cannot serialize a JSON value");
    return {text.get(), len};
}

std::string_view text_of(const std::vector<std::byte>& data) {
    return {reinterpret_cast<const char*>(data.data()), data.size()};
}

std::vector<std::byte> bytes_of(const std::string& text) {
    std::vector<std::byte> out(text.size());
    if (!text.empty()) std::memcpy(out.data(), text.data(), text.size());
    return out;
}

bool contains_crlf(std::string_view text) { return text.find("\r\n") != std::string_view::npos; }

// The manifest text without a UTF-8 byte order mark (Jackson skips one; the writers never emit it).
std::string_view manifest_text(const std::vector<std::byte>& data) {
    std::string_view text = text_of(data);
    if (text.starts_with("\xEF\xBB\xBF")) text.remove_prefix(3);
    return text;
}

// --- Java JsonNode coercions (asDouble / asInt / asBoolean / asText), so files read like the Java readers.

yyjson_val* member(yyjson_val* obj, const char* key) {
    yyjson_val* v = yyjson_obj_get(obj, key);
    return (v == nullptr || yyjson_is_null(v)) ? nullptr : v;
}

double parse_double_text(std::string_view s, double fallback) {
    if (s == "NaN") return std::numeric_limits<double>::quiet_NaN();
    if (s == "Infinity" || s == "+Infinity") return std::numeric_limits<double>::infinity();
    if (s == "-Infinity") return -std::numeric_limits<double>::infinity();
    double d = 0.0;
    auto r = std::from_chars(s.data(), s.data() + s.size(), d);
    return (r.ec == std::errc{} && r.ptr == s.data() + s.size()) ? d : fallback;
}

double as_double(yyjson_val* v, double fallback) {
    if (v == nullptr) return fallback;
    if (yyjson_is_num(v)) return yyjson_get_num(v);
    if (yyjson_is_bool(v)) return yyjson_get_bool(v) ? 1.0 : 0.0;
    if (yyjson_is_str(v)) return parse_double_text({yyjson_get_str(v), yyjson_get_len(v)}, fallback);
    return fallback;
}

float as_float(yyjson_val* v, float fallback) {
    return v == nullptr ? fallback : static_cast<float>(as_double(v, static_cast<double>(fallback)));
}

float float_field(yyjson_val* obj, const char* key, float fallback) { return as_float(member(obj, key), fallback); }

// Java's (int) cast of a double: truncation, saturating, NaN -> 0.
std::int32_t java_int_of(double d) {
    if (std::isnan(d)) return 0;
    if (d >= 2147483647.0) return std::numeric_limits<std::int32_t>::max();
    if (d <= -2147483648.0) return std::numeric_limits<std::int32_t>::min();
    return static_cast<std::int32_t>(d);
}

// Java's (int) cast of a long (Jackson asInt on a LongNode): wraps.
std::int32_t wrap_int(std::uint64_t bits) { return static_cast<std::int32_t>(static_cast<std::uint32_t>(bits)); }

std::int32_t as_int(yyjson_val* v, std::int32_t fallback) {
    if (v == nullptr) return fallback;
    if (yyjson_is_sint(v)) return wrap_int(static_cast<std::uint64_t>(yyjson_get_sint(v)));
    if (yyjson_is_uint(v)) return wrap_int(yyjson_get_uint(v));
    if (yyjson_is_real(v)) return java_int_of(yyjson_get_real(v));
    if (yyjson_is_bool(v)) return yyjson_get_bool(v) ? 1 : 0;
    if (yyjson_is_str(v)) {
        std::string_view s(yyjson_get_str(v), yyjson_get_len(v));
        std::int32_t i = 0;
        auto r = std::from_chars(s.data(), s.data() + s.size(), i);
        return (r.ec == std::errc{} && r.ptr == s.data() + s.size()) ? i : fallback;
    }
    return fallback;
}

std::int32_t int_field(yyjson_val* obj, const char* key, std::int32_t fallback) {
    return as_int(member(obj, key), fallback);
}

bool as_bool(yyjson_val* v, bool fallback) {
    if (v == nullptr) return fallback;
    if (yyjson_is_bool(v)) return yyjson_get_bool(v);
    if (yyjson_is_num(v)) return yyjson_get_num(v) != 0.0;
    if (yyjson_is_str(v)) {
        std::string_view s(yyjson_get_str(v), yyjson_get_len(v));
        if (s == "true") return true;
        if (s == "false") return false;
    }
    return fallback;
}

bool bool_field(yyjson_val* obj, const char* key, bool fallback) { return as_bool(member(obj, key), fallback); }

std::optional<std::string> as_text(yyjson_val* v) {
    if (v == nullptr) return std::nullopt;
    if (yyjson_is_str(v)) return std::string(yyjson_get_str(v), yyjson_get_len(v));
    if (yyjson_is_bool(v)) return std::string(yyjson_get_bool(v) ? "true" : "false");
    if (yyjson_is_sint(v)) return std::to_string(yyjson_get_sint(v));
    if (yyjson_is_uint(v)) return std::to_string(yyjson_get_uint(v));
    if (yyjson_is_real(v)) return java_double(yyjson_get_real(v));
    return std::string();  // containers: Jackson's asText() is ""
}

std::optional<std::string> text_field(yyjson_val* obj, const char* key) { return as_text(member(obj, key)); }

bool blank(const std::string& s) {
    return std::all_of(s.begin(), s.end(), [](char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\v';
    });
}

yyjson_val* array_member(yyjson_val* obj, const char* key) {
    yyjson_val* v = member(obj, key);
    return (v != nullptr && yyjson_is_arr(v)) ? v : nullptr;
}

yyjson_val* object_member(yyjson_val* obj, const char* key) {
    yyjson_val* v = member(obj, key);
    return (v != nullptr && yyjson_is_obj(v)) ? v : nullptr;
}

template <typename F>
void each(yyjson_val* arr, F&& f) {
    if (arr == nullptr) return;
    std::size_t idx = 0;
    std::size_t max = 0;
    yyjson_val* e = nullptr;
    yyjson_arr_foreach(arr, idx, max, e) { f(idx, e); }
}

Vec3 vec3_fields(yyjson_val* obj, const char* x, const char* y, const char* z, float fallback) {
    return {float_field(obj, x, fallback), float_field(obj, y, fallback), float_field(obj, z, fallback)};
}

std::string where(const std::string& section, std::size_t i) { return section + "[" + std::to_string(i) + "]"; }

// --- Strict readers for the 1.9 sections (no Java reader to mirror; malformed data is refused).

double strict_number(yyjson_val* v, const std::string& at) {
    if (v == nullptr || !yyjson_is_num(v)) fail(at + " must be a number");
    return yyjson_get_num(v);
}

std::int64_t strict_integer(yyjson_val* v, const std::string& at, std::int64_t lo, std::int64_t hi) {
    if (v == nullptr || !yyjson_is_int(v)) fail(at + " must be an integer");
    std::int64_t i = 0;
    if (yyjson_is_uint(v)) {
        const std::uint64_t u = yyjson_get_uint(v);
        if (u > static_cast<std::uint64_t>(hi)) fail(at + " is out of range");
        i = static_cast<std::int64_t>(u);
    } else {
        i = yyjson_get_sint(v);
    }
    if (i < lo || i > hi) fail(at + " is out of range");
    return i;
}

std::int32_t strict_index(yyjson_val* v, const std::string& at) {
    return static_cast<std::int32_t>(strict_integer(v, at, 0, kI32Max));
}

std::string strict_string(yyjson_val* obj, const char* key, const std::string& at) {
    yyjson_val* v = yyjson_obj_get(obj, key);
    if (v == nullptr || !yyjson_is_str(v)) fail(at + ": \"" + key + "\" must be a string");
    return {yyjson_get_str(v), yyjson_get_len(v)};
}

yyjson_val* strict_array(yyjson_val* obj, const char* key, const std::string& at) {
    yyjson_val* v = yyjson_obj_get(obj, key);
    if (v == nullptr || yyjson_is_null(v)) return nullptr;
    if (!yyjson_is_arr(v)) fail(at + ": \"" + key + "\" must be an array");
    return v;
}

void read_shading(yyjson_val* sh, Shading& out) {
    if (!yyjson_is_obj(sh)) fail("\"shading\" must be an object");
    each(strict_array(sh, "smooth", "shading"), [&](std::size_t i, yyjson_val* row) {
        const std::string at = where("shading.smooth", i);
        if (!yyjson_is_arr(row) || yyjson_arr_size(row) != 2) fail(at + " must be [firstFace, count]");
        const std::int64_t first = strict_integer(yyjson_arr_get(row, 0), at, 0, kMaxFaceId);
        const std::int64_t count = strict_integer(yyjson_arr_get(row, 1), at, 0, kMaxFaceId);
        if (first + count > kMaxFaceId) fail(at + " addresses more than " + std::to_string(kMaxFaceId) + " faces");
        for (std::int64_t f = first; f < first + count; ++f) out.set_smooth_face(static_cast<std::uint32_t>(f), true);
    });
    while (!out.smooth.empty() && out.smooth.back() == 0) out.smooth.pop_back();
    each(strict_array(sh, "sharpEdges", "shading"), [&](std::size_t i, yyjson_val* row) {
        const std::string at = where("shading.sharpEdges", i);
        if (!yyjson_is_arr(row) || yyjson_arr_size(row) != 2) fail(at + " must be [a, b]");
        out.sharp_edges.push_back(static_cast<std::uint32_t>(strict_index(yyjson_arr_get(row, 0), at)));
        out.sharp_edges.push_back(static_cast<std::uint32_t>(strict_index(yyjson_arr_get(row, 1), at)));
    });
    if (yyjson_val* a = member(sh, "autoSmoothAngle")) {
        out.auto_smooth_angle = static_cast<float>(strict_number(a, "shading.autoSmoothAngle"));
    }
    each(strict_array(sh, "customNormals", "shading"), [&](std::size_t i, yyjson_val* row) {
        const std::string at = where("shading.customNormals", i);
        if (!yyjson_is_arr(row) || yyjson_arr_size(row) != 3) fail(at + " must be [vertex, ox, oy]");
        out.normal_vertices.push_back(strict_index(yyjson_arr_get(row, 0), at));
        out.normal_oct.push_back(static_cast<std::int16_t>(strict_integer(yyjson_arr_get(row, 1), at, -32768, 32767)));
        out.normal_oct.push_back(static_cast<std::int16_t>(strict_integer(yyjson_arr_get(row, 2), at, -32768, 32767)));
    });
}

void read_vertex_groups(yyjson_val* arr, std::vector<VertexGroup>& out) {
    if (!yyjson_is_arr(arr)) fail("\"vertexGroups\" must be an array");
    each(arr, [&](std::size_t gi, yyjson_val* g) {
        const std::string at = where("vertexGroups", gi);
        if (!yyjson_is_obj(g)) fail(at + " must be an object");
        VertexGroup group;
        group.name = strict_string(g, "name", at);
        each(strict_array(g, "parts", at), [&](std::size_t pi, yyjson_val* p) {
            const std::string pat = where(at + ".parts", pi);
            if (!yyjson_is_obj(p)) fail(pat + " must be an object");
            GroupPart part;
            part.part_id = strict_string(p, "partId", pat);
            each(strict_array(p, "vertices", pat), [&](std::size_t ri, yyjson_val* row) {
                const std::string rat = where(pat + ".vertices", ri);
                if (!yyjson_is_arr(row) || yyjson_arr_size(row) != 2) fail(rat + " must be [localVertex, weight]");
                part.vertices.push_back(strict_index(yyjson_arr_get(row, 0), rat));
                part.weights.push_back(static_cast<float>(strict_number(yyjson_arr_get(row, 1), rat)));
            });
            group.parts.push_back(std::move(part));
        });
        out.push_back(std::move(group));
    });
}

void read_morphs(yyjson_val* arr, std::vector<Morph>& out) {
    if (!yyjson_is_arr(arr)) fail("\"morphs\" must be an array");
    each(arr, [&](std::size_t mi, yyjson_val* m) {
        const std::string at = where("morphs", mi);
        if (!yyjson_is_obj(m)) fail(at + " must be an object");
        Morph morph;
        morph.name = strict_string(m, "name", at);
        if (yyjson_val* v = member(m, "defaultWeight")) {
            morph.default_weight = static_cast<float>(strict_number(v, at + ".defaultWeight"));
        }
        if (yyjson_val* v = member(m, "min")) morph.min = static_cast<float>(strict_number(v, at + ".min"));
        if (yyjson_val* v = member(m, "max")) morph.max = static_cast<float>(strict_number(v, at + ".max"));
        each(strict_array(m, "parts", at), [&](std::size_t pi, yyjson_val* p) {
            const std::string pat = where(at + ".parts", pi);
            if (!yyjson_is_obj(p)) fail(pat + " must be an object");
            MorphPart part;
            part.part_id = strict_string(p, "partId", pat);
            yyjson_val* rows = strict_array(p, "deltas", pat);
            bool normals = false;
            each(rows, [&](std::size_t, yyjson_val* row) {
                if (yyjson_is_arr(row) && yyjson_arr_size(row) == 7) normals = true;
            });
            each(rows, [&](std::size_t ri, yyjson_val* row) {
                const std::string rat = where(pat + ".deltas", ri);
                const std::size_t n = yyjson_is_arr(row) ? yyjson_arr_size(row) : 0;
                if (n != 4 && n != 7) fail(rat + " must be [v, dx, dy, dz] or [v, dx, dy, dz, nx, ny, nz]");
                part.vertices.push_back(strict_index(yyjson_arr_get(row, 0), rat));
                for (std::size_t k = 1; k <= 3; ++k) {
                    part.deltas.push_back(static_cast<float>(strict_number(yyjson_arr_get(row, k), rat)));
                }
                if (normals) {
                    for (std::size_t k = 4; k <= 6; ++k) {
                        part.normals.push_back(
                                n == 7 ? static_cast<float>(strict_number(yyjson_arr_get(row, k), rat)) : 0.f);
                    }
                }
            });
            morph.parts.push_back(std::move(part));
        });
        out.push_back(std::move(morph));
    });
}

// --- Lenient readers for the 1.0-1.8 sections (the Java readers' defaults).

template <typename T>
std::optional<std::vector<T>> number_array(yyjson_val* mesh, const char* key) {
    yyjson_val* arr = array_member(mesh, key);
    if (arr == nullptr) return std::nullopt;
    std::vector<T> out;
    out.reserve(yyjson_arr_size(arr));
    each(arr, [&](std::size_t, yyjson_val* e) {
        if constexpr (std::is_same_v<T, float>) {
            out.push_back(as_float(e, 0.f));
        } else {
            out.push_back(as_int(e, 0));
        }
    });
    return out;
}

void read_skin(yyjson_val* sk, Skin& skin) {
    if (!yyjson_is_obj(sk)) return;
    each(array_member(sk, "joints"), [&](std::size_t, yyjson_val* j) {
        skin.joints.push_back(as_text(j).value_or("null"));  // Jackson: NullNode.asText() is "null"
    });
    const auto joint_count = static_cast<std::int64_t>(skin.joints.size());
    each(array_member(sk, "parts"), [&](std::size_t, yyjson_val* p) {
        if (!yyjson_is_obj(p)) return;
        auto id = text_field(p, "partId");
        if (!id) return;
        SkinPart part;
        part.part_id = std::move(*id);
        each(array_member(p, "vertices"), [&](std::size_t, yyjson_val* row) {
            if (!yyjson_is_arr(row)) return;
            const std::size_t n = yyjson_arr_size(row);
            if (n < 3 || n % 2 == 0) return;
            const std::size_t count = (n - 1) / 2;
            for (std::size_t k = 0; k < count; ++k) {
                const std::int32_t j = as_int(yyjson_arr_get(row, 1 + 2 * k), 0);
                if (j < 0 || j >= joint_count) return;
            }
            if (part.offsets.empty()) part.offsets.push_back(0);
            part.vertices.push_back(as_int(yyjson_arr_get(row, 0), 0));
            for (std::size_t k = 0; k < count; ++k) {
                part.joints.push_back(as_int(yyjson_arr_get(row, 1 + 2 * k), 0));
                part.weights.push_back(as_float(yyjson_arr_get(row, 2 + 2 * k), 0.f));
            }
            part.offsets.push_back(static_cast<std::int32_t>(part.joints.size()));
        });
        if (!part.vertices.empty()) skin.parts.push_back(std::move(part));
    });
    if (skin.empty()) skin = Skin{};
}

constexpr const char* kKnownKeys[] = {"version", "objectName", "modelType", "geometry", "textureFile",
                                      "mesh", "faceTextures", "parts", "modelTransform", "bones",
                                      "attachmentPoints", "skin", "shading", "vertexGroups", "morphs"};

bool is_known(std::string_view key) {
    return std::any_of(std::begin(kKnownKeys), std::end(kKnownKeys), [&](const char* k) { return key == k; });
}

// Unknown top-level sections, minified with their numbers spelled as in the file.
std::vector<UnknownSection> unknown_sections(std::string_view text, const std::vector<std::string>& keys,
                                             const std::string& what) {
    std::vector<UnknownSection> out;
    if (keys.empty()) return out;
    DocPtr raw = parse_json(text, YYJSON_READ_NUMBER_AS_RAW, what);
    for (const std::string& key : keys) {
        out.push_back({key, minified(yyjson_obj_get(yyjson_doc_get_root(raw.get()), key.c_str()))});
    }
    return out;
}

// Top-level keys, refusing duplicates; returns the unknown ones in file order.
std::vector<std::string> top_level_keys(yyjson_val* root, bool (*known)(std::string_view), const std::string& what) {
    std::unordered_set<std::string> seen;
    std::vector<std::string> unknown;
    std::size_t idx = 0;
    std::size_t max = 0;
    yyjson_val* k = nullptr;
    yyjson_val* v = nullptr;
    yyjson_obj_foreach(root, idx, max, k, v) {
        std::string key(yyjson_get_str(k), yyjson_get_len(k));
        if (!seen.insert(key).second) throw format_error(what + ": duplicate key \"" + key + "\"");
        if (!known(key)) unknown.push_back(std::move(key));
    }
    return unknown;
}

void write_unknown(jackson::Writer& w, const std::vector<UnknownSection>& sections, bool (*known)(std::string_view),
                   const std::string& what) {
    std::unordered_set<std::string> seen;
    for (const UnknownSection& s : sections) {
        if (s.key.empty() || known(s.key) || !seen.insert(s.key).second) {
            throw format_error(what + ": unknown section \"" + s.key + "\" is empty, a known key or a duplicate");
        }
        DocPtr doc = parse_json(s.json, YYJSON_READ_NUMBER_AS_RAW, what + " section \"" + s.key + "\"");
        w.key(s.key);
        w.value(yyjson_doc_get_root(doc.get()));
    }
}

void field_opt(jackson::Writer& w, std::string_view key, const std::optional<std::string>& v) {
    w.key(key);
    if (v) {
        w.string(*v);
    } else {
        w.null();
    }
}

void fields3(jackson::Writer& w, const char* x, const char* y, const char* z, const Vec3& v) {
    w.field_float(x, v.x);
    w.field_float(y, v.y);
    w.field_float(z, v.z);
}

template <typename T>
void array_or_null(jackson::Writer& w, std::string_view key, const std::optional<std::vector<T>>& values) {
    w.key(key);
    if (!values) {
        w.null();
        return;
    }
    w.begin_array();
    for (const T v : *values) {
        if constexpr (std::is_same_v<T, float>) {
            w.number(v);
        } else {
            w.integer(v);
        }
    }
    w.end_array();
}

void check_skin(const Skin& skin) {
    const auto joint_count = static_cast<std::int64_t>(skin.joints.size());
    for (const SkinPart& p : skin.parts) {
        const std::size_t rows = p.vertices.size();
        if (rows == 0) continue;
        if (p.offsets.size() != rows + 1 || p.offsets.front() != 0 || p.offsets.back() < 0 ||
            static_cast<std::size_t>(p.offsets.back()) != p.joints.size() || p.joints.size() != p.weights.size()) {
            fail("skin part \"" + p.part_id + "\": offsets / joints / weights do not match its rows");
        }
        for (std::size_t r = 0; r < rows; ++r) {
            if (p.offsets[r + 1] <= p.offsets[r]) fail("skin part \"" + p.part_id + "\": a row without influences");
        }
        for (const std::int32_t j : p.joints) {
            if (j < 0 || j >= joint_count) fail("skin part \"" + p.part_id + "\": joint index out of range");
        }
    }
}

void write_shading(jackson::Writer& w, const Shading& sh) {
    if (sh.sharp_edges.size() % 2 != 0) fail("shading.sharpEdges must hold pairs");
    if (sh.normal_oct.size() != sh.normal_vertices.size() * 2) fail("shading custom normals: 2 oct values per vertex");
    w.key("shading");
    w.begin_object();
    if (std::any_of(sh.smooth.begin(), sh.smooth.end(), [](std::uint32_t word) { return word != 0; })) {
        w.key("smooth");
        w.begin_array();
        std::int64_t run_start = -1;
        const std::int64_t bits = static_cast<std::int64_t>(sh.smooth.size()) * 32;
        for (std::int64_t f = 0; f <= bits; ++f) {
            const bool on = f < bits && ((sh.smooth[static_cast<std::size_t>(f / 32)] >> (f % 32)) & 1u) != 0;
            if (on && run_start < 0) run_start = f;
            if (!on && run_start >= 0) {
                w.begin_array();
                w.integer(run_start);
                w.integer(f - run_start);
                w.end_array();
                run_start = -1;
            }
        }
        w.end_array();
    }
    if (!sh.sharp_edges.empty()) {
        w.key("sharpEdges");
        w.begin_array();
        for (std::size_t i = 0; i < sh.sharp_edges.size(); i += 2) {
            w.begin_array();
            w.integer(sh.sharp_edges[i]);
            w.integer(sh.sharp_edges[i + 1]);
            w.end_array();
        }
        w.end_array();
    }
    if (sh.auto_smooth_angle) w.field_float("autoSmoothAngle", *sh.auto_smooth_angle);
    if (!sh.normal_vertices.empty()) {
        w.key("customNormals");
        w.begin_array();
        for (std::size_t i = 0; i < sh.normal_vertices.size(); ++i) {
            w.begin_array();
            w.integer(sh.normal_vertices[i]);
            w.integer(sh.normal_oct[2 * i]);
            w.integer(sh.normal_oct[2 * i + 1]);
            w.end_array();
        }
        w.end_array();
    }
    w.end_object();
}

void write_vertex_groups(jackson::Writer& w, const std::vector<VertexGroup>& groups) {
    w.key("vertexGroups");
    w.begin_array();
    for (const VertexGroup& g : groups) {
        w.begin_object();
        w.field("name", g.name);
        w.key("parts");
        w.begin_array();
        for (const GroupPart& p : g.parts) {
            if (p.vertices.size() != p.weights.size()) fail("vertex group \"" + g.name + "\": one weight per vertex");
            w.begin_object();
            w.field("partId", p.part_id);
            w.key("vertices");
            w.begin_array();
            for (std::size_t i = 0; i < p.vertices.size(); ++i) {
                w.begin_array();
                w.integer(p.vertices[i]);
                w.number(p.weights[i]);
                w.end_array();
            }
            w.end_array();
            w.end_object();
        }
        w.end_array();
        w.end_object();
    }
    w.end_array();
}

void write_morphs(jackson::Writer& w, const std::vector<Morph>& morphs) {
    w.key("morphs");
    w.begin_array();
    for (const Morph& m : morphs) {
        w.begin_object();
        w.field("name", m.name);
        w.field_float("defaultWeight", m.default_weight);
        w.field_float("min", m.min);
        w.field_float("max", m.max);
        w.key("parts");
        w.begin_array();
        for (const MorphPart& p : m.parts) {
            const std::size_t rows = p.vertices.size();
            if (p.deltas.size() != rows * 3 || (!p.normals.empty() && p.normals.size() != rows * 3)) {
                fail("morph \"" + m.name + "\": 3 deltas (and 3 or no normals) per vertex");
            }
            w.begin_object();
            w.field("partId", p.part_id);
            w.key("deltas");
            w.begin_array();
            for (std::size_t r = 0; r < rows; ++r) {
                w.begin_array();
                w.integer(p.vertices[r]);
                for (std::size_t k = 0; k < 3; ++k) w.number(p.deltas[r * 3 + k]);
                if (!p.normals.empty()) {
                    for (std::size_t k = 0; k < 3; ++k) w.number(p.normals[r * 3 + k]);
                }
                w.end_array();
            }
            w.end_array();
            w.end_object();
        }
        w.end_array();
        w.end_object();
    }
    w.end_array();
}

}  // namespace

// ------------------------------------------------------------------------------------------------------
// Versions and helpers.

std::optional<Version> parse_version(std::string_view text) {
    const auto dot = text.find('.');
    if (dot == std::string_view::npos || dot == 0 || dot + 1 >= text.size()) return std::nullopt;
    Version v;
    const char* begin = text.data();
    const char* end = begin + text.size();
    auto [p1, e1] = std::from_chars(begin, begin + dot, v.major);
    auto [p2, e2] = std::from_chars(begin + dot + 1, end, v.minor);
    if (e1 != std::errc{} || p1 != begin + dot || e2 != std::errc{} || p2 != end || v.major < 0 || v.minor < 0) {
        return std::nullopt;
    }
    return v;
}

int compare_versions(std::string_view a, std::string_view b) {
    const auto va = parse_version(a);
    const auto vb = parse_version(b);
    if (!va) fail("version \"" + std::string(a) + "\" is not <major>.<minor>");
    if (!vb) fail("version \"" + std::string(b) + "\" is not <major>.<minor>");
    if (*va < *vb) return -1;
    return *vb < *va ? 1 : 0;
}

std::string java_float(float value) {
    std::string out;
    jackson::append_java_number(out, value);
    return out;
}

std::string java_double(double value) {
    std::string out;
    jackson::append_java_number(out, value);
    return out;
}

bool Skin::empty() const {
    return std::all_of(parts.begin(), parts.end(), [](const SkinPart& p) { return p.vertices.empty(); });
}

bool Shading::empty() const {
    return std::all_of(smooth.begin(), smooth.end(), [](std::uint32_t w) { return w == 0; }) && sharp_edges.empty() &&
           !auto_smooth_angle && normal_vertices.empty();
}

bool Shading::smooth_face(std::uint32_t face) const {
    const std::size_t word = face / 32;
    return word < smooth.size() && ((smooth[word] >> (face % 32)) & 1u) != 0;
}

void Shading::set_smooth_face(std::uint32_t face, bool on) {
    const std::size_t word = face / 32;
    if (word >= smooth.size()) {
        if (!on) return;
        smooth.resize(word + 1, 0);
    }
    const std::uint32_t bit = 1u << (face % 32);
    smooth[word] = on ? (smooth[word] | bit) : (smooth[word] & ~bit);
}

bool Document::has_1_9_data() const {
    if (!shading.empty() || !vertex_groups.empty() || !morphs.empty()) return true;
    return face_textures && std::any_of(face_textures->materials.begin(), face_textures->materials.end(),
                                        [](const Material& m) { return m.base_color.has_value(); });
}

std::string Document::written_version() const {
    if (!parse_version(version)) fail("version \"" + version + "\" is not <major>.<minor>");
    if (has_1_9_data() && compare_versions(version, "1.9") < 0) return "1.9";
    return version;
}

const Image* Document::image(std::string_view name) const {
    for (const Image& i : images) {
        if (i.name == name) return &i;
    }
    return nullptr;
}

// ------------------------------------------------------------------------------------------------------
// Reading.

Document read(std::span<const std::byte> archive) {
    std::vector<ZipEntry> entries = read_zip(archive);
    const ZipEntry* manifest = nullptr;
    for (const ZipEntry& e : entries) {
        if (e.name == kManifestEntry) manifest = &e;
    }
    if (manifest == nullptr) fail("missing manifest.json");

    Document d;
    const std::string_view text = manifest_text(manifest->data);
    d.crlf = contains_crlf(text);
    DocPtr doc = parse_json(text, YYJSON_READ_NOFLAG, ".omo manifest.json");
    yyjson_val* root = yyjson_doc_get_root(doc.get());
    if (!yyjson_is_obj(root)) fail("manifest.json must be a JSON object");
    const std::vector<std::string> unknown = top_level_keys(root, is_known, ".omo manifest.json");

    auto version = text_field(root, "version");
    if (!version) fail("manifest.json has no \"version\"");
    const auto v = parse_version(*version);
    if (!v) fail("version \"" + *version + "\" is not <major>.<minor>");
    if (v->major > kVersionMajor) {
        throw version_error(".omo version " + *version + " is newer than this reader (" +
                            std::to_string(kVersionMajor) + "." + std::to_string(kVersionMinor) + ")");
    }
    if (v->major < 1) fail("version \"" + *version + "\" is not supported");
    d.version = std::move(*version);
    d.object_name = text_field(root, "objectName").value_or("");
    d.model_type = text_field(root, "modelType").value_or("");
    d.texture_file = text_field(root, "textureFile").value_or("");

    if (yyjson_val* g = object_member(root, "geometry")) {
        d.geometry.width = int_field(g, "width", 0);
        d.geometry.height = int_field(g, "height", 0);
        d.geometry.depth = int_field(g, "depth", 0);
        if (yyjson_val* p = object_member(g, "position")) {
            d.geometry.position = {as_double(member(p, "x"), 0.0), as_double(member(p, "y"), 0.0),
                                   as_double(member(p, "z"), 0.0)};
        }
    }

    if (yyjson_val* m = object_member(root, "mesh")) {
        Mesh mesh;
        mesh.vertices = number_array<float>(m, "vertices");
        mesh.tex_coords = number_array<float>(m, "texCoords");
        mesh.indices = number_array<std::int32_t>(m, "indices");
        mesh.triangle_to_face_id = number_array<std::int32_t>(m, "triangleToFaceId");
        mesh.uv_mode = text_field(m, "uvMode");
        d.mesh = std::move(mesh);
    }

    if (yyjson_val* ft = object_member(root, "faceTextures")) {
        FaceTextures tex;
        each(array_member(ft, "mappings"), [&](std::size_t, yyjson_val* m) {
            if (!yyjson_is_obj(m)) return;
            FaceMapping fm;
            fm.face_id = int_field(m, "faceId", 0);
            fm.material_id = int_field(m, "materialId", 0);
            fm.u0 = float_field(m, "u0", 0.f);
            fm.v0 = float_field(m, "v0", 0.f);
            fm.u1 = float_field(m, "u1", 0.f);
            fm.v1 = float_field(m, "v1", 0.f);
            fm.uv_rotation_degrees = int_field(m, "uvRotationDegrees", 0);
            fm.auto_resize = bool_field(m, "autoResize", true);
            tex.mappings.push_back(fm);
        });
        each(array_member(ft, "materials"), [&](std::size_t i, yyjson_val* m) {
            if (!yyjson_is_obj(m)) return;
            Material mat;
            mat.material_id = int_field(m, "materialId", 0);
            mat.name = text_field(m, "name");
            mat.texture_file = text_field(m, "textureFile");
            mat.render_layer = text_field(m, "renderLayer");
            mat.emissive = bool_field(m, "emissive", false);
            mat.tint_color = int_field(m, "tintColor", -1);
            if (yyjson_val* bc = member(m, "baseColor")) {
                const std::string at = where("faceTextures.materials", i) + ".baseColor";
                if (!yyjson_is_arr(bc) || yyjson_arr_size(bc) != 4) fail(at + " must be [r, g, b, a]");
                std::array<float, 4> rgba{};
                for (std::size_t k = 0; k < 4; ++k) {
                    rgba[k] = static_cast<float>(strict_number(yyjson_arr_get(bc, k), at));
                }
                mat.base_color = rgba;
            }
            tex.materials.push_back(std::move(mat));
        });
        d.face_textures = std::move(tex);
    }

    each(array_member(root, "parts"), [&](std::size_t, yyjson_val* p) {
        if (!yyjson_is_obj(p)) return;
        auto id = text_field(p, "id");
        if (!id || blank(*id)) return;
        Part part;
        part.id = std::move(*id);
        part.name = text_field(p, "name").value_or("Unnamed Part");
        part.origin = vec3_fields(p, "originX", "originY", "originZ", 0.f);
        part.pos = vec3_fields(p, "posX", "posY", "posZ", 0.f);
        part.rot = vec3_fields(p, "rotX", "rotY", "rotZ", 0.f);
        part.scale = vec3_fields(p, "scaleX", "scaleY", "scaleZ", 1.f);
        part.vertex_start = int_field(p, "vertexStart", 0);
        part.vertex_count = int_field(p, "vertexCount", 0);
        part.index_start = int_field(p, "indexStart", 0);
        part.index_count = int_field(p, "indexCount", 0);
        part.face_start = int_field(p, "faceStart", 0);
        part.face_count = int_field(p, "faceCount", 0);
        part.visible = bool_field(p, "visible", true);
        part.locked = bool_field(p, "locked", false);
        part.parent_id = text_field(p, "parentId");
        part.bone_id = text_field(p, "boneId");
        d.parts.push_back(std::move(part));
    });

    if (yyjson_val* t = object_member(root, "modelTransform")) {
        d.model_transform.pos = vec3_fields(t, "posX", "posY", "posZ", 0.f);
        d.model_transform.rot = vec3_fields(t, "rotX", "rotY", "rotZ", 0.f);
        d.model_transform.scale = vec3_fields(t, "scaleX", "scaleY", "scaleZ", 1.f);
    }

    each(array_member(root, "bones"), [&](std::size_t, yyjson_val* b) {
        if (!yyjson_is_obj(b)) return;
        auto id = text_field(b, "id");
        if (!id || blank(*id)) return;
        Bone bone;
        bone.name = text_field(b, "name").value_or(*id);
        bone.id = std::move(*id);
        bone.parent_bone_id = text_field(b, "parentBoneId");
        bone.origin = vec3_fields(b, "originX", "originY", "originZ", 0.f);
        bone.pos = vec3_fields(b, "posX", "posY", "posZ", 0.f);
        bone.rot = vec3_fields(b, "rotX", "rotY", "rotZ", 0.f);
        bone.endpoint = vec3_fields(b, "endpointX", "endpointY", "endpointZ", 0.f);
        d.bones.push_back(std::move(bone));
    });

    each(array_member(root, "attachmentPoints"), [&](std::size_t, yyjson_val* a) {
        if (!yyjson_is_obj(a)) return;
        auto id = text_field(a, "id");
        if (!id || blank(*id)) return;
        AttachmentPoint ap;
        ap.id = std::move(*id);
        ap.name = text_field(a, "name").value_or("Unnamed Socket");
        ap.parent_part_id = text_field(a, "parentPartId");
        ap.parent_part_name = text_field(a, "parentPartName");
        ap.pos = vec3_fields(a, "posX", "posY", "posZ", 0.f);
        ap.rot = vec3_fields(a, "rotX", "rotY", "rotZ", 0.f);
        ap.scale = vec3_fields(a, "scaleX", "scaleY", "scaleZ", 1.f);
        d.attachment_points.push_back(std::move(ap));
    });

    if (yyjson_val* sk = member(root, "skin")) read_skin(sk, d.skin);
    if (yyjson_val* sh = member(root, "shading")) read_shading(sh, d.shading);
    if (yyjson_val* vg = member(root, "vertexGroups")) read_vertex_groups(vg, d.vertex_groups);
    if (yyjson_val* mo = member(root, "morphs")) read_morphs(mo, d.morphs);

    d.unknown_sections = unknown_sections(text, unknown, ".omo manifest.json");

    for (ZipEntry& e : entries) {
        if (e.name == kManifestEntry) continue;
        if (e.name == kTextureEntry) {
            d.texture = std::move(e.data);
        } else if (e.name.starts_with("material_") && e.name.ends_with(".png")) {
            d.images.push_back({std::move(e.name), std::move(e.data)});
        } else {
            d.extra_entries.push_back({std::move(e.name), std::move(e.data)});
        }
    }
    return d;
}

// ------------------------------------------------------------------------------------------------------
// Writing.

std::string write_manifest(const Document& d) {
    const std::string version = d.written_version();
    check_skin(d.skin);

    std::size_t estimate = 4096;
    if (d.mesh) {
        if (d.mesh->vertices) estimate += d.mesh->vertices->size() * 14;
        if (d.mesh->tex_coords) estimate += d.mesh->tex_coords->size() * 14;
        if (d.mesh->indices) estimate += d.mesh->indices->size() * 7;
        if (d.mesh->triangle_to_face_id) estimate += d.mesh->triangle_to_face_id->size() * 7;
    }
    jackson::Writer w(d.crlf, estimate);
    w.begin_object();
    w.field("version", version);
    w.field("objectName", d.object_name);
    w.field("modelType", d.model_type);
    w.key("geometry");
    w.begin_object();
    w.field_int("width", d.geometry.width);
    w.field_int("height", d.geometry.height);
    w.field_int("depth", d.geometry.depth);
    w.key("position");
    w.begin_object();
    w.key("x");
    w.number(d.geometry.position.x);
    w.key("y");
    w.number(d.geometry.position.y);
    w.key("z");
    w.number(d.geometry.position.z);
    w.end_object();
    w.end_object();
    w.field("textureFile", d.texture_file);

    w.key("mesh");
    if (!d.mesh) {
        w.null();
    } else {
        w.begin_object();
        array_or_null(w, "vertices", d.mesh->vertices);
        array_or_null(w, "texCoords", d.mesh->tex_coords);
        array_or_null(w, "indices", d.mesh->indices);
        array_or_null(w, "triangleToFaceId", d.mesh->triangle_to_face_id);
        field_opt(w, "uvMode", d.mesh->uv_mode);
        w.end_object();
    }

    w.key("faceTextures");
    if (!d.face_textures) {
        w.null();
    } else {
        w.begin_object();
        w.key("mappings");
        w.begin_array();
        for (const FaceMapping& m : d.face_textures->mappings) {
            w.begin_object();
            w.field_int("faceId", m.face_id);
            w.field_int("materialId", m.material_id);
            w.field_float("u0", m.u0);
            w.field_float("v0", m.v0);
            w.field_float("u1", m.u1);
            w.field_float("v1", m.v1);
            w.field_int("uvRotationDegrees", m.uv_rotation_degrees);
            w.field_bool("autoResize", m.auto_resize);
            w.end_object();
        }
        w.end_array();
        w.key("materials");
        w.begin_array();
        for (const Material& m : d.face_textures->materials) {
            w.begin_object();
            w.field_int("materialId", m.material_id);
            field_opt(w, "name", m.name);
            field_opt(w, "textureFile", m.texture_file);
            field_opt(w, "renderLayer", m.render_layer);
            w.field_bool("emissive", m.emissive);
            w.field_int("tintColor", m.tint_color);
            if (m.base_color) {
                w.key("baseColor");
                w.begin_array();
                for (const float c : *m.base_color) w.number(c);
                w.end_array();
            }
            w.end_object();
        }
        w.end_array();
        w.end_object();
    }

    w.key("parts");
    if (d.parts.empty()) {
        w.null();
    } else {
        w.begin_array();
        for (const Part& p : d.parts) {
            w.begin_object();
            w.field("id", p.id);
            w.field("name", p.name);
            fields3(w, "originX", "originY", "originZ", p.origin);
            fields3(w, "posX", "posY", "posZ", p.pos);
            fields3(w, "rotX", "rotY", "rotZ", p.rot);
            fields3(w, "scaleX", "scaleY", "scaleZ", p.scale);
            w.field_int("vertexStart", p.vertex_start);
            w.field_int("vertexCount", p.vertex_count);
            w.field_int("indexStart", p.index_start);
            w.field_int("indexCount", p.index_count);
            w.field_int("faceStart", p.face_start);
            w.field_int("faceCount", p.face_count);
            w.field_bool("visible", p.visible);
            w.field_bool("locked", p.locked);
            field_opt(w, "parentId", p.parent_id);
            field_opt(w, "boneId", p.bone_id);
            w.end_object();
        }
        w.end_array();
    }

    w.key("modelTransform");
    if (d.model_transform.identity()) {
        w.null();
    } else {
        w.begin_object();
        fields3(w, "posX", "posY", "posZ", d.model_transform.pos);
        fields3(w, "rotX", "rotY", "rotZ", d.model_transform.rot);
        fields3(w, "scaleX", "scaleY", "scaleZ", d.model_transform.scale);
        w.end_object();
    }

    w.key("bones");
    if (d.bones.empty()) {
        w.null();
    } else {
        w.begin_array();
        for (const Bone& b : d.bones) {
            w.begin_object();
            w.field("id", b.id);
            w.field("name", b.name);
            field_opt(w, "parentBoneId", b.parent_bone_id);
            fields3(w, "originX", "originY", "originZ", b.origin);
            fields3(w, "posX", "posY", "posZ", b.pos);
            fields3(w, "rotX", "rotY", "rotZ", b.rot);
            fields3(w, "endpointX", "endpointY", "endpointZ", b.endpoint);
            w.end_object();
        }
        w.end_array();
    }

    w.key("attachmentPoints");
    if (d.attachment_points.empty()) {
        w.null();
    } else {
        w.begin_array();
        for (const AttachmentPoint& a : d.attachment_points) {
            w.begin_object();
            w.field("id", a.id);
            w.field("name", a.name);
            field_opt(w, "parentPartId", a.parent_part_id);
            field_opt(w, "parentPartName", a.parent_part_name);
            fields3(w, "posX", "posY", "posZ", a.pos);
            fields3(w, "rotX", "rotY", "rotZ", a.rot);
            fields3(w, "scaleX", "scaleY", "scaleZ", a.scale);
            w.end_object();
        }
        w.end_array();
    }

    w.key("skin");
    if (d.skin.empty()) {
        w.null();
    } else {
        w.begin_object();
        w.key("joints");
        w.begin_array();
        for (const std::string& j : d.skin.joints) w.string(j);
        w.end_array();
        w.key("parts");
        w.begin_array();
        for (const SkinPart& p : d.skin.parts) {
            if (p.vertices.empty()) continue;
            w.begin_object();
            w.field("partId", p.part_id);
            w.key("vertices");
            w.begin_array();
            for (std::size_t r = 0; r < p.vertices.size(); ++r) {
                w.begin_array();
                w.integer(p.vertices[r]);
                const auto end = static_cast<std::size_t>(p.offsets[r + 1]);
                for (auto k = static_cast<std::size_t>(p.offsets[r]); k < end; ++k) {
                    w.integer(p.joints[k]);
                    w.number(p.weights[k]);
                }
                w.end_array();
            }
            w.end_array();
            w.end_object();
        }
        w.end_array();
        w.end_object();
    }

    if (!d.shading.empty()) write_shading(w, d.shading);
    if (!d.vertex_groups.empty()) write_vertex_groups(w, d.vertex_groups);
    if (!d.morphs.empty()) write_morphs(w, d.morphs);

    write_unknown(w, d.unknown_sections, is_known, ".omo manifest.json");
    w.end_object();
    return w.take();
}

std::vector<std::byte> write(const Document& d) {
    std::vector<ZipEntry> entries;
    entries.reserve(2 + d.images.size() + d.extra_entries.size());
    entries.push_back({kManifestEntry, bytes_of(write_manifest(d)), false});
    if (!d.texture.empty()) entries.push_back({kTextureEntry, d.texture, true});
    for (const Image& i : d.images) entries.push_back({i.name, i.data, true});
    for (const Image& e : d.extra_entries) entries.push_back({e.name, e.data, false});
    return write_zip(entries);
}

// ------------------------------------------------------------------------------------------------------
// .omt

namespace omt {

namespace {

[[noreturn]] void omt_fail(const std::string& message) { throw format_error(".omt: " + message); }

constexpr const char* kOmtKnownKeys[] = {"version",          "canvasSize",   "layers",
                                         "activeLayerIndex", "faceMappings", "materials"};

bool omt_known(std::string_view key) {
    return std::any_of(std::begin(kOmtKnownKeys), std::end(kOmtKnownKeys), [&](const char* k) { return key == k; });
}

}  // namespace

Texture read(std::span<const std::byte> archive) {
    std::vector<ZipEntry> entries = read_zip(archive);
    const ZipEntry* manifest = nullptr;
    for (const ZipEntry& e : entries) {
        if (e.name == kManifestEntry) manifest = &e;
    }
    if (manifest == nullptr) omt_fail("missing manifest.json");

    Texture t;
    const std::string_view text = manifest_text(manifest->data);
    t.crlf = contains_crlf(text);
    DocPtr doc = parse_json(text, YYJSON_READ_NOFLAG, ".omt manifest.json");
    yyjson_val* root = yyjson_doc_get_root(doc.get());
    if (!yyjson_is_obj(root)) omt_fail("manifest.json must be a JSON object");
    const std::vector<std::string> unknown = top_level_keys(root, omt_known, ".omt manifest.json");

    // Jackson DTO defaults: a missing field reads as the Java default (false / 0).
    t.version = text_field(root, "version").value_or("");
    t.width = 0;
    t.height = 0;
    if (yyjson_val* c = object_member(root, "canvasSize")) {
        t.width = int_field(c, "width", 0);
        t.height = int_field(c, "height", 0);
    }
    each(array_member(root, "layers"), [&](std::size_t, yyjson_val* l) {
        if (!yyjson_is_obj(l)) return;
        Layer layer;
        layer.name = text_field(l, "name").value_or("");
        layer.visible = bool_field(l, "visible", false);
        layer.opacity = float_field(l, "opacity", 0.f);
        layer.data_file = text_field(l, "dataFile").value_or("");
        t.layers.push_back(std::move(layer));
    });
    t.active_layer = int_field(root, "activeLayerIndex", 0);
    each(array_member(root, "faceMappings"), [&](std::size_t, yyjson_val* m) {
        if (!yyjson_is_obj(m)) return;
        FaceMapping fm;
        fm.face_id = int_field(m, "faceId", 0);
        fm.material_id = int_field(m, "materialId", 0);
        fm.u0 = float_field(m, "u0", 0.f);
        fm.v0 = float_field(m, "v0", 0.f);
        fm.u1 = float_field(m, "u1", 0.f);
        fm.v1 = float_field(m, "v1", 0.f);
        fm.uv_rotation = int_field(m, "uvRotation", 0);
        t.face_mappings.push_back(fm);
    });
    each(array_member(root, "materials"), [&](std::size_t, yyjson_val* m) {
        if (!yyjson_is_obj(m)) return;
        Material mat;
        mat.material_id = int_field(m, "materialId", 0);
        mat.name = text_field(m, "name").value_or("");
        mat.render_layer = text_field(m, "renderLayer").value_or("");
        mat.texture_file = text_field(m, "textureFile").value_or("");
        t.materials.push_back(std::move(mat));
    });
    t.unknown_sections = unknown_sections(text, unknown, ".omt manifest.json");

    for (ZipEntry& e : entries) {
        if (e.name == kManifestEntry) continue;
        auto layer = std::find_if(t.layers.begin(), t.layers.end(),
                                  [&](const Layer& l) { return l.data_file == e.name && l.png.empty(); });
        if (layer != t.layers.end() && !e.data.empty()) {
            layer->png = std::move(e.data);
        } else {
            t.other_entries.push_back({std::move(e.name), std::move(e.data)});
        }
    }
    return t;
}

std::string write_manifest(const Texture& t) {
    jackson::Writer w(t.crlf, 1024);
    w.begin_object();
    w.field("version", t.version);
    w.key("canvasSize");
    w.begin_object();
    w.field_int("width", t.width);
    w.field_int("height", t.height);
    w.end_object();
    w.key("layers");
    w.begin_array();
    for (const Layer& l : t.layers) {
        w.begin_object();
        w.field("name", l.name);
        w.field_bool("visible", l.visible);
        w.field_float("opacity", l.opacity);
        w.field("dataFile", l.data_file);
        w.end_object();
    }
    w.end_array();
    w.field_int("activeLayerIndex", t.active_layer);
    w.key("faceMappings");
    w.begin_array();
    for (const FaceMapping& m : t.face_mappings) {
        w.begin_object();
        w.field_int("faceId", m.face_id);
        w.field_int("materialId", m.material_id);
        w.field_float("u0", m.u0);
        w.field_float("v0", m.v0);
        w.field_float("u1", m.u1);
        w.field_float("v1", m.v1);
        w.field_int("uvRotation", m.uv_rotation);
        w.end_object();
    }
    w.end_array();
    w.key("materials");
    w.begin_array();
    for (const Material& m : t.materials) {
        w.begin_object();
        w.field_int("materialId", m.material_id);
        w.field("name", m.name);
        w.field("renderLayer", m.render_layer);
        w.field("textureFile", m.texture_file);
        w.end_object();
    }
    w.end_array();
    write_unknown(w, t.unknown_sections, omt_known, ".omt manifest.json");
    w.end_object();
    return w.take();
}

std::vector<std::byte> write(const Texture& t) {
    std::vector<ZipEntry> entries;
    entries.reserve(1 + t.layers.size() + t.other_entries.size());
    entries.push_back({kManifestEntry, bytes_of(write_manifest(t)), false});
    for (const Layer& l : t.layers) {
        if (l.png.empty()) continue;
        if (l.data_file.empty()) omt_fail("layer \"" + l.name + "\" has no dataFile");
        entries.push_back({l.data_file, l.png, true});
    }
    for (const Image& e : t.other_entries) entries.push_back({e.name, e.data, false});
    return write_zip(entries);
}

}  // namespace omt

}  // namespace cenda::omformat::omo
