package dev.brahmkshatriya.kmedianotification

/** Settings shared by the JVM and Kotlin/Native desktop factories. */
public class DesktopMediaConfig {
    /** MPRIS service name, used only on Linux. */
    public var linuxBusName: String = "org.mpris.MediaPlayer2.player"
    /** Desktop entry ID, used only on Linux. */
    public var linuxDesktopEntry: String = ""
    /** Windows HWND address. Null selects the process's visible top-level window. */
    public var windowsHandle: Long? = null
    /** JVM-only override for a signed or application-bundled native bridge. */
    public var nativeLibraryPath: String? = null
}

/**
 * Creates desktop media controls. Kotlin/Native selects the backend at compile time;
 * JVM selects it for the host OS. Platform-specific factories remain available for
 * additional native configuration.
 */
public expect fun MediaPlatform.Companion.desktop(
    configure: DesktopMediaConfig.() -> Unit = {},
): MediaPlatform
