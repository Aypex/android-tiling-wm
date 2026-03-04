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
}
