package dev.brahmkshatriya.kmedianotification

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.Executors
import okio.FileSystem

public class JvmNativeMediaControls internal constructor(public val address: Long)
public suspend fun MediaSession.withDesktopMediaControls(block: (JvmNativeMediaControls) -> Unit): Boolean =
    withNativeAccess(JvmNativeMediaControls::class, block)

public actual fun MediaPlatform.Companion.desktop(configure: DesktopMediaConfig.() -> Unit): MediaPlatform {
    val config = DesktopMediaConfig().apply(configure)
    val os = System.getProperty("os.name").lowercase().let { when { it.contains("win") -> "windows"; it.contains("mac") -> "macos"; it.contains("linux") -> "linux"; else -> error("Unsupported desktop OS: $it") } }
    return MediaPlatform {
        val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "KMediaNotification-$os").apply { isDaemon = true } }.asCoroutineDispatcher()
        BackendHost(os, MediaCapabilities(if (os == "linux") desktopActions else if (os == "windows") windowsActions else appleActions,
            setOf(MediaFeature.Artwork, MediaFeature.Timeline)),
            dispatcher, { JvmMediaBackend(it, config, os) }, dispatcher::close)
    }
}
private interface NativeBridge : Library {
    fun interface CommandCallback : Callback { fun invoke(user: Pointer?, command: Int, value: Double, target: String?): Int }
    fun kmedia_create(callback: CommandCallback, user: Pointer?, id: String, displayName: String, desktopEntry: String, window: Pointer?, minimumRate: Double, maximumRate: Double): Pointer?
    fun kmedia_update(handle: Pointer, id: String, title: String, artist: String, album: String, artwork: String,
        present: Int, playing: Int, actions: Int, kind: Int, position: Double, duration: Double, speed: Double,
        volume: Double, shuffle: Int, repeat: Int, liked: Int, discontinuity: Int)
    fun kmedia_destroy(handle: Pointer)
    fun kmedia_native_handle(handle: Pointer): Pointer?
}
private class JvmMediaBackend(private val context: MediaBackendContext, config: DesktopMediaConfig, os: String) : MediaBackend {
    private val native: NativeBridge
    private var handle: Pointer? = null
    // Keep a strong reference until native callbacks have been detached by kmedia_destroy.
    private val callback = NativeBridge.CommandCallback { _, code, value, target ->
        val request = nativeCommand(code, value, target ?: if (code == 7 || code == 8) context.state.value.item?.entryId else null)
        if (request != null && context.dispatch(request)) 1 else 0
    }
    private var latest: MediaSessionState? = null
    private var refresh: Job? = null
    private var discontinuity: Pair<String?, Long>? = null
    private val artwork = DesktopArtwork(FileSystem.SYSTEM, FileSystem.SYSTEM_TEMPORARY_DIRECTORY, context.reportError)
    init {
        val arch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "aarch64" else "x86_64"
        val fileName = when (os) { "windows" -> "kmedia_notification.dll"; "macos" -> "libkmedia_notification.dylib"; else -> "libkmedia_notification.so" }
        val path = config.nativeLibraryPath ?: run {
            val resource = "/native/$os-$arch/$fileName"
            val stream = checkNotNull(javaClass.getResourceAsStream(resource)) { "Missing $resource; bundle the native bridge or set nativeLibraryPath" }
            val extracted = Files.createTempFile("kmedia-notification-", fileName.substringAfterLast('.').let { ".$it" }).toFile()
            extracted.deleteOnExit()
            stream.use { input -> extracted.outputStream().use(input::copyTo) }
            extracted.absolutePath
        }
        native = Native.load(path, NativeBridge::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        handle = checkNotNull(native.kmedia_create(callback, null, if (os == "linux") config.linuxBusName else context.id,
            context.displayName, config.linuxDesktopEntry, config.windowsHandle?.let(::Pointer), 0.25, 4.0)) { "Could not create $os media controls" }
        native.kmedia_native_handle(handle!!)?.let { context.setNativeAccess(JvmNativeMediaControls(Pointer.nativeValue(it))) }
        if (os == "windows") refresh = context.scope.launch {
            while (isActive) { delay(5_000); latest?.takeIf { it.playback.isAdvancing }?.let(::render) }
        }
    }
    override fun publish(state: MediaSessionState) { latest = state; render(state) }
    private fun render(state: MediaSessionState) {
        val item = state.item
        val sample = item?.entryId to state.playback.position.discontinuity
        native.kmedia_update(checkNotNull(handle), item?.entryId.orEmpty(), item?.title.orEmpty(), item?.artists?.joinToString().orEmpty(),
            item?.album.orEmpty(), artwork.uri(item), if (item != null) 1 else 0, if (state.playback.isAdvancing) 1 else 0,
            nativeActionMask(state.availableActions.orEmpty()), if (item?.kind == MediaKind.Video) 1 else 0,
            state.playback.positionNow().inWholeMilliseconds.toDouble(), state.playback.duration?.inWholeMilliseconds?.toDouble() ?: -1.0,
            state.playback.speed, state.volume, if (state.shuffle) 1 else 0, state.repeatMode.ordinal,
            state.liked?.let { if (it) 1 else 0 } ?: -1,
            if (discontinuity?.first == sample.first && discontinuity != sample) 1 else 0)
        discontinuity = sample
    }
    override fun close() { refresh?.cancel(); handle?.let(native::kmedia_destroy); handle = null; artwork.close(); context.setNativeAccess(null) }
}
