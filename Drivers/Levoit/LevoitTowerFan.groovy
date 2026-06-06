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

// NOTE: Tower Fan ships as a preview driver -- maintainer has no Tower Fan
// hardware for live verification. Driver built from canonical pyvesync
// fixtures + HA cross-check findings (#1 temperature divide-by-10,
// #5 displayingType read-only-diagnostic, bonus #d sleep mode reverse-
// mapping). Please report any issues at:
//   https://github.com/level99/Hubitat-VeSync/issues
//
// (Note: this comment is the driver-level "preview" indicator pending
//  the Phase 4 sweep that adds [PREVIEW] prefix to definition() across
//  all 5 v2.1 drivers consistently.)

/*
 *  Levoit Tower Fan (LTF-F422S) — Hubitat driver
 *
 *  Targets:    LTF-F422S-WUS, LTF-F422S-WUSR, LTF-F422S-KEU, LTF-F422S-WJP
 *  Marketing:  Levoit Classic 42-Inch Tower Fan
 *  Reference:  pyvesync VeSyncTowerFan + LTF-F422S.yaml fixture
 *              https://github.com/webdjoe/pyvesync
 *  Project:    https://github.com/level99/Hubitat-VeSync
 *
 *  Key implementation notes:
 *    - Mode "sleep" maps to API literal "advancedSleep" (HA finding #d).
 *      setMode("sleep") sends {workMode:"advancedSleep"}; response
 *      "advancedSleep" is reverse-mapped back to emitted mode="sleep".
 *    - Temperature field is raw / 10 to get °F (HA finding #1).
 *      pyvesync reads it raw (vesyncfan.py bug); real device returns
 *      e.g. 717 = 71.7°F. This driver divides before emitting.
 *    - displayingType: pyvesync comment says "Unknown functionality".
 *      We expose the raw int 0/1 as a read-only attribute for diagnostics
 *      but intentionally omit a setDisplayingType command (HA finding #5).
 *    - Switch payload is purifier-style {powerSwitch, switchIdx}, NOT
 *      humidifier-style {enabled, id}.
 *    - setLevel uses V2-API field names {levelIdx, levelType, manualSpeedLevel}
 *      NOT the legacy Core-line names {id, type, level} (Bug Pattern #4).
 *    - setMode uses method "setTowerFanMode", NOT "setPurifierMode".
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
 *                      BP18 normalization: setTimer() seconds and action args
 *                      now use explicit requireNotNull guards matching codebase
 *                      convention (previously implicit ?: coercions).
 *    2026-04-29: v2.4  Phase 5 — captureDiagnostics + error ring-buffer via LevoitDiagnosticsLib.
 *    2026-04-26: v2.0  Community fork initial release (Dan Cox). Preview driver.
 *                      Built from canonical pyvesync VeSyncTowerFan payloads
 *                      (LTF-F422S.yaml fixture + vesyncfan.py + fan_models.py).
 *                      Capabilities: Switch, SwitchLevel, FanControl,
 *                      TemperatureMeasurement, Sensor, Actuator, Refresh.
 *                      Modes: normal, turbo, auto, sleep (advancedSleep).
 *                      Setters: setMode, setSpeed (1-12), setOscillation,
 *                      setMute, setDisplay, setTimer, cancelTimer, toggle.
 *                      Read-only attributes: displayingType, sleepPreferenceType,
 *                      timerRemain, errorCode, temperature.
 */

#include level99.LevoitDiagnostics
#include level99.LevoitChildBase
#include level99.LevoitFan

metadata {
    definition(
        name: "Levoit Tower Fan",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "[PREVIEW v2.1] Levoit Tower Fan (LTF-F422S-WUS/WUSR/KEU/WJP) — power, fan speed 1-12, modes (normal/turbo/auto/sleep), oscillation, mute, display, timer, ambient temperature; canonical pyvesync payloads",
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

        // Read-only status attributes
        attribute "mode",               "string"    // normal | turbo | auto | sleep (user-facing label)
        attribute "oscillation",        "string"    // on | off
        attribute "mute",               "string"    // on | off
        attribute "displayOn",          "string"    // on | off
        attribute "timerRemain",        "number"    // seconds remaining on active timer
        attribute "errorCode",          "number"
        // displayingType: pyvesync vesyncfan.py says "Unknown functionality" -- read-only diagnostic.
        // CROSS-CHECK: no setter; raw int exposed for contributor observation only.
        // See applyStatus() CROSS-CHECK block for full rationale (HA finding #5).
        attribute "displayingType",     "number"    // 0 | 1 -- raw, meaning unknown
        // sleepPreferenceType: nested response field, read-only
        attribute "sleepPreferenceType","string"    // e.g. "default", "advanced", "quiet"
        attribute "info",               "string"    // HTML summary for dashboard tiles

        // Commands
        command "setMode", [[name:"Mode*", type:"ENUM", constraints:["normal","turbo","auto","sleep"]]]
        // setSpeed(NUMBER) accepts raw fan levels 1-12; also aliased via FanControl capability
        command "setSpeed", [[name:"Speed*", type:"NUMBER", description:"Fan level 1-12"]]
        command "setOscillation", [[name:"On/Off*", type:"ENUM", constraints:["on","off"]]]
        command "setMute",        [[name:"On/Off*", type:"ENUM", constraints:["on","off"]]]
        command "setDisplay",     [[name:"On/Off*", type:"ENUM", constraints:["on","off"]]]
        // setSleepPreference deferred to v2.5+ — see CROSS-CHECK block near method site
        command "setTimer", [
            [name:"Seconds*", type:"NUMBER", description:"Seconds until timer fires"],
            [name:"Action",   type:"ENUM",   constraints:["on","off"], description:"Action when timer fires (default: off)"]
        ]
        command "cancelTimer"
        command "toggle"
        // NOTE: setDisplayingType is intentionally absent -- pyvesync's own comment
        // states "Unknown functionality" for displayingType. We read + expose the field
        // as a diagnostic attribute only (HA finding #5). No user-facing setter.

        attribute "diagnostics",     "string"
        // "true" | "false" — parent marks "false" after 3 self-heal attempts fail; flips back to "true" on first successful poll (BP21)
        attribute "online",          "string"
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
        if (s == "auto") { ensureSwitchOn(); setMode("auto"); return }
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
// CROSS-CHECK [pyvesync device_map.py LTF-F422S entry / HA bonus finding #d]:
//   Decision (setTowerFanMode): Tower Fan uses API method "setTowerFanMode", NOT
//     "setPurifierMode" and NOT "setFanMode" (which Pedestal Fan uses).
//   Rationale: pyvesync device_map.py LTF-F422S entry explicitly sets
//     set_mode_method='setTowerFanMode'. The ST+HB cross-check parenthetical claimed Tower
//     Fan also uses "setFanMode", but this was directly contradicted by pyvesync's registry.
//     We verified the pyvesync device_map.py entry directly.
//   Source: https://github.com/webdjoe/pyvesync/blob/master/src/pyvesync/device_map.py
//     (LTF-F422S entry, set_mode_method='setTowerFanMode').
//
// CROSS-CHECK [pyvesync device_map.py LTF-F422S entry / HA bonus finding #d]:
//   Decision (sleep reverse-mapping): user-facing "sleep" maps to/from API literal
//     "advancedSleep" bidirectionally.
//   Rationale: pyvesync device_map.py LTF-F422S entry: FanModes.SLEEP: 'advancedSleep'.
//     The API requires the literal "advancedSleep"; users expect the simpler "sleep" label.
//     This is the same pattern as V201S "autoPro" <-> "auto". HA bonus finding #d
//     independently confirmed this mapping.
//   Source: https://github.com/webdjoe/pyvesync/blob/master/src/pyvesync/device_map.py
//     (LTF-F422S FanModes.SLEEP: 'advancedSleep'); HA vesync cross-check bonus finding #d.
//   If contradicted in the future: a community report showing "advancedSleep" is rejected
//     and "sleep" is the correct literal would mean removing the reverse-mapping and
//     sending "sleep" directly. No such report yet — current behavior follows pyvesync.
// BP24: SHOULD-ON — asking an off fan to change mode auto-turns it on (matches speed/level
//   setters). ensureSwitchOn() runs AFTER validation so invalid input cannot wake an off device.
def setMode(mode){
    logDebug "setMode(${mode})"
    if (!requireNonEmptyEnum(mode, "setMode")) return
    String m = (mode as String).trim().toLowerCase()
    if (!(m in ["normal","turbo","auto","sleep"])) {
        logError "setMode: invalid mode '${m}' -- must be normal|turbo|auto|sleep"
        recordError("setMode: invalid mode '${m}'", [method:"setTowerFanMode"])
        return
    }
    ensureSwitchOn()
    // Map user-facing "sleep" to API "advancedSleep" (HA finding #d + pyvesync device_map.py)
    String apiMode = (m == "sleep") ? "advancedSleep" : m
    def resp = hubBypass("setTowerFanMode", [workMode: apiMode], "setTowerFanMode(${apiMode})")
    if (httpOk(resp)) {
        state.mode = m   // store user-facing label, not API literal
        device.sendEvent(name:"mode", value: m)
        logInfo "Mode: ${m}"
    } else {
        logError "Mode write failed: ${m}"; recordError("Mode write failed: ${m}", [method:"setTowerFanMode"])
    }
}

// ---------- Feature setters ----------
// Public delegators for methods whose bodies live in LevoitFanLib.
// BP24: NO-ON — configures a device preference; powering on is not implied.
def setMute(o)    { doSetMuteSwitch(o) }
// BP24: NO-ON — configures a device preference; powering on is not implied.
def setDisplay(o) { doSetDisplayScreenSwitch(o) }

// BP24: NO-ON — configures a device preference; powering on is not implied.
def setOscillation(onOff){
    logDebug "setOscillation(${onOff})"
    if (!requireNonEmptyEnum(onOff, "setOscillation")) return
    String s = (onOff as String).trim().toLowerCase()
    if (!(s in ["on","off"])) { logError "setOscillation: invalid value '${s}'"; recordError("setOscillation invalid: ${s}", [method:"setOscillationSwitch"]); return }
    // C3 state-change gate: suppress redundant cloud calls when value already matches attribute.
    if (device.currentValue("oscillation") == s) return
    // BP24 NO-ON note: still send the command; just inform if the fan is off (setting applies on power-on).
    noteOscillationOffState()
    int v = (s == "on") ? 1 : 0  // strict-enum gate above guarantees s is "on" or "off"; truthy variants are unreachable
    def resp = hubBypass("setOscillationSwitch", [oscillationSwitch: v], "setOscillationSwitch(${s})")
    if (httpOk(resp)) {
        device.sendEvent(name:"oscillation", value: s)
        logInfo "Oscillation: ${s}"
    } else {
        // BP29: device-off => one WARN (expected); any other failure => logError + record.
        reportWriteFailure("Oscillation write failed", resp, [method:"setOscillationSwitch"])
    }
}

// CROSS-CHECK [pyvesync VeSyncTowerFan._set_fan_state + device_map.py LTF-F422S sleep_preferences]:
//   setSleepPreference was attempted in v2.4 but deferred to v2.5+ after Pedestal Fan live
//   verification (device 1132, 2026-05-01) found both flat {sleepPreferenceType} and nested
//   {sleepPreference: {...}} payloads rejected with inner 11000000. Both fan families share the
//   same sleepPreference API shape — applying the same deferral. The sleepPreferenceType
//   READ-ONLY attribute stays declared (populated on poll). Resolution path: mitmproxy capture.

// ---------- Timer ----------
// Timer shape for Tower Fan: {action: 'on'|'off', total: <seconds>}
// NOT the V201S addTimerV2 shape {enabled, startAct, tmgEvt}
// action: what the device does when the timer fires
// Default action "off" (turn device off after timer expires)
//
// BP18 normalization: explicit requireNotNull guards on seconds and action args,
// replacing the previous implicit ?: coercions. Matches the codebase convention
// used by all other fan/humidifier command methods.
// BP24: NO-ON — schedules a future power action; powering on now is not implied.
def setTimer(seconds, action="off"){
    if (!requireNotNull(seconds, "setTimer")) return
    int secs = safeIntArg(seconds, 0)
    if (secs <= 0) { cancelTimer(); return }
    // action defaults to "off" in the Groovy signature; only null-guard when explicitly null
    String act = (action != null) ? (action as String).trim().toLowerCase() : "off"
    if (!(act in ["on","off"])) { logError "setTimer: invalid action '${act}'"; recordError("setTimer: invalid action '${act}'", [method:"setTimer"]); return }
    logDebug "setTimer(${secs}s, action=${act})"
    def resp = hubBypass("setTimer", [action: act, total: secs], "setTimer(${secs}s,${act})")
    if (httpOk(resp)) {
        // Capture timer ID from response so cancelTimer can reference it
        def tid = resp?.data?.result?.result?.id ?: resp?.data?.result?.id
        if (tid != null) {
            state.timerId = tid
        } else {
            logDebug "setTimer: response did not include timer id -- cancelTimer will use state.timerId if known"
        }
        logInfo "Timer set: ${act} in ${secs}s (id=${state.timerId})"
    } else {
        reportWriteFailure("Timer set failed", resp, [method:"setTimer"])
    }
}

def cancelTimer(){
    logDebug "cancelTimer()"
    if (!state.timerId) {
        logDebug "cancelTimer: no active timer id in state -- no-op"
        return
    }
    def resp = hubBypass("clearTimer", [id: state.timerId], "clearTimer(id=${state.timerId})")
    if (httpOk(resp)) {
        state.remove("timerId")
        logInfo "Timer cancelled"
    } else {
        reportWriteFailure("Timer cancel failed", resp, [method:"clearTimer"])
    }
}

// ---------- Update / status ----------
// Self-fetch when called directly (no-arg).
// NOTE: this path passes resp.data into applyStatus; the parent-callback path (1-arg/2-arg
// in LevoitFanLib) passes data.result already. The peel-while-loop in applyStatus handles
// both shapes defensively, but the data-flow asymmetry means applyStatus's first peel
// iteration is doing different work depending on entry point.
// NOTE: update() 0-arg is NOT extracted to LevoitFanLib — Tower Fan uses getTowerFanStatus
// while Pedestal Fan uses getFanStatus. Each driver keeps its own 0-arg update().
def update(){
    logDebug "update() self-fetch"
    def resp = hubBypass("getTowerFanStatus", [:], "update")
    if (httpOk(resp)) {
        def status = resp?.data
        if (!status?.result) { logError "No status returned from getTowerFanStatus"; recordError("No status returned from getTowerFanStatus", [method:"getTowerFanStatus"]) }
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
    // LTF-F422S FanModes.SLEEP:'advancedSleep' + HA bonus finding #d).
    def head = applyFanCommonHead(r)
    boolean powerOn      = head.powerOn
    Integer activeSpeed  = head.activeSpeed
    String  reportedMode = head.reportedMode

    // ---- Oscillation (single-axis; Tower-specific) ----
    // oscillationState = actual hardware state; oscillationSwitch = configured setting.
    // Prefer state (actual) for reporting.
    Integer oscState = (r.oscillationState != null) ? (r.oscillationState as Integer) : (r.oscillationSwitch as Integer)
    device.sendEvent(name:"oscillation", value: oscState == 1 ? "on" : "off")

    // ---- Mute + Display (shared LevoitFanLib block) ----
    Integer muteState = applyFanMuteDisplay(r)

    // ---- Temperature (shared LevoitFanLib block) ----
    // CROSS-CHECK [HA fixture tests/components/vesync/fixtures/fan-detail.json line 19 /
    //   pyvesync vesyncfan.py asymmetry]:
    //   Decision: divide temperature by 10 before emitting (raw / 10 = degrees F).
    //   Rationale: the HA cross-check fixture contains a real LTF-F422S-KEU device capture
    //     with temperature:717 at idle -- 71.7°F is a plausible room temperature; 717°F is
    //     impossible. The ST+HB cross-check report claimed pyvesync vesyncfan.py reading raw
    //     was authoritative, but pyvesync vesyncfan.py has a KNOWN ASYMMETRY: VeSyncTowerFan
    //     reads temperature raw while VeSyncPedestalFan divides by 10 (vesyncfan.py:314).
    //     The asymmetry between two closely related devices strongly suggests the Tower Fan
    //     path is a pyvesync bug, not a genuine hardware difference. We trust the empirical
    //     real-device fixture over the suspect source code.
    //   Source: HA tests/components/vesync/fixtures/fan-detail.json line 19 (real-device
    //     LTF-F422S-KEU capture, temperature:717); pyvesync vesyncfan.py VeSyncTowerFan vs
    //     VeSyncPedestalFan temperature handling asymmetry (cross-check finding #1, 2026-04-26).
    //   Refutation: community user reports temperature:72 (already degrees F without /10) -->
    //     remove the divide; emit raw as degrees F. Also check if pyvesync has been patched.
    applyFanTemperature(r)

    // ---- displayingType ----
    // CROSS-CHECK [HA cross-check finding #5 / pyvesync vesyncfan.py:166-176]:
    //   Decision: expose displayingType as read-only attribute (raw int 0/1); no setter.
    //   Rationale: pyvesync's own inline comment at vesyncfan.py:166-176 says "Unknown
    //     functionality" for displayingType. The HA core doesn't expose it as a controllable
    //     feature. Community speculation (HA feature-request thread) suggests it might toggle
    //     what the LED display shows (fan level vs temperature), but no one has empirically
    //     confirmed what values 0 and 1 mean. Exposing as a read-only diagnostic attribute
    //     lets contributors observe it without committing to a semantic we might get wrong.
    //   Source: https://github.com/webdjoe/pyvesync/blob/master/src/pyvesync/devices/
    //     vesyncfan.py lines 166-176 ("Unknown functionality" comment); HA vesync integration
    //     cross-check finding #5 (2026-04-26).
    //   Refutation: community user empirically determines what displayingType 0 vs 1 controls
    //     --> add setDisplayingType command with a friendly label describing the behavior.
    if (r.displayingType != null) {
        device.sendEvent(name:"displayingType", value: r.displayingType as Integer)
    }

    // ---- Sleep preference (shared LevoitFanLib block) ----
    applyFanSleepPreference(r)

    // ---- Timer remain (Tower-specific) ----
    if (r.timerRemain != null) device.sendEvent(name:"timerRemain", value: r.timerRemain as Integer)

    // ---- Error code (shared LevoitFanLib block) ----
    applyFanErrorCode(r)

    // ---- Info HTML (use local variables -- avoids device.currentValue race; BP#7) ----
    def parts = []
    parts << "Mode: ${reportedMode}"
    parts << "Speed: ${powerOn ? levelToFanControlEnum(activeSpeed) + ' (L' + activeSpeed + ')' : 'off'}"
    parts << "Oscillation: ${oscState == 1 ? 'on' : 'off'}"
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
