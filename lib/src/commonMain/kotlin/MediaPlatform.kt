package dev.brahmkshatriya.kmedianotification

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

public data class MediaCapabilities(
    val actions: Set<MediaAction>,
    val features: Set<MediaFeature> = emptySet(),
)
/** Implement this SPI to add a backend without modifying the session core. */
public interface MediaBackend : AutoCloseable {
    public fun publish(state: MediaSessionState)
    override fun close()
}
public class MediaBackendContext internal constructor(
    public val id: String,
    public val displayName: String,
    public val scope: CoroutineScope,
    public val state: StateFlow<MediaSessionState>,
    public val availableActions: () -> Set<MediaAction>,
    public val customActionIds: Set<String>,
    public val dispatch: (MediaCommand) -> Boolean,
    public val reportError: (String, Throwable) -> Unit,
    public val setNativeAccess: (Any?) -> Unit,
    public val setCapabilities: (MediaCapabilities) -> Unit,
)

public class MediaPlatform internal constructor(internal val host: () -> BackendHost) {
    public companion object {
        public fun custom(
            name: String,
            capabilities: MediaCapabilities,
            dispatcher: CoroutineDispatcher? = null,
            create: (MediaBackendContext) -> MediaBackend,
        ): MediaPlatform = MediaPlatform { BackendHost(name, capabilities, dispatcher, create) }
    }
}
internal class BackendHost(
    val name: String,
    val capabilities: MediaCapabilities,
    val dispatcher: CoroutineDispatcher?,
    val create: (MediaBackendContext) -> MediaBackend,
    val dispose: () -> Unit = {},
)
