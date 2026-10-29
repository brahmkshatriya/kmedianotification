package dev.brahmkshatriya.kmedianotification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaControlsTest {
    private val item = NowPlayingItem("one", title = "One")
    private val preferences = listOf(MediaButton.Previous, MediaButton.PlayPause, MediaButton.Next, MediaButton.Like, MediaButton.Repeat)
    private val registered = setOf(MediaAction.Previous, MediaAction.TogglePlayPause, MediaAction.Next, MediaAction.SetLiked, MediaAction.SetRepeatMode, MediaAction.Custom)

    @Test fun filtersUnsupportedWebButtonsWithoutAnOsBranch() {
        val capabilities = MediaCapabilities(setOf(MediaAction.Previous, MediaAction.TogglePlayPause, MediaAction.Next))
        val result = resolveMediaControls(MediaSessionState(item = item, buttons = preferences, liked = false), registered, emptySet(), capabilities)
        assertEquals(preferences.take(3), result.buttons)
        assertFalse(MediaAction.SetRepeatMode in result.actions)
        assertFalse(MediaAction.SetLiked in result.actions)
    }

    @Test fun likeRequiresAnItemAndAnExplicitLikedState() {
        val capabilities = MediaCapabilities(registered)
        for (state in listOf(MediaSessionState(buttons = preferences, liked = false), MediaSessionState(item = item, buttons = preferences))) {
            val result = resolveMediaControls(state, registered, emptySet(), capabilities)
            assertFalse(MediaAction.SetLiked in result.actions)
            assertFalse(MediaButton.Like in result.buttons)
        }
        val result = resolveMediaControls(MediaSessionState(item = item, buttons = preferences, liked = false), registered, emptySet(), capabilities)
        assertTrue(MediaButton.Like in result.buttons)
    }

    @Test fun preservesMixedButtonOrderAndChecksCustomRegistration() {
        val bookmark = MediaButton.Custom("bookmark", "Bookmark", MediaIcon.Bookmark)
        val unknown = MediaButton.Custom("unknown", "Unknown")
        val state = MediaSessionState(item = item, buttons = listOf(MediaButton.Previous, bookmark, unknown, MediaButton.Next))
        val result = resolveMediaControls(state, registered, setOf("bookmark"), MediaCapabilities(registered, setOf(MediaFeature.CustomButtons)))
        assertEquals(listOf(MediaButton.Previous, bookmark, MediaButton.Next), result.buttons)
        val native = resolveMediaControls(state, registered, setOf("bookmark"), MediaCapabilities(registered))
        assertEquals(listOf(MediaButton.Previous, MediaButton.Next), native.buttons)
    }

    @Test fun appliesCurrentAvailabilityAndToggleIcons() {
        val state = MediaSessionState(item = item, availableActions = setOf(MediaAction.SetLiked), buttons = preferences, liked = true)
        val result = resolveMediaControls(state, registered, emptySet(), MediaCapabilities(registered))
        assertEquals(listOf(MediaButton.Like), result.buttons)
        assertEquals(MediaIcon.FavoriteFilled, MediaButton.Like.icon(state))
        assertEquals(MediaIcon.Favorite, MediaButton.Like.icon(state.copy(liked = false)))
        assertEquals(MediaIcon.RepeatOne, MediaButton.Repeat.icon(state.copy(repeatMode = RepeatMode.One)))
    }

    @Test fun retainsDisabledNextWithoutEnablingItsAction() {
        val next = MediaButton.Standard(MediaAction.Next, showWhenUnavailable = true)
        val state = MediaSessionState(
            item = item,
            buttons = listOf(MediaButton.Previous, MediaButton.PlayPause, next),
            availableActions = setOf(MediaAction.Previous, MediaAction.TogglePlayPause),
        )
        val capabilities = MediaCapabilities(registered)
        val result = resolveMediaControls(state, registered, emptySet(), capabilities)
        assertEquals(state.buttons, result.buttons)
        assertFalse(MediaAction.Next in result.actions)
        val enabled = resolveMediaControls(state.copy(availableActions = registered), registered, emptySet(), capabilities)
        assertEquals(state.buttons, enabled.buttons)
        assertTrue(MediaAction.Next in enabled.actions)

        val unsupported = resolveMediaControls(state, registered, emptySet(), MediaCapabilities(registered - MediaAction.Next))
        assertFalse(next in unsupported.buttons)
        val unregistered = resolveMediaControls(state, registered - MediaAction.Next, emptySet(), capabilities)
        assertFalse(next in unregistered.buttons)
        val empty = resolveMediaControls(state.copy(item = null), registered, emptySet(), capabilities)
        assertFalse(next in empty.buttons)
    }
}
