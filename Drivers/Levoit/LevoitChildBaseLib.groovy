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

// BP29: VeSync rejects a bypassV2 write with this inner result code when the target
// device is powered OFF (fleet-wide cloud behavior on V2 devices — affects setDisplay,
// setMistLevel, setTargetHumidity, setChildLock, etc.). This is an EXPECTED condition,
// not a fault. A write-failure branch that routes through reportWriteFailure() inspects
// the returned envelope at call time: device-off => one clear WARN (no ERROR, no
// diagnostics ring-buffer record); any other failure => the existing logError + recordError.
// Cross-ref pyvesync src/pyvesync/utils/errors.py: 11005000 = "BYPASS_DEVICE_IS_OFF".
// Note: pyvesync flags 11005000 critical_error=True, but this fork deliberately treats
// device-off as an expected non-fault (WARN) because a powered-off device is a normal
// user state, not a hardware error.
@groovy.transform.Field
static final Integer BYPASS_DEVICE_IS_OFF = 11005000

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
Integer safeIntArg(raw, Integer fallback = 0) {
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
Integer safeIntArg(raw, Integer fallback, Integer lo, Integer hi) {
    Integer v = safeIntArg(raw, fallback)
    return Math.max(lo, Math.min(hi, v))
}

// BP28 level parser: the SwitchLevel/MistLevel-aware variant of safeIntArg.
//
// safeIntArg substitutes its fallback (0) on NON-numeric input, which makes a
// typo like setMistLevel("hgih") indistinguishable from an explicit
// setMistLevel(0): both coerce to 0 and turn the device OFF. parseLevelOrNull
// lets level/mist setters tell these two cases apart:
//
//   - genuinely-numeric input ("0", "5", "5.7") -> the parsed Integer
//     (decimal truncates toward zero, matching safeIntArg's int() semantics)
//   - non-numeric / unparseable / null / empty input -> null
//
// Callers branch on the result:
//
//   Integer lvl = parseLevelOrNull(level)
//   if (lvl == null) { logWarn "setMistLevel: ignoring non-numeric value '${level}'"; return }
//   if (lvl <= 0)    { off(); return }   // explicit 0 -> off, contract preserved
//   ...clamp + ensureSwitchOn + cloud write...
//
// Use ONLY where a non-numeric value should leave the device unchanged rather
// than power it off (the level->off branch). For sites where the fallback-0
// is itself a valid clamp floor (setHumidity, brightness) or means "no timer"
// (setTimer), keep safeIntArg — those do not have an off()-on-zero branch.
//
// W1 guard: out-of-range BigDecimals (beyond int range) return null (cannot be
// represented as an Integer level; treated as garbage rather than bit-wrapped).
Integer parseLevelOrNull(raw) {
    if (raw == null) return null
    try {
        String s = raw.toString().trim()
        if (s.isEmpty()) return null
        if (s.isInteger()) return s.toInteger()
        if (s.isBigDecimal()) {
            BigDecimal bd = s.toBigDecimal()
            if (bd > Integer.MAX_VALUE || bd < Integer.MIN_VALUE) return null
            return bd.intValue()
        }
        return null
    } catch (ignored) {
        return null
    }
}

// BP25 canonical on/off coercion: the single blessed source for the permissive
// truthy-variant set. Returns "on" when the (already-normalized, lowercase) input
// is one of "on"/"true"/"1"/"yes"; otherwise "off". The input is re-normalized
// internally (toString().trim().toLowerCase()) so the helper is idempotent and
// safe whether the caller passes the raw arg or its normalized form — at every
// migrated call site the input is already the normalized `v`/`val`, so behavior is
// byte-identical to the inline `(v in ["on","true","1","yes"]) ? "on" : "off"`.
// Null input returns "off" (never throws); current call sites guard upstream via
// requireNonEmptyEnum so null never actually reaches here.
//
// Usage (canonical pattern — see LevoitHumidifierLib.doSetDisplayScreenSwitch):
//
//   String v     = (onOff as String).trim().toLowerCase()
//   String canon = canonOnOff(v)
//   if (device.currentValue("attr") == canon) return   // C3 gate uses canon
//   Integer sw   = (canon == "on") ? 1 : 0              // payload uses canon
//   device.sendEvent(name:"attr", value: canon)         // event emits canon
//
// Centralizing the truthy-variant list here prevents a future site from getting the
// set wrong (the duplication risk that motivated the v2.8 extraction).
//
// INTENTIONAL EXCEPTION — LevoitFanLib does NOT use this helper. The fan line's
// feature-toggle setters (doSetMuteSwitch / doSetDisplayScreenSwitch) apply a
// STRICT enum-rejection gate (any value outside "on"/"off" is rejected, truthy
// variants are unreachable) — a documented behavioral divergence from
// LevoitHumidifierLib's permissive coercion. The strict form must stay inline.
String canonOnOff(v) {
    if (v == null) return "off"
    return ((v.toString().trim().toLowerCase()) in ["on","true","1","yes"]) ? "on" : "off"
}

// BP12: seed pref defaults at first poll method invocation.
// Idempotent via state.prefsSeeded gate; safe to call repeatedly.
// Insert at top of: applyStatus() for V2-API drivers; update(status,nightLight)
// for Core line; etc. See CONTRIBUTING.md / CLAUDE.md "Pref-seed pattern" for
// the full insertion-point table per driver shape.
//
// Heals descriptionTextEnable=true default for users migrated from older
// Driver Type without clicking Save Preferences.
//
// NOTE: This helper is for child drivers only. The parent driver
// (VeSyncIntegration.groovy) and Notification Tile keep this pattern inline
// because they cannot #include level99.LevoitChildBase — the include's
// textual paste at file end would shadow their own logInfo/logDebug
// definitions (parent's logInfo routes through sanitize() for PII
// redaction; shadow would break that). VeSyncIntegrationVirtual seeds 2
// prefs and stays inline as a deliberate outlier.
private void seedPrefs() {
    if (state.prefsSeeded) return
    if (settings?.descriptionTextEnable == null) {
        device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
    }
    state.prefsSeeded = true
}

// BP3: peel bypassV2 envelope layers; returns innermost result Map (or
// empty Map if response is malformed). Up to 4 layers of [code, result,
// traceId] wrapping — handles both single-wrap (purifier shape) and
// double-wrap (humidifier shape) transparently.
//
// Call shape: replace
//   def r = response?.result ?: [:]
//   int peelGuard = 0
//   while (r instanceof Map && r.containsKey('code') && r.containsKey('result') && r.result instanceof Map && peelGuard < 4) {
//       r = r.result
//       peelGuard++
//   }
// with:
//   def r = peelEnvelope(response)
//
// Behavior is byte-identical to the pre-existing inline pattern.
private Map peelEnvelope(Map response) {
    def r = response?.result ?: [:]
    int peelGuard = 0
    while (r instanceof Map && r.containsKey('code') && r.containsKey('result') && r.result instanceof Map && peelGuard < 4) {
        r = r.result
        peelGuard++
    }
    return (r instanceof Map) ? (r as Map) : [:]
}

// Hub/parent call wrapper — invokes parent.sendBypassRequest with a standard
// bypassV2 envelope ([method, source:"APP", data]) and returns a synchronous
// [status, data] result captured from the parent's response callback.
// Optional tag adds a one-line debug trace; optional cb forwards the raw resp.
//
// recordError (called from httpOk below) resolves to the consumer driver's own
// definition at compile time — either #include level99.LevoitDiagnostics, or a
// local no-op stub (LevoitGeneric) — since the include's textual paste compiles
// inside the consumer unit.
private hubBypass(method, Map data=[:], tag=null, cb=null) {
    def rspObj = [status: -1, data: null]
    parent.sendBypassRequest(device, [method: method, source: "APP", data: data]) { resp ->
        rspObj = [status: resp?.status, data: resp?.data]
        def inner = resp?.data?.result?.code
        if (tag) logDebug "${tag} -> HTTP ${resp?.status}, inner ${inner}"
        if (cb) cb(resp)
    }
    return rspObj
}

private boolean httpOk(resp) {
    if (!resp) return false
    def st = resp.status as Integer
    if (st in [200,201,204]) {
        def inner = resp?.data?.result?.code
        if (inner == null || inner == 0) return true
        // BP29: device-off (inner 11005000) is an EXPECTED rejection, not a fault. httpOk()
        // simply returns false; the caller's failure branch decides how to report it.
        // Branches routed through reportWriteFailure() emit one WARN (no ERROR/record) for
        // device-off — the classification is stateless (re-inspects the same resp), so it
        // cannot leak into a later unrelated error. (DEBUG note kept for trace fidelity.)
        logDebug "HTTP 200, innerCode ${inner}"
        return false
    }
    logError "HTTP ${st}"; recordError("HTTP ${st}", [method:"httpOk"])
    return false
}

// BP29: stateless device-off predicate — inspects THIS envelope's inner result code.
// No persisted state, so it cannot misclassify a later call. Used by reportWriteFailure().
private boolean isDeviceOffResp(resp) {
    return (resp?.data?.result?.code == BYPASS_DEVICE_IS_OFF)
}

// BP29: stateless write-failure reporter. Call from a write-failure branch (after
// httpOk(resp) returned false) instead of a bare `logError(...) + recordError(...)`:
//
//   def resp = hubBypass("setDisplay", [screenSwitch: v], "setDisplay")
//   if (httpOk(resp)) { ...success... }
//   else reportWriteFailure("Display write failed", resp, [method:"setDisplay"])
//
// Leak-free by construction: the device-off vs genuine-fault decision is made from the
// `resp` passed in at call time, NOT from any cross-call sentinel. A device-off rejection
// on one command can never suppress a different command's genuine error.
//   - device-off (11005000)  => ONE WARN, no ERROR, no diagnostics ring-buffer record.
//     The WARN message is PII-free by construction (numeric code + the static tag only;
//     no user input, email, token, or device identifier is interpolated). Full child-level
//     logWarn sanitize routing is a separate, broader change (out of scope here).
//   - any other inner code / HTTP failure => the prior behavior: logError + recordError.
// `tag` is the human-readable failure message; `ctx` is the recordError context map.
def reportWriteFailure(String tag, resp, Map ctx = [:]) {
    if (isDeviceOffResp(resp)) {
        logWarn "${tag}: device is off — VeSync rejected the command (BYPASS_DEVICE_IS_OFF, code ${BYPASS_DEVICE_IS_OFF}); not applied"
        return
    }
    logError tag
    recordError(tag, ctx)
}
