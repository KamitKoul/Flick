# Flick: Wear OS Gesture Recognition System

Flick is a high-precision gesture recognition system designed for Wear OS. It uses raw accelerometer and gyroscope data to detect discrete user movements (Pinch, Flick, Tilt Up/Down) with low latency and high robustness.

---

## 🚀 Project Overview
The project is divided into four main pillars:
1.  **Wear OS Data Collector**: High-frequency sensor capture app.
2.  **ML Pipeline**: Feature extraction and model training.
3.  **Public Benchmarking**: Knowledge transfer from large-scale research datasets.
4.  **Deployment**: On-device real-time inference (In Progress).

---

## 🛠️ Phase 1 & 2: Data Collection & Extraction
We implemented a robust data acquisition system to capture high-fidelity sensor signals.

### 🔌 Wear OS App (Kotlin/Compose)
-   **SensorService**: A `FOREGROUND_SERVICE` that monitors `Sensor.TYPE_ACCELEROMETER` and `Sensor.TYPE_GYROSCOPE` at a high sampling rate.
-   **CSVWriter**: Logs raw data (Timestamp, AX, AY, AZ, GX, GY, GZ, Label) to external storage.
-   **Permissions**: Handles Android 14 requirements (`FOREGROUND_SERVICE_HEALTH`, `HIGH_SAMPLING_RATE_SENSORS`).

### 📂 Extraction Protocol
Data is extracted from the watch via ADB to the host machine:
```bash
adb pull /sdcard/Android/data/com.hyumn.flick/files/ datasets/files/
```

---

## 🧠 Phase 3: Machine Learning Pipeline
We built a Scikit-learn based pipeline to convert raw signals into a predictive model.

### 📐 Preprocessing (`preprocess.py`)
-   **Segmentation**: 500ms sliding windows with 50% overlap.
-   **Feature Engineering**: 14 statistical features per axis (Total 84 features), including:
    -   Mean & Standard Deviation (Spatial orientation).
    -   Signal Magnitude Area (SMA) (Intensity).
    -   Zero Crossing Rate (ZCR) (Frequency components).

### 🏆 Model Training (`train_model.py`)
-   **Engine**: `RandomForestClassifier`.
-   **Initial Accuracy**: **95.32%** on custom user data.

---

## 📊 Phase 3.5: Public Dataset Benchmarking (HGAG-DATA)
To ensure the model works for more than just one user, we integrated the **HGAG-DATA** set.

### 🔗 Harmonization (`harmonize_hgag.py`)
-   **Scale**: 23,650 samples (8,772 repetitions).
-   **Alignment**: Mapped public labels (Thumb Tap, Wrist Extension, etc.) to Flick's internal schema.
-   **Merge**: Combined the individual axis files (stored in repetition rows) into standard Flick CSV format.

### 📈 Benchmarking Results
| Model Configuration | Accuracy | Error Rate |
| :--- | :--- | :--- |
| **Baseline (User Data)** | 95.32% | 4.68% |
| **Public Model (Zero-Shot)** | 27.63% | 72.37% |
| **Boosted (Combined)** | **98.55%** | **1.45%** |

**Key Insight**: Combining public "priors" with user-specific data reduced the error rate by 3.2x.

---

## 📅 Current Status & Next Steps
We currently have a highly accurate, generalized model ready for deployment.

### ✅ Completed
- [x] High-freq data collection.
- [x] Feature extraction pipeline.
- [x] Harmonization of public data.
- [x] Robust comparative training.

### 🔜 Next: Phase 4 (Deployment)
- **TFLite Conversion**: Quantizing the `rf_combined_model` for mobile use.
- **On-Device Core**: Integrating the TFLite interpreter into the Android `SensorService`.
- **Haptic Feedback**: Triggering watch vibrations upon gesture detection.
