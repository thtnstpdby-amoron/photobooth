#!/usr/bin/env bash
# Builds the self-contained Windows server into dist/app (bundles cloudflared.exe). Run on any machine with the .NET 8 SDK.
set -e
cd "$(dirname "$0")"
rm -rf dist && mkdir -p dist/app
dotnet publish backend -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o dist/app
dotnet publish admin -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o dist/admin
[ -f cloudflared.exe ] || curl -fL -o cloudflared.exe https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe
cp cloudflared.exe dist/app/
echo "Done. On Windows, compile installer/photobooth.iss with Inno Setup to get dist/PhotoboothSetup.exe"
