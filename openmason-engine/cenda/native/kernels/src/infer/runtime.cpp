// Inference runtime: CUDA device + primary context, NVRTC kernel compilation with a disk cache.
#include "engine_internal.hpp"

#include <cstdio>
#include <filesystem>
#include <fstream>
#include <random>
#include <sstream>

namespace cenda::infer {

namespace {

thread_local std::string t_error;

uint64_t fnv1a(const std::string& s, uint64_t h) {
    for (char ch : s) {
        h ^= static_cast<uint64_t>(static_cast<unsigned char>(ch));
        h *= 1099511628211ULL;
    }
    return h;
}

std::string hex64(uint64_t v) {
    char b[17];
    std::snprintf(b, sizeof b, "%016llx", static_cast<unsigned long long>(v));
    return b;
}

bool read_file(const std::filesystem::path& p, std::string& out) {
    std::ifstream f(p, std::ios::binary);
    if (!f) return false;
    std::ostringstream ss;
    ss << f.rdbuf();
    out = ss.str();
    return static_cast<bool>(f) || f.eof();
}

// Atomic replace: a concurrent process reads either nothing or the whole file.
void write_file_atomic(const std::filesystem::path& p, const std::string& data) {
    std::error_code ec;
    std::random_device rd;
    auto tmp = p;
    tmp += ".tmp" + std::to_string(rd());
    {
        std::ofstream f(tmp, std::ios::binary);
        if (!f) return;
        f.write(data.data(), static_cast<std::streamsize>(data.size()));
        if (!f) {
            std::filesystem::remove(tmp, ec);
            return;
        }
    }
    std::filesystem::rename(tmp, p, ec);
    if (ec) std::filesystem::remove(tmp, ec);
}

} // namespace

void set_error(const std::string& msg) { t_error = msg; }
const char* last_error_cstr() { return t_error.c_str(); }

void check(const CudaApi* api, CUresult r, const char* what) {
    if (r == 0) return;
    const char* s = nullptr;
    api->cuGetErrorString(r, &s);
    throw CudaError(std::string("CUDA ") + what + ": " + (s != nullptr ? s : std::to_string(r)));
}

CtxScope::CtxScope(const Runtime& rt) : api_(rt.cu) { check(api_, api_->cuCtxPushCurrent(rt.ctx), "cuCtxPushCurrent"); }
CtxScope::~CtxScope() {
    CUcontext c = nullptr;
    api_->cuCtxPopCurrent(&c);
}

int ArgLayout::index(const std::string& name) const {
    for (size_t i = 0; i < fields.size(); ++i)
        if (fields[i].name == name) return static_cast<int>(i);
    return -1;
}

CUfunction Runtime::function(const std::string& label, const std::string& source, const std::string& name) {
    // Architecture: a cubin for the device's exact SM when this NVRTC knows it, else PTX for the newest virtual
    // architecture it knows (the driver JIT-compiles it) -- kernels/native.py's rule.
    int want = cc_major * 10 + cc_minor;
    int n_arch = 0;
    nv->nvrtcGetNumSupportedArchs(&n_arch);
    std::vector<int> archs(static_cast<size_t>(n_arch > 0 ? n_arch : 0));
    if (n_arch > 0) nv->nvrtcGetSupportedArchs(archs.data());
    bool real = false;
    int best = 0;
    for (int a : archs) {
        if (a == want) real = true;
        if (a <= want && a > best) best = a;
    }
    if (!real && best == 0) throw UnsupportedError("NVRTC supports no architecture <= sm_" + std::to_string(want));
    std::string arch = real ? "--gpu-architecture=sm_" + std::to_string(want)
                            : "--gpu-architecture=compute_" + std::to_string(best);
    // Exactly kernels/native.py's options: the same compiler and options give the same machine code.
    std::vector<std::string> opts = {arch, "-std=c++17", "--device-as-default-execution-space"};

    std::string key_src = label + '\0' + name + '\0' + arch + '\0' + std::to_string(nvrtc_major) + "." +
                          std::to_string(nvrtc_minor) + '\0' + source;
    for (const auto& o : opts) key_src += '\0' + o;
    std::string key = hex64(fnv1a(key_src, 1469598103934665603ULL)) + hex64(fnv1a(key_src, 0x9E3779B97F4A7C15ULL));
    {
        std::lock_guard<std::mutex> lock(fn_mutex);
        auto it = functions.find(key);
        if (it != functions.end()) return it->second;
    }

    std::string image, lowered;
    std::filesystem::path bin, meta;
    if (!cache_dir.empty()) {
        bin = std::filesystem::path(cache_dir) / ("ci-" + label + "-" + key + (real ? ".cubin" : ".ptx"));
        meta = std::filesystem::path(cache_dir) / ("ci-" + label + "-" + key + ".name");
        if (!(read_file(bin, image) && read_file(meta, lowered) && !image.empty() && !lowered.empty())) {
            image.clear();
            lowered.clear();
        }
    }
    if (image.empty()) {
        nvrtcProgram prog = nullptr;
        nvrtcResult r = nv->nvrtcCreateProgram(&prog, source.c_str(), (label + ".cu").c_str(), 0, nullptr, nullptr);
        if (r != 0) throw CompileError(std::string("nvrtcCreateProgram: ") + nv->nvrtcGetErrorString(r));
        struct Destroy {
            const NvrtcApi* nv;
            nvrtcProgram* p;
            ~Destroy() { nv->nvrtcDestroyProgram(p); }
        } destroy{nv, &prog};
        r = nv->nvrtcAddNameExpression(prog, name.c_str());
        if (r != 0) throw CompileError(std::string("nvrtcAddNameExpression: ") + nv->nvrtcGetErrorString(r));
        std::vector<const char*> copts;
        for (const auto& o : opts) copts.push_back(o.c_str());
        r = nv->nvrtcCompileProgram(prog, static_cast<int>(copts.size()), copts.data());
        if (r != 0) {
            size_t n = 0;
            nv->nvrtcGetProgramLogSize(prog, &n);
            std::string log(n, '\0');
            if (n > 0) nv->nvrtcGetProgramLog(prog, log.data());
            if (log.size() > 4000) log = log.substr(log.size() - 4000);
            throw CompileError("NVRTC compiling " + name + " (" + label + "): " + nv->nvrtcGetErrorString(r) + "\n" +
                               log);
        }
        const char* low = nullptr;
        r = nv->nvrtcGetLoweredName(prog, name.c_str(), &low);
        if (r != 0 || low == nullptr) throw CompileError("nvrtcGetLoweredName failed for " + name);
        lowered = low;
        size_t size = 0;
        if (real) {
            nv->nvrtcGetCUBINSize(prog, &size);
            image.resize(size);
            nv->nvrtcGetCUBIN(prog, image.data());
        } else {
            nv->nvrtcGetPTXSize(prog, &size);
            image.resize(size);
            nv->nvrtcGetPTX(prog, image.data());
        }
        if (!cache_dir.empty()) {
            write_file_atomic(bin, image);
            write_file_atomic(meta, lowered);
        }
    }
    if (!real && (image.empty() || image.back() != '\0')) image.push_back('\0');

    std::lock_guard<std::mutex> lock(fn_mutex);
    auto it = functions.find(key);
    if (it != functions.end()) return it->second;
    CtxScope scope(*this);
    CUmodule mod = nullptr;
    check(cu, cu->cuModuleLoadData(&mod, image.data()), "cuModuleLoadData");
    modules.push_back(mod);
    CUfunction fn = nullptr;
    check(cu, cu->cuModuleGetFunction(&fn, mod, lowered.c_str()), "cuModuleGetFunction");
    functions[key] = fn;
    return fn;
}

} // namespace cenda::infer
