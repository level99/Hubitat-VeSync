/*
 * MIT License
 *
 * Copyright (c) Niklas Gustafsson
 * Portions copyright (c) 2026 Dan Cox (level99/Hubitat-VeSync community fork)
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
    name: "LevoitCorePurifier",
    namespace: "level99",
    author: "Dan Cox (level99) — extracted from NiklasGustafsson upstream",
    description: "Shared methods for Levoit Core 200S/300S/400S/600S air purifier drivers",
    documentationLink: "https://github.com/level99/Hubitat-VeSync",
    importUrl: "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitCorePurifierLib.groovy"
)

// Library contract:
//   REQUIRES: #include level99.LevoitDiagnostics  (provides recordError)
//   REQUIRES: #include level99.LevoitChildBase    (provides logInfo/logDebug/logError/ensureSwitchOn)
//   REQUIRES: host driver provides `def getSpeedBands()` returning a Map of
//             API-integer-level -> named-band (e.g. [1:"low",2:"medium",3:"high"]
//             for 3-band 200S/300S; add 4:"max" for 4-band 400S/600S). The
//             table-driven mapSpeedToInteger/mapIntegerToSpeed/mapIntegerStringToSpeed
//             helpers consume it (Bucket B1, #142 Phase 2c).
//   REQUIRES: host driver provides `def supportsAutoMode()` returning boolean —
//             false for 200S (no AQ sensor / no auto mode), true for 300S/400S/600S.
//             setSpeed/setMode consume it to gate the auto branch + allowed-mode set
//             (Bucket B2/B3, #142 Phase 2d).
//   PROVIDES: the shared Core-purifier methods used by all four Core drivers
//             (200S/300S/400S/600S), including handleEvent. The auto-mode commands +
//             AQI/filter parsing (used only by the AQ trio) live in LevoitCoreAQPurifier.

// ---- Group 1: Shared 18 — called by all four Core drivers (200S/300S/400S/600S) ----

def installed() {
	logDebug "Installed with settings: ${settings}"
    updated();
}

def uninstalled() {
	logDebug "Uninstalled app"
}

def initialize() {
	logDebug "initializing"
}

// Bucket A3 (#142 Phase 2a): byte-identical lifecycle across all 4 Core drivers.
// State-clear + unschedule + initialize + delayed runIn pattern; BP16 debug-watchdog
// records state.debugEnabledAt for cross-reboot auto-disable per LevoitChildBase.
def updated() {
    logDebug "Updated with settings: ${settings}"
    state.clear()
    unschedule()
    initialize()

    runIn(3, "update")

    // Turn off debug log in 30 minutes (happy path — no hub reboot)
    if (settings?.debugOutput) {
        runIn(1800, "logDebugOff")
        state.debugEnabledAt = now()
    } else {
        state.remove("debugEnabledAt")
    }
}

// Bucket A1 (#142 Phase 2a): byte-identical power-on across all 4 Core drivers.
// state.turningOn re-entrance guard protects against on()→setSpeed→on() recursion
// when state.mode is null (fresh device) — see setSpeed() recovery branch.
// Dispatches to per-driver setSpeed/setMode/update via Groovy dynamic dispatch.
def on() {
    logDebug "on()"

    if (state.turningOn) { logDebug "Already turning on, skipping re-entrant call"; return }
    state.turningOn = true
    try {
        handlePower(true)
        logInfo "Power on"
        handleEvent("switch", "on")

        if (state.speed != null) {
            setSpeed(state.speed)
        }
        else {
            setSpeed("low")
        }

        if (state.mode != null) {
            setMode(state.mode)
        }
        else {
            update()
        }
    } finally {
        state.remove('turningOn')
    }
}

// Bucket A2 (#142 Phase 2a): byte-identical power-off across all 4 Core drivers.
// state.turningOff re-entrance guard mirrors on()'s state.turningOn protection.
// Emits speed:off so dashboards/Rule Machine see speed go to off on power-down
// (capability parity — D2 fix landed in v2.5).
def off() {
    logDebug "off()"

    if (state.turningOff) { logDebug "Already turning off, skipping re-entrant call"; return }
    state.turningOff = true
    try {
        handlePower(false)
        logInfo "Power off"
        handleEvent("switch", "off")
        handleEvent("speed", "off")
    } finally {
        state.remove('turningOff')
    }
}

def toggle() {
    logDebug "toggle()"
	if (device.currentValue("switch") == "on")
		off()
	else
		on()
}

// BP24: NO-ON — configures a device preference; powering on is not implied.
def setDisplay(displayOn) {
    logDebug "setDisplay(${displayOn})"
    // BP18: null/empty-guard — Rule Machine passes null or "" for blank parameter slots.
    if (!requireNonEmptyEnum(displayOn, "setDisplay")) return false
    // BP25: normalize to lowercase, then derive canonical on/off for C3 gate and callee.
    // Passing canon (not raw v) to handleDisplayOn ensures the attribute emits "on"/"off",
    // never "true"/"1"/"yes". The C3 gate compares canonical vs canonical.
    String v = (displayOn as String).trim().toLowerCase()
    String canon = canonOnOff(v)
    // C3 state-change gate: no-op when value matches current attribute (suppresses redundant events)
    if (device.currentValue("display") == canon) return
    handleDisplayOn(canon)
}

def handlePower(on) {

    def result = false

    parent.sendBypassRequest(device, [
                data: [ enabled: on, id: 0 ],
                "method": "setSwitch",
                "source": "APP" ]) { resp ->
			if (checkHttpResponse("handleOn", resp))
			{
                def operation = on ? "ON" : "OFF"
                logDebug "turned ${operation}()"
				result = true
			}
		}
    return result
}

// Bucket B1 (#142 Phase 2c): table-driven speed mapping. Reads the host driver's
// getSpeedBands() to support both 3-band (200S/300S: [1:low,2:medium,3:high]) and
// 4-band (400S/600S: [1:low,2:medium,3:high,4:max]) models from one implementation.
//
// Behavior preserved exactly from the prior per-driver inline switch/ternary forms:
//   mapSpeedToInteger(name|"int-string") -> Integer:
//     - exact band-name match (low/medium/high[/max]) -> that band's key
//     - integer-string ("1".."4") that maps to a known band key -> that key
//     - anything else (unknown name, null, out-of-range int-string) -> max key
//       (3-band fallback=3, 4-band fallback=4) — matches the old `return 3/4` default
//   mapIntegerToSpeed(int) / mapIntegerStringToSpeed("int-string") -> String name:
//     - exact key match -> that band's name
//     - anything else (unknown int, null) -> max band name ("high" 3-band, "max" 4-band)
//       — matches the old ternary/switch default
//
// The fallback-to-max semantics make a real "high" lookup on a 3-band table and an
// unknown-input fallback indistinguishable in output (both -> 3 / "high"), exactly
// as the prior code behaved.

// Public (def) to match the prior per-driver method visibility exactly — the old
// inline mapSpeedToInteger/mapIntegerToSpeed/mapIntegerStringToSpeed were all `def`.
def mapSpeedToInteger(speed) {
    Map bands = getSpeedBands()
    Integer maxKey = bands.keySet().max()
    if (speed != null) {
        String s = speed.toString()
        // Named-band match (low/medium/high[/max])
        def named = bands.find { k, v -> v == s }
        if (named) return named.key as Integer
        // Integer-string match ("2" -> band key 2, if 2 is a valid band)
        if (s.isInteger()) {
            Integer asInt = s.toInteger()
            if (bands.containsKey(asInt)) return asInt
        }
    }
    // Unknown name, null, or out-of-range integer-string -> max band (old `return 3/4`)
    return maxKey
}

def mapIntegerToSpeed(level) {
    Map bands = getSpeedBands()
    Integer maxKey = bands.keySet().max()
    if (level != null) {
        // Accept Integer or numeric-String key
        Integer key = (level instanceof Number) ? (level as Integer)
                     : (level.toString().isInteger() ? level.toString().toInteger() : null)
        if (key != null && bands.containsKey(key)) return bands[key]
    }
    // Unknown int, null, or out-of-range -> max band name (old ternary/switch default)
    return bands[maxKey]
}

// Integer-STRING input ("1".."4") -> band name. Distinct public name retained because
// the per-driver setSpeed() integer-string remap path calls it by this name. Delegates
// to mapIntegerToSpeed (which already accepts numeric-String input) for one impl.
def mapIntegerStringToSpeed(speed) {
    return mapIntegerToSpeed(speed)
}

def handleSpeed(speed) {

    def result = false

    parent.sendBypassRequest(device, [
                data: [ level: mapSpeedToInteger(speed), id: 0, type: "wind" ],
                "method": "setLevel",
                "source": "APP"
            ]) { resp ->
			if (checkHttpResponse("handleSpeed", resp))
			{
                logDebug "Set speed"
				result = true
			}
		}
    return result
}

def handleMode(mode) {

    def result = false

    parent.sendBypassRequest(device, [
                data: [ "mode": mode ],
                "method": "setPurifierMode",
                "source": "APP"
            ]) { resp ->
			if (checkHttpResponse("handleMode", resp))
			{
                logDebug "Set mode"
				result = true
			}
		}
    return result
}

// Bucket B2 (#142 Phase 2d): table + auto-support parameterized setSpeed.
// Two host hooks capture the only per-model differences:
//   getSpeedBands()     — band table (Phase 2c); its keys derive the integer-string
//                         remap gate (["1","2","3"] for 3-band, +"4" for 4-band)
//   supportsAutoMode()  — false for 200S (no AQ sensor / no auto mode), true otherwise;
//                         gates the `s == "auto"` branch that 200S lacks
// BP18 null/empty-guard, BP25 lowercase-normalize, BP24-B ensureSwitchOn placement
// (after the off short-circuit) all preserved verbatim from the prior per-driver bodies.
// SHOULD-ON: setSpeed turns the device on (SwitchLevel/FanControl convention) via
// ensureSwitchOn() after the "off" short-circuit.
def setSpeed(speed) {
    logDebug "setSpeed(${speed})"
    if (!requireNonEmptyEnum(speed, "setSpeed")) return false                   // BP18 null/empty-guard
    String s = (speed as String).trim().toLowerCase()
    // Remap integer-string values from setLevel() path to named speed strings.
    // setLevel() passes Integer 1..N; (speed as String) yields "1".."N".
    // The valid integer-string set is derived from the band table (Phase 2c) so
    // 3-band (200S/300S) and 4-band (400S/600S) models share this one body.
    def intStringSet = getSpeedBands().keySet().collect { it.toString() }
    if (s in intStringSet) s = mapIntegerStringToSpeed(s)
    // Power short-circuits BEFORE ensureSwitchOn — setSpeed("off") must NOT auto-on first
    if (s == "off") { off(); return }
    // Reject unknown speed values BEFORE ensureSwitchOn() and before any cloud write.
    // Without this, an unrecognized value (e.g. "turbo") falls through to handleSpeed ->
    // mapSpeedToInteger, which defaults unknown input to the MAX band — silently widening
    // garbage to full speed AND auto-powering the device on. setMode already rejects invalid
    // modes; mirror that here. Valid named bands come from the band table; "sleep" is always
    // valid, "auto" only on auto-capable models.
    List validSpeeds = getSpeedBands().values().collect { it as String }
    validSpeeds << "sleep"
    if (supportsAutoMode()) validSpeeds << "auto"
    if (!(s in validSpeeds)) {
        logWarn "setSpeed: invalid speed '${s}' -- must be one of: ${validSpeeds.join(', ')}; ignoring"
        return
    }
    ensureSwitchOn()                                                             // BP24-B auto-on (after short-circuit)
    if (supportsAutoMode() && s == "auto") {
        setMode(s)
        state.speed = s
        handleEvent("speed", s)
    }
    else if (s == "sleep") {
        setMode(s)
        handleEvent("speed", "on")
    }
    else if (state.mode == "manual") {
        handleSpeed(s)
        state.speed = s
        handleEvent("speed", s)
        logInfo "Speed: ${s}"
    }
    else if (state.mode == "sleep") {
        setMode("manual")
        handleSpeed(s)
        state.speed = s
        handleEvent("speed", s)
        logInfo "Speed: ${s}"
    }
    else {
        // Recover: unknown or null state.mode (e.g. fresh device, pre-first-poll).
        // Guard against on() re-entrancy: when state.turningOn is set we are inside on()'s
        // own setSpeed call — skip mode establishment to avoid issuing a spurious
        // setPurifierMode that would clobber a concurrently-dispatched setMode command.
        // on() will call setMode(state.mode) or update() after this setSpeed returns.
        if (!state.turningOn) {
            handleMode("manual")
            state.mode = "manual"
            handleEvent("mode", "manual")
        }
        handleSpeed(s)
        state.speed = s
        handleEvent("speed", s)
        logInfo "Speed: ${s}"
    }
}

// Bucket B3 (#142 Phase 2d): auto-support parameterized setMode.
// supportsAutoMode() gates the allowed-mode set AND the auto switch-case branch:
//   false (200S) -> ["manual","sleep"]; true (300S/400S/600S) -> ["manual","sleep","auto"]
// BP18 null/empty-guard, BP25 lowercase-normalize, invalid-mode rejection BEFORE
// auto-on, BP24-B ensureSwitchOn after rejection, all preserved verbatim.
// SHOULD-ON: setMode turns the device on after the rejection checks via ensureSwitchOn().
def setMode(mode) {
    logDebug "setMode(${mode})"
    if (!requireNonEmptyEnum(mode, "setMode")) return false                     // BP18 null/empty-guard
    String m = (mode as String).trim().toLowerCase()
    List allowed = supportsAutoMode() ? ["manual", "sleep", "auto"] : ["manual", "sleep"]
    if (!(m in allowed)) {                                                       // reject invalid BEFORE auto-on
        logWarn "setMode: invalid mode '${m}' -- must be one of: ${allowed.join(', ')}; ignoring"
        return false
    }
    ensureSwitchOn()                                                             // BP24-B auto-on (after rejection checks)

    // Only commit state + emit events when the cloud accepted the mode change. Previously
    // state.mode + the mode/speed events were set unconditionally, creating a state-vs-reality
    // mismatch when handleMode failed (e.g. transient API error) — the device stayed in its prior
    // mode but the driver reported the new one. On failure, leave state untouched; the next poll
    // owns reconciliation. (Same fix class as CoreAQ setAutoMode.)
    boolean ok = handleMode(m)
    if (ok) {
        state.mode = m
        handleEvent("mode", m)
        logInfo "Mode: ${m}"

        switch(m)
        {
            case "manual":
                handleEvent("speed", state.speed)
                break;
            case "auto":
                handleEvent("speed", "auto")
                break;
            case "sleep":
                handleEvent("speed", "on")
                break;
        }
    } else {
        logError "Mode write failed: ${m}"
        recordError("Mode write failed: ${m}", [method:"setMode"])
    }
}

// Bucket B5 (#142 Phase 2e): table-driven speed cycle. The cycle order is the
// band table's values (Phase 2c getSpeedBands()), so 3-band (low/medium/high) and
// 4-band (low/medium/high/max) models share this body. Walks to the next band and
// wraps to the first; an unrecognized/null state.speed (indexOf == -1) wraps to the
// first band ("low"), matching the prior per-driver ternary's final-else and the
// switch's default. BP24-A: ensureSwitchOn() turns the device on if off (SHOULD-ON).
def cycleSpeed() {
    logDebug "cycleSpeed()"
    ensureSwitchOn()

    List order = getSpeedBands().values().toList()
    int idx = order.indexOf(state.speed)
    String next = order[(idx + 1) % order.size()]
    setSpeed(next)
}

// Bucket B4 (#142 Phase 2e): table-driven setLevel(value) 1-arg. The
// percentage-to-band thresholds are derived from the band count: for N bands the
// boundaries are (i*100).intdiv(N) for i in 1..N-1 — proven byte-exact across the
// full 0..100 domain against the prior per-driver hardcoded thresholds (3-band
// [33,66], 4-band [25,50,75]; verified zero mismatches at every percentage).
// band = 1 + (count of thresholds <= pct), giving 1..N.
// BP18 null-guard (safeIntArg), setLevel(0)==off short-circuit, BP23 ensureSwitchOn,
// and the setMode("manual") + sendEvent(level) + setSpeed(band) sequence are all
// preserved verbatim from the prior per-driver bodies.
def setLevel(value)
{
    logDebug "setLevel $value"
    // BP28: distinguish explicit 0 (-> off) from non-numeric garbage (-> ignore, device unchanged).
    // null/empty (BP18 blank-RM-slot) also -> null here and route to the ignore branch.
    Integer pct = parseLevelOrNull(value)
    if (pct == null) { logWarn "setLevel: ignoring non-numeric value '${value}'"; return }
    pct = Math.max(0, Math.min(100, pct))
    // SwitchLevel convention: setLevel(0) means off (Z-Wave dimmer platform expectation).
    if (pct == 0) { off(); return }
    // BP23: auto-on when switch is off (SwitchLevel capability convention).
    // state.turningOn re-entrance guard is inside ensureSwitchOn().
    ensureSwitchOn()
    setMode("manual") // always manual if setLevel() cmd was called

    // Derive the band from the per-model band count (Phase 2c getSpeedBands()).
    // Thresholds (i*100).intdiv(N) reproduce the exact prior boundaries:
    //   3-band -> [33,66] (<33→1, 33-65→2, ≥66→3)
    //   4-band -> [25,50,75] (<25→1, 25-49→2, 50-74→3, ≥75→4)
    int n = getSpeedBands().size()
    int speed = 1
    for (int i = 1; i < n; i++) {
        if (pct >= (i * 100).intdiv(n)) speed = i + 1
    }

    device.sendEvent(name: "level", value: pct)
    setSpeed(speed)
}

def handleDisplayOn(displayOn)
{
    logDebug "handleDisplayOn()"
    // displayOn is expected to be pre-normalized to lowercase by the setDisplay caller.
    // The payload coercion and event value both use it directly.

    def result = false

    parent.sendBypassRequest(device, [
                data: [ "state": (displayOn == "on")],
                "method": "setDisplay",
                "source": "APP"
            ]) { resp ->
			if (checkHttpResponse("handleDisplayOn", resp))
			{
                logDebug "Set display"
                device.sendEvent(name: "display", value: displayOn)
                logInfo "Display: ${displayOn}"
				result = true
			}
		}
    return result
}

// BP24: NO-ON — configures a device preference; powering on is not implied.
def setChildLock(value) {
    // Core-line API uses child_lock (boolean); Vital-line API uses childLockSwitch (integer). Intentional divergence per pyvesync class hierarchy.
    logDebug "setChildLock(${value})"
    // BP18: null/empty-guard — Rule Machine passes null or "" for blank parameter slots.
    if (!requireNonEmptyEnum(value, "setChildLock")) return false
    // BP25: normalize to lowercase, then derive canonical on/off for sendEvent and C3 gate.
    // Attribute always emits "on" or "off"; the C3 gate compares canonical vs canonical so
    // truthy-variant input ("TRUE", "1", "yes") does not defeat same-state suppression.
    String v = (value as String).trim().toLowerCase()
    String canon = canonOnOff(v)
    // C3 state-change gate: no-op when value matches current attribute (suppresses redundant events)
    if (device.currentValue("childLock") == canon) return
    def result = false
    parent.sendBypassRequest(device, [
                data: [ child_lock: (canon == "on") ],
                "method": "setChildLock",
                "source": "APP"
            ]) { resp ->
        if (checkHttpResponse("setChildLock", resp)) {
            device.sendEvent(name: "childLock", value: canon)
            logInfo "Child lock (Display Lock): ${canon}"
            result = true
        }
    }
    return result
}

def setTimer(seconds) {
    if (!requireNotNull(seconds, "setTimer")) return
    int secs = safeIntArg(seconds, 0)
    logDebug "setTimer(${secs}s)"
    if (secs <= 0) { cancelTimer(); return }
    def result = false
    parent.sendBypassRequest(device, [
                data: [ action: "off", total: secs ],
                "method": "addTimer",
                "source": "APP"
            ]) { resp ->
        if (checkHttpResponse("setTimer", resp)) {
            def tid = resp?.data?.result?.id
            if (tid != null) state.timerId = tid
            logInfo "Timer set: power off in ${secs}s (id=${tid})"
            result = true
        }
    }
    return result
}

def cancelTimer() {
    logDebug "cancelTimer()"
    if (!state.timerId) { logDebug "No active timer to cancel"; return }
    def result = false
    parent.sendBypassRequest(device, [
                data: [ id: state.timerId ],
                "method": "delTimer",
                "source": "APP"
            ]) { resp ->
        if (checkHttpResponse("cancelTimer", resp)) {
            state.remove("timerId")
            logInfo "Timer cancelled"
            result = true
        }
    }
    return result
}

def resetFilter() {
    logDebug "resetFilter()"
    def result = false
    parent.sendBypassRequest(device, [
                data: [:],
                "method": "resetFilter",
                "source": "APP"
            ]) { resp ->
        if (checkHttpResponse("resetFilter", resp)) {
            logInfo "Filter life reset"
            result = true
        }
    }
    return result
}

def checkHttpResponse(action, resp) {
	if (resp.status == 200 || resp.status == 201 || resp.status == 204)
		return true
	else if (resp.status == 400 || resp.status == 401 || resp.status == 404 || resp.status == 409 || resp.status == 500)
	{
		logError "${action}: ${resp.status} - ${resp.getData()}"
		recordError("${action}: ${resp.status}", [site:"checkHttpResponse"])
		return false
	}
	else
	{
		logError "${action}: unexpected HTTP response: ${resp.status}"
		recordError("${action}: unexpected HTTP response: ${resp.status}", [site:"checkHttpResponse"])
		return false
	}
}

// 1-arg parent callback — BP1 requires all three overloads; this delegator lives in the lib
// so all four Core drivers (200S/300S/400S/600S) inherit it automatically.
def update(status) { update(status, null) }

// Bucket A4 (#142 Phase 2a): 2-arg setLevel overload — Hubitat SwitchLevel capability standard signature.
// VeSync devices do NOT support hardware-level fade/duration, so the duration
// parameter is intentionally ignored. Delegates to the per-driver 1-arg version
// via Groovy dynamic dispatch.
// Without this overload, any caller using the standard 2-arg form (Rule Machine
// with duration, dashboard tiles, MCP setLevel(N, D), third-party apps) throws
// MissingMethodException — Hubitat sandbox catches it silently and the command
// fails without user feedback.
def setLevel(value, duration) {
    setLevel(value)
}

// handleEvent stays in LevoitCorePurifier (NOT moved to LevoitCoreAQPurifier in Phase 2b-cleanup):
// it is load-bearing for Core 200S, which calls it via the shared on()/off()/setSpeed()/setMode()
// in Group 1 above. The AQ trio (300S/400S/600S) also consumes it from here via #include.
// The auto-mode commands + AQI/filter parsing (setAutoMode/handleAutoMode/updateAQIandFilter/
// convertRange) relocated to LevoitCoreAQPurifierLib — 200S never references those.
private void handleEvent(name, val)
{
    logDebug "handleEvent(${name}, ${val})"
    device.sendEvent(name: name, value: val)
}
