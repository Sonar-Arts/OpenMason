// .omanimgraph editing (OpenMason #164): operations, problems, the editor view and layout. See
// omformat/omanimgraph_edit.hpp. Member edits go through the format's own JSON (write_draft -> patch ->
// read_draft), so every value is checked by the same parser the runtime uses; references are rewritten on the
// data model.
#include "omformat/omanimgraph_edit.hpp"

#include "json_util.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <map>
#include <numbers>
#include <set>
#include <unordered_map>
#include <unordered_set>

namespace cenda::omformat::omanimgraph::edit {

namespace {

using json::CharPtr;
using json::DocPtr;
using json::MutDocPtr;

[[noreturn]] void fail(const std::string& message) { throw format_error(message); }

std::string str(yyjson_val* v) { return {yyjson_get_str(v), yyjson_get_len(v)}; }

yyjson_val* member(yyjson_val* obj, const char* key) {
    yyjson_val* v = yyjson_obj_get(obj, key);
    return v;
}

std::string text_of(yyjson_val* obj, const char* key, bool required) {
    yyjson_val* v = member(obj, key);
    if (v == nullptr || yyjson_is_null(v)) {
        if (required) fail(std::string("missing \"") + key + "\"");
        return {};
    }
    if (!yyjson_is_str(v)) fail(std::string("\"") + key + "\" must be a string");
    return str(v);
}

bool flag(yyjson_val* obj, const char* key) {
    yyjson_val* v = member(obj, key);
    return v != nullptr && yyjson_is_bool(v) && yyjson_get_bool(v);
}

double number(yyjson_val* obj, const char* key) {
    yyjson_val* v = member(obj, key);
    if (v == nullptr || !yyjson_is_num(v)) fail(std::string("\"") + key + "\" must be a number");
    return yyjson_get_num(v);
}

std::string minified(yyjson_val* v) {
    std::size_t len = 0;
    CharPtr s(yyjson_val_write(v, YYJSON_WRITE_NOFLAG, &len));
    if (!s) fail("out of memory");
    return {s.get(), len};
}

// ---- the graph as a mutable JSON document --------------------------------------------------------------------

struct MutGraph {
    DocPtr src;
    MutDocPtr doc;
    yyjson_mut_val* root = nullptr;

    explicit MutGraph(const Graph& g) {
        const std::string text = write_draft(g);
        src.reset(yyjson_read(text.data(), text.size(), 0));
        if (!src) fail("internal: write_draft gave invalid JSON");
        doc.reset(yyjson_doc_mut_copy(src.get(), nullptr));
        root = yyjson_mut_doc_get_root(doc.get());
    }

    yyjson_mut_val* array(const char* key) {
        yyjson_mut_val* a = yyjson_mut_obj_get(root, key);
        if (a == nullptr) {
            a = yyjson_mut_arr(doc.get());
            yyjson_mut_obj_add(root, yyjson_mut_strcpy(doc.get(), key), a);
        }
        return a;
    }

    // The element of array `key` whose `field` is `value`, or null.
    yyjson_mut_val* find(const char* key, const char* field, const std::string& value) {
        yyjson_mut_val* a = yyjson_mut_obj_get(root, key);
        if (a == nullptr) return nullptr;
        yyjson_mut_arr_iter it = yyjson_mut_arr_iter_with(a);
        for (yyjson_mut_val* e; (e = yyjson_mut_arr_iter_next(&it)) != nullptr;) {
            yyjson_mut_val* f = yyjson_mut_obj_get(e, field);
            if (f != nullptr && yyjson_mut_is_str(f) && value == yyjson_mut_get_str(f)) return e;
        }
        return nullptr;
    }

    // Copies the members of `patch` onto `target` (null removes one), skipping `skip`.
    void overlay(yyjson_mut_val* target, yyjson_val* patch, const std::set<std::string>& skip) {
        yyjson_obj_iter it = yyjson_obj_iter_with(patch);
        for (yyjson_val* k; (k = yyjson_obj_iter_next(&it)) != nullptr;) {
            const std::string key = str(k);
            if (skip.contains(key)) continue;
            yyjson_val* v = yyjson_obj_iter_get_val(k);
            yyjson_mut_obj_remove_key(target, key.c_str());
            if (!yyjson_is_null(v)) {
                yyjson_mut_obj_add(target, yyjson_mut_strncpy(doc.get(), key.data(), key.size()),
                                   yyjson_val_mut_copy(doc.get(), v));
            }
        }
    }

    Graph result() const {
        std::size_t len = 0;
        CharPtr s(yyjson_mut_write(doc.get(), YYJSON_WRITE_NOFLAG, &len));
        if (!s) fail("out of memory");
        return read_draft(std::string_view(s.get(), len));
    }
};

// ---- the editor section -------------------------------------------------------------------------------------

struct Editor {
    std::map<std::string, std::pair<double, double>> nodes;
    std::map<std::string, std::map<std::string, std::pair<double, double>>> states;
    std::map<std::string, std::string> other;  // other members, minified JSON

    static Editor of(const Graph& g) {
        Editor e;
        for (const UnknownSection& u : g.unknown_sections) {
            if (u.key != "editor") continue;
            DocPtr d(yyjson_read(u.json.data(), u.json.size(), 0));
            yyjson_val* root = d ? yyjson_doc_get_root(d.get()) : nullptr;
            if (root == nullptr || !yyjson_is_obj(root)) break;
            yyjson_obj_iter it = yyjson_obj_iter_with(root);
            for (yyjson_val* k; (k = yyjson_obj_iter_next(&it)) != nullptr;) {
                const std::string key = str(k);
                yyjson_val* v = yyjson_obj_iter_get_val(k);
                if (key == "nodes" && yyjson_is_obj(v)) {
                    read_points(v, e.nodes);
                } else if (key == "states" && yyjson_is_obj(v)) {
                    yyjson_obj_iter mi = yyjson_obj_iter_with(v);
                    for (yyjson_val* m; (m = yyjson_obj_iter_next(&mi)) != nullptr;) {
                        yyjson_val* sv = yyjson_obj_iter_get_val(m);
                        if (yyjson_is_obj(sv)) read_points(sv, e.states[str(m)]);
                    }
                } else {
                    e.other[key] = minified(v);
                }
            }
        }
        return e;
    }

    static void read_points(yyjson_val* obj, std::map<std::string, std::pair<double, double>>& out) {
        yyjson_obj_iter it = yyjson_obj_iter_with(obj);
        for (yyjson_val* k; (k = yyjson_obj_iter_next(&it)) != nullptr;) {
            yyjson_val* p = yyjson_obj_iter_get_val(k);
            if (yyjson_is_arr(p) && yyjson_arr_size(p) == 2 && yyjson_is_num(yyjson_arr_get(p, 0)) &&
                yyjson_is_num(yyjson_arr_get(p, 1))) {
                out[str(k)] = {yyjson_get_num(yyjson_arr_get(p, 0)), yyjson_get_num(yyjson_arr_get(p, 1))};
            }
        }
    }

    void store(Graph& g) const {
        MutDocPtr d(yyjson_mut_doc_new(nullptr));
        yyjson_mut_doc* m = d.get();
        yyjson_mut_val* root = yyjson_mut_obj(m);
        auto point = [&](const std::pair<double, double>& p) {
            yyjson_mut_val* a = yyjson_mut_arr(m);
            yyjson_mut_arr_append(a, yyjson_mut_real(m, std::round(p.first * 10.0) / 10.0 + 0.0));
            yyjson_mut_arr_append(a, yyjson_mut_real(m, std::round(p.second * 10.0) / 10.0 + 0.0));
            return a;
        };
        if (!nodes.empty()) {
            yyjson_mut_val* o = yyjson_mut_obj(m);
            for (const auto& [id, p] : nodes) yyjson_mut_obj_add(o, yyjson_mut_strncpy(m, id.data(), id.size()), point(p));
            yyjson_mut_obj_add(root, yyjson_mut_str(m, "nodes"), o);
        }
        if (!states.empty()) {
            yyjson_mut_val* o = yyjson_mut_obj(m);
            for (const auto& [machine, ss] : states) {
                if (ss.empty()) continue;
                yyjson_mut_val* so = yyjson_mut_obj(m);
                for (const auto& [name, p] : ss) yyjson_mut_obj_add(so, yyjson_mut_strncpy(m, name.data(), name.size()), point(p));
                yyjson_mut_obj_add(o, yyjson_mut_strncpy(m, machine.data(), machine.size()), so);
            }
            yyjson_mut_obj_add(root, yyjson_mut_str(m, "states"), o);
        }
        std::vector<DocPtr> keep;
        for (const auto& [key, js] : other) {
            keep.emplace_back(yyjson_read(js.data(), js.size(), 0));
            if (!keep.back()) continue;
            yyjson_mut_obj_add(root, yyjson_mut_strncpy(m, key.data(), key.size()),
                               yyjson_val_mut_copy(m, yyjson_doc_get_root(keep.back().get())));
        }
        std::size_t len = 0;
        CharPtr s(yyjson_mut_val_write(root, YYJSON_WRITE_NOFLAG, &len));
        auto it = std::find_if(g.unknown_sections.begin(), g.unknown_sections.end(),
                               [](const UnknownSection& u) { return u.key == "editor"; });
        const bool empty = nodes.empty() && states.empty() && other.empty();
        if (empty) {
            if (it != g.unknown_sections.end()) g.unknown_sections.erase(it);
            return;
        }
        if (it != g.unknown_sections.end()) {
            it->json.assign(s.get(), len);
        } else {
            g.unknown_sections.push_back({"editor", std::string(s.get(), len)});
        }
    }
};

// ---- lookups and references ----------------------------------------------------------------------------------

Node& node_ref(Graph& g, const std::string& id) {
    const int i = g.find_node(id);
    if (i < 0) fail("no node " + id);
    return g.nodes[static_cast<std::size_t>(i)];
}

Node& machine_ref(Graph& g, const std::string& id) {
    Node& n = node_ref(g, id);
    if (n.type != NodeType::StateMachine) fail("node " + id + " is not a state machine");
    return n;
}

void replace_if(std::string& s, const std::string& from, const std::string& to) {
    if (s == from) s = to;
}

void rename_node(Graph& g, const std::string& from, const std::string& to) {
    if (g.find_node(to) >= 0) fail("a node " + to + " exists");
    replace_if(g.root, from, to);
    for (Node& n : g.nodes) {
        replace_if(n.id, from, to);
        for (Sample& s : n.samples) replace_if(s.node, from, to);
        for (State& s : n.states) replace_if(s.node, from, to);
        replace_if(n.base, from, to);
        for (LayerEntry& l : n.layers) replace_if(l.node, from, to);
        replace_if(n.source, from, to);
    }
    for (Modifier& m : g.modifiers) replace_if(m.node, from, to);
    Editor e = Editor::of(g);
    if (auto it = e.nodes.find(from); it != e.nodes.end()) {
        auto p = it->second;
        e.nodes.erase(it);
        e.nodes[to] = p;
    }
    if (auto it = e.states.find(from); it != e.states.end()) {
        auto p = it->second;
        e.states.erase(it);
        e.states[to] = p;
    }
    e.store(g);
}

void rename_param(Graph& g, const std::string& from, const std::string& to) {
    if (g.find_param(to) >= 0) fail("a parameter " + to + " exists");
    for (Param& p : g.params) replace_if(p.name, from, to);
    for (Node& n : g.nodes) {
        replace_if(n.rate_param, from, to);
        replace_if(n.param, from, to);
        replace_if(n.param_y, from, to);
        for (Transition& t : n.transitions) {
            for (Condition& c : t.conditions) replace_if(c.param, from, to);
        }
        for (LayerEntry& l : n.layers) replace_if(l.weight_param, from, to);
    }
    for (Modifier& m : g.modifiers) {
        replace_if(m.weight_param, from, to);
        replace_if(m.angle_param, from, to);
        replace_if(m.speed_param, from, to);
        replace_if(m.direction_param, from, to);
    }
}

void rename_slot(Graph& g, const std::string& from, const std::string& to) {
    if (g.find_slot(to) >= 0) fail("a slot " + to + " exists");
    for (std::string& s : g.slots) replace_if(s, from, to);
    for (Node& n : g.nodes) {
        if (n.type == NodeType::Clip) replace_if(n.slot, from, to);
    }
}

void rename_mask(Graph& g, const std::string& from, const std::string& to) {
    if (g.find_mask(to) >= 0) fail("a mask " + to + " exists");
    for (Mask& m : g.masks) replace_if(m.name, from, to);
    for (Node& n : g.nodes) {
        if (n.type == NodeType::Slot) replace_if(n.mask, from, to);
        for (LayerEntry& l : n.layers) replace_if(l.mask, from, to);
    }
}

void rename_state(Graph& g, Node& machine, const std::string& from, const std::string& to) {
    for (const State& s : machine.states) {
        if (s.name == to) fail("a state " + to + " exists");
    }
    if (to.empty() || to == "*") fail("bad state name");
    for (State& s : machine.states) replace_if(s.name, from, to);
    replace_if(machine.entry, from, to);
    for (Transition& t : machine.transitions) {
        replace_if(t.from, from, to);
        replace_if(t.to, from, to);
    }
    Editor e = Editor::of(g);
    auto& ss = e.states[machine.id];
    if (auto it = ss.find(from); it != ss.end()) {
        auto p = it->second;
        ss.erase(it);
        ss[to] = p;
    }
    e.store(g);
}

// Removes every use of node `id` (samples, states, layers; base / source cleared; root cleared).
void unlink_everywhere(Graph& g, const std::string& id) {
    if (g.root == id) g.root.clear();
    for (Node& n : g.nodes) {
        std::erase_if(n.samples, [&](const Sample& s) { return s.node == id; });
        for (State& s : n.states) {
            if (s.node == id) s.node.clear();
        }
        std::erase_if(n.layers, [&](const LayerEntry& l) { return l.node == id; });
        if (n.base == id) n.base.clear();
        if (n.source == id) n.source.clear();
    }
}

int index_after(const std::string& at, const char* prefix) {
    const std::size_t n = std::strlen(prefix);
    if (at.compare(0, n, prefix) != 0) return -1;
    try {
        const long v = std::stol(at.substr(n));
        return v < 0 || v > 1000000 ? -2 : static_cast<int>(v);
    } catch (...) {
        return -2;
    }
}

// ---- operations ---------------------------------------------------------------------------------------------

std::string op_graph(Graph& g, yyjson_val* op) {
    MutGraph m(g);
    m.overlay(m.root, op, {"op", "format", "version", "nodes", "parameters", "slots", "masks", "targets", "colliders",
                           "modifiers", "editor"});
    g = m.result();
    return "graph updated";
}

std::string op_param(Graph& g, yyjson_val* op) {
    const std::string name = text_of(op, "name", true);
    const bool exists = g.find_param(name) >= 0;
    if (flag(op, "remove")) {
        if (!exists) fail("no parameter " + name);
        std::erase_if(g.params, [&](const Param& p) { return p.name == name; });
        return "parameter " + name + " removed";
    }
    if (const std::string to = text_of(op, "rename", false); !to.empty()) {
        if (!exists) fail("no parameter " + name);
        rename_param(g, name, to);
        return "parameter " + name + " renamed " + to;
    }
    MutGraph m(g);
    yyjson_mut_val* p = m.find("parameters", "name", name);
    if (p == nullptr) {
        if (member(op, "type") == nullptr) fail("a new parameter needs a type");
        p = yyjson_mut_obj(m.doc.get());
        yyjson_mut_obj_add(p, yyjson_mut_str(m.doc.get(), "name"), yyjson_mut_strncpy(m.doc.get(), name.data(), name.size()));
        yyjson_mut_arr_append(m.array("parameters"), p);
    }
    m.overlay(p, op, {"op", "name", "rename", "remove"});
    g = m.result();
    return "parameter " + name + (exists ? " updated" : " added");
}

std::string op_slot(Graph& g, yyjson_val* op) {
    const std::string name = text_of(op, "name", true);
    const bool exists = g.find_slot(name) >= 0;
    if (flag(op, "remove")) {
        if (!exists) fail("no slot " + name);
        std::erase(g.slots, name);
        return "slot " + name + " removed";
    }
    if (const std::string to = text_of(op, "rename", false); !to.empty()) {
        if (!exists) fail("no slot " + name);
        rename_slot(g, name, to);
        return "slot " + name + " renamed " + to;
    }
    if (exists) return "slot " + name + " exists";
    g.slots.push_back(name);
    return "slot " + name + " added";
}

std::string op_mask(Graph& g, yyjson_val* op) {
    const std::string name = text_of(op, "name", true);
    const bool exists = g.find_mask(name) >= 0;
    if (flag(op, "remove")) {
        if (!exists) fail("no mask " + name);
        std::erase_if(g.masks, [&](const Mask& k) { return k.name == name; });
        return "mask " + name + " removed";
    }
    if (const std::string to = text_of(op, "rename", false); !to.empty()) {
        if (!exists) fail("no mask " + name);
        rename_mask(g, name, to);
        return "mask " + name + " renamed " + to;
    }
    MutGraph m(g);
    yyjson_mut_val* k = m.find("masks", "name", name);
    if (k == nullptr) {
        k = yyjson_mut_obj(m.doc.get());
        yyjson_mut_obj_add(k, yyjson_mut_str(m.doc.get(), "name"), yyjson_mut_strncpy(m.doc.get(), name.data(), name.size()));
        yyjson_mut_obj_add(k, yyjson_mut_str(m.doc.get(), "entries"), yyjson_mut_arr(m.doc.get()));
        yyjson_mut_arr_append(m.array("masks"), k);
    }
    m.overlay(k, op, {"op", "name", "rename", "remove"});
    g = m.result();
    return "mask " + name + (exists ? " updated" : " added");
}

std::string op_node(Graph& g, yyjson_val* op) {
    const std::string id = text_of(op, "id", true);
    const bool exists = g.find_node(id) >= 0;
    if (flag(op, "remove")) {
        if (!exists) fail("no node " + id);
        unlink_everywhere(g, id);
        std::erase_if(g.nodes, [&](const Node& n) { return n.id == id; });
        Editor e = Editor::of(g);
        e.nodes.erase(id);
        e.states.erase(id);
        e.store(g);
        return "node " + id + " removed";
    }
    std::string result = "node " + id + (exists ? " updated" : " added");
    std::string current = id;
    if (const std::string to = text_of(op, "rename", false); !to.empty()) {
        if (!exists) fail("no node " + id);
        rename_node(g, id, to);
        current = to;
        result = "node " + id + " renamed " + to;
    }
    MutGraph m(g);
    yyjson_mut_val* n = m.find("nodes", "id", current);
    if (n == nullptr) {
        if (member(op, "type") == nullptr) fail("a new node needs a type");
        n = yyjson_mut_obj(m.doc.get());
        yyjson_mut_obj_add(n, yyjson_mut_str(m.doc.get(), "id"), yyjson_mut_strncpy(m.doc.get(), id.data(), id.size()));
        yyjson_mut_arr_append(m.array("nodes"), n);
    }
    m.overlay(n, op, {"op", "id", "rename", "remove", "x", "y"});
    g = m.result();
    if (member(op, "x") != nullptr && member(op, "y") != nullptr) {
        Editor e = Editor::of(g);
        e.nodes[current] = {number(op, "x"), number(op, "y")};
        e.store(g);
    }
    if (g.root.empty() && g.nodes.size() == 1) g.root = current;  // the first node is the output
    return result;
}

std::string op_link(Graph& g, yyjson_val* op) {
    const std::string parent = text_of(op, "parent", true);
    const std::string child = text_of(op, "child", true);
    const std::string at = text_of(op, "at", false).empty() ? "append" : text_of(op, "at", false);
    if (g.find_node(child) < 0) fail("no node " + child);
    if (child == parent) fail("a node cannot use itself");
    Node& p = node_ref(g, parent);
    auto bad = [&]() -> std::string { fail("node " + parent + " (" + std::to_string(static_cast<int>(p.type)) + ") has no input " + at); };
    switch (p.type) {
        case NodeType::Clip:
            fail("a clip node has no inputs");
        case NodeType::Blend1D:
        case NodeType::Blend2D: {
            if (at == "append") {
                Sample s;
                s.node = child;
                if (!p.samples.empty()) {
                    s.x = p.samples.back().x + 1.f;
                    s.y = p.samples.back().y;
                }
                p.samples.push_back(s);
                break;
            }
            const int i = index_after(at, "sample:");
            if (i < 0 || static_cast<std::size_t>(i) >= p.samples.size()) bad();
            p.samples[static_cast<std::size_t>(i)].node = child;
            break;
        }
        case NodeType::StateMachine: {
            if (at == "append") {
                std::string name = child;
                for (int k = 2; std::any_of(p.states.begin(), p.states.end(), [&](const State& s) { return s.name == name; }); ++k) {
                    name = child + "_" + std::to_string(k);
                }
                p.states.push_back({name, child, false});
                if (p.entry.empty()) p.entry = name;
                break;
            }
            if (at.rfind("state:", 0) != 0) bad();
            const std::string state = at.substr(6);
            auto it = std::find_if(p.states.begin(), p.states.end(), [&](const State& s) { return s.name == state; });
            if (it == p.states.end()) fail("machine " + parent + " has no state " + state);
            it->node = child;
            it->conduit = false;
            break;
        }
        case NodeType::Layer: {
            if (at == "base") {
                p.base = child;
            } else if (at == "append") {
                LayerEntry l;
                l.node = child;
                p.layers.push_back(l);
            } else {
                const int i = index_after(at, "layer:");
                if (i < 0 || static_cast<std::size_t>(i) >= p.layers.size()) bad();
                p.layers[static_cast<std::size_t>(i)].node = child;
            }
            break;
        }
        case NodeType::Slot:
            if (at != "source" && at != "append") bad();
            p.source = child;
            break;
    }
    return "linked " + child + " -> " + parent + " " + at;
}

std::string op_unlink(Graph& g, yyjson_val* op) {
    const std::string parent = text_of(op, "parent", true);
    const std::string at = text_of(op, "at", true);
    Node& p = node_ref(g, parent);
    if (at == "base") {
        p.base.clear();
    } else if (at == "source") {
        p.source.clear();
    } else if (const int i = index_after(at, "sample:"); i >= 0 && static_cast<std::size_t>(i) < p.samples.size()) {
        p.samples.erase(p.samples.begin() + i);
    } else if (const int l = index_after(at, "layer:"); l >= 0 && static_cast<std::size_t>(l) < p.layers.size()) {
        p.layers.erase(p.layers.begin() + l);
    } else if (at.rfind("state:", 0) == 0) {
        auto it = std::find_if(p.states.begin(), p.states.end(), [&](const State& s) { return s.name == at.substr(6); });
        if (it == p.states.end()) fail("no input " + at);
        it->node.clear();
    } else {
        fail("node " + parent + " has no input " + at);
    }
    return "unlinked " + parent + " " + at;
}

std::string op_state(Graph& g, yyjson_val* op) {
    const std::string machine_id = text_of(op, "machine", true);
    Node& m = machine_ref(g, machine_id);
    const std::string name = text_of(op, "name", true);
    auto it = std::find_if(m.states.begin(), m.states.end(), [&](const State& s) { return s.name == name; });
    if (flag(op, "remove")) {
        if (it == m.states.end()) fail("no state " + name);
        m.states.erase(it);
        std::erase_if(m.transitions, [&](const Transition& t) { return t.from == name || t.to == name; });
        if (m.entry == name) m.entry = m.states.empty() ? std::string() : m.states.front().name;
        Editor e = Editor::of(g);
        e.states[machine_id].erase(name);
        e.store(g);
        return "state " + name + " removed";
    }
    std::string current = name;
    if (const std::string to = text_of(op, "rename", false); !to.empty()) {
        if (it == m.states.end()) fail("no state " + name);
        rename_state(g, m, name, to);
        current = to;
    }
    Node& mm = machine_ref(g, machine_id);  // rename_state may store the editor section (no reallocation of nodes)
    auto st = std::find_if(mm.states.begin(), mm.states.end(), [&](const State& s) { return s.name == current; });
    if (st == mm.states.end()) {
        if (current.empty() || current == "*") fail("bad state name");
        mm.states.push_back({current, "", false});
        st = mm.states.end() - 1;
    }
    if (yyjson_val* n = member(op, "node"); n != nullptr) {
        if (yyjson_is_null(n)) {
            st->node.clear();
        } else {
            const std::string child = text_of(op, "node", true);
            if (g.find_node(child) < 0) fail("no node " + child);
            st->node = child;
            st->conduit = false;
        }
    }
    if (member(op, "conduit") != nullptr) {
        st->conduit = flag(op, "conduit");
        if (st->conduit) st->node.clear();
    }
    if (flag(op, "entry") || mm.entry.empty()) mm.entry = current;
    if (member(op, "x") != nullptr && member(op, "y") != nullptr) {
        Editor e = Editor::of(g);
        e.states[machine_id][current] = {number(op, "x"), number(op, "y")};
        e.store(g);
    }
    return "state " + current + " set";
}

std::string op_transition(Graph& g, yyjson_val* op) {
    const std::string machine_id = text_of(op, "machine", true);
    machine_ref(g, machine_id);
    const bool has_index = member(op, "index") != nullptr;
    const int index = has_index ? static_cast<int>(number(op, "index")) : -1;
    {
        Node& m = machine_ref(g, machine_id);
        if (has_index && (index < 0 || static_cast<std::size_t>(index) >= m.transitions.size())) {
            fail("machine " + machine_id + " has no transition " + std::to_string(index));
        }
        if (flag(op, "remove")) {
            if (!has_index) fail("remove needs an index");
            m.transitions.erase(m.transitions.begin() + index);
            return "transition " + std::to_string(index) + " removed";
        }
    }
    MutGraph mg(g);
    yyjson_mut_val* node = mg.find("nodes", "id", machine_id);
    yyjson_mut_val* list = yyjson_mut_obj_get(node, "transitions");
    if (list == nullptr) {
        list = yyjson_mut_arr(mg.doc.get());
        yyjson_mut_obj_add(node, yyjson_mut_str(mg.doc.get(), "transitions"), list);
    }
    yyjson_mut_val* t = nullptr;
    if (has_index) {
        t = yyjson_mut_arr_get(list, static_cast<std::size_t>(index));
    } else {
        if (text_of(op, "from", false).empty() || text_of(op, "to", false).empty()) fail("a new transition needs from and to");
        t = yyjson_mut_obj(mg.doc.get());
        yyjson_mut_arr_append(list, t);
    }
    mg.overlay(t, op, {"op", "machine", "index", "remove"});
    g = mg.result();
    const Node& m = machine_ref(g, machine_id);
    return "transition " + std::to_string(has_index ? index : static_cast<int>(m.transitions.size()) - 1) + " set";
}

std::string op_sample(Graph& g, yyjson_val* op) {
    Node& n = node_ref(g, text_of(op, "node", true));
    if (n.type != NodeType::Blend1D && n.type != NodeType::Blend2D) fail("node " + n.id + " is not a blend space");
    const int i = static_cast<int>(number(op, "index"));
    if (i < 0 || static_cast<std::size_t>(i) >= n.samples.size()) fail("no sample " + std::to_string(i));
    Sample& s = n.samples[static_cast<std::size_t>(i)];
    if (const std::string c = text_of(op, "child", false); !c.empty()) {
        if (g.find_node(c) < 0) fail("no node " + c);
        s.node = c;
    }
    if (member(op, "value") != nullptr) s.x = static_cast<float>(number(op, "value"));
    if (member(op, "x") != nullptr) s.x = static_cast<float>(number(op, "x"));
    if (member(op, "y") != nullptr) s.y = static_cast<float>(number(op, "y"));
    if (!std::isfinite(s.x) || !std::isfinite(s.y)) fail("sample coordinates must be finite");
    return "sample " + std::to_string(i) + " of " + n.id + " set";
}

std::string op_section(Graph& g, yyjson_val* op) {
    const std::string name = text_of(op, "name", true);
    static const std::set<std::string> allowed = {"targets", "colliders", "modifiers", "masks"};
    if (!allowed.contains(name)) fail("section must be targets, colliders, modifiers or masks");
    MutGraph m(g);
    yyjson_mut_obj_remove_key(m.root, name.c_str());
    yyjson_val* v = member(op, "value");
    if (v != nullptr && !yyjson_is_null(v)) {
        if (!yyjson_is_arr(v)) fail("value must be an array");
        yyjson_mut_obj_add(m.root, yyjson_mut_strncpy(m.doc.get(), name.data(), name.size()), yyjson_val_mut_copy(m.doc.get(), v));
    }
    g = m.result();
    return name + " replaced";
}

std::string op_position(Graph& g, yyjson_val* op) {
    Editor e = Editor::of(g);
    const double x = number(op, "x");
    const double y = number(op, "y");
    if (!std::isfinite(x) || !std::isfinite(y)) fail("x and y must be finite");
    if (const std::string machine = text_of(op, "machine", false); !machine.empty()) {
        const std::string state = text_of(op, "state", true);
        Node& m = machine_ref(g, machine);
        if (std::none_of(m.states.begin(), m.states.end(), [&](const State& s) { return s.name == state; })) fail("no state " + state);
        e.states[machine][state] = {x, y};
    } else {
        const std::string id = text_of(op, "node", true);
        if (g.find_node(id) < 0) fail("no node " + id);
        e.nodes[id] = {x, y};
    }
    e.store(g);
    return "moved";
}

std::string op_editor(Graph& g, yyjson_val* op) {
    const std::string key = text_of(op, "key", true);
    if (key == "nodes" || key == "states") fail("positions are set with the position op");
    Editor e = Editor::of(g);
    yyjson_val* v = member(op, "value");
    if (v == nullptr || yyjson_is_null(v)) {
        e.other.erase(key);
    } else {
        e.other[key] = minified(v);
    }
    e.store(g);
    return "editor " + key + " set";
}

}  // namespace

// ---- public ---------------------------------------------------------------------------------------------------

std::vector<std::string> apply(Graph& g, std::string_view ops_json) {
    DocPtr doc(yyjson_read(ops_json.data(), ops_json.size(), 0));
    if (!doc) fail("ops: not valid JSON");
    yyjson_val* ops = yyjson_doc_get_root(doc.get());
    if (!yyjson_is_arr(ops)) fail("ops must be an array");
    std::vector<std::string> out;
    for (std::size_t i = 0, n = yyjson_arr_size(ops); i < n; ++i) {
        yyjson_val* op = yyjson_arr_get(ops, i);
        std::string kind = "?";
        try {
            if (!yyjson_is_obj(op)) fail("an operation must be an object");
            kind = text_of(op, "op", true);
            if (kind == "graph") out.push_back(op_graph(g, op));
            else if (kind == "param") out.push_back(op_param(g, op));
            else if (kind == "slot") out.push_back(op_slot(g, op));
            else if (kind == "mask") out.push_back(op_mask(g, op));
            else if (kind == "node") out.push_back(op_node(g, op));
            else if (kind == "link") out.push_back(op_link(g, op));
            else if (kind == "unlink") out.push_back(op_unlink(g, op));
            else if (kind == "state") out.push_back(op_state(g, op));
            else if (kind == "transition") out.push_back(op_transition(g, op));
            else if (kind == "sample") out.push_back(op_sample(g, op));
            else if (kind == "section") out.push_back(op_section(g, op));
            else if (kind == "position") out.push_back(op_position(g, op));
            else if (kind == "layout") out.push_back("placed " + std::to_string(layout(g, member(op, "onlyMissing") == nullptr || flag(op, "onlyMissing"))));
            else if (kind == "editor") out.push_back(op_editor(g, op));
            else fail("unknown op (graph, param, slot, mask, node, link, unlink, state, transition, sample, section, "
                      "position, layout, editor)");
        } catch (const std::exception& e) {
            std::string msg = e.what();
            const std::string prefix = ".omanimgraph: ";
            if (msg.rfind(prefix, 0) == 0) msg = msg.substr(prefix.size());
            throw format_error("ops[" + std::to_string(i) + "] (" + kind + "): " + msg);
        }
    }
    return out;
}

std::vector<Problem> problems(const Graph& g) {
    std::vector<Problem> out;
    auto add = [&](Severity s, const std::string& node, const std::string& subject, const std::string& message) {
        out.push_back({s, node, subject, message});
    };
    auto param_type = [&](const std::string& name) -> int {
        const int i = g.find_param(name);
        return i < 0 ? -1 : static_cast<int>(g.params[static_cast<std::size_t>(i)].type);
    };
    auto need_param = [&](const std::string& node, const std::string& subject, const std::string& name,
                          std::initializer_list<ParamType> types) {
        if (name.empty()) {
            add(Severity::Error, node, subject, "no parameter chosen");
            return;
        }
        const int t = param_type(name);
        if (t < 0) {
            add(Severity::Error, node, subject, "unknown parameter " + name);
            return;
        }
        if (std::none_of(types.begin(), types.end(), [&](ParamType p) { return static_cast<int>(p) == t; })) {
            add(Severity::Error, node, subject, "parameter " + name + " has the wrong type");
        }
    };
    auto need_node = [&](const std::string& node, const std::string& subject, const std::string& id) {
        if (id.empty()) {
            add(Severity::Error, node, subject, "nothing connected");
        } else if (g.find_node(id) < 0) {
            add(Severity::Error, node, subject, "unknown node " + id);
        } else if (id == node) {
            add(Severity::Error, node, subject, "uses itself");
        } else if (id == g.root) {
            add(Severity::Error, node, subject, "uses the output node " + id);
        }
    };
    auto need_mask = [&](const std::string& node, const std::string& subject, const std::string& name) {
        if (!name.empty() && g.find_mask(name) < 0) add(Severity::Error, node, subject, "unknown mask " + name);
    };

    if (g.nodes.empty()) add(Severity::Error, "", "nodes", "the graph has no nodes");
    if (g.root.empty()) {
        add(Severity::Error, "", "root", "no output node");
    } else if (g.find_node(g.root) < 0) {
        add(Severity::Error, "", "root", "unknown output node " + g.root);
    }
    std::unordered_set<std::string> ids;
    for (const Node& n : g.nodes) {
        if (!ids.insert(n.id).second) add(Severity::Error, n.id, "", "duplicate node id");
        switch (n.type) {
            case NodeType::Clip:
                if (n.slot.empty()) add(Severity::Error, n.id, "slot", "no clip slot chosen");
                else if (g.find_slot(n.slot) < 0) add(Severity::Error, n.id, "slot", "unknown slot " + n.slot);
                if (!n.rate_param.empty()) need_param(n.id, "rateParam", n.rate_param, {ParamType::Float});
                break;
            case NodeType::Blend1D:
                need_param(n.id, "param", n.param, {ParamType::Float});
                if (n.samples.empty()) add(Severity::Error, n.id, "samples", "no samples");
                for (std::size_t k = 0; k < n.samples.size(); ++k) need_node(n.id, "samples[" + std::to_string(k) + "]", n.samples[k].node);
                break;
            case NodeType::Blend2D:
                if (n.param_y.empty()) {
                    need_param(n.id, "param", n.param, {ParamType::Vec2});
                } else {
                    need_param(n.id, "paramX", n.param, {ParamType::Float});
                    need_param(n.id, "paramY", n.param_y, {ParamType::Float});
                }
                if (n.samples.empty()) add(Severity::Error, n.id, "samples", "no samples");
                for (std::size_t k = 0; k < n.samples.size(); ++k) need_node(n.id, "samples[" + std::to_string(k) + "]", n.samples[k].node);
                break;
            case NodeType::StateMachine: {
                if (n.states.empty()) add(Severity::Error, n.id, "states", "no states");
                std::unordered_set<std::string> names;
                for (std::size_t k = 0; k < n.states.size(); ++k) {
                    const State& s = n.states[k];
                    if (!names.insert(s.name).second) add(Severity::Error, n.id, "states[" + std::to_string(k) + "]", "duplicate state " + s.name);
                    if (!s.conduit) need_node(n.id, "state:" + s.name, s.node);
                }
                if (!n.states.empty() && !names.contains(n.entry)) add(Severity::Error, n.id, "entry", "no entry state");
                for (std::size_t k = 0; k < n.transitions.size(); ++k) {
                    const Transition& t = n.transitions[k];
                    const std::string at = "transitions[" + std::to_string(k) + "]";
                    if (t.from != "*" && !names.contains(t.from)) add(Severity::Error, n.id, at, "unknown state " + t.from);
                    if (!names.contains(t.to)) add(Severity::Error, n.id, at, "unknown state " + t.to);
                    for (const Condition& c : t.conditions) {
                        if (g.find_param(c.param) < 0) add(Severity::Error, n.id, at, "unknown parameter " + c.param);
                    }
                }
                for (const State& s : n.states) {
                    const bool reached = s.name == n.entry ||
                        std::any_of(n.transitions.begin(), n.transitions.end(), [&](const Transition& t) { return t.to == s.name; });
                    if (!reached) add(Severity::Warning, n.id, "state:" + s.name, "no transition leads to state " + s.name);
                }
                break;
            }
            case NodeType::Layer:
                need_node(n.id, "base", n.base);
                for (std::size_t k = 0; k < n.layers.size(); ++k) {
                    const LayerEntry& l = n.layers[k];
                    need_node(n.id, "layer:" + std::to_string(k), l.node);
                    need_mask(n.id, "layer:" + std::to_string(k), l.mask);
                    if (!l.weight_param.empty()) need_param(n.id, "layer:" + std::to_string(k), l.weight_param, {ParamType::Float});
                }
                break;
            case NodeType::Slot:
                if (n.name.empty()) add(Severity::Error, n.id, "name", "no montage slot name");
                need_node(n.id, "source", n.source);
                need_mask(n.id, "mask", n.mask);
                break;
        }
    }
    // Reachability (a warning: unused nodes are kept but not evaluated) and cycles.
    std::unordered_map<std::string, int> colour;
    std::unordered_set<std::string> reached;
    std::function<void(const std::string&)> visit = [&](const std::string& id) {
        const int i = g.find_node(id);
        if (i < 0) return;
        int& c = colour[id];
        if (c == 1) {
            add(Severity::Error, id, "", "part of a cycle");
            return;
        }
        if (c == 2) return;
        c = 1;
        reached.insert(id);
        for (const std::string& ch : node_children(g.nodes[static_cast<std::size_t>(i)])) {
            if (!ch.empty()) visit(ch);
        }
        colour[id] = 2;
    };
    if (!g.root.empty()) visit(g.root);
    for (const Node& n : g.nodes) {
        if (!reached.contains(n.id)) {
            add(Severity::Warning, n.id, "", "not connected to the output");
            visit(n.id);  // cycles among unused nodes
        }
    }
    const bool any_error = std::any_of(out.begin(), out.end(), [](const Problem& p) { return p.severity == Severity::Error; });
    if (!any_error) {
        try {
            validate(g);
        } catch (const std::exception& e) {
            std::string msg = e.what();
            const std::string prefix = ".omanimgraph: ";
            if (msg.rfind(prefix, 0) == 0) msg = msg.substr(prefix.size());
            add(Severity::Error, "", "", msg);
        }
    }
    return out;
}

namespace {

const char* type_name(NodeType t) {
    switch (t) {
        case NodeType::Clip: return "clip";
        case NodeType::Blend1D: return "blend1d";
        case NodeType::Blend2D: return "blend2d";
        case NodeType::StateMachine: return "stateMachine";
        case NodeType::Layer: return "layer";
        case NodeType::Slot: return "slot";
    }
    return "?";
}

std::string fmt(double v) {
    char b[32];
    std::snprintf(b, sizeof b, "%.2f", v);
    return b;
}

const char* op_text(CondOp op) {
    switch (op) {
        case CondOp::Eq: return "==";
        case CondOp::Ne: return "!=";
        case CondOp::Gt: return ">";
        case CondOp::Lt: return "<";
        case CondOp::Ge: return ">=";
        case CondOp::Le: return "<=";
        default: return "";
    }
}

}  // namespace

std::string view(const Graph& g, const std::vector<Problem>& probs) {
    MutDocPtr doc(yyjson_mut_doc_new(nullptr));
    yyjson_mut_doc* d = doc.get();
    yyjson_mut_val* root = yyjson_mut_obj(d);
    yyjson_mut_doc_set_root(d, root);
    auto s = [&](const std::string& v) { return yyjson_mut_strncpy(d, v.data(), v.size()); };
    auto add = [&](yyjson_mut_val* o, const char* k, yyjson_mut_val* v) { yyjson_mut_obj_add(o, yyjson_mut_str(d, k), v); };
    const Editor e = Editor::of(g);
    add(root, "name", s(g.name));
    add(root, "profile", s(g.profile));
    add(root, "root", s(g.root));
    yyjson_mut_val* nodes = yyjson_mut_arr(d);
    yyjson_mut_val* machines = yyjson_mut_obj(d);
    for (const Node& n : g.nodes) {
        yyjson_mut_val* o = yyjson_mut_obj(d);
        add(o, "id", s(n.id));
        add(o, "type", yyjson_mut_str(d, type_name(n.type)));
        std::string title;
        switch (n.type) {
            case NodeType::Clip: title = "clip " + n.slot; break;
            case NodeType::Blend1D: title = "blend " + n.param; break;
            case NodeType::Blend2D: title = "blend " + n.param + (n.param_y.empty() ? "" : " / " + n.param_y); break;
            case NodeType::StateMachine: title = "state machine (" + std::to_string(n.states.size()) + ")"; break;
            case NodeType::Layer: title = "layer (" + std::to_string(n.layers.size()) + ")"; break;
            case NodeType::Slot: title = "montage " + n.name; break;
        }
        add(o, "title", s(title));
        auto pos = e.nodes.find(n.id);
        add(o, "x", yyjson_mut_real(d, pos == e.nodes.end() ? 0.0 : pos->second.first));
        add(o, "y", yyjson_mut_real(d, pos == e.nodes.end() ? 0.0 : pos->second.second));
        add(o, "placed", yyjson_mut_bool(d, pos != e.nodes.end()));
        yyjson_mut_val* inputs = yyjson_mut_arr(d);
        auto input = [&](const std::string& at, const std::string& label, const std::string& child) {
            yyjson_mut_val* in = yyjson_mut_obj(d);
            add(in, "at", s(at));
            add(in, "label", s(label));
            add(in, "child", s(child));
            yyjson_mut_arr_append(inputs, in);
        };
        switch (n.type) {
            case NodeType::Blend1D:
                for (std::size_t k = 0; k < n.samples.size(); ++k) input("sample:" + std::to_string(k), fmt(n.samples[k].x), n.samples[k].node);
                break;
            case NodeType::Blend2D:
                for (std::size_t k = 0; k < n.samples.size(); ++k) {
                    input("sample:" + std::to_string(k), fmt(n.samples[k].x) + ", " + fmt(n.samples[k].y), n.samples[k].node);
                }
                break;
            case NodeType::StateMachine:
                for (const State& st : n.states) {
                    if (!st.conduit) input("state:" + st.name, st.name, st.node);
                }
                break;
            case NodeType::Layer:
                input("base", "base", n.base);
                for (std::size_t k = 0; k < n.layers.size(); ++k) {
                    const LayerEntry& l = n.layers[k];
                    std::string label = (l.mode == LayerMode::Additive ? "additive" : "override");
                    if (!l.mask.empty()) label += " " + l.mask;
                    if (l.first_person) label += " (first person)";
                    input("layer:" + std::to_string(k), label, l.node);
                }
                break;
            case NodeType::Slot:
                input("source", "source", n.source);
                break;
            default:
                break;
        }
        add(o, "inputs", inputs);
        int errors = 0, warnings = 0;
        for (const Problem& p : probs) {
            if (p.node != n.id) continue;
            (p.severity == Severity::Error ? errors : warnings) += p.severity == Severity::Info ? 0 : 1;
        }
        add(o, "errors", yyjson_mut_int(d, errors));
        add(o, "warnings", yyjson_mut_int(d, warnings));
        yyjson_mut_arr_append(nodes, o);

        if (n.type == NodeType::StateMachine) {
            yyjson_mut_val* m = yyjson_mut_obj(d);
            add(m, "entry", s(n.entry));
            yyjson_mut_val* states = yyjson_mut_arr(d);
            const auto sp = e.states.find(n.id);
            for (const State& st : n.states) {
                yyjson_mut_val* so = yyjson_mut_obj(d);
                add(so, "name", s(st.name));
                add(so, "node", s(st.node));
                add(so, "conduit", yyjson_mut_bool(d, st.conduit));
                const std::pair<double, double>* p = nullptr;
                if (sp != e.states.end()) {
                    auto it = sp->second.find(st.name);
                    if (it != sp->second.end()) p = &it->second;
                }
                add(so, "x", yyjson_mut_real(d, p == nullptr ? 0.0 : p->first));
                add(so, "y", yyjson_mut_real(d, p == nullptr ? 0.0 : p->second));
                yyjson_mut_arr_append(states, so);
            }
            add(m, "states", states);
            yyjson_mut_val* trans = yyjson_mut_arr(d);
            for (std::size_t k = 0; k < n.transitions.size(); ++k) {
                const Transition& t = n.transitions[k];
                yyjson_mut_val* to = yyjson_mut_obj(d);
                add(to, "index", yyjson_mut_int(d, static_cast<int64_t>(k)));
                add(to, "from", s(t.from));
                add(to, "to", s(t.to));
                std::string label;
                for (const Condition& c : t.conditions) {
                    if (!label.empty()) label += " && ";
                    label += c.param;
                    if (c.op != CondOp::True) label += std::string(" ") + op_text(c.op) + " " + fmt(c.value);
                }
                if (t.exit_time >= 0.f) label += (label.empty() ? "" : " && ") + std::string("exit ") + fmt(t.exit_time);
                add(to, "label", s(label));
                add(to, "priority", yyjson_mut_int(d, t.priority));
                yyjson_mut_arr_append(trans, to);
            }
            add(m, "transitions", trans);
            yyjson_mut_obj_add(machines, s(n.id), m);
        }
    }
    add(root, "nodes", nodes);
    add(root, "machines", machines);
    yyjson_mut_val* params = yyjson_mut_arr(d);
    for (const Param& p : g.params) {
        yyjson_mut_val* o = yyjson_mut_obj(d);
        add(o, "name", s(p.name));
        static const char* names[] = {"float", "int", "bool", "trigger", "vec2"};
        add(o, "type", yyjson_mut_str(d, names[static_cast<int>(p.type)]));
        yyjson_mut_arr_append(params, o);
    }
    add(root, "parameters", params);
    yyjson_mut_val* slots = yyjson_mut_arr(d);
    for (const std::string& sl : g.slots) yyjson_mut_arr_append(slots, s(sl));
    add(root, "slots", slots);
    yyjson_mut_val* masks = yyjson_mut_arr(d);
    for (const Mask& m : g.masks) yyjson_mut_arr_append(masks, s(m.name));
    add(root, "masks", masks);
    yyjson_mut_val* targets = yyjson_mut_arr(d);
    for (const std::string& t : g.targets) yyjson_mut_arr_append(targets, s(t));
    add(root, "targets", targets);
    add(root, "modifiers", yyjson_mut_int(d, static_cast<int64_t>(g.modifiers.size())));
    yyjson_mut_val* pr = yyjson_mut_arr(d);
    for (const Problem& p : probs) {
        yyjson_mut_val* o = yyjson_mut_obj(d);
        static const char* sev[] = {"info", "warning", "error"};
        add(o, "severity", yyjson_mut_str(d, sev[static_cast<int>(p.severity)]));
        add(o, "node", s(p.node));
        add(o, "subject", s(p.subject));
        add(o, "message", s(p.message));
        yyjson_mut_arr_append(pr, o);
    }
    add(root, "problems", pr);
    for (const auto& [key, js] : e.other) {
        if (key == "preview") {
            DocPtr pd(yyjson_read(js.data(), js.size(), 0));
            if (pd) add(root, "preview", yyjson_val_mut_copy(d, yyjson_doc_get_root(pd.get())));
        }
    }
    std::size_t len = 0;
    CharPtr out(yyjson_mut_write(d, YYJSON_WRITE_NOFLAG, &len));
    if (!out) fail("out of memory");
    return {out.get(), len};
}

int layout(Graph& g, bool only_missing) {
    Editor e = Editor::of(g);
    int placed = 0;
    // Layers by the longest path from the root (a DAG node sits left of all its users).
    std::unordered_map<std::string, int> depth;
    std::vector<std::string> order;  // reachable, topological
    if (g.find_node(g.root) >= 0) {
        std::unordered_map<std::string, int> indeg;
        std::unordered_set<std::string> seen{g.root};
        std::vector<std::string> stack{g.root};
        while (!stack.empty()) {
            const std::string id = stack.back();
            stack.pop_back();
            for (const std::string& ch : node_children(g.nodes[static_cast<std::size_t>(g.find_node(id))])) {
                if (g.find_node(ch) < 0) continue;
                ++indeg[ch];
                if (seen.insert(ch).second) stack.push_back(ch);
            }
        }
        std::vector<std::string> queue{g.root};
        depth[g.root] = 0;
        for (std::size_t q = 0; q < queue.size(); ++q) {
            const std::string id = queue[q];
            order.push_back(id);
            for (const std::string& ch : node_children(g.nodes[static_cast<std::size_t>(g.find_node(id))])) {
                if (g.find_node(ch) < 0) continue;
                depth[ch] = std::max(depth[ch], depth[id] + 1);
                if (--indeg[ch] == 0) queue.push_back(ch);
            }
        }
    }
    constexpr double kColumn = 280.0;
    constexpr double kRow = 120.0;
    std::map<int, std::vector<std::string>> columns;
    for (const std::string& id : order) columns[depth[id]].push_back(id);  // BFS order = parents' order
    for (auto& [col, ids] : columns) {
        // Order a column by the mean row of the users already placed (barycentre), stable.
        std::vector<std::pair<double, std::string>> keyed;
        for (std::size_t k = 0; k < ids.size(); ++k) {
            double sum = 0.0;
            int count = 0;
            for (const Node& n : g.nodes) {
                for (const std::string& ch : node_children(n)) {
                    if (ch != ids[k]) continue;
                    if (auto it = e.nodes.find(n.id); it != e.nodes.end() && depth.contains(n.id) && depth[n.id] < col) {
                        sum += it->second.second;
                        ++count;
                    }
                }
            }
            keyed.emplace_back(count > 0 ? sum / count : static_cast<double>(k) * kRow, ids[k]);
        }
        std::stable_sort(keyed.begin(), keyed.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
        const double top = -(static_cast<double>(keyed.size()) - 1.0) * kRow / 2.0;
        for (std::size_t k = 0; k < keyed.size(); ++k) {
            const std::string& id = keyed[k].second;
            if (only_missing && e.nodes.contains(id)) continue;
            e.nodes[id] = {-static_cast<double>(col) * kColumn, top + static_cast<double>(k) * kRow};
            ++placed;
        }
    }
    // Nodes not connected to the output: a column further left.
    const double free_x = -static_cast<double>(columns.size() + 1) * kColumn;
    double free_y = 0.0;
    for (const Node& n : g.nodes) {
        if (depth.contains(n.id)) continue;
        if (!(only_missing && e.nodes.contains(n.id))) {
            e.nodes[n.id] = {free_x, free_y};
            ++placed;
        }
        free_y += kRow;
    }
    // States of each machine: a circle, entry first.
    for (const Node& n : g.nodes) {
        if (n.type != NodeType::StateMachine) continue;
        auto& ss = e.states[n.id];
        const std::size_t count = n.states.size();
        const double radius = std::max(160.0, 70.0 * static_cast<double>(count) / std::numbers::pi);
        for (std::size_t k = 0; k < count; ++k) {
            if (only_missing && ss.contains(n.states[k].name)) continue;
            const double a = std::numbers::pi + 2.0 * std::numbers::pi * static_cast<double>(k) / static_cast<double>(std::max<std::size_t>(count, 1));
            ss[n.states[k].name] = {radius * std::cos(a), radius * std::sin(a)};
            ++placed;
        }
    }
    e.store(g);
    return placed;
}

}  // namespace cenda::omformat::omanimgraph::edit
