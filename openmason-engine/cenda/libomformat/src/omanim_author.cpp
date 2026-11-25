// .omanim authoring data (OpenMason #165): the session model, its operations and the editor view. See
// omformat/omanim_author.hpp.
#include "omformat/omanim_author.hpp"

#include "json_util.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>

namespace cenda::omformat::omanim::author {

namespace {

using json::CharPtr;
using json::DocPtr;
using json::MutDocPtr;

constexpr const char* kSection = "authoring";
constexpr const char* kEntryPrefix = "authoring/";
constexpr float kTimeEps = 1e-4f;

[[noreturn]] void fail(const std::string& m) { throw format_error(m); }

std::string str(yyjson_val* v) { return {yyjson_get_str(v), yyjson_get_len(v)}; }

yyjson_val* get(yyjson_val* o, const char* k) {
    yyjson_val* v = yyjson_obj_get(o, k);
    return v == nullptr || yyjson_is_null(v) ? nullptr : v;
}

std::string text(yyjson_val* o, const char* k, bool required = false) {
    yyjson_val* v = get(o, k);
    if (v == nullptr) {
        if (required) fail(std::string("missing \"") + k + "\"");
        return {};
    }
    if (!yyjson_is_str(v)) fail(std::string("\"") + k + "\" must be a string");
    return str(v);
}

float num(yyjson_val* o, const char* k, float fallback) {
    yyjson_val* v = get(o, k);
    if (v == nullptr) return fallback;
    if (!yyjson_is_num(v) || !std::isfinite(yyjson_get_num(v))) fail(std::string("\"") + k + "\" must be a number");
    return static_cast<float>(yyjson_get_num(v));
}

float req_num(yyjson_val* o, const char* k) {
    if (get(o, k) == nullptr) fail(std::string("missing \"") + k + "\"");
    return num(o, k, 0.f);
}

bool boolean(yyjson_val* o, const char* k, bool fallback) {
    yyjson_val* v = get(o, k);
    if (v == nullptr) return fallback;
    if (!yyjson_is_bool(v)) fail(std::string("\"") + k + "\" must be true or false");
    return yyjson_get_bool(v);
}

bool vec3(yyjson_val* o, const char* k, float out[3]) {
    yyjson_val* v = get(o, k);
    if (v == nullptr) return false;
    if (!yyjson_is_arr(v) || yyjson_arr_size(v) != 3) fail(std::string("\"") + k + "\" must be [x, y, z]");
    for (std::size_t i = 0; i < 3; ++i) {
        yyjson_val* e = yyjson_arr_get(v, i);
        if (!yyjson_is_num(e) || !std::isfinite(yyjson_get_num(e))) fail(std::string("\"") + k + "\" must be numbers");
        out[i] = static_cast<float>(yyjson_get_num(e));
    }
    return true;
}

std::vector<float> times(yyjson_val* o) {
    std::vector<float> out;
    yyjson_val* v = get(o, "times");
    if (v == nullptr) return out;
    if (!yyjson_is_arr(v)) fail("\"times\" must be an array");
    for (std::size_t i = 0, n = yyjson_arr_size(v); i < n; ++i) {
        yyjson_val* e = yyjson_arr_get(v, i);
        if (!yyjson_is_num(e)) fail("\"times\" must be numbers");
        out.push_back(static_cast<float>(yyjson_get_num(e)));
    }
    return out;
}

bool near(float a, float b) { return std::fabs(a - b) <= kTimeEps; }

void sort_keys(std::vector<Key>& keys) {
    std::stable_sort(keys.begin(), keys.end(), [](const Key& a, const Key& b) { return a.time < b.time; });
}

Easing parse_easing(const std::string& s) {
    static const char* names[] = {"LINEAR", "EASE_IN", "EASE_OUT", "EASE_IN_OUT", "STEP"};
    for (int i = 0; i < 5; ++i) {
        if (s == names[i]) return static_cast<Easing>(i);
    }
    fail("easing must be LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT or STEP");
}

std::string minified(yyjson_val* v) {
    std::size_t len = 0;
    CharPtr s(yyjson_val_write(v, YYJSON_WRITE_NOFLAG, &len));
    if (!s) fail("out of memory");
    return {s.get(), len};
}

// ---- the authoring section ------------------------------------------------------------------------------------

MaskEntry parse_mask(yyjson_val* m) {
    if (!yyjson_is_obj(m)) fail("mask entries are objects");
    MaskEntry e;
    e.joint = text(m, "joint");
    e.role = text(m, "role");
    if (e.joint.empty() == e.role.empty()) fail("a mask entry names exactly one of joint or role");
    e.weight = std::clamp(num(m, "weight", 1.f), 0.f, 1.f);
    e.branch = boolean(m, "branch", true);
    return e;
}

std::vector<MaskEntry> parse_mask_list(yyjson_val* v) {
    std::vector<MaskEntry> out;
    if (v == nullptr) return out;
    if (!yyjson_is_arr(v)) fail("\"mask\" must be an array");
    for (std::size_t i = 0, n = yyjson_arr_size(v); i < n; ++i) out.push_back(parse_mask(yyjson_arr_get(v, i)));
    return out;
}

EffectorKey parse_effector_key(yyjson_val* k) {
    EffectorKey e;
    e.time = req_num(k, "time");
    vec3(k, "pos", e.pos);
    e.has_rot = vec3(k, "rot", e.rot);
    e.weight = std::clamp(num(k, "weight", 1.f), 0.f, 1.f);
    e.has_pole = vec3(k, "pole", e.pole);
    return e;
}

void parse_effector_shape(yyjson_val* o, Effector& e) {
    if (get(o, "kind") != nullptr) {
        e.kind = text(o, "kind");
        if (e.kind != "twoBone" && e.kind != "aim") fail("kind must be twoBone or aim");
    }
    if (get(o, "upper") != nullptr) e.upper = text(o, "upper");
    if (get(o, "mid") != nullptr) e.mid = text(o, "mid");
    if (get(o, "end") != nullptr) e.end = text(o, "end");
    if (yyjson_val* js = get(o, "joints")) {
        if (!yyjson_is_arr(js)) fail("\"joints\" must be an array");
        e.joints.clear();
        for (std::size_t i = 0, n = yyjson_arr_size(js); i < n; ++i) {
            yyjson_val* j = yyjson_arr_get(js, i);
            if (!yyjson_is_str(j)) fail("\"joints\" must be strings");
            e.joints.push_back(str(j));
        }
    }
    vec3(o, "axis", e.axis);
}

void put(yyjson_mut_doc* d, yyjson_mut_val* o, const char* k, yyjson_mut_val* v) {
    yyjson_mut_obj_add(o, yyjson_mut_strcpy(d, k), v);
}

yyjson_mut_val* mstr(yyjson_mut_doc* d, const std::string& s) { return yyjson_mut_strncpy(d, s.data(), s.size()); }

yyjson_mut_val* mvec(yyjson_mut_doc* d, const float* v, int n) {
    yyjson_mut_val* a = yyjson_mut_arr(d);
    for (int i = 0; i < n; ++i) yyjson_mut_arr_append(a, yyjson_mut_float(d, v[i]));
    return a;
}

yyjson_mut_val* mask_json(yyjson_mut_doc* d, const std::vector<MaskEntry>& mask) {
    yyjson_mut_val* a = yyjson_mut_arr(d);
    for (const MaskEntry& m : mask) {
        yyjson_mut_val* o = yyjson_mut_obj(d);
        if (!m.joint.empty()) put(d, o, "joint", mstr(d, m.joint));
        if (!m.role.empty()) put(d, o, "role", mstr(d, m.role));
        if (m.weight != 1.f) put(d, o, "weight", yyjson_mut_float(d, m.weight));
        if (!m.branch) put(d, o, "branch", yyjson_mut_false(d));
        yyjson_mut_arr_append(a, o);
    }
    return a;
}

yyjson_mut_val* effector_json(yyjson_mut_doc* d, const Effector& e, bool with_keys) {
    yyjson_mut_val* o = yyjson_mut_obj(d);
    put(d, o, "name", mstr(d, e.name));
    put(d, o, "kind", mstr(d, e.kind));
    if (e.kind == "twoBone") {
        put(d, o, "upper", mstr(d, e.upper));
        put(d, o, "mid", mstr(d, e.mid));
        put(d, o, "end", mstr(d, e.end));
    } else {
        yyjson_mut_val* js = yyjson_mut_arr(d);
        for (const std::string& j : e.joints) yyjson_mut_arr_append(js, mstr(d, j));
        put(d, o, "joints", js);
        put(d, o, "axis", mvec(d, e.axis, 3));
    }
    yyjson_mut_val* keys = yyjson_mut_arr(d);
    for (const EffectorKey& k : e.keys) {
        yyjson_mut_val* ko = yyjson_mut_obj(d);
        put(d, ko, "time", yyjson_mut_float(d, k.time));
        if (with_keys) {
            put(d, ko, "pos", mvec(d, k.pos, 3));
            if (k.has_rot) put(d, ko, "rot", mvec(d, k.rot, 3));
            if (k.weight != 1.f) put(d, ko, "weight", yyjson_mut_float(d, k.weight));
            if (k.has_pole) put(d, ko, "pole", mvec(d, k.pole, 3));
        }
        yyjson_mut_arr_append(keys, ko);
    }
    put(d, o, "keys", keys);
    return o;
}

std::string write_doc(yyjson_mut_doc* d) {
    std::size_t len = 0;
    CharPtr s(yyjson_mut_write(d, YYJSON_WRITE_NOFLAG, &len));
    if (!s) fail("out of memory");
    return {s.get(), len};
}

std::string entry_name(std::size_t i) { return std::string(kEntryPrefix) + "layer_" + std::to_string(i) + ".omanim"; }

bool has_weight_curve(const AuthorLayer& l) {
    return std::any_of(l.clip.curves.begin(), l.clip.curves.end(), [](const Curve& c) { return c.name == "weight"; });
}

// ---- operations -------------------------------------------------------------------------------------------------

int layer_of(const Session& s, yyjson_val* op) {
    yyjson_val* v = get(op, "layer");
    if (v == nullptr) return 0;
    std::string ref = yyjson_is_int(v) ? std::to_string(yyjson_get_int(v)) : yyjson_is_str(v) ? str(v) : "";
    const int i = find_layer(s, ref);
    if (i < 0) fail("no layer " + ref);
    return i;
}

Track* track_of(AuthorLayer& l, const Host::Joint& j, bool create) {
    for (Track& t : l.clip.tracks) {
        if (!j.role.empty() ? t.role == j.role : t.part_id == j.id) return &t;
    }
    if (!create) return nullptr;
    Track t;
    t.role = j.role;
    t.part_id = j.role.empty() ? j.id : std::string(kRolePrefix) + j.role;
    t.part_name = j.role.empty() ? j.name : std::string(kRolePrefix) + j.role;
    t.bone = j.bone;
    l.clip.tracks.push_back(std::move(t));
    return &l.clip.tracks.back();
}

// The clip's own runtime layer (how a game plays it: BASE or OVERLAY over named parts), not an authoring layer.
std::string op_clip_layer(Session& s, yyjson_val* op, Host& host) {
    Layer& l = s.clip.layer;
    l.present = true;
    if (get(op, "type") != nullptr) {
        const std::string t = text(op, "type");
        if (t != "BASE" && t != "OVERLAY") fail("type must be BASE or OVERLAY");
        l.type = t;
    }
    if (yyjson_val* m = yyjson_obj_get(op, "mask")) {
        l.mask_parts.clear();
        if (!yyjson_is_null(m)) {
            if (!yyjson_is_arr(m)) fail("\"mask\" must be an array of joints");
            for (std::size_t i = 0, n = yyjson_arr_size(m); i < n; ++i) {
                yyjson_val* e = yyjson_arr_get(m, i);
                if (!yyjson_is_str(e)) fail("mask entries are joint names");
                l.mask_parts.push_back(host.resolve(str(e)).name);
            }
        }
    }
    if (get(op, "fadeIn") != nullptr) l.fade_in = std::max(0.f, num(op, "fadeIn", 0.1f));
    if (get(op, "fadeOut") != nullptr) l.fade_out = std::max(0.f, num(op, "fadeOut", 0.1f));
    if (get(op, "priority") != nullptr) l.priority = static_cast<std::int32_t>(num(op, "priority", 0.f));
    return "clip layer " + l.type;
}

std::string op_header(Session& s, yyjson_val* op) {
    if (get(op, "name") != nullptr) s.clip.name = text(op, "name");
    if (get(op, "fps") != nullptr) {
        const float f = num(op, "fps", 30.f);
        if (!(f > 0.f && f <= 1000.f)) fail("fps must be in (0, 1000]");
        s.clip.fps = f;
    }
    if (get(op, "duration") != nullptr) {
        const float d = num(op, "duration", 0.f);
        if (!(d >= 0.f)) fail("duration must be >= 0");
        s.clip.duration = d;
    }
    if (get(op, "loop") != nullptr) s.clip.loop = boolean(op, "loop", false);
    if (yyjson_val* m = yyjson_obj_get(op, "modelRef")) {
        s.clip.model_ref_null = yyjson_is_null(m);
        s.clip.model_ref = s.clip.model_ref_null ? std::string() : text(op, "modelRef");
    }
    for (AuthorLayer& l : s.layers) {
        l.clip.fps = s.clip.fps;
        l.clip.duration = s.clip.duration;
        l.clip.loop = s.clip.loop;
    }
    return "header set";
}

std::string op_key(Session& s, yyjson_val* op, Host& host) {
    const int li = layer_of(s, op);
    const Host::Joint j = host.resolve(text(op, "joint", true));
    const float t = req_num(op, "time");
    if (t < 0.f) fail("time must be >= 0");
    Key k = host.sample(s, li, j, t);
    k.time = t;
    vec3(op, "pos", k.pos);
    vec3(op, "rot", k.rot);
    vec3(op, "scale", k.scale);
    AuthorLayer& l = s.layers[static_cast<std::size_t>(li)];
    Track* tr = track_of(l, j, true);
    auto it = std::find_if(tr->keys.begin(), tr->keys.end(), [&](const Key& x) { return near(x.time, t); });
    if (get(op, "easing") != nullptr) {
        k.easing = parse_easing(text(op, "easing"));
    } else if (it != tr->keys.end()) {
        k.easing = it->easing;
    }
    if (it != tr->keys.end()) {
        *it = k;
    } else {
        tr->keys.push_back(k);
        sort_keys(tr->keys);
    }
    if (t > s.clip.duration) {
        s.clip.duration = t;
        for (AuthorLayer& al : s.layers) al.clip.duration = t;
    }
    return "key " + j.name + " @" + std::to_string(t);
}

template <typename F>
int for_tracks(Session& s, yyjson_val* op, Host& host, F&& f) {
    const int li = layer_of(s, op);
    AuthorLayer& l = s.layers[static_cast<std::size_t>(li)];
    int n = 0;
    if (get(op, "joint") != nullptr) {
        const Host::Joint j = host.resolve(text(op, "joint"));
        Track* t = track_of(l, j, false);
        if (t == nullptr) fail("layer " + l.name + " has no track for " + j.name);
        n += f(*t);
    } else {
        for (Track& t : l.clip.tracks) n += f(t);
    }
    std::erase_if(l.clip.tracks, [](const Track& t) { return t.keys.empty(); });
    return n;
}

bool selected(float t, const std::vector<float>& ts, bool range, float from, float to) {
    if (range) return t >= from - kTimeEps && t <= to + kTimeEps;
    return std::any_of(ts.begin(), ts.end(), [&](float x) { return near(x, t); });
}

std::string op_remove_keys(Session& s, yyjson_val* op, Host& host) {
    const std::vector<float> ts = times(op);
    const bool range = get(op, "from") != nullptr || get(op, "to") != nullptr;
    const float from = num(op, "from", 0.f), to = num(op, "to", 1e9f);
    if (ts.empty() && !range) fail("give times or from / to");
    const int n = for_tracks(s, op, host, [&](Track& t) {
        const std::size_t before = t.keys.size();
        std::erase_if(t.keys, [&](const Key& k) { return selected(k.time, ts, range, from, to); });
        return static_cast<int>(before - t.keys.size());
    });
    return "removed " + std::to_string(n) + " key(s)";
}

std::string op_move_keys(Session& s, yyjson_val* op, Host& host) {
    const std::vector<float> ts = times(op);
    const float offset = req_num(op, "offset");
    const int n = for_tracks(s, op, host, [&](Track& t) {
        int moved = 0;
        std::vector<Key> keep, move;
        for (const Key& k : t.keys) (selected(k.time, ts, false, 0, 0) ? move : keep).push_back(k);
        for (Key& k : move) {
            k.time = std::max(0.f, k.time + offset);
            std::erase_if(keep, [&](const Key& x) { return near(x.time, k.time); });
            ++moved;
        }
        keep.insert(keep.end(), move.begin(), move.end());
        sort_keys(keep);
        t.keys = std::move(keep);
        return moved;
    });
    return "moved " + std::to_string(n) + " key(s)";
}

std::string op_easing(Session& s, yyjson_val* op, Host& host) {
    const std::vector<float> ts = times(op);
    const Easing e = parse_easing(text(op, "easing", true));
    const int n = for_tracks(s, op, host, [&](Track& t) {
        int c = 0;
        for (Key& k : t.keys) {
            if (selected(k.time, ts, ts.empty(), 0.f, 1e9f)) {
                k.easing = e;
                ++c;
            }
        }
        return c;
    });
    return "easing set on " + std::to_string(n) + " key(s)";
}

std::string op_track(Session& s, yyjson_val* op, Host& host) {
    if (!boolean(op, "remove", false)) fail("track supports remove: true");
    const int li = layer_of(s, op);
    const Host::Joint j = host.resolve(text(op, "joint", true));
    AuthorLayer& l = s.layers[static_cast<std::size_t>(li)];
    const std::size_t before = l.clip.tracks.size();
    std::erase_if(l.clip.tracks, [&](const Track& t) { return !j.role.empty() ? t.role == j.role : t.part_id == j.id; });
    if (before == l.clip.tracks.size()) fail("layer " + l.name + " has no track for " + j.name);
    return "track " + j.name + " removed";
}

std::string op_layer(Session& s, yyjson_val* op) {
    const std::string name = text(op, "name", true);
    int i = find_layer(s, name);
    if (boolean(op, "remove", false)) {
        if (i < 0) fail("no layer " + name);
        if (i == 0) fail("the base layer cannot be removed");
        s.layers.erase(s.layers.begin() + i);
        return "layer " + name + " removed";
    }
    if (i < 0) {
        AuthorLayer l;
        l.name = name;
        l.clip.fps = s.clip.fps;
        l.clip.duration = s.clip.duration;
        l.clip.loop = s.clip.loop;
        l.clip.name = name;
        s.layers.push_back(std::move(l));
        i = static_cast<int>(s.layers.size()) - 1;
    }
    AuthorLayer& l = s.layers[static_cast<std::size_t>(i)];
    if (const std::string to = text(op, "rename"); !to.empty()) {
        if (find_layer(s, to) >= 0) fail("a layer " + to + " exists");
        l.name = to;
    }
    if (get(op, "mode") != nullptr) {
        const std::string m = text(op, "mode");
        if (m != "override" && m != "additive") fail("mode must be override or additive");
        if (i == 0 && m == "additive") fail("the base layer overrides");
        l.mode = m == "additive" ? LayerMode::Additive : LayerMode::Override;
    }
    if (get(op, "weight") != nullptr) l.weight = std::clamp(num(op, "weight", 1.f), 0.f, 1.f);
    l.mute = boolean(op, "mute", l.mute);
    l.solo = boolean(op, "solo", l.solo);
    l.passthrough = boolean(op, "passthrough", l.passthrough);
    l.mesh_space = boolean(op, "meshSpace", l.mesh_space);
    if (yyjson_val* m = yyjson_obj_get(op, "mask")) l.mask = yyjson_is_null(m) ? std::vector<MaskEntry>{} : parse_mask_list(m);
    if (get(op, "index") != nullptr) {
        const int to = static_cast<int>(num(op, "index", 0.f));
        if (i == 0 || to <= 0 || to >= static_cast<int>(s.layers.size())) fail("index must move a layer above the base");
        AuthorLayer moved = std::move(s.layers[static_cast<std::size_t>(i)]);
        s.layers.erase(s.layers.begin() + i);
        s.layers.insert(s.layers.begin() + to, std::move(moved));
    }
    return "layer " + name + " set";
}

std::string op_layer_weight(Session& s, yyjson_val* op) {
    const int li = layer_of(s, op);
    AuthorLayer& l = s.layers[static_cast<std::size_t>(li)];
    const float t = req_num(op, "time");
    auto c = std::find_if(l.clip.curves.begin(), l.clip.curves.end(), [](const Curve& x) { return x.name == "weight"; });
    if (boolean(op, "remove", false)) {
        if (c == l.clip.curves.end()) fail("layer " + l.name + " has no weight keys");
        std::erase_if(c->keys, [&](const CurveKey& k) { return near(k.time, t); });
        if (c->keys.empty()) l.clip.curves.erase(c);
        return "weight key removed";
    }
    if (c == l.clip.curves.end()) {
        l.clip.curves.push_back({"weight", {}});
        c = l.clip.curves.end() - 1;
    }
    CurveKey k;
    k.time = t;
    k.value = std::clamp(req_num(op, "value"), 0.f, 1.f);
    std::erase_if(c->keys, [&](const CurveKey& x) { return near(x.time, t); });
    c->keys.push_back(k);
    std::stable_sort(c->keys.begin(), c->keys.end(), [](const CurveKey& a, const CurveKey& b) { return a.time < b.time; });
    return "weight key set";
}

std::string op_notify(Session& s, yyjson_val* op) {
    if (get(op, "index") != nullptr) {
        const int i = static_cast<int>(num(op, "index", 0.f));
        if (i < 0 || i >= static_cast<int>(s.clip.notifies.size())) fail("no notify " + std::to_string(i));
        if (boolean(op, "remove", false)) {
            s.clip.notifies.erase(s.clip.notifies.begin() + i);
            return "notify removed";
        }
        Notify& n = s.clip.notifies[static_cast<std::size_t>(i)];
        if (get(op, "name") != nullptr) n.name = text(op, "name");
        if (get(op, "time") != nullptr) n.time = num(op, "time", n.time);
        if (get(op, "duration") != nullptr) n.duration = std::max(0.f, num(op, "duration", 0.f));
        if (yyjson_val* p = yyjson_obj_get(op, "payload")) {
            n.payload_json = yyjson_is_null(p) ? std::string() : minify_object(minified(p), "payload");
        }
    } else {
        Notify n;
        n.name = text(op, "name", true);
        n.time = req_num(op, "time");
        n.duration = std::max(0.f, num(op, "duration", 0.f));
        if (yyjson_val* p = get(op, "payload")) n.payload_json = minify_object(minified(p), "payload");
        s.clip.notifies.push_back(std::move(n));
    }
    std::stable_sort(s.clip.notifies.begin(), s.clip.notifies.end(), [](const Notify& a, const Notify& b) { return a.time < b.time; });
    return "notify set";
}

std::string op_curve(Session& s, yyjson_val* op) {
    const std::string name = text(op, "name", true);
    std::erase_if(s.clip.curves, [&](const Curve& c) { return c.name == name; });
    yyjson_val* keys = get(op, "keys");
    if (keys == nullptr) return "curve " + name + " removed";
    if (!yyjson_is_arr(keys)) fail("\"keys\" must be an array");
    Curve c;
    c.name = name;
    for (std::size_t i = 0, n = yyjson_arr_size(keys); i < n; ++i) {
        yyjson_val* k = yyjson_arr_get(keys, i);
        if (!yyjson_is_obj(k)) fail("curve keys are objects");
        CurveKey ck;
        ck.time = req_num(k, "time");
        ck.value = req_num(k, "value");
        ck.in_tangent = num(k, "inTangent", 0.f);
        ck.out_tangent = num(k, "outTangent", 0.f);
        const std::string interp = text(k, "interp");
        ck.interp = interp == "STEP" ? CurveInterp::Step : interp == "CUBIC" ? CurveInterp::Cubic : CurveInterp::Linear;
        c.keys.push_back(ck);
    }
    std::stable_sort(c.keys.begin(), c.keys.end(), [](const CurveKey& a, const CurveKey& b) { return a.time < b.time; });
    s.clip.curves.push_back(std::move(c));
    return "curve " + name + " set";
}

std::string op_marker(Session& s, yyjson_val* op) {
    if (get(op, "index") != nullptr) {
        const int i = static_cast<int>(num(op, "index", 0.f));
        if (i < 0 || i >= static_cast<int>(s.clip.markers.size())) fail("no marker " + std::to_string(i));
        if (!boolean(op, "remove", false)) fail("marker index: remove: true");
        s.clip.markers.erase(s.clip.markers.begin() + i);
        return "marker removed";
    }
    s.clip.markers.push_back({text(op, "name", true), req_num(op, "time")});
    std::stable_sort(s.clip.markers.begin(), s.clip.markers.end(), [](const SyncMarker& a, const SyncMarker& b) { return a.time < b.time; });
    return "marker set";
}

std::string op_root_motion(Session& s, yyjson_val* op, Host& host) {
    if (get(op, "joint") == nullptr) {
        s.clip.root_motion = {};
        return "root motion off";
    }
    const std::string ref = text(op, "joint");
    const Host::Joint j = host.resolve(ref);
    s.clip.root_motion.joint = j.role.empty() ? j.id : std::string(kRolePrefix) + j.role;
    s.clip.root_motion.axes = 0;
    if (yyjson_val* a = get(op, "axes")) {
        if (!yyjson_is_arr(a)) fail("\"axes\" must be an array of x, y, z, yaw");
        for (std::size_t i = 0, n = yyjson_arr_size(a); i < n; ++i) {
            const std::string ax = yyjson_is_str(yyjson_arr_get(a, i)) ? str(yyjson_arr_get(a, i)) : "";
            if (ax == "x") s.clip.root_motion.axes |= kRootX;
            else if (ax == "y") s.clip.root_motion.axes |= kRootY;
            else if (ax == "z") s.clip.root_motion.axes |= kRootZ;
            else if (ax == "yaw") s.clip.root_motion.axes |= kRootYaw;
            else fail("axes are x, y, z, yaw");
        }
    }
    return "root motion " + j.name;
}

std::string op_additive(Session& s, yyjson_val* op) {
    const std::string ref = text(op, "reference", true);
    Additive a;
    if (ref == "none") a.reference = AdditiveReference::None;
    else if (ref == "frame") a.reference = AdditiveReference::Frame;
    else if (ref == "clip") a.reference = AdditiveReference::Clip;
    else if (ref == "character") a.reference = AdditiveReference::Character;
    else fail("reference must be none, frame, clip or character");
    a.time = num(op, "time", 0.f);
    a.clip = text(op, "clip");
    a.mesh_space = text(op, "space") == "mesh";
    s.clip.additive = a;
    return "additive " + ref;
}

int find_effector(const Session& s, const std::string& name) {
    for (std::size_t i = 0; i < s.effectors.size(); ++i) {
        if (s.effectors[i].name == name) return static_cast<int>(i);
    }
    return -1;
}

std::string op_effector(Session& s, yyjson_val* op, Host& host) {
    const std::string name = text(op, "name", true);
    int i = find_effector(s, name);
    if (boolean(op, "remove", false)) {
        if (i < 0) fail("no effector " + name);
        s.effectors.erase(s.effectors.begin() + i);
        return "effector " + name + " removed";
    }
    if (i < 0) {
        s.effectors.push_back({});
        s.effectors.back().name = name;
        i = static_cast<int>(s.effectors.size()) - 1;
    }
    Effector& e = s.effectors[static_cast<std::size_t>(i)];
    if (const std::string to = text(op, "rename"); !to.empty()) {
        if (find_effector(s, to) >= 0) fail("an effector " + to + " exists");
        e.name = to;
    }
    parse_effector_shape(op, e);
    if (e.kind == "twoBone") {
        if (e.upper.empty() || e.mid.empty() || e.end.empty()) fail("a twoBone effector needs upper, mid and end");
        host.resolve(e.upper);
        host.resolve(e.mid);
        host.resolve(e.end);
    } else {
        if (e.joints.empty()) fail("an aim effector needs joints");
        for (const std::string& j : e.joints) host.resolve(j);
    }
    return "effector " + e.name + " set";
}

std::string op_effector_key(Session& s, yyjson_val* op) {
    const std::string name = text(op, "effector", true);
    const int i = find_effector(s, name);
    if (i < 0) fail("no effector " + name);
    Effector& e = s.effectors[static_cast<std::size_t>(i)];
    const float t = req_num(op, "time");
    auto it = std::find_if(e.keys.begin(), e.keys.end(), [&](const EffectorKey& k) { return near(k.time, t); });
    if (boolean(op, "remove", false)) {
        if (it == e.keys.end()) fail("no key at " + std::to_string(t));
        e.keys.erase(it);
        return "effector key removed";
    }
    EffectorKey k = it != e.keys.end() ? *it : EffectorKey{};
    k.time = t;
    if (!vec3(op, "pos", k.pos) && it == e.keys.end()) fail("a new effector key needs pos");
    if (yyjson_val* r = yyjson_obj_get(op, "rot")) {
        k.has_rot = !yyjson_is_null(r);
        if (k.has_rot) vec3(op, "rot", k.rot);
    }
    if (get(op, "weight") != nullptr) k.weight = std::clamp(num(op, "weight", 1.f), 0.f, 1.f);
    if (yyjson_val* p = yyjson_obj_get(op, "pole")) {
        k.has_pole = !yyjson_is_null(p);
        if (k.has_pole) vec3(op, "pole", k.pole);
    }
    if (it != e.keys.end()) {
        *it = k;
    } else {
        e.keys.push_back(k);
        std::stable_sort(e.keys.begin(), e.keys.end(), [](const EffectorKey& a, const EffectorKey& b) { return a.time < b.time; });
    }
    return "effector key " + name + " @" + std::to_string(t);
}

}  // namespace

// ---- public ---------------------------------------------------------------------------------------------------------

int find_layer(const Session& s, std::string_view ref) {
    if (ref == "base") return s.layers.empty() ? -1 : 0;
    for (std::size_t i = 0; i < s.layers.size(); ++i) {
        if (s.layers[i].name == ref) return static_cast<int>(i);
    }
    if (!ref.empty() && std::all_of(ref.begin(), ref.end(), [](char c) { return c >= '0' && c <= '9'; })) {
        const int i = std::stoi(std::string(ref));
        if (i >= 0 && i < static_cast<int>(s.layers.size())) return i;
    }
    return -1;
}

Session session_of(const Clip& clip) {
    Session s;
    s.clip = clip;
    auto sec = std::find_if(s.clip.unknown_sections.begin(), s.clip.unknown_sections.end(),
                            [](const UnknownSection& u) { return u.key == kSection; });
    if (sec != s.clip.unknown_sections.end()) {
        DocPtr d(yyjson_read(sec->json.data(), sec->json.size(), 0));
        yyjson_val* root = d ? yyjson_doc_get_root(d.get()) : nullptr;
        if (root == nullptr || !yyjson_is_obj(root)) fail(".omanim authoring: not an object");
        if (yyjson_val* ls = get(root, "layers")) {
            for (std::size_t i = 0, n = yyjson_arr_size(ls); i < n; ++i) {
                yyjson_val* lo = yyjson_arr_get(ls, i);
                AuthorLayer l;
                l.name = text(lo, "name", true);
                const std::string mode = text(lo, "mode");
                l.mode = mode == "additive" ? LayerMode::Additive : LayerMode::Override;
                l.weight = num(lo, "weight", 1.f);
                l.mute = boolean(lo, "mute", false);
                l.solo = boolean(lo, "solo", false);
                l.passthrough = boolean(lo, "passthrough", true);
                l.mesh_space = boolean(lo, "meshSpace", false);
                l.mask = parse_mask_list(get(lo, "mask"));
                const std::string entry = text(lo, "entry", true);
                auto e = std::find_if(s.clip.unknown_entries.begin(), s.clip.unknown_entries.end(),
                                      [&](const ZipEntry& z) { return z.name == entry; });
                if (e == s.clip.unknown_entries.end()) fail(".omanim authoring: missing layer entry " + entry);
                l.clip = read(std::span<const std::byte>(e->data.data(), e->data.size()));
                s.layers.push_back(std::move(l));
            }
        }
        if (yyjson_val* es = get(root, "effectors")) {
            for (std::size_t i = 0, n = yyjson_arr_size(es); i < n; ++i) {
                yyjson_val* eo = yyjson_arr_get(es, i);
                Effector e;
                e.name = text(eo, "name", true);
                parse_effector_shape(eo, e);
                if (yyjson_val* ks = get(eo, "keys")) {
                    for (std::size_t k = 0, kn = yyjson_arr_size(ks); k < kn; ++k) e.keys.push_back(parse_effector_key(yyjson_arr_get(ks, k)));
                }
                s.effectors.push_back(std::move(e));
            }
        }
        s.clip.unknown_sections.erase(sec);
        std::erase_if(s.clip.unknown_entries, [](const ZipEntry& z) { return z.name.rfind(kEntryPrefix, 0) == 0; });
    }
    if (s.layers.empty()) {
        AuthorLayer base;
        base.clip.name = "Base";
        base.clip.fps = clip.fps;
        base.clip.duration = clip.duration;
        base.clip.loop = clip.loop;
        base.clip.tracks = clip.tracks;
        s.layers.push_back(std::move(base));
    }
    return s;
}

bool trivial(const Session& s) {
    if (s.layers.size() != 1 || !s.effectors.empty()) return false;
    const AuthorLayer& l = s.layers.front();
    return l.weight == 1.f && !l.mute && l.mask.empty() && !has_weight_curve(l) && l.mode == LayerMode::Override;
}

Clip clip_of(const Session& s) {
    Clip c = s.clip;
    if (trivial(s)) {
        c.tracks = s.layers.front().clip.tracks;
        return c;
    }
    MutDocPtr doc(yyjson_mut_doc_new(nullptr));
    yyjson_mut_doc* d = doc.get();
    yyjson_mut_val* root = yyjson_mut_obj(d);
    yyjson_mut_val* layers = yyjson_mut_arr(d);
    for (std::size_t i = 0; i < s.layers.size(); ++i) {
        const AuthorLayer& l = s.layers[i];
        yyjson_mut_val* o = yyjson_mut_obj(d);
        put(d, o, "name", mstr(d, l.name));
        put(d, o, "entry", mstr(d, entry_name(i)));
        put(d, o, "mode", yyjson_mut_str(d, l.mode == LayerMode::Additive ? "additive" : "override"));
        put(d, o, "weight", yyjson_mut_float(d, l.weight));
        if (l.mute) put(d, o, "mute", yyjson_mut_true(d));
        if (l.solo) put(d, o, "solo", yyjson_mut_true(d));
        if (!l.passthrough) put(d, o, "passthrough", yyjson_mut_false(d));
        if (l.mesh_space) put(d, o, "meshSpace", yyjson_mut_true(d));
        if (!l.mask.empty()) put(d, o, "mask", mask_json(d, l.mask));
        yyjson_mut_arr_append(layers, o);
        Clip lc = l.clip;
        lc.name = l.name;
        lc.fps = s.clip.fps;
        lc.duration = s.clip.duration;
        lc.loop = s.clip.loop;
        ZipEntry z;
        z.name = entry_name(i);
        z.data = write(lc);
        z.precompressed = true;
        c.unknown_entries.push_back(std::move(z));
    }
    put(d, root, "layers", layers);
    if (!s.effectors.empty()) {
        yyjson_mut_val* es = yyjson_mut_arr(d);
        for (const Effector& e : s.effectors) yyjson_mut_arr_append(es, effector_json(d, e, true));
        put(d, root, "effectors", es);
    }
    yyjson_mut_doc_set_root(d, root);
    c.unknown_sections.push_back({kSection, write_doc(d)});
    return c;
}

std::vector<std::string> apply(Session& s, std::string_view ops_json, Host& host) {
    DocPtr doc(yyjson_read(ops_json.data(), ops_json.size(), 0));
    if (!doc) fail("ops: not valid JSON");
    yyjson_val* ops = yyjson_doc_get_root(doc.get());
    if (!yyjson_is_arr(ops)) fail("ops must be an array");
    std::vector<std::string> out;
    for (std::size_t i = 0, n = yyjson_arr_size(ops); i < n; ++i) {
        yyjson_val* op = yyjson_arr_get(ops, i);
        std::string kind = "?";
        try {
            if (!yyjson_is_obj(op)) fail("an operation must be an object");
            kind = text(op, "op", true);
            if (kind == "header") out.push_back(op_header(s, op));
            else if (kind == "clip_layer") out.push_back(op_clip_layer(s, op, host));
            else if (kind == "key") out.push_back(op_key(s, op, host));
            else if (kind == "remove_keys") out.push_back(op_remove_keys(s, op, host));
            else if (kind == "move_keys") out.push_back(op_move_keys(s, op, host));
            else if (kind == "easing") out.push_back(op_easing(s, op, host));
            else if (kind == "track") out.push_back(op_track(s, op, host));
            else if (kind == "layer") out.push_back(op_layer(s, op));
            else if (kind == "layer_weight") out.push_back(op_layer_weight(s, op));
            else if (kind == "notify") out.push_back(op_notify(s, op));
            else if (kind == "curve") out.push_back(op_curve(s, op));
            else if (kind == "marker") out.push_back(op_marker(s, op));
            else if (kind == "root_motion") out.push_back(op_root_motion(s, op, host));
            else if (kind == "additive") out.push_back(op_additive(s, op));
            else if (kind == "effector") out.push_back(op_effector(s, op, host));
            else if (kind == "effector_key") out.push_back(op_effector_key(s, op));
            else if (kind == "match") {
                const int e = find_effector(s, text(op, "effector", true));
                if (e < 0) fail("no effector " + text(op, "effector"));
                const std::string to = text(op, "to", true);
                if (to != "ik" && to != "fk") fail("to must be ik or fk");
                // fk without a layer: the host picks one that holds the pose (-1).
                host.match(s, e, req_num(op, "time"), to == "ik", get(op, "layer") == nullptr ? -1 : layer_of(s, op));
                out.push_back("matched " + to);
            } else if (kind == "bake") {
                out.push_back("baked");
            } else {
                fail("unknown op (header, clip_layer, key, remove_keys, move_keys, easing, track, layer, layer_weight, notify, curve, "
                     "marker, root_motion, additive, effector, effector_key, match, bake)");
            }
        } catch (const std::exception& e) {
            throw format_error("ops[" + std::to_string(i) + "] (" + kind + "): " + e.what());
        }
    }
    return out;
}

std::string view(const Session& s) {
    MutDocPtr doc(yyjson_mut_doc_new(nullptr));
    yyjson_mut_doc* d = doc.get();
    yyjson_mut_val* root = yyjson_mut_obj(d);
    yyjson_mut_doc_set_root(d, root);
    put(d, root, "name", mstr(d, s.clip.name));
    put(d, root, "fps", yyjson_mut_float(d, s.clip.fps));
    put(d, root, "duration", yyjson_mut_float(d, s.clip.duration));
    put(d, root, "loop", yyjson_mut_bool(d, s.clip.loop));
    put(d, root, "modelRef", s.clip.model_ref_null ? yyjson_mut_null(d) : mstr(d, s.clip.model_ref));
    put(d, root, "trivial", yyjson_mut_bool(d, trivial(s)));
    {
        yyjson_mut_val* cl = yyjson_mut_obj(d);
        put(d, cl, "present", yyjson_mut_bool(d, s.clip.layer.present));
        put(d, cl, "type", mstr(d, s.clip.layer.type));
        yyjson_mut_val* mk = yyjson_mut_arr(d);
        for (const std::string& m : s.clip.layer.mask_parts) yyjson_mut_arr_append(mk, mstr(d, m));
        put(d, cl, "mask", mk);
        put(d, cl, "fadeIn", yyjson_mut_float(d, s.clip.layer.fade_in));
        put(d, cl, "fadeOut", yyjson_mut_float(d, s.clip.layer.fade_out));
        put(d, cl, "priority", yyjson_mut_int(d, s.clip.layer.priority));
        put(d, root, "clipLayer", cl);
    }
    yyjson_mut_val* layers = yyjson_mut_arr(d);
    for (const AuthorLayer& l : s.layers) {
        yyjson_mut_val* o = yyjson_mut_obj(d);
        put(d, o, "name", mstr(d, l.name));
        put(d, o, "mode", yyjson_mut_str(d, l.mode == LayerMode::Additive ? "additive" : "override"));
        put(d, o, "weight", yyjson_mut_float(d, l.weight));
        put(d, o, "mute", yyjson_mut_bool(d, l.mute));
        put(d, o, "solo", yyjson_mut_bool(d, l.solo));
        put(d, o, "passthrough", yyjson_mut_bool(d, l.passthrough));
        put(d, o, "meshSpace", yyjson_mut_bool(d, l.mesh_space));
        put(d, o, "mask", mask_json(d, l.mask));
        yyjson_mut_val* wk = yyjson_mut_arr(d);
        for (const Curve& c : l.clip.curves) {
            if (c.name != "weight") continue;
            for (const CurveKey& k : c.keys) {
                yyjson_mut_val* p = yyjson_mut_arr(d);
                yyjson_mut_arr_append(p, yyjson_mut_float(d, k.time));
                yyjson_mut_arr_append(p, yyjson_mut_float(d, k.value));
                yyjson_mut_arr_append(wk, p);
            }
        }
        put(d, o, "weightKeys", wk);
        yyjson_mut_val* tracks = yyjson_mut_arr(d);
        for (const Track& t : l.clip.tracks) {
            yyjson_mut_val* to = yyjson_mut_obj(d);
            put(d, to, "joint", mstr(d, t.part_id));
            put(d, to, "name", mstr(d, t.part_name));
            if (!t.role.empty()) put(d, to, "role", mstr(d, t.role));
            if (t.bone) put(d, to, "bone", yyjson_mut_true(d));
            yyjson_mut_val* ts = yyjson_mut_arr(d);
            for (const Key& k : t.keys) yyjson_mut_arr_append(ts, yyjson_mut_float(d, k.time));
            put(d, to, "keys", ts);
            yyjson_mut_arr_append(tracks, to);
        }
        put(d, o, "tracks", tracks);
        yyjson_mut_arr_append(layers, o);
    }
    put(d, root, "layers", layers);
    yyjson_mut_val* es = yyjson_mut_arr(d);
    for (const Effector& e : s.effectors) yyjson_mut_arr_append(es, effector_json(d, e, true));
    put(d, root, "effectors", es);
    yyjson_mut_val* ns = yyjson_mut_arr(d);
    for (const Notify& n : s.clip.notifies) {
        yyjson_mut_val* o = yyjson_mut_obj(d);
        put(d, o, "name", mstr(d, n.name));
        put(d, o, "time", yyjson_mut_float(d, n.time));
        if (n.duration > 0.f) put(d, o, "duration", yyjson_mut_float(d, n.duration));
        if (!n.payload_json.empty()) {
            DocPtr pd(yyjson_read(n.payload_json.data(), n.payload_json.size(), 0));
            if (pd) put(d, o, "payload", yyjson_val_mut_copy(d, yyjson_doc_get_root(pd.get())));
        }
        yyjson_mut_arr_append(ns, o);
    }
    put(d, root, "notifies", ns);
    yyjson_mut_val* cs = yyjson_mut_arr(d);
    for (const Curve& c : s.clip.curves) {
        yyjson_mut_val* o = yyjson_mut_obj(d);
        put(d, o, "name", mstr(d, c.name));
        yyjson_mut_val* ks = yyjson_mut_arr(d);
        for (const CurveKey& k : c.keys) {
            yyjson_mut_val* p = yyjson_mut_arr(d);
            yyjson_mut_arr_append(p, yyjson_mut_float(d, k.time));
            yyjson_mut_arr_append(p, yyjson_mut_float(d, k.value));
            yyjson_mut_arr_append(ks, p);
        }
        put(d, o, "keys", ks);
        yyjson_mut_arr_append(cs, o);
    }
    put(d, root, "curves", cs);
    yyjson_mut_val* ms = yyjson_mut_arr(d);
    for (const SyncMarker& m : s.clip.markers) {
        yyjson_mut_val* o = yyjson_mut_obj(d);
        put(d, o, "name", mstr(d, m.name));
        put(d, o, "time", yyjson_mut_float(d, m.time));
        yyjson_mut_arr_append(ms, o);
    }
    put(d, root, "markers", ms);
    if (!s.clip.root_motion.joint.empty()) {
        yyjson_mut_val* o = yyjson_mut_obj(d);
        put(d, o, "joint", mstr(d, s.clip.root_motion.joint));
        yyjson_mut_val* ax = yyjson_mut_arr(d);
        if (s.clip.root_motion.axes & kRootX) yyjson_mut_arr_append(ax, yyjson_mut_str(d, "x"));
        if (s.clip.root_motion.axes & kRootY) yyjson_mut_arr_append(ax, yyjson_mut_str(d, "y"));
        if (s.clip.root_motion.axes & kRootZ) yyjson_mut_arr_append(ax, yyjson_mut_str(d, "z"));
        if (s.clip.root_motion.axes & kRootYaw) yyjson_mut_arr_append(ax, yyjson_mut_str(d, "yaw"));
        put(d, o, "axes", ax);
        put(d, root, "rootMotion", o);
    }
    if (s.clip.additive.reference != AdditiveReference::None) {
        static const char* refs[] = {"none", "frame", "clip", "character"};
        yyjson_mut_val* o = yyjson_mut_obj(d);
        put(d, o, "reference", yyjson_mut_str(d, refs[static_cast<int>(s.clip.additive.reference)]));
        put(d, o, "time", yyjson_mut_float(d, s.clip.additive.time));
        if (!s.clip.additive.clip.empty()) put(d, o, "clip", mstr(d, s.clip.additive.clip));
        put(d, o, "space", yyjson_mut_str(d, s.clip.additive.mesh_space ? "mesh" : "local"));
        put(d, root, "additive", o);
    }
    return write_doc(d);
}

}  // namespace cenda::omformat::omanim::author
