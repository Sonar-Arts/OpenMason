#include "omformat/omlook.hpp"

#include "json_util.hpp"

#include <cmath>
#include <cstring>
#include <unordered_set>

namespace cenda::omformat::omlook {

namespace {

using json::CharPtr;
using json::DocPtr;
using json::MutDocPtr;

[[noreturn]] void fail(const std::string& message) { throw format_error(".omlook: " + message); }

int hex(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

bool parse_color(std::string_view s, std::uint8_t out[3]) {
    if (s.size() != 7 || s[0] != '#') return false;
    for (int i = 0; i < 3; ++i) {
        const int h = hex(s[static_cast<std::size_t>(1 + 2 * i)]), l = hex(s[static_cast<std::size_t>(2 + 2 * i)]);
        if (h < 0 || l < 0) return false;
        out[i] = static_cast<std::uint8_t>(h * 16 + l);
    }
    return true;
}

std::string color_text(const std::uint8_t rgb[3]) {
    static const char* digits = "0123456789abcdef";
    std::string s = "#";
    for (int i = 0; i < 3; ++i) {
        s += digits[rgb[i] >> 4];
        s += digits[rgb[i] & 15];
    }
    return s;
}

std::string hex_text(const std::vector<std::uint8_t>& raw) {
    static const char* digits = "0123456789abcdef";
    std::string s;
    s.reserve(raw.size() * 2);
    for (std::uint8_t b : raw) {
        s += digits[b >> 4];
        s += digits[b & 15];
    }
    return s;
}

void parse_version(std::string_view version) {
    const auto dot = version.find('.');
    if (dot == std::string_view::npos || dot == 0 || dot + 1 >= version.size()) fail("bad version \"" + std::string(version) + "\"");
    int major = 0;
    for (std::size_t i = 0; i < dot; ++i) {
        if (version[i] < '0' || version[i] > '9') fail("bad version \"" + std::string(version) + "\"");
        major = major * 10 + (version[i] - '0');
        if (major > 1000) fail("bad version");
    }
    if (major > kVersionMajor) {
        throw version_error(".omlook: version " + std::string(version) + " is newer than this reader (" +
                            std::to_string(kVersionMajor) + "." + std::to_string(kVersionMinor) + ")");
    }
}

std::string minified(yyjson_val* v) {
    std::size_t len = 0;
    CharPtr text(yyjson_val_write(v, YYJSON_WRITE_NOFLAG, &len));
    if (!text) fail("cannot write a JSON value");
    return {text.get(), len};
}

// ---- binary helpers ----

struct Reader {
    std::span<const std::byte> b;
    std::size_t at = 0;

    std::uint8_t u8() {
        if (at >= b.size()) fail("truncated binary record");
        return static_cast<std::uint8_t>(b[at++]);
    }
    std::uint64_t varint() {
        std::uint64_t v = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            const std::uint8_t c = u8();
            v |= static_cast<std::uint64_t>(c & 0x7f) << shift;
            if ((c & 0x80) == 0) return v;
        }
        fail("bad varint");
    }
    std::span<const std::byte> bytes(std::uint64_t n) {
        if (n > b.size() - at) fail("truncated binary record");
        auto s = b.subspan(at, static_cast<std::size_t>(n));
        at += static_cast<std::size_t>(n);
        return s;
    }
    std::string text(std::uint64_t n) {
        auto s = bytes(n);
        return {reinterpret_cast<const char*>(s.data()), s.size()};
    }
};

void put_varint(std::vector<std::byte>& out, std::uint64_t v) {
    do {
        std::uint8_t c = static_cast<std::uint8_t>(v & 0x7f);
        v >>= 7;
        if (v != 0) c |= 0x80;
        out.push_back(static_cast<std::byte>(c));
    } while (v != 0);
}

void put_bytes(std::vector<std::byte>& out, const void* data, std::size_t n) {
    const auto* p = static_cast<const std::byte*>(data);
    out.insert(out.end(), p, p + n);
}

void check_name(const Look& look, const std::string& name, std::unordered_set<std::string>& seen) {
    (void) look;
    if (name.empty()) fail("empty slot name");
    if (!seen.insert(name).second) fail("duplicate slot " + name);
}

}  // namespace

bool Entry::same_value(const Entry& o) const {
    if (type != o.type) return false;
    switch (type) {
        case Remove:
            return true;
        case Color:
            return std::memcmp(rgb, o.rgb, 3) == 0;
        case Bool:
            return flag == o.flag;
        case Number:
            return std::memcmp(&number, &o.number, sizeof number) == 0;
        case String:
        case Json:
            return text == o.text;
        default:
            return raw == o.raw;
    }
}

int Look::find(std::string_view name) const {
    for (std::size_t i = 0; i < entries.size(); ++i) {
        if (entries[i].name == name) return static_cast<int>(i);
    }
    return -1;
}

std::string minify_json_value(std::string_view text) {
    yyjson_read_err err{};
    DocPtr doc(yyjson_read_opts(const_cast<char*>(text.data()), text.size(), YYJSON_READ_NOFLAG, nullptr, &err));
    if (!doc) fail(std::string("bad JSON value: ") + err.msg);
    yyjson_val* root = yyjson_doc_get_root(doc.get());
    if (!yyjson_is_obj(root) && !yyjson_is_arr(root)) fail("a JSON slot value must be an object or an array");
    return minified(root);
}

Look read_json(std::string_view text) {
    yyjson_read_err err{};
    DocPtr doc(yyjson_read_opts(const_cast<char*>(text.data()), text.size(), YYJSON_READ_NOFLAG, nullptr, &err));
    if (!doc) fail(std::string("not JSON: ") + err.msg);
    yyjson_val* root = yyjson_doc_get_root(doc.get());
    if (!yyjson_is_obj(root)) fail("must be a JSON object");
    yyjson_val* format = yyjson_obj_get(root, "format");
    if (!yyjson_is_str(format) || std::string_view(yyjson_get_str(format)) != "omlook") fail("format is not \"omlook\"");
    yyjson_val* version = yyjson_obj_get(root, "version");
    if (!yyjson_is_str(version)) fail("missing \"version\"");
    parse_version(yyjson_get_str(version));
    Look look;
    if (yyjson_val* c = yyjson_obj_get(root, "character"); c != nullptr && !yyjson_is_null(c)) {
        if (!yyjson_is_str(c)) fail("\"character\" must be a string");
        look.character.assign(yyjson_get_str(c), yyjson_get_len(c));
    }
    yyjson_val* slots = yyjson_obj_get(root, "slots");
    if (slots == nullptr) return look;
    if (!yyjson_is_obj(slots)) fail("\"slots\" must be an object");
    std::unordered_set<std::string> seen;
    yyjson_obj_iter it = yyjson_obj_iter_with(slots);
    yyjson_val* key;
    while ((key = yyjson_obj_iter_next(&it)) != nullptr) {
        yyjson_val* v = yyjson_obj_iter_get_val(key);
        Entry e;
        e.name.assign(yyjson_get_str(key), yyjson_get_len(key));
        check_name(look, e.name, seen);
        if (yyjson_is_null(v)) {
            e.type = Remove;
        } else if (yyjson_is_bool(v)) {
            e.type = Bool;
            e.flag = yyjson_get_bool(v);
        } else if (yyjson_is_num(v)) {
            const double d = yyjson_get_num(v);
            if (!std::isfinite(d)) fail("slot " + e.name + ": not a finite number");
            e.type = Number;
            e.number = static_cast<float>(d);
        } else if (yyjson_is_str(v)) {
            const std::string_view s(yyjson_get_str(v), yyjson_get_len(v));
            if (parse_color(s, e.rgb)) {
                e.type = Color;
            } else {
                e.type = String;
                e.text.assign(s);
            }
        } else {
            yyjson_val* t = yyjson_is_obj(v) ? yyjson_obj_get(v, "$type") : nullptr;
            yyjson_val* h = yyjson_is_obj(v) ? yyjson_obj_get(v, "$hex") : nullptr;
            if (t != nullptr && h != nullptr && yyjson_obj_size(v) == 2) {
                if (!yyjson_is_uint(t) || yyjson_get_uint(t) <= Json || yyjson_get_uint(t) > 255 || !yyjson_is_str(h)) {
                    fail("slot " + e.name + ": bad $type/$hex value");
                }
                e.type = static_cast<std::uint8_t>(yyjson_get_uint(t));
                const std::string_view hs(yyjson_get_str(h), yyjson_get_len(h));
                if (hs.size() % 2 != 0) fail("slot " + e.name + ": odd $hex length");
                for (std::size_t i = 0; i < hs.size(); i += 2) {
                    const int a = hex(hs[i]), b = hex(hs[i + 1]);
                    if (a < 0 || b < 0) fail("slot " + e.name + ": bad $hex");
                    e.raw.push_back(static_cast<std::uint8_t>(a * 16 + b));
                }
            } else {
                e.type = Json;
                e.text = minified(v);
            }
        }
        look.entries.push_back(std::move(e));
    }
    return look;
}

Look read_binary(std::span<const std::byte> bytes) {
    Reader r{bytes};
    if (r.u8() != 'O' || r.u8() != 'L') fail("not a binary omlook (magic)");
    const int major = r.u8();
    r.u8();  // minor
    if (major > kVersionMajor) {
        throw version_error(".omlook: binary version " + std::to_string(major) + ".x is newer than this reader");
    }
    Look look;
    look.character = r.text(r.varint());
    const std::uint64_t count = r.varint();
    if (count > bytes.size()) fail("bad entry count");
    std::unordered_set<std::string> seen;
    for (std::uint64_t i = 0; i < count; ++i) {
        Entry e;
        e.name = r.text(r.varint());
        check_name(look, e.name, seen);
        e.type = r.u8();
        const auto payload = r.bytes(r.varint());
        const auto* p = reinterpret_cast<const std::uint8_t*>(payload.data());
        switch (e.type) {
            case Remove:
                if (!payload.empty()) fail("slot " + e.name + ": a removal carries no value");
                break;
            case Color:
                if (payload.size() != 3) fail("slot " + e.name + ": a colour is 3 bytes");
                std::memcpy(e.rgb, p, 3);
                break;
            case Bool:
                if (payload.size() != 1 || p[0] > 1) fail("slot " + e.name + ": a bool is one byte 0/1");
                e.flag = p[0] != 0;
                break;
            case Number: {
                if (payload.size() != 4) fail("slot " + e.name + ": a number is 4 bytes");
                const std::uint32_t u = static_cast<std::uint32_t>(p[0]) | static_cast<std::uint32_t>(p[1]) << 8 |
                                        static_cast<std::uint32_t>(p[2]) << 16 | static_cast<std::uint32_t>(p[3]) << 24;
                std::memcpy(&e.number, &u, 4);
                if (!std::isfinite(e.number)) fail("slot " + e.name + ": not a finite number");
                break;
            }
            case String:
                e.text.assign(reinterpret_cast<const char*>(p), payload.size());
                // "#rrggbb" is a colour in JSON; keep both encodings equivalent.
                if (parse_color(e.text, e.rgb)) {
                    e.type = Color;
                    e.text.clear();
                }
                break;
            case Json:
                e.text = minify_json_value(std::string_view(reinterpret_cast<const char*>(p), payload.size()));
                break;
            default:
                e.raw.assign(p, p + payload.size());
                break;
        }
        look.entries.push_back(std::move(e));
    }
    if (r.at != bytes.size()) fail("trailing bytes after the record");
    return look;
}

Look read(std::span<const std::byte> bytes) {
    for (std::byte b : bytes) {
        const char c = static_cast<char>(b);
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') continue;
        if (c == '{') {
            return read_json(std::string_view(reinterpret_cast<const char*>(bytes.data()), bytes.size()));
        }
        break;
    }
    return read_binary(bytes);
}

std::vector<std::byte> write_json(const Look& look) {
    MutDocPtr doc(yyjson_mut_doc_new(nullptr));
    yyjson_mut_doc* d = doc.get();
    yyjson_mut_val* root = yyjson_mut_obj(d);
    yyjson_mut_doc_set_root(d, root);
    auto put = [&](yyjson_mut_val* obj, const std::string& key, yyjson_mut_val* v) {
        yyjson_mut_obj_add(obj, yyjson_mut_strncpy(d, key.data(), key.size()), v);
    };
    put(root, "format", yyjson_mut_str(d, "omlook"));
    const std::string version = std::to_string(kVersionMajor) + "." + std::to_string(kVersionMinor);
    put(root, "version", yyjson_mut_strncpy(d, version.data(), version.size()));
    if (!look.character.empty()) put(root, "character", yyjson_mut_strncpy(d, look.character.data(), look.character.size()));
    yyjson_mut_val* slots = yyjson_mut_obj(d);
    std::unordered_set<std::string> seen;
    for (const Entry& e : look.entries) {
        check_name(look, e.name, seen);
        yyjson_mut_val* v = nullptr;
        switch (e.type) {
            case Remove:
                v = yyjson_mut_null(d);
                break;
            case Color: {
                const std::string s = color_text(e.rgb);
                v = yyjson_mut_strncpy(d, s.data(), s.size());
                break;
            }
            case Bool:
                v = yyjson_mut_bool(d, e.flag);
                break;
            case Number:
                v = yyjson_mut_float(d, e.number);
                break;
            case String:
                if (std::uint8_t rgb[3]; parse_color(e.text, rgb)) fail("slot " + e.name + ": a string that reads as a colour");
                v = yyjson_mut_strncpy(d, e.text.data(), e.text.size());
                break;
            case Json: {
                yyjson_read_err err{};
                DocPtr parsed(yyjson_read_opts(const_cast<char*>(e.text.data()), e.text.size(), YYJSON_READ_NOFLAG, nullptr, &err));
                if (!parsed) fail("slot " + e.name + ": bad JSON value");
                v = yyjson_val_mut_copy(d, yyjson_doc_get_root(parsed.get()));
                break;
            }
            default: {
                v = yyjson_mut_obj(d);
                put(v, "$type", yyjson_mut_uint(d, e.type));
                const std::string h = hex_text(e.raw);
                put(v, "$hex", yyjson_mut_strncpy(d, h.data(), h.size()));
                break;
            }
        }
        put(slots, e.name, v);
    }
    put(root, "slots", slots);
    std::size_t len = 0;
    CharPtr text(yyjson_mut_write_opts(d, YYJSON_WRITE_PRETTY_TWO_SPACES | YYJSON_WRITE_NEWLINE_AT_END, nullptr, &len, nullptr));
    if (!text) fail("cannot write JSON");
    std::vector<std::byte> out(len);
    std::memcpy(out.data(), text.get(), len);
    return out;
}

std::vector<std::byte> write_binary(const Look& look) {
    std::vector<std::byte> out;
    out.push_back(std::byte{'O'});
    out.push_back(std::byte{'L'});
    out.push_back(static_cast<std::byte>(kVersionMajor));
    out.push_back(static_cast<std::byte>(kVersionMinor));
    put_varint(out, look.character.size());
    put_bytes(out, look.character.data(), look.character.size());
    put_varint(out, look.entries.size());
    std::unordered_set<std::string> seen;
    for (const Entry& e : look.entries) {
        check_name(look, e.name, seen);
        put_varint(out, e.name.size());
        put_bytes(out, e.name.data(), e.name.size());
        out.push_back(static_cast<std::byte>(e.type));
        switch (e.type) {
            case Remove:
                put_varint(out, 0);
                break;
            case Color:
                put_varint(out, 3);
                put_bytes(out, e.rgb, 3);
                break;
            case Bool:
                put_varint(out, 1);
                out.push_back(static_cast<std::byte>(e.flag ? 1 : 0));
                break;
            case Number: {
                std::uint32_t u;
                std::memcpy(&u, &e.number, 4);
                put_varint(out, 4);
                for (int i = 0; i < 4; ++i) out.push_back(static_cast<std::byte>((u >> (8 * i)) & 0xff));
                break;
            }
            case String:
            case Json:
                put_varint(out, e.text.size());
                put_bytes(out, e.text.data(), e.text.size());
                break;
            default:
                put_varint(out, e.raw.size());
                put_bytes(out, e.raw.data(), e.raw.size());
                break;
        }
    }
    return out;
}

}  // namespace cenda::omformat::omlook
