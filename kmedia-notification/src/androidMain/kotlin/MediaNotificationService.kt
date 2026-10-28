@file:Suppress("UnstableApiUsage")
package dev.brahmkshatriya.kmedianotification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaSession as AndroidSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Subclass in the app manifest. Resolve the process/service-owned binding, never an Activity. */
public abstract class MediaNotificationService : MediaSessionService() {
    protected abstract fun provideMediaSession(): MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var native: AndroidSession? = null
    private var binding: MediaSession? = null
    private var attached = false
    private var nativeLease: AndroidMediaControls? = null
    /** False if an older service instance is being destroyed after a newer activation. */
    protected val ownsMediaSession: Boolean get() = binding?.ownsNativeAccess(nativeLease) == true
    override fun onCreate() {
        super.onCreate()
        val session = provideMediaSession()
        binding = session
        // A backend-created host must not turn an activation failure into a retry loop.
        if (!session.activationRequested) session.activate()
        scope.launch {
            session.status.collect { status ->
                if (status is SessionStatus.Active) {
                    session.withAndroidMediaControls { controls ->
                        if (native === controls.session) return@withAndroidMediaControls
                        detachNativeSession()
                        val config = controls.notificationConfig
                        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
                            NotificationChannel(config.channelId, config.channelName, NotificationManager.IMPORTANCE_LOW))
                        val provider = AndroidNotificationProvider(this@MediaNotificationService, controls.config)
                        provider.setSmallIcon(config.smallIcon)
                        setMediaNotificationProvider(provider)
                        attached = true
                        nativeLease = controls
                        native = controls.session
                        addSession(controls.session)
                    }
                } else if ((attached && status is SessionStatus.Inactive) || status is SessionStatus.Closed || status is SessionStatus.Unavailable) {
                    detachNativeSession()
                    if (!session.activationRequested || status is SessionStatus.Unavailable || status is SessionStatus.Closed) stopSelf()
                }
            }
        }
    }
    final override fun onGetSession(controllerInfo: AndroidSession.ControllerInfo): AndroidSession? = native
    private fun detachNativeSession() {
        val previous = native
        native = null
        nativeLease = null
        // Media3 removes a released session itself. A stale lease must also never
        // remove a newer session that happens to have the same ID.
        if (previous != null && sessions.any { it === previous }) removeSession(previous)
    }
    override fun onDestroy() {
        val owned = ownsMediaSession
        detachNativeSession()
        if (owned) binding?.deactivate()
        scope.cancel()
        super.onDestroy()
    }
}
