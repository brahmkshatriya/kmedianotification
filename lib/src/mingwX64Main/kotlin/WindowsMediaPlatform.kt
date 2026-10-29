@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.brahmkshatriya.kmedianotification

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.toCPointer

/** Null uses the process's visible top-level window. Prefer an explicit HWND for multi-window apps. */
public fun MediaPlatform.Companion.windows(windowHandle: COpaquePointer? = null): MediaPlatform =
    nativeDesktopPlatform("SMTC", null, "", windowHandle)

public actual fun MediaPlatform.Companion.desktop(configure: DesktopMediaConfig.() -> Unit): MediaPlatform {
    val config = DesktopMediaConfig().apply(configure)
    return windows(config.windowsHandle?.toCPointer<CPointed>())
}
