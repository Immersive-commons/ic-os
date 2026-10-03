#!/usr/bin/env bash
# Device-side half of cf-deploy.sh; runs ON the Cuttlefish host. Copied over and
# executed as a file because adb shell would swallow a script piped over ssh stdin.
PKG="${1:?package dir}"
set -euo pipefail
ADB="$(ls -td /var/tmp/cvd/1000/*/artifacts/host_tools/bin | head -1)/adb -s 0.0.0.0:6520"
waitboot() { $ADB wait-for-device; for _ in $(seq 1 60); do
  [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && return 0; sleep 5; done; return 1; }
$ADB root >/dev/null; sleep 2
if ! $ADB remount >/dev/null 2>&1; then
  $ADB disable-verity >/dev/null; $ADB reboot; sleep 10; waitboot
  $ADB root >/dev/null; sleep 2; $ADB remount >/dev/null
fi
# earlier experiments installed into /system; two copies of one package break boot
$ADB shell rm -rf "/system/priv-app/$PKG" /system/etc/permissions/privapp-permissions-ic.xml
$ADB shell mkdir -p "/system_ext/priv-app/$PKG" /system_ext/etc/permissions
$ADB push "/tmp/$PKG.apk" "/system_ext/priv-app/$PKG/$PKG.apk" >/dev/null
$ADB push /tmp/privapp-permissions-ic.xml /system_ext/etc/permissions/ >/dev/null
$ADB shell chmod 644 "/system_ext/priv-app/$PKG/$PKG.apk" /system_ext/etc/permissions/privapp-permissions-ic.xml
$ADB reboot; sleep 10; waitboot
$ADB shell pm list packages -f | grep "/priv-app/$PKG/" || { echo "deploy FAILED: $PKG not registered"; exit 1; }
