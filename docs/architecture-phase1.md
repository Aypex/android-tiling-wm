# ATWM Phase 1 — Architecture

**Package:** `dev.atwm.tilingwm`
**Target:** Android 16 (API 36), compileSdk 35, minSdk 33
**Language:** Kotlin 2.0
**Build:** Gradle 8.x, AGP 8.x, version catalog

---

## 1. File Tree

```
app/
├── build.gradle.kts
├── src/
│   └── main/
│       ├── AndroidManifest.xml
│       ├── aidl/
│       │   └── dev/atwm/tilingwm/
│       │       └── IWindowTilingService.aidl
│       ├── kotlin/dev/atwm/tilingwm/
│       │   ├── MainActivity.kt
│       │   ├── service/
│       │   │   ├── ShizukuServiceConnection.kt
│       │   │   ├── WindowTilingServiceImpl.kt
│       │   │   └── TilingAccessibilityService.kt
│       │   ├── engine/
│       │   │   ├── TilingEngine.kt
│       │   │   ├── LayoutStrategy.kt
│       │   │   └── MasterStackLayout.kt
│       │   └── model/
│       │       ├── TaskInfo.kt
│       │       ├── LayoutBounds.kt
│       │       └── TilingConfig.kt
│       └── res/
│           ├── layout/
│           │   └── activity_main.xml
│           ├── values/
│           │   └── strings.xml
│           └── xml/
│               └── accessibility_service_config.xml
├── build.gradle.kts (project-level)
├── settings.gradle.kts
└── gradle/
    └── libs.versions.toml
```

---

## 2. AIDL Interface

```aidl
// IWindowTilingService.aidl
package dev.atwm.tilingwm;

interface IWindowTilingService {
    void destroy() = 16777114;

    // Resize a task to the given pixel bounds
    void resizeTask(int taskId, int left, int top, int right, int bottom) = 1;

    // Switch a task between fullscreen (1) and freeform (5)
    void setTaskWindowingMode(int taskId, int windowingMode, boolean toTop) = 2;

    // Get IDs + package names + bounds of visible tasks on display 0
    // Returns a flattened array: [taskId, left, top, right, bottom, windowingMode, ...]
    // Package names returned separately via getVisibleTaskPackages()
    int[] getVisibleTaskInfo() = 3;

    // Parallel string array of package names matching getVisibleTaskInfo() entries
    String[] getVisibleTaskPackages() = 4;
}
```

**Why flattened arrays?** AIDL Parcelable requires both sides to share the class definition. Primitive arrays + String arrays avoid this without adding a shared library module. Each task occupies 6 ints: `[taskId, left, top, right, bottom, windowingMode]`.

---

## 3. Data Classes

```kotlin
// model/TaskInfo.kt
data class TaskInfo(
    val taskId: Int,
    val packageName: String,
    val bounds: Rect,           // android.graphics.Rect
    val windowingMode: Int      // 1=FULLSCREEN, 5=FREEFORM
)

// model/LayoutBounds.kt
data class LayoutBounds(
    val taskId: Int,
    val bounds: Rect
)

// model/TilingConfig.kt
data class TilingConfig(
    val masterRatio: Float = 0.55f,         // Master pane width ratio (portrait) or height ratio (landscape)
    val statusBarHeight: Int = 100,          // px — top inset
    val navBarHeight: Int = 100,             // px — bottom inset
    val windowGap: Int = 0,                  // px gap between tiled windows
    val excludedPackages: Set<String> = setOf(
        "com.android.systemui",
        "com.android.launcher3",
        "dev.atwm.tilingwm"                 // Don't tile ourselves
    )
)
```

---

## 4. Component Interfaces

### TilingEngine

The central coordinator. Stateless per invocation — receives current task list, returns desired layout.

```kotlin
// engine/TilingEngine.kt
class TilingEngine(
    private val config: TilingConfig,
    private val strategy: LayoutStrategy
) {
    /**
     * Given visible tasks and screen dimensions, compute target bounds for each.
     * Filters out excluded packages and non-freeform tasks.
     * Returns empty list if 0-1 tileable tasks (single task gets no resize).
     */
    fun computeLayout(
        tasks: List<TaskInfo>,
        screenWidth: Int,
        screenHeight: Int,
        orientation: Int          // Configuration.ORIENTATION_PORTRAIT or LANDSCAPE
    ): List<LayoutBounds>
}
```

### LayoutStrategy

Strategy pattern — one implementation per layout algorithm.

```kotlin
// engine/LayoutStrategy.kt
interface LayoutStrategy {
    /**
     * Calculate bounds for N tileable tasks within the usable area.
     * @param usableArea  Screen rect minus status bar and nav bar
     * @param taskCount   Number of tasks to tile (always >= 2)
     * @param config      Tiling configuration (master ratio, gap, etc.)
     * @param orientation Portrait or landscape
     * @return Ordered list of Rects, index 0 = master/first task
     */
    fun calculateBounds(
        usableArea: Rect,
        taskCount: Int,
        config: TilingConfig,
        orientation: Int
    ): List<Rect>
}
```

### MasterStackLayout

```kotlin
// engine/MasterStackLayout.kt
class MasterStackLayout : LayoutStrategy {
    override fun calculateBounds(
        usableArea: Rect,
        taskCount: Int,
        config: TilingConfig,
        orientation: Int
    ): List<Rect> {
        // See section 6 for orientation-specific logic
    }
}
```

---

## 5. Event Pipeline

```
┌─────────────────────────────┐
│  TilingAccessibilityService │
│                             │
│  onAccessibilityEvent()     │
│    TYPE_WINDOWS_CHANGED     │
│    TYPE_WINDOW_STATE_CHANGED│
└──────────┬──────────────────┘
           │
           ▼
┌─────────────────────────────┐
│  Debounce Handler (150ms)   │
│  Post delayed → cancel on   │
│  new event within window    │
└──────────┬──────────────────┘
           │
           ▼
┌─────────────────────────────┐
│  retile()                   │
│                             │
│  1. Check serviceConnection │
│     .isConnected            │
│                             │
│  2. Call IWindowTilingService│
│     .getVisibleTaskInfo()   │
│     .getVisibleTaskPackages()│
│                             │
│  3. Parse into List<TaskInfo>│
│                             │
│  4. engine.computeLayout(   │
│       tasks, screenW,       │
│       screenH, orientation) │
│                             │
│  5. For each LayoutBounds:  │
│     a. If task is FULLSCREEN│
│        → setTaskWindowingMode│
│          (id, 5, true)      │
│     b. resizeTask(id, ...)  │
└─────────────────────────────┘
```

### Key Details

**Debounce:** A `Handler(Looper.getMainLooper())` posts a `Runnable`. Each new event cancels the pending runnable and posts a fresh one with 150ms delay. This collapses rapid window transition events into a single retile.

**Task filtering (inside TilingEngine.computeLayout):**
1. Remove tasks whose `packageName` is in `config.excludedPackages`
2. Keep only tasks visible on display 0 (the Shizuku service filters by display 0 already)
3. If only 1 task remains, return empty list (no tiling needed — let it fill naturally)

**Freeform transition:** Tasks in `FULLSCREEN` (mode 1) must be switched to `FREEFORM` (mode 5) via `setTaskWindowingMode()` before `resizeTask()` will work. The AccessibilityService handles this in step 5a.

**Thread model:** AccessibilityService callbacks run on main thread. Shizuku binder calls are synchronous but fast (same-device IPC). The retile sequence is:
- Main thread → debounce handler fires → binder calls to Shizuku service → return
- All Shizuku calls are fast IPC (<5ms each), no need for background thread in Phase 1

---

## 6. Orientation Handling

The AccessibilityService monitors `onConfigurationChanged()` for orientation changes, which triggers an immediate retile (bypasses debounce).

### Portrait (ORIENTATION_PORTRAIT) — Horizontal Splits (Master+Stack)

```
┌─────────────────────┐
│     status bar       │  ← statusBarHeight
├──────────┬──────────┤
│          │  Stack 1  │
│  Master  ├──────────┤
│  (55%)   │  Stack 2  │
│          ├──────────┤
│          │  Stack 3  │
├──────────┴──────────┤
│     nav bar          │  ← navBarHeight
└─────────────────────┘
```

- Master: left=0, top=statusBar, right=screenW*ratio, bottom=screenH-navBar
- Stack items: split the remaining right column evenly by height

### Landscape (ORIENTATION_LANDSCAPE) — Vertical Splits (Master+Stack)

```
┌──────────────────────────────────┐
│           status bar              │
├──────────────────────────────────┤
│              Master (55%)         │
├──────────┬──────────┬────────────┤
│  Stack 1 │  Stack 2 │  Stack 3   │
├──────────┴──────────┴────────────┤
│           nav bar                 │
└──────────────────────────────────┘
```

- Master: left=0, top=statusBar, right=screenW, bottom=statusBar + usableH*ratio
- Stack items: split the remaining bottom row evenly by width

### Implementation in MasterStackLayout

```kotlin
override fun calculateBounds(
    usableArea: Rect,
    taskCount: Int,
    config: TilingConfig,
    orientation: Int
): List<Rect> {
    val gap = config.windowGap
    val results = mutableListOf<Rect>()

    if (orientation == Configuration.ORIENTATION_PORTRAIT) {
        val masterWidth = ((usableArea.width() - gap) * config.masterRatio).toInt()
        val stackCount = taskCount - 1

        // Master
        results.add(Rect(
            usableArea.left,
            usableArea.top,
            usableArea.left + masterWidth,
            usableArea.bottom
        ))

        // Stack — divide right column evenly
        val stackLeft = usableArea.left + masterWidth + gap
        val stackHeight = (usableArea.height() - gap * (stackCount - 1)) / stackCount
        for (i in 0 until stackCount) {
            results.add(Rect(
                stackLeft,
                usableArea.top + i * (stackHeight + gap),
                usableArea.right,
                usableArea.top + i * (stackHeight + gap) + stackHeight
            ))
        }
    } else {
        // Landscape: master on top, stack splits bottom
        val masterHeight = ((usableArea.height() - gap) * config.masterRatio).toInt()
        val stackCount = taskCount - 1

        // Master
        results.add(Rect(
            usableArea.left,
            usableArea.top,
            usableArea.right,
            usableArea.top + masterHeight
        ))

        // Stack — divide bottom row evenly
        val stackTop = usableArea.top + masterHeight + gap
        val stackWidth = (usableArea.width() - gap * (stackCount - 1)) / stackCount
        for (i in 0 until stackCount) {
            results.add(Rect(
                usableArea.left + i * (stackWidth + gap),
                stackTop,
                usableArea.left + i * (stackWidth + gap) + stackWidth,
                usableArea.bottom
            ))
        }
    }

    return results
}
```

### Screen Metrics

The AccessibilityService gets screen dimensions from `resources.displayMetrics` on init and on `onConfigurationChanged()`. Status bar and nav bar heights come from `TilingConfig` (hardcoded for Phase 1 — correct values for Pixel 7a are ~100px each; a future phase can read system insets).

---

## 7. Shizuku Permission Flow

### Lifecycle

```
MainActivity.onCreate()
  │
  ├── Check Shizuku installed: Shizuku.pingBinder()
  │   └── false → Show "Install Shizuku" message + link
  │
  ├── Check Shizuku running: Shizuku.pingBinder()
  │   └── false → Show "Start Shizuku via ADB" instructions
  │
  ├── Add listeners:
  │   Shizuku.addRequestPermissionResultListener(this)
  │   Shizuku.addBinderReceivedListener(this)
  │   Shizuku.addBinderDeadListener(this)
  │
  └── Check permission: Shizuku.checkSelfPermission()
      ├── GRANTED → bindUserService()
      └── DENIED → Shizuku.requestPermission(REQUEST_CODE)
                    → onRequestPermissionResult callback
                      ├── GRANTED → bindUserService()
                      └── DENIED → Show "Permission required" message

bindUserService()
  │
  └── Shizuku.bindUserService(args, connection)
      args = Shizuku.UserServiceArgs(
          ComponentName(packageName, WindowTilingServiceImpl::class.java.name))
          .daemon(false)
          .processNameSuffix("tiling")
          .debuggable(BuildConfig.DEBUG)
          .version(BuildConfig.VERSION_CODE)
```

### ShizukuServiceConnection

```kotlin
// service/ShizukuServiceConnection.kt
class ShizukuServiceConnection : ServiceConnection {
    var service: IWindowTilingService? = null
        private set

    val isConnected: Boolean
        get() = service != null

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = IWindowTilingService.Stub.asInterface(binder)
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }
}
```

The connection instance is held in `MainActivity` and passed to the `TilingAccessibilityService` via a companion object singleton reference (both live in the same app process). This is the simplest approach — no bound service or broadcast needed.

```kotlin
// In TilingAccessibilityService.kt
companion object {
    var serviceConnection: ShizukuServiceConnection? = null
}
```

`MainActivity` sets this when binding succeeds. The AccessibilityService checks `serviceConnection?.isConnected` before each retile.

---

## 8. Shizuku UserService Implementation

```kotlin
// service/WindowTilingServiceImpl.kt
class WindowTilingServiceImpl : IWindowTilingService.Stub() {

    // Direct binder access — runs in Shizuku's privileged process (UID 2000)
    private val atm: IActivityTaskManager by lazy {
        IActivityTaskManager.Stub.asInterface(
            android.os.ServiceManager.getService("activity_task")
        )
    }

    override fun resizeTask(taskId: Int, left: Int, top: Int, right: Int, bottom: Int) {
        atm.resizeTask(taskId, Rect(left, top, right, bottom), 0)  // RESIZE_MODE_SYSTEM
    }

    override fun setTaskWindowingMode(taskId: Int, windowingMode: Int, toTop: Boolean) {
        atm.setTaskWindowingMode(taskId, windowingMode, toTop)
    }

    override fun getVisibleTaskInfo(): IntArray {
        val tasks = atm.getTasks(20, false, false, 0)  // display 0, up to 20 tasks
            .filter { it.isVisible && it.isRunning }

        // Flatten: 6 ints per task [taskId, left, top, right, bottom, windowingMode]
        val result = IntArray(tasks.size * 6)
        tasks.forEachIndexed { i, task ->
            val offset = i * 6
            result[offset] = task.taskId
            result[offset + 1] = task.bounds.left
            result[offset + 2] = task.bounds.top
            result[offset + 3] = task.bounds.right
            result[offset + 4] = task.bounds.bottom
            result[offset + 5] = task.configuration.windowConfiguration.windowingMode
        }
        return result
    }

    override fun getVisibleTaskPackages(): Array<String> {
        val tasks = atm.getTasks(20, false, false, 0)
            .filter { it.isVisible && it.isRunning }
        return tasks.map { it.topActivity?.packageName ?: "" }.toTypedArray()
    }

    override fun destroy() {
        // Called by Shizuku when unbinding. No cleanup needed.
    }
}
```

**Note:** `getVisibleTaskInfo()` and `getVisibleTaskPackages()` are called together and return parallel arrays. A single call would be cleaner but AIDL doesn't support returning custom Parcelables without a shared library. This is the pragmatic tradeoff.

---

## 9. TilingAccessibilityService

```kotlin
// service/TilingAccessibilityService.kt
class TilingAccessibilityService : AccessibilityService() {

    companion object {
        var serviceConnection: ShizukuServiceConnection? = null
        var isEnabled: Boolean = false  // Toggle from MainActivity
    }

    private val handler = Handler(Looper.getMainLooper())
    private val debounceMs = 150L
    private val config = TilingConfig()
    private val engine = TilingEngine(config, MasterStackLayout())

    private var screenWidth = 0
    private var screenHeight = 0

    private val retileRunnable = Runnable { retile() }

    override fun onServiceConnected() {
        updateScreenMetrics()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isEnabled) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handler.removeCallbacks(retileRunnable)
                handler.postDelayed(retileRunnable, debounceMs)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenMetrics()
        // Orientation change — retile immediately
        handler.removeCallbacks(retileRunnable)
        retile()
    }

    override fun onInterrupt() {}

    private fun updateScreenMetrics() {
        val dm = resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
    }

    private fun retile() {
        val svc = serviceConnection?.service ?: return

        // Fetch current task state from Shizuku
        val taskInts = svc.getVisibleTaskInfo()
        val taskPkgs = svc.getVisibleTaskPackages()

        // Parse into TaskInfo list
        val tasks = mutableListOf<TaskInfo>()
        val count = taskInts.size / 6
        for (i in 0 until count) {
            val offset = i * 6
            tasks.add(TaskInfo(
                taskId = taskInts[offset],
                packageName = taskPkgs[i],
                bounds = Rect(taskInts[offset+1], taskInts[offset+2],
                              taskInts[offset+3], taskInts[offset+4]),
                windowingMode = taskInts[offset+5]
            ))
        }

        val orientation = resources.configuration.orientation
        val layout = engine.computeLayout(tasks, screenWidth, screenHeight, orientation)

        // Apply layout
        for (lb in layout) {
            val task = tasks.find { it.taskId == lb.taskId } ?: continue

            // Switch to freeform if needed
            if (task.windowingMode != 5) {
                svc.setTaskWindowingMode(task.taskId, 5, true)
            }

            svc.resizeTask(lb.taskId, lb.bounds.left, lb.bounds.top,
                          lb.bounds.right, lb.bounds.bottom)
        }
    }
}
```

---

## 10. AndroidManifest

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="dev.atwm.tilingwm">

    <!-- Shizuku permission -->
    <uses-permission android:name="moe.shizuku.manager.permission.API_V23" />

    <application
        android:label="@string/app_name"
        android:allowBackup="false"
        android:supportsRtl="true">

        <!-- Main activity: Shizuku permission flow + tiling toggle -->
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Shizuku content provider (required for Shizuku init) -->
        <provider
            android:name="rikka.shizuku.ShizukuProvider"
            android:authorities="${applicationId}.shizuku"
            android:multiprocess="false"
            android:enabled="true"
            android:exported="true"
            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />

        <!-- Accessibility service for window events -->
        <service
            android:name=".service.TilingAccessibilityService"
            android:exported="false"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

    </application>
</manifest>
```

### Accessibility Service Config

```xml
<!-- res/xml/accessibility_service_config.xml -->
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowsChanged|typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds"
    android:canRetrieveWindowContent="false"
    android:notificationTimeout="150"
    android:description="@string/accessibility_service_description" />
```

---

## 11. MainActivity

Simple single-screen activity. No fragments, no navigation.

```kotlin
// MainActivity.kt
class MainActivity : AppCompatActivity(),
    Shizuku.OnRequestPermissionResultListener,
    Shizuku.OnBinderReceivedListener,
    Shizuku.OnBinderDeadListener {

    private val serviceConnection = ShizukuServiceConnection()
    private var tilingEnabled = false

    // UI: status text + toggle button
    // Shows one of:
    //   "Shizuku not installed" + install link
    //   "Shizuku not running" + ADB instructions
    //   "Permission denied" + request button
    //   "Connected" + Start/Stop tiling toggle
    //   "Enable Accessibility Service" + open settings button

    override fun onCreate(savedInstanceState: Bundle?) {
        // Register Shizuku listeners
        // Check state and update UI
        // If permission already granted → bindUserService()
    }

    private fun bindUserService() {
        val args = Shizuku.UserServiceArgs(
            ComponentName(packageName, WindowTilingServiceImpl::class.java.name)
        ).daemon(false).processNameSuffix("tiling").version(BuildConfig.VERSION_CODE)

        Shizuku.bindUserService(args, serviceConnection)
        TilingAccessibilityService.serviceConnection = serviceConnection
    }

    private fun toggleTiling() {
        tilingEnabled = !tilingEnabled
        TilingAccessibilityService.isEnabled = tilingEnabled
        // Update button text
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(this)
        Shizuku.removeBinderReceivedListener(this)
        Shizuku.removeBinderDeadListener(this)
        super.onDestroy()
    }
}
```

---

## 12. Gradle Setup

### gradle/libs.versions.toml

```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
shizuku = "13.1.5"
hiddenApiBypass = "4.3"
appcompat = "1.7.0"

[libraries]
shizuku-api = { module = "dev.rikka.shizuku:api", version.ref = "shizuku" }
shizuku-provider = { module = "dev.rikka.shizuku:provider", version.ref = "shizuku" }
hidden-api-bypass = { module = "org.lsposed.hiddenapibypass:hiddenapibypass", version.ref = "hiddenApiBypass" }
appcompat = { module = "androidx.appcompat:appcompat", version.ref = "appcompat" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

### Project build.gradle.kts

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
```

### App build.gradle.kts

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.atwm.tilingwm"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.atwm.tilingwm"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hidden.api.bypass)
    implementation(libs.appcompat)
}
```

---

## 13. What Is NOT in Phase 1

Explicitly out of scope:
- No persistent state / saved layouts
- No per-app exclusion UI (hardcoded exclusion set only)
- No dynamic master ratio adjustment (fixed 55%)
- No animations or visual feedback
- No additional layout algorithms beyond master+stack
- No keyboard shortcuts or gestures
- No notification / foreground service (accessibility service handles lifecycle)
- No Taskbar integration (that's Phase 3)
- No status/nav bar inset detection (hardcoded pixel values)
