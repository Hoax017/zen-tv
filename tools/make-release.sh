#!/usr/bin/env bash
# Packages what end users need into dist/zen-tv.zip (builds the APK first if missing).
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f dist/zen-home.apk ] || ./launcher/build.sh
rm -rf dist/zen-tv dist/zen-tv.zip
mkdir -p dist/zen-tv/dist
cp -R zen.sh zen.ps1 zen.bat profiles README.md README.fr.md LICENSE dist/zen-tv/
cp dist/zen-home.apk dist/zen-tv/dist/
chmod +x dist/zen-tv/zen.sh
(cd dist && zip -qr zen-tv.zip zen-tv)
rm -rf dist/zen-tv
echo "OK -> $(pwd)/dist/zen-tv.zip"
