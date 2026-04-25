package com.hyumn.flick.sensor

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Service to capture active MediaSessions on Wear OS.
 * This resolves the SecurityException when trying to getActiveSessions elsewhere.
 */
class FlickNotificationListener : NotificationListenerService() {

    companion object {
        private var instance: FlickNotificationListener? = null
        
        fun getActiveControllers(context: Context): List<MediaController> {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            Log.d("FlickNotification", "ENABLED LISTENERS: $flat")
            
            val listener = instance
            if (listener == null) {
                val componentName = ComponentName(context, FlickNotificationListener::class.java)
                val isEnabled = flat?.contains(componentName.flattenToString()) == true
                
                if (isEnabled) {
                    Log.w("FlickNotification", "Listener enabled but UNBOUND. Forcing rebind nudge...")
                } else {
                    Log.w("FlickNotification", "Listener is physically DISABLED in Settings.")
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    requestRebind(componentName)
                }
                return emptyList()
            }

            return try {
                val mm = listener.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val sessions = mm.getActiveSessions(ComponentName(listener, FlickNotificationListener::class.java))
                Log.d("FlickNotification", "Found ${sessions.size} active sessions")
                sessions
            } catch (e: Exception) {
                Log.e("FlickNotification", "Error getting sessions: ${e.message}")
                emptyList()
            }
        }
    }

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        Log.d("FlickNotification", "Active sessions updated: ${controllers?.size ?: 0} apps currently exposing media")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d("FlickNotification", "Listener CONNECTED")
        
        try {
            val mm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            mm.addOnActiveSessionsChangedListener(sessionListener, ComponentName(this, FlickNotificationListener::class.java))
            Log.d("FlickNotification", "Media Session Watcher ACTIVE")
        } catch (e: Exception) {
            Log.e("FlickNotification", "Failed to register session watcher: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.w("FlickNotification", "Listener DISCONNECTED")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try {
            val mm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            mm.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (e: Exception) {}
    }
}
