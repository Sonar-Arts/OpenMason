/* Cenda inference engine — runs exported neural networks (flow-matching UNet samplers, regression UNets, small
 * vision transformers) on the GPU behind a C ABI. Consumers: Python (ctypes, DaedalusTGM's TGMPipe, phase 1),
 * Stonebreak's own native pipeline (phase 2) and the JVM via FFM (phase 3).
 *
 * Design (see Stonebreak `Dev Working/docs/DaedalusTGM-Exp-2-Native-Engine.md`):
 *  - No CUDA toolkit at build time: the CUDA driver (libcuda / nvcuda.dll) and NVRTC are opened at run time. Without
 *    them the engine reports itself unavailable (ci_cuda_available) and every load fails cleanly.
 *  - A model is ONE file (.cim, "Cenda Inference Model"): a JSON manifest + aligned blobs holding the packed
 *    weights, the execution plan (every kernel launch of every network variant, with pointers stored as
 *    relocations against the model's tensors and the engine's activation buffers) and the CUDA C++ sources the plan
 *    needs. The engine compiles those sources with NVRTC for the device's exact architecture (cubins cached on disk,
 *    keyed by source + kernel + options + architecture), so a model's numbers depend only on its file.
 *  - Execution: per model, `contexts` independent execution contexts (one stream + one set of activation buffers
 *    sized for `max_batch` windows each) run requests concurrently. Every (variant, batch) pair is an explicit CUDA
 *    graph built node by node (no stream capture: nothing a host thread does elsewhere can break it), created at
 *    warm-up or on first use. A request is: load kernel(s) -> graph launch -> output copy, all on the context's
 *    stream; with a caller stream the call orders itself after the caller's work and the caller's stream after it
 *    (events), so it never blocks the host.
 *  - Variants: a network with gated experts has one plan per active-expert subset (bit mask). The caller names the
 *    mask (CI_RUN_MASK_GIVEN: every window of the request has it), or the engine reads each window's mask from its
 *    gates on the device (one small readback, a host sync) and runs windows with different masks as separate
 *    sub-batches -- a window's numbers never depend on the windows batched with it -- or runs every expert
 *    (CI_RUN_MASK_ALL: deterministic, but a gated-off expert is exact only for the residual, not for the SiLU the
 *    next layer reads, so these numbers differ in the last fp16 bits from the window's own-mask plan).
 *
 * Threading: every function is thread-safe. Requests on one model run concurrently up to its context count.
 * Memory: device pointers given to ci_run must stay valid until the request's work is done (ordered on the caller
 * stream, or the call returned for a synchronous request).
 */
#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CI_ABI_VERSION 2   /* 2: + compute API (kernels, memory, streams, events) */

typedef struct ci_runtime ci_runtime;
typedef struct ci_model ci_model;

enum {
    CI_OK = 0,
    CI_ERR_ARG = -1,         /* null handle, bad sizes, unknown option */
    CI_ERR_UNAVAILABLE = -2, /* no CUDA driver / NVRTC / device */
    CI_ERR_FORMAT = -3,      /* the model file is malformed or of an unknown version */
    CI_ERR_CUDA = -4,        /* a CUDA driver call failed */
    CI_ERR_COMPILE = -5,     /* NVRTC failed to compile a kernel */
    CI_ERR_UNSUPPORTED = -6, /* the model needs something this engine / device cannot do */
    CI_ERR_IO = -7           /* file not readable */
};

int32_t ci_abi_version(void);
/* The calling thread's last error message ("" when none). */
const char* ci_last_error(void);

/* 1 when CUDA device `device` can run models (driver + NVRTC loadable, sm_80+), else 0 (reason: ci_last_error). */
int32_t ci_cuda_available(int32_t device);

typedef struct ci_runtime_options {
    const char* cache_dir;   /* compiled-kernel cache directory (created); NULL = no disk cache */
    const char* nvrtc_path;  /* NVRTC library to open; NULL = search the system (CENDA_NVRTC env first) */
    const char* cuda_path;   /* CUDA driver library; NULL = libcuda.so.1 / nvcuda.dll */
    int32_t compile_threads; /* parallel NVRTC compiles at model load; <= 0 = hardware threads */
    int32_t reserved[7];
} ci_runtime_options;

/* A runtime on CUDA device `device` (its primary context: device pointers are shared with any other user of the
 * primary context, e.g. torch). NULL on failure. */
ci_runtime* ci_runtime_create(int32_t device, const ci_runtime_options* options);
void ci_runtime_destroy(ci_runtime* runtime);

typedef struct ci_model_options {
    int32_t max_batch; /* windows per request (activation buffers are sized for it); <= 0 = 1 */
    int32_t contexts;  /* concurrent execution contexts; <= 0 = 1 */
    int32_t graphs;    /* 1 = CUDA graphs (default when 0 is not set explicitly via CI_MODEL_NO_GRAPHS), see flags */
    int32_t flags;     /* CI_MODEL_* */
    int32_t reserved[4];
} ci_model_options;

enum { CI_MODEL_NO_GRAPHS = 1 /* launch kernel by kernel (tests, profiling) */ };

/* Load a .cim model onto the runtime's device: weights uploaded, kernels compiled (or read from the cache),
 * buffers allocated for every context. NULL on failure. The runtime must outlive the model. */
ci_model* ci_model_load(ci_runtime* runtime, const char* path, const ci_model_options* options);
void ci_model_destroy(ci_model* model);

/* The model's description as JSON (kind, input / output shapes, styles, variants, memory): valid until destroy. */
const char* ci_model_info(const ci_model* model);

/* Build the CUDA graphs of these (variant mask, batch) pairs in every context now (NULL masks = every variant;
 * NULL batches = 1 .. max_batch). */
int32_t ci_model_warmup(ci_model* model, const uint32_t* masks, int32_t n_masks, const int32_t* batches,
                        int32_t n_batches);

/* Request flags. */
enum {
    CI_RUN_HOST = 1,       /* inputs / outputs are host pointers (else device pointers) */
    CI_RUN_MASK_GIVEN = 2, /* `mask` names the active experts (bit i = the model's style i) */
    CI_RUN_MASK_ALL = 4,   /* run every expert the model packs (zero gates are exact no-ops) */
    CI_RUN_SYNC = 8,       /* return only when the outputs are written */
    CI_RUN_STREAM = 16     /* order against `stream` (which may be 0: the legacy default stream) */
};

/* One request. Inputs by program kind (ci_model_info "inputs"):
 *   flow     inputs[0] cond  (B, n_cond, H, W) fp32
 *            inputs[1] noise (B, 1, H, W) fp32
 *            inputs[2] gates (B, n_styles, H, W) fp32 or NULL (no expert active)
 *            outputs[0] x    (B, 1, H, W) fp32
 *   forward  inputs[0] x (B, n_in, H, W) fp32 -> outputs[0] (B, n_out, H, W) fp32
 *   vit      inputs[0] x (B, n_in, H, W) fp32 -> outputs[0] (B, n_out, H, W) fp32
 * `stream` (with CI_RUN_STREAM): the caller's CUstream on the same device / primary context; 0 is the legacy default
 * stream (torch's default). The engine's work starts after the caller's previously queued work and the caller's
 * stream continues after the engine's; the call returns without waiting (unless CI_RUN_SYNC). Without
 * CI_RUN_STREAM the inputs must be complete (host data, or device data already synchronised) and the call returns
 * when the outputs are written.
 * `out_mask` (optional) receives the variant that ran. */
typedef struct ci_request {
    int32_t batch;
    int32_t flags;
    uint32_t mask;
    uint32_t reserved0;
    const void* inputs[4];
    void* outputs[2];
    void* stream;
    uint32_t* out_mask;
    int64_t reserved[4];
} ci_request;

int32_t ci_run(ci_model* model, const ci_request* request);

/* Counters since load: requests, windows, graph launches, eager launches, mask readbacks, graphs built, and requests
 * split into same-mask runs. Fills up to n values; returns the count. */
int32_t ci_model_stats(const ci_model* model, double* values, int32_t n);

/* ---- Compute API (ABI 2): game-side pipelines (Stonebreak's libstonebreak_terrain) run their own kernels on the
 * engine's runtime -- one CUDA runtime per process, no CUDA toolkit on either side. Kernels take ONE by-value argument
 * struct (the convention of every engine and csrc kernel). Pointers are device pointers of the runtime's device
 * (unified addressing: host pointers from ci_host_alloc work with ci_memcpy). Streams and events are the driver's
 * CUstream / CUevent handles as void*. Thread-safe. */
typedef struct ci_kernel ci_kernel;

/* Kernel `name` (an NVRTC name expression) of CUDA C++ `source` (module label `label`, used in cache file names):
 * compiled once per runtime (disk-cached like model kernels). NULL on failure. Owned by the runtime. */
ci_kernel* ci_kernel_get(ci_runtime* runtime, const char* label, const char* source, const char* name);
/* Launch with `args_bytes` of argument struct; `smem` > 48 KiB opts the kernel in automatically. */
int32_t ci_launch(ci_runtime* runtime, ci_kernel* kernel, const uint32_t grid[3], uint32_t block, uint32_t smem,
                  const void* args, size_t args_bytes, void* stream);

void* ci_malloc(ci_runtime* runtime, size_t bytes);      /* device memory, NULL on failure */
void ci_free(ci_runtime* runtime, void* ptr);
void* ci_host_alloc(ci_runtime* runtime, size_t bytes);  /* page-locked host memory */
void ci_host_free(ci_runtime* runtime, void* ptr);
int32_t ci_memcpy(ci_runtime* runtime, void* dst, const void* src, size_t bytes, void* stream); /* async on stream */
int32_t ci_memset(ci_runtime* runtime, void* dst, int32_t byte, size_t bytes, void* stream);

void* ci_stream_create(ci_runtime* runtime);             /* non-blocking stream */
void ci_stream_destroy(ci_runtime* runtime, void* stream);
int32_t ci_stream_sync(ci_runtime* runtime, void* stream);
void* ci_event_create(ci_runtime* runtime);
void ci_event_destroy(ci_runtime* runtime, void* event);
int32_t ci_event_record(ci_runtime* runtime, void* event, void* stream);
int32_t ci_stream_wait(ci_runtime* runtime, void* stream, void* event);
int32_t ci_event_sync(ci_runtime* runtime, void* event);

#ifdef __cplusplus
}
#endif
