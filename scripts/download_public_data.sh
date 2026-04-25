#!/bin/bash
# download_public_data.sh
# Automates the retrieval of HGAG-DATA from Mendeley Data.

DATASET_DIR="datasets/public"
ZIP_URL="https://data.mendeley.com/public-api/zip/mkhn7kxjvy/download/3"
ZIP_FILE="$DATASET_DIR/hgag_data.zip"
EXTRACT_DIR="$DATASET_DIR/hgag_raw"

echo "Creating directory: $DATASET_DIR"
mkdir -p "$DATASET_DIR"

if [ ! -f "$ZIP_FILE" ]; then
    echo "Downloading HGAG-DATA (~987 MB)..."
    curl -L "$ZIP_URL" -o "$ZIP_FILE"
    if [ $? -ne 0 ]; then
        echo "Error: Download failed. You may need to download manually from https://data.mendeley.com/datasets/mkhn7kxjvy/1"
        exit 1
    fi
else
    echo "Zip file already exists, skipping download."
fi

echo "Extracting data..."
unzip -q "$ZIP_FILE" -d "$EXTRACT_DIR"
if [ $? -ne 0 ]; then
    echo "Error: Extraction failed."
    exit 1
fi

echo "Public dataset setup complete."
