@file:Suppress("UnstableApiUsage")
package dev.brahmkshatriya.kmedianotification

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand

/** The same resolver styles transport, toggle, and arbitrary custom buttons. */
public data class AndroidMediaButtonIcon(
    val button: MediaButton,
    val icon: MediaIcon,
    /** False for a retained unavailable control. Use drawable path alpha for its disabled appearance. */
    val enabled: Boolean = true,
)

internal const val SetLikedCommand: String = "kmedianotification.setLiked"
internal const val UnavailableNextCommand: String = "kmedianotification.unavailableNext"
internal const val UnavailablePreviousCommand: String = "kmedianotification.unavailablePrevious"

internal fun AndroidMediaConfig.iconBuilder(
    button: MediaButton, icon: MediaIcon, enabled: Boolean = true,
): CommandButton.Builder = iconBuilder(icon, buttonIcon(AndroidMediaButtonIcon(button, icon, enabled)))

private fun iconBuilder(icon: MediaIcon, resource: Int?): CommandButton.Builder {
    val nativeIcon = when (icon) {
        MediaIcon.Play -> CommandButton.ICON_PLAY
        MediaIcon.Pause -> CommandButton.ICON_PAUSE
        MediaIcon.Previous -> CommandButton.ICON_PREVIOUS
        MediaIcon.Next -> CommandButton.ICON_NEXT
        MediaIcon.Stop -> CommandButton.ICON_STOP
        MediaIcon.SeekForward -> CommandButton.ICON_SKIP_FORWARD
        MediaIcon.SeekBackward -> CommandButton.ICON_SKIP_BACK
        MediaIcon.Favorite -> CommandButton.ICON_HEART_UNFILLED
        MediaIcon.FavoriteFilled -> CommandButton.ICON_HEART_FILLED
        MediaIcon.Bookmark -> CommandButton.ICON_BOOKMARK_UNFILLED
        MediaIcon.BookmarkFilled -> CommandButton.ICON_BOOKMARK_FILLED
        MediaIcon.RepeatOff -> CommandButton.ICON_REPEAT_OFF
        MediaIcon.RepeatAll -> CommandButton.ICON_REPEAT_ALL
        MediaIcon.RepeatOne -> CommandButton.ICON_REPEAT_ONE
        MediaIcon.ShuffleOff -> CommandButton.ICON_SHUFFLE_OFF
        MediaIcon.ShuffleOn -> CommandButton.ICON_SHUFFLE_ON
        MediaIcon.Custom -> CommandButton.ICON_UNDEFINED
    }
    return CommandButton.Builder(if (resource != null) CommandButton.ICON_UNDEFINED else nativeIcon)
        .apply { resource?.let(::setCustomIconResId) }
}

internal fun androidMediaButton(
    application: Context,
    config: AndroidMediaConfig,
    button: MediaButton,
    state: MediaSessionState,
): CommandButton? {
    val action = when (button) {
        is MediaButton.Standard -> button.action
        is MediaButton.Custom -> MediaAction.Custom
    }
    val enabled = state.availableActions?.contains(action) != false
    val icon = button.icon(state)
    val resource = config.buttonIcon(AndroidMediaButtonIcon(button, icon, enabled))
    val builder = iconBuilder(icon, resource).setEnabled(enabled)
    if (button is MediaButton.Custom) return builder.setDisplayName(button.label)
        .setSessionCommand(SessionCommand(button.id, Bundle.EMPTY))
        .setSlots(CommandButton.SLOT_OVERFLOW).build()
    val label = when (action) {
        MediaAction.SetLiked -> if (state.liked == true) R.string.kmedia_unlike else R.string.kmedia_like
        MediaAction.SetShuffle -> if (state.shuffle) R.string.kmedia_shuffle_on else R.string.kmedia_shuffle_off
        MediaAction.SetRepeatMode -> when (state.repeatMode) {
            RepeatMode.Off -> R.string.kmedia_repeat_off
            RepeatMode.All -> R.string.kmedia_repeat_all
            RepeatMode.One -> R.string.kmedia_repeat_one
        }
        MediaAction.Previous -> androidx.media3.session.R.string.media3_controls_seek_to_previous_description
        MediaAction.Next -> androidx.media3.session.R.string.media3_controls_seek_to_next_description
        MediaAction.TogglePlayPause -> if (state.playback.playWhenReady)
            androidx.media3.session.R.string.media3_controls_pause_description
        else androidx.media3.session.R.string.media3_controls_play_description
        MediaAction.Play -> androidx.media3.session.R.string.media3_controls_play_description
        MediaAction.Pause -> androidx.media3.session.R.string.media3_controls_pause_description
        MediaAction.Stop -> R.string.kmedia_stop
        MediaAction.SeekBy -> androidx.media3.session.R.string.media3_controls_seek_forward_description
        else -> return null
    }
    builder.setDisplayName(application.getString(label))
    if (!enabled && resource != null && Build.VERSION.SDK_INT >= 33 &&
        action in setOf(MediaAction.Next, MediaAction.Previous)) {
        // System UI drops disabled standard actions. Keep a dim custom icon in the same slot;
        // its private command always does nothing, including if a stale tap arrives after re-enabling.
        return builder.setEnabled(true)
            .setDisplayName(application.getString(R.string.kmedia_unavailable_button, application.getString(label)))
            .setSessionCommand(SessionCommand(
                if (action == MediaAction.Next) UnavailableNextCommand else UnavailablePreviousCommand,
                Bundle.EMPTY,
            ))
            .setSlots(if (action == MediaAction.Next) CommandButton.SLOT_FORWARD else CommandButton.SLOT_BACK)
            .build()
    }
    when (action) {
        MediaAction.SetLiked -> builder.setSessionCommand(SessionCommand(SetLikedCommand, Bundle().apply {
            putBoolean("liked", state.liked != true)
            putString("entryId", state.item?.entryId)
        })).setSlots(CommandButton.SLOT_OVERFLOW)
        MediaAction.SetShuffle -> builder.setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE)
            .setSlots(CommandButton.SLOT_OVERFLOW)
        MediaAction.SetRepeatMode -> builder.setPlayerCommand(Player.COMMAND_SET_REPEAT_MODE, when (state.repeatMode) {
            RepeatMode.Off -> Player.REPEAT_MODE_ALL
            RepeatMode.All -> Player.REPEAT_MODE_ONE
            RepeatMode.One -> Player.REPEAT_MODE_OFF
        }).setSlots(CommandButton.SLOT_OVERFLOW)
        MediaAction.Previous -> builder.setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).setSlots(CommandButton.SLOT_BACK)
        MediaAction.Next -> builder.setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).setSlots(CommandButton.SLOT_FORWARD)
        MediaAction.Play, MediaAction.Pause, MediaAction.TogglePlayPause -> builder.setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
            .setSlots(CommandButton.SLOT_CENTRAL)
        MediaAction.Stop -> builder.setPlayerCommand(Player.COMMAND_STOP).setSlots(CommandButton.SLOT_OVERFLOW)
        MediaAction.SeekBy -> builder.setPlayerCommand(Player.COMMAND_SEEK_FORWARD).setSlots(CommandButton.SLOT_FORWARD, CommandButton.SLOT_OVERFLOW)
    }
    return builder.build()
}
