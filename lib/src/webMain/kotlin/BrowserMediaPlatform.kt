package dev.brahmkshatriya.kmedianotification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration.Companion.seconds
import kotlin.io.encoding.Base64

public class BrowserMediaConfig {
    /** Platform hook for fields/actions beyond the common API. */
    public var afterPublish: (() -> Unit)? = null
}
public fun MediaPlatform.Companion.browser(configure: BrowserMediaConfig.() -> Unit = {}): MediaPlatform {
    val config = BrowserMediaConfig().apply(configure)
    return MediaPlatform.custom("Browser Media Session", MediaCapabilities(setOf(MediaAction.Play, MediaAction.Pause,
        MediaAction.TogglePlayPause, MediaAction.Next, MediaAction.Previous, MediaAction.Stop, MediaAction.SeekTo, MediaAction.SeekBy),
        setOf(MediaFeature.Artwork, MediaFeature.Timeline))) { context -> BrowserMediaBackend(context, config) }
}
private val browserOwner = MutableStateFlow<MediaBackendContext?>(null)
private class BrowserMediaBackend(private val context: MediaBackendContext, private val config: BrowserMediaConfig) : MediaBackend {
    private var artwork: MediaArtwork? = null
    private var artworkUrl: String = ""
    private val setAction = installActions { action, seconds ->
        if (!seconds.isFinite()) return@installActions
        val target = context.state.value.item?.entryId
        val request = when (action) {
            "play" -> MediaCommand.Play; "pause" -> MediaCommand.Pause; "stop" -> MediaCommand.Stop
            "nexttrack" -> MediaCommand.Next; "previoustrack" -> MediaCommand.Previous
            "seekto" -> MediaCommand.SeekTo(seconds.seconds, target)
            "seekforward" -> MediaCommand.SeekBy(seconds.seconds, target)
            "seekbackward" -> MediaCommand.SeekBy((-seconds).seconds, target)
            else -> null
        }
        if (request != null) context.dispatch(request)
    }
    init {
        check(browserOwner.compareAndSet(null, context)) { "A browser Media Session is already active" }
        val supported = mutableSetOf<MediaAction>()
        listOf("play" to MediaAction.Play, "pause" to MediaAction.Pause, "stop" to MediaAction.Stop,
            "nexttrack" to MediaAction.Next, "previoustrack" to MediaAction.Previous,
            "seekto" to MediaAction.SeekTo, "seekforward" to MediaAction.SeekBy, "seekbackward" to MediaAction.SeekBy).forEach { (name, action) ->
            if (setAction(name, false)) supported += action
        }
        val features = mutableSetOf(MediaFeature.Artwork)
        if (MediaAction.Play in supported && MediaAction.Pause in supported) supported += MediaAction.TogglePlayPause
        if (hasPositionApi()) features += MediaFeature.Timeline
        context.setCapabilities(MediaCapabilities(supported, features))
    }
    override fun publish(state: MediaSessionState) {
        val actions = state.availableActions.orEmpty()
        listOf("play" to MediaAction.Play, "pause" to MediaAction.Pause, "stop" to MediaAction.Stop,
            "nexttrack" to MediaAction.Next, "previoustrack" to MediaAction.Previous,
            "seekto" to MediaAction.SeekTo, "seekforward" to MediaAction.SeekBy, "seekbackward" to MediaAction.SeekBy).forEach { (name, action) ->
            setAction(name, state.item != null && action in actions &&
                (action != MediaAction.SeekTo || state.playback.seekableRange != null))
        }
        val item = state.item
        if (artwork != item?.artwork) {
            artwork = item?.artwork
            artworkUrl = artwork?.let { "data:${it.mimeType};base64,${Base64.encode(it.encodedData)}" }.orEmpty()
        }
        publishWebMedia(item?.title.orEmpty(), item?.artists?.joinToString().orEmpty(), item?.album.orEmpty(), if (item?.artwork != null) artworkUrl else item?.artworkUri.orEmpty(),
            item != null, state.playback.isAdvancing, state.playback.duration?.inWholeMilliseconds?.div(1000.0) ?: -1.0,
            state.playback.positionNow().inWholeMilliseconds / 1000.0, state.playback.speed)
        config.afterPublish?.invoke()
    }
    override fun close() {
        listOf("play", "pause", "stop", "nexttrack", "previoustrack", "seekto", "seekforward", "seekbackward").forEach { setAction(it, false) }
        publishWebMedia("", "", "", "", false, false, -1.0, 0.0, 1.0)
        browserOwner.compareAndSet(context, null)
    }
}
internal expect fun installActions(callback: (String, Double) -> Unit): (String, Boolean) -> Boolean
internal expect fun publishWebMedia(title: String, artist: String, album: String, artwork: String,
    present: Boolean, playing: Boolean, duration: Double, position: Double, speed: Double)
internal expect fun hasPositionApi(): Boolean
