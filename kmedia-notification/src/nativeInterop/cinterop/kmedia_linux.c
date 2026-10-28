#include "kmedia_notification.h"
#include <gio/gio.h>
#include <unistd.h>
#include <string.h>

/* GDBus owns its event loop; playback never polls the session bus. */
typedef struct {
    KMediaCommand callback;
    void *user;
    GMutex mutex;
    GCond started;
    GThread *thread;
    GMainContext *context;
    GMainLoop *loop;
    GDBusConnection *bus;
    GDBusNodeInfo *node;
    guint objects[2], owner;
    gboolean ready, failed;
    char *id, *title, *artist, *album, *artwork, *identity, *desktop_entry, *bus_name;
    double minimum_rate, maximum_rate;
    int actions;
    int present, playing, shuffle, repeat;
    double position, duration, speed, volume;
    gint64 sampled_at;
} KMedia;

static const char xml[] =
"<node><interface name='org.mpris.MediaPlayer2'>"
"<method name='Raise'/><method name='Quit'/>"
"<property name='CanQuit' type='b' access='read'/>"
"<property name='CanRaise' type='b' access='read'/>"
"<property name='HasTrackList' type='b' access='read'/>"
"<property name='Identity' type='s' access='read'/>"
"<property name='DesktopEntry' type='s' access='read'/>"
"<property name='SupportedUriSchemes' type='as' access='read'/>"
"<property name='SupportedMimeTypes' type='as' access='read'/>"
"</interface><interface name='org.mpris.MediaPlayer2.Player'>"
"<method name='Next'/><method name='Previous'/><method name='Pause'/>"
"<method name='PlayPause'/><method name='Stop'/><method name='Play'/>"
"<method name='Seek'><arg type='x' direction='in'/></method>"
"<method name='SetPosition'><arg type='o' direction='in'/><arg type='x' direction='in'/></method>"
"<method name='OpenUri'><arg type='s' direction='in'/></method>"
"<signal name='Seeked'><arg type='x'/></signal>"
"<property name='PlaybackStatus' type='s' access='read'/>"
"<property name='LoopStatus' type='s' access='readwrite'/>"
"<property name='Rate' type='d' access='readwrite'/>"
"<property name='Shuffle' type='b' access='readwrite'/>"
"<property name='Metadata' type='a{sv}' access='read'/>"
"<property name='Volume' type='d' access='readwrite'/>"
"<property name='Position' type='x' access='read'/>"
"<property name='MinimumRate' type='d' access='read'/>"
"<property name='MaximumRate' type='d' access='read'/>"
"<property name='CanGoNext' type='b' access='read'/>"
"<property name='CanGoPrevious' type='b' access='read'/>"
"<property name='CanPlay' type='b' access='read'/>"
"<property name='CanPause' type='b' access='read'/>"
"<property name='CanSeek' type='b' access='read'/>"
"<property name='CanControl' type='b' access='read'/>"
"</interface></node>";

static char *track_path(KMedia *m) {
    char *hash = g_compute_checksum_for_string(G_CHECKSUM_SHA256, m->id ? m->id : "", -1);
    char *path = g_strconcat("/org/mpris/MediaPlayer2/track/", hash, NULL);
    g_free(hash);
    return path;
}

/* All property construction is under mutex, including remote reads. */
static GVariant *property(KMedia *m, const char *name) {
    if (!strcmp(name, "Identity")) return g_variant_new_string(m->identity);
    if (!strcmp(name, "DesktopEntry")) return g_variant_new_string(m->desktop_entry);
    if (!strcmp(name, "SupportedUriSchemes") || !strcmp(name, "SupportedMimeTypes"))
        return g_variant_new_strv(NULL, 0);
    if (!strcmp(name, "CanQuit")) return g_variant_new_boolean((m->actions & (1 << 14)) != 0);
    if (!strcmp(name, "CanRaise")) return g_variant_new_boolean((m->actions & (1 << 13)) != 0);
    if (!strcmp(name, "HasTrackList")) return g_variant_new_boolean(FALSE);
    if (!strcmp(name, "PlaybackStatus")) return g_variant_new_string(!m->present ? "Stopped" : m->playing ? "Playing" : "Paused");
    if (!strcmp(name, "LoopStatus")) return g_variant_new_string(m->repeat == 1 ? "Playlist" : m->repeat == 2 ? "Track" : "None");
    if (!strcmp(name, "Shuffle")) return g_variant_new_boolean(m->shuffle);
    if (!strcmp(name, "Volume")) return g_variant_new_double(m->volume);
    if (!strcmp(name, "Rate")) return g_variant_new_double(m->speed > 0 ? m->speed : 1);
    if (!strcmp(name, "MinimumRate")) return g_variant_new_double((m->actions & (1 << 12)) ? m->minimum_rate : (m->speed > 0 ? m->speed : 1));
    if (!strcmp(name, "MaximumRate")) return g_variant_new_double((m->actions & (1 << 12)) ? m->maximum_rate : (m->speed > 0 ? m->speed : 1));
    if (!strcmp(name, "CanControl")) return g_variant_new_boolean(m->actions != 0);
    if (!strcmp(name, "CanGoNext")) return g_variant_new_boolean(m->present && (m->actions & (1 << 4)));
    if (!strcmp(name, "CanGoPrevious")) return g_variant_new_boolean(m->present && (m->actions & (1 << 5)));
    if (!strcmp(name, "CanPlay")) return g_variant_new_boolean(m->present && (m->actions & (1 << 1)));
    if (!strcmp(name, "CanPause")) return g_variant_new_boolean(m->present && (m->actions & (1 << 2)));
    if (!strcmp(name, "CanSeek")) return g_variant_new_boolean(m->present && m->duration > 0 && (m->actions & ((1 << 7) | (1 << 8))));
    if (!strcmp(name, "Position")) {
        double pos = m->position;
        if (m->playing) pos += (g_get_monotonic_time() - m->sampled_at) / 1000.0 * m->speed;
        if (m->duration > 0) pos = MIN(pos, m->duration);
        return g_variant_new_int64((gint64)(MAX(0, pos) * 1000));
    }
    if (!strcmp(name, "Metadata")) {
        GVariantBuilder b;
        g_variant_builder_init(&b, G_VARIANT_TYPE("a{sv}"));
        if (m->present) {
            char *path = track_path(m);
            g_variant_builder_add(&b, "{sv}", "mpris:trackid", g_variant_new_object_path(path));
            g_free(path);
            if (m->duration >= 0) g_variant_builder_add(&b, "{sv}", "mpris:length", g_variant_new_int64((gint64)(m->duration * 1000)));
            g_variant_builder_add(&b, "{sv}", "xesam:title", g_variant_new_string(m->title));
            const char *artists[] = {m->artist, NULL};
            g_variant_builder_add(&b, "{sv}", "xesam:artist", g_variant_new_strv(artists, -1));
            g_variant_builder_add(&b, "{sv}", "xesam:album", g_variant_new_string(m->album));
            if (m->artwork && *m->artwork)
                g_variant_builder_add(&b, "{sv}", "mpris:artUrl", g_variant_new_string(m->artwork));
        }
        return g_variant_builder_end(&b);
    }
    return NULL;
}

static GVariant *get_property(GDBusConnection *c, const char *sender, const char *path,
    const char *iface, const char *name, GError **error, void *user) {
    KMedia *m = user;
    g_mutex_lock(&m->mutex);
    GVariant *value = property(m, name);
    g_mutex_unlock(&m->mutex);
    return value;
}
static gboolean set_property(GDBusConnection *c, const char *sender, const char *path,
    const char *iface, const char *name, GVariant *value, GError **error, void *user) {
    KMedia *m = user;
    int command = 0; double number = 0;
    if (!strcmp(name, "Volume")) { command = 9; number = g_variant_get_double(value); }
    else if (!strcmp(name, "Shuffle")) { command = 10; number = g_variant_get_boolean(value); }
    else if (!strcmp(name, "Rate")) { command = 12; number = g_variant_get_double(value); }
    else if (!strcmp(name, "LoopStatus")) {
        const char *v = g_variant_get_string(value, NULL);
        if (strcmp(v, "None") && strcmp(v, "Playlist") && strcmp(v, "Track")) {
            g_set_error_literal(error, G_DBUS_ERROR, G_DBUS_ERROR_INVALID_ARGS, "Invalid loop status");
            return FALSE;
        }
        command = 11; number = !strcmp(v, "Playlist") ? 1 : !strcmp(v, "Track") ? 2 : 0;
    }
    if (!command || !m->callback(m->user, command, number, NULL)) {
        g_set_error_literal(error, G_DBUS_ERROR, G_DBUS_ERROR_NOT_SUPPORTED, "Command unavailable");
        return FALSE;
    }
    return TRUE;
}
static void method(GDBusConnection *c, const char *sender, const char *path,
    const char *iface, const char *name, GVariant *params, GDBusMethodInvocation *inv, void *user) {
    KMedia *m = user;
    int cmd = 0;
    char *entry = NULL;
    double value = 0;
    if (!strcmp(name, "Play")) cmd = 1;
    else if (!strcmp(name, "Pause")) cmd = 2;
    else if (!strcmp(name, "PlayPause")) cmd = 3;
    else if (!strcmp(name, "Next")) cmd = 4;
    else if (!strcmp(name, "Previous")) cmd = 5;
    else if (!strcmp(name, "Stop")) cmd = 6;
    else if (!strcmp(name, "Raise")) cmd = 13;
    else if (!strcmp(name, "Quit")) cmd = 14;
    else if (!strcmp(name, "Seek")) {
        gint64 pos; g_variant_get(params, "(x)", &pos); cmd = 8; value = pos / 1000.0;
        g_mutex_lock(&m->mutex); entry = g_strdup(m->id); g_mutex_unlock(&m->mutex);
    } else if (!strcmp(name, "SetPosition")) {
        const char *path; gint64 pos; g_variant_get(params, "(&ox)", &path, &pos);
        g_mutex_lock(&m->mutex); char *current = track_path(m);
        if (m->present && !strcmp(path, current) && pos >= 0) { cmd = 7; value = pos / 1000.0; entry = g_strdup(m->id); }
        g_free(current); g_mutex_unlock(&m->mutex);
    } else {
        g_dbus_method_invocation_return_error(inv, G_DBUS_ERROR, G_DBUS_ERROR_NOT_SUPPORTED, "Unsupported command");
        return;
    }
    if (cmd) m->callback(m->user, cmd, value, entry);
    g_free(entry);
    g_dbus_method_invocation_return_value(inv, NULL);
}
static const GDBusInterfaceVTable vtable = {method, get_property, set_property, {0}};

static gboolean mark_started(void *user) {
    KMedia *m = user;
    g_mutex_lock(&m->mutex);
    m->ready = TRUE;
    g_cond_signal(&m->started);
    g_mutex_unlock(&m->mutex);
    return G_SOURCE_REMOVE;
}

static void *run_bus(void *user) {
    KMedia *m = user;
    m->context = g_main_context_new();
    g_main_context_push_thread_default(m->context);
    m->loop = g_main_loop_new(m->context, FALSE);
    GError *error = NULL;
    char *address = g_dbus_address_get_for_bus_sync(G_BUS_TYPE_SESSION, NULL, &error);
    if (address) {
        m->bus = g_dbus_connection_new_for_address_sync(address,
            G_DBUS_CONNECTION_FLAGS_AUTHENTICATION_CLIENT | G_DBUS_CONNECTION_FLAGS_MESSAGE_BUS_CONNECTION,
            NULL, NULL, &error);
        g_free(address);
    }
    if (m->bus) {
        m->node = g_dbus_node_info_new_for_xml(xml, &error);
        for (int i = 0; m->node && i < 2 && !error; i++)
            m->objects[i] = g_dbus_connection_register_object(m->bus, "/org/mpris/MediaPlayer2", m->node->interfaces[i], &vtable, m, NULL, &error);
        if (!error) {
            GVariant *reply = g_dbus_connection_call_sync(m->bus, "org.freedesktop.DBus",
                "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName",
                g_variant_new("(su)", m->bus_name, 4u), G_VARIANT_TYPE("(u)"),
                G_DBUS_CALL_FLAGS_NONE, 5000, NULL, &error);
            if (reply) {
                guint result; g_variant_get(reply, "(u)", &result); g_variant_unref(reply);
                if (result != 1 && result != 4) g_set_error_literal(&error, G_DBUS_ERROR, G_DBUS_ERROR_FAILED, "MPRIS name unavailable");
            }
        }
    }
    m->failed = error != NULL || !m->bus;
    if (error) { g_error_free(error); }
    if (m->failed) mark_started(m);
    else {
        GSource *started = g_idle_source_new();
        g_source_set_callback(started, mark_started, m, NULL);
        g_source_attach(started, m->context);
        g_source_unref(started);
        g_main_loop_run(m->loop);
    }
    if (m->owner) g_bus_unown_name(m->owner);
    for (int i = 0; i < 2; i++) if (m->objects[i]) g_dbus_connection_unregister_object(m->bus, m->objects[i]);
    if (m->node) g_dbus_node_info_unref(m->node);
    if (m->bus) { g_dbus_connection_close_sync(m->bus, NULL, NULL); g_object_unref(m->bus); }
    g_main_loop_unref(m->loop);
    g_main_context_pop_thread_default(m->context);
    g_main_context_unref(m->context);
    return NULL;
}

void *kmedia_create(KMediaCommand callback, void *user, const char *id,
    const char *display_name, const char *desktop_entry, void *window, double minimum_rate, double maximum_rate) {
    KMedia *m = g_new0(KMedia, 1);
    m->callback = callback; m->user = user; m->speed = m->volume = 1;
    m->identity = g_strdup(display_name); m->desktop_entry = g_strdup(desktop_entry);
    m->minimum_rate = minimum_rate; m->maximum_rate = maximum_rate;
    m->bus_name = g_strdup_printf("%s.instance%d_%p", id, getpid(), (void *)m);
    g_mutex_init(&m->mutex); g_cond_init(&m->started);
    g_mutex_lock(&m->mutex);
    m->thread = g_thread_new("kmedia-notification", run_bus, m);
    while (!m->ready) g_cond_wait(&m->started, &m->mutex);
    gboolean failed = m->failed;
    g_mutex_unlock(&m->mutex);
    if (failed) { g_thread_join(m->thread); g_cond_clear(&m->started); g_mutex_clear(&m->mutex); g_free(m->identity); g_free(m->desktop_entry); g_free(m->bus_name); g_free(m); return NULL; }
    return m;
}

void kmedia_update(void *handle, const char *id, const char *title, const char *artist,
    const char *album, const char *artwork, int present, int playing, int actions, int kind,
    double position, double duration, double speed, double volume, int shuffle, int repeat, int liked, int discontinuity) {
    KMedia *m = handle;
    g_mutex_lock(&m->mutex);
    int old_actions = m->actions;
    double expected = m->position + (m->playing ? (g_get_monotonic_time() - m->sampled_at) / 1000.0 * m->speed : 0);
    gboolean sought = !g_strcmp0(id, m->id) && (discontinuity || position - expected > 1500 || expected - position > 1500);
    const char *names[] = {"PlaybackStatus", "Metadata", "CanGoNext", "CanGoPrevious", "CanPlay", "CanPause", "CanSeek", "LoopStatus", "Shuffle", "Volume", "Rate", "MinimumRate", "MaximumRate"};
    GVariant *old[G_N_ELEMENTS(names)];
    for (guint i = 0; i < G_N_ELEMENTS(names); i++) old[i] = g_variant_ref_sink(property(m, names[i]));
    #define SET_STRING(field, value) do { g_free(m->field); m->field = g_strdup(value); } while (0)
    SET_STRING(id, id); SET_STRING(title, title); SET_STRING(artist, artist); SET_STRING(album, album); SET_STRING(artwork, artwork);
    m->present = present; m->playing = playing; m->actions = actions;
    m->position = position; m->duration = duration; m->speed = speed; m->volume = volume; m->shuffle = shuffle; m->repeat = repeat;
    m->sampled_at = g_get_monotonic_time();
    GVariantBuilder changed;
    g_variant_builder_init(&changed, G_VARIANT_TYPE("a{sv}"));
    guint count = 0;
    for (guint i = 0; i < G_N_ELEMENTS(names); i++) {
        GVariant *value = g_variant_ref_sink(property(m, names[i]));
        if (!g_variant_equal(old[i], value)) { g_variant_builder_add(&changed, "{sv}", names[i], value); count++; }
        g_variant_unref(old[i]); g_variant_unref(value);
    }
    if (count) g_dbus_connection_emit_signal(m->bus, NULL, "/org/mpris/MediaPlayer2", "org.freedesktop.DBus.Properties", "PropertiesChanged",
        g_variant_new("(sa{sv}as)", "org.mpris.MediaPlayer2.Player", &changed, NULL), NULL);
    else g_variant_builder_clear(&changed);
    if ((old_actions ^ actions) & ((1 << 13) | (1 << 14))) {
        GVariantBuilder root_changed;
        g_variant_builder_init(&root_changed, G_VARIANT_TYPE("a{sv}"));
        if ((old_actions ^ actions) & (1 << 13))
            g_variant_builder_add(&root_changed, "{sv}", "CanRaise", property(m, "CanRaise"));
        if ((old_actions ^ actions) & (1 << 14))
            g_variant_builder_add(&root_changed, "{sv}", "CanQuit", property(m, "CanQuit"));
        g_dbus_connection_emit_signal(m->bus, NULL, "/org/mpris/MediaPlayer2", "org.freedesktop.DBus.Properties", "PropertiesChanged",
            g_variant_new("(sa{sv}as)", "org.mpris.MediaPlayer2", &root_changed, NULL), NULL);
    }
    if (sought) g_dbus_connection_emit_signal(m->bus, NULL, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", "Seeked", g_variant_new("(x)", (gint64)(position * 1000)), NULL);
    g_mutex_unlock(&m->mutex);
}
static gboolean quit_loop(void *loop) { g_main_loop_quit(loop); return G_SOURCE_REMOVE; }
void kmedia_destroy(void *handle) {
    KMedia *m = handle;
    g_main_context_invoke(m->context, quit_loop, m->loop);
    g_thread_join(m->thread);
    g_free(m->id); g_free(m->title); g_free(m->artist); g_free(m->album); g_free(m->artwork);
    g_free(m->identity); g_free(m->desktop_entry); g_free(m->bus_name);
    g_cond_clear(&m->started); g_mutex_clear(&m->mutex); g_free(m);
}

void *kmedia_native_handle(void *handle) { return ((KMedia *)handle)->bus; }
