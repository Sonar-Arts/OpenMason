#include "omformat/omappearance.hpp"

#include "json_util.hpp"

#include <unordered_set>

namespace cenda::omformat::omappearance {

namespace {

using json::DocPtr;

[[noreturn]] void fail(const std::string& message) { throw format_error(".omchar appearance: " + message); }

std::string str(yyjson_val* v) { return {yyjson_get_str(v), yyjson_get_len(v)}; }

yyjson_val* field(yyjson_val* obj, const char* key) {
    yyjson_val* v = yyjson_obj_get(obj, key);
    return v == nullptr || yyjson_is_null(v) ? nullptr : v;
}

std::string str_field(yyjson_val* obj, const char* key, const std::string& at, bool required) {
    yyjson_val* v = field(obj, key);
    if (v == nullptr) {
        if (required) fail(at + ": missing \"" + key + "\"");
        return {};
    }
    if (!yyjson_is_str(v)) fail(at + ": \"" + key + "\" must be a string");
    return str(v);
}

yyjson_val* arr(yyjson_val* obj, const char* key, const std::string& at) {
    yyjson_val* v = field(obj, key);
    if (v == nullptr) return nullptr;
    if (!yyjson_is_arr(v)) fail(at + ": \"" + key + "\" must be an array");
    return v;
}

int hexd(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

template <typename F>
void each(yyjson_val* a, const std::string& at, F f) {
    if (a == nullptr) return;
    for (std::size_t i = 0, n = yyjson_arr_size(a); i < n; ++i) {
        yyjson_val* v = yyjson_arr_get(a, i);
        const std::string where = at + "[" + std::to_string(i) + "]";
        if (!yyjson_is_obj(v)) fail(where + " must be an object");
        f(v, where);
    }
}

}  // namespace

int Section::tint(std::string_view name) const {
    for (std::size_t i = 0; i < tints.size(); ++i) {
        if (tints[i].name == name) return static_cast<int>(i);
    }
    return -1;
}

int Section::group(std::string_view name) const {
    for (std::size_t i = 0; i < groups.size(); ++i) {
        if (groups[i].name == name) return static_cast<int>(i);
    }
    return -1;
}

Section parse(std::string_view text) {
    Section s;
    if (text.empty()) return s;
    yyjson_read_err err{};
    DocPtr doc(yyjson_read_opts(const_cast<char*>(text.data()), text.size(), YYJSON_READ_NOFLAG, nullptr, &err));
    if (!doc) fail(std::string("not JSON: ") + err.msg);
    yyjson_val* root = yyjson_doc_get_root(doc.get());
    if (!yyjson_is_obj(root)) fail("must be an object");
    std::unordered_set<std::string> slots{kBodySlot};
    auto slot_name = [&](const std::string& name, const std::string& at) {
        if (name.empty()) fail(at + ": empty name");
        if (!slots.insert(name).second) fail(at + ": slot name \"" + name + "\" is taken");
    };

    each(arr(root, "tints", "tints"), "tints", [&](yyjson_val* v, const std::string& at) {
        Tint t;
        t.name = str_field(v, "name", at, true);
        slot_name(t.name, at);
        const std::string def = str_field(v, "default", at, false);
        if (!def.empty()) {
            if (def.size() != 7 || def[0] != '#') fail(at + ": default must be \"#rrggbb\"");
            for (int i = 0; i < 3; ++i) {
                const int h = hexd(def[static_cast<std::size_t>(1 + 2 * i)]), l = hexd(def[static_cast<std::size_t>(2 + 2 * i)]);
                if (h < 0 || l < 0) fail(at + ": default must be \"#rrggbb\"");
                t.rgb[i] = static_cast<std::uint8_t>(h * 16 + l);
            }
        }
        s.tints.push_back(std::move(t));
    });
    std::unordered_set<int> ids;
    each(arr(root, "materials", "materials"), "materials", [&](yyjson_val* v, const std::string& at) {
        MaterialDye m;
        yyjson_val* id = field(v, "id");
        if (id == nullptr || !yyjson_is_int(id)) fail(at + ": \"id\" must be an integer");
        m.id = static_cast<int>(yyjson_get_int(id));
        if (!ids.insert(m.id).second) fail(at + ": material " + std::to_string(m.id) + " listed twice");
        m.tint = str_field(v, "tint", at, false);
        m.mask = str_field(v, "mask", at, false);
        if (m.mask.empty()) {
            if (m.tint.empty()) fail(at + ": needs a tint or a mask");
            if (s.tint(m.tint) < 0) fail(at + ": unknown tint " + m.tint);
        } else {
            if (!m.tint.empty()) fail(at + ": a masked material names its tints in channels");
            yyjson_val* ch = field(v, "channels");
            if (ch == nullptr || !yyjson_is_obj(ch)) fail(at + ": a masked material needs \"channels\"");
            static const char* keys[4] = {"r", "g", "b", "a"};
            bool any = false;
            for (int k = 0; k < 4; ++k) {
                m.channels[k] = str_field(ch, keys[k], at + " channels", false);
                if (m.channels[k].empty()) continue;
                if (s.tint(m.channels[k]) < 0) fail(at + ": unknown tint " + m.channels[k]);
                any = true;
            }
            if (!any) fail(at + ": no channel names a tint");
        }
        s.materials.push_back(std::move(m));
    });
    each(arr(root, "groups", "groups"), "groups", [&](yyjson_val* v, const std::string& at) {
        Group g;
        g.name = str_field(v, "name", at, true);
        slot_name(g.name, at);
        yyjson_val* parts = arr(v, "parts", at);
        if (parts == nullptr) fail(at + ": missing \"parts\"");
        for (std::size_t i = 0, n = yyjson_arr_size(parts); i < n; ++i) {
            yyjson_val* p = yyjson_arr_get(parts, i);
            if (!yyjson_is_str(p)) fail(at + ": parts must be joint ids");
            g.parts.push_back(str(p));
        }
        if (yyjson_val* vis = field(v, "visible")) {
            if (!yyjson_is_bool(vis)) fail(at + ": \"visible\" must be true or false");
            g.visible = yyjson_get_bool(vis);
        }
        s.groups.push_back(std::move(g));
    });
    std::unordered_set<std::string> body_names;
    each(arr(root, "bodies", "bodies"), "bodies", [&](yyjson_val* v, const std::string& at) {
        Body b;
        b.name = str_field(v, "name", at, true);
        b.model = str_field(v, "model", at, true);
        if (b.name.empty() || !body_names.insert(b.name).second) fail(at + ": body names must be unique and non-empty");
        s.bodies.push_back(std::move(b));
    });
    each(arr(root, "equipment", "equipment"), "equipment", [&](yyjson_val* v, const std::string& at) {
        Equipment e;
        e.name = str_field(v, "name", at, true);
        slot_name(e.name, at);
        const std::string kind = str_field(v, "kind", at, false);
        if (kind.empty() || kind == "rigid") {
            e.kind = EquipmentKind::Rigid;
        } else if (kind == "skinned") {
            e.kind = EquipmentKind::Skinned;
        } else {
            fail(at + ": kind must be rigid or skinned");
        }
        e.socket = str_field(v, "socket", at, false);
        if (yyjson_val* hides = arr(v, "hides", at)) {
            for (std::size_t i = 0, n = yyjson_arr_size(hides); i < n; ++i) {
                yyjson_val* h = yyjson_arr_get(hides, i);
                if (!yyjson_is_str(h)) fail(at + ": hides must be group names");
                e.hides.push_back(str(h));
                if (s.group(e.hides.back()) < 0) fail(at + ": unknown group " + e.hides.back());
            }
        }
        s.equipment.push_back(std::move(e));
    });
    return s;
}

}  // namespace cenda::omformat::omappearance
