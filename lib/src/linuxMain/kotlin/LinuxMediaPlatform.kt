@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.brahmkshatriya.kmedianotification

public class LinuxMediaConfig {
    public var busName: String = "org.mpris.MediaPlayer2.player"
    public var desktopEntry: String = ""
    public var minimumRate: Double = 0.25
    public var maximumRate: Double = 4.0
}
public fun MediaPlatform.Companion.linux(configure: LinuxMediaConfig.() -> Unit = {}): MediaPlatform {
    val config = LinuxMediaConfig().apply(configure)
    require(config.busName.startsWith("org.mpris.MediaPlayer2.") && config.busName.split('.').all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) })
    require(config.minimumRate > 0 && config.maximumRate >= config.minimumRate && config.maximumRate.isFinite())
    return nativeDesktopPlatform("MPRIS", config.busName, config.desktopEntry, null, config.minimumRate, config.maximumRate)
}

public actual fun MediaPlatform.Companion.desktop(configure: DesktopMediaConfig.() -> Unit): MediaPlatform {
    val config = DesktopMediaConfig().apply(configure)
    return linux {
        busName = config.linuxBusName
        desktopEntry = config.linuxDesktopEntry
    }
}
