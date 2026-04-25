# Flick: Wear OS Gesture Recognition System

Flick is a high-precision, low-latency gesture recognition engine designed for Wear OS. It enables users to control their smartwatches using discrete wrist and finger movements, bypassing traditional touch interactions.

##  Key Features

- **Multi-Gesture Support**: Detects **Pinch**, **Double Pinch**, **Flick**, and **Tilt Up/Down**.
- **Zero-Lag Physics Engine**: Optimized IMU vector analysis for real-time responsiveness on wearable hardware.
- **System Integration**: Controls media, notifications, calls, and volume via a specialized Accessibility Service.
- **Advanced Call Handling**: Context-aware gesture mapping for answering or declining calls.
- **Smart Overlays**: Programmatic swipe emulation to bypass OS restrictions on system UI.

##  Architecture

The project consists of three main components:

1.  **Wear OS App (Kotlin/Compose)**:
    - High-frequency sensor capture (Accelerometer & Gyroscope).
    - Real-time gesture dispatching via `FlickAccessibilityService`.
    - Physics-based heuristic engine for minimal CPU overhead.
2.  **Machine Learning Pipeline (Python)**:
    - Feature extraction from raw sensor data (84 statistical features).
    - `RandomForestClassifier` training with HGAG-DATA benchmarking.
    - Model evaluation and optimization scripts.
3.  **Automation & Scripts**:
    - Data harmonization for public datasets.
    - Model conversion and data augmentation tools.

##  Development History

Flick evolved from a heavy Random Forest ML model to a "Zero-Lag" Physics Heuristic Engine. This transition was necessary to solve performance bottlenecks on devices like the Pixel Watch 2, ensuring 60fps UI responsiveness while maintaining high gesture accuracy (98%+ in combined benchmarks).

See `DEVELOPMENT_LOG.md` for detailed engineering insights on:
- Solving CPU bottlenecks and frame skipping.
- Implementing "Ghost Swipe" path emulation.
- Hysteresis logic for preventing cross-triggering.

##  Installation & Setup

### Android App
- Open the project in Android Studio.
- Sync Gradle and build the `app` module.
- Deploy to a Wear OS 4+ device (e.g., Pixel Watch 2).
- Enable **Flick Accessibility Service** in the watch settings.

### ML Pipeline
```bash
pip install -r requirements.txt
python scripts/train_model.py
```

##  Project Structure

- `app/`: Wear OS application source code.
- `scripts/`: Data processing and training scripts.
- `datasets/`: (Ignored) Sensor data and public benchmarks.
- `models/`: (Ignored) Trained model artifacts.
- `results/`: (Ignored) Training metrics and logs.

Author
Kamit Koul
