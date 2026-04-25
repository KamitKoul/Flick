package com.hyumn.flick.ml

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.util.ArrayDeque
import kotlin.math.sqrt

/**
 * Training Data Collector (Hardened v2.0)
 *
 * Implements Peak-Alignment for transient gestures (Pinch, Rotation) 
 * and continuous sampling for others. Increases window to 40 samples (160ms).
 */
class TrainingDataCollector(private val context: Context) {

    enum class GestureLabel(val id: Int, val display: String) {
        PINCH(0, "Pinch"),
        TYPING(1, "Typing"),
        ROTATION(2, "Wrist Rotation"),
        IDLE(3, "Idle/Random")
    }

    private val outputFile: File by lazy {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        File(dir, "flick_training_data_v2.csv")
    }

    @Volatile var isCollecting = false
    @Volatile var currentLabel: GestureLabel = GestureLabel.PINCH
    @Volatile var samplesCollected = 0
        private set

    private val scope = CoroutineScope(Dispatchers.IO)

    data class SensorSample(
        val ax: Float, val ay: Float, val az: Float,
        val gx: Float, val gy: Float, val gz: Float
    )

    // Configuration
    private val WINDOW_SIZE = 40
    private val SAMPLES_AFTER_PEAK = 25 // Center peak at ~index 15
    private val TRIGGER_THRESHOLD = 10.0f // Lowered (v5.4) to catch more pinches

    private val slidingBuffer = ArrayDeque<SensorSample>(WINDOW_SIZE)
    private var isRecordingEvent = false
    private var postPeakCount = 0

    fun startSession(label: GestureLabel) {
        currentLabel = label
        isCollecting = true
        slidingBuffer.clear()
        isRecordingEvent = false
        postPeakCount = 0
        Log.d("TrainingCollector", "Started HARDENED collection: ${label.display}")
    }

    fun stopSession() {
        isCollecting = false
        slidingBuffer.clear()
    }

    fun onSensorSample(
        ax: Float, ay: Float, az: Float,
        gx: Float, gy: Float, gz: Float
    ) {
        if (!isCollecting) return

        val sample = SensorSample(ax, ay, az, gx, gy, gz)
        
        // Maintain sliding window
        slidingBuffer.addLast(sample)
        if (slidingBuffer.size > WINDOW_SIZE) {
            slidingBuffer.removeFirst()
        }

        when (currentLabel) {
            GestureLabel.PINCH, GestureLabel.ROTATION -> {
                if (!isRecordingEvent) {
                    // Look for the "snap" trigger
                    val magnitude = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()
                    if (magnitude > TRIGGER_THRESHOLD) {
                        isRecordingEvent = true
                        postPeakCount = 0
                        Log.d("TrainingCollector", "Trigger detected for ${currentLabel.display}")
                    }
                } else {
                    // We are capturing the "tail" of the event
                    postPeakCount++
                    if (postPeakCount >= SAMPLES_AFTER_PEAK) {
                        if (slidingBuffer.size == WINDOW_SIZE) {
                            val snapshot = slidingBuffer.toList()
                            scope.launch { writeRow(currentLabel, snapshot) }
                        }
                        isRecordingEvent = false
                        postPeakCount = 0
                    }
                }
            }
            GestureLabel.TYPING, GestureLabel.IDLE -> {
                // For continuous data, just grab chunks
                if (slidingBuffer.size == WINDOW_SIZE) {
                    val snapshot = slidingBuffer.toList()
                    slidingBuffer.clear() // Clear to avoid overlapping windows for idle
                    scope.launch { writeRow(currentLabel, snapshot) }
                }
            }
        }
    }

    private fun writeRow(label: GestureLabel, window: List<SensorSample>) {
        try {
            val writer = FileWriter(outputFile, true)
            val sb = StringBuilder()
            sb.append(label.id)
            for (s in window) {
                sb.append(",${s.ax},${s.ay},${s.az},${s.gx},${s.gy},${s.gz}")
            }
            sb.append("\n")
            writer.write(sb.toString())
            writer.flush()
            writer.close()
            samplesCollected++
        } catch (e: Exception) {
            Log.e("TrainingCollector", "Failed to write CSV: ${e.message}")
        }
    }

    fun getOutputPath(): String = outputFile.absolutePath

    fun clearData() {
        if (outputFile.exists()) outputFile.delete()
        samplesCollected = 0
    }
}
