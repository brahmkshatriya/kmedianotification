package dev.brahmkshatriya.kmedianotification

import okio.FileSystem
import okio.Path
import kotlin.random.Random

/** Session-owned files for URL-only desktop APIs. Keep recent covers for asynchronous OS readers. */
internal class DesktopArtwork(
    private val fileSystem: FileSystem,
    private val temporaryDirectory: Path,
    private val reportError: (String, Throwable) -> Unit,
    private val maxEntries: Int = 12,
) : AutoCloseable {
    private var directory: Path? = null
    private var sequence = 0
    private val files = LinkedHashMap<MediaArtwork, Path>()
    private var current: MediaArtwork? = null
    private var currentUri = ""

    fun uri(item: NowPlayingItem?): String {
        val artwork = item?.artwork
        if (artwork == null) {
            current = null
            currentUri = ""
            return item?.artworkUri.orEmpty()
        }
        if (current == artwork) return currentUri
        current = artwork
        currentUri = try {
            val cached = files.remove(artwork)
            val file = cached ?: run {
                val root = directory ?: (temporaryDirectory / "kmedia-artwork-${Random.nextLong().toULong().toString(16)}").also {
                    fileSystem.createDirectory(it, mustCreate = true)
                    directory = it
                }
                val extension = when (artwork.mimeType) {
                    "image/jpeg" -> "jpg"
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    else -> "img"
                }
                val path = root / "${sequence++}.$extension"
                fileSystem.write(path, mustCreate = true) { write(artwork.encodedData) }
                path
            }
            files[artwork] = file
            while (files.size > maxEntries) {
                val oldest = files.entries.first()
                fileSystem.delete(oldest.value)
                files.remove(oldest.key)
            }
            artworkFileUri(file.toString())
        } catch (failure: Exception) {
            reportError("Prepare local artwork", failure)
            ""
        }
        return currentUri
    }

    override fun close() {
        directory?.let { root ->
            try { fileSystem.deleteRecursively(root) }
            catch (failure: Exception) { reportError("Remove local artwork", failure) }
        }
        directory = null
        files.clear()
        current = null
        currentUri = ""
    }
}

internal fun artworkFileUri(path: String): String {
    val normalized = path.replace('\\', '/')
    val absolute = if (normalized.startsWith('/')) normalized else "/$normalized"
    val encoded = buildString {
        for (byte in absolute.encodeToByteArray()) {
            val value = byte.toInt() and 255
            if (value in 65..90 || value in 97..122 || value in 48..57 || value.toChar() in "/:-._~") append(value.toChar())
            else { append('%'); append("0123456789ABCDEF"[value ushr 4]); append("0123456789ABCDEF"[value and 15]) }
        }
    }
    return if (encoded.startsWith("//")) "file:$encoded" else "file://$encoded"
}
