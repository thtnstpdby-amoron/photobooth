#Requires -Version 5.1
<#
Photobooth Windows development-machine bootstrap (Windows 10/11, PowerShell 5.1+).
Place at the repository root, alongside android/, backend/, and admin/.
Installs/checks: Git, Temurin JDK 17, .NET SDK 10, Android SDK API 34,
Android Build Tools 34.0.0, platform-tools (ADB), and Gradle 8.7.
Optional: .\setup-dev.ps1 -WithInnoSetup

Uses winget for Windows application installers. Those installers may request
administrator approval. Android SDK and Gradle live under your user profile.
This script does NOT build the projects or replace your working build scripts.
Running it accepts the package-manager and Android SDK license agreements.
#>
[CmdletBinding()]
param([switch]$WithInnoSetup)

$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'This script is for Windows only.' }
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$sdk = Join-Path $HOME 'android-sdk'
$gradleVersion = '8.7'
$tmp = Join-Path $HOME 'tmp'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

function Assert-NativeSuccess([string]$What) {
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit code $LASTEXITCODE)." }
}
function Refresh-ProcessPath {
    # Installers change registry environment variables; the current shell won't
    # see those changes until they are explicitly read or a new shell is opened.
    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = (@($env:Path, $machinePath, $userPath) | Where-Object { $_ }) -join ';'
    foreach ($name in @('JAVA_HOME', 'DOTNET_ROOT')) {
        $value = [Environment]::GetEnvironmentVariable($name, 'User')
        if (-not $value) { $value = [Environment]::GetEnvironmentVariable($name, 'Machine') }
        if ($value) { Set-Item -Path "Env:$name" -Value $value }
    }
}
function Install-Winget([string]$PackageId) {
    if (-not (Get-Command winget.exe -ErrorAction SilentlyContinue)) {
        throw 'winget is not installed. Install App Installer from Microsoft, then rerun this script.'
    }
    Write-Host "Installing $PackageId with winget (Windows may request administrator approval)..." -ForegroundColor Cyan
    & winget.exe install --id $PackageId --exact --source winget --accept-source-agreements --accept-package-agreements
    Assert-NativeSuccess "winget install $PackageId"
    Refresh-ProcessPath
}
function Get-Jdk17Home {
    $homes = @()
    if ($env:JAVA_HOME) { $homes += $env:JAVA_HOME }
    $javac = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javac) { $homes += (Split-Path -Parent (Split-Path -Parent $javac.Source)) }
    $roots = @(
        (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
        (Join-Path $env:ProgramFiles 'Microsoft'),
        (Join-Path $env:ProgramFiles 'Java'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Eclipse Adoptium')
    )
    foreach ($root in $roots) {
        if (Test-Path -LiteralPath $root) {
            $homes += @(Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match 'jdk[-_]?17|^jdk-17' } |
                ForEach-Object { $_.FullName })
        }
    }
    foreach ($homePath in ($homes | Select-Object -Unique)) {
        $exe = Join-Path $homePath 'bin\javac.exe'
        if (Test-Path -LiteralPath $exe -PathType Leaf) {
            $versionText = (& $exe -version | Out-String).Trim()
            if ($LASTEXITCODE -eq 0 -and $versionText -match '^javac 17(\.|\s|$)') { return $homePath }
        }
    }
    return $null
}
function Get-DotNetExe {
    # Prefer the x64 installation, avoiding a stale x86 dotnet.exe on PATH.
    $candidate = Join-Path $env:ProgramFiles 'dotnet\dotnet.exe'
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    $cmd = Get-Command dotnet.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}
function Has-DotNet10Sdk {
    $exe = Get-DotNetExe
    if (-not $exe) { return $false }
    $versions = @(& $exe --list-sdks)
    if ($LASTEXITCODE -ne 0) { return $false }
    return [bool]($versions | Where-Object { $_ -match '^10\.\d+\.\d+\s' })
}

Write-Host '=== Photobooth development setup ===' -ForegroundColor Green

# 1) Git (needed to clone/pull on future machines).
if (-not (Get-Command git.exe -ErrorAction SilentlyContinue)) {
    Install-Winget 'Git.Git'
    foreach ($gitCmd in @(
        (Join-Path $env:ProgramFiles 'Git\cmd'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Git\cmd')
    )) {
        if (Test-Path (Join-Path $gitCmd 'git.exe')) {
            $env:Path = "$gitCmd;$env:Path"
            break
        }
    }
}
if (-not (Get-Command git.exe -ErrorAction SilentlyContinue)) {
    throw 'Git was installed but is not available on PATH. Reopen PowerShell, then rerun.'
}

# 2) JDK 17, matching the known working Android build configuration.
$javaHome = Get-Jdk17Home
if (-not $javaHome) {
    Install-Winget 'EclipseAdoptium.Temurin.17.JDK'
    $javaHome = Get-Jdk17Home
}
if (-not $javaHome) { throw 'JDK 17 not found after installation. Open a new PowerShell window and rerun.' }
$env:JAVA_HOME = $javaHome
$env:Path = "$(Join-Path $javaHome 'bin');$env:Path"
Write-Host "JDK: $javaHome"

# 3) .NET 10 SDK (supported LTS toolchain). Existing .NET 8 target frameworks
# are NOT upgraded by installing this SDK; migrate the projects separately.
if (-not (Has-DotNet10Sdk)) { Install-Winget 'Microsoft.DotNet.SDK.10' }
if (-not (Has-DotNet10Sdk)) {
    throw '.NET 10 SDK not detected. Reopen PowerShell and rerun (check dotnet --list-sdks).'
}
$dotnetExe = Get-DotNetExe
$dotnetDir = Split-Path -Parent $dotnetExe
$env:Path = "$dotnetDir;$env:Path"

# 4) Android SDK command-line tools. The archive revision matches your
# working build-android.ps1; installed SDK packages are pinned below.
$sdkManager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $sdkManager -PathType Leaf)) {
    Write-Host 'Downloading Android SDK command-line tools...' -ForegroundColor Cyan
    $zip = Join-Path $tmp 'commandlinetools-win-11076708_latest.zip'
    $unpack = Join-Path $tmp 'photobooth-android-tools'
    if (Test-Path -LiteralPath $unpack) { Remove-Item -LiteralPath $unpack -Recurse -Force }
    Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile $zip
    Expand-Archive -LiteralPath $zip -DestinationPath $unpack -Force
    $source = Join-Path $unpack 'cmdline-tools'
    if (-not (Test-Path (Join-Path $source 'bin\sdkmanager.bat'))) {
        throw 'Unexpected Android command-line tools archive layout.'
    }
    $toolsDir = Join-Path $sdk 'cmdline-tools'
    New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null
    $dest = Join-Path $toolsDir 'latest'
    if (Test-Path $dest) { Remove-Item -LiteralPath $dest -Recurse -Force }
    Move-Item -LiteralPath $source -Destination $dest
    Remove-Item -LiteralPath $unpack -Recurse -Force
}
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
[Environment]::SetEnvironmentVariable('ANDROID_HOME', $sdk, 'User')
Write-Host 'Accepting Android SDK licenses and installing API 34 packages...' -ForegroundColor Cyan
1..50 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$sdk" --licenses | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Warning 'SDK license command returned an error. The package installation may require manual license acceptance.' }
& $sdkManager "--sdk_root=$sdk" 'platform-tools' 'platforms;android-34' 'build-tools;34.0.0'
Assert-NativeSuccess 'Android SDK package installation'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) { throw 'ADB installation was not found.' }

# 5) Standalone Gradle 8.7, matching your successful --no-daemon build script.
$gradle = Join-Path $HOME "gradle-$gradleVersion\bin\gradle.bat"
if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) {
    Write-Host "Downloading Gradle $gradleVersion..." -ForegroundColor Cyan
    $zip = Join-Path $tmp "gradle-$gradleVersion-bin.zip"
    Invoke-WebRequest -UseBasicParsing -Uri "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip" -OutFile $zip
    Expand-Archive -LiteralPath $zip -DestinationPath $HOME -Force
}
if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) { throw 'Gradle executable not found.' }

# 6) Optional Windows installer compiler.
if ($WithInnoSetup) {
    $isccPaths = @(
        (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe')
    )
    if (-not ($isccPaths | Where-Object { Test-Path $_ })) {
        Install-Winget 'JRSoftware.InnoSetup'
    }
}

# 7) Local SDK pointer for this checkout; Git should ignore local.properties.
$androidProject = Join-Path $PSScriptRoot 'android'
if (Test-Path (Join-Path $androidProject 'settings.gradle.kts')) {
    $sdkForwardSlashes = $sdk.Replace('\', '/')
    Set-Content -LiteralPath (Join-Path $androidProject 'local.properties') -Value "sdk.dir=$sdkForwardSlashes" -Encoding ASCII
    Write-Host 'Wrote android/local.properties (machine-specific; do not commit).'
}

Write-Host ''
Write-Host '=== Installed tools ===' -ForegroundColor Green
& git.exe --version
& (Join-Path $javaHome 'bin\javac.exe') -version
& $dotnetExe --list-sdks
& $adb version
& $gradle --version
Write-Host ''
Write-Host 'Environment setup completed. Use your existing build scripts to build the projects.' -ForegroundColor Green
Write-Host 'If commands are not found in another terminal, close it and open a fresh PowerShell window.'
