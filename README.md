# KMediaNotification

[![Maven Central](https://img.shields.io/maven-central/v/dev.brahmkshatriya.kmedianotification/kmedia-notification?label=Maven%20Central)](https://central.sonatype.com/artifact/dev.brahmkshatriya.kmedianotification/kmedia-notification)
[![Publish](https://github.com/brahmkshatriya/kmedianotification/actions/workflows/publish.yml/badge.svg)](https://github.com/brahmkshatriya/kmedianotification/actions/workflows/publish.yml)

Kotlin Multiplatform media notifications and system playback controls for Android, iOS, macOS, Linux, Windows, JVM, JavaScript, and WasmJS.

Connect your player by publishing its state and handling remote commands. Your app owns playback, the queue, audio focus, and the background lifecycle. No Compose or player engine is required.

## Installation

Add `mavenCentral()` and `google()` to your dependency repositories, then use the version shown in the Maven Central badge:

```kotlin
commonMain.dependencies {
    implementation("dev.brahmkshatriya.kmedianotification:kmedia-notification:<version>")
}
```

## Quick start

Create a `MediaPlatform` in platform code using the setup below, and pass it into your shared playback owner. Give the session a scope that lives as long as playback.

```kotlin
import dev.brahmkshatriya.kmedianotification.*
import kotlin.time.Duration.Companion.seconds

val session = KMediaNotification.create(
    platform = platform,
    scope = playbackScope,
    id = "player",
    displayName = "My Player",
) {
    onPlay { player.play() }
    onPause { player.pause() }
    onNext { queue.next() }
    onPrevious { queue.previous() }
    onSeekTo { request -> player.seekTo(request.position) }
}

session.publish(
    MediaSessionState(
        item = NowPlayingItem(
            mediaId = "track-12",
            entryId = "queue-entry-93",
            title = "Example",
            artists = listOf("Artist"),
            artworkUri = "https://example.com/cover.jpg",
        ),
        playback = PlaybackState.playing(
            position = 42.seconds,
            duration = 180.seconds,
        ),
    )
)

session.activate()
```

Handlers are suspend functions and run serially on the supplied scope's dispatcher. Publish the player's resulting state after each command; commands do not update the snapshot automatically. Alternatively, bind a state flow with `session.bind(states.map(::toMediaSessionState))`.

### Timeline and artwork

Publish a new position sample when seeking, pausing, buffering, or changing playback speed. Keep the existing `PositionSample` when only metadata or artwork changes. `positionNow()` projects progress between samples, so no polling timer is needed. Use a null duration when it is unknown, and `isAdvancing = false` while playback is stalled.

Use a distinct `entryId` for each queue occurrence, including repeated songs. The optional `queue` snapshot must contain the current item and have unique entry IDs. Seek and Like requests retain the occurrence they target; stale requests are rejected.

To reuse your image loader or cache, supply encoded artwork bytes:

```kotlin
item.copy(
    artworkUri = null,
    artwork = MediaArtwork(encodedImageBytes, "image/jpeg"),
)
```

`MediaArtwork` copies the bytes and takes precedence over `artworkUri`. The library handles delivery to the platform and cleanup of temporary artwork files.

## Platform setup

| Platform | Targets | System integration |
| --- | --- | --- |
| Android | API 24+ | Media3 session and media notification |
| iOS | arm64, x64 simulator, arm64 simulator | Now Playing and remote commands |
| macOS | x64, arm64 | Now Playing and remote commands |
| Linux | x64, arm64 | MPRIS over the session bus |
| Windows | x64 | System Media Transport Controls |
| JVM | Linux x64/arm64, Windows x64, macOS x64/arm64 | Native host integration |
| Browser | JS, WasmJS | Media Session API |

### Android

```kotlin
val platform = MediaPlatform.android(applicationContext) {
    serviceClass = PlaybackService::class.java
    notification {
        channelId = "playback"
        channelName = "Playback"
        smallIcon = R.drawable.ic_media
    }
    sessionActivity = openPlayerPendingIntent
}

class PlaybackService : MediaNotificationService() {
    override fun provideMediaSession(): MediaSession =
        (application as MyApplication).playback.session
}
```

Resolve the same app-owned session from your service, without depending on an Activity. Declare the service in your app manifest:

```xml
<service
    android:name=".PlaybackService"
    android:exported="true"
    android:foregroundServiceType="mediaPlayback">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
    </intent-filter>
</service>
```

The library declares the foreground-service and network permissions. Your app handles notification permission UX, audio focus, interruptions, and wake locks. Activation binds the service; Media3 promotes it to the foreground during playback. Trusted controllers are allowed by default; use `allowController` to customize access.

Customize button icons with drawable resources:

```kotlin
val platform = MediaPlatform.android(applicationContext) {
    buttonIcon = { request ->
        when (request.icon) {
            MediaIcon.Play -> R.drawable.my_play
            MediaIcon.Pause -> R.drawable.my_pause
            MediaIcon.Previous -> R.drawable.my_previous
            MediaIcon.Next -> if (request.enabled) R.drawable.my_next else R.drawable.my_next_disabled
            MediaIcon.Favorite -> R.drawable.my_heart_outline
            MediaIcon.FavoriteFilled -> R.drawable.my_heart_filled
            else -> null // Keep the default icon.
        }
    }
}
```

The resolver receives the semantic icon, button, and enabled state. Android 13+ may draw its own standard transport icons; custom action icons use the supplied drawables. Other platforms choose their system icons.

### Desktop

Use the same factory from JVM and shared desktop Kotlin/Native code:

```kotlin
val platform = MediaPlatform.desktop {
    linuxBusName = "org.mpris.MediaPlayer2.my_player"
    linuxDesktopEntry = "com.example.Player"
    // windowsHandle = hwndAddress
}
```

Linux needs GLib/GIO runtime libraries and a session D-Bus connection. Windows needs a visible application window; set `windowsHandle` to choose one explicitly. macOS JVM apps need an AppKit event loop. The release JVM JAR includes the native bridges; `nativeLibraryPath` can override their location for application packaging or signing.

The `MediaPlatform.linux`, `MediaPlatform.windows`, and `MediaPlatform.apple` factories offer additional platform-specific configuration from their respective Kotlin/Native source sets.

### Apple

```kotlin
val platform = MediaPlatform.apple {
    customizeInfo = { /* Additional Now Playing metadata. */ }
    customizeCommands = { /* Native command configuration. */ }
}
```

Configure your app's audio session and background-audio capability separately. Apple's shared command center permits one active owner at a time.

### Browser

```kotlin
val platform = MediaPlatform.browser {
    afterPublish = { /* Optional browser-native customization. */ }
}
```

Controls depend on the browser's Media Session support and activation policy. A document permits one active owner at a time.

## Buttons and capabilities

Registered handlers determine supported commands. `MediaSessionState.availableActions` can narrow them for the current item; null means all registered actions. `buttons` sets the preferred order. Unsupported controls are omitted, and the system decides the final layout.

```kotlin
// Register inside KMediaNotification.create { ... }.
onSetLiked { request -> favourites.set(request.entryId, request.liked) }
onSetRepeatMode { mode -> player.repeatMode = mode }

session.publish(currentState.copy(
    liked = favourites.isLiked(currentEntryId),
    buttons = listOf(
        MediaButton.Previous, MediaButton.PlayPause, MediaButton.Next,
        MediaButton.Like, MediaButton.Repeat,
    ),
))
```

Publish a non-null `liked` value to enable Like. Publish updated liked, shuffle, and repeat values after handling those commands.

| Control | Android | Apple Native / macOS JVM | Linux | Windows | Browser |
| --- | --- | --- | --- | --- | --- |
| Like | Yes | Native feedback command | No | No | No |
| Repeat / Shuffle | Yes | Yes | Yes | State only | No |
| Custom buttons | Yes | No | No | No | No |

To retain an unavailable skip control, use `MediaButton.Standard(MediaAction.Next, showWhenUnavailable = true)` or the equivalent Previous action. On Android 13+, supplying a dimmed icon shows a placeholder whose taps are ignored; otherwise the system reserves an empty slot. Put disabled opacity in the drawable's `android:fillAlpha`.

Observe `session.controls` for effective buttons/actions and `session.capabilities` for platform support instead of branching on operating-system names.

### Custom actions and native access

```kotlin
val bookmark = ActionKey.unit("player.bookmark")

// Register inside KMediaNotification.create { ... }.
onCustom(bookmark) { bookmarks.addCurrent() }

session.publish(currentState.copy(
    buttons = listOf(MediaButton.PlayPause, bookmark.button("Bookmark", MediaIcon.Bookmark)),
))
```

Use `ActionKey<T>` with an `ActionCodec<T>` for typed payloads. Use `handle(action) { ... }` when a handler needs to return `CommandResult.Rejected` or `Unsupported` explicitly.

Platform extensions provide scoped native access: `withAndroidMediaControls`, `withAppleMediaControls`, `withNativeMediaControls` on Linux/Windows Native, and `withDesktopMediaControls` on JVM. Android also exposes `customizeSession` and `customizeMetadata`. For a different integration, implement `MediaBackend` and create it with `MediaPlatform.custom(...)`.

## Lifecycle and errors

- `activate()` attaches system integration. Call it from a platform-permitted playback-start path; calling it again retries an unavailable backend.
- `deactivate()` releases system integration while preserving the latest state for reuse.
- `close()` releases the session permanently. Use `closeAndJoin()` when native cleanup must finish before destroying a window or event loop.
- Cancelling the owning scope also releases the session. The library never closes your player or cancels the supplied scope.

Observe `session.status` for availability and `session.events` for errors and command outcomes. Playback can continue when system integration is unavailable.

## License

[Apache License 2.0](LICENSE).
