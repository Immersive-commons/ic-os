# IC sensor bus v0

`packages/ICSensorBus` is a persistent, platform-signed `/system_ext` priv-app. It is the
only process on IC OS that holds raw sensors; companion apps get **consent-gated streams**
from it over Binder. Why it exists: stock `SensorService` stops delivering to any app uid
that is not active, about 60 s after the app leaves the foreground (measured below). A
persistent priv-app is always active, so the bus keeps receiving, and it decides, per
grant, what each companion may have.

Derived before raw: the bus also computes streams (v0: `activity.motion_state`) so a
companion that only needs "still or moving" never has to be trusted with the accelerometer.

## API (`aidl/org/immersivecommons/sensorbus/`)

Bind: action `org.immersivecommons.sensorbus.BIND`, package `org.immersivecommons.sensorbus`.
A companion targeting SDK 30+ must declare `<queries><package android:name="org.immersivecommons.sensorbus"/></queries>`
or the bind finds nothing.

| Call | Does |
|---|---|
| `StreamInfo[] listStreams()` | The device's stream manifest, discovered from `SensorManager` when the bus starts, plus derived streams. `StreamInfo = {name, kind: sensor\|derived, tier, maxRateHz (0 = on-change), source}` |
| `boolean hasGrant(stream)` | Whether the **calling** package holds an unexpired grant of a sufficient tier |
| `int openStream(stream, rateHz, durationSec, IStreamCallback)` | A handle (> 0) or `NO_GRANT` -1, `UNKNOWN_STREAM` -2, `RATE_ABOVE_GRANT` -3, `PAUSED` -4, `BAD_ARGS` -5 |
| `void closeStream(handle)` | Only the uid that opened a handle can close it |

`IStreamCallback` is `oneway`: `onSample(handle, timestampNs, float[] values)`,
`onState(handle, timestampNs, state)` for event streams, `onClosed(handle, reason)` with
`CLOSED_BY_CLIENT` 1, `CLOSED_EXPIRED` 2, `CLOSED_PAUSED` 3, `CLOSED_REVOKED` 4.

Rules the bus enforces:
- **Identity** is `Binder.getCallingUid()` mapped to packages by `PackageManager`. No call takes a package name.
- **Rate**: `rateHz` above the grant's `maxRateHz` is refused (`RATE_ABOVE_GRANT`), never silently lowered. Above the sensor's own maximum it is clamped. The bus decimates itself, because `SensorManager` runs a sensor at the fastest rate any client asked for.
- **Time**: a stream ends at `min(now + durationSec, grant.expiresAt)`; the bus closes it with `CLOSED_EXPIRED`.
- **Tier**: `event` < `feature` < `raw`. Physical sensors (`motion.accel/gyro/mag`, `env.*`) need `raw`; fused ones (`motion.gravity`, `motion.rotation`, ...) need `feature`; `activity.motion_state` needs `event`.
- A client that dies is cleaned up through `linkToDeath`.

Streams on the Cuttlefish phone (13): `motion.accel`, `motion.gyro`, `motion.mag` (raw, 100 Hz);
`motion.gravity`, `motion.linear_accel`, `motion.rotation`, `motion.game_rotation` (feature, 100 Hz);
`env.pressure` (raw, 100 Hz); `env.light`, `env.temperature`, `env.humidity`, `env.proximity` (raw, on-change);
`activity.motion_state` (derived, event).

`activity.motion_state`: the bus samples the accelerometer at 50 Hz, takes the standard
deviation of |a| over a 1 s window every 0.5 s, and emits `still`/`moving` on change
(enter moving above 0.25 m/s², back to still below 0.15).

## Grants v0

`grants.json` in the bus's **device-protected** data dir
(`/data/user_de/0/org.immersivecommons.sensorbus/files/`, so the bus can read it before the
user unlocks). One row per (package, stream):
`{package, stream, maxRateHz, tier: event|feature|raw, expiresAt (wall ms)}`, written with
`AtomicFile`. Granting the same pair again replaces the row.

Only the shell can change grants in v0 (no UI yet).

## Shell control

Writes go through a `ContentProvider.call()` that refuses every caller except shell
(uid 2000) and root (uid 0). That is the mechanism because `call()` runs on a binder thread
where `Binder.getCallingUid()` is the real caller. (`dumpsys` cannot be used for writes:
the app's `dump()` is invoked by system_server, so the shell's uid is not visible there.)

```bash
C="content call --uri content://org.immersivecommons.sensorbus.control"
adb shell $C --method grant  --arg "'<package> <stream> <maxRateHz> <event|feature|raw> <ttlSec>'"
adb shell $C --method revoke --arg "'<package> [stream|*]'"   # also closes its open streams
adb shell $C --method pause      # KILL SWITCH: closes every stream of every caller; opens return PAUSED
adb shell $C --method resume
adb shell $C --method keepalive --arg off   # see "Background delivery" below
adb shell dumpsys activity service org.immersivecommons.sensorbus [ledger N]
```

`tools/busctl.sh` wraps these for the Cuttlefish host (`tools/busctl.sh grant ...`,
`tools/busctl.sh dump ledger 40`). The paused state persists across reboots (a `paused`
file in the data dir).

## Consent ledger

`ledger.jsonl` beside `grants.json`, append-only, one JSON object per line. Every line has
`t` (wall ms) and `event`:

| event | extra fields |
|---|---|
| `start` | `uptimeSec`, `streams`, `paused` (each time the bus process starts) |
| `grant` | `package`, `stream`, `maxRateHz`, `tier`, `expiresAt`, `by` (shell/root) |
| `revoke` | `package`, `stream`, `grants`, `closed`, `by` |
| `open` | `package`, `uid`, `handle`, `stream`, `rateHz`, `requestedHz`, `tier`, `lifeSec` |
| `deny` | `package`, `uid`, `stream`, `rateHz`, `reason` (NO_GRANT, RATE_ABOVE_GRANT, PAUSED, ...) |
| `close` | `package`, `uid`, `handle`, `stream`, `reason` (client, expired, paused, revoked, client_died), `delivered`, `failed`, `secs`, `avgHz` |
| `pause` / `resume` | `closed`, `by` |

`dumpsys activity service org.immersivecommons.sensorbus` prints the stream manifest,
grants with time left, active streams (delivered, failed, average and last-5 s rate), the
keepalive bindings and the ledger tail.

## Background delivery and the keepalive bind

A companion in the background is not just denied sensors; its process becomes cached and
may be frozen, and a frozen process cannot take the bus's callbacks. So when a companion
opens a stream, the bus binds the companion's own `COMPANION_HOST` service (if it declares
one protected by `org.immersivecommons.permission.BIND_COMPANION`, a signature permission
only the bus holds) with `BIND_NOT_FOREGROUND`, and unbinds when its last stream closes.
That holds the companion at `TRANSIENT_BACKGROUND` (procstate 8): alive and unfrozen, but
still a background uid, so `SensorService` still gives it nothing directly (measured below).

## Boot

`android:persistent="true"` on a priv-app: system_server starts the process during boot and
restarts it if it dies. `BusApplication.onCreate` builds the bus (manifest + ledger `start`
line) and starts `SensorBusService`, so the service record exists for `dumpsys` and
companions bind to an already-running bus. App, service and provider are
`directBootAware`.

## Build and run on the virtual phone

```bash
bash tools/build-app.sh ICSensorBus                 # platform-signed, compiles aidl/
bash tools/cf-deploy.sh ICSensorBus ic13            # /system_ext priv-app + reboot
bash tools/build-app.sh ICCompanionSample           # build.conf: SIGN=debug, AIDL_DIRS=(../ICSensorBus/aidl)
bash tools/cf-install.sh ICCompanionSample ic13     # ordinary adb install
adb shell am start -n org.immersivecommons.companion.sample/.MainActivity --ei durationSec 180
adb logcat -s ICCompanion ICSensorBus
```

`--debug-key` (or `SIGN=debug` in a package's `build.conf`) signs with a generated
`out/keys/debug.jks`, so the companion is an ordinary third-party app with no platform
permissions. `out/` is gitignored.

The sample companion opens `motion.accel` at 100 Hz (expected `RATE_ABOVE_GRANT` against a
50 Hz grant), then at 50 Hz, then `activity.motion_state`, and every 5 s logs the rate it
gets from the bus next to the rate it gets from its **own** `SensorManager` accelerometer
listener at 50 Hz. It also tries the control provider, which must refuse it.

## Measured (Cuttlefish, Android 17 / SDK 37 userdebug, ic13, 2026-10-03 UTC)

| Check | Result |
|---|---|
| Boot start | After a reboot with no manual start: ledger `start` at `uptimeSec: 5`, service up (`dumpsys` shows pid), 13 streams. Seen on every deploy reboot. |
| (a) no grant | `hasGrant` false; all three opens `NO_GRANT`; three `deny` ledger lines |
| control from an app | `content call ... pause` from the companion: `SecurityException: sensor bus control is shell-only (uid 10123)` |
| rate above grant | 100 Hz against a 50 Hz grant: `RATE_ABOVE_GRANT`, ledger `deny` |
| (b) granted, foreground | bus 49.8 to 50.0 Hz per 5 s window; 60 s stream closed `expired` with `delivered: 2999, avgHz: 50` |
| (c) background (HOME) | 180 s stream, HOME at +20 s. Direct `SensorManager` listener: 50 Hz until about 61 s after HOME, then 6.4 Hz, then **0.0 Hz** (`dumpsys sensorservice`: `has sensor access: false`; `background_settle_time=60000`). Bus stream to the same app: **49.9 to 50.2 Hz** throughout, 95 s after HOME, procstate 8 TRANSIENT_BACKGROUND, not frozen, `failed: 0` |
| (d) kill switch | `pause` closed 2 streams (`onClosed` reason 3 in the app, ledger `close` reason `paused`, `delivered: 5783, secs: 115.7, avgHz: 50`), then `pause`; reopening while paused returned `PAUSED` x3 (ledger `deny` reason `PAUSED`); `resume` logged |
| `activity.motion_state` | Emitted `still` within 1 s of opening (1 event). **`moving` not verified**: the Cuttlefish accelerometer is static and nothing here injects motion. |
| keepalive off (control) | Same app, 240 s stream, keepalive off. Procstate 15 (cached); `isFrozen=true` from somewhere between +60 s and +90 s after HOME. From then the app logged **0.1 to 2.6 Hz over ~20 s windows followed by 245 to 251 Hz 5 s bursts**: the bus's oneway calls queue in the frozen process and arrive late in a lump. The bus saw `failed: 0` throughout, so it cannot detect this from its side. The thaws lined up with my 30 s `dumpsys activity processes` polls, so the unpolled behaviour (likely frozen until the stream ends or the binder buffer fills) is **not measured**. With keepalive on (the (c) run) the app stayed procstate 8, `isFrozen=false`, at a steady 50 Hz. |

Not verified yet: the `Android.bp` files have not been through Soong (no build host);
behaviour on real hardware; multi-user; shared-uid callers.
