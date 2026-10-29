package dev.brahmkshatriya.kmedianotification

import kotlin.time.Duration

public sealed interface MediaCommand {
    public val action: MediaAction
    public data object Play : MediaCommand { override val action: MediaAction = MediaAction.Play }
    public data object Pause : MediaCommand { override val action: MediaAction = MediaAction.Pause }
    public data object TogglePlayPause : MediaCommand { override val action: MediaAction = MediaAction.TogglePlayPause }
    public data object Stop : MediaCommand { override val action: MediaAction = MediaAction.Stop }
    public data object Next : MediaCommand { override val action: MediaAction = MediaAction.Next }
    public data object Previous : MediaCommand { override val action: MediaAction = MediaAction.Previous }
    public data class SeekTo(val position: Duration, val entryId: String? = null) : MediaCommand { override val action: MediaAction = MediaAction.SeekTo }
    public data class SeekBy(val offset: Duration, val entryId: String? = null) : MediaCommand { override val action: MediaAction = MediaAction.SeekBy }
    public data class SetVolume(val volume: Double) : MediaCommand { override val action: MediaAction = MediaAction.SetVolume }
    public data class SetRate(val speed: Double) : MediaCommand { override val action: MediaAction = MediaAction.SetRate }
    public data class SetShuffle(val enabled: Boolean) : MediaCommand { override val action: MediaAction = MediaAction.SetShuffle }
    public data class SetRepeatMode(val mode: RepeatMode) : MediaCommand { override val action: MediaAction = MediaAction.SetRepeatMode }
    public data class SkipToEntry(val entryId: String) : MediaCommand { override val action: MediaAction = MediaAction.SkipToEntry }
    public data object Raise : MediaCommand { override val action: MediaAction = MediaAction.Raise }
    public data object Quit : MediaCommand { override val action: MediaAction = MediaAction.Quit }
    public data class Custom(val id: String, val payload: ByteArray = byteArrayOf()) : MediaCommand { override val action: MediaAction = MediaAction.Custom }
    public data class SetLiked(val liked: Boolean, val entryId: String? = null) : MediaCommand { override val action: MediaAction = MediaAction.SetLiked }
}

public sealed interface CommandResult {
    public data object Completed : CommandResult
    public data object Unsupported : CommandResult
    public data class Rejected(val reason: String) : CommandResult
}
public interface ActionCodec<T> {
    public fun encode(value: T): ByteArray
    public fun decode(bytes: ByteArray): T
}
public class ActionKey<T>(public val id: String, public val codec: ActionCodec<T>) {
    init { require(id.isNotBlank()) }
    public companion object {
        public fun unit(id: String): ActionKey<Unit> = ActionKey(id, object : ActionCodec<Unit> {
            override fun encode(value: Unit): ByteArray = byteArrayOf()
            override fun decode(bytes: ByteArray) { require(bytes.isEmpty()) }
        })
    }
}

public class MediaCommandsBuilder internal constructor() {
    internal val handlers = mutableMapOf<MediaAction, suspend (MediaCommand) -> CommandResult>()
    internal val custom = mutableMapOf<String, suspend (ByteArray) -> CommandResult>()
    /** Advanced form with explicit outcomes. A command has exactly one owner. */
    public fun handle(action: MediaAction, handler: suspend (MediaCommand) -> CommandResult) {
        require(action !in handlers) { "Handler already registered for $action" }
        handlers[action] = handler
    }
    public fun onPlay(block: suspend () -> Unit): Unit = handle(MediaAction.Play) { block(); CommandResult.Completed }
    public fun onPause(block: suspend () -> Unit): Unit = handle(MediaAction.Pause) { block(); CommandResult.Completed }
    public fun onStop(block: suspend () -> Unit): Unit = handle(MediaAction.Stop) { block(); CommandResult.Completed }
    public fun onNext(block: suspend () -> Unit): Unit = handle(MediaAction.Next) { block(); CommandResult.Completed }
    public fun onPrevious(block: suspend () -> Unit): Unit = handle(MediaAction.Previous) { block(); CommandResult.Completed }
    public fun onTogglePlayPause(block: suspend () -> Unit): Unit = handle(MediaAction.TogglePlayPause) { block(); CommandResult.Completed }
    public fun onSeekTo(block: suspend (MediaCommand.SeekTo) -> Unit): Unit = handle(MediaAction.SeekTo) { block(it as MediaCommand.SeekTo); CommandResult.Completed }
    public fun onSeekBy(block: suspend (MediaCommand.SeekBy) -> Unit): Unit = handle(MediaAction.SeekBy) { block(it as MediaCommand.SeekBy); CommandResult.Completed }
    public fun onSetVolume(block: suspend (Double) -> Unit): Unit = handle(MediaAction.SetVolume) { block((it as MediaCommand.SetVolume).volume); CommandResult.Completed }
    public fun onSetRate(block: suspend (Double) -> Unit): Unit = handle(MediaAction.SetRate) { block((it as MediaCommand.SetRate).speed); CommandResult.Completed }
    public fun onSetShuffle(block: suspend (Boolean) -> Unit): Unit = handle(MediaAction.SetShuffle) { block((it as MediaCommand.SetShuffle).enabled); CommandResult.Completed }
    public fun onSetLiked(block: suspend (MediaCommand.SetLiked) -> Unit): Unit = handle(MediaAction.SetLiked) { block(it as MediaCommand.SetLiked); CommandResult.Completed }
    public fun onSetRepeatMode(block: suspend (RepeatMode) -> Unit): Unit = handle(MediaAction.SetRepeatMode) { block((it as MediaCommand.SetRepeatMode).mode); CommandResult.Completed }
    public fun onSkipToEntry(block: suspend (String) -> Unit): Unit = handle(MediaAction.SkipToEntry) { block((it as MediaCommand.SkipToEntry).entryId); CommandResult.Completed }
    public fun onRaise(block: suspend () -> Unit): Unit = handle(MediaAction.Raise) { block(); CommandResult.Completed }
    public fun onQuit(block: suspend () -> Unit): Unit = handle(MediaAction.Quit) { block(); CommandResult.Completed }
    public fun <T> onCustom(key: ActionKey<T>, block: suspend (T) -> Unit) {
        require(key.id !in custom) { "Handler already registered for ${key.id}" }
        custom[key.id] = { bytes -> block(key.codec.decode(bytes)); CommandResult.Completed }
    }
}
