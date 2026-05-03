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
        name: "Levoit Sprout Air Purifier",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "[PREVIEW v2.3] Levoit Sprout Air Purifier (LAP-B851S-WUS/-WEU/-AEUR/-AUS/-WNA, LAP-BAY-MAX01S) — fan 1-3, auto/sleep/manual modes, AQ sensors (AQLevel/PM2.5/PM1/PM10/AQI/VOC/CO2), child lock, display, nightlight (on/off/dim). pyvesync VeSyncAirSprout class (VeSyncAirBaseV2). V2-style payloads. No timer.",
        version: "2.4.1",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
    {
        capability "Switch"
        capability "AirQuality"                     // airQualityIndex attribute
        capability "RelativeHumidityMeasurement"    // humidity attribute
        capability "TemperatureMeasurement"         // temperature attribute
        capability "Sensor"
        capability "Actuator"
        capability "Refresh"

        attribute "mode",             "string"      // auto | sleep | manual
        attribute "fanSpeed",         "number"      // 0-3 (0 = inactive when off)
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
// Different from VeSyncAirBypass (Core line): {switch: 'on'/'off', id: 0}
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
// workMode wire values: 'auto', 'sleep', 'manual' (PurifierModes constants).
// CRITICAL: manual mode is established via setLevel (fan speed), NOT setPurifierMode.
// Sending setPurifierMode {workMode:'manual'} returns inner code -1 on this class.
// Per pyvesync VeSyncAirBaseV2.set_mode(): when mode==MANUAL, delegates to set_fan_speed(1).
// This driver mirrors that: setMode("manual") calls setFanSpeed(1) instead.
def setMode(mode){
    logDebug "setMode(${mode})"
    if (mode == null) { logWarn "setMode called with null mode (likely empty Rule Machine action parameter); ignoring"; return }
    String m = (mode as String).toLowerCase()
    if (!(m in ["auto","sleep","manual"])) { logError "Invalid mode: ${m} -- must be: auto, sleep, manual"; recordError("Invalid mode: ${m}", [method:"setPurifierMode"]); return }
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
// Range: 1-3 (device_map.py fan_levels=[1,2,3]).
// Setting a fan speed implicitly sets mode to manual.
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
// VeSyncAirBaseV2 toggle_display: {screenSwitch: int}
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
// VeSyncAirBaseV2 toggle_child_lock: {childLockSwitch: int}
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

// ---------- Nightlight ----------
// VeSyncAirBypass set_nightlight_mode: setNightLight {night_light: 'on'/'off'/'dim'}
// NOTE: This uses a different API endpoint from the Sprout Humidifier nightlight.
// Purifier nightlight: setNightLight with string enum {'on', 'off', 'dim'}.
// Humidifier nightlight: setLightStatus with {brightness, colorTemperature, nightLightSwitch}.
// These are different inherited methods from different base classes.
def setNightlightMode(nlMode){
    logDebug "setNightlightMode(${nlMode})"
    if (nlMode == null) { logWarn "setNightlightMode called with null nlMode (likely empty Rule Machine action parameter); ignoring"; return }
    String m = (nlMode as String).toLowerCase()
    if (!(m in ["on","off","dim"])) { logError "Invalid nightlight mode: ${m} -- must be: on, off, dim"; recordError("Invalid nightlight mode: ${m}", [method:"setNightLight"]); return }
    def resp = hubBypass("setNightLight", [night_light: m], "setNightLight(${m})")
    if (httpOk(resp)) {
        device.sendEvent(name:"nightlightOn", value: m)   // stores "on", "off", or "dim"
        logInfo "Nightlight: ${m}"
    } else {
        logError "Nightlight write failed: ${m}"; recordError("Nightlight write failed: ${m}", [method:"setNightLight"])
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
    if (!powerOn && fanSpeedRaw != null && fanSpeedRaw > 0) fanSpeedRaw = 0
    if (fanSpeedRaw != null) {
        device.sendEvent(name:"fanSpeed", value: fanSpeedRaw)
        if (fanSpeedRaw > 0) state.lastFanSpeed = fanSpeedRaw
    }

    // ---- Air quality sensors ----
    if (r.AQLevel != null) {
        device.sendEvent(name:"airQualityIndex", value: r.AQLevel as Integer)
    }
    if (r.PM25 != null)  device.sendEvent(name:"pm25",  value: r.PM25  as Integer)
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
        if (tempRaw != 0) {
            BigDecimal tempF = tempRaw / 10.0
            device.sendEvent(name:"temperature", value: tempF, unit: "°F")
        }
    }

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

    // ---- Nightlight (passive read) ----
    // nightlight is a nested map: {nightLightSwitch: bool/int, brightness: int}
    // nightLightSwitch can be bool (PurifierNightlight model uses bool field).
    def nl = r?.nightlight
    if (nl instanceof Map) {
        def nlSwitchRaw = nl.nightLightSwitch
        if (nlSwitchRaw != null) {
            boolean nlOn = (nlSwitchRaw instanceof Boolean) ? nlSwitchRaw : ((nlSwitchRaw as Integer) == 1)
            device.sendEvent(name:"nightlightOn", value: nlOn ? "on" : "off")
        }
        if (nl.brightness != null) device.sendEvent(name:"nightlightBrightness", value: nl.brightness as Integer)
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
