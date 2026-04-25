"""
Flick Wear OS - Hardened TFLite Training (90%+ Accuracy Goal)
============================================================

Upgrades:
1. Window Size: 20 -> 40 samples (160ms)
2. Peak Alignment: Centers the gesture impulse
3. Data Augmentation: Triple dataset via time-shifting
4. Architecture: ResNet-style 1D CNN
"""
import os
# Fix for macOS TensorFlow "Lock Blocking" hang
os.environ['TF_CPP_MIN_LOG_LEVEL'] = '2'
os.environ['KMP_DUPLICATE_LIB_OK'] = 'True'

import numpy as np
import pandas as pd
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler
import tensorflow as tf
import os

# --- Config ---
MODEL_OUT   = "gesture_model.tflite"
WINDOW_SIZE = 40   # samples per row (v2.0)
AXES        = 6    # ax,ay,az,gx,gy,gz
INPUT_SIZE  = WINDOW_SIZE * AXES 
NUM_CLASSES = 4
LABELS      = {0: "pinch", 1: "typing", 2: "rotation", 3: "idle"}

def find_csv():
    candidates = [
        "./flick_training_data_v2.csv",
        "./flick_training_data.csv",
        os.path.expanduser("~/Library/CloudStorage/GoogleDrive-kamitkoul05@gmail.com/My Drive/Flick/flick_training_data_v2.csv"),
        os.path.expanduser("~/Library/CloudStorage/GoogleDrive-kamitkoul05@gmail.com/My Drive/Flick/flick_training_data.csv")
    ]
    for path in candidates:
        if os.path.exists(path):
            return path
    return None

CSV_PATH = find_csv()

def augment_data(X, y):
    """
    Time-shifting augmentation: Shifting the window +/- 2 samples
    to make the model invariant to slight trigger delays.
    """
    X_aug, y_aug = [], []
    # Reshape to (N, 40, 6)
    X_reshaped = X.reshape(-1, WINDOW_SIZE, AXES)
    
    for i in range(len(X_reshaped)):
        # Original
        X_aug.append(X_reshaped[i])
        y_aug.append(y[i])
        
        # Only augment gestures (not idle/typing which are continuous)
        if y[i] in [0, 2]: 
            # Shift Left
            shift_l = np.roll(X_reshaped[i], -2, axis=0)
            shift_l[-2:] = X_reshaped[i][-1] # padding
            X_aug.append(shift_l)
            y_aug.append(y[i])
            
            # Shift Right
            shift_r = np.roll(X_reshaped[i], 2, axis=0)
            shift_r[:2] = X_reshaped[i][0] # padding
            X_aug.append(shift_r)
            y_aug.append(y[i])
            
    return np.array(X_aug), np.array(y_aug)

if CSV_PATH:
    print(f"Loading data from: {CSV_PATH}")
    df = pd.read_csv(CSV_PATH, header=None)
    
    # Check if this is a v1 (121 cols) or v2 (241 cols) file
    cols = df.shape[1]
    if cols == 121:
        print("Warning: Detected v1 data (20 samples). Retraining on old format.")
        W_SIZE = 20
    else:
        print(f"Detected v2 data ({cols-1} features). Success.")

    # --- Dataset Balancing (v7.0 Downsampling) ---
    print("Balancing classes (Capping Idle/Typing to 2x Pinch count)...")
    pinch_df = df[df[0] == 0]
    pinch_count = len(pinch_df)
    
    balanced_df = pd.concat([
        pinch_df,
        df[df[0] == 2], # Keep all Rotation
        df[df[0] == 1].sample(min(len(df[df[0] == 1]), pinch_count * 2), random_state=42),
        df[df[0] == 3].sample(min(len(df[df[0] == 3]), pinch_count * 2), random_state=42)
    ])
    
    print(f"Original: {len(df)} samples. Balanced: {len(balanced_df)} samples.")
    df = balanced_df.sample(frac=1, random_state=42) # Shuffle

    y = df.iloc[:, 0].values.astype(int)
    X_raw = df.iloc[:, 1:].values.astype(np.float32)

    # --- 1. Window-Wise Zero-Mean (Gravity Removal) ---
    print("Applying Zero-Mean (Gravity Eraser)...")
    X_reshaped = X_raw.reshape(-1, WINDOW_SIZE, AXES) # (N, 40, 6)
    X_means = X_reshaped.mean(axis=1, keepdims=True)
    X_zero_mean = X_reshaped - X_means
    
    # --- 2. 5Hz Digital High-Pass Filter (Slosh Removal) ---
    # Removes slow hand opening/closing (1-2Hz) while keeping finger snaps (20-50Hz)
    print("Applying 5Hz Digital High-Pass Filter...")
    alpha = 0.864 # for 5Hz at 200Hz sampling
    X_filtered = np.zeros_like(X_zero_mean)
    for i in range(1, WINDOW_SIZE):
        X_filtered[:, i, :] = alpha * (X_filtered[:, i-1, :] + X_zero_mean[:, i, :] - X_zero_mean[:, i-1, :])
    
    # NOTE: We skip division by stds (scaling). 
    # Scale is a FEATURE: Pinches are light, Slosh is heavy.
    X_scaled = X_filtered
    print("Normalization: Scale preserved. Windows are now Frequency-Isolated.")

    # Augment
    print("Augmenting data (Time-Shifting)...")
    X_aug, y_aug = [], []
    for i in range(len(X_reshaped)):
        X_aug.append(X_reshaped[i])
        y_aug.append(y[i])
        if y[i] in [0, 2]: # Pinch or Rotation
            X_aug.append(np.roll(X_reshaped[i], -3, axis=0))
            y_aug.append(y[i])
            X_aug.append(np.roll(X_reshaped[i], 3, axis=0))
            y_aug.append(y[i])
            
    X_aug = np.array(X_aug)
    y_aug = np.array(y_aug)
    
    # --- Aggressive Oversampling (v7.1 Pinch Booster) ---
    print("Oversampling Pinch data (4x replication)...")
    pinch_idx = np.where(y_aug == 0)[0]
    X_pinch = X_aug[pinch_idx]
    y_pinch = y_aug[pinch_idx]
    
    # Quadruple the pinch data to force model focus
    X_aug = np.concatenate([X_aug, X_pinch, X_pinch, X_pinch])
    y_aug = np.concatenate([y_aug, y_pinch, y_pinch, y_pinch])
    
    print(f"Final Dataset Size: {len(X_aug)} samples")

    # Split
    X_train, X_test, y_train, y_test = train_test_split(
        X_aug, y_aug, test_size=0.15, stratify=y_aug, random_state=42
    )

    # Compute Weights
    from sklearn.utils import class_weight
    weights = class_weight.compute_class_weight('balanced', classes=np.unique(y_aug), y=y_aug)
    class_weights = dict(enumerate(weights))
    
    # AGGRESSIVE Pinch weight (v7.1 Focus Fix)
    # Pinch is Class 0. We force the model to care about it 20x more than others.
    class_weights[0] = class_weights[0] * 20.0 

    # --- Conservative 3-Layer CNN Architecture ---
    inputs = tf.keras.layers.Input(shape=(WINDOW_SIZE, AXES))
    
    # Layer 1: Wider filters to catch subtle tendon twitches
    x = tf.keras.layers.Conv1D(64, 5, padding='same')(inputs)
    x = tf.keras.layers.BatchNormalization()(x)
    x = tf.keras.layers.Activation('relu')(x)
    x = tf.keras.layers.MaxPooling1D(2)(x)
    
    # Layer 2: Deeper patterns + Peak Extraction
    x = tf.keras.layers.Conv1D(128, 3, padding='same')(x)
    x = tf.keras.layers.BatchNormalization()(x)
    x = tf.keras.layers.Activation('relu')(x)
    x = tf.keras.layers.GlobalMaxPooling1D()(x) # v7.1: Max rather than Average to catch 'Snaps'
    
    # Layer 3: High-capacity classification
    x = tf.keras.layers.Dense(128)(x)
    x = tf.keras.layers.Activation('relu')(x)
    x = tf.keras.layers.Dropout(0.1)(x) # Reduced dropout (v5.6 Confidence Fix)
    
    x = tf.keras.layers.Dense(NUM_CLASSES)(x)
    outputs = tf.keras.layers.Activation('softmax')(x)

    model = tf.keras.Model(inputs, outputs)
    model.compile(optimizer='adam', loss='sparse_categorical_crossentropy', metrics=['accuracy'])

    # Train
    print("\nTraining for Peak Confidence (v7.1)...")
    model.fit(
        X_train, y_train,
        epochs=250, # More time to converge with oversampling
        batch_size=32,
        validation_split=0.15,
        class_weight=class_weights,
        callbacks=[tf.keras.callbacks.EarlyStopping(patience=20, restore_best_weights=True)],
        verbose=1
    )

    # Convert to TFLite (Stable Path for macOS/Keras3)
    print("\nSaving intermediate model for robust conversion...")
    # Keras 3.0 uses .export() for SavedModel format
    if hasattr(model, 'export'):
        model.export("temp_saved_model")
    else:
        model.save("temp_saved_model", save_format="tf")
    
    print("Converting to TFLite (Forcing Classic Opcodes)...")

    converter = tf.lite.TFLiteConverter.from_saved_model("temp_saved_model")
    
    # v5.2 Compatibility Fix: Disable per-axis scales in Dense layers (prevents Opcode v12)
    converter._experimental_disable_per_channel_quantization_for_dense_layers = True
    
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    
    tflite_model = converter.convert()
    with open(MODEL_OUT, "wb") as f: f.write(tflite_model)
    print(f"Saved: {MODEL_OUT}")
    
    # Cleanup
    import shutil
    if os.path.exists("temp_saved_model"):
        shutil.rmtree("temp_saved_model")

else:
    print("CSV not found. Please collect data first.")
