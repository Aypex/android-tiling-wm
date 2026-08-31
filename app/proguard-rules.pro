# Shizuku instantiates the UserService by class name in a separate process,
# and the AIDL stub is resolved reflectively across the binder. R8 in the
# release build would rename/strip these and the UserService would fail to
# start ("missing R8 keep rule"). Keep them by exact name.
# (Landmine surfaced by the LuterGS/android-workspace-manager fork.)
-keep class dev.atwm.tilingwm.service.WindowTilingServiceImpl { *; }
-keep class dev.atwm.tilingwm.IWindowTilingService { *; }
-keep class dev.atwm.tilingwm.IWindowTilingService$* { *; }
