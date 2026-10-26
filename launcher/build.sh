#!/usr/bin/env bash
# Builds Zen Home without Gradle: wallpapers -> aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs: Android SDK (build-tools + a platform >= 30), JDK 11+, Python 3 with Pillow.
# Output: ../dist/zen-home.apk
#
# Signing: set ZEN_KEYSTORE / ZEN_KEYSTORE_PASS / ZEN_KEY_ALIAS / ZEN_KEY_PASS for a release key,
# otherwise the Android debug key is used (created if missing).
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
[ -d "$SDK" ] || { echo "Android SDK not found (set ANDROID_HOME)"; exit 1; }
BT=$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)
JAR=$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)
OUT=build
DIST=../dist
echo "build-tools: $BT"
echo "platform:    $JAR"

if [ -z "$(ls assets/wallpapers/*.jpg 2>/dev/null)" ]; then
  echo "Generating wallpapers…"
  python3 -I tools/gen_wallpapers.py assets/wallpapers
fi
[ -f res/drawable/banner.png ] || python3 -I tools/gen_icons.py res/drawable

rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$DIST"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -A assets \
  --min-sdk-version 24 --target-sdk-version 30 --version-code "${ZEN_VERSION_CODE:-1}" \
  --version-name "${ZEN_VERSION:-1.0.0}" --java "$OUT/gen" -o "$OUT/unsigned.apk" "$OUT/res.zip"
javac -nowarn --release 11 -classpath "$JAR" -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java')
"$BT/d8" --lib "$JAR" --min-api 24 --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ -n "${ZEN_KEYSTORE:-}" ]; then
  "$BT/apksigner" sign --ks "$ZEN_KEYSTORE" --ks-pass "pass:$ZEN_KEYSTORE_PASS" \
    --ks-key-alias "$ZEN_KEY_ALIAS" --key-pass "pass:$ZEN_KEY_PASS" --out "$DIST/zen-home.apk" "$OUT/aligned.apk"
else
  KS=$HOME/.android/debug.keystore
  [ -f "$KS" ] || { mkdir -p "$(dirname "$KS")"; keytool -genkeypair -keystore "$KS" -storepass android \
    -keypass android -alias androiddebugkey -keyalg RSA -validity 10000 -dname "CN=Android Debug,O=Android,C=US"; }
  "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$DIST/zen-home.apk" "$OUT/aligned.apk"
fi
rm -f "$DIST/zen-home.apk.idsig"
echo "OK -> $(cd "$DIST" && pwd)/zen-home.apk"
