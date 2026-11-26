#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <emscripten/emscripten.h>

/* Declaraciones mínimas de la API Libretro */
#define RETRO_API_VERSION 1
#define RETRO_DEVICE_JOYPAD 1
#define RETRO_DEVICE_ID_JOYPAD_MASK 256

#define RETRO_PIXEL_FORMAT_0RGB1555 0
#define RETRO_PIXEL_FORMAT_XRGB8888 1
#define RETRO_PIXEL_FORMAT_RGB565   2

#define RETRO_ENVIRONMENT_SET_PIXEL_FORMAT 10
#define RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY 9
#define RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY 31
#define RETRO_ENVIRONMENT_GET_CAN_DUPE 3
#define RETRO_ENVIRONMENT_GET_LOG_INTERFACE 27
#define RETRO_ENVIRONMENT_GET_VARIABLE 15
#define RETRO_ENVIRONMENT_SET_VARIABLES 16
#define RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE 17

struct retro_game_info {
    const char *path;
    const void *data;
    size_t size;
    const char *meta;
};

struct retro_system_av_info {
    struct {
        unsigned base_width;
        unsigned base_height;
        unsigned max_width;
        unsigned max_height;
        float aspect_ratio;
    } geometry;
    struct {
        double fps;
        double sample_rate;
    } timing;
};

typedef bool (*retro_environment_t)(unsigned cmd, void *data);
typedef void (*retro_video_refresh_t)(const void *data, unsigned width, unsigned height, size_t pitch);
typedef void (*retro_audio_sample_t)(int16_t left, int16_t right);
typedef size_t (*retro_audio_sample_batch_t)(const int16_t *data, size_t frames);
typedef void (*retro_input_poll_t)(void);
typedef int16_t (*retro_input_state_t)(unsigned port, unsigned device, unsigned index, unsigned id);

#ifdef __cplusplus
extern "C" {
#endif

extern void retro_set_environment(retro_environment_t);
extern void retro_set_video_refresh(retro_video_refresh_t);
extern void retro_set_audio_sample(retro_audio_sample_t);
extern void retro_set_audio_sample_batch(retro_audio_sample_batch_t);
extern void retro_set_input_poll(retro_input_poll_t);
extern void retro_set_input_state(retro_input_state_t);

extern void retro_init(void);
extern void retro_deinit(void);
extern void retro_reset(void);
extern void retro_run(void);
extern bool retro_load_game(const struct retro_game_info *game);
extern void retro_unload_game(void);
extern void retro_get_system_av_info(struct retro_system_av_info *info);

#ifdef __cplusplus
}
#endif

/* Estado global del host WASM */
static int g_pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;
static uint8_t *g_video_rgba = NULL;
static unsigned g_video_w = 0;
static unsigned g_video_h = 0;
static size_t g_video_cap = 0;

#define MAX_AUDIO_FRAMES 8192
static int16_t g_audio_buf[MAX_AUDIO_FRAMES * 2];
static size_t g_audio_frames = 0;
static double g_sample_rate = 44100.0;

static uint16_t g_inputs[4] = {0, 0, 0, 0};

static bool env_cb(unsigned cmd, void *data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            if (!data) return false;
            int fmt = *(const int *)data;
            if (fmt == RETRO_PIXEL_FORMAT_0RGB1555 ||
                fmt == RETRO_PIXEL_FORMAT_XRGB8888 ||
                fmt == RETRO_PIXEL_FORMAT_RGB565) {
                g_pixel_format = fmt;
                return true;
            }
            return false;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: {
            *(const char **)data = "/system";
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: {
            *(const char **)data = "/saves";
            return true;
        }
        case RETRO_ENVIRONMENT_GET_CAN_DUPE: {
            *(bool *)data = true;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: {
            *(bool *)data = false;
            return true;
        }
        default:
            return false;
    }
}

static void video_cb(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (!data || width == 0 || height == 0) return;

    size_t needed = (size_t)width * (size_t)height * 4;
    if (needed > g_video_cap) {
        free(g_video_rgba);
        g_video_rgba = (uint8_t *)malloc(needed);
        g_video_cap = needed;
    }
    g_video_w = width;
    g_video_h = height;

    const uint8_t *src_bytes = (const uint8_t *)data;
    uint8_t *dst = g_video_rgba;

    if (g_pixel_format == RETRO_PIXEL_FORMAT_XRGB8888) {
        for (unsigned y = 0; y < height; y++) {
            const uint32_t *row = (const uint32_t *)(src_bytes + y * pitch);
            for (unsigned x = 0; x < width; x++) {
                uint32_t px = row[x];
                dst[0] = (px >> 16) & 0xFF;
                dst[1] = (px >> 8) & 0xFF;
                dst[2] = px & 0xFF;
                dst[3] = 0xFF;
                dst += 4;
            }
        }
    } else if (g_pixel_format == RETRO_PIXEL_FORMAT_RGB565) {
        for (unsigned y = 0; y < height; y++) {
            const uint16_t *row = (const uint16_t *)(src_bytes + y * pitch);
            for (unsigned x = 0; x < width; x++) {
                uint16_t px = row[x];
                uint8_t r = (px >> 11) & 0x1F;
                uint8_t g = (px >> 5) & 0x3F;
                uint8_t b = px & 0x1F;
                dst[0] = (r << 3) | (r >> 2);
                dst[1] = (g << 2) | (g >> 4);
                dst[2] = (b << 3) | (b >> 2);
                dst[3] = 0xFF;
                dst += 4;
            }
        }
    } else {
        /* 0RGB1555 */
        for (unsigned y = 0; y < height; y++) {
            const uint16_t *row = (const uint16_t *)(src_bytes + y * pitch);
            for (unsigned x = 0; x < width; x++) {
                uint16_t px = row[x];
                uint8_t r = (px >> 10) & 0x1F;
                uint8_t g = (px >> 5) & 0x1F;
                uint8_t b = px & 0x1F;
                dst[0] = (r << 3) | (r >> 2);
                dst[1] = (g << 3) | (g >> 2);
                dst[2] = (b << 3) | (b >> 2);
                dst[3] = 0xFF;
                dst += 4;
            }
        }
    }
}

static void audio_sample_cb(int16_t left, int16_t right) {
    if (g_audio_frames < MAX_AUDIO_FRAMES) {
        g_audio_buf[g_audio_frames * 2] = left;
        g_audio_buf[g_audio_frames * 2 + 1] = right;
        g_audio_frames++;
    }
}

static size_t audio_batch_cb(const int16_t *data, size_t frames) {
    size_t avail = MAX_AUDIO_FRAMES - g_audio_frames;
    size_t to_copy = frames < avail ? frames : avail;
    if (to_copy > 0 && data) {
        memcpy(&g_audio_buf[g_audio_frames * 2], data, to_copy * 2 * sizeof(int16_t));
        g_audio_frames += to_copy;
    }
    return frames;
}

static void input_poll_cb(void) {}

static int16_t input_state_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
    (void)index;
    if (port >= 4 || device != RETRO_DEVICE_JOYPAD) return 0;
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) {
        return (int16_t)g_inputs[port];
    }
    if (id < 16) {
        return (g_inputs[port] & (1u << id)) ? 1 : 0;
    }
    return 0;
}

EMSCRIPTEN_KEEPALIVE
void bd_init(void) {
    retro_set_environment(env_cb);
    retro_set_video_refresh(video_cb);
    retro_set_audio_sample(audio_sample_cb);
    retro_set_audio_sample_batch(audio_batch_cb);
    retro_set_input_poll(input_poll_cb);
    retro_set_input_state(input_state_cb);
    retro_init();
}

EMSCRIPTEN_KEEPALIVE
int bd_load_game(const char *rom_path) {
    struct retro_game_info info;
    memset(&info, 0, sizeof(info));
    info.path = rom_path;
    if (!retro_load_game(&info)) {
        return 0;
    }
    struct retro_system_av_info av;
    memset(&av, 0, sizeof(av));
    retro_get_system_av_info(&av);
    if (av.timing.sample_rate > 0) {
        g_sample_rate = av.timing.sample_rate;
    }
    return 1;
}

EMSCRIPTEN_KEEPALIVE
void bd_set_input_frame(uint16_t p0, uint16_t p1, uint16_t p2, uint16_t p3) {
    g_inputs[0] = p0;
    g_inputs[1] = p1;
    g_inputs[2] = p2;
    g_inputs[3] = p3;
}

EMSCRIPTEN_KEEPALIVE
void bd_run_frame(void) {
    g_audio_frames = 0;
    retro_run();
}

EMSCRIPTEN_KEEPALIVE
void bd_reset(void) {
    retro_reset();
}

EMSCRIPTEN_KEEPALIVE
void bd_unload_game(void) {
    retro_unload_game();
    retro_deinit();
}

EMSCRIPTEN_KEEPALIVE
uintptr_t bd_get_video_ptr(void) {
    return (uintptr_t)g_video_rgba;
}

EMSCRIPTEN_KEEPALIVE
unsigned bd_get_video_width(void) {
    return g_video_w;
}

EMSCRIPTEN_KEEPALIVE
unsigned bd_get_video_height(void) {
    return g_video_h;
}

EMSCRIPTEN_KEEPALIVE
uintptr_t bd_get_audio_ptr(void) {
    return (uintptr_t)g_audio_buf;
}

EMSCRIPTEN_KEEPALIVE
unsigned bd_get_audio_frames(void) {
    return (unsigned)g_audio_frames;
}

EMSCRIPTEN_KEEPALIVE
double bd_get_sample_rate(void) {
    return g_sample_rate;
}
