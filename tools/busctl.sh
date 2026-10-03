#!/usr/bin/env bash
# Drive the sensor bus on the Cuttlefish phone from this machine.
#   tools/busctl.sh [-H host] grant <package> <stream> <maxRateHz> <event|feature|raw> <ttlSec>
#   tools/busctl.sh revoke <package> [stream|*]
#   tools/busctl.sh pause | resume            kill switch for every stream of every caller
#   tools/busctl.sh keepalive on|off
#   tools/busctl.sh dump [ledger N]           grants, active streams, ledger tail
# On a device, run the same thing directly in `adb shell`:
#   content call --uri content://org.immersivecommons.sensorbus.control --method <m> --arg "<args>"
#   dumpsys activity service org.immersivecommons.sensorbus [ledger N]
set -euo pipefail
HOST=ic13
[ "${1:-}" = "-H" ] && { HOST="$2"; shift 2; }
M="${1:?usage: busctl.sh [-H host] <grant|revoke|pause|resume|keepalive|dump> [args]}"; shift
if [ "$M" = dump ]; then
  CMD="dumpsys activity service org.immersivecommons.sensorbus $*"
else
  CMD="content call --uri content://org.immersivecommons.sensorbus.control --method $M --arg \"'$*'\""
fi
ssh "$HOST" 'ADB="$(ls -td /var/tmp/cvd/1000/*/artifacts/host_tools/bin | head -1)/adb -s 0.0.0.0:6520"; $ADB shell '"$CMD"
