// import_omo ctest (OpenMason #173, writer half): glTF / OBJ -> OMO 1.9 + OMANIM through co_import_*.
// Round trip through omo::read, the OMO layout rules, rest pose (bone worlds through the native rig vs the glTF
// joints, skinned vertices vs the glTF skinning equation), posing (imported clips through cr_rig_pose vs the glTF
// animation evaluated here, independently), animation key reduction, influences, morphs, vertex groups, materials,
// UVs, texture downscale, quad merge, OBJ polygons and shading, options, malformed inputs. Plain CHECKs.

#include "cenda/model_import.h"
#include "cenda/rig.h"
#include "omformat/import.hpp"
#include "omformat/omanim.hpp"
#include "omformat/omo.hpp"
// libomformat's private pixel decoder (stb_image), to read the importer's PNGs back.
#include "../libomformat/src/import/image_resize.hpp"

#include <algorithm>
#include <array>
#include <climits>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <map>
#include <optional>
#include <random>
#include <set>
#include <string>
#include <string_view>
#include <vector>

namespace {

int failures = 0;

#define CHECK(cond)                                                                       \
    do {                                                                                  \
        if (!(cond)) {                                                                    \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); \
            ++failures;                                                                   \
        }                                                                                 \
    } while (0)

#define CHECK_NEAR(a, b, tol)                                                                                   \
    do {                                                                                                        \
        const double ca_ = (a), cb_ = (b);                                                                      \
        if (!(std::fabs(ca_ - cb_) <= (tol))) {                                                                 \
            std::fprintf(stderr, "%s:%d: CHECK_NEAR failed: %s = %.9g vs %s = %.9g\n", __FILE__, __LINE__, #a, \
                         ca_, #b, cb_);                                                                         \
            ++failures;                                                                                         \
        }                                                                                                       \
    } while (0)

namespace fs = std::filesystem;
namespace imp = cenda::omformat::import;
namespace omo = cenda::omformat::omo;
namespace oa = cenda::omformat::omanim;

const fs::path kA3 = CENDA_IMPORT_FIXTURES;  // tests/fixtures/import
const fs::path kOwn = fs::path(CENDA_IMPORT_FIXTURES).parent_path() / "import_omo";

std::vector<std::byte> load(const fs::path& p) {
    std::ifstream in(p, std::ios::binary);
    std::vector<char> raw((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    std::vector<std::byte> out(raw.size());
    if (!raw.empty()) std::memcpy(out.data(), raw.data(), raw.size());
    return out;
}

std::size_t uz(int v) { return static_cast<std::size_t>(v); }

// ---- independent double math (column-major) -------------------------------------------------------------------

struct V3 {
    double x = 0, y = 0, z = 0;
};
V3 operator+(V3 a, V3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
V3 operator-(V3 a, V3 b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
V3 operator*(V3 a, double s) { return {a.x * s, a.y * s, a.z * s}; }
double dot(V3 a, V3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
V3 cross(V3 a, V3 b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
double len(V3 a) { return std::sqrt(dot(a, a)); }
double dist(V3 a, V3 b) { return len(a - b); }

using M = std::array<double, 16>;
M ident() { return {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}; }
M mul(const M& a, const M& b) {
    M r{};
    for (std::size_t c = 0; c < 4; ++c) {
        for (std::size_t rr = 0; rr < 4; ++rr) {
            double s = 0;
            for (std::size_t k = 0; k < 4; ++k) s += a[k * 4 + rr] * b[c * 4 + k];
            r[c * 4 + rr] = s;
        }
    }
    return r;
}
M of(const std::array<float, 16>& f) {
    M r{};
    for (std::size_t i = 0; i < 16; ++i) r[i] = f[i];
    return r;
}
M of(const float* f) {
    M r{};
    for (std::size_t i = 0; i < 16; ++i) r[i] = f[i];
    return r;
}
M inv(const M& m) {  // Gauss-Jordan
    double a[4][8];
    for (std::size_t r = 0; r < 4; ++r) {
        for (std::size_t c = 0; c < 4; ++c) a[r][c] = m[c * 4 + r];
        for (std::size_t c = 0; c < 4; ++c) a[r][4 + c] = r == c ? 1 : 0;
    }
    for (std::size_t col = 0; col < 4; ++col) {
        std::size_t piv = col;
        for (std::size_t r = col + 1; r < 4; ++r) {
            if (std::fabs(a[r][col]) > std::fabs(a[piv][col])) piv = r;
        }
        for (std::size_t c = 0; c < 8; ++c) std::swap(a[col][c], a[piv][c]);
        const double p = a[col][col];
        for (std::size_t c = 0; c < 8; ++c) a[col][c] /= p;
        for (std::size_t r = 0; r < 4; ++r) {
            if (r == col) continue;
            const double f = a[r][col];
            for (std::size_t c = 0; c < 8; ++c) a[r][c] -= f * a[col][c];
        }
    }
    M r{};
    for (std::size_t rr = 0; rr < 4; ++rr) {
        for (std::size_t c = 0; c < 4; ++c) r[c * 4 + rr] = a[rr][4 + c];
    }
    return r;
}
V3 pt(const M& m, V3 p) {
    return {m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12], m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13],
            m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14]};
}
V3 vec(const M& m, V3 p) {
    return {m[0] * p.x + m[4] * p.y + m[8] * p.z, m[1] * p.x + m[5] * p.y + m[9] * p.z,
            m[2] * p.x + m[6] * p.y + m[10] * p.z};
}
double max_diff(const M& a, const M& b) {
    double e = 0;
    for (std::size_t i = 0; i < 16; ++i) e = std::max(e, std::fabs(a[i] - b[i]));
    return e;
}
M scale_m(double sx, double sy, double sz) {
    M r = ident();
    r[0] = sx;
    r[5] = sy;
    r[10] = sz;
    return r;
}

using Qt = std::array<double, 4>;  // xyzw
Qt qnorm(Qt q) {
    const double l = std::sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
    return {q[0] / l, q[1] / l, q[2] / l, q[3] / l};
}
M trs(V3 t, Qt q, V3 s) {
    q = qnorm(q);
    const double x = q[0], y = q[1], z = q[2], w = q[3];
    M m = ident();
    const double r[9] = {1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w),
                         2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w),
                         2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y)};
    const double sc[3] = {s.x, s.y, s.z};
    for (std::size_t c = 0; c < 3; ++c) {
        for (std::size_t rr = 0; rr < 3; ++rr) m[c * 4 + rr] = r[c * 3 + rr] * sc[c];
    }
    m[12] = t.x;
    m[13] = t.y;
    m[14] = t.z;
    return m;
}
Qt slerp(Qt a, Qt b, double u) {
    double d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
    if (d < 0) {
        for (double& v : b) v = -v;
        d = -d;
    }
    if (d > 0.9999999) {
        Qt r;
        for (std::size_t i = 0; i < 4; ++i) r[i] = a[i] + (b[i] - a[i]) * u;
        return qnorm(r);
    }
    const double th = std::acos(d), s = std::sin(th);
    Qt r;
    for (std::size_t i = 0; i < 4; ++i) r[i] = (std::sin((1 - u) * th) * a[i] + std::sin(u * th) * b[i]) / s;
    return r;
}

// The Open Mason conversion C = unit · Ry(180) (or unit · I): conj(M) = C·M·C^-1.
struct Conv {
    double unit = 1;
    bool flip = true;
    M c() const { return scale_m(flip ? -unit : unit, unit, flip ? -unit : unit); }
    M conj(const M& m) const { return mul(mul(c(), m), inv(c())); }
    V3 point(V3 p) const { return pt(c(), p); }
};

// ---- glTF evaluation (independent of the importer) ------------------------------------------------------------

void sample(const imp::AnimSampler& s, double t, bool rot, double* out) {
    const std::size_t n = s.input.size(), comps = static_cast<std::size_t>(s.components);
    const bool cubic = s.interpolation == imp::Interpolation::CubicSpline;
    const std::size_t stride = comps * (cubic ? 3 : 1), off = cubic ? comps : 0;
    auto val = [&](std::size_t k, std::size_t c) { return static_cast<double>(s.output[k * stride + off + c]); };
    std::size_t k = 0;
    double u = 0;
    bool clamp = true;
    if (t <= s.input[0]) {
        k = 0;
    } else if (t >= s.input[n - 1]) {
        k = n - 1;
    } else {
        while (!(t >= s.input[k] && t < s.input[k + 1])) ++k;
        u = (t - s.input[k]) / (s.input[k + 1] - s.input[k]);
        clamp = false;
    }
    if (clamp || s.interpolation == imp::Interpolation::Step) {
        for (std::size_t c = 0; c < comps; ++c) out[c] = val(k, c);
    } else if (s.interpolation == imp::Interpolation::Linear) {
        if (rot) {
            const Qt q = slerp({val(k, 0), val(k, 1), val(k, 2), val(k, 3)},
                               {val(k + 1, 0), val(k + 1, 1), val(k + 1, 2), val(k + 1, 3)}, u);
            for (std::size_t c = 0; c < 4; ++c) out[c] = q[c];
        } else {
            for (std::size_t c = 0; c < comps; ++c) out[c] = val(k, c) * (1 - u) + val(k + 1, c) * u;
        }
    } else {
        const double dt = s.input[k + 1] - s.input[k];
        for (std::size_t c = 0; c < comps; ++c) {
            const double p0 = val(k, c), p1 = val(k + 1, c);
            const double m0 = s.output[k * stride + 2 * comps + c] * dt, m1 = s.output[(k + 1) * stride + c] * dt;
            out[c] = (2 * u * u * u - 3 * u * u + 1) * p0 + (u * u * u - 2 * u * u + u) * m0 +
                     (-2 * u * u * u + 3 * u * u) * p1 + (u * u * u - u * u) * m1;
        }
    }
    if (rot) {
        const Qt q = qnorm({out[0], out[1], out[2], out[3]});
        for (std::size_t c = 0; c < 4; ++c) out[c] = q[c];
    }
}

// Source-space world matrices of every node at time t of animation `a` (nullptr = rest).
std::vector<M> worlds(const imp::Scene& s, const imp::Animation* a, double t) {
    std::vector<M> local(s.nodes.size());
    for (std::size_t i = 0; i < s.nodes.size(); ++i) {
        const imp::Node& n = s.nodes[i];
        V3 tr{}, sc{1, 1, 1};
        Qt q{0, 0, 0, 1};
        if (n.translation) tr = {(*n.translation)[0], (*n.translation)[1], (*n.translation)[2]};
        if (n.rotation) q = {(*n.rotation)[0], (*n.rotation)[1], (*n.rotation)[2], (*n.rotation)[3]};
        if (n.scale) sc = {(*n.scale)[0], (*n.scale)[1], (*n.scale)[2]};
        bool animated = false;
        if (a != nullptr) {
            for (const imp::AnimChannel& ch : a->channels) {
                if (ch.node != static_cast<int>(i) || ch.path == imp::AnimPath::Weights) continue;
                double buf[4];
                const imp::AnimSampler& smp = a->samplers[uz(ch.sampler)];
                sample(smp, t, ch.path == imp::AnimPath::Rotation, buf);
                animated = true;
                if (ch.path == imp::AnimPath::Translation) tr = {buf[0], buf[1], buf[2]};
                if (ch.path == imp::AnimPath::Rotation) q = {buf[0], buf[1], buf[2], buf[3]};
                if (ch.path == imp::AnimPath::Scale) sc = {buf[0], buf[1], buf[2]};
            }
        }
        local[i] = (n.matrix && !animated) ? of(*n.matrix) : trs(tr, q, sc);
    }
    std::vector<M> world(s.nodes.size(), ident());
    std::vector<char> done(s.nodes.size(), 0);
    for (std::size_t i = 0; i < s.nodes.size(); ++i) {
        std::vector<int> chain;
        for (int k = static_cast<int>(i); k >= 0 && !done[uz(k)]; k = s.nodes[uz(k)].parent) chain.push_back(k);
        for (auto it = chain.rbegin(); it != chain.rend(); ++it) {
            const int p = s.nodes[uz(*it)].parent;
            world[uz(*it)] = p >= 0 ? mul(world[uz(p)], local[uz(*it)]) : local[uz(*it)];
            done[uz(*it)] = 1;
        }
    }
    return world;
}

imp::Scene read_scene(const fs::path& p) {
    const auto bytes = load(p);
    const auto resolve = imp::directory_resolver(p.parent_path());
    if (p.extension() == ".obj") {
        return imp::read_obj(std::string_view(reinterpret_cast<const char*>(bytes.data()), bytes.size()), resolve);
    }
    return imp::read_gltf(bytes, resolve);
}

int node_named(const imp::Scene& s, std::string_view name) {
    for (std::size_t i = 0; i < s.nodes.size(); ++i) {
        if (s.nodes[i].name == name) return static_cast<int>(i);
    }
    return -1;
}

// ---- import helpers ----------------------------------------------------------------------------------------------

struct Imported {
    int32_t rc = CO_ERR_ARG;
    omo::Document doc;
    std::vector<std::pair<std::string, oa::Clip>> clips;
    std::vector<std::string> warnings;
    co_import_counts counts{};
};

Imported import_file(const fs::path& p, const co_import_options* opt = nullptr) {
    Imported out;
    co_import_result* r = nullptr;
    out.rc = co_import_file(p.string().c_str(), opt, &r);
    if (out.rc != CO_OK) {
        CHECK(r == nullptr);
        CHECK(std::strlen(co_import_last_error()) > 0);
        return out;
    }
    CHECK(r != nullptr);
    co_bytes* b = co_doc_write(co_import_result_doc(r));
    CHECK(b != nullptr);
    const std::span<const std::byte> archive(reinterpret_cast<const std::byte*>(co_bytes_data(b)),
                                             static_cast<std::size_t>(co_bytes_size(b)));
    out.doc = omo::read(archive);
    // Round trip: the document read back writes the same bytes again.
    const auto again = omo::write(out.doc);
    CHECK(again.size() == archive.size() && std::equal(again.begin(), again.end(), archive.begin()));
    co_bytes_destroy(b);
    for (int32_t i = 0; i < co_import_result_clip_count(r); ++i) {
        const char* name = nullptr;
        const uint8_t* data = nullptr;
        int64_t size = 0;
        CHECK(co_import_result_clip(r, i, &name, &data, &size) == CO_OK);
        out.clips.push_back({name, oa::read(std::span<const std::byte>(reinterpret_cast<const std::byte*>(data),
                                                                       static_cast<std::size_t>(size)))});
    }
    CHECK(co_import_result_clip(r, co_import_result_clip_count(r), nullptr, nullptr, nullptr) == CO_ERR_RANGE);
    for (int32_t i = 0; i < co_import_result_warning_count(r); ++i) out.warnings.push_back(co_import_result_warning(r, i));
    CHECK(co_import_result_warning(r, -1) == nullptr);
    CHECK(co_import_result_counts(r, &out.counts) == CO_OK);
    // Ownership: take the doc, the result no longer holds it.
    co_doc* taken = co_import_result_take_doc(r);
    CHECK(taken != nullptr && co_import_result_doc(r) == nullptr && co_import_result_take_doc(r) == nullptr);
    co_doc_destroy(taken);
    co_import_result_destroy(r);
    return out;
}

// ---- morph weight tracks (#171) ----------------------------------------------------------------------------------

// The glTF weights channel's value of target t at time x, evaluated here (glTF 2.0 spec: clamp, step, lerp, Hermite
// with dt-scaled tangents).
double gltf_weight(const imp::AnimSampler& smp, std::size_t t, double x) {
    const std::size_t c = static_cast<std::size_t>(smp.components);
    const bool cubic = smp.interpolation == imp::Interpolation::CubicSpline;
    const std::size_t stride = c * (cubic ? 3 : 1);
    auto v = [&](std::size_t k) { return static_cast<double>(smp.output[k * stride + (cubic ? c : 0) + t]); };
    const std::size_t n = smp.input.size();
    if (x <= smp.input[0]) return v(0);
    if (x >= smp.input[n - 1]) return v(n - 1);
    std::size_t k = 0;
    while (k + 2 < n && x >= smp.input[k + 1]) ++k;
    const double t0 = smp.input[k], t1 = smp.input[k + 1], dt = t1 - t0, u = (x - t0) / dt;
    if (smp.interpolation == imp::Interpolation::Step) return v(k);
    if (!cubic) return v(k) + (v(k + 1) - v(k)) * u;
    const double b = smp.output[k * stride + 2 * c + t], a = smp.output[(k + 1) * stride + t];
    const double u2 = u * u, u3 = u2 * u;
    return (2 * u3 - 3 * u2 + 1) * v(k) + (u3 - 2 * u2 + u) * dt * b + (-2 * u3 + 3 * u2) * v(k + 1) + (u3 - u2) * dt * a;
}

// The OMANIM 1.3 morph track's value at x (the format's curve rule, evaluated here).
double track_weight(const oa::Curve& cv, double x) {
    const auto& k = cv.keys;
    if (x <= k.front().time) return k.front().value;
    if (x >= k.back().time) return k.back().value;
    std::size_t i = 0;
    while (i + 1 < k.size() && k[i + 1].time <= x) ++i;
    const double dt = static_cast<double>(k[i + 1].time) - k[i].time, u = (x - k[i].time) / dt;
    if (k[i].interp == oa::CurveInterp::Step) return k[i].value;
    if (k[i].interp == oa::CurveInterp::Linear) return k[i].value + (static_cast<double>(k[i + 1].value) - k[i].value) * u;
    const double u2 = u * u, u3 = u2 * u;
    return (2 * u3 - 3 * u2 + 1) * k[i].value + (u3 - 2 * u2 + u) * dt * k[i].out_tangent +
           (-2 * u3 + 3 * u2) * k[i + 1].value + (u3 - u2) * dt * k[i + 1].in_tangent;
}

// Every weights channel of every animation came through as morph tracks equal to the glTF curve. Returns the number
// of tracks checked.
int check_morph_tracks(const imp::Scene& s, const Imported& im) {
    int checked = 0;
    for (const imp::Animation& a : s.animations) {
        const oa::Clip* clip = nullptr;
        for (const auto& [name, c] : im.clips) {
            if (name == a.name) clip = &c;
        }
        for (const imp::AnimChannel& ch : a.channels) {
            if (ch.path != imp::AnimPath::Weights) continue;
            CHECK(clip != nullptr);
            if (clip == nullptr) continue;
            CHECK(clip->version == "1.3");
            const imp::AnimSampler& smp = a.samplers[static_cast<std::size_t>(ch.sampler)];
            const imp::Mesh& mesh = s.meshes[static_cast<std::size_t>(s.nodes[static_cast<std::size_t>(ch.node)].mesh)];
            for (std::size_t t = 0; t < static_cast<std::size_t>(smp.components); ++t) {
                const std::string mname = t < mesh.target_names.size() && !mesh.target_names[t].empty()
                                              ? mesh.target_names[t] : "Key " + std::to_string(t + 1);
                const oa::Curve* cv = nullptr;
                for (const oa::Curve& c : clip->morphs) {
                    if (c.name == mname) cv = &c;
                }
                CHECK(cv != nullptr && !cv->keys.empty());
                if (cv == nullptr || cv->keys.empty()) continue;
                CHECK(cv->keys.size() <= smp.input.size());
                const bool cubic = smp.interpolation == imp::Interpolation::CubicSpline;
                if (cubic) CHECK(cv->keys.size() == smp.input.size());
                for (const oa::CurveKey& k : cv->keys) {
                    CHECK(k.interp == (cubic ? oa::CurveInterp::Cubic
                                             : smp.interpolation == imp::Interpolation::Step ? oa::CurveInterp::Step
                                                                                              : oa::CurveInterp::Linear));
                }
                const double end = smp.input.back();
                for (int i = -2; i <= 202; ++i) {
                    const double x = end * i / 200.0;
                    CHECK_NEAR(track_weight(*cv, x), gltf_weight(smp, t, x), 1e-6);
                }
                ++checked;
            }
        }
    }
    return checked;
}

bool has_warning(const Imported& im, std::string_view needle) {
    for (const auto& w : im.warnings) {
        if (w.find(needle) != std::string::npos) return true;
    }
    return false;
}

const omo::Part* part_named(const omo::Document& d, std::string_view name) {
    for (const auto& p : d.parts) {
        if (p.name == name) return &p;
    }
    return nullptr;
}

V3 vertex(const omo::Document& d, std::int32_t v) {
    const auto& vs = *d.mesh->vertices;
    const std::size_t i = static_cast<std::size_t>(v) * 3;
    return {vs[i], vs[i + 1], vs[i + 2]};
}

// The OMO layout rules the Java loaders enforce, plus "one face id = one simple polygon".
void check_layout(const omo::Document& d, const std::string& label) {
    if (!d.mesh) {
        CHECK(d.parts.empty());
        return;
    }
    const auto& vs = *d.mesh->vertices;
    const auto& tc = *d.mesh->tex_coords;
    const auto& idx = *d.mesh->indices;
    const auto& tf = *d.mesh->triangle_to_face_id;
    const std::size_t nv = vs.size() / 3, nt = idx.size() / 3;
    CHECK(vs.size() % 3 == 0 && tc.size() == nv * 2 && idx.size() % 3 == 0 && tf.size() == nt);
    std::int32_t v_at = 0, i_at = 0, f_at = 0;
    std::map<std::int32_t, std::vector<std::size_t>> tris_of_face;
    for (const omo::Part& p : d.parts) {
        CHECK(p.vertex_start == v_at && p.index_start == i_at && p.face_start == f_at);
        v_at += p.vertex_count;
        i_at += p.index_count;
        f_at += p.face_count;
        CHECK(p.index_count % 3 == 0);
        for (std::int32_t i = p.index_start; i < p.index_start + p.index_count; ++i) {
            const std::int32_t v = idx[static_cast<std::size_t>(i)];
            CHECK(v >= p.vertex_start && v < p.vertex_start + p.vertex_count);
        }
        for (std::int32_t t = p.index_start / 3; t < (p.index_start + p.index_count) / 3; ++t) {
            const std::int32_t f = tf[static_cast<std::size_t>(t)];
            CHECK(f >= p.face_start && f < p.face_start + p.face_count);
            tris_of_face[f].push_back(static_cast<std::size_t>(t));
        }
        CHECK(p.scale == (omo::Vec3{1, 1, 1}) && p.pos == omo::Vec3{} && p.rot == omo::Vec3{});
    }
    CHECK(static_cast<std::size_t>(v_at) == nv && static_cast<std::size_t>(i_at) == idx.size());
    CHECK(static_cast<int>(tris_of_face.size()) == f_at);
    // Every face: its corners are one contiguous run, n corners -> n - 2 triangles covering the polygon once with
    // its orientation (vector areas add up to the polygon's Newell area; none flipped).
    for (const auto& [f, tris] : tris_of_face) {
        std::int32_t lo = INT32_MAX, hi = -1;
        for (std::size_t t : tris) {
            for (std::size_t k = 0; k < 3; ++k) {
                lo = std::min(lo, idx[t * 3 + k]);
                hi = std::max(hi, idx[t * 3 + k]);
            }
        }
        const std::size_t corners = static_cast<std::size_t>(hi - lo + 1);
        CHECK(tris.size() == corners - 2);
        V3 newell{};
        for (std::size_t i = 0; i < corners; ++i) {
            const V3 a = vertex(d, lo + static_cast<std::int32_t>(i));
            const V3 b = vertex(d, lo + static_cast<std::int32_t>((i + 1) % corners));
            newell = newell + cross(a, b);
        }
        V3 sum{};
        for (std::size_t t : tris) {
            const V3 a = vertex(d, idx[t * 3]), b = vertex(d, idx[t * 3 + 1]), c = vertex(d, idx[t * 3 + 2]);
            const V3 n = cross(b - a, c - a);
            CHECK(dot(n, newell) >= -1e-9);
            sum = sum + n;
        }
        if (dist(sum, newell) > 1e-4 * std::max(1.0, len(newell))) {
            std::fprintf(stderr, "%s: face %d is not one simple polygon\n", label.c_str(), f);
            CHECK(false);
        }
    }
    // Every face has a mapping; skin rows in range and normalized.
    if (d.face_textures) {
        std::set<std::int32_t> mapped;
        for (const auto& m : d.face_textures->mappings) mapped.insert(m.face_id);
        CHECK(static_cast<std::int32_t>(mapped.size()) == f_at);
    }
    for (const omo::SkinPart& sp : d.skin.parts) {
        const omo::Part* p = nullptr;
        for (const auto& q : d.parts) {
            if (q.id == sp.part_id) p = &q;
        }
        CHECK(p != nullptr);
        for (std::size_t r = 0; r < sp.rows(); ++r) {
            CHECK(p != nullptr && sp.vertices[r] >= 0 && sp.vertices[r] < p->vertex_count);
            double sum = 0;
            for (std::int32_t i = sp.offsets[r]; i < sp.offsets[r + 1]; ++i) {
                const std::int32_t j = sp.joints[static_cast<std::size_t>(i)];
                CHECK(j >= 0 && static_cast<std::size_t>(j) < d.skin.joints.size());
                sum += sp.weights[static_cast<std::size_t>(i)];
            }
            CHECK_NEAR(sum, 1.0, 1e-5);
        }
    }
}

// ---- the rig of an imported document ------------------------------------------------------------------------------

struct RigOf {
    cr_rig* rig = nullptr;
    std::vector<std::string> ids, names;
    int bone_base = 0;
    RigOf() = default;
    RigOf(const RigOf&) = delete;
    RigOf& operator=(const RigOf&) = delete;
    ~RigOf() { cr_rig_destroy(rig); }
    int find(std::string_view name) const {
        for (std::size_t i = 0; i < names.size(); ++i) {
            if (names[i] == name) return static_cast<int>(i);
        }
        return -1;
    }
};

void build_rig(const omo::Document& d, RigOf& out) {
    std::vector<cr_joint_desc> j;
    std::map<std::string, int> index;
    for (const auto& p : d.parts) {
        index[p.id] = static_cast<int>(out.ids.size());
        out.ids.push_back(p.id);
        out.names.push_back(p.name);
    }
    out.bone_base = static_cast<int>(out.ids.size());
    for (const auto& b : d.bones) {
        index[b.id] = static_cast<int>(out.ids.size());
        out.ids.push_back(b.id);
        out.names.push_back(b.name);
    }
    auto put = [](float* dst, const omo::Vec3& v) {
        dst[0] = v.x;
        dst[1] = v.y;
        dst[2] = v.z;
    };
    for (const auto& p : d.parts) {
        cr_joint_desc jd{};
        jd.parent = p.parent_id ? index.at(*p.parent_id) : -1;
        jd.kind = CR_JOINT_PART;
        jd.flags = p.face_count > 0 ? static_cast<uint32_t>(CR_JOINT_HAS_GEOMETRY) : 0u;
        put(jd.origin, p.origin);
        put(jd.pos, p.pos);
        put(jd.rot_deg, p.rot);
        put(jd.scale, p.scale);
        j.push_back(jd);
    }
    for (const auto& b : d.bones) {
        cr_joint_desc jd{};
        jd.parent = b.parent_bone_id ? index.at(*b.parent_bone_id) : -1;
        jd.kind = CR_JOINT_BONE;
        put(jd.origin, b.origin);
        put(jd.pos, b.pos);
        put(jd.rot_deg, b.rot);
        jd.scale[0] = jd.scale[1] = jd.scale[2] = 1.f;
        put(jd.endpoint, b.endpoint);
        j.push_back(jd);
    }
    std::vector<const char*> ids, names;
    for (const auto& s : out.ids) ids.push_back(s.c_str());
    for (const auto& s : out.names) names.push_back(s.c_str());
    out.rig = cr_rig_create(j.data(), static_cast<int32_t>(j.size()), ids.data(), names.data());
    CHECK(out.rig != nullptr);
    if (out.rig != nullptr) CHECK(cr_rig_flags(out.rig) == 0);
}

cr_clip* make_clip(const oa::Clip& c) {
    std::vector<cr_track_desc> tracks;
    std::vector<cr_key> keys;
    std::vector<const char*> ids, names;
    for (const oa::Track& t : c.tracks) {
        tracks.push_back({static_cast<int32_t>(keys.size()), static_cast<int32_t>(t.keys.size())});
        for (const oa::Key& k : t.keys) {
            cr_key ck{};
            ck.time = k.time;
            std::memcpy(ck.pos, k.pos, sizeof ck.pos);
            std::memcpy(ck.rot_deg, k.rot, sizeof ck.rot_deg);
            std::memcpy(ck.scale, k.scale, sizeof ck.scale);
            ck.easing = static_cast<int32_t>(k.easing);
            keys.push_back(ck);
        }
        ids.push_back(t.part_id.c_str());
        names.push_back(t.part_name.c_str());
    }
    cr_clip_desc desc{c.duration, c.loop ? 1 : 0, CR_LAYER_BASE, 0, 0.f, 0.f};
    return cr_clip_create(&desc, tracks.data(), static_cast<int32_t>(tracks.size()), keys.data(),
                          static_cast<int32_t>(keys.size()), ids.data(), names.data(), nullptr, 0);
}

// The expected bone world: the converted joint world with its REST scale divided out.
M expected_bone_world(const Conv& cv, const M& g_t, const M& g_rest) {
    const M c = cv.conj(g_rest);
    const double sigma = (len({c[0], c[1], c[2]}) + len({c[4], c[5], c[6]}) + len({c[8], c[9], c[10]})) / 3.0;
    return mul(cv.conj(g_t), scale_m(1 / sigma, 1 / sigma, 1 / sigma));
}

// The rest skinning matrix (source space) of one vertex: sum w·(G_rest(joint)·IBM(joint)), weights normalized.
M rest_blend(const imp::Scene& s, const std::vector<M>& w0, const imp::Skin& skin, const imp::Primitive& prim,
             std::size_t v) {
    double sum = 0;
    for (std::uint32_t i = prim.influences.offsets[v]; i < prim.influences.offsets[v + 1]; ++i) {
        sum += prim.influences.weights[i];
    }
    M blend{};
    for (std::uint32_t i = prim.influences.offsets[v]; i < prim.influences.offsets[v + 1]; ++i) {
        const std::uint32_t j = prim.influences.joints[i];
        const M k = mul(w0[uz(skin.joints[j])], of(skin.inverse_bind_matrices[j]));
        for (std::size_t e = 0; e < 16; ++e) blend[e] += k[e] * prim.influences.weights[i] / sum;
    }
    (void)s;
    return blend;
}

// Expected model-space rest positions of a skinned mesh node (the glTF skinning equation at rest).
std::vector<V3> skinned_rest(const imp::Scene& s, int mesh_node, const Conv& cv) {
    const imp::Node& n = s.nodes[uz(mesh_node)];
    const imp::Skin& skin = s.skins[uz(n.skin)];
    const auto w = worlds(s, nullptr, 0);
    std::vector<V3> out;
    for (const auto& prim : s.meshes[uz(n.mesh)].primitives) {
        for (std::size_t v = 0; v < prim.positions.size(); ++v) {
            const auto& p = prim.positions[v];
            out.push_back(cv.point(pt(rest_blend(s, w, skin, prim, v), {p[0], p[1], p[2]})));
        }
    }
    return out;
}

int closest(const std::vector<V3>& set, V3 p, double* d_out = nullptr) {
    int best = -1;
    double bd = 1e300;
    for (std::size_t i = 0; i < set.size(); ++i) {
        const double dd = dist(set[i], p);
        if (dd < bd) {
            bd = dd;
            best = static_cast<int>(i);
        }
    }
    if (d_out != nullptr) *d_out = bd;
    return best;
}

// ---- tests --------------------------------------------------------------------------------------------------------

void test_defaults_and_args() {
    co_import_options o;
    co_import_options_default(&o);
    CHECK(o.unit_scale == 1.f && o.axis == CO_IMPORT_AXIS_OPENMASON && o.flags == CO_IMPORT_DEFAULT_FLAGS &&
          o.max_texture_size == 0 && o.quad_angle_deg == 1.f && o.reserved == 0);
    co_import_result* r = nullptr;
    CHECK(co_import_file(nullptr, nullptr, &r) == CO_ERR_ARG && r == nullptr);
    CHECK(co_import_file((kA3 / "quad.glb").string().c_str(), nullptr, nullptr) == CO_ERR_ARG);
    CHECK(co_import_file((kA3 / "missing_file.gltf").string().c_str(), nullptr, &r) == CO_ERR_ARG);
    CHECK(co_import_file((kA3 / "make_fixtures.py").string().c_str(), nullptr, &r) == CO_ERR_ARG);
    co_import_options bad = o;
    bad.unit_scale = 0.f;
    CHECK(co_import_file((kA3 / "quad.glb").string().c_str(), &bad, &r) == CO_ERR_ARG);
    bad = o;
    bad.axis = 7;
    CHECK(co_import_file((kA3 / "quad.glb").string().c_str(), &bad, &r) == CO_ERR_ARG);
    CHECK(co_import_bytes(nullptr, 5, CO_IMPORT_FORMAT_AUTO, nullptr, nullptr, &r) == CO_ERR_ARG);
    const uint8_t junk[] = {'x'};
    CHECK(co_import_bytes(junk, 1, 9, nullptr, nullptr, &r) == CO_ERR_ARG);
    CHECK(co_import_result_doc(nullptr) == nullptr && co_import_result_clip_count(nullptr) == 0);
    CHECK(r == nullptr);
    co_import_result_destroy(nullptr);
}

void test_malformed() {
    for (const char* f : {"bad_json.gltf", "truncated.glb", "accessor_overrun.gltf", "bad_index.gltf",
                          "missing_buffer.gltf", "unsupported_ext.gltf", "bad_index.obj", "bad_number.obj",
                          "zero_index.obj", "bad_relative.obj"}) {
        const Imported im = import_file(kA3 / f);
        if (im.rc != CO_ERR_FORMAT) std::fprintf(stderr, "malformed %s: rc %d\n", f, im.rc);
        CHECK(im.rc == CO_ERR_FORMAT);
    }
    // Mutated inputs: never a crash, only OK or a format error (run under ASan too).
    std::mt19937 rng(173);
    for (const char* f : {"rig.gltf", "fold.gltf"}) {
        const auto base = load(kOwn / f);
        for (int i = 0; i < 150; ++i) {
            auto b = base;
            const int edits = 1 + static_cast<int>(rng() % 4);
            for (int e = 0; e < edits; ++e) b[rng() % b.size()] = static_cast<std::byte>(rng() & 0xff);
            co_import_result* r = nullptr;
            const int32_t rc = co_import_bytes(reinterpret_cast<const uint8_t*>(b.data()), static_cast<int64_t>(b.size()),
                                               CO_IMPORT_FORMAT_AUTO, kOwn.string().c_str(), nullptr, &r);
            CHECK(rc == CO_OK || rc == CO_ERR_FORMAT);
            CHECK((rc == CO_OK) == (r != nullptr));
            co_import_result_destroy(r);
        }
    }
    const auto base = load(kA3 / "quad.glb");
    for (int i = 0; i < 150; ++i) {
        auto b = base;
        b[rng() % b.size()] = static_cast<std::byte>(rng() & 0xff);
        if (rng() % 3 == 0) b.resize(rng() % b.size());
        co_import_result* r = nullptr;
        const int32_t rc = co_import_bytes(reinterpret_cast<const uint8_t*>(b.data()), static_cast<int64_t>(b.size()),
                                           CO_IMPORT_FORMAT_GLTF, nullptr, nullptr, &r);
        CHECK(rc == CO_OK || rc == CO_ERR_FORMAT);
        co_import_result_destroy(r);
    }
}

// skin_strip: two bones, repeated joint indices merged, int16 positions, IBMs.
void test_skin_strip() {
    const Imported im = import_file(kA3 / "skin_strip.gltf");
    CHECK(im.rc == CO_OK);
    if (im.rc != CO_OK) return;
    const omo::Document& d = im.doc;
    check_layout(d, "skin_strip");
    CHECK(d.version == "1.9");
    CHECK(d.parts.size() == 1 && d.parts[0].name == "Strip" && !d.parts[0].parent_id);
    CHECK(d.bones.size() == 2 && d.bones[0].name == "Bone" && d.bones[1].name == "Bone.001");
    if (d.bones.size() != 2) return;
    CHECK(d.bones[1].parent_bone_id && *d.bones[1].parent_bone_id == d.bones[0].id && !d.bones[0].parent_bone_id);
    CHECK(d.skin.joints.size() == 2 && d.skin.joints[0] == d.bones[0].id);
    CHECK(im.counts.parts == 1 && im.counts.bones == 2 && im.counts.faces == 3 && im.counts.triangles == 6);
    // Bone frames: Bone at the origin, Bone.001 1.5 up; Bone's tail = Bone.001's head; the leaf points +Y.
    CHECK_NEAR(d.bones[0].endpoint.y, 1.5, 1e-6);
    CHECK_NEAR(d.bones[1].origin.y, 0.0, 1e-6);
    CHECK_NEAR(d.bones[1].endpoint.y, 1.5, 1e-6);  // the parent's length
    // Source vertex 2 = (0,1,0): influences j[0,1,1,0] w[.25,.25,.125,.125] + j[1,0] w[.2,.4] -> 2 rows.
    const imp::Scene s = read_scene(kA3 / "skin_strip.gltf");
    const Conv cv;
    const auto expect = skinned_rest(s, node_named(s, "Strip"), cv);
    const omo::SkinPart& sp = d.skin.parts.at(0);
    std::map<std::int32_t, std::size_t> row_of;
    for (std::size_t r = 0; r < sp.rows(); ++r) row_of[sp.vertices[r]] = r;
    CHECK(row_of.size() == static_cast<std::size_t>(d.parts[0].vertex_count));
    bool saw_v2 = false;
    for (std::int32_t v = 0; v < d.parts[0].vertex_count; ++v) {
        double dd = 0;
        const int src = closest(expect, vertex(d, v), &dd);
        CHECK(dd < 1e-5);
        if (src == 2) {
            saw_v2 = true;
            const std::size_t r = row_of.at(v);
            CHECK(sp.offsets[r + 1] - sp.offsets[r] == 2);
            for (std::int32_t i = sp.offsets[r]; i < sp.offsets[r + 1]; ++i) {
                const double want = sp.joints[static_cast<std::size_t>(i)] == 0 ? 0.775 / 1.35 : 0.575 / 1.35;
                CHECK_NEAR(sp.weights[static_cast<std::size_t>(i)], want, 1e-6);
            }
        }
    }
    CHECK(saw_v2);
}

// The big one: rig.gltf.
void test_rig() {
    const fs::path file = kOwn / "rig.gltf";
    const Imported im = import_file(file);
    CHECK(im.rc == CO_OK);
    if (im.rc != CO_OK) return;
    const omo::Document& d = im.doc;
    check_layout(d, "rig");
    const imp::Scene s = read_scene(file);
    const Conv cv;

    // Parts and hierarchy.
    CHECK(d.parts.size() == 5);
    const omo::Part* body = part_named(d, "Body");
    const omo::Part* hat = part_named(d, "Hat");
    const omo::Part* prop = part_named(d, "Prop");
    const omo::Part* prop_child = part_named(d, "PropChild");
    const omo::Part* mirror = part_named(d, "Mirror");
    CHECK(body && hat && prop && prop_child && mirror);
    if (!(body && hat && prop && prop_child && mirror)) return;
    CHECK(!body->parent_id && !prop->parent_id && !mirror->parent_id);
    CHECK(prop_child->parent_id && *prop_child->parent_id == prop->id);
    std::map<std::string, const omo::Bone*> bone;
    for (const auto& b : d.bones) bone[b.name] = &b;
    CHECK(d.bones.size() == 7 && bone.count("Hips") && bone.count("Tail") && bone.count("Head") && bone.count("Chest"));
    if (bone.size() != 7) return;
    CHECK(hat->parent_id && *hat->parent_id == bone["Head"]->id);
    CHECK(bone["Spine"]->parent_bone_id && *bone["Spine"]->parent_bone_id == bone["Hips"]->id);
    CHECK(!bone["Hips"]->parent_bone_id);
    // Leaf bones point +Y; Spine's tail is Chest's head.
    CHECK(bone["Tail"]->endpoint.x == 0.f && bone["Tail"]->endpoint.z == 0.f && bone["Tail"]->endpoint.y > 0.f);
    CHECK_NEAR(bone["Chest"]->origin.x, 0, 1e-5);
    CHECK_NEAR(bone["Chest"]->origin.y, 0, 1e-5);
    CHECK_NEAR(bone["Chest"]->origin.z, 0, 1e-5);
    // Names unique over parts and bones.
    std::set<std::string> all_names;
    for (const auto& p : d.parts) all_names.insert(p.name);
    for (const auto& b : d.bones) all_names.insert(b.name);
    CHECK(all_names.size() == d.parts.size() + d.bones.size());
    // Unbounded influences: source vertex 4 keeps its 6 distinct joints.
    CHECK(im.counts.max_influences == 6);

    // Rest pose: every bone's rig world == the converted joint world (rest scale divided out).
    RigOf rig;
    build_rig(d, rig);
    if (rig.rig == nullptr) return;
    const int nj = cr_rig_joint_count(rig.rig);
    std::vector<float> rw(uz(nj) * 16), rc(uz(nj) * 16);
    CHECK(cr_rig_rest_world(rig.rig, rw.data(), rc.data()) == CR_OK);
    const auto w0 = worlds(s, nullptr, 0);
    for (const auto& b : d.bones) {
        const int j = rig.find(b.name);
        const int n = node_named(s, b.name);
        CHECK(j >= 0 && n >= 0);
        const M want = expected_bone_world(cv, w0[uz(n)], w0[uz(n)]);
        const double e = max_diff(of(rw.data() + j * 16), want);
        if (e > 1e-5) std::fprintf(stderr, "rest world of %s off by %g\n", b.name.c_str(), e);
        CHECK(e <= 1e-5);
    }
    // Unit-scale joints: the bone world IS the converted glTF world.
    CHECK(max_diff(of(rw.data() + rig.find("Spine") * 16), cv.conj(w0[uz(node_named(s, "Spine"))])) <= 1e-5);

    // Skinned rest vertices == the glTF skinning equation at the rest pose (bind != rest re-baked).
    const auto expect = skinned_rest(s, node_named(s, "Body"), cv);
    std::vector<int> src_of(uz(body->vertex_count));
    for (std::int32_t v = 0; v < body->vertex_count; ++v) {
        double dd = 0;
        src_of[uz(v)] = closest(expect, vertex(d, body->vertex_start + v), &dd);
        CHECK(dd < 2e-5);
    }
    const omo::SkinPart* bsk = nullptr;
    for (const auto& sp : d.skin.parts) {
        if (sp.part_id == body->id) bsk = &sp;
    }
    CHECK(bsk != nullptr && bsk->rows() == uz(body->vertex_count));
    bool saw4 = false, saw7 = false;
    for (std::size_t r = 0; bsk != nullptr && r < bsk->rows(); ++r) {
        const int src = src_of[uz(bsk->vertices[r])];
        const int count = bsk->offsets[r + 1] - bsk->offsets[r];
        if (src == 4) {
            saw4 = true;
            CHECK(count == 6);
        }
        if (src == 7) {  // joint 3 twice + joint 4: merged to 2
            saw7 = true;
            CHECK(count == 2);
        }
    }
    CHECK(saw4 && saw7);
    // Rigid parts: Hat vertices = its node's world applied.
    {
        const int hn = node_named(s, "Hat");
        const auto& hm = s.meshes[uz(s.nodes[uz(hn)].mesh)];
        std::vector<V3> want;
        for (const auto& p : hm.primitives[0].positions) want.push_back(cv.point(pt(w0[uz(hn)], {p[0], p[1], p[2]})));
        for (std::int32_t v = 0; v < hat->vertex_count; ++v) {
            double dd = 0;
            closest(want, vertex(d, hat->vertex_start + v), &dd);
            CHECK(dd < 1e-5);
        }
        CHECK(hat->face_count == 1);  // the quad merged
    }
    // Prop / PropChild / Mirror: 6 quad faces each, outward winding (Mirror's det < 0 reversed).
    for (const omo::Part* p : {prop, prop_child, mirror}) {
        CHECK(p->face_count == 6 && p->index_count == 36 && p->vertex_count == 24);
        V3 centre{};
        for (std::int32_t v = 0; v < p->vertex_count; ++v) centre = centre + vertex(d, p->vertex_start + v);
        centre = centre * (1.0 / p->vertex_count);
        const auto& idx = *d.mesh->indices;
        for (std::int32_t i = p->index_start; i < p->index_start + p->index_count; i += 3) {
            const std::size_t ii = static_cast<std::size_t>(i);
            const V3 a = vertex(d, idx[ii]), b = vertex(d, idx[ii + 1]), c = vertex(d, idx[ii + 2]);
            CHECK(dot(cross(b - a, c - a), (a + b + c) * (1.0 / 3) - centre) > 0);
        }
    }
    // Custom normals: every corner (every mesh has NORMAL), faces smooth.
    CHECK(d.shading.normal_vertices.size() == d.mesh->vertices->size() / 3);
    for (std::int32_t f = 0; f < im.counts.faces; ++f) CHECK(d.shading.smooth_face(static_cast<std::uint32_t>(f)));

    // Morph on the skinned mesh: deltas through the vertex's rest skinning matrix (converted).
    CHECK(d.morphs.size() == 1 && d.morphs[0].name == "Breathe");
    if (d.morphs.size() == 1) {
        const omo::Morph& m = d.morphs[0];
        CHECK_NEAR(m.default_weight, 0.25, 1e-7);
        CHECK(m.parts.size() == 1 && m.parts[0].part_id == body->id && !m.parts[0].normals.empty());
        const imp::Node& bn = s.nodes[uz(node_named(s, "Body"))];
        const imp::Skin& skin = s.skins[uz(bn.skin)];
        const imp::Primitive& prim = s.meshes[uz(bn.mesh)].primitives[0];
        for (std::size_t r = 0; !m.parts.empty() && r < m.parts[0].vertices.size(); ++r) {
            const int src = src_of[uz(m.parts[0].vertices[r])];
            CHECK(src >= 4 && src <= 7);
            const M blend = rest_blend(s, w0, skin, prim, uz(src));
            const auto& dl = prim.targets[0].positions[uz(src)];
            const V3 want = vec(cv.c(), vec(blend, {dl[0], dl[1], dl[2]}));
            CHECK_NEAR(m.parts[0].deltas[r * 3], want.x, 1e-6);
            CHECK_NEAR(m.parts[0].deltas[r * 3 + 1], want.y, 1e-6);
            CHECK_NEAR(m.parts[0].deltas[r * 3 + 2], want.z, 1e-6);
        }
        CHECK(m.parts.empty() || m.parts[0].vertices.size() >= 8);  // 4 source vertices, each in >= 2 faces
    }

    // The morph weights channel became an OMANIM 1.3 morph track (#171); the non-joint Prop channel is skipped.
    CHECK(!has_warning(im, "morph weight channels skipped"));
    CHECK(check_morph_tracks(s, im) == 1);
    CHECK(has_warning(im, "'Prop' skipped"));
    // A clip without morph tracks is still written as 1.2.
    for (const auto& [name, clip] : im.clips) CHECK(clip.version == (clip.morphs.empty() ? "1.2" : "1.3"));
    // Morphs off: the weights channel is skipped with a warning.
    {
        co_import_options o;
        co_import_options_default(&o);
        o.flags &= ~static_cast<uint32_t>(CO_IMPORT_MORPHS);
        const Imported nm = import_file(kOwn / "rig.gltf", &o);
        CHECK(nm.rc == CO_OK && has_warning(nm, "morph weight channels skipped"));
        for (const auto& [name, clip] : nm.clips) CHECK(clip.morphs.empty() && clip.version == "1.2");
    }

    // Animations: pose the OM rig with each imported clip and compare against the glTF evaluated here.
    CHECK(im.clips.size() == 2 && im.counts.clips == 2);
    for (const auto& [name, clip] : im.clips) {
        const imp::Animation* anim = nullptr;
        for (const auto& a : s.animations) {
            if (a.name == name) anim = &a;
        }
        CHECK(anim != nullptr);
        if (anim == nullptr) continue;
        for (const auto& t : clip.tracks) CHECK(t.bone && rig.find(t.part_name) >= rig.bone_base);
        cr_clip* cc = make_clip(clip);
        CHECK(cc != nullptr);
        if (cc == nullptr) continue;
        std::vector<float> skin(uz(nj) * 16), world(uz(nj) * 16);
        double worst = 0, worst_skin = 0;
        for (double t : {0.0, 0.1, 0.25, 0.33, 0.5, 0.61, 0.75, 0.9, 0.99, 1.0, 1.01, 1.25, 1.49, 1.5, 1.77, 1.95, 2.0,
                         2.5}) {
            CHECK(cr_rig_pose(rig.rig, cc, static_cast<float>(t), nullptr, 0, nullptr, 0, nullptr, skin.data(),
                              world.data(), nullptr) == CR_OK);
            const auto wt = worlds(s, anim, t);
            for (const auto& b : d.bones) {
                const int j = rig.find(b.name);
                const std::size_t n = uz(node_named(s, b.name));
                const M want = expected_bone_world(cv, wt[n], w0[n]);
                worst = std::max(worst, max_diff(of(world.data() + j * 16), want));
                // The skin matrix == conj(G(t)·G_rest^-1).
                worst_skin = std::max(worst_skin, max_diff(of(skin.data() + j * 16), cv.conj(mul(wt[n], inv(w0[n])))));
            }
            // The rigid Hat follows Head exactly as glTF moves it.
            const int hj = rig.find("Hat");
            const std::size_t hn = uz(node_named(s, "Hat"));
            worst_skin = std::max(worst_skin, max_diff(of(skin.data() + hj * 16), cv.conj(mul(wt[hn], inv(w0[hn])))));
        }
        std::printf("clip %s: worst world %.3g, worst skin %.3g\n", name.c_str(), worst, worst_skin);
        CHECK(worst <= 3e-5);
        CHECK(worst_skin <= 5e-5);
        cr_clip_destroy(cc);
    }
    // STEP stays STEP; translation-only LINEAR keeps its keys; rotations are resampled.
    for (const auto& [name, clip] : im.clips) {
        if (name != "Move") continue;
        CHECK_NEAR(clip.duration, 2.0, 1e-6);
        for (const auto& t : clip.tracks) {
            if (t.part_name == "Tail") {
                CHECK(t.keys.size() == 3);
                for (const auto& k : t.keys) CHECK(k.easing == oa::Easing::Step);
            }
            if (t.part_name == "Head") CHECK(t.keys.size() > 2);
            if (t.part_name == "Hips") CHECK(t.keys.size() == 3);
            std::printf("  track %s: %zu keys\n", t.part_name.c_str(), t.keys.size());
        }
    }

    // Merge mode: one part; the Hat rides the Head bone through skin rows.
    co_import_options o;
    co_import_options_default(&o);
    o.flags &= ~static_cast<uint32_t>(CO_IMPORT_SPLIT_PARTS);
    const Imported merged = import_file(file, &o);
    CHECK(merged.rc == CO_OK && merged.doc.parts.size() == 1);
    if (merged.rc == CO_OK && merged.doc.skin.parts.size() == 1) {
        check_layout(merged.doc, "rig merged");
        std::size_t head_rows = 0;
        const auto& msp = merged.doc.skin.parts[0];
        std::size_t head_index = 0;
        for (std::size_t i = 0; i < merged.doc.bones.size(); ++i) {
            if (merged.doc.bones[i].name == "Head") head_index = i;
        }
        for (std::size_t r = 0; r < msp.rows(); ++r) {
            if (msp.offsets[r + 1] - msp.offsets[r] == 1 &&
                msp.joints[static_cast<std::size_t>(msp.offsets[r])] == static_cast<std::int32_t>(head_index)) {
                ++head_rows;
            }
        }
        CHECK(head_rows >= 4);  // the hat's 4 corners (+ body vertices fully on Head)
        CHECK(merged.counts.faces == im.counts.faces);
    } else {
        CHECK(false);
    }

    // Options: unit scale and axes.
    co_import_options big;
    co_import_options_default(&big);
    big.unit_scale = 16.f;
    big.axis = CO_IMPORT_AXIS_KEEP;
    const Imported scaled = import_file(file, &big);
    CHECK(scaled.rc == CO_OK);
    if (scaled.rc == CO_OK) {
        const Conv cv16{16.0, false};
        RigOf r16;
        build_rig(scaled.doc, r16);
        std::vector<float> w16(uz(cr_rig_joint_count(r16.rig)) * 16);
        cr_rig_rest_world(r16.rig, w16.data(), nullptr);
        for (const auto& b : scaled.doc.bones) {
            const std::size_t n = uz(node_named(s, b.name));
            const M want = expected_bone_world(cv16, w0[n], w0[n]);
            CHECK(max_diff(of(w16.data() + r16.find(b.name) * 16), want) <= 2e-4);
        }
        const omo::Part* p16 = part_named(scaled.doc, "Prop");
        CHECK(p16 != nullptr);
        if (p16 != nullptr) {
            std::vector<V3> want;
            for (const auto& q : s.meshes[2].primitives[0].positions) {
                want.push_back(cv16.point(pt(w0[uz(node_named(s, "Prop"))], {q[0], q[1], q[2]})));
            }
            for (std::int32_t v = 0; v < p16->vertex_count; ++v) {
                double dd = 0;
                closest(want, vertex(scaled.doc, p16->vertex_start + v), &dd);
                CHECK(dd < 1e-4);
            }
        }
    }
    // Animations / morphs off.
    co_import_options off;
    co_import_options_default(&off);
    off.flags = CO_IMPORT_SPLIT_PARTS | CO_IMPORT_MERGE_QUADS;
    const Imported bare = import_file(file, &off);
    CHECK(bare.rc == CO_OK && bare.clips.empty() && bare.doc.morphs.empty() && has_warning(bare, "not imported"));
}

void test_nonuniform() {
    const fs::path file = kOwn / "nonuniform.gltf";
    const Imported im = import_file(file);
    CHECK(im.rc == CO_OK);
    if (im.rc != CO_OK) return;
    check_layout(im.doc, "nonuniform");
    CHECK(has_warning(im, "non-uniform"));
    const imp::Scene s = read_scene(file);
    const auto expect = skinned_rest(s, node_named(s, "Sheet"), Conv{});
    for (std::int32_t v = 0; v < im.doc.parts[0].vertex_count; ++v) {
        double dd = 0;
        closest(expect, vertex(im.doc, v), &dd);
        CHECK(dd < 1e-5);
    }
    // The orthonormalized bone frames keep the joint heads.
    RigOf rig;
    build_rig(im.doc, rig);
    if (rig.rig == nullptr) return;
    std::vector<float> w(uz(cr_rig_joint_count(rig.rig)) * 16);
    cr_rig_rest_world(rig.rig, w.data(), nullptr);
    const auto w0 = worlds(s, nullptr, 0);
    const Conv cv;
    for (const auto& b : im.doc.bones) {
        const M g = cv.conj(w0[uz(node_named(s, b.name))]);
        const float* m = w.data() + rig.find(b.name) * 16;
        CHECK_NEAR(m[12], g[12], 1e-5);
        CHECK_NEAR(m[13], g[13], 1e-5);
        CHECK_NEAR(m[14], g[14], 1e-5);
    }
}

void test_morph_cube_and_quads() {
    const Imported im = import_file(kA3 / "morph_cube.gltf");
    CHECK(im.rc == CO_OK);
    if (im.rc != CO_OK) return;
    const omo::Document& d = im.doc;
    check_layout(d, "morph_cube");
    CHECK(im.counts.faces == 6 && im.counts.triangles == 12 && im.counts.vertices == 24);
    // No NORMAL: flat faces, no custom normals.
    CHECK(d.shading.normal_vertices.empty() && d.shading.smooth.empty());
    CHECK(d.morphs.size() == 2 && d.morphs[0].name == "Smile" && d.morphs[1].name == "Frown");
    if (d.morphs.size() != 2) return;
    CHECK_NEAR(d.morphs[0].default_weight, 0.25, 1e-7);
    CHECK_NEAR(d.morphs[1].default_weight, 0.75, 1e-7);
    // Smile: every corner with y > 0 moves +0.5 y (12 corners); Frown: source vertices 1 and 6 (3 corners each),
    // deltas converted (x, z negated).
    const auto& smile = d.morphs[0].parts.at(0);
    CHECK(smile.vertices.size() == 12);
    for (std::size_t r = 0; r < smile.vertices.size(); ++r) {
        CHECK(vertex(d, smile.vertices[r]).y > 0);
        CHECK(smile.deltas[r * 3] == 0.f && smile.deltas[r * 3 + 1] == 0.5f && smile.deltas[r * 3 + 2] == 0.f);
    }
    const auto& frown = d.morphs[1].parts.at(0);
    CHECK(frown.vertices.size() == 6);
    for (std::size_t r = 0; r < frown.vertices.size(); ++r) {
        const V3 p = vertex(d, frown.vertices[r]);
        if (dist(p, {-1, -1, 1}) < 1e-6) {  // source (1, -1, -1)
            CHECK(frown.deltas[r * 3] == -0.25f && frown.deltas[r * 3 + 1] == 0.f && frown.deltas[r * 3 + 2] == 0.f);
        } else {
            CHECK(dist(p, {-1, 1, -1}) < 1e-6);  // source (1, 1, 1)
            CHECK(frown.deltas[r * 3] == 0.f && frown.deltas[r * 3 + 1] == 0.f && frown.deltas[r * 3 + 2] == 0.75f);
        }
    }
    // Quad merge off: 12 triangle faces.
    co_import_options o;
    co_import_options_default(&o);
    o.flags &= ~static_cast<uint32_t>(CO_IMPORT_MERGE_QUADS);
    const Imported tri = import_file(kA3 / "morph_cube.gltf", &o);
    CHECK(tri.rc == CO_OK && tri.counts.faces == 12);
    check_layout(tri.doc, "morph_cube tris");

    // fold.gltf: 30 deg fold and a concave dart stay 2 faces each; a 0.5 deg fold merges at 1 deg, not at 0.1.
    const Imported fold = import_file(kOwn / "fold.gltf");
    CHECK(fold.rc == CO_OK && fold.counts.faces == 5);
    check_layout(fold.doc, "fold");
    co_import_options strict;
    co_import_options_default(&strict);
    strict.quad_angle_deg = 0.1f;
    const Imported fold2 = import_file(kOwn / "fold.gltf", &strict);
    CHECK(fold2.rc == CO_OK && fold2.counts.faces == 6);
}

void test_groups() {
    const Imported im = import_file(kA3 / "custom_attr.gltf");
    CHECK(im.rc == CO_OK);
    if (im.rc != CO_OK) return;
    check_layout(im.doc, "custom_attr");
    const auto& g = im.doc.vertex_groups;
    CHECK(g.size() == 2 && g[0].name == "covered_jacket" && g[1].name == "mask");
    if (g.size() != 2) return;
    // _COVERED_JACKET 0, 0.5, 1 -> 2 rows; _MASK 0, 1, 0.2 -> 2 rows (zeros dropped).
    CHECK(g[0].parts.size() == 1 && g[0].parts[0].vertices.size() == 2);
    CHECK(g[0].parts[0].weights == (std::vector<float>{0.5f, 1.0f}));
    CHECK(g[1].parts.size() == 1 && g[1].parts[0].vertices.size() == 2);
    if (g[1].parts.size() == 1 && g[1].parts[0].weights.size() == 2) {
        CHECK_NEAR(g[1].parts[0].weights[0], 1.0, 1e-7);
        CHECK_NEAR(g[1].parts[0].weights[1], 0.2, 1e-6);
    }
    // Default material (no material in the file): white baseColor, no image.
    const auto& mats = im.doc.face_textures->materials;
    CHECK(mats.size() == 1 && mats[0].name == "Default" && mats[0].base_color && !mats[0].texture_file);
    co_import_options o;
    co_import_options_default(&o);
    o.flags &= ~static_cast<uint32_t>(CO_IMPORT_VERTEX_GROUPS);
    const Imported none = import_file(kA3 / "custom_attr.gltf", &o);
    CHECK(none.rc == CO_OK && none.doc.vertex_groups.empty());
}

void test_materials() {
    for (const char* f : {"textured_png.gltf", "textured_jpeg.gltf", "quad.glb"}) {
        const fs::path file = kA3 / f;
        const Imported im = import_file(file);
        CHECK(im.rc == CO_OK);
        if (im.rc != CO_OK) continue;
        const omo::Document& d = im.doc;
        check_layout(d, f);
        const imp::Scene s = read_scene(file);
        CHECK(im.counts.faces == 1 && im.counts.triangles == 2 && im.counts.vertices == 4);
        CHECK(d.face_textures && d.face_textures->materials.size() == 1);
        if (!d.face_textures || d.face_textures->materials.empty()) continue;
        const omo::Material& m = d.face_textures->materials[0];
        CHECK(m.material_id == 1 && m.name == "Checker" && m.render_layer == "CUTOUT");
        CHECK(m.base_color && (*m.base_color)[0] == 0.5f && (*m.base_color)[1] == 0.75f && (*m.base_color)[3] == 0.5f);
        CHECK(m.texture_file && *m.texture_file == "material_1.png");
        const omo::Image* img = d.image("material_1.png");
        CHECK(img != nullptr && img->data == s.images[0].png);
        CHECK(img != nullptr && img->data.size() > 8 && img->data[1] == std::byte{'P'} && img->data[2] == std::byte{'N'});
        CHECK(d.face_textures->mappings.size() == 1);
        const omo::FaceMapping& fm = d.face_textures->mappings[0];
        CHECK(fm.material_id == 1 && fm.u0 == 0.f && fm.v0 == 0.f && fm.u1 == 1.f && fm.v1 == 1.f &&
              fm.uv_rotation_degrees == 0);
        // UVs: TEXCOORD_1 (the transform's texCoord override) through KHR_texture_transform; v-down kept.
        const imp::Primitive& prim = s.meshes[0].primitives[0];
        const auto& xf = *s.materials[0].base_color_texture->transform;
        std::vector<V3> src;
        for (const auto& p : prim.positions) src.push_back(Conv{}.point({p[0], p[1], p[2]}));
        for (std::int32_t v = 0; v < 4; ++v) {
            const int k = closest(src, vertex(d, v));
            const auto& uv = prim.texcoords[1][uz(k)];
            const double su = uv[0] * xf.scale[0], sv = uv[1] * xf.scale[1];
            const double eu = std::cos(xf.rotation) * su + std::sin(xf.rotation) * sv + xf.offset[0];
            const double ev = -std::sin(xf.rotation) * su + std::cos(xf.rotation) * sv + xf.offset[1];
            CHECK_NEAR((*d.mesh->tex_coords)[uz(v) * 2], eu, 1e-5);
            CHECK_NEAR((*d.mesh->tex_coords)[uz(v) * 2 + 1], ev, 1e-5);
        }
        // NORMAL (0,0,1) -> custom normals (0,0,-1) after the axis turn, face smooth. (0,0,-1) folds to (1, 1).
        CHECK(d.shading.normal_vertices.size() == 4 && d.shading.smooth_face(0));
        for (std::size_t r = 0; r < d.shading.normal_vertices.size(); ++r) {
            CHECK(d.shading.normal_oct[r * 2] == 32767 && d.shading.normal_oct[r * 2 + 1] == 32767);
        }
    }
    // A max texture size below the texture downscales it (here to 1 pixel on its longer side; base colour kept).
    co_import_options o;
    co_import_options_default(&o);
    o.max_texture_size = 1;
    const Imported small = import_file(kA3 / "textured_png.gltf", &o);
    CHECK(small.rc == CO_OK && small.doc.images.size() == 1 && has_warning(small, "exceeds the maximum") &&
          has_warning(small, "downscaled from"));
    CHECK(small.rc != CO_OK || (small.doc.face_textures->materials[0].base_color.has_value() &&
                                small.doc.face_textures->materials[0].texture_file));
    if (small.rc == CO_OK && small.doc.images.size() == 1) {
        std::vector<unsigned char> px;
        int w = 0, h = 0, c = 0;
        CHECK(cenda::omformat::import::detail::decode_pixels(small.doc.images[0].data, px, w, h, c));
        CHECK(std::max(w, h) == 1 && std::min(w, h) >= 1);
    }
}

// ---- texture downscale ----------------------------------------------------------------------------------------

double srgb_decode(double v) { return v <= 0.04045 ? v / 12.92 : std::pow((v + 0.055) / 1.055, 2.4); }
double srgb_encode(double v) { return v <= 0.0031308 ? v * 12.92 : 1.055 * std::pow(v, 1.0 / 2.4) - 0.055; }

struct Pixels {
    std::vector<unsigned char> px;
    int w = 0, h = 0, c = 0;
    const unsigned char* at(int x, int y) const {
        return px.data() + (static_cast<std::size_t>(y) * uz(w) + uz(x)) * uz(c);
    }
};

Pixels decode(const std::vector<std::byte>& png) {
    Pixels p;
    CHECK(cenda::omformat::import::detail::decode_pixels(png, p.px, p.w, p.h, p.c));
    return p;
}

// Mean of one colour channel in linear light.
double linear_mean(const Pixels& p, int channel) {
    double sum = 0;
    for (int y = 0; y < p.h; ++y) {
        for (int x = 0; x < p.w; ++x) sum += srgb_decode(p.at(x, y)[channel] / 255.0);
    }
    return sum / (static_cast<double>(p.w) * p.h);
}

void test_texture_downscale() {
    const fs::path file = kOwn / "textures.gltf";
    co_import_options o;
    co_import_options_default(&o);
    o.max_texture_size = 16;
    const Imported im = import_file(file, &o);
    CHECK(im.rc == CO_OK);
    if (im.rc != CO_OK) return;
    const imp::Scene s = read_scene(file);
    std::map<std::string, const omo::Image*> by;
    for (const auto& m : im.doc.face_textures->materials) {
        CHECK(m.texture_file.has_value());
        if (m.texture_file) by[*m.name] = im.doc.image(*m.texture_file);
    }
    CHECK(by.size() == 6);
    for (const auto& [name, img] : by) CHECK(img != nullptr);
    if (by.size() != 6) return;
    auto source = [&](std::string_view name) {
        for (const auto& img : s.images) {
            if (img.name == name) return decode(img.png);
        }
        CHECK(false);
        return Pixels{};
    };
    struct Want {
        const char* name;
        int w, h, c;
        const char* warning;
    };
    for (const Want& want : {Want{"Flat", 16, 8, 4, "from 40x20 to 16x8"}, Want{"Gradient", 16, 10, 3, "from 37x23 to 16x10"},
                             Want{"Checker", 16, 16, 1, "from 32x32 to 16x16"}, Want{"FlatGA", 16, 16, 2, "from 30x30 to 16x16"},
                             Want{"Fringe", 16, 16, 4, "from 32x32 to 16x16"}}) {
        const Pixels p = decode(by[want.name]->data);
        if (p.w != want.w || p.h != want.h || p.c != want.c) {
            std::fprintf(stderr, "%s: %dx%dx%d, want %dx%dx%d\n", want.name, p.w, p.h, p.c, want.w, want.h, want.c);
        }
        CHECK(p.w == want.w && p.h == want.h && p.c == want.c);
        CHECK(has_warning(im, std::string("material '") + want.name + "': texture") && has_warning(im, want.warning));
    }
    CHECK(has_warning(im, "exceeds the maximum 16; downscaled from 40x20 to 16x8"));
    // Below the limit: passed through byte for byte, no warning.
    CHECK(by["Small"]->data == s.images[5].png && !has_warning(im, "'Small'"));

    // Flat colours are unchanged (RGBA and grey + alpha, non-integer ratios 2.5 and 1.875).
    const Pixels flat = decode(by["Flat"]->data), flat_ga = decode(by["FlatGA"]->data);
    bool flat_ok = true;
    for (int y = 0; y < flat.h; ++y) {
        for (int x = 0; x < flat.w; ++x) {
            const unsigned char* q = flat.at(x, y);
            flat_ok = flat_ok && q[0] == 200 && q[1] == 100 && q[2] == 50 && q[3] == 128;
        }
    }
    for (int y = 0; y < flat_ga.h; ++y) {
        for (int x = 0; x < flat_ga.w; ++x) flat_ok = flat_ok && flat_ga.at(x, y)[0] == 90 && flat_ga.at(x, y)[1] == 200;
    }
    CHECK(flat_ok);
    // Linear light: a 1-pixel black / white checker halves to linear 0.5 = sRGB 188 everywhere (not 128).
    const Pixels checker = decode(by["Checker"]->data);
    const int mid_grey = static_cast<int>(std::lround(srgb_encode(0.5) * 255.0));
    CHECK(mid_grey == 188);
    bool checker_ok = true;
    for (int y = 0; y < checker.h; ++y) {
        for (int x = 0; x < checker.w; ++x) checker_ok = checker_ok && checker.at(x, y)[0] == mid_grey;
    }
    CHECK(checker_ok);
    // Straight alpha, colour weighted by alpha: opaque white beside transparent black -> white at half alpha.
    const Pixels fringe = decode(by["Fringe"]->data);
    bool fringe_ok = true;
    for (int y = 0; y < fringe.h; ++y) {
        for (int x = 0; x < fringe.w; ++x) {
            const unsigned char* q = fringe.at(x, y);
            fringe_ok = fringe_ok && q[0] == 255 && q[1] == 255 && q[2] == 255 && (q[3] == 127 || q[3] == 128);
        }
    }
    CHECK(fringe_ok);
    // A gradient's (linear-light) average survives within 1/255 (compared in sRGB units), every channel.
    const Pixels grad = decode(by["Gradient"]->data), grad_src = source("Gradient");
    CHECK(grad_src.w == 37 && grad_src.h == 23 && grad_src.c == 3);
    for (int c = 0; c < 3; ++c) {
        const double a = srgb_encode(linear_mean(grad_src, c)) * 255.0, b = srgb_encode(linear_mean(grad, c)) * 255.0;
        std::printf("gradient channel %d: source mean %.4f, downscaled mean %.4f (sRGB units)\n", c, a, b);
        CHECK(std::fabs(a - b) <= 1.0);
    }
    // Horizontal / vertical ramps stay monotonic.
    bool ramps = true;
    for (int x = 1; x < grad.w; ++x) ramps = ramps && grad.at(x, 0)[0] >= grad.at(x - 1, 0)[0];
    for (int y = 1; y < grad.h; ++y) ramps = ramps && grad.at(0, y)[1] >= grad.at(0, y - 1)[1];
    CHECK(ramps);
    // Deterministic.
    const Imported again = import_file(file, &o);
    CHECK(again.rc == CO_OK && again.doc.images.size() == im.doc.images.size());
    for (std::size_t i = 0; i < std::min(again.doc.images.size(), im.doc.images.size()); ++i) {
        CHECK(again.doc.images[i].data == im.doc.images[i].data);
    }
}

// ---- animation key reduction ----------------------------------------------------------------------------------

struct PoseError {
    double world = 0, skin = 0;
};

// Poses the imported clip `clip_name` through the native rig at `samples` + 1 times over [0, t_end] and returns the
// worst bone-world and skin-matrix differences against the glTF animation evaluated independently here.
PoseError pose_error(const Imported& im, const imp::Scene& s, const std::string& clip_name, double t_end, int samples) {
    PoseError out;
    RigOf rig;
    build_rig(im.doc, rig);
    const oa::Clip* clip = nullptr;
    for (const auto& [name, c] : im.clips) {
        if (name == clip_name) clip = &c;
    }
    const imp::Animation* anim = nullptr;
    for (const auto& a : s.animations) {
        if (a.name == clip_name) anim = &a;
    }
    CHECK(rig.rig != nullptr && clip != nullptr && anim != nullptr);
    if (rig.rig == nullptr || clip == nullptr || anim == nullptr) return {1e9, 1e9};
    cr_clip* cc = make_clip(*clip);
    CHECK(cc != nullptr);
    if (cc == nullptr) return {1e9, 1e9};
    const int nj = cr_rig_joint_count(rig.rig);
    std::vector<float> skin(uz(nj) * 16), world(uz(nj) * 16);
    const auto w0 = worlds(s, nullptr, 0);
    const Conv cv;
    for (int i = 0; i <= samples; ++i) {
        const double t = t_end * i / samples;
        CHECK(cr_rig_pose(rig.rig, cc, static_cast<float>(t), nullptr, 0, nullptr, 0, nullptr, skin.data(),
                          world.data(), nullptr) == CR_OK);
        const auto wt = worlds(s, anim, t);
        for (const auto& b : im.doc.bones) {
            const int j = rig.find(b.name);
            const std::size_t n = uz(node_named(s, b.name));
            out.world = std::max(out.world, max_diff(of(world.data() + j * 16), expected_bone_world(cv, wt[n], w0[n])));
            out.skin = std::max(out.skin, max_diff(of(skin.data() + j * 16), cv.conj(mul(wt[n], inv(w0[n])))));
        }
    }
    cr_clip_destroy(cc);
    return out;
}

std::map<std::string, std::size_t> key_counts(const Imported& im, const std::string& clip_name) {
    std::map<std::string, std::size_t> out;
    for (const auto& [name, c] : im.clips) {
        if (name != clip_name) continue;
        for (const auto& t : c.tracks) out[t.part_name] = t.keys.size();
    }
    return out;
}

const oa::Track* track_of(const Imported& im, const std::string& clip_name, std::string_view part) {
    for (const auto& [name, c] : im.clips) {
        if (name != clip_name) continue;
        for (const auto& t : c.tracks) {
            if (t.part_name == part) return &t;
        }
    }
    return nullptr;
}

// Imports `file` reduced (defaults) and dense (CO_IMPORT_KEEP_ALL_KEYS); prints the key counts and the posing errors
// of both. Returns {total dense, total reduced}.
std::pair<std::size_t, std::size_t> compare_reduction(const fs::path& file, const std::string& clip, double t_end,
                                                      Imported& reduced, Imported& dense, PoseError& e_reduced,
                                                      PoseError& e_dense) {
    co_import_options keep;
    co_import_options_default(&keep);
    keep.flags |= CO_IMPORT_KEEP_ALL_KEYS;
    reduced = import_file(file);
    dense = import_file(file, &keep);
    CHECK(reduced.rc == CO_OK && dense.rc == CO_OK);
    if (reduced.rc != CO_OK || dense.rc != CO_OK) return {0, 0};
    const imp::Scene s = read_scene(file);
    const auto before = key_counts(dense, clip), after = key_counts(reduced, clip);
    CHECK(before.size() == after.size());
    std::size_t total_before = 0, total_after = 0;
    for (const auto& [name, n] : before) {
        const std::size_t m = after.count(name) ? after.at(name) : 0;
        std::printf("  %s %s: %zu -> %zu keys\n", file.filename().string().c_str(), name.c_str(), n, m);
        total_before += n;
        total_after += m;
        // First and last keys stay (same times, same values).
        const oa::Track* td = track_of(dense, clip, name);
        const oa::Track* tr = track_of(reduced, clip, name);
        CHECK(td && tr && !td->keys.empty() && !tr->keys.empty());
        if (td && tr && !td->keys.empty() && !tr->keys.empty()) {
            CHECK(tr->keys.front().time == td->keys.front().time && tr->keys.back().time == td->keys.back().time);
            CHECK(std::memcmp(tr->keys.front().rot, td->keys.front().rot, sizeof td->keys.front().rot) == 0);
            CHECK(std::memcmp(tr->keys.back().pos, td->keys.back().pos, sizeof td->keys.back().pos) == 0);
        }
    }
    e_reduced = pose_error(reduced, s, clip, t_end, 997);
    e_dense = pose_error(dense, s, clip, t_end, 997);
    std::printf("  %s %s: %zu -> %zu keys (%.1f%% fewer); worst world %.3g (dense %.3g), worst skin %.3g (dense %.3g)\n",
                file.filename().string().c_str(), clip.c_str(), total_before, total_after,
                total_before > 0 ? 100.0 * (1.0 - static_cast<double>(total_after) / static_cast<double>(total_before)) : 0.0,
                e_reduced.world, e_dense.world, e_reduced.skin, e_dense.skin);
    return {total_before, total_after};
}

void test_key_reduction() {
    // baked.gltf: a 60 fps export, 121 keys per channel. Constant / linear stretches collapse to their ends.
    {
        Imported reduced, dense;
        PoseError er, ed;
        const auto [before, after] = compare_reduction(kOwn / "baked.gltf", "Baked", 2.0, reduced, dense, er, ed);
        auto n = key_counts(reduced, "Baked"), nd = key_counts(dense, "Baked");
        CHECK(nd.size() == 3 && nd["Root"] == 121 && nd["Arm"] == 121 && nd["Wiggle"] == 121);
        CHECK(n["Root"] == 2);    // a straight line, rotation / scale constant
        CHECK(n["Arm"] == 3);     // turn, then hold
        CHECK(n["Wiggle"] <= 121 && n["Wiggle"] >= 60);  // a sine sampled every frame: (nearly) every key needed
        CHECK(after * 100 <= before * 40);  // >= 60% fewer
        const oa::Track* arm = track_of(reduced, "Baked", "Arm");
        if (arm != nullptr && arm->keys.size() == 3) CHECK_NEAR(arm->keys[1].time, 1.0, 1e-6);
        CHECK(er.world <= 3e-5 && er.skin <= 5e-5 && er.world <= ed.world + 1e-6 && er.skin <= ed.skin + 1e-6);
    }
    // rig.gltf "Move": large slerps resampled for the per-component Euler lerp; the reduction picks its keys from a
    // finer candidate grid, so fewer keys at the same tolerance. STEP and 2-key tracks are untouched.
    {
        Imported reduced, dense;
        PoseError er, ed;
        const auto [before, after] = compare_reduction(kOwn / "rig.gltf", "Move", 2.5, reduced, dense, er, ed);
        auto n = key_counts(reduced, "Move"), nd = key_counts(dense, "Move");
        CHECK(nd["Spine"] > 400 && n["Spine"] * 100 <= nd["Spine"] * 85);
        CHECK(after * 100 <= before * 85);  // >= 15% fewer
        CHECK(n["Tail"] == 3 && n["Hips"] == 3 && n["ArmL"] == 2);
        // Every kept segment honours the same 2e-5 as the dense resampling, but more segments now sit near it, so
        // the worst error over a dense time sweep (997 steps, between the 18 acceptance times of test_rig) grows a
        // little (measured: world 3.3e-5 -> 4.1e-5, skin 6.1e-5 -> 7.0e-5); bounded here.
        CHECK(er.world <= 1.3 * ed.world + 1e-6 && er.skin <= 1.3 * ed.skin + 1e-6);
        const oa::Track* tail = track_of(reduced, "Move", "Tail");
        const oa::Track* tail_d = track_of(dense, "Move", "Tail");
        CHECK(tail && tail_d && tail->keys.size() == tail_d->keys.size());
        if (tail && tail_d && tail->keys.size() == tail_d->keys.size()) {
            for (std::size_t k = 0; k < tail->keys.size(); ++k) {
                CHECK(tail->keys[k].easing == oa::Easing::Step && tail->keys[k].time == tail_d->keys[k].time);
            }
        }
        CHECK(er.world <= 5e-5 && er.skin <= 8e-5);
        // Deterministic: the same clips byte for byte.
        const Imported again = import_file(kOwn / "rig.gltf");
        CHECK(again.clips.size() == reduced.clips.size());
        for (std::size_t i = 0; i < std::min(again.clips.size(), reduced.clips.size()); ++i) {
            CHECK(oa::write(again.clips[i].second) == oa::write(reduced.clips[i].second));
        }
    }
}

void test_obj() {
    const fs::path file = kA3 / "model.obj";
    const Imported im = import_file(file);
    CHECK(im.rc == CO_OK);
    if (im.rc != CO_OK) return;
    const omo::Document& d = im.doc;
    check_layout(d, "model.obj");
    const omo::Part* box = part_named(d, "Box");
    const omo::Part* panel = part_named(d, "Panel");
    CHECK(box && panel && d.parts.size() == 2);
    if (!(box && panel)) return;
    // Box: the quad (s 1, one face, 2 triangles) + the triangle (s off: flat).
    CHECK(box->face_count == 2 && box->index_count == 9 && box->vertex_count == 7);
    CHECK(d.shading.smooth_face(static_cast<std::uint32_t>(box->face_start)));
    CHECK(!d.shading.smooth_face(static_cast<std::uint32_t>(box->face_start + 1)));
    // Panel: the pentagon (one face id, 3 triangles), then two triangles; smoothing group 2 stays in effect.
    CHECK(panel->face_count == 3 && panel->vertex_count == 11 && panel->index_count == 15);
    for (std::int32_t f = panel->face_start; f < panel->face_start + panel->face_count; ++f) {
        CHECK(d.shading.smooth_face(static_cast<std::uint32_t>(f)));
    }
    std::size_t pent_tris = 0;
    for (std::int32_t t : *d.mesh->triangle_to_face_id) {
        if (t == panel->face_start) ++pent_tris;
    }
    CHECK(pent_tris == 3);
    // vn -> custom normals for the corners that have one (Box 7 + pentagon 5; the panel's Wood / Missing
    // triangles have no vn).
    CHECK(d.shading.normal_vertices.size() == 12);
    // UVs: OBJ vt is v-up; OMO is v-down. The Box quad's vt (0,0) (1,0) (1,1) (0,1) equal its source xy.
    const auto& tc = *d.mesh->tex_coords;
    for (std::int32_t v = 0; v < 4; ++v) {
        const V3 p = vertex(d, box->vertex_start + v);  // converted: x negated
        CHECK_NEAR(tc[uz(box->vertex_start + v) * 2], -p.x, 1e-6);
        CHECK_NEAR(tc[uz(box->vertex_start + v) * 2 + 1], 1.0 - p.y, 1e-6);
    }
    // Materials: Wood (Kd, texture), Paint (Kd, Tr 0.25 -> translucent), the missing one -> Default.
    std::map<std::string, const omo::Material*> by;
    for (const auto& m : d.face_textures->materials) by[*m.name] = &m;
    CHECK(by.count("Wood") && by.count("Paint") && by.count("Default"));
    if (by.count("Wood")) {
        CHECK(by["Wood"]->texture_file.has_value() && (*by["Wood"]->base_color)[0] == 0.8f);
        if (by["Wood"]->texture_file) CHECK(d.image(*by["Wood"]->texture_file) != nullptr);
    }
    if (by.count("Paint")) CHECK(by["Paint"]->render_layer == "TRANSLUCENT" && !by["Paint"]->texture_file);
}

void test_other_a3_fixtures() {
    // strip / fan / points: the points primitive is skipped by the reader, the rest imports and lays out.
    for (const char* f : {"strip.gltf", "animation.gltf"}) {
        const Imported im = import_file(kA3 / f);
        CHECK(im.rc == CO_OK);
        if (im.rc == CO_OK) check_layout(im.doc, f);
    }
    // animation.gltf animates a mesh node (not a joint: skipped, warning) + CUBICSPLINE morph weights: a clip with only
    // morph tracks (#171), Hermite keys with the glTF tangents.
    const Imported anim = import_file(kA3 / "animation.gltf");
    CHECK(anim.rc == CO_OK && anim.clips.size() == 1);
    CHECK(!has_warning(anim, "morph weight channels skipped") && !has_warning(anim, "nothing to import"));
    CHECK(has_warning(anim, "'Mover' skipped"));
    if (anim.clips.size() == 1) {
        CHECK(anim.clips[0].second.tracks.empty() && anim.clips[0].second.morphs.size() == 2);
        CHECK(check_morph_tracks(read_scene(kA3 / "animation.gltf"), anim) == 2);
    }
    // hierarchy.gltf has no meshes: an empty document, no parts.
    const Imported h = import_file(kA3 / "hierarchy.gltf");
    CHECK(h.rc == CO_OK && h.doc.parts.empty() && !h.doc.mesh);
    // Bytes entry point with a base dir resolves the external buffer; without one it cannot.
    const auto bytes = load(kA3 / "skin_strip.gltf");
    co_import_result* r = nullptr;
    CHECK(co_import_bytes(reinterpret_cast<const uint8_t*>(bytes.data()), static_cast<int64_t>(bytes.size()),
                          CO_IMPORT_FORMAT_AUTO, kA3.string().c_str(), nullptr, &r) == CO_OK);
    co_import_counts c{};
    CHECK(co_import_result_counts(r, &c) == CO_OK);
    CHECK(c.bones == 2 && c.parts == 1);
    co_import_result_destroy(r);
    r = nullptr;
    CHECK(co_import_bytes(reinterpret_cast<const uint8_t*>(bytes.data()), static_cast<int64_t>(bytes.size()),
                          CO_IMPORT_FORMAT_AUTO, nullptr, nullptr, &r) == CO_ERR_FORMAT);
    CHECK(r == nullptr);
}

}  // namespace

int main() {
    test_defaults_and_args();
    test_malformed();
    test_skin_strip();
    test_rig();
    test_nonuniform();
    test_morph_cube_and_quads();
    test_groups();
    test_materials();
    test_texture_downscale();
    test_key_reduction();
    test_obj();
    test_other_a3_fixtures();
    if (failures != 0) {
        std::fprintf(stderr, "import_omo: %d check(s) failed\n", failures);
        return 1;
    }
    std::printf("import_omo: all checks passed\n");
    return 0;
}
