/* The one translation unit that compiles miniaudio's implementation (and stb_vorbis, which
 * gives miniaudio's decoder Ogg Vorbis support). Built as its own static library without the
 * strict Cenda warning set; only the ca_* functions in audio_engine.cpp are exported. */
#define STB_VORBIS_HEADER_ONLY
#include "extras/stb_vorbis.c"

#define MINIAUDIO_IMPLEMENTATION
#include "miniaudio.h"

#undef STB_VORBIS_HEADER_ONLY
#include "extras/stb_vorbis.c"
