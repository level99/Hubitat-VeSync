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

// BP18 extended null-guard for string-enum setters: intercepts both null AND empty/whitespace-only
// string, so that a blank Rule Machine parameter slot (which arrives as "" not null) does not leak
// into the value-emission path and produce an empty-value INFO log.
//
// Usage: replace requireNotNull with requireNonEmptyEnum at the top of any string-enum setter
// (setMode, setSpeed, setDisplay, setChildLock, setDryingMode, set*, etc.) — NOT for numeric
// setters (setLevel, setMistLevel, setTargetHumidity, etc.) which use safeIntArg instead.
//
//   def setMode(mode) {
//       if (!requireNonEmptyEnum(mode, "setMode")) return
//       String m = (mode as String).trim().toLowerCase()
//       // ... rest of method ...
//   }
//
// Returns false (caller should return) when arg is null or empty/whitespace-only.
// Returns true  (caller should continue) when arg contains a non-whitespace value.
// Null input:         logs WARN (same as requireNotNull, visible for debugging)
// Empty/blank input:  returns false SILENTLY (RM blank-slot convention; double-warning undesirable)
boolean requireNonEmptyEnum(arg, String methodName) {
    if (arg == null) {
        logWarn "${methodName} called with null arg (likely empty Rule Machine action parameter); ignoring"
        return false
    }
    if ((arg as String).trim().isEmpty()) {
        // Silent per design: empty string is RM blank-slot equivalent of null.
        // requireNotNull already handles null with a WARN; a second WARN here is undesirable.
        return false
    }
    return true
}

// BP26 safe numeric coercion: convert any command-arg type to Integer without
// ever throwing. Rule Machine and dashboard tiles can pass String, GString,
// BigDecimal, Boolean, or null. Plain `(x as Integer)` throws
// NumberFormatException/GroovyCastException on non-numeric/decimal/empty/boolean
// input BEFORE the ?: fallback can intercept it — the Hubitat sandbox swallows
// the exception silently, leaving the command a no-op with no log entry.
//
// Decimal strings/BigDecimals truncate toward zero (matches Groovy native int()
// semantics and is spec-asserted by LevoitCore200SSpec.groovy:796).
//
// W1 guard: BigDecimal values outside [Integer.MIN_VALUE, Integer.MAX_VALUE] fall
// back instead of bit-wrapping (BigDecimal.intValue() silently narrows on overflow).
//
// W2 warn: non-null, non-empty inputs that cannot be parsed log a one-line WARN
// before returning fallback. Null and empty-string inputs are silently → fallback
// (the routine Rule Machine blank-slot path; requireNotNull already handles null warn).
//
// Belt-and-suspenders with requireNotNull: keep the null-guard at call sites,
// use safeIntArg to handle the non-null-but-non-numeric vector.
private Integer safeIntArg(raw, Integer fallback = 0) {
    if (raw == null) return fallback
    try {
        String s = raw.toString().trim()
        if (s.isEmpty()) return fallback
        if (s.isInteger()) return s.toInteger()
        if (s.isBigDecimal()) {
            BigDecimal bd = s.toBigDecimal()
            if (bd > Integer.MAX_VALUE || bd < Integer.MIN_VALUE) {
                logWarn "safeIntArg: out-of-range input ${raw} -> using fallback ${fallback}"
                return fallback
            }
            return bd.intValue()
        }
        logWarn "safeIntArg: non-numeric input ${raw} -> using fallback ${fallback}"
        return fallback
    } catch (ignored) {
        logWarn "safeIntArg: non-numeric input ${raw} -> using fallback ${fallback}"
        return fallback
    }
}

// 4-arg clamp overload: coerce raw to Integer (W1/W2-hardened), then clamp to [lo, hi].
// Collapses Math.max(lo, Math.min(hi, safeIntArg(x, fallback))) to one call.
// Clamp is applied AFTER coercion and AFTER fallback — fallback is the pre-clamp value.
private Integer safeIntArg(raw, Integer fallback, Integer lo, Integer hi) {
    Integer v = safeIntArg(raw, fallback)
    return Math.max(lo, Math.min(hi, v))
}
