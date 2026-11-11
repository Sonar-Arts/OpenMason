// Cenda audio engine (ca_*): see include/cenda/audio.h for the contract.
//
// Threads:
//  - host threads: ca_submit / ca_sound_* / ca_voice_state / ca_update. They decode, queue
//    commands under queue_mutex and never touch voices.
//  - the mixer (miniaudio's device thread, or the ca_render caller offline): owns voices, buses
//    and the listener. It takes queued commands with try_lock, so it never waits on a host.
//  - the streamer (device modes only): decodes streamed sounds into one ring per streamed voice
//    and deletes streams the mixer has let go of.
// The mixer never allocates or frees: freed sounds go back to the host through a lock-free
// garbage ring that ca_update drains, and streams are reaped by the streamer.

#include "cenda/audio.h"

#include <miniaudio.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstddef>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr int32_t kBlock = 256;            // frames per mixing block: ramps and commands step at this rate
constexpr int32_t kDefaultVoices = 96;
constexpr int32_t kMaxVoices = 1024;
constexpr int32_t kSoundCapacity = 65536;  // sound ids are never reused
constexpr int32_t kGarbageCapacity = 4096;
constexpr int32_t kWindowFrames = 4096;    // per-voice stream window (frames)
constexpr float kMaxStep = 8.0f;           // source frames consumed per output frame, at most
constexpr float kMinPitch = 0.0625f;
constexpr float kMaxPitch = 8.0f;
constexpr float kCentre = 0.70710678f;     // equal-power centre gain
constexpr float kPi = 3.14159265358979f;
constexpr int32_t kCmdRetire = 100;        // internal: drop a freed sound's voices, hand it to the host

thread_local std::string t_error;

void set_error(const std::string& text) { t_error = text; }

struct Sound {
    int32_t channels = 0;
    int32_t rate = 0;
    int64_t frames = 0;  // -1 = unknown (stream)
    std::vector<float> pcm;
    std::shared_ptr<const std::vector<uint8_t>> encoded;  // non-null = streamed

    bool streamed() const { return encoded != nullptr; }
    int64_t bytes() const {
        return streamed() ? static_cast<int64_t>(encoded->size())
                          : static_cast<int64_t>(pcm.size() * sizeof(float));
    }
};

// Single-producer single-consumer float ring with monotonic counters.
class FloatRing {
public:
    void init(size_t capacity) {
        buf_.assign(capacity, 0.0f);
        head_.store(0, std::memory_order_relaxed);
        tail_.store(0, std::memory_order_relaxed);
    }
    size_t readable() const {
        return head_.load(std::memory_order_acquire) - tail_.load(std::memory_order_acquire);
    }
    size_t writable() const { return buf_.size() - readable(); }
    size_t write(const float* src, size_t n) {
        const size_t head = head_.load(std::memory_order_relaxed);
        n = std::min(n, buf_.size() - (head - tail_.load(std::memory_order_acquire)));
        for (size_t i = 0; i < n; ++i) buf_[(head + i) % buf_.size()] = src[i];
        head_.store(head + n, std::memory_order_release);
        return n;
    }
    size_t read(float* dst, size_t n) {
        const size_t tail = tail_.load(std::memory_order_relaxed);
        n = std::min(n, head_.load(std::memory_order_acquire) - tail);
        for (size_t i = 0; i < n; ++i) dst[i] = buf_[(tail + i) % buf_.size()];
        tail_.store(tail + n, std::memory_order_release);
        return n;
    }

private:
    std::vector<float> buf_;
    std::atomic<size_t> head_{0};
    std::atomic<size_t> tail_{0};
};

// One streamed voice's decoder. Filled by one thread at a time (the streamer, or ca_render).
struct Stream {
    std::shared_ptr<const std::vector<uint8_t>> encoded;
    ma_decoder decoder{};
    bool decoder_ready = false;
    bool looping = false;
    int32_t channels = 0;
    FloatRing ring;
    std::atomic<bool> released{false};  // the mixer let go (or never took it): reap
    std::atomic<bool> eof{false};       // the decoder is finished; what is in the ring is all

    Stream() = default;
    Stream(const Stream&) = delete;
    Stream& operator=(const Stream&) = delete;
    ~Stream() {
        if (decoder_ready) ma_decoder_uninit(&decoder);
    }

    // Decodes into the ring until it holds `target` floats (or is full / at the end).
    void fill(std::vector<float>& scratch, size_t target) {
        if (eof.load(std::memory_order_relaxed) || released.load(std::memory_order_acquire)) return;
        const size_t ch = static_cast<size_t>(channels);
        bool just_seeked = false;
        while (ring.readable() < target) {
            const size_t room = std::min(ring.writable(), target - ring.readable()) / ch;
            if (room == 0) return;
            const ma_uint64 want = std::min<ma_uint64>(room, scratch.size() / ch);
            ma_uint64 got = 0;
            const ma_result r = ma_decoder_read_pcm_frames(&decoder, scratch.data(), want, &got);
            if (got > 0) {
                ring.write(scratch.data(), static_cast<size_t>(got) * ch);
                just_seeked = false;
            }
            if (got < want || (r != MA_SUCCESS && r != MA_AT_END)) {
                // An empty (or broken) file read straight after a seek would spin forever.
                if (!looping || just_seeked || (r != MA_SUCCESS && r != MA_AT_END && got == 0) ||
                    ma_decoder_seek_to_pcm_frame(&decoder, 0) != MA_SUCCESS) {
                    eof.store(true, std::memory_order_release);
                    return;
                }
                just_seeked = true;
            }
        }
    }
};

// Linear ramp of one scalar toward a target, advanced in whole blocks.
struct Ramp {
    float cur = 1.0f;
    float target = 1.0f;
    float step = 0.0f;  // per sample

    void reset(float value) {
        cur = target = value;
        step = 0.0f;
    }
    void set(float value, float seconds, float rate) {
        target = value;
        if (seconds <= 0.0f || cur == value) {
            cur = value;
            step = 0.0f;
        } else {
            step = std::fabs(value - cur) / (seconds * rate);
        }
    }
    float advance(int32_t n) {
        if (cur != target) {
            const float d = step * static_cast<float>(n);
            cur = cur < target ? std::min(target, cur + d) : std::max(target, cur - d);
        }
        return cur;
    }
};

struct Voice {
    uint64_t id = 0;  // 0 = free slot
    const Sound* sound = nullptr;
    Stream* stream = nullptr;
    int32_t bus = 0;
    int32_t flags = 0;
    int32_t priority = 0;
    double pos = 0.0;  // source frames (absolute, also for streams)
    float pitch = 1.0f;
    Ramp gain;
    Ramp env;  // fade-in / fade-out envelope
    bool stopping = false;
    bool fresh = true;
    bool paused = false;
    float x = 0.0f, y = 0.0f, z = 0.0f;
    float ref = 1.0f, max_dist = 50.0f, rolloff = 1.0f;
    float prev_l = 0.0f, prev_r = 0.0f;
    float last_att = 1.0f;
    std::vector<float> window;  // streams: decoded frames [win_start, win_start + win_len)
    int64_t win_start = 0;
    int32_t win_len = 0;
};

struct Bus {
    Ramp gain;
    Ramp coef;  // one-pole low-pass coefficient; 1 = off
    float lp_l = 0.0f, lp_r = 0.0f;
    std::vector<float> buf;
};

struct Listener {
    float px = 0.0f, py = 0.0f, pz = 0.0f;
    float rx = 1.0f, ry = 0.0f, rz = 0.0f;  // right axis (forward x up)
};

struct InternalCmd {
    ca_command c{};
    Stream* stream = nullptr;  // PLAY of a streamed sound: created by the submitting thread
    Sound* retire = nullptr;   // kCmdRetire
};

float finite_or(float v, float fallback) { return std::isfinite(v) ? v : fallback; }

float lowpass_coef(float cutoff_hz, float rate) {
    if (!(cutoff_hz > 0.0f) || cutoff_hz >= rate * 0.45f) return 1.0f;
    return 1.0f - std::exp(-2.0f * kPi * cutoff_hz / rate);
}

float soft_limit(float x) {
    const float a = std::fabs(x);
    if (a <= 0.8f) return x;
    return std::copysign(0.8f + 0.2f * std::tanh((a - 0.8f) / 0.2f), x);
}

int64_t now_nanos() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

}  // namespace

struct ca_engine {
    int32_t mode = CA_DEVICE_OFFLINE;
    int32_t rate = 48000;
    std::string device_name = "offline";

    ma_context context{};
    bool context_ready = false;
    ma_device device{};
    bool device_ready = false;

    // Sounds: the table is written by hosts under sound_mutex and read lock-free by the mixer.
    std::mutex sound_mutex;
    std::unique_ptr<std::atomic<Sound*>[]> sounds;
    int32_t next_sound = 1;
    std::array<std::atomic<Sound*>, kGarbageCapacity> garbage{};
    std::atomic<uint32_t> garbage_head{0};  // written by the mixer
    std::atomic<uint32_t> garbage_tail{0};  // written by ca_update

    // Commands.
    std::mutex queue_mutex;
    std::vector<InternalCmd> pending;     // hosts append (queue_mutex)
    std::vector<InternalCmd> processing;  // the mixer's (swapped in)
    uint64_t next_voice_id = 1;           // queue_mutex
    std::atomic<uint64_t> processed_play{0};  // highest play id whose outcome is published
    uint64_t block_play_max = 0;              // mixer: highest play id applied so far

    // Mixer state.
    std::vector<Voice> voices;
    std::unique_ptr<std::atomic<uint64_t>[]> published;  // per slot: (id << 2) | state
    std::array<Bus, CA_BUS_COUNT> buses;
    Listener listener;
    int32_t active_peak = 0;

    // Streams.
    std::mutex stream_mutex;
    std::condition_variable stream_cv;
    std::vector<std::unique_ptr<Stream>> streams;
    std::vector<float> stream_scratch;
    bool streamer_stop = false;
    std::thread streamer;

    std::array<std::atomic<int64_t>, CA_STAT_COUNT> stats{};

    void stat_add(int slot, int64_t v) { stats[static_cast<size_t>(slot)].fetch_add(v, std::memory_order_relaxed); }
    void stat_set(int slot, int64_t v) { stats[static_cast<size_t>(slot)].store(v, std::memory_order_relaxed); }

    // ---- streams --------------------------------------------------------------------------

    size_t stream_target(const Stream& s) const {
        // Keep half a second decoded ahead (the ring holds a second).
        return static_cast<size_t>(rate / 2) * static_cast<size_t>(s.channels);
    }

    // Fills every live stream and deletes released ones. Caller holds stream_mutex (or is the
    // only thread, offline).
    void service_streams() {
        for (auto it = streams.begin(); it != streams.end();) {
            Stream& s = **it;
            if (s.released.load(std::memory_order_acquire)) {
                it = streams.erase(it);
                continue;
            }
            s.fill(stream_scratch, stream_target(s));
            ++it;
        }
        stat_set(CA_STAT_STREAMS, static_cast<int64_t>(streams.size()));
    }

    void streamer_loop() {
        std::unique_lock<std::mutex> lock(stream_mutex);
        while (!streamer_stop) {
            service_streams();
            stream_cv.wait_for(lock, std::chrono::milliseconds(5));
        }
    }

    // ---- commands (mixer side) ------------------------------------------------------------

    void drain_commands(bool blocking) {
        if (blocking) {
            queue_mutex.lock();
        } else if (!queue_mutex.try_lock()) {
            return;
        }
        std::swap(pending, processing);
        queue_mutex.unlock();
        for (InternalCmd& ic : processing) apply(ic);
        stat_add(CA_STAT_COMMANDS, static_cast<int64_t>(processing.size()));
        processing.clear();
    }

    Voice* find_voice(uint64_t id) {
        if (id == 0) return nullptr;
        for (Voice& v : voices) {
            if (v.id == id) return &v;
        }
        return nullptr;
    }

    void free_voice(Voice& v) {
        if (v.stream != nullptr) v.stream->released.store(true, std::memory_order_release);
        v.id = 0;
        v.sound = nullptr;
        v.stream = nullptr;
    }

    void stop_voice(Voice& v, float fade) {
        if (fade <= 0.0f) {
            free_voice(v);
        } else {
            v.env.set(0.0f, fade, static_cast<float>(rate));
            v.stopping = true;
        }
    }

    void spatial_gains(const Voice& v, float& att, float& pan_l, float& pan_r) const {
        const float dx = v.x - listener.px, dy = v.y - listener.py, dz = v.z - listener.pz;
        const float d = std::sqrt(dx * dx + dy * dy + dz * dz);
        const float ref = v.ref;
        const float max_d = std::max(v.max_dist, ref);
        const float dc = std::clamp(d, ref, max_d);
        const float denom = ref + v.rolloff * (dc - ref);
        att = denom > 0.0f ? ref / denom : 1.0f;
        float px = 0.0f;
        if (d > 1e-5f) {
            px = (dx * listener.rx + dy * listener.ry + dz * listener.rz) / d;
            px *= std::min(1.0f, d / ref);  // centre a source inside its ref distance
        }
        const float angle = (std::clamp(px, -1.0f, 1.0f) + 1.0f) * (kPi / 4.0f);
        pan_l = std::cos(angle);
        pan_r = std::sin(angle);
    }

    float loudness_left(const Voice& v) const {
        if (v.stopping) return 0.0f;
        float remaining = 1.0f;
        if (!(v.flags & CA_PLAY_LOOP) && v.sound != nullptr && !v.sound->streamed() && v.sound->frames > 0) {
            remaining = std::max(0.0f, 1.0f - static_cast<float>(v.pos / static_cast<double>(v.sound->frames)));
        }
        return std::fabs(v.gain.target) * v.env.target * v.last_att * remaining;
    }

    // A free slot, or the least important voice if the new one outranks it; -1 = drop the play.
    int32_t claim_slot(int32_t priority, float loudness) {
        int32_t victim = -1;
        for (size_t i = 0; i < voices.size(); ++i) {
            if (voices[i].id == 0) return static_cast<int32_t>(i);
            const Voice& v = voices[i];
            if (victim < 0) {
                victim = static_cast<int32_t>(i);
                continue;
            }
            const Voice& w = voices[static_cast<size_t>(victim)];
            if (v.priority < w.priority || (v.priority == w.priority && loudness_left(v) < loudness_left(w))) {
                victim = static_cast<int32_t>(i);
            }
        }
        if (victim < 0) return -1;
        Voice& w = voices[static_cast<size_t>(victim)];
        if (priority > w.priority || (priority == w.priority && loudness > loudness_left(w))) {
            free_voice(w);
            stat_add(CA_STAT_VOICES_STOLEN, 1);
            return victim;
        }
        return -1;
    }

    void apply_play(InternalCmd& ic) {
        const ca_command& c = ic.c;
        block_play_max = std::max(block_play_max, c.voice);
        Sound* s = (c.sound > 0 && c.sound < kSoundCapacity)
                       ? sounds[static_cast<size_t>(c.sound)].load(std::memory_order_acquire)
                       : nullptr;
        auto drop = [&] {
            if (ic.stream != nullptr) ic.stream->released.store(true, std::memory_order_release);
            stat_add(CA_STAT_PLAYS_DROPPED, 1);
        };
        if (s == nullptr || s->streamed() != (ic.stream != nullptr)) {
            drop();
            return;
        }
        Voice probe;
        probe.flags = c.flags;
        probe.x = finite_or(c.f[2], 0.0f);
        probe.y = finite_or(c.f[3], 0.0f);
        probe.z = finite_or(c.f[4], 0.0f);
        if (c.f[5] == 0.0f && c.f[6] == 0.0f && c.f[7] == 0.0f) {
            probe.ref = 1.0f;
            probe.max_dist = 50.0f;
            probe.rolloff = 1.0f;
        } else {
            probe.ref = c.f[5] > 0.0f ? c.f[5] : 1.0f;
            probe.max_dist = c.f[6] > 0.0f ? c.f[6] : 50.0f;
            probe.rolloff = c.f[7] >= 0.0f ? c.f[7] : 1.0f;
        }
        const float gain = std::max(0.0f, finite_or(c.f[0], 0.0f));
        float att = 1.0f, pl = kCentre, pr = kCentre;
        if (c.flags & CA_PLAY_SPATIAL) spatial_gains(probe, att, pl, pr);

        const int32_t slot = claim_slot(c.priority, gain * att);
        if (slot < 0) {
            drop();
            return;
        }
        Voice& v = voices[static_cast<size_t>(slot)];
        v.id = c.voice;
        v.sound = s;
        v.stream = ic.stream;
        v.bus = c.bus;
        v.flags = c.flags;
        v.priority = c.priority;
        const float pitch = c.f[1] > 0.0f && std::isfinite(c.f[1]) ? c.f[1] : 1.0f;
        v.pitch = std::clamp(pitch, kMinPitch, kMaxPitch);
        v.gain.reset(gain);
        if (c.f[8] > 0.0f) {
            v.env.reset(0.0f);
            v.env.set(1.0f, c.f[8], static_cast<float>(rate));
        } else {
            v.env.reset(1.0f);
        }
        v.stopping = false;
        v.fresh = true;
        v.paused = false;
        v.x = probe.x;
        v.y = probe.y;
        v.z = probe.z;
        v.ref = probe.ref;
        v.max_dist = probe.max_dist;
        v.rolloff = probe.rolloff;
        v.last_att = att;
        v.pos = 0.0;
        if (!s->streamed() && c.f[9] > 0.0f && s->frames > 0) {
            v.pos = std::min(static_cast<double>(c.f[9]) * s->rate, static_cast<double>(s->frames - 1));
        }
        v.win_start = 0;
        v.win_len = 0;
    }

    void apply(InternalCmd& ic) {
        const ca_command& c = ic.c;
        switch (c.kind) {
            case CA_CMD_PLAY:
                apply_play(ic);
                break;
            case CA_CMD_STOP:
                if (Voice* v = find_voice(c.voice)) stop_voice(*v, c.f[0]);
                break;
            case CA_CMD_VOICE_GAIN:
                if (Voice* v = find_voice(c.voice)) {
                    v->gain.set(std::max(0.0f, finite_or(c.f[0], v->gain.target)), c.f[1],
                                static_cast<float>(rate));
                }
                break;
            case CA_CMD_VOICE_PITCH:
                if (Voice* v = find_voice(c.voice)) {
                    if (c.f[0] > 0.0f && std::isfinite(c.f[0])) v->pitch = std::clamp(c.f[0], kMinPitch, kMaxPitch);
                }
                break;
            case CA_CMD_VOICE_POSITION:
                if (Voice* v = find_voice(c.voice)) {
                    v->x = finite_or(c.f[0], v->x);
                    v->y = finite_or(c.f[1], v->y);
                    v->z = finite_or(c.f[2], v->z);
                }
                break;
            case CA_CMD_LISTENER: {
                if (!std::isfinite(c.f[0]) || !std::isfinite(c.f[1]) || !std::isfinite(c.f[2])) break;
                listener.px = c.f[0];
                listener.py = c.f[1];
                listener.pz = c.f[2];
                const float fx = c.f[3], fy = c.f[4], fz = c.f[5];
                const float ux = c.f[6], uy = c.f[7], uz = c.f[8];
                float rx = fy * uz - fz * uy, ry = fz * ux - fx * uz, rz = fx * uy - fy * ux;
                const float len = std::sqrt(rx * rx + ry * ry + rz * rz);
                if (len > 1e-6f && std::isfinite(len)) {
                    rx /= len;
                    ry /= len;
                    rz /= len;
                    listener.rx = rx;
                    listener.ry = ry;
                    listener.rz = rz;
                }
                break;
            }
            case CA_CMD_BUS: {
                Bus& b = buses[static_cast<size_t>(c.bus)];
                const float ramp = std::max(0.0f, c.f[2]);
                b.gain.set(std::max(0.0f, finite_or(c.f[0], b.gain.target)), ramp, static_cast<float>(rate));
                b.coef.set(lowpass_coef(c.f[1], static_cast<float>(rate)), ramp, static_cast<float>(rate));
                break;
            }
            case CA_CMD_STOP_BUS:
                for (Voice& v : voices) {
                    if (v.id != 0 && v.bus == c.bus) stop_voice(v, c.f[0]);
                }
                break;
            case kCmdRetire: {
                for (Voice& v : voices) {
                    if (v.id != 0 && v.sound == ic.retire) free_voice(v);
                }
                const uint32_t head = garbage_head.load(std::memory_order_relaxed);
                if (head - garbage_tail.load(std::memory_order_acquire) < kGarbageCapacity) {
                    garbage[head % kGarbageCapacity].store(ic.retire, std::memory_order_relaxed);
                    garbage_head.store(head + 1, std::memory_order_release);
                }  // else: leaks one sound rather than freeing on the mixer thread
                ic.retire = nullptr;
                break;
            }
            default:
                break;
        }
    }

    // ---- mixing ---------------------------------------------------------------------------

    // Mixes one voice into its bus; returns false when the voice has finished.
    bool mix_voice(Voice& v, int32_t n) {
        const Sound& s = *v.sound;
        const bool spatial = (v.flags & CA_PLAY_SPATIAL) != 0;
        const bool loop = (v.flags & CA_PLAY_LOOP) != 0;
        const int32_t ch = s.channels;
        if (v.stopping && v.env.cur <= 0.0f) return false;  // the fade-out has played out

        float att = 1.0f, pan_l = kCentre, pan_r = kCentre;
        if (spatial) {
            spatial_gains(v, att, pan_l, pan_r);
        } else if (ch == 2) {
            pan_l = pan_r = 1.0f;
        }
        v.last_att = att;
        const float g0 = v.gain.cur * v.env.cur;
        const float g1 = v.gain.advance(n) * v.env.advance(n);
        const float end_l = g1 * att * pan_l, end_r = g1 * att * pan_r;
        float start_l = v.prev_l, start_r = v.prev_r;
        if (v.fresh) {
            start_l = g0 * att * pan_l;
            start_r = g0 * att * pan_r;
            v.fresh = false;
        }
        v.prev_l = end_l;
        v.prev_r = end_r;

        // A silent loop pauses where it is, so it resumes without a restart.
        v.paused = loop && end_l == 0.0f && end_r == 0.0f && start_l == 0.0f && start_r == 0.0f;
        if (v.paused) return true;

        const float step = std::min(kMaxStep, v.pitch * static_cast<float>(s.rate) / static_cast<float>(rate));
        float* out = buses[static_cast<size_t>(v.bus)].buf.data();
        const float inv_n = 1.0f / static_cast<float>(n);
        const bool mono_mix = ch == 1 || spatial;

        if (v.stream != nullptr) return mix_stream(v, n, step, start_l, start_r, end_l, end_r, out, mono_mix);

        const int64_t frames = s.frames;
        const float* pcm = s.pcm.data();
        auto sample = [&](int64_t i, int32_t c) -> float {
            if (i >= frames) {
                if (!loop) return 0.0f;
                i %= frames;
            }
            return pcm[i * ch + c];
        };
        for (int32_t i = 0; i < n; ++i) {
            const float t = static_cast<float>(i + 1) * inv_n;
            const float gl = start_l + (end_l - start_l) * t;
            const float gr = start_r + (end_r - start_r) * t;
            const int64_t i0 = static_cast<int64_t>(v.pos);
            const float frac = static_cast<float>(v.pos - static_cast<double>(i0));
            if (mono_mix) {
                float a = sample(i0, 0), b = sample(i0 + 1, 0);
                if (ch == 2) {
                    a = 0.5f * (a + sample(i0, 1));
                    b = 0.5f * (b + sample(i0 + 1, 1));
                }
                const float m = a + (b - a) * frac;
                out[2 * i] += m * gl;
                out[2 * i + 1] += m * gr;
            } else {
                const float l = sample(i0, 0) + (sample(i0 + 1, 0) - sample(i0, 0)) * frac;
                const float r = sample(i0, 1) + (sample(i0 + 1, 1) - sample(i0, 1)) * frac;
                out[2 * i] += l * gl;
                out[2 * i + 1] += r * gr;
            }
            v.pos += static_cast<double>(step);
            if (v.pos >= static_cast<double>(frames)) {
                if (!loop) return false;
                v.pos = std::fmod(v.pos, static_cast<double>(frames));
            }
        }
        return true;
    }

    bool mix_stream(Voice& v, int32_t n, float step, float start_l, float start_r, float end_l, float end_r,
                    float* out, bool mono_mix) {
        Stream& st = *v.stream;
        const int32_t ch = st.channels;
        // Slide the window to the current position, then top it up from the ring.
        const int64_t base = static_cast<int64_t>(v.pos);
        const int64_t drop = std::min<int64_t>(base - v.win_start, v.win_len);
        if (drop > 0) {
            const int32_t keep = v.win_len - static_cast<int32_t>(drop);
            std::memmove(v.window.data(), v.window.data() + drop * ch,
                         static_cast<size_t>(keep) * static_cast<size_t>(ch) * sizeof(float));
            v.win_len = keep;
            v.win_start += drop;
        }
        const int32_t need = std::min(kWindowFrames, static_cast<int32_t>(std::ceil(step * static_cast<float>(n))) + 3);
        if (v.win_len < need) {
            const size_t got = st.ring.read(v.window.data() + static_cast<size_t>(v.win_len) * static_cast<size_t>(ch),
                                            static_cast<size_t>(need - v.win_len) * static_cast<size_t>(ch));
            v.win_len += static_cast<int32_t>(got / static_cast<size_t>(ch));
        }
        const bool ended = st.eof.load(std::memory_order_acquire) && st.ring.readable() == 0;
        const float* w = v.window.data();
        const float inv_n = 1.0f / static_cast<float>(n);
        for (int32_t i = 0; i < n; ++i) {
            const double rel = v.pos - static_cast<double>(v.win_start);
            const int32_t i0 = static_cast<int32_t>(rel);
            if (i0 + 1 >= v.win_len) {
                if (ended) {
                    if (i0 >= v.win_len) return false;  // played the last frame
                } else {
                    stat_add(CA_STAT_STREAM_UNDERRUNS, 1);
                    return true;  // wait for the decoder; position holds
                }
            }
            const bool has_next = i0 + 1 < v.win_len;
            const float frac = static_cast<float>(rel - static_cast<double>(i0));
            const float t = static_cast<float>(i + 1) * inv_n;
            const float gl = start_l + (end_l - start_l) * t;
            const float gr = start_r + (end_r - start_r) * t;
            const float* f0 = w + i0 * ch;
            if (mono_mix) {
                float a = f0[0], b = has_next ? f0[ch] : 0.0f;
                if (ch == 2) {
                    a = 0.5f * (a + f0[1]);
                    b = has_next ? 0.5f * (b + f0[ch + 1]) : 0.0f;
                }
                const float m = a + (b - a) * frac;
                out[2 * i] += m * gl;
                out[2 * i + 1] += m * gr;
            } else {
                const float l1 = has_next ? f0[2] : 0.0f, r1 = has_next ? f0[3] : 0.0f;
                out[2 * i] += (f0[0] + (l1 - f0[0]) * frac) * gl;
                out[2 * i + 1] += (f0[1] + (r1 - f0[1]) * frac) * gr;
            }
            v.pos += static_cast<double>(step);
        }
        return true;
    }

    void process_bus(Bus& b, int32_t n) {
        float* buf = b.buf.data();
        const float c0 = b.coef.cur;
        const float c1 = b.coef.advance(n);
        if (c0 < 1.0f || c1 < 1.0f) {
            const float inv_n = 1.0f / static_cast<float>(n);
            for (int32_t i = 0; i < n; ++i) {
                const float a = c0 + (c1 - c0) * static_cast<float>(i + 1) * inv_n;
                b.lp_l += a * (buf[2 * i] - b.lp_l);
                b.lp_r += a * (buf[2 * i + 1] - b.lp_r);
                buf[2 * i] = b.lp_l;
                buf[2 * i + 1] = b.lp_r;
            }
        } else if (n > 0) {
            b.lp_l = buf[2 * (n - 1)];
            b.lp_r = buf[2 * (n - 1) + 1];
        }
        const float g0 = b.gain.cur;
        const float g1 = b.gain.advance(n);
        if (g0 == 1.0f && g1 == 1.0f) return;
        const float inv_n = 1.0f / static_cast<float>(n);
        for (int32_t i = 0; i < n; ++i) {
            const float g = g0 + (g1 - g0) * static_cast<float>(i + 1) * inv_n;
            buf[2 * i] *= g;
            buf[2 * i + 1] *= g;
        }
    }

    void mix_block(float* out, int32_t n) {
        for (Bus& b : buses) std::fill_n(b.buf.begin(), 2 * n, 0.0f);
        int32_t active = 0;
        for (size_t i = 0; i < voices.size(); ++i) {
            Voice& v = voices[i];
            if (v.id != 0 && !mix_voice(v, n)) free_voice(v);
            uint64_t packed = 0;
            if (v.id != 0) {
                ++active;
                packed = (v.id << 2) | static_cast<uint64_t>(v.paused ? CA_VOICE_PAUSED : CA_VOICE_PLAYING);
            }
            published[i].store(packed, std::memory_order_release);
        }
        Bus& master = buses[0];
        for (size_t b = 1; b < buses.size(); ++b) {
            process_bus(buses[b], n);
            const float* src = buses[b].buf.data();
            for (int32_t i = 0; i < 2 * n; ++i) master.buf[static_cast<size_t>(i)] += src[i];
        }
        process_bus(master, n);
        for (int32_t i = 0; i < 2 * n; ++i) out[i] = soft_limit(master.buf[static_cast<size_t>(i)]);
        active_peak = std::max(active_peak, active);
        stat_set(CA_STAT_VOICES_ACTIVE, active);
        stat_set(CA_STAT_VOICES_PEAK, active_peak);
        stat_add(CA_STAT_BLOCKS, 1);
    }

    void mix(float* out, int32_t frames) {
        const bool offline = mode == CA_DEVICE_OFFLINE;
        int32_t done = 0;
        while (done < frames) {
            const int32_t n = std::min(kBlock, frames - done);
            drain_commands(offline);
            if (offline) {
                std::lock_guard<std::mutex> lock(stream_mutex);
                service_streams();
            }
            mix_block(out + 2 * done, n);
            // Only now can ca_voice_state tell a played voice from a finished one.
            processed_play.store(block_play_max, std::memory_order_release);
            done += n;
        }
    }

    // ---- host-side helpers ----------------------------------------------------------------

    Sound* lookup(int32_t id) {
        if (id <= 0 || id >= kSoundCapacity) return nullptr;
        return sounds[static_cast<size_t>(id)].load(std::memory_order_acquire);
    }

    int32_t register_sound(std::unique_ptr<Sound> s) {
        std::lock_guard<std::mutex> lock(sound_mutex);
        if (next_sound >= kSoundCapacity) {
            set_error("sound table full");
            return CA_ERR_FULL;
        }
        const int32_t id = next_sound++;
        stat_add(CA_STAT_SOUNDS, 1);
        stat_add(CA_STAT_SOUND_BYTES, s->bytes());
        sounds[static_cast<size_t>(id)].store(s.release(), std::memory_order_release);
        return id;
    }

    void delete_sound(Sound* s) {
        stat_add(CA_STAT_SOUNDS, -1);
        stat_add(CA_STAT_SOUND_BYTES, -s->bytes());
        delete s;
    }

    // A decoder over a streamed sound's bytes, primed with a little audio. Null on failure.
    std::unique_ptr<Stream> open_stream(const Sound& s, bool looping) {
        auto st = std::make_unique<Stream>();
        st->encoded = s.encoded;
        st->looping = looping;
        st->channels = s.channels;
        ma_decoder_config cfg = ma_decoder_config_init(ma_format_f32, static_cast<ma_uint32>(s.channels), 0);
        if (ma_decoder_init_memory(st->encoded->data(), st->encoded->size(), &cfg, &st->decoder) != MA_SUCCESS) {
            return nullptr;
        }
        st->decoder_ready = true;
        st->ring.init(static_cast<size_t>(std::max(rate, 8192)) * static_cast<size_t>(s.channels));
        std::vector<float> scratch(static_cast<size_t>(4096 * s.channels));
        st->fill(scratch, static_cast<size_t>(rate / 8) * static_cast<size_t>(s.channels));
        return st;
    }
};

namespace {

void on_device_data(ma_device* device, void* output, const void*, ma_uint32 frames) {
    auto* e = static_cast<ca_engine*>(device->pUserData);
    const int64_t t0 = now_nanos();
    e->mix(static_cast<float*>(output), static_cast<int32_t>(frames));
    const int64_t took = now_nanos() - t0;
    e->stat_set(CA_STAT_MIX_NANOS_LAST, took);
    if (took > e->stats[CA_STAT_MIX_NANOS_MAX].load(std::memory_order_relaxed)) {
        e->stat_set(CA_STAT_MIX_NANOS_MAX, took);
    }
}

std::unique_ptr<Sound> decode_all(const void* bytes, size_t length) {
    ma_decoder decoder;
    ma_decoder_config cfg = ma_decoder_config_init(ma_format_f32, 0, 0);
    if (ma_decoder_init_memory(bytes, length, &cfg, &decoder) != MA_SUCCESS) {
        set_error("not a decodable audio file (WAV, FLAC, MP3 or Ogg Vorbis)");
        return nullptr;
    }
    ma_uint32 channels = decoder.outputChannels;
    if (channels > 2) {
        // Ask the decoder to downmix to stereo.
        ma_decoder_uninit(&decoder);
        cfg = ma_decoder_config_init(ma_format_f32, 2, 0);
        if (ma_decoder_init_memory(bytes, length, &cfg, &decoder) != MA_SUCCESS) {
            set_error("could not downmix the file to stereo");
            return nullptr;
        }
        channels = 2;
    }
    auto s = std::make_unique<Sound>();
    s->channels = static_cast<int32_t>(channels);
    s->rate = static_cast<int32_t>(decoder.outputSampleRate);
    constexpr ma_uint64 kChunk = 16384;
    std::vector<float> chunk(static_cast<size_t>(kChunk) * channels);
    for (;;) {
        ma_uint64 got = 0;
        const ma_result r = ma_decoder_read_pcm_frames(&decoder, chunk.data(), kChunk, &got);
        s->pcm.insert(s->pcm.end(), chunk.begin(), chunk.begin() + static_cast<std::ptrdiff_t>(got * channels));
        if (got < kChunk || r != MA_SUCCESS) break;
    }
    ma_decoder_uninit(&decoder);
    s->frames = static_cast<int64_t>(s->pcm.size() / channels);
    if (s->frames == 0 || s->rate <= 0) {
        set_error("the file decodes to no audio");
        return nullptr;
    }
    return s;
}

std::unique_ptr<Sound> probe_stream(const void* bytes, size_t length) {
    auto s = std::make_unique<Sound>();
    s->encoded = std::make_shared<const std::vector<uint8_t>>(static_cast<const uint8_t*>(bytes),
                                                              static_cast<const uint8_t*>(bytes) + length);
    ma_decoder decoder;
    ma_decoder_config cfg = ma_decoder_config_init(ma_format_f32, 0, 0);
    if (ma_decoder_init_memory(s->encoded->data(), s->encoded->size(), &cfg, &decoder) != MA_SUCCESS) {
        set_error("not a decodable audio file (WAV, FLAC, MP3 or Ogg Vorbis)");
        return nullptr;
    }
    s->channels = static_cast<int32_t>(std::min<ma_uint32>(decoder.outputChannels, 2));
    s->rate = static_cast<int32_t>(decoder.outputSampleRate);
    ma_uint64 length_frames = 0;
    s->frames = ma_decoder_get_length_in_pcm_frames(&decoder, &length_frames) == MA_SUCCESS && length_frames > 0
                    ? static_cast<int64_t>(length_frames)
                    : -1;
    ma_decoder_uninit(&decoder);
    if (s->channels <= 0 || s->rate <= 0) {
        set_error("the file has no channels or sample rate");
        return nullptr;
    }
    return s;
}

}  // namespace

extern "C" {

int32_t ca_abi_version(void) { return CA_ABI_VERSION; }

const char* ca_last_error(void) { return t_error.c_str(); }

ca_engine* ca_engine_create(int32_t device_mode, int32_t sample_rate, int32_t max_voices) {
    if (device_mode < CA_DEVICE_DEFAULT || device_mode > CA_DEVICE_OFFLINE) {
        set_error("unknown device mode");
        return nullptr;
    }
    if (device_mode == CA_DEVICE_OFFLINE && sample_rate <= 0) {
        set_error("an offline engine needs a sample rate");
        return nullptr;
    }
    auto e = std::make_unique<ca_engine>();
    e->mode = device_mode;
    e->sounds = std::make_unique<std::atomic<Sound*>[]>(static_cast<size_t>(kSoundCapacity));
    const int32_t voice_count = std::clamp(max_voices > 0 ? max_voices : kDefaultVoices, 1, kMaxVoices);
    e->voices.resize(static_cast<size_t>(voice_count));
    for (Voice& v : e->voices) v.window.assign(static_cast<size_t>(kWindowFrames) * 2, 0.0f);
    e->published = std::make_unique<std::atomic<uint64_t>[]>(static_cast<size_t>(voice_count));
    for (Bus& b : e->buses) b.buf.assign(static_cast<size_t>(kBlock) * 2, 0.0f);
    e->pending.reserve(256);
    e->processing.reserve(256);
    e->stream_scratch.assign(4096 * 2, 0.0f);

    if (device_mode == CA_DEVICE_OFFLINE) {
        e->rate = sample_rate;
        return e.release();
    }

    ma_backend null_backend = ma_backend_null;
    const bool null_mode = device_mode == CA_DEVICE_NULL;
    if (ma_context_init(null_mode ? &null_backend : nullptr, null_mode ? 1 : 0, nullptr, &e->context) != MA_SUCCESS) {
        set_error("no audio backend could be initialized");
        return nullptr;
    }
    e->context_ready = true;
    ma_device_config cfg = ma_device_config_init(ma_device_type_playback);
    cfg.playback.format = ma_format_f32;
    cfg.playback.channels = 2;
    cfg.sampleRate = sample_rate > 0 ? static_cast<ma_uint32>(sample_rate) : 0;
    cfg.dataCallback = on_device_data;
    cfg.pUserData = e.get();
    if (ma_device_init(&e->context, &cfg, &e->device) != MA_SUCCESS) {
        set_error("the playback device could not be opened");
        ma_context_uninit(&e->context);
        return nullptr;
    }
    e->device_ready = true;
    e->rate = static_cast<int32_t>(e->device.sampleRate);
    e->device_name = e->device.playback.name[0] != '\0' ? e->device.playback.name : "default";
    e->stat_set(CA_STAT_SAMPLE_RATE, e->rate);
    e->streamer = std::thread([ptr = e.get()] { ptr->streamer_loop(); });
    if (ma_device_start(&e->device) != MA_SUCCESS) {
        set_error("the playback device could not be started");
        ca_engine_destroy(e.release());
        return nullptr;
    }
    return e.release();
}

void ca_engine_destroy(ca_engine* e) {
    if (e == nullptr) return;
    if (e->device_ready) ma_device_uninit(&e->device);
    if (e->streamer.joinable()) {
        {
            std::lock_guard<std::mutex> lock(e->stream_mutex);
            e->streamer_stop = true;
        }
        e->stream_cv.notify_all();
        e->streamer.join();
    }
    if (e->context_ready) ma_context_uninit(&e->context);
    e->streams.clear();
    for (auto* list : {&e->pending, &e->processing}) {
        for (InternalCmd& ic : *list) delete ic.retire;
    }
    for (int32_t i = 0; i < kSoundCapacity; ++i) delete e->sounds[static_cast<size_t>(i)].load();
    const uint32_t head = e->garbage_head.load();
    for (uint32_t t = e->garbage_tail.load(); t != head; ++t) delete e->garbage[t % kGarbageCapacity].load();
    delete e;
}

int32_t ca_engine_sample_rate(const ca_engine* e) { return e != nullptr ? e->rate : 0; }

const char* ca_engine_device_name(const ca_engine* e) { return e != nullptr ? e->device_name.c_str() : ""; }

int32_t ca_sound_load(ca_engine* e, const void* bytes, int64_t length, int32_t flags) {
    if (e == nullptr || bytes == nullptr || length <= 0) {
        set_error("no engine or no bytes");
        return CA_ERR_ARG;
    }
    std::unique_ptr<Sound> s = (flags & CA_LOAD_STREAM) ? probe_stream(bytes, static_cast<size_t>(length))
                                                        : decode_all(bytes, static_cast<size_t>(length));
    if (!s) return CA_ERR_DECODE;
    return e->register_sound(std::move(s));
}

int32_t ca_sound_load_pcm(ca_engine* e, const float* frames, int64_t frame_count, int32_t channels,
                          int32_t sample_rate) {
    if (e == nullptr || frames == nullptr || frame_count <= 0 || channels < 1 || channels > 2 || sample_rate <= 0) {
        set_error("bad PCM arguments");
        return CA_ERR_ARG;
    }
    auto s = std::make_unique<Sound>();
    s->channels = channels;
    s->rate = sample_rate;
    s->frames = frame_count;
    s->pcm.assign(frames, frames + frame_count * channels);
    return e->register_sound(std::move(s));
}

int32_t ca_sound_info(ca_engine* e, int32_t sound, int32_t* channels, int32_t* sample_rate, int64_t* frames) {
    if (e == nullptr) return CA_ERR_ARG;
    std::lock_guard<std::mutex> lock(e->sound_mutex);
    const Sound* s = e->lookup(sound);
    if (s == nullptr) return CA_ERR_ARG;
    if (channels != nullptr) *channels = s->channels;
    if (sample_rate != nullptr) *sample_rate = s->rate;
    if (frames != nullptr) *frames = s->frames;
    return CA_OK;
}

int32_t ca_sound_free(ca_engine* e, int32_t sound) {
    if (e == nullptr) return CA_ERR_ARG;
    Sound* s = nullptr;
    {
        std::lock_guard<std::mutex> lock(e->sound_mutex);
        s = e->lookup(sound);
        if (s == nullptr) return CA_ERR_ARG;
        e->sounds[static_cast<size_t>(sound)].store(nullptr, std::memory_order_release);
    }
    InternalCmd ic;
    ic.c.kind = kCmdRetire;
    ic.retire = s;
    std::lock_guard<std::mutex> lock(e->queue_mutex);
    e->pending.push_back(ic);
    return CA_OK;
}

int32_t ca_submit(ca_engine* e, ca_command* commands, int32_t count) {
    if (e == nullptr || count < 0 || (count > 0 && commands == nullptr)) return CA_ERR_ARG;
    for (int32_t i = 0; i < count; ++i) {
        const ca_command& c = commands[i];
        if (c.kind < CA_CMD_PLAY || c.kind > CA_CMD_STOP_BUS) return CA_ERR_ARG;
        const bool uses_bus = c.kind == CA_CMD_PLAY || c.kind == CA_CMD_BUS || c.kind == CA_CMD_STOP_BUS;
        if (uses_bus && (c.bus < 0 || c.bus >= CA_BUS_COUNT)) return CA_ERR_ARG;
    }
    // Streamed plays get their decoder here, on the submitting thread: the mixer cannot allocate.
    std::vector<InternalCmd> batch(static_cast<size_t>(count));
    for (int32_t i = 0; i < count; ++i) {
        InternalCmd& ic = batch[static_cast<size_t>(i)];
        ic.c = commands[i];
        if (ic.c.kind != CA_CMD_PLAY) continue;
        std::unique_ptr<Stream> st;
        {
            std::lock_guard<std::mutex> lock(e->sound_mutex);
            const Sound* s = e->lookup(ic.c.sound);
            if (s != nullptr && s->streamed()) {
                st = e->open_stream(*s, (ic.c.flags & CA_PLAY_LOOP) != 0);
                if (!st) {
                    ic.c.sound = 0;  // undecodable: the mixer drops the play
                }
            }
        }
        if (st) {
            ic.stream = st.get();
            std::lock_guard<std::mutex> lock(e->stream_mutex);
            e->streams.push_back(std::move(st));
        }
    }
    {
        std::lock_guard<std::mutex> lock(e->queue_mutex);
        for (int32_t i = 0; i < count; ++i) {
            InternalCmd& ic = batch[static_cast<size_t>(i)];
            if (ic.c.kind == CA_CMD_PLAY) {
                ic.c.voice = e->next_voice_id++;
                commands[i].voice = ic.c.voice;
            }
            e->pending.push_back(ic);
        }
    }
    return count;
}

int32_t ca_voice_state(ca_engine* e, uint64_t voice) {
    if (e == nullptr || voice == 0) return CA_VOICE_DONE;
    if (voice > e->processed_play.load(std::memory_order_acquire)) {
        std::lock_guard<std::mutex> lock(e->queue_mutex);
        if (voice < e->next_voice_id) return CA_VOICE_PENDING;
        return CA_VOICE_DONE;
    }
    for (size_t i = 0; i < e->voices.size(); ++i) {
        const uint64_t packed = e->published[i].load(std::memory_order_acquire);
        if ((packed >> 2) == voice) return static_cast<int32_t>(packed & 3u);
    }
    return CA_VOICE_DONE;
}

int32_t ca_update(ca_engine* e) {
    if (e == nullptr) return CA_ERR_ARG;
    std::lock_guard<std::mutex> lock(e->sound_mutex);
    const uint32_t head = e->garbage_head.load(std::memory_order_acquire);
    uint32_t tail = e->garbage_tail.load(std::memory_order_relaxed);
    for (; tail != head; ++tail) {
        e->delete_sound(e->garbage[tail % kGarbageCapacity].exchange(nullptr, std::memory_order_relaxed));
    }
    e->garbage_tail.store(tail, std::memory_order_release);
    return CA_OK;
}

int32_t ca_render(ca_engine* e, float* out, int32_t frames) {
    if (e == nullptr || out == nullptr || frames < 0) return CA_ERR_ARG;
    if (e->mode != CA_DEVICE_OFFLINE) return CA_ERR_MODE;
    e->mix(out, frames);
    return frames;
}

int32_t ca_stats(ca_engine* e, int64_t* out, int32_t count) {
    if (e == nullptr || out == nullptr || count < 0) return CA_ERR_ARG;
    e->stat_set(CA_STAT_SAMPLE_RATE, e->rate);
    const int32_t n = std::min(count, static_cast<int32_t>(CA_STAT_COUNT));
    for (int32_t i = 0; i < n; ++i) out[i] = e->stats[static_cast<size_t>(i)].load(std::memory_order_relaxed);
    return n;
}

}  // extern "C"
