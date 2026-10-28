@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package dev.brahmkshatriya.kmedianotification
import kotlin.js.js

internal actual fun installActions(callback: (String, Double) -> Unit): (String, Boolean) -> Boolean = js("""(() => {
    if (typeof navigator === 'undefined' || !navigator.mediaSession) throw new Error('Media Session API unavailable');
    const session = navigator.mediaSession, enabled = {}, unsupported = new Set();
    return (action, active) => {
        if (unsupported.has(action)) return false;
        if (enabled[action] === active) return true;
        try {
            session.setActionHandler(action, active ? (details => callback(action,
                action === 'seekto' ? details.seekTime : (details.seekOffset || 10))) : null);
            enabled[action] = active;
            return true;
        } catch (_) { unsupported.add(action); return false; }
    };
})()""")

internal actual fun publishWebMedia(title: String, artist: String, album: String, artwork: String,
    present: Boolean, playing: Boolean,
    duration: Double, position: Double, speed: Double): Unit = js("""{
    const session = navigator.mediaSession;
    if (session) {
        const old = session.metadata;
        if (!present) { session.metadata = null; session.playbackState = 'none';
            if (session.setPositionState) { try { session.setPositionState(); } catch (_) {} } }
        else {
            if (!old || old.title !== title || old.artist !== artist || old.album !== album ||
                (old.artwork[0]?.src || '') !== artwork) {
                session.metadata = new MediaMetadata({title, artist, album,
                    artwork: artwork ? [{src: artwork}] : []});
            }
            session.playbackState = playing ? 'playing' : 'paused';
            if (duration >= 0 && Number.isFinite(duration) && session.setPositionState) {
                try { session.setPositionState({duration, position: Math.max(0, Math.min(position, duration)),
                    playbackRate: speed > 0 ? speed : 1}); } catch (_) {}
            } else if (session.setPositionState) {
                try { session.setPositionState(); } catch (_) {}
            }
        }
    }
}""")


internal actual fun hasPositionApi(): Boolean = js("typeof navigator.mediaSession.setPositionState === 'function'")
