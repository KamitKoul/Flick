import pandas as pd
import numpy as np
import os
import glob
from pathlib import Path

def harmonize():
    base_raw = Path("datasets/public/hgag_raw/Hand Gesture Accelerometer and Gyroscope Dataset (HGAG-DATA)/HGAG-DATA/HGAG-DATA1")
    output_dir = Path("datasets/public/harmonized")
    output_dir.mkdir(parents=True, exist_ok=True)
    
    print(f"Harmonizing HGAG-DATA from {base_raw}...")
    
    label_map = {
        "Index Thumb Tap": "pinch",
        "Index Finger Flick": "flick",
        "Horizontal Wrist Extension": "tilt_up",
        "Wrist Flexion": "tilt_down"
    }
    
    for gesture_name, flick_label in label_map.items():
        gesture_path = base_raw / gesture_name
        if not gesture_path.exists():
            continue
            
        print(f"Processing: {gesture_name} -> {flick_label}")
        
        for sub_dir in gesture_path.iterdir():
            if not sub_dir.is_dir(): continue
            
            csv_dir = sub_dir / ".csv"
            if not csv_dir.exists(): continue
            
            # Load all 6 axes
            try:
                ax_df = pd.read_csv(csv_dir / "accel_x_data.csv", header=None)
                ay_df = pd.read_csv(csv_dir / "accel_y_data.csv", header=None)
                az_df = pd.read_csv(csv_dir / "accel_z_data.csv", header=None)
                gx_df = pd.read_csv(csv_dir / "gyro_x_data.csv", header=None)
                gy_df = pd.read_csv(csv_dir / "gyro_y_data.csv", header=None)
                gz_df = pd.read_csv(csv_dir / "gyro_z_data.csv", header=None)
            except Exception as e:
                print(f"  Error reading {sub_dir.name}: {e}")
                continue
                
            # num_reps is number of rows
            num_reps = len(ax_df)
            
            for rep_idx in range(num_reps):
                # Construct combined DF for this specific repetition
                rep_data = pd.DataFrame({
                    'ax': ax_df.iloc[rep_idx],
                    'ay': ay_df.iloc[rep_idx],
                    'az': az_df.iloc[rep_idx],
                    'gx': gx_df.iloc[rep_idx],
                    'gy': gy_df.iloc[rep_idx],
                    'gz': gz_df.iloc[rep_idx]
                }).dropna()
                
                if len(rep_data) < 10: continue
                
                # Align units (HGAG is usually raw or filtered units, Flick uses m/s^2 and rad/s)
                # Note: Testing on sample showed HGAG units are roughly in the same magnitude as Android sensors.
                
                rep_data['timestamp'] = np.arange(len(rep_data)) * 5 # 200Hz = 5ms
                rep_data['label'] = flick_label
                
                output_name = f"hgag_{gesture_name.replace(' ', '_')}_{sub_dir.name}_rep{rep_idx}.csv"
                rep_data.to_csv(output_dir / output_name, index=False)

    print("Harmonization complete.")

    print("Harmonization complete.")

if __name__ == "__main__":
    harmonize()
