package io.github.nitsuya.aa.display

import io.github.nitsuya.aa.display.xposed.CoreManager
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.hook.AndroidHook

val CoreApi by lazy {
    // uid 1000 is shared by many OEM system apps (e.g. Samsung SLocation);
    // only the real system_server process hosts CoreManagerService.
    if (AndroidHook.isReadyForSystemHooks()) CoreManagerService.instance!!
    else CoreManager
}
