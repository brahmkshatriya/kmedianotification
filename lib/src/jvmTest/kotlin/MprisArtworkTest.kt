package dev.brahmkshatriya.kmedianotification

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class MprisArtworkTest {
    @Test fun publishesCachedImageWithoutExposingItsRemoteUrl() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Linux") || System.getenv("DBUS_SESSION_BUS_ADDRESS") == null) return@runBlocking
        val name = "org.mpris.MediaPlayer2.kmedia_artwork_test"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = KMediaNotification.create(MediaPlatform.desktop { linuxBusName = name }, scope, "artwork-test", "Artwork test") { onPlay {} }
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
        var file: Path? = null
        try {
            session.publish(MediaSessionState(item = NowPlayingItem("one", title = "Cached artwork", artworkUri = "https://invalid.example/never-load", artwork = MediaArtwork(png))))
            session.activate()
            withTimeout(10_000) { session.status.first { it is SessionStatus.Active || it is SessionStatus.Unavailable }.also { assertIs<SessionStatus.Active>(it) } }
            val names = command("gdbus", "call", "--session", "--dest", "org.freedesktop.DBus", "--object-path", "/org/freedesktop/DBus", "--method", "org.freedesktop.DBus.ListNames")
            val owner = Regex("""'(${Regex.escape(name)}\.instance[^']+)'""").find(names)?.groupValues?.get(1)
            assertNotNull(owner, names)
            val metadata = command("gdbus", "call", "--session", "--dest", owner, "--object-path", "/org/mpris/MediaPlayer2", "--method", "org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "Metadata")
            assertFalse(metadata.contains("invalid.example"), metadata)
            val uri = Regex("file://[^']+").find(metadata)?.value
            assertNotNull(uri, metadata)
            file = Path.of(URI(uri))
            assertContentEquals(png, Files.readAllBytes(file))
        } finally {
            session.closeAndJoin()
            scope.cancel()
        }
        assertFalse(Files.exists(file))
    }

    private fun command(vararg args: String): String {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return output
    }
}
