#include "omformat/omanim.hpp"

#include "json_util.hpp"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <unordered_map>
#include <unordered_set>

namespace cenda::omformat::omanim {

namespace {

using json::CharPtr;
using json::DocPtr;
using json::MutDocPtr;

[[noreturn]] void fail(const std::string& message) {
    throw format_error(".omanim: " + message);
}

DocPtr parse(std::string_view text, const std::string& what) {
    yyjson_read_err err{};
    DocPtr doc(yyjson_read_opts(const_cast<char*>(text.data()), text.size(), YYJSON_READ_NOFLAG, nullptr, &err));
    if (!doc) fail(what + " is not valid JSON (" + err.msg + " at byte " + std::to_string(err.pos) + ")");
    return doc;
}

std::string text_of(const std::vector<std::byte>& data) {
    return {reinterpret_cast<const char*>(data.data()), data.size()};
}

std::string str(yyjson_val* v) { return {yyjson_get_str(v), yyjson_get_len(v)}; }

std::string opt_str(yyjson_val* obj, const char* key, const std::string& fallback) {
    yyjson_val* v = yyjson_obj_get(obj, key);
    if (v == nullptr || yyjson_is_null(v)) return fallback;
    if (!yyjson_is_str(v)) fail(std::string("\"") + key + "\" must be a string");
    return str(v);
}

float number(yyjson_val* v, const std::string& where) {
    if (!yyjson_is_num(v)) fail(where + " must be a number");
    const double d = yyjson_get_num(v);
    if (!std::isfinite(d)) fail(where + " must be finite");
    return static_cast<float>(d);
}

float opt_num(yyjson_val* obj, const char* key, float fallback, const std::string& where) {
    yyjson_val* v = yyjson_obj_get(obj, key);
    if (v == nullptr || yyjson_is_null(v)) return fallback;
    return number(v, where + "." + key);
}

bool opt_bool(yyjson_val* obj, const char* key, bool fallback) {
    yyjson_val* v = yyjson_obj_get(obj, key);
    if (v == nullptr || yyjson_is_null(v)) return fallback;
    if (!yyjson_is_bool(v)) fail(std::string("\"") + key + "\" must be true or false");
    return yyjson_get_bool(v);
}

// Like the Java reader: a missing or short array falls back.
void vec3(yyjson_val* obj, const char* key, float fallback, float out[3], const std::string& where) {
    yyjson_val* v = yyjson_obj_get(obj, key);
    if (v == nullptr || !yyjson_is_arr(v) || yyjson_arr_size(v) < 3) {
        out[0] = out[1] = out[2] = fallback;
        return;
    }
    for (std::size_t i = 0; i < 3; ++i) out[i] = number(yyjson_arr_get(v, i), where + "." + key);
}

yyjson_val* opt_array(yyjson_val* obj, const char* key) {
    yyjson_val* v = yyjson_obj_get(obj, key);
    if (v == nullptr || yyjson_is_null(v)) return nullptr;
    if (!yyjson_is_arr(v)) fail(std::string("\"") + key + "\" must be an array");
    return v;
}

std::string minified(yyjson_val* v) {
    std::size_t len = 0;
    CharPtr text(yyjson_val_write(v, YYJSON_WRITE_NOFLAG, &len));
    if (!text) fail("cannot serialize a JSON value");
    return {text.get(), len};
}

std::string upper(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return static_cast<char>(std::toupper(c)); });
    return s;
}

constexpr const char* kKnownKeys[] = {"version", "name", "fps", "duration", "loop", "modelRef", "tracks",
                                      "requiredParts", "layer", "notifies", "curves", "syncMarkers",
                                      "rootMotion", "additive"};

bool known(std::string_view key) {
    return std::any_of(std::begin(kKnownKeys), std::end(kKnownKeys), [&](const char* k) { return key == k; });
}

const char* interp_name(CurveInterp i) {
    switch (i) {
        case CurveInterp::Step: return "STEP";
        case CurveInterp::Cubic: return "CUBIC";
        default: return "LINEAR";
    }
}

CurveInterp interp_from(const std::string& s, const std::string& where) {
    const std::string u = upper(s);
    if (u.empty() || u == "LINEAR") return CurveInterp::Linear;
    if (u == "STEP") return CurveInterp::Step;
    if (u == "CUBIC") return CurveInterp::Cubic;
    fail(where + ": interp must be LINEAR, STEP or CUBIC");
}

Track read_track(yyjson_val* ref, std::size_t index, const std::unordered_map<std::string, const ZipEntry*>& entries) {
    const std::string where = "tracks[" + std::to_string(index) + "]";
    if (!yyjson_is_obj(ref)) fail(where + " must be an object");
    Track t;
    t.role = opt_str(ref, "role", "");
    t.part_id = opt_str(ref, "partId", t.role.empty() ? "" : kRolePrefix + t.role);
    t.part_name = opt_str(ref, "partName", t.part_id);
    t.data_file = opt_str(ref, "dataFile", "");
    t.bone = opt_str(ref, "targetType", "") == "bone";
    if (t.part_id.empty() || t.data_file.empty()) fail(where + ": track reference missing partId/dataFile");
    auto it = entries.find(t.data_file);
    if (it == entries.end()) fail("missing track data file: " + t.data_file);

    DocPtr doc = parse(text_of(it->second->data), t.data_file);
    yyjson_val* root = yyjson_doc_get_root(doc.get());
    if (!yyjson_is_obj(root)) fail(t.data_file + " must be a JSON object");
    if (yyjson_val* keys = opt_array(root, "keyframes")) {
        for (std::size_t i = 0, n = yyjson_arr_size(keys); i < n; ++i) {
            yyjson_val* k = yyjson_arr_get(keys, i);
            const std::string at = t.data_file + "[" + std::to_string(i) + "]";
            if (!yyjson_is_obj(k)) fail(at + " must be an object");
            Key key;
            key.time = opt_num(k, "time", 0.f, at);
            vec3(k, "position", 0.f, key.pos, at);
            vec3(k, "rotation", 0.f, key.rot, at);
            vec3(k, "scale", 1.f, key.scale, at);
            key.easing = easing_from_name(opt_str(k, "easing", "LINEAR"));
            t.keys.push_back(key);
        }
    }
    return t;
}

void read_layer(yyjson_val* l, Layer& layer) {
    if (l == nullptr || !yyjson_is_obj(l)) return;  // the Java reader treats a malformed layer as absent
    layer.present = true;
    const std::string type = upper(opt_str(l, "type", "BASE"));
    layer.type = type == "OVERLAY" ? "OVERLAY" : "BASE";
    if (yyjson_val* mask = yyjson_obj_get(l, "maskParts"); mask != nullptr && yyjson_is_arr(mask)) {
        for (std::size_t i = 0, n = yyjson_arr_size(mask); i < n; ++i) {
            yyjson_val* m = yyjson_arr_get(mask, i);
            if (yyjson_is_str(m) && yyjson_get_len(m) > 0) layer.mask_parts.push_back(str(m));
        }
    }
    layer.fade_in = std::max(0.f, opt_num(l, "fadeInSeconds", 0.1f, "layer"));
    layer.fade_out = std::max(0.f, opt_num(l, "fadeOutSeconds", 0.1f, "layer"));
    if (yyjson_val* p = yyjson_obj_get(l, "priority"); p != nullptr && yyjson_is_num(p)) {
        layer.priority = static_cast<std::int32_t>(yyjson_get_num(p));
    }
}

void read_extensions(yyjson_val* root, Clip& c) {
    if (yyjson_val* notifies = opt_array(root, "notifies")) {
        for (std::size_t i = 0, n = yyjson_arr_size(notifies); i < n; ++i) {
            yyjson_val* e = yyjson_arr_get(notifies, i);
            const std::string at = "notifies[" + std::to_string(i) + "]";
            if (!yyjson_is_obj(e)) fail(at + " must be an object");
            Notify note;
            note.name = opt_str(e, "name", "");
            if (note.name.empty()) fail(at + ": missing name");
            note.time = opt_num(e, "time", 0.f, at);
            note.duration = opt_num(e, "duration", 0.f, at);
            if (note.duration < 0.f) fail(at + ": negative duration");
            if (yyjson_val* p = yyjson_obj_get(e, "payload"); p != nullptr && !yyjson_is_null(p)) {
                if (!yyjson_is_obj(p)) fail(at + ": payload must be an object");
                note.payload_json = minified(p);
            }
            c.notifies.push_back(std::move(note));
        }
        std::stable_sort(c.notifies.begin(), c.notifies.end(), [](const Notify& a, const Notify& b) { return a.time < b.time; });
    }
    if (yyjson_val* curves = opt_array(root, "curves")) {
        std::unordered_set<std::string> names;
        for (std::size_t i = 0, n = yyjson_arr_size(curves); i < n; ++i) {
            yyjson_val* e = yyjson_arr_get(curves, i);
            const std::string at = "curves[" + std::to_string(i) + "]";
            if (!yyjson_is_obj(e)) fail(at + " must be an object");
            Curve curve;
            curve.name = opt_str(e, "name", "");
            if (curve.name.empty()) fail(at + ": missing name");
            if (!names.insert(curve.name).second) fail("duplicate curve " + curve.name);
            if (yyjson_val* keys = opt_array(e, "keys")) {
                for (std::size_t k = 0, m = yyjson_arr_size(keys); k < m; ++k) {
                    yyjson_val* kv = yyjson_arr_get(keys, k);
                    const std::string kat = at + ".keys[" + std::to_string(k) + "]";
                    if (!yyjson_is_obj(kv)) fail(kat + " must be an object");
                    CurveKey key;
                    key.time = opt_num(kv, "time", 0.f, kat);
                    key.value = opt_num(kv, "value", 0.f, kat);
                    key.in_tangent = opt_num(kv, "inTangent", 0.f, kat);
                    key.out_tangent = opt_num(kv, "outTangent", 0.f, kat);
                    key.interp = interp_from(opt_str(kv, "interp", ""), kat);
                    curve.keys.push_back(key);
                }
            }
            std::stable_sort(curve.keys.begin(), curve.keys.end(), [](const CurveKey& a, const CurveKey& b) { return a.time < b.time; });
            c.curves.push_back(std::move(curve));
        }
    }
    if (yyjson_val* markers = opt_array(root, "syncMarkers")) {
        for (std::size_t i = 0, n = yyjson_arr_size(markers); i < n; ++i) {
            yyjson_val* e = yyjson_arr_get(markers, i);
            const std::string at = "syncMarkers[" + std::to_string(i) + "]";
            if (!yyjson_is_obj(e)) fail(at + " must be an object");
            SyncMarker m{opt_str(e, "name", ""), opt_num(e, "time", 0.f, at)};
            if (m.name.empty()) fail(at + ": missing name");
            c.markers.push_back(std::move(m));
        }
        std::stable_sort(c.markers.begin(), c.markers.end(), [](const SyncMarker& a, const SyncMarker& b) { return a.time < b.time; });
    }
    if (yyjson_val* rm = yyjson_obj_get(root, "rootMotion"); rm != nullptr && !yyjson_is_null(rm)) {
        if (!yyjson_is_obj(rm)) fail("\"rootMotion\" must be an object");
        c.root_motion.joint = opt_str(rm, "joint", "");
        if (c.root_motion.joint.empty()) fail("rootMotion: missing joint");
        if (yyjson_val* tr = yyjson_obj_get(rm, "translation"); tr != nullptr && !yyjson_is_null(tr)) {
            if (!yyjson_is_arr(tr) || yyjson_arr_size(tr) != 3) fail("rootMotion.translation must be [x, y, z] booleans");
            for (std::size_t i = 0; i < 3; ++i) {
                yyjson_val* b = yyjson_arr_get(tr, i);
                if (!yyjson_is_bool(b)) fail("rootMotion.translation must be [x, y, z] booleans");
                if (yyjson_get_bool(b)) c.root_motion.axes |= 1u << i;
            }
        }
        if (opt_bool(rm, "rotationY", false)) c.root_motion.axes |= kRootYaw;
    }
    if (yyjson_val* ad = yyjson_obj_get(root, "additive"); ad != nullptr && !yyjson_is_null(ad)) {
        if (!yyjson_is_obj(ad)) fail("\"additive\" must be an object");
        const std::string ref = opt_str(ad, "reference", "frame");
        if (ref == "frame") c.additive.reference = AdditiveReference::Frame;
        else if (ref == "clip") c.additive.reference = AdditiveReference::Clip;
        else if (ref == "character") c.additive.reference = AdditiveReference::Character;
        else fail("additive.reference must be frame, clip or character");
        c.additive.time = opt_num(ad, "time", 0.f, "additive");
        c.additive.clip = opt_str(ad, "clip", "");
        if (c.additive.reference == AdditiveReference::Clip && c.additive.clip.empty()) fail("additive: reference clip needs \"clip\"");
        const std::string space = opt_str(ad, "space", "local");
        if (space != "local" && space != "mesh") fail("additive.space must be local or mesh");
        c.additive.mesh_space = space == "mesh";
    }
}

// --- Writing ---------------------------------------------------------------------------------

struct Writer {
    MutDocPtr doc{yyjson_mut_doc_new(nullptr)};

    yyjson_mut_doc* d() {
        if (!doc) fail("out of memory");
        return doc.get();
    }
    yyjson_mut_val* s(const std::string& v) { return yyjson_mut_strncpy(d(), v.data(), v.size()); }
    yyjson_mut_val* f(float v) { return yyjson_mut_float(d(), v); }
    yyjson_mut_val* obj() { return yyjson_mut_obj(d()); }
    yyjson_mut_val* arr() { return yyjson_mut_arr(d()); }
    void put(yyjson_mut_val* o, const char* key, yyjson_mut_val* v) {
        if (!v || !yyjson_mut_obj_add(o, yyjson_mut_strcpy(d(), key), v)) fail("out of memory");
    }
    void put_key(yyjson_mut_val* o, const std::string& key, yyjson_mut_val* v) {
        if (!v || !yyjson_mut_obj_add(o, s(key), v)) fail("out of memory");
    }
    yyjson_mut_val* vec(const float* v) {
        yyjson_mut_val* a = arr();
        for (int i = 0; i < 3; ++i) yyjson_mut_arr_append(a, f(v[i]));
        return a;
    }
    yyjson_mut_val* parsed(const std::string& text, const std::string& what) {
        DocPtr p = parse(text, what);
        yyjson_mut_val* copy = yyjson_val_mut_copy(d(), yyjson_doc_get_root(p.get()));
        if (!copy) fail("out of memory");
        return copy;
    }
    std::string text(yyjson_mut_val* root) {
        yyjson_mut_doc_set_root(d(), root);
        std::size_t len = 0;
        yyjson_write_err err{};
        CharPtr out(yyjson_mut_write_opts(d(), YYJSON_WRITE_PRETTY_TWO_SPACES | YYJSON_WRITE_NEWLINE_AT_END, nullptr, &len, &err));
        if (!out) fail(std::string("write: ") + err.msg);
        return {out.get(), len};
    }
};

std::string sanitize(const std::string& id) {
    std::string out;
    for (char ch : id) out += std::isalnum(static_cast<unsigned char>(ch)) || ch == '_' || ch == '-' ? ch : '_';
    return out.empty() ? "unknown" : out;
}

ZipEntry text_entry(std::string name, const std::string& text) {
    ZipEntry e;
    e.name = std::move(name);
    e.data.resize(text.size());
    std::memcpy(e.data.data(), text.data(), text.size());
    return e;
}

}  // namespace

Easing easing_from_name(std::string_view name) {
    const std::string u = upper(std::string(name));
    if (u == "EASE_IN") return Easing::EaseIn;
    if (u == "EASE_OUT") return Easing::EaseOut;
    if (u == "EASE_IN_OUT") return Easing::EaseInOut;
    if (u == "STEP") return Easing::Step;
    return Easing::Linear;
}

const char* easing_name(Easing e) {
    switch (e) {
        case Easing::EaseIn: return "EASE_IN";
        case Easing::EaseOut: return "EASE_OUT";
        case Easing::EaseInOut: return "EASE_IN_OUT";
        case Easing::Step: return "STEP";
        default: return "LINEAR";
    }
}

std::string minify_object(std::string_view json, std::string_view what) {
    DocPtr doc = parse(json, std::string(what));
    yyjson_val* root = yyjson_doc_get_root(doc.get());
    if (!yyjson_is_obj(root)) fail(std::string(what) + " must be a JSON object");
    return minified(root);
}

Clip read(std::span<const std::byte> archive) {
    std::vector<ZipEntry> entries = read_zip(archive);
    std::unordered_map<std::string, const ZipEntry*> by_name;
    for (const ZipEntry& e : entries) by_name[e.name] = &e;
    auto manifest = by_name.find(kManifestEntry);
    if (manifest == by_name.end()) fail("missing manifest.json in OMA archive");

    DocPtr doc = parse(text_of(manifest->second->data), "manifest.json");
    yyjson_val* root = yyjson_doc_get_root(doc.get());
    if (!yyjson_is_obj(root)) fail("manifest.json must be a JSON object");

    Clip c;
    c.version = opt_str(root, "version", "1.0");
    c.name = opt_str(root, "name", "Unnamed");
    c.fps = opt_num(root, "fps", 30.f, "manifest");
    c.duration = opt_num(root, "duration", 0.f, "manifest");
    c.loop = opt_bool(root, "loop", false);
    yyjson_val* model_ref = yyjson_obj_get(root, "modelRef");
    c.model_ref_null = model_ref == nullptr || yyjson_is_null(model_ref);
    if (!c.model_ref_null) c.model_ref = opt_str(root, "modelRef", "");

    std::unordered_set<std::string> data_files;
    if (yyjson_val* tracks = opt_array(root, "tracks")) {
        for (std::size_t i = 0, n = yyjson_arr_size(tracks); i < n; ++i) {
            c.tracks.push_back(read_track(yyjson_arr_get(tracks, i), i, by_name));
            data_files.insert(c.tracks.back().data_file);
        }
    }
    read_layer(yyjson_obj_get(root, "layer"), c.layer);
    read_extensions(root, c);

    yyjson_obj_iter iter = yyjson_obj_iter_with(root);
    std::unordered_set<std::string> seen;
    for (yyjson_val* key = nullptr; (key = yyjson_obj_iter_next(&iter)) != nullptr;) {
        std::string k = str(key);
        if (!seen.insert(k).second) fail("manifest.json: duplicate key \"" + k + "\"");
        if (!known(k)) c.unknown_sections.push_back({std::move(k), minified(yyjson_obj_iter_get_val(key))});
    }
    for (ZipEntry& e : entries) {
        if (e.name == kManifestEntry || data_files.contains(e.name)) continue;
        c.unknown_entries.push_back(std::move(e));
    }
    return c;
}

std::vector<std::byte> write(const Clip& c) {
    std::vector<ZipEntry> entries;
    std::unordered_set<std::string> names{kManifestEntry};
    for (const ZipEntry& e : c.unknown_entries) names.insert(e.name);

    // Track files first (their names go in the manifest).
    std::vector<std::string> files;
    for (const Track& t : c.tracks) {
        std::string name = t.data_file;
        if (name.empty() || names.contains(name)) {
            const std::string base = "track_" + sanitize(t.part_id);
            name = base + ".json";
            for (int i = 2; names.contains(name); ++i) name = base + "_" + std::to_string(i) + ".json";
        }
        names.insert(name);
        files.push_back(name);

        Writer w;
        yyjson_mut_val* root = w.obj();
        w.put(root, "partId", w.s(t.part_id));
        yyjson_mut_val* keys = w.arr();
        for (const Key& k : t.keys) {
            yyjson_mut_val* o = w.obj();
            w.put(o, "time", w.f(k.time));
            w.put(o, "position", w.vec(k.pos));
            w.put(o, "rotation", w.vec(k.rot));
            w.put(o, "scale", w.vec(k.scale));
            w.put(o, "easing", yyjson_mut_str(w.d(), easing_name(k.easing)));
            yyjson_mut_arr_append(keys, o);
        }
        w.put(root, "keyframes", keys);
        entries.push_back(text_entry(name, w.text(root)));
    }

    Writer w;
    yyjson_mut_val* root = w.obj();
    w.put(root, "version", yyjson_mut_str(w.d(), kWriteVersion));
    w.put(root, "name", w.s(c.name));
    w.put(root, "fps", w.f(c.fps));
    w.put(root, "duration", w.f(c.duration));
    w.put(root, "loop", yyjson_mut_bool(w.d(), c.loop));
    w.put(root, "modelRef", c.model_ref_null ? yyjson_mut_null(w.d()) : w.s(c.model_ref));
    yyjson_mut_val* tracks = w.arr();
    yyjson_mut_val* required = w.arr();
    for (std::size_t i = 0; i < c.tracks.size(); ++i) {
        const Track& t = c.tracks[i];
        yyjson_mut_val* o = w.obj();
        w.put(o, "partId", w.s(t.part_id));
        w.put(o, "partName", w.s(t.part_name));
        w.put(o, "dataFile", w.s(files[i]));
        if (t.bone) w.put(o, "targetType", yyjson_mut_str(w.d(), "bone"));
        if (!t.role.empty()) w.put(o, "role", w.s(t.role));
        yyjson_mut_arr_append(tracks, o);
        yyjson_mut_arr_append(required, w.s(t.part_id));
    }
    w.put(root, "tracks", tracks);
    w.put(root, "requiredParts", required);
    if (c.layer.present) {
        yyjson_mut_val* l = w.obj();
        w.put(l, "type", w.s(c.layer.type));
        yyjson_mut_val* mask = w.arr();
        for (const std::string& m : c.layer.mask_parts) yyjson_mut_arr_append(mask, w.s(m));
        w.put(l, "maskParts", mask);
        w.put(l, "fadeInSeconds", w.f(c.layer.fade_in));
        w.put(l, "fadeOutSeconds", w.f(c.layer.fade_out));
        w.put(l, "priority", yyjson_mut_sint(w.d(), c.layer.priority));
        w.put(root, "layer", l);
    }
    if (!c.notifies.empty()) {
        yyjson_mut_val* a = w.arr();
        for (const Notify& n : c.notifies) {
            yyjson_mut_val* o = w.obj();
            w.put(o, "name", w.s(n.name));
            w.put(o, "time", w.f(n.time));
            if (n.duration > 0.f) w.put(o, "duration", w.f(n.duration));
            if (!n.payload_json.empty()) w.put(o, "payload", w.parsed(n.payload_json, "notify payload"));
            yyjson_mut_arr_append(a, o);
        }
        w.put(root, "notifies", a);
    }
    if (!c.curves.empty()) {
        yyjson_mut_val* a = w.arr();
        for (const Curve& cv : c.curves) {
            yyjson_mut_val* o = w.obj();
            w.put(o, "name", w.s(cv.name));
            yyjson_mut_val* keys = w.arr();
            for (const CurveKey& k : cv.keys) {
                yyjson_mut_val* ko = w.obj();
                w.put(ko, "time", w.f(k.time));
                w.put(ko, "value", w.f(k.value));
                if (k.interp != CurveInterp::Linear) w.put(ko, "interp", yyjson_mut_str(w.d(), interp_name(k.interp)));
                if (k.interp == CurveInterp::Cubic) {
                    w.put(ko, "inTangent", w.f(k.in_tangent));
                    w.put(ko, "outTangent", w.f(k.out_tangent));
                }
                yyjson_mut_arr_append(keys, ko);
            }
            w.put(o, "keys", keys);
            yyjson_mut_arr_append(a, o);
        }
        w.put(root, "curves", a);
    }
    if (!c.markers.empty()) {
        yyjson_mut_val* a = w.arr();
        for (const SyncMarker& m : c.markers) {
            yyjson_mut_val* o = w.obj();
            w.put(o, "name", w.s(m.name));
            w.put(o, "time", w.f(m.time));
            yyjson_mut_arr_append(a, o);
        }
        w.put(root, "syncMarkers", a);
    }
    if (!c.root_motion.joint.empty()) {
        yyjson_mut_val* o = w.obj();
        w.put(o, "joint", w.s(c.root_motion.joint));
        yyjson_mut_val* tr = w.arr();
        for (std::uint32_t i = 0; i < 3; ++i) yyjson_mut_arr_append(tr, yyjson_mut_bool(w.d(), (c.root_motion.axes >> i) & 1u));
        w.put(o, "translation", tr);
        w.put(o, "rotationY", yyjson_mut_bool(w.d(), (c.root_motion.axes & kRootYaw) != 0));
        w.put(root, "rootMotion", o);
    }
    if (c.additive.reference != AdditiveReference::None) {
        yyjson_mut_val* o = w.obj();
        const char* ref = c.additive.reference == AdditiveReference::Clip ? "clip"
                        : c.additive.reference == AdditiveReference::Character ? "character" : "frame";
        w.put(o, "reference", yyjson_mut_str(w.d(), ref));
        if (c.additive.reference != AdditiveReference::Character) w.put(o, "time", w.f(c.additive.time));
        if (c.additive.reference == AdditiveReference::Clip) w.put(o, "clip", w.s(c.additive.clip));
        w.put(o, "space", yyjson_mut_str(w.d(), c.additive.mesh_space ? "mesh" : "local"));
        w.put(root, "additive", o);
    }
    for (const UnknownSection& u : c.unknown_sections) {
        if (known(u.key)) fail("unknown section \"" + u.key + "\" collides with a known key");
        w.put_key(root, u.key, w.parsed(u.json, "section " + u.key));
    }

    std::vector<ZipEntry> out;
    out.reserve(entries.size() + 1 + c.unknown_entries.size());
    out.push_back(text_entry(kManifestEntry, w.text(root)));
    for (ZipEntry& e : entries) out.push_back(std::move(e));
    for (const ZipEntry& e : c.unknown_entries) out.push_back(e);
    return write_zip(out);
}

}  // namespace cenda::omformat::omanim
