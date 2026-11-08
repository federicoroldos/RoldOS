#!/usr/bin/env bash
# Builds RoldOS without Gradle: javac -> d8 -> aapt2 -> zipalign -> apksigner.
# Usage (Git Bash):  ./build.sh            -> build/roldos.apk
#                    ./build.sh install IP:PORT   -> build and install on that device
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
AJ="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar"
JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"
J="$JAVA_HOME/bin"
SRC=app/src/main
OUT=build

rm -rf "$OUT"; mkdir -p "$OUT"/{classes,dex,flat,gen}

echo "[1/5] linking resources (+ generating R.java)"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/flat"
"$BT/aapt2" link -I "$AJ" --manifest "$SRC/AndroidManifest.xml" --java "$OUT/gen" \
    -o "$OUT/unsigned.apk" "$OUT"/flat/*.flat

echo "[2/5] compiling java"
"$J/javac" --release 11 -Xlint:-options -encoding UTF-8 -cp "$AJ" -d "$OUT/classes" \
    $(find "$SRC/java" "$OUT/gen" -name '*.java')

echo "[3/5] dexing"
"$J/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --lib "$AJ" --min-api 24 --release \
    --output "$OUT/dex" $(find "$OUT/classes" -name '*.class') 2>&1 | grep -v '^WARNING' || true
(cd "$OUT/dex" && "$J/jar" uf ../unsigned.apk classes.dex)

echo "[4/5] aligning + signing (debug key)"
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$J/java" -jar "$BT/lib/apksigner.jar" sign --ks "$USERPROFILE/.android/debug.keystore" \
    --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey \
    --out "$OUT/roldos.apk" "$OUT/aligned.apk" 2>&1 | grep -v '^WARNING' || true
rm -f "$OUT/roldos.apk.idsig"

echo "[5/5] done: $OUT/roldos.apk ($(du -k "$OUT/roldos.apk" | cut -f1) KB)"

if [ "${1:-}" = "install" ]; then
    ADB=("$SDK/platform-tools/adb" -s "${2:?device IP:PORT}")
    "${ADB[@]}" install -r "$OUT/roldos.apk"
    # Android turns accessibility services off when an update changes their capabilities,
    # so always re-enable ours (the Home key would otherwise go back to the stock launcher).
    "${ADB[@]}" shell settings put secure enabled_accessibility_services uy.fede.roldos/.HomeKeyService
    "${ADB[@]}" shell settings put secure accessibility_enabled 1
    "${ADB[@]}" shell cmd package set-home-activity uy.fede.roldos/.MainActivity >/dev/null
    echo "home + Home-key service re-enabled"
fi
