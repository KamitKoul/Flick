package com.hyumn.flick.sensor

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GestureDispatcher(private val context: Context) {

    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val preferredPackages = listOf(
        "com.spotify.music",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music"
    )
    
    private var lastUsedPackage: String? = null

    fun dispatch(label: String) {
        Log.d("GestureDispatcher", "Dispatching action for: $label")
        
        when (label) {
            "single_pinch" -> handleSinglePinch()
            "double_pinch" -> handleDoublePinch()
            "tilt_up" -> handleTiltUp()
            "tilt_down" -> handleTiltDown()
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun handleSinglePinch() {
        Log.d("GestureDispatcher", "Handling Single Pinch")
        
        CoroutineScope(Dispatchers.IO).launch {
            val state = try {
                if (hasPermission(Manifest.permission.READ_PHONE_STATE)) {
                    @Suppress("DEPRECATION")
                    telephonyManager.callState
                } else {
                    Log.w("GestureDispatcher", "Missing READ_PHONE_STATE permission")
                    TelephonyManager.CALL_STATE_IDLE
                }
            } catch (e: SecurityException) {
                Log.e("GestureDispatcher", "SecurityException accessing call state: ${e.message}")
                TelephonyManager.CALL_STATE_IDLE
            }
            
            if (state == TelephonyManager.CALL_STATE_RINGING) {
                Log.d("GestureDispatcher", "Answering call via Single Pinch")
                var success = false
                try {
                    if (hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) {
                        @Suppress("DEPRECATION")
                        telecomManager.acceptRingingCall()
                        success = true 
                    }
                } catch (e: Exception) {
                    Log.e("GestureDispatcher", "Failed to answer via TelecomManager: ${e.message}")
                }

                if (!success) {
                    Log.d("GestureDispatcher", "TelecomManager failed. Falling back to UI Discovery...")
                    success = findAndClickButton(listOf("Answer", "Accept", "Pick up", "RejectCall", "IncomingCallAnswer", "IncomingCallAccept"))
                }

                if (!success) {
                    Log.d("GestureDispatcher", "UI Discovery failed. Skipping fallback to avoid phantom media control.")
                }
            } else {
                toggleMediaPlayback()
            }
        }
    }

    private fun handleDoublePinch() {
        Log.d("GestureDispatcher", "Handling Double Pinch")
        
        CoroutineScope(Dispatchers.IO).launch {
            val state = try {
                if (hasPermission(Manifest.permission.READ_PHONE_STATE)) {
                    @Suppress("DEPRECATION")
                    telephonyManager.callState
                } else {
                    TelephonyManager.CALL_STATE_IDLE
                }
            } catch (e: SecurityException) {
                TelephonyManager.CALL_STATE_IDLE
            }
            
            if (state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK) {
                Log.d("GestureDispatcher", "Ending/Declining call via Double Pinch")
                var success = false
                try {
                    val canEndCall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)
                    } else {
                        false 
                    }

                    if (canEndCall) {
                        @Suppress("DEPRECATION")
                        success = telecomManager.endCall()
                    }
                } catch (e: Exception) {
                    Log.e("GestureDispatcher", "Failed to end call via TelecomManager: ${e.message}")
                }

                if (!success) {
                    Log.d("GestureDispatcher", "TelecomManager failed. Falling back to UI Discovery...")
                    success = findAndClickButton(listOf("End", "Decline", "Hang up", "Reject", "Dismiss", "Ignore", "Decline Call", "HangUp", "RejectCall"))
                }

                if (!success) {
                    Log.d("GestureDispatcher", "UI Discovery failed. Skipping fallback to avoid phantom media control.")
                }
            } else {
                Log.d("GestureDispatcher", "Showing Volume Metric via Double Pinch")
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            }
        }
    }

    private fun handleTiltUp() {
        Log.d("GestureDispatcher", "Emulating Swipe for: QUICK_SETTINGS")
        performSwipe(0f, 300f)
    }

    private fun handleTiltDown() {
        Log.d("GestureDispatcher", "Emulating Swipe for: NOTIFICATIONS")
        performSwipe(350f, 50f)
    }

    private fun performSwipe(fromY: Float, toY: Float) {
        val service = (context as? AccessibilityService) ?: FlickAccessibilityService.getService()
        if (service == null) {
            Log.e("GestureDispatcher", "Cannot perform swipe: Service instance is null")
            return
        }

        val swipePath = Path().apply {
            moveTo(192f, fromY)
            lineTo(192f, toY)
        }

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(swipePath, 0, 500))
        
        service.dispatchGesture(gestureBuilder.build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i("GestureDispatcher", "Swipe Gesture Completed")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w("GestureDispatcher", "Swipe Gesture Cancelled")
            }
        }, null)
    }

    private fun findAndClickButton(keywords: List<String>): Boolean {
        val service = (context as? AccessibilityService) ?: FlickAccessibilityService.getService()
        val rootNode = service?.rootInActiveWindow ?: return false
        
        return try {
            searchAndClick(rootNode, keywords)
        } finally {
            rootNode.recycle()
        }
    }

    private fun searchAndClick(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        // Check current node
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        
        // Diagnostic log: Helps identify what the buttons are called on specific devices
        if (node.isClickable) {
            Log.v("GestureDispatcher", "SEARCH: Found clickable node: text=$text, desc=$contentDesc, id=$viewId")
        }
        
        for (keyword in keywords) {
            if (text.contains(keyword, ignoreCase = true) || 
                contentDesc.contains(keyword, ignoreCase = true) ||
                viewId.contains(keyword, ignoreCase = true)) {
                
                if (node.isClickable) {
                    Log.i("GestureDispatcher", "Clicking call button: ${text.ifEmpty { contentDesc.ifEmpty { viewId } }}")
                    return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
        }
        
        // Check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (searchAndClick(child, keywords)) {
                    child.recycle()
                    return true
                }
                child.recycle()
            }
        }
        return false
    }

    private fun toggleMediaPlayback() {
        val controllers = try {
            mediaSessionManager.getActiveSessions(null)
        } catch (e: SecurityException) {
            FlickNotificationListener.getActiveControllers(context)
        }

        if (controllers.isEmpty()) {
            Log.w("GestureDispatcher", "No active media sessions found. Falling back to legacy AudioManager.")
            sendLegacyMediaCommand()
            return
        }

        val controller = selectBestController(controllers)
        if (controller != null) {
            Log.d("GestureDispatcher", "Controlling app: ${controller.packageName} (State: ${controller.playbackState?.state})")
            val isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            if (isPlaying) {
                controller.transportControls.pause()
            } else {
                controller.transportControls.play()
            }
            lastUsedPackage = controller.packageName
        } else {
            Log.w("GestureDispatcher", "Intelligent selector failed to find a valid controller")
        }
    }

    private fun selectBestController(controllers: List<MediaController>): MediaController? {
        val valid = controllers.filter { it.playbackState != null }
        if (valid.isEmpty()) return controllers.firstOrNull()

        // 1. Priority: Currently playing something
        val playing = valid.find { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (playing != null) return playing

        // 2. Priority: Preferred apps (Spotify, YouTube)
        val preferred = valid.find { preferredPackages.contains(it.packageName) }
        if (preferred != null) return preferred

        // 3. Priority: Last used app
        val lastUsed = valid.find { it.packageName == lastUsedPackage }
        if (lastUsed != null) return lastUsed

        // 4. Fallback: First valid session
        return valid.first()
    }
    private fun sendLegacyMediaCommand() {
        Log.d("GestureDispatcher", "Sending generic Play/Pause via AudioManager fallback")
        val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        audioManager.dispatchMediaKeyEvent(eventDown)
        val eventUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        audioManager.dispatchMediaKeyEvent(eventUp)
    }
}
