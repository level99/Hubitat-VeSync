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
 *    - Infrastructure methods (lifecycle, power, toggle, polling, HTTP plumbing)
 *      and shared V2-fan body methods (cycleSpeed, setLevel, sendLevel, speed
 *      enum/percent math, doSetMuteSwitch, doSetDisplayScreenSwitch) are
 *      provided by #include level99.LevoitFan (LevoitFanLib.groovy).
 *
 *  History:
 *    2026-05-03: v2.5  Phase 5 — migrated to LevoitFanLib.groovy.
 *                      Removed 12 infra methods + 8 shared V2-fan body methods
 *                      (now provided by LevoitFanLib). Added 1-line delegators
 *                      for setMute and setDisplay.
 *                      BP24-B fix: cycleSpeed() + setSpeed() now auto-turn-on
 *                      device when off (ensureSwitchOn() in LevoitFanLib
 *                      cycleSpeed body; ensureSwitchOn() at top of per-driver
 *                      setSpeed body).
 *                      BP18 normalization: setHorizontalRange and setVerticalRange
 *                      now explicitly reject null args with logWarn + return,
 *                      replacing the previous silent-zeroing ?: coercions.
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
#include level99.LevoitChildBase
#include level99.LevoitFan

metadata {
    definition(
        name: "Levoit Pedestal Fan",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "Levoit Pedestal Fan (LPF-R432S-AEU/AUS/AUK) — power, fan speed 1-12, modes (normal/turbo/eco/sleep), 2-axis oscillation with range control, mute, display, child lock, smart cleaning reminder, ambient temperature",
        version: "2.8",
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
        attribute "oscillationLeft",        "number"    // horizontal left bound (0-90)
        attribute "oscillationRight",       "number"    // horizontal right bound (0-90)
        attribute "oscillationTop",         "number"    // vertical top bound (0-120)
        attribute "oscillationBottom",      "number"    // vertical bottom bound (0-120)
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
        // Range values are device angular degrees (horizontal 0-90, vertical 0-120;
        // live-verified on LPF-R432S-AUS). State is always set to 1 (on) in range payloads.
        command "setHorizontalRange", [
            [name:"Left*",  type:"NUMBER", description:"Left bound (0-90)"],
            [name:"Right*", type:"NUMBER", description:"Right bound (0-90)"]
        ]
        command "setVerticalRange", [
            [name:"Top*",    type:"NUMBER", description:"Top bound (0-120)"],
            [name:"Bottom*", type:"NUMBER", description:"Bottom bound (0-120)"]
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

// ---------- FanControl capability ----------
// FanControl.setSpeed() accepts Hubitat's speed enum: off/low/medium-low/medium/medium-high/high/auto
// We also expose a raw setSpeed(NUMBER) command for 1-12 integer levels.
// Both routes funnel through the same sendLevel() internal helper (provided by LevoitFanLib).
// NOTE: Pedestal Fan maps the FanControl "auto" enum to setMode("eco") -- Pedestal Fan has
// no "auto" mode; "eco" is the closest semantic equivalent.
def setSpeed(spd){
    logDebug "setSpeed(${spd})"
    if (!requireNonEmptyEnum(spd, "setSpeed")) return
    // Short-circuit power commands before the auto-on guard.
    // setSpeed("off") must NOT trigger ensureSwitchOn() — the intent is explicitly to turn off.
    // setSpeed("on") short-circuits here too for symmetry (on() does everything needed).
    if (!(spd instanceof Number) && !(spd instanceof String && spd.isInteger())) {
        String early = (spd as String).trim().toLowerCase()
        if (early == "off") { off(); return }
        if (early == "on")  { on(); return }
    }
    // Resolve + validate the requested level BEFORE ensureSwitchOn() so an invalid speed
    // never auto-powers the device on (BP24-class: reject-before-auto-on). Previously
    // ensureSwitchOn() ran first, so setSpeed("turbo") from off turned the device ON and
    // then no-op'd at the enum-null reject below -- a phantom power-on with no speed set.
    Integer lvl
    boolean isRaw = (spd instanceof Number || (spd instanceof String && spd.isInteger()))
    if (isRaw) {
        Integer rawLevel = (spd as Integer)
        if (rawLevel < 1 || rawLevel > 12) {
            logError "setSpeed: invalid raw level ${rawLevel} -- must be 1-12"
            recordError("setSpeed: invalid raw level ${rawLevel}", [method:"setLevel"])
            return
        }
        lvl = rawLevel
    } else {
        // Enum string (FanControl capability path). "auto" is a mode, not a level.
        String s = (spd as String).trim().toLowerCase()
        if (s == "auto") { ensureSwitchOn(); setMode("eco"); return }  // Pedestal Fan: auto maps to eco (no auto mode)
        lvl = fanControlEnumToLevel(s)
        if (lvl == null) { logError "setSpeed: unknown enum value '${s}'"; recordError("setSpeed: unknown enum '${s}'", [method:"setLevel"]); return }
    }
    // BP24-B: auto-on when switch is off (SwitchLevel/FanControl capability convention).
    // Placed after the off/on short-circuits AND after speed validation so neither
    // setSpeed("off") nor an invalid speed triggers auto-on.
    // ensureSwitchOn() is provided by #include level99.LevoitChildBase.
    ensureSwitchOn()
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
//   If contradicted in the future: a community report showing "setFanMode" is rejected
//     and "setTowerFanMode" works would mean swapping methods and updating this header.
//     No such report yet — current behavior follows pyvesync.
//
// CROSS-CHECK [pyvesync device_map.py LPF-R432S entry / HA cross-check finding #d]:
//   Decision: "sleep" reverse-maps to/from API "advancedSleep" (same pattern as Tower Fan).
//   Decision: mode "eco" (NOT "auto") is the Pedestal Fan's 4th mode.
//     Tower Fan has "auto"; Pedestal Fan has "eco" -- do NOT confuse these.
//   Source: pyvesync device_map.py LPF-R432S FanModes entries (eco, advancedSleep);
//     HA vesync cross-check finding #d (advancedSleep reverse-mapping).
//   If contradicted in the future (eco vs auto): a community report showing "auto" is
//     accepted on the Pedestal Fan would mean adding "auto" as an alias or replacing
//     "eco" with "auto". No such report yet — current behavior follows pyvesync.
// BP24: SHOULD-ON — asking an off fan to change mode auto-turns it on (matches speed/level
//   setters). ensureSwitchOn() runs AFTER validation so invalid input cannot wake an off device.
def setMode(mode){
    logDebug "setMode(${mode})"
    if (!requireNonEmptyEnum(mode, "setMode")) return
    String m = (mode as String).trim().toLowerCase()
    if (!(m in ["normal","turbo","eco","sleep"])) {
        logError "setMode: invalid mode '${m}' -- must be normal|turbo|eco|sleep"
        recordError("setMode: invalid mode '${m}'", [method:"setFanMode"])
        return
    }
    ensureSwitchOn()
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

// ---------- Feature setters ----------
// Public delegators for methods whose bodies live in LevoitFanLib.
// BP24: NO-ON — configures a device preference; powering on is not implied.
def setMute(o)    { doSetMuteSwitch(o) }
// BP24: NO-ON — configures a device preference; powering on is not implied.
def setDisplay(o) { doSetDisplayScreenSwitch(o) }

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
// BP24: NO-ON — configures a device preference; powering on is not implied.
def setHorizontalOscillation(onOff){
    logDebug "setHorizontalOscillation(${onOff})"
    if (!requireNonEmptyEnum(onOff, "setHorizontalOscillation")) return
    String s = (onOff as String).trim().toLowerCase()
    if (!(s in ["on","off"])) { logError "setHorizontalOscillation: invalid value '${s}'"; recordError("setHorizontalOscillation invalid: ${s}", [method:"setOscillationStatus"]); return }
    // C3 state-change gate: suppress redundant cloud calls when value already matches attribute.
    if (device.currentValue("horizontalOscillation") == s) return
    // BP24 NO-ON note: still send the command; just inform if the fan is off (setting applies on power-on).
    noteOscillationOffState()
    Integer v = (s == "on") ? 1 : 0  // strict-enum gate above guarantees s is "on" or "off"; truthy variants are unreachable
    def resp = hubBypass("setOscillationStatus",
        [horizontalOscillationState: v, actType: "default"],
        "setOscillationStatus(H=${s})")
    if (httpOk(resp)) {
        device.sendEvent(name:"horizontalOscillation", value: s)
        logInfo "Horizontal oscillation: ${s}"
    } else {
        // BP29: device-off => one WARN (expected); any other failure => logError + record.
        reportWriteFailure("Horizontal oscillation write failed", resp, [method:"setOscillationStatus"])
    }
}

// Toggle vertical oscillation on or off (without changing range)
// BP24: NO-ON — configures a device preference; powering on is not implied.
def setVerticalOscillation(onOff){
    logDebug "setVerticalOscillation(${onOff})"
    if (!requireNonEmptyEnum(onOff, "setVerticalOscillation")) return
    String s = (onOff as String).trim().toLowerCase()
    if (!(s in ["on","off"])) { logError "setVerticalOscillation: invalid value '${s}'"; recordError("setVerticalOscillation invalid: ${s}", [method:"setOscillationStatus"]); return }
    // C3 state-change gate: suppress redundant cloud calls when value already matches attribute.
    if (device.currentValue("verticalOscillation") == s) return
    // BP24 NO-ON note: still send the command; just inform if the fan is off (setting applies on power-on).
    noteOscillationOffState()
    Integer v = (s == "on") ? 1 : 0  // strict-enum gate above guarantees s is "on" or "off"; truthy variants are unreachable
    def resp = hubBypass("setOscillationStatus",
        [verticalOscillationState: v, actType: "default"],
        "setOscillationStatus(V=${s})")
    if (httpOk(resp)) {
        device.sendEvent(name:"verticalOscillation", value: s)
        logInfo "Vertical oscillation: ${s}"
    } else {
        reportWriteFailure("Vertical oscillation write failed", resp, [method:"setOscillationStatus"])
    }
}

// Set horizontal oscillation range (0-90 angular degrees) and turn horizontal oscillation ON.
// Range payloads always include state=1 (oscillation must be on to set a range).
//
// BP18 normalization: explicit null-guard on left/right args, replacing the previous
// silent-zeroing (left as Integer) ?: 0 coercions. Null args now reject with logWarn.
// BP24: NO-ON — configures the oscillation range preference; powering the fan on is not implied.
def setHorizontalRange(left, right){
    logDebug "setHorizontalRange(left=${left}, right=${right})"
    if (!requireNotNull(left, "setHorizontalRange left")) return
    if (!requireNotNull(right, "setHorizontalRange right")) return
    // BP24 NO-ON note: still send the command; just inform if the fan is off (setting applies on power-on).
    noteOscillationOffState()
    // Clamp to device valid range 0-90 (horizontal angular max, live-verified on LPF-R432S-AUS).
    Integer l = safeIntArg(left, 0, 0, 90)
    Integer r = safeIntArg(right, 0, 0, 90)
    def resp = hubBypass("setOscillationStatus",
        [horizontalOscillationState: 1, actType: "default", left: l, right: r],
        "setOscillationStatus(H_range=${l}..${r})")
    if (httpOk(resp)) {
        device.sendEvent(name:"horizontalOscillation", value: "on")
        device.sendEvent(name:"oscillationLeft",  value: l)
        device.sendEvent(name:"oscillationRight", value: r)
        logInfo "Horizontal oscillation range: ${l}-${r}"
    } else {
        reportWriteFailure("Horizontal range write failed", resp, [method:"setOscillationStatus"])
    }
}

// Set vertical oscillation range (0-120 angular degrees) and turn vertical oscillation ON.
//
// BP18 normalization: explicit null-guard on top/bottom args, replacing the previous
// silent-zeroing coercions.
// BP24: NO-ON — configures the oscillation range preference; powering the fan on is not implied.
def setVerticalRange(top, bottom){
    logDebug "setVerticalRange(top=${top}, bottom=${bottom})"
    if (!requireNotNull(top, "setVerticalRange top")) return
    if (!requireNotNull(bottom, "setVerticalRange bottom")) return
    // BP24 NO-ON note: still send the command; just inform if the fan is off (setting applies on power-on).
    noteOscillationOffState()
    // Clamp to device valid range 0-120 (vertical angular max, live-verified on LPF-R432S-AUS).
    Integer t = safeIntArg(top, 0, 0, 120)
    Integer b = safeIntArg(bottom, 0, 0, 120)
    def resp = hubBypass("setOscillationStatus",
        [verticalOscillationState: 1, actType: "default", top: t, bottom: b],
        "setOscillationStatus(V_range=${t}..${b})")
    if (httpOk(resp)) {
        device.sendEvent(name:"verticalOscillation", value: "on")
        device.sendEvent(name:"oscillationTop",    value: t)
        device.sendEvent(name:"oscillationBottom", value: b)
        logInfo "Vertical oscillation range: ${t}-${b}"
    } else {
        reportWriteFailure("Vertical range write failed", resp, [method:"setOscillationStatus"])
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
// BP24: NO-ON — configures a device preference; powering on is not implied.
def setChildLock(onOff){
    logDebug "setChildLock(${onOff})"
    if (!requireNonEmptyEnum(onOff, "setChildLock")) return
    String s = (onOff as String).trim().toLowerCase()
    if (!(s in ["on","off"])) {
        logError "setChildLock: invalid value '${s}' -- must be on|off"
        recordError("setChildLock invalid: ${s}", [method:"setChildLock"])
        return
    }
    // C3 state-change gate: suppress redundant cloud calls when value already matches attribute.
    if (device.currentValue("childLock") == s) return
    int v = (s == "on") ? 1 : 0  // strict-enum gate above guarantees s is "on" or "off"; truthy variants are unreachable
    // [PREVIEW v2.4] iteration #1: method setChildLock + payload {childLock} (symmetric to read field)
    def resp = hubBypass("setChildLock", [childLock: v], "setChildLock(${s})")
    if (httpOk(resp)) {
        device.sendEvent(name:"childLock", value: s)
        logInfo "Child lock: ${s}"
    } else {
        reportWriteFailure("Child lock write failed: ${s}", resp, [method:"setChildLock"])
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
//   The `timerRemain` read-only attribute is populated from the getFanStatus poll
//   response (the device reports remaining seconds even though we cannot SET a timer
//   via the cloud API yet); see applyStatus emission below.

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
// BP24: NO-ON — configures a device preference; powering on is not implied.
def setSmartCleaningReminder(onOff){
    logDebug "setSmartCleaningReminder(${onOff})"
    if (!requireNonEmptyEnum(onOff, "setSmartCleaningReminder")) return
    String s = (onOff as String).trim().toLowerCase()
    if (!(s in ["on","off"])) {
        logError "setSmartCleaningReminder: invalid value '${s}'"
        recordError("setSmartCleaningReminder invalid: ${s}", [method:"setSmartCleaningReminder"])
        return
    }
    // C3 state-change gate: suppress redundant cloud calls when value already matches attribute.
    if (device.currentValue("smartCleaningReminder") == s) return
    int v = (s == "on") ? 1 : 0  // strict-enum gate above guarantees s is "on" or "off"; truthy variants are unreachable
    def resp = hubBypass("setSmartCleaningReminder", [smartCleaningReminderState: v],
                         "setSmartCleaningReminder(${s})")
    if (httpOk(resp)) {
        device.sendEvent(name:"smartCleaningReminder", value: s)
        logInfo "Smart cleaning reminder: ${s}"
    } else {
        reportWriteFailure("Smart cleaning reminder failed: ${s}", resp, [method:"setSmartCleaningReminder"])
    }
}

// ---------- Update / status ----------
// Self-fetch when called directly (no-arg).
// NOTE: this path passes resp.data into applyStatus; the parent-callback path (1-arg/2-arg
// in LevoitFanLib) passes data.result already. The peel-while-loop in applyStatus handles
// both shapes defensively, but the data-flow asymmetry means applyStatus's first peel
// iteration is doing different work depending on entry point.
// NOTE: update() 0-arg is NOT extracted to LevoitFanLib — Pedestal Fan uses getFanStatus
// while Tower Fan uses getTowerFanStatus. Each driver keeps its own 0-arg update().
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

// ---------- applyStatus ----------
def applyStatus(status){
    logDebug "applyStatus()"

    // BP16 watchdog: auto-disable debugOutput after 30 min even across hub reboots.
    // Placed here so all three update() entry points (0-arg, 1-arg, 2-arg) trigger it.
    ensureDebugWatchdog()

    seedPrefs()
    def r = peelEnvelope(status)
    // Diagnostic raw dump -- gated by debugOutput. Keep for ongoing field diagnostics.
    // Use this log line when a community member reports "device discovered but no data".
    logDebug "applyStatus raw r keys=${r?.keySet()}, values=${r}"

    // ---- Power + Fan speed + Mode (shared LevoitFanLib block) ----
    // CROSS-CHECK [mode]: reverse-map API "advancedSleep" -> user-facing "sleep".
    // See setMode() CROSS-CHECK block above for full rationale (pyvesync device_map.py
    // LPF-R432S FanModes.SLEEP:'advancedSleep' + HA cross-check finding #d).
    // Other modes (normal, turbo, eco) pass through unchanged.
    def head = applyFanCommonHead(r)
    boolean powerOn      = head.powerOn
    Integer activeSpeed  = head.activeSpeed
    String  reportedMode = head.reportedMode

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

    // ---- Mute + Display (shared LevoitFanLib block) ----
    Integer muteState = applyFanMuteDisplay(r)

    // ---- childLock ----
    // CROSS-CHECK [pyvesync VeSyncPedestalFan class / v2.4 hardware capture / iteration #1]:
    //   Read field "childLock" confirmed from live poll (device 1132, 2026-04-30). Value is 0|1 int.
    //   Write candidate #0 REFUTED: setChildLockSwitch + {childLockSwitch} returned inner -1 on
    //     device 1132 (2026-04-30). Write candidate #1 (current): setChildLock + {childLock}.
    //   See setChildLock() CROSS-CHECK block above for full refutation chain and remaining candidates.
    if (r.childLock != null) {
        device.sendEvent(name:"childLock", value: (r.childLock as Integer) == 1 ? "on" : "off")
    }

    // ---- Temperature (shared LevoitFanLib block) ----
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
    applyFanTemperature(r)

    // ---- Sleep preference (shared LevoitFanLib block) ----
    applyFanSleepPreference(r)

    // ---- Timer remain (mirrors Tower Fan) ----
    // getFanStatus returns timerRemain (seconds; 0 = no active timer).
    if (r.timerRemain != null) device.sendEvent(name:"timerRemain", value: r.timerRemain as Integer)

    // ---- Error code (shared LevoitFanLib block) ----
    applyFanErrorCode(r)

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
    if (r.timerRemain != null && (r.timerRemain as Integer) > 0) {
        parts << "Timer: ${r.timerRemain as Integer}s"
    }
    device.sendEvent(name:"info", value: parts.join("<br>"))
}

// logDebug, logError, logWarn, logInfo, logDebugOff, ensureDebugWatchdog,
// ensureSwitchOn, requireNotNull are provided by #include level99.LevoitChildBase.
//
// Lifecycle, power, toggle, update(1-arg/2-arg), cycleSpeed, setLevel, sendLevel,
// speed enum/percent helpers, doSetMuteSwitch, doSetDisplayScreenSwitch,
// hubBypass, httpOk are provided by #include level99.LevoitFan.

// ------------- END -------------
