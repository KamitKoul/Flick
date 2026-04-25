import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
from sklearn.model_selection import train_test_split
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import classification_report, confusion_matrix, accuracy_score
import joblib
import os

def train_and_eval(X_train, y_train, X_test, y_test, title):
    print(f"\n--- {title} ---")
    model = RandomForestClassifier(n_estimators=100, max_depth=10, random_state=42)
    model.fit(X_train, y_train)
    y_pred = model.predict(X_test)
    acc = accuracy_score(y_test, y_pred)
    print(f"Accuracy: {acc:.4f}")
    return model, acc

def compare_datasets():
    user_file = "datasets/processed_features.csv"
    public_file = "datasets/public_features.csv"
    
    if not os.path.exists(user_file) or not os.path.exists(public_file):
        print("Error: Feature files not found.")
        return
        
    user_df = pd.read_csv(user_file)
    public_df = pd.read_csv(public_file)
    
    print(f"User Samples: {len(user_df)}")
    print(f"Public Samples: {len(public_df)}")
    
    # 1. User-Only Baseline
    Xu = user_df.drop('label', axis=1)
    yu = user_df['label']
    Xu_train, Xu_test, yu_train, yu_test = train_test_split(Xu, yu, test_size=0.2, random_state=42, stratify=yu)
    _, acc_user = train_and_eval(Xu_train, yu_train, Xu_test, yu_test, "Model A: User Data Only (Split 80/20)")
    
    # 2. Public-Only (Testing on User)
    Xp = public_df.drop('label', axis=1)
    yp = public_df['label']
    # Filter public data to match user labels
    user_labels = yu.unique()
    public_df_filtered = public_df[public_df['label'].isin(user_labels)]
    Xpf = public_df_filtered.drop('label', axis=1)
    ypf = public_df_filtered['label']
    
    model_public, _ = train_and_eval(Xpf, ypf, Xpf, ypf, "Model B: Training on Public Only (Internal Check)")
    y_pred_u = model_public.predict(Xu_test)
    acc_zero_shot = accuracy_score(yu_test, y_pred_u)
    print(f"Zero-Shot Accuracy (Public Model on User Data): {acc_zero_shot:.4f}")
    
    # 3. Combined Model
    combined_df = pd.concat([user_df, public_df_filtered])
    Xc = combined_df.drop('label', axis=1)
    yc = combined_df['label']
    # Split so we keep a clean user test set
    model_combined, acc_comb = train_and_eval(Xc, yc, Xu_test, yu_test, "Model C: Combined (User + Public) Tested on User")
    
    # Results Summary
    print("\n" + "="*30)
    print("COMPARISON SUMMARY")
    print(f"User Only Acc:   {acc_user:.4f}")
    print(f"Zero-Shot Acc:   {acc_zero_shot:.4f}")
    print(f"Combined Acc:    {acc_comb:.4f}")
    print("="*30)
    
    # Save the combined model
    os.makedirs("models", exist_ok=True)
    joblib.dump(model_combined, "models/rf_combined_model.joblib")
    print("Combined model saved to models/rf_combined_model.joblib")

if __name__ == "__main__":
    compare_datasets()
