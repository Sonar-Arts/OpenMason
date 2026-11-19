// Inference engine internals shared by the runtime (runtime.cpp: driver, NVRTC, kernel cache) and the model /
// request code (engine.cpp).
#pragma once

#include "cuda_api.hpp"
#include "json.hpp"

#include <cstdint>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace cenda::infer {

void set_error(const std::string& msg);

struct CudaError : std::runtime_error {
    using std::runtime_error::runtime_error;
};
struct CompileError : std::runtime_error {
    using std::runtime_error::runtime_error;
};
struct FormatError : std::runtime_error {
    using std::runtime_error::runtime_error;
};
struct UnsupportedError : std::runtime_error {
    using std::runtime_error::runtime_error;
};

void check(const CudaApi* api, CUresult r, const char* what);

struct Runtime {
    const CudaApi* cu = nullptr;
    const NvrtcApi* nv = nullptr;
    std::string nvrtc_path;
    int nvrtc_major = 0, nvrtc_minor = 0;
    CUdevice dev = 0;
    int device_index = 0;
    CUcontext ctx = nullptr;
    int cc_major = 0, cc_minor = 0, sm_count = 0;
    std::string device_name;
    std::string cache_dir;
    int compile_threads = 0;

    // Compiled kernels by (source, name): shared by every model of the runtime.
    std::mutex fn_mutex;
    std::unordered_map<std::string, CUfunction> functions;
    std::vector<CUmodule> modules;

    // Kernel `name` (a name expression, e.g. a template instantiation) of `source` (module label `label`):
    // compiled by NVRTC for this device, or read from the disk cache. Thread-safe.
    CUfunction function(const std::string& label, const std::string& source, const std::string& name);
};

// RAII: the runtime's primary context is current on this thread.
class CtxScope {
public:
    explicit CtxScope(const Runtime& rt);
    ~CtxScope();
    CtxScope(const CtxScope&) = delete;
    CtxScope& operator=(const CtxScope&) = delete;

private:
    const CudaApi* api_;
};

// A kernel's by-value argument struct, as the model file describes it (names, types, byte offsets).
enum class FieldType : uint8_t { Ptr, I32, I64, F32 };
struct ArgLayout {
    struct Field {
        std::string name;
        FieldType type;
        size_t offset;
    };
    std::vector<Field> fields;
    size_t size = 0;
    int index(const std::string& name) const; // -1 when absent
};

// A pointer field's target: null, a model tensor (weights) or an activation buffer, + byte offset.
struct Reloc {
    uint8_t kind = 0; // 0 null, 1 tensor, 2 buffer
    int32_t index = 0;
    int64_t offset = 0;
};

struct OpField {
    int64_t i = 0;
    float f = 0.0f;
    Reloc r;
};

// One kernel launch of a plan, as recorded at batch 1, and how it follows the batch size.
struct Op {
    const ArgLayout* layout = nullptr;
    CUfunction fn = nullptr;
    unsigned grid[3] = {1, 1, 1};
    unsigned block = 1;
    unsigned smem = 0;
    enum class Grid : uint8_t { Fixed, Linear, Ceil } grid_rule = Grid::Fixed;
    int scale_field = -1;            // field multiplied by the batch (M, B, P, ...); -1: none
    int grid_dim = 0;                // the grid dimension that follows the batch
    int64_t tile = 1, tiles = 1, mul = 1; // Ceil: grid[dim] = ceil(field_B * mul / tile) * tiles
    uint32_t when = 0;               // runs only when every bit is active
    uint32_t drop_unless = 0;        // `drop_ptr` / `drop_bit` are cleared when any of these bits is active
    int drop_ptr = -1, drop_flags = -1;
    uint32_t drop_bit = 0;
    std::vector<OpField> fields;
};

} // namespace cenda::infer
