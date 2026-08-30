package dev.atwm.tilingwm;

interface IWindowTilingService {
    void destroy() = 16777114;

    // Resize a task to the given pixel bounds
    void resizeTask(int taskId, int left, int top, int right, int bottom) = 1;

    // Switch a task between fullscreen (1) and freeform (5)
    void setTaskWindowingMode(int taskId, int windowingMode, boolean toTop) = 2;

    // Get IDs + bounds + windowing mode of visible tasks on display 0
    // Returns a flattened array: [taskId, left, top, right, bottom, windowingMode, ...]
    int[] getVisibleTaskInfo() = 3;

    // Parallel string array of package names matching getVisibleTaskInfo() entries
    String[] getVisibleTaskPackages() = 4;

    // Re-append the given accessibility service to enabled_accessibility_services
    // if the OS pruned it (e.g. Android 17 removes force-stopped apps from the
    // list). Appends only — never overwrites other enabled services.
    void ensureAccessibilityService(String component) = 5;
}
