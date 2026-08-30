# android-tiling-wm (ATWM)

An i3/Hyprland-style tiling window manager for Android phones. No root required.

> ## ⚠️ Status: work-in-progress / proof-of-concept
>
> This is an experiment that has reached "demonstrably works on one device."
> Nothing about the interface — the taskbar, the settings, the layouts, the
> setup flow — is set in stone. Expect breaking changes between releases,
> expect rough edges, and expect Android platform updates to break the
> privileged internals from time to time (Android 17 already did, twice).
> Issues and feedback are welcome; stability promises are not yet on offer.

## What it does

Android's freeform windowing mode (hidden behind developer settings) can put
ordinary apps in arbitrarily positioned, arbitrarily sized windows on the
phone's primary display. This project drives that capability like a desktop
tiling WM:

- **Automatic tiling** — when tiling is active, visible apps are forced into
  freeform mode and snapped into a layout (no manual dragging)
- **Layouts**: Master+stack, Rows/Columns, Monocle — switchable from the taskbar
- **Overlay taskbar** — pinned apps, folders, running-app indicators, app
  drawer with search, settings panel (gaps, master ratio, accent color)
- **Launcher mode** — optionally set it as your HOME app: transparent
  wallpaper home screen with the taskbar as the whole interface

Under the hood: [Shizuku](https://shizuku.rikka.app/) provides privileged
access to `IActivityTaskManager` (task listing, freeform re-moding, window
resizing) and an AccessibilityService provides window-change events that
trigger re-tiling. Nothing needs root; Shizuku is started over ADB (wireless
pairing works).

## Current state (v0.1.0, tested 2026-08-30)

Works on a **Pixel 10 Pro XL running Android 17 (API 37)**: apps tile into
full-width rows in portrait (side-by-side in landscape), the taskbar, app
drawer, folders and settings panel all function, and layout switching works
live.

Known limitations, in honesty:

- **Minimum screen size matters.** Android refuses real freeform windowing on
  small displays. On a Pixel 7a this project degrades to split-screen-like
  behavior; on the larger Pixel 10 Pro XL it works. If your phone is small,
  this probably won't work for you.
- **No window chrome.** On the tested device/build, freeform windows render
  without caption bars or grabbable borders — windows are only controllable
  through the tiling engine, not by touch-dragging.
- **Hidden-API dependent.** The privileged layer talks to unstable Android
  internals via reflection. OS updates can and do break it (see
  `WindowTilingServiceImpl.kt` for the Android 17 fixes). Failures are logged
  under the `ATWM-Shizuku` / `ATWM-Tiling` logcat tags.
- Tested on exactly one device and one Android build. Reports from other
  devices are genuinely useful.

## Install & setup

1. Install [Shizuku](https://shizuku.rikka.app/) and start it (ADB method).
2. Install the ATWM APK (grab it from the
   [releases page](https://github.com/Aypex/android-tiling-wm/releases), or
   build with `./gradlew assembleDebug`).
3. Enable freeform windowing (once, persists across reboots):
   ```bash
   adb shell settings put global enable_freeform_support 1
   adb shell settings put global force_resizable_activities 1
   ```
4. Open **Tiling WM**, follow the permission flow (overlay → notifications →
   Shizuku), then enable the accessibility service when prompted.

   > If you enable the accessibility service via `adb shell settings put`
   > instead of the Settings UI, **append** to the existing
   > `enabled_accessibility_services` value — overwriting it disables every
   > other accessibility service on the device.
5. Tap **Start Tiling**. Launch a couple of apps and watch them snap.

To try launcher mode: Settings → Apps → Default apps → Home app → Tiling WM.
(Reversible the same way.)

## Shell-script proof of concept (historical)

The project started as an ADB-driven shell script, kept at
[`scripts/tile.sh`](scripts/tile.sh). It still works as a zero-install demo
and as reference for the `am`/`dumpsys`/`wm` command surface:

```bash
./scripts/tile.sh setup                        # enable freeform settings
./scripts/tile.sh launch com.android.settings  # launch apps in freeform
./scripts/tile.sh launch com.android.chrome
./scripts/tile.sh tile                         # tile them
./scripts/tile.sh layout columns               # switch layout
./scripts/tile.sh reset                        # back to fullscreen
```

## Roadmap (loose, subject to change)

- Grabbable window borders / manual resize affordances (needs investigation —
  the platform doesn't draw chrome on the tested build)
- Keyboard shortcut and gesture control
- Per-app floating exceptions
- More layouts (BSP, spiral)
- Widget hosting and notification badges in launcher mode

## Technical details

- [`docs/architecture-phase1.md`](docs/architecture-phase1.md) — app architecture
- [`docs/research-spec.md`](docs/research-spec.md) — original research: AOSP
  freeform internals, Shizuku API surface, known gotchas

## License

Apache 2.0
