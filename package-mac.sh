#!/bin/bash

echo "----------------------------------------------------"
echo " Vistora Agent: Packaging Native macOS .dmg"
echo "----------------------------------------------------"

APP_NAME="VistoraAgent"
VERSION="1.1.0"

# Automatically detect jar (avoids hardcoding errors)
INPUT_DIR="build/libs"
MAIN_JAR=$(ls $INPUT_DIR/*.jar | head -n 1)

MAIN_CLASS="com.vistora.monitor.agent.Agent"
OUTPUT_DIR="dist"

echo "Using JAR: $MAIN_JAR"

# Check jar exists
if [ ! -f "$MAIN_JAR" ]; then
    echo "❌ Error: JAR not found in $INPUT_DIR"
    exit 1
fi

# Clean output
mkdir -p "$OUTPUT_DIR"
rm -rf "$OUTPUT_DIR"/*

echo "📦 Bundling with jpackage..."

jpackage \
  --input "$INPUT_DIR" \
  --name "$APP_NAME" \
  --main-jar "$(basename $MAIN_JAR)" \
  --main-class "$MAIN_CLASS" \
  --type dmg \
  --dest "$OUTPUT_DIR" \
  --app-version "$VERSION" \
  --vendor "Vistora" \
  --mac-package-name "VistoraAgent" \
  --mac-package-identifier com.vistora.agent \
  --java-options "-Xmx512m" \
  --verbose

if [ $? -eq 0 ]; then
    echo "----------------------------------------------------"
    echo "✅ Success! DMG generated at: $OUTPUT_DIR"
    echo "----------------------------------------------------"
else
    echo "❌ Error: Packaging failed."
    exit 1
fi