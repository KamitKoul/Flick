import pandas as pd
import numpy as np
import os
import glob
from scipy.stats import skew, kurtosis

def extract_features(window_data, label):
    """
    Extract statistical features from a window of sensor data.
    """
    features = {}
    
    # 6 axes: ax, ay, az, gx, gy, gz
    axes = ['ax', 'ay', 'az', 'gx', 'gy', 'gz']
    
    for axis in axes:
        data = window_data[axis].values
        features[f'{axis}_mean'] = np.mean(data)
        features[f'{axis}_std'] = np.std(data)
        features[f'{axis}_max'] = np.max(data)
        features[f'{axis}_min'] = np.min(data)
        features[f'{axis}_zcr'] = ((data[:-1] * data[1:]) < 0).sum() / len(data)
        
    # SMA (Signal Magnitude Area)
    features['sma'] = np.sum(np.abs(window_data[['ax', 'ay', 'az']].values)) / len(window_data)
    
    # Energy
    features['energy'] = np.sum(window_data[['ax', 'ay', 'az']].values**2) / len(window_data)
    
    features['label'] = label
    return features

def process_csv(file_path, window_ms=500, overlap_pct=0.5):
    """
    Segment a CSV file into windows and extract features.
    """
    df = pd.read_csv(file_path)
    if len(df) < 10:
        return []
        
    # Calculate sampling rate roughly (ms per sample)
    time_diffs = df['timestamp'].diff().dropna()
    avg_sample_period = time_diffs.mean()
    
    window_size = int(window_ms / avg_sample_period)
    step_size = int(window_size * (1 - overlap_pct))
    
    if window_size <= 0:
        window_size = 50
        step_size = 25
        
    all_features = []
    
    for start in range(0, len(df) - window_size, step_size):
        end = start + window_size
        window = df.iloc[start:end]
        
        # Check if the label is consistent in this window
        # For simplicity, we take the most frequent label in the window
        label = window['label'].mode()[0]
        
        feat = extract_features(window, label)
        all_features.append(feat)
        
    return all_features

def main(dataset_dir="datasets/files", output_file="datasets/processed_features.csv"):
    csv_files = glob.glob(os.path.join(dataset_dir, "*.csv"))
    print(f"Found {len(csv_files)} CSV files in {dataset_dir}.")
    
    final_data = []
    for f in csv_files:
        features = process_csv(f)
        final_data.extend(features)
        
    if final_data:
        feature_df = pd.DataFrame(final_data)
        feature_df.to_csv(output_file, index=False)
        print(f"Saved {len(feature_df)} featured windows to {output_file}")
    else:
        print(f"No data processed from {dataset_dir}.")

if __name__ == "__main__":
    import sys
    if len(sys.argv) > 2:
        main(sys.argv[1], sys.argv[2])
    else:
        # Default: process user data
        main()
        # Also process public data if it exists
        if os.path.exists("datasets/public/harmonized"):
            main("datasets/public/harmonized", "datasets/public_features.csv")
