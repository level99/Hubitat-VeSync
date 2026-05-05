/*
 * MIT License
 *
 * Copyright (c) 2026 Dan Cox (level99/Hubitat-VeSync community fork)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
 */

library(
    name: "LevoitChildBase",
    namespace: "level99",
    author: "Dan Cox",
    description: "Shared logging helpers, BP16 debug watchdog, BP23 switch-on guard, and BP18 null-guard for Levoit child drivers (community fork v2.5+).",
    importUrl: "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitChildBaseLib.groovy",
    documentationLink: "https://github.com/level99/Hubitat-VeSync/blob/main/CONTRIBUTING.md"
)

def logInfo(msg)   { if (settings?.descriptionTextEnable) log.info  msg }
def logDebug(msg)  { if (settings?.debugOutput)           log.debug msg }
def logError(msg)  { log.error msg }
def logWarn(msg)   { log.warn  msg }
// Always-on info log — no pref gate. Use for user-invoked diagnostics that must appear
// regardless of descriptionTextEnable (e.g. probe commands, captureDiagnostics output).
def logAlways(msg) { log.info  msg }

void logDebugOff() {
    if (settings?.debugOutput) device.updateSetting("debugOutput", [type:"bool", value:false])
}

// BP16 watchdog: auto-disable stuck debugOutput after 30 min across hub reboots.
// Call at the top of every poll entry point and every command method.
// Requires updated() to set state.debugEnabledAt = now() when debug is enabled
// (and clear it when disabled), which all child drivers do per convention.
private void ensureDebugWatchdog() {
    if (settings?.debugOutput && state.debugEnabledAt) {
        Long elapsed = now() - (state.debugEnabledAt as Long)
        if (elapsed > 30 * 60 * 1000) {
            logInfo "BP16 watchdog: 30 min elapsed since debug enable; auto-disabling now (post-reboot self-heal)"
            device.updateSetting("debugOutput", [type:"bool", value:false])
            state.remove("debugEnabledAt")
        }
    }
}

// BP23 guard: turn on the device if it is currently off before executing a
// setLevel / setSpeed / setMistLevel call, matching SwitchLevel capability
// convention (setLevel on an off device should turn it on AND set the level).
//
// Skips the on() call when state.turningOn is already set (re-entrance guard)
// so that on()'s own internal speed/mode setup does not recurse.
//
// Call at the top of setLevel() after the val==0 -> off() early-return guard:
//
//   def setLevel(val) {
//       if (!val || val == 0) { off(); return }
//       ensureSwitchOn()
//       // ... rest of setLevel ...
//   }
void ensureSwitchOn() {
    if (!state.turningOn && device.currentValue("switch") != "on") on()
}

// BP18 null-guard helper: log a WARN and signal the caller to skip further
// processing when a command argument is null (Rule Machine blank-parameter path).
//
// Usage in a command method:
//
//   def setMode(mode) {
//       if (!requireNotNull(mode, "setMode")) return
//       // ... rest of method ...
//   }
//
// Returns false (caller should return) when arg is null.
// Returns true  (caller should continue) when arg is non-null.
boolean requireNotNull(arg, String methodName) {
    if (arg == null) {
        logWarn "${methodName} called with null arg (likely empty Rule Machine action parameter); ignoring"
        return false
    }
    return true
}
