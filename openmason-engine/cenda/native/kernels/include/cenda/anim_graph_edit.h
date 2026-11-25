/* Cenda animation graph editing (OpenMason #164, epic #166): the native, editable model behind Open Mason's
 * Animation Graph workspace and its animgraph_* MCP tools. Part of CG ABI 1 (additions); sources
 * src/graph/anim_graph_edit.cpp over libomformat's omformat/omanimgraph_edit.hpp (which documents every
 * operation); ctest `anim_graph_edit`.
 *
 * A cg_doc holds one .omanimgraph that may be a DRAFT while it is edited (missing members, empty lists, nodes not
 * yet connected). Every change is cg_doc_apply: a JSON array of operations applied in order as ONE undo step,
 * all or nothing (a failing operation leaves the document as it was; cg_last_error names it). Consecutive applies
 * with the same merge key fold into the step on top (a node drag is one step) until cg_doc_seal, another key, an
 * undo or a redo. The document text (cg_doc_json) is the canonical file text: deterministic, so it is also the
 * state undo restores. cg_doc_problems lists everything wrong; with no error-level problem the document compiles
 * (cg_doc_compile) to the same cg_graph cg_graph_read gives. Editor data (node / state positions, preview
 * settings) is the file's "editor" section, kept verbatim by the runtime.
 *
 * Strings returned are owned by the document and valid until its next change. One thread at a time.
 */
#pragma once

#include <stdint.h>

#include "cenda/anim_graph.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct cg_doc cg_doc;

/* An empty document (no nodes) named `name` (may be NULL), for profile `profile` (may be NULL). */
cg_doc* cg_doc_create(const char* name, const char* profile);
/* Reads an .omanimgraph, a draft included (malformed values still fail: NULL, *out_result CG_ERR_FORMAT /
 * CG_ERR_VERSION, cg_last_error). The base state: not dirty. */
cg_doc* cg_doc_read(const char* json, int64_t length, int32_t* out_result);
void cg_doc_destroy(cg_doc* d);

/* The document as .omanimgraph text. */
const char* cg_doc_json(const cg_doc* d);
/* Applies operations (see omanimgraph_edit.hpp) as one undo step labelled `label` (NULL = "edit graph"); merge_key
 * NULL or "" never merges. Returns the number of operations applied, or CG_ERR_FORMAT / CG_ERR_ARG (nothing
 * changed; cg_last_error = "ops[i] (op): why"). An apply that changes nothing records no step. */
int32_t cg_doc_apply(cg_doc* d, const char* ops_json, int64_t length, const char* label, const char* merge_key);
/* JSON array of strings: one line per operation of the last successful apply. */
const char* cg_doc_results(const cg_doc* d);
/* The editor's model as JSON (omanimgraph_edit.hpp view), cached per revision. */
const char* cg_doc_view(const cg_doc* d);
/* JSON array [{severity: error|warning|info, node, subject, message}], cached per revision. */
const char* cg_doc_problems(const cg_doc* d);
/* 1 when there is no error-level problem (cg_doc_compile succeeds). */
int32_t cg_doc_valid(const cg_doc* d);
/* A runtime graph of the current document (the caller destroys it). NULL while it has errors (cg_last_error says
 * the first). */
cg_graph* cg_doc_compile(const cg_doc* d);

/* History (as cenda/character_edit.h's cc_charedit). CG_ERR_RANGE when there is nothing to undo / redo. */
int32_t cg_doc_undo(cg_doc* d);
int32_t cg_doc_redo(cg_doc* d);
int32_t cg_doc_can_undo(const cg_doc* d);
int32_t cg_doc_can_redo(const cg_doc* d);
/* The label of the step undo (which 0) or redo (which 1) would take, "" when none. */
const char* cg_doc_label(const cg_doc* d, int32_t which);
int32_t cg_doc_seal(cg_doc* d);
int64_t cg_doc_revision(const cg_doc* d);
int32_t cg_doc_dirty(const cg_doc* d);
int32_t cg_doc_mark_saved(cg_doc* d);
int32_t cg_doc_step_count(const cg_doc* d);
/* Forgets the history; the current state becomes the base. */
int32_t cg_doc_reset(cg_doc* d);

/* Debug (the editor's debugger, #164): the weight of each input of a node in the last evaluate - blend-space
 * samples, state-machine states (in input order, conduits left out), layers (base first: the base's share, then
 * each layer's weight). Returns the input count (out may be NULL to ask), CG_ERR_ARG for a node without inputs. */
int32_t cg_instance_debug_weights(const cg_instance* inst, int32_t node, float* out, int32_t capacity);

#ifdef __cplusplus
}
#endif
