@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.brahmkshatriya.kmedianotification
import platform.Foundation.NSData
import platform.MediaPlayer.MPMediaItemArtwork
import platform.UIKit.UIImage
internal fun createAppleArtwork(data: NSData): MPMediaItemArtwork? = UIImage(data = data)?.let { image ->
    MPMediaItemArtwork(boundsSize = image.size, requestHandler = { image })
}
