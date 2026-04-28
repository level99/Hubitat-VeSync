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

// NOTE: Pedestal Fan ships as a preview driver -- maintainer's Pedestal Fan
// hardware is en-route (week of 2026-04-26). Driver built from canonical
// pyvesync fixtures + HA cross-check + SmartThings/Homebridge cross-check.
//
// Confirmed write paths (commands exposed):
//   - power, setSpeed, setMode, setHorizontalOscillation, setVerticalOscillation,
//     setHorizontalRange, setVerticalRange, setMute, setDisplay
//
// Read-only (no setter exposed; would need real-device payload capture):
//   - childLock (pyvesync VeSyncPedestalFan has no set_child_lock method;
//     ST+HB also silent on the request shape)
//
// Omitted (no community evidence; defer to v2.2 after hardware-arrival
// captures):
//   - Timer (no setTimer/clearTimer methods in pyvesync VeSyncPedestalFan;
//     not in fixture; HA PR #163353 still open)
//
// Note on pyvesync filename typo: pyvesync's fixture is LPF-R423S.yaml
// but real device codes are LPF-R432S-AEU/AUS. Fixture content is correct;
// the filename has a number transposition.
//
// Please report any issues at:
//   https://github.com/level99/Hubitat-VeSync/issues
//
// (Note: this comment is the driver-level "preview" indicator pending
//  the Phase 4 sweep that adds [PREVIEW] prefix to definition() across
//  all 5 v2.1 drivers consistently.)

/*
 *  Levoit Pedestal Fan (LPF-R432S) — Hubitat driver
 *
 *  Targets:    LPF-R432S-AEU, LPF-R432S-AUS
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
 *    - childLock appears in PedestalFanResult but pyvesync has no setter.
 *      Exposed as read-only attribute. No setChildLock command.
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

metadata {
    definition(
        name: "Levoit Pedestal Fan",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "[PREVIEW v2.1] Levoit Pedestal Fan (LPF-R432S-AEU/AUS) — power, fan speed 1-12, modes (normal/turbo/eco/sleep), 2-axis oscillation with range control, mute, display, ambient temperature; canonical pyvesync payloads",
        version: "2.2",
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
        // childLock: READ-ONLY. pyvesync VeSyncPedestalFan has no set_child_lock method.
        // ST+HB cross-check also confirms no setter. Exposed as diagnostic read-only attribute.
        attribute "childLock",              "string"    // on | off -- READ-ONLY, NO setter command
        attribute "errorCode",              "number"
        // sleepPreference nested fields (read-only)
        attribute "sleepPreferenceType",    "string"    // e.g. "default", "advanced", "quiet"
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

        // NOTE: setChildLock is intentionally absent -- see applyStatus() CROSS-CHECK block
        // for rationale (pyvesync VeSyncPedestalFan: no set_child_lock method; ST+HB: silent).
        //
        // CROSS-CHECK [pyvesync VeSyncPedestalFan class / LPF-R423S.yaml fixture /
        //   HA PR #163353 (open as of 2026-04-26)]:
        //   Decision: setTimer/cancelTimer are intentionally absent from v2.1.
        //   Rationale: pyvesync VeSyncPedestalFan has no set_timer() or clear_timer() methods.
        //     Timer commands are not present in the LPF-R423S.yaml fixture. HA PR #163353
        //     (fan timer support) was still open/unmerged as of 2026-04-26; no confirmed
        //     payload shapes from any community source. We don't ship commands we'd have to guess.
        //   Source: https://github.com/webdjoe/pyvesync (VeSyncPedestalFan class, no timer);
        //     https://github.com/home-assistant/core/pull/163353 (HA fan timer PR, open).
        //   Refutation: maintainer's hardware capture reveals timer payload --> add setTimer/
        //     cancelTimer in v2.2 using the confirmed payload format.
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
    def resp = hubBypass("setSwitch", [powerSwitch: 1, switchIdx: 0], "setSwitch(power=1)")
    if (httpOk(resp)) {
        logInfo "Power on"
        state.lastSwitchSet = "on"
        device.sendEvent(name:"switch", value:"on")
    } else {
        logError "Power on failed"
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
        logError "Power off failed"
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
            return
        }
        sendLevel(rawLevel)
        return
    }
    // Enum string (FanControl capability path)
    String s = (spd as String).toLowerCase()
    if (s == "off")  { off(); return }
    if (s == "on")   { on(); return }   // Hubitat FanControl spec: "on" resumes at prior/default speed
    if (s == "auto") { setMode("eco"); return }  // Pedestal Fan: auto maps to eco (no auto mode)
    Integer lvl = fanControlEnumToLevel(s)
    if (lvl == null) { logError "setSpeed: unknown enum value '${s}'"; return }
    sendLevel(lvl)
}

// FanControl.cycleSpeed() -- advance through levels in order
def cycleSpeed(){
    logDebug "cycleSpeed()"
    Integer cur = state.fanLevel as Integer ?: 1
    Integer next = (cur >= 12) ? 1 : (cur + 1)
    sendLevel(next)
}

// SwitchLevel capability: setLevel(percent 0-100) -> map to 1-12
// SwitchLevel convention: setLevel(0) turns the device off (matches Z-Wave dimmer platform expectation).
def setLevel(val){
    logDebug "setLevel(${val})"
    Integer pct = Math.max(0, Math.min(100, (val as Integer) ?: 0))
    if (pct == 0) { off(); return }
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
    String m = (mode as String).toLowerCase()
    if (!(m in ["normal","turbo","eco","sleep"])) {
        logError "setMode: invalid mode '${m}' -- must be normal|turbo|eco|sleep"
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
        logError "Mode write failed: ${m}"
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
    Integer v = (onOff == "on") ? 1 : 0
    def resp = hubBypass("setOscillationStatus",
        [horizontalOscillationState: v, actType: "default"],
        "setOscillationStatus(H=${onOff})")
    if (httpOk(resp)) {
        device.sendEvent(name:"horizontalOscillation", value: onOff)
        logInfo "Horizontal oscillation: ${onOff}"
    } else {
        logError "Horizontal oscillation write failed"
    }
}

// Toggle vertical oscillation on or off (without changing range)
def setVerticalOscillation(onOff){
    logDebug "setVerticalOscillation(${onOff})"
    Integer v = (onOff == "on") ? 1 : 0
    def resp = hubBypass("setOscillationStatus",
        [verticalOscillationState: v, actType: "default"],
        "setOscillationStatus(V=${onOff})")
    if (httpOk(resp)) {
        device.sendEvent(name:"verticalOscillation", value: onOff)
        logInfo "Vertical oscillation: ${onOff}"
    } else {
        logError "Vertical oscillation write failed"
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
        logError "Horizontal range write failed"
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
        logError "Vertical range write failed"
    }
}

// ---------- Feature setters ----------
def setMute(onOff){
    logDebug "setMute(${onOff})"
    Integer v = (onOff == "on") ? 1 : 0
    def resp = hubBypass("setMuteSwitch", [muteSwitch: v], "setMuteSwitch(${onOff})")
    if (httpOk(resp)) {
        device.sendEvent(name:"mute", value: onOff)
        logInfo "Mute: ${onOff}"
    } else {
        logError "Mute write failed"
    }
}

def setDisplay(onOff){
    logDebug "setDisplay(${onOff})"
    Integer v = (onOff == "on") ? 1 : 0
    def resp = hubBypass("setDisplay", [screenSwitch: v], "setDisplay(${onOff})")
    if (httpOk(resp)) {
        device.sendEvent(name:"displayOn", value: onOff)
        logInfo "Display: ${onOff}"
    } else {
        logError "Display write failed"
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
        if (!status?.result) logError "No status returned from getFanStatus"
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
    ensureDebugWatchdog()
    logDebug "update() from parent (2-arg, nightLight ignored -- Pedestal Fan has no nightlight)"
    applyStatus(status)
    return true
}

// ---------- applyStatus ----------
def applyStatus(status){
    logDebug "applyStatus()"

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

    // ---- Mute ----
    // muteState = actual; muteSwitch = configured. Prefer actual.
    Integer muteState = (r.muteState != null) ? (r.muteState as Integer) : (r.muteSwitch as Integer)
    device.sendEvent(name:"mute", value: muteState == 1 ? "on" : "off")

    // ---- Display ----
    // screenState = actual; screenSwitch = configured. Prefer actual.
    Integer screenState = (r.screenState != null) ? (r.screenState as Integer) : (r.screenSwitch as Integer)
    device.sendEvent(name:"displayOn", value: screenState == 1 ? "on" : "off")

    // ---- childLock (READ-ONLY) ----
    // CROSS-CHECK [pyvesync VeSyncPedestalFan class / ST+HB cross-check]:
    //   Decision: expose childLock as read-only attribute only; NO setChildLock command.
    //   Rationale: pyvesync VeSyncPedestalFan has no set_child_lock() method (contrast with
    //     VeSyncTowerFan which also lacks it -- both fans omit child-lock write support in
    //     pyvesync). ST+HB community drivers are also silent on the request payload shape.
    //     Without any community-captured request payload, we'd be guessing the field names
    //     and values; wrong guesses produce inner code -1 silently.
    //   Source: https://github.com/webdjoe/pyvesync/blob/master/src/pyvesync/devices/
    //     vesyncfan.py VeSyncPedestalFan class (no set_child_lock method present);
    //     ST+HB cross-check (2026-04-26, silent on payload).
    //   Refutation: maintainer's hardware capture (week of 2026-04-26) reveals the payload
    //     format --> add setChildLock command in v2.2 with the confirmed payload.
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
        logError "Speed write failed for level ${level}"
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
    logError "HTTP ${st}"
    return false
}

// ------------- END -------------
