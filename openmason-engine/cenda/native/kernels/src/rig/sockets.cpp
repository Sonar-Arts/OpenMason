// Sockets and mounts (Stonebreak #459): named frames on a rig and items hung on them. See socket.h.

#include "cenda/socket.h"

#include "rig_internal.hpp"

#include <cmath>
#include <cstring>
#include <new>
#include <string>
#include <vector>

using cenda::rig::Mat4;
using cenda::rig::set_error;

namespace {

struct Socket {
    std::string name;
    std::string key;  // lower-case name
    int32_t joint = CR_SOCKET_ROOT;
    int32_t source = CR_SOCKET_OMO;
    Mat4 local = Mat4::identity();
};

struct Point {
    std::string name;
    std::string key;
    Mat4 local = Mat4::identity();
};

struct Mount {
    std::string name;
    std::string key;
    int32_t socket = -1;
    Mat4 item = Mat4::identity();  // offset · attach^-1
    std::vector<Point> points;
};

bool finite_n(const float* v, int n) {
    if (v == nullptr) return true;
    for (int i = 0; i < n; ++i) {
        if (!std::isfinite(v[i])) return false;
    }
    return true;
}

// T(pos) · Rx · Ry · Rz · S(scale): JOML translate(pos).rotateXYZ(rad).scale(s), in double.
Mat4 trs(const float* pos, const float* rot_deg, const float* scale) {
    Mat4 m = Mat4::identity();
    double r[9];
    cenda::rig::rot_xyz(rot_deg != nullptr ? rot_deg[0] * cenda::rig::DEG_TO_RAD : 0.0,
                        rot_deg != nullptr ? rot_deg[1] * cenda::rig::DEG_TO_RAD : 0.0,
                        rot_deg != nullptr ? rot_deg[2] * cenda::rig::DEG_TO_RAD : 0.0, r);
    const double s[3] = {scale != nullptr ? scale[0] : 1.0, scale != nullptr ? scale[1] : 1.0,
                         scale != nullptr ? scale[2] : 1.0};
    for (int row = 0; row < 3; ++row) {
        for (int col = 0; col < 3; ++col) m.at(row, col) = r[row * 3 + col] * s[col];
        m.at(row, 3) = pos != nullptr ? pos[row] : 0.0;
    }
    return m;
}

Mat4 mat_or_identity(const float* f) {
    return f != nullptr ? Mat4::from_float(f) : Mat4::identity();
}

void write_zero(float* out) {
    std::memset(out, 0, 16 * sizeof(float));
}

}  // namespace

struct cr_sockets {
    const cr_rig* rig = nullptr;
    std::vector<Socket> sockets;
};

struct cr_mounts {
    std::vector<Mount> mounts;
};

namespace {

int32_t find_key(const std::vector<Socket>& v, const std::string& key) {
    for (size_t i = 0; i < v.size(); ++i) {
        if (v[i].key == key) return static_cast<int32_t>(i);
    }
    return -1;
}

int32_t add_socket(cr_sockets* set, const char* name, int32_t joint, const float* pos, const float* rot_deg,
                   const float* scale, int32_t source) {
    if (set == nullptr || name == nullptr || name[0] == '\0') {
        set_error("cr_sockets_add: null set or empty name");
        return CR_ERR_ARG;
    }
    if (source < CR_SOCKET_OMO || source > CR_SOCKET_HOST) {
        set_error("cr_sockets_add: bad source");
        return CR_ERR_ARG;
    }
    const int32_t joints = set->rig != nullptr ? static_cast<int32_t>(set->rig->joints.size()) : 0;
    if (joint != CR_SOCKET_ROOT && (joint < 0 || joint >= joints)) {
        set_error("cr_sockets_add: joint " + std::to_string(joint) + " is not in the rig");
        return CR_ERR_ARG;
    }
    if (!finite_n(pos, 3) || !finite_n(rot_deg, 3) || !finite_n(scale, 3)) {
        set_error("cr_sockets_add: non-finite pos / rot / scale");
        return CR_ERR_ARG;
    }
    Socket s;
    s.name = name;
    s.key = cenda::rig::lower(s.name);
    s.joint = joint;
    s.source = source;
    s.local = trs(pos, rot_deg, scale);
    const int32_t existing = find_key(set->sockets, s.key);
    if (existing >= 0) {
        Socket& old = set->sockets[static_cast<size_t>(existing)];
        if (source >= old.source) old = std::move(s);
        return existing;
    }
    set->sockets.push_back(std::move(s));
    return static_cast<int32_t>(set->sockets.size() - 1);
}

int32_t find_mount(const cr_mounts* mounts, const std::string& key) {
    for (size_t i = 0; i < mounts->mounts.size(); ++i) {
        if (mounts->mounts[i].key == key) return static_cast<int32_t>(i);
    }
    return -1;
}

bool valid_mount(const cr_mounts* mounts, int32_t mount) {
    return mounts != nullptr && mount >= 0 && static_cast<size_t>(mount) < mounts->mounts.size();
}

// The socket frame (double) on a palette, or false when it cannot resolve.
bool socket_frame(const cr_sockets* set, int32_t index, const float* skin, int32_t joint_count, const Mat4& pre,
                  const Mat4& root, Mat4& out) {
    if (index < 0 || static_cast<size_t>(index) >= set->sockets.size()) return false;
    const Socket& s = set->sockets[static_cast<size_t>(index)];
    if (s.joint == CR_SOCKET_ROOT) {
        out = root * s.local;
        return true;
    }
    if (skin == nullptr || s.joint >= joint_count) return false;
    out = pre * Mat4::from_float(skin + static_cast<size_t>(s.joint) * 16) * s.local;
    return true;
}

}  // namespace

extern "C" {

cr_sockets* cr_sockets_create(const cr_rig* rig) {
    cr_sockets* set = new (std::nothrow) cr_sockets();
    if (set == nullptr) {
        set_error("cr_sockets_create: out of memory");
        return nullptr;
    }
    set->rig = rig;
    return set;
}

void cr_sockets_destroy(cr_sockets* set) {
    delete set;
}

int32_t cr_sockets_add(cr_sockets* set, const char* name, int32_t joint, const float* pos, const float* rot_deg,
                       const float* scale, int32_t source) {
    return add_socket(set, name, joint, pos, rot_deg, scale, source);
}

int32_t cr_sockets_add_ref(cr_sockets* set, const char* name, const char* joint_id, const char* joint_name,
                           const float* pos, const float* rot_deg, const float* scale, int32_t source) {
    if (set == nullptr) {
        set_error("cr_sockets_add_ref: null set");
        return CR_ERR_ARG;
    }
    const bool has_id = joint_id != nullptr && joint_id[0] != '\0';
    const bool has_name = joint_name != nullptr && joint_name[0] != '\0';
    if (!has_id && !has_name) return add_socket(set, name, CR_SOCKET_ROOT, pos, rot_deg, scale, source);
    int32_t joint = -1;
    if (set->rig != nullptr) {
        if (has_id) {
            if (auto it = set->rig->by_id.find(joint_id); it != set->rig->by_id.end()) joint = it->second;
        }
        if (joint < 0 && has_name) {
            if (auto it = set->rig->by_name.find(joint_name); it != set->rig->by_name.end()) joint = it->second;
        }
    }
    if (joint < 0) {
        set_error(std::string("cr_sockets_add_ref: socket ") + (name != nullptr ? name : "") +
                  ": its joint is not in the rig");
        return CR_ERR_RANGE;
    }
    return add_socket(set, name, joint, pos, rot_deg, scale, source);
}

int32_t cr_sockets_count(const cr_sockets* set) {
    return set != nullptr ? static_cast<int32_t>(set->sockets.size()) : 0;
}

int32_t cr_sockets_find(const cr_sockets* set, const char* name) {
    if (set == nullptr || name == nullptr) return -1;
    return find_key(set->sockets, cenda::rig::lower(name));
}

int32_t cr_sockets_get(const cr_sockets* set, int32_t index, const char** out_name, int32_t* out_joint,
                       int32_t* out_source, float* out_local) {
    if (set == nullptr || index < 0 || static_cast<size_t>(index) >= set->sockets.size()) {
        set_error("cr_sockets_get: bad index");
        return CR_ERR_ARG;
    }
    const Socket& s = set->sockets[static_cast<size_t>(index)];
    if (out_name != nullptr) *out_name = s.name.c_str();
    if (out_joint != nullptr) *out_joint = s.joint;
    if (out_source != nullptr) *out_source = s.source;
    if (out_local != nullptr) s.local.to_float(out_local);
    return CR_OK;
}

int32_t cr_sockets_frame(const cr_sockets* set, int32_t index, const float* skin, int32_t joint_count,
                         const float* pre, const float* root, float* out) {
    if (set == nullptr || out == nullptr || index < 0 || static_cast<size_t>(index) >= set->sockets.size()
        || joint_count < 0) {
        set_error("cr_sockets_frame: bad arguments");
        return CR_ERR_ARG;
    }
    const Mat4 p = mat_or_identity(pre);
    const Mat4 r = root != nullptr ? Mat4::from_float(root) : p;
    Mat4 frame;
    if (!socket_frame(set, index, skin, joint_count, p, r, frame)) {
        set_error("cr_sockets_frame: the socket's joint is outside the palette");
        return CR_ERR_RANGE;
    }
    frame.to_float(out);
    return CR_OK;
}

int32_t cc_char_sockets_into(const cc_char* c, cr_sockets* set, int32_t* out_unresolved) {
    if (out_unresolved != nullptr) *out_unresolved = 0;
    if (c == nullptr || set == nullptr || set->rig == nullptr) {
        set_error("cc_char_sockets_into: null character, set or rig");
        return CR_ERR_ARG;
    }
    const int32_t n = cc_char_socket_count(c);
    int32_t added = 0;
    int32_t unresolved = 0;
    for (int32_t i = 0; i < n; ++i) {
        const char* name = nullptr;
        const char* joint_id = nullptr;
        float pos[3], rot[3], scale[3];
        if (cc_char_socket_get(c, i, &name, &joint_id, pos, rot, scale) != 0) continue;
        const int32_t joint = cr_rig_find(set->rig, joint_id);
        if (joint < 0) {
            ++unresolved;
            continue;
        }
        if (add_socket(set, name, joint, pos, rot, scale, CR_SOCKET_CHARACTER) >= 0) ++added;
    }
    if (out_unresolved != nullptr) *out_unresolved = unresolved;
    return added;
}

cr_mounts* cr_mounts_create(void) {
    cr_mounts* m = new (std::nothrow) cr_mounts();
    if (m == nullptr) set_error("cr_mounts_create: out of memory");
    return m;
}

void cr_mounts_destroy(cr_mounts* mounts) {
    delete mounts;
}

int32_t cr_mounts_set(cr_mounts* mounts, const char* name, int32_t socket, const float* offset, const float* attach) {
    if (mounts == nullptr || name == nullptr || name[0] == '\0' || !finite_n(offset, 16) || !finite_n(attach, 16)) {
        set_error("cr_mounts_set: null mounts, empty name or non-finite matrix");
        return CR_ERR_ARG;
    }
    Mount m;
    m.name = name;
    m.key = cenda::rig::lower(m.name);
    m.socket = socket < 0 ? -1 : socket;
    m.item = mat_or_identity(offset);
    if (attach != nullptr) m.item = m.item * Mat4::from_float(attach).affine_inverse();
    const int32_t existing = find_mount(mounts, m.key);
    if (existing >= 0) {
        mounts->mounts[static_cast<size_t>(existing)] = std::move(m);
        return existing;
    }
    mounts->mounts.push_back(std::move(m));
    return static_cast<int32_t>(mounts->mounts.size() - 1);
}

int32_t cr_mounts_remove(cr_mounts* mounts, const char* name) {
    if (mounts == nullptr || name == nullptr) {
        set_error("cr_mounts_remove: null argument");
        return CR_ERR_ARG;
    }
    const int32_t i = find_mount(mounts, cenda::rig::lower(name));
    if (i < 0) {
        set_error(std::string("cr_mounts_remove: no mount ") + name);
        return CR_ERR_RANGE;
    }
    mounts->mounts.erase(mounts->mounts.begin() + i);
    return CR_OK;
}

int32_t cr_mounts_clear(cr_mounts* mounts) {
    if (mounts == nullptr) {
        set_error("cr_mounts_clear: null mounts");
        return CR_ERR_ARG;
    }
    mounts->mounts.clear();
    return CR_OK;
}

int32_t cr_mounts_count(const cr_mounts* mounts) {
    return mounts != nullptr ? static_cast<int32_t>(mounts->mounts.size()) : 0;
}

int32_t cr_mounts_find(const cr_mounts* mounts, const char* name) {
    if (mounts == nullptr || name == nullptr) return -1;
    return find_mount(mounts, cenda::rig::lower(name));
}

int32_t cr_mounts_get(const cr_mounts* mounts, int32_t index, const char** out_name, int32_t* out_socket) {
    if (!valid_mount(mounts, index)) {
        set_error("cr_mounts_get: bad index");
        return CR_ERR_ARG;
    }
    const Mount& m = mounts->mounts[static_cast<size_t>(index)];
    if (out_name != nullptr) *out_name = m.name.c_str();
    if (out_socket != nullptr) *out_socket = m.socket;
    return CR_OK;
}

int32_t cr_mounts_set_point(cr_mounts* mounts, int32_t mount, const char* name, const float* local) {
    if (!valid_mount(mounts, mount) || name == nullptr || name[0] == '\0' || !finite_n(local, 16)) {
        set_error("cr_mounts_set_point: bad mount, empty name or non-finite matrix");
        return CR_ERR_ARG;
    }
    Mount& m = mounts->mounts[static_cast<size_t>(mount)];
    Point p;
    p.name = name;
    p.key = cenda::rig::lower(p.name);
    p.local = mat_or_identity(local);
    for (size_t i = 0; i < m.points.size(); ++i) {
        if (m.points[i].key == p.key) {
            m.points[i] = std::move(p);
            return static_cast<int32_t>(i);
        }
    }
    m.points.push_back(std::move(p));
    return static_cast<int32_t>(m.points.size() - 1);
}

int32_t cr_mounts_point_count(const cr_mounts* mounts, int32_t mount) {
    if (!valid_mount(mounts, mount)) return 0;
    return static_cast<int32_t>(mounts->mounts[static_cast<size_t>(mount)].points.size());
}

int32_t cr_mounts_find_point(const cr_mounts* mounts, int32_t mount, const char* name) {
    if (!valid_mount(mounts, mount) || name == nullptr) return -1;
    const std::string key = cenda::rig::lower(name);
    const Mount& m = mounts->mounts[static_cast<size_t>(mount)];
    for (size_t i = 0; i < m.points.size(); ++i) {
        if (m.points[i].key == key) return static_cast<int32_t>(i);
    }
    return -1;
}

int32_t cr_mounts_total_points(const cr_mounts* mounts) {
    if (mounts == nullptr) return 0;
    size_t n = 0;
    for (const Mount& m : mounts->mounts) n += m.points.size();
    return static_cast<int32_t>(n);
}

int32_t cr_mounts_point_base(const cr_mounts* mounts, int32_t mount) {
    if (!valid_mount(mounts, mount)) return -1;
    size_t n = 0;
    for (int32_t i = 0; i < mount; ++i) n += mounts->mounts[static_cast<size_t>(i)].points.size();
    return static_cast<int32_t>(n);
}

int32_t cr_mounts_resolve(const cr_mounts* mounts, const cr_sockets* sockets, const float* skin, int32_t joint_count,
                          const float* pre, const float* root, float* out_frames, float* out_points,
                          uint8_t* out_valid) {
    if (mounts == nullptr || sockets == nullptr || joint_count < 0
        || (out_frames == nullptr && !mounts->mounts.empty())) {
        set_error("cr_mounts_resolve: bad arguments");
        return CR_ERR_ARG;
    }
    const Mat4 p = mat_or_identity(pre);
    const Mat4 r = root != nullptr ? Mat4::from_float(root) : p;
    int32_t resolved = 0;
    size_t point = 0;
    for (size_t i = 0; i < mounts->mounts.size(); ++i) {
        const Mount& m = mounts->mounts[i];
        Mat4 frame;
        const bool ok = socket_frame(sockets, m.socket, skin, joint_count, p, r, frame);
        float* f = out_frames + i * 16;
        if (ok) {
            frame = frame * m.item;
            frame.to_float(f);
            ++resolved;
        } else {
            write_zero(f);
        }
        if (out_valid != nullptr) out_valid[i] = ok ? 1 : 0;
        for (const Point& pt : m.points) {
            if (out_points != nullptr) {
                if (ok) {
                    (frame * pt.local).to_float(out_points + point * 16);
                } else {
                    write_zero(out_points + point * 16);
                }
            }
            ++point;
        }
    }
    return resolved;
}

}  // extern "C"
