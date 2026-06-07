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
    name: "LevoitFan",
    namespace: "level99",
    author: "Dan Cox (level99)",
    description: "Shared infrastructure + V2-fan body methods for Levoit fan drivers (Tower Fan LTF-F422S, Pedestal Fan LPF-R432S).",
    documentationLink: "https://github.com/level99/Hubitat-VeSync",
    importUrl: "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitFanLib.groovy"
)

// REQUIRES: #include level99.LevoitDiagnostics  (provides recordError)
// REQUIRES: #include level99.LevoitChildBase    (provides logInfo/logDebug/logError/logWarn,
//                                                ensureDebugWatchdog, ensureSwitchOn, requireNotNull,
//                                                hubBypass, httpOk)
//
// PROVIDES:
//   Cross-family infra (12 methods):
//     Lifecycle:  installed, updated, uninstalled, initialize, refresh
//     Power:      on, off, toggle
//     Polling:    update(status) [1-arg], update(status, nightLight) [2-arg]
//     HTTP:       hubBypass, httpOk
//
//   Shared V2-fan body (10 methods):
//     cycleSpeed   — advances 1-12 speed rotation; includes BP24-B auto-on guard (ensureSwitchOn)
//     setLevel(val, duration) [2-arg], setLevel(val) [1-arg]
//     sendLevel    — raw 1-12 speed write (V2-API {levelIdx, levelType, manualSpeedLevel})
//     levelToFanControlEnum, fanControlEnumToLevel
//     percentFromLevel, levelFromPercent
//     doSetMuteSwitch      — inner body for setMute (payload {muteSwitch: int}, emits mute)
//     doSetDisplayScreenSwitch — inner body for setDisplay (payload {screenSwitch: int}, emits displayOn)
//
//   Shared applyStatus body blocks (5 methods) — emit events, return locals for info HTML:
//     applyFanCommonHead      — power + speed + mode; returns [powerOn, activeSpeed, reportedMode]
//     applyFanMuteDisplay     — mute + display; returns muteState
//     applyFanTemperature     — ambient temp (raw / 10 = °F, sanity-gated)
//     applyFanSleepPreference — nested sleepPreference -> sleepPreferenceType
//     applyFanErrorCode       — errorCode event + first-transition INFO log
//
// NOT in this lib — per-driver retained methods:
//   update() [0-arg]: API status method names differ per driver:
//       Tower Fan  → getTowerFanStatus
//       Pedestal Fan → getFanStatus
//     DO NOT extract the 0-arg update() into this lib. The method body is otherwise
//     identical, but Groovy has no "constant template" mechanism, and injecting a
//     parameter from the driver would break the humidifier lib precedent. Each
//     driver keeps its own 0-arg update() calling the correct API method.
//   setMode:  API method name + valid mode set both differ (setTowerFanMode/auto vs setFanMode/eco)
//   setSpeed: one-line semantic divergence — Tower maps "auto" enum to setMode("auto");
//             Pedestal maps "auto" enum to setMode("eco").
//   applyStatus: the public entry point + preamble (ensureDebugWatchdog/seedPrefs/
//                peelEnvelope/raw-dump) stays per-driver, as does the oscillation parsing
//                (single-axis Tower setOscillationSwitch vs 2-axis Pedestal + range +
//                coordinate + calibration + high-temp + childLock fields) and the info-HTML
//                content (single "Oscillation:" + Timer vs "H-Osc:"/"V-Osc:"). The shared
//                head/mute-display/temperature/sleepPreference/errorCode blocks ARE extracted
//                into the applyFan* helpers above; each driver's applyStatus() orchestrates
//                them interleaved with its divergent blocks to preserve event-emission order.

// ---- Lifecycle ----

def installed() {
    logDebug "Installed ${settings}"
    updated()
}

def updated() {
    logDebug "Updated ${settings}"
    state.clear(); unschedule(); initialize()
    runIn(3, "refresh")
    // Turn off debug log in 30 minutes (happy path — no hub reboot)
    if (settings?.debugOutput) {
        runIn(1800, "logDebugOff")
        state.debugEnabledAt = now()
    } else {
        state.remove("debugEnabledAt")
    }
}

def uninstalled() {
    logDebug "Uninstalled"
}

def initialize() {
    logDebug "Initializing"
}

// ---- Refresh ----

def refresh() {
    update()
}

// ---- Power ----
// Switch payload is purifier-style: {powerSwitch: int, switchIdx: 0}
// NOT humidifier-style {enabled: bool, id: 0}

def on() {
    logDebug "on()"
    // state.turningOn prevents BP23 re-entrance: setLevel(N) -> on() -> (internal speed call) -> setLevel()
    if (state.turningOn) { logDebug "Already turning on, skipping re-entrant call"; return }
    state.turningOn = true
    try {
        def resp = hubBypass("setSwitch", [powerSwitch: 1, switchIdx: 0], "setSwitch(power=1)")
        if (httpOk(resp)) {
            logInfo "Power on"
            state.lastSwitchSet = "on"
            device.sendEvent(name:"switch", value:"on")
        } else {
            logError "Power on failed"; recordError("Power on failed", [method:"setSwitch"])
        }
    } finally {
        state.remove('turningOn')
    }
}

def off() {
    logDebug "off()"
    // Defensive symmetry with on()'s guard; no active re-entrance vector into off() today.
    if (state.turningOff) { logDebug "Already turning off, skipping re-entrant call"; return }
    state.turningOff = true
    try {
        def resp = hubBypass("setSwitch", [powerSwitch: 0, switchIdx: 0], "setSwitch(power=0)")
        if (httpOk(resp)) {
            logInfo "Power off"
            state.lastSwitchSet = "off"
            device.sendEvent(name:"switch", value:"off")
        } else {
            logError "Power off failed"; recordError("Power off failed", [method:"setSwitch"])
        }
    } finally {
        state.remove('turningOff')
    }
}

// state.lastSwitchSet preferred over device.currentValue() to avoid the read-after-write
// race (the new event from on()/off() may not be queryable yet on a same-tick toggle()).
// Falls back to device.currentValue("switch") when state isn't seeded yet (first-call case).
def toggle() {
    logDebug "toggle()"
    String current = state.lastSwitchSet ?: device.currentValue("switch")
    current == "on" ? off() : on()
}

// ---- Update / status ----
// NOTE: the 0-arg update() is NOT provided by this lib — see header comment above.

// 1-arg parent callback
def update(status) {
    logDebug "update() from parent (1-arg)"
    applyStatus(status)
    return true
}

// 2-arg parent callback — REQUIRED (BP#1); parent always calls with two args.
// nightLight parameter is ignored — fans have no night-light hardware.
def update(status, nightLight) {
    logDebug "update() from parent (2-arg, nightLight ignored — fans have no nightlight)"
    applyStatus(status)
    return true
}

// ---- FanControl: cycleSpeed ----
// BP24-B fix: ensureSwitchOn() turns the device on if it is currently off before
// sending the speed command. Matches the SwitchLevel capability convention that
// calling a level/speed command on an off device should turn it on first.
// ensureSwitchOn() is provided by #include level99.LevoitChildBase.
def cycleSpeed() {
    logDebug "cycleSpeed()"
    ensureSwitchOn()
    Integer cur = state.fanLevel as Integer ?: 1
    Integer next = (cur >= 12) ? 1 : (cur + 1)
    sendLevel(next)
}

// ---- SwitchLevel ----

// 2-arg setLevel overload — Hubitat SwitchLevel capability standard signature.
// VeSync devices do NOT support hardware-level fade/duration, so the duration
// parameter is intentionally ignored. Delegates to the 1-arg version.
// Without this overload, any caller using the standard 2-arg form (Rule Machine
// with duration, dashboard tiles, MCP setLevel(N, D), third-party apps) throws
// MissingMethodException — Hubitat sandbox catches it silently and the command
// fails without user feedback.
def setLevel(val, duration) {
    setLevel(val)
}

// SwitchLevel capability: setLevel(percent 0-100) -> map to 1-12
// SwitchLevel convention: setLevel(0) turns the device off (matches Z-Wave dimmer platform expectation).
// BP23: setLevel(N>0) auto-turns-on when switch is off (SwitchLevel capability convention).
def setLevel(val) {
    logDebug "setLevel(${val})"
    // BP28: distinguish explicit 0 (-> off) from non-numeric garbage (-> ignore, device unchanged).
    Integer pct = parseLevelOrNull(val)
    if (pct == null) { logWarn "setLevel: ignoring non-numeric value '${val}'"; return }
    pct = Math.max(0, Math.min(100, pct))
    if (pct == 0) { off(); return }
    // BP23: auto-on when switch is off (SwitchLevel capability convention).
    // state.turningOn re-entrance guard is inside ensureSwitchOn().
    ensureSwitchOn()
    Integer lvl = levelFromPercent(pct)
    // SwitchLevel spec requires emitting the level event immediately
    sendEvent(name:"level", value: pct)
    sendLevel(lvl)
}

// ---- Internal helpers ----

// Send a raw 1-12 fan speed level to the device.
// Uses V2-API field names: levelIdx, levelType, manualSpeedLevel (Bug Pattern #4).
// NOT legacy Core-line names: id, type, level.
private boolean sendLevel(Integer level) {
    logDebug "sendLevel(${level})"
    if (level < 1 || level > 12) {
        logError "sendLevel: invalid level ${level} -- must be 1-12"
        recordError("sendLevel: invalid level ${level}", [method:"setLevel"])
        return false
    }
    def resp = hubBypass("setLevel", [levelIdx: 0, levelType: "wind", manualSpeedLevel: level], "setLevel{levelIdx,levelType,manualSpeedLevel=${level}}")
    if (httpOk(resp)) {
        state.fanLevel = level
        String enumVal = levelToFanControlEnum(level)
        device.sendEvent(name:"speed", value: enumVal)
        device.sendEvent(name:"level", value: percentFromLevel(level))
        logInfo "Speed: L${level} (${enumVal})"
        return true
    } else {
        reportWriteError("Speed write failed for level ${level}", [method:"setLevel"])
        return false
    }
}

// Map raw fan level 1-12 to Hubitat FanControl capability speed enum.
// Hubitat FanControl enum: off | low | medium-low | medium | medium-high | high | on | auto
// 12 levels -> 5 non-auto buckets: low(1-2), medium-low(3-4), medium(5-6), medium-high(7-8), high(9-12)
private String levelToFanControlEnum(Integer level) {
    if (level == null || level <= 0) return "off"
    if (level <= 2)  return "low"
    if (level <= 4)  return "medium-low"
    if (level <= 6)  return "medium"
    if (level <= 8)  return "medium-high"
    return "high"   // 9-12
}

// Map Hubitat FanControl speed enum back to a 1-12 representative level for writes.
private Integer fanControlEnumToLevel(String s) {
    switch (s?.toLowerCase()) {
        case "low":         return 2
        case "medium-low":  return 4
        case "medium":      return 6
        case "medium-high": return 8
        case "high":        return 10
        default:            return null
    }
}

// Map raw level 1-12 to SwitchLevel percentage 0-100.
private Integer percentFromLevel(Integer level) {
    if (level == null || level < 1) return 8   // level 1 = ~8%
    if (level >= 12) return 100
    // Map 1-12 linearly: level 1 = 8%, level 12 = 100%
    return Math.round(((level - 1) / 11.0) * 92 + 8) as Integer
}

// Map SwitchLevel percentage 0-100 to raw 1-12 level.
private Integer levelFromPercent(Integer pct) {
    if (pct == null || pct <= 0) return 1
    if (pct >= 100) return 12
    return Math.max(1, Math.min(12, Math.round(((pct - 8) / 92.0) * 11 + 1) as Integer))
}

// ---- Shared V2-fan feature setters ----
// Used by Tower Fan and Pedestal Fan (both use identical payload shapes for mute + display).
// Each driver exposes a 1-line public delegator:
//   def setMute(o)    { doSetMuteSwitch(o) }
//   def setDisplay(o) { doSetDisplayScreenSwitch(o) }
// This keeps the public method name stable while the body is shared.
// doSetDisplayScreenSwitch uses the same name as LevoitHumidifierLib's helper — intentional.
// No conflict arises because a driver only #includes one of the two libs.
// Behavioral divergence from LevoitHumidifierLib's helper of the same name: this version
// applies a strict enum-rejection gate (any value outside "on"/"off" is an error). The fan
// line uses strict-enum validation on all feature-toggle setters; the humidifier version omits
// that gate and relies only on truthy coercion — a permissive legacy-input choice preserved for
// back-compat with that driver family.

// BP24: NO-ON — configures a device preference; powering on is not implied.
def doSetMuteSwitch(onOff) {
    logDebug "setMute(${onOff})"
    if (!requireNonEmptyEnum(onOff, "setMute")) return
    String s = (onOff as String).trim().toLowerCase()
    if (!(s in ["on","off"])) { logError "setMute: invalid value '${s}'"; recordError("setMute invalid: ${s}", [method:"setMuteSwitch"]); return }
    // C3 idempotency gate: suppress redundant API call when attribute already matches.
    if (device.currentValue("mute") == s) return
    int v = (s == "on") ? 1 : 0  // strict-enum gate above guarantees s is "on" or "off"; truthy variants are unreachable
    def resp = hubBypass("setMuteSwitch", [muteSwitch: v], "setMuteSwitch(${s})")
    if (httpOk(resp)) {
        device.sendEvent(name:"mute", value: s)
        logInfo "Mute: ${s}"
    } else {
        // BP29: device-off => one WARN (expected); any other failure => logError + record.
        reportWriteFailure("Mute write failed", resp, [method:"setMuteSwitch"])
    }
}

// BP24: NO-ON — configures a device preference; powering on is not implied.
def doSetDisplayScreenSwitch(onOff) {
    logDebug "setDisplay(${onOff})"
    if (!requireNonEmptyEnum(onOff, "setDisplay")) return
    String s = (onOff as String).trim().toLowerCase()
    if (!(s in ["on","off"])) { logError "setDisplay: invalid value '${s}'"; recordError("setDisplay invalid: ${s}", [method:"setDisplay"]); return }
    // C3 idempotency gate: suppress redundant API call when attribute already matches.
    if (device.currentValue("displayOn") == s) return
    int v = (s == "on") ? 1 : 0  // strict-enum gate above guarantees s is "on" or "off"; truthy variants are unreachable
    def resp = hubBypass("setDisplay", [screenSwitch: v], "setDisplay(${s})")
    if (httpOk(resp)) {
        device.sendEvent(name:"displayOn", value: s)
        logInfo "Display: ${s}"
    } else {
        reportWriteFailure("Display write failed", resp, [method:"setDisplay"])
    }
}

// Informational note for the 5 oscillation/range setters (Tower + Pedestal): when the
// fan is off, the cloud accepts an oscillation/range write but the device silently
// discards it (oscillation geometry only persists while running, live-verified). The
// command is still SENT (BP29 "send & let the cloud be the authority" — no skip/pre-check);
// this INFO just keeps a rule author from being confused when the setting doesn't appear
// to take until the fan is powered on. Gated by descriptionTextEnable via logInfo.
private void noteOscillationOffState() {
    if (device.currentValue("switch") != "on") {
        logInfo "Fan is off — oscillation setting will apply when the fan is powered on"
    }
}

// ---- Shared applyStatus body blocks ----
// Tower Fan and Pedestal Fan share the head (power/speed/mode) and the
// mute/display/temperature/sleepPreference/errorCode blocks verbatim. Only the
// oscillation parsing (single-axis Tower vs 2-axis Pedestal + range/coordinate/
// calibration/high-temp/childLock extras) and the info-HTML content differ — those
// stay inline in each driver. Each driver's applyStatus() does the preamble
// (ensureDebugWatchdog/seedPrefs/peelEnvelope/raw-dump) itself, then orchestrates
// these shared blocks interleaved with its own divergent blocks so per-driver
// event-emission ordering is preserved exactly.
//
// applyFanCommonHead returns a Map of locals downstream blocks + info HTML need:
//   [powerOn: boolean, activeSpeed: Integer, reportedMode: String]

// Power + fan speed + mode. Identical across both fan families.
// Bug Pattern #6: when device is off, speed reports "off" regardless of last-set level.
// Mode CROSS-CHECK: reverse-map API "advancedSleep" -> user-facing "sleep" (pyvesync
// device_map.py LTF-F422S/LPF-R432S FanModes.SLEEP:'advancedSleep' + HA finding #d).
// Other modes (Tower: normal/turbo/auto; Pedestal: normal/turbo/eco) pass through unchanged.
private Map applyFanCommonHead(Map r) {
    // ---- Power ----
    boolean powerOn = (r.powerSwitch as Integer) == 1
    device.sendEvent(name:"switch", value: powerOn ? "on" : "off")

    // ---- Fan speed ----
    // Prefer fanSpeedLevel (currently active) over manualSpeedLevel (last-set)
    // for the live active speed. Both are in range 1-12.
    Integer fanSpeedRaw    = (r.fanSpeedLevel    != null) ? (r.fanSpeedLevel    as Integer) : null
    Integer manualSpeedRaw = (r.manualSpeedLevel != null) ? (r.manualSpeedLevel as Integer) : null
    Integer activeSpeed    = fanSpeedRaw ?: manualSpeedRaw ?: 1
    state.fanLevel = activeSpeed
    if (!powerOn) {
        device.sendEvent(name:"speed", value:"off")
        device.sendEvent(name:"level", value: percentFromLevel(activeSpeed))
    } else {
        String speedEnum = levelToFanControlEnum(activeSpeed)
        device.sendEvent(name:"speed", value: speedEnum)
        device.sendEvent(name:"level", value: percentFromLevel(activeSpeed))
    }

    // ---- Mode ----
    String rawWorkMode  = (r.workMode ?: "normal") as String
    String reportedMode = (rawWorkMode == "advancedSleep") ? "sleep" : rawWorkMode
    state.mode = reportedMode
    device.sendEvent(name:"mode", value: reportedMode)

    return [powerOn: powerOn, activeSpeed: activeSpeed, reportedMode: reportedMode]
}

// Mute + display. Contiguous and identical in both drivers.
// muteState/screenState = actual hardware state; *Switch = configured. Prefer actual.
// Returns muteState (Integer) for the info-HTML "Mute:" line.
private Integer applyFanMuteDisplay(Map r) {
    Integer muteState = (r.muteState != null) ? (r.muteState as Integer) : (r.muteSwitch as Integer)
    device.sendEvent(name:"mute", value: muteState == 1 ? "on" : "off")

    Integer screenState = (r.screenState != null) ? (r.screenState as Integer) : (r.screenSwitch as Integer)
    device.sendEvent(name:"displayOn", value: screenState == 1 ? "on" : "off")

    return muteState
}

// Ambient temperature. Raw / 10 = degrees F (HA finding #1 / pyvesync vesyncfan.py:314).
// Sanity gate: 0 raw is an uninitialized field — skip. Identical in both drivers.
private void applyFanTemperature(Map r) {
    if (r.temperature != null) {
        Integer rawTemp = r.temperature as Integer
        if (rawTemp > 0) {
            Float tempF = rawTemp / 10.0f
            device.sendEvent(name:"temperature", value: tempF, unit:"°F")
        }
    }
}

// Sleep preference (nested object). Identical in both drivers.
private void applyFanSleepPreference(Map r) {
    if (r.sleepPreference instanceof Map) {
        String spType = r.sleepPreference?.sleepPreferenceType as String
        if (spType) device.sendEvent(name:"sleepPreferenceType", value: spType)
    }
}

// Error code with first-transition INFO log. Identical in both drivers.
private void applyFanErrorCode(Map r) {
    if (r.errorCode != null) {
        Integer ec = r.errorCode as Integer
        device.sendEvent(name:"errorCode", value: ec)
        Integer lastEc = state.lastErrorCode as Integer
        if (ec != 0 && (lastEc == null || lastEc == 0)) logInfo "Device error code: ${ec}"
        state.lastErrorCode = ec
    }
}

// ---- HTTP plumbing ----

// hubBypass, httpOk are provided by #include level99.LevoitChildBase (LevoitChildBaseLib.groovy).
