/* Cenda audio — the Open Mason audio engine behind a C ABI, consumed by Java via
 * FFM (com.openmason.engine.audio.CendaAudio).
 *
 * miniaudio supplies the playback device and the decoders (WAV, FLAC, MP3, and
 * Ogg Vorbis through stb_vorbis). Everything between the decoded samples and the
 * device is ours: the voice pool, voice stealing, buses with gain and low-pass
 * filters, the spatializer, music streaming and the final limiter.
 *
 * Contract notes:
 *  - Lives in libcenda_kernels with its OWN handshake (ca_abi_version). Audio has
 *    no Java fallback: on a missing library or an ABI mismatch the Java side runs
 *    silent and says so.
 *  - Thread safety: every function except ca_render and ca_engine_destroy may be
 *    called from any thread at any time. Commands are queued and the mixer applies
 *    them in submission order at the start of its next block (about 5 ms), so a
 *    sound can be started from a simulation thread without handing it to the main
 *    thread first.
 *  - The mixer never blocks, allocates or frees memory. Decoding happens on the
 *    calling thread (resident sounds) or on the engine's streaming thread (streamed
 *    sounds). Freed sounds and finished streams are released by ca_update, which
 *    the host calls once per frame.
 *  - Voice ids are 64-bit, assigned by ca_submit in submission order and never
 *    reused. A finished, stopped or stolen voice's id simply stops matching
 *    anything; commands addressed to it are ignored.
 *  - CA_DEVICE_OFFLINE has no device and no threads: the caller drives mixing with
 *    ca_render, and streams are decoded inside it. Output is a pure function of the
 *    submitted commands, so tests can compare exact sample values.
 *
 * Spatial model (CA_PLAY_SPATIAL), kept equal to the OpenAL setup it replaced:
 *  - gain = ref / (ref + rolloff * (clamp(d, ref, max) - ref))  (inverse distance, clamped)
 *  - equal-power stereo pan from the source direction projected on the listener's
 *    right axis; inside ref distance the pan blends toward the centre, so a source
 *    at the listener's feet is not hard-panned.
 * A listener-relative (2D) mono voice plays centred at the same equal-power gain
 * (0.7071 per channel); a 2D stereo voice keeps its channels.
 */
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CA_ABI_VERSION 1

typedef struct ca_engine ca_engine;

/* Device modes for ca_engine_create. */
enum {
    CA_DEVICE_DEFAULT = 0, /* the system's default playback device */
    CA_DEVICE_NULL = 1,    /* miniaudio's null backend: a real mixing thread at real time, no sound */
    CA_DEVICE_OFFLINE = 2  /* no device, no threads: the caller mixes with ca_render */
};

/* Results. Positive values from loads are sound ids. */
enum {
    CA_OK = 0,
    CA_ERR_ARG = -1,      /* bad argument (null engine, bad sizes, unknown sound) */
    CA_ERR_DECODE = -2,   /* the bytes are not audio miniaudio can decode */
    CA_ERR_FULL = -3,     /* the sound table is full */
    CA_ERR_MODE = -4,     /* wrong engine mode for the call (ca_render on a device engine) */
    CA_ERR_INTERNAL = -5
};

/* ca_sound_load flags. */
enum {
    CA_LOAD_STREAM = 1 /* keep the encoded bytes and decode while playing (music, long ambience) */
};

/* Command kinds (ca_command.kind). */
enum {
    CA_CMD_PLAY = 1,           /* see ca_command */
    CA_CMD_STOP = 2,           /* voice; f[0] fade-out seconds (0 = cut) */
    CA_CMD_VOICE_GAIN = 3,     /* voice; f[0] gain, f[1] ramp seconds */
    CA_CMD_VOICE_PITCH = 4,    /* voice; f[0] pitch */
    CA_CMD_VOICE_POSITION = 5, /* voice; f[0..2] world position (spatial voices) */
    CA_CMD_LISTENER = 6,       /* f[0..2] position, f[3..5] forward, f[6..8] up */
    CA_CMD_BUS = 7,            /* bus; f[0] gain, f[1] low-pass cutoff Hz (0 = off), f[2] ramp seconds */
    CA_CMD_STOP_BUS = 8        /* bus; f[0] fade-out seconds: stops every voice routed to it */
};

/* CA_CMD_PLAY flags. */
enum {
    CA_PLAY_LOOP = 1,   /* loop forever; at zero gain the voice pauses instead of advancing */
    CA_PLAY_SPATIAL = 2 /* positioned in the world, else listener-relative and unattenuated */
};

/* Buses: CA_BUS_COUNT of them, bus 0 is the master and every other bus feeds it.
 * What each bus is for (sfx, music, ambience, ui) is the host's convention. */
#define CA_BUS_COUNT 8

/* Voice states (ca_voice_state). */
enum {
    CA_VOICE_DONE = 0,    /* finished, stopped, stolen, dropped, or never existed */
    CA_VOICE_PENDING = 1, /* submitted, not yet picked up by the mixer */
    CA_VOICE_PLAYING = 2,
    CA_VOICE_PAUSED = 3   /* a loop held at zero gain */
};

/* One command, 72 bytes. For CA_CMD_PLAY:
 *   sound, bus, flags, priority (higher survives voice stealing);
 *   f[0] gain, f[1] pitch (0 = 1), f[2..4] world position,
 *   f[5] ref distance, f[6] max distance, f[7] rolloff (all 0 = 1 / 50 / 1),
 *   f[8] fade-in seconds, f[9] start offset seconds (resident sounds only).
 * ca_submit writes the new voice id into `voice`. Every other kind reads `voice`
 * (voice commands) or `bus` (bus commands) and the f[] slots listed above. */
typedef struct ca_command {
    int32_t kind;
    int32_t sound;
    int32_t bus;
    int32_t flags;
    int32_t priority;
    int32_t reserved;
    uint64_t voice;
    float f[10];
} ca_command;

/* ca_stats slots. */
enum {
    CA_STAT_VOICES_ACTIVE = 0,  /* voices playing or paused after the last block */
    CA_STAT_VOICES_PEAK = 1,
    CA_STAT_VOICES_STOLEN = 2,  /* voices cut to make room for a more important one */
    CA_STAT_PLAYS_DROPPED = 3,  /* plays refused: no voice free and none less important */
    CA_STAT_STREAM_UNDERRUNS = 4, /* blocks where a stream had not decoded far enough */
    CA_STAT_BLOCKS = 5,         /* mixed blocks since creation */
    CA_STAT_MIX_NANOS_LAST = 6, /* time the last device callback spent mixing */
    CA_STAT_MIX_NANOS_MAX = 7,
    CA_STAT_SOUNDS = 8,         /* loaded sounds (resident + streamed) */
    CA_STAT_SOUND_BYTES = 9,    /* bytes held by loaded sounds (decoded PCM or encoded streams) */
    CA_STAT_SAMPLE_RATE = 10,
    CA_STAT_COMMANDS = 11,      /* commands applied since creation */
    CA_STAT_STREAMS = 12,       /* live stream decoders */
    CA_STAT_COUNT = 13
};

int32_t ca_abi_version(void);

/* Text of the last failure on THIS thread (create, load); valid until the next call. */
const char* ca_last_error(void);

/* sample_rate 0 = the device's own rate (OFFLINE requires one); max_voices 0 = 96. Null on failure. */
ca_engine* ca_engine_create(int32_t device_mode, int32_t sample_rate, int32_t max_voices);
/* Stops the device and the streaming thread, then frees everything. Not concurrent with other calls. */
void ca_engine_destroy(ca_engine* engine);
int32_t ca_engine_sample_rate(const ca_engine* engine);
/* Name of the playback device ("offline" for CA_DEVICE_OFFLINE). Lives as long as the engine. */
const char* ca_engine_device_name(const ca_engine* engine);

/* Decodes (or, with CA_LOAD_STREAM, keeps) an encoded file. Returns a sound id > 0 or a CA_ERR_*. */
int32_t ca_sound_load(ca_engine* engine, const void* bytes, int64_t length, int32_t flags);
/* Registers raw interleaved float PCM (1 or 2 channels). Returns a sound id > 0 or a CA_ERR_*. */
int32_t ca_sound_load_pcm(ca_engine* engine, const float* frames, int64_t frame_count,
                          int32_t channels, int32_t sample_rate);
/* frames is -1 for streamed sounds whose length the decoder cannot tell up front. */
int32_t ca_sound_info(ca_engine* engine, int32_t sound, int32_t* channels, int32_t* sample_rate,
                      int64_t* frames);
/* Stops every voice playing it; the memory goes on the next ca_update after the mixer lets go. */
int32_t ca_sound_free(ca_engine* engine, int32_t sound);

/* Queues `count` commands in order; writes play ids back. Returns count or a CA_ERR_*. */
int32_t ca_submit(ca_engine* engine, ca_command* commands, int32_t count);
int32_t ca_voice_state(ca_engine* engine, uint64_t voice);

/* Host housekeeping, once per frame from one thread: releases freed sounds and streams. */
int32_t ca_update(ca_engine* engine);

/* OFFLINE only: applies queued commands and mixes `frames` interleaved stereo floats into out. */
int32_t ca_render(ca_engine* engine, float* out, int32_t frames);

/* Copies min(count, CA_STAT_COUNT) stat slots into out; returns how many. */
int32_t ca_stats(ca_engine* engine, int64_t* out, int32_t count);

#ifdef __cplusplus
}
#endif
