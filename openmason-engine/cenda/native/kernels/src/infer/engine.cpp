// Inference engine: model files, execution contexts, CUDA graphs, requests, and the ci_* C ABI.
//
// Program kinds (the model's "io"): flow (Euler sampler: cond / noise / expert gates -> x), forward (NCHW fp32 ->
// an NHWC fp16 stem, NHWC fp16 output -> NCHW fp32), tensor (fp32 tensors copied in and out as they are).
#include "cenda/infer.h"

#include "engine_internal.hpp"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <map>
#include <memory>
#include <sstream>
#include <thread>

namespace cenda::infer {

const char* last_error_cstr();

namespace {

// ---------------------------------------------------------------------------------------------- built-in kernels
const char kBuiltinSource[] =
#include "ci_builtin.cu.inc"
    ;

constexpr size_t kAlign = 256;
size_t align_up(size_t v, size_t a = kAlign) { return (v + a - 1) / a * a; }

// ---------------------------------------------------------------------------------------------- model file
constexpr char kMagic[8] = {'C', 'E', 'N', 'D', 'A', 'I', 'N', 'F'};
constexpr uint32_t kFormat = 1;

struct FileImage {
    Json manifest;
    std::string data; // whole file
    size_t blob_base = 0;

    // Overflow-safe range checks (review-native F10): offsets / sizes come from the file.
    void check_range(int64_t off, int64_t n) const {
        if (off < 0 || n < 0 || blob_base > data.size()) throw FormatError("blob out of range");
        const uint64_t avail = data.size() - blob_base;
        if (static_cast<uint64_t>(off) > avail || static_cast<uint64_t>(n) > avail - static_cast<uint64_t>(off))
            throw FormatError("blob out of range");
    }
    std::string blob(const Json& ref) const {
        const int64_t off = ref["offset"].i64(), n = ref["nbytes"].i64();
        check_range(off, n);
        return data.substr(blob_base + static_cast<uint64_t>(off), static_cast<uint64_t>(n));
    }
    const char* blob_ptr(uint64_t off, uint64_t n) const {
        if (off > static_cast<uint64_t>(INT64_MAX) || n > static_cast<uint64_t>(INT64_MAX))
            throw FormatError("blob out of range");
        check_range(static_cast<int64_t>(off), static_cast<int64_t>(n));
        return data.data() + blob_base + off;
    }
};

FileImage read_model_file(const std::string& path) {
    FileImage img;
    std::ifstream f(path, std::ios::binary);
    if (!f) throw std::ios_base::failure("cannot open " + path);
    std::ostringstream ss;
    ss << f.rdbuf();
    img.data = ss.str();
    if (img.data.size() < 24 || std::memcmp(img.data.data(), kMagic, 8) != 0) throw FormatError("not a .cim model");
    uint32_t ver = 0;
    uint64_t mlen = 0;
    std::memcpy(&ver, img.data.data() + 8, 4);
    std::memcpy(&mlen, img.data.data() + 16, 8);
    if (ver != kFormat) throw FormatError("model format " + std::to_string(ver) + " (engine reads " +
                                          std::to_string(kFormat) + ")");
    if (mlen > img.data.size() - 24) throw FormatError("truncated manifest");
    img.manifest = Json::parse(img.data.substr(24, mlen));
    img.blob_base = align_up(24 + mlen);
    return img;
}

// ---------------------------------------------------------------------------------------------- contexts
struct Launch {
    CUfunction fn;
    unsigned grid[3];
    unsigned block, smem;
    std::vector<uint8_t> args;
};

struct Context {
    std::mutex m;
    CUstream stream = nullptr;
    CUevent ev_in = nullptr, ev_out = nullptr;
    CUdeviceptr arena = 0;
    size_t arena_bytes = 0;
    std::vector<CUdeviceptr> buf;
    CUdeviceptr wmask = 0;
    uint32_t* host_mask = nullptr;
    CUdeviceptr stage = 0; // host-memory requests: inputs + output
    size_t stage_bytes = 0;
    std::map<std::pair<uint32_t, int>, CUgraphExec> graphs;
    std::map<std::pair<uint32_t, int>, std::vector<Launch>> plans;
};

struct GateIo {
    uint32_t bit;
    int channel, buffer, factor;
};

struct Io {
    std::string kind; // flow | forward
    int H = 0, W = 0;
    int n_cond = 0;            // flow: conditioning channels; forward: input channels
    int stem_buffer = -1, stem_c = 0, stem_x0 = 0, stem_cond = 1;
    int xs_buffer = -1;
    float x0_scale = 1.0f;
    bool apply_scale = false;
    int gate_channels = 0;
    std::vector<GateIo> gates;
    int out_buffer = -1, out_channels = 1, out_stride = 1;
    std::string out_kind; // f32 | nhwc_f16
};

} // namespace

struct Model {
    Runtime* rt = nullptr;
    std::string name, model_id, info;
    int max_batch = 1;
    bool use_graphs = true;
    CUdeviceptr weights = 0;
    std::vector<CUdeviceptr> tensor_ptr;
    std::map<std::string, ArgLayout> layouts;
    std::vector<Op> ops;
    std::vector<size_t> buffer_bytes; // per window, aligned
    std::vector<Json> buffer_fill;
    std::vector<std::string> styles;
    uint32_t all_mask = 0;
    Io io;
    CUfunction k_load_x0 = nullptr, k_load_nchw = nullptr, k_gate_activity = nullptr, k_pool_gate = nullptr,
               k_store_nchw = nullptr;
    std::vector<std::unique_ptr<Context>> ctxs;
    std::atomic<uint32_t> rr{0};
    std::atomic<int64_t> n_requests{0}, n_windows{0}, n_graph_launches{0}, n_eager{0}, n_readbacks{0}, n_graphs{0},
        n_split{0};

    ~Model();
    void load(const std::string& path, const ci_model_options& opt);
    const std::vector<Launch>& plan(Context& c, uint32_t mask, int batch);
    CUgraphExec graph(Context& c, uint32_t mask, int batch);
    void run(const ci_request& req);
    void warmup(const std::vector<uint32_t>& masks, const std::vector<int>& batches);

private:
    void parse_ops(const FileImage& img);
    void parse_io(const Json& io);
    void alloc_context(Context& c);
    void launch(CUstream s, CUfunction fn, unsigned gx, unsigned gy, unsigned bx, void** params);
    uint32_t resolve_mask(Context& c, const ci_request& req, CUdeviceptr gates, bool& per_window);
};

namespace {

FieldType field_type(const std::string& s) {
    if (s == "ptr") return FieldType::Ptr;
    if (s == "i32") return FieldType::I32;
    if (s == "i64") return FieldType::I64;
    if (s == "f32") return FieldType::F32;
    throw FormatError("unknown field type " + s);
}

size_t field_size(FieldType t) { return (t == FieldType::Ptr || t == FieldType::I64) ? 8 : 4; }

Reloc parse_reloc(const Json& v) {
    Reloc r;
    if (v.is_null()) return r;
    if (v.has("t")) {
        r.kind = 1;
        r.index = v["t"].i32();
    } else if (v.has("b")) {
        r.kind = 2;
        r.index = v["b"].i32();
    } else {
        throw FormatError("bad pointer relocation");
    }
    r.offset = v.i64_or("o", 0);
    return r;
}

} // namespace

Model::~Model() {
    if (rt == nullptr) return;
    try {
        CtxScope scope(*rt);
        const CudaApi* cu = rt->cu;
        for (auto& c : ctxs) {
            if (c->stream != nullptr) cu->cuStreamSynchronize(c->stream);
            for (auto& g : c->graphs) cu->cuGraphExecDestroy(g.second);
            if (c->arena != 0) cu->cuMemFree(c->arena);
            if (c->stage != 0) cu->cuMemFree(c->stage);
            if (c->wmask != 0) cu->cuMemFree(c->wmask);
            if (c->host_mask != nullptr) cu->cuMemFreeHost(c->host_mask);
            if (c->ev_in != nullptr) cu->cuEventDestroy(c->ev_in);
            if (c->ev_out != nullptr) cu->cuEventDestroy(c->ev_out);
            if (c->stream != nullptr) cu->cuStreamDestroy(c->stream);
        }
        if (weights != 0) cu->cuMemFree(weights);
    } catch (...) {
    }
}

void Model::parse_io(const Json& j) {
    io.kind = j["kind"].str();
    io.H = j["H"].i32();
    io.W = j["W"].i32();
    const Json& st = j["stem"];
    io.stem_buffer = st["buffer"].i32();
    io.stem_c = st["channels"].i32();
    io.stem_cond = static_cast<int>(st.i64_or("cond", 1));
    io.stem_x0 = static_cast<int>(st.i64_or("x0", 0));
    const Json& out = j["output"];
    io.out_buffer = out["buffer"].i32();
    io.out_kind = out["kind"].str();
    io.out_channels = static_cast<int>(out.i64_or("channels", 1));
    io.out_stride = static_cast<int>(out.i64_or("stride", io.out_channels));
    if (io.kind == "flow") {
        io.n_cond = j["n_cond"].i32();
        io.xs_buffer = j["xs"].i32();
        io.x0_scale = static_cast<float>(j.num_or("x0_scale", 1.0));
        io.apply_scale = j.has("apply_scale") && j["apply_scale"].boolean();
        io.gate_channels = static_cast<int>(j.i64_or("gate_channels", 0));
        if (j.has("gates"))
            for (const Json& g : j["gates"].items())
                io.gates.push_back({static_cast<uint32_t>(g["bit"].i64()), g["channel"].i32(), g["buffer"].i32(),
                                    g["factor"].i32()});
    } else if (io.kind == "forward" || io.kind == "tensor") {
        io.n_cond = j["n_in"].i32();
    } else {
        throw UnsupportedError("program kind " + io.kind);
    }
    if (io.out_kind != "f32" && io.out_kind != "nhwc_f16") throw UnsupportedError("output kind " + io.out_kind);
}

void Model::parse_ops(const FileImage& img) {
    const Json& m = img.manifest;
    for (const auto& [lname, lj] : m["layouts"].members()) {
        ArgLayout l;
        l.size = static_cast<size_t>(lj["size"].i64());
        for (const Json& f : lj["fields"].items()) {
            ArgLayout::Field fd{f[0].str(), field_type(f[1].str()), static_cast<size_t>(f[2].i64())};
            if (fd.offset + field_size(fd.type) > l.size) throw FormatError("field beyond its struct");
            l.fields.push_back(fd);
        }
        layouts[lname] = std::move(l);
    }
    std::map<std::string, std::string> sources;
    for (const auto& [sname, sj] : m["sources"].members()) sources[sname] = img.blob(sj);

    // Compile every distinct kernel in parallel (NVRTC is thread-safe; compiles dominate a cold load).
    std::vector<std::pair<std::string, std::string>> kernels;
    for (const Json& o : m["ops"].items()) kernels.emplace_back(o["src"].str(), o["name"].str());
    std::sort(kernels.begin(), kernels.end());
    kernels.erase(std::unique(kernels.begin(), kernels.end()), kernels.end());
    std::map<std::pair<std::string, std::string>, CUfunction> fns;
    std::mutex mu;
    std::string first_error;
    std::atomic<size_t> next{0};
    auto worker = [&]() {
        for (size_t i = next++; i < kernels.size(); i = next++) {
            try {
                auto it = sources.find(kernels[i].first);
                if (it == sources.end()) throw FormatError("missing source " + kernels[i].first);
                CUfunction fn = rt->function(kernels[i].first, it->second, kernels[i].second);
                std::lock_guard<std::mutex> lock(mu);
                fns[kernels[i]] = fn;
            } catch (const std::exception& e) {
                std::lock_guard<std::mutex> lock(mu);
                if (first_error.empty()) first_error = e.what();
            }
        }
    };
    unsigned nt = rt->compile_threads > 0 ? static_cast<unsigned>(rt->compile_threads)
                                          : std::max(1u, std::thread::hardware_concurrency());
    nt = std::min<unsigned>(nt, static_cast<unsigned>(std::max<size_t>(1, kernels.size())));
    std::vector<std::thread> pool;
    for (unsigned t = 0; t < nt; ++t) pool.emplace_back(worker);
    for (auto& t : pool) t.join();
    if (!first_error.empty()) throw CompileError(first_error);

    for (const Json& o : m["ops"].items()) {
        Op op;
        auto lit = layouts.find(o["layout"].str());
        if (lit == layouts.end()) throw FormatError("unknown layout " + o["layout"].str());
        op.layout = &lit->second;
        op.fn = fns.at({o["src"].str(), o["name"].str()});
        const Json& g = o["grid"];
        for (size_t k = 0; k < 3; ++k) op.grid[k] = static_cast<unsigned>(g[k].i64());
        op.block = static_cast<unsigned>(o["block"].i64());
        op.smem = static_cast<unsigned>(o.i64_or("smem", 0));
        op.when = static_cast<uint32_t>(o.i64_or("when", 0));
        if (o.has("scale")) {
            const Json& s = o["scale"];
            if (s.has("field")) {
                op.scale_field = op.layout->index(s["field"].str());
                if (op.scale_field < 0) throw FormatError("scale field " + s["field"].str());
            }
            op.grid_dim = static_cast<int>(s.i64_or("dim", 0));
            if (op.grid_dim < 0 || op.grid_dim > 2) throw FormatError("scale dim");
            op.mul = s.i64_or("mul", 1);
            std::string rule = s["rule"].str();
            if (rule == "linear") {
                op.grid_rule = Op::Grid::Linear;
            } else if (rule == "ceil") {
                if (op.scale_field < 0) throw FormatError("ceil scale rule without a field");
                op.grid_rule = Op::Grid::Ceil;
                op.tile = s["tile"].i64();
                op.tiles = s["tiles"].i64();
            } else {
                throw FormatError("scale rule " + rule);
            }
        }
        if (o.has("drop")) {
            const Json& d = o["drop"];
            op.drop_unless = static_cast<uint32_t>(d["unless"].i64());
            op.drop_ptr = op.layout->index(d["ptr"].str());
            op.drop_flags = op.layout->index(d["flags"].str());
            op.drop_bit = static_cast<uint32_t>(d["bit"].i64());
            if (op.drop_ptr < 0 || op.drop_flags < 0) throw FormatError("bad drop rule");
        }
        op.fields.resize(op.layout->fields.size());
        for (const auto& [fname, fv] : o["args"].members()) {
            int fi = op.layout->index(fname);
            if (fi < 0) throw FormatError("unknown field " + fname);
            OpField& f = op.fields[static_cast<size_t>(fi)];
            switch (op.layout->fields[static_cast<size_t>(fi)].type) {
            case FieldType::Ptr: f.r = parse_reloc(fv); break;
            case FieldType::I32:
            case FieldType::I64: f.i = fv.i64(); break;
            case FieldType::F32: f.f = static_cast<float>(fv.num()); break;
            }
            if (f.r.kind == 1 && static_cast<size_t>(f.r.index) >= tensor_ptr.size()) throw FormatError("bad tensor");
            if (f.r.kind == 2 && static_cast<size_t>(f.r.index) >= buffer_bytes.size())
                throw FormatError("bad buffer");
        }
        if (op.smem > 48 * 1024)
            check(rt->cu, rt->cu->cuFuncSetAttribute(op.fn, CU_FUNC_ATTRIBUTE_MAX_DYNAMIC_SHARED_SIZE_BYTES,
                                                     static_cast<int>(op.smem)),
                  "cuFuncSetAttribute");
        ops.push_back(std::move(op));
    }
}

void Model::alloc_context(Context& c) {
    const CudaApi* cu = rt->cu;
    check(cu, cu->cuStreamCreate(&c.stream, CU_STREAM_NON_BLOCKING), "cuStreamCreate");
    check(cu, cu->cuEventCreate(&c.ev_in, CU_EVENT_DISABLE_TIMING), "cuEventCreate");
    check(cu, cu->cuEventCreate(&c.ev_out, CU_EVENT_DISABLE_TIMING), "cuEventCreate");
    size_t total = 0;
    std::vector<size_t> offs;
    for (size_t b : buffer_bytes) {
        offs.push_back(total);
        total += align_up(b * static_cast<size_t>(max_batch));
    }
    c.arena_bytes = total;
    check(cu, cu->cuMemAlloc(&c.arena, std::max<size_t>(total, kAlign)), "cuMemAlloc(buffers)");
    check(cu, cu->cuMemsetD8Async(c.arena, 0, std::max<size_t>(total, kAlign), c.stream), "cuMemset");
    for (size_t i = 0; i < buffer_bytes.size(); ++i) c.buf.push_back(c.arena + offs[i]);
    // Constant channels (e.g. a stem's ones channel): filled once, never written by the plan.
    check(cu, cu->cuStreamSynchronize(c.stream), "sync");
    for (size_t i = 0; i < buffer_fill.size(); ++i) {
        const Json& fills = buffer_fill[i];
        if (fills.is_null() || fills.size() == 0) continue;
        std::vector<uint8_t> host(buffer_bytes[i] * static_cast<size_t>(max_batch), 0); // other values: 0
        for (const Json& f : fills.items()) {
            size_t stride = static_cast<size_t>(f["stride"].i64()), at = static_cast<size_t>(f["at"].i64());
            size_t esz = f["dtype"].str() == "f16" ? 2 : 4;
            if (stride == 0 || at >= stride) throw FormatError("bad buffer fill");
            size_t n = host.size() / (esz * stride);
            uint32_t bits = static_cast<uint32_t>(f["bits"].i64());
            for (size_t k = 0; k < n; ++k) std::memcpy(host.data() + (k * stride + at) * esz, &bits, esz);
        }
        check(cu, cu->cuMemcpyHtoD(c.buf[i], host.data(), host.size()), "cuMemcpyHtoD(fill)");
    }
    check(cu, cu->cuMemAlloc(&c.wmask, static_cast<size_t>(max_batch) * 4 + 4), "cuMemAlloc(mask)");
    check(cu, cu->cuMemAllocHost(reinterpret_cast<void**>(&c.host_mask), static_cast<size_t>(max_batch) * 4 + 4),
          "cuMemAllocHost");
    size_t hw = static_cast<size_t>(io.H) * static_cast<size_t>(io.W) * static_cast<size_t>(max_batch);
    c.stage_bytes = align_up(hw * 4 * static_cast<size_t>(io.n_cond)) + align_up(hw * 4) +
                    align_up(hw * 4 * static_cast<size_t>(std::max(1, io.gate_channels))) +
                    align_up(hw * 4 * static_cast<size_t>(io.out_channels));
    check(cu, cu->cuStreamSynchronize(c.stream), "sync");
}

void Model::load(const std::string& path, const ci_model_options& opt) {
    FileImage img = read_model_file(path);
    const Json& m = img.manifest;
    name = m.str_or("name", "");
    model_id = m.str_or("model_id", "");
    max_batch = std::max(1, opt.max_batch);
    use_graphs = (opt.flags & CI_MODEL_NO_GRAPHS) == 0;
    int n_ctx = std::max(1, opt.contexts);
    if (m.has("styles"))
        for (const Json& s : m["styles"].items()) styles.push_back(s.str());
    if (styles.size() > 32) throw UnsupportedError("more than 32 styles");
    all_mask = styles.empty() ? 0u : (styles.size() == 32 ? 0xFFFFFFFFu : ((1u << styles.size()) - 1u));

    CtxScope scope(*rt);
    const CudaApi* cu = rt->cu;
    // Weights: one allocation, every tensor 256-byte aligned.
    std::vector<size_t> toff;
    size_t wtotal = 0;
    const auto& tensors = m["tensors"].items();
    for (const Json& t : tensors) {
        img.check_range(t["offset"].i64(), t["nbytes"].i64());   // every size below is inside the file
        toff.push_back(wtotal);
        wtotal += align_up(static_cast<size_t>(t["nbytes"].i64()));
    }
    check(cu, cu->cuMemAlloc(&weights, std::max<size_t>(wtotal, kAlign)), "cuMemAlloc(weights)");
    {
        std::string host(wtotal, '\0');
        for (size_t i = 0; i < tensors.size(); ++i) {
            uint64_t off = static_cast<uint64_t>(tensors[i]["offset"].i64());
            uint64_t n = static_cast<uint64_t>(tensors[i]["nbytes"].i64());
            std::memcpy(host.data() + toff[i], img.blob_ptr(off, n), n);
        }
        if (wtotal > 0) check(cu, cu->cuMemcpyHtoD(weights, host.data(), wtotal), "cuMemcpyHtoD(weights)");
    }
    for (size_t o : toff) tensor_ptr.push_back(weights + o);
    for (const Json& b : m["buffers"].items()) {
        buffer_bytes.push_back(static_cast<size_t>(b["nbytes"].i64()));
        buffer_fill.push_back(b.has("fill") ? b["fill"] : Json());
    }
    parse_io(m["io"]);
    parse_ops(img);

    std::string src(kBuiltinSource);
    k_load_x0 = rt->function("ci_builtin", src, "ci_load_x0");
    k_load_nchw = rt->function("ci_builtin", src, "ci_load_nchw");
    k_gate_activity = rt->function("ci_builtin", src, "ci_gate_activity");
    k_pool_gate = rt->function("ci_builtin", src, "ci_pool_gate");
    k_store_nchw = rt->function("ci_builtin", src, "ci_store_nchw");

    for (int i = 0; i < n_ctx; ++i) {
        ctxs.push_back(std::make_unique<Context>());
        alloc_context(*ctxs.back());
    }

    size_t per_ctx = ctxs.empty() ? 0 : ctxs[0]->arena_bytes;
    std::ostringstream js;
    js << "{\"name\":" << json_quote(name) << ",\"model_id\":" << json_quote(model_id) << ",\"kind\":"
       << json_quote(io.kind) << ",\"H\":" << io.H << ",\"W\":" << io.W << ",\"n_in\":" << io.n_cond
       << ",\"n_out\":" << io.out_channels << ",\"gate_channels\":" << io.gate_channels << ",\"styles\":[";
    for (size_t i = 0; i < styles.size(); ++i) js << (i ? "," : "") << json_quote(styles[i]);
    js << "],\"ops\":" << ops.size() << ",\"max_batch\":" << max_batch << ",\"contexts\":" << n_ctx
       << ",\"graphs\":" << (use_graphs ? "true" : "false") << ",\"weight_bytes\":" << wtotal
       << ",\"buffer_bytes_per_context\":" << per_ctx << ",\"device\":" << json_quote(rt->device_name)
       << ",\"nvrtc\":" << json_quote(std::to_string(rt->nvrtc_major) + "." + std::to_string(rt->nvrtc_minor))
       << ",\"nvrtc_path\":" << json_quote(rt->nvrtc_path) << "}";
    info = js.str();
}

const std::vector<Launch>& Model::plan(Context& c, uint32_t mask, int batch) {
    auto key = std::make_pair(mask, batch);
    auto it = c.plans.find(key);
    if (it != c.plans.end()) return it->second;
    std::vector<Launch> out;
    for (const Op& op : ops) {
        if ((op.when & mask) != op.when) continue;
        Launch l;
        l.fn = op.fn;
        l.block = op.block;
        l.smem = op.smem;
        l.args.assign(op.layout->size, 0);
        int64_t scaled = 0;
        for (size_t i = 0; i < op.fields.size(); ++i) {
            const auto& fd = op.layout->fields[i];
            const OpField& f = op.fields[i];
            uint8_t* dst = l.args.data() + fd.offset;
            switch (fd.type) {
            case FieldType::Ptr: {
                uint64_t p = 0;
                if (f.r.kind == 1) p = tensor_ptr[static_cast<size_t>(f.r.index)] + static_cast<uint64_t>(f.r.offset);
                if (f.r.kind == 2) p = c.buf[static_cast<size_t>(f.r.index)] + static_cast<uint64_t>(f.r.offset);
                if (static_cast<int>(i) == op.drop_ptr && (mask & op.drop_unless) != 0) p = 0;
                std::memcpy(dst, &p, 8);
                break;
            }
            case FieldType::I32: {
                int64_t v = f.i;
                if (static_cast<int>(i) == op.scale_field) v *= batch, scaled = v;
                if (static_cast<int>(i) == op.drop_flags && (mask & op.drop_unless) != 0) v &= ~int64_t(op.drop_bit);
                int32_t v32 = static_cast<int32_t>(v);
                std::memcpy(dst, &v32, 4);
                break;
            }
            case FieldType::I64: {
                int64_t v = f.i;
                if (static_cast<int>(i) == op.scale_field) v *= batch, scaled = v;
                std::memcpy(dst, &v, 8);
                break;
            }
            case FieldType::F32: std::memcpy(dst, &f.f, 4); break;
            }
        }
        l.grid[0] = op.grid[0];
        l.grid[1] = op.grid[1];
        l.grid[2] = op.grid[2];
        const size_t d = static_cast<size_t>(op.grid_dim);
        if (op.grid_rule == Op::Grid::Linear) l.grid[d] = op.grid[d] * static_cast<unsigned>(batch);
        if (op.grid_rule == Op::Grid::Ceil)
            l.grid[d] = static_cast<unsigned>((scaled * op.mul + op.tile - 1) / op.tile * op.tiles);
        out.push_back(std::move(l));
    }
    return c.plans.emplace(key, std::move(out)).first->second;
}

CUgraphExec Model::graph(Context& c, uint32_t mask, int batch) {
    auto key = std::make_pair(mask, batch);
    auto it = c.graphs.find(key);
    if (it != c.graphs.end()) return it->second;
    const CudaApi* cu = rt->cu;
    const auto& launches = plan(c, mask, batch);
    // Built node by node (no stream capture): independent of whatever other host threads do with CUDA.
    CUgraph g = nullptr;
    check(cu, cu->cuGraphCreate(&g, 0), "cuGraphCreate");
    CUgraphNode prev = nullptr;
    try {
        for (const Launch& l : launches) {
            void* argp = const_cast<uint8_t*>(l.args.data());
            void* params[1] = {argp};
            CudaKernelNodeParams p{};
            p.func = l.fn;
            p.gridDimX = l.grid[0];
            p.gridDimY = l.grid[1];
            p.gridDimZ = l.grid[2];
            p.blockDimX = l.block;
            p.blockDimY = 1;
            p.blockDimZ = 1;
            p.sharedMemBytes = l.smem;
            p.kernelParams = params;
            CUgraphNode node = nullptr;
            check(cu, cu->cuGraphAddKernelNode(&node, g, prev != nullptr ? &prev : nullptr, prev != nullptr ? 1 : 0, &p),
                  "cuGraphAddKernelNode");
            prev = node;
        }
        CUgraphExec exec = nullptr;
        check(cu, cu->cuGraphInstantiateWithFlags(&exec, g, 0), "cuGraphInstantiate");
        cu->cuGraphDestroy(g);
        n_graphs++;
        return c.graphs.emplace(key, exec).first->second;
    } catch (...) {
        cu->cuGraphDestroy(g);
        throw;
    }
}

void Model::launch(CUstream s, CUfunction fn, unsigned gx, unsigned gy, unsigned bx, void** params) {
    check(rt->cu, rt->cu->cuLaunchKernel(fn, gx, gy, 1, bx, 1, 1, 0, s, params, nullptr), "cuLaunchKernel");
}

uint32_t Model::resolve_mask(Context& c, const ci_request& req, CUdeviceptr gates, bool& per_window) {
    per_window = false;
    if (req.flags & CI_RUN_MASK_ALL) return all_mask;
    if (req.flags & CI_RUN_MASK_GIVEN) {
        if (req.mask & ~all_mask) throw UnsupportedError("mask names a style the model does not pack");
        return req.mask;
    }
    if (io.kind != "flow" || io.gates.empty() || gates == 0) return 0;
    // From the gates: which styles are > 0 anywhere in each window (one small readback).
    const CudaApi* cu = rt->cu;
    int B = req.batch;
    check(cu, cu->cuMemsetD8Async(c.wmask, 0, static_cast<size_t>(B) * 4, c.stream), "cuMemset(mask)");
    long long hw = static_cast<long long>(io.H) * io.W;
    std::vector<uint32_t> seen;
    for (const GateIo& g : io.gates) {
        if (std::find(seen.begin(), seen.end(), g.bit) != seen.end()) continue;
        seen.push_back(g.bit);
        CUdeviceptr wm = c.wmask;
        int nch = io.gate_channels, ch = g.channel;
        uint32_t bit = g.bit;
        void* params[] = {&gates, &wm, &nch, &ch, &hw, &bit};
        unsigned blocks = static_cast<unsigned>(std::min<long long>((hw + 255) / 256, 64));
        launch(c.stream, k_gate_activity, blocks, static_cast<unsigned>(B), 256, params);
    }
    check(cu, cu->cuMemcpyDtoHAsync(c.host_mask, c.wmask, static_cast<size_t>(B) * 4, c.stream), "cuMemcpyDtoH(mask)");
    check(cu, cu->cuStreamSynchronize(c.stream), "sync(mask)");
    n_readbacks++;
    uint32_t mask = 0;
    for (int b = 0; b < B; ++b) mask |= c.host_mask[b];
    for (int b = 0; b < B; ++b)
        if (c.host_mask[b] != mask) per_window = true;
    return mask;
}

void Model::run(const ci_request& req) {
    if (req.batch < 1 || req.batch > max_batch) throw std::invalid_argument("batch out of range");
    const bool host = (req.flags & CI_RUN_HOST) != 0;
    if (req.inputs[0] == nullptr || req.outputs[0] == nullptr) throw std::invalid_argument("missing input/output");
    if (io.kind == "flow" && req.inputs[1] == nullptr) throw std::invalid_argument("missing noise");

    // A free context if there is one, else wait for the next in turn.
    size_t n = ctxs.size(), start = rr++ % n;
    std::unique_lock<std::mutex> lock;
    Context* cp = nullptr;
    for (size_t k = 0; k < n && cp == nullptr; ++k) {
        Context& c = *ctxs[(start + k) % n];
        std::unique_lock<std::mutex> l(c.m, std::try_to_lock);
        if (l.owns_lock()) {
            lock = std::move(l);
            cp = &c;
        }
    }
    if (cp == nullptr) {
        cp = ctxs[start].get();
        lock = std::unique_lock<std::mutex>(cp->m);
    }
    Context& c = *cp;
    CtxScope scope(*rt);
    const CudaApi* cu = rt->cu;
    const bool ordered = (req.flags & CI_RUN_STREAM) != 0;
    CUstream caller = static_cast<CUstream>(req.stream);
    if (ordered) {
        check(cu, cu->cuEventRecord(c.ev_in, caller), "cuEventRecord");
        check(cu, cu->cuStreamWaitEvent(c.stream, c.ev_in, 0), "cuStreamWaitEvent");
    }
    const int B = req.batch;
    const long long hw = static_cast<long long>(io.H) * io.W;
    const size_t px = static_cast<size_t>(B) * static_cast<size_t>(hw);

    // Inputs (device pointers; host requests stage through the context's device memory).
    CUdeviceptr in0 = 0, in1 = 0, gates = 0, out = 0;
    if (host) {
        if (c.stage == 0) check(cu, cu->cuMemAlloc(&c.stage, c.stage_bytes), "cuMemAlloc(stage)");
        size_t o = 0;
        auto put = [&](const void* src, size_t bytes) -> CUdeviceptr {
            CUdeviceptr d = c.stage + o;
            o += align_up(bytes);
            if (src != nullptr) check(cu, cu->cuMemcpyHtoDAsync(d, src, bytes, c.stream), "cuMemcpyHtoD(input)");
            return d;
        };
        in0 = put(req.inputs[0], px * 4 * static_cast<size_t>(io.n_cond));
        if (io.kind == "flow") {
            in1 = put(req.inputs[1], px * 4);
            if (req.inputs[2] != nullptr) gates = put(req.inputs[2], px * 4 * static_cast<size_t>(io.gate_channels));
        }
        out = put(nullptr, px * 4 * static_cast<size_t>(io.out_channels));
    } else {
        in0 = reinterpret_cast<CUdeviceptr>(req.inputs[0]);
        in1 = reinterpret_cast<CUdeviceptr>(req.inputs[1]);
        gates = io.kind == "flow" ? reinterpret_cast<CUdeviceptr>(req.inputs[2]) : 0;
        out = reinterpret_cast<CUdeviceptr>(req.outputs[0]);
    }

    bool per_window = false;
    uint32_t mask = resolve_mask(c, req, gates, per_window);
    if (req.out_mask != nullptr) *req.out_mask = mask;

    // Windows [b0, b0 + nb) with expert mask m: load, network, output.
    auto exec = [&](int b0, int nb, uint32_t m) {
        const size_t pxn = static_cast<size_t>(nb) * static_cast<size_t>(hw);
        const size_t w0 = static_cast<size_t>(b0) * static_cast<size_t>(hw) * 4;
        CUdeviceptr i0 = in0 + w0 * static_cast<size_t>(io.n_cond);
        CUdeviceptr i1 = in1 != 0 ? in1 + w0 : 0;
        CUdeviceptr gt = gates != 0 ? gates + w0 * static_cast<size_t>(io.gate_channels) : 0;
        CUdeviceptr o = out + w0 * static_cast<size_t>(io.out_channels);
        CUdeviceptr stem = c.buf[static_cast<size_t>(io.stem_buffer)];
        if (io.kind == "flow") {
            CUdeviceptr xs = c.buf[static_cast<size_t>(io.xs_buffer)];
            long long np = static_cast<long long>(pxn);
            int stem_c = io.stem_c, x0c = io.stem_x0, apply = io.apply_scale ? 1 : 0;
            float scale = io.x0_scale;
            void* p0[] = {&i1, &xs, &stem, &np, &stem_c, &x0c, &scale, &apply};
            launch(c.stream, k_load_x0, static_cast<unsigned>((np + 255) / 256), 1, 256, p0);
            for (const GateIo& g : io.gates) {
                if (!((m >> g.bit) & 1u)) continue;
                CUdeviceptr dst = c.buf[static_cast<size_t>(g.buffer)];
                CUdeviceptr wm = 0; // every window of a run has exactly mask m
                int nch = io.gate_channels, ch = g.channel, H = io.H, W = io.W, f = g.factor, bb = nb;
                uint32_t bit = g.bit;
                void* pg[] = {&gt, &dst, &wm, &nch, &ch, &H, &W, &f, &bit, &bb};
                long long total = static_cast<long long>(nb) * (H / f) * (W / f);
                launch(c.stream, k_pool_gate, static_cast<unsigned>((total + 255) / 256), 1, 256, pg);
            }
        }
        if (io.kind == "tensor") { // the input as it is (B, n_in, H, W) fp32
            check(cu, cu->cuMemcpyDtoDAsync(stem, i0, pxn * 4 * static_cast<size_t>(io.n_cond), c.stream),
                  "cuMemcpyDtoD(input)");
        } else {
            int bb = nb, C = io.n_cond, dst_c = io.stem_c, off = io.stem_cond;
            long long hwl = hw;
            void* p1[] = {&i0, &stem, &bb, &C, &hwl, &dst_c, &off};
            long long total = static_cast<long long>(pxn) * C;
            launch(c.stream, k_load_nchw, static_cast<unsigned>((total + 255) / 256), 1, 256, p1);
        }
        if (use_graphs) {
            CUgraphExec g = graph(c, m, nb);
            check(cu, cu->cuGraphLaunch(g, c.stream), "cuGraphLaunch");
            n_graph_launches++;
        } else {
            for (const Launch& l : plan(c, m, nb)) {
                void* argp = const_cast<uint8_t*>(l.args.data());
                void* params[1] = {argp};
                check(cu, cu->cuLaunchKernel(l.fn, l.grid[0], l.grid[1], l.grid[2], l.block, 1, 1, l.smem, c.stream,
                                             params, nullptr),
                      "cuLaunchKernel(plan)");
                n_eager++;
            }
        }
        CUdeviceptr ob = c.buf[static_cast<size_t>(io.out_buffer)];
        if (io.out_kind == "f32") {
            check(cu, cu->cuMemcpyDtoDAsync(o, ob, pxn * 4 * static_cast<size_t>(io.out_channels), c.stream),
                  "cuMemcpyDtoD(output)");
        } else {
            int bb = nb, C = io.out_channels, sc = io.out_stride;
            long long hwl = hw;
            void* p2[] = {&ob, &o, &bb, &C, &hwl, &sc};
            long long total = static_cast<long long>(pxn) * C;
            launch(c.stream, k_store_nchw, static_cast<unsigned>((total + 255) / 256), 1, 256, p2);
        }
    };
    if (!per_window) {
        exec(0, B, mask);
    } else {
        // Windows with different expert subsets never share a launch: a window's numbers must not depend on the
        // windows it was batched with (an expert gated off by zero is exact for the residual, not for the SiLU the
        // next layer reads). Contiguous runs of equal masks run as sub-batches.
        n_split++;
        for (int b = 0; b < B;) {
            int e = b + 1;
            while (e < B && c.host_mask[e] == c.host_mask[b]) ++e;
            exec(b, e - b, c.host_mask[b]);
            b = e;
        }
    }
    if (host) {
        check(cu, cu->cuMemcpyDtoHAsync(req.outputs[0], out, px * 4 * static_cast<size_t>(io.out_channels), c.stream),
              "cuMemcpyDtoH(output)");
    }
    if (ordered) {
        check(cu, cu->cuEventRecord(c.ev_out, c.stream), "cuEventRecord");
        check(cu, cu->cuStreamWaitEvent(caller, c.ev_out, 0), "cuStreamWaitEvent");
    }
    if (!ordered || host || (req.flags & CI_RUN_SYNC))
        check(cu, cu->cuStreamSynchronize(c.stream), "cuStreamSynchronize");
    n_requests++;
    n_windows += B;
}

void Model::warmup(const std::vector<uint32_t>& masks, const std::vector<int>& batches) {
    CtxScope scope(*rt);
    for (auto& cp : ctxs) {
        std::lock_guard<std::mutex> lock(cp->m);
        for (uint32_t m : masks)
            for (int b : batches) {
                if (b < 1 || b > max_batch) continue;
                if (use_graphs)
                    graph(*cp, m & all_mask, b);
                else
                    plan(*cp, m & all_mask, b);
            }
    }
}

} // namespace cenda::infer

// ================================================================================================ C ABI
using namespace cenda::infer;

struct ci_kernel {
    CUfunction fn;
    std::atomic<int32_t> smem_set{48 * 1024};
};
struct ci_runtime {
    Runtime rt;
    // ci_kernel_get wrappers, one per function, owned by (and freed with) the runtime: a later runtime never inherits
    // a stale wrapper for a recycled CUfunction value (review-native F9).
    std::mutex kernels_mutex;
    std::map<CUfunction, std::unique_ptr<ci_kernel>> kernels;
};
struct ci_model {
    Model m;
};

namespace {

int32_t fail(int32_t code, const std::string& msg) {
    set_error(msg);
    return code;
}

int32_t translate(const std::exception& e) {
    if (dynamic_cast<const CompileError*>(&e)) return fail(CI_ERR_COMPILE, e.what());
    if (dynamic_cast<const FormatError*>(&e)) return fail(CI_ERR_FORMAT, e.what());
    if (dynamic_cast<const UnsupportedError*>(&e)) return fail(CI_ERR_UNSUPPORTED, e.what());
    if (dynamic_cast<const CudaError*>(&e)) return fail(CI_ERR_CUDA, e.what());
    if (dynamic_cast<const std::invalid_argument*>(&e)) return fail(CI_ERR_ARG, e.what());
    if (dynamic_cast<const std::ios_base::failure*>(&e)) return fail(CI_ERR_IO, e.what());
    return fail(CI_ERR_FORMAT, e.what());
}

bool open_device(Runtime& rt, int32_t device, const ci_runtime_options* o) {
    std::string err;
    rt.cu = cuda_api(o != nullptr ? o->cuda_path : nullptr, err);
    if (rt.cu == nullptr) {
        set_error(err);
        return false;
    }
    int count = 0;
    if (rt.cu->cuDeviceGetCount(&count) != 0 || device < 0 || device >= count) {
        set_error("no CUDA device " + std::to_string(device));
        return false;
    }
    check(rt.cu, rt.cu->cuDeviceGet(&rt.dev, device), "cuDeviceGet");
    rt.device_index = device;
    check(rt.cu, rt.cu->cuDeviceGetAttribute(&rt.cc_major, CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR, rt.dev), "attr");
    check(rt.cu, rt.cu->cuDeviceGetAttribute(&rt.cc_minor, CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MINOR, rt.dev), "attr");
    check(rt.cu, rt.cu->cuDeviceGetAttribute(&rt.sm_count, CU_DEVICE_ATTRIBUTE_MULTIPROCESSOR_COUNT, rt.dev), "attr");
    char name[256] = {0};
    rt.cu->cuDeviceGetName(name, sizeof name - 1, rt.dev);
    rt.device_name = name;
    if (rt.cc_major < 8) {
        set_error("device " + rt.device_name + " is below sm_80 (fp16 mma, cp.async)");
        return false;
    }
    std::string opened;
    rt.nv = nvrtc_api(o != nullptr ? o->nvrtc_path : nullptr, err, opened);
    if (rt.nv == nullptr) {
        set_error(err);
        return false;
    }
    rt.nvrtc_path = opened;
    rt.nv->nvrtcVersion(&rt.nvrtc_major, &rt.nvrtc_minor);
    return true;
}

} // namespace

extern "C" {

int32_t ci_abi_version(void) { return CI_ABI_VERSION; }

const char* ci_last_error(void) { return last_error_cstr(); }

int32_t ci_cuda_available(int32_t device) {
    try {
        Runtime rt;
        return open_device(rt, device, nullptr) ? 1 : 0;
    } catch (const std::exception& e) {
        set_error(e.what());
        return 0;
    }
}

ci_runtime* ci_runtime_create(int32_t device, const ci_runtime_options* options) {
    auto r = std::make_unique<ci_runtime>();
    try {
        if (!open_device(r->rt, device, options)) return nullptr;
        check(r->rt.cu, r->rt.cu->cuDevicePrimaryCtxRetain(&r->rt.ctx, r->rt.dev), "cuDevicePrimaryCtxRetain");
        if (options != nullptr && options->cache_dir != nullptr && *options->cache_dir != '\0') {
            std::error_code ec;
            std::filesystem::create_directories(options->cache_dir, ec);
            r->rt.cache_dir = options->cache_dir;
        }
        r->rt.compile_threads = options != nullptr ? options->compile_threads : 0;
        set_error("");
        return r.release();
    } catch (const std::exception& e) {
        translate(e);
        return nullptr;
    }
}

void ci_runtime_destroy(ci_runtime* runtime) {
    if (runtime == nullptr) return;
    try {
        if (runtime->rt.ctx != nullptr) {
            CtxScope scope(runtime->rt);
            for (CUmodule m : runtime->rt.modules) runtime->rt.cu->cuModuleUnload(m);
        }
        if (runtime->rt.ctx != nullptr) runtime->rt.cu->cuDevicePrimaryCtxRelease(runtime->rt.dev);
    } catch (...) {
    }
    delete runtime;
}

ci_model* ci_model_load(ci_runtime* runtime, const char* path, const ci_model_options* options) {
    if (runtime == nullptr || path == nullptr) {
        set_error("ci_model_load: null runtime or path");
        return nullptr;
    }
    ci_model_options opt{};
    opt.max_batch = 1;
    opt.contexts = 1;
    if (options != nullptr) opt = *options;
    auto m = std::make_unique<ci_model>();
    try {
        m->m.rt = &runtime->rt;
        m->m.load(path, opt);
        set_error("");
        return m.release();
    } catch (const std::exception& e) {
        translate(e);
        return nullptr;
    }
}

void ci_model_destroy(ci_model* model) { delete model; }

const char* ci_model_info(const ci_model* model) { return model == nullptr ? "" : model->m.info.c_str(); }

int32_t ci_model_warmup(ci_model* model, const uint32_t* masks, int32_t n_masks, const int32_t* batches,
                        int32_t n_batches) {
    if (model == nullptr) return fail(CI_ERR_ARG, "null model");
    try {
        std::vector<uint32_t> ms;
        std::vector<int> bs;
        if (masks == nullptr) {
            for (uint32_t m = 0; m <= model->m.all_mask && m < 4096; ++m) ms.push_back(m);
        } else {
            ms.assign(masks, masks + std::max(0, n_masks));
        }
        if (batches == nullptr) {
            for (int b = 1; b <= model->m.max_batch; ++b) bs.push_back(b);
        } else {
            bs.assign(batches, batches + std::max(0, n_batches));
        }
        model->m.warmup(ms, bs);
        return CI_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t ci_run(ci_model* model, const ci_request* request) {
    if (model == nullptr || request == nullptr) return fail(CI_ERR_ARG, "null model or request");
    try {
        model->m.run(*request);
        return CI_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

// ---------------------------------------------------------------------------------------------- compute API

ci_kernel* ci_kernel_get(ci_runtime* runtime, const char* label, const char* source, const char* name) {
    if (runtime == nullptr || label == nullptr || source == nullptr || name == nullptr) {
        set_error("ci_kernel_get: null argument");
        return nullptr;
    }
    try {
        CUfunction fn = runtime->rt.function(label, source, name);
        std::lock_guard<std::mutex> lock(runtime->kernels_mutex);
        auto& k = runtime->kernels[fn];
        if (!k) {
            k = std::make_unique<ci_kernel>();
            k->fn = fn;
        }
        return k.get();
    } catch (const std::exception& e) {
        translate(e);
        return nullptr;
    }
}

int32_t ci_launch(ci_runtime* runtime, ci_kernel* kernel, const uint32_t grid[3], uint32_t block, uint32_t smem,
                  const void* args, size_t args_bytes, void* stream) {
    if (runtime == nullptr || kernel == nullptr || grid == nullptr || (args == nullptr && args_bytes > 0))
        return fail(CI_ERR_ARG, "ci_launch: null argument");
    try {
        CtxScope scope(runtime->rt);
        const CudaApi* cu = runtime->rt.cu;
        if (static_cast<int32_t>(smem) > kernel->smem_set.load()) {
            check(cu, cu->cuFuncSetAttribute(kernel->fn, CU_FUNC_ATTRIBUTE_MAX_DYNAMIC_SHARED_SIZE_BYTES,
                                             static_cast<int>(smem)),
                  "cuFuncSetAttribute");
            kernel->smem_set.store(static_cast<int32_t>(smem));
        }
        void* params[1] = {const_cast<void*>(args)};
        check(cu, cu->cuLaunchKernel(kernel->fn, grid[0], grid[1], grid[2], block, 1, 1, smem,
                                     static_cast<CUstream>(stream), args_bytes > 0 ? params : nullptr, nullptr),
              "cuLaunchKernel");
        return CI_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

void* ci_malloc(ci_runtime* runtime, size_t bytes) {
    if (runtime == nullptr) return nullptr;
    try {
        CtxScope scope(runtime->rt);
        CUdeviceptr p = 0;
        check(runtime->rt.cu, runtime->rt.cu->cuMemAlloc(&p, std::max<size_t>(bytes, 1)), "cuMemAlloc");
        return reinterpret_cast<void*>(p);
    } catch (const std::exception& e) {
        translate(e);
        return nullptr;
    }
}

void ci_free(ci_runtime* runtime, void* ptr) {
    if (runtime == nullptr || ptr == nullptr) return;
    try {
        CtxScope scope(runtime->rt);
        runtime->rt.cu->cuMemFree(reinterpret_cast<CUdeviceptr>(ptr));
    } catch (...) {
    }
}

void* ci_host_alloc(ci_runtime* runtime, size_t bytes) {
    if (runtime == nullptr) return nullptr;
    try {
        CtxScope scope(runtime->rt);
        void* p = nullptr;
        check(runtime->rt.cu, runtime->rt.cu->cuMemAllocHost(&p, std::max<size_t>(bytes, 1)), "cuMemAllocHost");
        return p;
    } catch (const std::exception& e) {
        translate(e);
        return nullptr;
    }
}

void ci_host_free(ci_runtime* runtime, void* ptr) {
    if (runtime == nullptr || ptr == nullptr) return;
    try {
        CtxScope scope(runtime->rt);
        runtime->rt.cu->cuMemFreeHost(ptr);
    } catch (...) {
    }
}

int32_t ci_memcpy(ci_runtime* runtime, void* dst, const void* src, size_t bytes, void* stream) {
    if (runtime == nullptr) return fail(CI_ERR_ARG, "ci_memcpy: null runtime");
    if (bytes == 0) return CI_OK;
    try {
        CtxScope scope(runtime->rt);
        check(runtime->rt.cu,
              runtime->rt.cu->cuMemcpyAsync(reinterpret_cast<CUdeviceptr>(dst), reinterpret_cast<CUdeviceptr>(src), bytes,
                                            static_cast<CUstream>(stream)),
              "cuMemcpyAsync");
        return CI_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t ci_memset(ci_runtime* runtime, void* dst, int32_t byte, size_t bytes, void* stream) {
    if (runtime == nullptr) return fail(CI_ERR_ARG, "ci_memset: null runtime");
    if (bytes == 0) return CI_OK;
    try {
        CtxScope scope(runtime->rt);
        check(runtime->rt.cu,
              runtime->rt.cu->cuMemsetD8Async(reinterpret_cast<CUdeviceptr>(dst), static_cast<unsigned char>(byte), bytes,
                                              static_cast<CUstream>(stream)),
              "cuMemsetD8Async");
        return CI_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

void* ci_stream_create(ci_runtime* runtime) {
    if (runtime == nullptr) return nullptr;
    try {
        CtxScope scope(runtime->rt);
        CUstream s = nullptr;
        check(runtime->rt.cu, runtime->rt.cu->cuStreamCreate(&s, CU_STREAM_NON_BLOCKING), "cuStreamCreate");
        return s;
    } catch (const std::exception& e) {
        translate(e);
        return nullptr;
    }
}

void ci_stream_destroy(ci_runtime* runtime, void* stream) {
    if (runtime == nullptr || stream == nullptr) return;
    try {
        CtxScope scope(runtime->rt);
        runtime->rt.cu->cuStreamDestroy(static_cast<CUstream>(stream));
    } catch (...) {
    }
}

int32_t ci_stream_sync(ci_runtime* runtime, void* stream) {
    if (runtime == nullptr) return fail(CI_ERR_ARG, "ci_stream_sync: null runtime");
    try {
        CtxScope scope(runtime->rt);
        check(runtime->rt.cu, runtime->rt.cu->cuStreamSynchronize(static_cast<CUstream>(stream)), "cuStreamSynchronize");
        return CI_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

void* ci_event_create(ci_runtime* runtime) {
    if (runtime == nullptr) return nullptr;
    try {
        CtxScope scope(runtime->rt);
        CUevent e = nullptr;
        check(runtime->rt.cu, runtime->rt.cu->cuEventCreate(&e, CU_EVENT_DISABLE_TIMING), "cuEventCreate");
        return e;
    } catch (const std::exception& ex) {
        translate(ex);
        return nullptr;
    }
}

void ci_event_destroy(ci_runtime* runtime, void* event) {
    if (runtime == nullptr || event == nullptr) return;
    try {
        CtxScope scope(runtime->rt);
        runtime->rt.cu->cuEventDestroy(static_cast<CUevent>(event));
    } catch (...) {
    }
}

int32_t ci_event_record(ci_runtime* runtime, void* event, void* stream) {
    if (runtime == nullptr || event == nullptr) return fail(CI_ERR_ARG, "ci_event_record: null argument");
    try {
        CtxScope scope(runtime->rt);
        check(runtime->rt.cu, runtime->rt.cu->cuEventRecord(static_cast<CUevent>(event), static_cast<CUstream>(stream)),
              "cuEventRecord");
        return CI_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t ci_stream_wait(ci_runtime* runtime, void* stream, void* event) {
    if (runtime == nullptr || event == nullptr) return fail(CI_ERR_ARG, "ci_stream_wait: null argument");
    try {
        CtxScope scope(runtime->rt);
        check(runtime->rt.cu,
              runtime->rt.cu->cuStreamWaitEvent(static_cast<CUstream>(stream), static_cast<CUevent>(event), 0),
              "cuStreamWaitEvent");
        return CI_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t ci_event_sync(ci_runtime* runtime, void* event) {
    if (runtime == nullptr || event == nullptr) return fail(CI_ERR_ARG, "ci_event_sync: null argument");
    try {
        CtxScope scope(runtime->rt);
        check(runtime->rt.cu, runtime->rt.cu->cuEventSynchronize(static_cast<CUevent>(event)), "cuEventSynchronize");
        return CI_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t ci_model_stats(const ci_model* model, double* values, int32_t n) {
    if (model == nullptr || values == nullptr) return 0;
    const Model& m = model->m;
    double v[] = {static_cast<double>(m.n_requests.load()), static_cast<double>(m.n_windows.load()),
                  static_cast<double>(m.n_graph_launches.load()), static_cast<double>(m.n_eager.load()),
                  static_cast<double>(m.n_readbacks.load()), static_cast<double>(m.n_graphs.load()),
                  static_cast<double>(m.n_split.load())};
    int32_t k = std::min<int32_t>(n, static_cast<int32_t>(sizeof v / sizeof v[0]));
    for (int32_t i = 0; i < k; ++i) values[i] = v[i];
    return k;
}

} // extern "C"
