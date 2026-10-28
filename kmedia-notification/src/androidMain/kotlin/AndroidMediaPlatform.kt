@file:Suppress("UnstableApiUsage", "OVERRIDE_DEPRECATION")
package dev.brahmkshatriya.kmedianotification

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.MediaItem as AndroidMediaItem
import androidx.media3.common.MediaMetadata as AndroidMetadata
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession as AndroidSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlin.time.Duration.Companion.milliseconds

public class AndroidNotificationConfig {
    public var channelId: String = "media_playback"
    public var channelName: String = "Playback"
    public var notificationId: Int = 1001
    public var smallIcon: Int = android.R.drawable.ic_media_play
}
public class AndroidMediaConfig {
    public val notification: AndroidNotificationConfig = AndroidNotificationConfig()
    public fun notification(configure: AndroidNotificationConfig.() -> Unit) { notification.configure() }
    public var sessionActivity: PendingIntent? = null
    /** The host is consumer-owned and can recreate the binding without an Activity. */
    public var serviceClass: Class<out MediaNotificationService>? = null
    public var allowController: (AndroidSession.ControllerInfo) -> Boolean = { it.isTrusted }
    /** This hook may customize builder fields; callback ownership belongs to the bridge. */
    public var buttonIcon: (AndroidMediaButtonIcon) -> Int? = { null }
    public var customizeMetadata: ((NowPlayingItem, AndroidMetadata.Builder) -> Unit)? = null
    public var customizeSession: (AndroidSession.Builder.() -> Unit)? = null
}
public class AndroidMediaControls internal constructor(
    public val session: AndroidSession,
    internal val config: AndroidMediaConfig,
) {
    public val notificationConfig: AndroidNotificationConfig get() = config.notification
}
public suspend fun MediaSession.withAndroidMediaControls(block: (AndroidMediaControls) -> Unit): Boolean =
    withNativeAccess(AndroidMediaControls::class, block)
public fun MediaPlatform.Companion.android(context: Context, configure: AndroidMediaConfig.() -> Unit = {}): MediaPlatform {
    val application = context.applicationContext
    val config = AndroidMediaConfig().apply(configure)
    return MediaPlatform.custom("Android Media3", MediaCapabilities(setOf(MediaAction.Play, MediaAction.Pause,
        MediaAction.TogglePlayPause, MediaAction.Stop, MediaAction.Next, MediaAction.Previous,
        MediaAction.SeekTo, MediaAction.SeekBy, MediaAction.SetVolume, MediaAction.SetRate,
        MediaAction.SetShuffle, MediaAction.SetRepeatMode, MediaAction.SkipToEntry, MediaAction.Custom, MediaAction.SetLiked),
        setOf(MediaFeature.Artwork, MediaFeature.Timeline, MediaFeature.Queue, MediaFeature.CustomButtons)),
        Dispatchers.Main.immediate) { AndroidMediaBackend(it, application, config) }
}
private class AndroidMediaBackend(private val context: MediaBackendContext, private val application: Context,
    private val config: AndroidMediaConfig) : MediaBackend {
    private val player = SnapshotPlayer(context, config)
    private val native: AndroidSession
    private var serviceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }
    init {
        val callback = object : AndroidSession.Callback {
            override fun onConnectAsync(session: AndroidSession, controller: AndroidSession.ControllerInfo): ListenableFuture<AndroidSession.ConnectionResult> {
                if (!config.allowController(controller) && !session.isMediaNotificationController(controller)) {
                    return Futures.immediateFuture(AndroidSession.ConnectionResult.reject())
                }
                val available = AndroidSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                context.customActionIds.forEach { available.add(SessionCommand(it, Bundle.EMPTY)) }
                available.add(SessionCommand(SetLikedCommand, Bundle.EMPTY))
                available.add(SessionCommand(UnavailableNextCommand, Bundle.EMPTY))
                available.add(SessionCommand(UnavailablePreviousCommand, Bundle.EMPTY))
                return Futures.immediateFuture(AndroidSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                    .setAvailableSessionCommands(available.build()).build())
            }
            override fun onCustomCommand(session: AndroidSession, controller: AndroidSession.ControllerInfo,
                customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (customCommand.customAction == UnavailableNextCommand ||
                    customCommand.customAction == UnavailablePreviousCommand) {
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                val command = if (customCommand.customAction == SetLikedCommand) {
                    MediaCommand.SetLiked(customCommand.customExtras.getBoolean("liked"), customCommand.customExtras.getString("entryId"))
                } else MediaCommand.Custom(customCommand.customAction, args.getByteArray("payload") ?: byteArrayOf())
                val accepted = context.dispatch(command)
                return Futures.immediateFuture(SessionResult(if (accepted) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
        }
        val builder = AndroidSession.Builder(application, player).setId(context.id)
        config.sessionActivity?.let(builder::setSessionActivity)
        config.customizeSession?.invoke(builder)
        native = builder.setCallback(callback).build()
        context.setNativeAccess(AndroidMediaControls(native, config))
        try {
            config.serviceClass?.let { service ->
                // Activation may be paused or preparing. Keep the host bound while active;
                // Media3 promotes it only once it can post the playback notification.
                val intent = Intent(application, service).setAction(MediaSessionService.SERVICE_INTERFACE)
                check(application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                    "Could not bind media notification service ${service.name}"
                }
                serviceBound = true
            }
        } catch (error: Throwable) { native.release(); player.release(); context.setNativeAccess(null); throw error }
    }
    override fun publish(state: MediaSessionState) {
        player.publish(state)
        native.setMediaButtonPreferences(state.buttons.mapNotNull { androidMediaButton(application, config, it, state) })
    }

    override fun close() {
        if (serviceBound) {
            serviceBound = false
            application.unbindService(serviceConnection)
        }
        native.release(); player.release(); context.setNativeAccess(null)
    }
}

private class SnapshotPlayer(private val context: MediaBackendContext, private val config: AndroidMediaConfig) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var snapshot = context.state.value
    private var cachedItems: List<NowPlayingItem>? = null
    private var cachedItem: NowPlayingItem? = null
    private var cachedDuration: kotlin.time.Duration? = null
    private var cachedSeekable = false
    private var cachedLive = false
    private var cachedPlaylist: List<MediaItemData> = emptyList()
    private var cachedIndex = 0
    fun publish(state: MediaSessionState) { snapshot = state; invalidateState() }
    override fun getState(): State {
        val state = snapshot
        val actions = state.availableActions ?: context.availableActions()
        val available = Player.Commands.Builder().addAll(Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_VOLUME,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES, Player.COMMAND_RELEASE)
        if (MediaAction.Play in actions || MediaAction.Pause in actions) available.add(Player.COMMAND_PLAY_PAUSE)
        if (MediaAction.Play in actions) available.add(Player.COMMAND_PREPARE)
        if (MediaAction.Stop in actions) available.add(Player.COMMAND_STOP)
        if (MediaAction.Next in actions) available.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        if (MediaAction.Previous in actions) available.addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        if (MediaAction.SeekTo in actions && state.playback.seekableRange != null) available.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        if (MediaAction.SeekBy in actions) available.addAll(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD)
        if (MediaAction.SkipToEntry in actions) available.add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
        if (MediaAction.SetVolume in actions) available.add(Player.COMMAND_SET_VOLUME)
        if (MediaAction.SetRate in actions) available.add(Player.COMMAND_SET_SPEED_AND_PITCH)
        if (MediaAction.SetShuffle in actions) available.add(Player.COMMAND_SET_SHUFFLE_MODE)
        if (MediaAction.SetRepeatMode in actions) available.add(Player.COMMAND_SET_REPEAT_MODE)
        val items = if (state.item == null) emptyList() else state.queue.ifEmpty { listOf(state.item) }
        if (cachedItems != items || cachedItem != state.item || cachedDuration != state.playback.duration ||
            cachedSeekable != (state.playback.seekableRange != null) || cachedLive != state.playback.isLive || config.customizeMetadata != null) {
            cachedPlaylist = items.map { queuedItem ->
                val item = state.item?.takeIf { it.entryId == queuedItem.entryId } ?: queuedItem
                val metadata = AndroidMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.artists.joinToString())
                    .setAlbumTitle(item.album)
                    .setMediaType(if (item.kind == MediaKind.Video) AndroidMetadata.MEDIA_TYPE_VIDEO else AndroidMetadata.MEDIA_TYPE_MUSIC)
                if (item.artwork != null) {
                    metadata.setArtworkData(item.artwork.encodedData, AndroidMetadata.PICTURE_TYPE_FRONT_COVER)
                } else item.artworkUri?.let { metadata.setArtworkUri(Uri.parse(it)) }
                config.customizeMetadata?.invoke(item, metadata)
                val media = AndroidMediaItem.Builder().setMediaId(item.mediaId).setMediaMetadata(metadata.build()).build()
                MediaItemData.Builder(item.entryId).setMediaItem(media)
                    .setIsSeekable(state.playback.seekableRange != null)
                    .setIsDynamic(state.playback.isLive)
                    .setDurationUs(if (item.entryId == state.item?.entryId) state.playback.duration?.inWholeMicroseconds ?: C.TIME_UNSET else C.TIME_UNSET)
                    .build()
            }
            cachedItems = items; cachedItem = state.item; cachedDuration = state.playback.duration
            cachedSeekable = state.playback.seekableRange != null; cachedLive = state.playback.isLive
            cachedIndex = items.indexOfFirst { it.entryId == state.item?.entryId }.coerceAtLeast(0)
        }
        return State.Builder().setAvailableCommands(available.build())
            .setPlayWhenReady(state.playback.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (state.item == null) Player.STATE_IDLE else when(state.playback.phase) {
                PlaybackPhase.Idle -> Player.STATE_IDLE; PlaybackPhase.Preparing, PlaybackPhase.Buffering -> Player.STATE_BUFFERING
                PlaybackPhase.Ended -> Player.STATE_ENDED; PlaybackPhase.Failed -> Player.STATE_IDLE; PlaybackPhase.Ready -> Player.STATE_READY
            }).setIsLoading(state.playback.phase in setOf(PlaybackPhase.Preparing, PlaybackPhase.Buffering))
            .setPlaybackSuppressionReason(if (state.playback.playWhenReady && state.playback.phase == PlaybackPhase.Ready && !state.playback.isAdvancing)
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS else Player.PLAYBACK_SUPPRESSION_REASON_NONE)
            .setPlaybackParameters(PlaybackParameters(state.playback.speed.toFloat()))
            .setVolume(state.volume.toFloat()).setShuffleModeEnabled(state.shuffle)
            .setRepeatMode(when(state.repeatMode) { RepeatMode.Off -> Player.REPEAT_MODE_OFF; RepeatMode.All -> Player.REPEAT_MODE_ALL; RepeatMode.One -> Player.REPEAT_MODE_ONE })
            .setPlaylist(cachedPlaylist).setCurrentMediaItemIndex(cachedIndex)
            .setContentPositionMs { state.playback.positionNow().inWholeMilliseconds }.build()
    }
    private fun send(command: MediaCommand): ListenableFuture<*> {
        return if (context.dispatch(command)) Futures.immediateVoidFuture()
        else Futures.immediateFailedFuture<Void>(IllegalStateException("Command unavailable: ${command.action}"))
    }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = send(if (playWhenReady) MediaCommand.Play else MediaCommand.Pause)
    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handleStop(): ListenableFuture<*> = send(MediaCommand.Stop)
    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> = send(MediaCommand.SetRepeatMode(when(repeatMode) { Player.REPEAT_MODE_ONE -> RepeatMode.One; Player.REPEAT_MODE_ALL -> RepeatMode.All; else -> RepeatMode.Off }))
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> = send(MediaCommand.SetShuffle(shuffleModeEnabled))
    override fun handleSetVolume(volume: Float): ListenableFuture<*> = send(MediaCommand.SetVolume(volume.toDouble()))
    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> = send(MediaCommand.SetRate(playbackParameters.speed.toDouble()))
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = when(seekCommand) {
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> send(MediaCommand.Next)
        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> send(MediaCommand.Previous)
        Player.COMMAND_SEEK_BACK -> send(MediaCommand.SeekBy((-10_000).milliseconds, snapshot.item?.entryId))
        Player.COMMAND_SEEK_FORWARD -> send(MediaCommand.SeekBy(10_000.milliseconds, snapshot.item?.entryId))
        Player.COMMAND_SEEK_TO_MEDIA_ITEM -> snapshot.queue.getOrNull(mediaItemIndex)?.let { send(MediaCommand.SkipToEntry(it.entryId)) }
            ?: Futures.immediateFailedFuture<Void>(IllegalArgumentException("Queue entry no longer exists"))
        else -> send(MediaCommand.SeekTo(positionMs.coerceAtLeast(0).milliseconds, snapshot.item?.entryId))
    }
}
