package dev.brahmkshatriya.kmedianotification

import kotlin.time.Duration.Companion.milliseconds

internal fun nativeCommand(code: Int, value: Double, entryId: String?): MediaCommand? = when (code) {
    1 -> MediaCommand.Play; 2 -> MediaCommand.Pause; 3 -> MediaCommand.TogglePlayPause
    4 -> MediaCommand.Next; 5 -> MediaCommand.Previous; 6 -> MediaCommand.Stop
    7 -> if (value.isFinite()) MediaCommand.SeekTo(value.milliseconds, entryId) else null
    8 -> if (value.isFinite()) MediaCommand.SeekBy(value.milliseconds, entryId) else null
    9 -> MediaCommand.SetVolume(value); 10 -> MediaCommand.SetShuffle(value != 0.0)
    11 -> RepeatMode.entries.getOrNull(value.toInt())?.let(MediaCommand::SetRepeatMode)
    12 -> MediaCommand.SetRate(value); 13 -> MediaCommand.Raise; 14 -> MediaCommand.Quit
    15 -> MediaCommand.SetLiked(value != 0.0, entryId)
    else -> null
}
internal fun nativeActionMask(actions: Set<MediaAction>): Int = actions.fold(0) { mask, action ->
    val code = when (action) {
        MediaAction.Play -> 1; MediaAction.Pause -> 2; MediaAction.TogglePlayPause -> 3
        MediaAction.Next -> 4; MediaAction.Previous -> 5; MediaAction.Stop -> 6
        MediaAction.SeekTo -> 7; MediaAction.SeekBy -> 8; MediaAction.SetVolume -> 9
        MediaAction.SetShuffle -> 10; MediaAction.SetRepeatMode -> 11; MediaAction.SetRate -> 12
        MediaAction.Raise -> 13; MediaAction.Quit -> 14; MediaAction.SetLiked -> 15; else -> 0
    }
    if (code == 0) mask else mask or (1 shl code)
}
internal val desktopActions: Set<MediaAction> = setOf(MediaAction.Play, MediaAction.Pause,
    MediaAction.TogglePlayPause, MediaAction.Next, MediaAction.Previous, MediaAction.Stop,
    MediaAction.SeekTo, MediaAction.SeekBy, MediaAction.SetVolume, MediaAction.SetShuffle,
    MediaAction.SetRepeatMode, MediaAction.SetRate, MediaAction.Raise, MediaAction.Quit)
internal val windowsActions: Set<MediaAction> = setOf(MediaAction.Play, MediaAction.Pause,
    MediaAction.TogglePlayPause, MediaAction.Next, MediaAction.Previous, MediaAction.Stop, MediaAction.SeekTo)
internal val appleActions: Set<MediaAction> = setOf(MediaAction.Play, MediaAction.Pause,
    MediaAction.TogglePlayPause, MediaAction.Next, MediaAction.Previous, MediaAction.Stop,
    MediaAction.SeekTo, MediaAction.SeekBy, MediaAction.SetLiked, MediaAction.SetRepeatMode,
    MediaAction.SetShuffle, MediaAction.SetRate)
