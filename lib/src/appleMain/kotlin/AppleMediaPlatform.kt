@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.brahmkshatriya.kmedianotification

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.*
import platform.MediaPlayer.*
import kotlin.time.Duration.Companion.seconds

public class AppleMediaConfig {
    /** Called after common fields are mapped, for additional native metadata. */
    public var customizeInfo: (MutableMap<Any?, Any>.() -> Unit)? = null
    public var customizeCommands: (MPRemoteCommandCenter.() -> Unit)? = null
}
public class AppleMediaControls internal constructor(
    public val infoCenter: MPNowPlayingInfoCenter,
    public val commandCenter: MPRemoteCommandCenter,
)
public suspend fun MediaSession.withAppleMediaControls(block: (AppleMediaControls) -> Unit): Boolean =
    withNativeAccess(AppleMediaControls::class, block)
public fun MediaPlatform.Companion.apple(configure: AppleMediaConfig.() -> Unit = {}): MediaPlatform {
    val config = AppleMediaConfig().apply(configure)
    return MediaPlatform.custom("Apple Now Playing", MediaCapabilities(appleActions,
        setOf(MediaFeature.Artwork, MediaFeature.Timeline)), Dispatchers.Main.immediate) {
        AppleMediaBackend(it, config)
    }
}
private val appleOwner = MutableStateFlow<MediaBackendContext?>(null)
private class AppleMediaBackend(private val context: MediaBackendContext, private val config: AppleMediaConfig) : MediaBackend {
    private val center = MPNowPlayingInfoCenter.defaultCenter()
    private val commands = MPRemoteCommandCenter.sharedCommandCenter()
    private val targets = mutableListOf<Pair<MPRemoteCommand, Any>>()
    private var artworkTask: NSURLSessionDataTask? = null
    private var artworkUri: String? = null
    private var artworkSource: Any? = null
    private var artwork: MPMediaItemArtwork? = null
    private var latest: MediaSessionState? = null
    private var closed = false
    init {
        check(appleOwner.compareAndSet(null, context)) { "An Apple Now Playing session is already active" }
        try {
        bind(commands.playCommand, MediaAction.Play) { MediaCommand.Play }
        bind(commands.pauseCommand, MediaAction.Pause) { MediaCommand.Pause }
        bind(commands.togglePlayPauseCommand, MediaAction.TogglePlayPause) { MediaCommand.TogglePlayPause }
        bind(commands.stopCommand, MediaAction.Stop) { MediaCommand.Stop }
        bind(commands.nextTrackCommand, MediaAction.Next) { MediaCommand.Next }
        bind(commands.previousTrackCommand, MediaAction.Previous) { MediaCommand.Previous }
        bind(commands.changePlaybackPositionCommand, MediaAction.SeekTo) { event ->
            (event as? MPChangePlaybackPositionCommandEvent)?.takeIf { it.positionTime.isFinite() }?.let { MediaCommand.SeekTo(it.positionTime.seconds, context.state.value.item?.entryId) }
        }
        bind(commands.skipForwardCommand, MediaAction.SeekBy) { event ->
            (event as? MPSkipIntervalCommandEvent)?.takeIf { it.interval.isFinite() }?.let { MediaCommand.SeekBy(it.interval.seconds, context.state.value.item?.entryId) }
        }
        bind(commands.skipBackwardCommand, MediaAction.SeekBy) { event ->
            (event as? MPSkipIntervalCommandEvent)?.takeIf { it.interval.isFinite() }?.let { MediaCommand.SeekBy((-it.interval).seconds, context.state.value.item?.entryId) }
        }
        commands.skipForwardCommand.preferredIntervals = listOf(10)
        commands.skipBackwardCommand.preferredIntervals = listOf(10)
        bind(commands.changePlaybackRateCommand, MediaAction.SetRate) { event ->
            (event as? MPChangePlaybackRateCommandEvent)?.let { MediaCommand.SetRate(it.playbackRate.toDouble()) }
        }
        bind(commands.changeRepeatModeCommand, MediaAction.SetRepeatMode) { event ->
            (event as? MPChangeRepeatModeCommandEvent)?.let { MediaCommand.SetRepeatMode(when(it.repeatType) {
                MPRepeatType.MPRepeatTypeAll -> RepeatMode.All; MPRepeatType.MPRepeatTypeOne -> RepeatMode.One; else -> RepeatMode.Off
            }) }
        }
        bind(commands.changeShuffleModeCommand, MediaAction.SetShuffle) { event ->
            (event as? MPChangeShuffleModeCommandEvent)?.let { MediaCommand.SetShuffle(it.shuffleType != MPShuffleType.MPShuffleTypeOff) }
        }
        bind(commands.likeCommand, MediaAction.SetLiked) {
            MediaCommand.SetLiked(context.state.value.liked != true, context.state.value.item?.entryId)
        }
        context.setNativeAccess(AppleMediaControls(center, commands))
        } catch (error: Throwable) { close(); throw error }
    }
    private fun bind(native: MPRemoteCommand, action: MediaAction, convert: (MPRemoteCommandEvent?) -> MediaCommand?) {
        native.enabled = action in context.availableActions()
        val target = native.addTargetWithHandler { event ->
            val request = convert(event)
            if (request != null && context.dispatch(request)) MPRemoteCommandHandlerStatusSuccess
            else MPRemoteCommandHandlerStatusCommandFailed
        }
        targets += native to target
    }
    override fun publish(state: MediaSessionState) {
        latest = state
        val enabled = state.availableActions.orEmpty()
        commands.playCommand.enabled = MediaAction.Play in enabled
        commands.pauseCommand.enabled = MediaAction.Pause in enabled
        commands.togglePlayPauseCommand.enabled = MediaAction.TogglePlayPause in enabled
        commands.stopCommand.enabled = MediaAction.Stop in enabled
        commands.nextTrackCommand.enabled = MediaAction.Next in enabled
        commands.previousTrackCommand.enabled = MediaAction.Previous in enabled
        commands.changePlaybackPositionCommand.enabled = MediaAction.SeekTo in enabled && state.playback.seekableRange != null
        commands.skipForwardCommand.enabled = MediaAction.SeekBy in enabled
        commands.skipBackwardCommand.enabled = MediaAction.SeekBy in enabled
        commands.changePlaybackRateCommand.enabled = MediaAction.SetRate in enabled
        commands.changeRepeatModeCommand.enabled = MediaAction.SetRepeatMode in enabled
        commands.changeShuffleModeCommand.enabled = MediaAction.SetShuffle in enabled
        commands.changeRepeatModeCommand.currentRepeatType = when (state.repeatMode) {
            RepeatMode.Off -> MPRepeatType.MPRepeatTypeOff
            RepeatMode.All -> MPRepeatType.MPRepeatTypeAll
            RepeatMode.One -> MPRepeatType.MPRepeatTypeOne
        }
        commands.changeShuffleModeCommand.currentShuffleType = if (state.shuffle)
            MPShuffleType.MPShuffleTypeItems else MPShuffleType.MPShuffleTypeOff
        commands.likeCommand.enabled = state.item != null && state.liked != null && MediaAction.SetLiked in enabled
        commands.likeCommand.active = state.liked == true
        commands.likeCommand.localizedTitle = if (state.liked == true) "Unlike" else "Like"
        config.customizeCommands?.invoke(commands)
        val source = state.item?.artwork ?: state.item?.artworkUri
        if (artworkSource != source) {
            artworkTask?.cancel(); artworkTask = null
            artworkSource = source; artworkUri = state.item?.artworkUri; artwork = null
            if (source is MediaArtwork) {
                artwork = source.encodedData.usePinned { pinned ->
                    createAppleArtwork(NSData.create(bytes = pinned.addressOf(0), length = source.encodedData.size.toULong()))
                }
            } else artworkUri?.let { uri -> NSURL.URLWithString(uri)?.let { url ->
                artworkTask = NSURLSession.sharedSession.dataTaskWithURL(url) { data, _, error ->
                    context.scope.launch {
                        if (!closed && artworkSource == uri) {
                            if (error != null) context.reportError("Load artwork", IllegalStateException(error.localizedDescription))
                            try {
                                if (data != null) artwork = createAppleArtwork(data)
                                latest?.let(::publishInfo)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Throwable) { context.reportError("Publish artwork", failure) }
                        }
                    }
                }.also { it.resume() }
            } }
        }
        publishInfo(state)
    }
    private fun publishInfo(state: MediaSessionState) {
        center.nowPlayingInfo = state.item?.let { item ->
            mutableMapOf<Any?, Any>(
                MPMediaItemPropertyTitle to item.title,
                MPMediaItemPropertyArtist to item.artists.joinToString(),
                MPMediaItemPropertyAlbumTitle to item.album.orEmpty(),
                MPNowPlayingInfoPropertyElapsedPlaybackTime to state.playback.positionNow().inWholeMilliseconds / 1000.0,
                MPNowPlayingInfoPropertyPlaybackRate to if (state.playback.isAdvancing) state.playback.speed else 0.0,
                MPNowPlayingInfoPropertyDefaultPlaybackRate to state.playback.speed,
                MPNowPlayingInfoPropertyExternalContentIdentifier to item.mediaId,
                MPNowPlayingInfoPropertyIsLiveStream to state.playback.isLive,
                MPNowPlayingInfoPropertyMediaType to (if (item.kind == MediaKind.Video) MPNowPlayingInfoMediaTypeVideo else MPNowPlayingInfoMediaTypeAudio).toLong(),
            ).apply {
                state.playback.duration?.let { put(MPMediaItemPropertyPlaybackDuration, it.inWholeMilliseconds / 1000.0) }
                artwork?.let { put(MPMediaItemPropertyArtwork, it) }
                if (state.queue.isNotEmpty()) {
                    put(MPNowPlayingInfoPropertyPlaybackQueueIndex, state.queue.indexOfFirst { it.entryId == item.entryId }.coerceAtLeast(0))
                    put(MPNowPlayingInfoPropertyPlaybackQueueCount, state.queue.size)
                }
                config.customizeInfo?.invoke(this)
            }
        }
        center.playbackState = when {
            state.item == null -> MPNowPlayingPlaybackStateStopped
            state.playback.playWhenReady -> MPNowPlayingPlaybackStatePlaying
            else -> MPNowPlayingPlaybackStatePaused
        }
    }
    override fun close() {
        closed = true; artworkTask?.cancel()
        targets.forEach { (command, target) -> command.removeTarget(target); command.enabled = false }
        targets.clear(); center.nowPlayingInfo = null; center.playbackState = MPNowPlayingPlaybackStateStopped
        context.setNativeAccess(null)
        appleOwner.compareAndSet(context, null)
    }
}
