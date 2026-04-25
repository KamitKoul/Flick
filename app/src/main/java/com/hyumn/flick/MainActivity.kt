package com.hyumn.flick

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import com.hyumn.flick.data.CalibrationManager
import com.hyumn.flick.ml.TrainingDataCollector
import com.hyumn.flick.sensor.FlickAccessibilityService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var calibrationManager: CalibrationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        calibrationManager = CalibrationManager(this)

        setContent {
            val context = LocalContext.current
            val permissions = arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.BODY_SENSORS
            )
            
            var hasPermissions by remember { 
                mutableStateOf(permissions.all { 
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED 
                }) 
            }

            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { results ->
                hasPermissions = results.values.all { it }
            }

            LaunchedEffect(Unit) {
                if (!hasPermissions) {
                    launcher.launch(permissions)
                }
            }

            val service = remember { mutableStateOf(FlickAccessibilityService.getService()) }
            
            LaunchedEffect(Unit) {
                while (service.value == null) {
                    service.value = FlickAccessibilityService.getService()
                    delay(500)
                }
            }

            val detectedGesture by if (service.value != null) {
                service.value!!.detectedGesture.collectAsState()
            } else {
                remember { mutableStateOf("idle") }
            }
            
            val isLiveMode by if (service.value != null) {
                service.value!!.isLiveMode.collectAsState()
            } else {
                remember { mutableStateOf(false) }
            }

            val isNotificationAccessGranted = remember { 
                mutableStateOf(isNotificationListenerEnabled(context)) 
            }

            LaunchedEffect(Unit) {
                while (true) {
                    isNotificationAccessGranted.value = isNotificationListenerEnabled(context)
                    delay(2000)
                }
            }

            PremiumFlickApp(
                hasPermissions = hasPermissions,
                isServiceActive = service.value != null,
                isMediaAccessGranted = isNotificationAccessGranted.value,
                detectedGesture = detectedGesture,
                isLiveMode = isLiveMode,
                onToggleLiveMode = { service.value?.setLiveMode(it) },
                calibrationManager = calibrationManager,
                service = service.value,
                onOpenSettings = { openAccessibilitySettings() },
                onOpenNotificationSettings = { openNotificationAccessSettings() },
                onRequestPermissions = { launcher.launch(permissions) }
            )
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (e: Exception) {
            // Fallback to general settings if the specific listener screen is blocked/missing
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                // Last ditch effort
            }
        }
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val packageName = context.packageName
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return flat?.contains(packageName) == true
    }
}

@Composable
fun PremiumFlickApp(
    hasPermissions: Boolean,
    isServiceActive: Boolean,
    isMediaAccessGranted: Boolean,
    detectedGesture: String,
    isLiveMode: Boolean,
    onToggleLiveMode: (Boolean) -> Unit,
    calibrationManager: CalibrationManager,
    service: FlickAccessibilityService?,
    onOpenSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    val scrollState = androidx.compose.foundation.rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(scrollState)
            .padding(horizontal = 10.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GestureStatusHeader(detectedGesture, isLiveMode && isServiceActive)

        if (!hasPermissions || !isServiceActive) {
            StatusAlert(
                "SETUP REQUIRED",
                if (!hasPermissions) "Permissions needed" else "Accessibility is OFF",
                { if (!hasPermissions) onRequestPermissions() else onOpenSettings() }
            )
        }

        if (!isMediaAccessGranted) {
            StatusAlert(
                "MEDIA REPAIR REQUIRED",
                "Tap to enable Notification Access",
                onOpenNotificationSettings
            )
            Text(
                "Tip: Check the Pixel Watch app on your phone",
                fontSize = 8.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
        }

        ControlSection(isLiveMode, isServiceActive, onToggleLiveMode)

        CalibrationSection(calibrationManager)

        TrainingModeCard(service)

        Text(
            "v1.2.0 • Ultra-Light",
            fontSize = 8.sp,
            color = Color.DarkGray,
            modifier = Modifier.padding(top = 20.dp)
        )
    }
}

@Composable
fun GestureStatusHeader(gesture: String, isActive: Boolean) {
    val color = when (gesture) {
        "idle" -> if (isActive) Color(0xFF00E5FF) else Color.Gray
        "pinch", "single_pinch" -> Color(0xFF00FF88)
        "double_pinch" -> Color.Yellow
        else -> Color.Red
    }

    Text(
        text = gesture.uppercase(),
        fontWeight = FontWeight.Bold,
        color = color,
        fontSize = 20.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(bottom = 15.dp)
    )
}

@Composable
fun StatusAlert(title: String, msg: String, action: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF330000))
            .clickable(onClick = action)
            .padding(8.dp)
    ) {
        Text(title, color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(msg, color = Color.White, fontSize = 9.sp)
    }
}

@Composable
fun ControlSection(isLiveMode: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .background(if (isLiveMode) Color(0xFF002200) else Color(0xFF111111))
            .clickable(enabled = enabled) { onToggle(!isLiveMode) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("GESTURE ENGINE", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
            Text(if (isLiveMode) "RUNNING" else "STOPPED", fontSize = 10.sp, color = if (isLiveMode) Color.Green else Color.Gray)
        }
        androidx.compose.material.Checkbox(checked = isLiveMode, onCheckedChange = null)
    }
}

@Composable
fun CalibrationSection(calibrationManager: CalibrationManager) {
    var pinchValue by remember { mutableFloatStateOf(calibrationManager.pinchSensitivity) }
    var dialValue by remember { mutableFloatStateOf(calibrationManager.dialSpeed) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("CALIBRATION", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        
        Spacer(modifier = Modifier.height(8.dp))
        
        SensitivitySlider(
            label = "Sensitivity",
            value = pinchValue,
            icon = Icons.Default.Tune,
            onValueChange = { 
                pinchValue = it
                calibrationManager.pinchSensitivity = it
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        SensitivitySlider(
            label = "Dial Speed",
            value = dialValue,
            icon = Icons.Default.Speed,
            onValueChange = { 
                dialValue = it
                calibrationManager.dialSpeed = it
            }
        )
    }
}

@Composable
fun SensitivitySlider(label: String, value: Float, icon: androidx.compose.ui.graphics.vector.ImageVector, onValueChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.LightGray)
            Spacer(modifier = Modifier.width(4.dp))
            Text(label, fontSize = 10.sp, color = Color.LightGray)
            Spacer(modifier = Modifier.weight(1f))
            Text(String.format("%.1fx", value), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF))
        }
        InlineSlider(
            value = value,
            onValueChange = onValueChange,
            steps = 5,
            valueRange = 0.5f..2.0f,
            decreaseIcon = { Icon(Icons.Default.Remove, null) },
            increaseIcon = { Icon(Icons.Default.Add, null) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// =============================================================
// TRAINING MODE UI
// =============================================================
@Composable
fun TrainingModeCard(service: FlickAccessibilityService?) {
    val collector = service?.trainingCollector
    val labels = TrainingDataCollector.GestureLabel.entries.toList()

    var selectedLabel by remember { mutableStateOf(TrainingDataCollector.GestureLabel.PINCH) }
    var isRecording by remember { mutableStateOf(false) }
    var sampleCount by remember { mutableIntStateOf(0) }

    // Poll sample count while recording
    LaunchedEffect(isRecording) {
        while (isRecording) {
            sampleCount = collector?.samplesCollected ?: 0
            delay(200)
        }
    }

    val accentColor = when (selectedLabel) {
        TrainingDataCollector.GestureLabel.PINCH    -> Color(0xFF00FF88)
        TrainingDataCollector.GestureLabel.TYPING   -> Color(0xFFFFCC00)
        TrainingDataCollector.GestureLabel.ROTATION -> Color(0xFFFF6B35)
        TrainingDataCollector.GestureLabel.IDLE     -> Color(0xFF00E5FF)
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "TRAINING MODE",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = accentColor,
            letterSpacing = 1.sp
        )

            Spacer(Modifier.height(8.dp))

            // Gesture type selector
            Text("Gesture Type", fontSize = 9.sp, color = Color.Gray)
            Spacer(Modifier.height(4.dp))
            labels.forEach { label ->
                val isSelected = selectedLabel == label
                Chip(
                    onClick = { if (!isRecording) selectedLabel = label },
                    enabled = !isRecording,
                    label = { Text(label.display, fontSize = 10.sp) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    colors = ChipDefaults.chipColors(
                        backgroundColor = if (isSelected) accentColor.copy(alpha = 0.25f) else Color(0xFF1A1A1A),
                        contentColor = if (isSelected) accentColor else Color.Gray
                    )
                )
            }

            Spacer(Modifier.height(8.dp))

            // Sample counter
            if (isRecording) {
                Text(
                    "$sampleCount windows recorded",
                    fontSize = 10.sp,
                    color = accentColor,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
            }

            // Record / Stop button
            Button(
                onClick = {
                    if (collector == null) return@Button
                    if (!isRecording) {
                        collector.startSession(selectedLabel)
                        isRecording = true
                    } else {
                        collector.stopSession()
                        isRecording = false
                        sampleCount = collector.samplesCollected
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (isRecording) Color(0xFFFF3366) else accentColor
                ),
                enabled = collector != null
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = Color.Black
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (isRecording) "STOP" else "RECORD",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }

            if (collector != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "CSV: /Download/flick_training_data.csv",
                    fontSize = 8.sp,
                    color = Color(0xFF444444),
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    "Enable Accessibility Service first",
                    fontSize = 9.sp,
                    color = Color.DarkGray,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

