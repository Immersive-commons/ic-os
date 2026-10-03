# IC OS

An agentic Android fork by [Immersive Commons](https://www.immersivecommons.com):
the phone as a remote for a resident AI agent, with a sensor bus and a Companion SDK
so members can build AI companions on real hardware data, behind a consent gate.

This repo is the **vendor layer** (checked out at `vendor/ic`). It sits on top of
LineageOS (builder track, Snapdragon phones with an open NPU) and later GrapheneOS
(hardened track). Upstream changes live in our forks and are listed in
[`docs/PATCHES.md`](docs/PATCHES.md); everything else is here.

```
config/common.mk               products inherit this
manifest/local_manifests/ic.xml  adds vendor/ic + our framework forks to a LineageOS checkout
packages/ICSensorBus/           the only app that holds raw sensors; serves consent-gated streams
packages/ICCompanionSample/     an ordinary app that gets streams from the bus
permissions/                    privileged-permission allowlists
tools/build-app.sh              build + sign one package without the Android build system
tools/cf-deploy.sh              install it into a Cuttlefish virtual phone as a priv-app
tools/cf-install.sh             install it as an ordinary app (adb install)
tools/busctl.sh                 grant / revoke / pause / resume / dump the bus on the phone
docs/SENSOR_BUS.md              sensor bus API, grants, ledger, shell commands, measurements
docs/PATCHES.md                 every upstream change, why, and its status
```

## Develop without building Android

We do not have a build host yet, so packages are built standalone and tested on a
prebuilt AOSP userdebug Cuttlefish phone (Android 17). Those images are signed
with AOSP's public test platform key, so an app signed with it gets platform
permissions there.

```bash
tools/build-app.sh ICSensorBus             # needs JDK 17+ and the Android SDK build-tools
tools/cf-deploy.sh ICSensorBus ic13        # ssh host running the Cuttlefish phone
tools/build-app.sh ICCompanionSample       # debug-signed: an ordinary third-party app
tools/cf-install.sh ICCompanionSample ic13
```

Virtual phone runbook and measured results: `life/projects/ic-os/README.md` (IC fleet).

## Full build (later)

```bash
repo init -u https://github.com/LineageOS/android.git -b lineage-23.2 --git-lfs
mkdir -p .repo/local_manifests
curl -o .repo/local_manifests/ic.xml https://raw.githubusercontent.com/immersive-commons/ic-os/main/manifest/local_manifests/ic.xml
repo sync -c -j8
```

Needs ~64 GB RAM and ~400 GB disk.

Design spec: https://claude.ai/code/artifact/9aebaeab-fcc9-4f66-a4de-4f174f2f3ef2
