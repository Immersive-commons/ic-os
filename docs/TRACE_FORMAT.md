# IC trace format (`ic-trace/1`)

A trace is real-life sensor data recorded on a member's current phone, replayed
into the IC OS sensor bus so companions can be developed and tested on the
virtual phone (and on real phones) against the same day, repeatably.

Recorder: `tools/trace-recorder/index.html`, a single web page. It runs in the
phone browser (iPhone Safari or Android Chrome), needs no app, and saves a file the
member chooses to share. It must be served over HTTPS (browsers expose motion and
location only to secure pages).

## File

JSON Lines. First line is a header, last line a footer, everything between is samples.

```json
{"header":true,"format":"ic-trace/1","started_at":"2026-10-02T23:59:00.000Z","note":"walking to the tower","user_agent":"...","platform_hint":"ios","units":{...}}
{"t_ns":16000000,"stream":"motion.accel","v":[0.12,9.71,0.33]}
{"t_ns":16000000,"stream":"motion.gyro","v":[0.01,-0.02,0.00]}
{"t_ns":17000000,"stream":"location.fix","v":[37.7881,-122.3999,12.0],"accuracy_m":5,"speed_mps":1.3}
{"footer":true,"duration_s":312.4,"motion_events":18744,"location_fixes":290}
```

| Field | Meaning |
|---|---|
| `t_ns` | Nanoseconds since the recording started (page clock). The bus replays on its own clock. |
| `stream` | Sensor bus stream name. |
| `v` | Values in the units the header declares. |

## Streams and units

| Stream | Units | Source in the browser |
|---|---|---|
| `motion.accel` | m/s², gravity included, W3C device frame | `DeviceMotionEvent.accelerationIncludingGravity` |
| `motion.gyro` | rad/s (converted from the W3C deg/s) | `DeviceMotionEvent.rotationRate` |
| `motion.orientation_deg` | degrees: alpha, beta, gamma | `DeviceOrientationEvent` |
| `location.fix` | latitude, longitude (deg), altitude (m) | `Geolocation.watchPosition` |

## Known limits (not yet verified on devices)

- **Rate:** browsers deliver motion at about 60 Hz, so a trace cannot exercise the
  >200 Hz path. That stays a hardware test.
- **Sign conventions:** the W3C device frame matches Android's sensor frame on paper,
  but browsers have historically differed on the sign of `accelerationIncludingGravity`
  (iOS vs Android). The header's `platform_hint` and `user_agent` are kept so the replay
  source can normalise; confirm with a face-up-on-a-table recording from each platform
  (expect z ≈ +9.8 on Android).
- **Background:** phones pause motion events when the screen locks; the recorder holds a
  screen wake lock where the browser supports it.

## Replay

- **Location:** the Cuttlefish host can already inject fixes (`cvd_update_location`,
  `cvd_import_locations`, and the `GnssGrpcProxy` control service).
- **Motion:** Cuttlefish has no remote motion-injection service, so motion replay is a
  **replay source inside the sensor bus**: on a debug build the bus can feed a stream from a
  trace file instead of hardware. Planned after sensor bus v0 lands.
