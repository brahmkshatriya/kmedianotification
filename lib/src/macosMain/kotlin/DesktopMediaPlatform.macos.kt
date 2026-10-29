package dev.brahmkshatriya.kmedianotification

public actual fun MediaPlatform.Companion.desktop(configure: DesktopMediaConfig.() -> Unit): MediaPlatform {
    DesktopMediaConfig().apply(configure)
    return apple()
}
