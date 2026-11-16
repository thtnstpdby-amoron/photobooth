# PowerShell equivalent of build-windows.sh.
# Run from anywhere. Requires a .NET SDK capable of building the project's .NET 8 targets.
# Publishes self-contained Windows x64 binaries, then bundles cloudflared.exe.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-ExternalSuccess([string]$Description) {
    if ($LASTEXITCODE -ne 0) { throw "$Description failed (exit code $LASTEXITCODE)." }
}

if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
    throw 'dotnet was not found. Install the .NET 8 SDK and reopen PowerShell.'
}

Push-Location $PSScriptRoot
try {
    $dist = Join-Path $PSScriptRoot 'dist'
    $appOut = Join-Path $dist 'app'
    $adminOut = Join-Path $dist 'admin'

    if (-not (Test-Path (Join-Path $PSScriptRoot 'backend'))) { throw 'Missing backend/ folder.' }
    if (-not (Test-Path (Join-Path $PSScriptRoot 'admin'))) { throw 'Missing admin/ folder.' }

    # Mirrors: rm -rf dist && mkdir -p dist/app
    if (Test-Path $dist) { Remove-Item -LiteralPath $dist -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $appOut | Out-Null

    Write-Host 'Publishing Windows server (win-x64)...'
    & dotnet publish 'backend' -c Release -r win-x64 --self-contained true '-p:PublishSingleFile=true' -o $appOut
    Assert-ExternalSuccess 'Backend publish'

    Write-Host 'Publishing Windows admin app (win-x64)...'
    & dotnet publish 'admin' -c Release -r win-x64 --self-contained true '-p:PublishSingleFile=true' -o $adminOut
    Assert-ExternalSuccess 'Admin publish'

    $cloudflared = Join-Path $PSScriptRoot 'cloudflared.exe'
    if (-not (Test-Path -LiteralPath $cloudflared -PathType Leaf)) {
        Write-Host 'Downloading cloudflared.exe from Cloudflare GitHub releases...'
        Invoke-WebRequest -Uri 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe' -OutFile $cloudflared
    }
    Copy-Item -LiteralPath $cloudflared -Destination (Join-Path $appOut 'cloudflared.exe') -Force

    Write-Host ''
    Write-Host "Done. Server: $appOut"
    Write-Host "      Admin:  $adminOut"
    Write-Host 'To create the Windows installer, compile installer/photobooth.iss with Inno Setup.'
}
finally {
    Pop-Location
}
