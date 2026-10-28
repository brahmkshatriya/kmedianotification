package dev.brahmkshatriya.kmedianotification

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MediaArtworkTest {
    @Test fun ownsItsEncodedBytes() {
        val bytes = byteArrayOf(1, 2, 3)
        val artwork = MediaArtwork(bytes)
        bytes[0] = 9
        artwork.toByteArray()[1] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), artwork.toByteArray())
        assertEquals(MediaArtwork(byteArrayOf(1, 2, 3)), artwork)
        assertEquals(MediaArtwork(byteArrayOf(1, 2, 3)).hashCode(), artwork.hashCode())
    }

    @Test fun rejectsEmptyImagesAndInvalidMimeTypes() {
        assertFailsWith<IllegalArgumentException> { MediaArtwork(byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { MediaArtwork(byteArrayOf(1), "text/html") }
        assertFailsWith<IllegalArgumentException> { MediaArtwork(byteArrayOf(1), "image/png;base64,") }
    }
}
