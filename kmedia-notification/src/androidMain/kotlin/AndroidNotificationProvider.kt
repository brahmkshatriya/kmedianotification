@file:Suppress("UnstableApiUsage")
package dev.brahmkshatriya.kmedianotification

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/** Media3 inserts its own transport buttons on older Android; apply the consumer's icons there too. */
internal class AndroidNotificationProvider(private val context: Context, private val config: AndroidMediaConfig) :
    DefaultMediaNotificationProvider(context, { config.notification.notificationId },
        config.notification.channelId, androidx.media3.session.R.string.default_notification_channel_name) {
    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        showPauseButton: Boolean,
    ): ImmutableList<CommandButton> {
        val buttons = super.getMediaButtons(session, playerCommands, mediaButtonPreferences, showPauseButton).toMutableList()
        // Media3's default layout drops unavailable buttons. Retain explicit disabled preferences.
        mediaButtonPreferences.filterNot { it.isEnabled }.forEach { button ->
            buttons.removeAll {
                if (button.sessionCommand != null) it.sessionCommand == button.sessionCommand
                else it.playerCommand == button.playerCommand
            }
            val index = buttons.indexOfFirst { slotOrder(it) > slotOrder(button) }
            buttons.add(if (index < 0) buttons.size else index, button)
        }
        return ImmutableList.copyOf(buttons.map { button ->
            val (control, icon) = when (button.playerCommand) {
                Player.COMMAND_PLAY_PAUSE -> MediaButton.PlayPause to if (showPauseButton) MediaIcon.Pause else MediaIcon.Play
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> MediaButton.Previous to MediaIcon.Previous
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> MediaButton.Next to MediaIcon.Next
                else -> return@map button
            }
            config.iconBuilder(control, icon, button.isEnabled).setPlayerCommand(button.playerCommand, button.parameter)
                .setDisplayName(button.displayName).setEnabled(button.isEnabled)
                .setExtras(button.extras).setSlots(*button.slots.toArray()).build()
        })
    }

    override fun addNotificationActions(
        mediaSession: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory,
    ): IntArray {
        // A null PendingIntent makes the legacy notification action visibly disabled and unclickable.
        val factory = object : MediaNotification.ActionFactory by actionFactory {
            override fun createMediaAction(
                session: MediaSession, icon: IconCompat, title: CharSequence, command: Int,
            ): NotificationCompat.Action =
                if (mediaButtons.any { it.playerCommand == command && !it.isEnabled })
                    NotificationCompat.Action.Builder(icon, title, null).build()
                else actionFactory.createMediaAction(session, icon, title, command)

            override fun createCustomActionFromCustomCommandButton(
                session: MediaSession, button: CommandButton,
            ): NotificationCompat.Action =
                if (!button.isEnabled) NotificationCompat.Action.Builder(
                    IconCompat.createWithResource(context, button.iconResId), button.displayName, null,
                ).build()
                else actionFactory.createCustomActionFromCustomCommandButton(session, button)
        }
        return super.addNotificationActions(mediaSession, mediaButtons, builder, factory)
    }

    private fun slotOrder(button: CommandButton): Int = when (button.slots.get(0)) {
        CommandButton.SLOT_BACK -> 0
        CommandButton.SLOT_CENTRAL -> 1
        CommandButton.SLOT_FORWARD -> 2
        else -> 3
    }
}
