package dev.brahmkshatriya.kmedianotification

/** Semantic icons; platforms may use system artwork instead of consumer-provided icons. */
public enum class MediaIcon {
    Play, Pause, Previous, Next, Stop, SeekForward, SeekBackward,
    Favorite, FavoriteFilled, Bookmark, BookmarkFilled,
    RepeatOff, RepeatAll, RepeatOne, ShuffleOff, ShuffleOn, Custom,
}

/** One ordered list for native actions and typed custom actions. */
public sealed interface MediaButton {
    public data class Standard(
        val action: MediaAction,
        /**
         * Retain a supported, registered control when [MediaSessionState.availableActions] disables it.
         * Some system surfaces reserve an empty slot instead of drawing a disabled icon.
         */
        val showWhenUnavailable: Boolean = false,
    ) : MediaButton
    public data class Custom(val id: String, val label: String, val icon: MediaIcon = MediaIcon.Custom) : MediaButton {
        init {
            require(id.isNotBlank()) { "Custom media button ID must not be blank" }
            require(label.isNotBlank()) { "Custom media button label must not be blank" }
        }
    }

    public companion object {
        public val Previous: MediaButton = Standard(MediaAction.Previous)
        public val PlayPause: MediaButton = Standard(MediaAction.TogglePlayPause)
        public val Next: MediaButton = Standard(MediaAction.Next)
        public val Stop: MediaButton = Standard(MediaAction.Stop)
        public val Like: MediaButton = Standard(MediaAction.SetLiked)
        public val Repeat: MediaButton = Standard(MediaAction.SetRepeatMode)
        public val Shuffle: MediaButton = Standard(MediaAction.SetShuffle)
    }
}

/** Effective controls after registered handlers, current state, and platform support are applied. */
public data class MediaControls(val actions: Set<MediaAction>, val buttons: List<MediaButton>)

/** A visible button for a registered action that needs no payload. */
public fun ActionKey<Unit>.button(label: String, icon: MediaIcon = MediaIcon.Custom): MediaButton =
    MediaButton.Custom(id, label, icon)

internal fun resolveMediaControls(
    state: MediaSessionState,
    registeredActions: Set<MediaAction>,
    customActionIds: Set<String>,
    capabilities: MediaCapabilities,
): MediaControls {
    val supported = registeredActions.intersect(capabilities.actions)
    val actions = (state.availableActions ?: registeredActions)
        .intersect(supported).toMutableSet()
    if (state.item == null || state.liked == null) actions.remove(MediaAction.SetLiked)
    val buttons = state.buttons.filter { button ->
        when (button) {
            is MediaButton.Standard -> button.action in actions ||
                (button.showWhenUnavailable && button.action in supported && state.item != null &&
                    (button.action != MediaAction.SetLiked || state.liked != null))
            is MediaButton.Custom -> MediaAction.Custom in actions &&
                MediaFeature.CustomButtons in capabilities.features && button.id in customActionIds
        }
    }
    return MediaControls(actions, buttons)
}

internal fun MediaButton.icon(state: MediaSessionState): MediaIcon = when (this) {
    is MediaButton.Custom -> icon
    is MediaButton.Standard -> when (action) {
        MediaAction.Play -> MediaIcon.Play
        MediaAction.Pause -> MediaIcon.Pause
        MediaAction.TogglePlayPause -> if (state.playback.playWhenReady) MediaIcon.Pause else MediaIcon.Play
        MediaAction.Previous -> MediaIcon.Previous
        MediaAction.Next -> MediaIcon.Next
        MediaAction.Stop -> MediaIcon.Stop
        MediaAction.SeekBy -> MediaIcon.SeekForward
        MediaAction.SetLiked -> if (state.liked == true) MediaIcon.FavoriteFilled else MediaIcon.Favorite
        MediaAction.SetShuffle -> if (state.shuffle) MediaIcon.ShuffleOn else MediaIcon.ShuffleOff
        MediaAction.SetRepeatMode -> when (state.repeatMode) {
            RepeatMode.Off -> MediaIcon.RepeatOff
            RepeatMode.All -> MediaIcon.RepeatAll
            RepeatMode.One -> MediaIcon.RepeatOne
        }
        else -> MediaIcon.Custom
    }
}
