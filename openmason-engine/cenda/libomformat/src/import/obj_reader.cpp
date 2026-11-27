// Wavefront OBJ + MTL reader (OpenMason #173). Own parser; polygons are kept as polygons (corner loops), never
// triangulated. See omformat/import.hpp for the statements read.

#include "import_internal.hpp"

#include <charconv>
#include <cmath>
#include <map>
#include <set>
#include <unordered_map>

namespace cenda::omformat::import {

namespace {

[[noreturn]] void fail(std::size_t line, const std::string& msg) {
    throw format_error("OBJ line " + std::to_string(line) + ": " + msg);
}
[[noreturn]] void fail_mtl(const std::string& file, std::size_t line, const std::string& msg) {
    throw format_error("MTL '" + file + "' line " + std::to_string(line) + ": " + msg);
}

bool is_space(char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\f' || c == '\v'; }

std::string_view trim(std::string_view s) {
    while (!s.empty() && is_space(s.front())) s.remove_prefix(1);
    while (!s.empty() && is_space(s.back())) s.remove_suffix(1);
    return s;
}

std::vector<std::string_view> tokens(std::string_view s) {
    std::vector<std::string_view> out;
    std::size_t i = 0;
    while (i < s.size()) {
        while (i < s.size() && is_space(s[i])) ++i;
        const std::size_t b = i;
        while (i < s.size() && !is_space(s[i])) ++i;
        if (i > b) out.push_back(s.substr(b, i - b));
    }
    return out;
}

std::optional<float> parse_float(std::string_view t) {
    if (!t.empty() && t.front() == '+') t.remove_prefix(1);
    float v = 0.f;
    const auto r = std::from_chars(t.data(), t.data() + t.size(), v);
    if (r.ec != std::errc() || r.ptr != t.data() + t.size() || !std::isfinite(v)) return std::nullopt;
    return v;
}

std::optional<long long> parse_int(std::string_view t) {
    if (!t.empty() && t.front() == '+') t.remove_prefix(1);
    long long v = 0;
    const auto r = std::from_chars(t.data(), t.data() + t.size(), v);
    if (r.ec != std::errc() || r.ptr != t.data() + t.size()) return std::nullopt;
    return v;
}

// Logical lines: '\n' separated, "\r\n" tolerated, a trailing '\' joins the next physical line. first = the
// 1-based physical line number the logical line starts on.
struct Line {
    std::size_t number;
    std::string text;
};

std::vector<Line> logical_lines(std::string_view text) {
    std::vector<Line> out;
    std::size_t number = 0;
    std::string pending;
    std::size_t pending_start = 0;
    bool continuing = false;
    std::size_t pos = 0;
    while (pos <= text.size()) {
        const std::size_t nl = text.find('\n', pos);
        std::string_view raw = text.substr(pos, nl == std::string_view::npos ? std::string_view::npos : nl - pos);
        ++number;
        if (!raw.empty() && raw.back() == '\r') raw.remove_suffix(1);
        if (!continuing) pending_start = number;
        std::string_view body = raw;
        const std::string_view t = trim(body);
        const bool cont = !t.empty() && t.back() == '\\';
        if (cont) {
            body = t.substr(0, t.size() - 1);
            pending.append(body);
            pending.push_back(' ');
            continuing = true;
        } else {
            pending.append(body);
            out.push_back({pending_start, std::move(pending)});
            pending.clear();
            continuing = false;
        }
        if (nl == std::string_view::npos) break;
        pos = nl + 1;
    }
    if (continuing) out.push_back({pending_start, std::move(pending)});
    return out;
}

std::string bytes_to_text(const std::vector<std::byte>& b) {
    std::string s(b.size(), '\0');
    for (std::size_t i = 0; i < b.size(); ++i) s[i] = static_cast<char>(b[i]);
    return s;
}

std::string normalise_path(std::string_view p) {
    std::string s(p);
    for (char& c : s) {
        if (c == '\\') c = '/';
    }
    return s;
}

// ---- builders -------------------------------------------------------------------------------------------------

struct Corner {
    long long v, vt, vn;  // 0-based; -1 = absent
};

struct Face {
    std::vector<Corner> corners;
    std::uint32_t smoothing;
    std::size_t line;
};

struct Builder {
    std::string group;
    std::string material;  // usemtl name, "" none
    std::vector<Face> faces;
};

struct Object {
    std::string name;
    std::vector<Builder> builders;
    std::map<std::pair<std::string, std::string>, std::size_t> by_key;
};

struct CornerHash {
    std::size_t operator()(const std::array<long long, 3>& k) const noexcept {
        std::size_t h = std::hash<long long>{}(k[0]);
        h ^= std::hash<long long>{}(k[1]) + 0x9e3779b97f4a7c15ull + (h << 6) + (h >> 2);
        h ^= std::hash<long long>{}(k[2]) + 0x9e3779b97f4a7c15ull + (h << 6) + (h >> 2);
        return h;
    }
};

struct ObjReader {
    explicit ObjReader(const Resolver& r) : resolve(r) {}

    const Resolver& resolve;
    Scene scene;

    std::vector<Float3> positions;
    std::vector<Float3> colors;
    std::vector<bool> has_color;
    std::vector<Float2> uvs;
    std::vector<Float3> normals;

    std::vector<Object> objects;
    std::string group;
    std::string material;
    std::uint32_t smoothing = 0;

    std::map<std::string, int, std::less<>> material_index;
    std::map<std::string, int, std::less<>> image_index;
    std::set<std::string, std::less<>> loaded_mtl;
    std::set<std::string, std::less<>> warned;

    void warn(std::string w) { scene.warnings.push_back(std::move(w)); }

    void warn_once(const std::string& key, std::string w) {
        if (warned.insert(key).second) warn(std::move(w));
    }

    Object& current_object() {
        if (objects.empty()) objects.push_back({});
        return objects.back();
    }

    Builder& current_builder() {
        Object& o = current_object();
        const auto key = std::make_pair(group, material);
        auto it = o.by_key.find(key);
        if (it != o.by_key.end()) return o.builders[it->second];
        o.by_key.emplace(key, o.builders.size());
        o.builders.push_back({group, material, {}});
        return o.builders.back();
    }

    // OBJ index -> 0-based; negative = relative to the count read so far. Positive ones are range-checked at the
    // end (a forward reference is tolerated).
    static long long resolve_index(std::size_t line, std::string_view tok, std::size_t count, const char* what) {
        const auto n = parse_int(tok);
        if (!n) fail(line, std::string("bad ") + what + " index '" + std::string(tok) + "'");
        if (*n == 0) fail(line, std::string(what) + " index 0");
        if (*n > 0) return *n - 1;
        const long long rel = static_cast<long long>(count) + *n;
        if (rel < 0) fail(line, std::string(what) + " index " + std::string(tok) + " out of range");
        return rel;
    }

    void face(std::size_t line, const std::vector<std::string_view>& tk) {
        Face f;
        f.smoothing = smoothing;
        f.line = line;
        for (std::size_t i = 1; i < tk.size(); ++i) {
            const std::string_view c = tk[i];
            Corner corner{-1, -1, -1};
            const std::size_t s1 = c.find('/');
            const std::string_view vs = c.substr(0, s1);
            corner.v = resolve_index(line, vs, positions.size(), "vertex");
            if (s1 != std::string_view::npos) {
                const std::string_view rest = c.substr(s1 + 1);
                const std::size_t s2 = rest.find('/');
                const std::string_view ts = rest.substr(0, s2);
                if (!ts.empty()) corner.vt = resolve_index(line, ts, uvs.size(), "texcoord");
                if (s2 != std::string_view::npos) {
                    const std::string_view ns = rest.substr(s2 + 1);
                    if (ns.find('/') != std::string_view::npos) fail(line, "bad face corner '" + std::string(c) + "'");
                    if (!ns.empty()) corner.vn = resolve_index(line, ns, normals.size(), "normal");
                }
            }
            f.corners.push_back(corner);
        }
        if (f.corners.size() < 3) {
            warn("OBJ line " + std::to_string(line) + ": face with fewer than 3 corners skipped");
            return;
        }
        current_builder().faces.push_back(std::move(f));
    }

    void floats(std::size_t line, const std::vector<std::string_view>& tk, std::size_t first, std::size_t count,
                float* out) {
        for (std::size_t i = 0; i < count; ++i) {
            const auto v = parse_float(tk[first + i]);
            if (!v) fail(line, "bad number '" + std::string(tk[first + i]) + "'");
            out[i] = *v;
        }
    }

    // ---- MTL ----

    int image_for(const std::string& ref) {
        auto it = image_index.find(ref);
        if (it != image_index.end()) return it->second;
        Image img;
        img.name = ref;
        img.uri = ref;
        std::optional<std::vector<std::byte>> got;
        if (resolve) got = resolve(ref);
        if (!got) {
            img.missing = true;
            warn("texture '" + ref + "' not found");
        } else {
            img.bytes = std::move(*got);
        }
        detail::finish_image(img, "'" + ref + "'", scene.warnings);
        const int idx = static_cast<int>(scene.images.size());
        scene.images.push_back(std::move(img));
        image_index.emplace(ref, idx);
        return idx;
    }

    void load_mtl(const std::string& file, const std::string& text) {
        Material* m = nullptr;
        bool has_d = false;
        for (const Line& ln : logical_lines(text)) {
            const std::string_view t = trim(ln.text);
            if (t.empty() || t.front() == '#') continue;
            const auto tk = tokens(t);
            const std::string_view kw = tk[0];
            const std::string_view rest = trim(t.substr(kw.size()));
            if (kw == "newmtl") {
                if (rest.empty()) fail_mtl(file, ln.number, "newmtl without a name");
                const std::string name(rest);
                if (material_index.contains(name)) {
                    warn("MTL '" + file + "': material '" + name + "' defined twice, first kept");
                    m = nullptr;
                    continue;
                }
                material_index.emplace(name, static_cast<int>(scene.materials.size()));
                scene.materials.push_back({});
                m = &scene.materials.back();
                m->name = name;
                m->metallic = 0.f;
                m->roughness = 1.f;
                has_d = false;
                continue;
            }
            if (m == nullptr) continue;  // statements before the first newmtl (or of a duplicate) are ignored
            auto number = [&](std::size_t i) {
                if (i >= tk.size()) fail_mtl(file, ln.number, "missing value");
                const auto v = parse_float(tk[i]);
                if (!v) fail_mtl(file, ln.number, "bad number '" + std::string(tk[i]) + "'");
                return *v;
            };
            if (kw == "Kd" || kw == "Ke") {
                if (tk.size() > 1 && (tk[1] == "spectral" || tk[1] == "xyz")) {
                    warn_once("mtl-colour-model", "MTL: spectral / xyz colours ignored");
                    continue;
                }
                const float r = number(1);
                const float g = tk.size() > 2 ? number(2) : r;
                const float b = tk.size() > 3 ? number(3) : r;
                if (kw == "Kd") {
                    m->base_color[0] = r;
                    m->base_color[1] = g;
                    m->base_color[2] = b;
                } else {
                    m->emissive = {r, g, b};
                }
            } else if (kw == "d" || kw == "Tr") {
                std::size_t i = 1;
                if (kw == "d" && i < tk.size() && tk[i] == "-halo") ++i;
                const float v = number(i);
                if (kw == "d") {
                    m->base_color[3] = v;
                    has_d = true;
                } else if (!has_d) {
                    m->base_color[3] = 1.f - v;
                }
                m->alpha_mode = m->base_color[3] < 1.f ? AlphaMode::Blend : AlphaMode::Opaque;
            } else if (kw == "map_Kd") {
                std::size_t i = 1;
                while (i < tk.size() && tk[i].size() > 1 && tk[i].front() == '-') {
                    const std::string_view opt = tk[i];
                    ++i;
                    std::size_t max_args = 1;
                    if (opt == "-o" || opt == "-s" || opt == "-t") max_args = 3;
                    else if (opt == "-mm") max_args = 2;
                    for (std::size_t a = 0; a < max_args && i < tk.size(); ++a) {
                        // numeric arguments are optional after the first for -o/-s/-t; on/off and names are single
                        if (a > 0 && !parse_float(tk[i])) break;
                        ++i;
                    }
                }
                if (i >= tk.size()) fail_mtl(file, ln.number, "map_Kd without a file");
                // The rest of the line (spaces kept) is the file name.
                const std::size_t start = static_cast<std::size_t>(tk[i].data() - t.data());
                const std::string ref = normalise_path(trim(t.substr(start)));
                TextureRef tr;
                tr.image = image_for(ref);
                m->base_color_texture = tr;
            }
            // Ka, Ks, Ns, Ni, illum, other maps: not used by the scene.
        }
    }

    void mtllib(std::size_t line, std::string_view rest) {
        std::vector<std::string> names;
        const std::string whole = normalise_path(trim(rest));
        if (whole.empty()) fail(line, "mtllib without a file");
        // Whole rest first (a name with spaces), else each whitespace-separated name.
        std::optional<std::vector<std::byte>> got;
        if (!loaded_mtl.contains(whole) && resolve) got = resolve(whole);
        if (got || loaded_mtl.contains(whole)) {
            if (got) {
                loaded_mtl.insert(whole);
                load_mtl(whole, bytes_to_text(*got));
            }
            return;
        }
        const auto tk = tokens(rest);
        for (std::string_view name_view : tk) {
            const std::string name = normalise_path(name_view);
            if (loaded_mtl.contains(name)) continue;
            std::optional<std::vector<std::byte>> file;
            if (resolve) file = resolve(name);
            if (!file) {
                warn("material library '" + name + "' not found");
                continue;
            }
            loaded_mtl.insert(name);
            load_mtl(name, bytes_to_text(*file));
        }
    }

    // ---- statements ----

    void parse(std::string_view text) {
        for (const Line& ln : logical_lines(text)) {
            const std::string_view t = trim(ln.text);
            if (t.empty() || t.front() == '#') continue;
            const auto tk = tokens(t);
            const std::string_view kw = tk[0];
            const std::string_view rest = trim(t.substr(kw.size()));
            const std::size_t line = ln.number;
            if (kw == "v") {
                if (tk.size() < 4) fail(line, "v needs x y z");
                Float3 p{};
                floats(line, tk, 1, 3, p.data());
                positions.push_back(p);
                if (tk.size() >= 7) {
                    Float3 c{};
                    floats(line, tk, 4, 3, c.data());
                    colors.push_back(c);
                    has_color.push_back(true);
                } else {
                    if (tk.size() == 5) {
                        float w = 0.f;  // checked, not used
                        floats(line, tk, 4, 1, &w);
                    }
                    colors.push_back({1.f, 1.f, 1.f});
                    has_color.push_back(false);
                }
            } else if (kw == "vt") {
                if (tk.size() < 2) fail(line, "vt needs u");
                Float2 uv{0.f, 0.f};
                floats(line, tk, 1, std::min<std::size_t>(tk.size() - 1, 2), uv.data());
                uvs.push_back(uv);
            } else if (kw == "vn") {
                if (tk.size() < 4) fail(line, "vn needs x y z");
                Float3 n{};
                floats(line, tk, 1, 3, n.data());
                normals.push_back(n);
            } else if (kw == "f" || kw == "fo") {
                face(line, tk);
            } else if (kw == "o") {
                objects.push_back({});
                objects.back().name = std::string(rest);
            } else if (kw == "g") {
                group = std::string(rest);
            } else if (kw == "s") {
                if (rest.empty() || rest == "off") {
                    smoothing = 0;
                } else if (rest == "on") {
                    smoothing = 1;
                } else {
                    const auto n = parse_int(rest);
                    if (!n || *n < 0 || *n > 0xffffffffll) fail(line, "bad smoothing group '" + std::string(rest) + "'");
                    smoothing = static_cast<std::uint32_t>(*n);
                }
            } else if (kw == "usemtl") {
                material = std::string(rest);
            } else if (kw == "mtllib") {
                mtllib(line, rest);
            } else if (kw == "l" || kw == "p" || kw == "vp" || kw == "cstype" || kw == "deg" || kw == "bmat" ||
                       kw == "step" || kw == "curv" || kw == "curv2" || kw == "surf" || kw == "parm" ||
                       kw == "trim" || kw == "hole" || kw == "scrv" || kw == "sp" || kw == "end" || kw == "con" ||
                       kw == "mg" || kw == "bevel" || kw == "c_interp" || kw == "d_interp" || kw == "lod" ||
                       kw == "shadow_obj" || kw == "trace_obj" || kw == "ctech" || kw == "stech" ||
                       kw == "maplib" || kw == "usemap") {
                warn_once(std::string(kw), "OBJ: '" + std::string(kw) + "' statements ignored");
            } else {
                warn_once(std::string(kw), "OBJ: unknown statement '" + std::string(kw) + "' ignored");
            }
        }
    }

    // ---- scene ----

    Primitive build(const Builder& b) {
        Primitive p;
        p.group = b.group;
        bool any_uv = false, all_uv = true, any_n = false, all_n = true, any_color = false;
        for (const Face& f : b.faces) {
            for (const Corner& c : f.corners) {
                if (c.v < 0 || static_cast<std::size_t>(c.v) >= positions.size())
                    fail(f.line, "vertex index out of range");
                if (c.vt >= 0 && static_cast<std::size_t>(c.vt) >= uvs.size()) fail(f.line, "texcoord index out of range");
                if (c.vn >= 0 && static_cast<std::size_t>(c.vn) >= normals.size()) fail(f.line, "normal index out of range");
                if (c.vt >= 0) any_uv = true;
                else all_uv = false;
                if (c.vn >= 0) any_n = true;
                else all_n = false;
                if (has_color[static_cast<std::size_t>(c.v)]) any_color = true;
            }
        }
        const std::string label = "OBJ group '" + b.group + "' material '" + b.material + "'";
        if (any_uv && !all_uv) warn(label + ": some corners have no texcoord (0,0 used)");
        if (any_n && !all_n) warn(label + ": some corners have no normal, normals dropped");
        const bool use_n = any_n && all_n;

        std::unordered_map<std::array<long long, 3>, std::uint32_t, CornerHash> vertex_of;
        if (any_uv) p.texcoords.resize(1);
        for (const Face& f : b.faces) {
            for (const Corner& c : f.corners) {
                const std::array<long long, 3> key{c.v, any_uv ? c.vt : -1, use_n ? c.vn : -1};
                auto [it, fresh] = vertex_of.try_emplace(key, static_cast<std::uint32_t>(p.positions.size()));
                if (fresh) {
                    const auto vi = static_cast<std::size_t>(c.v);
                    p.positions.push_back(positions[vi]);
                    p.source_vertex.push_back(static_cast<std::uint32_t>(vi));
                    if (any_uv) p.texcoords[0].push_back(c.vt >= 0 ? uvs[static_cast<std::size_t>(c.vt)] : Float2{0.f, 0.f});
                    if (use_n) p.normals.push_back(normals[static_cast<std::size_t>(c.vn)]);
                    if (any_color) {
                        const Float3& col = colors[vi];
                        p.colors.push_back({col[0], col[1], col[2], 1.f});
                    }
                }
                p.indices.push_back(it->second);
            }
            p.face_sizes.push_back(static_cast<std::uint32_t>(f.corners.size()));
            p.face_smoothing.push_back(f.smoothing);
        }
        if (!b.material.empty()) {
            auto it = material_index.find(b.material);
            if (it == material_index.end()) {
                warn_once("usemtl:" + b.material, "OBJ: material '" + b.material + "' not defined");
            } else {
                p.material = it->second;
            }
        }
        return p;
    }

    void finish() {
        scene.format = SourceFormat::Obj;
        scene.uv_origin = UvOrigin::BottomLeft;
        for (const Object& o : objects) {
            Mesh mesh;
            mesh.name = o.name;
            for (const Builder& b : o.builders) {
                if (!b.faces.empty()) mesh.primitives.push_back(build(b));
            }
            if (mesh.primitives.empty()) continue;
            Node node;
            node.name = o.name;
            node.mesh = static_cast<int>(scene.meshes.size());
            scene.meshes.push_back(std::move(mesh));
            scene.roots.push_back(static_cast<int>(scene.nodes.size()));
            scene.nodes.push_back(std::move(node));
        }
    }
};

}  // namespace

Scene read_obj(std::string_view obj_text, const Resolver& resolve) {
    ObjReader r(resolve);
    r.parse(obj_text);
    r.finish();
    return std::move(r.scene);
}

}  // namespace cenda::omformat::import
