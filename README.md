# zelda3-android

A native Android port of [snesrev/zelda3](https://github.com/snesrev/zelda3), the C
reimplementation of *A Link to the Past*.

Upstream is pinned as a git submodule and left untouched. Android-specific code lives in
this repository: a small native entry point, the launcher, the menu, the on-screen controls
and the build. The few changes the game code needs are kept as small patches in
`patches/zelda3` and applied at build time (see [patches/README.md](patches/README.md)).

## Features

- **In-app setup.** Pick your ROM (`.sfc`/`.smc`, zipped or not) with the system file
  picker. The app checks it and builds `zelda3_assets.dat` on the device by applying the
  `zelda3_assets.bps` patch published with upstream v0.3. No Python, no PC, no copying files
  into `Android/data`. The ROM itself is not kept.
- **Widescreen** up to 18:9, plus a *fill the screen* mode (on by default) that picks the
  widest mode for the device and scales the picture to cover the whole display, hiding a few
  lines at the top and bottom on screens wider than 2:1.
- **Touch controls** with multi-touch, 8-way d-pad, sliding between face buttons, haptic
  feedback and adjustable opacity. They hide while a controller is in use.
- **In-game menu** with save states (9 slots plus the resume point, each with a screenshot
  and time), fast-forward at 2x, 3x or maximum speed, jump to any chapter (upstream's reference saves), reset and quit.
  It opens from an on-screen "⋯" button, an optional double tap, the back button, or the
  guide button, Select+Start or the right stick button on a controller, and is fully usable
  with a controller. On a controller, RT and LT step the speed up and down. Under the hood it drives upstream's own save state and turbo shortcuts.
- **Cheats**: infinite health, magic, bombs, arrows and small keys, a full wallet, walking
  through walls, and Pro Action Replay RAM codes (`7Exxxx:yy`). The game RAM keeps the SNES
  layout, so classic RAM codes work unchanged; ROM codes such as Game Genie do not apply.
- **Enhancements** from upstream (item switching on L/R, turning while dashing, bug fixes and
  more), each with a short explanation of what it changes.
- **Save management**: see the three game files (name and hearts) and every save state,
  erase a single file or all of them,
  export or restore everything as a `.zip`, and import or export the game files as an `.srm`.
  The save RAM uses the cartridge layout, so files move both ways between this app and SNES
  emulators.
- **Controllers** through SDL2 (Xbox, PlayStation, Switch Pro and generic pads).
- **Resume where you left off.** A save state is written whenever the app goes to the
  background or you quit, and loaded on the next start, so nothing is lost if Android
  closes the app while it is in the background.
- Settings screen for upstream's display options and gameplay enhancements (item switching
  on L/R, turning while dashing, bug fixes, and so on). Everything is stored in the stock
  `zelda3.ini`, so upstream's documentation still applies.
- English and Brazilian Portuguese UI, following the system or chosen in the app.

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
| Game code | `external/zelda3` (submodule) plus `patches/zelda3` |
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
