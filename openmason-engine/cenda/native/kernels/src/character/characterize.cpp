// Cenda character: characterization (OpenMason #154) — auto-map, validation against a profile, reference-pose
// fitting (rest normalization), lock, and profiles exported from a rig. See cenda/character.h.
#include "character_internal.hpp"
#include "cenda/character_edit.h"

#include <array>
#include <cctype>
#include <cstring>
#include <map>
#include <numbers>
#include <string_view>
#include <unordered_map>
#include <unordered_set>

using namespace cenda::character;

namespace {

// ---------------------------------------------------------------------------------------------------------
// Small vector / matrix math (double).

struct Vec {
    double x = 0, y = 0, z = 0;
    Vec operator+(const Vec& o) const { return {x + o.x, y + o.y, z + o.z}; }
    Vec operator-(const Vec& o) const { return {x - o.x, y - o.y, z - o.z}; }
    Vec operator*(double s) const { return {x * s, y * s, z * s}; }
    [[nodiscard]] double dot(const Vec& o) const { return x * o.x + y * o.y + z * o.z; }
    [[nodiscard]] Vec cross(const Vec& o) const { return {y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x}; }
    [[nodiscard]] double length() const { return std::sqrt(dot(*this)); }
    [[nodiscard]] Vec normalized() const {
        const double l = length();
        return l > 0 ? *this * (1.0 / l) : Vec{};
    }
};

using Mat3 = std::array<std::array<double, 3>, 3>;  // [row][col], column vectors

Mat3 mul(const Mat3& a, const Mat3& b) {
    Mat3 r{};
    for (std::size_t i = 0; i < 3; ++i)
        for (std::size_t j = 0; j < 3; ++j)
            for (std::size_t k = 0; k < 3; ++k) r[i][j] += a[i][k] * b[k][j];
    return r;
}

Mat3 transpose(const Mat3& a) {
    Mat3 r{};
    for (std::size_t i = 0; i < 3; ++i)
        for (std::size_t j = 0; j < 3; ++j) r[i][j] = a[j][i];
    return r;
}

Mat3 identity3() { return {{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}}}; }

constexpr double kDeg = std::numbers::pi / 180.0;

// R = Rx(a)·Ry(b)·Rz(c), degrees (the rig's convention, JOML rotateXYZ).
Mat3 euler_xyz(const float* deg) {
    const double a = deg[0] * kDeg, b = deg[1] * kDeg, c = deg[2] * kDeg;
    const double ca = std::cos(a), sa = std::sin(a), cb = std::cos(b), sb = std::sin(b), cc = std::cos(c), sc = std::sin(c);
    return {{{cb * cc, -cb * sc, sb},
             {ca * sc + sa * sb * cc, ca * cc - sa * sb * sc, -sa * cb},
             {sa * sc - ca * sb * cc, sa * cc + ca * sb * sc, ca * cb}}};
}

void to_euler_xyz(const Mat3& r, float* deg) {
    const double sb = std::clamp(r[0][2], -1.0, 1.0);
    const double b = std::asin(sb);
    double a = 0;
    double c = 0;
    if (std::abs(sb) < 1.0 - 1e-9) {
        a = std::atan2(-r[1][2], r[2][2]);
        c = std::atan2(-r[0][1], r[0][0]);
    } else {
        a = std::atan2(r[2][1], r[1][1]);  // gimbal lock: c = 0
    }
    deg[0] = static_cast<float>(a / kDeg);
    deg[1] = static_cast<float>(b / kDeg);
    deg[2] = static_cast<float>(c / kDeg);
}

// The rotation taking unit vector d onto unit vector t.
Mat3 rotation_between(const Vec& d, const Vec& t) {
    const double c = d.dot(t);
    if (c > 1.0 - 1e-12) return identity3();
    if (c < -1.0 + 1e-12) {
        const Vec axis = d.cross(std::abs(d.x) < 0.9 ? Vec{1, 0, 0} : Vec{0, 1, 0}).normalized();
        const double a[3] = {axis.x, axis.y, axis.z};
        Mat3 r{};
        for (std::size_t i = 0; i < 3; ++i)
            for (std::size_t j = 0; j < 3; ++j) r[i][j] = 2 * a[i] * a[j] - (i == j ? 1.0 : 0.0);
        return r;
    }
    const Vec v = d.cross(t);
    const Mat3 k = {{{0, -v.z, v.y}, {v.z, 0, -v.x}, {-v.y, v.x, 0}}};
    const Mat3 k2 = mul(k, k);
    const double f = 1.0 / (1.0 + c);
    Mat3 r = identity3();
    for (std::size_t i = 0; i < 3; ++i)
        for (std::size_t j = 0; j < 3; ++j) r[i][j] += k[i][j] + k2[i][j] * f;
    return r;
}

// Rotation part of a column-major affine float[16] (columns normalized: drops uniform scale).
Mat3 rotation_of(const float* m) {
    Mat3 r{};
    for (std::size_t col = 0; col < 3; ++col) {
        Vec c{m[col * 4 + 0], m[col * 4 + 1], m[col * 4 + 2]};
        c = c.normalized();
        r[0][col] = c.x;
        r[1][col] = c.y;
        r[2][col] = c.z;
    }
    return r;
}

Vec translation_of(const float* m) { return {m[12], m[13], m[14]}; }

// ---------------------------------------------------------------------------------------------------------
// Names.

// 0 = none, 1 = left, 2 = right.
int side_token(const std::string& t) {
    if (t == "l" || t == "left" || t == "lt" || t == "lft") return 1;
    if (t == "r" || t == "right" || t == "rt" || t == "rgt") return 2;
    return 0;
}

// "mixamorig:LeftForeArm" -> [left, fore, arm]; "finger_index_tip_left" -> [finger, index, tip, left];
// "Bip01 L UpperArm" -> [bip, 01, l, upper, arm]; "spine1" -> [spine, 1].
std::vector<std::string> tokenize(std::string_view name) {
    const auto cut = name.find_last_of(":|");
    if (cut != std::string_view::npos) name = name.substr(cut + 1);
    std::vector<std::string> out;
    std::string cur;
    auto flush = [&] {
        if (!cur.empty()) out.push_back(std::move(cur));
        cur.clear();
    };
    for (std::size_t i = 0; i < name.size(); ++i) {
        const auto ch = static_cast<unsigned char>(name[i]);
        if (!std::isalnum(ch)) {
            flush();
            continue;
        }
        if (!cur.empty()) {
            const auto prev = static_cast<unsigned char>(name[i - 1]);
            const bool lower_to_upper = std::islower(prev) && std::isupper(ch);
            const bool digit_change = (std::isdigit(prev) != 0) != (std::isdigit(ch) != 0);
            const bool acronym_end = std::isupper(prev) && std::isupper(ch) && i + 1 < name.size() &&
                                     std::islower(static_cast<unsigned char>(name[i + 1]));
            if (lower_to_upper || digit_change || acronym_end) flush();
        }
        cur.push_back(static_cast<char>(std::tolower(ch)));
    }
    flush();
    return out;
}

float alias_score(const op::Alias& alias, const std::vector<std::string>& tokens) {
    if (tokens.empty()) return 0.f;
    std::vector<char> taken(tokens.size(), 0);
    for (const std::string& a : alias.tokens) {
        bool found = false;
        for (std::size_t i = 0; i < tokens.size(); ++i) {
            if (!taken[i] && tokens[i] == a) {
                taken[i] = 1;
                found = true;
                break;
            }
        }
        if (!found) return 0.f;
    }
    return alias.weight * static_cast<float>(std::sqrt(static_cast<double>(alias.tokens.size()) / static_cast<double>(tokens.size())));
}

float role_score(const op::Role& role, const std::vector<std::string>& tokens) {
    float best = 0.f;
    for (const op::Alias& a : role.match) best = std::max(best, alias_score(a, tokens));
    return best;
}

// ---------------------------------------------------------------------------------------------------------
// A rig in its rest pose, as characterization sees it.

struct Candidate {
    int32_t joint = -1;
    std::string name;
    std::vector<std::string> tokens;  // side and noise tokens removed
    std::vector<std::string> all_tokens;
    int name_side = 0;                // from the name
    int side = 0;                     // anatomical (geometry), 0 = center
    bool chain_only = false;          // holds a chain keyword: only chain roles may take it
    Vec point;
    int depth = 0;
};

Vec facing_vector(const std::string& facing) {
    if (facing == "+z") return {0, 0, 1};
    if (facing == "-x") return {-1, 0, 0};
    if (facing == "+x") return {1, 0, 0};
    return {0, 0, -1};
}

std::string snap_facing(double x, double z) {
    if (std::abs(z) >= std::abs(x)) return z < 0 ? "-z" : "+z";
    return x < 0 ? "-x" : "+x";
}

struct Skeleton {
    const cr_rig* rig = nullptr;
    std::vector<float> world;   // rest W
    std::vector<float> child;   // rest F
    std::vector<Candidate> cands;
    std::vector<int> cand_of_joint;
    Vec up{0, 1, 0};
    Vec forward{0, 0, -1};
    Vec left{-1, 0, 0};
    Vec center;
    double height = 1.0;

    explicit Skeleton(const cr_rig* r) : rig(r) {
        const std::size_t n = rig->joints.size();
        world.resize(n * 16);
        child.resize(n * 16);
        cr_rig_rest_world(rig, world.data(), child.data());
        cand_of_joint.assign(n, -1);

        bool has_bones = false;
        for (const auto& j : rig->joints) has_bones |= j.kind == CR_JOINT_BONE;
        double min_y = 1e300;
        double max_y = -1e300;
        for (std::size_t j = 0; j < n; ++j) {
            const auto& joint = rig->joints[j];
            if (has_bones && joint.kind != CR_JOINT_BONE) continue;
            Candidate c;
            c.joint = static_cast<int32_t>(j);
            c.name = joint.name.empty() ? joint.id : joint.name;
            c.all_tokens = tokenize(c.name);
            for (const std::string& t : c.all_tokens) {
                const int s = side_token(t);
                if (s != 0 && c.name_side == 0) {
                    c.name_side = s;
                } else if (s == 0) {
                    c.tokens.push_back(t);
                }
            }
            double p[3];
            joint_point(rig, c.joint, world.data(), p);
            c.point = {p[0], p[1], p[2]};
            min_y = std::min(min_y, p[1]);
            max_y = std::max(max_y, p[1]);
            for (int32_t a = joint.parent; a >= 0 && c.depth <= static_cast<int>(n);
                 a = rig->joints[static_cast<std::size_t>(a)].parent) {
                ++c.depth;
            }
            cand_of_joint[j] = static_cast<int>(cands.size());
            cands.push_back(std::move(c));
        }
        if (max_y > min_y) height = max_y - min_y;

        // Noise tokens: a prefix most names share ("mixamorig" is cut as a namespace; "bip", "01", "def").
        if (cands.size() >= 4) {
            std::map<std::string, std::size_t> count;
            for (const Candidate& c : cands) {
                std::unordered_set<std::string> seen(c.tokens.begin(), c.tokens.end());
                for (const std::string& t : seen) ++count[t];
            }
            for (Candidate& c : cands) {
                std::vector<std::string> kept;
                for (const std::string& t : c.tokens) {
                    if (count[t] * 4 < cands.size() * 3) kept.push_back(t);
                }
                if (!kept.empty()) c.tokens = std::move(kept);
            }
        }
        Vec sum;
        for (const Candidate& c : cands) sum = sum + c.point;
        if (!cands.empty()) center = sum * (1.0 / static_cast<double>(cands.size()));
    }

    void set_facing(const std::string& facing) {
        forward = facing_vector(facing);
        left = up.cross(forward);
    }

    [[nodiscard]] double lateral(const Vec& p) const { return (p - center).dot(left); }

    // Anatomical sides: names pair a left and a right joint; positions say which is which.
    void assign_sides() {
        std::map<std::vector<std::string>, std::array<std::vector<int>, 3>> by_base;
        for (std::size_t i = 0; i < cands.size(); ++i) {
            const Candidate& c = cands[i];
            if (c.name_side != 0) by_base[c.tokens][static_cast<std::size_t>(c.name_side)].push_back(static_cast<int>(i));
        }
        const double eps = 1e-6 * height;
        for (Candidate& c : cands) {
            if (c.name_side == 0) {
                c.side = 0;
                continue;
            }
            const auto& group = by_base[c.tokens];
            const auto& partners = group[static_cast<std::size_t>(c.name_side == 1 ? 2 : 1)];
            const double mine = lateral(c.point);
            if (partners.size() == 1 && group[static_cast<std::size_t>(c.name_side)].size() == 1) {
                const double theirs = lateral(cands[static_cast<std::size_t>(partners[0])].point);
                c.side = std::abs(mine - theirs) <= eps ? c.name_side : (mine > theirs ? 1 : 2);
            } else {
                c.side = mine > eps ? 1 : (mine < -eps ? 2 : c.name_side);
            }
        }
    }

    [[nodiscard]] bool is_descendant(int32_t joint, int32_t ancestor) const {
        int guard = 0;
        for (int32_t a = rig->joints[static_cast<std::size_t>(joint)].parent; a >= 0 && guard <= static_cast<int>(rig->joints.size());
             a = rig->joints[static_cast<std::size_t>(a)].parent, ++guard) {
            if (a == ancestor) return true;
        }
        return false;
    }

    // A profile direction (profile frame: +Y up, faces -Z, left -X) in this model's frame.
    [[nodiscard]] Vec to_model(const float* d) const {
        const double l = -d[0];   // along the character's left
        const double u = d[1];
        const double f = -d[2];   // along the character's forward
        return left * l + up * u + forward * f;
    }

    // A model direction in the profile frame.
    [[nodiscard]] Vec to_profile(const Vec& v) const { return {-v.dot(left), v.dot(up), -v.dot(forward)}; }
};

// The rest-pose direction of a candidate's own bone (head -> tail), zero for a part.
Vec bone_direction(const Skeleton& s, int32_t joint) {
    if (s.rig->joints[static_cast<std::size_t>(joint)].kind != CR_JOINT_BONE) return {};
    double head[3];
    joint_point(s.rig, joint, s.world.data(), head);
    const Vec tail = translation_of(s.child.data() + static_cast<std::size_t>(joint) * 16);
    return tail - Vec{head[0], head[1], head[2]};
}

// Facing detection: from the profile's facing roles (their joints' own horizontal direction) or from/to.
std::string detect_facing(const Skeleton& s, const op::Profile& p, std::string& how) {
    auto best_for = [&](const std::string& role_name) {
        const int ri = p.find(role_name);
        int best = -1;
        float score = 0.5f;
        if (ri < 0) return best;
        for (std::size_t i = 0; i < s.cands.size(); ++i) {
            const float sc = role_score(p.roles[static_cast<std::size_t>(ri)], s.cands[i].tokens);
            if (sc > score) {
                score = sc;
                best = static_cast<int>(i);
            }
        }
        return best;
    };
    if (!p.facing.roles.empty()) {
        double x = 0;
        double z = 0;
        double total = 0;
        int used = 0;
        for (std::size_t i = 0; i < s.cands.size(); ++i) {
            bool matches = false;
            for (const std::string& r : p.facing.roles) {
                const int ri = p.find(r);
                if (ri >= 0 && role_score(p.roles[static_cast<std::size_t>(ri)], s.cands[i].tokens) > 0.5f) matches = true;
            }
            if (!matches) continue;
            const Vec d = bone_direction(s, s.cands[i].joint).normalized();
            if (d.length() == 0) continue;
            x += d.x;
            z += d.z;
            total += 1;
            ++used;
        }
        if (used > 0 && std::sqrt(x * x + z * z) / total > 0.25) {
            how = "from the direction of " + std::to_string(used) + " foot joint(s)";
            return snap_facing(x, z);
        }
    }
    if (!p.facing.from.empty()) {
        const int a = best_for(p.facing.from);
        const int b = best_for(p.facing.to);
        if (a >= 0 && b >= 0) {
            const Vec v = s.cands[static_cast<std::size_t>(b)].point - s.cands[static_cast<std::size_t>(a)].point;
            if (std::sqrt(v.x * v.x + v.z * v.z) > 0.25 * v.length()) {
                how = "from " + s.cands[static_cast<std::size_t>(a)].name + " to " + s.cands[static_cast<std::size_t>(b)].name;
                return snap_facing(v.x, v.z);
            }
        }
    }
    how = "not detectable; using the engine default -z";
    return "-z";
}

std::string side_label(int side) { return side == 1 ? "left" : side == 2 ? "right" : "center"; }

int role_side(const op::Role& r) { return r.side == op::Side::Left ? 1 : r.side == op::Side::Right ? 2 : 0; }

// The nearest mapped ancestor role's joint (-1 when none), following profile parents.
template <typename Mapped>
int32_t mapped_ancestor_joint(const op::Profile& p, const op::Role& role, Mapped mapped) {
    std::string parent = role.parent;
    for (std::size_t guard = 0; !parent.empty() && guard <= p.roles.size(); ++guard) {
        const int pi = p.find(parent);
        if (pi < 0) return -1;
        const int32_t j = mapped(pi);
        if (j >= 0) return j;
        parent = p.roles[static_cast<std::size_t>(pi)].parent;
    }
    return -1;
}

std::string first_names(const std::vector<std::string>& names) {
    std::string out;
    for (std::size_t i = 0; i < names.size() && i < 4; ++i) out += (i ? ", " : "") + names[i];
    if (names.size() > 4) out += ", ...";
    return out;
}

// ---------------------------------------------------------------------------------------------------------
// Auto-map.

void auto_map(oc::Character& def, const cr_rig* rig, const op::Profile& p, cc_automap& result) {
    Skeleton s(rig);
    std::string how;
    std::string facing = def.facing;
    if (facing.empty()) {
        facing = detect_facing(s, p, how);
        if (how.rfind("not", 0) != 0) def.facing = facing;
    } else {
        how = "set on the definition";
    }
    s.set_facing(facing);
    s.assign_sides();
    result.notes.add(CC_SEVERITY_INFO, CC_CHECK_FACING, facing, "facing " + facing + ": " + how);

    // Side swaps (names written from the viewer's side, for example).
    std::vector<std::string> swapped;
    for (const Candidate& c : s.cands) {
        if (c.name_side != 0 && c.side != c.name_side) swapped.push_back(c.name);
    }
    if (!swapped.empty()) {
        result.notes.add(CC_SEVERITY_INFO, CC_CHECK_SIDE_FROM_GEOMETRY, swapped.front(),
                         std::to_string(swapped.size()) + " joint(s) whose name says one side lie on the character's other side ("
                             + first_names(swapped) + "); mapped by position");
    }

    // Chain keywords reserve joints for chain roles.
    std::unordered_set<std::string> keywords;
    for (const op::Role& r : p.roles) {
        if (r.chain) keywords.insert(r.chain->keywords.begin(), r.chain->keywords.end());
    }
    for (Candidate& c : s.cands) {
        for (const std::string& t : c.tokens) c.chain_only |= keywords.contains(t);
    }

    const std::size_t nr = p.roles.size();
    std::vector<int> assigned(nr, -1);  // candidate index
    std::vector<float> confidence(nr, 0.f);
    std::vector<char> used(s.cands.size(), 0);
    auto mapped_joint = [&](int ri) {
        const int k = assigned[static_cast<std::size_t>(ri)];
        return k < 0 ? -1 : s.cands[static_cast<std::size_t>(k)].joint;
    };

    for (std::size_t ri = 0; ri < nr; ++ri) {
        const op::Role& role = p.roles[ri];
        const int32_t anc = mapped_ancestor_joint(p, role, mapped_joint);
        int best = -1;
        float best_score = 0.f;
        if (role.chain) {
            const int under = p.find(role.chain->under);
            const int32_t under_joint = under < 0 ? -1 : mapped_joint(under);
            if (under_joint < 0) continue;
            const int32_t root = anc >= 0 ? anc : under_joint;
            for (std::size_t k = 0; k < s.cands.size(); ++k) {
                const Candidate& c = s.cands[k];
                if (used[k] || !s.is_descendant(c.joint, root)) continue;
                const bool keyword = std::any_of(c.tokens.begin(), c.tokens.end(), [&](const std::string& t) {
                    return std::find(role.chain->keywords.begin(), role.chain->keywords.end(), t) != role.chain->keywords.end();
                });
                if (!keyword) continue;
                if (best < 0 || c.depth < s.cands[static_cast<std::size_t>(best)].depth) best = static_cast<int>(k);
            }
            best_score = 0.9f;
        } else {
            const int want = role_side(role);
            for (std::size_t k = 0; k < s.cands.size(); ++k) {
                const Candidate& c = s.cands[k];
                if (used[k] || c.chain_only || c.side != want) continue;
                if (anc >= 0 && !s.is_descendant(c.joint, anc)) continue;
                const float sc = role_score(role, c.tokens);
                if (sc <= 0.f) continue;
                const bool better = best < 0 || sc > best_score + 1e-6f ||
                                    (std::abs(sc - best_score) <= 1e-6f && c.depth < s.cands[static_cast<std::size_t>(best)].depth);
                if (better) {
                    best = static_cast<int>(k);
                    best_score = sc;
                }
            }
        }
        if (best >= 0) {
            assigned[ri] = best;
            confidence[ri] = best_score;
            used[static_cast<std::size_t>(best)] = 1;
        }
    }

    // Promotion: an optional parent role's joint that is really the missing required role (a goblin's
    // "shoulder" pivot is its upper arm, its "neck" its head).
    for (std::size_t ri = 0; ri < nr; ++ri) {
        const op::Role& role = p.roles[ri];
        if (!role.required || assigned[ri] >= 0 || role.parent.empty()) continue;
        const int pi = p.find(role.parent);
        if (pi < 0 || p.roles[static_cast<std::size_t>(pi)].required || assigned[static_cast<std::size_t>(pi)] < 0) continue;
        assigned[ri] = assigned[static_cast<std::size_t>(pi)];
        confidence[ri] = confidence[static_cast<std::size_t>(pi)] * 0.8f;
        assigned[static_cast<std::size_t>(pi)] = -1;
        const std::string& joint_name = s.cands[static_cast<std::size_t>(assigned[ri])].name;
        result.notes.add(CC_SEVERITY_INFO, CC_CHECK_PROMOTED, role.name,
                         "joint " + joint_name + " maps to required role " + role.name + " instead of optional " +
                             p.roles[static_cast<std::size_t>(pi)].name);
    }

    def.roles.clear();
    for (std::size_t ri = 0; ri < nr; ++ri) {
        if (assigned[ri] < 0) {
            if (p.roles[ri].required) {
                result.notes.add(CC_SEVERITY_WARNING, CC_CHECK_REQUIRED_MISSING, p.roles[ri].name,
                                 "no joint found for required role " + p.roles[ri].name);
            }
            continue;
        }
        const std::string& id = rig->joints[static_cast<std::size_t>(s.cands[static_cast<std::size_t>(assigned[ri])].joint)].id;
        def.roles.push_back({p.roles[ri].name, id});
        result.matches.push_back({p.roles[ri].name, id, confidence[ri]});
    }
    def.profile = p.id;

    // Retarget chains and IK goals from the profile (#160): a chain runs from its first mapped role to its last.
    def.ik_goals.clear();
    for (const op::IkGoal& g : p.ik_goals) {
        const std::string joint = role_joint(def, g.role);
        if (!joint.empty()) def.ik_goals.push_back({g.name, joint});
    }
    def.chains.clear();
    for (const op::RetargetChain& c : p.chains) {
        std::string first, last;
        for (const std::string& r : c.roles) {
            const std::string joint = role_joint(def, r);
            if (joint.empty()) continue;
            if (first.empty()) first = joint;
            last = joint;
        }
        if (first.empty()) continue;
        const bool goal = !c.goal.empty() && std::any_of(def.ik_goals.begin(), def.ik_goals.end(),
                                                         [&](const oc::IkGoal& g) { return g.name == c.goal; });
        def.chains.push_back({c.name, first, last, goal ? c.goal : std::string()});
    }
}

// ---------------------------------------------------------------------------------------------------------
// Validation.

void validate(const oc::Character& def, const cr_rig* rig, const op::Profile& p, cc_report& report) {
    if (!def.profile.empty() && def.profile != p.id) {
        report.add(CC_SEVERITY_WARNING, CC_CHECK_PROFILE_MISMATCH, def.profile,
                   "the definition names profile " + def.profile + ", validated against " + p.id);
    }
    std::unordered_map<std::string, std::string> joint_of;
    std::unordered_map<std::string, std::string> role_of_joint;
    for (const oc::RoleBinding& r : def.roles) {
        joint_of[r.role] = r.joint;
        if (p.find(r.role) < 0) report.add(CC_SEVERITY_WARNING, CC_CHECK_UNKNOWN_ROLE, r.role, "profile " + p.id + " has no role " + r.role);
        auto [it, fresh] = role_of_joint.emplace(r.joint, r.role);
        if (!fresh) {
            report.add(CC_SEVERITY_ERROR, CC_CHECK_DUPLICATE_JOINT, r.joint,
                       "roles " + it->second + " and " + r.role + " map to the same joint " + r.joint);
        }
    }
    for (const op::Role& role : p.roles) {
        if (role.required && !joint_of.contains(role.name)) {
            report.add(CC_SEVERITY_ERROR, CC_CHECK_REQUIRED_MISSING, role.name, "required role " + role.name + " is not mapped");
        }
    }
    if (rig == nullptr) return;

    Skeleton s(rig);
    s.set_facing(def.facing.empty() ? "-z" : def.facing);
    auto joint_index = [&](const std::string& role) -> int32_t {
        auto it = joint_of.find(role);
        return it == joint_of.end() ? -1 : rig_index(rig, it->second);
    };
    auto point = [&](int32_t j) {
        double q[3];
        joint_point(rig, j, s.world.data(), q);
        return Vec{q[0], q[1], q[2]};
    };
    // The body's midline: the hips when mapped.
    if (const int32_t hips = joint_index("hips"); hips >= 0) s.center = point(hips);
    const double tolerance = 0.05 * s.height;

    for (const op::Role& role : p.roles) {
        const int32_t j = joint_index(role.name);
        if (j < 0) continue;
        const int32_t anc = mapped_ancestor_joint(p, role, [&](int pi) {
            return joint_index(p.roles[static_cast<std::size_t>(pi)].name);
        });
        if (anc >= 0 && !s.is_descendant(j, anc)) {
            report.add(CC_SEVERITY_ERROR, CC_CHECK_HIERARCHY_ORDER, role.name,
                       "role " + role.name + ": its joint is not under the joint of its nearest mapped ancestor role");
        }
        const int want = role_side(role);
        if (want != 0) {
            const double lat = s.lateral(point(j));
            if ((want == 1 && lat < -0.2 * tolerance) || (want == 2 && lat > 0.2 * tolerance)) {
                report.add(CC_SEVERITY_ERROR, CC_CHECK_SIDE_MISMATCH, role.name,
                           "role " + role.name + ": its joint lies on the character's " + side_label(want == 1 ? 2 : 1) +
                               " (facing " + (def.facing.empty() ? std::string("-z") : def.facing) + ")");
            }
        }
        if (want == 1 && !role.mirror.empty()) {
            const int32_t m = joint_index(role.mirror);
            if (m >= 0) {
                const Vec pl = point(j);
                const Vec pr = point(m);
                const Vec reflected = pr - s.left * (2.0 * (pr - s.center).dot(s.left));
                if ((reflected - pl).length() > tolerance) {
                    report.add(CC_SEVERITY_WARNING, CC_CHECK_MIRROR_ASYMMETRIC, role.name,
                               "roles " + role.name + " and " + role.mirror + " are not mirror images of each other");
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Reference-pose fitting.

// pose: a named profile pose whose directions replace the roles' own (NULL = the roles' own).
int32_t fit_reference_pose(oc::Character& def, const cr_rig* rig, const op::Profile& p, const op::NamedPose* pose = nullptr) {
    Skeleton s(rig);
    s.set_facing(def.facing.empty() ? "-z" : def.facing);
    const std::size_t n = rig->joints.size();

    std::vector<int> role_of(n, -1);
    for (const oc::RoleBinding& b : def.roles) {
        const int ri = p.find(b.role);
        const int32_t j = rig_index(rig, b.joint);
        if (ri >= 0 && j >= 0) role_of[static_cast<std::size_t>(j)] = ri;
    }

    std::vector<float> locals(n * 9);
    for (std::size_t i = 0; i < n; ++i) {
        const auto& j = rig->joints[i];
        std::memcpy(locals.data() + i * 9, j.pos, sizeof j.pos);
        std::memcpy(locals.data() + i * 9 + 3, j.rot, sizeof j.rot);
        std::memcpy(locals.data() + i * 9 + 6, j.scale, sizeof j.scale);
    }
    std::vector<float> world(n * 16);
    std::vector<float> child(n * 16);
    std::vector<int32_t> fitted;

    for (int32_t j : rig->order) {
        const int ri = role_of[static_cast<std::size_t>(j)];
        if (ri < 0) continue;
        const op::Role& role = p.roles[static_cast<std::size_t>(ri)];
        const float* direction = role.has_direction ? role.direction : nullptr;
        if (pose != nullptr) {
            for (const op::PoseDirection& d : pose->directions) {
                if (d.role == role.name) direction = d.direction;
            }
        }
        if (direction == nullptr) continue;
        if (cr_rig_pose_locals(rig, locals.data(), nullptr, 0, nullptr, nullptr, world.data(), child.data()) != CR_OK) {
            return error(CC_ERR_ARG, std::string("cc_char_fit_reference_pose: ") + cr_last_error());
        }
        double h[3];
        joint_point(rig, j, world.data(), h);
        const Vec head{h[0], h[1], h[2]};
        Vec dir;
        for (const std::string& t : role.toward) {
            const std::string id = role_joint(def, t);
            const int32_t tj = id.empty() ? -1 : rig_index(rig, id);
            if (tj < 0) continue;
            double q[3];
            joint_point(rig, tj, world.data(), q);
            dir = Vec{q[0], q[1], q[2]} - head;
            break;
        }
        if (dir.length() < 1e-9 && rig->joints[static_cast<std::size_t>(j)].kind == CR_JOINT_BONE) {
            dir = translation_of(child.data() + static_cast<std::size_t>(j) * 16) - head;
        }
        if (dir.length() < 1e-9) continue;

        const Mat3 q = rotation_between(dir.normalized(), s.to_model(direction).normalized());
        const int32_t parent = rig->joints[static_cast<std::size_t>(j)].parent;
        const Mat3 rf = parent < 0 ? identity3() : rotation_of(child.data() + static_cast<std::size_t>(parent) * 16);
        float* rot = locals.data() + static_cast<std::size_t>(j) * 9 + 3;
        const Mat3 updated = mul(mul(mul(transpose(rf), q), rf), euler_xyz(rot));
        to_euler_xyz(updated, rot);
        fitted.push_back(j);
    }

    def.reference_pose.clear();
    for (int32_t j : fitted) {
        const float* rot = locals.data() + static_cast<std::size_t>(j) * 9 + 3;
        def.reference_pose.push_back({rig->joints[static_cast<std::size_t>(j)].id, {rot[0], rot[1], rot[2]}});
    }
    return static_cast<int32_t>(fitted.size());
}


// ---------------------------------------------------------------------------------------------------------
// Mirroring (OpenMason #163, cenda/character_edit.h).

// The other side's spelling of a side token, keeping its case ("L" -> "R", "Left" -> "Right"); "" = not one.
std::string swapped_side_token(const std::string& t) {
    std::string low = t;
    for (char& ch : low) ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
    static const std::pair<const char*, const char*> kPairs[] = {
        {"l", "r"}, {"left", "right"}, {"lt", "rt"}, {"lft", "rgt"}};
    std::string out;
    for (const auto& [a, b] : kPairs) {
        if (low == a) out = b;
        else if (low == b) out = a;
    }
    if (out.empty()) return out;
    const bool upper = std::all_of(t.begin(), t.end(), [](unsigned char ch) { return !std::islower(ch); });
    const bool capital = std::isupper(static_cast<unsigned char>(t[0])) != 0;
    if (upper && t.size() > 1) {
        for (char& ch : out) ch = static_cast<char>(std::toupper(static_cast<unsigned char>(ch)));
    } else if (upper || capital) {
        out[0] = static_cast<char>(std::toupper(static_cast<unsigned char>(out[0])));
    }
    return out;
}

// A name with its side token swapped (separators _ - . space, or a trailing camelCase Left/Right); *side gets the
// side it named (1 left, 2 right). "" when it names no side.
std::string swap_side_name(const std::string& name, int* side) {
    std::size_t start = 0;
    for (std::size_t i = 0; i <= name.size(); ++i) {
        if (i == name.size() || name[i] == '_' || name[i] == '-' || name[i] == '.' || name[i] == ' ') {
            const std::string seg = name.substr(start, i - start);
            const std::string other = seg.empty() ? std::string() : swapped_side_token(seg);
            if (!other.empty()) {
                std::string low = seg;
                for (char& ch : low) ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
                *side = side_token(low);
                return name.substr(0, start) + other + name.substr(i);
            }
            start = i + 1;
        }
    }
    for (const char* suffix : {"Left", "Right"}) {
        const std::size_t n = std::strlen(suffix);
        if (name.size() > n && name.compare(name.size() - n, n, suffix) == 0) {
            *side = suffix[0] == 'L' ? 1 : 2;
            return name.substr(0, name.size() - n) + (suffix[0] == 'L' ? "Right" : "Left");
        }
    }
    return {};
}

struct Mirror {
    const cr_rig* rig;
    Skeleton s;
    Vec center;
    Vec normal;  // the character's left axis

    Mirror(const oc::Character& def, const cr_rig* r) : rig(r), s(r) {
        s.set_facing(def.facing.empty() ? "-z" : def.facing);
        normal = s.left.normalized();
        center = s.center;
        const std::string hips = role_joint(def, "hips");
        if (const int32_t h = hips.empty() ? -1 : rig_index(rig, hips); h >= 0) center = head(h);
    }

    [[nodiscard]] Vec head(int32_t j) const {
        double q[3];
        joint_point(rig, j, s.world.data(), q);
        return {q[0], q[1], q[2]};
    }
    [[nodiscard]] Vec tail(int32_t j) const {
        if (rig->joints[static_cast<std::size_t>(j)].kind != CR_JOINT_BONE) return head(j);
        return translation_of(s.child.data() + static_cast<std::size_t>(j) * 16);
    }
    [[nodiscard]] Vec reflect(const Vec& p) const { return p - normal * (2.0 * (p - center).dot(normal)); }
    [[nodiscard]] Vec reflect_dir(const Vec& v) const { return v - normal * (2.0 * v.dot(normal)); }
    [[nodiscard]] Mat3 reflect_rot(const Mat3& r) const {
        Mat3 m = identity3();
        const double n[3] = {normal.x, normal.y, normal.z};
        for (std::size_t i = 0; i < 3; ++i)
            for (std::size_t j = 0; j < 3; ++j) m[i][j] -= 2.0 * n[i] * n[j];
        return mul(mul(m, r), m);
    }

    // The joint of j's kind nearest j's reflection (head and, for bones, tail), -1 when none lies within 5% of the
    // skeleton height or the nearest is j itself (a midline joint).
    [[nodiscard]] int32_t mirror_joint(int32_t j) const {
        const int32_t kind = rig->joints[static_cast<std::size_t>(j)].kind;
        const Vec h = reflect(head(j));
        const Vec t = reflect(tail(j));
        int32_t best = -1;
        double best_d = 1e300;
        for (std::size_t k = 0; k < rig->joints.size(); ++k) {
            if (rig->joints[k].kind != kind) continue;
            const auto kk = static_cast<int32_t>(k);
            const double d = (head(kk) - h).length() + (tail(kk) - t).length();
            if (d < best_d - 1e-9 * s.height) {
                best_d = d;
                best = kk;
            }
        }
        if (best < 0 || best == j || best_d > 0.05 * s.height) return -1;
        return best;
    }
};

void mirror(oc::Character& def, const cr_rig* rig, const op::Profile& p, int from, uint32_t what, cc_report& report,
            int32_t& count) {
    Mirror m(def, rig);
    const char* from_name = from == 1 ? "left" : "right";
    auto joint_name = [&](int32_t j) {
        const auto& joint = rig->joints[static_cast<std::size_t>(j)];
        return joint.name.empty() ? joint.id : joint.name;
    };

    if ((what & CC_MIRROR_ROLES) != 0) {
        for (const op::Role& role : p.roles) {
            if (role_side(role) != from || role.mirror.empty()) continue;
            const std::string id = role_joint(def, role.name);
            if (id.empty()) continue;
            const int32_t j = rig_index(rig, id);
            if (j < 0) {
                report.add(CC_SEVERITY_WARNING, CC_CHECK_UNKNOWN_JOINT, role.name, "role " + role.name + ": its joint is not in the rig");
                continue;
            }
            const int32_t k = m.mirror_joint(j);
            if (k < 0) {
                report.add(CC_SEVERITY_WARNING, CC_CHECK_MIRROR_ASYMMETRIC, role.mirror,
                           "role " + role.mirror + ": no joint at the mirror position of " + joint_name(j));
                continue;
            }
            const std::string& kid = rig->joints[static_cast<std::size_t>(k)].id;
            if (role_joint(def, role.mirror) == kid) continue;
            bool found = false;
            for (oc::RoleBinding& b : def.roles) {
                if (b.role == role.mirror) {
                    b.joint = kid;
                    found = true;
                }
            }
            if (!found) def.roles.push_back({role.mirror, kid});
            ++count;
            report.add(CC_SEVERITY_INFO, CC_CHECK_MIRROR_ASYMMETRIC, role.mirror,
                       "role " + role.mirror + " -> " + joint_name(k) + " (mirror of " + role.name + ")");
        }
    }

    if ((what & CC_MIRROR_LIMITS) != 0) {
        // Joint frames in the reference pose.
        const std::size_t n = rig->joints.size();
        std::vector<float> locals(n * 9);
        std::vector<float> world(n * 16);
        reference_locals(def, rig, locals.data());
        if (cr_rig_pose_locals(rig, locals.data(), nullptr, 0, nullptr, nullptr, world.data(), nullptr) != CR_OK) {
            throw std::runtime_error(std::string("cc_char_mirror: reference pose: ") + cr_last_error());
        }
        for (const op::Role& role : p.roles) {
            if (role_side(role) != from || role.mirror.empty()) continue;
            const std::string src = role_joint(def, role.name);
            const std::string dst = role_joint(def, role.mirror);
            if (src.empty() || dst.empty()) continue;
            const auto lim = std::find_if(def.limits.begin(), def.limits.end(), [&](const oc::JointLimit& l) { return l.joint == src; });
            if (lim == def.limits.end()) continue;
            const int32_t j = rig_index(rig, src);
            const int32_t k = rig_index(rig, dst);
            if (j < 0 || k < 0) continue;
            const Mat3 a = rotation_of(world.data() + static_cast<std::size_t>(j) * 16);
            const Mat3 b = rotation_of(world.data() + static_cast<std::size_t>(k) * 16);
            const float smin[3] = {lim->min.x, lim->min.y, lim->min.z};
            const float smax[3] = {lim->max.x, lim->max.y, lim->max.z};
            float dmin[3]{};
            float dmax[3]{};
            bool used[3] = {false, false, false};
            bool ok = true;
            for (std::size_t i = 0; i < 3 && ok; ++i) {
                const Vec d = m.reflect_dir(Vec{a[0][i], a[1][i], a[2][i]});
                std::size_t best = 0;
                double best_dot = 0;
                for (std::size_t c = 0; c < 3; ++c) {
                    const double dot = d.dot(Vec{b[0][c], b[1][c], b[2][c]});
                    if (std::abs(dot) > std::abs(best_dot)) {
                        best_dot = dot;
                        best = c;
                    }
                }
                if (std::abs(best_dot) < 0.9 || used[best]) {
                    ok = false;
                    break;
                }
                used[best] = true;
                // M R(a, t) M = R(Ma, -t) and Ma = s e: the angle about e is -s t.
                const float sgn = best_dot > 0 ? -1.f : 1.f;
                const float x0 = sgn * smin[i];
                const float x1 = sgn * smax[i];
                dmin[best] = std::min(x0, x1);
                dmax[best] = std::max(x0, x1);
            }
            if (!ok) {
                report.add(CC_SEVERITY_WARNING, CC_CHECK_MIRROR_ASYMMETRIC, role.mirror,
                           "limits of " + role.name + ": the joints' axes are not mirror images; not copied");
                continue;
            }
            const oc::JointLimit copy{dst, {dmin[0], dmin[1], dmin[2]}, {dmax[0], dmax[1], dmax[2]}};
            auto it = std::find_if(def.limits.begin(), def.limits.end(), [&](const oc::JointLimit& l) { return l.joint == dst; });
            if (it != def.limits.end()) *it = copy;
            else def.limits.push_back(copy);
            ++count;
            report.add(CC_SEVERITY_INFO, CC_CHECK_MIRROR_ASYMMETRIC, role.mirror, "limits of " + role.name + " copied to " + role.mirror);
        }
    }

    if ((what & CC_MIRROR_SOCKETS) != 0) {
        const std::vector<oc::Socket> sockets = def.sockets;
        for (const oc::Socket& sock : sockets) {
            int side = 0;
            const std::string name = swap_side_name(sock.name, &side);
            if (name.empty() || side != from) continue;
            const int32_t j = rig_index(rig, sock.joint);
            if (j < 0) continue;
            // The mirror role's joint when the socket's joint plays a sided role, else the reflected joint.
            int32_t k = -1;
            for (const oc::RoleBinding& b : def.roles) {
                if (b.joint != sock.joint) continue;
                const int ri = p.find(b.role);
                if (ri < 0 || p.roles[static_cast<std::size_t>(ri)].mirror.empty()) continue;
                const std::string mj = role_joint(def, p.roles[static_cast<std::size_t>(ri)].mirror);
                if (!mj.empty()) k = rig_index(rig, mj);
            }
            if (k < 0) k = m.mirror_joint(j);
            if (k < 0) {
                report.add(CC_SEVERITY_WARNING, CC_CHECK_MIRROR_ASYMMETRIC, name,
                           "socket " + sock.name + ": no joint at the mirror position of " + joint_name(j));
                continue;
            }
            oc::Socket out = sock;
            out.name = name;
            out.joint = rig->joints[static_cast<std::size_t>(k)].id;
            const Vec pos = m.reflect(Vec{sock.pos.x, sock.pos.y, sock.pos.z});
            out.pos = {static_cast<float>(pos.x), static_cast<float>(pos.y), static_cast<float>(pos.z)};
            const float rot[3] = {sock.rot.x, sock.rot.y, sock.rot.z};
            float r2[3];
            to_euler_xyz(m.reflect_rot(euler_xyz(rot)), r2);
            out.rot = {r2[0], r2[1], r2[2]};
            auto it = std::find_if(def.sockets.begin(), def.sockets.end(), [&](const oc::Socket& x) { return x.name == name; });
            if (it != def.sockets.end()) *it = out;
            else def.sockets.push_back(out);
            ++count;
            report.add(CC_SEVERITY_INFO, CC_CHECK_MIRROR_ASYMMETRIC, name, "socket " + name + " mirrored from " + sock.name);
        }
    }
    (void)from_name;
}

// ---------------------------------------------------------------------------------------------------------
// A profile from a rig.

op::Profile profile_from_rig(const cr_rig* rig, const oc::Character* def, const std::string& id) {
    Skeleton s(rig);
    s.set_facing(def != nullptr && !def->facing.empty() ? def->facing : "-z");
    s.assign_sides();

    op::Profile p;
    p.id = id.empty() ? "custom" : id;
    p.name = p.id;
    p.description = "Exported from a skeleton.";

    // Role names from joint names (lower case, '_' separated, unique), in parent-first order.
    std::vector<std::string> role_name(s.cands.size());
    std::unordered_set<std::string> taken;
    std::vector<int> order;
    for (int32_t j : rig->order) {
        const int k = s.cand_of_joint[static_cast<std::size_t>(j)];
        if (k >= 0) order.push_back(k);
    }
    for (int k : order) {
        std::string base;
        for (const std::string& t : s.cands[static_cast<std::size_t>(k)].all_tokens) base += (base.empty() ? "" : "_") + t;
        if (base.empty()) base = "joint";
        std::string name = base;
        for (int i = 2; taken.contains(name); ++i) name = base + "_" + std::to_string(i);
        taken.insert(name);
        role_name[static_cast<std::size_t>(k)] = name;
    }
    auto parent_cand = [&](int k) {
        for (int32_t a = s.rig->joints[static_cast<std::size_t>(s.cands[static_cast<std::size_t>(k)].joint)].parent; a >= 0;
             a = s.rig->joints[static_cast<std::size_t>(a)].parent) {
            if (s.cand_of_joint[static_cast<std::size_t>(a)] >= 0) return s.cand_of_joint[static_cast<std::size_t>(a)];
        }
        return -1;
    };
    std::vector<std::vector<int>> children(s.cands.size());
    for (int k : order) {
        if (const int pk = parent_cand(k); pk >= 0) children[static_cast<std::size_t>(pk)].push_back(k);
    }
    // Mirror partners: same tokens, opposite anatomical sides.
    std::map<std::pair<std::vector<std::string>, int>, std::vector<int>> by_base;
    for (int k : order) {
        const Candidate& c = s.cands[static_cast<std::size_t>(k)];
        if (c.side != 0) by_base[{c.tokens, c.side}].push_back(k);
    }

    for (int k : order) {
        const Candidate& c = s.cands[static_cast<std::size_t>(k)];
        op::Role r;
        r.name = role_name[static_cast<std::size_t>(k)];
        if (const int pk = parent_cand(k); pk >= 0) r.parent = role_name[static_cast<std::size_t>(pk)];
        r.required = true;
        r.side = c.side == 1 ? op::Side::Left : c.side == 2 ? op::Side::Right : op::Side::Center;
        if (c.side != 0) {
            const auto& mine = by_base[{c.tokens, c.side}];
            const auto& theirs = by_base[{c.tokens, c.side == 1 ? 2 : 1}];
            if (mine.size() == 1 && theirs.size() == 1) r.mirror = role_name[static_cast<std::size_t>(theirs[0])];
        }
        Vec dir;
        const auto& kids = children[static_cast<std::size_t>(k)];
        if (kids.size() == 1) {
            r.toward.push_back(role_name[static_cast<std::size_t>(kids[0])]);
            dir = s.cands[static_cast<std::size_t>(kids[0])].point - c.point;
        }
        if (dir.length() < 1e-9) dir = bone_direction(s, c.joint);
        if (dir.length() > 1e-9) {
            const Vec d = s.to_profile(dir).normalized();
            r.direction[0] = static_cast<float>(d.x);
            r.direction[1] = static_cast<float>(d.y);
            r.direction[2] = static_cast<float>(d.z);
            r.has_direction = true;
        }
        if (!c.tokens.empty()) {
            op::Alias alias;
            alias.tokens = c.tokens;
            r.match.push_back(std::move(alias));
        }
        p.roles.push_back(std::move(r));
    }
    // Round trip through the codec: the same validation a file gets.
    return op::read(op::write(p));
}

}  // namespace

extern "C" {

cc_automap* cc_char_auto_map(cc_char* c, const cr_rig* rig, const cc_profile* profile) {
    if (c == nullptr || rig == nullptr || profile == nullptr) {
        error(CC_ERR_ARG, "cc_char_auto_map: null argument");
        return nullptr;
    }
    if (c->def.locked) {
        error(CC_ERR_LOCKED, "cc_char_auto_map: the role mapping is locked (cc_char_unlock first)");
        return nullptr;
    }
    CC_GUARD_BEGIN
    clear_error();
    auto* result = new cc_automap();
    try {
        auto_map(c->def, rig, profile->profile, *result);
    } catch (...) {
        delete result;
        throw;
    }
    return result;
    CC_GUARD_END(nullptr)
}

int32_t cc_automap_count(const cc_automap* m) { return m == nullptr ? 0 : static_cast<int32_t>(m->matches.size()); }

int32_t cc_automap_get(const cc_automap* m, int32_t index, const char** out_role, const char** out_joint,
                       float* out_confidence) {
    if (m == nullptr) return error(CC_ERR_ARG, "cc_automap_get: null result");
    if (!in_range(m->matches, index)) return error(CC_ERR_RANGE, "cc_automap_get: index out of range");
    const auto& match = m->matches[static_cast<std::size_t>(index)];
    set_out(out_role, match.role.c_str());
    set_out(out_joint, match.joint.c_str());
    set_out(out_confidence, match.confidence);
    return CC_OK;
}

const cc_report* cc_automap_notes(const cc_automap* m) { return m == nullptr ? nullptr : &m->notes; }

void cc_automap_destroy(cc_automap* m) { delete m; }

cc_report* cc_char_validate(const cc_char* c, const cr_rig* rig, const cc_profile* profile) {
    if (c == nullptr || profile == nullptr) {
        error(CC_ERR_ARG, "cc_char_validate: null argument");
        return nullptr;
    }
    CC_GUARD_BEGIN
    cc_report* report = cc_char_check(c, rig);
    if (report == nullptr) return nullptr;
    try {
        validate(c->def, rig, profile->profile, *report);
    } catch (...) {
        delete report;
        throw;
    }
    return report;
    CC_GUARD_END(nullptr)
}

int32_t cc_char_fit_reference_pose(cc_char* c, const cr_rig* rig, const cc_profile* profile) {
    if (c == nullptr || rig == nullptr || profile == nullptr) return error(CC_ERR_ARG, "cc_char_fit_reference_pose: null argument");
    if (c->def.locked) return error(CC_ERR_LOCKED, "cc_char_fit_reference_pose: the role mapping is locked (cc_char_unlock first)");
    CC_GUARD_BEGIN
    clear_error();
    return fit_reference_pose(c->def, rig, profile->profile);
    CC_GUARD_END(CC_ERR_ARG)
}

int32_t cc_char_fit_reference_pose_named(cc_char* c, const cr_rig* rig, const cc_profile* profile, const char* pose) {
    if (c == nullptr || rig == nullptr || profile == nullptr) {
        return error(CC_ERR_ARG, "cc_char_fit_reference_pose_named: null argument");
    }
    if (c->def.locked) {
        return error(CC_ERR_LOCKED, "cc_char_fit_reference_pose_named: the role mapping is locked (cc_char_unlock first)");
    }
    CC_GUARD_BEGIN
    clear_error();
    const op::Profile& p = profile->profile;
    const op::NamedPose* named = nullptr;
    if (pose != nullptr && pose[0] != '\0') {
        const int pi = p.find_pose(pose);
        if (pi >= 0) {
            named = &p.poses[static_cast<std::size_t>(pi)];
        } else if (std::string_view(pose) != "T") {
            return error(CC_ERR_RANGE, std::string("cc_char_fit_reference_pose_named: profile ") + p.id + " has no pose " + pose);
        }
    }
    return fit_reference_pose(c->def, rig, p, named);
    CC_GUARD_END(CC_ERR_ARG)
}

cc_report* cc_char_mirror(cc_char* c, const cr_rig* rig, const cc_profile* profile, int32_t from_side, uint32_t what,
                          int32_t* out_count) {
    set_out(out_count, 0);
    if (c == nullptr || rig == nullptr || profile == nullptr) {
        error(CC_ERR_ARG, "cc_char_mirror: null argument");
        return nullptr;
    }
    if (from_side != CC_SIDE_LEFT && from_side != CC_SIDE_RIGHT) {
        error(CC_ERR_ARG, "cc_char_mirror: from_side must be CC_SIDE_LEFT or CC_SIDE_RIGHT");
        return nullptr;
    }
    if ((what & CC_MIRROR_ROLES) != 0 && c->def.locked) {
        error(CC_ERR_LOCKED, "cc_char_mirror: the role mapping is locked (cc_char_unlock first)");
        return nullptr;
    }
    CC_GUARD_BEGIN
    clear_error();
    auto* report = new cc_report();
    int32_t count = 0;
    try {
        mirror(c->def, rig, profile->profile, from_side, what, *report, count);
    } catch (...) {
        delete report;
        throw;
    }
    set_out(out_count, count);
    return report;
    CC_GUARD_END(nullptr)
}

int32_t cc_char_lock(cc_char* c, const cr_rig* rig, const cc_profile* profile) {
    if (c == nullptr || profile == nullptr) return error(CC_ERR_ARG, "cc_char_lock: null argument");
    CC_GUARD_BEGIN
    clear_error();
    if (c->def.locked) return CC_OK;
    cc_report* report = cc_char_validate(c, rig, profile);
    if (report == nullptr) return CC_ERR_ARG;
    for (const auto& item : report->items) {
        if (item.severity == CC_SEVERITY_ERROR) {
            const std::string message = "cc_char_lock: " + item.message;
            delete report;
            return error(CC_ERR_INVALID, message);
        }
    }
    delete report;
    c->def.locked = true;
    return CC_OK;
    CC_GUARD_END(CC_ERR_ARG)
}

int32_t cc_char_unlock(cc_char* c) {
    if (c == nullptr) return error(CC_ERR_ARG, "cc_char_unlock: null definition");
    c->def.locked = false;
    return CC_OK;
}

int32_t cc_char_locked(const cc_char* c) { return c != nullptr && c->def.locked ? 1 : 0; }

cc_profile* cc_profile_from_rig(const cr_rig* rig, const cc_char* c, const char* id) {
    if (rig == nullptr) {
        error(CC_ERR_ARG, "cc_profile_from_rig: null rig");
        return nullptr;
    }
    CC_GUARD_BEGIN
    clear_error();
    return new cc_profile{profile_from_rig(rig, c == nullptr ? nullptr : &c->def, id == nullptr ? "" : id)};
    CC_GUARD_END(nullptr)
}

}  // extern "C"
