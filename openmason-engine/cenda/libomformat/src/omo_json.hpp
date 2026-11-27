// libomformat internals (OpenMason #167): JSON text exactly as the Java tools write it - Jackson's
// DefaultPrettyPrinter layout and Java's Float/Double.toString numbers - shared by the .omo and .omt writers.
#pragma once

#include "omformat/zip_archive.hpp"

#include <yyjson.h>

#include <charconv>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>
#include <string_view>
#include <system_error>
#include <type_traits>
#include <vector>

namespace cenda::omformat::jackson {

// Java's Float.toString / Double.toString (JDK 19+, Raffaello Giulietti's spec) of a finite value:
// the shortest decimal that rounds to the value (closest of those; when one digit suffices, a closer
// two-digit decimal wins), then plain notation for 1e-3 <= |d| < 1e7 ("0.001", "123.0", at least one
// fraction digit) and computerized scientific notation otherwise ("1.0E-4", "1.2345E7").
template <typename T>
void append_java_number(std::string& out, T value) {
    static_assert(std::is_same_v<T, float> || std::is_same_v<T, double>);
    if (value == T(0)) {
        out += std::signbit(value) ? "-0.0" : "0.0";
        return;
    }
    char buf[64];
    auto res = std::to_chars(buf, buf + sizeof buf, value, std::chars_format::scientific);
    std::string_view sci(buf, static_cast<std::size_t>(res.ptr - buf));

    // Split "[-]d[.ddd]e[+-]xx" into the sign, the significant digits and the exponent of the first digit.
    bool negative = false;
    std::size_t i = 0;
    if (sci[0] == '-') {
        negative = true;
        i = 1;
    }
    char digits[32];
    std::size_t n = 0;
    std::size_t epos = sci.find('e');
    for (std::size_t k = i; k < epos; ++k) {
        if (sci[k] != '.') digits[n++] = sci[k];
    }
    int exponent = 0;
    {
        std::size_t k = epos + 1;
        bool eneg = false;
        if (sci[k] == '+' || sci[k] == '-') {
            eneg = sci[k] == '-';
            ++k;
        }
        for (; k < sci.size(); ++k) exponent = exponent * 10 + (sci[k] - '0');
        if (eneg) exponent = -exponent;
    }
    while (n > 1 && digits[n - 1] == '0') --n;

    if (n == 1) {
        // Java considers two-digit decimals too when one digit is enough, and takes the closest
        // (matters only for the lowest subnormals: Double.MIN_VALUE prints as 4.9E-324, not 5.0E-324).
        char two[64];
        auto r2 = std::to_chars(two, two + sizeof two, value, std::chars_format::scientific, 1);
        T back{};
        auto parsed = std::from_chars(two, r2.ptr, back);
        std::string_view t(two, static_cast<std::size_t>(r2.ptr - two));
        const std::size_t d0 = t[0] == '-' ? 1 : 0;
        if (parsed.ec == std::errc{} && back == value && t[d0 + 2] != '0') {
            digits[0] = t[d0];
            digits[1] = t[d0 + 2];
            n = 2;
            int e2 = 0;
            std::size_t k = t.find('e') + 1;
            bool eneg = false;
            if (t[k] == '+' || t[k] == '-') {
                eneg = t[k] == '-';
                ++k;
            }
            for (; k < t.size(); ++k) e2 = e2 * 10 + (t[k] - '0');
            exponent = eneg ? -e2 : e2;
        }
    }

    if (negative) out += '-';
    if (exponent >= -3 && exponent < 7) {
        if (exponent >= 0) {
            const std::size_t int_digits = static_cast<std::size_t>(exponent) + 1;
            for (std::size_t k = 0; k < int_digits; ++k) out += k < n ? digits[k] : '0';
            out += '.';
            if (n > int_digits) {
                out.append(digits + int_digits, n - int_digits);
            } else {
                out += '0';
            }
        } else {
            out += "0.";
            out.append(static_cast<std::size_t>(-exponent - 1), '0');
            out.append(digits, n);
        }
    } else {
        out += digits[0];
        out += '.';
        if (n > 1) {
            out.append(digits + 1, n - 1);
        } else {
            out += '0';
        }
        out += 'E';
        char eb[16];
        auto er = std::to_chars(eb, eb + sizeof eb, exponent);
        out.append(eb, er.ptr);
    }
}

// Jackson's DefaultPrettyPrinter: objects indent by two spaces per level with "key" : value entries; arrays
// stay on one line ("[ a, b ]", empty "[ ]"), objects inside them break lines at the object's level; an empty
// object is "{ }". No trailing line break. The line separator is the writing JVM's (LF, or CRLF on Windows).
class Writer {
public:
    explicit Writer(bool crlf, std::size_t reserve = 0) : eol_(crlf ? "\r\n" : "\n") { out_.reserve(reserve); }

    std::string take() { return std::move(out_); }

    void begin_object() {
        before_value();
        out_ += '{';
        ++nesting_;
        stack_.push_back({true, 0});
    }

    void end_object() {
        const Frame f = stack_.back();
        stack_.pop_back();
        --nesting_;
        if (f.count > 0) {
            newline();
        } else {
            out_ += ' ';
        }
        out_ += '}';
    }

    void begin_array() {
        before_value();
        out_ += '[';
        stack_.push_back({false, 0});
    }

    void end_array() {
        stack_.pop_back();
        out_ += " ]";
    }

    void key(std::string_view name) {
        Frame& f = stack_.back();
        if (f.count > 0) out_ += ',';
        newline();
        ++f.count;
        quoted(name);
        out_ += " : ";
    }

    void null() {
        before_value();
        out_ += "null";
    }

    void boolean(bool v) {
        before_value();
        out_ += v ? "true" : "false";
    }

    void integer(std::int64_t v) {
        before_value();
        char buf[24];
        auto r = std::to_chars(buf, buf + sizeof buf, v);
        out_.append(buf, r.ptr);
    }

    // Jackson writes a non-finite float as a string ("NaN", "Infinity", "-Infinity") by default.
    template <typename T>
    void number(T v) {
        before_value();
        if (std::isnan(v)) {
            out_ += "\"NaN\"";
        } else if (std::isinf(v)) {
            out_ += v > 0 ? "\"Infinity\"" : "\"-Infinity\"";
        } else {
            append_java_number(out_, v);
        }
    }

    void string(std::string_view s) {
        before_value();
        quoted(s);
    }

    // A number's original text, verbatim (unknown sections).
    void raw(std::string_view text) {
        before_value();
        out_ += text;
    }

    // Convenience: "key" : value.
    void field(std::string_view k, std::string_view v) {
        key(k);
        string(v);
    }
    void field_int(std::string_view k, std::int64_t v) {
        key(k);
        integer(v);
    }
    void field_float(std::string_view k, float v) {
        key(k);
        number(v);
    }
    void field_bool(std::string_view k, bool v) {
        key(k);
        boolean(v);
    }

    // A parsed yyjson value (numbers read as raw text keep their spelling).
    void value(yyjson_val* v) {
        switch (yyjson_get_type(v)) {
            case YYJSON_TYPE_NULL:
                null();
                break;
            case YYJSON_TYPE_BOOL:
                boolean(yyjson_get_bool(v));
                break;
            case YYJSON_TYPE_RAW:
                raw({yyjson_get_raw(v), yyjson_get_len(v)});
                break;
            case YYJSON_TYPE_NUM:
                if (yyjson_is_uint(v)) {
                    before_value();
                    char buf[24];
                    auto r = std::to_chars(buf, buf + sizeof buf, yyjson_get_uint(v));
                    out_.append(buf, r.ptr);
                } else if (yyjson_is_sint(v)) {
                    integer(yyjson_get_sint(v));
                } else {
                    number(yyjson_get_real(v));
                }
                break;
            case YYJSON_TYPE_STR:
                string({yyjson_get_str(v), yyjson_get_len(v)});
                break;
            case YYJSON_TYPE_ARR: {
                begin_array();
                std::size_t idx = 0;
                std::size_t max = 0;
                yyjson_val* e = nullptr;
                yyjson_arr_foreach(v, idx, max, e) { value(e); }
                end_array();
                break;
            }
            case YYJSON_TYPE_OBJ: {
                begin_object();
                std::size_t idx = 0;
                std::size_t max = 0;
                yyjson_val* k = nullptr;
                yyjson_val* e = nullptr;
                yyjson_obj_foreach(v, idx, max, k, e) {
                    key({yyjson_get_str(k), yyjson_get_len(k)});
                    value(e);
                }
                end_object();
                break;
            }
            default:
                null();
                break;
        }
    }

private:
    struct Frame {
        bool object;
        int count;
    };

    void before_value() {
        if (stack_.empty()) return;
        Frame& f = stack_.back();
        if (f.object) return;  // the key wrote its separator
        out_ += f.count > 0 ? ", " : " ";
        ++f.count;
    }

    void newline() {
        out_ += eol_;
        out_.append(static_cast<std::size_t>(nesting_) * 2, ' ');
    }

    // Jackson's escaping: '"', '\\', the short escapes \b \t \n \f \r, other control characters as \u00XX
    // (upper-case hex); everything else (non-ASCII included) as is.
    void quoted(std::string_view s) {
        static constexpr char kHex[] = "0123456789ABCDEF";
        out_ += '"';
        for (const char ch : s) {
            const auto c = static_cast<unsigned char>(ch);
            switch (c) {
                case '"':
                    out_ += "\\\"";
                    break;
                case '\\':
                    out_ += "\\\\";
                    break;
                case '\b':
                    out_ += "\\b";
                    break;
                case '\t':
                    out_ += "\\t";
                    break;
                case '\n':
                    out_ += "\\n";
                    break;
                case '\f':
                    out_ += "\\f";
                    break;
                case '\r':
                    out_ += "\\r";
                    break;
                default:
                    if (c < 0x20) {
                        out_ += "\\u00";
                        out_ += kHex[c >> 4];
                        out_ += kHex[c & 0xF];
                    } else {
                        out_ += ch;
                    }
            }
        }
        out_ += '"';
    }

    std::string out_;
    const char* eol_;
    int nesting_ = 0;
    std::vector<Frame> stack_;
};

}  // namespace cenda::omformat::jackson
