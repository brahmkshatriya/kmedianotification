@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.brahmkshatriya.kmedianotification

import dev.brahmkshatriya.kmedianotification.interop.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import okio.FileSystem

internal fun nativeDesktopPlatform(name: String, busName: String?, desktopEntry: String,
    window: COpaquePointer?, minimumRate: Double = 0.25, maximumRate: Double = 4.0): MediaPlatform = MediaPlatform {
    val dispatcher = newSingleThreadContext("KMediaNotification-$name")
    BackendHost(name, MediaCapabilities(if (name == "MPRIS") desktopActions else windowsActions,
        setOf(MediaFeature.Timeline, MediaFeature.Artwork)), dispatcher,
        { context -> NativeMediaBackend(context, busName, desktopEntry, window, minimumRate, maximumRate, name == "SMTC") }, dispatcher::close)
}
public class NativeMediaControls internal constructor(public val pointer: COpaquePointer)
/** The pointer is borrowed and valid only inside block, on the backend dispatcher. */
public suspend fun MediaSession.withNativeMediaControls(block: (NativeMediaControls) -> Unit): Boolean =
    withNativeAccess(NativeMediaControls::class, block)

private class NativeMediaBackend(private val context: MediaBackendContext, busName: String?, desktopEntry: String,
    window: COpaquePointer?, minimumRate: Double, maximumRate: Double, private val periodicRefresh: Boolean) : MediaBackend {
    private val reference = StableRef.create(context)
    private var handle: COpaquePointer? = null
    private var lastDiscontinuity: Pair<String?, Long>? = null
    private var latest: MediaSessionState? = null
    private var refresh: Job? = null
    private val artwork = DesktopArtwork(FileSystem.SYSTEM, FileSystem.SYSTEM_TEMPORARY_DIRECTORY, context.reportError)
    init {
        try {
            handle = checkNotNull(kmedia_create(staticCFunction(::onCommand), reference.asCPointer(),
                busName ?: context.id, context.displayName, desktopEntry, window, minimumRate, maximumRate)) {
                "Could not create system media controls (check the session bus/window and native SDK)"
            }
            kmedia_native_handle(handle)?.let { context.setNativeAccess(NativeMediaControls(it)) }
        } catch (error: Throwable) { reference.dispose(); throw error }
    }
    override fun publish(state: MediaSessionState) {
        latest = state
        render(state)
        // Windows asks for periodic timeline updates. This projects cached state; it never queries a player.
        if (periodicRefresh &&
            state.playback.isAdvancing && refresh?.isActive != true) {
            refresh = context.scope.launch {
                while (isActive) { delay(5_000); latest?.takeIf { it.playback.isAdvancing }?.let(::render) }
            }
        } else if (!state.playback.isAdvancing) { refresh?.cancel(); refresh = null }
    }
    private fun render(state: MediaSessionState) {
        val item = state.item
        val sample = item?.entryId to state.playback.position.discontinuity
        kmedia_update(handle, item?.entryId.orEmpty(), item?.title.orEmpty(), item?.artists?.joinToString().orEmpty(),
            item?.album.orEmpty(), artwork.uri(item), if (item != null) 1 else 0,
            if (state.playback.isAdvancing) 1 else 0, nativeActionMask(state.availableActions.orEmpty()),
            if (item?.kind == MediaKind.Video) 1 else 0,
            state.playback.positionNow().inWholeMilliseconds.toDouble(),
            state.playback.duration?.inWholeMilliseconds?.toDouble() ?: -1.0,
            state.playback.speed, state.volume, if (state.shuffle) 1 else 0, state.repeatMode.ordinal,
            state.liked?.let { if (it) 1 else 0 } ?: -1,
            if (lastDiscontinuity?.first == sample.first && lastDiscontinuity != sample) 1 else 0)
        lastDiscontinuity = sample
    }
    override fun close() {
        refresh?.cancel()
        handle?.let(::kmedia_destroy); handle = null
        artwork.close()
        context.setNativeAccess(null)
        reference.dispose()
    }
}
private fun onCommand(user: COpaquePointer?, command: Int, value: Double, target: CPointer<ByteVar>?): Int {
    val context = user?.asStableRef<MediaBackendContext>()?.get() ?: return 0
    val request = nativeCommand(command, value, target?.toKString() ?: if (command == 7 || command == 8) context.state.value.item?.entryId else null)
    return if (request != null && context.dispatch(request)) 1 else 0
}
