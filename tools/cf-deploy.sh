#!/usr/bin/env bash
# Install a built package into the Cuttlefish virtual phone as a /system_ext priv-app,
# the same place Android.bp puts it in a real build, then reboot.
#   tools/cf-deploy.sh ICSensorBus [host]     (host defaults to ic13)
# First run on a fresh instance also disables verity (one extra reboot).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="${1:?usage: cf-deploy.sh <PackageDir> [host]}"
HOST="${2:-ic13}"

scp -q "$ROOT/out/$PKG.apk" "$HOST:/tmp/$PKG.apk"
scp -q "$ROOT/permissions/privapp-permissions-ic.xml" "$HOST:/tmp/privapp-permissions-ic.xml"
scp -q "$ROOT/tools/cf-install-remote.sh" "$HOST:/tmp/cf-install-remote.sh"
ssh "$HOST" "bash /tmp/cf-install-remote.sh $PKG"
