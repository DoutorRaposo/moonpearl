# zelda3-android

A native Android port of [snesrev/zelda3](https://github.com/snesrev/zelda3), the C
reimplementation of *A Link to the Past*.

The upstream sources are used **unmodified** (pinned as a git submodule). Everything
Android-specific lives in this repository: a small native entry point, the launcher, the
on-screen controls and the build.

## Features

- **In-app setup.** Pick your ROM (`.sfc`/`.smc`, zipped or not) with the system file
  picker. The app checks it and builds `zelda3_assets.dat` on the device by applying the
  `zelda3_assets.bps` patch published with upstream v0.3. No Python, no PC, no copying files
  into `Android/data`. The ROM itself is not kept.
- **Widescreen** up to 18:9, picked automatically for the device, plus an optional
  *fill the screen* mode that scales the picture to cover the whole display (it hides a few
  lines at the top and bottom on screens wider than 2:1).
- **Touch controls** with multi-touch, 8-way d-pad, sliding between face buttons, haptic
  feedback and adjustable opacity. They hide while a controller is in use.
- **Controllers** through SDL2 (Xbox, PlayStation, Switch Pro and generic pads).
- **Resume where you left off.** A save state is written whenever the app goes to the
  background or you quit, and loaded on the next start, so nothing is lost if Android
  closes the app while it is in the background.
- Settings screen for upstream's display options and gameplay enhancements (item switching
  on L/R, turning while dashing, bug fixes, and so on). Everything is stored in the stock
  `zelda3.ini`, so upstream's documentation still applies.
- English and Brazilian Portuguese UI.

## Requirements

- Android 8.0 (API 26) or newer, arm64 or x86_64.
- Your own dump of the US release: CRC32 `777AAC2F`, 1 MiB (a 512-byte copier header is
  stripped automatically). A ready-made `zelda3_assets.dat` is accepted as well.

## Building

```sh
git clone --recursive https://github.com/DoutorRaposo/zelda3-android
cd zelda3-android
./gradlew assembleDebug
```

You need JDK 17+ and the Android SDK; Gradle installs the NDK and CMake versions it
needs. The APK ends up in `app/build/outputs/apk/debug/`.

### Release builds

Release APKs are signed with the key named in `keystore.properties` at the repository root
(not tracked by git):

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

CI reads the same values from the `ZELDA3_KEYSTORE*` / `ZELDA3_KEY*` environment
variables. Pushing a tag such as `v0.2.0` runs `.github/workflows/release.yml`, which
builds the signed APK (version taken from the tag) and publishes a GitHub release. It needs
the repository secrets `ZELDA3_KEYSTORE_BASE64`, `ZELDA3_KEYSTORE_PASSWORD`,
`ZELDA3_KEY_ALIAS` and `ZELDA3_KEY_PASSWORD`.

## How it fits together

| Piece | Where |
|---|---|
| Game code (unmodified) | `external/zelda3` (submodule) |
| SDL 2.32 (native and Java) | `external/SDL` (submodule) |
| Native entry point: moves into the app's data folder, sends stdio to logcat, locks landscape | `app/src/main/cpp/android_main.c` |
| ROM import and BPS patching | `GameData.kt`, `Bps.kt` |
| Launcher and settings | `LauncherActivity.kt` |
| Game host (runs in its own `:game` process, since upstream state lives in C globals) | `GameActivity.kt` |
| On-screen controls | `TouchControlsView.kt` |

Upstream binds Select to Right Shift. A held modifier turns other keys into Shift+key,
which would leave buttons stuck under multi-touch, so the app maps every pad button to a
plain key in `[KeyMap] Controls`.

## Legal

This project contains no game data. You must supply a ROM dumped from a cartridge you own.
*The Legend of Zelda* is a trademark of Nintendo; this project is not affiliated with or
endorsed by Nintendo.

The Android code in this repository is MIT licensed (see `LICENSE`). zelda3 and SDL keep
their own licenses (both permissive) in their submodules.
