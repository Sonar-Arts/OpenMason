#include "omformat/omchar.hpp"

#include <yyjson.h>

#include <algorithm>
#include <charconv>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string_view>
#include <unordered_set>

namespace cenda::omformat::omchar {

namespace {

// ---------------------------------------------------------------------------------------------
// Reading.

struct DocDeleter {
    void operator()(yyjson_doc* d) const { yyjson_doc_free(d); }
};
using DocPtr = std::unique_ptr<yyjson_doc, DocDeleter>;

struct MutDocDeleter {
    void operator()(yyjson_mut_doc* d) const { yyjson_mut_doc_free(d); }
};
using MutDocPtr = std::unique_ptr<yyjson_mut_doc, MutDocDeleter>;

struct FreeDeleter {
    void operator()(char* p) const { std::free(p); }
};
using CharPtr = std::unique_ptr<char, FreeDeleter>;

[[noreturn]] void fail(const std::string& message) {
    throw format_error(".omchar: " + message);
}

DocPtr parse_json(std::string_view text, const std::string& what) {
    yyjson_read_err err{};
    // Without YYJSON_READ_INSITU the input is not modified.
    yyjson_doc* doc = yyjson_read_opts(const_cast<char*>(text.data()), text.size(), YYJSON_READ_NOFLAG,
                                       nullptr, &err);
    if (doc == nullptr) {
        fail(what + " is not valid JSON (" + err.msg + " at byte " + std::to_string(err.pos) + ")");
    }
    return DocPtr(doc);
}

std::string minified(yyjson_val* val) {
    std::size_t len = 0;
    CharPtr text(yyjson_val_write(val, YYJSON_WRITE_NOFLAG, &len));
    if (!text) fail("cannot serialize a JSON value");
    return {text.get(), len};
}

std::string str_field(yyjson_val* obj, const char* key, const std::string& where, bool required) {
    yyjson_val* v = yyjson_obj_get(obj, key);
    if (v == nullptr || yyjson_is_null(v)) {
        if (required) fail(where + ": missing \"" + key + "\"");
        return {};
    }
    if (!yyjson_is_str(v)) fail(where + ": \"" + key + "\" must be a string");
    return {yyjson_get_str(v), yyjson_get_len(v)};
}

float number(yyjson_val* v, const std::string& where) {
    if (!yyjson_is_num(v)) fail(where + " must be a number");
    const double d = yyjson_get_num(v);
    if (!std::isfinite(d)) fail(where + " must be finite");
    return static_cast<float>(d);
}

Vec3 vec3_field(yyjson_val* obj, const char* key, const std::string& where, Vec3 fallback) {
    yyjson_val* v = yyjson_obj_get(obj, key);
    if (v == nullptr || yyjson_is_null(v)) return fallback;
    if (!yyjson_is_arr(v) || yyjson_arr_size(v) != 3) fail(where + ": \"" + key + "\" must be [x, y, z]");
    const std::string at = where + "." + key;
    return {number(yyjson_arr_get(v, 0), at), number(yyjson_arr_get(v, 1), at), number(yyjson_arr_get(v, 2), at)};
}

yyjson_val* array_field(yyjson_val* obj, const char* key) {
    yyjson_val* v = yyjson_obj_get(obj, key);
    if (v == nullptr || yyjson_is_null(v)) return nullptr;
    if (!yyjson_is_arr(v)) fail(std::string("\"") + key + "\" must be an array");
    return v;
}

yyjson_val* element_object(yyjson_val* arr, std::size_t i, const char* section) {
    yyjson_val* v = yyjson_arr_get(arr, i);
    if (!yyjson_is_obj(v)) fail(std::string(section) + "[" + std::to_string(i) + "] must be an object");
    return v;
}

std::string at(const char* section, std::size_t i) {
    return std::string(section) + "[" + std::to_string(i) + "]";
}

void parse_version(const std::string& version) {
    const auto dot = version.find('.');
    int major = 0;
    int minor = 0;
    const char* begin = version.data();
    const char* end = begin + version.size();
    bool ok = dot != std::string::npos && dot > 0;
    if (ok) {
        auto [p1, e1] = std::from_chars(begin, begin + dot, major);
        auto [p2, e2] = std::from_chars(begin + dot + 1, end, minor);
        ok = e1 == std::errc{} && p1 == begin + dot && e2 == std::errc{} && p2 == end && major >= 0 && minor >= 0;
    }
    if (!ok) fail("version \"" + version + "\" is not <major>.<minor>");
    if (major > kVersionMajor) {
        throw version_error(".omchar version " + version + " is newer than this reader (" +
                            std::to_string(kVersionMajor) + "." + std::to_string(kVersionMinor) + ")");
    }
    if (major < 1) fail("version \"" + version + "\" is not supported");
}

template <typename T, typename KeyOf>
void require_unique(const std::vector<T>& items, KeyOf key, const char* section) {
    std::unordered_set<std::string> seen;
    for (const T& item : items) {
        if (!seen.insert(key(item)).second) fail(std::string(section) + ": duplicate \"" + key(item) + "\"");
    }
}

constexpr const char* kKnownKeys[] = {"format", "version", "name", "objectId", "profile", "skeleton",
                                      "roles", "referencePose", "limits", "ikGoals", "chains",
                                      "sockets", "metrics", "animGraph", "appearance"};

bool is_known(std::string_view key) {
    return std::any_of(std::begin(kKnownKeys), std::end(kKnownKeys),
                       [&](const char* k) { return key == k; });
}

constexpr struct {
    const char* name;
    std::uint32_t bit;
} kMetricNames[] = {{"height", kMetricHeight},
                    {"eyeHeight", kMetricEyeHeight},
                    {"width", kMetricWidth},
                    {"hipHeight", kMetricHipHeight}};

void read_skeleton(yyjson_val* sk, Character& c, const std::vector<ZipEntry>& entries) {
    if (!yyjson_is_obj(sk)) fail("\"skeleton\" must be an object");
    c.model_entry = str_field(sk, "model", "skeleton", false);
    c.model_ref = str_field(sk, "modelRef", "skeleton", false);
    c.joint_hash = str_field(sk, "jointHash", "skeleton", false);
    if (!c.model_entry.empty()) {
        if (c.model_entry == kManifestEntry) fail("skeleton.model cannot be the manifest");
        auto it = std::find_if(entries.begin(), entries.end(),
                               [&](const ZipEntry& e) { return e.name == c.model_entry; });
        if (it == entries.end()) fail("skeleton.model \"" + c.model_entry + "\" is not in the archive");
        c.model = it->data;
    }
    if (yyjson_val* joints = array_field(sk, "joints")) {
        const std::size_t n = yyjson_arr_size(joints);
        c.joints.reserve(n);
        for (std::size_t i = 0; i < n; ++i) {
            yyjson_val* j = element_object(joints, i, "skeleton.joints");
            const std::string where = at("skeleton.joints", i);
            SkeletonJoint joint;
            joint.id = str_field(j, "id", where, true);
            if (joint.id.empty()) fail(where + ": empty id");
            joint.name = str_field(j, "name", where, false);
            joint.parent = str_field(j, "parent", where, false);
            const std::string kind = str_field(j, "kind", where, true);
            if (kind == "part") {
                joint.kind = JointKind::Part;
            } else if (kind == "bone") {
                joint.kind = JointKind::Bone;
            } else {
                fail(where + ": kind must be \"part\" or \"bone\"");
            }
            c.joints.push_back(std::move(joint));
        }
        require_unique(c.joints, [](const SkeletonJoint& j) { return j.id; }, "skeleton.joints");
    }
}

void read_sections(yyjson_val* root, Character& c) {
    if (yyjson_val* roles = yyjson_obj_get(root, "roles")) {
        if (!yyjson_is_obj(roles)) fail("\"roles\" must be an object");
        yyjson_obj_iter iter = yyjson_obj_iter_with(roles);
        yyjson_val* key = nullptr;
        while ((key = yyjson_obj_iter_next(&iter)) != nullptr) {
            yyjson_val* val = yyjson_obj_iter_get_val(key);
            if (!yyjson_is_str(val)) fail("roles: \"" + std::string(yyjson_get_str(key)) + "\" must name a joint id");
            c.roles.push_back({std::string(yyjson_get_str(key), yyjson_get_len(key)),
                               std::string(yyjson_get_str(val), yyjson_get_len(val))});
        }
        require_unique(c.roles, [](const RoleBinding& r) { return r.role; }, "roles");
    }
    if (yyjson_val* pose = array_field(root, "referencePose")) {
        for (std::size_t i = 0, n = yyjson_arr_size(pose); i < n; ++i) {
            yyjson_val* e = element_object(pose, i, "referencePose");
            const std::string where = at("referencePose", i);
            c.reference_pose.push_back({str_field(e, "joint", where, true), vec3_field(e, "rot", where, {})});
        }
        require_unique(c.reference_pose, [](const JointRotation& r) { return r.joint; }, "referencePose");
    }
    if (yyjson_val* limits = array_field(root, "limits")) {
        for (std::size_t i = 0, n = yyjson_arr_size(limits); i < n; ++i) {
            yyjson_val* e = element_object(limits, i, "limits");
            const std::string where = at("limits", i);
            c.limits.push_back({str_field(e, "joint", where, true), vec3_field(e, "min", where, {}),
                                vec3_field(e, "max", where, {})});
        }
        require_unique(c.limits, [](const JointLimit& l) { return l.joint; }, "limits");
    }
    if (yyjson_val* goals = array_field(root, "ikGoals")) {
        for (std::size_t i = 0, n = yyjson_arr_size(goals); i < n; ++i) {
            yyjson_val* e = element_object(goals, i, "ikGoals");
            const std::string where = at("ikGoals", i);
            c.ik_goals.push_back({str_field(e, "name", where, true), str_field(e, "joint", where, true)});
        }
        require_unique(c.ik_goals, [](const IkGoal& g) { return g.name; }, "ikGoals");
    }
    if (yyjson_val* chains = array_field(root, "chains")) {
        for (std::size_t i = 0, n = yyjson_arr_size(chains); i < n; ++i) {
            yyjson_val* e = element_object(chains, i, "chains");
            const std::string where = at("chains", i);
            c.chains.push_back({str_field(e, "name", where, true), str_field(e, "start", where, true),
                                str_field(e, "end", where, true), str_field(e, "goal", where, false)});
        }
        require_unique(c.chains, [](const Chain& ch) { return ch.name; }, "chains");
    }
    if (yyjson_val* sockets = array_field(root, "sockets")) {
        for (std::size_t i = 0, n = yyjson_arr_size(sockets); i < n; ++i) {
            yyjson_val* e = element_object(sockets, i, "sockets");
            const std::string where = at("sockets", i);
            c.sockets.push_back({str_field(e, "name", where, true), str_field(e, "joint", where, true),
                                 vec3_field(e, "pos", where, {}), vec3_field(e, "rot", where, {}),
                                 vec3_field(e, "scale", where, {1.f, 1.f, 1.f})});
        }
        require_unique(c.sockets, [](const Socket& s) { return s.name; }, "sockets");
    }
    if (yyjson_val* m = yyjson_obj_get(root, "metrics")) {
        if (!yyjson_is_obj(m)) fail("\"metrics\" must be an object");
        float* fields[] = {&c.metrics.height, &c.metrics.eye_height, &c.metrics.width, &c.metrics.hip_height};
        for (std::size_t i = 0; i < 4; ++i) {
            if (yyjson_val* v = yyjson_obj_get(m, kMetricNames[i].name)) {
                *fields[i] = number(v, std::string("metrics.") + kMetricNames[i].name);
            }
        }
        if (yyjson_val* over = array_field(m, "overridden")) {
            for (std::size_t i = 0, n = yyjson_arr_size(over); i < n; ++i) {
                yyjson_val* v = yyjson_arr_get(over, i);
                if (!yyjson_is_str(v)) fail("metrics.overridden must hold metric names");
                const std::string_view name(yyjson_get_str(v), yyjson_get_len(v));
                bool found = false;
                for (const auto& metric : kMetricNames) {
                    if (name == metric.name) {
                        c.metrics.overridden |= metric.bit;
                        found = true;
                    }
                }
                if (!found) fail("metrics.overridden: unknown metric \"" + std::string(name) + "\"");
            }
        }
    }
    if (yyjson_val* appearance = yyjson_obj_get(root, "appearance")) {
        if (!yyjson_is_obj(appearance)) fail("\"appearance\" must be an object");
        c.appearance_json = minified(appearance);
    }
}

// ---------------------------------------------------------------------------------------------
// Writing.

class Writer {
public:
    Writer() : doc_(yyjson_mut_doc_new(nullptr)) {
        if (!doc_) fail("out of memory");
    }

    yyjson_mut_doc* doc() { return doc_.get(); }

    yyjson_mut_val* str(const std::string& s) { return yyjson_mut_strncpy(doc(), s.data(), s.size()); }

    yyjson_mut_val* obj() { return yyjson_mut_obj(doc()); }

    yyjson_mut_val* arr() { return yyjson_mut_arr(doc()); }

    yyjson_mut_val* vec(const Vec3& v) {
        yyjson_mut_val* a = arr();
        yyjson_mut_arr_append(a, yyjson_mut_float(doc(), v.x));
        yyjson_mut_arr_append(a, yyjson_mut_float(doc(), v.y));
        yyjson_mut_arr_append(a, yyjson_mut_float(doc(), v.z));
        return a;
    }

    void put(yyjson_mut_val* o, const std::string& key, yyjson_mut_val* val) {
        if (!val || !yyjson_mut_obj_add(o, str(key), val)) fail("out of memory");
    }

    void put_str(yyjson_mut_val* o, const char* key, const std::string& value) {
        if (!value.empty()) put(o, key, str(value));
    }

    yyjson_mut_val* parsed(const std::string& json, const std::string& what) {
        DocPtr parsed = parse_json(json, what);
        yyjson_mut_val* copy = yyjson_val_mut_copy(doc(), yyjson_doc_get_root(parsed.get()));
        if (!copy) fail("out of memory");
        return copy;
    }

private:
    MutDocPtr doc_;
};

std::string write_manifest(const Character& c) {
    Writer w;
    yyjson_mut_val* root = w.obj();
    yyjson_mut_doc_set_root(w.doc(), root);

    w.put(root, "format", w.str("omchar"));
    w.put(root, "version", w.str(std::to_string(kVersionMajor) + "." + std::to_string(kVersionMinor)));
    w.put_str(root, "name", c.name);
    w.put_str(root, "objectId", c.object_id);
    w.put_str(root, "profile", c.profile);

    if (!c.model_entry.empty() || !c.model_ref.empty() || !c.joints.empty() || !c.joint_hash.empty()) {
        yyjson_mut_val* sk = w.obj();
        w.put_str(sk, "model", c.model_entry);
        w.put_str(sk, "modelRef", c.model_ref);
        w.put_str(sk, "jointHash", c.joint_hash);
        if (!c.joints.empty()) {
            yyjson_mut_val* joints = w.arr();
            for (const SkeletonJoint& j : c.joints) {
                yyjson_mut_val* o = w.obj();
                w.put(o, "id", w.str(j.id));
                w.put(o, "name", w.str(j.name));
                w.put(o, "parent", j.parent.empty() ? yyjson_mut_null(w.doc()) : w.str(j.parent));
                w.put(o, "kind", w.str(j.kind == JointKind::Bone ? "bone" : "part"));
                yyjson_mut_arr_append(joints, o);
            }
            w.put(sk, "joints", joints);
        }
        w.put(root, "skeleton", sk);
    }

    if (!c.roles.empty()) {
        yyjson_mut_val* roles = w.obj();
        for (const RoleBinding& r : c.roles) w.put(roles, r.role, w.str(r.joint));
        w.put(root, "roles", roles);
    }
    if (!c.reference_pose.empty()) {
        yyjson_mut_val* a = w.arr();
        for (const JointRotation& r : c.reference_pose) {
            yyjson_mut_val* o = w.obj();
            w.put(o, "joint", w.str(r.joint));
            w.put(o, "rot", w.vec(r.rot));
            yyjson_mut_arr_append(a, o);
        }
        w.put(root, "referencePose", a);
    }
    if (!c.limits.empty()) {
        yyjson_mut_val* a = w.arr();
        for (const JointLimit& l : c.limits) {
            yyjson_mut_val* o = w.obj();
            w.put(o, "joint", w.str(l.joint));
            w.put(o, "min", w.vec(l.min));
            w.put(o, "max", w.vec(l.max));
            yyjson_mut_arr_append(a, o);
        }
        w.put(root, "limits", a);
    }
    if (!c.ik_goals.empty()) {
        yyjson_mut_val* a = w.arr();
        for (const IkGoal& g : c.ik_goals) {
            yyjson_mut_val* o = w.obj();
            w.put(o, "name", w.str(g.name));
            w.put(o, "joint", w.str(g.joint));
            yyjson_mut_arr_append(a, o);
        }
        w.put(root, "ikGoals", a);
    }
    if (!c.chains.empty()) {
        yyjson_mut_val* a = w.arr();
        for (const Chain& ch : c.chains) {
            yyjson_mut_val* o = w.obj();
            w.put(o, "name", w.str(ch.name));
            w.put(o, "start", w.str(ch.start));
            w.put(o, "end", w.str(ch.end));
            w.put_str(o, "goal", ch.goal);
            yyjson_mut_arr_append(a, o);
        }
        w.put(root, "chains", a);
    }
    if (!c.sockets.empty()) {
        yyjson_mut_val* a = w.arr();
        for (const Socket& s : c.sockets) {
            yyjson_mut_val* o = w.obj();
            w.put(o, "name", w.str(s.name));
            w.put(o, "joint", w.str(s.joint));
            w.put(o, "pos", w.vec(s.pos));
            w.put(o, "rot", w.vec(s.rot));
            w.put(o, "scale", w.vec(s.scale));
            yyjson_mut_arr_append(a, o);
        }
        w.put(root, "sockets", a);
    }
    if (!c.metrics.empty()) {
        yyjson_mut_val* m = w.obj();
        const float values[] = {c.metrics.height, c.metrics.eye_height, c.metrics.width, c.metrics.hip_height};
        for (std::size_t i = 0; i < 4; ++i) w.put(m, kMetricNames[i].name, yyjson_mut_float(w.doc(), values[i]));
        if (c.metrics.overridden != 0) {
            yyjson_mut_val* over = w.arr();
            for (const auto& metric : kMetricNames) {
                if (c.metrics.overridden & metric.bit) yyjson_mut_arr_append(over, w.str(metric.name));
            }
            w.put(m, "overridden", over);
        }
        w.put(root, "metrics", m);
    }
    w.put_str(root, "animGraph", c.anim_graph);
    if (!c.appearance_json.empty()) {
        yyjson_mut_val* appearance = w.parsed(c.appearance_json, "appearance");
        if (!yyjson_mut_is_obj(appearance)) fail("appearance must be a JSON object");
        w.put(root, "appearance", appearance);
    }
    for (const UnknownSection& u : c.unknown_sections) {
        if (is_known(u.key)) fail("unknown section \"" + u.key + "\" collides with a known key");
        w.put(root, u.key, w.parsed(u.json, "section \"" + u.key + "\""));
    }

    std::size_t len = 0;
    yyjson_write_err err{};
    CharPtr text(yyjson_mut_write_opts(w.doc(), YYJSON_WRITE_PRETTY_TWO_SPACES | YYJSON_WRITE_NEWLINE_AT_END,
                                       nullptr, &len, &err));
    if (!text) fail(std::string("manifest: ") + err.msg);
    return {text.get(), len};
}

}  // namespace

void Character::set_joints(std::vector<SkeletonJoint> new_joints) {
    joints = std::move(new_joints);
    joint_hash = omchar::joint_hash(joints);
}

std::string minify_json_object(std::string_view json) {
    DocPtr doc = parse_json(json, "appearance");
    yyjson_val* root = yyjson_doc_get_root(doc.get());
    if (!yyjson_is_obj(root)) fail("appearance must be a JSON object");
    return minified(root);
}

std::string joint_hash(const std::vector<SkeletonJoint>& joints) {
    std::vector<const SkeletonJoint*> sorted;
    sorted.reserve(joints.size());
    for (const SkeletonJoint& j : joints) sorted.push_back(&j);
    std::sort(sorted.begin(), sorted.end(), [](const SkeletonJoint* a, const SkeletonJoint* b) { return a->id < b->id; });

    std::uint64_t h = 0xcbf29ce484222325ull;
    auto mix = [&h](std::string_view s) {
        for (char ch : s) {
            h ^= static_cast<std::uint8_t>(ch);
            h *= 0x100000001b3ull;
        }
    };
    for (const SkeletonJoint* j : sorted) {
        mix(j->id);
        mix("\x1f");
        mix(j->parent);
        mix(j->kind == JointKind::Bone ? "\x1f" "b\x1e" : "\x1f" "p\x1e");
    }
    char buf[32];
    std::snprintf(buf, sizeof buf, "fnv1a64:%016llx", static_cast<unsigned long long>(h));
    return buf;
}

Character read(std::span<const std::byte> archive) {
    std::vector<ZipEntry> entries = read_zip(archive);
    auto manifest = std::find_if(entries.begin(), entries.end(),
                                 [](const ZipEntry& e) { return e.name == kManifestEntry; });
    if (manifest == entries.end()) fail("no manifest.json");

    const std::string_view text(reinterpret_cast<const char*>(manifest->data.data()), manifest->data.size());
    DocPtr doc = parse_json(text, "manifest.json");
    yyjson_val* root = yyjson_doc_get_root(doc.get());
    if (!yyjson_is_obj(root)) fail("manifest.json must be a JSON object");

    // Top-level keys must be unique (yyjson keeps duplicates; the last one would silently win elsewhere).
    {
        std::unordered_set<std::string> keys;
        yyjson_obj_iter iter = yyjson_obj_iter_with(root);
        yyjson_val* key = nullptr;
        while ((key = yyjson_obj_iter_next(&iter)) != nullptr) {
            std::string k(yyjson_get_str(key), yyjson_get_len(key));
            if (!keys.insert(k).second) fail("manifest.json: duplicate key \"" + k + "\"");
        }
    }

    if (str_field(root, "format", "manifest", true) != "omchar") fail("format is not \"omchar\"");
    parse_version(str_field(root, "version", "manifest", true));

    Character c;
    c.name = str_field(root, "name", "manifest", false);
    c.object_id = str_field(root, "objectId", "manifest", false);
    c.profile = str_field(root, "profile", "manifest", false);
    c.anim_graph = str_field(root, "animGraph", "manifest", false);
    if (yyjson_val* sk = yyjson_obj_get(root, "skeleton")) read_skeleton(sk, c, entries);
    read_sections(root, c);

    yyjson_obj_iter iter = yyjson_obj_iter_with(root);
    yyjson_val* key = nullptr;
    while ((key = yyjson_obj_iter_next(&iter)) != nullptr) {
        std::string k(yyjson_get_str(key), yyjson_get_len(key));
        if (!is_known(k)) c.unknown_sections.push_back({std::move(k), minified(yyjson_obj_iter_get_val(key))});
    }

    for (ZipEntry& e : entries) {
        if (e.name == kManifestEntry || e.name == c.model_entry) continue;
        e.precompressed = false;
        c.unknown_entries.push_back(std::move(e));
    }
    return c;
}

std::vector<std::byte> write(const Character& c) {
    if (c.model_entry.empty() && !c.model.empty()) fail("an embedded model needs an entry name");
    if (c.model_entry == kManifestEntry) fail("skeleton.model cannot be the manifest");

    const std::string manifest = write_manifest(c);
    std::vector<ZipEntry> entries;
    entries.reserve(2 + c.unknown_entries.size());
    ZipEntry m;
    m.name = kManifestEntry;
    m.data.resize(manifest.size());
    std::memcpy(m.data.data(), manifest.data(), manifest.size());
    entries.push_back(std::move(m));
    if (!c.model_entry.empty()) {
        // An .omo is itself a zip: deflate it at the fastest level (see ZipEntry::precompressed).
        entries.push_back({c.model_entry, c.model, true});
    }
    for (const ZipEntry& e : c.unknown_entries) {
        if (e.name == kManifestEntry || e.name == c.model_entry) fail("entry \"" + e.name + "\" collides");
        entries.push_back(e);
    }
    return write_zip(entries);
}

}  // namespace cenda::omformat::omchar
