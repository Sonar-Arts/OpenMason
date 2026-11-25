// Cenda animation graph editing (OpenMason #164): the cg_doc C ABI - history (undo / redo / merge), operations,
// problems, the editor view and compilation - over libomformat's omanimgraph_edit. See cenda/anim_graph_edit.h.
#include "cenda/anim_graph_edit.h"

#include "omformat/omanimgraph.hpp"
#include "omformat/omanimgraph_edit.hpp"

#include <cstdio>
#include <string>
#include <vector>

namespace cenda::graph {
void set_error(const std::string& message);
}  // namespace cenda::graph

namespace og = cenda::omformat::omanimgraph;
namespace oe = cenda::omformat::omanimgraph::edit;

struct cg_doc {
    struct Step {
        std::string text;
        std::string label;
        std::string merge_key;
    };
    og::Graph graph;
    std::string text;  // write_draft(graph): the canonical state
    std::vector<Step> steps;
    int32_t cursor = 0;
    int32_t saved = 0;
    bool sealed = true;
    int64_t revision = 0;
    std::string results;
    // Per-revision caches.
    int64_t cached = -1;
    std::string view;
    std::string problems_json;
    bool valid = false;
};

namespace {

constexpr int32_t kMaxSteps = 200;

int32_t fail(int32_t code, const std::string& message) {
    cenda::graph::set_error(message);
    return code;
}

std::string quote(const std::string& s) {
    std::string out = "\"";
    for (char ch : s) {
        switch (ch) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\t': out += "\\t"; break;
            default:
                if (static_cast<unsigned char>(ch) < 0x20) {
                    char b[8];
                    std::snprintf(b, sizeof b, "\\u%04x", static_cast<unsigned>(static_cast<unsigned char>(ch)));
                    out += b;
                } else {
                    out += ch;
                }
        }
    }
    return out + "\"";
}

void refresh(const cg_doc& cd) {
    auto& d = const_cast<cg_doc&>(cd);
    if (d.cached == d.revision) return;
    const std::vector<oe::Problem> probs = oe::problems(d.graph);
    d.valid = true;
    std::string pj = "[";
    for (size_t i = 0; i < probs.size(); ++i) {
        const oe::Problem& p = probs[i];
        if (p.severity == oe::Severity::Error) d.valid = false;
        static const char* sev[] = {"info", "warning", "error"};
        pj += (i ? "," : "");
        pj += std::string("{\"severity\":\"") + sev[static_cast<int>(p.severity)] + "\",\"node\":" + quote(p.node) +
              ",\"subject\":" + quote(p.subject) + ",\"message\":" + quote(p.message) + "}";
    }
    pj += "]";
    d.problems_json = std::move(pj);
    d.view = oe::view(d.graph, probs);
    d.cached = d.revision;
}

void restore(cg_doc& d, const std::string& text) {
    d.graph = og::read_draft(text);
    d.text = text;
}

}  // namespace

extern "C" {

cg_doc* cg_doc_create(const char* name, const char* profile) {
    try {
        auto* d = new cg_doc();
        if (name != nullptr) d->graph.name = name;
        if (profile != nullptr) d->graph.profile = profile;
        d->text = og::write_draft(d->graph);
        d->steps.push_back({d->text, {}, {}});
        return d;
    } catch (const std::exception& e) {
        fail(CG_ERR_ARG, e.what());
        return nullptr;
    }
}

cg_doc* cg_doc_read(const char* json, int64_t length, int32_t* out_result) {
    if (out_result != nullptr) *out_result = CG_OK;
    if (json == nullptr || length < 0) {
        const int32_t rc = fail(CG_ERR_ARG, "cg_doc_read: null text");
        if (out_result != nullptr) *out_result = rc;
        return nullptr;
    }
    try {
        auto* d = new cg_doc();
        try {
            d->graph = og::read_draft(std::string_view(json, static_cast<size_t>(length)));
            d->text = og::write_draft(d->graph);
            d->steps.push_back({d->text, {}, {}});
        } catch (...) {
            delete d;
            throw;
        }
        return d;
    } catch (const og::version_error& e) {
        const int32_t rc = fail(CG_ERR_VERSION, e.what());
        if (out_result != nullptr) *out_result = rc;
    } catch (const cenda::omformat::format_error& e) {
        const int32_t rc = fail(CG_ERR_FORMAT, e.what());
        if (out_result != nullptr) *out_result = rc;
    } catch (const std::exception& e) {
        const int32_t rc = fail(CG_ERR_ARG, e.what());
        if (out_result != nullptr) *out_result = rc;
    }
    return nullptr;
}

void cg_doc_destroy(cg_doc* d) { delete d; }

const char* cg_doc_json(const cg_doc* d) { return d == nullptr ? "" : d->text.c_str(); }

int32_t cg_doc_apply(cg_doc* d, const char* ops_json, int64_t length, const char* label, const char* merge_key) {
    if (d == nullptr || ops_json == nullptr || length < 0) return fail(CG_ERR_ARG, "cg_doc_apply: null argument");
    try {
        og::Graph next = d->graph;
        const std::vector<std::string> lines = oe::apply(next, std::string_view(ops_json, static_cast<size_t>(length)));
        std::string text = og::write_draft(next);
        std::string results = "[";
        for (size_t i = 0; i < lines.size(); ++i) results += (i ? "," : "") + quote(lines[i]);
        d->results = results + "]";
        if (text == d->text) return static_cast<int32_t>(lines.size());
        const std::string key = merge_key == nullptr ? "" : merge_key;
        const bool merge = !d->sealed && !key.empty() && d->cursor > 0 &&
                           d->cursor == static_cast<int32_t>(d->steps.size()) - 1 &&
                           d->steps[static_cast<size_t>(d->cursor)].merge_key == key;
        d->graph = std::move(next);
        d->text = std::move(text);
        if (merge) {
            d->steps[static_cast<size_t>(d->cursor)].text = d->text;
            if (d->saved == d->cursor) d->saved = -1;
        } else {
            d->steps.resize(static_cast<size_t>(d->cursor) + 1);
            if (d->saved > d->cursor) d->saved = -1;
            d->steps.push_back({d->text, label != nullptr && label[0] != '\0' ? label : "edit graph", key});
            ++d->cursor;
            while (static_cast<int32_t>(d->steps.size()) > kMaxSteps + 1) {
                d->steps.erase(d->steps.begin());
                --d->cursor;
                d->saved = d->saved > 0 ? d->saved - 1 : -1;
            }
            d->sealed = key.empty();
        }
        ++d->revision;
        return static_cast<int32_t>(lines.size());
    } catch (const og::version_error& e) {
        return fail(CG_ERR_FORMAT, e.what());
    } catch (const cenda::omformat::format_error& e) {
        return fail(CG_ERR_FORMAT, e.what());
    } catch (const std::exception& e) {
        return fail(CG_ERR_ARG, e.what());
    }
}

const char* cg_doc_results(const cg_doc* d) { return d == nullptr || d->results.empty() ? "[]" : d->results.c_str(); }

const char* cg_doc_view(const cg_doc* d) {
    if (d == nullptr) return "{}";
    try {
        refresh(*d);
    } catch (const std::exception& e) {
        fail(CG_ERR_ARG, e.what());
        return "{}";
    }
    return d->view.c_str();
}

const char* cg_doc_problems(const cg_doc* d) {
    if (d == nullptr) return "[]";
    try {
        refresh(*d);
    } catch (const std::exception& e) {
        fail(CG_ERR_ARG, e.what());
        return "[]";
    }
    return d->problems_json.c_str();
}

int32_t cg_doc_valid(const cg_doc* d) {
    if (d == nullptr) return 0;
    try {
        refresh(*d);
    } catch (...) {
        return 0;
    }
    return d->valid ? 1 : 0;
}

cg_graph* cg_doc_compile(const cg_doc* d) {
    if (d == nullptr) {
        fail(CG_ERR_ARG, "cg_doc_compile: null document");
        return nullptr;
    }
    int32_t rc = 0;
    return cg_graph_read(d->text.data(), static_cast<int64_t>(d->text.size()), &rc);
}

int32_t cg_doc_undo(cg_doc* d) {
    if (d == nullptr) return fail(CG_ERR_ARG, "cg_doc_undo: null document");
    if (d->cursor <= 0) return fail(CG_ERR_RANGE, "cg_doc_undo: nothing to undo");
    try {
        restore(*d, d->steps[static_cast<size_t>(d->cursor) - 1].text);
    } catch (const std::exception& e) {
        return fail(CG_ERR_ARG, e.what());
    }
    --d->cursor;
    d->sealed = true;
    ++d->revision;
    return CG_OK;
}

int32_t cg_doc_redo(cg_doc* d) {
    if (d == nullptr) return fail(CG_ERR_ARG, "cg_doc_redo: null document");
    if (d->cursor + 1 >= static_cast<int32_t>(d->steps.size())) return fail(CG_ERR_RANGE, "cg_doc_redo: nothing to redo");
    try {
        restore(*d, d->steps[static_cast<size_t>(d->cursor) + 1].text);
    } catch (const std::exception& e) {
        return fail(CG_ERR_ARG, e.what());
    }
    ++d->cursor;
    d->sealed = true;
    ++d->revision;
    return CG_OK;
}

int32_t cg_doc_can_undo(const cg_doc* d) { return d != nullptr && d->cursor > 0 ? 1 : 0; }

int32_t cg_doc_can_redo(const cg_doc* d) {
    return d != nullptr && d->cursor + 1 < static_cast<int32_t>(d->steps.size()) ? 1 : 0;
}

const char* cg_doc_label(const cg_doc* d, int32_t which) {
    if (d == nullptr) return "";
    if (which == 0) return d->cursor > 0 ? d->steps[static_cast<size_t>(d->cursor)].label.c_str() : "";
    if (which == 1 && d->cursor + 1 < static_cast<int32_t>(d->steps.size())) {
        return d->steps[static_cast<size_t>(d->cursor) + 1].label.c_str();
    }
    return "";
}

int32_t cg_doc_seal(cg_doc* d) {
    if (d == nullptr) return fail(CG_ERR_ARG, "cg_doc_seal: null document");
    d->sealed = true;
    return CG_OK;
}

int64_t cg_doc_revision(const cg_doc* d) { return d == nullptr ? 0 : d->revision; }

int32_t cg_doc_dirty(const cg_doc* d) {
    if (d == nullptr) return 0;
    return d->saved == d->cursor ? 0 : 1;
}

int32_t cg_doc_mark_saved(cg_doc* d) {
    if (d == nullptr) return fail(CG_ERR_ARG, "cg_doc_mark_saved: null document");
    d->saved = d->cursor;
    return CG_OK;
}

int32_t cg_doc_step_count(const cg_doc* d) { return d == nullptr ? 0 : static_cast<int32_t>(d->steps.size()) - 1; }

int32_t cg_doc_reset(cg_doc* d) {
    if (d == nullptr) return fail(CG_ERR_ARG, "cg_doc_reset: null document");
    d->steps.clear();
    d->steps.push_back({d->text, {}, {}});
    d->cursor = 0;
    d->saved = 0;
    d->sealed = true;
    ++d->revision;
    return CG_OK;
}

}  // extern "C"
