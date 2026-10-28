package dev.brahmkshatriya.kmedianotification

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.test.*

class MediaSessionControlsTest {
    @Test fun publishesDisabledNextAndRejectsItUntilAvailable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val publications = Channel<MediaSessionState>(Channel.UNLIMITED)
        val nextCalls = Channel<Unit>(Channel.UNLIMITED)
        val next = MediaButton.Standard(MediaAction.Next, showWhenUnavailable = true)
        val platform = MediaPlatform.custom("Test", MediaCapabilities(setOf(MediaAction.Next))) { _ ->
            object : MediaBackend {
                override fun publish(state: MediaSessionState) { publications.trySend(state) }
                override fun close() = Unit
            }
        }
        val session = KMediaNotification.create(platform, scope, "next", "Next") {
            onNext { nextCalls.trySend(Unit) }
        }
        val state = MediaSessionState(
            item = NowPlayingItem("last", title = "Last"),
            buttons = listOf(next), availableActions = emptySet(),
        )
        try {
            session.publish(state)
            session.activate()
            val disabled = withTimeout(5_000) { publications.receiveAsFlow().first { it.item != null } }
            assertEquals(listOf(next), disabled.buttons)
            assertFalse(MediaAction.Next in disabled.availableActions.orEmpty())
            assertFalse(session.dispatch(MediaCommand.Next))
            assertTrue(nextCalls.tryReceive().isFailure)

            session.publish(state.copy(availableActions = setOf(MediaAction.Next)))
            val enabled = withTimeout(5_000) {
                publications.receiveAsFlow().first { MediaAction.Next in it.availableActions.orEmpty() }
            }
            assertEquals(disabled.buttons, enabled.buttons)
            assertTrue(session.dispatch(MediaCommand.Next))
            withTimeout(5_000) { nextCalls.receive() }

            session.publish(state)
            withTimeout(5_000) {
                publications.receiveAsFlow().first { MediaAction.Next !in it.availableActions.orEmpty() }
            }
            assertFalse(session.dispatch(MediaCommand.Next))
            assertTrue(nextCalls.tryReceive().isFailure)
        } finally {
            session.closeAndJoin()
            scope.cancel()
            publications.close()
            nextCalls.close()
        }
    }

    @Test fun rejectsStaleLikesAndReactsToCapabilityChanges() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val backend = CompletableDeferred<MediaBackendContext>()
        val received = CompletableDeferred<MediaCommand.SetLiked>()
        val capabilities = MediaCapabilities(setOf(MediaAction.SetLiked))
        val platform = MediaPlatform.custom("Test", capabilities) { context ->
            backend.complete(context)
            object : MediaBackend {
                override fun publish(state: MediaSessionState) = Unit
                override fun close() = Unit
            }
        }
        val session = KMediaNotification.create(platform, scope, "test", "Test") {
            onSetLiked { received.complete(it) }
        }
        try {
            session.publish(MediaSessionState(item = NowPlayingItem("one", title = "One"), buttons = listOf(MediaButton.Like), liked = false))
            session.activate()
            withTimeout(5_000) { session.status.first { it is SessionStatus.Active } }
            assertFalse(session.dispatch(MediaCommand.SetLiked(true, "old-track")))
            assertTrue(session.dispatch(MediaCommand.SetLiked(true)))
            assertEquals(MediaCommand.SetLiked(true, "one"), withTimeout(5_000) { received.await() })
            withTimeout(5_000) { session.controls.first { MediaButton.Like in it.buttons } }
            backend.await().setCapabilities(MediaCapabilities(emptySet()))
            withTimeout(5_000) { session.controls.first { it.buttons.isEmpty() && it.actions.isEmpty() } }
            assertFalse(session.dispatch(MediaCommand.SetLiked(false)))
        } finally {
            session.closeAndJoin()
            scope.cancel()
        }
    }
}
