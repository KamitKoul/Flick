import numpy as np
import pandas as pd
import os

CSV_PATH = "flick_training_data_v2.csv"
OUT_PATH = "flick_training_data_v2.csv" # Overwrite with augmented data

if not os.path.exists(CSV_PATH):
    print("CSV not found.")
    exit(1)

df = pd.read_csv(CSV_PATH, header=None)
# Filter for Pinch class (0)
pinches = df[df[0] == 0].values

if len(pinches) == 0:
    print("No pinches found in CSV.")
    exit(1)

print(f"Found {len(pinches)} original pinches. Hyper-augmenting to 200...")

augmented_pinches = []
target_count = 200

while len(augmented_pinches) < target_count:
    for p in pinches:
        if len(augmented_pinches) >= target_count:
            break
            
        label = p[0]
        features = p[1:].reshape(40, 6)
        
        # 1. Random Time Shift (+/- 5 samples)
        shift = np.random.randint(-5, 6)
        aug_features = np.roll(features, shift, axis=0)
        
        # 2. Random Scaling (0.8x to 1.2x)
        scale = np.random.uniform(0.8, 1.2)
        aug_features *= scale
        
        # 3. Add Gaussian Noise
        noise = np.random.normal(0, 0.05, aug_features.shape)
        aug_features += noise
        
        # Flatten and add label
        new_row = np.hstack([[label], aug_features.flatten()])
        augmented_pinches.append(new_row)

# Convert to DataFrame
df_aug = pd.DataFrame(augmented_pinches)

# Append to original (or replace if you want a clean balanced set)
# We append to keep the other data too
# df_final = pd.concat([df, df_aug], ignore_index=True)

# Actually, let's SAVE it as a separate file first to be safe, then merge
df_aug.to_csv("flick_pinch_boost.csv", index=False, header=False)

# Now merge into main
with open(OUT_PATH, "a") as main_file:
    with open("flick_pinch_boost.csv", "r") as boost_file:
        main_file.write(boost_file.read())

print(f"Successfully injected 200 augmented pinches into {OUT_PATH}")
