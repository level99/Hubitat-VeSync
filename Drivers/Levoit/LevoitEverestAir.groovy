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
 *  Levoit EverestAir Air Purifier (LAP-EL551S-WUS / LAP-EL551S-WEU /
 *                                   LAP-EL551S-AEUR / LAP-EL551S-AUS)
 *
 *  Targets:    US:  LAP-EL551S-WUS
 *              EU:  LAP-EL551S-WEU, LAP-EL551S-AEUR
 *              AUS: LAP-EL551S-AUS
 *              All four model codes route to this single driver.
 *  Marketing:  Levoit EverestAir / EverestAir-P (same API class, different marketing SKUs)
 *  pyvesync:   VeSyncAirBaseV2 class (same base class as Vital 200S / Vital 100S / Sprout Air).
 *              No separate VeSyncAirEverest class exists — device_map.py maps all
 *              LAP-EL551S model codes to VeSyncAirBaseV2 directly.
 *  Reference:  pyvesync device_map.py + devices/vesyncpurifier.py VeSyncAirBaseV2 class
 *              https://github.com/webdjoe/pyvesync
 *
 *  CROSS-CHECK [pyvesync device_map.py / VeSyncAirBaseV2 class]:
 *    How the EverestAir differs from all sibling VeSyncAirBaseV2 purifiers:
 *
 *    vs Vital 200S (LAP-V201S) / Vital 100S (LAP-V102S):
 *      - EverestAir ADDS: TURBO mode ("turbo" workMode via setPurifierMode)
 *        Vital line has: auto/manual/sleep/pet only.
 *      - EverestAir ADDS: VENT_ANGLE feature (fanRotateAngle response field).
 *        pyvesync exposes fanRotateAngle as a READ-ONLY status field in _set_state().
 *        No setter method exists in pyvesync (searched devices/vesyncpurifier.py —
 *        no setFanRotateAngle / setVentAngle / fan_rotate setter anywhere in the class
 *        hierarchy). ventAngle is therefore a PASSIVE READ-ONLY attribute.
 *        Future firmware may add a write path — if a user captures a working payload
 *        via captureDiagnostics(), a setVentAngle command can be added at that time.
 *      - EverestAir ADDS: LIGHT_DETECT feature (same as Vital 200S; toggle_light_detection
 *        via setLightDetection {lightDetectionSwitch: int}).
 *        Vital 100S intentionally omits LIGHT_DETECT despite API field being present.
 *      - Fan level range: 1-3 (same as Sprout Air; Vital 200S has 1-4).
 *      - Modes: auto, manual, sleep, TURBO (Vital line: auto, manual, sleep, pet).
 *      - No pet mode (PurifierModes.PET not in device_map.py entry).
 *
 *    vs VeSyncAirBypass (Core 200S/300S/400S/600S — old API):
 *      - Switch: {powerSwitch: int, switchIdx: 0} NOT {switch: 'on'/'off', id: 0}
 *      - Mode: setPurifierMode {workMode: str} NOT setPurifierMode {mode: str}
 *      - Fan speed: setLevel {levelIdx:0, manualSpeedLevel:N, levelType:'wind'}
 *        NOT setLevel {level:N, id:0, type:'wind'}
 *
 *    vs VeSyncAirSprout (Sprout Air LAP-B851S):
 *      - EverestAir has TURBO + VENT_ANGLE + LIGHT_DETECT; Sprout Air has NIGHTLIGHT instead.
 *      - Both have AIR_QUALITY (AQLevel, PM25) and fan levels 1-3.
 *      - Sprout Air has nightlight attribute; EverestAir has ventAngle + lightDetection.
 *
 *    TURBO MODE CONVENTION (first-of-kind in this codebase):
 *      TURBO is a workMode value ("turbo") handled by setPurifierMode exactly like auto/sleep.
 *      pyvesync VeSyncAirBaseV2.set_mode() accepts any value in self.modes — for EverestAir
 *      that includes PurifierModes.TURBO = "turbo". The fixture (EL551S.yaml) confirms:
 *        set_turbo_mode -> payload: {method: setPurifierMode, data: {workMode: turbo}}
 *      TURBO does not have a separate toggle method in pyvesync (no turn_on_turbo()).
 *      Hubitat driver: setMode("turbo") → setPurifierMode {workMode:"turbo"}.
 *      This sets the convention: for all future drivers where turbo is a mode value,
 *      use setMode("turbo") not a separate setTurbo command.
 *
 *    VENT_ANGLE CONVENTION (first-of-kind in this codebase):
 *      PurifierFeatures.VENT_ANGLE = "fan_rotate" in pyvesync const.py.
 *      The response field is `fanRotateAngle` (read by _set_state() into state.fan_rotate_angle).
 *      pyvesync has NO setter method for fan rotation — no setFanRotateAngle, no set_vent_angle,
 *      no turn_on_fan_rotate exists anywhere in the VeSyncAirBaseV2 or VeSyncAirBypass hierarchy.
 *      This driver exposes ventAngle as a NUMBER attribute (degrees or enum bucket — exact
 *      interpretation depends on firmware). If the community discovers a working write payload,
 *      a setVentAngle command can be added in a patch release.
 *
 *  Project:    https://github.com/level99/Hubitat-VeSync
 *
 *  History:
 *    2026-04-29: v2.4  Phase 5 — captureDiagnostics + error ring-buffer via LevoitDiagnosticsLib.
 *    2026-04-28: v2.2.1  Initial release. All 4 LAP-EL551S model codes in a single driver.
 *                        pyvesync VeSyncAirBaseV2 class (same as Vital 200S/Sprout Air).
 *                        TURBO mode (first in codebase) via setPurifierMode {workMode:"turbo"}.
 *                        VENT_ANGLE passive read (fanRotateAngle, no write path in pyvesync).
 *                        LIGHT_DETECT (setLightDetection {lightDetectionSwitch: int}).
 *                        Ships as [PREVIEW] — no maintainer hardware. Built from pyvesync
 *                        VeSyncAirBaseV2 class, device_map.py EverestAir entry, EL551S.yaml
 *                        fixture. See CROSS-CHECK above.
 */

#include level99.LevoitDiagnostics

metadata {
    definition(
        name: "Levoit EverestAir Air Purifier",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "[PREVIEW v2.3] Levoit EverestAir Air Purifier (LAP-EL551S-WUS/-WEU/-AEUR/-AUS) — fan 1-3, auto/sleep/manual/turbo modes, AQ sensors (AQLevel/PM2.5), light detection, display, child lock, vent angle (passive read). pyvesync VeSyncAirBaseV2 class. V2-style payloads. First driver in codebase with TURBO mode and VENT_ANGLE attribute.",
        version: "2.4.1",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
    {
        capability "Switch"
        capability "AirQuality"                     // airQualityIndex attribute
        capability "Sensor"
        capability "Actuator"
        capability "Refresh"

        attribute "mode",              "string"     // auto | sleep | manual | turbo
        attribute "fanSpeed",          "number"     // 0-3 (0 = inactive when off; 255 sentinel mapped to 0)
        attribute "airQualityIndex",   "number"     // 1-4 categorical (Levoit AQ level)
        attribute "pm25",              "number"     // PM2.5 µg/m³
        attribute "pm1",               "number"     // PM1.0 µg/m³ (if present in response)
        attribute "pm10",              "number"     // PM10 µg/m³ (if present in response)
        attribute "aqPercent",         "number"     // Levoit's own AQ percent index (AQPercent field)
        attribute "displayOn",         "string"     // on | off
        attribute "childLock",         "string"     // on | off
        attribute "lightDetection",    "string"     // on | off  (LIGHT_DETECT feature — enabled/disabled)
        attribute "lightDetected",     "string"     // yes | no  (passive read — is ambient light detected?)
        attribute "ventAngle",         "number"     // VENT_ANGLE passive read (fanRotateAngle from response; no write path in pyvesync)
        attribute "filterLife",        "number"     // filter life %
        attribute "timerRemain",       "number"     // timer remaining (seconds)
        attribute "info",              "string"     // HTML tile summary

        // TURBO MODE: setMode accepts "turbo" as a valid value. This is the first driver
        // in this codebase with turbo mode. The enum constraint lists all 4 modes.
        command "setMode",             [[name:"Mode*", type:"ENUM", constraints:["auto","sleep","manual","turbo"]]]
        command "setFanSpeed",         [[name:"Speed*", type:"NUMBER", description:"1-3"]]
        command "setDisplay",          [[name:"On/Off*", type:"ENUM", constraints:["on","off"]]]
        command "setChildLock",        [[name:"On/Off*", type:"ENUM", constraints:["on","off"]]]
        // LIGHT_DETECT: same endpoint as Vital 200S — setLightDetection {lightDetectionSwitch: int}
        command "setLightDetection",   [[name:"On/Off*", type:"ENUM", constraints:["on","off"]]]
        command "resetFilter"
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
    state.driverVersion = "2.4.1"
    runIn(3, "refresh")
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
// VeSyncAirBaseV2 toggle_switch: {powerSwitch: int, switchIdx: 0}
// V2-style convention — different from VeSyncAirBypass Core line: {switch: 'on'/'off', id: 0}
def on(){
    logDebug "on()"
    def resp = hubBypass("setSwitch", [powerSwitch: 1, switchIdx: 0], "setSwitch(powerSwitch=1)")
    if (httpOk(resp)) { state.lastSwitchSet = "on"; device.sendEvent(name:"switch", value:"on"); logInfo "Power on" }
    else { logError "Power on failed"; recordError("Power on failed", [method:"setSwitch"]) }
}

def off(){
    logDebug "off()"
    def resp = hubBypass("setSwitch", [powerSwitch: 0, switchIdx: 0], "setSwitch(powerSwitch=0)")
    if (httpOk(resp)) { state.lastSwitchSet = "off"; device.sendEvent(name:"switch", value:"off"); logInfo "Power off" }
    else { logError "Power off failed"; recordError("Power off failed", [method:"setSwitch"]) }
}

def toggle(){
    logDebug "toggle()"
    String current = state.lastSwitchSet ?: device.currentValue("switch")
    current == "on" ? off() : on()
}

// ---------- Mode ----------
// VeSyncAirBaseV2 set_mode: {workMode: mode} via setPurifierMode.
// workMode wire values: 'auto', 'sleep', 'turbo' (PurifierModes constants).
// CRITICAL: manual mode is established via setLevel (fan speed), NOT setPurifierMode.
// Per pyvesync VeSyncAirBaseV2.set_mode(): when mode==MANUAL, delegates to set_fan_speed(1).
// Sending setPurifierMode {workMode:'manual'} returns inner code -1 on this class.
//
// TURBO: handled identically to auto/sleep — setPurifierMode {workMode:"turbo"}.
// No separate turbo toggle method exists in pyvesync (confirmed by source search).
// This is the canonical convention for turbo-as-mode going forward in this codebase.
def setMode(mode){
    logDebug "setMode(${mode})"
    if (mode == null) { logWarn "setMode called with null mode (likely empty Rule Machine action parameter); ignoring"; return }
    String m = (mode as String).toLowerCase()
    if (!(m in ["auto","sleep","manual","turbo"])) {
        logError "Invalid mode: ${m} -- must be: auto, sleep, manual, turbo"
        recordError("Invalid mode: ${m}", [method:"setPurifierMode"])
        return
    }
    if (m == "manual") {
        // Manual established by setting fan speed (same as pyvesync VeSyncAirBaseV2.set_mode(MANUAL))
        setFanSpeed(state.lastFanSpeed ?: 1)
        return
    }
    def resp = hubBypass("setPurifierMode", [workMode: m], "setPurifierMode(${m})")
    if (httpOk(resp)) {
        state.mode = m
        device.sendEvent(name:"mode", value: m)
        logInfo "Mode: ${m}"
    } else {
        logError "Mode write failed: ${m}"; recordError("Mode write failed: ${m}", [method:"setPurifierMode"])
    }
}

// ---------- Fan speed ----------
// VeSyncAirBaseV2 set_fan_speed: setLevel {levelIdx:0, manualSpeedLevel:N, levelType:'wind'}
// Range: 1-3 (device_map.py fan_levels=[1,2,3] for EverestAir).
// Setting a fan speed implicitly establishes manual mode.
def setFanSpeed(speed){
    logDebug "setFanSpeed(${speed})"
    Integer spd = Math.max(1, Math.min(3, (speed as Integer) ?: 1))
    def resp = hubBypass("setLevel", [levelIdx: 0, manualSpeedLevel: spd, levelType: "wind"], "setLevel(wind,${spd})")
    if (httpOk(resp)) {
        state.lastFanSpeed = spd
        state.mode = "manual"
        device.sendEvent(name:"fanSpeed", value: spd)
        device.sendEvent(name:"mode",     value: "manual")
        logInfo "Fan speed: ${spd}, mode: manual"
    } else {
        logError "Fan speed write failed: ${spd}"; recordError("Fan speed write failed: ${spd}", [method:"setLevel"])
    }
}

// ---------- Display ----------
// VeSyncAirBaseV2 toggle_display: setDisplay {screenSwitch: int}
def setDisplay(onOff){
    logDebug "setDisplay(${onOff})"
    Integer v = (onOff == "on") ? 1 : 0
    def resp = hubBypass("setDisplay", [screenSwitch: v], "setDisplay(${onOff})")
    if (httpOk(resp)) {
        device.sendEvent(name:"displayOn", value: onOff)
        logInfo "Display: ${onOff}"
    } else {
        logError "Display write failed"; recordError("Display write failed", [method:"setDisplay"])
    }
}

// ---------- Child lock ----------
// VeSyncAirBaseV2 toggle_child_lock: setChildLock {childLockSwitch: int}
def setChildLock(onOff){
    logDebug "setChildLock(${onOff})"
    Integer v = (onOff == "on") ? 1 : 0
    def resp = hubBypass("setChildLock", [childLockSwitch: v], "setChildLock(${onOff})")
    if (httpOk(resp)) {
        device.sendEvent(name:"childLock", value: onOff)
        logInfo "Child lock: ${onOff}"
    } else {
        logError "Child lock write failed"; recordError("Child lock write failed", [method:"setChildLock"])
    }
}

// ---------- Light detection ----------
// LIGHT_DETECT feature flag (PurifierFeatures.LIGHT_DETECT = 'light_detect').
// Same API as Vital 200S: setLightDetection {lightDetectionSwitch: int}
// lightDetection attribute: whether the feature is ON/OFF (user setting).
// lightDetected attribute: whether ambient light is currently detected (passive read from status).
def setLightDetection(onOff){
    logDebug "setLightDetection(${onOff})"
    Integer v = (onOff == "on") ? 1 : 0
    def resp = hubBypass("setLightDetection", [lightDetectionSwitch: v], "setLightDetection(${onOff})")
    if (httpOk(resp)) {
        device.sendEvent(name:"lightDetection", value: onOff)
        logInfo "Light detection: ${onOff}"
    } else {
        logError "Light detection write failed"; recordError("Light detection write failed", [method:"setLightDetection"])
    }
}

// ---------- Reset filter ----------
// VeSyncAirBaseV2 reset_filter: resetFilter {}
def resetFilter(){
    logDebug "resetFilter()"
    def resp = hubBypass("resetFilter", [:], "resetFilter()")
    if (httpOk(resp)) {
        device.sendEvent(name:"filterLife", value: 100)
        logInfo "Filter reset to 100%"
    } else {
        logError "Filter reset failed"; recordError("Filter reset failed", [method:"resetFilter"])
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
    logDebug "update() from parent (2-arg, nightLight ignored -- EverestAir has no night-light child)"
    applyStatus(status)
    return true
}

// ---------- applyStatus ----------
def applyStatus(status){
    logDebug "applyStatus()"

    // BP16 watchdog: auto-disable debugOutput after 30 min even across hub reboots.
    ensureDebugWatchdog()

    // BP12 pref-seed: heal descriptionTextEnable=true default for migrated installs.
    if (!state.prefsSeeded) {
        if (settings?.descriptionTextEnable == null) {
            device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
        }
        state.prefsSeeded = true
    }

    def r = status?.result ?: [:]
    // BP#3: defensive envelope peel — bypassV2 responses can be wrapped in
    // {code, result, traceId} envelope layers. Peel until device data is reached.
    int peelGuard = 0
    while (r instanceof Map && r.containsKey('code') && r.containsKey('result') && r.result instanceof Map && peelGuard < 4) {
        r = r.result
        peelGuard++
    }
    // Diagnostic raw dump (debugOutput-gated).
    if (settings?.debugOutput) log.debug "applyStatus raw r (after peel=${peelGuard}) keys=${r?.keySet()}, values=${r}"

    // ---- Power ----
    def powerRaw = r.powerSwitch
    boolean powerOn = (powerRaw instanceof Boolean) ? powerRaw : ((powerRaw as Integer) == 1)
    device.sendEvent(name:"switch", value: powerOn ? "on" : "off")

    // ---- Mode ----
    // workMode wire values: 'auto', 'manual', 'sleep', 'turbo' (PurifierModes constants).
    // 'turbo' is unique to EverestAir in this codebase — maps 1:1 to the "turbo" attribute value.
    String rawMode = (r.workMode ?: "manual") as String
    state.mode = rawMode
    device.sendEvent(name:"mode", value: rawMode)

    // ---- Fan speed ----
    // fanSpeedLevel 255 means off (device reports last-set level when off; pyvesync maps 255->0).
    // BP#6 analog: clamp to 0 when device is off.
    Integer fanSpeedRaw = null
    if (r.fanSpeedLevel != null) {
        fanSpeedRaw = (r.fanSpeedLevel as Integer)
        if (fanSpeedRaw == 255) fanSpeedRaw = 0
    } else if (r.manualSpeedLevel != null) {
        fanSpeedRaw = r.manualSpeedLevel as Integer
    }
    if (!powerOn && fanSpeedRaw != null && fanSpeedRaw > 0) fanSpeedRaw = 0
    if (fanSpeedRaw != null) {
        device.sendEvent(name:"fanSpeed", value: fanSpeedRaw)
        if (fanSpeedRaw > 0) state.lastFanSpeed = fanSpeedRaw
    }

    // ---- Air quality sensors ----
    if (r.AQLevel  != null) device.sendEvent(name:"airQualityIndex", value: r.AQLevel  as Integer)
    if (r.PM25     != null) device.sendEvent(name:"pm25",            value: r.PM25     as Integer)
    if (r.PM1      != null) device.sendEvent(name:"pm1",             value: r.PM1      as Integer)
    if (r.PM10     != null) device.sendEvent(name:"pm10",            value: r.PM10     as Integer)
    if (r.AQPercent != null) device.sendEvent(name:"aqPercent",      value: r.AQPercent as Integer)

    // ---- Filter life ----
    if (r.filterLifePercent != null) device.sendEvent(name:"filterLife", value: r.filterLifePercent as Integer)

    // ---- Timer ----
    if (r.timerRemain != null) device.sendEvent(name:"timerRemain", value: r.timerRemain as Integer)

    // ---- Display ----
    // Prefer screenState (actual) over screenSwitch (config).
    def displayRaw = r.screenState != null ? r.screenState : r.screenSwitch
    if (displayRaw != null) {
        boolean displayOn = (displayRaw instanceof Boolean) ? displayRaw : ((displayRaw as Integer) == 1)
        device.sendEvent(name:"displayOn", value: displayOn ? "on" : "off")
    }

    // ---- Child lock ----
    def childLockRaw = r.childLockSwitch
    if (childLockRaw != null) {
        boolean childLock = (childLockRaw instanceof Boolean) ? childLockRaw : ((childLockRaw as Integer) == 1)
        device.sendEvent(name:"childLock", value: childLock ? "on" : "off")
    }

    // ---- Light detection (LIGHT_DETECT feature) ----
    // lightDetectionSwitch: whether the light-detect feature is enabled (user preference).
    // environmentLightState: whether ambient light is currently sensed (passive read).
    def ldSwitch = r.lightDetectionSwitch
    if (ldSwitch != null) {
        boolean ldOn = (ldSwitch instanceof Boolean) ? ldSwitch : ((ldSwitch as Integer) == 1)
        device.sendEvent(name:"lightDetection", value: ldOn ? "on" : "off")
    }
    def ldState = r.environmentLightState
    if (ldState != null) {
        boolean ldDetected = (ldState instanceof Boolean) ? ldState : ((ldState as Integer) == 1)
        device.sendEvent(name:"lightDetected", value: ldDetected ? "yes" : "no")
    }

    // ---- Vent angle (VENT_ANGLE feature — passive read only) ----
    // VENT_ANGLE convention (first-of-kind in this codebase):
    // pyvesync _set_state() reads: self.state.fan_rotate_angle = details.fanRotateAngle
    // No setter method exists in pyvesync — fanRotateAngle is status-only.
    // Exposed as NUMBER attribute for observation; exact unit (degrees vs enum) TBD by community.
    if (r.fanRotateAngle != null) {
        device.sendEvent(name:"ventAngle", value: r.fanRotateAngle as Integer)
    }

    // ---- Info HTML (local variables only — avoids device.currentValue race; BP#7) ----
    def parts = []
    if (r.AQLevel != null) parts << "AQ: ${r.AQLevel as Integer}"
    if (r.PM25    != null) parts << "PM2.5: ${r.PM25 as Integer}µg/m³"
    parts << "Mode: ${rawMode}"
    if (fanSpeedRaw != null) parts << "Fan: ${fanSpeedRaw}"
    if (r.filterLifePercent != null) parts << "Filter: ${r.filterLifePercent as Integer}%"
    if (r.fanRotateAngle != null) parts << "Vent: ${r.fanRotateAngle as Integer}°"
    device.sendEvent(name:"info", value: parts.join("<br>"))
}

// ---------- Internal helpers ----------
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

// Hub/parent call wrapper — matches sibling driver pattern
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
