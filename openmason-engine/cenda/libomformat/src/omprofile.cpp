#include "omformat/omprofile.hpp"

#include "builtin_profiles.hpp"
#include "json_util.hpp"

#include <algorithm>
#include <cctype>
#include <charconv>
#include <cmath>
#include <map>
#include <mutex>
#include <sstream>
#include <unordered_map>
#include <unordered_set>

namespace cenda::omformat::omprofile {

namespace {

using json::CharPtr;
using json::DocPtr;
using json::MutDocPtr;

[[noreturn]] void fail(const std::string& message) {
    throw format_error(".omprofile: " + message);
}

std::string str(yyjson_val* v) { return {yyjson_get_str(v), yyjson_get_len(v)}; }

std::string str_field(yyjson_val* obj, const char* key, const std::string& where, bool required) {
    yyjson_val* v = yyjson_obj_get(obj, key);
    if (v == nullptr || yyjson_is_null(v)) {
        if (required) fail(where + ": missing \"" + key + "\"");
        return {};
    }
    if (!yyjson_is_str(v)) fail(where + ": \"" + key + "\" must be a string");
    return str(v);
}

std::vector<std::string> str_list(yyjson_val* obj, const char* key, const std::string& where) {
    std::vector<std::string> out;
    yyjson_val* v = yyjson_obj_get(obj, key);
    if (v == nullptr || yyjson_is_null(v)) return out;
    if (!yyjson_is_arr(v)) fail(where + ": \"" + key + "\" must be an array of strings");
    for (std::size_t i = 0, n = yyjson_arr_size(v); i < n; ++i) {
        yyjson_val* e = yyjson_arr_get(v, i);
        if (!yyjson_is_str(e)) fail(where + ": \"" + key + "\" must be an array of strings");
        out.push_back(str(e));
    }
    return out;
}

std::string lower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return s;
}

std::vector<std::string> split_tokens(const std::string& text) {
    std::vector<std::string> out;
    std::istringstream in(lower(text));
    std::string t;
    while (in >> t) out.push_back(t);
    return out;
}

void parse_version(const std::string& version) {
    const auto dot = version.find('.');
    int major = -1;
    int minor = -1;
    const char* b = version.data();
    const char* e = b + version.size();
    bool ok = dot != std::string::npos && dot > 0;
    if (ok) {
        auto [p1, e1] = std::from_chars(b, b + dot, major);
        auto [p2, e2] = std::from_chars(b + dot + 1, e, minor);
        ok = e1 == std::errc{} && p1 == b + dot && e2 == std::errc{} && p2 == e && major >= 1 && minor >= 0;
    }
    if (!ok) fail("version \"" + version + "\" is not <major>.<minor>");
    if (major > kVersionMajor) {
        throw version_error(".omprofile version " + version + " is newer than this reader (" +
                            std::to_string(kVersionMajor) + "." + std::to_string(kVersionMinor) + ")");
    }
}

Side parse_side(const std::string& s, const std::string& where) {
    if (s.empty() || s == "center") return Side::Center;
    if (s == "left") return Side::Left;
    if (s == "right") return Side::Right;
    fail(where + ": side must be center, left or right");
}

const char* side_name(Side s) {
    switch (s) {
        case Side::Left: return "left";
        case Side::Right: return "right";
        default: return "center";
    }
}

Role parse_role(yyjson_val* r, std::size_t index) {
    const std::string where = "roles[" + std::to_string(index) + "]";
    if (!yyjson_is_obj(r)) fail(where + " must be an object");
    Role role;
    role.name = str_field(r, "name", where, true);
    if (role.name.empty()) fail(where + ": empty name");
    const std::string at = "role " + role.name;
    role.parent = str_field(r, "parent", at, false);
    if (yyjson_val* req = yyjson_obj_get(r, "required")) {
        if (!yyjson_is_bool(req)) fail(at + ": \"required\" must be true or false");
        role.required = yyjson_get_bool(req);
    }
    role.side = parse_side(str_field(r, "side", at, false), at);
    role.mirror = str_field(r, "mirror", at, false);
    role.toward = str_list(r, "toward", at);
    if (yyjson_val* d = yyjson_obj_get(r, "direction"); d != nullptr && !yyjson_is_null(d)) {
        if (!yyjson_is_arr(d) || yyjson_arr_size(d) != 3) fail(at + ": \"direction\" must be [x, y, z]");
        double v[3];
        for (std::size_t i = 0; i < 3; ++i) {
            yyjson_val* c = yyjson_arr_get(d, i);
            if (!yyjson_is_num(c) || !std::isfinite(yyjson_get_num(c))) fail(at + ": direction must be numbers");
            v[i] = yyjson_get_num(c);
        }
        const double len = std::sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len < 1e-9) fail(at + ": direction is zero");
        for (std::size_t i = 0; i < 3; ++i) role.direction[i] = static_cast<float>(v[i] / len);
        role.has_direction = true;
    }
    if (yyjson_val* m = yyjson_obj_get(r, "match"); m != nullptr && !yyjson_is_null(m)) {
        if (!yyjson_is_arr(m)) fail(at + ": \"match\" must be [[alias, weight], ...]");
        for (std::size_t i = 0, n = yyjson_arr_size(m); i < n; ++i) {
            yyjson_val* pair = yyjson_arr_get(m, i);
            if (!yyjson_is_arr(pair) || yyjson_arr_size(pair) != 2 || !yyjson_is_str(yyjson_arr_get(pair, 0)) ||
                !yyjson_is_num(yyjson_arr_get(pair, 1))) {
                fail(at + ": \"match\" entries are [alias, weight]");
            }
            Alias alias;
            alias.tokens = split_tokens(str(yyjson_arr_get(pair, 0)));
            const double w = yyjson_get_num(yyjson_arr_get(pair, 1));
            if (alias.tokens.empty()) fail(at + ": empty alias");
            if (!(w > 0.0 && w <= 1.0)) fail(at + ": alias weights are in (0, 1]");
            alias.weight = static_cast<float>(w);
            role.match.push_back(std::move(alias));
        }
    }
    if (yyjson_val* c = yyjson_obj_get(r, "chain"); c != nullptr && !yyjson_is_null(c)) {
        if (!yyjson_is_obj(c)) fail(at + ": \"chain\" must be an object");
        ChainRule rule;
        rule.under = str_field(c, "under", at + ".chain", true);
        for (std::string& k : str_list(c, "keywords", at + ".chain")) rule.keywords.push_back(lower(k));
        if (rule.keywords.empty()) fail(at + ": a chain needs keywords");
        yyjson_val* pos = yyjson_obj_get(c, "position");
        if (pos == nullptr || !yyjson_is_int(pos) || yyjson_get_sint(pos) < 0 || yyjson_get_sint(pos) > 64) {
            fail(at + ": chain.position must be an integer 0..64");
        }
        rule.position = static_cast<int>(yyjson_get_sint(pos));
        role.chain = std::move(rule);
    }
    return role;
}

void validate(const Profile& p) {
    if (p.id.empty()) fail("missing id");
    if (p.roles.empty()) fail("profile " + p.id + " has no roles");
    std::unordered_map<std::string, std::size_t> index;
    for (std::size_t i = 0; i < p.roles.size(); ++i) {
        const Role& r = p.roles[i];
        if (!index.emplace(r.name, i).second) fail("duplicate role " + r.name);
        if (!r.parent.empty()) {
            auto it = index.find(r.parent);
            if (it == index.end()) fail("role " + r.name + ": parent " + r.parent + " must be listed before it");
        }
    }
    auto known = [&](const std::string& name, const std::string& what) {
        if (!index.contains(name)) fail(what + " names unknown role " + name);
    };
    for (const Role& r : p.roles) {
        for (const std::string& t : r.toward) known(t, "role " + r.name + ": toward");
        if (r.chain) known(r.chain->under, "role " + r.name + ": chain.under");
        if (!r.mirror.empty()) {
            known(r.mirror, "role " + r.name + ": mirror");
            const Role& m = p.roles[index.at(r.mirror)];
            if (m.mirror != r.name) fail("roles " + r.name + " and " + r.mirror + " must mirror each other");
            const bool opposite = (r.side == Side::Left && m.side == Side::Right) ||
                                  (r.side == Side::Right && m.side == Side::Left);
            if (!opposite) fail("mirror roles " + r.name + " and " + r.mirror + " need opposite sides");
        }
    }
    std::unordered_map<std::string, std::string> goals;
    for (const IkGoal& g : p.ik_goals) {
        if (g.name.empty()) fail("an ik goal needs a name");
        known(g.role, "ik goal " + g.name);
        if (!goals.emplace(g.name, g.role).second) fail("duplicate ik goal " + g.name);
    }
    std::unordered_map<std::string, int> chains;
    for (const RetargetChain& c : p.chains) {
        if (c.name.empty()) fail("a chain needs a name");
        if (!chains.emplace(c.name, 0).second) fail("duplicate chain " + c.name);
        if (c.roles.empty()) fail("chain " + c.name + " has no roles");
        for (const std::string& r : c.roles) known(r, "chain " + c.name);
        if (!c.goal.empty() && !goals.contains(c.goal)) fail("chain " + c.name + " names unknown ik goal " + c.goal);
    }
    for (const std::string& f : p.facing.roles) known(f, "facing.roles");
    if (!p.facing.from.empty()) known(p.facing.from, "facing.from");
    if (!p.facing.to.empty()) known(p.facing.to, "facing.to");
    if (p.facing.from.empty() != p.facing.to.empty()) fail("facing needs both \"from\" and \"to\"");
}

yyjson_mut_val* mstr(yyjson_mut_doc* d, const std::string& s) { return yyjson_mut_strncpy(d, s.data(), s.size()); }

void put(yyjson_mut_doc* d, yyjson_mut_val* obj, const char* key, yyjson_mut_val* val) {
    if (!val || !yyjson_mut_obj_add(obj, yyjson_mut_strcpy(d, key), val)) fail("out of memory");
}

yyjson_mut_val* mlist(yyjson_mut_doc* d, const std::vector<std::string>& items) {
    yyjson_mut_val* a = yyjson_mut_arr(d);
    for (const std::string& s : items) yyjson_mut_arr_append(a, mstr(d, s));
    return a;
}

std::string join(const std::vector<std::string>& tokens) {
    std::string out;
    for (const std::string& t : tokens) {
        if (!out.empty()) out += ' ';
        out += t;
    }
    return out;
}

}  // namespace

int Profile::find(std::string_view role) const {
    for (std::size_t i = 0; i < roles.size(); ++i) {
        if (roles[i].name == role) return static_cast<int>(i);
    }
    return -1;
}

Profile read(std::string_view text) {
    yyjson_read_err err{};
    DocPtr doc(yyjson_read_opts(const_cast<char*>(text.data()), text.size(), YYJSON_READ_NOFLAG, nullptr, &err));
    if (!doc) fail(std::string("not valid JSON (") + err.msg + " at byte " + std::to_string(err.pos) + ")");
    yyjson_val* root = yyjson_doc_get_root(doc.get());
    if (!yyjson_is_obj(root)) fail("must be a JSON object");
    if (str_field(root, "format", "profile", true) != "omprofile") fail("format is not \"omprofile\"");
    parse_version(str_field(root, "version", "profile", true));

    Profile p;
    p.id = str_field(root, "id", "profile", true);
    p.name = str_field(root, "name", "profile", false);
    p.description = str_field(root, "description", "profile", false);
    if (yyjson_val* f = yyjson_obj_get(root, "facing"); f != nullptr && !yyjson_is_null(f)) {
        if (!yyjson_is_obj(f)) fail("\"facing\" must be an object");
        p.facing.roles = str_list(f, "roles", "facing");
        p.facing.from = str_field(f, "from", "facing", false);
        p.facing.to = str_field(f, "to", "facing", false);
    }
    yyjson_val* roles = yyjson_obj_get(root, "roles");
    if (roles == nullptr || !yyjson_is_arr(roles)) fail("\"roles\" must be an array");
    for (std::size_t i = 0, n = yyjson_arr_size(roles); i < n; ++i) p.roles.push_back(parse_role(yyjson_arr_get(roles, i), i));
    if (yyjson_val* cs = yyjson_obj_get(root, "chains"); cs != nullptr && !yyjson_is_null(cs)) {
        if (!yyjson_is_arr(cs)) fail("\"chains\" must be an array");
        for (std::size_t i = 0, n = yyjson_arr_size(cs); i < n; ++i) {
            yyjson_val* c = yyjson_arr_get(cs, i);
            const std::string at = "chains[" + std::to_string(i) + "]";
            if (!yyjson_is_obj(c)) fail(at + " must be an object");
            RetargetChain chain;
            chain.name = str_field(c, "name", at, true);
            chain.roles = str_list(c, "roles", at);
            chain.goal = str_field(c, "goal", at, false);
            if (yyjson_val* b = yyjson_obj_get(c, "bend"); b != nullptr && !yyjson_is_null(b)) {
                if (!yyjson_is_arr(b) || yyjson_arr_size(b) != 3) fail(at + ": \"bend\" must be [x, y, z]");
                double v[3], l = 0.0;
                for (std::size_t k = 0; k < 3; ++k) {
                    yyjson_val* e = yyjson_arr_get(b, k);
                    if (!yyjson_is_num(e)) fail(at + ": \"bend\" must be numbers");
                    v[k] = yyjson_get_num(e);
                    l += v[k] * v[k];
                }
                l = std::sqrt(l);
                if (!(l > 1e-9) || !std::isfinite(l)) fail(at + ": \"bend\" must be a non-zero vector");
                chain.has_bend = true;
                for (int k = 0; k < 3; ++k) chain.bend[k] = static_cast<float>(v[k] / l);
            }
            p.chains.push_back(std::move(chain));
        }
    }
    if (yyjson_val* gs = yyjson_obj_get(root, "ikGoals"); gs != nullptr && !yyjson_is_null(gs)) {
        if (!yyjson_is_arr(gs)) fail("\"ikGoals\" must be an array");
        for (std::size_t i = 0, n = yyjson_arr_size(gs); i < n; ++i) {
            yyjson_val* g = yyjson_arr_get(gs, i);
            const std::string at = "ikGoals[" + std::to_string(i) + "]";
            if (!yyjson_is_obj(g)) fail(at + " must be an object");
            p.ik_goals.push_back({str_field(g, "name", at, true), str_field(g, "role", at, true)});
        }
    }
    validate(p);
    return p;
}

std::string write(const Profile& p) {
    MutDocPtr doc(yyjson_mut_doc_new(nullptr));
    if (!doc) fail("out of memory");
    yyjson_mut_doc* d = doc.get();
    yyjson_mut_val* root = yyjson_mut_obj(d);
    yyjson_mut_doc_set_root(d, root);
    put(d, root, "format", yyjson_mut_str(d, "omprofile"));
    put(d, root, "version", mstr(d, std::to_string(kVersionMajor) + "." + std::to_string(kVersionMinor)));
    put(d, root, "id", mstr(d, p.id));
    if (!p.name.empty()) put(d, root, "name", mstr(d, p.name));
    if (!p.description.empty()) put(d, root, "description", mstr(d, p.description));
    if (!p.facing.roles.empty() || !p.facing.from.empty()) {
        yyjson_mut_val* f = yyjson_mut_obj(d);
        if (!p.facing.roles.empty()) put(d, f, "roles", mlist(d, p.facing.roles));
        if (!p.facing.from.empty()) {
            put(d, f, "from", mstr(d, p.facing.from));
            put(d, f, "to", mstr(d, p.facing.to));
        }
        put(d, root, "facing", f);
    }
    yyjson_mut_val* roles = yyjson_mut_arr(d);
    for (const Role& r : p.roles) {
        yyjson_mut_val* o = yyjson_mut_obj(d);
        put(d, o, "name", mstr(d, r.name));
        put(d, o, "parent", r.parent.empty() ? yyjson_mut_null(d) : mstr(d, r.parent));
        put(d, o, "required", yyjson_mut_bool(d, r.required));
        put(d, o, "side", yyjson_mut_str(d, side_name(r.side)));
        if (!r.mirror.empty()) put(d, o, "mirror", mstr(d, r.mirror));
        if (!r.toward.empty()) put(d, o, "toward", mlist(d, r.toward));
        if (r.has_direction) {
            yyjson_mut_val* dir = yyjson_mut_arr(d);
            for (float c : r.direction) yyjson_mut_arr_append(dir, yyjson_mut_float(d, c));
            put(d, o, "direction", dir);
        }
        if (!r.match.empty()) {
            yyjson_mut_val* m = yyjson_mut_arr(d);
            for (const Alias& a : r.match) {
                yyjson_mut_val* pair = yyjson_mut_arr(d);
                yyjson_mut_arr_append(pair, mstr(d, join(a.tokens)));
                yyjson_mut_arr_append(pair, yyjson_mut_float(d, a.weight));
                yyjson_mut_arr_append(m, pair);
            }
            put(d, o, "match", m);
        }
        if (r.chain) {
            yyjson_mut_val* c = yyjson_mut_obj(d);
            put(d, c, "under", mstr(d, r.chain->under));
            put(d, c, "keywords", mlist(d, r.chain->keywords));
            put(d, c, "position", yyjson_mut_sint(d, r.chain->position));
            put(d, o, "chain", c);
        }
        yyjson_mut_arr_append(roles, o);
    }
    put(d, root, "roles", roles);
    if (!p.chains.empty()) {
        yyjson_mut_val* cs = yyjson_mut_arr(d);
        for (const RetargetChain& c : p.chains) {
            yyjson_mut_val* o = yyjson_mut_obj(d);
            put(d, o, "name", mstr(d, c.name));
            put(d, o, "roles", mlist(d, c.roles));
            if (!c.goal.empty()) put(d, o, "goal", mstr(d, c.goal));
            if (c.has_bend) {
                yyjson_mut_val* b = yyjson_mut_arr(d);
                for (float v : c.bend) yyjson_mut_arr_append(b, yyjson_mut_float(d, v));
                put(d, o, "bend", b);
            }
            yyjson_mut_arr_append(cs, o);
        }
        put(d, root, "chains", cs);
    }
    if (!p.ik_goals.empty()) {
        yyjson_mut_val* gs = yyjson_mut_arr(d);
        for (const IkGoal& g : p.ik_goals) {
            yyjson_mut_val* o = yyjson_mut_obj(d);
            put(d, o, "name", mstr(d, g.name));
            put(d, o, "role", mstr(d, g.role));
            yyjson_mut_arr_append(gs, o);
        }
        put(d, root, "ikGoals", gs);
    }

    std::size_t len = 0;
    yyjson_write_err err{};
    CharPtr text(yyjson_mut_write_opts(d, YYJSON_WRITE_PRETTY_TWO_SPACES | YYJSON_WRITE_NEWLINE_AT_END, nullptr, &len, &err));
    if (!text) fail(std::string("write: ") + err.msg);
    return {text.get(), len};
}

const std::vector<std::string>& builtin_ids() {
    static const std::vector<std::string> ids = [] {
        std::vector<std::string> out;
        for (const auto& t : detail::builtin_texts()) out.emplace_back(t.id);
        return out;
    }();
    return ids;
}

const Profile& builtin(std::string_view id) {
    // Parsed once each, on first use; never freed (they live as long as the library).
    static std::mutex mutex;
    static std::map<std::string, Profile, std::less<>> parsed;
    std::lock_guard lock(mutex);
    if (auto it = parsed.find(id); it != parsed.end()) return it->second;
    for (const auto& t : detail::builtin_texts()) {
        if (id == t.id) return parsed.emplace(std::string(id), read(t.json)).first->second;
    }
    fail("no built-in profile \"" + std::string(id) + "\"");
}

}  // namespace cenda::omformat::omprofile
