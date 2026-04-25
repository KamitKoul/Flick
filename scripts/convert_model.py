import joblib
import m2cgen as m2c
import os
from pathlib import Path

def convert():
    model_path = "models/rf_combined_model.joblib"
    output_dir = Path("app/src/main/java/com/hyumn/flick/ml")
    output_dir.mkdir(parents=True, exist_ok=True)
    
    if not os.path.exists(model_path):
        print(f"Error: {model_path} not found.")
        return
        
    print(f"Loading model from {model_path}...")
    model = joblib.load(model_path)
    
    # m2cgen works best with pure models.
    # Note: If we used a Pipeline, we'd need to extract the classifier itself.
    # Our train_model.py saves the model directly.
    
    print("Generating Java code...")
    # m2cgen.export_to_java generates a class with a score() method.
    code = m2c.export_to_java(model, class_name="GestureClassifier")
    
    # We need to add package declaration
    final_code = f"package com.hyumn.flick.ml;\n\n{code}"
    
    output_file = output_dir / "GestureClassifier.java"
    with open(output_file, "w") as f:
        f.write(final_code)
        
    print(f"Successfully exported model to {output_file}")

if __name__ == "__main__":
    convert()
