#pragma once

// Editing an .omanimgraph (OpenMason #164): the data-model side of the animation graph editor and its MCP tools.
// Edits are a JSON array of OPERATIONS applied to a Graph in order (the caller makes the batch atomic); the graph
// may be a DRAFT (omanimgraph::read_draft) while it is edited. Problems lists everything wrong at once (the strict
// validator's first message is added when the structural checks find nothing); view is the editor's canvas
// model as JSON; layout places nodes and states. Editor data (node and state positions, preview settings) lives
// in the graph's top-level "editor" section, which the runtime keeps verbatim and ignores:
//   editor  { nodes: {<id>: [x, y]}, states: {<machine id>: {<state>: [x, y]}}, preview: {...} }
//
// Operations ({op, ...}; references to nodes by id, params / slots / masks / states / targets by name):
//   graph       name?, description?, profile?, root?, notifyThreshold?
//   param       name, type?, default?, rename?, remove?           (rename rewrites every reference)
//   slot        name, rename?, remove?
//   mask        name, entries?, rename?, remove?
//   node        id, type? (required for a new node), any node member (replaces it), rename?, remove?
//               (remove also unlinks it everywhere; rename rewrites every reference)
//   link        parent, child, at: base | source | sample:<i> | layer:<i> | state:<name> | append
//               (append: a new blend sample after the last, a new override layer, or a new state named after
//               the child)
//   unlink      parent, at                                          (a sample / layer / state goes; base / source clear)
//   state       machine, name, node?, conduit?, entry?: true, rename?, remove?
//   transition  machine, index? (an existing one; omitted = new), from?, to?, conditions?, priority?, blend?,
//               mode?, exitTime?, canInterrupt?, remove?
//   sample      node, index, child?, value? | x?, y?
//   section     name: targets | colliders | modifiers | masks | morphs (#171), value (the whole section)
//   position    node, x, y  |  machine, state, x, y
//   layout      onlyMissing?: true
//   editor      key, value (an editor-section member, e.g. "preview"; null removes)

#include "omformat/omanimgraph.hpp"

#include <string>
#include <string_view>
#include <vector>

namespace cenda::omformat::omanimgraph::edit {

// Applies the operations in order (throws format_error "ops[<i>] (<op>): ..." on the first failure, leaving the
// graph partly edited: the caller restores it). Returns one line per operation.
std::vector<std::string> apply(Graph& graph, std::string_view ops_json);

enum class Severity : int { Info = 0, Warning = 1, Error = 2 };

struct Problem {
    Severity severity = Severity::Error;
    std::string node;     // the node it concerns, "" = the graph
    std::string subject;  // a member path ("samples[1].node", "transitions[2]"), "" = the node / graph
    std::string message;
};

// Everything wrong with the graph (a draft or not). No error-level problem == omanimgraph::validate passes.
std::vector<Problem> problems(const Graph& graph);

// The editor's model as JSON: root, nodes [{id, type, title, x, y, inputs: [{at, label, child}], errors, warnings}],
// machines {<id>: {entry, states: [{name, node, conduit, x, y}], transitions: [{index, from, to, label}]}},
// parameters, slots, masks, targets, problems.
std::string view(const Graph& graph, const std::vector<Problem>& problems);

// Places nodes (layered right to left from the root, children ordered by their parents) and each machine's states
// (in a circle); only_missing keeps positions that exist. Returns the number placed.
int layout(Graph& graph, bool only_missing);

}  // namespace cenda::omformat::omanimgraph::edit
