#!/bin/bash
# ------------------------------------------------------------------------------
# Vistora Agent: macOS .dmg Packager
# ------------------------------------------------------------------------------
# Usage: Run this on a macOS machine with JDK 17+ installed.
# ------------------------------------------------------------------------------

echo "----------------------------------------------------"
echo " Vistora Agent: Packaging Native macOS .dmg"
echo "----------------------------------------------------"

# 1. Configuration
APP_NAME="VistoraAgent"
VERSION="1.1.0"
MAIN_JAR="discovery-client-0.0.1-SNAPSHOT-agent.jar"
MAIN_CLASS="com.vistora.monitor.agent.Agent"
INPUT_DIR="build/libs"
OUTPUT_DIR="dist"

# Ensure input JAR exists
if [ ! -f "$INPUT_DIR/$MAIN_JAR" ]; then
    echo " Error: $INPUT_DIR/$MAIN_JAR not found. Run './gradlew agentFatJar' first."
    exit 1
fi

# Clean output
mkdir -p "$OUTPUT_DIR"
rm -rf "$OUTPUT_DIR/*"

# 2. Run jpackage
# Note: --icon is optional; if you have a .icns file, add: --icon "assets/icon.icns"
echo " Bundling with jpackage..."
jpackage \
  --input "$INPUT_DIR" \
  --name "$APP_NAME" \
  --main-jar "$MAIN_JAR" \
  --main-class "$MAIN_CLASS" \
  --type dmg \
  --dest "$OUTPUT_DIR" \
  --app-version "$VERSION" \
  --vendor "Vistora" \
  --copyright "© 2026 Vistora" \
  --mac-package-name "VistoraAgent" \
  --mac-package-identifier com.vistora.agent \
  --verbose

if [ $? -eq 0 ]; then
    echo "----------------------------------------------------"
    echo " Success! installer generated at: $OUTPUT_DIR/$APP_NAME-$VERSION.dmg"
    echo "----------------------------------------------------"
else
    echo " Error: Packaging failed."
fi
