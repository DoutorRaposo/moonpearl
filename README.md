# Moon Pearl

*A Link to the Past* running natively on Android: a port of
[snesrev/zelda3](https://github.com/snesrev/zelda3), the complete C reimplementation of the
game, with widescreen, save states, fast-forward, cheats and controller support. Bring your
own ROM; the app sets everything up on the device.

<p align="center">
  <img src="docs/screenshots/game.webp" alt="Gameplay in widescreen with the on-screen controls" width="720">
</p>

<p align="center">
  <img src="docs/screenshots/launcher.webp" alt="Launcher" width="200">
  <img src="docs/screenshots/saves.webp" alt="Manage saves" width="200">
</p>

<p align="center">
  <img src="docs/screenshots/menu.webp" alt="In-game menu with save states" width="440">
  <img src="docs/screenshots/cheats.webp" alt="Cheats" width="440">
</p>

## Features

- **Set up in seconds.** Pick your ROM with the system file picker (`.sfc` or `.smc`, zipped
  or not). The app checks it and builds the game data on the device. No PC, no scripts, no
  copying files around. The ROM itself is not kept.
- **Widescreen** up to 18:9, and a *fill the screen* mode that covers the whole display.
- **In-game menu**: 9 save state slots with screenshots, jump to the start of any chapter,
  fast-forward at 2×, 3× or maximum speed, reset. The game is paused while it is open.
- **Resume where you left off.** Progress is saved whenever the app goes to the background,
  so nothing is lost if Android closes it.
- **Touch controls** with multi-touch, an 8-way d-pad, sliding between buttons, haptic
  feedback and adjustable opacity.
- **Controllers** (Xbox, PlayStation, Switch Pro and most others) are detected
  automatically; the touch controls hide while one is in use.
- **Cheats**: infinite health, magic, bombs, arrows and small keys, a full wallet, walking
  through walls, and Pro Action Replay codes.
- **Save management**: see your three game files, erase them, export or restore a backup,
  and move saves to and from SNES emulators as `.srm` files.
- **Enhancements** from the zelda3 project (switch items with L/R, turn while dashing, bug
  fixes and more), each explained in the app. All off by default.
- English and Brazilian Portuguese.

## Installing

1. Download the latest `moonpearl-x.y.z.apk` from
   [Releases](https://github.com/DoutorRaposo/moonpearl/releases) and open it on your
   device. Android asks you to allow installing apps from your browser or file manager the
   first time.
2. Open **Moon Pearl**, tap **Select ROM** and pick your copy of the game.
3. Tap **Play**.

Updates install over the previous version and keep your data. To get them automatically,
add this repository to [Obtainium](https://github.com/ImranR98/Obtainium).

**Requirements:** Android 8.0 or newer on a 64-bit device (practically every phone from 2017
on).

### The ROM

You need the **US release** of *A Link to the Past* for the SNES, dumped from a cartridge you
own:

| | |
|---|---|
| Size | 1 MiB (1,048,576 bytes); a 512-byte copier header is removed automatically |
| CRC32 | `777AAC2F` |

Other regions and translations are not supported: the app tells you if the file is not the
expected one. A ready-made `zelda3_assets.dat` from the PC version also works.

## Controls

| On screen | Controller | What it does |
|---|---|---|
| D-pad, L, R | D-pad, LB, RB | As on the SNES |
| A, B, X, Y | Face buttons by position, as on the SNES: right, bottom, top, left (B, A, Y, X on an Xbox pad) | As on the SNES |
| Start, Select | Start, Back/View | As on the SNES |
| ⋯ button (or double tap, if enabled) | Guide button or R3 | Open the menu |
| | RT / LT | Speed up / slow down |
| Back button | | Open the menu |

In the menu, use the d-pad and A with a controller; B or the guide button closes it.

## Questions

**The game resumed somewhere I did not expect.** With *Resume where you left off* on
(default), the game reopens exactly where you were, not at the file select screen. Use
*Reset* in the menu to go back to the title screen, or turn the option off in the launcher.

**Can I use my saves from an emulator?** Yes. In *Manage saves*, *Import .srm* takes the
save file from Snes9x, RetroArch and similar emulators, and *Export .srm* goes the other way.
Save states are specific to this app.

**Why do Game Genie codes not work?** They change the game's program code, which this
reimplementation does not run. Pro Action Replay codes that change RAM (`7Exxxx:yy`) work.

**The picture is cut at the top and bottom.** That is *Fill the screen* on a phone wider
than 2:1. Turn it off in the launcher to see the whole picture with bars at the sides.

**I moved from the old "Z3" app.** Moon Pearl is a separate app. In the old one, use
*Manage saves → Export backup*; in Moon Pearl, select your ROM and then *Restore backup*.

**Something went wrong.** If the game closes on an error, the launcher shows the message.
Please [open an issue](https://github.com/DoutorRaposo/moonpearl/issues) with it, your
device, Android version and controller.

## Building

```sh
git clone --recursive https://github.com/DoutorRaposo/moonpearl
cd moonpearl
./gradlew assembleDebug
```

You need JDK 17+ and the Android SDK; Gradle installs the NDK and CMake versions it needs.
The APK ends up in `app/build/outputs/apk/debug/`. Debug builds install next to release
builds, as *Moon Pearl debug*.

### Release builds

Release APKs are signed with the key named in `keystore.properties` at the repository root
(not tracked by git):

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

CI reads the same values from the `MOONPEARL_KEYSTORE*` / `MOONPEARL_KEY*` environment
variables. Pushing a tag such as `v0.5.0` runs `.github/workflows/release.yml`, which builds
the signed APK (version taken from the tag) and publishes a GitHub release. It needs the
repository secrets `MOONPEARL_KEYSTORE_BASE64`, `MOONPEARL_KEYSTORE_PASSWORD`,
`MOONPEARL_KEY_ALIAS` and `MOONPEARL_KEY_PASSWORD`.

## How it fits together

zelda3 is pinned as a git submodule and left untouched. The few changes the game code needs
are small patches in `patches/zelda3`, applied to a copy at build time (see
[patches/README.md](patches/README.md)).

| Piece | Where |
|---|---|
| Game code | `external/zelda3` (submodule) plus `patches/zelda3` |
| SDL 2.32 (native and Java) | `external/SDL` (submodule) |
| Native entry point: runs the game from the app's data folder, logs to logcat, reports fatal errors | `app/src/main/cpp/android_main.c` |
| Cheats, applied before every frame | `app/src/main/cpp/cheats.c` |
| ROM import: applies upstream's `zelda3_assets.bps` to the ROM | `GameData.kt`, `Bps.kt` |
| Launcher and settings (stored in the stock `zelda3.ini`) | `LauncherActivity.kt` |
| Game host, in its own `:game` process since upstream keeps its state in C globals | `GameActivity.kt` |
| In-game menu; drives upstream's own pause, save state and turbo shortcuts | `GameMenuActivity.kt` |
| Save files and backups | `SaveManager.kt`, `Sram.kt`, `SavesActivity.kt` |
| On-screen controls | `TouchControlsView.kt` |

Upstream binds Select to Right Shift. A held modifier turns other keys into Shift+key, which
would leave buttons stuck under multi-touch, so the app maps every pad button to a plain key
in `[KeyMap] Controls`.

## Credits

- [snesrev](https://github.com/snesrev) and the contributors of
  [zelda3](https://github.com/snesrev/zelda3), whose reimplementation this app runs.
- [SDL](https://www.libsdl.org), Opus and stb; see
  [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt), also shown in the app.

## Legal

This project contains no game data. You must supply a ROM dumped from a cartridge you own.
*The Legend of Zelda* is a trademark of Nintendo; this project is not affiliated with or
endorsed by Nintendo.

The Android code in this repository is MIT licensed (see [LICENSE](LICENSE)).
