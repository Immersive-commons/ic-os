#!/usr/bin/env bash
# Install a built package into the Cuttlefish virtual phone as an ORDINARY app
# (adb install, /data/app), the way a member would sideload a companion.
#   tools/cf-install.sh ICCompanionSample [host]     (host defaults to ic13)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="${1:?usage: cf-install.sh <PackageDir> [host]}"
HOST="${2:-ic13}"

scp -q "$ROOT/out/$PKG.apk" "$HOST:/tmp/$PKG.apk"
ssh "$HOST" 'ADB="$(ls -td /var/tmp/cvd/1000/*/artifacts/host_tools/bin | head -1)/adb -s 0.0.0.0:6520"; '"\$ADB install -r /tmp/$PKG.apk"
