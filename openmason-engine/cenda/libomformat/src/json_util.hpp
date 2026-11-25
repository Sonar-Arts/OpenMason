// libomformat internals: RAII for yyjson documents and the strings it allocates.
#pragma once

#include <yyjson.h>

#include <cstdlib>
#include <memory>

namespace cenda::omformat::json {

struct DocDeleter {
    void operator()(yyjson_doc* d) const { yyjson_doc_free(d); }
};
using DocPtr = std::unique_ptr<yyjson_doc, DocDeleter>;

struct MutDocDeleter {
    void operator()(yyjson_mut_doc* d) const { yyjson_mut_doc_free(d); }
};
using MutDocPtr = std::unique_ptr<yyjson_mut_doc, MutDocDeleter>;

struct FreeDeleter {
    void operator()(char* p) const { std::free(p); }
};
using CharPtr = std::unique_ptr<char, FreeDeleter>;

}  // namespace cenda::omformat::json
