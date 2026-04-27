# Vistora Agent: Windows Packager
Write-Output "--- Vistora Agent: Packaging Windows EXE Installer ---"

$APP_NAME = "VistoraAgent"
$VERSION = "1.1.0"
$MAIN_JAR = "discovery-client-0.0.1-SNAPSHOT-agent.jar"
$MAIN_CLASS = "com.vistora.discovery.monitor.agent.Agent"
$INPUT_DIR = "build/libs"
$OUTPUT_DIR = "dist"

if (-not (Test-Path "$INPUT_DIR/$MAIN_JAR")) {
    Write-Output "ERROR: $INPUT_DIR/$MAIN_JAR not found. Run ./gradlew agentFatJar first."
    exit 1
}

if (-not (Test-Path "extension")) {
    Write-Output "ERROR: 'extension' folder not found. Cannot bundle browser extension."
    exit 1
}

if (-not (Test-Path $OUTPUT_DIR)) { New-Item -ItemType Directory $OUTPUT_DIR }
Remove-Item -Path "$OUTPUT_DIR/*" -Recurse -Force -ErrorAction SilentlyContinue

Write-Output "Bundling with jpackage (app-image)..."
jpackage `
  --input $INPUT_DIR `
  --name $APP_NAME `
  --main-jar $MAIN_JAR `
  --main-class $MAIN_CLASS `
  --type app-image `
  --dest $OUTPUT_DIR `
  --app-version $VERSION `
  --vendor "Vistora" `
  --verbose

if ($LASTEXITCODE -ne 0) {
    Write-Output "ERROR: jpackage failed."
    exit 1
}

Write-Output "Compiling VistoraAgent-Setup.exe with Inno Setup 6..."
$ISCC = "C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
if (-not (Test-Path $ISCC)) {
    Write-Output "ERROR: Inno Setup 6 compiler not found at $ISCC. Please install it."
    exit 1
}

& $ISCC /DAppVersion=$VERSION installer.iss

if ($LASTEXITCODE -eq 0) {
    Write-Output "SUCCESS! Final setup installer generated at: $OUTPUT_DIR/VistoraAgent-Setup-$VERSION.exe"
    
    # NEW: Cleanup intermediate app-image to ensure only the final Setup.exe remains
    Write-Output "Cleaning up intermediate files..."
    Remove-Item -Path "$OUTPUT_DIR/$APP_NAME" -Recurse -Force -ErrorAction SilentlyContinue
    
    Write-Output "Done. Only the final installer remains in $OUTPUT_DIR"
} else {
    Write-Output "ERROR: Inno Setup compilation failed."
}