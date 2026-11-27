// mesh_pipeline ctest (OpenMason #175 F3): the native weld / import / triangulation / render mesh / topology of
// cenda/model_mesh.h against
//   - the hand-traced Java golden fixtures in fixtures/mesh_pipeline/*.json (format + Java parity plan: README.md),
//   - literal ports of VertexWelder.weld and MeshImporter.importSoup (Java's HashMap / LinkedHashMap walked the slow,
//     obvious way) over seeded random soups - the native code uses hashing, pruned cell scans and sorting instead,
//   - the cases of the Java tests (MeshImporterTest, RenderMeshBuilderTest, PolygonTriangulatorTest),
//   - co_mesh_move_vertices == a full rebuild, bit for bit, over random drags,
// plus a 54k-triangle benchmark (stage times printed). Plain CHECKs, -ffp-contract=off like the library.

#include "cenda/model_mesh.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <limits>
#include <map>
#include <random>
#include <sstream>
#include <string>
#include <unordered_map>
#include <utility>
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

#define CHECK_MSG(cond, ...)                                                       \
    do {                                                                           \
        if (!(cond)) {                                                             \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s: ", __FILE__, __LINE__, #cond); \
            std::fprintf(stderr, __VA_ARGS__);                                     \
            std::fprintf(stderr, "\n");                                            \
            ++failures;                                                            \
        }                                                                          \
    } while (0)

template <typename T>
std::vector<T> arr(const co_mesh* m, int32_t which) {
    const void* p = nullptr;
    const int64_t n = co_mesh_array(m, which, &p);
    if (n < 0) return {};
    const auto* t = static_cast<const T*>(p);
    return std::vector<T>(t, t + n);
}
std::vector<int32_t> ia(const co_mesh* m, int32_t which) { return arr<int32_t>(m, which); }
std::vector<float> fa(const co_mesh* m, int32_t which) { return arr<float>(m, which); }

co_mesh_info info(const co_mesh* m) {
    co_mesh_info i{};
    co_mesh_info_get(m, &i);
    return i;
}

std::vector<int32_t> csr_row(const std::vector<int32_t>& off, const std::vector<int32_t>& vals, size_t r) {
    return std::vector<int32_t>(vals.begin() + off[r], vals.begin() + off[r + 1]);
}

// ---------------------------------------------------------------------------------------------------------------
// Minimal JSON reader (numbers kept as text so floats parse with strtof, never through double).

struct J {
    enum Type { Null, Bool, Num, Str, Arr, Obj } t = Null;
    bool b = false;
    std::string s;
    std::vector<J> a;
    std::vector<std::pair<std::string, J>> o;

    const J* get(const std::string& k) const {
        for (const auto& kv : o) {
            if (kv.first == k) return &kv.second;
        }
        return nullptr;
    }
    float f() const { return std::strtof(s.c_str(), nullptr); }
    int32_t i() const { return static_cast<int32_t>(std::strtol(s.c_str(), nullptr, 10)); }
    std::vector<float> floats() const {
        std::vector<float> r;
        for (const J& x : a) r.push_back(x.f());
        return r;
    }
    std::vector<int32_t> ints() const {
        std::vector<int32_t> r;
        for (const J& x : a) r.push_back(x.i());
        return r;
    }
};

struct JsonParser {
    const char* p;
    const char* end;
    bool ok = true;

    void ws() {
        while (p < end && (*p == ' ' || *p == '\n' || *p == '\r' || *p == '\t')) ++p;
    }
    bool lit(const char* w) {
        const size_t n = std::strlen(w);
        if (static_cast<size_t>(end - p) >= n && std::strncmp(p, w, n) == 0) {
            p += n;
            return true;
        }
        return false;
    }
    std::string str() {
        std::string r;
        ++p;  // "
        while (p < end && *p != '"') {
            if (*p == '\\' && p + 1 < end) {
                ++p;
                r.push_back(*p == 'n' ? '\n' : *p);
            } else {
                r.push_back(*p);
            }
            ++p;
        }
        ++p;
        return r;
    }
    J value() {
        ws();
        J v;
        if (p >= end) {
            ok = false;
            return v;
        }
        if (*p == '{') {
            v.t = J::Obj;
            ++p;
            ws();
            if (*p == '}') {
                ++p;
                return v;
            }
            while (ok) {
                ws();
                if (*p != '"') {
                    ok = false;
                    break;
                }
                std::string k = str();
                ws();
                if (*p != ':') {
                    ok = false;
                    break;
                }
                ++p;
                v.o.emplace_back(k, value());
                ws();
                if (*p == ',') {
                    ++p;
                    continue;
                }
                if (*p == '}') {
                    ++p;
                    break;
                }
                ok = false;
            }
        } else if (*p == '[') {
            v.t = J::Arr;
            ++p;
            ws();
            if (*p == ']') {
                ++p;
                return v;
            }
            while (ok) {
                v.a.push_back(value());
                ws();
                if (*p == ',') {
                    ++p;
                    continue;
                }
                if (*p == ']') {
                    ++p;
                    break;
                }
                ok = false;
            }
        } else if (*p == '"') {
            v.t = J::Str;
            v.s = str();
        } else if (lit("null")) {
            v.t = J::Null;
        } else if (lit("true")) {
            v.t = J::Bool;
            v.b = true;
        } else if (lit("false")) {
            v.t = J::Bool;
        } else {
            v.t = J::Num;
            const char* s = p;
            while (p < end && (std::strchr("+-0123456789.eE", *p) != nullptr)) ++p;
            v.s.assign(s, p);
            if (v.s.empty()) ok = false;
        }
        return v;
    }
};

bool parse_json(const std::string& text, J& out) {
    JsonParser jp{text.data(), text.data() + text.size()};
    out = jp.value();
    return jp.ok;
}

// ---------------------------------------------------------------------------------------------------------------
// Fixtures.

struct FixtureCtx {
    std::string name;
    float tol = 1e-6f;
};

bool feq(float a, float b, float tol) { return std::fabs(a - b) <= tol || (std::isnan(a) && std::isnan(b)); }

void expect_ints(const FixtureCtx& c, const char* what, const std::vector<int32_t>& exp, const std::vector<int32_t>& got) {
    bool same = exp == got;
    CHECK_MSG(same, "[%s] %s: expected %zu ints, got %zu (first mismatch at %zu)", c.name.c_str(), what, exp.size(),
              got.size(), [&] {
                  size_t i = 0;
                  while (i < exp.size() && i < got.size() && exp[i] == got[i]) ++i;
                  return i;
              }());
}

void expect_floats(const FixtureCtx& c, const char* what, const std::vector<float>& exp, const float* got,
                   size_t got_n) {
    bool same = exp.size() == got_n;
    size_t bad = 0;
    for (size_t i = 0; same && i < exp.size(); ++i) {
        if (!feq(exp[i], got[i], c.tol)) {
            same = false;
            bad = i;
        }
    }
    CHECK_MSG(same, "[%s] %s: size %zu vs %zu, first mismatch at %zu (%.9g vs %.9g)", c.name.c_str(), what,
              exp.size(), got_n, bad, bad < exp.size() ? static_cast<double>(exp[bad]) : 0.0,
              bad < got_n ? static_cast<double>(got[bad]) : 0.0);
}

void expect_csr(const FixtureCtx& c, const char* what, const J& rows, const std::vector<int32_t>& off,
                const std::vector<int32_t>& vals) {
    CHECK_MSG(off.size() == rows.a.size() + 1, "[%s] %s: %zu rows expected, got %zu", c.name.c_str(), what,
              rows.a.size(), off.empty() ? 0 : off.size() - 1);
    if (off.size() != rows.a.size() + 1) return;
    for (size_t r = 0; r < rows.a.size(); ++r) {
        const std::string label = std::string(what) + "[" + std::to_string(r) + "]";
        expect_ints(c, label.c_str(), rows.a[r].ints(), csr_row(off, vals, r));
    }
}

int32_t edge_kind_of(const std::string& s) {
    if (s == "OPEN") return CO_MESH_EDGE_OPEN;
    if (s == "MANIFOLD") return CO_MESH_EDGE_MANIFOLD;
    return CO_MESH_EDGE_NON_MANIFOLD;
}

int32_t warn_kind_of(const std::string& s) {
    if (s == "BOUNDARY_WALK_FAILED") return CO_MESH_WARN_BOUNDARY_WALK_FAILED;
    if (s == "DEGENERATE_FACE") return CO_MESH_WARN_DEGENERATE_FACE;
    return CO_MESH_WARN_CORRUPT_INDICES;
}

void run_fixture(const std::filesystem::path& path) {
    std::ifstream in(path);
    std::stringstream ss;
    ss << in.rdbuf();
    J root;
    if (!parse_json(ss.str(), root) || root.t != J::Obj) {
        CHECK_MSG(false, "fixture %s does not parse", path.string().c_str());
        return;
    }
    FixtureCtx c;
    c.name = root.get("name") ? root.get("name")->s : path.filename().string();
    if (const J* t = root.get("tolerance")) c.tol = t->f();
    const J& input = *root.get("input");
    const J& expected = *root.get("expected");
    const std::string kind = input.get("kind")->s;

    co_mesh* m = co_mesh_create();
    if (const J* maps = input.get("faceMappings")) {
        std::vector<co_face_mapping> v;
        std::vector<int32_t> flags;
        for (const J& mp : maps->a) {
            co_face_mapping f{};
            f.face_id = mp.get("faceId")->i();
            f.material_id = mp.get("materialId")->i();
            f.u0 = mp.get("u0")->f();
            f.v0 = mp.get("v0")->f();
            f.u1 = mp.get("u1")->f();
            f.v1 = mp.get("v1")->f();
            f.uv_rotation_degrees = mp.get("rotation") ? mp.get("rotation")->i() : 0;
            f.auto_resize = 1;
            v.push_back(f);
            const J* au = mp.get("authoredUVs");
            flags.push_back(au != nullptr && au->b ? CO_MESH_MAPPING_AUTHORED_UVS : 0);
        }
        CHECK(co_mesh_set_mappings(m, v.data(), flags.data(), static_cast<int32_t>(v.size())) == CO_OK);
    }
    if (kind == "weld") {
        const std::vector<float> v = input.get("vertices")->floats();
        const float eps = input.get("epsilon") ? input.get("epsilon")->f() : 0.0f;
        CHECK(co_mesh_weld(m, v.data(), static_cast<int64_t>(v.size() / 3), eps) == CO_OK);
    } else if (kind == "soup") {
        const std::vector<float> v = input.get("vertices")->floats();
        const std::vector<int32_t> idx = input.get("indices")->ints();
        std::vector<float> tc;
        std::vector<int32_t> tf;
        if (const J* t = input.get("texCoords")) tc = t->floats();
        if (const J* t = input.get("triangleToFaceId")) tf = t->ints();
        const int32_t r = co_mesh_import(m, v.data(), static_cast<int64_t>(v.size() / 3), tc.empty() ? nullptr : tc.data(),
                                         static_cast<int64_t>(tc.size()), idx.data(), static_cast<int64_t>(idx.size()),
                                         tf.empty() ? nullptr : tf.data(), static_cast<int64_t>(tf.size()), 0.0f,
                                         CO_MESH_STAGE_ALL);
        CHECK_MSG(r == CO_OK, "[%s] import: %s", c.name.c_str(), co_last_error());
    } else {
        const std::vector<float> pos = input.get("positions")->floats();
        std::vector<int32_t> ids, off{0}, loops, has;
        std::vector<float> uvs;
        for (const J& f : input.get("faces")->a) {
            ids.push_back(f.get("id")->i());
            const std::vector<int32_t> loop = f.get("loop")->ints();
            loops.insert(loops.end(), loop.begin(), loop.end());
            off.push_back(static_cast<int32_t>(loops.size()));
            const J* cu = f.get("cornerUVs");
            has.push_back(cu != nullptr && cu->t == J::Arr ? 1 : 0);
            if (has.back()) {
                const std::vector<float> u = cu->floats();
                uvs.insert(uvs.end(), u.begin(), u.end());
            } else {
                uvs.insert(uvs.end(), loop.size() * 2, 0.0f);
            }
        }
        const int32_t upper = input.get("faceIdUpperBound") ? input.get("faceIdUpperBound")->i() : 0;
        const int32_t r = co_mesh_set_faces(m, pos.data(), static_cast<int32_t>(pos.size() / 3), ids.data(),
                                            static_cast<int32_t>(ids.size()), off.data(), loops.data(), has.data(),
                                            uvs.data(), upper, CO_MESH_STAGE_ALL);
        CHECK_MSG(r == CO_OK, "[%s] set_faces: %s", c.name.c_str(), co_last_error());
    }

    if (const J* w = expected.get("weld")) {
        expect_ints(c, "soupToVertex", w->get("soupToVertex")->ints(), ia(m, CO_MESH_A_SOUP_TO_VERTEX));
        if (const J* p = w->get("positions")) {
            const std::vector<float> got = fa(m, CO_MESH_A_VERTEX_POSITIONS);
            expect_floats(c, "weld.positions", p->floats(), got.data(), got.size());
        }
        expect_csr(c, "vertexToSoup", *w->get("vertexToSoup"), ia(m, CO_MESH_A_VERTEX_SOUP_OFFSETS),
                   ia(m, CO_MESH_A_VERTEX_SOUP));
    }
    if (const J* fs = expected.get("faces")) {
        const std::vector<int32_t> ids = ia(m, CO_MESH_A_FACE_IDS);
        const std::vector<int32_t> off = ia(m, CO_MESH_A_LOOP_OFFSETS);
        const std::vector<int32_t> lv = ia(m, CO_MESH_A_LOOP_VERTICES);
        const std::vector<int32_t> flags = ia(m, CO_MESH_A_FACE_FLAGS);
        const std::vector<float> luv = fa(m, CO_MESH_A_LOOP_UVS);
        const J& faces = *fs->get("faces");
        CHECK_MSG(ids.size() == faces.a.size(), "[%s] face count %zu vs %zu", c.name.c_str(), faces.a.size(), ids.size());
        for (size_t f = 0; f < faces.a.size() && f < ids.size(); ++f) {
            const J& ef = faces.a[f];
            CHECK_MSG(ids[f] == ef.get("id")->i(), "[%s] face %zu id", c.name.c_str(), f);
            expect_ints(c, "loop", ef.get("loop")->ints(), csr_row(off, lv, f));
            const J* cu = ef.get("cornerUVs");
            const bool want_uvs = cu != nullptr && cu->t == J::Arr;
            CHECK_MSG(((flags[f] & CO_MESH_FACE_AUTHORED_UVS) != 0) == want_uvs, "[%s] face %zu authored flag",
                      c.name.c_str(), f);
            if (want_uvs) {
                expect_floats(c, "cornerUVs", cu->floats(), luv.data() + off[f] * 2,
                              static_cast<size_t>(off[f + 1] - off[f]) * 2);
            }
        }
        if (const J* ub = fs->get("faceIdUpperBound")) CHECK(info(m).face_id_upper_bound == ub->i());
        if (const J* ws = fs->get("warnings")) {
            std::vector<int32_t> exp;
            for (const J& w : ws->a) {
                exp.push_back(warn_kind_of(w.get("kind")->s));
                exp.push_back(w.get("faceId")->i());
                exp.push_back(w.get("value")->i());
            }
            expect_ints(c, "warnings", exp, ia(m, CO_MESH_A_WARNINGS));
        }
    }
    if (const J* r = expected.get("render")) {
        if (const J* x = r->get("vertices")) {
            const std::vector<float> got = fa(m, CO_MESH_A_CORNER_POSITIONS);
            expect_floats(c, "render.vertices", x->floats(), got.data(), got.size());
        }
        if (const J* x = r->get("texCoords")) {
            const std::vector<float> got = fa(m, CO_MESH_A_CORNER_UVS);
            expect_floats(c, "render.texCoords", x->floats(), got.data(), got.size());
        }
        if (const J* x = r->get("indices")) expect_ints(c, "render.indices", x->ints(), ia(m, CO_MESH_A_TRI_INDICES));
        if (const J* x = r->get("triangleToFaceId")) {
            expect_ints(c, "render.triangleToFaceId", x->ints(), ia(m, CO_MESH_A_TRI_FACE_IDS));
        }
        if (const J* x = r->get("cornerToVertexId")) {
            expect_ints(c, "render.cornerToVertexId", x->ints(), ia(m, CO_MESH_A_LOOP_VERTICES));
        }
        if (const J* x = r->get("vertexIdToCorners")) {
            expect_csr(c, "vertexIdToCorners", *x, ia(m, CO_MESH_A_VERTEX_CORNER_OFFSETS), ia(m, CO_MESH_A_VERTEX_CORNERS));
        }
        if (const J* x = r->get("triangleCount")) CHECK(info(m).triangle_count == x->i());
    }
    if (const J* t = expected.get("topology")) {
        if (const J* x = t->get("edges")) {
            std::vector<int32_t> exp;
            for (const J& e : x->a) {
                exp.push_back(e.a[0].i());
                exp.push_back(e.a[1].i());
            }
            expect_ints(c, "edges", exp, ia(m, CO_MESH_A_EDGE_VERTICES));
        }
        if (const J* x = t->get("edgeFaces")) {
            expect_csr(c, "edgeFaces", *x, ia(m, CO_MESH_A_EDGE_FACE_OFFSETS), ia(m, CO_MESH_A_EDGE_FACES));
        }
        const std::vector<int32_t> slots = ia(m, CO_MESH_A_FACE_SLOTS);
        if (const J* x = t->get("faceEdges")) {
            const std::vector<int32_t> off = ia(m, CO_MESH_A_LOOP_OFFSETS);
            const std::vector<int32_t> ce = ia(m, CO_MESH_A_CORNER_EDGES);
            for (const auto& kv : x->o) {
                const auto slot = static_cast<size_t>(slots[static_cast<size_t>(std::stoi(kv.first))]);
                expect_ints(c, ("faceEdges " + kv.first).c_str(), kv.second.ints(), csr_row(off, ce, slot));
            }
        }
        if (const J* x = t->get("vertexEdges")) {
            expect_csr(c, "vertexEdges", *x, ia(m, CO_MESH_A_VERTEX_EDGE_OFFSETS), ia(m, CO_MESH_A_VERTEX_EDGES));
        }
        if (const J* x = t->get("vertexFaces")) {
            expect_csr(c, "vertexFaces", *x, ia(m, CO_MESH_A_VERTEX_FACE_OFFSETS), ia(m, CO_MESH_A_VERTEX_FACES));
        }
        if (const J* x = t->get("faceAdjacency")) {
            const std::vector<int32_t> off = ia(m, CO_MESH_A_FACE_ADJ_OFFSETS);
            const std::vector<int32_t> adj = ia(m, CO_MESH_A_FACE_ADJ);
            const std::vector<int32_t> adje = ia(m, CO_MESH_A_FACE_ADJ_EDGES);
            for (size_t id = 0; id + 1 < off.size(); ++id) {
                const J* row = x->get(std::to_string(id));
                const std::string label = "faceAdjacency " + std::to_string(id);
                expect_ints(c, label.c_str(), row ? row->get("faces")->ints() : std::vector<int32_t>{},
                            csr_row(off, adj, id));
                expect_ints(c, (label + " edges").c_str(), row ? row->get("edges")->ints() : std::vector<int32_t>{},
                            csr_row(off, adje, id));
            }
        }
        auto by_id3 = [&](const char* key, int32_t which) {
            if (const J* x = t->get(key)) {
                const std::vector<float> got = fa(m, which);
                for (const auto& kv : x->o) {
                    const auto id = static_cast<size_t>(std::stoi(kv.first));
                    expect_floats(c, (std::string(key) + " " + kv.first).c_str(), kv.second.floats(), got.data() + id * 3, 3);
                }
            }
        };
        by_id3("faceNormals", CO_MESH_A_FACE_NORMALS);
        by_id3("faceCentroids", CO_MESH_A_FACE_CENTROIDS);
        if (const J* x = t->get("faceAreas")) {
            const std::vector<float> got = fa(m, CO_MESH_A_FACE_AREAS);
            for (const auto& kv : x->o) {
                const auto id = static_cast<size_t>(std::stoi(kv.first));
                expect_floats(c, ("faceArea " + kv.first).c_str(), {kv.second.f()}, got.data() + id, 1);
            }
        }
        if (const J* x = t->get("edgeKinds")) {
            std::vector<int32_t> exp;
            for (const J& k : x->a) exp.push_back(edge_kind_of(k.s));
            expect_ints(c, "edgeKinds", exp, ia(m, CO_MESH_A_EDGE_KIND));
        }
        if (const J* x = t->get("edgeDihedral")) {
            std::vector<float> exp;
            for (const J& d : x->a) exp.push_back(d.t == J::Null ? std::numeric_limits<float>::quiet_NaN() : d.f());
            const std::vector<float> got = fa(m, CO_MESH_A_EDGE_DIHEDRAL);
            expect_floats(c, "edgeDihedral", exp, got.data(), got.size());
        }
    }
    co_mesh_destroy(m);
}

int run_fixtures() {
    const std::filesystem::path dir = std::filesystem::path(__FILE__).parent_path() / "fixtures" / "mesh_pipeline";
    std::vector<std::filesystem::path> files;
    if (std::filesystem::is_directory(dir)) {
        for (const auto& e : std::filesystem::directory_iterator(dir)) {
            if (e.path().extension() == ".json") files.push_back(e.path());
        }
    }
    std::sort(files.begin(), files.end());
    for (const auto& f : files) run_fixture(f);
    return static_cast<int>(files.size());
}

// ---------------------------------------------------------------------------------------------------------------
// Literal Java ports (reference implementations).

int32_t ref_d2i(double d) {
    if (d != d) return 0;
    if (d >= 2147483647.0) return std::numeric_limits<int32_t>::max();
    if (d <= -2147483648.0) return std::numeric_limits<int32_t>::min();
    return static_cast<int32_t>(d);
}
uint64_t ref_cell_key(int32_t x, int32_t y, int32_t z) {
    const auto lx = static_cast<int64_t>(x), ly = static_cast<int64_t>(y), lz = static_cast<int64_t>(z);
    return static_cast<uint64_t>(((lx & 0x1FFFFFLL) << 42) | ((ly & 0x1FFFFFLL) << 21) | (lz & 0x1FFFFFLL));
}
int32_t jadd(int32_t a, int32_t b) { return static_cast<int32_t>(static_cast<uint32_t>(a) + static_cast<uint32_t>(b)); }

// VertexWelder.weld, line by line (full 27-cell scan).
std::vector<int32_t> ref_weld(const std::vector<float>& v, float eps, std::vector<int32_t>* reps_out = nullptr) {
    const size_t n = v.size() / 3;
    std::vector<int32_t> s2w(n);
    std::vector<int32_t> reps;
    const float cell = eps * 2.0f;
    const float eps_sq = eps * eps;
    std::unordered_map<uint64_t, std::vector<int32_t>> hash;
    for (size_t i = 0; i < n; ++i) {
        const float x = v[i * 3], y = v[i * 3 + 1], z = v[i * 3 + 2];
        const int32_t cx = ref_d2i(std::floor(static_cast<double>(x / cell)));
        const int32_t cy = ref_d2i(std::floor(static_cast<double>(y / cell)));
        const int32_t cz = ref_d2i(std::floor(static_cast<double>(z / cell)));
        int32_t matched = -1;
        for (int32_t dx = -1; dx <= 1 && matched < 0; ++dx) {
            for (int32_t dy = -1; dy <= 1 && matched < 0; ++dy) {
                for (int32_t dz = -1; dz <= 1 && matched < 0; ++dz) {
                    auto it = hash.find(ref_cell_key(jadd(cx, dx), jadd(cy, dy), jadd(cz, dz)));
                    if (it == hash.end()) continue;
                    for (int32_t cand : it->second) {
                        const float ddx = x - v[static_cast<size_t>(cand) * 3];
                        const float ddy = y - v[static_cast<size_t>(cand) * 3 + 1];
                        const float ddz = z - v[static_cast<size_t>(cand) * 3 + 2];
                        if (ddx * ddx + ddy * ddy + ddz * ddz < eps_sq) {
                            matched = s2w[static_cast<size_t>(cand)];
                            break;
                        }
                    }
                }
            }
        }
        if (matched >= 0) {
            s2w[i] = matched;
        } else {
            s2w[i] = static_cast<int32_t>(reps.size());
            reps.push_back(static_cast<int32_t>(i));
        }
        hash[ref_cell_key(cx, cy, cz)].push_back(static_cast<int32_t>(i));
    }
    if (reps_out) *reps_out = reps;
    return s2w;
}

struct RefImport {
    std::vector<int32_t> soup_to_vertex;
    std::vector<int32_t> face_ids;
    std::vector<std::vector<int32_t>> loops;
    std::vector<std::vector<float>> uvs;  // empty = none
    std::vector<int32_t> warnings;
};

// Insertion-ordered map, the slow obvious way.
template <typename K, typename V>
struct LinkedMap {
    std::vector<std::pair<K, V>> items;
    V* find(const K& k) {
        for (auto& kv : items) {
            if (kv.first == k) return &kv.second;
        }
        return nullptr;
    }
    void put(const K& k, const V& v) {
        if (V* e = find(k)) {
            *e = v;
        } else {
            items.emplace_back(k, v);
        }
    }
};

std::vector<int32_t> ref_reconstruct(int32_t face_id, const std::vector<std::array<int32_t, 3>>& tris, RefImport& out) {
    if (tris.size() == 1) return {tris[0][0], tris[0][1], tris[0][2]};
    LinkedMap<uint64_t, int32_t> counts;
    auto key = [](int32_t s, int32_t d) {
        return (static_cast<uint64_t>(static_cast<uint32_t>(s)) << 32) | static_cast<uint32_t>(d);
    };
    for (const auto& t : tris) {
        for (int k = 0; k < 3; ++k) {
            const uint64_t e = key(t[static_cast<size_t>(k)], t[static_cast<size_t>((k + 1) % 3)]);
            if (int32_t* c = counts.find(e)) {
                ++*c;
            } else {
                counts.put(e, 1);
            }
        }
    }
    LinkedMap<int32_t, int32_t> next;
    for (const auto& kv : counts.items) {
        const auto src = static_cast<int32_t>(kv.first >> 32);
        const auto dst = static_cast<int32_t>(static_cast<uint32_t>(kv.first));
        if (counts.find(key(dst, src)) == nullptr) next.put(src, dst);
    }
    if (next.items.size() >= 3) {
        int32_t start = tris[0][0];
        if (next.find(start) == nullptr) start = next.items[0].first;
        std::vector<int32_t> poly;
        int32_t current = start;
        do {
            poly.push_back(current);
            const int32_t* n = next.find(current);
            if (n == nullptr) break;
            current = *n;
        } while (current != start && poly.size() < next.items.size());
        if (poly.size() >= 3 && current == start && poly.size() == next.items.size()) return poly;
    }
    out.warnings.insert(out.warnings.end(),
                        {CO_MESH_WARN_BOUNDARY_WALK_FAILED, face_id, static_cast<int32_t>(tris.size())});
    std::vector<int32_t> seen;
    for (const auto& t : tris) {
        for (int32_t v : t) {
            if (std::find(seen.begin(), seen.end(), v) == seen.end()) seen.push_back(v);
        }
    }
    return seen;
}

// MeshImporter.importSoup, line by line.
RefImport ref_import(const std::vector<float>& v, const std::vector<float>* tc, const std::vector<int32_t>& idx,
                     const std::vector<int32_t>* tf) {
    RefImport r;
    if (v.size() < 9 || idx.size() < 3) return r;
    r.soup_to_vertex = ref_weld(v, 1e-4f);
    const auto S = static_cast<int32_t>(v.size() / 3);
    const bool has_uvs = tc != nullptr && tc->size() >= (v.size() / 3) * 2;
    LinkedMap<int32_t, std::vector<std::array<int32_t, 3>>> face_tris;
    std::map<int32_t, std::map<int32_t, int32_t>> welded_to_soup;
    int32_t skipped = 0;
    for (size_t t = 0; t < idx.size() / 3; ++t) {
        const int32_t sa = idx[t * 3], sb = idx[t * 3 + 1], sc = idx[t * 3 + 2];
        if (sa < 0 || sa >= S || sb < 0 || sb >= S || sc < 0 || sc >= S) {
            ++skipped;
            continue;
        }
        const int32_t a = r.soup_to_vertex[static_cast<size_t>(sa)];
        const int32_t b = r.soup_to_vertex[static_cast<size_t>(sb)];
        const int32_t c = r.soup_to_vertex[static_cast<size_t>(sc)];
        if (a == b || b == c || a == c) continue;
        const int32_t face_id = (tf != nullptr && t < tf->size()) ? (*tf)[t] : static_cast<int32_t>(t);
        if (face_id < 0) continue;
        if (auto* l = face_tris.find(face_id)) {
            l->push_back({a, b, c});
        } else {
            face_tris.put(face_id, {{a, b, c}});
        }
        if (has_uvs) {
            auto& w2s = welded_to_soup[face_id];
            w2s.emplace(a, sa);  // putIfAbsent
            w2s.emplace(b, sb);
            w2s.emplace(c, sc);
        }
    }
    if (skipped > 0) r.warnings.insert(r.warnings.end(), {CO_MESH_WARN_CORRUPT_INDICES, -1, skipped});
    for (const auto& kv : face_tris.items) {
        std::vector<int32_t> loop = ref_reconstruct(kv.first, kv.second, r);
        if (loop.size() < 3) {
            r.warnings.insert(r.warnings.end(),
                              {CO_MESH_WARN_DEGENERATE_FACE, kv.first, static_cast<int32_t>(loop.size())});
            continue;
        }
        std::vector<float> uv;
        if (has_uvs) {
            const auto& w2s = welded_to_soup[kv.first];
            for (int32_t lv : loop) {
                const auto si = static_cast<size_t>(w2s.at(lv));
                uv.push_back((*tc)[si * 2]);
                uv.push_back((*tc)[si * 2 + 1]);
            }
        }
        r.face_ids.push_back(kv.first);
        r.loops.push_back(loop);
        r.uvs.push_back(uv);
    }
    return r;
}

void test_reference_weld() {
    std::mt19937 rng(1234);
    std::uniform_real_distribution<float> u01(0.0f, 1.0f);
    co_mesh* m = co_mesh_create();
    int cases = 0;
    for (int round = 0; round < 60; ++round) {
        const float eps = (round % 3 == 0) ? 1e-4f : (round % 3 == 1 ? 1e-3f : 0.05f);
        const float cell = eps * 2.0f;
        std::vector<float> v;
        const int n = 200 + round * 20;
        for (int i = 0; i < n; ++i) {
            const int mode = static_cast<int>(rng() % 8);
            float p[3];
            for (float& c : p) {
                switch (mode) {
                    case 0: c = static_cast<float>(static_cast<int>(rng() % 9) - 4) * cell; break;  // cell boundaries
                    case 1: c = (static_cast<float>(static_cast<int>(rng() % 9) - 4) + 0.5f) * cell; break;  // centres
                    case 2: c = (u01(rng) - 0.5f) * 8.0f * eps; break;                 // clusters near the origin
                    case 3: c = 100.0f + (u01(rng) - 0.5f) * 6.0f * eps; break;          // larger coordinates
                    case 4: c = -3.5f + static_cast<float>(rng() % 3) * eps * 0.999f; break;  // chains at ~eps
                    case 5: c = 2.0e6f + (u01(rng) - 0.5f) * 0.5f; break;                // > 2^22 cells (no pruning)
                    case 6: c = (u01(rng) - 0.5f) * 4.0f; break;
                    default: c = v.empty() ? 0.0f : v[(rng() % (v.size() / 3)) * 3 + (rng() % 3)]; break;  // repeats
                }
            }
            v.insert(v.end(), p, p + 3);
        }
        if (round == 7) {  // non-finite + huge values (saturated cells, wrapped neighbour keys)
            const float inf = std::numeric_limits<float>::infinity();
            const float nan = std::numeric_limits<float>::quiet_NaN();
            v.insert(v.end(), {nan, 0, 0, nan, 0, 0, inf, 1, 1, inf, 1, 1, -inf, 0, 0, 3e38f, 0, 0, 3e38f, 0, 0,
                               -3e38f, 1e30f, 0, 1e9f, 1e9f, 1e9f});
        }
        std::vector<int32_t> reps;
        const std::vector<int32_t> ref = ref_weld(v, eps, &reps);
        CHECK(co_mesh_weld(m, v.data(), static_cast<int64_t>(v.size() / 3), eps) == CO_OK);
        const std::vector<int32_t> got = ia(m, CO_MESH_A_SOUP_TO_VERTEX);
        CHECK_MSG(got == ref, "weld round %d (eps %g) differs from the literal VertexWelder", round, static_cast<double>(eps));
        CHECK(info(m).vertex_count == static_cast<int32_t>(reps.size()));
        const std::vector<float> pos = fa(m, CO_MESH_A_VERTEX_POSITIONS);
        bool pos_ok = pos.size() == reps.size() * 3;
        for (size_t w = 0; pos_ok && w < reps.size(); ++w) {
            pos_ok = std::memcmp(&pos[w * 3], &v[static_cast<size_t>(reps[w]) * 3], 12) == 0;
        }
        CHECK(pos_ok);
        ++cases;
    }
    // the default epsilon (<= 0 / NaN) is 1e-4
    const std::vector<float> two = {0, 0, 0, 0.00009f, 0, 0};
    CHECK(co_mesh_weld(m, two.data(), 2, -1.0f) == CO_OK);
    CHECK(info(m).vertex_count == 1);
    CHECK(co_mesh_weld(m, two.data(), 2, std::numeric_limits<float>::quiet_NaN()) == CO_OK);
    CHECK(info(m).vertex_count == 1);
    co_mesh_destroy(m);
    std::printf("weld: %d random soups == literal VertexWelder\n", cases);
}

void test_reference_import() {
    std::mt19937 rng(98765);
    co_mesh* m = co_mesh_create();
    int cases = 0, walk_failures = 0;
    for (int round = 0; round < 400; ++round) {
        const int S = 3 + static_cast<int>(rng() % 40);
        std::vector<float> v;
        for (int i = 0; i < S; ++i) {
            v.push_back(static_cast<float>(rng() % 4));
            v.push_back(static_cast<float>(rng() % 4));
            v.push_back(static_cast<float>(rng() % 2));
        }
        std::vector<int32_t> idx, tf;
        const int T = 1 + static_cast<int>(rng() % 30);
        const int faces = 1 + static_cast<int>(rng() % 6);
        for (int t = 0; t < T; ++t) {
            for (int k = 0; k < 3; ++k) {
                int32_t s = static_cast<int32_t>(rng() % static_cast<uint32_t>(S));
                if (rng() % 97 == 0) s = (rng() % 2) ? S + 3 : -1;  // corrupt index
                idx.push_back(s);
            }
            tf.push_back(rng() % 41 == 0 ? -1 : static_cast<int32_t>(rng() % static_cast<uint32_t>(faces)) * 3);
        }
        if (round % 5 == 0) {  // structured fans / strips: walks that succeed
            idx.clear();
            tf.clear();
            for (int t = 1; t + 1 < S && t < 10; ++t) {
                idx.insert(idx.end(), {0, t, t + 1});
                tf.push_back(round % 2);
            }
            if (idx.empty()) idx = {0, 1, 2};
            if (tf.empty()) tf = {0};
        }
        if (round % 7 == 0 && tf.size() > 1) tf.pop_back();  // shorter face id array: triangle index as id
        std::vector<float> tc;
        const bool with_uvs = rng() % 2 == 0;
        if (with_uvs) {
            for (int i = 0; i < S * 2; ++i) tc.push_back(static_cast<float>(rng() % 1000) / 1000.0f);
        }
        const RefImport ref = ref_import(v, with_uvs ? &tc : nullptr, idx, &tf);
        const int32_t r = co_mesh_import(m, v.data(), S, with_uvs ? tc.data() : nullptr, static_cast<int64_t>(tc.size()),
                                         idx.data(), static_cast<int64_t>(idx.size()), tf.data(),
                                         static_cast<int64_t>(tf.size()), 0.0f, CO_MESH_STAGE_ALL);
        CHECK_MSG(r == CO_OK, "import round %d: %s", round, co_last_error());
        CHECK_MSG(ia(m, CO_MESH_A_SOUP_TO_VERTEX) == ref.soup_to_vertex, "import round %d: weld", round);
        CHECK_MSG(ia(m, CO_MESH_A_FACE_IDS) == ref.face_ids, "import round %d: face ids", round);
        CHECK_MSG(ia(m, CO_MESH_A_WARNINGS) == ref.warnings, "import round %d: warnings", round);
        const std::vector<int32_t> off = ia(m, CO_MESH_A_LOOP_OFFSETS);
        const std::vector<int32_t> lv = ia(m, CO_MESH_A_LOOP_VERTICES);
        const std::vector<int32_t> flags = ia(m, CO_MESH_A_FACE_FLAGS);
        const std::vector<float> luv = fa(m, CO_MESH_A_LOOP_UVS);
        bool same = off.size() == ref.loops.size() + 1;
        for (size_t f = 0; same && f < ref.loops.size(); ++f) {
            same = csr_row(off, lv, f) == ref.loops[f];
            same = same && (((flags[f] & CO_MESH_FACE_AUTHORED_UVS) != 0) == !ref.uvs[f].empty());
            if (same && !ref.uvs[f].empty()) {
                same = std::memcmp(luv.data() + off[f] * 2, ref.uvs[f].data(), ref.uvs[f].size() * 4) == 0;
            }
            if (flags[f] & CO_MESH_FACE_WALK_FAILED) ++walk_failures;
        }
        CHECK_MSG(same, "import round %d: loops / UVs differ from the literal MeshImporter", round);
        // the render mesh is always n - 2 triangles per face
        const co_mesh_info i = info(m);
        CHECK(i.triangle_count == i.corner_count - 2 * i.face_count);
        ++cases;
    }
    co_mesh_destroy(m);
    std::printf("import: %d random soups == literal MeshImporter (%d boundary-walk fallbacks among them)\n", cases,
                walk_failures);
    CHECK(walk_failures > 0);
}

// ---------------------------------------------------------------------------------------------------------------
// Java tests ported.

using P3 = std::array<float, 3>;

double tri_area_on(const std::vector<P3>& loop, const int32_t* t, const double* n) {
    const P3& a = loop[static_cast<size_t>(t[0])];
    const P3& b = loop[static_cast<size_t>(t[1])];
    const P3& c = loop[static_cast<size_t>(t[2])];
    const double ab[3] = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
    const double ac[3] = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
    const double x[3] = {ab[1] * ac[2] - ab[2] * ac[1], ab[2] * ac[0] - ab[0] * ac[2], ab[0] * ac[1] - ab[1] * ac[0]};
    return 0.5 * (x[0] * n[0] + x[1] * n[1] + x[2] * n[2]);
}

std::vector<int32_t> triangulate(const std::vector<P3>& loop, float* newell = nullptr, int32_t* flag = nullptr) {
    std::vector<int32_t> out((loop.size() - 2) * 3, -1);
    const int32_t r = co_polygon_triangulate(loop[0].data(), static_cast<int32_t>(loop.size()), out.data(), newell);
    if (flag) *flag = r;
    return out;
}

// PolygonTriangulatorTest.assertCoversPolygon
void check_covers(const std::vector<P3>& loop, const char* what) {
    float nw[3];
    const std::vector<int32_t> tris = triangulate(loop, nw);
    CHECK_MSG(tris.size() == (loop.size() - 2) * 3, "%s: triangle count", what);
    const double len = std::sqrt(double(nw[0]) * nw[0] + double(nw[1]) * nw[1] + double(nw[2]) * nw[2]);
    const double n[3] = {nw[0] / len, nw[1] / len, nw[2] / len};
    double total = 0;
    for (size_t i = 0; i < tris.size(); i += 3) {
        const double a = tri_area_on(loop, &tris[i], n);
        CHECK_MSG(a >= -1e-5, "%s: flipped triangle %zu (%g)", what, i / 3, a);
        total += a;
    }
    CHECK_MSG(std::fabs(total - len * 0.5) < 1e-4, "%s: area %g vs polygon %g", what, total, len * 0.5);
}

void test_triangulator() {
    const std::vector<P3> l = {{0, 0, 0}, {2, 0, 0}, {2, 1, 0}, {1, 1, 0}, {1, 2, 0}, {0, 2, 0}};
    CHECK((triangulate({{0, 0, 0}, {1, 0, 0}, {0, 1, 0}}) == std::vector<int32_t>{0, 1, 2}));
    check_covers({{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}}, "square");
    check_covers(l, "L shape");
    check_covers({{0, 0, 0}, {2, 1, 0}, {0, 2, 0}, {0.8f, 1, 0}}, "arrow");
    check_covers({{0, 0, 0}, {0.5f, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}}, "collinear midpoint");
    check_covers({{0, 1, 0}, {1, 1, 0}, {1, 0, 0}, {0, 0, 0}}, "clockwise square");
    check_covers({{0, 0, 0}, {0, 2, 0}, {0, 2, 1}, {0, 1, 1}, {0, 1, 2}, {0, 0, 2}}, "vertical L");
    CHECK(triangulate({{0, 0, 0}, {1, 0, 0}, {1, 1, 0.3f}, {0, 1, 0}}).size() == 6);
    int32_t flag = -1;
    CHECK(triangulate({{0, 0, 0}, {1, 1e-8f, 0}, {2, 0, 0}, {1, 1e-8f, 0.0000001f}}, nullptr, &flag).size() == 6);
    CHECK(flag >= 0);
    // the hand trace of the L (fixtures/concave_l_face.json)
    CHECK((triangulate(l) == std::vector<int32_t>{0, 1, 2, 0, 2, 3, 5, 0, 3, 3, 4, 5}));
    // tiny / translated L triangulate like the full one
    std::vector<P3> tiny, moved;
    for (const P3& p : l) {
        tiny.push_back({p[0] * 1e-4f + 0.03f, p[1] * 1e-4f + 1.68f, p[2] * 1e-4f + -0.09f});
        moved.push_back({p[0] + 8192, p[1] - 8192, p[2] + 8192});
    }
    CHECK(triangulate(tiny) == triangulate(l));
    CHECK(triangulate(moved) == triangulate(l));
    // Newell of the CCW L points +Z
    float nw[3];
    triangulate(l, nw);
    CHECK(nw[0] == 0 && nw[1] == 0 && nw[2] > 0);
    // fallbacks: all points equal -> degenerate normal fan
    CHECK((triangulate({{1, 1, 1}, {1, 1, 1}, {1, 1, 1}, {1, 1, 1}}, nullptr, &flag) ==
           std::vector<int32_t>{0, 1, 2, 0, 2, 3}));
    CHECK(flag == CO_MESH_FACE_TRI_DEGENERATE_NORMAL);
    // errors
    int32_t out[3];
    CHECK(co_polygon_triangulate(nullptr, 3, out, nullptr) == CO_ERR_ARG);
    CHECK(std::strlen(co_last_error()) > 0);
    const float two[6] = {0, 0, 0, 1, 0, 0};
    CHECK(co_polygon_triangulate(two, 2, out, nullptr) == CO_ERR_ARG);
}

co_mesh* import_cube(int32_t stages = CO_MESH_STAGE_ALL) {
    const float h = 0.5f;
    const std::vector<float> v = {-h, -h, h, h, -h, h, h, h, h, -h, h, h,  h, -h, -h, -h, -h, -h, -h, h, -h, h, h, -h,
                                  -h, -h, -h, -h, -h, h, -h, h, h, -h, h, -h, h, -h, h, h, -h, -h, h, h, -h, h, h, h,
                                  -h, h, h, h, h, h, h, h, -h, -h, h, -h, -h, -h, -h, h, -h, -h, h, -h, h, -h, -h, h};
    std::vector<int32_t> idx, tf;
    for (int f = 0; f < 6; ++f) {
        const int b = f * 4;
        idx.insert(idx.end(), {b, b + 1, b + 2, b + 2, b + 3, b});
        tf.insert(tf.end(), {f, f});
    }
    co_mesh* m = co_mesh_create();
    CHECK(co_mesh_import(m, v.data(), 24, nullptr, 0, idx.data(), 36, tf.data(), 12, 0.0f, stages) == CO_OK);
    return m;
}

void test_java_cases() {
    // MeshImporterTest.cubeSoupBecomesEightVerticesSixQuads + soupIndexMappingCoversWeldedVertices
    co_mesh* cube = import_cube();
    co_mesh_info i = info(cube);
    CHECK(i.vertex_count == 8 && i.face_count == 6 && i.corner_count == 24 && i.triangle_count == 12);
    CHECK(i.edge_count == 12 && i.uniform == 1 && i.first_face_vertex_count == 4);
    const std::vector<int32_t> vso = ia(cube, CO_MESH_A_VERTEX_SOUP_OFFSETS);
    for (size_t v = 0; v < 8; ++v) CHECK(vso[v + 1] - vso[v] == 3);
    for (int32_t k : ia(cube, CO_MESH_A_EDGE_KIND)) CHECK(k == CO_MESH_EDGE_MANIFOLD);
    // RenderMeshBuilderTest.sharedVertexFansOutToMultipleCorners
    const std::vector<int32_t> vco = ia(cube, CO_MESH_A_VERTEX_CORNER_OFFSETS);
    for (size_t v = 0; v < 8; ++v) CHECK(vco[v + 1] - vco[v] == 3);
    // RenderMeshBuilderTest.uvsLandInsideTheFaceRegion
    co_face_mapping mp{0, 0, 0.25f, 0.25f, 0.75f, 0.75f, 0, 1};
    CHECK(co_mesh_set_mappings(cube, &mp, nullptr, 1) == CO_OK);
    CHECK(co_mesh_build(cube, CO_MESH_STAGE_RENDER) == CO_OK);
    const std::vector<float> uv = fa(cube, CO_MESH_A_CORNER_UVS);
    for (size_t c = 0; c < 4; ++c) {
        CHECK(uv[c * 2] >= 0.25f - 1e-5f && uv[c * 2] <= 0.75f + 1e-5f);
        CHECK(uv[c * 2 + 1] >= 0.25f - 1e-5f && uv[c * 2 + 1] <= 0.75f + 1e-5f);
    }
    for (size_t c = 4; c < 24; ++c) CHECK(uv[c * 2] >= 0 && uv[c * 2] <= 1 && uv[c * 2 + 1] >= 0 && uv[c * 2 + 1] <= 1);
    // a rotation alone does not make a mapping explicit; an invalid rotation is refused
    co_face_mapping bad = mp;
    bad.uv_rotation_degrees = 45;
    CHECK(co_mesh_set_mappings(cube, &bad, nullptr, 1) == CO_ERR_ARG);
    CHECK(std::strstr(co_last_error(), "45") != nullptr);
    co_mesh_destroy(cube);

    // MeshImporterTest.degenerateTrianglesAreDropped / emptyInputYieldsEmptyMesh
    {
        const std::vector<float> v = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 0, 0, 0.00001f, 0, 0, 1, 0, 0};
        const std::vector<int32_t> idx = {0, 1, 2, 3, 4, 5}, tf = {0, 1};
        co_mesh* m = co_mesh_create();
        CHECK(co_mesh_import(m, v.data(), 6, nullptr, 0, idx.data(), 6, tf.data(), 2, 0, CO_MESH_STAGE_ALL) == CO_OK);
        CHECK(info(m).face_count == 1 && ia(m, CO_MESH_A_FACE_IDS) == std::vector<int32_t>{0});
        CHECK(co_mesh_import(m, nullptr, 0, nullptr, 0, nullptr, 0, nullptr, 0, 0, CO_MESH_STAGE_ALL) == CO_OK);
        i = info(m);
        CHECK(i.vertex_count == 0 && i.face_count == 0 && i.soup_vertex_count == 0);
        CHECK((i.stages & CO_MESH_STAGE_TOPOLOGY) != 0 && i.edge_count == 0);
        // corrupt indices: one warning first, value = dropped triangles
        const std::vector<int32_t> bad_idx = {0, 1, 2, 0, 9, 1, -1, 0, 1};
        CHECK(co_mesh_import(m, v.data(), 6, nullptr, 0, bad_idx.data(), 9, nullptr, 0, 0, CO_MESH_STAGE_ALL) == CO_OK);
        CHECK((ia(m, CO_MESH_A_WARNINGS) == std::vector<int32_t>{CO_MESH_WARN_CORRUPT_INDICES, -1, 2}));
        const uint64_t rev = info(m).revision;
        const std::vector<int32_t> huge = {0, 2147483647};
        CHECK(co_mesh_import(m, v.data(), 6, nullptr, 0, idx.data(), 6, huge.data(), 2, 0, CO_MESH_STAGE_ALL) ==
              CO_ERR_RANGE);
        CHECK(std::strstr(co_last_error(), "2147483647") != nullptr);  // read before info() clears it
        CHECK(info(m).revision == rev);
        co_mesh_destroy(m);
    }

    // RenderMeshBuilderTest.tinyTexturedQuadKeepsItsFullUvRegion
    {
        const std::vector<float> p = {0, 1.68f, 0, 0.0001f, 1.68f, 0, 0.0001f, 1.6801f, 0, 0, 1.6801f, 0};
        const std::vector<int32_t> ids = {0}, off = {0, 4}, loop = {0, 1, 2, 3};
        co_mesh* m = co_mesh_create();
        CHECK(co_mesh_set_faces(m, p.data(), 4, ids.data(), 1, off.data(), loop.data(), nullptr, nullptr, 0,
                                CO_MESH_STAGE_ALL) == CO_OK);
        float lo = 1, hi = 0;
        for (float u : fa(m, CO_MESH_A_CORNER_UVS)) {
            lo = std::min(lo, u);
            hi = std::max(hi, u);
        }
        CHECK(std::fabs(lo) <= 1e-6f && std::fabs(hi - 1) <= 1e-6f);
        co_mesh_destroy(m);
    }

    // degenerate projection frame -> region centre; -0.0 region is explicit (record equality)
    {
        const std::vector<float> p = {0, 0, 0, 1, 0, 0, 2, 0, 0, 0, 1, 0, 1, 1, 0, 1, 2, 0};
        const std::vector<int32_t> ids = {0, 1}, off = {0, 3, 6}, loop = {0, 1, 2, 3, 4, 5}, has = {0, 1};
        const std::vector<float> auv = {0, 0, 0, 0, 0, 0, 0.1f, 0.1f, 0.2f, 0.1f, 0.2f, 0.2f};
        co_face_mapping maps[2] = {{0, 0, 0.2f, 0.4f, 0.6f, 0.8f, 0, 1}, {1, 0, -0.0f, 0.0f, 1.0f, 1.0f, 0, 1}};
        co_mesh* m = co_mesh_create();
        CHECK(co_mesh_set_mappings(m, maps, nullptr, 2) == CO_OK);
        CHECK(co_mesh_set_faces(m, p.data(), 6, ids.data(), 2, off.data(), loop.data(), has.data(), auv.data(), 0,
                                CO_MESH_STAGE_RENDER) == CO_OK);
        const std::vector<float> duv = fa(m, CO_MESH_A_CORNER_UVS);
        const std::vector<int32_t> fl = ia(m, CO_MESH_A_FACE_FLAGS);
        CHECK(std::fabs(duv[0] - 0.4f) < 1e-6f && std::fabs(duv[1] - 0.6f) < 1e-6f);
        CHECK((fl[0] & CO_MESH_FACE_UV_DEGENERATE) != 0);
        CHECK((fl[1] & CO_MESH_FACE_UV_PROJECTED) != 0);  // -0.0 region is explicit: projected over the authored UVs
        CHECK(duv[6] != 0.1f || duv[7] != 0.1f);
        const int32_t flags[2] = {0, CO_MESH_MAPPING_AUTHORED_UVS};  // FaceTextureMapping.authoredUVs on face 1
        CHECK(co_mesh_set_mappings(m, maps, flags, 2) == CO_OK);
        CHECK(co_mesh_build(m, CO_MESH_STAGE_RENDER) == CO_OK);
        CHECK(fa(m, CO_MESH_A_CORNER_UVS)[6] == 0.1f);
        // a non-explicit mapping (material 0, FULL region, any rotation) never hides authored UVs
        co_face_mapping plain[2] = {maps[0], {1, 0, 0.0f, 0.0f, 1.0f, 1.0f, 90, 1}};
        CHECK(co_mesh_set_mappings(m, plain, nullptr, 2) == CO_OK);
        CHECK(co_mesh_build(m, CO_MESH_STAGE_RENDER) == CO_OK);
        CHECK(fa(m, CO_MESH_A_CORNER_UVS)[6] == 0.1f && (ia(m, CO_MESH_A_FACE_FLAGS)[1] & CO_MESH_FACE_UV_PROJECTED) == 0);
        co_mesh_destroy(m);
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Topology: HashSet orders beyond one bucket table, boundary / manifold, thresholds, lookups.

co_mesh* fan_on_edge(const std::vector<int32_t>& ids) {
    std::vector<float> p = {0, 0, 0, 1, 0, 0};
    std::vector<int32_t> off{0}, loop;
    for (size_t k = 0; k < ids.size(); ++k) {
        const double a = 6.283185307179586 * static_cast<double>(k) / static_cast<double>(ids.size());
        p.insert(p.end(), {0.5f, static_cast<float>(std::cos(a)), static_cast<float>(std::sin(a))});
        loop.insert(loop.end(), {0, 1, static_cast<int32_t>(2 + k)});
        off.push_back(static_cast<int32_t>(loop.size()));
    }
    co_mesh* m = co_mesh_create();
    CHECK(co_mesh_set_faces(m, p.data(), static_cast<int32_t>(p.size() / 3), ids.data(), static_cast<int32_t>(ids.size()),
                            off.data(), loop.data(), nullptr, nullptr, 0, CO_MESH_STAGE_TOPOLOGY) == CO_OK);
    return m;
}

void test_topology() {
    {  // 13 entries: the set resized to 32 buckets (16 and 32 no longer share bucket 0)
        co_mesh* m = fan_on_edge({16, 0, 32, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        const std::vector<int32_t> off = ia(m, CO_MESH_A_EDGE_FACE_OFFSETS);
        CHECK((csr_row(off, ia(m, CO_MESH_A_EDGE_FACES), 0) ==
               std::vector<int32_t>{0, 32, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 16}));
        CHECK(ia(m, CO_MESH_A_EDGE_KIND)[0] == CO_MESH_EDGE_NON_MANIFOLD);
        CHECK(info(m).approximate_order_sets == 0);
        co_mesh_destroy(m);
    }
    {  // 9 ids in one bucket: HashMap.treeifyBin below 64 buckets resizes instead
        co_mesh* m = fan_on_edge({0, 16, 32, 48, 64, 80, 96, 112, 128});
        const std::vector<int32_t> off = ia(m, CO_MESH_A_EDGE_FACE_OFFSETS);
        CHECK((csr_row(off, ia(m, CO_MESH_A_EDGE_FACES), 0) ==
               std::vector<int32_t>{0, 32, 64, 96, 128, 16, 48, 80, 112}));
        const std::vector<int32_t> aoff = ia(m, CO_MESH_A_FACE_ADJ_OFFSETS);
        CHECK((csr_row(aoff, ia(m, CO_MESH_A_FACE_ADJ), 0) ==
               std::vector<int32_t>{32, 64, 96, 128, 16, 48, 80, 112}));
        co_mesh_destroy(m);
    }
    {  // open 3x3 grid of quads: border edges OPEN, inner MANIFOLD; thresholds; edge lookup
        std::vector<float> p;
        for (int y = 0; y <= 3; ++y) {
            for (int x = 0; x <= 3; ++x) p.insert(p.end(), {static_cast<float>(x), static_cast<float>(y), 0.0f});
        }
        std::vector<int32_t> ids, off{0}, loop;
        for (int y = 0; y < 3; ++y) {
            for (int x = 0; x < 3; ++x) {
                const int32_t a = y * 4 + x;
                loop.insert(loop.end(), {a, a + 1, a + 5, a + 4});
                off.push_back(static_cast<int32_t>(loop.size()));
                ids.push_back(static_cast<int32_t>(ids.size()));
            }
        }
        co_mesh* m = co_mesh_create();
        CHECK(co_mesh_set_faces(m, p.data(), 16, ids.data(), 9, off.data(), loop.data(), nullptr, nullptr, 0,
                                CO_MESH_STAGE_ALL) == CO_OK);
        const std::vector<int32_t> kinds = ia(m, CO_MESH_A_EDGE_KIND);
        const std::vector<int32_t> ev = ia(m, CO_MESH_A_EDGE_VERTICES);
        int open = 0, manifold = 0;
        for (int32_t k : kinds) (k == CO_MESH_EDGE_OPEN ? open : manifold)++;
        CHECK(open == 12 && manifold == 12 && kinds.size() == 24);
        for (size_t e = 0; e < kinds.size(); ++e) CHECK(co_mesh_find_edge(m, ev[e * 2 + 1], ev[e * 2]) == static_cast<int32_t>(e));
        CHECK(co_mesh_find_edge(m, 0, 5) == -1);
        const std::vector<float> dih = fa(m, CO_MESH_A_EDGE_DIHEDRAL);
        for (size_t e = 0; e < kinds.size(); ++e) {
            CHECK((kinds[e] == CO_MESH_EDGE_MANIFOLD) == !std::isnan(dih[e]));
        }
        co_mesh_destroy(m);
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Stages, validation, errors, clone.

void test_api() {
    co_mesh* m = co_mesh_create();
    const void* p = nullptr;
    CHECK(co_mesh_array(m, CO_MESH_A_TRI_INDICES, &p) == -1 && p == nullptr);
    CHECK(std::strstr(co_last_error(), "TRIANGULATE") != nullptr);
    CHECK(co_mesh_array(m, 999, &p) == -1);
    CHECK(co_mesh_build(m, CO_MESH_STAGE_ALL) == CO_ERR_ARG);
    CHECK(co_mesh_move_vertices(m, nullptr, nullptr, 0, nullptr) == CO_ERR_ARG);
    const std::vector<float> pos = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};
    const std::vector<int32_t> ids = {4}, off = {0, 4}, good = {0, 1, 2, 3}, repeat = {0, 1, 1, 3}, oob = {0, 1, 2, 9};
    CHECK(co_mesh_set_faces(m, pos.data(), 4, ids.data(), 1, off.data(), good.data(), nullptr, nullptr, 0,
                            CO_MESH_STAGE_ALL) == CO_OK);
    const uint64_t rev = info(m).revision;
    CHECK(co_mesh_set_faces(m, pos.data(), 4, ids.data(), 1, off.data(), repeat.data(), nullptr, nullptr, 0,
                            CO_MESH_STAGE_ALL) == CO_ERR_ARG);
    CHECK(std::strstr(co_last_error(), "repeats") != nullptr);
    CHECK(co_mesh_set_faces(m, pos.data(), 4, ids.data(), 1, off.data(), oob.data(), nullptr, nullptr, 0,
                            CO_MESH_STAGE_ALL) == CO_ERR_RANGE);
    const std::vector<int32_t> neg = {-1}, big = {1 << 26}, two_ids = {2, 2}, two_off = {0, 4, 8},
                               two_loops = {0, 1, 2, 3, 0, 1, 2, 3}, short_off = {0, 2};
    CHECK(co_mesh_set_faces(m, pos.data(), 4, neg.data(), 1, off.data(), good.data(), nullptr, nullptr, 0, 0) == CO_ERR_ARG);
    CHECK(co_mesh_set_faces(m, pos.data(), 4, big.data(), 1, off.data(), good.data(), nullptr, nullptr, 0, 0) ==
          CO_ERR_RANGE);
    CHECK(co_mesh_set_faces(m, pos.data(), 4, two_ids.data(), 2, two_off.data(), two_loops.data(), nullptr, nullptr, 0,
                            0) == CO_ERR_ARG);
    CHECK(co_mesh_set_faces(m, pos.data(), 4, ids.data(), 1, short_off.data(), good.data(), nullptr, nullptr, 0, 0) ==
          CO_ERR_ARG);
    const int32_t has = 1;
    CHECK(co_mesh_set_faces(m, pos.data(), 4, ids.data(), 1, off.data(), good.data(), &has, nullptr, 0, 0) == CO_ERR_ARG);
    CHECK(info(m).revision == rev);  // failed calls change nothing
    co_mesh_info i = info(m);
    CHECK(i.face_count == 1 && i.face_id_upper_bound == 5 && i.stages == (CO_MESH_STAGE_ALL & ~CO_MESH_STAGE_WELD));
    CHECK((ia(m, CO_MESH_A_FACE_SLOTS) == std::vector<int32_t>{-1, -1, -1, -1, 0}));
    // a larger caller-given upper bound is kept (EditableMesh.faceIdUpperBound after deletes)
    CHECK(co_mesh_set_faces(m, pos.data(), 4, ids.data(), 1, off.data(), good.data(), nullptr, nullptr, 12,
                            CO_MESH_STAGE_TOPOLOGY) == CO_OK);
    CHECK(info(m).face_id_upper_bound == 12 && fa(m, CO_MESH_A_FACE_NORMALS).size() == 36);
    CHECK(ia(m, CO_MESH_A_FACE_ADJ_OFFSETS).size() == 13);
    CHECK(co_mesh_array(m, CO_MESH_A_CORNER_UVS, nullptr) == -1);  // RENDER was not asked for
    // move: out of range refused, nothing changes
    const int32_t vid = 9;
    const float to[3] = {5, 5, 5};
    CHECK(co_mesh_move_vertices(m, &vid, to, 1, nullptr) == CO_ERR_RANGE);
    CHECK(fa(m, CO_MESH_A_VERTEX_POSITIONS)[0] == 0);
    // clone is independent
    co_mesh* c = co_mesh_clone(m);
    const int32_t v0 = 0;
    CHECK(co_mesh_move_vertices(c, &v0, to, 1, nullptr) == CO_OK);
    CHECK(fa(c, CO_MESH_A_VERTEX_POSITIONS)[0] == 5 && fa(m, CO_MESH_A_VERTEX_POSITIONS)[0] == 0);
    co_mesh_destroy(c);
    CHECK(co_mesh_clone(nullptr) == nullptr);
    co_mesh_info dummy{};
    CHECK(co_mesh_info_get(nullptr, &dummy) == CO_ERR_ARG);
    co_mesh_destroy(m);
    co_mesh_destroy(nullptr);
}

// ---------------------------------------------------------------------------------------------------------------
// Incremental refresh == full rebuild.

struct Editable {
    std::vector<float> pos;
    std::vector<int32_t> ids, off{0}, loops, has;
    std::vector<float> uvs;
    std::vector<co_face_mapping> maps;
    std::vector<int32_t> map_flags;
};

Editable random_editable(std::mt19937& rng) {
    Editable e;
    const int N = 7;
    for (int y = 0; y <= N; ++y) {
        for (int x = 0; x <= N; ++x) {
            e.pos.insert(e.pos.end(), {static_cast<float>(x) + 0.1f * static_cast<float>(rng() % 5),
                                       static_cast<float>(y), 0.05f * static_cast<float>(rng() % 7)});
        }
    }
    auto add = [&](std::vector<int32_t> loop, int32_t id, bool uv) {
        e.ids.push_back(id);
        e.loops.insert(e.loops.end(), loop.begin(), loop.end());
        e.off.push_back(static_cast<int32_t>(e.loops.size()));
        e.has.push_back(uv ? 1 : 0);
        for (size_t k = 0; k < loop.size(); ++k) {
            e.uvs.push_back(uv ? static_cast<float>(rng() % 100) / 100.0f : 0.0f);
            e.uvs.push_back(uv ? static_cast<float>(rng() % 100) / 100.0f : 0.0f);
        }
    };
    int32_t id = 0;
    for (int y = 0; y < N; ++y) {
        for (int x = 0; x < N; ++x) {
            const int32_t a = y * (N + 1) + x;
            if (x == 2 && y == 2) {  // a concave hexagon over 2x2 cells minus one corner + its missing quad as 2 tris
                continue;
            }
            if ((x == 3 && y == 2) || (x == 2 && y == 3) || (x == 3 && y == 3)) continue;
            add({a, a + 1, a + N + 2, a + N + 1}, id, rng() % 4 == 0);
            id += (rng() % 5 == 0) ? 2 : 1;  // face id gaps
        }
    }
    // a concave octagon (an L over cells (2,2) (3,2) (2,3), with the mid-edge grid points on its boundary) and a
    // quad for cell (3,3)
    auto vid = [&](int x, int y) { return static_cast<int32_t>(y * (N + 1) + x); };
    add({vid(2, 2), vid(3, 2), vid(4, 2), vid(4, 3), vid(3, 3), vid(3, 4), vid(2, 4), vid(2, 3)}, id++, false);
    add({vid(3, 3), vid(4, 3), vid(4, 4), vid(3, 4)}, id++, true);
    for (int k = 0; k < 6; ++k) {
        co_face_mapping mp{};
        mp.face_id = static_cast<int32_t>(rng() % static_cast<uint32_t>(id));
        mp.material_id = static_cast<int32_t>(rng() % 3);
        mp.u0 = static_cast<float>(rng() % 4) / 8.0f;
        mp.v0 = static_cast<float>(rng() % 4) / 8.0f;
        mp.u1 = mp.u0 + 0.5f;
        mp.v1 = mp.v0 + 0.5f;
        mp.uv_rotation_degrees = 90 * static_cast<int32_t>(rng() % 4);
        e.maps.push_back(mp);
        e.map_flags.push_back(rng() % 2 ? CO_MESH_MAPPING_AUTHORED_UVS : 0);
    }
    return e;
}

bool same_bits(const co_mesh* a, const co_mesh* b, int32_t which) {
    const void* pa = nullptr;
    const void* pb = nullptr;
    const int64_t na = co_mesh_array(a, which, &pa);
    const int64_t nb = co_mesh_array(b, which, &pb);
    return na == nb && (na <= 0 || std::memcmp(pa, pb, static_cast<size_t>(na) * 4) == 0);
}

void test_incremental() {
    std::mt19937 rng(4242);
    int frames = 0, index_changes = 0;
    for (int round = 0; round < 12; ++round) {
        Editable e = random_editable(rng);
        co_mesh* m = co_mesh_create();
        CHECK(co_mesh_set_mappings(m, e.maps.data(), e.map_flags.data(), static_cast<int32_t>(e.maps.size())) == CO_OK);
        const auto V = static_cast<int32_t>(e.pos.size() / 3);
        const int32_t r = co_mesh_set_faces(m, e.pos.data(), V, e.ids.data(), static_cast<int32_t>(e.ids.size()),
                                            e.off.data(), e.loops.data(), e.has.data(), e.uvs.data(), 0,
                                            CO_MESH_STAGE_ALL);
        CHECK_MSG(r == CO_OK, "incremental setup: %s", co_last_error());
        for (int frame = 0; frame < 25; ++frame) {
            const int k = 1 + static_cast<int>(rng() % 6);
            std::vector<int32_t> vids;
            std::vector<float> xyz;
            for (int j = 0; j < k; ++j) {
                const auto v = static_cast<int32_t>(rng() % static_cast<uint32_t>(V));
                vids.push_back(v);
                const float big = (rng() % 4 == 0) ? 2.5f : 0.3f;  // big moves fold faces (concave / flipped)
                for (int a = 0; a < 3; ++a) {
                    const float d = (static_cast<float>(rng() % 1000) / 1000.0f - 0.5f) * big;
                    xyz.push_back(e.pos[static_cast<size_t>(v) * 3 + static_cast<size_t>(a)] + d);
                }
            }
            if (frame == 3) {  // a move that collapses a face onto a line
                vids = {e.loops[0], e.loops[1], e.loops[2]};
                xyz = {0, 0, 0, 1, 0, 0, 2, 0, 0};
            }
            for (size_t j = 0; j < vids.size(); ++j) {  // the last write wins, like the native call
                std::memcpy(&e.pos[static_cast<size_t>(vids[j]) * 3], &xyz[j * 3], 12);
            }
            int32_t changes = 0;
            CHECK(co_mesh_move_vertices(m, vids.data(), xyz.data(), static_cast<int64_t>(vids.size()), &changes) == CO_OK);
            CHECK((changes & CO_MESH_CHANGED_POSITIONS) != 0);
            if (changes & CO_MESH_CHANGED_INDICES) ++index_changes;
            co_mesh* full = co_mesh_create();
            CHECK(co_mesh_set_mappings(full, e.maps.data(), e.map_flags.data(), static_cast<int32_t>(e.maps.size())) ==
                  CO_OK);
            CHECK(co_mesh_set_faces(full, e.pos.data(), V, e.ids.data(), static_cast<int32_t>(e.ids.size()),
                                    e.off.data(), e.loops.data(), e.has.data(), e.uvs.data(), 0,
                                    CO_MESH_STAGE_ALL) == CO_OK);
            const int32_t arrays[] = {CO_MESH_A_VERTEX_POSITIONS, CO_MESH_A_TRI_INDICES,   CO_MESH_A_FACE_FLAGS,
                                      CO_MESH_A_CORNER_POSITIONS, CO_MESH_A_CORNER_UVS,    CO_MESH_A_FACE_NORMALS,
                                      CO_MESH_A_FACE_CENTROIDS,   CO_MESH_A_FACE_AREAS,    CO_MESH_A_EDGE_DIHEDRAL,
                                      CO_MESH_A_EDGE_KIND,        CO_MESH_A_TRI_FACE_IDS,  CO_MESH_A_FACE_ADJ};
            for (int32_t a : arrays) CHECK_MSG(same_bits(m, full, a), "round %d frame %d: array %d differs from a rebuild", round, frame, a);
            // the touched slots are exactly the faces using a moved vertex
            const std::vector<int32_t> moved = ia(m, CO_MESH_A_MOVED_FACES);
            std::vector<int32_t> expect;
            for (size_t f = 0; f + 1 < e.off.size(); ++f) {
                for (int32_t c = e.off[f]; c < e.off[f + 1]; ++c) {
                    if (std::find(vids.begin(), vids.end(), e.loops[static_cast<size_t>(c)]) != vids.end()) {
                        expect.push_back(static_cast<int32_t>(f));
                        break;
                    }
                }
            }
            CHECK(moved == expect);
            co_mesh_destroy(full);
            ++frames;
        }
        co_mesh_destroy(m);
    }
    // stages subsets: TOPOLOGY only and FACES only both refresh what they have
    {
        co_mesh* t = import_cube(CO_MESH_STAGE_TOPOLOGY);
        const int32_t v = 0;
        const float to[3] = {-0.7f, -0.6f, 0.9f};
        CHECK(co_mesh_move_vertices(t, &v, to, 1, nullptr) == CO_OK);
        co_mesh* full = import_cube(CO_MESH_STAGE_TOPOLOGY);
        const std::vector<float> pos = fa(t, CO_MESH_A_VERTEX_POSITIONS);
        const std::vector<int32_t> ids = ia(t, CO_MESH_A_FACE_IDS), off = ia(t, CO_MESH_A_LOOP_OFFSETS),
                                   lv = ia(t, CO_MESH_A_LOOP_VERTICES);
        CHECK(co_mesh_set_faces(full, pos.data(), 8, ids.data(), 6, off.data(), lv.data(), nullptr, nullptr, 0,
                                CO_MESH_STAGE_TOPOLOGY) == CO_OK);
        CHECK(same_bits(t, full, CO_MESH_A_FACE_NORMALS) && same_bits(t, full, CO_MESH_A_FACE_AREAS));
        CHECK(same_bits(t, full, CO_MESH_A_EDGE_DIHEDRAL));
        CHECK(ia(t, CO_MESH_A_MOVED_FACES) == (std::vector<int32_t>{0, 2, 5}));
        co_mesh_destroy(full);
        co_mesh_destroy(t);
    }
    std::printf("incremental: %d drag frames == full rebuild (%d re-triangulated)\n", frames, index_changes);
    CHECK(index_changes > 0);
}

// ---------------------------------------------------------------------------------------------------------------
// 54k-triangle benchmark: a torus of 150 x 180 quads as the editor's soup (4 corners per quad, 2 triangles).

double ms_since(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
}

void benchmark() {
    const int R = 150, C = 180;
    std::vector<float> grid(static_cast<size_t>(R * C) * 3);
    for (int i = 0; i < R; ++i) {
        for (int j = 0; j < C; ++j) {
            const double u = 6.283185307179586 * i / R, w = 6.283185307179586 * j / C;
            const double rr = 1.0 + 0.35 * std::cos(w);
            float* p = &grid[static_cast<size_t>(i * C + j) * 3];
            p[0] = static_cast<float>(rr * std::cos(u));
            p[1] = static_cast<float>(0.35 * std::sin(w));
            p[2] = static_cast<float>(rr * std::sin(u));
        }
    }
    std::vector<float> soup, tc;
    std::vector<int32_t> idx, tf;
    for (int i = 0; i < R; ++i) {
        for (int j = 0; j < C; ++j) {
            const int q[4] = {i * C + j, ((i + 1) % R) * C + j, ((i + 1) % R) * C + (j + 1) % C, i * C + (j + 1) % C};
            const auto b = static_cast<int32_t>(soup.size() / 3);
            for (int k = 0; k < 4; ++k) {
                soup.insert(soup.end(), &grid[static_cast<size_t>(q[k]) * 3], &grid[static_cast<size_t>(q[k]) * 3] + 3);
                tc.insert(tc.end(), {static_cast<float>(k & 1), static_cast<float>(k >> 1)});
            }
            idx.insert(idx.end(), {b, b + 1, b + 2, b + 2, b + 3, b});
            const auto f = static_cast<int32_t>(tf.size() / 2);
            tf.insert(tf.end(), {f, f});
        }
    }
    const auto S = static_cast<int64_t>(soup.size() / 3);
    co_mesh* m = co_mesh_create();
    double best[7] = {1e9, 1e9, 1e9, 1e9, 1e9, 1e9, 1e9};
    for (int rep = 0; rep < 5; ++rep) {
        auto t0 = std::chrono::steady_clock::now();
        CHECK(co_mesh_weld(m, soup.data(), S, 0) == CO_OK);
        best[0] = std::min(best[0], ms_since(t0));
        t0 = std::chrono::steady_clock::now();
        CHECK(co_mesh_import(m, soup.data(), S, tc.data(), static_cast<int64_t>(tc.size()), idx.data(),
                             static_cast<int64_t>(idx.size()), tf.data(), static_cast<int64_t>(tf.size()), 0, 0) == CO_OK);
        best[1] = std::min(best[1], ms_since(t0));
        t0 = std::chrono::steady_clock::now();
        CHECK(co_mesh_build(m, CO_MESH_STAGE_TRIANGULATE) == CO_OK);
        best[2] = std::min(best[2], ms_since(t0));
        t0 = std::chrono::steady_clock::now();
        CHECK(co_mesh_build(m, CO_MESH_STAGE_RENDER) == CO_OK);  // includes the triangulation
        best[3] = std::min(best[3], ms_since(t0));
        t0 = std::chrono::steady_clock::now();
        CHECK(co_mesh_build(m, CO_MESH_STAGE_TOPOLOGY) == CO_OK);
        best[4] = std::min(best[4], ms_since(t0));
        t0 = std::chrono::steady_clock::now();
        CHECK(co_mesh_import(m, soup.data(), S, tc.data(), static_cast<int64_t>(tc.size()), idx.data(),
                             static_cast<int64_t>(idx.size()), tf.data(), static_cast<int64_t>(tf.size()), 0,
                             CO_MESH_STAGE_ALL) == CO_OK);
        best[5] = std::min(best[5], ms_since(t0));
        // drag: 50 vertices, 20 frames
        std::vector<int32_t> vids;
        std::vector<float> xyz;
        const std::vector<float> pos = fa(m, CO_MESH_A_VERTEX_POSITIONS);
        for (int k = 0; k < 50; ++k) vids.push_back(k * 97 % static_cast<int32_t>(pos.size() / 3));
        t0 = std::chrono::steady_clock::now();
        for (int frame = 0; frame < 20; ++frame) {
            xyz.clear();
            for (int32_t v : vids) {
                xyz.insert(xyz.end(), {pos[static_cast<size_t>(v) * 3] + 0.001f * static_cast<float>(frame),
                                       pos[static_cast<size_t>(v) * 3 + 1], pos[static_cast<size_t>(v) * 3 + 2]});
            }
            CHECK(co_mesh_move_vertices(m, vids.data(), xyz.data(), 50, nullptr) == CO_OK);
        }
        best[6] = std::min(best[6], ms_since(t0) / 20.0);
    }
    const co_mesh_info i = info(m);
    CHECK(i.triangle_count == 54000 && i.vertex_count == R * C && i.face_count == 27000 && i.edge_count == 54000);
    for (int32_t k : ia(m, CO_MESH_A_EDGE_KIND)) {
        if (k != CO_MESH_EDGE_MANIFOLD) {
            CHECK(k == CO_MESH_EDGE_MANIFOLD);
            break;
        }
    }
    std::printf("benchmark (54000 triangles, %lld soup vertices -> %d vertices, %d edges), best of 5:\n",
                static_cast<long long>(S), i.vertex_count, i.edge_count);
    std::printf("  weld %.2f ms | import (weld + loops) %.2f ms | triangulate %.2f ms | render (+tri) %.2f ms | "
                "topology %.2f ms\n",
                best[0], best[1], best[2], best[3], best[4]);
    std::printf("  ALL in one import call %.2f ms | drag of 50 vertices %.3f ms per frame\n", best[5], best[6]);
    co_mesh_destroy(m);

    // the all-triangle variant (every soup triangle its own face, as an OBJ import without face ids)
    co_mesh* t = co_mesh_create();
    double tri_best = 1e9;
    for (int rep = 0; rep < 3; ++rep) {
        const auto t0 = std::chrono::steady_clock::now();
        CHECK(co_mesh_import(t, soup.data(), S, nullptr, 0, idx.data(), static_cast<int64_t>(idx.size()), nullptr, 0, 0,
                             CO_MESH_STAGE_ALL) == CO_OK);
        tri_best = std::min(tri_best, ms_since(t0));
    }
    std::printf("  54000 one-triangle faces (162000 corners, 81000 edges), ALL: %.2f ms\n", tri_best);
    CHECK(info(t).face_count == 54000);
    co_mesh_destroy(t);
}

}  // namespace

int main() {
    const int fixtures = run_fixtures();
    std::printf("fixtures: %d hand-traced Java goldens\n", fixtures);
    CHECK(fixtures >= 9);
    test_reference_weld();
    test_reference_import();
    test_triangulator();
    test_java_cases();
    test_topology();
    test_api();
    test_incremental();
    benchmark();
    if (failures != 0) {
        std::fprintf(stderr, "mesh_pipeline: %d check(s) failed\n", failures);
        return 1;
    }
    std::printf("mesh_pipeline: all checks passed\n");
    return 0;
}
