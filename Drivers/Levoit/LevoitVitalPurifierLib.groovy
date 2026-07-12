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
    name: "LevoitVitalPurifier",
    namespace: "level99",
    author: "Dan Cox (level99)",
    description: "Shared methods for Levoit Vital 100S/200S air purifier drivers (VeSyncAirBaseV2 class)",
    documentationLink: "https://github.com/level99/Hubitat-VeSync",
    importUrl: "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitVitalPurifierLib.groovy"
)

// Library contract:
//   REQUIRES: #include level99.LevoitDiagnostics  (provides recordError)
//   REQUIRES: #include level99.LevoitChildBase    (provides logInfo/logDebug/logError/logWarn/
//                                                  ensureDebugWatchdog/ensureSwitchOn/requireNotNull/
//                                                  hubBypass/httpOk)
//   PROVIDES: 31 shared methods covering lifecycle, power, speed/level, mode,
//             feature setters, timer, polling, and HTTP plumbing. See sections below.
//   BEHAVIOR CONTRACTS:
//     - setMode(null), setSpeed(null), setDisplay(null), setChildLock(null),
//       setAutoPreference(null), setPetMode(null), setRoomSize(null): all null
//       inputs are rejected via requireNotNull with logWarn + early return; no API
//       call is made (BP18 null-guard).
//     - setChildLock(onOff): no-op (no API call, no event) when onOff matches the
//       current device.currentValue("childLock") (C3 state-change gate).
//     - setDisplay(onOff): same gate against device.currentValue("display") (C3).
//     - cycleSpeed(): calls ensureSwitchOn() at entry -- device is turned on if off
//       before cycling speed (BP24-C fix).
//     - setLevel(val, duration): duration ignored; delegates to setLevel(val).
//     - setLevel(0): calls off(), returns immediately (no speed command sent).
//     - setLevel(N>0): calls ensureSwitchOn() -- device is turned on if off (BP23).

// ---- Lifecycle ----

def installed() {
    logDebug "Installed ${settings}"
    updated()
}

def updated() {
    logDebug "Updated ${settings}"
    // Preserve state.timerId across state.clear() — it's the id an active device timer must
    // reference to be cancelled. Wiping it on every Save Preferences would leave cancelTimer()
    // unable to cancel a running timer (silent no-op). Mirrors LevoitFanLib.updated().
    def savedTimerId = state.timerId
    state.clear()
    if (savedTimerId != null) state.timerId = savedTimerId
    unschedule(); initialize()
    runIn(3, "update")
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
    // FanControl: publish the static speed enum once so dashboard fan-tiles and
    // integrations can populate their speed picker. Uniform across Vital 100S/200S.
    device.sendEvent(name:"supportedFanSpeeds",
        value: groovy.json.JsonOutput.toJson(["off","sleep","low","medium","high","max"]))
}

// ---- Power ----

def on() {
    logDebug "on()"

    // Prevent re-entrance loop
    if (state.turningOn) {
        logDebug "Already turning on, skipping recursive call"
        return
    }

    // BP30: async-window storm guard — collapse a burst of overlapping on() commands into
    // ONE effective power+mode sequence. Spans the runInMillis(500, configureOnState) async
    // leg below (state.turningOn clears synchronously before configureOnState runs, so it
    // alone cannot catch a storm). Returns false while a power-on is already in flight.
    if (!beginPowerOnWindow()) {
        logDebug "Power-on already in flight (BP30 storm guard); skipping redundant burst"
        return
    }

    state.turningOn = true
    try {
        if (handlePower(true)) {
            logInfo "Power on"
            state.lastSwitchSet = "on"
            // CRITICAL: Update switch state IMMEDIATELY so Rooms/automations see device is on
            // This prevents them from resending the command while we're still configuring speed/mode
            device.sendEvent(name:"switch", value:"on")

            // Now attempt speed/mode configuration (these may fail but device is already on)
            runInMillis(500, "configureOnState")
        } else {
            clearPowerOnWindow()   // B2: failed power-on is retryable immediately (don't hold the window)
            reportWriteError("Failed to turn on device", [method:"on"])
        }
    } finally {
        state.remove('turningOn')
    }
}

// Separate method to configure speed/mode after power is on
def configureOnState() {
    logDebug "configureOnState()"

    // Skip if user disabled speed configuration on turn-on
    if (settings?.skipSpeedOnTurnOn) {
        logDebug "Skipping speed/mode config (skipSpeedOnTurnOn=true)"
        return
    }

    if (device.currentValue("switch") != "on") return

    // Capture original mode before any writes so we don't read a mutated state.mode
    def origMode = state.mode ?: "manual"
    if (origMode == "manual") {
        // Single setLevel establishes manual mode + saved speed atomically (V2 quirk)
        def targetSpeed = state.speed && state.speed != "off" ? state.speed : "low"
        def lvl = mapSpeedToInteger(targetSpeed)
        def ok = setSpeedLevel(lvl)
        if (ok) {
            state.speed = targetSpeed
            state.mode = "manual"
            device.sendEvent(name: "speed", value: targetSpeed)
            device.sendEvent(name: "mode", value: "manual")
            device.sendEvent(name: "petMode", value: "off")
        }
    } else {
        // auto/sleep/pet — set mode directly (no speed write needed)
        setMode(origMode)
    }
}

def off() {
    logDebug "off()"

    // Prevent re-entrance loop
    if (state.turningOff) {
        logDebug "Already turning off, skipping recursive call"
        return
    }

    state.turningOff = true
    try {
        // BP30: cancel any open power-on window so a deliberate off -> on fires a fresh sequence,
        // and clear the speed dedup slot so a low -> off -> low re-establish write within the
        // 2s window is not suppressed (matches the sibling EverestAir/SproutAir off() theme).
        clearPowerOnWindow()
        clearDuplicateWrite("speed")
        if (handlePower(false)) {
            logInfo "Power off"
            state.lastSwitchSet = "off"
            // CRITICAL: Update switch state IMMEDIATELY
            device.sendEvent(name:"switch", value:"off")
            // BP6: clear the active FanControl/SwitchLevel mirrors on the off edge so the dashboard
            // fan/dimmer tiles read off/0 immediately (not the retained level) ahead of the next poll.
            // Centralized here so EVERY off entry point is covered: direct off(), setLevel(0),
            // setSpeed("off"), and toggle().
            device.sendEvent(name:"speed", value:"off")
            device.sendEvent(name:"level", value: 0)
        } else {
            reportWriteError("Failed to turn off device", [method:"off"])
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

// ---- Speed / Level ----

def cycleSpeed() {
    logDebug "cycleSpeed()"
    ensureSwitchOn()    // BP24-C fix — replaces inline non-re-entrance-safe check
    def cur = state.speed ?: "low"
    def next = ["sleep":"low","low":"medium","medium":"high","high":"max","max":"sleep"][cur] ?: "low"
    setSpeed(next)
}

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

// SwitchLevel convention: setLevel(0) turns the device off (matches Z-Wave dimmer platform expectation).
// BP23: setLevel(N>0) auto-turns-on when switch is off (SwitchLevel capability convention).
// BP18: null-guard converts null → 0 (null < N throws NPE; 0 routes cleanly to off() below).
def setLevel(val) {
    logDebug "setLevel $val"
    // BP28: distinguish explicit 0 (-> off) from non-numeric garbage (-> ignore, device unchanged).
    Integer pct = parseLevelOrNull(val)
    if (pct == null) { logWarn "setLevel: ignoring non-numeric value '${val}'"; return }
    pct = Math.max(0, Math.min(100, pct))
    if (pct == 0) { off(); return }
    // BP23: auto-on when switch is off.
    // state.turningOn is set by on() while configureOnState() runs async;
    // skip the redundant on() call if a turn-on cycle is already in flight.
    ensureSwitchOn()
    // Bands are CEILINGS aligned to the emitted values (speedToLevel: 1->25, 2->50, 3->75, 4->100)
    // so re-applying an emitted level is a fixed point: setLevel(25) -> lvl1 -> emits 25 (not 50).
    // The prior open-interval bands (<20/<40/<60) escalated on round-trip: setLevel(25) -> lvl2 -> 50.
    // Matches the sibling EverestAir/SproutAir ceiling convention.
    Integer lvl
    if (pct <= 25) lvl=1
    else if (pct <= 50) lvl=2
    else if (pct <= 75) lvl=3
    else lvl=4
    def ok = setSpeedLevel(lvl)
    if (ok) {
        // setLevel establishes manual mode + speed atomically (V2 quirk); emit mode events here.
        // Also write state.speed so configureOnState() replay path re-applies the correct named speed.
        state.speed = mapIntegerToSpeed(lvl)
        state.mode = "manual"
        // Emit the BANDED level (speedToLevel(lvl)) — NOT the raw requested pct — so the command and
        // the poll agree: a 4-speed device only has discrete levels, and applyStatus mirrors the same
        // speedToLevel(sp). Emitting the raw pct here made setLevel(30) show 30 then snap to 50 on the
        // next poll. Emit the FanControl `speed` mirror too — setSpeed()/setMode("manual") both emit
        // speed on success, but setLevel() left it stale. Gated on the write succeeding (matches speed).
        device.sendEvent(name:"level", value: speedToLevel(lvl))
        device.sendEvent(name:"speed", value: state.speed)
        device.sendEvent(name:"mode", value: "manual")
        device.sendEvent(name:"petMode", value: "off")
        logInfo "Level: ${pct}% (fan level ${lvl})"
    }
}

def setSpeed(spd) {
    logDebug "setSpeed(${spd})"
    if (!requireNonEmptyEnum(spd, "setSpeed")) return
    // BP25: normalize to lowercase so Rule Machine "OFF"/"SLEEP" route correctly.
    String s = (spd as String).trim().toLowerCase()
    if (s == "off") return off()

    // Reject unknown speed values BEFORE ensureSwitchOn() and before any cloud write.
    // Without this, an unrecognized value (e.g. "turbo") falls through to mapSpeedToInteger,
    // which defaults unknown input to the "low" band (default: return 2) -- silently widening
    // garbage to a real speed AND auto-powering the device on. setMode already rejects invalid
    // modes; mirror that here so a malformed FanControl/Rule Machine speed does not turn the
    // device on. (BP24-class: reject-before-auto-on.) "sleep" is a valid speed value (delegates to setMode).
    List validSpeeds = ["low", "medium", "high", "max"]
    if (!(s == "sleep" || s in validSpeeds)) {
        logWarn "setSpeed: invalid speed '${s}' -- must be one of: ${(validSpeeds + ['sleep','off']).join(', ')}; ignoring"
        return
    }

    ensureSwitchOn()

    // BP30 Layer 3 (A1): dedup BEFORE the sleep early-return so the "speed" slot reflects the NEW
    // effective speed even when sleep delegates to setMode — otherwise low->sleep->low within the
    // window would falsely suppress the 3rd write (slot stale at "low"). The turningOn/powerOnPending
    // guard keeps an in-flight power-on's establishment write from being suppressed. (Matches the
    // setMode reference, which dedups before its manual branch.)
    if (!state.turningOn && !state.powerOnPending && isDuplicateWrite("speed", s)) {
        logDebug "setSpeed: identical speed write within dedup window (storm duplicate); skipping"
        return false
    }

    // A1-delegation: the "speed" slot is already recorded; if the delegated setMode("sleep") FAILS,
    // clear it so a same-value setSpeed("sleep") retry is not falsely suppressed.
    // B1 fail-safe: the delegated setter's false can mean a genuine failure OR its own dedup-suppress; clearing the outer slot on either is harmless (inner write stays deduped -> no extra cloud write).
    if (s == "sleep") { if (!setMode("sleep")) clearDuplicateWrite("speed"); device.sendEvent(name:"speed", value:"on"); return }

    // setLevel establishes manual mode + speed atomically; no setMode("manual") pre-call needed (V2 quirk)
    def lvl = mapSpeedToInteger(s)
    def ok = setSpeedLevel(lvl)
    if (ok) {
        state.speed = s
        state.mode = "manual"
        device.sendEvent(name:"speed", value: s)
        device.sendEvent(name:"mode", value: "manual")
        device.sendEvent(name:"petMode", value: "off")
        logInfo "Speed: ${s}"
    } else {
        clearDuplicateWrite("speed")   // B1: failed write must not suppress an immediate retry
    }
}

def setSpeedLevel(level) {
    logDebug "setSpeedLevel(${level})"
    def ok = writeSpeedPreferred(level)
    if (!ok) { reportWriteError("Speed write failed for level ${level}", [method:"setSpeedLevel"]) }
    return ok
}

// ---- Mode ----
// V2-line mode-write split (per pyvesync LAP-V102S.yaml / LAP-V201S.yaml fixture):
//   manual: there is no separate "set manual mode" command; manual mode is established by
//           sending a speed via setLevel (levelIdx/levelType/manualSpeedLevel).
//   auto/sleep/pet: use setPurifierMode with {workMode: <mode>}.
// BP24: SHOULD-ON — asking an off device to change mode auto-turns it on. Earlier this method
//   skipped-when-off (returned false). pyvesync VeSyncAirBaseV2.set_mode has NO power gate and
//   sets device_status=ON after a successful mode change — the V2 API accepts mode-while-off.
//   The prior skip-when-off was therefore a driver-side deviation, not an API constraint, and is
//   replaced here with ensureSwitchOn() to match the family-wide decision (and speed/level setters).
//   ensureSwitchOn() runs AFTER validation so invalid input cannot wake an off device.
// BP25: lowercase-normalize the mode before comparisons so setMode("AUTO") is not silently dropped
//   by the case-sensitive enum checks (multi-state enum — canonOnOff does not apply here).
def setMode(mode) {
    logDebug "setMode(${mode})"
    if (!requireNonEmptyEnum(mode, "setMode")) return false
    String m = (mode as String).trim().toLowerCase()

    if (!(m in ["manual", "auto", "sleep", "pet"])) {
        logError "Unknown mode: ${m}"
        recordError("Unknown mode: ${m}", [method:"setMode"])
        return false
    }

    ensureSwitchOn()

    // BP30 Layer 3: drop an identical mode write issued within the storm dedup window. An
    // out-of-window re-request always fires, so a drifted cloud state stays correctable from
    // Hubitat (see isDuplicateWrite). The turningOn/powerOnPending guard keeps an in-flight
    // power-on's establishment write from being suppressed. Layers 1+2 are the primary storm fix.
    if (!state.turningOn && !state.powerOnPending && isDuplicateWrite("mode", m)) {
        logDebug "setMode: identical mode write within dedup window (storm duplicate); skipping"
        return false
    }

    boolean ok = false
    if (m == "manual") {
        // V2-line: manual mode is established by sending a speed via setLevel (no separate mode command)
        int spLevel = mapSpeedToInteger(state.speed ?: "low")
        def resp = hubBypass("setLevel", [levelIdx: 0, levelType: "wind", manualSpeedLevel: spLevel], "setMode->setLevel(${spLevel})")
        ok = httpOk(resp)
    } else {
        def resp = hubBypass("setPurifierMode", [workMode: m], "setPurifierMode(${m})")
        ok = httpOk(resp)
    }

    if (ok) {
        state.mode = m
        device.sendEvent(name: "mode", value: (m == "pet" ? "pet" : m))
        device.sendEvent(name: "petMode", value: (m == "pet" ? "on" : "off"))
        if (m == "manual") device.sendEvent(name: "speed", value: state.speed ?: "low")
        else if (m == "sleep") device.sendEvent(name: "speed", value: "on")
        else device.sendEvent(name: "speed", value: "auto")
        logInfo "Mode: ${m}"
    } else {
        clearDuplicateWrite("mode")   // B1: failed write must not suppress an immediate retry
        reportWriteError("Mode write failed for ${m}", [method:"setMode"])
    }
    return ok
}

// ---- Feature setters ----

def setPetMode(onOff) {
    if (!requireNonEmptyEnum(onOff, "setPetMode")) return
    // BP25: normalize + canonicalize truthy variants ("true"/"1"/"yes") before mode selection.
    String v = (onOff as String).trim().toLowerCase()
    String canon = canonOnOff(v)
    setMode(canon == "on" ? "pet" : "auto")
}

// BP24: NO-ON — configures a device preference; powering on is not implied.
def setAutoPreference(pref) {
    logDebug "setAutoPreference(${pref})"
    if (!requireNonEmptyEnum(pref, "setAutoPreference")) return
    def resp = hubBypass("setAutoPreference", [autoPreference: pref, roomSize: state.roomSize ?: 600], "setAutoPreference")
    if (httpOk(resp)) { state.autoPreference = pref; device.sendEvent(name:"autoPreference", value: pref) }
    else { reportWriteFailure("Auto preference write failed", resp, [method:"setAutoPreference"]) }
}

// BP24: NO-ON — configures a device preference; powering on is not implied.
def setRoomSize(sz) {
    logDebug "setRoomSize(${sz})"
    if (!requireNotNull(sz, "setRoomSize")) return
    Integer roomSz = safeIntArg(sz, 600)   // BP26: safeIntArg never throws on non-numeric RM input
    def resp = hubBypass("setAutoPreference", [autoPreference: state.autoPreference ?: "default", roomSize: roomSz], "setAutoPreference(roomSize)")
    if (httpOk(resp)) { state.roomSize = roomSz; device.sendEvent(name:"roomSize", value: roomSz) }
    else { reportWriteFailure("Room size write failed", resp, [method:"setRoomSize"]) }
}

// BP24: NO-ON — configures a device preference; powering on is not implied.
def setChildLock(onOff) {
    logDebug "setChildLock(${onOff})"
    if (!requireNonEmptyEnum(onOff, "setChildLock")) return
    // BP25: normalize to lowercase before C3 gate and payload coercion.
    // Without this, "ON" bypasses the gate (gate compares against "on") AND the payload
    // coercion evaluates ("ON" == "on") as false → sends childLockSwitch:0 (unlock) when
    // the caller intended to lock. Both the gate and the coercion must see the same form.
    String v = (onOff as String).trim().toLowerCase()
    // Canonical on/off derived from truthy test — sendEvent always emits "on" or "off".
    String canon = canonOnOff(v)
    // C3 state-change gate: no-op when value matches current attribute (suppresses redundant events)
    if (device.currentValue("childLock") == canon) return
    def resp = hubBypass("setChildLock", [childLockSwitch: canon == "on" ? 1 : 0], "setChildLock")
    if (httpOk(resp)) {
        device.sendEvent(name:"childLock", value: canon)
        logInfo "Child lock: ${canon}"
    } else { reportWriteFailure("Child lock write failed", resp, [method:"setChildLock"]) }
}

// BP24: NO-ON — configures a device preference; powering on is not implied.
def setDisplay(onOff) {
    logDebug "setDisplay(${onOff})"
    if (!requireNonEmptyEnum(onOff, "setDisplay")) return
    // BP25: normalize to lowercase before C3 gate and payload coercion (same rationale as setChildLock).
    String v = (onOff as String).trim().toLowerCase()
    // Canonical on/off derived from truthy test — sendEvent always emits "on" or "off".
    String canon = canonOnOff(v)
    // C3 state-change gate: no-op when value matches current attribute (suppresses redundant events)
    if (device.currentValue("display") == canon) return
    def resp = hubBypass("setDisplay", [screenSwitch: canon == "on" ? 1 : 0], "setDisplay")
    if (httpOk(resp)) {
        device.sendEvent(name:"display", value: canon)
        logInfo "Display: ${canon}"
    } else { reportWriteFailure("Display write failed", resp, [method:"setDisplay"]) }
}

// BP24: NO-ON — maintenance action; powering on is not implied.
def resetFilter() {
    logDebug "resetFilter()"
    def resp = hubBypass("resetFilter", [:], "resetFilter")
    if (httpOk(resp)) {
        logDebug "Filter reset requested"
    } else {
        reportWriteFailure("Filter reset failed", resp, [method:"resetFilter"])
    }
}

// ---- Timer (V2-line uses addTimerV2 / delTimerV2) ----

// BP24: NO-ON — scheduling action; powering on is not implied.
def setTimer(minutes) {
    if (!requireNotNull(minutes, "setTimer")) return   // BP18: null-guard (RM blank slot)
    int n = safeIntArg(minutes, 0)                     // BP26: safeIntArg never throws on non-numeric RM input
    logDebug "setTimer(${n} min)"
    if (n <= 0) { cancelTimer(); return }
    int secs = n * 60
    def data = [
        enabled: true,
        startAct: [[type: "powerSwitch", act: 0]],  // act:0 = power off when timer fires
        tmgEvt: [clkSec: secs]
    ]
    def resp = hubBypass("addTimerV2", data, "addTimerV2(${n}min)")
    if (httpOk(resp)) {
        // Type-guard: a non-JSON body makes resp.data a String; the .result read would throw.
        def tid = (resp?.data instanceof Map) ? resp.data.result?.id : null
        if (tid != null) state.timerId = tid
        logInfo "Timer set: power off in ${n} minutes (id=${tid})"
    } else {
        reportWriteFailure("Timer set failed", resp, [method:"addTimerV2"])
    }
}

// BP24: NO-ON — scheduling action; powering on is not implied.
def cancelTimer() {
    logDebug "cancelTimer()"
    if (!state.timerId) {
        logDebug "No active timer to cancel"
        return
    }
    def resp = hubBypass("delTimerV2", [id: state.timerId, subDeviceNo: 0], "delTimerV2(id=${state.timerId})")
    if (httpOk(resp)) {
        state.remove("timerId")
        logInfo "Timer cancelled"
    } else {
        reportWriteFailure("Timer cancel failed", resp, [method:"delTimerV2"])
    }
}

// ---- Update / status ----

def update() {
    logDebug "update()"
    def resp = hubBypass("getPurifierStatus", [:], "update")
    if (httpOk(resp)) {
        def status = resp?.data
        if (!status?.result) { logError "No status returned from getPurifierStatus"; recordError("No status returned from getPurifierStatus", [method:"update"]) }
        else applyStatus(status)
    }
}

// Called by parent driver with status updates.
// status arg structure: [code:0, result:[powerSwitch, workMode, AQLevel, PM25, ...]]
// Parent passes the full API response; do NOT wrap in [result: status] — it already has .result
def update(status) {
    logDebug "update() from parent (1-arg)"
    applyStatus(status)
    return true
}

// 2-arg variant — parent driver calls update(status, nightLight); nightLight unused on Vital line
// REQUIRED (BP1): parent always dispatches with 2 args; missing this causes MissingMethodException.
def update(status, nightLight) {
    logDebug "update() from parent (2-arg, nightLight ignored)"
    applyStatus(status)
    return true
}

// ---- HTTP plumbing ----

// hubBypass, httpOk are provided by #include level99.LevoitChildBase (LevoitChildBaseLib.groovy).

// ---- Power (canonical V2-line payload) ----
// Canonical payload per pyvesync LAP-V102S.yaml / LAP-V201S.yaml:
//   method: setSwitch
//   data: { powerSwitch: 0/1, switchIdx: 0 }
def handlePower(on) {
    logDebug "handlePower: ${on}"
    def resp = hubBypass("setSwitch", [powerSwitch: on ? 1 : 0, switchIdx: 0], "setSwitch(power=${on?1:0})")
    if (httpOk(resp)) {
        logDebug "Power response ${resp.status} ${resp.data}"
        return true
    }
    return false
}

// ---- Preferred V2-line speed write ----
// Canonical payload per pyvesync LAP-V102S.yaml / LAP-V201S.yaml fixture:
//   method: setLevel
//   data: { levelIdx: 0, levelType: "wind", manualSpeedLevel: <1..4> }
// NOTE field names: levelIdx (not switchIdx), levelType (not type), manualSpeedLevel (not level)
// Bug Pattern #4: using the Core-line names (level/id/type) returns inner code -1 on V2 devices.
private boolean writeSpeedPreferred(level) {
    def resp = hubBypass("setLevel", [ levelIdx: 0, levelType: "wind", manualSpeedLevel: level as Integer ], "setLevel{levelIdx,levelType,manualSpeedLevel}")
    if (httpOk(resp)) return true
    return false
}

// ---- Speed mapping helpers ----

def mapSpeedToInteger(s) {
    switch("${s}") {
        case "sleep": return 1
        case "low":   return 2
        case "medium":return 3
        case "high":  return 4
        case "max":   return 4
        default:      return 2
    }
}

def mapIntegerToSpeed(n) {
    switch(n as Integer) {
        case 1: return "sleep"
        case 2: return "low"
        case 3: return "medium"
        case 4: return "high"
        case 255: return "off"
        default: return "low"
    }
}

// Map a manual fan level (1-4) to a 0-100 SwitchLevel percent for the `level` mirror emitted
// by applyStatus (Vital 100S/200S). Mirrors the EverestAir speedToLevel convention for a banded
// fan so the dimmer tile reflects the real device level every poll instead of only after setLevel().
Integer speedToLevel(lvl) {
    switch (lvl as Integer) {
        case 1: return 25
        case 2: return 50
        case 3: return 75
        case 4: return 100
        default: return 25
    }
}
