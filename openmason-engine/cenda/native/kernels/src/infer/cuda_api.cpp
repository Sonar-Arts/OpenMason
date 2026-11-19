#include "cuda_api.hpp"

#include <cstdlib>
#include <map>
#include <memory>
#include <mutex>
#include <vector>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace cenda::infer {
namespace {

void* open_lib(const std::string& name) {
#if defined(_WIN32)
    return reinterpret_cast<void*>(LoadLibraryA(name.c_str()));
#else
    return dlopen(name.c_str(), RTLD_NOW | RTLD_LOCAL);
#endif
}

void* find_sym(void* lib, const char* name) {
#if defined(_WIN32)
    return reinterpret_cast<void*>(GetProcAddress(reinterpret_cast<HMODULE>(lib), name));
#else
    return dlsym(lib, name);
#endif
}

template <typename F>
bool bind(void* lib, F& fn, const char* name, std::string& missing) {
    void* p = find_sym(lib, name);
    if (p == nullptr) {
        missing = name;
        return false;
    }
    fn = reinterpret_cast<F>(p);
    return true;
}

std::mutex g_mutex;
std::unique_ptr<CudaApi> g_cuda;
std::string g_cuda_error;
bool g_cuda_tried = false;
std::map<std::string, std::unique_ptr<NvrtcApi>> g_nvrtc;
std::map<std::string, std::pair<std::string, std::string>> g_nvrtc_meta; // key -> (error, opened path)

} // namespace

const CudaApi* cuda_api(const char* path, std::string& error) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_cuda_tried) {
        error = g_cuda_error;
        return g_cuda.get();
    }
    g_cuda_tried = true;
    std::vector<std::string> cands;
    if (path != nullptr && *path != '\0') {
        cands.emplace_back(path);
    } else {
#if defined(_WIN32)
        cands.emplace_back("nvcuda.dll");
#elif defined(__APPLE__)
        cands.emplace_back("libcuda.dylib");
#else
        cands.emplace_back("libcuda.so.1");
        cands.emplace_back("libcuda.so");
#endif
    }
    void* lib = nullptr;
    for (const auto& c : cands) {
        lib = open_lib(c);
        if (lib != nullptr) break;
    }
    if (lib == nullptr) {
        g_cuda_error = "CUDA driver library not found";
        error = g_cuda_error;
        return nullptr;
    }
    auto api = std::make_unique<CudaApi>();
    std::string m;
    bool ok = bind(lib, api->cuInit, "cuInit", m) && bind(lib, api->cuDriverGetVersion, "cuDriverGetVersion", m) &&
              bind(lib, api->cuDeviceGetCount, "cuDeviceGetCount", m) &&
              bind(lib, api->cuDeviceGet, "cuDeviceGet", m) &&
              bind(lib, api->cuDeviceGetAttribute, "cuDeviceGetAttribute", m) &&
              bind(lib, api->cuDeviceGetName, "cuDeviceGetName", m) &&
              bind(lib, api->cuDevicePrimaryCtxRetain, "cuDevicePrimaryCtxRetain", m) &&
              bind(lib, api->cuDevicePrimaryCtxRelease, "cuDevicePrimaryCtxRelease_v2", m) &&
              bind(lib, api->cuCtxPushCurrent, "cuCtxPushCurrent_v2", m) &&
              bind(lib, api->cuCtxPopCurrent, "cuCtxPopCurrent_v2", m) &&
              bind(lib, api->cuModuleLoadData, "cuModuleLoadData", m) &&
              bind(lib, api->cuModuleUnload, "cuModuleUnload", m) &&
              bind(lib, api->cuModuleGetFunction, "cuModuleGetFunction", m) &&
              bind(lib, api->cuFuncSetAttribute, "cuFuncSetAttribute", m) &&
              bind(lib, api->cuLaunchKernel, "cuLaunchKernel", m) &&
              bind(lib, api->cuMemAlloc, "cuMemAlloc_v2", m) && bind(lib, api->cuMemFree, "cuMemFree_v2", m) &&
              bind(lib, api->cuMemAllocHost, "cuMemAllocHost_v2", m) &&
              bind(lib, api->cuMemFreeHost, "cuMemFreeHost", m) &&
              bind(lib, api->cuMemcpyHtoD, "cuMemcpyHtoD_v2", m) &&
              bind(lib, api->cuMemcpyHtoDAsync, "cuMemcpyHtoDAsync_v2", m) &&
              bind(lib, api->cuMemcpyDtoHAsync, "cuMemcpyDtoHAsync_v2", m) &&
              bind(lib, api->cuMemcpyDtoDAsync, "cuMemcpyDtoDAsync_v2", m) &&
              bind(lib, api->cuMemsetD8Async, "cuMemsetD8Async", m) &&
              bind(lib, api->cuMemcpyAsync, "cuMemcpyAsync", m) &&
              bind(lib, api->cuStreamCreate, "cuStreamCreate", m) &&
              bind(lib, api->cuStreamDestroy, "cuStreamDestroy_v2", m) &&
              bind(lib, api->cuStreamSynchronize, "cuStreamSynchronize", m) &&
              bind(lib, api->cuStreamWaitEvent, "cuStreamWaitEvent", m) &&
              bind(lib, api->cuEventCreate, "cuEventCreate", m) &&
              bind(lib, api->cuEventDestroy, "cuEventDestroy_v2", m) &&
              bind(lib, api->cuEventRecord, "cuEventRecord", m) &&
              bind(lib, api->cuEventSynchronize, "cuEventSynchronize", m) &&
              bind(lib, api->cuEventElapsedTime, "cuEventElapsedTime", m) &&
              bind(lib, api->cuGraphCreate, "cuGraphCreate", m) && bind(lib, api->cuGraphDestroy, "cuGraphDestroy", m) &&
              bind(lib, api->cuGraphAddKernelNode, "cuGraphAddKernelNode_v2", m) &&
              bind(lib, api->cuGraphInstantiateWithFlags, "cuGraphInstantiateWithFlags", m) &&
              bind(lib, api->cuGraphLaunch, "cuGraphLaunch", m) &&
              bind(lib, api->cuGraphExecDestroy, "cuGraphExecDestroy", m) &&
              bind(lib, api->cuGetErrorString, "cuGetErrorString", m);
    if (!ok) {
        g_cuda_error = "CUDA driver too old (missing " + m + ")";
        error = g_cuda_error;
        return nullptr;
    }
    CUresult r = api->cuInit(0);
    if (r != 0) {
        g_cuda_error = "cuInit failed (" + std::to_string(r) + ")";
        error = g_cuda_error;
        return nullptr;
    }
    g_cuda = std::move(api);
    return g_cuda.get();
}

const NvrtcApi* nvrtc_api(const char* path, std::string& error, std::string& opened) {
    std::lock_guard<std::mutex> lock(g_mutex);
    std::string key = path != nullptr ? path : "";
    auto it = g_nvrtc.find(key);
    if (it != g_nvrtc.end() || g_nvrtc_meta.count(key) != 0) {
        error = g_nvrtc_meta[key].first;
        opened = g_nvrtc_meta[key].second;
        return it == g_nvrtc.end() ? nullptr : it->second.get();
    }
    std::vector<std::string> cands;
    if (!key.empty()) {
        cands.push_back(key);
    } else {
        if (const char* env = std::getenv("CENDA_NVRTC"); env != nullptr && *env != '\0') cands.emplace_back(env);
#if defined(_WIN32)
        for (const char* n : {"nvrtc64_130_0.dll", "nvrtc64_120_0.dll"}) cands.emplace_back(n);
#else
        for (const char* n : {"libnvrtc.so.13", "libnvrtc.so.12", "libnvrtc.so"}) cands.emplace_back(n);
#endif
    }
    void* lib = nullptr;
    std::string which;
    for (const auto& c : cands) {
        lib = open_lib(c);
        if (lib != nullptr) {
            which = c;
            break;
        }
    }
    if (lib == nullptr) {
        g_nvrtc_meta[key] = {"NVRTC library not found", ""};
        error = "NVRTC library not found";
        return nullptr;
    }
    auto api = std::make_unique<NvrtcApi>();
    std::string m;
    bool ok = bind(lib, api->nvrtcVersion, "nvrtcVersion", m) &&
              bind(lib, api->nvrtcGetNumSupportedArchs, "nvrtcGetNumSupportedArchs", m) &&
              bind(lib, api->nvrtcGetSupportedArchs, "nvrtcGetSupportedArchs", m) &&
              bind(lib, api->nvrtcCreateProgram, "nvrtcCreateProgram", m) &&
              bind(lib, api->nvrtcDestroyProgram, "nvrtcDestroyProgram", m) &&
              bind(lib, api->nvrtcAddNameExpression, "nvrtcAddNameExpression", m) &&
              bind(lib, api->nvrtcCompileProgram, "nvrtcCompileProgram", m) &&
              bind(lib, api->nvrtcGetLoweredName, "nvrtcGetLoweredName", m) &&
              bind(lib, api->nvrtcGetCUBINSize, "nvrtcGetCUBINSize", m) &&
              bind(lib, api->nvrtcGetCUBIN, "nvrtcGetCUBIN", m) && bind(lib, api->nvrtcGetPTXSize, "nvrtcGetPTXSize", m) &&
              bind(lib, api->nvrtcGetPTX, "nvrtcGetPTX", m) &&
              bind(lib, api->nvrtcGetProgramLogSize, "nvrtcGetProgramLogSize", m) &&
              bind(lib, api->nvrtcGetProgramLog, "nvrtcGetProgramLog", m) &&
              bind(lib, api->nvrtcGetErrorString, "nvrtcGetErrorString", m);
    if (!ok) {
        g_nvrtc_meta[key] = {"NVRTC too old (missing " + m + ")", which};
        error = g_nvrtc_meta[key].first;
        return nullptr;
    }
    g_nvrtc_meta[key] = {"", which};
    opened = which;
    auto* raw = api.get();
    g_nvrtc[key] = std::move(api);
    return raw;
}

} // namespace cenda::infer
