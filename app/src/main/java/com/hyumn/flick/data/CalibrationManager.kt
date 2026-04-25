package com.hyumn.flick.data

import android.content.Context
import android.content.SharedPreferences

class CalibrationManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("flick_calib", Context.MODE_PRIVATE)

    var pinchSensitivity: Float
        get() = prefs.getFloat("pinch_sensitivity", 1.0f)
        set(value) = prefs.edit().putFloat("pinch_sensitivity", value).apply()

    var dialSpeed: Float
        get() = prefs.getFloat("dial_speed", 1.0f)
        set(value) = prefs.edit().putFloat("dial_speed", value).apply()

    var rotationThreshold: Float
        get() = prefs.getFloat("rotation_threshold", 15.0f)
        set(value) = prefs.edit().putFloat("rotation_threshold", value).apply()
}
