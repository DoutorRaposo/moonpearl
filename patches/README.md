# Patches to upstream zelda3

`external/zelda3` stays a pristine checkout of snesrev/zelda3. The build copies its `src/`
and `snes/` folders and applies these patches to the copy, in name order
(`app/src/main/cpp/CMakeLists.txt`). A patch that no longer applies fails the build.

| Patch | Why |
|---|---|
| `0001-fixed-rate-fast-forward.patch` | Upstream turbo is all or nothing (up to 16x). This adds `ZeldaSetSpeed(n)`, which runs *n* game frames per displayed frame with normal pacing, for 2x/3x fast-forward. |
| `0002-frame-hook.patch` | Adds `ZeldaSetFrameHook()`, a callback run on the game thread before each frame. The cheats (`app/src/main/cpp/cheats.c`) use it; the patch itself has no cheat logic. |
| `0003-die-hook.patch` | Adds `ZeldaSetDieHook()`, called with the message before `Die()` exits. Upstream only prints fatal errors; the app saves them and shows them in the launcher. |

Keep them small and in upstream's style, so they can be offered upstream as is.
