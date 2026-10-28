#ifndef KMEDIA_NOTIFICATION_H
#define KMEDIA_NOTIFICATION_H
#ifdef __cplusplus
extern "C" {
#endif
#ifdef _WIN32
#define KMEDIA_API __declspec(dllexport)
#else
#define KMEDIA_API
#endif
/* Callbacks must return promptly. 1 means admitted, 0 means rejected. */
typedef int (*KMediaCommand)(void *user, int command, double value, const char *entry_id);
KMEDIA_API void *kmedia_create(KMediaCommand callback, void *user, const char *id,
    const char *display_name, const char *desktop_entry, void *window,
    double minimum_rate, double maximum_rate);
KMEDIA_API void kmedia_update(void *handle, const char *id, const char *title,
    const char *artist, const char *album, const char *artwork,
    int present, int playing, int actions, int kind,
    double position_ms, double duration_ms, double speed, double volume,
    int shuffle, int repeat, int liked, int discontinuity);
KMEDIA_API void *kmedia_native_handle(void *handle);
KMEDIA_API void kmedia_destroy(void *handle);
#ifdef __cplusplus
}
#endif
#endif
