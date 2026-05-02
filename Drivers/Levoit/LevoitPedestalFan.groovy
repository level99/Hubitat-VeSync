/*
 * MIT License
 *
 * Copyright (c) 2026 Dan Cox (level99/Hubitat-VeSync community fork)
 * Built atop the VeSyncIntegration parent framework by Niklas Gustafsson.
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

// NOTE: Pedestal Fan shipped as a preview driver (v2.1). Maintainer received
// LPF-R432S hardware (device 1132, "Family Room Fan") week of 2026-04-26.
// v2.4 adds write-path commands confirmed by live hardware poll capture.
//
// Confirmed write paths (commands live-verified on device 1132, v2.4):
//   - power, setSpeed, setMode, setHorizontalOscillation, setVerticalOscillation,
//     setHorizontalRange, setVerticalRange, setMute, setDisplay,
//     setChildLock, setSmartCleaningReminder
//
// v2.4 write-path additions (live-verified on device 1132 LPF-R432S-AUK, 2026-05-01):
//   - setChildLock: {childLock: 1|0} — CONFIRMED inner 0
//   - setSmartCleaningReminder: {smartCleaningReminderState: 1|0} — CONFIRMED inner 0
//
// Deferred to v2.5+ (all refuted via live hardware; see CROSS-CHECK block below
// and ROADMAP.md "Pedestal Fan write-path commands — deferred features"):
//   - setTimer / cancelTimer, setSleepPreference, setHighTemperatureThreshold,
//     setHighTemperatureReminder, setLevelMemory, runOscillationCalibration
//
// Note on pyvesync filename typo: pyvesync's fixture is LPF-R423S.yaml
// but real device codes are LPF-R432S-AEU/AUS. Fixture content is correct;
// the filename has a number transposition.
//
// Please report any issues at:
//   https://github.com/level99/Hubitat-VeSync/issues

/*
 *  Levoit Pedestal Fan (LPF-R432S) — Hubitat driver
 *
 *  Targets:    LPF-R432S-AEU, LPF-R432S-AUS, LPF-R432S-AUK
 *  Marketing:  Levoit Smart Pedestal Fan
 *  Reference:  pyvesync VeSyncPedestalFan + LPF-R423S.yaml fixture (note typo in filename)
 *              https://github.com/webdjoe/pyvesync
 *
 *  CROSS-CHECK [pyvesync device_map.py LPF-R432S entry]:
 *    Decision: parent driver's deviceType() switch matches against LPF-R432S-* (real codes).
 *      Fixture content (LPF-R423S.yaml) is correct despite the filename being a typo.
 *    Rationale: pyvesync device_map.py lists the device as LPF-R432S with dev_types
 *      ['LPF-R432S-AEU', 'LPF-R432S-AUS'], while the YAML fixture filename and the
 *      setup_entry key inside it both use 'LPF-R423S' (digits 4-2-3 instead of 4-3-2).
 *      This is a pyvesync internal inconsistency (number transposition), NOT a real-world
 *      divergence. Real Levoit hardware ships with LPF-R432S model codes.
 *    Source: https://github.com/webdjoe/pyvesync/blob/master/src/pyvesync/device_map.py
 *      (LPF-R432S entry, dev_types=['LPF-R432S-AEU','LPF-R432S-AUS']);
 *      https://github.com/webdjoe/pyvesync/blob/master/src/tests/api/vesyncfan/
 *      LPF-R423S.yaml (filename typo; content correct).
 *    Refutation: not applicable -- this is a pyvesync source artifact, not device behavior.
 *  Project:    https://github.com/level99/Hubitat-VeSync
 *
 *  Key implementation notes:
 *    - Mode "sleep" maps to API literal "advancedSleep" (same reverse-mapping
 *      as Tower Fan; HA cross-check finding #d).
 *      setMode("sleep") sends {workMode:"advancedSleep"}; response
 *      "advancedSleep" is reverse-mapped back to emitted mode="sleep".
 *    - Mode "eco" (NOT "auto") is the Pedestal Fan's 4th mode.
 *      Tower Fan has "auto"; Pedestal Fan has "eco" -- do not confuse.
 *    - Temperature field is raw / 10 to get degrees F.
 *      pyvesync source (vesyncfan.py:314) confirms: temperature / 10.
 *      E.g. 750 raw = 75.0 degrees F.
 *    - Oscillation is 2-axis (horizontal + vertical) via setOscillationStatus.
 *      This is different from Tower Fan's single-axis setOscillationSwitch.
 *      Each axis is controlled separately -- NO combined setOscillation command.
 *      Payload key is horizontalOscillationState OR verticalOscillationState
 *      (exclusive per pyvesync _set_oscillation_state logic).
 *    - Range payloads require the matching axis state=1 (on) and the
 *      left/right (horizontal) or top/bottom (vertical) range fields.
 *    - childLock: exposed as both a read attribute AND writable via setChildLock
 *      command. Payload {childLock: 1|0} confirmed on device 1132 (2026-05-01).
 *      pyvesync VeSyncPedestalFan has no set_child_lock() method (community gap).
 *    - Timer omitted: no setTimer/clearTimer in pyvesync VeSyncPedestalFan.
 *    - Switch payload is purifier-style {powerSwitch, switchIdx}, NOT
 *      humidifier-style {enabled, id}.
 *    - setLevel uses V2-API field names {levelIdx, levelType, manualSpeedLevel}
 *      NOT the legacy Core-line names {id, type, level} (Bug Pattern #4).
 *    - API method setFanMode (NOT setTowerFanMode -- Pedestal Fan-specific name).
 *    - API read method getFanStatus (NOT getTowerFanStatus).
 *    - Oscillation method setOscillationStatus (NOT setOscillationSwitch).
 *
 *  History:
 *    2026-05-01: v2.4  Write-path live-verification complete on device 1132. Confirmed working:
 *                      setChildLock, setSmartCleaningReminder. Deferred to v2.5+ (refuted):
 *                      setTimer, cancelTimer, setSleepPreference, setHighTemperatureThreshold,
 *                      setHighTemperatureReminder, setLevelMemory, runOscillationCalibration.
 *                      Read-only attributes retained for all deferred fields.
 *    2026-04-30: v2.4  Write-path attempt — 7 new setter commands + read-only attribute exposures
 *                      added using live poll response field shapes (LPF-R432S device 1132).
 *                      Payloads marked [PREVIEW v2.4] pending live command verification.
 *    2026-04-29: v2.4  Phase 5 — captureDiagnostics + error ring-buffer via LevoitDiagnosticsLib.
 *    2026-04-26: v2.0  Community fork initial release (Dan Cox). Preview driver.
 *                      Built from canonical pyvesync VeSyncPedestalFan payloads
 *                      (LPF-R423S.yaml fixture -- note filename typo; real codes
 *                      are LPF-R432S -- + vesyncfan.py + fan_models.py).
 *                      Capabilities: Switch, SwitchLevel, FanControl,
 *                      TemperatureMeasurement, Sensor, Actuator, Refresh.
 *                      Modes: normal, turbo, eco, sleep (advancedSleep).
 *                      Setters: setMode, setSpeed (1-12), setHorizontalOscillation,
 *                      setVerticalOscillation, setHorizontalRange, setVerticalRange,
 *                      setMute, setDisplay, toggle.
 *                      Read-only attributes: childLock (no setter), sleepPreferenceType,
 *                      oscillationCoordinate (yaw/pitch), oscillationRange (L/R/T/B),
 *                      errorCode, temperature.
 */

#include level99.LevoitDiagnostics

metadata {
    definition(
        name: "Levoit Pedestal Fan",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "Levoit Pedestal Fan (LPF-R432S-AEU/AUS/AUK) — power, fan speed 1-12, modes (normal/turbo/eco/sleep), 2-axis oscillation with range control, mute, display, child lock, smart cleaning reminder, ambient temperature",
        version: "2.4.1",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
    {
        capability "Switch"
        capability "SwitchLevel"               // 1-12 speeds mapped to 8-100% increments
        capability "FanControl"                // speed enum + cycleSpeed()
        capability "TemperatureMeasurement"    // ambient temp from device sensor (raw / 10 = °F)
        capability "Sensor"
        capability "Actuator"
        capability "Refresh"

        // Oscillation axes (separate horizontal + vertical, no combined toggle)
        attribute "horizontalOscillation",  "string"    // on | off
        attribute "verticalOscillation",    "string"    // on | off
        // Oscillation range bounds (current configured range)
        attribute "oscillationLeft",        "number"    // horizontal left bound (0-100)
        attribute "oscillationRight",       "number"    // horizontal right bound (0-100)
        attribute "oscillationTop",         "number"    // vertical top bound (0-100)
        attribute "oscillationBottom",      "number"    // vertical bottom bound (0-100)
        // Oscillation coordinate (current head position, read-only)
        attribute "oscillationYaw",         "number"    // current yaw position
        attribute "oscillationPitch",       "number"    // current pitch position
        // Mode (user-facing label; "sleep" reverse-mapped from API "advancedSleep")
        attribute "mode",                   "string"    // normal | turbo | eco | sleep
        attribute "mute",                   "string"    // on | off
        attribute "displayOn",              "string"    // on | off
        // childLock: v2.4 adds setChildLock command (payload confirmed via Sister-Switch convention
        // + live poll field shape; command-level verification pending live hardware dispatch).
        attribute "childLock",              "string"    // on | off
        attribute "errorCode",              "number"
        attribute "timerRemain",            "number"    // seconds remaining on active timer (0 = no timer)
        // sleepPreference nested fields
        attribute "sleepPreferenceType",    "string"    // e.g. "default", "advanced", "quiet"
        // Oscillation calibration progress (read-only; populated during runOscillationCalibration)
        attribute "oscillationCalibrationState",    "string"    // idle | calibrating
        attribute "oscillationCalibrationProgress", "number"    // 0-100
        // High temperature reminder threshold + enable (setters below)
        attribute "highTemperature",         "number"    // user-set threshold, degrees F
        attribute "highTemperatureReminder", "string"    // on | off
        // Smart cleaning reminder enable (setter below)
        attribute "smartCleaningReminder",   "string"    // on | off
        attribute "info",                   "string"    // HTML summary for dashboard tiles

        // Power
        command "toggle"

        // Mode (4 modes; note ECO not AUTO -- Pedestal Fan differentiator vs Tower Fan)
        command "setMode", [[name:"Mode*", type:"ENUM", constraints:["normal","turbo","eco","sleep"]]]

        // Speed -- raw 1-12 (also aliased via FanControl capability setSpeed)
        command "setSpeed", [[name:"Speed*", type:"NUMBER", description:"Fan level 1-12"]]

        // Oscillation -- 2-axis. Separate horizontal + vertical toggles.
        // There is no combined setOscillation command -- Pedestal Fan is 2-axis;
        // for "both axes on" call setHorizontalOscillation("on") + setVerticalOscillation("on").
        command "setHorizontalOscillation", [[name:"On/Off*", type:"ENUM", constraints:["on","off"]]]
        command "setVerticalOscillation",   [[name:"On/Off*", type:"ENUM", constraints:["on","off"]]]

        // Oscillation range setters -- turn that axis ON with explicit bounds.
        // Range values 0-100 (device units). State is always set to 1 (on) in range payloads.
        command "setHorizontalRange", [
            [name:"Left*",  type:"NUMBER", description:"Left bound (0-100)"],
            [name:"Right*", type:"NUMBER", description:"Right bound (0-100)"]
        ]
        command "setVerticalRange", [
            [name:"Top*",    type:"NUMBER", description:"Top bound (0-100)"],
            [name:"Bottom*", type:"NUMBER", description:"Bottom bound (0-100)"]
        ]

        command "setMute",    [[name:"On/Off*", type:"ENUM", constraints:["on","off"]]]
        command "setDisplay", [[name:"On/Off*", type:"ENUM", constraints:["on","off"]]]

        // v2.4 write-path additions — live-verified on device 1132 LPF-R432S-AUK (2026-05-01).
        command "setChildLock",            [[name:"On/Off*", type:"ENUM", constraints:["on","off"]]]
        command "setSmartCleaningReminder",[[name:"On/Off*", type:"ENUM", constraints:["on","off"]]]

        attribute "diagnostics", "string"
        // "true" | "false" — parent marks "false" after 3 self-heal attempts fail; flips back to "true" on first successful poll (BP21)
        attribute "online", "string"
        command "captureDiagnostics"
    }

    preferences {
        input "descriptionTextEnable", "bool", title: "Enable descriptive (info-level) logging?", defaultValue: true
        input "debugOutput",           "bool", title: "Enable debug logging?",                    defaultValue: false
    }
}

// ---------- Lifecycle ----------
def installed(){ logDebug "Installed ${settings}"; updated() }
def updated(){
    logDebug "Updated ${settings}"
    state.clear(); unschedule(); initialize()
    state.driverVersion = "2.4.1"
    runIn(3, "refresh")
    // Turn off debug log in 30 minutes (happy path — no hub reboot)
    if (settings?.debugOutput) {
        runIn(1800, "logDebugOff")
        state.debugEnabledAt = now()
    } else {
        state.remove("debugEnabledAt")
    }
}
def uninstalled(){ logDebug "Uninstalled" }
def initialize(){ logDebug "Initializing" }

// ---------- Power ----------
// Switch payload is purifier-style: {powerSwitch: int, switchIdx: 0}
// NOT humidifier-style {enabled: bool, id: 0}
def on(){
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

def off(){
    logDebug "off()"
    def resp = hubBypass("setSwitch", [powerSwitch: 0, switchIdx: 0], "setSwitch(power=0)")
    if (httpOk(resp)) {
        logInfo "Power off"
        state.lastSwitchSet = "off"
        device.sendEvent(name:"switch", value:"off")
    } else {
        logError "Power off failed"; recordError("Power off failed", [method:"setSwitch"])
    }
}

// state.lastSwitchSet preferred over device.currentValue() to avoid the read-after-write
// race (the new event from on()/off() may not be queryable yet on a same-tick toggle()).
// Falls back to device.currentValue("switch") when state isn't seeded yet (first-call case).
def toggle(){
    logDebug "toggle()"
    String current = state.lastSwitchSet ?: device.currentValue("switch")
    current == "on" ? off() : on()
}

// ---------- FanControl capability ----------
// FanControl.setSpeed() accepts Hubitat's speed enum: off/low/medium-low/medium/medium-high/high/auto
// We also expose a raw setSpeed(NUMBER) command for 1-12 integer levels.
// Both routes funnel through the same sendLevel() internal helper.
def setSpeed(spd){
    logDebug "setSpeed(${spd})"
    // If numeric string or integer, treat as raw fan level (1-12 raw setSpeed command)
    if (spd instanceof Number || (spd instanceof String && spd.isInteger())) {
        Integer rawLevel = (spd as Integer)
        if (rawLevel < 1 || rawLevel > 12) {
            logError "setSpeed: invalid raw level ${rawLevel} -- must be 1-12"
            recordError("setSpeed: invalid raw level ${rawLevel}", [method:"setLevel"])
            return
        }
        sendLevel(rawLevel)
        return
    }
    // Enum string (FanControl capability path)
    if (spd == null) { logWarn "setSpeed called with null spd (likely empty Rule Machine action parameter); ignoring"; return }
    String s = (spd as String).toLowerCase()
    if (s == "off")  { off(); return }
    if (s == "on")   { on(); return }   // Hubitat FanControl spec: "on" resumes at prior/default speed
    if (s == "auto") { setMode("eco"); return }  // Pedestal Fan: auto maps to eco (no auto mode)
    Integer lvl = fanControlEnumToLevel(s)
    if (lvl == null) { logError "setSpeed: unknown enum value '${s}'"; recordError("setSpeed: unknown enum '${s}'", [method:"setLevel"]); return }
    sendLevel(lvl)
}

// FanControl.cycleSpeed() -- advance through levels in order
def cycleSpeed(){
    logDebug "cycleSpeed()"
    Integer cur = state.fanLevel as Integer ?: 1
    Integer next = (cur >= 12) ? 1 : (cur + 1)
    sendLevel(next)
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

// SwitchLevel capability: setLevel(percent 0-100) -> map to 1-12
// SwitchLevel convention: setLevel(0) turns the device off (matches Z-Wave dimmer platform expectation).
// BP23: setLevel(N>0) auto-turns-on when switch is off (SwitchLevel capability convention).
def setLevel(val){
    logDebug "setLevel(${val})"
    Integer pct = Math.max(0, Math.min(100, (val as Integer) ?: 0))
    if (pct == 0) { off(); return }
    // BP23: auto-on when switch is off.
    // state.turningOn guard set in on() prevents re-entrance.
    if (!state.turningOn && device.currentValue("switch") != "on") on()
    Integer lvl = levelFromPercent(pct)
    // SwitchLevel spec requires emitting the level event immediately
    sendEvent(name:"level", value: pct)
    sendLevel(lvl)
}

// ---------- Mode ----------
// CROSS-CHECK [pyvesync device_map.py LPF-R432S entry]:
//   Decision: Pedestal Fan uses API method "setFanMode", NOT "setTowerFanMode".
//     Tower Fan uses "setTowerFanMode"; Pedestal Fan uses "setFanMode" -- DIFFERENT names.
//   Rationale: pyvesync device_map.py LPF-R432S entry explicitly sets
//     set_mode_method='setFanMode'. The ST+HB cross-check parenthetical did not distinguish
//     the two fan method names; pyvesync device_map.py is the authoritative source.
//   Source: https://github.com/webdjoe/pyvesync/blob/master/src/pyvesync/device_map.py
//     (LPF-R432S entry, set_mode_method='setFanMode').
//   Refutation: community user reports "setFanMode" is rejected and "setTowerFanMode" works
//     --> swap to "setTowerFanMode" and update the header note.
//
// CROSS-CHECK [pyvesync device_map.py LPF-R432S entry / HA cross-check finding #d]:
//   Decision: "sleep" reverse-maps to/from API "advancedSleep" (same pattern as Tower Fan).
//   Decision: mode "eco" (NOT "auto") is the Pedestal Fan's 4th mode.
//     Tower Fan has "auto"; Pedestal Fan has "eco" -- do NOT confuse these.
//   Source: pyvesync device_map.py LPF-R432S FanModes entries (eco, advancedSleep);
//     HA vesync cross-check finding #d (advancedSleep reverse-mapping).
//   Refutation for eco: community user confirms "auto" is accepted on their Pedestal Fan -->
//     add "auto" as an alias or replace "eco" with "auto".
def setMode(mode){
    logDebug "setMode(${mode})"
    if (mode == null) { logWarn "setMode called with null mode (likely empty Rule Machine action parameter); ignoring"; return }
    String m = (mode as String).toLowerCase()
    if (!(m in ["normal","turbo","eco","sleep"])) {
        logError "setMode: invalid mode '${m}' -- must be normal|turbo|eco|sleep"
        recordError("setMode: invalid mode '${m}'", [method:"setFanMode"])
        return
    }
    // Map user-facing "sleep" to API "advancedSleep" (pyvesync device_map.py + HA finding #d)
    String apiMode = (m == "sleep") ? "advancedSleep" : m
    def resp = hubBypass("setFanMode", [workMode: apiMode], "setFanMode(${apiMode})")
    if (httpOk(resp)) {
        state.mode = m   // store user-facing label, not API literal
        device.sendEvent(name:"mode", value: m)
        logInfo "Mode: ${m}"
    } else {
        logError "Mode write failed: ${m}"; recordError("Mode write failed: ${m}", [method:"setFanMode"])
    }
}

// ---------- Oscillation ----------
// CROSS-CHECK [pyvesync LPF-R423S.yaml fixture / pyvesync VeSyncPedestalFan._set_oscillation_state]:
//   Decision: use method "setOscillationStatus" (NOT "setOscillationSwitch").
//     Pedestal Fan is 2-axis (horizontal + vertical); Tower Fan is single-axis.
//     Different method name AND different semantics.
//   Rationale: pyvesync LPF-R423S.yaml fixture (note: filename typo, content is correct for
//     LPF-R432S) shows the oscillation method as setOscillationStatus with fields
//     horizontalOscillationState and verticalOscillationState. Tower Fan uses
//     setOscillationSwitch with oscillationSwitch field -- completely different.
//     Per pyvesync VeSyncPedestalFan._set_oscillation_state: H and V must be sent in
//     separate payloads (if verticalOscillationState is present, horizontal and ranges
//     are ignored). Therefore: no combined H+V toggle command.
//   Source: https://github.com/webdjoe/pyvesync/blob/master/src/tests/api/vesyncfan/
//     LPF-R423S.yaml (fixture); pyvesync VeSyncPedestalFan._set_oscillation_state method.
//   Refutation: community user reports setOscillationStatus is rejected, setOscillationSwitch
//     works --> swap method name and revise semantics to single-axis.

// Toggle horizontal oscillation on or off (without changing range)
def setHorizontalOscillation(onOff){
    logDebug "setHorizontalOscillation(${onOff})"
    if (onOff == null) { logWarn "setHorizontalOscillation called with null (likely empty Rule Machine action parameter); ignoring"; return }
    String s = (onOff as String).toLowerCase()
    if (!(s in ["on","off"])) { logError "setHorizontalOscillation: invalid value '${s}'"; recordError("setHorizontalOscillation invalid: ${s}", [method:"setOscillationStatus"]); return }
    Integer v = (s == "on") ? 1 : 0
    def resp = hubBypass("setOscillationStatus",
        [horizontalOscillationState: v, actType: "default"],
        "setOscillationStatus(H=${s})")
    if (httpOk(resp)) {
        device.sendEvent(name:"horizontalOscillation", value: s)
        logInfo "Horizontal oscillation: ${s}"
    } else {
        logError "Horizontal oscillation write failed"; recordError("Horizontal oscillation write failed", [method:"setOscillationStatus"])
    }
}

// Toggle vertical oscillation on or off (without changing range)
def setVerticalOscillation(onOff){
    logDebug "setVerticalOscillation(${onOff})"
    if (onOff == null) { logWarn "setVerticalOscillation called with null (likely empty Rule Machine action parameter); ignoring"; return }
    String s = (onOff as String).toLowerCase()
    if (!(s in ["on","off"])) { logError "setVerticalOscillation: invalid value '${s}'"; recordError("setVerticalOscillation invalid: ${s}", [method:"setOscillationStatus"]); return }
    Integer v = (s == "on") ? 1 : 0
    def resp = hubBypass("setOscillationStatus",
        [verticalOscillationState: v, actType: "default"],
        "setOscillationStatus(V=${s})")
    if (httpOk(resp)) {
        device.sendEvent(name:"verticalOscillation", value: s)
        logInfo "Vertical oscillation: ${s}"
    } else {
        logError "Vertical oscillation write failed"; recordError("Vertical oscillation write failed", [method:"setOscillationStatus"])
    }
}

// Set horizontal oscillation range (0-100 device units) and turn horizontal oscillation ON.
// Range payloads always include state=1 (oscillation must be on to set a range).
def setHorizontalRange(left, right){
    logDebug "setHorizontalRange(left=${left}, right=${right})"
    Integer l = (left as Integer) ?: 0
    Integer r = (right as Integer) ?: 0
    def resp = hubBypass("setOscillationStatus",
        [horizontalOscillationState: 1, actType: "default", left: l, right: r],
        "setOscillationStatus(H_range=${l}..${r})")
    if (httpOk(resp)) {
        device.sendEvent(name:"horizontalOscillation", value: "on")
        device.sendEvent(name:"oscillationLeft",  value: l)
        device.sendEvent(name:"oscillationRight", value: r)
        logInfo "Horizontal oscillation range: ${l}-${r}"
    } else {
        logError "Horizontal range write failed"; recordError("Horizontal range write failed", [method:"setOscillationStatus"])
    }
}

// Set vertical oscillation range (0-100 device units) and turn vertical oscillation ON.
def setVerticalRange(top, bottom){
    logDebug "setVerticalRange(top=${top}, bottom=${bottom})"
    Integer t = (top as Integer) ?: 0
    Integer b = (bottom as Integer) ?: 0
    def resp = hubBypass("setOscillationStatus",
        [verticalOscillationState: 1, actType: "default", top: t, bottom: b],
        "setOscillationStatus(V_range=${t}..${b})")
    if (httpOk(resp)) {
        device.sendEvent(name:"verticalOscillation", value: "on")
        device.sendEvent(name:"oscillationTop",    value: t)
        device.sendEvent(name:"oscillationBottom", value: b)
        logInfo "Vertical oscillation range: ${t}-${b}"
    } else {
        logError "Vertical range write failed"; recordError("Vertical range write failed", [method:"setOscillationStatus"])
    }
}

// ---------- Feature setters ----------
def setMute(onOff){
    logDebug "setMute(${onOff})"
    if (onOff == null) { logWarn "setMute called with null (likely empty Rule Machine action parameter); ignoring"; return }
    String s = (onOff as String).toLowerCase()
    if (!(s in ["on","off"])) { logError "setMute: invalid value '${s}'"; recordError("setMute invalid: ${s}", [method:"setMuteSwitch"]); return }
    Integer v = (s == "on") ? 1 : 0
    def resp = hubBypass("setMuteSwitch", [muteSwitch: v], "setMuteSwitch(${s})")
    if (httpOk(resp)) {
        device.sendEvent(name:"mute", value: s)
        logInfo "Mute: ${s}"
    } else {
        logError "Mute write failed"; recordError("Mute write failed", [method:"setMuteSwitch"])
    }
}

def setDisplay(onOff){
    logDebug "setDisplay(${onOff})"
    if (onOff == null) { logWarn "setDisplay called with null (likely empty Rule Machine action parameter); ignoring"; return }
    String s = (onOff as String).toLowerCase()
    if (!(s in ["on","off"])) { logError "setDisplay: invalid value '${s}'"; recordError("setDisplay invalid: ${s}", [method:"setDisplaySwitch"]); return }
    Integer v = (s == "on") ? 1 : 0
    def resp = hubBypass("setDisplay", [screenSwitch: v], "setDisplay(${s})")
    if (httpOk(resp)) {
        device.sendEvent(name:"displayOn", value: s)
        logInfo "Display: ${s}"
    } else {
        logError "Display write failed"; recordError("Display write failed", [method:"setDisplay"])
    }
}

// ---------- v2.4 write-path additions ----------

// CROSS-CHECK [v2.4 hardware capture / iteration #1 / pyvesync gap]:
//   [PREVIEW v2.4 — payload is a best-effort guess pending live command verification]
//   Candidate #1 (current): method "setChildLock" + payload field "childLock" (symmetric to
//     the read field name). This is iteration #1 after candidate #0 was REFUTED by live hardware.
//   Candidate #0 REFUTED: method "setChildLockSwitch" + payload {childLockSwitch: v} -- tested
//     on device 1132 (2026-04-30); device returned HTTP 200 with inner code -1, confirming
//     Sister-Switch convention does NOT apply to childLock. Payload rejected at device level.
//   Rationale for #1: if the read field is "childLock", the most symmetric write payload is
//     also "childLock" (mirrors the setHumidifierStatus convention in other families where
//     read and write share the same field name). Method "setChildLock" (no Switch suffix)
//     follows the same logic: setChildLock / {childLock}.
//   Remaining candidates (if #1 also returns inner -1):
//     #2: method "setChildLockSwitch" + payload {childLock: v}
//     #3: method "setChildLock" + payload {childLockSwitch: v}
//     #4: method "setLock" + payload {lock: v}
//   Source: live poll response (device 1132, 2026-04-30); pyvesync gap confirmed (no
//     set_child_lock() method in VeSyncPedestalFan as of 2026-04-30).
def setChildLock(onOff){
    logDebug "setChildLock(${onOff})"
    if (onOff == null) {
        logWarn "setChildLock called with null (likely empty Rule Machine action parameter); ignoring"
        return
    }
    String s = (onOff as String).toLowerCase()
    if (!(s in ["on","off"])) {
        logError "setChildLock: invalid value '${s}' -- must be on|off"
        recordError("setChildLock invalid: ${s}", [method:"setChildLock"])
        return
    }
    int v = (s == "on") ? 1 : 0
    // [PREVIEW v2.4] iteration #1: method setChildLock + payload {childLock} (symmetric to read field)
    def resp = hubBypass("setChildLock", [childLock: v], "setChildLock(${s})")
    if (httpOk(resp)) {
        device.sendEvent(name:"childLock", value: s)
        logInfo "Child lock: ${s}"
    } else {
        logError "Child lock write failed"
        recordError("Child lock write failed: ${s}", [method:"setChildLock"])
    }
}

// CROSS-CHECK [maintainer's hardware capture, device 1132 LPF-R432S-AUK, 2026-05-01]:
//   Decision: Pedestal Fan timer commands (setTimer/cancelTimer) deferred to v2.5+.
//   Rationale: Two payload guesses both refuted on live hardware (HTTP 200, inner -1
//   on both `setTimer + {action: "on"|"off", total: N}` and `clearTimer + {}`).
//   Combined evidence: pyvesync VeSyncPedestalFan has no timer methods; HA PR #163353
//   (Pedestal Fan timer support) is still open/unmerged after months.
//   Hypothesis: VeSync app's "timer" feature maps to a "schedule" API namespace
//   (poll fields suggest this: scheduleCount, isTimerSupportPowerOn capability flag).
//   Resolution path: maintainer captures VeSync app's timer-set request via mitmproxy
//   to identify the actual API method + payload shape; revisit in a clean v2.5 cycle.
//   The `timerRemain` read-only attribute stays declared for status visibility.

// CROSS-CHECK [maintainer's hardware capture, device 1132 LPF-R432S-AUK, 2026-05-01]:
//   The following write-path commands were attempted with educated-guess payloads
//   (informed by read-shape field names + sister-method patterns from Tower Fan)
//   and ALL refuted via live hardware tests. Each returned a non-zero VeSync API
//   inner code, indicating method-doesn't-exist or payload-format-wrong:
//
//   - setSleepPreference: tried flat {sleepPreferenceType} and nested
//     {sleepPreference: {...}}, both "advanced" and "default" values; all rejected
//     with inner 11000000.
//   - setHighTemperatureThreshold: tried setHighTemperature + {highTemperature: degF*10}; rejected.
//   - setHighTemperatureReminder: tried setHighTemperatureReminder + {highTemperatureReminderState: 1|0}; rejected.
//   - setLevelMemory: tried setLevelMemory + {workMode, level, enable}; rejected.
//   - runOscillationCalibration: tried oscillationCalibration + {}; rejected.
//
//   Hypothesis: VeSync mobile app uses a different API namespace (possibly schedule-style
//   for timer features, possibly local-network for oscillation calibration) OR these
//   features are not exposed via the cloud API at all. Read-side fields populate fine
//   on poll, so the device DOES track this state -- we just can't write to it via the
//   guessed cloud paths.
//
//   Resolution path: maintainer captures the VeSync mobile app's actual API request
//   for each feature via mitmproxy; revisit in v2.5+ with confirmed payloads.
//   The READ-ONLY attributes for these fields stay declared so users can see device
//   state, even though they can't change it from Hubitat yet.

// CROSS-CHECK [v2.4 hardware capture / field-name convention]:
//   [PREVIEW v2.4 — method name and payload field are best-effort guesses]
//   Decision: method "setSmartCleaningReminder" with {smartCleaningReminderState: 1|0}.
//     Payload field matches the read-field name exactly (smartCleaningReminderState).
//   Source: live poll smartCleaningReminderState:1 (device 1132, 2026-04-30).
//   Refutation: inner code -1 --> try payload field "smartCleaningReminder" (without "State"
//     suffix) or try method "setSmartCleaning"; update CROSS-CHECK when confirmed.
def setSmartCleaningReminder(onOff){
    logDebug "setSmartCleaningReminder(${onOff})"
    if (onOff == null) { logWarn "setSmartCleaningReminder called with null; ignoring"; return }
    String s = (onOff as String).toLowerCase()
    if (!(s in ["on","off"])) {
        logError "setSmartCleaningReminder: invalid value '${s}'"
        recordError("setSmartCleaningReminder invalid: ${s}", [method:"setSmartCleaningReminder"])
        return
    }
    int v = (s == "on") ? 1 : 0
    def resp = hubBypass("setSmartCleaningReminder", [smartCleaningReminderState: v],
                         "setSmartCleaningReminder(${s})")
    if (httpOk(resp)) {
        device.sendEvent(name:"smartCleaningReminder", value: s)
        logInfo "Smart cleaning reminder: ${s}"
    } else {
        logError "Smart cleaning reminder write failed"
        recordError("Smart cleaning reminder failed: ${s}", [method:"setSmartCleaningReminder"])
    }
}

// ---------- Refresh ----------
def refresh(){ update() }

// ---------- Update / status ----------
// Self-fetch when called directly (no-arg).
// NOTE: this path passes resp.data into applyStatus; the parent-callback path (1-arg/2-arg
// below) passes data.result already. The peel-while-loop in applyStatus handles both shapes
// defensively, but the data-flow asymmetry means applyStatus's first peel iteration is doing
// different work depending on entry point.
def update(){
    logDebug "update() self-fetch"
    // Pedestal Fan uses getFanStatus (NOT getTowerFanStatus -- different from Tower Fan)
    def resp = hubBypass("getFanStatus", [:], "update")
    if (httpOk(resp)) {
        def status = resp?.data
        if (!status?.result) { logError "No status returned from getFanStatus"; recordError("No status returned from getFanStatus", [method:"update"]) }
        else applyStatus(status)
    }
}

// Parent-poll, 1-arg signature (defensive; parent always uses 2-arg)
def update(status){
    logDebug "update() from parent (1-arg)"
    applyStatus(status)
    return true
}

// 2-arg variant -- REQUIRED (Bug Pattern #1); parent always calls with two args.
// nightLight parameter is ignored -- Pedestal Fan has no night-light hardware.
def update(status, nightLight){
    logDebug "update() from parent (2-arg, nightLight ignored -- Pedestal Fan has no nightlight)"
    applyStatus(status)
    return true
}

// ---------- applyStatus ----------
def applyStatus(status){
    logDebug "applyStatus()"

    // BP16 watchdog: auto-disable debugOutput after 30 min even across hub reboots.
    // Placed here so all three update() entry points (0-arg, 1-arg, 2-arg) trigger it.
    ensureDebugWatchdog()

    // One-time pref seed: heal descriptionTextEnable=true default for users migrated
    // from older Type without Save (forward-compat -- Bug Pattern #12)
    if (!state.prefsSeeded) {
        if (settings?.descriptionTextEnable == null) {
            device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
        }
        state.prefsSeeded = true
    }

    def r = status?.result ?: [:]
    // Defensive envelope peel -- Pedestal Fan bypassV2 responses are purifier-style
    // (single-wrapped), but we run the same defensive peel loop used by all V2-line
    // drivers in case a firmware update or API change introduces double-wrapping (BP#3).
    int peelGuard = 0
    while (r instanceof Map && r.containsKey('code') && r.containsKey('result') && r.result instanceof Map && peelGuard < 4) {
        r = r.result
        peelGuard++
    }
    // Diagnostic raw dump -- gated by debugOutput. Keep for ongoing field diagnostics.
    // Use this log line when a community member reports "device discovered but no data".
    if (settings?.debugOutput) log.debug "applyStatus raw r (after peel=${peelGuard}) keys=${r?.keySet()}, values=${r}"

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
    // Bug Pattern #6: when device is off, speed reports "off" regardless of last-set level.
    if (!powerOn) {
        device.sendEvent(name:"speed", value:"off")
        device.sendEvent(name:"level", value: percentFromLevel(activeSpeed))
    } else {
        String speedEnum = levelToFanControlEnum(activeSpeed)
        device.sendEvent(name:"speed", value: speedEnum)
        device.sendEvent(name:"level", value: percentFromLevel(activeSpeed))
    }

    // ---- Mode ----
    // CROSS-CHECK: reverse-map API "advancedSleep" -> user-facing "sleep".
    // See setMode() CROSS-CHECK block above for full rationale (pyvesync device_map.py
    // LPF-R432S FanModes.SLEEP:'advancedSleep' + HA cross-check finding #d).
    // Other modes (normal, turbo, eco) pass through unchanged.
    String rawWorkMode   = (r.workMode ?: "normal") as String
    String reportedMode  = (rawWorkMode == "advancedSleep") ? "sleep" : rawWorkMode
    state.mode = reportedMode
    device.sendEvent(name:"mode", value: reportedMode)

    // ---- Horizontal oscillation ----
    Integer hOscState = (r.horizontalOscillationState as Integer)
    device.sendEvent(name:"horizontalOscillation", value: hOscState == 1 ? "on" : "off")

    // ---- Vertical oscillation ----
    Integer vOscState = (r.verticalOscillationState as Integer)
    device.sendEvent(name:"verticalOscillation", value: vOscState == 1 ? "on" : "off")

    // ---- Oscillation range (nested object) ----
    if (r.oscillationRange instanceof Map) {
        def oRange = r.oscillationRange
        if (oRange.left    != null) device.sendEvent(name:"oscillationLeft",   value: oRange.left    as Integer)
        if (oRange.right   != null) device.sendEvent(name:"oscillationRight",  value: oRange.right   as Integer)
        if (oRange.top     != null) device.sendEvent(name:"oscillationTop",    value: oRange.top     as Integer)
        if (oRange.bottom  != null) device.sendEvent(name:"oscillationBottom", value: oRange.bottom  as Integer)
    }

    // ---- Oscillation coordinate (head position, read-only) ----
    if (r.oscillationCoordinate instanceof Map) {
        def oCoord = r.oscillationCoordinate
        if (oCoord.yaw   != null) device.sendEvent(name:"oscillationYaw",   value: oCoord.yaw   as Integer)
        if (oCoord.pitch != null) device.sendEvent(name:"oscillationPitch", value: oCoord.pitch  as Integer)
    }

    // ---- Oscillation calibration (read-only feedback for runOscillationCalibration) ----
    if (r.oscillationCalibrationState != null) {
        Integer cs = (r.oscillationCalibrationState as Integer)
        device.sendEvent(name:"oscillationCalibrationState", value: cs == 1 ? "calibrating" : "idle")
    }
    if (r.oscillationCalibrationProgress != null) {
        device.sendEvent(name:"oscillationCalibrationProgress", value: (r.oscillationCalibrationProgress as Integer))
    }

    // ---- High temperature threshold + reminder ----
    // Raw value is tenths of a degree F (same /10 convention as existing temperature field)
    if (r.highTemperature != null) {
        Integer rawHigh = (r.highTemperature as Integer)
        device.sendEvent(name:"highTemperature", value: (rawHigh / 10.0) as BigDecimal)
    }
    if (r.highTemperatureReminderState != null) {
        Integer hrm = (r.highTemperatureReminderState as Integer)
        device.sendEvent(name:"highTemperatureReminder", value: hrm == 1 ? "on" : "off")
    }

    // ---- Smart cleaning reminder ----
    if (r.smartCleaningReminderState != null) {
        Integer scrm = (r.smartCleaningReminderState as Integer)
        device.sendEvent(name:"smartCleaningReminder", value: scrm == 1 ? "on" : "off")
    }

    // ---- Mute ----
    // muteState = actual; muteSwitch = configured. Prefer actual.
    Integer muteState = (r.muteState != null) ? (r.muteState as Integer) : (r.muteSwitch as Integer)
    device.sendEvent(name:"mute", value: muteState == 1 ? "on" : "off")

    // ---- Display ----
    // screenState = actual; screenSwitch = configured. Prefer actual.
    Integer screenState = (r.screenState != null) ? (r.screenState as Integer) : (r.screenSwitch as Integer)
    device.sendEvent(name:"displayOn", value: screenState == 1 ? "on" : "off")

    // ---- childLock ----
    // CROSS-CHECK [pyvesync VeSyncPedestalFan class / v2.4 hardware capture / iteration #1]:
    //   Read field "childLock" confirmed from live poll (device 1132, 2026-04-30). Value is 0|1 int.
    //   Write candidate #0 REFUTED: setChildLockSwitch + {childLockSwitch} returned inner -1 on
    //     device 1132 (2026-04-30). Write candidate #1 (current): setChildLock + {childLock}.
    //   See setChildLock() CROSS-CHECK block above for full refutation chain and remaining candidates.
    if (r.childLock != null) {
        device.sendEvent(name:"childLock", value: (r.childLock as Integer) == 1 ? "on" : "off")
    }

    // ---- Temperature ----
    // CROSS-CHECK [pyvesync vesyncfan.py:314 / HA cross-check / ST+HB cross-check]:
    //   Decision: divide temperature by 10 (raw / 10 = degrees F). NO ambiguity.
    //   Rationale: pyvesync VeSyncPedestalFan source explicitly does:
    //     state.temperature = (res.temperature / 10) if res.temperature else None
    //     (vesyncfan.py:314). Both HA cross-check and ST+HB cross-check independently
    //     confirm /10 for Pedestal Fan. This is the non-contentious case (contrast with
    //     Tower Fan where the pyvesync source has a known asymmetry/bug).
    //   Source: https://github.com/webdjoe/pyvesync/blob/master/src/pyvesync/devices/
    //     vesyncfan.py line 314 (VeSyncPedestalFan, temperature / 10).
    //   Refutation: community user reports impossible value (e.g. 750°F) -- investigate
    //     pyvesync source to see if the /10 was changed in a newer version.
    if (r.temperature != null) {
        Integer rawTemp = r.temperature as Integer
        // Sanity gate: 0 raw = 0°F which is almost certainly an uninitialized field, skip
        if (rawTemp > 0) {
            Float tempF = rawTemp / 10.0f
            device.sendEvent(name:"temperature", value: tempF, unit:"°F")
        }
    }

    // ---- Sleep preference (nested object) ----
    if (r.sleepPreference instanceof Map) {
        String spType = r.sleepPreference?.sleepPreferenceType as String
        if (spType) device.sendEvent(name:"sleepPreferenceType", value: spType)
    }

    // ---- Error code ----
    if (r.errorCode != null) {
        Integer ec = r.errorCode as Integer
        device.sendEvent(name:"errorCode", value: ec)
        Integer lastEc = state.lastErrorCode as Integer
        if (ec != 0 && (lastEc == null || lastEc == 0)) logInfo "Device error code: ${ec}"
        state.lastErrorCode = ec
    }

    // ---- Info HTML (use local variables -- avoids device.currentValue race; BP#7) ----
    def parts = []
    parts << "Mode: ${reportedMode}"
    parts << "Speed: ${powerOn ? levelToFanControlEnum(activeSpeed) + ' (L' + activeSpeed + ')' : 'off'}"
    parts << "H-Osc: ${hOscState == 1 ? 'on' : 'off'}"
    parts << "V-Osc: ${vOscState == 1 ? 'on' : 'off'}"
    parts << "Mute: ${muteState == 1 ? 'on' : 'off'}"
    if (r.temperature != null && (r.temperature as Integer) > 0) {
        Float tf = (r.temperature as Integer) / 10.0f
        parts << "Temp: ${tf}°F"
    }
    device.sendEvent(name:"info", value: parts.join("<br>"))
}

// ---------- Internal helpers ----------

// Send a raw 1-12 fan speed level to the device.
// Uses V2-API field names: levelIdx, levelType, manualSpeedLevel (Bug Pattern #4).
// NOT legacy Core-line names: id, type, level.
private boolean sendLevel(Integer level){
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
        logError "Speed write failed for level ${level}"; recordError("Speed write failed for level ${level}", [method:"setLevel"])
        return false
    }
}

// Map raw fan level 1-12 to Hubitat FanControl capability speed enum.
// Hubitat FanControl enum: off | low | medium-low | medium | medium-high | high | on | auto
// 12 levels -> 5 non-auto buckets: low(1-2), medium-low(3-4), medium(5-6), medium-high(7-8), high(9-12)
private String levelToFanControlEnum(Integer level){
    if (level == null || level <= 0) return "off"
    if (level <= 2)  return "low"
    if (level <= 4)  return "medium-low"
    if (level <= 6)  return "medium"
    if (level <= 8)  return "medium-high"
    return "high"   // 9-12
}

// Map Hubitat FanControl speed enum back to a 1-12 representative level for writes.
private Integer fanControlEnumToLevel(String s){
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
private Integer percentFromLevel(Integer level){
    if (level == null || level < 1) return 8   // level 1 = ~8%
    if (level >= 12) return 100
    // Map 1-12 linearly: level 1 = 8%, level 12 = 100%
    return Math.round(((level - 1) / 11.0) * 92 + 8) as Integer
}

// Map SwitchLevel percentage 0-100 to raw 1-12 level.
private Integer levelFromPercent(Integer pct){
    if (pct == null || pct <= 0) return 1
    if (pct >= 100) return 12
    return Math.max(1, Math.min(12, Math.round(((pct - 8) / 92.0) * 11 + 1) as Integer))
}

// ---------- Logging ----------
def logDebug(msg){ if (settings?.debugOutput) log.debug msg }
def logError(msg){ log.error msg }
def logWarn(msg){ log.warn msg }
def logInfo(msg){ if (settings?.descriptionTextEnable) log.info msg }
void logDebugOff(){ if (settings?.debugOutput) device.updateSetting("debugOutput", [type:"bool", value:false]) }

// BP16 debug watchdog — auto-disable stuck debugOutput after hub reboot
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

// ---------- Hub/parent call wrapper ----------
// Matches sibling driver pattern (LevoitVital200S, LevoitTowerFan, etc.)
private hubBypass(method, Map data=[:], tag=null, cb=null){
    def rspObj = [status: -1, data: null]
    parent.sendBypassRequest(device, [method: method, source: "APP", data: data]) { resp ->
        rspObj = [status: resp?.status, data: resp?.data]
        def inner = resp?.data?.result?.code
        if (tag) logDebug "${tag} -> HTTP ${resp?.status}, inner ${inner}"
        if (cb) cb(resp)
    }
    return rspObj
}

private boolean httpOk(resp){
    if (!resp) return false
    def st = resp.status as Integer
    if (st in [200,201,204]){
        def inner = resp?.data?.result?.code
        if (inner == null || inner == 0) return true
        logDebug "HTTP 200, innerCode ${inner}"
        return false
    }
    logError "HTTP ${st}"; recordError("HTTP ${st}", [site:"httpOk"])
    return false
}

// ------------- END -------------
