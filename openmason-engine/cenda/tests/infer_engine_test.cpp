// Inference engine (ci_*): ABI, model-file parsing, and -- on a CUDA machine -- the plan executor itself, with a tiny
// model built here (its own kernel source): batch scaling, expert `when` / `drop` rules, eager vs graph, buffer fills,
// concurrent contexts, stream ordering, host requests. Without CUDA the GPU part reports SKIP (exit 77).
// The real networks' parity (DaedalusTGM's detail / relief / SR / climate / planner) is the Python harness's job:
// Stonebreak Models/DaedalusTGM-Exp/tests/test_cenda_engine.py.
#include "cenda/infer.h"

#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {

int g_fail = 0;
#define EXPECT(c)                                                                                                     \
    do {                                                                                                              \
        if (!(c)) {                                                                                                   \
            std::fprintf(stderr, "%s:%d: EXPECT(%s) failed (%s)\n", __FILE__, __LINE__, #c, ci_last_error());     \
            ++g_fail;                                                                                                 \
        }                                                                                                             \
    } while (0)

// The test model's kernels. Per window: y[i] = x[i] * scale + add[i] (+ 100 when the `bonus` bit is on via a second
// launch that only runs with mask bit 0; the first launch's side output `aux` is dropped while bit 1 is on).
const char* kSource = R"(
struct AxpyArgs { const float* x; const float* add; float* y; float* aux; long long n; int flags; float scale; };
extern "C" __global__ void t_axpy(const AxpyArgs a) {
    long long i = (long long)blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= a.n) return;
    float v = a.x[i] * a.scale + a.add[i % 64];
    a.y[i] = v;
    if ((a.flags & 8) && a.aux) a.aux[i] = -v;
}
struct BonusArgs { float* y; long long n; float v; };
extern "C" __global__ void t_bonus(const BonusArgs a) {
    long long i = (long long)blockIdx.x * blockDim.x + threadIdx.x;
    if (i < a.n) a.y[i] += a.v;
}
)";

constexpr int kH = 8, kW = 8, kC = 2;            // window: (C, H, W) fp32 = 128 values
constexpr int kN = kH * kW * kC;

std::string manifest(size_t src_bytes, size_t add_bytes, size_t add_off) {
    // buffers: 0 = input (tensor io), 1 = output, 2 = aux (filled with 7.0f bits at every 2nd value)
    std::string m = R"({"format":1,"name":"test","model_id":"t","kind":"tensor","styles":["bonus","quiet"],
"layouts":{
 "axpy":{"size":48,"fields":[["x","ptr",0],["add","ptr",8],["y","ptr",16],["aux","ptr",24],["n","i64",32],["flags","i32",40],["scale","f32",44]]},
 "bonus":{"size":24,"fields":[["y","ptr",0],["n","i64",8],["v","f32",16]]}},
"sources":{"t":{"offset":0,"nbytes":)" + std::to_string(src_bytes) +
                    R"(}},
"tensors":[{"offset":)" + std::to_string(add_off) + R"(,"nbytes":)" + std::to_string(add_bytes) + R"(}],
"buffers":[{"nbytes":512},{"nbytes":512},{"nbytes":512,"fill":[{"stride":2,"at":1,"dtype":"f32","bits":1088421888}]}],
"io":{"kind":"tensor","H":8,"W":8,"n_in":2,"stem":{"buffer":0,"channels":2},"output":{"buffer":1,"kind":"f32","channels":2}},
"ops":[
 {"src":"t","name":"t_axpy","layout":"axpy","grid":[1,1,1],"block":128,
  "scale":{"field":"n","rule":"ceil","tile":128,"tiles":1},"drop":{"unless":2,"ptr":"aux","flags":"flags","bit":8},
  "args":{"x":{"b":0},"add":{"t":0},"y":{"b":1},"aux":{"b":2},"n":128,"flags":8,"scale":2.0}},
 {"src":"t","name":"t_bonus","layout":"bonus","grid":[1,1,1],"block":128,"when":1,
  "scale":{"field":"n","rule":"ceil","tile":128,"tiles":1},"args":{"y":{"b":1},"n":128,"v":100.0}}
]})";
    return m;
}

std::string write_model(const std::filesystem::path& dir) {
    std::vector<float> add(64);
    for (int i = 0; i < 64; ++i) add[static_cast<size_t>(i)] = static_cast<float>(i) * 0.5f;
    std::string src = kSource;
    size_t src_pad = (src.size() + 255) / 256 * 256;
    std::string man = manifest(src.size(), add.size() * 4, src_pad);
    std::string head = "CENDAINF";
    uint32_t fmt = 1, zero = 0;
    uint64_t mlen = man.size();
    head.append(reinterpret_cast<const char*>(&fmt), 4);
    head.append(reinterpret_cast<const char*>(&zero), 4);
    head.append(reinterpret_cast<const char*>(&mlen), 8);
    head += man;
    head.resize((head.size() + 255) / 256 * 256, '\0');
    std::string blob = src;
    blob.resize(src_pad, '\0');
    blob.append(reinterpret_cast<const char*>(add.data()), add.size() * 4);
    auto p = dir / "test.cim";
    std::ofstream(p, std::ios::binary) << head << blob;
    return p.string();
}

float expect_y(int b, int i, uint32_t mask) {
    float x = static_cast<float>(b * 1000 + i) * 0.25f;
    float v = x * 2.0f + static_cast<float>(i % 64) * 0.5f;
    return (mask & 1u) ? v + 100.0f : v;
}

void run_cases(ci_runtime* rt, const std::string& path, bool graphs) {
    ci_model_options mo{};
    mo.max_batch = 3;
    mo.contexts = 2;
    mo.flags = graphs ? 0 : CI_MODEL_NO_GRAPHS;
    ci_model* m = ci_model_load(rt, path.c_str(), &mo);
    EXPECT(m != nullptr);
    if (m == nullptr) return;
    EXPECT(std::strstr(ci_model_info(m), "\"kind\":\"tensor\"") != nullptr);
    EXPECT(ci_model_warmup(m, nullptr, 0, nullptr, 0) == CI_OK);
    for (int batch = 1; batch <= 3; ++batch)
        for (uint32_t mask = 0; mask < 4; ++mask) {
            std::vector<float> in(static_cast<size_t>(batch * kN)), out(in.size(), -1.0f);
            for (int b = 0; b < batch; ++b)
                for (int i = 0; i < kN; ++i)
                    in[static_cast<size_t>(b * kN + i)] = static_cast<float>(b * 1000 + i) * 0.25f;
            ci_request r{};
            r.batch = batch;
            r.flags = CI_RUN_HOST | CI_RUN_MASK_GIVEN;
            r.mask = mask;
            r.inputs[0] = in.data();
            r.outputs[0] = out.data();
            EXPECT(ci_run(m, &r) == CI_OK);
            int bad = 0;
            for (int b = 0; b < batch; ++b)
                for (int i = 0; i < kN; ++i)
                    if (out[static_cast<size_t>(b * kN + i)] != expect_y(b, i, mask)) ++bad;
            EXPECT(bad == 0);
        }
    // A mask naming a style the model lacks is refused.
    {
        std::vector<float> in(kN), out(kN);
        ci_request r{};
        r.batch = 1;
        r.flags = CI_RUN_HOST | CI_RUN_MASK_GIVEN;
        r.mask = 4;
        r.inputs[0] = in.data();
        r.outputs[0] = out.data();
        EXPECT(ci_run(m, &r) == CI_ERR_UNSUPPORTED);
        r.batch = 4;
        r.mask = 0;
        EXPECT(ci_run(m, &r) == CI_ERR_ARG);
    }
    // Concurrent requests from threads (both contexts busy at once).
    std::atomic<int> errors{0};
    std::vector<std::thread> ts;
    for (int t = 0; t < 4; ++t)
        ts.emplace_back([&, t]() {
            for (int k = 0; k < 50; ++k) {
                int batch = 1 + (t + k) % 3;
                uint32_t mask = static_cast<uint32_t>((t + k) % 4);
                std::vector<float> in(static_cast<size_t>(batch * kN)), out(in.size());
                for (int b = 0; b < batch; ++b)
                    for (int i = 0; i < kN; ++i)
                        in[static_cast<size_t>(b * kN + i)] = static_cast<float>(b * 1000 + i) * 0.25f;
                ci_request r{};
                r.batch = batch;
                r.flags = CI_RUN_HOST | CI_RUN_MASK_GIVEN;
                r.mask = mask;
                r.inputs[0] = in.data();
                r.outputs[0] = out.data();
                if (ci_run(m, &r) != CI_OK) {
                    ++errors;
                    continue;
                }
                for (int b = 0; b < batch; ++b)
                    for (int i = 0; i < kN; ++i)
                        if (out[static_cast<size_t>(b * kN + i)] != expect_y(b, i, mask)) {
                            ++errors;
                            return;
                        }
            }
        });
    for (auto& t : ts) t.join();
    EXPECT(errors.load() == 0);
    double st[8] = {0};
    EXPECT(ci_model_stats(m, st, 8) >= 6);
    EXPECT(st[1] > 200.0);                                   // windows served
    EXPECT(graphs ? st[2] > 0.0 : st[3] > 0.0);              // graph launches vs eager launches
    ci_model_destroy(m);
}

// ---------------------------------------------------------------------------------------------- ci_vit.cu kernels
// The engine's transformer kernels (src/infer/cuda/ci_vit.cu) through the plan executor, against double-precision
// CPU references: GEMM (3xTF32 tensor cores and SIMT; bias + residual + GELU), LayerNorm, fused attention.
namespace vit {
constexpr int M = 200, N = 72, K = 48, ROWS = 37, D = 96, H = 2, S = 100, HD = 64;
constexpr size_t oA = 0, oW = oA + M * K, oBias = oW + N * K, oR = oBias + N, oX = oR + M * N, oLw = oX + ROWS * D,
                 oLb = oLw + D, oQ = oLb + D, oK = oQ + H * S * HD, oV = oK + H * S * HD, IN_END = oV + H * S * HD;
constexpr size_t oC1 = 0, oC2 = oC1 + M * N, oY = oC2 + M * N, oO = oY + ROWS * D, OUT_END = oO + S * H * HD;
constexpr size_t W = OUT_END; // window = 1 x W "pixels"; input has 2 channels (2 W floats >= IN_END)

std::string source() {
    std::ifstream f(CI_VIT_CU, std::ios::binary);
    std::ostringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

std::string ref(size_t off, int buf) { return "{\"b\":" + std::to_string(buf) + ",\"o\":" + std::to_string(off * 4) + "}"; }

std::string gemm_op(const char* name, size_t c_off) {
    return std::string("{\"src\":\"vit\",\"name\":\"") + name +
           "\",\"layout\":\"gemm\",\"grid\":[1,2,1],\"block\":256,\"args\":{\"A\":" + ref(oA, 0) +
           ",\"B\":" + ref(oW, 0) + ",\"bias\":" + ref(oBias, 0) + ",\"R\":" + ref(oR, 0) + ",\"C\":" + ref(c_off, 1) +
           ",\"lda\":48,\"ldb\":48,\"ldc\":72,\"ldr\":72,\"M\":200,\"N\":72,\"K\":48,\"zdiv\":1,\"act\":1,\"alpha\":1.0}}";
}

std::string write(const std::filesystem::path& dir) {
    std::string src = source();
    size_t pad = (src.size() + 255) / 256 * 256;
    std::string man = std::string(R"({"format":1,"name":"vit","kind":"tensor","layouts":{
"gemm":{"size":160,"fields":[["A","ptr",0],["B","ptr",8],["bias","ptr",16],["R","ptr",24],["C","ptr",32],["lda","i64",40],["ldb","i64",48],["ldc","i64",56],["ldr","i64",64],["sA1","i64",72],["sA2","i64",80],["sB1","i64",88],["sB2","i64",96],["sC1","i64",104],["sC2","i64",112],["sR1","i64",120],["sR2","i64",128],["M","i32",136],["N","i32",140],["K","i32",144],["zdiv","i32",148],["act","i32",152],["alpha","f32",156]]},
"ln":{"size":48,"fields":[["x","ptr",0],["w","ptr",8],["b","ptr",16],["y","ptr",24],["rows","i32",32],["D","i32",36],["eps","f32",40]]},
"attn":{"size":56,"fields":[["q","ptr",0],["k","ptr",8],["v","ptr",16],["o","ptr",24],["B","i32",32],["H","i32",36],["N","i32",40],["ldo","i32",44],["scale","f32",48]]}},
"sources":{"vit":{"offset":0,"nbytes":)") + std::to_string(src.size()) + R"(}},"tensors":[],
"buffers":[{"nbytes":)" + std::to_string(2 * W * 4) + R"(},{"nbytes":)" + std::to_string(W * 4) + R"(}],
"io":{"kind":"tensor","H":1,"W":)" + std::to_string(W) + R"(,"n_in":2,"stem":{"buffer":0,"channels":2},"output":{"buffer":1,"kind":"f32","channels":1}},
"ops":[)" + gemm_op("ci_gemm_tf32x3", oC1) + "," + gemm_op("ci_sgemm<1>", oC2) +
                      R"(,{"src":"vit","name":"ci_layernorm","layout":"ln","grid":[5,1,1],"block":256,"args":{"x":)" + ref(oX, 0) +
                      ",\"w\":" + ref(oLw, 0) + ",\"b\":" + ref(oLb, 0) + ",\"y\":" + ref(oY, 1) +
                      R"(,"rows":37,"D":96,"eps":1e-5}},{"src":"vit","name":"ci_attention64","layout":"attn","grid":[2,2,1],"block":256,"smem":66560,"args":{"q":)" +
                      ref(oQ, 0) + ",\"k\":" + ref(oK, 0) + ",\"v\":" + ref(oV, 0) + ",\"o\":" + ref(oO, 1) +
                      R"(,"B":1,"H":2,"N":100,"ldo":128,"scale":0.125}}]})";
    std::string head = "CENDAINF";
    uint32_t fmt = 1, zero = 0;
    uint64_t mlen = man.size();
    head.append(reinterpret_cast<const char*>(&fmt), 4);
    head.append(reinterpret_cast<const char*>(&zero), 4);
    head.append(reinterpret_cast<const char*>(&mlen), 8);
    head += man;
    head.resize((head.size() + 255) / 256 * 256, '\0');
    src.resize(pad, '\0');
    auto p = dir / "vit.cim";
    std::ofstream(p, std::ios::binary) << head << src;
    return p.string();
}

double gelu(double x) { return 0.5 * x * (1.0 + std::erf(x / std::sqrt(2.0))); }

void run(ci_runtime* rt, const std::filesystem::path& dir) {
    std::string path = write(dir);
    ci_model_options mo{};
    mo.max_batch = 1;
    mo.contexts = 1;
    ci_model* m = ci_model_load(rt, path.c_str(), &mo);
    EXPECT(m != nullptr);
    if (m == nullptr) return;
    std::vector<float> in(2 * W, 0.0f), out(W, 0.0f);
    uint32_t seed = 12345;
    auto rnd = [&]() {
        seed = seed * 1664525u + 1013904223u;
        return static_cast<float>(static_cast<int>(seed >> 8) % 20001 - 10000) / 10000.0f;
    };
    for (size_t i = 0; i < IN_END; ++i) in[i] = rnd();
    ci_request r{};
    r.batch = 1;
    r.flags = CI_RUN_HOST;
    r.inputs[0] = in.data();
    r.outputs[0] = out.data();
    EXPECT(ci_run(m, &r) == CI_OK);
    // GEMM: C = gelu(A W^T + bias + R)
    double e1 = 0, e2 = 0, mag = 0;
    for (int i = 0; i < M; ++i)
        for (int j = 0; j < N; ++j) {
            double acc = 0;
            for (int k = 0; k < K; ++k) acc += double(in[oA + size_t(i * K + k)]) * double(in[oW + size_t(j * K + k)]);
            double v = gelu(acc + in[oBias + size_t(j)] + in[oR + size_t(i * N + j)]);
            e1 = std::max(e1, std::abs(v - out[oC1 + size_t(i * N + j)]));
            e2 = std::max(e2, std::abs(v - out[oC2 + size_t(i * N + j)]));
            mag = std::max(mag, std::abs(v));
        }
    std::printf("vit gemm: tf32x3 max err %.3g, simt %.3g (|C| <= %.3g)\n", e1, e2, mag);
    EXPECT(e1 < 1e-5 * mag);
    EXPECT(e2 < 1e-5 * mag);
    // LayerNorm
    double e3 = 0;
    for (int rr = 0; rr < ROWS; ++rr) {
        double mean = 0, var = 0;
        for (int c = 0; c < D; ++c) mean += in[oX + size_t(rr * D + c)];
        mean /= D;
        for (int c = 0; c < D; ++c) var += std::pow(in[oX + size_t(rr * D + c)] - mean, 2);
        var /= D;
        for (int c = 0; c < D; ++c) {
            double y = (in[oX + size_t(rr * D + c)] - mean) / std::sqrt(var + 1e-5) * in[oLw + size_t(c)] + in[oLb + size_t(c)];
            e3 = std::max(e3, std::abs(y - out[oY + size_t(rr * D + c)]));
        }
    }
    std::printf("vit layernorm: max err %.3g\n", e3);
    EXPECT(e3 < 2e-5);
    // Attention: o[n, h * 64 + d] = softmax_m(q k / 8) v
    double e4 = 0;
    for (int h = 0; h < H; ++h)
        for (int n = 0; n < S; ++n) {
            std::vector<double> sc(S);
            double mx = -1e300, sum = 0;
            for (int mm = 0; mm < S; ++mm) {
                double d0 = 0;
                for (int d = 0; d < HD; ++d)
                    d0 += double(in[oQ + size_t((h * S + n) * HD + d)]) * in[oK + size_t((h * S + mm) * HD + d)];
                sc[size_t(mm)] = d0 * 0.125;
                mx = std::max(mx, sc[size_t(mm)]);
            }
            for (double& v : sc) sum += (v = std::exp(v - mx));
            for (int d = 0; d < HD; ++d) {
                double o = 0;
                for (int mm = 0; mm < S; ++mm) o += sc[size_t(mm)] / sum * in[oV + size_t((h * S + mm) * HD + d)];
                e4 = std::max(e4, std::abs(o - out[oO + size_t(n * 128 + h * HD + d)]));
            }
        }
    std::printf("vit attention: max err %.3g\n", e4);
    EXPECT(e4 < 2e-5);
    ci_model_destroy(m);
}
} // namespace vit

// ---------------------------------------------------------------------------------------------- compute API (ABI 2)
void compute_api(ci_runtime* rt) {
    const char* src = R"(
struct ScaleArgs { const float* x; float* y; long long n; float a; };
extern "C" __global__ void t_scale(const ScaleArgs s) {
    long long i = (long long)blockIdx.x * blockDim.x + threadIdx.x;
    if (i < s.n) s.y[i] = s.x[i] * s.a;
})";
    struct ScaleArgs {
        const float* x;
        float* y;
        long long n;
        float a;
    };
    ci_kernel* k = ci_kernel_get(rt, "t_compute", src, "t_scale");
    EXPECT(k != nullptr);
    EXPECT(ci_kernel_get(rt, "t_compute", src, "t_scale") == k);   // cached
    EXPECT(ci_kernel_get(rt, "t_compute", "this is not CUDA", "nothing") == nullptr);
    if (k == nullptr) return;
    const long long n = 100000;
    float* h = static_cast<float*>(ci_host_alloc(rt, n * 4));
    float* d = static_cast<float*>(ci_malloc(rt, 2 * n * 4));
    void* s = ci_stream_create(rt);
    void* e = ci_event_create(rt);
    EXPECT(h != nullptr && d != nullptr && s != nullptr && e != nullptr);
    if (h == nullptr || d == nullptr || s == nullptr || e == nullptr) return;
    for (long long i = 0; i < n; ++i) h[i] = static_cast<float>(i);
    EXPECT(ci_memcpy(rt, d, h, n * 4, s) == CI_OK);
    EXPECT(ci_memset(rt, d + n, 0, n * 4, s) == CI_OK);
    ScaleArgs a{d, d + n, n, 0.5f};
    uint32_t grid[3] = {static_cast<uint32_t>((n + 255) / 256), 1, 1};
    EXPECT(ci_launch(rt, k, grid, 256, 0, &a, sizeof a, s) == CI_OK);
    EXPECT(ci_event_record(rt, e, s) == CI_OK);
    EXPECT(ci_stream_wait(rt, nullptr, e) == CI_OK);
    EXPECT(ci_memcpy(rt, h, d + n, n * 4, s) == CI_OK);
    EXPECT(ci_stream_sync(rt, s) == CI_OK);
    EXPECT(ci_event_sync(rt, e) == CI_OK);
    int bad = 0;
    for (long long i = 0; i < n; ++i)
        if (h[i] != static_cast<float>(i) * 0.5f) ++bad;
    EXPECT(bad == 0);
    ci_event_destroy(rt, e);
    ci_stream_destroy(rt, s);
    ci_free(rt, d);
    ci_host_free(rt, h);
    std::printf("compute api: OK\n");
}

} // namespace

int main() {
    EXPECT(ci_abi_version() == CI_ABI_VERSION);
    // Not a model file / missing file: clean errors without a device.
    auto dir = std::filesystem::temp_directory_path() / ("ci_test_" + std::to_string(std::rand()));
    std::filesystem::create_directories(dir);
    std::string path = write_model(dir);
    {
        std::ofstream(dir / "bad.cim") << "not a model";
    }
    if (!ci_cuda_available(0)) {
        std::printf("SKIP: no usable CUDA device (%s)\n", ci_last_error());
        std::filesystem::remove_all(dir);
        return g_fail ? 1 : 77;
    }
    ci_runtime_options ro{};
    std::string cache = (dir / "cache").string();
    ro.cache_dir = cache.c_str();
    ci_runtime* rt = ci_runtime_create(0, &ro);
    EXPECT(rt != nullptr);
    if (rt != nullptr) {
        EXPECT(ci_model_load(rt, (dir / "bad.cim").string().c_str(), nullptr) == nullptr);
        EXPECT(ci_model_load(rt, (dir / "missing.cim").string().c_str(), nullptr) == nullptr);
        run_cases(rt, path, false);
        run_cases(rt, path, true);
        run_cases(rt, path, true); // second load: kernels from the in-process + disk cache
        vit::run(rt, dir);
        compute_api(rt);
        EXPECT(std::filesystem::exists(dir / "cache"));
        ci_runtime_destroy(rt);
    }
    std::filesystem::remove_all(dir);
    if (g_fail) std::fprintf(stderr, "%d failure(s)\n", g_fail);
    else std::printf("infer_engine: OK\n");
    return g_fail ? 1 : 0;
}
