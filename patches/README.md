# Patches to upstream zelda3

`external/zelda3` stays a pristine checkout of snesrev/zelda3. The build copies its `src/`
and `snes/` folders and applies these patches to the copy, in name order
(`app/src/main/cpp/CMakeLists.txt`). A patch that no longer applies fails the build.

| Patch | Why |
|---|---|
| `0001-fixed-rate-fast-forward.patch` | Upstream turbo is all or nothing (up to 16x). This adds `ZeldaSetSpeed(n)`, which runs *n* game frames per displayed frame with normal pacing, for 2x/3x fast-forward. |
| `0002-frame-hook.patch` | Adds `ZeldaSetFrameHook()`, a callback run on the game thread before each frame. The cheats (`app/src/main/cpp/cheats.c`) use it; the patch itself has no cheat logic. |
| `0003-die-hook.patch` | Adds `ZeldaSetDieHook()`, called with the message before `Die()` exits. Upstream only prints fatal errors; the app saves them and shows them in the launcher. |
| `0004-widescreen-edge-sync.patch` | Fixes stale map columns at the left or right edge in widescreen. The original game streams BG2 columns with 16-pixel scroll counters that drift with the scroll history (they reset when the camera stops at an area edge); the 256-pixel view tolerates it, a 448-pixel one does not. Before each drawn frame outdoors, the widescreen-only side areas are rewritten from the map. |
| `0005-sprite-palette-on-state-load.patch` | A save state keeps the palettes loaded when it was made, so after switching Link's sprite its colors only changed at the next palette reload. Re-applies Link's gear palettes (or the bunny palette) after loading a state during gameplay, as the game does after a transformation. |
| `0006-msu-open-hook.patch` | Adds `ZeldaSetMsuOpenHook()` to replace the `fopen()` of MSU-1 track files. On Android the pack is in a folder picked with the system file picker, readable through open descriptors but not by path; the app opens tracks from those descriptors. |
| `0007-opengl-es-output.patch` | Makes upstream's OpenGL output (needed for shaders) work on OpenGL ES: uploads the frame as RGBA and swaps red and blue with a texture swizzle (ES has no `GL_BGRA` upload into an RGBA texture), centers the picture vertically (`viewport_y` was always 0), honors SDL's `overscan` logical size mode to fill the screen, uses ES formats and wrap modes for shader passes, replaces desktop `#version` lines, retries shaders written for GLSL 1.x as GLSL ES 1.00 (as RetroArch does on GLES) and prefers `highp` precision. |
| `0008-live-image-filter.patch` | Adds `ZeldaSetImageFilter()`, which switches the shader (OpenGL output) or the scaling filter (SDL renderer) while the game runs: the game thread applies the change before its next frame and, while paused, redraws the current frame so it shows behind the in-game menu. Also `ZeldaMainLoopCount()`, a heartbeat that keeps counting while paused, so the app can tell a hung shader from a paused game. |
| `0009-rewind-hooks.patch` | For rewinding: `ZeldaSaveSnapshot()`/`ZeldaLoadSnapshot()` give the save state snapshot in memory; `ZeldaSetRewindHook()` lets the host take over the main loop while rewinding (no frames run, audio paused, the loaded point drawn); `ZeldaSetStateJumpHook()` reports loads and resets, so rewinding does not go back across them. The rewind itself is `app/src/main/cpp/rewind.c`. |
| `0010-widescreen-hud.patch` | Optional (`ZeldaSetWidescreenHud()`): in widescreen, draws the HUD's left part (magic, item, counters) at the left edge and the hearts at the right edge, as far from the edges as on the 256-pixel screen. Done at render time on BG3's HUD lines, only in the game modes that show the HUD (not the item menu or maps); the game's logic is untouched. |

Keep them small and in upstream's style, so they can be offered upstream as is.
