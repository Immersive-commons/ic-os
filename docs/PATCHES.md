# Fork patches

Every change IC makes to upstream code, one row each. Keep this list short:
the more we patch, the more each upstream security release costs us to rebase.
Prefer a system app in `vendor/ic` over a framework patch whenever it can do the job.

Forks (branch `ic-23.2`, based on LineageOS `lineage-23.2`):
- https://github.com/immersive-commons/android_frameworks_base
- https://github.com/immersive-commons/android_frameworks_native

| # | Repo | Change | Why | Status |
|---|---|---|---|---|
| 1 | frameworks_base | `HIGH_SAMPLING_RATE_SENSORS` protection level `normal` → `dangerous` (`core/res/AndroidManifest.xml`) | Stock Android grants >200 Hz motion data to any app that asks; at those rates an accelerometer works as a crude microphone. The person should decide. | Written, not yet built |
| — | frameworks_native | Let the sensor bus read sensors in the background | **Not needed.** `SensorService::UidPolicy::isUidActiveLocked` (SensorService.cpp) cuts off non-active app uids, but a persistent priv-app is always active: measured ~100 Hz with no UI on Cuttlefish, 2026-10-02. | Dropped |

## Candidate patches (not started)

- Attention broker: rank and ration notifications OS-wide (`services/core/.../notification/`).
- IC permission tiers for companion streams: new `org.immersivecommons.permission.*`
  declared by the sensor bus itself, so no framework patch is required unless the
  Settings UI must show them beside system permissions.
- Capture indicator per companion (SystemUI privacy chip).
