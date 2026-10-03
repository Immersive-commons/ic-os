#!/usr/bin/env bash
# Build + sign one package under packages/ WITHOUT the Android build system,
# so we can code and test on a prebuilt Cuttlefish image before we have a build host.
#   tools/build-app.sh ICSensorBus                   -> out/ICSensorBus.apk (platform key)
#   tools/build-app.sh ICCompanionSample --debug-key -> out/ICCompanionSample.apk
# Platform signing uses AOSP's PUBLIC test platform key, which is what prebuilt userdebug
# Cuttlefish images are signed with. Never put these keys near a real user's phone.
# --debug-key signs with a locally generated key (out/keys/debug.jks), i.e. an ordinary
# third-party app that gets no platform permissions. All keys live under out/ (gitignored).
#
# AIDL: every .aidl under packages/<Pkg>/aidl is compiled, plus the dirs listed in
# packages/<Pkg>/build.conf as AIDL_DIRS=(relative/to/package ...). build.conf may also
# set SIGN=debug so the flag is not needed.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="${1:?usage: build-app.sh <PackageDir> [--debug-key]}"
SRC="$ROOT/packages/$PKG"
AIDL_DIRS=()
SIGN=platform
[ -f "$SRC/build.conf" ] && . "$SRC/build.conf"
[ "${2:-}" = "--debug-key" ] && SIGN=debug

SDK="${ANDROID_SDK_ROOT:-${LOCALAPPDATA:-$HOME}/Android/Sdk}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
JAR="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar"
EXE=""; [ -f "$BT/aapt2.exe" ] && EXE=".exe"
BAT=""; [ -f "$BT/d8.bat" ] && BAT=".bat"
OUT="$ROOT/out/$PKG"
KEYS="$ROOT/out/keys"
AOSP_SEC="https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security"
# native Windows tools need Windows paths; elsewhere this is a no-op
winpath() { if command -v cygpath >/dev/null; then cygpath -w "$1"; else echo "$1"; fi; }

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/gen"

dirs=()
[ -d "$SRC/aidl" ] && dirs+=("$SRC/aidl")
for d in "${AIDL_DIRS[@]}"; do dirs+=("$(cd "$SRC/$d" && pwd)"); done
if [ ${#dirs[@]} -gt 0 ]; then
  inc=(); for d in "${dirs[@]}"; do inc+=("-I$(winpath "$d")"); done
  for d in "${dirs[@]}"; do
    find "$d" -name '*.aidl' | while read -r f; do
      "$BT/aidl$EXE" --lang=java "${inc[@]}" -o"$(winpath "$OUT/gen")" "$(winpath "$f")"
    done
  done
fi

javac -source 17 -target 17 -Xlint:-options -classpath "$JAR" -d "$OUT/classes" \
  $(find "$SRC/src" "$OUT/gen" -name '*.java')
"$BT/d8$BAT" --release --min-api 33 --lib "$JAR" --output "$OUT" \
  $(find "$OUT/classes" -name '*.class')
"$BT/aapt2$EXE" link --manifest "$SRC/AndroidManifest.xml" -I "$JAR" -o "$OUT/unsigned.apk"
python - "$OUT/unsigned.apk" "$OUT/classes.dex" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "a", zipfile.ZIP_DEFLATED) as z:
    z.write(sys.argv[2], "classes.dex")
PY
"$BT/zipalign$EXE" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

mkdir -p "$KEYS"
if [ "$SIGN" = platform ]; then
  for f in platform.pk8 platform.x509.pem; do
    [ -s "$KEYS/$f" ] || curl -fsSL "$AOSP_SEC/$f?format=TEXT" | base64 -d > "$KEYS/$f"
  done
  "$BT/apksigner$BAT" sign --key "$KEYS/platform.pk8" --cert "$KEYS/platform.x509.pem" \
    --out "$ROOT/out/$PKG.apk" "$OUT/aligned.apk"
else
  [ -s "$KEYS/debug.jks" ] || keytool -genkeypair -keystore "$KEYS/debug.jks" \
    -storepass android -keypass android -alias icdebug -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=IC Companion Debug" >/dev/null
  "$BT/apksigner$BAT" sign --ks "$KEYS/debug.jks" --ks-pass pass:android \
    --out "$ROOT/out/$PKG.apk" "$OUT/aligned.apk"
fi
echo "built out/$PKG.apk ($SIGN key)"
