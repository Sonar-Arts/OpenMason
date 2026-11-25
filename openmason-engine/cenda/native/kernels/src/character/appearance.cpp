// Cenda character appearance (OpenMason #162): records (cc_look), garments (cc_garment: leader-pose remap + fit
// onto another character of the same profile) and resolution (cc_appearance). See cenda/appearance.h.

#include "character_internal.hpp"

#include "cenda/appearance.h"
#include "omformat/omappearance.hpp"
#include "omformat/omlook.hpp"

#include "../rig/rig_quat.hpp"

#include <array>
#include <cstring>
#include <memory>
#include <new>
#include <unordered_map>

using cenda::rig::Joint;
using cenda::rig::Mat4;
using cenda::rig::Pose;
using cenda::rig::Quat;

namespace ch = cenda::character;

// The Java binding (character.CendaAppearance) marshals these by offset.
static_assert(sizeof(cc_garment_desc) == 64, "cc_garment_desc layout");
static_assert(sizeof(cc_item_desc) == 88, "cc_item_desc layout");
namespace ol = cenda::omformat::omlook;
namespace oa = cenda::omformat::omappearance;

struct cc_look {
    ol::Look look;
    mutable std::vector<std::string> hex;  // unknown types' payloads as text (cc_look_get)
};

struct cc_garment {
    std::vector<float> positions;
    cr_skin* skin = nullptr;
    std::vector<int32_t> joint_of_ref;
    int32_t fallback = 0;

    ~cc_garment() {
        if (skin != nullptr) cr_skin_destroy(skin);
    }
};

struct cc_appearance {
    struct Item {
        std::string id, slot;
        int slot_index = -1;  // equipment index
        int32_t kind = CC_ITEM_RIGID;
        std::vector<int> hides;  // group indices
        int32_t joint = -1;
        float matrix[16]{};
    };
    struct Slot {
        std::string name;
        int32_t kind = 0;
        int32_t type = 0;
        int index = 0;  // into its section list
    };
    struct Material {
        int32_t id = 0;
        std::string mask;
        float tints[12]{};
    };
    struct Equipped {
        int slot = 0;
        int item = 0;
    };

    const cc_char* chr = nullptr;
    const cr_rig* rig = nullptr;
    oa::Section sec;
    std::vector<Slot> slots;
    std::vector<std::vector<int32_t>> group_joints;
    std::vector<int32_t> slot_socket_joint;  // per equipment slot (-1 = none)
    std::vector<Mat4> slot_socket;
    std::vector<Item> items;
    // Resolved state.
    int body = -1;
    std::vector<uint8_t> group_on;
    std::vector<std::array<uint8_t, 3>> tint_rgb;
    std::vector<int> equipped;  // per equipment slot: item index, -1 = empty
    std::vector<Material> materials;
    std::vector<Equipped> equipped_list;
};

namespace {

using ch::error;

void set_rgb(ol::Entry& e, const float* rgb) {
    for (int i = 0; i < 3; ++i) {
        const float c = std::isfinite(rgb[i]) ? std::clamp(rgb[i], 0.f, 1.f) : 0.f;
        e.rgb[i] = static_cast<uint8_t>(std::lround(c * 255.f));
    }
}

ol::Entry& upsert(ol::Look& look, const char* name) {
    const int i = look.find(name);
    if (i >= 0) {
        ol::Entry& e = look.entries[static_cast<size_t>(i)];
        const std::string keep = e.name;
        e = ol::Entry{};
        e.name = keep;
        return e;
    }
    look.entries.push_back(ol::Entry{});
    look.entries.back().name = name;
    return look.entries.back();
}

cc_bytes* to_bytes(std::vector<std::byte> data) {
    auto* b = new (std::nothrow) cc_bytes();
    if (b == nullptr) return nullptr;
    b->data = std::move(data);
    return b;
}

bool is_color_text(const char* s) {
    if (s == nullptr || std::strlen(s) != 7 || s[0] != '#') return false;
    for (int i = 1; i < 7; ++i) {
        if (!std::isxdigit(static_cast<unsigned char>(s[i]))) return false;
    }
    return true;
}

// ---- vectors (double) ----

struct V3 {
    double x = 0, y = 0, z = 0;
};
V3 operator+(V3 a, V3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
V3 operator-(V3 a, V3 b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
V3 operator*(V3 a, double s) { return {a.x * s, a.y * s, a.z * s}; }
double dot(V3 a, V3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
V3 cross(V3 a, V3 b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
double length(V3 a) { return std::sqrt(dot(a, a)); }
constexpr double EPS = 1e-9;

V3 rotate(const Quat& q, V3 v) {
    double r[9];
    cenda::rig::qmatrix(q, r);
    return {r[0] * v.x + r[1] * v.y + r[2] * v.z, r[3] * v.x + r[4] * v.y + r[5] * v.z,
            r[6] * v.x + r[7] * v.y + r[8] * v.z};
}

V3 apply(const Mat4& m, V3 p) {
    const double in[3] = {p.x, p.y, p.z};
    double out[3];
    m.transform_point(in, out);
    return {out[0], out[1], out[2]};
}

// The shortest rotation taking direction a to b.
Quat from_to(V3 a, V3 b) {
    const double la = length(a), lb = length(b);
    if (la < EPS || lb < EPS) return Quat{};
    a = a * (1.0 / la);
    b = b * (1.0 / lb);
    const double d = dot(a, b);
    if (d < -1.0 + 1e-12) {
        V3 axis = cross(V3{1, 0, 0}, a);
        if (length(axis) < 1e-6) axis = cross(V3{0, 1, 0}, a);
        axis = axis * (1.0 / length(axis));
        return cenda::rig::qaxis(axis.x, axis.y, axis.z, 3.14159265358979323846);
    }
    const V3 c = cross(a, b);
    return cenda::rig::qnormalize(Quat{1.0 + d, c.x, c.y, c.z});
}

Quat facing_turn(const std::string& f) {
    const double h = 3.14159265358979323846 / 2.0;
    if (f == "+z") return cenda::rig::qaxis(0, 1, 0, 2.0 * h);
    if (f == "-x") return cenda::rig::qaxis(0, 1, 0, -h);
    if (f == "+x") return cenda::rig::qaxis(0, 1, 0, h);
    return Quat{};
}

// One character in its reference pose.
struct RefSide {
    const cr_rig* rig = nullptr;
    const cc_char* chr = nullptr;
    Quat F;  // model -> profile
    std::vector<Mat4> world, child, skin;  // reference pose: W, child frame, K = W·W_rest^-1
    double size = 1.0;                     // hips height above the lowest joint (reference pose)

    void init(const cr_rig* r, const cc_char* c) {
        rig = r;
        chr = c;
        F = facing_turn(c->def.facing);
        const size_t n = r->joints.size();
        std::vector<Pose> ref(n);
        for (size_t i = 0; i < n; ++i) {
            const Joint& j = r->joints[i];
            std::memcpy(ref[i].pos, j.pos, sizeof j.pos);
            std::memcpy(ref[i].rot, j.rot, sizeof j.rot);
            std::memcpy(ref[i].scale, j.scale, sizeof j.scale);
        }
        for (const auto& jr : c->def.reference_pose) {
            const int j = ch::rig_index(r, jr.joint);
            if (j < 0) continue;
            ref[static_cast<size_t>(j)].rot[0] = jr.rot.x;
            ref[static_cast<size_t>(j)].rot[1] = jr.rot.y;
            ref[static_cast<size_t>(j)].rot[2] = jr.rot.z;
        }
        world.resize(n);
        child.resize(n);
        skin.resize(n);
        for (int idx : r->order) {
            const size_t i = static_cast<size_t>(idx);
            const Joint& j = r->joints[i];
            const Mat4 parent = j.parent >= 0 ? child[static_cast<size_t>(j.parent)] : Mat4::identity();
            world[i] = parent * cenda::rig::joint_local_matrix(j, ref[i]);
            child[i] = cenda::rig::joint_child_frame(j, world[i]);
        }
        for (size_t i = 0; i < n; ++i) skin[i] = world[i] * r->rest_world_inv[i];
        const char* hips = cc_char_role_joint(c, "hips");
        const int h = hips == nullptr ? -1 : ch::rig_index(r, hips);
        double lo = 1e300;
        for (size_t i = 0; i < n; ++i) lo = std::min(lo, point(static_cast<int>(i)).y);
        if (h >= 0 && point(h).y - lo > EPS) size = point(h).y - lo;
    }

    [[nodiscard]] V3 point(int j) const {
        const Joint& jt = rig->joints[static_cast<size_t>(j)];
        const V3 local = jt.kind == CR_JOINT_PART ? V3{jt.origin[0], jt.origin[1], jt.origin[2]} : V3{};
        return apply(world[static_cast<size_t>(j)], local);
    }

    // The joint's direction in the reference pose (model space): a bone's head -> tail, a part's pivot -> its
    // first child's point; zero when it has neither.
    [[nodiscard]] V3 direction(int j) const {
        const Joint& jt = rig->joints[static_cast<size_t>(j)];
        if (jt.kind == CR_JOINT_BONE) {
            const Mat4& c = child[static_cast<size_t>(j)];
            const V3 d = V3{c.at(0, 3), c.at(1, 3), c.at(2, 3)} - point(j);
            if (length(d) > EPS) return d;
        }
        for (size_t k = 0; k < rig->joints.size(); ++k) {
            if (rig->joints[k].parent == j) {
                const V3 d = point(static_cast<int>(k)) - point(j);
                if (length(d) > EPS) return d;
            }
        }
        return {};
    }

    // The role a joint holds, or "".
    [[nodiscard]] std::string role_of(int j) const {
        const std::string& id = rig->joints[static_cast<size_t>(j)].id;
        for (const auto& r : chr->def.roles) {
            if (r.joint == id) return r.role;
        }
        return {};
    }
};

int resolve_ref(const cr_rig* rig, const cc_char* chr, const char* ref) {
    if (ref == nullptr || ref[0] == '\0') return -1;
    if (std::strncmp(ref, "role:", 5) == 0) {
        if (chr == nullptr) return -1;
        const char* id = cc_char_role_joint(chr, ref + 5);
        return id == nullptr ? -1 : cr_rig_find(rig, id);
    }
    return cr_rig_find(rig, ref);
}

}  // namespace

extern "C" {

// ---- record ------------------------------------------------------------------------------------------

cc_look* cc_look_create(void) {
    auto* l = new (std::nothrow) cc_look();
    if (l == nullptr) error(CC_ERR_ARG, "cc_look_create: out of memory");
    return l;
}

cc_look* cc_look_clone(const cc_look* look) {
    if (look == nullptr) {
        error(CC_ERR_ARG, "cc_look_clone: null look");
        return nullptr;
    }
    auto* l = new (std::nothrow) cc_look();
    if (l != nullptr) l->look = look->look;
    return l;
}

void cc_look_destroy(cc_look* look) { delete look; }

cc_look* cc_look_read(const uint8_t* bytes, int64_t length, int32_t* out_result) {
    ch::set_out(out_result, static_cast<int32_t>(CC_OK));
    if (bytes == nullptr || length <= 0) {
        ch::set_out(out_result, static_cast<int32_t>(CC_ERR_ARG));
        error(CC_ERR_ARG, "cc_look_read: no bytes");
        return nullptr;
    }
    try {
        auto* l = new cc_look();
        try {
            l->look = ol::read(std::span<const std::byte>(reinterpret_cast<const std::byte*>(bytes), static_cast<size_t>(length)));
        } catch (...) {
            delete l;
            throw;
        }
        ch::clear_error();
        return l;
    } catch (const ol::version_error& e) {
        ch::set_out(out_result, static_cast<int32_t>(CC_ERR_VERSION));
        error(CC_ERR_VERSION, e.what());
    } catch (const std::exception& e) {
        ch::set_out(out_result, static_cast<int32_t>(CC_ERR_FORMAT));
        error(CC_ERR_FORMAT, e.what());
    }
    return nullptr;
}

cc_bytes* cc_look_write_json(const cc_look* look) {
    if (look == nullptr) {
        error(CC_ERR_ARG, "cc_look_write_json: null look");
        return nullptr;
    }
    try {
        return to_bytes(ol::write_json(look->look));
    } catch (const std::exception& e) {
        error(CC_ERR_FORMAT, e.what());
        return nullptr;
    }
}

cc_bytes* cc_look_write_binary(const cc_look* look) {
    if (look == nullptr) {
        error(CC_ERR_ARG, "cc_look_write_binary: null look");
        return nullptr;
    }
    try {
        return to_bytes(ol::write_binary(look->look));
    } catch (const std::exception& e) {
        error(CC_ERR_FORMAT, e.what());
        return nullptr;
    }
}

const char* cc_look_character(const cc_look* look) { return look == nullptr ? "" : look->look.character.c_str(); }

int32_t cc_look_set_character(cc_look* look, const char* object_id) {
    if (look == nullptr) return error(CC_ERR_ARG, "cc_look_set_character: null look");
    look->look.character = object_id == nullptr ? "" : object_id;
    return CC_OK;
}

int32_t cc_look_count(const cc_look* look) { return look == nullptr ? 0 : static_cast<int32_t>(look->look.entries.size()); }

int32_t cc_look_find(const cc_look* look, const char* name) {
    return look == nullptr || name == nullptr ? -1 : look->look.find(name);
}

int32_t cc_look_get(const cc_look* look, int32_t index, const char** out_name, int32_t* out_type, float* out_color,
                    int32_t* out_flag, float* out_number, const char** out_text) {
    if (look == nullptr) return error(CC_ERR_ARG, "cc_look_get: null look");
    if (!ch::in_range(look->look.entries, index)) return error(CC_ERR_RANGE, "cc_look_get: index out of range");
    const ol::Entry& e = look->look.entries[static_cast<size_t>(index)];
    ch::set_out(out_name, e.name.c_str());
    ch::set_out(out_type, static_cast<int32_t>(e.type));
    if (out_color != nullptr) {
        for (int i = 0; i < 3; ++i) out_color[i] = e.type == ol::Color ? static_cast<float>(e.rgb[i]) / 255.f : 0.f;
    }
    ch::set_out(out_flag, static_cast<int32_t>(e.type == ol::Bool && e.flag ? 1 : 0));
    ch::set_out(out_number, e.type == ol::Number ? e.number : 0.f);
    if (out_text != nullptr) {
        if (e.type == ol::String || e.type == ol::Json) {
            *out_text = e.text.c_str();
        } else if (e.type > ol::Json) {
            look->hex.resize(look->look.entries.size());
            std::string& h = look->hex[static_cast<size_t>(index)];
            static const char* digits = "0123456789abcdef";
            h.clear();
            for (uint8_t b : e.raw) {
                h += digits[b >> 4];
                h += digits[b & 15];
            }
            *out_text = h.c_str();
        } else {
            *out_text = "";
        }
    }
    return CC_OK;
}

int32_t cc_look_set_color(cc_look* look, const char* name, const float* rgb) {
    if (look == nullptr || !ch::valid_key(name) || rgb == nullptr) return error(CC_ERR_ARG, "cc_look_set_color: bad arguments");
    ol::Entry& e = upsert(look->look, name);
    e.type = ol::Color;
    set_rgb(e, rgb);
    return CC_OK;
}

int32_t cc_look_set_bool(cc_look* look, const char* name, int32_t value) {
    if (look == nullptr || !ch::valid_key(name)) return error(CC_ERR_ARG, "cc_look_set_bool: bad arguments");
    ol::Entry& e = upsert(look->look, name);
    e.type = ol::Bool;
    e.flag = value != 0;
    return CC_OK;
}

int32_t cc_look_set_string(cc_look* look, const char* name, const char* value) {
    if (look == nullptr || !ch::valid_key(name) || value == nullptr) return error(CC_ERR_ARG, "cc_look_set_string: bad arguments");
    if (is_color_text(value)) return error(CC_ERR_ARG, "cc_look_set_string: \"#rrggbb\" is a colour; use cc_look_set_color");
    ol::Entry& e = upsert(look->look, name);
    e.type = ol::String;
    e.text = value;
    return CC_OK;
}

int32_t cc_look_set_number(cc_look* look, const char* name, float value) {
    if (look == nullptr || !ch::valid_key(name) || !std::isfinite(value)) return error(CC_ERR_ARG, "cc_look_set_number: bad arguments");
    ol::Entry& e = upsert(look->look, name);
    e.type = ol::Number;
    e.number = value;
    return CC_OK;
}

int32_t cc_look_set_json(cc_look* look, const char* name, const char* json) {
    if (look == nullptr || !ch::valid_key(name) || json == nullptr) return error(CC_ERR_ARG, "cc_look_set_json: bad arguments");
    try {
        std::string text = ol::minify_json_value(json);
        ol::Entry& e = upsert(look->look, name);
        e.type = ol::Json;
        e.text = std::move(text);
        return CC_OK;
    } catch (const std::exception& e) {
        return error(CC_ERR_ARG, std::string("cc_look_set_json: ") + e.what());
    }
}

int32_t cc_look_remove(cc_look* look, const char* name) {
    if (look == nullptr || name == nullptr) return error(CC_ERR_ARG, "cc_look_remove: bad arguments");
    const int i = look->look.find(name);
    if (i >= 0) look->look.entries.erase(look->look.entries.begin() + i);
    return CC_OK;
}

int32_t cc_look_equal(const cc_look* a, const cc_look* b) {
    if (a == nullptr || b == nullptr) return 0;
    if (a->look.character != b->look.character || a->look.entries.size() != b->look.entries.size()) return 0;
    for (const ol::Entry& e : a->look.entries) {
        const int j = b->look.find(e.name);
        if (j < 0 || !e.same_value(b->look.entries[static_cast<size_t>(j)])) return 0;
    }
    return 1;
}

cc_look* cc_look_diff(const cc_look* from, const cc_look* to) {
    if (from == nullptr || to == nullptr) {
        error(CC_ERR_ARG, "cc_look_diff: null look");
        return nullptr;
    }
    auto* p = new (std::nothrow) cc_look();
    if (p == nullptr) return nullptr;
    if (from->look.character != to->look.character) p->look.character = to->look.character;
    for (const ol::Entry& e : to->look.entries) {
        const int j = from->look.find(e.name);
        if (j < 0 || !e.same_value(from->look.entries[static_cast<size_t>(j)])) p->look.entries.push_back(e);
    }
    for (const ol::Entry& e : from->look.entries) {
        if (to->look.find(e.name) < 0) {
            ol::Entry r;
            r.name = e.name;
            r.type = ol::Remove;
            p->look.entries.push_back(std::move(r));
        }
    }
    return p;
}

int32_t cc_look_apply(cc_look* look, const cc_look* patch) {
    if (look == nullptr || patch == nullptr) return error(CC_ERR_ARG, "cc_look_apply: null look");
    if (!patch->look.character.empty()) look->look.character = patch->look.character;
    for (const ol::Entry& e : patch->look.entries) {
        const int j = look->look.find(e.name);
        if (e.type == ol::Remove) {
            if (j >= 0) look->look.entries.erase(look->look.entries.begin() + j);
        } else if (j >= 0) {
            look->look.entries[static_cast<size_t>(j)] = e;
        } else {
            look->look.entries.push_back(e);
        }
    }
    return CC_OK;
}

// ---- garments ----------------------------------------------------------------------------------------

cc_garment* cc_garment_create(const cc_char* target, const cr_rig* target_rig, const cc_garment_desc* d,
                              int32_t* out_unresolved) {
    ch::set_out(out_unresolved, 0);
    if (target == nullptr || target_rig == nullptr || d == nullptr || d->ref_count <= 0 || d->joint_refs == nullptr ||
        d->vertex_count < 0 || (d->vertex_count > 0 && d->positions == nullptr) || d->skin == nullptr ||
        cr_skin_vertex_count(d->skin) != d->vertex_count || (d->source != nullptr) != (d->source_rig != nullptr)) {
        error(CC_ERR_ARG, "cc_garment_create: bad arguments");
        return nullptr;
    }
    try {
        const cc_char* src_chr = d->source != nullptr ? d->source : target;
        const cr_rig* src_rig = d->source != nullptr ? d->source_rig : target_rig;
        const bool same = src_chr == target && src_rig == target_rig;
        const int32_t refs = d->ref_count;

        // Ref -> source joint.
        std::vector<int32_t> src_joint(static_cast<size_t>(refs), -1);
        for (int32_t r = 0; r < refs; ++r) src_joint[static_cast<size_t>(r)] = resolve_ref(src_rig, src_chr, d->joint_refs[r]);

        RefSide A, B;
        if (!same) {
            A.init(src_rig, src_chr);
            B.init(target_rig, target);
        }
        // Source joint -> target joint: itself (same character), else its role, else its name, else the nearest
        // mapped ancestor.
        auto direct = [&](int j) -> int32_t {
            if (j < 0) return -1;
            if (same) return j;
            const std::string role = A.role_of(j);
            if (!role.empty()) {
                const char* id = cc_char_role_joint(target, role.c_str());
                const int32_t t = id == nullptr ? -1 : cr_rig_find(target_rig, id);
                if (t >= 0) return t;
            }
            const std::string& name = src_rig->joints[static_cast<size_t>(j)].name;
            return name.empty() ? -1 : cr_rig_find(target_rig, name.c_str());
        };
        std::vector<uint8_t> exact(static_cast<size_t>(refs), 0);
        auto mapped = [&](int j, bool* is_exact) -> int32_t {
            *is_exact = true;
            for (int a = j; a >= 0; a = src_rig->joints[static_cast<size_t>(a)].parent) {
                const int32_t t = direct(a);
                if (t >= 0) return t;
                *is_exact = false;
            }
            return -1;
        };
        auto* g = new cc_garment();
        std::unique_ptr<cc_garment> guard(g);
        g->joint_of_ref.assign(static_cast<size_t>(refs), -1);
        int32_t unresolved = 0;
        for (int32_t r = 0; r < refs; ++r) {
            bool is_exact = false;
            const int32_t t = mapped(src_joint[static_cast<size_t>(r)], &is_exact);
            if (t < 0 || !is_exact) ++unresolved;
            g->joint_of_ref[static_cast<size_t>(r)] = t;
        }
        // Unresolved refs fall back to ref 0's joint, then the target's first root.
        int32_t fallback = g->joint_of_ref[0];
        if (fallback < 0) fallback = target_rig->order.empty() ? 0 : target_rig->order[0];
        g->fallback = fallback;
        std::vector<int32_t> map(static_cast<size_t>(refs));
        for (int32_t r = 0; r < refs; ++r) {
            const int32_t t = g->joint_of_ref[static_cast<size_t>(r)];
            map[static_cast<size_t>(r)] = t >= 0 ? t : fallback;
        }

        // Positions: the same character keeps them; another is fitted through both reference poses.
        const int32_t n = d->vertex_count;
        g->positions.assign(d->positions, d->positions + static_cast<size_t>(n) * 3);
        if (!same) {
            // Per source joint: the move from A's reference pose onto B's (profile frame, bone-aligned scale).
            const double k = B.size / A.size;
            struct Move {
                V3 pa, pb, axis;
                Quat r;
                double along = 1.0;
                bool has_axis = false;
            };
            std::unordered_map<int, Move> moves;
            auto move_of = [&](int j) -> const Move& {
                auto it = moves.find(j);
                if (it != moves.end()) return it->second;
                Move m;
                bool unused = false;
                const int t = mapped(j, &unused);
                m.pa = A.point(j);
                m.pb = t >= 0 ? B.point(t) : m.pa;
                const V3 da = A.direction(j), db = t >= 0 ? B.direction(t) : V3{};
                if (length(da) > EPS && length(db) > EPS) {
                    // A model -> profile -> turn onto B's bone -> B model.
                    const Quat turn = from_to(rotate(A.F, da), rotate(B.F, db));
                    m.r = cenda::rig::qmul(cenda::rig::qconj(B.F), cenda::rig::qmul(turn, A.F));
                    m.axis = da * (1.0 / length(da));
                    m.along = length(db) / length(da);
                    m.has_axis = true;
                } else {
                    m.r = cenda::rig::qmul(cenda::rig::qconj(B.F), A.F);
                }
                return moves.emplace(j, m).first->second;
            };
            int32_t joints[CR_MAX_INFLUENCES];
            float weights[CR_MAX_INFLUENCES];
            for (int32_t v = 0; v < n; ++v) {
                int32_t count = cr_skin_get(d->skin, v, joints, weights);
                if (count <= 0) {
                    joints[0] = 0;
                    weights[0] = 1.f;
                    count = 1;
                }
                const float* p = d->positions + static_cast<size_t>(v) * 3;
                const V3 rest{p[0], p[1], p[2]};
                V3 aref, bref;
                Mat4 kb{};
                double wsum = 0.0;
                for (int i = 0; i < count; ++i) {
                    const int r = joints[i];
                    if (r < 0 || r >= refs) continue;
                    int sj = src_joint[static_cast<size_t>(r)];
                    if (sj < 0) continue;
                    wsum += weights[i];
                }
                if (wsum <= 0.0) continue;  // nothing to fit with: keep the source position
                for (int i = 0; i < count; ++i) {
                    const int r = joints[i];
                    if (r < 0 || r >= refs || src_joint[static_cast<size_t>(r)] < 0) continue;
                    aref = aref + apply(A.skin[static_cast<size_t>(src_joint[static_cast<size_t>(r)])], rest) * (weights[i] / wsum);
                }
                for (int i = 0; i < count; ++i) {
                    const int r = joints[i];
                    if (r < 0 || r >= refs || src_joint[static_cast<size_t>(r)] < 0) continue;
                    const double w = weights[i] / wsum;
                    const Move& m = move_of(src_joint[static_cast<size_t>(r)]);
                    V3 x = aref - m.pa;
                    if (m.has_axis) {
                        const V3 par = m.axis * dot(x, m.axis);
                        x = par * m.along + (x - par) * k;
                    } else {
                        x = x * k;
                    }
                    bref = bref + (m.pb + rotate(m.r, x)) * w;
                    const Mat4& kbj = B.skin[static_cast<size_t>(map[static_cast<size_t>(r)])];
                    for (int c = 0; c < 16; ++c) kb.m[c] += kbj.m[c] * w;
                }
                const V3 out = apply(kb.affine_inverse(), bref);
                float* q = g->positions.data() + static_cast<size_t>(v) * 3;
                q[0] = static_cast<float>(out.x);
                q[1] = static_cast<float>(out.y);
                q[2] = static_cast<float>(out.z);
            }
        }

        g->skin = cr_skin_clone(d->skin);
        if (g->skin == nullptr || cr_skin_remap_joints(g->skin, map.data(), refs) != CR_OK) {
            error(CC_ERR_ARG, std::string("cc_garment_create: ") + cr_last_error());
            return nullptr;
        }
        ch::set_out(out_unresolved, unresolved);
        ch::clear_error();
        return guard.release();
    } catch (const std::exception& e) {
        error(CC_ERR_ARG, std::string("cc_garment_create: ") + e.what());
        return nullptr;
    }
}

void cc_garment_destroy(cc_garment* g) { delete g; }

int32_t cc_garment_vertex_count(const cc_garment* g) {
    return g == nullptr ? 0 : static_cast<int32_t>(g->positions.size() / 3);
}

int32_t cc_garment_positions(const cc_garment* g, float* out) {
    if (g == nullptr || out == nullptr) return error(CC_ERR_ARG, "cc_garment_positions: bad arguments");
    std::memcpy(out, g->positions.data(), g->positions.size() * sizeof(float));
    return CC_OK;
}

const cr_skin* cc_garment_skin(const cc_garment* g) { return g == nullptr ? nullptr : g->skin; }

int32_t cc_garment_pack(const cc_garment* g, uint16_t* out_joints, uint8_t* out_weights) {
    if (g == nullptr || out_joints == nullptr || out_weights == nullptr) return error(CC_ERR_ARG, "cc_garment_pack: bad arguments");
    std::vector<int32_t> rigid(g->positions.size() / 3, g->fallback);
    if (cr_skin_pack(g->skin, rigid.data(), out_joints, out_weights) != CR_OK) {
        return error(CC_ERR_ARG, std::string("cc_garment_pack: ") + cr_last_error());
    }
    return CC_OK;
}

int32_t cc_garment_joint(const cc_garment* g, int32_t ref) {
    if (g == nullptr || ref < 0 || static_cast<size_t>(ref) >= g->joint_of_ref.size()) return -1;
    return g->joint_of_ref[static_cast<size_t>(ref)];
}

// ---- resolution --------------------------------------------------------------------------------------

cc_appearance* cc_appearance_create(const cc_char* c, const cr_rig* rig, int32_t* out_result, int32_t* out_unresolved) {
    ch::set_out(out_result, static_cast<int32_t>(CC_OK));
    ch::set_out(out_unresolved, 0);
    if (c == nullptr || rig == nullptr) {
        ch::set_out(out_result, static_cast<int32_t>(CC_ERR_ARG));
        error(CC_ERR_ARG, "cc_appearance_create: null character or rig");
        return nullptr;
    }
    try {
        auto a = std::make_unique<cc_appearance>();
        a->chr = c;
        a->rig = rig;
        try {
            a->sec = oa::parse(c->def.appearance_json);
        } catch (const std::exception& e) {
            ch::set_out(out_result, static_cast<int32_t>(CC_ERR_FORMAT));
            error(CC_ERR_FORMAT, e.what());
            return nullptr;
        }
        int32_t unresolved = 0;
        const oa::Section& s = a->sec;
        for (size_t i = 0; i < s.tints.size(); ++i) a->slots.push_back({s.tints[i].name, CC_SLOT_TINT, CC_LOOK_COLOR, static_cast<int>(i)});
        for (size_t i = 0; i < s.groups.size(); ++i) a->slots.push_back({s.groups[i].name, CC_SLOT_GROUP, CC_LOOK_BOOL, static_cast<int>(i)});
        if (!s.bodies.empty()) a->slots.push_back({oa::kBodySlot, CC_SLOT_BODY, CC_LOOK_STRING, 0});
        for (size_t i = 0; i < s.equipment.size(); ++i) a->slots.push_back({s.equipment[i].name, CC_SLOT_EQUIPMENT, CC_LOOK_STRING, static_cast<int>(i)});
        for (const oa::Group& g : s.groups) {
            std::vector<int32_t> joints;
            for (const std::string& id : g.parts) {
                const int32_t j = ch::rig_index(rig, id);
                if (j < 0) {
                    ++unresolved;
                } else {
                    joints.push_back(j);
                }
            }
            a->group_joints.push_back(std::move(joints));
        }
        for (const oa::Equipment& e : s.equipment) {
            int32_t joint = -1;
            Mat4 m = Mat4::identity();
            if (!e.socket.empty()) {
                bool found = false;
                for (const auto& so : c->def.sockets) {
                    if (so.name != e.socket) continue;
                    joint = ch::rig_index(rig, so.joint);
                    found = joint >= 0;
                    double r[9];
                    cenda::rig::rot_xyz(so.rot.x * cenda::rig::DEG_TO_RAD, so.rot.y * cenda::rig::DEG_TO_RAD,
                                        so.rot.z * cenda::rig::DEG_TO_RAD, r);
                    const double sc[3] = {so.scale.x, so.scale.y, so.scale.z};
                    for (int row = 0; row < 3; ++row) {
                        for (int col = 0; col < 3; ++col) m.at(row, col) = r[row * 3 + col] * sc[col];
                    }
                    m.at(0, 3) = so.pos.x;
                    m.at(1, 3) = so.pos.y;
                    m.at(2, 3) = so.pos.z;
                    break;
                }
                if (!found) ++unresolved;
            }
            a->slot_socket_joint.push_back(joint);
            a->slot_socket.push_back(m);
        }
        for (const oa::MaterialDye& md : s.materials) {
            if (!md.mask.empty() && cc_char_entry_find(c, md.mask.c_str()) < 0) ++unresolved;
        }
        for (const oa::Body& b : s.bodies) {
            if (cc_char_entry_find(c, b.model.c_str()) < 0 && b.model != c->def.model_entry) ++unresolved;
        }
        ch::set_out(out_unresolved, unresolved);
        cc_look* defaults = cc_look_create();
        std::unique_ptr<cc_look, void (*)(cc_look*)> dg(defaults, cc_look_destroy);
        cc_appearance_defaults(a.get(), defaults);
        cc_appearance_resolve(a.get(), defaults);
        ch::clear_error();
        return a.release();
    } catch (const std::exception& e) {
        ch::set_out(out_result, static_cast<int32_t>(CC_ERR_ARG));
        error(CC_ERR_ARG, std::string("cc_appearance_create: ") + e.what());
        return nullptr;
    }
}

void cc_appearance_destroy(cc_appearance* a) { delete a; }

int32_t cc_appearance_slot_count(const cc_appearance* a) { return a == nullptr ? 0 : static_cast<int32_t>(a->slots.size()); }

int32_t cc_appearance_slot(const cc_appearance* a, int32_t index, const char** out_name, int32_t* out_kind, int32_t* out_type) {
    if (a == nullptr) return error(CC_ERR_ARG, "cc_appearance_slot: null appearance");
    if (!ch::in_range(a->slots, index)) return error(CC_ERR_RANGE, "cc_appearance_slot: index out of range");
    const auto& s = a->slots[static_cast<size_t>(index)];
    ch::set_out(out_name, s.name.c_str());
    ch::set_out(out_kind, s.kind);
    ch::set_out(out_type, s.type);
    return CC_OK;
}

int32_t cc_appearance_defaults(const cc_appearance* a, cc_look* out) {
    if (a == nullptr || out == nullptr) return error(CC_ERR_ARG, "cc_appearance_defaults: bad arguments");
    out->look.entries.clear();
    out->look.character = a->chr->def.object_id;
    for (const auto& s : a->slots) {
        ol::Entry e;
        e.name = s.name;
        switch (s.kind) {
            case CC_SLOT_TINT:
                e.type = ol::Color;
                std::memcpy(e.rgb, a->sec.tints[static_cast<size_t>(s.index)].rgb, 3);
                break;
            case CC_SLOT_GROUP:
                e.type = ol::Bool;
                e.flag = a->sec.groups[static_cast<size_t>(s.index)].visible;
                break;
            case CC_SLOT_BODY:
                e.type = ol::String;
                e.text = a->sec.bodies.front().name;
                break;
            default:
                e.type = ol::String;
                break;
        }
        out->look.entries.push_back(std::move(e));
    }
    return CC_OK;
}

int32_t cc_appearance_add_item(cc_appearance* a, const cc_item_desc* item) {
    if (a == nullptr || item == nullptr || !ch::valid_key(item->id) || !ch::valid_key(item->slot) ||
        (item->kind != CC_ITEM_RIGID && item->kind != CC_ITEM_SKINNED) || item->hide_count < 0 ||
        (item->hide_count > 0 && item->hides == nullptr)) {
        return error(CC_ERR_ARG, "cc_appearance_add_item: bad arguments");
    }
    int slot = -1;
    for (size_t i = 0; i < a->sec.equipment.size(); ++i) {
        if (a->sec.equipment[i].name == item->slot) slot = static_cast<int>(i);
    }
    if (slot < 0) return error(CC_ERR_RANGE, std::string("cc_appearance_add_item: no equipment slot ") + item->slot);
    cc_appearance::Item it;
    it.id = item->id;
    it.slot = item->slot;
    it.slot_index = slot;
    it.kind = item->kind;
    for (const std::string& h : a->sec.equipment[static_cast<size_t>(slot)].hides) it.hides.push_back(a->sec.group(h));
    for (int32_t i = 0; i < item->hide_count; ++i) {
        const int g = item->hides[i] == nullptr ? -1 : a->sec.group(item->hides[i]);
        if (g < 0) return error(CC_ERR_RANGE, "cc_appearance_add_item: unknown group in hides");
        it.hides.push_back(g);
    }
    Mat4 identity = Mat4::identity();
    identity.to_float(it.matrix);
    if (item->kind == CC_ITEM_RIGID) {
        int32_t joint = a->slot_socket_joint[static_cast<size_t>(slot)];
        Mat4 socket = a->slot_socket[static_cast<size_t>(slot)];
        if (ch::valid_key(item->socket)) {
            joint = -1;
            for (const auto& so : a->chr->def.sockets) {
                if (so.name != item->socket) continue;
                joint = ch::rig_index(a->rig, so.joint);
                double r[9];
                cenda::rig::rot_xyz(so.rot.x * cenda::rig::DEG_TO_RAD, so.rot.y * cenda::rig::DEG_TO_RAD,
                                    so.rot.z * cenda::rig::DEG_TO_RAD, r);
                socket = Mat4::identity();
                const double sc[3] = {so.scale.x, so.scale.y, so.scale.z};
                for (int row = 0; row < 3; ++row) {
                    for (int col = 0; col < 3; ++col) socket.at(row, col) = r[row * 3 + col] * sc[col];
                }
                socket.at(0, 3) = so.pos.x;
                socket.at(1, 3) = so.pos.y;
                socket.at(2, 3) = so.pos.z;
                break;
            }
        }
        if (joint < 0) return error(CC_ERR_RANGE, "cc_appearance_add_item: a rigid item needs a socket the rig has");
        for (int c = 0; c < 3; ++c) {
            if (!std::isfinite(item->pos[c]) || !std::isfinite(item->rot[c]) || !std::isfinite(item->scale[c])) {
                return error(CC_ERR_ARG, "cc_appearance_add_item: non-finite offset");
            }
        }
        double r[9];
        cenda::rig::rot_xyz(item->rot[0] * cenda::rig::DEG_TO_RAD, item->rot[1] * cenda::rig::DEG_TO_RAD,
                            item->rot[2] * cenda::rig::DEG_TO_RAD, r);
        Mat4 off = Mat4::identity();
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 3; ++col) {
                const float s = item->scale[col] == 0.f ? 1.f : item->scale[col];
                off.at(row, col) = r[row * 3 + col] * s;
            }
        }
        off.at(0, 3) = item->pos[0];
        off.at(1, 3) = item->pos[1];
        off.at(2, 3) = item->pos[2];
        (socket * off).to_float(it.matrix);
        it.joint = joint;
    }
    for (size_t i = 0; i < a->items.size(); ++i) {
        if (a->items[i].id == it.id) {
            a->items[i] = std::move(it);
            return static_cast<int32_t>(i);
        }
    }
    a->items.push_back(std::move(it));
    return static_cast<int32_t>(a->items.size() - 1);
}

int32_t cc_appearance_item_count(const cc_appearance* a) { return a == nullptr ? 0 : static_cast<int32_t>(a->items.size()); }

int32_t cc_appearance_resolve(cc_appearance* a, const cc_look* look) {
    if (a == nullptr || look == nullptr) return error(CC_ERR_ARG, "cc_appearance_resolve: bad arguments");
    const oa::Section& s = a->sec;
    a->body = s.bodies.empty() ? -1 : 0;
    a->group_on.assign(s.groups.size(), 0);
    for (size_t i = 0; i < s.groups.size(); ++i) a->group_on[i] = s.groups[i].visible ? 1 : 0;
    a->tint_rgb.assign(s.tints.size(), {});
    for (size_t i = 0; i < s.tints.size(); ++i) std::memcpy(a->tint_rgb[i].data(), s.tints[i].rgb, 3);
    a->equipped.assign(s.equipment.size(), -1);
    int32_t fallbacks = 0;
    for (const auto& slot : a->slots) {
        const int i = look->look.find(slot.name);
        if (i < 0) continue;
        const ol::Entry& e = look->look.entries[static_cast<size_t>(i)];
        if (e.type == ol::Remove) continue;
        const size_t k = static_cast<size_t>(slot.index);
        switch (slot.kind) {
            case CC_SLOT_TINT:
                if (e.type == ol::Color) {
                    std::memcpy(a->tint_rgb[k].data(), e.rgb, 3);
                } else {
                    ++fallbacks;
                }
                break;
            case CC_SLOT_GROUP:
                if (e.type == ol::Bool) {
                    a->group_on[k] = e.flag ? 1 : 0;
                } else {
                    ++fallbacks;
                }
                break;
            case CC_SLOT_BODY: {
                int found = -1;
                if (e.type == ol::String) {
                    for (size_t b = 0; b < s.bodies.size(); ++b) {
                        if (s.bodies[b].name == e.text) found = static_cast<int>(b);
                    }
                }
                if (found >= 0) {
                    a->body = found;
                } else {
                    ++fallbacks;
                }
                break;
            }
            default: {
                if (e.type != ol::String) {
                    ++fallbacks;
                    break;
                }
                if (e.text.empty()) break;
                int found = -1;
                for (size_t it = 0; it < a->items.size(); ++it) {
                    if (a->items[it].id == e.text && a->items[it].slot_index == slot.index) found = static_cast<int>(it);
                }
                if (found >= 0) {
                    a->equipped[k] = found;
                } else {
                    ++fallbacks;
                }
                break;
            }
        }
    }
    // Materials.
    a->materials.clear();
    for (const oa::MaterialDye& md : s.materials) {
        cc_appearance::Material m;
        m.id = md.id;
        m.mask = md.mask;
        for (float& t : m.tints) t = 1.f;
        auto put = [&](int channel, const std::string& tint) {
            const int t = s.tint(tint);
            if (t < 0) return;
            for (int c = 0; c < 3; ++c) m.tints[channel * 3 + c] = static_cast<float>(a->tint_rgb[static_cast<size_t>(t)][static_cast<size_t>(c)]) / 255.f;
        };
        if (md.mask.empty()) {
            put(0, md.tint);
        } else {
            for (int ch2 = 0; ch2 < 4; ++ch2) {
                if (!md.channels[ch2].empty()) put(ch2, md.channels[ch2]);
            }
        }
        a->materials.push_back(std::move(m));
    }
    a->equipped_list.clear();
    for (size_t k = 0; k < a->equipped.size(); ++k) {
        if (a->equipped[k] >= 0) a->equipped_list.push_back({static_cast<int>(k), a->equipped[k]});
    }
    return fallbacks;
}

int32_t cc_appearance_body(const cc_appearance* a, const char** out_name, const char** out_model) {
    if (a == nullptr) return error(CC_ERR_ARG, "cc_appearance_body: null appearance");
    if (a->body < 0) {
        ch::set_out(out_name, "");
        ch::set_out(out_model, "");
    } else {
        const oa::Body& b = a->sec.bodies[static_cast<size_t>(a->body)];
        ch::set_out(out_name, b.name.c_str());
        ch::set_out(out_model, b.model.c_str());
    }
    return CC_OK;
}

int32_t cc_appearance_visible(const cc_appearance* a, const cr_mask* view, uint64_t* out_bits, int32_t words) {
    if (a == nullptr || out_bits == nullptr) return error(CC_ERR_ARG, "cc_appearance_visible: bad arguments");
    const size_t n = a->rig->joints.size();
    if (static_cast<size_t>(words) < (n + 63) / 64) return error(CC_ERR_ARG, "cc_appearance_visible: too few words");
    std::vector<uint8_t> vis(n, 1);
    for (size_t j = 0; j < n; ++j) {
        if ((a->rig->joints[j].flags & CR_JOINT_HIDDEN) != 0u) vis[j] = 0;
    }
    std::vector<uint8_t> hidden_group(a->group_on.size(), 0);
    for (size_t g = 0; g < a->group_on.size(); ++g) hidden_group[g] = a->group_on[g] == 0 ? 1 : 0;
    for (const auto& eq : a->equipped_list) {
        for (int g : a->items[static_cast<size_t>(eq.item)].hides) {
            if (g >= 0) hidden_group[static_cast<size_t>(g)] = 1;
        }
    }
    for (size_t g = 0; g < hidden_group.size(); ++g) {
        if (hidden_group[g] == 0) continue;
        for (int32_t j : a->group_joints[g]) vis[static_cast<size_t>(j)] = 0;
    }
    if (view != nullptr) {
        if (static_cast<size_t>(cr_mask_joint_count(view)) != n) return error(CC_ERR_ARG, "cc_appearance_visible: view mask of another rig");
        std::vector<float> w(n);
        cr_mask_get(view, w.data());
        for (size_t j = 0; j < n; ++j) {
            if (w[j] <= 0.f) vis[j] = 0;
        }
    }
    std::fill(out_bits, out_bits + words, 0ull);
    for (size_t j = 0; j < n; ++j) {
        if (vis[j] != 0) out_bits[j / 64] |= 1ull << (j % 64);
    }
    return CC_OK;
}

int32_t cc_appearance_material_count(const cc_appearance* a) {
    return a == nullptr ? 0 : static_cast<int32_t>(a->materials.size());
}

int32_t cc_appearance_material(const cc_appearance* a, int32_t index, int32_t* out_material_id, const char** out_mask,
                               float* out_tints) {
    if (a == nullptr) return error(CC_ERR_ARG, "cc_appearance_material: null appearance");
    if (!ch::in_range(a->materials, index)) return error(CC_ERR_RANGE, "cc_appearance_material: index out of range");
    const auto& m = a->materials[static_cast<size_t>(index)];
    ch::set_out(out_material_id, m.id);
    ch::set_out(out_mask, m.mask.c_str());
    if (out_tints != nullptr) std::memcpy(out_tints, m.tints, sizeof m.tints);
    return CC_OK;
}

int32_t cc_appearance_equipped_count(const cc_appearance* a) {
    return a == nullptr ? 0 : static_cast<int32_t>(a->equipped_list.size());
}

int32_t cc_appearance_equipped(const cc_appearance* a, int32_t index, const char** out_slot, int32_t* out_item,
                               int32_t* out_kind, int32_t* out_joint, float* out_matrix) {
    if (a == nullptr) return error(CC_ERR_ARG, "cc_appearance_equipped: null appearance");
    if (!ch::in_range(a->equipped_list, index)) return error(CC_ERR_RANGE, "cc_appearance_equipped: index out of range");
    const auto& eq = a->equipped_list[static_cast<size_t>(index)];
    const auto& it = a->items[static_cast<size_t>(eq.item)];
    ch::set_out(out_slot, a->sec.equipment[static_cast<size_t>(eq.slot)].name.c_str());
    ch::set_out(out_item, eq.item);
    ch::set_out(out_kind, it.kind);
    ch::set_out(out_joint, it.joint);
    if (out_matrix != nullptr) std::memcpy(out_matrix, it.matrix, sizeof it.matrix);
    return CC_OK;
}

}  // extern "C"
