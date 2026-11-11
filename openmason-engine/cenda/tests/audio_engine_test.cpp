// Plain-assert checks for the Cenda audio engine (ca_*). Everything runs on an OFFLINE engine,
// so output samples are exact functions of the submitted commands: the spatial model must match
// the OpenAL setup it replaced (inverse distance clamped, ref 1 / max 50 / rolloff 1), voices
// steal by priority then loudness left, silent loops pause, buses scale and filter, streams
// decode WAV bytes, and freed sounds are released only through ca_update. The Java side
// re-tests the same contract through FFM. One real device check uses the null backend.

#include "cenda/audio.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    if (!ok) {
        ++failures;
        std::fprintf(stderr, "FAIL: %s\n", what);
    }
}

bool near(float a, float b, float eps = 1e-4f) { return std::fabs(a - b) <= eps; }

constexpr int32_t kRate = 48000;

ca_command play(int32_t sound, float gain, int32_t flags = 0, int32_t bus = 1, int32_t priority = 0) {
    ca_command c{};
    c.kind = CA_CMD_PLAY;
    c.sound = sound;
    c.bus = bus;
    c.flags = flags;
    c.priority = priority;
    c.f[0] = gain;
    c.f[1] = 1.0f;
    return c;
}

ca_command voice_cmd(int32_t kind, uint64_t voice, float a = 0.0f, float b = 0.0f) {
    ca_command c{};
    c.kind = kind;
    c.voice = voice;
    c.f[0] = a;
    c.f[1] = b;
    return c;
}

uint64_t submit(ca_engine* e, ca_command c) {
    check(ca_submit(e, &c, 1) == 1, "submit accepted");
    return c.voice;
}

std::vector<float> render(ca_engine* e, int32_t frames) {
    std::vector<float> out(static_cast<size_t>(frames) * 2);
    check(ca_render(e, out.data(), frames) == frames, "render");
    return out;
}

int32_t constant_sound(ca_engine* e, float value, int32_t frames, int32_t channels = 1) {
    std::vector<float> pcm(static_cast<size_t>(frames * channels), value);
    return ca_sound_load_pcm(e, pcm.data(), frames, channels, kRate);
}

void put_u32(std::vector<uint8_t>& b, uint32_t v) {
    for (int i = 0; i < 4; ++i) b.push_back(static_cast<uint8_t>(v >> (8 * i)));
}
void put_u16(std::vector<uint8_t>& b, uint16_t v) {
    b.push_back(static_cast<uint8_t>(v));
    b.push_back(static_cast<uint8_t>(v >> 8));
}

// A 16-bit PCM WAV file holding `frames` frames of `value` on every channel.
std::vector<uint8_t> wav(int32_t frames, int16_t value, uint16_t channels, uint32_t rate) {
    std::vector<uint8_t> b;
    const uint32_t data_bytes = static_cast<uint32_t>(frames) * channels * 2u;
    b.insert(b.end(), {'R', 'I', 'F', 'F'});
    put_u32(b, 36u + data_bytes);
    b.insert(b.end(), {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
    put_u32(b, 16);
    put_u16(b, 1);
    put_u16(b, channels);
    put_u32(b, rate);
    put_u32(b, rate * channels * 2u);
    put_u16(b, static_cast<uint16_t>(channels * 2u));
    put_u16(b, 16);
    b.insert(b.end(), {'d', 'a', 't', 'a'});
    put_u32(b, data_bytes);
    for (int32_t i = 0; i < frames * channels; ++i) put_u16(b, static_cast<uint16_t>(value));
    return b;
}

void test_centre_and_pan() {
    ca_engine* e = ca_engine_create(CA_DEVICE_OFFLINE, kRate, 8);
    check(e != nullptr, "offline engine");
    const int32_t s = constant_sound(e, 1.0f, kRate);
    check(s > 0, "pcm load");

    // 2D mono: equal-power centre on both channels.
    const uint64_t v = submit(e, play(s, 1.0f));
    check(ca_voice_state(e, v) == CA_VOICE_PENDING, "pending before the mixer runs");
    auto out = render(e, 256);
    check(near(out[0], 0.70710678f) && near(out[1], 0.70710678f), "2D mono centred at 0.7071");
    check(ca_voice_state(e, v) == CA_VOICE_PLAYING, "playing after a block");
    submit(e, voice_cmd(CA_CMD_STOP, v));
    render(e, 256);
    check(ca_voice_state(e, v) == CA_VOICE_DONE, "cut voice is done");

    // Spatial, 10 blocks to the listener's right (forward -Z, up +Y => right +X).
    ca_command c = play(s, 1.0f, CA_PLAY_SPATIAL);
    c.f[2] = 10.0f;
    submit(e, c);
    out = render(e, 256);
    check(near(out[0], 0.0f), "right source: silent left");
    check(near(out[1], 0.1f), "right source: inverse-distance 1/(1+9) on the right");
    ca_command stop_all{};
    stop_all.kind = CA_CMD_STOP_BUS;
    stop_all.bus = 1;
    submit(e, stop_all);
    render(e, 256);

    // Past max distance (50) the gain holds at the clamped value 1/50, like AL_INVERSE_DISTANCE_CLAMPED.
    c = play(s, 1.0f, CA_PLAY_SPATIAL);
    c.f[4] = -500.0f;  // straight ahead
    submit(e, c);
    out = render(e, 256);
    check(near(out[0], 0.02f * 0.70710678f) && near(out[1], 0.02f * 0.70710678f), "clamped at max distance");
    submit(e, stop_all);
    render(e, 256);

    // Turning the listener moves the image: face +X and the same source at +X is centred.
    ca_command l{};
    l.kind = CA_CMD_LISTENER;
    l.f[3] = 1.0f;  // forward +X
    l.f[7] = 1.0f;  // up +Y
    submit(e, l);
    c = play(s, 1.0f, CA_PLAY_SPATIAL);
    c.f[2] = 10.0f;
    submit(e, c);
    out = render(e, 256);
    check(near(out[0], out[1]) && near(out[1], 0.1f * 0.70710678f), "listener orientation applies");
    ca_engine_destroy(e);
}

void test_stealing() {
    ca_engine* e = ca_engine_create(CA_DEVICE_OFFLINE, kRate, 2);
    const int32_t s = constant_sound(e, 0.1f, kRate);
    const uint64_t quiet = submit(e, play(s, 0.2f));
    const uint64_t loud = submit(e, play(s, 0.9f));
    render(e, 256);
    // A fainter equal-priority play is dropped; a louder one takes the quietest voice.
    const uint64_t fainter = submit(e, play(s, 0.1f));
    render(e, 256);
    check(ca_voice_state(e, fainter) == CA_VOICE_DONE, "fainter play dropped when full");
    const uint64_t louder = submit(e, play(s, 0.5f));
    render(e, 256);
    check(ca_voice_state(e, louder) == CA_VOICE_PLAYING, "louder play gets a voice");
    check(ca_voice_state(e, quiet) == CA_VOICE_DONE, "quietest voice stolen");
    check(ca_voice_state(e, loud) == CA_VOICE_PLAYING, "loud voice kept");
    // Priority beats loudness.
    const uint64_t important = submit(e, play(s, 0.01f, 0, 1, 5));
    render(e, 256);
    check(ca_voice_state(e, important) == CA_VOICE_PLAYING, "higher priority steals");
    int64_t stats[CA_STAT_COUNT] = {};
    check(ca_stats(e, stats, CA_STAT_COUNT) == CA_STAT_COUNT, "stats");
    check(stats[CA_STAT_VOICES_STOLEN] == 2, "two steals counted");
    check(stats[CA_STAT_PLAYS_DROPPED] == 1, "one drop counted");
    ca_engine_destroy(e);
}

void test_one_shot_pitch_and_loops() {
    ca_engine* e = ca_engine_create(CA_DEVICE_OFFLINE, kRate, 8);
    const int32_t s = constant_sound(e, 0.5f, 1024);
    ca_command fast = play(s, 1.0f);
    fast.f[1] = 2.0f;
    const uint64_t a = submit(e, fast);
    const uint64_t b = submit(e, play(s, 1.0f));
    render(e, 512);
    check(ca_voice_state(e, a) == CA_VOICE_DONE, "pitch 2 finishes 1024 frames in 512");
    check(ca_voice_state(e, b) == CA_VOICE_PLAYING, "pitch 1 still playing at 512");
    render(e, 768);
    check(ca_voice_state(e, b) == CA_VOICE_DONE, "pitch 1 done after 1024");

    // A loop runs past its length; at zero gain it pauses (and resumes) instead of advancing.
    const uint64_t loop = submit(e, play(s, 1.0f, CA_PLAY_LOOP));
    auto out = render(e, 4096);
    check(ca_voice_state(e, loop) == CA_VOICE_PLAYING && near(out[2 * 4000], 0.5f * 0.70710678f), "loop keeps playing");
    submit(e, voice_cmd(CA_CMD_VOICE_GAIN, loop, 0.0f, 0.0f));
    render(e, 512);
    check(ca_voice_state(e, loop) == CA_VOICE_PAUSED, "silent loop pauses");
    submit(e, voice_cmd(CA_CMD_VOICE_GAIN, loop, 1.0f, 0.0f));
    out = render(e, 512);
    check(ca_voice_state(e, loop) == CA_VOICE_PLAYING && out[2 * 511] > 0.3f, "loop resumes");

    // A fade-out ramps down, then ends the voice.
    submit(e, voice_cmd(CA_CMD_STOP, loop, 0.01f));
    out = render(e, 256);
    check(out[0] > out[2 * 255] && ca_voice_state(e, loop) == CA_VOICE_PLAYING, "fading");
    render(e, 1024);
    check(ca_voice_state(e, loop) == CA_VOICE_DONE, "faded voice ends");
    ca_engine_destroy(e);
}

void test_buses() {
    ca_engine* e = ca_engine_create(CA_DEVICE_OFFLINE, kRate, 8);
    const int32_t s = constant_sound(e, 1.0f, kRate);
    ca_command bus{};
    bus.kind = CA_CMD_BUS;
    bus.bus = 2;
    bus.f[0] = 0.5f;
    submit(e, bus);
    submit(e, play(s, 1.0f, 0, 2));
    auto out = render(e, 256);
    check(near(out[0], 0.5f * 0.70710678f), "bus gain scales its voices");
    ca_command stop{};
    stop.kind = CA_CMD_STOP_BUS;
    stop.bus = 2;
    submit(e, stop);
    render(e, 256);

    // Low-pass on a bus: a Nyquist-rate square wave is crushed, DC passes.
    std::vector<float> nyquist(4096);
    for (size_t i = 0; i < nyquist.size(); ++i) nyquist[i] = (i % 2 == 0) ? 0.5f : -0.5f;
    const int32_t hf = ca_sound_load_pcm(e, nyquist.data(), 4096, 1, kRate);
    bus.f[0] = 1.0f;
    bus.f[1] = 500.0f;
    submit(e, bus);
    submit(e, play(hf, 1.0f, 0, 2));
    out = render(e, 1024);
    check(std::fabs(out[2 * 1000]) < 0.05f, "low-pass removes high frequencies");

    // Master gain applies to everything.
    ca_command master{};
    master.kind = CA_CMD_BUS;
    master.bus = 0;
    master.f[0] = 0.25f;
    submit(e, master);
    submit(e, stop);
    submit(e, play(s, 1.0f, 0, 3));
    out = render(e, 256);
    // (sample 255: the first samples still carry bus 2's low-pass tail from the cut voice)
    check(near(out[2 * 255], 0.25f * 0.70710678f), "master gain");
    ca_engine_destroy(e);
}

void test_decode_stream_and_free() {
    ca_engine* e = ca_engine_create(CA_DEVICE_OFFLINE, kRate, 8);
    const std::vector<uint8_t> file = wav(4800, 16384, 2, kRate);
    const int32_t resident = ca_sound_load(e, file.data(), static_cast<int64_t>(file.size()), 0);
    check(resident > 0, "WAV decodes");
    int32_t ch = 0, rate = 0;
    int64_t frames = 0;
    check(ca_sound_info(e, resident, &ch, &rate, &frames) == CA_OK && ch == 2 && rate == kRate && frames == 4800,
          "WAV info");
    const uint8_t junk[16] = {1, 2, 3};
    check(ca_sound_load(e, junk, 16, 0) == CA_ERR_DECODE && std::strlen(ca_last_error()) > 0, "junk refused");

    // 2D stereo keeps its channels at unit gain.
    submit(e, play(resident, 1.0f));
    auto out = render(e, 256);
    check(near(out[0], 0.5f, 1e-3f) && near(out[1], 0.5f, 1e-3f), "2D stereo plays as is");
    ca_command stop{};
    stop.kind = CA_CMD_STOP_BUS;
    stop.bus = 1;
    submit(e, stop);
    render(e, 256);

    // The same file streamed and looped plays continuously past its length.
    const int32_t streamed = ca_sound_load(e, file.data(), static_cast<int64_t>(file.size()), CA_LOAD_STREAM);
    check(streamed > 0, "stream load");
    const uint64_t music = submit(e, play(streamed, 1.0f, CA_PLAY_LOOP, 4));
    out = render(e, 12000);
    bool continuous = true;
    for (size_t i = 0; i < 12000; ++i) continuous = continuous && near(out[2 * i], 0.5f, 1e-3f);
    check(continuous, "looped stream has no gaps");
    check(ca_voice_state(e, music) == CA_VOICE_PLAYING, "stream playing");
    // A one-shot stream ends after its frames.
    const uint64_t once = submit(e, play(streamed, 1.0f, 0, 4));
    render(e, 6000);
    check(ca_voice_state(e, once) == CA_VOICE_DONE, "one-shot stream ends");

    // Freeing stops its voices and is released on ca_update.
    int64_t stats[CA_STAT_COUNT] = {};
    ca_stats(e, stats, CA_STAT_COUNT);
    const int64_t before = stats[CA_STAT_SOUNDS];
    check(ca_sound_free(e, streamed) == CA_OK, "free");
    check(ca_sound_info(e, streamed, nullptr, nullptr, nullptr) == CA_ERR_ARG, "freed sound is gone");
    render(e, 256);
    check(ca_voice_state(e, music) == CA_VOICE_DONE, "freeing stops its voices");
    ca_update(e);
    ca_stats(e, stats, CA_STAT_COUNT);
    check(stats[CA_STAT_SOUNDS] == before - 1, "ca_update releases the sound");
    check(submit(e, play(streamed, 1.0f)) != 0 && (render(e, 256), true), "playing a freed sound is harmless");
    ca_engine_destroy(e);
}

void test_null_device() {
    ca_engine* e = ca_engine_create(CA_DEVICE_NULL, 0, 16);
    check(e != nullptr, "null device opens");
    if (e == nullptr) return;
    check(ca_engine_sample_rate(e) > 0, "null device has a rate");
    const int32_t s = constant_sound(e, 0.5f, 4800);
    const uint64_t v = submit(e, play(s, 1.0f));
    check(v != 0, "id assigned");
    float r[2];
    check(ca_render(e, r, 1) == CA_ERR_MODE, "no ca_render on a device engine");
    ca_engine_destroy(e);
}

}  // namespace

int main() {
    check(ca_abi_version() == CA_ABI_VERSION, "abi version");
    check(ca_engine_create(CA_DEVICE_OFFLINE, 0, 0) == nullptr, "offline needs a rate");
    test_centre_and_pan();
    test_stealing();
    test_one_shot_pitch_and_loops();
    test_buses();
    test_decode_stream_and_free();
    test_null_device();
    if (failures == 0) std::printf("audio_engine: all checks passed\n");
    return failures == 0 ? 0 : 1;
}
