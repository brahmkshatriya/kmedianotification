package dev.brahmkshatriya.kmedianotification

/** An encoded image supplied by the application, including images from its own loader/cache.
 * Backends prefer this over [NowPlayingItem.artworkUri] and never download a replacement. */
public class MediaArtwork(data: ByteArray, public val mimeType: String = "image/png") {
    internal val encodedData: ByteArray = data.copyOf()
    private val contentHash: Int = encodedData.contentHashCode()

    init {
        require(encodedData.isNotEmpty()) { "Artwork must contain an encoded image" }
        require(mimeType.startsWith("image/") && mimeType.none { it.isWhitespace() || it == ';' || it == ',' })
    }

    /** Returns a detached copy; publishing an image never shares mutable bytes with its caller. */
    public fun toByteArray(): ByteArray = encodedData.copyOf()

    override fun equals(other: Any?): Boolean = this === other ||
        other is MediaArtwork && mimeType == other.mimeType && encodedData.contentEquals(other.encodedData)
    override fun hashCode(): Int = 31 * contentHash + mimeType.hashCode()
}
