package dev.brahmkshatriya.kmedianotification

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import okio.FileSystem
import okio.Path.Companion.toOkioPath

class DesktopArtworkTest {
    @Test fun suppliesLocalBytesReusesFilesAndCleansUp() {
        val root = Files.createTempDirectory("artwork with spaces é")
        val errors = mutableListOf<Throwable>()
        val cache = DesktopArtwork(FileSystem.SYSTEM, root.toFile().toOkioPath(), { _, error -> errors += error }, maxEntries = 2)
        try {
            val first = NowPlayingItem("one", title = "One", artworkUri = "https://invalid.example/never-load", artwork = MediaArtwork(byteArrayOf(1)))
            val uri = cache.uri(first)
            val file = Path.of(URI(uri))
            assertTrue(uri.startsWith("file://"))
            assertContentEquals(byteArrayOf(1), Files.readAllBytes(file))
            assertEquals(uri, cache.uri(first.copy(title = "Updated")))
            cache.uri(first.copy(artwork = MediaArtwork(byteArrayOf(2))))
            cache.uri(first.copy(artwork = MediaArtwork(byteArrayOf(3))))
            assertFalse(Files.exists(file))
            cache.close()
            Files.list(root).use { assertEquals(0, it.count()) }
            assertTrue(errors.isEmpty())
        } finally {
            cache.close()
            Files.delete(root)
        }
    }

    @Test fun doesNotFallBackToRemoteArtworkWhenLocalStorageFails() {
        val root = Files.createTempFile("artwork-blocked", ".tmp")
        var failures = 0
        val cache = DesktopArtwork(FileSystem.SYSTEM, root.toFile().toOkioPath(), { _, _ -> failures++ })
        val item = NowPlayingItem("one", title = "One", artworkUri = "https://invalid.example/never-load", artwork = MediaArtwork(byteArrayOf(1)))
        try {
            assertEquals("", cache.uri(item))
            assertEquals("", cache.uri(item))
            assertEquals(1, failures)
            cache.close()
            assertTrue(Files.exists(root))
        } finally { Files.delete(root) }
    }

    @Test fun encodesNativePathsAsFileUrls() {
        assertEquals("file:///tmp/art%20%C3%A9.jpg", artworkFileUri("/tmp/art é.jpg"))
        assertEquals("file:///C:/Users/Some%20User/art.jpg", artworkFileUri("C:\\Users\\Some User\\art.jpg"))
        assertEquals("file://host/share/art.jpg", artworkFileUri("\\\\host\\share\\art.jpg"))
    }
}
