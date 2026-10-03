#!/usr/bin/env bash
# Build + platform-sign one package under packages/ WITHOUT the Android build system,
# so we can code and test on a prebuilt Cuttlefish image before we have a build host.
#   tools/build-app.sh ICSensorBus      -> out/ICSensorBus.apk
# Signs with AOSP's PUBLIC test platform key, which is what prebuilt userdebug
# Cuttlefish images are signed with. Never put these keys near a real user's phone.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="${1:?usage: build-app.sh <PackageDir>}"
SRC="$ROOT/packages/$PKG"

SDK="${ANDROID_SDK_ROOT:-${LOCALAPPDATA:-$HOME}/Android/Sdk}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
JAR="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar"
EXE=""; [ -f "$BT/aapt2.exe" ] && EXE=".exe"
BAT=""; [ -f "$BT/d8.bat" ] && BAT=".bat"
OUT="$ROOT/out/$PKG"
KEYS="$ROOT/out/keys"
AOSP_SEC="https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security"

mkdir -p "$KEYS"
for f in platform.pk8 platform.x509.pem; do
  [ -s "$KEYS/$f" ] || curl -fsSL "$AOSP_SEC/$f?format=TEXT" | base64 -d > "$KEYS/$f"
done

rm -rf "$OUT" && mkdir -p "$OUT/classes"
javac -source 17 -target 17 -Xlint:-options -classpath "$JAR" -d "$OUT/classes" \
  $(find "$SRC/src" -name '*.java')
"$BT/d8$BAT" --release --min-api 33 --lib "$JAR" --output "$OUT" \
  $(find "$OUT/classes" -name '*.class')
"$BT/aapt2$EXE" link --manifest "$SRC/AndroidManifest.xml" -I "$JAR" -o "$OUT/unsigned.apk"
python - "$OUT/unsigned.apk" "$OUT/classes.dex" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "a", zipfile.ZIP_DEFLATED) as z:
    z.write(sys.argv[2], "classes.dex")
PY
"$BT/zipalign$EXE" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner$BAT" sign --key "$KEYS/platform.pk8" --cert "$KEYS/platform.x509.pem" \
  --out "$ROOT/out/$PKG.apk" "$OUT/aligned.apk"
echo "built out/$PKG.apk"
