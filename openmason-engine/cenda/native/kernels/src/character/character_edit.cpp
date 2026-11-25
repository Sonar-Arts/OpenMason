// Cenda character editing (OpenMason #163): the edit session (undo / redo), role states, the profile body
// diagram, reference locals, standard sockets and the viewport overlay. See cenda/character_edit.h. Named
// reference poses and mirroring live in characterize.cpp (they need its skeleton analysis).
#include "cenda/character_edit.h"

#include "character_internal.hpp"

#include <array>
#include <cstring>
#include <limits>
#include <memory>
#include <numbers>
#include <unordered_map>

using namespace cenda::character;

namespace {

// ---------------------------------------------------------------------------------------------------------
// States.

// The parts of a definition that rarely change and may be large, shared between states while equal.
struct Blobs {
    std::string model_entry;
    std::vector<std::byte> model;
    std::vector<cenda::omformat::ZipEntry> entries;

    [[nodiscard]] bool same(const oc::Character& c) const {
        if (model_entry != c.model_entry || model != c.model || entries.size() != c.unknown_entries.size()) return false;
        for (std::size_t i = 0; i < entries.size(); ++i) {
            const auto& a = entries[i];
            const auto& b = c.unknown_entries[i];
            if (a.name != b.name || a.data != b.data || a.precompressed != b.precompressed) return false;
        }
        return true;
    }
};

struct State {
    oc::Character def;  // model / unknown_entries left empty: they are in blobs
    std::shared_ptr<const Blobs> blobs;
};

bool same_vec(const oc::Vec3& a, const oc::Vec3& b) { return a == b; }

// Field-by-field equality of everything but the blobs.
bool same_manifest(const oc::Character& a, const oc::Character& b) {
    if (a.name != b.name || a.object_id != b.object_id || a.profile != b.profile || a.facing != b.facing ||
        a.locked != b.locked || a.anim_graph != b.anim_graph || a.model_ref != b.model_ref || a.joints != b.joints ||
        a.joint_hash != b.joint_hash || a.appearance_json != b.appearance_json) {
        return false;
    }
    if (a.roles.size() != b.roles.size() || a.reference_pose.size() != b.reference_pose.size() ||
        a.limits.size() != b.limits.size() || a.ik_goals.size() != b.ik_goals.size() || a.chains.size() != b.chains.size() ||
        a.sockets.size() != b.sockets.size() || a.unknown_sections.size() != b.unknown_sections.size()) {
        return false;
    }
    for (std::size_t i = 0; i < a.roles.size(); ++i) {
        if (a.roles[i].role != b.roles[i].role || a.roles[i].joint != b.roles[i].joint) return false;
    }
    for (std::size_t i = 0; i < a.reference_pose.size(); ++i) {
        if (a.reference_pose[i].joint != b.reference_pose[i].joint || !same_vec(a.reference_pose[i].rot, b.reference_pose[i].rot)) return false;
    }
    for (std::size_t i = 0; i < a.limits.size(); ++i) {
        const auto& x = a.limits[i];
        const auto& y = b.limits[i];
        if (x.joint != y.joint || !same_vec(x.min, y.min) || !same_vec(x.max, y.max)) return false;
    }
    for (std::size_t i = 0; i < a.ik_goals.size(); ++i) {
        if (a.ik_goals[i].name != b.ik_goals[i].name || a.ik_goals[i].joint != b.ik_goals[i].joint) return false;
    }
    for (std::size_t i = 0; i < a.chains.size(); ++i) {
        const auto& x = a.chains[i];
        const auto& y = b.chains[i];
        if (x.name != y.name || x.start != y.start || x.end != y.end || x.goal != y.goal) return false;
    }
    for (std::size_t i = 0; i < a.sockets.size(); ++i) {
        const auto& x = a.sockets[i];
        const auto& y = b.sockets[i];
        if (x.name != y.name || x.joint != y.joint || !same_vec(x.pos, y.pos) || !same_vec(x.rot, y.rot) ||
            !same_vec(x.scale, y.scale)) {
            return false;
        }
    }
    const auto& m = a.metrics;
    const auto& n = b.metrics;
    if (m.height != n.height || m.eye_height != n.eye_height || m.width != n.width || m.hip_height != n.hip_height ||
        m.overridden != n.overridden) {
        return false;
    }
    for (std::size_t i = 0; i < a.unknown_sections.size(); ++i) {
        if (a.unknown_sections[i].key != b.unknown_sections[i].key || a.unknown_sections[i].json != b.unknown_sections[i].json) {
            return false;
        }
    }
    return true;
}

}  // namespace

struct cc_charedit {
    struct Step {
        State state;
        std::string label;
        std::string merge_key;
    };

    cc_char* c = nullptr;
    int32_t max_steps = 200;
    std::vector<Step> steps;  // steps[0] = the base
    int32_t cursor = 0;       // the step the definition is at
    int32_t saved = 0;        // the step last marked saved, -1 = none still held
    int32_t depth = 0;
    State group_start;
    std::string group_label;
    std::string group_key;
    bool sealed = true;
    int64_t revision = 0;

    // A state of the live definition; the blobs are shared with `like` when unchanged.
    [[nodiscard]] State capture(const State* like) const {
        State s;
        const oc::Character& d = c->def;
        if (like != nullptr && like->blobs && like->blobs->same(d)) {
            s.blobs = like->blobs;
        } else {
            auto b = std::make_shared<Blobs>();
            b->model_entry = d.model_entry;
            b->model = d.model;
            b->entries = d.unknown_entries;
            s.blobs = std::move(b);
        }
        s.def = d;
        s.def.model.clear();
        s.def.model.shrink_to_fit();
        s.def.unknown_entries.clear();
        s.def.unknown_entries.shrink_to_fit();
        return s;
    }

    [[nodiscard]] bool live_equals(const State& s) const {
        return same_manifest(c->def, s.def) && s.blobs && s.blobs->same(c->def);
    }

    void restore(const State& s) {
        oc::Character d = s.def;
        d.model_entry = s.blobs->model_entry;
        d.model = s.blobs->model;
        d.unknown_entries = s.blobs->entries;
        c->def = std::move(d);
    }

    // Pushes the live state as a new step on top of the cursor (drops the redo tail and the oldest steps).
    void push(std::string label, std::string key) {
        State s = capture(&steps[static_cast<std::size_t>(cursor)].state);
        steps.resize(static_cast<std::size_t>(cursor) + 1);
        if (saved > cursor) saved = -1;
        steps.push_back({std::move(s), std::move(label), std::move(key)});
        ++cursor;
        while (static_cast<int32_t>(steps.size()) > max_steps + 1) {
            steps.erase(steps.begin());
            --cursor;
            saved = saved > 0 ? saved - 1 : -1;
        }
        ++revision;
    }

    // Folds edits made outside a group into a step (undo / redo / reset see the recorded state).
    void absorb_stray() {
        if (!live_equals(steps[static_cast<std::size_t>(cursor)].state)) {
            push("edit", {});
            sealed = true;
        }
    }
};

namespace cenda::character {

void reference_locals(const oc::Character& def, const cr_rig* rig, float* out) {
    const std::size_t n = rig->joints.size();
    for (std::size_t i = 0; i < n; ++i) {
        const auto& j = rig->joints[i];
        std::memcpy(out + i * 9, j.pos, sizeof j.pos);
        std::memcpy(out + i * 9 + 3, j.rot, sizeof j.rot);
        std::memcpy(out + i * 9 + 6, j.scale, sizeof j.scale);
    }
    for (const oc::JointRotation& r : def.reference_pose) {
        const int32_t j = rig_index(rig, r.joint);
        if (j < 0) continue;
        float* rot = out + static_cast<std::size_t>(j) * 9 + 3;
        rot[0] = r.rot.x;
        rot[1] = r.rot.y;
        rot[2] = r.rot.z;
    }
}

}  // namespace cenda::character

namespace {

// ---------------------------------------------------------------------------------------------------------
// Small math (column-major float[16] affine matrices, double work).

struct V3 {
    double x = 0, y = 0, z = 0;
    V3 operator+(const V3& o) const { return {x + o.x, y + o.y, z + o.z}; }
    V3 operator-(const V3& o) const { return {x - o.x, y - o.y, z - o.z}; }
    V3 operator*(double s) const { return {x * s, y * s, z * s}; }
    [[nodiscard]] double dot(const V3& o) const { return x * o.x + y * o.y + z * o.z; }
    [[nodiscard]] double length() const { return std::sqrt(dot(*this)); }
};

void mul4(const float* a, const float* b, float* out) {
    float r[16];
    for (int c = 0; c < 4; ++c)
        for (int row = 0; row < 4; ++row) {
            double s = 0;
            for (int k = 0; k < 4; ++k) s += static_cast<double>(a[k * 4 + row]) * b[c * 4 + k];
            r[c * 4 + row] = static_cast<float>(s);
        }
    std::memcpy(out, r, sizeof r);
}

// T(pos) . Rx Ry Rz (degrees) . S(scale), column-major.
void trs(const float* pos, const float* rot_deg, const float* scale, float* out) {
    constexpr double kDeg = std::numbers::pi / 180.0;
    const double a = rot_deg[0] * kDeg, b = rot_deg[1] * kDeg, c = rot_deg[2] * kDeg;
    const double ca = std::cos(a), sa = std::sin(a), cb = std::cos(b), sb = std::sin(b), cc = std::cos(c), sc = std::sin(c);
    const double r[3][3] = {{cb * cc, -cb * sc, sb},
                            {ca * sc + sa * sb * cc, ca * cc - sa * sb * sc, -sa * cb},
                            {sa * sc - ca * sb * cc, sa * cc + ca * sb * sc, ca * cb}};
    for (int col = 0; col < 3; ++col) {
        for (int row = 0; row < 3; ++row) out[col * 4 + row] = static_cast<float>(r[row][col] * scale[col]);
        out[col * 4 + 3] = 0.f;
    }
    out[12] = pos[0];
    out[13] = pos[1];
    out[14] = pos[2];
    out[15] = 1.f;
}

// A joint's point (bone head / part pivot) and tail (bone tail / the point again) from world + child frames.
void joint_head_tail(const cr_rig* rig, int32_t j, const float* world, const float* child, V3& head, V3& tail) {
    double p[3];
    joint_point(rig, j, world, p);
    head = {p[0], p[1], p[2]};
    if (rig->joints[static_cast<std::size_t>(j)].kind == CR_JOINT_BONE && child != nullptr) {
        const float* f = child + static_cast<std::size_t>(j) * 16;
        tail = {f[12], f[13], f[14]};
    } else {
        tail = head;
    }
}

// ---------------------------------------------------------------------------------------------------------
// Standard sockets.

struct StandardSocket {
    const char* name;
    const char* role;
};

constexpr StandardSocket kStandardSockets[] = {
    {"grip_r", "hand_r"}, {"grip_l", "hand_l"}, {"head", "head"}, {"eyes", "head"}, {"back", "upper_chest"}};
constexpr int32_t kStandardCount = static_cast<int32_t>(sizeof kStandardSockets / sizeof kStandardSockets[0]);

int32_t standard_index(const std::string& name) {
    for (int32_t i = 0; i < kStandardCount; ++i) {
        if (name == kStandardSockets[i].name) return i;
    }
    return -1;
}

// The character's forward and left axes from its facing (model frame).
void facing_axes(const oc::Character& def, V3& forward, V3& left) {
    const std::string& f = def.facing;
    forward = f == "+z" ? V3{0, 0, 1} : f == "-x" ? V3{-1, 0, 0} : f == "+x" ? V3{1, 0, 0} : V3{0, 0, -1};
    // left = up x forward
    left = {forward.z, 0.0, -forward.x};
}

// ---------------------------------------------------------------------------------------------------------
// Profile diagram.

void diagram(const op::Profile& p, float* out) {
    const std::size_t n = p.roles.size();
    bool any_authored = false;
    for (const op::Role& r : p.roles) any_authored |= r.has_diagram;
    // Side view when the root points horizontally (quadrupeds, birds), else front view.
    bool side = false;
    for (const op::Role& r : p.roles) {
        if (r.parent.empty() && r.has_direction) side = std::abs(r.direction[2]) > std::abs(r.direction[1]);
        if (r.parent.empty()) break;
    }
    std::vector<double> x(n, 0.5);
    std::vector<double> y(n, 0.5);
    std::vector<int> children(n, 0);
    for (std::size_t i = 0; i < n; ++i) {
        const op::Role& r = p.roles[i];
        if (r.has_diagram) {
            x[i] = r.diagram[0];
            y[i] = r.diagram[1];
            continue;
        }
        const int parent = r.parent.empty() ? -1 : p.find(r.parent);
        if (parent < 0) {
            x[i] = 0.5;
            y[i] = any_authored ? 0.05 : 0.5;
            continue;
        }
        // Chain roles (fingers, tails) are short; directionless roles hang off their parent.
        const double len = r.chain ? 0.035 : 0.09;
        double dx = 0;
        double dy = 0;
        if (r.has_direction) {
            // The character faces the viewer: its left (-X) on the right; up = -y.
            dx = side ? r.direction[2] : -r.direction[0];
            dy = -r.direction[1];
            if (side && r.side == op::Side::Right) {
                dx += 0.25;
                dy -= 0.15;
            }
        } else {
            const int k = children[static_cast<std::size_t>(parent)];
            dx = r.side == op::Side::Left ? 0.6 : r.side == op::Side::Right ? -0.6 : 0.35 * (k % 2 == 0 ? 1 : -1);
            if (side) dx = r.side == op::Side::Right ? 0.4 : -0.2;
            dy = 0.6;
        }
        const double l = std::sqrt(dx * dx + dy * dy);
        if (l > 1e-9) {
            dx /= l;
            dy /= l;
        }
        x[i] = x[static_cast<std::size_t>(parent)] + dx * len;
        y[i] = y[static_cast<std::size_t>(parent)] + dy * len;
        ++children[static_cast<std::size_t>(parent)];
    }
    if (!any_authored && n > 0) {
        double x0 = 1e300, x1 = -1e300, y0 = 1e300, y1 = -1e300;
        for (std::size_t i = 0; i < n; ++i) {
            x0 = std::min(x0, x[i]);
            x1 = std::max(x1, x[i]);
            y0 = std::min(y0, y[i]);
            y1 = std::max(y1, y[i]);
        }
        const double span = std::max({x1 - x0, y1 - y0, 1e-9});
        const double scale = 0.9 / span;
        const double ox = 0.5 - 0.5 * (x0 + x1) * scale;
        const double oy = 0.5 - 0.5 * (y0 + y1) * scale;
        for (std::size_t i = 0; i < n; ++i) {
            x[i] = x[i] * scale + ox;
            y[i] = y[i] * scale + oy;
        }
    }
    for (std::size_t i = 0; i < n; ++i) {
        out[i * 2] = static_cast<float>(std::clamp(x[i], 0.0, 1.0));
        out[i * 2 + 1] = static_cast<float>(std::clamp(y[i], 0.0, 1.0));
    }
}

// ---------------------------------------------------------------------------------------------------------
// Role states.

void role_states(const cc_char* c, const cr_rig* rig, const op::Profile& p, const cc_report& report, uint32_t* out) {
    const std::size_t n = p.roles.size();
    std::unordered_map<std::string, std::size_t> index;
    for (std::size_t i = 0; i < n; ++i) {
        index.emplace(p.roles[i].name, i);
        out[i] = p.roles[i].required ? static_cast<uint32_t>(CC_ROLE_REQUIRED) : 0u;
    }
    std::unordered_map<std::string, std::vector<std::size_t>> roles_of_joint;
    for (const oc::RoleBinding& b : c->def.roles) {
        auto it = index.find(b.role);
        if (it == index.end()) continue;
        out[it->second] |= CC_ROLE_MAPPED;
        roles_of_joint[b.joint].push_back(it->second);
        if (rig != nullptr && rig_index(rig, b.joint) < 0) out[it->second] |= CC_ROLE_JOINT_MISSING;
    }
    auto flag_role = [&](const std::string& role, uint32_t bit) {
        auto it = index.find(role);
        if (it != index.end()) out[it->second] |= bit;
    };
    for (const auto& item : report.items) {
        switch (item.code) {
            case CC_CHECK_DUPLICATE_JOINT: {
                auto it = roles_of_joint.find(item.subject);
                if (it != roles_of_joint.end()) {
                    for (std::size_t r : it->second) out[r] |= CC_ROLE_DUPLICATE;
                }
                break;
            }
            case CC_CHECK_HIERARCHY_ORDER: flag_role(item.subject, CC_ROLE_HIERARCHY); break;
            case CC_CHECK_SIDE_MISMATCH: flag_role(item.subject, CC_ROLE_SIDE_MISMATCH); break;
            case CC_CHECK_MIRROR_ASYMMETRIC: {
                flag_role(item.subject, CC_ROLE_MIRROR_MISMATCH);
                auto it = index.find(item.subject);
                if (it != index.end() && !p.roles[it->second].mirror.empty()) {
                    flag_role(p.roles[it->second].mirror, CC_ROLE_MIRROR_MISMATCH);
                }
                break;
            }
            default: break;
        }
    }
    constexpr uint32_t kErrors = CC_ROLE_JOINT_MISSING | CC_ROLE_DUPLICATE | CC_ROLE_HIERARCHY | CC_ROLE_SIDE_MISMATCH;
    for (std::size_t i = 0; i < n; ++i) {
        const bool missing_required = (out[i] & CC_ROLE_REQUIRED) != 0 && (out[i] & CC_ROLE_MAPPED) == 0;
        if (missing_required || (out[i] & kErrors) != 0) out[i] |= CC_ROLE_ERROR;
    }
}

}  // namespace

// ---------------------------------------------------------------------------------------------------------
// Overlay.

struct cc_overlay {
    std::vector<cc_overlay_joint> joints;
    std::vector<cc_overlay_segment> segments;
    std::vector<cc_overlay_socket> sockets;
    cc_overlay_capsule capsule{};
    std::vector<float> skin;
    std::vector<float> world;
    std::vector<int32_t> kinds;
    bool has_bones = false;
};

namespace {

cc_overlay_segment segment(const V3& a, const V3& b, int32_t kind, int32_t index) {
    cc_overlay_segment s{};
    s.a[0] = static_cast<float>(a.x);
    s.a[1] = static_cast<float>(a.y);
    s.a[2] = static_cast<float>(a.z);
    s.b[0] = static_cast<float>(b.x);
    s.b[1] = static_cast<float>(b.y);
    s.b[2] = static_cast<float>(b.z);
    s.kind = kind;
    s.index = index;
    return s;
}

void build_overlay(const cc_char* c, const cr_rig* rig, const cc_profile* profile, const float* locals_in,
                   const float* bounds_min, const float* bounds_max, cc_overlay& o) {
    const oc::Character& def = c->def;
    const op::Profile* p = profile == nullptr ? nullptr : &profile->profile;
    const std::size_t n = rig->joints.size();
    std::vector<float> locals(n * 9);
    if (locals_in != nullptr) {
        std::memcpy(locals.data(), locals_in, n * 9 * sizeof(float));
    } else {
        reference_locals(def, rig, locals.data());
    }
    o.skin.resize(n * 16);
    o.world.resize(n * 16);
    std::vector<float> child(n * 16);
    if (cr_rig_pose_locals(rig, locals.data(), nullptr, 0, nullptr, o.skin.data(), o.world.data(), child.data()) != CR_OK) {
        throw std::runtime_error(std::string("cc_char_overlay: ") + cr_last_error());
    }

    // Role of every joint + states.
    std::vector<uint32_t> states;
    std::unordered_map<std::string, int32_t> role_index_of_joint;
    if (p != nullptr) {
        states.assign(p->roles.size(), 0u);
        cc_report* report = cc_char_validate(c, rig, profile);
        if (report == nullptr) throw std::runtime_error(last_error());
        role_states(c, rig, *p, *report, states.data());
        cc_report_destroy(report);
    }
    for (const oc::RoleBinding& b : def.roles) {
        const int ri = p == nullptr ? -1 : p->find(b.role);
        role_index_of_joint[b.joint] = ri >= 0 ? ri : -2;
    }

    o.joints.resize(n);
    o.kinds.resize(n);
    V3 lo{1e300, 1e300, 1e300};
    V3 hi{-1e300, -1e300, -1e300};
    for (std::size_t j = 0; j < n; ++j) {
        const auto& joint = rig->joints[j];
        o.has_bones |= joint.kind == CR_JOINT_BONE;
        o.kinds[j] = joint.kind;
        V3 head;
        V3 tail;
        joint_head_tail(rig, static_cast<int32_t>(j), o.world.data(), child.data(), head, tail);
        cc_overlay_joint& oj = o.joints[j];
        oj.point[0] = static_cast<float>(head.x);
        oj.point[1] = static_cast<float>(head.y);
        oj.point[2] = static_cast<float>(head.z);
        oj.tail[0] = static_cast<float>(tail.x);
        oj.tail[1] = static_cast<float>(tail.y);
        oj.tail[2] = static_cast<float>(tail.z);
        auto it = role_index_of_joint.find(joint.id);
        oj.role = it == role_index_of_joint.end() ? -1 : it->second;
        oj.state = oj.role >= 0 && static_cast<std::size_t>(oj.role) < states.size() ? states[static_cast<std::size_t>(oj.role)] : 0u;
        for (const V3& q : {head, tail}) {
            lo = {std::min(lo.x, q.x), std::min(lo.y, q.y), std::min(lo.z, q.z)};
            hi = {std::max(hi.x, q.x), std::max(hi.y, q.y), std::max(hi.z, q.z)};
        }
    }
    if (bounds_min != nullptr && bounds_max != nullptr) {
        lo = {bounds_min[0], bounds_min[1], bounds_min[2]};
        hi = {bounds_max[0], bounds_max[1], bounds_max[2]};
    }
    if (n == 0) {
        lo = {};
        hi = {};
    }

    auto point_of = [&](const std::string& id, V3& out) {
        const int32_t j = rig_index(rig, id);
        if (j < 0) return false;
        const auto& oj = o.joints[static_cast<std::size_t>(j)];
        out = {oj.point[0], oj.point[1], oj.point[2]};
        return true;
    };
    auto role_point = [&](const char* role, V3& out) {
        const std::string id = role_joint(def, role);
        return !id.empty() && point_of(id, out);
    };

    // Chains: the path end -> start through the rig, drawn joint to joint.
    for (std::size_t ci = 0; ci < def.chains.size(); ++ci) {
        const oc::Chain& ch = def.chains[ci];
        const int32_t start = rig_index(rig, ch.start);
        int32_t j = rig_index(rig, ch.end);
        if (start < 0 || j < 0) continue;
        std::vector<int32_t> path;
        for (std::size_t guard = 0; j >= 0 && guard <= n; ++guard) {
            path.push_back(j);
            if (j == start) break;
            j = rig->joints[static_cast<std::size_t>(j)].parent;
        }
        if (path.empty() || path.back() != start) continue;
        const int32_t kind = rig->joints[static_cast<std::size_t>(start)].kind;
        std::vector<V3> pts;
        for (auto it = path.rbegin(); it != path.rend(); ++it) {
            if (rig->joints[static_cast<std::size_t>(*it)].kind != kind) continue;
            const auto& oj = o.joints[static_cast<std::size_t>(*it)];
            pts.push_back({oj.point[0], oj.point[1], oj.point[2]});
        }
        const auto& end = o.joints[static_cast<std::size_t>(path.front())];
        const V3 end_tail{end.tail[0], end.tail[1], end.tail[2]};
        if ((end_tail - pts.back()).length() > 1e-9) pts.push_back(end_tail);
        for (std::size_t k = 1; k < pts.size(); ++k) {
            o.segments.push_back(segment(pts[k - 1], pts[k], CC_OVERLAY_CHAIN, static_cast<int32_t>(ci)));
        }
    }

    // Sockets.
    for (std::size_t si = 0; si < def.sockets.size(); ++si) {
        const oc::Socket& s = def.sockets[si];
        const int32_t j = rig_index(rig, s.joint);
        if (j < 0) continue;
        cc_overlay_socket os{};
        const float pos[3] = {s.pos.x, s.pos.y, s.pos.z};
        const float rot[3] = {s.rot.x, s.rot.y, s.rot.z};
        const float scl[3] = {s.scale.x, s.scale.y, s.scale.z};
        float local[16];
        trs(pos, rot, scl, local);
        mul4(o.skin.data() + static_cast<std::size_t>(j) * 16, local, os.matrix);
        os.joint = j;
        os.index = static_cast<int32_t>(si);
        os.standard = standard_index(s.name);
        o.sockets.push_back(os);
    }

    // Capsule and lines.
    const oc::Metrics& m = def.metrics;
    const double height = m.height > 0 ? m.height : hi.y - lo.y;
    V3 base{0.5 * (lo.x + hi.x), lo.y, 0.5 * (lo.z + hi.z)};
    V3 hips;
    if (role_point("hips", hips)) {
        base.x = hips.x;
        base.z = hips.z;
    }
    V3 a;
    V3 b;
    double radius = 0;
    if (role_point("upper_arm_l", a) && role_point("upper_arm_r", b)) {
        radius = 0.5 * (a - b).length();
    } else if (role_point("upper_leg_l", a) && role_point("upper_leg_r", b)) {
        radius = 0.5 * (a - b).length();
    }
    if (radius <= 1e-6) radius = 0.25 * (m.width > 0 ? m.width : hi.x - lo.x);
    o.capsule.base[0] = static_cast<float>(base.x);
    o.capsule.base[1] = static_cast<float>(base.y);
    o.capsule.base[2] = static_cast<float>(base.z);
    o.capsule.height = static_cast<float>(height);
    o.capsule.radius = static_cast<float>(radius);
    o.capsule.eye_height = m.eye_height;
    o.capsule.hip_height = m.hip_height;

    V3 forward;
    V3 left;
    facing_axes(def, forward, left);
    const double half = 0.5 * std::max({hi.x - lo.x, hi.z - lo.z, 2.0 * radius});
    if (m.eye_height > 0) {
        V3 eyes{base.x, lo.y + m.eye_height, base.z};
        float eye[3];
        if (rig != nullptr && cc_char_eyes_point(c, rig, o.skin.data(), o.world.data(), eye) > 0) eyes = {eye[0], eye[1], eye[2]};
        o.segments.push_back(segment(eyes - left * half, eyes + left * half, CC_OVERLAY_EYE_LINE, -1));
    }
    if (m.hip_height > 0) {
        const V3 h{base.x, lo.y + m.hip_height, base.z};
        o.segments.push_back(segment(h - left * half, h + left * half, CC_OVERLAY_HIP_LINE, -1));
    }
}

double ray_distance(const V3& o, const V3& d, const V3& p, double& along) {
    along = (p - o).dot(d);
    const V3 closest = o + d * along;
    return (p - closest).length();
}

}  // namespace

extern "C" {

// ---------------------------------------------------------------------------------------------------------
// Session.

cc_charedit* cc_charedit_create(cc_char* c, int32_t max_steps) {
    if (c == nullptr) {
        error(CC_ERR_ARG, "cc_charedit_create: null definition");
        return nullptr;
    }
    CC_GUARD_BEGIN
    clear_error();
    auto* ed = new cc_charedit();
    ed->c = c;
    ed->max_steps = max_steps > 0 ? max_steps : 200;
    ed->steps.push_back({ed->capture(nullptr), {}, {}});
    return ed;
    CC_GUARD_END(nullptr)
}

void cc_charedit_destroy(cc_charedit* ed) { delete ed; }

int32_t cc_charedit_begin(cc_charedit* ed, const char* label, const char* merge_key) {
    if (ed == nullptr) return error(CC_ERR_ARG, "cc_charedit_begin: null session");
    CC_GUARD_BEGIN
    if (ed->depth++ == 0) {
        ed->absorb_stray();
        ed->group_start = ed->steps[static_cast<std::size_t>(ed->cursor)].state;
        ed->group_label = label != nullptr && label[0] != '\0' ? label : "edit";
        ed->group_key = merge_key != nullptr ? merge_key : "";
    }
    return CC_OK;
    CC_GUARD_END(CC_ERR_ARG)
}

int32_t cc_charedit_commit(cc_charedit* ed) {
    if (ed == nullptr) return error(CC_ERR_ARG, "cc_charedit_commit: null session");
    if (ed->depth <= 0) return error(CC_ERR_ARG, "cc_charedit_commit: no open group");
    CC_GUARD_BEGIN
    if (--ed->depth > 0) return 0;
    auto& top = ed->steps[static_cast<std::size_t>(ed->cursor)];
    if (ed->live_equals(top.state)) return 0;
    const bool merge = !ed->sealed && !ed->group_key.empty() && ed->cursor > 0 && top.merge_key == ed->group_key &&
                       ed->cursor == static_cast<int32_t>(ed->steps.size()) - 1;
    if (merge) {
        top.state = ed->capture(&top.state);
        if (ed->saved == ed->cursor) ed->saved = -1;
        ++ed->revision;
        return 2;
    }
    ed->push(ed->group_label, ed->group_key);
    ed->sealed = ed->group_key.empty();
    return 1;
    CC_GUARD_END(CC_ERR_ARG)
}

int32_t cc_charedit_cancel(cc_charedit* ed) {
    if (ed == nullptr) return error(CC_ERR_ARG, "cc_charedit_cancel: null session");
    if (ed->depth <= 0) return error(CC_ERR_ARG, "cc_charedit_cancel: no open group");
    CC_GUARD_BEGIN
    ed->depth = 0;
    if (ed->live_equals(ed->group_start)) return 0;
    ed->restore(ed->group_start);
    ++ed->revision;
    return 1;
    CC_GUARD_END(CC_ERR_ARG)
}

int32_t cc_charedit_seal(cc_charedit* ed) {
    if (ed == nullptr) return error(CC_ERR_ARG, "cc_charedit_seal: null session");
    ed->sealed = true;
    return CC_OK;
}

int32_t cc_charedit_undo(cc_charedit* ed) {
    if (ed == nullptr) return error(CC_ERR_ARG, "cc_charedit_undo: null session");
    if (ed->depth > 0) return error(CC_ERR_ARG, "cc_charedit_undo: a group is open");
    CC_GUARD_BEGIN
    ed->absorb_stray();
    if (ed->cursor <= 0) return error(CC_ERR_RANGE, "cc_charedit_undo: nothing to undo");
    --ed->cursor;
    ed->restore(ed->steps[static_cast<std::size_t>(ed->cursor)].state);
    ed->sealed = true;
    ++ed->revision;
    return CC_OK;
    CC_GUARD_END(CC_ERR_ARG)
}

int32_t cc_charedit_redo(cc_charedit* ed) {
    if (ed == nullptr) return error(CC_ERR_ARG, "cc_charedit_redo: null session");
    if (ed->depth > 0) return error(CC_ERR_ARG, "cc_charedit_redo: a group is open");
    CC_GUARD_BEGIN
    ed->absorb_stray();
    if (ed->cursor + 1 >= static_cast<int32_t>(ed->steps.size())) return error(CC_ERR_RANGE, "cc_charedit_redo: nothing to redo");
    ++ed->cursor;
    ed->restore(ed->steps[static_cast<std::size_t>(ed->cursor)].state);
    ed->sealed = true;
    ++ed->revision;
    return CC_OK;
    CC_GUARD_END(CC_ERR_ARG)
}

int32_t cc_charedit_can_undo(const cc_charedit* ed) { return ed != nullptr && ed->depth == 0 && ed->cursor > 0 ? 1 : 0; }

int32_t cc_charedit_can_redo(const cc_charedit* ed) {
    return ed != nullptr && ed->depth == 0 && ed->cursor + 1 < static_cast<int32_t>(ed->steps.size()) ? 1 : 0;
}

const char* cc_charedit_label(const cc_charedit* ed, int32_t which) {
    if (ed == nullptr) return "";
    if (which == 0) return ed->cursor > 0 ? ed->steps[static_cast<std::size_t>(ed->cursor)].label.c_str() : "";
    if (which == 1 && ed->cursor + 1 < static_cast<int32_t>(ed->steps.size())) {
        return ed->steps[static_cast<std::size_t>(ed->cursor) + 1].label.c_str();
    }
    return "";
}

int64_t cc_charedit_revision(const cc_charedit* ed) { return ed == nullptr ? 0 : ed->revision; }

int32_t cc_charedit_dirty(const cc_charedit* ed) {
    if (ed == nullptr) return 0;
    if (ed->saved != ed->cursor) return 1;
    try {
        return ed->live_equals(ed->steps[static_cast<std::size_t>(ed->cursor)].state) ? 0 : 1;
    } catch (...) {
        return 1;
    }
}

int32_t cc_charedit_mark_saved(cc_charedit* ed) {
    if (ed == nullptr) return error(CC_ERR_ARG, "cc_charedit_mark_saved: null session");
    if (ed->depth > 0) return error(CC_ERR_ARG, "cc_charedit_mark_saved: a group is open");
    CC_GUARD_BEGIN
    ed->absorb_stray();
    ed->saved = ed->cursor;
    return CC_OK;
    CC_GUARD_END(CC_ERR_ARG)
}

int32_t cc_charedit_depth(const cc_charedit* ed) { return ed == nullptr ? 0 : ed->depth; }

int32_t cc_charedit_step_count(const cc_charedit* ed) { return ed == nullptr ? 0 : static_cast<int32_t>(ed->steps.size()) - 1; }

int32_t cc_charedit_reset(cc_charedit* ed) {
    if (ed == nullptr) return error(CC_ERR_ARG, "cc_charedit_reset: null session");
    if (ed->depth > 0) return error(CC_ERR_ARG, "cc_charedit_reset: a group is open");
    CC_GUARD_BEGIN
    State s = ed->capture(nullptr);
    ed->steps.clear();
    ed->steps.push_back({std::move(s), {}, {}});
    ed->cursor = 0;
    ed->saved = 0;
    ed->sealed = true;
    ++ed->revision;
    return CC_OK;
    CC_GUARD_END(CC_ERR_ARG)
}

// ---------------------------------------------------------------------------------------------------------
// Role states, diagram, poses, reference locals.

int32_t cc_char_role_states(const cc_char* c, const cr_rig* rig, const cc_profile* profile, uint32_t* out,
                            int32_t capacity) {
    if (c == nullptr || profile == nullptr) return error(CC_ERR_ARG, "cc_char_role_states: null argument");
    const op::Profile& p = profile->profile;
    const auto n = static_cast<int32_t>(p.roles.size());
    if (out == nullptr || capacity < n) return error(CC_ERR_ARG, "cc_char_role_states: out needs one entry per profile role");
    CC_GUARD_BEGIN
    clear_error();
    cc_report* report = cc_char_validate(c, rig, profile);
    if (report == nullptr) return CC_ERR_ARG;
    std::unique_ptr<cc_report, void (*)(cc_report*)> guard(report, cc_report_destroy);
    role_states(c, rig, p, *report, out);
    return n;
    CC_GUARD_END(CC_ERR_ARG)
}

int32_t cc_profile_diagram(const cc_profile* p, float* out_xy, int32_t capacity) {
    if (p == nullptr) return error(CC_ERR_ARG, "cc_profile_diagram: null profile");
    const auto n = static_cast<int32_t>(p->profile.roles.size());
    if (out_xy == nullptr || capacity < n * 2) return error(CC_ERR_ARG, "cc_profile_diagram: out needs 2 floats per role");
    CC_GUARD_BEGIN
    diagram(p->profile, out_xy);
    return n;
    CC_GUARD_END(CC_ERR_ARG)
}

int32_t cc_profile_role_has_diagram(const cc_profile* p, int32_t index) {
    if (p == nullptr || !in_range(p->profile.roles, index)) return 0;
    return p->profile.roles[static_cast<std::size_t>(index)].has_diagram ? 1 : 0;
}

int32_t cc_profile_pose_count(const cc_profile* p) { return p == nullptr ? 0 : static_cast<int32_t>(p->profile.poses.size()); }

const char* cc_profile_pose_name(const cc_profile* p, int32_t index) {
    if (p == nullptr || !in_range(p->profile.poses, index)) return nullptr;
    return p->profile.poses[static_cast<std::size_t>(index)].name.c_str();
}

int32_t cc_char_reference_locals(const cc_char* c, const cr_rig* rig, float* out_locals) {
    if (c == nullptr || rig == nullptr || out_locals == nullptr) return error(CC_ERR_ARG, "cc_char_reference_locals: null argument");
    reference_locals(c->def, rig, out_locals);
    return CC_OK;
}

// ---------------------------------------------------------------------------------------------------------
// Standard sockets.

int32_t cc_char_standard_socket_count(void) { return kStandardCount; }

const char* cc_char_standard_socket_name(int32_t index) {
    return index >= 0 && index < kStandardCount ? kStandardSockets[index].name : nullptr;
}

const char* cc_char_standard_socket_role(int32_t index) {
    return index >= 0 && index < kStandardCount ? kStandardSockets[index].role : nullptr;
}

int32_t cc_char_place_standard_socket(cc_char* c, const cr_rig* rig, const char* name) {
    if (c == nullptr || rig == nullptr || !valid_key(name)) return error(CC_ERR_ARG, "cc_char_place_standard_socket: null argument");
    const int32_t si = standard_index(name);
    if (si < 0) return error(CC_ERR_RANGE, std::string("cc_char_place_standard_socket: not a standard socket: ") + name);
    CC_GUARD_BEGIN
    clear_error();
    const oc::Character& def = c->def;
    std::string role = kStandardSockets[si].role;
    std::string joint = role_joint(def, role);
    if (joint.empty() && role == "upper_chest") {
        for (const char* r : {"chest", "spine"}) {
            joint = role_joint(def, r);
            if (!joint.empty()) {
                role = r;
                break;
            }
        }
    }
    const int32_t j = joint.empty() ? -1 : rig_index(rig, joint);
    if (j < 0) return error(CC_ERR_RANGE, std::string("cc_char_place_standard_socket: role ") + role + " is not mapped to a joint of the rig");

    // Rest frames: sockets live in rest model space.
    const std::size_t n = rig->joints.size();
    std::vector<float> world(n * 16);
    std::vector<float> child(n * 16);
    cr_rig_rest_world(rig, world.data(), child.data());
    V3 head;
    V3 tail;
    joint_head_tail(rig, j, world.data(), child.data(), head, tail);
    V3 forward;
    V3 left;
    facing_axes(def, forward, left);
    const double len = (tail - head).length();
    double height = def.metrics.height;
    if (height <= 0) {
        double y0 = 1e300, y1 = -1e300;
        for (std::size_t k = 0; k < n; ++k) {
            V3 h;
            V3 t;
            joint_head_tail(rig, static_cast<int32_t>(k), world.data(), child.data(), h, t);
            y0 = std::min({y0, h.y, t.y});
            y1 = std::max({y1, h.y, t.y});
        }
        height = y1 > y0 ? y1 - y0 : 1.0;
    }

    V3 at = head;
    const std::string sname = name;
    if (sname == "grip_r" || sname == "grip_l") {
        at = head + (tail - head) * 0.5;
    } else if (sname == "eyes") {
        V3 l;
        V3 r;
        auto pt = [&](const char* rr, V3& out) {
            const std::string id = role_joint(def, rr);
            const int32_t k = id.empty() ? -1 : rig_index(rig, id);
            if (k < 0) return false;
            V3 t2;
            joint_head_tail(rig, k, world.data(), child.data(), out, t2);
            return true;
        };
        if (pt("eye_l", l) && pt("eye_r", r)) {
            at = (l + r) * 0.5;
        } else {
            const double size = len > 1e-6 ? len : 0.12 * height;
            at = head + (len > 1e-6 ? (tail - head) * 0.5 : V3{0, 0.5 * size, 0}) + forward * (0.35 * size);
        }
    } else if (sname == "back") {
        at = head - forward * (0.08 * height);
    }
    const float pos[3] = {static_cast<float>(at.x), static_cast<float>(at.y), static_cast<float>(at.z)};
    const float zero[3] = {0.f, 0.f, 0.f};
    return cc_char_socket_set(c, name, rig->joints[static_cast<std::size_t>(j)].id.c_str(), pos, zero, nullptr);
    CC_GUARD_END(CC_ERR_ARG)
}

// ---------------------------------------------------------------------------------------------------------
// Overlay.

cc_overlay* cc_char_overlay(const cc_char* c, const cr_rig* rig, const cc_profile* profile, const float* locals,
                            const float* bounds_min, const float* bounds_max) {
    if (c == nullptr || rig == nullptr) {
        error(CC_ERR_ARG, "cc_char_overlay: null argument");
        return nullptr;
    }
    CC_GUARD_BEGIN
    clear_error();
    auto o = std::make_unique<cc_overlay>();
    build_overlay(c, rig, profile, locals, bounds_min, bounds_max, *o);
    return o.release();
    CC_GUARD_END(nullptr)
}

void cc_overlay_destroy(cc_overlay* o) { delete o; }

int32_t cc_overlay_joint_count(const cc_overlay* o) { return o == nullptr ? 0 : static_cast<int32_t>(o->joints.size()); }
const cc_overlay_joint* cc_overlay_joints(const cc_overlay* o) { return o == nullptr ? nullptr : o->joints.data(); }
int32_t cc_overlay_segment_count(const cc_overlay* o) { return o == nullptr ? 0 : static_cast<int32_t>(o->segments.size()); }
const cc_overlay_segment* cc_overlay_segments(const cc_overlay* o) { return o == nullptr ? nullptr : o->segments.data(); }
int32_t cc_overlay_socket_count(const cc_overlay* o) { return o == nullptr ? 0 : static_cast<int32_t>(o->sockets.size()); }
const cc_overlay_socket* cc_overlay_sockets(const cc_overlay* o) { return o == nullptr ? nullptr : o->sockets.data(); }
const cc_overlay_capsule* cc_overlay_get_capsule(const cc_overlay* o) { return o == nullptr ? nullptr : &o->capsule; }
const float* cc_overlay_skin(const cc_overlay* o) { return o == nullptr ? nullptr : o->skin.data(); }
const float* cc_overlay_world(const cc_overlay* o) { return o == nullptr ? nullptr : o->world.data(); }

int32_t cc_overlay_pick(const cc_overlay* o, const float* origin, const float* dir, float tolerance,
                        float tolerance_per_distance, int32_t candidates_only, int32_t* out_index, float* out_distance) {
    set_out(out_index, -1);
    if (o == nullptr || origin == nullptr || dir == nullptr) return error(CC_ERR_ARG, "cc_overlay_pick: null argument");
    V3 d{dir[0], dir[1], dir[2]};
    const double l = d.length();
    if (!(l > 0) || !std::isfinite(l)) return error(CC_ERR_ARG, "cc_overlay_pick: zero direction");
    d = d * (1.0 / l);
    const V3 org{origin[0], origin[1], origin[2]};
    int32_t kind = CC_PICK_NONE;
    int32_t index = -1;
    double best = std::numeric_limits<double>::infinity();
    auto consider = [&](const V3& p, int32_t k, int32_t i) {
        double along = 0;
        const double dist = ray_distance(org, d, p, along);
        if (along < 0) return;
        if (dist > tolerance + tolerance_per_distance * along) return;
        if (along < best) {
            best = along;
            kind = k;
            index = i;
        }
    };
    for (std::size_t i = 0; i < o->sockets.size(); ++i) {
        const float* m = o->sockets[i].matrix;
        consider({m[12], m[13], m[14]}, CC_PICK_SOCKET, static_cast<int32_t>(i));
    }
    for (std::size_t i = 0; i < o->joints.size(); ++i) {
        if (candidates_only != 0 && o->has_bones && o->kinds[i] != CR_JOINT_BONE) continue;
        consider({o->joints[i].point[0], o->joints[i].point[1], o->joints[i].point[2]}, CC_PICK_JOINT, static_cast<int32_t>(i));
    }
    set_out(out_index, index);
    set_out(out_distance, static_cast<float>(best));
    return kind;
}

}  // extern "C"
