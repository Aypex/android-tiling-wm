#!/usr/bin/env bash
# tile.sh — ADB-based tiling window manager for Android 16+
# Proof of concept. Requires: adb, freeform support enabled.
#
# Usage:
#   tile.sh setup          — Enable required settings on device
#   tile.sh launch <pkg>   — Launch app in freeform mode
#   tile.sh tile            — Tile all freeform tasks in master+stack layout
#   tile.sh list           — List visible freeform tasks
#   tile.sh layout <name>  — Switch layout (master-stack, columns, monocle)
#   tile.sh close <taskId> — Close a freeform task
#   tile.sh reset          — Return all tasks to fullscreen
#   tile.sh screenshot     — Take screenshot and pull to /tmp/

set -euo pipefail

# --- Config ---
LAYOUT="${TILE_LAYOUT:-master-stack}"
MASTER_RATIO="${TILE_MASTER_RATIO:-55}"  # percent of screen width for master
STATUS_BAR_HEIGHT=100                     # pixels, adjust per device
NAV_BAR_HEIGHT=100                        # pixels, adjust per device

# --- Helpers ---

die() { echo "ERROR: $*" >&2; exit 1; }

require_adb() {
    command -v adb >/dev/null 2>&1 || die "adb not found in PATH"
    local devices
    devices=$(adb devices | grep -c 'device$')
    [[ "$devices" -ge 1 ]] || die "No ADB device connected"
}

get_screen_size() {
    local size
    size=$(adb shell wm size | grep -oP '\d+x\d+' | tail -1)
    SCREEN_W="${size%x*}"
    SCREEN_H="${size#*x}"
}

get_freeform_tasks() {
    # Returns lines like: taskId packageName bounds
    adb shell "dumpsys activity activities" 2>/dev/null | \
        grep -oP 'Task\{[^ ]+ #\K\d+(?= .* mode=freeform)' || true
}

get_task_info() {
    local task_id="$1"
    adb shell "dumpsys activity activities" 2>/dev/null | \
        grep -A3 "Task{.*#${task_id} " | head -4
}

get_all_visible_tasks() {
    # Returns unique taskId lines for visible freeform tasks
    adb shell "dumpsys activity activities" 2>/dev/null | \
        grep -P 'Task\{.*visible=true.*mode=freeform' | \
        grep -oP '#\K\d+' | sort -u || true
}

# --- Commands ---

cmd_setup() {
    echo "Enabling freeform windowing on device..."
    adb shell settings put global enable_freeform_support 1
    adb shell settings put global force_resizable_activities 1
    adb shell settings put global enable_non_resizable_multi_window 1
    adb shell settings put global force_desktop_mode_on_external_displays 1
    adb shell settings put secure desktop_mode 1
    echo "Done. Freeform windowing enabled."
    echo ""
    echo "Verify with: adb shell settings get global enable_freeform_support"
}

cmd_launch() {
    local pkg="${1:-}"
    [[ -n "$pkg" ]] || die "Usage: tile.sh launch <package.name>"

    # Resolve the main activity
    local activity
    activity=$(adb shell cmd package resolve-activity --brief "$pkg" 2>/dev/null | tail -1)
    [[ -n "$activity" ]] || die "Could not resolve activity for $pkg"

    echo "Launching $activity in freeform mode..."
    adb shell am start -n "$activity" --windowingMode 5 -f 0x10000000
    echo "Launched. Use 'tile.sh tile' to arrange windows."
}

cmd_list() {
    echo "Visible freeform tasks:"
    echo "---"
    adb shell "dumpsys activity activities" 2>/dev/null | \
        grep -P 'Task\{.*mode=freeform' | \
        sed 's/.*Task{/  Task{/' | \
        while read -r line; do
            local tid
            tid=$(echo "$line" | grep -oP '#\K\d+')
            local pkg
            pkg=$(echo "$line" | grep -oP 'A=\d+:\K[^ ]+')
            local vis
            vis=$(echo "$line" | grep -oP 'visible=\K\w+')
            echo "  Task #$tid  $pkg  visible=$vis"
        done
    echo "---"
}

cmd_tile() {
    get_screen_size
    # Read task IDs into array
    local -a task_arr=()
    while IFS= read -r tid; do
        [[ -n "$tid" ]] && task_arr+=("$tid")
    done < <(get_all_visible_tasks)
    local count=${#task_arr[@]}

    [[ "$count" -gt 0 ]] || die "No visible freeform tasks. Launch some first with: tile.sh launch <pkg>"

    local top=$STATUS_BAR_HEIGHT
    local bottom=$((SCREEN_H - NAV_BAR_HEIGHT))

    echo "Tiling $count windows (layout: $LAYOUT, screen: ${SCREEN_W}x${SCREEN_H})"

    case "$LAYOUT" in
        master-stack)
            _layout_master_stack task_arr "$count" "$top" "$bottom"
            ;;
        columns)
            _layout_columns task_arr "$count" "$top" "$bottom"
            ;;
        monocle)
            _layout_monocle task_arr "$count" "$top" "$bottom"
            ;;
        *)
            die "Unknown layout: $LAYOUT. Options: master-stack, columns, monocle"
            ;;
    esac

    echo "Done."
}

_layout_master_stack() {
    local -n _tasks=$1
    local count="$2" top="$3" bottom="$4"

    if [[ "$count" -eq 1 ]]; then
        adb shell am task resize "${_tasks[0]}" 0 "$top" "$SCREEN_W" "$bottom"
        echo "  Task #${_tasks[0]} → fullscreen"
        return
    fi

    local master_w=$((SCREEN_W * MASTER_RATIO / 100))
    local stack_count=$((count - 1))
    local stack_h=$(( (bottom - top) / stack_count ))

    for i in "${!_tasks[@]}"; do
        local tid="${_tasks[$i]}"
        if [[ "$i" -eq 0 ]]; then
            adb shell am task resize "$tid" 0 "$top" "$master_w" "$bottom"
            echo "  Task #$tid → master (0,$top → $master_w,$bottom)"
        else
            local slot=$((i - 1))
            local s_top=$((top + slot * stack_h))
            local s_bottom=$((s_top + stack_h))
            [[ "$i" -eq "$((count - 1))" ]] && s_bottom=$bottom
            adb shell am task resize "$tid" "$master_w" "$s_top" "$SCREEN_W" "$s_bottom"
            echo "  Task #$tid → stack ($master_w,$s_top → $SCREEN_W,$s_bottom)"
        fi
    done
}

_layout_columns() {
    local -n _tasks=$1
    local count="$2" top="$3" bottom="$4"
    local col_w=$((SCREEN_W / count))

    for i in "${!_tasks[@]}"; do
        local tid="${_tasks[$i]}"
        local left=$((i * col_w))
        local right=$((left + col_w))
        [[ "$i" -eq "$((count - 1))" ]] && right=$SCREEN_W
        adb shell am task resize "$tid" "$left" "$top" "$right" "$bottom"
        echo "  Task #$tid → column ($left,$top → $right,$bottom)"
    done
}

_layout_monocle() {
    local -n _tasks=$1
    local count="$2" top="$3" bottom="$4"

    for i in "${!_tasks[@]}"; do
        local tid="${_tasks[$i]}"
        adb shell am task resize "$tid" 0 "$top" "$SCREEN_W" "$bottom"
        echo "  Task #$tid → fullscreen (monocle)"
    done

    # Bring last task to front
    local focused="${_tasks[$((count - 1))]}"
    if [[ -n "$focused" ]]; then
        adb shell am task focus "$focused" 2>/dev/null || true
        echo "  Focused: Task #$focused"
    fi
}

cmd_layout() {
    local name="${1:-}"
    [[ -n "$name" ]] || die "Usage: tile.sh layout <master-stack|columns|monocle>"
    LAYOUT="$name"
    cmd_tile
}

cmd_close() {
    local tid="${1:-}"
    [[ -n "$tid" ]] || die "Usage: tile.sh close <taskId>"
    adb shell am task remove "$tid"
    echo "Closed task #$tid"
    # Re-tile remaining
    sleep 0.5
    cmd_tile 2>/dev/null || true
}

cmd_reset() {
    echo "Returning all freeform tasks to fullscreen..."
    local tasks
    tasks=$(get_freeform_tasks)
    while IFS= read -r tid; do
        [[ -n "$tid" ]] || continue
        get_screen_size
        adb shell am task resize "$tid" 0 0 "$SCREEN_W" "$SCREEN_H"
        echo "  Task #$tid → fullscreen"
    done <<< "$tasks"
    echo "Done."
}

cmd_screenshot() {
    local ts
    ts=$(date +%Y%m%d_%H%M%S)
    local path="/tmp/tile_screenshot_${ts}.png"
    adb shell screencap -p /sdcard/tile_screenshot.png
    adb pull /sdcard/tile_screenshot.png "$path" 2>/dev/null
    adb shell rm /sdcard/tile_screenshot.png
    echo "Screenshot saved to $path"
}

# --- Main ---

require_adb

case "${1:-help}" in
    setup)      cmd_setup ;;
    launch)     shift; cmd_launch "$@" ;;
    list)       cmd_list ;;
    tile)       cmd_tile ;;
    layout)     shift; cmd_layout "$@" ;;
    close)      shift; cmd_close "$@" ;;
    reset)      cmd_reset ;;
    screenshot) cmd_screenshot ;;
    help|*)
        echo "tile.sh — ADB-based tiling window manager for Android 16+"
        echo ""
        echo "Commands:"
        echo "  setup              Enable freeform windowing on device"
        echo "  launch <pkg>       Launch app in freeform mode"
        echo "  tile               Tile all freeform windows"
        echo "  list               List visible freeform tasks"
        echo "  layout <name>      Switch layout (master-stack, columns, monocle)"
        echo "  close <taskId>     Close a task and re-tile"
        echo "  reset              Return all tasks to fullscreen"
        echo "  screenshot         Capture and pull screenshot"
        echo ""
        echo "Environment:"
        echo "  TILE_LAYOUT        Default layout (default: master-stack)"
        echo "  TILE_MASTER_RATIO  Master pane width % (default: 55)"
        echo ""
        echo "Example:"
        echo "  tile.sh setup"
        echo "  tile.sh launch com.android.settings"
        echo "  tile.sh launch com.android.chrome"
        echo "  tile.sh launch com.google.android.apps.messaging"
        echo "  tile.sh tile"
        echo "  tile.sh layout columns"
        ;;
esac
