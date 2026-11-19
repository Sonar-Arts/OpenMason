#include "json.hpp"

#include <cmath>
#include <cstdio>
#include <cstdlib>

namespace cenda::infer {

class JsonParser {
public:
    explicit JsonParser(const std::string& t) : t_(t) {}

    Json run() {
        Json v = value(0);
        ws();
        if (p_ != t_.size()) fail("trailing characters");
        return v;
    }

private:
    const std::string& t_;
    size_t p_ = 0;

    [[noreturn]] void fail(const char* what) const {
        throw std::runtime_error(std::string("JSON: ") + what + " at offset " + std::to_string(p_));
    }
    void ws() {
        while (p_ < t_.size() && (t_[p_] == ' ' || t_[p_] == '\n' || t_[p_] == '\r' || t_[p_] == '\t')) ++p_;
    }
    char peek() {
        ws();
        if (p_ >= t_.size()) fail("unexpected end");
        return t_[p_];
    }
    void expect(char c) {
        if (peek() != c) fail("unexpected character");
        ++p_;
    }
    bool lit(const char* s) {
        size_t n = std::char_traits<char>::length(s);
        if (t_.compare(p_, n, s) == 0) {
            p_ += n;
            return true;
        }
        return false;
    }

    Json value(int depth) {
        if (depth > 64) fail("nesting too deep");
        Json v;
        char c = peek();
        if (c == '{') {
            ++p_;
            v.type_ = Json::Type::Object;
            if (peek() == '}') {
                ++p_;
                return v;
            }
            for (;;) {
                if (peek() != '"') fail("expected key");
                std::string k = string();
                expect(':');
                v.o_.emplace_back(std::move(k), value(depth + 1));
                c = peek();
                ++p_;
                if (c == '}') break;
                if (c != ',') fail("expected , or }");
            }
        } else if (c == '[') {
            ++p_;
            v.type_ = Json::Type::Array;
            if (peek() == ']') {
                ++p_;
                return v;
            }
            for (;;) {
                v.a_.push_back(value(depth + 1));
                c = peek();
                ++p_;
                if (c == ']') break;
                if (c != ',') fail("expected , or ]");
            }
        } else if (c == '"') {
            v.type_ = Json::Type::String;
            v.s_ = string();
        } else if (lit("true")) {
            v.type_ = Json::Type::Bool;
            v.b_ = true;
        } else if (lit("false")) {
            v.type_ = Json::Type::Bool;
        } else if (lit("null")) {
            v.type_ = Json::Type::Null;
        } else {
            number(v);
        }
        return v;
    }

    void number(Json& v) {
        size_t s = p_;
        bool integral = true;
        if (p_ < t_.size() && t_[p_] == '-') ++p_;
        while (p_ < t_.size()) {
            char c = t_[p_];
            if (c >= '0' && c <= '9') {
                ++p_;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                integral = false;
                ++p_;
            } else {
                break;
            }
        }
        if (s == p_) fail("bad value");
        std::string tok = t_.substr(s, p_ - s);
        char* end = nullptr;
        v.type_ = Json::Type::Number;
        v.n_ = std::strtod(tok.c_str(), &end);
        if (end == tok.c_str()) fail("bad number");
        if (integral) {
            v.i_ = std::strtoll(tok.c_str(), nullptr, 10);
            v.integral_ = true;
        } else {
            v.i_ = static_cast<int64_t>(v.n_);
            v.integral_ = std::floor(v.n_) == v.n_;
        }
    }

    static void utf8(std::string& out, uint32_t cp) {
        if (cp < 0x80) {
            out += static_cast<char>(cp);
        } else if (cp < 0x800) {
            out += static_cast<char>(0xC0 | (cp >> 6));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += static_cast<char>(0xE0 | (cp >> 12));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (cp >> 18));
            out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }

    uint32_t hex4() {
        if (p_ + 4 > t_.size()) fail("bad escape");
        uint32_t v = 0;
        for (int i = 0; i < 4; ++i) {
            char c = t_[p_++];
            v <<= 4;
            if (c >= '0' && c <= '9') v |= static_cast<uint32_t>(c - '0');
            else if (c >= 'a' && c <= 'f') v |= static_cast<uint32_t>(c - 'a' + 10);
            else if (c >= 'A' && c <= 'F') v |= static_cast<uint32_t>(c - 'A' + 10);
            else fail("bad escape");
        }
        return v;
    }

    std::string string() {
        expect('"');
        std::string out;
        while (true) {
            if (p_ >= t_.size()) fail("unterminated string");
            char c = t_[p_++];
            if (c == '"') break;
            if (c != '\\') {
                out += c;
                continue;
            }
            if (p_ >= t_.size()) fail("bad escape");
            char e = t_[p_++];
            switch (e) {
            case '"': out += '"'; break;
            case '\\': out += '\\'; break;
            case '/': out += '/'; break;
            case 'b': out += '\b'; break;
            case 'f': out += '\f'; break;
            case 'n': out += '\n'; break;
            case 'r': out += '\r'; break;
            case 't': out += '\t'; break;
            case 'u': {
                uint32_t cp = hex4();
                if (cp >= 0xD800 && cp < 0xDC00 && p_ + 1 < t_.size() && t_[p_] == '\\' && t_[p_ + 1] == 'u') {
                    p_ += 2;
                    uint32_t lo = hex4();
                    cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                }
                utf8(out, cp);
                break;
            }
            default: fail("bad escape");
            }
        }
        return out;
    }
};

Json Json::parse(const std::string& text) { return JsonParser(text).run(); }

bool Json::has(const std::string& key) const {
    if (type_ != Type::Object) return false;
    for (const auto& m : o_)
        if (m.first == key) return true;
    return false;
}

const Json& Json::operator[](const std::string& key) const {
    if (type_ == Type::Object)
        for (const auto& m : o_)
            if (m.first == key) return m.second;
    throw std::runtime_error("JSON: missing key '" + key + "'");
}

const Json& Json::operator[](size_t i) const {
    if (type_ != Type::Array || i >= a_.size()) throw std::runtime_error("JSON: index out of range");
    return a_[i];
}

size_t Json::size() const { return type_ == Type::Array ? a_.size() : type_ == Type::Object ? o_.size() : 0; }

const std::vector<Json>& Json::items() const {
    if (type_ != Type::Array) throw std::runtime_error("JSON: not an array");
    return a_;
}

const std::vector<std::pair<std::string, Json>>& Json::members() const {
    if (type_ != Type::Object) throw std::runtime_error("JSON: not an object");
    return o_;
}

double Json::num() const {
    if (type_ != Type::Number) throw std::runtime_error("JSON: not a number");
    return n_;
}

int64_t Json::i64() const {
    if (type_ != Type::Number || !integral_) throw std::runtime_error("JSON: not an integer");
    return i_;
}

int32_t Json::i32() const {
    int64_t v = i64();
    if (v < INT32_MIN || v > INT32_MAX) throw std::runtime_error("JSON: integer out of range");
    return static_cast<int32_t>(v);
}

bool Json::boolean() const {
    if (type_ != Type::Bool) throw std::runtime_error("JSON: not a bool");
    return b_;
}

const std::string& Json::str() const {
    if (type_ != Type::String) throw std::runtime_error("JSON: not a string");
    return s_;
}

int64_t Json::i64_or(const std::string& key, int64_t fallback) const {
    if (!has(key) || (*this)[key].is_null()) return fallback;
    return (*this)[key].i64();
}

double Json::num_or(const std::string& key, double fallback) const {
    if (!has(key) || (*this)[key].is_null()) return fallback;
    return (*this)[key].num();
}

std::string Json::str_or(const std::string& key, const std::string& fallback) const {
    if (!has(key) || (*this)[key].is_null()) return fallback;
    return (*this)[key].str();
}

std::string json_quote(const std::string& s) {
    std::string o = "\"";
    for (char c : s) {
        switch (c) {
        case '"': o += "\\\""; break;
        case '\\': o += "\\\\"; break;
        case '\n': o += "\\n"; break;
        case '\r': o += "\\r"; break;
        case '\t': o += "\\t"; break;
        default:
            if (static_cast<unsigned char>(c) < 0x20) {
                char buf[8];
                std::snprintf(buf, sizeof buf, "\\u%04x", static_cast<unsigned>(static_cast<unsigned char>(c)));
                o += buf;
            } else {
                o += c;
            }
        }
    }
    return o + "\"";
}

} // namespace cenda::infer
