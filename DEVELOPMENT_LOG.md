# Flick Wear OS: Development & Architecture Log

This document serves as a comprehensive record of the engineering challenges, system restrictions, and architectural solutions implemented while stabilizing the Flick Gesture Engine for Wear OS 4 (specifically the Pixel Watch 2).

## 1. The "Davey" Frame Skipping & CPU Bottleneck
- **The Challenge**: The system's `Choreographer` reported massive frame skipping (over 2800ms per frame), resulting in "Davey!" logs. This completely froze the watch UI and caused gesture dispatches to time out.
- **The Root Cause**: The auto-generated Machine Learning model (`GestureClassifier.java`) was over 100,000 lines long. Evaluating this massive Decision Tree at 100Hz (Sensor's `FASTEST` delay) completely saturated the wearable CPU. 
- **The Solution**: We abandoned the heavy ML architecture in favor of a "Zero-Lag" Physics Heuristic Engine. By moving to raw IMU vector analysis (Gravity filtering and Acceleration amplitude), we reduced memory bloat by 6MB, completely restored 60fps responsiveness, and eliminated all frame-skipping logs.

## 2. 'Invisible' Global Actions (Pixel Watch 2)
- **The Challenge**: Dispatching `GLOBAL_ACTION_NOTIFICATIONS` via the Accessibility Service returned `true` (success) in the logs, but the system UI shades refused to actually open on the Pixel Watch 2 screen.
- **The Root Cause**: Wear OS 4 implements heavy manufacturer-level restrictions on system UI overlays to prevent 3rd-party accessibility services from hijacking the screen layout. 
- **The Solution (Ghost Shade Recovery)**: We pivoted from system commands to physical path emulation. We implemented a `performSwipe` method utilizing `GestureDescription.Builder` to programmatically drag a "ghost finger" from the top or bottom of the screen. This bypasses the OS restrictions by tricking the WindowManager into thinking a physical finger pulled the shade open.

## 3. High-Frequency Pinch Failure (Delta Math Flaw)
- **The Challenge**: The Pinch and Double Pinch gestures completely stopped registering, even when snapping fingers forcefully.
- **The Root Cause**: The pinch detector was originally calculating the frame-over-frame derivative (`deltaMag = currentMag - lastAccMagnitude`). Because `SENSOR_DELAY_FASTEST` outputs frames roughly every 10 milliseconds, the acceleration change between two consecutive frames was tiny, never reaching the `35f` threshold.
- **The Solution**: We changed the physics logic from a "derivative delta" to an "absolute amplitude." The engine now compares the raw magnitude against standard resting gravity (`abs(currentMag - 9.81f)`). A threshold of `12f` (translating to a ~21m/s² accelerometer reading) now perfectly and reliably detects the transient shockwave of a physical finger snap.

## 4. The "Pendulum" / Rapid-Fire Cross-Triggering
- **The Challenge**: When performing a "Tilt Up" and relaxing the arm, the watch would "rapid-fire" and accidentally trigger a "Tilt Down." 
- **The Root Cause**: The 'Neutral' dead zone was too wide and too easily satisfied. When the user swung their arm back to rest, the gravity vector would overshoot the center, passing the threshold of the opposite gesture.
- **The Solution (Strict Hysteresis)**: 
    1. **Steeper Thresholds**: We widened the dead band by increasing the required tilt from `6.0f` to `7.5f` (requiring a deep 50-degree physical tilt).
    2. **One-Shot Lock**: We implemented a strict binary state lock (`isInNeutralZone`). Gestures act as a "one-shot" and require the user to bring the watch back to a highly restrictive "Strict Flat" position (`Z > 6.5`, `Y < 3.5`) before another gesture is allowed to fire.

## 5. Security exceptions on Media Controls
- **The Challenge**: Attempting to Play/Pause media threw a fatal `SecurityException: Missing permission to control media`.
- **The Root Cause**: Without explicit Notification Listener permissions or a bound active session, Android tightly restricts global media key injections.
- **The Solution**: We implemented a triple-fallback system in `sendMediaCommand()`:
    1. Query active `MediaSessionManager` controllers natively.
    2. Fallback to `AudioManager.dispatchMediaKeyEvent()`.
    3. **Ultimate Fallback**: Broadcast a system-wide `Intent.ACTION_MEDIA_BUTTON` intent to forcefully toggle the media receiver without requiring session ownership.

## 6. The "Hidden" Volume Metric UI
- **The Challenge**: Assigning `AudioManager.ADJUST_SAME` along with `FLAG_SHOW_UI` to a Double Pinch failed to summon the volume metric on Wear OS.
- **The Root Cause**: Wear OS aggressively hides the volume overlay unless an active volume transition is occurring.
- **The Solution**: We successfully tricked the system by issuing a micro-bump: calling `ADJUST_RAISE` instantly followed by `ADJUST_LOWER` forces the volume overlay to render on the screen without actually altering the user's volume level.

## 7. Granular Call Management
- **The Challenge**: Mappings needed to be distinct based on the Telecom Manager state.
- **The Solution**: We instituted a dynamic state-checker routing protocol. 
    - **Single Pinch**: Checks `telephonyManager.callState`. If ringing, it answers the call. If idle, it plays/pauses media.
    - **Double Pinch**: Checks call state. If in any active call state (Ringing or Offhook), it ends/declines the call. If idle, it summons the Volume metric.
