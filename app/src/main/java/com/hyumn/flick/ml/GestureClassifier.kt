package com.hyumn.flick.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * LAYER 2: Neural Gesture Classifier (v6.0 - Gravity-Blind Strategy)
 *
 * This version uses "Sample-Wise Normalization" to erase the gravity vector
 * from every gesture window. This makes the model orientation-blind and 
 * resolves the issue where gestures were misidentified as 'Typing' based 
 * on the watch's tilt.
 */
class GestureClassifier(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var isInitializing = false

    // Pre-allocated buffers to avoid GC pressure
    private val inputBuffer = Array(1) { Array(40) { FloatArray(6) } }
    private val outputBuffer = Array(1) { FloatArray(4) }

    data class GestureProbabilities(
        val pinch: Float,
        val typing: Float,
        val rotation: Float,
        val idle: Float,
        val isRinging: Boolean = false
    ) {
        // Gates (v7.2 Smart-Call Tuning)
        val isPinchDominant: Boolean get() {
            // During a call, we require a massive 0.80 confidence to overcome ringtone vibration.
            val threshold = if (isRinging) 0.80f else 0.45f
            return pinch > threshold && pinch > typing && pinch > (rotation * 0.7f)
        }
    }

    init {
        initEngine()
    }

    private fun initEngine() {
        if (isInitializing) return
        isInitializing = true
        
        Log.i("GestureClassifier", "Initializing Gravity-Blind Engine (v6.0)...")
        
        try {
            val modelBuffer = loadModelFile()
            val options = Interpreter.Options()
                .setNumThreads(2)
                .setUseNNAPI(false) // NNAPI disabled: WearOS hardware stalls during delegate compilation
            
            interpreter = Interpreter(modelBuffer, options)
            Log.i("GestureClassifier", "Neural Engine Ready (Bundled Runtime)")
        } catch (e: Exception) {
            Log.e("GestureClassifier", "Failed to create Bundled Interpreter: ${e.message}")
        } finally {
            isInitializing = false
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd("gesture_model.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    fun classifyNeural(rawBuffer: List<com.hyumn.flick.sensor.FlickAccessibilityService.SensorEventData>, isRinging: Boolean): GestureProbabilities {
        val interp = interpreter ?: run {
            if (!isInitializing) initEngine()
            return fallbackProbabilities()
        }

        // 1. Prepare Window (Zero-padded or Trimmed to 40)
        val n = Math.min(rawBuffer.size, 40)
        
        // 2. Compute Window-Wise Zero-Mean (Gravity Eraser)
        val means = FloatArray(6)
        for (axis in 0 until 6) {
            var sum = 0f
            for (i in 0 until n) {
                val sample = rawBuffer[i]
                sum += when(axis) {
                    0 -> sample.x; 1 -> sample.y; 2 -> sample.z
                    3 -> sample.gx; 4 -> sample.gy; else -> sample.gz
                }
            }
            means[axis] = sum / n
        }

        // 3. Apply 5Hz Digital High-Pass Filter (Isolate Snap from Slosh)
        // Reset state for every window to match training zero-init strategy
        val alpha = 0.864f
        val filtered = Array(40) { FloatArray(6) }
        val prevX = FloatArray(6) 
        
        for (i in 0 until n) {
            val s = rawBuffer[i]
            val xRaw = floatArrayOf(s.x - means[0], s.y - means[1], s.z - means[2], 
                                    s.gx - means[3], s.gy - means[4], s.gz - means[5])
            
            if (i == 0) {
                // Initial sample: Filter state is 0, but we store the raw value for next step
                for (a in 0..5) {
                    filtered[i][a] = 0f
                    prevX[a] = xRaw[a]
                }
            } else {
                for (a in 0..5) {
                    filtered[i][a] = alpha * (filtered[i-1][a] + xRaw[a] - prevX[a])
                    prevX[a] = xRaw[a]
                }
            }
        }

        // 4. Fill Transformed Input Buffer (Scale PRESERVED)
        for (i in 0 until 40) {
            for (axis in 0..5) {
                inputBuffer[0][i][axis] = filtered[i][axis]
            }
        }

        // 4. Run Inference on Shared Buffers
        interp.run(inputBuffer, outputBuffer)

        val probs = outputBuffer[0]
        return GestureProbabilities(pinch = probs[0], typing = probs[1], rotation = probs[2], idle = probs[3], isRinging = isRinging)
    }

    private fun fallbackProbabilities() = GestureProbabilities(0f, 0f, 0f, 1f)
}
