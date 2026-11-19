// Cenda inference engine: fp32 transformer kernels (vision-transformer encoders such as DaedalusTGM's planner).
// Compiled at run time by NVRTC (no headers). Exporters reference these kernels by name from a model's plan and
// embed this file in the model (single source: this file).
//
// Numerics: IEEE fp32 throughout (no tensor cores, no TF32), fixed reduction orders, so a launch's result does not
// depend on the batch or the grid. Where torch's reference computes separately rounded products (RoPE), the kernel
// uses __fmul_rn / __fadd_rn so no FMA contraction changes the rounding.

typedef unsigned int u32;

// ------------------------------------------------------------------------------------------------ GEMM
// C[m, n] = alpha * sum_k A[m, k] B'[k, n] (+ bias[n]) (+ R[m, n]), then act (0 none, 1 GELU-erf).
// BT = 0: B is (K, N) row-major; BT = 1: B is (N, K) row-major (torch Linear weights, K^T of attention).
// Batch (grid.z): window z of the batch reads at offsets (z / zdiv) * s1 + (z % zdiv) * s2 of each operand.
struct GemmArgs {
    const float* A;
    const float* B;
    const float* bias;
    const float* R;
    float* C;
    long long lda, ldb, ldc, ldr;
    long long sA1, sA2, sB1, sB2, sC1, sC2, sR1, sR2;
    int M, N, K, zdiv, act;
    float alpha;
};

__device__ __forceinline__ float ci_gelu(float x) { return 0.5f * x * (1.0f + erff(x * 0.70710678118654752440f)); }

// 128 x 128 output tile per block of 256 threads, k-slices of 8, two shared-memory stages (the next slice loads into
// registers while the current one computes), 8 x 8 outputs per thread as two 4-row x two 4-column blocks (float4
// shared loads). Every output is one fmaf chain over k in order: the result is independent of tiles and batch.
// Requires K % 4 == 0 and 16-byte aligned rows (lda, ldb % 4 == 0); edges are guarded.
#define GT 128
#define GK 8
template <int BT>
__global__ void __launch_bounds__(256) ci_sgemm(const GemmArgs a) {
    __shared__ __align__(16) float As[2][GK][GT];
    __shared__ __align__(16) float Bs[2][GK][GT];
    const int z = blockIdx.z, z1 = z / a.zdiv, z2 = z - z1 * a.zdiv;
    const float* A = a.A + z1 * a.sA1 + z2 * a.sA2;
    const float* B = a.B + z1 * a.sB1 + z2 * a.sB2;
    float* C = a.C + z1 * a.sC1 + z2 * a.sC2;
    const float* R = a.R ? a.R + z1 * a.sR1 + z2 * a.sR2 : 0;
    const int m0 = blockIdx.y * GT, n0 = blockIdx.x * GT;
    const int tid = threadIdx.x, tx = tid & 15, ty = tid >> 4;
    // loader roles: A (and B when BT = 1): row tid / 2, k quad (tid & 1) * 4; B when BT = 0: k row tid / 32, n quad
    const int lr = tid >> 1, lk = (tid & 1) * 4;
    const int bk = tid >> 5, bn = (tid & 31) * 4;
    float4 ra, rb;
    auto load = [&](int k0) {
        int m = m0 + lr, k = k0 + lk;
        ra = make_float4(0.f, 0.f, 0.f, 0.f);
        if (m < a.M && k < a.K) ra = *reinterpret_cast<const float4*>(A + (long long)m * a.lda + k);
        rb = make_float4(0.f, 0.f, 0.f, 0.f);
        if (BT == 1) {
            int n = n0 + lr;
            if (n < a.N && k < a.K) rb = *reinterpret_cast<const float4*>(B + (long long)n * a.ldb + k);
        } else {
            int kk = k0 + bk, n = n0 + bn;
            if (kk < a.K) {
                const float* bp = B + (long long)kk * a.ldb + n;
                if (n + 3 < a.N) {
                    rb = *reinterpret_cast<const float4*>(bp);
                } else {
                    if (n < a.N) rb.x = bp[0];
                    if (n + 1 < a.N) rb.y = bp[1];
                    if (n + 2 < a.N) rb.z = bp[2];
                }
            }
        }
    };
    auto store = [&](int st) {
        As[st][lk + 0][lr] = ra.x;
        As[st][lk + 1][lr] = ra.y;
        As[st][lk + 2][lr] = ra.z;
        As[st][lk + 3][lr] = ra.w;
        if (BT == 1) {
            Bs[st][lk + 0][lr] = rb.x;
            Bs[st][lk + 1][lr] = rb.y;
            Bs[st][lk + 2][lr] = rb.z;
            Bs[st][lk + 3][lr] = rb.w;
        } else {
            *reinterpret_cast<float4*>(&Bs[st][bk][bn]) = rb;
        }
    };
    float acc[8][8];
#pragma unroll
    for (int i = 0; i < 8; ++i)
#pragma unroll
        for (int j = 0; j < 8; ++j) acc[i][j] = 0.0f;
    load(0);
    store(0);
    __syncthreads();
    int st = 0;
    for (int k0 = 0; k0 < a.K; k0 += GK) {
        const bool more = k0 + GK < a.K;
        if (more) load(k0 + GK);
#pragma unroll
        for (int kk = 0; kk < GK; ++kk) {
            float af[8], bf[8];
            *reinterpret_cast<float4*>(af) = *reinterpret_cast<const float4*>(&As[st][kk][ty * 4]);
            *reinterpret_cast<float4*>(af + 4) = *reinterpret_cast<const float4*>(&As[st][kk][64 + ty * 4]);
            *reinterpret_cast<float4*>(bf) = *reinterpret_cast<const float4*>(&Bs[st][kk][tx * 4]);
            *reinterpret_cast<float4*>(bf + 4) = *reinterpret_cast<const float4*>(&Bs[st][kk][64 + tx * 4]);
#pragma unroll
            for (int i = 0; i < 8; ++i)
#pragma unroll
                for (int j = 0; j < 8; ++j) acc[i][j] = fmaf(af[i], bf[j], acc[i][j]);
        }
        if (more) {
            store(st ^ 1);
            __syncthreads();
            st ^= 1;
        }
    }
#pragma unroll
    for (int i = 0; i < 8; ++i) {
        int m = m0 + (i < 4 ? ty * 4 + i : 64 + ty * 4 + i - 4);
        if (m >= a.M) continue;
#pragma unroll
        for (int j = 0; j < 8; ++j) {
            int n = n0 + (j < 4 ? tx * 4 + j : 64 + tx * 4 + j - 4);
            if (n >= a.N) continue;
            float v = a.alpha * acc[i][j];
            if (a.bias) v = v + a.bias[n];
            if (R) v = v + R[(long long)m * a.ldr + n];
            if (a.act == 1) v = ci_gelu(v);
            C[(long long)m * a.ldc + n] = v;
        }
    }
}

// ------------------------------------------------------------------------------------------------ LayerNorm
// y = (x - mean) / sqrt(var + eps) * w + b over rows of D (biased variance, two passes); one warp per row.
struct LayerNormArgs {
    const float* x;
    const float* w;
    const float* b;
    float* y;
    int rows, D;
    float eps;
};

extern "C" __global__ void __launch_bounds__(256) ci_layernorm(const LayerNormArgs a) {
    const int lane = threadIdx.x & 31;
    const long long row = (long long)blockIdx.x * 8 + (threadIdx.x >> 5);
    if (row >= a.rows) return;
    const float* x = a.x + row * a.D;
    float s = 0.0f;
    for (int i = lane; i < a.D; i += 32) s += x[i];
#pragma unroll
    for (int o = 16; o > 0; o >>= 1) s += __shfl_xor_sync(0xffffffffu, s, o);
    const float mean = s / (float)a.D;
    float q = 0.0f;
    for (int i = lane; i < a.D; i += 32) {
        float d = x[i] - mean;
        q = fmaf(d, d, q);
    }
#pragma unroll
    for (int o = 16; o > 0; o >>= 1) q += __shfl_xor_sync(0xffffffffu, q, o);
    const float rstd = rsqrtf(q / (float)a.D + a.eps);
    float* y = a.y + row * a.D;
    for (int i = lane; i < a.D; i += 32) y[i] = (x[i] - mean) * rstd * a.w[i] + a.b[i];
}

// ------------------------------------------------------------------------------------------------ attention glue
// qkv (B * N, 3 * H * hd) -> q, k, v each (B, H, N, hd); RoPE on q and k: pair p of a head (elements 2p, 2p + 1) is
// rotated by angle table[n, p] (cos / sin tables (N, hd / 2)), as x1 cos - x2 sin, x1 sin + x2 cos.
struct RopeArgs {
    const float* qkv;
    const float* cos_t;
    const float* sin_t;
    float* q;
    float* k;
    float* v;
    int B, N, H, hd;
};

extern "C" __global__ void ci_rope_split(const RopeArgs a) {
    const int half = a.hd >> 1;
    long long i = (long long)blockIdx.x * blockDim.x + threadIdx.x;   // over B * N * 3 * H * half
    long long total = (long long)a.B * a.N * 3 * a.H * half;
    if (i >= total) return;
    int p = (int)(i % half);
    long long r = i / half;
    int h = (int)(r % a.H);
    r /= a.H;
    int part = (int)(r % 3);
    r /= 3;
    int n = (int)(r % a.N);
    int b = (int)(r / a.N);
    const float* src = a.qkv + ((long long)b * a.N + n) * (3LL * a.H * a.hd) + (long long)part * a.H * a.hd +
                       (long long)h * a.hd + 2 * p;
    float x1 = src[0], x2 = src[1];
    float o1 = x1, o2 = x2;
    if (part < 2) {
        float c = a.cos_t[(long long)n * half + p], s = a.sin_t[(long long)n * half + p];
        o1 = __fsub_rn(__fmul_rn(x1, c), __fmul_rn(x2, s));
        o2 = __fadd_rn(__fmul_rn(x1, s), __fmul_rn(x2, c));
    }
    float* dst = (part == 0 ? a.q : part == 1 ? a.k : a.v) + (((long long)b * a.H + h) * a.N + n) * a.hd + 2 * p;
    dst[0] = o1;
    dst[1] = o2;
}

// Row softmax in place over rows of length L (one warp per row).
struct SoftmaxArgs {
    float* x;
    long long rows;
    int L;
};

extern "C" __global__ void __launch_bounds__(256) ci_softmax(const SoftmaxArgs a) {
    const int lane = threadIdx.x & 31;
    const long long row = (long long)blockIdx.x * 8 + (threadIdx.x >> 5);
    if (row >= a.rows) return;
    float* x = a.x + row * a.L;
    float mx = -3.402823466e38f;
    for (int i = lane; i < a.L; i += 32) mx = fmaxf(mx, x[i]);
#pragma unroll
    for (int o = 16; o > 0; o >>= 1) mx = fmaxf(mx, __shfl_xor_sync(0xffffffffu, mx, o));
    float s = 0.0f;
    for (int i = lane; i < a.L; i += 32) {
        float e = expf(x[i] - mx);
        x[i] = e;
        s += e;
    }
#pragma unroll
    for (int o = 16; o > 0; o >>= 1) s += __shfl_xor_sync(0xffffffffu, s, o);
    const float inv = 1.0f / s;
    for (int i = lane; i < a.L; i += 32) x[i] = x[i] * inv;
}

// ------------------------------------------------------------------------------------------------ patches
// x (B, C, Hp * P, Wp * P) -> tokens (B * Hp * Wp, C * P * P): token (b, i, j), feature c * P * P + py * P + px.
// dir 0 gathers image -> tokens; dir 1 scatters tokens -> image (the head's inverse layout).
struct PatchArgs {
    const float* src;
    float* dst;
    int B, C, Hp, Wp, P, dir;
};

extern "C" __global__ void ci_patches(const PatchArgs a) {
    long long i = (long long)blockIdx.x * blockDim.x + threadIdx.x;   // over the token tensor
    const int F = a.C * a.P * a.P;
    long long total = (long long)a.B * a.Hp * a.Wp * F;
    if (i >= total) return;
    int f = (int)(i % F);
    long long t = i / F;
    int j = (int)(t % a.Wp);
    long long r = t / a.Wp;
    int ii = (int)(r % a.Hp);
    int b = (int)(r / a.Hp);
    int c = f / (a.P * a.P), py = (f / a.P) % a.P, px = f % a.P;
    long long W = (long long)a.Wp * a.P, H = (long long)a.Hp * a.P;
    long long img = (((long long)b * a.C + c) * H + (long long)ii * a.P + py) * W + (long long)j * a.P + px;
    if (a.dir == 0)
        a.dst[i] = a.src[img];
    else
        a.dst[img] = a.src[i];
}

// ------------------------------------------------------------------------------------------------ fused attention
// O = softmax(scale * Q K^T) V per (window, head), flash-style: a block owns 64 queries of one (window, head) and
// streams the keys in blocks of 64 with an online softmax (running max / sum, fp32), so the N x N scores never reach
// memory. q, k, v: (B, H, N, 64); o: row (b * N + n), columns h * 64 .. + 64 of a (B * N, ldo) matrix (heads merged).
// 256 threads: thread (ty, tx) owns query rows ty * 4 .. + 4 and score / output columns tx * 4 .. + 4.
struct AttnArgs {
    const float* q;
    const float* k;
    const float* v;
    float* o;
    int B, H, N, ldo;
    float scale;
};

#define AP 65   // padded row stride (floats) of the shared tiles: column reads across rows hit distinct banks
extern "C" __global__ void __launch_bounds__(256) ci_attention64(const AttnArgs a) {
    extern __shared__ float sm[];
    float* Qs = sm;              // 64 x AP
    float* Ks = Qs + 64 * AP;    // 64 x AP
    float* Vs = Ks + 64 * AP;    // 64 x AP
    float* Ps = Vs + 64 * AP;    // 64 x AP
    const int tid = threadIdx.x, tx = tid & 15, ty = tid >> 4;
    const int bh = blockIdx.y, q0 = blockIdx.x * 64;
    const long long base = (long long)bh * a.N * 64;
    for (int e = tid; e < 64 * 64; e += 256) {
        int r = e >> 6, c = e & 63;
        Qs[r * AP + c] = (q0 + r < a.N) ? a.q[base + (long long)(q0 + r) * 64 + c] : 0.0f;
    }
    float m[4], l[4], acc[4][4];
#pragma unroll
    for (int i = 0; i < 4; ++i) {
        m[i] = -3.402823466e38f;
        l[i] = 0.0f;
#pragma unroll
        for (int j = 0; j < 4; ++j) acc[i][j] = 0.0f;
    }
    for (int k0 = 0; k0 < a.N; k0 += 64) {
        __syncthreads();   // previous block's Ks / Vs / Ps reads are done (and Qs is written, first pass)
        for (int e = tid; e < 64 * 64; e += 256) {
            int r = e >> 6, c = e & 63;
            bool ok = k0 + r < a.N;
            Ks[r * AP + c] = ok ? a.k[base + (long long)(k0 + r) * 64 + c] : 0.0f;
            Vs[r * AP + c] = ok ? a.v[base + (long long)(k0 + r) * 64 + c] : 0.0f;
        }
        __syncthreads();
        float s[4][4];
#pragma unroll
        for (int i = 0; i < 4; ++i)
#pragma unroll
            for (int j = 0; j < 4; ++j) s[i][j] = 0.0f;
#pragma unroll 8
        for (int d = 0; d < 64; ++d) {
            float qv[4], kv[4];
#pragma unroll
            for (int i = 0; i < 4; ++i) qv[i] = Qs[(ty * 4 + i) * AP + d];
#pragma unroll
            for (int j = 0; j < 4; ++j) kv[j] = Ks[(tx * 4 + j) * AP + d];
#pragma unroll
            for (int i = 0; i < 4; ++i)
#pragma unroll
                for (int j = 0; j < 4; ++j) s[i][j] = fmaf(qv[i], kv[j], s[i][j]);
        }
#pragma unroll
        for (int i = 0; i < 4; ++i) {
            float mx = -3.402823466e38f;
#pragma unroll
            for (int j = 0; j < 4; ++j) {
                s[i][j] = (k0 + tx * 4 + j < a.N) ? s[i][j] * a.scale : -3.402823466e38f;
                mx = fmaxf(mx, s[i][j]);
            }
#pragma unroll
            for (int off = 8; off > 0; off >>= 1) mx = fmaxf(mx, __shfl_xor_sync(0xffffffffu, mx, off));
            const float mn = fmaxf(m[i], mx);
            const float corr = expf(m[i] - mn);
            float rs = 0.0f;
#pragma unroll
            for (int j = 0; j < 4; ++j) {
                float p = (k0 + tx * 4 + j < a.N) ? expf(s[i][j] - mn) : 0.0f;
                Ps[(ty * 4 + i) * AP + tx * 4 + j] = p;
                rs += p;
            }
#pragma unroll
            for (int off = 8; off > 0; off >>= 1) rs += __shfl_xor_sync(0xffffffffu, rs, off);
            l[i] = l[i] * corr + rs;
            m[i] = mn;
#pragma unroll
            for (int j = 0; j < 4; ++j) acc[i][j] *= corr;
        }
        __syncthreads();
#pragma unroll 8
        for (int kk = 0; kk < 64; ++kk) {
            float pv[4], vv[4];
#pragma unroll
            for (int i = 0; i < 4; ++i) pv[i] = Ps[(ty * 4 + i) * AP + kk];
#pragma unroll
            for (int j = 0; j < 4; ++j) vv[j] = Vs[kk * AP + tx * 4 + j];
#pragma unroll
            for (int i = 0; i < 4; ++i)
#pragma unroll
                for (int j = 0; j < 4; ++j) acc[i][j] = fmaf(pv[i], vv[j], acc[i][j]);
        }
    }
    const int b = bh / a.H, h = bh - b * a.H;
#pragma unroll
    for (int i = 0; i < 4; ++i) {
        int n = q0 + ty * 4 + i;
        if (n >= a.N) continue;
        const float inv = 1.0f / l[i];
        float* op = a.o + ((long long)b * a.N + n) * a.ldo + h * 64 + tx * 4;
#pragma unroll
        for (int j = 0; j < 4; ++j) op[j] = acc[i][j] * inv;
    }
}

// ------------------------------------------------------------------------------------------------ 3xTF32 GEMM
// The GemmArgs product on tensor cores at fp32-level accuracy: each fp32 operand is split into tf32 hi + lo
// (x = hi + lo + O(2^-22 x)) and a * b ~ a_lo b_hi + a_hi b_lo + a_hi b_hi (mma.sync m16n8k8 tf32, fp32 accumulate),
// the small terms first. B is (N, K) row-major (BT = 1 only), K % 16 == 0, rows 16-byte aligned. 128 x 128 tile,
// 8 warps (4 along M x 2 along N, 32 x 64 each), k-slices of 16 double-buffered through registers. Fixed k order per
// output: batch- and grid-independent like ci_sgemm (but a different rounding sequence).
__device__ __forceinline__ u32 ci_tf32(float x) {
    u32 r;
    asm("cvt.rna.tf32.f32 %0, %1;" : "=r"(r) : "f"(x));
    return r;
}
__device__ __forceinline__ void ci_mma_tf32(float* d, const u32* a, const u32* b) {
    asm volatile("mma.sync.aligned.m16n8k8.row.col.f32.tf32.tf32.f32 {%0,%1,%2,%3}, {%4,%5,%6,%7}, {%8,%9}, "
                 "{%0,%1,%2,%3};\n"
                 : "+f"(d[0]), "+f"(d[1]), "+f"(d[2]), "+f"(d[3])
                 : "r"(a[0]), "r"(a[1]), "r"(a[2]), "r"(a[3]), "r"(b[0]), "r"(b[1]));
}

#define TA 20    // A smem row stride (floats): fragment reads hit 32 distinct banks
#define TB 136   // B smem row stride
extern "C" __global__ void __launch_bounds__(256) ci_gemm_tf32x3(const GemmArgs a) {
    __shared__ __align__(16) float As[2][128 * TA];
    __shared__ __align__(16) float Bs[2][16 * TB];
    const int z = blockIdx.z, z1 = z / a.zdiv, z2 = z - z1 * a.zdiv;
    const float* A = a.A + z1 * a.sA1 + z2 * a.sA2;
    const float* B = a.B + z1 * a.sB1 + z2 * a.sB2;
    float* C = a.C + z1 * a.sC1 + z2 * a.sC2;
    const float* R = a.R ? a.R + z1 * a.sR1 + z2 * a.sR2 : 0;
    const int m0 = blockIdx.y * 128, n0 = blockIdx.x * 128;
    const int tid = threadIdx.x, lane = tid & 31, warp = tid >> 5;
    const int wm = warp & 3, wn = warp >> 2;
    const int g = lane >> 2, t = lane & 3;
    const int lr = tid >> 2, lk = (tid & 3) * 4;   // loader: rows lr and lr + 64, k quad lk
    float4 ra[2], rb[2];
    auto load = [&](int k0) {
#pragma unroll
        for (int h = 0; h < 2; ++h) {
            int m = m0 + lr + 64 * h, n = n0 + lr + 64 * h;
            ra[h] = m < a.M ? *reinterpret_cast<const float4*>(A + (long long)m * a.lda + k0 + lk)
                            : make_float4(0.f, 0.f, 0.f, 0.f);
            rb[h] = n < a.N ? *reinterpret_cast<const float4*>(B + (long long)n * a.ldb + k0 + lk)
                            : make_float4(0.f, 0.f, 0.f, 0.f);
        }
    };
    auto store = [&](int st) {
#pragma unroll
        for (int h = 0; h < 2; ++h) {
            int r = lr + 64 * h;
            *reinterpret_cast<float4*>(&As[st][r * TA + lk]) = ra[h];
            Bs[st][(lk + 0) * TB + r] = rb[h].x;
            Bs[st][(lk + 1) * TB + r] = rb[h].y;
            Bs[st][(lk + 2) * TB + r] = rb[h].z;
            Bs[st][(lk + 3) * TB + r] = rb[h].w;
        }
    };
    float acc[2][8][4];
#pragma unroll
    for (int i = 0; i < 2; ++i)
#pragma unroll
        for (int j = 0; j < 8; ++j)
#pragma unroll
            for (int e = 0; e < 4; ++e) acc[i][j][e] = 0.0f;
    load(0);
    store(0);
    __syncthreads();
    int st = 0;
    for (int k0 = 0; k0 < a.K; k0 += 16) {
        const bool more = k0 + 16 < a.K;
        if (more) load(k0 + 16);
        const float* as = As[st];
        const float* bs = Bs[st];
#pragma unroll
        for (int ks = 0; ks < 16; ks += 8) {
            u32 ah[2][4], al[2][4];
#pragma unroll
            for (int mt = 0; mt < 2; ++mt) {
                int r = wm * 32 + mt * 16 + g;
                float v[4] = {as[r * TA + ks + t], as[(r + 8) * TA + ks + t], as[r * TA + ks + t + 4],
                              as[(r + 8) * TA + ks + t + 4]};
#pragma unroll
                for (int e = 0; e < 4; ++e) {
                    ah[mt][e] = ci_tf32(v[e]);
                    al[mt][e] = ci_tf32(v[e] - __uint_as_float(ah[mt][e]));
                }
            }
#pragma unroll
            for (int nt = 0; nt < 8; ++nt) {
                int c = wn * 64 + nt * 8 + g;
                float v[2] = {bs[(ks + t) * TB + c], bs[(ks + t + 4) * TB + c]};
                u32 bh[2], bl[2];
#pragma unroll
                for (int e = 0; e < 2; ++e) {
                    bh[e] = ci_tf32(v[e]);
                    bl[e] = ci_tf32(v[e] - __uint_as_float(bh[e]));
                }
#pragma unroll
                for (int mt = 0; mt < 2; ++mt) {
                    ci_mma_tf32(acc[mt][nt], al[mt], bh);
                    ci_mma_tf32(acc[mt][nt], ah[mt], bl);
                    ci_mma_tf32(acc[mt][nt], ah[mt], bh);
                }
            }
        }
        if (more) {   // the other stage was last read before the previous iteration's barrier: safe to overwrite
            store(st ^ 1);
            __syncthreads();
            st ^= 1;
        }
    }
#pragma unroll
    for (int mt = 0; mt < 2; ++mt)
#pragma unroll
        for (int hh = 0; hh < 2; ++hh) {
            int m = m0 + wm * 32 + mt * 16 + g + hh * 8;
            if (m >= a.M) continue;
#pragma unroll
            for (int nt = 0; nt < 8; ++nt)
#pragma unroll
                for (int e = 0; e < 2; ++e) {
                    int n = n0 + wn * 64 + nt * 8 + 2 * t + e;
                    if (n >= a.N) continue;
                    float v = a.alpha * acc[mt][nt][hh * 2 + e];
                    if (a.bias) v = v + a.bias[n];
                    if (R) v = v + R[(long long)m * a.ldr + n];
                    if (a.act == 1) v = ci_gelu(v);
                    C[(long long)m * a.ldc + n] = v;
                }
        }
}
