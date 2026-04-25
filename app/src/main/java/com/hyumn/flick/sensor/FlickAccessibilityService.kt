package com.hyumn.flick.sensor

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent

import com.hyumn.flick.ml.GestureClassifier
import com.hyumn.flick.ml.TrainingDataCollector
import android.os.SystemClock
import android.os.PowerManager
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.os.VibratorManager
import android.telephony.TelephonyManager
import android.telephony.TelephonyCallback
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import com.hyumn.flick.data.CalibrationManager
import kotlin.math.abs

/**
 * Unified Flick Wear OS Engine.
 *
 * This service consolidates logic from the legacy SensorService directly into the
 * Accessibility Framework. By leveraging Accessibility privileges, we ensure the
 * gesture engine (Sensor Listening + ML Inference) remains active permanently in the
 * background, avoiding the frequent service kills common on Wear OS.
 */
class FlickAccessibilityService : AccessibilityService(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private lateinit var powerManager: PowerManager
    private lateinit var audioManager: AudioManager
    private lateinit var gestureDispatcher: GestureDispatcher
    private lateinit var calibrationManager: CalibrationManager
    private lateinit var vibrator: Vibrator
    private lateinit var telephonyManager: TelephonyManager
    private var wakeLock: PowerManager.WakeLock? = null

    // ML / DSP Pipeline
    private val gestureClassifier by lazy { GestureClassifier(this) } // Layer 2: Neural Engine
    val trainingCollector by lazy { TrainingDataCollector(this) } // Data Collection Mode
    private var sampleCounter = 0
    private val inferenceStride = 40
    private val gestureLabels = arrayOf("flick", "pinch", "tilt_down", "tilt_up")

    private val lastAccValues = FloatArray(3)
    private val lastGyroValues = FloatArray(3)

    private val inferenceMutex = Mutex()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    private val _detectedGesture = MutableStateFlow("idle")
    val detectedGesture = _detectedGesture.asStateFlow()

    private val _isLiveMode = MutableStateFlow(true)
    val isLiveMode = _isLiveMode.asStateFlow()

    private val pinchCount = AtomicInteger(0)
    private var pinchJob: Job? = null
    private var isDialing = false
    private var accumulatedRotation = 0f
    private var lastDispatchTime = 0L
    private val gestureCooldown = 800L 
    private var lastTiltTime = 0L
    private val tiltSuppressionWindow = 500L 
    private val basePinchEnergy = 3.5f // Tunable DSP base energy
    private val gyroStillThreshold = 1.0f // Wrist must NOT be rotating
    private var lastPinchIncrementTime = 0L
    private val pinchIncrementCooldown = 120L // Reduced to allow rapid double pinches 
    private var lastGyroMagnitude = 0f
    
    // Advanced DSP: Probabilistic Classifier
    // Primitive circular buffer to eliminate GC churn at 200Hz
    private val gyroHistorySize = 64
    private val gyroHistoryTimestamps = LongArray(gyroHistorySize)
    private val gyroHistoryMagnitudes = FloatArray(gyroHistorySize)
    private var gyroHistoryIndex = 0
    
    private val accelBuffer = java.util.ArrayList<SensorEventData>()
    data class SensorEventData(
        val timestamp: Long, 
        val x: Float, val y: Float, val z: Float,
        val gx: Float, val gy: Float, val gz: Float
    )
    
    private val gyroVetoWindowNs = 200_000_000L // 200ms gyro history
    private val captureWindowNs = 200_000_000L  // 200ms shape profiling window (v5.0 Alignment)
    
    // Detector State Machine: IDLE -> ARMED -> CAPTURING
    private enum class DetectorState { IDLE, ARMED, CAPTURING }
    private var detectorState = DetectorState.IDLE
    private var captureStartTimeNs = 0L
    private var refractoryEndTimeMs = 0L

    // Pre-stillness tracking: 30ms of continuous quiet before arming
    private val stillnessRequiredMs = 30L
    private var continuousStillSinceMs = 0L
    private val gyroRmsThreshold = 2.5f     // Relaxed for natural movement
    private val accVarianceThreshold = 2.5f // Relaxed for natural movement
    private var isStillBaseline = false
    private var baselineBrokenSinceMs = 0L

    private var noiseFloor = 0.5f 
    private val entryThreshold = 1.0f 
    private var energySpikeStartTimeMs = 0L
    private var initialSpikeEnergy = 0f

    // Pre-load ring buffer: small circular buffer of energies before the spike
    private val preloadRingBuffer = FloatArray(8) // ~30ms at typical 250Hz polling
    private var preloadRingIndex = 0
    
    // Pre-trigger sensor data: full axe samples to prepend to the neural window
    private val preTriggerBuffer = java.util.LinkedList<SensorEventData>()
    private val PRE_TRIGGER_SIZE = 15
    private val POST_TRIGGER_SIZE = 25
    
    // Low-Pass Gravity estimate
    private val gravity = FloatArray(3)
    private val gravityAlpha = 0.9f 
    private var isInNeutralZone = true 

    // Context Filter: tracks what is currently on screen
    // Updated by onAccessibilityEvent — zero runtime cost
    @Volatile private var activePackage: String = ""
    @Volatile private var isCallScreenVisible: Boolean = false
    @Volatile private var isMediaScreenVisible: Boolean = false
    @Volatile private var lastTypingEventMs: Long = 0L  // Updated on every keypress
    
    @Volatile private var isPhoneRinging: Boolean = false
    private var lastRingingTimeMs: Long = 0L
    @Volatile private var isHangingSleep: Boolean = false

    private val callPackages = setOf(
        "com.google.android.dialer",
        "com.android.server.telecom",
        "com.android.phone",
        "com.google.android.apps.wearable.phone"
    )
    private val mediaPackages = setOf(
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.google.android.music",
        "com.amazon.mp3",
        "com.pandora.android",
        "com.soundcloud.android"
    )

    companion object {
        private var instance: FlickAccessibilityService? = null
        fun getService(): FlickAccessibilityService? = instance
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        gestureDispatcher = GestureDispatcher(this)
        calibrationManager = CalibrationManager(this)
        
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        sensorThread = HandlerThread("FlickSensorThread").also { it.start() }
        sensorHandler = Handler(sensorThread!!.looper)

        // Using a short 10s timeout for safety; we now use dynamic pulse wake locks
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Flick::GestureEngine").apply {
            acquire(10000L) 
        }

        startSensorMonitoring()
        setupTelephonyCallback()
        ensureNotificationListener()
        Log.d("FlickAccessibility", "Service Connected & Brain Active (Unified Architecture)")
    }
    
    private fun ensureNotificationListener() {
        if (!isNotificationListenerEnabled()) {
            Log.e("FlickAccessibility", "CRITICAL: Notification Access is DISABLED for Flick.")
            Log.e("FlickAccessibility", "ACTION REQUIRED: Please enable 'Notification Access' for Flick in Watch Settings -> Apps -> Special App Access")
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val componentName = ComponentName(this, FlickNotificationListener::class.java)
            NotificationListenerService.requestRebind(componentName)
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val componentName = "com.hyumn.flick/com.hyumn.flick.sensor.FlickNotificationListener"
        return flat?.contains(componentName) == true
    }

    private fun startSensorMonitoring() {
        val handler = sensorHandler ?: return
        
        // v8.1 BATTERY: Add 200ms hardware batching.
        // This allows the sensor hub to collect data while the main CPU sleeps.
        val sensorDelayUs = 5000 // 5ms = 200Hz
        val maxLatencyUs = 200_000 // 200ms batching (Hardware Hub)

        accelerometer?.also { 
            sensorManager.registerListener(this, it, sensorDelayUs, maxLatencyUs, handler)
        }
        gyroscope?.also { 
            sensorManager.registerListener(this, it, sensorDelayUs, maxLatencyUs, handler)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!_isLiveMode.value) return
        
        // --- 0. Positional Gating (The 'Hanging Sleep') ---
        // Pixel Watch 2 Orientation: Arm hanging down => gravity.y ~ -9.8
        // If the arm is hanging, the watch is likely not being interacted with.
        // We pause the engine to save CPU/Battery.
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val ay = event.values[1]
            if (ay < -8.5f) {
                if (!isHangingSleep) Log.d("FlickAccessibility", "Engine Entering Hanging Sleep (Arm Down)")
                isHangingSleep = true
                return
            } else if (ay > -7.0f && isHangingSleep) {
                Log.d("FlickAccessibility", "Engine Waking Up (Interaction Position)")
                isHangingSleep = false
            }
        }
        if (isHangingSleep) return

        val currentTime = SystemClock.elapsedRealtime()

        // --- 1. Vibration Masking (The 'Ringtone Armor') ---
        // If the phone is ringing, the motor is vibrating at ~50Hz.
        if (isPhoneRinging) {
            lastRingingTimeMs = currentTime
            // We allow processing during ringing, but we will use a higher energy gate below.
        } else if (currentTime - lastRingingTimeMs < 1000L) {
            // Wait 1.0s for the motor mechanics to physically settle
            return 
        }
        
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // DON'T update lastAccValues yet! We need the old values for the HPF delta.
                
                // Tilt Heuristic (Low-Pass Filter for Gravity & Linear Accel Isolation)
                for (i in 0..2) {
                    gravity[i] = gravityAlpha * gravity[i] + (1 - gravityAlpha) * event.values[i]
                }
                
                // Pixel Watch 2 Orientation:
                // Face-up (normal reading): Z ~ +9.8, Y ~ 0
                // Tilted toward face:       Z decreases, Y increases  
                // Arm hanging down:         Y ~ -9.8, Z ~ 0
                
                // STRICT Neutral = watch strictly flat (Z is highly dominant, Y is minimal)
                // This prevents 'wobbling' from resetting the gesture lock
                if (gravity[2] > 6.5f && Math.abs(gravity[1]) < 3.5f) { 
                    if (!isInNeutralZone) {
                        Log.d("FlickAccessibility", "Returned to STRICT Neutral Zone")
                        isInNeutralZone = true
                    }
                }

                if (currentTime - lastDispatchTime > gestureCooldown && isInNeutralZone) {
                    // Tilt UP = Very sharp, deliberate tilt toward face
                    if (gravity[1] > 7.5f) {
                        isInNeutralZone = false
                        processHeuristicGesture("tilt_up")
                    }
                    // Tilt DOWN = Very sharp, deliberate tilt away / dropping arm
                    else if (gravity[1] < -7.5f) {
                        isInNeutralZone = false
                        processHeuristicGesture("tilt_down")
                    }
                }


                // 1. Remove Gravity (Orientation Invariant Linear Acceleration)
                val linX = event.values[0] - gravity[0]
                val linY = event.values[1] - gravity[1]
                val linZ = event.values[2] - gravity[2]
                val currentDynEnergy = linX*linX + linY*linY + linZ*linZ
                
                var maxGyroInWindow = lastGyroMagnitude
                val cutoffTime = event.timestamp - gyroVetoWindowNs
                for (i in 0 until gyroHistorySize) {
                    if (gyroHistoryTimestamps[i] > cutoffTime && gyroHistoryMagnitudes[i] > maxGyroInWindow) {
                        maxGyroInWindow = gyroHistoryMagnitudes[i]
                    }
                }

                // 2. Refractory Period — engine completely dead, disarm if hit
                if (currentTime < refractoryEndTimeMs) {
                    detectorState = DetectorState.IDLE
                    isStillBaseline = false
                    return
                }

                // 3. Pre-stillness gate (updates continuously, never during capture)
                if (detectorState != DetectorState.CAPTURING) {
                    val isCurrentFrameStill = maxGyroInWindow < gyroRmsThreshold && currentDynEnergy < accVarianceThreshold
                    if (isCurrentFrameStill) {
                        if (continuousStillSinceMs == 0L) continuousStillSinceMs = currentTime
                        val stillDuration = currentTime - continuousStillSinceMs
                        isStillBaseline = stillDuration >= stillnessRequiredMs

                        // Gated noise floor + pre-load buffer: only update while confirmed still
                        noiseFloor = 0.95f * noiseFloor + 0.05f * currentDynEnergy
                        preloadRingBuffer[preloadRingIndex % preloadRingBuffer.size] = currentDynEnergy
                        preloadRingIndex++
                    } else {
                        // Any noise breaks the stillness contract
                        continuousStillSinceMs = 0L
                        isStillBaseline = false
                    }
                }

                // Maintain pre-trigger history (latest 15 samples)
                val currentData = SensorEventData(
                    event.timestamp, event.values[0], event.values[1], event.values[2],
                    lastGyroValues[0], lastGyroValues[1], lastGyroValues[2]
                )
                preTriggerBuffer.addLast(currentData)
                if (preTriggerBuffer.size > PRE_TRIGGER_SIZE) {
                    preTriggerBuffer.removeFirst()
                }

                // 4. State machine transitions
                when (detectorState) {
                    DetectorState.IDLE -> {
                        // Can only arm if we have a verified quiet baseline
                        if (isStillBaseline) {
                            detectorState = DetectorState.ARMED
                            baselineBrokenSinceMs = 0L
                            Log.d("FlickAccessibility", "ARMED (Quiet Baseline Met)")
                        }
                    }
                    DetectorState.ARMED -> {
                        // Wait for the energy spike that begins the gesture
                        // v7.4 SMART-CALL: Balanced 7x threshold during ringing.
                        val currentEntryThreshold = if (isPhoneRinging) entryThreshold * 7.0f else entryThreshold
                        
                        if (currentDynEnergy > (noiseFloor * 2.0f) && currentDynEnergy > currentEntryThreshold) {
                            detectorState = DetectorState.CAPTURING
                            captureStartTimeNs = event.timestamp
                            energySpikeStartTimeMs = currentTime
                            initialSpikeEnergy = currentDynEnergy
                            accelBuffer.clear()
                            
                            // v8.1 PULSE WAKE-LOCK: Only hold CPU while deep-analyzing the gesture
                            wakeLock?.acquire(3000L) 
                            Log.v("FlickAccessibility", "Capture started (armed pinch candidate)")
                        } else if (!isStillBaseline) {
                            // Baseline broken. Natural "pre-twitch" happens ~50ms before the actual finger snap shockwave.
                            // Give a 100ms grace period before we disarm back to IDLE.
                            if (baselineBrokenSinceMs == 0L) baselineBrokenSinceMs = currentTime
                            if (currentTime - baselineBrokenSinceMs > 100L) {
                                detectorState = DetectorState.IDLE
                            }
                        } else {
                            baselineBrokenSinceMs = 0L // Reset if we successfully regained baseline
                        }
                    }
                    DetectorState.CAPTURING -> {
                        // v7.4 SMART-DECAY VETO: Distinguish between flat buzzing (Motor) and decaying impact (Finger).
                        if (isPhoneRinging) {
                            val elapsedMs = currentTime - energySpikeStartTimeMs
                            if (elapsedMs > 40L && currentDynEnergy > initialSpikeEnergy * 0.85f) {
                                // Energy is still high and 'flat' after 40ms — definitively a motor buzz.
                                detectorState = DetectorState.IDLE
                                isStillBaseline = false
                                continuousStillSinceMs = 0L
                                refractoryEndTimeMs = currentTime + 500L
                                Log.d("FlickAccessibility", "VIBRATION VETO: Profile too flat for pinch (Motor detected)")
                                return
                            }
                            if (elapsedMs > 120L) {
                                // Even if decaying, if it lasts >120ms it's likely haptic reverberation.
                                detectorState = DetectorState.IDLE
                                isStillBaseline = false
                                continuousStillSinceMs = 0L
                                refractoryEndTimeMs = currentTime + 400L
                                Log.d("FlickAccessibility", "VIBRATION VETO: Spike duration too long (>120ms)")
                                return
                            }
                        }

                        accelBuffer.add(currentData)
                        
                        // Stop after collecting the "tail" (25 samples)
                        // Total window = 15 (pre) + 25 (post) = 40 samples
                        if (accelBuffer.size >= POST_TRIGGER_SIZE) {
                            detectorState = DetectorState.IDLE
                            isStillBaseline = false
                            continuousStillSinceMs = 0L
                            
                            // Combine pre-trigger + post-trigger for neural inference
                            val fullWindow = preTriggerBuffer.toList() + accelBuffer
                            evaluateCaptureWindow(fullWindow, maxGyroInWindow, currentTime)
                        }
                    }
                }

                // Update last known values for external consumers (like TrainingDataCollector)
                System.arraycopy(event.values, 0, lastAccValues, 0, 3)
            } // End TYPE_ACCELEROMETER

            Sensor.TYPE_GYROSCOPE -> {
                System.arraycopy(event.values, 0, lastGyroValues, 0, 3)
                lastGyroMagnitude = kotlin.math.sqrt(event.values[0] * event.values[0] + 
                                                     event.values[1] * event.values[1] + 
                                                     event.values[2] * event.values[2])
                
                // Update Gyro Veto Buffer (Primitive Circular)
                gyroHistoryTimestamps[gyroHistoryIndex] = event.timestamp
                gyroHistoryMagnitudes[gyroHistoryIndex] = lastGyroMagnitude
                gyroHistoryIndex = (gyroHistoryIndex + 1) % gyroHistorySize

                // Feed raw sample to training collector (no-op when not recording)
                if (trainingCollector.isCollecting) {
                    trainingCollector.onSensorSample(
                        ax = lastAccValues[0], ay = lastAccValues[1], az = lastAccValues[2],
                        gx = event.values[0], gy = event.values[1], gz = event.values[2]
                    )
                }

                if (isDialing) {
                    handleDialing(event.values[1])
                }
            }
        }
    }


    // =============================================================
    // LAYER 2: Gesture Classifier (DSP heuristic / TFLite-ready)
    // =============================================================
    private fun evaluateCaptureWindow(window: List<SensorEventData>, maxGyro: Float, currentTime: Long) {
        if (window.size < 4) {
            refractoryEndTimeMs = currentTime + 300L
            return
        }
        val n = window.size

        // --- Compute per-sample magnitudes ---
        val magnitudes = FloatArray(n) { i ->
            val s = window[i]
            Math.sqrt((s.x*s.x + s.y*s.y + s.z*s.z).toDouble()).toFloat()
        }

        // --- Locate peak ---
        var peakIdx = 0; var peakMag = 0f
        for (i in 0 until n) { if (magnitudes[i] > peakMag) { peakMag = magnitudes[i]; peakIdx = i } }

        // --- Pre-load ---
        val preloadAvg = preloadRingBuffer.average().toFloat()
        val hasPreload = preloadAvg > (noiseFloor * 1.2f)

        // --- Peak count (1–2 OK, 3+ = reverberation) ---
        var peakCount = 0; val peakThreshold = peakMag * 0.45f; var inPeak = false
        for (mag in magnitudes) {
            if (mag > peakThreshold) { if (!inPeak) { peakCount++; inPeak = true } }
            else { inPeak = false }
        }

        // --- Rise / Decay fractions ---
        val rise  = peakIdx.toFloat() / n.toFloat()
        val decay = (n - 1 - peakIdx).toFloat() / n.toFloat()

        // --- Bandpassed derivative features ---
        var zcrCount = 0; var totalCovariance = 0f; var sumBpEnergy = 0f
        val bpX = FloatArray(n); val bpY = FloatArray(n); val bpZ = FloatArray(n)
        for (i in 1 until n) {
            val curr = window[i]; val prev = window[i-1]
            val dtMs = ((curr.timestamp - prev.timestamp) / 1_000_000f).coerceAtLeast(1f)
            bpX[i] = (curr.x - prev.x) / dtMs
            bpY[i] = (curr.y - prev.y) / dtMs
            bpZ[i] = (curr.z - prev.z) / dtMs
            if (Math.signum(bpX[i]) != Math.signum(bpX[i-1])) zcrCount++
            if (Math.signum(bpY[i]) != Math.signum(bpY[i-1])) zcrCount++
            if (Math.signum(bpZ[i]) != Math.signum(bpZ[i-1])) zcrCount++
            sumBpEnergy += Math.sqrt((bpX[i]*bpX[i] + bpY[i]*bpY[i] + bpZ[i]*bpZ[i]).toDouble()).toFloat()
            totalCovariance += Math.abs(bpX[i]*bpY[i]) + Math.abs(bpY[i]*bpZ[i]) + Math.abs(bpX[i]*bpZ[i])
        }

        // --- Layer 2: Neural Inference (TFLite) ---
        val probs = gestureClassifier.classifyNeural(window, isPhoneRinging)

        Log.i("FlickAccessibility",
            "NEURAL_CLASSIFY: pinch=%.2f typing=%.2f rot=%.2f | BufSize=${window.size}"
                .format(probs.pinch, probs.typing, probs.rotation))


        if (probs.isPinchDominant) {
            dispatchValidatedPinch()
        } else {
            Log.d("FlickAccessibility", "NEURAL: Ignore (Confidence too low)")
        }

        // Refractory: 600ms blocks haptic echo. 
        // We need a longer 'deaf' period to allow the watch body to stop vibrating.
        refractoryEndTimeMs = currentTime + 600L
    }

    private fun processHeuristicGesture(gesture: String) {
        val currentTime = SystemClock.elapsedRealtime()
        
        // --- Heuristic Veto ---
        // Any sharp tilt or arm rotation kills the neural capture candidate.
        detectorState = DetectorState.IDLE
        refractoryEndTimeMs = currentTime + 800L // 'Deaf' period to allow movement to settle
        
        if (currentTime - lastDispatchTime > gestureCooldown) {
            lastDispatchTime = currentTime
            if (gesture == "tilt_up") lastTiltTime = currentTime
            
            triggerHapticFeedback()
            _detectedGesture.value = gesture
            gestureDispatcher.dispatch(gesture)
        }
    }

    private fun handlePinchEvent() {
        pinchCount.incrementAndGet()
        pinchJob?.cancel()
        pinchJob = serviceScope.launch {
            delay(400) // Detection window for multi-tap
            val finalCount = pinchCount.get()
            pinchCount.set(0) // Reset immediately
            
            when {
                finalCount == 1 -> {
                    Log.d("FlickAccessibility", "DISPATCH: Single Pinch")
                    gestureDispatcher.dispatch("single_pinch")
                    triggerHapticFeedback(1)
                }
                finalCount == 2 -> {
                    Log.d("FlickAccessibility", "DISPATCH: Double Pinch")
                    gestureDispatcher.dispatch("double_pinch")
                    triggerHapticFeedback(2)
                }
                finalCount >= 3 -> {
                    Log.d("FlickAccessibility", "MODE: Entering Dialing")
                    enteringDialMode()
                }
            }
        }
    }

    private fun enteringDialMode() {
        isDialing = true
        accumulatedRotation = 0f
        // Subtle double-click to indicate entry
        triggerHapticFeedback(2) 
        serviceScope.launch {
            delay(1500)
            if (isDialing) exitDialMode()
        }
    }

    private fun handleDialing(gyroY: Float) {
        val deltaDeg = Math.toDegrees(gyroY.toDouble()).toFloat() * 0.02f * calibrationManager.dialSpeed
        accumulatedRotation += deltaDeg
        
        if (Math.abs(accumulatedRotation) >= calibrationManager.rotationThreshold) {
            val direction = if (accumulatedRotation > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            
            // Incremental tick for every volume step
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(20)
            }
            
            accumulatedRotation = 0f
        }
    }

    private fun exitDialMode() {
        isDialing = false
        pinchCount.set(0)
    }

    private fun triggerHapticFeedback(repeats: Int = 1) {
        serviceScope.launch {
            repeat(repeats) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(30)
                }
                delay(150)
            }
        }
    }

    fun setLiveMode(active: Boolean) {
        _isLiveMode.value = active
        if (active) triggerHapticFeedback(1)
        Log.d("FlickAccessibility", "Live Mode: $active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            // Track keypresses cheaply: just update a timestamp, no string ops
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                lastTypingEventMs = SystemClock.elapsedRealtime()
                return
            }
            // Only do full package analysis on actual screen switches
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (pkg == activePackage) return // Same screen, nothing to update

                activePackage = pkg
                isCallScreenVisible = callPackages.any { pkg.contains(it) } ||
                    event.className?.toString()?.contains("InCall", ignoreCase = true) == true ||
                    event.className?.toString()?.contains("Incoming", ignoreCase = true) == true
                isMediaScreenVisible = mediaPackages.any { pkg.contains(it) }

                Log.v("FlickAccessibility", "Context updated: pkg=$pkg call=$isCallScreenVisible media=$isMediaScreenVisible")
            }
            else -> return // Drop everything else immediately
        }
    }
    override fun onInterrupt() {
        // Cleanup requested by system
    }

    override fun onDestroy() {
        instance = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { 
                telephonyManager.unregisterTelephonyCallback(it)
            }
        }
        sensorManager.unregisterListener(this)
        sensorThread?.quitSafely()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        super.onDestroy()
    }

    private var telephonyCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                isPhoneRinging = (state == TelephonyManager.CALL_STATE_RINGING)
                if (isPhoneRinging) {
                    Log.d("FlickAccessibility", "Incoming Call Detected - Sensor Masking ACTIVE")
                }
            }
        }
    } else null

    private fun setupTelephonyCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { 
                telephonyManager.registerTelephonyCallback(mainExecutor, it)
            }
        }
    }

    private fun dispatchValidatedPinch() {
        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - lastTiltTime <= tiltSuppressionWindow) return
        if (currentTime - lastPinchIncrementTime <= pinchIncrementCooldown) return
        if (currentTime - lastPinchIncrementTime <= 80L) return

        // --- CONTEXT FILTER ---
        var contextScore = 0
        if (isCallScreenVisible)      contextScore += 4  // Call is ringing — highest priority
        if (isMediaScreenVisible)     contextScore += 3  // Media app is open
        if (lastGyroMagnitude < 0.5f) contextScore += 2  // Wrist is very still
        if (gravity[2] > 5.5f)        contextScore += 1  // Watch face-up (natural position)

        // Anti-typing gate: if user typed anything in the last 1.5s, suppress all pinches.
        // Exception: call screen always overrides (you may need to answer while typing a message).
        val recentlyTyping = (currentTime - lastTypingEventMs) < 1500L
        if (recentlyTyping && !isCallScreenVisible) {
            Log.d("FlickAccessibility", "Pinch suppressed: recent typing detected (${currentTime - lastTypingEventMs}ms ago)")
            return
        }

        /* Temporarily disabled Context Filter for debugging
        if (contextScore < 3) {
            Log.d("FlickAccessibility", "Pinch suppressed by Context Filter (score=$contextScore, pkg=$activePackage)")
            return
        }
        */

        Log.d("FlickAccessibility", "Pinch dispatched (contextScore=$contextScore, typing=$recentlyTyping)")
        lastPinchIncrementTime = currentTime
        handlePinchEvent()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
