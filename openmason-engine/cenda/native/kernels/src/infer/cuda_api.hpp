// The CUDA driver API and NVRTC, opened at run time (no CUDA toolkit at build time, no link dependency): the
// handful of declarations the inference engine uses, ABI-compatible with cuda.h / nvrtc.h (CUDA 12+).
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace cenda::infer {

using CUresult = int;
using CUdevice = int;
using CUdeviceptr = unsigned long long;
using CUcontext = struct CUctx_st*;
using CUmodule = struct CUmod_st*;
using CUfunction = struct CUfunc_st*;
using CUkernel = struct CUkern_st*;
using CUstream = struct CUstream_st*;
using CUevent = struct CUevent_st*;
using CUgraph = struct CUgraph_st*;
using CUgraphNode = struct CUgraphNode_st*;
using CUgraphExec = struct CUgraphExec_st*;
using nvrtcProgram = struct _nvrtcProgram*;
using nvrtcResult = int;

struct CudaKernelNodeParams { // CUDA_KERNEL_NODE_PARAMS_v2
    CUfunction func;
    unsigned int gridDimX, gridDimY, gridDimZ;
    unsigned int blockDimX, blockDimY, blockDimZ;
    unsigned int sharedMemBytes;
    void** kernelParams;
    void** extra;
    CUkernel kern;
    CUcontext ctx;
};

constexpr int CU_DEVICE_ATTRIBUTE_MULTIPROCESSOR_COUNT = 16;
constexpr int CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR = 75;
constexpr int CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MINOR = 76;
constexpr int CU_FUNC_ATTRIBUTE_MAX_DYNAMIC_SHARED_SIZE_BYTES = 8;
constexpr unsigned CU_STREAM_NON_BLOCKING = 1;
constexpr unsigned CU_EVENT_DEFAULT = 0;
constexpr unsigned CU_EVENT_DISABLE_TIMING = 2;

// Function table. Every entry is resolved at load; a missing symbol makes the API unavailable.
struct CudaApi {
    CUresult (*cuInit)(unsigned);
    CUresult (*cuDriverGetVersion)(int*);
    CUresult (*cuDeviceGetCount)(int*);
    CUresult (*cuDeviceGet)(CUdevice*, int);
    CUresult (*cuDeviceGetAttribute)(int*, int, CUdevice);
    CUresult (*cuDeviceGetName)(char*, int, CUdevice);
    CUresult (*cuDevicePrimaryCtxRetain)(CUcontext*, CUdevice);
    CUresult (*cuDevicePrimaryCtxRelease)(CUdevice);
    CUresult (*cuCtxPushCurrent)(CUcontext);
    CUresult (*cuCtxPopCurrent)(CUcontext*);
    CUresult (*cuModuleLoadData)(CUmodule*, const void*);
    CUresult (*cuModuleUnload)(CUmodule);
    CUresult (*cuModuleGetFunction)(CUfunction*, CUmodule, const char*);
    CUresult (*cuFuncSetAttribute)(CUfunction, int, int);
    CUresult (*cuLaunchKernel)(CUfunction, unsigned, unsigned, unsigned, unsigned, unsigned, unsigned, unsigned,
                               CUstream, void**, void**);
    CUresult (*cuMemAlloc)(CUdeviceptr*, size_t);
    CUresult (*cuMemFree)(CUdeviceptr);
    CUresult (*cuMemAllocHost)(void**, size_t);
    CUresult (*cuMemFreeHost)(void*);
    CUresult (*cuMemcpyHtoD)(CUdeviceptr, const void*, size_t);
    CUresult (*cuMemcpyHtoDAsync)(CUdeviceptr, const void*, size_t, CUstream);
    CUresult (*cuMemcpyDtoHAsync)(void*, CUdeviceptr, size_t, CUstream);
    CUresult (*cuMemcpyDtoDAsync)(CUdeviceptr, CUdeviceptr, size_t, CUstream);
    CUresult (*cuMemsetD8Async)(CUdeviceptr, unsigned char, size_t, CUstream);
    CUresult (*cuMemcpyAsync)(CUdeviceptr, CUdeviceptr, size_t, CUstream);
    CUresult (*cuStreamCreate)(CUstream*, unsigned);
    CUresult (*cuStreamDestroy)(CUstream);
    CUresult (*cuStreamSynchronize)(CUstream);
    CUresult (*cuStreamWaitEvent)(CUstream, CUevent, unsigned);
    CUresult (*cuEventCreate)(CUevent*, unsigned);
    CUresult (*cuEventDestroy)(CUevent);
    CUresult (*cuEventRecord)(CUevent, CUstream);
    CUresult (*cuEventSynchronize)(CUevent);
    CUresult (*cuEventElapsedTime)(float*, CUevent, CUevent);
    CUresult (*cuGraphCreate)(CUgraph*, unsigned);
    CUresult (*cuGraphDestroy)(CUgraph);
    CUresult (*cuGraphAddKernelNode)(CUgraphNode*, CUgraph, const CUgraphNode*, size_t, const CudaKernelNodeParams*);
    CUresult (*cuGraphInstantiateWithFlags)(CUgraphExec*, CUgraph, unsigned long long);
    CUresult (*cuGraphLaunch)(CUgraphExec, CUstream);
    CUresult (*cuGraphExecDestroy)(CUgraphExec);
    CUresult (*cuGetErrorString)(CUresult, const char**);
};

struct NvrtcApi {
    nvrtcResult (*nvrtcVersion)(int*, int*);
    nvrtcResult (*nvrtcGetNumSupportedArchs)(int*);
    nvrtcResult (*nvrtcGetSupportedArchs)(int*);
    nvrtcResult (*nvrtcCreateProgram)(nvrtcProgram*, const char*, const char*, int, const char* const*,
                                      const char* const*);
    nvrtcResult (*nvrtcDestroyProgram)(nvrtcProgram*);
    nvrtcResult (*nvrtcAddNameExpression)(nvrtcProgram, const char*);
    nvrtcResult (*nvrtcCompileProgram)(nvrtcProgram, int, const char* const*);
    nvrtcResult (*nvrtcGetLoweredName)(nvrtcProgram, const char*, const char**);
    nvrtcResult (*nvrtcGetCUBINSize)(nvrtcProgram, size_t*);
    nvrtcResult (*nvrtcGetCUBIN)(nvrtcProgram, char*);
    nvrtcResult (*nvrtcGetPTXSize)(nvrtcProgram, size_t*);
    nvrtcResult (*nvrtcGetPTX)(nvrtcProgram, char*);
    nvrtcResult (*nvrtcGetProgramLogSize)(nvrtcProgram, size_t*);
    nvrtcResult (*nvrtcGetProgramLog)(nvrtcProgram, char*);
    const char* (*nvrtcGetErrorString)(nvrtcResult);
};

// The driver (process-wide, opened once). nullptr + `error` when unavailable.
const CudaApi* cuda_api(const char* path, std::string& error);
// NVRTC: one per distinct path (the first open of a path wins). nullptr + `error` when unavailable.
const NvrtcApi* nvrtc_api(const char* path, std::string& error, std::string& opened);

} // namespace cenda::infer
