# Android Tiling WM — Research Spec

**Date:** 2026-02-27
**Target Device:** Pixel 10 Pro XL (Android 16, API 36, codename mustang)
**Test Device:** Pixel 7a (Android 16, API 36, build BP4A.260105.004.E1)
**Status:** Research complete. Ready for architecture design.

---

## Executive Summary

A tiling window manager for Android phones. Single layer:
- **Taskbar fork** with Shizuku-powered tiling engine

**KEY FINDING:** LSPosed/root is NOT needed. Android 16 already renders native caption bars
(title bar, minimize/maximize/close, drag-to-move, resize handles) on freeform windows on
the phone's primary display (display 0). Verified on Pixel 7a, Android 16 API 36.

### Proof of Concept (2026-02-27)

Tiling achieved with two ADB commands:
```bash
# Launch Settings freeform, tile left
adb shell am start -n com.android.settings/.Settings --windowingMode 5
adb shell am task resize 35563 0 100 540 2300

# Launch Chrome freeform, tile right
adb shell am start -n com.android.chrome/com.google.android.apps.chrome.Main --windowingMode 5
adb shell am task resize 35564 540 100 1080 2300
```
Result: Two apps tiled side-by-side with full native window chrome. No root required.
Screenshots saved on test device at `/sdcard/freeform_test*.png`.

---

## Critical Finding: The Display Gate

**There is NO hard-coded display ID check** gating caption bars to external displays. The decoration system is driven entirely by **windowing mode**, not display identity.

### Two Decoration Paths

| Path | Era | Where it runs | Gate |
|------|-----|---------------|------|
| **Legacy** | Android 7+ | Client (in-app process) | `WindowConfiguration.hasWindowDecorCaption()` returns `mWindowingMode == WINDOWING_MODE_FREEFORM` |
| **Modern** | Android 15+ | Server (SystemUI/system_server) | `DesktopModeWindowDecorViewModel` in WM Shell checks desktop mode feature flags + task windowing mode |

Android 16 desktop mode uses the **modern path** exclusively. The new-style header bar (drag handle, app icon, dropdown, maximize, close) is rendered by `CaptionWindowDecoration` in `com.android.wm.shell.windowdecor`.

### Why Captions Don't Appear on Phone Screen

The phone screen (display 0) runs in `WINDOWING_MODE_FULLSCREEN` (1). External displays configured for desktop mode run in `WINDOWING_MODE_FREEFORM` (5). Captions appear because the windowing mode is freeform, not because of any display ID check.

The desktop windowing feature is also gated by:
- `Settings.Global.enable_freeform_support` (already set to 1 on test device)
- `Settings.Secure.desktop_mode` (set to 1 during testing)
- `persist.wm.debug.desktop_mode` / `persist.wm.debug.desktop_mode_2` system props
- Feature flag: `Flags.enableDesktopWindowing()` (aconfig, internal)
- Config overlay: `config_freeformWindowManagement` (boolean)

### Test Device Current State

```
enable_freeform_support = 1
force_desktop_mode_on_external_displays = 1
force_resizable_activities = 1
enable_non_resizable_multi_window = 1
force_allow_on_external = 0
desktop_mode (secure) = 1
persist.wm.extensions.enabled = true
```

---

## LSPosed Hook Targets (Ranked by Impact)

### Hook 1: Display Windowing Mode (Recommended — Server Side)

**Class:** `com.android.server.wm.DisplayWindowSettings`
**Method:** `setWindowingModeLocked(DisplayContent dc, int mode)`
**Process:** `system_server`
**Strategy:** Force `WINDOWING_MODE_FREEFORM` (5) for display 0

This makes the phone screen operate in freeform mode system-wide. All tasks on display 0 would be in freeform windowing mode, triggering both legacy and modern caption decoration.

**Risk:** High impact — changes entire display behavior. May break launcher, status bar, nav bar. Needs careful testing.

### Hook 2: Legacy Caption Gate (Client Side — Simplest)

**Class:** `android.app.WindowConfiguration`
**Method:** `public boolean hasWindowDecorCaption()`
**Process:** Every app process
**Strategy:** Return `true` always (or conditionally when freeform is active)

Forces the legacy `DecorCaptionView` (Nougat-era title bar with maximize/close buttons) on all windows. Simpler but older UI.

### Hook 3: Modern Desktop Decorations (Server Side — Best UX)

**Class:** `com.android.wm.shell.windowdecor.DesktopModeWindowDecorViewModel`
**Process:** SystemUI / system_server
**Strategy:** Override the "should show decoration" logic to return true for tasks on display 0 in freeform mode

This gives the new Android 16 desktop-style header bar. Best visual result but most complex hook.

### Hook 4: Per-App Caption Control (Client Side)

**Class:** `com.android.internal.policy.DecorView`
**Method:** `void updateDecorCaptionStatus(Configuration config)`
**Process:** Each app process
**Strategy:** Override the `displayWindowDecor` boolean

Fine-grained control — could selectively add captions to specific apps.

### Existing Reference Module

**[Android_16_Desktop_Experience_Enabler](https://github.com/igorb200828/Android_16_Desktop_Experience_Enabler)** — LSPosed module that enables the desktop experience developer option. Shows exact hook targets for the settings gate. Start here for module scaffolding.

---

## Taskbar Fork Analysis

### Codebase Overview

- **Repo:** https://github.com/farmerbb/Taskbar
- **License:** Apache 2.0
- **Build:** Gradle, single `:app` module
- **Language:** Kotlin 2.0.20 / Java 21
- **SDK:** minSdk 21, compileSdk 34, targetSdk 34
- **Existing Shizuku dep:** Already uses Shizuku 12.1.0 + Hidden API Bypass 3.0

### How Freeform Launch Works

All launches funnel through `U.java` (~2300 lines):

1. `U.launchApp()` → checks `hasFreeformSupport()` + `isFreeformModeEnabled()`
2. `startFreeformHack()` → launches invisible 1x1px `InvisibleActivityFreeform` to enter freeform workspace
3. `continueLaunchingApp()` → builds Intent with `FLAG_ACTIVITY_NEW_TASK`
4. `getActivityOptionsBundle()` → calculates bounds via `launchMode1/2/3`, sets `WINDOWING_MODE_FREEFORM` via reflection on `ActivityOptions.setWindowingMode()`
5. `context.startActivity(intent, options.toBundle())`

### Bounds Calculation (Current)

| Mode | Bounds |
|------|--------|
| `standard` | Centered 75% of screen (`factor=4`) |
| `large` | Centered 87.5% (`factor=8`) |
| `fullscreen` | Full screen minus taskbar icon |
| `half_left/half_right` | Half screen minus taskbar |
| `phone_size` | Fixed small window from dimen resources |

### Critical Gap: No Post-Launch Window Tracking

Taskbar is **fire-and-forget**. After launch:
- No window position tracking
- No AccessibilityService for window events (existing `PowerMenuService` has empty stubs)
- No `resizeTask()` calls — once launched, Taskbar never touches the window again
- `SavedWindowSizes` only stores SIZE PREFERENCE per package (string like "standard"), not actual Rects

### Service Architecture

```
NotificationService (foreground, persistent)
  ├── TaskbarService → TaskbarController (overlay)
  ├── StartMenuService → StartMenuController (overlay)
  └── DashboardService → DashboardController (overlay)
```

All UI services extend `UIHostService`:
```java
abstract class UIHostService extends Service implements UIHost {
    abstract UIController newController();
    addView(view, params)     // → WindowManager.addView()
    removeView(view)          // → WindowManager.removeView()
    updateViewLayout(...)     // → WindowManager.updateViewLayout()
}
```

Uses `TYPE_APPLICATION_OVERLAY` with `SYSTEM_ALERT_WINDOW` permission.

### Fork Hook Points

| Hook Point | File | What to Change |
|-----------|------|---------------|
| **Launch bounds** | `U.getActivityOptionsBundle()` | Replace `launchMode1/2/3` with tiling layout query |
| **Service startup** | `NotificationService` | Add `TilingService` to startup chain |
| **App polling** | `TaskbarController.startRefreshingRecents()` | Extend to reconcile tiling state |
| **Window size storage** | `SavedWindowSizes` | Replace with `TilingState` (zone assignments per task) |
| **Accessibility** | `PowerMenuService` (empty stubs) | Add window event processing or create new service |

### Related: libtaskbar

[libtaskbar](https://github.com/farmerbb/libtaskbar) wraps Taskbar as an embeddable library for third-party launchers. Too thin for our use — fork Taskbar directly.

---

## Shizuku API Surface

### Key IActivityTaskManager Methods

Accessed via service name `"activity_task"`:

```kotlin
// Window resizing
fun resizeTask(taskId: Int, bounds: Rect, resizeMode: Int)
  // resizeMode: 0=SYSTEM, 1=PRESERVE_WINDOW, 2=FORCED

// Windowing mode
fun setTaskWindowingMode(taskId: Int, windowingMode: Int, toTop: Boolean)
  // windowingMode: 1=FULLSCREEN, 5=FREEFORM

// Task enumeration
fun getTasks(maxNum: Int, filterOnlyVisibleRecents: Boolean,
             keepIntentExtra: Boolean, displayId: Int): List<RunningTaskInfo>
fun getTaskBounds(taskId: Int): Rect

// Task movement
fun moveTaskToFront(appThread: IApplicationThread, callingPackage: String,
                    taskId: Int, flags: Int, options: Bundle)
fun moveRootTaskToDisplay(taskId: Int, displayId: Int)
```

### RunningTaskInfo Fields (from getTasks)

```kotlin
taskId: Int                  // The task ID for resizeTask()
bounds: Rect                 // Current window bounds
topActivity: ComponentName   // Package + activity class
baseActivity: ComponentName
isRunning: Boolean
isVisible: Boolean
windowingMode: Int           // Current mode (1=full, 5=freeform)
displayId: Int
numActivities: Int
```

### Recommended: Shizuku UserService (Pattern B)

Runs your code in a privileged process (UID 2000). No hidden API bypass needed. Direct binder access.

```kotlin
// Define AIDL interface
interface IWindowTilingService {
    void destroy() = 16777114;
    void resizeTask(int taskId, int left, int top, int right, int bottom) = 1;
    int[] getVisibleTaskIds() = 2;
}

// Implementation runs in Shizuku process
class WindowTilingServiceImpl : IWindowTilingService.Stub() {
    private val atm = IActivityTaskManager.Stub.asInterface(
        android.os.ServiceManager.getService("activity_task")
    )

    override fun resizeTask(taskId: Int, l: Int, t: Int, r: Int, b: Int) {
        atm.resizeTask(taskId, Rect(l, t, r, b), 0)
    }
}
```

### Gradle Dependencies

```kotlin
val shizukuVersion = "13.1.5"
implementation("dev.rikka.shizuku:api:$shizukuVersion")
implementation("dev.rikka.shizuku:provider:$shizukuVersion")
implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
```

---

## Tiling Engine Pipeline

```
[AccessibilityService]           [TilingEngine]            [Shizuku UserService]
        |                             |                            |
  TYPE_WINDOWS_CHANGED                |                            |
  TYPE_WINDOW_STATE_CHANGED           |                            |
        |                             |                            |
        +-- onWindowLayoutChanged()   |                            |
        |   debounce 150ms ---------> |                            |
        |                             |                            |
        |                    getVisibleTasks() -----------------> |
        |                             | <--- RunningTaskInfo[] -- |
        |                             |                            |
        |                    filterAppTasks()                      |
        |                    calculateLayout()                     |
        |                             |                            |
        |                    for each task:                        |
        |                      resizeTask(id, bounds) ----------> |
        |                             |                            |
```

### Window-Task ID Bridging

AccessibilityService has no `getTaskId()`. Bridge via package name matching:
```kotlin
val windowPackage = accessibilityWindow.getRoot()?.packageName
val matchingTask = tasks.find { it.topActivity?.packageName == windowPackage }
```

Ambiguity with multi-instance apps (e.g., Chrome) — resolve by also comparing bounds or activity class.

### Layout Algorithms

| Layout | Description | Complexity |
|--------|-------------|-----------|
| Monocle | Fullscreen, one app | Trivial |
| Master+Stack | Left master, right stack | Low |
| Columns | Equal vertical splits | Low |
| BSP (binary space partition) | Recursive halving | Medium |
| Spiral | Fibonacci-like | Medium |

Start with master+stack (most useful on phone aspect ratio).

---

## Gotchas and Limitations

### Shizuku
- **Dies on reboot** (non-root) — user must re-pair. Shizuku 13.6.0+ has auto-startup on trusted WiFi for Android 13+
- Transaction code mismatches across API levels when using ShizukuBinderWrapper — UserService avoids this
- No binder rate limit but system_server can drop resize requests if overwhelmed

### Accessibility
- Debounce essential (150-200ms) — window events fire rapidly during transitions
- `resizeTask()` during animation causes visual glitches
- No direct accessibility window ID → task ID mapping

### Freeform Mode
- Must enable via `settings put global enable_freeform_support 1` (persists across reboots)
- Must call `setTaskWindowingMode(taskId, 5, true)` before `resizeTask()` for fullscreen→freeform switch
- Min window size: 386dp x 352dp in desktop windowing

### avbroot (Locked Bootloader)
- Re-sign OTA with custom AVB key → relock bootloader → STRONG integrity passes
- Monthly OTA repatch (~5 min manual process)
- First-time setup wipes device
- Don't lose your AVB key

---

## Existing Prior Art

| Project | What it does | Relevance |
|---------|-------------|-----------|
| [Taskbar](https://github.com/farmerbb/Taskbar) | Freeform launcher, fire-and-forget | Fork base |
| [Android_16_Desktop_Experience_Enabler](https://github.com/igorb200828/Android_16_Desktop_Experience_Enabler) | LSPosed module enabling desktop dev option | Module scaffolding reference |
| [Cover-Screen-Launcher](https://github.com/Katsuyamaki/Cover-Screen-Launcher) | Z Flip tiling via Shizuku shell commands | Shizuku + window resize reference |
| [awesome-shizuku](https://github.com/timschneeb/awesome-shizuku) | Curated Shizuku app list | Discovery |
| [FloatingWindows](https://github.com/diegoRodriguezAguila/FloatingWindows) | Overlay window library | Fallback if system captions don't work |

---

## Recommended Build Order

### Phase 0: Validate (on Pixel 7a test device) -- DONE 2026-02-27
- [x] Try `am start` with freeform windowing mode and explicit bounds via ADB -- WORKS
- [x] Verify `am task resize` works on a running task -- WORKS
- [x] Test if display 0 freeform windows render captions -- YES, native chrome appears
- [x] Two apps tiled side-by-side with native caption bars -- CONFIRMED

### ~~Phase 1: LSPosed Module (MVP)~~ -- UNNECESSARY
Caption bars render natively on display 0 freeform windows. No hook needed.

### Phase 1: Tiling Engine (Standalone App)
- [ ] Build Shizuku UserService with `resizeTask()` + `getTasks()` + `setTaskWindowingMode()`
- [ ] Build AccessibilityService for window events
- [ ] Implement master+stack layout algorithm
- [ ] Wire up: accessibility event → debounce → get tasks → calculate layout → resize all

### Phase 3: Taskbar Fork Integration
- [ ] Fork Taskbar, add tiling engine as new package
- [ ] Hook launch path to use tiling layout for initial bounds
- [ ] Add layout switching UI (gesture or button)
- [ ] Persist tiling state per workspace

### Phase 4: Polish
- [ ] Additional layouts (columns, BSP, monocle)
- [ ] Keyboard shortcuts / gesture controls
- [ ] Per-app floating exceptions (don't tile certain apps)
- [ ] avbroot packaging for locked bootloader deployment
