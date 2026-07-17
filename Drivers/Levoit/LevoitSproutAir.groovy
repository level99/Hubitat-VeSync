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

/*
 *  Levoit Sprout Air Purifier (LAP-B851S-WUS / LAP-B851S-WEU / LAP-B851S-AEUR /
 *                              LAP-B851S-AUS / LAP-B851S-WNA / LAP-BAY-MAX01S)
 *
 *  Targets:    US:  LAP-B851S-WUS, LAP-B851S-WNA
 *              EU:  LAP-B851S-WEU, LAP-B851S-AEUR
 *              AUS: LAP-B851S-AUS
 *              BAY: LAP-BAY-MAX01S
 *              All six model codes route to this single driver.
 *  Marketing:  Levoit Sprout 3-in-1 Air Purifier / Sprout Series
 *  pyvesync:   VeSyncAirSprout class (inherits VeSyncAirBaseV2 -> VeSyncAirBypass ->
 *              BypassV2Mixin + VeSyncPurifier)
 *  Reference:  pyvesync device_map.py + devices/vesyncpurifier.py VeSyncAirSprout class
 *              https://github.com/webdjoe/pyvesync
 *
 *  CROSS-CHECK [pyvesync device_map.py / VeSyncAirSprout class]:
 *    How the Sprout Air Purifier differs from ALL sibling purifier classes:
 *
 *    vs VeSyncAirBypass (Core 200S/300S/400S/600S — old API):
 *      - Switch: {powerSwitch: int, switchIdx: 0} NOT {switch: 'on'/'off', id: 0}
 *      - Mode: setPurifierMode {workMode: str} NOT setPurifierMode {mode: str}
 *      - Fan speed: setLevel {levelIdx:0, manualSpeedLevel:N, levelType:'wind'}
 *        NOT setLevel {level:N, id:0, type:'wind'}
 *      - Sprout fan levels: 1-3 only (device_map.py fan_levels=[1,2,3])
 *        (Core 200S: 1-3; Core 300S: 1-3; Core 400S: 1-4; Core 600S: 1-4)
 *      - Nightlight: setNightLight {night_light: 'on'/'off'/'dim'} (inherited from
 *        VeSyncAirBypass via VeSyncAirBaseV2; uses string enum NOT int toggle)
 *        Core 200S light uses same endpoint but the Core 200S exposes it as a
 *        separate child device; Sprout exposes it as an attribute on the same device
 *      - Sprout has AQ sensors (AIR_QUALITY feature flag); Core 200S does not
 *
 *    vs VeSyncAirBaseV2 (Vital 200S LAP-V201S, Vital 100S LAP-V102S):
 *      - Vital line: no NIGHTLIGHT feature; Sprout: NIGHTLIGHT in feature_map
 *      - Vital 200S: manualSpeedLevel range 1-4; Sprout: 1-3
 *      - Vital 200S: AQ sensors + PM2.5 + filter life + auto-preference + child lock +
 *        light-detection + sleep preference + timer; Sprout is simpler (no timer,
 *        no light detection, no sleep preference, but adds nightlight)
 *      - Both use {workMode: str} with setPurifierMode + setLevel with manualSpeedLevel
 *
 *    vs VeSyncAirSprout (this driver):
 *      Modes: auto, manual, sleep (workMode wire values: 'auto', 'manual', 'sleep')
 *      Response fields: powerSwitch, workMode, fanSpeedLevel (255 = off -> 0),
 *        manualSpeedLevel, childLockSwitch, AQLevel, PM25, PM1, PM10, AQI,
 *        screenSwitch, screenState, humidity, temperature (div 10), VOC, CO2,
 *        nightlight {nightLightSwitch: bool/int, brightness: int}
 *      Note: manual mode requires setLevel, NOT setPurifierMode (pyvesync
 *        VeSyncAirBaseV2.set_mode: if mode==MANUAL, delegates to set_fan_speed(1))
 *
 *    Nightlight:
 *      All Sprout Air variants have nightlight (AIR_QUALITY + NIGHTLIGHT feature flags,
 *      no regional gating in device_map.py). setNightLight uses string enum:
 *      'on', 'off', 'dim'. Driver exposes setNightlightMode("on"/"off"/"dim")
 *      and the response nightLightSwitch/brightness for passive read.
 *      No runtime gate needed — all variants support it.
 *
 *  Project:    https://github.com/level99/Hubitat-VeSync
 *
 *  History:
 *    2026-06-28: v2.10 Added standard FanControl (setSpeed enum + cycleSpeed) + SwitchLevel
 *                      (setLevel) capabilities so fan speed/level drive from dashboard tiles, Rule
 *                      Machine, and voice (Alexa/Google/HomeKit). setSpeed/setLevel/cycleSpeed
 *                      resolve to a 1-3 speed and route through the existing setFanSpeed cloud-write
 *                      path (single dedup slot; sleep/auto delegate to setMode). Existing
 *                      setFanSpeed(1-3) + fanSpeed attribute preserved (BP9). Emits
 *                      speed/supportedFanSpeeds/level (RULE47); off edge clears speed/level tiles.
 *    2026-04-29: v2.4  Phase 5 — captureDiagnostics + error ring-buffer via LevoitDiagnosticsLib.
 *    2026-04-28: v2.2.1  Initial release. All 6 LAP-B851S model codes + LAP-BAY-MAX01S
 *                        in a single driver. pyvesync VeSyncAirSprout class.
 *                        V2-style payload conventions (VeSyncAirBaseV2 inheritance).
 *                        Ships as [PREVIEW] — no maintainer hardware. Built from
 *                        pyvesync VeSyncAirSprout + VeSyncAirBaseV2 class implementation
 *                        and device_map.py. See CROSS-CHECK above.
 */

#include level99.LevoitDiagnostics
#include level99.LevoitChildBase

metadata {
    definition(
        singleThreaded: true,  // BP30 Layer 1: serialize command + async-callback execution (storm hardening)
        name: "Levoit Sprout Air Purifier",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "[PREVIEW v2.3] Levoit Sprout Air Purifier (LAP-B851S-WUS/-WEU/-AEUR/-AUS/-WNA, LAP-BAY-MAX01S) — fan 1-3, auto/sleep/manual modes, AQ sensors (AQLevel/PM2.5/PM1/PM10/AQI/VOC/CO2), child lock, display, nightlight (on/off/dim). pyvesync VeSyncAirSprout class (VeSyncAirBaseV2). V2-style payloads. No timer.",
        version: "2.10",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
    {
        capability "Switch"
        capability "FanControl"                     // provides speed + supportedFanSpeeds attrs + setSpeed command (emitted in applyStatus/initialize)
        capability "SwitchLevel"                    // provides level attr + setLevel command (emitted in applyStatus)
        capability "AirQuality"                     // provides the standard airQuality attribute (emitted in applyStatus)
        capability "RelativeHumidityMeasurement"    // humidity attribute
        capability "TemperatureMeasurement"         // temperature attribute
        capability "Sensor"
        capability "Actuator"
        capability "Refresh"

        attribute "mode",             "string"      // auto | sleep | manual
        attribute "fanSpeed",         "number"      // 0-3 (0 = inactive when off)
        attribute "airQuality",       "number"      // standard AirQuality cap: US-AQI 0-500 (from PM2.5)
        attribute "airQualityIndex",  "number"      // 1-4 categorical (Levoit AQ level)
        attribute "pm25",             "number"      // PM2.5 µg/m³
        attribute "pm1",              "number"      // PM1.0 µg/m³
        attribute "pm10",             "number"      // PM10 µg/m³
        attribute "aqi",              "number"      // AQI percent (Levoit's own index)
        attribute "voc",              "number"      // VOC (raw sensor reading)
        attribute "co2",              "number"      // CO2 ppm
        attribute "displayOn",        "string"      // on | off
        attribute "childLock",        "string"      // on | off
        attribute "nightlightOn",     "string"      // on | off | dim
        attribute "nightlightBrightness", "number"  // 0-100
        attribute "info",             "string"      // HTML tile summary

        command "setMode",            [[name:"Mode*",      type:"ENUM",   constraints:["auto","sleep","manual"]]]
        command "setFanSpeed",        [[name:"Speed*",     type:"NUMBER", description:"1-3"]]
        // FanControl standard command — ENUM override so dashboards show a picker. low/medium/high
        // map to fan levels 1/2/3; off/on map to off()/on(); sleep/auto delegate to setMode.
        command "setSpeed",           [[name:"Speed*",     type:"ENUM",   constraints:["off","low","medium","high","sleep","auto","on"]]]
        command "setDisplay",         [[name:"On/Off*",    type:"ENUM",   constraints:["on","off"]]]
        command "setChildLock",       [[name:"On/Off*",    type:"ENUM",   constraints:["on","off"]]]
        command "setNightlightMode",  [[name:"Mode*",      type:"ENUM",   constraints:["on","off","dim"]]]
        command "toggle"

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
    runIn(3, "refresh")
    if (settings?.debugOutput) {
        runIn(1800, "logDebugOff")
        state.debugEnabledAt = now()
    } else {
        state.remove("debugEnabledAt")
    }
}
def uninstalled(){ logDebug "Uninstalled" }
def initialize(){
    logDebug "Initializing"
    // FanControl: publish the static speed enum once so dashboard fan-tiles and integrations
    // populate their speed picker. Sprout Air hardware exposes 3 manual levels (low/medium/high);
    // sleep/auto delegate to setMode. on/off included per the standard FanControl enum.
    device.sendEvent(name:"supportedFanSpeeds",
        value: groovy.json.JsonOutput.toJson(["off","low","medium","high","sleep","auto","on"]))
}

// ---------- Power ----------
// VeSyncAirBaseV2 toggle_switch: {powerSwitch: int, switchIdx: 0}
// Different from VeSyncAirBypass (Core line): {switch: 'on'/'off', id: 0}
def on(){
    logDebug "on()"
    // Re-entrance guard: guards against re-entry when a speed/mode setter calls ensureSwitchOn()
    // during an in-flight turn-on. The recursion vector is setFanSpeed -> ensureSwitchOn -> on;
    // on() itself only issues setSwitch, so without this flag a setter that auto-ons would re-enter
    // on() before the first call completes. state.turningOn matches humidifier drivers (e.g. Sup6000S).
    if (state.turningOn) { logDebug "Already turning on, skipping re-entrant call"; return }
    // BP30: async-window storm guard — collapse a burst of overlapping on() commands into ONE
    // effective power sequence. Returns false while a power-on is already in flight.
    if (!beginPowerOnWindow()) { logDebug "Power-on already in flight (BP30 storm guard); skipping redundant burst"; return }
    state.turningOn = true
    try {
        def resp = hubBypass("setSwitch", [powerSwitch: 1, switchIdx: 0], "setSwitch(powerSwitch=1)")
        // on() stays a PURE Switch op (emits only switch:"on") — it is the COMMON auto-on path
        // (ensureSwitchOn()->on() from setFanSpeed/setLevel/cycleSpeed). Emitting a speed here would
        // fire a spurious intermediate speed (stale lastFanSpeed) on every off->fan-write before the
        // real target lands. The optimistic speed mirror is emitted by the caller (setSpeed case "on").
        if (httpOk(resp)) { state.lastSwitchSet = "on"; device.sendEvent(name:"switch", value:"on"); logInfo "Power on" }
        else { clearPowerOnWindow(); reportWriteError("Power on failed", [method:"setSwitch"]) }
    } finally {
        state.remove('turningOn')
    }
}

def off(){
    logDebug "off()"
    // Defensive symmetry with on()'s guard; no active re-entrance vector into off() today.
    if (state.turningOff) { logDebug "Already turning off, skipping re-entrant call"; return }
    state.turningOff = true
    try {
        // BP30: cancel any open power-on window so a deliberate off -> on fires a fresh sequence,
        // and clear the fanSpeed dedup slot so a low -> off -> low re-establish write within the
        // 2s window is not suppressed (matches the release's failure-path-clear theme, RULE50).
        clearPowerOnWindow()
        clearDuplicateWrite("fanSpeed")
        def resp = hubBypass("setSwitch", [powerSwitch: 0, switchIdx: 0], "setSwitch(powerSwitch=0)")
        if (httpOk(resp)) {
            state.lastSwitchSet = "off"
            device.sendEvent(name:"switch", value:"off")
            // BP6: clear the active FanControl/SwitchLevel mirrors on the off edge so the dashboard
            // fan/dimmer tiles read off/0 immediately (not the retained level) ahead of the next poll.
            // Centralized here so EVERY off entry point is covered: direct off(), setLevel(0),
            // setSpeed("off"), and toggle().
            device.sendEvent(name:"speed", value:"off")
            device.sendEvent(name:"level", value: 0)
            logInfo "Power off"
        }
        else { reportWriteError("Power off failed", [method:"setSwitch"]) }
    } finally {
        state.remove('turningOff')
    }
}

def toggle(){
    logDebug "toggle()"
    String current = state.lastSwitchSet ?: device.currentValue("switch")
    current == "on" ? off() : on()
}

// ---------- Mode ----------
// VeSyncAirBaseV2 set_mode: {workMode: mode} via setPurifierMode.
// workMode wire values: 'auto', 'sleep', 'manual' (PurifierModes constants).
// CRITICAL: manual mode is established via setLevel (fan speed), NOT setPurifierMode.
// Sending setPurifierMode {workMode:'manual'} returns inner code -1 on this class.
// Per pyvesync VeSyncAirBaseV2.set_mode(): when mode==MANUAL, delegates to set_fan_speed(1).
// This driver mirrors that: setMode("manual") calls setFanSpeed(1) instead.
// BP24: SHOULD-ON — asking an off device to change mode auto-turns it on (matches speed/level
//   setters; pyvesync VeSyncAirBaseV2.set_mode has no power gate and sets device ON on success).
//   ensureSwitchOn() runs AFTER validation so invalid input cannot wake an off device. The outer
//   ensureSwitchOn() here is the load-bearing auto-on guard for BOTH paths (manual and mode); the
//   manual path's later setFanSpeed call also calls ensureSwitchOn, but by then the device is
//   already on so that inner call is a no-op — it is not what powers the device on.
def setMode(mode){
    logDebug "setMode(${mode})"
    if (!requireNonEmptyEnum(mode, "setMode")) return
    String m = (mode as String).trim().toLowerCase()
    if (!(m in ["auto","sleep","manual"])) { logError "Invalid mode: ${m} -- must be: auto, sleep, manual"; recordError("Invalid mode: ${m}", [method:"setPurifierMode"]); return }
    ensureSwitchOn()
    // BP30 Layer 3 (A1): dedup BEFORE the manual delegation so the "mode" slot reflects the NEW
    // effective mode even when manual delegates to setFanSpeed — otherwise auto->manual->auto within
    // the window would falsely suppress the 3rd write (slot stale at "auto"). The turningOn/
    // powerOnPending guard keeps an in-flight power-on's establishment write from being suppressed.
    if (!state.turningOn && !state.powerOnPending && isDuplicateWrite("mode", m)) {
        logDebug "setMode: identical mode write within dedup window (storm duplicate); skipping"
        return false
    }
    if (m == "manual") {
        // Manual established by setting fan speed (same as pyvesync VeSyncAirBaseV2.set_mode(MANUAL)).
        // A1-delegation: the "mode" slot is already recorded; if the delegated setFanSpeed FAILS,
        // clear it so a same-value setMode("manual") retry is not falsely suppressed.
        // B1 fail-safe: the delegated setter's false can mean a genuine failure OR its own dedup-suppress; clearing the outer slot on either is harmless (inner write stays deduped -> no extra cloud write).
        if (!setFanSpeed(state.lastFanSpeed ?: 1)) clearDuplicateWrite("mode")
        return
    }
    def resp = hubBypass("setPurifierMode", [workMode: m], "setPurifierMode(${m})")
    if (httpOk(resp)) {
        state.mode = m
        device.sendEvent(name:"mode", value: m)
        logInfo "Mode: ${m}"
    } else {
        clearDuplicateWrite("mode")   // B1: failed write must not suppress an immediate retry
        reportWriteError("Mode write failed: ${m}", [method:"setPurifierMode"])
    }
}

// ---------- Fan speed ----------
// VeSyncAirBaseV2 set_fan_speed: setLevel {levelIdx:0, manualSpeedLevel:N, levelType:'wind'}
// Range: 1-3 (device_map.py fan_levels=[1,2,3]).
// Setting a fan speed implicitly sets mode to manual.
def setFanSpeed(speed){
    logDebug "setFanSpeed(${speed})"
    // BP18: null-guard — Rule Machine blank slots pass null; silent coercion to speed 1 is wrong.
    if (!requireNotNull(speed, "setFanSpeed")) return
    Integer spd = safeIntArg(speed, 1, 1, 3)
    // BP24-B: auto-on from off-state. on() re-entrance guard (state.turningOn) prevents recursion
    // when setMode("manual") delegates here and on() calls setFanSpeed internally.
    ensureSwitchOn()
    // BP30 Layer 3: drop an identical fanSpeed write issued within the storm dedup window. An
    // out-of-window re-request always fires, so a drifted cloud state stays correctable from
    // Hubitat (see isDuplicateWrite). The turningOn/powerOnPending guard keeps an in-flight
    // power-on's establishment write from being suppressed. Layers 1+2 are the primary storm fix.
    if (!state.turningOn && !state.powerOnPending && isDuplicateWrite("fanSpeed", spd)) {
        logDebug "setFanSpeed: identical fanSpeed write within dedup window (storm duplicate); skipping"
        return false
    }
    def resp = hubBypass("setLevel", [levelIdx: 0, manualSpeedLevel: spd, levelType: "wind"], "setLevel(wind,${spd})")
    boolean ok = httpOk(resp)
    if (ok) {
        state.lastFanSpeed = spd
        state.mode = "manual"
        device.sendEvent(name:"fanSpeed", value: spd)
        device.sendEvent(name:"mode",     value: "manual")
        // Optimistic update of the standard FanControl/SwitchLevel attrs so dashboard/voice tiles
        // reflect the command immediately (not only after the next parent poll). Uses the SAME
        // fan-level -> speed/level mapping as applyStatus, so there is no flip on reconcile. Covers
        // every fan-level write path: setFanSpeed, setSpeed(low/medium/high), and setLevel.
        device.sendEvent(name:"speed", value: speedNameFor(spd))
        device.sendEvent(name:"level", value: speedToLevel(spd))
        logInfo "Fan speed: ${spd}, mode: manual"
    } else {
        clearDuplicateWrite("fanSpeed")   // B1: failed write must not suppress an immediate retry
        reportWriteError("Fan speed write failed: ${spd}", [method:"setLevel"])
    }
    return ok   // A1-delegation: setMode("manual") observes this to clear its "mode" slot on failure
}

// ---------- FanControl: cycleSpeed ----------
// Standard Hubitat FanControl command (required alongside setSpeed; without it a dashboard/RM
// cycleSpeed throws MissingMethodException). Advances the manual fan level 1 -> 2 -> 3 -> 1 and
// routes through the SINGLE shared setFanSpeed cloud-write path, so the optimistic speed/level
// emit + BP30 dedup come for free. null/0 last speed -> 1.
// BP24-A: SHOULD-ON — FanControl convention; cycling speed on an off device turns it on first.
// The explicit ensureSwitchOn() matches the sibling cycleSpeed convention (Vital/Fan libs);
// setFanSpeed also calls it, but by then the device is already on so that inner call is a no-op.
def cycleSpeed(){
    logDebug "cycleSpeed()"
    ensureSwitchOn()
    Integer cur = (state.lastFanSpeed ?: 0) as Integer
    Integer next = (cur >= 3) ? 1 : (cur + 1)
    setFanSpeed(next)
}

// ---------- FanControl: setSpeed (enum) ----------
// Standard Hubitat FanControl command. Resolves the enum to Sprout Air's 3 manual speed levels
// (low/medium/high -> 1/2/3) and routes through the SINGLE shared setFanSpeed cloud-write path,
// which owns the "fanSpeed" BP30 dedup slot + failure-clear. No NEW dedup slot is introduced here,
// so RULE49/RULE50 stay satisfied and a storm of identical setSpeed calls coalesces via setFanSpeed.
// off/on -> off()/on(); sleep/auto delegate to setMode (workMode values, not fan-speed levels per
// pyvesync device_map.py — Sprout Air has no turbo mode).
// BP24: an invalid value is rejected BEFORE any auto-on/delegation (validate-before-on) — so a
// malformed speed never wakes an off device or drifts to a real speed.
def setSpeed(speed){
    logDebug "setSpeed(${speed})"
    if (!requireNonEmptyEnum(speed, "setSpeed")) return
    String s = (speed as String).trim().toLowerCase()
    // SUCCESS-GATED paths: low/medium/high route through setFanSpeed (emits speed+level in its
    // `if (ok)` branch); off routes through off() (emits speed:"off"+level:0 in its httpOk branch).
    // A failed cloud write on those paths never reports a state the device isn't in.
    // UNCONDITIONAL paths (deferred to v2.11, task #10): on/sleep/auto emit their optimistic `speed`
    // right after delegating. on() is the COMMON auto-on path (ensureSwitchOn()->on()), so emitting a
    // speed inside on()'s httpOk branch would fire a spurious intermediate speed on every off->fan
    // auto-on; and setMode() (sleep/auto) returns no reliable success boolean. Clean gating for all
    // three needs an on()/setMode() success-bool refactor that intersects the BP30 storm/dedup guards.
    // (V2 Air deliberately reports speed:"sleep" for sleep mode — "sleep" is a supportedFanSpeeds
    // value — diverging from Vital's speed:"on" convention.)
    switch (s) {
        case "off":    off();            return   // off() emits speed:"off"+level:0 in its httpOk branch (success-gated)
        case "on":     on();             device.sendEvent(name:"speed", value: state.lastFanSpeed ? speedNameFor(state.lastFanSpeed) : "on"); return   // unconditional (deferred — on() is the common auto-on path)
        case "low":    setFanSpeed(1);   return
        case "medium": setFanSpeed(2);   return
        case "high":   setFanSpeed(3);   return
        case "sleep":  setMode("sleep"); device.sendEvent(name:"speed", value:"sleep"); return   // unconditional (deferred — setMode has no success bool); "sleep" divergence noted above
        case "auto":   setMode("auto");  device.sendEvent(name:"speed", value:"auto");  return   // unconditional (deferred — setMode has no success bool)
        default:
            logWarn "setSpeed: invalid speed '${s}' -- must be one of: off, low, medium, high, sleep, auto, on; ignoring"
            return
    }
}

// ---------- SwitchLevel: setLevel ----------
// Standard Hubitat SwitchLevel command. Maps 0-100 to Sprout Air's 3 fan-speed bands and routes
// through the SINGLE shared setFanSpeed cloud-write path (no new dedup slot — see setSpeed).
// BP28: parseLevelOrNull distinguishes an explicit 0 (-> off) from non-numeric garbage (-> ignore,
// device unchanged). setLevel(N>0) auto-ons via setFanSpeed's ensureSwitchOn (BP23 SwitchLevel convention).
def setLevel(val){
    logDebug "setLevel(${val})"
    Integer pct = parseLevelOrNull(val)
    if (pct == null) { logWarn "setLevel: ignoring non-numeric value '${val}'"; return }
    pct = Math.max(0, Math.min(100, pct))
    if (pct == 0) { off(); return }
    Integer lvl = (pct <= 33) ? 1 : (pct <= 66 ? 2 : 3)
    // No pre-emit of `level` here: setFanSpeed emits speed + level (at the band ceiling) in its
    // success branch, so `level` updates only on a CONFIRMED write and settles directly on the
    // banded value — no 50->66 visible flip, and no level reported when the cloud write fails.
    setFanSpeed(lvl)
}

// 2-arg SwitchLevel overload (BP1) — VeSync has no hardware fade; the duration arg is ignored.
def setLevel(val, duration){ setLevel(val) }

// FanControl/SwitchLevel display helpers — map the 1-3 fan level to the standard `speed` enum
// name and a representative 0-100 `level` band (band ceilings, so setLevel(33/66/100) round-trip).
private String speedNameFor(lvl){
    switch (lvl as Integer) { case 1: return "low"; case 2: return "medium"; case 3: return "high"; default: return "low" }
}
private Integer speedToLevel(lvl){
    switch (lvl as Integer) { case 1: return 33; case 2: return 66; case 3: return 100; default: return 33 }
}

// ---------- Display ----------
// VeSyncAirBaseV2 toggle_display: {screenSwitch: int}
// BP24: NO-ON — configures a device preference; powering on is not implied.
def setDisplay(onOff){
    logDebug "setDisplay(${onOff})"
    if (!requireNonEmptyEnum(onOff, "setDisplay")) return
    // BP25: normalize to lowercase, then derive a canonical on/off string from the truthy test.
    // sendEvent always emits "on" or "off" — never the raw input ("true", "1", "yes").
    // The C3 gate compares canonical vs canonical so truthy-variant input cannot defeat it.
    String v = (onOff as String).trim().toLowerCase()
    String canon = canonOnOff(v)
    // C3 state-change gate: suppress redundant cloud calls when value already matches attribute.
    if (device.currentValue("displayOn") == canon) return
    Integer sw = (canon == "on") ? 1 : 0
    def resp = hubBypass("setDisplay", [screenSwitch: sw], "setDisplay(${canon})")
    if (httpOk(resp)) {
        device.sendEvent(name:"displayOn", value: canon)
        logInfo "Display: ${canon}"
    } else {
        // BP29: device-off => one WARN (expected); any other failure => logError + record.
        reportWriteFailure("Display write failed", resp, [method:"setDisplay"])
    }
}

// ---------- Child lock ----------
// VeSyncAirBaseV2 toggle_child_lock: {childLockSwitch: int}
// BP24: NO-ON — configures a device preference; powering on is not implied.
def setChildLock(onOff){
    logDebug "setChildLock(${onOff})"
    if (!requireNonEmptyEnum(onOff, "setChildLock")) return
    // BP25: normalize to lowercase, then derive canonical on/off for sendEvent and C3 gate.
    String v = (onOff as String).trim().toLowerCase()
    String canon = canonOnOff(v)
    // C3 state-change gate: suppress redundant cloud calls when value already matches attribute.
    if (device.currentValue("childLock") == canon) return
    Integer sw = (canon == "on") ? 1 : 0
    def resp = hubBypass("setChildLock", [childLockSwitch: sw], "setChildLock(${canon})")
    if (httpOk(resp)) {
        device.sendEvent(name:"childLock", value: canon)
        logInfo "Child lock: ${canon}"
    } else {
        reportWriteFailure("Child lock write failed", resp, [method:"setChildLock"])
    }
}

// ---------- Nightlight ----------
// VeSyncAirBypass set_nightlight_mode: setNightLight {night_light: 'on'/'off'/'dim'}
// NOTE: This uses a different API endpoint from the Sprout Humidifier nightlight.
// Purifier nightlight: setNightLight with string enum {'on', 'off', 'dim'}.
// Humidifier nightlight: setLightStatus with {brightness, colorTemperature, nightLightSwitch}.
// These are different inherited methods from different base classes.
//
// No C3 idempotency gate: this is a three-state enum setter ("on"/"off"/"dim"), not a
// boolean on/off toggle. The nightlightOn attribute stores all three states, so same-state
// suppression would require comparing the full enum value — which is valid in principle
// but this method is not classified as an on/off setter in the C3 gate scope.
def setNightlightMode(nlMode){
    logDebug "setNightlightMode(${nlMode})"
    if (!requireNonEmptyEnum(nlMode, "setNightlightMode")) return
    String m = (nlMode as String).trim().toLowerCase()
    if (!(m in ["on","off","dim"])) { logError "Invalid nightlight mode: ${m} -- must be: on, off, dim"; recordError("Invalid nightlight mode: ${m}", [method:"setNightLight"]); return }
    def resp = hubBypass("setNightLight", [night_light: m], "setNightLight(${m})")
    if (httpOk(resp)) {
        device.sendEvent(name:"nightlightOn", value: m)   // stores "on", "off", or "dim"
        logInfo "Nightlight: ${m}"
    } else {
        reportWriteFailure("Nightlight write failed: ${m}", resp, [method:"setNightLight"])
    }
}

// ---------- Refresh ----------
def refresh(){ update() }

// ---------- Update / status ----------
def update(){
    logDebug "update() self-fetch"
    def resp = hubBypass("getPurifierStatus", [:], "update")
    if (httpOk(resp)) {
        def status = resp?.data
        if (!status?.result) { logError "No status returned from getPurifierStatus"; recordError("No status returned from getPurifierStatus", [method:"update"]) }
        else applyStatus(status)
    }
}

// 1-arg parent callback
def update(status){
    logDebug "update() from parent (1-arg)"
    applyStatus(status)
    return true
}

// 2-arg parent callback -- REQUIRED (BP#1); parent always calls with two args
def update(status, nightLight){
    logDebug "update() from parent (2-arg, nightLight ignored -- Sprout Air has no external night-light child)"
    applyStatus(status)
    return true
}

// ---------- applyStatus ----------
def applyStatus(status){
    logDebug "applyStatus()"

    // BP16 watchdog: auto-disable debugOutput after 30 min even across hub reboots.
    ensureDebugWatchdog()

    seedPrefs()
    def r = peelEnvelope(status)
    // Diagnostic raw dump (debugOutput-gated).
    logDebug "applyStatus raw r keys=${r?.keySet()}, values=${r}"

    // ---- Power ----
    def powerRaw = r.powerSwitch
    boolean powerOn = asBool(powerRaw)
    emitSwitchState(powerOn)

    // ---- Mode ----
    // workMode wire values: 'auto', 'manual', 'sleep' — direct mapping (PurifierModes).
    String rawMode = (r.workMode ?: "manual") as String
    state.mode = rawMode
    device.sendEvent(name:"mode", value: rawMode)

    // ---- Fan speed ----
    // fanSpeedLevel 255 means off (device reports last-set level when off; pyvesync maps 255->0).
    // BP#6 pattern: clamp to 0 when device is off.
    Integer fanSpeedRaw = null
    if (r.fanSpeedLevel != null) {
        fanSpeedRaw = (r.fanSpeedLevel as Integer)
        if (fanSpeedRaw == 255) fanSpeedRaw = 0
    } else if (r.manualSpeedLevel != null) {
        fanSpeedRaw = r.manualSpeedLevel as Integer
    }
    fanSpeedRaw = clampOffLevel(fanSpeedRaw, powerOn)
    if (fanSpeedRaw != null) {
        device.sendEvent(name:"fanSpeed", value: fanSpeedRaw)
        if (fanSpeedRaw > 0) state.lastFanSpeed = fanSpeedRaw
    }

    // ---- FanControl speed (enum) + SwitchLevel level — standard-capability mirrors of fanSpeed ----
    // speed: BP6 power-gated by the !powerOn branch — "off" while the device is off, else the named
    // manual speed (or the mode for auto/sleep). level: mirrors the (already-clamped) fan level, so
    // it reads 0 while off (BP6) and a 0-100 band when running — consistent with fanSpeed.
    if (!powerOn) {
        device.sendEvent(name:"speed", value:"off")
    } else {
        switch (rawMode) {
            case "manual": if (fanSpeedRaw != null) device.sendEvent(name:"speed", value: speedNameFor(fanSpeedRaw)); break
            case "sleep":  device.sendEvent(name:"speed", value:"sleep"); break   // V2 Air: "sleep" is a valid supportedFanSpeeds value (diverges from Vital's speed:"on")
            default:       device.sendEvent(name:"speed", value:"auto");  break
        }
    }
    if (fanSpeedRaw != null) {
        device.sendEvent(name:"level", value: (fanSpeedRaw > 0 ? speedToLevel(fanSpeedRaw) : 0))
    }

    // ---- Air quality sensors ----
    if (r.AQLevel != null) device.sendEvent(name:"airQualityIndex", value: r.AQLevel as Integer)
    if (r.PM25 != null) {
        Integer pm = r.PM25 as Integer
        device.sendEvent(name:"pm25", value: pm)
        // Standard AirQuality-capability attribute: a US-AQI (0-500) derived from PM2.5 via the
        // shared EPA breakpoint ladder (LevoitChildBase.usAqiFromPm25), so this matches the Core
        // purifiers' airQuality semantics exactly. airQualityIndex remains the Levoit 1-4
        // categorical level; the custom `aqi` attribute (Levoit's own index, r.AQI) is unchanged below.
        // E4: emit the derived airQuality only on a PM2.5 CHANGE (mirrors CoreAQPurifierLib's
        // state.prevPM gate) — avoids a redundant airQuality event every poll when PM is steady.
        if (state.prevPM == null || state.prevPM != pm) {
            state.prevPM = pm
            def usAqi = usAqiFromPm25(pm)
            if (usAqi != null) device.sendEvent(name:"airQuality", value: usAqi)
        }
    }
    if (r.PM1  != null)  device.sendEvent(name:"pm1",   value: r.PM1   as Integer)
    if (r.PM10 != null)  device.sendEvent(name:"pm10",  value: r.PM10  as Integer)
    if (r.AQI  != null)  device.sendEvent(name:"aqi",   value: r.AQI   as Integer)
    if (r.VOC  != null)  device.sendEvent(name:"voc",   value: r.VOC   as Integer)
    if (r.CO2  != null)  device.sendEvent(name:"co2",   value: r.CO2   as Integer)

    // ---- Humidity ----
    if (r.humidity != null) device.sendEvent(name:"humidity", value: r.humidity as Integer)

    // ---- Temperature (divided by 10) ----
    if (r.temperature != null) {
        Integer tempRaw = r.temperature as Integer
        // API gives F × 10; emitTemperature (LevoitChildBase) converts to the hub scale.
        // Skip 0 raw (sensor absent / not yet warmed up).
        if (tempRaw != 0) emitTemperature(tempRaw)
    }

    // ---- Display ----
    // Prefer screenState (actual) over screenSwitch (config).
    def displayRaw = r.screenState != null ? r.screenState : r.screenSwitch
    if (displayRaw != null) {
        boolean displayOn = asBool(displayRaw)
        device.sendEvent(name:"displayOn", value: displayOn ? "on" : "off")
    }

    // ---- Child lock ----
    def childLockRaw = r.childLockSwitch
    if (childLockRaw != null) {
        boolean childLock = asBool(childLockRaw)
        device.sendEvent(name:"childLock", value: childLock ? "on" : "off")
    }

    // ---- Nightlight (passive read) ----
    // nightlight is a nested map: {nightLightSwitch: bool/int, brightness: int}
    // nightLightSwitch can be bool (PurifierNightlight model uses bool field).
    def nl = r?.nightlight
    if (nl instanceof Map) {
        def nlSwitchRaw = nl.nightLightSwitch
        def nlBrightRaw = nl.brightness
        // nightlightOn is a TRI-STATE enum ("on"/"off"/"dim") set by setNightlightMode. The response
        // only carries the boolean nightLightSwitch + a brightness, so map the boolean alone would
        // clobber a "dim" back to "on" every poll. Reconstruct the tri-state: off when the light is
        // off, dim when it is on at reduced brightness, on at full brightness.
        if (nlSwitchRaw != null || nlBrightRaw != null) {
            boolean nlOn = asBool(nlSwitchRaw)
            Integer nlBright = (nlBrightRaw != null) ? (nlBrightRaw as Integer) : null
            String nlMode
            if (!nlOn || nlBright == 0)             nlMode = "off"
            // NOTE: the dim-vs-on boundary (brightness < 100 => "dim") is INFERRED and
            // hardware-unconfirmed — pending live/A2 confirmation. Do not tighten it here.
            else if (nlBright != null && nlBright < 100) nlMode = "dim"
            else                                    nlMode = "on"
            device.sendEvent(name:"nightlightOn", value: nlMode)
            // Brightness: report 0 when the light is off so the dashboard never shows an
            // "off" nightlight with a stale positive brightness (VeSync retains the last
            // brightness across an off). Emit the reported value otherwise.
            if (nlBrightRaw != null) {
                Integer reportBright = (nlMode == "off") ? 0 : (nlBrightRaw as Integer)
                device.sendEvent(name:"nightlightBrightness", value: reportBright)
            }
        }
    }

    // ---- Info HTML (local variables only — avoids device.currentValue race; BP#7) ----
    def parts = []
    if (r.AQLevel != null) parts << "AQ: ${r.AQLevel as Integer}"
    if (r.PM25    != null) parts << "PM2.5: ${r.PM25 as Integer}µg/m³"
    if (r.humidity != null) parts << "Humidity: ${r.humidity as Integer}%"
    parts << "Mode: ${rawMode}"
    if (fanSpeedRaw != null) parts << "Fan: ${fanSpeedRaw}"
    device.sendEvent(name:"info", value: parts.join("<br>"))
}

// logDebug, logError, logWarn, logInfo, logDebugOff, ensureDebugWatchdog
// are provided by #include level99.LevoitChildBase (LevoitChildBaseLib.groovy).

// hubBypass, httpOk are provided by #include level99.LevoitChildBase (LevoitChildBaseLib.groovy).

// ------------- END -------------
