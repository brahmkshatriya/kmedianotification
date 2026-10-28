@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
package dev.brahmkshatriya.kmedianotification

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration

public sealed interface SessionStatus {
    public data object Inactive : SessionStatus
    public data object Connecting : SessionStatus
    public data class Active(val backend: String, val generation: Long = 0) : SessionStatus
    public data class Unavailable(val operation: String, val cause: Throwable) : SessionStatus
    public data object Closed : SessionStatus
}
public sealed interface MediaSessionEvent {
    public data class Error(val operation: String, val cause: Throwable) : MediaSessionEvent
    public data class CommandFinished(val command: MediaCommand, val result: CommandResult) : MediaSessionEvent
}
public object KMediaNotification {
    public fun create(
        platform: MediaPlatform,
        scope: CoroutineScope,
        id: String,
        displayName: String,
        commands: MediaCommandsBuilder.() -> Unit = {},
    ): MediaSession {
        require(id.isNotBlank()); require(displayName.isNotBlank())
        return MediaSession(platform, scope, id, displayName, MediaCommandsBuilder().apply(commands))
    }
}

/** Owns OS integration only. It never owns or releases the consumer's player or scope. */
public class MediaSession internal constructor(
    platform: MediaPlatform,
    ownerScope: CoroutineScope,
    id: String,
    displayName: String,
    private val commands: MediaCommandsBuilder,
) : AutoCloseable {
    private val closed = MutableStateFlow(false)
    private val active = MutableStateFlow(false)
    private val activation = MutableStateFlow(0L)
    internal val activationRequested: Boolean get() = active.value
    internal fun ownsNativeAccess(value: Any?): Boolean = value != null && nativeAccess.value === value
    private val mutableState = MutableStateFlow(MediaSessionState())
    public val state: StateFlow<MediaSessionState> = mutableState.asStateFlow()
    private val mutableStatus = MutableStateFlow<SessionStatus>(SessionStatus.Inactive)
    public val status: StateFlow<SessionStatus> = mutableStatus.asStateFlow()
    private val mutableEvents = MutableSharedFlow<MediaSessionEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    public val events: SharedFlow<MediaSessionEvent> = mutableEvents.asSharedFlow()
    private val host = platform.host()
    private val mutableCapabilities = MutableStateFlow(host.capabilities)
    public val capabilities: StateFlow<MediaCapabilities> = mutableCapabilities.asStateFlow()
    private val nativeAccess = MutableStateFlow<Any?>(null)
    private val job = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + job)
    private val incoming = Channel<MediaCommand>(64)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cleanupDone = CompletableDeferred<Unit>()
    private var binding: Job? = null
    public val supportedActions: Set<MediaAction> = commands.handlers.keys.toSet() +
        (if (MediaAction.Play in commands.handlers && MediaAction.Pause in commands.handlers) setOf(MediaAction.TogglePlayPause) else emptySet()) +
        (if (commands.custom.isNotEmpty()) setOf(MediaAction.Custom) else emptySet())
    /** Observe this rather than branching on OS names. Updated when browser capabilities change. */
    public val controls: StateFlow<MediaControls> = combine(state, capabilities, ::resolveControls)
        .stateIn(scope, SharingStarted.Eagerly, resolveControls(state.value, capabilities.value))

    init {
        scope.launch {
            for (command in incoming) {
                if (!admissible(command)) {
                    mutableEvents.emit(MediaSessionEvent.CommandFinished(command, CommandResult.Rejected("Playback state changed before execution")))
                    continue
                }
                try {
                    val normalized = if (command == MediaCommand.TogglePlayPause && MediaAction.TogglePlayPause !in commands.handlers) {
                        if (state.value.playback.playWhenReady) MediaCommand.Pause else MediaCommand.Play
                    } else command
                    val result = if (normalized is MediaCommand.Custom) commands.custom[normalized.id]?.invoke(normalized.payload)
                    else commands.handlers[normalized.action]?.invoke(normalized)
                    mutableEvents.emit(MediaSessionEvent.CommandFinished(command, result ?: CommandResult.Unsupported))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { reportError("Command ${command.action}", error) }
            }
        }
        val worker = scope.launch(host.dispatcher ?: EmptyCoroutineContext, start = CoroutineStart.ATOMIC) {
            var backend: MediaBackend? = null
            var failedActivation: Long? = null
            var generation = 0L
            val context = MediaBackendContext(id, displayName, this, state, ::effectiveActions,
                commands.custom.keys.toSet(), ::dispatch, ::reportError,
                { nativeAccess.value = it }, { mutableCapabilities.value = it })
            try {
                combine(state, active, activation, capabilities) { snapshot, enabled, request, _ -> Triple(snapshot, enabled, request) }.collect { (snapshot, enabled, request) ->
                    if (!enabled) {
                        runCatching { backend?.close() }.onFailure { reportError("Deactivate media session", it) }; backend = null; nativeAccess.value = null
                        mutableStatus.value = SessionStatus.Inactive
                        failedActivation = null
                    } else if (failedActivation != request) try {
                        if (backend == null) {
                            mutableStatus.value = SessionStatus.Connecting
                            backend = host.create(context)
                            generation++
                        }
                        val controls = resolveControls(snapshot, capabilities.value)
                        backend?.publish(snapshot.copy(availableActions = controls.actions, buttons = controls.buttons))
                        mutableStatus.value = SessionStatus.Active(host.name, generation)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Throwable) {
                        runCatching { backend?.close() }; backend = null; nativeAccess.value = null
                        failedActivation = request
                        mutableStatus.value = SessionStatus.Unavailable("Publish media session", error)
                        reportError("Publish media session", error)
                    }
                }
            } finally {
                try { runCatching { backend?.close() }.onFailure { reportError("Close media session", it) } } finally {
                    nativeAccess.value = null; closed.value = true; incoming.close()
                    // The dedicated dispatcher is disposed after this coroutine has left it.
                    // Kotlin/Native waits for worker termination; closing it here would self-join.
                }
            }
        }
        worker.invokeOnCompletion {
            cleanupScope.launch {
                try { host.dispose() }
                catch (error: Throwable) { reportError("Release media dispatcher", error) }
                finally {
                    mutableStatus.value = SessionStatus.Closed
                    cleanupDone.complete(Unit)
                    cleanupScope.cancel()
                }
            }
        }
    }
    public fun publish(state: MediaSessionState) {
        check(!closed.value) { "Media session is closed" }
        val previous = mutableState.value
        mutableState.value = state.copy(
            item = if (previous.item == state.item) previous.item else state.item?.copy(artists = state.item.artists.toList()),
            queue = if (previous.queue == state.queue) previous.queue else QueueSnapshot(state.queue),
            availableActions = state.availableActions?.toSet(),
            buttons = state.buttons.toList(),
        )
    }
    /** Bind once, or publish imperatively. Both use the same state pipeline. */
    public fun bind(states: Flow<MediaSessionState>): Job {
        check(!closed.value)
        check(binding?.isActive != true) { "A state source is already bound" }
        return scope.launch {
            try { states.collect(::publish) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { reportError("Collect playback state", error) }
        }.also { binding = it }
    }
    public fun activate() { check(!closed.value); activation.update { it + 1 }; active.value = true }
    public fun deactivate() { active.value = false }
    public fun effectiveActions(): Set<MediaAction> = effectiveActions(state.value)
    private fun resolveControls(snapshot: MediaSessionState, capabilities: MediaCapabilities): MediaControls =
        resolveMediaControls(snapshot, supportedActions, commands.custom.keys, capabilities)
    private fun effectiveActions(snapshot: MediaSessionState): Set<MediaAction> = resolveControls(snapshot, capabilities.value).actions
    /** Nonblocking admission for synchronous OS callbacks; execution remains serialized. */
    public fun dispatch(command: MediaCommand): Boolean {
        val snapshot = state.value
        if (!admissible(command, snapshot)) return false
        val scoped = when (command) {
            is MediaCommand.SeekTo -> command.copy(entryId = command.entryId ?: snapshot.item?.entryId)
            is MediaCommand.SeekBy -> command.copy(entryId = command.entryId ?: snapshot.item?.entryId)
            is MediaCommand.SetLiked -> command.copy(entryId = command.entryId ?: snapshot.item?.entryId)
            is MediaCommand.Custom -> command.copy(payload = command.payload.copyOf())
            else -> command
        }
        return incoming.trySend(scoped).isSuccess
    }
    private fun admissible(command: MediaCommand, snapshot: MediaSessionState = state.value): Boolean {
        if (closed.value || !active.value || command.action !in effectiveActions(snapshot)) return false
        val target = when (command) {
            is MediaCommand.SeekTo -> command.entryId
            is MediaCommand.SeekBy -> command.entryId
            is MediaCommand.SetLiked -> command.entryId
            else -> null
        }
        if (target != null && target != snapshot.item?.entryId) return false
        return when (command) {
            is MediaCommand.SeekTo -> command.position.isFinite() && command.position >= Duration.ZERO &&
                snapshot.playback.seekableRange?.let { command.position in it.start..it.end } == true
            is MediaCommand.SeekBy -> command.offset.isFinite() && snapshot.playback.seekableRange != null
            is MediaCommand.SetVolume -> command.volume.isFinite() && command.volume in 0.0..1.0
            is MediaCommand.SetRate -> command.speed.isFinite() && command.speed > 0
            is MediaCommand.SkipToEntry -> snapshot.queue.any { it.entryId == command.entryId }
            is MediaCommand.Custom -> command.id in commands.custom && command.payload.size <= 256 * 1024
            else -> true
        }
    }
    private fun reportError(operation: String, error: Throwable) {
        mutableEvents.tryEmit(MediaSessionEvent.Error(operation, error))
    }
    /** Platform extensions use this checked accessor; native handles cannot outlive activation. */
    public suspend fun <T : Any> withNativeAccess(type: kotlin.reflect.KClass<T>, block: (T) -> Unit): Boolean =
        if (closed.value) false else withContext(scope.coroutineContext + (host.dispatcher ?: EmptyCoroutineContext)) {
            val native = nativeAccess.value ?: return@withContext false
            if (!type.isInstance(native)) return@withContext false
            @Suppress("UNCHECKED_CAST") block(native as T)
            true
        }
    /** Await native cleanup before destroying an owning window or stopping its event loop. */
    public suspend fun closeAndJoin() { close(); job.join(); cleanupDone.await() }
    override fun close() {
        if (closed.compareAndSet(false, true)) { active.value = false; incoming.close(); job.cancel() }
    }
}
