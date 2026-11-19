// Minimal JSON (RFC 8259) reader and writer for the inference engine's model manifests: objects, arrays,
// strings (UTF-8, \u escapes), numbers (as double + exact int64 when integral), true / false / null.
#pragma once

#include <cstdint>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace cenda::infer {

class Json {
public:
    enum class Type { Null, Bool, Number, String, Array, Object };

    Json() = default;
    static Json parse(const std::string& text); // throws std::runtime_error

    Type type() const { return type_; }
    bool is_null() const { return type_ == Type::Null; }
    bool is_object() const { return type_ == Type::Object; }
    bool is_array() const { return type_ == Type::Array; }
    bool has(const std::string& key) const;

    const Json& operator[](const std::string& key) const; // throws if missing
    const Json& operator[](size_t i) const;
    size_t size() const;
    const std::vector<Json>& items() const;
    const std::vector<std::pair<std::string, Json>>& members() const;

    double num() const;
    int64_t i64() const;
    int32_t i32() const;
    bool boolean() const;
    const std::string& str() const;

    // Value of `key` or `fallback` when absent / null.
    int64_t i64_or(const std::string& key, int64_t fallback) const;
    double num_or(const std::string& key, double fallback) const;
    std::string str_or(const std::string& key, const std::string& fallback) const;

private:
    Type type_ = Type::Null;
    bool b_ = false;
    double n_ = 0.0;
    int64_t i_ = 0;
    bool integral_ = false;
    std::string s_;
    std::vector<Json> a_;
    std::vector<std::pair<std::string, Json>> o_;
    friend class JsonParser;
};

// JSON string literal for `s` (quotes included).
std::string json_quote(const std::string& s);

} // namespace cenda::infer
