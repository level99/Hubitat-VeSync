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

@groovy.transform.Field
static final Integer BYPASS_DEVICE_IS_OFF = 11005000  // pyvesync utils/errors.py: device powered OFF (expected, not a fault)

// BP30: upper bound (ms) on the async power-on storm window. A burst of overlapping
// on() commands inside this window collapses to ONE effective power+mode sequence; the
// window auto-closes after this many ms so a dropped cloud callback can never wedge the
// device permanently off. 4s comfortably spans the Vital configureOnState runInMillis(500)
// async leg plus cloud round-trip, while staying short enough that a genuine off->on a few
// seconds later is never blocked (off() also clears the window immediately).
@groovy.transform.Field
static final Integer POWER_ON_WINDOW_MS = 4000

// BP30 Layer 3: dedup window (ms) for identical mode/speed writes. Short by design — long
// enough to absorb a command burst (the observed storm was ~360 ms), short enough that an
// out-of-band "set it back" correction is only ever delayed by at most this much. NOT the
// power-on window (that one spans the slower async power-on leg); kept separate on purpose.
@groovy.transform.Field
static final Integer DUP_WRITE_WINDOW_MS = 2000

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

// BP30 async-window power-on storm guard. A burst of overlapping on() commands
// (Rule Machine double-fire, dashboard taps, automations all firing within a few
// hundred ms) would otherwise EACH issue the full power+speed+mode cloud sequence,
// colliding in flight and producing "...write failed" errors plus a device that never
// settles (the real-world incident: 3 on() in 360ms -> 4 "Mode write failed: manual",
// and a dependent nightlight child starved for ~20 min). This guard collapses a storm
// into ONE effective sequence.
//
// beginPowerOnWindow() opens the window synchronously and returns:
//   true  -> caller is the first on() in the window; proceed with the full sequence
//   false -> a power-on is already in flight; caller must no-op (skip the burst)
//
// The window is bounded TWO ways so it can never wedge the device in a can't-turn-on
// state: (1) a runInMillis safety timer fires clearPowerOnWindow() after
// POWER_ON_WINDOW_MS (string-literal handler form per Hubitat sandbox binding); and
// (2) a belt-and-suspenders elapsed-time check reopens the window if that timer was
// ever lost across the async boundary. off() also calls clearPowerOnWindow() so a
// deliberate off -> on sequence is never blocked.
//
// This is the determinism layer; it is only sound BECAUSE the driver declares
// singleThreaded:true (BP30 Layer 1) — that serializes command + async-callback
// execution so these state reads/writes are race-free.
boolean beginPowerOnWindow() {
    Long nowMs = now()
    Long openedAt = (state.powerOnWindowAt ?: 0L) as Long
    if (state.powerOnPending && (nowMs - openedAt) < POWER_ON_WINDOW_MS) {
        return false
    }
    state.powerOnPending = true
    state.powerOnWindowAt = nowMs
    // B3: cancel any prior safety timer before arming a fresh one so timers cannot stack
    // (e.g. when the elapsed-time check reopens a window whose runInMillis is still pending).
    unschedule("clearPowerOnWindow")
    runInMillis(POWER_ON_WINDOW_MS, "clearPowerOnWindow")
    return true
}

// BP30: close the async power-on window. Invoked by the runInMillis safety timer
// (handler resolved as a string literal), synchronously by off(), AND by a FAILED on()
// (B2 — so a power-on write failure does not hold the window open for the full
// POWER_ON_WINDOW_MS and suppress an immediate retry). Idempotent — safe to call when no
// window is open.
void clearPowerOnWindow() {
    // B3: cancel the pending safety timer so a later off->on cannot inherit an orphan timer
    // that closes the next window early. Harmless when called BY the timer itself (already fired).
    unschedule("clearPowerOnWindow")
    state.remove("powerOnPending")
    state.remove("powerOnWindowAt")
}

// BP30 Layer 3: time-windowed duplicate-write suppression for mode/speed setters. Returns
// true (caller should SKIP the cloud write) ONLY when an identical write — same `slot`
// (e.g. "mode" / "speed" / "fanSpeed") AND same `value` — was issued within
// DUP_WRITE_WINDOW_MS. That is the storm-duplicate case (the same command re-fired in a burst).
//
// An identical re-request OUTSIDE the window ALWAYS returns false (caller writes). This is the
// load-bearing anti-wedge property: it must remain possible to CORRECT a drifted cloud/device
// state from Hubitat. Hubitat's cached attribute can diverge from reality (physical button,
// VeSync app, Alexa routine, or a prior silently-failed write); an equality gate against the
// cached attribute would refuse every "set it back" retry and strand the user until the next
// poll. Time-scoping (not state-equality) drops ONLY burst duplicates. Poll reconciliation
// (applyStatus / refresh updating the attribute from real cloud state) is the drift backstop.
//
// Deliberately keyed on recent WRITES, never on device.currentValue(...) — so it is immune to
// cached-attribute drift by construction. Per-`slot` tracking (separate state fields per command
// type) so a mode write and a speed write never evict each other's dedup record. Records THIS
// write as the most recent on a non-suppressed (false) result.
boolean isDuplicateWrite(String slot, value) {
    String valField = "dupWriteVal_${slot}"
    String atField  = "dupWriteAt_${slot}"
    Long nowMs = now()
    Long lastAt = (state[atField] ?: 0L) as Long
    if (state[valField] == (value as String) && (nowMs - lastAt) < DUP_WRITE_WINDOW_MS) {
        return true
    }
    state[valField] = (value as String)
    state[atField] = nowMs
    return false
}

// BP30 Layer 3 (B1): clear a dedup slot so a FAILED write does not suppress an immediate
// same-value retry. isDuplicateWrite records the slot on-proceed (so the slot reflects the
// NEW effective value even on A1's early-return-delegation paths, where the actual cloud write
// happens in a delegated setter). When THIS site's cloud write then FAILS, call this from the
// write-failure branch to undo the record — restoring the pre-write state so the retry is not
// falsely deduped. Identical SUCCESSFUL writes still coalesce (success path does NOT clear).
void clearDuplicateWrite(String slot) {
    // String (not GString) keys — must match isDuplicateWrite's String valField/atField exactly,
    // or state.remove() with a GString key would fail to match the String-keyed entry and the
    // slot would NOT clear (GString and String with equal content are distinct Map keys).
    String valField = "dupWriteVal_${slot}"
    String atField  = "dupWriteAt_${slot}"
    state.remove(valField)
    state.remove(atField)
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

// Single source of truth for converting a PM2.5 reading (micrograms/m3) to a US-AQI
// (0-500) via the EPA breakpoint ladder. Shared so every AirQuality-capability driver
// emits the SAME `airQuality` semantics: the Core purifiers, EverestAir, and Sprout Air
// all report airQuality as this US-AQI, NOT a vendor categorical level. (airQualityIndex
// stays the Levoit 1-4 categorical level, a separate attribute.)
//
// Returns a BigDecimal whole number (same type/value the Core line emitted from its
// previous inline ladder, so Core's emitted aqi/airQuality are byte-identical). Returns
// null if pm is null or non-numeric — callers gate their emit on a non-null result,
// matching Core's "emit only when PM present" behavior. The linear-interpolation math
// mirrors LevoitCoreAQPurifierLib.convertRange exactly, including the toFloat().round()
// integer rounding (BigDecimal.round is unreliable on the Hubitat sandbox).
BigDecimal usAqiFromPm25(pm) {
    if (pm == null) return null
    BigDecimal p
    try {
        p = (pm instanceof BigDecimal) ? pm : new BigDecimal(pm.toString().trim())
    } catch (ignored) {
        return null
    }
    BigDecimal inMin, inMax, outMin, outMax
    if      (p <  12.1) { inMin =   0.0; inMax =  12.0; outMin =   0; outMax =  50 }
    else if (p <  35.5) { inMin =  12.1; inMax =  35.4; outMin =  51; outMax = 100 }
    else if (p <  55.5) { inMin =  35.5; inMax =  55.4; outMin = 101; outMax = 150 }
    else if (p < 150.5) { inMin =  55.5; inMax = 150.4; outMin = 151; outMax = 200 }
    else if (p < 250.5) { inMin = 150.5; inMax = 250.4; outMin = 201; outMax = 300 }
    else if (p < 350.5) { inMin = 250.5; inMax = 350.4; outMin = 301; outMax = 400 }
    else                { inMin = 350.5; inMax = 500.4; outMin = 401; outMax = 500 }
    // Restrain input to the band (mirrors convertRange).
    if (p < inMin) p = inMin
    else if (p > inMax) p = inMax
    BigDecimal v = ((p - inMin) * (outMax - outMin)) / (inMax - inMin) + outMin
    return v.toFloat().round().toBigDecimal()
}

// BP6 off-clamp: the single source of truth for "a level the device reports while
// powered OFF should display as 0, not the retained last-set value." VeSync keeps
// mist_virtual_level / warm_level / mistLevel etc. at their last-set value when the
// device is off; emitting them verbatim produces a "switch=off, Mist: L5" contradiction
// on dashboards. Every applyStatus level emit (mist + warm-mist event AND the info-tile
// equivalents) routes the active-level value through this helper before display.
// Returns 0 when off and the level is positive; otherwise the value unchanged (null
// passes through so the caller's own null-guard still governs whether to emit at all).
// NOT for SETPOINT values (Superior virtualLevel/level dimmer attribute) — those
// intentionally retain the target while off; only the "currently misting at" display
// is clamped.
def clampOffLevel(v, boolean powerOn) {
    // Accept AND return def (not strictly Integer): a future caller passing a null Boolean
    // or a String must not NPE/throw at the parameter boundary, AND the unchanged value
    // must pass through without an Integer-return coercion (which would throw on a String
    // or silently turn a Boolean into 0/1). The clamp only fires for a positive Number;
    // everything else (null, String, non-positive Number) passes through unchanged so the
    // caller's own null-guard still governs whether to emit. Current Integer callers still
    // receive an Integer (0 or the original Integer) — behavior is identical for them.
    return (!powerOn && v instanceof Number && v > 0) ? 0 : v
}

// Single source of truth for emitting the `temperature` attribute from a VeSync
// "F × 10" reading (e.g. 683 -> 68.3°F). Converts to the hub's configured scale
// (°F by default; °C on °C hubs / EU-AUS SKUs) and sendEvent's the rounded value
// with the matching unit. Every TemperatureMeasurement driver routes through here
// so temperature semantics are identical fork-wide (RULE56 flags any inline
// temperature emit that bypasses this helper — the hardcoded-°F C5 bug class).
//
// The caller supplies the raw F×10 value and OWNS the presence/zero guard: a null
// or 0 raw reading is an uninitialized/absent sensor and must not be emitted, so
// each caller wraps this in its existing `r.temperature != null` / `raw != 0` /
// `raw > 0` guard. Centralizing the emit here collapses the four hand-inlined
// temperature blocks (and their four ° unit literals) to a single site, which is
// the whole point of RULE56 — one sanctioned degree-sign emit, not four.
void emitTemperature(rawTempTimesTen) {
    double tempF = (rawTempTimesTen as Integer) / 10.0
    if (location?.temperatureScale == "C") {
        double tempC = (tempF - 32) * 5.0 / 9.0
        device.sendEvent(name:"temperature", value: Math.round(tempC * 10) / 10.0, unit:"°C")
    } else {
        device.sendEvent(name:"temperature", value: Math.round(tempF * 10) / 10.0, unit:"°F")
    }
}

// Total, never-throwing boolean coercion for VeSync flag fields (enabled, water_lacks,
// display, child_lock, warm_enabled, etc.) that may arrive as Boolean, Number (0/1), or
// (defensively) a String. Single source of truth — replaces the hand-inlined
// instanceof-Boolean-ternary-else-as-Integer sites that threw
// NumberFormatException when the field arrived as a non-numeric String (e.g. "false").
//
// Number semantics intentionally match the prior as-Integer-equals-1 form: ONLY 1 is
// true (2 -> false). Strings parse the truthy-variant set ("true"/"1"/"on"/"yes",
// case-insensitive, trimmed) -> true; anything else (incl. null, empty string, or an
// uncoercible object) -> false.
boolean asBool(raw) {
    if (raw instanceof Boolean) return raw
    if (raw instanceof Number)  return raw.intValue() == 1
    if (raw instanceof CharSequence) return raw.toString().trim().toLowerCase() in ["true","1","on","yes"]
    return false
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
        // Type-guard before peeling: on a non-JSON error response (CDN/gateway HTTP
        // 502/504 with a raw HTML body) resp.data is a non-null String. The ?. operator
        // guards null but NOT wrong-type, so a bare resp?.data?.result?.code would do a
        // property access on a String and throw MissingPropertyException inside this
        // async callback. inner is null on a non-Map body (no inner code to report).
        def inner = (resp?.data instanceof Map) ? resp.data.result?.code : null
        if (tag) logDebug "${tag} -> HTTP ${resp?.status}, inner ${inner}"
        if (cb) cb(resp)
    }
    return rspObj
}

private boolean httpOk(resp) {
    if (!resp) return false
    def st = resp.status as Integer
    if (st in [200,201,204]) {
        // Type-guard before the .result read: on a non-JSON body (a CDN/gateway HTTP 200
        // with an HTML interstitial, or a proxy error page) resp.data is a non-null String.
        // A bare resp?.data?.result?.code would then do a property access on a String and
        // throw MissingPropertyException, aborting the caller's command with a raw sandbox
        // stack trace. A non-Map body is not a valid success -> return false (mirrors the
        // hubBypass / isDeviceOffResp instanceof-Map guards elsewhere in this lib).
        if (!(resp.data instanceof Map)) {
            logDebug "HTTP ${st} with non-Map body (${resp.data instanceof String ? 'String' : 'non-Map'}); treating as failure"
            return false
        }
        def inner = resp.data.result?.code
        if (inner == null || inner == 0) return true
        // BP29: device-off (inner 11005000) is an EXPECTED rejection, not a fault. httpOk()
        // simply returns false; the caller's failure branch decides how to report it.
        // Branches routed through reportWriteFailure() emit one WARN (no ERROR/record) for
        // device-off — the classification is stateless (re-inspects the same resp), so it
        // cannot leak into a later unrelated error. (DEBUG note kept for trace fidelity.)
        logDebug "HTTP 200, innerCode ${inner}"
        return false
    }
    // BP22 child-side dedup: during a known network outage, parent.sendBypassRequest never
    // invokes the callback (breaker-return or httpPost throws), so resp stays the [status:-1]
    // transport-failure sentinel and this branch would log one ERROR + record per child per
    // retrigger for the whole outage. The parent already surfaces the outage (first-fire WARN
    // + hourly re-surface), so downgrade to a single DEBUG and skip the record. When NOT in a
    // known outage this is a genuine HTTP failure → unchanged ERROR + record.
    if (networkOutageKnown()) {
        logDebug "HTTP ${st} suppressed during known network outage (BP22)"
        return false
    }
    logError "HTTP ${st}"; recordError("HTTP ${st}", [method:"httpOk"])
    return false
}

// BP22 child-side dedup gate: true when the parent reports a known network outage
// (state.networkUnreachableSince set). Parent-null-safe by construction — in standalone
// or unit-test contexts where parent is null or lacks the method, returns false so the
// caller behaves EXACTLY as before this gate existed (full ERROR + recordError path).
private boolean networkOutageKnown() {
    try {
        if (parent?.respondsTo("isNetworkUnreachable")) {
            return parent.isNetworkUnreachable() ? true : false
        }
    } catch (ignored) {}
    return false
}

// BP29: stateless device-off predicate — inspects THIS envelope's inner result code.
// No persisted state, so it cannot misclassify a later call. Used by reportWriteFailure().
//
// Each level is instanceof-Map-guarded before the property access: on a non-JSON error
// response (e.g. a CDN/gateway HTTP 502/504 with a raw HTML body) the inner resp.data is a
// non-null String, so a bare resp?.data?.result would do a property access on a String and
// throw MissingPropertyException. The ?. operator only guards null, not wrong-type — hence
// the explicit type guards.
private boolean isDeviceOffResp(resp) {
    return (resp instanceof Map &&
            resp.data instanceof Map &&
            resp.data.result instanceof Map &&
            resp.data.result.code == BYPASS_DEVICE_IS_OFF)
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
    // device-off is checked FIRST: a device-off rejection (inner 11005000) only arrives on a
    // SUCCESSFUL round-trip, which cannot occur during a transport outage — so the order is
    // safe either way, but keeping it first guarantees a genuine device-off WARN is never
    // masked by the outage downgrade when NOT actually in an outage.
    if (isDeviceOffResp(resp)) {
        // BP29: device-off (inner 11005000) is an EXPECTED condition on V2 devices, not a
        // hardware fault — a powered-off device is a normal user state. So WARN once and skip
        // the diagnostics ring-buffer record. (pyvesync flags 11005000 critical_error=True;
        // this fork deliberately diverges. Full detail in docs/BUG-PATTERNS.md BP29.)
        logWarn "${tag}: device is off — VeSync rejected the command (BYPASS_DEVICE_IS_OFF, code ${BYPASS_DEVICE_IS_OFF}); not applied"
        return
    }
    // BP22 child-side dedup: during a known network outage the resp is a transport-failure
    // sentinel (status -1 / 500, no inner 11005000). Downgrade to a single DEBUG and skip the
    // record — the parent already surfaces the outage. Outside an outage → genuine fault path.
    if (networkOutageKnown()) {
        logDebug "${tag} (suppressed during known network outage, BP22)"
        return
    }
    logError tag
    recordError(tag, ctx)
}

// BP22 child-side dedup: network-aware reporter for the per-driver bare write-fail branches
// that do NOT have a `resp` in scope to route through reportWriteFailure() (e.g. on()/off()'s
// "Failed to turn on/off device" and setSpeedLevel/setMode "write failed" branches, where the
// write went through handlePower()/httpOk() and only a boolean ok flag survives). Centralizing
// these through one helper makes the whole write-fail-error class network-aware in one place.
//   - known outage  => single DEBUG, no ERROR, no record (parent already surfaced the outage)
//   - otherwise     => the prior behavior: logError(tag) + recordError(tag, ctx)
def reportWriteError(String tag, Map ctx = [:]) {
    if (networkOutageKnown()) {
        logDebug "${tag} (suppressed during known network outage, BP22)"
        return
    }
    logError tag
    recordError(tag, ctx)
}

// Emit the `switch` attribute from a poll/status parse AND keep state.lastSwitchSet in sync.
// toggle() prefers state.lastSwitchSet over device.currentValue("switch") as a synchronous
// read-after-write mirror (currentValue is not updated synchronously right after sendEvent within a
// rapid toggle sequence). on()/off() set it on the write path; a poll switch-emit MUST also update it
// here, otherwise an EXTERNAL power change (VeSync app / physical button / Alexa) seen only by the poll
// leaves a stale lastSwitchSet shadowing the fresh switch attribute forever — so toggle() would keep
// inverting the wrong way after any out-of-band on/off. Enforced by RULE54.
void emitSwitchState(powerOn) {
    String v = powerOn ? "on" : "off"
    device.sendEvent(name:"switch", value: v)
    state.lastSwitchSet = v
}
