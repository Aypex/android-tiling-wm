# android-tiling-wm

An i3/Hyprland-style tiling window manager for Android 16+ phones. No root required.

## What is this?

Android 16 supports freeform windowing on the phone's primary display — complete with native caption bars (title bar, minimize/maximize/close, drag-to-move, resize handles). This project uses that capability to tile app windows automatically, like a desktop tiling WM.

**Key discovery:** Android 16 renders native window chrome on freeform windows on display 0. No LSPosed hooks, no custom ROM, no root. Just freeform mode + window resizing.

## Current State

**Proof of concept** — an ADB-based shell script (`tile.sh`) that demonstrates tiling works. Tested on Pixel 7a running Android 16 (API 36).

The roadmap is a standalone Android app using Shizuku for privileged window management + AccessibilityService for window event tracking.

## Quick Start

### Prerequisites

- Android 16+ device
- ADB connected via USB or WiFi
- `adb` in your PATH

### Setup

```bash
# Enable freeform windowing on the device
./scripts/tile.sh setup

# Launch some apps in freeform mode
./scripts/tile.sh launch com.android.settings
./scripts/tile.sh launch com.android.chrome
./scripts/tile.sh launch com.google.android.apps.messaging

# Tile them
./scripts/tile.sh tile
```

### Commands

| Command | Description |
|---------|-------------|
| `tile.sh setup` | Enable freeform windowing settings on device |
| `tile.sh launch <pkg>` | Launch an app in freeform mode |
| `tile.sh tile` | Tile all visible freeform windows |
| `tile.sh list` | List visible freeform tasks |
| `tile.sh layout <name>` | Switch layout (master-stack, columns, monocle) |
| `tile.sh close <taskId>` | Close a task and re-tile remaining |
| `tile.sh reset` | Return all tasks to fullscreen |
| `tile.sh screenshot` | Capture and pull screenshot to /tmp/ |

### Layouts

- **master-stack** (default) — One master pane on the left (55% width), remaining apps stacked vertically on the right
- **columns** — Equal-width vertical columns
- **monocle** — All windows fullscreen, last one on top

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `TILE_LAYOUT` | `master-stack` | Default layout |
| `TILE_MASTER_RATIO` | `55` | Master pane width as percentage |

## How It Works

1. `am start --windowingMode 5` launches apps in freeform mode on the phone screen
2. `am task resize <taskId> <left> <top> <right> <bottom>` positions each window
3. Android 16 automatically renders native caption bars (drag handle, minimize, maximize, close) on freeform windows

That's it. The window management is built into Android — we're just driving it.

## Roadmap

### Phase 1: Tiling Engine (Standalone App)
- [ ] Shizuku UserService with `resizeTask()` + `getTasks()` + `setTaskWindowingMode()`
- [ ] AccessibilityService for window open/close/focus events
- [ ] Master+stack layout algorithm
- [ ] Event pipeline: accessibility event → debounce → get tasks → calculate layout → resize

### Phase 2: Taskbar Fork Integration
- [ ] Fork [Taskbar](https://github.com/farmerbb/Taskbar) (Apache 2.0)
- [ ] Replace fire-and-forget launch with tiling-aware placement
- [ ] Layout switching UI
- [ ] Persistent tiling state

### Phase 3: Polish
- [ ] Additional layouts (BSP, spiral)
- [ ] Keyboard shortcuts / gesture controls
- [ ] Per-app floating exceptions
- [ ] Gap/padding configuration

## Requirements for the App (Future)

- Android 16+ (API 36)
- [Shizuku](https://shizuku.rikka.app/) running (no root needed — wireless ADB pairing works)
- Accessibility service enabled

## Technical Details

See [docs/research-spec.md](docs/research-spec.md) for the full research spec, including:
- AOSP source analysis of the freeform decoration system
- Shizuku API surface (`IActivityTaskManager` methods)
- Taskbar codebase analysis and fork hook points
- Tiling engine architecture with AccessibilityService bridging
- Known gotchas and limitations

## Device Settings

The `setup` command enables these (all persist across reboots):

```
Settings.Global.enable_freeform_support = 1
Settings.Global.force_resizable_activities = 1
Settings.Global.enable_non_resizable_multi_window = 1
Settings.Global.force_desktop_mode_on_external_displays = 1
Settings.Secure.desktop_mode = 1
```

## License

Apache 2.0
