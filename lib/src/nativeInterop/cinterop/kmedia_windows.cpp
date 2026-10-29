#include "kmedia_notification.h"
#include <windows.h>
#include <roapi.h>
#include <winstring.h>
#include <inspectable.h>
#include <new>
#include <string.h>

/* Minimal WinRT ABI access keeps this bridge buildable with the MinGW SDK.
 * Slots and interface IDs follow Microsoft's windows.media.h and
 * SystemMediaTransportControlsInterop.h. No Windows runtime is bundled. */
static const GUID uri_factory_iid = {0x44a9796f,0x723e,0x4fdf,{0xa2,0x18,0x03,0x3e,0x75,0xb0,0xc0,0x84}};
static const GUID stream_reference_iid = {0x857309dc,0x3fbf,0x4e7d,{0x98,0x6f,0xef,0x3b,0x1a,0x07,0xa9,0x64}};
static const GUID random_stream_iid = {0x905a0fe1,0xbc53,0x11df,{0x8c,0x49,0x00,0x1e,0x4f,0xc6,0x86,0xda}};
static const GUID control_iid = {0x99fa3ff4,0x1742,0x42a6,{0x90,0x2e,0x08,0x7d,0x41,0xf9,0x65,0xec}};
static const GUID control2_iid = {0xea98d2f6,0x7f3c,0x4af2,{0xa5,0x86,0x72,0x88,0x98,0x08,0xef,0xb1}};
static const GUID interop_iid = {0xddb0472d,0xc911,0x4a1f,{0x86,0xd9,0xdc,0x3d,0x71,0xa9,0x5f,0x5a}};
static const GUID music2_iid = {0x00368462,0x97d3,0x44b9,{0xb0,0x0f,0x00,0x8a,0xfc,0xef,0xaf,0x18}};
static const GUID button_delegate_iid = {0x0557e996,0x7b23,0x5bae,{0xaa,0x81,0xea,0x0d,0x67,0x11,0x43,0xa4}};
static const GUID seek_delegate_iid = {0x44e34f15,0xbdc0,0x50a7,{0xac,0xe4,0x39,0xe9,0x1f,0xb7,0x53,0xf1}};
static const GUID timeline_iid = {0x5125316a,0xc3a2,0x475b,{0x85,0x07,0x93,0x53,0x4d,0xc8,0x8f,0x15}};
static const GUID agile_iid = {0x94ea2b94,0xe9cc,0x49e0,{0xc0,0xff,0xee,0x64,0xca,0x8f,0x5b,0x90}};

template<class... Args> static HRESULT invoke(IUnknown *object, int slot, Args... args) {
    using Method = HRESULT (STDMETHODCALLTYPE *)(IUnknown *, Args...);
    return reinterpret_cast<Method>((*reinterpret_cast<void ***>(object))[slot])(object, args...);
}
static HSTRING string_utf8(const char *text) {
    int size = MultiByteToWideChar(CP_UTF8, 0, text, -1, NULL, 0);
    wchar_t *wide = static_cast<wchar_t *>(HeapAlloc(GetProcessHeap(), 0, size * sizeof(wchar_t)));
    if (!wide) return NULL;
    MultiByteToWideChar(CP_UTF8, 0, text, -1, wide, size);
    HSTRING result = NULL;
    WindowsCreateString(wide, size - 1, &result);
    HeapFree(GetProcessHeap(), 0, wide);
    return result;
}
static void set_string(IUnknown *object, int slot, const char *value) {
    HSTRING text = string_utf8(value);
    invoke(object, slot, text);
    WindowsDeleteString(text);
}

static IUnknown *factory_for(const char *name, const GUID &iid) {
    HSTRING type = string_utf8(name);
    IUnknown *factory = NULL;
    RoGetActivationFactory(type, iid, reinterpret_cast<void **>(&factory));
    WindowsDeleteString(type);
    return factory;
}
static IUnknown *thumbnail_for(const char *url) {
    if (!url || !*url) return NULL;
    if (!strncmp(url, "file://", 7)) {
        // CreateFromUri accepts web/package URIs, not file://. Open app-supplied cached
        // artwork as a local stream instead of asking WinRT to download it.
        static HMODULE shell = LoadLibraryExW(L"shlwapi.dll", NULL, LOAD_LIBRARY_SEARCH_SYSTEM32);
        static HMODULE core = LoadLibraryExW(L"shcore.dll", NULL, LOAD_LIBRARY_SEARCH_SYSTEM32);
        if (!shell || !core) return NULL;
        using ToPath = HRESULT (WINAPI *)(PCWSTR, PWSTR, DWORD *, DWORD);
        using OpenFile = HRESULT (WINAPI *)(PCWSTR, DWORD, DWORD, BOOL, IStream *, IStream **);
        using ToRandom = HRESULT (WINAPI *)(IStream *, int, REFIID, void **);
        auto to_path = reinterpret_cast<ToPath>(GetProcAddress(shell, "PathCreateFromUrlW"));
        auto open_file = reinterpret_cast<OpenFile>(GetProcAddress(shell, "SHCreateStreamOnFileEx"));
        auto to_random = reinterpret_cast<ToRandom>(GetProcAddress(core, "CreateRandomAccessStreamOverStream"));
        if (!to_path || !open_file || !to_random) return NULL;
        HSTRING text = string_utf8(url);
        wchar_t path[32768]; DWORD count = 32768;
        HRESULT hr = to_path(WindowsGetStringRawBuffer(text, NULL), path, &count, 0);
        WindowsDeleteString(text);
        if (FAILED(hr)) return NULL;
        IStream *file = NULL;
        hr = open_file(path, STGM_READ | STGM_SHARE_DENY_NONE, FILE_ATTRIBUTE_NORMAL, FALSE, NULL, &file);
        if (FAILED(hr) || !file) return NULL;
        IUnknown *stream = NULL;
        hr = to_random(file, 0, random_stream_iid, reinterpret_cast<void **>(&stream));
        file->Release();
        if (FAILED(hr) || !stream) return NULL;
        IUnknown *factory = factory_for("Windows.Storage.Streams.RandomAccessStreamReference", stream_reference_iid);
        IUnknown *reference = NULL;
        if (factory) { invoke(factory, 8, stream, &reference); factory->Release(); }
        stream->Release();
        return reference;
    }
    IUnknown *uri_factory = factory_for("Windows.Foundation.Uri", uri_factory_iid);
    if (!uri_factory) return NULL;
    HSTRING text = string_utf8(url);
    IUnknown *uri = NULL;
    HRESULT hr = invoke(uri_factory, 6, text, &uri);
    WindowsDeleteString(text); uri_factory->Release();
    if (FAILED(hr) || !uri) return NULL;
    IUnknown *stream_factory = factory_for("Windows.Storage.Streams.RandomAccessStreamReference", stream_reference_iid);
    IUnknown *reference = NULL;
    if (stream_factory) {
        invoke(stream_factory, 7, uri, &reference);
        stream_factory->Release();
    }
    uri->Release();
    return reference;
}

/* WinRT typed delegates inherit IUnknown and expose Invoke at slot 3. */
struct MediaHandler : IUnknown {
    LONG refs = 1;
    CRITICAL_SECTION mutex;
    KMediaCommand callback;
    void *user;
    bool seek;
    MediaHandler(KMediaCommand cb, void *u, bool s) : callback(cb), user(u), seek(s) { InitializeCriticalSection(&mutex); }
    ~MediaHandler() { DeleteCriticalSection(&mutex); }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id, void **out) override {
        if (!out) return E_POINTER;
        *out = NULL;
        if (IsEqualIID(id, IID_IUnknown) || IsEqualIID(id, agile_iid) ||
            IsEqualIID(id, seek ? seek_delegate_iid : button_delegate_iid)) {
            *out = this; AddRef(); return S_OK;
        }
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&refs); }
    ULONG STDMETHODCALLTYPE Release() override {
        ULONG count = InterlockedDecrement(&refs);
        if (!count) { this->~MediaHandler(); HeapFree(GetProcessHeap(), 0, this); }
        return count;
    }
    virtual HRESULT STDMETHODCALLTYPE Invoke(IUnknown *, IUnknown *args) {
        EnterCriticalSection(&mutex);
        if (callback) {
            if (seek) {
                INT64 ticks = 0;
                if (SUCCEEDED(invoke(args, 6, &ticks))) callback(user, 7, ticks / 10000.0, NULL);
            } else {
                int button = -1;
                if (SUCCEEDED(invoke(args, 6, &button))) {
                    int command = button == 0 ? 1 : button == 1 ? 2 : button == 2 ? 6 : button == 6 ? 4 : button == 7 ? 5 : 0;
                    if (command) callback(user, command, 0, NULL);
                }
            }
        }
        LeaveCriticalSection(&mutex);
        return S_OK;
    }
    void disable() { EnterCriticalSection(&mutex); callback = NULL; user = NULL; LeaveCriticalSection(&mutex); }
};
struct KMedia {
    IUnknown *control = NULL, *control2 = NULL, *display = NULL, *music = NULL, *music2 = NULL, *video = NULL, *timeline = NULL;
    char *artwork = NULL;
    HWND window = NULL;
    KMedia *next_owner = NULL;
    bool owns_window = false;
    MediaHandler *buttons = NULL, *seek = NULL;
    INT64 button_token = 0, seek_token = 0;
    bool button_registered = false, seek_registered = false, initialized = false;
};
static SRWLOCK owner_lock = SRWLOCK_INIT;
static KMedia *window_owners = NULL;
static BOOL CALLBACK find_window(HWND hwnd, LPARAM data) {
    DWORD pid = 0;
    GetWindowThreadProcessId(hwnd, &pid);
    if (pid == GetCurrentProcessId() && GetWindow(hwnd, GW_OWNER) == NULL && IsWindowVisible(hwnd)) {
        *reinterpret_cast<HWND *>(data) = hwnd;
        return FALSE;
    }
    return TRUE;
}
void kmedia_destroy(void *handle) {
    KMedia *m = static_cast<KMedia *>(handle);
    if (m->buttons) m->buttons->disable();
    if (m->seek) m->seek->disable();
    if (m->control) {
        invoke(m->control, 11, static_cast<BYTE>(0)); // IsEnabled
        if (m->button_registered) invoke(m->control, 33, m->button_token);
    }
    if (m->control2 && m->seek_registered) invoke(m->control2, 14, m->seek_token);
    if (m->buttons) m->buttons->Release();
    if (m->seek) m->seek->Release();
    if (m->display) invoke(m->display, 16); // ClearAll
    if (m->artwork) HeapFree(GetProcessHeap(), 0, m->artwork);
    IUnknown *objects[] = {m->timeline, m->video, m->music2, m->music, m->display, m->control2, m->control};
    for (IUnknown *o : objects) if (o) o->Release();
    if (m->owns_window) {
        AcquireSRWLockExclusive(&owner_lock);
        KMedia **owner = &window_owners;
        while (*owner && *owner != m) owner = &(*owner)->next_owner;
        if (*owner == m) *owner = m->next_owner;
        ReleaseSRWLockExclusive(&owner_lock);
    }
    bool initialized = m->initialized;
    m->~KMedia();
    HeapFree(GetProcessHeap(), 0, m);
    if (initialized) RoUninitialize();
}
void *kmedia_create(KMediaCommand callback, void *user, const char *id,
    const char *display_name, const char *desktop_entry, void *provided_window, double minimum_rate, double maximum_rate) {
    void *storage = HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(KMedia));
    if (!storage) return NULL;
    KMedia *m = new (storage) KMedia();
    HRESULT hr = RoInitialize(RO_INIT_SINGLETHREADED);
    m->initialized = SUCCEEDED(hr);
    if (FAILED(hr) && hr != RPC_E_CHANGED_MODE) { kmedia_destroy(m); return NULL; }
    HWND window = static_cast<HWND>(provided_window);
    if (!window) EnumWindows(find_window, reinterpret_cast<LPARAM>(&window));
    if (!window) { kmedia_destroy(m); return NULL; }
    AcquireSRWLockExclusive(&owner_lock);
    for (KMedia *owner = window_owners; owner; owner = owner->next_owner) {
        if (owner->window == window) {
            ReleaseSRWLockExclusive(&owner_lock); kmedia_destroy(m); return NULL;
        }
    }
    m->window = window; m->owns_window = true; m->next_owner = window_owners; window_owners = m;
    ReleaseSRWLockExclusive(&owner_lock);
    HSTRING type = NULL;
    WindowsCreateString(L"Windows.Media.SystemMediaTransportControls", 42, &type);
    IUnknown *factory = NULL;
    hr = RoGetActivationFactory(type, interop_iid, reinterpret_cast<void **>(&factory));
    WindowsDeleteString(type);
    if (SUCCEEDED(hr) && window) hr = invoke(factory, 3, window, &control_iid, reinterpret_cast<void **>(&m->control));
    if (factory) factory->Release();
    if (FAILED(hr) || !m->control) { kmedia_destroy(m); return NULL; }
    hr = invoke(m->control, 8, &m->display);
    if (SUCCEEDED(hr)) hr = invoke(m->display, 12, &m->music);
    if (FAILED(hr)) { kmedia_destroy(m); return NULL; }
    invoke(m->display, 13, &m->video);
    m->music->QueryInterface(music2_iid, reinterpret_cast<void **>(&m->music2));
    m->control->QueryInterface(control2_iid, reinterpret_cast<void **>(&m->control2));
    storage = HeapAlloc(GetProcessHeap(), 0, sizeof(MediaHandler));
    m->buttons = storage ? new (storage) MediaHandler(callback, user, false) : NULL;
    if (!m->buttons) { kmedia_destroy(m); return NULL; }
    hr = invoke(m->control, 32, static_cast<IUnknown *>(m->buttons), &m->button_token);
    m->button_registered = SUCCEEDED(hr);
    if (!m->button_registered) { kmedia_destroy(m); return NULL; }
    if (m->control2) {
        storage = HeapAlloc(GetProcessHeap(), 0, sizeof(MediaHandler));
        m->seek = storage ? new (storage) MediaHandler(callback, user, true) : NULL;
        if (m->seek) m->seek_registered = SUCCEEDED(invoke(m->control2, 13, static_cast<IUnknown *>(m->seek), &m->seek_token));
        WindowsCreateString(L"Windows.Media.SystemMediaTransportControlsTimelineProperties", 60, &type);
        IInspectable *timeline = NULL;
        if (SUCCEEDED(RoActivateInstance(type, &timeline))) {
            timeline->QueryInterface(timeline_iid, reinterpret_cast<void **>(&m->timeline));
            timeline->Release();
        }
        WindowsDeleteString(type);
    }
    return m;
}
void kmedia_update(void *handle, const char *id, const char *title, const char *artist,
    const char *album, const char *artwork, int present, int playing, int actions, int kind,
    double position, double duration, double speed, double volume, int shuffle, int repeat, int liked, int discontinuity) {
    KMedia *m = static_cast<KMedia *>(handle);
    invoke(m->control, 11, static_cast<BYTE>(present != 0));
    invoke(m->control, 13, static_cast<BYTE>(present && (actions & (1 << 1))));
    invoke(m->control, 15, static_cast<BYTE>(present && (actions & (1 << 2))));
    invoke(m->control, 17, static_cast<BYTE>(present && (actions & (1 << 6))));
    invoke(m->control, 25, static_cast<BYTE>(present && (actions & (1 << 5))));
    invoke(m->control, 27, static_cast<BYTE>(present && (actions & (1 << 4))));
    invoke(m->control, 7, !present ? 0 : playing ? 3 : 4);
    if (!present) { invoke(m->display, 16); if (m->artwork) HeapFree(GetProcessHeap(), 0, m->artwork); m->artwork = NULL; return; }
    invoke(m->display, 7, kind && m->video ? 2 : 1); // Video / Music
    set_string(m->display, 9, id);
    if (kind && m->video) {
        set_string(m->video, 7, title); set_string(m->video, 9, artist);
    } else {
        set_string(m->music, 7, title); set_string(m->music, 9, artist);
        set_string(m->music, 11, artist);
        if (m->music2) set_string(m->music2, 7, album);
    }
    if (!m->artwork || strcmp(m->artwork, artwork)) {
        IUnknown *thumbnail = thumbnail_for(artwork);
        invoke(m->display, 11, thumbnail);
        if (thumbnail) thumbnail->Release();
        if (m->artwork) HeapFree(GetProcessHeap(), 0, m->artwork);
        size_t count = strlen(artwork) + 1;
        m->artwork = static_cast<char *>(HeapAlloc(GetProcessHeap(), 0, count));
        if (m->artwork) memcpy(m->artwork, artwork, count);
    }
    invoke(m->display, 17);
    if (m->control2) {
        // Windows enum: None, Track, List; shared enum: Off, All, One.
        invoke(m->control2, 7, repeat == 1 ? 2 : repeat == 2 ? 1 : 0);
        invoke(m->control2, 9, static_cast<BYTE>(shuffle != 0));
        invoke(m->control2, 11, speed);
        if (m->timeline && duration > 0) {
            invoke(m->timeline, 7, static_cast<INT64>(0));
            invoke(m->timeline, 9, static_cast<INT64>(duration * 10000));
            invoke(m->timeline, 11, static_cast<INT64>(0));
            invoke(m->timeline, 13, static_cast<INT64>(duration * 10000));
            invoke(m->timeline, 15, static_cast<INT64>(position * 10000));
            invoke(m->control2, 12, m->timeline);
        }
    }
}

void *kmedia_native_handle(void *handle) { return static_cast<KMedia *>(handle)->control; }
