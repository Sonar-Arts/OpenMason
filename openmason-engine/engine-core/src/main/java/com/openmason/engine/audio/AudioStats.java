package com.openmason.engine.audio;

/**
 * A snapshot of the native mixer ({@link SoundSystem#stats()}), for debug overlays and logs.
 *
 * @param mixNanosLast time the last device callback spent mixing
 * @param soundBytes   memory held by loaded sounds (decoded PCM, or encoded bytes for streams)
 */
public record AudioStats(String device, int sampleRate, int activeVoices, int peakVoices, long voicesStolen,
                         long playsDropped, long streamUnderruns, int streams, int sounds, long soundBytes,
                         long mixNanosLast, long mixNanosMax) {

    static AudioStats of(long[] s, String device) {
        return new AudioStats(device, (int) s[CendaAudio.STAT_SAMPLE_RATE], (int) s[CendaAudio.STAT_VOICES_ACTIVE],
                (int) s[CendaAudio.STAT_VOICES_PEAK], s[CendaAudio.STAT_VOICES_STOLEN],
                s[CendaAudio.STAT_PLAYS_DROPPED], s[CendaAudio.STAT_STREAM_UNDERRUNS],
                (int) s[CendaAudio.STAT_STREAMS], (int) s[CendaAudio.STAT_SOUNDS], s[CendaAudio.STAT_SOUND_BYTES],
                s[CendaAudio.STAT_MIX_NANOS_LAST], s[CendaAudio.STAT_MIX_NANOS_MAX]);
    }

    /** One line: "48000 Hz 'device' | voices 3 (peak 12) | ...". */
    public String summary() {
        return String.format("%d Hz '%s' | voices %d (peak %d, stolen %d, dropped %d) | sounds %d (%.1f MiB) | "
                        + "streams %d (underruns %d) | mix %.2f ms (max %.2f)",
                sampleRate, device, activeVoices, peakVoices, voicesStolen, playsDropped, sounds,
                soundBytes / (1024.0 * 1024.0), streams, streamUnderruns, mixNanosLast / 1e6, mixNanosMax / 1e6);
    }
}
