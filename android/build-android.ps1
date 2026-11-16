# Builds app-debug.apk from the command line (Windows). No Android Studio. Needs JDK 17+ and internet.
$ErrorActionPreference = "Stop"

# Navigate to script directory
Set-Location $PSScriptRoot

# Setup paths
$TMPDIR = Join-Path $HOME "tmp"
if (-not (Test-Path $TMPDIR)) { New-Item -ItemType Directory -Force -Path $TMPDIR | Out-Null }
$SDK = Join-Path $HOME "android-sdk"
$GV = "8.7"

# Check for Java
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host "Install a JDK first (JDK 17+). Run: winget install Microsoft.OpenJDK.17" -ForegroundColor Red
    exit 1
}

# Download and extract Android command-line tools if missing
if (-not (Test-Path "$SDK\cmdline-tools\latest")) {
    New-Item -ItemType Directory -Force -Path "$SDK\cmdline-tools" | Out-Null
    Write-Host "Downloading Android SDK Command-line Tools..."
    # Note: Swapped to the 'win' zip instead of 'linux'
    Invoke-WebRequest -Uri "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile "$TMPDIR\cmd.zip"
    Write-Host "Extracting tools..."
    Expand-Archive -Path "$TMPDIR\cmd.zip" -DestinationPath "$SDK\cmdline-tools" -Force
    Rename-Item -Path "$SDK\cmdline-tools\cmdline-tools" -NewName "latest"
}

$env:ANDROID_HOME = $SDK

# Accept licenses (pipes 'y' repeatedly to simulate the Linux 'yes' command)
Write-Host "Accepting Android SDK licenses..."
1..50 | ForEach-Object { "y" } | & "$SDK\cmdline-tools\latest\bin\sdkmanager.bat" --licenses > $null

# Install SDK components
& "$SDK\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# Download and extract Gradle if missing
if (-not (Test-Path "$HOME\gradle-$GV\bin\gradle.bat")) {
    Write-Host "Downloading Gradle $GV..."
    Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-$GV-bin.zip" -OutFile "$TMPDIR\gradle.zip"
    Write-Host "Extracting Gradle..."
    Expand-Archive -Path "$TMPDIR\gradle.zip" -DestinationPath "$HOME" -Force
}

# Write local.properties (escaping backslashes for Java properties format)
$EscapedSDK = $SDK -replace '\\', '\\'
Set-Content -Path "local.properties" -Value "sdk.dir=$EscapedSDK"

# Run Gradle build
Write-Host "Building APK..."
& "$HOME\gradle-$GV\bin\gradle.bat" --no-daemon assembleDebug

# Print output locations
$CurrentPath = (Get-Location).Path
Write-Host "`nAPK: $CurrentPath\app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor Green
Write-Host "Install: $SDK\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor Cyan