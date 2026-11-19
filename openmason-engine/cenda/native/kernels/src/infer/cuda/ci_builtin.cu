// Cenda inference engine: built-in kernels (input loading, expert-gate pooling and activity, output conversion).
// Compiled at run time by NVRTC (no headers): fp16 is raw 16-bit storage converted with inline PTX, rounding to
// nearest even like torch's .to(torch.float16).

typedef unsigned short u16;
typedef unsigned int u32;

__device__ __forceinline__ u16 ci_to_h(float v) {
    u16 r;
    asm("cvt.rn.f16.f32 %0, %1;" : "=h"(r) : "f"(v));
    return r;
}
__device__ __forceinline__ float ci_from_h(u16 h) {
    float r;
    asm("cvt.f32.f16 %0, %1;" : "=f"(r) : "h"(h));
    return r;
}

// Flow samplers' state: x0 = noise (* scale when apply_scale) -> xs (fp32) and channel x0_ch of the NHWC fp16 stem
// input. One thread per pixel of the batch.
extern "C" __global__ void ci_load_x0(const float* noise, float* xs, u16* stem, long long n, int stem_c, int x0_ch,
                                      float scale, int apply_scale) {
    long long p = (long long)blockIdx.x * blockDim.x + threadIdx.x;
    if (p >= n) return;
    float x = noise[p];
    if (apply_scale) x = scale * x;
    xs[p] = x;
    stem[p * stem_c + x0_ch] = ci_to_h(x);
}

// NCHW fp32 (B, C, H, W) -> channels [dst_off, dst_off + C) of an NHWC fp16 tensor with dst_c channels.
extern "C" __global__ void ci_load_nchw(const float* src, u16* dst, int B, int C, long long HW, int dst_c,
                                       int dst_off) {
    long long i = (long long)blockIdx.x * blockDim.x + threadIdx.x;   // over B * HW * C, channel fastest
    long long total = (long long)B * HW * C;
    if (i >= total) return;
    int c = (int)(i % C);
    long long pix = i / C;                    // b * HW + yx
    long long b = pix / HW, yx = pix - b * HW;
    dst[pix * dst_c + dst_off + c] = ci_to_h(src[(b * C + c) * HW + yx]);
}

// Bits of the styles whose gate is > 0 anywhere in each window: wmask[b] |= 1 << bit for gate channel `channel`.
// wmask must be zeroed first. Grid: (blocks over HW, B).
extern "C" __global__ void ci_gate_activity(const float* gates, u32* wmask, int n_channels, int channel, long long HW,
                                           u32 bit) {
    int b = blockIdx.y;
    const float* g = gates + ((long long)b * n_channels + channel) * HW;
    int any = 0;
    for (long long i = (long long)blockIdx.x * blockDim.x + threadIdx.x; i < HW; i += (long long)gridDim.x * blockDim.x)
        any |= g[i] > 0.0f;
    any = __syncthreads_or(any);
    if (any && threadIdx.x == 0) atomicOr(wmask + b, 1u << bit);
}

// One expert's gate at a level: the mean over f x f blocks of gate channel `channel` (torch adaptive_avg_pool2d's
// order: rows, then columns, sum / f / f; f = 1 is a copy), (B, H / f, W / f) fp32. Windows whose mask (wmask,
// optional) lacks `bit` get 0: their expert is skipped, as when they run alone.
extern "C" __global__ void ci_pool_gate(const float* gates, float* out, const u32* wmask, int n_channels, int channel,
                                       int H, int W, int f, u32 bit, int B) {
    int ho = H / f, wo = W / f;
    long long i = (long long)blockIdx.x * blockDim.x + threadIdx.x;
    long long total = (long long)B * ho * wo;
    if (i >= total) return;
    int ox = (int)(i % wo);
    long long r = i / wo;
    int oy = (int)(r % ho);
    int b = (int)(r / ho);
    if (wmask != 0 && !((wmask[b] >> bit) & 1u)) {
        out[i] = 0.0f;
        return;
    }
    const float* g = gates + ((long long)b * n_channels + channel) * H * W;
    if (f == 1) {
        out[i] = g[(long long)oy * W + ox];
        return;
    }
    const float* p = g + (long long)(oy * f) * W + ox * f;
    float sum = 0.0f;
    for (int y = 0; y < f; ++y) {
        for (int x = 0; x < f; ++x) sum += p[x];
        p += W;
    }
    out[i] = sum / (float)f / (float)f;
}

// NHWC fp16 (B, H, W, src_c) channels [0, C) -> NCHW fp32 (B, C, H, W).
extern "C" __global__ void ci_store_nchw(const u16* src, float* dst, int B, int C, long long HW, int src_c) {
    long long i = (long long)blockIdx.x * blockDim.x + threadIdx.x;   // over B * C * HW, pixel fastest
    long long total = (long long)B * C * HW;
    if (i >= total) return;
    long long yx = i % HW;
    long long r = i / HW;
    int c = (int)(r % C);
    long long b = r / C;
    dst[i] = ci_from_h(src[(b * HW + yx) * src_c + c]);
}
